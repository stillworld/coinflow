package com.coinflow;

import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.ItemID;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import static com.coinflow.TestHelpers.gains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GrandExchangeTracker}: offer delta detection, cost-basis
 * FIFO lots, and the carried-value sell decomposition.
 *
 * All {@code spent} values are gross (pre-tax), matching the client API.
 * Tax is 2% per item rounded down: helm @59,000 -> 1,180; shark @1,000 -> 20.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class GrandExchangeTrackerTest
{
	private static final int HELM = 11497; // Dragon med helm
	private static final int SHARK = 385;  // Shark
	private static final long HELM_TAX = 1180L;
	private static final long SHARK_TAX = 20L;

	private GrandExchangeTracker tracker;

	@Before
	public void setUp()
	{
		tracker = new GrandExchangeTracker();
	}

	private static GrandExchangeOffer offer(GrandExchangeOfferState state, int itemId,
		int quantitySold, int totalQuantity, long price, long spent)
	{
		GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(state);
		when(offer.getItemId()).thenReturn(itemId);
		when(offer.getQuantitySold()).thenReturn(quantitySold);
		when(offer.getTotalQuantity()).thenReturn(totalQuantity);
		when(offer.getPrice()).thenReturn(price);
		when(offer.getSpent()).thenReturn(spent);
		return offer;
	}

	private static GrandExchangeOffer empty()
	{
		return offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
	}

	private List<GrandExchangeTracker.GeDelta> changed(int slot, GrandExchangeOffer offer)
	{
		return tracker.onOfferChanged(slot, offer, true);
	}

	private static CoinFlowSession.TrackedItem coins(Map<Integer, CoinFlowSession.TrackedItem> map)
	{
		return map.get(ItemID.COINS);
	}

	private static CoinFlowSession.TrackedItem tax(Map<Integer, CoinFlowSession.TrackedItem> map)
	{
		return map.get(GrandExchangeTax.TAX_ITEM_ID);
	}

	// ── Delta detection ──────────────────────────────────────────────────

	@Test
	public void firstObservation_baselinesAndEmitsNoDelta()
	{
		Assert.assertTrue(changed(0,
			offer(GrandExchangeOfferState.SELLING, SHARK, 5, 10, 1000, 5000)).isEmpty());
	}

	@Test
	public void sellPartialFills_emitIncrementalDeltas()
	{
		changed(0, offer(GrandExchangeOfferState.SELLING, SHARK, 0, 10, 1000, 0));

		List<GrandExchangeTracker.GeDelta> d1 = changed(0,
			offer(GrandExchangeOfferState.SELLING, SHARK, 3, 10, 1000, 3000));
		Assert.assertEquals(1, d1.size());
		Assert.assertEquals(3, d1.get(0).quantityDelta);
		Assert.assertEquals(3000L, d1.get(0).coinsDelta);
		Assert.assertFalse(d1.get(0).buy);

		List<GrandExchangeTracker.GeDelta> d2 = changed(0,
			offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000));
		Assert.assertEquals(1, d2.size());
		Assert.assertEquals(7, d2.get(0).quantityDelta);
		Assert.assertEquals(7000L, d2.get(0).coinsDelta);
	}

	@Test
	public void buyFill_emitsBuyDelta()
	{
		changed(2, offer(GrandExchangeOfferState.BUYING, HELM, 0, 1, 59000, 0));

		List<GrandExchangeTracker.GeDelta> deltas = changed(2,
			offer(GrandExchangeOfferState.BOUGHT, HELM, 1, 1, 59000, 57000));
		Assert.assertEquals(1, deltas.size());
		Assert.assertTrue(deltas.get(0).buy);
		Assert.assertEquals(1, deltas.get(0).quantityDelta);
		Assert.assertEquals(57000L, deltas.get(0).coinsDelta);
		Assert.assertEquals(59000L, deltas.get(0).priceEach);
	}

	@Test
	public void cancelledSell_emitsFilledPortion()
	{
		changed(1, offer(GrandExchangeOfferState.SELLING, SHARK, 0, 10, 1000, 0));
		changed(1, offer(GrandExchangeOfferState.SELLING, SHARK, 4, 10, 1000, 4000));

		List<GrandExchangeTracker.GeDelta> deltas = changed(1,
			offer(GrandExchangeOfferState.CANCELLED_SELL, SHARK, 7, 10, 1000, 7000));
		Assert.assertEquals(1, deltas.size());
		Assert.assertEquals(3, deltas.get(0).quantityDelta);
		Assert.assertEquals(3000L, deltas.get(0).coinsDelta);
		Assert.assertFalse(deltas.get(0).buy);
	}

	@Test
	public void cancelledBuy_emitsFilledPortion()
	{
		changed(1, offer(GrandExchangeOfferState.BUYING, SHARK, 0, 10, 1000, 0));
		changed(1, offer(GrandExchangeOfferState.BUYING, SHARK, 4, 10, 1000, 4000));

		List<GrandExchangeTracker.GeDelta> deltas = changed(1,
			offer(GrandExchangeOfferState.CANCELLED_BUY, SHARK, 7, 10, 1000, 7000));
		Assert.assertEquals(1, deltas.size());
		Assert.assertTrue(deltas.get(0).buy);
		Assert.assertEquals(3, deltas.get(0).quantityDelta);
	}

	@Test
	public void stateTransitionWithoutNewFill_emitsNoDelta()
	{
		changed(0, offer(GrandExchangeOfferState.SELLING, SHARK, 10, 10, 1000, 10000));
		Assert.assertTrue(changed(0,
			offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000)).isEmpty());
	}

	@Test
	public void emptyWhileNotLoggedIn_isIgnored_loggedOutFillsStillDelta()
	{
		changed(0, offer(GrandExchangeOfferState.SELLING, SHARK, 3, 10, 1000, 3000));

		// Login flood: EMPTY for all slots while LOGGING_IN
		tracker.onOfferChanged(0, empty(), false);

		List<GrandExchangeTracker.GeDelta> deltas = changed(0,
			offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000));
		Assert.assertEquals(1, deltas.size());
		Assert.assertEquals(7, deltas.get(0).quantityDelta);
	}

	@Test
	public void emptyWhileLoggedIn_clearsSlot_nextOfferBaselines()
	{
		changed(0, offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000));

		// Offer collected -> genuine slot clear
		changed(0, empty());

		// A new offer that somehow first reports with fills is a baseline, not a delta
		Assert.assertTrue(changed(0,
			offer(GrandExchangeOfferState.SELLING, SHARK, 12, 20, 1000, 12000)).isEmpty());
	}

	@Test
	public void completedOfferThenNewOffer_sameSlot_rebaselines()
	{
		changed(0, offer(GrandExchangeOfferState.SELLING, SHARK, 0, 10, 1000, 0));
		changed(0, offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000));

		Assert.assertTrue(changed(0,
			offer(GrandExchangeOfferState.BUYING, HELM, 0, 1, 59000, 0)).isEmpty());
		Assert.assertTrue(changed(1,
			offer(GrandExchangeOfferState.SELLING, SHARK, 0, 5, 1000, 0)).isEmpty());
	}

	@Test
	public void quantityDecrease_rebaselines()
	{
		changed(0, offer(GrandExchangeOfferState.SELLING, SHARK, 8, 10, 1000, 8000));
		Assert.assertTrue(changed(0,
			offer(GrandExchangeOfferState.SELLING, SHARK, 2, 10, 1000, 2000)).isEmpty());
	}

	@Test
	public void slotsAreIndependent()
	{
		changed(0, offer(GrandExchangeOfferState.SELLING, SHARK, 0, 10, 1000, 0));
		changed(1, offer(GrandExchangeOfferState.BUYING, HELM, 0, 1, 59000, 0));

		Assert.assertTrue(changed(0,
			offer(GrandExchangeOfferState.SELLING, SHARK, 0, 10, 1000, 0)).isEmpty());
		Assert.assertEquals(1, changed(1,
			offer(GrandExchangeOfferState.BOUGHT, HELM, 1, 1, 59000, 59000)).size());
	}

	@Test
	public void loginSequence_emptyThenCompletedOffer_baselines()
	{
		tracker.onOfferChanged(3, empty(), false);
		Assert.assertTrue("Pre-session fills must not be counted", changed(3,
			offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000)).isEmpty());
	}

	@Test
	public void nullOffer_isIgnored()
	{
		Assert.assertTrue(tracker.onOfferChanged(0, null, true).isEmpty());
	}

	// ── Seeding ──────────────────────────────────────────────────────────

	@Test
	public void seed_makesNextFillDeltaInsteadOfBaseline()
	{
		tracker.seed(new GrandExchangeOffer[]{
			offer(GrandExchangeOfferState.SELLING, SHARK, 3, 10, 1000, 3000),
			empty(),
			null
		});

		List<GrandExchangeTracker.GeDelta> deltas = changed(0,
			offer(GrandExchangeOfferState.SELLING, SHARK, 5, 10, 1000, 5000));
		Assert.assertEquals(1, deltas.size());
		Assert.assertEquals(2, deltas.get(0).quantityDelta);

		// Empty/null seeded slots still baseline on first real offer
		Assert.assertTrue(changed(1,
			offer(GrandExchangeOfferState.SOLD, HELM, 1, 1, 59000, 59000)).isEmpty());
		Assert.assertTrue(changed(2,
			offer(GrandExchangeOfferState.SOLD, HELM, 1, 1, 59000, 59000)).isEmpty());
	}

	@Test
	public void seed_null_isNoop()
	{
		tracker.seed(null);
		Assert.assertTrue(changed(0,
			offer(GrandExchangeOfferState.SOLD, SHARK, 10, 10, 1000, 10000)).isEmpty());
	}

	// ── Cost basis ───────────────────────────────────────────────────────

	@Test
	public void basisQuantity_aggregatesLots()
	{
		tracker.addBasis(HELM, 2, 114000);
		tracker.addBasis(HELM, 3, 171000);
		Assert.assertEquals(5, tracker.basisQuantity(HELM));
		Assert.assertEquals(0, tracker.basisQuantity(SHARK));
	}

	@Test
	public void addBasis_rejectsInvalid()
	{
		tracker.addBasis(0, 5, 1000);
		tracker.addBasis(HELM, 0, 1000);
		Assert.assertEquals(0, tracker.basisQuantity(0));
		Assert.assertEquals(0, tracker.basisQuantity(HELM));
	}

	// ── Sell settlement (carried-value decomposition) ────────────────────

	@Test
	public void settleSell_untrackedBankedItem_recordsOnlyGeTax()
	{
		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(
			HELM, 1, 59000, CoinFlowSession.createNew());

		Assert.assertTrue(ledger.gains.isEmpty());
		Assert.assertTrue(ledger.deductions.isEmpty());
		Assert.assertEquals(HELM_TAX, tax(ledger.expenses).getQuantity());
		Assert.assertEquals("GE Tax", tax(ledger.expenses).getName());
		Assert.assertEquals(-HELM_TAX, ledger.netDelta);
	}

	@Test
	public void settleSell_untrackedItemFilledAboveAsk_stillOnlyTax()
	{
		// Carried at the actual gross fill price, so instant-sell upside is not
		// treated as profit on a pre-session asset: residual is tax only.
		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(
			HELM, 1, 60000, CoinFlowSession.createNew());

		Assert.assertTrue(ledger.gains.isEmpty());
		Assert.assertEquals(1200L, tax(ledger.expenses).getQuantity());
		Assert.assertEquals(-1200L, ledger.netDelta);
	}

	@Test
	public void settleSell_untrackedTaxExemptItem_recordsNothing()
	{
		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(
			ItemID.LOBSTER, 100, 20000, CoinFlowSession.createNew());
		Assert.assertTrue(ledger.isEmpty());
		Assert.assertEquals(0L, ledger.netDelta);
	}

	@Test
	public void settleSell_untrackedCheapItem_belowTaxThreshold_recordsNothing()
	{
		// 40 gp each: floor(0.8) = 0 tax
		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(
			SHARK, 10, 400, CoinFlowSession.createNew());
		Assert.assertTrue(ledger.isEmpty());
	}

	@Test
	public void settleSell_lootedItem_residualIsTaxAndDrift()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(HELM, "Dragon med helm", 1, 59000));

		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(HELM, 1, 59000, session);

		long net = 59000 - HELM_TAX;
		Assert.assertEquals(net, coins(ledger.gains).getQuantity());
		Assert.assertEquals(1, ledger.deductions.get(HELM).getQuantity());
		Assert.assertTrue(ledger.expenses.isEmpty());
		Assert.assertEquals(-HELM_TAX, ledger.netDelta);

		CoinFlowSession updated = session.withGainsLossesAndExpenses(
			ledger.gains, ledger.deductions, ledger.expenses);
		Assert.assertNull(updated.getTrackedItems().get(HELM));
		Assert.assertEquals(net, updated.getTotalProfit());
	}

	@Test
	public void settleSell_lootedItem_soldBelowEstimate_lossRealized()
	{
		// Looted at 59,000 estimate, sold for only 55,000 gross (tax 1,100)
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(HELM, "Dragon med helm", 1, 59000));

		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(HELM, 1, 55000, session);

		CoinFlowSession updated = session.withGainsLossesAndExpenses(
			ledger.gains, ledger.deductions, ledger.expenses);
		Assert.assertEquals(53900L, updated.getTotalProfit());
		Assert.assertEquals(53900L - 59000L, ledger.netDelta);
	}

	@Test
	public void settleSell_boughtItem_recordsRealMargin()
	{
		tracker.addBasis(HELM, 1, 57000);

		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(
			HELM, 1, 59000, CoinFlowSession.createNew());

		// 59,000 - 1,180 tax - 57,000 cost = +820
		Assert.assertEquals(820L, coins(ledger.gains).getQuantity());
		Assert.assertTrue(ledger.deductions.isEmpty());
		Assert.assertTrue(ledger.expenses.isEmpty());
		Assert.assertEquals(820L, ledger.netDelta);
		Assert.assertEquals(0, tracker.basisQuantity(HELM));
	}

	@Test
	public void settleSell_boughtItemSoldAtCost_recordsTaxLoss()
	{
		tracker.addBasis(HELM, 1, 59000);

		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(
			HELM, 1, 59000, CoinFlowSession.createNew());

		Assert.assertEquals(HELM_TAX, coins(ledger.expenses).getQuantity());
		Assert.assertTrue(ledger.gains.isEmpty());
		Assert.assertEquals(-HELM_TAX, ledger.netDelta);
	}

	@Test
	public void settleSell_mixedPortions_sumToNetProceeds()
	{
		// 3 helms sold at 59,000 each (gross 177,000; tax 3,540; net 173,460):
		//   1 bought @ 57,000 (basis), 1 looted (tracked @ 59,000), 1 banked
		tracker.addBasis(HELM, 1, 57000);
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(HELM, "Dragon med helm", 1, 59000));

		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(HELM, 3, 177000, session);

		long netEach = 59000 - HELM_TAX; // 57,820
		// basis:     57,820 - 57,000 = +820 gain
		// tracked:   +57,820 gain, -59,000 deduction
		// untracked: 57,820 - 59,000 = -1,180 expense
		Assert.assertEquals(netEach + 820L, coins(ledger.gains).getQuantity());
		Assert.assertEquals(1, ledger.deductions.get(HELM).getQuantity());
		Assert.assertEquals(HELM_TAX, tax(ledger.expenses).getQuantity());
		Assert.assertEquals(820L - HELM_TAX - HELM_TAX, ledger.netDelta);

		CoinFlowSession updated = session.withGainsLossesAndExpenses(
			ledger.gains, ledger.deductions, ledger.expenses);
		// Started at 59,000 (loot est.); ends at net of all three portions
		Assert.assertEquals(59000L + ledger.netDelta, updated.getTotalProfit());
	}

	@Test
	public void settleSell_proceedsRoundingRemainderFallsToUntracked()
	{
		// gross not divisible by quantity: tracked gets floor share, untracked the rest
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(SHARK, "Shark", 1, 1000));

		// 3 sharks, gross 3,001 -> unit 1,000 -> tax 20 each -> net 2,941
		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(SHARK, 3, 3001, session);

		Assert.assertEquals(980L, coins(ledger.gains).getQuantity()); // floor(2941/3)
		// untracked: net share 1,961 - gross share (3001 - 1000) = -40
		Assert.assertEquals(40L, tax(ledger.expenses).getQuantity());
		Assert.assertEquals(2941L - 3001L, ledger.netDelta);
	}

	@Test
	public void settleSell_partialBasisCoverage()
	{
		tracker.addBasis(HELM, 2, 114000);

		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(
			HELM, 3, 177000, CoinFlowSession.createNew());

		// basis: 2 * 57,820 - 114,000 = +1,640 ; untracked: -1,180 tax
		Assert.assertEquals(1640L, coins(ledger.gains).getQuantity());
		Assert.assertEquals(HELM_TAX, tax(ledger.expenses).getQuantity());
		Assert.assertEquals(0, tracker.basisQuantity(HELM));
	}

	@Test
	public void settleSell_multiLotBasis_fifoConsumption()
	{
		tracker.addBasis(HELM, 2, 114000);
		tracker.addBasis(HELM, 1, 60000);

		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(
			HELM, 2, 118000, CoinFlowSession.createNew());

		// FIFO consumes the 57,000 lot: 2 * 57,820 - 114,000 = +1,640
		Assert.assertEquals(1640L, coins(ledger.gains).getQuantity());
		Assert.assertEquals(1, tracker.basisQuantity(HELM));

		// Second lot at 60,000: 57,820 - 60,000 = -2,180
		GrandExchangeTracker.GeLedger ledger2 = tracker.settleSell(
			HELM, 1, 59000, CoinFlowSession.createNew());
		Assert.assertEquals(2180L, coins(ledger2.expenses).getQuantity());
	}

	@Test
	public void settleSell_nullSession_treatsEverythingAsUntracked()
	{
		GrandExchangeTracker.GeLedger ledger = tracker.settleSell(SHARK, 10, 10000, null);
		Assert.assertEquals(SHARK_TAX * 10, tax(ledger.expenses).getQuantity());
		Assert.assertTrue(ledger.gains.isEmpty());
	}

	@Test
	public void settleSell_zeroQuantity_returnsEmptyLedger()
	{
		Assert.assertTrue(tracker.settleSell(SHARK, 0, 0, CoinFlowSession.createNew()).isEmpty());
	}

	@Test
	public void reset_clearsOffersAndBasis()
	{
		changed(0, offer(GrandExchangeOfferState.BUYING, HELM, 0, 1, 59000, 0));
		tracker.addBasis(HELM, 1, 59000);

		tracker.reset();

		Assert.assertEquals(0, tracker.basisQuantity(HELM));
		Assert.assertTrue(changed(0,
			offer(GrandExchangeOfferState.BOUGHT, HELM, 1, 1, 59000, 59000)).isEmpty());
	}
}
