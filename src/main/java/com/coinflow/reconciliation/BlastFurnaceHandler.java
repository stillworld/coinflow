package com.coinflow.reconciliation;

import com.coinflow.CoinFlowSession;
import com.coinflow.ItemPricing;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemManager;

/**
 * Blast Furnace reconciliation.
 *
 * Ore and coal loaded onto the conveyor belt leave the inventory with no
 * exchange, so without this handler they book as owned drops (free inputs)
 * while bars collected from the dispenser book as full profit. Instead:
 *
 * - Unnoted recipe ore/coal losses with no Drop intent are stripped (the belt
 *   load), and matching session gains are retired so session-mined ore cannot
 *   be deducted twice. The bar collected later carries the full input cost.
 * - Unnoted recipe bar gains are charged their per-bar recipe (half the usual
 *   coal) as supply expenses, repriced through GE cost basis downstream.
 *
 * A diff containing both a recipe-input loss and a recipe-bar gain is a
 * Superheat smelt (or a same-tick load + collect), not belt bookkeeping — the
 * handler steps aside and lets {@link ProcessingHandler}'s smelting rules
 * settle it. Ignored bars charge no inputs so net can never go negative on a
 * filtered row; ignored inputs are skipped individually.
 *
 * Perfect gold ore/bars are excluded (untradeable quest items).
 */
@Slf4j
public class BlastFurnaceHandler implements ReconciliationHandler
{
	/**
	 * Bar item id -> (input item id -> quantity per bar). The Blast Furnace
	 * consumes half the coal of a regular furnace.
	 */
	public static final Map<Integer, Map<Integer, Integer>> RECIPES;

	/** Dispenser varbit id -> bar item id, for the collection rescue ledger. */
	public static final Map<Integer, Integer> DISPENSER_VARBIT_TO_BAR;

	public static final Set<Integer> INPUT_IDS;
	public static final Set<Integer> BAR_IDS;

	static
	{
		Map<Integer, Map<Integer, Integer>> recipes = new HashMap<>();
		recipes.put(ItemID.BRONZE_BAR, Map.of(ItemID.COPPER_ORE, 1, ItemID.TIN_ORE, 1));
		recipes.put(ItemID.IRON_BAR, Map.of(ItemID.IRON_ORE, 1));
		recipes.put(ItemID.SILVER_BAR, Map.of(ItemID.SILVER_ORE, 1));
		recipes.put(ItemID.STEEL_BAR, Map.of(ItemID.IRON_ORE, 1, ItemID.COAL, 1));
		recipes.put(ItemID.GOLD_BAR, Map.of(ItemID.GOLD_ORE, 1));
		recipes.put(ItemID.MITHRIL_BAR, Map.of(ItemID.MITHRIL_ORE, 1, ItemID.COAL, 2));
		recipes.put(ItemID.ADAMANTITE_BAR, Map.of(ItemID.ADAMANTITE_ORE, 1, ItemID.COAL, 3));
		recipes.put(ItemID.RUNITE_BAR, Map.of(ItemID.RUNITE_ORE, 1, ItemID.COAL, 4));
		recipes.put(ItemID.LEAD_BAR, Map.of(ItemID.LEAD_ORE, 2));
		recipes.put(ItemID.CUPRONICKEL_BAR, Map.of(ItemID.COPPER_ORE, 2, ItemID.NICKEL_ORE, 1));
		RECIPES = java.util.Collections.unmodifiableMap(recipes);
		BAR_IDS = RECIPES.keySet();

		Set<Integer> inputs = new java.util.HashSet<>();
		for (Map<Integer, Integer> recipe : RECIPES.values())
		{
			inputs.addAll(recipe.keySet());
		}
		INPUT_IDS = java.util.Collections.unmodifiableSet(inputs);

		Map<Integer, Integer> dispensers = new HashMap<>();
		dispensers.put(VarbitID.BLAST_FURNACE_BRONZE_BARS, ItemID.BRONZE_BAR);
		dispensers.put(VarbitID.BLAST_FURNACE_IRON_BARS, ItemID.IRON_BAR);
		dispensers.put(VarbitID.BLAST_FURNACE_SILVER_BARS, ItemID.SILVER_BAR);
		dispensers.put(VarbitID.BLAST_FURNACE_STEEL_BARS, ItemID.STEEL_BAR);
		dispensers.put(VarbitID.BLAST_FURNACE_GOLD_BARS, ItemID.GOLD_BAR);
		dispensers.put(VarbitID.BLAST_FURNACE_MITHRIL_BARS, ItemID.MITHRIL_BAR);
		dispensers.put(VarbitID.BLAST_FURNACE_ADAMANTITE_BARS, ItemID.ADAMANTITE_BAR);
		dispensers.put(VarbitID.BLAST_FURNACE_RUNITE_BARS, ItemID.RUNITE_BAR);
		dispensers.put(VarbitID.BLAST_FURNACE_LEAD_BARS, ItemID.LEAD_BAR);
		dispensers.put(VarbitID.BLAST_FURNACE_CUPRONICKEL_BARS, ItemID.CUPRONICKEL_BAR);
		DISPENSER_VARBIT_TO_BAR = java.util.Collections.unmodifiableMap(dispensers);
	}

