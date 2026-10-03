package com.coinflow;

import java.util.Map;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Assert;
import org.junit.Test;

public class WeaponChargeTrackerTest
{
	private static void seed(WeaponChargeTracker tracker, int varbitId, int initialCharges)
	{
		tracker.onVarbitChanged(varbitId, initialCharges, false, 0);
	}

	// ---- Attack-triggered weapons ----

	@Test
	public void tridentCast_recordsRunesAndCoins()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();

		tracker.onAttackGraphic(1251, "Trident of the seas", 1);

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(1, spent.get(ItemID.CHAOSRUNE).intValue());
		Assert.assertEquals(1, spent.get(ItemID.DEATHRUNE).intValue());
		Assert.assertEquals(5, spent.get(ItemID.FIRERUNE).intValue());
		Assert.assertEquals(10, spent.get(ItemID.COINS).intValue());
	}

	@Test
	public void swampTridentCast_consumesZulrahsScaleNotCoins()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();

		tracker.onAttackGraphic(665, "Trident of the swamp", 1);

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(1, spent.get(ItemID.CHAOSRUNE).intValue());
		Assert.assertEquals(1, spent.get(ItemID.DEATHRUNE).intValue());
		Assert.assertEquals(5, spent.get(ItemID.FIRERUNE).intValue());
		Assert.assertEquals(1, spent.get(ItemID.SNAKEBOSS_SCALE).intValue());
		Assert.assertNull(spent.get(ItemID.COINS));
	}

	@Test
	public void seasGraphic_doesNotTriggerForSwamp()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();

		tracker.onAttackGraphic(1251, "Trident of the swamp", 1);

		Assert.assertTrue(tracker.drain().isEmpty());
	}

	@Test
	public void shadowCast_recordsChaosAndSoul()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();

		tracker.onAttackGraphic(2125, "Tumeken's shadow", 1);

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(5, spent.get(ItemID.CHAOSRUNE).intValue());
		Assert.assertEquals(20, spent.get(ItemID.SOULRUNE).intValue());
	}

	@Test
	public void sanguinestiCast_recordsBloodRunesViaCastAnimation()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();

		tracker.onAttackAnimation(AnimationID.HUMAN_CASTWAVE_STAFF, "Sanguinesti staff", 1);

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(3, spent.get(ItemID.BLOODRUNE).intValue());
	}

	@Test
	public void crawsBowShot_recordsEtherViaBowAnimation()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();

		tracker.onAttackAnimation(AnimationID.HUMAN_BOW, "Craw's bow", 1);

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(1, spent.get(ItemID.WILD_CAVE_SHARD).intValue());
	}

	@Test
	public void bowAnimationWithUnrelatedWeapon_recordsNothing()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();

		tracker.onAttackAnimation(AnimationID.HUMAN_BOW, "Magic shortbow", 1);

		Assert.assertTrue(tracker.drain().isEmpty());
	}

	@Test
	public void attackGraphicWithNoWeapon_recordsNothing()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();

		tracker.onAttackGraphic(1251, null, 1);
		tracker.onAttackGraphic(1251, "", 1);

		Assert.assertTrue(tracker.drain().isEmpty());
	}

	@Test
	public void bowfaShots_accumulateIntoWholeShards()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();

		for (int i = 0; i < 150; i++)
		{
			tracker.onAttackGraphic(1888, "Bow of faerdhinen", i);
		}

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(1, spent.get(ItemID.PRIF_CRYSTAL_SHARD).intValue());
	}

	@Test
	public void blowpipe_recordsScalesAndLastLoadedDartType()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		tracker.recordUseSource("Rune dart");

		for (int i = 0; i < 30; i++)
		{
			tracker.onAttackAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, "Toxic blowpipe", i);
		}

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(20, spent.get(ItemID.SNAKEBOSS_SCALE).intValue());
		Assert.assertEquals(30, spent.get(ItemID.RUNE_DART).intValue());
	}

	@Test
	public void blowpipeDartRecovery_reducesDartSpend()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		tracker.recordUseSource("Dragon dart");
		tracker.setDartRecoveryRate(0.80);

		for (int i = 0; i < 10; i++)
		{
			tracker.onAttackAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, "Toxic blowpipe", i);
		}

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(2, spent.get(ItemID.DRAGON_DART).intValue());
	}

	@Test
	public void blowpipeWithoutLoadedDart_recordsScalesOnly()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();

		for (int i = 0; i < 3; i++)
		{
			tracker.onAttackAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, "Toxic blowpipe", i);
		}

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(2, spent.get(ItemID.SNAKEBOSS_SCALE).intValue());
		Assert.assertEquals(1, spent.size());
	}

	@Test
	public void recordUseSource_ignoresNonDarts()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		tracker.recordUseSource("Rune dart");
		tracker.recordUseSource("Chaos rune");
		tracker.onAttackAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, "Toxic blowpipe", 1);

		Assert.assertTrue(tracker.drain().containsKey(ItemID.RUNE_DART));
	}

	@Test
	public void recordUseSource_stripsUseMenuPrefix()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		tracker.recordUseSource("use rune dart");
		tracker.onAttackAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, "Toxic blowpipe", 1);

		Assert.assertTrue(tracker.drain().containsKey(ItemID.RUNE_DART));
	}

	// ---- Attack/varbit deduplication ----

	@Test
	public void varbitDeltaMatchingRecentAttack_isNotDoubleCounted()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		seed(tracker, VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 100);

		// Attack fires, then the same charge drop arrives via the varbit next tick
		tracker.onAttackAnimation(AnimationID.HUMAN_BOW, "Craw's bow", 5);
		tracker.onVarbitChanged(VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 99, true, 6);

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(1, spent.get(ItemID.WILD_CAVE_SHARD).intValue());
	}

	@Test
	public void varbitDeltaLargerThanRecentAttacks_countsRemainder()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		seed(tracker, VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 100);

		tracker.onAttackAnimation(AnimationID.HUMAN_BOW, "Craw's bow", 5);
		tracker.onVarbitChanged(VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 97, true, 6);

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(3, spent.get(ItemID.WILD_CAVE_SHARD).intValue());
	}

	@Test
	public void staleAttackDedupe_doesNotSuppressLaterVarbitDelta()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		seed(tracker, VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 100);

		tracker.onAttackAnimation(AnimationID.HUMAN_BOW, "Craw's bow", 5);
		// Varbit drop long after the attack was expensed -> counts fully
		tracker.onVarbitChanged(VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 99, true, 20);

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(2, spent.get(ItemID.WILD_CAVE_SHARD).intValue());
	}

	@Test
	public void tridentVarbitAlone_recordsCastCost()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		seed(tracker, VarbitID.CHARGES_TRIDENT_OF_THE_SEAS_QUANTITY, 500);

		tracker.onVarbitChanged(VarbitID.CHARGES_TRIDENT_OF_THE_SEAS_QUANTITY, 498, true, 1);

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(2, spent.get(ItemID.CHAOSRUNE).intValue());
		Assert.assertEquals(20, spent.get(ItemID.COINS).intValue());
	}

	// ---- Varbit-tracked items ----

	@Test
	public void wildernessWeaponVarbit_recordsEther()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		seed(tracker, VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 100);

		tracker.onVarbitChanged(VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 93, true, 1);

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(7, spent.get(ItemID.WILD_CAVE_SHARD).intValue());
	}

	@Test
	public void ringOfSufferingFractional_convertsToRecoilRings()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		seed(tracker, VarbitID.CHARGES_RING_OF_SUFFERING_QUANTITY, 400);

		tracker.onVarbitChanged(VarbitID.CHARGES_RING_OF_SUFFERING_QUANTITY, 320, true, 1);

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(2, spent.get(ItemID.RING_OF_RECOIL).intValue());
	}

	@Test
	public void chargeIncrease_isRechargeNotExpense()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		seed(tracker, VarbitID.CHARGES_TOME_OF_FIRE_QUANTITY, 100);

		tracker.onVarbitChanged(VarbitID.CHARGES_TOME_OF_FIRE_QUANTITY, 2600, true, 1);

		Assert.assertTrue(tracker.drain().isEmpty());
	}

	@Test
	public void firstObservation_establishesBaselineOnly()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();

		tracker.onVarbitChanged(VarbitID.CHARGES_BONECRUSHER_QUANTITY, 50, true, 1);

		Assert.assertTrue(tracker.drain().isEmpty());
	}

	@Test
	public void suppressedTracking_baselinesWithoutExpense()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		seed(tracker, VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 2500);

		// Uncharge while suppressed: varbit drops to 0 but no expense is recorded
		tracker.onVarbitChanged(VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 0, false, 1);
		Assert.assertTrue(tracker.drain().isEmpty());

		// A fresh deposit baselines again; only consumption after it counts
		tracker.onVarbitChanged(VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 2500, false, 2);
		tracker.onVarbitChanged(VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 2499, true, 3);
		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertEquals(1, spent.get(ItemID.WILD_CAVE_SHARD).intValue());
	}

	@Test
	public void reset_clearsBaselinesAndPendingSpend()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		seed(tracker, VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 100);
		tracker.onVarbitChanged(VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 97, true, 1);

		tracker.reset();

		// Next observation is a baseline again, not a diff against the stale value
		tracker.onVarbitChanged(VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY, 3, true, 2);
		Assert.assertTrue(tracker.drain().isEmpty());
	}

	@Test
	public void reset_preservesLoadedDartType()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		tracker.recordUseSource("Rune dart");
		tracker.reset();

		tracker.onAttackAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, "Toxic blowpipe", 1);

		Assert.assertTrue(tracker.drain().containsKey(ItemID.RUNE_DART));
	}

	@Test
	public void clearLoadedDarts_stopsDartAccounting()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();
		tracker.recordUseSource("Rune dart");
		tracker.clearLoadedDarts();

		tracker.onAttackAnimation(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK, "Toxic blowpipe", 1);

		Map<Integer, Integer> spent = tracker.drain();
		Assert.assertNull(spent.get(ItemID.RUNE_DART));
	}

	@Test
	public void untrackedVarbit_isIgnored()
	{
		WeaponChargeTracker tracker = new WeaponChargeTracker();

		tracker.onVarbitChanged(12345, 100, true, 1);
		tracker.onVarbitChanged(12345, 0, true, 2);

		Assert.assertTrue(tracker.drain().isEmpty());
	}
}
