package com.coinflow;

import org.junit.Assert;
import org.junit.Test;

public class ConsumableRegistryTest
{
	@Test
	public void parsePotion_validDoses_extractedCorrectly()
	{
		ConsumableRegistry.PotionDose dose4 = ConsumableRegistry.parsePotion("Stamina potion(4)");
		Assert.assertNotNull(dose4);
		Assert.assertEquals("Stamina potion", dose4.getBaseName());
		Assert.assertEquals(4, dose4.getDose());

		ConsumableRegistry.PotionDose dose3 = ConsumableRegistry.parsePotion("Prayer potion (3)");
		Assert.assertNotNull(dose3);
		Assert.assertEquals("Prayer potion", dose3.getBaseName());
		Assert.assertEquals(3, dose3.getDose());

		ConsumableRegistry.PotionDose dose1 = ConsumableRegistry.parsePotion("Saradomin brew(1)");
		Assert.assertNotNull(dose1);
		Assert.assertEquals("Saradomin brew", dose1.getBaseName());
		Assert.assertEquals(1, dose1.getDose());
	}

	@Test
	public void parsePotion_nonPotion_returnsNull()
	{
		Assert.assertNull(ConsumableRegistry.parsePotion("Iron ore"));
		Assert.assertNull(ConsumableRegistry.parsePotion("Shark"));
		Assert.assertNull(ConsumableRegistry.parsePotion(null));
		Assert.assertNull(ConsumableRegistry.parsePotion(""));
	}

