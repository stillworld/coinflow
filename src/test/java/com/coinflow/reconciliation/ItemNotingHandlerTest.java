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
public class ItemNotingHandlerTest
{
	@Mock
	private ItemManager itemManager;

	@Mock
	private CoinFlowConfig config;

	private ItemNotingHandler handler;
	private CoinFlowSession session;

	private static final int UNNOTED_RANARR = 257;
	private static final int NOTED_RANARR = 258;

	private static final int UNNOTED_SNAPDRAGON = 3051;
	private static final int NOTED_SNAPDRAGON = 3052;

	private static final int UNNOTED_TEAK_PLANK = 8780;
	private static final int NOTED_TEAK_PLANK = 8781;

	private static final int UNNOTED_DARK_CRAB = 11936;
	private static final int NOTED_DARK_CRAB = 11937;

	@Before
	public void setUp()
	{
		handler = new ItemNotingHandler();
		session = CoinFlowSession.createNew();

		lenient().when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));

		stubNotedPair(UNNOTED_RANARR, NOTED_RANARR, "Grimy ranarr weed", 30_000L);
		stubNotedPair(UNNOTED_SNAPDRAGON, NOTED_SNAPDRAGON, "Grimy snapdragon", 45_000L);
		stubNotedPair(UNNOTED_TEAK_PLANK, NOTED_TEAK_PLANK, "Teak plank", 800L);
		stubNotedPair(UNNOTED_DARK_CRAB, NOTED_DARK_CRAB, "Dark crab", 1_200L);

		// Coins
		stubItem(ItemID.COINS, "Coins", 1L);
		lenient().when(itemManager.canonicalize(ItemID.COINS)).thenReturn(ItemID.COINS);
	}

	private void stubItem(int id, String name, long price)
	{
		ItemComposition comp = mock(ItemComposition.class);
		lenient().when(comp.getName()).thenReturn(name);
		lenient().when(itemManager.getItemComposition(id)).thenReturn(comp);
		lenient().when(itemManager.getItemPrice(id)).thenReturn(price);
	}

	private void stubNotedPair(int unnotedId, int notedId, String name, long price)
	{
		stubItem(unnotedId, name, price);
		stubItem(notedId, name, price);
		lenient().when(itemManager.canonicalize(unnotedId)).thenReturn(unnotedId);
		lenient().when(itemManager.canonicalize(notedId)).thenReturn(unnotedId);
	}

	private ReconciliationContext createContext(Map<Integer, Integer> rawGains, Map<Integer, Integer> rawLosses)
	{
		return new ReconciliationContext(
			rawGains,
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			null,
			null,
			session,
			itemManager,
			config,
			null,
			new HashMap<>(),
			new HashMap<>()
		);
	}

	@Test
	public void toolLeprechaun_notesHarvestedHerbs_reconcilesSwapWithoutExpenseOrGain()
	{
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(UNNOTED_RANARR, 8);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(NOTED_RANARR, 8);

		ReconciliationContext context = createContext(rawGains, rawLosses);
		handler.reconcile(context);

		Assert.assertTrue("rawLosses should be emptied after 1:1 noting", context.getRawLosses().isEmpty());
		Assert.assertTrue("rawGains should be emptied after 1:1 noting", context.getRawGains().isEmpty());
		Assert.assertTrue("No supply expenses for free leprechaun noting", context.getSupplyExpenses().isEmpty());
	}

	@Test
	public void phials_unnotesPlanksWithFee_reconcilesSwapAndRecordsCoinsExpense()
	{
		// Player unnotes 27 teak planks at Phials (5 coins each = 135 coins)
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(NOTED_TEAK_PLANK, 27);
		rawLosses.put(ItemID.COINS, 135);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(UNNOTED_TEAK_PLANK, 27);

		ReconciliationContext context = createContext(rawGains, rawLosses);
		context.setNotingService(true);
		handler.reconcile(context);

		Assert.assertTrue("rawLosses for planks and exact fee coins should be cleared", context.getRawLosses().isEmpty());
		Assert.assertTrue("rawGains for planks should be cleared", context.getRawGains().isEmpty());

		Assert.assertEquals(1, context.getSupplyExpenses().size());
		CoinFlowSession.TrackedItem feeExpense = context.getSupplyExpenses().get(ItemID.COINS);
		Assert.assertNotNull(feeExpense);
		Assert.assertEquals(135, feeExpense.getQuantity());
		Assert.assertEquals(1L, feeExpense.getPriceEach());
	}

	@Test
	public void piles_notesWildernessResourcesWithFee_reconcilesSwapAndRecordsCoinsExpense()
	{
		// Player notes 20 dark crabs at Piles (5 coins each = 100 coins)
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(UNNOTED_DARK_CRAB, 20);
		rawLosses.put(ItemID.COINS, 100);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(NOTED_DARK_CRAB, 20);

		ReconciliationContext context = createContext(rawGains, rawLosses);
		context.setNotingService(true);
		handler.reconcile(context);

		Assert.assertTrue(context.getRawLosses().isEmpty());
		Assert.assertTrue(context.getRawGains().isEmpty());

		CoinFlowSession.TrackedItem feeExpense = context.getSupplyExpenses().get(ItemID.COINS);
		Assert.assertNotNull(feeExpense);
		Assert.assertEquals(100, feeExpense.getQuantity());
	}

	@Test
	public void partialNoting_leavesRemainderInLosses()
	{
		// Lost 10 unnoted, but only gained 8 noted
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(UNNOTED_RANARR, 10);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(NOTED_RANARR, 8);

		ReconciliationContext context = createContext(rawGains, rawLosses);
		handler.reconcile(context);

		Assert.assertTrue("rawGains should be fully consumed", context.getRawGains().isEmpty());
		Assert.assertEquals(1, context.getRawLosses().size());
		Assert.assertEquals(Integer.valueOf(2), context.getRawLosses().get(UNNOTED_RANARR));
	}

	@Test
	public void multipleItemsNotedSameTick_reconcilesAll()
	{
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(UNNOTED_RANARR, 5);
		rawLosses.put(UNNOTED_SNAPDRAGON, 7);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(NOTED_RANARR, 5);
		rawGains.put(NOTED_SNAPDRAGON, 7);

		ReconciliationContext context = createContext(rawGains, rawLosses);
		handler.reconcile(context);

		Assert.assertTrue(context.getRawLosses().isEmpty());
		Assert.assertTrue(context.getRawGains().isEmpty());
	}

	@Test
	public void excessCoinsLost_onlyDeductsMaxExpectedFee()
	{
		// Unnoted 10 planks (50 gp fee), but player lost 200 coins
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(NOTED_TEAK_PLANK, 10);
		rawLosses.put(ItemID.COINS, 200);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(UNNOTED_TEAK_PLANK, 10);

		ReconciliationContext context = createContext(rawGains, rawLosses);
		context.setNotingService(true);
		handler.reconcile(context);

		Assert.assertTrue(context.getRawGains().isEmpty());
		// 50 gp fee recorded
		CoinFlowSession.TrackedItem feeExpense = context.getSupplyExpenses().get(ItemID.COINS);
		Assert.assertNotNull(feeExpense);
		Assert.assertEquals(50, feeExpense.getQuantity());

		// 150 coins remaining in rawLosses
		Assert.assertEquals(1, context.getRawLosses().size());
		Assert.assertEquals(Integer.valueOf(150), context.getRawLosses().get(ItemID.COINS));
	}

	@Test
	public void coincidentalCoinLoss_withoutNotingServiceIntent_noFeeAttributed()
	{
		// Note swap + unrelated coin loss in the same tick (e.g. free Tool Leprechaun
		// noting while coins left for another reason): the coins must NOT be eaten
		// as a service fee without a noting-service click intent.
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(UNNOTED_RANARR, 8);
		rawLosses.put(ItemID.COINS, 40);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(NOTED_RANARR, 8);

		ReconciliationContext context = createContext(rawGains, rawLosses);
		handler.reconcile(context);

		// The swap is still reconciled...
		Assert.assertTrue(context.getRawGains().isEmpty());
		// ...but the coins remain in rawLosses for other handlers, and no fee is recorded
		Assert.assertEquals(Integer.valueOf(40), context.getRawLosses().get(ItemID.COINS));
		Assert.assertTrue("No fee expense without noting-service intent", context.getSupplyExpenses().isEmpty());
	}

	@Test
	public void differentItems_notMatchedByHandler()
	{
		// Lost ranarr, gained snapdragon
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(UNNOTED_RANARR, 5);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(NOTED_SNAPDRAGON, 5);

		ReconciliationContext context = createContext(rawGains, rawLosses);
		handler.reconcile(context);

		Assert.assertEquals(1, context.getRawLosses().size());
		Assert.assertEquals(1, context.getRawGains().size());
		Assert.assertTrue(context.getSupplyExpenses().isEmpty());
	}

	@Test
	public void coins_neverTreatedAsNotedItems()
	{
		int fakeCoinsOtherId = 996;
		lenient().when(itemManager.canonicalize(fakeCoinsOtherId)).thenReturn(ItemID.COINS);

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(ItemID.COINS, 100);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(fakeCoinsOtherId, 100);

		ReconciliationContext context = createContext(rawGains, rawLosses);
		handler.reconcile(context);

		// Coins must not be matched as a noted/unnoted item
		Assert.assertEquals(1, context.getRawLosses().size());
		Assert.assertEquals(1, context.getRawGains().size());
		Assert.assertTrue(context.getSupplyExpenses().isEmpty());
	}

	@Test
	public void splitGains_matchesMultipleGainedEntriesForSingleLost()
	{
		int notedRanarrOtherVariant = 259;
		stubItem(notedRanarrOtherVariant, "Grimy ranarr weed", 30_000L);
		lenient().when(itemManager.canonicalize(notedRanarrOtherVariant)).thenReturn(UNNOTED_RANARR);

		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(UNNOTED_RANARR, 10);

		Map<Integer, Integer> rawGains = new HashMap<>();
		rawGains.put(NOTED_RANARR, 4);
		rawGains.put(notedRanarrOtherVariant, 6);

		ReconciliationContext context = createContext(rawGains, rawLosses);
		handler.reconcile(context);

		Assert.assertTrue(context.getRawLosses().isEmpty());
		Assert.assertTrue(context.getRawGains().isEmpty());
	}
}
