package com.coinflow;

import lombok.extern.slf4j.Slf4j;

/**
 * Persists goal progress across session boundaries (logout, manual reset,
 * client restart) so a target GP goal can span days. Progress is stored per
 * account hash in the plugin's config group under
 * {@code goalProgress.<accountHash>} as
 * {@code "<goalAmount>|<persistedGp>|<completeFlag>|<trackedSeconds>"}
 * (the seconds field is omitted on values written before it existed and
 * reads as 0). Tracked seconds feed the blended cross-session goal ETA.
 * <p>
 * The stored amount is a self-invalidating tag: when the configured goal
 * amount changes, stale persisted values for every account are ignored at
 * read time, and {@link #onGoalAmountChanged} additionally unsets the current
 * account's key. Completion is persisted too — once reached the goal reports
 * "Goal Reached!" (via {@link #effectiveTotal} returning the target) until it
 * is cleared or the amount changes, and {@link #persistSession} freezes so a
 * later losing session cannot un-complete it.
 * <p>
 * {@link #persistSession} is idempotent within a session: it persists only the
 * delta since the last call, so it can run at logout, reset, and shutdown
 * checkpoints without double-counting. {@link #onNewSession} must be invoked
 * at every session replacement to clear the per-session offset.
 */
@Slf4j
final class GoalTracker
{
	/** Config key prefix; the account hash is appended ({@code goalProgress.<hash>}). */
	static final String PROGRESS_KEY_PREFIX = "goalProgress.";

	private final CoinFlowPlugin plugin;

	/**
	 * Portion of the current session's contribution already written to config.
	 * Zeroed by {@link #onNewSession} whenever the session object is replaced.
	 */
	private long persistedFromSession;

	/** Seconds counterpart of {@link #persistedFromSession}. */
	private long persistedSecondsFromSession;

	/** Last raw config value parsed, so reads don't re-split every frame/tick. */
	private String lastRawValue;
	private PersistedGoal lastParsed;

