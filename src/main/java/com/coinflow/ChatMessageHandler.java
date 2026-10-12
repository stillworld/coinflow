package com.coinflow;

import java.util.regex.Matcher;
import net.runelite.api.ChatMessageType;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.util.Text;

/**
 * Routes game chat messages into plugin signals: death reclaim fees and
 * gravestone retrievals, firemaking/farming chat ticks, container pickups
 * that bypass inventory diffs, re-baselining container notifications, and
 * skilling-gem correlation for gem-bag drops.
 */
final class ChatMessageHandler
{
	private final CoinFlowPlugin plugin;

	ChatMessageHandler(CoinFlowPlugin plugin)
	{
		this.plugin = plugin;
	}

	void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.SPAM || event.getType() == ChatMessageType.GAMEMESSAGE)
		{
			String msg = event.getMessage();
			String cleanMsg = Text.removeTags(msg);
			Matcher deathFee = ChatMessageParser.DEATH_FEE_MESSAGE.matcher(cleanMsg);
			if (deathFee.find())
			{
				plugin.deathTracker.noteChatFee(Long.parseLong(deathFee.group(1).replace(",", "")),
					plugin.client != null ? plugin.client.getTickCount() : 0);
			}
			else if (ChatMessageParser.GRAVE_RETRIEVAL_MESSAGE.matcher(cleanMsg).find())
			{
				plugin.deathTracker.noteGraveRetrieval(plugin.client != null ? plugin.client.getTickCount() : 0);
			}
			else if (msg.contains("fire catches") || msg.contains("light the logs") || msg.contains("burn the logs")
				|| msg.contains("burn some") || msg.contains("add a log to the fire"))
			{
				plugin.lastFiremakingChatTick = plugin.client != null ? plugin.client.getTickCount() : 0;
			}
			else if (msg.startsWith("You plant ") || msg.startsWith("You treat the ")
				|| (msg.startsWith("You put the ") && msg.contains("plant pot"))
				|| msg.startsWith("You cure the "))
			{
				int tick = plugin.client != null ? plugin.client.getTickCount() : 0;
				plugin.lastFarmingChatTick = tick;
				plugin.lastSkillXpTicks.put(Skill.FARMING, tick);
			}
			else if (msg.contains("into your open gem bag") || msg.contains("into your open gem sack")
				|| msg.contains("into your open gem pouch") || msg.contains("into your open gem satchel")
				|| msg.contains("into your open gem tote"))
			{
				int currentTick = plugin.client != null ? plugin.client.getTickCount() : 0;
				int gemId = CoinFlowPlugin.findUncutGemIdInMessage(msg);
				if (gemId <= 0 && plugin.lastSkillingGemId > 0 && currentTick - plugin.lastSkillingGemTick <= 3)
				{
					gemId = plugin.lastSkillingGemId;
				}
				if (gemId > 0)
				{
					plugin.containerPickupTracker.pendingGemBagPickups.add(new CoinFlowPlugin.LootPickup(gemId, 1));
					plugin.lastSkillingGemId = -1;
				}
			}
			else if (msg.contains("into your open herb sack") || msg.contains("into your herb sack")
				|| ChatMessageParser.HERBIBOAR_HERB_SACK_PATTERN.matcher(msg).matches())
			{
				int herbId = CoinFlowPlugin.findGrimyHerbIdInMessage(msg);
				if (herbId > 0)
				{
					plugin.containerPickupTracker.pendingHerbSackPickups.add(new CoinFlowPlugin.LootPickup(herbId, 1));
				}
			}
			else if (msg.contains("into your open seed box"))
			{
				int seedId = CoinFlowPlugin.findSeedIdInMessage(msg);
				int qty = CoinFlowPlugin.parseSeedQtyInMessage(msg);
				if (seedId > 0 && qty > 0)
				{
					plugin.containerPickupTracker.pendingSeedBoxPickups.add(new CoinFlowPlugin.LootPickup(seedId, qty));
				}
			}
			else if (msg.contains("You steal") && msg.contains("seed"))
			{
				ItemContainer inv = plugin.client != null ? plugin.client.getItemContainer(InventoryID.INV) : null;
				if (inv != null && plugin.hasOpenSeedBox(inv))
				{
					int seedId = CoinFlowPlugin.findSeedIdInMessage(msg);
					int qty = CoinFlowPlugin.parseSeedQtyInMessage(msg);
					if (seedId > 0 && qty > 0)
					{
						plugin.containerPickupTracker.pendingSeedBoxPickups.add(new CoinFlowPlugin.LootPickup(seedId, qty));
					}
				}
			}
			else if (ChatMessageParser.FISHING_CATCH_REGEX.matcher(msg).find())
			{
				ItemContainer inv = plugin.client != null ? plugin.client.getItemContainer(InventoryID.INV) : null;
				ItemContainer worn = plugin.client != null ? plugin.client.getItemContainer(InventoryID.WORN) : null;
				if (plugin.hasOpenFishBarrel(inv, worn))
				{
					int fishId = CoinFlowPlugin.findRawFishIdInMessage(msg);
					int qty = CoinFlowPlugin.parseFishQtyInMessage(msg);
					if (fishId > 0 && qty > 0)
					{
						plugin.containerPickupTracker.pendingFishBarrelPickups.add(new CoinFlowPlugin.LootPickup(fishId, qty));
					}
				}
			}
			else if (ChatMessageParser.WOODCUTTING_CHOP_REGEX.matcher(msg).find())
			{
				ItemContainer inv = plugin.client != null ? plugin.client.getItemContainer(InventoryID.INV) : null;
				ItemContainer worn = plugin.client != null ? plugin.client.getItemContainer(InventoryID.WORN) : null;
				if (plugin.hasOpenLogBasket(inv, worn))
				{
					int logId = CoinFlowPlugin.findLogIdInMessage(msg);
					int qty = CoinFlowPlugin.parseLogQtyInMessage(msg);
					if (logId > 0 && qty > 0)
					{
						plugin.containerPickupTracker.pendingLogBasketPickups.add(new CoinFlowPlugin.LootPickup(logId, qty));
					}
				}
			}
			else if (msg.contains("You add the gem") || msg.contains("to your gem bag")
				|| (msg.contains("gem") && (msg.contains("to your bag") || msg.contains("into your bag")))
				|| msg.contains("to your gem sack") || msg.contains("to your gem pouch")
				|| msg.contains("to your gem satchel") || msg.contains("to your gem tote")
				|| msg.contains("into your herb sack") || msg.contains("to your herb sack")
				|| msg.contains("into your seed box") || msg.contains("to your seed box")
				|| msg.contains("The fish barrel is now full") || msg.contains("The seed box is full")
				|| msg.contains("The herb sack is full")
				|| msg.contains("The basket is full")
				|| msg.contains("into the Forestry basket") || msg.contains("into the log basket")
				|| msg.contains("into your Forestry basket") || msg.contains("into your log basket")
				|| msg.contains("to your Forestry basket") || msg.contains("to your log basket")
				|| msg.contains("You empty your basket") || msg.contains("You empty as many logs as you can carry"))
			{
				plugin.interfaceTracker.setNeedsRebaseline(true);
				plugin.rebaselineGraceTicks = Math.max(plugin.rebaselineGraceTicks, 2);
			}
			else
			{
				int gemId = CoinFlowPlugin.findUncutGemIdInMessage(msg);
				if (gemId > 0 && (msg.contains("find") || msg.contains("found") || msg.contains("mine")
					|| msg.contains("steal") || msg.contains("stole") || msg.contains("manage to")))
				{
					plugin.lastSkillingGemId = gemId;
					plugin.lastSkillingGemTick = plugin.client != null ? plugin.client.getTickCount() : 0;
				}
			}
		}
	}
}
