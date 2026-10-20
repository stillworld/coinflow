package com.coinflow;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.vars.AccountType;
import net.runelite.api.GameState;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.gameval.ItemID;

/**
 * Orchestrates the death flow: captures the pre-death snapshot on
 * {@link ActorDeath}, settles the pending loss once post-respawn containers
 * go quiet, settles reclaim-interface recoveries and deferred chat fees on
 * the game tick, and matches gained items against the recovery ledger.
 */
@Slf4j
final class DeathFlowCoordinator
{
	private final CoinFlowPlugin plugin;

	DeathFlowCoordinator(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	void onActorDeath(ActorDeath event)
	{
		if (plugin.client != null && event.getActor() == plugin.client.getLocalPlayer()
			// Deaths inside quarantine minigames (PvP tutorial arena, Tempoross,
			// Gauntlet HUD) are staged content — the loaner strip is already
			// suppressed, so booking them would only add noise. The exit-grace
			// windows count too: a death while the strip is still landing would
			// capture loaner items into the loss.
			&& !plugin.isTrackingSuppressed())
		{
			// Capture BEFORE resetTransientTrackingState clears the looting
			// bag/quiver caches — the pre-death state must reflect what was
			// actually carried. Tracking freezes until the death settles.
			if (plugin.session != null)
			{
				plugin.deathTracker.onDeath(plugin.combinedSnapshotItems(), plugin.client.getTickCount());
			}
			plugin.interfaceTracker.onActorDeath();
			plugin.lootingBagInitialized = false;
			plugin.resetTransientTrackingState();
		}
	}

	/**
	 * Per-tick death bookkeeping: reclaim-interface settles, deferred chat
	 * fees, and the pending-death settle heuristic.
	 */
	void onGameTick()
	{
		// Settle reclaim-interface recovery once no retrieval interface remains
		// open (WidgetClosed may be skipped when a scene load clears the
		// suppressed set — e.g. Death's Office teleporting the player out).
		if (plugin.deathTracker.hasRetrievalBaseline() && !plugin.interfaceTracker.isDeathRetrievalOpen())
		{
			applyDeathRecovery(plugin.deathTracker.settleRetrieval(plugin.combinedSnapshotItems(),
				plugin.client != null ? plugin.client.getTickCount() : 0, isIronmanAccount()));
		}

		// A "Death charges you" message can arrive after the reclaim interface
		// already closed and settled — apply an unconsumed chat fee once it has
		// aged past the settle window (skipped while a baseline is live so the
		// fee folds into the pending settle instead of applying twice).
		if (!plugin.deathTracker.hasRetrievalBaseline() && !plugin.interfaceTracker.isDeathRetrievalOpen())
		{
			applyDeathReclaimFee(plugin.deathTracker.consumeDeferredChatFee(plugin.client != null ? plugin.client.getTickCount() : 0));
		}

		// Settle a pending death once post-respawn containers have gone quiet.
		if (plugin.deathTracker.isPending() && plugin.client != null && plugin.client.getGameState() == GameState.LOGGED_IN
			&& plugin.deathTracker.shouldSettle(plugin.client.getTickCount()))
		{
			settlePendingDeath();
		}
	}

	/**
	 * Finalizes a pending death: re-baselines, diffs pre- vs post-death state,
	 * and charges the loss to the aggregated "Death" expense row. Runs on
	 * GameTick once post-respawn containers have gone quiet (or on timeout).
	 */
	void settlePendingDeath()
	{
		// takeBaseline() first: it refreshes the looting bag/quiver caches that
		// resetTransientTrackingState cleared, so the post-death snapshot can't
		// manufacture phantom container losses.
		plugin.takeBaseline();
		Map<Integer, Integer> post = new HashMap<>();
		if (plugin.previousInventorySnapshot != null)
		{
			plugin.previousInventorySnapshot.getItems()
				.forEach((id, qty) -> post.merge(plugin.canonicalize(id), qty, Integer::sum));
		}
		if (plugin.previousEquipmentSnapshot != null)
		{
			plugin.previousEquipmentSnapshot.getItems()
				.forEach((id, qty) -> post.merge(plugin.canonicalize(id), qty, Integer::sum));
		}
		Map<Integer, Integer> lost = plugin.deathTracker.computeLosses(post, plugin.itemManager);
		plugin.deathTracker.discardPending();
		plugin.interfaceTracker.setNeedsRebaseline(false);
		plugin.rebaselineGraceTicks = 0;
		plugin.minigameHudGraceTicks = 0;
		plugin.weaponChargeTracker.reset();
		plugin.pendingWornAmmoExpenses.clear();
		plugin.pendingBarrowsWear.clear();
		plugin.barrowsSwapBasesThisTick.clear();

		if (lost.isEmpty())
		{
			log.debug("Death settled: no item loss (safe death or all kept)");
			return;
		}
		if (!plugin.config.trackSpent())
		{
			log.debug("Death settled: {} item types lost, not recorded (trackSpent off)", lost.size());
			return;
		}

		Map<Integer, Integer> filteredLost = new HashMap<>();
		Map<Integer, Long> prices = new HashMap<>();
		long totalValue = 0L;
		for (Map.Entry<Integer, Integer> entry : lost.entrySet())
		{
			int itemId = plugin.canonicalize(entry.getKey());
			String name = plugin.getItemName(itemId);
			if (plugin.isIgnored(name))
			{
				continue;
			}
			long price;
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
				// Only tradeables carry a value on death. Untradeables are kept,
				// converted, or reclaimed for a fee — none of which is a market
				// loss (and minigame loadout gear would otherwise add noise).
				price = plugin.itemManager != null ? plugin.itemManager.getItemPrice(itemId) : 0;
			}
			if (price <= 0)
			{
				continue;
			}
			filteredLost.merge(itemId, entry.getValue(), Integer::sum);
			prices.putIfAbsent(itemId, price);
			totalValue += price * (long) entry.getValue();
		}

		if (filteredLost.isEmpty())
		{
			log.debug("Death settled: only untradeable/ignored losses, no charge");
			return;
		}

		Map<Integer, CoinFlowSession.TrackedItem> expenses = new HashMap<>();
		expenses.put(DeathTracker.DEATH_ROW_ID,
			new CoinFlowSession.TrackedItem(DeathTracker.DEATH_ROW_ID, DeathTracker.DEATH_ROW_NAME, totalValue, 1L));
		plugin.session = plugin.session.withGainsLossesAndExpenses(Collections.emptyMap(), Collections.emptyMap(), expenses);

		// Lost session gains can no longer be deducted a second time.
		Map<Integer, Long> consumed = new HashMap<>();
		filteredLost.forEach((id, qty) -> consumed.put(id, (long) qty));
		plugin.session = plugin.session.markConsumed(consumed);

		plugin.deathTracker.recordLost(filteredLost, prices);
		log.debug("Death settled: {} gp loss across {} item types", totalValue, filteredLost.size());
	}

