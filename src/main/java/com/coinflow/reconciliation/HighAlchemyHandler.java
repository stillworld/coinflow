package com.coinflow.reconciliation;

import com.coinflow.CoinFlowSession;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * Detects High Alchemy casts: matches coin gains against items lost whose High Alchemy
 * value produces the exact coin quantity. Converts session drops or banked items into
 * supplies/deductions according to True Inventory Accounting.
 */
@Slf4j
public class HighAlchemyHandler implements ReconciliationHandler
{
	@Override
	public void reconcile(ReconciliationContext context)
	{
		Map<Integer, Integer> rawGains = context.getRawGains();
		Map<Integer, Integer> rawLosses = context.getRawLosses();
		ItemManager itemManager = context.getItemManager();
		CoinFlowSession session = context.getSession();

		if (!rawGains.containsKey(ItemID.COINS))
		{
			return;
		}

		// Prevent false positives from coincidental monster coin drops or shop sales:
		// Require either Nature rune consumption or a verified alchemy action/context.
		boolean hasNatureRuneLoss = rawLosses.containsKey(ItemID.NATURERUNE);
		if (!hasNatureRuneLoss && !context.isAlchemy())
		{
			return;
		}

		int coinsGained = rawGains.get(ItemID.COINS);
		Integer alchedLostId = null;
		int alchedQty = 0;
		long alchedItemMarketPrice = 0L;
		String alchedItemName = null;

		// Deterministic candidate scan: sorted ids, prefer the smallest alch qty
		// (a real cast alchemises one item per tick; a partial-stack match also
		// covers an alched item that was lost for another reason in the same tick).
		java.util.List<Integer> sortedLossIds = new java.util.ArrayList<>(rawLosses.keySet());
		java.util.Collections.sort(sortedLossIds);
		int bestAlchQty = Integer.MAX_VALUE;

		for (int rawLostId : sortedLossIds)
		{
			int lostQty = rawLosses.get(rawLostId);
			int canonicalLostId = itemManager.canonicalize(rawLostId);

			if (canonicalLostId == ItemID.FIRERUNE || canonicalLostId == ItemID.COINS)
			{
				continue;
			}

			ItemComposition comp = itemManager.getItemComposition(canonicalLostId);
			if (comp == null)
			{
				continue;
			}

			int haPrice = comp.getHaPrice();
			if (haPrice <= 0 || coinsGained % haPrice != 0)
			{
				continue;
			}

			int quotient = coinsGained / haPrice;
			int candidateAlchQty;
			if (canonicalLostId == ItemID.NATURERUNE)
			{
				// The casting rune may come from the same stack: allow alch qty of
				// lostQty - 1 (one nature spent casting) or lostQty.
				if (lostQty >= 2 && quotient == lostQty - 1)
				{
					candidateAlchQty = quotient;
				}
				else if (quotient == lostQty)
				{
					candidateAlchQty = quotient;
				}
				else
				{
					continue;
				}
			}
			else if (quotient >= 1 && quotient <= lostQty)
			{
				candidateAlchQty = quotient;
			}
			else
			{
				continue;
			}

			if (candidateAlchQty < bestAlchQty)
			{
				bestAlchQty = candidateAlchQty;
				alchedLostId = rawLostId;
				alchedQty = candidateAlchQty;
				alchedItemMarketPrice = itemManager.getItemPrice(canonicalLostId);
				if (alchedItemMarketPrice <= 0)
				{
					alchedItemMarketPrice = haPrice;
				}
				alchedItemName = comp.getName();
			}
		}

		if (alchedLostId != null)
		{
			int canonicalLostId = itemManager.canonicalize(alchedLostId);
			ItemComposition comp = itemManager.getItemComposition(canonicalLostId);
			int haPrice = comp != null ? comp.getHaPrice() : (int) (coinsGained / Math.max(1, alchedQty));

			// Clean up rawLosses: if casting rune was used from the same stack (nature runes), keep 1 for supply expenses
			int currentLoss = rawLosses.getOrDefault(alchedLostId, 0);
			if (currentLoss <= alchedQty)
			{
				rawLosses.remove(alchedLostId);
			}
			else
			{
				rawLosses.put(alchedLostId, currentLoss - alchedQty);
			}

			int availableInSession = 0;
			if (session != null)
			{
				CoinFlowSession.TrackedItem sessionDrop = session.getTrackedItems().get(canonicalLostId);
				if (sessionDrop != null)
				{
					availableInSession = (int) Math.min(Integer.MAX_VALUE, sessionDrop.getRemainingQuantity());
				}
			}

			int sessionDeductibleQty = Math.min(alchedQty, availableInSession);
			if (sessionDeductibleQty > 0)
			{
				CoinFlowSession.TrackedItem sessionDrop = session.getTrackedItems().get(canonicalLostId);
				long dropPrice = sessionDrop != null ? sessionDrop.getPriceEach() : alchedItemMarketPrice;
				CoinFlowSession.TrackedItem deductItem = new CoinFlowSession.TrackedItem(
					canonicalLostId, alchedItemName, sessionDeductibleQty, dropPrice
				);
				context.addDroppedDeduction(canonicalLostId, deductItem);
			}

			int bankedQty = alchedQty - sessionDeductibleQty;
			if (bankedQty > 0)
			{
				CoinFlowSession.TrackedItem itemExpense = new CoinFlowSession.TrackedItem(
					canonicalLostId, alchedItemName, bankedQty, alchedItemMarketPrice
				);
				context.addSupplyExpense(canonicalLostId, itemExpense);
			}

			long unitMargin = haPrice - alchedItemMarketPrice;
			long totalMargin = unitMargin * alchedQty;

			context.setAlchedDropItemId(ItemID.COINS);
			context.setAlchedTotalMargin(totalMargin);
			context.setAlchedItemRawId(alchedLostId);

			log.debug("Alchemised: {} x{} (ha: {} gp, market: {} gp, sessionConverted: {}, banked: {}, margin: {} gp)",
				alchedItemName, alchedQty, haPrice, alchedItemMarketPrice, sessionDeductibleQty, bankedQty, totalMargin);
		}
	}
}
