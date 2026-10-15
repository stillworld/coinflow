package com.coinflow.reconciliation;

import com.coinflow.CoinFlowSession;
import com.coinflow.ConsumableRegistry;
import com.coinflow.ItemPricing;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
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

		netCashExchange(context.getRawGains(), rawLosses);

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
				long price = ItemPricing.priceOf(itemId, itemManager);
				context.addDropExpense(itemId, new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price));
				context.getRecentlyExpensedDrops().merge(itemId, quantity, Integer::sum);
				context.retireSessionGain(itemId, quantity);

				log.debug("Dropped item expensed: {} x{} @ {} gp = {} gp",
					itemName, quantity, price, (long) quantity * price);
				continue;
			}

			// Cash payments: coins/platinum leaving with no specialist claim is
			// money spent — fees, fares, repairs, coffers, services. Expense at
			// face value; a drop-intent coin loss keeps the own-drop path, and
			// cash must never enter the drop bookkeeping, where it would
			// suppress later coin pickups.
			if ((itemId == ItemID.COINS || itemId == ItemID.PLATINUM) && !context.isDropIntent(itemId))
			{
				long price = itemId == ItemID.PLATINUM ? 1000L : 1L;
				context.addSupplyExpense(itemId, new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price));
				context.retireSessionGain(itemId, quantity);
				log.debug("Cash spend: {} x{} = {} gp", itemName, quantity, (long) quantity * price);
				continue;
			}

			// A "Drop" click means the item left for the ground, not the player's
			// stomach: route through the own-drop path regardless of item type.
			// Last-charge teleport jewelry crumbled in the pack is a supply
			// expense; other crumble items (e.g. Ring of recoil) are excluded here
			// because an inventory loss usually means feeding a Ring of suffering,
			// which WeaponChargeTracker expenses separately.
			// An "Open"/"Loot"/"Search" intent means the pack item (casket,
			// impling jar, supply crate) was consumed for its contents — a
			// supply expense like ammo or food. repriceFromBasis swaps in
			// carried purchase basis so a bought-then-opened item is charged
			// at cost, and the loss stays out of the drop bookkeeping where
			// it would suppress a later genuine pickup.
			boolean isConsumable = !context.isDropIntent(itemId)
				&& (context.isOpenIntent(itemId)
				|| ConsumableRegistry.isConsumable(itemId, itemName, itemManager)
				|| ConsumableRegistry.isSkillSink(context.getActiveSkillingSkills(), itemName)
				|| ConsumableRegistry.isLastChargeTeleportJewelry(itemName));

			if (isConsumable)
			{
				long price = ItemPricing.priceOf(itemId, itemManager);

				CoinFlowSession.TrackedItem expenseItem =
					new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price);
				context.addSupplyExpense(itemId, expenseItem);

				// Retire matching session gains so a later drop/sale of pre-session
				// stock cannot deduct the already-consumed loot a second time.
				context.retireSessionGain(itemId, quantity);

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
	 * Cancels banker coin/platinum token exchanges (1 token = 1,000 coins) so a
	 * pure currency conversion is neither a spend nor income.
	 */
	static void netCashExchange(Map<Integer, Integer> gains, Map<Integer, Integer> losses)
	{
		netExchange(losses, gains, ItemID.PLATINUM, ItemID.COINS);
		netExchange(losses, gains, ItemID.COINS, ItemID.PLATINUM);
	}

	private static void netExchange(Map<Integer, Integer> losses, Map<Integer, Integer> gains, int lostId, int gainedId)
	{
		int lost = losses.getOrDefault(lostId, 0);
		int gained = gains.getOrDefault(gainedId, 0);
		if (lost <= 0 || gained <= 0)
		{
			return;
		}
		int tokens = lostId == ItemID.PLATINUM ? Math.min(lost, gained / 1000) : Math.min(gained, lost / 1000);
		if (tokens <= 0)
		{
			return;
		}
		int lostMatched = lostId == ItemID.PLATINUM ? tokens : tokens * 1000;
		int gainedMatched = lostId == ItemID.PLATINUM ? tokens * 1000 : tokens;
		subtract(losses, lostId, lostMatched);
		subtract(gains, gainedId, gainedMatched);
		log.debug("Netted currency exchange: {} x{} -> {} x{}", lostId, lostMatched, gainedId, gainedMatched);
	}

	private static void subtract(Map<Integer, Integer> map, int id, int qty)
	{
		int remaining = map.getOrDefault(id, 0) - qty;
		if (remaining <= 0)
		{
			map.remove(id);
		}
		else
		{
			map.put(id, remaining);
		}
	}

}
