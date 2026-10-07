package com.coinflow;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.ItemComposition;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;

/**
 * Registry and classifier for consumable supplies (potions, food, runes, ammo, and teleports).
 * Used when "Track Supply Costs" is enabled to ensure only genuine consumable expenses
 * are deducted from net profit, while dropped resources, unequipped gear, or empty vials
 * are never falsely charged as expenses.
 */
public final class ConsumableRegistry
{
	private ConsumableRegistry() {}

	// Matches potion names like "Stamina potion(4)", "Prayer potion (3)", "Saradomin brew(1)"
	private static final Pattern POTION_PATTERN = Pattern.compile("^(.*?)\\s*\\((\\d)\\)$");

	private static final Set<String> NON_POTION_KEYWORDS = new HashSet<>(Arrays.asList(
		"amulet", "ring", "necklace", "bracelet", "pendant", "sceptre",
		"crystal", "device", "waterskin", "bellows", "lockpick", "quiver",
		"compass", "chronicle", "pouch", "horn"
	));

	// Drinkables that persist after use and are never consumed (e.g. Waterskin(4) -> Waterskin(0))
	private static final Set<String> NON_CONSUMABLE_DRINKABLE_KEYWORDS = new HashSet<>(Collections.singletonList(
		"waterskin"
	));

	public static final class PotionDose
	{
		private final String baseName;
		private final int dose;

		public PotionDose(String baseName, int dose)
		{
			this.baseName = baseName.trim();
			this.dose = dose;
		}

		public String getBaseName()
		{
			return baseName;
		}

		public int getDose()
		{
			return dose;
		}
	}

