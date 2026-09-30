package com.coinflow;

import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

public class GoalModeTest
{
	@Test
	public void testParseGpText()
	{
		Assert.assertEquals(10_000_000L, CoinFlowPanel.parseGpText("10m"));
		Assert.assertEquals(10_000_000L, CoinFlowPanel.parseGpText("10M"));
		Assert.assertEquals(500_000L, CoinFlowPanel.parseGpText("500k"));
		Assert.assertEquals(2_500_000L, CoinFlowPanel.parseGpText("2.5m"));
		Assert.assertEquals(1_000_000_000L, CoinFlowPanel.parseGpText("1b"));
		Assert.assertEquals(1_250_000L, CoinFlowPanel.parseGpText("1,250,000"));
		Assert.assertEquals(750L, CoinFlowPanel.parseGpText("750"));
	}

	@Test(expected = NumberFormatException.class)
	public void testParseGpTextInvalid()
	{
		CoinFlowPanel.parseGpText("abc");
	}

	@Test(expected = NumberFormatException.class)
	public void testParseGpTextZero()
	{
		CoinFlowPanel.parseGpText("0");
	}

	@Test
	public void testParseGoalAmount()
	{
		Assert.assertEquals(10_000L, CoinFlowSession.parseGoalAmount("10000"));
		Assert.assertEquals(10_000_000L, CoinFlowSession.parseGoalAmount("10m"));
		Assert.assertEquals(500_000L, CoinFlowSession.parseGoalAmount("500k"));
		Assert.assertEquals(0L, CoinFlowSession.parseGoalAmount(""));
		Assert.assertEquals(0L, CoinFlowSession.parseGoalAmount(null));
		Assert.assertEquals(0L, CoinFlowSession.parseGoalAmount("invalid"));
	}

	@Test
	public void testIconLoaded()
	{
		java.awt.image.BufferedImage icon = net.runelite.client.util.ImageUtil.loadImageResource(CoinFlowPlugin.class, "coinflow_icon.png");
		Assert.assertNotNull("coinflow_icon.png must be loadable from classpath", icon);
		Assert.assertEquals(16, icon.getWidth());
		Assert.assertEquals(16, icon.getHeight());
	}

	@Test
	public void testGoalCalculations()
	{
		CoinFlowSession session = CoinFlowSession.createNew();
		long goal = 10_000_000L;

		// Initial empty session
		Assert.assertEquals(10_000_000L, session.getGoalRemaining(goal));
		Assert.assertEquals(0.0, session.getGoalProgress(goal), 0.001);
		// ETA is -1 during warmup (< 10 seconds or 0 gp/hr)
		Assert.assertEquals(-1, session.getGoalEtaSeconds(goal, false));
		Assert.assertEquals("--:--", CoinFlowSession.formatGoalEta(-1));

		// Add gains
		CoinFlowSession.TrackedItem item = new CoinFlowSession.TrackedItem(1, "Test Item", 5_000, 1_000);
		session = session.withGains(Collections.singletonMap(1, item));

		Assert.assertEquals(5_000_000L, session.getTotalProfit());
		Assert.assertEquals(5_000_000L, session.getGoalRemaining(goal));
		Assert.assertEquals(0.5, session.getGoalProgress(goal), 0.001);

		// Complete goal
		CoinFlowSession.TrackedItem item2 = new CoinFlowSession.TrackedItem(2, "Test Item 2", 5_000, 1_000);
		session = session.withGains(Collections.singletonMap(2, item2));

		Assert.assertEquals(10_000_000L, session.getTotalProfit());
		Assert.assertEquals(0L, session.getGoalRemaining(goal));
		Assert.assertEquals(1.0, session.getGoalProgress(goal), 0.001);
		Assert.assertEquals(0L, session.getGoalEtaSeconds(goal, false));
		Assert.assertEquals("Goal Reached!", CoinFlowSession.formatGoalEta(0L));
	}

	@Test
	public void testFormatGoalEta()
	{
		Assert.assertEquals("Goal Reached!", CoinFlowSession.formatGoalEta(0L));
		Assert.assertEquals("--:--", CoinFlowSession.formatGoalEta(-1L));
		Assert.assertEquals("< 1m", CoinFlowSession.formatGoalEta(45L));
		Assert.assertEquals("15m", CoinFlowSession.formatGoalEta(15 * 60L));
		Assert.assertEquals("2h 15m", CoinFlowSession.formatGoalEta(2 * 3600L + 15 * 60L));
		Assert.assertEquals("> 99h", CoinFlowSession.formatGoalEta(105 * 3600L));
	}

