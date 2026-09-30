package com.coinflow.reconciliation;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stateless semantic pattern matcher for Old School RuneScape production and processing skills.
 * Recognizes transformations in Cooking, Fletching, Herblore, Crafting, Smithing, and Magic.
 */
public final class ProcessingPatternRegistry
{
	private ProcessingPatternRegistry() {}

	public static class MatchResult
	{
		private final int primaryConsumedQty;
		private final Map<String, Integer> secondariesNeeded;
		private final boolean isBurntJunk;
		private final boolean isPotionFinishing;

		public MatchResult(int primaryConsumedQty, Map<String, Integer> secondariesNeeded, boolean isBurntJunk)
		{
			this(primaryConsumedQty, secondariesNeeded, isBurntJunk, false);
		}

		public MatchResult(int primaryConsumedQty, Map<String, Integer> secondariesNeeded, boolean isBurntJunk, boolean isPotionFinishing)
		{
			this.primaryConsumedQty = primaryConsumedQty;
			this.secondariesNeeded = secondariesNeeded != null ? secondariesNeeded : Collections.emptyMap();
			this.isBurntJunk = isBurntJunk;
			this.isPotionFinishing = isPotionFinishing;
		}

		public int getPrimaryConsumedQty()
		{
			return primaryConsumedQty;
		}

		public Map<String, Integer> getSecondariesNeeded()
		{
			return secondariesNeeded;
		}

		public boolean isBurntJunk()
		{
			return isBurntJunk;
		}

		public boolean isPotionFinishing()
		{
			return isPotionFinishing;
		}
	}

	// ── Common Gem Names ──────────────────────────────────────────────────
	private static final Set<String> GEMS = new HashSet<>();
	static
	{
		GEMS.add("sapphire");
		GEMS.add("emerald");
		GEMS.add("ruby");
		GEMS.add("diamond");
		GEMS.add("dragonstone");
		GEMS.add("onyx");
		GEMS.add("zenyte");
		GEMS.add("opal");
		GEMS.add("jade");
		GEMS.add("red topaz");
	}

	// ── Common Metal Names ────────────────────────────────────────────────
	private static final Set<String> METALS = new HashSet<>();
	static
	{
		METALS.add("bronze");
		METALS.add("iron");
		METALS.add("steel");
		METALS.add("silver");
		METALS.add("gold");
		METALS.add("mithril");
		METALS.add("adamantite");
		METALS.add("adamant");
		METALS.add("runite");
		METALS.add("rune");
	}

	// ── Coal required per bar ─────────────────────────────────────────────
	private static final Map<String, Integer> SMELTING_COAL_COST = new HashMap<>();
	static
	{
		SMELTING_COAL_COST.put("steel", 2);
		SMELTING_COAL_COST.put("mithril", 4);
		SMELTING_COAL_COST.put("adamantite", 6);
		SMELTING_COAL_COST.put("adamant", 6);
		SMELTING_COAL_COST.put("runite", 8);
		SMELTING_COAL_COST.put("rune", 8);
	}

	// ── Smithing equipment bar costs ──────────────────────────────────────
	private static final Map<String, Integer> SMITHING_BAR_COSTS = new HashMap<>();
	static
	{
		SMITHING_BAR_COSTS.put("dagger", 1);
		SMITHING_BAR_COSTS.put("axe", 1);
		SMITHING_BAR_COSTS.put("mace", 1);
		SMITHING_BAR_COSTS.put("medium helm", 1);
		SMITHING_BAR_COSTS.put("med helm", 1);
		SMITHING_BAR_COSTS.put("dart tip", 1);
		SMITHING_BAR_COSTS.put("dart tips", 1);
		SMITHING_BAR_COSTS.put("arrowtip", 1);
		SMITHING_BAR_COSTS.put("arrowtips", 1);
		SMITHING_BAR_COSTS.put("bolt (unf)", 1);
		SMITHING_BAR_COSTS.put("bolts (unf)", 1);
		SMITHING_BAR_COSTS.put("javelin head", 1);
		SMITHING_BAR_COSTS.put("javelin heads", 1);
		SMITHING_BAR_COSTS.put("knife", 1);
		SMITHING_BAR_COSTS.put("knives", 1);
		SMITHING_BAR_COSTS.put("wire", 1);
		SMITHING_BAR_COSTS.put("limbs", 1);
		SMITHING_BAR_COSTS.put("crossbow limbs", 1);
		SMITHING_BAR_COSTS.put("nails", 1);
		SMITHING_BAR_COSTS.put("spit", 1);
		SMITHING_BAR_COSTS.put("studs", 1);

		SMITHING_BAR_COSTS.put("sword", 2);
		SMITHING_BAR_COSTS.put("scimitar", 2);
		SMITHING_BAR_COSTS.put("longsword", 2);
		SMITHING_BAR_COSTS.put("full helm", 2);
		SMITHING_BAR_COSTS.put("sq shield", 2);

		SMITHING_BAR_COSTS.put("warhammer", 3);
		SMITHING_BAR_COSTS.put("battleaxe", 3);
		SMITHING_BAR_COSTS.put("chainbody", 3);
		SMITHING_BAR_COSTS.put("kiteshield", 3);
		SMITHING_BAR_COSTS.put("kite shield", 3);
		SMITHING_BAR_COSTS.put("2h sword", 3);
		SMITHING_BAR_COSTS.put("two-handed sword", 3);
		SMITHING_BAR_COSTS.put("platelegs", 3);
		SMITHING_BAR_COSTS.put("plateskirt", 3);

		SMITHING_BAR_COSTS.put("platebody", 5);
	}

