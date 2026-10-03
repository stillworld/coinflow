package com.coinflow.reconciliation;

import com.coinflow.CoinFlowSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * Reconciles items converted between noted and unnoted forms (e.g., Tool Leprechaun noting
 * harvested crops, Phials unnoting planks/bones in Rimmington, or Piles in the Wilderness).
 *
 * Prevents noting owned or harvested items from triggering duplicate gains / false profit,
 * prevents unnoting items from triggering fake loot drops, and attributes any unnoting / noting
 * service fees (e.g. 5 gp each charged by Phials/Piles) to supply expenses.
 */
@Slf4j
public class ItemNotingHandler implements ReconciliationHandler
{
	private static final int DEFAULT_UNNOTING_FEE_EACH = 5;

	@Override
	public void reconcile(ReconciliationContext context)
	{
		Map<Integer, Integer> rawGains = context.getRawGains();
		Map<Integer, Integer> rawLosses = context.getRawLosses();
		ItemManager itemManager = context.getItemManager();

		if (rawLosses.isEmpty() || rawGains.isEmpty())
		{
			return;
		}

		int totalConvertedItems = 0;

		List<Integer> lostKeys = new ArrayList<>(rawLosses.keySet());
		for (int lostId : lostKeys)
		{
			if (rawGains.isEmpty())
			{
				break;
			}

			Integer lostQtyBoxed = rawLosses.get(lostId);
			if (lostQtyBoxed == null || lostQtyBoxed <= 0)
			{
				continue;
			}
			int lostQty = lostQtyBoxed;

			int canonicalLostId = itemManager != null ? itemManager.canonicalize(lostId) : lostId;
			if (canonicalLostId <= 0 || canonicalLostId == ItemID.COINS)
			{
				continue;
			}

			while (lostQty > 0 && !rawGains.isEmpty())
			{
				Integer matchedGainedId = null;
				int gainedQty = 0;

				for (Map.Entry<Integer, Integer> gainedEntry : rawGains.entrySet())
				{
					int gainedId = gainedEntry.getKey();
					if (gainedId == lostId)
					{
						continue;
					}

					int canonicalGainedId = itemManager != null ? itemManager.canonicalize(gainedId) : gainedId;
					if (canonicalLostId == canonicalGainedId && gainedEntry.getValue() > 0)
					{
						matchedGainedId = gainedId;
						gainedQty = gainedEntry.getValue();
						break;
					}
				}

				if (matchedGainedId == null)
				{
					break;
				}

				int matchQty = Math.min(lostQty, gainedQty);
				if (matchQty > 0)
				{
					totalConvertedItems += matchQty;
					String itemName = context.getItemName(canonicalLostId);
					log.debug("Reconciled note/unnote swap: {} x{} (lost raw {}, gained raw {})",
						itemName, matchQty, lostId, matchedGainedId);

					lostQty -= matchQty;
					if (lostQty <= 0)
					{
						rawLosses.remove(lostId);
					}
					else
					{
						rawLosses.put(lostId, lostQty);
					}

					if (gainedQty <= matchQty)
					{
						rawGains.remove(matchedGainedId);
					}
					else
					{
						rawGains.put(matchedGainedId, gainedQty - matchQty);
					}
				}
				else
				{
					break;
				}
			}
		}

		// Reconcile service fee (e.g. 5 gp each charged by Phials or Piles).
		// Only attribute a coin loss as a fee when a noting-service interaction was
		// recently clicked — otherwise coincidental coin losses in the same tick as a
		// note swap (or free noting at the Tool Leprechaun) get eaten as fake fees.
		if (totalConvertedItems > 0 && context.isNotingService() && !rawLosses.isEmpty())
		{
			Integer coinsLostKey = null;
			int coinsLostQty = 0;

			for (Map.Entry<Integer, Integer> entry : rawLosses.entrySet())
			{
				int canonicalId = itemManager != null ? itemManager.canonicalize(entry.getKey()) : entry.getKey();
				if (canonicalId == ItemID.COINS)
				{
					coinsLostKey = entry.getKey();
					coinsLostQty = entry.getValue();
					break;
				}
			}

			if (coinsLostKey != null && coinsLostQty > 0)
			{
				int maxExpectedFee = totalConvertedItems * DEFAULT_UNNOTING_FEE_EACH;
				int feeDeducted = Math.min(coinsLostQty, maxExpectedFee);

				if (feeDeducted > 0)
				{
					String coinsName = context.getItemName(ItemID.COINS);
					if (coinsName == null || coinsName.isEmpty())
					{
						coinsName = "Coins";
					}

					context.addSupplyExpense(
						ItemID.COINS,
						new CoinFlowSession.TrackedItem(ItemID.COINS, coinsName, feeDeducted, 1L)
					);
					log.debug("Attributed noting/unnoting fee: {} gp", feeDeducted);

					if (coinsLostQty <= feeDeducted)
					{
						rawLosses.remove(coinsLostKey);
					}
					else
					{
						rawLosses.put(coinsLostKey, coinsLostQty - feeDeducted);
					}
				}
			}
		}
	}
}