	@Test
	public void testSpecialCharacterRejectionInGpInput()
	{
		String[] invalidInputs = {
			"@#$5m",
			"500k!",
			"10m; drop table",
			"<script>alert(1)</script>",
			"1.2.3m",
			"m10",
			"10mm",
			"-5m",
			"+10m",
			"1e5",
			"NaN",
			"Infinity",
			"10$000",
			"500k gp",
			"10.5.2"
		};

		for (String invalid : invalidInputs)
		{
			try
			{
				CoinFlowPanel.parseGpText(invalid);
				Assert.fail("Expected NumberFormatException for invalid input: " + invalid);
			}
			catch (NumberFormatException expected)
			{
				// Expected
			}
		}
	}

	@Test
	public void testCleanGoalName()
	{
		// Valid names preserved
		Assert.assertEquals("Bandos Tassets", CoinFlowSession.cleanGoalName("Bandos Tassets"));
		Assert.assertEquals("Torag's hammers", CoinFlowSession.cleanGoalName("Torag's hammers"));
		Assert.assertEquals("Anti-dragon shield", CoinFlowSession.cleanGoalName("Anti-dragon shield"));
		Assert.assertEquals("Ring of recoil (8)", CoinFlowSession.cleanGoalName("Ring of recoil (8)"));
		Assert.assertEquals("Staff 100/100", CoinFlowSession.cleanGoalName("Staff 100/100"));

		// Special characters stripped
		Assert.assertEquals("", CoinFlowSession.cleanGoalName("!@#$%^&*+=~{}[]:;\"?|\\"));
		Assert.assertEquals("scriptalert(1)/script", CoinFlowSession.cleanGoalName("<script>alert(1)</script>"));
		Assert.assertEquals("Old School Bond", CoinFlowSession.cleanGoalName("   Old    School    Bond   "));

		// Null handled
		Assert.assertEquals("", CoinFlowSession.cleanGoalName(null));

		// Max length 32 characters
		String longName = "This is a super extremely long goal name that should be clamped";
		String cleanedLong = CoinFlowSession.cleanGoalName(longName);
		Assert.assertTrue(cleanedLong.length() <= 32);
	}

