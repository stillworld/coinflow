package com.coinflow;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * Shared item valuation: GE market price, then the fixed coin/platinum
 * values, then high alchemy as an estimate for untradeables.
 *
 * The HA fallback is skipped for {@link #UNALCHABLE_UNTRADEABLES}: every item
 * carries a nominal HA price in its composition even when it cannot be
 * alchemised, so untradeable items that also cannot be alched (or sold) have
 * no way to realize that value and must price at 0.
 */
public final class ItemPricing
{
	private ItemPricing() {}

	// Untradeable items that also cannot be alchemised — the HA fallback would
	// book phantom profit/spend. Canonical item ids only; only add entries
	// verified unalchable — wrongly listing an alchable item loses its real
	// value twice (0 at pickup, ~0 alch margin since the basis self-references).
	static final Set<Integer> UNALCHABLE_UNTRADEABLES = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
		// Blessed bone shards (Cam Torum calcified rocks / libation bowl) —
		// untradeable, unalchable; nominal HA price 10 gp is unrealizable.
		ItemID.BLESSED_BONE_SHARD,
		// Blessed bone statuettes (eagle/fox/buffalo, stealing valuables) —
		// untradeable, unalchable, nominal HA ~1,200 gp each.
		ItemID.VARLAMORE_BONE_STATUETTE01,
		ItemID.VARLAMORE_BONE_STATUETTE02,
		ItemID.VARLAMORE_BONE_STATUETTE03
	)));

	/**
	 * Whether the item can never carry value in any context — untradeable and
	 * unalchable. Such diffs are stripped before reconciliation (invisible to
	 * tracking, like minigame-only items).
	 */
	static boolean isValueless(int canonicalId)
	{
		return UNALCHABLE_UNTRADEABLES.contains(canonicalId);
	}

	/**
	 * Whether the item is untradeable and carries no market price — the
	 * population the HA fallback would otherwise value. Stripped from diffs
	 * when the Track Untradeable Items config is off; the value still surfaces
	 * if the item is later converted to cash (e.g. alched: the item loss is
	 * invisible, so the coins land as plain income).
	 */
	static boolean isUnpricedUntradeable(int canonicalId, ItemManager itemManager)
	{
		if (itemManager == null || itemManager.getItemPrice(canonicalId) > 0)
		{
			return false;
		}
		ItemComposition comp = itemManager.getItemComposition(canonicalId);
		return comp != null && !comp.isTradeable();
	}

	/**
	 * Realizable value of one item: GE price, fixed coin/platinum values, then
	 * high alchemy — or 0 for items that can never be converted to cash.
	 */
	public static long priceOf(int itemId, ItemManager itemManager)
	{
		if (itemId == ItemID.COINS)
		{
			return 1;
		}
		if (itemId == ItemID.PLATINUM)
		{
			return 1000;
		}
		if (itemManager == null)
		{
			return 0;
		}
		long price = itemManager.getItemPrice(itemId);
		if (price > 0)
		{
			return price;
		}
		if (UNALCHABLE_UNTRADEABLES.contains(itemManager.canonicalize(itemId)))
		{
			return 0;
		}
		ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp != null ? comp.getHaPrice() : 0;
	}
}
