package com.coinflow;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

/**
 * Grand Exchange sell tax. {@code GrandExchangeOffer.getSpent()} reports gross
 * coins before tax, so net proceeds must be derived: 2% per item, rounded down,
 * capped at 5,000,000 coins per item, with a set of exempt items.
 */
final class GrandExchangeTax
{
	static final double RATE = 0.02;
	static final long CAP_PER_ITEM = 5_000_000L;

	/**
	 * Sentinel key for the GE tax expense entry in the session breakdown. Uses a
	 * real coins-pile sprite id so the panel renders an icon; it never collides
	 * with actual inventory items or the generic "Coins" expense row.
	 */
	static final int TAX_ITEM_ID = ItemID.COINS_10000;
	static final String TAX_ITEM_NAME = "GE Tax";

	static final Set<Integer> EXEMPT_ITEMS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		ItemID.CHISEL,
		ItemID.GARDENING_TROWEL,
		ItemID.HAMMER,
		ItemID.NEEDLE,
		ItemID.OSRS_BOND,
		ItemID.PESTLE_AND_MORTAR,
		ItemID.RAKE,
		ItemID.POH_SAW,
		ItemID.SECATEURS,
		ItemID.DIBBER,
		ItemID.SHEARS,
		ItemID.SPADE,
		ItemID.WATERING_CAN_0,
		ItemID.POH_TABLET_ARDOUGNETELEPORT,
		ItemID.BASS,
		ItemID.BREAD,
		ItemID.BRONZE_ARROW,
		ItemID.BRONZE_DART,
		ItemID.CAKE,
		ItemID.POH_TABLET_CAMELOTTELEPORT,
		ItemID.POH_TABLET_FORTISTELEPORT,
		ItemID.COOKED_CHICKEN,
		ItemID.COOKED_MEAT,
		ItemID._4DOSE1ENERGY,
		ItemID._3DOSE1ENERGY,
		ItemID._2DOSE1ENERGY,
		ItemID._1DOSE1ENERGY,
		ItemID.POH_TABLET_FALADORTELEPORT,
		ItemID.NECKLACE_OF_MINIGAMES_8,
		ItemID.HERRING,
		ItemID.IRON_ARROW,
		ItemID.IRON_DART,
		ItemID.POH_TABLET_KOURENDTELEPORT,
		ItemID.LOBSTER,
		ItemID.POH_TABLET_LUMBRIDGETELEPORT,
		ItemID.MACKEREL,
		ItemID.MEAT_PIE,
		ItemID.MINDRUNE,
		ItemID.PIKE,
		ItemID.RING_OF_DUELING_8,
		ItemID.SALMON,
		ItemID.SHRIMP,
		ItemID.STEEL_ARROW,
		ItemID.STEEL_DART,
		ItemID.POH_TABLET_TELEPORTTOHOUSE,
		ItemID.TUNA,
		ItemID.POH_TABLET_VARROCKTELEPORT
	)));

	private GrandExchangeTax() {}

	/**
	 * Tax charged on one item sold at the given price.
	 */
	static long taxPerItem(int itemId, long priceEach)
	{
		if (priceEach <= 0 || EXEMPT_ITEMS.contains(itemId))
		{
			return 0L;
		}
		return Math.min((long) Math.floor(priceEach * RATE), CAP_PER_ITEM);
	}

	/**
	 * Net coins received for a sell fill of {@code quantity} items with
	 * {@code grossProceeds} total pre-tax coins.
	 */
	static long netProceeds(int itemId, int quantity, long grossProceeds)
	{
		if (quantity <= 0 || grossProceeds <= 0)
		{
			return 0L;
		}
		long priceEach = grossProceeds / quantity;
		return grossProceeds - taxPerItem(itemId, priceEach) * quantity;
	}
}
