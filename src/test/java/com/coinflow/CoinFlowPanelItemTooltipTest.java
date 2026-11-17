package com.coinflow;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link CoinFlowPanel#itemTooltip}: the provenance lines added to
 * item-row tooltips. The builder is static and headless-safe.
 */
public class CoinFlowPanelItemTooltipTest
{
	private static CoinFlowSession.TrackedItem item(
		int id, String name, long qty, long price,
		CoinFlowSession.TrackedItem.Reason reason,
		CoinFlowSession.TrackedItem.PriceBasis basis)
	{
		return new CoinFlowSession.TrackedItem(id, name, qty, price)
			.withProvenance(reason, basis);
	}

	@Test
	public void untaggedItem_showsBaseFieldsOnly()
	{
		String tip = CoinFlowPanel.itemTooltip(
			new CoinFlowSession.TrackedItem(1, "Coal", 10, 150L), false);

		Assert.assertTrue(tip.contains("Coal"));
		Assert.assertTrue(tip.contains("Quantity: 10"));
		Assert.assertTrue(tip.contains("Price each: 150 gp"));
		Assert.assertTrue(tip.contains("Total: +1,500 gp"));
		Assert.assertFalse(tip.contains("Booked as:"));
		Assert.assertFalse(tip.contains("Priced by:"));
		Assert.assertFalse(tip.contains("Still held:"));
	}

	@Test
	public void gainRow_showsReasonAndBasis()
	{
		String tip = CoinFlowPanel.itemTooltip(
			item(385, "Shark", 3, 1_000L,
				CoinFlowSession.TrackedItem.Reason.GAINED,
				CoinFlowSession.TrackedItem.PriceBasis.GE_PRICE),
			false);

		Assert.assertTrue(tip.contains("Loot / Gain"));
		Assert.assertTrue(tip.contains("Booked as: Item gained"));
		Assert.assertTrue(tip.contains("Priced by: GE price"));
	}

	@Test
	public void expenseRow_usesExpenseLabel()
	{
		String tip = CoinFlowPanel.itemTooltip(
			item(385, "Shark", 3, 1_000L,
				CoinFlowSession.TrackedItem.Reason.CONSUMED,
				CoinFlowSession.TrackedItem.PriceBasis.GE_PRICE),
			true);

		Assert.assertTrue(tip.contains("Expense (supply consumed)"));
		Assert.assertTrue(tip.contains("Total: -3,000 gp"));
		Assert.assertTrue(tip.contains("Booked as: Supply consumed"));
	}

	@Test
	public void multipleBases_showMixedMarker()
	{
		String tip = CoinFlowPanel.itemTooltip(
			item(385, "Shark", 10, 1_100L,
					CoinFlowSession.TrackedItem.Reason.CONSUMED,
					CoinFlowSession.TrackedItem.PriceBasis.GE_PRICE)
				.withProvenance(null, CoinFlowSession.TrackedItem.PriceBasis.PURCHASE_COST),
			true);

		Assert.assertTrue(tip.contains("Priced by: GE price, What you paid (mixed)"));
	}

	@Test
	public void partialConsumption_showsStillHeld()
	{
		CoinFlowSession.TrackedItem item =
			item(385, "Shark", 10, 1_000L,
					CoinFlowSession.TrackedItem.Reason.GAINED,
					CoinFlowSession.TrackedItem.PriceBasis.GE_PRICE)
				.withConsumption(7);

		String tip = CoinFlowPanel.itemTooltip(item, false);
		Assert.assertTrue(tip.contains("Still held: 3 of 10"));

		// Expense rows never show the line.
		Assert.assertFalse(CoinFlowPanel.itemTooltip(item, true)
			.contains("Still held:"));
	}

	@Test
	public void fullyHeldGain_omitsStillHeld()
	{
		String tip = CoinFlowPanel.itemTooltip(
			item(385, "Shark", 10, 1_000L,
				CoinFlowSession.TrackedItem.Reason.GAINED,
				CoinFlowSession.TrackedItem.PriceBasis.GE_PRICE),
			false);
		Assert.assertFalse(tip.contains("Still held:"));
	}

	@Test
	public void deathRow_showsSyntheticExplanation()
	{
		String tip = CoinFlowPanel.itemTooltip(
			item(DeathTracker.DEATH_ROW_ID, DeathTracker.DEATH_ROW_NAME, 50_000L, 1L,
				CoinFlowSession.TrackedItem.Reason.SYNTHETIC,
				CoinFlowSession.TrackedItem.PriceBasis.FIXED),
			true);

		Assert.assertTrue(tip.contains("Bookkeeping row"));
		Assert.assertTrue(tip.contains("Value of items lost on death; reclaims reverse it."));
	}

	@Test
	public void geTaxRow_showsSyntheticExplanation()
	{
		String tip = CoinFlowPanel.itemTooltip(
			item(GrandExchangeTax.TAX_ITEM_ID, GrandExchangeTax.TAX_ITEM_NAME, 1180L, 1L,
				CoinFlowSession.TrackedItem.Reason.SYNTHETIC,
				CoinFlowSession.TrackedItem.PriceBasis.FIXED),
			true);

		Assert.assertTrue(tip.contains("GE tax on sales of untracked stock."));
	}

	@Test
	public void realItemOnSyntheticId_noExplanation()
	{
		// Wooden hull parts are real items; only the SYNTHETIC-tagged cargo
		// loss row should show the explanation line.
		String tip = CoinFlowPanel.itemTooltip(
			item(InventorySnapshotService.CARGO_LOSS_ROW_ID, "Wooden hull part", 5, 500L,
				CoinFlowSession.TrackedItem.Reason.GAINED,
				CoinFlowSession.TrackedItem.PriceBasis.GE_PRICE),
			false);

		Assert.assertFalse(tip.contains("Cargo lost with a destroyed boat."));
	}

	@Test
	public void alchGain_usesAlchLabel()
	{
		String tip = CoinFlowPanel.itemTooltip(
			item(net.runelite.api.gameval.ItemID.COINS, "Coins", 6000L, 1L,
				CoinFlowSession.TrackedItem.Reason.ALCHED,
				CoinFlowSession.TrackedItem.PriceBasis.FIXED),
			false);

		Assert.assertTrue(tip.contains("Booked as: Alchemy proceeds"));
	}
}
