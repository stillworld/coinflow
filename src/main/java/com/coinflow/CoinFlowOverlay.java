package com.coinflow;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.time.Duration;
import javax.inject.Inject;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.ProgressBarComponent;
import net.runelite.client.ui.overlay.components.TextComponent;
import net.runelite.client.util.QuantityFormatter;

public class CoinFlowOverlay extends OverlayPanel
{
	static final String OVERLAY_TARGET = "Coin Flow overlay";
	static final String MENU_VIEW_SUMMARY = "View Summary";
	static final String MENU_RESET = "Reset";

	private static final Color TITLE_COLOR = new Color(0xFF, 0xD7, 0x00); // Gold
	private static final Color PROFIT_COLOR = new Color(0x00, 0xFF, 0x80); // Green
	private static final Color LABEL_COLOR = Color.WHITE;
	private static final Color IDLE_COLOR = new Color(0xFF, 0xA5, 0x00);  // Orange
	private static final Color GOAL_COMPLETE_COLOR = new Color(24, 134, 52); // Rich forest green (#188634) with high contrast for white text
	private static final Color GOAL_IN_PROGRESS_COLOR = new Color(205, 110, 0); // Warm amber orange
	private static final Color LOSS_COLOR = new Color(0xE7, 0x4C, 0x3C);
	private static final Color PROGRESS_BAR_BG = new Color(0x28, 0x28, 0x28);