	// ── Dragonhide armor leather costs ────────────────────────────────────
	private static final Map<String, Integer> DHIDE_LEATHER_COSTS = new HashMap<>();
	static
	{
		DHIDE_LEATHER_COSTS.put("vambraces", 1);
		DHIDE_LEATHER_COSTS.put("vambs", 1);
		DHIDE_LEATHER_COSTS.put("gloves", 1);
		DHIDE_LEATHER_COSTS.put("boots", 1);
		DHIDE_LEATHER_COSTS.put("coif", 2);
		DHIDE_LEATHER_COSTS.put("chaps", 2);
		DHIDE_LEATHER_COSTS.put("shield", 2);
		DHIDE_LEATHER_COSTS.put("body", 3);
	}

	// ── Glassblowing products ─────────────────────────────────────────────
	private static final Set<String> GLASSBLOWING_PRODUCTS = new HashSet<>();
	static
	{
		GLASSBLOWING_PRODUCTS.add("beer glass");
		GLASSBLOWING_PRODUCTS.add("empty candle lantern");
		GLASSBLOWING_PRODUCTS.add("candle lantern");
		GLASSBLOWING_PRODUCTS.add("oil lamp");
		GLASSBLOWING_PRODUCTS.add("vial");
		GLASSBLOWING_PRODUCTS.add("empty vial");
		GLASSBLOWING_PRODUCTS.add("fishbowl");
		GLASSBLOWING_PRODUCTS.add("unpowered orb");
		GLASSBLOWING_PRODUCTS.add("lantern lens");
		GLASSBLOWING_PRODUCTS.add("empty light orb");
		GLASSBLOWING_PRODUCTS.add("light orb");
	}

	/**
	 * Matches a lost item and gained item to see if they represent a valid processing transformation.
	 *
	 * @param lostName   the name of the item lost from inventory
	 * @param gainedName the name of the item gained in inventory
	 * @param gainedQty  the quantity of product gained
	 * @return MatchResult if matched, or null if unrelated
	 */
	public static MatchResult match(String lostName, String gainedName, int gainedQty)
	{
		if (lostName == null || gainedName == null || gainedQty <= 0)
		{
			return null;
		}

		String lostLower = lostName.trim().toLowerCase(Locale.ROOT);
		String gainedLower = gainedName.trim().toLowerCase(Locale.ROOT);

		// 1. Cooking
		MatchResult cooking = matchCooking(lostLower, gainedLower, gainedQty);
		if (cooking != null)
		{
			return cooking;
		}

		// 2. Fletching
		MatchResult fletching = matchFletching(lostLower, gainedLower, gainedQty);
		if (fletching != null)
		{
			return fletching;
		}

		// 3. Herblore
		MatchResult herblore = matchHerblore(lostLower, gainedLower, gainedQty);
		if (herblore != null)
		{
			return herblore;
		}

		// 4. Crafting
		MatchResult crafting = matchCrafting(lostLower, gainedLower, gainedQty);
		if (crafting != null)
		{
			return crafting;
		}

		// 5. Smithing
		MatchResult smithing = matchSmithing(lostLower, gainedLower, gainedQty);
		if (smithing != null)
		{
			return smithing;
		}

		// 6. Magic Transmutation (Plank Make)
		MatchResult magic = matchMagicTransmutation(lostLower, gainedLower, gainedQty);
		if (magic != null)
		{
			return magic;
		}

		// 7. Runecrafting
		MatchResult runecrafting = matchRunecrafting(lostLower, gainedLower, gainedQty);
		if (runecrafting != null)
		{
			return runecrafting;
		}

		return null;
	}

