package one.lindegaard.MobHunting.compatability;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robingrether.idisguise.api.*;
import de.robingrether.idisguise.disguise.*;
//import de.robingrether.idisguise.iDisguise;
import one.lindegaard.MobHunting.MobHunting;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

public class IDisguiseCompat implements Listener {

	// API
	// http://dev.bukkit.org/bukkit-plugins/idisguise/pages/api/

	private static Plugin mPlugin;
	private static DisguiseAPI api;
	private static boolean supported = false;

	public IDisguiseCompat() {
		if (isDisabledInConfig()) {
			MobHunting.getInstance().getLogger().info(
					"Compatability with iDisguise is disabled in config.yml");
		} else {
			mPlugin = Bukkit.getServer().getPluginManager()
					.getPlugin("iDisguise");
			api = MobHunting.getInstance().getServer().getServicesManager()
					.getRegistration(DisguiseAPI.class).getProvider();

			Bukkit.getPluginManager().registerEvents(this, MobHunting.getInstance());

			MobHunting.getInstance().getLogger().info(
					"Enabling compatability with iDisguise ("
							+ getiDisguise().getDescription().getVersion()
							+ ")");
			supported = true;
		}
	}

	// **************************************************************************
	// OTHER
	// **************************************************************************

	public static Plugin getiDisguise() {
		return mPlugin;
	}

	public static boolean isSupported() {
		return supported;
	}

	public static boolean isDisabledInConfig() {
		return MobHunting.getConfigManager().disableIntegrationIDisguise;
	}

	public static boolean isEnabledInConfig() {
		return !MobHunting.getConfigManager().disableIntegrationIDisguise;
	}

	public static boolean isDisguised(Entity entity) {
		if (entity instanceof Player)
			return api.isDisguised((Player) entity);
		else
			return false;
	}

	public static Disguise getDisguise(Entity entity) {
		if (entity instanceof Player)
			return api.getDisguise((Player) entity);
		else
			return null;
	}

	public static void disguisePlayer(Player player, Disguise disguise) {
		api.disguiseToAll(player, disguise);
	}

	public static void undisguisePlayer(Entity entity) {
		if (entity instanceof Player)
			api.undisguiseToAll((Player) entity);
	}

	private static final DisguiseType aggresiveList[] = { DisguiseType.ZOMBIE,
			DisguiseType.BLAZE, DisguiseType.CAVE_SPIDER, DisguiseType.CREEPER,
			DisguiseType.ENDER_DRAGON, DisguiseType.ENDERMAN,
			DisguiseType.ENDERMITE, DisguiseType.GHAST, DisguiseType.GIANT,
			DisguiseType.GUARDIAN, DisguiseType.PIG_ZOMBIE,
			DisguiseType.SKELETON, DisguiseType.SLIME, DisguiseType.SPIDER,
			DisguiseType.WITCH, DisguiseType.WITHER, DisguiseType.ZOMBIE };
	// TODO: SHULKER not supported by IDisguise yet???
	
	public static final Set<DisguiseType> aggresiveMobs = new HashSet<DisguiseType>(
			Arrays.asList(aggresiveList));

	private static final DisguiseType passiveList[] = { DisguiseType.BAT,
			DisguiseType.CHICKEN, DisguiseType.COW, DisguiseType.HORSE,
			DisguiseType.IRON_GOLEM, DisguiseType.MAGMA_CUBE,
			DisguiseType.MUSHROOM_COW, DisguiseType.OCELOT, DisguiseType.PIG,
			DisguiseType.RABBIT, DisguiseType.SHEEP, DisguiseType.SILVERFISH,
			DisguiseType.SNOWMAN, DisguiseType.SQUID, };
	public static final Set<DisguiseType> passiveMobs = new HashSet<DisguiseType>(
			Arrays.asList(passiveList));

	private static final DisguiseType otherList[] = { DisguiseType.PLAYER,
			DisguiseType.VILLAGER, DisguiseType.WOLF };
	public static final Set<DisguiseType> otherDisguiseTypes = new HashSet<DisguiseType>(
			Arrays.asList(otherList));

	public static boolean isAggresiveDisguise(Entity entity) {
		Disguise d = getDisguise(entity);
		if (aggresiveMobs.contains(d.getType()))
			return true;
		else
			return false;
	}

	public static boolean isPlayerDisguise(Entity entity) {
		Disguise d = getDisguise(entity);
		if (d.getType().equals(DisguiseType.PLAYER))
			return true;
		else
			return false;
	}

	// **************************************************************************
	// EVENTS
	// **************************************************************************

}
