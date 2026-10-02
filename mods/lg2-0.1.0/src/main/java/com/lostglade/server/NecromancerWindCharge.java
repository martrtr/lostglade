package com.lostglade.server;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SimpleExplosionDamageCalculator;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.function.Function;

final class NecromancerWindCharge extends WindCharge {
	private static final float VANILLA_RADIUS = 1.2F;
	private static final float DOUBLE_VANILLA_KNOCKBACK = 2.44F;
	private static final DustParticleOptions DARK_SOUL_DUST = new DustParticleOptions(0x54206F, 0.85F);
	private static final SimpleExplosionDamageCalculator EXPLOSION_DAMAGE_CALCULATOR = new SimpleExplosionDamageCalculator(
			true,
			false,
			Optional.of(DOUBLE_VANILLA_KNOCKBACK),
			BuiltInRegistries.BLOCK.get(BlockTags.BLOCKS_WIND_CHARGE_EXPLOSIONS).map(Function.identity())
	);

	private final float directMagicDamage;

	NecromancerWindCharge(ServerPlayer owner, ServerLevel level, double directDamageHearts) {
		super(owner, level, owner.getX(), owner.getEyeY(), owner.getZ());
		this.directMagicDamage = (float) Math.max(0.0D, directDamageHearts * 2.0D);
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level) || !isAlive()) return;
		Vec3 movement = getDeltaMovement();
		Vec3 trailCenter = position().subtract(movement.scale(0.28D));
		level.sendParticles(DARK_SOUL_DUST, trailCenter.x, trailCenter.y, trailCenter.z, 3, 0.12D, 0.12D, 0.12D, 0.008D);
		level.sendParticles(ParticleTypes.SCULK_SOUL, trailCenter.x, trailCenter.y, trailCenter.z, 1, 0.08D, 0.08D, 0.08D, 0.006D);
		if (tickCount % 2 == 0) {
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, trailCenter.x, trailCenter.y, trailCenter.z, 2, 0.10D, 0.10D, 0.10D, 0.01D);
		}
	}

	@Override
	protected void onHitEntity(EntityHitResult hitResult) {
		if (!(level() instanceof ServerLevel level)) return;
		Entity target = hitResult.getEntity();
		LivingEntity owner = getOwner() instanceof LivingEntity livingOwner ? livingOwner : null;
		if (owner != null) owner.setLastHurtMob(target);
		if (directMagicDamage > 0.0F) {
			var damageSource = damageSources().indirectMagic(this, owner);
			if (target.hurtServer(level, damageSource, directMagicDamage) && target instanceof LivingEntity livingTarget) {
				EnchantmentHelper.doPostAttackEffects(level, livingTarget, damageSource);
			}
		}
		explode(position());
	}

	@Override
	protected void explode(Vec3 position) {
		level().explode(
				this,
				null,
				EXPLOSION_DAMAGE_CALCULATOR,
				position.x,
				position.y,
				position.z,
				VANILLA_RADIUS,
				false,
				Level.ExplosionInteraction.TRIGGER,
				ParticleTypes.GUST_EMITTER_SMALL,
				ParticleTypes.GUST_EMITTER_LARGE,
				WeightedList.of(),
				SoundEvents.WIND_CHARGE_BURST
		);
	}
}
