package com.coinflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Live session timeline: diffs session gross/expense totals tick-to-tick and
 * attributes the delta (plus active/total time and kills) to the activity the
 * player was doing. The classifier in {@link ActivityMonitor} emits an
 * {@link ActivityClass} (or null for "no signal" — the current activity stays
 * sticky); a different observed class only becomes a new segment after a
 * confirmation window so brief interludes (a 2-second bank peek, a stray
 * skilling click) fold back into the running segment instead of splitting it.
 *
 * Reads happen on the Swing EDT / overlay thread while writes happen on the
 * game thread, so the segment list is published as a volatile immutable
 * snapshot each tick rather than exposed live.
 */
@Slf4j
final class ActivityTimeline
{
	enum Kind
	{
		COMBAT, SKILLING, BLAST_FURNACE, BANKING, AFK, TELEPORT, OTHER
	}

	/** Classifier output: which activity, plus a display label (NPC or skill name). */
	static final class ActivityClass
	{
		static final ActivityClass BANKING = new ActivityClass(Kind.BANKING, "Banking / GE");
		static final ActivityClass BLAST_FURNACE = new ActivityClass(Kind.BLAST_FURNACE, "Blast Furnace");
		static final ActivityClass AFK = new ActivityClass(Kind.AFK, "AFK");
		static final ActivityClass TELEPORT = new ActivityClass(Kind.TELEPORT, "Teleport");

		final Kind kind;
		final String label;

		private ActivityClass(Kind kind, String label)
		{
			this.kind = kind;
			this.label = label;
		}

		static ActivityClass combat(String npcName)
		{
			return new ActivityClass(Kind.COMBAT, npcName);
		}

