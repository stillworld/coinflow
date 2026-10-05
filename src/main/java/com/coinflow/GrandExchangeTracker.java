package com.coinflow;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;

/**
 * Tracks Grand Exchange offers and settles completed fills against the session's
 * carried values, independent of inventory diffing.
 *
 * GE activity never produces usable inventory diffs: items leave while the GE
 * interface suppresses tracking, and proceeds/items arrive via "Collect" which is
 * re-baselined away. This tracker therefore derives accounting purely from
 * {@link GrandExchangeOffer} state deltas (quantitySold and spent), which reflect
 * fills the moment they happen — including partial fills, fills while away from
 * the exchange, and collect-to-bank.
 *
 * Carried-value accounting model for a sold unit:
 *  - bought via GE this session: actual cost basis (FIFO lots)
 *  - gained this session (loot): the session's tracked gain price
 *  - anything else (banked/pre-session): the actual gross fill price, so the
 *    only residual is the GE tax
 *
 * {@link GrandExchangeOffer#getSpent()} is gross (pre-tax) for sells; net
 * proceeds are derived via {@link GrandExchangeTax}.
 *
 * Buy fills are not session entries; they are asset conversions. The coins spent
 * are stored as cost basis so a later sale realizes the true margin.
 *
 * The FIFO cost-basis pool and sell-settlement logic are shared with shop
 * trades ({@link ShopTracker}): shop purchases add basis lots here, and shop
 * sales decompose via {@link #settleShopSell}, so cross-venue flows
 * (buy shop → sell GE, buy GE → sell shop) realize the correct margin.
 */
@Slf4j
public final class GrandExchangeTracker
{
	/**
	 * An incremental fill observed on one GE slot between two offer snapshots.
	 */
	static final class GeDelta
	{
		final int itemId;
		final int quantityDelta;
		final long priceEach;
		final long coinsDelta;
		final boolean buy;

		GeDelta(int itemId, int quantityDelta, long priceEach, long coinsDelta, boolean buy)
		{
			this.itemId = itemId;
			this.quantityDelta = quantityDelta;
			this.priceEach = priceEach;
			this.coinsDelta = coinsDelta;
			this.buy = buy;
		}
	}

	/**
	 * Session entries produced by settling a sell delta, plus the net profit
	 * impact for display purposes.
	 */
	static final class GeLedger
	{
		final Map<Integer, CoinFlowSession.TrackedItem> gains = new HashMap<>();
		final Map<Integer, CoinFlowSession.TrackedItem> deductions = new HashMap<>();
		final Map<Integer, CoinFlowSession.TrackedItem> expenses = new HashMap<>();
		long netDelta;

		boolean isEmpty()
		{
			return gains.isEmpty() && deductions.isEmpty() && expenses.isEmpty();
		}
	}

	private static final class ObservedOffer
	{
		final int itemId;
		final int quantitySold;
		final long spent;

		ObservedOffer(int itemId, int quantitySold, long spent)
		{
			this.itemId = itemId;
			this.quantitySold = quantitySold;
			this.spent = spent;
		}
	}

	private static final class BasisLot
	{
		int quantity;
		long remainingCost;

		BasisLot(int quantity, long cost)
		{
			this.quantity = quantity;
			this.remainingCost = cost;
		}
	}

	private final Map<Integer, ObservedOffer> observedOffers = new HashMap<>();
	private final Map<Integer, Deque<BasisLot>> costBasis = new HashMap<>();

