package com.coinflow;

import net.runelite.api.GameState;
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
	private final CoinFlowPlugin plugin;
	private WorldPoint lastPlayerLocation;
	private int lastPlayerActivityTick = -100;

	ActivityMonitor(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		int currentXp = event.getXp();
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
	}
}
