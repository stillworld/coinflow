package com.coinflow;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.VarbitComposition;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * Service responsible for capturing snapshots of item containers
 * and resolving nested container supplies like the Rune Pouch,
 * Looting Bag, Master Scroll Book, and Dizana's Quiver.
 */
@Singleton
public class InventorySnapshotService
{
	private static final int NUM_RUNE_POUCH_SLOTS = 6;
	private static final int[] RUNE_POUCH_AMOUNT_VARBITS = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6
	};
	private static final int[] RUNE_POUCH_TYPE_VARBITS = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6
	};

	private static final int[] SCROLL_BOOK_VARBITS = {
		VarbitID.BOOKOFSCROLLS_NARDAH,
		VarbitID.BOOKOFSCROLLS_DIGSITE,
		VarbitID.BOOKOFSCROLLS_FELDIP,
		VarbitID.BOOKOFSCROLLS_LUNARISLE,
		VarbitID.BOOKOFSCROLLS_MORTTON,
		VarbitID.BOOKOFSCROLLS_PESTCONTROL,
		VarbitID.BOOKOFSCROLLS_PISCATORIS,
		VarbitID.BOOKOFSCROLLS_TAIBWO,
		VarbitID.BOOKOFSCROLLS_ELF,
		VarbitID.BOOKOFSCROLLS_MOSLES,
		VarbitID.BOOKOFSCROLLS_LUMBERYARD,
		VarbitID.BOOKOFSCROLLS_ZULANDRA,
		VarbitID.BOOKOFSCROLLS_CERBERUS,
		VarbitID.BOOKOFSCROLLS_REVENANTS,
		VarbitID.BOOKOFSCROLLS_GUTHIXIAN_TEMPLE,
		VarbitID.BOOKOFSCROLLS_SPIDERCAVE,
		VarbitID.BOOKOFSCROLLS_COLOSSAL_WYRM,
		VarbitID.BOOKOFSCROLLS_ARDEAGLAIS,
		VarbitID.BOOKOFSCROLLS_CHASMOFFIRE
	};

	private static final int[] SCROLL_BOOK_ITEM_IDS = {
		ItemID.TELEPORTSCROLL_NARDAH,
		ItemID.TELEPORTSCROLL_DIGSITE,
		ItemID.TELEPORTSCROLL_FELDIP,
		ItemID.TELEPORTSCROLL_LUNARISLE,
		ItemID.TELEPORTSCROLL_MORTTON,
		ItemID.TELEPORTSCROLL_PESTCONTROL,
		ItemID.TELEPORTSCROLL_PISCATORIS,
		ItemID.TELEPORTSCROLL_TAIBWO,
		ItemID.TELEPORTSCROLL_ELF,
		ItemID.TELEPORTSCROLL_MOSLES,
		ItemID.TELEPORTSCROLL_LUMBERYARD,
		ItemID.TELEPORTSCROLL_ZULANDRA,
		ItemID.TELEPORTSCROLL_CERBERUS,
		ItemID.TELEPORTSCROLL_REVENANTS,
		ItemID.TELEPORTSCROLL_GUTHIXIAN_TEMPLE,
		ItemID.TELEPORTSCROLL_SPIDERCAVE,
		ItemID.TELEPORTSCROLL_COLOSSAL_WYRM,
		ItemID.TELEPORTSCROLL_ARDEAGLAIS,
		ItemID.TELEPORTSCROLL_CHASMOFFIRE
	};

	private Client client;

	@Inject
	public InventorySnapshotService(Client client)
	{
		this.client = client;
	}

	public InventorySnapshotService()
	{
		this(null);
	}

	public Client getClient()
	{
		return client;
	}

	public void setClient(Client client)
	{
		this.client = client;
	}

	/**
	 * Converts an ItemContainer into a basic InventorySnapshot.
	 */
	public InventorySnapshot takeSnapshot(ItemContainer container)
	{
		if (container == null)
		{
			return InventorySnapshot.empty();
		}

		Item[] items = container.getItems();
		if (items == null)
		{
			return InventorySnapshot.empty();
		}

		int[] ids = new int[items.length];
		int[] qtys = new int[items.length];

		for (int i = 0; i < items.length; i++)
		{
			Item item = items[i];
			if (item != null)
			{
				int id = item.getId();
				if (id == ItemID.FISH_SACK_BARREL_OPEN)
				{
					id = ItemID.FISH_SACK_BARREL_CLOSED;
				}
				ids[i] = id;
				qtys[i] = item.getQuantity();
			}
			else
			{
				ids[i] = -1;
				qtys[i] = 0;
			}
		}

		return InventorySnapshot.fromArrays(ids, qtys);
	}

	/**
	 * Converts an inventory ItemContainer into an InventorySnapshot,
	 * incorporating any runes currently stored inside a carried Rune Pouch.
	 */
	public InventorySnapshot takeInventorySnapshot(ItemContainer invContainer)
	{
		if (invContainer == null)
		{
			return InventorySnapshot.empty();
		}

		Item[] items = invContainer.getItems();
		if (items == null)
		{
			return InventorySnapshot.empty();
		}

		Map<Integer, Integer> map = new HashMap<>();
		boolean hasPouch = false;
		boolean hasBag = false;
		boolean hasScrollBook = false;
		boolean hasQuiver = false;
		boolean hasSanctifier = false;

		for (Item item : items)
		{
			if (item == null)
			{
				continue;
			}
			int id = item.getId();
			int qty = item.getQuantity();
			if (id > 0 && qty > 0)
			{
				if (id == ItemID.BH_RUNE_POUCH || id == ItemID.BH_RUNE_POUCH_TROUVER
					|| id == ItemID.DIVINE_RUNE_POUCH || id == ItemID.DIVINE_RUNE_POUCH_TROUVER)
				{
					hasPouch = true;
				}
				if (isLootingBag(id))
				{
					hasBag = true;
					id = ItemID.LOOTING_BAG;
				}
				if (isMasterScrollBook(id))
				{
					hasScrollBook = true;
					id = ItemID.BOOKOFSCROLLS_CHARGED;
				}
				if (isDizanasQuiver(id))
				{
					hasQuiver = true;
					id = ItemID.DIZANAS_QUIVER_CHARGED;
				}
				if (isGemBag(id))
				{
					id = ItemID.GEM_BAG;
				}
				else if (isGemPouch(id))
				{
					id = ItemID.GEM_POUCH;
				}
				else if (isGemSatchel(id))
				{
					id = ItemID.GEM_SATCHEL;
				}
				else if (isGemTote(id))
				{
					id = ItemID.GEM_TOTE;
				}
				else if (isGemSack(id))
				{
					id = ItemID.GEM_SACK;
				}
				else if (isOpenHerbSack(id))
				{
					id = (id == ItemID.SLAYER_HERB_SACK_SILK_OPEN) ? ItemID.SLAYER_HERB_SACK_SILK : ItemID.SLAYER_HERB_SACK;
				}
				else if (id == ItemID.FISH_BARREL_OPEN)
				{
					id = ItemID.FISH_BARREL_CLOSED;
				}
				else if (id == ItemID.FISH_SACK_BARREL_OPEN)
				{
					id = ItemID.FISH_SACK_BARREL_CLOSED;
				}
				else if (id == ItemID.SEED_BOX_OPEN)
				{
					id = ItemID.SEED_BOX;
				}
				else if (isAshSanctifier(id))
				{
					hasSanctifier = true;
				}
				map.merge(id, qty, Integer::sum);
			}
		}

		if (hasPouch)
		{
			Map<Integer, Integer> pouchRunes = getRunePouchContents();
			for (Map.Entry<Integer, Integer> entry : pouchRunes.entrySet())
			{
				map.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
		}

		if (hasBag)
		{
			Map<Integer, Integer> bagItems = getLootingBagContents();
			for (Map.Entry<Integer, Integer> entry : bagItems.entrySet())
			{
				map.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
		}

		if (hasScrollBook)
		{
			Map<Integer, Integer> scrollBookScrolls = getMasterScrollBookContents();
			for (Map.Entry<Integer, Integer> entry : scrollBookScrolls.entrySet())
			{
				map.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
		}

		if (!hasQuiver && client != null)
		{
			ItemContainer equipContainer = client.getItemContainer(InventoryID.WORN);
			if (equipContainer != null && hasDizanasQuiver(equipContainer))
			{
				hasQuiver = true;
			}
		}

		if (hasSanctifier && client != null)
		{
			try
			{
				int deathRunes = client.getVarbitValue(VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY);
				if (deathRunes > 0)
				{
					map.merge(ItemID.DEATHRUNE, deathRunes, Integer::sum);
				}
			}
			catch (Exception ignored) {}
		}

		if (hasQuiver)
		{
			Map<Integer, Integer> quiverAmmo = getQuiverAmmoContents();
			for (Map.Entry<Integer, Integer> entry : quiverAmmo.entrySet())
			{
				map.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
		}

		return InventorySnapshot.fromMap(map);
	}

	/**
	 * Returns true if the item ID matches any variant of the Looting Bag.
	 */
	public static boolean isLootingBag(int itemId)
	{
		return itemId == ItemID.LOOTING_BAG || itemId == ItemID.LOOTING_BAG_OPEN
			|| itemId == 18274 || itemId == 22587;
	}

	/**
	 * Checks if the inventory container holds any variant of the Looting Bag.
	 */
	public boolean hasLootingBag(ItemContainer invContainer)
	{
		if (invContainer == null)
		{
			return false;
		}

		Item[] items = invContainer.getItems();
		if (items == null)
		{
			return false;
		}

		for (Item item : items)
		{
			if (item != null && isLootingBag(item.getId()))
			{
				return true;
			}
		}

		return false;
	}

	private final Map<Integer, Integer> cachedLootingBagContents = new HashMap<>();

	/**
	 * Returns true if the item ID matches an Open Looting Bag variant.
	 */
	public static boolean isOpenLootingBag(int itemId)
	{
		return itemId == ItemID.LOOTING_BAG_OPEN || itemId == 22587;
	}

	/**
	 * Checks if the inventory container holds an open variant of the Looting Bag.
	 */
	public boolean hasOpenLootingBag(ItemContainer invContainer)
	{
		if (invContainer == null)
		{
			return false;
		}

		Item[] items = invContainer.getItems();
		if (items == null)
		{
			return false;
		}

		for (Item item : items)
		{
			if (item != null && isOpenLootingBag(item.getId()))
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Adds an item into the cached looting bag contents (e.g. ground loot pickup into open bag).
	 */
	public synchronized void addLootingBagPendingItem(int itemId, int quantity)
	{
		if (itemId > 0 && quantity > 0)
		{
			cachedLootingBagContents.merge(itemId, quantity, Integer::sum);
		}
	}

	/**
	 * Updates the cached looting bag contents with server-authoritative items from container 516.
	 */
	public synchronized void setLootingBagContents(Map<Integer, Integer> items)
	{
		cachedLootingBagContents.clear();
		if (items != null)
		{
			for (Map.Entry<Integer, Integer> entry : items.entrySet())
			{
				if (entry.getKey() != null && entry.getKey() > 0 && entry.getValue() != null && entry.getValue() > 0)
				{
					cachedLootingBagContents.put(entry.getKey(), entry.getValue());
				}
			}
		}
	}

	/**
	 * Clears the cached looting bag contents (e.g. on death, session reset, bank deposit).
	 */
	public synchronized void clearLootingBagPendingItems()
	{
		cachedLootingBagContents.clear();
	}

	/**
	 * Returns a copy of the current cached looting bag contents.
	 */
	public synchronized Map<Integer, Integer> getLootingBagPendingItems()
	{
		return new HashMap<>(cachedLootingBagContents);
	}

	/**
	 * Reads the current items and quantities stored inside the Looting Bag.
	 * Persists across interface closure since container 516 is only present while checking.
	 */
	public synchronized Map<Integer, Integer> getLootingBagContents()
	{
		return new HashMap<>(cachedLootingBagContents);
	}

	/**
	 * Checks if the inventory container holds any variant of the Rune Pouch.
	 */
	public boolean hasRunePouch(ItemContainer invContainer)
	{
		if (invContainer == null)
		{
			return false;
		}

		Item[] items = invContainer.getItems();
		if (items == null)
		{
			return false;
		}

		for (Item item : items)
		{
			if (item != null)
			{
				int id = item.getId();
				if (id == ItemID.BH_RUNE_POUCH || id == ItemID.BH_RUNE_POUCH_TROUVER
					|| id == ItemID.DIVINE_RUNE_POUCH || id == ItemID.DIVINE_RUNE_POUCH_TROUVER)
				{
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * Reads the current runes and quantities stored inside the Rune Pouch via client varbits.
	 */
	public Map<Integer, Integer> getRunePouchContents()
	{
		if (client == null)
		{
			return Collections.emptyMap();
		}

		EnumComposition runepouchEnum = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		if (runepouchEnum == null)
		{
			return Collections.emptyMap();
		}

		Map<Integer, Integer> runes = new HashMap<>();
		for (int i = 0; i < NUM_RUNE_POUCH_SLOTS; i++)
		{
			int runeType = client.getVarbitValue(RUNE_POUCH_TYPE_VARBITS[i]);
			int amount = client.getVarbitValue(RUNE_POUCH_AMOUNT_VARBITS[i]);
			if (runeType > 0 && amount > 0)
			{
				int runeId = runepouchEnum.getIntValue(runeType);
				if (runeId > 0)
				{
					runes.merge(runeId, amount, Integer::sum);
				}
			}
		}

		return runes;
	}

	/**
	 * Checks if the specified varbit ID corresponds to a Rune Pouch slot quantity or type.
	 */
	public boolean isRunePouchVarbit(int varbitId)
	{
		for (int i = 0; i < NUM_RUNE_POUCH_SLOTS; i++)
		{
			if (varbitId == RUNE_POUCH_AMOUNT_VARBITS[i] || varbitId == RUNE_POUCH_TYPE_VARBITS[i])
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true if the item ID matches any variant of the Gem Bag.
	 */
	public static boolean isGemBag(int itemId)
	{
		return itemId == ItemID.GEM_BAG || itemId == ItemID.GEM_BAG_OPEN;
	}

	/**
	 * Returns true if the item ID matches any variant of the Gem Pouch.
	 */
	public static boolean isGemPouch(int itemId)
	{
		return itemId == ItemID.GEM_POUCH || itemId == ItemID.GEM_POUCH_OPEN;
	}

	/**
	 * Returns true if the item ID matches any variant of the Gem Satchel.
	 */
	public static boolean isGemSatchel(int itemId)
	{
		return itemId == ItemID.GEM_SATCHEL || itemId == ItemID.GEM_SATCHEL_OPEN;
	}

	/**
	 * Returns true if the item ID matches any variant of the Gem Tote.
	 */
	public static boolean isGemTote(int itemId)
	{
		return itemId == ItemID.GEM_TOTE || itemId == ItemID.GEM_TOTE_OPEN;
	}

	/**
	 * Returns true if the item ID matches any variant of the Gem Sack.
	 */
	public static boolean isGemSack(int itemId)
	{
		return itemId == ItemID.GEM_SACK || itemId == ItemID.GEM_SACK_OPEN;
	}

	/**
	 * Returns true if the item ID matches any gem container variant (bag, pouch, satchel, tote, sack).
	 */
	public static boolean isAnyGemContainer(int itemId)
	{
		return isGemBag(itemId) || isGemPouch(itemId) || isGemSatchel(itemId)
			|| isGemTote(itemId) || isGemSack(itemId);
	}

	/**
	 * Returns true if the item ID matches any open gem container variant.
	 */
	public static boolean isOpenGemBag(int itemId)
	{
		return itemId == ItemID.GEM_BAG_OPEN || itemId == ItemID.GEM_POUCH_OPEN
			|| itemId == ItemID.GEM_SATCHEL_OPEN || itemId == ItemID.GEM_TOTE_OPEN
			|| itemId == ItemID.GEM_SACK_OPEN;
	}

	/**
	 * Returns true if the item ID is an uncut gem that can be stored in a gem bag/sack.
	 */
	public static boolean isUncutGem(int itemId)
	{
		return itemId == ItemID.UNCUT_SAPPHIRE
			|| itemId == ItemID.UNCUT_EMERALD
			|| itemId == ItemID.UNCUT_RUBY
			|| itemId == ItemID.UNCUT_DIAMOND
			|| itemId == ItemID.UNCUT_DRAGONSTONE
			|| itemId == ItemID.UNCUT_OPAL
			|| itemId == ItemID.UNCUT_JADE
			|| itemId == ItemID.UNCUT_RED_TOPAZ;
	}

	/**
	 * Checks if the inventory container holds any variant of a gem container.
	 */
	public boolean hasGemBag(ItemContainer invContainer)
	{
		if (invContainer == null)
		{
			return false;
		}

		Item[] items = invContainer.getItems();
		if (items == null)
		{
			return false;
		}

		for (Item item : items)
		{
			if (item != null && isAnyGemContainer(item.getId()))
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Checks if the inventory container holds an open variant of any gem container.
	 */
	public boolean hasOpenGemBag(ItemContainer invContainer)
	{
		if (invContainer == null)
		{
			return false;
		}

		Item[] items = invContainer.getItems();
		if (items == null)
		{
			return false;
		}

		for (Item item : items)
		{
			if (item != null && isOpenGemBag(item.getId()))
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Returns true if the item ID matches any variant of the Herb Sack.
	 */
	public static boolean isHerbSack(int itemId)
	{
		return itemId == ItemID.SLAYER_HERB_SACK || itemId == ItemID.SLAYER_HERB_SACK_OPEN
			|| itemId == ItemID.SLAYER_HERB_SACK_SILK || itemId == ItemID.SLAYER_HERB_SACK_SILK_OPEN;
	}

	/**
	 * Returns true if the item ID matches any open variant of the Herb Sack.
	 */
	public static boolean isOpenHerbSack(int itemId)
	{
		return itemId == ItemID.SLAYER_HERB_SACK_OPEN || itemId == ItemID.SLAYER_HERB_SACK_SILK_OPEN;
	}

	/**
	 * Checks if the inventory container holds any variant of the Herb Sack.
	 */
	public boolean hasHerbSack(ItemContainer invContainer)
	{
		if (invContainer == null || invContainer.getItems() == null)
		{
			return false;
		}
		for (Item item : invContainer.getItems())
		{
			if (item != null && isHerbSack(item.getId()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks if the inventory container holds an open variant of the Herb Sack.
	 */
	public boolean hasOpenHerbSack(ItemContainer invContainer)
	{
		if (invContainer == null || invContainer.getItems() == null)
		{
			return false;
		}
		for (Item item : invContainer.getItems())
		{
			if (item != null && isOpenHerbSack(item.getId()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true if the item ID is a grimy herb that can be stored in an herb sack.
	 */
	public static boolean isGrimyHerb(int itemId)
	{
		return itemId == ItemID.UNIDENTIFIED_GUAM
			|| itemId == ItemID.UNIDENTIFIED_MARENTILL
			|| itemId == ItemID.UNIDENTIFIED_TARROMIN
			|| itemId == ItemID.UNIDENTIFIED_HARRALANDER
			|| itemId == ItemID.UNIDENTIFIED_RANARR
			|| itemId == ItemID.UNIDENTIFIED_TOADFLAX
			|| itemId == ItemID.UNIDENTIFIED_IRIT
			|| itemId == ItemID.UNIDENTIFIED_AVANTOE
			|| itemId == ItemID.UNIDENTIFIED_KWUARM
			|| itemId == ItemID.UNIDENTIFIED_SNAPDRAGON
			|| itemId == ItemID.UNIDENTIFIED_CADANTINE
			|| itemId == ItemID.UNIDENTIFIED_LANTADYME
			|| itemId == ItemID.UNIDENTIFIED_DWARF_WEED
			|| itemId == ItemID.UNIDENTIFIED_TORSTOL;
	}

	/**
	 * Returns true if the item ID matches any variant of the Fish Barrel.
	 */
	public static boolean isFishBarrel(int itemId)
	{
		return itemId == ItemID.FISH_BARREL_CLOSED || itemId == ItemID.FISH_BARREL_OPEN
			|| itemId == ItemID.FISH_SACK_BARREL_CLOSED || itemId == ItemID.FISH_SACK_BARREL_OPEN;
	}

	/**
	 * Returns true if the item ID matches an open Fish Barrel variant.
	 */
	public static boolean isOpenFishBarrel(int itemId)
	{
		return itemId == ItemID.FISH_BARREL_OPEN || itemId == ItemID.FISH_SACK_BARREL_OPEN;
	}

	/**
	 * Checks if inventory or worn equipment holds any variant of the Fish Barrel.
	 */
	public boolean hasFishBarrel(ItemContainer invContainer, ItemContainer wornContainer)
	{
		if (invContainer != null && invContainer.getItems() != null)
		{
			for (Item item : invContainer.getItems())
			{
				if (item != null && isFishBarrel(item.getId()))
				{
					return true;
				}
			}
		}
		if (wornContainer != null && wornContainer.getItems() != null)
		{
			for (Item item : wornContainer.getItems())
			{
				if (item != null && isFishBarrel(item.getId()))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Checks if inventory or worn equipment holds an open variant of the Fish Barrel.
	 */
	public boolean hasOpenFishBarrel(ItemContainer invContainer, ItemContainer wornContainer)
	{
		if (invContainer != null && invContainer.getItems() != null)
		{
			for (Item item : invContainer.getItems())
			{
				if (item != null && isOpenFishBarrel(item.getId()))
				{
					return true;
				}
			}
		}
		if (wornContainer != null && wornContainer.getItems() != null)
		{
			for (Item item : wornContainer.getItems())
			{
				if (item != null && isOpenFishBarrel(item.getId()))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns true if the item ID matches any variant of the Seed Box.
	 */
	public static boolean isSeedBox(int itemId)
	{
		return itemId == ItemID.SEED_BOX || itemId == ItemID.SEED_BOX_OPEN;
	}

	/**
	 * Returns true if the item ID matches an open variant of the Seed Box.
	 */
	public static boolean isOpenSeedBox(int itemId)
	{
		return itemId == ItemID.SEED_BOX_OPEN;
	}

	/**
	 * Checks if the inventory container holds any variant of the Seed Box.
	 */
	public boolean hasSeedBox(ItemContainer invContainer)
	{
		if (invContainer == null || invContainer.getItems() == null)
		{
			return false;
		}
		for (Item item : invContainer.getItems())
		{
			if (item != null && isSeedBox(item.getId()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks if the inventory container holds an open variant of the Seed Box.
	 */
	public boolean hasOpenSeedBox(ItemContainer invContainer)
	{
		if (invContainer == null || invContainer.getItems() == null)
		{
			return false;
		}
		for (Item item : invContainer.getItems())
		{
			if (item != null && isOpenSeedBox(item.getId()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true if the item ID matches the Ash Sanctifier.
	 */
	public static boolean isAshSanctifier(int itemId)
	{
		return itemId == ItemID.ASH_SANCTIFIER;
	}

	/**
	 * Checks if the inventory container holds the Ash Sanctifier.
	 */
	public boolean hasAshSanctifier(ItemContainer invContainer)
	{
		if (invContainer == null || invContainer.getItems() == null)
		{
			return false;
		}
		for (Item item : invContainer.getItems())
		{
			if (item != null && isAshSanctifier(item.getId()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true if the item ID matches any variant of the Bonecrusher.
	 */
	public static boolean isBonecrusher(int itemId)
	{
		return itemId == ItemID.BONECRUSHER || itemId == ItemID.BONECRUSHER_NECKLACE;
	}

	/**
	 * Checks if inventory or worn equipment holds any variant of the Bonecrusher.
	 */
	public boolean hasBonecrusher(ItemContainer invContainer, ItemContainer wornContainer)
	{
		if (invContainer != null && invContainer.getItems() != null)
		{
			for (Item item : invContainer.getItems())
			{
				if (item != null && isBonecrusher(item.getId()))
				{
					return true;
				}
			}
		}
		if (wornContainer != null && wornContainer.getItems() != null)
		{
			for (Item item : wornContainer.getItems())
			{
				if (item != null && isBonecrusher(item.getId()))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Checks if the item ID corresponds to a Master Scroll Book.
	 */
	public static boolean isMasterScrollBook(int itemId)
	{
		return itemId == ItemID.BOOKOFSCROLLS_CHARGED || itemId == ItemID.BOOKOFSCROLLS_EMPTY;
	}

	/**
	 * Checks if the inventory container holds any variant of a Master Scroll Book.
	 */
	public boolean hasMasterScrollBook(ItemContainer invContainer)
	{
		if (invContainer == null)
		{
			return false;
		}

		Item[] items = invContainer.getItems();
		if (items == null)
		{
			return false;
		}

		for (Item item : items)
		{
			if (item != null && isMasterScrollBook(item.getId()))
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Checks if the specified varbit ID corresponds to any Master Scroll Book scroll count.
	 */
	public static boolean isMasterScrollBookVarbit(int varbitId)
	{
		if (varbitId == VarbitID.BOOKOFSCROLLS_WATSON_LOWBITS || varbitId == VarbitID.BOOKOFSCROLLS_WATSON_HIGHBITS)
		{
			return true;
		}
		for (int v : SCROLL_BOOK_VARBITS)
		{
			if (v == varbitId)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Reads the current teleport scrolls and quantities stored inside the Master Scroll Book via client varbits.
	 */
	public Map<Integer, Integer> getMasterScrollBookContents()
	{
		if (client == null)
		{
			return Collections.emptyMap();
		}

		Map<Integer, Integer> scrolls = new HashMap<>();
		for (int i = 0; i < SCROLL_BOOK_VARBITS.length; i++)
		{
			int count = client.getVarbitValue(SCROLL_BOOK_VARBITS[i]);
			if (count > 0)
			{
				scrolls.put(SCROLL_BOOK_ITEM_IDS[i], count);
			}
		}

		// Watson teleport scroll count (split across low and high bits)
		int low = 0;
		int high = 0;
		try
		{
			low = client.getVarbitValue(VarbitID.BOOKOFSCROLLS_WATSON_LOWBITS);
			high = client.getVarbitValue(VarbitID.BOOKOFSCROLLS_WATSON_HIGHBITS);
		}
		catch (Exception ignored) {}

		if (low > 0 || high > 0)
		{
			int bitWidth = 16;
			try
			{
				VarbitComposition comp = client.getVarbit(VarbitID.BOOKOFSCROLLS_WATSON_LOWBITS);
				if (comp != null)
				{
					bitWidth = comp.getMostSignificantBit() - comp.getLeastSignificantBit() + 1;
				}
			}
			catch (Exception ignored) {}

			int watsonCount = (high << bitWidth) | low;
			if (watsonCount > 1000)
			{
				watsonCount = Math.max(low, high);
			}
			if (watsonCount > 0)
			{
				scrolls.put(ItemID.TELEPORTSCROLL_WATSON, watsonCount);
			}
		}

		return scrolls;
	}

	/**
	 * Returns true if the item ID matches any variant of Dizana's Quiver.
	 */
	public static boolean isDizanasQuiver(int itemId)
	{
		return itemId == ItemID.DIZANAS_QUIVER_UNCHARGED
			|| itemId == ItemID.DIZANAS_QUIVER_UNCHARGED_TROUVER
			|| itemId == ItemID.DIZANAS_QUIVER_CHARGED
			|| itemId == ItemID.DIZANAS_QUIVER_CHARGED_TROUVER
			|| itemId == ItemID.DIZANAS_QUIVER_INFINITE
			|| itemId == ItemID.DIZANAS_QUIVER_INFINITE_TROUVER
			|| itemId == ItemID.DIZANAS_QUIVER_BROKEN
			|| itemId == ItemID.DIZANAS_QUIVER_INFINITE_BROKEN
			|| itemId == ItemID.DIZANAS_QUIVER_TROUVER_BROKEN
			|| itemId == ItemID.DIZANAS_QUIVER_TROUVER_MANGLED
			|| itemId == ItemID.DIZANAS_QUIVER_INFINITE_TROUVER_BROKEN
			|| itemId == ItemID.DIZANAS_QUIVER_INFINITE_TROUVER_MANGLED
			|| itemId == ItemID.SKILLCAPE_MAX_DIZANAS
			|| itemId == ItemID.SKILLCAPE_MAX_DIZANAS_TROUVER
			|| itemId == ItemID.SKILLCAPE_MAX_DIZANAS_BROKEN
			|| itemId == ItemID.SKILLCAPE_MAX_DIZANAS_TROUVER_BROKEN
			|| itemId == ItemID.SKILLCAPE_MAX_DIZANAS_TROUVER_MANGLED;
	}

	/**
	 * Checks if the container holds any variant of Dizana's Quiver.
	 */
	public boolean hasDizanasQuiver(ItemContainer container)
	{
		if (container == null)
		{
			return false;
		}

		Item[] items = container.getItems();
		if (items == null)
		{
			return false;
		}

		for (Item item : items)
		{
			if (item != null && isDizanasQuiver(item.getId()))
			{
				return true;
			}
		}

		return false;
	}

	private final Map<Integer, Integer> cachedQuiverAmmoContents = new HashMap<>();

	/**
	 * Updates the cached Dizana's Quiver ammo contents.
	 */
	public synchronized void setQuiverAmmoContents(Map<Integer, Integer> items)
	{
		cachedQuiverAmmoContents.clear();
		if (items != null)
		{
			for (Map.Entry<Integer, Integer> entry : items.entrySet())
			{
				if (entry.getKey() != null && entry.getKey() > 0 && entry.getValue() != null && entry.getValue() > 0)
				{
					cachedQuiverAmmoContents.put(entry.getKey(), entry.getValue());
				}
			}
		}
	}

	/**
	 * Clears the cached Dizana's Quiver ammo contents (e.g. on death, session reset).
	 */
	public synchronized void clearQuiverAmmoContents()
	{
		cachedQuiverAmmoContents.clear();
	}

	/**
	 * Reads the current ammo and quantities stored inside Dizana's Quiver (container 879).
	 */
	public synchronized Map<Integer, Integer> getQuiverAmmoContents()
	{
		if (client != null)
		{
			ItemContainer container = client.getItemContainer(InventoryID.DIZANAS_QUIVER_AMMO);
			if (container != null && container.getItems() != null)
			{
				Map<Integer, Integer> map = new HashMap<>();
				for (Item item : container.getItems())
				{
					if (item != null && item.getId() > 0 && item.getQuantity() > 0)
					{
						map.merge(item.getId(), item.getQuantity(), Integer::sum);
					}
				}
				cachedQuiverAmmoContents.clear();
				cachedQuiverAmmoContents.putAll(map);
				return map;
			}
		}
		return new HashMap<>(cachedQuiverAmmoContents);
	}
}
