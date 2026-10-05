package com.coinflow.reconciliation;

import com.coinflow.CoinFlowSession;
import com.coinflow.ConsumableRegistry;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * Final step in reconciliation: processes remaining lost items.
 * Consumable supplies (food, potions, runes, ammo, teleports) are recorded as supply expenses.
 * Non-consumable items dropped from inventory (coal, ore, logs) are recorded as dropped item deductions
 * bounded by prior session gains.
 */
@Slf4j
public class ConsumablesAndDropsHandler implements ReconciliationHandler
{
	@Override
	public void reconcile(ReconciliationContext context)
	{
		Map<Integer, Integer> rawLosses = context.getRawLosses();
		ItemManager itemManager = context.getItemManager();
		CoinFlowSession session = context.getSession();
		Map<Integer, Integer> recentlyDroppedItems = context.getRecentlyDroppedItems();

		if (rawLosses.isEmpty())
		{
			return;
		}

		for (Map.Entry<Integer, Integer> entry : rawLosses.entrySet())
		{
			int lostId = entry.getKey();
			int quantity = entry.getValue();
			int itemId = itemManager.canonicalize(lostId);
			String itemName = context.getItemName(itemId);

			if (context.isIgnored(itemName))
			{
				continue;
			}

			// Opt-in: a confirmed "Drop" click is charged as a supply expense at
			// market price. Only click-confirmed drops qualify so unexplained
			// losses (quest hand-ins, Destroy, death) keep the legacy path.
			if (context.isDropsAsSpent() && context.isDropIntent(itemId))
			{
				long price = priceOf(itemId, itemManager);
				context.addDropExpense(itemId, new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price));
				context.getRecentlyExpensedDrops().merge(itemId, quantity, Integer::sum);

				if (session != null)
				{
					CoinFlowSession.TrackedItem existingGain = session.getTrackedItems().get(itemId);
					if (existingGain != null && existingGain.getRemainingQuantity() > 0)
					{
						context.markSessionGainConsumed(itemId,
							Math.min(quantity, existingGain.getRemainingQuantity()));
					}
				}

				log.debug("Dropped item expensed: {} x{} @ {} gp = {} gp",
					itemName, quantity, price, (long) quantity * price);
				continue;
			}

			// A "Drop" click means the item left for the ground, not the player's
			// stomach: route through the own-drop path regardless of item type.
			boolean isConsumable = !context.isDropIntent(itemId)
				&& (ConsumableRegistry.isConsumable(itemId, itemName, itemManager)
				|| ConsumableRegistry.isSkillSink(context.getActiveSkillingSkills(), itemName));

			if (isConsumable)
			{
				long price = priceOf(itemId, itemManager);

				CoinFlowSession.TrackedItem expenseItem =
					new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price);
				context.addSupplyExpense(itemId, expenseItem);

				// Retire matching session gains so a later drop/sale of pre-session
				// stock cannot deduct the already-consumed loot a second time.
				if (session != null)
				{
					CoinFlowSession.TrackedItem existingGain = session.getTrackedItems().get(itemId);
					if (existingGain != null && existingGain.getRemainingQuantity() > 0)
					{
						context.markSessionGainConsumed(itemId,
							Math.min(quantity, existingGain.getRemainingQuantity()));
					}
				}

				log.debug("Consumed supply: {} x{} @ {} gp = {} gp",
					itemName, quantity, price, (long) quantity * price);
			}
			else
			{
				// Non-consumable item dropped from inventory (e.g. Coal, Iron ore, logs, gems, Cooking cape)
				int sessionDeductibleQty = 0;
				if (session != null)
				{
					CoinFlowSession.TrackedItem existingGain = session.getTrackedItems().get(itemId);
					if (existingGain != null && existingGain.getRemainingQuantity() > 0)
					{
						sessionDeductibleQty = (int) Math.min(quantity, existingGain.getRemainingQuantity());
						long price = existingGain.getPriceEach();

						CoinFlowSession.TrackedItem droppedItem =
							new CoinFlowSession.TrackedItem(itemId, itemName, sessionDeductibleQty, price);
						context.addDroppedDeduction(itemId, droppedItem);

						log.debug("Dropped item deducted from profit: {} x{} @ {} gp = {} gp",
							itemName, sessionDeductibleQty, price, (long) sessionDeductibleQty * price);
					}
				}

				if (sessionDeductibleQty > 0)
				{
					recentlyDroppedItems.merge(itemId, sessionDeductibleQty, Integer::sum);
				}

				int unacquiredDroppedQty = quantity - sessionDeductibleQty;
				if (unacquiredDroppedQty > 0 && context.getRecentlyDroppedOwnedItems() != null)
				{
					context.getRecentlyDroppedOwnedItems().merge(itemId, unacquiredDroppedQty, Integer::sum);
					log.debug("Dropped owned/banked item tracked to prevent false pickup profit: {} x{}",
						itemName, unacquiredDroppedQty);
				}
			}
		}
	}

	/**
	 * Market price with the same fallbacks used for gains: GE price, then the
	 * fixed coin/platinum values, then high alchemy for untradeables.
	 */
	private static long priceOf(int itemId, ItemManager itemManager)
	{
		long price = itemManager.getItemPrice(itemId);
		if (price > 0)
		{
			return price;
		}
		if (itemId == ItemID.COINS)
		{
			return 1;
		}
		if (itemId == ItemID.PLATINUM)
		{
			return 1000;
		}
		ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp != null ? comp.getHaPrice() : 0;
	}
}
