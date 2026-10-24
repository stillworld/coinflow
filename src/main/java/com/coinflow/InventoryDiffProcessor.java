package com.coinflow;

import com.coinflow.reconciliation.BlastFurnaceHandler;
import com.coinflow.reconciliation.ProcessingPatternRegistry;
import com.coinflow.reconciliation.ReconciliationContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.widgets.Widget;
import net.runelite.api.GameState;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.QuantityFormatter;

/**
 * Inventory-diff pipeline: turns container-change events and charge varbits
 * into raw gains/losses, routes them through suppression, re-baselining,
 * shop purchase detection, looting-bag deposits, and the reconciliation
 * handler chain, then applies the result to the session.
 */
@Slf4j
final class InventoryDiffProcessor
{
	private final CoinFlowPlugin plugin;

	InventoryDiffProcessor(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	void onItemContainerChanged(ItemContainerChanged event)
	{
		// Evaluate region suppression at diff time, not on scene-load ordering —
		// the tutorial's loaner grant/strip arrive as ordinary container diffs.
		plugin.updateSuppressedRegion();

		int containerId = event.getContainerId();

		if (containerId == InventoryID.INV
			|| containerId == net.runelite.api.gameval.InventoryID.LOOTING_BAG)
		{
			plugin.recordPlayerActivity();
		}

		if (ContainerPickupTracker.isPvpKeyContainer(containerId))
		{
			plugin.containerPickupTracker.checkPvpKeyContainers();
			return;
		}

		// ── Sailing boat cargo holds ─────────────────────────────────────
		// Anything consumed straight out of the hold (landing-site/facility
		// builds, ship-cannon shots) produces no INV event, so route the hold
		// change through the merged snapshot diff. Deposits/withdrawals go
		// through the (suppressed) hold interface and baseline away.
		if (InventorySnapshotService.isSailingCargoContainer(containerId))
		{
			if (plugin.client != null)
			{
				ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
				if (invContainer != null)
				{
					processInventoryChanges(invContainer, true);
				}
			}
			return;
		}

		// ── Handle Equipment changes (detect gear unequipped into inventory) ──
		if (containerId == InventoryID.WORN)
		{
			ItemContainer equipContainer = event.getItemContainer();
			InventorySnapshot currentEquip = plugin.takeSnapshot(equipContainer);

			// Death pending: advance the equipment baseline only. Without this
			// the post-respawn gear clear would be booked as fired ammo (WORN
			// ammo losses feed pendingWornAmmoExpenses) or dropped items.
			if (plugin.deathTracker.isPending())
			{
				plugin.deathTracker.onContainerChanged(plugin.client != null ? plugin.client.getTickCount() : 0);
				plugin.previousEquipmentSnapshot = currentEquip;
				plugin.pendingWornAmmoExpenses.clear();
				plugin.pendingBarrowsWear.clear();
				plugin.barrowsSwapBasesThisTick.clear();
				plugin.weaponChargeTracker.reset();
				plugin.recentlyUnequippedItems.clear();
				plugin.recentlyEquippedItems.clear();
				plugin.gearSwapItemTicks.clear();
				return;
			}

			if (plugin.isTrackingSuppressed() || plugin.isBankOrContainerOpen() || plugin.interfaceTracker.isShopOpen() || plugin.interfaceTracker.isNeedsRebaseline() || plugin.rebaselineGraceTicks > 0)
			{
				plugin.previousEquipmentSnapshot = currentEquip;
				plugin.pendingWornAmmoExpenses.clear();
				plugin.pendingBarrowsWear.clear();
				plugin.barrowsSwapBasesThisTick.clear();
				plugin.weaponChargeTracker.reset();
				plugin.recentlyUnequippedItems.clear();
				plugin.recentlyEquippedItems.clear();
				plugin.gearSwapItemTicks.clear();
				return;
			}

			if (plugin.previousEquipmentSnapshot != null)
			{
				Map<Integer, Integer> removedFromEquip = plugin.previousEquipmentSnapshot.getGainedItems(currentEquip);
				Map<Integer, Integer> addedToEquip = currentEquip.getGainedItems(plugin.previousEquipmentSnapshot);

				// Minigame-only items can be equipped (e.g. Soul Wars team
				// capes) — hide them before degradation pairing and the
				// ammo/crumbled-jewelry checks so they never book an expense.
				stripNonEconomicItems(addedToEquip, removedFromEquip);

				// Reconcile items that degraded directly in equipment (e.g. worn Slayer ring (3) -> (2), Barrows degradation)
				// These did not move to or from inventory.
				if (!removedFromEquip.isEmpty() && !addedToEquip.isEmpty())
				{
					Iterator<Map.Entry<Integer, Integer>> removedIt = removedFromEquip.entrySet().iterator();
					while (removedIt.hasNext())
					{
						Map.Entry<Integer, Integer> removedEntry = removedIt.next();
						int removedId = plugin.itemManager != null ? plugin.itemManager.canonicalize(removedEntry.getKey()) : removedEntry.getKey();
						String removedName = plugin.getItemName(removedId);

						Iterator<Map.Entry<Integer, Integer>> addedIt = addedToEquip.entrySet().iterator();
						while (addedIt.hasNext())
						{
							Map.Entry<Integer, Integer> addedEntry = addedIt.next();
							int addedId = plugin.itemManager != null ? plugin.itemManager.canonicalize(addedEntry.getKey()) : addedEntry.getKey();
							String addedName = plugin.getItemName(addedId);

							if (com.coinflow.reconciliation.ChargeDegradationHandler.isChargeDegradationPair(removedName, addedName)
								// Raw ids: ItemMapping canonicalizes moon used variants
								// onto the new id, so canonical ids would never differ.
								|| com.coinflow.reconciliation.MoonWear.isRepairOrDegradePair(removedEntry.getKey(), addedEntry.getKey()))
							{
								int match = Math.min(removedEntry.getValue(), addedEntry.getValue());

								// Worn Barrows gear degrading in place is real
								// spend — queue the wear; booked on GameTick
								// once same-tick same-base INV swaps are known.
								long wearGp = com.coinflow.reconciliation.BarrowsWear.wearCostGp(removedName, addedName);
								if (wearGp > 0)
								{
									com.coinflow.reconciliation.BarrowsWear.Piece piece =
										com.coinflow.reconciliation.BarrowsWear.parse(removedName);
									if (piece != null && !ConsumableRegistry.isIgnoredName(piece.baseName, plugin.ignoredItemNames))
									{
										plugin.pendingBarrowsWear.merge(piece.baseName.toLowerCase(java.util.Locale.ROOT),
											wearGp * match, Long::sum);
										log.debug("Queued Barrows wear: {} -> {} (x{}) = {} gp",
											removedName, addedName, match, wearGp * match);
									}
								}
								if (addedEntry.getValue() <= match)
								{
									addedIt.remove();
								}
								else
								{
									addedEntry.setValue(addedEntry.getValue() - match);
								}

								if (removedEntry.getValue() <= match)
								{
									removedIt.remove();
									break;
								}
								else
								{
									removedEntry.setValue(removedEntry.getValue() - match);
								}
							}
						}
					}
				}

				for (Map.Entry<Integer, Integer> entry : removedFromEquip.entrySet())
				{
					int rawId = entry.getKey();
					int qty = entry.getValue();
					int canonicalId = plugin.itemManager != null ? plugin.itemManager.canonicalize(rawId) : rawId;
					String name = plugin.getItemName(canonicalId);

					int effectiveQty = qty;
					Integer alreadyMatched = plugin.matchedUnequipsThisTick.get(canonicalId);
					if (alreadyMatched == null && canonicalId != rawId)
					{
						alreadyMatched = plugin.matchedUnequipsThisTick.get(rawId);
					}
					if (alreadyMatched != null)
					{
						int deduction = Math.min(effectiveQty, alreadyMatched);
						effectiveQty -= deduction;
						int remMatched = alreadyMatched - deduction;
						if (remMatched <= 0)
						{
							plugin.matchedUnequipsThisTick.remove(canonicalId);
							plugin.matchedUnequipsThisTick.remove(rawId);
						}
						else
						{
							plugin.matchedUnequipsThisTick.put(canonicalId, remMatched);
						}
					}

					if (effectiveQty > 0)
					{
						// Ammo fired/thrown, or jewelry that crumbled on its last
						// charge: consumed in place rather than unequipped to INV.
						if (ConsumableRegistry.isAmmo(name) || ConsumableRegistry.isCrumblingJewelry(name))
						{
							plugin.pendingWornAmmoExpenses.merge(canonicalId, effectiveQty, Integer::sum);
						}
						plugin.recentlyUnequippedItems.merge(entry.getKey(), effectiveQty, Integer::sum);
						plugin.gearSwapItemTicks.put(entry.getKey(), plugin.client != null ? plugin.client.getTickCount() : 0);
					}
				}

				for (Map.Entry<Integer, Integer> entry : addedToEquip.entrySet())
				{
					plugin.recentlyEquippedItems.merge(entry.getKey(), entry.getValue(), Integer::sum);
					plugin.gearSwapItemTicks.put(entry.getKey(), plugin.client != null ? plugin.client.getTickCount() : 0);
				}
			}
			plugin.previousEquipmentSnapshot = currentEquip;
			return;
		}

		// ── Handle Dizana's Quiver changes ───────────────────────────────
		if (containerId == InventoryID.DIZANAS_QUIVER_AMMO)
		{
			if (plugin.client != null)
			{
				ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
				if (invContainer != null)
				{
					ItemContainer quiverContainer = event.getItemContainer();
					Map<Integer, Integer> items = new HashMap<>();
					if (quiverContainer != null && quiverContainer.getItems() != null)
					{
						for (Item item : quiverContainer.getItems())
						{
							if (item != null && item.getId() > 0 && item.getQuantity() > 0)
							{
								items.merge(item.getId(), item.getQuantity(), Integer::sum);
							}
						}
					}
					plugin.getSnapshotService().setQuiverAmmoContents(items);
					processInventoryChanges(invContainer);
				}
			}
			return;
		}

		// ── Handle Inventory changes ─────────────────────────────────────
		if (containerId == InventoryID.INV)
		{
			ItemContainer currentInv = event.getItemContainer();
			if (plugin.previousInventorySnapshot != null && currentInv != null)
			{
				InventorySnapshot currentSnap = plugin.takeSnapshot(currentInv);
				Map<Integer, Integer> gained = currentSnap.getGainedItems(plugin.previousInventorySnapshot);
				for (Map.Entry<Integer, Integer> entry : gained.entrySet())
				{
					int canonicalId = plugin.itemManager != null ? plugin.itemManager.canonicalize(entry.getKey()) : entry.getKey();
					if (!MinigameQuarantine.isMinigameOnlyItem(canonicalId)
						&& !ItemPricing.isValueless(canonicalId)
						&& (untradeableTrackingEnabled()
							|| !ItemPricing.isUnpricedUntradeable(canonicalId, plugin.itemManager)))
					{
						plugin.invItemsGainedThisTick.merge(entry.getKey(), entry.getValue(), Integer::sum);
					}
				}
			}
			processInventoryChanges(event.getItemContainer());
			return;
		}

		// ── Handle Looting Bag changes ───────────────────────────────────
		if (containerId == net.runelite.api.gameval.InventoryID.LOOTING_BAG)
		{
			if (plugin.client != null)
			{
				ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
				if (invContainer != null && plugin.hasLootingBag(invContainer))
				{
					ItemContainer bagContainer = event.getItemContainer();
					Map<Integer, Integer> bagItems = new HashMap<>();
					if (bagContainer != null && bagContainer.getItems() != null)
					{
						for (Item item : bagContainer.getItems())
						{
							if (item != null && item.getId() > 0 && item.getQuantity() > 0)
							{
								bagItems.merge(item.getId(), item.getQuantity(), Integer::sum);
							}
						}
					}

					if (!plugin.lootingBagInitialized)
					{
						// Baseline initialization for container 516:
						// Calculate pre-existing bag items (items already inside bag before login or first check)
						Map<Integer, Integer> currentlyCached = plugin.getSnapshotService().getLootingBagContents();
						Map<Integer, Integer> preExistingItems = new HashMap<>();
						for (Map.Entry<Integer, Integer> entry : bagItems.entrySet())
						{
							int itemId = entry.getKey();
							int bagQty = entry.getValue();
							int cachedQty = currentlyCached.getOrDefault(itemId, 0);
							int preExistingQty = bagQty - cachedQty;
							if (preExistingQty > 0)
							{
								preExistingItems.put(itemId, preExistingQty);
							}
						}

						if (plugin.previousInventorySnapshot != null && !preExistingItems.isEmpty())
						{
							Map<Integer, Integer> merged = new HashMap<>(plugin.previousInventorySnapshot.getItems());
							for (Map.Entry<Integer, Integer> entry : preExistingItems.entrySet())
							{
								merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
							}
							plugin.previousInventorySnapshot = InventorySnapshot.fromMap(merged);
						}

						plugin.lootingBagInitialized = true;
					}

					plugin.getSnapshotService().setLootingBagContents(bagItems);
					processInventoryChanges(invContainer);
				}
			}
			return;
		}
	}

	void processInventoryChanges(ItemContainer invContainer)
	{
		processInventoryChanges(invContainer, false);
	}

	void processInventoryChanges(ItemContainer invContainer, boolean cargoHoldDiff)
	{
		if (plugin.session == null)
		{
			return;
		}

		InventorySnapshot currentSnapshot = plugin.takeInventorySnapshot(invContainer);

		// Death pending: freeze all diff tracking. The post-respawn container
		// clear must never reach the normal pipeline (it would be booked as
		// consumed supplies, cash spends, or drops on top of the Death row).
		// Covers INV directly plus the rune pouch, quiver, and looting bag
		// triggers that all funnel through here.
		if (plugin.deathTracker.isPending())
		{
			plugin.deathTracker.onContainerChanged(plugin.client != null ? plugin.client.getTickCount() : 0);
			plugin.previousInventorySnapshot = currentSnapshot;
			return;
		}

		// If bank or trading interface is actively open, suppress tracking and update baseline
		if (plugin.isTrackingSuppressed() || plugin.isBankOrContainerOpen())
		{
			// The clerk's Sets interface suppresses the set<->components swap
			// like any other conversion, but the produced items must inherit
			// the consumed side's cost basis or they sell as untracked income.
			if (plugin.interfaceTracker.isItemsetsOpen()
				&& plugin.previousInventorySnapshot != null && plugin.snapshotInitialized)
			{
				plugin.grandExchangeTracker.transferSetExchangeBasis(
					currentSnapshot.getLostItems(plugin.previousInventorySnapshot),
					currentSnapshot.getGainedItems(plugin.previousInventorySnapshot),
					plugin.itemManager, plugin.client != null ? plugin.client.getTickCount() : 0);
			}
			// Omnishops (Mixology rewards, NMZ imbues, volcanic/fossil stores)
			// charge a varp currency, so a purchase arrives as a pure inventory
			// gain — credit it instead of letting suppression swallow it. Any
			// losses while open still baseline away (points shops take no items).
			// snapshotInitialized is not required: the first suppressed event
			// under the open shop anchors the baseline, so a shop opened before
			// the login re-baseline lands still credits later purchases.
			if (plugin.interfaceTracker.isOmnishopOpen()
				&& plugin.previousInventorySnapshot != null)
			{
				Map<Integer, Integer> omnishopGains =
					currentSnapshot.getGainedItems(plugin.previousInventorySnapshot);
				Map<Integer, Integer> omnishopLosses =
					currentSnapshot.getLostItems(plugin.previousInventorySnapshot);
				stripNonEconomicItems(omnishopGains, omnishopLosses);
				if (!omnishopGains.isEmpty())
				{
					log.debug("Omnishop purchase credited: {}", omnishopGains);
					plugin.processGains(omnishopGains);
				}
			}
			plugin.previousInventorySnapshot = currentSnapshot;
			return;
		}

		// If we need a re-baseline (login, interface close, in-flight withdrawal)
		if (plugin.interfaceTracker.isNeedsRebaseline() || plugin.rebaselineGraceTicks > 0 || !plugin.snapshotInitialized)
		{
			boolean isProcessing = false;
			Map<Integer, Integer> rawGains = Collections.emptyMap();
			Map<Integer, Integer> rawLosses = Collections.emptyMap();
			if (plugin.previousInventorySnapshot != null && plugin.snapshotInitialized)
			{
				rawGains = currentSnapshot.getGainedItems(plugin.previousInventorySnapshot);
				rawLosses = currentSnapshot.getLostItems(plugin.previousInventorySnapshot);
				// Minigame-only items are invisible everywhere — including
				// inside a re-baseline window, where one arriving must not
				// promote baseline noise into a processed diff or a purchase.
				stripNonEconomicItems(rawGains, rawLosses);
				// A death-drop pickup inside a re-baseline window is still a
				// recovery — reverse the Death charge before the gain is
				// swallowed by the baseline.
				// KNOWN ISSUE (fix later): if isProcessing flips true below, the
				// normal path re-diffs the snapshots at ~line 472 and the
				// already-consumed recovery items are booked as fresh gains on
				// top of the Death-row reversal (~2x phantom profit). In practice
				// this is unlikely: a re-baselined reclaim arriving in the same
				// diff as a dose sip/portion/noting/processing change requires
				// e.g. sipping a potion in the exact tick reclaimed items land.
				plugin.matchDeathPickupGains(rawGains);
				if (!rawLosses.isEmpty() && !rawGains.isEmpty())
				{
					for (Map.Entry<Integer, Integer> lostEntry : rawLosses.entrySet())
					{
						int lostId = plugin.itemManager != null ? plugin.itemManager.canonicalize(lostEntry.getKey()) : lostEntry.getKey();
						ItemComposition lostComp = plugin.itemManager != null ? plugin.itemManager.getItemComposition(lostId) : null;
						String lostName = lostComp != null ? lostComp.getName() : "";

						for (Map.Entry<Integer, Integer> gainedEntry : rawGains.entrySet())
						{
							int gainedId = plugin.itemManager != null ? plugin.itemManager.canonicalize(gainedEntry.getKey()) : gainedEntry.getKey();
							ItemComposition gainedComp = plugin.itemManager != null ? plugin.itemManager.getItemComposition(gainedId) : null;
							String gainedName = gainedComp != null ? gainedComp.getName() : "";

							// Potion dose steps and food portion transitions are also
							// real inventory changes, not baseline noise — swallowing
							// them would lose the first sip/bite after an interface.
							ConsumableRegistry.PotionDose lostPotion = ConsumableRegistry.parsePotion(lostName);
							ConsumableRegistry.PotionDose gainedPotion = ConsumableRegistry.parsePotion(gainedName);
							boolean samePotionBase = lostPotion != null && gainedPotion != null
								&& lostPotion.getBaseName().equalsIgnoreCase(gainedPotion.getBaseName());

							if ((lostId == gainedId && !lostEntry.getKey().equals(gainedEntry.getKey()))
								|| ProcessingPatternRegistry.match(lostName, gainedName, gainedEntry.getValue()) != null
								|| samePotionBase
								|| ConsumableRegistry.isFoodPortion(gainedName, lostName))
							{
								isProcessing = true;
								break;
							}
						}
						if (isProcessing)
						{
							break;
						}
					}
				}
			}

			if (!isProcessing)
			{
				// Cannon setup/pick-up inside a re-baseline window still has to
				// net against deployed parts/loaded balls or the tracker stays
				// deployed forever; returned balls reverse the earlier load
				// expense rather than being swallowed by the baseline.
				Map<Integer, Long> cannonReversals = plugin.cannonTracker.netDiff(rawGains, rawLosses,
					plugin.client != null ? plugin.client.getTickCount() : 0,
					plugin.interfaceTracker.isShopOpen());
				if (!cannonReversals.isEmpty())
				{
					plugin.session = plugin.session.withExpenseReversal(cannonReversals);
				}
				// A set<->components conversion whose packet arrived after the
				// Sets interface closed is swallowed by this baseline — still
				// carry its cost basis across. (Only when the diff is actually
				// baselined: a processed diff owns its own accounting.)
				if (plugin.interfaceTracker.wasLastClosedGroupItemsets())
				{
					plugin.grandExchangeTracker.transferSetExchangeBasis(rawLosses, rawGains,
						plugin.itemManager, plugin.client != null ? plugin.client.getTickCount() : 0);
				}
				// Blast Furnace bar collection landing inside a re-baseline
				// window (e.g. a coal-bag Open/Close toggle) is still real
				// output — rescue gains matching a recent dispenser varbit
				// decrease. Bank-chest bar withdrawals produce no dispenser
				// decrease and are never rescued.
				if (plugin.blastFurnaceTracker.isActive() && rawLosses.isEmpty())
				{
					plugin.blastFurnaceTracker.rescueBars(rawGains,
						plugin.client != null ? plugin.client.getTickCount() : 0);
				}
				// An omnishop purchase packet can land one tick after the
				// interface closed — inside this re-baseline window. Credit
				// those gains rather than swallowing them as baseline noise.
				// Diff directly when the re-baseline gate skipped rawGains
				// (baseline never initialized before the shop opened).
				if (plugin.interfaceTracker.wasLastClosedGroupOmnishop()
					&& plugin.previousInventorySnapshot != null)
				{
					Map<Integer, Integer> lateGains = rawGains;
					if (lateGains.isEmpty())
					{
						lateGains = currentSnapshot.getGainedItems(plugin.previousInventorySnapshot);
						stripNonEconomicItems(lateGains, Collections.emptyMap());
					}
					if (!lateGains.isEmpty())
					{
						log.debug("Late omnishop purchase credited: {}", lateGains);
						plugin.processGains(lateGains);
					}
				}
				// A purchase landing inside a re-baseline window (e.g. Zaff's
				// daily battlestaves bought right after a login or bank close)
				// is a real transaction, not baseline noise — swallowing it
				// loses the cost basis, so the goods later sell as fully
				// untracked income. Route it through ShopTracker instead.
				if (plugin.interfaceTracker.isShopOpen() || ActionClassifiers.isCoinsOnlyPurchase(rawGains, rawLosses))
				{
					plugin.applyGeLedger(
						plugin.shopTracker.reconcileDiff(rawGains, rawLosses, plugin.session, plugin.itemManager, plugin.ignoredItemNames,
							plugin.config.untrackedSalesAsIncome()),
						ItemID.COINS);
					log.debug("Purchase during re-baseline routed to shop tracking: {} gp for {}",
						rawLosses.getOrDefault(ItemID.COINS, 0), rawGains);
				}
				else
				{
					log.debug("Baseline snapshot taken ({} items)", currentSnapshot.getItems().size());
				}
				plugin.previousInventorySnapshot = currentSnapshot;
				plugin.snapshotInitialized = true;
				if (plugin.client != null && plugin.client.getItemContainer(net.runelite.api.gameval.InventoryID.LOOTING_BAG) != null)
				{
					plugin.lootingBagInitialized = true;
				}
				// If the swallowed diff contained pouch-rune rows, the baseline
				// absorbed one half of a pouch transfer — held counterparts are
				// now unpaired, so drop them and suppress settle for the window
				// the other half may land in.
				if (containsPouchRuneRow(rawGains) || containsPouchRuneRow(rawLosses))
				{
					plugin.pendingPouchDiffs.clear();
					plugin.pouchAbsorbBaselineTick = plugin.client != null ? plugin.client.getTickCount() : -1;
				}
				plugin.rebaselineGraceTicks = 0;
				plugin.interfaceTracker.setNeedsRebaseline(false);
				return;
			}
			else
			{
				plugin.rebaselineGraceTicks = 0;
				plugin.interfaceTracker.setNeedsRebaseline(false);
			}
		}

		// ── Shop open: route the diff through ShopTracker ────────────────
		// The coins delta is the actual price paid/received. Entries the
		// tracker does not reconcile (non-coin currencies, noise while the
		// shop is open) are intentionally suppressed.
		if (plugin.interfaceTracker.isShopOpen() && plugin.previousInventorySnapshot != null)
		{
			Map<Integer, Integer> shopGains = currentSnapshot.getGainedItems(plugin.previousInventorySnapshot);
			Map<Integer, Integer> shopLosses = currentSnapshot.getLostItems(plugin.previousInventorySnapshot);
			plugin.applyGeLedger(
				plugin.shopTracker.reconcileDiff(shopGains, shopLosses, plugin.session, plugin.itemManager, plugin.ignoredItemNames,
					plugin.config.untrackedSalesAsIncome()),
				ItemID.COINS);
			plugin.previousInventorySnapshot = currentSnapshot;
			return;
		}

		// ── Diff and process gains & expenses ─────────────────────────────
		if (plugin.previousInventorySnapshot != null)
		{
			Map<Integer, Integer> rawGains = currentSnapshot.getGainedItems(plugin.previousInventorySnapshot);
			Map<Integer, Integer> rawLosses = currentSnapshot.getLostItems(plugin.previousInventorySnapshot);

			// Minigame-only items (CW bandages, BA eggs, NMZ potions, GOTR
			// cells, Tithe seeds, ...) can never exist outside their minigame,
			// so both their grant and their exit strip are invisible here.
			stripNonEconomicItems(rawGains, rawLosses);

			// Wilderness death-drop pickups carrying a Take click reverse the
			// Death charge rather than counting as fresh loot.
			plugin.matchDeathPickupGains(rawGains);

			// Deployed-cannon traffic: part losses mark the cannon deployed,
			// ball losses count as loaded, and returned parts/balls strip the
			// gain — returned balls carry an expense reversal so the earlier
			// load spend is undone instead of the kit booking as loot.
			Map<Integer, Long> cannonReversals = plugin.cannonTracker.netDiff(rawGains, rawLosses,
				plugin.client != null ? plugin.client.getTickCount() : 0,
				plugin.interfaceTracker.isShopOpen());

			// Blast Furnace coffer: a coin-only diff is a deposit or a
			// withdrawal, not a spend or income — hold it aside so the coffer
			// varbit can attribute it next tick. Diffs containing anything
			// besides coins are left alone (purchases keep their accounting).
			plugin.blastFurnaceTracker.holdCoins(rawGains, rawLosses,
				plugin.client != null ? plugin.client.getTickCount() : 0,
				plugin.isDropIntentActive(ItemID.COINS,
					plugin.client != null ? plugin.client.getTickCount() : 0));

			// ── Dialogue purchase (Zaff, Ali Morrisane, etc.) ─────────────
			// Coins were the only thing lost and non-coin items arrived in the
			// same tick with no shop interface open: that is a purchase, not
			// loot. Route it through ShopTracker for basis so the cash does not
			// vanish while the goods show up as profit.
			if (ActionClassifiers.isCoinsOnlyPurchase(rawGains, rawLosses))
			{
				plugin.applyGeLedger(
					plugin.shopTracker.reconcileDiff(rawGains, rawLosses, plugin.session, plugin.itemManager, plugin.ignoredItemNames,
						plugin.config.untrackedSalesAsIncome()),
					ItemID.COINS);
				log.debug("Dialogue purchase: {} gp for {}", rawLosses.get(ItemID.COINS), rawGains);
				plugin.previousInventorySnapshot = currentSnapshot;
				return;
			}

			if (!rawLosses.isEmpty() && plugin.hasLootingBag(invContainer))
			{
				int currentTick = plugin.client != null ? plugin.client.getTickCount() : 0;
				boolean recentDepositIntent = (currentTick - plugin.lastLootingBagDepositTick <= 15);
				Widget lootingBagWidget = plugin.client != null ? plugin.client.getWidget(InterfaceID.WildernessLootingbag.FRAME) : null;
				boolean lootingBagInterfaceOpen = lootingBagWidget != null && !lootingBagWidget.isHidden();

				boolean anyDeposited = false;
				Iterator<Map.Entry<Integer, Integer>> it = rawLosses.entrySet().iterator();
				while (it.hasNext())
				{
					Map.Entry<Integer, Integer> entry = it.next();
					int lostId = entry.getKey();
					int lostQty = entry.getValue();
					int canonicalLostId = plugin.itemManager != null ? plugin.itemManager.canonicalize(lostId) : lostId;
					String lostName = plugin.itemManager != null && plugin.itemManager.getItemComposition(canonicalLostId) != null
						? plugin.itemManager.getItemComposition(canonicalLostId).getName().toLowerCase(Locale.ROOT)
						: "";

					// While the bag interface is open, only non-consumables are treated as
					// deposits — eating/drinking while "Check"-ing the bag must still be
					// expensed. Consumable deposits still match via the Store/Deposit
					// click intent below, which captures the item id/name.
					boolean isDepositMatch = lootingBagInterfaceOpen
						&& !ConsumableRegistry.isConsumable(canonicalLostId, lostName, plugin.itemManager);
					if (!isDepositMatch && recentDepositIntent)
					{
						if (plugin.lastLootingBagDepositItemId > 0)
						{
							int canonicalDepositId = plugin.itemManager != null ? plugin.itemManager.canonicalize(plugin.lastLootingBagDepositItemId) : plugin.lastLootingBagDepositItemId;
							if (canonicalDepositId == canonicalLostId)
							{
								isDepositMatch = true;
							}
						}
						if (!isDepositMatch && plugin.lastLootingBagDepositItemName != null && !plugin.lastLootingBagDepositItemName.isEmpty())
						{
							String lowerDepositName = plugin.lastLootingBagDepositItemName.toLowerCase(Locale.ROOT);
							if (lostName.contains(lowerDepositName) || lowerDepositName.contains(lostName))
							{
								isDepositMatch = true;
							}
						}
					}

					if (isDepositMatch)
					{
						plugin.getSnapshotService().addLootingBagPendingItem(lostId, lostQty);
						it.remove();
						anyDeposited = true;
						log.debug("Deposited item into looting bag: {} x{} (id {})", lostName, lostQty, lostId);
					}
				}

				if (anyDeposited)
				{
					plugin.lastLootingBagDepositTick = -100;
					plugin.lastLootingBagDepositItemId = -1;
					plugin.lastLootingBagDepositItemName = null;
					currentSnapshot = plugin.takeInventorySnapshot(invContainer);
					rawGains = currentSnapshot.getGainedItems(plugin.previousInventorySnapshot);
					rawLosses = currentSnapshot.getLostItems(plugin.previousInventorySnapshot);
				}
			}

			// A Barrows piece moving UP a stage in inventory (staged out,
			// higher-stage/bare in) is a swap between two copies, not wear —
			// WORN sees the mirror image as a fake degradation. Mark the base
			// so the GameTick wear flush skips it. Done on the raw diff before
			// GearSwapHandler can cancel the pair away.
			if (!rawLosses.isEmpty() && !rawGains.isEmpty())
			{
				for (Map.Entry<Integer, Integer> lostEntry : rawLosses.entrySet())
				{
					int lostId = plugin.itemManager != null ? plugin.itemManager.canonicalize(lostEntry.getKey()) : lostEntry.getKey();
					String lostName = plugin.getItemName(lostId);
					for (Map.Entry<Integer, Integer> gainedEntry : rawGains.entrySet())
					{
						int gainedId = plugin.itemManager != null ? plugin.itemManager.canonicalize(gainedEntry.getKey()) : gainedEntry.getKey();
						String gainedName = plugin.getItemName(gainedId);
						com.coinflow.reconciliation.BarrowsWear.Piece lostPiece =
							com.coinflow.reconciliation.BarrowsWear.parse(lostName);
						if (lostPiece != null
							&& com.coinflow.reconciliation.BarrowsWear.isUpwardPair(lostName, gainedName))
						{
							plugin.barrowsSwapBasesThisTick.add(lostPiece.baseName.toLowerCase(java.util.Locale.ROOT));
						}
					}
				}
			}

			// A rune-pouch transfer splits across an ItemContainerChanged and a
			// VarbitChanged that can arrive in either order — each half diffs as
			// a phantom gain/loss on its own. While a pouch is carried, defer
			// pouch-eligible rune rows and settle the net on GameTick, when
			// both halves have landed and cancel out.
			Set<Integer> deferredRuneLosses = Collections.emptySet();
			if (plugin.hasRunePouch(invContainer))
			{
				deferredRuneLosses = deferPouchRuneDiffs(rawGains, rawLosses);
				if (rawGains.isEmpty() && rawLosses.isEmpty())
				{
					plugin.previousInventorySnapshot = currentSnapshot;
					return;
				}
			}

			reconcileAndApplyDiff(rawGains, rawLosses, cannonReversals, deferredRuneLosses, cargoHoldDiff);
		}

		plugin.previousInventorySnapshot = currentSnapshot;
	}

	private Set<Integer> deferPouchRuneDiffs(Map<Integer, Integer> gains, Map<Integer, Integer> losses)
	{
		deferPouchRuneRows(gains, 1, null);
		Set<Integer> deferredLosses = new java.util.HashSet<>();
		deferPouchRuneRows(losses, -1, deferredLosses);
		return deferredLosses;
	}

	private void deferPouchRuneRows(Map<Integer, Integer> diff, int sign, Set<Integer> deferred)
	{
		Iterator<Map.Entry<Integer, Integer>> it = diff.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Integer, Integer> entry = it.next();
			int itemId = plugin.itemManager != null ? plugin.itemManager.canonicalize(entry.getKey()) : entry.getKey();
			if (plugin.getSnapshotService().isPouchRuneId(itemId))
			{
				plugin.pendingPouchDiffs.merge(itemId, sign * entry.getValue(), Integer::sum);
				if (deferred != null)
				{
					deferred.add(itemId);
				}
				it.remove();
			}
		}
	}

