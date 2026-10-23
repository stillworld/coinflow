package com.coinflow;

import java.time.Duration;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class ActivityTimelineTest
{
	private static final Duration TICK = Duration.ofMillis(600);
	private static final ActivityTimeline.ActivityClass VORKATH =
		ActivityTimeline.ActivityClass.combat("Vorkath");
	private static final ActivityTimeline.ActivityClass DAGANNOTH_REX =
		ActivityTimeline.ActivityClass.combat("Dagannoth Rex");
	private static final ActivityTimeline.ActivityClass MINING =
		ActivityTimeline.ActivityClass.skilling("Mining");
	private static final ActivityTimeline.ActivityClass BANKING = ActivityTimeline.ActivityClass.BANKING;
	private static final ActivityTimeline.ActivityClass AFK = ActivityTimeline.ActivityClass.AFK;
	private static final ActivityTimeline.ActivityClass TELEPORT = ActivityTimeline.ActivityClass.TELEPORT;

	private static void tick(ActivityTimeline t, ActivityTimeline.ActivityClass cls)
	{
		t.onTick(cls, TICK, TICK, 0, 0);
	}

	private static void tick(ActivityTimeline t, ActivityTimeline.ActivityClass cls, long gross, long expenses)
	{
		t.onTick(cls, TICK, TICK, gross, expenses);
	}

	private static void idleTicks(ActivityTimeline t, ActivityTimeline.ActivityClass cls, int n)
	{
		for (int i = 0; i < n; i++)
		{
			tick(t, cls);
		}
	}

	@Test
	public void noSignal_sticksToCurrentSegment()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, VORKATH, 21);
		Assert.assertEquals(VORKATH.label, t.getCurrentSegment().label);

		idleTicks(t, null, 200);
		Assert.assertEquals(1, t.getSegments().size());
		Assert.assertEquals(VORKATH.label, t.getCurrentSegment().label);
	}

	@Test
	public void shortCandidate_foldsBackIntoCurrent()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, VORKATH, 21);
		idleTicks(t, BANKING, 4); // below QUICK_CONFIRM_TICKS
		idleTicks(t, VORKATH, 10);

		Assert.assertEquals(1, t.getSegments().size());
		// pending time folded back: 21 + 4 + 10 ticks
		Assert.assertEquals(TICK.multipliedBy(35), t.getCurrentSegment().totalTime);
	}

	@Test
	public void confirmedCandidate_opensBackdatedSegment()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, VORKATH, 21);
		for (int i = 0; i < 20; i++)
		{
			tick(t, MINING, 1000, 0); // gross rises during pending
		}

		List<ActivityTimeline.Segment> segs = t.getSegments();
		Assert.assertEquals(2, segs.size());
		Assert.assertEquals("Mining", segs.get(1).label);
		// Pending ticks back-dated into the new segment
		Assert.assertEquals(TICK.multipliedBy(20), segs.get(1).totalTime);
		Assert.assertEquals(1000L, segs.get(1).gross);
		Assert.assertEquals(TICK.multipliedBy(21), segs.get(0).totalTime);
		Assert.assertEquals(VORKATH.label, segs.get(0).label);
	}

	@Test
	public void afkAndBanking_useQuickConfirm()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, VORKATH, 21);
		idleTicks(t, AFK, 5);
		Assert.assertEquals("AFK", t.getCurrentSegment().label);

		idleTicks(t, BANKING, 5);
		Assert.assertEquals("Banking / GE", t.getCurrentSegment().label);
	}

	@Test
	public void thirdClassFlip_resetsPendingAndFoldsOldCandidate()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, VORKATH, 21);
		idleTicks(t, MINING, 5);   // pending Mining
		idleTicks(t, BANKING, 5);  // candidate flips; Mining folds into Vorkath; Banking confirms quickly

		List<ActivityTimeline.Segment> segs = t.getSegments();
		Assert.assertEquals(2, segs.size());
		Assert.assertEquals(VORKATH.label, segs.get(0).label);
		Assert.assertEquals(TICK.multipliedBy(26), segs.get(0).totalTime);
		Assert.assertEquals("Banking / GE", segs.get(1).label);
		Assert.assertEquals(TICK.multipliedBy(5), segs.get(1).totalTime);
	}

	@Test
	public void kills_goToPendingOrCurrentCombat()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, MINING, 21);
		// Kill during pending combat (combat just started, not yet confirmed)
		idleTicks(t, VORKATH, 3);
		t.recordKill();
		// Kill dropped while non-combat
		t.reset();
		idleTicks(t, MINING, 21);
		t.recordKill();
		Assert.assertEquals(0, t.getCurrentSegment().kills);
	}

	@Test
	public void kill_attachedToPendingSurvivesConfirm()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, MINING, 21);
		tick(t, VORKATH);
		t.recordKill();
		idleTicks(t, VORKATH, 19);
		Assert.assertEquals(VORKATH.label, t.getCurrentSegment().label);
		Assert.assertEquals(1, t.getCurrentSegment().kills);
	}

	@Test
	public void kill_ignoresLabelMismatch()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, VORKATH, 21);
		idleTicks(t, DAGANNOTH_REX, 3); // pending different NPC
		t.recordKill(); // lands in pending combat segment
		idleTicks(t, DAGANNOTH_REX, 17);
		Assert.assertEquals(1, t.getCurrentSegment().kills);
		Assert.assertEquals("Dagannoth Rex", t.getCurrentSegment().label);
	}

	@Test
	public void rebase_attributesNothing()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, VORKATH, 21);
		tick(t, VORKATH, 1000, 200);
		// User removes a row: gross drops to 400 — rebase means no -600 lands on the segment
		t.rebase(400, 200);
		for (int i = 0; i < 3; i++)
		{
			tick(t, VORKATH, 400, 200);
		}
		Assert.assertEquals(1000L, t.getCurrentSegment().gross);
		Assert.assertEquals(200L, t.getCurrentSegment().expenses);
	}

	@Test
	public void segmentTotals_matchSessionTotals()
	{
		ActivityTimeline t = new ActivityTimeline();
		for (int i = 0; i < 21; i++)
		{
			tick(t, VORKATH, 500, 100);
		}
		for (int i = 0; i < 25; i++)
		{
			tick(t, MINING, 700, 150);
		}
		long grossSum = 0, expSum = 0, timeSum = 0;
		for (ActivityTimeline.Segment s : t.getSegments())
		{
			grossSum += s.gross;
			expSum += s.expenses;
			timeSum += s.totalTime.toMillis();
		}
		Assert.assertEquals(700, grossSum);
		Assert.assertEquals(150, expSum);
		Assert.assertEquals(TICK.multipliedBy(46).toMillis(), timeSum);
	}

	@Test
	public void perKillAndRate_math()
	{
		ActivityTimeline t = new ActivityTimeline();
		for (int i = 0; i < 21; i++)
		{
			tick(t, VORKATH, 3600000, 720000);
		}
		t.recordKill();
		tick(t, VORKATH, 3600000, 720000); // attribute kill before final tick lands it on current
		ActivityTimeline.Segment seg = t.getCurrentSegment();
		Assert.assertEquals(1, seg.kills);
		Assert.assertEquals(3600000 - 720000, seg.net());
		Assert.assertEquals(seg.net(), seg.netPerKill());
		Assert.assertEquals(720000, seg.expensesPerKill());
		// ~13.2s active: gp/hr is net*3600/13
		Assert.assertTrue(seg.gpPerHour(true) > 0);
		Assert.assertTrue(seg.gpPerHour(false) > 0);
	}

	@Test
	public void reset_clearsEverything()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, VORKATH, 25);
		int rev = t.getRevision();
		t.reset();
		Assert.assertTrue(t.getSegments().isEmpty());
		Assert.assertTrue(t.getRevision() > rev);
		idleTicks(t, MINING, 1);
		Assert.assertEquals(1, t.getSegments().size());
	}

	@Test
	public void publishedList_isImmutableAndStable()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, VORKATH, 21);
		List<ActivityTimeline.Segment> segs = t.getSegments();
		try
		{
			segs.add(null);
			Assert.fail("published list must be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
		}
		// A later tick returns a new list instance
		tick(t, VORKATH);
		Assert.assertNotSame(segs, t.getSegments());
	}

	@Test
	public void aggregateByLabel_mergesRepeatedActivities()
	{
		ActivityTimeline t = new ActivityTimeline();
		for (int i = 0; i < 21; i++)
		{
			tick(t, VORKATH, 100, 0);
		}
		for (int i = 0; i < 21; i++)
		{
			tick(t, MINING, 100, 0);
		}
		for (int i = 0; i < 21; i++)
		{
			tick(t, VORKATH, 400, 0);
		}
		List<ActivityTimeline.Aggregate> aggs = t.aggregateByLabel();
		Assert.assertEquals(2, aggs.size());
		Assert.assertEquals(VORKATH.label, aggs.get(0).label);
		Assert.assertEquals(TICK.multipliedBy(42), aggs.get(0).totalTime);
		Assert.assertEquals(400, aggs.get(0).gross);
		Assert.assertEquals("Mining", aggs.get(1).label);
		Assert.assertEquals(TICK.multipliedBy(21), aggs.get(1).totalTime);
	}

	@Test
	public void shortBankingGapBetweenSameActivity_foldsIntoRun()
	{
		ActivityTimeline t = new ActivityTimeline();
		for (int i = 0; i < 21; i++)
		{
			tick(t, VORKATH, 1000, 200);
		}
		for (int i = 0; i < 60; i++)
		{
			tick(t, BANKING, 1000, 500); // 36s bank trip — confirmed, then folded
		}
		for (int i = 0; i < 21; i++)
		{
			tick(t, VORKATH, 3000, 600);
		}

		List<ActivityTimeline.Segment> segs = t.getSegments();
		Assert.assertEquals(1, segs.size());
		ActivityTimeline.Segment seg = segs.get(0);
		Assert.assertEquals(VORKATH.label, seg.label);
		// Gap time and gp fold into the run: 21 + 60 + 21 ticks
		Assert.assertEquals(TICK.multipliedBy(102), seg.totalTime);
		Assert.assertEquals(3000, seg.gross);
		Assert.assertEquals(600, seg.expenses);
	}

	@Test
	public void longBankingGapBetweenSameActivity_staysSeparate()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, VORKATH, 21);
		idleTicks(t, BANKING, 310); // >3min gap — a real bank session
		idleTicks(t, VORKATH, 21);

		List<ActivityTimeline.Segment> segs = t.getSegments();
		Assert.assertEquals(3, segs.size());
		Assert.assertEquals(VORKATH.label, segs.get(0).label);
		Assert.assertEquals("Banking / GE", segs.get(1).label);
		Assert.assertEquals(VORKATH.label, segs.get(2).label);
	}

	@Test
	public void gapBetweenDifferentActivities_neverFolds()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, VORKATH, 21);
		idleTicks(t, BANKING, 10);
		idleTicks(t, DAGANNOTH_REX, 21);

		List<ActivityTimeline.Segment> segs = t.getSegments();
		Assert.assertEquals(3, segs.size());
		Assert.assertEquals("Banking / GE", segs.get(1).label);
		Assert.assertEquals("Dagannoth Rex", segs.get(2).label);
	}

	@Test
	public void teleport_usesQuickConfirm()
	{
		ActivityTimeline t = new ActivityTimeline();
		idleTicks(t, VORKATH, 21);
		idleTicks(t, TELEPORT, 5);
		Assert.assertEquals("Teleport", t.getCurrentSegment().label);
	}

	@Test
	public void shortTeleportGapBetweenSameActivity_foldsIntoRun()
	{
		// [Mining, Teleport, Mining] — the hop merges like a bank trip.
		ActivityTimeline t = new ActivityTimeline();
		for (int i = 0; i < 21; i++)
		{
			tick(t, MINING, 1000, 0);
		}
		idleTicks(t, TELEPORT, 5);
		for (int i = 0; i < 21; i++)
		{
			tick(t, MINING, 2000, 0);
		}

		List<ActivityTimeline.Segment> segs = t.getSegments();
		Assert.assertEquals(1, segs.size());
		ActivityTimeline.Segment seg = segs.get(0);
		Assert.assertEquals("Mining", seg.label);
		Assert.assertEquals(TICK.multipliedBy(47), seg.totalTime);
		Assert.assertEquals(2000, seg.gross);
	}
}
