package com.coinflow;

import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
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
				if (ActionClassifiers.isContainerOrChargedItemTarget(dest)
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
		else if (option.toLowerCase(Locale.ROOT).startsWith("collect")
			&& (plugin.interfaceTracker.isTrackingSuppressed() || target.toLowerCase(Locale.ROOT).contains("grand exchange")))
		{
			plugin.interfaceTracker.setNeedsRebaseline(true);
			plugin.rebaselineGraceTicks = 3;
			log.debug("Collect action clicked ('{}' on '{}'), scheduling rebaseline", option, target);
		}
		else if (option.toLowerCase(Locale.ROOT).startsWith("store") || option.toLowerCase(Locale.ROOT).startsWith("deposit"))
		{
			if (plugin.client != null && plugin.client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1)
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
