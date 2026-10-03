package com.coinflow.reconciliation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * Handles charged jewelry, tools, and degradable equipment
 * (e.g. Amulet of glory(5) -> Amulet of glory(4), Ring of dueling(8) -> (7),
 * Amulet of glory(1) -> uncharged Amulet of glory, Barrows 100 -> 75 -> 50 -> 25 -> 0).
 * Reconciles the degraded item in rawGains against the higher-charge item in rawLosses,
 * preventing charged jewelry usage from generating false profit loot.
 */
@Slf4j
public class ChargeDegradationHandler implements ReconciliationHandler
{
	// Matches "Amulet of glory(5)", "Ring of dueling (8)", "Amulet of glory (t5)"
	private static final Pattern CHARGED_PATTERN = Pattern.compile("^(.*?)\\s*\\(([a-zA-Z]*)(\\d+)\\)$");

	// Matches Barrows degrade stages: "Dharok's helm 100" -> 75 -> 50 -> 25 -> 0
	private static final Pattern BARROWS_PATTERN = Pattern.compile("^(.*?)\\s+(100|75|50|25|0)$");

	private static final class ChargeInfo
	{
		final String baseName;
		final String tag;
		final int charges;

		ChargeInfo(String baseName, String tag, int charges)
		{
			this.baseName = baseName.trim();
			this.tag = tag != null ? tag.trim() : "";
			this.charges = charges;
		}
	}

