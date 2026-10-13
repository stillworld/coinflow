package com.coinflow;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.InventoryID;

/**
 * Session lifecycle: login/logout transitions, baselining, session resets,
 * transient-state clears, and config-change sanitization. All mutations run
 * on the client thread (resetSession marshals itself there when called from
 * the Swing EDT).
 */
@Slf4j
final class SessionLifecycleManager
{
	private final CoinFlowPlugin plugin;

	SessionLifecycleManager(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		plugin.interfaceTracker.onGameStateChanged(state, plugin.snapshotInitialized);

		if (state == GameState.LOGGED_IN)
		{
			checkAccount();
			if (plugin.session != null)
			{
				plugin.session = plugin.session.withResumedState();
			}
			if (plugin.interfaceTracker.isNeedsRebaseline() && plugin.client != null && plugin.clientThread != null)
			{
				plugin.clientThread.invokeLater(() ->
				{
					plugin.takeBaseline();
					plugin.interfaceTracker.setNeedsRebaseline(false);
				});
			}
		}
		else if (state != GameState.LOADING)
		{
			plugin.snapshotInitialized = false;
			plugin.lootingBagInitialized = false;
			// Logout/hop while a death is still settling: discard the pending
			// capture rather than risk mispricing the loss. The recovery
			// ledger survives — reclaiming often happens after a relog.
			plugin.deathTracker.discardPending();
			resetTransientTrackingState();
		}
	}

	/**
	 * Sessions, GE cost basis and the death recovery ledger belong to one
	 * account. A different account logging in resets everything so account
	 * A's observations can never book fills or recoveries for account B.
	 * Called from the account-hash event, on LOGGED_IN, and at the top of
	 * {@link CoinFlowPlugin#onGrandExchangeOfferChanged} — offer events can
	 * arrive before the game-state transition finishes. Idempotent; a hash
	 * of -1 (logout / not yet identified) is ignored.
	 */
	void checkAccount()
	{
		if (plugin.client == null)
		{
			return;
		}
		long hash = plugin.client.getAccountHash();
		if (hash == -1)
		{
			return;
		}
		if (plugin.lastAccountHash != -1 && hash != plugin.lastAccountHash)
		{
			log.info("Account switched, resetting Coin Flow session");
			resetSession();
		}
		plugin.lastAccountHash = hash;
	}