	private boolean containsPouchRuneRow(Map<Integer, Integer> diff)
	{
		for (int itemId : diff.keySet())
		{
			int canonical = plugin.itemManager != null ? plugin.itemManager.canonicalize(itemId) : itemId;
			if (plugin.getSnapshotService().isPouchRuneId(canonical))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Settles deferred pouch-rune diff rows once per GameTick, after the
	 * container and varbit halves of a transfer have both landed. The net
	 * leftover is a real diff (spell costs, pickups, drops): routed through
	 * the normal reconciliation path. Held rows are discarded while tracking
	 * is suppressed — the baseline has already absorbed the transfer.
	 */
	void settlePendingPouchDiffs()
	{
		if (plugin.pendingPouchDiffs.isEmpty())
		{
			return;
		}
		Map<Integer, Integer> net = new HashMap<>(plugin.pendingPouchDiffs);
		plugin.pendingPouchDiffs.clear();

		if (plugin.session == null || plugin.client == null
			|| plugin.isTrackingSuppressed() || plugin.isBankOrContainerOpen()
			|| plugin.interfaceTracker.isShopOpen()
			|| plugin.interfaceTracker.isNeedsRebaseline() || plugin.rebaselineGraceTicks > 0
			|| plugin.deathTracker.isPending()
			|| (plugin.pouchAbsorbBaselineTick >= 0
				&& plugin.client.getTickCount() - plugin.pouchAbsorbBaselineTick <= 1))
		{
			return;
		}

		Map<Integer, Integer> gains = new HashMap<>();
		Map<Integer, Integer> losses = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : net.entrySet())
		{
			if (entry.getValue() > 0)
			{
				gains.put(entry.getKey(), entry.getValue());
			}
			else if (entry.getValue() < 0)
			{
				losses.put(entry.getKey(), -entry.getValue());
			}
		}
		if (!gains.isEmpty() || !losses.isEmpty())
		{
			reconcileAndApplyDiff(gains, losses, Collections.emptyMap(), Collections.emptySet(), false);
		}
	}

	private void reconcileAndApplyDiff(Map<Integer, Integer> rawGains, Map<Integer, Integer> rawLosses,
		Map<Integer, Long> cannonReversals, Set<Integer> deferredRuneLosses, boolean cargoHoldDiff)
	{
		ItemContainer currentEquipContainer = plugin.client != null ? plugin.client.getItemContainer(InventoryID.WORN) : null;
		ReconciliationContext context = new ReconciliationContext(
			rawGains,
			rawLosses,
			plugin.recentlyUnequippedItems,
			plugin.recentlyEquippedItems,
			plugin.recentlyDroppedItems,
			plugin.recentlyDroppedOwnedItems,
			plugin.recentlyExpensedDrops,
			plugin.previousEquipmentSnapshot,
			currentEquipContainer,
			plugin.session,
			plugin.grandExchangeTracker,
			plugin.itemManager,
			plugin.config,
			plugin.ignoredItemNames,
			plugin.pendingWornAmmoExpenses,
			plugin.matchedUnequipsThisTick
		);
		context.setBarrowsWearBookedByPiece(plugin.barrowsWearBookedByPiece);
		context.setCargoHoldLoss(cargoHoldDiff);
		int currentTick = plugin.client != null ? plugin.client.getTickCount() : 0;
		Set<Integer> liveDropIntents = plugin.expireDropIntents(currentTick);
		if (!liveDropIntents.isEmpty())
		{
			for (int lostId : rawLosses.keySet())
			{
				int canonicalLost = plugin.itemManager != null ? plugin.itemManager.canonicalize(lostId) : lostId;
				if (liveDropIntents.contains(canonicalLost))
				{
					context.getDropIntentIds().add(canonicalLost);
					plugin.recentDropIntents.remove(canonicalLost);
				}
			}
		}
		Set<Integer> liveOpenIntents = plugin.expireOpenIntents(currentTick);
		if (!liveOpenIntents.isEmpty())
		{
			for (int lostId : rawLosses.keySet())
			{
				int canonicalLost = plugin.itemManager != null ? plugin.itemManager.canonicalize(lostId) : lostId;
				if (liveOpenIntents.contains(canonicalLost))
				{
					context.getOpenIntentIds().add(canonicalLost);
					plugin.recentOpenIntents.remove(canonicalLost);
				}
			}
		}
		cannonReversals.forEach(context::addExpenseReversal);

		if (plugin.client != null)
		{
			for (Map.Entry<Skill, Integer> entry : plugin.lastSkillXpTicks.entrySet())
			{
				int tick = entry.getValue();
				if (tick == currentTick || tick == currentTick - 1)
				{
					context.addActiveSkillingSkill(entry.getKey());
				}
			}

			boolean isFiremaking = plugin.lastTinderboxActionTick >= currentTick - 5 && plugin.lastTinderboxActionTick > 0
				|| plugin.lastFiremakingAnimTick >= currentTick - 3 && plugin.lastFiremakingAnimTick > 0
				|| plugin.lastFiremakingChatTick >= currentTick - 2 && plugin.lastFiremakingChatTick > 0;
			if (!isFiremaking && plugin.client.getLocalPlayer() != null)
			{
				int anim = plugin.client.getLocalPlayer().getAnimation();
				if (ActionClassifiers.isFiremakingAnimation(anim))
				{
					isFiremaking = true;
					plugin.lastFiremakingAnimTick = currentTick;
				}
			}
			if (isFiremaking)
			{
				context.addActiveSkillingSkill(Skill.FIREMAKING);
			}

			boolean isFarming = (plugin.lastFarmingActionTick >= currentTick - 8 && plugin.lastFarmingActionTick > 0)
				|| (plugin.lastFarmingAnimTick >= currentTick - 2 && plugin.lastFarmingAnimTick > 0)
				|| (plugin.lastFarmingChatTick >= currentTick - 2 && plugin.lastFarmingChatTick > 0);
			if (!isFarming && plugin.client.getLocalPlayer() != null)
			{
				int anim = plugin.client.getLocalPlayer().getAnimation();
				if (ActionClassifiers.isFarmingAnimation(anim))
				{
					isFarming = true;
					plugin.lastFarmingAnimTick = currentTick;
				}
			}
			if (isFarming)
			{
				context.addActiveSkillingSkill(Skill.FARMING);
			}

			boolean isAlchemy = (plugin.lastAlchActionTick >= currentTick - 3 && plugin.lastAlchActionTick > 0)
				|| (plugin.lastAlchAnimTick >= currentTick - 3 && plugin.lastAlchAnimTick > 0)
				|| (plugin.lastSkillXpTicks.containsKey(Skill.MAGIC) && plugin.lastSkillXpTicks.get(Skill.MAGIC) >= currentTick - 1);
			if (!isAlchemy && plugin.client.getLocalPlayer() != null)
			{
				int anim = plugin.client.getLocalPlayer().getAnimation();
				if (ActionClassifiers.isAlchemyAnimation(anim))
				{
					isAlchemy = true;
					plugin.lastAlchAnimTick = currentTick;
				}
			}
			if (isAlchemy)
			{
				context.setAlchemy(true);
			}

			// A deferred nature-rune pouch row is the same casting
			// evidence a raw nature loss would be — let the alch
			// handler still pair the cast.
			if (deferredRuneLosses.contains(ItemID.NATURERUNE))
			{
				context.setAlchemy(true);
			}

			context.setNotingService(currentTick - plugin.lastNotingServiceTick <= 5);
		}

		context.setBlastFurnaceActive(plugin.blastFurnaceTracker.isActive());
		plugin.reconciliationEngine.reconcile(context);

		// Drain any remaining consumed equipped ammo/thrown weapons or crumbled
		// jewelry into supply expenses
		if (!plugin.pendingWornAmmoExpenses.isEmpty())
		{
			for (Map.Entry<Integer, Integer> entry : plugin.pendingWornAmmoExpenses.entrySet())
			{
				int itemId = entry.getKey();
				int quantity = entry.getValue();
				String itemName = plugin.getItemName(itemId);
				long price = ItemPricing.priceOf(itemId, plugin.itemManager);
				context.addSupplyExpense(itemId, new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price));
				log.debug("Consumed equipped ammo/jewelry: {} x{} @ {} gp = {} gp", itemName, quantity, price, (long) quantity * price);
				plugin.removeRecentlyUnequipped(itemId, quantity);
			}
			plugin.pendingWornAmmoExpenses.clear();
		}

		if (currentTick > 0)
		{
			for (int id : context.getRecentlyDroppedItems().keySet())
			{
				plugin.droppedItemTicks.put(id, currentTick);
			}
			for (int id : context.getRecentlyDroppedOwnedItems().keySet())
			{
				plugin.droppedItemTicks.put(id, currentTick);
			}
			for (int id : context.getRecentlyExpensedDrops().keySet())
			{
				plugin.droppedItemTicks.put(id, currentTick);
			}
		}

		// Pop gold drop for the positive profit margin (if profitable and meets threshold)
		if (context.getAlchedTotalMargin() > 0 && plugin.config.showGoldDrops() && plugin.goldDropOverlay != null
			&& context.getAlchedTotalMargin() >= plugin.config.goldDropMinThreshold())
		{
			String dropText = "+" + QuantityFormatter.quantityToStackSize(context.getAlchedTotalMargin()) + " gp";
			plugin.goldDropOverlay.addDrop(dropText, context.getAlchedItemRawId(), 0);
		}

		if (!context.getRawGains().isEmpty() || !context.getDroppedGainsDeductions().isEmpty()
			|| !context.getSupplyExpenses().isEmpty() || !context.getExpenseReversals().isEmpty()
			|| !context.getConsumedSessionQuantities().isEmpty())
		{
			plugin.session = plugin.session.markConsumed(context.getConsumedSessionQuantities());
			plugin.session = plugin.session.withExpenseReversal(context.getExpenseReversals());
			plugin.processGainsLossesAndExpenses(
				context.getRawGains(),
				context.getDroppedGainsDeductions(),
				context.getSupplyExpenses(),
				context.getAlchedDropItemId(),
				context.getExemptProductIds(),
				context.getBasisExcludedExpenseIds()
			);

			if (context.getActiveSkillingSkills().contains(Skill.FIREMAKING))
			{
				plugin.lastTinderboxActionTick = -100;
			}
			if (context.getActiveSkillingSkills().contains(Skill.FARMING))
			{
				plugin.lastFarmingActionTick = -100;
				plugin.lastFarmingAnimTick = -100;
				plugin.lastFarmingChatTick = -100;
			}
		}
	}

