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
}
