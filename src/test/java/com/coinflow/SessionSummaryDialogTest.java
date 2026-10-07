package com.coinflow;

import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class SessionSummaryDialogTest
{
	/** Dialog construction needs a display; tests run headless via build.gradle. */
	private static void assumeHeaded()
	{
		Assume.assumeFalse(GraphicsEnvironment.isHeadless());
	}

	@Test
	public void sessionSummaryDialog_constructsWithNormalSession()
	{
		assumeHeaded();
		Map<Integer, CoinFlowSession.TrackedItem> gains = new HashMap<>();
		gains.put(1, new CoinFlowSession.TrackedItem(1, "Grimy ranarr weed", 10, 100_000L));
		gains.put(2, new CoinFlowSession.TrackedItem(2, "Rune 2h sword", 2, 40_000L));

		Map<Integer, CoinFlowSession.TrackedItem> expenses = new HashMap<>();
		expenses.put(3, new CoinFlowSession.TrackedItem(3, "Ranarr seed", 2, 30_000L));

		CoinFlowSession session = CoinFlowSession.createNew()
			.withGainsAndExpenses(gains, expenses);

		// Tick with some time to accumulate active and total in-game time
		session = session.tick(5, true);

		AtomicBoolean resetCalled = new AtomicBoolean(false);
		SessionSummaryDialog dialog = new SessionSummaryDialog((java.awt.Frame) null, session, () -> resetCalled.set(true));

		Assert.assertNotNull(dialog);
		Assert.assertEquals("Coin Flow — Session Summary", dialog.getTitle());
		dialog.dispose();
	}

	@Test
	public void sessionSummaryDialog_emptySession_doesNotThrow()
	{
		assumeHeaded();
		CoinFlowSession emptySession = CoinFlowSession.createNew();

		SessionSummaryDialog dialog = new SessionSummaryDialog((java.awt.Frame) null, emptySession, () -> {});
		Assert.assertNotNull(dialog);
		dialog.dispose();
	}

	@Test
	public void sessionSummaryDialog_netLossSession_doesNotThrow()
	{
		assumeHeaded();
		Map<Integer, CoinFlowSession.TrackedItem> expenses = Collections.singletonMap(
			1, new CoinFlowSession.TrackedItem(1, "Stamina potion(4)", 5, 20_000L)
		);

		// Net loss: 0 gains, 100k expenses
		CoinFlowSession session = CoinFlowSession.createNew().withExpenses(expenses);

		SessionSummaryDialog dialog = new SessionSummaryDialog((java.awt.Frame) null, session, () -> {});
		Assert.assertNotNull(dialog);
		dialog.dispose();
	}

	@Test
	public void sessionSummaryDialog_zeroExpensesSession_doesNotThrow()
	{
		assumeHeaded();
		Map<Integer, CoinFlowSession.TrackedItem> gains = Collections.singletonMap(
			1, new CoinFlowSession.TrackedItem(1, "Dragon bones", 100, 2_500L)
		);

		CoinFlowSession session = CoinFlowSession.createNew().withGains(gains);

		SessionSummaryDialog dialog = new SessionSummaryDialog((java.awt.Frame) null, session, () -> {});
		Assert.assertNotNull(dialog);
		dialog.dispose();
	}

	@Test
	public void showDialog_headless_doesNotThrow()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		// In headless test environments, showDialog safely no-ops without throwing HeadlessException
		SessionSummaryDialog.showDialog(null, session, () -> {});
		SessionSummaryDialog.showDialog(null, session, false, () -> {});
	}

	@Test
	public void sessionSummaryDialog_grossOnlyMode_doesNotThrow()
	{
		assumeHeaded();
		Map<Integer, CoinFlowSession.TrackedItem> gains = Collections.singletonMap(
			1, new CoinFlowSession.TrackedItem(1, "Dragon bones", 100, 2_500L)
		);
		Map<Integer, CoinFlowSession.TrackedItem> expenses = Collections.singletonMap(
			2, new CoinFlowSession.TrackedItem(2, "Prayer potion(4)", 5, 10_000L)
		);

		CoinFlowSession session = CoinFlowSession.createNew()
			.withGainsAndExpenses(gains, expenses);

		SessionSummaryDialog dialog = new SessionSummaryDialog((java.awt.Frame) null, session, false, () -> {});
		Assert.assertNotNull(dialog);
		dialog.dispose();
	}

	@Test
	public void displaySummary_defaultsToFalse()
	{
		CoinFlowConfig config = new CoinFlowConfig() {};
		Assert.assertFalse("displaySummary should default to false (opt-in)", config.displaySummary());
	}

	@Test
	public void promptResetSession_whenDisplaySummaryEnabled_opensSummaryDialogForActiveSession()
	{
		CoinFlowPlugin plugin = org.mockito.Mockito.mock(CoinFlowPlugin.class);
		CoinFlowConfig config = org.mockito.Mockito.mock(CoinFlowConfig.class);
		net.runelite.client.config.ConfigManager configManager = org.mockito.Mockito.mock(net.runelite.client.config.ConfigManager.class);
		net.runelite.client.game.ItemManager itemManager = org.mockito.Mockito.mock(net.runelite.client.game.ItemManager.class);

		org.mockito.Mockito.when(config.displaySummary()).thenReturn(true);
		org.mockito.Mockito.when(config.trackSpent()).thenReturn(true);

		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(Collections.singletonMap(1, new CoinFlowSession.TrackedItem(1, "Coal", 10, 200L)));
		org.mockito.Mockito.when(plugin.getSession()).thenReturn(session);
		plugin.session = session;

		AtomicBoolean summaryOpened = new AtomicBoolean(false);
		CoinFlowPanel panel = new CoinFlowPanel(plugin, config, configManager, itemManager)
		{
			@Override
			void openSessionSummary(CoinFlowSession s)
			{
				summaryOpened.set(true);
			}
		};
		panel.init();

		panel.promptResetSession();
		Assert.assertTrue("openSessionSummary should be called when displaySummary is enabled and session has activity",
			summaryOpened.get());
	}

	@Test
	public void promptResetSession_whenDisplaySummaryDisabled_doesNotOpenSummaryDialog()
	{
		CoinFlowPlugin plugin = org.mockito.Mockito.mock(CoinFlowPlugin.class);
		CoinFlowConfig config = org.mockito.Mockito.mock(CoinFlowConfig.class);
		net.runelite.client.config.ConfigManager configManager = org.mockito.Mockito.mock(net.runelite.client.config.ConfigManager.class);
		net.runelite.client.game.ItemManager itemManager = org.mockito.Mockito.mock(net.runelite.client.game.ItemManager.class);

		org.mockito.Mockito.when(config.displaySummary()).thenReturn(false);

		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(Collections.singletonMap(1, new CoinFlowSession.TrackedItem(1, "Coal", 10, 200L)));
		org.mockito.Mockito.when(plugin.getSession()).thenReturn(session);
		plugin.session = session;

		AtomicBoolean summaryOpened = new AtomicBoolean(false);
		CoinFlowPanel panel = new CoinFlowPanel(plugin, config, configManager, itemManager)
		{
			@Override
			void openSessionSummary(CoinFlowSession s)
			{
				summaryOpened.set(true);
			}

			@Override
			boolean confirmResetSession()
			{
				return false;
			}
		};
		panel.init();

		panel.promptResetSession();
		Assert.assertFalse("openSessionSummary should not be called when displaySummary is disabled",
			summaryOpened.get());
	}

	@Test
	public void promptResetSession_whenDisplaySummaryEnabled_butNoActivity_doesNotOpenSummaryDialog()
	{
		CoinFlowPlugin plugin = org.mockito.Mockito.mock(CoinFlowPlugin.class);
		CoinFlowConfig config = org.mockito.Mockito.mock(CoinFlowConfig.class);
		net.runelite.client.config.ConfigManager configManager = org.mockito.Mockito.mock(net.runelite.client.config.ConfigManager.class);
		net.runelite.client.game.ItemManager itemManager = org.mockito.Mockito.mock(net.runelite.client.game.ItemManager.class);

		org.mockito.Mockito.when(config.displaySummary()).thenReturn(true);

		// Completely empty session without activity
		CoinFlowSession emptySession = CoinFlowSession.createNew();
		org.mockito.Mockito.when(plugin.getSession()).thenReturn(emptySession);
		plugin.session = emptySession;

		AtomicBoolean summaryOpened = new AtomicBoolean(false);
		CoinFlowPanel panel = new CoinFlowPanel(plugin, config, configManager, itemManager)
		{
			@Override
			void openSessionSummary(CoinFlowSession s)
			{
				summaryOpened.set(true);
			}

			@Override
			boolean confirmResetSession()
			{
				return false;
			}
		};
		panel.init();

		panel.promptResetSession();
		Assert.assertFalse("openSessionSummary should not be called when there is no financial activity",
			summaryOpened.get());
	}

	@Test
	public void onFinishSessionClicked_whenDisplaySummaryDisabled_doesNotOpenSummaryDialog()
	{
		CoinFlowPlugin plugin = org.mockito.Mockito.mock(CoinFlowPlugin.class);
		CoinFlowConfig config = org.mockito.Mockito.mock(CoinFlowConfig.class);
		net.runelite.client.config.ConfigManager configManager = org.mockito.Mockito.mock(net.runelite.client.config.ConfigManager.class);
		net.runelite.client.game.ItemManager itemManager = org.mockito.Mockito.mock(net.runelite.client.game.ItemManager.class);

		org.mockito.Mockito.when(config.displaySummary()).thenReturn(false);

		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(Collections.singletonMap(1, new CoinFlowSession.TrackedItem(1, "Coal", 10, 200L)));
		org.mockito.Mockito.when(plugin.getSession()).thenReturn(session);
		plugin.session = session;

		AtomicBoolean summaryOpened = new AtomicBoolean(false);
		CoinFlowPanel panel = new CoinFlowPanel(plugin, config, configManager, itemManager)
		{
			@Override
			void openSessionSummary(CoinFlowSession s)
			{
				summaryOpened.set(true);
			}

			@Override
			boolean confirmResetSession()
			{
				return false;
			}
		};
		panel.init();

		panel.onFinishSessionClicked();
		Assert.assertFalse("openSessionSummary should not be called on Finish Session when displaySummary is unchecked",
			summaryOpened.get());
	}

	@Test
	public void onFinishSessionClicked_whenDisplaySummaryEnabled_opensSummaryDialogForActiveSession()
	{
		CoinFlowPlugin plugin = org.mockito.Mockito.mock(CoinFlowPlugin.class);
		CoinFlowConfig config = org.mockito.Mockito.mock(CoinFlowConfig.class);
		net.runelite.client.config.ConfigManager configManager = org.mockito.Mockito.mock(net.runelite.client.config.ConfigManager.class);
		net.runelite.client.game.ItemManager itemManager = org.mockito.Mockito.mock(net.runelite.client.game.ItemManager.class);

		org.mockito.Mockito.when(config.displaySummary()).thenReturn(true);
		org.mockito.Mockito.when(config.trackSpent()).thenReturn(true);

		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(Collections.singletonMap(1, new CoinFlowSession.TrackedItem(1, "Coal", 10, 200L)));
		org.mockito.Mockito.when(plugin.getSession()).thenReturn(session);
		plugin.session = session;

		AtomicBoolean summaryOpened = new AtomicBoolean(false);
		CoinFlowPanel panel = new CoinFlowPanel(plugin, config, configManager, itemManager)
		{
			@Override
			void openSessionSummary(CoinFlowSession s)
			{
				summaryOpened.set(true);
			}
		};
		panel.init();

		panel.onFinishSessionClicked();
		Assert.assertTrue("openSessionSummary should be called on Finish Session when displaySummary is enabled and session has activity",
			summaryOpened.get());
	}
}
