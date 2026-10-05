package com.coinflow;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import static com.coinflow.TestHelpers.gains;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShopTracker}: coin-delta detection of shop buys/sells,
 * cost-basis recording on buys, carried-value settlement on sells, and
 * suppression of non-coin diffs while a shop is open.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ShopTrackerTest
{
	private static final int SHARK = 385;
	private static final int HELM = 11497;
	private static final int NOTED_SHARK = 386;

	private GrandExchangeTracker tradeTracker;
	private ShopTracker shopTracker;
	private ItemManager itemManager;
	private CoinFlowSession session;

	@Before
	public void setUp()
	{
		tradeTracker = new GrandExchangeTracker();
		shopTracker = new ShopTracker(tradeTracker);
		session = CoinFlowSession.createNew();
		itemManager = mock(ItemManager.class);
		when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
	}

	private GrandExchangeTracker.GeLedger reconcile(Map<Integer, Integer> gains, Map<Integer, Integer> losses)
	{
		return shopTracker.reconcileDiff(gains, losses, session, itemManager, Collections.<String>emptySet(), false);
	}

	private GrandExchangeTracker.GeLedger reconcileAsIncome(Map<Integer, Integer> gains, Map<Integer, Integer> losses)
	{
		return shopTracker.reconcileDiff(gains, losses, session, itemManager, Collections.<String>emptySet(), true);
	}

	private static Map<Integer, Integer> map(int... idQtyPairs)
	{
		Map<Integer, Integer> map = new HashMap<>();
		for (int i = 0; i < idQtyPairs.length; i += 2)
		{
			map.put(idQtyPairs[i], idQtyPairs[i + 1]);
		}
		return map;
	}

	private static CoinFlowSession.TrackedItem coins(Map<Integer, CoinFlowSession.TrackedItem> m)
	{
		return m.get(ItemID.COINS);
	}

	// ── Buys ────────────────────────────────────────────────────────────

	@Test
	public void buy_recordsCostBasisAndNoSessionEntries()
	{
		GrandExchangeTracker.GeLedger ledger = reconcile(
			map(SHARK, 10), map(ItemID.COINS, 5000));

		Assert.assertTrue(ledger.isEmpty());
		Assert.assertEquals(10, tradeTracker.basisQuantity(SHARK));
	}

	@Test
	public void buy_multipleItemTypes_recordsBasisForEach()
	{
		reconcile(
			map(SHARK, 10, HELM, 2), map(ItemID.COINS, 120000));

		Assert.assertEquals(10, tradeTracker.basisQuantity(SHARK));
		Assert.assertEquals(2, tradeTracker.basisQuantity(HELM));
	}

	@Test
	public void coinLossWithoutItemGain_recordsNothing()
	{
		GrandExchangeTracker.GeLedger ledger = reconcile(
			Collections.<Integer, Integer>emptyMap(), map(ItemID.COINS, 5000));

		Assert.assertTrue(ledger.isEmpty());
		Assert.assertEquals(0, tradeTracker.basisQuantity(SHARK));
	}

	// ── Sells ───────────────────────────────────────────────────────────

	@Test
	public void sell_trackedItem_realizesActualProceeds()
	{
		session = session.withGains(gains(SHARK, "Shark", 10, 1000));

		GrandExchangeTracker.GeLedger ledger = reconcile(
			map(ItemID.COINS, 6000), map(SHARK, 10));

		Assert.assertEquals(6000, coins(ledger.gains).getQuantity());
		Assert.assertEquals(10, ledger.deductions.get(SHARK).getQuantity());
		Assert.assertEquals(1000, ledger.deductions.get(SHARK).getPriceEach());
		Assert.assertEquals(-4000L, ledger.netDelta);
	}

	@Test
	public void sell_basisItem_realizesMargin()
	{
		tradeTracker.addBasis(SHARK, 10, 8000);

		GrandExchangeTracker.GeLedger ledger = reconcile(
			map(ItemID.COINS, 9000), map(SHARK, 10));

		Assert.assertEquals(0, tradeTracker.basisQuantity(SHARK));
		Assert.assertEquals(1000, coins(ledger.gains).getQuantity());
		Assert.assertEquals(1000L, ledger.netDelta);
	}

	@Test
	public void sell_basisItemBelowCost_realizesLoss()
	{
		tradeTracker.addBasis(SHARK, 10, 8000);

		GrandExchangeTracker.GeLedger ledger = reconcile(
			map(ItemID.COINS, 5000), map(SHARK, 10));

		Assert.assertEquals(3000, coins(ledger.expenses).getQuantity());
		Assert.assertEquals(-3000L, ledger.netDelta);
	}

	@Test
	public void sell_untrackedItem_recordsNothing()
	{
		GrandExchangeTracker.GeLedger ledger = reconcile(
			map(ItemID.COINS, 4000), map(HELM, 1));

		Assert.assertTrue(ledger.isEmpty());
	}

	// ── Stock sales as income ───────────────────────────────────────────

	@Test
	public void sell_untrackedItemAsIncome_recordsProceedsAsGain()
	{
		GrandExchangeTracker.GeLedger ledger = reconcileAsIncome(
			map(ItemID.COINS, 4000), map(HELM, 1));

		Assert.assertEquals(4000, coins(ledger.gains).getQuantity());
		Assert.assertEquals(4000L, ledger.netDelta);
	}

	@Test
	public void sellAsIncome_basisAndUntracked_marginPlusIncome()
	{
		tradeTracker.addBasis(SHARK, 5, 4000);

		GrandExchangeTracker.GeLedger ledger = reconcileAsIncome(
			map(ItemID.COINS, 10000), map(SHARK, 10));

		// 5 basis: 5000 net - 4000 cost = 1000 gain; 5 untracked: 5000 income
		Assert.assertEquals(0, tradeTracker.basisQuantity(SHARK));
		Assert.assertEquals(6000, coins(ledger.gains).getQuantity());
		Assert.assertEquals(6000L, ledger.netDelta);
	}

	@Test
	public void sellAsIncome_trackedItem_unchanged()
	{
		session = session.withGains(gains(SHARK, "Shark", 10, 1000));

		GrandExchangeTracker.GeLedger ledger = reconcileAsIncome(
			map(ItemID.COINS, 6000), map(SHARK, 10));

		Assert.assertEquals(6000, coins(ledger.gains).getQuantity());
		Assert.assertEquals(10, ledger.deductions.get(SHARK).getQuantity());
		Assert.assertEquals(1000, ledger.deductions.get(SHARK).getPriceEach());
		Assert.assertEquals(-4000L, ledger.netDelta);
	}

	@Test
	public void coinGainWithoutItemLoss_recordsNothing()
	{
		GrandExchangeTracker.GeLedger ledger = reconcile(
			map(ItemID.COINS, 4000), Collections.<Integer, Integer>emptyMap());

		Assert.assertTrue(ledger.isEmpty());
	}

	// ── Non-coin diffs are suppressed ───────────────────────────────────

	@Test
	public void nonCoinDiff_suppressed()
	{
		GrandExchangeTracker.GeLedger ledger = reconcile(
			map(SHARK, 5), map(HELM, 1));

		Assert.assertTrue(ledger.isEmpty());
		Assert.assertEquals(0, tradeTracker.basisQuantity(SHARK));
	}

	// ── Canonicalization ────────────────────────────────────────────────

	@Test
	public void sell_notedItem_canonicalizesToBaseId()
	{
		when(itemManager.canonicalize(NOTED_SHARK)).thenReturn(SHARK);
		session = session.withGains(gains(SHARK, "Shark", 5, 1000));

		GrandExchangeTracker.GeLedger ledger = reconcile(
			map(ItemID.COINS, 2500), map(NOTED_SHARK, 5));

		Assert.assertEquals(2500, coins(ledger.gains).getQuantity());
		Assert.assertEquals(5, ledger.deductions.get(SHARK).getQuantity());
	}
}
