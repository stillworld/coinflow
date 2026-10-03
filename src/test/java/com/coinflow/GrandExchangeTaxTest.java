package com.coinflow;

import net.runelite.api.gameval.ItemID;
import org.junit.Assert;
import org.junit.Test;

public class GrandExchangeTaxTest
{
	private static final int HELM = 11497;

	@Test
	public void taxPerItem_twoPercentRoundedDown()
	{
		Assert.assertEquals(1180L, GrandExchangeTax.taxPerItem(HELM, 59000));
		Assert.assertEquals(20L, GrandExchangeTax.taxPerItem(HELM, 1000));
		Assert.assertEquals(2L, GrandExchangeTax.taxPerItem(HELM, 149)); // floor(2.98)
	}

	@Test
	public void taxPerItem_belowFiftyGp_isZero()
	{
		Assert.assertEquals(0L, GrandExchangeTax.taxPerItem(HELM, 49));
		Assert.assertEquals(1L, GrandExchangeTax.taxPerItem(HELM, 50));
	}

	@Test
	public void taxPerItem_cappedAtFiveMillion()
	{
		Assert.assertEquals(5_000_000L, GrandExchangeTax.taxPerItem(HELM, 250_000_000L));
		Assert.assertEquals(5_000_000L, GrandExchangeTax.taxPerItem(HELM, 2_000_000_000L));
		Assert.assertEquals(4_999_980L, GrandExchangeTax.taxPerItem(HELM, 249_999_000L));
	}

	@Test
	public void taxPerItem_exemptItems()
	{
		Assert.assertEquals(0L, GrandExchangeTax.taxPerItem(ItemID.OSRS_BOND, 10_000_000L));
		Assert.assertEquals(0L, GrandExchangeTax.taxPerItem(ItemID.LOBSTER, 1000));
		Assert.assertEquals(0L, GrandExchangeTax.taxPerItem(ItemID.HAMMER, 1000));
	}

	@Test
	public void taxPerItem_nonPositivePrice_isZero()
	{
		Assert.assertEquals(0L, GrandExchangeTax.taxPerItem(HELM, 0));
		Assert.assertEquals(0L, GrandExchangeTax.taxPerItem(HELM, -5));
	}

	@Test
	public void netProceeds_multiItemFill()
	{
		// 10 sharks at 1,000: 20 tax each
		Assert.assertEquals(9800L, GrandExchangeTax.netProceeds(HELM, 10, 10_000L));
		// exempt
		Assert.assertEquals(10_000L, GrandExchangeTax.netProceeds(ItemID.LOBSTER, 10, 10_000L));
	}

	@Test
	public void netProceeds_degenerateInputs()
	{
		Assert.assertEquals(0L, GrandExchangeTax.netProceeds(HELM, 0, 1000L));
		Assert.assertEquals(0L, GrandExchangeTax.netProceeds(HELM, 5, 0L));
		Assert.assertEquals(0L, GrandExchangeTax.netProceeds(HELM, 5, -100L));
	}
}
