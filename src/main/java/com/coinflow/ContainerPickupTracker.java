package com.coinflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * Ground-pickup bookkeeping for items routed into open secondary containers
 * (looting bag, gem bag, herb sack, fish barrel, seed box, log basket) where
 * the pickup produces no inventory diff: Take clicks are matched to tile
 * despawns, pending pickups are finalized on the next game tick against the
 * items that did reach inventory, and PvP loot-key chests are diffed for
 * gains on open.
 */
@Slf4j
final class ContainerPickupTracker
{
	private final CoinFlowPlugin plugin;

	private static final class TakeClick
	{
		final int itemId;
		final WorldPoint point;
		final int tick;

		TakeClick(int itemId, WorldPoint point, int tick)
		{
			this.itemId = itemId;
			this.point = point;
			this.tick = tick;
		}
	}

	final List<TakeClick> recentTakeClicks = new ArrayList<>();
	final List<CoinFlowPlugin.LootPickup> pendingLootingBagPickups = new ArrayList<>();
	final List<CoinFlowPlugin.LootPickup> pendingGemBagPickups = new ArrayList<>();
	final List<CoinFlowPlugin.LootPickup> pendingHerbSackPickups = new ArrayList<>();
	final List<CoinFlowPlugin.LootPickup> pendingFishBarrelPickups = new ArrayList<>();
	final List<CoinFlowPlugin.LootPickup> pendingSeedBoxPickups = new ArrayList<>();
	final List<CoinFlowPlugin.LootPickup> pendingLogBasketPickups = new ArrayList<>();
	final Map<Integer, Map<Integer, Integer>> previousPvpKeyContainers = new HashMap<>();

	private static final int[] PVP_LOOT_KEY_CONTAINERS = {
		InventoryID.DEADMAN_LOOT_INV0,
		InventoryID.DEADMAN_LOOT_INV1,
		InventoryID.DEADMAN_LOOT_INV2,
		InventoryID.DEADMAN_LOOT_INV3,
		InventoryID.DEADMAN_LOOT_INV4
	};