	// ── 1. Cooking ────────────────────────────────────────────────────────
	private static MatchResult matchCooking(String lost, String gained, int gainedQty)
	{
		// A. Wine making: grapes -> jug of wine / jug of bad wine
		if (lost.equals("grapes") && (gained.equals("jug of wine") || gained.equals("jug of bad wine")))
		{
			Map<String, Integer> secondaries = new HashMap<>();
			secondaries.put("jug of water", gainedQty);
			return new MatchResult(gainedQty, secondaries, gained.equals("jug of bad wine"));
		}

		// B. Dough making: pot of flour -> bread dough / pastry dough / pizza base / biscuit dough / pitta dough
		if (lost.equals("pot of flour"))
		{
			if (gained.equals("bread dough") || gained.equals("pastry dough")
				|| gained.equals("pizza base") || gained.equals("biscuit dough")
				|| gained.equals("pitta dough"))
			{
				Map<String, Integer> secondaries = new HashMap<>();
				secondaries.put("jug of water|bucket of water", gainedQty);
				return new MatchResult(gainedQty, secondaries, false);
			}
		}

		// C. Bread baking: bread dough -> bread / burnt bread
		if (lost.equals("bread dough"))
		{
			if (gained.equals("bread"))
			{
				return new MatchResult(gainedQty, null, false);
			}
			if (gained.equals("burnt bread"))
			{
				return new MatchResult(gainedQty, null, true);
			}
		}

		String rawBase = null;
		if (lost.startsWith("raw "))
		{
			rawBase = lost.substring(4).trim();
		}
		else if (lost.startsWith("uncooked "))
		{
			rawBase = lost.substring(9).trim();
		}
		else if (lost.equals("potato"))
		{
			rawBase = "potato";
		}

		if (rawBase == null || rawBase.isEmpty())
		{
			return null;
		}

		boolean isBurnt = gained.equals("burnt " + rawBase) || gained.equals("burnt fish")
			|| gained.equals("burnt meat") || (rawBase.endsWith("pie") && gained.equals("burnt pie"))
			|| (rawBase.equals("pizza") && gained.equals("burnt pizza"));

		boolean isCooked = gained.equals(rawBase)
			|| gained.equals("cooked " + rawBase)
			|| (rawBase.equals("pizza") && gained.equals("plain pizza"))
			|| (rawBase.equals("karambwan") && gained.equals("poison karambwan"))
			|| (rawBase.contains("meat") && gained.equals("cooked meat"))
			|| (rawBase.equals("potato") && gained.equals("baked potato"));

		if (isCooked || isBurnt)
		{
			return new MatchResult(gainedQty, null, isBurnt);
		}

		return null;
	}

