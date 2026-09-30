package com.coinflow.reconciliation;

/**
 * Handler interface for a specific item reconciliation scenario
 * (e.g. gear unequip, high alchemy, potion sipping, food eating, cooking).
 */
public interface ReconciliationHandler
{
	/**
	 * Inspects and modifies gains/losses in context, appending any detected
	 * supply expenses or dropped deductions.
	 *
	 * @param context the mutable reconciliation context
	 */
	void reconcile(ReconciliationContext context);
}
