package com.coinflow.reconciliation;

import net.runelite.api.gameval.ItemID;
import org.junit.Assert;
import org.junit.Test;

public class MoonWearTest
{
	@Test
	public void repair_brokenToUsed_isTransition()
	{
		Assert.assertTrue(MoonWear.isRepairOrDegradePair(
			ItemID.BLOOD_MOON_CHESTPLATE_BROKEN, ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED));
		Assert.assertTrue(MoonWear.isRepairOrDegradePair(
			ItemID.ECLIPSE_MOON_HELM_BROKEN, ItemID.ECLIPSE_MOON_HELM_DEGRADED));
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
	public void newVariantGain_isNeverTransition()
	{
		// A repaired piece returns in the used state; the new id only comes
		// from the Lunar Chest, so it must stay loot.
		Assert.assertFalse(MoonWear.isRepairOrDegradePair(
			ItemID.BLOOD_MOON_CHESTPLATE_BROKEN, ItemID.BLOOD_MOON_CHESTPLATE));
		Assert.assertFalse(MoonWear.isRepairOrDegradePair(
			ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED, ItemID.BLOOD_MOON_CHESTPLATE));
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

		Assert.assertTrue(MoonWear.isNonNewVariant(ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED));
		Assert.assertTrue(MoonWear.isNonNewVariant(ItemID.BLOOD_MOON_CHESTPLATE_BROKEN));
		Assert.assertFalse(MoonWear.isNonNewVariant(ItemID.BLOOD_MOON_CHESTPLATE));
		Assert.assertFalse(MoonWear.isNonNewVariant(ItemID.COINS));
	}
}
