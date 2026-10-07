package com.coinflow;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeathTracker}: pending-death settle predicate,
 * loss computation, and the recovery ledger / matching rules.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class DeathTrackerTest
{
	@Mock
	ItemManager itemManager;

	private DeathTracker tracker;

	@Before
	public void setUp()
	{
		tracker = new DeathTracker();
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

	private static Map<Integer, Long> prices(Object... idPricePairs)
	{
		Map<Integer, Long> map = new HashMap<>();
		for (int i = 0; i < idPricePairs.length; i += 2)
		{
			map.put((Integer) idPricePairs[i], (Long) idPricePairs[i + 1]);
		}
		return map;
	}

	private void stubName(int itemId, String name)
	{
		ItemComposition comp = mock(ItemComposition.class);
		when(comp.getName()).thenReturn(name);
		when(itemManager.getItemComposition(itemId)).thenReturn(comp);
	}

	// ── Settle predicate ───────────────────────────────────────────────

	@Test
	public void settle_requiresMinimumTicksAndQuietWindow()
	{
		tracker.onDeath(map(4151, 1), 100);
		tracker.onContainerChanged(102);

		Assert.assertFalse("too early", tracker.shouldSettle(102));
		Assert.assertFalse("quiet window not elapsed", tracker.shouldSettle(103));
		Assert.assertTrue("min ticks + quiet satisfied", tracker.shouldSettle(104));
	}

	@Test
	public void settle_timeoutFiresWithoutAnyContainerChange()
	{
		tracker.onDeath(map(4151, 1), 100);

		Assert.assertFalse(tracker.shouldSettle(110));
		Assert.assertTrue("timeout settles a safe death", tracker.shouldSettle(120));
	}

	@Test
	public void secondDeathWhilePending_keepsFirstCapture()
	{
		tracker.onDeath(map(4151, 1, 995, 100), 100);
		tracker.onDeath(map(4151, 1), 105);

		// The original capture is retained
		Map<Integer, Integer> lost = tracker.computeLosses(new HashMap<>(), itemManager);
		Assert.assertEquals(Integer.valueOf(1), lost.get(4151));
		Assert.assertEquals(Integer.valueOf(100), lost.get(995));
	}

	// ── Loss computation ───────────────────────────────────────────────

	@Test
	public void computeLosses_diffsPreAndPost()
	{
		tracker.onDeath(map(4151, 1, 385, 5, 995, 5000), 100);

		// Kept: 3 sharks + all coins
		Map<Integer, Integer> lost = tracker.computeLosses(map(385, 3, 995, 5000), itemManager);

		Assert.assertEquals(Integer.valueOf(1), lost.get(4151));
		Assert.assertEquals(Integer.valueOf(2), lost.get(385));
		Assert.assertNull(lost.get(995));
	}

	@Test
	public void computeLosses_cancelsDegradeTransitions()
	{
		int glory4 = 1712;
		int glory3 = 1710;
		stubName(glory4, "Amulet of glory(4)");
		stubName(glory3, "Amulet of glory(3)");

		tracker.onDeath(map(glory4, 1), 100);
		Map<Integer, Integer> lost = tracker.computeLosses(map(glory3, 1), itemManager);

		Assert.assertTrue("charged->degraded keep must not be a loss", lost.isEmpty());
	}

	@Test
	public void computeLosses_cancelsBrokenTransitions()
	{
		int legs = 26386;
		int legsBroken = 26387;
		stubName(legs, "Torva platelegs");
		stubName(legsBroken, "Torva platelegs (broken)");

		tracker.onDeath(map(legs, 1), 100);
		Map<Integer, Integer> lost = tracker.computeLosses(map(legsBroken, 1), itemManager);

		Assert.assertTrue("item kept as (broken) must not be a loss", lost.isEmpty());
	}

	@Test
	public void discardPending_keepsLedger_resetClearsIt()
	{
		tracker.onDeath(map(4151, 1), 100);
		tracker.discardPending();
		Assert.assertFalse(tracker.isPending());

		tracker.recordLost(map(4151, 1), prices(4151, 1_500_000L));
		Assert.assertTrue(tracker.hasLedger());
		tracker.discardPending();
		Assert.assertTrue("ledger must survive discard", tracker.hasLedger());

		tracker.reset();
		Assert.assertFalse(tracker.hasLedger());
	}

	// ── Recovery ledger ────────────────────────────────────────────────

	@Test
	public void recover_capsAtLedgerQuantityAndPricesAtDeathTime()
	{
		tracker.recordLost(map(4151, 2), prices(4151, 1_500_000L));

		Map<Integer, Integer> gains = map(4151, 5);
		DeathTracker.RecoveryResult result = tracker.recover(gains);

		Assert.assertEquals(3_000_000L, result.value);
		Assert.assertEquals(Integer.valueOf(3), gains.get(4151));
		Assert.assertFalse(tracker.hasLedger());
	}

	@Test
	public void recoverForTakeClicks_requiresClick()
	{
		tracker.recordLost(map(4151, 1), prices(4151, 1_500_000L));

		// No take click recorded: gain is untouched
		Map<Integer, Integer> gains = map(4151, 1);
		DeathTracker.RecoveryResult none = tracker.recoverForTakeClicks(gains, 200);
		Assert.assertTrue(none.isEmpty());
		Assert.assertEquals(Integer.valueOf(1), gains.get(4151));

		// With a take click: recovered
		tracker.recordTakeClick(4151, 205);
		DeathTracker.RecoveryResult result = tracker.recoverForTakeClicks(gains, 206);
		Assert.assertEquals(1_500_000L, result.value);
		Assert.assertTrue(gains.isEmpty());
		Assert.assertFalse(tracker.hasLedger());
	}

	@Test
	public void recoverForTakeClicks_expiresOldClicks()
	{
		tracker.recordLost(map(4151, 1), prices(4151, 1_500_000L));
		tracker.recordTakeClick(4151, 200);

		Map<Integer, Integer> gains = map(4151, 1);
		DeathTracker.RecoveryResult result = tracker.recoverForTakeClicks(gains, 200 + DeathTracker.TAKE_CLICK_WINDOW_TICKS + 1);
		Assert.assertTrue(result.isEmpty());
		Assert.assertEquals(Integer.valueOf(1), gains.get(4151));
	}

	// ── Reclaim interface settle ───────────────────────────────────────

	@Test
	public void settleRetrieval_matchesGainsAndReportsCoinFee()
	{
		tracker.recordLost(map(4151, 1, 385, 3, ItemID.COINS, 50_000),
			prices(4151, 1_500_000L, 385, 800L, ItemID.COINS, 1L));

		// Interface opened: player holds 20k coins
		tracker.setRetrievalBaseline(map(ItemID.COINS, 20_000), InterfaceID.GRAVESTONE_GENERIC);
		Assert.assertTrue(tracker.hasRetrievalBaseline());

		// Closed: player reclaimed whip + 2 sharks, paid a 5k fee
		DeathTracker.RecoveryResult result =
			tracker.settleRetrieval(map(4151, 1, 385, 2, ItemID.COINS, 15_000), 200, false);

		Assert.assertEquals(1_500_000L + 2 * 800L, result.value);
		// Gravestone: whip is 1m-10m -> 10k tier; observed 5k coin fee; max wins
		Assert.assertEquals(10_000L, result.fee);
		Assert.assertEquals(1, tracker.ledgerQuantity(385));
		Assert.assertEquals(50_000, tracker.ledgerQuantity(ItemID.COINS));
		Assert.assertFalse(tracker.hasRetrievalBaseline());
	}

	@Test
	public void settleRetrieval_capsObservedFee()
	{
		tracker.recordLost(map(385, 1), prices(385, 800L));
		tracker.setRetrievalBaseline(map(ItemID.COINS, 100_000_000), InterfaceID.GRAVESTONE_GENERIC);

		DeathTracker.RecoveryResult result = tracker.settleRetrieval(map(385, 1), 200, false);

		Assert.assertEquals(DeathTracker.MAX_RETRIEVAL_FEE, result.fee);
	}

	@Test
	public void settleRetrieval_officeComputesFivePercentBankPaid()
	{
		// Reclaim paid from the bank: no inventory coin movement at all
		tracker.recordLost(map(11838, 1), prices(11838, 126_844L));
		tracker.setRetrievalBaseline(map(ItemID.COINS, 500), InterfaceID.DEATH_OFFICE);

		DeathTracker.RecoveryResult result =
			tracker.settleRetrieval(map(11838, 1, ItemID.COINS, 500), 200, false);

		Assert.assertEquals(126_844L, result.value);
		Assert.assertEquals(126_844L * 5 / 100, result.fee);
	}

	@Test
	public void settleRetrieval_officeComputesFeePerUnit()
	{
		tracker.recordLost(map(4151, 3), prices(4151, 150_000L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.DEATH_OFFICE);

		DeathTracker.RecoveryResult result = tracker.settleRetrieval(map(4151, 3), 200, false);

		Assert.assertEquals(3 * (150_000L * 5 / 100), result.fee);
	}

	@Test
	public void settleRetrieval_officeFreeBelowThreshold()
	{
		tracker.recordLost(map(385, 5), prices(385, 800L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.DEATH_OFFICE);

		DeathTracker.RecoveryResult result = tracker.settleRetrieval(map(385, 5), 200, false);

		Assert.assertEquals(0L, result.fee);
	}

	@Test
	public void settleRetrieval_computedFeeNotCapped()
	{
		// A ~120m office reclaim legitimately exceeds the observed-fee cap
		tracker.recordLost(map(22323, 1), prices(22323, 120_000_000L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.DEATH_OFFICE);

		DeathTracker.RecoveryResult result = tracker.settleRetrieval(map(22323, 1), 200, false);

		Assert.assertEquals(6_000_000L, result.fee);
	}

	@Test
	public void settleRetrieval_gravestoneTiers()
	{
		tracker.recordLost(map(1, 1, 2, 1, 3, 1),
			prices(1, 150_000L, 2, 5_000_000L, 3, 50_000_000L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.GRAVESTONE_RETRIEVAL);

		DeathTracker.RecoveryResult result = tracker.settleRetrieval(map(1, 1, 2, 1, 3, 1), 200, false);

		Assert.assertEquals(1_000L + 10_000L + 100_000L, result.fee);
	}

	@Test
	public void settleRetrieval_gravestoneTotalCapped()
	{
		tracker.recordLost(map(2, 100), prices(2, 5_000_000L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.GRAVESTONE_GENERIC);

		DeathTracker.RecoveryResult result = tracker.settleRetrieval(map(2, 100), 200, false);

		Assert.assertEquals(DeathTracker.GRAVE_FEE_CAP, result.fee);
	}

	@Test
	public void settleRetrieval_ironmanHalfFee()
	{
		tracker.recordLost(map(11838, 1), prices(11838, 126_844L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.DEATH_OFFICE);

		DeathTracker.RecoveryResult result = tracker.settleRetrieval(map(11838, 1), 200, true);

		Assert.assertEquals((126_844L * 5 / 100) / 2, result.fee);
	}

	@Test
	public void settleRetrieval_partialReclaimPaysPartialFee()
	{
		tracker.recordLost(map(4151, 3), prices(4151, 1_500_000L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.DEATH_OFFICE);

		// Take only 1 of 3
		DeathTracker.RecoveryResult result = tracker.settleRetrieval(map(4151, 1), 200, false);

		Assert.assertEquals(1_500_000L * 5 / 100, result.fee);
		Assert.assertEquals(2, tracker.ledgerQuantity(4151));
	}

	@Test
	public void setRetrievalBaseline_noopWithEmptyLedger()
	{
		tracker.setRetrievalBaseline(map(4151, 1), InterfaceID.DEATH_OFFICE);
		Assert.assertFalse(tracker.hasRetrievalBaseline());
	}

	@Test
	public void settleRetrieval_equipmentReturnCounts()
	{
		// Items reclaimed straight into equipment appear in the combined map
		tracker.recordLost(map(4151, 1), prices(4151, 1_500_000L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.DEATH_OFFICE);

		DeathTracker.RecoveryResult result = tracker.settleRetrieval(map(4151, 1), 200, false);
		Assert.assertEquals(1_500_000L, result.value);
	}

	// ── Chat-reported fees & post-close recovery grace ─────────────────

	@Test
	public void settleRetrieval_foldsChatFeeWhenItemsMissed()
	{
		// Reclaim-to-bank (or a swallowed delivery): nothing enters inv/worn,
		// so computed + observed fees are zero — the chat message still lands.
		tracker.recordLost(map(4151, 1), prices(4151, 200_000L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.DEATH_OFFICE);
		tracker.noteChatFee(8_915, 195);

		DeathTracker.RecoveryResult result = tracker.settleRetrieval(new HashMap<>(), 200, false);
		Assert.assertEquals(8_915L, result.fee);
		Assert.assertTrue(result.recoveredQuantities.isEmpty());
	}

	@Test
	public void settleRetrieval_chatFeePreferredOverComputed()
	{
		// Chat (40k) and computed (5% of 1.5m = 75k) describe the same
		// charge. The message is the exact amount — the game's reclaim
		// valuation differs from our ledger prices — so it wins outright
		// rather than max-ing against the estimate.
		tracker.recordLost(map(4151, 1), prices(4151, 1_500_000L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.DEATH_OFFICE);
		tracker.noteChatFee(40_000, 195);

		DeathTracker.RecoveryResult result = tracker.settleRetrieval(map(4151, 1), 200, false);
		Assert.assertEquals(1_500_000L, result.value);
		Assert.assertEquals("exact chat fee beats computed estimate", 40_000L, result.fee);
	}

	@Test
	public void settleRetrieval_chatFeeDoesNotMaskObservedCoinLoss()
	{
		// A larger real coin outflow (e.g. a coffer purchase while the
		// interface was open) still surfaces over a smaller chat fee.
		tracker.recordLost(map(4151, 1), prices(4151, 200_000L));
		tracker.setRetrievalBaseline(map(ItemID.COINS, 50_000), InterfaceID.DEATH_OFFICE);
		tracker.noteChatFee(8_915, 195);

		DeathTracker.RecoveryResult result = tracker.settleRetrieval(map(4151, 1), 200, false);
		Assert.assertEquals(50_000L, result.fee);
	}

	@Test
	public void settleRetrieval_chatFeeWinsWhenHigher()
	{
		tracker.recordLost(map(4151, 1), prices(4151, 200_000L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.DEATH_OFFICE);
		tracker.noteChatFee(30_000, 195);

		DeathTracker.RecoveryResult result = tracker.settleRetrieval(map(4151, 1), 200, false);
		Assert.assertEquals("chat fee (30k) beats computed 5% of 200k (10k)", 30_000L, result.fee);
	}

	@Test
	public void noteChatFee_ignoredWithoutReclaimContext()
	{
		tracker.noteChatFee(5_000, 100);
		Assert.assertEquals(0L, tracker.consumeDeferredChatFee(500));
	}

	@Test
	public void consumeDeferredChatFee_appliesOnceAfterWindow()
	{
		tracker.recordLost(map(4151, 1), prices(4151, 200_000L));
		tracker.noteChatFee(8_915, 100);

		Assert.assertEquals("too fresh to apply", 0L, tracker.consumeDeferredChatFee(101));
		Assert.assertEquals(8_915L, tracker.consumeDeferredChatFee(102));
		Assert.assertEquals("consumed exactly once", 0L, tracker.consumeDeferredChatFee(103));
	}

	@Test
	public void inRecoveryGrace_matchesWithoutTakeClick()
	{
		tracker.recordLost(map(4151, 1), prices(4151, 1_500_000L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.DEATH_OFFICE);
		tracker.settleRetrieval(new HashMap<>(), 200, false);

		Assert.assertTrue("grace active right after settle", tracker.inRecoveryGrace(205));
		Map<Integer, Integer> gains = new HashMap<>(map(4151, 1));
		DeathTracker.RecoveryResult result = tracker.recover(gains);
		Assert.assertEquals(1_500_000L, result.value);
		Assert.assertTrue(gains.isEmpty());
	}

	@Test
	public void inRecoveryGrace_expires()
	{
		tracker.recordLost(map(4151, 1), prices(4151, 1_500_000L));
		tracker.setRetrievalBaseline(new HashMap<>(), InterfaceID.DEATH_OFFICE);
		tracker.settleRetrieval(new HashMap<>(), 200, false);

		Assert.assertFalse(tracker.inRecoveryGrace(200 + DeathTracker.RECOVERY_GRACE_TICKS + 1));
	}

	@Test
	public void noteGraveRetrieval_opensRecoveryWindow()
	{
		// A direct gravestone claim opens no retrieval interface — the chat
		// message alone grants the unconditional recovery window.
		tracker.recordLost(map(4151, 1), prices(4151, 1_500_000L));
		tracker.noteGraveRetrieval(300);

		Assert.assertTrue(tracker.inRecoveryGrace(300));
		Map<Integer, Integer> gains = new HashMap<>(map(4151, 1));
		DeathTracker.RecoveryResult result = tracker.recover(gains);
		Assert.assertEquals(1_500_000L, result.value);
		Assert.assertTrue(gains.isEmpty());
	}

	@Test
	public void noteGraveRetrieval_ignoredWithoutLedger()
	{
		tracker.noteGraveRetrieval(300);
		Assert.assertFalse("no ledger, no window", tracker.inRecoveryGrace(300));
	}

	@Test
	public void noteGraveRetrieval_windowExpires()
	{
		tracker.recordLost(map(4151, 1), prices(4151, 200_000L));
		tracker.noteGraveRetrieval(300);

		Assert.assertTrue(tracker.inRecoveryGrace(300 + DeathTracker.RECOVERY_GRACE_TICKS));
		Assert.assertFalse(tracker.inRecoveryGrace(300 + DeathTracker.RECOVERY_GRACE_TICKS + 1));
	}
}
