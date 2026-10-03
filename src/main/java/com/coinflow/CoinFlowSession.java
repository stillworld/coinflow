package com.coinflow;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Immutable session data snapshot. A new instance is created on every update
 * and published via a volatile field in the plugin. This avoids all
 * synchronization issues between the game thread (writes) and the EDT (reads
 * from overlay/panel).
 */
public final class CoinFlowSession
{
	private static final Pattern GP_PATTERN = Pattern.compile("^\\s*(\\d{1,3}(,\\d{3})*|\\d+)(\\.\\d+)?[kmbKMB]?\\s*$");
	private static final Pattern DISALLOWED_NAME_CHARS = Pattern.compile("[^a-zA-Z0-9 '()/\\-]");
	private static final long MAX_ALLOWED_GP = 2_000_000_000_000L; // 2 Trillion GP
	/**
	 * Per-item tracking data.
	 */
	public static final class TrackedItem
	{
		private final int itemId;
		private final String name;
		private final int quantity;
		private final long priceEach;
		private final long totalValue;

		public TrackedItem(int itemId, String name, int quantity, long priceEach)
		{
			this.itemId = itemId;
			this.name = name;
			this.quantity = quantity;
			this.priceEach = priceEach;
			this.totalValue = (long) quantity * priceEach;
		}

		public int getItemId() { return itemId; }
		public String getName() { return name; }
		public int getQuantity() { return quantity; }
		public long getPriceEach() { return priceEach; }
		public long getTotalValue() { return totalValue; }

		/**
		 * Returns a new TrackedItem with additional quantity added.
		 */
		public TrackedItem withAdditionalQuantity(int additionalQty)
		{
			return new TrackedItem(itemId, name, quantity + additionalQty, priceEach);
		}

		/**
		 * Returns a new TrackedItem with a specific quantity.
		 */
		public TrackedItem withQuantity(int newQty)
		{
			return new TrackedItem(itemId, name, newQty, priceEach);
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			TrackedItem that = (TrackedItem) o;
			return itemId == that.itemId &&
				quantity == that.quantity &&
				priceEach == that.priceEach &&
				totalValue == that.totalValue &&
				java.util.Objects.equals(name, that.name);
		}

		@Override
		public int hashCode()
		{
			return java.util.Objects.hash(itemId, name, quantity, priceEach, totalValue);
		}
	}

	private final Map<Integer, TrackedItem> trackedItems;
	private final List<TrackedItem> sortedItems;
	private final Map<Integer, TrackedItem> trackedExpenses;
	private final List<TrackedItem> sortedExpenses;
	private final long grossProfit;
	private final long totalExpenses;
	private final long totalProfit;
	private final Instant sessionStartTime;
	private final Duration activeTime;
	private final Duration totalInGameTime;
	private final Instant lastActivityTime;
	private final Instant lastTickTime;
	private final boolean idle;

	private CoinFlowSession(
		Map<Integer, TrackedItem> trackedItems,
		Map<Integer, TrackedItem> trackedExpenses,
		long grossProfit,
		long totalExpenses,
		Instant sessionStartTime,
		Duration activeTime,
		Duration totalInGameTime,
		Instant lastActivityTime,
		Instant lastTickTime,
		boolean idle)
	{
		this.trackedItems = Collections.unmodifiableMap(new HashMap<>(trackedItems));
		List<TrackedItem> sorted = new java.util.ArrayList<>(trackedItems.values());
		sorted.sort(java.util.Comparator.comparingLong(TrackedItem::getTotalValue).reversed());
		this.sortedItems = Collections.unmodifiableList(sorted);

		this.trackedExpenses = Collections.unmodifiableMap(new HashMap<>(trackedExpenses));
		List<TrackedItem> sortedExp = new java.util.ArrayList<>(trackedExpenses.values());
		sortedExp.sort(java.util.Comparator.comparingLong(TrackedItem::getTotalValue).reversed());
		this.sortedExpenses = Collections.unmodifiableList(sortedExp);

		this.grossProfit = grossProfit;
		this.totalExpenses = totalExpenses;
		this.totalProfit = grossProfit - totalExpenses;
		this.sessionStartTime = sessionStartTime;
		this.activeTime = activeTime;
		this.totalInGameTime = totalInGameTime;
		this.lastActivityTime = lastActivityTime;
		this.lastTickTime = lastTickTime;
		this.idle = idle;
	}

