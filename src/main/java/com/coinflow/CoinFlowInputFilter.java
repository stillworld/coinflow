package com.coinflow;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

/**
 * Real-time DocumentFilter and utility for ensuring user-facing text fields
 * across the Coin Flow plugin (both in the side panel and RuneLite config panel)
 * strictly reject special characters.
 */
public class CoinFlowInputFilter extends DocumentFilter
{
	public static final String DIGITS_ONLY = "[^0-9]";
	public static final String TARGET_GP = "[^0-9.,kKmMbB]";
	public static final String GOAL_NAME = "[^a-zA-Z0-9 '()/\\-]";
	public static final String IGNORED_ITEMS = "[^a-zA-Z0-9 ',()\\-]";

	private final String disallowedRegex;
	private final int maxLength;

	public CoinFlowInputFilter(String disallowedRegex, int maxLength)
	{
		this.disallowedRegex = disallowedRegex;
		this.maxLength = maxLength;
	}

	@Override
	public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException
	{
		if (string == null)
		{
			return;
		}
		String sanitized = string.replaceAll(disallowedRegex, "");
		if (sanitized.isEmpty())
		{
			return;
		}
		int currentLen = fb.getDocument().getLength();
		if (currentLen + sanitized.length() > maxLength)
		{
			int available = maxLength - currentLen;
			if (available <= 0)
			{
				return;
			}
			sanitized = sanitized.substring(0, available);
		}
		super.insertString(fb, offset, sanitized, attr);
	}

	@Override
	public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException
	{
		if (text == null)
		{
			super.replace(fb, offset, length, null, attrs);
			return;
		}
		String sanitized = text.replaceAll(disallowedRegex, "");
		int currentLen = fb.getDocument().getLength();
		int newLen = currentLen - length + sanitized.length();
		if (newLen > maxLength)
		{
			int available = maxLength - (currentLen - length);
			if (available <= 0)
			{
				return;
			}
			sanitized = sanitized.substring(0, available);
		}
		super.replace(fb, offset, length, sanitized, attrs);
	}
}
