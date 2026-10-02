package com.lostglade.server;

import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.WindChargeItem;

public final class NecromancerAttackSystem {
	private static final double DEFAULT_DAMAGE_HEARTS = 2.0D;
	private static final double DEFAULT_MANA_COST = 30.0D;

	private NecromancerAttackSystem() {
	}

	public static int use(ServerPlayer player, RaceAbilityConfig ability) {
		if (player == null || ability == null || player.isSpectator() || !player.isAlive()
				|| !(player.level() instanceof ServerLevel level)) return 0;
		double manaCost = nonNegativeOrDefault(ability.necromancerAttackManaCost, DEFAULT_MANA_COST);
		if (!NecromancerStockSystem.trySpendMana(player, manaCost)) return 0;

		double damageHearts = nonNegativeOrDefault(ability.necromancerAttackDamageHearts, DEFAULT_DAMAGE_HEARTS);
		NecromancerWindCharge charge = new NecromancerWindCharge(player, level, damageHearts);
		charge.shootFromRotation(
				player,
				player.getXRot(),
				player.getYRot(),
				0.0F,
				WindChargeItem.PROJECTILE_SHOOT_POWER,
				1.0F
		);
		level.addFreshEntity(charge);
		level.playSound(
				null,
				player.getX(),
				player.getY(),
				player.getZ(),
				SoundEvents.WIND_CHARGE_THROW,
				SoundSource.NEUTRAL,
				0.5F,
				0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F)
		);
		return 1;
	}

	private static double nonNegativeOrDefault(double value, double fallback) {
		return Double.isFinite(value) && value >= 0.0D ? value : fallback;
	}
}
