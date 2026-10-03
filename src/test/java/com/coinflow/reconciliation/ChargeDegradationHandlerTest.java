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
}
