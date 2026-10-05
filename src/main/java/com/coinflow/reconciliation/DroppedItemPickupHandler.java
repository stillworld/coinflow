package com.coinflow.reconciliation;

import java.util.ArrayList;
import java.util.Map;
import net.runelite.client.game.ItemManager;

/**
 * Handles picking up previously dropped items:
 * 1. For items not gained in the session (e.g. Cooking cape, Spade brought from bank/gear),
 *    cancels them out from rawGains so picking up owned items does not create fake profit.
 * 2. For items gained in the current session (e.g. mined Coal, chopped logs), decrements
 *    recentlyDroppedItems and allows rawGains to restore the deducted profit.
 * 3. For items expensed on drop (Count Drops as Spent), cancels them out from rawGains
 *    and records an expense reversal so the Spent charge is undone.
 */
public class DroppedItemPickupHandler implements ReconciliationHandler
{
	@Override
	public void reconcile(ReconciliationContext context)
	{
		applyDropReconciliation(
			context.getRawGains(),
			context.getRecentlyDroppedItems(),
			context.getRecentlyDroppedOwnedItems(),
			context.getRecentlyExpensedDrops(),
			context.getExpenseReversals(),
			context.getItemManager());
	}

	/**
	 * Applies own-drop reconciliation to a gains map in place. Shared with
	 * non-inventory pickup paths (gem bag, herb sack, seed box, fish barrel,
	 * log basket) which bypass the handler chain.
	 */
	public static void applyDropReconciliation(
		Map<Integer, Integer> rawGains,
		Map<Integer, Integer> recentlyDroppedItems,
		Map<Integer, Integer> recentlyDroppedOwnedItems,
		ItemManager itemManager)
	{
		applyDropReconciliation(rawGains, recentlyDroppedItems, recentlyDroppedOwnedItems,
			null, null, itemManager);
	}

	/**
	 * Variant that also matches expensed drops: matched gains are removed from
	 * {@code rawGains} and the quantities are accumulated into
	 * {@code expenseReversalsOut} for the caller to apply with
	 * {@link com.coinflow.CoinFlowSession#withExpenseReversal}.
	 */
	public static void applyDropReconciliation(
		Map<Integer, Integer> rawGains,
		Map<Integer, Integer> recentlyDroppedItems,
		Map<Integer, Integer> recentlyDroppedOwnedItems,
		Map<Integer, Integer> recentlyExpensedDrops,
		Map<Integer, Long> expenseReversalsOut,
		ItemManager itemManager)
	{
		if (rawGains.isEmpty())
		{
			return;
		}

		// 0. Reconcile expensed drops (Count Drops as Spent):
		// The drop was charged to Spent, so the pickup reverses that charge rather than
		// crediting a gain. Runs first so these never fall through to the legacy maps.
		if (recentlyExpensedDrops != null && !recentlyExpensedDrops.isEmpty())
		{
			for (Map.Entry<Integer, Integer> entry : new ArrayList<>(rawGains.entrySet()))
			{
				int rawId = entry.getKey();
				int gainQty = entry.getValue();
				int canonicalId = itemManager != null ? itemManager.canonicalize(rawId) : rawId;

				int pending = recentlyExpensedDrops.getOrDefault(canonicalId, 0);
				int keyToUse = canonicalId;
				if (pending == 0 && canonicalId != rawId)
				{
					pending = recentlyExpensedDrops.getOrDefault(rawId, 0);
					keyToUse = rawId;
				}

				if (pending > 0)
				{
					int match = Math.min(gainQty, pending);
					if (pending <= match)
					{
						recentlyExpensedDrops.remove(keyToUse);
					}
					else
					{
						recentlyExpensedDrops.put(keyToUse, pending - match);
					}

					if (expenseReversalsOut != null)
					{
						expenseReversalsOut.merge(canonicalId, (long) match, Long::sum);
					}

					int remainingGain = gainQty - match;
					if (remainingGain <= 0)
					{
						rawGains.remove(rawId);
					}
					else
					{
						rawGains.put(rawId, remainingGain);
					}
				}
			}
		}

		if (rawGains.isEmpty())
		{
			return;
		}

		// 1. Reconcile owned/banked items that were dropped:
		// These were never credited as profit or deducted on drop, so picking them back up
		// must NOT be credited as loot/profit. Remove them from rawGains.
		if (recentlyDroppedOwnedItems != null && !recentlyDroppedOwnedItems.isEmpty())
		{
			for (Map.Entry<Integer, Integer> entry : new ArrayList<>(rawGains.entrySet()))
			{
				int rawId = entry.getKey();
				int gainQty = entry.getValue();
				int canonicalId = itemManager != null ? itemManager.canonicalize(rawId) : rawId;

				int droppedOwned = recentlyDroppedOwnedItems.getOrDefault(canonicalId, 0);
				int keyToUse = canonicalId;
				if (droppedOwned == 0 && canonicalId != rawId)
				{
					droppedOwned = recentlyDroppedOwnedItems.getOrDefault(rawId, 0);
					keyToUse = rawId;
				}

				if (droppedOwned > 0)
				{
					int match = Math.min(gainQty, droppedOwned);
					if (droppedOwned <= match)
					{
						recentlyDroppedOwnedItems.remove(keyToUse);
					}
					else
					{
						recentlyDroppedOwnedItems.put(keyToUse, droppedOwned - match);
					}

					int remainingGain = gainQty - match;
					if (remainingGain <= 0)
					{
						rawGains.remove(rawId);
					}
					else
					{
						rawGains.put(rawId, remainingGain);
					}
				}
			}
		}

		// 2. Reconcile session-gained items that were dropped:
		// Decrement recentlyDroppedItems so the remaining gain restores the session deduction without staying pending.
		if (recentlyDroppedItems != null && !recentlyDroppedItems.isEmpty() && !rawGains.isEmpty())
		{
			for (Map.Entry<Integer, Integer> entry : rawGains.entrySet())
			{
				int rawId = entry.getKey();
				int gainQty = entry.getValue();
				int canonicalId = itemManager != null ? itemManager.canonicalize(rawId) : rawId;

				Integer droppedQty = recentlyDroppedItems.get(canonicalId);
				int keyToUse = canonicalId;
				if (droppedQty == null && canonicalId != rawId)
				{
					droppedQty = recentlyDroppedItems.get(rawId);
					keyToUse = rawId;
				}

				if (droppedQty != null)
				{
					if (droppedQty <= gainQty)
					{
						recentlyDroppedItems.remove(keyToUse);
					}
					else
					{
						recentlyDroppedItems.put(keyToUse, droppedQty - gainQty);
					}
				}
			}
		}
	}
}
