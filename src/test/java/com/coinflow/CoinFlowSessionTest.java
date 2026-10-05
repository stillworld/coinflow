package com.coinflow;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

import static com.coinflow.TestHelpers.gains;
import static com.coinflow.TestHelpers.simulateTicks;
import static com.coinflow.TestHelpers.sumBreakdownValues;

/**
 * Tests for {@link CoinFlowSession} — profit tracking, GP/hr, idle detection,
 * goal calculations, and ETA formatting.
 *
 * Note on timing: {@link CoinFlowSession#tick} uses {@code Instant.now()} internally,
 * so time-dependent assertions use structural checks (e.g. > 0, > ZERO) rather than
 * exact millisecond values. Precise GP/hr accuracy is validated via integration testing.
 */
public class CoinFlowSessionTest
{
	// ── Profit & Item Accumulation ───────────────────────────────────────

	@Test
	public void freshSession_hasZeroProfit()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		Assert.assertEquals(0L, session.getTotalProfit());
	}

	@Test
	public void freshSession_hasNoTrackedItems()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		Assert.assertTrue(session.getTrackedItems().isEmpty());
		Assert.assertTrue(session.getSortedItems().isEmpty());
	}

	@Test
	public void withGains_accumulatesProfit()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.withGains(gains(1, "Yew logs", 500, 1_000L));
		Assert.assertEquals(500_000L, session.getTotalProfit());
	}

	@Test
	public void withGains_emptyMap_isNoop()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		CoinFlowSession after = session.withGains(java.util.Collections.emptyMap());
		Assert.assertSame("withGains(empty) should return same instance", session, after);
	}

	@Test
	public void withGains_mergesQuantityForSameItemAcrossBatches()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.withGains(gains(1, "Iron ore", 10, 200L));
		session = session.withGains(gains(1, "Iron ore", 5, 200L));

		CoinFlowSession.TrackedItem tracked = session.getTrackedItems().get(1);
		Assert.assertNotNull(tracked);
		Assert.assertEquals(15, tracked.getQuantity());
		Assert.assertEquals(3_000L, session.getTotalProfit()); // 15 * 200
	}

	@Test
	public void withGains_totalProfitIsMonotonicallyIncreasing()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		long last = 0;
		for (int i = 1; i <= 5; i++)
		{
			session = session.withGains(gains(i, "item" + i, 10, 100L));
			Assert.assertTrue(session.getTotalProfit() > last);
			last = session.getTotalProfit();
		}
	}

	@Test
	public void withGains_multipleDistinctItems_allTracked()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.withGains(gains(1, "Logs", 100, 50L));
		session = session.withGains(gains(2, "Coal", 200, 150L));

		Assert.assertEquals(2, session.getTrackedItems().size());
		Assert.assertEquals(5_000L + 30_000L, session.getTotalProfit());
	}

	/**
	 * Bug #3 regression: priceEach in the breakdown should reflect the CURRENT
	 * (most recently seen) price, not the price from the first batch.
	 */
	@Test
	public void withGains_priceCurrentAfterMerge_bug3Regression()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		// First batch: 10 items @ 100gp
		session = session.withGains(gains(1, "Yew logs", 10, 100L));
		// Second batch: 5 items @ 200gp (price changed)
		session = session.withGains(gains(1, "Yew logs", 5, 200L));

		CoinFlowSession.TrackedItem item = session.getTrackedItems().get(1);
		Assert.assertNotNull(item);
		// priceEach must reflect the current price (200), not the stale one (100)
		Assert.assertEquals(200L, item.getPriceEach());
		// Total quantity: 10 + 5 = 15
		Assert.assertEquals(15, item.getQuantity());
		// totalProfit tracks actual received value: (10*100) + (5*200) = 2000
		Assert.assertEquals(2_000L, session.getTotalProfit());
	}

	/**
	 * When all items in the session were gained at the same price (stable prices),
	 * the sum of breakdown totalValues must match totalProfit.
	 */
	@Test
	public void withGains_breakdownSumMatchesTotalProfit_stablePrices()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.withGains(gains(1, "Shark", 50, 1_000L));
		session = session.withGains(gains(1, "Shark", 25, 1_000L)); // same price

		Assert.assertEquals(session.getTotalProfit(), sumBreakdownValues(session));
	}

	@Test
	public void sortedItems_orderedByTotalValueDescending()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.withGains(gains(1, "Cheap", 100, 10L));   // totalValue = 1,000
		session = session.withGains(gains(2, "Mid", 10, 500L));     // totalValue = 5,000
		session = session.withGains(gains(3, "Expensive", 5, 2_000L)); // totalValue = 10,000

		List<CoinFlowSession.TrackedItem> sorted = session.getSortedItems();
		Assert.assertEquals(3, sorted.size());
		Assert.assertEquals(3, sorted.get(0).getItemId()); // 10,000
		Assert.assertEquals(2, sorted.get(1).getItemId()); // 5,000
		Assert.assertEquals(1, sorted.get(2).getItemId()); // 1,000
	}

	@Test
	public void sortedItems_isUnmodifiableView()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Item", 1, 100L));
		try
		{
			session.getSortedItems().clear();
			Assert.fail("Expected UnsupportedOperationException");
		}
		catch (UnsupportedOperationException e)
		{
			// expected — the list is unmodifiable
		}
	}

	// ── GP/hr Calculation ────────────────────────────────────────────────

	@Test
	public void gpPerHour_zeroForFreshSession()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		// No time has elapsed (< 1 second), so result must be 0
		Assert.assertEquals(0L, session.getGpPerHour());
		Assert.assertEquals(0L, session.getGpPerHour(true));
	}

	@Test
	public void gpPerHour_zeroWhenNoProfit()
	{
		CoinFlowSession session = simulateTicks(CoinFlowSession.createNew(), 10, 5);
		// Even after ticks (some time elapsed), 0 profit → 0 gp/hr
		Assert.assertEquals(0L, session.getGpPerHour());
	}

	@Test
	public void gpPerHour_afkVsActiveTimeUseDifferentBases()
	{
		// Start a session and go idle immediately (idleTimeout=0 means any elapsed time triggers idle)
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.withGains(gains(1, "Item", 100, 1_000L)); // 100k profit
		// After going idle, activeTime freezes but totalInGameTime keeps growing
		session = simulateTicks(session, 3, 0); // idleTimeout=0 → immediately idle

		// When idle, activeTime is frozen but getGpPerHour(true) uses totalInGameTime
		// Both may be sub-second and return 0, but they must use different denominators
		// (structural check — they should not throw)
		session.getGpPerHour(false);
		session.getGpPerHour(true);
		// If totalInGameTime > 0 seconds but activeTime = 0 seconds:
		// getGpPerHour(false) could differ from getGpPerHour(true)
		// We just verify both are non-negative
		Assert.assertTrue(session.getGpPerHour(false) >= 0);
		Assert.assertTrue(session.getGpPerHour(true) >= 0);
	}

	// ── Idle / Time Tracking ─────────────────────────────────────────────

	@Test
	public void isIdle_falseInitially()
	{
		Assert.assertFalse(CoinFlowSession.createNew().isIdle());
	}

	@Test
	public void tick_accumulatesActiveTime_whenNotIdle()
	{
		CoinFlowSession before = CoinFlowSession.createNew();
		// idleTimeout = 999 minutes → won't go idle from a short test
		CoinFlowSession after = simulateTicks(before, 5, 999);
		// activeTime must grow (even if only by a few milliseconds)
		Assert.assertTrue(after.getActiveTime().compareTo(Duration.ZERO) > 0);
	}

	@Test
	public void tick_accumulatesTotalInGameTime_regardlessOfIdle()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		// idleTimeout=0 → immediately idle
		session = simulateTicks(session, 5, 0);
		// totalInGameTime must grow even when idle
		Assert.assertTrue(session.getTotalInGameTime().compareTo(Duration.ZERO) > 0);
	}

	@Test
	public void isIdle_trueAfterTimeoutElapsed()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		// idleTimeout=0 → toMinutes() of any elapsed time >= 0, so immediately idle
		session = session.tick(0);
		Assert.assertTrue(session.isIdle());
	}

	@Test
	public void isIdle_freezesActiveTimeWhenIdle()
	{
		// idleTimeout=0 → go idle immediately on first tick
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.tick(0);
		Assert.assertTrue(session.isIdle());
		Duration activeAfterFirstTick = session.getActiveTime();

		// Tick more — totalInGameTime grows but activeTime should NOT
		session = session.tick(0);
		Assert.assertEquals(activeAfterFirstTick, session.getActiveTime());
	}

	@Test
	public void tick_transitionToIdle_rollsBackInactivityWindow()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withDurations(Duration.ofSeconds(60), Duration.ofSeconds(60));

		// Tick with idleTimeout = 0 to trigger transition to idle
		CoinFlowSession idleSession = session.tick(0);
		Assert.assertTrue(idleSession.isIdle());
		// Active time should not leak time beyond pre-idle active duration
		Assert.assertTrue("Active time should not exceed initial active time",
			idleSession.getActiveTime().compareTo(Duration.ofSeconds(60)) <= 0);
	}

	@Test
	public void tick_transitionToIdle_doesNotWipeActiveTimeOnLongInactivityGap()
	{
		// Simulate player playing for 10 minutes (600 seconds)
		// and inactive for 30 minutes in the past
		CoinFlowSession session = CoinFlowSession.createNew()
			.withDurations(Duration.ofMinutes(10), Duration.ofMinutes(10))
			.withLastActivityTime(java.time.Instant.now().minus(Duration.ofMinutes(30)));

		// Tick with idleTimeout = 2 minutes: even though lastActivityTime was 30 minutes ago,
		// the rollback is bounded to at most idleTimeoutMinutes (2 min), preserving at least 8 minutes.
		CoinFlowSession idleSession = session.tick(2);
		Assert.assertTrue(idleSession.isIdle());
		Assert.assertTrue("Active time must retain at least (10m - 2m) = 8m of played time",
			idleSession.getActiveTime().compareTo(Duration.ofMinutes(8)) >= 0);
	}

	@Test
	public void withResumedState_preservesSessionDurationsAndResetsActivity()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withDurations(Duration.ofMinutes(15), Duration.ofMinutes(15));

		CoinFlowSession resumed = session.withResumedState();
		Assert.assertEquals(Duration.ofMinutes(15), resumed.getActiveTime());
		Assert.assertEquals(Duration.ofMinutes(15), resumed.getTotalInGameTime());
	}

	@Test
	public void withGains_resetsIdleState()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.tick(0); // go idle
		Assert.assertTrue(session.isIdle());

		session = session.withGains(gains(1, "Item", 1, 100L));
		Assert.assertFalse("withGains should reset idle state", session.isIdle());
	}

	@Test
	public void withGains_preservesLastTickTime()
	{
		// Regression test: gains/expenses are processed on non-tick events that fire
		// milliseconds before GameTick. If withGains overwrote lastTickTime, the next
		// tick() would measure a ~0ms delta and the entire ~600ms interval would be
		// lost from the session clock (inflating GP/hr).
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.tick(999); // establishes a lastTickTime

		CoinFlowSession withGains = session.withGains(gains(1, "Item", 1, 100L));
		Assert.assertEquals("withGains must not advance lastTickTime",
			session.getLastTickTime(), withGains.getLastTickTime());
	}

	@Test
	public void withGainsLossesAndExpenses_preservesLastTickTime()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.tick(999);

		CoinFlowSession updated = session.withGainsLossesAndExpenses(
			gains(1, "Item", 1, 100L), java.util.Collections.emptyMap(),
			java.util.Collections.emptyMap());
		Assert.assertEquals("withGainsLossesAndExpenses must not advance lastTickTime",
			session.getLastTickTime(), updated.getLastTickTime());
	}

	@Test
	public void withActivity_resetsIdleState()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.tick(0); // go idle
		Assert.assertTrue(session.isIdle());

		session = session.withActivity();
		Assert.assertFalse("withActivity should reset idle state", session.isIdle());
	}

	@Test
	public void tick_withPlayerActive_clearsAndPreventsIdleState()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.tick(0); // go idle
		Assert.assertTrue(session.isIdle());

		// Next tick with playerActive=true should clear idle and accumulate active time
		Duration beforeActive = session.getActiveTime();
		session = session.tick(0, true);
		Assert.assertFalse("Active player tick should clear idle state", session.isIdle());
		Assert.assertTrue("Active time should accumulate during active tick",
			session.getActiveTime().compareTo(beforeActive) > 0);
	}

	@Test
	public void getTime_returnsActiveOrTotalBasedOnFlag()
	{
		CoinFlowSession session = simulateTicks(CoinFlowSession.createNew(), 3, 999);
		// When not idle, both should be equal
		Assert.assertEquals(session.getActiveTime(), session.getTime(false));
		Assert.assertEquals(session.getTotalInGameTime(), session.getTime(true));
	}

	// ── Goal Calculations ────────────────────────────────────────────────

	@Test
	public void goalRemaining_fullAmountWhenNoProfit()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		Assert.assertEquals(10_000_000L, session.getGoalRemaining(10_000_000L));
	}

	@Test
	public void goalRemaining_zeroWhenGoalReached()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Item", 1, 10_000_000L));
		Assert.assertEquals(0L, session.getGoalRemaining(10_000_000L));
	}

	@Test
	public void goalRemaining_clampedAtZeroWhenExceeded()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Item", 1, 20_000_000L));
		// Profit exceeds goal, remaining should be 0, not negative
		Assert.assertEquals(0L, session.getGoalRemaining(10_000_000L));
	}

	@Test
	public void goalRemaining_zeroWhenGoalAmountIsZeroOrNegative()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		Assert.assertEquals(0L, session.getGoalRemaining(0L));
		Assert.assertEquals(0L, session.getGoalRemaining(-1L));
	}

	@Test
	public void goalProgress_zeroToOne()
	{
		CoinFlowSession empty = CoinFlowSession.createNew();
		Assert.assertEquals(0.0, empty.getGoalProgress(10_000_000L), 0.001);

		CoinFlowSession half = CoinFlowSession.createNew()
			.withGains(gains(1, "Item", 1, 5_000_000L));
		Assert.assertEquals(0.5, half.getGoalProgress(10_000_000L), 0.001);

		CoinFlowSession done = CoinFlowSession.createNew()
			.withGains(gains(1, "Item", 1, 10_000_000L));
		Assert.assertEquals(1.0, done.getGoalProgress(10_000_000L), 0.001);
	}

	@Test
	public void goalProgress_clampedAtOneWhenExceeded()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Item", 1, 50_000_000L));
		Assert.assertEquals(1.0, session.getGoalProgress(10_000_000L), 0.001);
	}

	@Test
	public void goalProgress_zeroWhenGoalIsZeroOrNegative()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		Assert.assertEquals(0.0, session.getGoalProgress(0L), 0.001);
		Assert.assertEquals(0.0, session.getGoalProgress(-100L), 0.001);
	}

	// ── Goal ETA ─────────────────────────────────────────────────────────

	@Test
	public void goalEta_minusOneBeforeWarmup()
	{
		// Fresh session has < 10 seconds of tracked time → warmup → ETA = -1
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Item", 1, 5_000_000L));
		Assert.assertEquals(-1L, session.getGoalEtaSeconds(10_000_000L, false));
	}

	@Test
	public void goalEta_minusOneWhenNoGoalSet()
	{
		Assert.assertEquals(-1L, CoinFlowSession.createNew().getGoalEtaSeconds(0L, false));
		Assert.assertEquals(-1L, CoinFlowSession.createNew().getGoalEtaSeconds(-1L, false));
	}

	@Test
	public void goalEta_zeroWhenGoalReached()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Item", 1, 10_000_000L));
		Assert.assertEquals(0L, session.getGoalEtaSeconds(10_000_000L, false));
	}

	// ── formatGoalEta ────────────────────────────────────────────────────

	@Test
	public void formatGoalEta_goalReached()
	{
		Assert.assertEquals("Goal Reached!", CoinFlowSession.formatGoalEta(0L));
	}

	@Test
	public void formatGoalEta_calculating()
	{
		Assert.assertEquals("--:--", CoinFlowSession.formatGoalEta(-1L));
	}

	@Test
	public void formatGoalEta_lessThanOneMinute()
	{
		Assert.assertEquals("< 1m", CoinFlowSession.formatGoalEta(30L));
		Assert.assertEquals("< 1m", CoinFlowSession.formatGoalEta(59L));
	}

	@Test
	public void formatGoalEta_exactMinutes()
	{
		Assert.assertEquals("15m", CoinFlowSession.formatGoalEta(15 * 60L));
		Assert.assertEquals("59m", CoinFlowSession.formatGoalEta(59 * 60L));
	}

	@Test
	public void formatGoalEta_hoursAndMinutes()
	{
		Assert.assertEquals("2h 15m", CoinFlowSession.formatGoalEta(2 * 3600L + 15 * 60L));
		Assert.assertEquals("1h 00m", CoinFlowSession.formatGoalEta(3600L));
	}

	@Test
	public void formatGoalEta_over99Hours()
	{
		Assert.assertEquals("> 99h", CoinFlowSession.formatGoalEta(100 * 3600L));
	}

	// ── TrackedItem ──────────────────────────────────────────────────────

	@Test
	public void changedPrices_deductHistoricalValueExactly()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Yew logs", 10, 100))
			.withGains(gains(1, "Yew logs", 5, 200));
		session = session.withGainsLossesAndExpenses(Collections.emptyMap(),
			gains(1, "Yew logs", 7, 200), Collections.emptyMap());
		session = session.withGainsLossesAndExpenses(Collections.emptyMap(),
			gains(1, "Yew logs", 8, 200), Collections.emptyMap());
		Assert.assertEquals(0L, session.getGrossProfit());
		Assert.assertTrue(session.getTrackedItems().isEmpty());
	}

	@Test
	public void coinGainsAboveIntegerLimit_doNotOverflowOnDeduction()
	{
		int coins = net.runelite.api.gameval.ItemID.COINS;
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(coins, "Coins", 1_500_000_000, 1))
			.withGains(gains(coins, "Coins", 1_500_000_000, 1));
		Assert.assertEquals(3_000_000_000L, session.getTrackedItems().get(coins).getQuantity());
		session = session.withGainsLossesAndExpenses(Collections.emptyMap(),
			gains(coins, "Coins", 1, 1), Collections.emptyMap());
		Assert.assertEquals(2_999_999_999L, session.getGrossProfit());
	}

	@Test
	public void consumedSessionGain_cannotBeDeductedAgain()
	{
		// Loot a shark, eat it (expense), then drop an identical banked shark:
		// the consumed loot must not be deducted a second time.
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(385, "Shark", 1, 1_000))
			.withExpenses(gains(385, "Shark", 1, 1_000))
			.markConsumed(Collections.singletonMap(385, 1L));
		Assert.assertEquals(0L, session.getNetProfit());

		session = session.withGainsLossesAndExpenses(
			Collections.emptyMap(), gains(385, "Shark", 1, 1_000), Collections.emptyMap());
		Assert.assertEquals(1_000L, session.getGrossProfit());
		Assert.assertEquals(0L, session.getNetProfit());
	}

	@Test
	public void partialConsumption_leavesRemainingDeductible()
	{
		// Loot 2 sharks, eat one: one unit of the credited gain is still
		// deductible if the player then drops/sells a shark.
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(385, "Shark", 2, 1_000))
			.withExpenses(gains(385, "Shark", 1, 1_000))
			.markConsumed(Collections.singletonMap(385, 1L));

		session = session.withGainsLossesAndExpenses(
			Collections.emptyMap(), gains(385, "Shark", 1, 1_000), Collections.emptyMap());
		Assert.assertEquals(1_000L, session.getGrossProfit());
		Assert.assertEquals(0L, session.getNetProfit());
	}

	@Test
	public void trackedItem_totalValueIsQtyTimesPrice()
	{
		CoinFlowSession.TrackedItem item = new CoinFlowSession.TrackedItem(1, "Log", 200, 150L);
		Assert.assertEquals(30_000L, item.getTotalValue());
	}

	@Test
	public void trackedItem_withAdditionalQuantity_keepsPrice()
	{
		CoinFlowSession.TrackedItem original = new CoinFlowSession.TrackedItem(1, "Log", 10, 100L);
		CoinFlowSession.TrackedItem updated = original.withAdditionalQuantity(5);
		Assert.assertEquals(15, updated.getQuantity());
		Assert.assertEquals(100L, updated.getPriceEach());
		Assert.assertEquals(1_500L, updated.getTotalValue());
	}

	// ── Dual-Ledger Expense & Net Profit Tests ────────────────────────────

	@Test
	public void withExpenses_accumulatesExpensesAndDecreasesNetProfit()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.withGains(gains(1, "Magic logs", 100, 1_000L)); // +100k gross
		Assert.assertEquals(100_000L, session.getGrossProfit());
		Assert.assertEquals(0L, session.getTotalExpenses());
		Assert.assertEquals(100_000L, session.getTotalProfit());

		// Consume 4 doses of stamina potion (6,000 gp total)
		session = session.withExpenses(gains(12625, "Stamina potion (dose)", 4, 1_500L));
		Assert.assertEquals(100_000L, session.getGrossProfit());
		Assert.assertEquals(6_000L, session.getTotalExpenses());
		Assert.assertEquals(94_000L, session.getNetProfit());
		Assert.assertEquals(94_000L, session.getTotalProfit());
		Assert.assertEquals(1, session.getTrackedExpenses().size());
	}

	@Test
	public void withGainsAndExpenses_updatesBothSimultaneously()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.withGainsAndExpenses(
			gains(1, "Runite ore", 10, 11_000L), // +110k gross
			gains(2, "Prayer potion (dose)", 2, 2_500L) // -5k expenses
		);

		Assert.assertEquals(110_000L, session.getGrossProfit());
		Assert.assertEquals(5_000L, session.getTotalExpenses());
		Assert.assertEquals(105_000L, session.getNetProfit());
		Assert.assertEquals(105_000L, session.getTotalProfit());
	}

	@Test
	public void netProfit_negative_whenExpensesExceedGains()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		session = session.withExpenses(gains(1, "Shark", 10, 1_000L)); // -10k expenses
		Assert.assertEquals(0L, session.getGrossProfit());
		Assert.assertEquals(10_000L, session.getTotalExpenses());
		Assert.assertEquals(-10_000L, session.getNetProfit());
		Assert.assertEquals(-10_000L, session.getTotalProfit());
	}

	@Test
	public void grossGpPerHour_and_grossGoalCalculations()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGainsAndExpenses(
				gains(1, "Runite ore", 10, 10_000L), // +100k gross
				gains(2, "Prayer potion", 1, 20_000L) // -20k expenses -> 80k net
			)
			.withDurations(java.time.Duration.ofHours(1), java.time.Duration.ofHours(1));

		long netGpHr = session.getGpPerHour(false);
		long grossGpHr = session.getGrossGpPerHour(false);
		Assert.assertEquals(80_000L, netGpHr);
		Assert.assertEquals(100_000L, grossGpHr);
		Assert.assertTrue("Gross GP/hr should be greater than Net GP/hr when expenses exist", grossGpHr > netGpHr);

		long goal = 100_000L;
		// With trackSpent = true (net tracking): 80k net profit -> 20k remaining, 80% progress
		Assert.assertEquals(20_000L, session.getGoalRemaining(goal, true));
		Assert.assertEquals(0.8, session.getGoalProgress(goal, true), 0.001);

		// With trackSpent = false (gross tracking): 100k gross profit -> 0 remaining, 100% progress
		Assert.assertEquals(0L, session.getGoalRemaining(goal, false));
		Assert.assertEquals(1.0, session.getGoalProgress(goal, false), 0.001);
		Assert.assertEquals(0L, session.getGoalEtaSeconds(goal, false, false));
	}

	// ── Expense Reversal (Count Drops as Spent) ─────────────────────────

	@Test
	public void withExpenseReversal_fullQuantity_removesRowAndRestoresSpent()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withExpenses(gains(1, "Coal", 10, 150L));
		Assert.assertEquals(1_500L, session.getTotalExpenses());

		session = session.withExpenseReversal(Collections.singletonMap(1, 10L));

		Assert.assertEquals(0L, session.getTotalExpenses());
		Assert.assertFalse(session.getTrackedExpenses().containsKey(1));
		Assert.assertEquals(0L, session.getTotalProfit());
	}

	@Test
	public void withExpenseReversal_partialQuantity_reducesProportionally()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withExpenses(gains(1, "Coal", 10, 150L));

		session = session.withExpenseReversal(Collections.singletonMap(1, 4L));

		Assert.assertEquals(900L, session.getTotalExpenses());
		CoinFlowSession.TrackedItem row = session.getTrackedExpenses().get(1);
		Assert.assertEquals(6L, row.getQuantity());
		Assert.assertEquals(900L, row.getTotalValue());
	}

	@Test
	public void withExpenseReversal_moreThanExpensed_capsAtRow()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withExpenses(gains(1, "Coal", 3, 150L));

		session = session.withExpenseReversal(Collections.singletonMap(1, 10L));

		Assert.assertEquals(0L, session.getTotalExpenses());
		Assert.assertFalse(session.getTrackedExpenses().containsKey(1));
	}

	@Test
	public void withExpenseReversal_unknownItemOrEmpty_isNoop()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withExpenses(gains(1, "Coal", 3, 150L));

		Assert.assertSame(session, session.withExpenseReversal(Collections.emptyMap()));
		Assert.assertSame(session, session.withExpenseReversal(Collections.singletonMap(99, 5L)));
		Assert.assertSame(session, session.withExpenseReversal(Collections.singletonMap(1, 0L)));
		Assert.assertEquals(450L, session.getTotalExpenses());
	}

	@Test
	public void withExpenseReversal_blendedRow_reversesAtAveragePrice()
	{
		// 5 eaten at 100 + 5 dropped at 200 share one row worth 1,500
		CoinFlowSession session = CoinFlowSession.createNew()
			.withExpenses(gains(1, "Shark", 5, 100L))
			.withExpenses(gains(1, "Shark", 5, 200L));
		Assert.assertEquals(1_500L, session.getTotalExpenses());

		session = session.withExpenseReversal(Collections.singletonMap(1, 5L));

		Assert.assertEquals("Half the units reverse half the carried value", 750L, session.getTotalExpenses());
		Assert.assertEquals(5L, session.getTrackedExpenses().get(1).getQuantity());
	}

	@Test
	public void withExpenseReversal_restoresConsumedSessionGain()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Coal", 10, 150L))
			.withExpenses(gains(1, "Coal", 4, 150L))
			.markConsumed(Collections.singletonMap(1, 4L));
		Assert.assertEquals(6L, session.getTrackedItems().get(1).getRemainingQuantity());
		Assert.assertEquals(900L, session.getTrackedItems().get(1).getRemainingValue());

		session = session.withExpenseReversal(Collections.singletonMap(1, 4L));

		CoinFlowSession.TrackedItem gain = session.getTrackedItems().get(1);
		Assert.assertEquals(10L, gain.getRemainingQuantity());
		Assert.assertEquals(1_500L, gain.getRemainingValue());
		Assert.assertEquals(1_500L, session.getGrossProfit());
		Assert.assertEquals(0L, session.getTotalExpenses());
		Assert.assertEquals(1_500L, session.getTotalProfit());
	}

	@Test
	public void withExpenseReversal_restoreNeverExceedsOriginalQuantity()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(gains(1, "Coal", 10, 150L))
			.withExpenses(gains(1, "Coal", 2, 150L))
			.markConsumed(Collections.singletonMap(1, 2L));

		session = session.withExpenseReversal(Collections.singletonMap(1, 50L));

		CoinFlowSession.TrackedItem gain = session.getTrackedItems().get(1);
		Assert.assertEquals(10L, gain.getRemainingQuantity());
		Assert.assertEquals(1_500L, gain.getRemainingValue());
	}
}
