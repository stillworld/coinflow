package com.coinflow;

import org.junit.Assert;
import org.junit.Test;

public class ActionClassifiersTest
{
	@Test
	public void isContainerOrChargedItemTarget_knownContainers_true()
	{
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("gem bag"));
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("herb sack"));
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("seed box"));
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("essence pouch"));
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("toxic blowpipe"));
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("coal bag"));
	}

	@Test
	public void isContainerOrChargedItemTarget_unchargeRefundItems_true()
	{
		// These all refund stored runes/materials on Uncharge — without a
		// re-baseline the refund books as phantom profit.
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("ring of the elements"));
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("ring of shadows"));
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("soul bearer"));
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("circlet of water"));
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("cowbell amulet"));
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("pendant of ates"));
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("bone staff"));
		Assert.assertTrue(ActionClassifiers.isContainerOrChargedItemTarget("eye of ayak"));
	}

	@Test
	public void isContainerOrChargedItemTarget_coinPouch_false()
	{
		// Thieving coin pouches convert to coins in place. "Open" is a real
		// gain and must not trigger a container re-baseline that swallows it.
		Assert.assertFalse(ActionClassifiers.isContainerOrChargedItemTarget("coin pouch"));
	}

	@Test
	public void isContainerOrChargedItemTarget_unrelated_false()
	{
		Assert.assertFalse(ActionClassifiers.isContainerOrChargedItemTarget(null));
		Assert.assertFalse(ActionClassifiers.isContainerOrChargedItemTarget(""));
		Assert.assertFalse(ActionClassifiers.isContainerOrChargedItemTarget("shark"));
		Assert.assertFalse(ActionClassifiers.isContainerOrChargedItemTarget("iron ore"));
	}
}
