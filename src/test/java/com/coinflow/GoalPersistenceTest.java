package com.coinflow;

import java.util.Collections;
import net.runelite.client.config.ConfigManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for cross-session goal persistence via {@link GoalTracker}: the
 * per-account persisted GP figure, the completion flag, idempotent session
 * checkpoints, and the goal-amount self-invalidation tag.
 */
public class GoalPersistenceTest
{
	private static final long HASH = 12345L;
	private static final String KEY = GoalTracker.PROGRESS_KEY_PREFIX + HASH;
	private static final String GOAL = "10000";

	private CoinFlowPlugin plugin;
	private CoinFlowConfig config;
	private ConfigManager configManager;

	@Before
	public void setUp()
	{
		plugin = new CoinFlowPlugin();
		config = mock(CoinFlowConfig.class);
		configManager = mock(ConfigManager.class);
		plugin.config = config;
		plugin.configManager = configManager;
		plugin.session = CoinFlowSession.createNew();
		plugin.lastAccountHash = HASH;

		when(config.trackSpent()).thenReturn(true);
		when(config.goalAmount()).thenReturn(GOAL);
		when(config.goalName()).thenReturn("Test Goal");
	}

	private static CoinFlowSession sessionWithProfit(long gp)
	{
		return CoinFlowSession.createNew().withGains(
			Collections.singletonMap(555, new CoinFlowSession.TrackedItem(555, "Iron ore", 1, gp)));
	}

