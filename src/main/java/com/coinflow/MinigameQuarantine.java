package com.coinflow;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;

/**
 * Quarantine rules for minigames that issue temporary items or replace the
 * player's inventory. Two kinds of entries:
 *
 * <ul>
 *   <li>{@link #MINIGAME_ONLY_ITEMS} — item ids that cannot legally exist
 *   outside their minigame (Castle Wars supplies, Barbarian Assault eggs,
 *   NMZ potions, GOTR cells, ...). Any inventory diff containing one is
 *   definitionally non-economic, so it is stripped before reconciliation —
 *   both the grant and the exit strip disappear. Persistent reward items
 *   (fighter torso, Castle Wars tickets/decorative armour, Soul Wars spoils,
 *   guardian essence) are deliberately excluded.</li>
 *   <li>{@link #MINIGAME_HUD_INTERFACES} — interface groups whose HUD is only
 *   visible while inside a minigame that sits on the normal overworld map
 *   (where region suppression's ~2-region load halo would blind nearby
 *   players' tracking). Evaluated by live widget visibility at diff time,
 *   not by WidgetLoaded/Closed events, so groups that stay loaded-but-hidden
 *   cannot wedge tracking off permanently.</li>
 * </ul>
 *
 * If Jagex ever reuses one of these item ids for a real item, that item
 * silently stops being tracked — the ids are minigame-scoped, so this is a
 * low but nonzero risk.
 */
public final class MinigameQuarantine
{
	private MinigameQuarantine() {}

	// ── Minigame-only item ids ─────────────────────────────────────────
	// Anything here is invisible to tracking in every context.
	static final Set<Integer> MINIGAME_ONLY_ITEMS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		// Castle Wars — table supplies, flags, and catapult kit. NOT the
		// tickets, cloaks, or decorative armour (real rewards).
		ItemID.CASTLEWARS_BANDAGES,
		ItemID.CASTLEWARS_BARRICADE,
		ItemID.CASTLEWARS_TOOLKIT,
		ItemID.CASTLEWARS_CATAPULT_ROCK,
		ItemID.CASTLEWARS_EXPLOSIVES_POTION,
		ItemID.CASTLEWARS_CLIMBING_ROPE,
		ItemID.CASTLEWARS_CATAPULT,
		ItemID.CASTLEWARS_SARADOMIN_BANNER,
		ItemID.CASTLEWARS_ZAMORAK_BANNER,
		ItemID.CASTLEWARS_MANUAL, // TODO verify — may persist outside the arena
		// "Castlewars brew" (respawn-room potion) — the gameval constant is
		// misleadingly named *_SKILL_POTION. All four doses.
		ItemID._4DOSE_CASTLEWARS_SKILL_POTION,
		ItemID._3DOSE_CASTLEWARS_SKILL_POTION,
		ItemID._2DOSE_CASTLEWARS_SKILL_POTION,
		ItemID._1DOSE_CASTLEWARS_SKILL_POTION,
		// Temporary "Rune pouch" issued on entry (Poll 83 update) — provides
		// free runes in-arena and is stripped on exit.
		ItemID.CASTLEWARS_RUNE_REPLACEMENT,
		// Arena ammunition (tables/issue) — CW-only variants of arrows/bolts.
		ItemID.CASTLEWARS_ARROW,
		ItemID.CASTLEWARS_BOLT,
		ItemID.CASTLEWARS_ARROWS2,
		ItemID.CASTLEWARS_ARROWS3,
		ItemID.CASTLEWARS_ARROWS4,
		ItemID.CASTLEWARS_ARROWS5,
		ItemID.CASTLEWARS_BOLTS2,
		ItemID.CASTLEWARS_BOLTS3,
		ItemID.CASTLEWARS_BOLTS4,
		ItemID.CASTLEWARS_BOLTS5,
		ItemID.CASTLEWARS_NO0,
		ItemID.CASTLEWARS_NO1,
		ItemID.CASTLEWARS_NO2,
		ItemID.CASTLEWARS_NO3,
		ItemID.CASTLEWARS_NO4,
		ItemID.CASTLEWARS_NO5,
		ItemID.CASTLEWARS_NO6,
		ItemID.CASTLEWARS_NO7,
		ItemID.CASTLEWARS_NO8,
		ItemID.CASTLEWARS_NO9,

