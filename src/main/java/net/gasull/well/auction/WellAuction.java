package net.gasull.well.auction;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import javax.persistence.PersistenceException;

import net.gasull.well.WellConfig;
import net.gasull.well.auction.command.WaucAttachCommand;
import net.gasull.well.auction.command.WaucCommandHelper;
import net.gasull.well.auction.command.WaucDetachCommand;
import net.gasull.well.auction.command.WaucListCommand;
import net.gasull.well.auction.command.WaucPresetCommand;
import net.gasull.well.auction.command.WaucRemoveCommand;
import net.gasull.well.auction.db.WellAuctionDao;
import net.gasull.well.auction.db.model.AucEntityToShop;
import net.gasull.well.auction.db.model.AuctionPlayer;
import net.gasull.well.auction.db.model.AuctionSale;
import net.gasull.well.auction.db.model.AuctionSellerData;
import net.gasull.well.auction.db.model.AuctionShop;
import net.gasull.well.auction.db.model.ShopEntityModel;
import net.gasull.well.auction.event.AuctionBlockShopListener;
import net.gasull.well.auction.event.AuctionShopInventoryListener;
import net.gasull.well.auction.inventory.AuctionInventoryManager;
import net.gasull.well.auction.shop.AuctionShopManager;
import net.gasull.well.auction.shop.entity.AucShopEntityManager;
import net.gasull.well.command.WellCommandHandler;
import net.milkbowl.vault.economy.Economy;

import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * WellAuction, this is it!
 */
public class WellAuction extends JavaPlugin {

	/** The well config. */
	private WellConfig wellConfig;

	/** The database access object for Well Auction . */
	private WellAuctionDao db;

	/** The shop manager. */
	private AuctionShopManager shopManager;

	/** The shop entity manager. */
	private AucShopEntityManager shopEntityManager;

	/** The inventory manager. */
	private AuctionInventoryManager inventoryManager;

	/** The economy. */
	private Economy economy;

	@Override
	public void onEnable() {
		setupConf();
		setupVault();

		wellConfig = new WellConfig(this, "well-auction.yml");

		if (shopManager == null) {
			shopEntityManager = new AucShopEntityManager(this);
			shopManager = new AuctionShopManager(this, shopEntityManager);
			inventoryManager = new AuctionInventoryManager(this, shopManager);
			setupDb();
		}

		// Listeners
		getServer().getPluginManager().registerEvents(new AuctionShopInventoryListener(this, shopManager, inventoryManager, shopEntityManager), this);
		getServer().getPluginManager().registerEvents(new AuctionBlockShopListener(this, shopEntityManager), this);

		wellConfig.save();
		setupCommands();
	}

	@Override
	public void onDisable() {
		shopManager.disable();
		shopEntityManager.clean();
	}

	@Override
	public List<Class<?>> getDatabaseClasses() {
		List<Class<?>> list = new ArrayList<Class<?>>();
		list.add(AuctionShop.class);
		list.add(ShopEntityModel.class);
		list.add(AucEntityToShop.class);
		list.add(AuctionPlayer.class);
		list.add(AuctionSellerData.class);
		list.add(AuctionSale.class);
		return list;
	}

	/**
	 * Gets the Well suite's config.
	 * 
	 * @return the well config
	 */
	public WellConfig wellConfig() {
		return wellConfig;
	}

	/**
	 * Returns the plugin's DAO.
	 * 
	 * @return the well auction dao
	 */
	public WellAuctionDao db() {
		return db;
	}

	/**
	 * Economy.
	 * 
	 * @return Vault's {@link Economy}
	 */
	public Economy economy() {
		return economy;
	}

	/**
	 * Setup vault.
	 */
	private void setupVault() {
		RegisteredServiceProvider<Economy> economyProvider = getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);

		if (economyProvider == null) {
			throw new RuntimeException("Couldn't initialize Vault's Economy. Is Vault in your plugins?");
		}

		economy = economyProvider.getProvider();
	}

	/**
	 * Setup conf.
	 */
	private void setupConf() {
		saveResource("presets.yml", false);
	}

	/**
	 * Setup commands.
	 */
	private void setupCommands() {
		WaucCommandHelper helper = new WaucCommandHelper(this, shopEntityManager);
		WellCommandHandler.bind(this, "wellauction").attach(new WaucAttachCommand(this, helper)).attach(new WaucDetachCommand(this, helper))
				.attach(new WaucRemoveCommand(this, helper)).attach(new WaucListCommand(this)).attach(new WaucPresetCommand(this, helper));
	}

	/**
	 * Setup DB.
	 */
	private void setupDb() {
		this.db = new WellAuctionDao(this);

		try {
			getDatabase().find(AuctionShop.class).findRowCount();
			shopManager.load();
		} catch (PersistenceException e) {
			getLogger().log(Level.WARNING, "Installing database for " + getDescription().getName() + " due to first time usage", e);
			installDDL();
		}

		shopManager.enable();
	}
}
