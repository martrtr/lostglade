package com.lostglade.server;

import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class NecromancerDefenseSystem {
	private static final double DEFAULT_RADIUS_BLOCKS = 3.0D;
	private static final double DEFAULT_KNOCKBACK_DISTANCE_BLOCKS = 8.0D;
	private static final double DEFAULT_MANA_COST = 50.0D;
	private static final int PULSE_DURATION_TICKS = 8;
	private static final double LAUNCH_ANGLE_RADIANS = Math.toRadians(30.0D);
	private static final double AIR_HORIZONTAL_DRAG = 0.91D;
	private static final double AIR_VERTICAL_DRAG = 0.98D;
	private static final double GRAVITY_PER_TICK = 0.08D;
	private static final int VELOCITY_SEARCH_STEPS = 64;
	private static final double DISTANCE_CORRECTION = 0.9834216741023181D;
	private static final double WAVE_MAX_HEIGHT_BLOCKS = 3.0D;
	private static final int WAVE_VERTICAL_BANDS = 6;
	private static final DustParticleOptions BLACK_WAVE = new DustParticleOptions(0x000000, 1.75F);
	private static final DustParticleOptions BLACK_WAVE_DEPTH = new DustParticleOptions(0x080808, 1.35F);
	private static final DustParticleOptions NECRO_DUST = new DustParticleOptions(0x32113F, 0.85F);
	private static final DustParticleOptions DARK_SOUL_DUST = new DustParticleOptions(0x54206F, 0.85F);
	private static final DustParticleOptions RED_SOUL_DUST = new DustParticleOptions(0xD62F2F, 0.62F);
	private static final List<DefensePulse> ACTIVE_PULSES = new ArrayList<>();

	private NecromancerDefenseSystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(NecromancerDefenseSystem::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ACTIVE_PULSES.clear());
	}

	public static int use(ServerPlayer player, RaceAbilityConfig ability) {
		if (player == null || ability == null || player.isSpectator() || !player.isAlive()
				|| !(player.level() instanceof ServerLevel level)) return 0;
		double manaCost = nonNegativeOrDefault(ability.necromancerDefenseManaCost, DEFAULT_MANA_COST);
		if (!NecromancerStockSystem.trySpendMana(player, manaCost)) return 0;

		double radius = nonNegativeOrDefault(ability.necromancerDefenseRadiusBlocks, DEFAULT_RADIUS_BLOCKS);
		double knockbackDistance = nonNegativeOrDefault(
				ability.necromancerDefenseKnockbackDistanceBlocks,
				DEFAULT_KNOCKBACK_DISTANCE_BLOCKS
		);
		AABB playerBounds = player.getBoundingBox();
		Vec3 center = playerBounds.getCenter();
		ACTIVE_PULSES.add(new DefensePulse(
				player.getUUID(),
				level.dimension(),
				center,
				playerBounds.minY,
				playerBounds.getYsize(),
				radius,
				knockbackDistance
		));
		level.playSound(
				null,
				center.x,
				center.y,
				center.z,
				SoundEvents.WARDEN_SONIC_BOOM,
				SoundSource.PLAYERS,
				0.7F,
				0.62F
		);
		return 1;
	}

	private static void tick(MinecraftServer server) {
		Iterator<DefensePulse> iterator = ACTIVE_PULSES.iterator();
		while (iterator.hasNext()) {
			DefensePulse pulse = iterator.next();
			ServerLevel level = server.getLevel(pulse.dimension);
			if (level == null || pulse.radius <= 0.0D || ++pulse.ageTicks > PULSE_DURATION_TICKS) {
				iterator.remove();
				continue;
			}

			double expansionProgress = PULSE_DURATION_TICKS <= 1
					? 1.0D
					: (pulse.ageTicks - 1.0D) / (PULSE_DURATION_TICKS - 1.0D);
			double waveRadius = pulse.radius * expansionProgress;
			pushReachedEntities(level, pulse, waveRadius);
			spawnWaveParticles(level, pulse, waveRadius, expansionProgress);
			if (pulse.ageTicks >= PULSE_DURATION_TICKS) iterator.remove();
		}
	}

	private static void pushReachedEntities(ServerLevel level, DefensePulse pulse, double waveRadius) {
		AABB bounds = new AABB(pulse.center, pulse.center).inflate(waveRadius + 0.6D);
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, bounds, target ->
				target.isAlive()
						&& !target.isSpectator()
						&& !target.getUUID().equals(pulse.ownerId)
						&& (target instanceof Mob || target instanceof ServerPlayer))) {
			if (pulse.pushedEntities.contains(entity.getUUID())) continue;
			Vec3 targetCenter = entity.getBoundingBox().getCenter();
			if (targetCenter.distanceToSqr(pulse.center) > waveRadius * waveRadius) continue;
			pulse.pushedEntities.add(entity.getUUID());
			applyKnockback(entity, pulse.center, pulse.knockbackDistance);
		}
	}

	private static void applyKnockback(LivingEntity entity, Vec3 center, double distance) {
		Vec3 horizontal = new Vec3(entity.getX() - center.x, 0.0D, entity.getZ() - center.z);
		if (horizontal.lengthSqr() < 1.0E-8D) {
			double angle = Math.floorMod(entity.getId() * 101, 360) * Math.PI / 180.0D;
			horizontal = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
		} else {
			horizontal = horizontal.normalize();
		}

		double horizontalVelocity = horizontalVelocityForDistance(entity, Math.max(0.0D, distance));
		double verticalVelocity = horizontalVelocity * Math.tan(LAUNCH_ANGLE_RADIANS);
		entity.setDeltaMovement(horizontal.x * horizontalVelocity, verticalVelocity, horizontal.z * horizontalVelocity);
		entity.hurtMarked = true;
		if (entity instanceof ServerPlayer player) {
			player.connection.send(new ClientboundSetEntityMotionPacket(player));
		}
	}

	private static double horizontalVelocityForDistance(LivingEntity entity, double distance) {
		if (distance <= 0.0D) return 0.0D;
		double initialFriction = AIR_HORIZONTAL_DRAG;
		if (entity.onGround()) {
			initialFriction = entity.level()
					.getBlockState(entity.getBlockPosBelowThatAffectsMyMovement())
					.getBlock()
					.getFriction() * AIR_HORIZONTAL_DRAG;
		}
		double low = 0.0D;
		double high = Math.max(1.0D, distance / 4.0D);
		while (simulatedHorizontalRange(high, initialFriction) < distance && high < 64.0D) high *= 2.0D;
		for (int iteration = 0; iteration < VELOCITY_SEARCH_STEPS; iteration++) {
			double middle = (low + high) * 0.5D;
			if (simulatedHorizontalRange(middle, initialFriction) < distance) low = middle;
			else high = middle;
		}
		return high * DISTANCE_CORRECTION;
	}

	private static double simulatedHorizontalRange(double horizontalVelocity, double initialFriction) {
		double horizontalPosition = 0.0D;
		double verticalPosition = 0.0D;
		double verticalVelocity = horizontalVelocity * Math.tan(LAUNCH_ANGLE_RADIANS);
		for (int tick = 0; tick < 200; tick++) {
			horizontalPosition += horizontalVelocity;
			verticalPosition += verticalVelocity;
			horizontalVelocity *= tick == 0 ? initialFriction : AIR_HORIZONTAL_DRAG;
			verticalVelocity = (verticalVelocity - GRAVITY_PER_TICK) * AIR_VERTICAL_DRAG;
			if (tick > 0 && verticalPosition <= 0.0D) break;
		}
		return horizontalPosition;
	}

	private static void spawnWaveParticles(
			ServerLevel level,
			DefensePulse pulse,
			double radius,
			double expansionProgress
	) {
		double densityProgress = Math.pow(Mth.clamp(expansionProgress, 0.0D, 1.0D), 1.75D);
		int angularPoints = 6 + (int) Math.round(154.0D * densityProgress);
		int doubledDensityBuckets = (int) Math.round(16.0D * densityProgress);
		double phase = pulse.visualPhase;
		double waveHeight = pulse.initialHeight
				+ (WAVE_MAX_HEIGHT_BLOCKS - pulse.initialHeight) * expansionProgress;
		for (int band = 0; band < WAVE_VERTICAL_BANDS; band++) {
			double verticalProgress = band / (double) (WAVE_VERTICAL_BANDS - 1);
			double y = pulse.baseY + verticalProgress * waveHeight;

			// The front leans forward towards its crest instead of forming a flat cylinder.
			double curvedProfile = -0.18D * (1.0D - verticalProgress)
					+ 0.28D * Math.sin(Math.PI * verticalProgress)
					+ 0.32D * Math.pow(verticalProgress, 4.0D);
			double bandOffset = (band & 1) == 0 ? 0.0D : Math.PI / angularPoints;
			for (int index = 0; index < angularPoints; index++) {
				double angle = Math.PI * 2.0D * index / angularPoints + bandOffset;
				double ripple = 0.075D * Math.sin(angle * 6.0D + phase + verticalProgress * 2.0D);
				double pointRadius = Math.max(0.05D, radius + curvedProfile + ripple);
				double cos = Math.cos(angle);
				double sin = Math.sin(angle);
				double x = pulse.center.x + cos * pointRadius;
				double z = pulse.center.z + sin * pointRadius;

				int densityBucket = Math.floorMod(index * 31 + band * 17, 16);
				int particleCount = densityBucket < doubledDensityBuckets ? 2 : 1;
				level.sendParticles(BLACK_WAVE, x, y, z, particleCount, 0.045D, 0.055D, 0.045D, 0.0D);
				if (((index + band) & 1) == 0) {
					double depthRadius = Math.max(0.05D, pointRadius - 0.16D);
					level.sendParticles(
							BLACK_WAVE_DEPTH,
							pulse.center.x + cos * depthRadius,
							y + 0.04D,
							pulse.center.z + sin * depthRadius,
							particleCount,
							0.035D,
							0.04D,
							0.035D,
							0.0D
					);
				}
				int paletteIndex = Math.floorMod(index + band * 7, 24);
				if (paletteIndex % 4 == 0) {
					level.sendParticles(NECRO_DUST, x, y, z, 1, 0.035D, 0.045D, 0.035D, 0.002D);
				}
				if (paletteIndex % 8 == 1) {
					level.sendParticles(DARK_SOUL_DUST, x, y, z, 1, 0.03D, 0.04D, 0.03D, 0.002D);
				}
				if (paletteIndex == 2 || paletteIndex == 14) {
					level.sendParticles(RED_SOUL_DUST, x, y, z, 1, 0.025D, 0.035D, 0.025D, 0.002D);
				}
				if (paletteIndex == 5) {
					level.sendParticles(ParticleTypes.CRIMSON_SPORE, x, y, z, 1, 0.02D, 0.03D, 0.02D, 0.001D);
				}
				if (paletteIndex == 11) {
					level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y, z, 1, 0.025D, 0.035D, 0.025D, 0.004D);
				}
			}
		}
	}

	private static double nonNegativeOrDefault(double value, double fallback) {
		return Double.isFinite(value) && value >= 0.0D ? value : fallback;
	}

	private static final class DefensePulse {
		private final UUID ownerId;
		private final ResourceKey<Level> dimension;
		private final Vec3 center;
		private final double baseY;
		private final double initialHeight;
		private final double radius;
		private final double knockbackDistance;
		private final double visualPhase;
		private final Set<UUID> pushedEntities = new HashSet<>();
		private int ageTicks;

		private DefensePulse(
				UUID ownerId,
				ResourceKey<Level> dimension,
				Vec3 center,
				double baseY,
				double initialHeight,
				double radius,
				double knockbackDistance
		) {
			this.ownerId = ownerId;
			this.dimension = dimension;
			this.center = center;
			this.baseY = baseY;
			this.initialHeight = initialHeight;
			this.radius = radius;
			this.knockbackDistance = knockbackDistance;
			this.visualPhase = Math.floorMod(ownerId.hashCode(), 360) * Math.PI / 180.0D;
		}
	}
}
