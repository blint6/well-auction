package net.gasull.well.auction.shop;

import java.util.List;

import net.gasull.well.WellCore;
import net.gasull.well.auction.WellAuction;
import net.gasull.well.auction.db.model.AuctionSale;
import net.gasull.well.auction.db.model.AuctionSellerData;
import net.gasull.well.auction.db.model.AuctionShop;
import net.gasull.well.auction.db.model.ShopEntityModel;
import net.gasull.well.auction.shop.entity.AucShopEntityManager;
import net.gasull.well.auction.shop.entity.ShopEntity;
import net.gasull.well.auction.util.ItemStackUtil;
import net.gasull.well.conf.WellPermissionManager.WellPermissionException;

import org.apache.commons.lang.StringUtils;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.avaje.ebean.Transaction;

/**
 * The AuctionShop manager.
 */
public class AuctionShopManager {

	/** The plugin. */
	private WellAuction plugin;

	/** The shop entity manager. */
	private AucShopEntityManager shopEntityManager;

	/** The enabled, to avoid using this between reloads. */
	private boolean enabled = false;

	/** The max sale id. */
	private int maxSaleId = 0;

	/**
	 * Instantiates a new auction shop manager.
	 * 
	 * @param plugin
	 *            the plugin
	 * @param shopEntityManager
	 *            the shop entity manager
	 */
	public AuctionShopManager(WellAuction plugin, AucShopEntityManager shopEntityManager) {
		this.plugin = plugin;
		this.shopEntityManager = shopEntityManager;
	}

	/**
	 * Sell.
	 * 
	 * @param player
	 *            the player
	 * @param theItem
	 *            the the item
	 * @return the auction sale
	 * @throws AuctionShopException
	 *             the auction shop exception
	 * @throws WellPermissionException
	 *             the well permission exception
	 */
	public AuctionSale sell(Player player, ItemStack theItem) throws AuctionShopException, WellPermissionException {

		WellCore.permission().can(player, plugin.lang().get("permission.sell"), "well.auction.sell");
		checkEnabled(player);
		AuctionShop shop = plugin.db().getShop(theItem);

		if (shop == null) {
			throw new AuctionShopException("No registered shop for item " + ItemStackUtil.asString(theItem));
		}
		if (!shop.getStackSizes().contains(theItem.getAmount())) {
			String msg = plugin.lang().error("sell.invalidStackSize");
			msg = msg.replace("%amount%", String.valueOf(theItem.getAmount())).replace("%amounts%", StringUtils.join(shop.getStackSizes(), ", "));
			player.sendMessage(msg);
			throw new AuctionShopException(String.format("To %s : %s", player.getName(), msg));
		}

		Transaction t = plugin.db().transaction();

		try {
			AuctionSellerData sellerData = plugin.db().findSellerData(player, shop);
			AuctionSale sale = new AuctionSale(++maxSaleId, plugin, sellerData, theItem);
			plugin.db().save(sale);
			shop.getSales().refresh(sale);
			t.commit();
			return sale;
		} finally {
			t.end();
		}
	}

	/**
	 * Unsell.
	 * 
	 * @param player
	 *            the player
	 * @param theItem
	 *            the the item
	 * @return the item stack
	 * @throws AuctionShopException
	 *             the auction shop exception
	 * @throws WellPermissionException
	 *             the well permission exception
	 */
	public ItemStack unsell(Player player, ItemStack theItem) throws AuctionShopException, WellPermissionException {
		WellCore.permission().can(player, plugin.lang().get("permission.sell"), "well.auction.sell");
		checkEnabled(player);
		AuctionShop shop = plugin.db().getShop(theItem);

		if (shop == null) {
			throw new AuctionShopException("No registered shop for item " + ItemStackUtil.asString(theItem));
		}

		AuctionSellerData sellerData = plugin.db().findSellerData(player, shop);
		AuctionSale sale = getSale(sellerData, theItem);

		// Lock the sale for the removal
		if (sale != null && sale.lock()) {
			Transaction t = plugin.db().transaction();

			try {
				ItemStack item = removeSale(shop, sale);
				sale.unlock();
				t.commit();
				return item;
			} finally {
				t.end();
			}
		}

		// Handle failure here
		String msg = plugin.lang().error("buy.sorry").replace("%item%", ItemStackUtil.asString(theItem));

		player.sendMessage(msg);
		throw new AuctionShopException("To " + player.getName() + " : " + msg);
	}

