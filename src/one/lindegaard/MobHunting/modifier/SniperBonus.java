package one.lindegaard.MobHunting.modifier;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import one.lindegaard.MobHunting.DamageInformation;
import one.lindegaard.MobHunting.HuntData;
import one.lindegaard.MobHunting.Messages;
import one.lindegaard.MobHunting.MobHunting;

public class SniperBonus implements IModifier
{

	@Override
	public String getName()
	{
		return ChatColor.GRAY + Messages.getString("bonus.sniper.name"); //$NON-NLS-1$
	}

	@Override
	public double getMultiplier(LivingEntity deadEntity, Player killer, HuntData data, DamageInformation extraInfo, EntityDamageByEntityEvent lastDamageCause)
	{
		return MobHunting.getConfigManager().bonusFarShot / 2;
	}

	@Override
	public boolean doesApply( LivingEntity deadEntity, Player killer, HuntData data, DamageInformation extraInfo, EntityDamageByEntityEvent lastDamageCause )
	{
		if(extraInfo.weapon.getType() == Material.BOW && !extraInfo.mele)
		{
			double dist = extraInfo.attackerPosition.distance(deadEntity.getLocation());
			if(dist >= 20 && dist < 50)
				return true;
		}
		
		return false;
	}

}
