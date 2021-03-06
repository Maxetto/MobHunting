package one.lindegaard.MobHunting.commands;

import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import one.lindegaard.MobHunting.Messages;
import one.lindegaard.MobHunting.MobHunting;
import one.lindegaard.MobHunting.update.UpdateHelper;
import one.lindegaard.MobHunting.update.UpdateStatus;

public class VersionCommand implements ICommand {
	@Override
	public String getName() {
		return "version";
	}

	@Override
	public String[] getAliases() {
		return new String[] { "ver", "-v" };
	}

	@Override
	public String getPermission() {
		return "mobhunting.version";
	}

	@Override
	public String[] getUsageString(String label, CommandSender sender) {
		return new String[] { label + ChatColor.RED + " version"
				+ ChatColor.GOLD + " to get the version number" };
	}

	@Override
	public String getDescription() {
		return Messages.getString("mobhunting.commands.version.description");
	}

	@Override
	public boolean canBeConsole() {
		return true;
	}

	@Override
	public boolean canBeCommandBlock() {
		return false;
	}

	@Override
	public boolean onCommand(CommandSender sender, String label, String[] args) {

		sender.sendMessage(ChatColor.GREEN
				+ Messages.getString(
						"mobhunting.commands.version.currentversion","currentversion",
						MobHunting.getInstance().getDescription().getVersion()));
		if (UpdateHelper.getUpdateAvailable() == UpdateStatus.AVAILABLE)
			sender.sendMessage(ChatColor.GREEN
					+ Messages.getString(
							"mobhunting.commands.version.newversion","newversion",
							UpdateHelper.getBukkitUpdate().getVersionName()));
		if (sender.hasPermission("mobhunting.update")) {
			UpdateHelper.pluginUpdateCheck(sender, true, true);
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, String label,
			String[] args) {
		return null;
	}

}