	/**
	 * Buy.
	 * 
	 * @param player
	 *            the player
	 * @param saleStack
	 *            the {@link ItemStack} on sale
	 * @return the sale
	 * @throws AuctionShopException
	 *             the auction shop exception
	 * @throws WellPermissionException
	 *             the well permission exception
	 */
	public AuctionSale buy(Player player, ItemStack saleStack) throws AuctionShopException, WellPermissionException {

		WellCore.permission().can(player, plugin.lang().get("permission.buy"), "well.auction.buy");
		checkEnabled(player);
		AuctionSale sale = plugin.db().saleFromSaleStack(saleStack);

		if (sale != null && sale.lock()) {

			double money = plugin.economy().getBalance(player);
			Double price = sale.getTradePrice();

			if (money >= sale.getTradePrice()) {
				Transaction t = plugin.db().transaction();

				try {
					ItemStack item = removeSale(sale.getShop(), sale);

					// Notify both players
					OfflinePlayer seller = sale.getSellerData().getAuctionPlayer().getPlayer();
					String priceStr = plugin.economy().format(price);
					player.sendMessage(ChatColor.DARK_GREEN
							+ plugin.lang().get("buy.notification").replace("%item%", ItemStackUtil.asString(item)).replace("%player%", seller.getName())
									.replace("%price%", priceStr));

					if (seller.isOnline() && seller instanceof Player) {
						((Player) seller).sendMessage(ChatColor.BLUE
								+ plugin.lang().get("sell.notification").replace("%item%", ItemStackUtil.asString(item)).replace("%player%", player.getName())
										.replace("%price%", priceStr));
					}

					plugin.economy().withdrawPlayer(player, price);
					plugin.economy().depositPlayer(seller, price);

					t.commit();
					return sale;
				} finally {
					t.end();
				}
			}

			sale.unlock();
		}

		// Handle failure here
		String msg;
		if (sale == null) {
			msg = plugin.lang().error("buy.sorry").replace("%item%", ItemStackUtil.asString(saleStack));

			// This time, ensure to remove the sale from shop
			Integer saleId = AuctionSale.idFromTradeStack(saleStack);

			if (saleId != null) {
				// Create a dummy sale
				sale = new AuctionSale();
				sale.setId(saleId);

				// Create a template itemstack from sale to find associated shop
				ItemStack soldItem = new ItemStack(saleStack.getType());
				soldItem.setData(saleStack.getData());
				AuctionShop shop = plugin.db().getShop(saleStack);

				shop.getSales().remove(sale);
			}
		} else {
			msg = plugin.lang().get("buy.noMoney").replace("%item%", ItemStackUtil.asString(saleStack));
		}

		player.sendMessage(msg);
		throw new AuctionShopException("To " + player.getName() + " : " + msg);
	}

	/**
	 * Change sale price.
	 * 
	 * @param player
	 *            the player
	 * @param sale
	 *            the sale
	 * @param price
	 *            the price
	 * @throws AuctionShopException
	 *             the auction shop exception
	 */
	public void changeSalePrice(Player player, AuctionSale sale, Double price) throws AuctionShopException {
		Double newPrice;
		String successMsg;

		if (price < 0) {
			newPrice = null;
			successMsg = plugin.lang().get("player.setPrice.unset").replace("%item%", ItemStackUtil.asString(sale.getItem()));
		} else {
			newPrice = price;
			successMsg = plugin.lang().get("player.setPrice.success").replace("%item%", ItemStackUtil.asString(sale.getItem()))
					.replace("%price%", plugin.economy().format(price));
		}

		checkEnabled(player);

		if (sale.lock()) {
			Transaction t = plugin.db().transaction();

			try {
				changePrice(sale, newPrice);
				sale.unlock();
				sale.getSellerData().getAuctionPlayer().sendMessage(ChatColor.BLUE + successMsg);
				t.commit();
			} finally {
				t.end();
			}
		}
	}

