package com.coinflow;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import java.time.Duration;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CoinFlowOverlayTest
{
	private CoinFlowPlugin plugin;
	private CoinFlowConfig config;
	private CoinFlowOverlay overlay;
	private Graphics2D graphics;

	@Before
	public void setUp()
	{
		plugin = mock(CoinFlowPlugin.class);
		config = mock(CoinFlowConfig.class);
		overlay = new CoinFlowOverlay(plugin, config);

		BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
		graphics = img.createGraphics();

		when(config.showOverlay()).thenReturn(true);
		when(config.overlayStyle()).thenReturn(CoinFlowConfig.OverlayStyle.DETAILED);
		when(config.showOverlayBackground()).thenReturn(true);
		when(config.trackSpent()).thenReturn(true);
		when(config.showGoalOverlay()).thenReturn(false);
		when(config.goalAmount()).thenReturn("");
		when(config.goalName()).thenReturn("");
		when(config.includeAfkTime()).thenReturn(false);
		when(config.showItemBreakdown()).thenReturn(true);
	}

	@Test
	public void render_hiddenWhenShowOverlayFalse()
	{
		when(config.showOverlay()).thenReturn(false);
		when(plugin.getSession()).thenReturn(CoinFlowSession.createNew());

		Dimension result = overlay.render(graphics);
		Assert.assertNull("Overlay must not render when showOverlay is false", result);
	}

	@Test
	public void render_hiddenWhenSessionNull()
	{
		when(plugin.getSession()).thenReturn(null);

		Dimension result = overlay.render(graphics);
		Assert.assertNull("Overlay must not render when session is null", result);
	}

	@Test
	public void render_doesNotIncludeItemBreakdownInOverlay()
	{
		Map<Integer, CoinFlowSession.TrackedItem> gains = new HashMap<>();
		for (int i = 0; i < 10; i++)
		{
			gains.put(i + 1, new CoinFlowSession.TrackedItem(i + 1, "Item " + i, 10, 100L));
		}

		CoinFlowSession session = CoinFlowSession.createNew().withGains(gains);
		when(plugin.getSession()).thenReturn(session);
		when(config.trackSpent()).thenReturn(false);

		overlay.setClearChildren(false);
		Dimension result = overlay.render(graphics);
		Assert.assertNotNull(result);

		// With item breakdown moved from the overlay to the side panel,
		// the overlay only renders: Title, Total Profit, GP/Hour, and Time (4 children total without goal/supplies).
		// When item breakdown was in the overlay, it rendered 4 + 8 + 1 ("more...") = 13 children.
		int childCount = overlay.getPanelComponent().getChildren().size();
		Assert.assertEquals("Overlay must only contain session summary lines and no item breakdown rows", 4, childCount);
	}

	@Test
	public void render_trackSpentTrue_showsNetProfitAndSpentWhenExpensesPresent()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(Collections.singletonMap(1, new CoinFlowSession.TrackedItem(1, "Shark", 10, 1000L)))
			.withExpenses(Collections.singletonMap(2, new CoinFlowSession.TrackedItem(2, "Prayer potion(4)", 1, 2000L)));

		when(plugin.getSession()).thenReturn(session);
		when(config.trackSpent()).thenReturn(true);

		overlay.setClearChildren(false);
		Dimension result = overlay.render(graphics);
		Assert.assertNotNull(result);

		// When trackSpent is true and expenses > 0:
		// 5 children: Title, Net Profit, Spent, GP/Hour, Time
		Assert.assertEquals(5, overlay.getPanelComponent().getChildren().size());
	}

	@Test
	public void render_trackSpentFalse_showsGrossProfitAndHidesSpent()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(Collections.singletonMap(1, new CoinFlowSession.TrackedItem(1, "Shark", 10, 1000L)))
			.withExpenses(Collections.singletonMap(2, new CoinFlowSession.TrackedItem(2, "Prayer potion(4)", 1, 2000L)));

		when(plugin.getSession()).thenReturn(session);
		when(config.trackSpent()).thenReturn(false);

		overlay.setClearChildren(false);
		Dimension result = overlay.render(graphics);
		Assert.assertNotNull(result);

		// When trackSpent is false:
		// 4 children: Title, Profit, GP/Hour, Time (Spent row is omitted)
		Assert.assertEquals(4, overlay.getPanelComponent().getChildren().size());
	}

	@Test
	public void render_trackSpentTrue_zeroExpenses_showsNetProfitAndSpent()
	{
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGains(Collections.singletonMap(1, new CoinFlowSession.TrackedItem(1, "Shark", 10, 1000L)));

		when(plugin.getSession()).thenReturn(session);
		when(config.trackSpent()).thenReturn(true);

		overlay.setClearChildren(false);
		Dimension result = overlay.render(graphics);
		Assert.assertNotNull(result);

		// When trackSpent is true even with 0 expenses:
		// 5 children: Title, Net Profit, Spent, GP/Hour, Time
		Assert.assertEquals(5, overlay.getPanelComponent().getChildren().size());
	}

	@Test
	public void formatTitle_and_formatTimeRight_idleFormatting()
	{
		Assert.assertEquals("Coin Flow (idle)", CoinFlowOverlay.formatTitle(true));
		Assert.assertEquals("Coin Flow", CoinFlowOverlay.formatTitle(false));

		Duration d = Duration.ofMinutes(5).plusSeconds(30);
		// Not idle
		Assert.assertEquals("05:30", CoinFlowOverlay.formatTimeRight(d, false, false));
		Assert.assertEquals("05:30", CoinFlowOverlay.formatTimeRight(d, false, true));
		// Idle with includeAfk = false -> paused
		Assert.assertEquals("05:30 (paused)", CoinFlowOverlay.formatTimeRight(d, true, false));
		// Idle with includeAfk = true -> idle
		Assert.assertEquals("05:30 (idle)", CoinFlowOverlay.formatTimeRight(d, true, true));
	}

	@Test
	public void render_singleLine_rendersOneLineComponent()
	{
		when(config.overlayStyle()).thenReturn(CoinFlowConfig.OverlayStyle.SINGLE_LINE);
		when(plugin.getSession()).thenReturn(CoinFlowSession.createNew());

		overlay.setClearChildren(false);
		Dimension result = overlay.render(graphics);
		Assert.assertNotNull(result);
		Assert.assertEquals(1, overlay.getPanelComponent().getChildren().size());
		Assert.assertTrue(overlay.getPanelComponent().getChildren().get(0) instanceof LineComponent);
	}

	@Test
	public void render_singleLine_ignoresUserResizedPreferredSize()
	{
		when(config.overlayStyle()).thenReturn(CoinFlowConfig.OverlayStyle.SINGLE_LINE);
		CoinFlowSession session = CoinFlowSession.createNew();
		when(plugin.getSession()).thenReturn(session);

		// Simulate a saved Alt+drag resize narrower than the text; a stale saved
		// size must not force the line to wrap.
		overlay.setPreferredSize(new Dimension(40, 0));
		// PanelComponent returns the size measured on the previous pass, so render
		// once to populate it, then assert on the second render.
		overlay.render(graphics);
		Dimension result = overlay.render(graphics);
		Assert.assertNotNull(result);

		String expectedText = CoinFlowOverlay.formatCompactGpPerHour(session.getGpPerHour(false));
		Assert.assertEquals("Single-line overlay must size to the text, not the saved overlay size",
			graphics.getFontMetrics().stringWidth(expectedText) + 16, result.width);
		Assert.assertEquals("Single-line overlay must render exactly one line tall",
			graphics.getFontMetrics().getHeight() + 8, result.height);
	}

	@Test
	public void render_singleLine_disablesResize_detailedKeepsResizable()
	{
		when(plugin.getSession()).thenReturn(CoinFlowSession.createNew());

		when(config.overlayStyle()).thenReturn(CoinFlowConfig.OverlayStyle.SINGLE_LINE);
		overlay.render(graphics);
		Assert.assertFalse("Single-line mode must hide resize handles", overlay.isResizable());

		when(config.overlayStyle()).thenReturn(CoinFlowConfig.OverlayStyle.DETAILED);
		overlay.render(graphics);
		Assert.assertTrue("Detailed mode must stay resizable", overlay.isResizable());
	}

	@Test
	public void render_singleLine_hiddenWhenShowOverlayFalse()
	{
		when(config.overlayStyle()).thenReturn(CoinFlowConfig.OverlayStyle.SINGLE_LINE);
		when(config.showOverlay()).thenReturn(false);
		when(plugin.getSession()).thenReturn(CoinFlowSession.createNew());

		Assert.assertNull(overlay.render(graphics));
	}

	@Test
	public void render_singleLine_hiddenWhenSessionNull()
	{
		when(config.overlayStyle()).thenReturn(CoinFlowConfig.OverlayStyle.SINGLE_LINE);
		when(plugin.getSession()).thenReturn(null);

		Assert.assertNull(overlay.render(graphics));
	}

	@Test
	public void render_singleLine_idle_rendersOneLine()
	{
		when(config.overlayStyle()).thenReturn(CoinFlowConfig.OverlayStyle.SINGLE_LINE);
		when(plugin.getSession()).thenReturn(CoinFlowSession.createNew().tick(0));

		overlay.setClearChildren(false);
		Dimension result = overlay.render(graphics);
		Assert.assertNotNull(result);
		Assert.assertEquals(1, overlay.getPanelComponent().getChildren().size());
	}

	@Test
	public void formatCompactGpPerHour_formats()
	{
		Assert.assertEquals("0 gp/hr", CoinFlowOverlay.formatCompactGpPerHour(0));
		Assert.assertEquals("721 gp/hr", CoinFlowOverlay.formatCompactGpPerHour(721));
		Assert.assertEquals("-50 gp/hr", CoinFlowOverlay.formatCompactGpPerHour(-50));
		Assert.assertEquals("1.5M gp/hr", CoinFlowOverlay.formatCompactGpPerHour(1_500_000));
	}

	@Test
	public void render_backgroundToggle_controlsPanelBackground()
	{
		when(plugin.getSession()).thenReturn(CoinFlowSession.createNew());

		when(config.showOverlayBackground()).thenReturn(false);
		overlay.render(graphics);
		Assert.assertNull(overlay.getPanelComponent().getBackgroundColor());

		when(config.showOverlayBackground()).thenReturn(true);
		overlay.render(graphics);
		Assert.assertEquals(ComponentConstants.STANDARD_BACKGROUND_COLOR,
			overlay.getPanelComponent().getBackgroundColor());
	}

	@Test
	public void render_singleLine_backgroundDisabled_preferredColorNotApplied()
	{
		when(config.overlayStyle()).thenReturn(CoinFlowConfig.OverlayStyle.SINGLE_LINE);
		when(config.showOverlayBackground()).thenReturn(false);
		when(plugin.getSession()).thenReturn(CoinFlowSession.createNew());
		overlay.setPreferredColor(Color.RED);

		overlay.render(graphics);
		Assert.assertNull("Preferred color must not resurrect a disabled background",
			overlay.getPanelComponent().getBackgroundColor());
	}

	@Test
	public void render_whenIdle_rendersSuccessfully()
	{
		CoinFlowSession session = CoinFlowSession.createNew().tick(0);
		Assert.assertTrue(session.isIdle());

		when(plugin.getSession()).thenReturn(session);
		when(config.trackSpent()).thenReturn(false);
		when(config.includeAfkTime()).thenReturn(false);

		overlay.setClearChildren(false);
		Dimension result = overlay.render(graphics);
		Assert.assertNotNull(result);
		Assert.assertEquals(4, overlay.getPanelComponent().getChildren().size());
	}

	@Test
	public void render_whenIdleAndIncludeAfkTimeTrue_rendersSuccessfully()
	{
		CoinFlowSession session = CoinFlowSession.createNew().tick(0);
		Assert.assertTrue(session.isIdle());

		when(plugin.getSession()).thenReturn(session);
		when(config.trackSpent()).thenReturn(false);
		when(config.includeAfkTime()).thenReturn(true);

		overlay.setClearChildren(false);
		Dimension result = overlay.render(graphics);
		Assert.assertNotNull(result);
		Assert.assertEquals(4, overlay.getPanelComponent().getChildren().size());
	}
}
