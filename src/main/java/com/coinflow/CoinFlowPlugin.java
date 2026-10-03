package com.coinflow;

import com.coinflow.reconciliation.InventoryReconciliationEngine;
import com.coinflow.reconciliation.ProcessingPatternRegistry;
import com.coinflow.reconciliation.ReconciliationContext;
import com.google.inject.Injector;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Coin Flow",
	description = "Real-time GP/hr and income tracker",
	tags = {"coin", "flow", "gp", "gold", "money", "profit", "rate", "income", "tracker"},
	internalName = "coinflow"
)
public class CoinFlowPlugin extends Plugin
{
	@Inject
	Client client;

	@Inject
	ClientThread clientThread;

	@Inject
	CoinFlowConfig config;

	@Inject
	ItemManager itemManager;

	@Inject
	ConfigManager configManager;

	@Inject
	OverlayManager overlayManager;

	@Inject
	CoinFlowOverlay overlay;

	@Inject
	CoinFlowGoldDropOverlay goldDropOverlay;

	@Inject
	Notifier notifier;

	@Inject
	ClientToolbar clientToolbar;

	@Inject
	Injector injector;

	@Inject
	EventBus eventBus;

	@Inject
	InventorySnapshotService snapshotService;

	@Inject
	InterfaceTracker interfaceTracker = new InterfaceTracker();

	@Inject
	InventoryReconciliationEngine reconciliationEngine = new InventoryReconciliationEngine();

	NavigationButton navButton;
	CoinFlowPanel panel;

	/**
	 * Latch to prevent goal notification from spamming every tick.
	 */
	boolean goalCompletedNotified;

	/**
	 * Current session snapshot. Published via volatile for thread-safe reads
	 * from the overlay (EDT) while being written to on the game thread.
	 * (Fixes Bug 8: thread safety)
	 */
	@Getter
	volatile CoinFlowSession session;

	/**
	 * The last known inventory state. Used for diffing.
	 */
	InventorySnapshot previousInventorySnapshot;

	/**
	 * The last known equipment state. Used to detect equipment swaps.
	 * (Fixes Bug 5: equipment swap contamination)
	 */
	InventorySnapshot previousEquipmentSnapshot;

	/**
	 * Whether we have taken an initial baseline snapshot.
	 * Prevents counting the entire inventory as profit on first tick.
	 * (Fixes Bug 1: login snapshot ghost gains)
	 */
	boolean snapshotInitialized;

	/**
	 * Whether container 516 (Looting Bag) has been baselined for this login session.
	 * Prevents pre-existing items inside the bag from counting as profit upon first "Check".
	 */
	boolean lootingBagInitialized;

	/**
	 * Track ammo and thrown weapons removed from WORN that were not unequipped to INV.
	 */
	final Map<Integer, Integer> pendingWornAmmoExpenses = new HashMap<>();

	/**
	 * Tracks resources consumed inside charged weapons (runes, scales, shards)
	 * via CHARGES_*_QUANTITY varbits, which never produce an inventory diff.
	 */
	final WeaponChargeTracker weaponChargeTracker = new WeaponChargeTracker();

	/**
	 * Number of game ticks remaining to suppress diffing for in-flight items
	 * arriving immediately upon or after interface closure (e.g. bank withdrawals).
	 */
	int rebaselineGraceTicks;

	boolean isTrackingSuppressed()
	{
		return interfaceTracker.isTrackingSuppressed();
	}

	void setTrackingSuppressed(boolean suppressed)
	{
		interfaceTracker.setTrackingSuppressed(suppressed);
	}

	boolean isNeedsRebaseline()
	{
		return interfaceTracker.isNeedsRebaseline();
	}

	void setNeedsRebaseline(boolean needs)
	{
		interfaceTracker.setNeedsRebaseline(needs);
	}

	Set<Integer> getOpenSuppressedInterfaces()
	{
		return interfaceTracker.getOpenSuppressedInterfaces();
	}

	GameState getPreviousGameState()
	{
		return interfaceTracker.getPreviousGameState();
	}

	void setPreviousGameState(GameState state)
	{
		interfaceTracker.setPreviousGameState(state);
	}

	/**
	 * Parsed set of ignored item names (lowercase) from config.
	 */
	Set<String> ignoredItemNames;

	/**
	 * Items removed from equipment on recent ticks to subtract from inventory gains.
	 */
	final Map<Integer, Integer> recentlyUnequippedItems = new HashMap<>();

	/**
	 * Items unequipped and matched in inventory on this tick when INV dispatched before WORN.
	 */
	final Map<Integer, Integer> matchedUnequipsThisTick = new HashMap<>();

	/**
	 * Items added to equipment on recent ticks to subtract from inventory losses.
	 */
	final Map<Integer, Integer> recentlyEquippedItems = new HashMap<>();

	/**
	 * Non-consumable items dropped from inventory on recent ticks that were gained in this session.
	 * In Gross Mode: prevents picking up dropped items from duplicating gains.
	 * In Net Mode: tracks dropped item deductions and restores on pickup.
	 */
	final Map<Integer, Integer> recentlyDroppedItems = new HashMap<>();

	/**
	 * Non-consumable items dropped from inventory that were NOT gained in this session (e.g. from bank/gear).
	 * Prevents picking them back up from counting as false profit loot (e.g. Cooking cape, Spade).
	 */
	final Map<Integer, Integer> recentlyDroppedOwnedItems = new HashMap<>();

	/**
	 * Game tick when each item was dropped, used to expire stale drop records.
	 */
	final Map<Integer, Integer> droppedItemTicks = new HashMap<>();

	/**
	 * Game tick when each gear-swap item was last seen in a WORN diff, used to
	 * expire stale unequipped/equipped records that never found their inventory
	 * counterpart (e.g. ammo picked up directly into the ammo slot).
	 */
	final Map<Integer, Integer> gearSwapItemTicks = new HashMap<>();

	/**
	 * Skilling action sink detection across ticks.
	 */
	final Map<Skill, Integer> lastSkillXpTicks = new HashMap<>();
	final Map<Skill, Integer> previousSkillXp = new HashMap<>();
	int lastFiremakingAnimTick = -100;
	int lastTinderboxActionTick = -100;
	int lastFiremakingChatTick = -100;
	int lastAlchAnimTick = -100;
	int lastAlchActionTick = -100;
	int lastFarmingActionTick = -100;
	int lastFarmingAnimTick = -100;
	int lastFarmingChatTick = -100;
	int lastLootingBagDepositTick = -100;
	int lastLootingBagDepositItemId = -1;
	String lastLootingBagDepositItemName = null;
	int lastNotingServiceTick = -100;

	private static class TakeClick
	{
		final int itemId;
		final WorldPoint point;
		final int tick;

		TakeClick(int itemId, WorldPoint point, int tick)
		{
			this.itemId = itemId;
			this.point = point;
			this.tick = tick;
		}
	}

	static class LootPickup
	{
		final int itemId;
		final int qty;

		LootPickup(int itemId, int qty)
		{
			this.itemId = itemId;
			this.qty = qty;
		}
	}

	final List<TakeClick> recentTakeClicks = new ArrayList<>();
	final List<LootPickup> pendingLootingBagPickups = new ArrayList<>();
	final List<LootPickup> pendingGemBagPickups = new ArrayList<>();
	final List<LootPickup> pendingHerbSackPickups = new ArrayList<>();
	final List<LootPickup> pendingFishBarrelPickups = new ArrayList<>();
	final List<LootPickup> pendingSeedBoxPickups = new ArrayList<>();
	final List<LootPickup> pendingLogBasketPickups = new ArrayList<>();
	final Map<Integer, Integer> invItemsGainedThisTick = new HashMap<>();
	final Map<Integer, Map<Integer, Integer>> previousPvpKeyContainers = new HashMap<>();

	/**
	 * Tracked varbits that threw on getVarbitValue (not varp-backed in this client
	 * build); logged once per ID to avoid spam.
	 */
	final Set<Integer> invalidVarbitIds = new HashSet<>();
	int lastSkillingGemId = -1;
	int lastSkillingGemTick = -100;
	private WorldPoint lastPlayerLocation;
	int lastPlayerActivityTick = -100;

	private static final int[] PVP_LOOT_KEY_CONTAINERS = {
		InventoryID.DEADMAN_LOOT_INV0,
		InventoryID.DEADMAN_LOOT_INV1,
		InventoryID.DEADMAN_LOOT_INV2,
		InventoryID.DEADMAN_LOOT_INV3,
		InventoryID.DEADMAN_LOOT_INV4
	};

