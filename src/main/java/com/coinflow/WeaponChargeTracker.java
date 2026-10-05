package com.coinflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.gameval.VarbitID;

/**
 * Tracks resources consumed inside charged weapons and equipment (runes stored in a
 * trident, scales in a blowpipe, crystal shards in a Bow of Faerdhinen, etc.).
 * These costs never produce an inventory diff.
 *
 * Two signals are used:
 *  - Attack triggers: the cast graphic or attack animation played per attack while a
 *    given weapon is equipped. This is how charge-consuming weapons (tridents, shadow,
 *    bowfa, blowpipe, ...) are observed because their CHARGES_* varbits do not update
 *    live during combat.
 *  - CHARGES_*_QUANTITY varbits: polled each tick for items whose charges drain
 *    passively (bonecrusher, ring of suffering, crystal armour, ...). Where these
 *    varbits turn out not to update live, the item is simply missed rather than
 *    double counted.
 */
@Slf4j
final class WeaponChargeTracker
{
	/** Resource units consumed per charge/attack. Numerator/denominator supports fractions (e.g. 1/100 shard). */
	private static final class ChargeCost
	{
		final int itemId;
		final int numerator;
		final int denominator;

		ChargeCost(int itemId, int numerator, int denominator)
		{
			this.itemId = itemId;
			this.numerator = numerator;
			this.denominator = denominator;
		}
	}

	private static final class WeaponConfig
	{
		final String name;
		final List<ChargeCost> costs;

		WeaponConfig(String name, List<ChargeCost> costs)
		{
			this.name = name;
			this.costs = Collections.unmodifiableList(costs);
		}

		WeaponConfig(String name, ChargeCost... costs)
		{
			this(name, Arrays.asList(costs));
		}
	}

	/** A weapon whose attacks are detected via cast graphics or attack animations. */
	private static final class AttackProfile
	{
		final String weaponNameMatch;
		final Set<Integer> graphics;
		final Set<Integer> animations;
		final List<ChargeCost> costsPerAttack;
		final boolean consumesDarts;
		/**
		 * Varbits that also report this weapon's charge count (1 varbit unit == 1 attack).
		 * Attack-triggered consumption is deduplicated against these varbit deltas so a
		 * charge is never counted twice if the varbit turns out to update live.
		 */
		final Set<Integer> dedupeVarbitIds;

		AttackProfile(String weaponNameMatch, Set<Integer> graphics, Set<Integer> animations,
			boolean consumesDarts, Set<Integer> dedupeVarbitIds, ChargeCost... costsPerAttack)
		{
			this.weaponNameMatch = weaponNameMatch;
			this.graphics = graphics;
			this.animations = animations;
			this.consumesDarts = consumesDarts;
			this.dedupeVarbitIds = dedupeVarbitIds;
			this.costsPerAttack = Collections.unmodifiableList(Arrays.asList(costsPerAttack));
		}
	}

	/** Attack-triggered charge consumption recently expensed, awaiting varbit dedup. */
	private static final class AttackDedupe
	{
		int count;
		int lastTick;
	}

	private static final Map<Integer, WeaponConfig> CONFIGS = new HashMap<>();
	private static final List<AttackProfile> ATTACK_PROFILES = new ArrayList<>();

	// Dart name -> item id, used to price blowpipe ammo from "Use <dart> -> Toxic blowpipe" clicks
	private static final Map<String, Integer> DART_ITEM_IDS = new HashMap<>();

