package com.coinflow.reconciliation;

import com.coinflow.CoinFlowConfig;
import com.coinflow.CoinFlowSession;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@RunWith(MockitoJUnitRunner.class)
public class ConsumablesAndDropsHandlerTest
{
	@Mock
	private ItemManager itemManager;

	@Mock
	private CoinFlowConfig config;

	private ConsumablesAndDropsHandler handler;

	@Before
	public void setUp()
	{
		handler = new ConsumablesAndDropsHandler();
		lenient().when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));

		stubItem(ItemID.COINS, "Coins", 1L);
		stubItem(ItemID.PLATINUM, "Platinum token", 1000L);
	}

	private void stubItem(int id, String name, long price)
	{
		ItemComposition comp = mock(ItemComposition.class);
		lenient().when(comp.getName()).thenReturn(name);
		lenient().when(itemManager.getItemComposition(id)).thenReturn(comp);
		lenient().when(itemManager.getItemPrice(id)).thenReturn(price);
	}

	private ReconciliationContext context(Map<Integer, Integer> gains, Map<Integer, Integer> losses)
	{
		return new ReconciliationContext(
			gains,
			losses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			null
		);
	}

	@Test
	public void coinsOnlyLoss_recordedAsSupplyExpense()
	{
		Map<Integer, Integer> losses = new HashMap<>();
		losses.put(ItemID.COINS, 12758);

		ReconciliationContext context = context(new HashMap<>(), losses);
		handler.reconcile(context);

		CoinFlowSession.TrackedItem expense = context.getSupplyExpenses().get(ItemID.COINS);
		Assert.assertNotNull("Coin loss must be recorded as a supply expense", expense);
		Assert.assertEquals(12758, expense.getQuantity());
		Assert.assertEquals(1L, expense.getPriceEach());
	}

	@Test
	public void coinsOnlyLoss_neverEntersDropBookkeeping()
	{
		Map<Integer, Integer> losses = new HashMap<>();
		losses.put(ItemID.COINS, 30000);

		ReconciliationContext context = context(new HashMap<>(), losses);
		handler.reconcile(context);

		Assert.assertTrue("Coins must not be tracked as a dropped owned item "
				+ "(would suppress future coin pickups)",
			context.getRecentlyDroppedOwnedItems().isEmpty());
		Assert.assertTrue(context.getRecentlyDroppedItems().isEmpty());
		Assert.assertTrue(context.getDroppedGainsDeductions().isEmpty());
	}

	@Test
	public void platinumLoss_expensedAtFaceValue()
	{
		Map<Integer, Integer> losses = new HashMap<>();
		losses.put(ItemID.PLATINUM, 5);

		ReconciliationContext context = context(new HashMap<>(), losses);
		handler.reconcile(context);

		CoinFlowSession.TrackedItem expense = context.getSupplyExpenses().get(ItemID.PLATINUM);
		Assert.assertNotNull("Platinum loss must be recorded as a supply expense", expense);
		Assert.assertEquals(5, expense.getQuantity());
		Assert.assertEquals(1000L, expense.getPriceEach());
	}

	@Test
	public void coinLoss_mixedWithConsumable_bothExpensed()
	{
		int sharkId = 385;
		ItemComposition sharkComp = mock(ItemComposition.class);
		lenient().when(sharkComp.getName()).thenReturn("Shark");
		lenient().when(sharkComp.getInventoryActions()).thenReturn(new String[]{"Eat"});
		lenient().when(itemManager.getItemComposition(sharkId)).thenReturn(sharkComp);
		lenient().when(itemManager.getItemPrice(sharkId)).thenReturn(900L);

		Map<Integer, Integer> losses = new HashMap<>();
		losses.put(ItemID.COINS, 50000);
		losses.put(sharkId, 1);

		ReconciliationContext context = context(new HashMap<>(), losses);
		handler.reconcile(context);

		Assert.assertNotNull("Coins must be expensed alongside other losses in the same diff",
			context.getSupplyExpenses().get(ItemID.COINS));
		Assert.assertNotNull("Eaten food must still be expensed",
			context.getSupplyExpenses().get(sharkId));
		Assert.assertEquals(50000, context.getSupplyExpenses().get(ItemID.COINS).getQuantity());
	}

	@Test
	public void coinSpend_retiresSessionCoinGains()
	{
		Map<Integer, CoinFlowSession.TrackedItem> loot = new HashMap<>();
		loot.put(ItemID.COINS, new CoinFlowSession.TrackedItem(ItemID.COINS, "Coins", 10000, 1L));
		CoinFlowSession session = CoinFlowSession.createNew()
			.withGainsLossesAndExpenses(loot, new HashMap<>(), new HashMap<>());

		Map<Integer, Integer> losses = new HashMap<>();
		losses.put(ItemID.COINS, 4000);
		ReconciliationContext context = new ReconciliationContext(
			new HashMap<>(), losses, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
			null, null, session, itemManager, config, null);
		handler.reconcile(context);

		Assert.assertEquals("Spent loot coins must be retired so a later drop cannot deduct them again",
			Long.valueOf(4000), context.getConsumedSessionQuantities().get(ItemID.COINS));
	}

	@Test
	public void platinumToCoinsExchange_nettedToNothing()
	{
		Map<Integer, Integer> gains = new HashMap<>();
		gains.put(ItemID.COINS, 5000);
		Map<Integer, Integer> losses = new HashMap<>();
		losses.put(ItemID.PLATINUM, 5);

		ReconciliationContext context = context(gains, losses);
		handler.reconcile(context);

		Assert.assertTrue("Exchange must not count as a spend", context.getSupplyExpenses().isEmpty());
		Assert.assertTrue("Exchange must not count as income", context.getRawGains().isEmpty());
	}

	@Test
	public void coinsToPlatinumExchange_nettedLeavesRemainderAsSpend()
	{
		Map<Integer, Integer> gains = new HashMap<>();
		gains.put(ItemID.PLATINUM, 3);
		Map<Integer, Integer> losses = new HashMap<>();
		losses.put(ItemID.COINS, 3500);

		ReconciliationContext context = context(gains, losses);
		handler.reconcile(context);

		Assert.assertTrue(context.getRawGains().isEmpty());
		Assert.assertEquals("Only the coins beyond the exchange are a spend",
			500, context.getSupplyExpenses().get(ItemID.COINS).getQuantity());
	}

	@Test
	public void dropIntentCoins_withDropsAsSpentOff_keepsOwnedDropPath()
	{
		Map<Integer, Integer> losses = new HashMap<>();
		losses.put(ItemID.COINS, 500);

		ReconciliationContext context = context(new HashMap<>(), losses);
		context.getDropIntentIds().add(ItemID.COINS);
		handler.reconcile(context);

		Assert.assertTrue("An intentional coin drop is not a spend — pickup must stay suppressed",
			context.getSupplyExpenses().isEmpty());
		Assert.assertEquals(Integer.valueOf(500),
			context.getRecentlyDroppedOwnedItems().get(ItemID.COINS));
	}

	@Test
	public void dropIntentCoins_withDropsAsSpentOn_expensedWithReversalBookkeeping()
	{
		lenient().when(config.trackSpent()).thenReturn(true);
		lenient().when(config.countDropsAsSpent()).thenReturn(true);

		Map<Integer, Integer> losses = new HashMap<>();
		losses.put(ItemID.COINS, 500);

		ReconciliationContext context = context(new HashMap<>(), losses);
		context.getDropIntentIds().add(ItemID.COINS);
		handler.reconcile(context);

		CoinFlowSession.TrackedItem expense = context.getSupplyExpenses().get(ItemID.COINS);
		Assert.assertNotNull("Drop-intent coins with drops-as-spent on must be expensed", expense);
		Assert.assertEquals(Integer.valueOf(500),
			context.getRecentlyExpensedDrops().get(ItemID.COINS));
	}
}
