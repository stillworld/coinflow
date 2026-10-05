package com.coinflow.reconciliation;

import com.coinflow.CoinFlowSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * Unified production and processing reconciliation handler:
 * Handles Cooking, Fletching, Herblore, Crafting, Smithing, and Magic conversions.
 * Deducts session-gathered materials to prevent double profit, and records banked
 * ingredients as supply expenses so net profit accurately reflects the margin.
 */
@Slf4j
public class ProcessingHandler implements ReconciliationHandler
{
	@Override
	public void reconcile(ReconciliationContext context)
	{
		Map<Integer, Integer> rawGains = context.getRawGains();
		Map<Integer, Integer> rawLosses = context.getRawLosses();
		ItemManager itemManager = context.getItemManager();
		CoinFlowSession session = context.getSession();

		if (rawLosses.isEmpty() || rawGains.isEmpty())
		{
			return;
		}

		Map<Integer, Integer> availableSessionGains = new HashMap<>();
		if (session != null)
		{
			for (Map.Entry<Integer, CoinFlowSession.TrackedItem> entry : session.getTrackedItems().entrySet())
			{
				availableSessionGains.put(entry.getKey(), (int) Math.min(Integer.MAX_VALUE, entry.getValue().getRemainingQuantity()));
			}
		}

		Map<Integer, Integer> unmatchedGains = new HashMap<>(rawGains);

		List<Integer> lostKeys = new ArrayList<>(rawLosses.keySet());
		for (int rawLostId : lostKeys)
		{
			Integer lostQty = rawLosses.get(rawLostId);
			if (lostQty == null || lostQty <= 0)
			{
				continue;
			}

			int canonicalRawLostId = itemManager.canonicalize(rawLostId);
			String rawLostName = context.getItemName(canonicalRawLostId);
			if (context.isIgnored(rawLostName))
			{
				continue;
			}

			List<Integer> gainKeys = new ArrayList<>(unmatchedGains.keySet());
			for (int rawGainedId : gainKeys)
			{
				Integer gainedQty = unmatchedGains.get(rawGainedId);
				if (gainedQty == null || gainedQty <= 0)
				{
					continue;
				}

				int canonicalGainedId = itemManager.canonicalize(rawGainedId);
				String gainedName = context.getItemName(canonicalGainedId);
				if (context.isIgnored(gainedName))
				{
					continue;
				}

				ProcessingPatternRegistry.MatchResult match =
					ProcessingPatternRegistry.match(rawLostName, gainedName, gainedQty);

				if (match == null)
				{
					continue;
				}

				int primaryNeeded = match.getPrimaryConsumedQty();
				if (primaryNeeded <= 0)
				{
					continue;
				}

				int primaryToConsume = Math.min(lostQty, primaryNeeded);

				// 1. Apply primary material cost
				applyMaterialCost(
					context,
					canonicalRawLostId,
					rawLostName,
					primaryToConsume,
					availableSessionGains,
					session,
					itemManager
				);

				// Exempt glassblown products (like vials/glasses) from byproduct suppression
				if (rawLostName.equalsIgnoreCase("molten glass"))
				{
					context.addExemptProduct(canonicalGainedId);
				}

				// 2. Decrement unmatched gains and purge failure junk
				if (match.isBurntJunk())
				{
					Integer currentGained = rawGains.get(rawGainedId);
					if (currentGained != null)
					{
						if (currentGained <= gainedQty)
						{
							rawGains.remove(rawGainedId);
						}
						else
						{
							rawGains.put(rawGainedId, currentGained - gainedQty);
						}
					}
					unmatchedGains.remove(rawGainedId);
				}
				else
				{
					int satisfiedGained = primaryNeeded == primaryToConsume
						? gainedQty
						: Math.max(1, (int) Math.floor((double) primaryToConsume * gainedQty / primaryNeeded));

					int remGained = gainedQty - satisfiedGained;
					if (remGained <= 0)
					{
						unmatchedGains.remove(rawGainedId);
					}
					else
					{
						unmatchedGains.put(rawGainedId, remGained);
					}
				}

				// 3. Consume secondary ingredients if present in rawLosses
				if (!match.getSecondariesNeeded().isEmpty())
				{
					for (Map.Entry<String, Integer> secEntry : match.getSecondariesNeeded().entrySet())
					{
						consumeSecondaryIngredient(
							context,
							rawLosses,
							secEntry.getKey(),
							secEntry.getValue(),
							availableSessionGains,
							session,
							itemManager
						);
					}
				}
				else if (match.isPotionFinishing())
				{
					consumeAccompanyingSecondary(
						context,
						rawLosses,
						rawLostId,
						primaryToConsume,
						availableSessionGains,
						session,
						itemManager
					);
				}

				// 4. Update lost quantity
				lostQty -= primaryToConsume;
				if (lostQty <= 0)
				{
					rawLosses.remove(rawLostId);
					break;
				}
				else
				{
					rawLosses.put(rawLostId, lostQty);
				}
			}
		}
	}

