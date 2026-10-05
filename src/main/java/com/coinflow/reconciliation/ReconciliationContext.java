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

	/**
	 * Canonical item id -> quantity dropped while {@link #isDropsAsSpent()} was
	 * active. These were recorded as supply expenses; picking them back up
	 * reverses the expense instead of crediting a gain.
	 */
	@Getter
	private final Map<Integer, Integer> recentlyExpensedDrops;

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
	private final com.coinflow.GrandExchangeTracker grandExchangeTracker;

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

	/**
	 * Item ids whose supply expense this tick is denominated in sub-units
	 * (potion doses, food portions) rather than whole items. These must not be
	 * repriced against whole-item cost basis.
	 */
	@Getter
	private final java.util.Set<Integer> fractionalExpenseIds = new java.util.HashSet<>();

	/**
	 * Session-gained quantities that were consumed in place this tick (eaten,
	 * drunk, used). Applied to the session so those units cannot be deducted a
	 * second time when identical pre-session items are dropped or sold later.
	 */
	@Getter
	private final Map<Integer, Long> consumedSessionQuantities = new HashMap<>();

	/**
	 * Expensed drops picked back up this tick (canonical id -> quantity). The
	 * matching expense is reversed on the session instead of crediting a gain.
	 */
	@Getter
	private final Map<Integer, Long> expenseReversals = new HashMap<>();

	/**
	 * Item ids whose supply expense this tick is a drop rather than a
	 * consumption. Excluded from cost basis repricing so a later pickup does
	 * not orphan the basis lot.
	 */
	@Getter
	private final java.util.Set<Integer> droppedExpenseIds = new java.util.HashSet<>();

	/**
	 * Canonical item ids the player recently clicked "Drop" on. A loss of one of
	 * these this tick is a drop, not a consumption: consumable handlers must
	 * leave it for the own-drop path rather than expensing it.
	 */
	@Getter
	private final java.util.Set<Integer> dropIntentIds = new java.util.HashSet<>();

	public boolean isDropIntent(int canonicalItemId)
	{
		return dropIntentIds.contains(canonicalItemId);
	}

	/**
	 * Whether "Drop" clicks should be recorded as supply expenses. Requires
	 * spent tracking, since expenses are discarded when it is off and the drop
	 * would otherwise vanish from both gross and spent.
	 */
	public boolean isDropsAsSpent()
	{
		return config != null && config.trackSpent() && config.countDropsAsSpent();
	}

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
	@Setter
	private boolean notingService = false;

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
		this(rawGains, rawLosses, recentlyUnequippedItems, recentlyEquippedItems, recentlyDroppedItems,
			recentlyDroppedOwnedItems, previousEquipmentSnapshot, currentEquipContainer, session, null,
			itemManager, config, ignoredItemNames, pendingWornAmmoExpenses, matchedUnequipsThisTick);
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
		com.coinflow.GrandExchangeTracker grandExchangeTracker,
		ItemManager itemManager,
		CoinFlowConfig config,
		Set<String> ignoredItemNames,
		Map<Integer, Integer> pendingWornAmmoExpenses,
		Map<Integer, Integer> matchedUnequipsThisTick)
	{
		this(rawGains, rawLosses, recentlyUnequippedItems, recentlyEquippedItems, recentlyDroppedItems,
			recentlyDroppedOwnedItems, new HashMap<>(), previousEquipmentSnapshot, currentEquipContainer, session,
			grandExchangeTracker, itemManager, config, ignoredItemNames, pendingWornAmmoExpenses, matchedUnequipsThisTick);
	}

	public ReconciliationContext(
		Map<Integer, Integer> rawGains,
		Map<Integer, Integer> rawLosses,
		Map<Integer, Integer> recentlyUnequippedItems,
		Map<Integer, Integer> recentlyEquippedItems,
		Map<Integer, Integer> recentlyDroppedItems,
		Map<Integer, Integer> recentlyDroppedOwnedItems,
		Map<Integer, Integer> recentlyExpensedDrops,
		InventorySnapshot previousEquipmentSnapshot,
		ItemContainer currentEquipContainer,
		CoinFlowSession session,
		com.coinflow.GrandExchangeTracker grandExchangeTracker,
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
		this.recentlyExpensedDrops = recentlyExpensedDrops != null ? recentlyExpensedDrops : new HashMap<>();
		this.previousEquipmentSnapshot = previousEquipmentSnapshot;
		this.currentEquipContainer = currentEquipContainer;
		this.session = session;
		this.grandExchangeTracker = grandExchangeTracker;
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
			(a, b) -> a.merge(b));
	}

	/**
	 * Records a supply expense denominated in sub-units of the item (a dose or
	 * portion), excluding it from whole-item cost basis repricing.
	 */
	public void addFractionalSupplyExpense(int itemId, CoinFlowSession.TrackedItem expenseItem)
	{
		fractionalExpenseIds.add(itemId);
		addSupplyExpense(itemId, expenseItem);
	}

	/**
	 * Records a dropped item as a supply expense, excluding it from cost basis
	 * repricing so a later pickup can reverse it cleanly.
	 */
	public void addDropExpense(int itemId, CoinFlowSession.TrackedItem droppedItem)
	{
		droppedExpenseIds.add(itemId);
		addSupplyExpense(itemId, droppedItem);
	}

	/**
	 * Expense ids that must not be repriced against whole-item cost basis:
	 * fractional (dose/portion) expenses and drop expenses.
	 */
	public java.util.Set<Integer> getBasisExcludedExpenseIds()
	{
		if (droppedExpenseIds.isEmpty())
		{
			return fractionalExpenseIds;
		}
		java.util.Set<Integer> excluded = new java.util.HashSet<>(fractionalExpenseIds);
		excluded.addAll(droppedExpenseIds);
		return excluded;
	}

	public void addExpenseReversal(int itemId, long quantity)
	{
		if (quantity > 0)
		{
			expenseReversals.merge(itemId, quantity, Long::sum);
		}
	}

	public void addDroppedDeduction(int itemId, CoinFlowSession.TrackedItem droppedItem)
	{
		droppedGainsDeductions.merge(itemId, droppedItem,
			(a, b) -> a.merge(b));
	}

	/**
	 * Marks up to {@code quantity} session-gained units of {@code itemId} as
	 * consumed in place, retiring them from future drop/sale deductions.
	 */
	public void markSessionGainConsumed(int itemId, long quantity)
	{
		if (quantity > 0)
		{
			consumedSessionQuantities.merge(itemId, quantity, Long::sum);
		}
	}
}
