package com.coinflow;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * Reconciles player-inventory diffs that occur while a standard coin shop
 * interface (SHOPMAIN/SHOPSIDE) is open.
 *
 * Unlike the GE, a shop transaction directly mutates the player inventory:
 * the coins delta IS the actual price paid or received, so no widget price
 * scraping or per-NPC shop container diffs are needed.
 *
 *  - Buy: coins lost + items gained -> FIFO cost basis added to the shared
 *    {@link GrandExchangeTracker} pool (asset conversion, no session entry).
 *  - Sell: coins gained + items lost -> {@link GrandExchangeTracker#settleShopSell}
 *    decomposes proceeds by carried value (basis -> session tracked -> untracked).
 *
 * Diffs with no matching coin delta (non-coin currencies like tokkul, eating or
 * equipping while the shop is open, mixed same-tick buy+sell that nets the coin
 * delta) are intentionally suppressed: the caller discards whatever this method
 * does not reconcile, which is strictly safer than counting phantom profit.
 */
@Slf4j
final class ShopTracker
{
	private final GrandExchangeTracker tradeTracker;

	ShopTracker(GrandExchangeTracker tradeTracker)
	{
		this.tradeTracker = tradeTracker;
	}

	/**
	 * Converts a tick's inventory diff into a ledger of session entries.
	 * Entries not reconciled here are implicitly suppressed by the caller.
	 *
	 * {@code untrackedAsIncome} carries untracked (pre-session) sold stock at
	 * zero so its proceeds surface as income instead of a net-zero asset
	 * conversion.
	 */
	GrandExchangeTracker.GeLedger reconcileDiff(
		Map<Integer, Integer> gains,
		Map<Integer, Integer> losses,
		CoinFlowSession session,
		ItemManager itemManager,
		Set<String> ignoredItemNames,
		boolean untrackedAsIncome)
	{
		GrandExchangeTracker.GeLedger ledger = new GrandExchangeTracker.GeLedger();

		int coinsLost = losses != null ? losses.getOrDefault(ItemID.COINS, 0) : 0;
		int coinsGained = gains != null ? gains.getOrDefault(ItemID.COINS, 0) : 0;

		Map<Integer, Integer> nonCoinGains = canonicalize(gains, ItemID.COINS, itemManager, ignoredItemNames);
		Map<Integer, Integer> nonCoinLosses = canonicalize(losses, ItemID.COINS, itemManager, ignoredItemNames);

		if (coinsLost > 0 && !nonCoinGains.isEmpty())
		{
			Map<Integer, Long> spend = allocate(nonCoinGains, coinsLost, itemManager);
			for (Map.Entry<Integer, Long> entry : spend.entrySet())
			{
				int itemId = entry.getKey();
				int quantity = nonCoinGains.get(itemId);
				long cost = entry.getValue();
				tradeTracker.addBasis(itemId, quantity, cost);
				log.debug("Shop buy: item {} x{} for {} gp", itemId, quantity, cost);
			}
		}

		if (coinsGained > 0 && !nonCoinLosses.isEmpty())
		{
			Map<Integer, Long> proceeds = allocate(nonCoinLosses, coinsGained, itemManager);
			for (Map.Entry<Integer, Long> entry : proceeds.entrySet())
			{
				int itemId = entry.getKey();
				int quantity = nonCoinLosses.get(itemId);
				merge(ledger, tradeTracker.settleShopSell(itemId, quantity, entry.getValue(), session, untrackedAsIncome));
				log.debug("Shop sell: item {} x{} for {} gp", itemId, quantity, entry.getValue());
			}
		}

		return ledger;
	}

	/**
	 * Canonicalizes a raw diff map (noted ids -> base ids, merges sums),
	 * drops the excluded id (coins) and ignored item names.
	 */
	private static Map<Integer, Integer> canonicalize(
		Map<Integer, Integer> raw,
		int excludeItemId,
		ItemManager itemManager,
		Set<String> ignoredItemNames)
	{
		Map<Integer, Integer> result = new HashMap<>();
		if (raw == null || raw.isEmpty())
		{
			return result;
		}
		for (Map.Entry<Integer, Integer> entry : raw.entrySet())
		{
			int rawId = entry.getKey();
			int qty = entry.getValue();
			if (qty <= 0)
			{
				continue;
			}
			int itemId = itemManager != null ? itemManager.canonicalize(rawId) : rawId;
			if (itemId == excludeItemId || itemId <= 0)
			{
				continue;
			}
			if (isIgnored(itemId, itemManager, ignoredItemNames))
			{
				continue;
			}
			result.merge(itemId, qty, Integer::sum);
		}
		return result;
	}

	private static boolean isIgnored(int itemId, ItemManager itemManager, Set<String> ignoredItemNames)
	{
		if (ignoredItemNames == null || ignoredItemNames.isEmpty() || itemManager == null)
		{
			return false;
		}
		ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp != null && comp.getName() != null
			&& ignoredItemNames.contains(comp.getName().toLowerCase(java.util.Locale.ROOT));
	}

	/**
	 * Distributes {@code total} across item types pro-rata by GE value
	 * (qty * unit price). Exact for a single item type; for multi-type
	 * baskets the division remainder lands on the largest weighted entry.
	 */
	private static Map<Integer, Long> allocate(Map<Integer, Integer> items, long total, ItemManager itemManager)
	{
		Map<Integer, Long> allocation = new HashMap<>();
		if (items.isEmpty() || total <= 0)
		{
			return allocation;
		}

		Map<Integer, Long> weights = new HashMap<>();
		long weightSum = 0;
		for (Map.Entry<Integer, Integer> entry : items.entrySet())
		{
			long price = itemManager != null ? itemManager.getItemPrice(entry.getKey()) : 0;
			long weight = entry.getValue() * Math.max(1L, price);
			weights.put(entry.getKey(), weight);
			weightSum += weight;
		}

		long distributed = 0;
		int largestId = -1;
		long largestWeight = -1;
		for (Map.Entry<Integer, Integer> entry : items.entrySet())
		{
			long weight = weights.get(entry.getKey());
			long share = weightSum > 0 ? total * weight / weightSum : 0;
			allocation.put(entry.getKey(), share);
			distributed += share;
			if (weight > largestWeight)
			{
				largestWeight = weight;
				largestId = entry.getKey();
			}
		}

		long remainder = total - distributed;
		if (remainder != 0 && largestId >= 0)
		{
			allocation.merge(largestId, remainder, Long::sum);
		}
		return allocation;
	}

	private static void merge(GrandExchangeTracker.GeLedger into, GrandExchangeTracker.GeLedger part)
	{
		part.gains.forEach((id, item) -> into.gains.merge(id, item,
			(a, b) -> a.merge(b)));
		part.deductions.forEach((id, item) -> into.deductions.merge(id, item,
			(a, b) -> a.merge(b)));
		part.expenses.forEach((id, item) -> into.expenses.merge(id, item,
			(a, b) -> a.merge(b)));
		into.netDelta += part.netDelta;
	}
}
