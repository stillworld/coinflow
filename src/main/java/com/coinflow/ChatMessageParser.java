package com.coinflow;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.gameval.ItemID;

/**
 * Parses English chat messages into item ids and quantities for loot that
 * enters closed containers (gem bags, herb sacks, fish barrels, seed boxes,
 * log baskets) without an inventory diff. Pure functions — no game state.
 */
final class ChatMessageParser
{
	private ChatMessageParser() {}

	static final Pattern FISHING_CATCH_REGEX = Pattern.compile(
		"(?:You catch (?:a|an|some|\\d+)\\s+|Your cormorant returns with its catch|You catch .*Karambwanji)",
		Pattern.CASE_INSENSITIVE
	);
	static final Pattern HERBIBOAR_HERB_SACK_PATTERN = Pattern.compile(
		".+(Grimy .+?) herb.+",
		Pattern.CASE_INSENSITIVE
	);
	static final Pattern WOODCUTTING_CHOP_REGEX = Pattern.compile(
		"^(?:You get (?:a|an|some|\\d+)\\s+|You cut (?:a|an|some|\\d+)\\s+).*(?:logs?|bark)",
		Pattern.CASE_INSENSITIVE
	);
	// "Death charges you 8,915 coins." — the authoritative reclaim-fee signal;
	// bank/coffer payments and reclaim-to-bank items are invisible to diffs.
	static final Pattern DEATH_FEE_MESSAGE = Pattern.compile(
		"Death charges you ([\\d,]+) coins"
	);
	// "You successfully retrieved everything from your gravestone." — a
	// direct gravestone claim opens no retrieval interface and fires no
	// ground Take click; the message is the only signal that the arriving
	// items are death recovery rather than loot.
	static final Pattern GRAVE_RETRIEVAL_MESSAGE = Pattern.compile(
		"retriev\\w+ .*your gravestone",
		Pattern.CASE_INSENSITIVE
	);

	static int findUncutGemIdInMessage(String msg)
	{
		String lower = msg.toLowerCase(Locale.ROOT);
		if (!lower.contains("uncut") && !lower.contains("gem"))
		{
			return -1;
		}
		if (lower.contains("dragonstone"))
		{
			return ItemID.UNCUT_DRAGONSTONE;
		}
		if (lower.contains("diamond"))
		{
			return ItemID.UNCUT_DIAMOND;
		}
		if (lower.contains("ruby"))
		{
			return ItemID.UNCUT_RUBY;
		}
		if (lower.contains("emerald"))
		{
			return ItemID.UNCUT_EMERALD;
		}
		if (lower.contains("sapphire"))
		{
			return ItemID.UNCUT_SAPPHIRE;
		}
		if (lower.contains("topaz"))
		{
			return ItemID.UNCUT_RED_TOPAZ;
		}
		if (lower.contains("jade"))
		{
			return ItemID.UNCUT_JADE;
		}
		if (lower.contains("opal"))
		{
			return ItemID.UNCUT_OPAL;
		}
		return -1;
	}

	static int findGrimyHerbIdInMessage(String msg)
	{
		String lower = msg.toLowerCase(Locale.ROOT);
		if (!lower.contains("grimy") && !lower.contains("herb"))
		{
			return -1;
		}
		if (lower.contains("torstol"))
		{
			return ItemID.UNIDENTIFIED_TORSTOL;
		}
		if (lower.contains("dwarf weed"))
		{
			return ItemID.UNIDENTIFIED_DWARF_WEED;
		}
		if (lower.contains("lantadyme"))
		{
			return ItemID.UNIDENTIFIED_LANTADYME;
		}
		if (lower.contains("cadantine"))
		{
			return ItemID.UNIDENTIFIED_CADANTINE;
		}
		if (lower.contains("snapdragon"))
		{
			return ItemID.UNIDENTIFIED_SNAPDRAGON;
		}
		if (lower.contains("kwuarm"))
		{
			return ItemID.UNIDENTIFIED_KWUARM;
		}
		if (lower.contains("avantoe"))
		{
			return ItemID.UNIDENTIFIED_AVANTOE;
		}
		if (lower.contains("irit"))
		{
			return ItemID.UNIDENTIFIED_IRIT;
		}
		if (lower.contains("toadflax"))
		{
			return ItemID.UNIDENTIFIED_TOADFLAX;
		}
		if (lower.contains("ranarr"))
		{
			return ItemID.UNIDENTIFIED_RANARR;
		}
		if (lower.contains("harralander"))
		{
			return ItemID.UNIDENTIFIED_HARRALANDER;
		}
		if (lower.contains("tarromin"))
		{
			return ItemID.UNIDENTIFIED_TARROMIN;
		}
		if (lower.contains("marrentill"))
		{
			return ItemID.UNIDENTIFIED_MARENTILL;
		}
		if (lower.contains("guam"))
		{
			return ItemID.UNIDENTIFIED_GUAM;
		}
		return -1;
	}