	static
	{
		// -- Attack-triggered weapons --
		// Cast graphics / attack animations are the primary signal. Each profile also
		// lists the weapon's CHARGES_* varbit for deduplication: if the varbit does
		// update live, its delta is netted against attack-counted charges instead of
		// double counting. (Blowpipe is excluded because its varbit counts scales,
		// not attacks, so unit-level dedup is impossible; the 2/3-scale attack model
		// is the only signal used there.)

		Set<Integer> tridentVarbits = new HashSet<>(Arrays.asList(
			VarbitID.CHARGES_TRIDENT_OF_THE_SEAS_QUANTITY,
			VarbitID.CHARGES_TRIDENT_OF_THE_SEAS_E_QUANTITY));
		Set<Integer> wildernessVarbit = Collections.singleton(VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY);
		Set<Integer> swordAnims = new HashSet<>(Arrays.asList(
			AnimationID.HUMAN_SWORD_SLASH, AnimationID.HUMAN_SWORD_STAB));

		// Trident of the seas / (e): 1 chaos + 1 death + 5 fire + 10 gp per cast.
		ATTACK_PROFILES.add(new AttackProfile("trident of the seas",
			Collections.singleton(SpotanimID.SLAYER_TOTS_CASTING), Collections.emptySet(), false, tridentVarbits,
			new ChargeCost(ItemID.CHAOSRUNE, 1, 1),
			new ChargeCost(ItemID.DEATHRUNE, 1, 1),
			new ChargeCost(ItemID.FIRERUNE, 5, 1),
			new ChargeCost(ItemID.COINS, 10, 1)));
		// Trident of the swamp / (e): same runes but a Zulrah's scale instead of coins.
		ATTACK_PROFILES.add(new AttackProfile("trident of the swamp",
			Collections.singleton(SpotanimID.TOXIC_TOTS_CASTING), Collections.emptySet(), false, tridentVarbits,
			new ChargeCost(ItemID.CHAOSRUNE, 1, 1),
			new ChargeCost(ItemID.DEATHRUNE, 1, 1),
			new ChargeCost(ItemID.FIRERUNE, 5, 1),
			new ChargeCost(ItemID.SNAKEBOSS_SCALE, 1, 1)));
		// Powered-staff cast animation. TODO verify sang shares anim 1167
		ATTACK_PROFILES.add(new AttackProfile("sanguinesti staff",
			Collections.emptySet(), Collections.singleton(AnimationID.HUMAN_CASTWAVE_STAFF), false,
			Collections.singleton(VarbitID.CHARGES_SANGUINESTI_STAFF_QUANTITY),
			new ChargeCost(ItemID.BLOODRUNE, 3, 1)));
		ATTACK_PROFILES.add(new AttackProfile("tumeken's shadow",
			Collections.singleton(SpotanimID.TUMEKENS_SHADOW_CASTING), Collections.emptySet(), false,
			Collections.singleton(VarbitID.CHARGES_TUMEKENS_SHADOW_QUANTITY),
			new ChargeCost(ItemID.CHAOSRUNE, 5, 1),
			new ChargeCost(ItemID.SOULRUNE, 2, 1)));
		ATTACK_PROFILES.add(new AttackProfile("warped sceptre",
			Collections.singleton(SpotanimID.VFX_WARPED_SCEPTRE_CAST), Collections.emptySet(), false,
			Collections.singleton(VarbitID.CHARGES_WARPED_SCEPTRE_QUANTITY),
			new ChargeCost(ItemID.CHAOSRUNE, 2, 1),
			new ChargeCost(ItemID.EARTHRUNE, 5, 1)));
		// Eye of ayak cast graphics. Rune-mode cost assumed; demon-tear mode is not distinguished. TODO verify costs
		ATTACK_PROFILES.add(new AttackProfile("eye of ayak",
			new HashSet<>(Arrays.asList(SpotanimID.VFX_AYAK_PLAYER_SPECIAL_SPOTANIM,
				SpotanimID.VFX_AYAK_PLAYER_NORMAL_SPOTANIM)), Collections.emptySet(), false,
			Collections.singleton(VarbitID.CHARGES_EYE_OF_AYAK_QUANTITY),
			new ChargeCost(ItemID.DEATHRUNE, 2, 1),
			new ChargeCost(ItemID.CHAOSRUNE, 1, 1)));
		ATTACK_PROFILES.add(new AttackProfile("bow of faerdhinen",
			Collections.singleton(SpotanimID.SP_ATTACK_ARROW_LAUNCH_FAERDHINEN), Collections.emptySet(), false,
			Collections.singleton(VarbitID.CHARGES_BOW_OF_FAERDHINEN_QUANTITY),
			new ChargeCost(ItemID.PRIF_CRYSTAL_SHARD, 1, 100)));
		ATTACK_PROFILES.add(new AttackProfile("venator bow",
			Collections.singleton(SpotanimID.ARROW_VENATOR01_LAUNCH01), Collections.emptySet(), false,
			Collections.singleton(VarbitID.CHARGES_VENATOR_BOW_QUANTITY),
			new ChargeCost(ItemID.ANCIENT_ESSENCE, 1, 1)));
		// Wilderness bows share the generic bow animation
		ATTACK_PROFILES.add(new AttackProfile("craw's bow",
			Collections.emptySet(), Collections.singleton(AnimationID.HUMAN_BOW), false, wildernessVarbit,
			new ChargeCost(ItemID.WILD_CAVE_SHARD, 1, 1)));
		ATTACK_PROFILES.add(new AttackProfile("webweaver bow",
			Collections.emptySet(), Collections.singleton(AnimationID.HUMAN_BOW), false, wildernessVarbit,
			new ChargeCost(ItemID.WILD_CAVE_SHARD, 1, 1)));
		// Wilderness powered staves share the cast animation. TODO verify anim
		ATTACK_PROFILES.add(new AttackProfile("thammaron's sceptre",
			Collections.emptySet(), Collections.singleton(AnimationID.HUMAN_CASTWAVE_STAFF), false, wildernessVarbit,
			new ChargeCost(ItemID.WILD_CAVE_SHARD, 1, 1)));
		ATTACK_PROFILES.add(new AttackProfile("accursed sceptre",
			Collections.emptySet(), Collections.singleton(AnimationID.HUMAN_CASTWAVE_STAFF), false, wildernessVarbit,
			new ChargeCost(ItemID.WILD_CAVE_SHARD, 1, 1)));
		// Arclight/emberlight: sword slash/stab anims, 1 charge per attack, 333 charges per shard
		ATTACK_PROFILES.add(new AttackProfile("arclight",
			Collections.emptySet(), swordAnims, false,
			Collections.singleton(VarbitID.CHARGES_ARCLIGHT_QUANTITY),
			new ChargeCost(ItemID.CATA_SHARD, 1, 333)));
		ATTACK_PROFILES.add(new AttackProfile("emberlight",
			Collections.emptySet(), swordAnims, false,
			Collections.singleton(VarbitID.CHARGES_ARCLIGHT_QUANTITY),
			new ChargeCost(ItemID.CATA_SHARD, 1, 333)));
		// Blade of saeldor: sword anims, 1 charge per attack. TODO verify charges-per-shard
		ATTACK_PROFILES.add(new AttackProfile("blade of saeldor",
			Collections.emptySet(), swordAnims, false,
			Collections.singleton(VarbitID.CHARGES_BLADE_OF_SAELDOR_QUANTITY),
			new ChargeCost(ItemID.PRIF_CRYSTAL_SHARD, 1, 100)));
		// Toxic blowpipe: 1 dart + ~2/3 scale per shot (varbit counts scales -> no dedup)
		ATTACK_PROFILES.add(new AttackProfile("toxic blowpipe",
			Collections.emptySet(), Collections.singleton(AnimationID.SNAKEBOSS_BLOWPIPE_ATTACK), true,
			Collections.emptySet(),
			new ChargeCost(ItemID.SNAKEBOSS_SCALE, 2, 3)));

		// -- Varbit-tracked items --
		// Items whose charges drain passively (no per-attack trigger), plus the attack
		// weapons above so consumption is still counted if the varbit updates while
		// the attack trigger misses. Attack-triggered charges are deduplicated against
		// these deltas, so a charge is never counted twice.
		// TODO verify which of these varbits actually update live in-game

		List<ChargeCost> seasVarbitCosts = Arrays.asList(
			new ChargeCost(ItemID.CHAOSRUNE, 1, 1),
			new ChargeCost(ItemID.DEATHRUNE, 1, 1),
			new ChargeCost(ItemID.FIRERUNE, 5, 1),
			new ChargeCost(ItemID.COINS, 10, 1));
		CONFIGS.put(VarbitID.CHARGES_TRIDENT_OF_THE_SEAS_QUANTITY,
			new WeaponConfig("trident", seasVarbitCosts));
		CONFIGS.put(VarbitID.CHARGES_TRIDENT_OF_THE_SEAS_E_QUANTITY,
			new WeaponConfig("trident (e)", seasVarbitCosts));
		CONFIGS.put(VarbitID.CHARGES_SANGUINESTI_STAFF_QUANTITY,
			new WeaponConfig("sanguinesti staff", new ChargeCost(ItemID.BLOODRUNE, 3, 1)));
		CONFIGS.put(VarbitID.CHARGES_TUMEKENS_SHADOW_QUANTITY,
			new WeaponConfig("tumeken's shadow",
				new ChargeCost(ItemID.CHAOSRUNE, 5, 1),
				new ChargeCost(ItemID.SOULRUNE, 2, 1)));
		CONFIGS.put(VarbitID.CHARGES_WARPED_SCEPTRE_QUANTITY,
			new WeaponConfig("warped sceptre",
				new ChargeCost(ItemID.CHAOSRUNE, 2, 1),
				new ChargeCost(ItemID.EARTHRUNE, 5, 1)));
		CONFIGS.put(VarbitID.CHARGES_EYE_OF_AYAK_QUANTITY,
			new WeaponConfig("eye of ayak",
				new ChargeCost(ItemID.DEATHRUNE, 2, 1),
				new ChargeCost(ItemID.CHAOSRUNE, 1, 1)));
		CONFIGS.put(VarbitID.CHARGES_BOW_OF_FAERDHINEN_QUANTITY,
			new WeaponConfig("bow of faerdhinen", new ChargeCost(ItemID.PRIF_CRYSTAL_SHARD, 1, 100)));
		CONFIGS.put(VarbitID.CHARGES_BLADE_OF_SAELDOR_QUANTITY,
			new WeaponConfig("blade of saeldor", new ChargeCost(ItemID.PRIF_CRYSTAL_SHARD, 1, 100)));
		CONFIGS.put(VarbitID.CHARGES_VENATOR_BOW_QUANTITY,
			new WeaponConfig("venator bow", new ChargeCost(ItemID.ANCIENT_ESSENCE, 1, 1)));
		CONFIGS.put(VarbitID.CHARGES_ARCLIGHT_QUANTITY,
			new WeaponConfig("arclight", new ChargeCost(ItemID.CATA_SHARD, 1, 333)));
		CONFIGS.put(VarbitID.CHARGES_WILDERNESS_WEAPON_QUANTITY,
			new WeaponConfig("wilderness weapon", new ChargeCost(ItemID.WILD_CAVE_SHARD, 1, 1)));
		CONFIGS.put(VarbitID.CHARGES_CRYSTAL_ARMOUR_QUANTITY,
			new WeaponConfig("crystal armour", new ChargeCost(ItemID.PRIF_CRYSTAL_SHARD, 1, 100)));
		CONFIGS.put(VarbitID.CHARGES_SERPENTINE_HELM_QUANTITY,
			new WeaponConfig("serpentine helm", new ChargeCost(ItemID.SNAKEBOSS_SCALE, 1, 1)));
		CONFIGS.put(VarbitID.CHARGES_TOXIC_STAFF_OF_THE_DEAD_QUANTITY,
			new WeaponConfig("toxic staff of the dead", new ChargeCost(ItemID.SNAKEBOSS_SCALE, 1, 1)));
		CONFIGS.put(VarbitID.CHARGES_TONALZTICS_OF_RALOS_QUANTITY,
			new WeaponConfig("tonalztics of ralos", new ChargeCost(ItemID.SUNFIRESPLINTER, 1, 1)));
		CONFIGS.put(VarbitID.CHARGES_TOME_OF_FIRE_QUANTITY,
			new WeaponConfig("tome of fire", new ChargeCost(ItemID.WINT_BURNT_PAGE, 1, 20)));
		CONFIGS.put(VarbitID.CHARGES_TOME_OF_WATER_QUANTITY,
			new WeaponConfig("tome of water", new ChargeCost(ItemID.SOAKED_PAGE, 1, 20)));
		CONFIGS.put(VarbitID.CHARGES_TOME_OF_EARTH_QUANTITY,
			new WeaponConfig("tome of earth", new ChargeCost(ItemID.SOILED_PAGE, 1, 20)));
		CONFIGS.put(VarbitID.CHARGES_RING_OF_SUFFERING_QUANTITY,
			new WeaponConfig("ring of suffering", new ChargeCost(ItemID.RING_OF_RECOIL, 1, 40)));
		CONFIGS.put(VarbitID.CHARGES_XERICS_TALISMAN_QUANTITY,
			new WeaponConfig("xeric's talisman", new ChargeCost(ItemID.LIZARDMAN_FANG, 1, 1)));
		CONFIGS.put(VarbitID.CHARGES_BRACELET_OF_ETHEREUM_QUANTITY,
			new WeaponConfig("bracelet of ethereum", new ChargeCost(ItemID.WILD_CAVE_SHARD, 1, 1)));
		CONFIGS.put(VarbitID.CHARGES_BRYOPHYTAS_STAFF_QUANTITY,
			new WeaponConfig("bryophyta's staff", new ChargeCost(ItemID.NATURERUNE, 1, 1)));
		CONFIGS.put(VarbitID.CHARGES_BLOOD_FURY_QUANTITY,
			new WeaponConfig("amulet of blood fury", new ChargeCost(ItemID.BLOOD_SHARD, 1, 10000)));
		CONFIGS.put(VarbitID.CHARGES_BONECRUSHER_QUANTITY,
			new WeaponConfig("bonecrusher", new ChargeCost(ItemID.DRAGONBONE_NECKLACE, 1, 500)));
		CONFIGS.put(VarbitID.CHARGES_CRYSTAL_TOOLS_QUANTITY,
			new WeaponConfig("crystal tool", new ChargeCost(ItemID.PRIF_CRYSTAL_SHARD, 1, 100)));

		// Not covered: scythe of vitur (hitsplat-triggered, unimplemented), soulreaper axe,
		// nightmare staves, abyssal tentacle, dizana's quiver, viggora's/ursine chainmaces
		// (attack animations unknown).

		DART_ITEM_IDS.put("bronze dart", ItemID.BRONZE_DART);
		DART_ITEM_IDS.put("iron dart", ItemID.IRON_DART);
		DART_ITEM_IDS.put("steel dart", ItemID.STEEL_DART);
		DART_ITEM_IDS.put("black dart", ItemID.BLACK_DART);
		DART_ITEM_IDS.put("mithril dart", ItemID.MITHRIL_DART);
		DART_ITEM_IDS.put("adamant dart", ItemID.ADAMANT_DART);
		DART_ITEM_IDS.put("rune dart", ItemID.RUNE_DART);
		DART_ITEM_IDS.put("amethyst dart", ItemID.AMETHYST_DART);
		DART_ITEM_IDS.put("dragon dart", ItemID.DRAGON_DART);
	}

