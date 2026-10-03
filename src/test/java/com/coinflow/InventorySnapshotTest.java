package com.coinflow;

import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

import static com.coinflow.TestHelpers.snapshot;

/**
 * Tests for {@link InventorySnapshot} diff logic.
 * Pure logic — no mocking required.
 */
public class InventorySnapshotTest
{
	// ── getGainedItems ───────────────────────────────────────────────────

	@Test
	public void emptyVsEmpty_noGains()
	{
		InventorySnapshot current = snapshot();
		InventorySnapshot previous = snapshot();
		Assert.assertTrue(current.getGainedItems(previous).isEmpty());
	}

	@Test
	public void newItemAppears_fullQuantityGained()
	{
		InventorySnapshot current = snapshot(100, 5);
		InventorySnapshot previous = snapshot();
		Map<Integer, Integer> gains = current.getGainedItems(previous);
		Assert.assertEquals(1, gains.size());
		Assert.assertEquals(5, (int) gains.get(100));
	}

	@Test
	public void itemQuantityIncreases_deltaOnly()
	{
		InventorySnapshot current = snapshot(100, 8);
		InventorySnapshot previous = snapshot(100, 5);
		Map<Integer, Integer> gains = current.getGainedItems(previous);
		Assert.assertEquals(1, gains.size());
		Assert.assertEquals(3, (int) gains.get(100));
	}

	@Test
	public void itemQuantityDecreases_notIncluded()
	{
		InventorySnapshot current = snapshot(100, 2);
		InventorySnapshot previous = snapshot(100, 5);
		Assert.assertTrue(current.getGainedItems(previous).isEmpty());
	}

	@Test
	public void itemDisappears_notIncluded()
	{
		InventorySnapshot current = snapshot();
		InventorySnapshot previous = snapshot(100, 3);
		Assert.assertTrue(current.getGainedItems(previous).isEmpty());
	}

	@Test
	public void multipleItemsMixed_onlyPositiveDeltasReturned()
	{
		// Item 1: 5 → 8 (gain 3)
		// Item 2: 10 → 10 (no change)
		// Item 3: 4 → 2 (loss, excluded)
		// Item 4: 0 → 7 (new item, gain 7)
		InventorySnapshot current = snapshot(1, 8, 2, 10, 3, 2, 4, 7);
		InventorySnapshot previous = snapshot(1, 5, 2, 10, 3, 4);
		Map<Integer, Integer> gains = current.getGainedItems(previous);
		Assert.assertEquals(2, gains.size());
		Assert.assertEquals(3, (int) gains.get(1));
		Assert.assertEquals(7, (int) gains.get(4));
		Assert.assertFalse(gains.containsKey(2));
		Assert.assertFalse(gains.containsKey(3));
	}

	@Test
	public void stackableItemLargeQty_correctDelta()
	{
		InventorySnapshot current = snapshot(555, 10_000);
		InventorySnapshot previous = snapshot(555, 3_000);
		Map<Integer, Integer> gains = current.getGainedItems(previous);
		Assert.assertEquals(7_000, (int) gains.get(555));
	}

	@Test
	public void emptySlotsIgnored_idMinusOneAndQtyZero()
	{
		// id=-1 and qty=0 are both skip conditions in fromArrays
		int[] ids = {-1, 100, 0};
		int[] qtys = {1, 5, 0};
		InventorySnapshot snap = InventorySnapshot.fromArrays(ids, qtys);
		Assert.assertEquals(1, snap.getItems().size());
		Assert.assertEquals(5, (int) snap.getItems().get(100));
	}

	@Test
	public void baselineVsBaseline_noGains()
	{
		InventorySnapshot snap = snapshot(10, 3, 20, 7);
		Assert.assertTrue(snap.getGainedItems(snap).isEmpty());
	}

	@Test
	public void fromArrays_nullGuard_emptySnapshot()
	{
		// Must not throw; returns empty
		InventorySnapshot snap = InventorySnapshot.fromArrays(null, null);
		Assert.assertTrue(snap.getItems().isEmpty());
	}

	@Test
	public void fromArrays_mergesDuplicateIds_qtySummed()
	{
		// Two slots with the same item ID (e.g. partial stacks in different slots)
		int[] ids = {200, 200};
		int[] qtys = {3, 7};
		InventorySnapshot snap = InventorySnapshot.fromArrays(ids, qtys);
		Assert.assertEquals(1, snap.getItems().size());
		Assert.assertEquals(10, (int) snap.getItems().get(200));
	}

