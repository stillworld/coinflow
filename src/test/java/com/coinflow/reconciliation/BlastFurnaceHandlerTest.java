package com.coinflow.reconciliation;

import com.coinflow.CoinFlowConfig;
import com.coinflow.CoinFlowSession;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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

/**
 * Tests for {@link BlastFurnaceHandler}: belt-load ore/coal losses are
 * stripped (not expensed as owned drops), and collected bars are charged
 * their per-bar Blast Furnace recipe.
 */
@RunWith(MockitoJUnitRunner.class)
public class BlastFurnaceHandlerTest
{
	@Mock
	private ItemManager itemManager;

	@Mock
	private CoinFlowConfig config;

	private BlastFurnaceHandler handler;

	@Before
	public void setUp()
	{
		handler = new BlastFurnaceHandler();
		lenient().when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));

		stubItem(ItemID.IRON_ORE, "Iron ore", 100L);
		stubItem(ItemID.COAL, "Coal", 200L);
		stubItem(ItemID.RUNITE_ORE, "Runite ore", 11_000L);
		stubItem(ItemID.MITHRIL_ORE, "Mithril ore", 160L);
		stubItem(ItemID.STEEL_BAR, "Steel bar", 800L);
		stubItem(ItemID.MITHRIL_BAR, "Mithril bar", 1_000L);
		stubItem(ItemID.RUNITE_BAR, "Runite bar", 12_000L);
	}

	private void stubItem(int id, String name, long price)
	{
		ItemComposition comp = mock(ItemComposition.class);
		lenient().when(comp.getName()).thenReturn(name);
		lenient().when(itemManager.getItemComposition(id)).thenReturn(comp);
		lenient().when(itemManager.getItemPrice(id)).thenReturn(price);
	}

	private ReconciliationContext context(Map<Integer, Integer> gains, Map<Integer, Integer> losses,
		boolean active)
	{
		ReconciliationContext ctx = new ReconciliationContext(
			gains,
			losses,
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
		ctx.setBlastFurnaceActive(active);
		return ctx;
	}

	private ReconciliationContext context(Map<Integer, Integer> gains, Map<Integer, Integer> losses,
		boolean active, CoinFlowSession session, java.util.Set<String> ignored)
	{
		ReconciliationContext ctx = new ReconciliationContext(
			gains,
			losses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			session,
			itemManager,
			config,
			ignored
		);
		ctx.setBlastFurnaceActive(active);
		return ctx;
	}

	private static Map<Integer, Integer> mapOf(int... idQtyPairs)
	{
		Map<Integer, Integer> map = new HashMap<>();
		for (int i = 0; i < idQtyPairs.length; i += 2)
		{
			map.put(idQtyPairs[i], idQtyPairs[i + 1]);
		}
		return map;
	}

	// ── Belt-load losses ─────────────────────────────────────────────────

	@Test
	public void oreLoss_atFurnace_strippedNotExpensed()
	{
		Map<Integer, Integer> losses = mapOf(ItemID.IRON_ORE, 27);
		ReconciliationContext ctx = context(new HashMap<>(), losses, true);
		handler.reconcile(ctx);

		Assert.assertFalse("Belt-loaded ore must be stripped from the diff",
			ctx.getRawLosses().containsKey(ItemID.IRON_ORE));
		Assert.assertTrue("Belt-loaded ore must not book as a supply expense",
			ctx.getSupplyExpenses().isEmpty());
	}

	@Test
	public void oreLoss_notAtFurnace_untouched()
	{
		Map<Integer, Integer> losses = mapOf(ItemID.IRON_ORE, 27);
		ReconciliationContext ctx = context(new HashMap<>(), losses, false);
		handler.reconcile(ctx);

		Assert.assertEquals("Away from the furnace the loss stays for the normal path",
			27, (int) ctx.getRawLosses().get(ItemID.IRON_ORE));
	}

	@Test
	public void oreLoss_withDropIntent_kept()
	{
		Map<Integer, Integer> losses = mapOf(ItemID.IRON_ORE, 5);
		ReconciliationContext ctx = context(new HashMap<>(), losses, true);
		ctx.getDropIntentIds().add(ItemID.IRON_ORE);
		handler.reconcile(ctx);

		Assert.assertEquals("A Drop-clicked ore stays on the own-drop path",
			5, (int) ctx.getRawLosses().get(ItemID.IRON_ORE));
	}

	@Test
	public void notedOreLoss_kept()
	{
		int notedIronOre = 999_001;
		lenient().when(itemManager.canonicalize(notedIronOre)).thenReturn(ItemID.IRON_ORE);

		Map<Integer, Integer> losses = mapOf(notedIronOre, 27);
		ReconciliationContext ctx = context(new HashMap<>(), losses, true);
		handler.reconcile(ctx);

		Assert.assertTrue("Noted ore is bank business, not a belt load",
			ctx.getRawLosses().containsKey(notedIronOre));
	}

	@Test
	public void sessionOreGain_retiredOnBeltLoad()
	{
		Map<Integer, CoinFlowSession.TrackedItem> gains = new HashMap<>();
		gains.put(ItemID.IRON_ORE,
			new CoinFlowSession.TrackedItem(ItemID.IRON_ORE, "Iron ore", 27, 100L));
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGainsLossesAndExpenses(gains, Collections.emptyMap(), Collections.emptyMap());

		Map<Integer, Integer> losses = mapOf(ItemID.IRON_ORE, 27);
		ReconciliationContext ctx = context(new HashMap<>(), losses, true, session, null);
		handler.reconcile(ctx);

		Assert.assertEquals("Session-mined ore loaded onto the belt is marked consumed",
			27L, (long) ctx.getConsumedSessionQuantities().getOrDefault(ItemID.IRON_ORE, 0L));
	}

	// ── Bar collection expenses ──────────────────────────────────────────

	@Test
	public void steelBarsCollected_chargesIronAndCoal()
	{
		Map<Integer, Integer> gains = mapOf(ItemID.STEEL_BAR, 27);
		ReconciliationContext ctx = context(gains, new HashMap<>(), true);
		handler.reconcile(ctx);

		CoinFlowSession.TrackedItem iron = ctx.getSupplyExpenses().get(ItemID.IRON_ORE);
		CoinFlowSession.TrackedItem coal = ctx.getSupplyExpenses().get(ItemID.COAL);
		Assert.assertNotNull("Iron ore input must be expensed", iron);
		Assert.assertNotNull("Coal input must be expensed", coal);
		Assert.assertEquals(27L, iron.getQuantity());
		Assert.assertEquals(27L, coal.getQuantity());
		Assert.assertEquals(100L, iron.getPriceEach());
		Assert.assertEquals(200L, coal.getPriceEach());
		Assert.assertTrue("The bar gain itself is left for the normal gain path",
			ctx.getRawGains().containsKey(ItemID.STEEL_BAR));
	}

	@Test
	public void runiteBarsCollected_chargesFourCoalPerBar()
	{
		Map<Integer, Integer> gains = mapOf(ItemID.RUNITE_BAR, 5);
		ReconciliationContext ctx = context(gains, new HashMap<>(), true);
		handler.reconcile(ctx);

		Assert.assertEquals(5L, ctx.getSupplyExpenses().get(ItemID.RUNITE_ORE).getQuantity());
		Assert.assertEquals("Runite costs 4 coal per bar at the Blast Furnace",
			20L, ctx.getSupplyExpenses().get(ItemID.COAL).getQuantity());
	}

	@Test
	public void mixedBars_mergesCoalExpense()
	{
		Map<Integer, Integer> gains = mapOf(ItemID.STEEL_BAR, 1, ItemID.MITHRIL_BAR, 1);
		ReconciliationContext ctx = context(gains, new HashMap<>(), true);
		handler.reconcile(ctx);

		Assert.assertEquals("1 (steel) + 2 (mithril) coal merge into one expense row",
			3L, ctx.getSupplyExpenses().get(ItemID.COAL).getQuantity());
		Assert.assertEquals(1L, ctx.getSupplyExpenses().get(ItemID.IRON_ORE).getQuantity());
		Assert.assertEquals(1L, ctx.getSupplyExpenses().get(ItemID.MITHRIL_ORE).getQuantity());
	}

	@Test
	public void ignoredBar_noInputCharges()
	{
		Map<Integer, Integer> gains = mapOf(ItemID.STEEL_BAR, 27);
		ReconciliationContext ctx = context(gains, new HashMap<>(), true,
			CoinFlowSession.createNew(), new HashSet<>(Collections.singletonList("steel bar")));
		handler.reconcile(ctx);

		Assert.assertTrue("An ignored bar must not charge inputs (net can never go negative)",
			ctx.getSupplyExpenses().isEmpty());
	}

	@Test
	public void ignoredCoal_skipsCoalButChargesOre()
	{
		Map<Integer, Integer> gains = mapOf(ItemID.STEEL_BAR, 10);
		ReconciliationContext ctx = context(gains, new HashMap<>(), true,
			CoinFlowSession.createNew(), new HashSet<>(Collections.singletonList("coal")));
		handler.reconcile(ctx);

		Assert.assertNull("Ignored coal input is skipped", ctx.getSupplyExpenses().get(ItemID.COAL));
		Assert.assertEquals(10L, ctx.getSupplyExpenses().get(ItemID.IRON_ORE).getQuantity());
	}

	// ── Superheat guard ──────────────────────────────────────────────────

	@Test
	public void superheatSmelt_leftToProcessingHandler()
	{
		// Superheat loses ore + coal and gains a bar in the same tick — the
		// handler must step aside or the ore would be stripped as a belt load
		// and the bar charged with the half-coal BF recipe.
		Map<Integer, Integer> gains = mapOf(ItemID.STEEL_BAR, 1);
		Map<Integer, Integer> losses = mapOf(ItemID.IRON_ORE, 1, ItemID.COAL, 2);
		ReconciliationContext ctx = context(gains, losses, true);
		handler.reconcile(ctx);

		Assert.assertEquals("Ore loss of a same-tick smelt is not a belt load",
			1, (int) ctx.getRawLosses().get(ItemID.IRON_ORE));
		Assert.assertEquals(2, (int) ctx.getRawLosses().get(ItemID.COAL));
		Assert.assertTrue("No BF recipe charge on a Superheat diff",
			ctx.getSupplyExpenses().isEmpty());
	}
}
