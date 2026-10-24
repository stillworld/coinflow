package com.coinflow.reconciliation;

import net.runelite.api.gameval.ItemID;
import org.junit.Assert;
import org.junit.Test;

public class MoonWearTest
{
	@Test
	public void repair_toNewVariant_isTransition()
	{
		// A repair returns the tradeable new variant — that is what makes the
		// repair-flip market possible — so both used->new and broken->new
		// must pair to cancel the item sides of a repair diff.
		Assert.assertTrue(MoonWear.isRepairOrDegradePair(
			ItemID.BLOOD_MOON_CHESTPLATE_BROKEN, ItemID.BLOOD_MOON_CHESTPLATE));
		Assert.assertTrue(MoonWear.isRepairOrDegradePair(
			ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED, ItemID.BLOOD_MOON_CHESTPLATE));
		Assert.assertTrue(MoonWear.isRepairOrDegradePair(
			ItemID.ECLIPSE_MOON_HELM_BROKEN, ItemID.ECLIPSE_MOON_HELM));
	}

	@Test
	public void degrade_newToUsed_and_usedToBroken_areTransitions()
	{
		Assert.assertTrue(MoonWear.isRepairOrDegradePair(
			ItemID.BLOOD_MOON_CHESTPLATE, ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED));
		Assert.assertTrue(MoonWear.isRepairOrDegradePair(
			ItemID.FROST_MOON_TASSETS_DEGRADED, ItemID.FROST_MOON_TASSETS_BROKEN));
	}

	@Test
	public void loneNewVariantGain_stillLoot()
	{
		// Pairing only fires on a same-piece loss + gain in one diff, so a
		// Lunar Chest drop of the new variant is never suppressed.
		Assert.assertFalse(MoonWear.isRepairOrDegradePair(
			ItemID.COINS, ItemID.BLOOD_MOON_CHESTPLATE));
		Assert.assertFalse(MoonWear.isRepairOrDegradePair(
			ItemID.ECLIPSE_MOON_CHESTPLATE, ItemID.BLOOD_MOON_CHESTPLATE));
	}

	@Test
	public void differentPieceOrNonMoonIds_doNotMatch()
	{
		Assert.assertFalse(MoonWear.isRepairOrDegradePair(
			ItemID.BLOOD_MOON_CHESTPLATE_BROKEN, ItemID.ECLIPSE_MOON_CHESTPLATE_DEGRADED));
		Assert.assertFalse(MoonWear.isRepairOrDegradePair(
			ItemID.COINS, ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED));
		Assert.assertFalse(MoonWear.isRepairOrDegradePair(
			ItemID.BLOOD_MOON_CHESTPLATE_BROKEN, ItemID.COINS));
		Assert.assertFalse(MoonWear.isRepairOrDegradePair(
			ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED, ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED));
	}

	@Test
	public void variantDetection()
	{
		Assert.assertTrue(MoonWear.isUsedVariant(ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED));
		Assert.assertFalse(MoonWear.isUsedVariant(ItemID.BLOOD_MOON_CHESTPLATE));
		Assert.assertFalse(MoonWear.isUsedVariant(ItemID.BLOOD_MOON_CHESTPLATE_BROKEN));
		Assert.assertFalse(MoonWear.isUsedVariant(ItemID.COINS));

		Assert.assertTrue(MoonWear.isMoonVariant(ItemID.BLOOD_MOON_CHESTPLATE));
		Assert.assertTrue(MoonWear.isMoonVariant(ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED));
		Assert.assertTrue(MoonWear.isMoonVariant(ItemID.BLOOD_MOON_CHESTPLATE_BROKEN));
		Assert.assertFalse(MoonWear.isMoonVariant(ItemID.TORVA_LEGS));
		Assert.assertFalse(MoonWear.isMoonVariant(ItemID.COINS));
	}
}