	private CoinFlowSession(
		Map<Integer, TrackedItem> trackedItems,
		long totalProfit,
		Instant sessionStartTime,
		Duration activeTime,
		Duration totalInGameTime,
		Instant lastActivityTime,
		Instant lastTickTime,
		boolean idle)
	{
		this(
			trackedItems,
			Collections.emptyMap(),
			totalProfit,
			0L,
			sessionStartTime,
			activeTime,
			totalInGameTime,
			lastActivityTime,
			lastTickTime,
			idle
		);
	}

	/**
	 * Creates a fresh empty session starting now.
	 */
	public static CoinFlowSession createNew()
	{
		Instant now = Instant.now();
		return new CoinFlowSession(
			Collections.emptyMap(),
			Collections.emptyMap(),
			0L,
			0L,
			now,
			Duration.ZERO,
			Duration.ZERO,
			now,
			now,
			false
		);
	}

	/**
	 * Returns a new session snapshot with lastActivityTime updated to now and idle cleared.
	 *
	 * @return new session snapshot with active state refreshed
	 */
	public CoinFlowSession withActivity()
	{
		Instant now = Instant.now();
		return new CoinFlowSession(
			this.trackedItems,
			this.trackedExpenses,
			this.grossProfit,
			this.totalExpenses,
			this.sessionStartTime,
			this.activeTime,
			this.totalInGameTime,
			now,
			this.lastTickTime,
			false
		);
	}

	/**
	 * Returns a new session snapshot with the specified active and in-game durations.
	 *
	 * @param activeTime active elapsed duration
	 * @param totalInGameTime total in-game elapsed duration
	 * @return new session snapshot with updated durations
	 */
	public CoinFlowSession withDurations(Duration activeTime, Duration totalInGameTime)
	{
		return new CoinFlowSession(
			this.trackedItems,
			this.trackedExpenses,
			this.grossProfit,
			this.totalExpenses,
			this.sessionStartTime,
			activeTime,
			totalInGameTime,
			this.lastActivityTime,
			this.lastTickTime,
			this.idle
		);
	}

	/**
	 * Returns a new session snapshot with lastTickTime and lastActivityTime updated to now.
	 * Called upon logging in or hopping worlds to prevent offline time from accumulating or
	 * corrupting active session duration.
	 */
	public CoinFlowSession withResumedState()
	{
		Instant now = Instant.now();
		return new CoinFlowSession(
			this.trackedItems,
			this.trackedExpenses,
			this.grossProfit,
			this.totalExpenses,
			this.sessionStartTime,
			this.activeTime,
			this.totalInGameTime,
			now,
			now,
			this.idle
		);
	}

	public CoinFlowSession withLastActivityTime(Instant lastActivityTime)
	{
		return new CoinFlowSession(
			this.trackedItems,
			this.trackedExpenses,
			this.grossProfit,
			this.totalExpenses,
			this.sessionStartTime,
			this.activeTime,
			this.totalInGameTime,
			lastActivityTime,
			this.lastTickTime,
			this.idle
		);
	}

	/**
	 * Returns a new session with the given items added to the tracked totals.
	 * Resets idle state and sets lastActivityTime to now without adding idle time gaps.
	 *
	 * @param gains map of itemId -> TrackedItem for newly gained items
	 * @return new session snapshot with updated totals
	 */
	public CoinFlowSession withGains(Map<Integer, TrackedItem> gains)
	{
		return withGainsAndExpenses(gains, Collections.emptyMap());
	}

	/**
	 * Returns a new session with the given items added to the tracked expenses.
	 *
	 * @param expenses map of itemId -> TrackedItem for consumed supplies
	 * @return new session snapshot with updated expenses
	 */
	public CoinFlowSession withExpenses(Map<Integer, TrackedItem> expenses)
	{
		return withGainsAndExpenses(Collections.emptyMap(), expenses);
	}

	/**
	 * Returns a new session snapshot with both gains and expenses applied atomically.
	 *
	 * @param gains    map of itemId -> TrackedItem for gained items
	 * @param expenses map of itemId -> TrackedItem for consumed supplies
	 * @return new session snapshot with updated totals
	 */
	public CoinFlowSession withGainsAndExpenses(Map<Integer, TrackedItem> gains, Map<Integer, TrackedItem> expenses)
	{
		return withGainsLossesAndExpenses(gains, Collections.emptyMap(), expenses);
	}

