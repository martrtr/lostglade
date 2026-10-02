package com.lostglade.server.glitch;

import com.lostglade.server.AccountAuthSystem;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonObject;
import com.lostglade.Lg2;
import com.lostglade.mixin.PlayerTrackedDataAccessor;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.ServerBackroomsSystem;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.lionarius.skinrestorer.SkinRestorer;
import net.lionarius.skinrestorer.skin.SkinValue;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PhantomMobGlitch implements ServerGlitchHandler {
	private static final String MIN_TARGET_PLAYERS = "minTargetPlayers";
	private static final String MAX_TARGET_PLAYERS = "maxTargetPlayers";
	private static final String MIN_SPAWN_DISTANCE = "minSpawnDistance";
	private static final String MAX_SPAWN_DISTANCE = "maxSpawnDistance";
	private static final String MIN_DURATION_SECONDS = "minDurationSeconds";
	private static final String MAX_DURATION_SECONDS = "maxDurationSeconds";
	private static final String MIN_CHASING_SPEED = "minChasingSpeed";
	private static final String MAX_CHASING_SPEED = "maxChasingSpeed";
	private static final String STANDING_WEIGHT = "standingWeight";
	private static final String CHASING_WEIGHT = "chasingWeight";
	private static final String WANDERING_WEIGHT = "wanderingWeight";
	private static final String PHANTOM_MOB_TAG = "lg2_phantom_mob";
	private static final double TARGET_COUNT_PRIORITY = 2.4D;
	private static final double DEFAULT_MIN_CHASING_SPEED = 0.34D;
	private static final double DEFAULT_MAX_CHASING_SPEED = 0.50D;
	private static final double WANDERING_SPEED = 0.09D;
	private static final double MOTION_BLEND_FACTOR = 0.34D;
	private static final double CONTACT_PADDING = 0.08D;
	private static final double PROJECTILE_CHECK_PADDING = 2.0D;
	private static final double MAX_FALL_SPEED = 0.8D;
	private static final double STEP_UP_JUMP_VELOCITY = 0.42D;
	private static final double STEP_UP_CLEARANCE = 1.05D;
	private static final double GROUND_CHECK_DISTANCE = 0.08D;
	private static final int MIN_WANDER_CHANGE_TICKS = 16;
	private static final int MAX_WANDER_CHANGE_TICKS = 48;
	private static final int MAX_MOB_SPAWN_ATTEMPTS = 10;
	private static final int MAX_POSITION_ATTEMPTS = 20;
	private static final List<Integer> SPAWN_Y_OFFSETS = List.of(2, 1, 0, -1, -2, -3, -4, -5, 3, 4);
	private static final Set<EntityType<?>> EXCLUDED_MOBS = Set.of(
			EntityType.ENDER_DRAGON,
			EntityType.WITHER,
			EntityType.ELDER_GUARDIAN,
			EntityType.WARDEN,
			EntityType.GIANT
	);
	private static final Map<UUID, ActivePhantomState> ACTIVE_STATES = new HashMap<>();
	private static List<EntityType<?>> mobPool;

	public static void tickActiveStates(MinecraftServer server) {
		if (server == null || ACTIVE_STATES.isEmpty()) {
			return;
		}

		long nowTick = server.overworld().getGameTime();
		Iterator<Map.Entry<UUID, ActivePhantomState>> iterator = ACTIVE_STATES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, ActivePhantomState> mapEntry = iterator.next();
			ActivePhantomState state = mapEntry.getValue();
			Entity rawEntity = findEntity(server, state.dimension, state.entityUuid);
			if (!(rawEntity instanceof Mob mob) || !isManagedPhantom(mob)) {
				iterator.remove();
				continue;
			}
			if (ServerBackroomsSystem.isInBackrooms(mob)) {
				discardEntityOnly(mob, true);
				iterator.remove();
				continue;
			}

			ServerPlayer targetPlayer = server.getPlayerList().getPlayer(state.targetPlayerUuid);
			if (targetPlayer == null
					|| !targetPlayer.isAlive()
					|| targetPlayer.isSpectator()
					|| ServerBackroomsSystem.isInBackrooms(targetPlayer)
					|| targetPlayer.level() != mob.level()) {
				discardEntityOnly(mob, true);
				iterator.remove();
				continue;
			}

			if (mob.level() instanceof ServerLevel level) {
				state.dimension = level.dimension();
			}
			if (hasPlayerAppearance(mob) && mob.isOnFire()) {
				mob.setRemainingFireTicks(0);
				mob.clearFire();
			}

			boolean touchedPlayer = touchesPlayer(mob);
			if (nowTick >= state.endTick || touchedPlayer || touchesProjectile(mob)) {
				discardEntityOnly(mob, true, touchedPlayer);
				iterator.remove();
				continue;
			}

			applyMovement(mob, targetPlayer, state, nowTick);
		}
	}

	public static void resetRuntimeState() {
		ACTIVE_STATES.clear();
	}

	public static void discardLingeringPhantoms(MinecraftServer server) {
		if (server == null) {
			return;
		}

		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity.getTags().contains(PHANTOM_MOB_TAG)) {
					entity.discard();
				}
			}
		}
	}

	public static void handleEntityLoad(Entity entity) {
		if (entity == null || !entity.getTags().contains(PHANTOM_MOB_TAG)) {
			return;
		}
		if (!ACTIVE_STATES.containsKey(entity.getUUID())) {
			entity.discard();
		}
	}

	public static boolean handleAttack(Entity entity) {
		if (!isManagedPhantom(entity)) {
			return false;
		}

		discardPhantom(entity, true, true, true);
		return true;
	}

	@Override
	public String id() {
		return "phantom_mobs";
	}

	@Override
	public GlitchConfig.GlitchEntry defaultEntry() {
		GlitchConfig.GlitchEntry entry = new GlitchConfig.GlitchEntry();
		entry.enabled = true;
		entry.minStabilityPercent = 0.0D;
		entry.maxStabilityPercent = 45.0D;
		entry.chancePerCheck = 0.12D;
		entry.stabilityInfluence = 1.0D;
		entry.minCooldownTicks = 160;
		entry.maxCooldownTicks = 2200;

		JsonObject settings = new JsonObject();
		settings.addProperty(MIN_TARGET_PLAYERS, 1);
		settings.addProperty(MAX_TARGET_PLAYERS, 3);
		settings.addProperty(MIN_SPAWN_DISTANCE, 4.0D);
		settings.addProperty(MAX_SPAWN_DISTANCE, 14.0D);
		settings.addProperty(MIN_DURATION_SECONDS, 5);
		settings.addProperty(MAX_DURATION_SECONDS, 14);
		settings.addProperty(MIN_CHASING_SPEED, DEFAULT_MIN_CHASING_SPEED);
		settings.addProperty(MAX_CHASING_SPEED, DEFAULT_MAX_CHASING_SPEED);
		settings.addProperty(STANDING_WEIGHT, 1.0D);
		settings.addProperty(CHASING_WEIGHT, 1.0D);
		settings.addProperty(WANDERING_WEIGHT, 1.0D);
		entry.settings = settings;
		return entry;
	}

	@Override
	public boolean sanitizeSettings(GlitchConfig.GlitchEntry entry) {
		if (entry.settings == null) {
			entry.settings = new JsonObject();
		}

		boolean changed = false;
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MIN_TARGET_PLAYERS, 1, 1, 100);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_TARGET_PLAYERS, 3, 1, 100);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, MIN_SPAWN_DISTANCE, 4.0D, 0.5D, 128.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, MAX_SPAWN_DISTANCE, 14.0D, 0.5D, 128.0D);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MIN_DURATION_SECONDS, 5, 1, 3600);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_DURATION_SECONDS, 14, 1, 3600);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, MIN_CHASING_SPEED, DEFAULT_MIN_CHASING_SPEED, 0.01D, 8.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, MAX_CHASING_SPEED, DEFAULT_MAX_CHASING_SPEED, 0.01D, 8.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, STANDING_WEIGHT, 1.0D, 0.0D, 1000.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, CHASING_WEIGHT, 1.0D, 0.0D, 1000.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, WANDERING_WEIGHT, 1.0D, 0.0D, 1000.0D);
		changed |= entry.settings.remove("mobModelWeight") != null;
		changed |= entry.settings.remove("playerModelWeight") != null;

		int minTargets = GlitchSettingsHelper.getInt(entry.settings, MIN_TARGET_PLAYERS, 1);
		int maxTargets = Math.max(1, GlitchSettingsHelper.getInt(entry.settings, MAX_TARGET_PLAYERS, 3));
		boolean minTargetExpression = GlitchSettingsHelper.isExpression(entry.settings, MIN_TARGET_PLAYERS);
		boolean maxTargetExpression = GlitchSettingsHelper.isExpression(entry.settings, MAX_TARGET_PLAYERS);
		if (!minTargetExpression && !maxTargetExpression && maxTargets < minTargets) {
			entry.settings.addProperty(MAX_TARGET_PLAYERS, minTargets);
			changed = true;
		}

		double minDistance = GlitchSettingsHelper.getDouble(entry.settings, MIN_SPAWN_DISTANCE, 4.0D);
		double maxDistance = GlitchSettingsHelper.getDouble(entry.settings, MAX_SPAWN_DISTANCE, 14.0D);
		if (maxDistance < minDistance) {
			entry.settings.addProperty(MAX_SPAWN_DISTANCE, minDistance);
			changed = true;
		}

		int minDuration = GlitchSettingsHelper.getInt(entry.settings, MIN_DURATION_SECONDS, 5);
		int maxDuration = GlitchSettingsHelper.getInt(entry.settings, MAX_DURATION_SECONDS, 14);
		if (maxDuration < minDuration) {
			entry.settings.addProperty(MAX_DURATION_SECONDS, minDuration);
			changed = true;
		}

		double minChasingSpeed = GlitchSettingsHelper.getDouble(entry.settings, MIN_CHASING_SPEED, DEFAULT_MIN_CHASING_SPEED);
		double maxChasingSpeed = GlitchSettingsHelper.getDouble(entry.settings, MAX_CHASING_SPEED, DEFAULT_MAX_CHASING_SPEED);
		if (maxChasingSpeed < minChasingSpeed) {
			entry.settings.addProperty(MAX_CHASING_SPEED, minChasingSpeed);
			changed = true;
		}

		return changed;
	}

	@Override
	public boolean trigger(MinecraftServer server, RandomSource random, GlitchConfig.GlitchEntry entry, double stabilityPercent) {
		tickActiveStates(server);
		List<ServerPlayer> players = collectEligiblePlayers(server);
		if (players.isEmpty()) {
			return false;
		}

		JsonObject settings = entry.settings == null ? new JsonObject() : entry.settings;
		double instability = getRangeInstabilityFactor(stabilityPercent, entry.minStabilityPercent, entry.maxStabilityPercent);
		int minTargets = GlitchSettingsHelper.getInt(settings, MIN_TARGET_PLAYERS, 1);
		int maxTargets = Math.max(1, GlitchSettingsHelper.getInt(settings, MAX_TARGET_PLAYERS, 3));
		int targetCount = pickWeightedTargetCount(random, minTargets, maxTargets, instability);
		targetCount = Math.max(1, Math.min(players.size(), targetCount));

		shuffle(players, random);
		long nowTick = server.overworld().getGameTime();
		boolean spawnedAny = false;
		for (int i = 0; i < targetCount; i++) {
			if (spawnForPlayer(players.get(i), random, settings, nowTick)) {
				spawnedAny = true;
			}
		}

		return spawnedAny;
	}

	private static boolean spawnForPlayer(ServerPlayer player, RandomSource random, JsonObject settings, long nowTick) {
		return spawnForPlayer(player, random, settings, nowTick, null, null);
	}

	private static boolean spawnForPlayer(
			ServerPlayer player,
			RandomSource random,
			JsonObject settings,
			long nowTick,
			Boolean forcePlayerAppearance,
			ServerPlayer forcedSkinSource
	) {
		if (player == null || !(player.level() instanceof ServerLevel level)) {
			return false;
		}

		List<EntityType<?>> pool = getMobPool(level);
		if (pool.isEmpty()) {
			return false;
		}

		double minSpawnDistance = GlitchSettingsHelper.getDouble(settings, MIN_SPAWN_DISTANCE, 4.0D);
		double maxSpawnDistance = GlitchSettingsHelper.getDouble(settings, MAX_SPAWN_DISTANCE, 14.0D);
		int minDurationSeconds = GlitchSettingsHelper.getInt(settings, MIN_DURATION_SECONDS, 5);
		int maxDurationSeconds = GlitchSettingsHelper.getInt(settings, MAX_DURATION_SECONDS, 14);
		int durationSeconds = sampleRangeInt(random, minDurationSeconds, maxDurationSeconds);
		long durationTicks = Math.max(20L, durationSeconds * 20L);
		MovementType movementType = pickMovementType(random, settings);
		MinecraftServer server = level.getServer();
		ServerPlayer skinSource = forcedSkinSource != null ? forcedSkinSource : pickRandomSkinSource(server, random);
		boolean usePlayerAppearance;
		if (forcePlayerAppearance != null) {
			usePlayerAppearance = forcePlayerAppearance && skinSource != null;
		} else {
			usePlayerAppearance = skinSource != null && random.nextInt(pool.size() + 1) == pool.size();
		}
		EntityType<?> selectedMobType = usePlayerAppearance ? null : pool.get(random.nextInt(pool.size()));
		double minChasingSpeed = GlitchSettingsHelper.getDouble(settings, MIN_CHASING_SPEED, DEFAULT_MIN_CHASING_SPEED);
		double maxChasingSpeed = GlitchSettingsHelper.getDouble(settings, MAX_CHASING_SPEED, DEFAULT_MAX_CHASING_SPEED);
		double chasingSpeed = sampleRange(random, minChasingSpeed, maxChasingSpeed);

		for (int attempt = 0; attempt < MAX_MOB_SPAWN_ATTEMPTS; attempt++) {
			Mob mob = usePlayerAppearance
					? createPlayerAppearanceBaseMob(level)
					: createMobFromType(level, selectedMobType);
			if (mob == null) {
				continue;
			}

			configureMob(mob);
			Vec3 spawnPos = findSpawnPosition(level, player, mob, random, minSpawnDistance, maxSpawnDistance);
			if (spawnPos == null) {
				mob.discard();
				continue;
			}

			mob.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
			mob.setYRot(random.nextFloat() * 360.0F);
			if (!level.noCollision(mob) || level.containsAnyLiquid(mob.getBoundingBox())) {
				mob.discard();
				continue;
			}

			if (usePlayerAppearance && skinSource != null) {
				GameProfile profile = createPhantomPlayerProfile(skinSource, mob.getUUID());
				PolymerEntityUtils.setPolymerEntity(mob, new PhantomPlayerOverlay(profile));
			}

			ActivePhantomState state = new ActivePhantomState(
					mob.getUUID(),
					level.dimension(),
					player.getUUID(),
					movementType,
					nowTick + durationTicks,
					nowTick + sampleRangeInt(random, MIN_WANDER_CHANGE_TICKS, MAX_WANDER_CHANGE_TICKS),
					randomHorizontalDirection(random),
					Vec3.ZERO,
					chasingSpeed
			);
			ACTIVE_STATES.put(mob.getUUID(), state);

			if (!level.addFreshEntity(mob)) {
				ACTIVE_STATES.remove(mob.getUUID());
				mob.discard();
				continue;
			}

			applyMovement(mob, player, state, nowTick);
			return true;
		}

		return false;
	}

	private static List<EntityType<?>> getMobPool(ServerLevel level) {
		if (mobPool != null) {
			return mobPool;
		}

		List<EntityType<?>> collected = new ArrayList<>();
		for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
			if (EXCLUDED_MOBS.contains(entityType)) {
				continue;
			}

			Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
			if (id == null || !"minecraft".equals(id.getNamespace())) {
				continue;
			}

			Entity entity = entityType.create(level, EntitySpawnReason.TRIGGERED);
			if (entity instanceof Mob mob) {
				collected.add(entityType);
				mob.discard();
				continue;
			}
			if (entity != null) {
				entity.discard();
			}
		}

		mobPool = collected;
		return mobPool;
	}

	private static Mob createPlayerAppearanceBaseMob(ServerLevel level) {
		Entity entity = EntityType.HUSK.create(level, EntitySpawnReason.TRIGGERED);
		return entity instanceof Mob mob ? mob : null;
	}

	private static Mob createMobFromType(ServerLevel level, EntityType<?> entityType) {
		if (entityType == null) {
			return null;
		}

		Entity entity = entityType.create(level, EntitySpawnReason.TRIGGERED);
		if (entity instanceof Mob mob) {
			return mob;
		}
		if (entity != null) {
			entity.discard();
		}
		return null;
	}

	private static void configureMob(Mob mob) {
		mob.setNoAi(true);
		mob.setSilent(true);
		mob.setInvulnerable(true);
		mob.setCanPickUpLoot(false);
		mob.setTarget(null);
		mob.addTag(PHANTOM_MOB_TAG);
	}

	private static ServerPlayer pickRandomSkinSource(MinecraftServer server, RandomSource random) {
		if (server == null) {
			return null;
		}

		List<ServerPlayer> candidates = new ArrayList<>();
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			if (player == null || !player.isAlive() || player.isSpectator() || ServerBackroomsSystem.isInBackrooms(player)) {
				continue;
			}
			candidates.add(player);
		}

		if (candidates.isEmpty()) {
			return null;
		}
		if (random == null) {
			return candidates.getFirst();
		}
		return candidates.get(random.nextInt(candidates.size()));
	}

	private static GameProfile createPhantomPlayerProfile(ServerPlayer sourcePlayer, UUID fakeProfileId) {
		GameProfile sourceProfile = sourcePlayer.getGameProfile();
		String safeName = buildPhantomProfileName(fakeProfileId);
		PropertyMap properties = sourceProfile != null
				? new PropertyMap(ImmutableMultimap.copyOf(sourceProfile.properties()))
				: new PropertyMap(ImmutableMultimap.of());
		applySkinRestorerSkin(sourcePlayer, properties);
		return new GameProfile(fakeProfileId, safeName, properties);
	}

	private static void applySkinRestorerSkin(ServerPlayer sourcePlayer, PropertyMap properties) {
		if (sourcePlayer == null || properties == null) {
			return;
		}

		try {
			SkinValue skinValue = SkinRestorer.getSkinStorage().getSkin(sourcePlayer.getUUID());
			if (skinValue == null) {
				return;
			}

			Property textures = skinValue.value();
			if (textures == null) {
				return;
			}

			properties.removeAll("textures");
			properties.put("textures", textures);
		} catch (Exception exception) {
			Lg2.LOGGER.debug("Failed to resolve SkinRestorer skin for {}", sourcePlayer.getScoreboardName(), exception);
		}
	}

	private static String buildPhantomProfileName(UUID fakeProfileId) {
		String compact = fakeProfileId == null ? UUID.randomUUID().toString().replace("-", "") : fakeProfileId.toString().replace("-", "");
		return "ph" + compact.substring(0, 14);
	}

	private static String buildPhantomTeamName(UUID profileId) {
		String compact = profileId == null ? UUID.randomUUID().toString().replace("-", "") : profileId.toString().replace("-", "");
		return "lg2pm_" + compact.substring(0, 10);
	}

	private static Vec3 findSpawnPosition(
			ServerLevel level,
			ServerPlayer player,
			Mob mob,
			RandomSource random,
			double minDistance,
			double maxDistance
	) {
		int minBuildY = level.getMinY() + 1;
		int baseY = (int) Math.floor(player.getY());

		for (int attempt = 0; attempt < MAX_POSITION_ATTEMPTS; attempt++) {
			double angle = sampleRange(random, 0.0D, Math.PI * 2.0D);
			double distance = sampleRange(random, minDistance, maxDistance);
			double x = player.getX() + (Math.cos(angle) * distance);
			double z = player.getZ() + (Math.sin(angle) * distance);

			for (int yOffset : SPAWN_Y_OFFSETS) {
				int blockY = Math.max(minBuildY, baseY + yOffset);
				Double supportTopY = getSupportTopY(level, mob, BlockPos.containing(x, blockY - 1, z));
				if (supportTopY == null) {
					continue;
				}
				double y = supportTopY;
				mob.setPos(x, y, z);
				if (!level.noCollision(mob) || level.containsAnyLiquid(mob.getBoundingBox())) {
					continue;
				}
				return new Vec3(x, y, z);
			}
		}

		return null;
	}

	private static Double getSupportTopY(ServerLevel level, Entity entity, BlockPos supportPos) {
		BlockState groundState = level.getBlockState(supportPos);
		CollisionContext context = entity == null ? CollisionContext.empty() : CollisionContext.of(entity);
		VoxelShape shape = groundState.getCollisionShape(level, supportPos, context);
		if (shape.isEmpty()) {
			return null;
		}

		double top = shape.max(Direction.Axis.Y);
		if (top <= 1.0E-6D) {
			return null;
		}

		return supportPos.getY() + top;
	}

	private static void applyMovement(Mob mob, ServerPlayer targetPlayer, ActivePhantomState state, long nowTick) {
		if (mob == null || targetPlayer == null || state == null) {
			return;
		}

		if (state.movementType == MovementType.WANDERING && nowTick >= state.nextWanderChangeTick) {
			state.wanderDirection = randomHorizontalDirection(mob.getRandom());
			state.nextWanderChangeTick = nowTick + sampleRangeInt(mob.getRandom(), MIN_WANDER_CHANGE_TICKS, MAX_WANDER_CHANGE_TICKS);
		}

		Vec3 desiredHorizontal = switch (state.movementType) {
			case STANDING -> Vec3.ZERO;
			case CHASING -> getChasingVelocity(mob, targetPlayer, state.chasingSpeed);
			case WANDERING -> state.wanderDirection.scale(WANDERING_SPEED);
		};

		double nextX = state.currentMotion.x + ((desiredHorizontal.x - state.currentMotion.x) * MOTION_BLEND_FACTOR);
		double nextZ = state.currentMotion.z + ((desiredHorizontal.z - state.currentMotion.z) * MOTION_BLEND_FACTOR);
		double currentVerticalMotion = mob.getDeltaMovement().y;
		double gravity = mob.isNoGravity() ? 0.0D : mob.getGravity();
		double nextVerticalMotion = mob.onGround() && currentVerticalMotion >= 0.0D
				? 0.0D
				: Math.max(currentVerticalMotion - gravity, -MAX_FALL_SPEED);
		if (shouldAttemptStepUp(mob, new Vec3(nextX, 0.0D, nextZ))) {
			nextVerticalMotion = Math.max(nextVerticalMotion, STEP_UP_JUMP_VELOCITY);
		}
		Vec3 requestedMotion = new Vec3(nextX, nextVerticalMotion, nextZ);
		Vec3 appliedMotion = moveWithPhysics(mob, requestedMotion);
		state.currentMotion = new Vec3(appliedMotion.x, 0.0D, appliedMotion.z);
		mob.setDeltaMovement(appliedMotion.x, mob.onGround() ? 0.0D : appliedMotion.y, appliedMotion.z);
		mob.resetFallDistance();
		mob.hurtMarked = true;

		if (desiredHorizontal.lengthSqr() > 1.0E-6D) {
			float yaw = (float) Math.toDegrees(Math.atan2(desiredHorizontal.z, desiredHorizontal.x)) - 90.0F;
			mob.setYRot(yaw);
			mob.setYBodyRot(yaw);
			mob.setYHeadRot(yaw);
		}
	}

	private static Vec3 moveWithPhysics(Mob mob, Vec3 motion) {
		if (mob == null || motion.lengthSqr() <= 1.0E-8D) {
			return Vec3.ZERO;
		}

		Vec3 startPos = mob.position();
		mob.move(MoverType.SELF, motion);
		return mob.position().subtract(startPos);
	}

	private static boolean shouldAttemptStepUp(Mob mob, Vec3 horizontalMotion) {
		if (mob == null || horizontalMotion.horizontalDistanceSqr() <= 1.0E-8D) {
			return false;
		}
		if (!(mob.onGround() || isNearGround(mob))) {
			return false;
		}

		AABB boundingBox = mob.getBoundingBox();
		Level level = mob.level();
		AABB horizontalBox = boundingBox.move(horizontalMotion.x, 0.0D, horizontalMotion.z);
		if (level.noCollision(mob, horizontalBox)) {
			return false;
		}

		AABB steppedBox = boundingBox.move(horizontalMotion.x, STEP_UP_CLEARANCE, horizontalMotion.z);
		return level.noCollision(mob, steppedBox);
	}

	private static boolean isNearGround(Mob mob) {
		if (mob == null) {
			return false;
		}
		return !mob.level().noCollision(mob, mob.getBoundingBox().move(0.0D, -GROUND_CHECK_DISTANCE, 0.0D));
	}

	private static Vec3 getChasingVelocity(Mob mob, ServerPlayer targetPlayer, double chasingSpeed) {
		Vec3 delta = targetPlayer.position().subtract(mob.position());
		Vec3 horizontal = new Vec3(delta.x, 0.0D, delta.z);
		if (horizontal.lengthSqr() <= 1.0E-6D) {
			return Vec3.ZERO;
		}
		return horizontal.normalize().scale(chasingSpeed);
	}

	private static boolean touchesPlayer(Mob mob) {
		if (!(mob.level() instanceof ServerLevel level)) {
			return false;
		}

		AABB phantomBox = mob.getBoundingBox().inflate(CONTACT_PADDING);
		for (ServerPlayer player : level.players()) {
			if (!player.isAlive() || player.isSpectator()) {
				continue;
			}
			if (player.getBoundingBox().intersects(phantomBox)) {
				return true;
			}
		}
		return false;
	}

	private static boolean touchesProjectile(Mob mob) {
		if (!(mob.level() instanceof ServerLevel level)) {
			return false;
		}

		AABB phantomBox = mob.getBoundingBox().inflate(CONTACT_PADDING);
		List<Projectile> projectiles = level.getEntitiesOfClass(
				Projectile.class,
				phantomBox.inflate(PROJECTILE_CHECK_PADDING),
				projectile -> projectile != null && projectile.isAlive() && !projectile.isRemoved()
		);
		for (Projectile projectile : projectiles) {
			AABB projectilePath = projectile.getBoundingBox().expandTowards(projectile.getDeltaMovement()).inflate(0.15D);
			if (projectilePath.intersects(phantomBox)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isManagedPhantom(Entity entity) {
		return entity != null
				&& entity.isAlive()
				&& (ACTIVE_STATES.containsKey(entity.getUUID()) || entity.getTags().contains(PHANTOM_MOB_TAG));
	}

	private static void discardEntityOnly(Entity entity, boolean particles) {
		discardEntityOnly(entity, particles, false);
	}

	private static void discardEntityOnly(Entity entity, boolean particles, boolean playDespawnSound) {
		discardPhantom(entity, false, particles, playDespawnSound);
	}

	private static void discardPhantom(Entity entity, boolean removeState, boolean particles) {
		discardPhantom(entity, removeState, particles, false);
	}

	private static void discardPhantom(Entity entity, boolean removeState, boolean particles, boolean playDespawnSound) {
		if (entity == null) {
			return;
		}

		if (removeState) {
			ACTIVE_STATES.remove(entity.getUUID());
		}
		if (entity.level() instanceof ServerLevel level && hasPlayerAppearance(entity)) {
			sendPlayerAppearanceRemoval(level, entity.getUUID());
		}
		if (entity.level() instanceof ServerLevel level && entity.isAlive() && !entity.isRemoved()) {
			if (particles) {
				spawnDespawnParticles(level, entity);
			}
			if (playDespawnSound) {
				playContactDespawnSound(level, entity);
			}
		}
		if (!entity.isRemoved()) {
			entity.discard();
		}
	}

	private static void spawnDespawnParticles(ServerLevel level, Entity entity) {
		double centerX = entity.getX();
		double centerY = entity.getY() + (entity.getBbHeight() * 0.5D);
		double centerZ = entity.getZ();
		double spreadX = Math.max(0.15D, entity.getBbWidth() * 0.35D);
		double spreadY = Math.max(0.15D, entity.getBbHeight() * 0.25D);
		double spreadZ = Math.max(0.15D, entity.getBbWidth() * 0.35D);

		level.sendParticles(ParticleTypes.SMOKE, centerX, centerY, centerZ, 18, spreadX, spreadY, spreadZ, 0.01D);
		level.sendParticles(ParticleTypes.ASH, centerX, centerY, centerZ, 12, spreadX, spreadY, spreadZ, 0.01D);
	}

	private static void playContactDespawnSound(ServerLevel level, Entity entity) {
		level.playSound(
				null,
				entity.getX(),
				entity.getY() + (entity.getBbHeight() * 0.5D),
				entity.getZ(),
				SoundEvents.FIRE_EXTINGUISH,
				SoundSource.HOSTILE,
				0.55F,
				1.15F
		);
	}

	private static boolean hasPlayerAppearance(Entity entity) {
		return PolymerEntity.get(entity) instanceof PhantomPlayerOverlay;
	}

	private static void sendPlayerAppearanceRemoval(ServerLevel level, UUID profileId) {
		if (level == null || profileId == null) {
			return;
		}

		PlayerTeam team = createPhantomNameHiddenTeam(profileId, buildPhantomProfileName(profileId));
		ClientboundSetPlayerTeamPacket teamPacket = ClientboundSetPlayerTeamPacket.createRemovePacket(team);
		ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(List.of(profileId));
		for (ServerPlayer player : level.players()) {
			player.connection.send(teamPacket);
			player.connection.send(packet);
		}
	}

	private static PlayerTeam createPhantomNameHiddenTeam(UUID profileId, String profileName) {
		PlayerTeam team = new PlayerTeam(new Scoreboard(), buildPhantomTeamName(profileId));
		team.setDisplayName(Component.empty());
		team.setPlayerPrefix(Component.empty());
		team.setPlayerSuffix(Component.empty());
		team.setNameTagVisibility(Team.Visibility.NEVER);
		team.setDeathMessageVisibility(Team.Visibility.NEVER);
		team.setCollisionRule(Team.CollisionRule.NEVER);
		team.getPlayers().add(profileName);
		return team;
	}

	private static MovementType pickMovementType(RandomSource random, JsonObject settings) {
		double standingWeight = Math.max(0.0D, GlitchSettingsHelper.getDouble(settings, STANDING_WEIGHT, 1.0D));
		double chasingWeight = Math.max(0.0D, GlitchSettingsHelper.getDouble(settings, CHASING_WEIGHT, 1.0D));
		double wanderingWeight = Math.max(0.0D, GlitchSettingsHelper.getDouble(settings, WANDERING_WEIGHT, 1.0D));
		double totalWeight = standingWeight + chasingWeight + wanderingWeight;
		if (totalWeight <= 1.0E-9D) {
			return MovementType.values()[random.nextInt(MovementType.values().length)];
		}

		double roll = random.nextDouble() * totalWeight;
		if (roll < standingWeight) {
			return MovementType.STANDING;
		}
		roll -= standingWeight;
		if (roll < chasingWeight) {
			return MovementType.CHASING;
		}
		return MovementType.WANDERING;
	}

	private static List<ServerPlayer> collectEligiblePlayers(MinecraftServer server) {
		List<ServerPlayer> players = new ArrayList<>();
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			if (player.isSpectator() || !player.isAlive() || ServerBackroomsSystem.isInBackrooms(player)) {
				continue;
			}
			players.add(player);
		}
		return players;
	}

	private static int pickWeightedTargetCount(RandomSource random, int minTargets, int maxTargets, double instabilityFactor) {
		int safeMin = Math.max(1, minTargets);
		int safeMax = Math.max(safeMin, maxTargets);
		int dynamicMax = interpolateInt(safeMin, safeMax, instabilityFactor);
		if (dynamicMax <= safeMin) {
			return safeMin;
		}

		double weightedRoll = 1.0D - Math.pow(1.0D - random.nextDouble(), TARGET_COUNT_PRIORITY);
		double sampled = safeMin + (weightedRoll * (dynamicMax - safeMin));
		return Math.max(safeMin, Math.min(dynamicMax, (int) Math.round(sampled)));
	}

	private static Vec3 randomHorizontalDirection(RandomSource random) {
		for (int attempt = 0; attempt < 8; attempt++) {
			double x = sampleRange(random, -1.0D, 1.0D);
			double z = sampleRange(random, -1.0D, 1.0D);
			Vec3 direction = new Vec3(x, 0.0D, z);
			if (direction.lengthSqr() > 1.0E-6D) {
				return direction.normalize();
			}
		}
		return new Vec3(1.0D, 0.0D, 0.0D);
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

	private static int interpolateInt(int min, int max, double factor) {
		if (max <= min) {
			return min;
		}
		double clamped = Math.max(0.0D, Math.min(1.0D, factor));
		return min + (int) Math.round((max - min) * clamped);
	}

	private static double getRangeInstabilityFactor(double stabilityPercent, double minStabilityPercent, double maxStabilityPercent) {
		double range = maxStabilityPercent - minStabilityPercent;
		if (range <= 1.0E-9D) {
			return 1.0D;
		}

		double normalized = (stabilityPercent - minStabilityPercent) / range;
		return 1.0D - Math.max(0.0D, Math.min(1.0D, normalized));
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

	private static void shuffle(List<?> list, RandomSource random) {
		for (int i = list.size() - 1; i > 0; i--) {
			int swapWith = random.nextInt(i + 1);
			Collections.swap(list, i, swapWith);
		}
	}

	private enum MovementType {
		STANDING,
		CHASING,
		WANDERING
	}

	private static final class ActivePhantomState {
		private final UUID entityUuid;
		private ResourceKey<Level> dimension;
		private final UUID targetPlayerUuid;
		private final MovementType movementType;
		private final long endTick;
		private long nextWanderChangeTick;
		private Vec3 wanderDirection;
		private Vec3 currentMotion;
		private final double chasingSpeed;

		private ActivePhantomState(
				UUID entityUuid,
				ResourceKey<Level> dimension,
				UUID targetPlayerUuid,
				MovementType movementType,
				long endTick,
				long nextWanderChangeTick,
				Vec3 wanderDirection,
				Vec3 currentMotion,
				double chasingSpeed
		) {
			this.entityUuid = entityUuid;
			this.dimension = dimension;
			this.targetPlayerUuid = targetPlayerUuid;
			this.movementType = movementType;
			this.endTick = endTick;
			this.nextWanderChangeTick = nextWanderChangeTick;
			this.wanderDirection = wanderDirection;
			this.currentMotion = currentMotion;
			this.chasingSpeed = chasingSpeed;
		}
	}

	private static final class PhantomPlayerOverlay implements PolymerEntity {
		private static final byte ALL_PLAYER_SKIN_PARTS = (byte) 0x7F;
		private final GameProfile profile;

		private PhantomPlayerOverlay(GameProfile profile) {
			this.profile = profile;
		}

		@Override
		public EntityType<?> getPolymerEntityType(PacketContext context) {
			return EntityType.PLAYER;
		}

		@Override
		public void onBeforeSpawnPacket(ServerPlayer player, java.util.function.Consumer<Packet<?>> packetConsumer) {
			packetConsumer.accept(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(
					createPhantomNameHiddenTeam(this.profile.id(), this.profile.name()),
					true
			));
			EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
					ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
					ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
					ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
					ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
					ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
					ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER,
					ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT
			);
			ClientboundPlayerInfoUpdatePacket packet = PolymerEntityUtils.createMutablePlayerListPacket(actions);
			ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
					this.profile.id(),
					this.profile,
					false,
					0,
					GameType.SURVIVAL,
					null,
					true,
					0,
					(RemoteChatSession.Data) null
			);
			packet.entries().add(entry);
			packetConsumer.accept(packet);
		}

		@Override
		public void modifyRawTrackedData(List<SynchedEntityData.DataValue<?>> data, ServerPlayer player, boolean initial) {
			upsertTrackedData(data, SynchedEntityData.DataValue.create(PlayerTrackedDataAccessor.lg2$getDataPlayerMainHand(), HumanoidArm.RIGHT));
			upsertTrackedData(data, SynchedEntityData.DataValue.create(PlayerTrackedDataAccessor.lg2$getDataPlayerModeCustomisation(), ALL_PLAYER_SKIN_PARTS));
		}

		private static void upsertTrackedData(List<SynchedEntityData.DataValue<?>> data, SynchedEntityData.DataValue<?> replacement) {
			for (int i = 0; i < data.size(); i++) {
				SynchedEntityData.DataValue<?> current = data.get(i);
				if (current.id() == replacement.id()) {
					data.set(i, replacement);
					return;
				}
			}
			data.add(replacement);
		}
	}
}
