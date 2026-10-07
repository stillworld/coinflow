package com.coinflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import static org.mockito.Mockito.mock;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static com.coinflow.TestHelpers.gains;
import static com.coinflow.TestHelpers.snapshot;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CoinFlowPlugin} event handler state machine.
 *
 * All dependencies and plugin state are set directly via package-private fields
 * without any reflection.
 *
 * Covers:
 * - Baseline initialization (login, first inventory event)
 * - Interface suppression and rebaseline after bank/GE close (Bug #2 regression)
 * - Equipment unequip suppression (Bug #1 regression)
 * - Goal notification behaviour
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class CoinFlowPluginEventTest
{
	// ── Mocks ─────────────────────────────────────────────────────────────

	@Mock Client client;
	@Mock CoinFlowConfig config;
	@Mock ItemManager itemManager;
	@Mock OverlayManager overlayManager;
	@Mock CoinFlowGoldDropOverlay goldDropOverlay;
	@Mock Notifier notifier;
	@Mock ClientThread clientThread;
	@Mock ConfigManager configManager;
	@Mock net.runelite.client.eventbus.EventBus eventBus;
	@Mock CoinFlowOverlay overlay;

	/** Plugin under test — dependencies injected directly. */
	private CoinFlowPlugin plugin;

	// ── Setup ──────────────────────────────────────────────────────────────

	@Before
	public void setUp()
	{
		plugin = new CoinFlowPlugin();

		// Inject mock dependencies
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

		// Initialise session and auxiliary state (mirrors startUp)
		plugin.session = CoinFlowSession.createNew();
		plugin.snapshotInitialized = false;
		plugin.setTrackingSuppressed(false);
		plugin.setNeedsRebaseline(false);
		plugin.goalCompletedNotified = false;
		plugin.getOpenSuppressedInterfaces().clear();
		plugin.recentlyUnequippedItems.clear();
		plugin.recentlyDroppedItems.clear();
		plugin.recentlyDroppedOwnedItems.clear();
		plugin.ignoredItemNames = new HashSet<>();

		// Default config stubs
		when(config.trackSpent()).thenReturn(true);
		when(config.idleTimeoutMinutes()).thenReturn(5);
		when(config.goalAmount()).thenReturn("");
		when(config.goalName()).thenReturn("");
		when(config.notifyOnGoal()).thenReturn(true);
		when(config.includeAfkTime()).thenReturn(false);
		when(config.showGoldDrops()).thenReturn(true);
		when(config.goldDropMinThreshold()).thenReturn(0);
		when(client.getMouseIdleTicks()).thenReturn(1000);
		when(client.getKeyboardIdleTicks()).thenReturn(1000);
		// resetSession runs inline when called on the client thread; tests run on one
		when(client.isClientThread()).thenReturn(true);

		// Default coins stubbing
		int coinsId = net.runelite.api.gameval.ItemID.COINS;
		when(itemManager.canonicalize(coinsId)).thenReturn(coinsId);
		when(itemManager.getItemPrice(coinsId)).thenReturn(1L);
		ItemComposition coinsComp = org.mockito.Mockito.mock(ItemComposition.class);
		when(coinsComp.getName()).thenReturn("Coins");
		when(itemManager.getItemComposition(coinsId)).thenReturn(coinsComp);
	}

	// ── Baseline Initialization ───────────────────────────────────────────

	/**
	 * First inventory event after startup or hop should establish the baseline snapshot
	 * without recording any profit (Bug #1: login ghost gains).
	 */
	@Test
	public void firstInventoryEvent_takesBaseline_recordsNoProfit()
	{
		ItemContainer container = mockContainer(InventoryID.INV, 555, 100);
		ItemContainerChanged event = new ItemContainerChanged(InventoryID.INV, container);

		plugin.onItemContainerChanged(event);

		Assert.assertTrue(plugin.snapshotInitialized);
		Assert.assertEquals("First inventory event must not record gains", 0L, plugin.session.getTotalProfit());
	}

	@Test
	public void secondInventoryEvent_withGains_recordsProfit()
	{
		plugin.previousInventorySnapshot = snapshot();
		plugin.snapshotInitialized = true;

		stubTrackableItem(555, "Iron ore", 200L);

		ItemContainer container = mockContainer(InventoryID.INV, 555, 3);
		ItemContainerChanged event = new ItemContainerChanged(InventoryID.INV, container);

		plugin.onItemContainerChanged(event);

		Assert.assertEquals(3 * 200L, plugin.session.getTotalProfit());
	}

	@Test
	public void gameStateLoggedIn_setsNeedsRebaseline()
	{
		plugin.snapshotInitialized = true;
		plugin.setNeedsRebaseline(false);

		GameStateChanged event = new GameStateChanged();
		event.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(event);

		Assert.assertTrue(plugin.isNeedsRebaseline());
	}

	@Test
	public void gameStateHopping_pausesTracking()
	{
		plugin.snapshotInitialized = true;

		GameStateChanged event = new GameStateChanged();
		event.setGameState(GameState.HOPPING);
		plugin.onGameStateChanged(event);

		Assert.assertFalse(plugin.snapshotInitialized);
	}

	@Test
	public void gameStateTeleportLoading_preservesSnapshot()
	{
		plugin.snapshotInitialized = true;
		plugin.setNeedsRebaseline(false);
		plugin.setPreviousGameState(GameState.LOGGED_IN);

		GameStateChanged loadingEvent = new GameStateChanged();
		loadingEvent.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(loadingEvent);

		Assert.assertTrue("snapshotInitialized must be preserved during LOADING", plugin.snapshotInitialized);
		Assert.assertFalse("needsRebaseline must remain false during LOADING", plugin.isNeedsRebaseline());

		GameStateChanged loggedInEvent = new GameStateChanged();
		loggedInEvent.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(loggedInEvent);

		Assert.assertTrue("snapshotInitialized must remain true after LOADING -> LOGGED_IN", plugin.snapshotInitialized);
		Assert.assertFalse("needsRebaseline must NOT be set on returning from LOADING", plugin.isNeedsRebaseline());
	}

	@Test
	public void gameStateFreshLoginAfterLoading_stillRebaselines()
	{
		plugin.snapshotInitialized = false;
		plugin.setNeedsRebaseline(false);

		GameStateChanged loginScreen = new GameStateChanged();
		loginScreen.setGameState(GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(loginScreen);

		GameStateChanged loading = new GameStateChanged();
		loading.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(loading);

		GameStateChanged loggedIn = new GameStateChanged();
		loggedIn.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(loggedIn);

		Assert.assertTrue("needsRebaseline must be set on fresh login after LOADING", plugin.isNeedsRebaseline());
	}

	@Test
	public void teleportTablet_teleportToHouse_trackedAsSupplyExpense()
	{
		int tabId = 8013;
		stubTrackableItem(tabId, "Teleport to house", 850L);

		// Baseline: player has 1 house tab
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, tabId, 1)
		));
		Assert.assertTrue(plugin.snapshotInitialized);
		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());

		// Player breaks tab and teleports to POH: LOGGED_IN -> LOADING -> LOGGED_IN
		plugin.setPreviousGameState(GameState.LOGGED_IN);
		GameStateChanged loading = new GameStateChanged();
		loading.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(loading);

		GameStateChanged loggedIn = new GameStateChanged();
		loggedIn.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(loggedIn);

		// Inventory now has 0 house tabs
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV)
		));

		Assert.assertEquals("Supply expense should equal tab cost", 850L, plugin.session.getTotalExpenses());
		Assert.assertEquals("Net profit should be -850 gp", -850L, plugin.session.getNetProfit());
		Assert.assertTrue(plugin.session.getTrackedExpenses().containsKey(tabId));
		Assert.assertEquals(1, plugin.session.getTrackedExpenses().get(tabId).getQuantity());
	}

	@Test
	public void teleportTablet_varrockTeleport_trackedAsSupplyExpense()
	{
		int tabId = 8007;
		stubTrackableItem(tabId, "Varrock teleport", 600L);

		// Baseline: player has 5 Varrock teleport tablets
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, tabId, 5)
		));

		// Player uses 1 Varrock tab: inventory drops to 4 across loading screen
		plugin.setPreviousGameState(GameState.LOGGED_IN);
		GameStateChanged loading = new GameStateChanged();
		loading.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(loading);

		GameStateChanged loggedIn = new GameStateChanged();
		loggedIn.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(loggedIn);

		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, tabId, 4)
		));

		Assert.assertEquals("Supply expense should equal 600 gp", 600L, plugin.session.getTotalExpenses());
		Assert.assertEquals("Net profit should be -600 gp", -600L, plugin.session.getNetProfit());
		Assert.assertTrue(plugin.session.getTrackedExpenses().containsKey(tabId));
		Assert.assertEquals(1, plugin.session.getTrackedExpenses().get(tabId).getQuantity());
	}

	@Test
	public void teleportScroll_ardeaglaisTeleportScroll_trackedAsSupplyExpense()
	{
		int scrollId = 34033;
		stubTrackableItem(scrollId, "Ardeaglais teleport scroll", 32_000L);

		// Baseline: player has 3 Ardeaglais teleport scrolls
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, scrollId, 3)
		));

		// Player consumes 1 scroll: inventory drops to 2 across loading screen
		plugin.setPreviousGameState(GameState.LOGGED_IN);
		GameStateChanged loading = new GameStateChanged();
		loading.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(loading);

		GameStateChanged loggedIn = new GameStateChanged();
		loggedIn.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(loggedIn);

		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, scrollId, 2)
		));

		Assert.assertEquals("Supply expense should equal 32,000 gp", 32_000L, plugin.session.getTotalExpenses());
		Assert.assertEquals("Net profit should be -32,000 gp", -32_000L, plugin.session.getNetProfit());
		Assert.assertTrue(plugin.session.getTrackedExpenses().containsKey(scrollId));
		Assert.assertEquals(1, plugin.session.getTrackedExpenses().get(scrollId).getQuantity());
	}

	@Test
	public void teleportScroll_colossalWyrmTeleportScroll_trackedAsSupplyExpense()
	{
		int scrollId = 30140;
		stubTrackableItem(scrollId, "Colossal wyrm teleport scroll", 2_500L);

		// Baseline: player has 10 Colossal wyrm teleport scrolls
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, scrollId, 10)
		));

		// Player consumes 1 scroll: inventory drops to 9 across loading screen
		plugin.setPreviousGameState(GameState.LOGGED_IN);
		GameStateChanged loading = new GameStateChanged();
		loading.setGameState(GameState.LOADING);
		plugin.onGameStateChanged(loading);

		GameStateChanged loggedIn = new GameStateChanged();
		loggedIn.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(loggedIn);

		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, scrollId, 9)
		));

		Assert.assertEquals("Supply expense should equal 2,500 gp", 2_500L, plugin.session.getTotalExpenses());
		Assert.assertEquals("Net profit should be -2,500 gp", -2_500L, plugin.session.getNetProfit());
		Assert.assertTrue(plugin.session.getTrackedExpenses().containsKey(scrollId));
		Assert.assertEquals(1, plugin.session.getTrackedExpenses().get(scrollId).getQuantity());
	}

	@Test
	public void playerDeath_setsNeedsRebaseline()
	{
		Player localPlayer = org.mockito.Mockito.mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		plugin.setNeedsRebaseline(false);

		plugin.onActorDeath(new ActorDeath(localPlayer));

		Assert.assertTrue("needsRebaseline must be set when local player dies", plugin.isNeedsRebaseline());
	}

	@Test
	public void otherActorDeath_doesNotSetNeedsRebaseline()
	{
		Player localPlayer = org.mockito.Mockito.mock(Player.class);
		Player otherPlayer = org.mockito.Mockito.mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		plugin.setNeedsRebaseline(false);

		plugin.onActorDeath(new ActorDeath(otherPlayer));

		Assert.assertFalse("needsRebaseline must not be set when other actor dies", plugin.isNeedsRebaseline());
	}

	@Test
	public void gravestoneRetrieval_suppressesTracking()
	{
		WidgetLoaded event = new WidgetLoaded();
		event.setGroupId(InterfaceID.GRAVESTONE_RETRIEVAL);
		plugin.onWidgetLoaded(event);

		Assert.assertTrue(plugin.isTrackingSuppressed());

		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.GRAVESTONE_RETRIEVAL, 0, false));
		Assert.assertFalse(plugin.isTrackingSuppressed());
		Assert.assertTrue(plugin.isNeedsRebaseline());
	}

	// ── Death Tracking ─────────────────────────────────────────────────

	private void stubLiveContainers(ItemContainer inv, ItemContainer worn)
	{
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(worn);
	}

	private Player mockLocalPlayer()
	{
		Player p = org.mockito.Mockito.mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(p);
		return p;
	}

	private void clickTakeOn(int itemId)
	{
		net.runelite.api.MenuEntry entry = mock(net.runelite.api.MenuEntry.class);
		when(entry.getOption()).thenReturn("Take");
		when(entry.getIdentifier()).thenReturn(itemId);
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));
	}

	/**
	 * Runs the standard death sequence: baseline, die at {@code deathTick},
	 * containers empty at {@code clearTick}, settle on a GameTick at
	 * {@code settleTick}. Returns the local player mock.
	 */
	private Player dieAndSettle(int deathTick, int clearTick, int settleTick,
		ItemContainer postInv, ItemContainer postWorn)
	{
		Player player = mockLocalPlayer();
		when(client.getTickCount()).thenReturn(deathTick);
		plugin.onActorDeath(new ActorDeath(player));

		when(client.getTickCount()).thenReturn(clearTick);
		stubLiveContainers(postInv, postWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, postInv));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, postWorn));

		when(client.getTickCount()).thenReturn(settleTick);
		plugin.onGameTick(new GameTick());
		return player;
	}

	@Test
	public void playerDeath_itemLoss_chargedToDeathRow()
	{
		int whipId = 4151;
		int sharkId = 385;
		int coinsId = net.runelite.api.gameval.ItemID.COINS;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		stubTrackableItem(sharkId, "Shark", 800L, "Eat");
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1, sharkId, 5, coinsId, 50_000);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, emptyWorn));

		dieAndSettle(100, 103, 106, mockContainer(InventoryID.INV), emptyWorn);

		long expected = 1_500_000L + 5 * 800L + 50_000L;
		CoinFlowSession.TrackedItem deathRow =
			plugin.session.getTrackedExpenses().get(DeathTracker.DEATH_ROW_ID);
		Assert.assertNotNull("Death row must exist", deathRow);
		Assert.assertEquals(expected, deathRow.getTotalValue());
		Assert.assertEquals(expected, plugin.session.getTotalExpenses());
		Assert.assertNull("lost sharks must fold into the Death row, not a supply row",
			plugin.session.getTrackedExpenses().get(sharkId));
		Assert.assertEquals("lost items are not profit", 0L, plugin.session.getGrossProfit());
		Assert.assertFalse(plugin.deathTracker.isPending());
		Assert.assertEquals(1, plugin.deathTracker.ledgerQuantity(whipId));
		Assert.assertEquals(5, plugin.deathTracker.ledgerQuantity(sharkId));
	}

	@Test
	public void playerDeath_freezeInventoryClearAfterLoginBaseline_noDoubleBooking()
	{
		// Regression: LOGGED_IN takeBaseline() runs before the post-death
		// container clear arrives; the clear must still not reach the pipeline.
		int whipId = 4151;
		int sharkId = 385;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		stubTrackableItem(sharkId, "Shark", 800L, "Eat");
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1, sharkId, 3);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, emptyWorn));

		Player player = mockLocalPlayer();
		when(client.getTickCount()).thenReturn(100);
		plugin.onActorDeath(new ActorDeath(player));

		// Respawn baseline taken while the clear is still in flight
		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		stubLiveContainers(emptyInv, emptyWorn);
		plugin.takeBaseline();

		// The container clear arrives afterwards — frozen, never diffed
		when(client.getTickCount()).thenReturn(103);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyInv));

		Assert.assertEquals("no supply expenses during pending death", 0L,
			plugin.session.getTotalExpenses());
		Assert.assertTrue("clear must not enter drop bookkeeping",
			plugin.recentlyDroppedOwnedItems.isEmpty());
		Assert.assertTrue(plugin.recentlyDroppedItems.isEmpty());

		when(client.getTickCount()).thenReturn(105);
		plugin.onGameTick(new GameTick());

		Assert.assertEquals(1_500_000L + 3 * 800L, plugin.session.getTotalExpenses());
		Assert.assertEquals(0L, plugin.session.getGrossProfit());
	}

	@Test
	public void playerDeath_safeDeath_recordsNothing()
	{
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		Player player = mockLocalPlayer();
		when(client.getTickCount()).thenReturn(100);
		plugin.onActorDeath(new ActorDeath(player));

		// Nothing changes after respawn (safe death); timeout settles it
		when(client.getTickCount()).thenReturn(121);
		plugin.onGameTick(new GameTick());

		Assert.assertFalse(plugin.deathTracker.isPending());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
		Assert.assertFalse(plugin.deathTracker.hasLedger());
	}

	@Test
	public void playerDeath_wornAmmo_notExpensedAsFiredAmmo()
	{
		int arrowId = 892;
		stubTrackableItem(arrowId, "Rune arrow", 200L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer worn = mockContainer(InventoryID.WORN, arrowId, 1_000);
		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		stubLiveContainers(emptyInv, worn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyInv));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, worn));

		dieAndSettle(100, 103, 106, emptyInv, mockContainer(InventoryID.WORN));

		CoinFlowSession.TrackedItem deathRow =
			plugin.session.getTrackedExpenses().get(DeathTracker.DEATH_ROW_ID);
		Assert.assertNotNull(deathRow);
		Assert.assertEquals("ammo loss folds into the Death row", 200_000L, deathRow.getTotalValue());
		Assert.assertNull("worn ammo must not also expense as fired",
			plugin.session.getTrackedExpenses().get(arrowId));
	}

	@Test
	public void playerDeath_chargeVarbitChangeDuringPending_noChargeExpense()
	{
		when(config.trackWeaponCharges()).thenReturn(true);
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		Player player = mockLocalPlayer();
		when(client.getTickCount()).thenReturn(100);
		plugin.onActorDeath(new ActorDeath(player));

		// Serpentine helm charge varbit drops while the death is pending
		when(client.getVarbitValue(net.runelite.api.gameval.VarbitID.CHARGES_SERPENTINE_HELM_QUANTITY))
			.thenReturn(400);
		VarbitChanged varbit = new VarbitChanged();
		varbit.setVarbitId(net.runelite.api.gameval.VarbitID.CHARGES_SERPENTINE_HELM_QUANTITY);
		varbit.setValue(400);
		plugin.onVarbitChanged(varbit);

		when(client.getTickCount()).thenReturn(103);
		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		stubLiveContainers(emptyInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyInv));
		when(client.getTickCount()).thenReturn(106);
		plugin.onGameTick(new GameTick());

		Assert.assertEquals("only the Death row, no charge expense",
			1, plugin.session.getTrackedExpenses().size());
		Assert.assertEquals(1_500_000L, plugin.session.getTotalExpenses());
	}

	@Test
	public void gravestoneReclaim_reversesDeathRow()
	{
		int whipId = 4151;
		int sharkId = 385;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		stubTrackableItem(sharkId, "Shark", 800L, "Eat");
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1, sharkId, 5);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, emptyWorn));

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);
		Assert.assertEquals(1_500_000L + 4_000L, plugin.session.getTotalExpenses());

		// Open gravestone retrieval (baseline = empty containers)
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.GRAVESTONE_RETRIEVAL);
		plugin.onWidgetLoaded(open);
		Assert.assertTrue(plugin.deathTracker.hasRetrievalBaseline());

		// Reclaim whip + 2 sharks into inventory
		ItemContainer reclaimedInv = mockContainer(InventoryID.INV, whipId, 1, sharkId, 2);
		stubLiveContainers(reclaimedInv, emptyWorn);
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.GRAVESTONE_RETRIEVAL, 0, false));
		plugin.onGameTick(new GameTick());

		// 1.5m whip reclaimed at a gravestone costs the 10k mid-tier fee
		Assert.assertEquals("reclaimed value reversed off the Death row, fee applied",
			3 * 800L + 10_000L, plugin.session.getTotalExpenses());
		Assert.assertEquals(0L, plugin.session.getGrossProfit());
		Assert.assertEquals(3, plugin.deathTracker.ledgerQuantity(sharkId));
		Assert.assertEquals(0, plugin.deathTracker.ledgerQuantity(whipId));
	}

	@Test
	public void reclaim_settlesOnSceneLoad_withoutWidgetClosed()
	{
		// Death's Office teleports the player out — the suppressed set is
		// cleared by the game state transition, no WidgetClosed fires.
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);

		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.DEATH_OFFICE);
		plugin.onWidgetLoaded(open);
		Assert.assertTrue(plugin.deathTracker.hasRetrievalBaseline());

		// Reclaim, then a scene load clears the interface without closing it
		ItemContainer reclaimedInv = mockContainer(InventoryID.INV, whipId, 1);
		stubLiveContainers(reclaimedInv, emptyWorn);
		GameStateChanged loggedIn = new GameStateChanged();
		loggedIn.setGameState(GameState.LOGGED_IN);
		plugin.onGameStateChanged(loggedIn);
		Assert.assertFalse(plugin.interfaceTracker.isDeathRetrievalOpen());

		plugin.onGameTick(new GameTick());
		Assert.assertEquals("5% office fee on the reclaimed 1.5m whip",
			75_000L, plugin.session.getTotalExpenses());
		Assert.assertEquals(0L, plugin.session.getGrossProfit());
	}

	@Test
	public void reclaim_inventoryFee_addedToDeathRow()
	{
		int whipId = 4151;
		int coinsId = net.runelite.api.gameval.ItemID.COINS;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer keptCoinsWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, keptCoinsWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		// Death keeps 100k coins in inventory, loses the whip
		ItemContainer postInv = mockContainer(InventoryID.INV, coinsId, 100_000);
		dieAndSettle(100, 103, 106, postInv, keptCoinsWorn);
		Assert.assertEquals(1_500_000L, plugin.session.getTotalExpenses());

		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.GRAVESTONE_RETRIEVAL);
		plugin.onWidgetLoaded(open);

		// Reclaim whip, pay 50k from the kept coin stack
		stubLiveContainers(mockContainer(InventoryID.INV, whipId, 1, coinsId, 50_000), keptCoinsWorn);
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.GRAVESTONE_RETRIEVAL, 0, false));
		plugin.onGameTick(new GameTick());

		Assert.assertEquals("reclaim fee folded into the Death row",
			50_000L, plugin.session.getTotalExpenses());
	}

	@Test
	public void deathsOfficeReclaim_bankPaidFee_addedToDeathRow()
	{
		// Death's Office charges 5% per item over 100k, deducted from the
		// coffer or the bank — invisible to the inventory diff, so the fee is
		// computed from the recovered item's ledger price.
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);
		Assert.assertEquals(1_500_000L, plugin.session.getTotalExpenses());

		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.DEATH_OFFICE);
		plugin.onWidgetLoaded(open);

		// Reclaim with zero inventory-coin delta — fee charged to the bank
		stubLiveContainers(mockContainer(InventoryID.INV, whipId, 1), emptyWorn);
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.DEATH_OFFICE, 0, false));
		plugin.onGameTick(new GameTick());

		Assert.assertEquals("1.5m reversed, 5% office fee remains",
			75_000L, plugin.session.getTotalExpenses());
		Assert.assertEquals(0, plugin.deathTracker.ledgerQuantity(whipId));
	}

	@Test
	public void deathsOfficeReclaim_itemToBank_chatFeeApplied()
	{
		// Live bug: reclaiming with a full inventory sends the item straight
		// to the bank — nothing enters inv/worn, so no gain is matched and no
		// computed/observed fee exists. The "Death charges you X coins."
		// message is the only signal and must still land on the Death row.
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);
		Assert.assertEquals(1_500_000L, plugin.session.getTotalExpenses());

		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.DEATH_OFFICE);
		plugin.onWidgetLoaded(open);

		// Fee charged while the interface is open; the item went to the bank,
		// so the close settles with an empty diff.
		when(client.getTickCount()).thenReturn(200);
		plugin.onChatMessage(new ChatMessage(null, net.runelite.api.ChatMessageType.GAMEMESSAGE,
			"", "Death charges you 8,915 coins.", "", 0));
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.DEATH_OFFICE, 0, false));
		plugin.onGameTick(new GameTick());

		Assert.assertEquals("chat-reported fee must land even with no inv diff",
			1_500_000L + 8_915L, plugin.session.getTotalExpenses());
	}

	@Test
	public void postSettleGain_withinGrace_reversesDeathRow()
	{
		// Live bug: the reclaimed item landed in the gap between the close
		// settle and the re-baseline — it was swallowed as baseline noise, so
		// neither the reversal nor its fee applied. Post-close grace makes
		// ledger-matching gains count as recovery without a Take click.
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);
		Assert.assertEquals(1_500_000L, plugin.session.getTotalExpenses());

		// Interface opens and closes with nothing recovered yet.
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.DEATH_OFFICE);
		plugin.onWidgetLoaded(open);
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.DEATH_OFFICE, 0, false));
		when(client.getTickCount()).thenReturn(200);
		plugin.onGameTick(new GameTick());
		Assert.assertEquals("nothing settled", 1_500_000L, plugin.session.getTotalExpenses());

		// The whip arrives late, inside the re-baseline window after close —
		// no Take click, but the grace window still matches it as recovery.
		when(client.getTickCount()).thenReturn(203);
		ItemContainer lateInv = mockContainer(InventoryID.INV, whipId, 1);
		stubLiveContainers(lateInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, lateInv));

		Assert.assertEquals("late reclaim reversed the Death row", 0L, plugin.session.getTotalExpenses());
		Assert.assertEquals("late gain was not counted as loot", 0L, plugin.session.getGrossProfit());
		Assert.assertEquals(0, plugin.deathTracker.ledgerQuantity(whipId));
	}

	@Test
	public void directGravestoneClaim_reversesDeathRow()
	{
		// Live bug: a free gravestone claim delivered items straight into
		// inventory by script — no retrieval interface opened, no Take click
		// fired, so the reclaim was counted as loot income. The
		// "You successfully retrieved ... gravestone." message is the only
		// signal; it opens the unconditional recovery window.
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);
		Assert.assertEquals(1_500_000L, plugin.session.getTotalExpenses());

		when(client.getTickCount()).thenReturn(200);
		plugin.onChatMessage(new ChatMessage(null, net.runelite.api.ChatMessageType.GAMEMESSAGE,
			"", "You successfully retrieved everything from your gravestone.", "", 0));
		ItemContainer reclaimedInv = mockContainer(InventoryID.INV, whipId, 1);
		stubLiveContainers(reclaimedInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, reclaimedInv));

		Assert.assertEquals("grave claim reversed the Death row", 0L, plugin.session.getTotalExpenses());
		Assert.assertEquals("reclaimed item was not counted as loot", 0L, plugin.session.getGrossProfit());
		Assert.assertEquals(0, plugin.deathTracker.ledgerQuantity(whipId));
	}

	@Test
	public void gravestoneGain_withoutRetrievalMessage_staysIncome()
	{
		// Without the claim message a ledger-matching gain still requires a
		// Take click — a coincidental same-id pickup is ordinary loot.
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);
		Assert.assertEquals(1_500_000L, plugin.session.getTotalExpenses());

		when(client.getTickCount()).thenReturn(200);
		ItemContainer lootInv = mockContainer(InventoryID.INV, whipId, 1);
		stubLiveContainers(lootInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, lootInv));

		Assert.assertEquals("Death row still stands", 1_500_000L, plugin.session.getTotalExpenses());
		Assert.assertEquals(1, plugin.deathTracker.ledgerQuantity(whipId));
	}

	@Test
	public void deathFeeMessage_afterClose_appliesDeferred()
	{
		// The fee message can arrive after the reclaim interface already
		// closed and settled — it must still land on the Death row.
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);
		Assert.assertEquals(1_500_000L, plugin.session.getTotalExpenses());

		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.DEATH_OFFICE);
		plugin.onWidgetLoaded(open);
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.DEATH_OFFICE, 0, false));
		when(client.getTickCount()).thenReturn(200);
		plugin.onGameTick(new GameTick());

		// Fee message lands a tick after the settle — it ages past the defer
		// window and applies on its own.
		when(client.getTickCount()).thenReturn(201);
		plugin.onChatMessage(new ChatMessage(null, net.runelite.api.ChatMessageType.GAMEMESSAGE,
			"", "Death charges you 8,915 coins.", "", 0));
		plugin.onGameTick(new GameTick());
		Assert.assertEquals("still deferring", 1_500_000L, plugin.session.getTotalExpenses());
		when(client.getTickCount()).thenReturn(203);
		plugin.onGameTick(new GameTick());
		Assert.assertEquals(1_500_000L + 8_915L, plugin.session.getTotalExpenses());
	}

	@Test
	public void wildernessPickup_withTakeClick_reversesDeathRow()
	{
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);

		// Player walks back and Takes their whip off the ground
		when(client.getTickCount()).thenReturn(120);
		clickTakeOn(whipId);
		stubLiveContainers(mockContainer(InventoryID.INV, whipId, 1), emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV, mockContainer(InventoryID.INV, whipId, 1)));

		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
		Assert.assertEquals("recovery is not loot", 0L, plugin.session.getGrossProfit());
		Assert.assertFalse(plugin.deathTracker.hasLedger());
	}

	@Test
	public void wildernessGain_withoutTakeClick_countsAsLoot()
	{
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);

		// A whip appears with no Take click (someone else's drop / new loot)
		when(client.getTickCount()).thenReturn(120);
		stubLiveContainers(mockContainer(InventoryID.INV, whipId, 1), emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV, mockContainer(InventoryID.INV, whipId, 1)));

		Assert.assertEquals("same-id gain without a Take click is loot",
			1_500_000L, plugin.session.getGrossProfit());
		Assert.assertEquals(1_500_000L, plugin.session.getTotalExpenses());
		Assert.assertTrue("ledger keeps the unrecovered loss", plugin.deathTracker.hasLedger());
	}

	@Test
	public void lostSessionGain_markedConsumed_restoredOnRecovery()
	{
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		// Session gain: whip looted mid-session
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(mockContainer(InventoryID.INV), emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));
		stubLiveContainers(mockContainer(InventoryID.INV, whipId, 1), emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV, mockContainer(InventoryID.INV, whipId, 1)));
		Assert.assertEquals(1_500_000L, plugin.session.getGrossProfit());
		Assert.assertEquals(1, plugin.session.getTrackedItems().get(whipId).getRemainingQuantity());

		// Die with the whip
		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);

		Assert.assertEquals("lost session gain marked consumed",
			0, plugin.session.getTrackedItems().get(whipId).getRemainingQuantity());

		// Reclaim it from the gravestone
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.GRAVESTONE_RETRIEVAL);
		plugin.onWidgetLoaded(open);
		stubLiveContainers(mockContainer(InventoryID.INV, whipId, 1), emptyWorn);
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.GRAVESTONE_RETRIEVAL, 0, false));
		plugin.onGameTick(new GameTick());

		Assert.assertEquals("gravestone tier fee on the 1.5m whip remains",
			10_000L, plugin.session.getTotalExpenses());
		Assert.assertEquals("recovered gain becomes deductible again",
			1, plugin.session.getTrackedItems().get(whipId).getRemainingQuantity());
	}

	@Test
	public void trackSpentOff_deathRecordsNothing()
	{
		when(config.trackSpent()).thenReturn(false);
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);

		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
		Assert.assertFalse("no ledger without an expense to reverse", plugin.deathTracker.hasLedger());
		Assert.assertEquals(0L, plugin.session.getGrossProfit());
	}

	@Test
	public void deathLedger_survivesLogout_clearedOnSessionReset()
	{
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(0);

		ItemContainer fullInv = mockContainer(InventoryID.INV, whipId, 1);
		ItemContainer emptyWorn = mockContainer(InventoryID.WORN);
		stubLiveContainers(fullInv, emptyWorn);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fullInv));

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		dieAndSettle(100, 103, 106, emptyInv, emptyWorn);
		Assert.assertTrue(plugin.deathTracker.hasLedger());

		// Logout — the ledger survives so a post-relog reclaim still reverses
		GameStateChanged logout = new GameStateChanged();
		logout.setGameState(GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(logout);
		Assert.assertTrue(plugin.deathTracker.hasLedger());

		// Session reset clears it
		plugin.resetSession();
		Assert.assertFalse(plugin.deathTracker.hasLedger());
	}

	// ── Interface Suppression (Bug #2 regression) ────────────────────────

	@Test
	public void bankOpen_suppressesTracking()
	{
		WidgetLoaded event = new WidgetLoaded();
		event.setGroupId(InterfaceID.BANKMAIN);
		plugin.onWidgetLoaded(event);

		Assert.assertTrue(plugin.isTrackingSuppressed());
	}

	/**
	 * Bug #2 regression: closing the last suppressed interface MUST set needsRebaseline.
	 * Without this fix, the next inventory event would diff against the pre-bank snapshot.
	 */
	@Test
	public void bankClose_setsNeedsRebaseline()
	{
		// Open bank first to populate openSuppressedInterfaces
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.BANKMAIN);
		plugin.onWidgetLoaded(open);

		// Now close it
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 0, false));

		Assert.assertTrue("needsRebaseline must be set after suppressed interface closes",
			plugin.isNeedsRebaseline());
		Assert.assertFalse("trackingSuppressed must be cleared",
			plugin.isTrackingSuppressed());
	}

	/**
	 * Bug #2 regression: items moved through bank should not cause phantom gains.
	 */
	@Test
	public void noPhantomGains_afterBankClose()
	{
		// Arrange: baseline has 0 of item 555
		plugin.previousInventorySnapshot = snapshot();
		plugin.snapshotInitialized = true;

		// Open bank
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.BANKMAIN);
		plugin.onWidgetLoaded(open);

		// While bank is open, inventory changes (items moved from bank) — suppressed
		ItemContainer withBankItems = mockContainer(InventoryID.INV, 555, 100);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, withBankItems));

		// Close bank → triggers needsRebaseline
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 0, false));

		// Next inventory event: fires after close with same 100 items → should baseline, not diff
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, withBankItems));

		// Assert: no profit from the bank transfer
		Assert.assertEquals("Bank items must not count as profit", 0L, plugin.session.getTotalProfit());
		Assert.assertFalse("needsRebaseline should be consumed", plugin.isNeedsRebaseline());
	}

	/**
	 * Regression test for live bug: withdrawing an untradeable item (e.g. Bow string spool,
	 * HA = 1,200 gp, GE = 0 gp) where the inventory update packet arrives immediately after
	 * the bank widget closes. It must be absorbed without registering profit.
	 */
	@Test
	public void bankWithdrawUntradeableDelayedPacket_doesNotRegisterProfit()
	{
		int bowStringSpoolId = 29505;
		stubUntradeableItemWithHa(bowStringSpoolId, "Bow string spool", 1200L);

		// Pre-bank baseline has 3 items
		ItemContainer preBankInv = mockContainer(InventoryID.INV, 555, 3);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(preBankInv);
		plugin.previousInventorySnapshot = snapshot(555, 3);
		plugin.snapshotInitialized = true;

		// 1. Open bank
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.BANKMAIN);
		plugin.onWidgetLoaded(open);

		// 2. Bank closes before the inventory packet arrives
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 0, false));

		// 3. One tick later, inventory packet arrives with the withdrawn Bow string spool
		ItemContainer withSpool = mockContainer(InventoryID.INV, 555, 3, bowStringSpoolId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(withSpool);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, withSpool));

		// Verified: zero profit registered for withdrawing the untradeable spool
		Assert.assertEquals("Withdrawing Bow string spool from bank must not register profit",
			0L, plugin.session.getTotalProfit());
		Assert.assertFalse(plugin.isNeedsRebaseline());
	}

	@Test
	public void bankWithdrawWithActiveBankWidget_suppressesProfit()
	{
		int runePouchId = 12791;
		stubUntradeableItemWithHa(runePouchId, "Rune pouch", 1200L);

		plugin.previousInventorySnapshot = snapshot();
		plugin.snapshotInitialized = true;

		// Mock Bank widget as active and visible
		Widget mockBankWidget = org.mockito.Mockito.mock(Widget.class);
		when(mockBankWidget.isHidden()).thenReturn(false);
		when(client.getWidget(InterfaceID.Bankmain.TITLE)).thenReturn(mockBankWidget);

		// Even if trackingSuppressed was false, visible bank widget must suppress
		plugin.setTrackingSuppressed(false);
		ItemContainer withPouch = mockContainer(InventoryID.INV, runePouchId, 1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, withPouch));

		Assert.assertEquals("Visible bank widget must suppress item withdrawal profit",
			0L, plugin.session.getTotalProfit());
	}

	@Test
	public void bankCloseWithNoWithdrawal_graceTicksExpire_subsequentLootTrackedNormally()
	{
		// Baseline with 5 items
		plugin.previousInventorySnapshot = snapshot(555, 5);
		plugin.snapshotInitialized = true;

		// Open and close bank with no withdrawal
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.BANKMAIN);
		plugin.onWidgetLoaded(open);
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 0, false));

		Assert.assertTrue(plugin.isNeedsRebaseline());

		// Two game ticks pass without any inventory events
		plugin.onGameTick(new GameTick());
		plugin.onGameTick(new GameTick());

		// Grace window expired cleanly
		Assert.assertFalse("Grace window must clear needsRebaseline after expiry", plugin.isNeedsRebaseline());

		// Now subsequent gameplay loot is tracked normally
		stubTrackableItem(555, "Iron ore", 200L);
		ItemContainer withLoot = mockContainer(InventoryID.INV, 555, 6);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, withLoot));

		Assert.assertEquals(200L, plugin.session.getTotalProfit());
	}

	@Test
	public void multipleInterfaces_trackingStillSuppressedUntilAllClosed()
	{
		// Open both BANKMAIN and BANKSIDE
		WidgetLoaded openBank = new WidgetLoaded();
		openBank.setGroupId(InterfaceID.BANKMAIN);
		plugin.onWidgetLoaded(openBank);

		WidgetLoaded openBankInv = new WidgetLoaded();
		openBankInv.setGroupId(InterfaceID.BANKSIDE);
		plugin.onWidgetLoaded(openBankInv);

		// Close only BANKMAIN
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 0, false));

		// Still suppressed — BANKSIDE is still open
		Assert.assertTrue(plugin.isTrackingSuppressed());
		Assert.assertFalse(plugin.isNeedsRebaseline());

		// Close BANKSIDE — now all interfaces are closed
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKSIDE, 0, false));

		Assert.assertFalse(plugin.isTrackingSuppressed());
		Assert.assertTrue(plugin.isNeedsRebaseline());
	}

	/**
	 * Price Checker bug fix: opening View Price Guides, placing items (e.g. Runite ore)
	 * into the price checker, and closing the UI returns the items to inventory.
	 * This must NOT be counted as profit.
	 */
	@Test
	public void priceChecker_addAndClose_doesNotCountProfit()
	{
		// Baseline: 0 Runite ore
		plugin.previousInventorySnapshot = snapshot();
		plugin.snapshotInitialized = true;
		stubTrackableItem(451, "Runite ore", 10_148L);

		// 1. User opens "View Price Guides" -> loads GE_PRICECHECKER & GE_PRICECHECKER_SIDE
		WidgetLoaded openMain = new WidgetLoaded();
		openMain.setGroupId(InterfaceID.GE_PRICECHECKER);
		plugin.onWidgetLoaded(openMain);

		WidgetLoaded openSide = new WidgetLoaded();
		openSide.setGroupId(InterfaceID.GE_PRICECHECKER_SIDE);
		plugin.onWidgetLoaded(openSide);

		Assert.assertTrue("Tracking must be suppressed while price checker is open",
			plugin.isTrackingSuppressed());

		// 2. User adds 2 Runite ore into price checker (leaves inventory)
		ItemContainer inPriceChecker = mockContainer(InventoryID.INV);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inPriceChecker));
		Assert.assertEquals("No profit while price checker is open", 0L, plugin.session.getTotalProfit());

		// 3. User closes price checker
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.GE_PRICECHECKER, 0, false));
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.GE_PRICECHECKER_SIDE, 0, false));

		Assert.assertTrue("needsRebaseline must be true after closing price checker", plugin.isNeedsRebaseline());

		// 4. Server restores the 2 Runite ore to inventory
		ItemContainer restoredInv = mockContainer(InventoryID.INV, 451, 2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, restoredInv));

		// Assert: 0 profit gained!
		Assert.assertEquals("Restoring items on price checker close must NOT count as profit",
			0L, plugin.session.getTotalProfit());
		Assert.assertFalse("needsRebaseline should be consumed after inventory event", plugin.isNeedsRebaseline());
	}

	@Test
	public void priceChecker_inventoryTabLoaded_clearsSideInterfaceAndRebaselines()
	{
		plugin.snapshotInitialized = true;

		// Open GE_PRICECHECKER & GE_PRICECHECKER_SIDE
		WidgetLoaded openMain = new WidgetLoaded();
		openMain.setGroupId(InterfaceID.GE_PRICECHECKER);
		plugin.onWidgetLoaded(openMain);

		WidgetLoaded openSide = new WidgetLoaded();
		openSide.setGroupId(InterfaceID.GE_PRICECHECKER_SIDE);
		plugin.onWidgetLoaded(openSide);

		// Close main dialog
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.GE_PRICECHECKER, 0, false));

		// Normal inventory tab loads (replacing price checker side)
		WidgetLoaded invLoaded = new WidgetLoaded();
		invLoaded.setGroupId(InterfaceID.INVENTORY);
		plugin.onWidgetLoaded(invLoaded);

		Assert.assertFalse("Tracking should resume after inventory tab restored", plugin.isTrackingSuppressed());
		Assert.assertTrue("needsRebaseline must be set", plugin.isNeedsRebaseline());
	}

	@Test
	public void tradeAndShopInterfaces_suppressTracking()
	{
		for (int interfaceId : new int[]{
			InterfaceID.TRADEMAIN,
			InterfaceID.TRADECONFIRM,
			InterfaceID.OMNISHOP_MAIN,
			InterfaceID.SEED_VAULT,
			InterfaceID.DEATH_COFFER,
			InterfaceID.GE_PRICELIST
		})
		{
			WidgetLoaded open = new WidgetLoaded();
			open.setGroupId(interfaceId);
			plugin.onWidgetLoaded(open);

			Assert.assertTrue("Interface " + interfaceId + " must suppress tracking",
				plugin.isTrackingSuppressed());

			plugin.onWidgetClosed(new WidgetClosed(interfaceId, 0, false));
			Assert.assertFalse("Interface " + interfaceId + " close must restore tracking",
				plugin.isTrackingSuppressed());
		}
	}

	@Test
	public void shopInterfaces_markShopOpenWithoutSuppressing()
	{
		WidgetLoaded openMain = new WidgetLoaded();
		openMain.setGroupId(InterfaceID.SHOPMAIN);
		plugin.onWidgetLoaded(openMain);

		WidgetLoaded openSide = new WidgetLoaded();
		openSide.setGroupId(InterfaceID.SHOPSIDE);
		plugin.onWidgetLoaded(openSide);

		Assert.assertTrue("Shop must be marked open", plugin.interfaceTracker.isShopOpen());
		Assert.assertFalse("Standard shop must NOT suppress tracking", plugin.isTrackingSuppressed());

		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.SHOPMAIN, 0, false));
		Assert.assertTrue("Shop must remain open while side panel is up", plugin.interfaceTracker.isShopOpen());

		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.SHOPSIDE, 0, false));
		Assert.assertFalse("Shop must report closed once both widgets are gone", plugin.interfaceTracker.isShopOpen());
		Assert.assertFalse("Closing a shop must NOT schedule a re-baseline; the shop branch keeps the snapshot current, "
			+ "and a re-baseline would swallow the first post-shop diff", plugin.isNeedsRebaseline());
	}

	@Test
	public void trackingResumes_afterRebaseline()
	{
		// Establish baseline with 5 items
		plugin.previousInventorySnapshot = snapshot(555, 5);
		plugin.snapshotInitialized = true;

		// Open + close bank (triggers needsRebaseline)
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.BANKMAIN);
		plugin.onWidgetLoaded(open);
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 0, false));

		// Rebaseline on same inventory (5 items)
		plugin.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.INV,
				mockContainer(InventoryID.INV, 555, 5)));

		// Now gain 3 more items — should be tracked
		stubTrackableItem(555, "Iron ore", 200L);
		plugin.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.INV,
				mockContainer(InventoryID.INV, 555, 8)));

		Assert.assertEquals(3 * 200L, plugin.session.getTotalProfit());
	}

	// ── Equipment Unequip Suppression (Bug #1 regression) ────────────────

	/**
	 * Bug #1 regression: onGameTick must NOT clear recentlyUnequippedItems.
	 * The map is consumed in onItemContainerChanged and cleared there.
	 */
	@Test
	public void gameTick_doesNotClearRecentlyUnequippedMap()
	{
		plugin.snapshotInitialized = true;

		// Pre-populate the map as if an equipment event just fired
		plugin.recentlyUnequippedItems.put(555, 1);

		// Fire game tick
		plugin.onGameTick(new GameTick());

		// Map must still contain the data — consumed by inventory diff, not by tick
		Assert.assertTrue("recentlyUnequippedItems must NOT be cleared by onGameTick",
			plugin.recentlyUnequippedItems.containsKey(555));
	}

	@Test
	public void unequippedItem_notCountedAsGain()
	{
		// Baseline: inventory has 0 of item 555
		plugin.previousInventorySnapshot = snapshot();
		plugin.snapshotInitialized = true;
		// Baseline: equipment has item 555 x1
		plugin.previousEquipmentSnapshot = snapshot(555, 1);

		// Equipment event: item 555 removed from gear
		ItemContainer equipNow = mockContainer(InventoryID.WORN);
		plugin.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.WORN, equipNow));

		// Inventory event: item 555 now in inventory (it was unequipped)
		ItemContainer invNow = mockContainer(InventoryID.INV, 555, 1);
		plugin.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.INV, invNow));

		// Must not record profit for unequipped item
		Assert.assertEquals("Unequipped item should not count as profit", 0L, plugin.session.getTotalProfit());
	}

	@Test
	public void unequippedItem_netted_onlyExcessCountedAsGain()
	{
		// Baseline: 0 of item 555; equipment had 1 of item 555
		plugin.previousInventorySnapshot = snapshot();
		plugin.snapshotInitialized = true;
		plugin.previousEquipmentSnapshot = snapshot(555, 1);

		// Equipment event: 555 unequipped (equipment now empty)
		plugin.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.WORN,
				mockContainer(InventoryID.WORN)));

		// Inventory event: 555 x4 (1 from unequip + 3 actually gathered)
		stubTrackableItem(555, "Iron ore", 200L);
		plugin.onItemContainerChanged(
			new ItemContainerChanged(InventoryID.INV,
				mockContainer(InventoryID.INV, 555, 4)));

		// Net gain: 4 in inv - 1 unequipped = 3 actually gathered
		Assert.assertEquals("Only net gathered items should be profit", 3 * 200L, plugin.session.getTotalProfit());
	}

	// ── Goal Notification ────────────────────────────────────────────────

	@Test
	public void notificationSent_whenGoalReached()
	{
		when(config.goalAmount()).thenReturn("1000000");
		when(config.notifyOnGoal()).thenReturn(true);
		when(config.goalName()).thenReturn("Test Goal");

		// Push session past goal
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Item", 1, 1_000_000L));
		plugin.session = session;
		plugin.snapshotInitialized = true;

		plugin.onGameTick(new GameTick());

		verify(notifier, times(1)).notify(anyString());
	}

	@Test
	public void notificationNotSpammed_secondTickAfterGoalReached()
	{
		when(config.goalAmount()).thenReturn("1000000");
		when(config.notifyOnGoal()).thenReturn(true);
		when(config.goalName()).thenReturn("");

		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Item", 1, 1_000_000L));
		plugin.session = session;
		plugin.snapshotInitialized = true;

		// First tick: triggers notification
		plugin.onGameTick(new GameTick());
		// Second and third tick: notification must NOT fire again
		plugin.onGameTick(new GameTick());
		plugin.onGameTick(new GameTick());

		verify(notifier, times(1)).notify(anyString());
	}

	@Test
	public void noNotification_whenNotifyOnGoalDisabled()
	{
		when(config.goalAmount()).thenReturn("1000000");
		when(config.notifyOnGoal()).thenReturn(false);

		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Item", 1, 1_000_000L));
		plugin.session = session;
		plugin.snapshotInitialized = true;

		plugin.onGameTick(new GameTick());

		verify(notifier, never()).notify(anyString());
	}

	@Test
	public void notificationLatch_resetsWhenGoalAmountChanges()
	{
		when(config.goalAmount()).thenReturn("1000000");
		when(config.notifyOnGoal()).thenReturn(true);
		when(config.goalName()).thenReturn("");

		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Item", 1, 1_000_000L));
		plugin.session = session;
		plugin.snapshotInitialized = true;

		// First tick: notifies, sets latch
		plugin.onGameTick(new GameTick());
		verify(notifier, times(1)).notify(anyString());

		// Config change for goalAmount resets latch
		ConfigChanged configEvent = new ConfigChanged();
		configEvent.setGroup(CoinFlowConfig.CONFIG_GROUP);
		configEvent.setKey("goalAmount");
		configEvent.setNewValue("2000000");
		// Update stub to match new goal (still met by existing profit)
		when(config.goalAmount()).thenReturn("1000000");
		plugin.onConfigChanged(configEvent);

		// Second tick: latch was reset → should notify again
		plugin.onGameTick(new GameTick());
		verify(notifier, times(2)).notify(anyString());
	}

	// ── Gold Drops ────────────────────────────────────────────────────────

	@Test
	public void processGains_triggersGoldDrop_whenEnabledAndAboveThreshold()
	{
		stubTrackableItem(1513, "Magic logs", 1000L);
		when(config.showGoldDrops()).thenReturn(true);
		when(config.goldDropMinThreshold()).thenReturn(500);

		// Baseline
		ItemContainer c1 = mockContainer(InventoryID.INV);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, c1));

		// Gain 2 magic logs = 2,000 gp (above 500 gp threshold)
		ItemContainer c2 = mockContainer(InventoryID.INV, 1513, 2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, c2));

		verify(goldDropOverlay, times(1)).addDrop(org.mockito.ArgumentMatchers.eq("+2,000 gp"), org.mockito.ArgumentMatchers.eq(1513), org.mockito.ArgumentMatchers.eq(0));
	}

	@Test
	public void processGains_skipsGoldDrop_whenBelowThreshold()
	{
		stubTrackableItem(1513, "Magic logs", 1000L);
		when(config.showGoldDrops()).thenReturn(true);
		when(config.goldDropMinThreshold()).thenReturn(5000);

		// Baseline
		ItemContainer c1 = mockContainer(InventoryID.INV);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, c1));

		// Gain 2 magic logs = 2,000 gp (below 5,000 gp threshold)
		ItemContainer c2 = mockContainer(InventoryID.INV, 1513, 2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, c2));

		verify(goldDropOverlay, never()).addDrop(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	public void processGains_skipsGoldDrop_whenToggledOff()
	{
		stubTrackableItem(1513, "Magic logs", 1000L);
		when(config.showGoldDrops()).thenReturn(false);

		// Baseline
		ItemContainer c1 = mockContainer(InventoryID.INV);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, c1));

		ItemContainer c2 = mockContainer(InventoryID.INV, 1513, 2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, c2));

		verify(goldDropOverlay, never()).addDrop(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	public void onConfigChanged_sanitizesGoldDropMinThreshold_whenSpecialCharsProvided()
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(CoinFlowConfig.CONFIG_GROUP);
		event.setKey("goldDropMinThreshold");
		event.setNewValue("-@#$500abc");

		plugin.onConfigChanged(event);

		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goldDropMinThreshold", 500);
	}

	@Test
	public void onConfigChanged_sanitizesGoalAmount_whenSpecialCharsProvided()
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(CoinFlowConfig.CONFIG_GROUP);
		event.setKey("goalAmount");
		event.setNewValue("10m!@#$");

		plugin.onConfigChanged(event);

		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalAmount", "10m");
	}

	@Test
	public void onConfigChanged_sanitizesGoalName_whenSpecialCharsProvided()
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(CoinFlowConfig.CONFIG_GROUP);
		event.setKey("goalName");
		event.setNewValue("Bond <Goal>!");

		plugin.onConfigChanged(event);

		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalName", "Bond Goal");
	}

	@Test
	public void onConfigChanged_sanitizesIgnoredItems_whenSpecialCharsProvided()
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(CoinFlowConfig.CONFIG_GROUP);
		event.setKey("ignoredItems");
		event.setNewValue("Logs, Ores!@#$");

		plugin.onConfigChanged(event);

		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, "ignoredItems", "Logs, Ores");
	}

	// ── Supply Cost & Potion Tracking Tests ───────────────────────────────

	@Test
	public void potionDose_doseExpenseCharged()
	{
		int pot4Id = 12625;
		int pot3Id = 12627;
		stubTrackableItem(pot4Id, "Stamina potion(4)", 6_000L);
		stubTrackableItem(pot3Id, "Stamina potion(3)", 4_500L);

		// Baseline with Stamina potion(4)
		ItemContainerChanged baselineEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, pot4Id, 1)
		);
		plugin.onItemContainerChanged(baselineEvent);

		// Sipped 1 dose -> inventory has Stamina potion(3)
		ItemContainerChanged sipEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, pot3Id, 1)
		);
		plugin.onItemContainerChanged(sipEvent);

		// Expense: 6,000 / 4 = 1,500 gp
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1_500L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-1_500L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(-1_500L, plugin.getSession().getNetProfit());
		// Screen clutter prevention: gold drops are purely for income
		verify(goldDropOverlay, never()).addDrop(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	public void rebaselineGraceWindow_firstPotionSip_isStillCharged()
	{
		int pot4Id = 12625;
		int pot3Id = 12627;
		stubTrackableItem(pot4Id, "Stamina potion(4)", 6_000L);
		stubTrackableItem(pot3Id, "Stamina potion(3)", 4_500L);

		// Post-interface-close rebaseline window (e.g. just closed the GE)
		plugin.previousInventorySnapshot = snapshot(pot4Id, 1);
		plugin.snapshotInitialized = true;
		plugin.setNeedsRebaseline(true);
		plugin.rebaselineGraceTicks = 2;

		// First sip inside the grace window must reconcile, not be baselined away
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, pot3Id, 1)
		));

		Assert.assertFalse(plugin.isNeedsRebaseline());
		Assert.assertEquals(0, plugin.rebaselineGraceTicks);
		Assert.assertEquals(1_500L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-1_500L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void onMenuOptionClicked_collectOnNonGeTarget_doesNotRebaseline()
	{
		net.runelite.api.MenuEntry collectEntry = mock(net.runelite.api.MenuEntry.class);
		when(collectEntry.getOption()).thenReturn("Collect");
		when(collectEntry.getTarget()).thenReturn("Blast mine operator");
		plugin.onMenuOptionClicked(new MenuOptionClicked(collectEntry));

		Assert.assertFalse(plugin.isNeedsRebaseline());
		Assert.assertEquals(0, plugin.rebaselineGraceTicks);
	}

	@Test
	public void potionDose_combiningTwo3DosesInto4And2_zeroProfitZeroExpense()
	{
		int pot4Id = ItemID._4DOSE2ANTIPOISON;
		int pot3Id = ItemID._3DOSE2ANTIPOISON;
		int pot2Id = ItemID._2DOSE2ANTIPOISON;
		stubTrackableItem(pot4Id, "Superantipoison(4)", 1_257L);
		stubTrackableItem(pot3Id, "Superantipoison(3)", 612L);
		stubTrackableItem(pot2Id, "Superantipoison(2)", 400L);

		// Baseline: 2x Superantipoison(3) (total 6 doses)
		ItemContainerChanged baselineEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, pot3Id, 2)
		);
		plugin.onItemContainerChanged(baselineEvent);
		Assert.assertTrue(plugin.snapshotInitialized);
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// Player uses 1 3-dose potion on another 3-dose potion -> 1x (4) and 1x (2) (total 6 doses)
		ItemContainerChanged combineEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, pot4Id, 1, pot2Id, 1)
		);
		plugin.onItemContainerChanged(combineEvent);

		// Zero doses consumed, zero items created from external sources -> net zero profit, zero expenses!
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getNetProfit());
		verify(goldDropOverlay, never()).addDrop(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	public void potionDose_combiningTwo2DosesInto4DoseAndVial_zeroProfitZeroExpense()
	{
		int pot4Id = ItemID._4DOSEPRAYERRESTORE;
		int pot2Id = ItemID._2DOSEPRAYERRESTORE;
		int vialId = ItemID.VIAL_EMPTY;
		stubTrackableItem(pot4Id, "Prayer potion(4)", 10_000L);
		stubTrackableItem(pot2Id, "Prayer potion(2)", 5_000L);
		stubTrackableItem(vialId, "Vial", 2L);

		// Baseline: 2x Prayer potion(2) (total 4 doses)
		ItemContainerChanged baselineEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, pot2Id, 2)
		);
		plugin.onItemContainerChanged(baselineEvent);

		// Combine into 1x Prayer potion(4) + 1x empty vial
		ItemContainerChanged combineEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, pot4Id, 1, vialId, 1)
		);
		plugin.onItemContainerChanged(combineEvent);

		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		// Empty vial produced as decant byproduct must NOT be counted as loot!
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(vialId));
	}

	@Test
	public void dropAndPickup_deductsAndRestores()
	{

		int coalId = 453;
		stubTrackableItem(coalId, "Coal", 150L);

		// Baseline empty
		ItemContainerChanged baselineEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV)
		);
		plugin.onItemContainerChanged(baselineEvent);

		// 1. Mined 1 Coal -> net profit +150 gp
		ItemContainerChanged mineEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, coalId, 1)
		);
		plugin.onItemContainerChanged(mineEvent);
		Assert.assertEquals(150L, plugin.getSession().getTotalProfit());

		// 2. Dropped 1 Coal -> net profit deducted to 0 gp
		ItemContainerChanged dropEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV)
		);
		plugin.onItemContainerChanged(dropEvent);
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// 3. Picked up 1 Coal -> net profit restored to 150 gp
		ItemContainerChanged pickupEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, coalId, 1)
		);
		plugin.onItemContainerChanged(pickupEvent);
		Assert.assertEquals(150L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(coalId).getQuantity());
	}

	@Test
	public void equippingGear_doesNotChargeExpense()
	{
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);

		// Baseline inventory with whip
		ItemContainerChanged baselineInv = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, whipId, 1)
		);
		plugin.onItemContainerChanged(baselineInv);

		// Baseline equipment (empty)
		ItemContainerChanged baselineEquip = new ItemContainerChanged(
			InventoryID.WORN,
			mockContainer(InventoryID.WORN)
		);
		plugin.onItemContainerChanged(baselineEquip);

		// Player equips whip: equipment has whip
		ItemContainerChanged equipChange = new ItemContainerChanged(
			InventoryID.WORN,
			mockContainer(InventoryID.WORN, whipId, 1)
		);
		plugin.onItemContainerChanged(equipChange);

		// Inventory loses whip
		ItemContainerChanged invChange = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV)
		);
		plugin.onItemContainerChanged(invChange);

		// Equipping whip from inventory is NOT a consumable supply expense
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	// ── Cash spends ─────────────────────────────────────────────────────

	/**
	 * A coins-only loss (fee, fare, repair, coffer) is a supply expense, not an
	 * owned drop — and must never enter the drop bookkeeping that suppresses
	 * future coin pickups.
	 */
	@Test
	public void coinsOnlyLoss_recordsExpense_neverDropBookkeeping()
	{
		int coinsId = ItemID.COINS;
		plugin.previousInventorySnapshot = snapshot(coinsId, 50000);
		plugin.snapshotInitialized = true;

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV,
			mockContainer(InventoryID.INV, coinsId, 20000)));

		Assert.assertEquals(30000L, plugin.session.getTotalExpenses());
		Assert.assertEquals(0L, plugin.session.getGrossProfit());
		Assert.assertFalse(plugin.recentlyDroppedOwnedItems.containsKey(coinsId));
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(coinsId));
	}

	/**
	 * Regression: repairing Barrows gear at a POH armour stand. The degraded
	 * variants leave and the repaired items arrive with a coin fee — the item
	 * transition is asset-neutral and only the fee is spent.
	 */
	@Test
	public void armourStandBarrowsRepair_expensesFee_noPhantomProfit()
	{
		int platebodyDegraded = 50001;
		int platebody = 50002;
		int chainskirtDegraded = 50003;
		int chainskirt = 50004;
		int coinsId = ItemID.COINS;

		stubTrackableItem(platebodyDegraded, "Guthan's platebody 100", 150000L);
		stubTrackableItem(platebody, "Guthan's platebody", 184122L);
		stubTrackableItem(chainskirtDegraded, "Guthan's chainskirt 100", 160000L);
		stubTrackableItem(chainskirt, "Guthan's chainskirt", 195100L);

		plugin.previousInventorySnapshot = snapshot(
			platebodyDegraded, 1, chainskirtDegraded, 1, coinsId, 100000);
		plugin.snapshotInitialized = true;

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV,
			mockContainer(InventoryID.INV, platebody, 1, chainskirt, 1, coinsId, 87242)));

		Assert.assertEquals("Repaired items must not count as profit",
			0L, plugin.session.getGrossProfit());
		Assert.assertEquals("The 12,758 gp repair fee must be expensed",
			12758L, plugin.session.getTotalExpenses());
		Assert.assertFalse(plugin.recentlyDroppedOwnedItems.containsKey(platebodyDegraded));
		Assert.assertFalse(plugin.recentlyDroppedOwnedItems.containsKey(chainskirtDegraded));
	}

	@Test
	public void coinsToPlatinumExchange_isNotAPurchaseOrSpend()
	{
		int coinsId = ItemID.COINS;
		int platId = ItemID.PLATINUM;
		stubTrackableItem(platId, "Platinum token", 0L);
		Assert.assertFalse(CoinFlowPlugin.isCoinsOnlyPurchase(
			Collections.singletonMap(platId, 5), Collections.singletonMap(coinsId, 5000)));

		plugin.previousInventorySnapshot = snapshot(coinsId, 5000);
		plugin.snapshotInitialized = true;

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV,
			mockContainer(InventoryID.INV, platId, 5)));

		Assert.assertEquals(0L, plugin.session.getGrossProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	/**
	 * Creates a mock ItemContainer returning the given item at index 0.
	 * Pass no itemId/qty for an empty container.
	 */
	private ItemContainer mockContainer(int containerId, int... idQtyPairs)
	{
		ItemContainer container = org.mockito.Mockito.mock(ItemContainer.class);
		when(container.getId()).thenReturn(containerId);
		Item[] items;
		if (idQtyPairs.length == 0)
		{
			items = new Item[0];
		}
		else
		{
			items = new Item[idQtyPairs.length / 2];
			for (int i = 0; i < idQtyPairs.length; i += 2)
			{
				items[i / 2] = new Item(idQtyPairs[i], idQtyPairs[i + 1]);
			}
		}
		when(container.getItems()).thenReturn(items);
		return container;
	}

	/**
	 * Stubs ItemManager to make the given item trackable and priced.
	 */
	private void stubTrackableItem(int itemId, String name, long price, String... actions)
	{
		when(itemManager.canonicalize(itemId)).thenReturn(itemId);
		when(itemManager.getItemPrice(itemId)).thenReturn(price);
		ItemComposition comp = org.mockito.Mockito.mock(ItemComposition.class);
		when(comp.getName()).thenReturn(name);
		when(comp.isTradeable()).thenReturn(true);
		when(comp.getInventoryActions()).thenReturn(actions);
		when(itemManager.getItemComposition(itemId)).thenReturn(comp);
	}

	private void stubUntradeableItemWithHa(int itemId, String name, long haPrice)
	{
		when(itemManager.canonicalize(itemId)).thenReturn(itemId);
		when(itemManager.getItemPrice(itemId)).thenReturn(0L);
		ItemComposition comp = org.mockito.Mockito.mock(ItemComposition.class);
		when(comp.getName()).thenReturn(name);
		when(comp.getHaPrice()).thenReturn((int) haPrice);
		when(itemManager.getItemComposition(itemId)).thenReturn(comp);
	}

	@Test
	public void openConfiguration_postsOverlayMenuClickedEvent()
	{
		plugin.openConfiguration();

		org.mockito.ArgumentCaptor<net.runelite.client.events.OverlayMenuClicked> captor =
			org.mockito.ArgumentCaptor.forClass(net.runelite.client.events.OverlayMenuClicked.class);
		verify(eventBus).post(captor.capture());

		net.runelite.client.events.OverlayMenuClicked event = captor.getValue();
		org.junit.Assert.assertNotNull(event);
		org.junit.Assert.assertEquals(net.runelite.api.MenuAction.RUNELITE_OVERLAY_CONFIG, event.getEntry().getMenuAction());
		org.junit.Assert.assertEquals("Coin Flow", event.getEntry().getTarget());
		org.junit.Assert.assertEquals(overlay, event.getOverlay());
	}

	@Test
	public void allItemsTrackedByDefault_andIgnoredItemsExcludes()
	{
		// Baseline empty
		ItemContainerChanged baseline = new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV));
		plugin.onItemContainerChanged(baseline);

		// Non-skilling item (e.g. Abyssal whip)
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);

		// Ignored item (e.g. Empty vial)
		int vialId = 229;
		stubTrackableItem(vialId, "Vial", 3L);
		plugin.ignoredItemNames.add("vial");

		ItemContainerChanged gainEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, whipId, 1, vialId, 5)
		);
		plugin.onItemContainerChanged(gainEvent);

		// Whip should be tracked (+1.5m gp), Vial should be ignored (0 gp)
		Assert.assertEquals(1_500_000L, plugin.getSession().getTotalProfit());
		Assert.assertTrue(plugin.getSession().getTrackedItems().containsKey(whipId));
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(vialId));
	}

	@Test
	public void coinsGained_trackedAtOneGpEach()
	{
		// Baseline empty
		ItemContainerChanged baseline = new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV));
		plugin.onItemContainerChanged(baseline);

		int coinsId = net.runelite.api.gameval.ItemID.COINS;
		when(itemManager.canonicalize(coinsId)).thenReturn(coinsId);
		when(itemManager.getItemPrice(coinsId)).thenReturn(0L); // GE price for coins is 0
		ItemComposition comp = org.mockito.Mockito.mock(ItemComposition.class);
		when(comp.getName()).thenReturn("Coins");
		when(itemManager.getItemComposition(coinsId)).thenReturn(comp);

		ItemContainerChanged coinEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, coinsId, 50_000)
		);
		plugin.onItemContainerChanged(coinEvent);

		// 50,000 coins @ 1 gp = 50,000 gp
		Assert.assertEquals(50_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1L, plugin.getSession().getTrackedItems().get(coinsId).getPriceEach());
		Assert.assertEquals(50_000, plugin.getSession().getTrackedItems().get(coinsId).getQuantity());
	}

	private void stubAlchableItem(int itemId, String name, long gePrice, int haPrice, String... actions)
	{
		when(itemManager.canonicalize(itemId)).thenReturn(itemId);
		when(itemManager.getItemPrice(itemId)).thenReturn(gePrice);
		ItemComposition comp = org.mockito.Mockito.mock(ItemComposition.class);
		when(comp.getName()).thenReturn(name);
		when(comp.getHaPrice()).thenReturn(haPrice);
		when(comp.getInventoryActions()).thenReturn(actions);
		when(itemManager.getItemComposition(itemId)).thenReturn(comp);
	}

	@Test
	public void unequipGear_invDispatchedBeforeWorn_doesNotCountAsProfitGain()
	{
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);

		// Baseline: 0 whip in inv, 1 whip in worn
		ItemContainerChanged baselineInv = new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV));
		plugin.onItemContainerChanged(baselineInv);
		ItemContainer equipContainer = mockContainer(InventoryID.WORN, whipId, 1);
		ItemContainerChanged baselineEquip = new ItemContainerChanged(InventoryID.WORN, equipContainer);
		plugin.onItemContainerChanged(baselineEquip);

		// INV dispatches first with whip in inventory; WORN container in client now empty
		ItemContainer emptyEquip = mockContainer(InventoryID.WORN);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(emptyEquip);

		ItemContainer unequippedInv = mockContainer(InventoryID.INV, whipId, 1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, unequippedInv));

		// Total profit must be 0 (whip was unequipped, not gathered as loot)
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(whipId));
	}

	@Test
	public void equipGear_invDispatchedBeforeWorn_doesNotCountAsDroppedItem()
	{
		int whipId = 4151;
		stubTrackableItem(whipId, "Abyssal whip", 1_500_000L);

		// Baseline: 1 whip in inv, 0 in worn
		ItemContainerChanged baselineInv = new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, whipId, 1));
		plugin.onItemContainerChanged(baselineInv);
		ItemContainerChanged baselineEquip = new ItemContainerChanged(InventoryID.WORN, mockContainer(InventoryID.WORN));
		plugin.onItemContainerChanged(baselineEquip);

		// Equipped whip: WORN container now has whip; INV container empty
		ItemContainer nowEquipped = mockContainer(InventoryID.WORN, whipId, 1);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(nowEquipped);

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyInv));

		// Total profit must be 0 (whip was equipped, not dropped or lost)
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(whipId));
	}

	@Test
	public void highAlchemy_tracksMarginAndDeductsNatureRune()
	{
		int r2hId = 1319;
		int natureRuneId = net.runelite.api.gameval.ItemID.NATURERUNE;
		int coinsId = net.runelite.api.gameval.ItemID.COINS;

		stubAlchableItem(r2hId, "Rune 2h sword", 38_100L, 38_400);
		stubTrackableItem(natureRuneId, "Nature rune", 90L);

		// Baseline: has Rune 2h and Nature rune
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, r2hId, 1, natureRuneId, 10)));

		// Cast High Alchemy: 38,400 coins gained, 1 Rune 2h lost, 1 Nature rune lost
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, coinsId, 38_400, natureRuneId, 9)));

		// True Inventory Accounting:
		// Gross profit: 38,400 coins gained
		// Expenses: 1 banked Rune 2h (38,100 gp) + 1 Nature rune (90 gp) = 38,190 gp
		// Net profit: 38,400 - 38,190 = 210 gp
		Assert.assertEquals(38_400L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(38_190L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(210L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(r2hId));
		verify(goldDropOverlay).addDrop(org.mockito.ArgumentMatchers.eq("+300 gp"), org.mockito.ArgumentMatchers.eq(r2hId), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	public void highAlchemy_natureRuneAlched_tracksNetMarginAndCastingExpense()
	{
		int natureRuneId = net.runelite.api.gameval.ItemID.NATURERUNE;
		int coinsId = net.runelite.api.gameval.ItemID.COINS;

		// Nature rune: GE price 90 gp, HA price 108 gp
		stubAlchableItem(natureRuneId, "Nature rune", 90L, 108);

		// Baseline: 10 Nature runes in inventory
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, natureRuneId, 10)
		));

		// Cast High Alchemy on 1 Nature rune with staff & runes:
		// 108 coins gained, 2 Nature runes lost (1 casting cost + 1 alched item), 8 Nature runes remaining
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, coinsId, 108, natureRuneId, 8)
		));

		// True Inventory Accounting:
		// Gross profit: 108 coins
		// Supplies: 1 alched Nature rune (90 gp) + 1 casting Nature rune (90 gp) = 180 gp
		// Net profit: 108 - 180 = -72 gp
		Assert.assertEquals(108L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(180L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-72L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(natureRuneId));
	}

	@Test
	public void highAlchemy_natureRuneAlched_zeroCastingCost_tracks164GpSupplyAnd108GpCoins()
	{
		int natureRuneId = net.runelite.api.gameval.ItemID.NATURERUNE;
		int coinsId = net.runelite.api.gameval.ItemID.COINS;

		// Real OSRS values: GE price 164 gp, HA price 108 gp
		stubAlchableItem(natureRuneId, "Nature rune", 164L, 108);

		// Baseline: 5 Nature runes in inventory
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, natureRuneId, 5)
		));

		// Cast High Alchemy using Explorer's Ring (0 rune cast cost):
		// 108 coins gained, exactly 1 Nature rune lost from inventory
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, coinsId, 108, natureRuneId, 4)
		));

		// True Inventory Accounting:
		// Gross profit: +108 gp (Coins)
		// Supplies used: 164 gp (1 Nature rune)
		// Net profit: 108 - 164 = -56 gp
		Assert.assertEquals(108L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(164L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-56L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(natureRuneId));
	}

	@Test
	public void highAlchemy_sessionGainedDropAlched_convertsDropToCoinsWithoutPriceCorruption()
	{
		int r2hId = 1319;
		int natureRuneId = net.runelite.api.gameval.ItemID.NATURERUNE;
		int coinsId = net.runelite.api.gameval.ItemID.COINS;

		stubAlchableItem(r2hId, "Rune 2h sword", 38_100L, 38_400);
		stubTrackableItem(natureRuneId, "Nature rune", 90L);

		// Baseline empty
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		// 1. Player loots Rune 2h sword from a mob drop (+38,100 gp)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, r2hId, 1)
		));
		Assert.assertEquals(38_100L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(r2hId).getQuantity());

		// 2. Player withdraws Nature runes from bank (re-baselined into inventory snapshot)
		plugin.previousInventorySnapshot = InventorySnapshot.fromArrays(new int[]{r2hId, natureRuneId}, new int[]{1, 10});

		// 3. Player casts High Alchemy on the looted Rune 2h sword
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, coinsId, 38_400, natureRuneId, 9)
		));

		// Drop is deducted (-38,100 gp) and converted to 38,400 Coins (+38,400 gp)
		// Total gross profit: 38,400 gp
		// Nature rune spell expense: 90 gp
		// Total net profit: 38,400 - 90 = 38,310 gp
		Assert.assertEquals(38_400L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(90L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(38_310L, plugin.getSession().getTotalProfit());
		Assert.assertFalse("Rune 2h should be converted, not tracked as existing", plugin.getSession().getTrackedItems().containsKey(r2hId));
		Assert.assertEquals(38_400, plugin.getSession().getTrackedItems().get(coinsId).getQuantity());
	}

	@Test
	public void foodPortion_summerPieEaten_doesNotCountAsGain()
	{
		int pieId = 7218;
		int halfPieId = 7220;
		stubTrackableItem(pieId, "Summer pie", 1_200L);
		stubTrackableItem(halfPieId, "Half a summer pie", 600L);

		// Baseline with Summer pie
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, pieId, 1)));

		// Eat 1 slice -> inventory now has Half a summer pie
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, halfPieId, 1)));

		// Half pie is not counted as loot gain; portion consumed (600 gp) is charged as supply expense
		Assert.assertEquals(-600L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(600L, plugin.getSession().getTotalExpenses());
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(halfPieId));
	}

	@Test
	public void byproducts_emptyContainers_ignoredFromProfit()
	{
		int bowlId = 1923;
		stubTrackableItem(bowlId, "Bowl", 5L);

		// Baseline empty
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		// Gained empty bowl
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, bowlId, 1)));

		// Bowl is a byproduct, profit remains 0
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(bowlId));
	}

	@Test
	public void droppingUnacquiredItem_doesNotPenalizeProfit()
	{
		int spadeId = 952;
		stubTrackableItem(spadeId, "Spade", 1_000L);

		// Baseline with Spade
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, spadeId, 1)));

		// Drop Spade
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		// Spade was never gained in session -> profit must remain 0, never negative
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(spadeId));
		Assert.assertEquals(Integer.valueOf(1), plugin.recentlyDroppedOwnedItems.get(spadeId));
	}

	@Test
	public void dropAndPickup_ownedItem_recordsNoProfit()
	{
		int spadeId = 952;
		stubTrackableItem(spadeId, "Spade", 1_000L);

		// Baseline with Spade
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, spadeId, 1)));

		// Drop Spade
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(spadeId));
		Assert.assertEquals(Integer.valueOf(1), plugin.recentlyDroppedOwnedItems.get(spadeId));

		// Pick up Spade
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, spadeId, 1)));

		// Profit must still be 0 (owned item picked back up is not loot)
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.recentlyDroppedOwnedItems.containsKey(spadeId));
		verify(goldDropOverlay, never()).addDrop(anyString(), anyInt(), anyInt());
	}

	@Test
	public void dropAndPickup_cookingCape_untradeable_recordsNoProfit()
	{
		int cookingCapeId = 9801; // Cooking cape
		when(itemManager.canonicalize(cookingCapeId)).thenReturn(cookingCapeId);
		when(itemManager.getItemPrice(cookingCapeId)).thenReturn(0L); // Untradeable: 0 GE price
		ItemComposition capeComp = org.mockito.Mockito.mock(ItemComposition.class);
		when(capeComp.getName()).thenReturn("Cooking cape");
		when(capeComp.getHaPrice()).thenReturn(59_400); // 59.4K HA price
		when(itemManager.getItemComposition(cookingCapeId)).thenReturn(capeComp);

		// Baseline with Cooking cape
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, cookingCapeId, 1)));

		// Drop Cooking cape
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		// Dropping owned untradeable must not affect profit or expenses
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(cookingCapeId));
		Assert.assertEquals(Integer.valueOf(1), plugin.recentlyDroppedOwnedItems.get(cookingCapeId));

		// Pick up Cooking cape
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, cookingCapeId, 1)));

		// Profit must remain 0 — NOT +59.4K gp!
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertFalse(plugin.recentlyDroppedOwnedItems.containsKey(cookingCapeId));
		verify(goldDropOverlay, never()).addDrop(anyString(), anyInt(), anyInt());
	}

	@Test
	public void dropAndPickup_potionWithDropClick_isNotExpensedAndPickupIsNotLoot()
	{
		int potionId = 185; // Superantipoison(1)
		stubTrackableItem(potionId, "Superantipoison(1)", 329L);

		// Baseline with potion (pre-session / banked)
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, potionId, 1)));

		// Player clicks "Drop" on it
		net.runelite.api.MenuEntry dropEntry = mock(net.runelite.api.MenuEntry.class);
		when(dropEntry.getOption()).thenReturn("Drop");
		when(dropEntry.getTarget()).thenReturn("<col=ff9040>Superantipoison(1)</col>");
		when(dropEntry.getItemId()).thenReturn(potionId);
		plugin.onMenuOptionClicked(new MenuOptionClicked(dropEntry));

		// Potion leaves inventory
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		// Must be a drop, not a drink: no expense, tracked as an owned drop
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(Integer.valueOf(1), plugin.recentlyDroppedOwnedItems.get(potionId));
		Assert.assertTrue("Intent consumed once the drop is seen", plugin.recentDropIntents.isEmpty());

		// Pick it back up
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, potionId, 1)));

		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.recentlyDroppedOwnedItems.containsKey(potionId));
	}

	// ── Count Drops as Spent ───────────────────────────────────────────────

	private void clickDrop(int itemId, String name)
	{
		net.runelite.api.MenuEntry dropEntry = mock(net.runelite.api.MenuEntry.class);
		when(dropEntry.getOption()).thenReturn("Drop");
		when(dropEntry.getTarget()).thenReturn("<col=ff9040>" + name + "</col>");
		when(dropEntry.getItemId()).thenReturn(itemId);
		plugin.onMenuOptionClicked(new MenuOptionClicked(dropEntry));
	}

	@Test
	public void dropsAsSpent_ownedItemDrop_isExpensedNotIgnored()
	{
		when(config.countDropsAsSpent()).thenReturn(true);
		int spadeId = 952;
		stubTrackableItem(spadeId, "Spade", 1_000L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, spadeId, 1)));
		clickDrop(spadeId, "Spade");
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		Assert.assertEquals(1_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(-1_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(Integer.valueOf(1), plugin.recentlyExpensedDrops.get(spadeId));
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(spadeId));
		Assert.assertFalse(plugin.recentlyDroppedOwnedItems.containsKey(spadeId));
	}

	@Test
	public void dropsAsSpent_ownedItemDropAndPickup_reversesExpense()
	{
		when(config.countDropsAsSpent()).thenReturn(true);
		int spadeId = 952;
		stubTrackableItem(spadeId, "Spade", 1_000L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, spadeId, 1)));
		clickDrop(spadeId, "Spade");
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));
		Assert.assertEquals(1_000L, plugin.getSession().getTotalExpenses());

		// Pickup-only tick: nothing else changes, so the reversal must still fire
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, spadeId, 1)));

		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.getSession().getTrackedExpenses().containsKey(spadeId));
		Assert.assertFalse(plugin.recentlyExpensedDrops.containsKey(spadeId));
		verify(goldDropOverlay, never()).addDrop(anyString(), anyInt(), anyInt());
	}

	@Test
	public void dropsAsSpent_sessionGainedDrop_keepsGrossAndChargesSpent()
	{
		when(config.countDropsAsSpent()).thenReturn(true);
		int coalId = 453;
		stubTrackableItem(coalId, "Coal", 150L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, coalId, 10)));
		Assert.assertEquals(1_500L, plugin.getSession().getGrossProfit());

		clickDrop(coalId, "Coal");
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		Assert.assertEquals("Gross stays credited", 1_500L, plugin.getSession().getGrossProfit());
		Assert.assertEquals("Drop is charged to Spent", 1_500L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals("Gain retired so it can't be deducted twice",
			0L, plugin.getSession().getTrackedItems().get(coalId).getRemainingQuantity());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(coalId));
	}

	@Test
	public void dropsAsSpent_sessionGainedDropAndPickup_restoresGain()
	{
		when(config.countDropsAsSpent()).thenReturn(true);
		int coalId = 453;
		stubTrackableItem(coalId, "Coal", 150L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, coalId, 10)));
		clickDrop(coalId, "Coal");
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, coalId, 10)));

		Assert.assertEquals(1_500L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(1_500L, plugin.getSession().getTotalProfit());
		CoinFlowSession.TrackedItem gain = plugin.getSession().getTrackedItems().get(coalId);
		Assert.assertEquals("Pickup is not fresh loot", 10L, gain.getQuantity());
		Assert.assertEquals("Retired units are deductible again", 10L, gain.getRemainingQuantity());
		Assert.assertFalse(plugin.recentlyExpensedDrops.containsKey(coalId));
	}

	@Test
	public void dropsAsSpent_partialPickup_reversesOnlyRecoveredUnits()
	{
		when(config.countDropsAsSpent()).thenReturn(true);
		int coalId = 453;
		stubTrackableItem(coalId, "Coal", 150L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, coalId, 10)));
		clickDrop(coalId, "Coal");
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));
		Assert.assertEquals(1_500L, plugin.getSession().getTotalExpenses());

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, coalId, 4)));

		Assert.assertEquals(900L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(Integer.valueOf(6), plugin.recentlyExpensedDrops.get(coalId));
	}

	@Test
	public void dropsAsSpent_potionWithDropClick_isExpensedAsWholeItem()
	{
		when(config.countDropsAsSpent()).thenReturn(true);
		int potionId = 185; // Superantipoison(1)
		stubTrackableItem(potionId, "Superantipoison(1)", 329L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, potionId, 1)));
		clickDrop(potionId, "Superantipoison(1)");
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		Assert.assertEquals(329L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(1L, plugin.getSession().getTrackedExpenses().get(potionId).getQuantity());

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, potionId, 1)));
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void dropsAsSpent_lossWithoutDropClick_keepsLegacyBehavior()
	{
		when(config.countDropsAsSpent()).thenReturn(true);
		int spadeId = 952;
		stubTrackableItem(spadeId, "Spade", 1_000L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, spadeId, 1)));
		// Item leaves inventory with no "Drop" click (quest hand-in, Destroy, death, ...)
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertTrue(plugin.recentlyExpensedDrops.isEmpty());
		Assert.assertEquals(Integer.valueOf(1), plugin.recentlyDroppedOwnedItems.get(spadeId));
	}

	@Test
	public void dropsAsSpent_dropCoins_isExpensedAtOneGpEach()
	{
		when(config.countDropsAsSpent()).thenReturn(true);
		int coinsId = ItemID.COINS;
		when(itemManager.canonicalize(coinsId)).thenReturn(coinsId);
		when(itemManager.getItemPrice(coinsId)).thenReturn(0L);
		ItemComposition coinsComp = mock(ItemComposition.class);
		when(coinsComp.getName()).thenReturn("Coins");
		when(itemManager.getItemComposition(coinsId)).thenReturn(coinsComp);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, coinsId, 5_000)));
		clickDrop(coinsId, "Coins");
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		Assert.assertEquals(5_000L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void dropsAsSpent_requiresTrackSpent()
	{
		when(config.countDropsAsSpent()).thenReturn(true);
		when(config.trackSpent()).thenReturn(false);
		int spadeId = 952;
		stubTrackableItem(spadeId, "Spade", 1_000L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, spadeId, 1)));
		clickDrop(spadeId, "Spade");
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertTrue(plugin.recentlyExpensedDrops.isEmpty());
		Assert.assertEquals(Integer.valueOf(1), plugin.recentlyDroppedOwnedItems.get(spadeId));
	}

	@Test
	public void dropsAsSpent_toggleOffBeforePickup_stillReversesAndCreditsNoLoot()
	{
		when(config.countDropsAsSpent()).thenReturn(true);
		int spadeId = 952;
		stubTrackableItem(spadeId, "Spade", 1_000L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, spadeId, 1)));
		clickDrop(spadeId, "Spade");
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));
		Assert.assertEquals(1_000L, plugin.getSession().getTotalExpenses());

		when(config.countDropsAsSpent()).thenReturn(false);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, spadeId, 1)));

		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertFalse(plugin.recentlyExpensedDrops.containsKey(spadeId));
	}

	@Test
	public void dropsAsSpent_toggleOff_legacyDropPathUnchanged()
	{
		when(config.countDropsAsSpent()).thenReturn(false);
		int coalId = 453;
		stubTrackableItem(coalId, "Coal", 150L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, coalId, 10)));
		clickDrop(coalId, "Coal");
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		Assert.assertEquals("Legacy: drop deducts gross", 0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(Integer.valueOf(10), plugin.recentlyDroppedItems.get(coalId));
		Assert.assertTrue(plugin.recentlyExpensedDrops.isEmpty());
	}

	@Test
	public void dropsAsSpent_pendingRecordsExpireWithOtherDropRecords()
	{
		when(config.countDropsAsSpent()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(10);
		plugin.snapshotInitialized = true;
		int spadeId = 952;
		stubTrackableItem(spadeId, "Spade", 1_000L);

		plugin.previousInventorySnapshot = snapshot(spadeId, 1);
		clickDrop(spadeId, "Spade");
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));
		Assert.assertEquals(Integer.valueOf(1), plugin.recentlyExpensedDrops.get(spadeId));
		Assert.assertEquals(Integer.valueOf(10), plugin.droppedItemTicks.get(spadeId));

		when(client.getTickCount()).thenReturn(650);
		plugin.onGameTick(new GameTick());

		Assert.assertFalse(plugin.recentlyExpensedDrops.containsKey(spadeId));
		Assert.assertFalse(plugin.droppedItemTicks.containsKey(spadeId));
		Assert.assertEquals("Expense stays once the ground item is gone", 1_000L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void dropsAsSpent_resetSession_clearsPendingDrops()
	{
		when(config.countDropsAsSpent()).thenReturn(true);
		int spadeId = 952;
		stubTrackableItem(spadeId, "Spade", 1_000L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, spadeId, 1)));
		clickDrop(spadeId, "Spade");
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));
		Assert.assertFalse(plugin.recentlyExpensedDrops.isEmpty());

		plugin.resetSession();

		Assert.assertTrue(plugin.recentlyExpensedDrops.isEmpty());
	}

	@Test
	public void dialoguePurchase_coinsOutItemsIn_recordsBasisNotLoot()
	{
		// Zaff: 120 battlestaffs for 840,000 gp via NPC dialogue (no SHOPMAIN interface)
		int staffId = 1391;
		stubTrackableItem(staffId, "Battlestaff", 7945L);
		stubTrackableItem(ItemID.COINS, "Coins", 1L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV,
			mockContainer(InventoryID.INV, ItemID.COINS, 1_000_000)));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV,
			mockContainer(InventoryID.INV, ItemID.COINS, 160_000, staffId, 120)));

		Assert.assertEquals("Purchase is an asset conversion, not loot", 0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(120, plugin.grandExchangeTracker.basisQuantity(staffId));
		Assert.assertFalse(plugin.recentlyDroppedOwnedItems.containsKey(ItemID.COINS));

		// Selling them later realizes margin against the 7,000 gp basis
		GrandExchangeTracker.GeLedger ledger = plugin.grandExchangeTracker.settleSell(staffId, 120, 960_000, plugin.getSession());
		Assert.assertEquals(960_000L - 120L * 7_000L - 120L * 160L, ledger.netDelta); // minus 2% tax (160/staff @ 8000)
	}

	@Test
	public void dialoguePurchaseDuringRebaseline_stillRecordsBasis()
	{
		// Regression: a Zaff buy landing inside a post-bank-close re-baseline
		// window was swallowed into the baseline, losing the cost basis so the
		// staves later sold as fully untracked income.
		int staffId = 1391;
		stubTrackableItem(staffId, "Battlestaff", 7945L);
		stubTrackableItem(ItemID.COINS, "Coins", 1L);

		plugin.previousInventorySnapshot = snapshot(ItemID.COINS, 1_000_000);
		plugin.snapshotInitialized = true;

		// Open + close bank → needsRebaseline
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.BANKMAIN);
		plugin.onWidgetLoaded(open);
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 0, false));
		Assert.assertTrue(plugin.isNeedsRebaseline());

		// The first inventory event after the close is the purchase diff itself
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV,
			mockContainer(InventoryID.INV, ItemID.COINS, 160_000, staffId, 120)));

		Assert.assertEquals("Purchase is an asset conversion, not loot", 0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals("Basis must be recorded even during re-baseline", 120,
			plugin.grandExchangeTracker.basisQuantity(staffId));
		Assert.assertFalse(plugin.isNeedsRebaseline());
	}

	@Test
	public void potionLossWithoutDropClick_isStillExpensedAsConsumed()
	{
		int potionId = 185;
		stubTrackableItem(potionId, "Superantipoison(1)", 329L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, potionId, 1)));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		Assert.assertEquals(329L, plugin.getSession().getTotalExpenses());
		Assert.assertFalse(plugin.recentlyDroppedOwnedItems.containsKey(potionId));
	}

	@Test
	public void dropAndPickup_sessionItem_restoresDeductedProfit()
	{
		int coalId = 453;
		stubTrackableItem(coalId, "Coal", 150L);

		// Baseline empty
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		// Mine 2 coal in this session
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, coalId, 2)));
		Assert.assertEquals(300L, plugin.getSession().getTotalProfit());

		// Drop 1 coal
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, coalId, 1)));
		Assert.assertEquals(150L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(Integer.valueOf(1), plugin.recentlyDroppedItems.get(coalId));
		Assert.assertFalse(plugin.recentlyDroppedOwnedItems.containsKey(coalId));

		// Pick up the dropped coal
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, coalId, 2)));
		Assert.assertEquals(300L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(coalId));
	}

	@Test
	public void notedAndUnnotedItemGainedSameTick_mergesQuantities()
	{
		int unnotedShark = 383;
		int notedShark = 384;
		when(itemManager.canonicalize(unnotedShark)).thenReturn(unnotedShark);
		when(itemManager.canonicalize(notedShark)).thenReturn(unnotedShark);
		when(itemManager.getItemPrice(unnotedShark)).thenReturn(1_000L);
		ItemComposition comp = org.mockito.Mockito.mock(ItemComposition.class);
		when(comp.getName()).thenReturn("Shark");
		when(itemManager.getItemComposition(unnotedShark)).thenReturn(comp);

		// Baseline empty
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		// Gain 2 unnoted sharks and 5 noted sharks
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV, unnotedShark, 2, notedShark, 5)));

		// Total should be 7 sharks @ 1,000 gp = 7,000 gp
		Assert.assertEquals(7_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(7, plugin.getSession().getTrackedItems().get(unnotedShark).getQuantity());
	}

	@Test
	public void cooking_bankedRawMantaRay_deductsRawFishAndCreditsCookedFish()
	{
		int rawMantaId = 389;
		int cookedMantaId = 391;
		stubTrackableItem(rawMantaId, "Raw manta ray", 1_200L);
		stubTrackableItem(cookedMantaId, "Manta ray", 1_336L);

		// Baseline with 1 raw manta ray from bank
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, rawMantaId, 1)
		));

		// Cook raw manta ray -> 1 cooked manta ray
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, cookedMantaId, 1)
		));

		// Net profit: 1,336 gp (cooked) - 1,200 gp (raw supply) = 136 gp
		Assert.assertEquals(136L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1_200L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(cookedMantaId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(rawMantaId).getQuantity());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(rawMantaId));
	}

	@Test
	public void cooking_burntRawMantaRay_chargesExpenseAndExcludesJunkGain()
	{
		int rawMantaId = 389;
		int burntMantaId = 393;
		stubTrackableItem(rawMantaId, "Raw manta ray", 1_200L);
		stubTrackableItem(burntMantaId, "Burnt manta ray", 0L);

		// Baseline with 1 raw manta ray from bank
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, rawMantaId, 1)
		));

		// Burn the manta ray
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, burntMantaId, 1)
		));

		// Net profit: 0 gp (burnt) - 1,200 gp (raw supply) = -1,200 gp
		Assert.assertEquals(-1_200L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1_200L, plugin.getSession().getTotalExpenses());
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(burntMantaId));
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(rawMantaId));
	}

	@Test
	public void cooking_caughtRawMantaRay_convertsCleanlyWithoutDoubleCharge()
	{
		int rawMantaId = 389;
		int cookedMantaId = 391;
		stubTrackableItem(rawMantaId, "Raw manta ray", 1_200L);
		stubTrackableItem(cookedMantaId, "Manta ray", 1_336L);

		// Baseline empty
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		// 1. Catch raw manta ray
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, rawMantaId, 1)
		));
		Assert.assertEquals(1_200L, plugin.getSession().getTotalProfit());

		// 2. Cook raw manta ray on dock fire
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, cookedMantaId, 1)
		));

		// Raw fish deduction balances with cooked gain -> net profit is exactly 1,336 gp
		Assert.assertEquals(1_336L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(cookedMantaId).getQuantity());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(rawMantaId));
	}

	@Test
	public void cooking_mixedCaughtAndBankedFishSameTick_accuratelyPartitionsExpenseAndDeduction()
	{
		int rawMantaId = 389;
		int cookedMantaId = 391;
		int burntMantaId = 393;
		stubTrackableItem(rawMantaId, "Raw manta ray", 1_200L);
		stubTrackableItem(cookedMantaId, "Manta ray", 1_336L);
		stubTrackableItem(burntMantaId, "Burnt manta ray", 0L);

		// Baseline empty
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, mockContainer(InventoryID.INV)));

		// 1. Catch 1 raw manta ray (session gains = 1 raw manta ray)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, rawMantaId, 1)
		));
		Assert.assertEquals(1_200L, plugin.getSession().getTotalProfit());

		// 2. Player withdraws 1 additional raw manta ray from bank (re-baselined into inventory)
		plugin.previousInventorySnapshot = InventorySnapshot.fromArrays(new int[]{rawMantaId}, new int[]{2});

		// 3. Player cooks both: loses 2 raw manta rays, gains 1 cooked manta ray and 1 burnt manta ray
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, cookedMantaId, 1, burntMantaId, 1)
		));

		// 1 raw was deducted from session gains, 1 raw was charged as supply expense (1,200 gp)
		// Net profit: 1,336 (cooked) - 1,200 (banked raw supply) = 136 gp
		Assert.assertEquals(136L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1_200L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(cookedMantaId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(rawMantaId).getQuantity());
	}

	@Test
	public void runePouch_teleportVarrock_consumesRunesViaVarbitChanged()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int lawRuneId = ItemID.LAWRUNE;
		int airRuneId = ItemID.AIRRUNE;
		int fireRuneId = ItemID.FIRERUNE;
		stubTrackableItem(lawRuneId, "Law rune", 100L);
		stubTrackableItem(airRuneId, "Air rune", 5L);
		stubTrackableItem(fireRuneId, "Fire rune", 5L);

		// Setup rune pouch enum mapping:
		// type 1 -> Law rune, type 2 -> Air rune, type 3 -> Fire rune
		EnumComposition runeEnum = org.mockito.Mockito.mock(EnumComposition.class);
		when(runeEnum.getIntValue(1)).thenReturn(lawRuneId);
		when(runeEnum.getIntValue(2)).thenReturn(airRuneId);
		when(runeEnum.getIntValue(3)).thenReturn(fireRuneId);
		when(client.getEnum(EnumID.RUNEPOUCH_RUNE)).thenReturn(runeEnum);

		// Initial varbits: slot 0 (Law: 50), slot 1 (Air: 100), slot 2 (Fire: 50)
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_1)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1)).thenReturn(50);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_2)).thenReturn(2);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_2)).thenReturn(100);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_3)).thenReturn(3);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_3)).thenReturn(50);

		// Inventory has Rune Pouch
		ItemContainer invContainer = mockContainer(InventoryID.INV, ItemID.BH_RUNE_POUCH, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(invContainer);

		// Baseline snapshot
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, invContainer));
		Assert.assertTrue(plugin.snapshotInitialized);
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// Player teleports to Varrock: consumes 1 Law rune, 3 Air runes, 1 Fire rune from pouch
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1)).thenReturn(49);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_2)).thenReturn(97);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_3)).thenReturn(49);

		// Varbit changed event fires (ItemContainerChanged does NOT fire on spell teleport)
		VarbitChanged varbitEvent = new VarbitChanged();
		varbitEvent.setVarbitId(VarbitID.RUNE_POUCH_QUANTITY_1);
		plugin.onVarbitChanged(varbitEvent);

		// Expenses: 1 Law (100) + 3 Air (15) + 1 Fire (5) = 120 gp
		Assert.assertEquals(120L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-120L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(lawRuneId).getQuantity());
		Assert.assertEquals(3, plugin.getSession().getTrackedExpenses().get(airRuneId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(fireRuneId).getQuantity());
	}

	@Test
	public void runePouch_withoutPouchInInventory_varbitsIgnored()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int lawRuneId = ItemID.LAWRUNE;
		stubTrackableItem(lawRuneId, "Law rune", 100L);

		EnumComposition runeEnum = org.mockito.Mockito.mock(EnumComposition.class);
		when(runeEnum.getIntValue(1)).thenReturn(lawRuneId);
		when(client.getEnum(EnumID.RUNEPOUCH_RUNE)).thenReturn(runeEnum);

		// Varbit values exist (e.g. from prior session), but pouch is in bank (not in inv)
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_1)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1)).thenReturn(50);

		// Inventory does NOT have rune pouch
		ItemContainer invContainer = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(invContainer);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, invContainer));

		// Varbit changes while pouch is not on player
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1)).thenReturn(40);
		VarbitChanged varbitEvent = new VarbitChanged();
		varbitEvent.setVarbitId(VarbitID.RUNE_POUCH_QUANTITY_1);
		plugin.onVarbitChanged(varbitEvent);

		// Must not track any supply expenses
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void runePouch_fillingAndEmptyingPouch_netZeroExpenseAndProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int lawRuneId = ItemID.LAWRUNE;
		stubTrackableItem(lawRuneId, "Law rune", 100L);

		EnumComposition runeEnum = org.mockito.Mockito.mock(EnumComposition.class);
		when(runeEnum.getIntValue(1)).thenReturn(lawRuneId);
		when(client.getEnum(EnumID.RUNEPOUCH_RUNE)).thenReturn(runeEnum);

		// Start: Pouch has 0 runes, Inventory has Rune Pouch + 10 Law runes
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_1)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1)).thenReturn(0);

		ItemContainer inv1 = mockContainer(InventoryID.INV, ItemID.BH_RUNE_POUCH, 1, lawRuneId, 10);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Player fills pouch: 10 Law runes move into pouch, leaving inventory slots
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1)).thenReturn(10);
		ItemContainer inv2 = mockContainer(InventoryID.INV, ItemID.BH_RUNE_POUCH, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Net delta is 0: total Law runes remained 10
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// Player empties pouch: 10 Law runes move back into inventory
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1)).thenReturn(0);
		ItemContainer inv3 = mockContainer(InventoryID.INV, ItemID.BH_RUNE_POUCH, 1, lawRuneId, 10);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv3);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv3));

		// Still net delta 0
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void runePouch_highAlchemyWithNatureRuneInPouch()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int r2hId = 1319;
		int natureRuneId = ItemID.NATURERUNE;
		int coinsId = ItemID.COINS;

		stubAlchableItem(r2hId, "Rune 2h sword", 38_100L, 38_400);
		stubTrackableItem(natureRuneId, "Nature rune", 90L);

		EnumComposition runeEnum = org.mockito.Mockito.mock(EnumComposition.class);
		when(runeEnum.getIntValue(1)).thenReturn(natureRuneId);
		when(client.getEnum(EnumID.RUNEPOUCH_RUNE)).thenReturn(runeEnum);

		// Baseline: Rune Pouch has 10 Nature runes, inventory has Rune 2h sword
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_1)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1)).thenReturn(10);

		ItemContainer inv1 = mockContainer(InventoryID.INV, ItemID.DIVINE_RUNE_POUCH, 1, r2hId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Cast High Alchemy: 38,400 coins gained, Rune 2h lost, 1 Nature rune consumed from pouch
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_1)).thenReturn(9);
		ItemContainer inv2 = mockContainer(InventoryID.INV, ItemID.DIVINE_RUNE_POUCH, 1, coinsId, 38_400);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Profit accounting:
		// Gross profit: 38,400 coins
		// Expenses: 38,100 (banked Rune 2h) + 90 (Nature rune from pouch) = 38,190 gp
		// Net profit: 210 gp
		Assert.assertEquals(38_400L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(38_190L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(210L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void fletching_bankedMagicLog_toMagicLongbowU_chargesLogExpenseAndCreditsUnstrungBow()
	{
		int magicLogId = 1513;
		int magicLongbowUId = 70;
		stubTrackableItem(magicLogId, "Magic logs", 1_000L);
		stubTrackableItem(magicLongbowUId, "Magic longbow (u)", 747L);

		// Baseline with 1 Magic logs from bank
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, magicLogId, 1)
		));

		// Fletch into Magic longbow (u)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, magicLongbowUId, 1)
		));

		// Net profit: 747 gp (bow) - 1,000 gp (log supply) = -253 gp
		Assert.assertEquals(-253L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(747L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(magicLongbowUId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(magicLogId).getQuantity());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(magicLogId));
	}

	@Test
	public void fletching_existingLogInInventoryOnLogin_fletchesSuccessfully()
	{
		int magicLogId = 1513;
		int magicLongbowUId = 70;
		stubTrackableItem(magicLogId, "Magic logs", 1_000L);
		stubTrackableItem(magicLongbowUId, "Magic longbow (u)", 747L);

		// Player logs in with 1 Magic log already in inventory
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		ItemContainer loginInv = mockContainer(InventoryID.INV, magicLogId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(loginInv);

		GameStateChanged event = new GameStateChanged();
		event.setGameState(GameState.LOGGED_IN);
		org.mockito.ArgumentCaptor<Runnable> runnableCaptor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
		plugin.onGameStateChanged(event);
		verify(clientThread).invokeLater(runnableCaptor.capture());
		runnableCaptor.getValue().run();

		// Baseline is initialized with the login inventory
		Assert.assertTrue(plugin.snapshotInitialized);
		Assert.assertFalse(plugin.isNeedsRebaseline());

		// Now player fletches the log into Magic longbow (u) WITHOUT banking
		ItemContainer fletchedInv = mockContainer(InventoryID.INV, magicLongbowUId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(fletchedInv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, fletchedInv));

		// Verified: log is expensed, bow is credited
		Assert.assertEquals(-253L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(747L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(magicLongbowUId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(magicLogId).getQuantity());
	}

	@Test
	public void fletching_immediateAfterBankClose_isNotSwallowedByRebaseline()
	{
		int magicLogId = 1513;
		int magicLongbowUId = 70;
		stubTrackableItem(magicLogId, "Magic logs", 1_000L);
		stubTrackableItem(magicLongbowUId, "Magic longbow (u)", 747L);

		// Baseline empty inventory
		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(emptyInv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyInv));

		// 1. Open bank
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.BANKMAIN);
		plugin.onWidgetLoaded(open);

		// 2. Withdraw 1 Magic log while bank is open
		ItemContainer withLog = mockContainer(InventoryID.INV, magicLogId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(withLog);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, withLog));

		// 3. Close bank
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 0, false));

		// 4. Immediately fletch the Magic log into Magic longbow (u) (NO intervening inventory event)
		ItemContainer withBow = mockContainer(InventoryID.INV, magicLongbowUId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(withBow);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, withBow));

		// Verified: the fletch event was NOT swallowed by a deferred re-baseline!
		Assert.assertEquals(-253L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(747L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(magicLongbowUId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(magicLogId).getQuantity());
	}

	@Test
	public void fletching_choppedMagicLog_convertsCleanlyWithoutDoubleProfit()
	{
		int magicLogId = 1513;
		int magicLongbowUId = 70;
		stubTrackableItem(magicLogId, "Magic logs", 1_000L);
		stubTrackableItem(magicLongbowUId, "Magic longbow (u)", 747L);

		// Baseline empty inventory
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV)
		));

		// Chop 1 Magic log in session
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, magicLogId, 1)
		));
		Assert.assertEquals(1_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// Fletch the chopped log into Magic longbow (u)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, magicLongbowUId, 1)
		));

		// Net profit is the final product's value: 747 gp (no double gain)
		Assert.assertEquals(747L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(magicLongbowUId).getQuantity());
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(magicLogId));
	}

	@Test
	public void fletching_stringMagicLongbowU_consumesBowStringAndCreditsStrungBow()
	{
		int magicLongbowUId = 70;
		int bowStringId = 1777;
		int magicLongbowId = 859;
		stubTrackableItem(magicLongbowUId, "Magic longbow (u)", 747L);
		stubTrackableItem(bowStringId, "Bow string", 120L);
		stubTrackableItem(magicLongbowId, "Magic longbow", 1_350L);

		// Baseline with unstrung bow and bow string from bank
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, magicLongbowUId, 1, bowStringId, 1)
		));

		// String the bow -> 1 Magic longbow
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, magicLongbowId, 1)
		));

		// Net profit: 1,350 gp (strung bow) - (747 gp unstrung + 120 gp bow string) = 483 gp
		long expectedExpenses = 747L + 120L;
		Assert.assertEquals(1_350L - expectedExpenses, plugin.getSession().getTotalProfit());
		Assert.assertEquals(expectedExpenses, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(magicLongbowId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(magicLongbowUId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(bowStringId).getQuantity());
	}

	@Test
	public void crafting_cutUncutDiamond_chargesUncutExpenseAndCreditsDiamond()
	{
		int uncutDiamondId = 1617;
		int diamondId = 1601;
		stubTrackableItem(uncutDiamondId, "Uncut diamond", 2_000L);
		stubTrackableItem(diamondId, "Diamond", 2_500L);

		// Baseline with 1 Uncut diamond from bank
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, uncutDiamondId, 1)
		));

		// Cut into Diamond
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, diamondId, 1)
		));

		// Net profit: 2,500 - 2,000 = 500 gp
		Assert.assertEquals(500L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(2_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(2_500L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(diamondId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(uncutDiamondId).getQuantity());
	}

	@Test
	public void herblore_cleanGrimyRanarr_chargesGrimyExpenseAndCreditsCleanHerb()
	{
		int grimyRanarrId = 207;
		int ranarrWeedId = 257;
		stubTrackableItem(grimyRanarrId, "Grimy ranarr weed", 5_000L);
		stubTrackableItem(ranarrWeedId, "Ranarr weed", 5_200L);

		// Baseline with 1 Grimy ranarr weed from bank
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, grimyRanarrId, 1)
		));

		// Clean into Ranarr weed
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, ranarrWeedId, 1)
		));

		// Net profit: 5,200 - 5,000 = 200 gp
		Assert.assertEquals(200L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(5_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(5_200L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(ranarrWeedId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(grimyRanarrId).getQuantity());
	}

	@Test
	public void smithing_forgeRunePlatebody_consumes5Bars()
	{
		int runeBarId = 2363;
		int runePlatebodyId = 1127;
		stubTrackableItem(runeBarId, "Runite bar", 12_000L);
		stubTrackableItem(runePlatebodyId, "Rune platebody", 38_000L);

		// Baseline with 5 Runite bars from bank
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, runeBarId, 5)
		));

		// Forge 1 Rune platebody (consumes 5 bars)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, runePlatebodyId, 1)
		));

		// 5 bars @ 12k = 60k expense, 38k gross profit -> -22k net profit
		Assert.assertEquals(-22_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(60_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(38_000L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(runePlatebodyId).getQuantity());
		Assert.assertEquals(5, plugin.getSession().getTrackedExpenses().get(runeBarId).getQuantity());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(runeBarId));
	}

	@Test
	public void smithing_smeltMithrilBar_consumesOreAndCoal()
	{
		int mithrilOreId = 447;
		int coalId = 453;
		int mithrilBarId = 2359;
		stubTrackableItem(mithrilOreId, "Mithril ore", 150L);
		stubTrackableItem(coalId, "Coal", 150L);
		stubTrackableItem(mithrilBarId, "Mithril bar", 900L);

		// Baseline with 1 Mithril ore and 4 Coal from bank
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, mithrilOreId, 1, coalId, 4)
		));

		// Smelt 1 Mithril bar
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, mithrilBarId, 1)
		));

		// Expenses: 150 (ore) + 600 (4 coal) = 750 gp
		// Gross: 900 gp
		// Net profit: 900 - 750 = 150 gp
		Assert.assertEquals(150L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(750L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(900L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(mithrilBarId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(mithrilOreId).getQuantity());
		Assert.assertEquals(4, plugin.getSession().getTrackedExpenses().get(coalId).getQuantity());
	}

	@Test
	public void herblore_finishPrayerPotion_chargesUnfPotionAndSecondaryExpense()
	{
		int unfPotionId = 99;
		int snapeGrassId = 231;
		int prayerPotId = 139;
		stubTrackableItem(unfPotionId, "Ranarr potion (unf)", 7_500L);
		stubTrackableItem(snapeGrassId, "Snape grass", 300L);
		stubTrackableItem(prayerPotId, "Prayer potion(3)", 9_000L);

		// Baseline with 1 unf potion and 1 snape grass from bank
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, unfPotionId, 1, snapeGrassId, 1)
		));

		// Finish potion -> 1 Prayer potion(3)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, prayerPotId, 1)
		));

		// Gross: 9,000 gp
		// Expenses: 7,500 + 300 = 7,800 gp
		// Net: 1,200 gp
		Assert.assertEquals(1_200L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(7_800L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(9_000L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(prayerPotId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(unfPotionId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(snapeGrassId).getQuantity());
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(snapeGrassId));
	}

	@Test
	public void crafting_glassblowingVial_creditsProfitAndDoesNotSuppressAsByproduct()
	{
		int moltenGlassId = 1775;
		int vialId = 229;
		stubTrackableItem(moltenGlassId, "Molten glass", 100L);
		stubTrackableItem(vialId, "Vial", 20L);

		// Baseline with 1 Molten glass from bank
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, moltenGlassId, 1)
		));

		// Blow into Vial
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, vialId, 1)
		));

		// Gross: 20 gp (vial), Expense: 100 gp (molten glass) -> Net: -80 gp
		Assert.assertEquals(-80L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(100L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(20L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(vialId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(moltenGlassId).getQuantity());
	}

	@Test
	public void smithing_bronzeSmelting_expensesCopperAndTinOre()
	{
		int copperOreId = 436;
		int tinOreId = 438;
		int bronzeBarId = 2349;
		stubTrackableItem(copperOreId, "Copper ore", 50L);
		stubTrackableItem(tinOreId, "Tin ore", 50L);
		stubTrackableItem(bronzeBarId, "Bronze bar", 180L);

		// Baseline with 1 Copper ore and 1 Tin ore from bank
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, copperOreId, 1, tinOreId, 1)
		));

		// Smelt 1 Bronze bar
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, bronzeBarId, 1)
		));

		// Expenses: 50 + 50 = 100 gp
		// Gross: 180 gp
		// Net profit: 80 gp
		Assert.assertEquals(80L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(100L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(180L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(bronzeBarId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(copperOreId).getQuantity());
		Assert.assertEquals(1, plugin.getSession().getTrackedExpenses().get(tinOreId).getQuantity());
	}

	@Test
	public void widgetClosed_nonSuppressedWidget_doesNotSuppressLoot()
	{
		plugin.snapshotInitialized = true;
		plugin.setTrackingSuppressed(false);
		plugin.rebaselineGraceTicks = 0;

		stubTrackableItem(555, "Rune scimitar", 15000L);
		plugin.previousInventorySnapshot = snapshot();

		// A non-suppressed widget (e.g. dialogue or quest scroll) closes
		WidgetClosed widgetClosed = new WidgetClosed(9999, 0, false);
		plugin.onWidgetClosed(widgetClosed);

		Assert.assertEquals(0, plugin.rebaselineGraceTicks);
		Assert.assertFalse(plugin.isNeedsRebaseline());

		// Inventory receives drop immediately
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, 555, 1)
		));

		Assert.assertEquals(15000L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void widgetClosed_suppressedBankWidget_setsRebaselineGraceTicks()
	{
		plugin.snapshotInitialized = true;

		// Bank opens
		WidgetLoaded open = new WidgetLoaded();
		open.setGroupId(InterfaceID.BANKMAIN);
		plugin.onWidgetLoaded(open);
		Assert.assertTrue(plugin.isTrackingSuppressed());

		// Bank closes
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 0, false));
		Assert.assertFalse(plugin.isTrackingSuppressed());
		Assert.assertEquals(2, plugin.rebaselineGraceTicks);
		Assert.assertTrue(plugin.isNeedsRebaseline());
	}

	@Test
	public void resetSession_clearsDroppedAndEquippedItemsAndOverlay()
	{
		plugin.recentlyDroppedItems.put(555, 1);
		plugin.recentlyDroppedOwnedItems.put(556, 1);
		plugin.recentlyUnequippedItems.put(557, 1);
		plugin.recentlyEquippedItems.put(558, 1);

		plugin.resetSession();

		Assert.assertTrue(plugin.recentlyDroppedItems.isEmpty());
		Assert.assertTrue(plugin.recentlyDroppedOwnedItems.isEmpty());
		Assert.assertTrue(plugin.recentlyUnequippedItems.isEmpty());
		Assert.assertTrue(plugin.recentlyEquippedItems.isEmpty());
		org.mockito.Mockito.verify(goldDropOverlay).clear();
	}

	@Test
	public void resetTransientTrackingState_clearsAllTransientBuffersAndTimers()
	{
		plugin.recentlyDroppedItems.put(100, 2);
		plugin.recentlyDroppedOwnedItems.put(101, 3);
		plugin.recentlyUnequippedItems.put(102, 1);
		plugin.recentlyEquippedItems.put(103, 1);
		plugin.droppedItemTicks.put(100, 50);
		plugin.pendingWornAmmoExpenses.put(882, 10);
		plugin.lastSkillingGemId = 1617;
		plugin.lastSkillingGemTick = 55;
		plugin.lastFiremakingAnimTick = 56;
		plugin.lastTinderboxActionTick = 57;
		plugin.lastFiremakingChatTick = 58;
		plugin.lastAlchAnimTick = 59;
		plugin.lastAlchActionTick = 60;
		plugin.lastFarmingActionTick = 61;
		plugin.lastFarmingAnimTick = 62;
		plugin.lastFarmingChatTick = 63;
		plugin.lastLootingBagDepositTick = 64;
		plugin.lastLootingBagDepositItemId = 11941;
		plugin.lastLootingBagDepositItemName = "Looting bag";
		plugin.rebaselineGraceTicks = 4;

		plugin.resetTransientTrackingState();

		Assert.assertTrue(plugin.recentlyDroppedItems.isEmpty());
		Assert.assertTrue(plugin.recentlyDroppedOwnedItems.isEmpty());
		Assert.assertTrue(plugin.recentlyUnequippedItems.isEmpty());
		Assert.assertTrue(plugin.recentlyEquippedItems.isEmpty());
		Assert.assertTrue(plugin.droppedItemTicks.isEmpty());
		Assert.assertTrue(plugin.pendingWornAmmoExpenses.isEmpty());
		Assert.assertEquals(-1, plugin.lastSkillingGemId);
		Assert.assertEquals(-100, plugin.lastSkillingGemTick);
		Assert.assertEquals(-100, plugin.lastFiremakingAnimTick);
		Assert.assertEquals(-100, plugin.lastTinderboxActionTick);
		Assert.assertEquals(-100, plugin.lastFiremakingChatTick);
		Assert.assertEquals(-100, plugin.lastAlchAnimTick);
		Assert.assertEquals(-100, plugin.lastAlchActionTick);
		Assert.assertEquals(-100, plugin.lastFarmingActionTick);
		Assert.assertEquals(-100, plugin.lastFarmingAnimTick);
		Assert.assertEquals(-100, plugin.lastFarmingChatTick);
		Assert.assertEquals(-100, plugin.lastLootingBagDepositTick);
		Assert.assertEquals(-1, plugin.lastLootingBagDepositItemId);
		Assert.assertNull(plugin.lastLootingBagDepositItemName);
		Assert.assertEquals(0, plugin.rebaselineGraceTicks);
		org.mockito.Mockito.verify(goldDropOverlay, org.mockito.Mockito.atLeastOnce()).clear();
	}

	@Test
	public void checkGoalNotification_nullGoalName_doesNotThrowNPE()
	{
		when(config.goalAmount()).thenReturn("1000");
		when(config.goalName()).thenReturn(null);
		when(config.notifyOnGoal()).thenReturn(true);

		// Set profit >= goal
		stubTrackableItem(555, "Iron ore", 2000L);
		plugin.previousInventorySnapshot = snapshot();
		plugin.snapshotInitialized = true;
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, 555, 1)
		));

		// Should not throw NPE
		plugin.checkGoalNotification();
		Assert.assertTrue(plugin.goalCompletedNotified);
		org.mockito.Mockito.verify(notifier).notify(org.mockito.ArgumentMatchers.contains("Target GP"));
	}

	@Test
	public void configChanged_largeThresholdNumber_safelyClampedWithoutException()
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup(CoinFlowConfig.CONFIG_GROUP);
		event.setKey("goldDropMinThreshold");
		event.setNewValue("9999999999999abc");

		plugin.onConfigChanged(event);

		org.mockito.Mockito.verify(configManager).setConfiguration(
			CoinFlowConfig.CONFIG_GROUP,
			"goldDropMinThreshold",
			Integer.MAX_VALUE
		);
	}

	@Test
	public void firemakingEvents_updateDetectionTimers()
	{
		when(client.getTickCount()).thenReturn(50);

		// StatChanged
		StatChanged statEvent = new StatChanged(net.runelite.api.Skill.FIREMAKING, 100, 1, 1);
		plugin.previousSkillXp.put(net.runelite.api.Skill.FIREMAKING, 50);
		plugin.onStatChanged(statEvent);
		Assert.assertEquals(Integer.valueOf(50), plugin.lastSkillXpTicks.get(net.runelite.api.Skill.FIREMAKING));

		// MenuOptionClicked Use Tinderbox -> Logs
		net.runelite.api.MenuEntry entry = mock(net.runelite.api.MenuEntry.class);
		when(entry.getOption()).thenReturn("Use");
		when(entry.getTarget()).thenReturn("<col=ff9040>Tinderbox</col> -> <col=ff9040>Willow logs</col>");
		MenuOptionClicked menuEvent = new MenuOptionClicked(entry);
		plugin.onMenuOptionClicked(menuEvent);
		Assert.assertEquals(50, plugin.lastTinderboxActionTick);

		// AnimationChanged
		net.runelite.api.Player player = mock(net.runelite.api.Player.class);
		when(player.getAnimation()).thenReturn(AnimationID.HUMAN_CREATEFIRE_SINGLE);
		when(client.getLocalPlayer()).thenReturn(player);
		AnimationChanged animEvent = new AnimationChanged();
		animEvent.setActor(player);
		plugin.onAnimationChanged(animEvent);
		Assert.assertEquals(50, plugin.lastFiremakingAnimTick);

		// ChatMessage
		ChatMessage chatEvent = new ChatMessage(null, net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "The fire catches and the logs begin to burn.", "", 0);
		plugin.onChatMessage(chatEvent);
		Assert.assertEquals(50, plugin.lastFiremakingChatTick);
	}

	@Test
	public void lightLogsWithTinderbox_deductsFromProfitAsSupplyExpense()
	{
		int logsId = ItemID.WILLOW_LOGS;
		stubTrackableItem(logsId, "Willow logs", 25L);

		when(client.getTickCount()).thenReturn(10);
		plugin.lastSkillXpTicks.put(net.runelite.api.Skill.FIREMAKING, 10);
		plugin.snapshotInitialized = true;
		plugin.previousInventorySnapshot = snapshot(logsId, 5);

		// Player lights 1 log with tinderbox -> inventory drops from 5 to 4
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, logsId, 4)
		));

		// Supply expense should be recorded
		Assert.assertEquals(25L, plugin.session.getTotalExpenses());
		Assert.assertEquals(-25L, plugin.session.getTotalProfit());
		CoinFlowSession.TrackedItem expense = plugin.session.getTrackedExpenses().get(logsId);
		Assert.assertNotNull(expense);
		Assert.assertEquals(1, expense.getQuantity());
		Assert.assertEquals(25L, expense.getPriceEach());
	}

	@Test
	public void onMenuOptionClicked_reanimateCraftSetTrap_registersActiveSkillTicks()
	{
		when(client.getTickCount()).thenReturn(42);

		net.runelite.api.MenuEntry reanimateEntry = mock(net.runelite.api.MenuEntry.class);
		when(reanimateEntry.getOption()).thenReturn("Reanimate");
		when(reanimateEntry.getTarget()).thenReturn("Ensouled dragon head");
		plugin.onMenuOptionClicked(new MenuOptionClicked(reanimateEntry));
		Assert.assertEquals(Integer.valueOf(42), plugin.lastSkillXpTicks.get(net.runelite.api.Skill.PRAYER));
		Assert.assertEquals(Integer.valueOf(42), plugin.lastSkillXpTicks.get(net.runelite.api.Skill.MAGIC));

		net.runelite.api.MenuEntry castEntry = mock(net.runelite.api.MenuEntry.class);
		when(castEntry.getOption()).thenReturn("Cast");
		when(castEntry.getTarget()).thenReturn("<col=00ff00>Basic Reanimation</col> -> <col=ff9040>Ensouled chaos druid head</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(castEntry));
		Assert.assertEquals(Integer.valueOf(42), plugin.lastSkillXpTicks.get(net.runelite.api.Skill.PRAYER));
		Assert.assertEquals(Integer.valueOf(42), plugin.lastSkillXpTicks.get(net.runelite.api.Skill.MAGIC));

		net.runelite.api.MenuEntry craftEntry = mock(net.runelite.api.MenuEntry.class);
		when(craftEntry.getOption()).thenReturn("Craft-rune");
		when(craftEntry.getTarget()).thenReturn("Fire altar");
		plugin.onMenuOptionClicked(new MenuOptionClicked(craftEntry));
		Assert.assertEquals(Integer.valueOf(42), plugin.lastSkillXpTicks.get(net.runelite.api.Skill.RUNECRAFT));
		Assert.assertEquals(Integer.valueOf(42), plugin.lastSkillXpTicks.get(net.runelite.api.Skill.CRAFTING));

		net.runelite.api.MenuEntry trapEntry = mock(net.runelite.api.MenuEntry.class);
		when(trapEntry.getOption()).thenReturn("Set-trap");
		when(trapEntry.getTarget()).thenReturn("Deadfall");
		plugin.onMenuOptionClicked(new MenuOptionClicked(trapEntry));
		Assert.assertEquals(Integer.valueOf(42), plugin.lastSkillXpTicks.get(net.runelite.api.Skill.HUNTER));

		net.runelite.api.MenuEntry layEntry = mock(net.runelite.api.MenuEntry.class);
		when(layEntry.getOption()).thenReturn("Lay");
		when(layEntry.getTarget()).thenReturn("Box trap");
		plugin.onMenuOptionClicked(new MenuOptionClicked(layEntry));
		Assert.assertEquals(Integer.valueOf(42), plugin.lastSkillXpTicks.get(net.runelite.api.Skill.HUNTER));
	}

	@Test
	public void onMenuOptionClicked_collectFromClerk_triggersRebaselineGracePeriodAndSuppressesProfit()
	{
		plugin.snapshotInitialized = true;
		plugin.previousInventorySnapshot = snapshot();
		plugin.rebaselineGraceTicks = 0;
		stubTrackableItem(ItemID.COINS, "Coins", 1L);

		// Right-click "Collect" on Grand Exchange Clerk
		net.runelite.api.MenuEntry collectEntry = mock(net.runelite.api.MenuEntry.class);
		when(collectEntry.getOption()).thenReturn("Collect");
		when(collectEntry.getTarget()).thenReturn("<col=ffff00>Grand Exchange Clerk</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(collectEntry));

		Assert.assertTrue(plugin.isNeedsRebaseline());
		Assert.assertEquals(3, plugin.rebaselineGraceTicks);

		// Coins arrive from the sold offer (e.g. 5,000,000 coins)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, ItemID.COINS, 5000000)
		));

		// Verified: Zero profit credited, rebaseline consumed the coins into snapshot
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(Integer.valueOf(5000000), plugin.previousInventorySnapshot.getItems().get(ItemID.COINS));
		Assert.assertFalse(plugin.isNeedsRebaseline());
		Assert.assertEquals(0, plugin.rebaselineGraceTicks);
	}

	@Test
	public void onMenuOptionClicked_collectFromClerk_multiItemCollection_thenSubsequentLootTracked()
	{
		plugin.snapshotInitialized = true;
		plugin.previousInventorySnapshot = snapshot();
		plugin.rebaselineGraceTicks = 0;
		stubTrackableItem(ItemID.COINS, "Coins", 1L);
		stubTrackableItem(ItemID.RUNITE_ORE, "Runite ore", 11000L);
		stubTrackableItem(ItemID.IRON_ORE, "Iron ore", 200L);

		// Right-click "Collect" on Banker / Grand Exchange booth
		net.runelite.api.MenuEntry collectEntry = mock(net.runelite.api.MenuEntry.class);
		when(collectEntry.getOption()).thenReturn("Collect");
		when(collectEntry.getTarget()).thenReturn("Grand Exchange booth");
		plugin.onMenuOptionClicked(new MenuOptionClicked(collectEntry));

		Assert.assertTrue(plugin.isNeedsRebaseline());
		Assert.assertEquals(3, plugin.rebaselineGraceTicks);

		// Server deposits coins and runite ore
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, ItemID.COINS, 2500000, ItemID.RUNITE_ORE, 100)
		));

		// Verified: Zero profit credited, both coins and ores absorbed into baseline
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(Integer.valueOf(2500000), plugin.previousInventorySnapshot.getItems().get(ItemID.COINS));
		Assert.assertEquals(Integer.valueOf(100), plugin.previousInventorySnapshot.getItems().get(ItemID.RUNITE_ORE));

		// Subsequent legitimate loot (mined 1 iron ore) is tracked
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, ItemID.COINS, 2500000, ItemID.RUNITE_ORE, 100, ItemID.IRON_ORE, 1)
		));

		Assert.assertEquals(200L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void gloryRubbing_5to4_doesNotAddProfit()
	{
		int glory5Id = 11976;
		int glory4Id = 1712;
		stubTrackableItem(glory5Id, "Amulet of glory(5)", 13_000L);
		stubTrackableItem(glory4Id, "Amulet of glory(4)", 12_700L);

		// Baseline with Amulet of glory(5) in inventory
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, glory5Id, 1)
		));

		// Rubbed glory: (5) is replaced by (4)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, glory4Id, 1)
		));

		// Must not count the 12.7k glory(4) as profit/loot or full-item expense
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getNetProfit());
		verify(goldDropOverlay, never()).addDrop(anyString(), anyInt(), anyInt());
	}

	@Test
	public void slayerRingRubbing_3to2_inInventory_doesNotAddProfitOrBreakdownItem()
	{
		int slayerRing3Id = 11871;
		int slayerRing2Id = 11872;
		stubAlchableItem(slayerRing3Id, "Slayer ring (3)", 0L, 564);
		stubAlchableItem(slayerRing2Id, "Slayer ring (2)", 0L, 564);

		// Baseline with Slayer ring (3) in inventory
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, slayerRing3Id, 1)
		));

		// Used Slayer ring: (3) becomes (2)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, slayerRing2Id, 1)
		));

		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getNetProfit());
		Assert.assertFalse("Slayer ring (2) must not appear in trackedItems breakdown",
			plugin.getSession().getTrackedItems().containsKey(slayerRing2Id));
		verify(goldDropOverlay, never()).addDrop(anyString(), anyInt(), anyInt());
	}

	@Test
	public void slayerRingRubbing_3to2_equipped_doesNotPolluteUnequippedItemsOrTriggerProfitOnUnequip()
	{
		int slayerRing3Id = 11871;
		int slayerRing2Id = 11872;
		stubAlchableItem(slayerRing3Id, "Slayer ring (3)", 0L, 564);
		stubAlchableItem(slayerRing2Id, "Slayer ring (2)", 0L, 564);

		// Baseline: Slayer ring (3) equipped, empty inventory
		plugin.previousEquipmentSnapshot = snapshot(slayerRing3Id, 1);
		plugin.previousInventorySnapshot = snapshot();
		plugin.snapshotInitialized = true;

		// WORN updates: Slayer ring (3) degrades to (2) while equipped
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.WORN,
			mockContainer(InventoryID.WORN, slayerRing2Id, 1)
		));

		// In-place equipment degradation must NOT pollute recentlyUnequippedItems
		Assert.assertTrue(plugin.recentlyUnequippedItems.isEmpty());
		Assert.assertTrue(plugin.recentlyEquippedItems.isEmpty());

		// Player now unequips Slayer ring (2) into inventory
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, slayerRing2Id, 1)
		));

		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertFalse("Slayer ring (2) must not appear in trackedItems breakdown",
			plugin.getSession().getTrackedItems().containsKey(slayerRing2Id));
		verify(goldDropOverlay, never()).addDrop(anyString(), anyInt(), anyInt());
	}

	@Test
	public void slayerRing_loneGain_sceneTransition_doesNotAddProfit()
	{
		int slayerRing2Id = 11872;
		stubAlchableItem(slayerRing2Id, "Slayer ring (2)", 0L, 564);

		plugin.previousInventorySnapshot = snapshot();
		plugin.snapshotInitialized = true;

		// Lone gain of Slayer ring (2) arriving after teleport scene load
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, slayerRing2Id, 1)
		));

		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(slayerRing2Id));
		verify(goldDropOverlay, never()).addDrop(anyString(), anyInt(), anyInt());
	}

	@Test
	public void highAlchemy_coincidentalCoinsWithoutAlch_doesNotTriggerAlchemy()
	{
		int sharkId = 385;
		int coinsId = net.runelite.api.gameval.ItemID.COINS;

		stubAlchableItem(sharkId, "Shark", 1_000L, 630, "Eat");

		// Baseline: player has 1 Shark
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, sharkId, 1)
		));

		// Coincidental: Player eats Shark while picking up a 630 coin drop (HA price of shark is 630)
		// WITHOUT casting High Alchemy (no nature rune consumed, no alchemy animation/action)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, coinsId, 630)
		));

		// 630 coins counted as genuine drop profit; Shark counted as consumable food expense
		Assert.assertEquals(630L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-370L, plugin.getSession().getTotalProfit());
		verify(goldDropOverlay, never()).addDrop(anyString(), org.mockito.ArgumentMatchers.eq(sharkId), anyInt());
	}

	@Test
	public void gearSwap_partialUnequippedMatch_retainsRemainingUnequippedInMap()
	{
		plugin.snapshotInitialized = true;
		int itemId = 555;
		plugin.recentlyUnequippedItems.put(itemId, 2);

		// Inventory diff only gains 1 of item 555
		plugin.previousInventorySnapshot = snapshot();
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, itemId, 1)
		));

		// 1 unequipped item should still remain in recentlyUnequippedItems
		Assert.assertEquals(Integer.valueOf(1), plugin.recentlyUnequippedItems.get(itemId));
	}

	@Test
	public void gearSwap_unmatchedUnequippedItem_notWipedFromMap()
	{
		plugin.snapshotInitialized = true;
		int helmId = 555;
		int bodyId = 666;
		plugin.recentlyUnequippedItems.put(helmId, 1);
		plugin.recentlyUnequippedItems.put(bodyId, 1);

		// Inventory diff gains only helmId
		plugin.previousInventorySnapshot = snapshot();
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, helmId, 1)
		));

		// helmId consumed, but bodyId must still be retained
		Assert.assertFalse(plugin.recentlyUnequippedItems.containsKey(helmId));
		Assert.assertEquals(Integer.valueOf(1), plugin.recentlyUnequippedItems.get(bodyId));
	}

	@Test
	public void foodPortion_cakeThreeBites_costsOneThirdOfWholePrice()
	{
		int cakeId = 1891;
		int twoThirdsCakeId = 1893;
		stubTrackableItem(cakeId, "Cake", 1_500L);
		stubTrackableItem(twoThirdsCakeId, "2/3 cake", 1_000L);

		// Baseline with whole cake
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, cakeId, 1)
		));

		// Eat 1 slice -> inventory now has 2/3 cake
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, twoThirdsCakeId, 1)
		));

		// Expense charged must be 1,500 / 3 = 500 gp (not 1,500 / 2 = 750 gp)
		Assert.assertEquals(500L, plugin.getSession().getTotalExpenses());
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(twoThirdsCakeId));
	}

	@Test
	public void droppedItems_ttlExpiration_prunesStaleRecordsAfter600Ticks()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(10);
		plugin.snapshotInitialized = true;

		int itemId = 555;
		stubTrackableItem(itemId, "Iron ore", 200L);

		// Credit 1 iron ore in session
		plugin.previousInventorySnapshot = snapshot();
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, itemId, 1)
		));
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(itemId).getQuantity());

		// Drop the iron ore at tick 10
		plugin.previousInventorySnapshot = snapshot(itemId, 1);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV)
		));
		Assert.assertTrue(plugin.recentlyDroppedItems.containsKey(itemId));
		Assert.assertEquals(Integer.valueOf(10), plugin.droppedItemTicks.get(itemId));

		// Advance to tick 650 (640 ticks elapsed > 600 ticks TTL), trigger onGameTick on a % 50 tick
		when(client.getTickCount()).thenReturn(650);
		plugin.onGameTick(new GameTick());

		// Stale drop record must be pruned
		Assert.assertFalse(plugin.recentlyDroppedItems.containsKey(itemId));
		Assert.assertFalse(plugin.droppedItemTicks.containsKey(itemId));
	}

	@Test
	public void lootingBag_directLootPickupIntoBag_creditsProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int bagId = ItemID.LOOTING_BAG_OPEN;
		int runeScimitarId = ItemID.RUNE_SCIMITAR;
		stubTrackableItem(bagId, "Looting bag (open)", 0L);
		stubTrackableItem(runeScimitarId, "Rune scimitar", 15_000L);

		// Start: Inventory has open looting bag. Looting bag container is empty.
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1);
		ItemContainer emptyBagContainer1 = mockContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(emptyBagContainer1);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));
		Assert.assertTrue(plugin.snapshotInitialized);
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// Ground loot goes directly into Looting Bag container
		ItemContainer bagContainer = mockContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG, runeScimitarId, 1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(bagContainer);

		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, bagContainer));

		// Verified: Profit is credited and expenses remain 0
		Assert.assertEquals(15_000L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(15_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertTrue(plugin.getSession().getTrackedItems().containsKey(runeScimitarId));
	}

	@Test
	public void lootingBag_depositingFromInventoryIntoBag_netZeroExpenseAndProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int bagId = ItemID.LOOTING_BAG;
		int runeOreId = ItemID.RUNITE_ORE;
		stubTrackableItem(bagId, "Looting bag", 0L);
		stubTrackableItem(runeOreId, "Rune ore", 11_000L);

		// Start: Inventory has looting bag + 5 Rune ores. Looting bag container is empty.
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1, runeOreId, 5);
		ItemContainer emptyBagContainer2 = mockContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(emptyBagContainer2);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));
		Assert.assertTrue(plugin.snapshotInitialized);

		// Deposit 5 Rune ores into Looting Bag:
		// Inventory now only contains Looting bag
		// Looting Bag container now contains 5 Rune ores
		ItemContainer inv2 = mockContainer(InventoryID.INV, bagId, 1);
		ItemContainer bagContainer = mockContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG, runeOreId, 5);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(bagContainer);

		// Inv container changed fires
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Net delta is 0: total Rune ores remained 5 across inventory + looting bag
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// Looting bag container changed fires
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, bagContainer));

		// Still 0
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void lootingBag_depositingBagAtBank_netZeroExpenseAndProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int bagId = ItemID.LOOTING_BAG;
		int runePickaxeId = ItemID.RUNE_PICKAXE;
		stubTrackableItem(bagId, "Looting bag", 0L);
		stubTrackableItem(runePickaxeId, "Rune pickaxe", 18_000L);

		// Start: Looting bag in inventory with 2 Rune pickaxes inside the bag container
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1);
		ItemContainer bagContainer = mockContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG, runePickaxeId, 2);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(bagContainer);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));
		Assert.assertTrue(plugin.snapshotInitialized);

		// Open Bank
		WidgetLoaded openBank = new WidgetLoaded();
		openBank.setGroupId(InterfaceID.BANKMAIN);
		plugin.onWidgetLoaded(openBank);
		Assert.assertTrue(plugin.isTrackingSuppressed());

		// Empty looting bag into bank: bag container is now empty
		ItemContainer emptyBagContainer = mockContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(emptyBagContainer);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, emptyBagContainer));

		// Close Bank
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 0, false));

		// Grace ticks pass
		plugin.onGameTick(new GameTick());
		plugin.onGameTick(new GameTick());

		// Verified: Depositing bag contents at bank did NOT trigger phantom expenses or losses
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void lootingBag_viewingBagInWilderness_doesNotSuppressTracking()
	{
		// Opening wilderness looting bag interface does not pause tracking
		WidgetLoaded openBag = new WidgetLoaded();
		openBag.setGroupId(InterfaceID.WILDERNESS_LOOTINGBAG);
		plugin.onWidgetLoaded(openBag);

		Assert.assertFalse(plugin.isTrackingSuppressed());
		Assert.assertFalse(plugin.isNeedsRebaseline());
	}

	@Test
	public void lootingBag_togglingOpenAndCloseState_producesZeroDiff()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int closedBagId = ItemID.LOOTING_BAG;
		int openBagId = ItemID.LOOTING_BAG_OPEN;
		stubTrackableItem(closedBagId, "Looting bag", 0L);
		stubTrackableItem(openBagId, "Looting bag (open)", 0L);

		// Start with closed looting bag
		ItemContainer inv1 = mockContainer(InventoryID.INV, closedBagId, 1);
		ItemContainer emptyBagContainer = mockContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(emptyBagContainer);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));
		Assert.assertTrue(plugin.snapshotInitialized);

		// Player clicks "Open" on the bag in inventory
		ItemContainer inv2 = mockContainer(InventoryID.INV, openBagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Verified: Toggling open does not generate lost or gained items
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(openBagId));

		// Player clicks "Close" on the bag
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Verified: Toggling back to closed still produces 0 delta
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void lootingBag_checkBagAfterLogin_doesNotCountPreExistingItemsAsProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int bagId = ItemID.LOOTING_BAG_OPEN;
		int runeScimitarId = ItemID.RUNE_SCIMITAR;
		int bonesId = ItemID.DRAGON_BONES;
		stubTrackableItem(bagId, "Looting bag (open)", 0L);
		stubTrackableItem(runeScimitarId, "Rune scimitar", 15_000L);
		stubTrackableItem(bonesId, "Dragon bones", 2_500L);

		// Start: Player logs in carrying looting bag with items inside.
		// Before "Check", container 516 is null (server has not sent it yet).
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(null);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));
		Assert.assertTrue(plugin.snapshotInitialized);
		Assert.assertFalse(plugin.lootingBagInitialized);
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// Player right-clicks "Check" on the looting bag just after login.
		// Server transmits container 516 with 1 Rune scimitar and 5 Dragon bones.
		ItemContainer bagContainer = mockContainer(
			net.runelite.api.gameval.InventoryID.LOOTING_BAG,
			runeScimitarId, 1,
			bonesId, 5
		);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(bagContainer);

		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, bagContainer));

		// Verified: Checking bag after login does NOT add pre-existing items to session profit!
		Assert.assertTrue(plugin.lootingBagInitialized);
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(runeScimitarId));
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(bonesId));
		verify(goldDropOverlay, never()).addDrop(anyString(), anyInt(), anyInt());
	}

	@Test
	public void lootingBag_groundPickupInWilderness_immediatelyCreditsProfitOnGameTick()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(100);

		net.runelite.api.WorldView worldView = mock(net.runelite.api.WorldView.class);
		when(worldView.getBaseX()).thenReturn(1000);
		when(worldView.getBaseY()).thenReturn(2000);
		when(worldView.getPlane()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		Player player = mock(Player.class);
		WorldPoint playerLocation = new WorldPoint(1010, 2020, 0);
		when(player.getWorldLocation()).thenReturn(playerLocation);
		when(client.getLocalPlayer()).thenReturn(player);

		int bagId = ItemID.LOOTING_BAG_OPEN;
		int bonesId = ItemID.DRAGON_BONES;
		stubTrackableItem(bagId, "Looting bag (open)", 0L);
		stubTrackableItem(bonesId, "Dragon bones", 2_500L);

		// Start: Player has open looting bag in inventory
		ItemContainer inv = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(null);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));
		Assert.assertTrue(plugin.snapshotInitialized);
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// Player clicks "Take" Dragon bones on ground at (1010, 2020)
		net.runelite.api.MenuEntry takeEntry = mock(net.runelite.api.MenuEntry.class);
		when(takeEntry.getOption()).thenReturn("Take");
		when(takeEntry.getIdentifier()).thenReturn(bonesId);
		when(takeEntry.getParam0()).thenReturn(10);
		when(takeEntry.getParam1()).thenReturn(20);
		plugin.onMenuOptionClicked(new MenuOptionClicked(takeEntry));

		// Ground item despawns as it is picked up into the open bag
		TileItem tileItem = mock(TileItem.class);
		when(tileItem.getId()).thenReturn(bonesId);
		when(tileItem.getQuantity()).thenReturn(1);
		Tile tile = mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(playerLocation);

		plugin.onItemDespawned(new ItemDespawned(tile, tileItem));

		// Server does not send container 516 or 93 update; game tick fires
		plugin.onGameTick(new GameTick());

		// Verified: Profit is immediately credited on the game tick of pickup!
		Assert.assertEquals(2_500L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(2_500L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertTrue(plugin.getSession().getTrackedItems().containsKey(bonesId));
		verify(goldDropOverlay, times(1)).addDrop(anyString(), org.mockito.ArgumentMatchers.eq(bonesId), anyInt());

		// Later: Player checks the looting bag (server sends container 516)
		ItemContainer bagContainer = mockContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG, bonesId, 1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(bagContainer);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, bagContainer));

		// Verified: Bag check does NOT duplicate profit! Still exactly 2,500L
		Assert.assertEquals(2_500L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(2_500L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		verify(goldDropOverlay, times(1)).addDrop(anyString(), org.mockito.ArgumentMatchers.eq(bonesId), anyInt());
	}

	@Test
	public void lootingBag_groundPickupOutsideWilderness_doesNotCreditViaPending()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(0);
		when(client.getTickCount()).thenReturn(100);

		net.runelite.api.WorldView worldView = mock(net.runelite.api.WorldView.class);
		when(worldView.getBaseX()).thenReturn(1000);
		when(worldView.getBaseY()).thenReturn(2000);
		when(worldView.getPlane()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		Player player = mock(Player.class);
		WorldPoint playerLocation = new WorldPoint(1010, 2020, 0);
		when(player.getWorldLocation()).thenReturn(playerLocation);
		when(client.getLocalPlayer()).thenReturn(player);

		int bagId = ItemID.LOOTING_BAG_OPEN;
		int bonesId = ItemID.DRAGON_BONES;
		stubTrackableItem(bagId, "Looting bag (open)", 0L);
		stubTrackableItem(bonesId, "Dragon bones", 2_500L);

		ItemContainer inv = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		net.runelite.api.MenuEntry takeEntry = mock(net.runelite.api.MenuEntry.class);
		when(takeEntry.getOption()).thenReturn("Take");
		when(takeEntry.getIdentifier()).thenReturn(bonesId);
		when(takeEntry.getParam0()).thenReturn(10);
		when(takeEntry.getParam1()).thenReturn(20);
		plugin.onMenuOptionClicked(new MenuOptionClicked(takeEntry));

		TileItem tileItem = mock(TileItem.class);
		when(tileItem.getId()).thenReturn(bonesId);
		when(tileItem.getQuantity()).thenReturn(1);
		Tile tile = mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(playerLocation);

		plugin.onItemDespawned(new ItemDespawned(tile, tileItem));
		plugin.onGameTick(new GameTick());

		// Outside wilderness, items do not enter open looting bag directly
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void lootingBag_groundPickupUntradeableItem_doesNotCreditViaPending()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(100);

		net.runelite.api.WorldView worldView = mock(net.runelite.api.WorldView.class);
		when(worldView.getBaseX()).thenReturn(1000);
		when(worldView.getBaseY()).thenReturn(2000);
		when(worldView.getPlane()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		Player player = mock(Player.class);
		WorldPoint playerLocation = new WorldPoint(1010, 2020, 0);
		when(player.getWorldLocation()).thenReturn(playerLocation);
		when(client.getLocalPlayer()).thenReturn(player);

		int bagId = ItemID.LOOTING_BAG_OPEN;
		int clueScrollId = 2677; // Clue scroll (easy) - untradeable
		stubTrackableItem(bagId, "Looting bag (open)", 0L);

		ItemComposition clueComp = mock(ItemComposition.class);
		when(clueComp.getName()).thenReturn("Clue scroll (easy)");
		when(clueComp.isTradeable()).thenReturn(false);
		when(itemManager.getItemComposition(clueScrollId)).thenReturn(clueComp);
		when(itemManager.canonicalize(clueScrollId)).thenReturn(clueScrollId);

		ItemContainer inv = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		net.runelite.api.MenuEntry takeEntry = mock(net.runelite.api.MenuEntry.class);
		when(takeEntry.getOption()).thenReturn("Take");
		when(takeEntry.getIdentifier()).thenReturn(clueScrollId);
		when(takeEntry.getParam0()).thenReturn(10);
		when(takeEntry.getParam1()).thenReturn(20);
		plugin.onMenuOptionClicked(new MenuOptionClicked(takeEntry));

		TileItem tileItem = mock(TileItem.class);
		when(tileItem.getId()).thenReturn(clueScrollId);
		when(tileItem.getQuantity()).thenReturn(1);
		Tile tile = mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(playerLocation);

		plugin.onItemDespawned(new ItemDespawned(tile, tileItem));
		plugin.onGameTick(new GameTick());

		// Untradeable item cannot enter looting bag
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void lootingBag_checkBagAtLogin_thenPickupMultipleItemsIntoBag_doesNotResetProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(100);

		net.runelite.api.WorldView worldView = mock(net.runelite.api.WorldView.class);
		when(worldView.getBaseX()).thenReturn(1000);
		when(worldView.getBaseY()).thenReturn(2000);
		when(worldView.getPlane()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		Player player = mock(Player.class);
		WorldPoint playerLocation = new WorldPoint(1010, 2020, 0);
		when(player.getWorldLocation()).thenReturn(playerLocation);
		when(client.getLocalPlayer()).thenReturn(player);

		int bagId = ItemID.LOOTING_BAG_OPEN;
		int ironMedHelmId = ItemID.IRON_MED_HELM;
		int tarrominId = 203; // Grimy tarromin
		int bonesId = ItemID.BONES;
		int harralanderId = 205; // Grimy harralander

		stubTrackableItem(bagId, "Looting bag (open)", 0L);
		stubTrackableItem(ironMedHelmId, "Iron med helm", 100L);
		stubTrackableItem(tarrominId, "Grimy tarromin", 150L);
		stubTrackableItem(bonesId, "Bones", 80L);
		stubTrackableItem(harralanderId, "Grimy harralander", 314L);

		// 1. Login: Inventory has open bag. Container 516 is null initially.
		ItemContainer inv = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(null);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));
		Assert.assertTrue(plugin.snapshotInitialized);
		Assert.assertFalse(plugin.lootingBagInitialized);
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// 2. Player checks looting bag upon login: container 516 arrives with existing loot
		ItemContainer initialBag = mockContainer(
			net.runelite.api.gameval.InventoryID.LOOTING_BAG,
			ironMedHelmId, 1,
			tarrominId, 1,
			bonesId, 2
		);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(initialBag);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, initialBag));

		Assert.assertTrue(plugin.lootingBagInitialized);
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// 3. Bag interface is closed (container 516 becomes null in client memory)
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(null);

		// 4. Player picks up Grimy harralander from ground into open bag
		net.runelite.api.MenuEntry takeHerb = mock(net.runelite.api.MenuEntry.class);
		when(takeHerb.getOption()).thenReturn("Take");
		when(takeHerb.getIdentifier()).thenReturn(harralanderId);
		when(takeHerb.getParam0()).thenReturn(10);
		when(takeHerb.getParam1()).thenReturn(20);
		plugin.onMenuOptionClicked(new MenuOptionClicked(takeHerb));

		TileItem herbItem = mock(TileItem.class);
		when(herbItem.getId()).thenReturn(harralanderId);
		when(herbItem.getQuantity()).thenReturn(1);
		Tile tile = mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(playerLocation);

		plugin.onItemDespawned(new ItemDespawned(tile, herbItem));
		plugin.onGameTick(new GameTick());

		// Harralander (+314 gp) is credited
		Assert.assertEquals(314L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// 5. Player picks up Bones from ground into open bag
		when(client.getTickCount()).thenReturn(106);
		net.runelite.api.MenuEntry takeBones = mock(net.runelite.api.MenuEntry.class);
		when(takeBones.getOption()).thenReturn("Take");
		when(takeBones.getIdentifier()).thenReturn(bonesId);
		when(takeBones.getParam0()).thenReturn(10);
		when(takeBones.getParam1()).thenReturn(20);
		plugin.onMenuOptionClicked(new MenuOptionClicked(takeBones));

		TileItem boneItem = mock(TileItem.class);
		when(boneItem.getId()).thenReturn(bonesId);
		when(boneItem.getQuantity()).thenReturn(1);

		plugin.onItemDespawned(new ItemDespawned(tile, boneItem));
		plugin.onGameTick(new GameTick());

		// Profit must now be 314 + 80 = 394 gp! NOT reset to 0!
		Assert.assertEquals(394L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertTrue(plugin.getSession().getTrackedItems().containsKey(harralanderId));
		Assert.assertTrue(plugin.getSession().getTrackedItems().containsKey(bonesId));

		// 6. Later, server emits container 516 update with all items
		ItemContainer updatedBag = mockContainer(
			net.runelite.api.gameval.InventoryID.LOOTING_BAG,
			ironMedHelmId, 1,
			tarrominId, 1,
			bonesId, 3,
			harralanderId, 1
		);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(updatedBag);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, updatedBag));

		// Profit remains exactly 394 gp
		Assert.assertEquals(394L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void lootingBag_depositItemFromInventoryInWilderness_doesNotDeductProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(200);

		int bagId = ItemID.LOOTING_BAG_OPEN;
		int tarrominId = 203; // Grimy tarromin
		stubTrackableItem(bagId, "Looting bag (open)", 0L);
		stubTrackableItem(tarrominId, "Grimy tarromin", 243L);

		// 1. Initial inventory: Player carries looting bag
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(null);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// 2. Player kills monster, gains Grimy tarromin in inventory (not yet in bag)
		ItemContainer inv2 = mockContainer(InventoryID.INV, bagId, 1, tarrominId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Profit is 243 gp
		Assert.assertEquals(243L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// 3. Player uses Grimy tarromin on Looting Bag: "Use Grimy tarromin -> Looting bag (open)"
		net.runelite.api.MenuEntry useOnBag = mock(net.runelite.api.MenuEntry.class);
		when(useOnBag.getOption()).thenReturn("Use");
		when(useOnBag.getTarget()).thenReturn("<col=ff9040>Use Grimy tarromin</col> -> <col=ff9040>Looting bag (open)</col>");
		when(useOnBag.getIdentifier()).thenReturn(bagId);

		Widget selectedItemWidget = mock(Widget.class);
		when(selectedItemWidget.getItemId()).thenReturn(tarrominId);
		when(client.getSelectedWidget()).thenReturn(selectedItemWidget);

		plugin.onMenuOptionClicked(new MenuOptionClicked(useOnBag));

		// 4. Server removes Grimy tarromin from inventory (container 93 update)
		// Container 516 does NOT fire in Wilderness
		ItemContainer inv3 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv3);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv3));

		// Verified: Depositing Grimy tarromin into looting bag DOES NOT deduct profit! Still 243 gp!
		Assert.assertEquals(243L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(1, plugin.getSnapshotService().getLootingBagContents().get(tarrominId).intValue());

		// 5. Player later right-clicks "Check" on looting bag (container 516 arrives)
		ItemContainer bagContainer = mockContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG, tarrominId, 1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(bagContainer);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, bagContainer));

		// Profit remains exactly 243 gp, zero diff on bag check
		Assert.assertEquals(243L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void lootingBag_storeOptionOnItemInWilderness_transfersToBagWithoutLoss()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(300);

		int bagId = ItemID.LOOTING_BAG;
		int runeOreId = ItemID.RUNITE_ORE;
		stubTrackableItem(bagId, "Looting bag", 0L);
		stubTrackableItem(runeOreId, "Runite ore", 11_000L);

		// Start with bag + 2 Runite ores gained
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		ItemContainer inv2 = mockContainer(InventoryID.INV, bagId, 1, runeOreId, 2);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		Assert.assertEquals(22_000L, plugin.getSession().getTotalProfit());

		// Player clicks "Store-All" on Runite ore
		net.runelite.api.MenuEntry storeAll = mock(net.runelite.api.MenuEntry.class);
		when(storeAll.getOption()).thenReturn("Store-All");
		when(storeAll.getTarget()).thenReturn("<col=ff9040>Runite ore</col>");
		when(storeAll.getItemId()).thenReturn(runeOreId);
		plugin.onMenuOptionClicked(new MenuOptionClicked(storeAll));

		// Inventory loses the 2 Runite ores
		ItemContainer inv3 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv3);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv3));

		// Profit remains 22,000 gp
		Assert.assertEquals(22_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(2, plugin.getSnapshotService().getLootingBagContents().get(runeOreId).intValue());
	}

	@Test
	public void lootingBag_clickItemInInventoryAndClickBag_thenCheck_thenClose_multipleChecks()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(400);

		int bagId = ItemID.LOOTING_BAG_OPEN;
		int ranarrId = 207; // Grimy ranarr weed
		stubTrackableItem(bagId, "Looting bag (open)", 0L);
		stubTrackableItem(ranarrId, "Grimy ranarr weed", 7_500L);

		// 1. Initial inventory with open looting bag
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(null);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// 2. Kill monster: Grimy ranarr weed drops into inventory
		ItemContainer inv2 = mockContainer(InventoryID.INV, bagId, 1, ranarrId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));
		Assert.assertEquals(7_500L, plugin.getSession().getTotalProfit());

		// 3. Player clicks "Use" on Grimy ranarr weed in inventory, then clicks Looting bag
		net.runelite.api.MenuEntry useOnBag = mock(net.runelite.api.MenuEntry.class);
		when(useOnBag.getOption()).thenReturn("Use");
		when(useOnBag.getTarget()).thenReturn("<col=ff9040>Use Grimy ranarr weed</col> -> <col=ff9040>Looting bag (open)</col>");
		when(useOnBag.getIdentifier()).thenReturn(bagId);

		Widget selectedItemWidget = mock(Widget.class);
		when(selectedItemWidget.getItemId()).thenReturn(ranarrId);
		when(client.getSelectedWidget()).thenReturn(selectedItemWidget);

		plugin.onMenuOptionClicked(new MenuOptionClicked(useOnBag));

		// 4. Server updates inventory container (Grimy ranarr weed removed, container 516 does not fire)
		ItemContainer inv3 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv3);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv3));

		// Profit is preserved at 7,500 gp (NOT deducted as drop)
		Assert.assertEquals(7_500L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(1, plugin.getSnapshotService().getLootingBagContents().get(ranarrId).intValue());

		// 5. Player right-clicks "Check" on the looting bag -> container 516 opens
		ItemContainer bagContainer = mockContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG, ranarrId, 1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(bagContainer);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, bagContainer));

		// Profit remains exactly 7,500 gp (NOT duplicated)
		Assert.assertEquals(7_500L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// 6. Player closes the looting bag interface -> container 516 becomes null
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(null);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv3));

		// Profit remains exactly 7,500 gp (closing bag does NOT deduct items as lost)
		Assert.assertEquals(7_500L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// 7. Player right-clicks "Check" AGAIN -> container 516 arrives again
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(bagContainer);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, bagContainer));

		// Profit remains exactly 7,500 gp
		Assert.assertEquals(7_500L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// 8. Player closes bag again -> container 516 null
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(null);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv3));
		Assert.assertEquals(7_500L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void lootingBag_depositStackableCoinsAndRunes_preservesProfitAcrossCheckAndClose()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(500);

		int bagId = ItemID.LOOTING_BAG_OPEN;
		int coinsId = ItemID.COINS;
		int chaosRuneId = 562; // Chaos rune
		stubTrackableItem(bagId, "Looting bag (open)", 0L);
		stubTrackableItem(coinsId, "Coins", 1L);
		stubTrackableItem(chaosRuneId, "Chaos rune", 100L);

		// Baseline
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Gain 50,000 Coins and 100 Chaos runes (10,000 gp) = 60,000 gp profit
		ItemContainer inv2 = mockContainer(InventoryID.INV, bagId, 1, coinsId, 50_000, chaosRuneId, 100);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));
		Assert.assertEquals(60_000L, plugin.getSession().getTotalProfit());

		// Deposit 40 Chaos runes into bag: Use Chaos rune -> Looting bag
		net.runelite.api.MenuEntry useRunes = mock(net.runelite.api.MenuEntry.class);
		when(useRunes.getOption()).thenReturn("Use");
		when(useRunes.getTarget()).thenReturn("<col=ff9040>Use Chaos rune</col> -> <col=ff9040>Looting bag (open)</col>");
		when(useRunes.getIdentifier()).thenReturn(bagId);
		Widget runesWidget = mock(Widget.class);
		when(runesWidget.getItemId()).thenReturn(chaosRuneId);
		when(client.getSelectedWidget()).thenReturn(runesWidget);
		plugin.onMenuOptionClicked(new MenuOptionClicked(useRunes));

		// Inv loses 40 Chaos runes (60 remain in inv, 40 in bag)
		ItemContainer inv3 = mockContainer(InventoryID.INV, bagId, 1, coinsId, 50_000, chaosRuneId, 60);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv3);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv3));

		Assert.assertEquals(60_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(40, plugin.getSnapshotService().getLootingBagContents().get(chaosRuneId).intValue());

		// Deposit all 50,000 Coins into bag
		net.runelite.api.MenuEntry useCoins = mock(net.runelite.api.MenuEntry.class);
		when(useCoins.getOption()).thenReturn("Use");
		when(useCoins.getTarget()).thenReturn("<col=ff9040>Use Coins</col> -> <col=ff9040>Looting bag (open)</col>");
		when(useCoins.getIdentifier()).thenReturn(bagId);
		Widget coinsWidget = mock(Widget.class);
		when(coinsWidget.getItemId()).thenReturn(coinsId);
		when(client.getSelectedWidget()).thenReturn(coinsWidget);
		plugin.onMenuOptionClicked(new MenuOptionClicked(useCoins));

		// Inv loses 50,000 Coins
		ItemContainer inv4 = mockContainer(InventoryID.INV, bagId, 1, chaosRuneId, 60);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv4);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv4));

		Assert.assertEquals(60_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(50_000, plugin.getSnapshotService().getLootingBagContents().get(coinsId).intValue());
		Assert.assertEquals(40, plugin.getSnapshotService().getLootingBagContents().get(chaosRuneId).intValue());

		// Check bag: container 516 arrives with 40 Chaos runes and 50,000 Coins
		ItemContainer bagContainer = mockContainer(
			net.runelite.api.gameval.InventoryID.LOOTING_BAG,
			chaosRuneId, 40,
			coinsId, 50_000
		);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(bagContainer);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, bagContainer));

		// Profit remains 60,000 gp
		Assert.assertEquals(60_000L, plugin.getSession().getTotalProfit());

		// Close bag: container 516 null
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(null);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv4));
		Assert.assertEquals(60_000L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void lootingBag_groundPickupAndManualDepositCombined_reconcilesCleanlyOnCheck()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(600);

		net.runelite.api.WorldView worldView = mock(net.runelite.api.WorldView.class);
		when(worldView.getBaseX()).thenReturn(1000);
		when(worldView.getBaseY()).thenReturn(2000);
		when(worldView.getPlane()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		Player player = mock(Player.class);
		WorldPoint playerLoc = new WorldPoint(1010, 2020, 0);
		when(player.getWorldLocation()).thenReturn(playerLoc);
		when(client.getLocalPlayer()).thenReturn(player);

		int bagId = ItemID.LOOTING_BAG_OPEN;
		int scimitarId = ItemID.RUNE_SCIMITAR;
		int bonesId = ItemID.DRAGON_BONES;
		stubTrackableItem(bagId, "Looting bag (open)", 0L);
		stubTrackableItem(scimitarId, "Rune scimitar", 15_000L);
		stubTrackableItem(bonesId, "Dragon bones", 2_500L);

		// Baseline
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(null);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// 1. Ground pickup of Rune scimitar into open bag
		net.runelite.api.MenuEntry takeScimitar = mock(net.runelite.api.MenuEntry.class);
		when(takeScimitar.getOption()).thenReturn("Take");
		when(takeScimitar.getIdentifier()).thenReturn(scimitarId);
		when(takeScimitar.getParam0()).thenReturn(10);
		when(takeScimitar.getParam1()).thenReturn(20);
		plugin.onMenuOptionClicked(new MenuOptionClicked(takeScimitar));

		TileItem scimitarTile = mock(TileItem.class);
		when(scimitarTile.getId()).thenReturn(scimitarId);
		when(scimitarTile.getQuantity()).thenReturn(1);
		Tile tile = mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(playerLoc);
		plugin.onItemDespawned(new ItemDespawned(tile, scimitarTile));
		plugin.onGameTick(new GameTick());

		// Profit = 15,000 gp
		Assert.assertEquals(15_000L, plugin.getSession().getTotalProfit());

		// 2. Dragon bones picked up directly into regular inventory
		ItemContainer inv2 = mockContainer(InventoryID.INV, bagId, 1, bonesId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Profit = 15,000 + 2,500 = 17,500 gp
		Assert.assertEquals(17_500L, plugin.getSession().getTotalProfit());

		// 3. Player manually deposits Dragon bones into Looting bag
		net.runelite.api.MenuEntry useBones = mock(net.runelite.api.MenuEntry.class);
		when(useBones.getOption()).thenReturn("Use");
		when(useBones.getTarget()).thenReturn("<col=ff9040>Use Dragon bones</col> -> <col=ff9040>Looting bag (open)</col>");
		when(useBones.getIdentifier()).thenReturn(bagId);
		Widget bonesWidget = mock(Widget.class);
		when(bonesWidget.getItemId()).thenReturn(bonesId);
		when(client.getSelectedWidget()).thenReturn(bonesWidget);
		plugin.onMenuOptionClicked(new MenuOptionClicked(useBones));

		// Inventory loses Dragon bones
		ItemContainer inv3 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv3);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv3));

		// Profit remains 17,500 gp
		Assert.assertEquals(17_500L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1, plugin.getSnapshotService().getLootingBagContents().get(scimitarId).intValue());
		Assert.assertEquals(1, plugin.getSnapshotService().getLootingBagContents().get(bonesId).intValue());

		// 4. Check bag: container 516 arrives containing both Rune scimitar and Dragon bones
		ItemContainer bagContainer = mockContainer(
			net.runelite.api.gameval.InventoryID.LOOTING_BAG,
			scimitarId, 1,
			bonesId, 1
		);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(bagContainer);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, bagContainer));

		// Profit remains 17,500 gp without duplicate or reset
		Assert.assertEquals(17_500L, plugin.getSession().getTotalProfit());

		// 5. Close bag
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(null);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv3));
		Assert.assertEquals(17_500L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void lootingBag_droppingRealItemDeductsProfit_whileDepositingIntoBagDoesNot()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(700);

		int bagId = ItemID.LOOTING_BAG;
		int ironOreId = ItemID.IRON_ORE;
		int coalId = ItemID.COAL;
		stubTrackableItem(bagId, "Looting bag", 0L);
		stubTrackableItem(ironOreId, "Iron ore", 200L);
		stubTrackableItem(coalId, "Coal", 150L);

		// Baseline
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Gain Iron ore and Coal
		ItemContainer inv2 = mockContainer(InventoryID.INV, bagId, 1, ironOreId, 1, coalId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Profit = 350 gp
		Assert.assertEquals(350L, plugin.getSession().getTotalProfit());

		// 1. Deposit Coal into Looting bag: Use Coal -> Looting bag
		net.runelite.api.MenuEntry useCoal = mock(net.runelite.api.MenuEntry.class);
		when(useCoal.getOption()).thenReturn("Use");
		when(useCoal.getTarget()).thenReturn("<col=ff9040>Use Coal</col> -> <col=ff9040>Looting bag</col>");
		when(useCoal.getIdentifier()).thenReturn(bagId);
		Widget coalWidget = mock(Widget.class);
		when(coalWidget.getItemId()).thenReturn(coalId);
		when(client.getSelectedWidget()).thenReturn(coalWidget);
		plugin.onMenuOptionClicked(new MenuOptionClicked(useCoal));

		// Inventory loses Coal
		ItemContainer inv3 = mockContainer(InventoryID.INV, bagId, 1, ironOreId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv3);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv3));

		// Verified: Depositing Coal does NOT deduct profit. Still 350 gp!
		Assert.assertEquals(350L, plugin.getSession().getTotalProfit());

		// 2. Drop Iron ore on ground: Menu option "Drop" on Iron ore
		net.runelite.api.MenuEntry dropOre = mock(net.runelite.api.MenuEntry.class);
		when(dropOre.getOption()).thenReturn("Drop");
		when(dropOre.getTarget()).thenReturn("<col=ff9040>Iron ore</col>");
		when(dropOre.getItemId()).thenReturn(ironOreId);
		when(client.getSelectedWidget()).thenReturn(null);
		plugin.onMenuOptionClicked(new MenuOptionClicked(dropOre));

		// Inventory loses Iron ore
		ItemContainer inv4 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv4);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv4));

		// Verified: Dropping Iron ore DOES deduct profit! 350 - 200 = 150 gp!
		Assert.assertEquals(150L, plugin.getSession().getTotalProfit());
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(ironOreId));
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(coalId).getQuantity());

		// 3. Check bag: container 516 arrives with 1 Coal
		ItemContainer bagContainer = mockContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG, coalId, 1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(bagContainer);
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, bagContainer));

		// Profit remains 150 gp
		Assert.assertEquals(150L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void lootingBag_deathInWilderness_clearsBagCacheAndReinitializes()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
		when(client.getTickCount()).thenReturn(800);

		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);

		int bagId = ItemID.LOOTING_BAG_OPEN;
		int scimitarId = ItemID.RUNE_SCIMITAR;
		stubTrackableItem(bagId, "Looting bag (open)", 0L);
		stubTrackableItem(scimitarId, "Rune scimitar", 15_000L);

		// Start with bag and 1 Rune scimitar in bag
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1);
		ItemContainer bag1 = mockContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG, scimitarId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		when(client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG)).thenReturn(bag1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));
		plugin.onItemContainerChanged(new ItemContainerChanged(net.runelite.api.gameval.InventoryID.LOOTING_BAG, bag1));

		Assert.assertTrue(plugin.lootingBagInitialized);
		Assert.assertEquals(1, plugin.getSnapshotService().getLootingBagContents().get(scimitarId).intValue());

		// Player dies in Wilderness
		ActorDeath death = new ActorDeath(player);
		plugin.onActorDeath(death);

		// Bag cache and initialization flag must be cleared on death
		Assert.assertFalse(plugin.lootingBagInitialized);
		Assert.assertTrue(plugin.getSnapshotService().getLootingBagContents().isEmpty());
	}

	@Test
	public void gemBag_directLootPickupIntoOpenGemBag_creditsProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		net.runelite.api.WorldView worldView = mock(net.runelite.api.WorldView.class);
		when(worldView.getBaseX()).thenReturn(1000);
		when(worldView.getBaseY()).thenReturn(2000);
		when(worldView.getPlane()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		Player player = mock(Player.class);
		WorldPoint playerLocation = new WorldPoint(1010, 2020, 0);
		when(player.getWorldLocation()).thenReturn(playerLocation);
		when(client.getLocalPlayer()).thenReturn(player);

		int bagId = ItemID.GEM_BAG_OPEN;
		int rubyId = ItemID.UNCUT_RUBY;
		stubTrackableItem(bagId, "Open gem bag", 0L);
		stubTrackableItem(rubyId, "Uncut ruby", 1_200L);

		// Start: Player has open gem bag in inventory
		ItemContainer inv = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));
		Assert.assertTrue(plugin.snapshotInitialized);
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// Player clicks "Take" Uncut ruby on ground
		net.runelite.api.MenuEntry takeEntry = mock(net.runelite.api.MenuEntry.class);
		when(takeEntry.getOption()).thenReturn("Take");
		when(takeEntry.getIdentifier()).thenReturn(rubyId);
		when(takeEntry.getParam0()).thenReturn(10);
		when(takeEntry.getParam1()).thenReturn(20);
		plugin.onMenuOptionClicked(new MenuOptionClicked(takeEntry));

		// Ground item despawns as it goes directly into the open gem bag
		TileItem tileItem = mock(TileItem.class);
		when(tileItem.getId()).thenReturn(rubyId);
		when(tileItem.getQuantity()).thenReturn(1);
		Tile tile = mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(playerLocation);

		plugin.onItemDespawned(new ItemDespawned(tile, tileItem));

		// Tick fires without inventory change
		plugin.onGameTick(new GameTick());

		// Profit is credited and gold drop displayed
		Assert.assertEquals(1_200L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(1_200L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertTrue(plugin.getSession().getTrackedItems().containsKey(rubyId));
		verify(goldDropOverlay, times(1)).addDrop(anyString(), org.mockito.ArgumentMatchers.eq(rubyId), anyInt());
	}

	@Test
	public void drainPendingContainerPickups_partialAndFullInventoryGains_reconcilesCorrectly()
	{
		int sapphireId = ItemID.UNCUT_SAPPHIRE;
		int emeraldId = ItemID.UNCUT_EMERALD;
		stubTrackableItem(sapphireId, "Uncut sapphire", 500L);
		stubTrackableItem(emeraldId, "Uncut emerald", 800L);

		// Case 1: Null or empty list handles gracefully
		plugin.drainPendingContainerPickups(null);
		plugin.drainPendingContainerPickups(new ArrayList<>());

		// Case 2: Sapphire had 3 picked up, but 1 was counted in inventory
		// Only 2 should be credited via drainPendingContainerPickups
		plugin.invItemsGainedThisTick.put(sapphireId, 1);
		// Emerald had 2 picked up, and 2 were counted in inventory
		// 0 should be credited via container
		plugin.invItemsGainedThisTick.put(emeraldId, 2);

		List<CoinFlowPlugin.LootPickup> pickups = new ArrayList<>();
		pickups.add(new CoinFlowPlugin.LootPickup(sapphireId, 3));
		pickups.add(new CoinFlowPlugin.LootPickup(emeraldId, 2));

		plugin.drainPendingContainerPickups(pickups);

		// Container gains: 2 sapphires @ 500 = 1000 gp
		Assert.assertEquals(1000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(2, plugin.getSession().getTrackedItems().get(sapphireId).getQuantity());
		Assert.assertFalse(plugin.getSession().getTrackedItems().containsKey(emeraldId));

		// Pending pickups list should be cleared
		Assert.assertTrue(pickups.isEmpty());
		// invItemsGainedThisTick for both should now be 0
		Assert.assertEquals(0, plugin.invItemsGainedThisTick.get(sapphireId).intValue());
		Assert.assertEquals(0, plugin.invItemsGainedThisTick.get(emeraldId).intValue());
	}

	@Test
	public void gemBag_depositingFromInventoryIntoGemBag_netZeroExpenseAndProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int bagId = ItemID.GEM_BAG;
		int diamondId = ItemID.UNCUT_DIAMOND;
		stubTrackableItem(bagId, "Gem bag", 0L);
		stubTrackableItem(diamondId, "Uncut diamond", 2_000L);

		// Start: Player has gem bag + 3 uncut diamonds
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1, diamondId, 3);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));
		Assert.assertTrue(plugin.snapshotInitialized);

		// Player clicks "Fill" on "Gem bag"
		net.runelite.api.MenuEntry fillEntry = mock(net.runelite.api.MenuEntry.class);
		when(fillEntry.getOption()).thenReturn("Fill");
		when(fillEntry.getTarget()).thenReturn("<col=ff9040>Gem bag</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(fillEntry));

		// Inventory now only contains the Gem bag (diamonds moved into bag)
		ItemContainer inv2 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Filling bag must NOT trigger supply expenses or dropped item losses!
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void gemBag_useGemOnGemBag_netZeroExpenseAndProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int bagId = ItemID.GEM_BAG;
		int sapphireId = ItemID.UNCUT_SAPPHIRE;
		stubTrackableItem(bagId, "Gem bag", 0L);
		stubTrackableItem(sapphireId, "Uncut sapphire", 250L);

		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1, sapphireId, 5);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Player clicks "Use" Uncut sapphire -> Gem bag
		net.runelite.api.MenuEntry useEntry = mock(net.runelite.api.MenuEntry.class);
		when(useEntry.getOption()).thenReturn("Use");
		when(useEntry.getTarget()).thenReturn("Use Uncut sapphire -> <col=ff9040>Gem bag</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(useEntry));

		// Inventory updates with gems stored
		ItemContainer inv2 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void gemBag_emptyGemBagIntoInventory_netZeroProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int bagId = ItemID.GEM_BAG;
		int emeraldId = ItemID.UNCUT_EMERALD;
		stubTrackableItem(bagId, "Gem bag", 0L);
		stubTrackableItem(emeraldId, "Uncut emerald", 500L);

		// Start with Gem bag in inventory
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Player clicks "Empty" on "Gem bag"
		net.runelite.api.MenuEntry emptyEntry = mock(net.runelite.api.MenuEntry.class);
		when(emptyEntry.getOption()).thenReturn("Empty");
		when(emptyEntry.getTarget()).thenReturn("<col=ff9040>Gem bag</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(emptyEntry));

		// Gems emerge into inventory
		ItemContainer inv2 = mockContainer(InventoryID.INV, bagId, 1, emeraldId, 10);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Emptying bag must NOT count as new profit!
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void colossalPouch_fillAndEmpty_netZeroExpenseAndProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int pouchId = ItemID.RCU_POUCH_COLOSSAL;
		int essenceId = ItemID.BLANKRUNE_HIGH;
		stubTrackableItem(pouchId, "Colossal pouch", 0L);
		stubTrackableItem(essenceId, "Pure essence", 100L);

		// Start: pouch + 40 pure essence in inventory
		ItemContainer inv1 = mockContainer(InventoryID.INV, pouchId, 1, essenceId, 40);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));
		Assert.assertTrue(plugin.snapshotInitialized);

		// Player clicks "Fill" on "Colossal pouch" - essence moves into pouch
		net.runelite.api.MenuEntry fillEntry = mock(net.runelite.api.MenuEntry.class);
		when(fillEntry.getOption()).thenReturn("Fill");
		when(fillEntry.getTarget()).thenReturn("<col=ff9040>Colossal pouch</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(fillEntry));

		ItemContainer inv2 = mockContainer(InventoryID.INV, pouchId, 1, essenceId, 13);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Filling is storage, not supply consumption
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// Player clicks "Empty" on "Colossal pouch" - essence returns to inventory
		net.runelite.api.MenuEntry emptyEntry = mock(net.runelite.api.MenuEntry.class);
		when(emptyEntry.getOption()).thenReturn("Empty");
		when(emptyEntry.getTarget()).thenReturn("<col=ff9040>Colossal pouch</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(emptyEntry));

		ItemContainer inv3 = mockContainer(InventoryID.INV, pouchId, 1, essenceId, 40);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv3);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv3));

		// Emptying must NOT count as profit
		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void colossalPouch_useEssenceOnPouch_netZeroExpenseAndProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int pouchId = ItemID.RCU_POUCH_COLOSSAL;
		int essenceId = ItemID.BLANKRUNE_HIGH;
		stubTrackableItem(pouchId, "Colossal pouch", 0L);
		stubTrackableItem(essenceId, "Pure essence", 100L);

		ItemContainer inv1 = mockContainer(InventoryID.INV, pouchId, 1, essenceId, 20);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Player clicks "Use" Pure essence -> Colossal pouch
		net.runelite.api.MenuEntry useEntry = mock(net.runelite.api.MenuEntry.class);
		when(useEntry.getOption()).thenReturn("Use");
		when(useEntry.getTarget()).thenReturn("Use Pure essence -> <col=ff9040>Colossal pouch</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(useEntry));

		ItemContainer inv2 = mockContainer(InventoryID.INV, pouchId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void gemBag_togglingOpenAndCloseState_producesZeroDiff()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int bagClosed = ItemID.GEM_BAG;
		int bagOpen = ItemID.GEM_BAG_OPEN;
		stubTrackableItem(bagClosed, "Gem bag", 0L);
		stubTrackableItem(bagOpen, "Open gem bag", 0L);

		// Start with closed gem bag
		ItemContainer inv1 = mockContainer(InventoryID.INV, bagClosed, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// Toggle Open
		ItemContainer inv2 = mockContainer(InventoryID.INV, bagOpen, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// Toggle Close
		ItemContainer inv3 = mockContainer(InventoryID.INV, bagClosed, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv3);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv3));

		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void gemBag_miningUncutGemIntoOpenGemBag_viaChatMessage_creditsProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(50);

		int bagId = ItemID.GEM_BAG_OPEN;
		int sapphireId = ItemID.UNCUT_SAPPHIRE;
		stubTrackableItem(bagId, "Open gem bag", 0L);
		stubTrackableItem(sapphireId, "Uncut sapphire", 250L);

		// Start with open gem bag
		ItemContainer inv = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		// Game messages for mining uncut sapphire directly into open gem bag
		ChatMessage msg1 = new ChatMessage(null, net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "You find an uncut sapphire!", "", 0);
		plugin.onChatMessage(msg1);

		ChatMessage msg2 = new ChatMessage(null, net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "You put it straight into your open gem bag.", "", 0);
		plugin.onChatMessage(msg2);

		// Game tick fires
		plugin.onGameTick(new GameTick());

		Assert.assertEquals(250L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(250L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertTrue(plugin.getSession().getTrackedItems().containsKey(sapphireId));
		verify(goldDropOverlay, times(1)).addDrop(anyString(), org.mockito.ArgumentMatchers.eq(sapphireId), anyInt());
	}

	@Test
	public void gemBag_closedGemBag_groundPickupDoesNotGoToPending()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		net.runelite.api.WorldView worldView = mock(net.runelite.api.WorldView.class);
		when(worldView.getBaseX()).thenReturn(1000);
		when(worldView.getBaseY()).thenReturn(2000);
		when(worldView.getPlane()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		Player player = mock(Player.class);
		WorldPoint playerLocation = new WorldPoint(1010, 2020, 0);
		when(player.getWorldLocation()).thenReturn(playerLocation);
		when(client.getLocalPlayer()).thenReturn(player);

		int bagId = ItemID.GEM_BAG; // CLOSED
		int rubyId = ItemID.UNCUT_RUBY;
		stubTrackableItem(bagId, "Gem bag", 0L);
		stubTrackableItem(rubyId, "Uncut ruby", 1_200L);

		// Player has CLOSED gem bag
		ItemContainer inv = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		// Player clicks "Take"
		net.runelite.api.MenuEntry takeEntry = mock(net.runelite.api.MenuEntry.class);
		when(takeEntry.getOption()).thenReturn("Take");
		when(takeEntry.getIdentifier()).thenReturn(rubyId);
		when(takeEntry.getParam0()).thenReturn(10);
		when(takeEntry.getParam1()).thenReturn(20);
		plugin.onMenuOptionClicked(new MenuOptionClicked(takeEntry));

		TileItem tileItem = mock(TileItem.class);
		when(tileItem.getId()).thenReturn(rubyId);
		when(tileItem.getQuantity()).thenReturn(1);
		Tile tile = mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(playerLocation);

		plugin.onItemDespawned(new ItemDespawned(tile, tileItem));
		plugin.onGameTick(new GameTick());

		// Because bag is closed, it did not auto-deposit into bag
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void gemSack_worksForSemiPreciousGems()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		net.runelite.api.WorldView worldView = mock(net.runelite.api.WorldView.class);
		when(worldView.getBaseX()).thenReturn(1000);
		when(worldView.getBaseY()).thenReturn(2000);
		when(worldView.getPlane()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		Player player = mock(Player.class);
		WorldPoint playerLocation = new WorldPoint(1010, 2020, 0);
		when(player.getWorldLocation()).thenReturn(playerLocation);
		when(client.getLocalPlayer()).thenReturn(player);

		int sackId = ItemID.GEM_SACK_OPEN;
		int topazId = ItemID.UNCUT_RED_TOPAZ;
		stubTrackableItem(sackId, "Open gem sack", 0L);
		stubTrackableItem(topazId, "Uncut red topaz", 3_500L);

		ItemContainer inv = mockContainer(InventoryID.INV, sackId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		net.runelite.api.MenuEntry takeEntry = mock(net.runelite.api.MenuEntry.class);
		when(takeEntry.getOption()).thenReturn("Take");
		when(takeEntry.getIdentifier()).thenReturn(topazId);
		when(takeEntry.getParam0()).thenReturn(10);
		when(takeEntry.getParam1()).thenReturn(20);
		plugin.onMenuOptionClicked(new MenuOptionClicked(takeEntry));

		TileItem tileItem = mock(TileItem.class);
		when(tileItem.getId()).thenReturn(topazId);
		when(tileItem.getQuantity()).thenReturn(1);
		Tile tile = mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(playerLocation);

		plugin.onItemDespawned(new ItemDespawned(tile, tileItem));
		plugin.onGameTick(new GameTick());

		Assert.assertEquals(3_500L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(3_500L, plugin.getSession().getTotalProfit());
		Assert.assertTrue(plugin.getSession().getTrackedItems().containsKey(topazId));
	}

	@Test
	public void potionDose_sippingMultipleDoses_prefersHighestDosePrice()
	{
		int pot4Id = ItemID._4DOSEPRAYERRESTORE;
		int pot1Id = ItemID._1DOSEPRAYERRESTORE;
		int pot3Id = ItemID._3DOSEPRAYERRESTORE;
		stubTrackableItem(pot4Id, "Prayer potion(4)", 10_000L); // 2,500/dose
		stubTrackableItem(pot1Id, "Prayer potion(1)", 1_000L);  // distorted 1,000/dose
		stubTrackableItem(pot3Id, "Prayer potion(3)", 7_500L);

		// Baseline: 1x (4) and 1x (1) -> 5 doses total
		ItemContainerChanged baselineEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, pot1Id, 1, pot4Id, 1)
		);
		plugin.onItemContainerChanged(baselineEvent);

		// Inventory becomes 1x (3) -> 3 doses total (2 doses consumed)
		ItemContainerChanged updateEvent = new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, pot3Id, 1)
		);
		plugin.onItemContainerChanged(updateEvent);

		// Expense must derive from 4-dose price (2,500 * 2 = 5,000 gp), not 1-dose price (1,000 * 2 = 2,000 gp)
		Assert.assertEquals(5_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-5_000L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void gemBag_nonGemChatMessage_doesNotTriggerPendingPickup()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(50);

		int bagId = ItemID.GEM_BAG_OPEN;
		stubTrackableItem(bagId, "Open gem bag", 0L);

		ItemContainer inv = mockContainer(InventoryID.INV, bagId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		// Non-gem message mentioning "diamond necklace"
		ChatMessage msg1 = new ChatMessage(null, net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "You steal a diamond necklace!", "", 0);
		plugin.onChatMessage(msg1);

		// Follow-up open gem bag message
		ChatMessage msg2 = new ChatMessage(null, net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "You put it straight into your open gem bag.", "", 0);
		plugin.onChatMessage(msg2);

		plugin.onGameTick(new GameTick());

		// Diamond necklace is not an uncut gem; zero profit credited
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void equippedAmmo_firedInCombat_recordsSupplyExpense()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		int ammoId = ItemID.DRAGON_BOLTS_ENCHANTED_DIAMOND;
		stubTrackableItem(ammoId, "Dragon bolts (e)", 1_200L);

		// Baseline: 0 in inv, 100 in worn
		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(emptyInv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyInv));

		ItemContainer initialEquip = mockContainer(InventoryID.WORN, ammoId, 100);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(initialEquip);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, initialEquip));

		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// 5 bolts fired in combat: WORN updates to 95, INV does not change
		ItemContainer updatedEquip = mockContainer(InventoryID.WORN, ammoId, 95);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(updatedEquip);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, updatedEquip));

		// Tick completes
		plugin.onGameTick(new GameTick());

		// Expense: 5 * 1200 = 6000 gp
		Assert.assertEquals(6_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-6_000L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void equippedAmmo_swappedWithInventoryAmmo_recordsNoExpenseOrProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		int rubyBolts = ItemID.DRAGON_BOLTS_ENCHANTED_RUBY;
		int diamondBolts = ItemID.DRAGON_BOLTS_ENCHANTED_DIAMOND;
		stubTrackableItem(rubyBolts, "Dragon bolts (e)", 1_500L);
		stubTrackableItem(diamondBolts, "Dragon bolts (e)", 1_200L);

		// Baseline: INV has 100 diamond bolts, WORN has 100 ruby bolts
		ItemContainer baselineInv = mockContainer(InventoryID.INV, diamondBolts, 100);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(baselineInv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, baselineInv));

		ItemContainer baselineEquip = mockContainer(InventoryID.WORN, rubyBolts, 100);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(baselineEquip);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, baselineEquip));

		// Swap ammo: WORN updates to 100 diamond bolts (ruby bolts unequipped)
		ItemContainer swappedEquip = mockContainer(InventoryID.WORN, diamondBolts, 100);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(swappedEquip);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, swappedEquip));

		// INV updates to 100 ruby bolts (diamond bolts equipped)
		ItemContainer swappedInv = mockContainer(InventoryID.INV, rubyBolts, 100);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(swappedInv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, swappedInv));

		plugin.onGameTick(new GameTick());

		// Financial neutrality: 0 profit, 0 expenses
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void thrownWeapons_thrownFromWeaponSlot_recordsSupplyExpense()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		int dartId = ItemID.DRAGON_DART;
		stubTrackableItem(dartId, "Dragon dart", 1_400L);

		// Baseline: 0 in inv, 50 in worn
		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(emptyInv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyInv));

		ItemContainer initialEquip = mockContainer(InventoryID.WORN, dartId, 50);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(initialEquip);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, initialEquip));

		// 10 darts thrown in combat: WORN updates to 40
		ItemContainer updatedEquip = mockContainer(InventoryID.WORN, dartId, 40);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(updatedEquip);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, updatedEquip));

		plugin.onGameTick(new GameTick());

		// Expense: 10 * 1400 = 14,000 gp
		Assert.assertEquals(14_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-14_000L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void dizanasQuiver_ammoFired_recordsSupplyExpense()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		int quiverId = ItemID.DIZANAS_QUIVER_CHARGED;
		int arrowId = ItemID.DRAGON_ARROW;
		stubTrackableItem(quiverId, "Dizana's quiver", 0L);
		stubTrackableItem(arrowId, "Dragon arrow", 1_000L);

		// Baseline: Player wears quiver, quiver container 879 has 200 Dragon arrows
		ItemContainer wornContainer = mockContainer(InventoryID.WORN, quiverId, 1);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(wornContainer);

		ItemContainer quiverContainer = mockContainer(InventoryID.DIZANAS_QUIVER_AMMO, arrowId, 200);
		when(client.getItemContainer(InventoryID.DIZANAS_QUIVER_AMMO)).thenReturn(quiverContainer);

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(emptyInv);

		// Baseline inventory snapshot merges quiver ammo
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyInv));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.DIZANAS_QUIVER_AMMO, quiverContainer));

		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// 20 arrows fired from quiver: container 879 drops to 180
		ItemContainer updatedQuiver = mockContainer(InventoryID.DIZANAS_QUIVER_AMMO, arrowId, 180);
		when(client.getItemContainer(InventoryID.DIZANAS_QUIVER_AMMO)).thenReturn(updatedQuiver);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.DIZANAS_QUIVER_AMMO, updatedQuiver));

		// Expense: 20 * 1000 = 20,000 gp
		Assert.assertEquals(20_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-20_000L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void dizanasQuiver_loadAmmoFromInventory_recordsZeroProfitAndExpense()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		int quiverId = ItemID.DIZANAS_QUIVER_CHARGED;
		int arrowId = ItemID.DRAGON_ARROW;
		stubTrackableItem(quiverId, "Dizana's quiver", 0L);
		stubTrackableItem(arrowId, "Dragon arrow", 1_000L);

		// Baseline: WORN has quiver. INV has 100 Dragon arrows. Quiver has 0 arrows.
		ItemContainer worn = mockContainer(InventoryID.WORN, quiverId, 1);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(worn);

		ItemContainer inv = mockContainer(InventoryID.INV, arrowId, 100);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		// Player loads 100 arrows into quiver:
		// INV loses 100 arrows, Quiver container gains 100 arrows
		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(emptyInv);

		ItemContainer quiverAmmo = mockContainer(InventoryID.DIZANAS_QUIVER_AMMO, arrowId, 100);
		when(client.getItemContainer(InventoryID.DIZANAS_QUIVER_AMMO)).thenReturn(quiverAmmo);

		// When quiver container updates, plugin caches quiver ammo and processes changes
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.DIZANAS_QUIVER_AMMO, quiverAmmo));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyInv));

		// Snapshot merges INV + Quiver: 0 + 100 = 100 Dragon arrows. Net diff is 0!
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void masterScrollBook_teleportUsed_recordsSupplyExpense()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		int bookId = ItemID.BOOKOFSCROLLS_CHARGED;
		int nardahScrollId = ItemID.TELEPORTSCROLL_NARDAH;
		stubTrackableItem(bookId, "Master scroll book", 0L);
		stubTrackableItem(nardahScrollId, "Nardah teleport", 1_800L);

		// Mock varbits for Master Scroll Book: Nardah has 5 scrolls
		when(client.getVarbitValue(VarbitID.BOOKOFSCROLLS_NARDAH)).thenReturn(5);

		ItemContainer invWithBook = mockContainer(InventoryID.INV, bookId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(invWithBook);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, invWithBook));

		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// Player teleports using Master Scroll Book: Nardah count drops to 4
		when(client.getVarbitValue(VarbitID.BOOKOFSCROLLS_NARDAH)).thenReturn(4);
		VarbitChanged varbitEvent = new VarbitChanged();
		varbitEvent.setVarbitId(VarbitID.BOOKOFSCROLLS_NARDAH);
		varbitEvent.setValue(4);
		plugin.onVarbitChanged(varbitEvent);

		// Expense: 1 * 1800 = 1,800 gp
		Assert.assertEquals(1_800L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-1_800L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void masterScrollBook_addScrollsFromInventory_recordsZeroProfitAndExpense()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		int bookId = ItemID.BOOKOFSCROLLS_CHARGED;
		int nardahScrollId = ItemID.TELEPORTSCROLL_NARDAH;
		stubTrackableItem(bookId, "Master scroll book", 0L);
		stubTrackableItem(nardahScrollId, "Nardah teleport", 1_800L);

		// Baseline: INV has book + 5 loose Nardah scrolls. Book varbit currently 0.
		when(client.getVarbitValue(VarbitID.BOOKOFSCROLLS_NARDAH)).thenReturn(0);
		ItemContainer initialInv = mockContainer(InventoryID.INV, bookId, 1, nardahScrollId, 5);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(initialInv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, initialInv));

		// Player puts 5 loose scrolls into the book:
		// INV now has only book (loose scrolls = 0). Varbit becomes 5.
		when(client.getVarbitValue(VarbitID.BOOKOFSCROLLS_NARDAH)).thenReturn(5);
		ItemContainer updatedInv = mockContainer(InventoryID.INV, bookId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(updatedInv);

		VarbitChanged varbitEvent = new VarbitChanged();
		varbitEvent.setVarbitId(VarbitID.BOOKOFSCROLLS_NARDAH);
		varbitEvent.setValue(5);
		plugin.onVarbitChanged(varbitEvent);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, updatedInv));

		// Total Nardah scrolls before: 5 (loose) + 0 (book) = 5
		// Total Nardah scrolls after: 0 (loose) + 5 (book) = 5
		// Net diff is 0: 0 profit, 0 expense!
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void equippedAmmo_firedThenDroppedItemPickedUp_creditsFullProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		int ammoId = ItemID.DRAGON_BOLTS_ENCHANTED_DIAMOND;
		stubTrackableItem(ammoId, "Dragon bolts (e)", 1_200L);

		// Baseline: 0 in inv, 100 in worn
		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(emptyInv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyInv));

		ItemContainer initialEquip = mockContainer(InventoryID.WORN, ammoId, 100);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(initialEquip);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, initialEquip));

		// 5 bolts fired in combat: WORN updates to 95, INV does not change
		ItemContainer updatedEquip = mockContainer(InventoryID.WORN, ammoId, 95);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(updatedEquip);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, updatedEquip));

		// Tick completes -> 5 bolts charged as expense (6,000 gp)
		plugin.onGameTick(new GameTick());
		Assert.assertEquals(6_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-6_000L, plugin.getSession().getTotalProfit());
		// recentlyUnequippedItems MUST NOT hold the 5 consumed bolts
		Assert.assertFalse(plugin.recentlyUnequippedItems.containsKey(ammoId));

		// Next tick: Monster drops 10 bolts and player picks them up into INV
		when(client.getTickCount()).thenReturn(101);
		ItemContainer invWithDrop = mockContainer(InventoryID.INV, ammoId, 10);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(invWithDrop);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, invWithDrop));

		// All 10 bolts MUST be credited as loot profit (12,000 gp).
		// Net profit: -6,000 + 12,000 = +6,000 gp
		Assert.assertEquals(6_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(6_000L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void equippedAmmo_unequipInvDispatchedBeforeWorn_doesNotChargeExpense()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		int ammoId = ItemID.DRAGON_BOLTS_ENCHANTED_DIAMOND;
		stubTrackableItem(ammoId, "Dragon bolts (e)", 1_200L);

		// Baseline: 0 in inv, 100 in worn
		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(emptyInv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyInv));

		ItemContainer initialEquip = mockContainer(InventoryID.WORN, ammoId, 100);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(initialEquip);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, initialEquip));

		// Player unequips ammo: INV dispatches first with 100 bolts
		// Client worn container is now empty
		ItemContainer emptyEquip = mockContainer(InventoryID.WORN);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(emptyEquip);

		ItemContainer unequippedInv = mockContainer(InventoryID.INV, ammoId, 100);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(unequippedInv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, unequippedInv));

		// Now WORN dispatches second with 0 bolts (100 removed)
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, emptyEquip));

		// End of tick
		plugin.onGameTick(new GameTick());

		// Financial neutrality: zero profit and zero expenses!
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertFalse(plugin.recentlyUnequippedItems.containsKey(ammoId));
		Assert.assertFalse(plugin.getPendingWornAmmoExpenses().containsKey(ammoId));
	}

	@Test
	public void masterScrollBook_transitionBetweenEmptyAndCharged_recordsZeroProfitAndExpense()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		int emptyBookId = ItemID.BOOKOFSCROLLS_EMPTY;
		int chargedBookId = ItemID.BOOKOFSCROLLS_CHARGED;
		int scrollId = ItemID.TELEPORTSCROLL_NARDAH;
		stubTrackableItem(emptyBookId, "Master scroll book (empty)", 0L);
		stubTrackableItem(chargedBookId, "Master scroll book", 150_000L);
		stubTrackableItem(scrollId, "Nardah teleport", 1_800L);

		// Baseline: INV has empty book + 1 loose scroll
		when(client.getVarbitValue(VarbitID.BOOKOFSCROLLS_NARDAH)).thenReturn(0);
		ItemContainer initialInv = mockContainer(InventoryID.INV, emptyBookId, 1, scrollId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(initialInv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, initialInv));

		// Scroll is put into book: Book transforms from EMPTY to CHARGED in INV!
		when(client.getVarbitValue(VarbitID.BOOKOFSCROLLS_NARDAH)).thenReturn(1);
		ItemContainer chargedInv = mockContainer(InventoryID.INV, chargedBookId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(chargedInv);

		VarbitChanged varbitEvent = new VarbitChanged();
		varbitEvent.setVarbitId(VarbitID.BOOKOFSCROLLS_NARDAH);
		varbitEvent.setValue(1);
		plugin.onVarbitChanged(varbitEvent);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, chargedInv));

		// Must not trigger false profit for the charged book or false expense!
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void dizanasQuiver_dizanasMaxCape_recognizesQuiverAndTracksAmmo()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		int maxCapeQuiver = ItemID.SKILLCAPE_MAX_DIZANAS;
		int arrowId = ItemID.DRAGON_ARROW;
		stubTrackableItem(maxCapeQuiver, "Dizana's max cape", 0L);
		stubTrackableItem(arrowId, "Dragon arrow", 1_000L);

		Assert.assertTrue(plugin.isDizanasQuiver(maxCapeQuiver));

		// Player wears Dizana's max cape with 100 Dragon arrows in quiver container 879
		ItemContainer worn = mockContainer(InventoryID.WORN, maxCapeQuiver, 1);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(worn);

		ItemContainer quiverAmmo = mockContainer(InventoryID.DIZANAS_QUIVER_AMMO, arrowId, 100);
		when(client.getItemContainer(InventoryID.DIZANAS_QUIVER_AMMO)).thenReturn(quiverAmmo);

		ItemContainer emptyInv = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(emptyInv);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, emptyInv));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.DIZANAS_QUIVER_AMMO, quiverAmmo));

		// 10 arrows fired from quiver container
		ItemContainer updatedQuiver = mockContainer(InventoryID.DIZANAS_QUIVER_AMMO, arrowId, 90);
		when(client.getItemContainer(InventoryID.DIZANAS_QUIVER_AMMO)).thenReturn(updatedQuiver);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.DIZANAS_QUIVER_AMMO, updatedQuiver));

		// 10 * 1000 = 10,000 gp expense recorded
		Assert.assertEquals(10_000L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-10_000L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void openHerbSack_groundPickup_creditsProfitImmediately()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		net.runelite.api.WorldView worldView = mock(net.runelite.api.WorldView.class);
		when(worldView.getBaseX()).thenReturn(1000);
		when(worldView.getBaseY()).thenReturn(2000);
		when(worldView.getPlane()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		Player player = mock(Player.class);
		WorldPoint playerLocation = new WorldPoint(1010, 2020, 0);
		when(player.getWorldLocation()).thenReturn(playerLocation);
		when(client.getLocalPlayer()).thenReturn(player);

		int sackId = ItemID.SLAYER_HERB_SACK_OPEN;
		int herbId = ItemID.UNIDENTIFIED_RANARR;
		stubTrackableItem(sackId, "Herb sack", 0L);
		stubTrackableItem(herbId, "Grimy ranarr weed", 7_500L);

		// Inventory has open herb sack
		ItemContainer inv = mockContainer(InventoryID.INV, sackId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));
		Assert.assertTrue(plugin.snapshotInitialized);

		// Player takes herb from ground
		net.runelite.api.MenuEntry takeEntry = mock(net.runelite.api.MenuEntry.class);
		when(takeEntry.getOption()).thenReturn("Take");
		when(takeEntry.getIdentifier()).thenReturn(herbId);
		when(takeEntry.getParam0()).thenReturn(10);
		when(takeEntry.getParam1()).thenReturn(20);
		plugin.onMenuOptionClicked(new MenuOptionClicked(takeEntry));

		// Herb despawns as it goes directly into open herb sack
		TileItem tileItem = mock(TileItem.class);
		when(tileItem.getId()).thenReturn(herbId);
		when(tileItem.getQuantity()).thenReturn(1);
		Tile tile = mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(playerLocation);
		plugin.onItemDespawned(new ItemDespawned(tile, tileItem));

		// Tick fires
		plugin.onGameTick(new GameTick());

		Assert.assertEquals(7_500L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertTrue(plugin.getSession().getTrackedItems().containsKey(herbId));
	}

	@Test
	public void openHerbSack_herbiboarChatMessage_creditsProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int sackId = ItemID.SLAYER_HERB_SACK_OPEN;
		int herbId = ItemID.UNIDENTIFIED_RANARR;
		stubTrackableItem(sackId, "Herb sack", 0L);
		stubTrackableItem(herbId, "Grimy ranarr weed", 7_500L);

		ItemContainer inv = mockContainer(InventoryID.INV, sackId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		ChatMessage event = new ChatMessage(null, net.runelite.api.ChatMessageType.SPAM, "", "You put the grimy ranarr weed into your herb sack.", "", 0);
		plugin.onChatMessage(event);
		plugin.onGameTick(new GameTick());

		Assert.assertEquals(7_500L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void openFishBarrel_chatMessage_creditsProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int barrelId = ItemID.FISH_BARREL_OPEN;
		int fishId = ItemID.RAW_SALMON;
		stubTrackableItem(barrelId, "Fish barrel (open)", 0L);
		stubTrackableItem(fishId, "Raw salmon", 300L);

		ItemContainer inv = mockContainer(InventoryID.INV, barrelId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		ChatMessage event = new ChatMessage(null, net.runelite.api.ChatMessageType.SPAM, "", "You catch a raw salmon.", "", 0);
		plugin.onChatMessage(event);
		plugin.onGameTick(new GameTick());

		Assert.assertEquals(300L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void openSeedBox_groundPickupAndMasterFarmer_creditsProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(100);

		net.runelite.api.WorldView worldView = mock(net.runelite.api.WorldView.class);
		when(worldView.getBaseX()).thenReturn(1000);
		when(worldView.getBaseY()).thenReturn(2000);
		when(worldView.getPlane()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		Player player = mock(Player.class);
		WorldPoint playerLocation = new WorldPoint(1010, 2020, 0);
		when(player.getWorldLocation()).thenReturn(playerLocation);
		when(client.getLocalPlayer()).thenReturn(player);

		int boxId = ItemID.SEED_BOX_OPEN;
		int seedId = ItemID.RANARR_SEED;
		stubTrackableItem(boxId, "Open seed box", 0L);
		stubTrackableItem(seedId, "Ranarr seed", 30_000L);

		ItemContainer inv = mockContainer(InventoryID.INV, boxId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		// Ground pickup into seed box
		net.runelite.api.MenuEntry takeEntry = mock(net.runelite.api.MenuEntry.class);
		when(takeEntry.getOption()).thenReturn("Take");
		when(takeEntry.getIdentifier()).thenReturn(seedId);
		when(takeEntry.getParam0()).thenReturn(10);
		when(takeEntry.getParam1()).thenReturn(20);
		plugin.onMenuOptionClicked(new MenuOptionClicked(takeEntry));

		TileItem tileItem = mock(TileItem.class);
		when(tileItem.getId()).thenReturn(seedId);
		when(tileItem.getQuantity()).thenReturn(1);
		Tile tile = mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(playerLocation);
		plugin.onItemDespawned(new ItemDespawned(tile, tileItem));
		plugin.onGameTick(new GameTick());

		Assert.assertEquals(30_000L, plugin.getSession().getTotalProfit());

		// Master farmer pickpocket into seed box
		ChatMessage event = new ChatMessage(null, net.runelite.api.ChatMessageType.SPAM, "", "You steal 2 x Ranarr seed from the Master Farmer (sent to seed box).", "", 0);
		plugin.onChatMessage(event);
		plugin.onGameTick(new GameTick());

		Assert.assertEquals(90_000L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void pvpLootKey_openingChest_creditsKeyItemsAsProfit_andInterfaceSuppressed()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int scimitarId = ItemID.RUNE_SCIMITAR;
		int coinsId = ItemID.COINS;
		stubTrackableItem(scimitarId, "Rune scimitar", 15_000L);

		ItemContainer inv = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		// Chest container 558 has loot
		ItemContainer chestLoot = mockContainer(InventoryID.DEADMAN_LOOT_INV0, scimitarId, 1, coinsId, 50_000);
		when(client.getItemContainer(InventoryID.DEADMAN_LOOT_INV0)).thenReturn(chestLoot);

		// Chest widget opens
		WidgetLoaded widgetLoaded = new WidgetLoaded();
		widgetLoaded.setGroupId(InterfaceID.WILDY_LOOT_CHEST);
		plugin.onWidgetLoaded(widgetLoaded);

		// Verified: Profit is credited from key container
		Assert.assertEquals(65_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// Verified: Interface is tracked as suppressed
		Assert.assertTrue(plugin.isTrackingSuppressed());
	}

	@Test
	public void ashSanctifier_chargingAndUsage_reconcilesSupplyCost()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int sanctifierId = ItemID.ASH_SANCTIFIER;
		int deathRuneId = ItemID.DEATHRUNE;
		stubTrackableItem(sanctifierId, "Ash sanctifier", 0L);
		stubTrackableItem(deathRuneId, "Death rune", 200L);

		// Baseline: 100 loose Death runes, sanctifier has 0 charges
		when(client.getVarbitValue(VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY)).thenReturn(0);
		ItemContainer inv1 = mockContainer(InventoryID.INV, sanctifierId, 1, deathRuneId, 100);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Step 1: Charge sanctifier with 50 Death runes
		// Varbit increases to 50, loose Death runes decrease to 50
		when(client.getVarbitValue(VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY)).thenReturn(50);
		ItemContainer inv2 = mockContainer(InventoryID.INV, sanctifierId, 1, deathRuneId, 50);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);

		VarbitChanged chargeEvent = new VarbitChanged();
		chargeEvent.setVarbitId(VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY);
		chargeEvent.setValue(50);
		plugin.onVarbitChanged(chargeEvent);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Charging must be net-zero!
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// Step 2: Use in combat (sanctifier scatters ash, consuming 1 death rune charge)
		when(client.getVarbitValue(VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY)).thenReturn(49);
		VarbitChanged useEvent = new VarbitChanged();
		useEvent.setVarbitId(VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY);
		useEvent.setValue(49);
		plugin.onVarbitChanged(useEvent);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Consuming 1 charge records 200 gp supply expense
		Assert.assertEquals(200L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-200L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void containers_toggleOpenCloseAndEmpty_netZeroDiff()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		int closedSack = ItemID.SLAYER_HERB_SACK;
		int openSack = ItemID.SLAYER_HERB_SACK_OPEN;
		int closedBarrel = ItemID.FISH_BARREL_CLOSED;
		int openBarrel = ItemID.FISH_BARREL_OPEN;
		int closedBox = ItemID.SEED_BOX;
		int openBox = ItemID.SEED_BOX_OPEN;

		stubTrackableItem(closedSack, "Herb sack", 0L);
		stubTrackableItem(openSack, "Open herb sack", 0L);
		stubTrackableItem(closedBarrel, "Fish barrel", 0L);
		stubTrackableItem(openBarrel, "Open fish barrel", 0L);
		stubTrackableItem(closedBox, "Seed box", 0L);
		stubTrackableItem(openBox, "Open seed box", 0L);

		// Start with closed containers
		ItemContainer inv1 = mockContainer(InventoryID.INV, closedSack, 1, closedBarrel, 1, closedBox, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));
		Assert.assertTrue(plugin.snapshotInitialized);

		// Toggle all containers to open state
		ItemContainer inv2 = mockContainer(InventoryID.INV, openSack, 1, openBarrel, 1, openBox, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Verified: Normalization ensures 0 profit and 0 expense
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// Clicking "Empty" on open herb sack triggers rebaseline
		net.runelite.api.MenuEntry emptyEntry = mock(net.runelite.api.MenuEntry.class);
		when(emptyEntry.getOption()).thenReturn("Empty");
		when(emptyEntry.getTarget()).thenReturn("<col=ff9040>Open herb sack</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(emptyEntry));
		Assert.assertTrue(plugin.isNeedsRebaseline());
	}

	@Test
	public void pvpLootKey_reopeningChest_doesNotDuplicateProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int scimitarId = ItemID.RUNE_SCIMITAR;
		int coinsId = ItemID.COINS;
		stubTrackableItem(scimitarId, "Rune scimitar", 15_000L);

		ItemContainer inv = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		// First key in container 558
		ItemContainer chestLoot = mockContainer(InventoryID.DEADMAN_LOOT_INV0, scimitarId, 1, coinsId, 50_000);
		when(client.getItemContainer(InventoryID.DEADMAN_LOOT_INV0)).thenReturn(chestLoot);

		WidgetLoaded widgetLoaded = new WidgetLoaded();
		widgetLoaded.setGroupId(InterfaceID.WILDY_LOOT_CHEST);
		plugin.onWidgetLoaded(widgetLoaded);

		// First opening credits 65,000 gp
		Assert.assertEquals(65_000L, plugin.getSession().getTotalProfit());

		// Re-opening chest (WidgetLoaded fires again with same items)
		plugin.onWidgetLoaded(widgetLoaded);

		// Must NOT duplicate profit!
		Assert.assertEquals(65_000L, plugin.getSession().getTotalProfit());

		// Container changed event fires for container 558 with same items
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.DEADMAN_LOOT_INV0, chestLoot));
		Assert.assertEquals(65_000L, plugin.getSession().getTotalProfit());

		// Second key in container 559 has 10,000 coins
		ItemContainer secondKeyLoot = mockContainer(InventoryID.DEADMAN_LOOT_INV1, coinsId, 10_000);
		when(client.getItemContainer(InventoryID.DEADMAN_LOOT_INV1)).thenReturn(secondKeyLoot);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.DEADMAN_LOOT_INV1, secondKeyLoot));

		// Second key loot added: 65,000 + 10,000 = 75,000 gp
		Assert.assertEquals(75_000L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void fishBarrel_multiCatchAndRadasBlessing_creditsFullQuantity()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int barrelId = ItemID.FISH_BARREL_OPEN;
		int fishId = ItemID.RAW_SALMON;
		int karambwanjiId = ItemID.TBWT_RAW_KARAMBWANJI;
		stubTrackableItem(barrelId, "Fish barrel (open)", 0L);
		stubTrackableItem(fishId, "Raw salmon", 300L);
		stubTrackableItem(karambwanjiId, "Raw karambwanji", 10L);

		ItemContainer inv = mockContainer(InventoryID.INV, barrelId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		// Rada's blessing catches 2 raw salmon
		ChatMessage doubleCatch = new ChatMessage(null, net.runelite.api.ChatMessageType.SPAM, "", "You catch 2 raw salmon.", "", 0);
		plugin.onChatMessage(doubleCatch);
		plugin.onGameTick(new GameTick());

		// 2 * 300 = 600 gp
		Assert.assertEquals(600L, plugin.getSession().getTotalProfit());

		// Karambwanji stack of 25
		ChatMessage karambwanjiCatch = new ChatMessage(null, net.runelite.api.ChatMessageType.SPAM, "", "You catch 25 Karambwanji.", "", 0);
		plugin.onChatMessage(karambwanjiCatch);
		plugin.onGameTick(new GameTick());

		// 600 + (25 * 10) = 850 gp
		Assert.assertEquals(850L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void containers_partialFillAndInventorySpillover_creditsBothAccurately()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int sackId = ItemID.SLAYER_HERB_SACK_OPEN;
		int herbId = ItemID.UNIDENTIFIED_RANARR;
		stubTrackableItem(sackId, "Herb sack", 0L);
		stubTrackableItem(herbId, "Grimy ranarr weed", 7_500L);

		// Baseline
		ItemContainer inv1 = mockContainer(InventoryID.INV, sackId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Player harvests 2 ranarrs from Herbiboar
		// Sack had room for only 1, so 1 went to sack and 1 went to inventory
		ChatMessage herbMsg1 = new ChatMessage(null, net.runelite.api.ChatMessageType.SPAM, "", "You put the grimy ranarr weed into your herb sack.", "", 0);
		ChatMessage herbMsg2 = new ChatMessage(null, net.runelite.api.ChatMessageType.SPAM, "", "You put the grimy ranarr weed into your herb sack.", "", 0);
		plugin.onChatMessage(herbMsg1);
		plugin.onChatMessage(herbMsg2);

		// 1 ranarr spilled over to inventory
		ItemContainer inv2 = mockContainer(InventoryID.INV, sackId, 1, herbId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// End of tick
		plugin.onGameTick(new GameTick());

		// Total profit must be exactly 2 * 7,500 = 15,000 gp (1 from sack + 1 from inventory)
		Assert.assertEquals(15_000L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void forestryBasket_woodcuttingChoppedLogs_creditsProfitImmediately()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int basketId = ItemID.FORESTRY_BASKET_OPEN;
		int logId = ItemID.OAK_LOGS;
		stubTrackableItem(basketId, "Forestry basket (open)", 0L);
		stubTrackableItem(logId, "Oak logs", 50L);

		ItemContainer inv = mockContainer(InventoryID.INV, basketId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		ChatMessage event = new ChatMessage(null, net.runelite.api.ChatMessageType.SPAM, "", "You get some oak logs.", "", 0);
		plugin.onChatMessage(event);
		plugin.onGameTick(new GameTick());

		Assert.assertEquals(50L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(logId).getQuantity());
	}

	@Test
	public void forestryBasket_equippedInCapeSlot_creditsProfitImmediately()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int basketId = ItemID.FORESTRY_BASKET_OPEN;
		int magicLogId = ItemID.MAGIC_LOGS;
		stubTrackableItem(basketId, "Forestry basket (open)", 0L);
		stubTrackableItem(magicLogId, "Magic logs", 1_100L);

		// Basket is worn in equipment cape slot
		ItemContainer worn = mockContainer(InventoryID.WORN, basketId, 1);
		ItemContainer inv = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(worn);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, worn));

		ChatMessage event = new ChatMessage(null, net.runelite.api.ChatMessageType.SPAM, "", "You get a magic log.", "", 0);
		plugin.onChatMessage(event);
		plugin.onGameTick(new GameTick());

		Assert.assertEquals(1_100L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void logBasket_woodcuttingDoubleProc_creditsBothLogs()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int basketId = ItemID.LOG_BASKET_OPEN;
		int yewId = ItemID.YEW_LOGS;
		stubTrackableItem(basketId, "Log basket (open)", 0L);
		stubTrackableItem(yewId, "Yew logs", 250L);

		ItemContainer inv = mockContainer(InventoryID.INV, basketId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		// Double proc from woodcutting perk / cape
		ChatMessage doubleLog = new ChatMessage(null, net.runelite.api.ChatMessageType.SPAM, "", "You get 2 yew logs.", "", 0);
		plugin.onChatMessage(doubleLog);
		plugin.onGameTick(new GameTick());

		Assert.assertEquals(500L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(2, plugin.getSession().getTrackedItems().get(yewId).getQuantity());
	}

	@Test
	public void forestryBasket_basketFullOverflowToInventory_creditsOnceWithoutDuplicate()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int basketId = ItemID.FORESTRY_BASKET_OPEN;
		int yewId = ItemID.YEW_LOGS;
		stubTrackableItem(basketId, "Forestry basket (open)", 0L);
		stubTrackableItem(yewId, "Yew logs", 250L);

		// Baseline: basket only
		ItemContainer inv1 = mockContainer(InventoryID.INV, basketId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Chat message says player got a yew log
		ChatMessage msg = new ChatMessage(null, net.runelite.api.ChatMessageType.SPAM, "", "You get some yew logs.", "", 0);
		plugin.onChatMessage(msg);

		// Basket was full, so the yew log spilled over into inventory
		ItemContainer inv2 = mockContainer(InventoryID.INV, basketId, 1, yewId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		plugin.onGameTick(new GameTick());

		// Profit must be exactly 250 gp (credited once, not duplicated between basket and inventory)
		Assert.assertEquals(250L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(1, plugin.getSession().getTrackedItems().get(yewId).getQuantity());
	}

	@Test
	public void forestryBasket_groundPickup_creditsProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenReturn(50);

		net.runelite.api.WorldView worldView = mock(net.runelite.api.WorldView.class);
		when(worldView.getBaseX()).thenReturn(1000);
		when(worldView.getBaseY()).thenReturn(2000);
		when(worldView.getPlane()).thenReturn(0);
		when(client.getTopLevelWorldView()).thenReturn(worldView);

		Player player = mock(Player.class);
		WorldPoint playerLocation = new WorldPoint(1010, 2020, 0);
		when(player.getWorldLocation()).thenReturn(playerLocation);
		when(client.getLocalPlayer()).thenReturn(player);

		int basketId = ItemID.FORESTRY_BASKET_OPEN;
		int teakId = ItemID.TEAK_LOGS;
		stubTrackableItem(basketId, "Forestry basket (open)", 0L);
		stubTrackableItem(teakId, "Teak logs", 120L);

		ItemContainer inv = mockContainer(InventoryID.INV, basketId, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));

		// Ground pickup into open forestry basket
		net.runelite.api.MenuEntry takeEntry = mock(net.runelite.api.MenuEntry.class);
		when(takeEntry.getOption()).thenReturn("Take");
		when(takeEntry.getIdentifier()).thenReturn(teakId);
		when(takeEntry.getParam0()).thenReturn(10);
		when(takeEntry.getParam1()).thenReturn(20);
		plugin.onMenuOptionClicked(new MenuOptionClicked(takeEntry));

		TileItem tileItem = mock(TileItem.class);
		when(tileItem.getId()).thenReturn(teakId);
		when(tileItem.getQuantity()).thenReturn(1);

		Tile tile = mock(Tile.class);
		when(tile.getWorldLocation()).thenReturn(playerLocation);

		plugin.onItemDespawned(new ItemDespawned(tile, tileItem));
		plugin.onGameTick(new GameTick());

		Assert.assertEquals(120L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void forestryBasket_togglingOpenClosed_zeroProfitOrLoss()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int closedBasket = ItemID.FORESTRY_BASKET_CLOSED;
		int openBasket = ItemID.FORESTRY_BASKET_OPEN;
		int closedLogBasket = ItemID.LOG_BASKET_CLOSED;
		int openLogBasket = ItemID.LOG_BASKET_OPEN;

		stubTrackableItem(closedBasket, "Forestry basket", 0L);
		stubTrackableItem(openBasket, "Open forestry basket", 0L);
		stubTrackableItem(closedLogBasket, "Log basket", 0L);
		stubTrackableItem(openLogBasket, "Open log basket", 0L);

		ItemContainer inv1 = mockContainer(InventoryID.INV, closedBasket, 1, closedLogBasket, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		ItemContainer inv2 = mockContainer(InventoryID.INV, openBasket, 1, openLogBasket, 1);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// Clicking "Empty basket" on open forestry basket triggers rebaseline
		net.runelite.api.MenuEntry emptyEntry = mock(net.runelite.api.MenuEntry.class);
		when(emptyEntry.getOption()).thenReturn("Empty basket");
		when(emptyEntry.getTarget()).thenReturn("<col=ff9040>Open forestry basket</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(emptyEntry));
		Assert.assertTrue(plugin.isNeedsRebaseline());
	}

	@Test
	public void forestryBasket_suppressesWhenForestryKitOpen()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Assert.assertFalse(plugin.interfaceTracker.isTrackingSuppressed());

		WidgetLoaded widgetLoaded = new WidgetLoaded();
		widgetLoaded.setGroupId(InterfaceID.FORESTRY_KIT_MAIN);
		plugin.onWidgetLoaded(widgetLoaded);

		Assert.assertTrue(plugin.interfaceTracker.isTrackingSuppressed());
	}

	@Test
	public void woodcutting_allLogTypesAndBark_parsedAccurately()
	{
		Assert.assertEquals(ItemID.LOGS, CoinFlowPlugin.findLogIdInMessage("You get some logs."));
		Assert.assertEquals(ItemID.LOGS, CoinFlowPlugin.findLogIdInMessage("You get a log."));
		Assert.assertEquals(ItemID.OAK_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some oak logs."));
		Assert.assertEquals(ItemID.OAK_LOGS, CoinFlowPlugin.findLogIdInMessage("You get an oak log."));
		Assert.assertEquals(ItemID.WILLOW_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some willow logs."));
		Assert.assertEquals(ItemID.TEAK_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some teak logs."));
		Assert.assertEquals(ItemID.JUNIPER_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some juniper logs."));
		Assert.assertEquals(ItemID.MAPLE_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some maple logs."));
		Assert.assertEquals(ItemID.MAHOGANY_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some mahogany logs."));
		Assert.assertEquals(ItemID.YEW_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some yew logs."));
		Assert.assertEquals(ItemID.MAGIC_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some magic logs."));
		Assert.assertEquals(ItemID.REDWOOD_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some redwood logs."));
		Assert.assertEquals(ItemID.ARCTIC_PINE_LOG, CoinFlowPlugin.findLogIdInMessage("You get some arctic pine logs."));
		Assert.assertEquals(ItemID.BLISTERWOOD_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some blisterwood logs."));
		Assert.assertEquals(ItemID.ACHEY_TREE_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some achey tree logs."));
		Assert.assertEquals(ItemID.HOLLOW_BARK, CoinFlowPlugin.findLogIdInMessage("You get some bark."));
		Assert.assertEquals(ItemID.CAMPHOR_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some camphor logs."));
		Assert.assertEquals(ItemID.IRONWOOD_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some ironwood logs."));
		Assert.assertEquals(ItemID.ROSEWOOD_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some rosewood logs."));
		Assert.assertEquals(ItemID.JATOBA_LOGS, CoinFlowPlugin.findLogIdInMessage("You get some jatoba logs."));

		Assert.assertEquals(1, CoinFlowPlugin.parseLogQtyInMessage("You get some oak logs."));
		Assert.assertEquals(2, CoinFlowPlugin.parseLogQtyInMessage("You get 2 oak logs."));
		Assert.assertEquals(3, CoinFlowPlugin.parseLogQtyInMessage("You cut 3 willow logs."));
	}

	@Test
	public void toolLeprechaun_notingFreshlyHarvestedHerbs_preservesProfitWithoutChurn()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int unnotedRanarr = 257;
		int notedRanarr = 258;
		stubTrackableItem(unnotedRanarr, "Grimy ranarr weed", 30_000L);
		stubTrackableItem(notedRanarr, "Grimy ranarr weed", 30_000L);
		when(itemManager.canonicalize(notedRanarr)).thenReturn(unnotedRanarr);

		// Baseline: empty inventory
		ItemContainer inv0 = mockContainer(InventoryID.INV);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv0);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv0));

		// Step 1: Harvest 8 unnoted ranarr weed from herb patch
		ItemContainer inv1 = mockContainer(InventoryID.INV, unnotedRanarr, 8);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Verified: 8 * 30,000 = 240,000 gp
		Assert.assertEquals(240_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(8, plugin.getSession().getTrackedItems().get(unnotedRanarr).getQuantity());

		// Step 2: Use herbs on Tool Leprechaun -> 8 unnoted converted to 8 noted
		ItemContainer inv2 = mockContainer(InventoryID.INV, notedRanarr, 8);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv2);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv2));

		// Profit must remain exactly 240,000 gp with 8 tracked herbs, no drop deductions or duplicate gains
		Assert.assertEquals(240_000L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(8, plugin.getSession().getTrackedItems().get(unnotedRanarr).getQuantity());
	}

	@Test
	public void toolLeprechaun_notingBankedHerbs_yieldsZeroFakeProfit()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int unnotedRanarr = 257;
		int notedRanarr = 258;
		stubTrackableItem(unnotedRanarr, "Grimy ranarr weed", 30_000L);
		stubTrackableItem(notedRanarr, "Grimy ranarr weed", 30_000L);
		when(itemManager.canonicalize(notedRanarr)).thenReturn(unnotedRanarr);

		// Baseline: player withdrew 8 unnoted ranarr weed from bank before session started
		ItemContainer inv0 = mockContainer(InventoryID.INV, unnotedRanarr, 8);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv0);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv0));

		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// Player notes the 8 herbs at Tool Leprechaun -> 8 unnoted converted to 8 noted
		ItemContainer inv1 = mockContainer(InventoryID.INV, notedRanarr, 8);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Must yield 0 gp profit
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertNull(plugin.getSession().getTrackedItems().get(unnotedRanarr));
	}

	@Test
	public void phials_unnotingPlanksWithCoinsFee_deductsFeeAndCreatesNoFakeGains()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int coinsId = ItemID.COINS;
		int unnotedTeak = 8780;
		int notedTeak = 8781;
		stubTrackableItem(coinsId, "Coins", 1L);
		stubTrackableItem(unnotedTeak, "Teak plank", 800L);
		stubTrackableItem(notedTeak, "Teak plank", 800L);
		when(itemManager.canonicalize(notedTeak)).thenReturn(unnotedTeak);

		// Baseline: player arrives at Phials with 100 noted teak planks and 10,000 coins
		ItemContainer inv0 = mockContainer(InventoryID.INV, notedTeak, 100, coinsId, 10_000);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv0);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv0));

		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());

		// Player clicks "Use Teak plank -> Phials" (noting-service intent required for fee attribution)
		net.runelite.api.MenuEntry useOnPhials = mock(net.runelite.api.MenuEntry.class);
		when(useOnPhials.getOption()).thenReturn("Use");
		when(useOnPhials.getTarget()).thenReturn("<col=ff9040>Teak plank</col> -> <col=ffff00>Phials</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(useOnPhials));

		// Phials unnotes 27 planks: 27 noted planks leave, 135 coins leave, 27 unnoted planks enter
		ItemContainer inv1 = mockContainer(InventoryID.INV, notedTeak, 73, unnotedTeak, 27, coinsId, 10_000 - 135);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv1));

		// Net profit: -135 gp (expenses 135 gp), zero fake teak plank gains
		Assert.assertEquals(-135L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(135L, plugin.getSession().getTotalExpenses());
		Assert.assertNull(plugin.getSession().getTrackedItems().get(unnotedTeak));
		Assert.assertNotNull(plugin.getSession().getTrackedExpenses().get(coinsId));
		Assert.assertEquals(135, plugin.getSession().getTrackedExpenses().get(coinsId).getQuantity());
	}

	@Test
	public void bank_depositingWornAmmo_doesNotDeductAmmoExpense()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		int broadBoltsId = 11875;
		stubTrackableItem(broadBoltsId, "Broad bolts", 50L);

		// Baseline: player has empty inventory and 1194 broad bolts equipped
		ItemContainer inv0 = mockContainer(InventoryID.INV);
		ItemContainer equip0 = mockContainer(InventoryID.WORN, broadBoltsId, 1194);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv0);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(equip0);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv0));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, equip0));

		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());

		// Player opens Bank (group 12 = BANKMAIN, group 15 = BANKSIDE)
		WidgetLoaded openBank = new WidgetLoaded();
		openBank.setGroupId(InterfaceID.BANKMAIN);
		plugin.onWidgetLoaded(openBank);

		WidgetLoaded openBankSide = new WidgetLoaded();
		openBankSide.setGroupId(InterfaceID.BANKSIDE);
		plugin.onWidgetLoaded(openBankSide);
		Assert.assertTrue(plugin.interfaceTracker.isTrackingSuppressed());

		// Player deposits worn broad bolts into bank: worn container becomes empty
		ItemContainer equip1 = mockContainer(InventoryID.WORN);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(equip1);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, equip1));

		// Tick fires while bank is open
		plugin.onGameTick(new GameTick());

		// Bank closed
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKMAIN, 0, false));
		plugin.onWidgetClosed(new WidgetClosed(InterfaceID.BANKSIDE, 0, false));

		// End of tick after bank closed
		plugin.onGameTick(new GameTick());

		// Verified: Depositing 1,194 broad bolts into the bank must NOT deduct 59.7k gp!
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertNull(plugin.getSession().getTrackedExpenses().get(broadBoltsId));
	}

	@Test
	public void farming_plantingSeedInPatch_deductsSeedExpense()
	{
		int ranarrSeedId = 5295;
		stubTrackableItem(ranarrSeedId, "Ranarr seed", 35000L);

		when(client.getTickCount()).thenReturn(20);
		plugin.snapshotInitialized = true;
		plugin.previousInventorySnapshot = snapshot(ranarrSeedId, 1);

		// Player clicks "Use Ranarr seed -> Herb patch"
		net.runelite.api.MenuEntry entry = mock(net.runelite.api.MenuEntry.class);
		when(entry.getOption()).thenReturn("Use");
		when(entry.getTarget()).thenReturn("<col=ff9040>Ranarr seed</col><col=ffffff> -> <col=ffff>Herb patch</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));

		// 2 ticks later, seed leaves inventory as planting begins
		when(client.getTickCount()).thenReturn(22);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV)
		));

		Assert.assertEquals(35000L, plugin.session.getTotalExpenses());
		Assert.assertEquals(-35000L, plugin.session.getTotalProfit());
		CoinFlowSession.TrackedItem expense = plugin.session.getTrackedExpenses().get(ranarrSeedId);
		Assert.assertNotNull(expense);
		Assert.assertEquals(1, expense.getQuantity());
		Assert.assertEquals(35000L, expense.getPriceEach());
		Assert.assertFalse("Planted seed must NOT be recorded as a dropped owned item",
			plugin.recentlyDroppedOwnedItems.containsKey(ranarrSeedId));
	}

	@Test
	public void farming_treatingPatchWithCompost_deductsCompostExpense()
	{
		int ultracompostId = 21483;
		stubTrackableItem(ultracompostId, "Ultracompost", 1200L);

		when(client.getTickCount()).thenReturn(30);
		plugin.snapshotInitialized = true;
		plugin.previousInventorySnapshot = snapshot(ultracompostId, 1);

		// Player clicks "Treat" or "Use Ultracompost -> Herb patch"
		net.runelite.api.MenuEntry entry = mock(net.runelite.api.MenuEntry.class);
		when(entry.getOption()).thenReturn("Use");
		when(entry.getTarget()).thenReturn("Ultracompost -> Herb patch");
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));

		// Compost consumed
		when(client.getTickCount()).thenReturn(31);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV)
		));

		Assert.assertEquals(1200L, plugin.session.getTotalExpenses());
		Assert.assertEquals(-1200L, plugin.session.getTotalProfit());
		CoinFlowSession.TrackedItem expense = plugin.session.getTrackedExpenses().get(ultracompostId);
		Assert.assertNotNull(expense);
		Assert.assertEquals(1, expense.getQuantity());
	}

	@Test
	public void farming_plantingSeedViaChatAndAnimation_deductsSeedExpense()
	{
		int torstolSeedId = 5304;
		stubTrackableItem(torstolSeedId, "Torstol seed", 15000L);

		when(client.getTickCount()).thenReturn(40);
		plugin.snapshotInitialized = true;
		plugin.previousInventorySnapshot = snapshot(torstolSeedId, 1);

		// Player plays farming seed dibbing animation
		net.runelite.api.Player player = mock(net.runelite.api.Player.class);
		when(player.getAnimation()).thenReturn(AnimationID.FARMING_SEED_DIBBING);
		when(client.getLocalPlayer()).thenReturn(player);
		AnimationChanged animEvent = new AnimationChanged();
		animEvent.setActor(player);
		plugin.onAnimationChanged(animEvent);

		// Chat message fires: "You plant a ranarr seed in the herb patch."
		ChatMessage chatEvent = new ChatMessage(null, net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "You plant a torstol seed in the herb patch.", "", 0);
		plugin.onChatMessage(chatEvent);

		// Seed leaves inventory
		when(client.getTickCount()).thenReturn(41);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV)
		));

		Assert.assertEquals(15000L, plugin.session.getTotalExpenses());
		Assert.assertEquals(-15000L, plugin.session.getTotalProfit());
		CoinFlowSession.TrackedItem expense = plugin.session.getTrackedExpenses().get(torstolSeedId);
		Assert.assertNotNull(expense);
	}

	@Test
	public void hunter_birdHouseSeeding_deductsSeedExpense()
	{
		int barleySeedId = 5305;
		stubTrackableItem(barleySeedId, "Barley seed", 50L);

		when(client.getTickCount()).thenReturn(50);
		plugin.snapshotInitialized = true;
		plugin.previousInventorySnapshot = snapshot(barleySeedId, 10);

		// Player uses seeds on birdhouse
		net.runelite.api.MenuEntry entry = mock(net.runelite.api.MenuEntry.class);
		when(entry.getOption()).thenReturn("Use");
		when(entry.getTarget()).thenReturn("<col=ff9040>Barley seed</col> -> <col=ffff>Bird house</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));

		// 10 seeds consumed
		when(client.getTickCount()).thenReturn(51);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV)
		));

		Assert.assertEquals(500L, plugin.session.getTotalExpenses());
		Assert.assertEquals(-500L, plugin.session.getTotalProfit());
		CoinFlowSession.TrackedItem expense = plugin.session.getTrackedExpenses().get(barleySeedId);
		Assert.assertNotNull(expense);
		Assert.assertEquals(10, expense.getQuantity());
	}

	@Test
	public void farming_plantingSapling_gainsEmptyPlantPot_emptyPlantPotNotCountedAsProfit()
	{
		int magicSaplingId = 5374;
		int plantPotId = 5354;
		stubTrackableItem(magicSaplingId, "Magic sapling", 100000L);
		stubTrackableItem(plantPotId, "Plant pot", 1L);

		when(client.getTickCount()).thenReturn(60);
		plugin.snapshotInitialized = true;
		plugin.previousInventorySnapshot = snapshot(magicSaplingId, 1);

		// Click "Use Magic sapling -> Tree patch"
		net.runelite.api.MenuEntry entry = mock(net.runelite.api.MenuEntry.class);
		when(entry.getOption()).thenReturn("Use");
		when(entry.getTarget()).thenReturn("<col=ff9040>Magic sapling</col><col=ffffff> -> <col=ffff>Tree patch</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));

		// Sapling consumed, empty plant pot returned
		when(client.getTickCount()).thenReturn(62);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, plantPotId, 1)
		));

		// Verified: Sapling deducted as expense (-100,000 gp), and empty plant pot is ignored byproduct (0 profit)
		Assert.assertEquals(100000L, plugin.session.getTotalExpenses());
		Assert.assertEquals(-100000L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getGrossProfit());
		Assert.assertNull(plugin.session.getTrackedItems().get(plantPotId));
	}

	@Test
	public void farming_usingSeedOnPlayerOrBank_doesNotActivateFarmingAction()
	{
		int tick = 70;
		when(client.getTickCount()).thenReturn(tick);

		// Player uses seed on another player (e.g. trading)
		net.runelite.api.MenuEntry tradeEntry = mock(net.runelite.api.MenuEntry.class);
		when(tradeEntry.getOption()).thenReturn("Use");
		when(tradeEntry.getTarget()).thenReturn("Ranarr seed -> PlayerName");
		plugin.onMenuOptionClicked(new MenuOptionClicked(tradeEntry));

		Assert.assertEquals(-100, plugin.lastFarmingActionTick);

		// Player uses seed on bank chest
		net.runelite.api.MenuEntry bankEntry = mock(net.runelite.api.MenuEntry.class);
		when(bankEntry.getOption()).thenReturn("Use");
		when(bankEntry.getTarget()).thenReturn("Ranarr seed -> Bank chest");
		plugin.onMenuOptionClicked(new MenuOptionClicked(bankEntry));

		Assert.assertEquals(-100, plugin.lastFarmingActionTick);
	}

	@Test
	public void farming_droppingSeedAfterPlanting_isRecordedAsDroppedOwnedItemNotExpense()
	{
		int ranarrSeedId = 5295;
		stubTrackableItem(ranarrSeedId, "Ranarr seed", 35000L);

		when(client.getTickCount()).thenReturn(80);
		plugin.snapshotInitialized = true;
		plugin.previousInventorySnapshot = snapshot(ranarrSeedId, 2);

		// Player plants 1 seed
		net.runelite.api.MenuEntry entry = mock(net.runelite.api.MenuEntry.class);
		when(entry.getOption()).thenReturn("Use");
		when(entry.getTarget()).thenReturn("Ranarr seed -> Herb patch");
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));

		// 1 seed consumed
		when(client.getTickCount()).thenReturn(82);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, ranarrSeedId, 1)
		));

		Assert.assertEquals(35000L, plugin.session.getTotalExpenses());
		Assert.assertEquals(-100, plugin.lastFarmingActionTick);
		Assert.assertEquals(-100, plugin.lastFarmingChatTick);

		// 2 ticks later, player drops the remaining owned seed on the floor
		when(client.getTickCount()).thenReturn(84);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV)
		));

		// Dropping owned seed must NOT increase expenses! It must be recorded in recentlyDroppedOwnedItems
		Assert.assertEquals(35000L, plugin.session.getTotalExpenses());
		Assert.assertTrue("Dropped owned seed must be tracked in recentlyDroppedOwnedItems",
			plugin.recentlyDroppedOwnedItems.containsKey(ranarrSeedId));
	}

	@Test
	public void farming_fillingBottomlessBucket_triggersRebaselineGraceTicks()
	{
		int tick = 90;
		when(client.getTickCount()).thenReturn(tick);

		net.runelite.api.MenuEntry entry = mock(net.runelite.api.MenuEntry.class);
		when(entry.getOption()).thenReturn("Use");
		when(entry.getTarget()).thenReturn("Ultracompost -> Bottomless compost bucket");
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));

		Assert.assertTrue(plugin.interfaceTracker.isNeedsRebaseline());
		Assert.assertEquals(3, plugin.rebaselineGraceTicks);
		Assert.assertEquals(-100, plugin.lastFarmingActionTick);
	}

	@Test
	public void farming_usingBottomlessBucketOnHerbPatch_doesNotTriggerRebaseline()
	{
		int tick = 95;
		when(client.getTickCount()).thenReturn(tick);

		net.runelite.api.MenuEntry entry = mock(net.runelite.api.MenuEntry.class);
		when(entry.getOption()).thenReturn("Use");
		when(entry.getTarget()).thenReturn("Bottomless compost bucket -> Herb patch");
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));

		Assert.assertFalse(plugin.interfaceTracker.isNeedsRebaseline());
		Assert.assertEquals(0, plugin.rebaselineGraceTicks);
		Assert.assertEquals(tick, plugin.lastFarmingActionTick);
		Assert.assertEquals(Integer.valueOf(tick), plugin.lastSkillXpTicks.get(net.runelite.api.Skill.FARMING));
	}

	@Test
	public void farming_clickingUseOnBottomlessBucketInInventory_doesNotTriggerRebaseline()
	{
		int tick = 100;
		when(client.getTickCount()).thenReturn(tick);

		net.runelite.api.MenuEntry entry = mock(net.runelite.api.MenuEntry.class);
		when(entry.getOption()).thenReturn("Use");
		when(entry.getTarget()).thenReturn("Bottomless compost bucket");
		plugin.onMenuOptionClicked(new MenuOptionClicked(entry));

		Assert.assertFalse(plugin.interfaceTracker.isNeedsRebaseline());
		Assert.assertEquals(0, plugin.rebaselineGraceTicks);
	}

	@Test
	public void farming_harvestAndPickActions_recordFarmingActionTick()
	{
		int tick = 105;
		when(client.getTickCount()).thenReturn(tick);

		net.runelite.api.MenuEntry pickEntry = mock(net.runelite.api.MenuEntry.class);
		when(pickEntry.getOption()).thenReturn("Pick");
		when(pickEntry.getTarget()).thenReturn("Herb patch");
		plugin.onMenuOptionClicked(new MenuOptionClicked(pickEntry));

		Assert.assertEquals(tick, plugin.lastFarmingActionTick);
		Assert.assertEquals(Integer.valueOf(tick), plugin.lastSkillXpTicks.get(net.runelite.api.Skill.FARMING));

		net.runelite.api.MenuEntry harvestEntry = mock(net.runelite.api.MenuEntry.class);
		when(harvestEntry.getOption()).thenReturn("Harvest");
		when(harvestEntry.getTarget()).thenReturn("Allotment");
		plugin.onMenuOptionClicked(new MenuOptionClicked(harvestEntry));

		Assert.assertEquals(tick, plugin.lastFarmingActionTick);
	}

	@Test
	public void farming_harvestHerbsPlantSeedCleanHerbs_accurateExpensesAndProfit()
	{
		int seedId = 5295; // Ranarr seed
		int grimyId = 207; // Grimy ranarr weed
		int cleanId = 257; // Ranarr weed
		long seedPrice = 32962L;
		long grimyPrice = 5667L;
		long cleanPrice = 5857L;

		stubTrackableItem(seedId, "Ranarr seed", seedPrice);
		stubTrackableItem(grimyId, "Grimy ranarr weed", grimyPrice);
		stubTrackableItem(cleanId, "Ranarr weed", cleanPrice);

		int tick = 120;
		when(client.getTickCount()).thenReturn(tick);
		plugin.snapshotInitialized = true;
		plugin.previousInventorySnapshot = snapshot(seedId, 1);

		// 1. Player harvests 9 grimy ranarr weeds
		net.runelite.api.MenuEntry pickEntry = mock(net.runelite.api.MenuEntry.class);
		when(pickEntry.getOption()).thenReturn("Pick");
		when(pickEntry.getTarget()).thenReturn("Herb patch");
		plugin.onMenuOptionClicked(new MenuOptionClicked(pickEntry));

		tick += 2;
		when(client.getTickCount()).thenReturn(tick);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, seedId, 1, grimyId, 9)
		));
		Assert.assertEquals(9 * grimyPrice, plugin.session.getGrossProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());

		// 2. Player uses bottomless compost bucket on patch
		tick += 2;
		when(client.getTickCount()).thenReturn(tick);
		net.runelite.api.MenuEntry bucketEntry = mock(net.runelite.api.MenuEntry.class);
		when(bucketEntry.getOption()).thenReturn("Use");
		when(bucketEntry.getTarget()).thenReturn("Bottomless compost bucket -> Herb patch");
		plugin.onMenuOptionClicked(new MenuOptionClicked(bucketEntry));
		Assert.assertFalse(plugin.interfaceTracker.isNeedsRebaseline());
		Assert.assertEquals(0, plugin.rebaselineGraceTicks);

		// 3. Player plants 1 ranarr seed into the patch
		tick += 2;
		when(client.getTickCount()).thenReturn(tick);
		net.runelite.api.MenuEntry plantEntry = mock(net.runelite.api.MenuEntry.class);
		when(plantEntry.getOption()).thenReturn("Use");
		when(plantEntry.getTarget()).thenReturn("Ranarr seed -> Herb patch");
		plugin.onMenuOptionClicked(new MenuOptionClicked(plantEntry));

		tick += 2;
		when(client.getTickCount()).thenReturn(tick);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, grimyId, 9)
		));
		// Seed planted: expense of 32,962 gp
		Assert.assertEquals(seedPrice, plugin.session.getTotalExpenses());

		// 4. Player cleans all 9 grimy ranarr weeds into clean ranarr weeds
		tick += 2;
		when(client.getTickCount()).thenReturn(tick);
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, cleanId, 9)
		));

		// All 9 grimy were session-gained, so 0 grimy herbs are charged as supply expenses!
		Assert.assertEquals(seedPrice, plugin.session.getTotalExpenses());
		Assert.assertEquals(9 * cleanPrice, plugin.session.getGrossProfit());
		Assert.assertEquals((9 * cleanPrice) - seedPrice, plugin.session.getTotalProfit());
	}

	@Test
	public void onGameTick_playerWalking_clearsAndPreventsIdleState()
	{
		plugin.snapshotInitialized = true;
		when(config.idleTimeoutMinutes()).thenReturn(0);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);

		// Initially at (100, 200, 0)
		when(player.getWorldLocation()).thenReturn(new WorldPoint(100, 200, 0));
		when(player.getPoseAnimation()).thenReturn(808); // idle pose
		when(player.getIdlePoseAnimation()).thenReturn(808);
		when(player.getAnimation()).thenReturn(-1);
		when(client.getTickCount()).thenReturn(1);

		// Make session idle
		plugin.session = plugin.session.tick(0);
		Assert.assertTrue(plugin.session.isIdle());

		// Tick 1: Player hasn't moved yet, initial location registered
		plugin.onGameTick(new GameTick());
		Assert.assertTrue("Player standing still without input should remain idle", plugin.session.isIdle());

		// Tick 2: Player moves to (101, 200, 0)
		when(client.getTickCount()).thenReturn(2);
		when(player.getWorldLocation()).thenReturn(new WorldPoint(101, 200, 0));
		plugin.onGameTick(new GameTick());

		Assert.assertFalse("Player moving should clear idle status", plugin.session.isIdle());
	}

	@Test
	public void onGameTick_playerStandingStill_followingOrAfkTarget_goesIdle()
	{
		plugin.snapshotInitialized = true;
		when(config.idleTimeoutMinutes()).thenReturn(0);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);

		// Standing still at same location
		when(player.getWorldLocation()).thenReturn(new WorldPoint(100, 200, 0));
		when(player.getPoseAnimation()).thenReturn(808);
		when(player.getIdlePoseAnimation()).thenReturn(808);
		when(player.getAnimation()).thenReturn(-1);
		when(client.getTickCount()).thenReturn(1);

		plugin.onGameTick(new GameTick());

		// Tick 2: Still standing at same location
		when(client.getTickCount()).thenReturn(2);
		plugin.onGameTick(new GameTick());

		Assert.assertTrue("Standing still with no input should go idle", plugin.session.isIdle());
	}

	@Test
	public void onItemContainerChanged_clearsIdleState_evenWithoutProfit()
	{
		when(config.idleTimeoutMinutes()).thenReturn(0);
		plugin.session = plugin.session.tick(0);
		Assert.assertTrue(plugin.session.isIdle());

		// Item container change with no recognized loot/expense
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, 999999, 1)
		));

		Assert.assertFalse("Inventory container change should immediately clear idle status", plugin.session.isIdle());
	}

	@Test
	public void onItemContainerChanged_wornContainer_doesNotClearIdleState()
	{
		when(config.idleTimeoutMinutes()).thenReturn(0);
		plugin.session = plugin.session.tick(0);
		Assert.assertTrue(plugin.session.isIdle());

		// Equipment container change without inventory change (e.g. passive degradation)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.WORN,
			mockContainer(InventoryID.WORN, 999999, 1)
		));

		Assert.assertTrue("Passive worn container change should not clear idle status", plugin.session.isIdle());
	}

	@Test
	public void onAnimationChanged_animNegativeOne_doesNotClearIdleState()
	{
		when(config.idleTimeoutMinutes()).thenReturn(0);
		plugin.session = plugin.session.tick(0);
		Assert.assertTrue(plugin.session.isIdle());

		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getAnimation()).thenReturn(-1);

		net.runelite.api.events.AnimationChanged event = new net.runelite.api.events.AnimationChanged();
		event.setActor(player);
		plugin.onAnimationChanged(event);

		Assert.assertTrue("Transitioning to idle animation (-1) should not clear idle status", plugin.session.isIdle());
	}

	@Test
	public void onAnimationChanged_defensiveAnimation_doesNotClearIdleState()
	{
		when(config.idleTimeoutMinutes()).thenReturn(0);
		plugin.session = plugin.session.tick(0);
		Assert.assertTrue(plugin.session.isIdle());

		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getAnimation()).thenReturn(424); // unarmed defend

		net.runelite.api.events.AnimationChanged event = new net.runelite.api.events.AnimationChanged();
		event.setActor(player);
		plugin.onAnimationChanged(event);

		Assert.assertTrue("Defensive combat animation should not clear idle status", plugin.session.isIdle());
	}

	@Test
	public void onMenuOptionClicked_clearsIdleState()
	{
		when(config.idleTimeoutMinutes()).thenReturn(0);
		plugin.session = plugin.session.tick(0);
		Assert.assertTrue(plugin.session.isIdle());

		net.runelite.api.MenuEntry walkEntry = mock(net.runelite.api.MenuEntry.class);
		when(walkEntry.getOption()).thenReturn("Walk here");
		when(walkEntry.getTarget()).thenReturn("");
		plugin.onMenuOptionClicked(new MenuOptionClicked(walkEntry));

		Assert.assertFalse("Menu option click should immediately clear idle status", plugin.session.isIdle());
	}

	@Test
	public void trackSpent_whenDisabled_ignoresSupplyExpensesForGrossOnlyTracking()
	{
		when(config.trackSpent()).thenReturn(false);

		int prayerPot4 = net.runelite.api.gameval.ItemID._4DOSEPRAYERRESTORE;
		int prayerPot3 = net.runelite.api.gameval.ItemID._3DOSEPRAYERRESTORE;
		int sharkId = net.runelite.api.gameval.ItemID.SHARK;

		stubTrackableItem(prayerPot4, "Prayer potion(4)", 10_000L);
		stubTrackableItem(prayerPot3, "Prayer potion(3)", 7_500L);
		stubTrackableItem(sharkId, "Shark", 1_000L);

		// 1. Initial snapshot with 1x Prayer pot(4)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, prayerPot4, 1)
		));
		plugin.onGameTick(new GameTick());

		// 2. Sip potion -> 1x pot(3)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, prayerPot3, 1)
		));
		plugin.onGameTick(new GameTick());

		// With trackSpent = false, no supply expenses should be recorded
		Assert.assertEquals("Expenses should be 0 with trackSpent disabled", 0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals("Net profit should be 0", 0L, plugin.getSession().getTotalProfit());

		// 3. Loot 2x Shark (+2,000 gp gross)
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV,
			mockContainer(InventoryID.INV, prayerPot3, 1, sharkId, 2)
		));
		plugin.onGameTick(new GameTick());

		Assert.assertEquals("Gross profit should be 2,000 gp", 2_000L, plugin.getSession().getGrossProfit());
		Assert.assertEquals("Expenses should remain 0", 0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals("Net profit equals gross profit in gross tracking mode", 2_000L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void checkGoalNotification_whenTrackSpentDisabled_evaluatesAgainstGrossProfit()
	{
		when(config.trackSpent()).thenReturn(false);
		when(config.goalAmount()).thenReturn("10k");
		when(config.goalName()).thenReturn("Gross Goal");
		when(config.notifyOnGoal()).thenReturn(true);

		// Session with 12k gross profit, 5k expenses (net 7k, gross 12k)
		plugin.session = CoinFlowSession.createNew().withGainsAndExpenses(
			Collections.singletonMap(1, new CoinFlowSession.TrackedItem(1, "Onyx", 1, 12_000L)),
			Collections.singletonMap(2, new CoinFlowSession.TrackedItem(2, "Prayer potion", 1, 5_000L))
		);

		plugin.checkGoalNotification();

		// Should notify because gross profit 12k >= goal 10k, even though net is only 7k
		Assert.assertTrue("Goal completed notified flag should be set", plugin.goalCompletedNotified);
		org.mockito.Mockito.verify(notifier).notify(org.mockito.ArgumentMatchers.contains("Gross Goal"));
	}
}




