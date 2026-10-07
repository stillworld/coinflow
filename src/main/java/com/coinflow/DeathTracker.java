package com.coinflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

/**
 * Tracks items lost when the local player dies.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>{@link #onDeath} captures the combined inventory+equipment state. While
 *       a death is pending the plugin freezes diff tracking entirely, so the
 *       post-respawn container clear can never be misclassified as supplies,
 *       cash spends, or drops.</li>
 *   <li>{@link #shouldSettle} fires once the containers have gone quiet after
 *       respawn (or on timeout for safe deaths where nothing changes).
 *       {@link #computeLosses} diffs against the post-death state.</li>
 *   <li>{@link #recover} and {@link #recoverForTakeClicks} match later gains
 *       against the per-item ledger — reclaim interfaces diff against
 *       {@link #setRetrievalBaseline}, Wilderness ground pickups must carry a
 *       recorded Take click — producing a reversal value for the aggregated
 *       "Death" expense row.</li>
 * </ol>
 * The ledger survives logout and transient resets because reclaiming often
 * happens after a relog; it is cleared on startup, shutdown, and session reset.
 */
@Slf4j
@Singleton
public class DeathTracker
{
	/**
	 * Synthetic item id keying the aggregated "Death" expense row. SKULL is a
	 * real item id, but a Skull essentially never produces an expense row, so
	 * a collision is practically impossible and harmless.
	 */
	static final int DEATH_ROW_ID = ItemID.SKULL;
	static final String DEATH_ROW_NAME = "Death";

	/** Ticks after death before a settle is allowed (containers may lag respawn). */
	static final int MIN_SETTLE_TICKS = 3;
	/** Quiet ticks required after the last container change before settling. */
	static final int QUIET_TICKS = 2;
	/** Timeout: settle even when containers never changed (safe death). */
	static final int TIMEOUT_TICKS = 20;
	/** Take clicks older than this cannot confirm a Wilderness recovery. */
	static final int TAKE_CLICK_WINDOW_TICKS = 5;
	/**
	 * Ticks after a reclaim-interface settle during which ledger-matching
	 * gains still count as recovery without a Take click — reclaimed items
	 * can arrive after the interface closes (server lag, or delivery between
	 * a close and a re-open where the re-baseline would otherwise swallow
	 * them).
	 */
	static final int RECOVERY_GRACE_TICKS = 10;
	/**
	 * Ticks a chat-reported fee may sit unconsumed before being applied on
	 * its own — the "Death charges you" message can arrive after the reclaim
	 * interface has already closed and settled.
	 */
	static final int CHAT_FEE_DEFER_TICKS = 2;
	/**
	 * Sanity cap on the reclaim fee inferred from inventory coin movement —
	 * unrelated spends during the window can't fabricate an absurd fee. The
	 * <em>computed</em> fee is not capped by this value: a multi-hundred-mil
	 * office reclaim legitimately exceeds it.
	 */
	static final int MAX_RETRIEVAL_FEE = 5_000_000;

	/** Per-item value below which reclaims are free. */
	static final long FEE_FREE_THRESHOLD = 100_000L;
	/** Gravestone tiers: flat fee per unit by unit value. */
	static final long GRAVE_FEE_T1 = 1_000L;   // 100k - 1m
	static final long GRAVE_FEE_T2 = 10_000L;  // 1m - 10m
	static final long GRAVE_FEE_T3 = 100_000L; // >= 10m
	/** Combined gravestone fee cap per reclaim. */
	static final long GRAVE_FEE_CAP = 500_000L;

	/** A single item lost on death, pending possible recovery. */
	static final class DeathLot
	{
		int quantity;
		long priceEach;

		DeathLot(int quantity, long priceEach)
		{
			this.quantity = quantity;
			this.priceEach = priceEach;
		}
	}

