package com.coinflow.reconciliation;

import com.coinflow.CoinFlowSession;
import com.coinflow.ConsumableRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * Handles potion dose stepping (e.g. 4-dose -> 3-dose, 1-dose -> empty vial)
 * and potion dose combining/decanting (e.g. 3-dose + 3-dose -> 4-dose + 2-dose),
 * pricing genuinely consumed doses without counting empty vials or decanted doses as loot/expenses.
 */
public class PotionDoseHandler implements ReconciliationHandler
{
	private static class PotionEntry
	{
		final int itemId;
		final int canonicalId;
		final int dose;
		final int qty;

		PotionEntry(int itemId, int canonicalId, int dose, int qty)
		{
			this.itemId = itemId;
			this.canonicalId = canonicalId;
			this.dose = dose;
			this.qty = qty;
		}
	}

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

		// Group lost potions by baseName
		Map<String, List<PotionEntry>> lostByBase = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : rawLosses.entrySet())
		{
			int rawId = entry.getKey();
			int qty = entry.getValue();
			if (qty <= 0)
			{
				continue;
			}
			int canonicalId = itemManager.canonicalize(rawId);
			String name = context.getItemName(canonicalId);
			ConsumableRegistry.PotionDose potion = ConsumableRegistry.parsePotion(name);
			if (potion != null)
			{
				String key = potion.getBaseName().toLowerCase(Locale.ROOT);
				lostByBase.computeIfAbsent(key, k -> new ArrayList<>())
					.add(new PotionEntry(rawId, canonicalId, potion.getDose(), qty));
			}
		}

		if (lostByBase.isEmpty())
		{
			return;
		}

		// Group gained potions by baseName
		Map<String, List<PotionEntry>> gainedByBase = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : rawGains.entrySet())
		{
			int rawId = entry.getKey();
			int qty = entry.getValue();
			if (qty <= 0)
			{
				continue;
			}
			int canonicalId = itemManager.canonicalize(rawId);
			String name = context.getItemName(canonicalId);
			ConsumableRegistry.PotionDose potion = ConsumableRegistry.parsePotion(name);
			if (potion != null)
			{
				String key = potion.getBaseName().toLowerCase(Locale.ROOT);
				gainedByBase.computeIfAbsent(key, k -> new ArrayList<>())
					.add(new PotionEntry(rawId, canonicalId, potion.getDose(), qty));
			}
		}

		// Process each baseName with lost doses
		for (Map.Entry<String, List<PotionEntry>> baseEntry : lostByBase.entrySet())
		{
			String baseKey = baseEntry.getKey();
			List<PotionEntry> lostList = baseEntry.getValue();
			List<PotionEntry> gainedList = gainedByBase.getOrDefault(baseKey, Collections.emptyList());

			int totalLostDoses = 0;
			int totalLostBottles = 0;
			int sampleCanonicalId = -1;
			int sampleDose = -1;
			String sampleBaseName = null;

			for (PotionEntry p : lostList)
			{
				totalLostDoses += p.dose * p.qty;
				totalLostBottles += p.qty;
				if (p.dose > sampleDose)
				{
					sampleCanonicalId = p.canonicalId;
					sampleDose = p.dose;
					ConsumableRegistry.PotionDose parsed = ConsumableRegistry.parsePotion(context.getItemName(p.canonicalId));
					if (parsed != null)
					{
						sampleBaseName = parsed.getBaseName();
					}
				}
			}

			int totalGainedDoses = 0;
			int totalGainedBottles = 0;
			for (PotionEntry p : gainedList)
			{
				totalGainedDoses += p.dose * p.qty;
				totalGainedBottles += p.qty;
			}

			if (totalLostDoses <= 0)
			{
				continue;
			}

			int dosesConsumed = Math.max(0, totalLostDoses - totalGainedDoses);
			int vialsFreed = totalLostBottles - totalGainedBottles;

			// Remove all lost potion items: they transformed into other dose forms
			// (or were consumed as doses), so they are never a raw supply loss.
			for (PotionEntry p : lostList)
			{
				rawLosses.remove(p.itemId);
			}

			if (totalLostDoses >= totalGainedDoses)
			{
				// Decant and/or consumption of this potion: suppress all gains of this base
				for (PotionEntry p : gainedList)
				{
					rawGains.remove(p.itemId);
				}
			}
			else
			{
				// More doses gained than lost (e.g. drank a dose while looting or
				// decanting the same potion in the same tick): suppress only the
				// dose-equivalent of the lost potions, highest dose first.
				// Leftover gained doses remain as legitimate profit.
				int dosesToSuppress = totalLostDoses;
				List<PotionEntry> descendingDose = new ArrayList<>(gainedList);
				descendingDose.sort((a, b) -> Integer.compare(b.dose, a.dose));
				for (PotionEntry p : descendingDose)
				{
					if (dosesToSuppress <= 0)
					{
						break;
					}
					int suppressQty = Math.min(p.qty, dosesToSuppress / p.dose);
					if (suppressQty <= 0)
					{
						continue;
					}
					dosesToSuppress -= suppressQty * p.dose;
					int remaining = rawGains.getOrDefault(p.itemId, 0) - suppressQty;
					if (remaining <= 0)
					{
						rawGains.remove(p.itemId);
					}
					else
					{
						rawGains.put(p.itemId, remaining);
					}
				}
			}

			// If doses were consumed, record supply expense
			if (dosesConsumed > 0 && sampleCanonicalId != -1 && sampleDose > 0)
			{
				long potionPrice = itemManager.getItemPrice(sampleCanonicalId);
				long dosePrice = Math.max(1L, potionPrice / sampleDose);
				String expenseName = (sampleBaseName != null ? sampleBaseName : "Potion") + " (dose)";
				context.addSupplyExpense(sampleCanonicalId,
					new CoinFlowSession.TrackedItem(sampleCanonicalId, expenseName, dosesConsumed, dosePrice));
			}

			// Reconcile empty vials if vials were freed
			if (vialsFreed > 0)
			{
				Integer vialGainId = null;
				for (int gainedId : rawGains.keySet())
				{
					int canonicalGainedId = itemManager.canonicalize(gainedId);
					if (canonicalGainedId == ItemID.VIAL_EMPTY || "Vial".equalsIgnoreCase(context.getItemName(canonicalGainedId)))
					{
						vialGainId = gainedId;
						break;
					}
				}

				if (vialGainId != null)
				{
					int vialQty = rawGains.get(vialGainId);
					if (vialQty <= vialsFreed)
					{
						rawGains.remove(vialGainId);
					}
					else
					{
						rawGains.put(vialGainId, vialQty - vialsFreed);
					}
				}
			}
			// If bottles were consolidated from empty vials:
			else if (vialsFreed < 0)
			{
				int vialsNeeded = -vialsFreed;
				Integer vialLostId = null;
				for (int lostId : rawLosses.keySet())
				{
					int canonicalLostId = itemManager.canonicalize(lostId);
					if (canonicalLostId == ItemID.VIAL_EMPTY || "Vial".equalsIgnoreCase(context.getItemName(canonicalLostId)))
					{
						vialLostId = lostId;
						break;
					}
				}
				if (vialLostId != null)
				{
					int vialQty = rawLosses.get(vialLostId);
					if (vialQty <= vialsNeeded)
					{
						rawLosses.remove(vialLostId);
					}
					else
					{
						rawLosses.put(vialLostId, vialQty - vialsNeeded);
					}
				}
			}
		}
	}
}
