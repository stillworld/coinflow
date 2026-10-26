package com.coinflow;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.time.Duration;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Modal dialog presenting the "Executive Summary" Dashboard and Bankstanding Audit
 * when finishing or reviewing an active Coin Flow session.
 */
public class SessionSummaryDialog extends JDialog
{
	private static final Color PROFIT_GREEN = new Color(0x00, 0xC8, 0x53);
	private static final Color LOSS_RED = new Color(0xFF, 0x52, 0x52);
	private static final Color ACCENT_GOLD = new Color(0xFF, 0xD7, 0x00);
	private static final Color DOWNTIME_ORANGE = new Color(0xFF, 0x98, 0x00);

	public static void showDialog(Component parent, CoinFlowSession session, Runnable onFinishAndReset)
	{
		showDialog(parent, session, true, null, false, onFinishAndReset);
	}

	public static void showDialog(Component parent, CoinFlowSession session, boolean trackSpent, Runnable onFinishAndReset)
	{
		showDialog(parent, session, trackSpent, null, false, onFinishAndReset);
	}

	public static void showDialog(Component parent, CoinFlowSession session, boolean trackSpent,
		ActivityTimeline timeline, boolean includeAfk, Runnable onFinishAndReset)
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}

		SwingUtilities.invokeLater(() ->
		{
			Window window = SwingUtilities.getWindowAncestor(parent);
			SessionSummaryDialog dialog;
			if (window instanceof Frame)
			{
				dialog = new SessionSummaryDialog((Frame) window, session, trackSpent, timeline, includeAfk, onFinishAndReset);
			}
			else if (window instanceof Dialog)
			{
				dialog = new SessionSummaryDialog((Dialog) window, session, trackSpent, timeline, includeAfk, onFinishAndReset);
			}
			else
			{
				dialog = new SessionSummaryDialog((Frame) null, session, trackSpent, timeline, includeAfk, onFinishAndReset);
			}
			dialog.setVisible(true);
		});
	}

	public SessionSummaryDialog(Frame owner, CoinFlowSession session, Runnable onFinishAndReset)
	{
		this(owner, session, true, onFinishAndReset);
	}

	public SessionSummaryDialog(Dialog owner, CoinFlowSession session, Runnable onFinishAndReset)
	{
		this(owner, session, true, onFinishAndReset);
	}

	public SessionSummaryDialog(Frame owner, CoinFlowSession session, boolean trackSpent, Runnable onFinishAndReset)
	{
		this(owner, session, trackSpent, null, false, onFinishAndReset);
	}

	public SessionSummaryDialog(Dialog owner, CoinFlowSession session, boolean trackSpent, Runnable onFinishAndReset)
	{
		this(owner, session, trackSpent, null, false, onFinishAndReset);
	}

	public SessionSummaryDialog(Frame owner, CoinFlowSession session, boolean trackSpent,
		ActivityTimeline timeline, boolean includeAfk, Runnable onFinishAndReset)
	{
		super(owner, "Coin Flow — Session Summary", true);
		init(session, trackSpent, timeline, includeAfk, onFinishAndReset);
	}

	public SessionSummaryDialog(Dialog owner, CoinFlowSession session, boolean trackSpent,
		ActivityTimeline timeline, boolean includeAfk, Runnable onFinishAndReset)
	{
		super(owner, "Coin Flow — Session Summary", true);
		init(session, trackSpent, timeline, includeAfk, onFinishAndReset);
	}

	private void init(CoinFlowSession session, boolean trackSpent, ActivityTimeline timeline,
		boolean includeAfk, Runnable onFinishAndReset)
	{
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setResizable(false);
		getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout());

		// ── Calculations ─────────────────────────────────────────────────────
		long grossProfit = session.getGrossProfit();
		long totalExpenses = trackSpent ? session.getTotalExpenses() : 0L;
		long netProfit = trackSpent ? session.getTotalProfit() : grossProfit;

		Duration activeTime = session.getActiveTime();
		Duration totalTime = session.getTotalInGameTime();
		if (totalTime.compareTo(activeTime) < 0)
		{
			totalTime = activeTime;
		}
		Duration downtime = totalTime.minus(activeTime);

		long totalSec = Math.max(1, totalTime.getSeconds());
		long activeSec = activeTime.getSeconds();
		int efficiencyPct = (int) Math.min(100, Math.max(0, (activeSec * 100) / totalSec));
		int downtimePct = 100 - efficiencyPct;

		long activeGpHr = trackSpent ? session.getGpPerHour(false) : session.getGrossGpPerHour(false);
		long trueGpHr = trackSpent ? session.getGpPerHour(true) : session.getGrossGpPerHour(true);

		double profitMargin = grossProfit > 0 ? ((double) netProfit / grossProfit) * 100.0 : 0.0;

		// ── Main Content Container ───────────────────────────────────────────
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(new EmptyBorder(16, 20, 16, 20));

		// 1. Header Card
		content.add(createHeaderPanel(totalTime, efficiencyPct));
		content.add(Box.createVerticalStrut(14));

		// 2. Financial Metrics Overview
		content.add(createFinancialMetricsPanel(trackSpent, netProfit, grossProfit, totalExpenses, activeGpHr, trueGpHr, profitMargin));
		content.add(Box.createVerticalStrut(14));

		// 3. Time & Downtime / Bankstanding Audit
		content.add(createDowntimeAuditPanel(activeTime, downtime, efficiencyPct, downtimePct));
		content.add(Box.createVerticalStrut(14));

		// 3b. Per-activity breakdown (only when the timeline recorded segments)
		List<ActivityTimeline.Aggregate> aggregates =
			timeline != null ? timeline.aggregateByLabel() : java.util.Collections.emptyList();
		JPanel activityPanel = createActivityBreakdownPanel(aggregates, trackSpent, includeAfk, totalTime);
		if (activityPanel != null)
		{
			content.add(activityPanel);
			content.add(Box.createVerticalStrut(14));
		}

		// 4. Top Revenue vs Biggest Cost Sinks
		content.add(createBreakdownPanel(session, trackSpent, grossProfit, totalExpenses));
		content.add(Box.createVerticalStrut(14));

		// 5. Key Efficiency Insight
		content.add(createInsightPanel(trackSpent, grossProfit, totalExpenses, netProfit, activeGpHr, trueGpHr, downtimePct, profitMargin, aggregates, totalTime));
		content.add(Box.createVerticalStrut(18));

		// 6. Action Footer
		content.add(createFooterPanel(onFinishAndReset));

		add(content, BorderLayout.CENTER);
		pack();
		setLocationRelativeTo(getOwner());
	}

	private JPanel createHeaderPanel(Duration totalTime, int efficiencyPct)
	{
		JPanel panel = new JPanel(new BorderLayout(8, 4));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Session Summary");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ACCENT_GOLD);

		JLabel subtitle = new JLabel(String.format("Duration: %s   |   Active Efficiency: %d%%",
			formatDurationWords(totalTime), efficiencyPct));
		subtitle.setFont(FontManager.getRunescapeSmallFont());
		subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		panel.add(title, BorderLayout.NORTH);
		panel.add(subtitle, BorderLayout.SOUTH);
		return panel;
	}

	private JPanel createFinancialMetricsPanel(
		boolean trackSpent,
		long netProfit,
		long grossProfit,
		long totalExpenses,
		long activeGpHr,
		long trueGpHr,
		double profitMargin)
	{
		JPanel card = createCardPanel();
		card.setLayout(new GridLayout(2, 3, 16, 10));

		// Net Profit / Loss or Gross Profit
		String netSign = netProfit > 0 ? "+" : (netProfit < 0 ? "-" : "");
		Color netColor = netProfit < 0 ? LOSS_RED : PROFIT_GREEN;
		String netText = netSign + QuantityFormatter.formatNumber(Math.abs(netProfit)) + " gp";
		String profitTitle = trackSpent ? (netProfit >= 0 ? "Net Profit" : "Net Loss") : "Gross Profit";
		card.add(createMetricCell(profitTitle, netText, netColor, true));

		// Active GP/Hour
		String activeRateText = (activeGpHr < 0 ? "-" : "") + QuantityFormatter.quantityToStackSize(Math.abs(activeGpHr)) + " gp/hr";
		card.add(createMetricCell("Active GP/Hr", activeRateText, activeGpHr < 0 ? LOSS_RED : PROFIT_GREEN, false));

		// True GP/Hour (including downtime)
		String trueRateText = (trueGpHr < 0 ? "-" : "") + QuantityFormatter.quantityToStackSize(Math.abs(trueGpHr)) + " gp/hr";
		card.add(createMetricCell("True GP/Hr (incl. AFK)", trueRateText, trueGpHr < 0 ? LOSS_RED : Color.WHITE, false));

		// Gross Earnings
		card.add(createMetricCell("Gross Earnings", "+" + QuantityFormatter.quantityToStackSize(grossProfit) + " gp", PROFIT_GREEN, false));

		// Total Supply Expenses
		String expenseText = trackSpent
			? "-" + QuantityFormatter.quantityToStackSize(totalExpenses) + " gp"
			: "Untracked (Gross)";
		Color expenseColor = (trackSpent && totalExpenses > 0) ? LOSS_RED : Color.GRAY;
		card.add(createMetricCell("Supply Expenses", expenseText, expenseColor, false));

		// Profit Margin %
		String marginText;
		if (grossProfit > 0)
		{
			marginText = String.format("%.1f%%", profitMargin);
		}
		else if (totalExpenses > 0)
		{
			marginText = "0.0%";
		}
		else
		{
			marginText = "N/A";
		}
		Color marginColor = profitMargin >= 75.0 ? PROFIT_GREEN : (profitMargin > 0 ? ACCENT_GOLD : LOSS_RED);
		card.add(createMetricCell("Profit Margin", marginText, marginColor, false));

		return card;
	}

	private JPanel createDowntimeAuditPanel(Duration activeTime, Duration downtime, int efficiencyPct, int downtimePct)
	{
		JPanel card = createCardPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

		JLabel sectionTitle = new JLabel("Time & Downtime Audit");
		sectionTitle.setFont(FontManager.getRunescapeBoldFont());
		sectionTitle.setForeground(Color.WHITE);
		card.add(sectionTitle);
		card.add(Box.createVerticalStrut(8));

		JPanel row = new JPanel(new GridLayout(1, 2, 12, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel activeLabel = new JLabel(String.format("Active Grinding: %s (%d%%)", formatDurationWords(activeTime), efficiencyPct));
		activeLabel.setFont(FontManager.getRunescapeSmallFont());
		activeLabel.setForeground(PROFIT_GREEN);

		JLabel downtimeLabel = new JLabel(String.format("Bankstanding / AFK: %s (%d%%)", formatDurationWords(downtime), downtimePct));
		downtimeLabel.setFont(FontManager.getRunescapeSmallFont());
		downtimeLabel.setForeground(downtimePct > 20 ? DOWNTIME_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);

		row.add(activeLabel);
		row.add(downtimeLabel);
		card.add(row);
		card.add(Box.createVerticalStrut(8));

		// Two-tone progress bar
		JProgressBar bar = new JProgressBar(0, 100);
		bar.setValue(efficiencyPct);
		bar.setStringPainted(false);
		bar.setPreferredSize(new Dimension(420, 10));
		bar.setForeground(PROFIT_GREEN);
		bar.setBackground(downtimePct > 0 ? DOWNTIME_ORANGE : ColorScheme.DARK_GRAY_COLOR);
		bar.setBorderPainted(false);
		card.add(bar);

		return card;
	}

	private JPanel createBreakdownPanel(CoinFlowSession session, boolean trackSpent, long grossProfit, long totalExpenses)
	{
		JPanel card = createCardPanel();
		card.setLayout(new GridLayout(1, 2, 20, 0));

		// Top Revenue Items
		JPanel revPanel = new JPanel();
		revPanel.setLayout(new BoxLayout(revPanel, BoxLayout.Y_AXIS));
		revPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel revTitle = new JLabel("Top Revenue Sources");
		revTitle.setFont(FontManager.getRunescapeBoldFont());
		revTitle.setForeground(PROFIT_GREEN);
		revPanel.add(revTitle);
		revPanel.add(Box.createVerticalStrut(6));

		List<CoinFlowSession.TrackedItem> topGains = session.getSortedItems();
		if (topGains.isEmpty())
		{
			JLabel noneLabel = new JLabel("None recorded");
			noneLabel.setFont(FontManager.getRunescapeSmallFont());
			noneLabel.setForeground(Color.GRAY);
			revPanel.add(noneLabel);
		}
		else
		{
			int count = Math.min(3, topGains.size());
			for (int i = 0; i < count; i++)
			{
				CoinFlowSession.TrackedItem item = topGains.get(i);
				int pct = grossProfit > 0 ? (int) ((item.getTotalValue() * 100) / grossProfit) : 0;
				String text = String.format("%d. %s (%d%%) - %s",
					i + 1, item.getName(), pct, QuantityFormatter.quantityToStackSize(item.getTotalValue()));
				JLabel itemLabel = new JLabel(text);
				itemLabel.setFont(FontManager.getRunescapeSmallFont());
				itemLabel.setForeground(Color.WHITE);
				revPanel.add(itemLabel);
				if (i < count - 1)
				{
					revPanel.add(Box.createVerticalStrut(3));
				}
			}
		}

		// Biggest Expense Sinks
		JPanel expPanel = new JPanel();
		expPanel.setLayout(new BoxLayout(expPanel, BoxLayout.Y_AXIS));
		expPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel expTitle = new JLabel("Biggest Cost Sinks");
		expTitle.setFont(FontManager.getRunescapeBoldFont());
		expTitle.setForeground(LOSS_RED);
		expPanel.add(expTitle);
		expPanel.add(Box.createVerticalStrut(6));

		if (!trackSpent)
		{
			JLabel disabledLabel = new JLabel("None (Spent tracking disabled)");
			disabledLabel.setFont(FontManager.getRunescapeSmallFont());
			disabledLabel.setForeground(PROFIT_GREEN);
			expPanel.add(disabledLabel);
		}
		else
		{
			List<CoinFlowSession.TrackedItem> topExpenses = session.getSortedExpenses();
			if (topExpenses.isEmpty())
			{
				JLabel noneLabel = new JLabel("None (Zero supply costs)");
				noneLabel.setFont(FontManager.getRunescapeSmallFont());
				noneLabel.setForeground(PROFIT_GREEN);
				expPanel.add(noneLabel);
			}
			else
			{
				int count = Math.min(3, topExpenses.size());
				for (int i = 0; i < count; i++)
				{
					CoinFlowSession.TrackedItem item = topExpenses.get(i);
					int pct = totalExpenses > 0 ? (int) ((item.getTotalValue() * 100) / totalExpenses) : 0;
					String text = String.format("%d. %s (%d%%) - %s",
						i + 1, item.getName(), pct, QuantityFormatter.quantityToStackSize(item.getTotalValue()));
					JLabel itemLabel = new JLabel(text);
					itemLabel.setFont(FontManager.getRunescapeSmallFont());
					itemLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					expPanel.add(itemLabel);
					if (i < count - 1)
					{
						expPanel.add(Box.createVerticalStrut(3));
					}
				}
			}
		}

		card.add(revPanel);
		card.add(expPanel);
		return card;
	}

	/**
	 * Per-activity table: where the time went and what each activity earned
	 * (plus kills and net-per-kill for combat segments). Returns null when the
	 * timeline has no usable segments so the dialog layout is unchanged.
	 */
	private JPanel createActivityBreakdownPanel(List<ActivityTimeline.Aggregate> aggregates,
		boolean trackSpent, boolean includeAfk, Duration totalTime)
	{
		if (aggregates.isEmpty())
		{
			return null;
		}
		long totalMillis = Math.max(1, totalTime.toMillis());

		JPanel card = createCardPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

		JLabel sectionTitle = new JLabel("Activity Breakdown");
		sectionTitle.setFont(FontManager.getRunescapeBoldFont());
		sectionTitle.setForeground(ACCENT_GOLD);
		card.add(sectionTitle);
		card.add(Box.createVerticalStrut(8));

		for (ActivityTimeline.Aggregate agg : aggregates)
		{
			JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));
			row.setOpaque(false);

			int sharePct = (int) Math.min(100, (agg.totalTime.toMillis() * 100) / totalMillis);
			JLabel left = new JLabel(agg.label);
			left.setFont(FontManager.getRunescapeSmallFont());
			left.setForeground(Color.WHITE);
			row.add(left);

			StringBuilder right = new StringBuilder();
			long net = agg.net();
			if (agg.kind == ActivityTimeline.Kind.CORRECTION)
			{
				right.append("Adjustment · ");
			}
			else
			{
				right.append(formatDurationWords(agg.totalTime)).append(" (").append(sharePct).append("%) · ");
				right.append(QuantityFormatter.quantityToStackSize(agg.gpPerHour(includeAfk))).append(" gp/hr · ");
			}
			right.append(net < 0 ? "-" : "+").append(QuantityFormatter.quantityToStackSize(Math.abs(net))).append(" gp");
			if (agg.kind == ActivityTimeline.Kind.COMBAT && agg.kills > 0)
			{
				right.append(" · ").append(agg.kills).append(agg.kills == 1 ? " kill" : " kills")
					.append(", ").append(QuantityFormatter.quantityToStackSize(net / agg.kills)).append("/kill");
				if (trackSpent && agg.expenses > 0)
				{
					right.append(" (").append(QuantityFormatter.quantityToStackSize(agg.expenses / agg.kills)).append(" supplies)");
				}
			}

			JLabel rightLabel = new JLabel(right.toString(), SwingConstants.RIGHT);
			rightLabel.setFont(FontManager.getRunescapeSmallFont());
			rightLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			row.add(rightLabel);

			card.add(row);
		}
		return card;
	}

	private JPanel createInsightPanel(
		boolean trackSpent,
		long grossProfit,
		long totalExpenses,
		long netProfit,
		long activeGpHr,
		long trueGpHr,
		int downtimePct,
		double profitMargin,
		List<ActivityTimeline.Aggregate> aggregates,
		Duration totalTime)
	{
		JPanel card = createCardPanel();
		card.setLayout(new BorderLayout(8, 4));

		JLabel insightIcon = new JLabel("Key Takeaway: ");
		insightIcon.setFont(FontManager.getRunescapeBoldFont());
		insightIcon.setForeground(ACCENT_GOLD);

		String insightText;
		if (!trackSpent)
		{
			insightText = "Gross tracking mode active with supply expenses disabled.";
		}
		else if (grossProfit == 0 && totalExpenses == 0)
		{
			insightText = "No financial activity recorded in this session yet.";
		}
		else if (netProfit < 0)
		{
			insightText = "Supply expenses exceeded gross earnings this run (net loss).";
		}
		else if (totalExpenses == 0)
		{
			insightText = "100% pure profit! Zero supplies consumed during this session.";
		}
		else if (aggregates != null && aggregates.stream()
			.filter(agg -> agg.kind != ActivityTimeline.Kind.CORRECTION).count() >= 2)
		{
			long totalMillis = Math.max(1, totalTime.toMillis());
			ActivityTimeline.Aggregate banking = null;
			ActivityTimeline.Aggregate best = null;
			for (ActivityTimeline.Aggregate agg : aggregates)
			{
				if (agg.kind == ActivityTimeline.Kind.CORRECTION)
				{
					continue;
				}
				if (agg.kind == ActivityTimeline.Kind.BANKING)
				{
					banking = agg;
				}
				if (best == null || agg.net() > best.net())
				{
					best = agg;
				}
			}
			int bankingPct = banking != null ? (int) ((banking.totalTime.toMillis() * 100) / totalMillis) : 0;
			String bestPart = best != null && best.net() != 0
				? String.format(" Best earner: %s at %s gp/hr.",
					best.label, QuantityFormatter.quantityToStackSize(best.gpPerHour(true)))
				: "";
			if (bankingPct >= 10)
			{
				insightText = String.format("%d%% of your session was Banking / GE.%s", bankingPct, bestPart);
			}
			else if (!bestPart.isEmpty())
			{
				insightText = bestPart.trim();
			}
			else
			{
				insightText = String.format("Net margin of %.1f%% with %s gp spent.",
					profitMargin, QuantityFormatter.quantityToStackSize(totalExpenses));
			}
		}
		else if (downtimePct >= 20 && activeGpHr > trueGpHr)
		{
			long rateGap = activeGpHr - trueGpHr;
			insightText = String.format("Bankstanding and AFK downtime reduced your true rate by ~%s gp/hr.",
				QuantityFormatter.quantityToStackSize(rateGap));
		}
		else if (profitMargin >= 80.0)
		{
			insightText = String.format("High efficiency! You retained %.1f%% of your gross loot as profit.", profitMargin);
		}
		else
		{
			insightText = String.format("Net margin of %.1f%% with %s gp spent.",
				profitMargin, QuantityFormatter.quantityToStackSize(totalExpenses));
		}

		JLabel label = new JLabel(insightText);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.WHITE);

		card.add(insightIcon, BorderLayout.WEST);
		card.add(label, BorderLayout.CENTER);
		return card;
	}

	private JPanel createFooterPanel(Runnable onFinishAndReset)
	{
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton continueBtn = new JButton("Continue Session");
		continueBtn.setFont(FontManager.getRunescapeFont());
		continueBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		continueBtn.setForeground(Color.WHITE);
		continueBtn.setFocusPainted(false);
		continueBtn.setBorder(new EmptyBorder(6, 12, 6, 12));
		continueBtn.addActionListener(e -> dispose());

		JButton finishBtn = new JButton("Finish & Reset Session");
		finishBtn.setFont(FontManager.getRunescapeBoldFont());
		finishBtn.setBackground(new Color(0x7F, 0x1D, 0x1D));
		finishBtn.setForeground(Color.WHITE);
		finishBtn.setFocusPainted(false);
		finishBtn.setBorder(new EmptyBorder(6, 14, 6, 14));
		finishBtn.addActionListener(e ->
		{
			dispose();
			if (onFinishAndReset != null)
			{
				onFinishAndReset.run();
			}
		});

		panel.add(continueBtn);
		panel.add(finishBtn);
		return panel;
	}

	private JPanel createMetricCell(String caption, String value, Color valueColor, boolean isPrimary)
	{
		JPanel cell = new JPanel();
		cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
		cell.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel cap = new JLabel(caption);
		cap.setFont(FontManager.getRunescapeSmallFont());
		cap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JLabel val = new JLabel(value);
		val.setFont(isPrimary ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeFont());
		val.setForeground(valueColor);

		cell.add(cap);
		cell.add(Box.createVerticalStrut(3));
		cell.add(val);
		return cell;
	}

	private JPanel createCardPanel()
	{
		JPanel card = new JPanel();
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR, 1),
			new EmptyBorder(10, 14, 10, 14)
		));
		return card;
	}

	private static String formatDurationWords(Duration duration)
	{
		long totalSeconds = Math.max(0, duration.getSeconds());
		long hours = totalSeconds / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;

		if (hours > 0)
		{
			return String.format("%dh %02dm", hours, minutes);
		}
		if (minutes > 0)
		{
			return String.format("%dm %02ds", minutes, seconds);
		}
		return String.format("%ds", seconds);
	}
}
