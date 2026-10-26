package com.coinflow;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.config.ConfigPlugin;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.SwingUtil;

@Singleton
public class CoinFlowPanel extends PluginPanel
{
	private static final Color ACCENT_GOLD = new Color(0xFF, 0xD7, 0x00);
	private static final Color PROFIT_GREEN = ColorScheme.PROGRESS_COMPLETE_COLOR;
	private static final Color WARN_ORANGE = ColorScheme.BRAND_ORANGE;
	private static final Color GOAL_COMPLETE_GREEN = new Color(24, 134, 52); // Rich forest green (#188634)
	private static final Color GOAL_IN_PROGRESS_ORANGE = new Color(205, 110, 0); // Warm amber orange

	private final CoinFlowPlugin plugin;
	private final CoinFlowConfig config;
	private final ConfigManager configManager;
	private final ItemManager itemManager;

	// ── Top Header UI ────────────────────────────────────────────────────
	private final JPanel headerPanel = new JPanel();
	private final JButton toggleCompactButton = new JButton("Compact");
	private final JButton configButton = new JButton();

	// ── Session Summary UI ───────────────────────────────────────────────
	final JPanel sessionCard = new JPanel();
	private final JLabel sessionCollapsedPreviewLabel = new JLabel("", SwingConstants.RIGHT);
	private final JLabel sessionProfitCaption = new JLabel("Net Profit", SwingConstants.CENTER);
	private final JLabel sessionProfitLabel = new JLabel("0 gp", SwingConstants.CENTER);
	final SparklinePanel sessionSparkline = new SparklinePanel();
	private final JLabel sessionRateLabel = new JLabel("0 gp/hr", SwingConstants.RIGHT);
	private final JLabel sessionTimeLabel = new JLabel("00:00", SwingConstants.RIGHT);
	private final JLabel sessionGrossLabel = new JLabel("+0 gp", SwingConstants.RIGHT);
	private final JLabel sessionSuppliesLabel = new JLabel("0 gp", SwingConstants.RIGHT);
	private final JPanel sessionStatsGrid = new JPanel(new GridLayout(2, 2, 6, 4));
	private final JButton finishSessionButton = new JButton("Finish Session / Reset");
	private final JButton compactResetButton = new JButton("Reset");

	// ── Goal UI Components ───────────────────────────────────────────────
	private final JPanel goalCard = new JPanel();
	private final JLabel goalCollapsedPreviewLabel = new JLabel("", SwingConstants.RIGHT);
	private final JLabel goalTitleLabel = new JLabel("No Active Goal", SwingConstants.LEFT);
	private final JProgressBar goalProgressBar = new JProgressBar(0, 100);
	private final JLabel goalProgressNumbers = new JLabel("0 / 0 gp (0.0%)", SwingConstants.CENTER);

	private final JLabel goalRemainingCaption = new JLabel("Remaining:", SwingConstants.LEFT);
	private final JLabel goalRemainingValue = new JLabel("0 gp", SwingConstants.RIGHT);
	private final JLabel goalEtaCaption = new JLabel("ETA:", SwingConstants.LEFT);
	private final JLabel goalEtaValue = new JLabel("--:--", SwingConstants.RIGHT);
	private final JPanel goalStatsGrid = new JPanel(new GridLayout(2, 2, 6, 4));

	private final JPanel activeGoalActionsPanel = new JPanel(new GridLayout(1, 2, 6, 0));
	private final JButton editGoalButton = new JButton("Edit");
	private final JButton clearGoalButton = new JButton("Clear");
	private final JButton miniEditGoalButton = new JButton("Edit");
	private final JButton miniClearGoalButton = new JButton("Clear");

	// Goal Setup Panel (when editing or no goal set)
	private final JPanel goalSetupPanel = new JPanel();
	private final JTextField targetGpField = new JTextField();
	private final JTextField goalNameField = new JTextField();
	private final JPanel goalSetupButtonsRow = new JPanel();
	private final JButton setGoalButton = new JButton("Set Goal");
	private final JButton cancelEditButton = new JButton("Cancel");

	// ── Activity Timeline UI Components ──────────────────────────────────
	private final JPanel timelineCard = new JPanel();
	private final JLabel timelineCollapsedPreviewLabel = new JLabel("", SwingConstants.RIGHT);
	final JPanel timelineContainer = new JPanel();
	/** Retained labels of the live segment row so per-tick updates need no rebuild. */
	private final List<ActivityRow> activityRows = new ArrayList<>();
	private final Map<Skill, ImageIcon> skillIcons = new EnumMap<>(Skill.class);
	/** Timeline vs Totals view; a field (like itemFilter) so it doesn't churn persisted settings. */
	boolean timelineTotalsView = true;

	// ── Item Breakdown UI Components ─────────────────────────────────────
	private final JPanel itemsCard = new JPanel();
	private final JLabel itemsCollapsedPreviewLabel = new JLabel("", SwingConstants.RIGHT);
	final JPanel itemsContainer = new JPanel();

	/**
	 * Which rows the item breakdown card renders. Only meaningful when
	 * {@code trackSpent} is on; kept as a field (not config) so the choice
	 * doesn't churn persisted settings.
	 */
	enum ItemFilter
	{
		ALL, LOOT, SPENT
	}
	ItemFilter itemFilter = ItemFilter.ALL;

	private boolean editingGoal = false;
	private boolean lastHadActiveGoal = false;
	private String lastGoalName = "";
	private boolean lastCompactMode = false;
	private boolean lastSessionCollapsed = false;
	private boolean lastGoalCollapsed = false;
	private boolean lastItemsCardCollapsed = false;
	private boolean lastShowItemBreakdown = true;
	private boolean lastTrackSpent = true;
	private boolean lastTimelineCardCollapsed = false;
	private boolean lastShowTimeline = true;
	private int lastTimelineRevision = -1;
	private Map<Integer, CoinFlowSession.TrackedItem> lastRenderedItems = null;
	private Map<Integer, CoinFlowSession.TrackedItem> lastRenderedExpenses = null;

	@Inject
	public CoinFlowPanel(
		CoinFlowPlugin plugin,
		CoinFlowConfig config,
		ConfigManager configManager,
		ItemManager itemManager)
	{
		super();
		this.plugin = plugin;
		this.config = config;
		this.configManager = configManager;
		this.itemManager = itemManager;
		this.lastTrackSpent = config.trackSpent();
	}

	private boolean initialized = false;
	private boolean promptOpen = false;

	public void init()
	{
		if (initialized)
		{
			onConfigChanged();
			return;
		}
		initialized = true;

		setBorder(new EmptyBorder(BORDER_OFFSET, BORDER_OFFSET, BORDER_OFFSET, BORDER_OFFSET));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new GridBagLayout());

		// Lock horizontal viewport scroll to 0 to prevent panel content from shifting or cutting off on the left
		JScrollPane sp = getScrollPane();
		if (sp != null)
		{
			sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
			sp.getViewport().addChangeListener(e -> {
				Point p = sp.getViewport().getViewPosition();
				if (p.x != 0)
				{
					p.x = 0;
					sp.getViewport().setViewPosition(p);
				}
			});
		}

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1.0;
		c.gridx = 0;
		c.gridy = 0;

		// 1. Header
		c.insets = new Insets(0, 0, 10, 0);
		add(headerPanel, c);
		c.gridy++;

		// 2. Session Summary Card
		c.insets = new Insets(0, 0, 10, 0);
		add(sessionCard, c);
		c.gridy++;

		// 3. Goal Card
		c.insets = new Insets(0, 0, 10, 0);
		add(goalCard, c);
		c.gridy++;

		// 4. Activity Timeline Card
		c.insets = new Insets(0, 0, 10, 0);
		add(timelineCard, c);
		c.gridy++;

		// 5. Item Breakdown Card
		c.insets = new Insets(0, 0, 0, 0);
		add(itemsCard, c);
		c.gridy++;

		// 5. Spacer panel to absorb remaining vertical space and keep cards at the top
		c.weighty = 1.0;
		c.fill = GridBagConstraints.BOTH;
		JPanel filler = new JPanel();
		filler.setOpaque(false);
		add(filler, c);

		// Initialize sub-panels & components
		buildGoalSetupSubpanel();
		initGoalComponents();
		initSessionComponents();
		initHeaderComponents();

