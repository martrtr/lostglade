package com.lostglade.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public final class OrthodoxUniqueSystem {
	private static final int WAVE_DURATION_TICKS = 240;
	private static final double WAVE_EFFECT_SHELL_PADDING = 0.65D;
	private static final int MIN_WAVE_PARTICLE_POINTS = 1;
	private static final int MAX_WAVE_PARTICLE_POINTS = 200;
	private static final double WAVE_PARTICLE_AREA_PER_POINT = 14.5D;
	private static final double FULL_PARTICLE_RATE_RADIUS = 8.0D;
	private static final double MIN_PARTICLE_EMISSION_RATE = 0.15D;
	private static final int WAVE_SOUND_INTERVAL_TICKS = 30;
	private static final int MIN_WAVE_LIGHT_POINTS = 6;
	private static final int MAX_WAVE_LIGHT_POINTS = 48;
	private static final double WAVE_LIGHT_AREA_PER_POINT = 60.0D;
	private static final ColorParticleOption WAVE_PARTICLE =
			ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF);
	private static final int MAX_GROWTH_CHECKS_PER_TICK = 512;
	private static final int MAX_GROWTH_ACTIONS_PER_TICK = 8;
	private static final int MAX_BONEMEAL_PASSES = 4;
	private static final int MAX_SAPLING_GROWTH_ATTEMPTS = 24;
	private static final Set<EntityType<?>> NATIVE_NETHER_MOBS = Set.of(
			EntityType.BLAZE, EntityType.GHAST, EntityType.HAPPY_GHAST, EntityType.HOGLIN,
			EntityType.MAGMA_CUBE, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.STRIDER,
			EntityType.WITHER_SKELETON, EntityType.ZOGLIN, EntityType.ZOMBIFIED_PIGLIN
	);
	private static final Map<UUID, LightWave> WAVES = new HashMap<>();

	private OrthodoxUniqueSystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(OrthodoxUniqueSystem::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(OrthodoxUniqueSystem::clearAll);
	}

	public static boolean activate(ServerPlayer caster, double radius) {
		if (caster == null || !caster.isAlive() || caster.isSpectator() || radius <= 0.0D || WAVES.containsKey(caster.getUUID())) return false;
		ServerLevel level = caster.level();
		Vec3 center = new Vec3(caster.getX(), caster.getY() + caster.getBbHeight() * 0.5D, caster.getZ());
		LightWave wave = new LightWave(caster.getUUID(), level, center, radius, buildBlockShells(level, center, radius));
		WAVES.put(caster.getUUID(), wave);
		level.playSound(null, caster.blockPosition(), SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 0.85F, 1.35F);
		level.playSound(null, caster.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 0.85F);
		return true;
	}

	private static void tick(MinecraftServer server) {
		if (server == null || WAVES.isEmpty()) return;
		Iterator<LightWave> iterator = WAVES.values().iterator();
		while (iterator.hasNext()) {
			LightWave wave = iterator.next();
			if (wave.level.getServer() != server) {
				clearWaveLights(wave);
				iterator.remove();
				continue;
			}
			if (wave.age < WAVE_DURATION_TICKS) {
				wave.age++;
				double previousProgress = (wave.age - 1) / (double) WAVE_DURATION_TICKS;
				double progress = wave.age / (double) WAVE_DURATION_TICKS;
				double previousRadius = wave.radius * previousProgress;
				double currentRadius = wave.radius * progress;
				emitWaveFront(wave, currentRadius);
				emitExpansionSound(wave, progress);
				updateWaveLights(wave, currentRadius);
				applyEntityEffects(wave, previousRadius, currentRadius);
				wave.pendingGrowth.addAll(wave.blockShells.get(wave.age - 1));
				if (wave.age == WAVE_DURATION_TICKS) {
					clearWaveLights(wave);
					wave.level.playSound(null, BlockPos.containing(wave.center), SoundEvents.AMETHYST_BLOCK_RESONATE,
							SoundSource.PLAYERS, 0.9F, 1.65F);
				}
			}
			processGrowthQueue(wave);
			if (wave.age >= WAVE_DURATION_TICKS && wave.pendingGrowth.isEmpty()) iterator.remove();
		}
	}

	private static void emitWaveFront(LightWave wave, double radius) {
		if (radius <= 0.05D) return;
		double emissionRate = Math.max(
				MIN_PARTICLE_EMISSION_RATE,
				Math.min(1.0D, radius / FULL_PARTICLE_RATE_RADIUS)
		);
		wave.particleEmissionBudget += emissionRate;
		if (wave.particleEmissionBudget < 1.0D) return;
		wave.particleEmissionBudget -= 1.0D;
		int pointCount = wavePointCount(radius, WAVE_PARTICLE_AREA_PER_POINT,
				MIN_WAVE_PARTICLE_POINTS, MAX_WAVE_PARTICLE_POINTS);
		for (int i = 0; i < pointCount; i++) {
			Vec3 direction = stableSphereDirection(i);
			Vec3 point = wave.center.add(direction.scale(radius));
			wave.level.sendParticles(WAVE_PARTICLE, point.x, point.y, point.z,
					1, 0.0D, 0.0D, 0.0D, 0.0D);
			wave.level.sendParticles(ParticleTypes.END_ROD, point.x, point.y, point.z,
					1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
	}

	private static void emitExpansionSound(LightWave wave, double progress) {
		if (wave.age <= 0 || wave.age >= WAVE_DURATION_TICKS || wave.age % WAVE_SOUND_INTERVAL_TICKS != 0) return;
		float pitch = 0.82F + (float) Math.max(0.0D, Math.min(1.0D, progress)) * 0.24F;
		wave.level.playSound(
				null,
				BlockPos.containing(wave.center),
				SoundEvents.BREEZE_IDLE_AIR,
				SoundSource.PLAYERS,
				0.52F,
				pitch
		);
	}

	private static void updateWaveLights(LightWave wave, double radius) {
		if (radius < 0.75D) {
			clearWaveLights(wave);
			return;
		}
		int pointCount = wavePointCount(radius, WAVE_LIGHT_AREA_PER_POINT,
				MIN_WAVE_LIGHT_POINTS, MAX_WAVE_LIGHT_POINTS);
		Set<BlockPos> desiredLights = new HashSet<>();
		for (int i = 0; i < pointCount; i++) {
			Vec3 point = wave.center.add(stableSphereDirection(i).scale(radius));
			BlockPos pos = BlockPos.containing(point);
			if (!wave.level.isInWorldBounds(pos) || !wave.level.hasChunkAt(pos)) continue;
			BlockState state = wave.level.getBlockState(pos);
			if (state.isAir() || (state.is(Blocks.LIGHT) && wave.temporaryLights.contains(pos))) {
				desiredLights.add(pos.immutable());
			}
		}

		Iterator<BlockPos> existing = wave.temporaryLights.iterator();
		while (existing.hasNext()) {
			BlockPos pos = existing.next();
			if (desiredLights.contains(pos)) continue;
			if (wave.level.hasChunkAt(pos) && wave.level.getBlockState(pos).is(Blocks.LIGHT)) {
				wave.level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
			existing.remove();
		}

		BlockState light = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15);
		for (BlockPos pos : desiredLights) {
			if (wave.temporaryLights.contains(pos)) continue;
			if (wave.level.getBlockState(pos).isAir() && wave.level.setBlock(pos, light, Block.UPDATE_ALL)) {
				wave.temporaryLights.add(pos);
			}
		}
	}

	private static int wavePointCount(double radius, double areaPerPoint, int minimum, int maximum) {
		double surfaceArea = 4.0D * Math.PI * radius * radius;
		return Math.max(minimum, Math.min(maximum, (int) Math.ceil(surfaceArea / areaPerPoint)));
	}

	private static Vec3 stableSphereDirection(int index) {
		double verticalSample = fractional(0.5D + index * 0.7548776662466927D);
		double angleSample = fractional(0.5D + index * 0.5698402909980532D);
		double y = 1.0D - 2.0D * verticalSample;
		double horizontal = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
		double angle = angleSample * Math.PI * 2.0D;
		return new Vec3(Math.cos(angle) * horizontal, y, Math.sin(angle) * horizontal);
	}

	private static double fractional(double value) {
		return value - Math.floor(value);
	}

	private static void clearWaveLights(LightWave wave) {
		for (BlockPos pos : wave.temporaryLights) {
			if (wave.level.hasChunkAt(pos) && wave.level.getBlockState(pos).is(Blocks.LIGHT)) {
				wave.level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		wave.temporaryLights.clear();
	}

	private static void clearAll(MinecraftServer server) {
		for (LightWave wave : WAVES.values()) clearWaveLights(wave);
		WAVES.clear();
	}

	private static void applyEntityEffects(LightWave wave, double previousRadius, double currentRadius) {
		double innerRadius = Math.max(0.0D, previousRadius - WAVE_EFFECT_SHELL_PADDING);
		double outerRadius = currentRadius + WAVE_EFFECT_SHELL_PADDING;
		double innerRadiusSquared = innerRadius * innerRadius;
		double outerRadiusSquared = outerRadius * outerRadius;
		AABB bounds = AABB.ofSize(wave.center, outerRadius * 2.0D, outerRadius * 2.0D, outerRadius * 2.0D);
		for (LivingEntity entity : wave.level.getEntitiesOfClass(LivingEntity.class, bounds, LivingEntity::isAlive)) {
			Vec3 entityCenter = entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D);
			double distanceSquared = entityCenter.distanceToSqr(wave.center);
			if (distanceSquared < innerRadiusSquared || distanceSquared > outerRadiusSquared
					|| !wave.affectedEntities.add(entity.getUUID())) continue;
			if (entity instanceof ServerPlayer player) {
				player.heal(player.getMaxHealth());
				removeNegativeEffects(player);
				if (!player.getUUID().equals(wave.ownerId)) spawnCleansingLight(wave.level, player);
			} else if (isIncineratedByLight(entity)) {
				incinerate(wave.level, entity);
			}
		}
	}

	private static void removeNegativeEffects(ServerPlayer player) {
		List<Holder<MobEffect>> harmful = player.getActiveEffects().stream()
				.filter(effect -> effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL)
				.map(MobEffectInstance::getEffect).toList();
		for (Holder<MobEffect> effect : harmful) player.removeEffect(effect);
	}

	private static boolean isIncineratedByLight(LivingEntity entity) {
		return !ServerRaceSystem.isAncientUkrCreditor(entity)
				&& (entity.getType().is(EntityTypeTags.UNDEAD) || isNativeNetherMob(entity.getType()));
	}

	static boolean isNativeNetherMob(EntityType<?> type) {
		return NATIVE_NETHER_MOBS.contains(type);
	}

	private static void incinerate(ServerLevel level, LivingEntity entity) {
		double y = entity.getY() + entity.getBbHeight() * 0.5D;
		double verticalSpread = Math.max(0.25D, entity.getBbHeight() * 0.42D);
		level.sendParticles(ParticleTypes.END_ROD, entity.getX(), y, entity.getZ(), 24, 0.34D, verticalSpread, 0.34D, 0.045D);
		level.sendParticles(ParticleTypes.WHITE_ASH, entity.getX(), y, entity.getZ(), 44, 0.52D, verticalSpread, 0.52D, 0.020D);
		level.sendParticles(ParticleTypes.ASH, entity.getX(), y, entity.getZ(), 40, 0.48D, verticalSpread, 0.48D, 0.014D);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, entity.getX(), y, entity.getZ(), 12, 0.34D, verticalSpread * 0.75D, 0.34D, 0.012D);
		level.playSound(null, entity.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.9F, 1.55F);
		entity.discard();
	}

	private static void spawnCleansingLight(ServerLevel level, LivingEntity entity) {
		double y = entity.getY() + entity.getBbHeight() * 0.6D;
		double verticalSpread = Math.max(0.25D, entity.getBbHeight() * 0.35D);
		level.sendParticles(ParticleTypes.END_ROD, entity.getX(), y, entity.getZ(), 18,
				0.36D, verticalSpread, 0.36D, 0.028D);
	}

	private static List<List<BlockPos>> buildBlockShells(ServerLevel level, Vec3 center, double radius) {
		List<List<BlockPos>> shells = new ArrayList<>(WAVE_DURATION_TICKS);
		for (int i = 0; i < WAVE_DURATION_TICKS; i++) shells.add(new ArrayList<>());
		int minX = (int) Math.floor(center.x - radius), maxX = (int) Math.ceil(center.x + radius);
		int minY = Math.max(level.getMinY(), (int) Math.floor(center.y - radius));
		int maxY = Math.min(level.getMaxY() - 1, (int) Math.ceil(center.y + radius));
		int minZ = (int) Math.floor(center.z - radius), maxZ = (int) Math.ceil(center.z + radius);
		double radiusSquared = radius * radius;
		for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
			double dx = x + 0.5D - center.x, dy = y + 0.5D - center.y, dz = z + 0.5D - center.z;
			double distanceSquared = dx * dx + dy * dy + dz * dz;
			if (distanceSquared > radiusSquared) continue;
			double normalized = Math.sqrt(distanceSquared) / radius;
			double arrivalProgress = normalized;
			int shell = Math.min(WAVE_DURATION_TICKS - 1, (int) Math.floor(arrivalProgress * WAVE_DURATION_TICKS));
			shells.get(shell).add(new BlockPos(x, y, z));
		}
		return shells;
	}

	private static void processGrowthQueue(LightWave wave) {
		int checks = 0, actions = 0;
		while (!wave.pendingGrowth.isEmpty() && checks < MAX_GROWTH_CHECKS_PER_TICK && actions < MAX_GROWTH_ACTIONS_PER_TICK) {
			BlockPos pos = wave.pendingGrowth.removeFirst();
			checks++;
			if (wave.level.hasChunkAt(pos) && growPlant(wave.level, pos)) actions++;
		}
	}

	private static boolean growPlant(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		if (block instanceof NetherWartBlock && state.getValue(NetherWartBlock.AGE) < NetherWartBlock.MAX_AGE) {
			level.setBlock(pos, state.setValue(NetherWartBlock.AGE, NetherWartBlock.MAX_AGE), 3);
			spawnGrowthParticles(level, pos);
			return true;
		}
		if (block == Blocks.CACTUS || block == Blocks.SUGAR_CANE) {
			boolean changed = growColumnPlant(level, pos, block);
			if (changed) spawnGrowthParticles(level, pos);
			return changed;
		}
		if (block instanceof SaplingBlock) return growSapling(level, pos);
		if (!(block instanceof BonemealableBlock) || !isPlantedGrower(block)) return false;
		boolean changed = false;
		for (int pass = 0; pass < MAX_BONEMEAL_PASSES; pass++) {
			state = level.getBlockState(pos);
			if (state.getBlock() != block || !(state.getBlock() instanceof BonemealableBlock current)
					|| !current.isValidBonemealTarget(level, pos, state)) break;
			current.performBonemeal(level, level.random, pos, state);
			changed = true;
			if (level.getBlockState(pos).getBlock() != block) break;
		}
		if (changed) spawnGrowthParticles(level, pos);
		return changed;
	}

	private static boolean growSapling(ServerLevel level, BlockPos pos) {
		boolean changed = false;
		for (int attempt = 0; attempt < MAX_SAPLING_GROWTH_ATTEMPTS; attempt++) {
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof SaplingBlock sapling)) {
				if (changed) spawnGrowthParticles(level, pos);
				return changed;
			}
			sapling.advanceTree(level, pos, state, level.random);
			BlockState result = level.getBlockState(pos);
			changed |= !result.equals(state);
			if (!(result.getBlock() instanceof SaplingBlock)) {
				spawnGrowthParticles(level, pos);
				return true;
			}
		}
		if (changed) spawnGrowthParticles(level, pos);
		return changed;
	}

	private static boolean isPlantedGrower(Block block) {
		return block instanceof CropBlock || block instanceof PitcherCropBlock || block instanceof StemBlock
				|| block instanceof CocoaBlock || block instanceof SweetBerryBushBlock || block instanceof SaplingBlock
				|| block instanceof MushroomBlock || block instanceof FungusBlock || block instanceof BambooSaplingBlock
				|| block instanceof BambooStalkBlock || block instanceof GrowingPlantHeadBlock;
	}

	private static boolean growColumnPlant(ServerLevel level, BlockPos pos, Block block) {
		BlockPos base = pos;
		while (level.getBlockState(base.below()).is(block)) base = base.below();
		int height = 0;
		while (height < 3 && level.getBlockState(base.above(height)).is(block)) height++;
		boolean changed = false;
		while (height < 3) {
			BlockPos next = base.above(height);
			BlockState nextState = block.defaultBlockState();
			if (!level.isEmptyBlock(next) || !nextState.canSurvive(level, next)) break;
			level.setBlock(next, nextState, 3);
			height++;
			changed = true;
		}
		return changed;
	}

	private static void spawnGrowthParticles(ServerLevel level, BlockPos pos) {
		level.sendParticles(
				ParticleTypes.HAPPY_VILLAGER,
				pos.getX() + 0.5D,
				pos.getY() + 0.5D,
				pos.getZ() + 0.5D,
				15,
				0.36D,
				0.36D,
				0.36D,
				0.02D
		);
	}

	private static final class LightWave {
		private final UUID ownerId;
		private final ServerLevel level;
		private final Vec3 center;
		private final double radius;
		private final List<List<BlockPos>> blockShells;
		private final ArrayDeque<BlockPos> pendingGrowth = new ArrayDeque<>();
		private final Set<UUID> affectedEntities = new HashSet<>();
		private final Set<BlockPos> temporaryLights = new HashSet<>();
		private double particleEmissionBudget;
		private int age;

		private LightWave(UUID ownerId, ServerLevel level, Vec3 center, double radius, List<List<BlockPos>> blockShells) {
			this.ownerId = ownerId;
			this.level = level;
			this.center = center;
			this.radius = radius;
			this.blockShells = blockShells;
		}
	}
}
