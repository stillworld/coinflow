package com.coinflow;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.time.Duration;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CoinFlowPanelActivityTest
{
	private static final Duration TICK = Duration.ofMillis(600);

	private CoinFlowPlugin plugin;
	private CoinFlowPanel panel;

	@Before
	public void setUp()
	{
		plugin = new CoinFlowPlugin();
		plugin.session = CoinFlowSession.createNew();
		plugin.skillIconManager = mock(SkillIconManager.class);
		when(plugin.skillIconManager.getSkillImage(any(Skill.class), anyBoolean()))
			.thenReturn(new BufferedImage(17, 17, BufferedImage.TYPE_INT_ARGB));

		CoinFlowConfig config = mock(CoinFlowConfig.class);
		when(config.showTimeline()).thenReturn(true);
		when(config.trackSpent()).thenReturn(true);
		panel = new CoinFlowPanel(plugin, config, mock(ConfigManager.class), mock(ItemManager.class));

		// Fire giant -> Mining -> Fire giant -> Banking (a bank gap between
		// two Fire giant stints would fold back into the run — use Mining as
		// the separator so the stints stay distinct for these assertions)
		feed(ActivityTimeline.ActivityClass.combat("Fire giant"), 30);
		feed(ActivityTimeline.ActivityClass.skilling("Mining"), 30);
		feed(ActivityTimeline.ActivityClass.combat("Fire giant"), 30);
		feed(ActivityTimeline.ActivityClass.BANKING, 30);
	}

	private void feed(ActivityTimeline.ActivityClass cls, int ticks)
	{
		for (int i = 0; i < ticks; i++)
		{
			plugin.activityTimeline.onTick(cls, TICK, TICK, 0, 0);
		}
	}

	/** Rows after the Timeline/Totals view bar (component 0). */
	private int rowCount()
	{
		return panel.timelineContainer.getComponentCount() - 1;
	}

	private JLabel iconOf(int row)
	{
		return (JLabel) ((JPanel) panel.timelineContainer.getComponent(row + 1)).getComponent(0);
	}

	@Test
	public void timelineView_listsEachStintNewestFirst()
	{
		panel.refreshTimelineCardView();
		Assert.assertEquals(4, rowCount());
	}

	@Test
	public void totalsView_collapsesRepeatedActivities()
	{
		panel.timelineTotalsView = true;
		panel.refreshTimelineCardView();
		// Fire giant x2 merge -> Fire giant, Mining, Banking
		Assert.assertEquals(3, rowCount());
	}

	@Test
	public void rows_showSkillIconsForSkillingAndCombat_noneForBanking()
	{
		panel.refreshTimelineCardView();
		// Newest first: Banking, Fire giant, Mining, Fire giant
		Assert.assertNull("banking row has no icon", iconOf(0).getIcon());
		Assert.assertNotNull("combat row shows the attack icon", iconOf(1).getIcon());
		Assert.assertNotNull("skilling row shows its skill icon", iconOf(2).getIcon());
	}

	@Test
	public void rows_renderWithoutSkillIconManager()
	{
		plugin.skillIconManager = null;
		panel.refreshTimelineCardView();
		for (int i = 0; i < rowCount(); i++)
		{
			Component c = iconOf(i);
			Assert.assertNull(((JLabel) c).getIcon());
		}
	}
}
