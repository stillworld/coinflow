package com.coinflow.reconciliation;

import org.junit.Assert;
import org.junit.Test;

public class ProcessingPatternRegistryTest
{
	@Test
	public void cooking_rawFish_matches()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Raw manta ray", "Manta ray", 1);
		Assert.assertNotNull(res);
		Assert.assertEquals(1, res.getPrimaryConsumedQty());
		Assert.assertFalse(res.isBurntJunk());
	}

	@Test
	public void cooking_burntFish_markedAsBurntJunk()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Raw shark", "Burnt shark", 1);
		Assert.assertNotNull(res);
		Assert.assertEquals(1, res.getPrimaryConsumedQty());
		Assert.assertTrue(res.isBurntJunk());
	}

	@Test
	public void cooking_unrelatedBurntItems_doesNotMatchBurntJunk()
	{
		Assert.assertNull(ProcessingPatternRegistry.match("Raw shark", "Burnt page", 1));
		Assert.assertNull(ProcessingPatternRegistry.match("Raw manta ray", "Burnt page", 1));
	}

	@Test
	public void fletching_logsToBows_matches1to1()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Magic logs", "Magic longbow (u)", 1);
		Assert.assertNotNull(res);
		Assert.assertEquals(1, res.getPrimaryConsumedQty());

		ProcessingPatternRegistry.MatchResult res2 =
			ProcessingPatternRegistry.match("Logs", "Shortbow (u)", 1);
		Assert.assertNotNull(res2);
		Assert.assertEquals(1, res2.getPrimaryConsumedQty());
	}

	@Test
	public void fletching_logsToShield_requires2LogsPerShield()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Magic logs", "Magic shield", 2);
		Assert.assertNotNull(res);
		Assert.assertEquals(4, res.getPrimaryConsumedQty());
	}

	@Test
	public void fletching_logsToShafts_correctRatios()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Logs", "Arrow shaft", 15);
		Assert.assertNotNull(res);
		Assert.assertEquals(1, res.getPrimaryConsumedQty());

		ProcessingPatternRegistry.MatchResult resRedwood =
			ProcessingPatternRegistry.match("Redwood logs", "Arrow shaft", 60);
		Assert.assertNotNull(resRedwood);
		Assert.assertEquals(1, resRedwood.getPrimaryConsumedQty());
	}

	@Test
	public void fletching_bowStringing_requiresBowString()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Magic longbow (u)", "Magic longbow", 1);
		Assert.assertNotNull(res);
		Assert.assertEquals(1, res.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(1), res.getSecondariesNeeded().get("bow string"));
	}

	@Test
	public void herblore_cleaningHerbs_matches()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Grimy ranarr weed", "Ranarr weed", 14);
		Assert.assertNotNull(res);
		Assert.assertEquals(14, res.getPrimaryConsumedQty());
	}

	@Test
	public void herblore_unfinishedPotions_requiresVialOfWater()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Ranarr weed", "Ranarr potion (unf)", 14);
		Assert.assertNotNull(res);
		Assert.assertEquals(14, res.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(14), res.getSecondariesNeeded().get("vial of water"));
	}

	@Test
	public void crafting_cutGems_matches()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Uncut diamond", "Diamond", 5);
		Assert.assertNotNull(res);
		Assert.assertEquals(5, res.getPrimaryConsumedQty());
	}

	@Test
	public void crafting_jewellery_requiresBarAndGem()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Gold bar", "Ruby ring", 1);
		Assert.assertNotNull(res);
		Assert.assertEquals(1, res.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(1), res.getSecondariesNeeded().get("ruby"));
	}

	@Test
	public void crafting_dragonhideArmor_requiresMultipleLeathers()
	{
		ProcessingPatternRegistry.MatchResult resBody =
			ProcessingPatternRegistry.match("Green dragon leather", "Green d'hide body", 1);
		Assert.assertNotNull(resBody);
		Assert.assertEquals(3, resBody.getPrimaryConsumedQty());

		ProcessingPatternRegistry.MatchResult resChaps =
			ProcessingPatternRegistry.match("Green dragon leather", "Green d'hide chaps", 2);
		Assert.assertNotNull(resChaps);
		Assert.assertEquals(4, resChaps.getPrimaryConsumedQty());
	}

	@Test
	public void smithing_smelting_requiresOreAndCoal()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Mithril ore", "Mithril bar", 5);
		Assert.assertNotNull(res);
		Assert.assertEquals(5, res.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(20), res.getSecondariesNeeded().get("coal"));
	}

	@Test
	public void smithing_forgingPlatebody_requires5Bars()
	{
		// Real OSRS item names: Runite bar -> Rune platebody
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Runite bar", "Rune platebody", 2);
		Assert.assertNotNull(res);
		Assert.assertEquals(10, res.getPrimaryConsumedQty());

		// Adamantite bar -> Adamant platebody
		ProcessingPatternRegistry.MatchResult resAddy =
			ProcessingPatternRegistry.match("Adamantite bar", "Adamant platebody", 1);
		Assert.assertNotNull(resAddy);
		Assert.assertEquals(5, resAddy.getPrimaryConsumedQty());
	}

	@Test
	public void smithing_boltsUnf_1BarYields10()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Runite bar", "Rune bolts (unf)", 10);
		Assert.assertNotNull(res);
		Assert.assertEquals(1, res.getPrimaryConsumedQty());
	}

	@Test
	public void smithing_bronzeSmelting_copperAndTinOre()
	{
		ProcessingPatternRegistry.MatchResult resCopper =
			ProcessingPatternRegistry.match("Copper ore", "Bronze bar", 3);
		Assert.assertNotNull(resCopper);
		Assert.assertEquals(3, resCopper.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(3), resCopper.getSecondariesNeeded().get("tin ore"));

		ProcessingPatternRegistry.MatchResult resTin =
			ProcessingPatternRegistry.match("Tin ore", "Bronze bar", 2);
		Assert.assertNotNull(resTin);
		Assert.assertEquals(2, resTin.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(2), resTin.getSecondariesNeeded().get("copper ore"));
	}

	@Test
	public void fletching_broadArrows_requiresBroadArrowheads()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Headless arrow", "Broad arrow", 15);
		Assert.assertNotNull(res);
		Assert.assertEquals(15, res.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(15), res.getSecondariesNeeded().get("broad arrowheads"));
	}

	@Test
	public void fletching_boltTipping_requiresBoltTips()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Runite bolts", "Diamond bolts", 10);
		Assert.assertNotNull(res);
		Assert.assertEquals(10, res.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(10), res.getSecondariesNeeded().get("diamond bolt tips"));
	}

	@Test
	public void herblore_finishingPotion_flaggedForDynamicSecondaryConsumption()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Ranarr potion (unf)", "Prayer potion(3)", 14);
		Assert.assertNotNull(res);
		Assert.assertEquals(14, res.getPrimaryConsumedQty());
		Assert.assertTrue(res.isPotionFinishing());
	}

	@Test
	public void crafting_topazJewellery_matchesRedTopaz()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Gold bar", "Topaz ring", 1);
		Assert.assertNotNull(res);
		Assert.assertEquals(1, res.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(1), res.getSecondariesNeeded().get("red topaz"));
	}

	@Test
	public void crafting_silverCrafting_matchesTiaraAndSymbols()
	{
		ProcessingPatternRegistry.MatchResult resTiara =
			ProcessingPatternRegistry.match("Silver bar", "Tiara", 1);
		Assert.assertNotNull(resTiara);
		Assert.assertEquals(1, resTiara.getPrimaryConsumedQty());

		ProcessingPatternRegistry.MatchResult resHoly =
			ProcessingPatternRegistry.match("Silver bar", "Holy symbol (u)", 1);
		Assert.assertNotNull(resHoly);
		Assert.assertEquals(1, resHoly.getPrimaryConsumedQty());
	}

	@Test
	public void crafting_hardleatherBody_matches()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Hard leather", "Hardleather body", 1);
		Assert.assertNotNull(res);
		Assert.assertEquals(1, res.getPrimaryConsumedQty());
	}

	@Test
	public void smithing_cannonballs_1BarTo4Cannonballs()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Steel bar", "Cannonball", 8);
		Assert.assertNotNull(res);
		Assert.assertEquals(2, res.getPrimaryConsumedQty());
	}

	@Test
	public void magic_plankMake_requiresCoins()
	{
		ProcessingPatternRegistry.MatchResult res =
			ProcessingPatternRegistry.match("Mahogany logs", "Mahogany plank", 1);
		Assert.assertNotNull(res);
		Assert.assertEquals(1, res.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(1050), res.getSecondariesNeeded().get("coins"));
	}

	@Test
	public void unrelatedItems_doNotMatch()
	{
		Assert.assertNull(ProcessingPatternRegistry.match("Coal", "Rune scimitar", 1));
		Assert.assertNull(ProcessingPatternRegistry.match("Cooking cape", "Rune platebody", 1));
		Assert.assertNull(ProcessingPatternRegistry.match("Iron ore", "Magic longbow", 1));
	}

	@Test
	public void cooking_wineMaking_matches()
	{
		ProcessingPatternRegistry.MatchResult resGood =
			ProcessingPatternRegistry.match("Grapes", "Jug of wine", 14);
		Assert.assertNotNull(resGood);
		Assert.assertEquals(14, resGood.getPrimaryConsumedQty());
		Assert.assertFalse(resGood.isBurntJunk());
		Assert.assertEquals(Integer.valueOf(14), resGood.getSecondariesNeeded().get("jug of water"));

		ProcessingPatternRegistry.MatchResult resBad =
			ProcessingPatternRegistry.match("Grapes", "Jug of bad wine", 5);
		Assert.assertNotNull(resBad);
		Assert.assertEquals(5, resBad.getPrimaryConsumedQty());
		Assert.assertTrue(resBad.isBurntJunk());
		Assert.assertEquals(Integer.valueOf(5), resBad.getSecondariesNeeded().get("jug of water"));
	}

	@Test
	public void cooking_doughMaking_andBaking_matches()
	{
		ProcessingPatternRegistry.MatchResult resDough =
			ProcessingPatternRegistry.match("Pot of flour", "Bread dough", 10);
		Assert.assertNotNull(resDough);
		Assert.assertEquals(10, resDough.getPrimaryConsumedQty());
		Assert.assertTrue(resDough.getSecondariesNeeded().containsKey("jug of water|bucket of water"));

		ProcessingPatternRegistry.MatchResult resBread =
			ProcessingPatternRegistry.match("Bread dough", "Bread", 10);
		Assert.assertNotNull(resBread);
		Assert.assertEquals(10, resBread.getPrimaryConsumedQty());
		Assert.assertFalse(resBread.isBurntJunk());

		ProcessingPatternRegistry.MatchResult resBurnt =
			ProcessingPatternRegistry.match("Bread dough", "Burnt bread", 2);
		Assert.assertNotNull(resBurnt);
		Assert.assertEquals(2, resBurnt.getPrimaryConsumedQty());
		Assert.assertTrue(resBurnt.isBurntJunk());
	}

	@Test
	public void fletching_shieldsAndCrossbows_matches()
	{
		// Shield carving requires 2 logs per shield
		ProcessingPatternRegistry.MatchResult resShieldU =
			ProcessingPatternRegistry.match("Yew logs", "Yew shield (u)", 3);
		Assert.assertNotNull(resShieldU);
		Assert.assertEquals(6, resShieldU.getPrimaryConsumedQty());

		// Shield stringing requires 2 bow strings per shield
		ProcessingPatternRegistry.MatchResult resShieldString =
			ProcessingPatternRegistry.match("Yew shield (u)", "Yew shield", 3);
		Assert.assertNotNull(resShieldString);
		Assert.assertEquals(3, resShieldString.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(6), resShieldString.getSecondariesNeeded().get("bow string"));

		// Crossbow limbs assembly
		ProcessingPatternRegistry.MatchResult resXbow =
			ProcessingPatternRegistry.match("Rune limbs", "Rune crossbow (u)", 5);
		Assert.assertNotNull(resXbow);
		Assert.assertEquals(5, resXbow.getPrimaryConsumedQty());
		Assert.assertTrue(resXbow.getSecondariesNeeded().containsKey("wooden stock|oak stock|willow stock|teak stock|maple stock|mahogany stock"));

		// Crossbow stringing
		ProcessingPatternRegistry.MatchResult resXbowString =
			ProcessingPatternRegistry.match("Rune crossbow (u)", "Rune crossbow", 5);
		Assert.assertNotNull(resXbowString);
		Assert.assertEquals(5, resXbowString.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(5), resXbowString.getSecondariesNeeded().get("crossbow string"));

		// Amethyst broad bolts tipping
		ProcessingPatternRegistry.MatchResult resAmethystBolts =
			ProcessingPatternRegistry.match("Broad bolts", "Amethyst broad bolts", 10);
		Assert.assertNotNull(resAmethystBolts);
		Assert.assertEquals(10, resAmethystBolts.getPrimaryConsumedQty());
		Assert.assertTrue(resAmethystBolts.getSecondariesNeeded().containsKey("amethyst bolt tips|amethyst bolt tip"));
		Assert.assertEquals(Integer.valueOf(10), resAmethystBolts.getSecondariesNeeded().get("amethyst bolt tips|amethyst bolt tip"));
	}

	@Test
	public void fletching_javelins_matches()
	{
		ProcessingPatternRegistry.MatchResult resFeather =
			ProcessingPatternRegistry.match("Javelin shaft", "Headless javelin", 15);
		Assert.assertNotNull(resFeather);
		Assert.assertEquals(15, resFeather.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(15), resFeather.getSecondariesNeeded().get("feather"));

		ProcessingPatternRegistry.MatchResult resHeads =
			ProcessingPatternRegistry.match("Headless javelin", "Rune javelin", 15);
		Assert.assertNotNull(resHeads);
		Assert.assertEquals(15, resHeads.getPrimaryConsumedQty());
		Assert.assertTrue(resHeads.getSecondariesNeeded().containsKey("rune javelin heads|rune javelin head"));
	}

	@Test
	public void herblore_herbTarAndDivines_matches()
	{
		// Guam tar: 15 tar requires 1 guam leaf and 15 swamp tar
		ProcessingPatternRegistry.MatchResult resTar =
			ProcessingPatternRegistry.match("Guam leaf", "Guam tar", 15);
		Assert.assertNotNull(resTar);
		Assert.assertEquals(1, resTar.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(15), resTar.getSecondariesNeeded().get("swamp tar"));

		// Lava scale crushing
		ProcessingPatternRegistry.MatchResult resLavaScale =
			ProcessingPatternRegistry.match("Lava scale", "Lava scale shard", 5);
		Assert.assertNotNull(resLavaScale);
		Assert.assertEquals(5, resLavaScale.getPrimaryConsumedQty());

		// Divine super combat potion(4): 4 crystal dust per potion
		ProcessingPatternRegistry.MatchResult resDivine =
			ProcessingPatternRegistry.match("Super combat potion(4)", "Divine super combat potion(4)", 2);
		Assert.assertNotNull(resDivine);
		Assert.assertEquals(2, resDivine.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(8), resDivine.getSecondariesNeeded().get("crystal dust"));

		// Extended antifire(4): 4 lava scale shards per potion
		ProcessingPatternRegistry.MatchResult resExt =
			ProcessingPatternRegistry.match("Antifire potion(4)", "Extended antifire(4)", 3);
		Assert.assertNotNull(resExt);
		Assert.assertEquals(3, resExt.getPrimaryConsumedQty());
		Assert.assertTrue(resExt.getSecondariesNeeded().containsKey("lava scale shard|lava scale shards"));
		Assert.assertEquals(Integer.valueOf(12), resExt.getSecondariesNeeded().get("lava scale shard|lava scale shards"));
	}

	@Test
	public void crafting_amethystPotteryAndMoltenGlass_matches()
	{
		// Amethyst dart tips: 8 tips per 1 amethyst
		ProcessingPatternRegistry.MatchResult resDarts =
			ProcessingPatternRegistry.match("Amethyst", "Amethyst dart tips", 16);
		Assert.assertNotNull(resDarts);
		Assert.assertEquals(2, resDarts.getPrimaryConsumedQty());

		// Amethyst bolt tips: 15 tips per 1 amethyst
		ProcessingPatternRegistry.MatchResult resBolts =
			ProcessingPatternRegistry.match("Amethyst", "Amethyst bolt tips", 15);
		Assert.assertNotNull(resBolts);
		Assert.assertEquals(1, resBolts.getPrimaryConsumedQty());

		// Pottery: soft clay -> unfired bowl
		ProcessingPatternRegistry.MatchResult resPottery =
			ProcessingPatternRegistry.match("Soft clay", "Unfired bowl", 5);
		Assert.assertNotNull(resPottery);
		Assert.assertEquals(5, resPottery.getPrimaryConsumedQty());

		// Giant seaweed: 6 molten glass per 1 giant seaweed
		ProcessingPatternRegistry.MatchResult resGiantSeaweed =
			ProcessingPatternRegistry.match("Giant seaweed", "Molten glass", 18);
		Assert.assertNotNull(resGiantSeaweed);
		Assert.assertEquals(3, resGiantSeaweed.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(18), resGiantSeaweed.getSecondariesNeeded().get("bucket of sand"));

		// Normal seaweed: 1:1
		ProcessingPatternRegistry.MatchResult resSeaweed =
			ProcessingPatternRegistry.match("Seaweed", "Molten glass", 10);
		Assert.assertNotNull(resSeaweed);
		Assert.assertEquals(10, resSeaweed.getPrimaryConsumedQty());
		Assert.assertEquals(Integer.valueOf(10), resSeaweed.getSecondariesNeeded().get("bucket of sand"));
	}

	@Test
	public void runecrafting_essenceToRunes_matches()
	{
		ProcessingPatternRegistry.MatchResult resBlood =
			ProcessingPatternRegistry.match("Pure essence", "Blood rune", 28);
		Assert.assertNotNull(resBlood);
		Assert.assertEquals(28, resBlood.getPrimaryConsumedQty());
		Assert.assertTrue(resBlood.getSecondariesNeeded().isEmpty());

		ProcessingPatternRegistry.MatchResult resLava =
			ProcessingPatternRegistry.match("Pure essence", "Lava rune", 26);
		Assert.assertNotNull(resLava);
		Assert.assertEquals(26, resLava.getPrimaryConsumedQty());
		Assert.assertTrue(resLava.getSecondariesNeeded().containsKey("earth rune|fire rune"));
		Assert.assertEquals(Integer.valueOf(26), resLava.getSecondariesNeeded().get("earth rune|fire rune"));
	}
}
