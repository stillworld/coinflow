package com.coinflow;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.time.Duration;
import javax.inject.Inject;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.ProgressBarComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;

public class CoinFlowOverlay extends OverlayPanel
{
	private static final Color TITLE_COLOR = new Color(0xFF, 0xD7, 0x00); // Gold
	private static final Color PROFIT_COLOR = new Color(0x00, 0xFF, 0x80); // Green
	private static final Color LABEL_COLOR = Color.WHITE;
	private static final Color IDLE_COLOR = new Color(0xFF, 0xA5, 0x00);  // Orange
	private static final Color GOAL_COMPLETE_COLOR = new Color(24, 134, 52); // Rich forest green (#188634) with high contrast for white text
	private static final Color GOAL_IN_PROGRESS_COLOR = new Color(205, 110, 0); // Warm amber orange
	private static final Color LOSS_COLOR = new Color(0xE7, 0x4C, 0x3C);
	private static final Color PROGRESS_BAR_BG = new Color(0x28, 0x28, 0x28);

	private static final int MIN_PANEL_WIDTH = 140;
	private static final int PANEL_PADDING = 16;
	private static final int COLUMN_SPACING = 12;

	private final CoinFlowPlugin plugin;
	private final CoinFlowConfig config;

	@Inject
	public CoinFlowOverlay(CoinFlowPlugin plugin, CoinFlowConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(PRIORITY_LOW);
		panelComponent.setPreferredSize(new Dimension(MIN_PANEL_WIDTH, 0));
		getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, "Configure", "Coin Flow overlay"));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOverlay())
		{
			return null;
		}

		CoinFlowSession session = plugin.getSession();
		if (session == null)
		{
			return null;
		}

		boolean includeAfk = config.includeAfkTime();
		boolean isIdle = session.isIdle() && !includeAfk;

		FontMetrics fontMetrics = graphics.getFontMetrics();
		int maxLineWidth = MIN_PANEL_WIDTH;

		// ── Title ────────────────────────────────────────────────────
		String titleText = isIdle ? "Coin Flow (idle)" : "Coin Flow";
		maxLineWidth = Math.max(maxLineWidth, fontMetrics.stringWidth(titleText));
		panelComponent.getChildren().add(TitleComponent.builder()
			.text(titleText)
			.color(isIdle ? IDLE_COLOR : TITLE_COLOR)
			.build());

		// ── Profit & Supplies (Dynamic Unified Display) ──────────────
		long totalExpenses = session.getTotalExpenses();
		long totalProfit = session.getTotalProfit();
		String profitLabel = totalExpenses > 0 ? "Net Profit:" : "Profit:";
		String totalProfitRight = (totalProfit < 0 ? "-" : "") + formatGp(Math.abs(totalProfit)) + " gp";
		Color profitColor = totalProfit < 0 ? LOSS_COLOR : PROFIT_COLOR;

		maxLineWidth = Math.max(maxLineWidth, fontMetrics.stringWidth(profitLabel) + fontMetrics.stringWidth(totalProfitRight) + COLUMN_SPACING);
		panelComponent.getChildren().add(LineComponent.builder()
			.left(profitLabel)
			.leftColor(LABEL_COLOR)
			.right(totalProfitRight)
			.rightColor(profitColor)
			.build());

		if (totalExpenses > 0)
		{
			String spentLeft = "Spent:";
			String spentRight = "-" + formatGp(totalExpenses) + " gp";
			maxLineWidth = Math.max(maxLineWidth, fontMetrics.stringWidth(spentLeft) + fontMetrics.stringWidth(spentRight) + COLUMN_SPACING);
			panelComponent.getChildren().add(LineComponent.builder()
				.left(spentLeft)
				.leftColor(LABEL_COLOR)
				.right(spentRight)
				.rightColor(LOSS_COLOR)
				.build());
		}

		// ── GP/Hour ──────────────────────────────────────────────────
		long gpPerHour = session.getGpPerHour(includeAfk);
		String gpHrLeft = "GP/Hour:";
		String gpHrRight = (gpPerHour < 0 ? "-" : "") + formatGp(Math.abs(gpPerHour)) + " gp/hr";
		Color gpHrColor = isIdle ? IDLE_COLOR : (gpPerHour < 0 ? LOSS_COLOR : PROFIT_COLOR);
		maxLineWidth = Math.max(maxLineWidth, fontMetrics.stringWidth(gpHrLeft) + fontMetrics.stringWidth(gpHrRight) + COLUMN_SPACING);
		panelComponent.getChildren().add(LineComponent.builder()
			.left(gpHrLeft)
			.leftColor(LABEL_COLOR)
			.right(gpHrRight)
			.rightColor(gpHrColor)
			.build());

		// ── Active / Session Time ─────────────────────────────────────
		Duration displayTime = session.getTime(includeAfk);
		String timeLeft = "Time:";
		String timeRight = formatDuration(displayTime);
		maxLineWidth = Math.max(maxLineWidth, fontMetrics.stringWidth(timeLeft) + fontMetrics.stringWidth(timeRight) + COLUMN_SPACING);
		panelComponent.getChildren().add(LineComponent.builder()
			.left(timeLeft)
			.leftColor(LABEL_COLOR)
			.right(timeRight)
			.rightColor(isIdle ? IDLE_COLOR : LABEL_COLOR)
			.build());

		// ── Goal Progress Bar ─────────────────────────────────────────
		ProgressBarComponent progressBar = null;
		long goalAmount = CoinFlowSession.parseGoalAmount(config.goalAmount());
		if (config.showGoalOverlay() && goalAmount > 0)
		{
			double progress = session.getGoalProgress(goalAmount);
			boolean isGoalComplete = progress >= 1.0;
			long etaSeconds = session.getGoalEtaSeconds(goalAmount, includeAfk);
			String etaStr = CoinFlowSession.formatGoalEta(etaSeconds);

			String goalName = CoinFlowSession.cleanGoalName(config.goalName());
			String goalLabel = goalName.isEmpty() ? "Goal" : goalName;
			String percentStr = String.format("%.1f%%", progress * 100);

			String barCenterText = isGoalComplete
				? "Goal Reached! (100%)"
				: (goalLabel + " " + percentStr + " - " + etaStr);

			maxLineWidth = Math.max(maxLineWidth, fontMetrics.stringWidth(barCenterText) + 8);

			progressBar = new ProgressBarComponent();
			progressBar.setMinimum(0);
			progressBar.setMaximum(goalAmount);
			progressBar.setValue(Math.min((double) goalAmount, Math.max(0.0, (double) session.getTotalProfit())));
			progressBar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.TEXT_ONLY);
			progressBar.setCenterLabel(barCenterText);
			progressBar.setForegroundColor(isGoalComplete ? GOAL_COMPLETE_COLOR : GOAL_IN_PROGRESS_COLOR);
			progressBar.setBackgroundColor(PROGRESS_BAR_BG);
			progressBar.setFontColor(Color.WHITE);

			panelComponent.getChildren().add(progressBar);
		}

		// Set progress bar width to match the full panel width
		if (progressBar != null)
		{
			progressBar.setPreferredSize(new Dimension(maxLineWidth, 16));
		}

		// Dynamically size panel width to comfortably fit the widest line without awkward wrapping
		panelComponent.setPreferredSize(new Dimension(maxLineWidth + PANEL_PADDING, 0));

		return super.render(graphics);
	}

	/**
	 * Formats a GP value with abbreviations (e.g., 1.2M, 456K).
	 */
	private static String formatGp(long value)
	{
		return QuantityFormatter.quantityToStackSize(value);
	}

	/**
	 * Formats a duration as compact digital "mm:ss" or "h:mm:ss" to prevent wrapping.
	 */
	private static String formatDuration(Duration duration)
	{
		long totalSeconds = Math.max(0, duration.getSeconds());
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;

		if (hours > 0)
		{
			return String.format("%d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format("%02d:%02d", minutes, seconds);
	}
}
