package com.coinflow;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.QuantityFormatter;

/**
 * Applies reconciliation output to the session: prices raw gains, reprices
 * supply expenses against FIFO cost basis, publishes the new
 * {@link CoinFlowSession}, fires gold-drop notifications, evaluates goal
 * completion, and owns the ignored-items filter set.
 */
@Slf4j
final class SessionApplier
{
	private final CoinFlowPlugin plugin;

	SessionApplier(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	/**
	 * Applies GE settlement entries to the session and pops a gold drop for the
	 * realized net delta (flip margin, drift correction) when positive.
	 */
	void applyGeLedger(GrandExchangeTracker.GeLedger ledger, int soldItemId)
	{
		if (ledger.isEmpty() || plugin.session == null)
		{
			return;
		}

		Map<Integer, CoinFlowSession.TrackedItem> expenses =
			(plugin.config != null && !plugin.config.trackSpent()) ? Collections.emptyMap() : ledger.expenses;
		plugin.session = plugin.session.withGainsLossesAndExpenses(ledger.gains, ledger.deductions, expenses);

		if (plugin.config != null && plugin.config.showGoldDrops() && plugin.goldDropOverlay != null
			&& ledger.netDelta > 0 && ledger.netDelta >= plugin.config.goldDropMinThreshold())
		{
			String dropText = "+" + QuantityFormatter.quantityToStackSize(ledger.netDelta) + " gp";
			plugin.goldDropOverlay.addDrop(dropText, soldItemId, 0);
		}
	}

	void processGains(Map<Integer, Integer> rawGains)
	{
		processGainsLossesAndExpenses(rawGains, Collections.emptyMap(), Collections.emptyMap(), -1);
	}

	/**
	 * Processes raw item gains and supply expenses atomically:
	 * looks up prices, updates the session, and triggers gold drops for positive gains.
	 */
	void processGainsAndExpenses(Map<Integer, Integer> rawGains, Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses)
	{
		processGainsLossesAndExpenses(rawGains, Collections.emptyMap(), supplyExpenses, -1);
	}

	/**
	 * Processes raw item gains, dropped item deductions, and supply expenses atomically:
	 * looks up prices, updates the session, and triggers gold drops for positive gains.
	 */
	void processGainsLossesAndExpenses(
		Map<Integer, Integer> rawGains,
		Map<Integer, CoinFlowSession.TrackedItem> droppedGainsDeductions,
		Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses)
	{
		processGainsLossesAndExpenses(rawGains, droppedGainsDeductions, supplyExpenses, -1);
	}

	void processGainsLossesAndExpenses(
		Map<Integer, Integer> rawGains,
		Map<Integer, CoinFlowSession.TrackedItem> droppedGainsDeductions,
		Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses,
		int alchedDropItemId)
	{
		processGainsLossesAndExpenses(rawGains, droppedGainsDeductions, supplyExpenses, alchedDropItemId, Collections.emptySet());
	}

	void processGainsLossesAndExpenses(
		Map<Integer, Integer> rawGains,
		Map<Integer, CoinFlowSession.TrackedItem> droppedGainsDeductions,
		Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses,
		int alchedDropItemId,
		Set<Integer> exemptProductIds)
	{
		processGainsLossesAndExpenses(rawGains, droppedGainsDeductions, supplyExpenses, alchedDropItemId,
			exemptProductIds, Collections.emptySet());
	}

	/**
	 * Processes raw item gains, dropped item deductions, and supply expenses atomically:
	 * looks up prices, updates the session, and triggers gold drops for positive gains,
	 * skipping generic coins gold drop if an alchemy drop was already displayed.
	 *
	 * @param fractionalExpenseIds expense ids excluded from whole-item cost basis
	 *        repricing: dose/portion expenses and drop expenses
	 */
	void processGainsLossesAndExpenses(
		Map<Integer, Integer> rawGains,
		Map<Integer, CoinFlowSession.TrackedItem> droppedGainsDeductions,
		Map<Integer, CoinFlowSession.TrackedItem> supplyExpenses,
		int alchedDropItemId,
		Set<Integer> exemptProductIds,
		Set<Integer> fractionalExpenseIds)
	{
		Map<Integer, CoinFlowSession.TrackedItem> trackedGains = new HashMap<>();

		for (Map.Entry<Integer, Integer> entry : rawGains.entrySet())
		{
			int rawItemId = entry.getKey();
			int quantity = entry.getValue();

			// Canonicalize: resolve noted items, placeholders, and worn items to base ID (Fixes Bug 6)
			int itemId = plugin.itemManager.canonicalize(rawItemId);

			// Check ignored items list
			String itemName = plugin.getItemName(itemId);
			if (isIgnored(itemName))
			{
				continue;
			}

			// Skip trash / empty byproduct containers (e.g. pie dish, bowl, empty vial), unless intentionally produced
			if (ConsumableRegistry.isByproduct(itemName) && (exemptProductIds == null || !exemptProductIds.contains(itemId)))
			{
				continue;
			}

			// Look up price (Fixes Bug 7: coins and platinum tokens)
			long price = ItemPricing.priceOf(itemId, plugin.itemManager);

			CoinFlowSession.TrackedItem trackedItem =
				new CoinFlowSession.TrackedItem(itemId, itemName, quantity, price);
			trackedGains.merge(itemId, trackedItem,
				(existing, added) -> existing.merge(added));

			log.debug("Gained: {} x{} @ {} gp each = {} gp",
				itemName, quantity, price, (long) quantity * price);
		}

		// Drain basis even when spent tracking is off or the expense is ignored so
		// lots never outlive the items they priced.
		Map<Integer, CoinFlowSession.TrackedItem> repricedExpenses =
			plugin.grandExchangeTracker.repriceFromBasis(supplyExpenses, fractionalExpenseIds);
		Map<Integer, CoinFlowSession.TrackedItem> effectiveExpenses =
			(plugin.config != null && !plugin.config.trackSpent()) ? Collections.emptyMap()
				: filterIgnoredExpenses(repricedExpenses);

		if (!trackedGains.isEmpty() || !droppedGainsDeductions.isEmpty() || !effectiveExpenses.isEmpty())
		{
			plugin.session = plugin.session.withGainsLossesAndExpenses(trackedGains, droppedGainsDeductions, effectiveExpenses);

			// Gold drops are kept purely for positive income to prevent screen clutter
			if (plugin.config.showGoldDrops() && plugin.goldDropOverlay != null && !trackedGains.isEmpty())
			{
				int stackIndex = 0;
				for (CoinFlowSession.TrackedItem item : trackedGains.values())
				{
					// If a gold drop was already displayed specifically for an alched item, skip showing coins for it
					if (alchedDropItemId != -1 && item.getItemId() == ItemID.COINS)
					{
						continue;
					}

					long value = item.getTotalValue();
					if (value >= plugin.config.goldDropMinThreshold())
					{
						String dropText = "+" + QuantityFormatter.quantityToStackSize(value) + " gp";
						plugin.goldDropOverlay.addDrop(dropText, item.getItemId(), stackIndex * 18);
						stackIndex++;
					}
				}
			}
		}
	}

	/**
	 * Drops ignored items from a supply-expense batch. Synthetic GP rows
	 * ("Death", "GE Tax", "Blast Furnace coffer" — see
	 * {@link CoinFlowSession#isSyntheticRowId}) are exempt by id — their
	 * backing item names ("Skull", coin piles) are real items a player may
	 * legitimately ignore without wanting to hide bookkeeping rows.
	 */
	private Map<Integer, CoinFlowSession.TrackedItem> filterIgnoredExpenses(
		Map<Integer, CoinFlowSession.TrackedItem> expenses)
	{
		if (plugin.ignoredItemNames == null || plugin.ignoredItemNames.isEmpty() || expenses.isEmpty())
		{
			return expenses;
		}
		Map<Integer, CoinFlowSession.TrackedItem> filtered = new HashMap<>();
		for (Map.Entry<Integer, CoinFlowSession.TrackedItem> entry : expenses.entrySet())
		{
			int itemId = entry.getKey();
			CoinFlowSession.TrackedItem expense = entry.getValue();
			if (!CoinFlowSession.isSyntheticRowId(itemId)
				&& (isIgnored(expense.getName()) || isIgnored(plugin.getItemName(itemId))))
			{
				continue;
			}
			filtered.put(itemId, expense);
		}
		return filtered;
	}

	/**
	 * Checks if an item name is in the user's ignored items list.
	 */
	boolean isIgnored(String itemName)
	{
		return ConsumableRegistry.isIgnoredName(itemName, plugin.ignoredItemNames);
	}

	/**
	 * Rebuilds the ignored items list from config.
	 */
	void rebuildFilterSet()
	{
		String ignored = plugin.config.ignoredItems();
		if (ignored == null || ignored.trim().isEmpty())
		{
			plugin.ignoredItemNames = new HashSet<>();
		}
		else
		{
			plugin.ignoredItemNames = new HashSet<>();
			for (String name : ignored.split(","))
			{
				String trimmed = name.trim().toLowerCase();
				if (!trimmed.isEmpty())
				{
					plugin.ignoredItemNames.add(trimmed);
				}
			}
		}
	}

	/**
	 * Checks if an active goal was reached and triggers a single notification.
	 */
	void checkGoalNotification()
	{
		long goalAmount = CoinFlowSession.parseGoalAmount(plugin.config.goalAmount());
		if (goalAmount <= 0)
		{
			plugin.goalCompletedNotified = false;
			return;
		}

		long currentProfit = (plugin.session != null && plugin.config != null && !plugin.config.trackSpent())
			? plugin.session.getGrossProfit()
			: (plugin.session != null ? plugin.session.getTotalProfit() : 0L);

		if (plugin.session != null && currentProfit >= goalAmount)
		{
			if (!plugin.goalCompletedNotified)
			{
				plugin.goalCompletedNotified = true;
				if (plugin.config.notifyOnGoal())
				{
					String label = CoinFlowSession.cleanGoalName(plugin.config.goalName());
					String goalTitle = label.isEmpty() ? "Target GP" : label;
					plugin.notifier.notify("Coin Flow: Goal reached for " + goalTitle + " (" + QuantityFormatter.quantityToStackSize(goalAmount) + " gp)!");
				}
			}
		}
	}
}