	// ── getLostItems ─────────────────────────────────────────────────────

	@Test
	public void emptyVsEmpty_noLosses()
	{
		InventorySnapshot current = snapshot();
		InventorySnapshot previous = snapshot();
		Assert.assertTrue(current.getLostItems(previous).isEmpty());
	}

	@Test
	public void itemDecreases_quantityLossCalculated()
	{
		InventorySnapshot previous = snapshot(100, 5);
		InventorySnapshot current = snapshot(100, 2);
		Map<Integer, Integer> losses = current.getLostItems(previous);
		Assert.assertEquals(1, losses.size());
		Assert.assertEquals(3, (int) losses.get(100));
	}

	@Test
	public void itemDisappears_fullQuantityLossCalculated()
	{
		InventorySnapshot previous = snapshot(100, 4);
		InventorySnapshot current = snapshot();
		Map<Integer, Integer> losses = current.getLostItems(previous);
		Assert.assertEquals(1, losses.size());
		Assert.assertEquals(4, (int) losses.get(100));
	}

	@Test
	public void itemIncreases_notCountedAsLoss()
	{
		InventorySnapshot previous = snapshot(100, 2);
		InventorySnapshot current = snapshot(100, 5);
		Assert.assertTrue(current.getLostItems(previous).isEmpty());
	}

	@Test
	public void normalizeContainerItemId_normalizesAllOpenContainersConsistently()
	{
		Assert.assertEquals(net.runelite.api.gameval.ItemID.FISH_BARREL_CLOSED,
			InventorySnapshotService.normalizeContainerItemId(net.runelite.api.gameval.ItemID.FISH_BARREL_OPEN));
		Assert.assertEquals(net.runelite.api.gameval.ItemID.FISH_SACK_BARREL_CLOSED,
			InventorySnapshotService.normalizeContainerItemId(net.runelite.api.gameval.ItemID.FISH_SACK_BARREL_OPEN));
		Assert.assertEquals(net.runelite.api.gameval.ItemID.SEED_BOX,
			InventorySnapshotService.normalizeContainerItemId(net.runelite.api.gameval.ItemID.SEED_BOX_OPEN));
		Assert.assertEquals(net.runelite.api.gameval.ItemID.LOG_BASKET_CLOSED,
			InventorySnapshotService.normalizeContainerItemId(net.runelite.api.gameval.ItemID.LOG_BASKET_OPEN));
		Assert.assertEquals(net.runelite.api.gameval.ItemID.FORESTRY_BASKET_CLOSED,
			InventorySnapshotService.normalizeContainerItemId(net.runelite.api.gameval.ItemID.FORESTRY_BASKET_OPEN));
		Assert.assertEquals(net.runelite.api.gameval.ItemID.SLAYER_HERB_SACK,
			InventorySnapshotService.normalizeContainerItemId(net.runelite.api.gameval.ItemID.SLAYER_HERB_SACK_OPEN));
		Assert.assertEquals(net.runelite.api.gameval.ItemID.GEM_BAG,
			InventorySnapshotService.normalizeContainerItemId(net.runelite.api.gameval.ItemID.GEM_BAG_OPEN));

		// Light sources: lit lantern/candle/torch normalized to unlit
		Assert.assertEquals(net.runelite.api.gameval.ItemID.BULLSEYE_LANTERN_UNLIT,
			InventorySnapshotService.normalizeContainerItemId(net.runelite.api.gameval.ItemID.BULLSEYE_LANTERN_LIT));
		Assert.assertEquals(net.runelite.api.gameval.ItemID.OIL_LANTERN_UNLIT,
			InventorySnapshotService.normalizeContainerItemId(net.runelite.api.gameval.ItemID.OIL_LANTERN_LIT));
		Assert.assertEquals(net.runelite.api.gameval.ItemID.UNLIT_CANDLE,
			InventorySnapshotService.normalizeContainerItemId(net.runelite.api.gameval.ItemID.LIT_CANDLE));
		Assert.assertEquals(net.runelite.api.gameval.ItemID.TORCH_UNLIT,
			InventorySnapshotService.normalizeContainerItemId(net.runelite.api.gameval.ItemID.TORCH_LIT));

		// Non-container item remains unchanged
		Assert.assertEquals(net.runelite.api.gameval.ItemID.ABYSSAL_WHIP,
			InventorySnapshotService.normalizeContainerItemId(net.runelite.api.gameval.ItemID.ABYSSAL_WHIP));
	}
}
