package com.coinflow;

import java.util.Arrays;

/**
 * Bounded time series of the session's cumulative profit, sampled on the
 * client thread every few game ticks and published as an immutable snapshot
 * for the side panel's sparkline.
 * <p>
 * Capacity is fixed at {@link #CAPACITY} points. When full, adjacent pairs
 * are averaged into half as many points and the sampling interval doubles,
 * so a multi-hour session costs the same memory and paint time as a short
 * one — the curve just gets coarser. Like {@link ActivityTimeline}, mutation
 * happens on the client thread while the EDT reads a volatile snapshot that
 * is only replaced when the data actually changes (record, downsample,
 * reset), so readers never allocate or see partial state.
 */
final class ProfitHistory
{
	static final int CAPACITY = 240;
	static final int BASE_INTERVAL_TICKS = 5;

	/** Immutable view of the series for the EDT. */
	static final class Snapshot
	{
		static final Snapshot EMPTY = new Snapshot(new long[0], 0);

		/** Whether the series has nothing recorded. */
		boolean isEmpty()
		{
			return values.length == 0;
		}

		final long[] values;
		final int revision;
		final long min;
		final long max;

		private Snapshot(long[] values, int revision)
		{
			this.values = values;
			this.revision = revision;
			long lo = Long.MAX_VALUE;
			long hi = Long.MIN_VALUE;
			for (long v : values)
			{
				lo = Math.min(lo, v);
				hi = Math.max(hi, v);
			}
			this.min = values.length == 0 ? 0L : lo;
			this.max = values.length == 0 ? 0L : hi;
		}

		int size()
		{
			return values.length;
		}
	}

	private final long[] samples = new long[CAPACITY];
	private int count;
	private int intervalTicks = BASE_INTERVAL_TICKS;
	private int ticksSinceSample;
	private int revision;
	private volatile Snapshot published = Snapshot.EMPTY;

	/**
	 * Called once per game tick with the session's current cumulative profit.
	 * Records a sample every {@code intervalTicks}; the first tick after a
	 * reset records immediately so the curve has an origin.
	 */
	void onTick(long cumulativeProfit)
	{
		if (count > 0 && ++ticksSinceSample < intervalTicks)
		{
			return;
		}
		ticksSinceSample = 0;
		if (count == CAPACITY)
		{
			downsample();
		}
		samples[count++] = cumulativeProfit;
		publish();
	}

	/** Averages adjacent pairs into half the points and doubles the interval. */
	private void downsample()
	{
		int half = count / 2;
		for (int i = 0; i < half; i++)
		{
			samples[i] = (samples[2 * i] + samples[2 * i + 1]) / 2;
		}
		count = half;
		intervalTicks *= 2;
	}

	void reset()
	{
		count = 0;
		intervalTicks = BASE_INTERVAL_TICKS;
		ticksSinceSample = 0;
		published = new Snapshot(new long[0], ++revision);
	}

	private void publish()
	{
		published = new Snapshot(Arrays.copyOf(samples, count), ++revision);
	}

	/** Current published snapshot — a reference read, never allocates. */
	Snapshot snapshot()
	{
		return published;
	}

	/** Current sampling interval in ticks (test-facing). */
	int intervalTicks()
	{
		return intervalTicks;
	}
}