	static int findRawFishIdInMessage(String msg)
	{
		String lower = msg.toLowerCase(Locale.ROOT);
		if (lower.contains("karambwanji"))
		{
			return ItemID.TBWT_RAW_KARAMBWANJI;
		}
		if (lower.contains("karambwan"))
		{
			return ItemID.TBWT_RAW_KARAMBWAN;
		}
		if (lower.contains("anglerfish"))
		{
			return ItemID.RAW_ANGLERFISH;
		}
		if (lower.contains("dark crab"))
		{
			return ItemID.RAW_DARK_CRAB;
		}
		if (lower.contains("monkfish"))
		{
			return ItemID.RAW_MONKFISH;
		}
		if (lower.contains("shark"))
		{
			return ItemID.RAW_SHARK;
		}
		if (lower.contains("swordfish"))
		{
			return ItemID.RAW_SWORDFISH;
		}
		if (lower.contains("lobster"))
		{
			return ItemID.RAW_LOBSTER;
		}
		if (lower.contains("bass"))
		{
			return ItemID.RAW_BASS;
		}
		if (lower.contains("tuna"))
		{
			return ItemID.RAW_TUNA;
		}
		if (lower.contains("salmon"))
		{
			return ItemID.RAW_SALMON;
		}
		if (lower.contains("trout"))
		{
			return ItemID.RAW_TROUT;
		}
		if (lower.contains("pike"))
		{
			return ItemID.RAW_PIKE;
		}
		if (lower.contains("herring"))
		{
			return ItemID.RAW_HERRING;
		}
		if (lower.contains("sardine"))
		{
			return ItemID.RAW_SARDINE;
		}
		if (lower.contains("anchov"))
		{
			return ItemID.RAW_ANCHOVIES;
		}
		if (lower.contains("shrimp"))
		{
			return ItemID.RAW_SHRIMP;
		}
		if (lower.contains("lava eel"))
		{
			return ItemID.LAVA_EEL;
		}
		if (lower.contains("cave eel"))
		{
			return ItemID.RAW_CAVE_EEL;
		}
		if (lower.contains("slimy eel"))
		{
			return ItemID.MORT_SLIMEY_EEL;
		}
		if (lower.contains("rainbow fish"))
		{
			return ItemID.HUNTING_RAW_FISH_SPECIAL;
		}
		return -1;
	}

	static int findSeedIdInMessage(String msg)
	{
		String lower = msg.toLowerCase(Locale.ROOT);
		if (!lower.contains("seed"))
		{
			return -1;
		}
		if (lower.contains("ranarr"))
		{
			return ItemID.RANARR_SEED;
		}
		if (lower.contains("snapdragon"))
		{
			return ItemID.SNAPDRAGON_SEED;
		}
		if (lower.contains("torstol"))
		{
			return ItemID.TORSTOL_SEED;
		}
		if (lower.contains("toadflax"))
		{
			return ItemID.TOADFLAX_SEED;
		}
		if (lower.contains("avantoe"))
		{
			return ItemID.AVANTOE_SEED;
		}
		if (lower.contains("kwuarm"))
		{
			return ItemID.KWUARM_SEED;
		}
		if (lower.contains("cadantine"))
		{
			return ItemID.CADANTINE_SEED;
		}
		if (lower.contains("lantadyme"))
		{
			return ItemID.LANTADYME_SEED;
		}
		if (lower.contains("dwarf weed"))
		{
			return ItemID.DWARF_WEED_SEED;
		}
		if (lower.contains("irit"))
		{
			return ItemID.IRIT_SEED;
		}
		if (lower.contains("harralander"))
		{
			return ItemID.HARRALANDER_SEED;
		}
		if (lower.contains("tarromin"))
		{
			return ItemID.TARROMIN_SEED;
		}
		if (lower.contains("marrentill"))
		{
			return ItemID.MARRENTILL_SEED;
		}
		if (lower.contains("guam"))
		{
			return ItemID.GUAM_SEED;
		}
		if (lower.contains("limpwurt"))
		{
			return ItemID.LIMPWURT_SEED;
		}
		if (lower.contains("watermelon"))
		{
			return ItemID.WATERMELON_SEED;
		}
		if (lower.contains("snape grass"))
		{
			return ItemID.SNAPE_GRASS_SEED;
		}
		if (lower.contains("yew"))
		{
			return ItemID.YEW_SEED;
		}
		if (lower.contains("magic"))
		{
			return ItemID.MAGIC_TREE_SEED;
		}
		if (lower.contains("palm"))
		{
			return ItemID.PALM_TREE_SEED;
		}
		if (lower.contains("dragonfruit"))
		{
			return ItemID.DRAGONFRUIT_TREE_SEED;
		}
		if (lower.contains("celastrus"))
		{
			return ItemID.CELASTRUS_TREE_SEED;
		}
		if (lower.contains("redwood"))
		{
			return ItemID.REDWOOD_TREE_SEED;
		}
		if (lower.contains("spirit"))
		{
			return ItemID.SPIRIT_TREE_SEED;
		}
		if (lower.contains("hespori"))
		{
			return ItemID.HESPORI_SEED;
		}
		if (lower.contains("potato"))
		{
			return ItemID.POTATO_SEED;
		}
		if (lower.contains("onion"))
		{
			return ItemID.ONION_SEED;
		}
		if (lower.contains("cabbage"))
		{
			return ItemID.CABBAGE_SEED;
		}
		if (lower.contains("tomato"))
		{
			return ItemID.TOMATO_SEED;
		}
		if (lower.contains("sweetcorn"))
		{
			return ItemID.SWEETCORN_SEED;
		}
		if (lower.contains("strawberry"))
		{
			return ItemID.STRAWBERRY_SEED;
		}
		if (lower.contains("barley"))
		{
			return ItemID.BARLEY_SEED;
		}
		if (lower.contains("hammerstone"))
		{
			return ItemID.HAMMERSTONE_HOP_SEED;
		}
		if (lower.contains("asgarnian"))
		{
			return ItemID.ASGARNIAN_HOP_SEED;
		}
		if (lower.contains("jute"))
		{
			return ItemID.JUTE_SEED;
		}
		if (lower.contains("yanillian"))
		{
			return ItemID.YANILLIAN_HOP_SEED;
		}
		if (lower.contains("krandorian"))
		{
			return ItemID.KRANDORIAN_HOP_SEED;
		}
		if (lower.contains("wildblood"))
		{
			return ItemID.WILDBLOOD_HOP_SEED;
		}
		return -1;
	}

