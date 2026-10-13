package com.coinflow;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InventoryID;

/**
 * Per-game-tick orchestration: ages grace windows and take-click records,
 * finalizes death recovery and pending pickup drains, settles consumed
 * worn ammo and weapon-charge resources, ticks session idle/time state,
 * and refreshes goal notification plus the sidebar panel. Ordering is
 * load-bearing — each stage depends on the one before.
 */
@Slf4j
final class GameTickProcessor
{
	private final CoinFlowPlugin plugin;

	GameTickProcessor(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	void onGameTick(GameTick event)
	{
		// Age the region grace window before re-evaluating so a same-tick
		// transition keeps its full window.
		plugin.interfaceTracker.onGameTick();

		// Age the HUD and re-baseline grace windows the same way — BEFORE
		// updateSuppressedRegion latches any fresh transition (so it keeps
		// its full window), and BEFORE the snapshot guard below: if the
		// login baseline never initialized (e.g. the inventory container
		// was null mid-load), an early return would freeze them open and
		// wedge tracking suppression on permanently.
		if (plugin.minigameHudGraceTicks > 0)
		{
			plugin.minigameHudGraceTicks--;
		}
		if (plugin.rebaselineGraceTicks > 0)
		{
			plugin.rebaselineGraceTicks--;
			if (plugin.rebaselineGraceTicks == 0 && plugin.interfaceTracker.isNeedsRebaseline())
			{
				plugin.interfaceTracker.setNeedsRebaseline(false);
				if (plugin.client != null && plugin.snapshotInitialized)
				{
					ItemContainer inv = plugin.client.getItemContainer(InventoryID.INV);
					if (inv != null)
					{
						plugin.previousInventorySnapshot = plugin.takeInventorySnapshot(inv);
					}
				}
			}
		}

		plugin.updateSuppressedRegion();

		if (!plugin.containerPickupTracker.finalizePendingPickups())
		{
			return;
		}
		plugin.matchedUnequipsThisTick.clear();

		plugin.deathFlowCoordinator.onGameTick();

		// Finalize pending equipped ammo/thrown weapons and crumbled jewelry
		// consumed without inventory changes
		if (!plugin.pendingWornAmmoExpenses.isEmpty())
		{
			if (plugin.isTrackingSuppressed() || plugin.isBankOrContainerOpen() || plugin.interfaceTracker.isShopOpen() || plugin.interfaceTracker.isNeedsRebaseline() || plugin.rebaselineGraceTicks > 0 || plugin.deathTracker.isPending())
			{
				plugin.pendingWornAmmoExpenses.clear();
			}
			else
			{
				Map<Integer, CoinFlowSession.TrackedItem> wornAmmoExpenses = new HashMap<>();
				for (Map.Entry<Integer, Integer> entry : plugin.pendingWornAmmoExpenses.entrySet())
				{
					int itemId = entry.getKey();
					int quantity = entry.getValue();
					String itemName = plugin.getItemName(itemId);
					long price = ItemPricing.priceOf(itemId, plugin.itemManager);
					wornAmmoExpenses.put(itemId, new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price));
					log.debug("Consumed equipped ammo/jewelry on tick: {} x{} @ {} gp = {} gp", itemName, quantity, price, (long) quantity * price);
					plugin.removeRecentlyUnequipped(itemId, quantity);
				}
				plugin.pendingWornAmmoExpenses.clear();
				plugin.processGainsLossesAndExpenses(Collections.emptyMap(), Collections.emptyMap(), wornAmmoExpenses);
			}
		}

		// Finalize pending charge-based supply costs (runes/scales/shards stored inside weapons)
		boolean chargeTrackingAllowed = !plugin.isTrackingSuppressed() && !plugin.isBankOrContainerOpen()
			&& !plugin.interfaceTracker.isShopOpen()
			&& !plugin.interfaceTracker.isNeedsRebaseline() && plugin.rebaselineGraceTicks <= 0
			&& !plugin.deathTracker.isPending();
		if (plugin.client != null)
		{
			for (int varbitId : WeaponChargeTracker.trackedVarbitIds())
			{
				if (plugin.invalidVarbitIds.contains(varbitId))
				{
					continue;
				}
				int value;
				try
				{
					value = plugin.client.getVarbitValue(varbitId);
				}
				catch (IndexOutOfBoundsException e)
				{
					// Some tracked CHARGES_* varbits are not varp-backed / not present in this
					// client build; getVarbitValue throws for them. Skip them from now on.
					if (plugin.invalidVarbitIds.add(varbitId))
					{
						log.debug("Ignoring untracked varbit {} (not varp-backed)", varbitId);
					}
					continue;
				}
				plugin.weaponChargeTracker.onVarbitChanged(varbitId, value,
					chargeTrackingAllowed, plugin.client.getTickCount());
			}
		}
		plugin.weaponChargeTracker.setDartRecoveryRate(plugin.equippedAvasRecoveryRate());
		// When tracking is disallowed mid-window (rebaseline grace), keep the pending
		// spend so attack-triggered costs aren't silently dropped; when the feature is
		// disabled, drain anyway to discard accumulation.
		Map<Integer, Integer> consumedChargeResources = chargeTrackingAllowed || !plugin.config.trackWeaponCharges()
			? plugin.weaponChargeTracker.drain()
			: Collections.emptyMap();
		if (!consumedChargeResources.isEmpty() && plugin.config.trackWeaponCharges())
		{
			Map<Integer, CoinFlowSession.TrackedItem> chargeExpenses = new HashMap<>();
			for (Map.Entry<Integer, Integer> entry : consumedChargeResources.entrySet())
			{
				int itemId = entry.getKey();
				int quantity = entry.getValue();
				String itemName = plugin.getItemName(itemId);
				long price = ItemPricing.priceOf(itemId, plugin.itemManager);
				chargeExpenses.put(itemId, new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price));
				log.debug("Consumed weapon charge resource on tick: {} x{} @ {} gp", itemName, quantity, price);
			}
			plugin.processGainsLossesAndExpenses(Collections.emptyMap(), Collections.emptyMap(), chargeExpenses);
		}

