package com.coinflow.reconciliation;

import com.coinflow.CoinFlowConfig;
import com.coinflow.CoinFlowSession;
import com.coinflow.InventorySnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;

/**
 * Mutable context passed through the item reconciliation pipeline.
 * Contains raw gains and losses between inventory snapshots, equipment states,
 * and collects output supply expenses and dropped item deductions.
 */
public class ReconciliationContext
{
	@Getter
	private final Map<Integer, Integer> rawGains;

	@Getter
	private final Map<Integer, Integer> rawLosses;

	@Getter
	private final Map<Integer, Integer> recentlyUnequippedItems;

	@Getter
	private final Map<Integer, Integer> recentlyEquippedItems;

	@Getter
	private final Map<Integer, Integer> recentlyDroppedItems;

	@Getter
	private final Map<Integer, Integer> recentlyDroppedOwnedItems;

	@Getter
	private final Map<Integer, Integer> pendingWornAmmoExpenses;

	@Getter
	private final Map<Integer, Integer> matchedUnequipsThisTick;

	@Getter
	private final InventorySnapshot previousEquipmentSnapshot;

	@Getter
	private final ItemContainer currentEquipContainer;

	@Getter
	private final CoinFlowSession session;

	@Getter
	private final ItemManager itemManager;

	@Getter
	private final CoinFlowConfig config;

	@Getter
	private final Set<String> ignoredItemNames;

	// ── Collected Results ────────────────────────────────────────────────
	@Getter
	private final Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses = new HashMap<>();

	@Getter
	private final Map<Integer, CoinFlowSession.TrackedItem> droppedGainsDeductions = new HashMap<>();

	@Getter
	private final java.util.Set<Integer> exemptProductIds = new java.util.HashSet<>();

	@Getter
	@Setter
	private int alchedDropItemId = -1;

	@Getter
	@Setter
	private long alchedTotalMargin = 0L;

	@Getter
	@Setter
	private int alchedItemRawId = -1;

	@Setter
	private boolean firemaking = false;

	@Setter
	private boolean alchemy = false;

	@Getter
	private final Set<Skill> activeSkillingSkills = new java.util.HashSet<>();

	public void addActiveSkillingSkill(Skill skill)
	{
		if (skill != null)
		{
			activeSkillingSkills.add(skill);
		}
	}

	public boolean isFiremaking()
	{
		return firemaking || activeSkillingSkills.contains(Skill.FIREMAKING);
	}

	public boolean isAlchemy()
	{
		return alchemy || activeSkillingSkills.contains(Skill.MAGIC);
	}

	public void addExemptProduct(int itemId)
	{
		exemptProductIds.add(itemId);
	}

	public boolean isExemptProduct(int itemId)
	{
		return exemptProductIds.contains(itemId);
	}

	public ReconciliationContext(
		Map<Integer, Integer> rawGains,
		Map<Integer, Integer> rawLosses,
		Map<Integer, Integer> recentlyUnequippedItems,
		Map<Integer, Integer> recentlyEquippedItems,
		Map<Integer, Integer> recentlyDroppedItems,
		Map<Integer, Integer> recentlyDroppedOwnedItems,
		InventorySnapshot previousEquipmentSnapshot,
		ItemContainer currentEquipContainer,
		CoinFlowSession session,
		ItemManager itemManager,
		CoinFlowConfig config,
		Set<String> ignoredItemNames,
		Map<Integer, Integer> pendingWornAmmoExpenses,
		Map<Integer, Integer> matchedUnequipsThisTick)
	{
		this.rawGains = new HashMap<>(rawGains);
		this.rawLosses = new HashMap<>(rawLosses);
		this.recentlyUnequippedItems = recentlyUnequippedItems;
		this.recentlyEquippedItems = recentlyEquippedItems;
		this.recentlyDroppedItems = recentlyDroppedItems;
		this.recentlyDroppedOwnedItems = recentlyDroppedOwnedItems;
		this.previousEquipmentSnapshot = previousEquipmentSnapshot;
		this.currentEquipContainer = currentEquipContainer;
		this.session = session;
		this.itemManager = itemManager;
		this.config = config;
		this.ignoredItemNames = ignoredItemNames;
		this.pendingWornAmmoExpenses = pendingWornAmmoExpenses != null ? pendingWornAmmoExpenses : new HashMap<>();
		this.matchedUnequipsThisTick = matchedUnequipsThisTick != null ? matchedUnequipsThisTick : new HashMap<>();
	}