	private static final Pattern FISHING_CATCH_REGEX = Pattern.compile(
		"(?:You catch (?:a|an|some|\\d+)\\s+|Your cormorant returns with its catch|You catch .*Karambwanji)",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern HERBIBOAR_HERB_SACK_PATTERN = Pattern.compile(
		".+(Grimy .+?) herb.+",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern WOODCUTTING_CHOP_REGEX = Pattern.compile(
		"^(?:You get (?:a|an|some|\\d+)\\s+|You cut (?:a|an|some|\\d+)\\s+).*(?:logs?|bark)",
		Pattern.CASE_INSENSITIVE
	);

	// ── Interface IDs that suppress tracking ─────────────────────────────
	// Maintained via InterfaceTracker; aliases preserved for backward compatibility
	public static final Set<Integer> SUPPRESSED_INTERFACES = InterfaceTracker.SUPPRESSED_INTERFACES;
	public static final Set<Integer> SIDE_INTERFACES = InterfaceTracker.SIDE_INTERFACES;

	// ── Lifecycle ────────────────────────────────────────────────────────

	@Override
	protected void startUp()
	{
		log.info("Coin Flow {} started", Version.getFormattedVersion());
		session = CoinFlowSession.createNew();
		previousInventorySnapshot = null;
		previousEquipmentSnapshot = null;
		snapshotInitialized = false;
		lootingBagInitialized = false;
		interfaceTracker.reset(client != null && client.getGameState() != null ? client.getGameState() : GameState.UNKNOWN);
		goalCompletedNotified = false;
		resetTransientTrackingState();
		rebuildFilterSet();
		overlayManager.add(overlay);
		overlayManager.add(goldDropOverlay);

		panel = injector.getInstance(CoinFlowPanel.class);
		panel.init();

		BufferedImage icon = ImageUtil.loadImageResource(CoinFlowPlugin.class, "coinflow_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Coin Flow")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		// If we're already logged in (plugin enabled mid-session), take baseline now
		if (client != null && client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::takeBaseline);
		}
	}

	@Override
	protected void shutDown()
	{
		log.info("Coin Flow stopped");
		overlayManager.remove(overlay);
		overlayManager.remove(goldDropOverlay);
		goldDropOverlay.clear();
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
		session = null;
		previousInventorySnapshot = null;
		previousEquipmentSnapshot = null;
		snapshotInitialized = false;
		lootingBagInitialized = false;
		resetTransientTrackingState();
		interfaceTracker.reset(GameState.UNKNOWN);
	}

	@Provides
	CoinFlowConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CoinFlowConfig.class);
	}

	// ── Event Handlers ───────────────────────────────────────────────────

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		interfaceTracker.onGameStateChanged(state, snapshotInitialized);

		if (state == GameState.LOGGED_IN)
		{
			if (session != null)
			{
				session = session.withResumedState();
			}
			if (interfaceTracker.isNeedsRebaseline() && client != null && clientThread != null)
			{
				clientThread.invokeLater(() ->
				{
					takeBaseline();
					interfaceTracker.setNeedsRebaseline(false);
				});
			}
		}
		else if (state != GameState.LOADING)
		{
			snapshotInitialized = false;
			lootingBagInitialized = false;
			resetTransientTrackingState();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (client != null && event.getActor() == client.getLocalPlayer())
		{
			interfaceTracker.onActorDeath();
			lootingBagInitialized = false;
			resetTransientTrackingState();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int containerId = event.getContainerId();

		if (containerId == InventoryID.INV
			|| containerId == net.runelite.api.gameval.InventoryID.LOOTING_BAG)
		{
			recordPlayerActivity();
		}

		if (isPvpKeyContainer(containerId))
		{
			checkPvpKeyContainers();
			return;
		}

		// ── Handle Equipment changes (detect gear unequipped into inventory) ──
		if (containerId == InventoryID.WORN)
		{
			ItemContainer equipContainer = event.getItemContainer();
			InventorySnapshot currentEquip = takeSnapshot(equipContainer);

			if (interfaceTracker.isTrackingSuppressed() || isBankOrContainerOpen() || interfaceTracker.isNeedsRebaseline() || rebaselineGraceTicks > 0)
			{
				previousEquipmentSnapshot = currentEquip;
				pendingWornAmmoExpenses.clear();
				weaponChargeTracker.reset();
				recentlyUnequippedItems.clear();
				recentlyEquippedItems.clear();
				gearSwapItemTicks.clear();
				return;
			}

			if (previousEquipmentSnapshot != null)
			{
				Map<Integer, Integer> removedFromEquip = previousEquipmentSnapshot.getGainedItems(currentEquip);
				Map<Integer, Integer> addedToEquip = currentEquip.getGainedItems(previousEquipmentSnapshot);

				// Reconcile items that degraded directly in equipment (e.g. worn Slayer ring (3) -> (2), Barrows degradation)
				// These did not move to or from inventory.
				if (!removedFromEquip.isEmpty() && !addedToEquip.isEmpty())
				{
					Iterator<Map.Entry<Integer, Integer>> removedIt = removedFromEquip.entrySet().iterator();
					while (removedIt.hasNext())
					{
						Map.Entry<Integer, Integer> removedEntry = removedIt.next();
						int removedId = itemManager != null ? itemManager.canonicalize(removedEntry.getKey()) : removedEntry.getKey();
						String removedName = getItemName(removedId);

						Iterator<Map.Entry<Integer, Integer>> addedIt = addedToEquip.entrySet().iterator();
						while (addedIt.hasNext())
						{
							Map.Entry<Integer, Integer> addedEntry = addedIt.next();
							int addedId = itemManager != null ? itemManager.canonicalize(addedEntry.getKey()) : addedEntry.getKey();
							String addedName = getItemName(addedId);

							if (com.coinflow.reconciliation.ChargeDegradationHandler.isChargeDegradationPair(removedName, addedName))
							{
								int match = Math.min(removedEntry.getValue(), addedEntry.getValue());
								if (addedEntry.getValue() <= match)
								{
									addedIt.remove();
								}
								else
								{
									addedEntry.setValue(addedEntry.getValue() - match);
								}

								if (removedEntry.getValue() <= match)
								{
									removedIt.remove();
									break;
								}
								else
								{
									removedEntry.setValue(removedEntry.getValue() - match);
								}
							}
						}
					}
				}

				for (Map.Entry<Integer, Integer> entry : removedFromEquip.entrySet())
				{
					int rawId = entry.getKey();
					int qty = entry.getValue();
					int canonicalId = itemManager != null ? itemManager.canonicalize(rawId) : rawId;
					String name = getItemName(canonicalId);

					int effectiveQty = qty;
					Integer alreadyMatched = matchedUnequipsThisTick.get(canonicalId);
					if (alreadyMatched == null && canonicalId != rawId)
					{
						alreadyMatched = matchedUnequipsThisTick.get(rawId);
					}
					if (alreadyMatched != null)
					{
						int deduction = Math.min(effectiveQty, alreadyMatched);
						effectiveQty -= deduction;
						int remMatched = alreadyMatched - deduction;
						if (remMatched <= 0)
						{
							matchedUnequipsThisTick.remove(canonicalId);
							matchedUnequipsThisTick.remove(rawId);
						}
						else
						{
							matchedUnequipsThisTick.put(canonicalId, remMatched);
						}
					}

					if (effectiveQty > 0)
					{
						if (ConsumableRegistry.isAmmo(name))
						{
							pendingWornAmmoExpenses.merge(canonicalId, effectiveQty, Integer::sum);
						}
						recentlyUnequippedItems.merge(entry.getKey(), effectiveQty, Integer::sum);
						gearSwapItemTicks.put(entry.getKey(), client != null ? client.getTickCount() : 0);
					}
				}

				for (Map.Entry<Integer, Integer> entry : addedToEquip.entrySet())
				{
					recentlyEquippedItems.merge(entry.getKey(), entry.getValue(), Integer::sum);
					gearSwapItemTicks.put(entry.getKey(), client != null ? client.getTickCount() : 0);
				}
			}
			previousEquipmentSnapshot = currentEquip;
			return;
		}

		// ── Handle Dizana's Quiver changes ───────────────────────────────
		if (containerId == InventoryID.DIZANAS_QUIVER_AMMO)
		{
			if (client != null)
			{
				ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
				if (invContainer != null)
				{
					ItemContainer quiverContainer = event.getItemContainer();
					Map<Integer, Integer> items = new HashMap<>();
					if (quiverContainer != null && quiverContainer.getItems() != null)
					{
						for (Item item : quiverContainer.getItems())
						{
							if (item != null && item.getId() > 0 && item.getQuantity() > 0)
							{
								items.merge(item.getId(), item.getQuantity(), Integer::sum);
							}
						}
					}
					getSnapshotService().setQuiverAmmoContents(items);
					processInventoryChanges(invContainer);
				}
			}
			return;
		}

		// ── Handle Inventory changes ─────────────────────────────────────
		if (containerId == InventoryID.INV)
		{
			ItemContainer currentInv = event.getItemContainer();
			if (previousInventorySnapshot != null && currentInv != null)
			{
				InventorySnapshot currentSnap = takeSnapshot(currentInv);
				Map<Integer, Integer> gained = currentSnap.getGainedItems(previousInventorySnapshot);
				for (Map.Entry<Integer, Integer> entry : gained.entrySet())
				{
					invItemsGainedThisTick.merge(entry.getKey(), entry.getValue(), Integer::sum);
				}
			}
			processInventoryChanges(event.getItemContainer());
			return;
		}

		// ── Handle Looting Bag changes ───────────────────────────────────
		if (containerId == net.runelite.api.gameval.InventoryID.LOOTING_BAG)
		{
			if (client != null)
			{
				ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
				if (invContainer != null && hasLootingBag(invContainer))
				{
					ItemContainer bagContainer = event.getItemContainer();
					Map<Integer, Integer> bagItems = new HashMap<>();
					if (bagContainer != null && bagContainer.getItems() != null)
					{
						for (Item item : bagContainer.getItems())
						{
							if (item != null && item.getId() > 0 && item.getQuantity() > 0)
							{
								bagItems.merge(item.getId(), item.getQuantity(), Integer::sum);
							}
						}
					}

					if (!lootingBagInitialized)
					{
						// Baseline initialization for container 516:
						// Calculate pre-existing bag items (items already inside bag before login or first check)
						Map<Integer, Integer> currentlyCached = getSnapshotService().getLootingBagContents();
						Map<Integer, Integer> preExistingItems = new HashMap<>();
						for (Map.Entry<Integer, Integer> entry : bagItems.entrySet())
						{
							int itemId = entry.getKey();
							int bagQty = entry.getValue();
							int cachedQty = currentlyCached.getOrDefault(itemId, 0);
							int preExistingQty = bagQty - cachedQty;
							if (preExistingQty > 0)
							{
								preExistingItems.put(itemId, preExistingQty);
							}
						}

						if (previousInventorySnapshot != null && !preExistingItems.isEmpty())
						{
							Map<Integer, Integer> merged = new HashMap<>(previousInventorySnapshot.getItems());
							for (Map.Entry<Integer, Integer> entry : preExistingItems.entrySet())
							{
								merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
							}
							previousInventorySnapshot = InventorySnapshot.fromMap(merged);
						}

						lootingBagInitialized = true;
					}

					getSnapshotService().setLootingBagContents(bagItems);
					processInventoryChanges(invContainer);
				}
			}
			return;
		}
	}

	void processInventoryChanges(ItemContainer invContainer)
	{
		if (session == null)
		{
			return;
		}

		InventorySnapshot currentSnapshot = takeInventorySnapshot(invContainer);

		// If bank or trading interface is actively open, suppress tracking and update baseline
		if (interfaceTracker.isTrackingSuppressed() || isBankOrContainerOpen())
		{
			previousInventorySnapshot = currentSnapshot;
			return;
		}

		// If we need a re-baseline (login, interface close, in-flight withdrawal)
		if (interfaceTracker.isNeedsRebaseline() || rebaselineGraceTicks > 0 || !snapshotInitialized)
		{
			boolean isProcessing = false;
			if (previousInventorySnapshot != null && snapshotInitialized)
			{
				Map<Integer, Integer> rawGains = currentSnapshot.getGainedItems(previousInventorySnapshot);
				Map<Integer, Integer> rawLosses = currentSnapshot.getLostItems(previousInventorySnapshot);
				if (!rawLosses.isEmpty() && !rawGains.isEmpty())
				{
					for (Map.Entry<Integer, Integer> lostEntry : rawLosses.entrySet())
					{
						int lostId = itemManager != null ? itemManager.canonicalize(lostEntry.getKey()) : lostEntry.getKey();
						ItemComposition lostComp = itemManager != null ? itemManager.getItemComposition(lostId) : null;
						String lostName = lostComp != null ? lostComp.getName() : "";

						for (Map.Entry<Integer, Integer> gainedEntry : rawGains.entrySet())
						{
							int gainedId = itemManager != null ? itemManager.canonicalize(gainedEntry.getKey()) : gainedEntry.getKey();
							ItemComposition gainedComp = itemManager != null ? itemManager.getItemComposition(gainedId) : null;
							String gainedName = gainedComp != null ? gainedComp.getName() : "";

							if ((lostId == gainedId && !lostEntry.getKey().equals(gainedEntry.getKey()))
								|| ProcessingPatternRegistry.match(lostName, gainedName, gainedEntry.getValue()) != null)
							{
								isProcessing = true;
								break;
							}
						}
						if (isProcessing)
						{
							break;
						}
					}
				}
			}

			if (!isProcessing)
			{
				previousInventorySnapshot = currentSnapshot;
				snapshotInitialized = true;
				if (client != null && client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG) != null)
				{
					lootingBagInitialized = true;
				}
				rebaselineGraceTicks = 0;
				interfaceTracker.setNeedsRebaseline(false);
				log.debug("Baseline snapshot taken ({} items)", currentSnapshot.getItems().size());
				return;
			}
			else
			{
				rebaselineGraceTicks = 0;
				interfaceTracker.setNeedsRebaseline(false);
			}
		}

		// ── Diff and process gains & expenses ─────────────────────────────
		if (previousInventorySnapshot != null)
		{
			Map<Integer, Integer> rawGains = currentSnapshot.getGainedItems(previousInventorySnapshot);
			Map<Integer, Integer> rawLosses = currentSnapshot.getLostItems(previousInventorySnapshot);

			if (!rawLosses.isEmpty() && hasLootingBag(invContainer))
			{
				int currentTick = client != null ? client.getTickCount() : 0;
				boolean recentDepositIntent = (currentTick - lastLootingBagDepositTick <= 15);
				Widget lootingBagWidget = client != null ? client.getWidget(InterfaceID.WildernessLootingbag.FRAME) : null;
				boolean lootingBagInterfaceOpen = lootingBagWidget != null && !lootingBagWidget.isHidden();

				boolean anyDeposited = false;
				Iterator<Map.Entry<Integer, Integer>> it = rawLosses.entrySet().iterator();
				while (it.hasNext())
				{
					Map.Entry<Integer, Integer> entry = it.next();
					int lostId = entry.getKey();
					int lostQty = entry.getValue();
					int canonicalLostId = itemManager != null ? itemManager.canonicalize(lostId) : lostId;
					String lostName = itemManager != null && itemManager.getItemComposition(canonicalLostId) != null
						? itemManager.getItemComposition(canonicalLostId).getName().toLowerCase(Locale.ROOT)
						: "";

					// While the bag interface is open, only non-consumables are treated as
					// deposits — eating/drinking while "Check"-ing the bag must still be
					// expensed. Consumable deposits still match via the Store/Deposit
					// click intent below, which captures the item id/name.
					boolean isDepositMatch = lootingBagInterfaceOpen
						&& !ConsumableRegistry.isConsumable(canonicalLostId, lostName, itemManager);
					if (!isDepositMatch && recentDepositIntent)
					{
						if (lastLootingBagDepositItemId > 0)
						{
							int canonicalDepositId = itemManager != null ? itemManager.canonicalize(lastLootingBagDepositItemId) : lastLootingBagDepositItemId;
							if (canonicalDepositId == canonicalLostId)
							{
								isDepositMatch = true;
							}
						}
						if (!isDepositMatch && lastLootingBagDepositItemName != null && !lastLootingBagDepositItemName.isEmpty())
						{
							String lowerDepositName = lastLootingBagDepositItemName.toLowerCase(Locale.ROOT);
							if (lostName.contains(lowerDepositName) || lowerDepositName.contains(lostName))
							{
								isDepositMatch = true;
							}
						}
					}

					if (isDepositMatch)
					{
						getSnapshotService().addLootingBagPendingItem(lostId, lostQty);
						it.remove();
						anyDeposited = true;
						log.debug("Deposited item into looting bag: {} x{} (id {})", lostName, lostQty, lostId);
					}
				}

				if (anyDeposited)
				{
					lastLootingBagDepositTick = -100;
					lastLootingBagDepositItemId = -1;
					lastLootingBagDepositItemName = null;
					currentSnapshot = takeInventorySnapshot(invContainer);
					rawGains = currentSnapshot.getGainedItems(previousInventorySnapshot);
					rawLosses = currentSnapshot.getLostItems(previousInventorySnapshot);
				}
			}

			ItemContainer currentEquipContainer = client != null ? client.getItemContainer(InventoryID.WORN) : null;
			ReconciliationContext context = new ReconciliationContext(
				rawGains,
				rawLosses,
				recentlyUnequippedItems,
				recentlyEquippedItems,
				recentlyDroppedItems,
				recentlyDroppedOwnedItems,
				previousEquipmentSnapshot,
				currentEquipContainer,
				session,
				itemManager,
				config,
				ignoredItemNames,
				pendingWornAmmoExpenses,
				matchedUnequipsThisTick
			);

			int currentTick = client != null ? client.getTickCount() : 0;
			if (client != null)
			{
				for (Map.Entry<Skill, Integer> entry : lastSkillXpTicks.entrySet())
				{
					int tick = entry.getValue();
					if (tick == currentTick || tick == currentTick - 1)
					{
						context.addActiveSkillingSkill(entry.getKey());
					}
				}

				boolean isFiremaking = lastTinderboxActionTick >= currentTick - 5 && lastTinderboxActionTick > 0
					|| lastFiremakingAnimTick >= currentTick - 3 && lastFiremakingAnimTick > 0
					|| lastFiremakingChatTick >= currentTick - 2 && lastFiremakingChatTick > 0;
				if (!isFiremaking && client.getLocalPlayer() != null)
				{
					int anim = client.getLocalPlayer().getAnimation();
					if (isFiremakingAnimation(anim))
					{
						isFiremaking = true;
						lastFiremakingAnimTick = currentTick;
					}
				}
				if (isFiremaking)
				{
					context.addActiveSkillingSkill(Skill.FIREMAKING);
				}

				boolean isFarming = (lastFarmingActionTick >= currentTick - 8 && lastFarmingActionTick > 0)
					|| (lastFarmingAnimTick >= currentTick - 2 && lastFarmingAnimTick > 0)
					|| (lastFarmingChatTick >= currentTick - 2 && lastFarmingChatTick > 0);
				if (!isFarming && client.getLocalPlayer() != null)
				{
					int anim = client.getLocalPlayer().getAnimation();
					if (isFarmingAnimation(anim))
					{
						isFarming = true;
						lastFarmingAnimTick = currentTick;
					}
				}
				if (isFarming)
				{
					context.addActiveSkillingSkill(Skill.FARMING);
				}

				boolean isAlchemy = (lastAlchActionTick >= currentTick - 3 && lastAlchActionTick > 0)
					|| (lastAlchAnimTick >= currentTick - 3 && lastAlchAnimTick > 0)
					|| (lastSkillXpTicks.containsKey(Skill.MAGIC) && lastSkillXpTicks.get(Skill.MAGIC) >= currentTick - 1);
				if (!isAlchemy && client.getLocalPlayer() != null)
				{
					int anim = client.getLocalPlayer().getAnimation();
					if (isAlchemyAnimation(anim))
					{
						isAlchemy = true;
						lastAlchAnimTick = currentTick;
					}
				}
				if (isAlchemy)
				{
					context.setAlchemy(true);
				}

				context.setNotingService(currentTick - lastNotingServiceTick <= 5);
			}

			reconciliationEngine.reconcile(context);

			// Drain any remaining consumed equipped ammo/thrown weapons into supply expenses
			if (!pendingWornAmmoExpenses.isEmpty())
			{
				for (Map.Entry<Integer, Integer> entry : pendingWornAmmoExpenses.entrySet())
				{
					int itemId = entry.getKey();
					int quantity = entry.getValue();
					String itemName = getItemName(itemId);
					long price = itemManager != null ? itemManager.getItemPrice(itemId) : 0;
					if (price <= 0 && itemManager != null)
					{
						ItemComposition comp = itemManager.getItemComposition(itemId);
						if (comp != null)
						{
							price = comp.getHaPrice();
						}
					}
					context.addSupplyExpense(itemId, new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price));
					log.debug("Consumed equipped ammo: {} x{} @ {} gp = {} gp", itemName, quantity, price, (long) quantity * price);
					removeRecentlyUnequipped(itemId, quantity);
				}
				pendingWornAmmoExpenses.clear();
			}

			if (currentTick > 0)
			{
				for (int id : context.getRecentlyDroppedItems().keySet())
				{
					droppedItemTicks.put(id, currentTick);
				}
				for (int id : context.getRecentlyDroppedOwnedItems().keySet())
				{
					droppedItemTicks.put(id, currentTick);
				}
			}

			// Pop gold drop for the positive profit margin (if profitable and meets threshold)
			if (context.getAlchedTotalMargin() > 0 && config.showGoldDrops() && goldDropOverlay != null
				&& context.getAlchedTotalMargin() >= config.goldDropMinThreshold())
			{
				String dropText = "+" + QuantityFormatter.quantityToStackSize(context.getAlchedTotalMargin()) + " gp";
				goldDropOverlay.addDrop(dropText, context.getAlchedItemRawId(), 0);
			}

			if (!context.getRawGains().isEmpty() || !context.getDroppedGainsDeductions().isEmpty() || !context.getSupplyExpenses().isEmpty())
			{
				processGainsLossesAndExpenses(
					context.getRawGains(),
					context.getDroppedGainsDeductions(),
					context.getSupplyExpenses(),
					context.getAlchedDropItemId(),
					context.getExemptProductIds()
				);

				if (context.getActiveSkillingSkills().contains(Skill.FIREMAKING))
				{
					lastTinderboxActionTick = -100;
				}
				if (context.getActiveSkillingSkills().contains(Skill.FARMING))
				{
					lastFarmingActionTick = -100;
					lastFarmingAnimTick = -100;
					lastFarmingChatTick = -100;
				}
			}
		}

		previousInventorySnapshot = currentSnapshot;
	}


	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		int varbitId = event.getVarbitId();
		if (isRunePouchVarbit(varbitId))
		{
			ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
			if (invContainer != null && hasRunePouch(invContainer))
			{
				processInventoryChanges(invContainer);
			}
		}
		else if (isMasterScrollBookVarbit(varbitId))
		{
			ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
			if (invContainer != null && hasMasterScrollBook(invContainer))
			{
				processInventoryChanges(invContainer);
			}
		}
		else if (varbitId == VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY)
		{
			ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
			if (invContainer != null && hasAshSanctifier(invContainer))
			{
				processInventoryChanges(invContainer);
			}
		}

		if (varbitId >= 0 && WeaponChargeTracker.isTrackedVarbit(varbitId))
		{
			int value;
			try
			{
				value = client.getVarbitValue(varbitId);
			}
			catch (IndexOutOfBoundsException e)
			{
				// Some tracked CHARGES_* varbits are not varp-backed / not present in this
				// client build; getVarbitValue throws for them. Log once per varbit ID.
				if (invalidVarbitIds.add(varbitId))
				{
					log.debug("Ignoring untracked varbit {} (not varp-backed)", varbitId);
				}
				return;
			}
			boolean trackingAllowed = !interfaceTracker.isTrackingSuppressed() && !isBankOrContainerOpen()
				&& !interfaceTracker.isNeedsRebaseline() && rebaselineGraceTicks <= 0;
			weaponChargeTracker.onVarbitChanged(varbitId, value, trackingAllowed,
				client.getTickCount());
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		boolean wasSuppressed = interfaceTracker.isTrackingSuppressed();
		interfaceTracker.onWidgetLoaded(event.getGroupId());

		if (event.getGroupId() == InterfaceID.WILDY_LOOT_CHEST || event.getGroupId() == InterfaceID.DEADMANLOOT)
		{
			checkPvpKeyContainers();
		}

		if (interfaceTracker.isTrackingSuppressed())
		{
			pendingWornAmmoExpenses.clear();
			weaponChargeTracker.reset();
			recentlyUnequippedItems.clear();
			recentlyEquippedItems.clear();
			gearSwapItemTicks.clear();
		}

		if (wasSuppressed && !interfaceTracker.isTrackingSuppressed() && client != null && snapshotInitialized)
		{
			ItemContainer inv = client.getItemContainer(InventoryID.INV);
			if (inv != null)
			{
				previousInventorySnapshot = takeInventorySnapshot(inv);
			}
			ItemContainer worn = client.getItemContainer(InventoryID.WORN);
			if (worn != null)
			{
				previousEquipmentSnapshot = takeSnapshot(worn);
			}
			pendingWornAmmoExpenses.clear();
			weaponChargeTracker.reset();
			recentlyUnequippedItems.clear();
			recentlyEquippedItems.clear();
			gearSwapItemTicks.clear();
			rebaselineGraceTicks = 2;
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		boolean wasSuppressed = interfaceTracker.isTrackingSuppressed();
		interfaceTracker.onWidgetClosed(event.getGroupId());

		if (wasSuppressed && !interfaceTracker.isTrackingSuppressed() && client != null && snapshotInitialized)
		{
			ItemContainer inv = client.getItemContainer(InventoryID.INV);
			if (inv != null)
			{
				previousInventorySnapshot = takeInventorySnapshot(inv);
			}
			ItemContainer worn = client.getItemContainer(InventoryID.WORN);
			if (worn != null)
			{
				previousEquipmentSnapshot = takeSnapshot(worn);
			}
			pendingWornAmmoExpenses.clear();
			weaponChargeTracker.reset();
			recentlyUnequippedItems.clear();
			recentlyEquippedItems.clear();
			gearSwapItemTicks.clear();
			rebaselineGraceTicks = 2;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		int currentXp = event.getXp();
		Integer prevXp = previousSkillXp.get(skill);
		if (prevXp != null && currentXp > prevXp && client != null)
		{
			lastSkillXpTicks.put(skill, client.getTickCount());
			recordPlayerActivity();
		}
		previousSkillXp.put(skill, currentXp);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		recordPlayerActivity();
		String option = event.getMenuOption();
		if (option == null)
		{
			return;
		}
		String rawTarget = event.getMenuTarget();
		String target = rawTarget != null ? Text.removeTags(rawTarget).toLowerCase(Locale.ROOT) : "";
		int tick = client != null ? client.getTickCount() : 0;

		if ("Take".equalsIgnoreCase(option))
		{
			int itemId = event.getId();
			int sceneX = event.getParam0();
			int sceneY = event.getParam1();
			if (client != null && client.getLocalPlayer() != null)
			{
				try
				{
					WorldView worldView = client.getTopLevelWorldView();
					WorldPoint targetPoint = worldView != null
						? WorldPoint.fromScene(worldView, sceneX, sceneY, worldView.getPlane())
						: null;
					if (targetPoint != null)
					{
						recentTakeClicks.add(new TakeClick(itemId, targetPoint, tick));
					}
				}
				catch (Exception e)
				{
					log.debug("Failed to calculate WorldPoint from scene coords: {}", e.getMessage());
				}
			}
		}
		else if ("Use".equalsIgnoreCase(option))
		{
			String lowerTarget = target.toLowerCase(Locale.ROOT);
			if (lowerTarget.contains("looting bag"))
			{
				int usedId = -1;
				if (client != null && client.getSelectedWidget() != null)
				{
					usedId = client.getSelectedWidget().getItemId();
				}
				String usedName = "";
				if (target.contains("->"))
				{
					String beforeArrow = target.split("->")[0].trim();
					if (beforeArrow.startsWith("use "))
					{
						beforeArrow = beforeArrow.substring(4).trim();
					}
					usedName = beforeArrow;
				}
				lastLootingBagDepositTick = tick;
				lastLootingBagDepositItemId = usedId;
				lastLootingBagDepositItemName = usedName;
				log.debug("Looting bag deposit intent via Use: name '{}', id {}", usedName, usedId);
			}
			else if (isFarmingPatchAction(lowerTarget))
			{
				lastFarmingActionTick = tick;
				lastSkillXpTicks.put(Skill.FARMING, tick);
				log.debug("Farming patch action clicked ('{}' on '{}'), recording farming action tick", option, target);
			}
			else if (isBirdHouseAction(lowerTarget))
			{
				lastSkillXpTicks.put(Skill.HUNTER, tick);
				log.debug("Birdhouse action clicked ('{}' on '{}'), recording hunter action tick", option, target);
			}
			else if (target.contains("tinderbox") || target.contains("bruma torch"))
			{
				lastTinderboxActionTick = tick;
			}
			else if (lowerTarget.contains("leprechaun") || lowerTarget.contains("phials")
				|| lowerTarget.contains("piles"))
			{
				lastNotingServiceTick = tick;
			}
			else if (lowerTarget.contains("->"))
			{
				String[] parts = lowerTarget.split("->");
				String dest = parts.length > 1 ? parts[1].trim() : "";
				if (isContainerOrChargedItemTarget(dest) || (isContainerOrChargedItemTarget(parts[0].trim()) && (dest.contains("bank") || dest.contains("deposit"))))
				{
					weaponChargeTracker.recordUseSource(parts[0]);
					interfaceTracker.setNeedsRebaseline(true);
					rebaselineGraceTicks = 3;
					log.debug("Container/charged item Use action clicked ('{}'), scheduling rebaseline", target);
				}
			}
		}
		else if ("Fill".equalsIgnoreCase(option) || "Empty".equalsIgnoreCase(option)
			|| "Empty basket".equalsIgnoreCase(option)
			|| "Open".equalsIgnoreCase(option) || "Close".equalsIgnoreCase(option)
			|| "Charge".equalsIgnoreCase(option) || "Uncharge".equalsIgnoreCase(option)
			|| "Unload".equalsIgnoreCase(option))
		{
			String lowerTarget = target.toLowerCase(Locale.ROOT);
			if (isContainerOrChargedItemTarget(lowerTarget))
			{
				if ("Unload".equalsIgnoreCase(option) && lowerTarget.contains("blowpipe"))
				{
					weaponChargeTracker.clearLoadedDarts();
				}
				interfaceTracker.setNeedsRebaseline(true);
				rebaselineGraceTicks = 3;
				log.debug("Container/charged item action clicked ('{}' on '{}'), scheduling rebaseline", option, target);
			}
		}
		else if (option.toLowerCase(Locale.ROOT).startsWith("collect"))
		{
			interfaceTracker.setNeedsRebaseline(true);
			rebaselineGraceTicks = 3;
			log.debug("Collect action clicked ('{}' on '{}'), scheduling rebaseline", option, target);
		}
		else if (option.toLowerCase(Locale.ROOT).startsWith("store") || option.toLowerCase(Locale.ROOT).startsWith("deposit"))
		{
			if (client != null && client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1)
			{
				ItemContainer inv = client.getItemContainer(InventoryID.INV);
				if (inv != null && hasLootingBag(inv))
				{
					lastLootingBagDepositTick = tick;
					lastLootingBagDepositItemId = event.getItemId();
					lastLootingBagDepositItemName = target;
					log.debug("Looting bag deposit intent via Store/Deposit: {} on {} (id {})", option, target, event.getItemId());
				}
			}
		}
		else if ("note".equalsIgnoreCase(option) || "un-note".equalsIgnoreCase(option))
		{
			lastNotingServiceTick = tick;
		}
		else if (lastLootingBagDepositTick > 0 && tick - lastLootingBagDepositTick <= 15
			&& ("one".equalsIgnoreCase(option) || "five".equalsIgnoreCase(option) || "all".equalsIgnoreCase(option) || "x".equalsIgnoreCase(option)))
		{
			lastLootingBagDepositTick = tick;
			log.debug("Looting bag deposit amount selected: {}", option);
		}
		else if ("Light".equalsIgnoreCase(option) || "Burn".equalsIgnoreCase(option))
		{
			lastTinderboxActionTick = tick;
		}
		else if ("Bury".equalsIgnoreCase(option) || "Scatter".equalsIgnoreCase(option))
		{
			lastSkillXpTicks.put(Skill.PRAYER, tick);
		}
		else if ("Plant".equalsIgnoreCase(option) || "Treat".equalsIgnoreCase(option) || "Cure".equalsIgnoreCase(option)
			|| "Harvest".equalsIgnoreCase(option) || "Pick".equalsIgnoreCase(option) || "Rake".equalsIgnoreCase(option)
			|| "Clear".equalsIgnoreCase(option) || "Prune".equalsIgnoreCase(option) || "Check-health".equalsIgnoreCase(option)
			|| isFarmingPatchAction(target))
		{
			lastFarmingActionTick = tick;
			lastSkillXpTicks.put(Skill.FARMING, tick);
		}
		else if ("Build".equalsIgnoreCase(option))
		{
			lastSkillXpTicks.put(Skill.CONSTRUCTION, tick);
		}
		else if ("Reanimate".equalsIgnoreCase(option) || ("Cast".equalsIgnoreCase(option) && target.contains("ensouled")))
		{
			lastSkillXpTicks.put(Skill.PRAYER, tick);
			lastSkillXpTicks.put(Skill.MAGIC, tick);
		}
		else if ("Cast".equalsIgnoreCase(option) && target.contains("alchemy"))
		{
			lastAlchActionTick = tick;
		}
		else if (option.toLowerCase(Locale.ROOT).contains("alchemy"))
		{
			lastAlchActionTick = tick;
		}
		else if (option.toLowerCase(Locale.ROOT).startsWith("craft"))
		{
			lastSkillXpTicks.put(Skill.RUNECRAFT, tick);
			lastSkillXpTicks.put(Skill.CRAFTING, tick);
		}
		else if ("Set-trap".equalsIgnoreCase(option) || "Lay".equalsIgnoreCase(option))
		{
			lastSkillXpTicks.put(Skill.HUNTER, tick);
		}
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		if (client == null || client.getLocalPlayer() == null)
		{
			return;
		}

		ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
		if (invContainer == null)
		{
			return;
		}

		boolean canLootBag = hasOpenLootingBag(invContainer) && client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1;
		boolean canGemBag = hasOpenGemBag(invContainer);
		boolean canHerbSack = hasOpenHerbSack(invContainer);
		boolean canSeedBox = hasOpenSeedBox(invContainer);
		ItemContainer wornContainer = client.getItemContainer(InventoryID.WORN);
		boolean canLogBasket = hasOpenLogBasket(invContainer, wornContainer);

		if (!canLootBag && !canGemBag && !canHerbSack && !canSeedBox && !canLogBasket)
		{
			return;
		}

		TileItem tileItem = event.getItem();
		Tile tile = event.getTile();
		if (tileItem == null || tile == null)
		{
			return;
		}

		WorldPoint itemPoint = tile.getWorldLocation();
		WorldPoint playerPoint = client.getLocalPlayer().getWorldLocation();
		if (playerPoint.distanceTo(itemPoint) > 1)
		{
			return;
		}

		int currentTick = client.getTickCount();
		TakeClick matchedClick = null;
		for (TakeClick click : recentTakeClicks)
		{
			if (click.itemId == tileItem.getId()
				&& click.point.distanceTo(itemPoint) <= 1
				&& currentTick - click.tick <= 5)
			{
				matchedClick = click;
				break;
			}
		}

		if (matchedClick == null)
		{
			return;
		}

		recentTakeClicks.remove(matchedClick);

		ItemComposition comp = itemManager != null ? itemManager.getItemComposition(tileItem.getId()) : null;
		if (comp != null && !comp.isTradeable())
		{
			return;
		}

		if (canGemBag && InventorySnapshotService.isUncutGem(tileItem.getId()))
		{
			pendingGemBagPickups.add(new LootPickup(tileItem.getId(), tileItem.getQuantity()));
		}
		else if (canHerbSack && InventorySnapshotService.isGrimyHerb(tileItem.getId()))
		{
			pendingHerbSackPickups.add(new LootPickup(tileItem.getId(), tileItem.getQuantity()));
		}
		else if (canSeedBox && comp != null && ConsumableRegistry.isSeed(comp.getName()))
		{
			pendingSeedBoxPickups.add(new LootPickup(tileItem.getId(), tileItem.getQuantity()));
		}
		else if (canLogBasket && comp != null && (ConsumableRegistry.isLog(comp.getName()) || tileItem.getId() == ItemID.HOLLOW_BARK))
		{
			pendingLogBasketPickups.add(new LootPickup(tileItem.getId(), tileItem.getQuantity()));
		}
		else if (canLootBag)
		{
			pendingLootingBagPickups.add(new LootPickup(tileItem.getId(), tileItem.getQuantity()));
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (client != null && event.getActor() == client.getLocalPlayer())
		{
			int anim = client.getLocalPlayer().getAnimation();
			if (anim != -1 && !isDefensiveAnimation(anim))
			{
				recordPlayerActivity();
			}
			if (isFiremakingAnimation(anim))
			{
				lastFiremakingAnimTick = client.getTickCount();
			}
			else if (isFarmingAnimation(anim))
			{
				lastFarmingAnimTick = client.getTickCount();
			}
			else if (isAlchemyAnimation(anim))
			{
				lastAlchAnimTick = client.getTickCount();
			}
			weaponChargeTracker.onAttackAnimation(anim, equippedWeaponName(), client.getTickCount());
		}
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		if (client != null && event.getActor() != null && event.getActor() == client.getLocalPlayer())
		{
			weaponChargeTracker.onAttackGraphic(event.getActor().getGraphic(), equippedWeaponName(),
				client.getTickCount());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.SPAM || event.getType() == ChatMessageType.GAMEMESSAGE)
		{
			String msg = event.getMessage();
			if (msg.contains("fire catches") || msg.contains("light the logs") || msg.contains("burn the logs")
				|| msg.contains("burn some") || msg.contains("add a log to the fire"))
			{
				lastFiremakingChatTick = client != null ? client.getTickCount() : 0;
			}
			else if (msg.startsWith("You plant ") || msg.startsWith("You treat the ")
				|| (msg.startsWith("You put the ") && msg.contains("plant pot"))
				|| msg.startsWith("You cure the "))
			{
				int tick = client != null ? client.getTickCount() : 0;
				lastFarmingChatTick = tick;
				lastSkillXpTicks.put(Skill.FARMING, tick);
			}
			else if (msg.contains("into your open gem bag") || msg.contains("into your open gem sack")
				|| msg.contains("into your open gem pouch") || msg.contains("into your open gem satchel")
				|| msg.contains("into your open gem tote"))
			{
				int currentTick = client != null ? client.getTickCount() : 0;
				int gemId = findUncutGemIdInMessage(msg);
				if (gemId <= 0 && lastSkillingGemId > 0 && currentTick - lastSkillingGemTick <= 3)
				{
					gemId = lastSkillingGemId;
				}
				if (gemId > 0)
				{
					pendingGemBagPickups.add(new LootPickup(gemId, 1));
					lastSkillingGemId = -1;
				}
			}
			else if (msg.contains("into your open herb sack") || msg.contains("into your herb sack")
				|| HERBIBOAR_HERB_SACK_PATTERN.matcher(msg).matches())
			{
				int herbId = findGrimyHerbIdInMessage(msg);
				if (herbId > 0)
				{
					pendingHerbSackPickups.add(new LootPickup(herbId, 1));
				}
			}
			else if (msg.contains("into your open seed box"))
			{
				int seedId = findSeedIdInMessage(msg);
				int qty = parseSeedQtyInMessage(msg);
				if (seedId > 0 && qty > 0)
				{
					pendingSeedBoxPickups.add(new LootPickup(seedId, qty));
				}
			}
			else if (msg.contains("You steal") && msg.contains("seed"))
			{
				ItemContainer inv = client != null ? client.getItemContainer(InventoryID.INV) : null;
				if (inv != null && hasOpenSeedBox(inv))
				{
					int seedId = findSeedIdInMessage(msg);
					int qty = parseSeedQtyInMessage(msg);
					if (seedId > 0 && qty > 0)
					{
						pendingSeedBoxPickups.add(new LootPickup(seedId, qty));
					}
				}
			}
			else if (FISHING_CATCH_REGEX.matcher(msg).find())
			{
				ItemContainer inv = client != null ? client.getItemContainer(InventoryID.INV) : null;
				ItemContainer worn = client != null ? client.getItemContainer(InventoryID.WORN) : null;
				if (hasOpenFishBarrel(inv, worn))
				{
					int fishId = findRawFishIdInMessage(msg);
					int qty = parseFishQtyInMessage(msg);
					if (fishId > 0 && qty > 0)
					{
						pendingFishBarrelPickups.add(new LootPickup(fishId, qty));
					}
				}
			}
			else if (WOODCUTTING_CHOP_REGEX.matcher(msg).find())
			{
				ItemContainer inv = client != null ? client.getItemContainer(InventoryID.INV) : null;
				ItemContainer worn = client != null ? client.getItemContainer(InventoryID.WORN) : null;
				if (hasOpenLogBasket(inv, worn))
				{
					int logId = findLogIdInMessage(msg);
					int qty = parseLogQtyInMessage(msg);
					if (logId > 0 && qty > 0)
					{
						pendingLogBasketPickups.add(new LootPickup(logId, qty));
					}
				}
			}
			else if (msg.contains("You add the gem") || msg.contains("to your gem bag")
				|| (msg.contains("gem") && (msg.contains("to your bag") || msg.contains("into your bag")))
				|| msg.contains("to your gem sack") || msg.contains("to your gem pouch")
				|| msg.contains("to your gem satchel") || msg.contains("to your gem tote")
				|| msg.contains("into your herb sack") || msg.contains("to your herb sack")
				|| msg.contains("into your seed box") || msg.contains("to your seed box")
				|| msg.contains("The fish barrel is now full") || msg.contains("The seed box is full")
				|| msg.contains("The herb sack is full")
				|| msg.contains("The basket is full")
				|| msg.contains("into the Forestry basket") || msg.contains("into the log basket")
				|| msg.contains("into your Forestry basket") || msg.contains("into your log basket")
				|| msg.contains("to your Forestry basket") || msg.contains("to your log basket")
				|| msg.contains("You empty your basket") || msg.contains("You empty as many logs as you can carry"))
			{
				interfaceTracker.setNeedsRebaseline(true);
				rebaselineGraceTicks = Math.max(rebaselineGraceTicks, 2);
			}
			else
			{
				int gemId = findUncutGemIdInMessage(msg);
				if (gemId > 0 && (msg.contains("find") || msg.contains("found") || msg.contains("mine")
					|| msg.contains("steal") || msg.contains("stole") || msg.contains("manage to")))
				{
					lastSkillingGemId = gemId;
					lastSkillingGemTick = client != null ? client.getTickCount() : 0;
				}
			}
		}
	}

	static int findUncutGemIdInMessage(String msg)
	{
		String lower = msg.toLowerCase(Locale.ROOT);
		if (!lower.contains("uncut") && !lower.contains("gem"))
		{
			return -1;
		}
		if (lower.contains("dragonstone"))
		{
			return ItemID.UNCUT_DRAGONSTONE;
		}
		if (lower.contains("diamond"))
		{
			return ItemID.UNCUT_DIAMOND;
		}
		if (lower.contains("ruby"))
		{
			return ItemID.UNCUT_RUBY;
		}
		if (lower.contains("emerald"))
		{
			return ItemID.UNCUT_EMERALD;
		}
		if (lower.contains("sapphire"))
		{
			return ItemID.UNCUT_SAPPHIRE;
		}
		if (lower.contains("topaz"))
		{
			return ItemID.UNCUT_RED_TOPAZ;
		}
		if (lower.contains("jade"))
		{
			return ItemID.UNCUT_JADE;
		}
		if (lower.contains("opal"))
		{
			return ItemID.UNCUT_OPAL;
		}
		return -1;
	}

	static int findGrimyHerbIdInMessage(String msg)
	{
		String lower = msg.toLowerCase(Locale.ROOT);
		if (!lower.contains("grimy") && !lower.contains("herb"))
		{
			return -1;
		}
		if (lower.contains("torstol"))
		{
			return ItemID.UNIDENTIFIED_TORSTOL;
		}
		if (lower.contains("dwarf weed"))
		{
			return ItemID.UNIDENTIFIED_DWARF_WEED;
		}
		if (lower.contains("lantadyme"))
		{
			return ItemID.UNIDENTIFIED_LANTADYME;
		}
		if (lower.contains("cadantine"))
		{
			return ItemID.UNIDENTIFIED_CADANTINE;
		}
		if (lower.contains("snapdragon"))
		{
			return ItemID.UNIDENTIFIED_SNAPDRAGON;
		}
		if (lower.contains("kwuarm"))
		{
			return ItemID.UNIDENTIFIED_KWUARM;
		}
		if (lower.contains("avantoe"))
		{
			return ItemID.UNIDENTIFIED_AVANTOE;
		}
		if (lower.contains("irit"))
		{
			return ItemID.UNIDENTIFIED_IRIT;
		}
		if (lower.contains("toadflax"))
		{
			return ItemID.UNIDENTIFIED_TOADFLAX;
		}
		if (lower.contains("ranarr"))
		{
			return ItemID.UNIDENTIFIED_RANARR;
		}
		if (lower.contains("harralander"))
		{
			return ItemID.UNIDENTIFIED_HARRALANDER;
		}
		if (lower.contains("tarromin"))
		{
			return ItemID.UNIDENTIFIED_TARROMIN;
		}
		if (lower.contains("marrentill"))
		{
			return ItemID.UNIDENTIFIED_MARENTILL;
		}
		if (lower.contains("guam"))
		{
			return ItemID.UNIDENTIFIED_GUAM;
		}
		return -1;
	}

	static int findRawFishIdInMessage(String msg)
	{
		String lower = msg.toLowerCase(Locale.ROOT);
		if (lower.contains("karambwanji"))
		{
			return ItemID.TBWT_RAW_KARAMBWANJI;
		}
		if (lower.contains("karambwan"))
		{
			return ItemID.TBWT_RAW_KARAMBWAN;
		}
		if (lower.contains("anglerfish"))
		{
			return ItemID.RAW_ANGLERFISH;
		}
		if (lower.contains("dark crab"))
		{
			return ItemID.RAW_DARK_CRAB;
		}
		if (lower.contains("monkfish"))
		{
			return ItemID.RAW_MONKFISH;
		}
		if (lower.contains("shark"))
		{
			return ItemID.RAW_SHARK;
		}
		if (lower.contains("swordfish"))
		{
			return ItemID.RAW_SWORDFISH;
		}
		if (lower.contains("lobster"))
		{
			return ItemID.RAW_LOBSTER;
		}
		if (lower.contains("bass"))
		{
			return ItemID.RAW_BASS;
		}
		if (lower.contains("tuna"))
		{
			return ItemID.RAW_TUNA;
		}
		if (lower.contains("salmon"))
		{
			return ItemID.RAW_SALMON;
		}
		if (lower.contains("trout"))
		{
			return ItemID.RAW_TROUT;
		}
		if (lower.contains("pike"))
		{
			return ItemID.RAW_PIKE;
		}
		if (lower.contains("herring"))
		{
			return ItemID.RAW_HERRING;
		}
		if (lower.contains("sardine"))
		{
			return ItemID.RAW_SARDINE;
		}
		if (lower.contains("anchov"))
		{
			return ItemID.RAW_ANCHOVIES;
		}
		if (lower.contains("shrimp"))
		{
			return ItemID.RAW_SHRIMP;
		}
		if (lower.contains("lava eel"))
		{
			return ItemID.LAVA_EEL;
		}
		if (lower.contains("cave eel"))
		{
			return ItemID.RAW_CAVE_EEL;
		}
		if (lower.contains("slimy eel"))
		{
			return ItemID.MORT_SLIMEY_EEL;
		}
		if (lower.contains("rainbow fish"))
		{
			return ItemID.HUNTING_RAW_FISH_SPECIAL;
		}
		return -1;
	}

	static int findSeedIdInMessage(String msg)
	{
		String lower = msg.toLowerCase(Locale.ROOT);
		if (!lower.contains("seed"))
		{
			return -1;
		}
		if (lower.contains("ranarr"))
		{
			return ItemID.RANARR_SEED;
		}
		if (lower.contains("snapdragon"))
		{
			return ItemID.SNAPDRAGON_SEED;
		}
		if (lower.contains("torstol"))
		{
			return ItemID.TORSTOL_SEED;
		}
		if (lower.contains("toadflax"))
		{
			return ItemID.TOADFLAX_SEED;
		}
		if (lower.contains("avantoe"))
		{
			return ItemID.AVANTOE_SEED;
		}
		if (lower.contains("kwuarm"))
		{
			return ItemID.KWUARM_SEED;
		}
		if (lower.contains("cadantine"))
		{
			return ItemID.CADANTINE_SEED;
		}
		if (lower.contains("lantadyme"))
		{
			return ItemID.LANTADYME_SEED;
		}
		if (lower.contains("dwarf weed"))
		{
			return ItemID.DWARF_WEED_SEED;
		}
		if (lower.contains("irit"))
		{
			return ItemID.IRIT_SEED;
		}
		if (lower.contains("harralander"))
		{
			return ItemID.HARRALANDER_SEED;
		}
		if (lower.contains("tarromin"))
		{
			return ItemID.TARROMIN_SEED;
		}
		if (lower.contains("marrentill"))
		{
			return ItemID.MARRENTILL_SEED;
		}
		if (lower.contains("guam"))
		{
			return ItemID.GUAM_SEED;
		}
		if (lower.contains("limpwurt"))
		{
			return ItemID.LIMPWURT_SEED;
		}
		if (lower.contains("watermelon"))
		{
			return ItemID.WATERMELON_SEED;
		}
		if (lower.contains("snape grass"))
		{
			return ItemID.SNAPE_GRASS_SEED;
		}
		if (lower.contains("yew"))
		{
			return ItemID.YEW_SEED;
		}
		if (lower.contains("magic"))
		{
			return ItemID.MAGIC_TREE_SEED;
		}
		if (lower.contains("palm"))
		{
			return ItemID.PALM_TREE_SEED;
		}
		if (lower.contains("dragonfruit"))
		{
			return ItemID.DRAGONFRUIT_TREE_SEED;
		}
		if (lower.contains("celastrus"))
		{
			return ItemID.CELASTRUS_TREE_SEED;
		}
		if (lower.contains("redwood"))
		{
			return ItemID.REDWOOD_TREE_SEED;
		}
		if (lower.contains("spirit"))
		{
			return ItemID.SPIRIT_TREE_SEED;
		}
		if (lower.contains("hespori"))
		{
			return ItemID.HESPORI_SEED;
		}
		if (lower.contains("potato"))
		{
			return ItemID.POTATO_SEED;
		}
		if (lower.contains("onion"))
		{
			return ItemID.ONION_SEED;
		}
		if (lower.contains("cabbage"))
		{
			return ItemID.CABBAGE_SEED;
		}
		if (lower.contains("tomato"))
		{
			return ItemID.TOMATO_SEED;
		}
		if (lower.contains("sweetcorn"))
		{
			return ItemID.SWEETCORN_SEED;
		}
		if (lower.contains("strawberry"))
		{
			return ItemID.STRAWBERRY_SEED;
		}
		if (lower.contains("barley"))
		{
			return ItemID.BARLEY_SEED;
		}
		if (lower.contains("hammerstone"))
		{
			return ItemID.HAMMERSTONE_HOP_SEED;
		}
		if (lower.contains("asgarnian"))
		{
			return ItemID.ASGARNIAN_HOP_SEED;
		}
		if (lower.contains("jute"))
		{
			return ItemID.JUTE_SEED;
		}
		if (lower.contains("yanillian"))
		{
			return ItemID.YANILLIAN_HOP_SEED;
		}
		if (lower.contains("krandorian"))
		{
			return ItemID.KRANDORIAN_HOP_SEED;
		}
		if (lower.contains("wildblood"))
		{
			return ItemID.WILDBLOOD_HOP_SEED;
		}
		return -1;
	}

	static int parseSeedQtyInMessage(String msg)
	{
		Matcher m = Pattern.compile("(\\d+)\\s*x", Pattern.CASE_INSENSITIVE).matcher(msg);
		if (m.find())
		{
			try
			{
				return Integer.parseInt(m.group(1));
			}
			catch (NumberFormatException ignored) {}
		}
		return 1;
	}

	static int parseFishQtyInMessage(String msg)
	{
		Matcher m = Pattern.compile("You catch (\\d+)", Pattern.CASE_INSENSITIVE).matcher(msg);
		if (m.find())
		{
			try
			{
				return Integer.parseInt(m.group(1));
			}
			catch (NumberFormatException ignored) {}
		}
		return 1;
	}

	static int findLogIdInMessage(String msg)
	{
		String lower = msg.toLowerCase(Locale.ROOT);
		if (lower.contains("arctic pine"))
		{
			return ItemID.ARCTIC_PINE_LOG;
		}
		if (lower.contains("redwood"))
		{
			return ItemID.REDWOOD_LOGS;
		}
		if (lower.contains("magic"))
		{
			return ItemID.MAGIC_LOGS;
		}
		if (lower.contains("yew"))
		{
			return ItemID.YEW_LOGS;
		}
		if (lower.contains("maple"))
		{
			return ItemID.MAPLE_LOGS;
		}
		if (lower.contains("mahogany"))
		{
			return ItemID.MAHOGANY_LOGS;
		}
		if (lower.contains("teak"))
		{
			return ItemID.TEAK_LOGS;
		}
		if (lower.contains("willow"))
		{
			return ItemID.WILLOW_LOGS;
		}
		if (lower.contains("oak"))
		{
			return ItemID.OAK_LOGS;
		}
		if (lower.contains("juniper"))
		{
			return ItemID.JUNIPER_LOGS;
		}
		if (lower.contains("blisterwood"))
		{
			return ItemID.BLISTERWOOD_LOGS;
		}
		if (lower.contains("achey"))
		{
			return ItemID.ACHEY_TREE_LOGS;
		}
		if (lower.contains("camphor"))
		{
			return ItemID.CAMPHOR_LOGS;
		}
		if (lower.contains("ironwood"))
		{
			return ItemID.IRONWOOD_LOGS;
		}
		if (lower.contains("rosewood"))
		{
			return ItemID.ROSEWOOD_LOGS;
		}
		if (lower.contains("jatoba"))
		{
			return ItemID.JATOBA_LOGS;
		}
		if (lower.contains("bark"))
		{
			return ItemID.HOLLOW_BARK;
		}
		if (lower.contains("logs") || lower.contains("log"))
		{
			return ItemID.LOGS;
		}
		return -1;
	}

	static int parseLogQtyInMessage(String msg)
	{
		Matcher m = Pattern.compile("You (?:get|cut) (\\d+)", Pattern.CASE_INSENSITIVE).matcher(msg);
		if (m.find())
		{
			try
			{
				return Integer.parseInt(m.group(1));
			}
			catch (NumberFormatException ignored) {}
		}
		return 1;
	}

	private static boolean isPvpKeyContainer(int containerId)
	{
		for (int id : PVP_LOOT_KEY_CONTAINERS)
		{
			if (id == containerId)
			{
				return true;
			}
		}
		return false;
	}

	void checkPvpKeyContainers()
	{
		if (client == null || session == null)
		{
			return;
		}

		Map<Integer, Integer> pvpGains = new HashMap<>();
		for (int containerId : PVP_LOOT_KEY_CONTAINERS)
		{
			ItemContainer container = client.getItemContainer(containerId);
			Map<Integer, Integer> currentItems = new HashMap<>();
			if (container != null && container.getItems() != null)
			{
				for (Item item : container.getItems())
				{
					if (item != null && item.getId() > 0 && item.getQuantity() > 0)
					{
						currentItems.merge(item.getId(), item.getQuantity(), Integer::sum);
					}
				}
			}

			Map<Integer, Integer> prevItems = previousPvpKeyContainers.getOrDefault(containerId, Collections.emptyMap());
			for (Map.Entry<Integer, Integer> entry : currentItems.entrySet())
			{
				int id = entry.getKey();
				int qty = entry.getValue();
				int prevQty = prevItems.getOrDefault(id, 0);
				if (qty > prevQty)
				{
					pvpGains.merge(id, qty - prevQty, Integer::sum);
				}
			}

			previousPvpKeyContainers.put(containerId, currentItems);
		}

		if (!pvpGains.isEmpty())
		{
			log.debug("PvP Loot Chest opened/updated, crediting key loot: {}", pvpGains);
			processGains(pvpGains);
		}
	}

	static boolean isContainerOrChargedItemTarget(String lowerTarget)
	{
		if (lowerTarget == null || isFarmingPatchAction(lowerTarget))
		{
			return false;
		}
		return lowerTarget.contains("gem bag") || lowerTarget.contains("gem sack")
			|| lowerTarget.contains("gem pouch") || lowerTarget.contains("gem satchel") || lowerTarget.contains("gem tote")
			|| lowerTarget.contains("herb sack")
			|| lowerTarget.contains("fish barrel") || lowerTarget.contains("fish sack barrel")
			|| lowerTarget.contains("log basket") || lowerTarget.contains("forestry basket")
			|| lowerTarget.contains("seed box")
			|| lowerTarget.contains("ash sanctifier")
			|| lowerTarget.contains("bonecrusher")
			|| lowerTarget.contains("tackle box")
			|| lowerTarget.contains("plank sack")
			|| lowerTarget.contains("bottomless compost bucket")
			|| lowerTarget.contains("coal bag")
			// Essence pouches (small/medium/large/giant/colossal): Fill/Empty moves
			// essence between inventory and pouch without consuming it.
			|| lowerTarget.endsWith(" pouch") || lowerTarget.equals("pouch")
			// Charged weapons: Charge/Uncharge/Unload actions return stored resources to
			// inventory, so the diff must be rebaselined rather than treated as profit.
			|| lowerTarget.contains("toxic blowpipe")
			|| lowerTarget.contains("trident of the")
			|| lowerTarget.contains("sanguinesti")
			|| lowerTarget.contains("tumeken's shadow") || lowerTarget.contains("tumekens shadow")
			|| lowerTarget.contains("craw's bow") || lowerTarget.contains("webweaver bow")
			|| lowerTarget.contains("viggora's chainmace") || lowerTarget.contains("thammaron's sceptre")
			|| lowerTarget.contains("accursed sceptre") || lowerTarget.contains("ursine chainmace")
			|| lowerTarget.contains("serpentine helm") || lowerTarget.contains("toxic staff")
			|| lowerTarget.contains("bow of faerdhinen") || lowerTarget.contains("blade of saeldor")
			|| lowerTarget.contains("crystal helm") || lowerTarget.contains("crystal body")
			|| lowerTarget.contains("crystal legs")
			|| lowerTarget.contains("venator bow") || lowerTarget.contains("tonalztics")
			|| lowerTarget.contains("tome of fire") || lowerTarget.contains("tome of water")
			|| lowerTarget.contains("tome of earth")
			|| lowerTarget.contains("ring of suffering") || lowerTarget.contains("xeric's talisman")
			|| lowerTarget.contains("bryophyta's staff") || lowerTarget.contains("arclight")
			|| lowerTarget.contains("amulet of blood fury") || lowerTarget.contains("bracelet of ethereum");
	}

	public static boolean isFarmingPatchAction(String lowerTarget)
	{
		if (lowerTarget == null)
		{
			return false;
		}
		if (lowerTarget.contains("->"))
		{
			String[] parts = lowerTarget.split("->");
			if (parts.length >= 2)
			{
				String dest = parts[1].trim();
				return dest.contains("patch") || dest.contains("allotment")
					|| dest.contains("plant pot") || dest.contains("compost bin")
					|| dest.contains("soil") || dest.contains("vine");
			}
		}
		return lowerTarget.contains("patch") || lowerTarget.contains("plant pot") || lowerTarget.contains("allotment")
			|| lowerTarget.contains("compost bin") || lowerTarget.contains("soil") || lowerTarget.contains("vine");
	}

	public static boolean isBirdHouseAction(String lowerTarget)
	{
		if (lowerTarget == null)
		{
			return false;
		}
		return lowerTarget.contains("bird house") || lowerTarget.contains("birdhouse");
	}

	public static boolean isFarmingAnimation(int anim)
	{
		return anim == AnimationID.FARMING_SEED_DIBBING
			|| anim == AnimationID.FARMING_SEED_DIBBING_NODIB
			|| anim == AnimationID.FARMING_POUR_WATER
			|| anim == AnimationID.FARMING_PLANT_CURE
			|| anim == AnimationID.FARMING_TROWEL_DIG
			|| anim == AnimationID.FARMING_TROWEL_DIGGING
			|| anim == AnimationID.FARMING_TROWEL_SHORTDIG
			|| anim == AnimationID.FARMING_FILLING_PLANTPOT
			|| anim == AnimationID.FARMING_POUR_WATER_BOTTOMLESSBUCKET;
	}

	public static boolean isFiremakingAnimation(int anim)
	{
		return anim == AnimationID.HUMAN_CREATEFIRE_SINGLE
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_GENERIC_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_OAK_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_WILLOW_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_TEAK_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_ARCTIC_PINE_LOG_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_MAPLE_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_MAHOGANY_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_YEW_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_MAGIC_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_REDWOOD_LOGS_NOLOOP
			|| anim == AnimationID.FORESTRY_CAMPFIRE_BURNING_BLISTERWOOD_LOGS_NOLOOP;
	}

	public static boolean isAlchemyAnimation(int anim)
	{
		return anim == AnimationID.HUMAN_CASTHIGHLVLALCHEMY
			|| anim == AnimationID.HUMAN_CASTLOWLVLALCHEMY
			|| anim == AnimationID.HUMAN_CASTHIGHLVLALCHEMY_FIRE
			|| anim == AnimationID.HUMAN_CASTLOWLVLALCHEMY_FIRE;
	}

	public static boolean isDefensiveAnimation(int anim)
	{
		return anim == AnimationID.HUMAN_UNARMEDBLOCK
			|| anim == AnimationID.HUMAN_BLUNT_BLOCK
			|| anim == AnimationID.HUMAN_BLUNT_DEF
			|| anim == AnimationID.HUMAN_SWORD_DEF
			|| anim == AnimationID.HUMAN_STAFFORB_BLOCK
			|| anim == AnimationID.HUMAN_SHIELD_DEFENCE;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (session == null || !snapshotInitialized)
		{
			pendingLootingBagPickups.clear();
			pendingGemBagPickups.clear();
			pendingHerbSackPickups.clear();
			pendingFishBarrelPickups.clear();
			pendingSeedBoxPickups.clear();
			pendingLogBasketPickups.clear();
			invItemsGainedThisTick.clear();
			return;
		}

		// Finalize pending looting bag pickups from ground
		if (!pendingLootingBagPickups.isEmpty())
		{
			if (client != null)
			{
				ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
				if (invContainer != null && hasOpenLootingBag(invContainer))
				{
					boolean added = false;
					for (LootPickup pickup : pendingLootingBagPickups)
					{
						int invGained = invItemsGainedThisTick.getOrDefault(pickup.itemId, 0);
						if (pickup.qty > invGained)
						{
							int containerQty = pickup.qty - invGained;
							getSnapshotService().addLootingBagPendingItem(pickup.itemId, containerQty);
							invItemsGainedThisTick.put(pickup.itemId, 0);
							added = true;
						}
						else
						{
							invItemsGainedThisTick.put(pickup.itemId, invGained - pickup.qty);
						}
					}
					if (added)
					{
						processInventoryChanges(invContainer);
					}
				}
			}
			pendingLootingBagPickups.clear();
		}

		// Finalize pending gem bag pickups (ground take or skilling into open gem bag)
		if (!pendingGemBagPickups.isEmpty())
		{
			if (client != null)
			{
				ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
				if (invContainer != null && hasOpenGemBag(invContainer))
				{
					drainPendingContainerPickups(pendingGemBagPickups);
				}
			}
			pendingGemBagPickups.clear();
		}

		// Finalize pending herb sack pickups
		if (!pendingHerbSackPickups.isEmpty())
		{
			if (client != null)
			{
				ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
				if (invContainer != null && hasOpenHerbSack(invContainer))
				{
					drainPendingContainerPickups(pendingHerbSackPickups);
				}
			}
			pendingHerbSackPickups.clear();
		}

		// Finalize pending fish barrel pickups
		if (!pendingFishBarrelPickups.isEmpty())
		{
			if (client != null)
			{
				ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
				ItemContainer wornContainer = client.getItemContainer(InventoryID.WORN);
				if (hasOpenFishBarrel(invContainer, wornContainer))
				{
					drainPendingContainerPickups(pendingFishBarrelPickups);
				}
			}
			pendingFishBarrelPickups.clear();
		}

		// Finalize pending seed box pickups
		if (!pendingSeedBoxPickups.isEmpty())
		{
			if (client != null)
			{
				ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
				if (invContainer != null && hasOpenSeedBox(invContainer))
				{
					drainPendingContainerPickups(pendingSeedBoxPickups);
				}
			}
			pendingSeedBoxPickups.clear();
		}

		// Finalize pending log basket pickups
		if (!pendingLogBasketPickups.isEmpty())
		{
			if (client != null)
			{
				ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
				ItemContainer wornContainer = client.getItemContainer(InventoryID.WORN);
				if (hasOpenLogBasket(invContainer, wornContainer))
				{
					drainPendingContainerPickups(pendingLogBasketPickups);
				}
			}
			pendingLogBasketPickups.clear();
		}
		invItemsGainedThisTick.clear();
		matchedUnequipsThisTick.clear();

		// Finalize pending equipped ammo/thrown weapons consumed without inventory changes
		if (!pendingWornAmmoExpenses.isEmpty())
		{
			if (interfaceTracker.isTrackingSuppressed() || isBankOrContainerOpen() || interfaceTracker.isNeedsRebaseline() || rebaselineGraceTicks > 0)
			{
				pendingWornAmmoExpenses.clear();
			}
			else
			{
				Map<Integer, CoinFlowSession.TrackedItem> wornAmmoExpenses = new HashMap<>();
				for (Map.Entry<Integer, Integer> entry : pendingWornAmmoExpenses.entrySet())
				{
					int itemId = entry.getKey();
					int quantity = entry.getValue();
					String itemName = getItemName(itemId);
					long price = itemManager != null ? itemManager.getItemPrice(itemId) : 0;
					if (price <= 0 && itemManager != null)
					{
						ItemComposition comp = itemManager.getItemComposition(itemId);
						if (comp != null)
						{
							price = comp.getHaPrice();
						}
					}
					wornAmmoExpenses.put(itemId, new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price));
					log.debug("Consumed equipped ammo on tick: {} x{} @ {} gp = {} gp", itemName, quantity, price, (long) quantity * price);
					removeRecentlyUnequipped(itemId, quantity);
				}
				pendingWornAmmoExpenses.clear();
				processGainsLossesAndExpenses(Collections.emptyMap(), Collections.emptyMap(), wornAmmoExpenses);
			}
		}

		// Finalize pending charge-based supply costs (runes/scales/shards stored inside weapons)
		boolean chargeTrackingAllowed = !interfaceTracker.isTrackingSuppressed() && !isBankOrContainerOpen()
			&& !interfaceTracker.isNeedsRebaseline() && rebaselineGraceTicks <= 0;
		if (client != null)
		{
			for (int varbitId : WeaponChargeTracker.trackedVarbitIds())
			{
				if (invalidVarbitIds.contains(varbitId))
				{
					continue;
				}
				int value;
				try
				{
					value = client.getVarbitValue(varbitId);
				}
				catch (IndexOutOfBoundsException e)
				{
					// Some tracked CHARGES_* varbits are not varp-backed / not present in this
					// client build; getVarbitValue throws for them. Skip them from now on.
					if (invalidVarbitIds.add(varbitId))
					{
						log.debug("Ignoring untracked varbit {} (not varp-backed)", varbitId);
					}
					continue;
				}
				weaponChargeTracker.onVarbitChanged(varbitId, value,
					chargeTrackingAllowed, client.getTickCount());
			}
		}
		weaponChargeTracker.setDartRecoveryRate(equippedAvasRecoveryRate());
		// When tracking is disallowed mid-window (rebaseline grace), keep the pending
		// spend so attack-triggered costs aren't silently dropped; when the feature is
		// disabled, drain anyway to discard accumulation.
		Map<Integer, Integer> consumedChargeResources = chargeTrackingAllowed || !config.trackWeaponCharges()
			? weaponChargeTracker.drain()
			: Collections.emptyMap();
		if (!consumedChargeResources.isEmpty() && config.trackWeaponCharges())
		{
			Map<Integer, CoinFlowSession.TrackedItem> chargeExpenses = new HashMap<>();
			for (Map.Entry<Integer, Integer> entry : consumedChargeResources.entrySet())
			{
				int itemId = entry.getKey();
				int quantity = entry.getValue();
				String itemName = getItemName(itemId);
				long price = itemId == ItemID.COINS ? 1 : (itemManager != null ? itemManager.getItemPrice(itemId) : 0);
				if (price <= 0 && itemManager != null)
				{
					ItemComposition comp = itemManager.getItemComposition(itemId);
					if (comp != null)
					{
						price = comp.getHaPrice();
					}
				}
				chargeExpenses.put(itemId, new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price));
				log.debug("Consumed weapon charge resource on tick: {} x{} @ {} gp", itemName, quantity, price);
			}
			processGainsLossesAndExpenses(Collections.emptyMap(), Collections.emptyMap(), chargeExpenses);
		}

		if (client != null)
		{
			int currentTick = client.getTickCount();
			recentTakeClicks.removeIf(click -> currentTick - click.tick > 5);

			// Evict gear-swap records unmatched for >10 ticks (~6s). WORN<->INV
			// dispatch pairs resolve within 1-2 ticks, so anything older is stale
			// (e.g. ammo picked up directly into the ammo slot) and must not cancel
			// future legitimate inventory diffs.
			if (!gearSwapItemTicks.isEmpty())
			{
				gearSwapItemTicks.entrySet().removeIf(entry -> {
					if (currentTick - entry.getValue() > 10)
					{
						recentlyUnequippedItems.remove(entry.getKey());
						recentlyEquippedItems.remove(entry.getKey());
						return true;
					}
					return false;
				});
			}
		}

		// Update idle state and time tracking on each tick
		CoinFlowSession prevSession = session;
		session = session.tick(config.idleTimeoutMinutes(), isPlayerActive());

		// Periodic session telemetry (~every 30s) for debugging GP/hr and timer behavior.
		// tickDelta should hover ~600ms; sustained near-0 values indicate clock corruption.
		if (log.isDebugEnabled() && client != null && client.getTickCount() % 50 == 0)
		{
			log.debug("Session: tickDelta={}ms active={}s total={}s gross={} spent={} gp/hr={} idle={}",
				session.getTotalInGameTime().minus(prevSession.getTotalInGameTime()).toMillis(),
				session.getActiveTime().getSeconds(), session.getTotalInGameTime().getSeconds(),
				session.getGrossProfit(), session.getTotalExpenses(),
				session.getGpPerHour(config.includeAfkTime()), session.isIdle());
		}

		// Decrement rebaseline grace window after interface closures
		if (rebaselineGraceTicks > 0)
		{
			rebaselineGraceTicks--;
			if (rebaselineGraceTicks == 0 && interfaceTracker.isNeedsRebaseline())
			{
				interfaceTracker.setNeedsRebaseline(false);
				if (client != null && snapshotInitialized)
				{
					ItemContainer inv = client.getItemContainer(InventoryID.INV);
					if (inv != null)
					{
						previousInventorySnapshot = takeInventorySnapshot(inv);
					}
				}
			}
		}

		// Evict dropped item records older than 600 ticks (~6 minutes) to prevent memory leak and stale pickup matching
		if (client != null && client.getTickCount() % 50 == 0)
		{
			int currentTick = client.getTickCount();
			droppedItemTicks.entrySet().removeIf(entry -> {
				if (currentTick - entry.getValue() > 600)
				{
					recentlyDroppedItems.remove(entry.getKey());
					recentlyDroppedOwnedItems.remove(entry.getKey());
					return true;
				}
				return false;
			});
		}

		// Check goal completion notification (with latch to prevent tick spam)
		checkGoalNotification();

		// Update sidebar panel if visible
		if (panel != null)
		{
			panel.updateSession(session);
		}
	}

	void recordPlayerActivity()
	{
		if (client != null)
		{
			lastPlayerActivityTick = client.getTickCount();
		}
		if (session != null && session.isIdle())
		{
			session = session.withActivity();
		}
	}

	boolean isPlayerActive()
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return false;
		}

		// 1. Movement: World location changed since last tick
		WorldPoint currentLoc = localPlayer.getWorldLocation();
		boolean moved = false;
		if (currentLoc != null)
		{
			moved = lastPlayerLocation != null && !lastPlayerLocation.equals(currentLoc);
			lastPlayerLocation = currentLoc;
		}

		// 2. Action animation (woodcutting, mining, combat, casting, etc.)
		// NOTE: pose animation is deliberately not used — it stays in combat stance
		// for the whole fight, which would prevent AFK combat from ever going idle.
		boolean animating = localPlayer.getAnimation() != -1;

		// 3. Recent user-triggered action (menu click, item container change, XP drop within last 2 ticks)
		int currentTick = client.getTickCount();
		int diff = currentTick - lastPlayerActivityTick;
		boolean recentAction = diff >= 0 && diff <= 2;

		// 4. Direct input: mouse moved or key pressed recently (< 50 client cycles / ~1 sec)
		boolean activeInput = false;
		try
		{
			activeInput = client.getMouseIdleTicks() < 50 || client.getKeyboardIdleTicks() < 50;
		}
		catch (Throwable ignored)
		{
		}

		return moved || animating || recentAction || activeInput;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(CoinFlowConfig.CONFIG_GROUP))
		{
			if ("goldDropMinThreshold".equals(event.getKey()))
			{
				String val = event.getNewValue();
				if (val != null)
				{
					String sanitized = val.replaceAll(CoinFlowInputFilter.DIGITS_ONLY, "");
					if (!sanitized.equals(val))
					{
						int safeVal = 0;
						if (!sanitized.isEmpty())
						{
							try
							{
								safeVal = (int) Math.min(Integer.MAX_VALUE, Math.max(0, Long.parseLong(sanitized)));
							}
							catch (NumberFormatException ignored)
							{
								safeVal = 0;
							}
						}
						configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goldDropMinThreshold", safeVal);
					}
				}
			}
			else if ("idleTimeoutMinutes".equals(event.getKey()))
			{
				String val = event.getNewValue();
				if (val != null)
				{
					String sanitized = val.replaceAll(CoinFlowInputFilter.DIGITS_ONLY, "");
					if (!sanitized.equals(val))
					{
						int safeVal = 2;
						if (!sanitized.isEmpty())
						{
							try
							{
								safeVal = (int) Math.max(1, Math.min(60, Long.parseLong(sanitized)));
							}
							catch (NumberFormatException ignored)
							{
								safeVal = 2;
							}
						}
						configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "idleTimeoutMinutes", safeVal);
					}
				}
			}
			else if ("goalAmount".equals(event.getKey()))
			{
				goalCompletedNotified = false;
				String val = event.getNewValue();
				if (val != null)
				{
					String sanitized = val.replaceAll(CoinFlowInputFilter.TARGET_GP, "");
					if (!sanitized.equals(val))
					{
						configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalAmount", sanitized);
					}
				}
			}
			else if ("goalName".equals(event.getKey()))
			{
				String val = event.getNewValue();
				if (val != null)
				{
					String sanitized = val.replaceAll(CoinFlowInputFilter.GOAL_NAME, "");
					if (!sanitized.equals(val))
					{
						configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalName", sanitized);
					}
				}
			}
			else if ("ignoredItems".equals(event.getKey()))
			{
				String val = event.getNewValue();
				if (val != null)
				{
					String sanitized = val.replaceAll(CoinFlowInputFilter.IGNORED_ITEMS, "");
					if (!sanitized.equals(val))
					{
						configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "ignoredItems", sanitized);
					}
				}
			}

			if ("showGoldDrops".equals(event.getKey()) && !config.showGoldDrops() && goldDropOverlay != null)
			{
				goldDropOverlay.clear();
			}
			rebuildFilterSet();
			if (panel != null)
			{
				panel.onConfigChanged();
			}
			log.debug("Config changed for {}, reloaded ignored items", CoinFlowConfig.CONFIG_GROUP);
		}
	}

	// ── Internal Logic ───────────────────────────────────────────────────

	/**
	 * Takes a baseline snapshot of the current inventory without triggering a diff.
	 */
	void takeBaseline()
	{
		if (client == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
		if (invContainer != null)
		{
			ItemContainer bagContainer = client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG);
			if (bagContainer != null)
			{
				Map<Integer, Integer> bagItems = new HashMap<>();
				if (bagContainer.getItems() != null)
				{
					for (Item item : bagContainer.getItems())
					{
						if (item != null && item.getId() > 0 && item.getQuantity() > 0)
						{
							bagItems.merge(item.getId(), item.getQuantity(), Integer::sum);
						}
					}
				}
				getSnapshotService().setLootingBagContents(bagItems);
				lootingBagInitialized = true;
			}

			ItemContainer quiverContainer = client.getItemContainer(InventoryID.DIZANAS_QUIVER_AMMO);
			if (quiverContainer != null)
			{
				Map<Integer, Integer> quiverItems = new HashMap<>();
				if (quiverContainer.getItems() != null)
				{
					for (Item item : quiverContainer.getItems())
					{
						if (item != null && item.getId() > 0 && item.getQuantity() > 0)
						{
							quiverItems.merge(item.getId(), item.getQuantity(), Integer::sum);
						}
					}
				}
				getSnapshotService().setQuiverAmmoContents(quiverItems);
			}

			previousInventorySnapshot = takeInventorySnapshot(invContainer);
			snapshotInitialized = true;
			log.debug("Baseline taken on startup ({} items)", previousInventorySnapshot.getItems().size());
		}

		ItemContainer equipContainer = client.getItemContainer(InventoryID.WORN);
		if (equipContainer != null)
		{
			previousEquipmentSnapshot = takeSnapshot(equipContainer);
		}
		pendingWornAmmoExpenses.clear();
		recentlyUnequippedItems.clear();
		recentlyEquippedItems.clear();
		gearSwapItemTicks.clear();

		for (Skill skill : Skill.values())
		{
			try
			{
				int xp = client.getSkillExperience(skill);
				previousSkillXp.put(skill, xp);
			}
			catch (Exception ignored)
			{
			}
		}
	}

	/**
	 * Name of the item currently in the weapon slot, or null.
	 */
	String equippedWeaponName()
	{
		if (client == null)
		{
			return null;
		}
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			return null;
		}
		Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (weapon == null)
		{
			return null;
		}
		int weaponId = itemManager != null ? itemManager.canonicalize(weapon.getId()) : weapon.getId();
		return getItemName(weaponId);
	}

	/**
	 * Fraction of consumed blowpipe darts returned by the equipped Ava's device,
	 * or 0 when none is worn.
	 */
	double equippedAvasRecoveryRate()
	{
		if (client == null)
		{
			return 0.0;
		}
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			return 0.0;
		}
		Item cape = worn.getItem(EquipmentInventorySlot.CAPE.getSlotIdx());
		if (cape == null)
		{
			return 0.0;
		}
		int capeId = itemManager != null ? itemManager.canonicalize(cape.getId()) : cape.getId();
		String name = getItemName(capeId);
		if (name == null)
		{
			return 0.0;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.contains("assembler") || lower.contains("dizana's quiver"))
		{
			return 0.80;
		}
		if (lower.contains("accumulator"))
		{
			return 0.72;
		}
		if (lower.contains("attractor"))
		{
			return 0.60;
		}
		return 0.0;
	}

	/**
	 * Checks if the bank or any trading/deposit container widget is visibly open on screen.
	 * Acts as an active real-time safeguard in case an interface event was dropped or delayed.
	 */
	boolean isBankOrContainerOpen()
	{
		if (client == null)
		{
			return false;
		}

		Widget bankWidget = client.getWidget(InterfaceID.Bankmain.TITLE);
		if (bankWidget != null && !bankWidget.isHidden())
		{
			return true;
		}

		Widget depositBox = client.getWidget(InterfaceID.BankDepositbox.FRAME);
		if (depositBox != null && !depositBox.isHidden())
		{
			return true;
		}

		Widget geOffers = client.getWidget(InterfaceID.GeOffers.FRAME);
		if (geOffers != null && !geOffers.isHidden())
		{
			return true;
		}

		Widget tradeMain = client.getWidget(InterfaceID.Trademain.UNIVERSE);
		if (tradeMain != null && !tradeMain.isHidden())
		{
			return true;
		}

		Widget shopMain = client.getWidget(InterfaceID.Shopmain.FRAME);
		if (shopMain != null && !shopMain.isHidden())
		{
			return true;
		}

		return false;
	}

	/**
	 * Converts an ItemContainer into an InventorySnapshot.
	 */
	InventorySnapshot takeSnapshot(ItemContainer container)
	{
		return getSnapshotService().takeSnapshot(container);
	}

	/**
	 * Converts an inventory ItemContainer into an InventorySnapshot,
	 * incorporating any runes currently stored inside a carried Rune Pouch.
	 */
	InventorySnapshot takeInventorySnapshot(ItemContainer invContainer)
	{
		return getSnapshotService().takeInventorySnapshot(invContainer);
	}

	/**
	 * Checks if the inventory container holds any variant of the Rune Pouch.
	 */
	boolean hasRunePouch(ItemContainer invContainer)
	{
		return getSnapshotService().hasRunePouch(invContainer);
	}

	/**
	 * Reads the current runes and quantities stored inside the Rune Pouch via client varbits.
	 */
	Map<Integer, Integer> getRunePouchContents()
	{
		return getSnapshotService().getRunePouchContents();
	}

	/**
	 * Checks if the specified varbit ID corresponds to a Rune Pouch slot quantity or type.
	 */
	boolean isRunePouchVarbit(int varbitId)
	{
		return getSnapshotService().isRunePouchVarbit(varbitId);
	}

	/**
	 * Checks if the inventory container holds any variant of the Looting Bag.
	 */
	boolean hasLootingBag(ItemContainer invContainer)
	{
		return getSnapshotService().hasLootingBag(invContainer);
	}

	/**
	 * Checks if the inventory container holds an open variant of the Looting Bag.
	 */
	boolean hasOpenLootingBag(ItemContainer invContainer)
	{
		return getSnapshotService().hasOpenLootingBag(invContainer);
	}

	/**
	 * Checks if the inventory container holds any variant of a gem container.
	 */
	boolean hasGemBag(ItemContainer invContainer)
	{
		return getSnapshotService().hasGemBag(invContainer);
	}

	/**
	 * Checks if the inventory container holds an open variant of any gem container.
	 */
	boolean hasOpenGemBag(ItemContainer invContainer)
	{
		return getSnapshotService().hasOpenGemBag(invContainer);
	}

	boolean hasOpenHerbSack(ItemContainer invContainer)
	{
		return getSnapshotService().hasOpenHerbSack(invContainer);
	}

	boolean hasOpenFishBarrel(ItemContainer invContainer, ItemContainer wornContainer)
	{
		return getSnapshotService().hasOpenFishBarrel(invContainer, wornContainer);
	}

	boolean hasOpenLogBasket(ItemContainer invContainer, ItemContainer wornContainer)
	{
		return getSnapshotService().hasOpenLogBasket(invContainer, wornContainer);
	}

	boolean hasOpenSeedBox(ItemContainer invContainer)
	{
		return getSnapshotService().hasOpenSeedBox(invContainer);
	}

	boolean hasAshSanctifier(ItemContainer invContainer)
	{
		return getSnapshotService().hasAshSanctifier(invContainer);
	}

	/**
	 * Reads the current items and quantities stored inside the Looting Bag container (ID 516).
	 */
	Map<Integer, Integer> getLootingBagContents()
	{
		return getSnapshotService().getLootingBagContents();
	}

	/**
	 * Checks if the specified varbit ID corresponds to a Master Scroll Book slot.
	 */
	boolean isMasterScrollBookVarbit(int varbitId)
	{
		return getSnapshotService().isMasterScrollBookVarbit(varbitId);
	}

	/**
	 * Checks if the inventory container holds any variant of the Master Scroll Book.
	 */
	boolean hasMasterScrollBook(ItemContainer invContainer)
	{
		return getSnapshotService().hasMasterScrollBook(invContainer);
	}

	/**
	 * Reads the current teleport scrolls and quantities stored inside the Master Scroll Book via varbits.
	 */
	Map<Integer, Integer> getMasterScrollBookContents()
	{
		return getSnapshotService().getMasterScrollBookContents();
	}

	/**
	 * Checks if an item ID corresponds to Dizana's Quiver.
	 */
	boolean isDizanasQuiver(int itemId)
	{
		return InventorySnapshotService.isDizanasQuiver(itemId);
	}

	/**
	 * Checks if the container holds any variant of Dizana's Quiver.
	 */
	boolean hasDizanasQuiver(ItemContainer container)
	{
		return getSnapshotService().hasDizanasQuiver(container);
	}

	/**
	 * Reads the currently cached Dizana's Quiver ammo contents.
	 */
	Map<Integer, Integer> getQuiverAmmoContents()
	{
		return getSnapshotService().getQuiverAmmoContents();
	}

	/**
	 * Returns the pending worn ammo expenses map.
	 */
	Map<Integer, Integer> getPendingWornAmmoExpenses()
	{
		return pendingWornAmmoExpenses;
	}

	synchronized InventorySnapshotService getSnapshotService()
	{
		if (snapshotService == null)
		{
			snapshotService = new InventorySnapshotService(client);
		}
		else if (snapshotService.getClient() == null && client != null)
		{
			snapshotService.setClient(client);
		}
		return snapshotService;
	}

	/**
	 * Drains pending pickups into container gains, deducting any items already counted in inventory,
	 * applying profit gains, and clearing the pending pickups list.
	 *
	 * @param pickups list of pending item pickups for a secondary container (gem bag, herb sack, etc.)
	 */
	void drainPendingContainerPickups(List<LootPickup> pickups)
	{
		if (pickups == null || pickups.isEmpty())
		{
			return;
		}

		Map<Integer, Integer> containerGains = new HashMap<>();
		for (LootPickup pickup : pickups)
		{
			int invGained = invItemsGainedThisTick.getOrDefault(pickup.itemId, 0);
			if (pickup.qty > invGained)
			{
				int containerQty = pickup.qty - invGained;
				containerGains.merge(pickup.itemId, containerQty, Integer::sum);
				invItemsGainedThisTick.put(pickup.itemId, 0);
			}
			else
			{
				invItemsGainedThisTick.put(pickup.itemId, invGained - pickup.qty);
			}
		}

		// Re-picking up the player's own dropped items into an open bag/box/sack
		// must not count as fresh profit — this path bypasses the handler chain.
		com.coinflow.reconciliation.DroppedItemPickupHandler.applyDropReconciliation(
			containerGains, recentlyDroppedItems, recentlyDroppedOwnedItems, itemManager);

		if (!containerGains.isEmpty())
		{
			processGains(containerGains);
		}
		pickups.clear();
	}

	/**
	 * Processes raw item gains: canonicalizes IDs, filters ignored items,
	 * looks up prices, and updates the session.
	 */
	void processGains(Map<Integer, Integer> rawGains)
	{
		processGainsLossesAndExpenses(rawGains, Collections.emptyMap(), Collections.emptyMap(), -1);
	}

	/**
	 * Processes raw item gains and supply expenses atomically:
	 * looks up prices, updates the session, and triggers gold drops for positive gains.
	 */
	void processGainsAndExpenses(Map<Integer, Integer> rawGains, Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses)
	{
		processGainsLossesAndExpenses(rawGains, Collections.emptyMap(), supplyExpenses, -1);
	}

	/**
	 * Processes raw item gains, dropped item deductions, and supply expenses atomically:
	 * looks up prices, updates the session, and triggers gold drops for positive gains.
	 */
	void processGainsLossesAndExpenses(
		Map<Integer, Integer> rawGains,
		Map<Integer, CoinFlowSession.TrackedItem> droppedGainsDeductions,
		Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses)
	{
		processGainsLossesAndExpenses(rawGains, droppedGainsDeductions, supplyExpenses, -1);
	}

	void processGainsLossesAndExpenses(
		Map<Integer, Integer> rawGains,
		Map<Integer, CoinFlowSession.TrackedItem> droppedGainsDeductions,
		Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses,
		int alchedDropItemId)
	{
		processGainsLossesAndExpenses(rawGains, droppedGainsDeductions, supplyExpenses, alchedDropItemId, Collections.emptySet());
	}

	/**
	 * Processes raw item gains, dropped item deductions, and supply expenses atomically:
	 * looks up prices, updates the session, and triggers gold drops for positive gains,
	 * skipping generic coins gold drop if an alchemy drop was already displayed.
	 */
	void processGainsLossesAndExpenses(
		Map<Integer, Integer> rawGains,
		Map<Integer, CoinFlowSession.TrackedItem> droppedGainsDeductions,
		Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses,
		int alchedDropItemId,
		Set<Integer> exemptProductIds)
	{
		Map<Integer, CoinFlowSession.TrackedItem> trackedGains = new HashMap<>();

		for (Map.Entry<Integer, Integer> entry : rawGains.entrySet())
		{
			int rawItemId = entry.getKey();
			int quantity = entry.getValue();

			// Canonicalize: resolve noted items, placeholders, and worn items to base ID (Fixes Bug 6)
			int itemId = itemManager.canonicalize(rawItemId);

			// Check ignored items list
			String itemName = getItemName(itemId);
			if (isIgnored(itemName))
			{
				continue;
			}

			// Skip trash / empty byproduct containers (e.g. pie dish, bowl, empty vial), unless intentionally produced
			if (ConsumableRegistry.isByproduct(itemName) && (exemptProductIds == null || !exemptProductIds.contains(itemId)))
			{
				continue;
			}

			// Look up price (Fixes Bug 7: coins and platinum tokens)
			long price = itemManager.getItemPrice(itemId);
			if (price <= 0)
			{
				if (itemId == ItemID.COINS)
				{
					price = 1;
				}
				else if (itemId == ItemID.PLATINUM)
				{
					price = 1000;
				}
				else
				{
					// Fallback to high alchemy value for untradeable items
					ItemComposition comp = itemManager.getItemComposition(itemId);
					if (comp != null)
					{
						price = comp.getHaPrice();
					}
				}
			}

			CoinFlowSession.TrackedItem trackedItem =
				new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price);
			trackedGains.merge(itemId, trackedItem,
				(existing, added) -> existing.withAdditionalQuantity(added.getQuantity()));

			log.debug("Gained: {} x{} @ {} gp each = {} gp",
				itemName, quantity, price, (long) quantity * price);
		}

		Map<Integer, CoinFlowSession.TrackedItem> effectiveExpenses =
			(config != null && !config.trackSpent()) ? Collections.emptyMap() : supplyExpenses;

		if (!trackedGains.isEmpty() || !droppedGainsDeductions.isEmpty() || !effectiveExpenses.isEmpty())
		{
			session = session.withGainsLossesAndExpenses(trackedGains, droppedGainsDeductions, effectiveExpenses);

			// Gold drops are kept purely for positive income to prevent screen clutter
			if (config.showGoldDrops() && goldDropOverlay != null && !trackedGains.isEmpty())
			{
				int stackIndex = 0;
				for (CoinFlowSession.TrackedItem item : trackedGains.values())
				{
					// If a gold drop was already displayed specifically for an alched item, skip showing coins for it
					if (alchedDropItemId != -1 && item.getItemId() == ItemID.COINS)
					{
						continue;
					}

					long value = item.getTotalValue();
					if (value >= config.goldDropMinThreshold())
					{
						String dropText = "+" + QuantityFormatter.quantityToStackSize(value) + " gp";
						goldDropOverlay.addDrop(dropText, item.getItemId(), stackIndex * 18);
						stackIndex++;
					}
				}
			}
		}
	}

	/**
	 * Gets the display name for an item.
	 */
	private String getItemName(int itemId)
	{
		ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp != null ? comp.getName() : "Unknown";
	}

	/**
	 * Checks if an item name is in the user's ignored items list.
	 */
	private boolean isIgnored(String itemName)
	{
		if (ignoredItemNames == null || ignoredItemNames.isEmpty())
		{
			return false;
		}
		return ignoredItemNames.contains(itemName.toLowerCase());
	}

	/**
	 * Rebuilds the ignored items list from config.
	 */
	void rebuildFilterSet()
	{
		String ignored = config.ignoredItems();
		if (ignored == null || ignored.trim().isEmpty())
		{
			ignoredItemNames = new HashSet<>();
		}
		else
		{
			ignoredItemNames = new HashSet<>();
			for (String name : ignored.split(","))
			{
				String trimmed = name.trim().toLowerCase();
				if (!trimmed.isEmpty())
				{
					ignoredItemNames.add(trimmed);
				}
			}
		}
	}

	/**
	 * Checks if an active goal was reached and triggers a single notification.
	 */
	void checkGoalNotification()
	{
		long goalAmount = CoinFlowSession.parseGoalAmount(config.goalAmount());
		if (goalAmount <= 0)
		{
			goalCompletedNotified = false;
			return;
		}

		long currentProfit = (session != null && config != null && !config.trackSpent())
			? session.getGrossProfit()
			: (session != null ? session.getTotalProfit() : 0L);

		if (session != null && currentProfit >= goalAmount)
		{
			if (!goalCompletedNotified)
			{
				goalCompletedNotified = true;
				if (config.notifyOnGoal())
				{
					String label = CoinFlowSession.cleanGoalName(config.goalName());
					String goalTitle = label.isEmpty() ? "Target GP" : label;
					notifier.notify("Coin Flow: Goal reached for " + goalTitle + " (" + QuantityFormatter.quantityToStackSize(goalAmount) + " gp)!");
				}
			}
		}
	}

	/**
	 * Clears all transient tracking buffers, pending container pickups, click intents, and activity timers.
	 */
	void resetTransientTrackingState()
	{
		recentlyUnequippedItems.clear();
		recentlyEquippedItems.clear();
		gearSwapItemTicks.clear();
		recentlyDroppedItems.clear();
		recentlyDroppedOwnedItems.clear();
		droppedItemTicks.clear();
		recentTakeClicks.clear();
		pendingLootingBagPickups.clear();
		pendingGemBagPickups.clear();
		pendingHerbSackPickups.clear();
		pendingFishBarrelPickups.clear();
		pendingSeedBoxPickups.clear();
		pendingLogBasketPickups.clear();
		invItemsGainedThisTick.clear();
		matchedUnequipsThisTick.clear();
		previousPvpKeyContainers.clear();
		pendingWornAmmoExpenses.clear();
		weaponChargeTracker.reset();
		lastSkillingGemId = -1;
		lastSkillingGemTick = -100;
		if (getSnapshotService() != null)
		{
			getSnapshotService().clearLootingBagPendingItems();
			getSnapshotService().clearQuiverAmmoContents();
		}
		lastSkillXpTicks.clear();
		previousSkillXp.clear();
		lastFiremakingAnimTick = -100;
		lastTinderboxActionTick = -100;
		lastFiremakingChatTick = -100;
		lastAlchAnimTick = -100;
		lastAlchActionTick = -100;
		lastFarmingActionTick = -100;
		lastFarmingAnimTick = -100;
		lastFarmingChatTick = -100;
		lastLootingBagDepositTick = -100;
		lastLootingBagDepositItemId = -1;
		lastLootingBagDepositItemName = null;
		lastNotingServiceTick = -100;
		rebaselineGraceTicks = 0;
		lastPlayerLocation = null;
		lastPlayerActivityTick = -100;
		if (goldDropOverlay != null)
		{
			goldDropOverlay.clear();
		}
	}

	/**
	 * Resets the current session. Called from the panel or keybind.
	 */
	public void resetSession()
	{
		if (log.isDebugEnabled() && session != null)
		{
			log.debug("Session reset: active={}s total={}s gross={} spent={} idle={}",
				session.getActiveTime().getSeconds(), session.getTotalInGameTime().getSeconds(),
				session.getGrossProfit(), session.getTotalExpenses(), session.isIdle());
		}
		session = CoinFlowSession.createNew();
		goalCompletedNotified = false;
		lootingBagInitialized = false;
		resetTransientTrackingState();
		// Invalidate the baseline immediately: any inventory event before the deferred
		// takeBaseline() runs must rebaseline rather than diff against the stale pre-reset snapshot.
		snapshotInitialized = false;
		previousInventorySnapshot = null;
		previousEquipmentSnapshot = null;
		if (client != null && client.getGameState() == GameState.LOGGED_IN)
		{
			if (clientThread != null)
			{
				clientThread.invokeLater(this::takeBaseline);
			}
			else
			{
				takeBaseline();
			}
		}
		if (panel != null)
		{
			panel.updateSession(session);
		}
		log.info("Coin Flow session reset");
	}

	/**
	 * Opens RuneLite's configuration panel directly focused on Coin Flow.
	 */
	public void openConfiguration()
	{
		if (overlay != null && eventBus != null)
		{
			eventBus.post(new OverlayMenuClicked(
				new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, "Configure", "Coin Flow"),
				overlay
			));
		}
	}

	private void removeRecentlyUnequipped(int itemId, int quantity)
	{
		int remaining = quantity;
		Integer direct = recentlyUnequippedItems.get(itemId);
		if (direct != null)
		{
			if (direct <= remaining)
			{
				recentlyUnequippedItems.remove(itemId);
				remaining -= direct;
			}
			else
			{
				recentlyUnequippedItems.put(itemId, direct - remaining);
				remaining = 0;
			}
		}
		if (remaining > 0)
		{
			for (Map.Entry<Integer, Integer> entry : new ArrayList<>(recentlyUnequippedItems.entrySet()))
			{
				int unequippedId = entry.getKey();
				int canonicalId = itemManager != null ? itemManager.canonicalize(unequippedId) : unequippedId;
				if (canonicalId == itemId || unequippedId == itemId)
				{
					int toRemove = Math.min(remaining, entry.getValue());
					if (entry.getValue() <= toRemove)
					{
						recentlyUnequippedItems.remove(unequippedId);
					}
					else
					{
						recentlyUnequippedItems.put(unequippedId, entry.getValue() - toRemove);
					}
					remaining -= toRemove;
					if (remaining <= 0)
					{
						break;
					}
				}
			}
		}
	}
}
