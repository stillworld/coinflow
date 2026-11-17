package com.coinflow.reconciliation;

import com.coinflow.CoinFlowSession;
import com.coinflow.ConsumableRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

/**
 * Handles multi-bite food portions (e.g. Summer pie -> Half a summer pie, pizzas, cakes),
 * consuming whole items and attributing portion supply expenses without marking portions as loot.
 */
@Slf4j
public class FoodPortionHandler implements ReconciliationHandler
{
	@Override
	public void reconcile(ReconciliationContext context)
	{
		Map<Integer, Integer> rawGains = context.getRawGains();
		Map<Integer, Integer> rawLosses = context.getRawLosses();
		ItemManager itemManager = context.getItemManager();

		if (rawLosses.isEmpty())
		{
			return;
		}

		List<Integer> lostIds = new ArrayList<>(rawLosses.keySet());
		for (int lostId : lostIds)
		{
			int canonicalLostId = itemManager.canonicalize(lostId);
			String lostName = context.getItemName(canonicalLostId);

			Integer portionGainedId = null;
			for (int gainedId : rawGains.keySet())
			{
				int canonicalGainedId = itemManager.canonicalize(gainedId);
				String gainedName = context.getItemName(canonicalGainedId);
				if (ConsumableRegistry.isFoodPortion(gainedName, lostName))
				{
					portionGainedId = gainedId;
					break;
				}
			}

			if (portionGainedId != null)
			{
				int lostQty = rawLosses.get(lostId);
				int gainedQty = rawGains.get(portionGainedId);
				int portions = Math.min(lostQty, gainedQty);

				if (gainedQty <= portions)
				{
					rawGains.remove(portionGainedId);
				}
				else
				{
					rawGains.put(portionGainedId, gainedQty - portions);
				}

				if (lostQty <= portions)
				{
					rawLosses.remove(lostId);
				}
				else
				{
					rawLosses.put(lostId, lostQty - portions);
				}

				long wholePrice = itemManager.getItemPrice(canonicalLostId);
				int portionsInWhole = lostName.toLowerCase(java.util.Locale.ROOT).contains("cake") ? 3 : 2;
				long portionPrice = Math.max(1L, wholePrice / portionsInWhole);

				// Move purchase basis with the food: the eaten portion's share
				// is expensed at cost, the rest stays on the leftover portion.
				long basisCost = 0;
				int basisPortions = 0;
				com.coinflow.GrandExchangeTracker tracker = context.getGrandExchangeTracker();
				if (tracker != null)
				{
					long[] result = tracker.reconcilePotionBasis(
						java.util.Collections.singletonList(
							new com.coinflow.GrandExchangeTracker.PotionBottle(canonicalLostId, portionsInWhole, portions)),
						java.util.Collections.singletonList(
							new com.coinflow.GrandExchangeTracker.PotionBottle(
								itemManager.canonicalize(portionGainedId), portionsInWhole - 1, portions)),
						portions);
					basisCost = result[0];
					basisPortions = (int) result[1];
				}

				long expenseTotal = basisCost
					+ (long) Math.max(0, portions - basisPortions) * portionPrice;
				String expenseName = lostName + " (portion)";
				if (!context.isIgnored(lostName) && !context.isIgnored(expenseName))
				{
					CoinFlowSession.TrackedItem.PriceBasis portionBasis = wholePrice > 0
						? CoinFlowSession.TrackedItem.PriceBasis.GE_PRICE
						: CoinFlowSession.TrackedItem.PriceBasis.UNPRICED;
					CoinFlowSession.TrackedItem portionExpense =
						new CoinFlowSession.TrackedItem(canonicalLostId, expenseName, portions,
							expenseTotal / portions)
							.withProvenance(CoinFlowSession.TrackedItem.Reason.DOSE, portionBasis);
					if (basisCost > 0)
					{
						portionExpense = portionExpense.withProvenance(null,
							CoinFlowSession.TrackedItem.PriceBasis.PURCHASE_COST);
					}
					context.addFractionalSupplyExpense(canonicalLostId, portionExpense);
					log.debug("Consumed supply: {} x{} @ {} gp = {} gp",
						expenseName, portions, expenseTotal / portions, expenseTotal);
				}
			}
		}
	}
}
