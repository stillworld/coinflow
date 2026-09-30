package com.coinflow.reconciliation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Executes the ordered pipeline of item reconciliation handlers.
 * Resolves gear swaps, alchemy, potions, portions, cooking, supplies, and drops
 * into final gains, supply expenses, and dropped deductions.
 */
@Singleton
public class InventoryReconciliationEngine
{
	private final List<ReconciliationHandler> handlers;

	@Inject
	public InventoryReconciliationEngine()
	{
		this.handlers = Collections.unmodifiableList(Arrays.asList(
			new GearSwapHandler(),
			new DroppedItemPickupHandler(),
			new ItemNotingHandler(),
			new HighAlchemyHandler(),
			new PotionDoseHandler(),
			new ChargeDegradationHandler(),
			new FoodPortionHandler(),
			new ProcessingHandler(),
			new ConsumablesAndDropsHandler()
		));
	}

	public InventoryReconciliationEngine(List<ReconciliationHandler> customHandlers)
	{
		this.handlers = Collections.unmodifiableList(customHandlers);
	}

	/**
	 * Runs the reconciliation pipeline on the given context.
	 *
	 * @param context the mutable reconciliation context
	 */
	public void reconcile(ReconciliationContext context)
	{
		for (ReconciliationHandler handler : handlers)
		{
			handler.reconcile(context);
		}
	}
}