	/**
	 * Returns a new session snapshot with gains, dropped item deductions, and expenses applied atomically.
	 *
	 * @param gains                  map of itemId -> TrackedItem for gained items
	 * @param droppedGainsDeductions map of itemId -> TrackedItem for dropped items that deduct from gross gains
	 * @param expenses               map of itemId -> TrackedItem for consumed supplies
	 * @return new session snapshot with updated totals
	 */
	public CoinFlowSession withGainsLossesAndExpenses(
		Map<Integer, TrackedItem> gains,
		Map<Integer, TrackedItem> droppedGainsDeductions,
		Map<Integer, TrackedItem> expenses)
	{
		if (gains.isEmpty() && droppedGainsDeductions.isEmpty() && expenses.isEmpty())
		{
			return this;
		}

		Map<Integer, TrackedItem> updatedGains = new HashMap<>(this.trackedItems);
		long grossDelta = 0L;

		for (Map.Entry<Integer, TrackedItem> entry : gains.entrySet())
		{
			int itemId = entry.getKey();
			TrackedItem gained = entry.getValue();

			TrackedItem existing = updatedGains.get(itemId);
			if (existing != null)
			{
				// Call on gained so the current/latest market price is preserved (Fixes Bug 3)
				updatedGains.put(itemId, gained.withAdditionalQuantity(existing.getQuantity()));
			}
			else
			{
				updatedGains.put(itemId, gained);
			}

			grossDelta += gained.getTotalValue();
		}

		for (Map.Entry<Integer, TrackedItem> entry : droppedGainsDeductions.entrySet())
		{
			int itemId = entry.getKey();
			TrackedItem dropped = entry.getValue();
			int dropQty = dropped.getQuantity();

			TrackedItem existing = updatedGains.get(itemId);
			if (existing != null)
			{
				int deductible = Math.min(dropQty, existing.getQuantity());
				int newQty = existing.getQuantity() - deductible;
				if (newQty > 0)
				{
					updatedGains.put(itemId, existing.withQuantity(newQty));
					grossDelta -= (long) deductible * existing.getPriceEach();
				}
				else
				{
					updatedGains.remove(itemId);
					grossDelta -= existing.getTotalValue();
				}
			}
			// If existing == null, item was not gained in this session (e.g. brought from bank); do not penalize profit
		}

		Map<Integer, TrackedItem> updatedExpenses = new HashMap<>(this.trackedExpenses);
		long additionalExpenses = 0L;

		for (Map.Entry<Integer, TrackedItem> entry : expenses.entrySet())
		{
			int itemId = entry.getKey();
			TrackedItem expense = entry.getValue();

			TrackedItem existing = updatedExpenses.get(itemId);
			if (existing != null)
			{
				// Call on expense so the current price is preserved
				updatedExpenses.put(itemId, expense.withAdditionalQuantity(existing.getQuantity()));
			}
			else
			{
				updatedExpenses.put(itemId, expense);
			}

			additionalExpenses += expense.getTotalValue();
		}

		Instant now = Instant.now();
		return new CoinFlowSession(
			updatedGains,
			updatedExpenses,
			this.grossProfit + grossDelta,
			this.totalExpenses + additionalExpenses,
			this.sessionStartTime,
			this.activeTime,
			this.totalInGameTime,
			now,
			this.lastTickTime,
			false
		);
	}