	/** Result of matching a gain against the pending-death ledger. */
	static final class RecoveryResult
	{
		/** itemId -> quantity recovered (capped at ledger quantity). */
		final Map<Integer, Integer> recoveredQuantities = new HashMap<>();
		/** GP value to reverse off the Death expense row (death-time prices). */
		long value;
		/** GP fee observed/computed during a reclaim (added to the Death row). */
		long fee;
		/** itemId -> ledger priceEach for recovered items, for fee computation. */
		final Map<Integer, Long> recoveredPrices = new HashMap<>();

		boolean isEmpty()
		{
			return recoveredQuantities.isEmpty() && value <= 0 && fee <= 0;
		}
	}

	// ── Pending death state ────────────────────────────────────────────
	private Map<Integer, Integer> preDeathItems;
	private int deathTick;
	private int lastContainerChangeTick;

	// ── Recovery ledger ────────────────────────────────────────────────
	/** itemId (canonical) -> unrecovered lost quantity at death-time price. */
	private final Map<Integer, DeathLot> ledger = new HashMap<>();

	/**
	 * Combined inventory+equipment baseline captured when a reclaim interface
	 * (gravestone, Death's Office) opens. Settled on GameTick once no retrieval
	 * interface remains open — scene loads clear the suppressed set without a
	 * WidgetClosed event, so event-driven closing cannot be relied on.
	 */
	private Map<Integer, Integer> retrievalBaseline;
	/** Group id of the retrieval interface that produced the baseline. */
	private int retrievalGroupId = -1;
	/**
	 * Reclaim fee reported by the "Death charges you X coins." game message,
	 * pending consumption by the next {@link #settleRetrieval}. This is the
	 * only signal when the fee is paid from Death's coffer or the bank and
	 * the reclaimed item never appears in inv/worn (e.g. reclaim-to-bank on
	 * a full inventory).
	 */
	private long pendingChatFee;
	private int pendingChatFeeTick = -1;
	/** Tick of the last retrieval settle; gates the post-close recovery grace. */
	private int lastRetrievalSettleTick = -1;
	/**
	 * Tick of the last "You ... retrieved ... from your gravestone." message.
	 * A direct gravestone claim delivers items by script — no retrieval
	 * interface opens and no ground Take click fires — so the message itself
	 * opens the unconditional recovery window.
	 */
	private int graveRetrievalTick = -1;

	/** Canonical item ids whose Take clicks may confirm a Wilderness recovery. */
	private final List<TakeClickRecord> takeClicks = new ArrayList<>();

	private static final class TakeClickRecord
	{
		final int itemId;
		final int tick;

		TakeClickRecord(int itemId, int tick)
		{
			this.itemId = itemId;
			this.tick = tick;
		}
	}

	@Inject
	public DeathTracker() {}

	// ── Capture / freeze / settle ──────────────────────────────────────

	/**
	 * Records a local-player death with the combined pre-death inventory+equipment
	 * state (canonical ids). A second death while still settling keeps the
	 * original capture: nothing between the two deaths can be real player gain,
	 * so the merged window is the correct loss.
	 */
	void onDeath(Map<Integer, Integer> preDeath, int tick)
	{
		if (isPending())
		{
			log.debug("Death while pending; keeping earlier capture");
			return;
		}
		preDeathItems = new HashMap<>(preDeath);
		deathTick = tick;
		lastContainerChangeTick = -1;
		log.debug("Death pending: captured {} item types", preDeathItems.size());
	}

	boolean isPending()
	{
		return preDeathItems != null;
	}

	void onContainerChanged(int tick)
	{
		if (isPending())
		{
			lastContainerChangeTick = tick;
		}
	}

	/**
	 * Settle once the post-death containers have gone quiet, or on timeout
	 * (safe deaths where nothing changes). Settling early is what must be
	 * avoided: a staged clear arriving after settle would be swallowed by the
	 * frozen baseline and under-counted.
	 */
	boolean shouldSettle(int currentTick)
	{
		if (!isPending())
		{
			return false;
		}
		int since = currentTick - deathTick;
		if (since >= TIMEOUT_TICKS)
		{
			return true;
		}
		return since >= MIN_SETTLE_TICKS
			&& lastContainerChangeTick >= 0
			&& currentTick - lastContainerChangeTick >= QUIET_TICKS;
	}