	// ── 2. Fletching ──────────────────────────────────────────────────────
	private static MatchResult matchFletching(String lost, String gained, int gainedQty)
	{
		// A. Cutting Logs
		if (lost.equals("logs") || lost.equals("log") || lost.endsWith(" logs") || lost.endsWith(" log"))
		{
			String wood = "";
			if (lost.endsWith(" logs"))
			{
				wood = lost.substring(0, lost.length() - 5).trim();
			}
			else if (lost.endsWith(" log"))
			{
				wood = lost.substring(0, lost.length() - 4).trim();
			}

			String shortbowSpaced = wood.isEmpty() ? "shortbow (u)" : wood + " shortbow (u)";
			String shortbowTight = wood.isEmpty() ? "shortbow(u)" : wood + " shortbow(u)";
			if (gained.equals(shortbowSpaced) || gained.equals(shortbowTight))
			{
				return new MatchResult(gainedQty, null, false);
			}

			String longbowSpaced = wood.isEmpty() ? "longbow (u)" : wood + " longbow (u)";
			String longbowTight = wood.isEmpty() ? "longbow(u)" : wood + " longbow(u)";
			if (gained.equals(longbowSpaced) || gained.equals(longbowTight))
			{
				return new MatchResult(gainedQty, null, false);
			}

			String expectedStock = wood.isEmpty() ? "wooden stock" : wood + " stock";
			if (gained.equals(expectedStock))
			{
				return new MatchResult(gainedQty, null, false);
			}

			String expectedShield = wood.isEmpty() ? "wooden shield" : wood + " shield";
			if (gained.equals(expectedShield) || gained.equals(expectedShield + " (u)") || gained.equals(expectedShield + "(u)"))
			{
				return new MatchResult(gainedQty * 2, null, false);
			}

			if (gained.equals("arrow shaft"))
			{
				int perLog = wood.equals("redwood") ? 60 : 15;
				int logsNeeded = (int) Math.ceil((double) gainedQty / perLog);
				return new MatchResult(logsNeeded, null, false);
			}

			if (gained.equals("javelin shaft"))
			{
				int logsNeeded = (int) Math.ceil((double) gainedQty / 15);
				return new MatchResult(logsNeeded, null, false);
			}
		}

		// B. Stringing Bows, Crossbows & Shields: <Item> (u) -> <Item> + String
		if (lost.endsWith(" (u)") || lost.endsWith("(u)"))
		{
			String finished = lost.endsWith(" (u)")
				? lost.substring(0, lost.length() - 4).trim()
				: lost.substring(0, lost.length() - 3).trim();
			if (gained.equals(finished))
			{
				Map<String, Integer> secondaries = new HashMap<>();
				if (finished.endsWith(" shield"))
				{
					secondaries.put("bow string", gainedQty * 2);
				}
				else if (finished.endsWith(" crossbow"))
				{
					secondaries.put("crossbow string", gainedQty);
				}
				else
				{
					secondaries.put("bow string", gainedQty);
				}
				return new MatchResult(gainedQty, secondaries, false);
			}
		}

		// C. Feathering Arrow Shafts: Arrow shaft -> Headless arrow + Feathers
		if (lost.equals("arrow shaft") && gained.equals("headless arrow"))
		{
			Map<String, Integer> secondaries = new HashMap<>();
			secondaries.put("feather", gainedQty);
			return new MatchResult(gainedQty, secondaries, false);
		}

		// D. Tipping Arrows: Headless arrow -> <Metal> arrow + <Metal> arrowtips / arrowheads
		if (lost.equals("headless arrow") && gained.endsWith(" arrow") && !gained.contains("headless"))
		{
			String metal = gained.substring(0, gained.length() - 6).trim();
			String secondaryName = metal.equals("broad") ? "broad arrowheads" : metal + " arrowtips";
			Map<String, Integer> secondaries = new HashMap<>();
			secondaries.put(secondaryName, gainedQty);
			return new MatchResult(gainedQty, secondaries, false);
		}

		// E. Feathering Darts: <Metal> dart tip -> <Metal> dart + Feather
		if (lost.endsWith(" dart tip") && gained.endsWith(" dart"))
		{
			String metalLost = lost.substring(0, lost.length() - 9).trim();
			String metalGained = gained.substring(0, gained.length() - 5).trim();
			if (metalLost.equals(metalGained))
			{
				Map<String, Integer> secondaries = new HashMap<>();
				secondaries.put("feather", gainedQty);
				return new MatchResult(gainedQty, secondaries, false);
			}
		}

		// F. Feathering Bolts: <Metal> bolt (unf) -> <Metal> bolts + Feather
		if ((lost.endsWith(" bolt (unf)") || lost.endsWith(" bolts (unf)")) && gained.endsWith(" bolts"))
		{
			Map<String, Integer> secondaries = new HashMap<>();
			secondaries.put("feather", gainedQty);
			return new MatchResult(gainedQty, secondaries, false);
		}

		// G. Tipping Bolts: <Metal> bolts -> <Gem> bolts / <Gem> <metal> bolts + <Gem> bolt tips
		if (lost.endsWith(" bolts") && gained.endsWith(" bolts") && !lost.equals(gained))
		{
			for (String gem : GEMS)
			{
				if (gained.startsWith(gem + " "))
				{
					Map<String, Integer> secondaries = new HashMap<>();
					secondaries.put(gem + " bolt tips", gainedQty);
					return new MatchResult(gainedQty, secondaries, false);
				}
			}
			if (gained.startsWith("amethyst "))
			{
				Map<String, Integer> secondaries = new HashMap<>();
				secondaries.put("amethyst bolt tips|amethyst bolt tip", gainedQty);
				return new MatchResult(gainedQty, secondaries, false);
			}
		}

		// H. Crossbow Assembly: Limbs + Stock -> <Metal> crossbow (u)
		if (lost.endsWith(" limbs") || lost.endsWith(" limb"))
		{
			String metal = lost.endsWith(" limbs")
				? lost.substring(0, lost.length() - 6).trim()
				: lost.substring(0, lost.length() - 5).trim();
			if (metal.equals("runite")) metal = "rune";
			if (metal.equals("adamantite")) metal = "adamant";
			if (gained.equals(metal + " crossbow (u)") || gained.equals(metal + " crossbow(u)")
				|| gained.equals("unstrung " + metal + " crossbow"))
			{
				Map<String, Integer> secondaries = new HashMap<>();
				secondaries.put("wooden stock|oak stock|willow stock|teak stock|maple stock|mahogany stock", gainedQty);
				return new MatchResult(gainedQty, secondaries, false);
			}
		}
		if (lost.endsWith(" stock"))
		{
			if (gained.endsWith(" crossbow (u)") || gained.endsWith(" crossbow(u)")
				|| (gained.startsWith("unstrung ") && gained.endsWith(" crossbow")))
			{
				Map<String, Integer> secondaries = new HashMap<>();
				secondaries.put("bronze limbs|iron limbs|steel limbs|mithril limbs|adamantite limbs|adamant limbs|runite limbs|rune limbs", gainedQty);
				return new MatchResult(gainedQty, secondaries, false);
			}
		}

		// I. Crossbow Stringing: <Metal> crossbow (u) -> <Metal> crossbow + Crossbow string
		if ((lost.endsWith(" crossbow (u)") || lost.endsWith(" crossbow(u)") || (lost.startsWith("unstrung ") && lost.endsWith(" crossbow")))
			&& gained.endsWith(" crossbow") && !gained.contains("(u)"))
		{
			Map<String, Integer> secondaries = new HashMap<>();
			secondaries.put("crossbow string", gainedQty);
			return new MatchResult(gainedQty, secondaries, false);
		}

		// J. Javelin Assembly: Shafts + Feathers -> Headless javelin; Headless javelin + Heads -> Javelin
		if (lost.equals("javelin shaft") && gained.equals("headless javelin"))
		{
			Map<String, Integer> secondaries = new HashMap<>();
			secondaries.put("feather", gainedQty);
			return new MatchResult(gainedQty, secondaries, false);
		}
		if (lost.equals("headless javelin") && gained.endsWith(" javelin") && !gained.contains("shaft"))
		{
			String javelinType = gained.substring(0, gained.length() - 8).trim();
			Map<String, Integer> secondaries = new HashMap<>();
			secondaries.put(javelinType + " javelin heads|" + javelinType + " javelin head", gainedQty);
			return new MatchResult(gainedQty, secondaries, false);
		}

		return null;
	}