	GoalTracker(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** Parsed form of the persisted value. */
	private static final class PersistedGoal
	{
		final long amount;
		final long persisted;
		final boolean complete;
		final long seconds;

		PersistedGoal(long amount, long persisted, boolean complete, long seconds)
		{
			this.amount = amount;
			this.persisted = persisted;
			this.complete = complete;
			this.seconds = seconds;
		}
	}

	private long accountHash()
	{
		return plugin.lastAccountHash;
	}

	private String progressKey()
	{
		return PROGRESS_KEY_PREFIX + accountHash();
	}

	private long goalAmount()
	{
		return CoinFlowSession.parseGoalAmount(
			plugin.config != null ? plugin.config.goalAmount() : null);
	}

	/**
	 * The session profit figure the goal is evaluated against — net profit
	 * when tracking supplies, gross otherwise. 0 when no session exists.
	 */
	long sessionContribution()
	{
		CoinFlowSession session = plugin.session;
		if (session == null)
		{
			return 0L;
		}
		return (plugin.config != null && !plugin.config.trackSpent())
			? session.getGrossProfit()
			: session.getTotalProfit();
	}

	/**
	 * Seconds the live session has tracked toward the goal — total or active
	 * time per the include-AFK setting, matching the session's gp/hr clock.
	 * 0 when no session exists.
	 */
	long sessionTrackedSeconds()
	{
		CoinFlowSession session = plugin.session;
		if (session == null)
		{
			return 0L;
		}
		boolean includeAfk = plugin.config == null || plugin.config.includeAfkTime();
		return session.getTime(includeAfk).getSeconds();
	}

	/**
	 * Reads and parses the persisted goal for the current account. Cached by
	 * raw value so the string is only split when the stored data changes.
	 * Returns null when logged out (hash -1), nothing stored, or the value is
	 * malformed.
	 */
	private PersistedGoal readPersisted()
	{
		if (accountHash() == -1 || plugin.configManager == null)
		{
			return null;
		}
		String raw;
		try
		{
			raw = plugin.configManager.getConfiguration(CoinFlowConfig.CONFIG_GROUP, progressKey());
		}
		catch (Exception e)
		{
			return null;
		}
		if (raw == null)
		{
			lastRawValue = null;
			lastParsed = null;
			return null;
		}
		if (raw.equals(lastRawValue))
		{
			return lastParsed;
		}
		lastRawValue = raw;
		lastParsed = parse(raw);
		return lastParsed;
	}

	private static PersistedGoal parse(String raw)
	{
		try
		{
			String[] parts = raw.split("\\|");
			if (parts.length != 3 && parts.length != 4)
			{
				return null;
			}
			return new PersistedGoal(
				Long.parseLong(parts[0].trim()),
				Long.parseLong(parts[1].trim()),
				"1".equals(parts[2].trim()),
				parts.length == 4 ? Long.parseLong(parts[3].trim()) : 0L);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * GP persisted for the current account toward the current goal amount.
	 * Stale values (stored against a different goal amount) read as 0.
	 */
	long persistedGp()
	{
		PersistedGoal persisted = readPersisted();
		long goal = goalAmount();
		if (persisted == null || goal <= 0 || persisted.amount != goal)
		{
			return 0L;
		}
		return persisted.persisted;
	}

	/**
	 * Seconds persisted for the current account toward the current goal
	 * amount. Stale values (stored against a different goal amount) read as 0.
	 */
	long persistedSeconds()
	{
		PersistedGoal persisted = readPersisted();
		long goal = goalAmount();
		if (persisted == null || goal <= 0 || persisted.amount != goal)
		{
			return 0L;
		}
		return persisted.seconds;
	}

	/**
	 * Whether the current goal amount was already marked complete for this
	 * account.
	 */
	boolean isComplete()
	{
		PersistedGoal persisted = readPersisted();
		long goal = goalAmount();
		return persisted != null && goal > 0 && persisted.complete && persisted.amount == goal;
	}

	/**
	 * The figure goal UI and notification evaluate: the target itself when
	 * complete (frozen "Goal Reached!" display), otherwise persisted progress
	 * plus the live session's contribution. Unclamped — callers clamp at 0.
	 */
	long effectiveTotal()
	{
		long goal = goalAmount();
		if (goal <= 0)
		{
			return 0L;
		}
		if (isComplete())
		{
			return goal;
		}
		return persistedGp() + sessionContribution();
	}

	/**
	 * Seconds tracked toward the goal across the boundary — persisted time
	 * from earlier sessions plus the live session's tracked time. Feeds the
	 * blended cross-session ETA.
	 */
	long effectiveSeconds()
	{
		if (goalAmount() <= 0)
		{
			return 0L;
		}
		return persistedSeconds() + sessionTrackedSeconds();
	}

	/**
	 * Persists the not-yet-written portion of the current session's
	 * contribution to config. Idempotent: only the delta since the last call
	 * is written, so repeated checkpoints (logout, reset, shutdown) never
	 * double-count. A negative delta — a losing session or a row removal
	 * after an earlier checkpoint — legitimately shrinks the persisted figure.
	 * No-op while logged out, goal-less, already complete, or when nothing
	 * changed.
	 */
	void persistSession()
	{
		if (accountHash() == -1 || plugin.configManager == null)
		{
			return;
		}
		long goal = goalAmount();
		if (goal <= 0 || isComplete() || plugin.session == null)
		{
			return;
		}
		long contribution = sessionContribution();
		long trackedSecs = sessionTrackedSeconds();
		long delta = contribution - persistedFromSession;
		long deltaSecs = trackedSecs - persistedSecondsFromSession;
		if (delta == 0 && deltaSecs == 0)
		{
			return;
		}
		long updated = persistedGp() + delta;
		long updatedSecs = persistedSeconds() + deltaSecs;
		persistedFromSession = contribution;
		persistedSecondsFromSession = trackedSecs;
		writePersisted(new PersistedGoal(goal, updated, false, updatedSecs));
		log.debug("Persisted goal progress: {} gp toward {} (account {})",
			updated, goal, accountHash());
	}

	/**
	 * Marks the current goal amount complete for this account. Independent of
	 * the notify-on-goal setting — that only gates the toast, not the state.
	 */
	void markComplete()
	{
		if (accountHash() == -1 || plugin.configManager == null)
		{
			return;
		}
		long goal = goalAmount();
		if (goal <= 0)
		{
			return;
		}
		writePersisted(new PersistedGoal(goal, persistedGp(), true, persistedSeconds()));
	}

	/**
	 * Drops the persisted goal state for the current account — called when
	 * the configured goal amount changes (covers Set Goal and Clear).
	 * Other accounts' keys self-invalidate via the amount tag.
	 */
	void onGoalAmountChanged()
	{
		if (accountHash() == -1 || plugin.configManager == null)
		{
			return;
		}
		try
		{
			plugin.configManager.unsetConfiguration(CoinFlowConfig.CONFIG_GROUP, progressKey());
		}
		catch (Exception ignored)
		{
		}
		lastRawValue = null;
		lastParsed = null;
		persistedFromSession = 0L;
		persistedSecondsFromSession = 0L;
	}

	/**
	 * Must be called whenever the session object is replaced or dropped —
	 * the per-session offset only has meaning against the session it was
	 * captured from.
	 */
	void onNewSession()
	{
		persistedFromSession = 0L;
		persistedSecondsFromSession = 0L;
	}

	private void writePersisted(PersistedGoal value)
	{
		try
		{
			String raw = value.amount + "|" + value.persisted + "|" + (value.complete ? "1" : "0") + "|" + value.seconds;
			plugin.configManager.setConfiguration(CoinFlowConfig.CONFIG_GROUP, progressKey(), raw);
			lastRawValue = raw;
			lastParsed = value;
		}
		catch (Exception e)
		{
			log.debug("Failed to persist goal progress", e);
		}
	}
}