		// Soul Wars — bandages, buff potions, barricades, explosives, team
		// capes (worn, which is why the WORN diff is filtered too), and the
		// tutorial-side items. NOT spoils of war (real reward crates).
		ItemID.SOUL_WARS_BANDAGES,
		ItemID.SOUL_WARS_BUFF_POTION_1,
		ItemID.SOUL_WARS_BUFF_POTION_2,
		ItemID.SOUL_WARS_BUFF_POTION_3,
		ItemID.SOUL_WARS_BUFF_POTION_4,
		ItemID.SOUL_WARS_RED_CAPE,
		ItemID.SOUL_WARS_BLUE_CAPE,
		ItemID.SOUL_WARS_BARRICADE_RED,
		ItemID.SOUL_WARS_BARRICADE_BLUE,
		ItemID.SOUL_WARS_EXPLOSIVE_POTION,
		ItemID.SOUL_WARS_BONES, // TODO verify — soul fragments; may leave the game
		ItemID.SOUL_WARS_TUT_BLUE_CAPE,
		ItemID.SOUL_WARS_TUT_SOUL_FRAGMENT,
		ItemID.SOUL_WARS_TUT_BOOK,

		// Barbarian Assault — role horns/bags, eggs, poison kits, healer
		// vials, and runner bait. NOT BARBASSAULT_PENANCE_* (real rewards).
		ItemID.BARBASSAULT_RUNNER_FOOD,
		ItemID.BARBASSAULT_RUNNER_FOOD2,
		ItemID.BARBASSAULT_RUNNER_FOOD3,
		ItemID.BARBASSAULT_ATT_HORN_01,
		ItemID.BARBASSAULT_ATT_HORN_02,
		ItemID.BARBASSAULT_ATT_HORN_03,
		ItemID.BARBASSAULT_ATT_HORN_04,
		ItemID.BARBASSAULT_ATT_HORN_05,
		ItemID.BARBASSAULT_COLL_BAG_01,
		ItemID.BARBASSAULT_COLL_BAG_02,
		ItemID.BARBASSAULT_COLL_BAG_03,
		ItemID.BARBASSAULT_COLL_BAG_04,
		ItemID.BARBASSAULT_COLL_BAG_05,
		ItemID.BARBASSAULT_HEAL_HORN_01,
		ItemID.BARBASSAULT_HEAL_HORN_02,
		ItemID.BARBASSAULT_HEAL_HORN_03,
		ItemID.BARBASSAULT_HEAL_HORN_04,
		ItemID.BARBASSAULT_HEAL_HORN_05,
		ItemID.BARBASSAULT_DEFENDER_HORN,
		ItemID.BARBASSAULT_HORN_COLLECTOR,
		ItemID.BARBASSAULT_EGG_01,
		ItemID.BARBASSAULT_EGG_02,
		ItemID.BARBASSAULT_EGG_03,
		ItemID.BARBASSAULT_EGG_04,
		ItemID.BARBASSAULT_EGG_POISON,
		ItemID.BARBASSAULT_EGG_POISON_SPIKES,
		ItemID.BARBASSAULT_EGG_HEATED_POISON_SPIKES,
		ItemID.BARBASSAULT_EGG_BLACKED_OUT,
		ItemID.BARBASSAULT_POISION_01,
		ItemID.BARBASSAULT_POISION_02,
		ItemID.BARBASSAULT_POISION_03,
		ItemID.BARBASSAULT_VIAL_04_FULL,
		ItemID.BARBASSAULT_VIAL_04_3Q,
		ItemID.BARBASSAULT_VIAL_04_HALF,
		ItemID.BARBASSAULT_VIAL_04_1Q,
		ItemID.BARBASSAULT_VIAL_04_EMPTY,
		ItemID.BARBASSAULT_SPIKES,
		ItemID.BARBASSAULT_SCROLL, // TODO verify — may persist outside the waves
		ItemID.BARBASSAULT_QUEEN_GUIDEBOOK, // TODO verify — may persist outside the waves

