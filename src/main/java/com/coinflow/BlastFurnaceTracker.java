package com.coinflow;

import com.coinflow.reconciliation.BlastFurnaceHandler;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.gameval.ItemID;

/**
 * Blast Furnace bookkeeping. Two ledgers:
 *
 * <b>Coffer.</b> The coffer is a balance — deposits and withdrawals move
 * coins between the inventory and the furnace, so only the 12 gp/tick drain
 * is a real expense. Coin-only diffs are held aside and settled each tick
 * against the {@code BLAST_FURNACE_COFFER} varbit: an increase matched to a
 * held loss is a deposit (no expense), a decrease matched to a held gain is
 * a withdrawal (no profit), and any remaining decrease is drain — capped at
 * {@link #DRAIN_PER_TICK} per tick since the last observed change so a
 * withdrawal swallowed by a re-baseline can never book as thousands of gp of
 * "drain". A deposit racing a drain tick leaves a small held remainder
 * (the coffer rose by less than the coins lost) — attributed to drain up to
 * the same cap rather than expiring into a {@code Coins} spend. Drain
 * accrues and is charged to the synthetic
 * {@link #COFFER_ROW_NAME} row in batches of {@link #DRAIN_FLUSH_GP}, on
 * leaving the furnace, and on logout. Held coins that never match (e.g. the
 * 2,500 gp foreman fee) fall back to a {@code Coins} cash spend after
 * {@link #GRACE_TICKS} ticks.
 *
 * <b>Dispenser rescue.</b> Bar collections arriving inside a re-baseline
 * window are normally swallowed as baseline noise. Dispenser varbit
 * decreases are recorded so matching bar gains within
 * {@link #RESCUE_WINDOW_TICKS} ticks can be rescued with their recipe
 * expenses — bank-chest bar withdrawals produce no dispenser decrease and
 * are never rescued.
 */
@Slf4j
final class BlastFurnaceTracker
{
	/** Synthetic expense row id: a coin-pile icon distinct from GE Tax's COINS_10000. */
	static final int COFFER_ROW_ID = ItemID.COINS_1000;
	static final String COFFER_ROW_NAME = "Blast Furnace coffer";

	static final int DRAIN_PER_TICK = 12;
	static final int DRAIN_FLUSH_GP = 1_200;
	static final int GRACE_TICKS = 2;
	static final int RESCUE_WINDOW_TICKS = 3;

	private final CoinFlowPlugin plugin;

	/** True while the conveyor belt object is present in the loaded scene. */
	private boolean active;

	private int lastCofferValue = -1;
	private int lastCofferChangeTick = -1;
	private int pendingDrainGp = 0;

	/** Held coin-only diffs awaiting a coffer-varbit match. */
	private int heldCoinLoss = 0;
	private int heldCoinLossTick = -1;
	private int heldCoinGain = 0;
	private int heldCoinGainTick = -1;

	/** Last seen value per dispenser varbit (varbit id -> bar count). */
	private final Map<Integer, Integer> lastDispenserValues = new HashMap<>();

	/** Bar item id -> quantity the dispenser recently delivered. */
	private final Map<Integer, Integer> recentDispensed = new HashMap<>();
	private int recentDispensedTick = -1;

