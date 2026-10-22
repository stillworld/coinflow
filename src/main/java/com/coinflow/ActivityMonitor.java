package com.coinflow;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.StatChanged;

/**
 * Player-activity and skill-signal tracking: XP gains, player animations and
 * attack graphics, and the idle heuristic that drives {@code session.tick}.
 * Owns only its private state; the skill-XP maps and animation ticks remain
 * {@link CoinFlowPlugin} fields (visible to tests) and are mutated through
 * the plugin reference.
 */
final class ActivityMonitor
{
	/** Recent per-tick NPC interaction samples — the combat label is the most frequent name. */
	private static final int COMBAT_SIGNAL_TICKS = 50;
	private static final Set<Skill> COMBAT_SKILLS = EnumSet.of(
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED,
		Skill.MAGIC, Skill.HITPOINTS, Skill.SLAYER);

	/** Combat-only skills excluded from the skilling label (Magic stays — alching is skilling). */
	private static final Set<Skill> NEVER_SKILLING = EnumSet.of(
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.HITPOINTS, Skill.SLAYER);

	private final CoinFlowPlugin plugin;
	private WorldPoint lastPlayerLocation;
	private int lastPlayerActivityTick = -100;
	private final Deque<String> recentNpcTargets = new ArrayDeque<>();

	ActivityMonitor(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	/**
	 * Login/hop XP sync window, mirroring core XpTrackerPlugin: the server
	 * pushes every skill's XP as StatChanged in the first ticks after
	 * LOGGING_IN/HOPPING, and the baseline read on LOGGED_IN can predate it —
	 * so the sync would read as a same-tick XP gain in every skill and
	 * classify a phantom skilling segment. Not cleared by {@link #reset()}:
	 * a LOGGED_IN-time reset must not cut the window short.
	 */
	private static final int XP_SYNC_TICKS = 2;
	private int xpSyncTicks;

	void onLoginStarted()
	{
		xpSyncTicks = XP_SYNC_TICKS;
	}

	void onGameTick()
	{
		if (xpSyncTicks > 0 && --xpSyncTicks == 0 && plugin.client != null)
		{
			for (Skill skill : Skill.values())
			{
				plugin.previousSkillXp.put(skill, plugin.client.getSkillExperience(skill));
			}
		}
	}

	void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		int currentXp = event.getXp();
		if (xpSyncTicks > 0)
		{
			plugin.previousSkillXp.put(skill, currentXp);
			return;
		}
		Integer prevXp = plugin.previousSkillXp.get(skill);
		if (prevXp != null && currentXp > prevXp && plugin.client != null)
		{
			plugin.lastSkillXpTicks.put(skill, plugin.client.getTickCount());
			recordPlayerActivity();
		}
		plugin.previousSkillXp.put(skill, currentXp);
	}

	void onAnimationChanged(AnimationChanged event)
	{
		if (plugin.client != null && event.getActor() == plugin.client.getLocalPlayer())
		{
			int anim = plugin.client.getLocalPlayer().getAnimation();
			if (anim != -1 && !ActionClassifiers.isDefensiveAnimation(anim))
			{
				recordPlayerActivity();
			}
			if (ActionClassifiers.isFiremakingAnimation(anim))
			{
				plugin.lastFiremakingAnimTick = plugin.client.getTickCount();
			}
			else if (ActionClassifiers.isFarmingAnimation(anim))
			{
				plugin.lastFarmingAnimTick = plugin.client.getTickCount();
			}
			else if (ActionClassifiers.isAlchemyAnimation(anim))
			{
				plugin.lastAlchAnimTick = plugin.client.getTickCount();
			}
			plugin.weaponChargeTracker.onAttackAnimation(anim, plugin.equippedWeaponName(),
				plugin.client.getTickCount());
		}
	}

	void onGraphicChanged(GraphicChanged event)
	{
		if (plugin.client != null && event.getActor() != null && event.getActor() == plugin.client.getLocalPlayer())
		{
			plugin.weaponChargeTracker.onAttackGraphic(event.getActor().getGraphic(), plugin.equippedWeaponName(),
				plugin.client.getTickCount());
		}
	}

	void recordPlayerActivity()
	{
		if (plugin.client != null)
		{
			lastPlayerActivityTick = plugin.client.getTickCount();
		}
		if (plugin.session != null && plugin.session.isIdle())
		{
			plugin.session = plugin.session.withActivity();
		}
	}

