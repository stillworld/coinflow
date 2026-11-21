package com.coinflow;

import org.junit.Assert;
import org.junit.Test;

public class VersionTest
{
	@Test
	public void testVersionNotNull()
	{
		String version = Version.getVersion();
		Assert.assertNotNull("Version should not be null", version);
		Assert.assertFalse("Version should not be empty", version.isEmpty());
		Assert.assertFalse("Version should not contain unresolved placeholder", version.contains("${"));
	}

	@Test
	public void testFormattedVersion()
	{
		String formatted = Version.getFormattedVersion();
		Assert.assertNotNull("Formatted version should not be null", formatted);
		Assert.assertTrue("Formatted version should start with 'v'", formatted.startsWith("v"));
	}

	@Test
	public void testCompare()
	{
		Assert.assertEquals(0, Version.compare("1.14.0", "1.14.0"));
		Assert.assertTrue(Version.compare("1.14.1", "1.14.0") > 0);
		Assert.assertTrue(Version.compare("1.9.2", "1.14.0") < 0);
		Assert.assertTrue(Version.compare("1.14.0.1", "1.14.0") > 0);
		Assert.assertEquals(0, Version.compare("1.14.0-SNAPSHOT", "1.14.0"));
		Assert.assertTrue(Version.compare("2.0.0", "1.99.9") > 0);
		Assert.assertTrue(Version.compare("1.14.0", null) > 0);
	}
}