	/**
	 * Seeds slot observations from the client's current offers so the next fill
	 * deltas correctly instead of being treated as a baseline (plugin enabled or
	 * session reset while offers are already in progress).
	 */
	void seed(GrandExchangeOffer[] offers)
	{
		if (offers == null)
		{
			return;
		}
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				observedOffers.remove(slot);
				continue;
			}
			observedOffers.put(slot, new ObservedOffer(offer.getItemId(), offer.getQuantitySold(), offer.getSpent()));
		}
	}

	/**
	 * Processes an offer update for a slot and returns any newly filled deltas.
	 * The first observation of an offer is a baseline only — fills that completed
	 * before this plugin session observed the slot are not counted. A quantity
	 * decrease or item change also re-baselines (slot reuse / data reset).
	 *
	 * The client emits EMPTY for every slot while logging in or hopping; those are
	 * ignored so fills completed while logged out still delta against the
	 * pre-logout observation. An EMPTY while logged in is a genuine slot clear
	 * (offer collected) and drops the observation.
	 */
	List<GeDelta> onOfferChanged(int slot, GrandExchangeOffer offer, boolean loggedIn)
	{
		if (offer == null || offer.getState() == null)
		{
			return Collections.emptyList();
		}
		if (offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			if (loggedIn)
			{
				observedOffers.remove(slot);
			}
			return Collections.emptyList();
		}

		ObservedOffer current = new ObservedOffer(offer.getItemId(), offer.getQuantitySold(), offer.getSpent());
		ObservedOffer previous = observedOffers.get(slot);

		if (previous == null
			|| previous.itemId != current.itemId
			|| current.quantitySold < previous.quantitySold)
		{
			observedOffers.put(slot, current);
			return Collections.emptyList();
		}

		observedOffers.put(slot, current);

		int quantityDelta = current.quantitySold - previous.quantitySold;
		if (quantityDelta <= 0)
		{
			return Collections.emptyList();
		}

		GrandExchangeOfferState state = offer.getState();
		boolean buy = state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;

		GeDelta delta = new GeDelta(
			current.itemId,
			quantityDelta,
			offer.getPrice(),
			current.spent - previous.spent,
			buy);

		log.debug("GE {} fill on slot {}: item {} x{} for {} gp",
			buy ? "buy" : "sell", slot, current.itemId, quantityDelta, delta.coinsDelta);

		return Collections.singletonList(delta);
	}

	/**
	 * Records cost basis for items bought through the GE this session.
	 */
	void addBasis(int itemId, int quantity, long cost)
	{
		if (itemId <= 0 || quantity <= 0)
		{
			return;
		}
		costBasis.computeIfAbsent(itemId, k -> new ArrayDeque<>()).add(new BasisLot(quantity, cost));
	}

	/**
	 * Total basis quantity held for an item across all lots.
	 */
	int basisQuantity(int itemId)
	{
		Deque<BasisLot> lots = costBasis.get(itemId);
		if (lots == null)
		{
			return 0;
		}
		int total = 0;
		for (BasisLot lot : lots)
		{
			total += lot.quantity;
		}
		return total;
	}

	/**
	 * Decomposes a sell fill into session ledger entries using carried values.
	 * Consumption order: GE cost basis first, then session tracked gains, then
	 * untracked stock carried at its gross fill price (residual = GE tax only).
	 * Net proceeds are allocated pro-rata across portions; division remainders
	 * fall to the untracked portion so portions always sum to netProceeds.
	 *
	 * @param grossProceeds pre-tax coins for this fill ({@code getSpent()} delta)
	 */
	GeLedger settleSell(int itemId, int quantity, long grossProceeds, CoinFlowSession session)
	{
		return settleSell(itemId, quantity, grossProceeds, session, false);
	}

	/**
	 * Same as {@link #settleSell} with control over the untracked portion's
	 * carried value. When {@code untrackedAsIncome} is unset, untracked stock
	 * is carried at its gross proceeds share so the asset conversion nets to
	 * zero (residual = GE tax only). When set, untracked stock is carried at
	 * zero so the net proceeds share surfaces as income instead.
	 */
	GeLedger settleSell(int itemId, int quantity, long grossProceeds, CoinFlowSession session,
		boolean untrackedAsIncome)
	{
		return settle(itemId, quantity, grossProceeds,
			GrandExchangeTax.netProceeds(itemId, quantity, grossProceeds), session, untrackedAsIncome);
	}

	/**
	 * Settles a shop sale exactly like {@link #settleSell} but with no tax:
	 * net proceeds equal the coins received. When {@code untrackedAsIncome}
	 * is unset the untracked portion's residual is always zero, so selling
	 * pre-session (banked) stock to a shop records nothing — an asset
	 * conversion, matching the GE convention. When set, its share of the
	 * proceeds surfaces as income.
	 */
	GeLedger settleShopSell(int itemId, int quantity, long proceeds, CoinFlowSession session,
		boolean untrackedAsIncome)
	{
		return settle(itemId, quantity, proceeds, proceeds, session, untrackedAsIncome);
	}

	private GeLedger settle(int itemId, int quantity, long grossProceeds, long netProceeds,
		CoinFlowSession session, boolean untrackedAsIncome)
	{
		GeLedger ledger = new GeLedger();
		if (quantity <= 0)
		{
			return ledger;
		}

		long[] basis = consumeBasis(itemId, quantity);
		int basisQty = (int) basis[0];
		long basisCost = basis[1];

		CoinFlowSession.TrackedItem tracked = session != null ? session.getTrackedItems().get(itemId) : null;
		int trackedQty = tracked != null
			? (int) Math.min(quantity - basisQty, tracked.getRemainingQuantity())
			: 0;

		int untrackedQty = quantity - basisQty - trackedQty;

		long pBasis = netProceeds * basisQty / quantity;
		long pTracked = netProceeds * trackedQty / quantity;
		long pUntracked = netProceeds - pBasis - pTracked;
		if (untrackedQty == 0)
		{
			if (trackedQty > 0)
			{
				pTracked += pUntracked;
			}
			else
			{
				pBasis += pUntracked;
			}
			pUntracked = 0;
		}

		if (basisQty > 0)
		{
			long residual = pBasis - basisCost;
			ledger.netDelta += residual;
			if (residual > 0)
			{
				addCoinGain(ledger, residual);
			}
			else if (residual < 0)
			{
				addCoinExpense(ledger, -residual);
			}
		}

		if (trackedQty > 0)
		{
			addCoinGain(ledger, pTracked);
			ledger.deductions.put(itemId,
				new CoinFlowSession.TrackedItem(itemId, tracked.getName(), trackedQty, tracked.getPriceEach()));
			long carried = tracked.getRemainingQuantity() > 0
				? tracked.getRemainingValue() * trackedQty / tracked.getRemainingQuantity()
				: (long) trackedQty * tracked.getPriceEach();
			ledger.netDelta += pTracked - carried;
		}

		if (untrackedQty > 0)
		{
			long carried = untrackedAsIncome
				? 0
				: grossProceeds - (grossProceeds * basisQty / quantity) - (grossProceeds * trackedQty / quantity);
			long residual = pUntracked - carried;
			ledger.netDelta += residual;
			if (residual < 0)
			{
				ledger.expenses.merge(GrandExchangeTax.TAX_ITEM_ID,
					new CoinFlowSession.TrackedItem(GrandExchangeTax.TAX_ITEM_ID, GrandExchangeTax.TAX_ITEM_NAME, clampQty(-residual), 1L),
					(a, b) -> a.merge(b));
			}
			else if (residual > 0)
			{
				addCoinGain(ledger, residual);
			}
		}

		return ledger;
	}

	/**
	 * Reprices consumed supplies against held cost basis. Quantity covered by
	 * FIFO basis is charged at its actual purchase cost; any remainder keeps the
	 * expense's market price. Draining basis here keeps lots from being orphaned
	 * (and later mis-attributed to a sale) once the bought items are used up.
	 *
	 * <p>Because a {@link CoinFlowSession.TrackedItem} carries one price, a mix
	 * of basis-covered and market-priced units collapses to a rounded average.
	 *
	 * @param fractionalIds item ids whose expense quantity is in sub-units
	 *        (doses, portions) and therefore must not drain whole-item basis
	 */
	Map<Integer, CoinFlowSession.TrackedItem> repriceFromBasis(
		Map<Integer, CoinFlowSession.TrackedItem> expenses, Set<Integer> fractionalIds)
	{
		if (expenses == null || expenses.isEmpty() || costBasis.isEmpty())
		{
			return expenses;
		}

		Map<Integer, CoinFlowSession.TrackedItem> result = new HashMap<>(expenses);
		for (Map.Entry<Integer, CoinFlowSession.TrackedItem> entry : expenses.entrySet())
		{
			CoinFlowSession.TrackedItem item = entry.getValue();
			int qty = (int) Math.min(Integer.MAX_VALUE, item.getQuantity());
			if (qty <= 0 || !costBasis.containsKey(item.getItemId())
				|| (fractionalIds != null && fractionalIds.contains(entry.getKey())))
			{
				continue;
			}

			long[] basis = consumeBasis(item.getItemId(), qty);
			int basisQty = (int) basis[0];
			if (basisQty == 0)
			{
				continue;
			}

			long total = basis[1] + (long) (qty - basisQty) * item.getPriceEach();
			long priceEach = (total + qty / 2) / qty;
			result.put(entry.getKey(),
				new CoinFlowSession.TrackedItem(item.getItemId(), item.getName(), qty, priceEach));

			log.debug("Repriced supply from basis: {} x{} ({} from basis @ {} gp total) -> {} gp each",
				item.getName(), qty, basisQty, basis[1], priceEach);
		}
		return result;
	}

	void reset()
	{
		observedOffers.clear();
		costBasis.clear();
	}

	/**
	 * A group of same-base potion bottles observed in an inventory diff.
	 * {@code doses} is the dose level of the bottle form (1-4); {@code quantity}
	 * is the number of bottles.
	 */
	public static final class PotionBottle
	{
		public final int itemId;
		public final int doses;
		public final int quantity;

		public PotionBottle(int itemId, int doses, int quantity)
		{
			this.itemId = itemId;
			this.doses = doses;
			this.quantity = quantity;
		}
	}

	/**
	 * Follows purchase cost basis as potions change dose level. Consumes basis
	 * for every lost bottle, redistributes the share still held in liquid to the
	 * gained bottle forms, and returns the cost attributable to the consumed
	 * doses.
	 *
	 * <p>This prevents a fully drunk bottle's basis from lingering and later
	 * being mis-attributed to the sale of an identical pre-session potion.
	 *
	 * @param lost lost bottle groups for one potion base
	 * @param gained gained bottle groups for the same potion base
	 * @param dosesConsumed net doses that left the player's possession
	 * @return {cost of basis-covered consumed doses, number of consumed doses covered by basis}
	 */
	public long[] reconcilePotionBasis(List<PotionBottle> lost, List<PotionBottle> gained, int dosesConsumed)
	{
		if (costBasis.isEmpty())
		{
			return new long[]{0, 0};
		}

		long totalBasisCost = 0;
		int totalLostDoses = 0;
		int basisDosesHeld = 0;
		for (PotionBottle p : lost)
		{
			totalLostDoses += p.doses * p.quantity;
			long[] basis = consumeBasis(p.itemId, p.quantity);
			totalBasisCost += basis[1];
			basisDosesHeld += p.doses * (int) basis[0];
		}
		if (totalBasisCost <= 0 || totalLostDoses <= 0)
		{
			return new long[]{0, 0};
		}

		int totalGainedDoses = 0;
		for (PotionBottle p : gained)
		{
			totalGainedDoses += p.doses * p.quantity;
		}

		// Cost follows the liquid: the share still held moves to the gained
		// dose forms, the rest belongs to the consumed doses.
		long retainedCost = totalBasisCost * Math.min(totalGainedDoses, totalLostDoses) / totalLostDoses;
		long allocated = 0;
		for (int i = 0; i < gained.size(); i++)
		{
			PotionBottle p = gained.get(i);
			long share = (i == gained.size() - 1 || totalGainedDoses <= 0)
				? retainedCost - allocated
				: retainedCost * p.doses * p.quantity / totalGainedDoses;
			if (share > 0)
			{
				addBasis(p.itemId, p.quantity, share);
			}
			allocated += share;
		}

		long consumedCost = totalBasisCost - allocated;
		int basisDosesConsumed = Math.min(dosesConsumed, basisDosesHeld);
		return new long[]{consumedCost, basisDosesConsumed};
	}

	/**
	 * Consumes up to maxQty of cost basis FIFO. Returns {consumedQty, cost}.
	 */
	private long[] consumeBasis(int itemId, int maxQty)
	{
		Deque<BasisLot> lots = costBasis.get(itemId);
		if (lots == null || maxQty <= 0)
		{
			return new long[]{0, 0};
		}

		int quantity = 0;
		long cost = 0;
		while (maxQty > 0 && !lots.isEmpty())
		{
			BasisLot lot = lots.peekFirst();
			int take = Math.min(maxQty, lot.quantity);
			long lotCost = take == lot.quantity
				? lot.remainingCost
				: lot.remainingCost * take / lot.quantity;
			lot.quantity -= take;
			lot.remainingCost -= lotCost;
			if (lot.quantity == 0)
			{
				lots.pollFirst();
			}
			quantity += take;
			cost += lotCost;
			maxQty -= take;
		}
		if (lots.isEmpty())
		{
			costBasis.remove(itemId);
		}
		return new long[]{quantity, cost};
	}

	private static int clampQty(long amount)
	{
		return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, amount));
	}

	private static void addCoinGain(GeLedger ledger, long amount)
	{
		ledger.gains.merge(ItemID.COINS,
			new CoinFlowSession.TrackedItem(ItemID.COINS, "Coins", Math.max(0L, amount), 1L),
			(a, b) -> a.merge(b));
	}

	private static void addCoinExpense(GeLedger ledger, long amount)
	{
		ledger.expenses.merge(ItemID.COINS,
			new CoinFlowSession.TrackedItem(ItemID.COINS, "Coins", Math.max(0L, amount), 1L),
			(a, b) -> a.merge(b));
	}
}