		static ActivityClass skilling(String skillName)
		{
			return new ActivityClass(Kind.SKILLING, skillName);
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof ActivityClass))
			{
				return false;
			}
			ActivityClass other = (ActivityClass) o;
			return kind == other.kind && Objects.equals(label, other.label);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(kind, label);
		}
	}

	/** Immutable published view of one finished or in-progress segment. */
	static final class Segment
	{
		final Kind kind;
		final String label;
		final Duration startOffset;
		final Duration activeTime;
		final Duration totalTime;
		final long gross;
		final long expenses;
		final int kills;

		private Segment(Kind kind, String label, Duration startOffset, Duration activeTime,
			Duration totalTime, long gross, long expenses, int kills)
		{
			this.kind = kind;
			this.label = label;
			this.startOffset = startOffset;
			this.activeTime = activeTime.isNegative() ? Duration.ZERO : activeTime;
			this.totalTime = totalTime.isNegative() ? Duration.ZERO : totalTime;
			this.gross = gross;
			this.expenses = expenses;
			this.kills = kills;
		}

		long net()
		{
			return gross - expenses;
		}

		private Duration effectiveTime(boolean includeAfk)
		{
			return includeAfk ? totalTime : activeTime;
		}

		long gpPerHour(boolean includeAfk)
		{
			long secs = effectiveTime(includeAfk).getSeconds();
			return secs > 0 ? net() * 3600 / secs : 0;
		}

		long netPerKill()
		{
			return kills > 0 ? net() / kills : 0;
		}

		long expensesPerKill()
		{
			return kills > 0 ? expenses / kills : 0;
		}

		long killsPerHour(boolean includeAfk)
		{
			long secs = effectiveTime(includeAfk).getSeconds();
			return secs > 0 ? kills * 3600L / secs : 0;
		}
	}

	/** Same fields as {@link Segment} but summed across all segments of one label. */
	static final class Aggregate
	{
		final Kind kind;
		final String label;
		Duration activeTime = Duration.ZERO;
		Duration totalTime = Duration.ZERO;
		long gross;
		long expenses;
		int kills;

		Aggregate(Kind kind, String label)
		{
			this.kind = kind;
			this.label = label;
		}

		long net()
		{
			return gross - expenses;
		}

		long gpPerHour(boolean includeAfk)
		{
			long secs = (includeAfk ? totalTime : activeTime).getSeconds();
			return secs > 0 ? net() * 3600 / secs : 0;
		}
	}

	/** Mutable per-segment accumulator; published as an immutable {@link Segment}. */
	private static final class Builder
	{
		ActivityClass cls;
		Duration startOffset;
		Duration activeTime = Duration.ZERO;
		Duration totalTime = Duration.ZERO;
		long gross;
		long expenses;
		int kills;
		int ticks;

		Builder(ActivityClass cls)
		{
			this.cls = cls;
		}

		void add(Duration activeDelta, Duration totalDelta, long grossDelta, long expDelta)
		{
			activeTime = activeTime.plus(activeDelta);
			totalTime = totalTime.plus(totalDelta);
			gross += grossDelta;
			expenses += expDelta;
			ticks++;
		}

		void absorb(Builder other)
		{
			activeTime = activeTime.plus(other.activeTime);
			totalTime = totalTime.plus(other.totalTime);
			gross += other.gross;
			expenses += other.expenses;
			kills += other.kills;
		}

		Segment snapshot()
		{
			return new Segment(cls.kind, cls.label, startOffset, activeTime, totalTime, gross, expenses, kills);
		}
	}

	private static final int CONFIRM_TICKS = 20;
	private static final int QUICK_CONFIRM_TICKS = 5;
	/**
	 * A gap segment (bank trip, AFK, unclassified) this short or shorter folds
	 * back into the surrounding run when the same activity resumes — a bank
	 * stop in the middle of a grind is part of the trip, not a new activity.
	 * Longer gaps stay their own row.
	 */
	private static final Duration FOLD_GAP_MAX = Duration.ofMinutes(3);
	private static final ActivityClass BOOTSTRAP = new ActivityClass(Kind.OTHER, "Other");

	private final List<Builder> closed = new ArrayList<>();
	private Builder current;
	private Builder pending;
	private long lastGross;
	private long lastExpenses;
	private volatile List<Segment> published = Collections.emptyList();
	private int revision;

	private static int confirmTicks(ActivityClass cls)
	{
		// AFK/BANKING/TELEPORT are state or event signals rather than inferred
		// work — a teleport is a momentary act, so it confirms fast and the
		// next real activity takes over without a long shadow.
		return (cls.kind == Kind.AFK || cls.kind == Kind.BANKING || cls.kind == Kind.TELEPORT)
			? QUICK_CONFIRM_TICKS : CONFIRM_TICKS;
	}

	/**
	 * Attributes one tick of session deltas to the live segment. {@code observed}
	 * may be null (no activity signal — the current segment keeps accumulating).
	 * {@code gross}/{@code expenses} are the session's absolute totals.
	 */
	void onTick(ActivityClass observed, Duration activeDelta, Duration totalDelta, long gross, long expenses)
	{
		long grossDelta = gross - lastGross;
		long expDelta = expenses - lastExpenses;
		lastGross = gross;
		lastExpenses = expenses;

		if (current == null)
		{
			current = new Builder(BOOTSTRAP);
			current.startOffset = Duration.ZERO;
		}

		if (observed == null || observed.equals(current.cls))
		{
			foldPendingIntoCurrent();
			current.add(activeDelta, totalDelta, grossDelta, expDelta);
		}
		else
		{
			if (pending == null || !pending.cls.equals(observed))
			{
				foldPendingIntoCurrent();
				pending = new Builder(observed);
			}
			pending.add(activeDelta, totalDelta, grossDelta, expDelta);
			if (pending.ticks >= confirmTicks(observed))
			{
				commitPending();
			}
		}

		publish();
	}

	private static boolean isFoldableGap(ActivityClass cls)
	{
		return cls.kind == Kind.BANKING || cls.kind == Kind.AFK || cls.kind == Kind.TELEPORT
			|| cls.kind == Kind.OTHER;
	}

	private void foldPendingIntoCurrent()
	{
		if (pending != null)
		{
			current.absorb(pending);
			pending = null;
		}
	}

	private void commitPending()
	{
		// Fold a short gap flanked by the same activity back into the run:
		// [Renu, Bank 1:13, Renu] becomes one Renu segment covering the whole
		// trip — the row spam of every bank visit splitting the timeline.
		if (isFoldableGap(current.cls)
			&& current.totalTime.compareTo(FOLD_GAP_MAX) <= 0
			&& !closed.isEmpty()
			&& closed.get(closed.size() - 1).cls.equals(pending.cls))
		{
			Builder run = closed.remove(closed.size() - 1);
			run.absorb(current);  // the gap
			run.absorb(pending);  // the resumed stint
			log.debug("Activity segment: {} folded {} ({}s) gap, continues (+{}s, {} gp)",
				pending.cls.label, current.cls.label, current.totalTime.getSeconds(),
				pending.totalTime.getSeconds(), pending.gross - pending.expenses);
			current = run;
			pending = null;
			revision++;
			return;
		}

		// Keep the bootstrap out of the list until it has real content.
		if (current.cls != BOOTSTRAP || current.totalTime.toMillis() > 0
			|| current.gross != 0 || current.expenses != 0 || current.kills > 0)
		{
			// Equal-class neighbors cannot normally occur (pending never matches
			// current), but guard anyway so a rebase edge can't produce dupes.
			if (!closed.isEmpty() && closed.get(closed.size() - 1).cls.equals(current.cls))
			{
				closed.get(closed.size() - 1).absorb(current);
			}
			else
			{
				closed.add(current);
			}
		}
		pending.startOffset = current.startOffset.plus(current.totalTime);
		log.debug("Activity segment: {} -> {} (+{}s, {} gp)", current.cls.label,
			pending.cls.label, pending.totalTime.getSeconds(), pending.gross - pending.expenses);
		current = pending;
		pending = null;
		revision++;
	}

	/**
	 * Credits a kill to the current combat context. The NPC name is deliberately
	 * ignored: mixed-NPC content (Dagannoth Kings, slayer variants) would
	 * undercount under label matching, and a kill landing mid-pending means the
	 * fight started before the classifier confirmed it.
	 * ponytail: label-agnostic attribution; revisit only if cross-activity kills prove noisy.
	 */
	void recordKill()
	{
		if (pending != null && pending.cls.kind == Kind.COMBAT)
		{
			pending.kills++;
		}
		else if (current != null && current.cls.kind == Kind.COMBAT)
		{
			current.kills++;
		}
		else
		{
			return;
		}
		publish();
	}

	/**
	 * Resyncs the session-total baselines after a mutation that is not
	 * activity (user removed/ignored a row), so the change isn't booked
	 * against the live segment.
	 */
	void rebase(long gross, long expenses)
	{
		lastGross = gross;
		lastExpenses = expenses;
	}

	void reset()
	{
		closed.clear();
		current = null;
		pending = null;
		lastGross = 0;
		lastExpenses = 0;
		revision++;
		publish();
	}

	private void publish()
	{
		List<Segment> out = new ArrayList<>(closed.size() + 1);
		for (Builder b : closed)
		{
			out.add(b.snapshot());
		}
		if (current != null)
		{
			out.add(current.snapshot());
		}
		published = Collections.unmodifiableList(out);
	}

	List<Segment> getSegments()
	{
		return published;
	}

	Segment getCurrentSegment()
	{
		List<Segment> segs = published;
		return segs.isEmpty() ? null : segs.get(segs.size() - 1);
	}

	/**
	 * Per-label sums (all segments with the same class collapse into one row),
	 * sorted by total time descending. Computed on demand — callers invoke this
	 * on panel rebuilds / summary open, not per tick.
	 */
	List<Aggregate> aggregateByLabel()
	{
		Map<ActivityClass, Aggregate> map = new LinkedHashMap<>();
		for (Segment seg : published)
		{
			Aggregate agg = map.computeIfAbsent(
				new ActivityClass(seg.kind, seg.label), k -> new Aggregate(k.kind, k.label));
			agg.activeTime = agg.activeTime.plus(seg.activeTime);
			agg.totalTime = agg.totalTime.plus(seg.totalTime);
			agg.gross += seg.gross;
			agg.expenses += seg.expenses;
			agg.kills += seg.kills;
		}
		List<Aggregate> list = new ArrayList<>(map.values());
		list.sort(Comparator.comparing((Aggregate a) -> a.totalTime).reversed());
		return list;
	}

	int getRevision()
	{
		return revision;
	}
}
