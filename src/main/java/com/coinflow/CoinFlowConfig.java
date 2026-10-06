package com.coinflow;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(CoinFlowConfig.CONFIG_GROUP)
public interface CoinFlowConfig extends Config
{
	String CONFIG_GROUP = "coin-flow";

	// ── General Section ──────────────────────────────────────────────────

	@ConfigSection(
		name = "General",
		description = "General tracking settings",
		position = 0
	)
	String generalSection = "general";

	@ConfigItem(
		keyName = "trackSpent",
		name = "Track Spent",
		description = "Track supply costs and expenses, deducting them from profit (disable for gross tracking)",
		section = generalSection,
		position = 0
	)
	default boolean trackSpent()
	{
		return true;
	}

	// ── Display Section ──────────────────────────────────────────────────

	@ConfigSection(
		name = "Display",
		description = "Overlay, side panel, and gold drop display settings",
		position = 1
	)
	String displaySection = "display";

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show Overlay",
		description = "Toggle the on-screen profit overlay",
		section = displaySection,
		position = 0
	)
	default boolean showOverlay()
	{
		return true;
	}

	enum OverlayStyle
	{
		DETAILED("Detailed"),
		SINGLE_LINE("Single Line");

		private final String name;

		OverlayStyle(String name)
		{
			this.name = name;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	@ConfigItem(
		keyName = "overlayStyle",
		name = "Overlay Style",
		description = "Detailed shows profit, spent, GP/hr, time and goal progress; Single Line shows only the GP/hr rate",
		section = displaySection,
		position = 1
	)
	default OverlayStyle overlayStyle()
	{
		return OverlayStyle.DETAILED;
	}

	@ConfigItem(
		keyName = "showOverlayBackground",
		name = "Overlay Background",
		description = "Draw the panel background behind the overlay (disable for text only)",
		section = displaySection,
		position = 2
	)
	default boolean showOverlayBackground()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showItemBreakdown",
		name = "Show Item Breakdown",
		description = "Show per-item breakdown in the side panel",
		section = displaySection,
		position = 3
	)
	default boolean showItemBreakdown()
	{
		return false;
	}

	@ConfigItem(
		keyName = "compactMode",
		name = "Compact Mode",
		description = "Display a streamlined, compact view in the side panel",
		section = displaySection,
		position = 4
	)
	default boolean compactMode()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showGoldDrops",
		name = "Show Gold Drops",
		description = "Display floating gold drops when gaining profit",
		section = displaySection,
		position = 5
	)
	default boolean showGoldDrops()
	{
		return false;
	}

	enum GoldDropPosition
	{
		TOP_RIGHT("Top Right (XP Drops)"),
		OVERHEAD("Overhead (Player)");

		private final String name;

		GoldDropPosition(String name)
		{
			this.name = name;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	@ConfigItem(
		keyName = "goldDropPosition",
		name = "Drop Position",
		description = "Where to display floating gold drops on screen",
		section = displaySection,
		position = 6
	)
	default GoldDropPosition goldDropPosition()
	{
		return GoldDropPosition.TOP_RIGHT;
	}

	@ConfigItem(
		keyName = "goldDropMinThreshold",
		name = "Min Gold Drop (GP)",
		description = "Minimum GP value required to trigger an in-game gold drop (0 to show all)",
		section = displaySection,
		position = 7
	)
	@Range(min = 0)
	default int goldDropMinThreshold()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "sessionCardCollapsed",
		name = "Collapse Session Card",
		description = "Collapse session card in side panel",
		hidden = true
	)
	default boolean sessionCardCollapsed()
	{
		return false;
	}

	@ConfigItem(
		keyName = "goalCardCollapsed",
		name = "Collapse Goal Card",
		description = "Collapse goal progress card in side panel",
		hidden = true
	)
	default boolean goalCardCollapsed()
	{
		return false;
	}

	@ConfigItem(
		keyName = "itemsCardCollapsed",
		name = "Collapse Items Card",
		description = "Collapse item breakdown card in side panel",
		hidden = true
	)
	default boolean itemsCardCollapsed()
	{
		return false;
	}

	// ── Goal Section ─────────────────────────────────────────────────────

	@ConfigSection(
		name = "Goal",
		description = "Target GP goals and countdown settings",
		position = 2
	)
	String goalSection = "goal";

	@ConfigItem(
		keyName = "showGoalOverlay",
		name = "Show Goal in Overlay",
		description = "Display a mini progress bar and ETA in the overlay HUD",
		section = goalSection,
		position = 0
	)
	default boolean showGoalOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "goalAmount",
		name = "Target GP",
		description = "The target GP amount to work towards (e.g. 10m, 500k, 10000; blank to disable)",
		section = goalSection,
		position = 1
	)
	default String goalAmount()
	{
		return "";
	}

	@ConfigItem(
		keyName = "goalName",
		name = "Goal Label",
		description = "A custom label for your goal (e.g. Old School Bond)",
		section = goalSection,
		position = 2
	)
	default String goalName()
	{
		return "";
	}

	@ConfigItem(
		keyName = "notifyOnGoal",
		name = "Notify on Completion",
		description = "Send a notification when the target GP goal is reached",
		section = goalSection,
		position = 3
	)
	default boolean notifyOnGoal()
	{
		return true;
	}

	// ── Reporting Section ─────────────────────────────────────────────────

	@ConfigSection(
		name = "Reporting",
		description = "Reporting settings",
		position = 3,
		closedByDefault = true
	)
	String reportingSection = "reporting";

	@ConfigItem(
		keyName = "displaySummary",
		name = "Display Summary on Reset",
		description = "Display the session summary dialog when resetting an active session",
		section = reportingSection,
		position = 0
	)
	default boolean displaySummary()
	{
		return false;
	}

	// ── Advanced Section ─────────────────────────────────────────────────

	@ConfigSection(
		name = "Advanced",
		description = "Advanced tracking settings",
		position = 4,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	@ConfigItem(
		keyName = "includeAfkTime",
		name = "Include AFK Time",
		description = "Include AFK and idle time in the GP/hr calculation instead of pausing while inactive",
		section = advancedSection,
		position = 0
	)
	default boolean includeAfkTime()
	{
		return false;
	}

	@ConfigItem(
		keyName = "idleTimeoutMinutes",
		name = "Idle Timeout (minutes)",
		description = "Minutes of player inactivity before pausing the GP/hr timer (when Include AFK Time is disabled)",
		section = advancedSection,
		position = 1
	)
	@Range(min = 1, max = 60)
	default int idleTimeoutMinutes()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "ignoredItems",
		name = "Ignored Items",
		description = "Comma-separated item names to exclude from tracking",
		section = advancedSection,
		position = 2
	)
	default String ignoredItems()
	{
		return "";
	}

	@ConfigItem(
		keyName = "trackWeaponCharges",
		name = "Track Weapon Charges",
		description = "Deduct runes, scales, and other resources consumed inside charged weapons (blowpipe, tridents, etc.) from profit",
		section = advancedSection,
		position = 3
	)
	default boolean trackWeaponCharges()
	{
		return true;
	}

	@ConfigItem(
		keyName = "untrackedSalesAsIncome",
		name = "Stock Sales as Income",
		description = "Count proceeds from selling untracked (banked or pre-session) items at shops or the GE as income instead of a net-zero asset conversion",
		section = advancedSection,
		position = 4
	)
	default boolean untrackedSalesAsIncome()
	{
		return false;
	}

	@ConfigItem(
		keyName = "countDropsAsSpent",
		name = "Count Drops as Spent",
		description = "Record items you Drop as a supply expense (Spent) instead of deducting them from gross profit. Picking the item back up reverses the expense. Requires Track Spent.",
		section = advancedSection,
		position = 5
	)
	default boolean countDropsAsSpent()
	{
		return false;
	}
}