	/**
	 * Returns a new session snapshot with updated active time and in-game time tracking.
	 * Called on each game tick to keep the timer and GP/hr accurate in real time.
	 *
	 * @param idleTimeoutMinutes minutes of inactivity before marking as idle
	 * @param playerActive whether the player exhibited in-game activity (movement, skilling, input) this tick
	 * @return new session snapshot with updated timings
	 */
	public CoinFlowSession tick(int idleTimeoutMinutes, boolean playerActive)
	{
		Instant now = Instant.now();
		Instant updatedActivityTime = playerActive ? now : this.lastActivityTime;
		Duration sinceLastActivity = Duration.between(updatedActivityTime, now);
		boolean nowIdle = !playerActive && sinceLastActivity.toMinutes() >= idleTimeoutMinutes;

		// Accumulate time since last tick.
		// Clamp to max 1500ms to ignore long gaps (e.g. world hops, lag spikes, or login delays).
		Duration tickDelta = Duration.between(lastTickTime, now);
		if (tickDelta.isNegative() || tickDelta.toMillis() > 1500)
		{
			tickDelta = Duration.ofMillis(600);
		}

		Duration newTotalInGameTime = this.totalInGameTime.plus(tickDelta);
		Duration newActiveTime;
		if (nowIdle)
		{
			if (!this.idle)
			{
				// Transitioning from active to idle: retroactively remove the inactivity window
				// that accumulated while waiting for the idle timeout threshold to trigger.
				// Bounded by at most idleTimeoutMinutes so offline time or long gaps never wipe out active time.
				Duration maxCountdown = Duration.ofMinutes(Math.max(0, idleTimeoutMinutes));
				Duration inactiveDuration = Duration.between(this.lastActivityTime, now);
				Duration rollback = inactiveDuration.compareTo(maxCountdown) > 0 ? maxCountdown : inactiveDuration;
				if (this.activeTime.compareTo(rollback) > 0)
				{
					newActiveTime = this.activeTime.minus(rollback);
				}
				else
				{
					newActiveTime = Duration.ZERO;
				}
			}
			else
			{
				newActiveTime = this.activeTime;
			}
		}
		else
		{
			newActiveTime = this.activeTime.plus(tickDelta);
		}

		return new CoinFlowSession(
			this.trackedItems,
			this.trackedExpenses,
			this.grossProfit,
			this.totalExpenses,
			this.sessionStartTime,
			newActiveTime,
			newTotalInGameTime,
			updatedActivityTime,
			now,
			nowIdle
		);
	}

	/**
	 * Returns a new session snapshot with updated active time and in-game time tracking.
	 *
	 * @param idleTimeoutMinutes minutes of inactivity before marking as idle
	 * @return new session snapshot with updated timings
	 */
	public CoinFlowSession tick(int idleTimeoutMinutes)
	{
		return tick(idleTimeoutMinutes, false);
	}

	// ── Getters ──────────────────────────────────────────────────────────

	public Map<Integer, TrackedItem> getTrackedItems()
	{
		return trackedItems;
	}

	public java.util.List<TrackedItem> getSortedItems()
	{
		return sortedItems;
	}

	public Map<Integer, TrackedItem> getTrackedExpenses()
	{
		return trackedExpenses;
	}

	public java.util.List<TrackedItem> getSortedExpenses()
	{
		return sortedExpenses;
	}

	public long getGrossProfit()
	{
		return grossProfit;
	}

	public long getTotalExpenses()
	{
		return totalExpenses;
	}

	public long getNetProfit()
	{
		return totalProfit;
	}

	public long getTotalProfit()
	{
		return totalProfit;
	}

	public Instant getSessionStartTime()
	{
		return sessionStartTime;
	}

	public Duration getActiveTime()
	{
		return activeTime;
	}

	public Duration getTotalInGameTime()
	{
		return totalInGameTime;
	}

	public Duration getTime(boolean includeAfkTime)
	{
		return includeAfkTime ? totalInGameTime : activeTime;
	}

	public Duration getTotalElapsedTime()
	{
		return Duration.between(sessionStartTime, Instant.now());
	}

	public boolean isIdle()
	{
		return idle;
	}

	/**
	 * Wall-clock time of the last processed game tick. Only advanced by tick();
	 * package-private for tests and session telemetry logging.
	 */
	Instant getLastTickTime()
	{
		return lastTickTime;
	}

	/**
	 * Calculates GP per hour based on active time or total in-game time (including AFK gaps).
	 *
	 * @param includeAfkTime whether to include AFK/idle time in the calculation
	 * @return GP/hr, or 0 if time is too short
	 */
	public long getGpPerHour(boolean includeAfkTime)
	{
		Duration time = includeAfkTime ? totalInGameTime : activeTime;
		long seconds = time.getSeconds();
		if (seconds < 1)
		{
			return 0;
		}

		return (totalProfit * 3600L) / seconds;
	}