	ContainerPickupTracker(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	static boolean isPvpKeyContainer(int containerId)
	{
		for (int id : PVP_LOOT_KEY_CONTAINERS)
		{
			if (id == containerId)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Records a Take menu click at its scene position so a matching despawn
	 * within 5 ticks can be attributed to a secondary-container pickup.
	 */
	void recordTakeClick(int itemId, int sceneX, int sceneY, int tick)
	{
		if (plugin.client != null && plugin.client.getLocalPlayer() != null)
		{
			try
			{
				WorldView worldView = plugin.client.getTopLevelWorldView();
				WorldPoint targetPoint = worldView != null
					? WorldPoint.fromScene(worldView, sceneX, sceneY, worldView.getPlane())
					: null;
				if (targetPoint != null)
				{
					recentTakeClicks.add(new TakeClick(itemId, targetPoint, tick));
				}
			}
			catch (Exception e)
			{
				log.debug("Failed to calculate WorldPoint from scene coords: {}", e.getMessage());
			}
		}
	}

	void ageTakeClicks(int currentTick)
	{
		recentTakeClicks.removeIf(click -> currentTick - click.tick > 5);
	}

	void onItemDespawned(ItemDespawned event)
	{
		if (plugin.client == null || plugin.client.getLocalPlayer() == null)
		{
			return;
		}

		ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
		if (invContainer == null)
		{
			return;
		}

		boolean canLootBag = plugin.hasOpenLootingBag(invContainer)
			&& plugin.client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1;
		boolean canGemBag = plugin.hasOpenGemBag(invContainer);
		boolean canHerbSack = plugin.hasOpenHerbSack(invContainer);
		boolean canSeedBox = plugin.hasOpenSeedBox(invContainer);
		ItemContainer wornContainer = plugin.client.getItemContainer(InventoryID.WORN);
		boolean canLogBasket = plugin.hasOpenLogBasket(invContainer, wornContainer);

		if (!canLootBag && !canGemBag && !canHerbSack && !canSeedBox && !canLogBasket)
		{
			return;
		}

		TileItem tileItem = event.getItem();
		Tile tile = event.getTile();
		if (tileItem == null || tile == null)
		{
			return;
		}

		WorldPoint itemPoint = tile.getWorldLocation();
		WorldPoint playerPoint = plugin.client.getLocalPlayer().getWorldLocation();
		if (playerPoint.distanceTo(itemPoint) > 1)
		{
			return;
		}

		int currentTick = plugin.client.getTickCount();
		TakeClick matchedClick = null;
		for (TakeClick click : recentTakeClicks)
		{
			if (click.itemId == tileItem.getId()
				&& click.point.distanceTo(itemPoint) <= 1
				&& currentTick - click.tick <= 5)
			{
				matchedClick = click;
				break;
			}
		}

		if (matchedClick == null)
		{
			return;
		}

		recentTakeClicks.remove(matchedClick);

		ItemComposition comp = plugin.itemManager != null ? plugin.itemManager.getItemComposition(tileItem.getId()) : null;
		if (comp != null && !comp.isTradeable())
		{
			return;
		}

		if (canGemBag && InventorySnapshotService.isUncutGem(tileItem.getId()))
		{
			pendingGemBagPickups.add(new CoinFlowPlugin.LootPickup(tileItem.getId(), tileItem.getQuantity()));
		}
		else if (canHerbSack && InventorySnapshotService.isGrimyHerb(tileItem.getId()))
		{
			pendingHerbSackPickups.add(new CoinFlowPlugin.LootPickup(tileItem.getId(), tileItem.getQuantity()));
		}
		else if (canSeedBox && comp != null && ConsumableRegistry.isSeed(comp.getName()))
		{
			pendingSeedBoxPickups.add(new CoinFlowPlugin.LootPickup(tileItem.getId(), tileItem.getQuantity()));
		}
		else if (canLogBasket && comp != null && (ConsumableRegistry.isLog(comp.getName()) || tileItem.getId() == ItemID.HOLLOW_BARK))
		{
			pendingLogBasketPickups.add(new CoinFlowPlugin.LootPickup(tileItem.getId(), tileItem.getQuantity()));
		}
		else if (canLootBag)
		{
			pendingLootingBagPickups.add(new CoinFlowPlugin.LootPickup(tileItem.getId(), tileItem.getQuantity()));
		}
	}

	/**
	 * Finalizes pending pickups on the game tick: items that reached a secondary
	 * container without an inventory diff are credited as gains. Returns false
	 * when tracking is uninitialized and the caller should bail out of the tick.
	 */
	boolean finalizePendingPickups()
	{
		if (plugin.session == null || !plugin.snapshotInitialized)
		{
			pendingLootingBagPickups.clear();
			pendingGemBagPickups.clear();
			pendingHerbSackPickups.clear();
			pendingFishBarrelPickups.clear();
			pendingSeedBoxPickups.clear();
			pendingLogBasketPickups.clear();
			plugin.invItemsGainedThisTick.clear();
			return false;
		}

		// Finalize pending looting bag pickups from ground
		if (!pendingLootingBagPickups.isEmpty())
		{
			if (plugin.client != null)
			{
				ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
				if (invContainer != null && plugin.hasOpenLootingBag(invContainer))
				{
					boolean added = false;
					for (CoinFlowPlugin.LootPickup pickup : pendingLootingBagPickups)
					{
						int invGained = plugin.invItemsGainedThisTick.getOrDefault(pickup.itemId, 0);
						if (pickup.qty > invGained)
						{
							int containerQty = pickup.qty - invGained;
							plugin.getSnapshotService().addLootingBagPendingItem(pickup.itemId, containerQty);
							plugin.invItemsGainedThisTick.put(pickup.itemId, 0);
							added = true;
						}
						else
						{
							plugin.invItemsGainedThisTick.put(pickup.itemId, invGained - pickup.qty);
						}
					}
					if (added)
					{
						plugin.processInventoryChanges(invContainer);
					}
				}
			}
			pendingLootingBagPickups.clear();
		}

		// Finalize pending gem bag pickups (ground take or skilling into open gem bag)
		if (!pendingGemBagPickups.isEmpty())
		{
			if (plugin.client != null)
			{
				ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
				if (invContainer != null && plugin.hasOpenGemBag(invContainer))
				{
					drainPendingContainerPickups(pendingGemBagPickups);
				}
			}
			pendingGemBagPickups.clear();
		}

		// Finalize pending herb sack pickups
		if (!pendingHerbSackPickups.isEmpty())
		{
			if (plugin.client != null)
			{
				ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
				if (invContainer != null && plugin.hasOpenHerbSack(invContainer))
				{
					drainPendingContainerPickups(pendingHerbSackPickups);
				}
			}
			pendingHerbSackPickups.clear();
		}

		// Finalize pending fish barrel pickups
		if (!pendingFishBarrelPickups.isEmpty())
		{
			if (plugin.client != null)
			{
				ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
				ItemContainer wornContainer = plugin.client.getItemContainer(InventoryID.WORN);
				if (plugin.hasOpenFishBarrel(invContainer, wornContainer))
				{
					drainPendingContainerPickups(pendingFishBarrelPickups);
				}
			}
			pendingFishBarrelPickups.clear();
		}

		// Finalize pending seed box pickups
		if (!pendingSeedBoxPickups.isEmpty())
		{
			if (plugin.client != null)
			{
				ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
				if (invContainer != null && plugin.hasOpenSeedBox(invContainer))
				{
					drainPendingContainerPickups(pendingSeedBoxPickups);
				}
			}
			pendingSeedBoxPickups.clear();
		}

		// Finalize pending log basket pickups
		if (!pendingLogBasketPickups.isEmpty())
		{
			if (plugin.client != null)
			{
				ItemContainer invContainer = plugin.client.getItemContainer(InventoryID.INV);
				ItemContainer wornContainer = plugin.client.getItemContainer(InventoryID.WORN);
				if (plugin.hasOpenLogBasket(invContainer, wornContainer))
				{
					drainPendingContainerPickups(pendingLogBasketPickups);
				}
			}
			pendingLogBasketPickups.clear();
		}
		plugin.invItemsGainedThisTick.clear();
		return true;
	}

	void checkPvpKeyContainers()
	{
		if (plugin.client == null || plugin.session == null)
		{
			return;
		}

		Map<Integer, Integer> pvpGains = new HashMap<>();
		for (int containerId : PVP_LOOT_KEY_CONTAINERS)
		{
			ItemContainer container = plugin.client.getItemContainer(containerId);
			Map<Integer, Integer> currentItems = new HashMap<>();
			if (container != null && container.getItems() != null)
			{
				for (Item item : container.getItems())
				{
					if (item != null && item.getId() > 0 && item.getQuantity() > 0)
					{
						currentItems.merge(item.getId(), item.getQuantity(), Integer::sum);
					}
				}
			}

			Map<Integer, Integer> prevItems = previousPvpKeyContainers.getOrDefault(containerId, Collections.emptyMap());
			for (Map.Entry<Integer, Integer> entry : currentItems.entrySet())
			{
				int id = entry.getKey();
				int qty = entry.getValue();
				int prevQty = prevItems.getOrDefault(id, 0);
				if (qty > prevQty)
				{
					pvpGains.merge(id, qty - prevQty, Integer::sum);
				}
			}

			previousPvpKeyContainers.put(containerId, currentItems);
		}

		if (!pvpGains.isEmpty())
		{
			log.debug("PvP Loot Chest opened/updated, crediting key loot: {}", pvpGains);
			plugin.processGains(pvpGains);
		}
	}

	/**
	 * Drains pending pickups into container gains, deducting any items already counted in inventory,
	 * applying profit gains, and clearing the pending pickups list.
	 *
	 * @param pickups list of pending item pickups for a secondary container (gem bag, herb sack, etc.)
	 */
	void drainPendingContainerPickups(List<CoinFlowPlugin.LootPickup> pickups)
	{
		if (pickups == null || pickups.isEmpty())
		{
			return;
		}

		Map<Integer, Integer> containerGains = new HashMap<>();
		for (CoinFlowPlugin.LootPickup pickup : pickups)
		{
			int invGained = plugin.invItemsGainedThisTick.getOrDefault(pickup.itemId, 0);
			if (pickup.qty > invGained)
			{
				int containerQty = pickup.qty - invGained;
				containerGains.merge(pickup.itemId, containerQty, Integer::sum);
				plugin.invItemsGainedThisTick.put(pickup.itemId, 0);
			}
			else
			{
				plugin.invItemsGainedThisTick.put(pickup.itemId, invGained - pickup.qty);
			}
		}

		// Death-drop pickups into an open container still count as recovery —
		// this path bypasses the handler chain just like own-drop pickups.
		plugin.matchDeathPickupGains(containerGains);

		// Re-picking up the player's own dropped items into an open bag/box/sack
		// must not count as fresh profit — this path bypasses the handler chain.
		Map<Integer, Long> expenseReversals = new HashMap<>();
		com.coinflow.reconciliation.DroppedItemPickupHandler.applyDropReconciliation(
			containerGains, plugin.recentlyDroppedItems, plugin.recentlyDroppedOwnedItems, plugin.recentlyExpensedDrops,
			expenseReversals, plugin.itemManager);
		plugin.session = plugin.session.withExpenseReversal(expenseReversals);

		if (!containerGains.isEmpty())
		{
			plugin.processGains(containerGains);
		}
		pickups.clear();
	}

	/**
	 * Clears all transient pickup/click buffers and PvP-key baselines.
	 */
	void resetTransient()
	{
		recentTakeClicks.clear();
		pendingLootingBagPickups.clear();
		pendingGemBagPickups.clear();
		pendingHerbSackPickups.clear();
		pendingFishBarrelPickups.clear();
		pendingSeedBoxPickups.clear();
		pendingLogBasketPickups.clear();
		plugin.invItemsGainedThisTick.clear();
		previousPvpKeyContainers.clear();
	}
}
