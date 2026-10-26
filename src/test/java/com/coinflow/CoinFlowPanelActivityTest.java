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
		feed(cls, ticks, 0, 0);
	}

	private void feed(ActivityTimeline.ActivityClass cls, int ticks, long gross, long expenses)
	{
		for (int i = 0; i < ticks; i++)
		{
			plugin.activityTimeline.onTick(cls, TICK, TICK, gross, expenses);
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
	public void defaultView_isTotals()
	{
		Assert.assertTrue(panel.timelineTotalsView);
	}

	@Test
	public void timelineView_listsEachStintNewestFirst()
	{
		panel.timelineTotalsView = false;
		panel.refreshTimelineCardView();
		Assert.assertEquals(4, rowCount());
	}

	@Test
	public void timelineView_showsCorrectionWithoutReplacingCurrentActivity()
	{
		plugin.activityTimeline.recordCorrection(-500, 0);
		panel.timelineTotalsView = false;
		panel.refreshTimelineCardView();

		Assert.assertEquals(5, rowCount());
		Assert.assertEquals("Session adjustment", nameOf(0).getText());
		Assert.assertEquals("Banking / GE", plugin.activityTimeline.getCurrentSegment().label);
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
		panel.timelineTotalsView = false;
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

	private JLabel nameOf(int row)
	{
		JPanel textPanel = (JPanel) ((JPanel) panel.timelineContainer.getComponent(row + 1)).getComponent(1);
		return (JLabel) textPanel.getComponent(0);
	}

	@Test
	public void zeroNetOtherSegment_hiddenInTimelineAndTotals()
	{
		plugin.activityTimeline.reset();
		// Unclassified bootstrap time closes as a zero-net "Other" row once
		// combat confirms — it has nothing to report, so both views hide it.
		feed(null, 30);
		feed(ActivityTimeline.ActivityClass.combat("Fire giant"), 30);
		panel.timelineTotalsView = false;
		panel.refreshTimelineCardView();
		Assert.assertEquals(1, rowCount());
		Assert.assertEquals("Fire giant", nameOf(0).getText());

		panel.timelineTotalsView = true;
		panel.refreshTimelineCardView();
		Assert.assertEquals(1, rowCount());
		Assert.assertEquals("Fire giant", nameOf(0).getText());
	}

	@Test
	public void otherSegment_showsOnceItCarriesGp()
	{
		plugin.activityTimeline.reset();
		feed(null, 10);
		plugin.activityTimeline.onTick(null, TICK, TICK, 5000, 0);
		feed(ActivityTimeline.ActivityClass.combat("Fire giant"), 30, 5000, 0);
		panel.timelineTotalsView = false;
		panel.refreshTimelineCardView();
		// Newest first: Fire giant, then the +5k "Other" row which carried a
		// value and stays visible.
		Assert.assertEquals(2, rowCount());
		Assert.assertEquals("Other", nameOf(1).getText());
	}

	@Test
	public void otherSegment_withExpensesOnly_isShown()
	{
		plugin.activityTimeline.reset();
		feed(null, 10);
		plugin.activityTimeline.onTick(null, TICK, TICK, 0, 3000);
		feed(ActivityTimeline.ActivityClass.combat("Fire giant"), 30, 0, 3000);
		panel.timelineTotalsView = false;
		panel.refreshTimelineCardView();
		// A -3k "Other" row is meaningful spend — shown in both views.
		Assert.assertEquals(2, rowCount());
		Assert.assertEquals("Other", nameOf(1).getText());

		panel.timelineTotalsView = true;
		panel.refreshTimelineCardView();
		Assert.assertEquals(2, rowCount());
	}

	@Test
	public void hiddenOtherRow_appearsWhenItGainsValue()
	{
		plugin.activityTimeline.reset();
		// A live zero-net bootstrap "Other" is the only segment — the card
		// falls back to the empty-state placeholder (one label, no rows).
		feed(null, 30);
		panel.timelineTotalsView = false;
		panel.refreshTimelineCardView();
		Assert.assertEquals(0, rowCount());

		// First gp lands mid-segment — no revision bump, so the per-tick
		// update detects the row-set change and rebuilds to reveal it.
		plugin.activityTimeline.onTick(null, TICK, TICK, 5000, 0);
		panel.updateLiveSegmentRow();
		Assert.assertEquals(1, rowCount());
		Assert.assertEquals("Other", nameOf(0).getText());
	}
}
