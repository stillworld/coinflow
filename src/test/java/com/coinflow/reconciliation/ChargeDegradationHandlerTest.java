package com.coinflow.reconciliation;

import com.coinflow.CoinFlowConfig;
import com.coinflow.CoinFlowSession;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@RunWith(MockitoJUnitRunner.class)
public class ChargeDegradationHandlerTest
{
	@Mock
	private ItemManager itemManager;

	@Mock
	private CoinFlowConfig config;

	private ChargeDegradationHandler handler;

	@Before
	public void setUp()
	{
		handler = new ChargeDegradationHandler();
		lenient().when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));

		stubItem(11978, "Amulet of glory(6)", 13500L);
		stubItem(11976, "Amulet of glory(5)", 13000L);
		stubItem(1712, "Amulet of glory(4)", 12700L);
		stubItem(1710, "Amulet of glory(3)", 12500L);
		stubItem(1708, "Amulet of glory(2)", 12300L);
		stubItem(1706, "Amulet of glory(1)", 12000L);
		stubItem(1704, "Amulet of glory", 11500L);

		stubItem(2552, "Ring of dueling(8)", 1200L);
		stubItem(2554, "Ring of dueling(7)", 1100L);

		stubItem(4716, "Dharok's helm 100", 1500000L);
		stubItem(4718, "Dharok's helm 75", 1450000L);
	}

	private void stubItem(int id, String name, long price)
	{
		ItemComposition comp = mock(ItemComposition.class);
		lenient().when(comp.getName()).thenReturn(name);
		lenient().when(itemManager.getItemComposition(id)).thenReturn(comp);
		lenient().when(itemManager.getItemPrice(id)).thenReturn(price);
	}

	@Test
	public void gloryRubbing_5to4_cancelsGainsAndLossesWithoutPhantomProfit()
	{
		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(1712, 1); // Amulet of glory(4)

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(11976, 1); // Amulet of glory(5)

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Amulet of glory(4) must be removed from rawGains", context.getRawGains().isEmpty());
		Assert.assertTrue("Amulet of glory(5) must be removed from rawLosses", context.getRawLosses().isEmpty());
		Assert.assertTrue("Zero supply expenses should be recorded for glory rubbing", context.getSupplyExpenses().isEmpty());
	}

	@Test
	public void gloryRubbing_1toUncharged_cancelsGainsAndLosses()
	{
		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(1704, 1); // uncharged Amulet of glory

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(1706, 1); // Amulet of glory(1)

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Uncharged glory must be removed from rawGains", context.getRawGains().isEmpty());
		Assert.assertTrue("Amulet of glory(1) must be removed from rawLosses", context.getRawLosses().isEmpty());
	}

	@Test
	public void ringOfDueling_8to7_cancelsGainsAndLosses()
	{
		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(2554, 1); // Ring of dueling(7)

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(2552, 1); // Ring of dueling(8)

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue(context.getRawGains().isEmpty());
		Assert.assertTrue(context.getRawLosses().isEmpty());
	}

	@Test
	public void barrowsEquipment_100to75_cancelsGainsAndLosses()
	{
		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(4718, 1); // Dharok's helm 75

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(4716, 1); // Dharok's helm 100

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue(context.getRawGains().isEmpty());
		Assert.assertTrue(context.getRawLosses().isEmpty());
	}

	@Test
	public void slayerRing_3to2_cancelsGainsAndLosses()
	{
		stubItem(11871, "Slayer ring (3)", 600L);
		stubItem(11872, "Slayer ring (2)", 600L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(11872, 1); // Slayer ring (2)

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(11871, 1); // Slayer ring (3)

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Slayer ring (2) must be removed from rawGains", context.getRawGains().isEmpty());
		Assert.assertTrue("Slayer ring (3) must be removed from rawLosses", context.getRawLosses().isEmpty());
		Assert.assertTrue("Zero supply expenses recorded", context.getSupplyExpenses().isEmpty());
	}

	@Test
	public void slayerRing_2aloneInRawGains_suppressedFromGainsWithoutLoss()
	{
		stubItem(11872, "Slayer ring (2)", 600L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(11872, 1); // Slayer ring (2) arrived alone (e.g. split tick / scene transition)

		Map<Integer, Integer> rawLosses = new HashMap<>(); // empty losses

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Partially degraded Slayer ring (2) must be suppressed from rawGains even without matching loss",
			context.getRawGains().isEmpty());
	}

	@Test
	public void barrows_75aloneInRawGains_suppressedFromGainsWithoutLoss()
	{
		stubItem(4718, "Dharok's helm 75", 1450000L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(4718, 1);

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Dharok's helm 75 must be suppressed from rawGains", context.getRawGains().isEmpty());
	}

	@Test
	public void glory_5and4aloneInRawGains_suppressedFromGainsWithoutLoss()
	{
		Assert.assertTrue(ChargeDegradationHandler.isPartiallyDegraded("Amulet of glory(5)"));
		Assert.assertTrue(ChargeDegradationHandler.isPartiallyDegraded("Amulet of glory(4)"));
		Assert.assertTrue(ChargeDegradationHandler.isPartiallyDegraded("Combat bracelet(5)"));
		Assert.assertTrue(ChargeDegradationHandler.isPartiallyDegraded("Skills necklace(5)"));
		Assert.assertFalse(ChargeDegradationHandler.isPartiallyDegraded("Amulet of glory(6)"));

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(11976, 1); // Amulet of glory(5)
		rawGains.put(1712, 1);  // Amulet of glory(4)

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Partially degraded glory (5) and (4) must be suppressed from rawGains", context.getRawGains().isEmpty());
	}

	@Test
	public void extinguishingBullseyeLantern_cancelsGainsAndLosses()
	{
		stubItem(ItemID.BULLSEYE_LANTERN_LIT, "Bullseye lantern", 0L);
		stubItem(ItemID.BULLSEYE_LANTERN_UNLIT, "Bullseye lantern (unlit)", 680L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(ItemID.BULLSEYE_LANTERN_UNLIT, 1);

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(ItemID.BULLSEYE_LANTERN_LIT, 1);

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Unlit lantern must be removed from rawGains without generating 680gp phantom profit",
			context.getRawGains().isEmpty());
		Assert.assertTrue("Lit lantern must be removed from rawLosses",
			context.getRawLosses().isEmpty());
	}

	@Test
	public void lightingBullseyeLantern_cancelsGainsAndLosses()
	{
		stubItem(ItemID.BULLSEYE_LANTERN_UNLIT, "Bullseye lantern (unlit)", 680L);
		stubItem(ItemID.BULLSEYE_LANTERN_LIT, "Bullseye lantern", 0L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(ItemID.BULLSEYE_LANTERN_LIT, 1);

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(ItemID.BULLSEYE_LANTERN_UNLIT, 1);

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Lit lantern must be removed from rawGains", context.getRawGains().isEmpty());
		Assert.assertTrue("Unlit lantern must be removed from rawLosses", context.getRawLosses().isEmpty());
	}

	@Test
	public void litBullseyeLantern_aloneInRawGains_suppressedFromGains()
	{
		stubItem(ItemID.BULLSEYE_LANTERN_LIT, "Bullseye lantern (lit)", 0L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(ItemID.BULLSEYE_LANTERN_LIT, 1);

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Lit lantern in rawGains must be suppressed", context.getRawGains().isEmpty());
	}

	// ── Repair & recharge transitions ────────────────────────────────────

	@Test
	public void barrowsRepair_degradedToBase_cancelsGainsAndLosses_leavesFee()
	{
		stubItem(50001, "Guthan's platebody 100", 150000L);
		stubItem(50002, "Guthan's platebody", 184122L);
		stubItem(ItemID.COINS, "Coins", 1L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(50002, 1); // repaired Guthan's platebody

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(50001, 1);          // Guthan's platebody 100 handed in
		rawLosses.put(ItemID.COINS, 12758); // armour stand fee

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Repaired item must not count as profit", context.getRawGains().isEmpty());
		Assert.assertEquals("Only the coin fee remains in losses for the cash handler",
			java.util.Collections.singletonMap(ItemID.COINS, 12758), context.getRawLosses());
		Assert.assertEquals("Repair arms a fee allowance: stage-100 body caps at 250 pts x 90 gp",
			22_500L, context.getBarrowsRepairAllowanceGp());
	}

	@Test
	public void barrowsDegrade_75to50_armsNoRepairAllowance()
	{
		stubItem(50005, "Guthan's platebody 75", 150000L);
		stubItem(50006, "Guthan's platebody 50", 150000L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(50006, 1);

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(50005, 1);

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue(context.getRawGains().isEmpty());
		Assert.assertTrue(context.getRawLosses().isEmpty());
		Assert.assertEquals("Usage wear arms no repair allowance",
			0L, context.getBarrowsRepairAllowanceGp());
	}

	@Test
	public void barrowsFullyBroken_0toBase_cancelsGainsAndLosses()
	{
		stubItem(50003, "Dharok's helm 0", 100000L);
		stubItem(50004, "Dharok's helm", 1200000L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(50004, 1);

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(50003, 1);

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue(context.getRawGains().isEmpty());
		Assert.assertTrue(context.getRawLosses().isEmpty());
	}

	@Test
	public void barrowsFirstUse_baseTo100_cancelsGainsAndLosses()
	{
		stubItem(50004, "Dharok's helm", 1200000L);
		stubItem(50001, "Dharok's helm 100", 150000L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(50001, 1); // pristine piece degrades to "100" on first use

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(50004, 1);

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue(context.getRawGains().isEmpty());
		Assert.assertTrue(context.getRawLosses().isEmpty());
	}

	@Test
	public void gloryRecharge_2to4_cancelsGainsAndLosses()
	{
		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(1712, 1); // Amulet of glory(4)

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(1708, 1); // Amulet of glory(2)

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Recharged glory must not count as profit", context.getRawGains().isEmpty());
		Assert.assertTrue(context.getRawLosses().isEmpty());
	}

	@Test
	public void unchargedGloryRecharge_baseTo4_cancelsGainsAndLosses()
	{
		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(1712, 1); // Amulet of glory(4)

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(1704, 1); // uncharged Amulet of glory

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue(context.getRawGains().isEmpty());
		Assert.assertTrue(context.getRawLosses().isEmpty());
	}

	@Test
	public void unrelatedItems_atDegradeStages_doNotMatch()
	{
		stubItem(50003, "Dharok's helm 50", 1100000L);
		stubItem(50002, "Guthan's platebody", 184122L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(50002, 1);

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(50003, 1);

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Unrelated gain must remain", context.getRawGains().containsKey(50002));
		Assert.assertTrue("Unrelated loss must remain", context.getRawLosses().containsKey(50003));
	}

	@Test
	public void usageStep_preferredOverRecharge_sameBaseLootSurvives()
	{
		stubItem(2556, "Ring of dueling(6)", 1000L);
		stubItem(2558, "Ring of dueling(5)", 900L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(2552, 1); // Ring of dueling(8) picked up as loot
		rawGains.put(2558, 1); // Ring of dueling(5) after teleporting

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(2556, 1); // Ring of dueling(6)

		ReconciliationContext context = new ReconciliationContext(
			rawGains, rawLosses, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
			null, null, CoinFlowSession.createNew(), itemManager, config, null);

		handler.reconcile(context);

		Assert.assertTrue("Looted full-charge ring must stay a gain", context.getRawGains().containsKey(2552));
		Assert.assertEquals(1, context.getRawGains().size());
		Assert.assertTrue(context.getRawLosses().isEmpty());
	}

	@Test
	public void dropIntentLoss_notPairedWithGain()
	{
		stubItem(2556, "Ring of dueling(6)", 1000L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(2552, 1); // Ring of dueling(8) loot

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(2556, 1); // Ring of dueling(6) dropped

		ReconciliationContext context = new ReconciliationContext(
			rawGains, rawLosses, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
			null, null, CoinFlowSession.createNew(), itemManager, config, null);
		context.getDropIntentIds().add(2556);

		handler.reconcile(context);

		Assert.assertTrue(context.getRawGains().containsKey(2552));
		Assert.assertTrue(context.getRawLosses().containsKey(2556));
	}

	@Test
	public void stagedNameToBare_nonBarrowsItem_doesNotMatch()
	{
		Assert.assertFalse(ChargeDegradationHandler.isChargeDegradationPair("Ancient page 25", "Ancient page"));
		Assert.assertFalse(ChargeDegradationHandler.isChargeDegradationPair("Ancient page", "Ancient page 100"));
		Assert.assertTrue(ChargeDegradationHandler.isChargeDegradationPair("Karil's crossbow 0", "Karil's crossbow"));
	}

	@Test
	public void imbue_seersRing_cancelsGainAndLoss()
	{
		stubItem(6731, "Seers ring", 755000L);
		stubItem(11770, "Seers ring (i)", 755203L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(11770, 1); // Seers ring (i) after NMZ imbue

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(6731, 1); // Seers ring consumed by the imbue

		ReconciliationContext context = new ReconciliationContext(
			rawGains, rawLosses, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
			null, null, CoinFlowSession.createNew(), itemManager, config, null);

		handler.reconcile(context);

		Assert.assertTrue("Imbued ring must not be booked as profit", context.getRawGains().isEmpty());
		Assert.assertTrue("Unimbued ring loss must not be booked as a drop/expense", context.getRawLosses().isEmpty());
	}

	@Test
	public void imbuePair_matchesSameBaseImbuedVariants()
	{
		Assert.assertTrue(ChargeDegradationHandler.isChargeDegradationPair("Seers ring", "Seers ring (i)"));
		Assert.assertTrue(ChargeDegradationHandler.isChargeDegradationPair("Slayer helmet", "Slayer helmet (i)"));
		Assert.assertTrue(ChargeDegradationHandler.isChargeDegradationPair("Salve amulet(i)", "Salve amulet(ei)"));
		Assert.assertTrue(ChargeDegradationHandler.isChargeDegradationPair("Ring of suffering (r)", "Ring of suffering (ri)"));
		Assert.assertTrue(ChargeDegradationHandler.isChargeDegradationPair("Salve amulet(e)", "Salve amulet(ei)"));
		Assert.assertFalse(ChargeDegradationHandler.isChargeDegradationPair("Seers ring", "Berserker ring (i)"));
		Assert.assertFalse(ChargeDegradationHandler.isChargeDegradationPair("Seers ring", "Seers ring"));
		// (e)/(r) bases strip only when compared against an imbued twin —
		// tradeable suffix items must never pair with their base form.
		Assert.assertFalse(ChargeDegradationHandler.isChargeDegradationPair("Diamond bolts", "Diamond bolts (e)"));
		Assert.assertFalse(ChargeDegradationHandler.isChargeDegradationPair("Salve amulet", "Salve amulet(e)"));
	}

	@Test
	public void imbue_recoilVariant_cancelsGainAndLoss()
	{
		stubItem(20657, "Ring of suffering (r)", 150000L);
		stubItem(20655, "Ring of suffering (ri)", 150000L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(20655, 1); // Ring of suffering (ri) after the imbue

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(20657, 1); // Ring of suffering (r) consumed

		ReconciliationContext context = new ReconciliationContext(
			rawGains, rawLosses, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
			null, null, CoinFlowSession.createNew(), itemManager, config, null);

		handler.reconcile(context);

		Assert.assertTrue("Recoil-imbued ring must not be booked as profit", context.getRawGains().isEmpty());
		Assert.assertTrue("Unimbued ring loss must not be booked as a drop/expense", context.getRawLosses().isEmpty());
	}

	@Test
	public void unmatchedImbuedGain_isSuppressed()
	{
		stubItem(11770, "Seers ring (i)", 755203L);
		stubItem(20655, "Ring of suffering (ri)", 150000L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(11770, 1); // Imbue gains landing on a later tick than the loss
		rawGains.put(20655, 1);

		ReconciliationContext context = new ReconciliationContext(
			rawGains, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
			null, null, CoinFlowSession.createNew(), itemManager, config, null);

		handler.reconcile(context);

		Assert.assertTrue("Imbued variant gains must be suppressed as conversion artifacts",
			context.getRawGains().isEmpty());
	}

	// ── Moons of Peril repairs ─────────────────────────────────────────

	@Test
	public void moonRepair_brokenToUsed_cancelsGainsAndLosses_leavesFee()
	{
		stubItem(ItemID.BLOOD_MOON_CHESTPLATE_BROKEN, "Blood moon chestplate (broken)", 0L);
		stubItem(ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED, "Blood moon chestplate", 2791852L);
		stubItem(ItemID.COINS, "Coins", 1L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED, 1); // repaired chestplate from the armour stand

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(ItemID.BLOOD_MOON_CHESTPLATE_BROKEN, 1); // broken chestplate handed in
		rawLosses.put(ItemID.COINS, 757500);                  // repair fee at 99 Smithing

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Repaired chestplate must not count as profit", context.getRawGains().isEmpty());
		Assert.assertEquals("Only the repair fee remains in losses for the cash handler",
			java.util.Collections.singletonMap(ItemID.COINS, 757500), context.getRawLosses());
		Assert.assertEquals("Moon wear is not accrued — no repair allowance",
			0L, context.getBarrowsRepairAllowanceGp());
	}

	@Test
	public void moonUsedVariant_aloneInRawGains_suppressedFromGains()
	{
		stubItem(ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED, "Blood moon chestplate", 2791852L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(ItemID.BLOOD_MOON_CHESTPLATE_DEGRADED, 1); // repaired chestplate landing without its loss

		ReconciliationContext context = new ReconciliationContext(
			rawGains, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
			null, null, CoinFlowSession.createNew(), itemManager, config, null);

		handler.reconcile(context);

		Assert.assertTrue("A used-variant gain is always a conversion artifact",
			context.getRawGains().isEmpty());
	}

	@Test
	public void moonNewVariant_aloneInRawGains_survivesAsLoot()
	{
		stubItem(ItemID.BLOOD_MOON_CHESTPLATE, "Blood moon chestplate", 2791852L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(ItemID.BLOOD_MOON_CHESTPLATE, 1); // Lunar Chest drop

		ReconciliationContext context = new ReconciliationContext(
			rawGains, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
			null, null, CoinFlowSession.createNew(), itemManager, config, null);

		handler.reconcile(context);

		Assert.assertEquals("A new-variant gain is real loot from the Lunar Chest",
			java.util.Collections.singletonMap(ItemID.BLOOD_MOON_CHESTPLATE, 1), context.getRawGains());
	}

	@Test
	public void torvaRepair_brokenToBase_cancelsGainsAndLosses_leavesFee()
	{
		stubItem(ItemID.BROKEN_TORVA_LEGS, "Torva platelegs (broken)", 0L);
		stubItem(ItemID.TORVA_LEGS, "Torva platelegs", 340000000L);
		stubItem(ItemID.COINS, "Coins", 1L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(ItemID.TORVA_LEGS, 1); // repaired platelegs from Bob

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(ItemID.BROKEN_TORVA_LEGS, 1); // broken platelegs handed in
		rawLosses.put(ItemID.COINS, 500000); // repair fee

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Repaired platelegs must not count as profit", context.getRawGains().isEmpty());
		Assert.assertEquals("Only the repair fee remains in losses for the cash handler",
			java.util.Collections.singletonMap(ItemID.COINS, 500000), context.getRawLosses());
	}

	@Test
	public void unchargedRecharge_unchargedToCharged_cancelsGainsAndLosses()
	{
		stubItem(11284, "Dragonfire shield (uncharged)", 16000000L);
		stubItem(11283, "Dragonfire shield", 17000000L);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(11283, 1); // shield charged by dragonfire

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(11284, 1); // uncharged shield converted

		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);

		handler.reconcile(context);

		Assert.assertTrue("Charged shield must not count as profit", context.getRawGains().isEmpty());
		Assert.assertTrue("Uncharged shield loss must cancel against the charge conversion",
			context.getRawLosses().isEmpty());
	}

	@Test
	public void brokenRepair_pairing_matchesSameBase()
	{
		Assert.assertTrue(ChargeDegradationHandler.isChargeDegradationPair(
			"Blood moon chestplate (broken)", "Blood moon chestplate"));
		Assert.assertTrue(ChargeDegradationHandler.isChargeDegradationPair(
			"Blood moon chestplate", "Blood moon chestplate (broken)"));
		Assert.assertTrue(ChargeDegradationHandler.isChargeDegradationPair(
			"Torva platelegs (broken)", "Torva platelegs"));
		Assert.assertTrue(ChargeDegradationHandler.isChargeDegradationPair(
			"Infernal cape (broken)", "Infernal cape"));
		Assert.assertTrue(ChargeDegradationHandler.isChargeDegradationPair(
			"Dragonfire shield (uncharged)", "Dragonfire shield"));
		Assert.assertFalse(ChargeDegradationHandler.isChargeDegradationPair(
			"Blood moon chestplate (broken)", "Eclipse moon chestplate"));
		Assert.assertFalse(ChargeDegradationHandler.isChargeDegradationPair(
			"Blood moon chestplate", "Blood moon chestplate"));
		// "(u)" means unstrung in fletching (Magic longbow (u) -> Magic
		// longbow is production), so it is deliberately not paired.
		Assert.assertFalse(ChargeDegradationHandler.isChargeDegradationPair(
			"Magic longbow (u)", "Magic longbow"));
		Assert.assertFalse(ChargeDegradationHandler.isChargeDegradationPair(
			"Webweaver bow (u)", "Webweaver bow"));
	}
}