	private void consumeSecondaryIngredient(
		ReconciliationContext context,
		Map<Integer, Integer> rawLosses,
		String ingredientName,
		int quantityNeeded,
		Map<Integer, Integer> availableSessionGains,
		CoinFlowSession session,
		ItemManager itemManager)
	{
		for (Map.Entry<Integer, Integer> entry : new ArrayList<>(rawLosses.entrySet()))
		{
			int lostId = entry.getKey();
			int canonicalId = itemManager.canonicalize(lostId);
			String name = context.getItemName(canonicalId);
			boolean matches = false;
			if (ingredientName.contains("|"))
			{
				for (String opt : ingredientName.split("\\|"))
				{
					if (name.equalsIgnoreCase(opt.trim()))
					{
						matches = true;
						break;
					}
				}
			}
			else
			{
				matches = name.equalsIgnoreCase(ingredientName);
			}

			if (matches)
			{
				int available = entry.getValue();
				int toConsume = Math.min(available, quantityNeeded);
				applyMaterialCost(
					context,
					canonicalId,
					name,
					toConsume,
					availableSessionGains,
					session,
					itemManager
				);

				if (available <= toConsume)
				{
					rawLosses.remove(lostId);
				}
				else
				{
					rawLosses.put(lostId, available - toConsume);
				}
				break;
			}
		}
	}

	private void consumeAccompanyingSecondary(
		ReconciliationContext context,
		Map<Integer, Integer> rawLosses,
		int primaryLostId,
		int quantityNeeded,
		Map<Integer, Integer> availableSessionGains,
		CoinFlowSession session,
		ItemManager itemManager)
	{
		for (Map.Entry<Integer, Integer> entry : new ArrayList<>(rawLosses.entrySet()))
		{
			int lostId = entry.getKey();
			if (lostId == primaryLostId)
			{
				continue;
			}

			int canonicalId = itemManager.canonicalize(lostId);
			String name = context.getItemName(canonicalId);
			String lower = name.toLowerCase(java.util.Locale.ROOT);

			// Exclude tools or empty containers
			if (lower.equals("pestle and mortar") || lower.equals("vial")
				|| lower.equals("empty vial") || lower.equals("vial of water")
				|| context.isIgnored(name))
			{
				continue;
			}

			int available = entry.getValue();
			int toConsume = Math.min(available, quantityNeeded);
			applyMaterialCost(
				context,
				canonicalId,
				name,
				toConsume,
				availableSessionGains,
				session,
				itemManager
			);

			if (available <= toConsume)
			{
				rawLosses.remove(lostId);
			}
			else
			{
				rawLosses.put(lostId, available - toConsume);
			}
			break;
		}
	}

	private void applyMaterialCost(
		ReconciliationContext context,
		int itemId,
		String itemName,
		int quantity,
		Map<Integer, Integer> availableSessionGains,
		CoinFlowSession session,
		ItemManager itemManager)
	{
		int availableInSession = availableSessionGains.getOrDefault(itemId, 0);
		int deductibleQty = Math.min(quantity, availableInSession);
		if (deductibleQty > 0)
		{
			availableSessionGains.put(itemId, availableInSession - deductibleQty);
			CoinFlowSession.TrackedItem gainedItem = session != null ? session.getTrackedItems().get(itemId) : null;
			long priceEach = gainedItem != null ? gainedItem.getPriceEach() : itemManager.getItemPrice(itemId);
			CoinFlowSession.TrackedItem deductItem = new CoinFlowSession.TrackedItem(
				itemId, itemName, deductibleQty, priceEach
			);
			context.addDroppedDeduction(itemId, deductItem);
		}

		int remainingSupplyQty = quantity - deductibleQty;
		if (remainingSupplyQty > 0)
		{
			long price = itemManager.getItemPrice(itemId);
			if (price <= 0)
			{
				if (itemId == ItemID.COINS)
				{
					price = 1;
				}
				else
				{
					ItemComposition comp = itemManager.getItemComposition(itemId);
					if (comp != null)
					{
						price = comp.getHaPrice();
					}
				}
			}
			CoinFlowSession.TrackedItem expenseItem = new CoinFlowSession.TrackedItem(
				itemId, itemName, remainingSupplyQty, price
			);
			context.addSupplyExpense(itemId, expenseItem);

			log.debug("Processing ingredient consumed: {} x{} @ {} gp = {} gp",
				itemName, remainingSupplyQty, price, (long) remainingSupplyQty * price);
		}
	}
}
