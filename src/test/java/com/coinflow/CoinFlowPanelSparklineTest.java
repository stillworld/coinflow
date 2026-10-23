package com.coinflow;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the optional Session Overview sparkline: config gating, compact
 * exclusion, live toggle, session-reset clearing, and paint robustness.
 */
public class CoinFlowPanelSparklineTest
{
	private CoinFlowPlugin plugin;
	private CoinFlowConfig config;
	private CoinFlowPanel panel;

	@Before
	public void setUp()
	{
		plugin = new CoinFlowPlugin();
		plugin.session = CoinFlowSession.createNew();
		config = mock(CoinFlowConfig.class);
		when(config.trackSpent()).thenReturn(true);
		panel = new CoinFlowPanel(plugin, config, mock(ConfigManager.class), mock(ItemManager.class));
	}

	private boolean sparklineInCard()
	{
		return contains(panel.sessionCard, panel.sessionSparkline);
	}

	private static boolean contains(Component root, Component target)
	{
		if (root == target)
		{
			return true;
		}
		if (root instanceof java.awt.Container)
		{
			for (Component child : ((java.awt.Container) root).getComponents())
			{
				if (contains(child, target))
				{
					return true;
				}
			}
		}
		return false;
	}

	@Test
	public void hiddenByDefault()
	{
		// Mock default: showSparkline() == false
		panel.refreshSessionCardView();
		Assert.assertFalse(sparklineInCard());
	}

	@Test
	public void shownInStandardLayoutWhenEnabled()
	{
		when(config.showSparkline()).thenReturn(true);
		panel.refreshSessionCardView();
		Assert.assertTrue(sparklineInCard());
	}

	@Test
	public void hiddenInCompactModeEvenWhenEnabled()
	{
		when(config.showSparkline()).thenReturn(true);
		when(config.compactMode()).thenReturn(true);
		panel.refreshSessionCardView();
		Assert.assertFalse(sparklineInCard());
	}

	@Test
	public void hiddenWhenCardCollapsed()
	{
		when(config.showSparkline()).thenReturn(true);
		when(config.sessionCardCollapsed()).thenReturn(true);
		panel.refreshSessionCardView();
		Assert.assertFalse(sparklineInCard());
	}

	@Test
	public void toggleOff_removesOnRebuild()
	{
		when(config.showSparkline()).thenReturn(true);
		panel.refreshSessionCardView();
		Assert.assertTrue(sparklineInCard());

		when(config.showSparkline()).thenReturn(false);
		panel.refreshSessionCardView();
		Assert.assertFalse(sparklineInCard());
	}

	@Test
	public void resetSession_clearsHistory()
	{
		plugin.profitHistory.onTick(500);
		Assert.assertFalse(plugin.profitHistory.snapshot().isEmpty());

		// clientThread is null on the bare plugin → resetSession runs inline.
		plugin.resetSession();

		Assert.assertTrue(plugin.profitHistory.snapshot().isEmpty());
	}

	@Test
	public void setSnapshot_onlyAdoptsNewRevisions()
	{
		CoinFlowPanel.SparklinePanel spark = new CoinFlowPanel.SparklinePanel();
		ProfitHistory history = new ProfitHistory();
		history.onTick(1);
		ProfitHistory.Snapshot first = history.snapshot();
		spark.setSnapshot(first);
		Assert.assertSame(first, spark.getSnapshot());

		// Same revision → ignored; new revision → adopted.
		spark.setSnapshot(first);
		Assert.assertSame(first, spark.getSnapshot());
		for (int i = 0; i < ProfitHistory.BASE_INTERVAL_TICKS; i++)
		{
			history.onTick(2);
		}
		spark.setSnapshot(history.snapshot());
		Assert.assertEquals(2, spark.getSnapshot().size());
	}

	@Test
	public void paint_handlesEdgeCasesWithoutError()
	{
		CoinFlowPanel.SparklinePanel spark = new CoinFlowPanel.SparklinePanel();
		spark.setSize(120, CoinFlowPanel.SparklinePanel.HEIGHT);

		paint(spark); // empty

		ProfitHistory one = new ProfitHistory();
		one.onTick(5);
		spark.setSnapshot(one.snapshot());
		paint(spark); // single point

		ProfitHistory flat = new ProfitHistory();
		flat.onTick(100);
		for (int i = 0; i < 3 * ProfitHistory.BASE_INTERVAL_TICKS; i++)
		{
			flat.onTick(100);
		}
		spark.setSnapshot(flat.snapshot());
		Assert.assertEquals(flat.snapshot().min, flat.snapshot().max);
		paint(spark); // flat series (range 0)

		ProfitHistory crossing = new ProfitHistory();
		crossing.onTick(-300);
		for (int i = 0; i < ProfitHistory.BASE_INTERVAL_TICKS; i++)
		{
			crossing.onTick(200);
		}
		for (int i = 0; i < ProfitHistory.BASE_INTERVAL_TICKS; i++)
		{
			crossing.onTick(900);
		}
		spark.setSnapshot(crossing.snapshot());
		paint(spark); // crosses zero → baseline drawn

		spark.setSize(0, 0);
		paint(spark); // degenerate size
	}

	private static void paint(CoinFlowPanel.SparklinePanel spark)
	{
		BufferedImage img = new BufferedImage(
			Math.max(1, spark.getWidth()), Math.max(1, spark.getHeight()), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try
		{
			spark.paint(g);
		}
		finally
		{
			g.dispose();
		}
	}
}
