package com.coinflow;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared utilities for CoinFlow unit tests.
 */
public final class TestHelpers
{
	private TestHelpers() {}

	/**
	 * Builds an InventorySnapshot from interleaved (itemId, qty) pairs.
	 * <pre>snapshot(1234, 5, 5678, 10)</pre> produces {1234→5, 5678→10}.
	 * The varargs length must be even.
	 */
	public static InventorySnapshot snapshot(int... idQtyPairs)
	{
		if (idQtyPairs.length % 2 != 0)
		{
			throw new IllegalArgumentException("Arguments must be interleaved id/qty pairs");
		}
		int len = idQtyPairs.length / 2;
		int[] ids = new int[len];
		int[] qtys = new int[len];
		for (int i = 0; i < idQtyPairs.length; i += 2)
		{
			ids[i / 2] = idQtyPairs[i];
			qtys[i / 2] = idQtyPairs[i + 1];
		}
		return InventorySnapshot.fromArrays(ids, qtys);
	}

	/**
	 * Builds a single-entry gains map suitable for passing to
	 * {@link CoinFlowSession#withGains(Map)}.
	 */
	public static Map<Integer, CoinFlowSession.TrackedItem> gains(
		int id, String name, int qty, long priceEach)
	{
		return Collections.singletonMap(
			id, new CoinFlowSession.TrackedItem(id, name, qty, priceEach));
	}

	/**
	 * Builds a multi-entry gains map from interleaved
	 * (id, qty, priceEach) triples.
	 */
	public static Map<Integer, CoinFlowSession.TrackedItem> gainsMulti(
		String name, long... idQtyPriceTuples)
	{
		if (idQtyPriceTuples.length % 3 != 0)
		{
			throw new IllegalArgumentException("Arguments must be interleaved id/qty/price triples");
		}
		Map<Integer, CoinFlowSession.TrackedItem> map = new HashMap<>();
		for (int i = 0; i < idQtyPriceTuples.length; i += 3)
		{
			int id = (int) idQtyPriceTuples[i];
			int qty = (int) idQtyPriceTuples[i + 1];
			long price = idQtyPriceTuples[i + 2];
			map.put(id, new CoinFlowSession.TrackedItem(id, name, qty, price));
		}
		return map;
	}

	/**
	 * Simulates {@code ticks} game ticks on the session, each spaced by the
	 * real elapsed wall clock time between calls. Use a small number of ticks
	 * to verify structural behavior (idle flag, field invariants) without
	 * requiring precise timing.
	 */
	public static CoinFlowSession simulateTicks(
		CoinFlowSession session, int ticks, int idleTimeoutMinutes)
	{
		for (int i = 0; i < ticks; i++)
		{
			session = session.tick(idleTimeoutMinutes);
		}
		return session;
	}

	/**
	 * Returns the total value sum across all tracked items.
	 * Used to verify the invariant: sum(TrackedItem.totalValue) == session.totalProfit
	 * when all gains come from the same price point.
	 */
	public static long sumBreakdownValues(CoinFlowSession session)
	{
		long sum = 0;
		for (CoinFlowSession.TrackedItem item : session.getTrackedItems().values())
		{
			sum += item.getTotalValue();
		}
		return sum;
	}
}