	// ── 3. Herblore ───────────────────────────────────────────────────────
	private static MatchResult matchHerblore(String lost, String gained, int gainedQty)
	{
		// A. Cleaning Herbs: Grimy <herb> -> <herb> / Clean <herb>
		if (lost.startsWith("grimy "))
		{
			String cleanName = lost.substring(6).trim();
			if (gained.equals(cleanName) || gained.equals("clean " + cleanName))
			{
				return new MatchResult(gainedQty, null, false);
			}
		}

		// B. Unfinished Potions: <herb> -> <herb> potion (unf) + Vial of water
		if (gained.endsWith(" potion (unf)") || gained.endsWith(" (unf)"))
		{
			String base = gained.endsWith(" potion (unf)")
				? gained.substring(0, gained.length() - 13).trim()
				: gained.substring(0, gained.length() - 6).trim();

			if (lost.equals(base) || lost.startsWith(base) || lost.contains(base))
			{
				Map<String, Integer> secondaries = new HashMap<>();
				secondaries.put("vial of water", gainedQty);
				return new MatchResult(gainedQty, secondaries, false);
			}
		}

		// C. Finishing Potions: <herb> potion (unf) -> <potion>(3) or <potion>(4) or <brew>(3)/(4)
		if (lost.endsWith(" potion (unf)") || lost.endsWith(" (unf)"))
		{
			if (gained.endsWith("(3)") || gained.endsWith("(4)") || gained.contains("potion")
				|| gained.endsWith(" brew(3)") || gained.endsWith(" brew(4)"))
			{
				return new MatchResult(gainedQty, null, false, true);
			}
		}

		// D. Crushing Ingredients with Pestle and Mortar: <Item> -> Crushed <Item> / <Item> dust
		if (gained.equals("crushed " + lost) || gained.equals(lost + " dust"))
		{
			return new MatchResult(gainedQty, null, false);
		}
		if (lost.equals("blue dragon scale") && gained.equals("dragon scale dust"))
		{
			return new MatchResult(gainedQty, null, false);
		}
		if (lost.equals("chocolate bar") && gained.equals("chocolate dust"))
		{
			return new MatchResult(gainedQty, null, false);
		}
		if (lost.equals("bird nest") && gained.equals("crushed nest"))
		{
			return new MatchResult(gainedQty, null, false);
		}
		if (lost.equals("lava scale") && (gained.equals("lava scale shard") || gained.equals("lava scale shards")))
		{
			return new MatchResult(gainedQty, null, false);
		}

		// E. Herb Tar: <Herb> + Swamp tar -> <Herb> tar (15 tar per 1 herb, 15 swamp tar per 15 herb tar)
		if (gained.endsWith(" tar") && !gained.equals("swamp tar"))
		{
			String herb = gained.substring(0, gained.length() - 4).trim();
			if (lost.startsWith(herb))
			{
				int herbsNeeded = (int) Math.ceil((double) gainedQty / 15);
				Map<String, Integer> secondaries = new HashMap<>();
				secondaries.put("swamp tar", gainedQty);
				return new MatchResult(herbsNeeded, secondaries, false);
			}
		}

		// F. Divine Potions: <Base Potion>(4) + Crystal dust -> Divine <Base Potion>(4) (4 dust per potion)
		if (gained.startsWith("divine "))
		{
			String basePotion = gained.substring(7).trim();
			if (lost.equals(basePotion))
			{
				Map<String, Integer> secondaries = new HashMap<>();
				secondaries.put("crystal dust", gainedQty * 4);
				return new MatchResult(gainedQty, secondaries, false);
			}
		}

		// G. Extended Antifires: Antifire potion(4) + Lava scale shard -> Extended antifire(4)
		if (gained.startsWith("extended antifire") || gained.startsWith("extended super antifire"))
		{
			String basePotion = gained.substring(9).trim();
			if (basePotion.startsWith("antifire"))
			{
				String expectedLost = "antifire potion" + basePotion.substring(8);
				if (lost.equals(expectedLost) || lost.equals(basePotion))
				{
					Map<String, Integer> secondaries = new HashMap<>();
					secondaries.put("lava scale shard|lava scale shards", gainedQty * 4);
					return new MatchResult(gainedQty, secondaries, false);
				}
			}
			else if (basePotion.startsWith("super antifire"))
			{
				String expectedLost = "super antifire potion" + basePotion.substring(14);
				if (lost.equals(expectedLost) || lost.equals(basePotion))
				{
					Map<String, Integer> secondaries = new HashMap<>();
					secondaries.put("lava scale shard|lava scale shards", gainedQty * 4);
					return new MatchResult(gainedQty, secondaries, false);
				}
			}
		}

		return null;
	}

