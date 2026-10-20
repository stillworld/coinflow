package com.coinflow;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

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

		// Unsupported worlds pause tracking entirely — Leagues/DMM time must
		// not dilute the main session's GP/hr. This latch runs before the
		// snapshot gate below: on an unsupported world no baseline is ever
		// taken (suppressed container events re-baseline without setting
		// snapshotInitialized), so a check placed after finalizePendingPickups
		// would be unreachable for the entire stay. On leaving, resume like a
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

		if (!plugin.containerPickupTracker.finalizePendingPickups())
		{
			return;
		}
		plugin.matchedUnequipsThisTick.clear();

		plugin.deathFlowCoordinator.onGameTick();

		// Blast Furnace coffer settle: held coin diffs are attributed against
		// the coffer varbit (deposit/withdrawal transfer vs. drain), the drain
		// accrues to the synthetic "Blast Furnace coffer" row, and unmatched
		// held coins expire into fallback accounting. The drain keeps ticking
		// while the bank chest is open, so no bank-open gate; skipped on
		// unsupported worlds and mid-death-settle (held entries carry over).
		if (!unsupportedWorld && !plugin.deathTracker.isPending() && plugin.session != null)
		{
			settleBlastFurnaceCoffer();
		}

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

		// Book worn Barrows wear queued this tick. Deferred from the WORN diff
		// so a same-tick same-base INV swap (equipping a differently staged
		// spare) can veto the pair before the wear is charged.
		if (!plugin.pendingBarrowsWear.isEmpty() || !plugin.barrowsSwapBasesThisTick.isEmpty())
		{
			if (plugin.isTrackingSuppressed() || plugin.isBankOrContainerOpen() || plugin.interfaceTracker.isShopOpen() || plugin.interfaceTracker.isNeedsRebaseline() || plugin.rebaselineGraceTicks > 0 || plugin.deathTracker.isPending())
			{
				plugin.pendingBarrowsWear.clear();
				plugin.barrowsSwapBasesThisTick.clear();
			}
			else
			{
				long wearGp = 0;
				for (Map.Entry<String, Long> entry : plugin.pendingBarrowsWear.entrySet())
				{
					if (!plugin.barrowsSwapBasesThisTick.contains(entry.getKey()))
					{
						wearGp += entry.getValue();
						plugin.barrowsWearBookedByPiece.merge(entry.getKey(), entry.getValue(), Long::sum);
					}
				}
				plugin.pendingBarrowsWear.clear();
				plugin.barrowsSwapBasesThisTick.clear();
				if (wearGp > 0)
				{
					Map<Integer, CoinFlowSession.TrackedItem> wearExpenses = new HashMap<>();
					wearExpenses.put(com.coinflow.reconciliation.BarrowsWear.WEAR_ROW_ID,
						new CoinFlowSession.TrackedItem(com.coinflow.reconciliation.BarrowsWear.WEAR_ROW_ID,
							com.coinflow.reconciliation.BarrowsWear.WEAR_ROW_NAME, wearGp, 1L));
					log.debug("Barrows wear charged on tick: {} gp", wearGp);
					plugin.processGainsLossesAndExpenses(Collections.emptyMap(), Collections.emptyMap(), wearExpenses);
				}
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

	/**
	 * Reads the coffer varbit (only while at the furnace), settles the held
	 * coin diffs and the accrued drain, and applies the outcome to the
	 * session. When the tracker reports inactive (conveyor despawned /
	 * LOADING), the settle flushes any remaining drain on the way out.
	 */
	private void settleBlastFurnaceCoffer()
	{
		int cofferValue = -1;
		if (plugin.blastFurnaceTracker.isActive()
			&& plugin.client != null
			&& !plugin.invalidVarbitIds.contains(VarbitID.BLAST_FURNACE_COFFER))
		{
			try
			{
				cofferValue = plugin.client.getVarbitValue(VarbitID.BLAST_FURNACE_COFFER);
			}
			catch (IndexOutOfBoundsException e)
			{
				plugin.invalidVarbitIds.add(VarbitID.BLAST_FURNACE_COFFER);
				log.debug("Ignoring untracked varbit {} (not varp-backed)", VarbitID.BLAST_FURNACE_COFFER);
			}
		}

		BlastFurnaceTracker.Settlement settle = plugin.blastFurnaceTracker.onTick(cofferValue,
			plugin.client != null ? plugin.client.getTickCount() : 0);

		Map<Integer, CoinFlowSession.TrackedItem> expenses = new HashMap<>();
		Map<Integer, Integer> gains = Collections.emptyMap();
		if (settle.drainChargeGp > 0)
		{
			expenses.put(BlastFurnaceTracker.COFFER_ROW_ID,
				new CoinFlowSession.TrackedItem(BlastFurnaceTracker.COFFER_ROW_ID,
					BlastFurnaceTracker.COFFER_ROW_NAME, settle.drainChargeGp, 1L));
			log.debug("Blast Furnace coffer drain charged: {} gp", settle.drainChargeGp);
		}
		if (settle.cashSpendCoins > 0)
		{
			String coinsName = plugin.getItemName(ItemID.COINS);
			expenses.put(ItemID.COINS,
				new CoinFlowSession.TrackedItem(ItemID.COINS, coinsName, settle.cashSpendCoins, 1L));
			// Like the cash-spend path in ConsumablesAndDropsHandler: retire
			// matching session coin gains so pre-session coins dropped later
			// cannot deduct the already-spent loot a second time.
			plugin.session = plugin.session.markConsumed(
				Collections.singletonMap(ItemID.COINS, (long) settle.cashSpendCoins));
			log.debug("Blast Furnace unmatched coin spend: {} gp", settle.cashSpendCoins);
		}
		if (settle.coinGainQty > 0)
		{
			gains = Collections.singletonMap(ItemID.COINS, settle.coinGainQty);
			log.debug("Blast Furnace unmatched coin gain re-applied: {} gp", settle.coinGainQty);
		}
		if (!expenses.isEmpty() || !gains.isEmpty())
		{
			plugin.processGainsLossesAndExpenses(gains, Collections.emptyMap(), expenses);
		}
	}
}