	/**
	 * Matches gained items against the death ledger, limited to items carrying
	 * a live Take click — the signal that distinguishes picking up your own
	 * death drops from looting a coincidental item of the same id.
	 * Matched quantities are removed from {@code gains} in place.
	 */
	void matchDeathPickupGains(Map<Integer, Integer> gains)
	{
		if (gains == null || gains.isEmpty() || !plugin.deathTracker.hasLedger())
		{
			return;
		}
		int tick = plugin.client != null ? plugin.client.getTickCount() : 0;

		// Ledger keys are canonical; the gains map may carry raw ids (noted,
		// placeholders), so match on a canonicalized copy and subtract back.
		Map<Integer, Integer> canonicalGains = new HashMap<>();
		gains.forEach((id, qty) -> canonicalGains.merge(plugin.canonicalize(id), qty, Integer::sum));
		// Post-settle grace: a reclaim item that arrives after the interface
		// closed matches the ledger without needing a Take click.
		DeathTracker.RecoveryResult result = plugin.deathTracker.inRecoveryGrace(tick)
			? plugin.deathTracker.recover(canonicalGains)
			: plugin.deathTracker.recoverForTakeClicks(canonicalGains, tick);
		if (result.isEmpty())
		{
			return;
		}
		for (Map.Entry<Integer, Integer> matched : result.recoveredQuantities.entrySet())
		{
			int remaining = matched.getValue();
			for (Iterator<Map.Entry<Integer, Integer>> it = gains.entrySet().iterator();
				it.hasNext() && remaining > 0;)
			{
				Map.Entry<Integer, Integer> entry = it.next();
				if (plugin.canonicalize(entry.getKey()) != matched.getKey())
				{
					continue;
				}
				int take = Math.min(remaining, entry.getValue());
				remaining -= take;
				if (entry.getValue() <= take)
				{
					it.remove();
				}
				else
				{
					entry.setValue(entry.getValue() - take);
				}
			}
		}
		applyDeathRecovery(result);
	}

	/**
	 * Applies a death recovery to the session: reverses the matched value off
	 * the "Death" expense row, restores consumption on recovered session
	 * gains, and folds any reclaim fee back into the Death row.
	 */
	void applyDeathRecovery(DeathTracker.RecoveryResult result)
	{
		if (result == null || result.isEmpty() || plugin.session == null)
		{
			return;
		}
		if (result.value > 0)
		{
			Map<Integer, Long> reversal = new HashMap<>();
			reversal.put(DeathTracker.DEATH_ROW_ID, result.value);
			plugin.session = plugin.session.withExpenseReversal(reversal);

			Map<Integer, Long> restored = new HashMap<>();
			result.recoveredQuantities.forEach((id, qty) -> restored.put(id, (long) qty));
			plugin.session = plugin.session.withRestoredConsumption(restored);
			log.debug("Death recovery: {} gp reversed for {}", result.value, result.recoveredQuantities);
		}
		if (result.fee > 0)
		{
			applyDeathReclaimFee(result.fee);
		}
	}

	/**
	 * Adds a reclaim fee to the aggregated "Death" expense row. Skipped when
	 * spent tracking is off — a fee-only row has no matching loss to justify.
	 */
	void applyDeathReclaimFee(long fee)
	{
		if (fee <= 0 || plugin.session == null || plugin.config == null || !plugin.config.trackSpent())
		{
			return;
		}
		Map<Integer, CoinFlowSession.TrackedItem> expenses = new HashMap<>();
		expenses.put(DeathTracker.DEATH_ROW_ID,
			new CoinFlowSession.TrackedItem(DeathTracker.DEATH_ROW_ID, DeathTracker.DEATH_ROW_NAME, fee, 1L));
		plugin.session = plugin.session.withGainsLossesAndExpenses(Collections.emptyMap(), Collections.emptyMap(), expenses);
		log.debug("Death reclaim fee: {} gp", fee);
	}

	/**
	 * Whether the local account gets the 50% ironman reclaim-fee discount.
	 * All iron types qualify except ultimate ironmen.
	 */
	boolean isIronmanAccount()
	{
		AccountType type = plugin.client != null ? plugin.client.getAccountType() : null;
		return type != null && type != AccountType.NORMAL && type != AccountType.ULTIMATE_IRONMAN;
	}
}