	// ── 4. Crafting ───────────────────────────────────────────────────────
	private static MatchResult matchCrafting(String lost, String gained, int gainedQty)
	{
		// A. Gem Cutting: Uncut <gem> -> <gem>
		if (lost.startsWith("uncut "))
		{
			String gem = lost.substring(6).trim();
			if (gained.equals(gem) || gained.equals("cut " + gem))
			{
				return new MatchResult(gainedQty, null, false);
			}
			// Crushed gem failure (Opal, Jade, Red Topaz)
			if (gained.equals("crushed gem"))
			{
				return new MatchResult(gainedQty, null, true);
			}
		}

		// B. Jewellery: Gold bar / Silver bar -> <Gem> ring / necklace / bracelet / amulet (u)
		if (lost.equals("gold bar") || lost.equals("silver bar"))
		{
			for (String gem : GEMS)
			{
				String gemPrefix = gem.equals("red topaz") ? "topaz " : gem + " ";
				if (gained.startsWith(gemPrefix) || gained.startsWith(gem + " "))
				{
					Map<String, Integer> secondaries = new HashMap<>();
					secondaries.put(gem, gainedQty);
					return new MatchResult(gainedQty, secondaries, false);
				}
			}
			// Plain gold/silver jewellery & symbols
			if (gained.startsWith("gold ") || gained.startsWith("silver ")
				|| gained.equals("tiara") || gained.equals("holy symbol (u)")
				|| gained.equals("unholy symbol (u)") || gained.equals("silver sickle"))
			{
				return new MatchResult(gainedQty, null, false);
			}
		}

		// C. Amulet Stringing: <Amulet> (u) -> <Amulet> + Ball of wool
		if (lost.endsWith(" (u)") && gained.equals(lost.substring(0, lost.length() - 4).trim()) && gained.contains("amulet"))
		{
			Map<String, Integer> secondaries = new HashMap<>();
			secondaries.put("ball of wool", gainedQty);
			return new MatchResult(gainedQty, secondaries, false);
		}

		// D. Dragonhide / Leather Crafting
		if (lost.endsWith(" dragon leather") || lost.endsWith(" d'hide leather") || lost.equals("leather"))
		{
			String color = "";
			if (lost.contains("dragon") || lost.contains("d'hide"))
			{
				color = lost.substring(0, lost.indexOf(' ')).trim(); // e.g. "green", "blue", "red", "black"
			}

			for (Map.Entry<String, Integer> entry : DHIDE_LEATHER_COSTS.entrySet())
			{
				String armorPiece = entry.getKey();
				String expectedArmor = color.isEmpty()
					? "leather " + armorPiece
					: color + " d'hide " + armorPiece;

				if (gained.equals(expectedArmor) || gained.equals(color + " dragonhide " + armorPiece))
				{
					int leatherCost = entry.getValue() * gainedQty;
					return new MatchResult(leatherCost, null, false);
				}
			}
		}

		if (lost.equals("hard leather") && gained.equals("hardleather body"))
		{
			return new MatchResult(gainedQty, null, false);
		}

		// E. Battlestaffs: Battlestaff -> <Element> battlestaff + <Element> orb
		if (lost.equals("battlestaff") && gained.endsWith(" battlestaff"))
		{
			String element = gained.substring(0, gained.length() - 12).trim();
			Map<String, Integer> secondaries = new HashMap<>();
			secondaries.put(element + " orb", gainedQty);
			return new MatchResult(gainedQty, secondaries, false);
		}

		// F. Glassblowing: Molten glass -> Unpowered orb, Vial, Beer glass, etc.
		if (lost.equals("molten glass") && GLASSBLOWING_PRODUCTS.contains(gained))
		{
			return new MatchResult(gainedQty, null, false);
		}

		// G. Spinning: Flax -> Bow string, Wool -> Ball of wool
		if (lost.equals("flax") && gained.equals("bow string"))
		{
			return new MatchResult(gainedQty, null, false);
		}
		if (lost.equals("wool") && gained.equals("ball of wool"))
		{
			return new MatchResult(gainedQty, null, false);
		}

		// H. Amethyst Processing: Amethyst -> Dart tips (8), Arrowtips (15), Bolt tips (15), Javelin heads (5)
		if (lost.equals("amethyst"))
		{
			if (gained.equals("amethyst dart tip") || gained.equals("amethyst dart tips"))
			{
				int needed = (int) Math.ceil((double) gainedQty / 8);
				return new MatchResult(needed, null, false);
			}
			if (gained.equals("amethyst arrowtip") || gained.equals("amethyst arrowtips"))
			{
				int needed = (int) Math.ceil((double) gainedQty / 15);
				return new MatchResult(needed, null, false);
			}
			if (gained.equals("amethyst bolt tips") || gained.equals("amethyst bolt tip"))
			{
				int needed = (int) Math.ceil((double) gainedQty / 15);
				return new MatchResult(needed, null, false);
			}
			if (gained.equals("amethyst javelin heads") || gained.equals("amethyst javelin head"))
			{
				int needed = (int) Math.ceil((double) gainedQty / 5);
				return new MatchResult(needed, null, false);
			}
		}

		// I. Pottery: Soft clay -> Unfired <pottery>
		if (lost.equals("soft clay") && gained.startsWith("unfired "))
		{
			return new MatchResult(gainedQty, null, false);
		}

		// J. Molten Glass (Superglass Make / Furnace): Seaweed/Soda ash + Bucket of sand -> Molten glass
		if (gained.equals("molten glass"))
		{
			if (lost.equals("giant seaweed"))
			{
				int seaweedNeeded = (int) Math.ceil((double) gainedQty / 6);
				Map<String, Integer> secondaries = new HashMap<>();
				secondaries.put("bucket of sand", gainedQty);
				return new MatchResult(seaweedNeeded, secondaries, false);
			}
			if (lost.equals("seaweed") || lost.equals("soda ash") || lost.equals("swamp weed"))
			{
				Map<String, Integer> secondaries = new HashMap<>();
				secondaries.put("bucket of sand", gainedQty);
				return new MatchResult(gainedQty, secondaries, false);
			}
		}

		return null;
	}

