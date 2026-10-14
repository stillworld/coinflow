package com.coinflow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;

/**
 * Dwarf multicannon bookkeeping. Setting a cannon up removes its four parts
 * (base/stand/barrels/furnace) and loaded cannonballs from the inventory for
 * as long as it stays deployed — far beyond the ~6-minute expiry of the
 * dropped-items bookkeeping — so a later pick-up (or reclaim from Nulodion
 * after logout/decay) would book the returned kit as fresh profit.
 *
 * Deployed parts are held in {@link #deployedParts} with no expiry — they
 * survive logout since the cannon itself does — and clear only on
 * startUp/shutDown/session reset. Loaded balls are still expensed as ammo at
 * load time (live GP/hr stays right); balls returned on pick-up or Empty
 * reverse that expense rather than booking loot.
 */
@Slf4j
final class CannonTracker
{
	/** The four kit parts (plus the Leagues ornament-kit variants). */
	private static final Set<Integer> CANNON_PART_IDS = new HashSet<>(java.util.Arrays.asList(
		ItemID.TWPART1, ItemID.TWPART2, ItemID.TWPART3, ItemID.TWPART4,
		ItemID.LEAGUE_3_MULTICANNON_BASE, ItemID.LEAGUE_3_MULTICANNON_STAND,
		ItemID.LEAGUE_3_MULTICANNON_BARRELS, ItemID.LEAGUE_3_MULTICANNON_FURNACE));

	/** Cannonball types the dwarf cannon can be loaded and fired with. */
	private static final Set<Integer> CANNONBALL_IDS = new HashSet<>(java.util.Arrays.asList(
		ItemID.MCANNONBALL, ItemID.GRANITE_CANNONBALL));

	private final CoinFlowPlugin plugin;

	/** Canonical id -> quantity of parts currently deployed as a cannon. */
	private final Map<Integer, Integer> deployedParts = new HashMap<>();

	/** Canonical id -> quantity of balls loaded into the deployed cannon. */
	private final Map<Integer, Integer> loadedBalls = new HashMap<>();

	CannonTracker(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	boolean isDeployed()
	{
		return !deployedParts.isEmpty();
	}

	/** Clears all cannon state (startUp/shutDown/session reset). */
	void reset()
	{
		deployedParts.clear();
		loadedBalls.clear();
	}

	/** Drops only the loaded-balls estimate (logout/session transient reset). */
	void clearLoadedBalls()
	{
		loadedBalls.clear();
	}

	static boolean isCannonPart(int canonicalItemId)
	{
		return CANNON_PART_IDS.contains(canonicalItemId);
	}

	static boolean isCannonball(int canonicalItemId)
	{
		return CANNONBALL_IDS.contains(canonicalItemId);
	}

	/**
	 * Nets deployed-cannon traffic out of an inventory diff. Must run on every
	 * inventory diff — including diffs that end up re-baselined, where a part
	 * gain would otherwise desync {@link #deployedParts} — but never while a
	 * shop is open (a shop purchase handled by ShopTracker is not a deploy).
	 *
	 * @param gains gained quantities, canonical ids, mutated in place
	 * @param losses lost quantities, canonical ids, mutated in place
	 * @param currentTick client tick, used to respect Drop-click intents
	 * @param shopOpen whether a shop interface is open — part/ball gains then
	 *        are purchases, never cannon returns, and must not net
	 * @return id -&gt; quantity of loaded balls returned to the inventory; the
	 *         caller applies these as expense reversals
	 */
	Map<Integer, Long> netDiff(Map<Integer, Integer> gains, Map<Integer, Integer> losses,
		int currentTick, boolean shopOpen)
	{
		Map<Integer, Long> reversals = new HashMap<>();
		boolean wasDeployed = isDeployed();

		if (losses != null)
		{
			Iterator<Map.Entry<Integer, Integer>> it = losses.entrySet().iterator();
			while (it.hasNext())
			{
				Map.Entry<Integer, Integer> entry = it.next();
				int id = plugin.itemManager != null ? plugin.itemManager.canonicalize(entry.getKey()) : entry.getKey();
				if (isCannonPart(id) && !plugin.isDropIntentActive(id, currentTick))
				{
					deployedParts.merge(id, entry.getValue(), Integer::sum);
					it.remove();
					log.debug("Cannon part deployed: {} x{}", id, entry.getValue());
				}
				else if (isCannonball(id) && wasDeployed && !plugin.isDropIntentActive(id, currentTick))
				{
					loadedBalls.merge(id, entry.getValue(), Integer::sum);
					log.debug("Loaded cannonball x{} (total {})", entry.getValue(), loadedBalls.get(id));
				}
			}
		}

		if (gains != null && !shopOpen)
		{
			Iterator<Map.Entry<Integer, Integer>> it = gains.entrySet().iterator();
			while (it.hasNext())
			{
				Map.Entry<Integer, Integer> entry = it.next();
				int id = plugin.itemManager != null ? plugin.itemManager.canonicalize(entry.getKey()) : entry.getKey();
				if (isCannonPart(id) && wasDeployed)
				{
					int returned = Math.min(entry.getValue(), deployedParts.getOrDefault(id, 0));
					if (returned > 0)
					{
						deployedParts.compute(id, (k, v) -> v - returned <= 0 ? null : v - returned);
						entry.setValue(entry.getValue() - returned);
						log.debug("Cannon part returned: {} x{}", id, returned);
					}
					if (entry.getValue() <= 0)
					{
						it.remove();
					}
				}
				else if (isCannonball(id) && wasDeployed)
				{
					int returned = Math.min(entry.getValue(), loadedBalls.getOrDefault(id, 0));
					if (returned > 0)
					{
						loadedBalls.compute(id, (k, v) -> v - returned <= 0 ? null : v - returned);
						reversals.merge(id, (long) returned, Long::sum);
						entry.setValue(entry.getValue() - returned);
						log.debug("Cannonball returned: {} x{}", id, returned);
					}
					if (entry.getValue() <= 0)
					{
						it.remove();
					}
				}
			}
		}

		if (deployedParts.isEmpty())
		{
			loadedBalls.clear();
		}
		return reversals;
	}
}
