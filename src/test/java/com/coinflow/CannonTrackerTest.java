package com.coinflow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CannonTracker}: deployed cannon parts are netted out of
 * inventory diffs (setup is not an expense, pickup is not profit), loaded
 * cannonballs are expensed on load and reversed on return, and Drop-click
 * intents keep manual part drops on the own-drop path.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class CannonTrackerTest
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

		when(config.trackSpent()).thenReturn(true);
		when(config.countDropsAsSpent()).thenReturn(true);
		when(config.idleTimeoutMinutes()).thenReturn(5);
		when(config.goalAmount()).thenReturn("");
		when(config.goalName()).thenReturn("");
		when(config.includeAfkTime()).thenReturn(false);
		when(config.trackUntradeableItems()).thenReturn(true);
		when(client.isClientThread()).thenReturn(true);

		stubTrackableItem(ItemID.COINS, "Coins", 1L);
		stubTrackableItem(ItemID.TWPART1, "Cannon base", 180_000L);
		stubTrackableItem(ItemID.TWPART2, "Cannon stand", 190_000L);
		stubTrackableItem(ItemID.TWPART3, "Cannon barrels", 180_000L);
		stubTrackableItem(ItemID.TWPART4, "Cannon furnace", 200_000L);
		stubTrackableItem(ItemID.MCANNONBALL, "Steel cannonball", 150L);
		stubTrackableItem(ItemID.GRANITE_CANNONBALL, "Granite cannonball", 500L);
	}

	// ── Setup / teardown netting ─────────────────────────────────────────

	@Test
	public void deploy_recordsNoExpenseOrProfit()
	{
		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1, ItemID.TWPART4, 1);
		fireInv(); // all four parts leave the inventory

		Assert.assertTrue(plugin.cannonTracker.isDeployed());
		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	@Test
	public void multiTickSetup_accumulatesDeployedParts()
	{
		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1, ItemID.TWPART4, 1);
		fireInv(ItemID.TWPART2, 1, ItemID.TWPART3, 1, ItemID.TWPART4, 1); // base placed
		Assert.assertTrue(plugin.cannonTracker.isDeployed());
		fireInv(ItemID.TWPART3, 1, ItemID.TWPART4, 1);
		fireInv(ItemID.TWPART4, 1);
		Assert.assertTrue(plugin.cannonTracker.isDeployed());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());

		// Pickup returns everything — nothing books as loot.
		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1, ItemID.TWPART4, 1);
		Assert.assertFalse(plugin.cannonTracker.isDeployed());
		Assert.assertEquals(0L, plugin.session.getTotalProfit());
	}

	@Test
	public void partialPickup_staysDeployed()
	{
		deployCannon();

		fireInv(ItemID.TWPART4, 1, ItemID.MCANNONBALL, 100); // only the furnace comes back

		Assert.assertTrue(plugin.cannonTracker.isDeployed());
		Assert.assertEquals(0L, plugin.session.getTotalProfit());
	}

	@Test
	public void partDropClick_isDropNotDeployment()
	{
		fireInv(ItemID.TWPART1, 1);

		plugin.recentDropIntents.put(ItemID.TWPART1, client.getTickCount());
		fireInv();

		Assert.assertFalse("A Drop-clicked part is a drop, not a deploy",
			plugin.cannonTracker.isDeployed());
	}

	@Test
	public void partGainWithoutDeployment_isGain()
	{
		fireInv();
		fireInv(ItemID.TWPART1, 1);

		Assert.assertFalse(plugin.cannonTracker.isDeployed());
		Assert.assertEquals(180_000L, plugin.session.getTotalProfit());
	}

	// ── Cannonballs ──────────────────────────────────────────────────────

	@Test
	public void loadedBalls_expenseReversedOnPickup()
	{
		deployCannon();

		// Load 100 balls: they leave inventory → ammo expense.
		fireInv();
		Assert.assertEquals(100L * 150L, plugin.session.getTotalExpenses());

		// Pickup returns the kit + 60 leftover balls.
		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1,
			ItemID.TWPART4, 1, ItemID.MCANNONBALL, 60);

		Assert.assertFalse(plugin.cannonTracker.isDeployed());
		Assert.assertEquals("Returned balls reverse the load expense",
			40L * 150L, plugin.session.getTotalExpenses());
		Assert.assertEquals(0L, plugin.session.getGrossProfit());
	}

	@Test
	public void ballReturnPartial_excessBooksAsGain()
	{
		deployCannon();
		fireInv(ItemID.MCANNONBALL, 80); // only 20 loaded into the cannon

		Assert.assertEquals(20L * 150L, plugin.session.getTotalExpenses());

		// Pickup returns the kit; 30 more balls than the 80 in inventory
		// arrive — 20 reverse the load expense, the other 10 book as loot.
		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1,
			ItemID.TWPART4, 1, ItemID.MCANNONBALL, 110);

		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
		Assert.assertEquals(10L * 150L, plugin.session.getGrossProfit());
	}

	@Test
	public void ballLossWithoutDeployment_stillExpensedButNotLoaded()
	{
		fireInv(ItemID.MCANNONBALL, 100);
		fireInv(); // balls leave with no cannon deployed — e.g. sold/dropped

		Assert.assertFalse(plugin.cannonTracker.isDeployed());
		Assert.assertEquals(100L * 150L, plugin.session.getTotalExpenses());
	}

	@Test
	public void shopPurchaseWhileDeployed_isNotCannonReturn()
	{
		deployCannon();
		fireInv(ItemID.MCANNONBALL, 50); // 50 loaded, expense = 7500

		// Simulate a shop-open ball purchase: gains are not netted.
		Map<Integer, Integer> gains = new HashMap<>();
		gains.put(ItemID.MCANNONBALL, 200);
		Map<Integer, Long> reversals = plugin.cannonTracker.netDiff(gains, new HashMap<>(),
			client.getTickCount(), true);

		Assert.assertTrue(reversals.isEmpty());
		Assert.assertEquals(200, (int) gains.get(ItemID.MCANNONBALL));
	}

	@Test
	public void reset_clearsDeployedState()
	{
		deployCannon();
		plugin.cannonTracker.reset();

		Assert.assertFalse(plugin.cannonTracker.isDeployed());
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	/**
	 * Baselines the inventory carrying the full cannon kit, then removes the
	 * four parts in one diff — a deployed cannon with nothing loaded.
	 */
	private void deployCannon()
	{
		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1,
			ItemID.TWPART4, 1, ItemID.MCANNONBALL, 100);
		fireInv(ItemID.MCANNONBALL, 100); // parts consumed by the deployment
		Assert.assertTrue(plugin.cannonTracker.isDeployed());
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

	private void stubTrackableItem(int itemId, String name, long price, String... actions)
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