	@Override
	public void reconcile(ReconciliationContext context)
	{
		if (!context.isBlastFurnaceActive())
		{
			return;
		}

		Map<Integer, Integer> rawGains = context.getRawGains();
		Map<Integer, Integer> rawLosses = context.getRawLosses();
		ItemManager itemManager = context.getItemManager();

		if (rawGains.isEmpty() && rawLosses.isEmpty())
		{
			return;
		}

		// Collect unnoted recipe-bar gains and unnoted recipe-input losses.
		// Noted forms are bank business, not belt bookkeeping.
		Map<Integer, Integer> barGains = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : rawGains.entrySet())
		{
			int gainedId = entry.getKey();
			int canonical = itemManager != null ? itemManager.canonicalize(gainedId) : gainedId;
			if (canonical == gainedId && BAR_IDS.contains(canonical) && entry.getValue() > 0)
			{
				barGains.merge(canonical, entry.getValue(), Integer::sum);
			}
		}

		Map<Integer, Integer> inputLosses = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : rawLosses.entrySet())
		{
			int lostId = entry.getKey();
			int canonical = itemManager != null ? itemManager.canonicalize(lostId) : lostId;
			if (canonical == lostId && INPUT_IDS.contains(canonical) && entry.getValue() > 0)
			{
				inputLosses.merge(canonical, entry.getValue(), Integer::sum);
			}
		}

		// Inputs lost AND bars gained in the same diff is a Superheat smelt
		// (full-coal recipe) or a same-tick belt load + collection. Either way
		// ProcessingHandler's smelting rules own this diff — stepping aside
		// avoids double-charging ore that never reached the belt.
		if (!barGains.isEmpty() && !inputLosses.isEmpty())
		{
			return;
		}

		// Belt-load losses: strip them and retire matching session gains so
		// session-mined ore loaded this session cannot be deducted again.
		if (!inputLosses.isEmpty())
		{
			for (Map.Entry<Integer, Integer> entry : inputLosses.entrySet())
			{
				int inputId = entry.getKey();
				int qty = entry.getValue();
				if (context.isDropIntent(inputId))
				{
					continue;
				}
				rawLosses.remove(inputId);
				context.retireSessionGain(inputId, qty);
				log.debug("Blast Furnace belt load: {} x{} (free input, cost deferred to bars)",
					context.getItemName(inputId), qty);
			}
			return;
		}

		// Bar collection: charge each bar's recipe inputs as supply expenses.
		for (Map.Entry<Integer, Integer> entry : barGains.entrySet())
		{
			int barId = entry.getKey();
			int barQty = entry.getValue();
			String barName = context.getItemName(barId);
			if (context.isIgnored(barName))
			{
				continue;
			}

			Map<Integer, Integer> recipe = RECIPES.get(barId);
			if (recipe == null)
			{
				continue;
			}

			for (Map.Entry<Integer, Integer> input : recipe.entrySet())
			{
				int inputId = input.getKey();
				int inputQty = input.getValue() * barQty;
				String inputName = context.getItemName(inputId);
				if (context.isIgnored(inputName))
				{
					continue;
				}

				long price = ItemPricing.priceOf(inputId, itemManager);
				context.addSupplyExpense(inputId,
					new CoinFlowSession.TrackedItem(inputId, inputName, inputQty, price));
			}
			log.debug("Blast Furnace bars collected: {} x{} -> recipe inputs expensed", barName, barQty);
		}
	}
}
