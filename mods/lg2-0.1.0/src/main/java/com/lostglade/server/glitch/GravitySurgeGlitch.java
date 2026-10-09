package com.lostglade.server.glitch;

import com.google.gson.JsonObject;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.RendererBotPresenceSystem;
import com.lostglade.server.ServerBackroomsSystem;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GravitySurgeGlitch implements ServerGlitchHandler {
	private static final String LEGACY_ENTITY_PERCENT = "entityPercent";
	private static final String LEGACY_ITEM_PERCENT = "itemPercent";
	private static final String MIN_ENTITY_PERCENT = "minEntityPercent";
	private static final String MAX_ENTITY_PERCENT = "maxEntityPercent";
	private static final String MIN_ITEM_PERCENT = "minItemPercent";
	private static final String MAX_ITEM_PERCENT = "maxItemPercent";
	private static final String MIN_DURATION_SECONDS = "minDurationSeconds";
	private static final String MAX_DURATION_SECONDS = "maxDurationSeconds";
	private static final String UPWARD_DIRECTION_WEIGHT = "upwardDirectionWeight";
	private static final String DOWNWARD_DIRECTION_WEIGHT = "downwardDirectionWeight";
	private static final String MIN_DIRECTION_COUNT = "minDirectionCount";
	private static final String MAX_DIRECTION_COUNT = "maxDirectionCount";
	private static final String PLAYER_CONTROL_RATIO = "playerControlRatio";
	private static final String MIN_FORCE = "minForce";
	private static final String MAX_FORCE = "maxForce";
	private static final int MIN_ALLOWED_DIRECTION_COUNT = 1;
	private static final int MAX_ALLOWED_DIRECTION_COUNT = 100;
	private static final int EFFECT_REAPPLY_INTERVAL_TICKS = 1;
	private static final double DEFAULT_MIN_FORCE = 0.22D;
	private static final double DEFAULT_MAX_FORCE = 0.58D;
	private static final double ITEM_FORCE_MULTIPLIER = 0.85D;
	private static final double DURATION_PRIORITY = 2.4D;
	private static final double ENTITY_VELOCITY_BLEND_FACTOR = 0.34D;
	private static final double ITEM_VELOCITY_BLEND_FACTOR = 0.42D;
	private static final double DEFAULT_PLAYER_CONTROL_RATIO = 0.70D;
	private static final Map<UUID, ActiveGravityState> ACTIVE_STATES = new HashMap<>();

	public static void tickActiveStates(MinecraftServer server) {
		if (server == null || ACTIVE_STATES.isEmpty()) {
			return;
		}

		long nowTick = server.overworld().getGameTime();
		Iterator<Map.Entry<UUID, ActiveGravityState>> iterator = ACTIVE_STATES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, ActiveGravityState> mapEntry = iterator.next();
			ActiveGravityState state = mapEntry.getValue();
			Entity entity = findEntity(server, state.dimension, state.entityUuid);
			if (!isTrackedEntityValid(entity) || ServerBackroomsSystem.isInBackrooms(entity)) {
				iterator.remove();
				continue;
			}

			if (entity.level() instanceof ServerLevel serverLevel) {
				state.dimension = serverLevel.dimension();
			}

			if (nowTick >= state.endTick) {
				iterator.remove();
				continue;
			}

			if (nowTick < state.nextApplyTick) {
				continue;
			}

			applySmoothMotion(entity, state);
			state.nextApplyTick = nowTick + EFFECT_REAPPLY_INTERVAL_TICKS;
		}
	}

	public static void resetRuntimeState() {
		ACTIVE_STATES.clear();
	}

	@Override
	public String id() {
		return "gravity_surges";
	}

	@Override
	public GlitchConfig.GlitchEntry defaultEntry() {
		GlitchConfig.GlitchEntry entry = new GlitchConfig.GlitchEntry();
		entry.enabled = true;
		entry.minStabilityPercent = 0.0D;
		entry.maxStabilityPercent = 55.0D;
		entry.chancePerCheck = 0.20D;
		entry.stabilityInfluence = 1.0D;
		entry.minCooldownTicks = 40;
		entry.maxCooldownTicks = 2000;

		JsonObject settings = new JsonObject();
		settings.addProperty(MIN_ENTITY_PERCENT, 10.0D);
		settings.addProperty(MAX_ENTITY_PERCENT, 45.0D);
		settings.addProperty(MIN_ITEM_PERCENT, 15.0D);
		settings.addProperty(MAX_ITEM_PERCENT, 65.0D);
		settings.addProperty(MIN_DURATION_SECONDS, 2);
		settings.addProperty(MAX_DURATION_SECONDS, 10);
		settings.addProperty(UPWARD_DIRECTION_WEIGHT, 1.0D);
		settings.addProperty(DOWNWARD_DIRECTION_WEIGHT, 1.0D);
		settings.addProperty(MIN_DIRECTION_COUNT, 1);
		settings.addProperty(MAX_DIRECTION_COUNT, 10);
		settings.addProperty(PLAYER_CONTROL_RATIO, DEFAULT_PLAYER_CONTROL_RATIO);
		settings.addProperty(MIN_FORCE, DEFAULT_MIN_FORCE);
		settings.addProperty(MAX_FORCE, DEFAULT_MAX_FORCE);
		entry.settings = settings;
		return entry;
	}

	@Override
	public boolean sanitizeSettings(GlitchConfig.GlitchEntry entry) {
		if (entry.settings == null) {
			entry.settings = new JsonObject();
		}

		boolean changed = false;
		boolean hasMinEntityPercent = entry.settings.has(MIN_ENTITY_PERCENT);
		boolean hasMaxEntityPercent = entry.settings.has(MAX_ENTITY_PERCENT);
		boolean hasMinItemPercent = entry.settings.has(MIN_ITEM_PERCENT);
		boolean hasMaxItemPercent = entry.settings.has(MAX_ITEM_PERCENT);
		double legacyEntityPercent = GlitchSettingsHelper.getDouble(entry.settings, LEGACY_ENTITY_PERCENT, 18.0D);
		double legacyItemPercent = GlitchSettingsHelper.getDouble(entry.settings, LEGACY_ITEM_PERCENT, 35.0D);

		changed |= GlitchSettingsHelper.sanitizeDouble(
				entry.settings,
				MIN_ENTITY_PERCENT,
				hasMinEntityPercent ? 10.0D : legacyEntityPercent,
				0.0D,
				100.0D
		);
		changed |= GlitchSettingsHelper.sanitizeDouble(
				entry.settings,
				MAX_ENTITY_PERCENT,
				hasMaxEntityPercent ? 45.0D : legacyEntityPercent,
				0.0D,
				100.0D
		);
		changed |= GlitchSettingsHelper.sanitizeDouble(
				entry.settings,
				MIN_ITEM_PERCENT,
				hasMinItemPercent ? 15.0D : legacyItemPercent,
				0.0D,
				100.0D
		);
		changed |= GlitchSettingsHelper.sanitizeDouble(
				entry.settings,
				MAX_ITEM_PERCENT,
				hasMaxItemPercent ? 65.0D : legacyItemPercent,
				0.0D,
				100.0D
		);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MIN_DURATION_SECONDS, 2, 1, 3600);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_DURATION_SECONDS, 10, 1, 3600);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, UPWARD_DIRECTION_WEIGHT, 1.0D, 0.0D, 1000.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, DOWNWARD_DIRECTION_WEIGHT, 1.0D, 0.0D, 1000.0D);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MIN_DIRECTION_COUNT, 1, MIN_ALLOWED_DIRECTION_COUNT, MAX_ALLOWED_DIRECTION_COUNT);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_DIRECTION_COUNT, 10, MIN_ALLOWED_DIRECTION_COUNT, MAX_ALLOWED_DIRECTION_COUNT);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, PLAYER_CONTROL_RATIO, DEFAULT_PLAYER_CONTROL_RATIO, 0.0D, 1.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, MIN_FORCE, DEFAULT_MIN_FORCE, 0.01D, 8.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, MAX_FORCE, DEFAULT_MAX_FORCE, 0.01D, 8.0D);

		double minEntityPercent = GlitchSettingsHelper.getDouble(entry.settings, MIN_ENTITY_PERCENT, legacyEntityPercent);
		double maxEntityPercent = GlitchSettingsHelper.getDouble(entry.settings, MAX_ENTITY_PERCENT, legacyEntityPercent);
		if (maxEntityPercent < minEntityPercent) {
			entry.settings.addProperty(MAX_ENTITY_PERCENT, minEntityPercent);
			changed = true;
		}

		double minItemPercent = GlitchSettingsHelper.getDouble(entry.settings, MIN_ITEM_PERCENT, legacyItemPercent);
		double maxItemPercent = GlitchSettingsHelper.getDouble(entry.settings, MAX_ITEM_PERCENT, legacyItemPercent);
		if (maxItemPercent < minItemPercent) {
			entry.settings.addProperty(MAX_ITEM_PERCENT, minItemPercent);
			changed = true;
		}

		int minDurationSeconds = GlitchSettingsHelper.getInt(entry.settings, MIN_DURATION_SECONDS, 2);
		int maxDurationSeconds = GlitchSettingsHelper.getInt(entry.settings, MAX_DURATION_SECONDS, 10);
		if (maxDurationSeconds < minDurationSeconds) {
			entry.settings.addProperty(MAX_DURATION_SECONDS, minDurationSeconds);
			changed = true;
		}

		int minDirectionCount = GlitchSettingsHelper.getInt(entry.settings, MIN_DIRECTION_COUNT, 1);
		int maxDirectionCount = GlitchSettingsHelper.getInt(entry.settings, MAX_DIRECTION_COUNT, 10);
		if (maxDirectionCount < minDirectionCount) {
			entry.settings.addProperty(MAX_DIRECTION_COUNT, minDirectionCount);
			changed = true;
		}

		double minForce = GlitchSettingsHelper.getDouble(entry.settings, MIN_FORCE, DEFAULT_MIN_FORCE);
		double maxForce = GlitchSettingsHelper.getDouble(entry.settings, MAX_FORCE, DEFAULT_MAX_FORCE);
		if (maxForce < minForce) {
			entry.settings.addProperty(MAX_FORCE, minForce);
			changed = true;
		}

		if (entry.settings.has(LEGACY_ENTITY_PERCENT)) {
			entry.settings.remove(LEGACY_ENTITY_PERCENT);
			changed = true;
		}
		if (entry.settings.has(LEGACY_ITEM_PERCENT)) {
			entry.settings.remove(LEGACY_ITEM_PERCENT);
			changed = true;
		}

		return changed;
	}

	@Override
	public boolean trigger(MinecraftServer server, RandomSource random, GlitchConfig.GlitchEntry entry, double stabilityPercent) {
		tickActiveStates(server);

		List<Entity> entities = new ArrayList<>();
		List<ItemEntity> items = new ArrayList<>();
		collectTargets(server, entities, items);
		if (entities.isEmpty() && items.isEmpty()) {
			return false;
		}

		JsonObject settings = entry.settings == null ? new JsonObject() : entry.settings;
		double instability = getRangeInstabilityFactor(stabilityPercent, entry.minStabilityPercent, entry.maxStabilityPercent);
		double entityPercent = interpolateDouble(
				GlitchSettingsHelper.getDouble(settings, MIN_ENTITY_PERCENT, 10.0D),
				GlitchSettingsHelper.getDouble(settings, MAX_ENTITY_PERCENT, 45.0D),
				instability
		);
		double itemPercent = interpolateDouble(
				GlitchSettingsHelper.getDouble(settings, MIN_ITEM_PERCENT, 15.0D),
				GlitchSettingsHelper.getDouble(settings, MAX_ITEM_PERCENT, 65.0D),
				instability
		);

		int entityTargetCount = getTargetCount(entities.size(), entityPercent);
		int itemTargetCount = getTargetCount(items.size(), itemPercent);
		if (entityTargetCount <= 0 && itemTargetCount <= 0) {
			return false;
		}

		int minDurationSeconds = GlitchSettingsHelper.getInt(settings, MIN_DURATION_SECONDS, 2);
		int maxDurationSeconds = GlitchSettingsHelper.getInt(settings, MAX_DURATION_SECONDS, 10);
		int durationSeconds = pickWeightedDurationSeconds(random, minDurationSeconds, maxDurationSeconds, instability);
		long durationTicks = Math.max(20L, durationSeconds * 20L);
		double upwardDirectionWeight = GlitchSettingsHelper.getDouble(settings, UPWARD_DIRECTION_WEIGHT, 1.0D);
		double downwardDirectionWeight = GlitchSettingsHelper.getDouble(settings, DOWNWARD_DIRECTION_WEIGHT, 1.0D);
		int minDirectionCount = GlitchSettingsHelper.getInt(settings, MIN_DIRECTION_COUNT, 1);
		int maxDirectionCount = GlitchSettingsHelper.getInt(settings, MAX_DIRECTION_COUNT, 10);
		double playerControlRatio = GlitchSettingsHelper.getDouble(settings, PLAYER_CONTROL_RATIO, DEFAULT_PLAYER_CONTROL_RATIO);
		double minForce = GlitchSettingsHelper.getDouble(settings, MIN_FORCE, DEFAULT_MIN_FORCE);
		double maxForce = GlitchSettingsHelper.getDouble(settings, MAX_FORCE, DEFAULT_MAX_FORCE);
		double dynamicMaxForce = interpolateDouble(minForce, maxForce, instability);

		shuffle(entities, random);
		shuffle(items, random);

		List<SelectedTarget> targets = buildInterleavedTargets(entities, entityTargetCount, items, itemTargetCount, random);
		if (targets.isEmpty()) {
			return false;
		}

		int directionCount = Math.min(targets.size(), sampleRangeInt(random, minDirectionCount, maxDirectionCount));
		List<Vec3> directions = buildDirections(random, Math.max(1, directionCount), upwardDirectionWeight, downwardDirectionWeight);
		long nowTick = server.overworld().getGameTime();

		boolean appliedAny = false;
		for (int i = 0; i < targets.size(); i++) {
			SelectedTarget target = targets.get(i);
			double baseForce = sampleRange(random, minForce, dynamicMaxForce);
			Vec3 targetVelocity = directions.get(i % directions.size()).scale(baseForce);
			double velocityBlendFactor = ENTITY_VELOCITY_BLEND_FACTOR;
			if (target.item) {
				targetVelocity = targetVelocity.scale(ITEM_FORCE_MULTIPLIER);
				velocityBlendFactor = ITEM_VELOCITY_BLEND_FACTOR;
			}

			Vec3 appliedVelocity = applySmoothMotion(
					target.entity,
					targetVelocity,
					velocityBlendFactor,
					target.entity instanceof ServerPlayer,
					playerControlRatio
			);
			ACTIVE_STATES.put(target.entity.getUUID(), new ActiveGravityState(
					target.entity.getUUID(),
					target.dimension,
					targetVelocity,
					velocityBlendFactor,
					target.entity instanceof ServerPlayer,
					playerControlRatio,
					appliedVelocity,
					nowTick + durationTicks,
					nowTick + EFFECT_REAPPLY_INTERVAL_TICKS
			));
			appliedAny = true;
		}

		return appliedAny;
	}

	private static void collectTargets(MinecraftServer server, List<Entity> entities, List<ItemEntity> items) {
		for (ServerLevel level : server.getAllLevels()) {
			if (ServerBackroomsSystem.isBackrooms(level)) {
				continue;
			}
			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof ItemEntity itemEntity) {
					if (isEligibleItem(itemEntity)) {
						items.add(itemEntity);
					}
					continue;
				}

				if (isEligibleEntity(entity)) {
					entities.add(entity);
				}
			}
		}
	}

	private static boolean isEligibleEntity(Entity entity) {
		if (entity == null || !entity.isAlive() || entity.isRemoved() || !entity.isPushable()) {
			return false;
		}
		if (entity.isPassenger()) {
			return false;
		}
		if (entity instanceof ServerPlayer player
				&& (player.isSpectator() || RendererBotPresenceSystem.isRendererBot(player))) {
			return false;
		}
		return true;
	}

	private static boolean isEligibleItem(ItemEntity itemEntity) {
		return itemEntity != null
				&& itemEntity.isAlive()
				&& !itemEntity.isRemoved()
				&& !itemEntity.getItem().isEmpty();
	}

	private static boolean isTrackedEntityValid(Entity entity) {
		if (entity instanceof ItemEntity itemEntity) {
			return isEligibleItem(itemEntity);
		}
		return isEligibleEntity(entity);
	}

	private static int getTargetCount(int total, double percent) {
		if (total <= 0 || percent <= 0.0D) {
			return 0;
		}

		double clampedPercent = Math.max(0.0D, Math.min(100.0D, percent));
		int count = (int) Math.round(total * (clampedPercent / 100.0D));
		if (count <= 0) {
			count = 1;
		}
		return Math.min(total, count);
	}

	private static List<SelectedTarget> buildInterleavedTargets(
			List<Entity> entities,
			int entityTargetCount,
			List<ItemEntity> items,
			int itemTargetCount,
			RandomSource random
	) {
		List<SelectedTarget> result = new ArrayList<>(entityTargetCount + itemTargetCount);
		int entityIndex = 0;
		int itemIndex = 0;
		boolean preferEntity = random.nextBoolean();

		while (entityIndex < entityTargetCount || itemIndex < itemTargetCount) {
			boolean canUseEntity = entityIndex < entityTargetCount;
			boolean canUseItem = itemIndex < itemTargetCount;
			if (!canUseEntity && !canUseItem) {
				break;
			}

			if ((preferEntity && canUseEntity) || !canUseItem) {
				Entity entity = entities.get(entityIndex++);
				result.add(new SelectedTarget(entity, getDimension(entity), false));
			} else {
				ItemEntity item = items.get(itemIndex++);
				result.add(new SelectedTarget(item, getDimension(item), true));
			}

			preferEntity = !preferEntity;
		}

		return result;
	}

	private static List<Vec3> buildDirections(RandomSource random, int count, double upwardDirectionWeight, double downwardDirectionWeight) {
		List<Vec3> directions = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			directions.add(buildDirection(random, upwardDirectionWeight, downwardDirectionWeight));
		}
		return directions;
	}

	private static Vec3 buildDirection(RandomSource random, double upwardDirectionWeight, double downwardDirectionWeight) {
		boolean upward = chooseUpwardDirection(random, upwardDirectionWeight, downwardDirectionWeight);
		for (int attempt = 0; attempt < 8; attempt++) {
			double x = sampleRange(random, -1.0D, 1.0D);
			double y = upward
					? sampleRange(random, 0.02D, 0.82D)
					: sampleRange(random, -0.28D, -0.02D);
			double z = sampleRange(random, -1.0D, 1.0D);
			Vec3 raw = new Vec3(x, y, z);
			if (raw.lengthSqr() <= 1.0E-6D) {
				continue;
			}

			return raw.normalize();
		}

		return upward
				? new Vec3(0.0D, 1.0D, 0.0D)
				: new Vec3(0.0D, -1.0D, 0.0D);
	}

	private static boolean chooseUpwardDirection(RandomSource random, double upwardDirectionWeight, double downwardDirectionWeight) {
		double upward = Math.max(0.0D, upwardDirectionWeight);
		double downward = Math.max(0.0D, downwardDirectionWeight);
		double total = upward + downward;
		if (total <= 1.0E-9D) {
			return random.nextBoolean();
		}
		if (upward <= 1.0E-9D) {
			return false;
		}
		if (downward <= 1.0E-9D) {
			return true;
		}
		return random.nextDouble() * total < upward;
	}

	private static Vec3 applySmoothMotion(Entity entity, ActiveGravityState state) {
		if (entity == null || state == null) {
			return Vec3.ZERO;
		}

		return applySmoothMotion(
				entity,
				state.targetVelocity,
				state.velocityBlendFactor,
				state.allowPlayerControl,
				state.playerControlRatio,
				state.lastAppliedVelocity,
				state
		);
	}

	private static Vec3 applySmoothMotion(
			Entity entity,
			Vec3 targetVelocity,
			double velocityBlendFactor,
			boolean allowPlayerControl,
			double playerControlRatio
	) {
		return applySmoothMotion(entity, targetVelocity, velocityBlendFactor, allowPlayerControl, playerControlRatio, null, null);
	}

	private static Vec3 applySmoothMotion(
			Entity entity,
			Vec3 targetVelocity,
			double velocityBlendFactor,
			boolean allowPlayerControl,
			double playerControlRatio,
			Vec3 lastAppliedVelocity,
			ActiveGravityState state
	) {
		if (entity == null || targetVelocity == null) {
			return Vec3.ZERO;
		}

		Vec3 currentVelocity = entity.getDeltaMovement();
		Vec3 desiredVelocity = targetVelocity;
		if (allowPlayerControl && entity instanceof ServerPlayer) {
			Vec3 previousVelocity = lastAppliedVelocity == null ? targetVelocity : lastAppliedVelocity;
			Vec3 playerInfluence = currentVelocity.subtract(previousVelocity);
			double maxPlayerInfluence = targetVelocity.length() * clamp01(playerControlRatio);
			if (playerInfluence.lengthSqr() > (maxPlayerInfluence * maxPlayerInfluence) && maxPlayerInfluence > 1.0E-6D) {
				playerInfluence = playerInfluence.normalize().scale(maxPlayerInfluence);
			}
			desiredVelocity = targetVelocity.add(playerInfluence);
		}

		double blend = clamp01(velocityBlendFactor);
		Vec3 blendedVelocity = new Vec3(
				currentVelocity.x + ((desiredVelocity.x - currentVelocity.x) * blend),
				currentVelocity.y + ((desiredVelocity.y - currentVelocity.y) * blend),
				currentVelocity.z + ((desiredVelocity.z - currentVelocity.z) * blend)
		);
		entity.setDeltaMovement(blendedVelocity);
		entity.resetFallDistance();
		entity.hurtMarked = true;
		if (state != null) {
			state.lastAppliedVelocity = blendedVelocity;
		}
		return blendedVelocity;
	}

	private static Entity findEntity(MinecraftServer server, ResourceKey<Level> dimension, UUID entityUuid) {
		if (server == null || entityUuid == null) {
			return null;
		}

		if (dimension != null) {
			ServerLevel preferredLevel = server.getLevel(dimension);
			if (preferredLevel != null) {
				Entity found = preferredLevel.getEntity(entityUuid);
				if (found != null) {
					return found;
				}
			}
		}

		for (ServerLevel level : server.getAllLevels()) {
			Entity found = level.getEntity(entityUuid);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static ResourceKey<Level> getDimension(Entity entity) {
		if (entity == null || !(entity.level() instanceof ServerLevel serverLevel)) {
			return Level.OVERWORLD;
		}
		return serverLevel.dimension();
	}

	private static int pickWeightedDurationSeconds(
			RandomSource random,
			int minDurationSeconds,
			int maxDurationSeconds,
			double instabilityFactor
	) {
		double dynamicMaxDuration = interpolateDouble(minDurationSeconds, maxDurationSeconds, instabilityFactor);
		if (dynamicMaxDuration <= minDurationSeconds + 1.0E-9D) {
			return minDurationSeconds;
		}

		double weightedRoll = 1.0D - Math.pow(1.0D - random.nextDouble(), DURATION_PRIORITY);
		double duration = minDurationSeconds + (weightedRoll * (dynamicMaxDuration - minDurationSeconds));
		return Math.max(1, (int) Math.round(duration));
	}

	private static double interpolateDouble(double min, double max, double factor) {
		if (max <= min) {
			return min;
		}
		return min + ((max - min) * clamp01(factor));
	}

	private static double getRangeInstabilityFactor(double stabilityPercent, double minStabilityPercent, double maxStabilityPercent) {
		double range = maxStabilityPercent - minStabilityPercent;
		if (range <= 1.0E-9D) {
			return 1.0D;
		}

		double normalized = (stabilityPercent - minStabilityPercent) / range;
		return 1.0D - clamp01(normalized);
	}

	private static double sampleRange(RandomSource random, double min, double max) {
		if (max <= min) {
			return min;
		}
		return min + (random.nextDouble() * (max - min));
	}

	private static int sampleRangeInt(RandomSource random, int min, int max) {
		if (max <= min) {
			return min;
		}
		return min + random.nextInt(max - min + 1);
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	private static void shuffle(List<?> list, RandomSource random) {
		for (int i = list.size() - 1; i > 0; i--) {
			int swapWith = random.nextInt(i + 1);
			Collections.swap(list, i, swapWith);
		}
	}

	private static final class SelectedTarget {
		private final Entity entity;
		private final ResourceKey<Level> dimension;
		private final boolean item;

		private SelectedTarget(Entity entity, ResourceKey<Level> dimension, boolean item) {
			this.entity = entity;
			this.dimension = dimension;
			this.item = item;
		}
	}

	private static final class ActiveGravityState {
		private final UUID entityUuid;
		private ResourceKey<Level> dimension;
		private final Vec3 targetVelocity;
		private final double velocityBlendFactor;
		private final boolean allowPlayerControl;
		private final double playerControlRatio;
		private Vec3 lastAppliedVelocity;
		private final long endTick;
		private long nextApplyTick;

		private ActiveGravityState(
				UUID entityUuid,
				ResourceKey<Level> dimension,
				Vec3 targetVelocity,
				double velocityBlendFactor,
				boolean allowPlayerControl,
				double playerControlRatio,
				Vec3 lastAppliedVelocity,
				long endTick,
				long nextApplyTick
		) {
			this.entityUuid = entityUuid;
			this.dimension = dimension;
			this.targetVelocity = targetVelocity;
			this.velocityBlendFactor = velocityBlendFactor;
			this.allowPlayerControl = allowPlayerControl;
			this.playerControlRatio = playerControlRatio;
			this.lastAppliedVelocity = lastAppliedVelocity;
			this.endTick = endTick;
			this.nextApplyTick = nextApplyTick;
		}
	}
}
