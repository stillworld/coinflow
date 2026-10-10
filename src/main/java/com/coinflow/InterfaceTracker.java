package com.coinflow;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;

/**
 * Tracks open in-game interfaces (bank, GE, shops, etc.) to suppress inventory tracking
 * and signal re-baselines upon interface closures or scene transitions.
 */
@Slf4j
@Singleton
public class InterfaceTracker
{
	// ── Interface IDs that suppress tracking ─────────────────────────────
	static final Set<Integer> SUPPRESSED_INTERFACES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		// Bank & Deposit Boxes
		InterfaceID.BANKMAIN,
		InterfaceID.BANKSIDE,
		InterfaceID.BANKPIN_KEYPAD,
		InterfaceID.BANKPIN_SETTINGS,
		InterfaceID.BANK_SIDE_LOCKS,
		InterfaceID.BANK_DEPOSITBOX,
		InterfaceID.BANK_DEPOSIT_IMP,
		InterfaceID.SHARED_BANK,
		InterfaceID.SHARED_BANK_SIDE,
		InterfaceID.GIM_SHARED_BANK_UNLOCKS,
		InterfaceID.CAMDOZAAL_VAULT,
		InterfaceID.ZMI_BANK_PAYMENT,

		// Grand Exchange & Price Checker
		InterfaceID.GE_OFFERS,
		InterfaceID.GE_OFFERS_SIDE,
		InterfaceID.GE_PRICECHECKER,
		InterfaceID.GE_PRICECHECKER_SIDE,
		InterfaceID.GE_PRICELIST,
		// Item sets exchange — the clerk's "Sets" interface is the only way to
		// pack/unpack armour sets. The diff (-1 set, +4 components, or the
		// reverse) lands while it is open; without suppression the components
		// book as phantom profit (observed: a Dharok's unbox logged +3.6m).
		InterfaceID.ITEMSETS,
		InterfaceID.ITEMSETS_SIDE,

		// Trades & Shops
		InterfaceID.TRADEMAIN,
		InterfaceID.TRADESIDE,
		InterfaceID.TRADECONFIRM,
		InterfaceID.OMNISHOP_MAIN,
		InterfaceID.OMNISHOP_SIDE,
		InterfaceID.PVP_STORE,
		InterfaceID.PVP_STORE_SIDE,

		// Storage, Safes & Containers
		InterfaceID.SEED_VAULT,
		InterfaceID.SEED_VAULT_DEPOSIT,
		InterfaceID.DEADMAN_SAFEBOX,
		InterfaceID.DEADMAN_SAFEBOX_SIDE,
		InterfaceID.DEATHKEEP,
		InterfaceID.DEATH_COFFER,
		InterfaceID.DEATH_COFFER_SIDE,
		InterfaceID.GRAVESTONE_RETRIEVAL,
		InterfaceID.GRAVESTONE_GENERIC,
		InterfaceID.DEATH_OFFICE,
		InterfaceID.RAIDS_STORAGE_PRIVATE,
		InterfaceID.RAIDS_STORAGE_SHARED,
		InterfaceID.RAIDS_STORAGE_SIDE,
		InterfaceID.CLANS_STORAGE_MAIN,
		InterfaceID.CLANS_STORAGE_SIDE,
		InterfaceID.FOSSIL_STORAGE,
		InterfaceID.FOSSIL_STORAGE_INV,
		InterfaceID.POH_COSTUMES_SIDE,
		InterfaceID.HOSIDIUS_SEEDBOX,
		InterfaceID.RUNE_POUCH,
		InterfaceID.PVP_ARENA_RUNEPOUCH,
		InterfaceID.TACKLE_BOX_MAIN,
		InterfaceID.TACKLE_BOX_SIDE,
		InterfaceID.II_ELNOCK_STORAGE,
		InterfaceID.II_ELNOCK_STORAGE_SIDE,
		InterfaceID.FORESTRY_KIT_MAIN,
		InterfaceID.FORESTRY_KIT_SIDE,