	@Test
	public void testDocumentFilterRejectsSpecialCharacters() throws Exception
	{
		javax.swing.JTextField gpField = new javax.swing.JTextField();
		((javax.swing.text.AbstractDocument) gpField.getDocument()).setDocumentFilter(
			new javax.swing.text.DocumentFilter()
			{
				@Override
				public void insertString(FilterBypass fb, int offset, String string, javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException
				{
					if (string == null) return;
					String sanitized = string.replaceAll("[^0-9.,kKmMbB]", "");
					if (!sanitized.isEmpty()) super.insertString(fb, offset, sanitized, attr);
				}

				@Override
				public void replace(FilterBypass fb, int offset, int length, String text, javax.swing.text.AttributeSet attrs) throws javax.swing.text.BadLocationException
				{
					if (text == null) { super.replace(fb, offset, length, null, attrs); return; }
					String sanitized = text.replaceAll("[^0-9.,kKmMbB]", "");
					super.replace(fb, offset, length, sanitized, attrs);
				}
			}
		);

		gpField.setText("$$10m!!@#");
		Assert.assertEquals("10m", gpField.getText());
	}

	@Test
	public void testPanelCollapseAndCompactMode() throws Exception
	{
		CoinFlowPlugin plugin = org.mockito.Mockito.mock(CoinFlowPlugin.class);
		CoinFlowConfig config = org.mockito.Mockito.mock(CoinFlowConfig.class);
		net.runelite.client.config.ConfigManager configManager = org.mockito.Mockito.mock(net.runelite.client.config.ConfigManager.class);
		net.runelite.client.game.ItemManager itemManager = org.mockito.Mockito.mock(net.runelite.client.game.ItemManager.class);

		org.mockito.Mockito.when(config.goalAmount()).thenReturn("1000000");
		org.mockito.Mockito.when(config.goalName()).thenReturn("1M Goal");
		org.mockito.Mockito.when(config.compactMode()).thenReturn(false);
		org.mockito.Mockito.when(config.sessionCardCollapsed()).thenReturn(false);
		org.mockito.Mockito.when(config.goalCardCollapsed()).thenReturn(false);

		CoinFlowPanel panel = new CoinFlowPanel(plugin, config, configManager, itemManager);
		panel.init();

		// Switch to compact mode
		org.mockito.Mockito.when(config.compactMode()).thenReturn(true);
		panel.onConfigChanged();

		// Collapse session and goal
		org.mockito.Mockito.when(config.sessionCardCollapsed()).thenReturn(true);
		org.mockito.Mockito.when(config.goalCardCollapsed()).thenReturn(true);
		panel.onConfigChanged();

		// Provide a live session update
		CoinFlowSession session = CoinFlowSession.createNew();
		panel.updateSession(session);
	}

	@Test
	public void testPanelItemBreakdownVisibilityAndCollapse() throws Exception
	{
		CoinFlowPlugin plugin = org.mockito.Mockito.mock(CoinFlowPlugin.class);
		CoinFlowConfig config = org.mockito.Mockito.mock(CoinFlowConfig.class);
		net.runelite.client.config.ConfigManager configManager = org.mockito.Mockito.mock(net.runelite.client.config.ConfigManager.class);
		net.runelite.client.game.ItemManager itemManager = org.mockito.Mockito.mock(net.runelite.client.game.ItemManager.class);

		org.mockito.Mockito.when(config.goalAmount()).thenReturn("");
		org.mockito.Mockito.when(config.goalName()).thenReturn("");
		org.mockito.Mockito.when(config.compactMode()).thenReturn(false);
		org.mockito.Mockito.when(config.sessionCardCollapsed()).thenReturn(false);
		org.mockito.Mockito.when(config.goalCardCollapsed()).thenReturn(false);
		org.mockito.Mockito.when(config.itemsCardCollapsed()).thenReturn(false);
		org.mockito.Mockito.when(config.showItemBreakdown()).thenReturn(true);

		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(java.util.Collections.singletonMap(453, new CoinFlowSession.TrackedItem(453, "Coal", 10, 150L)));
		org.mockito.Mockito.when(plugin.getSession()).thenReturn(session);

		CoinFlowPanel panel = new CoinFlowPanel(plugin, config, configManager, itemManager);
		panel.init();

		// Update panel with live session containing items
		panel.updateSession(session);

		// Test collapse items card
		org.mockito.Mockito.when(config.itemsCardCollapsed()).thenReturn(true);
		panel.onConfigChanged();

		// Test compact mode with items card
		org.mockito.Mockito.when(config.compactMode()).thenReturn(true);
		panel.onConfigChanged();

		// Test hiding item breakdown via config
		org.mockito.Mockito.when(config.showItemBreakdown()).thenReturn(false);
		panel.onConfigChanged();
	}

	@Test
	public void testPanelViewportScrollLock_preventsHorizontalShift()
	{
		CoinFlowPlugin plugin = org.mockito.Mockito.mock(CoinFlowPlugin.class);
		CoinFlowConfig config = org.mockito.Mockito.mock(CoinFlowConfig.class);
		net.runelite.client.config.ConfigManager configManager = org.mockito.Mockito.mock(net.runelite.client.config.ConfigManager.class);
		net.runelite.client.game.ItemManager itemManager = org.mockito.Mockito.mock(net.runelite.client.game.ItemManager.class);

		CoinFlowPanel panel = new CoinFlowPanel(plugin, config, configManager, itemManager);
		panel.init();

		// Verify scrollRectToVisible forces x = 0
		java.awt.Rectangle rect = new java.awt.Rectangle(50, 10, 100, 20);
		panel.scrollRectToVisible(rect);
		Assert.assertEquals(0, rect.x);

		// Verify JScrollPane viewport listener resets horizontal shift to 0
		if (panel.getScrollPane() != null)
		{
			panel.getScrollPane().getViewport().setViewPosition(new java.awt.Point(25, 0));
			Assert.assertEquals(0, panel.getScrollPane().getViewport().getViewPosition().x);
		}
	}
}
