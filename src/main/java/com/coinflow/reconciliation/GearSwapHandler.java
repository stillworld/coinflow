package com.coinflow.reconciliation;

import java.util.ArrayList;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;

/**
 * Handles unequipped gear moved to inventory or equipped gear moved to equipment slots,
 * canceling out spurious gains and losses across inventory and worn equipment diffs.
 */
public class GearSwapHandler implements ReconciliationHandler
{
	@Override
	public void reconcile(ReconciliationContext context)
	{
		Map<Integer, Integer> rawGains = context.getRawGains();
		Map<Integer, Integer> rawLosses = context.getRawLosses();
		Map<Integer, Integer> recentlyUnequippedItems = context.getRecentlyUnequippedItems();
		Map<Integer, Integer> recentlyEquippedItems = context.getRecentlyEquippedItems();

		// 1. Subtract any items that were just unequipped from gear slots
		if (!recentlyUnequippedItems.isEmpty())
		{
			for (Map.Entry<Integer, Integer> unequipped : new ArrayList<>(recentlyUnequippedItems.entrySet()))
			{
				int unequippedId = unequipped.getKey();
				int unequippedQty = unequipped.getValue();

				Integer gainedQty = rawGains.get(unequippedId);
				if (gainedQty != null)
				{
					int matched = Math.min(gainedQty, unequippedQty);
					int netGain = gainedQty - matched;
					if (netGain <= 0)
					{
						rawGains.remove(unequippedId);
					}
					else
					{
						rawGains.put(unequippedId, netGain);
					}

					int remainingUnequipped = unequippedQty - matched;
					if (remainingUnequipped <= 0)
					{
						recentlyUnequippedItems.remove(unequippedId);
					}
					else
					{
						recentlyUnequippedItems.put(unequippedId, remainingUnequipped);
					}

					if (context.getPendingWornAmmoExpenses() != null)
					{
						int canonicalUnequippedId = context.getItemManager() != null
							? context.getItemManager().canonicalize(unequippedId)
							: unequippedId;
						Integer pending = context.getPendingWornAmmoExpenses().get(canonicalUnequippedId);
						int keyToUse = canonicalUnequippedId;
						if (pending == null && canonicalUnequippedId != unequippedId)
						{
							pending = context.getPendingWornAmmoExpenses().get(unequippedId);
							keyToUse = unequippedId;
						}
						if (pending != null)
						{
							if (pending <= matched)
							{
								context.getPendingWornAmmoExpenses().remove(keyToUse);
							}
							else
							{
								context.getPendingWornAmmoExpenses().put(keyToUse, pending - matched);
							}
						}
					}
				}
			}
		}

		// 2. Also check against previousEquipmentSnapshot if INV dispatched before WORN
		if (context.getPreviousEquipmentSnapshot() != null && !rawGains.isEmpty())
		{
			ItemContainer currentEquip = context.getCurrentEquipContainer();
			for (Map.Entry<Integer, Integer> entry : new ArrayList<>(rawGains.entrySet()))
			{
				int rawId = entry.getKey();
				int gainQty = entry.getValue();
				int canonicalId = context.getItemManager().canonicalize(rawId);

				int equippedQty = context.getPreviousEquipmentSnapshot().getItems().getOrDefault(rawId, 0);
				if (equippedQty == 0 && canonicalId != rawId)
				{
					equippedQty = context.getPreviousEquipmentSnapshot().getItems().getOrDefault(canonicalId, 0);
				}

				if (equippedQty > 0)
				{
					int currentWornQty = 0;
					if (currentEquip != null)
					{
						Item[] wornItems = currentEquip.getItems();
						if (wornItems != null)
						{
							for (Item item : wornItems)
							{
								if (item != null && (item.getId() == rawId || item.getId() == canonicalId))
								{
									currentWornQty += item.getQuantity();
								}
							}
						}
					}

					int unequipped = Math.max(0, equippedQty - currentWornQty);
					if (unequipped > 0)
					{
						int match = Math.min(gainQty, unequipped);
						if (gainQty <= match)
						{
							rawGains.remove(rawId);
						}
						else
						{
							rawGains.put(rawId, gainQty - match);
						}

						if (context.getMatchedUnequipsThisTick() != null)
						{
							context.getMatchedUnequipsThisTick().merge(canonicalId, match, Integer::sum);
						}

						if (context.getPendingWornAmmoExpenses() != null)
						{
							Integer pending = context.getPendingWornAmmoExpenses().get(canonicalId);
							int keyToUse = canonicalId;
							if (pending == null && canonicalId != rawId)
							{
								pending = context.getPendingWornAmmoExpenses().get(rawId);
								keyToUse = rawId;
							}
							if (pending != null)
							{
								if (pending <= match)
								{
									context.getPendingWornAmmoExpenses().remove(keyToUse);
								}
								else
								{
									context.getPendingWornAmmoExpenses().put(keyToUse, pending - match);
								}
							}
						}
					}
				}
			}
		}

		// 3. Subtract any items that were just equipped into gear slots
		if (!recentlyEquippedItems.isEmpty())
		{
			for (Map.Entry<Integer, Integer> equipped : new ArrayList<>(recentlyEquippedItems.entrySet()))
			{
				int equippedId = equipped.getKey();
				int equippedQty = equipped.getValue();

				Integer lostQty = rawLosses.get(equippedId);
				if (lostQty != null)
				{
					int matched = Math.min(lostQty, equippedQty);
					int netLost = lostQty - matched;
					if (netLost <= 0)
					{
						rawLosses.remove(equippedId);
					}
					else
					{
						rawLosses.put(equippedId, netLost);
					}

					int remainingEquipped = equippedQty - matched;
					if (remainingEquipped <= 0)
					{
						recentlyEquippedItems.remove(equippedId);
					}
					else
					{
						recentlyEquippedItems.put(equippedId, remainingEquipped);
					}
				}
			}
		}

		// 4. Also check against current equipment if INV dispatched before WORN
		if (!rawLosses.isEmpty())
		{
			ItemContainer currentEquip = context.getCurrentEquipContainer();
			if (currentEquip != null && context.getPreviousEquipmentSnapshot() != null)
			{
				for (Map.Entry<Integer, Integer> entry : new ArrayList<>(rawLosses.entrySet()))
				{
					int rawId = entry.getKey();
					int lostQty = entry.getValue();
					int canonicalId = context.getItemManager().canonicalize(rawId);

					int prevWornQty = context.getPreviousEquipmentSnapshot().getItems().getOrDefault(rawId, 0);
					if (prevWornQty == 0 && canonicalId != rawId)
					{
						prevWornQty = context.getPreviousEquipmentSnapshot().getItems().getOrDefault(canonicalId, 0);
					}

					int currentWornQty = 0;
					Item[] wornItems = currentEquip.getItems();
					if (wornItems != null)
					{
						for (Item item : wornItems)
						{
							if (item != null && (item.getId() == rawId || item.getId() == canonicalId))
							{
								currentWornQty += item.getQuantity();
							}
						}
					}

					int newlyEquipped = Math.max(0, currentWornQty - prevWornQty);
					if (newlyEquipped > 0)
					{
						int match = Math.min(lostQty, newlyEquipped);
						if (lostQty <= match)
						{
							rawLosses.remove(rawId);
						}
						else
						{
							rawLosses.put(rawId, lostQty - match);
						}
					}
				}
			}
		}
	}
}
