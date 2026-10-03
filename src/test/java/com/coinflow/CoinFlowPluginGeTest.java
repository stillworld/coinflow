package com.coinflow;

import java.util.HashSet;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end tests for Grand Exchange handling in {@link CoinFlowPlugin}:
 * offer events settled against carried values (GE basis, tracked gains,
 * gross fill price), while collection inventory diffs stay suppressed.
 *
 * All {@code spent} values are gross (pre-tax). Tax: 2% per item, floored.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class CoinFlowPluginGeTest
{
	private static final int HELM = 11497; // Dragon med helm
	private static final int SHARK = 385;  // Shark
	private static final long HELM_TAX = 1180L;

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
		plugin.goalCompletedNotified = false;
		plugin.getOpenSuppressedInterfaces().clear();
		plugin.recentlyUnequippedItems.clear();
		plugin.recentlyDroppedItems.clear();
		plugin.recentlyDroppedOwnedItems.clear();
		plugin.ignoredItemNames = new HashSet<>();
		plugin.grandExchangeTracker.reset();

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
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		stubTrackableItem(ItemID.COINS, "Coins", 1L);
	}

	// ── Helpers ──────────────────────────────────────────────────────────

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

	private static GrandExchangeOffer offer(GrandExchangeOfferState state, int itemId,
		int quantitySold, int totalQuantity, long price, long spent)
	{
		GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(state);
		when(offer.getItemId()).thenReturn(itemId);
		when(offer.getQuantitySold()).thenReturn(quantitySold);
		when(offer.getTotalQuantity()).thenReturn(totalQuantity);
		when(offer.getPrice()).thenReturn(price);
		when(offer.getSpent()).thenReturn(spent);
		return offer;
	}

	private static GrandExchangeOffer empty()
	{
		return offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
	}

	private void offerChanged(int slot, GrandExchangeOffer offer)
	{
		GrandExchangeOfferChanged event = new GrandExchangeOfferChanged();
		event.setSlot(slot);
		event.setOffer(offer);
		plugin.onGrandExchangeOfferChanged(event);
	}

	// ── Sell scenarios ───────────────────────────────────────────────────

	@Test
	public void sellBankedItem_recordsOnlyGeTax()
	{
		stubTrackableItem(HELM, "Dragon med helm", 59000L);

		offerChanged(0, offer(GrandExchangeOfferState.SELLING, HELM, 0, 1, 59000, 0));
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, HELM, 1, 1, 59000, 59000));

		Assert.assertEquals(0L, plugin.getSession().getGrossProfit());
		Assert.assertEquals(HELM_TAX, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(-HELM_TAX, plugin.getSession().getTotalProfit());
		verify(goldDropOverlay, never()).addDrop(anyString(), anyInt(), anyInt());
	}

	@Test
	public void sellLootedItem_correctsEstimateByTax()
	{
		stubTrackableItem(HELM, "Dragon med helm", 59000L);

		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV,
			mockContainer(InventoryID.INV)));
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV,
			mockContainer(InventoryID.INV, HELM, 1)));
		Assert.assertEquals(59000L, plugin.getSession().getTotalProfit());

		offerChanged(0, offer(GrandExchangeOfferState.SELLING, HELM, 0, 1, 59000, 0));
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, HELM, 1, 1, 59000, 59000));

		Assert.assertEquals(59000L - HELM_TAX, plugin.getSession().getTotalProfit());
		Assert.assertNull(plugin.getSession().getTrackedItems().get(HELM));
	}

	@Test
	public void buyThenSell_flip_recordsRealMargin()
	{
		stubTrackableItem(SHARK, "Shark", 1000L);

		// Buy 10 sharks at 1,000 each
		offerChanged(0, offer(GrandExchangeOfferState.BUYING, SHARK, 0, 10, 1000, 0));
		offerChanged(0, offer(GrandExchangeOfferState.BOUGHT, SHARK, 10, 10, 1000, 10000));

		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(10, plugin.grandExchangeTracker.basisQuantity(SHARK));

		// Sell 10 at 1,100: gross 11,000, tax 22 each -> net 10,780 -> margin +780
		offerChanged(1, offer(GrandExchangeOfferState.SELLING, SHARK, 0, 10, 1100, 0));
		offerChanged(1, offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1100, 11000));

		Assert.assertEquals(780L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0, plugin.grandExchangeTracker.basisQuantity(SHARK));

		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(goldDropOverlay).addDrop(text.capture(), eq(SHARK), anyInt());
		Assert.assertTrue(text.getValue().startsWith("+"));
	}

	@Test
	public void buyThenSell_flipAtLoss_recordsLoss()
	{
		stubTrackableItem(SHARK, "Shark", 1000L);

		offerChanged(0, offer(GrandExchangeOfferState.BUYING, SHARK, 0, 10, 1100, 0));
		offerChanged(0, offer(GrandExchangeOfferState.BOUGHT, SHARK, 10, 10, 1100, 11000));

		// Sell at 1,000: gross 10,000, tax 200 -> net 9,800 -> margin -1,200
		offerChanged(1, offer(GrandExchangeOfferState.SELLING, SHARK, 0, 10, 1000, 0));
		offerChanged(1, offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000));

		Assert.assertEquals(-1200L, plugin.getSession().getTotalProfit());
		verify(goldDropOverlay, never()).addDrop(anyString(), anyInt(), anyInt());
	}

	@Test
	public void partialFills_acrossTicks_accumulate()
	{
		stubTrackableItem(SHARK, "Shark", 1000L);

		offerChanged(0, offer(GrandExchangeOfferState.SELLING, SHARK, 0, 10, 1000, 0));
		offerChanged(0, offer(GrandExchangeOfferState.SELLING, SHARK, 3, 10, 1000, 3000));
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000));

		Assert.assertEquals(-200L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void cancelledOffer_recordsFilledPortionOnly()
	{
		stubTrackableItem(SHARK, "Shark", 1000L);

		offerChanged(0, offer(GrandExchangeOfferState.SELLING, SHARK, 0, 10, 1000, 0));
		offerChanged(0, offer(GrandExchangeOfferState.SELLING, SHARK, 4, 10, 1000, 4000));
		offerChanged(0, offer(GrandExchangeOfferState.CANCELLED_SELL, SHARK, 4, 10, 1000, 4000));

		Assert.assertEquals(-80L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void offerCompletedBeforePluginObservation_notCounted()
	{
		stubTrackableItem(HELM, "Dragon med helm", 59000L);

		offerChanged(0, offer(GrandExchangeOfferState.SOLD, HELM, 1, 1, 59000, 59000));

		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void offerFilledWhileLoggedOut_deltasAgainstPreLogoutState()
	{
		stubTrackableItem(SHARK, "Shark", 1000L);

		offerChanged(0, offer(GrandExchangeOfferState.SELLING, SHARK, 0, 10, 1000, 0));
		offerChanged(0, offer(GrandExchangeOfferState.SELLING, SHARK, 3, 10, 1000, 3000));
		Assert.assertEquals(-60L, plugin.getSession().getTotalProfit());

		// Relogin: EMPTY flood arrives while LOGGING_IN, then the completed offer
		when(client.getGameState()).thenReturn(GameState.LOGGING_IN);
		offerChanged(0, empty());
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000));

		Assert.assertEquals(-200L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void collectedSlot_emptyWhileLoggedIn_nextOfferBaselines()
	{
		stubTrackableItem(SHARK, "Shark", 1000L);

		offerChanged(0, offer(GrandExchangeOfferState.SELLING, SHARK, 0, 10, 1000, 0));
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000));
		Assert.assertEquals(-200L, plugin.getSession().getTotalProfit());

		// Collect clears the slot while logged in
		offerChanged(0, empty());

		// A new offer whose first observation already has fills must baseline, not delta
		offerChanged(0, offer(GrandExchangeOfferState.SELLING, SHARK, 15, 20, 1000, 15000));
		Assert.assertEquals(-200L, plugin.getSession().getTotalProfit());

		// Subsequent fills on the new offer do count
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, SHARK, 20, 20, 1000, 20000));
		Assert.assertEquals(-300L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void collectionRemainsSuppressed_noDoubleCount()
	{
		stubTrackableItem(HELM, "Dragon med helm", 59000L);

		offerChanged(0, offer(GrandExchangeOfferState.SELLING, HELM, 0, 1, 59000, 0));
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, HELM, 1, 1, 59000, 59000));
		Assert.assertEquals(-HELM_TAX, plugin.getSession().getTotalProfit());

		plugin.snapshotInitialized = true;
		plugin.previousInventorySnapshot = TestHelpers.snapshot();
		MenuEntry collectEntry = mock(MenuEntry.class);
		when(collectEntry.getOption()).thenReturn("Collect");
		when(collectEntry.getTarget()).thenReturn("<col=ffff00>Grand Exchange Clerk</col>");
		plugin.onMenuOptionClicked(new MenuOptionClicked(collectEntry));

		int net = (int) (59000 - HELM_TAX);
		plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV,
			mockContainer(InventoryID.INV, ItemID.COINS, net)));

		Assert.assertEquals(-HELM_TAX, plugin.getSession().getTotalProfit());
		Assert.assertEquals(Integer.valueOf(net),
			plugin.previousInventorySnapshot.getItems().get(ItemID.COINS));
	}

	@Test
	public void trackSpentDisabled_taxExpenseNotRecorded()
	{
		when(config.trackSpent()).thenReturn(false);
		stubTrackableItem(HELM, "Dragon med helm", 59000L);

		offerChanged(0, offer(GrandExchangeOfferState.SELLING, HELM, 0, 1, 59000, 0));
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, HELM, 1, 1, 59000, 59000));

		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void sellWhileGeInterfaceOpen_stillSettles()
	{
		stubTrackableItem(HELM, "Dragon med helm", 59000L);
		plugin.setTrackingSuppressed(true);

		offerChanged(0, offer(GrandExchangeOfferState.SELLING, HELM, 0, 1, 59000, 0));
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, HELM, 1, 1, 59000, 59000));

		Assert.assertEquals(-HELM_TAX, plugin.getSession().getTotalProfit());
	}

	@Test
	public void notedItemIdSell_canonicalizesToBaseItem()
	{
		int notedHelm = 11498;
		stubTrackableItem(HELM, "Dragon med helm", 59000L);
		when(itemManager.canonicalize(notedHelm)).thenReturn(HELM);

		plugin.grandExchangeTracker.addBasis(HELM, 1, 57000);

		offerChanged(0, offer(GrandExchangeOfferState.SELLING, notedHelm, 0, 1, 59000, 0));
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, notedHelm, 1, 1, 59000, 59000));

		// 59,000 - 1,180 - 57,000 = +820
		Assert.assertEquals(820L, plugin.getSession().getTotalProfit());
	}

	@Test
	public void taxExemptItem_sellRecordsNothing()
	{
		stubTrackableItem(ItemID.LOBSTER, "Lobster", 200L);

		offerChanged(0, offer(GrandExchangeOfferState.SELLING, ItemID.LOBSTER, 0, 100, 200, 0));
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, ItemID.LOBSTER, 100, 100, 200, 20000));

		Assert.assertEquals(0L, plugin.getSession().getTotalProfit());
		Assert.assertEquals(0L, plugin.getSession().getTotalExpenses());
	}

	@Test
	public void resetSession_clearsBasisAndReseedsFromClientOffers()
	{
		stubTrackableItem(SHARK, "Shark", 1000L);
		plugin.grandExchangeTracker.addBasis(SHARK, 10, 10000);

		GrandExchangeOffer inProgress = offer(GrandExchangeOfferState.SELLING, SHARK, 3, 10, 1000, 3000);
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[]{inProgress});

		plugin.resetSession();

		Assert.assertEquals(0, plugin.grandExchangeTracker.basisQuantity(SHARK));

		// The in-progress offer was seeded, so the next fill deltas rather than baselining
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000));
		Assert.assertEquals(-140L, plugin.getSession().getTotalProfit()); // 7 * 20 tax
	}

	@Test
	public void nullSession_ignoresEvents()
	{
		plugin.session = null;
		offerChanged(0, offer(GrandExchangeOfferState.SELLING, SHARK, 0, 10, 1000, 0));
		offerChanged(0, offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000));
		Assert.assertNull(plugin.getSession());
	}
}
