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
 */
public class DroppedItemPickupHandler implements ReconciliationHandler
{
	@Override
	public void reconcile(ReconciliationContext context)
	{
		Map<Integer, Integer> rawGains = context.getRawGains();
		Map<Integer, Integer> recentlyDroppedOwnedItems = context.getRecentlyDroppedOwnedItems();
		Map<Integer, Integer> recentlyDroppedItems = context.getRecentlyDroppedItems();
		ItemManager itemManager = context.getItemManager();

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
