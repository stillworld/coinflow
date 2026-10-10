package com.coinflow;

import java.util.HashSet;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Minigame quarantine: region-scoped suppression for instanced minigames,
 * HUD-visibility suppression for overworld minigames (Tempoross), and the
 * global minigame-only item denylist (Castle Wars supplies, BA eggs, NMZ
 * potions, GOTR cells, Tithe seeds, ...). None of it may book profit or
 * spend — the grant, consumption inside, and exit strip are all invisible.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class CoinFlowPluginMinigameQuarantineTest
{
	// Verified in game: Gauntlet loads as [7512]; the LMS match island as
	// [14174, 14175, 14176, 14430, 14431, 14432]. The LMS lobby's halo was
	// [12344, 12345, 12600, 12601] — those are real Ferox/Wilderness
	// overworld chunks, so the lobby is quarantined by HUD, not region.
	private static final int FEROX_REGION = 12344; // real Ferox Enclave — must NEVER be suppressed
	private static final int GAUNTLET_REGION = 7512;
	private static final int[] LMS_MATCH_REGIONS = {14174, 14175, 14176, 14430, 14431, 14432};

	private static final int BANDAGES = ItemID.CASTLEWARS_BANDAGES;
	private static final int SW_CAPE = ItemID.SOUL_WARS_RED_CAPE;
	private static final int NMZ_OVERLOAD = ItemID.NZONE4DOSEOVERLOADPOTION;
	private static final int WHIP = ItemID.ABYSSAL_WHIP;
	private static final int SHARK = ItemID.SHARK;

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
	@Mock WorldView worldView;

	private CoinFlowPlugin plugin;

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
		plugin.ignoredItemNames = new HashSet<>();

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
		when(client.isClientThread()).thenReturn(true);
		when(client.getTopLevelWorldView()).thenReturn(worldView);
		when(worldView.getMapRegions()).thenReturn(new int[]{FEROX_REGION});

		stubItem(ItemID.COINS, "Coins", 1L);
		stubItem(BANDAGES, "Bandages", 0L);
		stubItem(SW_CAPE, "Red cape", 0L);
		stubItem(NMZ_OVERLOAD, "Overload", 0L);
		stubItem(WHIP, "Abyssal whip", 450L);
		stubItem(SHARK, "Shark", 800L, "Eat");
	}

	// ── Region quarantine ──────────────────────────────────────────────

	/**
	 * The Gauntlet's instanced region suppresses its full grant/strip the
	 * same way the tutorial arena does.
	 */
	@Test
	public void gauntletRegion_suppressesGrantAndStrip()
	{
		fireInv(ItemID.COINS, 50_000);
		Assert.assertEquals(0L, plugin.session.getTotalProfit());

		when(worldView.getMapRegions()).thenReturn(new int[]{GAUNTLET_REGION});
		fireInv(ItemID.COINS, 50_000, WHIP, 1);
		fireWorn(WHIP, 1);
		Assert.assertTrue(plugin.isTrackingSuppressed());
		Assert.assertEquals(0L, plugin.session.getTotalProfit());

		when(worldView.getMapRegions()).thenReturn(new int[]{FEROX_REGION});
		fireWorn();
		fireInv(ItemID.COINS, 50_000);

		plugin.setNeedsRebaseline(false);
		for (int i = 0; i < 3; i++)
		{
			plugin.onGameTick(new GameTick());
		}

		fireInv(ItemID.COINS, 50_000, SHARK, 1);
		Assert.assertEquals(800L, plugin.session.getTotalProfit());
	}

	/**
	 * Verified LMS flow: the lobby (HUD group 333) suppresses since its
	 * region halo is shared Ferox overworld; the match island (verified
	 * 3x2 regions) suppresses by region; and an in-match death must not
	 * book a "Death" row — the real-client run before LMS was quarantined
	 * recorded a 3.5m one.
	 */
	@Test
	public void lms_suppressesAndGatesDeath()
	{
		fireInv(ItemID.COINS, 50_000);
		Assert.assertFalse(plugin.isTrackingSuppressed());
		Assert.assertFalse(InterfaceTracker.SUPPRESSED_REGIONS.contains(FEROX_REGION));

		// Lobby: suppression via the lobby HUD, not the shared region halo.
		Widget lobbyHud = hudWidget(false);
		when(client.getWidget(InterfaceID.BR_LOBBYOVERLAY, 0)).thenReturn(lobbyHud);
		fireInv(ItemID.COINS, 50_000);
		Assert.assertTrue(plugin.isTrackingSuppressed());

		// Match island: suppression via the verified region set.
		when(client.getWidget(InterfaceID.BR_LOBBYOVERLAY, 0)).thenReturn(null);
		when(worldView.getMapRegions()).thenReturn(LMS_MATCH_REGIONS);
		fireInv(ItemID.COINS, 50_000, WHIP, 1); // loadout grant
		Assert.assertTrue(plugin.isTrackingSuppressed());
		Assert.assertEquals(0L, plugin.session.getTotalProfit());

		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		plugin.onActorDeath(new ActorDeath(player));
		Assert.assertFalse(plugin.deathTracker.isPending());
	}

	// ── Item quarantine ────────────────────────────────────────────────

	/**
	 * A minigame-only item arriving counts for nothing — the table supply
	 * was never owned wealth.
	 */
	@Test
	public void minigameItemGain_isIgnored()
	{
		fireInv(ItemID.COINS, 50_000);
		fireInv(ItemID.COINS, 50_000, BANDAGES, 10);

		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	/**
	 * Losing a minigame-only item (exit strip, barricade placed) is never
	 * a supply expense either.
	 */
	@Test
	public void minigameItemLoss_isNotExpensed()
	{
		fireInv(ItemID.COINS, 50_000, BANDAGES, 10);
		fireInv(ItemID.COINS, 50_000);

		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	/**
	 * The Poll-83 additions to the arena — the respawn-room "Castlewars
	 * brew" potion (all dose ids), the issued "Rune pouch", and the
	 * arena-only ammo variants — are quarantined like the table supplies.
	 * A real run saw `Gained: Castlewars brew(4)` and `Gained: Rune pouch`
	 * book phantom rows before these ids were denylisted.
	 */
	@Test
	public void castlewarsBrewPouchAndAmmo_areIgnored()
	{
		fireInv(ItemID.COINS, 50_000);
		fireInv(ItemID.COINS, 50_000,
			ItemID._4DOSE_CASTLEWARS_SKILL_POTION, 1,
			ItemID._3DOSE_CASTLEWARS_SKILL_POTION, 1,
			ItemID._2DOSE_CASTLEWARS_SKILL_POTION, 1,
			ItemID._1DOSE_CASTLEWARS_SKILL_POTION, 1,
			ItemID.CASTLEWARS_RUNE_REPLACEMENT, 1,
			ItemID.CASTLEWARS_BOLT, 20);

		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	/**
	 * Drinking the brew inside the arena is a dose transition between two
	 * quarantined ids — nothing to book either way.
	 */
	@Test
	public void castlewarsBrewDoseStep_isIgnored()
	{
		fireInv(ItemID.COINS, 50_000, ItemID._4DOSE_CASTLEWARS_SKILL_POTION, 1);
		fireInv(ItemID.COINS, 50_000, ItemID._3DOSE_CASTLEWARS_SKILL_POTION, 1);

		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	/**
	 * Stripping is per-entry: real items in the same diff still track.
	 */
	@Test
	public void mixedDiff_keepsRealItems()
	{
		fireInv(ItemID.COINS, 50_000);
		fireInv(ItemID.COINS, 50_000, BANDAGES, 10, SHARK, 1);

		Assert.assertEquals(800L, plugin.session.getTotalProfit());
	}

	/**
	 * A quarantined gain inside a re-baseline window must not make the
	 * accompanying coin loss look like a purchase — without the strip,
	 * "+bandages, -coins" routes to ShopTracker and books the cost.
	 */
	@Test
	public void quarantinedItemInsideRebaseline_doesNotRouteToShop()
	{
		fireInv(ItemID.COINS, 50_000);

		plugin.setNeedsRebaseline(true);
		fireInv(ItemID.COINS, 45_000, BANDAGES, 10);

		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	/**
	 * Equipped minigame items (Soul Wars team capes) are filtered on the
	 * WORN side too — no fired-ammo expense, no diff booked.
	 */
	@Test
	public void wornMinigameItem_isIgnored()
	{
		fireInv(ItemID.COINS, 50_000);
		fireWorn(SW_CAPE, 1);
		fireWorn();

		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	// ── HUD quarantine ─────────────────────────────────────────────────

	private Widget hudWidget(boolean hidden)
	{
		Widget w = mock(Widget.class);
		when(w.isHidden()).thenReturn(hidden);
		return w;
	}

	/**
	 * While a quarantine HUD (Tempoross) is visible, diffs are suppressed —
	 * including gains of real item ids, since the loaned tools are ordinary
	 * buckets and harpoons.
	 */
	@Test
	public void hudVisible_suppressesDiffs()
	{
		fireInv(ItemID.COINS, 50_000);

		Widget hud = hudWidget(false);
		when(client.getWidget(InterfaceID.TEMPOROSS_HUD, 0)).thenReturn(hud);
		fireInv(ItemID.COINS, 50_000); // latch the HUD state
		Assert.assertTrue(plugin.isTrackingSuppressed());

		fireInv(ItemID.COINS, 50_000, SHARK, 5);
		Assert.assertEquals(0L, plugin.session.getTotalProfit());
	}

	/**
	 * On HUD close the loaned-tool strip is baselined inside the grace
	 * window, and normal tracking resumes once the window ages out.
	 */
	@Test
	public void hudClose_baselinesStripAndResumes()
	{
		fireInv(ItemID.COINS, 50_000);
		Widget hud = hudWidget(false);
		when(client.getWidget(InterfaceID.TEMPOROSS_HUD, 0)).thenReturn(hud);
		fireInv(ItemID.COINS, 50_000, SHARK, 2); // granted while suppressed
		Assert.assertEquals(0L, plugin.session.getTotalProfit());

		// HUD gone; the strip lands over the next events during grace.
		when(client.getWidget(InterfaceID.TEMPOROSS_HUD, 0)).thenReturn(null);
		fireInv(ItemID.COINS, 50_000, SHARK, 1);
		fireInv(ItemID.COINS, 50_000);
		Assert.assertTrue(plugin.isTrackingSuppressed()); // still in grace
		Assert.assertEquals(0L, plugin.session.getTotalProfit());

		plugin.setNeedsRebaseline(false);
		for (int i = 0; i < 3; i++)
		{
			plugin.onGameTick(new GameTick());
		}
		Assert.assertFalse(plugin.isTrackingSuppressed());

		fireInv(ItemID.COINS, 50_000, SHARK, 1);
		Assert.assertEquals(800L, plugin.session.getTotalProfit());
	}

	/**
	 * Regression for a real wedge observed in game: if the HUD closes while
	 * the snapshot is still uninitialized (login baseline ran before the
	 * inventory container existed), the grace window must still age out —
	 * otherwise suppression stays on forever and tracking dies silently.
	 */
	@Test
	public void hudCloseGrace_agesOutWhileUninitialized()
	{
		Widget hud = hudWidget(false);
		when(client.getWidget(InterfaceID.TEMPOROSS_HUD, 0)).thenReturn(hud);
		plugin.onGameTick(new GameTick()); // latch visible — runs before the snapshot guard
		Assert.assertTrue(plugin.isTrackingSuppressed());

		when(client.getWidget(InterfaceID.TEMPOROSS_HUD, 0)).thenReturn(null);
		plugin.onGameTick(new GameTick()); // latch closed -> grace starts
		Assert.assertTrue(plugin.isTrackingSuppressed());

		for (int i = 0; i < 3; i++)
		{
			plugin.onGameTick(new GameTick());
		}
		Assert.assertFalse(plugin.isTrackingSuppressed());
		Assert.assertFalse(plugin.snapshotInitialized);
	}

	/**
	 * A staged death while a quarantine HUD is up must not open a pending
	 * death capture — same reasoning as the tutorial region gate.
	 */
	@Test
	public void deathDuringHud_isNotTracked()
	{
		fireInv(ItemID.COINS, 50_000);
		Widget hud = hudWidget(false);
		when(client.getWidget(InterfaceID.TEMPOROSS_HUD, 0)).thenReturn(hud);
		fireInv(ItemID.COINS, 50_000, WHIP, 1);

		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		plugin.onActorDeath(new ActorDeath(player));

		Assert.assertFalse(plugin.deathTracker.isPending());
	}

	/**
	 * The 3-tick exit grace after the HUD closes exists to absorb the loaner
	 * strip — a death inside it is equally staged and must not capture.
	 */
	@Test
	public void deathDuringHudExitGrace_isNotTracked()
	{
		fireInv(ItemID.COINS, 50_000);
		Widget hud = hudWidget(false);
		when(client.getWidget(InterfaceID.TEMPOROSS_HUD, 0)).thenReturn(hud);
		fireInv(ItemID.COINS, 50_000, WHIP, 1); // latch visible
		Assert.assertTrue(plugin.isTrackingSuppressed());

		when(client.getWidget(InterfaceID.TEMPOROSS_HUD, 0)).thenReturn(null);
		fireInv(ItemID.COINS, 50_000, WHIP, 1); // latch closed -> grace starts
		Assert.assertTrue("Exit grace must still be suppressing", plugin.isTrackingSuppressed());

		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		plugin.onActorDeath(new ActorDeath(player));

		Assert.assertFalse(plugin.deathTracker.isPending());
	}

	/**
	 * A coin loss alongside a minigame-item gain is not a shop purchase —
	 * after the denylist strips the item the diff is a bare cash spend and
	 * must be expensed, not asset-converted.
	 */
	@Test
	public void quarantinedItemInPurchaseShape_expensesCoinLoss()
	{
		fireInv(ItemID.COINS, 50_000);

		fireInv(ItemID.COINS, 49_500, BANDAGES, 2);

		Assert.assertEquals(500L, plugin.session.getTotalExpenses());
		Assert.assertEquals(-500L, plugin.session.getTotalProfit());
		Assert.assertFalse(plugin.session.getTrackedItems().containsKey(BANDAGES));
	}

	// ── Helpers ────────────────────────────────────────────────────────

	private void fireInv(int... idQtyPairs)
	{
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.INV, mockContainer(InventoryID.INV, idQtyPairs)));
	}

	private void fireWorn(int... idQtyPairs)
	{
		plugin.onItemContainerChanged(new ItemContainerChanged(
			InventoryID.WORN, mockContainer(InventoryID.WORN, idQtyPairs)));
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

	private void stubItem(int itemId, String name, long price, String... actions)
	{
		when(itemManager.canonicalize(itemId)).thenReturn(itemId);
		when(itemManager.getItemPrice(itemId)).thenReturn(price);
		ItemComposition comp = mock(ItemComposition.class);
		when(comp.getName()).thenReturn(name);
		when(comp.isTradeable()).thenReturn(true);
		when(comp.getInventoryActions()).thenReturn(actions);
		when(itemManager.getItemComposition(itemId)).thenReturn(comp);
	}
}
