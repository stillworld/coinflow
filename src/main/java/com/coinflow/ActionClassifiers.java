package com.coinflow;

import java.util.Map;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;

/**
 * Pure classifiers for menu-option target strings and player animations.
 * Used to decide whether a menu click/animation indicates container
 * interaction, farming, firemaking, alchemy, or defensive stances.
 */
final class ActionClassifiers
{
	private ActionClassifiers() {}

	static boolean isContainerOrChargedItemTarget(String lowerTarget)
	{
		if (lowerTarget == null || isFarmingPatchAction(lowerTarget))
		{
			return false;
		}
		return lowerTarget.contains("gem bag") || lowerTarget.contains("gem sack")
			|| lowerTarget.contains("gem pouch") || lowerTarget.contains("gem satchel") || lowerTarget.contains("gem tote")
			|| lowerTarget.contains("herb sack")
			|| lowerTarget.contains("fish barrel") || lowerTarget.contains("fish sack barrel")
			|| lowerTarget.contains("log basket") || lowerTarget.contains("forestry basket")
			|| lowerTarget.contains("seed box")
			|| lowerTarget.contains("ash sanctifier")
			|| lowerTarget.contains("bonecrusher")
			|| lowerTarget.contains("tackle box")
			|| lowerTarget.contains("plank sack")
			|| lowerTarget.contains("bottomless compost bucket")
			|| lowerTarget.contains("coal bag")
			// Essence pouches (small/medium/large/giant/colossal): Fill/Empty moves
			// essence between inventory and pouch without consuming it.
			|| lowerTarget.endsWith(" pouch") || lowerTarget.equals("pouch")
			// Charged weapons: Charge/Uncharge/Unload actions return stored resources to
			// inventory, so the diff must be rebaselined rather than treated as profit.
			|| lowerTarget.contains("toxic blowpipe")
			|| lowerTarget.contains("trident of the")
			|| lowerTarget.contains("sanguinesti")
			|| lowerTarget.contains("tumeken's shadow") || lowerTarget.contains("tumekens shadow")
			|| lowerTarget.contains("craw's bow") || lowerTarget.contains("webweaver bow")
			|| lowerTarget.contains("viggora's chainmace") || lowerTarget.contains("thammaron's sceptre")
			|| lowerTarget.contains("accursed sceptre") || lowerTarget.contains("ursine chainmace")
			|| lowerTarget.contains("serpentine helm") || lowerTarget.contains("toxic staff")
			|| lowerTarget.contains("bow of faerdhinen") || lowerTarget.contains("blade of saeldor")
			|| lowerTarget.contains("crystal helm") || lowerTarget.contains("crystal body")
			|| lowerTarget.contains("crystal legs")
			|| lowerTarget.contains("venator bow") || lowerTarget.contains("tonalztics")
			|| lowerTarget.contains("tome of fire") || lowerTarget.contains("tome of water")
			|| lowerTarget.contains("tome of earth")
			|| lowerTarget.contains("ring of suffering") || lowerTarget.contains("xeric's talisman")
			|| lowerTarget.contains("bryophyta's staff") || lowerTarget.contains("arclight")
			|| lowerTarget.contains("amulet of blood fury") || lowerTarget.contains("bracelet of ethereum");
	}

	static boolean isFarmingPatchAction(String lowerTarget)
	{
		if (lowerTarget == null)
		{
			return false;
		}
		if (lowerTarget.contains("->"))
		{
			String[] parts = lowerTarget.split("->");
			if (parts.length >= 2)
			{
				String dest = parts[1].trim();
				return dest.contains("patch") || dest.contains("allotment")
					|| dest.contains("plant pot") || dest.contains("compost bin")
					|| dest.contains("soil") || dest.contains("vine");
			}
		}
		return lowerTarget.contains("patch") || lowerTarget.contains("plant pot") || lowerTarget.contains("allotment")
			|| lowerTarget.contains("compost bin") || lowerTarget.contains("soil") || lowerTarget.contains("vine");
	}

	static boolean isBirdHouseAction(String lowerTarget)
	{
		if (lowerTarget == null)
		{
			return false;
		}
		return lowerTarget.contains("bird house") || lowerTarget.contains("birdhouse");
	}

	static boolean isFarmingAnimation(int anim)
	{
		return anim == AnimationID.FARMING_SEED_DIBBING
			|| anim == AnimationID.FARMING_SEED_DIBBING_NODIB
			|| anim == AnimationID.FARMING_POUR_WATER
			|| anim == AnimationID.FARMING_PLANT_CURE
			|| anim == AnimationID.FARMING_TROWEL_DIG
			|| anim == AnimationID.FARMING_TROWEL_DIGGING
			|| anim == AnimationID.FARMING_TROWEL_SHORTDIG
			|| anim == AnimationID.FARMING_FILLING_PLANTPOT
			|| anim == AnimationID.FARMING_POUR_WATER_BOTTOMLESSBUCKET;
	}

	static boolean isFiremakingAnimation(int anim)
	{
		return anim == AnimationID.HUMAN_CREATEFIRE_SINGLE
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_GENERIC_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_OAK_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_WILLOW_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_TEAK_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_ARCTIC_PINE_LOG_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_MAPLE_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_MAHOGANY_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_YEW_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_MAGIC_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_REDWOOD_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_BLISTERWOOD_LOGS_NOLOOP;
	}

	static boolean isAlchemyAnimation(int anim)
	{
		return anim == AnimationID.HUMAN_CASTHIGHLVLALCHEMY
			|| anim == AnimationID.HUMAN_CASTLOWLVLALCHEMY
			|| anim == AnimationID.HUMAN_CASTHIGHLVLALCHEMY_FIRE
			|| anim == AnimationID.HUMAN_CASTLOWLVLALCHEMY_FIRE;
	}

	static boolean isDefensiveAnimation(int anim)
	{
		return anim == AnimationID.HUMAN_UNARMEDBLOCK
			|| anim == AnimationID.HUMAN_BLUNT_BLOCK
			|| anim == AnimationID.HUMAN_BLUNT_DEF
			|| anim == AnimationID.HUMAN_SWORD_DEF
			|| anim == AnimationID.HUMAN_STAFFORB_BLOCK
			|| anim == AnimationID.HUMAN_SHIELD_DEFENCE;
	}

	/**
	 * True when the diff is exactly "coins out, non-coin items in": the shape of
	 * an NPC dialogue purchase. Any other loss (noting fee, processing input,
	 * consumed supply) disqualifies it so those handlers keep ownership. Coins
	 * exchanged only for platinum tokens is a currency conversion, not a buy.
	 */
	static boolean isCoinsOnlyPurchase(Map<Integer, Integer> rawGains, Map<Integer, Integer> rawLosses)
	{
		if (rawLosses.size() != 1 || rawGains.isEmpty())
		{
			return false;
		}
		Integer coinsLost = rawLosses.get(ItemID.COINS);
		if (coinsLost == null || coinsLost <= 0)
		{
			return false;
		}
		for (int gainedId : rawGains.keySet())
		{
			if (gainedId != ItemID.COINS && gainedId != ItemID.PLATINUM)
			{
				return true;
			}
		}
		return false;
	}
}
