package com.coinflow;

import java.io.InputStream;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

/**
 * Accessor for the Coin Flow plugin version. The version is read from
 * {@code runelite-plugin.properties} on the classpath — the single source
 * of truth bumped at release time; the hardcoded constant is only a
 * fallback for environments where the resource is absent.
 */
@Slf4j
public class Version
{
	private static final String FALLBACK_VERSION = "1.15.0";
	private static final String VERSION = loadVersion();

	private Version()
	{
	}

	private static String loadVersion()
	{
		try (InputStream in = Version.class.getResourceAsStream("/runelite-plugin.properties"))
		{
			if (in != null)
			{
				Properties props = new Properties();
				props.load(in);
				String version = props.getProperty("version");
				if (version != null && !version.trim().isEmpty())
				{
					return version.trim();
				}
			}
		}
		catch (Exception e)
		{
			log.debug("Could not read runelite-plugin.properties; using fallback version", e);
		}
		return FALLBACK_VERSION;
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

	/**
	 * Dot-separated numeric version compare ("1.14.0" vs "1.9.2"). Missing
	 * trailing segments read as 0 and non-numeric suffixes are ignored, so
	 * "1.14.1" &gt; "1.14" and "1.14.0-SNAPSHOT" == "1.14.0". Null reads as
	 * "0". Returns negative, zero, or positive like a Comparator.
	 */
	public static int compare(String a, String b)
	{
		String[] pa = (a == null ? "0" : a).split("\\.");
		String[] pb = (b == null ? "0" : b).split("\\.");
		int n = Math.max(pa.length, pb.length);
		for (int i = 0; i < n; i++)
		{
			int x = i < pa.length ? numericPrefix(pa[i]) : 0;
			int y = i < pb.length ? numericPrefix(pb[i]) : 0;
			if (x != y)
			{
				return Integer.compare(x, y);
			}
		}
		return 0;
	}

	private static int numericPrefix(String segment)
	{
		int value = 0;
		for (int i = 0; i < segment.length(); i++)
		{
			char c = segment.charAt(i);
			if (!Character.isDigit(c))
			{
				break;
			}
			value = value * 10 + (c - '0');
		}
		return value;
	}
}