	@Test
	public void persistSession_writesContributionUnderAccountKey()
	{
		plugin.session = sessionWithProfit(5000);

		plugin.goalTracker.persistSession();

		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY, "10000|5000|0|0");
	}

	@Test
	public void persistSession_idempotentAndAccumulatesDelta()
	{
		plugin.session = sessionWithProfit(5000);
		plugin.goalTracker.persistSession();
		// Second call with no change writes nothing.
		plugin.goalTracker.persistSession();
		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY, "10000|5000|0|0");

		// The stored figure now exists; the session grows and only the delta is added.
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("10000|5000|0|0");
		plugin.session = plugin.session.withGains(
			Collections.singletonMap(556, new CoinFlowSession.TrackedItem(556, "Coal", 1, 3000)));
		plugin.goalTracker.persistSession();
		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY, "10000|8000|0|0");
	}

	@Test
	public void persistSession_skippedWhenNoGoal()
	{
		when(config.goalAmount()).thenReturn("");
		plugin.session = sessionWithProfit(5000);

		plugin.goalTracker.persistSession();

		verify(configManager, never()).setConfiguration(eq(CoinFlowConfig.CONFIG_GROUP), anyString(), anyString());
	}

	@Test
	public void persistSession_skippedWhenLoggedOut()
	{
		plugin.lastAccountHash = -1;
		plugin.session = sessionWithProfit(5000);

		plugin.goalTracker.persistSession();

		verify(configManager, never()).setConfiguration(eq(CoinFlowConfig.CONFIG_GROUP), anyString(), anyString());
	}

	@Test
	public void persistSession_skippedWhenComplete()
	{
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("10000|9000|1");
		plugin.session = sessionWithProfit(5000);

		plugin.goalTracker.persistSession();

		verify(configManager, never()).setConfiguration(eq(CoinFlowConfig.CONFIG_GROUP), anyString(), anyString());
	}

	@Test
	public void persistedGp_ignoresMismatchedGoalAmount()
	{
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("5000|3000|0");

		// Stored against goal 5000 while the configured goal is 10000 → ignored.
		Assert.assertEquals(0L, plugin.goalTracker.persistedGp());

		// Same stored value becomes visible when the goal matches again.
		when(config.goalAmount()).thenReturn("5000");
		Assert.assertEquals(3000L, plugin.goalTracker.persistedGp());
	}

	@Test
	public void persistedGp_handlesMalformedValue()
	{
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("garbage");
		Assert.assertEquals(0L, plugin.goalTracker.persistedGp());
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("10000|notanumber|0");
		Assert.assertEquals(0L, plugin.goalTracker.persistedGp());
	}

	@Test
	public void effectiveTotal_combinesPersistedAndSession()
	{
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("10000|4000|0");
		plugin.session = sessionWithProfit(3000);
		Assert.assertEquals(7000L, plugin.goalTracker.effectiveTotal());
	}

	@Test
	public void effectiveTotal_returnsTargetWhenComplete()
	{
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("10000|4000|1|0");
		plugin.session = sessionWithProfit(1);
		Assert.assertEquals(10000L, plugin.goalTracker.effectiveTotal());
		Assert.assertTrue(plugin.goalTracker.isComplete());
	}

	@Test
	public void markComplete_persistsCompleteFlag()
	{
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("10000|4000|0");

		plugin.goalTracker.markComplete();

		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY, "10000|4000|1|0");
	}

	@Test
	public void onGoalAmountChanged_unsetsProgressKey()
	{
		plugin.goalTracker.onGoalAmountChanged();
		verify(configManager).unsetConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY);
	}

	@Test
	public void onNewSession_clearsSessionOffset()
	{
		plugin.session = sessionWithProfit(5000);
		plugin.goalTracker.persistSession();
		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY, "10000|5000|0|0");

		// A new session must not subtract the previous offset from the banked figure.
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("10000|5000|0|0");
		plugin.session = CoinFlowSession.createNew();
		plugin.goalTracker.onNewSession();
		plugin.goalTracker.persistSession();

		verify(configManager, never()).setConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY, "10000|0|0");
	}

	@Test
	public void resetSession_persistsContributionBeforeClearing()
	{
		plugin.session = sessionWithProfit(5000);

		// clientThread is null on the bare plugin → resetSession runs inline.
		plugin.resetSession();

		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY, "10000|5000|0|0");
		Assert.assertEquals(0L, plugin.session.getTotalProfit());
		Assert.assertEquals(0L, plugin.goalDisplayTotal());
	}

	@Test
	public void goalDisplayTotal_zeroWithoutGoal()
	{
		when(config.goalAmount()).thenReturn("");
		plugin.session = sessionWithProfit(5000);
		Assert.assertEquals(0L, plugin.goalDisplayTotal());
	}

	@Test
	public void persistSession_writesTrackedSeconds()
	{
		// includeAfkTime defaults false in the mock → active time is tracked.
		plugin.session = sessionWithProfit(5000)
			.withDurations(java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(90));

		plugin.goalTracker.persistSession();

		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY, "10000|5000|0|60");
	}

	@Test
	public void persistedSeconds_readsNewFieldAndLegacyValues()
	{
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("10000|4000|0|120");
		Assert.assertEquals(120L, plugin.goalTracker.persistedSeconds());

		// Values written before the field existed read as 0 seconds.
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("10000|4000|0");
		Assert.assertEquals(0L, plugin.goalTracker.persistedSeconds());
	}

	@Test
	public void effectiveSeconds_combinesPersistedAndSession()
	{
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("10000|4000|0|120");
		plugin.session = CoinFlowSession.createNew()
			.withDurations(java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(45));
		Assert.assertEquals(150L, plugin.goalTracker.effectiveSeconds());
		Assert.assertEquals(150L, plugin.goalDisplaySeconds());
	}

	@Test
	public void effectiveSeconds_respectsIncludeAfkClock()
	{
		plugin.session = CoinFlowSession.createNew()
			.withDurations(java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(45));

		when(config.includeAfkTime()).thenReturn(true);
		Assert.assertEquals(45L, plugin.goalTracker.effectiveSeconds());
	}

	@Test
	public void persistSession_accumulatesSecondsAcrossSessions()
	{
		plugin.session = sessionWithProfit(5000)
			.withDurations(java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(60));
		plugin.goalTracker.persistSession();
		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY, "10000|5000|0|60");

		// Session replaced → offsets reset; new session time adds on top.
		when(configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY)).thenReturn("10000|5000|0|60");
		plugin.session = sessionWithProfit(3000)
			.withDurations(java.time.Duration.ofSeconds(40), java.time.Duration.ofSeconds(40));
		plugin.goalTracker.onNewSession();
		plugin.goalTracker.persistSession();
		verify(configManager).setConfiguration(CoinFlowConfig.CONFIG_GROUP, KEY, "10000|8000|0|100");
	}

	@Test
	public void goalEta_blendedRateAcrossSessions()
	{
		CoinFlowSession session = sessionWithProfit(1);
		long goal = 10_000L;

		// Blended pace: 5000 gp over 100s → remaining 5000 takes another 100s.
		Assert.assertEquals(100L, session.getGoalEtaSeconds(goal, 5000L, 100L));
		// Achieved → 0; warmup/no-rate → -1.
		Assert.assertEquals(0L, session.getGoalEtaSeconds(goal, 10_000L, 500L));
		Assert.assertEquals(-1L, session.getGoalEtaSeconds(goal, 5000L, 5L));
		Assert.assertEquals(-1L, session.getGoalEtaSeconds(goal, 0L, 100L));
		Assert.assertEquals(-1L, session.getGoalEtaSeconds(0L, 5000L, 100L));
	}

	@Test
	public void sessionGoalOverloads_useExplicitCurrentTotal()
	{
		CoinFlowSession session = sessionWithProfit(3000);
		long goal = 10_000L;

		// Persisted 5000 + session 3000 = 8000 toward a 10000 goal.
		Assert.assertEquals(0.8, session.getGoalProgress(goal, 8000L), 0.001);
		Assert.assertEquals(2000L, session.getGoalRemaining(goal, 8000L));
		Assert.assertEquals(0.3, session.getGoalProgress(goal, 3000L), 0.001);

		// Complete at or above target; negative totals clamp to zero.
		Assert.assertEquals(1.0, session.getGoalProgress(goal, 12_000L), 0.001);
		Assert.assertEquals(0L, session.getGoalRemaining(goal, 12_000L));
		Assert.assertEquals(0L, session.getGoalEtaSeconds(goal, false, true, 12_000L));
		Assert.assertEquals(0.0, session.getGoalProgress(goal, -500L), 0.001);
		Assert.assertEquals(goal, session.getGoalRemaining(goal, -500L));
	}
}