	/** varbit id -> last observed charge count */
	private final Map<Integer, Integer> lastCharges = new HashMap<>();

	/** fractional resource remainder per (source, resource item) */
	private final Map<String, Double> resourceRemainders = new HashMap<>();

	/** resource item id -> whole units consumed since last drain */
	private final Map<Integer, Integer> pendingResources = new HashMap<>();

	/** varbit id -> attack-triggered charges already expensed, for varbit delta dedup */
	private final Map<Integer, AttackDedupe> recentAttackCharges = new HashMap<>();

	private int lastLoadedDartItemId = -1;
	private double dartRecoveryRate;

	/**
	 * Records an attack graphic played on the local player while {@code weaponName} is equipped.
	 */
	void onAttackGraphic(int graphicId, String weaponName, int tick)
	{
		AttackProfile profile = findProfile(weaponName);
		if (profile == null || !profile.graphics.contains(graphicId))
		{
			return;
		}
		consumeAttack(profile, "graphic " + graphicId, tick);
	}

	/**
	 * Records an attack animation played on the local player while {@code weaponName} is equipped.
	 */
	void onAttackAnimation(int animationId, String weaponName, int tick)
	{
		AttackProfile profile = findProfile(weaponName);
		if (profile == null || !profile.animations.contains(animationId))
		{
			return;
		}
		consumeAttack(profile, "animation " + animationId, tick);
	}

