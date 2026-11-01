package com.coinflow;

import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.util.Text;

/**
 * Interprets menu clicks into cross-tick intents: drop intents, take clicks,
 * looting-bag deposits, container/charged-item interactions that need a
 * re-baseline, and skill-action ticks that bias diff attribution.
 */
@Slf4j
final class MenuIntentHandler
{
	/**
	 * Re-baseline window for a cargo-hold quick deposit — the hold object can
	 * be several tiles away and the server removes the items a few ticks
	 * after the click.
	 */
	private static final int CARGO_HOLD_DEPOSIT_GRACE_TICKS = 12;

	private final CoinFlowPlugin plugin;

	MenuIntentHandler(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	void onMenuOptionClicked(MenuOptionClicked event)
	{
		plugin.recordPlayerActivity();
		String option = event.getMenuOption();
		if (option == null)
		{
			return;
		}
		String rawTarget = event.getMenuTarget();
		String target = rawTarget != null ? Text.removeTags(rawTarget).toLowerCase(Locale.ROOT) : "";
		int tick = plugin.client != null ? plugin.client.getTickCount() : 0;

		if ("Drop".equalsIgnoreCase(option) && event.getItemId() > 0)
		{
			int dropId = plugin.itemManager != null ? plugin.itemManager.canonicalize(event.getItemId()) : event.getItemId();
			plugin.recentDropIntents.put(dropId, tick);
		}

		if ("Take".equalsIgnoreCase(option))
		{
			int itemId = event.getId();
			int sceneX = event.getParam0();
			int sceneY = event.getParam1();
			plugin.deathTracker.recordTakeClick(
				plugin.itemManager != null ? plugin.itemManager.canonicalize(itemId) : itemId, tick);
			plugin.containerPickupTracker.recordTakeClick(itemId, sceneX, sceneY, tick);
		}
		else if ("Use".equalsIgnoreCase(option))
		{
			String lowerTarget = target.toLowerCase(Locale.ROOT);
			if (lowerTarget.contains("looting bag"))
			{
				int usedId = -1;
				if (plugin.client != null && plugin.client.getSelectedWidget() != null)
				{
					usedId = plugin.client.getSelectedWidget().getItemId();
				}
				String usedName = "";
				if (target.contains("->"))
				{
					String beforeArrow = target.split("->")[0].trim();
					if (beforeArrow.startsWith("use "))
					{
						beforeArrow = beforeArrow.substring(4).trim();
					}
					usedName = beforeArrow;
				}
				plugin.lastLootingBagDepositTick = tick;
				plugin.lastLootingBagDepositItemId = usedId;
				plugin.lastLootingBagDepositItemName = usedName;
				log.debug("Looting bag deposit intent via Use: name '{}', id {}", usedName, usedId);
			}
			else if (ActionClassifiers.isFarmingPatchAction(lowerTarget))
			{
				plugin.lastFarmingActionTick = tick;
				plugin.lastSkillXpTicks.put(Skill.FARMING, tick);
				log.debug("Farming patch action clicked ('{}' on '{}'), recording farming action tick", option, target);
			}
			else if (ActionClassifiers.isBirdHouseAction(lowerTarget))
			{
				plugin.lastSkillXpTicks.put(Skill.HUNTER, tick);
				log.debug("Birdhouse action clicked ('{}' on '{}'), recording hunter action tick", option, target);
			}
			else if (target.contains("tinderbox") || target.contains("bruma torch"))
			{
				plugin.lastTinderboxActionTick = tick;
			}
			else if (lowerTarget.contains("leprechaun") || lowerTarget.contains("phials")
				|| lowerTarget.contains("piles"))
			{
				plugin.lastNotingServiceTick = tick;
			}
			else if (lowerTarget.contains("->"))
			{
				String[] parts = lowerTarget.split("->");
				String dest = parts.length > 1 ? parts[1].trim() : "";
				if (dest.contains("cargo hold"))
				{
					// Using an item on a boat's cargo hold is a single-item
					// quick deposit — same stale-hold transfer as Deposit-all.
					plugin.interfaceTracker.setNeedsRebaseline(true);
					plugin.rebaselineGraceTicks = Math.max(plugin.rebaselineGraceTicks, CARGO_HOLD_DEPOSIT_GRACE_TICKS);
					log.debug("Cargo hold item deposit clicked ('{}'), scheduling rebaseline", target);
				}
				else if (ActionClassifiers.isContainerOrChargedItemTarget(dest)
					|| (ActionClassifiers.isContainerOrChargedItemTarget(parts[0].trim()) && (dest.contains("bank") || dest.contains("deposit"))))
				{
					plugin.weaponChargeTracker.recordUseSource(parts[0]);
					plugin.interfaceTracker.setNeedsRebaseline(true);
					plugin.rebaselineGraceTicks = 3;
					log.debug("Container/charged item Use action clicked ('{}'), scheduling rebaseline", target);
				}
			}
		}
		else if ("Fill".equalsIgnoreCase(option) || "Empty".equalsIgnoreCase(option)
			|| "Empty basket".equalsIgnoreCase(option)
			|| "Open".equalsIgnoreCase(option) || "Close".equalsIgnoreCase(option)
			|| "Charge".equalsIgnoreCase(option) || "Uncharge".equalsIgnoreCase(option)
			|| "Unload".equalsIgnoreCase(option)
			|| "Loot".equalsIgnoreCase(option) || "Search".equalsIgnoreCase(option))
		{
			String lowerTarget = target.toLowerCase(Locale.ROOT);
			if (ActionClassifiers.isContainerOrChargedItemTarget(lowerTarget))
			{
				if ("Unload".equalsIgnoreCase(option) && lowerTarget.contains("blowpipe"))
				{
					plugin.weaponChargeTracker.clearLoadedDarts();
				}
				plugin.interfaceTracker.setNeedsRebaseline(true);
				plugin.rebaselineGraceTicks = 3;
				log.debug("Container/charged item action clicked ('{}' on '{}'), scheduling rebaseline", option, target);
			}
			// "Open"/"Loot"/"Search" on an inventory pack item (casket, impling
			// jar, supply crate) consumes the item for its contents. CC_OP +
			// itemId restricts this to inventory clicks — object opens (STASH
			// units, chests) are GAME_OBJECT actions and must not mint intents.
			else if (("Open".equalsIgnoreCase(option) || "Loot".equalsIgnoreCase(option)
				|| "Search".equalsIgnoreCase(option))
				&& event.getMenuAction() == net.runelite.api.MenuAction.CC_OP
				&& event.getItemId() > 0)
			{
				int openId = plugin.itemManager != null ? plugin.itemManager.canonicalize(event.getItemId()) : event.getItemId();
				plugin.recentOpenIntents.put(openId, tick);
				log.debug("Open/loot intent on inventory item '{}' (id {}), recording", target, openId);
			}
		}
		// Activating a blood essence irreversibly converts the tradeable stack
		// item into the untradeable "(active)" variant — a consume, like opening
		// a casket for its contents. An open intent expenses the lost essence at
		// market price; the active variant is valueless and strips from gains.
		else if ("Activate".equalsIgnoreCase(option)
			&& event.getMenuAction() == net.runelite.api.MenuAction.CC_OP
			&& event.getItemId() == ItemID.BLOOD_ESSENCE_INACTIVE)
		{
			plugin.recentOpenIntents.put(ItemID.BLOOD_ESSENCE_INACTIVE, tick);
			log.debug("Blood essence activate clicked (id {}), recording open intent",
				ItemID.BLOOD_ESSENCE_INACTIVE);
		}
		else if (option.toLowerCase(Locale.ROOT).startsWith("collect")
			&& (plugin.interfaceTracker.isTrackingSuppressed() || target.toLowerCase(Locale.ROOT).contains("grand exchange")))
		{
			plugin.interfaceTracker.setNeedsRebaseline(true);
			plugin.rebaselineGraceTicks = 3;
			log.debug("Collect action clicked ('{}' on '{}'), scheduling rebaseline", option, target);
		}
		else if (option.toLowerCase(Locale.ROOT).startsWith("store") || option.toLowerCase(Locale.ROOT).startsWith("deposit"))
		{
			// A right-click "Deposit-all" quick deposit on a boat's cargo hold
			// removes items with no matching cargo-hold ItemContainerChanged —
			// the client-side hold stays stale until the interface is opened —
			// so the inventory loss would book as a supply expense.
			if (target.contains("cargo"))
			{
				plugin.interfaceTracker.setNeedsRebaseline(true);
				plugin.rebaselineGraceTicks = Math.max(plugin.rebaselineGraceTicks, CARGO_HOLD_DEPOSIT_GRACE_TICKS);
				log.debug("Cargo hold quick deposit clicked ('{}' on '{}'), scheduling rebaseline", option, target);
			}
			else if (plugin.client != null && plugin.client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1)
			{
				ItemContainer inv = plugin.client.getItemContainer(InventoryID.INV);
				if (inv != null && plugin.hasLootingBag(inv))
				{
					plugin.lastLootingBagDepositTick = tick;
					plugin.lastLootingBagDepositItemId = event.getItemId();
					plugin.lastLootingBagDepositItemName = target;
					log.debug("Looting bag deposit intent via Store/Deposit: {} on {} (id {})", option, target, event.getItemId());
				}
			}
		}
		else if ("note".equalsIgnoreCase(option) || "un-note".equalsIgnoreCase(option))
		{
			plugin.lastNotingServiceTick = tick;
		}
		else if (plugin.lastLootingBagDepositTick > 0 && tick - plugin.lastLootingBagDepositTick <= 15
			&& ("one".equalsIgnoreCase(option) || "five".equalsIgnoreCase(option) || "all".equalsIgnoreCase(option) || "x".equalsIgnoreCase(option)))
		{
			plugin.lastLootingBagDepositTick = tick;
			log.debug("Looting bag deposit amount selected: {}", option);
		}
		else if ("Light".equalsIgnoreCase(option) || "Burn".equalsIgnoreCase(option))
		{
			plugin.lastTinderboxActionTick = tick;
		}
		else if ("Bury".equalsIgnoreCase(option) || "Scatter".equalsIgnoreCase(option))
		{
			plugin.lastSkillXpTicks.put(Skill.PRAYER, tick);
		}
		else if ("Plant".equalsIgnoreCase(option) || "Treat".equalsIgnoreCase(option) || "Cure".equalsIgnoreCase(option)
			|| "Harvest".equalsIgnoreCase(option) || "Pick".equalsIgnoreCase(option) || "Rake".equalsIgnoreCase(option)
			|| "Clear".equalsIgnoreCase(option) || "Prune".equalsIgnoreCase(option) || "Check-health".equalsIgnoreCase(option)
			|| ActionClassifiers.isFarmingPatchAction(target))
		{
			plugin.lastFarmingActionTick = tick;
			plugin.lastSkillXpTicks.put(Skill.FARMING, tick);
		}
		else if ("Build".equalsIgnoreCase(option))
		{
			plugin.lastSkillXpTicks.put(Skill.CONSTRUCTION, tick);
		}
		else if ("Reanimate".equalsIgnoreCase(option) || ("Cast".equalsIgnoreCase(option) && target.contains("ensouled")))
		{
			plugin.lastSkillXpTicks.put(Skill.PRAYER, tick);
			plugin.lastSkillXpTicks.put(Skill.MAGIC, tick);
		}
		else if ("Cast".equalsIgnoreCase(option) && target.contains("alchemy"))
		{
			plugin.lastAlchActionTick = tick;
		}
		else if (option.toLowerCase(Locale.ROOT).contains("alchemy"))
		{
			plugin.lastAlchActionTick = tick;
		}
		else if (option.toLowerCase(Locale.ROOT).startsWith("craft"))
		{
			plugin.lastSkillXpTicks.put(Skill.RUNECRAFT, tick);
			plugin.lastSkillXpTicks.put(Skill.CRAFTING, tick);
		}
		else if ("Set-trap".equalsIgnoreCase(option) || "Lay".equalsIgnoreCase(option))
		{
			plugin.lastSkillXpTicks.put(Skill.HUNTER, tick);
		}
	}
}
