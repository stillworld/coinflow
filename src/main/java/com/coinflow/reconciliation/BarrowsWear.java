package com.coinflow.reconciliation;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.gameval.ItemID;

/**
 * Barrows equipment wear accounting. Degradation is only visible at the four
 * stage transitions (100 -> 75 -> 50 -> 25 -> 0); the underlying points are
 * hidden. Each piece has 1000 points: first use costs 1 (bare -> 100), the
 * 100 -> 75 drop covers points 750..999 (249), and each later stage drop
 * covers a full 250-point band. NPC repair is priced per point by slot:
 * weapon 100 gp, body 90, legs 80, helm 60 (the armour-stand Smithing
 * discount is not modelled — wear is valued at the NPC rate).
 */
public final class BarrowsWear
{
	/**
	 * Item id backing the synthetic GP-denominated "Barrows wear" expense
	 * row (qty = gp, price = 1). An interface-only sprite that can never
	 * appear in an inventory, so the id can never collide with a real item.
	 */
	public static final int WEAR_ROW_ID = ItemID.BARROWS_INTERFACE_DHAROK;
	public static final String WEAR_ROW_NAME = "Barrows wear";

	// Degrade stage suffix, e.g. "Dharok's helm 75".
	private static final Pattern STAGE_PATTERN = Pattern.compile("^(.*?)\\s+(100|75|50|25|0)$");

	// Points remaining when a piece shows this stage label: the label
	// sits at the top of its band (e.g. "100" covers 750..999).
	private static final Map<String, Integer> STAGE_TOP_POINTS;
	// Most points a piece at this stage could have consumed (the bottom of
	// its band): "100" has used 1..250, "75" up to 500, etc.
	private static final Map<String, Integer> STAGE_MAX_USED;
	static
	{
		Map<String, Integer> stages = new HashMap<>();
		stages.put("100", 999);
		stages.put("75", 750);
		stages.put("50", 500);
		stages.put("25", 250);
		stages.put("0", 0);
		STAGE_TOP_POINTS = Collections.unmodifiableMap(stages);

		Map<String, Integer> used = new HashMap<>();
		used.put("100", 250);
		used.put("75", 500);
		used.put("50", 750);
		used.put("25", 1000);
		used.put("0", 1000);
		STAGE_MAX_USED = Collections.unmodifiableMap(used);
	}

	// Bare base name -> NPC repair cost per degradation point.
	private static final Map<String, Integer> POINT_COSTS;
	static
	{
		Map<String, Integer> costs = new HashMap<>();
		// Weapons: 100 gp/point (100,000 full)
		for (String n : new String[]{"ahrim's staff", "dharok's greataxe", "guthan's warspear",
			"karil's crossbow", "torag's hammers", "verac's flail"})
		{
			costs.put(n, 100);
		}
		// Bodies: 90 gp/point (90,000 full)
		for (String n : new String[]{"ahrim's robetop", "dharok's platebody", "guthan's platebody",
			"karil's leathertop", "torag's platebody", "verac's brassard"})
		{
			costs.put(n, 90);
		}
		// Legs: 80 gp/point (80,000 full)
		for (String n : new String[]{"ahrim's robeskirt", "dharok's platelegs", "guthan's chainskirt",
			"karil's leatherskirt", "torag's platelegs", "verac's plateskirt"})
		{
			costs.put(n, 80);
		}
		// Helms: 60 gp/point (60,000 full)
		for (String n : new String[]{"ahrim's hood", "dharok's helm", "guthan's helm",
			"karil's coif", "torag's helm", "verac's helm"})
		{
			costs.put(n, 60);
		}
		POINT_COSTS = Collections.unmodifiableMap(costs);
	}

	/** A parsed Barrows piece: bare base name, remaining points, gp/point. */
	public static final class Piece
	{
		public final String baseName;
		public final int points;
		public final int costPerPoint;
		/** Max points this stage could have consumed (0 for a bare piece). */
		public final int maxUsedPoints;

		private Piece(String baseName, int points, int costPerPoint, int maxUsedPoints)
		{
			this.baseName = baseName;
			this.points = points;
			this.costPerPoint = costPerPoint;
			this.maxUsedPoints = maxUsedPoints;
		}
	}

	/** Parses a Barrows piece (bare or staged); null for anything else. */
	public static Piece parse(String name)
	{
		if (name == null)
		{
			return null;
		}
		String base = name;
		int points = 1000;
		int maxUsed = 0;
		Matcher m = STAGE_PATTERN.matcher(name);
		if (m.matches())
		{
			base = m.group(1).trim();
			Integer p = STAGE_TOP_POINTS.get(m.group(2));
			if (p == null)
			{
				return null;
			}
			points = p;
			maxUsed = STAGE_MAX_USED.get(m.group(2));
		}
		Integer cost = POINT_COSTS.get(base.toLowerCase(java.util.Locale.ROOT));
		return cost == null ? null : new Piece(base, points, cost, maxUsed);
	}

	/**
	 * Same Barrows piece moving to a lower degradation state (bare -> 100,
	 * 100 -> 75, ..., or a broader match across multiple stages). Returns
	 * the gp wear cost for the transition, or 0 if this isn't a Barrows
	 * downgrade.
	 */
	public static long wearCostGp(String lostName, String gainedName)
	{
		Piece lost = parse(lostName);
		Piece gained = parse(gainedName);
		if (lost == null || gained == null
			|| !lost.baseName.equalsIgnoreCase(gained.baseName)
			|| gained.points >= lost.points)
		{
			return 0;
		}
		return (long) (lost.points - gained.points) * lost.costPerPoint;
	}

	/**
	 * Most wear a piece at this stage could represent (its full band), in gp.
	 * Caps how much of a repair fee is absorbed: stage 100 -> 250 pts,
	 * 75 -> 500, 50 -> 750, 25/0 -> 1000.
	 */
	public static long repairAllowanceGp(String stagedName)
	{
		Piece piece = parse(stagedName);
		return piece == null ? 0 : (long) piece.maxUsedPoints * piece.costPerPoint;
	}

	/**
	 * Same Barrows piece moving to a HIGHER state (a repair, or a gear swap
	 * between two copies at different stages seen as an INV diff).
	 */
	public static boolean isUpwardPair(String lostName, String gainedName)
	{
		Piece lost = parse(lostName);
		Piece gained = parse(gainedName);
		return lost != null && gained != null
			&& lost.baseName.equalsIgnoreCase(gained.baseName)
			&& gained.points > lost.points;
	}

	/** True if the bare name is one of the six brothers' pieces. */
	public static boolean isBarrowsBaseName(String baseName)
	{
		return baseName != null
			&& POINT_COSTS.containsKey(baseName.toLowerCase(java.util.Locale.ROOT));
	}
}