	/**
	 * Calculates GP per hour based on active time (excluding AFK gaps).
	 *
	 * @return GP/hr, or 0 if active time is too short
	 */
	public long getGpPerHour()
	{
		return getGpPerHour(false);
	}

	/**
	 * Calculates gross GP per hour based on active time or total in-game time (excluding supply expenses).
	 *
	 * @param includeAfkTime whether to include AFK/idle time in the calculation
	 * @return Gross GP/hr, or 0 if time is too short
	 */
	public long getGrossGpPerHour(boolean includeAfkTime)
	{
		Duration time = includeAfkTime ? totalInGameTime : activeTime;
		long seconds = time.getSeconds();
		if (seconds < 1)
		{
			return 0;
		}

		return (grossProfit * 3600L) / seconds;
	}

	/**
	 * Calculates gross GP per hour based on active time (excluding AFK gaps and supply expenses).
	 *
	 * @return Gross GP/hr, or 0 if active time is too short
	 */
	public long getGrossGpPerHour()
	{
		return getGrossGpPerHour(false);
	}

	// ── Goal Calculations ────────────────────────────────────────────────

	/**
	 * Parses user GP input with shorthand multipliers ('k', 'm', 'b').
	 * Hardened against special characters, invalid formats, negative numbers, and overflow.
	 */
	public static long parseGpText(String text) throws NumberFormatException
	{
		if (text == null)
		{
			throw new NumberFormatException("Input cannot be empty");
		}
		String trimmed = text.trim();
		if (trimmed.isEmpty())
		{
			throw new NumberFormatException("Input cannot be empty");
		}
		if (!GP_PATTERN.matcher(trimmed).matches())
		{
			throw new NumberFormatException("Special characters and invalid formats are not accepted. Use numbers with optional shorthand (e.g. 10m, 500k, 2.5m, 1,000,000).");
		}

		String clean = trimmed.toLowerCase().replace(",", "").replace(" ", "");

		double multiplier = 1.0;
		if (clean.endsWith("k"))
		{
			multiplier = 1_000.0;
			clean = clean.substring(0, clean.length() - 1);
		}
		else if (clean.endsWith("m"))
		{
			multiplier = 1_000_000.0;
			clean = clean.substring(0, clean.length() - 1);
		}
		else if (clean.endsWith("b"))
		{
			multiplier = 1_000_000_000.0;
			clean = clean.substring(0, clean.length() - 1);
		}

		double parsed;
		try
		{
			parsed = Double.parseDouble(clean);
		}
		catch (NumberFormatException e)
		{
			throw new NumberFormatException("Invalid numeric amount");
		}

		if (Double.isNaN(parsed) || Double.isInfinite(parsed))
		{
			throw new NumberFormatException("Invalid numeric amount");
		}

		double total = parsed * multiplier;
		if (total <= 0)
		{
			throw new NumberFormatException("Amount must be greater than zero");
		}
		if (total > MAX_ALLOWED_GP)
		{
			throw new NumberFormatException("Amount cannot exceed 2,000,000,000,000 gp");
		}

		return (long) total;
	}

	/**
	 * Sanitizes a goal label by stripping special characters, HTML tags, and clamping length.
	 * Allows standard alphanumeric characters, spaces, and safe punctuation used in RuneScape item names.
	 */
	public static String cleanGoalName(String text)
	{
		if (text == null)
		{
			return "";
		}
		// Strip all special characters not allowed in item names
		String clean = DISALLOWED_NAME_CHARS.matcher(text).replaceAll("");
		// Collapse multiple spaces into a single space and trim
		clean = clean.replaceAll("\\s+", " ").trim();
		// Clamp length to max 32 characters
		if (clean.length() > 32)
		{
			clean = clean.substring(0, 32).trim();
		}
		return clean;
	}

	/**
	 * Safely parses the goalAmount config string into a long.
	 * Returns 0 if empty, invalid, or zero.
	 */
	public static long parseGoalAmount(String goalStr)
	{
		if (goalStr == null || goalStr.trim().isEmpty())
		{
			return 0L;
		}
		try
		{
			return parseGpText(goalStr);
		}
		catch (Exception e)
		{
			return 0L;
		}
	}