		// Synchronize initial state
		onConfigChanged();
	}

	@Override
	public void scrollRectToVisible(Rectangle aRect)
	{
		if (aRect != null)
		{
			aRect.x = 0;
			aRect.width = Math.min(aRect.width, getWidth());
		}
		super.scrollRectToVisible(aRect);
	}

	@Override
	public JScrollPane getScrollPane()
	{
		return super.getScrollPane();
	}

	// ── Component Initializers ───────────────────────────────────────────

	private void initHeaderComponents()
	{
		styleButton(toggleCompactButton);
		toggleCompactButton.addActionListener(e -> {
			boolean nextMode = !config.compactMode();
			configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "compactMode", nextMode);
			refreshHeaderPanel();
			refreshSessionCardView();
			refreshGoalCardView();
			refreshTimelineCardView();
			refreshItemsCardView();
			CoinFlowSession session = plugin.getSession();
			if (session != null)
			{
				updateSessionUI(session);
			}
		});

		configButton.setPreferredSize(new Dimension(24, 22));
		configButton.setToolTipText("Configure Coin Flow");
		try
		{
			BufferedImage configIcon = ImageUtil.loadImageResource(ConfigPlugin.class, "config_edit_icon.png");
			if (configIcon != null)
			{
				configButton.setIcon(new ImageIcon(configIcon));
				configButton.setRolloverIcon(new ImageIcon(ImageUtil.luminanceOffset(configIcon, 40)));
			}
			else
			{
				configButton.setText("⚙");
			}
		}
		catch (Exception e)
		{
			configButton.setText("⚙");
		}
		SwingUtil.removeButtonDecorations(configButton);
		configButton.addActionListener(e -> plugin.openConfiguration());
	}

	private void initSessionComponents()
	{
		sessionProfitLabel.setFont(FontManager.getRunescapeBoldFont());
		sessionProfitLabel.setForeground(PROFIT_GREEN);

		sessionRateLabel.setFont(FontManager.getRunescapeFont());
		sessionRateLabel.setForeground(Color.WHITE);

		sessionTimeLabel.setFont(FontManager.getRunescapeFont());
		sessionTimeLabel.setForeground(Color.WHITE);

		sessionCollapsedPreviewLabel.setFont(FontManager.getRunescapeSmallFont());

		styleButton(finishSessionButton);
		finishSessionButton.setPreferredSize(new Dimension(0, 26));
		for (java.awt.event.ActionListener al : finishSessionButton.getActionListeners())
		{
			finishSessionButton.removeActionListener(al);
		}
		finishSessionButton.addActionListener(e -> onFinishSessionClicked());

		styleButton(compactResetButton);
		compactResetButton.setPreferredSize(new Dimension(48, 20));
		for (java.awt.event.ActionListener al : compactResetButton.getActionListeners())
		{
			compactResetButton.removeActionListener(al);
		}
		compactResetButton.addActionListener(e -> onFinishSessionClicked());
	}

	private void initGoalComponents()
	{
		goalTitleLabel.setFont(FontManager.getRunescapeBoldFont());
		goalTitleLabel.setForeground(Color.WHITE);

		goalProgressBar.setStringPainted(true);
		goalProgressBar.setFont(FontManager.getRunescapeSmallFont());
		goalProgressBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		goalProgressBar.setForeground(GOAL_COMPLETE_GREEN);

		goalProgressNumbers.setFont(FontManager.getRunescapeSmallFont());
		goalProgressNumbers.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		goalProgressNumbers.setHorizontalAlignment(SwingConstants.CENTER);

		goalRemainingCaption.setFont(FontManager.getRunescapeSmallFont());
		goalRemainingCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		goalRemainingValue.setFont(FontManager.getRunescapeFont());
		goalRemainingValue.setForeground(Color.WHITE);
		goalRemainingValue.setHorizontalAlignment(SwingConstants.RIGHT);

		goalEtaCaption.setFont(FontManager.getRunescapeSmallFont());
		goalEtaCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		goalEtaValue.setFont(FontManager.getRunescapeFont());
		goalEtaValue.setForeground(WARN_ORANGE);
		goalEtaValue.setHorizontalAlignment(SwingConstants.RIGHT);

		goalCollapsedPreviewLabel.setFont(FontManager.getRunescapeSmallFont());

		styleButton(editGoalButton);
		editGoalButton.addActionListener(e -> {
			editingGoal = true;
			refreshGoalCardView();
		});

		styleButton(clearGoalButton);
		clearGoalButton.addActionListener(e -> {
			configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalAmount", "");
			configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalName", "");
			targetGpField.setText("");
			goalNameField.setText("");
			editingGoal = false;
			refreshGoalCardView();
		});

		activeGoalActionsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		activeGoalActionsPanel.setPreferredSize(new Dimension(0, 26));
		activeGoalActionsPanel.removeAll();
		activeGoalActionsPanel.add(editGoalButton);
		activeGoalActionsPanel.add(clearGoalButton);

		styleButton(miniEditGoalButton);
		miniEditGoalButton.setPreferredSize(new Dimension(38, 18));
		miniEditGoalButton.addActionListener(e -> {
			editingGoal = true;
			refreshGoalCardView();
		});

		styleButton(miniClearGoalButton);
		miniClearGoalButton.setPreferredSize(new Dimension(42, 18));
		miniClearGoalButton.addActionListener(e -> {
			configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalAmount", "");
			configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalName", "");
			targetGpField.setText("");
			goalNameField.setText("");
			editingGoal = false;
			refreshGoalCardView();
		});
	}

	// ── Card Builders & Refreshers ───────────────────────────────────────

	private void refreshHeaderPanel()
	{
		headerPanel.removeAll();
		headerPanel.setLayout(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		boolean compact = config.compactMode();
		lastCompactMode = compact;

		JPanel titleBox = new JPanel(new BorderLayout());
		titleBox.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Coin Flow");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ACCENT_GOLD);
		title.setToolTipText("Coin Flow " + Version.getFormattedVersion());
		titleBox.add(title, BorderLayout.NORTH);

		if (!compact)
		{
			JLabel subtitle = new JLabel("Live GP/hr & Goals · " + Version.getFormattedVersion());
			subtitle.setFont(FontManager.getRunescapeSmallFont());
			subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			titleBox.add(subtitle, BorderLayout.SOUTH);
		}

		toggleCompactButton.setText(compact ? "Standard" : "Compact");
		toggleCompactButton.setPreferredSize(new Dimension(compact ? 66 : 60, 22));
		toggleCompactButton.setToolTipText(compact ? "Switch to standard expanded view" : "Switch to compact view");
		toggleCompactButton.setForeground(compact ? ACCENT_GOLD : Color.WHITE);

		JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, compact ? 0 : 2));
		rightBox.setOpaque(false);
		rightBox.add(toggleCompactButton);
		rightBox.add(configButton);

		headerPanel.add(titleBox, BorderLayout.WEST);
		headerPanel.add(rightBox, BorderLayout.EAST);

		headerPanel.revalidate();
		headerPanel.repaint();
	}

	void refreshSessionCardView()
	{
		sessionCard.removeAll();
		sessionCard.setLayout(new GridBagLayout());
		sessionCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		boolean compact = config.compactMode();
		boolean collapsed = config.sessionCardCollapsed();
		lastCompactMode = compact;
		lastSessionCollapsed = collapsed;
		lastTrackSpent = config.trackSpent();

		sessionCard.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 1, 1, 1, ColorScheme.DARK_GRAY_HOVER_COLOR),
			new EmptyBorder(compact ? 6 : 8, compact ? 6 : 8, compact ? 6 : 8, compact ? 6 : 8)
		));

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1.0;
		c.gridx = 0;
		c.gridy = 0;

		sessionCollapsedPreviewLabel.setFont(FontManager.getRunescapeSmallFont());

		JPanel headerRow = buildCardHeaderRow("Session Overview", collapsed, sessionCollapsedPreviewLabel, () -> {
			boolean next = !config.sessionCardCollapsed();
			configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "sessionCardCollapsed", next);
			refreshSessionCardView();
			CoinFlowSession s = plugin.getSession();
			if (s != null)
			{
				updateSessionUI(s);
			}
		});

		c.insets = new Insets(0, 0, collapsed ? 0 : (compact ? 6 : 8), 0);
		sessionCard.add(headerRow, c);
		c.gridy++;

		if (!collapsed)
		{
			if (compact)
			{
				buildCompactSessionBody(sessionCard, c);
			}
			else
			{
				buildStandardSessionBody(sessionCard, c);
			}
		}

		sessionCard.revalidate();
		sessionCard.repaint();
		revalidate();
		repaint();
	}

	private void buildStandardSessionBody(JPanel card, GridBagConstraints c)
	{
		boolean trackSpent = config.trackSpent();

		// Net Profit Caption
		sessionProfitCaption.setText(trackSpent ? "Net Profit" : "Profit");
		sessionProfitCaption.setFont(FontManager.getRunescapeSmallFont());
		sessionProfitCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sessionProfitCaption.setHorizontalAlignment(SwingConstants.CENTER);
		c.insets = new Insets(0, 0, 2, 0);
		card.add(sessionProfitCaption, c);
		c.gridy++;

		// Profit Value
		boolean sparkline = config.showSparkline();
		sessionProfitLabel.setFont(FontManager.getRunescapeBoldFont());
		sessionProfitLabel.setHorizontalAlignment(SwingConstants.CENTER);
		c.insets = new Insets(0, 0, sparkline ? 4 : 10, 0);
		card.add(sessionProfitLabel, c);
		c.gridy++;

		// Optional cumulative-profit sparkline (standard layout only)
		if (sparkline)
		{
			c.insets = new Insets(0, 0, 10, 0);
			card.add(sessionSparkline, c);
			c.gridy++;
		}

		// Rate, Time, Gross Loot & Supplies Grid
		sessionStatsGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sessionStatsGrid.removeAll();
		sessionStatsGrid.setLayout(new GridLayout(trackSpent ? 4 : 2, 2, 6, 4));

		JLabel rateCaption = new JLabel("Rate:", SwingConstants.LEFT);
		rateCaption.setFont(FontManager.getRunescapeSmallFont());
		rateCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		sessionRateLabel.setFont(FontManager.getRunescapeFont());
		sessionRateLabel.setHorizontalAlignment(SwingConstants.RIGHT);

		JLabel timeCaption = new JLabel("Active Time:", SwingConstants.LEFT);
		timeCaption.setFont(FontManager.getRunescapeSmallFont());
		timeCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		sessionTimeLabel.setFont(FontManager.getRunescapeFont());
		sessionTimeLabel.setHorizontalAlignment(SwingConstants.RIGHT);

		sessionStatsGrid.add(rateCaption);
		sessionStatsGrid.add(sessionRateLabel);
		sessionStatsGrid.add(timeCaption);
		sessionStatsGrid.add(sessionTimeLabel);

		if (trackSpent)
		{
			JLabel grossCaption = new JLabel("Gross Loot:", SwingConstants.LEFT);
			grossCaption.setFont(FontManager.getRunescapeSmallFont());
			grossCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

			sessionGrossLabel.setFont(FontManager.getRunescapeFont());
			sessionGrossLabel.setHorizontalAlignment(SwingConstants.RIGHT);

			JLabel suppliesCaption = new JLabel("Spent:", SwingConstants.LEFT);
			suppliesCaption.setFont(FontManager.getRunescapeSmallFont());
			suppliesCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

			sessionSuppliesLabel.setFont(FontManager.getRunescapeFont());
			sessionSuppliesLabel.setHorizontalAlignment(SwingConstants.RIGHT);

			sessionStatsGrid.add(grossCaption);
			sessionStatsGrid.add(sessionGrossLabel);
			sessionStatsGrid.add(suppliesCaption);
			sessionStatsGrid.add(sessionSuppliesLabel);
		}

		c.insets = new Insets(0, 0, 10, 0);
		card.add(sessionStatsGrid, c);
		c.gridy++;

		// Finish Session / Reset Button (full width)
		c.insets = new Insets(0, 0, 0, 0);
		card.add(finishSessionButton, c);
		c.gridy++;
	}

	private void buildCompactSessionBody(JPanel card, GridBagConstraints c)
	{
		boolean trackSpent = config.trackSpent();
		JPanel compactGrid = new JPanel(new GridLayout(trackSpent ? 3 : 2, 2, 6, 3));
		compactGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel profitCaption = new JLabel(trackSpent ? "Net Profit:" : "Profit:", SwingConstants.LEFT);
		profitCaption.setFont(FontManager.getRunescapeSmallFont());
		profitCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		sessionProfitLabel.setFont(FontManager.getRunescapeFont());
		sessionProfitLabel.setHorizontalAlignment(SwingConstants.RIGHT);

		compactGrid.add(profitCaption);
		compactGrid.add(sessionProfitLabel);

		if (trackSpent)
		{
			JLabel suppliesCaption = new JLabel("Spent:", SwingConstants.LEFT);
			suppliesCaption.setFont(FontManager.getRunescapeSmallFont());
			suppliesCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

			sessionSuppliesLabel.setFont(FontManager.getRunescapeFont());
			sessionSuppliesLabel.setHorizontalAlignment(SwingConstants.RIGHT);

			compactGrid.add(suppliesCaption);
			compactGrid.add(sessionSuppliesLabel);
		}

		JLabel rateCaption = new JLabel("Rate:", SwingConstants.LEFT);
		rateCaption.setFont(FontManager.getRunescapeSmallFont());
		rateCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		sessionRateLabel.setFont(FontManager.getRunescapeFont());
		sessionRateLabel.setHorizontalAlignment(SwingConstants.RIGHT);

		compactGrid.add(rateCaption);
		compactGrid.add(sessionRateLabel);

		c.insets = new Insets(0, 0, 4, 0);
		card.add(compactGrid, c);
		c.gridy++;

		// Row with Active Time and small Reset button
		JPanel bottomRow = new JPanel(new BorderLayout(6, 0));
		bottomRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JPanel timePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		timePanel.setOpaque(false);
		JLabel timeCaption = new JLabel("Time: ");
		timeCaption.setFont(FontManager.getRunescapeSmallFont());
		timeCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sessionTimeLabel.setFont(FontManager.getRunescapeFont());
		sessionTimeLabel.setHorizontalAlignment(SwingConstants.LEFT);
		timePanel.add(timeCaption);
		timePanel.add(sessionTimeLabel);

		bottomRow.add(timePanel, BorderLayout.WEST);
		bottomRow.add(compactResetButton, BorderLayout.EAST);

		c.insets = new Insets(0, 0, 0, 0);
		card.add(bottomRow, c);
		c.gridy++;
	}

	private void refreshGoalCardView()
	{
		goalCard.removeAll();
		goalCard.setLayout(new GridBagLayout());
		goalCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		boolean compact = config.compactMode();
		boolean collapsed = config.goalCardCollapsed();
		lastGoalCollapsed = collapsed;

		goalCard.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 1, 1, 1, ColorScheme.DARK_GRAY_HOVER_COLOR),
			new EmptyBorder(compact ? 6 : 8, compact ? 6 : 8, compact ? 6 : 8, compact ? 6 : 8)
		));

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1.0;
		c.gridx = 0;
		c.gridy = 0;

		long goalAmount = CoinFlowSession.parseGoalAmount(config.goalAmount());
		boolean hasActiveGoal = goalAmount > 0;
		lastHadActiveGoal = hasActiveGoal;
		lastGoalName = CoinFlowSession.cleanGoalName(config.goalName());

		goalCollapsedPreviewLabel.setFont(FontManager.getRunescapeSmallFont());
		if (hasActiveGoal)
		{
			CoinFlowSession session = plugin.getSession();
			double progress = session != null ? session.getGoalProgress(goalAmount, plugin.goalDisplayTotal()) : 0.0;
			goalCollapsedPreviewLabel.setText(String.format("%.1f%%", progress * 100));
			goalCollapsedPreviewLabel.setForeground(progress >= 1.0 ? GOAL_COMPLETE_GREEN : GOAL_IN_PROGRESS_ORANGE);
		}
		else
		{
			goalCollapsedPreviewLabel.setText("No Goal");
			goalCollapsedPreviewLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}

		JPanel headerRow = buildCardHeaderRow("Goal Progress", collapsed, goalCollapsedPreviewLabel, () -> {
			boolean next = !config.goalCardCollapsed();
			configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalCardCollapsed", next);
			refreshGoalCardView();
			CoinFlowSession s = plugin.getSession();
			if (s != null)
			{
				updateSessionUI(s);
			}
		});

		c.insets = new Insets(0, 0, collapsed ? 0 : (compact ? 6 : 8), 0);
		goalCard.add(headerRow, c);
		c.gridy++;

		if (!collapsed)
		{
			if (!hasActiveGoal || editingGoal)
			{
				// Show setup view
				if (editingGoal)
				{
					targetGpField.setText(config.goalAmount());
					goalNameField.setText(config.goalName());
				}
				updateGoalSetupButtons();
				c.insets = new Insets(0, 0, 0, 0);
				goalCard.add(goalSetupPanel, c);
			}
			else
			{
				if (compact)
				{
					buildCompactGoalBody(goalCard, c, goalAmount);
				}
				else
				{
					buildStandardGoalBody(goalCard, c, goalAmount);
				}
			}
		}

		goalCard.revalidate();
		goalCard.repaint();
		revalidate();
		repaint();
	}

	private void buildStandardGoalBody(JPanel card, GridBagConstraints c, long goalAmount)
	{
		String name = CoinFlowSession.cleanGoalName(config.goalName());
		goalTitleLabel.setText(name.isEmpty() ? "Target GP Goal" : name);
		goalTitleLabel.setFont(FontManager.getRunescapeBoldFont());

		c.insets = new Insets(0, 0, 6, 0);
		card.add(goalTitleLabel, c);
		c.gridy++;

		goalProgressBar.setPreferredSize(new Dimension(0, 20));
		goalProgressBar.setMinimumSize(new Dimension(0, 20));
		c.insets = new Insets(0, 0, 4, 0);
		card.add(goalProgressBar, c);
		c.gridy++;

		c.insets = new Insets(0, 0, 8, 0);
		card.add(goalProgressNumbers, c);
		c.gridy++;

		goalStatsGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		goalStatsGrid.removeAll();
		goalStatsGrid.setLayout(new GridLayout(2, 2, 6, 4));
		goalRemainingValue.setFont(FontManager.getRunescapeFont());
		goalEtaValue.setFont(FontManager.getRunescapeFont());
		goalStatsGrid.add(goalRemainingCaption);
		goalStatsGrid.add(goalRemainingValue);
		goalStatsGrid.add(goalEtaCaption);
		goalStatsGrid.add(goalEtaValue);

		c.insets = new Insets(0, 0, 10, 0);
		card.add(goalStatsGrid, c);
		c.gridy++;

		c.insets = new Insets(0, 0, 0, 0);
		card.add(activeGoalActionsPanel, c);
		c.gridy++;

		CoinFlowSession session = plugin.getSession();
		if (session != null)
		{
			applySessionToGoal(session, goalAmount);
		}
	}

	private void buildCompactGoalBody(JPanel card, GridBagConstraints c, long goalAmount)
	{
		String name = CoinFlowSession.cleanGoalName(config.goalName());
		goalTitleLabel.setText(name.isEmpty() ? "Target GP" : name);
		goalTitleLabel.setFont(FontManager.getRunescapeFont());

		// Top row: Title on left, Edit/Clear buttons on right
		JPanel topRow = new JPanel(new BorderLayout(4, 0));
		topRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		topRow.add(goalTitleLabel, BorderLayout.WEST);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		actions.setOpaque(false);
		actions.add(miniEditGoalButton);
		actions.add(miniClearGoalButton);
		topRow.add(actions, BorderLayout.EAST);

		c.insets = new Insets(0, 0, 4, 0);
		card.add(topRow, c);
		c.gridy++;

		// Slim progress bar
		goalProgressBar.setPreferredSize(new Dimension(0, 14));
		goalProgressBar.setMinimumSize(new Dimension(0, 14));
		c.insets = new Insets(0, 0, 4, 0);
		card.add(goalProgressBar, c);
		c.gridy++;

		// Compact bottom row: Rem on left, ETA on right
		JPanel bottomRow = new JPanel(new BorderLayout(6, 0));
		bottomRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JPanel remPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		remPanel.setOpaque(false);
		JLabel remCap = new JLabel("Rem: ");
		remCap.setFont(FontManager.getRunescapeSmallFont());
		remCap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		goalRemainingValue.setFont(FontManager.getRunescapeSmallFont());
		remPanel.add(remCap);
		remPanel.add(goalRemainingValue);

		JPanel etaPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		etaPanel.setOpaque(false);
		JLabel etaCap = new JLabel("ETA: ");
		etaCap.setFont(FontManager.getRunescapeSmallFont());
		etaCap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		goalEtaValue.setFont(FontManager.getRunescapeSmallFont());
		etaPanel.add(etaCap);
		etaPanel.add(goalEtaValue);

		bottomRow.add(remPanel, BorderLayout.WEST);
		bottomRow.add(etaPanel, BorderLayout.EAST);

		c.insets = new Insets(0, 0, 0, 0);
		card.add(bottomRow, c);
		c.gridy++;

		CoinFlowSession session = plugin.getSession();
		if (session != null)
		{
			applySessionToGoal(session, goalAmount);
		}
	}

	// ── Activity Timeline Card ───────────────────────────────────────────

	/** Max segment rows rendered; earlier ones collapse into a footer count. */
	private static final int MAX_SEGMENT_ROWS = 10;

	void refreshTimelineCardView()
	{
		timelineCard.removeAll();
		timelineCard.setLayout(new GridBagLayout());
		timelineCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		activityRows.clear();

		boolean show = config.showTimeline() && plugin.activityTimeline != null;
		lastShowTimeline = show;
		lastTimelineRevision = plugin.activityTimeline != null ? plugin.activityTimeline.getRevision() : -1;
		timelineCard.setVisible(show);
		if (!show)
		{
			timelineCard.revalidate();
			timelineCard.repaint();
			return;
		}

		boolean compact = config.compactMode();
		boolean collapsed = config.timelineCardCollapsed();
		boolean trackSpent = config.trackSpent();
		lastTimelineCardCollapsed = collapsed;

		timelineCard.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 1, 1, 1, ColorScheme.DARK_GRAY_HOVER_COLOR),
			new EmptyBorder(compact ? 6 : 8, compact ? 6 : 8, compact ? 6 : 8, compact ? 6 : 8)
		));

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1.0;
		c.gridx = 0;
		c.gridy = 0;

		List<ActivityTimeline.Segment> segments = plugin.activityTimeline != null
			? plugin.activityTimeline.getSegments() : Collections.emptyList();
		ActivityTimeline.Segment current = plugin.activityTimeline != null
			? plugin.activityTimeline.getCurrentSegment() : null;

		timelineCollapsedPreviewLabel.setFont(FontManager.getRunescapeSmallFont());
		timelineCollapsedPreviewLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		timelineCollapsedPreviewLabel.setText(current != null ? current.label : "No activity");

		JPanel headerRow = buildCardHeaderRow("Profit by Activity",
			"<html>GP earned and spent, split by what you were doing<br>"
				+ "(each monster, skill, bank trip). The highlighted row is what you're doing now.<br>"
				+ "Timeline: each stint, newest first. Totals: everything summed per activity.</html>",
			collapsed, timelineCollapsedPreviewLabel, () -> {
			boolean next = !config.timelineCardCollapsed();
			configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "timelineCardCollapsed", next);
			refreshTimelineCardView();
			CoinFlowSession s = plugin.getSession();
			if (s != null)
			{
				updateSessionUI(s);
			}
		});

		c.insets = new Insets(0, 0, collapsed ? 0 : (compact ? 6 : 8), 0);
		timelineCard.add(headerRow, c);
		c.gridy++;

		if (!collapsed)
		{
			timelineContainer.removeAll();
			timelineContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);

			List<RowStats> stats = currentRowStats(segments);
			if (stats.isEmpty())
			{
				// Nothing to report — no segments at all, or only the
				// bootstrap "Other" bucket which hides while zero-net.
				timelineContainer.setLayout(new BorderLayout());
				JLabel emptyLabel = new JLabel("<html><div style='text-align:center'>No activity yet<br>"
					+ "Fight, skill, or bank to see<br>the GP from each activity.</div></html>", SwingConstants.CENTER);
				emptyLabel.setFont(FontManager.getRunescapeSmallFont());
				emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				emptyLabel.setBorder(new EmptyBorder(compact ? 4 : 8, 0, compact ? 4 : 8, 0));
				timelineContainer.add(emptyLabel, BorderLayout.CENTER);
			}
			else
			{
				timelineContainer.setLayout(new DynamicGridLayout(0, 1, 0, compact ? 2 : 4));
				timelineContainer.add(buildTimelineViewBar());

				long totalSecs = totalSeconds(stats);
				int shown = Math.min(stats.size(), MAX_SEGMENT_ROWS);
				for (int i = 0; i < shown; i++)
				{
					timelineContainer.add(buildActivityRow(stats.get(i), compact, trackSpent, totalSecs));
				}
				int hidden = stats.size() - shown;
				if (hidden > 0)
				{
					String noun = hidden == 1 ? "activity" : "activities";
					JLabel moreLabel = new JLabel(timelineTotalsView
						? "+" + hidden + " more " + noun
						: "+" + hidden + " earlier " + noun, SwingConstants.CENTER);
					moreLabel.setFont(FontManager.getRunescapeSmallFont());
					moreLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					timelineContainer.add(moreLabel);
				}
			}

			c.insets = new Insets(0, 0, 0, 0);
			timelineCard.add(timelineContainer, c);
			c.gridy++;
		}

		timelineCard.revalidate();
		timelineCard.repaint();
		revalidate();
		repaint();
	}

	private JPanel buildTimelineViewBar()
	{
		JPanel bar = new JPanel(new GridLayout(1, 2, 4, 0));
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setPreferredSize(new Dimension(0, 20));
		bar.add(buildTimelineViewButton("Timeline", false,
			"Each stint as its own row, newest first"));
		bar.add(buildTimelineViewButton("Totals", true,
			"All stints of the same activity summed into one row, longest first"));
		return bar;
	}

	private JButton buildTimelineViewButton(String label, boolean totals, String tooltip)
	{
		JButton btn = new JButton(label);
		styleButton(btn);
		boolean active = timelineTotalsView == totals;
		btn.setForeground(active ? ACCENT_GOLD : ColorScheme.LIGHT_GRAY_COLOR);
		btn.setFont(active ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont());
		btn.setToolTipText(tooltip);
		btn.addActionListener(e -> {
			if (timelineTotalsView != totals)
			{
				timelineTotalsView = totals;
				refreshTimelineCardView();
			}
		});
		return btn;
	}

	/**
	 * Display rows for the active view, in display order: timeline is the
	 * segment list newest-first; totals collapses same-activity segments
	 * (longest total time first). The live flag marks the row containing the
	 * current segment. Zero-net "Other" rows are omitted — the bootstrap
	 * bucket has nothing to report until it carries a value.
	 */
	private List<RowStats> currentRowStats(List<ActivityTimeline.Segment> segments)
	{
		List<RowStats> rows = new ArrayList<>();
		if (segments.isEmpty())
		{
			return rows;
		}
		ActivityTimeline.Segment current = plugin.activityTimeline.getCurrentSegment();
		if (!timelineTotalsView)
		{
			for (int i = segments.size() - 1; i >= 0; i--)
			{
				RowStats row = RowStats.of(segments.get(i), segments.get(i).live);
				if (!isEmptyOther(row))
				{
					rows.add(row);
				}
			}
			return rows;
		}

		Map<String, Integer> visits = new HashMap<>();
		for (ActivityTimeline.Segment seg : segments)
		{
			visits.merge(RowStats.key(seg.kind, seg.label), 1, Integer::sum);
		}
		String liveKey = current != null ? RowStats.key(current.kind, current.label) : null;
		for (ActivityTimeline.Aggregate agg : plugin.activityTimeline.aggregateByLabel())
		{
			String key = RowStats.key(agg.kind, agg.label);
			RowStats row = RowStats.of(agg, visits.getOrDefault(key, 1), liveKey != null && key.equals(liveKey));
			if (!isEmptyOther(row))
			{
				rows.add(row);
			}
		}
		return rows;
	}

	/**
	 * "Other" is the session's bootstrap bucket — time before any activity
	 * signal confirms. A zero-net row is pure noise, so it stays hidden
	 * until it carries a positive or negative GP value.
	 */
	private static boolean isEmptyOther(RowStats stats)
	{
		return stats.kind == ActivityTimeline.Kind.OTHER && stats.net() == 0;
	}

	private static long totalSeconds(List<RowStats> rows)
	{
		long secs = 0;
		for (RowStats r : rows)
		{
			secs += r.totalTime.getSeconds();
		}
		return secs;
	}

	private static double timeShare(RowStats stats, long totalSecs)
	{
		return totalSecs > 0 ? Math.min(1.0, (double) stats.totalTime.getSeconds() / totalSecs) : 0;
	}

	private JPanel buildActivityRow(RowStats stats, boolean compact, boolean trackSpent, long totalSecs)
	{
		Color kindColor = kindColor(stats.kind);
		ShareBarPanel row = new ShareBarPanel(new BorderLayout(compact ? 4 : 6, 0),
			new Color(kindColor.getRed(), kindColor.getGreen(), kindColor.getBlue(), SHARE_BAR_ALPHA));
		row.setBackground(stats.live ? ColorScheme.DARK_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, KIND_STRIPE_WIDTH, 0, 0, kindColor),
			new EmptyBorder(compact ? 2 : 3, compact ? 3 : 4, compact ? 2 : 3, compact ? 3 : 4)));
		row.setShare(timeShare(stats, totalSecs));

		JLabel iconLabel = new JLabel(activityIcon(stats.kind, stats.label));
		iconLabel.setPreferredSize(new Dimension(ACTIVITY_ICON_SIZE, 0));
		row.add(iconLabel, BorderLayout.WEST);

		JPanel textPanel = new JPanel(new GridLayout(compact ? 1 : 2, 1, 0, 1));
		textPanel.setOpaque(false);

		JLabel nameLabel = new JLabel(stats.label);
		nameLabel.setFont(compact ? FontManager.getRunescapeSmallFont() : FontManager.getRunescapeFont());
		nameLabel.setForeground(stats.live ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		textPanel.add(nameLabel);

		boolean includeAfk = config.includeAfkTime();
		JLabel detailLabel = null;
		if (!compact)
		{
			detailLabel = new JLabel(activityDetailText(stats, includeAfk));
			detailLabel.setFont(FontManager.getRunescapeSmallFont());
			detailLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			textPanel.add(detailLabel);
		}
		row.add(textPanel, BorderLayout.CENTER);

		JLabel valueLabel = new JLabel(formatSegmentNet(stats.net()), SwingConstants.RIGHT);
		valueLabel.setFont(compact ? FontManager.getRunescapeSmallFont() : FontManager.getRunescapeFont());
		valueLabel.setForeground(segmentNetColor(stats.net()));
		row.add(valueLabel, BorderLayout.EAST);

		row.setToolTipText(activityTooltip(stats, trackSpent, includeAfk, totalSecs));
		activityRows.add(new ActivityRow(stats.key(), row, detailLabel, valueLabel));
		return row;
	}

	/**
	 * Segments shorter than this show no gp/hr: a 1-minute bank trip
	 * extrapolates to absurd hourly rates that read as noise.
	 */
	private static final long MIN_RATE_SECONDS = 120;
	private static final int KIND_STRIPE_WIDTH = 3;
	private static final int ACTIVITY_ICON_SIZE = 16;
	private static final int SHARE_BAR_ALPHA = 34;

	private static Color kindColor(ActivityTimeline.Kind kind)
	{
		switch (kind)
		{
			case COMBAT:
				return new Color(0xC8, 0x46, 0x3C);
			case SKILLING:
				return new Color(0x46, 0xA8, 0x5A);
			case BLAST_FURNACE:
				return new Color(0xD8, 0x8A, 0x2C);
			case BANKING:
				return new Color(0x4C, 0x8C, 0xC8);
			case TELEPORT:
				return new Color(0x9B, 0x5C, 0xC8);
			default:
				return ColorScheme.MEDIUM_GRAY_COLOR;
		}
	}

	/**
	 * Skill icon for the row: the trained skill for skilling, Attack's sword
	 * for combat, Smithing for Blast Furnace. Banking/AFK/Other get none (the
	 * label column still reserves the icon width so names stay aligned).
	 */
	private ImageIcon activityIcon(ActivityTimeline.Kind kind, String label)
	{
		Skill skill;
		switch (kind)
		{
			case COMBAT:
				skill = Skill.ATTACK;
				break;
			case BLAST_FURNACE:
				skill = Skill.SMITHING;
				break;
			case SKILLING:
				skill = skillByName(label);
				break;
			case TELEPORT:
				skill = Skill.MAGIC;
				break;
			default:
				skill = null;
		}
		if (skill == null || plugin.skillIconManager == null)
		{
			return null;
		}
		return skillIcons.computeIfAbsent(skill, s -> {
			BufferedImage img = plugin.skillIconManager.getSkillImage(s, true);
			return img != null ? new ImageIcon(ImageUtil.resizeImage(img, ACTIVITY_ICON_SIZE, ACTIVITY_ICON_SIZE)) : null;
		});
	}

	private static Skill skillByName(String name)
	{
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equals(name))
			{
				return skill;
			}
		}
		return null;
	}

	/** Banking/AFK rows never show a rate — an hourly figure for either is meaningless. */
	private static boolean showsRate(RowStats stats)
	{
		return stats.kind != ActivityTimeline.Kind.BANKING && stats.kind != ActivityTimeline.Kind.AFK
			&& stats.kind != ActivityTimeline.Kind.CORRECTION
			&& stats.totalTime.getSeconds() >= MIN_RATE_SECONDS;
	}

	private static String formatSegmentNet(long net)
	{
		if (net == 0)
		{
			return "0 gp";
		}
		return (net < 0 ? "-" : "+") + QuantityFormatter.formatNumber(Math.abs(net)) + " gp";
	}

	private static Color segmentNetColor(long net)
	{
		if (net == 0)
		{
			return ColorScheme.LIGHT_GRAY_COLOR;
		}
		return net > 0 ? PROFIT_GREEN : WARN_ORANGE;
	}

	/**
	 * One-line row detail, most useful figure first so the label's tail
	 * truncation only ever drops the least important part: combat leads with
	 * kills and gp/kill (gp/hr lives in the tooltip), other activities show
	 * gp/hr only once the row is long enough for the rate to mean anything.
	 */
	private static String activityDetailText(RowStats stats, boolean includeAfk)
	{
		if (stats.kind == ActivityTimeline.Kind.CORRECTION)
		{
			return "Manual adjustment";
		}
		StringBuilder sb = new StringBuilder(formatDuration(stats.totalTime));
		if (stats.kind == ActivityTimeline.Kind.COMBAT && stats.kills > 0)
		{
			sb.append(" · ").append(stats.kills).append(stats.kills == 1 ? " kill" : " kills")
				.append(" · ").append(QuantityFormatter.quantityToStackSize(stats.netPerKill())).append("/kill");
		}
		else if (showsRate(stats))
		{
			sb.append(" · ").append(QuantityFormatter.quantityToStackSize(stats.gpPerHour(includeAfk))).append(" gp/hr");
		}
		return sb.toString();
	}

	/** Full breakdown on hover — everything the one-line detail has to leave out. */
	private static String activityTooltip(RowStats stats, boolean trackSpent, boolean includeAfk, long totalSecs)
	{
		StringBuilder sb = new StringBuilder("<html><b>").append(stats.label).append("</b>");
		if (stats.live)
		{
			sb.append(" (now)");
		}
		if (stats.kind == ActivityTimeline.Kind.CORRECTION)
		{
			sb.append("<br>Manual correction to session totals");
		}
		else
		{
			sb.append("<br>Time: ").append(formatDuration(stats.totalTime));
			if (stats.activeTime.compareTo(stats.totalTime) < 0)
			{
				sb.append(" (").append(formatDuration(stats.activeTime)).append(" active)");
			}
			sb.append(", ").append(Math.round(timeShare(stats, totalSecs) * 100)).append("% of session");
		}
		if (stats.visits > 1)
		{
			sb.append("<br>Stints: ").append(stats.visits);
		}
		sb.append("<br>Earned: ").append(QuantityFormatter.formatNumber(stats.gross)).append(" gp");
		if (trackSpent)
		{
			sb.append("<br>Spent: ").append(QuantityFormatter.formatNumber(stats.expenses)).append(" gp");
		}
		sb.append("<br>Net: ").append(formatSegmentNet(stats.net()));
		if (showsRate(stats))
		{
			sb.append("<br>Rate: ").append(QuantityFormatter.quantityToStackSize(stats.gpPerHour(includeAfk))).append(" gp/hr");
		}
		if (stats.kind == ActivityTimeline.Kind.COMBAT && stats.kills > 0)
		{
			sb.append("<br>Kills: ").append(stats.kills)
				.append(" (").append(stats.killsPerHour(includeAfk)).append("/hr)")
				.append("<br>Per kill: ").append(QuantityFormatter.quantityToStackSize(stats.netPerKill())).append(" gp net");
			if (trackSpent && stats.expenses > 0)
			{
				sb.append(", ").append(QuantityFormatter.quantityToStackSize(stats.expensesPerKill())).append(" gp supplies");
			}
		}
		return sb.append("</html>").toString();
	}

	/**
	 * Per-tick refresh of the rendered rows' numbers, tooltips and share bars
	 * plus the collapsed preview — no structural rebuild, which only happens
	 * when the timeline revision changes. Rows match by activity key (totals
	 * re-sorts by time, so position isn't stable there) or by position in the
	 * timeline view, where duplicate keys are expected.
	 */
	void updateLiveSegmentRow()
	{
		if (plugin.activityTimeline == null)
		{
			return;
		}
		List<ActivityTimeline.Segment> segments = plugin.activityTimeline.getSegments();
		if (segments.isEmpty())
		{
			return;
		}
		ActivityTimeline.Segment current = plugin.activityTimeline.getCurrentSegment();
		timelineCollapsedPreviewLabel.setText(current != null ? current.label : "No activity");

		List<RowStats> stats = currentRowStats(segments);
		// A zero-net "Other" row gaining or losing its first value changes the
		// rendered row set without a timeline revision bump — rebuild so the
		// row appears or disappears instead of going stale.
		if (activityRows.size() != Math.min(stats.size(), MAX_SEGMENT_ROWS))
		{
			refreshTimelineCardView();
			return;
		}
		if (activityRows.isEmpty())
		{
			return;
		}
		long totalSecs = totalSeconds(stats);
		Map<String, RowStats> byKey = new HashMap<>();
		if (timelineTotalsView)
		{
			for (RowStats s : stats)
			{
				byKey.put(s.key(), s);
			}
		}
		boolean includeAfk = config.includeAfkTime();
		boolean trackSpent = config.trackSpent();
		for (int i = 0; i < activityRows.size(); i++)
		{
			ActivityRow row = activityRows.get(i);
			RowStats s = timelineTotalsView ? byKey.get(row.key) : (i < stats.size() ? stats.get(i) : null);
			if (s == null || !s.key().equals(row.key))
			{
				continue;
			}
			row.valueLabel.setText(formatSegmentNet(s.net()));
			row.valueLabel.setForeground(segmentNetColor(s.net()));
			if (row.detailLabel != null)
			{
				row.detailLabel.setText(activityDetailText(s, includeAfk));
			}
			row.panel.setToolTipText(activityTooltip(s, trackSpent, includeAfk, totalSecs));
			row.panel.setShare(timeShare(s, totalSecs));
		}
	}

	/** View model shared by the timeline (one segment) and totals (one aggregate) rows. */
	private static final class RowStats
	{
		final ActivityTimeline.Kind kind;
		final String label;
		final Duration activeTime;
		final Duration totalTime;
		final long gross;
		final long expenses;
		final int kills;
		final int visits;
		final boolean live;

		private RowStats(ActivityTimeline.Kind kind, String label, Duration activeTime, Duration totalTime,
			long gross, long expenses, int kills, int visits, boolean live)
		{
			this.kind = kind;
			this.label = label;
			this.activeTime = activeTime;
			this.totalTime = totalTime;
			this.gross = gross;
			this.expenses = expenses;
			this.kills = kills;
			this.visits = visits;
			this.live = live;
		}

		static RowStats of(ActivityTimeline.Segment s, boolean live)
		{
			return new RowStats(s.kind, s.label, s.activeTime, s.totalTime, s.gross, s.expenses, s.kills, 1, live);
		}

		static RowStats of(ActivityTimeline.Aggregate a, int visits, boolean live)
		{
			return new RowStats(a.kind, a.label, a.activeTime, a.totalTime, a.gross, a.expenses, a.kills, visits, live);
		}

		static String key(ActivityTimeline.Kind kind, String label)
		{
			return kind + "|" + label;
		}

		String key()
		{
			return key(kind, label);
		}

		long net()
		{
			return gross - expenses;
		}

		private long effectiveSeconds(boolean includeAfk)
		{
			return (includeAfk ? totalTime : activeTime).getSeconds();
		}

		long gpPerHour(boolean includeAfk)
		{
			long secs = effectiveSeconds(includeAfk);
			return secs > 0 ? net() * 3600 / secs : 0;
		}

		long killsPerHour(boolean includeAfk)
		{
			long secs = effectiveSeconds(includeAfk);
			return secs > 0 ? kills * 3600L / secs : 0;
		}

		long netPerKill()
		{
			return kills > 0 ? net() / kills : 0;
		}

		long expensesPerKill()
		{
			return kills > 0 ? expenses / kills : 0;
		}
	}

	/** A rendered row plus the labels the per-tick refresh rewrites in place. */
	private static final class ActivityRow
	{
		final String key;
		final ShareBarPanel panel;
		final JLabel detailLabel;
		final JLabel valueLabel;

		ActivityRow(String key, ShareBarPanel panel, JLabel detailLabel, JLabel valueLabel)
		{
			this.key = key;
			this.panel = panel;
			this.detailLabel = detailLabel;
			this.valueLabel = valueLabel;
		}
	}

	/**
	 * Row background with a faint bar filled to the activity's share of
	 * session time, tinted by activity kind — where the time went, readable
	 * without parsing any numbers.
	 */
	/**
	 * Minimal cumulative-profit line: no axes or labels, a faint zero line
	 * when the series crosses zero, stroke coloured by the latest value.
	 * Repaints only when the snapshot revision changes.
	 */
	static final class SparklinePanel extends JPanel
	{
		static final int HEIGHT = 28;
		private static final int PAD_Y = 2;

		private ProfitHistory.Snapshot snapshot = ProfitHistory.Snapshot.EMPTY;

		SparklinePanel()
		{
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setPreferredSize(new Dimension(0, HEIGHT));
			setMinimumSize(new Dimension(0, HEIGHT));
		}

		void setSnapshot(ProfitHistory.Snapshot next)
		{
			if (next != null && next.revision != snapshot.revision)
			{
				snapshot = next;
				repaint();
			}
		}

		ProfitHistory.Snapshot getSnapshot()
		{
			return snapshot;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			long[] values = snapshot.values;
			if (values.length < 2)
			{
				return;
			}

			int w = getWidth();
			int h = getHeight();
			int plotH = h - 2 * PAD_Y;
			if (w <= 1 || plotH <= 0)
			{
				return;
			}

			long min = snapshot.min;
			long max = snapshot.max;
			double range = max - min;
			Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

				if (min < 0 && max > 0)
				{
					int zeroY = PAD_Y + (int) Math.round(plotH * (max / range));
					g2.setColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
					g2.drawLine(0, zeroY, w, zeroY);
				}

				Path2D.Double path = new Path2D.Double();
				double xStep = (double) (w - 1) / (values.length - 1);
				for (int i = 0; i < values.length; i++)
				{
					double x = i * xStep;
					// Flat series (range 0) sits on the vertical centre line.
					double y = range == 0
						? PAD_Y + plotH / 2.0
						: PAD_Y + plotH * ((max - values[i]) / range);
					if (i == 0)
					{
						path.moveTo(x, y);
					}
					else
					{
						path.lineTo(x, y);
					}
				}
				g2.setColor(values[values.length - 1] >= 0 ? PROFIT_GREEN : WARN_ORANGE);
				g2.setStroke(new BasicStroke(1.5f));
				g2.draw(path);
			}
			finally
			{
				g2.dispose();
			}
		}
	}

	private static final class ShareBarPanel extends JPanel
	{
		private final Color barColor;
		private double share;

		ShareBarPanel(java.awt.LayoutManager layout, Color barColor)
		{
			super(layout);
			this.barColor = barColor;
		}

		void setShare(double share)
		{
			if (Math.abs(this.share - share) > 0.001)
			{
				this.share = share;
				repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			int barWidth = (int) Math.round((getWidth() - KIND_STRIPE_WIDTH) * share);
			if (barWidth > 0)
			{
				g.setColor(barColor);
				g.fillRect(KIND_STRIPE_WIDTH, 0, barWidth, getHeight());
			}
		}
	}

	void refreshItemsCardView()
	{
		itemsCard.removeAll();
		itemsCard.setLayout(new GridBagLayout());
		itemsCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		boolean show = config.showItemBreakdown();
		lastShowItemBreakdown = show;
		itemsCard.setVisible(show);
		if (!show)
		{
			itemsCard.revalidate();
			itemsCard.repaint();
			return;
		}

		boolean compact = config.compactMode();
		boolean collapsed = config.itemsCardCollapsed();
		lastItemsCardCollapsed = collapsed;

		itemsCard.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 1, 1, 1, ColorScheme.DARK_GRAY_HOVER_COLOR),
			new EmptyBorder(compact ? 6 : 8, compact ? 6 : 8, compact ? 6 : 8, compact ? 6 : 8)
		));

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1.0;
		c.gridx = 0;
		c.gridy = 0;

		CoinFlowSession session = plugin.getSession();
		boolean trackSpent = config.trackSpent();
		List<CoinFlowSession.TrackedItem> items = session != null ? session.getSortedItems() : Collections.emptyList();
		List<CoinFlowSession.TrackedItem> expenses = (session != null && trackSpent) ? session.getSortedExpenses() : Collections.emptyList();
		lastRenderedItems = session != null ? session.getTrackedItems() : Collections.emptyMap();
		lastRenderedExpenses = (session != null && trackSpent) ? session.getTrackedExpenses() : Collections.emptyMap();
		int totalItemCount = items.size() + expenses.size();

		itemsCollapsedPreviewLabel.setFont(FontManager.getRunescapeSmallFont());
		if (totalItemCount > 0)
		{
			itemsCollapsedPreviewLabel.setText(totalItemCount + (totalItemCount == 1 ? " item" : " items"));
			itemsCollapsedPreviewLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else
		{
			itemsCollapsedPreviewLabel.setText("No items");
			itemsCollapsedPreviewLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}

		JPanel headerRow = buildCardHeaderRow("Item Breakdown", collapsed, itemsCollapsedPreviewLabel, () -> {
			boolean next = !config.itemsCardCollapsed();
			configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "itemsCardCollapsed", next);
			refreshItemsCardView();
			CoinFlowSession s = plugin.getSession();
			if (s != null)
			{
				updateSessionUI(s);
			}
		});

		c.insets = new Insets(0, 0, collapsed ? 0 : (compact ? 6 : 8), 0);
		itemsCard.add(headerRow, c);
		c.gridy++;

		if (!collapsed)
		{
			itemsContainer.removeAll();
			itemsContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);

			// Without spent tracking there are no expense rows to filter; force ALL.
			ItemFilter filter = trackSpent ? itemFilter : ItemFilter.ALL;
			boolean showLoot = filter != ItemFilter.SPENT;
			boolean showSpent = filter != ItemFilter.LOOT && trackSpent;
			int shownCount = (showLoot ? items.size() : 0) + (showSpent ? expenses.size() : 0);

			if (trackSpent)
			{
				c.insets = new Insets(0, 0, compact ? 4 : 6, 0);
				itemsCard.add(buildItemFilterBar(items.size(), expenses.size()), c);
				c.gridy++;
			}

			if (shownCount == 0)
			{
				itemsContainer.setLayout(new BorderLayout());
				String emptyText = filter == ItemFilter.LOOT ? "No loot tracked this session"
					: filter == ItemFilter.SPENT ? "No expenses tracked this session"
					: "No items tracked this session";
				JLabel emptyLabel = new JLabel(emptyText, SwingConstants.CENTER);
				emptyLabel.setFont(FontManager.getRunescapeSmallFont());
				emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				emptyLabel.setBorder(new EmptyBorder(compact ? 4 : 8, 0, compact ? 4 : 8, 0));
				itemsContainer.add(emptyLabel, BorderLayout.CENTER);
			}
			else
			{
				itemsContainer.setLayout(new DynamicGridLayout(0, 1, 0, compact ? 2 : 4));
				if (showLoot)
				{
					for (CoinFlowSession.TrackedItem item : items)
					{
						itemsContainer.add(buildItemRow(item, compact, false));
					}
				}
				if (showSpent)
				{
					for (CoinFlowSession.TrackedItem expense : expenses)
					{
						itemsContainer.add(buildItemRow(expense, compact, true));
					}
				}
			}

			c.insets = new Insets(0, 0, 0, 0);
			itemsCard.add(itemsContainer, c);
			c.gridy++;
		}

		itemsCard.revalidate();
		itemsCard.repaint();
		revalidate();
		repaint();
	}

	private JPanel buildItemRow(CoinFlowSession.TrackedItem item, boolean compact, boolean isExpense)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(compact ? 2 : 3, compact ? 3 : 4, compact ? 2 : 3, compact ? 3 : 4));

		// Left: Item icon (standard 32x32 RuneLite sprite)
		JLabel iconLabel = new JLabel();
		iconLabel.setPreferredSize(new Dimension(32, 32));
		iconLabel.setMinimumSize(new Dimension(32, 32));
		iconLabel.setHorizontalAlignment(SwingConstants.LEFT);
		iconLabel.setVerticalAlignment(SwingConstants.CENTER);
		if (itemManager != null)
		{
			// Synthetic GP-denominated rows (Death, GE Tax, Blast Furnace
			// coffer) carry gp as their quantity; draw the icon once rather
			// than painting a meaningless stack count.
			boolean gpRow = CoinFlowSession.isSyntheticRowId(item.getItemId());
			AsyncBufferedImage image = gpRow
				? itemManager.getImage(item.getItemId())
				: itemManager.getImage(item.getItemId(), (int) Math.min(Integer.MAX_VALUE, item.getQuantity()), item.getQuantity() > 1);
			if (image != null)
			{
				image.addTo(iconLabel);
			}
		}
		row.add(iconLabel, BorderLayout.WEST);

		// Center: Name and quantity / unit price
		JPanel textPanel = new JPanel(new GridLayout(compact ? 1 : 2, 1, 0, 1));
		textPanel.setOpaque(false);

		JLabel nameLabel = new JLabel(item.getName());
		nameLabel.setFont(compact ? FontManager.getRunescapeSmallFont() : FontManager.getRunescapeFont());
		nameLabel.setForeground(Color.WHITE);
		textPanel.add(nameLabel);

		if (!compact)
		{
			JLabel detailLabel = new JLabel(
				QuantityFormatter.formatNumber(item.getQuantity()) + " x " +
				QuantityFormatter.formatNumber(item.getPriceEach()) + " gp" +
				(isExpense ? " (spent)" : "")
			);
			detailLabel.setFont(FontManager.getRunescapeSmallFont());
			detailLabel.setForeground(isExpense ? WARN_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
			textPanel.add(detailLabel);
		}

		row.add(textPanel, BorderLayout.CENTER);

		// Right: Total value
		String sign = isExpense ? "-" : "+";
		Color valueColor = isExpense ? WARN_ORANGE : PROFIT_GREEN;
		String valueStr = compact
			? QuantityFormatter.quantityToStackSize(item.getTotalValue()) + " gp"
			: QuantityFormatter.formatNumber(item.getTotalValue()) + " gp";

		JLabel valueLabel = new JLabel(
			sign + valueStr,
			SwingConstants.RIGHT
		);
		valueLabel.setFont(compact ? FontManager.getRunescapeSmallFont() : FontManager.getRunescapeFont());
		valueLabel.setForeground(valueColor);
		row.add(valueLabel, BorderLayout.EAST);

		// Tooltip
		String tooltipType = isExpense ? "Expense (supply consumed)" : "Loot / Gain";
		String tooltip = String.format(
			"<html><b>%s</b> <i>(%s)</i><br>Quantity: %s<br>Price each: %s gp<br>Total: %s%s gp</html>",
			item.getName(),
			tooltipType,
			QuantityFormatter.formatNumber(item.getQuantity()),
			QuantityFormatter.formatNumber(item.getPriceEach()),
			sign,
			QuantityFormatter.formatNumber(item.getTotalValue())
		);
		row.setToolTipText(tooltip);
		iconLabel.setToolTipText(tooltip);
		nameLabel.setToolTipText(tooltip);
		valueLabel.setToolTipText(tooltip);

		// Mouse hover background highlight
		Color normalBg = ColorScheme.DARKER_GRAY_COLOR;
		Color hoverBg = ColorScheme.DARK_GRAY_HOVER_COLOR;
		MouseAdapter hoverAdapter = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(hoverBg);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(normalBg);
			}
		};
		row.addMouseListener(hoverAdapter);
		iconLabel.addMouseListener(hoverAdapter);
		nameLabel.addMouseListener(hoverAdapter);
		valueLabel.addMouseListener(hoverAdapter);

		JPopupMenu popup = createItemPopupMenu(item, isExpense);
		row.setComponentPopupMenu(popup);
		iconLabel.setComponentPopupMenu(popup);
		nameLabel.setComponentPopupMenu(popup);
		valueLabel.setComponentPopupMenu(popup);
		textPanel.setComponentPopupMenu(popup);

		return row;
	}

	/**
	 * True for synthetic GP-denominated rows ("Death", "GE Tax", "Blast
	 * Furnace coffer") that are not real inventory items — their underlying
	 * ids (Skull, coin piles) must never be offered to the ignored-items list.
	 */
	static boolean isSyntheticRow(CoinFlowSession.TrackedItem item)
	{
		return CoinFlowSession.isSyntheticRowId(item.getItemId());
	}

	JPanel buildItemFilterBar(int lootCount, int spentCount)
	{
		JPanel bar = new JPanel(new GridLayout(1, 3, 4, 0));
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setPreferredSize(new Dimension(0, 22));
		bar.add(buildFilterButton("All", lootCount + spentCount, ItemFilter.ALL, ACCENT_GOLD));
		bar.add(buildFilterButton("Loot", lootCount, ItemFilter.LOOT, PROFIT_GREEN));
		bar.add(buildFilterButton("Spent", spentCount, ItemFilter.SPENT, WARN_ORANGE));
		return bar;
	}

	private JButton buildFilterButton(String label, int count, ItemFilter filter, Color activeColor)
	{
		JButton btn = new JButton(label + " (" + count + ")");
		styleButton(btn);
		boolean active = itemFilter == filter;
		btn.setForeground(active ? activeColor : ColorScheme.LIGHT_GRAY_COLOR);
		btn.setFont(active ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont());
		btn.addActionListener(e -> {
			if (itemFilter != filter)
			{
				itemFilter = filter;
				refreshItemsCardView();
			}
		});
		return btn;
	}

	JPopupMenu createItemPopupMenu(CoinFlowSession.TrackedItem item, boolean isExpense)
	{
		JPopupMenu popup = new JPopupMenu();

		JMenuItem removeItem = new JMenuItem("Remove from Session");
		removeItem.addActionListener(e -> plugin.removeSessionItem(item.getItemId(), isExpense));
		popup.add(removeItem);

		if (!isSyntheticRow(item))
		{
			JMenuItem ignoreItem = new JMenuItem("Ignore " + ConsumableRegistry.canonicalIgnoreName(item.getName()));
			ignoreItem.addActionListener(e -> plugin.ignoreItem(item.getItemId(), item.getName()));
			popup.add(ignoreItem);
		}

		JMenuItem wikiItem = new JMenuItem("Open on OSRS Wiki");
		wikiItem.addActionListener(e -> openItemWiki(item));
		popup.add(wikiItem);

		return popup;
	}

	private void openItemWiki(CoinFlowSession.TrackedItem item)
	{
		LinkBrowser.browse(wikiUrlForItem(item));
	}

	static String wikiUrlForItem(CoinFlowSession.TrackedItem item)
	{
		if (item.getItemId() == DeathTracker.DEATH_ROW_ID)
		{
			return "https://oldschool.runescape.wiki/w/Death";
		}
		if (item.getItemId() == GrandExchangeTax.TAX_ITEM_ID)
		{
			return "https://oldschool.runescape.wiki/w/Grand_Exchange#Tax";
		}
		if (item.getItemId() == BlastFurnaceTracker.COFFER_ROW_ID)
		{
			return "https://oldschool.runescape.wiki/w/Blast_Furnace";
		}
		if (item.getItemId() == com.coinflow.reconciliation.BarrowsWear.WEAR_ROW_ID)
		{
			return "https://oldschool.runescape.wiki/w/Barrows_equipment#Degrading";
		}
		return "https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=" + item.getItemId();
	}

	private JPanel buildCardHeaderRow(String titleText, boolean collapsed, JLabel previewLabel, Runnable onToggle)
	{
		return buildCardHeaderRow(titleText, null, collapsed, previewLabel, onToggle);
	}

	private JPanel buildCardHeaderRow(String titleText, String titleTooltip, boolean collapsed, JLabel previewLabel, Runnable onToggle)
	{
		JPanel headerRow = new JPanel(new BorderLayout(6, 0));
		headerRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JLabel titleLabel = new JLabel(titleText);
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(ACCENT_GOLD);
		if (titleTooltip != null)
		{
			titleLabel.setToolTipText(titleTooltip);
			headerRow.setToolTipText(titleTooltip);
		}

		JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		rightPanel.setOpaque(false);

		if (collapsed && previewLabel != null)
		{
			rightPanel.add(previewLabel);
		}

		JLabel chevron = new JLabel(collapsed ? ">" : "v");
		chevron.setFont(FontManager.getRunescapeSmallFont());
		chevron.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		rightPanel.add(chevron);

		headerRow.add(titleLabel, BorderLayout.WEST);
		headerRow.add(rightPanel, BorderLayout.EAST);

		MouseAdapter ma = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onToggle.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				chevron.setForeground(Color.WHITE);
				titleLabel.setForeground(Color.WHITE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				chevron.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				titleLabel.setForeground(ACCENT_GOLD);
			}
		};

		headerRow.addMouseListener(ma);
		titleLabel.addMouseListener(ma);
		rightPanel.addMouseListener(ma);
		chevron.addMouseListener(ma);
		if (previewLabel != null)
		{
			for (MouseListener ml : previewLabel.getMouseListeners())
			{
				previewLabel.removeMouseListener(ml);
			}
			previewLabel.addMouseListener(ma);
		}

		return headerRow;
	}

	void onFinishSessionClicked()
	{
		promptResetSession();
	}

	void promptResetSession()
	{
		if (promptOpen)
		{
			return;
		}

		CoinFlowSession session = plugin != null ? plugin.session : null;
		boolean hasActivity = session != null && (session.getGrossProfit() != 0 || session.getTotalExpenses() != 0
			|| session.getTotalInGameTime().getSeconds() > 30);

		if (config != null && config.displaySummary() && hasActivity)
		{
			openSessionSummary(session);
			return;
		}

		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}

		promptOpen = true;
		try
		{
			if (confirmResetSession())
			{
				plugin.resetSession();
			}
		}
		finally
		{
			promptOpen = false;
		}
	}

	/**
	 * Shows the reset confirmation dialog. Extracted so tests can stub the
	 * modal answer instead of blocking on a real JOptionPane.
	 */
	boolean confirmResetSession()
	{
		return JOptionPane.showConfirmDialog(
			this,
			"Reset active Coin Flow session and counters?",
			"Reset Session",
			JOptionPane.YES_NO_OPTION
		) == JOptionPane.YES_OPTION;
	}

	void openSessionSummary(CoinFlowSession session)
	{
		SessionSummaryDialog.showDialog(this, session, config != null && config.trackSpent(),
			plugin.activityTimeline, config != null && config.includeAfkTime(), () -> plugin.resetSession());
	}

	private void buildGoalSetupSubpanel()
	{
		goalSetupPanel.setLayout(new GridBagLayout());
		goalSetupPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		goalSetupPanel.removeAll();

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1.0;
		c.gridx = 0;
		c.gridy = 0;

		// Attach input filters to reject special characters in real time
		((AbstractDocument) targetGpField.getDocument()).setDocumentFilter(
			new CoinFlowInputFilter(CoinFlowInputFilter.TARGET_GP, 15)
		);
		((AbstractDocument) goalNameField.getDocument()).setDocumentFilter(
			new CoinFlowInputFilter(CoinFlowInputFilter.GOAL_NAME, 32)
		);

		JLabel targetLabel = new JLabel("Target GP (e.g. 10m, 500k):");
		targetLabel.setFont(FontManager.getRunescapeSmallFont());
		targetLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		c.insets = new Insets(0, 0, 3, 0);
		goalSetupPanel.add(targetLabel, c);
		c.gridy++;

		targetGpField.setBackground(ColorScheme.DARK_GRAY_COLOR);
		targetGpField.setForeground(Color.WHITE);
		targetGpField.setCaretColor(Color.WHITE);
		targetGpField.setPreferredSize(new Dimension(0, 24));
		c.insets = new Insets(0, 0, 6, 0);
		goalSetupPanel.add(targetGpField, c);
		c.gridy++;

		JLabel nameLabel = new JLabel("Label (optional):");
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		c.insets = new Insets(0, 0, 3, 0);
		goalSetupPanel.add(nameLabel, c);
		c.gridy++;

		goalNameField.setBackground(ColorScheme.DARK_GRAY_COLOR);
		goalNameField.setForeground(Color.WHITE);
		goalNameField.setCaretColor(Color.WHITE);
		goalNameField.setPreferredSize(new Dimension(0, 24));
		c.insets = new Insets(0, 0, 6, 0);
		goalSetupPanel.add(goalNameField, c);
		c.gridy++;

		// Presets Row: [1M] [5M] [10M] [Bond]
		JPanel presetsPanel = new JPanel(new GridLayout(1, 4, 3, 0));
		presetsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		presetsPanel.setPreferredSize(new Dimension(0, 24));

		JButton p1m = createPresetButton("1M", () -> {
			targetGpField.setText("1,000,000");
			if (goalNameField.getText().trim().isEmpty()) goalNameField.setText("1M GP");
		});
		JButton p5m = createPresetButton("5M", () -> {
			targetGpField.setText("5,000,000");
			if (goalNameField.getText().trim().isEmpty()) goalNameField.setText("5M GP");
		});
		JButton p10m = createPresetButton("10M", () -> {
			targetGpField.setText("10,000,000");
			if (goalNameField.getText().trim().isEmpty()) goalNameField.setText("10M GP");
		});
		JButton pBond = createPresetButton("Bond", () -> {
			long bondPrice = itemManager.getItemPrice(ItemID.OSRS_BOND);
			if (bondPrice <= 0)
			{
				bondPrice = 13_000_000L;
			}
			targetGpField.setText(QuantityFormatter.formatNumber(bondPrice));
			goalNameField.setText("Old School Bond");
		});

		presetsPanel.add(p1m);
		presetsPanel.add(p5m);
		presetsPanel.add(p10m);
		presetsPanel.add(pBond);

		c.insets = new Insets(0, 0, 8, 0);
		goalSetupPanel.add(presetsPanel, c);
		c.gridy++;

		// Buttons: Set Goal / Cancel
		styleButton(setGoalButton);
		setGoalButton.addActionListener(e -> {
			try
			{
				String rawTarget = targetGpField.getText();
				long amount = parseGpText(rawTarget);
				String name = CoinFlowSession.cleanGoalName(goalNameField.getText());
				configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalAmount", QuantityFormatter.formatNumber(amount));
				configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, "goalName", name);
				editingGoal = false;
				refreshGoalCardView();
			}
			catch (NumberFormatException ex)
			{
				JOptionPane.showMessageDialog(
					this,
					ex.getMessage() != null && !ex.getMessage().isEmpty()
						? ex.getMessage()
						: "Please enter a valid positive GP amount (e.g., 5m, 500k, 10,000,000).",
					"Invalid Amount",
					JOptionPane.WARNING_MESSAGE
				);
			}
		});

		styleButton(cancelEditButton);
		cancelEditButton.addActionListener(e -> {
			editingGoal = false;
			refreshGoalCardView();
		});

		goalSetupButtonsRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		goalSetupButtonsRow.setPreferredSize(new Dimension(0, 26));

		c.insets = new Insets(0, 0, 0, 0);
		goalSetupPanel.add(goalSetupButtonsRow, c);
	}

	private void updateGoalSetupButtons()
	{
		goalSetupButtonsRow.removeAll();
		if (editingGoal)
		{
			goalSetupButtonsRow.setLayout(new GridLayout(1, 2, 6, 0));
			goalSetupButtonsRow.add(setGoalButton);
			goalSetupButtonsRow.add(cancelEditButton);
		}
		else
		{
			goalSetupButtonsRow.setLayout(new GridLayout(1, 1));
			goalSetupButtonsRow.add(setGoalButton);
		}
		goalSetupButtonsRow.revalidate();
		goalSetupButtonsRow.repaint();
	}

	// ── Live Session Updates ─────────────────────────────────────────────

	/**
	 * Updates panel components with the latest session data.
	 * Guards against background repaints when the tab is closed.
	 */
	public void updateSession(CoinFlowSession session)
	{
		if (session == null || !isShowing())
		{
			return;
		}

		SwingUtilities.invokeLater(() -> updateSessionUI(session));
	}

	private void updateSessionUI(CoinFlowSession session)
	{
		if (session == null)
		{
			return;
		}

		boolean includeAfk = config.includeAfkTime();
		boolean trackSpent = config.trackSpent();

		// Session card
		boolean worldPaused = plugin.isUnsupportedWorld();
		long totalProfit = trackSpent ? session.getTotalProfit() : session.getGrossProfit();
		sessionProfitCaption.setText(worldPaused ? "Paused — unsupported world" : (trackSpent ? "Net Profit" : "Profit"));
		sessionProfitCaption.setForeground(worldPaused ? WARN_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		sessionProfitLabel.setText((totalProfit < 0 ? "-" : "") + QuantityFormatter.formatNumber(Math.abs(totalProfit)) + " gp");
		sessionProfitLabel.setForeground(totalProfit >= 0 ? PROFIT_GREEN : WARN_ORANGE);
		boolean isIdle = session.isIdle();
		long rate = trackSpent ? session.getGpPerHour(includeAfk) : session.getGrossGpPerHour(includeAfk);
		sessionRateLabel.setText((rate < 0 ? "-" : "") + QuantityFormatter.formatNumber(Math.abs(rate)) + " gp/hr");
		sessionRateLabel.setForeground(!isIdle && rate >= 0 ? Color.WHITE : WARN_ORANGE);
		String timeStr = formatDuration(session.getTime(includeAfk)) + (isIdle ? (includeAfk ? " (idle)" : " (paused)") : "");
		sessionTimeLabel.setText(timeStr);
		sessionTimeLabel.setForeground(isIdle ? WARN_ORANGE : Color.WHITE);

		sessionCollapsedPreviewLabel.setText((totalProfit < 0 ? "-" : "") + QuantityFormatter.quantityToStackSize(Math.abs(totalProfit)) + " gp");
		sessionCollapsedPreviewLabel.setForeground(totalProfit >= 0 ? PROFIT_GREEN : WARN_ORANGE);

		if (sessionSparkline.getParent() != null)
		{
			sessionSparkline.setSnapshot(plugin.profitHistory.snapshot());
		}

		if (trackSpent)
		{
			sessionGrossLabel.setText("+" + QuantityFormatter.formatNumber(session.getGrossProfit()) + " gp");
			sessionGrossLabel.setForeground(PROFIT_GREEN);
			long expenses = session.getTotalExpenses();
			sessionSuppliesLabel.setText((expenses > 0 ? "-" : "") + QuantityFormatter.formatNumber(expenses) + " gp");
			sessionSuppliesLabel.setForeground(expenses > 0 ? WARN_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		}

		// Check if active goal state or collapse/compact state changed in config externally
		long goalAmount = CoinFlowSession.parseGoalAmount(config.goalAmount());
		boolean hasActiveGoal = goalAmount > 0;
		boolean compact = config.compactMode();
		boolean sessionCollapsed = config.sessionCardCollapsed();
		boolean goalCollapsed = config.goalCardCollapsed();

		boolean compactChanged = compact != lastCompactMode;
		boolean sessionCollapsedChanged = sessionCollapsed != lastSessionCollapsed;
		boolean trackSpentChanged = trackSpent != lastTrackSpent;

		if (compactChanged || sessionCollapsedChanged || trackSpentChanged)
		{
			lastCompactMode = compact;
			lastSessionCollapsed = sessionCollapsed;
			lastTrackSpent = trackSpent;
			refreshHeaderPanel();
			refreshSessionCardView();
			if (compactChanged)
			{
				refreshGoalCardView();
			}
		}

		String currentGoalName = CoinFlowSession.cleanGoalName(config.goalName());
		if (goalCollapsed != lastGoalCollapsed || hasActiveGoal != lastHadActiveGoal || !currentGoalName.equals(lastGoalName))
		{
			lastGoalCollapsed = goalCollapsed;
			lastHadActiveGoal = hasActiveGoal;
			lastGoalName = currentGoalName;
			refreshGoalCardView();
		}

		// Goal card
		if (goalAmount > 0 && !editingGoal)
		{
			applySessionToGoal(session, goalAmount);
		}
		else
		{
			goalCollapsedPreviewLabel.setText("No Goal");
			goalCollapsedPreviewLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}

		// Activity timeline card: structural rebuild only when the segment
		// list changes or display config flipped; otherwise update the live
		// row's labels in place.
		boolean showTimeline = config.showTimeline() && plugin.activityTimeline != null;
		boolean timelineCollapsed = config.timelineCardCollapsed();
		int timelineRevision = plugin.activityTimeline != null ? plugin.activityTimeline.getRevision() : -1;
		if (showTimeline != lastShowTimeline || timelineCollapsed != lastTimelineCardCollapsed
			|| timelineRevision != lastTimelineRevision || compactChanged || trackSpentChanged)
		{
			lastShowTimeline = showTimeline;
			lastTimelineCardCollapsed = timelineCollapsed;
			refreshTimelineCardView();
		}
		else if (showTimeline && !timelineCollapsed)
		{
			updateLiveSegmentRow();
		}
		else if (showTimeline)
		{
			ActivityTimeline.Segment cur = plugin.activityTimeline.getCurrentSegment();
			timelineCollapsedPreviewLabel.setText(cur != null ? cur.label : "No activity");
		}

		// Item breakdown card
		boolean showBreakdown = config.showItemBreakdown();
		boolean itemsCollapsed = config.itemsCardCollapsed();

		if (showBreakdown != lastShowItemBreakdown)
		{
			lastShowItemBreakdown = showBreakdown;
			refreshItemsCardView();
		}
		else if (showBreakdown)
		{
			if (itemsCollapsed != lastItemsCardCollapsed || compactChanged || trackSpentChanged)
			{
				lastItemsCardCollapsed = itemsCollapsed;
				refreshItemsCardView();
			}
			else
			{
				Map<Integer, CoinFlowSession.TrackedItem> currentItems = session.getTrackedItems();
				Map<Integer, CoinFlowSession.TrackedItem> currentExpenses = trackSpent ? session.getTrackedExpenses() : Collections.emptyMap();
				if (lastRenderedItems == null || lastRenderedExpenses == null
					|| !currentItems.equals(lastRenderedItems) || !currentExpenses.equals(lastRenderedExpenses))
				{
					lastRenderedItems = currentItems;
					lastRenderedExpenses = currentExpenses;
					refreshItemsCardView();
				}
			}
		}
	}

	private void applySessionToGoal(CoinFlowSession session, long goalAmount)
	{
		// goalDisplayTotal folds persisted cross-session progress into the live
		// session contribution (full target once the goal is marked complete).
		long goalTotal = plugin.goalDisplayTotal();
		double progress = session.getGoalProgress(goalAmount, goalTotal);
		int percent = (int) Math.round(progress * 100);
		goalProgressBar.setValue(percent);
		goalProgressBar.setString(percent + "%");

		boolean complete = progress >= 1.0;
		goalProgressBar.setForeground(complete ? GOAL_COMPLETE_GREEN : GOAL_IN_PROGRESS_ORANGE);

		goalProgressNumbers.setText(
			QuantityFormatter.quantityToStackSize(Math.max(0L, goalTotal)) + " / " +
			QuantityFormatter.quantityToStackSize(goalAmount) + " gp (" +
			String.format("%.1f%%", progress * 100) + ")"
		);

		long remaining = session.getGoalRemaining(goalAmount, goalTotal);
		goalRemainingValue.setText(QuantityFormatter.formatNumber(remaining) + " gp");

		// Blended cross-session rate — same ETA basis as the overlay.
		long etaSeconds = session.getGoalEtaSeconds(goalAmount, goalTotal, plugin.goalDisplaySeconds());
		goalEtaValue.setText(CoinFlowSession.formatGoalEta(etaSeconds));
		goalEtaValue.setForeground(complete ? PROFIT_GREEN : WARN_ORANGE);

		goalCollapsedPreviewLabel.setText(String.format("%.1f%%", progress * 100));
		goalCollapsedPreviewLabel.setForeground(complete ? GOAL_COMPLETE_GREEN : GOAL_IN_PROGRESS_ORANGE);
	}

	/**
	 * Synchronizes goal inputs and view when config changes externally.
	 */
	public void onConfigChanged()
	{
		SwingUtilities.invokeLater(() -> {
			refreshHeaderPanel();
			refreshSessionCardView();
			refreshGoalCardView();
			refreshTimelineCardView();
			refreshItemsCardView();
			CoinFlowSession session = plugin.getSession();
			if (session != null)
			{
				updateSessionUI(session);
			}
		});
	}

	@Override
	public void onActivate()
	{
		super.onActivate();
		SwingUtilities.invokeLater(() -> {
			refreshHeaderPanel();
			refreshSessionCardView();
			refreshGoalCardView();
			refreshTimelineCardView();
			refreshItemsCardView();
			CoinFlowSession session = plugin.getSession();
			if (session != null)
			{
				updateSessionUI(session);
			}
		});
	}

	private JButton createPresetButton(String text, Runnable action)
	{
		JButton btn = new JButton(text);
		styleButton(btn);
		btn.setFont(FontManager.getRunescapeSmallFont());
		btn.addActionListener(e -> action.run());
		return btn;
	}

	private void styleButton(JButton btn)
	{
		btn.setFont(FontManager.getRunescapeSmallFont());
		btn.setBackground(ColorScheme.DARK_GRAY_COLOR);
		btn.setForeground(Color.WHITE);
		btn.setFocusPainted(false);
		btn.setMargin(new Insets(2, 2, 2, 2));
	}

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
	 * Parses user GP input with shorthand suffixes: 'k', 'm', 'b'.
	 * Delegates to CoinFlowSession.parseGpText for hardened validation.
	 */
	public static long parseGpText(String text) throws NumberFormatException
	{
		return CoinFlowSession.parseGpText(text);
	}

	/**
	 * Sanitizes a goal label.
	 * Delegates to CoinFlowSession.cleanGoalName.
	 */
	public static String cleanGoalName(String text)
	{
		return CoinFlowSession.cleanGoalName(text);
	}

}