	public ReconciliationContext(
		Map<Integer, Integer> rawGains,
		Map<Integer, Integer> rawLosses,
		Map<Integer, Integer> recentlyUnequippedItems,
		Map<Integer, Integer> recentlyEquippedItems,
		Map<Integer, Integer> recentlyDroppedItems,
		Map<Integer, Integer> recentlyDroppedOwnedItems,
		InventorySnapshot previousEquipmentSnapshot,
		ItemContainer currentEquipContainer,
		CoinFlowSession session,
		ItemManager itemManager,
		CoinFlowConfig config,
		Set<String> ignoredItemNames,
		Map<Integer, Integer> pendingWornAmmoExpenses)
	{
		this(rawGains, rawLosses, recentlyUnequippedItems, recentlyEquippedItems, recentlyDroppedItems,
			recentlyDroppedOwnedItems, previousEquipmentSnapshot, currentEquipContainer, session, itemManager, config, ignoredItemNames,
			pendingWornAmmoExpenses, new HashMap<>());
	}

	public ReconciliationContext(
		Map<Integer, Integer> rawGains,
		Map<Integer, Integer> rawLosses,
		Map<Integer, Integer> recentlyUnequippedItems,
		Map<Integer, Integer> recentlyEquippedItems,
		Map<Integer, Integer> recentlyDroppedItems,
		Map<Integer, Integer> recentlyDroppedOwnedItems,
		InventorySnapshot previousEquipmentSnapshot,
		ItemContainer currentEquipContainer,
		CoinFlowSession session,
		ItemManager itemManager,
		CoinFlowConfig config,
		Set<String> ignoredItemNames)
	{
		this(rawGains, rawLosses, recentlyUnequippedItems, recentlyEquippedItems, recentlyDroppedItems,
			recentlyDroppedOwnedItems, previousEquipmentSnapshot, currentEquipContainer, session, itemManager, config, ignoredItemNames, new HashMap<>());
	}

	public ReconciliationContext(
		Map<Integer, Integer> rawGains,
		Map<Integer, Integer> rawLosses,
		Map<Integer, Integer> recentlyUnequippedItems,
		Map<Integer, Integer> recentlyEquippedItems,
		Map<Integer, Integer> recentlyDroppedItems,
		InventorySnapshot previousEquipmentSnapshot,
		ItemContainer currentEquipContainer,
		CoinFlowSession session,
		ItemManager itemManager,
		CoinFlowConfig config,
		Set<String> ignoredItemNames)
	{
		this(rawGains, rawLosses, recentlyUnequippedItems, recentlyEquippedItems, recentlyDroppedItems,
			new HashMap<>(), previousEquipmentSnapshot, currentEquipContainer, session, itemManager, config, ignoredItemNames);
	}

	public String getItemName(int itemId)
	{
		ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp != null ? comp.getName() : "Unknown";
	}

	public boolean isIgnored(String itemName)
	{
		if (ignoredItemNames == null || ignoredItemNames.isEmpty() || itemName == null)
		{
			return false;
		}
		return ignoredItemNames.contains(itemName.toLowerCase());
	}

	public void addSupplyExpense(int itemId, CoinFlowSession.TrackedItem expenseItem)
	{
		supplyExpenses.merge(itemId, expenseItem,
			(a, b) -> a.withAdditionalQuantity(b.getQuantity()));
	}

	public void addDroppedDeduction(int itemId, CoinFlowSession.TrackedItem droppedItem)
	{
		droppedGainsDeductions.merge(itemId, droppedItem,
			(a, b) -> a.withAdditionalQuantity(b.getQuantity()));
	}
}