	void onVarbitChanged(VarbitChanged event)
	{
		if (plugin.client == null || plugin.client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		int varbitId = event.getVarbitId();

		// The boat hold joins/leaves the merged snapshot with the spawned
		// state — absorb the transition so spawn/despawn never books the
		// hold's contents as a phantom gain or loss.
		if (varbitId == VarbitID.SAILING_BOAT_SPAWNED)
		{
			plugin.rebaselineGraceTicks = Math.max(plugin.rebaselineGraceTicks, 3);
			plugin.setNeedsRebaseline(true);
		}

		if (plugin.blastFurnaceTracker.isActive()
			&& BlastFurnaceHandler.DISPENSER_VARBIT_TO_BAR.containsKey(varbitId)
			&& !plugin.invalidVarbitIds.contains(varbitId))
		{
			try
			{
				int value = plugin.client.getVarbitValue(varbitId);
				plugin.blastFurnaceTracker.onDispenserVarbit(varbitId, value, plugin.client.getTickCount());
			}
			catch (IndexOutOfBoundsException e)
			{
				if (plugin.invalidVarbitIds.add(varbitId))
				{
					log.debug("Ignoring untracked varbit {} (not varp-backed)", varbitId);
				}
			}
		}
		if (plugin.isRunePouchVarbit(varbitId))
		{
			ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
			if (invContainer != null && plugin.hasRunePouch(invContainer))
			{
				processInventoryChanges(invContainer);
			}
		}
		else if (plugin.isMasterScrollBookVarbit(varbitId))
		{
			ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
			if (invContainer != null && plugin.hasMasterScrollBook(invContainer))
			{
				processInventoryChanges(invContainer);
			}
		}
		else if (varbitId == VarbitID.CHARGES_ASH_SANCTIFIER_QUANTITY)
		{
			ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
			if (invContainer != null && plugin.hasAshSanctifier(invContainer))
			{
				processInventoryChanges(invContainer);
			}
		}

		if (varbitId >= 0 && WeaponChargeTracker.isTrackedVarbit(varbitId))
		{
			int value;
			try
			{
				value = plugin.client.getVarbitValue(varbitId);
			}
			catch (IndexOutOfBoundsException e)
			{
				// Some tracked CHARGES_* varbits are not varp-backed / not present in this
				// client build; getVarbitValue throws for them. Log once per varbit ID.
				if (plugin.invalidVarbitIds.add(varbitId))
				{
					log.debug("Ignoring untracked varbit {} (not varp-backed)", varbitId);
				}
				return;
			}
			boolean trackingAllowed = !plugin.isTrackingSuppressed() && !plugin.isBankOrContainerOpen()
				&& !plugin.interfaceTracker.isShopOpen()
				&& !plugin.interfaceTracker.isNeedsRebaseline() && plugin.rebaselineGraceTicks <= 0
				&& !plugin.deathTracker.isPending();
			plugin.weaponChargeTracker.onVarbitChanged(varbitId, value, trackingAllowed,
				plugin.client.getTickCount());
		}
	}

	private void stripNonEconomicItems(Map<Integer, Integer> gains, Map<Integer, Integer> losses)
	{
		stripNonEconomicItems(gains, "gain");
		stripNonEconomicItems(losses, "loss");
	}

	private void stripNonEconomicItems(Map<Integer, Integer> diff, String direction)
	{
		Iterator<Map.Entry<Integer, Integer>> it = diff.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<Integer, Integer> entry = it.next();
			int canonicalId = plugin.itemManager != null ? plugin.itemManager.canonicalize(entry.getKey()) : entry.getKey();
			if (MinigameQuarantine.isMinigameOnlyItem(canonicalId))
			{
				it.remove();
				log.debug("Quarantined minigame item {} x{} ({} — cannot exist outside its minigame)",
					entry.getKey(), entry.getValue(), direction);
			}
			else if (ItemPricing.isValueless(canonicalId))
			{
				it.remove();
				log.debug("Stripped valueless item {} x{} ({} — untradeable and unalchable)",
					entry.getKey(), entry.getValue(), direction);
			}
			else if (!untradeableTrackingEnabled() && ItemPricing.isUnpricedUntradeable(canonicalId, plugin.itemManager))
			{
				it.remove();
				log.debug("Stripped unpriced untradeable {} x{} ({} — Track Untradeable Items off)",
					entry.getKey(), entry.getValue(), direction);
			}
		}
	}

	private boolean untradeableTrackingEnabled()
	{
		return plugin.config == null || plugin.config.trackUntradeableItems();
	}
}
