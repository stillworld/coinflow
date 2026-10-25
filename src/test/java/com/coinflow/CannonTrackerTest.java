package com.coinflow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
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

		// Server varps agree the cannon is down in these tests — deploys
		// observed via diffs would otherwise heal as phantom state.
		when(client.getVarpValue(VarPlayerID.DROPCANNON)).thenReturn(4);
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

	// ── Server-varp seeding (cannon already down at login/reset) ────────

	@Test
	public void preDeployedCannon_pickupIsNotProfit()
	{
		// Cannon deployed before tracking began — no deploy diff was ever
		// observed, so the ledger is seeded from the server varps.
		when(client.getVarpValue(VarPlayerID.DROPCANNON)).thenReturn(4);
		plugin.cannonTracker.syncDeployed();

		Assert.assertTrue(plugin.cannonTracker.isDeployed());

		fireInv();
		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1, ItemID.TWPART4, 1);

		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertFalse(plugin.cannonTracker.isDeployed());
	}

	@Test
	public void decayedCannon_reclaimIsNotProfit()
	{
		// A decayed cannon returns no parts on its own, but the Nulodion
		// reclaim still has to net against the ledger.
		when(client.getVarbitValue(VarbitID.MCANNON_DECAYED)).thenReturn(1);
		plugin.cannonTracker.syncDeployed();

		Assert.assertTrue(plugin.cannonTracker.isDeployed());

		fireInv();
		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1, ItemID.TWPART4, 1);

		Assert.assertEquals(0L, plugin.session.getTotalProfit());
	}

	@Test
	public void varpSync_doesNotInflateObservedDeploy()
	{
		deployCannon();
		fireInv(); // load the 100 balls into the deployed cannon

		// Mid-session sync after a diff-observed deploy must not add a
		// second phantom kit on top of the recorded one.
		when(client.getVarpValue(VarPlayerID.DROPCANNON)).thenReturn(4);
		plugin.cannonTracker.syncDeployed();

		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1,
			ItemID.TWPART4, 1, ItemID.MCANNONBALL, 100);

		Assert.assertFalse(plugin.cannonTracker.isDeployed());
		Assert.assertEquals(0L, plugin.session.getGrossProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	@Test
	public void partialSetupVarp_doesNotSeed()
	{
		when(client.getVarpValue(VarPlayerID.DROPCANNON)).thenReturn(2);
		plugin.cannonTracker.syncDeployed();

		Assert.assertFalse(plugin.cannonTracker.isDeployed());
	}

	// ── Phantom ledger healing ────────────────────────────────────────────

	// A spare base carried into the deploy and later lost while the cannon
	// is down merges as a second deploy — {TWPART1:2} survives the
	// single-part return and would stick the ledger "deployed" forever.
	private void deployWithSparePartPhantom()
	{
		fireInv(ItemID.TWPART1, 2, ItemID.TWPART2, 1, ItemID.TWPART3, 1,
			ItemID.TWPART4, 1, ItemID.MCANNONBALL, 100);
		fireInv(ItemID.TWPART1, 1, ItemID.MCANNONBALL, 100); // kit consumed, spare stays
		fireInv(ItemID.MCANNONBALL, 100); // spare base leaves (noted/sold) — misread as deploy
		Assert.assertTrue(plugin.cannonTracker.isDeployed());
	}

	@Test
	public void phantomDeployEntry_healsOnPickup()
	{
		deployWithSparePartPhantom();
		when(client.getVarpValue(VarPlayerID.DROPCANNON)).thenReturn(0);

		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1, ItemID.TWPART4, 1);

		Assert.assertFalse("Phantom deploy state cleared on pick-up",
			plugin.cannonTracker.isDeployed());
		Assert.assertEquals(0L, plugin.session.getGrossProfit());
	}

	@Test
	public void phantomDeployEntry_noHealWhileServerDeployed()
	{
		// Same phantom, but the server still reports the cannon down —
		// nothing is cleared (the residue may be real state).
		deployWithSparePartPhantom();

		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1, ItemID.TWPART4, 1);

		Assert.assertTrue(plugin.cannonTracker.isDeployed());
	}

	@Test
	public void phantomDeployEntry_healsAtBaseline()
	{
		deployWithSparePartPhantom();
		when(client.getVarpValue(VarPlayerID.DROPCANNON)).thenReturn(0);

		plugin.cannonTracker.reconcileBaseline();

		Assert.assertFalse(plugin.cannonTracker.isDeployed());
	}

	@Test
	public void dropcannonVarpEvent_seedsDeployed()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getVarpValue(VarPlayerID.DROPCANNON)).thenReturn(4);

		VarbitChanged varp = new VarbitChanged();
		varp.setVarpId(VarPlayerID.DROPCANNON);
		varp.setVarbitId(-1);
		varp.setValue(4);
		plugin.onVarbitChanged(varp);

		Assert.assertTrue(plugin.cannonTracker.isDeployed());
	}

	// ── Varp-seeded ball contents (pre-session/pre-reset loads) ─────────

	@Test
	public void seededBalls_returnWithoutProfit()
	{
		// Cannon already loaded before tracking began: the returned balls
		// strip against the seeded pool — neutral, not loot, no reversal.
		when(client.getVarpValue(VarPlayerID.DROPCANNON)).thenReturn(4);
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(20);
		plugin.cannonTracker.syncDeployed();

		fireInv();
		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1,
			ItemID.TWPART4, 1, ItemID.MCANNONBALL, 20);

		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	@Test
	public void seededBalls_stripAnyBallType()
	{
		// The varp carries no type info — the seeded pool strips any
		// cannonball id, so a granite return is still neutral.
		when(client.getVarpValue(VarPlayerID.DROPCANNON)).thenReturn(4);
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(10);
		plugin.cannonTracker.syncDeployed();

		fireInv();
		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1,
			ItemID.TWPART4, 1, ItemID.GRANITE_CANNONBALL, 10);

		Assert.assertEquals(0L, plugin.session.getTotalProfit());
	}

	@Test
	public void sessionReset_seededBallsReturnNeutral()
	{
		// Reproduces the reported case: load 30, session reset, load 17,
		// pick up — the pre-reset contents must not book as profit.
		deployCannon();
		fireInv(); // load the 100 balls
		plugin.session = CoinFlowSession.createNew();
		plugin.cannonTracker.reset(); // what resetSession does

		// Baseline after the reset reseeds from varps: cannon still down,
		// 25 balls still inside. The 17 balls carried into the new session
		// land in the re-baseline, not a gain diff.
		when(client.getVarpValue(VarPlayerID.DROPCANNON)).thenReturn(4);
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(25);
		plugin.cannonTracker.syncDeployed();
		plugin.previousInventorySnapshot = null;
		plugin.snapshotInitialized = false;
		fireInv(ItemID.MCANNONBALL, 17);

		fireInv(); // reload the 17 into the cannon — a new-session expense
		Assert.assertEquals(17L * 150L, plugin.session.getTotalExpenses());

		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1,
			ItemID.TWPART4, 1, ItemID.MCANNONBALL, 25 + 17);

		Assert.assertEquals("Pre-reset balls return neutral — not loot",
			0L, plugin.session.getGrossProfit());
		Assert.assertEquals("New-session load reversed on return",
			0L, plugin.session.getTotalExpenses());
		Assert.assertFalse(plugin.cannonTracker.isDeployed());
	}

	@Test
	public void seededBalls_doNotInflateReversals()
	{
		deployCannon();
		fireInv(); // load the 100 balls — booked in-session

		// Varp reports the same 100 — nothing new seeds on top of the
		// observed loads, so the return reverses exactly the expense.
		when(client.getVarpValue(VarPlayerID.DROPCANNON)).thenReturn(4);
		when(client.getVarpValue(VarPlayerID.ROCKTHROWER)).thenReturn(100);
		plugin.cannonTracker.syncDeployed();

		fireInv(ItemID.TWPART1, 1, ItemID.TWPART2, 1, ItemID.TWPART3, 1,
			ItemID.TWPART4, 1, ItemID.MCANNONBALL, 100);

		Assert.assertEquals(0L, plugin.session.getGrossProfit());
		Assert.assertEquals(0L, plugin.session.getTotalExpenses());
	}

	@Test
	public void decayedCannon_dropsBallLedger()
	{
		deployCannon();
		fireInv(); // load the 100 balls — expense booked

		// Decay destroys the cannon's contents: ball pools clear and a
		// later ball gain is real loot, not a return.
		when(client.getVarbitValue(VarbitID.MCANNON_DECAYED)).thenReturn(1);
		plugin.cannonTracker.syncDeployed();

		fireInv(ItemID.MCANNONBALL, 5);
		Assert.assertEquals(5L * 150L, plugin.session.getGrossProfit());
	}

	// ── Rune-pouch deferral interplay ────────────────────────────────────

	@Test
	public void pickupWithRunePouch_stillReversesBallExpense()
	{
		// Regression: with a pouch carried, a fully-netted pickup diff hit
		// the deferred-rune early return and silently dropped the ball
		// reversals — the load expense stayed booked forever.
		fireInv(ItemID.BH_RUNE_POUCH, 1, ItemID.TWPART1, 1, ItemID.TWPART2, 1,
			ItemID.TWPART3, 1, ItemID.TWPART4, 1, ItemID.MCANNONBALL, 100);
		fireInv(ItemID.BH_RUNE_POUCH, 1, ItemID.MCANNONBALL, 100); // deploy
		Assert.assertTrue(plugin.cannonTracker.isDeployed());

		fireInv(ItemID.BH_RUNE_POUCH, 1); // load the 100 balls
		Assert.assertEquals(100L * 150L, plugin.session.getTotalExpenses());

		fireInv(ItemID.BH_RUNE_POUCH, 1, ItemID.TWPART1, 1, ItemID.TWPART2, 1,
			ItemID.TWPART3, 1, ItemID.TWPART4, 1, ItemID.MCANNONBALL, 60);

		Assert.assertEquals(0L, plugin.session.getGrossProfit());
		Assert.assertEquals("Returned balls reverse the load expense",
			40L * 150L, plugin.session.getTotalExpenses());
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