	/**
	 * Finalizes the pending death against the post-death combined state.
	 * Charge-degradation transitions (charged -> degraded variant of the same
	 * item, including Wilderness "(broken)" forms) are cancelled from both
	 * sides so a keep-plus-degrade isn't counted as a loss.
	 *
	 * @return itemId (canonical) -> lost quantity; the caller filters/prices it
	 */
	Map<Integer, Integer> computeLosses(Map<Integer, Integer> postDeath, ItemManager itemManager)
	{
		Map<Integer, Integer> losses = new HashMap<>();
		if (preDeathItems == null)
		{
			return losses;
		}
		for (Map.Entry<Integer, Integer> entry : preDeathItems.entrySet())
		{
			int delta = entry.getValue() - postDeath.getOrDefault(entry.getKey(), 0);
			if (delta > 0)
			{
				losses.put(entry.getKey(), delta);
			}
		}

		Map<Integer, Integer> gains = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : postDeath.entrySet())
		{
			int delta = entry.getValue() - preDeathItems.getOrDefault(entry.getKey(), 0);
			if (delta > 0)
			{
				gains.put(entry.getKey(), delta);
			}
		}
		cancelDegradePairs(losses, gains, itemManager);
		return losses;
	}

	/**
	 * Cancels lost<->gained pairs that are charge/degradation transitions of
	 * the same item — e.g. "Amulet of glory(4)" -> "(3)", "Dharok's helm 100" ->
	 * 75, or "Torva platelegs" -> "Torva platelegs (broken)" on a wildy death.
	 */
	private void cancelDegradePairs(Map<Integer, Integer> losses, Map<Integer, Integer> gains,
		ItemManager itemManager)
	{
		for (Iterator<Map.Entry<Integer, Integer>> lostIt = losses.entrySet().iterator(); lostIt.hasNext();)
		{
			Map.Entry<Integer, Integer> lost = lostIt.next();
			String lostName = itemName(itemManager, lost.getKey());

			for (Iterator<Map.Entry<Integer, Integer>> gainedIt = gains.entrySet().iterator(); gainedIt.hasNext();)
			{
				Map.Entry<Integer, Integer> gained = gainedIt.next();
				String gainedName = itemName(itemManager, gained.getKey());

				if (!com.coinflow.reconciliation.ChargeDegradationHandler.isChargeDegradationPair(lostName, gainedName)
					&& !isBrokenPair(lostName, gainedName))
				{
					continue;
				}

				int match = Math.min(lost.getValue(), gained.getValue());
				if (gained.getValue() <= match)
				{
					gainedIt.remove();
				}
				else
				{
					gained.setValue(gained.getValue() - match);
				}
				if (lost.getValue() <= match)
				{
					lostIt.remove();
					break;
				}
				lost.setValue(lost.getValue() - match);
			}
		}
	}

	private static boolean isBrokenPair(String lostName, String gainedName)
	{
		return gainedName != null && lostName != null
			&& gainedName.equalsIgnoreCase(lostName + " (broken)");
	}

	private static String itemName(ItemManager itemManager, int itemId)
	{
		if (itemManager == null)
		{
			return "";
		}
		ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp != null && comp.getName() != null ? comp.getName() : "";
	}

	/**
	 * Discards the pending death (logout/hop before settle). Ledger kept.
	 */
	void discardPending()
	{
		preDeathItems = null;
		lastContainerChangeTick = -1;
	}

	/**
	 * Adds lost quantities to the recovery ledger at their death-time prices.
	 * Re-lost items re-price at the newer death's price; per-lot price history
	 * isn't worth tracking.
	 */
	void recordLost(Map<Integer, Integer> quantities, Map<Integer, Long> prices)
	{
		for (Map.Entry<Integer, Integer> entry : quantities.entrySet())
		{
			long price = prices.getOrDefault(entry.getKey(), 0L);
			DeathLot existing = ledger.get(entry.getKey());
			if (existing != null)
			{
				existing.quantity += entry.getValue();
				existing.priceEach = price;
			}
			else
			{
				ledger.put(entry.getKey(), new DeathLot(entry.getValue(), price));
			}
		}
	}

	Map<Integer, DeathLot> getLedger()
	{
		return ledger;
	}

	boolean hasLedger()
	{
		return !ledger.isEmpty();
	}

	int ledgerQuantity(int itemId)
	{
		DeathLot lot = ledger.get(itemId);
		return lot != null ? lot.quantity : 0;
	}

	// ── Recovery: reclaim interfaces ───────────────────────────────────

	/**
	 * Stores the combined inventory+equipment baseline when a reclaim
	 * interface opens. Ignored while the ledger is empty (nothing to recover).
	 */
	void setRetrievalBaseline(Map<Integer, Integer> combined, int groupId)
	{
		if (!hasLedger())
		{
			return;
		}
		retrievalBaseline = new HashMap<>(combined);
		retrievalGroupId = groupId;
	}

	boolean hasRetrievalBaseline()
	{
		return retrievalBaseline != null;
	}

	/**
	 * Diffs live containers against the retrieval baseline once no reclaim
	 * interface is open: gains are recoveries (matched into the ledger).
	 * The reclaim fee is the largest of three measures — coins that left
	 * inventory while the interface was open (cash-paid fees, capped as a
	 * sanity bound), the fee computed from the recovered items' ledger
	 * prices using the official reclaim schedules (which also sees fees paid
	 * from Death's coffer or the bank, invisible to the inventory diff), and
	 * any pending fee reported by the "Death charges you" chat message.
	 *
	 * @param current combined inventory+equipment map
	 * @param tick current tick, for the post-close recovery grace window
	 * @param ironman whether the account gets the 50% ironman fee discount
	 */
	RecoveryResult settleRetrieval(Map<Integer, Integer> current, int tick, boolean ironman)
	{
		RecoveryResult result = new RecoveryResult();
		if (retrievalBaseline == null)
		{
			return result;
		}
		Map<Integer, Integer> baseline = retrievalBaseline;
		retrievalBaseline = null;
		int groupId = retrievalGroupId;
		retrievalGroupId = -1;
		lastRetrievalSettleTick = tick;

		Map<Integer, Integer> gains = new HashMap<>();
		for (Map.Entry<Integer, Integer> entry : current.entrySet())
		{
			int delta = entry.getValue() - baseline.getOrDefault(entry.getKey(), 0);
			if (delta > 0)
			{
				gains.put(entry.getKey(), delta);
			}
		}
		matchIntoLedger(gains, result);

		// The chat-reported fee is folded in here rather than applied at parse
		// time so it can't double-count against the computed/observed fee for
		// the same reclaim. Accumulating pendingChatFee also lets it cover a
		// reclaim whose items never entered inv/worn (reclaim-to-bank).
		long chatFee = pendingChatFee;
		pendingChatFee = 0;
		pendingChatFeeTick = -1;

		long computedFee = computedReclaimFee(result, groupId, ironman);
		int coinsLost = baseline.getOrDefault(ItemID.COINS, 0) - current.getOrDefault(ItemID.COINS, 0);
		long observedFee = Math.min(Math.max(coinsLost, 0), MAX_RETRIEVAL_FEE);
		// The chat message is the exact charge, so it wins over the schedule
		// estimate — the game's reclaim valuation differs from our item
		// prices. Computed only fills in when no message was seen. Observed
		// coin losses still compete since they may be a real extra outflow.
		result.fee = Math.max(observedFee, chatFee > 0 ? chatFee : computedFee);
		return result;
	}

	/**
	 * Records a fee reported by the "Death charges you X coins." game
	 * message. Ignored with no reclaim context (empty ledger, no open
	 * retrieval baseline, no recent settle).
	 */
	void noteChatFee(long amount, int tick)
	{
		if (amount <= 0
			|| (!hasLedger() && !hasRetrievalBaseline() && !recentlySettled(tick)))
		{
			return;
		}
		pendingChatFee += amount;
		pendingChatFeeTick = tick;
	}

	/**
	 * Records a "retrieved ... from your gravestone." game message — the only
	 * signal for a direct gravestone claim, which delivers items by script
	 * without opening a retrieval interface. Ignored with no ledger: the
	 * window would match nothing anyway, and a stale marker could mislabel
	 * a later death's same-id gains as recovery.
	 */
	void noteGraveRetrieval(int tick)
	{
		if (hasLedger())
		{
			graveRetrievalTick = tick;
		}
	}

	/**
	 * Whether a reclaim settled or a gravestone claim was announced recently
	 * enough that ledger-matching gains should count as recovery even
	 * without a Take click.
	 */
	boolean inRecoveryGrace(int tick)
	{
		return hasLedger() && (recentlySettled(tick) || recentlyRetrievedFromGrave(tick));
	}

	private boolean recentlySettled(int tick)
	{
		return lastRetrievalSettleTick >= 0 && tick - lastRetrievalSettleTick <= RECOVERY_GRACE_TICKS;
	}

	private boolean recentlyRetrievedFromGrave(int tick)
	{
		return graveRetrievalTick >= 0 && tick - graveRetrievalTick <= RECOVERY_GRACE_TICKS;
	}

	/**
	 * Returns a chat-reported fee that outlived its settle window, once —
	 * a fee message that arrived after the reclaim interface already closed
	 * and settled would otherwise sit in {@link #pendingChatFee} forever.
	 * The caller skips this while a retrieval baseline is live so a pending
	 * fee always folds into the next settle instead of applying twice.
	 */
	long consumeDeferredChatFee(int tick)
	{
		if (pendingChatFee <= 0 || tick - pendingChatFeeTick < CHAT_FEE_DEFER_TICKS)
		{
			return 0;
		}
		long fee = pendingChatFee;
		pendingChatFee = 0;
		pendingChatFeeTick = -1;
		return fee;
	}

	/**
	 * Total reclaim fee for the recovered items under the schedule of the
	 * interface that was open: Death's Office charges 5% per unit over the
	 * free threshold, gravestones a flat per-unit tier with a combined cap.
	 * Prices are the death-time ledger prices — close enough to the game's
	 * current-GE-value basis.
	 */
	private long computedReclaimFee(RecoveryResult result, int groupId, boolean ironman)
	{
		long fee = 0;
		for (Map.Entry<Integer, Integer> entry : result.recoveredQuantities.entrySet())
		{
			long price = result.recoveredPrices.getOrDefault(entry.getKey(), 0L);
			fee += feePerUnit(price, groupId, ironman) * entry.getValue();
		}
		if (groupId != InterfaceID.DEATH_OFFICE)
		{
			fee = Math.min(fee, GRAVE_FEE_CAP);
		}
		return fee;
	}

	private static long feePerUnit(long priceEach, int groupId, boolean ironman)
	{
		if (priceEach < FEE_FREE_THRESHOLD)
		{
			return 0;
		}
		long fee;
		if (groupId == InterfaceID.DEATH_OFFICE)
		{
			fee = priceEach * 5 / 100;
		}
		else if (priceEach >= 10_000_000L)
		{
			fee = GRAVE_FEE_T3;
		}
		else if (priceEach >= 1_000_000L)
		{
			fee = GRAVE_FEE_T2;
		}
		else
		{
			fee = GRAVE_FEE_T1;
		}
		return ironman ? fee / 2 : fee;
	}

	// ── Recovery: ground pickups ───────────────────────────────────────

	/**
	 * Records a Take click that may later confirm a Wilderness ground pickup
	 * recovery. Only clicks with a non-empty ledger are recorded.
	 */
	void recordTakeClick(int itemId, int tick)
	{
		if (!hasLedger())
		{
			return;
		}
		takeClicks.add(new TakeClickRecord(itemId, tick));
	}

	/**
	 * Matches gained items against the ledger, but only for items carrying a
	 * live Take click — this is what separates picking up your own death drops
	 * from looting a coincidental new item (e.g. another monster's drop).
	 *
	 * @param gains map of canonical itemId -> gained quantity; matched amounts
	 *              are subtracted in place
	 */
	RecoveryResult recoverForTakeClicks(Map<Integer, Integer> gains, int currentTick)
	{
		RecoveryResult result = new RecoveryResult();
		if (gains == null || gains.isEmpty() || !hasLedger())
		{
			return result;
		}
		expireTakeClicks(currentTick);
		if (takeClicks.isEmpty())
		{
			return result;
		}

		for (Iterator<Map.Entry<Integer, Integer>> it = gains.entrySet().iterator(); it.hasNext();)
		{
			Map.Entry<Integer, Integer> entry = it.next();
			boolean clicked = false;
			for (TakeClickRecord click : takeClicks)
			{
				if (click.itemId == entry.getKey())
				{
					clicked = true;
					break;
				}
			}
			if (!clicked)
			{
				continue;
			}
			int matched = matchItem(entry.getKey(), entry.getValue(), result);
			int remaining = entry.getValue() - matched;
			if (remaining <= 0)
			{
				it.remove();
			}
			else
			{
				entry.setValue(remaining);
			}
		}
		return result;
	}

	/**
	 * Matches all gains against the ledger unconditionally — used by reclaim
	 * interfaces where anything returning is a recovery. Matched amounts are
	 * subtracted from {@code gains} in place.
	 */
	RecoveryResult recover(Map<Integer, Integer> gains)
	{
		RecoveryResult result = new RecoveryResult();
		if (gains == null || gains.isEmpty() || !hasLedger())
		{
			return result;
		}
		matchIntoLedger(gains, result);
		return result;
	}

	private void matchIntoLedger(Map<Integer, Integer> gains, RecoveryResult result)
	{
		for (Iterator<Map.Entry<Integer, Integer>> it = gains.entrySet().iterator(); it.hasNext();)
		{
			Map.Entry<Integer, Integer> entry = it.next();
			int matched = matchItem(entry.getKey(), entry.getValue(), result);
			int remaining = entry.getValue() - matched;
			if (remaining <= 0)
			{
				it.remove();
			}
			else
			{
				entry.setValue(remaining);
			}
		}
	}

	/** @return quantity matched into the ledger (also accumulated in result) */
	private int matchItem(int itemId, int gainedQty, RecoveryResult result)
	{
		DeathLot lot = ledger.get(itemId);
		if (lot == null || gainedQty <= 0)
		{
			return 0;
		}
		int matched = Math.min(gainedQty, lot.quantity);
		lot.quantity -= matched;
		if (lot.quantity <= 0)
		{
			ledger.remove(itemId);
		}
		result.recoveredQuantities.merge(itemId, matched, Integer::sum);
		result.recoveredPrices.putIfAbsent(itemId, lot.priceEach);
		result.value += matched * lot.priceEach;
		return matched;
	}

	private void expireTakeClicks(int currentTick)
	{
		takeClicks.removeIf(click -> currentTick - click.tick > TAKE_CLICK_WINDOW_TICKS);
	}

	int takeClickCount()
	{
		return takeClicks.size();
	}

	/**
	 * Full reset (startup, shutdown, session reset). The ledger is intentionally
	 * NOT cleared by {@link #discardPending} or transient tracking resets —
	 * reclaimed items must still reverse the Death row after a relog.
	 */
	void reset()
	{
		discardPending();
		ledger.clear();
		retrievalBaseline = null;
		retrievalGroupId = -1;
		lastRetrievalSettleTick = -1;
		graveRetrievalTick = -1;
		pendingChatFee = 0;
		pendingChatFeeTick = -1;
		takeClicks.clear();
	}
}