	/**
	 * Parses a potion name into its base name and dose count.
	 * Returns null if the item name does not match a dose pattern.
	 */
	public static PotionDose parsePotion(String itemName)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return null;
		}

		Matcher m = POTION_PATTERN.matcher(itemName);
		if (m.matches())
		{
			String base = m.group(1).trim();
			String lower = base.toLowerCase(Locale.ROOT);
			if (containsKeyword(lower, NON_POTION_KEYWORDS))
			{
				return null;
			}

			try
			{
				int dose = Integer.parseInt(m.group(2));
				if (dose >= 1 && dose <= 4)
				{
					return new PotionDose(base, dose);
				}
			}
			catch (NumberFormatException ignored)
			{
				// Not a valid dose number
			}
		}

		return null;
	}

	private static final Set<String> BYPRODUCTS = new HashSet<>();
	static
	{
		BYPRODUCTS.add("vial");
		BYPRODUCTS.add("empty vial");
		BYPRODUCTS.add("pie dish");
		BYPRODUCTS.add("empty pie dish");
		BYPRODUCTS.add("bowl");
		BYPRODUCTS.add("empty bowl");
		BYPRODUCTS.add("cup");
		BYPRODUCTS.add("empty cup");
		BYPRODUCTS.add("pot");
		BYPRODUCTS.add("empty pot");
		BYPRODUCTS.add("beer glass");
		BYPRODUCTS.add("jug");
		BYPRODUCTS.add("empty jug");
		BYPRODUCTS.add("bucket");
		BYPRODUCTS.add("empty bucket");
		BYPRODUCTS.add("plant pot");
		BYPRODUCTS.add("empty plant pot");
		BYPRODUCTS.add("cocktail glass");
	}

	private static final Set<String> NON_CONSUMABLE_TELEPORT_SCROLLS = new HashSet<>();
	static
	{
		NON_CONSUMABLE_TELEPORT_SCROLLS.add("teleport anchoring scroll");
		NON_CONSUMABLE_TELEPORT_SCROLLS.add("twisted teleport scroll");
		NON_CONSUMABLE_TELEPORT_SCROLLS.add("trailblazer teleport scroll");
		NON_CONSUMABLE_TELEPORT_SCROLLS.add("trailblazer reloaded home teleport scroll");
		NON_CONSUMABLE_TELEPORT_SCROLLS.add("shattered teleport scroll");
		NON_CONSUMABLE_TELEPORT_SCROLLS.add("speedy teleport scroll");
		NON_CONSUMABLE_TELEPORT_SCROLLS.add("echo home teleport scroll");
		NON_CONSUMABLE_TELEPORT_SCROLLS.add("armageddon teleport scroll");
		NON_CONSUMABLE_TELEPORT_SCROLLS.add("annihilation teleport scroll");
	}

	// Charged teleport jewelry that crumbles to dust when its last charge is used
	// (e.g. "Ring of dueling(1)" -> nothing). These can be rubbed worn or from the
	// inventory. Jewelry that depletes to an uncharged variant instead (glory,
	// ring of wealth, combat bracelet, skills necklace, pharaoh's sceptre) is
	// excluded on purpose — those transitions are handled as degradation pairs.
	private static final Set<String> LAST_CHARGE_TELEPORT_BASES = new HashSet<>(Arrays.asList(
		"ring of dueling",
		"games necklace",
		"slayer ring",
		"necklace of passage",
		"burning amulet",
		"digsite pendant",
		"ring of returning"
	));

	// Charged worn items that crumble at (1) but are not teleport jewelry.
	private static final Set<String> LAST_CHARGE_WORN_BASES = new HashSet<>(Collections.singletonList(
		"castle wars bracelet"
	));

	// Single-life worn jewelry with no charge count in the item name — it simply
	// disappears from the equipment slot when consumed (e.g. Ring of recoil when
	// its 40 recoil hits are spent, Ring of life when it saves the wearer).
	private static final Set<String> CRUMBLING_WORN_JEWELRY = new HashSet<>(Arrays.asList(
		"ring of recoil",
		"ring of life",
		"binding necklace",
		"dodgy necklace",
		"bracelet of slaughter",
		"expeditious bracelet",
		"ring of forging",
		"bracelet of clay",
		"amulet of chemistry"
	));

	/**
	 * Returns true if the item name represents a zero-value byproduct container
	 * left over after consuming food or drink.
	 */
	public static boolean isByproduct(String itemName)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return false;
		}
		return BYPRODUCTS.contains(itemName.trim().toLowerCase(Locale.ROOT));
	}

	/**
	 * Returns true if gainedName represents a remaining portion of lostName (e.g. eating half a pie).
	 */
	public static boolean isFoodPortion(String gainedName, String lostName)
	{
		if (gainedName == null || lostName == null)
		{
			return false;
		}

		String g = gainedName.trim().toLowerCase(Locale.ROOT);
		String l = lostName.trim().toLowerCase(Locale.ROOT);

		// Pies: e.g. "summer pie" -> "half a summer pie" / "half an apple pie"
		if (g.startsWith("half a ") && g.substring(7).equals(l))
		{
			return true;
		}
		if (g.startsWith("half an ") && g.substring(8).equals(l))
		{
			return true;
		}

		// Pizzas: e.g. "plain pizza" -> "1/2 plain pizza"
		if (g.startsWith("1/2 ") && g.substring(4).equals(l))
		{
			return true;
		}

		// Cakes: e.g. "cake" -> "2/3 cake" -> "slice of cake"
		// or "chocolate cake" -> "2/3 chocolate cake" -> "chocolate slice"
		if (l.endsWith("cake"))
		{
			String prefix = l.substring(0, l.length() - 4).trim();
			if (prefix.startsWith("2/3"))
			{
				prefix = prefix.substring(3).trim();
			}
			if (g.equals(prefix.isEmpty() ? "2/3 cake" : "2/3 " + prefix + " cake"))
			{
				return true;
			}
			if (g.equals(prefix.isEmpty() ? "slice of cake" : prefix + " slice") || g.equals("slice of cake"))
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Returns true if rawFoodName represents the raw ingredient used to cook
	 * cookedOrBurntFoodName (e.g. "Raw manta ray" -> "Manta ray" or "Burnt manta ray").
	 */
	public static boolean isCookingPair(String rawFoodName, String cookedOrBurntFoodName)
	{
		if (rawFoodName == null || cookedOrBurntFoodName == null)
		{
			return false;
		}

		String raw = rawFoodName.trim().toLowerCase(Locale.ROOT);
		String cooked = cookedOrBurntFoodName.trim().toLowerCase(Locale.ROOT);

		// Special case: "potato" -> "baked potato" or "burnt potato"
		if (raw.equals("potato"))
		{
			return cooked.equals("baked potato") || cooked.equals("burnt potato");
		}

		// Most raw food items in OSRS start with "raw " or "uncooked "
		String base;
		if (raw.startsWith("raw "))
		{
			base = raw.substring(4).trim();
		}
		else if (raw.startsWith("uncooked "))
		{
			base = raw.substring(9).trim();
		}
		else
		{
			return false;
		}

		if (base.isEmpty())
		{
			return false;
		}

		// Direct name match: "Raw manta ray" -> "Manta ray", "Uncooked summer pie" -> "Summer pie"
		if (cooked.equals(base))
		{
			return true;
		}

		// Cooked prefix match: "Raw karambwan" -> "Cooked karambwan", "Raw chicken" -> "Cooked chicken", "Raw sweetcorn" -> "Cooked sweetcorn"
		if (cooked.equals("cooked " + base))
		{
			return true;
		}

		// Burnt prefix match: "Raw manta ray" -> "Burnt manta ray", "Raw shark" -> "Burnt shark"
		if (cooked.equals("burnt " + base))
		{
			return true;
		}

		// Generic burnt food: in OSRS many fish burn to "Burnt fish", meats burn to "Burnt meat", pies to "Burnt pie"
		if (cooked.equals("burnt fish") || cooked.equals("burnt meat") || (base.endsWith("pie") && cooked.equals("burnt pie"))
			|| (base.equals("pizza") && cooked.equals("burnt pizza")))
		{
			return true;
		}

		// Uncooked pizza cooks into plain pizza
		if (base.equals("pizza") && cooked.equals("plain pizza"))
		{
			return true;
		}

		// Karambwan poison variation: "Raw karambwan" -> "Poison karambwan"
		if (base.equals("karambwan") && cooked.equals("poison karambwan"))
		{
			return true;
		}

		// Meats: "Raw beef", "Raw bear meat", "Raw rat meat", "Raw yak meat" -> "Cooked meat"
		if (cooked.equals("cooked meat") && (base.contains("meat") || base.equals("beef")))
		{
			return true;
		}

		return false;
	}

	private static boolean containsKeyword(String lower, Set<String> keywords)
	{
		for (String keyword : keywords)
		{
			if (lower.contains(keyword))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Determines whether the item is a consumable supply (potion, food, rune, ammo, teleport).
	 * Food and drink are detected via the item's "Eat"/"Drink" inventory actions
	 * (from its {@link ItemComposition}); potions, runes, ammo, and teleports are
	 * detected by name patterns and also apply when the composition is unavailable.
	 */
	public static boolean isConsumable(int itemId, String itemName, ItemManager itemManager)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return false;
		}

		String lower = itemName.trim().toLowerCase(Locale.ROOT);

		// Raw items, seeds, ores, bars, logs are never consumable supplies
		if (lower.startsWith("raw ") || lower.endsWith(" ore") || lower.endsWith(" logs")
			|| lower.endsWith(" log") || lower.endsWith(" bar") || lower.endsWith(" seed")
			|| lower.endsWith(" hide") || lower.endsWith(" leather"))
		{
			return false;
		}

		// Empty containers or currency
		if (lower.equals("vial") || lower.equals("empty vial") || lower.equals("vial of water")
			|| lower.equals("jug") || lower.equals("empty cup") || lower.equals("bowl")
			|| lower.equals("coins") || lower.equals("platinum token"))
		{
			return false;
		}

		// 1. Inventory actions: anything with an Eat or Drink option is a consumable supply,
		// except durable drinkables that persist after use (e.g. waterskins)
		if (itemManager != null)
		{
			ItemComposition comp = itemManager.getItemComposition(itemId);
			if (comp != null && comp.getInventoryActions() != null)
			{
				for (String action : comp.getInventoryActions())
				{
					if ("Eat".equalsIgnoreCase(action)
						|| ("Drink".equalsIgnoreCase(action) && !containsKeyword(lower, NON_CONSUMABLE_DRINKABLE_KEYWORDS)))
					{
						return true;
					}
				}
			}
		}

		// 2. Potions (e.g. Stamina potion(4))
		if (parsePotion(itemName) != null)
		{
			return true;
		}

		// 3. Runes (excluding essence)
		if (lower.endsWith(" rune") && !lower.contains("essence"))
		{
			return true;
		}

		// 4. Ammunition (arrows, bolts, darts, knives, thrownaxes, chinchompas, javelins, cannonballs)
		if (isAmmo(itemName))
		{
			return true;
		}

		// 5. Teleports (tablets and scrolls)
		if (lower.contains("teleport") || lower.endsWith(" tab") || lower.endsWith(" tabs")
			|| lower.endsWith("basalt") && !lower.equals("basalt"))
		{
			if (NON_CONSUMABLE_TELEPORT_SCROLLS.contains(lower)
				|| lower.contains("home teleport")
				|| lower.contains("anchoring")
				|| lower.contains("crystal")
				|| lower.contains("eternal")
				|| lower.contains("book")
				|| lower.contains("charm")
				|| lower.contains("card")
				|| lower.contains("trap")
				|| lower.contains("focus")
				|| lower.contains("teleporter"))
			{
				return false;
			}
			return true;
		}

		return false;
	}

	/**
	 * Returns true if the item is a last-charge variant of crumble-type teleport
	 * jewelry (e.g. "Ring of dueling(1)", "Games necklace(1)"). These crumble to
	 * dust whether rubbed from the equipment slot or the inventory.
	 *
	 * Uses POTION_PATTERN directly rather than {@link #parsePotion}: jewelry base
	 * names are deliberately excluded from potion parsing by NON_POTION_KEYWORDS.
	 */
	public static boolean isLastChargeTeleportJewelry(String itemName)
	{
		return isLastChargeVariant(itemName, LAST_CHARGE_TELEPORT_BASES);
	}

	/**
	 * Returns true if a worn item disappearing from the equipment slot means it
	 * was consumed (crumbled to dust) rather than unequipped. Covers last-charge
	 * teleport jewelry plus single-life charged jewelry with no charge count in
	 * the item name (e.g. Ring of recoil, Dodgy necklace).
	 */
	public static boolean isCrumblingJewelry(String itemName)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return false;
		}
		if (CRUMBLING_WORN_JEWELRY.contains(itemName.trim().toLowerCase(Locale.ROOT)))
		{
			return true;
		}
		return isLastChargeTeleportJewelry(itemName)
			|| isLastChargeVariant(itemName, LAST_CHARGE_WORN_BASES);
	}

	private static boolean isLastChargeVariant(String itemName, Set<String> bases)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return false;
		}
		Matcher m = POTION_PATTERN.matcher(itemName.trim());
		if (!m.matches() || !"1".equals(m.group(2)))
		{
			return false;
		}
		return bases.contains(m.group(1).trim().toLowerCase(Locale.ROOT));
	}

	/**
	 * Determines whether the item is ammunition or a thrown weapon.
	 */
	public static boolean isAmmo(String itemName)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return false;
		}

		String lower = itemName.trim().toLowerCase(Locale.ROOT);

		return lower.endsWith(" arrow") || lower.endsWith(" arrows") || lower.contains("arrow(")
			|| lower.endsWith(" bolt") || lower.endsWith(" bolts") || lower.contains("bolts (") || lower.contains("bolts(")
			|| lower.endsWith(" dart") || lower.endsWith(" darts") || lower.contains("dart(")
			|| lower.endsWith(" knife") || lower.endsWith(" knives") || lower.contains("knife(")
			|| lower.endsWith(" thrownaxe") || lower.endsWith(" thrownaxes") || lower.contains("thrownaxe(")
			|| lower.endsWith(" javelin") || lower.endsWith(" javelins") || lower.contains("javelin(")
			|| lower.contains("chinchompa")
			|| lower.equals("cannonball") || lower.equals("granite cannonball")
			|| lower.contains("sunfire splinter");
	}

	/**
	 * Returns true if the item name represents a farming seed.
	 */
	public static boolean isSeed(String itemName)
	{
		if (itemName == null || itemName.isEmpty())
		{
			return false;
		}
		String lower = itemName.trim().toLowerCase(Locale.ROOT);
		return lower.endsWith(" seed") || lower.endsWith(" seeds")
			|| lower.endsWith(" spore") || lower.endsWith(" spores")
			|| lower.equals("gout tuber");
	}

	/**
	 * Returns true if the lost item represents a skilling sink corresponding to an active skill
	 * that just gained XP or performed an action on this tick (e.g. Prayer bones/ashes,
	 * Firemaking logs, Farming seeds/saplings/compost, Construction planks/nails).
	 */
	public static boolean isSkillSink(Set<Skill> activeSkills, String itemName)
	{
		if (activeSkills == null || activeSkills.isEmpty() || itemName == null || itemName.isEmpty())
		{
			return false;
		}

		String lower = itemName.trim().toLowerCase(Locale.ROOT);

		if (activeSkills.contains(Skill.FIREMAKING))
		{
			if (isLog(lower))
			{
				return true;
			}
		}

		if (activeSkills.contains(Skill.PRAYER) || activeSkills.contains(Skill.MAGIC))
		{
			if (lower.startsWith("ensouled "))
			{
				return true;
			}
		}

		if (activeSkills.contains(Skill.PRAYER))
		{
			if (lower.equals("bones") || lower.endsWith(" bones")
				|| lower.equals("ashes") || lower.endsWith(" ashes"))
			{
				return true;
			}
		}

		if (activeSkills.contains(Skill.FARMING))
		{
			if (isSeed(itemName) || lower.endsWith(" sapling") || lower.endsWith(" seedling")
				|| lower.equals("compost") || lower.equals("supercompost") || lower.equals("ultracompost")
				|| lower.equals("plant cure"))
			{
				return true;
			}
		}

		if (activeSkills.contains(Skill.CONSTRUCTION))
		{
			if (lower.equals("plank") || lower.equals("planks")
				|| lower.endsWith(" plank") || lower.endsWith(" planks")
				|| lower.endsWith(" nails")
				|| lower.equals("marble block")
				|| lower.equals("gold leaf")
				|| lower.equals("magic stone")
				|| lower.equals("limestone brick")
				|| lower.equals("bolt of cloth")
				|| lower.equals("clockwork")
				|| lower.equals("steel bar")
				|| lower.startsWith("bagged "))
			{
				return true;
			}
		}

		if (activeSkills.contains(Skill.RUNECRAFT))
		{
			if (lower.equals("pure essence") || lower.equals("daeyalt essence")
				|| lower.equals("rune essence") || lower.equals("binding necklace"))
			{
				return true;
			}
		}

		if (activeSkills.contains(Skill.HUNTER))
		{
			if (isLog(lower) || isSeed(itemName)
				|| lower.equals("raw beef") || lower.equals("raw chicken")
				|| lower.equals("raw beast meat") || lower.equals("raw rat meat")
				|| lower.equals("fishing bait"))
			{
				return true;
			}
		}

		return false;
	}

	public static boolean isLog(String rawName)
	{
		if (rawName == null)
		{
			return false;
		}
		String lower = rawName.trim().toLowerCase(Locale.ROOT);
		if (lower.contains("collection log") || lower.contains("captain's log") || lower.contains("clue"))
		{
			return false;
		}
		return lower.equals("logs")
			|| lower.endsWith(" logs")
			|| lower.endsWith(" log")
			|| lower.contains("pyre logs")
			|| lower.equals("kindling");
	}
}
