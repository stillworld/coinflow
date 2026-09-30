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

		for (Map.Entry<Integer, Integer> loss : rawLosses.entrySet())
		{
			int rawLostId = loss.getKey();
			int lostQty = loss.getValue();
			int canonicalLostId = itemManager.canonicalize(rawLostId);

			if (canonicalLostId == ItemID.FIRERUNE || canonicalLostId == ItemID.COINS)
			{
				continue;
			}

			ItemComposition comp = itemManager.getItemComposition(canonicalLostId);
			if (comp != null)
			{
				int haPrice = comp.getHaPrice();
				if (haPrice > 0)
				{
					int candidateAlchQty = lostQty;
					if (canonicalLostId == ItemID.NATURERUNE)
					{
						if (lostQty >= 2 && (long) haPrice * (lostQty - 1) == coinsGained)
						{
							candidateAlchQty = lostQty - 1;
						}
						else if ((long) haPrice * lostQty != coinsGained)
						{
							continue;
						}
					}

					if ((long) haPrice * candidateAlchQty == coinsGained)
					{
						alchedLostId = rawLostId;
						alchedQty = candidateAlchQty;
						alchedItemMarketPrice = itemManager.getItemPrice(canonicalLostId);
						if (alchedItemMarketPrice <= 0)
						{
							alchedItemMarketPrice = haPrice;
						}
						alchedItemName = comp.getName();
						break;
					}
				}
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
					availableInSession = sessionDrop.getQuantity();
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
