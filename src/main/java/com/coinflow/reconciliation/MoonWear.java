package com.coinflow.reconciliation;

import net.runelite.api.gameval.ItemID;

/**
 * Moons of Peril equipment state tracking. Each piece exists as three item
 * ids — new (tradeable), used (degraded, untradeable), and broken — sharing
 * one display name (the broken form appends " (broken)"). Degradation
 * mutates the id in place: new -> used on first combat use, used -> broken
 * at 0 charges; a repair stand or NPC repair returns the used variant.
 * Individual charges are invisible to inventory diffs, so unlike Barrows no
 * wear can be accrued during combat — the repair fee itself is the expense.
 */
public final class MoonWear
{
	private static final int NEW = 0;
	private static final int USED = 1;
	private static final int BROKEN = 2;

	// [new, used, broken] per piece: Eclipse, Blue (FROST_* constants), Blood.
	private static final int[][] PIECES = {
		{ItemID.ECLIPSE_MOON_HELM, ItemID.ECLIPSE_MOON_HELM_DEGRADED, ItemID.ECLIPSE_MOON_HELM_BROKEN},
		{ItemID.ECLIPSE_MOON_CHESTPLATE, ItemID.ECLIPSE_MOON_CHESTPLATE_DEGRADED, ItemID.ECLIPSE_MOON_CHESTPLATE_BROKEN},
		{ItemID.ECLIPSE_MOON_TASSETS, ItemID.ECLIPSE_MOON_TASSETS_DEGRADED, ItemID.ECLIPSE_MOON_TASSETS_BROKEN},
		{ItemID.FROST_MOON_HELM, ItemID.FROST_MOON_HELM_DEGRADED, ItemID.FROST_MOON_HELM_BROKEN},
		{ItemID.FROST_MOON_CHESTPLATE, ItemID.FROST_MOON_CHESTPLATE_DEGRADED, ItemID.FROST_MOON_CHESTPLATE_BROKEN},
		{ItemID.FROST_MOON_TASSETS, ItemID.FROST_MOON_TASSETS_DEGRADED, ItemID.FROST_MOON_TASSETS_BROKEN},
		{ItemID.BLOOD_MOON_HELM, ItemID.BLOOD_MOON_HELM_DEGRADED, ItemID.BLOOD_MOON_HELM_BROKEN},
		{ItemID.BLOOD_MOON_CHESTPLATE, ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED, ItemID.BLOOD_MOON_CHESTPLATE_BROKEN},
		{ItemID.BLOOD_MOON_TASSETS, ItemID.BLOOD_MOON_TASSETS_DEGRADED, ItemID.BLOOD_MOON_TASSETS_BROKEN},
	};

	private MoonWear() {}

	/** The piece index and state of an id, packed as (index << 2) | state; -1 when not a Moon piece. */
	private static int pack(int id)
	{
		for (int i = 0; i < PIECES.length; i++)
		{
			for (int state = 0; state < PIECES[i].length; state++)
			{
				if (PIECES[i][state] == id)
				{
					return (i << 2) | state;
				}
			}
		}
		return -1;
	}

	private static int stateOf(int id)
	{
		int packed = pack(id);
		return packed < 0 ? -1 : packed & 3;
	}

	/**
	 * An in-place state transition of the same piece: new -> used (first
	 * combat use), used -> broken (0 charges), or broken -> used (repair).
	 * A new-variant gain is never a transition product — the Lunar Chest is
	 * the only source — so same-name loot of that piece always survives.
	 */
	public static boolean isRepairOrDegradePair(int lostId, int gainedId)
	{
		int lost = pack(lostId);
		int gained = pack(gainedId);
		return lost >= 0 && gained >= 0
			&& (lost >> 2) == (gained >> 2)
			&& (gained & 3) != NEW
			&& lost != gained;
	}

	/**
	 * Whether the id is the used (degraded) variant. It can only enter an
	 * inventory via a repair or first use — never as loot — so an unmatched
	 * gain of one is always a conversion artifact.
	 */
	public static boolean isUsedVariant(int id)
	{
		return stateOf(id) == USED;
	}

	/**
	 * Whether the id is a used or broken variant — neither is purchasable,
	 * so coins paid for one are a repair fee, not a purchase.
	 */
	public static boolean isNonNewVariant(int id)
	{
		return stateOf(id) > NEW;
	}
}
