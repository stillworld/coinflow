package com.coinflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Per-release "What's New" notes rendered in the update card. Add a new
 * {@link Entry} at the top of {@link #ENTRIES} at release time, matching
 * the {@code version} bumped in {@code runelite-plugin.properties}. Users
 * upgrading across multiple releases see every entry newer than their last
 * seen version.
 */
final class Changelog
{
	static final class Entry
	{
		final String version;
		final List<String> bullets;

		Entry(String version, String... bullets)
		{
			this.version = version;
			this.bullets = Collections.unmodifiableList(Arrays.asList(bullets));
		}
	}

	/** Newest release first. */
	private static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
		new Entry("1.14.1",
			"Fixed untradeable crafted gear (Ferocious gloves, Neitiznot faceguard, nightmare "
				+ "staves, Elidinis' ward (f), avernic treads, tormented weapons, crystal tools) "
				+ "counting as pure profit \u2014 assembly and dismantle now net against their components"),
		new Entry("1.14.0",
			"Item rows now show booking provenance in their tooltips",
			"New plugin icon",
			"Goal GP input accepts shorthand (k/m/b); a blank field clears the goal",
			"Destroyed boat cargo books as an expense; blood essence activation is tracked")
	));

	private Changelog()
	{
	}

	/**
	 * Entries strictly newer than {@code lastSeen}, newest first. A null or
	 * empty {@code lastSeen} returns only {@link #latest()} so first-run
	 * users see the current release's highlights rather than every note ever
	 * written.
	 */
	static List<Entry> newerThan(String lastSeen)
	{
		if (lastSeen == null || lastSeen.isEmpty())
		{
			return latest();
		}
		List<Entry> out = new ArrayList<>();
		for (Entry e : ENTRIES)
		{
			if (Version.compare(e.version, lastSeen) > 0)
			{
				out.add(e);
			}
		}
		return out;
	}

	/** The newest entry only, or empty if no notes exist. */
	static List<Entry> latest()
	{
		return ENTRIES.isEmpty() ? Collections.emptyList() : Collections.singletonList(ENTRIES.get(0));
	}
}