	void onConfigChanged(ConfigChanged event)
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
						plugin.configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goldDropMinThreshold", safeVal);
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
						plugin.configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "idleTimeoutMinutes", safeVal);
					}
				}
			}
			else if ("goalAmount".equals(event.getKey()))
			{
				plugin.goalCompletedNotified = false;
				String val = event.getNewValue();
				if (val != null)
				{
					String sanitized = val.replaceAll(CoinFlowInputFilter.TARGET_GP, "");
					if (!sanitized.equals(val))
					{
						plugin.configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalAmount", sanitized);
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
						plugin.configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalName", sanitized);
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
						plugin.configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "ignoredItems", sanitized);
					}
				}
			}

			if ("showGoldDrops".equals(event.getKey()) && !plugin.config.showGoldDrops() && plugin.goldDropOverlay != null)
			{
				plugin.goldDropOverlay.clear();
			}
			plugin.rebuildFilterSet();
			if (plugin.panel != null)
			{
				plugin.panel.onConfigChanged();
			}
			log.debug("Config changed for {}, reloaded ignored items", CoinFlowConfig.CONFIG_GROUP);
		}
	}

	/**
	 * Takes a baseline snapshot of the current inventory without triggering a diff.
	 */
	void takeBaseline()
	{
		if (plugin.client == null || plugin.client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
		if (invContainer != null)
		{
			ItemContainer bagContainer = plugin.client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG);
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
				plugin.getSnapshotService().setLootingBagContents(bagItems);
				plugin.lootingBagInitialized = true;
			}

			ItemContainer quiverContainer = plugin.client.getItemContainer(InventoryID.DIZANAS_QUIVER_AMMO);
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
				plugin.getSnapshotService().setQuiverAmmoContents(quiverItems);
			}

			plugin.previousInventorySnapshot = plugin.takeInventorySnapshot(invContainer);
			plugin.snapshotInitialized = true;
			log.debug("Baseline taken on startup ({} items)", plugin.previousInventorySnapshot.getItems().size());
		}

		ItemContainer equipContainer = plugin.client.getItemContainer(InventoryID.WORN);
		if (equipContainer != null)
		{
			plugin.previousEquipmentSnapshot = plugin.takeSnapshot(equipContainer);
		}
		plugin.pendingWornAmmoExpenses.clear();
		plugin.recentlyUnequippedItems.clear();
		plugin.recentlyEquippedItems.clear();
		plugin.gearSwapItemTicks.clear();

		for (Skill skill : Skill.values())
		{
			try
			{
				int xp = plugin.client.getSkillExperience(skill);
				plugin.previousSkillXp.put(skill, xp);
			}
			catch (Exception ignored)
			{
			}
		}
	}

	/**
	 * Clears all transient tracking buffers, pending container pickups, click intents, and activity timers.
	 */
	void resetTransientTrackingState()
	{
		plugin.recentlyUnequippedItems.clear();
		plugin.recentlyEquippedItems.clear();
		plugin.gearSwapItemTicks.clear();
		plugin.recentlyDroppedItems.clear();
		plugin.recentlyDroppedOwnedItems.clear();
		plugin.recentlyExpensedDrops.clear();
		plugin.droppedItemTicks.clear();
		plugin.containerPickupTracker.resetTransient();
		plugin.matchedUnequipsThisTick.clear();
		plugin.pendingWornAmmoExpenses.clear();
		plugin.weaponChargeTracker.reset();
		plugin.lastSkillingGemId = -1;
		plugin.lastSkillingGemTick = -100;
		if (plugin.getSnapshotService() != null)
		{
			plugin.getSnapshotService().clearLootingBagPendingItems();
			plugin.getSnapshotService().clearQuiverAmmoContents();
		}
		plugin.activityMonitor.reset();
		plugin.lastTinderboxActionTick = -100;
		plugin.lastFiremakingChatTick = -100;
		plugin.lastAlchActionTick = -100;
		plugin.lastFarmingActionTick = -100;
		plugin.lastFarmingChatTick = -100;
		plugin.lastLootingBagDepositTick = -100;
		plugin.lastLootingBagDepositItemId = -1;
		plugin.lastLootingBagDepositItemName = null;
		plugin.lastNotingServiceTick = -100;
		plugin.recentDropIntents.clear();
		plugin.recentOpenIntents.clear();
		// The deployed-cannon ledger survives logout (a deployed cannon does
		// too — reclaimable from Nulodion), but the loaded-balls estimate does
		// not: ball state on relog is unknown, so fewer reversals are safer
		// than phantom ones.
		plugin.cannonTracker.clearLoadedBalls();
		plugin.rebaselineGraceTicks = 0;
		plugin.minigameHudVisible = false;
		plugin.minigameHudGraceTicks = 0;
		if (plugin.goldDropOverlay != null)
		{
			plugin.goldDropOverlay.clear();
		}
	}

	/**
	 * Resets the current session. Called from the panel or keybind — possibly on
	 * the Swing EDT — so the state mutation is marshalled onto the client
	 * thread, where all other session/track bookkeeping runs.
	 */
	void resetSession()
	{
		if (plugin.clientThread != null && (plugin.client == null || !plugin.client.isClientThread()))
		{
			plugin.clientThread.invokeLater(this::resetSessionOnClientThread);
			return;
		}
		resetSessionOnClientThread();
	}

	private void resetSessionOnClientThread()
	{
		if (log.isDebugEnabled() && plugin.session != null)
		{
			log.debug("Session reset: active={}s total={}s gross={} spent={} idle={}",
				plugin.session.getActiveTime().getSeconds(), plugin.session.getTotalInGameTime().getSeconds(),
				plugin.session.getGrossProfit(), plugin.session.getTotalExpenses(), plugin.session.isIdle());
		}
		plugin.session = CoinFlowSession.createNew();
		plugin.goalCompletedNotified = false;
		plugin.grandExchangeTracker.reset();
		plugin.deathTracker.reset();
		plugin.cannonTracker.reset();
		if (plugin.client != null && plugin.client.getGameState() == GameState.LOGGED_IN)
		{
			plugin.grandExchangeTracker.seed(plugin.client.getGrandExchangeOffers());
		}
		plugin.lootingBagInitialized = false;
		resetTransientTrackingState();
		// Invalidate the baseline immediately: any inventory event before the deferred
		// takeBaseline() runs must rebaseline rather than diff against the stale pre-reset snapshot.
		plugin.snapshotInitialized = false;
		plugin.previousInventorySnapshot = null;
		plugin.previousEquipmentSnapshot = null;
		if (plugin.client != null && plugin.client.getGameState() == GameState.LOGGED_IN)
		{
			// Already on the client thread by now — re-baseline immediately.
			takeBaseline();
		}
		if (plugin.panel != null)
		{
			plugin.panel.updateSession(plugin.session);
		}
		log.info("Coin Flow session reset");
	}
}