		// PvP Loot Chests
		InterfaceID.WILDY_LOOT_CHEST,
		InterfaceID.DEADMANLOOT
	)));

	// ── Map regions that suppress tracking ───────────────────────────────
	// IMPORTANT: only instanced or isolated template regions belong here.
	// WorldView#getMapRegions returns every loaded region (~a 2-3 region
	// halo around the player), so suppressing a real overworld region would
	// also blind tracking for players merely standing near the minigame
	// (e.g. Tithe Farm's region 7222 covers the Hosidius vinery — it is
	// deliberately NOT here; its provided items are quarantined via
	// MinigameQuarantine instead). Minigames on the overworld map that need
	// quarantine use MinigameQuarantine.MINIGAME_HUD_INTERFACES.
	//
	// The PvP tutorial arena (Pete Kayer, entered from Ferox Enclave) issues
	// loaner gear and supplies on entry and strips them on exit — none of it
	// is real profit or spend. Client#getMapRegions returns template region
	// ids, so this matches whether the arena is instanced or not.
	static final Set<Integer> SUPPRESSED_REGIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		10588, // PvP tutorial arena (Pete Kayer, Ferox Enclave)
		7512,  // The Gauntlet / Corrupted Gauntlet dungeon — verified in game:
		       // the instance loads as the single template region [7512].
		       // GAUNTLET_OVERLAY/GAUNTLET_MAP HUD suppression covers it too.

		// Last Man Standing match island — verified in game: loads as a
		// 3x2 block far east of the map with no overworld regions in the
		// halo. An in-match death booked a real "Death" row before these
		// were added. LMS uses MULTIPLE arena maps — a later match loaded
		// [13658, 13659, 13914, 13915], which sits inside the Wilderness
		// bounding box and cannot be confirmed isolated, so it is NOT
		// here; the LMS HUD groups (MinigameQuarantine) suppress matches
		// on every map variant regardless.
		14174, 14175, 14176,
		14430, 14431, 14432
		// The LMS lobby is deliberately NOT here: it sits north-east of
		// Ferox Enclave, and its loaded halo [12344, 12345, 12600, 12601]
		// contains the real Ferox/Wilderness overworld chunks — suppressing
		// them would blind tracking for anyone standing in the enclave.
		// Lobby suppression is HUD-based (MinigameQuarantine) instead.

		// TODO in-game capture (see the "Map regions" debug line):
		//   - Stealing Creation arena regions
	)));

	/**
	 * Ticks to keep suppressing after leaving a suppressed region. The
	 * tutorial's exit strip can land across several events and containers
	 * (worn, inv, rune pouch varbits) over a couple of ticks — a single-shot
	 * re-baseline is consumed by the first event, so trailing removals would
	 * leak through as supply expenses.
	 */
	private static final int SUPPRESSED_REGION_EXIT_GRACE_TICKS = 3;

	// ── Death reclaim interface IDs ──────────────────────────────────────
	// Subset of SUPPRESSED_INTERFACES where death-loss recoveries happen.
	// Baselines are taken on open and settled on GameTick once none of these
	// remain open, because scene loads can clear the suppressed set without a
	// WidgetClosed event.
	static final Set<Integer> DEATH_RETRIEVAL_INTERFACES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		InterfaceID.GRAVESTONE_RETRIEVAL,
		InterfaceID.GRAVESTONE_GENERIC,
		InterfaceID.DEATH_OFFICE
	)));

	// ── Coin-shop interface IDs tracked as an open-shop state ────────────
	// Standard NPC shops transact in coins and produce usable inventory
	// diffs, so they are NOT suppressed: diffs are routed to ShopTracker
	// instead. Omnishop/reward-shop interfaces stay suppressed since many
	// use non-coin currencies or points.
	static final Set<Integer> SHOP_INTERFACES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		InterfaceID.SHOPMAIN,
		InterfaceID.SHOPSIDE
	)));

	// ── Side interface IDs that represent inventory side panels ──────────
	static final Set<Integer> SIDE_INTERFACES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		InterfaceID.BANKSIDE,
		InterfaceID.BANK_SIDE_LOCKS,
		InterfaceID.SHARED_BANK_SIDE,
		InterfaceID.GE_OFFERS_SIDE,
		InterfaceID.GE_PRICECHECKER_SIDE,
		InterfaceID.TRADESIDE,
		InterfaceID.SHOPSIDE,
		InterfaceID.OMNISHOP_SIDE,
		InterfaceID.PVP_STORE_SIDE,
		InterfaceID.SEED_VAULT_DEPOSIT,
		InterfaceID.DEADMAN_SAFEBOX_SIDE,
		InterfaceID.DEATH_COFFER_SIDE,
		InterfaceID.RAIDS_STORAGE_SIDE,
		InterfaceID.CLANS_STORAGE_SIDE,
		InterfaceID.FOSSIL_STORAGE_INV,
		InterfaceID.POH_COSTUMES_SIDE,
		InterfaceID.TACKLE_BOX_SIDE,
		InterfaceID.II_ELNOCK_STORAGE_SIDE,
		InterfaceID.FORESTRY_KIT_SIDE
	)));

	@Getter
	private final Set<Integer> openSuppressedInterfaces = new HashSet<>();

	@Getter
	private final Set<Integer> openShopInterfaces = new HashSet<>();

	@Setter
	private boolean trackingSuppressed = false;

	@Getter
	private boolean inSuppressedRegion = false;

	private int suppressedRegionGraceTicks = 0;

	// Signature of the last seen map-region set — used to log region
	// transitions once per scene load so new quarantine regions can be
	// captured in game from the debug log.
	private int lastMapRegionsSignature = 0;

	// Most recently closed suppressed interface group. A set exchange can
	// deliver its inventory packet a tick after the Sets interface closes,
	// landing in the re-baseline path — this lets that diff still carry
	// cost basis across the conversion. The record only stays valid for
	// {@link #LAST_CLOSED_GROUP_VALID_TICKS}: without a bound, a stale record
	// would let any later re-baseline window containing a "... set" item
	// trigger a bogus cost-basis transfer.
	private int lastClosedSuppressedGroupId = -1;

	// Ticks the last-closed suppressed group stays identifiable. A late
	// conversion packet lands within a tick or two of WidgetClosed, inside
	// the ~2-tick re-baseline window armed on close.
	private static final int LAST_CLOSED_GROUP_VALID_TICKS = 3;

	private int lastClosedSuppressedGroupAge = LAST_CLOSED_GROUP_VALID_TICKS + 1;

	@Getter
	@Setter
	private boolean needsRebaseline = false;

	@Getter
	@Setter
	private GameState previousGameState = GameState.UNKNOWN;

	@Inject
	public InterfaceTracker() {}

	/**
	 * Resets all interface tracking and suppression states (e.g., on startup or shutdown).
	 */
	public void reset(GameState currentGameState)
	{
		openSuppressedInterfaces.clear();
		openShopInterfaces.clear();
		trackingSuppressed = false;
		inSuppressedRegion = false;
		suppressedRegionGraceTicks = 0;
		lastMapRegionsSignature = 0;
		lastClosedSuppressedGroupId = -1;
		lastClosedSuppressedGroupAge = LAST_CLOSED_GROUP_VALID_TICKS + 1;
		needsRebaseline = false;
		previousGameState = currentGameState != null ? currentGameState : GameState.UNKNOWN;
	}

	/**
	 * True while tracking is suppressed — either an interface from
	 * {@link #SUPPRESSED_INTERFACES} is open or the player is inside a
	 * {@link #SUPPRESSED_REGIONS} region (e.g. the PvP tutorial arena), or
	 * within {@link #SUPPRESSED_REGION_EXIT_GRACE_TICKS} of leaving one.
	 */
	public boolean isTrackingSuppressed()
	{
		return trackingSuppressed || inSuppressedRegion || suppressedRegionGraceTicks > 0;
	}

	/**
	 * Re-evaluates region suppression from the currently loaded map regions.
	 * Called on container events and game ticks so the check is applied at
	 * diff time, not on scene-load ordering. Both transitions schedule a
	 * re-baseline: the entry grant and the exit strip must be adopted as the
	 * new baseline rather than diffed as gains/losses. Exiting additionally
	 * opens a short suppression window that survives multiple events.
	 */
	public void updateSuppressedRegion(int[] mapRegions)
	{
		if (mapRegions != null)
		{
			int signature = Arrays.hashCode(mapRegions);
			if (signature != lastMapRegionsSignature)
			{
				lastMapRegionsSignature = signature;
				log.debug("Map regions: {}", Arrays.toString(mapRegions));
			}
		}
		boolean inside = false;
		if (mapRegions != null)
		{
			for (int region : mapRegions)
			{
				if (SUPPRESSED_REGIONS.contains(region))
				{
					inside = true;
					break;
				}
			}
		}
		if (inside != inSuppressedRegion)
		{
			inSuppressedRegion = inside;
			needsRebaseline = true;
			if (!inside)
			{
				suppressedRegionGraceTicks = SUPPRESSED_REGION_EXIT_GRACE_TICKS;
			}
			log.debug("{} a suppressed region, will re-baseline on next inventory event",
				inside ? "Entered" : "Exited");
		}
	}

	/**
	 * Ages the post-exit region suppression window; call once per game tick.
	 */
	public void onGameTick()
	{
		if (suppressedRegionGraceTicks > 0)
		{
			suppressedRegionGraceTicks--;
		}
		if (lastClosedSuppressedGroupAge <= LAST_CLOSED_GROUP_VALID_TICKS)
		{
			lastClosedSuppressedGroupAge++;
		}
	}

	/**
	 * Returns true while a standard coin shop interface is open. Shop diffs are
	 * routed to ShopTracker rather than suppressed or passed to the normal
	 * reconciliation pipeline.
	 */
	public boolean isShopOpen()
	{
		return !openShopInterfaces.isEmpty();
	}

	/**
	 * Returns true while any death reclaim interface (gravestone, Death's
	 * Office) is registered as open.
	 */
	public boolean isDeathRetrievalOpen()
	{
		for (int groupId : DEATH_RETRIEVAL_INTERFACES)
		{
			if (openSuppressedInterfaces.contains(groupId))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * True while the Grand Exchange clerk's item-sets interface is open —
	 * the only path for packing/unpacking sets, so inventory diffs in this
	 * window are set↔component conversions. The side panel can linger after
	 * the main interface, so either group counts.
	 */
	public boolean isItemsetsOpen()
	{
		return openSuppressedInterfaces.contains(InterfaceID.ITEMSETS)
			|| openSuppressedInterfaces.contains(InterfaceID.ITEMSETS_SIDE);
	}

	/**
	 * True if the most recently closed suppressed interface was the
	 * item-sets interface — used to catch a conversion diff that arrives
	 * one tick after the interface closed.
	 */
	public boolean wasLastClosedGroupItemsets()
	{
		return lastClosedSuppressedGroupAge <= LAST_CLOSED_GROUP_VALID_TICKS
			&& (lastClosedSuppressedGroupId == InterfaceID.ITEMSETS
			|| lastClosedSuppressedGroupId == InterfaceID.ITEMSETS_SIDE);
	}

	public void onWidgetLoaded(int groupId)
	{
		if (groupId == InterfaceID.INVENTORY)
		{
			// Returning to regular inventory tab clears any leftover side-panel interfaces
			boolean wasShopOpen = !openShopInterfaces.isEmpty();
			openSuppressedInterfaces.removeAll(SIDE_INTERFACES);
			openShopInterfaces.removeAll(SIDE_INTERFACES);
			if (openSuppressedInterfaces.isEmpty() && trackingSuppressed)
			{
				trackingSuppressed = false;
				needsRebaseline = true;
				log.debug("Inventory tab restored, all suppressed interfaces closed; will re-baseline");
			}
			if (wasShopOpen && openShopInterfaces.isEmpty())
			{
				needsRebaseline = true;
				log.debug("Shop side panel cleared with inventory tab; will re-baseline");
			}
			return;
		}

		if (SHOP_INTERFACES.contains(groupId))
		{
			openShopInterfaces.add(groupId);
			log.debug("Shop interface opened (group {}), routing diffs to shop tracking", groupId);
			return;
		}

		if (SUPPRESSED_INTERFACES.contains(groupId))
		{
			openSuppressedInterfaces.add(groupId);
			trackingSuppressed = true;
			log.debug("Interface opened (group {}), suppressing tracking", groupId);
		}
	}

	public void onWidgetClosed(int groupId)
	{
		if (openSuppressedInterfaces.remove(groupId))
		{
			lastClosedSuppressedGroupId = groupId;
			lastClosedSuppressedGroupAge = 0;
			if (openSuppressedInterfaces.isEmpty())
			{
				trackingSuppressed = false;
				needsRebaseline = true;
				log.debug("All suppressed interfaces closed, will re-baseline on next inventory event");
			}
		}

		// No re-baseline on shop close: the shop branch advances the inventory
		// snapshot on every tick while open, so the baseline is already current.
		// Scheduling one here would swallow the first real post-shop diff (e.g.
		// a drop), leaving the subsequent pickup to be counted as new loot.
		if (openShopInterfaces.remove(groupId) && openShopInterfaces.isEmpty())
		{
			log.debug("Shop interface closed, resuming normal inventory tracking");
		}
	}

	public void onGameStateChanged(GameState state, boolean snapshotInitialized)
	{
		if (state == GameState.LOGGED_IN)
		{
			if (previousGameState == GameState.UNKNOWN
				|| previousGameState == GameState.LOGIN_SCREEN
				|| previousGameState == GameState.LOGIN_SCREEN_AUTHENTICATOR
				|| previousGameState == GameState.HOPPING
				|| previousGameState == GameState.LOGGING_IN
				|| previousGameState == GameState.CONNECTION_LOST
				|| !snapshotInitialized)
			{
				needsRebaseline = true;
				log.debug("Game state -> LOGGED_IN (from {}), will re-baseline on next inventory event", previousGameState);
			}
			else
			{
				log.debug("Game state -> LOGGED_IN (from {}), preserving inventory snapshot across scene load", previousGameState);
			}

			openSuppressedInterfaces.clear();
			openShopInterfaces.clear();
			trackingSuppressed = false;
			lastClosedSuppressedGroupId = -1;
			lastClosedSuppressedGroupAge = LAST_CLOSED_GROUP_VALID_TICKS + 1;
		}
		else if (state == GameState.LOADING)
		{
			log.debug("Game state -> LOADING (from {}), maintaining session tracking state", previousGameState);
		}
		else
		{
			needsRebaseline = true;
			openSuppressedInterfaces.clear();
			openShopInterfaces.clear();
			trackingSuppressed = false;
			lastClosedSuppressedGroupId = -1;
			lastClosedSuppressedGroupAge = LAST_CLOSED_GROUP_VALID_TICKS + 1;
			log.debug("Game state -> {}, pausing tracking", state);
		}

		previousGameState = state;
	}

	public void onActorDeath()
	{
		needsRebaseline = true;
		log.debug("Local player died, will re-baseline inventory upon respawn");
	}
}
