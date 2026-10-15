package com.coinflow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BlastFurnaceTracker}: coffer deposits/withdrawals are
 * balance transfers (not spend/profit), only the capped 12 gp/tick drain is
 * charged to the synthetic coffer row, and belt loads are free inputs paid
 * for when bars are collected.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class BlastFurnaceTrackerTest
{
	@Mock Client client;
	@Mock CoinFlowConfig config;
	@Mock ItemManager itemManager;
	@Mock OverlayManager overlayManager;
	@Mock CoinFlowGoldDropOverlay goldDropOverlay;
	@Mock Notifier notifier;
	@Mock ClientThread clientThread;
	@Mock ConfigManager configManager;
	@Mock EventBus eventBus;
	@Mock CoinFlowOverlay overlay;

	private CoinFlowPlugin plugin;
	private int tick;

	@Before
	public void setUp()
	{
		plugin = new CoinFlowPlugin();
		plugin.client = client;
		plugin.config = config;
		plugin.itemManager = itemManager;
		plugin.overlayManager = overlayManager;
		plugin.goldDropOverlay = goldDropOverlay;
		plugin.notifier = notifier;
		plugin.clientThread = clientThread;
		plugin.configManager = configManager;
		plugin.eventBus = eventBus;
		plugin.overlay = overlay;

		plugin.session = CoinFlowSession.createNew();
		plugin.snapshotInitialized = false;
		plugin.setTrackingSuppressed(false);
		plugin.setNeedsRebaseline(false);
		plugin.ignoredItemNames = new HashSet<>();

		lenient().when(config.trackSpent()).thenReturn(true);
		lenient().when(config.countDropsAsSpent()).thenReturn(true);
		lenient().when(config.idleTimeoutMinutes()).thenReturn(5);
		lenient().when(config.goalAmount()).thenReturn("");
		lenient().when(config.goalName()).thenReturn("");
		lenient().when(config.includeAfkTime()).thenReturn(false);
		lenient().when(config.trackUntradeableItems()).thenReturn(true);
		lenient().when(config.showGoldDrops()).thenReturn(false);
		lenient().when(client.isClientThread()).thenReturn(true);
		lenient().when(client.getTickCount()).thenAnswer(inv -> tick);
		lenient().when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));

		stubItem(ItemID.COINS, "Coins", 1L);
		stubItem(ItemID.IRON_ORE, "Iron ore", 100L);
		stubItem(ItemID.COAL, "Coal", 200L);
		stubItem(ItemID.STEEL_BAR, "Steel bar", 800L);
		stubItem(ItemID.MITHRIL_ORE, "Mithril ore", 160L);
		stubItem(ItemID.MITHRIL_BAR, "Mithril bar", 1_000L);

		activate();
	}

	private void activate()
	{
		plugin.blastFurnaceTracker.onObjectSpawn(ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE);
	}

	private void stubItem(int itemId, String name, long price)
	{
		ItemComposition comp = mock(ItemComposition.class);
		lenient().when(comp.getName()).thenReturn(name);
		lenient().when(comp.isTradeable()).thenReturn(true);
		lenient().when(itemManager.getItemComposition(itemId)).thenReturn(comp);
		lenient().when(itemManager.getItemPrice(itemId)).thenReturn(price);
	}

	private void fireInv(int... idQtyPairs)
	{
		plugin.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, idQtyPairs)));
	}

	private ItemContainer mockContainer(int containerId, int... idQtyPairs)
	{
		ItemContainer container = mock(ItemContainer.class);
		when(container.getId()).thenReturn(containerId);
		Item[] items = new Item[idQtyPairs.length / 2];
		for (int i = 0; i < idQtyPairs.length; i += 2)
		{
			items[i / 2] = new Item(idQtyPairs[i], idQtyPairs[i + 1]);
		}
		when(container.getItems()).thenReturn(items);
		return container;
	}

	// ── Presence detection ───────────────────────────────────────────────

	@Test
	public void spawnDespawnAndLoading_toggleActive()
	{
		Assert.assertTrue(plugin.blastFurnaceTracker.isActive());
		plugin.blastFurnaceTracker.onObjectDespawn(ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE);
		Assert.assertFalse(plugin.blastFurnaceTracker.isActive());
		activate();
		plugin.blastFurnaceTracker.onGameStateChanged(GameState.LOADING);
		Assert.assertFalse(plugin.blastFurnaceTracker.isActive());
	}

	@Test
	public void unrelatedObjectSpawn_doesNotActivate()
	{
		plugin.blastFurnaceTracker.reset();
		plugin.blastFurnaceTracker.onObjectSpawn(12345);
		Assert.assertFalse(plugin.blastFurnaceTracker.isActive());
	}

	@Test
	public void gameObjectSpawnedEvent_reachesTracker()
	{
		plugin.blastFurnaceTracker.reset();

		GameObject conveyor = mock(GameObject.class);
		when(conveyor.getId()).thenReturn(ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE);
		GameObjectSpawned spawned = new GameObjectSpawned();
		spawned.setGameObject(conveyor);
		plugin.onGameObjectSpawned(spawned);
		Assert.assertTrue("The spawn subscriber activates the tracker",
			plugin.blastFurnaceTracker.isActive());

		GameObjectDespawned despawned = new GameObjectDespawned();
		despawned.setGameObject(conveyor);
		plugin.onGameObjectDespawned(despawned);
		Assert.assertFalse(plugin.blastFurnaceTracker.isActive());
	}

	// ── Coffer transfers ─────────────────────────────────────────────────

	@Test
	public void deposit_matchedToCofferIncrease_noExpense()
	{
		fireInv(ItemID.COINS, 100_000);
		fireInv(); // 100k leaves the inventory — held aside

		Assert.assertEquals("The deposit is a balance transfer, not a spend",
			0L, plugin.session.getTotalExpenses());

		plugin.blastFurnaceTracker.onTick(5_000, tick);
		BlastFurnaceTracker.Settlement s = plugin.blastFurnaceTracker.onTick(105_000, ++tick);

		Assert.assertEquals(0, s.cashSpendCoins);
		Assert.assertEquals(0, s.drainChargeGp);
		Assert.assertEquals("A matched deposit never books as an expense",
			0L, plugin.session.getTotalExpenses());
	}

	@Test
	public void depositRacingDrainTick_leftoverAccruesDrainNotSpend()
	{
		fireInv(ItemID.COINS, 40_000);
		fireInv(); // 40k leaves the inventory for the coffer — held aside

		plugin.blastFurnaceTracker.onTick(0, tick);
		// Same tick: the deposit lands but the furnace drains 12, so the
		// coffer rises by only 39,988.
		BlastFurnaceTracker.Settlement s = plugin.blastFurnaceTracker.onTick(40_000 - 12, ++tick);

		Assert.assertEquals(0, s.cashSpendCoins);
		Assert.assertEquals("The raced drain accrues below the 1,200 batch",
			0, s.drainChargeGp);
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());

		// The remainder was attributed to drain — nothing expires to Coins.
		BlastFurnaceTracker.Settlement later =
			plugin.blastFurnaceTracker.onTick(40_000 - 12, tick + 5);
		Assert.assertEquals(0, later.cashSpendCoins);

		plugin.blastFurnaceTracker.onLeftLoggedIn();
		CoinFlowSession.TrackedItem cofferRow =
			plugin.session.getTrackedExpenses().get(BlastFurnaceTracker.COFFER_ROW_ID);
		Assert.assertNotNull("The raced drain charges the coffer row", cofferRow);
		Assert.assertEquals(12L, cofferRow.getQuantity());
	}

	@Test
	public void depositLeftoverBeyondDrainCap_stillExpiresToSpend()
	{
		fireInv(ItemID.COINS, 40_500);
		fireInv(); // 40,500 leaves — 40k deposit + a 500 gp unrelated spend

		plugin.blastFurnaceTracker.onTick(0, tick);
		BlastFurnaceTracker.Settlement s = plugin.blastFurnaceTracker.onTick(40_000, ++tick);

		// Only the 24 gp drain cap is siphoned off; 476 stays held and
		// expires to a normal Coins spend.
		Assert.assertEquals(0, s.cashSpendCoins);
		BlastFurnaceTracker.Settlement later =
			plugin.blastFurnaceTracker.onTick(40_000, tick + 3);
		Assert.assertEquals(476, later.cashSpendCoins);
	}

	@Test
	public void withdrawal_matchedToCofferDecrease_noProfit()
	{
		fireInv();
		fireInv(ItemID.COINS, 50_000); // 50k returns from the coffer — held aside

		Assert.assertEquals("The withdrawal is a balance transfer, not profit",
			0L, plugin.session.getGrossProfit());

		plugin.blastFurnaceTracker.onTick(100_000, tick);
		BlastFurnaceTracker.Settlement s = plugin.blastFurnaceTracker.onTick(50_000, ++tick);

		Assert.assertEquals(0, s.drainChargeGp);
		Assert.assertEquals("A matched withdrawal never books as income",
			0L, plugin.session.getGrossProfit());
	}

	@Test
	public void withdrawalAcrossOneTickGap_stillMatched()
	{
		fireInv();
		plugin.blastFurnaceTracker.onTick(100_000, tick);
		fireInv(ItemID.COINS, 50_000); // held this tick, varbit lands next tick

		tick++; // one tick passes with no coffer event
		BlastFurnaceTracker.Settlement s = plugin.blastFurnaceTracker.onTick(50_000, ++tick);

		Assert.assertEquals(0, s.drainChargeGp);
		Assert.assertEquals(0, s.coinGainQty);
		Assert.assertEquals(0L, plugin.session.getGrossProfit());
	}

	// ── Drain ────────────────────────────────────────────────────────────

	@Test
	public void drain_accruesAndFlushesAtThreshold()
	{
		plugin.blastFurnaceTracker.onTick(100_000, tick);

		BlastFurnaceTracker.Settlement s = new BlastFurnaceTracker.Settlement();
		for (int i = 1; i <= 100; i++)
		{
			s = plugin.blastFurnaceTracker.onTick(100_000 - 12 * i, ++tick);
			Assert.assertEquals("Drain batches, it does not charge per tick",
				i < 100 ? 0 : 1_200, s.drainChargeGp);
		}
	}

	@Test
	public void drain_chargedToCofferRowOnGameTick()
	{
		final int[] coffer = {100_000};
		lenient().when(client.getVarbitValue(VarbitID.BLAST_FURNACE_COFFER))
			.thenAnswer(inv -> coffer[0]);
		fireInv(); // take the baseline so game ticks process

		for (int i = 0; i <= 101; i++)
		{
			coffer[0] -= 12;
			tick++;
			plugin.onGameTick(new GameTick());
		}

		CoinFlowSession.TrackedItem cofferRow =
			plugin.session.getTrackedExpenses().get(BlastFurnaceTracker.COFFER_ROW_ID);
		Assert.assertNotNull("The drain charges the synthetic coffer row", cofferRow);
		Assert.assertEquals(BlastFurnaceTracker.COFFER_ROW_NAME, cofferRow.getName());
		Assert.assertTrue(cofferRow.getQuantity() >= 1_200L);
	}

	@Test
	public void swallowedWithdrawal_cappedNotChargedAsDrain()
	{
		plugin.blastFurnaceTracker.onTick(1_000_000, tick);

		// A 100k withdrawal whose coin gain was swallowed by a re-baseline:
		// the coffer drops by 100k with no held gain — only the per-tick cap
		// of drain may book.
		plugin.blastFurnaceTracker.onTick(900_000, ++tick);

		// Keep draining normally; total drain charged must reflect the
		// capped 24 from the swallowed event plus 12/tick, not 100k.
		int charged = 0;
		for (int i = 1; i <= 100; i++)
		{
			charged += plugin.blastFurnaceTracker.onTick(900_000 - 12 * i, ++tick).drainChargeGp;
		}
		plugin.blastFurnaceTracker.onObjectDespawn(ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE);
		charged += plugin.blastFurnaceTracker.onTick(-1, ++tick).drainChargeGp;
		Assert.assertEquals("Drain is capped per elapsed tick, the rest is treated as withdrawal",
			24 + 1_200, charged);
	}

	@Test
	public void firstCofferRead_onlyBaselines()
	{
		BlastFurnaceTracker.Settlement s = plugin.blastFurnaceTracker.onTick(100_000, tick);
		Assert.assertEquals(0, s.cashSpendCoins);
		Assert.assertEquals(0, s.coinGainQty);
		Assert.assertEquals(0, s.drainChargeGp);
	}

	// ── Fallback paths ───────────────────────────────────────────────────

	@Test
	public void unmatchedCoinLoss_expiresToCashSpend()
	{
		fireInv(ItemID.COINS, 100_000);
		tick++;
		fireInv(ItemID.COINS, 97_500); // 2,500 foreman fee — held, never matches a coffer change

		plugin.blastFurnaceTracker.onTick(5_000, tick);
		BlastFurnaceTracker.Settlement s = plugin.blastFurnaceTracker.onTick(5_000, tick + 3);

		Assert.assertEquals("An unmatched held coin loss falls back to a cash spend",
			2_500, s.cashSpendCoins);
	}

	@Test
	public void coinPlusItemDiff_notHeld()
	{
		fireInv(ItemID.COINS, 100_000, ItemID.IRON_ORE, 1);
		fireInv(ItemID.COINS, 97_500); // coins + ore leave together

		Assert.assertEquals("A mixed diff keeps normal accounting — the coins book as spend",
			2_500L, plugin.session.getTotalExpenses());
		Assert.assertTrue("...while the ore is a free belt-load input",
			plugin.session.getTrackedExpenses().get(ItemID.IRON_ORE) == null);
	}

	@Test
	public void droppedCoins_notHeld()
	{
		fireInv(ItemID.COINS, 10_000);
		plugin.recentDropIntents.put(ItemID.COINS, client.getTickCount());
		fireInv(); // Drop-clicked coins keep the own-drop path

		Assert.assertEquals("A Drop-clicked coin loss is a drop expense, not a coffer deposit",
			10_000L, plugin.session.getTotalExpenses());
	}

	// ── Belt loads and bar collection ────────────────────────────────────

	@Test
	public void beltLoadThenCollection_netIsBarMinusInputs()
	{
		fireInv(ItemID.IRON_ORE, 27);
		fireInv(); // 27 ore onto the belt — no expense, no deduction
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());

		fireInv(ItemID.STEEL_BAR, 27); // bars from the dispenser

		Assert.assertEquals("Bars book as gains", 27L * 800L, plugin.session.getGrossProfit());
		Assert.assertEquals("Inputs are charged once, at collection",
			27L * 100L + 27L * 200L, plugin.session.getTotalExpenses());
		Assert.assertEquals(27L * 800L - 27L * 300L, plugin.session.getTotalProfit());
	}

	@Test
	public void oreLossAwayFromFurnace_keepsLegacyPath()
	{
		plugin.blastFurnaceTracker.onObjectDespawn(ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE);

		fireInv(ItemID.IRON_ORE, 27);
		fireInv(); // dropped/banked ore away from the furnace: no phantom charge either

		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
		Assert.assertEquals(0L, plugin.session.getGrossProfit());
	}

	// ── Dispenser rescue ─────────────────────────────────────────────────

	@Test
	public void rescue_matchingDispenserDecrease_creditsBarsAndInputs()
	{
		plugin.blastFurnaceTracker.onDispenserVarbit(VarbitID.BLAST_FURNACE_STEEL_BARS, 10, tick);
		plugin.blastFurnaceTracker.onDispenserVarbit(VarbitID.BLAST_FURNACE_STEEL_BARS, 0, ++tick);

		Map<Integer, Integer> gains = new HashMap<>();
		gains.put(ItemID.STEEL_BAR, 10);
		plugin.blastFurnaceTracker.rescueBars(gains, tick + 1);

		Assert.assertTrue("Rescued bars are removed from the baselined diff", gains.isEmpty());
		Assert.assertEquals(10L,
			plugin.session.getTrackedItems().get(ItemID.STEEL_BAR).getQuantity());
		Assert.assertEquals(10L,
			plugin.session.getTrackedExpenses().get(ItemID.IRON_ORE).getQuantity());
		Assert.assertEquals(10L,
			plugin.session.getTrackedExpenses().get(ItemID.COAL).getQuantity());
	}

	@Test
	public void rescue_noDispenserDecrease_nothingRescued()
	{
		// Bars straight out of the bank chest produce no dispenser varbit
		// decrease — they must never be rescued as furnace output.
		Map<Integer, Integer> gains = new HashMap<>();
		gains.put(ItemID.STEEL_BAR, 10);
		plugin.blastFurnaceTracker.rescueBars(gains, tick);

		Assert.assertEquals(10, (int) gains.get(ItemID.STEEL_BAR));
		Assert.assertEquals(0L, plugin.session.getGrossProfit());
	}

	@Test
	public void rescue_quantityNeverRescuedTwice()
	{
		plugin.blastFurnaceTracker.onDispenserVarbit(VarbitID.BLAST_FURNACE_STEEL_BARS, 10, tick);
		plugin.blastFurnaceTracker.onDispenserVarbit(VarbitID.BLAST_FURNACE_STEEL_BARS, 0, ++tick);

		Map<Integer, Integer> first = new HashMap<>();
		first.put(ItemID.STEEL_BAR, 10);
		plugin.blastFurnaceTracker.rescueBars(first, tick + 1);

		Map<Integer, Integer> second = new HashMap<>();
		second.put(ItemID.STEEL_BAR, 10);
		plugin.blastFurnaceTracker.rescueBars(second, tick + 1);

		Assert.assertEquals("The dispenser decrease is consumed by the first rescue",
			10, (int) second.get(ItemID.STEEL_BAR));
		Assert.assertEquals(10L,
			plugin.session.getTrackedItems().get(ItemID.STEEL_BAR).getQuantity());
	}

	// ── Reset / boundary behaviour ───────────────────────────────────────

	@Test
	public void sessionReset_discardsDrainButKeepsPresence()
	{
		plugin.blastFurnaceTracker.onTick(100_000, tick);
		plugin.blastFurnaceTracker.onTick(99_000, ++tick); // 1,000 drain accrued

		plugin.blastFurnaceTracker.resetSession();

		Assert.assertTrue("Still at the furnace after a session reset",
			plugin.blastFurnaceTracker.isActive());
		BlastFurnaceTracker.Settlement s = plugin.blastFurnaceTracker.onTick(99_000, ++tick);
		Assert.assertEquals("Accrued drain belonged to the old session", 0, s.drainChargeGp);
	}

	@Test
	public void fullReset_clearsPresence()
	{
		plugin.blastFurnaceTracker.reset();
		Assert.assertFalse(plugin.blastFurnaceTracker.isActive());
	}

	@Test
	public void leavingFurnace_flushesAccruedDrain()
	{
		plugin.blastFurnaceTracker.onTick(100_000, tick);
		// 50 ticks of ordinary drain = 600 gp accrued, below the batch threshold
		for (int i = 1; i <= 50; i++)
		{
			plugin.blastFurnaceTracker.onTick(100_000 - 12 * i, ++tick);
		}

		plugin.blastFurnaceTracker.onObjectDespawn(ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE);
		BlastFurnaceTracker.Settlement s = plugin.blastFurnaceTracker.onTick(-1, ++tick);

		Assert.assertEquals("The accrued drain is charged on the way out", 600, s.drainChargeGp);
	}
}