	/**
	 * Change price. Not directly changing setter directly because of Avaje
	 * wrapping.
	 * 
	 * @param sale
	 *            the sale
	 * @param price
	 *            the price
	 */
	private void changePrice(AuctionSale sale, Double price) {
		sale.setPrice(price);

		if (price == null) {
			Double defaultPrice = sale.getSellerData().getDefaultPrice();

			if (defaultPrice != null) {
				sale.setUnitPrice(defaultPrice * sale.getItem().getAmount());
			}
		} else {
			sale.setUnitPrice(price / (double) sale.getItem().getAmount());
		}

		plugin.db().save(sale);
		sale.getSellerData().getShop().getSales().refresh(sale);
	}

	/**
	 * Sets the default price.
	 * 
	 * @param player
	 *            the player
	 * @param sellerData
	 *            the seller data
	 * @param price
	 *            the price
	 * @throws AuctionShopException
	 *             the auction shop exception
	 */
	public void setDefaultPrice(Player player, AuctionSellerData sellerData, Double price) throws AuctionShopException {
		Double defaultPrice;
		String successMsg;

		if (price < 0) {
			defaultPrice = null;
			successMsg = plugin.lang().get("player.setPrice.unsetDefault").replace("%item%", ItemStackUtil.asString(sellerData.getShop().getRefItemCopy()));
		} else {
			defaultPrice = price;
			successMsg = plugin.lang().get("player.setPrice.successDefault").replace("%item%", ItemStackUtil.asString(sellerData.getShop().getRefItemCopy()))
					.replace("%price%", plugin.economy().format(price));
		}

		checkEnabled(player);
		sellerData.setDefaultPrice(defaultPrice);
		plugin.db().save(sellerData);

		// Update associated sales
		List<AuctionSale> sales = plugin.db().findSales(sellerData);
		boolean changed = false;

		for (AuctionSale sale : sales) {
			if (sale.getPrice() == null) {
				changed = true;
				sale.setUnitPrice(defaultPrice);
			}
		}

		if (changed) {
			plugin.db().save(sales);
		}

		// Refresh the price of sales that depend on default price
		AuctionShop shop = sellerData.getShop();
		if (shop != null) {
			shop.getSales().refreshAll();
		}

		sellerData.getAuctionPlayer().sendMessage(ChatColor.BLUE + successMsg);
	}

	/**
	 * Checks if is enabled.
	 * 
	 * @return true, if is enabled
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Enable.
	 */
	public void enable() {
		enabled = true;
	}

	/**
	 * Disable.
	 */
	public void disable() {
		enabled = false;
	}

	/**
	 * Check enabled.
	 * 
	 * @param player
	 *            the player
	 * @throws AuctionShopException
	 *             the auction shop exception
	 */
	public void checkEnabled(Player player) throws AuctionShopException {
		if (!isEnabled()) {
			player.sendMessage(plugin.lang().error("lang.db.error.sync"));
			throw new AuctionShopException("");
		}
	}

	/**
	 * Gets the sale.
	 * 
	 * @param sellerData
	 *            the seller data
	 * @param theItem
	 *            the the item
	 * @return the sale
	 */
	public AuctionSale getSale(AuctionSellerData sellerData, ItemStack theItem) {
		List<AuctionSale> sales = plugin.db().findSales(sellerData);
		for (AuctionSale sale : sales) {
			if (sale.isSellingStack(theItem)) {
				return sale;
			}
		}
		return null;
	}

	/**
	 * Removes the sale.
	 * 
	 * @param shop
	 *            the shop
	 * @param sale
	 *            the sale
	 * @return the item stack
	 */
	private ItemStack removeSale(AuctionShop shop, AuctionSale sale) {
		plugin.db().delete(sale);
		shop.getSales().refresh(sale);
		return sale.getItem();
	}

	/**
	 * Load the shop manager from DB.
	 */
	public void load() {
		List<AuctionShop> dbShops = plugin.db().listShops();

		for (AuctionShop shop : dbShops) {
			shop.setup(plugin);
			plugin.db().registerShop(shop);
			shop.getSales().refreshAll();
		}

		List<ShopEntityModel> registered = plugin.db().listShopEntities();

		for (ShopEntityModel shopEntityModel : registered) {
			ShopEntity shopEntity = shopEntityManager.get(shopEntityModel);

			if (shopEntity != null) {
				shopEntity.register();
			}
		}

		AuctionSale lastSale = plugin.db().getDb().find(AuctionSale.class).order("id desc").setMaxRows(1).findUnique();

		if (lastSale != null) {
			maxSaleId = lastSale.getId();
		}
	}
}
