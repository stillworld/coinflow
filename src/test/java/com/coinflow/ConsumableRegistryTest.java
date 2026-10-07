package com.coinflow;

import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class ConsumableRegistryTest
{
	private static boolean isConsumable(int itemId, String itemName)
	{
		return ConsumableRegistry.isConsumable(itemId, itemName, null);
	}

	private static ItemManager mockItemManager(int itemId, String name, String... actions)
	{
		ItemComposition comp = Mockito.mock(ItemComposition.class);
		Mockito.when(comp.getName()).thenReturn(name);
		Mockito.when(comp.getInventoryActions()).thenReturn(actions);
		ItemManager itemManager = Mockito.mock(ItemManager.class);
		Mockito.when(itemManager.getItemComposition(itemId)).thenReturn(comp);
		return itemManager;
	}

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
		Assert.assertTrue(isConsumable(1, "Stamina potion(4)"));
		Assert.assertTrue(isConsumable(2, "Prayer potion(2)"));
		Assert.assertTrue(isConsumable(3, "Divine super combat potion(4)"));
	}

	@Test
	public void isConsumable_foodEatAction_true()
	{
		String[] foods = {
			"Shark", "Cooked karambwan", "Anglerfish", "Apple pie", "Pineapple pizza",
			"Potato with cheese", "Purple sweets", "Wild pie", "Half a wild pie",
			"Spicy stew", "Curry", "Ugthanki kebab", "Rainbow fish", "Shrimps",
			"Roast beast meat", "Thin snail meat", "Strawberry",
			"Cooked moonlight antelope", "Premade t'd crunch",
		};

		for (int i = 0; i < foods.length; i++)
		{
			int id = 10 + i;
			ItemManager itemManager = mockItemManager(id, foods[i], "Eat", "Drop");
			Assert.assertTrue(foods[i], ConsumableRegistry.isConsumable(id, foods[i], itemManager));
		}
	}

	@Test
	public void isConsumable_foodWithoutEatAction_false()
	{
		// Food names alone are not sufficient; the item must expose an Eat action
		Assert.assertFalse(isConsumable(30, "Shark"));
		Assert.assertFalse(isConsumable(31, "Cooked karambwan"));
		Assert.assertFalse(isConsumable(32, "Anglerfish"));
	}

	@Test
	public void isConsumable_drinkAction_true()
	{
		String[] drinks = {"Wizard blizzard", "Dwarven stout", "Jug of wine", "Beer", "Braindeath 'rum'"};

		for (int i = 0; i < drinks.length; i++)
		{
			int id = 100 + i;
			ItemManager itemManager = mockItemManager(id, drinks[i], "Drink", "Drop");
			Assert.assertTrue(drinks[i], ConsumableRegistry.isConsumable(id, drinks[i], itemManager));
		}
	}

	@Test
	public void isConsumable_rawFood_false()
	{
		Assert.assertFalse(isConsumable(20, "Raw shark"));
		Assert.assertFalse(isConsumable(21, "Raw karambwan"));
	}

	@Test
	public void isConsumable_runes_true_essence_false()
	{
		Assert.assertTrue(isConsumable(30, "Fire rune"));
		Assert.assertTrue(isConsumable(31, "Death rune"));
		Assert.assertTrue(isConsumable(32, "Blood rune"));

		Assert.assertFalse(isConsumable(33, "Pure essence"));
		Assert.assertFalse(isConsumable(34, "Rune essence"));
	}

	@Test
	public void isConsumable_ammunition_true()
	{
		Assert.assertTrue(isConsumable(40, "Dragon bolts (e)"));
		Assert.assertTrue(isConsumable(41, "Rune arrow"));
		Assert.assertTrue(isConsumable(42, "Adamant dart"));
		Assert.assertTrue(isConsumable(43, "Red chinchompa"));
	}

	@Test
	public void isConsumable_teleports_true()
	{
		Assert.assertTrue(isConsumable(50, "Varrock teleport"));
		Assert.assertTrue(isConsumable(51, "Teleport to house"));
		Assert.assertTrue(isConsumable(52, "Teleport to target"));
		Assert.assertTrue(isConsumable(53, "House tab"));
		Assert.assertTrue(isConsumable(54, "Lumbridge teleport"));
		Assert.assertTrue(isConsumable(55, "Camelot teleport"));
		Assert.assertTrue(isConsumable(56, "Zul-andra teleport"));
		Assert.assertTrue(isConsumable(57, "Nardah teleport"));
		Assert.assertTrue(isConsumable(58, "Digsite teleport"));
		Assert.assertTrue(isConsumable(59, "Ardeaglais teleport scroll"));
		Assert.assertTrue(isConsumable(34033, "Ardeaglais teleport"));
		Assert.assertTrue(isConsumable(70, "Colossal wyrm teleport scroll"));
		Assert.assertTrue(isConsumable(71, "Chasm teleport scroll"));
		Assert.assertTrue(isConsumable(72, "Target teleport scroll"));
		Assert.assertTrue(isConsumable(73, "Lumberyard teleport scroll"));
		Assert.assertTrue(isConsumable(74, "Watson teleport scroll"));
		Assert.assertTrue(isConsumable(75, "Nardah teleport scroll"));
		Assert.assertTrue(isConsumable(76, "Digsite teleport scroll"));
		Assert.assertTrue(isConsumable(77, "Feldip hills teleport scroll"));
		Assert.assertTrue(isConsumable(78, "Teleport scroll"));
		Assert.assertTrue(isConsumable(79, "Icy basalt"));
	}

	@Test
	public void isConsumable_teleportExclusions_false()
	{
		Assert.assertFalse(isConsumable(500, "Clue scroll (easy)"));
		Assert.assertFalse(isConsumable(501, "Clue scroll (master)"));
		Assert.assertFalse(isConsumable(502, "Eternal teleport crystal"));
		Assert.assertFalse(isConsumable(503, "Master scroll book"));
		Assert.assertFalse(isConsumable(504, "Enhanced crystal teleport seed"));
		Assert.assertFalse(isConsumable(505, "Teleport anchor charm"));
		Assert.assertFalse(isConsumable(506, "Dexterous prayer scroll"));
		Assert.assertFalse(isConsumable(507, "Arcane prayer scroll"));
		Assert.assertFalse(isConsumable(508, "Ancient tablet"));
		Assert.assertFalse(isConsumable(509, "Teleport anchoring scroll"));
		Assert.assertFalse(isConsumable(510, "Twisted teleport scroll"));
		Assert.assertFalse(isConsumable(511, "Trailblazer teleport scroll"));
		Assert.assertFalse(isConsumable(512, "Trailblazer reloaded home teleport scroll"));
		Assert.assertFalse(isConsumable(513, "Shattered teleport scroll"));
		Assert.assertFalse(isConsumable(514, "Speedy teleport scroll"));
		Assert.assertFalse(isConsumable(515, "Echo home teleport scroll"));
		Assert.assertFalse(isConsumable(516, "Armageddon teleport scroll"));
		Assert.assertFalse(isConsumable(517, "Annihilation teleport scroll"));
		Assert.assertFalse(isConsumable(518, "Teleport focus"));
		Assert.assertFalse(isConsumable(519, "Teleport trap"));
		Assert.assertFalse(isConsumable(520, "Teleport card"));
		Assert.assertFalse(isConsumable(521, "Ancient teleporter"));
	}

	@Test
	public void isConsumable_resourcesAndEquipment_false()
	{
		Assert.assertFalse(isConsumable(60, "Iron ore"));
		Assert.assertFalse(isConsumable(61, "Magic logs"));
		Assert.assertFalse(isConsumable(62, "Rune bar"));
		Assert.assertFalse(isConsumable(63, "Rune pickaxe"));
		Assert.assertFalse(isConsumable(64, "Abyssal whip"));
		Assert.assertFalse(isConsumable(65, "Vial"));
		Assert.assertFalse(isConsumable(66, "Coins"));
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

		Assert.assertFalse(isConsumable(1704, "Amulet of glory(4)"));
		Assert.assertFalse(isConsumable(2552, "Ring of dueling(4)"));
	}

	@Test
	public void isFoodPortion_cakesAndChocolateCakes_matchesCorrectly()
	{
		Assert.assertTrue(ConsumableRegistry.isFoodPortion("2/3 cake", "cake"));
		Assert.assertTrue(ConsumableRegistry.isFoodPortion("slice of cake", "2/3 cake"));
		Assert.assertTrue(ConsumableRegistry.isFoodPortion("2/3 chocolate cake", "chocolate cake"));
		Assert.assertTrue(ConsumableRegistry.isFoodPortion("chocolate slice", "2/3 chocolate cake"));
	}

	@Test
	public void isConsumable_durableDrinkable_false()
	{
		ItemManager itemManager = mockItemManager(9003, "Waterskin(4)", "Drink", "Drop");
		Assert.assertFalse(ConsumableRegistry.isConsumable(9003, "Waterskin(4)", itemManager));
	}

	@Test
	public void isConsumable_noEatOrDrinkAction_false()
	{
		ItemManager itemManager = mockItemManager(9004, "Rune pickaxe", "Wield", "Drop");
		Assert.assertFalse(ConsumableRegistry.isConsumable(9004, "Rune pickaxe", itemManager));
	}

	@Test
	public void isLastChargeTeleportJewelry_lastCharge_true()
	{
		Assert.assertTrue(ConsumableRegistry.isLastChargeTeleportJewelry("Ring of dueling(1)"));
		Assert.assertTrue(ConsumableRegistry.isLastChargeTeleportJewelry("Games necklace(1)"));
		Assert.assertTrue(ConsumableRegistry.isLastChargeTeleportJewelry("Slayer ring (1)"));
		Assert.assertTrue(ConsumableRegistry.isLastChargeTeleportJewelry("Necklace of passage(1)"));
		Assert.assertTrue(ConsumableRegistry.isLastChargeTeleportJewelry("Burning amulet(1)"));
		Assert.assertTrue(ConsumableRegistry.isLastChargeTeleportJewelry("Digsite pendant(1)"));
		Assert.assertTrue(ConsumableRegistry.isLastChargeTeleportJewelry("Ring of returning(1)"));
	}

	@Test
	public void isLastChargeTeleportJewelry_notLastChargeOrNotCrumbling_false()
	{
		Assert.assertFalse(ConsumableRegistry.isLastChargeTeleportJewelry("Ring of dueling(2)"));
		Assert.assertFalse(ConsumableRegistry.isLastChargeTeleportJewelry("Games necklace(8)"));
		Assert.assertFalse(ConsumableRegistry.isLastChargeTeleportJewelry("Amulet of glory(1)"));
		Assert.assertFalse(ConsumableRegistry.isLastChargeTeleportJewelry("Ring of wealth (1)"));
		Assert.assertFalse(ConsumableRegistry.isLastChargeTeleportJewelry("Combat bracelet(1)"));
		Assert.assertFalse(ConsumableRegistry.isLastChargeTeleportJewelry("Ring of recoil"));
		Assert.assertFalse(ConsumableRegistry.isLastChargeTeleportJewelry("Ring of life"));
		Assert.assertFalse(ConsumableRegistry.isLastChargeTeleportJewelry("Prayer potion(1)"));
		Assert.assertFalse(ConsumableRegistry.isLastChargeTeleportJewelry(null));
		Assert.assertFalse(ConsumableRegistry.isLastChargeTeleportJewelry(""));
	}

	@Test
	public void isCrumblingJewelry_crumblingItems_true()
	{
		Assert.assertTrue(ConsumableRegistry.isCrumblingJewelry("Ring of dueling(1)"));
		Assert.assertTrue(ConsumableRegistry.isCrumblingJewelry("Games necklace(1)"));
		Assert.assertTrue(ConsumableRegistry.isCrumblingJewelry("Castle wars bracelet(1)"));
		Assert.assertTrue(ConsumableRegistry.isCrumblingJewelry("Ring of recoil"));
		Assert.assertTrue(ConsumableRegistry.isCrumblingJewelry("Ring of life"));
		Assert.assertTrue(ConsumableRegistry.isCrumblingJewelry("Binding necklace"));
		Assert.assertTrue(ConsumableRegistry.isCrumblingJewelry("Dodgy necklace"));
		Assert.assertTrue(ConsumableRegistry.isCrumblingJewelry("Bracelet of slaughter"));
		Assert.assertTrue(ConsumableRegistry.isCrumblingJewelry("Expeditious bracelet"));
		Assert.assertTrue(ConsumableRegistry.isCrumblingJewelry("Ring of forging"));
		Assert.assertTrue(ConsumableRegistry.isCrumblingJewelry("Bracelet of clay"));
		Assert.assertTrue(ConsumableRegistry.isCrumblingJewelry("Amulet of chemistry"));
	}

	@Test
	public void isCrumblingJewelry_persistentOrPartialCharge_false()
	{
		Assert.assertFalse(ConsumableRegistry.isCrumblingJewelry("Ring of dueling(2)"));
		Assert.assertFalse(ConsumableRegistry.isCrumblingJewelry("Amulet of glory(1)"));
		Assert.assertFalse(ConsumableRegistry.isCrumblingJewelry("Combat bracelet(1)"));
		Assert.assertFalse(ConsumableRegistry.isCrumblingJewelry("Amulet of glory"));
		Assert.assertFalse(ConsumableRegistry.isCrumblingJewelry("Amulet of eternal glory"));
		Assert.assertFalse(ConsumableRegistry.isCrumblingJewelry("Ring of wealth (1)"));
		Assert.assertFalse(ConsumableRegistry.isCrumblingJewelry("Slayer ring (eternal)"));
		Assert.assertFalse(ConsumableRegistry.isCrumblingJewelry("Castle wars bracelet(2)"));
		Assert.assertFalse(ConsumableRegistry.isCrumblingJewelry(null));
		Assert.assertFalse(ConsumableRegistry.isCrumblingJewelry(""));
	}
}
