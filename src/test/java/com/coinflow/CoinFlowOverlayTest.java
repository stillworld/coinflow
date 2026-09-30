package com.coinflow;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
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

		overlay.setClearChildren(false);
		Dimension result = overlay.render(graphics);
		Assert.assertNotNull(result);

		// With item breakdown moved from the overlay to the side panel,
		// the overlay only renders: Title, Total Profit, GP/Hour, and Time (4 children total without goal/supplies).
		// When item breakdown was in the overlay, it rendered 4 + 8 + 1 ("more...") = 13 children.
		int childCount = overlay.getPanelComponent().getChildren().size();
		Assert.assertEquals("Overlay must only contain session summary lines and no item breakdown rows", 4, childCount);
	}
}
