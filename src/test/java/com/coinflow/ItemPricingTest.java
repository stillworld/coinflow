package com.coinflow;

import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ItemPricing#priced}: the price-plus-basis lookup used
 * by row provenance.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ItemPricingTest
{
	private ItemManager itemManager;

	@Before
	public void setUp()
	{
		itemManager = mock(ItemManager.class);
		when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
	}

	private static ItemComposition comp(long haPrice)
	{
		ItemComposition comp = mock(ItemComposition.class);
		when(comp.getHaPrice()).thenReturn((int) haPrice);
		return comp;
	}

	@Test
	public void coinsAndPlatinum_priceAtFaceValue()
	{
		Assert.assertEquals(1L, ItemPricing.priced(ItemID.COINS, itemManager).price);
		Assert.assertEquals(CoinFlowSession.TrackedItem.PriceBasis.FIXED,
			ItemPricing.priced(ItemID.COINS, itemManager).basis);
		Assert.assertEquals(1000L, ItemPricing.priced(ItemID.PLATINUM, itemManager).price);
		Assert.assertEquals(CoinFlowSession.TrackedItem.PriceBasis.FIXED,
			ItemPricing.priced(ItemID.PLATINUM, itemManager).basis);
	}

	@Test
	public void gePricedItem_usesGeBasis()
	{
		when(itemManager.getItemPrice(385)).thenReturn(1000L);
		ItemPricing.Priced priced = ItemPricing.priced(385, itemManager);
		Assert.assertEquals(1_000L, priced.price);
		Assert.assertEquals(CoinFlowSession.TrackedItem.PriceBasis.GE_PRICE, priced.basis);
	}

	@Test
	public void unpricedTradeable_fallsBackToHighAlch()
	{
		when(itemManager.getItemPrice(9999)).thenReturn(0L);
		ItemComposition c = comp(60);
		when(itemManager.getItemComposition(9999)).thenReturn(c);
		ItemPricing.Priced priced = ItemPricing.priced(9999, itemManager);
		Assert.assertEquals(60L, priced.price);
		Assert.assertEquals(CoinFlowSession.TrackedItem.PriceBasis.HIGH_ALCH, priced.basis);
	}

	@Test
	public void unalchableUntradeable_isUnpriced()
	{
		// Blessed bone shards carry a nominal HA price in their composition but
		// cannot be alched or sold.
		when(itemManager.getItemPrice(ItemID.BLESSED_BONE_SHARD)).thenReturn(0L);
		ItemComposition c = comp(10);
		when(itemManager.getItemComposition(ItemID.BLESSED_BONE_SHARD)).thenReturn(c);
		ItemPricing.Priced priced = ItemPricing.priced(ItemID.BLESSED_BONE_SHARD, itemManager);
		Assert.assertEquals(0L, priced.price);
		Assert.assertEquals(CoinFlowSession.TrackedItem.PriceBasis.UNPRICED, priced.basis);
	}

	@Test
	public void missingComposition_isUnpriced()
	{
		when(itemManager.getItemPrice(4242)).thenReturn(0L);
		when(itemManager.getItemComposition(4242)).thenReturn(null);
		ItemPricing.Priced priced = ItemPricing.priced(4242, itemManager);
		Assert.assertEquals(0L, priced.price);
		Assert.assertEquals(CoinFlowSession.TrackedItem.PriceBasis.UNPRICED, priced.basis);
	}

	@Test
	public void nullItemManager_isUnpriced()
	{
		ItemPricing.Priced priced = ItemPricing.priced(385, null);
		Assert.assertEquals(0L, priced.price);
		Assert.assertEquals(CoinFlowSession.TrackedItem.PriceBasis.UNPRICED, priced.basis);
	}

	@Test
	public void priceOf_delegatesToPriced()
	{
		when(itemManager.getItemPrice(385)).thenReturn(1000L);
		Assert.assertEquals(ItemPricing.priced(385, itemManager).price,
			ItemPricing.priceOf(385, itemManager));
	}
}
