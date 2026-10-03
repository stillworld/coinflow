package com.coinflow;

import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.game.ItemManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CoinFlowGoldDropOverlayTest
{
	private Client client;
	private ItemManager itemManager;
	private CoinFlowConfig config;
	private CoinFlowPlugin plugin;

	private CoinFlowGoldDropOverlay overlay;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		itemManager = mock(ItemManager.class);
		config = mock(CoinFlowConfig.class);
		plugin = mock(CoinFlowPlugin.class);

		when(config.showGoldDrops()).thenReturn(true);
		when(config.goldDropPosition()).thenReturn(CoinFlowConfig.GoldDropPosition.TOP_RIGHT);
		overlay = new CoinFlowGoldDropOverlay(plugin, client, itemManager, config);
	}

	@Test
	public void addDrop_addsDropToQueue()
	{
		overlay.addDrop("+500 gp", 1513, 0);
		Assert.assertEquals(1, overlay.drops.size());

		CoinFlowGoldDropOverlay.GoldDrop drop = overlay.drops.peek();
		Assert.assertNotNull(drop);
		Assert.assertEquals("+500 gp", drop.text);
		Assert.assertEquals(1513, drop.itemId);
		Assert.assertEquals(0, drop.verticalOffset);
	}

	@Test
	public void addDrop_capsAtMaxDrops()
	{
		for (int i = 0; i < 15; i++)
		{
			overlay.addDrop("+" + i + " gp", 1513, 0);
		}
		Assert.assertEquals(10, overlay.drops.size());
		Assert.assertEquals("+5 gp", overlay.drops.peekFirst().text);
		Assert.assertEquals("+14 gp", overlay.drops.peekLast().text);
	}

	@Test
	public void clear_removesAllDrops()
	{
		overlay.addDrop("+1,000 gp", 1513, 0);
		overlay.clear();
		Assert.assertTrue(overlay.drops.isEmpty());
	}

	@Test
	public void getXpDropLocation_fallbackWhenNoWidgets()
	{
		when(client.getCanvasWidth()).thenReturn(800);
		Point loc = overlay.getXpDropLocation();
		Assert.assertNotNull(loc);
		Assert.assertEquals(640, loc.getX()); // 800 - 160
		Assert.assertEquals(160, loc.getY());
	}

	@Test
	public void render_loadsImageForDropItemId()
	{
		net.runelite.api.Player player = mock(net.runelite.api.Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		when(client.getCanvasWidth()).thenReturn(800);

		java.awt.Graphics2D graphics = mock(java.awt.Graphics2D.class);
		java.awt.FontMetrics fontMetrics = mock(java.awt.FontMetrics.class);
		when(graphics.getFontMetrics()).thenReturn(fontMetrics);
		when(fontMetrics.stringWidth(org.mockito.ArgumentMatchers.anyString())).thenReturn(50);

		net.runelite.client.util.AsyncBufferedImage img = mock(net.runelite.client.util.AsyncBufferedImage.class);
		when(itemManager.getImage(1513)).thenReturn(img);

		overlay.addDrop("+500 gp", 1513, 0);
		overlay.render(graphics);

		org.mockito.Mockito.verify(itemManager).getImage(1513);
	}
}
