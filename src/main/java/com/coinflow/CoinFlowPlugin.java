package com.coinflow;

import com.coinflow.reconciliation.InventoryReconciliationEngine;
import com.google.inject.Injector;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.WorldType;
import net.runelite.api.events.AccountHashChanged;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
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

	/**
	 * Tracks local-player deaths: freezes diff tracking until post-respawn
	 * containers settle, charges the loss to a synthetic "Death" expense row,
	 * and reverses it for items reclaimed or picked back up.
	 */
	@Inject
	DeathTracker deathTracker = new DeathTracker();

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
	 * Track ammo, thrown weapons, and crumble-type jewelry removed from WORN that
	 * were not unequipped to INV (e.g. fired bolts, a spent Ring of recoil, or a
	 * Ring of dueling(1) that crumbled on its last charge).
	 */
	final Map<Integer, Integer> pendingWornAmmoExpenses = new HashMap<>();

	/**
	 * Tracks resources consumed inside charged weapons (runes, scales, shards)
	 * via CHARGES_*_QUANTITY varbits, which never produce an inventory diff.
	 */
	final WeaponChargeTracker weaponChargeTracker = new WeaponChargeTracker();

	/**
	 * Tracks Grand Exchange offers and settles buy/sell fills against carried
	 * values (GE cost basis, session tracked gains, or the offer price), since
	 * GE activity is invisible to inventory diffing by design.
	 */
	final GrandExchangeTracker grandExchangeTracker = new GrandExchangeTracker();

	/**
	 * Reconciles inventory diffs while a standard coin shop is open: buys add
	 * cost basis to the shared trade pool, sells settle against carried value.
	 */
	final ShopTracker shopTracker = new ShopTracker(grandExchangeTracker);

	/**
	 * Number of game ticks remaining to suppress diffing for in-flight items
	 * arriving immediately upon or after interface closure (e.g. bank withdrawals).
	 */
	int rebaselineGraceTicks;

	/**
	 * The RuneScape account hash of the account this session belongs to.
	 * Session totals, GE observations/cost basis and the death recovery ledger
	 * are all per-account; a different account logging in resets everything
	 * ({@link SessionLifecycleManager#checkAccount()}). -1 = unknown.
	 */
	long lastAccountHash = -1;

	/**
	 * Latched while the player is on an unsupported world; cleared by
	 * {@link GameTickProcessor} which then resumes the session clock and
	 * schedules a re-baseline.
	 */
	boolean wasUnsupportedWorld;

	/**
	 * World types with a separate economy (or no persistent economy at all).
	 * Items there cannot be priced at main-game GE prices, and their GE
	 * offers would feed the main session's cost basis, so tracking pauses.
	 */
	private static final Set<WorldType> UNSUPPORTED_WORLD_TYPES = Collections.unmodifiableSet(EnumSet.of(
		WorldType.SEASONAL, WorldType.DEADMAN, WorldType.BETA_WORLD, WorldType.TOURNAMENT_WORLD,
		WorldType.FRESH_START_WORLD, WorldType.QUEST_SPEEDRUNNING, WorldType.NOSAVE_MODE));

	/**
	 * True while the player is on a world with a separate economy. Evaluated
	 * live rather than latched at LOGGED_IN: GE offer events can arrive before
	 * the game-state transition finishes, and they must see the new world.
	 */
	boolean isUnsupportedWorld()
	{
		return client != null && client.getWorldType() != null
			&& !Collections.disjoint(client.getWorldType(), UNSUPPORTED_WORLD_TYPES);
	}

	boolean isTrackingSuppressed()
	{
		return interfaceTracker.isTrackingSuppressed() || minigameHudVisible || minigameHudGraceTicks > 0
			|| isUnsupportedWorld();
	}

	/**
	 * Latched visibility of any {@link MinigameQuarantine#MINIGAME_HUD_COMPONENTS}
	 * HUD. Latched in {@link #updateSuppressedRegion()} (runs on every container
	 * event and game tick) so the transition can schedule a re-baseline and an
	 * exit grace window just like {@link InterfaceTracker#SUPPRESSED_REGIONS}.
	 * Evaluated by live widget visibility rather than WidgetLoaded/Closed, so a
	 * group that stays loaded-but-hidden cannot wedge suppression on permanently,
	 * and a missed close event cannot strand it either. Used for minigames on the
	 * normal overworld map (e.g. Tempoross) where region suppression's load halo
	 * would blind nearby players' tracking.
	 */
	boolean minigameHudVisible = false;

	/**
	 * Ticks to keep suppressing after a minigame HUD disappears — the exit strip
	 * (e.g. Tempoross's loaned tools) can land across several container events.
	 */
	int minigameHudGraceTicks = 0;

	private static final int MINIGAME_HUD_EXIT_GRACE_TICKS = 3;

	/**
	 * Removes minigame-only items ({@link MinigameQuarantine#MINIGAME_ONLY_ITEMS})
	 * from a gains/losses diff pair. These items cannot legally exist outside
	 * their minigame, so any diff containing them — including the exit strip
	 * landing outside a suppressed region — is definitionally non-economic.
	 */
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
	 * Re-evaluates region suppression (e.g. the PvP tutorial arena) from the
	 * loaded map regions. Both transitions schedule a re-baseline, and exiting
	 * opens a short suppression window so the loaner strip's trailing removals
	 * are baselined rather than booked as losses.
	 */
	void updateSuppressedRegion()
	{
		if (client == null || client.getTopLevelWorldView() == null)
		{
			return;
		}
		interfaceTracker.updateSuppressedRegion(client.getTopLevelWorldView().getMapRegions());
		updateMinigameHudSuppression();
	}

	/**
	 * Re-evaluates minigame HUD suppression from live widget visibility. Both
	 * transitions schedule a re-baseline, and the HUD disappearing opens a
	 * short grace window so a trailing exit strip (e.g. Tempoross's loaned
	 * tools) is baselined rather than booked as losses.
	 */
	private void updateMinigameHudSuppression()
	{
		boolean visible = false;
		for (int componentId : MinigameQuarantine.MINIGAME_HUD_COMPONENTS)
		{
			Widget hud = client.getWidget(componentId);
			if (hud != null && !hud.isHidden())
			{
				visible = true;
				break;
			}
		}
		if (visible != minigameHudVisible)
		{
			minigameHudVisible = visible;
			setNeedsRebaseline(true);
			if (!visible)
			{
				minigameHudGraceTicks = MINIGAME_HUD_EXIT_GRACE_TICKS;
			}
			log.debug("{} a minigame HUD, {} tracking", visible ? "Opened" : "Closed",
				visible ? "suppressing" : "resuming after grace");
		}
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
	 * Items dropped while "Count Drops as Spent" was active. The drop was charged as a
	 * supply expense; picking it back up reverses that expense instead of crediting a gain.
	 */
	final Map<Integer, Integer> recentlyExpensedDrops = new HashMap<>();

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

	/**
	 * Canonical item id -> tick of the player's most recent "Drop" click. A loss
	 * of that item within {@link #DROP_INTENT_WINDOW_TICKS} is a drop, not a
	 * consumption, and is routed through the own-drop path instead of expensed.
	 */
	final Map<Integer, Integer> recentDropIntents = new HashMap<>();
	static final int DROP_INTENT_WINDOW_TICKS = 2;

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

	final Map<Integer, Integer> invItemsGainedThisTick = new HashMap<>();

	/**
	 * Tracked varbits that threw on getVarbitValue (not varp-backed in this client
	 * build); logged once per ID to avoid spam.
	 */
	final Set<Integer> invalidVarbitIds = new HashSet<>();
	int lastSkillingGemId = -1;
	int lastSkillingGemTick = -100;

	final ActivityMonitor activityMonitor = new ActivityMonitor(this);
	final SessionApplier sessionApplier = new SessionApplier(this);

	final ContainerPickupTracker containerPickupTracker = new ContainerPickupTracker(this);
	final DeathFlowCoordinator deathFlowCoordinator = new DeathFlowCoordinator(this);
	final MenuIntentHandler menuIntentHandler = new MenuIntentHandler(this);
	final ChatMessageHandler chatMessageHandler = new ChatMessageHandler(this);
	final SessionLifecycleManager lifecycleManager = new SessionLifecycleManager(this);
	final InventoryDiffProcessor inventoryDiffProcessor = new InventoryDiffProcessor(this);
	final GameTickProcessor gameTickProcessor = new GameTickProcessor(this);

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
		lastAccountHash = client != null ? client.getAccountHash() : -1;
		wasUnsupportedWorld = false;
		interfaceTracker.reset(client != null && client.getGameState() != null ? client.getGameState() : GameState.UNKNOWN);
		grandExchangeTracker.reset();
		deathTracker.reset();
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
			clientThread.invokeLater(() ->
			{
				takeBaseline();
				grandExchangeTracker.seed(client.getGrandExchangeOffers());
			});
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
		grandExchangeTracker.reset();
		deathTracker.reset();
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
		lifecycleManager.onGameStateChanged(event);
	}

	@Subscribe
	public void onAccountHashChanged(AccountHashChanged event)
	{
		lifecycleManager.checkAccount();
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		deathFlowCoordinator.onActorDeath(event);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		inventoryDiffProcessor.onItemContainerChanged(event);
	}

	void processInventoryChanges(ItemContainer invContainer)
	{
		inventoryDiffProcessor.processInventoryChanges(invContainer);
	}


	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		inventoryDiffProcessor.onVarbitChanged(event);
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (session == null)
		{
			return;
		}

		// Offer events for the new account can arrive before LOGGED_IN —
		// re-key here so they never delta against the previous account.
		lifecycleManager.checkAccount();

		// Special worlds have their own GE/economy; ignore their offers so
		// the main session's observations and basis stay untouched.
		if (isUnsupportedWorld())
		{
			return;
		}

		boolean loggedIn = client != null && client.getGameState() == GameState.LOGGED_IN;
		for (GrandExchangeTracker.GeDelta delta : grandExchangeTracker.onOfferChanged(event.getSlot(), event.getOffer(), loggedIn))
		{
			int itemId = itemManager != null ? itemManager.canonicalize(delta.itemId) : delta.itemId;
			if (itemId <= 0)
			{
				continue;
			}
			if (delta.buy)
			{
				grandExchangeTracker.addBasis(itemId, delta.quantityDelta, delta.coinsDelta);
			}
			else
			{
				applyGeLedger(
					grandExchangeTracker.settleSell(itemId, delta.quantityDelta, delta.coinsDelta, session,
						config.untrackedSalesAsIncome()),
					itemId);
			}
		}
	}

	/**
	 * Applies GE settlement entries to the session and pops a gold drop for the
	 * realized net delta (flip margin, drift correction) when positive.
	 */
	void applyGeLedger(GrandExchangeTracker.GeLedger ledger, int soldItemId)
	{
		sessionApplier.applyGeLedger(ledger, soldItemId);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		boolean wasSuppressed = interfaceTracker.isTrackingSuppressed();
		interfaceTracker.onWidgetLoaded(event.getGroupId());

		// A reclaim interface opened: baseline inv+worn so recovered items can
		// be diffed out when it closes (no-op while the ledger is empty).
		if (InterfaceTracker.DEATH_RETRIEVAL_INTERFACES.contains(event.getGroupId()))
		{
			deathTracker.setRetrievalBaseline(combinedSnapshotItems(), event.getGroupId());
		}

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
		activityMonitor.onStatChanged(event);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		menuIntentHandler.onMenuOptionClicked(event);
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		containerPickupTracker.onItemDespawned(event);
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		activityMonitor.onAnimationChanged(event);
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		activityMonitor.onGraphicChanged(event);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		chatMessageHandler.onChatMessage(event);
	}

	static int findUncutGemIdInMessage(String msg)
	{
		return ChatMessageParser.findUncutGemIdInMessage(msg);
	}

	static int findGrimyHerbIdInMessage(String msg)
	{
		return ChatMessageParser.findGrimyHerbIdInMessage(msg);
	}

	static int findRawFishIdInMessage(String msg)
	{
		return ChatMessageParser.findRawFishIdInMessage(msg);
	}

	static int findSeedIdInMessage(String msg)
	{
		return ChatMessageParser.findSeedIdInMessage(msg);
	}

	static int parseSeedQtyInMessage(String msg)
	{
		return ChatMessageParser.parseSeedQtyInMessage(msg);
	}

	static int parseFishQtyInMessage(String msg)
	{
		return ChatMessageParser.parseFishQtyInMessage(msg);
	}

	static int findLogIdInMessage(String msg)
	{
		return ChatMessageParser.findLogIdInMessage(msg);
	}

	static int parseLogQtyInMessage(String msg)
	{
		return ChatMessageParser.parseLogQtyInMessage(msg);
	}

	void checkPvpKeyContainers()
	{
		containerPickupTracker.checkPvpKeyContainers();
	}

	static boolean isContainerOrChargedItemTarget(String lowerTarget)
	{
		return ActionClassifiers.isContainerOrChargedItemTarget(lowerTarget);
	}

	public static boolean isFarmingPatchAction(String lowerTarget)
	{
		return ActionClassifiers.isFarmingPatchAction(lowerTarget);
	}

	public static boolean isBirdHouseAction(String lowerTarget)
	{
		return ActionClassifiers.isBirdHouseAction(lowerTarget);
	}

	public static boolean isFarmingAnimation(int anim)
	{
		return ActionClassifiers.isFarmingAnimation(anim);
	}

	public static boolean isFiremakingAnimation(int anim)
	{
		return ActionClassifiers.isFiremakingAnimation(anim);
	}

	public static boolean isAlchemyAnimation(int anim)
	{
		return ActionClassifiers.isAlchemyAnimation(anim);
	}

	public static boolean isDefensiveAnimation(int anim)
	{
		return ActionClassifiers.isDefensiveAnimation(anim);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		gameTickProcessor.onGameTick(event);
	}


	void recordPlayerActivity()
	{
		activityMonitor.recordPlayerActivity();
	}

	boolean isPlayerActive()
	{
		return activityMonitor.isPlayerActive();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		lifecycleManager.onConfigChanged(event);
	}

	// ── Internal Logic ───────────────────────────────────────────────────

	/**
	 * Takes a baseline snapshot of the current inventory without triggering a diff.
	 */
	void takeBaseline()
	{
		lifecycleManager.takeBaseline();
	}

	// ── Death tracking ─────────────────────────────────────────────────

	/**
	 * Combined live inventory+equipment contents as canonical itemId -> qty.
	 * The inventory snapshot folds in rune pouch, looting bag, quiver, and
	 * sanctifier contents, so those supplies are captured too.
	 */
	Map<Integer, Integer> combinedSnapshotItems()
	{
		Map<Integer, Integer> map = new HashMap<>();
		if (client == null)
		{
			return map;
		}
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv != null)
		{
			takeInventorySnapshot(inv).getItems()
				.forEach((id, qty) -> map.merge(canonicalize(id), qty, Integer::sum));
		}
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn != null)
		{
			takeSnapshot(worn).getItems()
				.forEach((id, qty) -> map.merge(canonicalize(id), qty, Integer::sum));
		}
		return map;
	}

	int canonicalize(int itemId)
	{
		return itemManager != null ? itemManager.canonicalize(itemId) : itemId;
	}

	/**
	 * Matches gained items against the death ledger, limited to items carrying
	 * a live Take click — the signal that distinguishes picking up your own
	 * death drops from looting a coincidental item of the same id.
	 * Matched quantities are removed from {@code gains} in place.
	 */
	void matchDeathPickupGains(Map<Integer, Integer> gains)
	{
		deathFlowCoordinator.matchDeathPickupGains(gains);
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
		containerPickupTracker.drainPendingContainerPickups(pickups);
	}

	/**
	 * Processes raw item gains: canonicalizes IDs, filters ignored items,
	 * looks up prices, and updates the session.
	 */
	void processGains(Map<Integer, Integer> rawGains)
	{
		sessionApplier.processGains(rawGains);
	}

	/**
	 * Processes raw item gains and supply expenses atomically:
	 * looks up prices, updates the session, and triggers gold drops for positive gains.
	 */
	void processGainsAndExpenses(Map<Integer, Integer> rawGains, Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses)
	{
		sessionApplier.processGainsAndExpenses(rawGains, supplyExpenses);
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
		sessionApplier.processGainsLossesAndExpenses(rawGains, droppedGainsDeductions, supplyExpenses);
	}

	void processGainsLossesAndExpenses(
		Map<Integer, Integer> rawGains,
		Map<Integer, CoinFlowSession.TrackedItem> droppedGainsDeductions,
		Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses,
		int alchedDropItemId)
	{
		sessionApplier.processGainsLossesAndExpenses(rawGains, droppedGainsDeductions, supplyExpenses, alchedDropItemId);
	}

	void processGainsLossesAndExpenses(
		Map<Integer, Integer> rawGains,
		Map<Integer, CoinFlowSession.TrackedItem> droppedGainsDeductions,
		Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses,
		int alchedDropItemId,
		Set<Integer> exemptProductIds)
	{
		sessionApplier.processGainsLossesAndExpenses(rawGains, droppedGainsDeductions, supplyExpenses, alchedDropItemId,
			exemptProductIds);
	}

	/**
	 * Processes raw item gains, dropped item deductions, and supply expenses atomically:
	 * looks up prices, updates the session, and triggers gold drops for positive gains,
	 * skipping generic coins gold drop if an alchemy drop was already displayed.
	 *
	 * @param fractionalExpenseIds expense ids excluded from whole-item cost basis
	 *        repricing: dose/portion expenses and drop expenses
	 */
	void processGainsLossesAndExpenses(
		Map<Integer, Integer> rawGains,
		Map<Integer, CoinFlowSession.TrackedItem> droppedGainsDeductions,
		Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses,
		int alchedDropItemId,
		Set<Integer> exemptProductIds,
		Set<Integer> fractionalExpenseIds)
	{
		sessionApplier.processGainsLossesAndExpenses(rawGains, droppedGainsDeductions, supplyExpenses, alchedDropItemId,
			exemptProductIds, fractionalExpenseIds);
	}

	/**
	 * Gets the display name for an item.
	 */
	String getItemName(int itemId)
	{
		ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp != null ? comp.getName() : "Unknown";
	}

	/**
	 * Checks if an item name is in the user's ignored items list.
	 */
	boolean isIgnored(String itemName)
	{
		return sessionApplier.isIgnored(itemName);
	}

	/**
	 * Rebuilds the ignored items list from config.
	 */
	void rebuildFilterSet()
	{
		sessionApplier.rebuildFilterSet();
	}

	/**
	 * Checks if an active goal was reached and triggers a single notification.
	 */
	void checkGoalNotification()
	{
		sessionApplier.checkGoalNotification();
	}

	/**
	 * Clears all transient tracking buffers, pending container pickups, click intents, and activity timers.
	 */
	void resetTransientTrackingState()
	{
		lifecycleManager.resetTransientTrackingState();
	}

	/**
	 * Resets the current session. Called from the panel or keybind — possibly on
	 * the Swing EDT — so the state mutation is marshalled onto the client
	 * thread, where all other session/track bookkeeping runs.
	 */
	public void resetSession()
	{
		lifecycleManager.resetSession();
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

	/**
	 * True when the diff is exactly "coins out, non-coin items in": the shape of
	 * an NPC dialogue purchase. Any other loss (noting fee, processing input,
	 * consumed supply) disqualifies it so those handlers keep ownership. Coins
	 * exchanged only for platinum tokens is a currency conversion, not a buy.
	 */
	static boolean isCoinsOnlyPurchase(Map<Integer, Integer> rawGains, Map<Integer, Integer> rawLosses)
	{
		return ActionClassifiers.isCoinsOnlyPurchase(rawGains, rawLosses);
	}

	/**
	 * Drops intents older than the window and returns the canonical ids still live.
	 */
	Set<Integer> expireDropIntents(int currentTick)
	{
		recentDropIntents.values().removeIf(t -> currentTick - t > DROP_INTENT_WINDOW_TICKS);
		return new HashSet<>(recentDropIntents.keySet());
	}

	void removeRecentlyUnequipped(int itemId, int quantity)
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