	// ── 5. Smithing ───────────────────────────────────────────────────────
	private static MatchResult matchSmithing(String lost, String gained, int gainedQty)
	{
		// 5. Smithing
		// A. Smelting: <Metal> ore -> <Metal> bar (+ Coal)
		if (lost.endsWith(" ore"))
		{
			if (gained.equals("bronze bar"))
			{
				if (lost.equals("copper ore"))
				{
					Map<String, Integer> secondaries = new HashMap<>();
					secondaries.put("tin ore", gainedQty);
					return new MatchResult(gainedQty, secondaries, false);
				}
				if (lost.equals("tin ore"))
				{
					Map<String, Integer> secondaries = new HashMap<>();
					secondaries.put("copper ore", gainedQty);
					return new MatchResult(gainedQty, secondaries, false);
				}
			}

			String metal = lost.substring(0, lost.length() - 4).trim();
			String expectedBar = metal.equals("iron") ? "iron bar" : metal + " bar";

			if (gained.equals(expectedBar))
			{
				int coalCost = SMELTING_COAL_COST.getOrDefault(metal, 0) * gainedQty;
				Map<String, Integer> secondaries = null;
				if (coalCost > 0)
				{
					secondaries = new HashMap<>();
					secondaries.put("coal", coalCost);
				}
				return new MatchResult(gainedQty, secondaries, false);
			}
		}

		// B. Anvil Smithing: <Metal> bar -> <Metal> <Equipment>
		if (lost.endsWith(" bar"))
		{
			String metal = lost.substring(0, lost.length() - 4).trim();
			if (METALS.contains(metal))
			{
				String equipment = null;
				if (gained.startsWith(metal + " "))
				{
					equipment = gained.substring(metal.length() + 1).trim();
				}
				else if (metal.equals("runite") && gained.startsWith("rune "))
				{
					equipment = gained.substring(5).trim();
				}
				else if (metal.equals("adamantite") && gained.startsWith("adamant "))
				{
					equipment = gained.substring(8).trim();
				}

				if (equipment != null)
				{
					int barsPerUnit = SMITHING_BAR_COSTS.getOrDefault(equipment, 0);

					if (barsPerUnit > 0)
					{
						// Dart tips, arrowtips, and bolts (unf) yield 10 per 1 bar
						if (equipment.contains("dart tip") || equipment.contains("arrowtip") || equipment.contains("bolt"))
						{
							int barsNeeded = (int) Math.ceil((double) gainedQty / 10);
							return new MatchResult(barsNeeded, null, false);
						}
						// Knives and javelin heads yield 5 per 1 bar
						if (equipment.contains("knife") || equipment.contains("knives") || equipment.contains("javelin"))
						{
							int barsNeeded = (int) Math.ceil((double) gainedQty / 5);
							return new MatchResult(barsNeeded, null, false);
						}
						// Nails yield 15 per 1 bar
						if (equipment.contains("nails"))
						{
							int barsNeeded = (int) Math.ceil((double) gainedQty / 15);
							return new MatchResult(barsNeeded, null, false);
						}

						return new MatchResult(barsPerUnit * gainedQty, null, false);
					}
				}
			}

			// C. Cannonballs: Steel bar -> Cannonball (1 bar yields 4 cannonballs)
			if (lost.equals("steel bar") && (gained.equals("cannonball") || gained.equals("granite cannonball")))
			{
				int barsNeeded = (int) Math.ceil((double) gainedQty / 4);
				return new MatchResult(barsNeeded, null, false);
			}
		}

		return null;
	}