	private static final int MIN_PANEL_WIDTH = 140;
	// Hard character cap for the goal name on the bar's left edge — it feeds
	// maxLineWidth, so an unbounded name would stretch the whole overlay.
	private static final int GOAL_BAR_LABEL_MAX_CHARS = 20;
	// Compact mode tightens the cap to ~10 chars so the label can't crowd out
	// the right-side progress text in the narrower panel.
	private static final int GOAL_BAR_LABEL_MAX_COMPACT = 10;
	private static final int PANEL_PADDING = 16;
	private static final int COLUMN_SPACING = 12;
	private static final int COMPACT_MIN_PANEL_WIDTH = 110;
	private static final int COMPACT_PANEL_PADDING = 8;
	private static final int COMPACT_COLUMN_SPACING = 8;
	private static final Rectangle STANDARD_PANEL_BORDER = new Rectangle(
		ComponentConstants.STANDARD_BORDER, ComponentConstants.STANDARD_BORDER,
		ComponentConstants.STANDARD_BORDER, ComponentConstants.STANDARD_BORDER);
	private static final Rectangle COMPACT_PANEL_BORDER = new Rectangle(2, 2, 2, 2);

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
		getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, "Configure", OVERLAY_TARGET));
		getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, MENU_VIEW_SUMMARY, OVERLAY_TARGET));
		getMenuEntries().add(new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY, MENU_RESET, OVERLAY_TARGET));
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
		boolean isIdle = session.isIdle();

		// Use the standard color (not a copy) when the background is enabled so
		// OverlayPanel's preferred-color substitution (the global "Overlay Color"
		// setting) still applies; null keeps the panel fully transparent.
		panelComponent.setBackgroundColor(config.showOverlayBackground()
			? ComponentConstants.STANDARD_BACKGROUND_COLOR
			: null);

		boolean compact = config.compactMode();
		panelComponent.setBorder(compact ? COMPACT_PANEL_BORDER : STANDARD_PANEL_BORDER);

		Font defaultFont = graphics.getFont();
		if (compact)
		{
			// runescape_small is a distinct font designed for compact text; shrinking the
			// interface font to a fractional size rasterizes the bitmap-style font poorly
			graphics.setFont(FontManager.getRunescapeSmallFont());
		}

		try
		{
			boolean singleLine = config.overlayStyle() != CoinFlowConfig.OverlayStyle.DETAILED;
			// A resized overlay's saved size is ignored in single-line mode, so hide the resize handles.
			setResizable(!singleLine);
			if (singleLine)
			{
				return renderSingleLine(graphics, session, isIdle, includeAfk, compact);
			}
			return renderDetailed(graphics, session, isIdle, includeAfk, compact);
		}
		finally
		{
			graphics.setFont(defaultFont);
		}
	}

	/**
	 * Bounds the goal name shown on the bar's left edge. Percent and ETA are
	 * placed separately, so only the name itself needs a budget — a name that
	 * fits keeps its full text. Compact mode truncates to ~10 chars with
	 * "..." (vs ".." in the standard layout).
	 */
	static String goalBarName(String name)
	{
		return goalBarName(name, false);
	}

	static String goalBarName(String name, boolean compact)
	{
		if (compact)
		{
			return name.length() <= GOAL_BAR_LABEL_MAX_COMPACT
				? name
				: name.substring(0, GOAL_BAR_LABEL_MAX_COMPACT - 3) + "...";
		}
		return name.length() <= GOAL_BAR_LABEL_MAX_CHARS
			? name
			: name.substring(0, GOAL_BAR_LABEL_MAX_CHARS - 2) + "..";
	}

	/**
	 * Renders the multi-line "Detailed" display (profit, spent, GP/hr, time, goal bar).
	 */
	private Dimension renderDetailed(Graphics2D graphics, CoinFlowSession session, boolean isIdle, boolean includeAfk, boolean compact)
	{
		int minWidth = compact ? COMPACT_MIN_PANEL_WIDTH : MIN_PANEL_WIDTH;
		int padding = compact ? COMPACT_PANEL_PADDING : PANEL_PADDING;
		int columnSpacing = compact ? COMPACT_COLUMN_SPACING : COLUMN_SPACING;

		FontMetrics fontMetrics = graphics.getFontMetrics();
		int maxLineWidth = minWidth;

		// ── Title ────────────────────────────────────────────────────
		if (!compact)
		{
			String titleText = formatTitle(isIdle);
			// runescape_small keeps the header smaller than the body text;
			// TitleComponent can't take a font, so FontTitleComponent does.
			Font titleFont = FontManager.getRunescapeSmallFont();
			maxLineWidth = Math.max(maxLineWidth,
				graphics.getFontMetrics(titleFont).stringWidth(titleText));
			panelComponent.getChildren().add(
				new FontTitleComponent(titleText, isIdle ? IDLE_COLOR : TITLE_COLOR, titleFont));
		}

		// ── Profit & Supplies (Dynamic Unified Display) ──────────────
		boolean trackSpent = config.trackSpent();
		long totalExpenses = trackSpent ? session.getTotalExpenses() : 0L;
		long totalProfit = trackSpent ? session.getTotalProfit() : session.getGrossProfit();
		String profitLabel = trackSpent ? "Net Profit:" : "Profit:";
		String totalProfitRight = (totalProfit < 0 ? "-" : "") + formatGp(Math.abs(totalProfit)) + " gp";
		Color profitColor = totalProfit < 0 ? LOSS_COLOR : PROFIT_COLOR;

		maxLineWidth = Math.max(maxLineWidth, fontMetrics.stringWidth(profitLabel) + fontMetrics.stringWidth(totalProfitRight) + columnSpacing);
		panelComponent.getChildren().add(LineComponent.builder()
			.left(profitLabel)
			.leftColor(LABEL_COLOR)
			.right(totalProfitRight)
			.rightColor(profitColor)
			.build());

		if (trackSpent)
		{
			String spentLeft = "Spent:";
			String spentRight = (totalExpenses > 0 ? "-" : "") + formatGp(totalExpenses) + " gp";
			Color spentColor = totalExpenses > 0 ? LOSS_COLOR : LABEL_COLOR;
			maxLineWidth = Math.max(maxLineWidth, fontMetrics.stringWidth(spentLeft) + fontMetrics.stringWidth(spentRight) + columnSpacing);
			panelComponent.getChildren().add(LineComponent.builder()
				.left(spentLeft)
				.leftColor(LABEL_COLOR)
				.right(spentRight)
				.rightColor(spentColor)
				.build());
		}

		// ── GP/Hour ──────────────────────────────────────────────────
		long gpPerHour = trackSpent ? session.getGpPerHour(includeAfk) : session.getGrossGpPerHour(includeAfk);
		String gpHrLeft = "GP/Hour:";
		String gpHrRight = (gpPerHour < 0 ? "-" : "") + formatGp(Math.abs(gpPerHour)) + " gp/hr";
		Color gpHrColor = isIdle ? IDLE_COLOR : (gpPerHour < 0 ? LOSS_COLOR : PROFIT_COLOR);
		maxLineWidth = Math.max(maxLineWidth, fontMetrics.stringWidth(gpHrLeft) + fontMetrics.stringWidth(gpHrRight) + columnSpacing);
		panelComponent.getChildren().add(LineComponent.builder()
			.left(gpHrLeft)
			.leftColor(LABEL_COLOR)
			.right(gpHrRight)
			.rightColor(gpHrColor)
			.build());

		// ── Active / Session Time ─────────────────────────────────────
		Duration displayTime = session.getTime(includeAfk);
		String timeLeft = "Time:";
		String timeRight = formatTimeRight(displayTime, isIdle, includeAfk);
		maxLineWidth = Math.max(maxLineWidth, fontMetrics.stringWidth(timeLeft) + fontMetrics.stringWidth(timeRight) + columnSpacing);
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
			// goalDisplayTotal folds persisted cross-session progress into the
			// live session contribution (and returns the full target when the
			// goal is marked complete).
			long goalTotal = plugin.goalDisplayTotal();
			double progress = session.getGoalProgress(goalAmount, goalTotal);
			boolean isGoalComplete = progress >= 1.0;
			// Blended cross-session rate: persisted progress/time folded in, so
			// a fresh login or a losing stretch can't spike or blank the ETA.
			long etaSeconds = session.getGoalEtaSeconds(goalAmount, goalTotal, plugin.goalDisplaySeconds());
			String etaStr = CoinFlowSession.formatGoalEta(etaSeconds);

			String goalName = CoinFlowSession.cleanGoalName(config.goalName());
			String goalLabel = goalBarName(goalName.isEmpty() ? "Goal" : goalName, compact);
			String percentStr = String.format("%.1f%%", progress * 100);

			progressBar = new ProgressBarComponent();
			progressBar.setMinimum(0);
			progressBar.setMaximum(goalAmount);
			progressBar.setValue(Math.min((double) goalAmount, Math.max(0.0, (double) goalTotal)));

			int barTextWidth;
			if (isGoalComplete)
			{
				progressBar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.TEXT_ONLY);
				progressBar.setCenterLabel("Goal Reached! (100%)");
				barTextWidth = fontMetrics.stringWidth("Goal Reached! (100%)");
			}
			else
			{
				// Name left, "1.4% - 4:32" right — edge labels never overlap,
				// unlike a centered percent which paints over a long name.
				String barRight = percentStr + " - " + etaStr;
				progressBar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.TEXT_ONLY);
				progressBar.setLeftLabel(goalLabel);
				progressBar.setRightLabel(barRight);
				barTextWidth = fontMetrics.stringWidth(goalLabel)
					+ fontMetrics.stringWidth(barRight) + 16; // 4px side offsets + gap
			}
			maxLineWidth = Math.max(maxLineWidth, barTextWidth + 8);

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
		panelComponent.setPreferredSize(new Dimension(maxLineWidth + padding, 0));

		return super.render(graphics);
	}

	/**
	 * Renders the compact single-line "GP/hr" display.
	 *
	 * Renders {@link #panelComponent} directly instead of via {@code super.render()} so a
	 * saved overlay resize ({@code getPreferredSize()}) cannot force a stale width that
	 * would stretch the background or wrap the line. Mirrors OverlayPanel's
	 * preferred-color swap so the global "Overlay Color" setting still applies.
	 */
	private Dimension renderSingleLine(Graphics2D graphics, CoinFlowSession session, boolean isIdle, boolean includeAfk, boolean compact)
	{
		String text;
		Color color;
		if (config.overlayStyle() == CoinFlowConfig.OverlayStyle.SINGLE_LINE_NET)
		{
			long net = config.trackSpent() ? session.getTotalProfit() : session.getGrossProfit();
			text = formatCompactNetProfit(net);
			color = net < 0 ? LOSS_COLOR : PROFIT_COLOR;
		}
		else
		{
			long gpPerHour = config.trackSpent() ? session.getGpPerHour(includeAfk) : session.getGrossGpPerHour(includeAfk);
			text = formatCompactGpPerHour(gpPerHour);
			color = isIdle ? IDLE_COLOR : (gpPerHour < 0 ? LOSS_COLOR : PROFIT_COLOR);
		}

		FontMetrics fontMetrics = graphics.getFontMetrics();
		panelComponent.setPreferredSize(new Dimension(fontMetrics.stringWidth(text) + (compact ? COMPACT_PANEL_PADDING : PANEL_PADDING), 0));
		panelComponent.getChildren().add(LineComponent.builder()
			.left(text)
			.leftColor(color)
			.build());

		Color background = panelComponent.getBackgroundColor();
		if (background != null && getPreferredColor() != null)
		{
			panelComponent.setBackgroundColor(getPreferredColor());
		}

		try
		{
			return panelComponent.render(graphics);
		}
		finally
		{
			if (isClearChildren())
			{
				panelComponent.getChildren().clear();
			}
			panelComponent.setBackgroundColor(background);
		}
	}

	/**
	 * Formats the single-line overlay text, e.g. "721 gp/hr" or "-50 gp/hr".
	 */
	static String formatCompactGpPerHour(long gpPerHour)
	{
		return (gpPerHour < 0 ? "-" : "") + formatGp(Math.abs(gpPerHour)) + " gp/hr";
	}

	/**
	 * Formats the single-line net-profit text, e.g. "1.2M gp" or "-50 gp".
	 */
	static String formatCompactNetProfit(long net)
	{
		return (net < 0 ? "-" : "") + formatGp(Math.abs(net)) + " gp";
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

	/**
	 * Formats the overlay title text based on idle state.
	 */
	static String formatTitle(boolean isIdle)
	{
		return isIdle ? "Coin Flow (idle)" : "Coin Flow";
	}

	/**
	 * Formats the right-side Time text in the overlay based on idle and includeAfk configuration.
	 */
	static String formatTimeRight(Duration displayTime, boolean isIdle, boolean includeAfk)
	{
		return formatDuration(displayTime) + (isIdle ? (includeAfk ? " (idle)" : " (paused)") : "");
	}

	/**
	 * A left-aligned title row rendered in its own font — TitleComponent
	 * doesn't expose one (and centers anyway), but TextComponent does, so
	 * the header can run smaller than the body text and sit flush left.
	 * Row height comes from the rendered text, same as TitleComponent.
	 */
	private static final class FontTitleComponent implements LayoutableRenderableEntity
	{
		private final TextComponent text = new TextComponent();
		private final String title;
		private final Font font;
		private final Rectangle bounds = new Rectangle();
		private Point preferredLocation = new Point();
		private Dimension preferredSize = new Dimension(ComponentConstants.STANDARD_WIDTH, 0);

		FontTitleComponent(String title, Color color, Font font)
		{
			this.title = title;
			this.font = font;
			text.setText(title);
			text.setColor(color);
			text.setFont(font);
		}

		@Override
		public Dimension render(Graphics2D graphics)
		{
			// Measure with the title's own font, not the render font.
			FontMetrics metrics = graphics.getFontMetrics(font);
			text.setPosition(preferredLocation.x, preferredLocation.y + metrics.getHeight());
			Dimension rendered = text.render(graphics);
			Dimension size = new Dimension(preferredSize.width, rendered.height);
			bounds.setLocation(preferredLocation);
			bounds.setSize(size);
			return size;
		}

		@Override
		public Rectangle getBounds()
		{
			return bounds;
		}

		@Override
		public void setPreferredLocation(Point position)
		{
			this.preferredLocation = position;
		}

		@Override
		public void setPreferredSize(Dimension dimension)
		{
			this.preferredSize = dimension;
		}
	}
}
