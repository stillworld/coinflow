package com.coinflow.reconciliation;

import com.coinflow.CoinFlowConfig;
import com.coinflow.CoinFlowSession;
import com.coinflow.ConsumableRegistry;
import com.coinflow.InventorySnapshot;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.ItemComposition;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@RunWith(MockitoJUnitRunner.class)
public class UniversalSkillSinkTest
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

		stubItem(ItemID.DRAGON_BONES, "Dragon bones", 2500L);
		stubItem(ItemID.INFERNAL_ASHES, "Infernal ashes", 1200L);
		stubItem(ItemID.RANARR_SEED, "Ranarr seed", 45000L);
		stubItem(ItemID.PLANK_MAHOGANY, "Mahogany plank", 1800L);
		stubItem(ItemID.WILLOW_LOGS, "Willow logs", 20L);
		stubItem(ItemID.COAL, "Coal", 160L);
	}

	private void stubItem(int id, String name, long price)
	{
		ItemComposition comp = mock(ItemComposition.class);
		lenient().when(comp.getName()).thenReturn(name);
		lenient().when(itemManager.getItemComposition(id)).thenReturn(comp);
		lenient().when(itemManager.getItemPrice(id)).thenReturn(price);
	}

	@Test
	public void testSkillSinkClassifier()
	{
		Set<Skill> prayer = Collections.singleton(Skill.PRAYER);
		assertTrue(ConsumableRegistry.isSkillSink(prayer, "Bones"));
		assertTrue(ConsumableRegistry.isSkillSink(prayer, "Dragon bones"));
		assertTrue(ConsumableRegistry.isSkillSink(prayer, "Superior dragon bones"));
		assertTrue(ConsumableRegistry.isSkillSink(prayer, "Infernal ashes"));
		assertTrue(ConsumableRegistry.isSkillSink(prayer, "Ensouled dragon head"));
		assertTrue(ConsumableRegistry.isSkillSink(prayer, "Ensouled goblin head"));
		assertFalse(ConsumableRegistry.isSkillSink(prayer, "Willow logs"));

		Set<Skill> firemaking = Collections.singleton(Skill.FIREMAKING);
		assertTrue(ConsumableRegistry.isSkillSink(firemaking, "Willow logs"));
		assertTrue(ConsumableRegistry.isSkillSink(firemaking, "Logs"));
		assertTrue(ConsumableRegistry.isSkillSink(firemaking, "Redwood logs"));
		assertFalse(ConsumableRegistry.isSkillSink(firemaking, "Collection log"));
		assertFalse(ConsumableRegistry.isSkillSink(firemaking, "Dragon bones"));

		Set<Skill> farming = Collections.singleton(Skill.FARMING);
		assertTrue(ConsumableRegistry.isSkillSink(farming, "Ranarr seed"));
		assertTrue(ConsumableRegistry.isSkillSink(farming, "Magic sapling"));
		assertTrue(ConsumableRegistry.isSkillSink(farming, "Ultracompost"));
		assertTrue(ConsumableRegistry.isSkillSink(farming, "Supercompost"));
		assertTrue(ConsumableRegistry.isSkillSink(farming, "Compost"));
		assertFalse(ConsumableRegistry.isSkillSink(farming, "Bottomless compost bucket"));
		assertFalse(ConsumableRegistry.isSkillSink(farming, "Dragon bones"));

		Set<Skill> construction = Collections.singleton(Skill.CONSTRUCTION);
		assertTrue(ConsumableRegistry.isSkillSink(construction, "Mahogany plank"));
		assertTrue(ConsumableRegistry.isSkillSink(construction, "Oak planks"));
		assertTrue(ConsumableRegistry.isSkillSink(construction, "Plank"));
		assertTrue(ConsumableRegistry.isSkillSink(construction, "Steel nails"));
		assertTrue(ConsumableRegistry.isSkillSink(construction, "Marble block"));
		assertTrue(ConsumableRegistry.isSkillSink(construction, "Gold leaf"));
		assertTrue(ConsumableRegistry.isSkillSink(construction, "Magic stone"));
		assertTrue(ConsumableRegistry.isSkillSink(construction, "Limestone brick"));
		assertTrue(ConsumableRegistry.isSkillSink(construction, "Bolt of cloth"));
		assertTrue(ConsumableRegistry.isSkillSink(construction, "Clockwork"));
		assertTrue(ConsumableRegistry.isSkillSink(construction, "Steel bar"));
		assertTrue(ConsumableRegistry.isSkillSink(construction, "Bagged plant 1"));
		assertFalse(ConsumableRegistry.isSkillSink(construction, "Plank sack"));
		assertFalse(ConsumableRegistry.isSkillSink(construction, "Coal"));

		Set<Skill> magic = Collections.singleton(Skill.MAGIC);
		assertTrue(ConsumableRegistry.isSkillSink(magic, "Ensouled dragon head"));
		assertFalse(ConsumableRegistry.isSkillSink(magic, "Dragon bones"));

		Set<Skill> runecraft = Collections.singleton(Skill.RUNECRAFT);
		assertTrue(ConsumableRegistry.isSkillSink(runecraft, "Pure essence"));
		assertTrue(ConsumableRegistry.isSkillSink(runecraft, "Daeyalt essence"));
		assertTrue(ConsumableRegistry.isSkillSink(runecraft, "Rune essence"));
		assertTrue(ConsumableRegistry.isSkillSink(runecraft, "Binding necklace"));
		assertFalse(ConsumableRegistry.isSkillSink(runecraft, "Dragon bones"));

		Set<Skill> hunter = Collections.singleton(Skill.HUNTER);
		assertTrue(ConsumableRegistry.isSkillSink(hunter, "Willow logs"));
		assertTrue(ConsumableRegistry.isSkillSink(hunter, "Raw beef"));
		assertTrue(ConsumableRegistry.isSkillSink(hunter, "Raw chicken"));
		assertTrue(ConsumableRegistry.isSkillSink(hunter, "Raw beast meat"));
		assertTrue(ConsumableRegistry.isSkillSink(hunter, "Raw rat meat"));
		assertTrue(ConsumableRegistry.isSkillSink(hunter, "Fishing bait"));
		assertFalse(ConsumableRegistry.isSkillSink(hunter, "Kebbit spike"));
		assertFalse(ConsumableRegistry.isSkillSink(hunter, "Kebbit claws"));
		assertFalse(ConsumableRegistry.isSkillSink(hunter, "Plank"));

		// Case-insensitivity check on isLog
		assertTrue(ConsumableRegistry.isLog("Maple logs"));
		assertTrue(ConsumableRegistry.isLog("MAPLE LOGS"));
		assertFalse(ConsumableRegistry.isLog("Collection log"));
	}

	@Test
	public void prayerBones_consumedDuringPrayerXP_recordedAsSupplyExpense()
	{
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(ItemID.DRAGON_BONES, 2);

		ReconciliationContext context = createContext(rawLosses);
		context.addActiveSkillingSkill(Skill.PRAYER);

		handler.reconcile(context);

		assertEquals(1, context.getSupplyExpenses().size());
		CoinFlowSession.TrackedItem expense = context.getSupplyExpenses().get(ItemID.DRAGON_BONES);
		assertNotNull(expense);
		assertEquals(2, expense.getQuantity());
		assertEquals(2500L, expense.getPriceEach());
		assertEquals(5000L, expense.getTotalValue());
	}

	@Test
	public void farmingSeed_consumedDuringFarmingXP_recordedAsSupplyExpense()
	{
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(ItemID.RANARR_SEED, 1);

		ReconciliationContext context = createContext(rawLosses);
		context.addActiveSkillingSkill(Skill.FARMING);

		handler.reconcile(context);

		assertEquals(1, context.getSupplyExpenses().size());
		CoinFlowSession.TrackedItem expense = context.getSupplyExpenses().get(ItemID.RANARR_SEED);
		assertNotNull(expense);
		assertEquals(1, expense.getQuantity());
		assertEquals(45000L, expense.getPriceEach());
	}

	@Test
	public void constructionPlanks_consumedDuringConstructionXP_recordedAsSupplyExpense()
	{
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(ItemID.PLANK_MAHOGANY, 3);

		ReconciliationContext context = createContext(rawLosses);
		context.addActiveSkillingSkill(Skill.CONSTRUCTION);

		handler.reconcile(context);

		assertEquals(1, context.getSupplyExpenses().size());
		CoinFlowSession.TrackedItem expense = context.getSupplyExpenses().get(ItemID.PLANK_MAHOGANY);
		assertNotNull(expense);
		assertEquals(3, expense.getQuantity());
		assertEquals(1800L, expense.getPriceEach());
		assertEquals(5400L, expense.getTotalValue());
	}

	@Test
	public void firemakingLogs_consumedDuringFiremaking_recordedAsSupplyExpense()
	{
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(ItemID.WILLOW_LOGS, 5);

		ReconciliationContext context = createContext(rawLosses);
		context.addActiveSkillingSkill(Skill.FIREMAKING);

		handler.reconcile(context);

		assertEquals(1, context.getSupplyExpenses().size());
		CoinFlowSession.TrackedItem expense = context.getSupplyExpenses().get(ItemID.WILLOW_LOGS);
		assertNotNull(expense);
		assertEquals(5, expense.getQuantity());
		assertEquals(20L, expense.getPriceEach());
		assertEquals(100L, expense.getTotalValue());
	}

	@Test
	public void itemsDroppedWithoutMatchingSkillXP_notTreatedAsSupplyExpense()
	{
		Map<Integer, Integer> rawLosses = new HashMap<>();
		rawLosses.put(ItemID.DRAGON_BONES, 1);
		rawLosses.put(ItemID.RANARR_SEED, 1);
		rawLosses.put(ItemID.WILLOW_LOGS, 1);

		// No active skills
		ReconciliationContext context = createContext(rawLosses);

		handler.reconcile(context);

		// None should be recorded as supply expenses
		assertTrue(context.getSupplyExpenses().isEmpty());
	}

	private ReconciliationContext createContext(Map<Integer, Integer> rawLosses)
	{
		return new ReconciliationContext(
			new HashMap<>(),
			rawLosses,
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			new HashMap<>(),
			InventorySnapshot.empty(),
			null,
			CoinFlowSession.createNew(),
			itemManager,
			config,
			Collections.emptySet()
		);
	}
}