	BlastFurnaceTracker(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	boolean isActive()
	{
		return active;
	}

	/**
	 * Outcome of a per-tick coffer settle, applied by the caller.
	 */
	static final class Settlement
	{
		/** Expired held coin loss -> {@code Coins} cash spend at face value. */
		int cashSpendCoins;
		/** Expired held coin gain -> re-applied as a normal coin gain. */
		int coinGainQty;
		/** Accrued coffer drain to charge on {@link #COFFER_ROW_ID}. */
		int drainChargeGp;
	}

	// ── Presence detection ──────────────────────────────────────────────

	void onObjectSpawn(int objectId)
	{
		if (objectId == net.runelite.api.gameval.ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE && !active)
		{
			active = true;
			// The coffer varbit holds its value across scene loads; the first
			// read after arriving only re-baselines it.
			lastCofferValue = -1;
			lastCofferChangeTick = -1;
			lastDispenserValues.clear();
			log.debug("Blast Furnace entered");
		}
	}

	void onObjectDespawn(int objectId)
	{
		if (objectId == net.runelite.api.gameval.ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE)
		{
			active = false;
			log.debug("Blast Furnace left");
		}
	}

	void onGameStateChanged(GameState state)
	{
		if (state == GameState.LOADING)
		{
			active = false;
		}
	}

	/**
	 * One-shot scene lookup for a plugin enabled while already at the
	 * furnace (no spawn event fires for already-loaded objects).
	 */
	void checkSceneForConveyor()
	{
		if (plugin.client == null)
		{
			return;
		}
		net.runelite.api.WorldView worldView = plugin.client.getTopLevelWorldView();
		if (worldView == null || worldView.getScene() == null)
		{
			return;
		}
		net.runelite.api.Tile[][][] tiles = worldView.getScene().getTiles();
		if (tiles == null)
		{
			return;
		}
		int plane = worldView.getPlane();
		if (plane < 0 || plane >= tiles.length)
		{
			return;
		}
		for (net.runelite.api.Tile[] row : tiles[plane])
		{
			for (net.runelite.api.Tile tile : row)
			{
				if (tile == null)
				{
					continue;
				}
				for (net.runelite.api.GameObject obj : tile.getGameObjects())
				{
					if (obj != null && obj.getId() == net.runelite.api.gameval.ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE)
					{
						onObjectSpawn(obj.getId());
						return;
					}
				}
			}
		}
	}

	// ── Coin diff holding ───────────────────────────────────────────────

	/**
	 * Pulls a coin-only diff out of the raw maps while at the furnace so the
	 * coffer varbit can attribute it (deposit/withdrawal/drain) next tick.
	 * Diffs containing anything but coins stay untouched — purchases and
	 * mixed losses keep their normal accounting.
	 *
	 * @return true if coins were held (the diff was coin-only)
	 */
	boolean holdCoins(Map<Integer, Integer> rawGains, Map<Integer, Integer> rawLosses,
		int tick, boolean coinDropIntent)
	{
		if (!active || plugin.session == null)
		{
			return false;
		}
		if (rawLosses.size() == 1 && rawGains.isEmpty() && !coinDropIntent)
		{
			Map.Entry<Integer, Integer> entry = rawLosses.entrySet().iterator().next();
			int canonical = plugin.itemManager != null ? plugin.itemManager.canonicalize(entry.getKey()) : entry.getKey();
			if (canonical == ItemID.COINS)
			{
				heldCoinLoss += entry.getValue();
				heldCoinLossTick = tick;
				rawLosses.remove(entry.getKey());
				return true;
			}
		}
		if (rawGains.size() == 1 && rawLosses.isEmpty())
		{
			Map.Entry<Integer, Integer> entry = rawGains.entrySet().iterator().next();
			int canonical = plugin.itemManager != null ? plugin.itemManager.canonicalize(entry.getKey()) : entry.getKey();
			if (canonical == ItemID.COINS)
			{
				heldCoinGain += entry.getValue();
				heldCoinGainTick = tick;
				rawGains.remove(entry.getKey());
				return true;
			}
		}
		return false;
	}

	// ── Dispenser varbit / bar rescue ───────────────────────────────────

	/**
	 * Records dispenser bar-varbit decreases so a collection swallowed by a
	 * re-baseline can still be credited. Called for every varbit event while
	 * at the furnace; non-dispenser ids are ignored.
	 */
	void onDispenserVarbit(int varbitId, int value, int tick)
	{
		if (!active)
		{
			return;
		}
		Integer barId = BlastFurnaceHandler.DISPENSER_VARBIT_TO_BAR.get(varbitId);
		if (barId == null)
		{
			return;
		}
		int prev = lastDispenserValues.getOrDefault(varbitId, -1);
		lastDispenserValues.put(varbitId, value);
		if (prev >= 0 && value < prev)
		{
			recentDispensed.merge(barId, prev - value, Integer::sum);
			recentDispensedTick = tick;
		}
	}

	/**
	 * Rescues bar gains about to be swallowed by a re-baseline, crediting the
	 * bars and charging their recipe inputs. Only unnoted recipe-bar gains
	 * matching a dispenser decrease inside {@link #RESCUE_WINDOW_TICKS} are
	 * rescued — bank-chest bar withdrawals produce no decrease. Caller must
	 * only invoke this when the diff has no losses.
	 */
	void rescueBars(Map<Integer, Integer> rawGains, int tick)
	{
		if (!active || plugin.session == null || rawGains.isEmpty()
			|| recentDispensed.isEmpty() || tick - recentDispensedTick > RESCUE_WINDOW_TICKS)
		{
			return;
		}

		Map<Integer, Integer> rescued = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : rawGains.entrySet())
		{
			int gainedId = entry.getKey();
			int canonical = plugin.itemManager != null ? plugin.itemManager.canonicalize(gainedId) : gainedId;
			if (canonical != gainedId || !BlastFurnaceHandler.BAR_IDS.contains(canonical))
			{
				continue;
			}
			int avail = recentDispensed.getOrDefault(canonical, 0);
			int qty = Math.min(entry.getValue(), avail);
			if (qty <= 0)
			{
				continue;
			}
			rescued.merge(canonical, qty, Integer::sum);
			int remainingAvail = avail - qty;
			if (remainingAvail <= 0)
			{
				recentDispensed.remove(canonical);
			}
			else
			{
				recentDispensed.put(canonical, remainingAvail);
			}
		}
		if (rescued.isEmpty())
		{
			return;
		}

		for (Map.Entry<Integer, Integer> entry : rescued.entrySet())
		{
			if (rawGains.getOrDefault(entry.getKey(), 0) <= entry.getValue())
			{
				rawGains.remove(entry.getKey());
			}
			else
			{
				rawGains.put(entry.getKey(), rawGains.get(entry.getKey()) - entry.getValue());
			}
		}

		Map<Integer, CoinFlowSession.TrackedItem> expenses = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : rescued.entrySet())
		{
			recipeExpenses(entry.getKey(), entry.getValue())
				.forEach((id, item) -> expenses.merge(id, item, (a, b) -> a.merge(b)));
		}
		log.debug("Blast Furnace bars rescued from re-baseline: {}", rescued);
		plugin.processGainsLossesAndExpenses(rescued, Collections.emptyMap(), expenses);
	}

	// ── Coffer settlement ───────────────────────────────────────────────

	/**
	 * Per-tick coffer settle. Matches held coins against the coffer balance
	 * delta, accrues the capped drain, and expires unmatched held coins into
	 * fallback accounting. Pass {@code cofferValue < 0} when the varbit could
	 * not be read this tick.
	 */
	Settlement onTick(int cofferValue, int tick)
	{
		Settlement s = new Settlement();
		if (!active)
		{
			s.drainChargeGp = flushDrain();
			return s;
		}
		if (cofferValue < 0)
		{
			return s;
		}
		if (lastCofferValue < 0)
		{
			lastCofferValue = cofferValue;
			lastCofferChangeTick = tick;
			return s;
		}

		// Expire held coins that never met a coffer change BEFORE matching —
		// a stale entry (e.g. ticks skipped on an unsupported world) must not
		// attach itself to a fresh coffer delta.
		if (heldCoinLoss > 0 && heldCoinLossTick >= 0 && tick - heldCoinLossTick > GRACE_TICKS)
		{
			s.cashSpendCoins = heldCoinLoss;
			heldCoinLoss = 0;
			heldCoinLossTick = -1;
		}
		if (heldCoinGain > 0 && heldCoinGainTick >= 0 && tick - heldCoinGainTick > GRACE_TICKS)
		{
			s.coinGainQty = heldCoinGain;
			heldCoinGain = 0;
			heldCoinGainTick = -1;
		}

		int delta = cofferValue - lastCofferValue;
		if (delta != 0)
		{
			int elapsed = lastCofferChangeTick >= 0 ? Math.max(1, tick - lastCofferChangeTick) : 1;
			lastCofferValue = cofferValue;
			lastCofferChangeTick = tick;

			if (delta > 0)
			{
				// Deposit: the held coin loss leaves inventory for the coffer —
				// a balance transfer, not a spend. An increase beyond the held
				// amount (held diff swallowed) is likewise a transfer.
				int leftover = heldCoinLoss - delta;
				if (leftover > 0)
				{
					// Deposit raced a drain tick: the coffer rose by less than
					// the coins lost because some drained the same tick.
					// Attribute the remainder to drain (capped) rather than
					// letting it expire into a Coins spend.
					int cap = DRAIN_PER_TICK * elapsed + DRAIN_PER_TICK;
					int drain = Math.min(leftover, cap);
					pendingDrainGp += drain;
					leftover -= drain;
				}
				heldCoinLoss = Math.max(0, leftover);
			}
			else
			{
				int decrease = -delta;
				int withdrawn = Math.min(decrease, heldCoinGain);
				heldCoinGain -= withdrawn;
				int remaining = decrease - withdrawn;
				if (remaining > 0)
				{
					// Drain is capped so a withdrawal whose coin gain was
					// swallowed by a re-baseline can never book as spend.
					int cap = DRAIN_PER_TICK * elapsed + DRAIN_PER_TICK;
					int drain = Math.min(remaining, cap);
					pendingDrainGp += drain;
					if (remaining - drain > 0)
					{
						log.debug("Blast Furnace coffer: {} gp decrease beyond drain cap treated as withdrawal",
							remaining - drain);
					}
				}
			}
		}

		if (pendingDrainGp >= DRAIN_FLUSH_GP)
		{
			s.drainChargeGp = flushDrain();
		}
		return s;
	}

	/**
	 * Charges the accrued drain to the coffer row now (logout boundary).
	 */
	void onLeftLoggedIn()
	{
		int gp = flushDrain();
		if (gp > 0)
		{
			applyDrain(gp);
		}
		lastCofferValue = -1;
		lastCofferChangeTick = -1;
		heldCoinLoss = 0;
		heldCoinLossTick = -1;
		heldCoinGain = 0;
		heldCoinGainTick = -1;
	}

	private int flushDrain()
	{
		int gp = pendingDrainGp;
		pendingDrainGp = 0;
		return gp;
	}

	private void applyDrain(int gp)
	{
		if (plugin.session == null)
		{
			return;
		}
		Map<Integer, CoinFlowSession.TrackedItem> expenses = new HashMap<>();
		expenses.put(COFFER_ROW_ID,
			new CoinFlowSession.TrackedItem(COFFER_ROW_ID, COFFER_ROW_NAME, gp, 1L));
		log.debug("Blast Furnace coffer drain charged: {} gp", gp);
		plugin.processGainsLossesAndExpenses(Collections.emptyMap(), Collections.emptyMap(), expenses);
	}

	/** Shared recipe-expense builder for collections and rescues. */
	private Map<Integer, CoinFlowSession.TrackedItem> recipeExpenses(int barId, int barQty)
	{
		Map<Integer, CoinFlowSession.TrackedItem> expenses = new HashMap<>();
		Map<Integer, Integer> recipe = BlastFurnaceHandler.RECIPES.get(barId);
		String barName = plugin.getItemName(barId);
		if (recipe == null
			|| ConsumableRegistry.isIgnoredName(barName, plugin.ignoredItemNames))
		{
			return expenses;
		}
		for (Map.Entry<Integer, Integer> input : recipe.entrySet())
		{
			int inputId = input.getKey();
			String inputName = plugin.getItemName(inputId);
			if (ConsumableRegistry.isIgnoredName(inputName, plugin.ignoredItemNames))
			{
				continue;
			}
			long price = ItemPricing.priceOf(inputId, plugin.itemManager);
			expenses.merge(inputId,
				new CoinFlowSession.TrackedItem(inputId, inputName, (long) input.getValue() * barQty, price),
				(a, b) -> a.merge(b));
		}
		return expenses;
	}

	// ── Resets ──────────────────────────────────────────────────────────

	/**
	 * Plugin startUp/shutDown: full reset including {@link #active} — the
	 * player may have walked away while the plugin was off, and the startup
	 * scene lookup re-detects the conveyor if still there.
	 */
	void reset()
	{
		active = false;
		resetSession();
	}

	/**
	 * Session/account reset: discards accrued drain (it belonged to the old
	 * session) and all bookkeeping. {@link #active} intentionally survives —
	 * no spawn event will re-fire for the already-loaded conveyor, and the
	 * next onTick re-baselines the coffer anyway.
	 */
	void resetSession()
	{
		lastCofferValue = -1;
		lastCofferChangeTick = -1;
		pendingDrainGp = 0;
		clearTransient();
	}

	/**
	 * Transient reset (logout, shutdown, resetSession): held coins and the
	 * rescue ledger never survive a boundary.
	 */
	void clearTransient()
	{
		heldCoinLoss = 0;
		heldCoinLossTick = -1;
		heldCoinGain = 0;
		heldCoinGainTick = -1;
		recentDispensed.clear();
		recentDispensedTick = -1;
		lastDispenserValues.clear();
	}
}
