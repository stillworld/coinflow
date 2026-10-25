package com.coinflow;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.events.OverlayMenuClicked;

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
		plugin.blastFurnaceTracker.onGameStateChanged(state);
		if (state == GameState.LOGGING_IN || state == GameState.HOPPING)
		{
			plugin.activityMonitor.onLoginStarted();
		}
		if (state == GameState.LOADING && plugin.client != null)
		{
			plugin.activityMonitor.onSceneLoad(plugin.client.getTickCount());
		}

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
			// Charge any accrued coffer drain while the session is still
			// alive, then drop the coffer baseline — the next read after
			// relog only re-baselines.
			plugin.blastFurnaceTracker.onLeftLoggedIn();
			// Checkpoint goal progress at the relog boundary — the session
			// survives logout, so only the delta since the last persist is
			// written; a client close here loses nothing since the last hop.
			plugin.goalTracker.persistSession();
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
			// Goal checkpoint writes are internal bookkeeping — ignore them so
			// every persist doesn't rebuild the filter set or spam the log.
			String changedKey = event.getKey();
			if (changedKey != null && changedKey.startsWith(GoalTracker.PROGRESS_KEY_PREFIX))
			{
				return;
			}

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
				plugin.goalTracker.onGoalAmountChanged();
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

		// A cannon already deployed when the baseline anchors never produced a
		// deploy diff — seed the ledger from the server varps or its pick-up
		// books as profit; a phantom ledger heals here too.
		plugin.cannonTracker.reconcileBaseline();

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
		plugin.pendingBarrowsWear.clear();
		plugin.barrowsSwapBasesThisTick.clear();
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
		plugin.pendingPouchDiffs.clear();
		plugin.pouchAbsorbBaselineTick = -1;
		plugin.pendingBarrowsWear.clear();
		plugin.barrowsSwapBasesThisTick.clear();
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
		// too — reclaimable from Nulodion) — including the loaded-balls
		// mirror, which matches the session's booked load expense. Only the
		// varp-seeded strip pool drops: it re-derives from ROCKTHROWER on
		// relog anyway.
		plugin.cannonTracker.clearSeededBalls();
		// Held coffer coins and dispenser-rescue entries never survive a
		// boundary — a held loss expiring after relog would book as spend.
		plugin.blastFurnaceTracker.clearTransient();
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
		// Persist the outgoing session's goal contribution before it is
		// replaced. On account switch this still sees the previous hash —
		// checkAccount updates lastAccountHash only after resetSession —
		// so each account's progress lands under its own key.
		plugin.goalTracker.persistSession();
		plugin.session = CoinFlowSession.createNew();
		plugin.goalTracker.onNewSession();
		plugin.goalCompletedNotified = false;
		plugin.barrowsWearBookedByPiece.clear();
		plugin.grandExchangeTracker.reset();
		plugin.deathTracker.reset();
		plugin.cannonTracker.reset();
		plugin.blastFurnaceTracker.resetSession();
		plugin.activityTimeline.reset();
		plugin.profitHistory.reset();
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

	/**
	 * Removes a tracked row from the active session (a gain or an expense).
	 * Called from the item-row context menu on the Swing EDT, so the session
	 * mutation is marshalled onto the client thread like {@link #resetSession}.
	 */
	void removeSessionItem(int itemId, boolean isExpense)
	{
		if (plugin.clientThread != null && (plugin.client == null || !plugin.client.isClientThread()))
		{
			plugin.clientThread.invokeLater(() -> removeSessionItemOnClientThread(itemId, isExpense));
			return;
		}
		removeSessionItemOnClientThread(itemId, isExpense);
	}

	/**
	 * Removes a tracked row from both ledgers — used by "Ignore", where the
	 * same item may appear as both loot and a supply expense.
	 */
	private void removeSessionItemCompletely(int itemId)
	{
		if (plugin.session == null)
		{
			return;
		}
		plugin.session = plugin.session.withoutItem(itemId);
		afterSessionItemRemoval(itemId);
	}

	private void removeSessionItemOnClientThread(int itemId, boolean isExpense)
	{
		if (plugin.session == null)
		{
			return;
		}
		plugin.session = plugin.session.withoutItem(itemId, isExpense);
		afterSessionItemRemoval(itemId);
	}

	/**
	 * Post-removal bookkeeping: drop stale pending-drop ledgers for the item
	 * (a pickup of a removed row must never reverse an expense or restore a
	 * deduction that no longer exists), rebase the activity timeline so a
	 * deleted row isn't booked as a loss against the live segment, re-arm the
	 * goal notification if the removal dropped profit below the target, and
	 * refresh the panel.
	 */
	private void afterSessionItemRemoval(int itemId)
	{
		plugin.recentlyDroppedItems.remove(itemId);
		plugin.recentlyDroppedOwnedItems.remove(itemId);
		plugin.recentlyExpensedDrops.remove(itemId);

		// The session totals changed without an activity — resync the timeline's
		// baselines so the removal isn't attributed to whatever segment is live.
		plugin.activityTimeline.rebase(plugin.session.getGrossProfit(), plugin.session.getTotalExpenses());

		long goalAmount = CoinFlowSession.parseGoalAmount(plugin.config != null ? plugin.config.goalAmount() : null);
		if (goalAmount > 0 && plugin.session != null)
		{
			if (!plugin.goalTracker.isComplete() && plugin.goalDisplayTotal() < goalAmount)
			{
				plugin.goalCompletedNotified = false;
			}
		}

		if (plugin.panel != null)
		{
			plugin.panel.updateSession(plugin.session);
		}
	}

	/**
	 * Adds an item to the ignored-items config and removes it from the active
	 * session. Fractional expense rows (" (dose)" / " (portion)") are
	 * normalized to their base item name first so ignoring a dose row ignores
	 * the underlying potion. Called from the item-row context menu on the
	 * Swing EDT; config writes are EDT-safe, the session removal marshals to
	 * the client thread.
	 */
	void ignoreItem(int itemId, String itemName)
	{
		String clean = ConsumableRegistry.canonicalIgnoreName(itemName)
			.replaceAll(CoinFlowInputFilter.IGNORED_ITEMS, "").trim();
		if (!clean.isEmpty() && plugin.configManager != null)
		{
			String existing = plugin.config != null ? plugin.config.ignoredItems() : null;
			boolean alreadyListed = false;
			if (existing != null && !existing.trim().isEmpty())
			{
				for (String name : existing.split(","))
				{
					if (name.trim().equalsIgnoreCase(clean))
					{
						alreadyListed = true;
						break;
					}
				}
			}
			if (!alreadyListed)
			{
				String updated = (existing == null || existing.trim().isEmpty())
					? clean
					: existing.trim() + ", " + clean;
				plugin.configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "ignoredItems", updated);
				plugin.rebuildFilterSet();
				log.debug("Added '{}' to ignored items", clean);
			}
		}

		if (plugin.clientThread != null && (plugin.client == null || !plugin.client.isClientThread()))
		{
			plugin.clientThread.invokeLater(() -> removeSessionItemCompletely(itemId));
		}
		else
		{
			removeSessionItemCompletely(itemId);
		}
	}

	/**
	 * Handles right-click menu actions on the Coin Flow HUD overlay. Only
	 * custom {@link MenuAction#RUNELITE_OVERLAY} entries are handled here —
	 * RUNELITE_OVERLAY_CONFIG ("Configure") is consumed by the client itself,
	 * which {@link CoinFlowPlugin#openConfiguration()} also relies on.
	 */
	void onOverlayMenuClicked(OverlayMenuClicked event)
	{
		if (plugin.overlay == null || event.getOverlay() != plugin.overlay || event.getEntry() == null
			|| event.getEntry().getMenuAction() != MenuAction.RUNELITE_OVERLAY)
		{
			return;
		}
		String option = event.getEntry().getOption();
		if (CoinFlowOverlay.MENU_RESET.equals(option))
		{
			resetSession();
		}
		else if (CoinFlowOverlay.MENU_VIEW_SUMMARY.equals(option))
		{
			CoinFlowSession session = plugin.session;
			if (plugin.panel != null && session != null)
			{
				plugin.panel.openSessionSummary(session);
			}
		}
	}
}
