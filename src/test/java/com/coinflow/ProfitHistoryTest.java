package com.coinflow;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link ProfitHistory}: interval sampling, capacity downsampling,
 * reset, and the immutable published snapshot.
 */
public class ProfitHistoryTest
{
	private static void tick(ProfitHistory history, long value, int ticks)
	{
		for (int i = 0; i < ticks; i++)
		{
			history.onTick(value);
		}
	}

	@Test
	public void firstTickRecordsImmediately_thenOnInterval()
	{
		ProfitHistory history = new ProfitHistory();
		Assert.assertTrue(history.snapshot().isEmpty());

		history.onTick(100);
		Assert.assertEquals(1, history.snapshot().size());

		// Ticks 2..5 since the last sample don't record; the 5th does.
		tick(history, 200, ProfitHistory.BASE_INTERVAL_TICKS - 1);
		Assert.assertEquals(1, history.snapshot().size());
		history.onTick(300);
		Assert.assertEquals(2, history.snapshot().size());
		Assert.assertArrayEquals(new long[]{100, 300}, history.snapshot().values);
	}

	@Test
	public void snapshotReference_stableWhenNothingRecorded()
	{
		ProfitHistory history = new ProfitHistory();
		history.onTick(10);
		ProfitHistory.Snapshot before = history.snapshot();
		history.onTick(20); // not an interval boundary
		Assert.assertSame(before, history.snapshot());
	}

	@Test
	public void snapshotValues_areACopy()
	{
		ProfitHistory history = new ProfitHistory();
		history.onTick(10);
		ProfitHistory.Snapshot first = history.snapshot();
		tick(history, 50, ProfitHistory.BASE_INTERVAL_TICKS);
		Assert.assertEquals(1, first.size());
		Assert.assertEquals(10L, first.values[0]);
		Assert.assertEquals(2, history.snapshot().size());
	}

	@Test
	public void correction_recordsImmediateAdjustedEndpoint()
	{
		ProfitHistory history = new ProfitHistory();
		history.onTick(10_000);
		ProfitHistory.Snapshot before = history.snapshot();

		history.recordCorrection(4_000);

		ProfitHistory.Snapshot after = history.snapshot();
		Assert.assertTrue(after.revision > before.revision);
		Assert.assertArrayEquals(new long[]{10_000, 4_000}, after.values);
	}

	@Test
	public void minMax_trackNegativesAndRevisionBumps()
	{
		ProfitHistory history = new ProfitHistory();
		history.onTick(-500);
		int rev = history.snapshot().revision;
		tick(history, 1200, ProfitHistory.BASE_INTERVAL_TICKS);
		ProfitHistory.Snapshot s = history.snapshot();
		Assert.assertEquals(-500L, s.min);
		Assert.assertEquals(1200L, s.max);
		Assert.assertTrue(s.revision > rev);
	}

	@Test
	public void fillingCapacity_halvesPointsAndDoublesInterval()
	{
		ProfitHistory history = new ProfitHistory();
		// Record CAPACITY samples with values 0,1,2,...
		history.onTick(0);
		for (int v = 1; v < ProfitHistory.CAPACITY; v++)
		{
			tick(history, v, ProfitHistory.BASE_INTERVAL_TICKS);
		}
		Assert.assertEquals(ProfitHistory.CAPACITY, history.snapshot().size());
		Assert.assertEquals(ProfitHistory.BASE_INTERVAL_TICKS, history.intervalTicks());

		// Next sample triggers the downsample, then appends.
		tick(history, 9999, ProfitHistory.BASE_INTERVAL_TICKS);
		ProfitHistory.Snapshot s = history.snapshot();
		Assert.assertEquals(ProfitHistory.CAPACITY / 2 + 1, s.size());
		Assert.assertEquals(ProfitHistory.BASE_INTERVAL_TICKS * 2, history.intervalTicks());
		// Pair averages: (0+1)/2=0, (2+3)/2=2, (4+5)/2=4 ...
		Assert.assertEquals(0L, s.values[0]);
		Assert.assertEquals(2L, s.values[1]);
		Assert.assertEquals(4L, s.values[2]);
		Assert.assertEquals(9999L, s.values[s.size() - 1]);

		// The doubled interval now governs: the old interval no longer records.
		tick(history, 7, ProfitHistory.BASE_INTERVAL_TICKS);
		Assert.assertEquals(ProfitHistory.CAPACITY / 2 + 1, history.snapshot().size());
		tick(history, 7, ProfitHistory.BASE_INTERVAL_TICKS);
		Assert.assertEquals(ProfitHistory.CAPACITY / 2 + 2, history.snapshot().size());
	}

	@Test
	public void reset_clearsAndRestoresBaseInterval()
	{
		ProfitHistory history = new ProfitHistory();
		history.onTick(0);
		for (int v = 1; v <= ProfitHistory.CAPACITY; v++)
		{
			tick(history, v, ProfitHistory.BASE_INTERVAL_TICKS);
		}
		Assert.assertEquals(ProfitHistory.BASE_INTERVAL_TICKS * 2, history.intervalTicks());
		int rev = history.snapshot().revision;

		history.reset();

		Assert.assertTrue(history.snapshot().isEmpty());
		Assert.assertTrue(history.snapshot().revision > rev);
		Assert.assertEquals(ProfitHistory.BASE_INTERVAL_TICKS, history.intervalTicks());
		// First tick after reset records immediately again.
		history.onTick(42);
		Assert.assertEquals(1, history.snapshot().size());
	}
}
