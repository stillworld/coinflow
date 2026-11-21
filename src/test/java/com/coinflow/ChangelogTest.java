package com.coinflow;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class ChangelogTest
{
	@Test
	public void testNullLastSeenReturnsOnlyLatest()
	{
		List<Changelog.Entry> notes = Changelog.newerThan(null);
		Assert.assertEquals(1, notes.size());
		Assert.assertEquals(Changelog.latest().get(0).version, notes.get(0).version);
	}

	@Test
	public void testNewerThanFiltersSeenVersions()
	{
		List<Changelog.Entry> all = Changelog.newerThan("0.0.0");
		Assert.assertFalse(all.isEmpty());

		// Every returned entry must be strictly newer than the cutoff
		for (Changelog.Entry e : Changelog.newerThan("999.0.0"))
		{
			Assert.fail("No entry should be newer than 999.0.0, got " + e.version);
		}

		String oldest = all.get(all.size() - 1).version;
		List<Changelog.Entry> afterOldest = Changelog.newerThan(oldest);
		Assert.assertEquals(all.size() - 1, afterOldest.size());
		Assert.assertFalse(afterOldest.stream().anyMatch(e -> e.version.equals(oldest)));
	}

	@Test
	public void testEntriesHaveBullets()
	{
		for (Changelog.Entry e : Changelog.newerThan("0.0.0"))
		{
			Assert.assertFalse("Entry " + e.version + " has no bullets", e.bullets.isEmpty());
			Assert.assertFalse("Entry " + e.version + " has empty version", e.version.isEmpty());
		}
	}
}
