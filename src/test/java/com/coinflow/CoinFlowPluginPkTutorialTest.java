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
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
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
 * Region-scoped suppression for the PvP tutorial arena (Pete Kayer, region
 * 10588). The tutorial issues loaner gear/supplies on entry and strips them
 * on exit — none of it may be tracked as profit or spend.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class CoinFlowPluginPkTutorialTest
{
	private static final int FEROX_REGION = 12344;
	private static final int TUTORIAL_REGION = 10588;

	private static final int WHIP = ItemID.ABYSSAL_WHIP;
	private static final int TORSO = 10551; // Fighter torso
	private static final int BLOOD_RUNE = 565;
	private static final int BOLTS_E = 21949; // Diamond bolts (e)
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
		stubItem(WHIP, "Abyssal whip", 450L);
		stubItem(TORSO, "Fighter torso", 60L);
		stubItem(BLOOD_RUNE, "Blood rune", 0L);
		stubItem(BOLTS_E, "Diamond bolts (e)", 0L);
		stubItem(SHARK, "Shark", 800L, "Eat");
	}

	// ── Tests ────────────────────────────────────────────────────────────

	/**
	 * The loaner grant arriving with the scene load into the arena must not
	 * be recorded as profit.
	 */
	@Test
	public void enteringTutorial_suppressesLoanerGrant()
	{
		fireInv(ItemID.COINS, 50_000);
		Assert.assertEquals(0L, plugin.session.getTotalProfit());

		when(worldView.getMapRegions()).thenReturn(new int[]{TUTORIAL_REGION});

		fireInv(ItemID.COINS, 50_000, WHIP, 1, TORSO, 1, BLOOD_RUNE, 1_000, BOLTS_E, 1_000);
		fireWorn(WHIP, 1, TORSO, 1);

		Assert.assertTrue(plugin.interfaceTracker.isInSuppressedRegion());
		Assert.assertTrue(plugin.isTrackingSuppressed());
		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	/**
	 * Supplies consumed inside the arena are suppressed like an open bank.
	 */
	@Test
	public void insideTutorial_suppressesSupplyUse()
	{
		fireInv(ItemID.COINS, 50_000);
		when(worldView.getMapRegions()).thenReturn(new int[]{TUTORIAL_REGION});
		fireInv(ItemID.COINS, 50_000, BOLTS_E, 1_000);

		// Firing bolts removes them from the container — must not expense.
		fireInv(ItemID.COINS, 50_000, BOLTS_E, 999);

		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	/**
	 * On exit the loaner strip must be adopted as the new baseline rather
	 * than booked as losses, and normal tracking resumes afterwards.
	 */
	@Test
	public void exitingTutorial_baselinesStripAndResumesTracking()
	{
		fireInv(ItemID.COINS, 50_000);
		when(worldView.getMapRegions()).thenReturn(new int[]{TUTORIAL_REGION});
		fireInv(ItemID.COINS, 50_000, WHIP, 1, TORSO, 1, BLOOD_RUNE, 1_000, BOLTS_E, 1_000);
		fireWorn(WHIP, 1, TORSO, 1);
		Assert.assertEquals(0L, plugin.session.getTotalProfit());

		// Exit: back at Ferox, tutorial gear stripped across worn + inv events.
		when(worldView.getMapRegions()).thenReturn(new int[]{FEROX_REGION});
		fireWorn();
		fireInv(ItemID.COINS, 50_000, BOLTS_E, 999);

		// A trailing strip on the next event (e.g. rune pouch varbit settle)
		// must still be baselined — a single-shot re-baseline is consumed by
		// the first event, letting later removals book supply expenses.
		fireInv(ItemID.COINS, 50_000);

		Assert.assertFalse(plugin.interfaceTracker.isInSuppressedRegion());
		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());

		// Emulate the invokeLater takeBaseline cleanup that clears the flag on
		// LOGGED_IN — the mocked clientThread never runs it.
		plugin.setNeedsRebaseline(false);

		// The exit window ages out over game ticks.
		for (int i = 0; i < 3; i++)
		{
			plugin.onGameTick(new GameTick());
		}

		// A real gain after the tutorial is tracked normally again.
		fireInv(ItemID.COINS, 50_000, SHARK, 1);
		Assert.assertEquals(800L, plugin.session.getTotalProfit());
	}

	/**
	 * A death inside the arena is staged tutorial content — it must not open
	 * a pending death capture, or the loaner strip would book a "Death" row.
	 */
	@Test
	public void deathInsideTutorial_isNotTracked()
	{
		fireInv(ItemID.COINS, 50_000);
		when(worldView.getMapRegions()).thenReturn(new int[]{TUTORIAL_REGION});
		fireInv(ItemID.COINS, 50_000, WHIP, 1);

		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		plugin.onActorDeath(new ActorDeath(player));

		Assert.assertFalse(plugin.deathTracker.isPending());
	}

	// ── Helpers ──────────────────────────────────────────────────────────

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