	static int parseSeedQtyInMessage(String msg)
	{
		Matcher m = Pattern.compile("(\\d+)\\s*x", Pattern.CASE_INSENSITIVE).matcher(msg);
		if (m.find())
		{
			try
			{
				return Integer.parseInt(m.group(1));
			}
			catch (NumberFormatException ignored) {}
		}
		return 1;
	}

	static int parseFishQtyInMessage(String msg)
	{
		Matcher m = Pattern.compile("You catch (\\d+)", Pattern.CASE_INSENSITIVE).matcher(msg);
		if (m.find())
		{
			try
			{
				return Integer.parseInt(m.group(1));
			}
			catch (NumberFormatException ignored) {}
		}
		return 1;
	}

	static int findLogIdInMessage(String msg)
	{
		String lower = msg.toLowerCase(Locale.ROOT);
		if (lower.contains("arctic pine"))
		{
			return ItemID.ARCTIC_PINE_LOG;
		}
		if (lower.contains("redwood"))
		{
			return ItemID.REDWOOD_LOGS;
		}
		if (lower.contains("magic"))
		{
			return ItemID.MAGIC_LOGS;
		}
		if (lower.contains("yew"))
		{
			return ItemID.YEW_LOGS;
		}
		if (lower.contains("maple"))
		{
			return ItemID.MAPLE_LOGS;
		}
		if (lower.contains("mahogany"))
		{
			return ItemID.MAHOGANY_LOGS;
		}
		if (lower.contains("teak"))
		{
			return ItemID.TEAK_LOGS;
		}
		if (lower.contains("willow"))
		{
			return ItemID.WILLOW_LOGS;
		}
		if (lower.contains("oak"))
		{
			return ItemID.OAK_LOGS;
		}
		if (lower.contains("juniper"))
		{
			return ItemID.JUNIPER_LOGS;
		}
		if (lower.contains("blisterwood"))
		{
			return ItemID.BLISTERWOOD_LOGS;
		}
		if (lower.contains("achey"))
		{
			return ItemID.ACHEY_TREE_LOGS;
		}
		if (lower.contains("camphor"))
		{
			return ItemID.CAMPHOR_LOGS;
		}
		if (lower.contains("ironwood"))
		{
			return ItemID.IRONWOOD_LOGS;
		}
		if (lower.contains("rosewood"))
		{
			return ItemID.ROSEWOOD_LOGS;
		}
		if (lower.contains("jatoba"))
		{
			return ItemID.JATOBA_LOGS;
		}
		if (lower.contains("bark"))
		{
			return ItemID.HOLLOW_BARK;
		}
		if (lower.contains("logs") || lower.contains("log"))
		{
			return ItemID.LOGS;
		}
		return -1;
	}

	static int parseLogQtyInMessage(String msg)
	{
		Matcher m = Pattern.compile("You (?:get|cut) (\\d+)", Pattern.CASE_INSENSITIVE).matcher(msg);
		if (m.find())
		{
			try
			{
				return Integer.parseInt(m.group(1));
			}
			catch (NumberFormatException ignored) {}
		}
		return 1;
	}
}