	/**
	 * Returns the remaining GP to reach the target goal.
	 * Returns 0 if goal is achieved or no goal is set.
	 */
	public long getGoalRemaining(long goalAmount)
	{
		return getGoalRemaining(goalAmount, true);
	}

	/**
	 * Returns the remaining GP to reach the target goal based on net or gross tracking.
	 *
	 * @param goalAmount the target GP amount
	 * @param trackSpent whether to evaluate against net profit (true) or gross profit (false)
	 * @return remaining GP to target, or 0 if reached
	 */
	public long getGoalRemaining(long goalAmount, boolean trackSpent)
	{
		if (goalAmount <= 0)
		{
			return 0L;
		}
		long current = Math.max(0L, trackSpent ? totalProfit : grossProfit);
		return Math.max(0L, goalAmount - current);
	}

	/**
	 * Returns the goal completion progress as a ratio between 0.0 and 1.0.
	 */
	public double getGoalProgress(long goalAmount)
	{
		return getGoalProgress(goalAmount, true);
	}

	/**
	 * Returns the goal completion progress as a ratio between 0.0 and 1.0 based on net or gross tracking.
	 *
	 * @param goalAmount the target GP amount
	 * @param trackSpent whether to evaluate against net profit (true) or gross profit (false)
	 * @return progress ratio between 0.0 and 1.0
	 */
	public double getGoalProgress(long goalAmount, boolean trackSpent)
	{
		if (goalAmount <= 0)
		{
			return 0.0;
		}
		long current = Math.max(0L, trackSpent ? totalProfit : grossProfit);
		return Math.min(1.0, (double) current / goalAmount);
	}

	/**
	 * Returns the estimated seconds remaining until the goal is reached,
	 * or -1 if the ETA cannot be calculated (e.g. paused, zero GP/hr, or warming up).
	 * Returns 0 if the goal has already been achieved.
	 *
	 * @param goalAmount the target GP amount
	 * @param includeAfkTime whether to compute using AFK time
	 * @return estimated seconds remaining, 0 if achieved, or -1 if calculating/paused
	 */
	public long getGoalEtaSeconds(long goalAmount, boolean includeAfkTime)
	{
		return getGoalEtaSeconds(goalAmount, includeAfkTime, true);
	}

	/**
	 * Returns the estimated seconds remaining until the goal is reached,
	 * or -1 if the ETA cannot be calculated (e.g. paused, zero GP/hr, or warming up).
	 *
	 * @param goalAmount the target GP amount
	 * @param includeAfkTime whether to compute using AFK time
	 * @param trackSpent whether to evaluate against net profit and net GP/hr or gross tracking
	 * @return estimated seconds remaining, 0 if achieved, or -1 if calculating/paused
	 */
	public long getGoalEtaSeconds(long goalAmount, boolean includeAfkTime, boolean trackSpent)
	{
		if (goalAmount <= 0)
		{
			return -1;
		}

		long remaining = getGoalRemaining(goalAmount, trackSpent);
		if (remaining <= 0)
		{
			return 0; // Goal reached
		}

		Duration time = includeAfkTime ? totalInGameTime : activeTime;
		// Require at least 10 seconds of tracking before showing ETA to prevent wild single-drop spikes
		if (time.getSeconds() < 10)
		{
			return -1; // Warmup
		}

		long gpHr = trackSpent ? getGpPerHour(includeAfkTime) : getGrossGpPerHour(includeAfkTime);
		if (gpHr <= 0)
		{
			return -1; // Paused or no positive rate
		}

		return (remaining * 3600L) / gpHr;
	}

	/**
	 * Formats ETA seconds into a concise human-readable string.
	 * Examples: "Goal Reached!", "2h 15m", "45m", "< 1m", "> 99h", or "--:--".
	 */
	public static String formatGoalEta(long etaSeconds)
	{
		if (etaSeconds == 0)
		{
			return "Goal Reached!";
		}
		if (etaSeconds < 0)
		{
			return "--:--";
		}
		if (etaSeconds < 60)
		{
			return "< 1m";
		}

		long hours = etaSeconds / 3600;
		long minutes = (etaSeconds % 3600) / 60;

		if (hours > 99)
		{
			return "> 99h";
		}
		if (hours > 0)
		{
			return String.format("%dh %02dm", hours, minutes);
		}
		return String.format("%dm", minutes);
	}
}
