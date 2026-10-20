package com.coinflow.reconciliation;

import org.junit.Assert;
import org.junit.Test;

public class BarrowsWearTest
{
	@Test
	public void stageDrop_costsBandAtSlotRate()
	{
		// helm 100 -> 75: 249 pts x 60
		Assert.assertEquals(14_940L, BarrowsWear.wearCostGp("Dharok's helm 100", "Dharok's helm 75"));
		// body 75 -> 50: 250 x 90
		Assert.assertEquals(22_500L, BarrowsWear.wearCostGp("Guthan's platebody 75", "Guthan's platebody 50"));
		// legs 50 -> 25: 250 x 80
		Assert.assertEquals(20_000L, BarrowsWear.wearCostGp("Karil's leatherskirt 50", "Karil's leatherskirt 25"));
		// weapon 25 -> 0: 250 x 100
		Assert.assertEquals(25_000L, BarrowsWear.wearCostGp("Verac's flail 25", "Verac's flail 0"));
	}

	@Test
	public void firstUse_bareTo100_costsOnePoint()
	{
		Assert.assertEquals(60L, BarrowsWear.wearCostGp("Dharok's helm", "Dharok's helm 100"));
		Assert.assertEquals(100L, BarrowsWear.wearCostGp("Ahrim's staff", "Ahrim's staff 100"));
	}

	@Test
	public void fullLifecycle_sumsToFullRepairCost()
	{
		String[] stages = {"Dharok's greataxe", "Dharok's greataxe 100", "Dharok's greataxe 75",
			"Dharok's greataxe 50", "Dharok's greataxe 25", "Dharok's greataxe 0"};
		long total = 0;
		for (int i = 0; i + 1 < stages.length; i++)
		{
			total += BarrowsWear.wearCostGp(stages[i], stages[i + 1]);
		}
		Assert.assertEquals(100_000L, total);
	}

	@Test
	public void upwardAndCrossPiecePairs_costNothing()
	{
		// Repair direction
		Assert.assertEquals(0L, BarrowsWear.wearCostGp("Dharok's helm 0", "Dharok's helm"));
		Assert.assertEquals(0L, BarrowsWear.wearCostGp("Dharok's helm 75", "Dharok's helm 100"));
		// Different brothers/slots never pair
		Assert.assertEquals(0L, BarrowsWear.wearCostGp("Dharok's helm 100", "Karil's coif 75"));
		Assert.assertEquals(0L, BarrowsWear.wearCostGp("Dharok's helm 100", "Dharok's platebody 75"));
	}

	@Test
	public void nonBarrowsNames_rejected()
	{
		Assert.assertNull(BarrowsWear.parse("Snakeskin boots 30"));
		Assert.assertNull(BarrowsWear.parse("Dharok's ceremonial helm"));
		Assert.assertNull(BarrowsWear.parse(null));
		Assert.assertEquals(0L, BarrowsWear.wearCostGp("Snakeskin boots 100", "Snakeskin boots 75"));
		Assert.assertFalse(BarrowsWear.isBarrowsBaseName("snakeskin boots"));
		Assert.assertTrue(BarrowsWear.isBarrowsBaseName("Verac's brassard"));
	}

	@Test
	public void allSixBrothers_allSlots_parse()
	{
		String[][] pieces = {
			{"Ahrim's hood", "Ahrim's robetop", "Ahrim's robeskirt", "Ahrim's staff"},
			{"Dharok's helm", "Dharok's platebody", "Dharok's platelegs", "Dharok's greataxe"},
			{"Guthan's helm", "Guthan's platebody", "Guthan's chainskirt", "Guthan's warspear"},
			{"Karil's coif", "Karil's leathertop", "Karil's leatherskirt", "Karil's crossbow"},
			{"Torag's helm", "Torag's platebody", "Torag's platelegs", "Torag's hammers"},
			{"Verac's helm", "Verac's brassard", "Verac's plateskirt", "Verac's flail"},
		};
		for (String[] set : pieces)
		{
			for (String piece : set)
			{
				Assert.assertNotNull(piece, BarrowsWear.parse(piece));
				Assert.assertNotNull(piece + " 75", BarrowsWear.parse(piece + " 75"));
			}
		}
	}

	@Test
	public void repairAllowance_capsAtBandMax()
	{
		// Stage 100: up to 250 pts used
		Assert.assertEquals(22_500L, BarrowsWear.repairAllowanceGp("Guthan's platebody 100"));
		// Stage 75: up to 500
		Assert.assertEquals(30_000L, BarrowsWear.repairAllowanceGp("Dharok's helm 75"));
		// Stage 50: up to 750
		Assert.assertEquals(75_000L, BarrowsWear.repairAllowanceGp("Dharok's greataxe 50"));
		// Stages 25 and 0: full 1000
		Assert.assertEquals(80_000L, BarrowsWear.repairAllowanceGp("Torag's platelegs 25"));
		Assert.assertEquals(60_000L, BarrowsWear.repairAllowanceGp("Dharok's helm 0"));
		// Bare pieces carry no repair allowance
		Assert.assertEquals(0L, BarrowsWear.repairAllowanceGp("Dharok's helm"));
	}

	@Test
	public void upwardPair_detection()
	{
		Assert.assertTrue(BarrowsWear.isUpwardPair("Dharok's helm 75", "Dharok's helm 100"));
		Assert.assertTrue(BarrowsWear.isUpwardPair("Dharok's helm 0", "Dharok's helm"));
		Assert.assertFalse(BarrowsWear.isUpwardPair("Dharok's helm 100", "Dharok's helm 75"));
		Assert.assertFalse(BarrowsWear.isUpwardPair("Amulet of glory(4)", "Amulet of glory(6)"));
	}
}