		if (plugin.client != null)
		{
			int currentTick = plugin.client.getTickCount();
			plugin.containerPickupTracker.ageTakeClicks(currentTick);

			// Evict gear-swap records unmatched for >10 ticks (~6s). WORN<->INV
			// dispatch pairs resolve within 1-2 ticks, so anything older is stale
			// (e.g. ammo picked up directly into the ammo slot) and must not cancel
			// future legitimate inventory diffs.
			if (!plugin.gearSwapItemTicks.isEmpty())
			{
				plugin.gearSwapItemTicks.entrySet().removeIf(entry -> {
					if (currentTick - entry.getValue() > 10)
					{
						plugin.recentlyUnequippedItems.remove(entry.getKey());
						plugin.recentlyEquippedItems.remove(entry.getKey());
						return true;
					}
					return false;
				});
			}
		}

		// Unsupported worlds pause tracking entirely — Leagues/DMM time must
		// not dilute the main session's GP/hr. On leaving, resume like a
		// relog (the paused gap never counts) and re-baseline the inventory.
		boolean unsupportedWorld = plugin.isUnsupportedWorld();
		if (unsupportedWorld)
		{
			if (!plugin.wasUnsupportedWorld)
			{
				// Pause transitions get no session event, so ping the panel
				// once to flip the "Paused" status on/off.
				if (plugin.panel != null && plugin.session != null)
				{
					plugin.panel.updateSession(plugin.session);
				}
				log.debug("Unsupported world ({}), pausing tracking",
					plugin.client.getWorldType());
			}
			plugin.wasUnsupportedWorld = true;
		}
		else if (plugin.wasUnsupportedWorld)
		{
			plugin.wasUnsupportedWorld = false;
			if (plugin.session != null)
			{
				plugin.session = plugin.session.withResumedState();
			}
			plugin.interfaceTracker.setNeedsRebaseline(true);
			if (plugin.panel != null && plugin.session != null)
			{
				plugin.panel.updateSession(plugin.session);
			}
			log.debug("Left unsupported world, resuming session");
		}

		// Update idle state and time tracking on each tick
		if (!unsupportedWorld)
		{
			CoinFlowSession prevSession = plugin.session;
			plugin.session = plugin.session.tick(plugin.config.idleTimeoutMinutes(), plugin.isPlayerActive());

			// Periodic session telemetry (~every 30s) for debugging GP/hr and timer behavior.
			// tickDelta should hover ~600ms; sustained near-0 values indicate clock corruption.
			if (log.isDebugEnabled() && plugin.client != null && plugin.client.getTickCount() % 50 == 0)
			{
				log.debug("Session: tickDelta={}ms active={}s total={}s gross={} spent={} gp/hr={} idle={}",
					plugin.session.getTotalInGameTime().minus(prevSession.getTotalInGameTime()).toMillis(),
					plugin.session.getActiveTime().getSeconds(), plugin.session.getTotalInGameTime().getSeconds(),
					plugin.session.getGrossProfit(), plugin.session.getTotalExpenses(),
					plugin.session.getGpPerHour(plugin.config.includeAfkTime()), plugin.session.isIdle());
			}
		}

		// Evict dropped item records older than 600 ticks (~6 minutes) to prevent memory leak and stale pickup matching
		if (plugin.client != null && plugin.client.getTickCount() % 50 == 0)
		{
			int currentTick = plugin.client.getTickCount();
			plugin.droppedItemTicks.entrySet().removeIf(entry -> {
				if (currentTick - entry.getValue() > 600)
				{
					plugin.recentlyDroppedItems.remove(entry.getKey());
					plugin.recentlyDroppedOwnedItems.remove(entry.getKey());
					plugin.recentlyExpensedDrops.remove(entry.getKey());
					return true;
				}
				return false;
			});
		}

		// Check goal completion notification (with latch to prevent tick spam)
		plugin.checkGoalNotification();

		// Update sidebar panel if visible
		if (plugin.panel != null)
		{
			plugin.panel.updateSession(plugin.session);
		}
	}
}