	/**
	 * Records a charge varbit update. {@code trackingAllowed} should be false while
	 * inventory tracking is suppressed or a rebaseline is pending (e.g. uncharge at a
	 * bank); in that case the new value is baselined without recording expense.
	 * Charges already expensed via attack triggers within the last few ticks are
	 * deducted from the delta to avoid double counting.
	 */
	void onVarbitChanged(int varbitId, int newValue, boolean trackingAllowed, int tick)
	{
		WeaponConfig config = CONFIGS.get(varbitId);
		if (config == null)
		{
			return;
		}

		Integer previous = lastCharges.put(varbitId, newValue);
		if (previous == null || previous == newValue)
		{
			return;
		}

		log.debug("Weapon charge varbit {} ({}): {} -> {} (trackingAllowed={})",
			varbitId, config.name, previous, newValue, trackingAllowed);

		if (!trackingAllowed)
		{
			return;
		}

		int consumed = previous - newValue;
		if (consumed <= 0)
		{
			return;
		}

		AttackDedupe dedupe = recentAttackCharges.get(varbitId);
		if (dedupe != null)
		{
			if (tick - dedupe.lastTick <= 2)
			{
				int alreadyCounted = Math.min(consumed, dedupe.count);
				dedupe.count -= alreadyCounted;
				consumed -= alreadyCounted;
			}
			else
			{
				recentAttackCharges.remove(varbitId);
			}
			if (dedupe.count <= 0)
			{
				recentAttackCharges.remove(varbitId);
			}
		}
		if (consumed <= 0)
		{
			return;
		}

		for (ChargeCost cost : config.costs)
		{
			accumulate("varbit:" + varbitId, cost.itemId, consumed * (double) cost.numerator / cost.denominator);
		}
	}