	@Test
	public void isConsumable_potions_true()
	{
		Assert.assertTrue(ConsumableRegistry.isConsumable(1, "Stamina potion(4)"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(2, "Prayer potion(2)"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(3, "Divine super combat potion(4)"));
	}

	@Test
	public void isConsumable_cookedFood_true()
	{
		Assert.assertTrue(ConsumableRegistry.isConsumable(10, "Shark"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(11, "Cooked karambwan"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(12, "Anglerfish"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(13, "Apple pie"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(14, "Pineapple pizza"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(15, "Potato with cheese"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(16, "Purple sweets"));
	}

	@Test
	public void isConsumable_rawFood_false()
	{
		Assert.assertFalse(ConsumableRegistry.isConsumable(20, "Raw shark"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(21, "Raw karambwan"));
	}

	@Test
	public void isConsumable_runes_true_essence_false()
	{
		Assert.assertTrue(ConsumableRegistry.isConsumable(30, "Fire rune"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(31, "Death rune"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(32, "Blood rune"));

		Assert.assertFalse(ConsumableRegistry.isConsumable(33, "Pure essence"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(34, "Rune essence"));
	}

	@Test
	public void isConsumable_ammunition_true()
	{
		Assert.assertTrue(ConsumableRegistry.isConsumable(40, "Dragon bolts (e)"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(41, "Rune arrow"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(42, "Adamant dart"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(43, "Red chinchompa"));
	}

	@Test
	public void isConsumable_teleports_true()
	{
		Assert.assertTrue(ConsumableRegistry.isConsumable(50, "Varrock teleport"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(51, "Teleport to house"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(52, "Teleport to target"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(53, "House tab"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(54, "Lumbridge teleport"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(55, "Camelot teleport"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(56, "Zul-andra teleport"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(57, "Nardah teleport"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(58, "Digsite teleport"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(59, "Ardeaglais teleport scroll"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(34033, "Ardeaglais teleport"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(70, "Colossal wyrm teleport scroll"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(71, "Chasm teleport scroll"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(72, "Target teleport scroll"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(73, "Lumberyard teleport scroll"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(74, "Watson teleport scroll"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(75, "Nardah teleport scroll"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(76, "Digsite teleport scroll"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(77, "Feldip hills teleport scroll"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(78, "Teleport scroll"));
		Assert.assertTrue(ConsumableRegistry.isConsumable(79, "Icy basalt"));
	}

	@Test
	public void isConsumable_teleportExclusions_false()
	{
		Assert.assertFalse(ConsumableRegistry.isConsumable(500, "Clue scroll (easy)"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(501, "Clue scroll (master)"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(502, "Eternal teleport crystal"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(503, "Master scroll book"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(504, "Enhanced crystal teleport seed"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(505, "Teleport anchor charm"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(506, "Dexterous prayer scroll"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(507, "Arcane prayer scroll"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(508, "Ancient tablet"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(509, "Teleport anchoring scroll"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(510, "Twisted teleport scroll"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(511, "Trailblazer teleport scroll"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(512, "Trailblazer reloaded home teleport scroll"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(513, "Shattered teleport scroll"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(514, "Speedy teleport scroll"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(515, "Echo home teleport scroll"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(516, "Armageddon teleport scroll"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(517, "Annihilation teleport scroll"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(518, "Teleport focus"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(519, "Teleport trap"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(520, "Teleport card"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(521, "Ancient teleporter"));
	}

	@Test
	public void isConsumable_resourcesAndEquipment_false()
	{
		Assert.assertFalse(ConsumableRegistry.isConsumable(60, "Iron ore"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(61, "Magic logs"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(62, "Rune bar"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(63, "Rune pickaxe"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(64, "Abyssal whip"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(65, "Vial"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(66, "Coins"));
	}

	@Test
	public void isCookingPair_validPairs_true()
	{
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Raw manta ray", "Manta ray"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Raw manta ray", "Burnt manta ray"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Raw shark", "Shark"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Raw shark", "Burnt shark"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Raw karambwan", "Cooked karambwan"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Raw karambwan", "Poison karambwan"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Raw karambwan", "Burnt karambwan"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Raw trout", "Trout"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Raw trout", "Burnt fish"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Raw beef", "Cooked meat"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Raw beef", "Burnt meat"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Raw chicken", "Cooked chicken"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Potato", "Baked potato"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Potato", "Burnt potato"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Uncooked summer pie", "Summer pie"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Uncooked summer pie", "Burnt pie"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Uncooked pizza", "Plain pizza"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Uncooked pizza", "Burnt pizza"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Uncooked cake", "Cake"));
		Assert.assertTrue(ConsumableRegistry.isCookingPair("Uncooked stew", "Stew"));
	}

	@Test
	public void isCookingPair_invalidPairs_false()
	{
		Assert.assertFalse(ConsumableRegistry.isCookingPair("Raw manta ray", "Lobster"));
		Assert.assertFalse(ConsumableRegistry.isCookingPair("Shark", "Cooked shark"));
		Assert.assertFalse(ConsumableRegistry.isCookingPair("Coal", "Manta ray"));
		Assert.assertFalse(ConsumableRegistry.isCookingPair(null, "Manta ray"));
		Assert.assertFalse(ConsumableRegistry.isCookingPair("Raw manta ray", null));
	}

	@Test
	public void parsePotion_chargedJewelryAndDevices_returnsNull()
	{
		Assert.assertNull(ConsumableRegistry.parsePotion("Amulet of glory(4)"));
		Assert.assertNull(ConsumableRegistry.parsePotion("Ring of dueling(4)"));
		Assert.assertNull(ConsumableRegistry.parsePotion("Games necklace(4)"));
		Assert.assertNull(ConsumableRegistry.parsePotion("Combat bracelet(4)"));
		Assert.assertNull(ConsumableRegistry.parsePotion("Skills necklace(4)"));
		Assert.assertNull(ConsumableRegistry.parsePotion("Digsite pendant(5)"));
		Assert.assertNull(ConsumableRegistry.parsePotion("Teleport crystal(4)"));
		Assert.assertNull(ConsumableRegistry.parsePotion("Pharaoh's sceptre(3)"));

		Assert.assertFalse(ConsumableRegistry.isConsumable(1704, "Amulet of glory(4)"));
		Assert.assertFalse(ConsumableRegistry.isConsumable(2552, "Ring of dueling(4)"));
	}

	@Test
	public void isFoodPortion_cakesAndChocolateCakes_matchesCorrectly()
	{
		Assert.assertTrue(ConsumableRegistry.isFoodPortion("2/3 cake", "cake"));
		Assert.assertTrue(ConsumableRegistry.isFoodPortion("slice of cake", "2/3 cake"));
		Assert.assertTrue(ConsumableRegistry.isFoodPortion("2/3 chocolate cake", "chocolate cake"));
		Assert.assertTrue(ConsumableRegistry.isFoodPortion("chocolate slice", "2/3 chocolate cake"));
	}
}
