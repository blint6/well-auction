package net.gasull.well.auction.command;

import net.gasull.well.auction.WellAuction;
import net.gasull.well.auction.db.model.AuctionShop;
import net.gasull.well.auction.db.model.ShopEntityModel;
import net.gasull.well.auction.shop.entity.AucShopEntityManager;
import net.gasull.well.auction.shop.entity.BlockShopEntity;
import net.gasull.well.auction.shop.entity.ShopEntity;
import net.gasull.well.command.WellCommandException;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BlockIterator;

/**
 * The Class WaucCommandHelper.
 */
public class WaucCommandHelper {

	/** The plugin. */
	private WellAuction plugin;

	/** The shop entity manager. */
	private AucShopEntityManager shopEntityManager;

	/** "not looking at a block" error message. */
	private final String ERR_NO_BLOCK_SEEN;

	/** "can't sell air" error message. */
	private final String ERR_CANT_SELL_AIR;

	/**
	 * Instantiates a new wauc command helper.
	 * 
	 * @param plugin
	 *            the plugin
	 * @param shopEntityManager
	 *            the shop entity manager
	 */
	public WaucCommandHelper(WellAuction plugin, AucShopEntityManager shopEntityManager) {
		this.plugin = plugin;
		this.shopEntityManager = shopEntityManager;
		this.ERR_NO_BLOCK_SEEN = ChatColor.DARK_RED + plugin.wellConfig().getString("lang.command.error.notBlockSeen", "You must be looking at a block");
		this.ERR_CANT_SELL_AIR = ChatColor.DARK_RED + plugin.wellConfig().getString("lang.command.error.cantSellAir", "You can't put air on sale!");
	}

	/**
	 * Gets the target shop.
	 * 
	 * @param args
	 *            the args
	 * @param player
	 *            the player
	 * @return the target shop
	 * @throws WellCommandException
	 *             the well command exception
	 */
	public ShopEntity getTargetShop(String[] args, Player player) throws WellCommandException {
		ShopEntity shopEntity = null;

		// Take the block seen by default as a shop
		if (shopEntity == null) {
			Block solidBlock = null;
			Block block = null;
			BlockIterator blockIterator = new BlockIterator(player, 3);

			while (blockIterator.hasNext() && solidBlock == null) {
				block = blockIterator.next();
				if (block.getType() != Material.AIR) {
					solidBlock = block;
				}
			}
			if (solidBlock == null) {
				throw new WellCommandException(ERR_NO_BLOCK_SEEN);
			}

			shopEntity = new BlockShopEntity(plugin, solidBlock);
		}

		ShopEntityModel similarEntity = plugin.db().findSimilarShopEntity(shopEntity);
		if (similarEntity != null) {
			shopEntity = shopEntityManager.get(similarEntity);
		}

		return shopEntity;
	}

	/**
	 * Gets the shop from hand.
	 * 
	 * @param player
	 *            the player
	 * @return the shop from hand
	 * @throws WellCommandException
	 *             the well command exception
	 */
	public AuctionShop getShopFromHand(Player player) throws WellCommandException {
		ItemStack refItem = player.getItemInHand();

		if (refItem == null || refItem.getType() == Material.AIR) {
			throw new WellCommandException(ERR_CANT_SELL_AIR);
		}

		return plugin.db().getShop(refItem);
	}

}
