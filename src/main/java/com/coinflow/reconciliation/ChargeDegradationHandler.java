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

	// Matches repair/recharge-state suffixes: "Blood moon chestplate (broken)",
	// "Torva platelegs (broken)", "Dragonfire shield (uncharged)". "(u)" is
	// deliberately excluded — it means unstrung in fletching (Magic longbow (u)),
	// where the transition is production, not a state change on the same item.
	private static final Pattern BROKEN_PATTERN = Pattern.compile("^(.*?)\\s+\\((broken|uncharged)\\)$", Pattern.CASE_INSENSITIVE);

	// Matches imbued variants: "Seers ring (i)", "Slayer helmet (i)",
	// "Salve amulet(ei)", "Ring of suffering (ri)". All are untradeable.
	private static final Pattern IMBUED_PATTERN = Pattern.compile("^(.*?)\\s*\\((i|ei|ri)\\)$", Pattern.CASE_INSENSITIVE);

	// Suffixes stripped when base-name matching against an imbued side —
	// includes "(e)"/"(r)", the un-imbued enchanted/recoil variants that
	// imbue into (ei)/(ri): "Salve amulet(e)" -> "(ei)", "Ring of suffering (r)" -> "(ri)".
	private static final Pattern IMBUE_BASE_PATTERN = Pattern.compile("^(.*?)\\s*\\((i|ei|ri|e|r)\\)$", Pattern.CASE_INSENSITIVE);

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
				if (context.isDropIntent(canonicalLostId))
				{
					// Dropped, not transformed: leave for the own-drop path
					continue;
				}
				String lostName = context.getItemName(canonicalLostId);

				// Prefer a single-step usage transition (e.g. (3) -> (2)) over a
				// broader recharge/repair match, so a same-base loot pickup in the
				// same tick (e.g. a dropped Ring of dueling(8)) is not paired away.
				Integer gainedMatchId = null;
				Integer broadMatchId = null;
				for (int gainedId : rawGains.keySet())
				{
					int canonicalGainedId = itemManager != null ? itemManager.canonicalize(gainedId) : gainedId;
					String gainedName = context.getItemName(canonicalGainedId);

					if (isUsageStepPair(lostName, gainedName)
						|| isLightSourcePair(canonicalLostId, canonicalGainedId, lostName, gainedName)
						|| MoonWear.isRepairOrDegradePair(canonicalLostId, canonicalGainedId))
					{
						gainedMatchId = gainedId;
						break;
					}
					if (broadMatchId == null && isChargeDegradationPair(lostName, gainedName))
					{
						broadMatchId = gainedId;
					}
				}
				if (gainedMatchId == null)
				{
					gainedMatchId = broadMatchId;
				}

				if (gainedMatchId != null)
				{
					int gainedQty = rawGains.get(gainedMatchId);
					int matched = Math.min(lostQty, gainedQty);
					// A staged Barrows piece returning to its bare name is a
					// repair: arm a fee allowance so the coin loss in this
					// diff is absorbed rather than booked as a fresh spend
					// (wear is expensed as it accrues — see BarrowsWear).
					String matchedGainName = context.getItemName(
						itemManager != null ? itemManager.canonicalize(gainedMatchId) : gainedMatchId);
					BarrowsWear.Piece gainedPiece = BarrowsWear.parse(matchedGainName);
					if (gainedPiece != null && gainedPiece.points == 1000
						&& BarrowsWear.isUpwardPair(lostName, matchedGainName))
					{
						String baseKey = gainedPiece.baseName.toLowerCase(java.util.Locale.ROOT);
						long allowance = BarrowsWear.repairAllowanceGp(lostName) * matched;
						if (context.getBarrowsWearBookedByPiece() != null)
						{
							allowance = Math.min(allowance,
								context.getBarrowsWearBookedByPiece().getOrDefault(baseKey, 0L));
						}
						if (allowance > 0)
						{
							context.addBarrowsRepairAllowance(baseKey, allowance);
						}
					}

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

		// Suppress any lingering partially degraded items, lit light sources, or imbued
		// variants in rawGains (e.g. Slayer ring (2), Seers ring (i)). None of these can
		// be obtained as drops or loot; they indicate owned items converting across
		// split ticks, teleport scene transitions, or unequipped after use.
		if (!rawGains.isEmpty())
		{
			List<Integer> gainedKeys = new ArrayList<>(rawGains.keySet());
			for (int gainedId : gainedKeys)
			{
				int canonicalGainedId = itemManager != null ? itemManager.canonicalize(gainedId) : gainedId;
				String gainedName = context.getItemName(canonicalGainedId);
				if (isPartiallyDegraded(gainedName) || isLitLightSource(canonicalGainedId, gainedName)
					|| isImbuedVariant(gainedName) || MoonWear.isUsedVariant(canonicalGainedId))
				{
					rawGains.remove(gainedId);
					log.debug("Suppressed partially degraded, lit, or imbued item from profit gains: {}", gainedName);
				}
			}
		}
	}

	/**
	 * Checks if lostName transitioned into gainedName via a charge or degrade-state
	 * change on the same item: degradation (Slayer ring (3) -> (2)), depletion
	 * ((1) -> uncharged), recharge ((2) -> (4), base -> (4)), or repairs —
	 * Barrows ("X 50" -> "X", first-use "X" -> "X 100") and broken gear
	 * ("X (broken)" -> "X", "X (uncharged)" -> "X").
	 */
	public static boolean isChargeDegradationPair(String lostName, String gainedName)
	{
		if (lostName == null || gainedName == null)
		{
			return false;
		}

		// 1. Parenthesized charges: same item at a different charge count —
		//    degrade steps, multi-charge drains, or fountain/altar recharges.
		ChargeInfo lostCharge = parseCharge(lostName);
		ChargeInfo gainedCharge = parseCharge(gainedName);
		if (lostCharge != null && gainedCharge != null)
		{
			return gainedCharge.baseName.equalsIgnoreCase(lostCharge.baseName)
				&& gainedCharge.tag.equalsIgnoreCase(lostCharge.tag)
				&& gainedCharge.charges != lostCharge.charges;
		}

		// 2. Charged <-> uncharged base name: (1) depleting to uncharged, or an
		//    uncharged item recharged back up (e.g. glory -> glory(4)).
		if (lostCharge != null && lostCharge.charges == 1 && matchesUnchargedBase(gainedName, lostCharge))
		{
			return true;
		}
		if (gainedCharge != null && matchesUnchargedBase(lostName, gainedCharge))
		{
			return true;
		}

		// 3. Barrows degrade states: same base item moving between any stages
		//    (100 -> 75 -> 50 -> 25 -> 0), to the repaired bare name
		//    ("X 50" -> "X"), or from it on first use ("X" -> "X 100"). The
		//    bare-name direction is limited to Barrows brothers' gear, since the
		//    stage pattern alone matches any name ending in those numbers.
		Matcher barrowsLost = BARROWS_PATTERN.matcher(lostName);
		Matcher barrowsGained = BARROWS_PATTERN.matcher(gainedName);
		boolean lostStaged = barrowsLost.matches();
		boolean gainedStaged = barrowsGained.matches();
		if (lostStaged || gainedStaged)
		{
			String lostBase = lostStaged ? barrowsLost.group(1).trim() : lostName.trim();
			String gainedBase = gainedStaged ? barrowsGained.group(1).trim() : gainedName.trim();
			return lostBase.equalsIgnoreCase(gainedBase)
				&& (lostStaged && gainedStaged || isBarrowsBrotherItem(lostBase));
		}

		// 4. Broken gear repair: "(broken)" appended to the broken variant's
		//    display name makes this a strict same-base comparison in either
		//    direction. The pairing is NPC-agnostic — the diff looks identical
		//    whether the repair came from Bob or a POH armour stand (Torva,
		//    ornamented Barrows) or from Perdu (PvP-death breakables: capes,
		//    defenders, sceptres, trouver-locked gear). "(uncharged)" covers
		//    DFS-family recharges where the two forms are different ids. The
		//    fee (and any consumed charge material) is left to the cash-spend
		//    path. Moons of Peril repairs resolve by id via MoonWear before
		//    this rule is even needed, since their states share one display
		//    name.
		Matcher brokenLost = BROKEN_PATTERN.matcher(lostName);
		Matcher brokenGained = BROKEN_PATTERN.matcher(gainedName);
		boolean lostBroken = brokenLost.matches();
		boolean gainedBroken = brokenGained.matches();
		if (lostBroken || gainedBroken)
		{
			String lostBase = lostBroken ? brokenLost.group(1).trim() : lostName.trim();
			String gainedBase = gainedBroken ? brokenGained.group(1).trim() : gainedName.trim();
			return lostBase.equalsIgnoreCase(gainedBase);
		}

		// 5. Imbue conversions: the same base item gaining or swapping the
		//    imbued suffix (Seers ring -> Seers ring (i), Salve amulet(i) ->
		//    Salve amulet(ei), Ring of suffering (r) -> Ring of suffering (ri))
		//    via NMZ, Soul Wars, or Emir's Arena rewards. Imbued items are
		//    untradeable and never drops, so pairing is safe. The un-imbued
		//    side may itself carry an "(e)"/"(r)" suffix, so both sides are
		//    reduced to their shared base before comparing.
		if (imbuedBaseName(lostName) != null || imbuedBaseName(gainedName) != null)
		{
			return stripImbueSuffix(lostName).equalsIgnoreCase(stripImbueSuffix(gainedName));
		}

		return false;
	}

	/**
	 * Single-step usage transitions only: one charge drained ((3) -> (2)),
	 * (1) -> uncharged, or one Barrows stage down (100 -> 75). These are the
	 * unambiguous per-tick transitions and take priority over broader
	 * recharge/repair matches.
	 */
	static boolean isUsageStepPair(String lostName, String gainedName)
	{
		if (lostName == null || gainedName == null)
		{
			return false;
		}

		ChargeInfo lostCharge = parseCharge(lostName);
		ChargeInfo gainedCharge = parseCharge(gainedName);
		if (lostCharge != null && gainedCharge != null)
		{
			return gainedCharge.baseName.equalsIgnoreCase(lostCharge.baseName)
				&& gainedCharge.tag.equalsIgnoreCase(lostCharge.tag)
				&& gainedCharge.charges == lostCharge.charges - 1;
		}
		if (lostCharge != null && lostCharge.charges == 1 && matchesUnchargedBase(gainedName, lostCharge))
		{
			return true;
		}

		Matcher barrowsLost = BARROWS_PATTERN.matcher(lostName);
		Matcher barrowsGained = BARROWS_PATTERN.matcher(gainedName);
		if (barrowsLost.matches() && barrowsGained.matches())
		{
			return barrowsLost.group(1).trim().equalsIgnoreCase(barrowsGained.group(1).trim())
				&& barrowsGained.group(2).equals(nextBarrowsStage(barrowsLost.group(2)));
		}
		return false;
	}

	private static String imbuedBaseName(String itemName)
	{
		Matcher m = IMBUED_PATTERN.matcher(itemName);
		return m.matches() ? m.group(1).trim() : null;
	}

	/**
	 * The item's base name with any charge-family suffix removed — the imbued
	 * forms (i)/(ei)/(ri) plus the un-imbued (e)/(r) variants that imbue into
	 * them — so an un-imbued-but-suffixed side still matches its imbued twin.
	 */
	private static String stripImbueSuffix(String itemName)
	{
		Matcher m = IMBUE_BASE_PATTERN.matcher(itemName);
		return m.matches() ? m.group(1).trim() : itemName.trim();
	}

	/**
	 * Checks if an item is an imbued variant (Seers ring (i), Slayer helmet (i),
	 * Salve amulet(ei), Ring of suffering (ri)). Imbued items are untradeable
	 * and never obtained as drops or loot, so an unmatched gain is always a
	 * conversion artifact.
	 */
	static boolean isImbuedVariant(String itemName)
	{
		return itemName != null && IMBUED_PATTERN.matcher(itemName).matches();
	}

	private static String nextBarrowsStage(String stage)
	{
		switch (stage)
		{
			case "100": return "75";
			case "75": return "50";
			case "50": return "25";
			case "25": return "0";
			default: return null;
		}
	}

	private static boolean isBarrowsBrotherItem(String baseName)
	{
		return BarrowsWear.isBarrowsBaseName(baseName);
	}

	private static boolean matchesUnchargedBase(String bareName, ChargeInfo charged)
	{
		String expectedUncharged = charged.tag.isEmpty()
			? charged.baseName
			: charged.baseName + " (" + charged.tag + ")";
		return bareName.equalsIgnoreCase(expectedUncharged) || bareName.equalsIgnoreCase(charged.baseName);
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