	// ── 6. Magic Transmutation (Plank Make) ────────────────────────────────
	private static MatchResult matchMagicTransmutation(String lost, String gained, int gainedQty)
	{
		// <Wood> logs -> <Wood> plank
		if (lost.equals("logs") || lost.endsWith(" logs"))
		{
			String wood = lost.equals("logs") ? "" : lost.substring(0, lost.length() - 5).trim();
			String expectedPlank = wood.isEmpty() ? "plank" : wood + " plank";

			if (gained.equals(expectedPlank))
			{
				int coinsPerPlank = 100;
				if (wood.equals("oak"))
				{
					coinsPerPlank = 250;
				}
				else if (wood.equals("teak"))
				{
					coinsPerPlank = 500;
				}
				else if (wood.equals("mahogany"))
				{
					coinsPerPlank = 1050;
				}

				Map<String, Integer> secondaries = new HashMap<>();
				secondaries.put("coins", coinsPerPlank * gainedQty);
				return new MatchResult(gainedQty, secondaries, false);
			}
		}

		return null;
	}

	// ── Common Rune Names ─────────────────────────────────────────────────
	private static final Set<String> RUNES = new HashSet<>();
	static
	{
		RUNES.add("air rune");
		RUNES.add("mind rune");
		RUNES.add("water rune");
		RUNES.add("earth rune");
		RUNES.add("fire rune");
		RUNES.add("body rune");
		RUNES.add("cosmic rune");
		RUNES.add("chaos rune");
		RUNES.add("astral rune");
		RUNES.add("nature rune");
		RUNES.add("law rune");
		RUNES.add("death rune");
		RUNES.add("blood rune");
		RUNES.add("soul rune");
		RUNES.add("wrath rune");
		RUNES.add("mist rune");
		RUNES.add("dust rune");
		RUNES.add("mud rune");
		RUNES.add("smoke rune");
		RUNES.add("steam rune");
		RUNES.add("lava rune");
		RUNES.add("sunfire rune");
	}

	// ── 7. Runecrafting ───────────────────────────────────────────────────
	private static MatchResult matchRunecrafting(String lost, String gained, int gainedQty)
	{
		if ((lost.equals("pure essence") || lost.equals("daeyalt essence") || lost.equals("rune essence"))
			&& RUNES.contains(gained))
		{
			Map<String, Integer> secondaries = null;
			if (gained.equals("lava rune"))
			{
				secondaries = Collections.singletonMap("earth rune|fire rune", gainedQty);
			}
			else if (gained.equals("steam rune"))
			{
				secondaries = Collections.singletonMap("water rune|fire rune", gainedQty);
			}
			else if (gained.equals("smoke rune"))
			{
				secondaries = Collections.singletonMap("air rune|fire rune", gainedQty);
			}
			else if (gained.equals("mud rune"))
			{
				secondaries = Collections.singletonMap("water rune|earth rune", gainedQty);
			}
			else if (gained.equals("dust rune"))
			{
				secondaries = Collections.singletonMap("air rune|earth rune", gainedQty);
			}
			else if (gained.equals("mist rune"))
			{
				secondaries = Collections.singletonMap("air rune|water rune", gainedQty);
			}

			return new MatchResult(gainedQty, secondaries, false);
		}

		return null;
	}
}