	/** Returns and clears the whole resource units consumed since the last drain. */
	Map<Integer, Integer> drain()
	{
		if (pendingResources.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<Integer, Integer> result = new HashMap<>(pendingResources);
		pendingResources.clear();
		return result;
	}

	/**
	 * Records the dart type loaded into a blowpipe, parsed from the source name of a
	 * "Use <dart> -> Toxic blowpipe" menu click.
	 */
	void recordUseSource(String itemName)
	{
		if (itemName == null)
		{
			return;
		}
		String lower = itemName.trim().toLowerCase(Locale.ROOT);
		if (lower.startsWith("use "))
		{
			lower = lower.substring(4).trim();
		}
		Integer dartId = DART_ITEM_IDS.get(lower);
		if (dartId != null)
		{
			lastLoadedDartItemId = dartId;
		}
	}

	/** Fraction of consumed darts recovered by the equipped Ava's device (0 when none). */
	void setDartRecoveryRate(double dartRecoveryRate)
	{
		this.dartRecoveryRate = Math.max(0.0, Math.min(1.0, dartRecoveryRate));
	}

	/**
	 * Clears the remembered blowpipe dart type (called when the weapon is unloaded).
	 * Darts persist inside the blowpipe across banking, rebaselines and logout, so
	 * {@link #reset()} intentionally does NOT clear this state.
	 */
	void clearLoadedDarts()
	{
		lastLoadedDartItemId = -1;
	}

	/** Clears all baselines and pending spend (session reset / logout / full rebaseline). */
	void reset()
	{
		lastCharges.clear();
		resourceRemainders.clear();
		pendingResources.clear();
		recentAttackCharges.clear();
		// lastLoadedDartItemId survives: the blowpipe's darts are still loaded
	}

	static boolean isTrackedVarbit(int varbitId)
	{
		return CONFIGS.containsKey(varbitId);
	}

	static Set<Integer> trackedVarbitIds()
	{
		return Collections.unmodifiableSet(CONFIGS.keySet());
	}

	private AttackProfile findProfile(String weaponName)
	{
		if (weaponName == null)
		{
			return null;
		}
		String lower = weaponName.toLowerCase(Locale.ROOT);
		for (AttackProfile profile : ATTACK_PROFILES)
		{
			if (lower.contains(profile.weaponNameMatch))
			{
				return profile;
			}
		}
		return null;
	}

	private void consumeAttack(AttackProfile profile, String trigger, int tick)
	{
		log.debug("Charged weapon attack via {} ({}): {}", trigger, profile.weaponNameMatch,
			profile.consumesDarts ? "scales + darts" : profile.costsPerAttack.size() + " resource(s)");
		for (ChargeCost cost : profile.costsPerAttack)
		{
			accumulate("attack:" + profile.weaponNameMatch, cost.itemId,
				(double) cost.numerator / cost.denominator);
		}
		if (profile.consumesDarts && lastLoadedDartItemId > 0)
		{
			accumulate("attack:" + profile.weaponNameMatch, lastLoadedDartItemId, 1.0 - dartRecoveryRate);
		}
		for (int varbitId : profile.dedupeVarbitIds)
		{
			AttackDedupe dedupe = recentAttackCharges.computeIfAbsent(varbitId, k -> new AttackDedupe());
			dedupe.count++;
			dedupe.lastTick = tick;
		}
	}

	private void accumulate(String source, int itemId, double units)
	{
		String key = source + ":" + itemId;
		double total = resourceRemainders.getOrDefault(key, 0.0) + units;
		// Epsilon absorbs floating point error at whole-unit boundaries (e.g. 1.0 - 0.8)
		int whole = (int) (total + 1e-9);
		resourceRemainders.put(key, total - whole);
		if (whole > 0)
		{
			pendingResources.merge(itemId, whole, Integer::sum);
		}
	}
}
