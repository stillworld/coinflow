package com.coinflow;

/**
 * Accessor for the Coin Flow plugin version.
 */
public class Version
{
	private static final String VERSION = "1.3.0";

	private Version()
	{
	}

	/**
	 * Returns the semantic version string (e.g., "1.0.0").
	 */
	public static String getVersion()
	{
		return VERSION;
	}

	/**
	 * Returns the prefixed version string (e.g., "v1.0.0").
	 */
	public static String getFormattedVersion()
	{
		return "v" + VERSION;
	}
}
