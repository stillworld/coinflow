package com.coinflow;

import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import org.junit.Assert;
import org.junit.Test;

public class CoinFlowInputFilterTest
{
	@Test
	public void digitsOnlyFilter_rejectsSpecialCharactersAndLetters() throws BadLocationException
	{
		PlainDocument doc = new PlainDocument();
		doc.setDocumentFilter(new CoinFlowInputFilter(CoinFlowInputFilter.DIGITS_ONLY, 10));

		// Insert valid digits
		doc.insertString(0, "12345", null);
		Assert.assertEquals("12345", doc.getText(0, doc.getLength()));

		// Try inserting special characters
		doc.insertString(doc.getLength(), "@#$%^&*!", null);
		Assert.assertEquals("12345", doc.getText(0, doc.getLength()));

		// Try inserting negative sign
		doc.insertString(0, "-", null);
		Assert.assertEquals("12345", doc.getText(0, doc.getLength()));

		// Try inserting mixed text
		doc.insertString(doc.getLength(), "abc678!@#", null);
		Assert.assertEquals("12345678", doc.getText(0, doc.getLength()));
	}

	@Test
	public void targetGpFilter_allowsValidSuffixesAndSeparators_rejectsSpecialChars() throws BadLocationException
	{
		PlainDocument doc = new PlainDocument();
		doc.setDocumentFilter(new CoinFlowInputFilter(CoinFlowInputFilter.TARGET_GP, 15));

		doc.insertString(0, "10,500,000", null);
		Assert.assertEquals("10,500,000", doc.getText(0, doc.getLength()));

		// Suffixes k, m, b allowed
		doc.remove(0, doc.getLength());
		doc.insertString(0, "1.5m", null);
		Assert.assertEquals("1.5m", doc.getText(0, doc.getLength()));

		// Disallowed symbols rejected
		doc.insertString(doc.getLength(), "!@#$%^&*()_+=", null);
		Assert.assertEquals("1.5m", doc.getText(0, doc.getLength()));
	}

	@Test
	public void goalNameFilter_rejectsSpecialCharacters() throws BadLocationException
	{
		PlainDocument doc = new PlainDocument();
		doc.setDocumentFilter(new CoinFlowInputFilter(CoinFlowInputFilter.GOAL_NAME, 32));

		doc.insertString(0, "My Goal (2026) - Bandos!", null);
		// '!' is disallowed by GOAL_NAME
		Assert.assertEquals("My Goal (2026) - Bandos", doc.getText(0, doc.getLength()));

		doc.remove(0, doc.getLength());
		doc.insertString(0, "<script>alert(1)</script>", null);
		// '<' and '>' are disallowed
		Assert.assertEquals("scriptalert(1)/script", doc.getText(0, doc.getLength()));
	}

	@Test
	public void maxLengthEnforced() throws BadLocationException
	{
		PlainDocument doc = new PlainDocument();
		doc.setDocumentFilter(new CoinFlowInputFilter(CoinFlowInputFilter.DIGITS_ONLY, 5));

		doc.insertString(0, "1234567890", null);
		Assert.assertEquals("12345", doc.getText(0, doc.getLength()));
	}

	@Test
	public void ignoredItemsFilter_allowsNamesAndCommas_rejectsSpecialChars() throws BadLocationException
	{
		PlainDocument doc = new PlainDocument();
		doc.setDocumentFilter(new CoinFlowInputFilter(CoinFlowInputFilter.IGNORED_ITEMS, 100));

		doc.insertString(0, "Logs, Raw fish (trout), Uncut diamond - gem", null);
		Assert.assertEquals("Logs, Raw fish (trout), Uncut diamond - gem", doc.getText(0, doc.getLength()));

		doc.insertString(doc.getLength(), "!@#$%^&*", null);
		Assert.assertEquals("Logs, Raw fish (trout), Uncut diamond - gem", doc.getText(0, doc.getLength()));
	}
}
