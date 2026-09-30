package com.coinflow;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

@Singleton
public class CoinFlowGoldDropOverlay extends Overlay
{
	private static final int MAX_DROPS = 10;
	static final long DURATION_MS = 1800;
	private static final Color GOLD_DROP_COLOR = new Color(0xFF, 0xD7, 0x00);

	private static final int[] XP_ORB_WIDGET_IDS = {
		InterfaceID.Orbs.XP_DROPS,
		InterfaceID.OrbsNomap.XP_DROPS,
		InterfaceID.OrbsOsm.XP_DROPS,
		InterfaceID.OrbsOsmNomap.XP_DROPS,
		InterfaceID.XpDrops.CONTAINER
	};

	private final Client client;
	private final ItemManager itemManager;
	private final CoinFlowConfig config;
	final ConcurrentLinkedDeque<GoldDrop> drops = new ConcurrentLinkedDeque<>();

	@Inject
	public CoinFlowGoldDropOverlay(CoinFlowPlugin plugin, Client client, ItemManager itemManager, CoinFlowConfig config)
	{
		super(plugin);
		this.client = client;
		this.itemManager = itemManager;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_MED);
	}

	public void addDrop(String text, int itemId, int verticalOffset)
	{
		while (drops.size() >= MAX_DROPS)
		{
			drops.pollFirst();
		}
		drops.addLast(new GoldDrop(text, itemId, System.currentTimeMillis(), verticalOffset));
	}

	public void clear()
	{
		drops.clear();
	}

	Point getXpDropLocation()
	{
		for (int id : XP_ORB_WIDGET_IDS)
		{
			Widget widget = client.getWidget(id);
			if (widget != null && !widget.isHidden())
			{
				Point loc = widget.getCanvasLocation();
				if (loc != null && loc.getX() > 0 && loc.getY() > 0)
				{
					// Anchor to the right edge just to the left of the XP orb, starting below and floating upward
					return new Point(loc.getX() - 15, loc.getY() + 70);
				}
			}
		}

		return new Point(Math.max(100, client.getCanvasWidth() - 160), 160);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showGoldDrops() || drops.isEmpty() || client == null)
		{
			return null;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			drops.clear();
			return null;
		}

		long now = System.currentTimeMillis();
		boolean isTopRight = config.goldDropPosition() == CoinFlowConfig.GoldDropPosition.TOP_RIGHT;
		Point topRightOrigin = isTopRight ? getXpDropLocation() : null;

		LocalPoint loc = isTopRight ? null : localPlayer.getLocalLocation();
		if (!isTopRight && loc == null)
		{
			return null;
		}
		int zOffset = isTopRight ? 0 : localPlayer.getLogicalHeight() + 30;

		Font prevFont = graphics.getFont();
		if (isTopRight)
		{
			graphics.setFont(FontManager.getRunescapeBoldFont());
		}

		try
		{
			Iterator<GoldDrop> it = drops.iterator();

			while (it.hasNext())
			{
				GoldDrop drop = it.next();
				long elapsed = now - drop.spawnTime;
				if (elapsed >= DURATION_MS)
				{
					it.remove();
					continue;
				}

				float progress = (float) elapsed / DURATION_MS;
				float alpha = Math.max(0.0f, Math.min(1.0f, 1.0f - progress));

				Point drawLoc;
				if (isTopRight)
				{
					int floatY = (int) (progress * 55);
					// Floats UP towards the XP counter, matching real OSRS XP drops
					drawLoc = new Point(topRightOrigin.getX(), topRightOrigin.getY() - floatY + drop.verticalOffset);
				}
				else
				{
					int floatY = (int) (progress * 45);
					Point baseLoc = Perspective.getCanvasTextLocation(client, graphics, loc, drop.text, zOffset);
					if (baseLoc == null)
					{
						continue;
					}
					drawLoc = new Point(baseLoc.getX(), baseLoc.getY() - floatY - drop.verticalOffset);
				}

				Composite origComposite = graphics.getComposite();
				graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

				try
				{
					BufferedImage icon = itemManager != null ? itemManager.getImage(ItemID.COINS_10000) : null;
					if (icon == null && itemManager != null)
					{
						icon = itemManager.getImage(ItemID.COINS);
					}

					int textX = drawLoc.getX();
					if (isTopRight)
					{
						int textWidth = graphics.getFontMetrics().stringWidth(drop.text);
						textX = topRightOrigin.getX() - textWidth;
					}

					if (icon != null)
					{
						graphics.drawImage(icon, textX - 18, drawLoc.getY() - 13, 16, 16, null);
					}

					OverlayUtil.renderTextLocation(graphics, new Point(textX, drawLoc.getY()), drop.text, GOLD_DROP_COLOR);
				}
				finally
				{
					graphics.setComposite(origComposite);
				}
			}
		}
		finally
		{
			if (isTopRight)
			{
				graphics.setFont(prevFont);
			}
		}

		return null;
	}

	static class GoldDrop
	{
		final String text;
		final int itemId;
		final long spawnTime;
		final int verticalOffset;

		GoldDrop(String text, int itemId, long spawnTime, int verticalOffset)
		{
			this.text = text;
			this.itemId = itemId;
			this.spawnTime = spawnTime;
			this.verticalOffset = verticalOffset;
		}
	}
}