	boolean isPlayerActive()
	{
		if (plugin.client == null || plugin.client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}

		Player localPlayer = plugin.client.getLocalPlayer();
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
		int currentTick = plugin.client.getTickCount();
		int diff = currentTick - lastPlayerActivityTick;
		boolean recentAction = diff >= 0 && diff <= 2;

		// 4. Direct input: mouse moved or key pressed recently (< 50 client cycles / ~1 sec)
		boolean activeInput = false;
		try
		{
			activeInput = plugin.client.getMouseIdleTicks() < 50 || plugin.client.getKeyboardIdleTicks() < 50;
		}
		catch (Throwable ignored)
		{
		}

		return moved || animating || recentAction || activeInput;
	}

	void reset()
	{
		plugin.lastSkillXpTicks.clear();
		plugin.previousSkillXp.clear();
		plugin.lastFiremakingAnimTick = -100;
		plugin.lastAlchAnimTick = -100;
		plugin.lastFarmingAnimTick = -100;
		lastPlayerLocation = null;
		lastPlayerActivityTick = -100;
		recentNpcTargets.clear();
	}

	// ── Activity classification (feeds {@link ActivityTimeline}) ─────────

	/**
	 * Classifies what the player is doing this tick. A null return means "no
	 * signal" — the timeline keeps accumulating into the current segment.
	 * Precedence: AFK > Banking/GE > combat NPC > Blast Furnace > skilling.
	 * Also samples the local player's NPC target into {@link #recentNpcTargets}
	 * so the combat label reflects the most-fought NPC over a rolling window.
	 */
	ActivityTimeline.ActivityClass classify(CoinFlowSession session, int currentTick)
	{
		if (session != null && session.isIdle())
		{
			return ActivityTimeline.ActivityClass.AFK;
		}
		if (plugin.isBankOrContainerOpen())
		{
			return ActivityTimeline.ActivityClass.BANKING;
		}

		Player localPlayer = plugin.client != null ? plugin.client.getLocalPlayer() : null;
		if (localPlayer != null)
		{
			Actor target = localPlayer.getInteracting();
			if (target instanceof NPC)
			{
				String name = ((NPC) target).getName();
				if (name != null)
				{
					recentNpcTargets.addLast(name);
					while (recentNpcTargets.size() > COMBAT_SIGNAL_TICKS)
					{
						recentNpcTargets.pollFirst();
					}
				}
			}
		}

		// Combat requires both an NPC target and recent combat XP — talking to
		// a banker or quest NPC alone must never classify as combat. Magic XP
		// only counts here because the deque is non-empty; alching has no NPC
		// target and falls through to the skilling branch ("Magic").
		if (!recentNpcTargets.isEmpty() && hasRecentCombatXp(currentTick))
		{
			return ActivityTimeline.ActivityClass.combat(mostFrequentNpcTarget());
		}

		// Blast Furnace before skilling so Smithing there gets the better label.
		if (plugin.blastFurnaceTracker.isActive())
		{
			return ActivityTimeline.ActivityClass.BLAST_FURNACE;
		}

		Skill skilling = mostRecentSkillingSkill(currentTick);
		if (skilling != null)
		{
			return ActivityTimeline.ActivityClass.skilling(skilling.getName());
		}
		return null;
	}

	private boolean hasRecentCombatXp(int currentTick)
	{
		for (Map.Entry<Skill, Integer> entry : plugin.lastSkillXpTicks.entrySet())
		{
			if (COMBAT_SKILLS.contains(entry.getKey()) && currentTick - entry.getValue() <= COMBAT_SIGNAL_TICKS)
			{
				return true;
			}
		}
		return false;
	}

	private String mostFrequentNpcTarget()
	{
		Map<String, Integer> counts = new HashMap<>();
		String best = null;
		int bestCount = 0;
		for (String name : recentNpcTargets)
		{
			int count = counts.merge(name, 1, Integer::sum);
			if (count > bestCount)
			{
				bestCount = count;
				best = name;
			}
		}
		return best;
	}

	private Skill mostRecentSkillingSkill(int currentTick)
	{
		Skill best = null;
		int bestTick = currentTick - COMBAT_SIGNAL_TICKS;
		for (Map.Entry<Skill, Integer> entry : plugin.lastSkillXpTicks.entrySet())
		{
			if (!NEVER_SKILLING.contains(entry.getKey()) && entry.getValue() > bestTick)
			{
				bestTick = entry.getValue();
				best = entry.getKey();
			}
		}
		return best;
	}
}
