package com.coinflow;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable snapshot of inventory contents.
 * Maps item ID -> quantity. Used for diffing between ticks.
 */
public final class InventorySnapshot
{
	private final Map<Integer, Integer> items;

	private InventorySnapshot(Map<Integer, Integer> items)
	{
		this.items = Collections.unmodifiableMap(new HashMap<>(items));
	}

	/**
	 * Creates a snapshot from raw item arrays (as returned by ItemContainer).
	 *
	 * @param itemIds    array of item IDs in each slot (-1 for empty)
	 * @param quantities array of quantities in each slot
	 * @return a new immutable snapshot
	 */
	public static InventorySnapshot fromArrays(int[] itemIds, int[] quantities)
	{
		Map<Integer, Integer> map = new HashMap<>();

		if (itemIds == null || quantities == null)
		{
			return new InventorySnapshot(map);
		}

		for (int i = 0; i < itemIds.length; i++)
		{
			int id = itemIds[i];
			int qty = quantities[i];

			if (id <= 0 || qty <= 0)
			{
				continue;
			}

			map.merge(id, qty, Integer::sum);
		}

		return new InventorySnapshot(map);
	}

	/**
	 * Creates an immutable snapshot from an existing map of item IDs to quantities.
	 *
	 * @param items map of itemId -> quantity
	 * @return a new immutable snapshot
	 */
	public static InventorySnapshot fromMap(Map<Integer, Integer> items)
	{
		if (items == null || items.isEmpty())
		{
			return empty();
		}

		Map<Integer, Integer> map = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : items.entrySet())
		{
			Integer id = entry.getKey();
			Integer qty = entry.getValue();
			if (id != null && id > 0 && qty != null && qty > 0)
			{
				map.put(id, qty);
			}
		}

		return new InventorySnapshot(map);
	}

	/**
	 * Creates an empty snapshot (used as the initial baseline).
	 */
	public static InventorySnapshot empty()
	{
		return new InventorySnapshot(Collections.emptyMap());
	}

	/**
	 * Computes the items GAINED between the previous snapshot and this snapshot.
	 * Only returns positive deltas (items that increased in quantity or appeared).
	 *
	 * @param previous the previous snapshot to diff against
	 * @return map of itemId -> quantity gained (only positive deltas)
	 */
	public Map<Integer, Integer> getGainedItems(InventorySnapshot previous)
	{
		Map<Integer, Integer> gains = new HashMap<>();

		for (Map.Entry<Integer, Integer> entry : this.items.entrySet())
		{
			int itemId = entry.getKey();
			int currentQty = entry.getValue();
			int previousQty = previous.items.getOrDefault(itemId, 0);

			int delta = currentQty - previousQty;
			if (delta > 0)
			{
				gains.put(itemId, delta);
			}
		}

		return gains;
	}

	/**
	 * Computes the items LOST between the previous snapshot and this snapshot.
	 * Only returns positive deltas of reduced quantities (items that decreased in quantity or disappeared).
	 *
	 * @param previous the previous snapshot to diff against
	 * @return map of itemId -> quantity lost
	 */
	public Map<Integer, Integer> getLostItems(InventorySnapshot previous)
	{
		Map<Integer, Integer> losses = new HashMap<>();

		for (Map.Entry<Integer, Integer> entry : previous.items.entrySet())
		{
			int itemId = entry.getKey();
			int previousQty = entry.getValue();
			int currentQty = this.items.getOrDefault(itemId, 0);

			int delta = previousQty - currentQty;
			if (delta > 0)
			{
				losses.put(itemId, delta);
			}
		}

		return losses;
	}

	/**
	 * Returns the underlying item map (unmodifiable).
	 */
	public Map<Integer, Integer> getItems()
	{
		return items;
	}
}