	private static ChargeInfo parseCharge(String itemName)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return null;
		}

		Matcher m = CHARGED_PATTERN.matcher(itemName);
		if (m.matches())
		{
			try
			{
				int charges = Integer.parseInt(m.group(3));
				return new ChargeInfo(m.group(1), m.group(2), charges);
			}
			catch (NumberFormatException ignored)
			{
			}
		}

		return null;
	}

	@Override
	public void reconcile(ReconciliationContext context)
	{
		Map<Integer, Integer> rawGains = context.getRawGains();
		Map<Integer, Integer> rawLosses = context.getRawLosses();
		ItemManager itemManager = context.getItemManager();

		if (!rawLosses.isEmpty() && !rawGains.isEmpty())
		{
			List<Integer> lostKeys = new ArrayList<>(rawLosses.keySet());
			for (int lostId : lostKeys)
			{
				Integer lostQtyBoxed = rawLosses.get(lostId);
				if (lostQtyBoxed == null || lostQtyBoxed <= 0)
				{
					continue;
				}
				int lostQty = lostQtyBoxed;

				int canonicalLostId = itemManager != null ? itemManager.canonicalize(lostId) : lostId;
				String lostName = context.getItemName(canonicalLostId);

				Integer gainedMatchId = null;
				for (int gainedId : rawGains.keySet())
				{
					int canonicalGainedId = itemManager != null ? itemManager.canonicalize(gainedId) : gainedId;
					String gainedName = context.getItemName(canonicalGainedId);

					if (isChargeDegradationPair(lostName, gainedName)
						|| isLightSourcePair(canonicalLostId, canonicalGainedId, lostName, gainedName))
					{
						gainedMatchId = gainedId;
						break;
					}
				}

				if (gainedMatchId != null)
				{
					int gainedQty = rawGains.get(gainedMatchId);
					int matched = Math.min(lostQty, gainedQty);

					if (gainedQty <= matched)
					{
						rawGains.remove(gainedMatchId);
					}
					else
					{
						rawGains.put(gainedMatchId, gainedQty - matched);
					}

					if (lostQty <= matched)
					{
						rawLosses.remove(lostId);
					}
					else
					{
						rawLosses.put(lostId, lostQty - matched);
					}

					log.debug("Reconciled degradation/transition: {} -> {} (x{})", lostName, context.getItemName(gainedMatchId), matched);
				}
			}
		}

		// Suppress any lingering partially degraded items or lit light sources in rawGains (e.g. Slayer ring (2))
		// Partially degraded items cannot be obtained as drops or loot and indicate owned items
		// degrading across split ticks, teleport scene transitions, or unequipped after use.
		if (!rawGains.isEmpty())
		{
			List<Integer> gainedKeys = new ArrayList<>(rawGains.keySet());
			for (int gainedId : gainedKeys)
			{
				int canonicalGainedId = itemManager != null ? itemManager.canonicalize(gainedId) : gainedId;
				String gainedName = context.getItemName(canonicalGainedId);
				if (isPartiallyDegraded(gainedName) || isLitLightSource(canonicalGainedId, gainedName))
				{
					rawGains.remove(gainedId);
					log.debug("Suppressed partially degraded or lit item from profit gains: {}", gainedName);
				}
			}
		}
	}

	/**
	 * Checks if lostName degraded into gainedName (e.g. Slayer ring (3) -> Slayer ring (2)).
	 */
	public static boolean isChargeDegradationPair(String lostName, String gainedName)
	{
		if (lostName == null || gainedName == null)
		{
			return false;
		}

		// 1. Parenthesized charges: e.g. (3) -> (2)
		ChargeInfo lostCharge = parseCharge(lostName);
		ChargeInfo gainedCharge = parseCharge(gainedName);
		if (lostCharge != null && gainedCharge != null)
		{
			return gainedCharge.baseName.equalsIgnoreCase(lostCharge.baseName)
				&& gainedCharge.tag.equalsIgnoreCase(lostCharge.tag)
				&& gainedCharge.charges == lostCharge.charges - 1;
		}

		// 2. Depleted to uncharged: e.g. (1) -> uncharged
		if (lostCharge != null && lostCharge.charges == 1)
		{
			String expectedUncharged = lostCharge.tag.isEmpty()
				? lostCharge.baseName
				: lostCharge.baseName + " (" + lostCharge.tag + ")";
			if (gainedName.equalsIgnoreCase(expectedUncharged) || gainedName.equalsIgnoreCase(lostCharge.baseName))
			{
				return true;
			}
		}

		// 3. Barrows degradation: 100 -> 75 -> 50 -> 25 -> 0
		Matcher barrowsLost = BARROWS_PATTERN.matcher(lostName);
		Matcher barrowsGained = BARROWS_PATTERN.matcher(gainedName);
		if (barrowsLost.matches() && barrowsGained.matches())
		{
			String baseLost = barrowsLost.group(1);
			String baseGained = barrowsGained.group(1);
			String nextStage = getNextBarrowsStage(barrowsLost.group(2));
			return baseLost.equalsIgnoreCase(baseGained) && barrowsGained.group(2).equals(nextStage);
		}

		return false;
	}

	/**
	 * Checks if an item is a partially degraded variant that cannot be dropped as loot
	 * or produced from crafting (e.g. Slayer ring (2), Ring of dueling(7), Barrows 75/50/25/0).
	 */
	public static boolean isPartiallyDegraded(String itemName)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return false;
		}

		// 1. Barrows degraded stages (75, 50, 25, 0 are degraded; 100 is pristine)
		Matcher barrowsMatcher = BARROWS_PATTERN.matcher(itemName);
		if (barrowsMatcher.matches())
		{
			String stage = barrowsMatcher.group(2);
			return !"100".equals(stage);
		}

		// 2. Parenthesized charges
		ChargeInfo charge = parseCharge(itemName);
		if (charge == null)
		{
			return false;
		}

		String lowerBase = charge.baseName.toLowerCase(java.util.Locale.ROOT);
		int charges = charge.charges;

		if (lowerBase.contains("slayer ring"))
		{
			return charges < 8;
		}
		if (lowerBase.contains("ring of dueling") || lowerBase.contains("games necklace") || lowerBase.contains("pharaoh's sceptre"))
		{
			return charges < 8;
		}
		if (lowerBase.contains("glory") || lowerBase.contains("combat bracelet") || lowerBase.contains("skills necklace"))
		{
			return charges < 6;
		}
		if (lowerBase.contains("necklace of passage") || lowerBase.contains("burning amulet")
			|| lowerBase.contains("digsite pendant") || lowerBase.contains("ring of wealth")
			|| lowerBase.contains("abyssal bracelet"))
		{
			return charges < 5;
		}
		if (lowerBase.contains("dodgy necklace"))
		{
			return charges < 10;
		}
		if (lowerBase.contains("binding necklace"))
		{
			return charges < 16;
		}

		return false;
	}

	private static String getNextBarrowsStage(String currentStage)
	{
		switch (currentStage)
		{
			case "100": return "75";
			case "75": return "50";
			case "50": return "25";
			case "25": return "0";
			default: return null;
		}
	}

	public static boolean isLightSourcePair(int lostId, int gainedId, String lostName, String gainedName)
	{
		if ((lostId == ItemID.BULLSEYE_LANTERN_LIT && gainedId == ItemID.BULLSEYE_LANTERN_UNLIT)
			|| (lostId == ItemID.BULLSEYE_LANTERN_UNLIT && gainedId == ItemID.BULLSEYE_LANTERN_LIT)
			|| (lostId == ItemID.BULLSEYE_LANTERN_LIT_LUNAR_QUEST && gainedId == ItemID.BULLSEYE_LANTERN_UNLIT_LUNAR_QUEST)
			|| (lostId == ItemID.BULLSEYE_LANTERN_UNLIT_LUNAR_QUEST && gainedId == ItemID.BULLSEYE_LANTERN_LIT_LUNAR_QUEST)
			|| (lostId == ItemID.OIL_LANTERN_LIT && gainedId == ItemID.OIL_LANTERN_UNLIT)
			|| (lostId == ItemID.OIL_LANTERN_UNLIT && gainedId == ItemID.OIL_LANTERN_LIT)
			|| (lostId == ItemID.CANDLE_LANTERN_LIT && gainedId == ItemID.CANDLE_LANTERN_UNLIT)
			|| (lostId == ItemID.CANDLE_LANTERN_UNLIT && gainedId == ItemID.CANDLE_LANTERN_LIT)
			|| (lostId == ItemID.CANDLE_LANTERN_BLACK_LIT && gainedId == ItemID.CANDLE_LANTERN_BLACK_UNLIT)
			|| (lostId == ItemID.CANDLE_LANTERN_BLACK_UNLIT && gainedId == ItemID.CANDLE_LANTERN_BLACK_LIT)
			|| (lostId == ItemID.OIL_LAMP_LIT && gainedId == ItemID.OIL_LAMP_UNLIT)
			|| (lostId == ItemID.OIL_LAMP_UNLIT && gainedId == ItemID.OIL_LAMP_LIT)
			|| (lostId == ItemID.TOG_SAPPHIRE_LANTERN_LIT && gainedId == ItemID.TOG_SAPPHIRE_LANTERN_UNLIT)
			|| (lostId == ItemID.TOG_SAPPHIRE_LANTERN_UNLIT && gainedId == ItemID.TOG_SAPPHIRE_LANTERN_LIT)
			|| (lostId == ItemID.CAVE_GOBLIN_MINING_HELMET_LIT && gainedId == ItemID.CAVE_GOBLIN_MINING_HELMET_UNLIT)
			|| (lostId == ItemID.CAVE_GOBLIN_MINING_HELMET_UNLIT && gainedId == ItemID.CAVE_GOBLIN_MINING_HELMET_LIT)
			|| (lostId == ItemID.LIT_CANDLE && gainedId == ItemID.UNLIT_CANDLE)
			|| (lostId == ItemID.UNLIT_CANDLE && gainedId == ItemID.LIT_CANDLE)
			|| (lostId == ItemID.LIT_BLACK_CANDLE && gainedId == ItemID.UNLIT_BLACK_CANDLE)
			|| (lostId == ItemID.UNLIT_BLACK_CANDLE && gainedId == ItemID.LIT_BLACK_CANDLE)
			|| (lostId == ItemID.TORCH_LIT && gainedId == ItemID.TORCH_UNLIT)
			|| (lostId == ItemID.TORCH_UNLIT && gainedId == ItemID.TORCH_LIT))
		{
			return true;
		}

		if (lostName != null && gainedName != null)
		{
			String l = lostName.toLowerCase(java.util.Locale.ROOT);
			String g = gainedName.toLowerCase(java.util.Locale.ROOT);
			String lBase = l.replace(" (lit)", "").replace(" lit", "").replace(" (unlit)", "").replace(" unlit", "").trim();
			String gBase = g.replace(" (lit)", "").replace(" lit", "").replace(" (unlit)", "").replace(" unlit", "").trim();
			if (!lBase.isEmpty() && lBase.equals(gBase))
			{
				boolean lIsLit = l.contains("lit");
				boolean gIsLit = g.contains("lit");
				if (lIsLit || gIsLit)
				{
					return true;
				}
			}
		}

		return false;
	}

	public static boolean isLitLightSource(int id, String name)
	{
		if (id == ItemID.BULLSEYE_LANTERN_LIT
			|| id == ItemID.BULLSEYE_LANTERN_LIT_LUNAR_QUEST
			|| id == ItemID.OIL_LANTERN_LIT
			|| id == ItemID.CANDLE_LANTERN_LIT
			|| id == ItemID.CANDLE_LANTERN_BLACK_LIT
			|| id == ItemID.OIL_LAMP_LIT
			|| id == ItemID.TOG_SAPPHIRE_LANTERN_LIT
			|| id == ItemID.CAVE_GOBLIN_MINING_HELMET_LIT
			|| id == ItemID.LIT_CANDLE
			|| id == ItemID.LIT_BLACK_CANDLE
			|| id == ItemID.TORCH_LIT)
		{
			return true;
		}
		if (name != null)
		{
			String lower = name.toLowerCase(java.util.Locale.ROOT);
			return (lower.contains("lantern") || lower.contains("candle") || lower.contains("torch") || lower.contains("lamp") || lower.contains("helmet"))
				&& (lower.contains("(lit)") || lower.startsWith("lit ") || lower.endsWith(" (lit)"));
		}
		return false;
	}
}