		// Guardians of the Rift — cells crumble when leaving the Temple of
		// the Eye. Guardian essence persists and stays tracked.
		ItemID.GOTR_CELL_UNCHARGED,
		ItemID.GOTR_CELL_TIER1,
		ItemID.GOTR_CELL_TIER2,
		ItemID.GOTR_CELL_TIER3,
		ItemID.GOTR_CELL_TIER4,

		// Nightmare Zone — rumble potions usable only inside a dream.
		ItemID.NZONE1DOSEOVERLOADPOTION,
		ItemID.NZONE2DOSEOVERLOADPOTION,
		ItemID.NZONE3DOSEOVERLOADPOTION,
		ItemID.NZONE4DOSEOVERLOADPOTION,
		ItemID.NZONE1DOSEABSORPTIONPOTION,
		ItemID.NZONE2DOSEABSORPTIONPOTION,
		ItemID.NZONE3DOSEABSORPTIONPOTION,
		ItemID.NZONE4DOSEABSORPTIONPOTION,

		// Tithe Farm — provided seeds, fruit, and fertiliser never leave the
		// instance. Reward gear (TITHE_REWARD_*) stays tracked.
		ItemID.HOSIDIUS_TITHE_SEED_A,
		ItemID.HOSIDIUS_TITHE_SEED_B,
		ItemID.HOSIDIUS_TITHE_SEED_C,
		ItemID.HOSIDIUS_TITHE_FRUIT_A,
		ItemID.HOSIDIUS_TITHE_FRUIT_B,
		ItemID.HOSIDIUS_TITHE_FRUIT_C,
		ItemID.HOSIDIUS_TITHE_FERTILISER // TODO verify — may persist outside
	)));

	// ── Minigame HUD interface groups ──────────────────────────────────
	// While one of these is visible the player is inside an overworld-area
	// minigame where region suppression would bleed onto neighbours.
	// TODO verify each group only shows while actually inside the minigame
	// (watch for "Interface opened" debug lines appearing at login — that
	// would mean the group is persistently loaded and cannot be trusted even
	// with the visibility check, since isHidden() may report stale state).
	static final Set<Integer> MINIGAME_HUD_INTERFACES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		// Tempoross — tools are real item ids, so only a HUD/region signal
		// can quarantine them; the cove shares map space with Unkah's docks.
		InterfaceID.TEMPOROSS_HUD,
		InterfaceID.TEMPOROSS_LOBBY_HUD,
		// The Gauntlet — belt-and-suspenders alongside the region entry; the
		// HUD is what actually quarantines if the seeded region id is wrong.
		InterfaceID.GAUNTLET_OVERLAY,
		InterfaceID.GAUNTLET_MAP,
		// Last Man Standing — legacy WidgetID groups (gameval has no LMS
		// constants). LMS_GROUP_ID is the lobby panel, LMS_INGAME_GROUP_ID
		// the in-match HUD. Verified in game: the lobby HUD latches on every
		// lobby login and suppression persists through the whole match. This
		// is the primary LMS coverage — the lobby's region halo shares real
		// Ferox Enclave chunks, and matches rotate between several arena
		// maps, so neither end can be fully covered by region ids.
		net.runelite.api.widgets.WidgetID.LMS_GROUP_ID,
		net.runelite.api.widgets.WidgetID.LMS_INGAME_GROUP_ID
		// Candidates pending in-game capture: SOUL_WARS_GAME (375),
		// BARBASSAULT_OVER_* (485-488), CASTLEWARS_STATUS_OVERLAY_* (58/59).
		// Those minigames are already covered by the item denylist, so HUD
		// suppression adds little — enable only if gaps appear.
	)));

	static boolean isMinigameOnlyItem(int canonicalId)
	{
		return MINIGAME_ONLY_ITEMS.contains(canonicalId);
	}
}
