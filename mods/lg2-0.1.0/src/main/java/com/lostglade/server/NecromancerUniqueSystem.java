package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import com.lostglade.config.RaceConfig.RaceAbilitySlot;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class NecromancerUniqueSystem {
	private static final String RACE_ID = "necromancer";
	private static final String SPIRIT_TAG = "lg2.necromancer_spirit";
	private static final String AIR_CLICK_TRIGGER_TAG = "lg2.necromancer_spirit_air_click";
	private static final String STATE_FILE_NAME = "lg2-necromancer-spirit.json";
	private static final double DEFAULT_SUMMON_MANA_COST = 250.0D;
	private static final double DEFAULT_TOGGLE_MANA_COST = 5.0D;
	private static final double INNER_FOLLOW_RADIUS = 1.35D;
	private static final double OUTER_FOLLOW_RADIUS = 3.75D;
	private static final double EMERGENCY_TELEPORT_DISTANCE = 96.0D;
	private static final double TRAVEL_SPEED = 1.0D;
	private static final double WANDER_SPEED = 0.1D;
	private static final double DELIVERY_ACCELERATION = 0.065D;
	private static final double WANDER_ACCELERATION = 0.02D;
	private static final long WANDER_PAUSE_MIN_TICKS = 20L;
	private static final long WANDER_PAUSE_RANDOM_TICKS = 30L;
	private static final long WANDER_MAX_OUTSIDE_TICKS = 100L;
	private static final double ITEM_LOOK_DISTANCE = 256.0D;
	private static final double ITEM_AIM_BASE_TOLERANCE = 0.55D;
	private static final double ITEM_AIM_DISTANCE_TOLERANCE = 0.012D;
	private static final double AIR_CLICK_TRIGGER_FORWARD_OFFSET = 0.22D;
	private static final float AIR_CLICK_TRIGGER_WIDTH = 1.8F;
	private static final float AIR_CLICK_TRIGGER_HEIGHT = 1.8F;
	private static final int SPIRIT_FLIGHT_CHUNK_RADIUS = 0;
	private static final int SPIRIT_FLIGHT_TICKET_FLAGS =
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE;
	private static final DustParticleOptions NECRO_DUST = new DustParticleOptions(0x32113F, 0.85F);
	private static final DustParticleOptions SOUL_DUST = new DustParticleOptions(0x42BFA8, 0.62F);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, SpiritState> STATES = new LinkedHashMap<>();
	private static final Map<UUID, RuntimeSpirit> RUNTIME = new HashMap<>();
	private static final Map<UUID, Long> LAST_RIGHT_CLICK_TICKS = new HashMap<>();
	private static final Map<UUID, Interaction> AIR_CLICK_TRIGGERS = new HashMap<>();
	private static boolean dirty;

	private NecromancerUniqueSystem() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(NecromancerUniqueSystem::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(NecromancerUniqueSystem::shutdown);
		ServerTickEvents.END_SERVER_TICK.register(NecromancerUniqueSystem::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				onDisconnect(handler.player, server));
		ServerLivingEntityEvents.AFTER_DEATH.register(NecromancerUniqueSystem::onLivingDeath);
	}

	public static int use(ServerPlayer player, RaceAbilityConfig ability) {
		if (player == null || ability == null || !player.isAlive() || player.isSpectator()
				|| !(player.level() instanceof ServerLevel)) return 0;

		SpiritState state = STATES.computeIfAbsent(player.getUUID(), ignored -> new SpiritState());
		if (!state.summoned) {
			double cost = configuredCost(ability.necromancerUniqueSummonManaCost, DEFAULT_SUMMON_MANA_COST);
			if (!NecromancerStockSystem.trySpendMana(player, cost)) return 0;
			state.summoned = true;
			state.hidden = false;
			markDirty();
			ensureRuntime(player, true);
			return 1;
		}

		double cost = configuredCost(ability.necromancerUniqueToggleManaCost, DEFAULT_TOGGLE_MANA_COST);
		if (!NecromancerStockSystem.trySpendMana(player, cost)) return 0;
		state.hidden = !state.hidden;
		markDirty();
		RuntimeSpirit runtime = ensureRuntime(player, false);
		if (runtime != null) {
			keepSpiritSafe(runtime.spirit, runtime.carriedItem, state.hidden, runtime.mode != SpiritMode.WANDERING);
		}
		player.displayClientMessage(
				Component.literal(state.hidden ? "Дух скрыт" : "Дух показан")
						.withStyle(state.hidden ? ChatFormatting.GRAY : ChatFormatting.DARK_AQUA),
				true
		);
		return 1;
	}

	public static void onRightClick(ServerPlayer player) {
		if (player == null || !canUseSpirit(player) || !(player.level() instanceof ServerLevel level)) return;
		SpiritState state = STATES.get(player.getUUID());
		if (state == null || !state.summoned) return;

		long now = level.getGameTime();
		Long previousClick = LAST_RIGHT_CLICK_TICKS.put(player.getUUID(), now);
		if (previousClick != null && previousClick == now) return;
		RuntimeSpirit runtime = ensureRuntime(player, false);
		if (runtime == null) return;

		ItemEntity target = findLookedAtItem(player, level);
		if (target == null) return;
		UUID targetId = target.getUUID();
		if (targetId.equals(runtime.targetItemId)
				|| runtime.deliveryQueue.stream().anyMatch(queued -> targetId.equals(queued.itemId()))) return;
		runtime.deliveryQueue.addLast(new DeliveryTarget(targetId, target.chunkPosition()));
		if ((runtime.mode == SpiritMode.WANDERING || runtime.mode == SpiritMode.RECENTERING) && runtime.carriedItem.isEmpty()) {
			startNextQueuedTarget(level, runtime);
		}
	}

	public static void handleMovePacket(ServerPlayer player) {
		if (player == null) return;
		syncAirClickTrigger(player, RUNTIME.get(player.getUUID()));
	}

	private static void tick(MinecraftServer server) {
		if (server == null) return;
		long now = server.getTickCount();
		Set<UUID> online = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			online.add(player.getUUID());
			SpiritState state = STATES.get(player.getUUID());
			if (state == null || !state.summoned) {
				cleanupRuntime(server, player.getUUID(), true, false);
				continue;
			}
			if (!player.isAlive()) {
				destroyWithOwner(player);
				continue;
			}
			if (!canUseSpirit(player) || player.isSpectator()) {
				cleanupRuntime(server, player.getUUID(), true, false);
				continue;
			}
			RuntimeSpirit runtime = ensureRuntime(player, false);
			if (runtime != null) {
				tickSpirit(player, runtime, now, state.hidden);
				syncAirClickTrigger(player, runtime);
			}
		}

		for (UUID ownerId : new HashSet<>(RUNTIME.keySet())) {
			if (!online.contains(ownerId)) cleanupRuntime(server, ownerId, false, false);
		}
		for (UUID ownerId : new HashSet<>(AIR_CLICK_TRIGGERS.keySet())) {
			if (!online.contains(ownerId)) removeAirClickTrigger(ownerId);
		}
		if (dirty && now % 200L == 0L) save(server);
	}

	private static void tickSpirit(ServerPlayer owner, RuntimeSpirit runtime, long now, boolean hidden) {
		if (!(owner.level() instanceof ServerLevel level)) return;
		NecromancerSpiritVex spirit = runtime.spirit;
		if (spirit == null || spirit.isRemoved() || spirit.level() != level) {
			ensureRuntime(owner, false);
			return;
		}

		keepSpiritSafe(spirit, runtime.carriedItem, hidden, runtime.mode != SpiritMode.WANDERING);
		if (runtime.mode != SpiritMode.RETURNING || runtime.carriedItem.isEmpty()) {
			releaseFlightChunkTickets(level, runtime);
		}

		double ownerDistanceSqr = spirit.distanceToSqr(owner);
		if ((runtime.mode == SpiritMode.WANDERING || runtime.mode == SpiritMode.RECENTERING)
				&& ownerDistanceSqr > EMERGENCY_TELEPORT_DISTANCE * EMERGENCY_TELEPORT_DISTANCE) {
			Vec3 destination = safeCompanionPoint(owner, level, null, 1.2D, 0.5D);
			spirit.teleportTo(destination.x, destination.y, destination.z);
			stopSpiritImmediately(runtime, false);
			if (!runtime.carriedItem.isEmpty()) {
				runtime.mode = SpiritMode.RETURNING;
				runtime.targetItemId = null;
			} else if (runtime.targetItemId != null) {
				runtime.mode = SpiritMode.FETCHING;
			} else if (!startNextQueuedTarget(level, runtime)) {
				runtime.mode = SpiritMode.RECENTERING;
				runtime.recenterTarget = null;
			}
		}

		switch (runtime.mode) {
			case FETCHING -> tickFetching(owner, level, runtime);
			case RETURNING -> tickReturning(owner, level, runtime, now);
			case RECENTERING -> tickRecentering(owner, level, runtime, now);
			case WANDERING -> tickWandering(owner, level, runtime, now);
		}
		if (!hidden) spawnMovementTrail(level, runtime, now);
	}

	private static void tickFetching(ServerPlayer owner, ServerLevel level, RuntimeSpirit runtime) {
		if (runtime.targetItemChunk != null
				&& !level.getChunkSource().hasChunk(runtime.targetItemChunk.x, runtime.targetItemChunk.z)) {
			forgetUnavailableTarget(level, runtime);
			return;
		}
		Entity entity = runtime.targetItemId == null ? null : level.getEntity(runtime.targetItemId);
		if (!(entity instanceof ItemEntity item) || !item.isAlive() || item.getItem().isEmpty()) {
			forgetUnavailableTarget(level, runtime);
			return;
		}

		ChunkPos itemChunk = item.chunkPosition();
		if (!level.getChunkSource().hasChunk(itemChunk.x, itemChunk.z)) {
			forgetUnavailableTarget(level, runtime);
			return;
		}
		runtime.targetItemChunk = itemChunk;
		Vec3 target = item.getBoundingBox().getCenter();
		if (runtime.spirit.distanceToSqr(item) <= 0.9D * 0.9D) {
			runtime.carriedItem = item.getItem().copy();
			item.discard();
			level.sendParticles(SOUL_DUST, target.x, target.y, target.z, 12, 0.18D, 0.18D, 0.18D, 0.015D);
			level.playSound(null, target.x, target.y, target.z, SoundEvents.VEX_AMBIENT, SoundSource.NEUTRAL, 0.45F, 1.25F);
			beginReturn(runtime);
			commandSpirit(runtime, ownerDeliveryPoint(owner));
			return;
		}
		Vec3 direction = target.subtract(runtime.spirit.position());
		if (!isNextFlightChunkLoaded(level, runtime.spirit.position(), direction)) {
			forgetUnavailableTarget(level, runtime);
			return;
		}
		commandSpirit(runtime, target);
	}

	private static void tickReturning(ServerPlayer owner, ServerLevel level, RuntimeSpirit runtime, long now) {
		Vec3 target = ownerDeliveryPoint(owner);
		if (runtime.spirit.position().distanceToSqr(target) <= 1.35D * 1.35D) {
			if (!runtime.carriedItem.isEmpty()) throwCarriedItem(level, owner, runtime);
			if (startNextQueuedTarget(level, runtime)) {
				tickFetching(owner, level, runtime);
			} else {
				enterWandering(owner, level, runtime, now);
			}
			return;
		}
		commandSpirit(runtime, target);
	}

	private static void tickWandering(
			ServerPlayer owner,
			ServerLevel level,
			RuntimeSpirit runtime,
			long now
	) {
		Vec3 center = owner.position().add(0.0D, 1.0D, 0.0D);
		if (!isWithinSpiritSphere(center, runtime.spirit.position())
				|| !level.noBlockCollision(runtime.spirit, runtime.spirit.getBoundingBox())) {
			runtime.mode = SpiritMode.RECENTERING;
			runtime.recenterTarget = null;
			runtime.wanderPath = null;
			tickRecentering(owner, level, runtime, now);
			return;
		}
		if (runtime.wanderPath != null && (now >= runtime.nextWanderTargetTick
				|| !isWanderPathWithinSphere(center, runtime.spirit, runtime.wanderPath))) {
			runtime.wanderPath = null;
			runtime.nextWanderTargetTick = now + 10L;
		}
		Vec3 target = CompanionMovement.waypoint(runtime.wanderPath, runtime.spirit, false);
		if (runtime.wanderPath != null && target == null) {
			runtime.wanderPath = null;
			runtime.nextWanderTargetTick = now + randomWanderPauseTicks(owner);
		}
		if (runtime.wanderPath == null && now >= runtime.nextWanderTargetTick) {
			runtime.wanderPath = sampleSpiritWanderPath(level, owner, runtime.spirit);
			runtime.nextWanderTargetTick = now + (runtime.wanderPath == null ? 10L : 200L);
			target = CompanionMovement.waypoint(runtime.wanderPath, runtime.spirit, false);
		}
		boolean finalNode = runtime.wanderPath == null
				|| runtime.wanderPath.getNextNodeIndex() >= runtime.wanderPath.getNodeCount() - 1;
		Vec3 offset = target == null ? Vec3.ZERO : target.subtract(runtime.spirit.position());
		Vec3 velocity = CompanionMovement.steer(runtime.controlledVelocity, offset, WANDER_SPEED, WANDER_ACCELERATION, finalNode);
		velocity = CompanionMovement.constrainStep(center, runtime.spirit.position(), velocity, INNER_FOLLOW_RADIUS, OUTER_FOLLOW_RADIUS);
		if (!isFlightPathClear(level, runtime.spirit, runtime.spirit.position().add(velocity))) {
			// Discard a blocked route; do not continually push into a newly placed block.
			runtime.wanderPath = null;
			runtime.nextWanderTargetTick = now + 10L;
			velocity = Vec3.ZERO;
		}
		applyControlledVelocity(runtime, velocity, false);
	}

	private static void tickRecentering(ServerPlayer owner, ServerLevel level, RuntimeSpirit runtime, long now) {
		Vec3 center = owner.position().add(0.0D, 1.0D, 0.0D);
		boolean outside = runtime.spirit.position().distanceToSqr(center) > OUTER_FOLLOW_RADIUS * OUTER_FOLLOW_RADIUS;
		if (outside) {
			if (runtime.outsideSinceTick == null) runtime.outsideSinceTick = now;
			if (now - runtime.outsideSinceTick >= WANDER_MAX_OUTSIDE_TICKS) {
				Vec3 destination = safeCompanionPoint(owner, level, null, 2.0D, 0.0D);
				runtime.spirit.teleportTo(destination.x, destination.y, destination.z);
				stopSpiritImmediately(runtime, true);
				runtime.recenterTarget = null;
				runtime.outsideSinceTick = null;
			}
		} else runtime.outsideSinceTick = null;

		if (runtime.recenterTarget == null || !isWithinSpiritSphere(center, runtime.recenterTarget)
				|| !isOpenSpiritDestination(level, runtime.recenterTarget)) {
			runtime.recenterTarget = findRecenterTarget(level, center, runtime.spirit);
		}
		Vec3 target = runtime.recenterTarget;
		boolean settled = isWithinSpiritSphere(center, runtime.spirit.position())
				&& level.noBlockCollision(runtime.spirit, runtime.spirit.getBoundingBox())
				&& runtime.spirit.position().distanceToSqr(target) < 0.5D * 0.5D
				&& runtime.controlledVelocity.lengthSqr() <= WANDER_SPEED * WANDER_SPEED;
		if (settled) {
			runtime.mode = SpiritMode.WANDERING;
			runtime.wanderPath = null;
			runtime.recenterTarget = null;
			runtime.nextWanderTargetTick = now;
			tickWandering(owner, level, runtime, now);
			return;
		}
		Vec3 direction = target.subtract(runtime.spirit.position());
		if (!isNextFlightChunkLoaded(level, runtime.spirit.position(), direction)) {
			stopForUnloadedChunk(runtime);
			return;
		}
		commandSpirit(runtime, target);
	}

	private static Vec3 findRecenterTarget(ServerLevel level, Vec3 center, NecromancerSpiritVex spirit) {
		Vec3 radial = spirit.position().subtract(center).normalize();
		if (radial.lengthSqr() < 0.1D) radial = new Vec3(1.0D, 0.0D, 0.0D);
		Vec3 preferred = center.add(radial.scale(2.4D));
		if (isOpenSpiritDestination(level, preferred)) return preferred;
		Vec3 best = preferred;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (double height : new double[]{0.0D, 0.7D, -0.7D, 1.4D, -1.4D}) {
			for (int angle = 0; angle < 16; angle++) {
				double radians = angle * Math.PI / 8.0D;
				Vec3 candidate = center.add(Math.cos(radians) * 2.2D, height, Math.sin(radians) * 2.2D);
				double distance = spirit.position().distanceToSqr(candidate);
				if (distance < bestDistance && isOpenSpiritDestination(level, candidate)) {
					best = candidate;
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	private static RuntimeSpirit ensureRuntime(ServerPlayer owner, boolean appearanceEffects) {
		if (!(owner.level() instanceof ServerLevel level)) return null;
		RuntimeSpirit current = RUNTIME.get(owner.getUUID());
		if (current != null && current.spirit != null && !current.spirit.isRemoved()
				&& current.spirit.level() == level) return current;

		ItemStack carried = current == null ? ItemStack.EMPTY : current.carriedItem.copy();
		Deque<DeliveryTarget> queuedTargets = new ArrayDeque<>();
		if (current != null && current.dimension.equals(level.dimension())) {
			if (current.targetItemId != null && current.targetItemChunk != null) {
				queuedTargets.addLast(new DeliveryTarget(current.targetItemId, current.targetItemChunk));
			}
			queuedTargets.addAll(current.deliveryQueue);
		}
		if (current != null) removeRuntimeEntity(owner.level().getServer(), current);
		NecromancerSpiritVex spirit = new NecromancerSpiritVex(level);
		SpiritState state = STATES.get(owner.getUUID());
		boolean hidden = state != null && state.hidden;
		Vec3 spawn = safeCompanionPoint(owner, level, null, 1.35D, 0.45D);
		spirit.setPos(spawn);
		spirit.setYRot(owner.getYRot());
		spirit.setYHeadRot(owner.getYRot());
		spirit.setInvisible(hidden);
		spirit.setControlledPhasing(!carried.isEmpty());
		spirit.setItemSlot(EquipmentSlot.MAINHAND, hidden ? ItemStack.EMPTY : carried.copy());
		if (!level.addFreshEntity(spirit)) return null;

		RuntimeSpirit runtime = new RuntimeSpirit(spirit, level.dimension());
		runtime.carriedItem = carried;
		runtime.deliveryQueue.addAll(queuedTargets);
		if (!carried.isEmpty()) runtime.mode = SpiritMode.RETURNING;
		else if (!runtime.deliveryQueue.isEmpty()) startNextQueuedTarget(level, runtime);
		RUNTIME.put(owner.getUUID(), runtime);
		if (appearanceEffects) spawnAppearance(level, spawn);
		return runtime;
	}

	private static void beginReturn(RuntimeSpirit runtime) {
		clearActiveTarget(runtime);
		runtime.wanderPath = null;
		runtime.recenterTarget = null;
		runtime.mode = SpiritMode.RETURNING;
		runtime.spirit.setControlledPhasing(true);
	}

	private static boolean startNextQueuedTarget(ServerLevel level, RuntimeSpirit runtime) {
		if (level == null || runtime == null || !runtime.carriedItem.isEmpty()) return false;
		releaseFlightChunkTickets(level, runtime);
		while (!runtime.deliveryQueue.isEmpty()) {
			DeliveryTarget target = runtime.deliveryQueue.removeFirst();
			if (!level.getChunkSource().hasChunk(target.chunkPos().x, target.chunkPos().z)) continue;
			Entity entity = level.getEntity(target.itemId());
			if (!(entity instanceof ItemEntity item) || !item.isAlive() || item.getItem().isEmpty()) continue;
			ChunkPos itemChunk = item.chunkPosition();
			if (!level.getChunkSource().hasChunk(itemChunk.x, itemChunk.z)) continue;
			runtime.targetItemId = target.itemId();
			runtime.targetItemChunk = itemChunk;
			runtime.mode = SpiritMode.FETCHING;
			runtime.wanderPath = null;
			runtime.recenterTarget = null;
			runtime.outsideSinceTick = null;
			runtime.spirit.setControlledPhasing(true);
			return true;
		}
		clearActiveTarget(runtime);
		return false;
	}

	private static void clearActiveTarget(RuntimeSpirit runtime) {
		if (runtime == null) return;
		runtime.targetItemId = null;
		runtime.targetItemChunk = null;
	}

	private static void forgetUnavailableTarget(ServerLevel level, RuntimeSpirit runtime) {
		clearActiveTarget(runtime);
		if (!startNextQueuedTarget(level, runtime)) beginReturn(runtime);
	}

	private static void enterWandering(ServerPlayer owner, ServerLevel level, RuntimeSpirit runtime, long now) {
		if (runtime == null) return;
		runtime.mode = SpiritMode.RECENTERING;
		clearActiveTarget(runtime);
		runtime.wanderPath = null;
		runtime.recenterTarget = null;
		runtime.outsideSinceTick = null;
		runtime.nextWanderTargetTick = now + randomWanderPauseTicks(owner);
		releaseFlightChunkTickets(level, runtime);
		tickRecentering(owner, level, runtime, now);
	}

	private static Vec3 ownerDeliveryPoint(ServerPlayer owner) {
		return owner.getBoundingBox().getCenter();
	}

	private static void keepSpiritSafe(
			NecromancerSpiritVex spirit,
			ItemStack carried,
			boolean hidden,
			boolean deliveryFlight
	) {
		spirit.setInvulnerable(true);
		spirit.setNoGravity(true);
		spirit.setControlledPhasing(deliveryFlight);
		spirit.setInvisible(hidden);
		spirit.setGlowingTag(false);
		spirit.setCustomName(null);
		spirit.setCustomNameVisible(false);
		spirit.setHealth(spirit.getMaxHealth());
		spirit.setItemSlot(EquipmentSlot.MAINHAND, hidden ? ItemStack.EMPTY : carried.copy());
		spirit.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		spirit.setTarget(null);
	}

	private static ItemEntity findLookedAtItem(ServerPlayer player, ServerLevel level) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle().normalize();
		Vec3 rayEnd = eye.add(look.scale(ITEM_LOOK_DISTANCE));
		HitResult blockHit = level.clip(new ClipContext(
				eye,
				rayEnd,
				ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE,
				player
		));
		double clearDistanceSqr = blockHit.getType() == HitResult.Type.MISS
				? ITEM_LOOK_DISTANCE * ITEM_LOOK_DISTANCE
				: blockHit.getLocation().distanceToSqr(eye) + 0.25D;

		ItemEntity directHit = null;
		double directHitDistanceSqr = Double.POSITIVE_INFINITY;
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ItemEntity item) || !item.isAlive() || item.getItem().isEmpty()) continue;
			Vec3 intersection = item.getBoundingBox().inflate(0.35D).clip(eye, rayEnd).orElse(null);
			if (intersection == null) continue;
			double distanceSqr = intersection.distanceToSqr(eye);
			if (distanceSqr <= clearDistanceSqr && distanceSqr < directHitDistanceSqr) {
				directHit = item;
				directHitDistanceSqr = distanceSqr;
			}
		}
		if (directHit != null) return directHit;

		ItemEntity best = null;
		double bestAngularScore = Double.POSITIVE_INFINITY;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof ItemEntity item) || !item.isAlive() || item.getItem().isEmpty()) continue;
			Vec3 target = item.getBoundingBox().getCenter();
			Vec3 delta = target.subtract(eye);
			double projection = delta.dot(look);
			if (projection <= 0.0D || projection > ITEM_LOOK_DISTANCE) continue;
			double perpendicularSqr = Math.max(0.0D, delta.lengthSqr() - projection * projection);
			double tolerance = ITEM_AIM_BASE_TOLERANCE + Math.min(2.5D, projection * ITEM_AIM_DISTANCE_TOLERANCE);
			if (perpendicularSqr > tolerance * tolerance || !hasClearView(level, player, eye, target)) continue;
			double angularScore = perpendicularSqr / Math.max(1.0D, projection * projection);
			if (angularScore < bestAngularScore - 1.0E-8D
					|| Math.abs(angularScore - bestAngularScore) <= 1.0E-8D && projection < bestDistance) {
				best = item;
				bestAngularScore = angularScore;
				bestDistance = projection;
			}
		}
		return best;
	}

	private static boolean hasClearView(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to) {
		HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.MISS
				|| hit.getLocation().distanceToSqr(from) + 0.04D >= to.distanceToSqr(from);
	}

	private static void syncAirClickTrigger(ServerPlayer player, RuntimeSpirit runtime) {
		if (!shouldMaintainAirClickTrigger(player, runtime) || hasAirClickTriggerObstruction(player)) {
			removeAirClickTrigger(player == null ? null : player.getUUID());
			return;
		}

		Interaction trigger = AIR_CLICK_TRIGGERS.get(player.getUUID());
		if (trigger == null || !trigger.isAlive() || trigger.level() != player.level()) {
			if (trigger != null) trigger.discard();
			trigger = new Interaction(EntityType.INTERACTION, player.level());
			trigger.addTag(AIR_CLICK_TRIGGER_TAG);
			trigger.setNoGravity(true);
			trigger.setSilent(true);
			trigger.setInvisible(true);
			trigger.setResponse(false);
			trigger.setWidth(AIR_CLICK_TRIGGER_WIDTH);
			trigger.setHeight(AIR_CLICK_TRIGGER_HEIGHT);
			if (!player.level().addFreshEntity(trigger)) return;
			AIR_CLICK_TRIGGERS.put(player.getUUID(), trigger);
		}

		Vec3 position = player.getEyePosition()
				.add(player.getLookAngle().normalize().scale(AIR_CLICK_TRIGGER_FORWARD_OFFSET))
				.subtract(0.0D, AIR_CLICK_TRIGGER_HEIGHT * 0.5D, 0.0D);
		trigger.setInvisible(true);
		trigger.setPos(position.x, position.y, position.z);
		trigger.setDeltaMovement(Vec3.ZERO);
		trigger.setYRot(player.getYRot());
		trigger.setXRot(player.getXRot());
		player.connection.send(ClientboundEntityPositionSyncPacket.of(trigger));
	}

	private static boolean shouldMaintainAirClickTrigger(ServerPlayer player, RuntimeSpirit runtime) {
		if (player == null || runtime == null || !player.isAlive() || player.isSpectator()
				|| !canUseSpirit(player)
				|| !player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
			return false;
		}
		SpiritState state = STATES.get(player.getUUID());
		return state != null
				&& state.summoned
				&& runtime.spirit != null
				&& runtime.spirit.isAlive();
	}

	private static boolean hasAirClickTriggerObstruction(ServerPlayer player) {
		double reach = Math.max(player.blockInteractionRange(), player.entityInteractionRange());
		HitResult hit = player.pick(reach, 1.0F, false);
		if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit
				&& AIR_CLICK_TRIGGERS.get(player.getUUID()) == entityHit.getEntity()) {
			return false;
		}
		return hit.getType() != HitResult.Type.MISS;
	}

	private static void removeAirClickTrigger(UUID playerId) {
		if (playerId == null) return;
		Interaction trigger = AIR_CLICK_TRIGGERS.remove(playerId);
		if (trigger != null) trigger.discard();
	}

	private static boolean isNextFlightChunkLoaded(ServerLevel level, Vec3 current, Vec3 direction) {
		if (direction.lengthSqr() < 1.0E-8D) return true;
		Vec3 probe = current.add(direction.normalize().scale(1.5D));
		BlockPos pos = BlockPos.containing(probe);
		return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
	}

	private static void throwCarriedItem(ServerLevel level, ServerPlayer owner, RuntimeSpirit runtime) {
		ItemStack stack = runtime.carriedItem.copy();
		if (stack.isEmpty()) return;
		Vec3 source = runtime.spirit.position().add(0.0D, 0.25D, 0.0D);
		Vec3 target = owner.position().add(0.0D, 0.35D, 0.0D);
		Vec3 direction = target.subtract(source);
		Vec3 velocity = direction.lengthSqr() < 1.0E-6D
				? new Vec3(0.0D, 0.12D, 0.0D)
				: direction.normalize().scale(0.28D).add(0.0D, 0.10D, 0.0D);
		ItemEntity dropped = new ItemEntity(level, source.x, source.y, source.z, stack);
		dropped.setNoPickUpDelay();
		dropped.setThrower(runtime.spirit);
		dropped.setTarget(owner.getUUID());
		dropped.setDeltaMovement(velocity);
		if (!level.addFreshEntity(dropped)) returnItemDirectly(owner, stack);
		runtime.carriedItem = ItemStack.EMPTY;
		runtime.spirit.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		runtime.spirit.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
		level.playSound(null, source.x, source.y, source.z, SoundEvents.VEX_AMBIENT, SoundSource.NEUTRAL, 0.7F, 0.9F);
	}

	private static void returnItemDirectly(ServerPlayer owner, ItemStack carried) {
		if (owner == null || carried == null || carried.isEmpty()) return;
		ItemStack remainder = carried.copy();
		owner.getInventory().add(remainder);
		if (!remainder.isEmpty()) owner.drop(remainder, false);
	}

	private static void spawnMovementTrail(ServerLevel level, RuntimeSpirit runtime, long now) {
		Vec3 position = runtime.spirit.position().add(0.0D, runtime.spirit.getBbHeight() * 0.45D, 0.0D);
		if (runtime.lastTrailPosition != null && position.distanceToSqr(runtime.lastTrailPosition) < 0.012D) return;
		runtime.lastTrailPosition = position;
		level.sendParticles(NECRO_DUST, position.x, position.y, position.z, 2, 0.10D, 0.10D, 0.10D, 0.006D);
		if ((now & 1L) == 0L) {
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, position.x, position.y, position.z, 1, 0.08D, 0.08D, 0.08D, 0.01D);
		}
		if (now % 4L == 0L) {
			level.sendParticles(ParticleTypes.SCULK_SOUL, position.x, position.y, position.z, 1, 0.04D, 0.04D, 0.04D, 0.002D);
		}
	}

	private static void spawnAppearance(ServerLevel level, Vec3 position) {
		level.sendParticles(NECRO_DUST, position.x, position.y + 0.5D, position.z, 38, 0.65D, 0.8D, 0.65D, 0.035D);
		level.sendParticles(SOUL_DUST, position.x, position.y + 0.5D, position.z, 22, 0.5D, 0.7D, 0.5D, 0.02D);
		level.sendParticles(ParticleTypes.SCULK_SOUL, position.x, position.y + 0.4D, position.z, 14, 0.45D, 0.65D, 0.45D, 0.015D);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, position.x, position.y + 0.4D, position.z, 18, 0.5D, 0.65D, 0.5D, 0.025D);
		level.playSound(null, position.x, position.y, position.z, SoundEvents.VEX_AMBIENT, SoundSource.PLAYERS, 0.85F, 0.68F);
		level.playSound(null, position.x, position.y, position.z, SoundEvents.VEX_CHARGE, SoundSource.PLAYERS, 0.55F, 0.72F);
	}

	private static void spawnDissolve(ServerLevel level, Vec3 position) {
		level.sendParticles(ParticleTypes.ASH, position.x, position.y + 0.45D, position.z, 42, 0.55D, 0.75D, 0.55D, 0.025D);
		level.sendParticles(ParticleTypes.WHITE_ASH, position.x, position.y + 0.45D, position.z, 25, 0.45D, 0.65D, 0.45D, 0.018D);
		level.sendParticles(ParticleTypes.SMOKE, position.x, position.y + 0.35D, position.z, 24, 0.4D, 0.55D, 0.4D, 0.02D);
		level.sendParticles(NECRO_DUST, position.x, position.y + 0.4D, position.z, 18, 0.45D, 0.6D, 0.45D, 0.015D);
		level.playSound(null, position.x, position.y, position.z, SoundEvents.VEX_DEATH, SoundSource.PLAYERS, 0.8F, 0.72F);
	}

	private static net.minecraft.world.level.pathfinder.Path sampleSpiritWanderPath(ServerLevel level, ServerPlayer owner, NecromancerSpiritVex spirit) {
		Vec3 center = owner.position().add(0.0D, 1.0D, 0.0D);
		Vec3 view = spirit.getViewVector(1.0F);
		for (int attempt = 0; attempt < 20; attempt++) {
			// The same air/water target sampler used by a calm allay, constrained to our smaller sphere.
			Vec3 candidate = net.minecraft.world.entity.ai.util.AirAndWaterRandomPos.getPos(
					spirit, 3, 2, 0, view.x, view.z, (float) Math.PI);
			if (candidate == null || !isWithinSpiritSphere(center, candidate)
					|| spirit.position().distanceToSqr(candidate) < 0.8D * 0.8D
					|| !isOpenSpiritDestination(level, candidate)) continue;
			net.minecraft.world.level.pathfinder.Path path = spirit.getNavigation().createPath(BlockPos.containing(candidate), 0);
			CompanionMovement.skipRoundedStart(path, spirit);
			if (path != null && path.canReach() && isWanderPathWithinSphere(center, spirit, path)) return path;
		}
		return null;
	}

	private static boolean isWanderPathWithinSphere(Vec3 center, NecromancerSpiritVex spirit, net.minecraft.world.level.pathfinder.Path path) {
		Vec3 previous = spirit.position();
		for (int index = path.getNextNodeIndex(); index < path.getNodeCount(); index++) {
			Vec3 next = path.getEntityPosAtNode(spirit, index);
			if (!isWithinSpiritSphere(center, next)
					|| CompanionMovement.crossesInner(center, previous, next, INNER_FOLLOW_RADIUS)) return false;
			previous = next;
		}
		return true;
	}

	private static boolean isWithinSpiritSphere(Vec3 center, Vec3 position) {
		double distanceSqr = center.distanceToSqr(position);
		return distanceSqr >= INNER_FOLLOW_RADIUS * INNER_FOLLOW_RADIUS
				&& distanceSqr <= OUTER_FOLLOW_RADIUS * OUTER_FOLLOW_RADIUS;
	}

	private static void commandSpirit(RuntimeSpirit runtime, Vec3 target) {
		Vec3 offset = target.subtract(runtime.spirit.position());
		Vec3 velocity = CompanionMovement.steer(runtime.controlledVelocity, offset,
				TRAVEL_SPEED, DELIVERY_ACCELERATION, true);
		ServerLevel level = (ServerLevel) runtime.spirit.level();
		boolean canMove = runtime.mode == SpiritMode.RETURNING && !runtime.carriedItem.isEmpty()
				? updateReturnFlightChunkTicket(level, runtime, velocity)
				: isNextFlightChunkLoaded(level, runtime.spirit.position(), velocity);
		if (!canMove) {
			stopForUnloadedChunk(runtime);
			return;
		}
		applyControlledVelocity(runtime, velocity, true);
	}

	private static void stopForUnloadedChunk(RuntimeSpirit runtime) {
		// Keep steering momentum, but never take a step into a chunk before its ticket is ready.
		runtime.spirit.setDeltaMovement(Vec3.ZERO);
	}

	private static void applyControlledVelocity(
			RuntimeSpirit runtime,
			Vec3 velocity,
			boolean phaseThroughBlocks
	) {
		runtime.controlledVelocity = velocity;
		NecromancerSpiritVex spirit = runtime.spirit;
		spirit.setControlledPhasing(phaseThroughBlocks);
		spirit.setDeltaMovement(velocity);
		// NoAI disables vanilla travel. The controller must move the spirit itself.
		Vec3 previousPosition = spirit.position();
		spirit.move(MoverType.SELF, velocity);
		Vec3 actualMovement = spirit.position().subtract(previousPosition);
		if (!phaseThroughBlocks) runtime.controlledVelocity = actualMovement;
		orientSpirit(spirit, actualMovement);
	}

	private static void orientSpirit(NecromancerSpiritVex spirit, Vec3 velocity) {
		if (velocity.lengthSqr() <= 1.0E-6D) return;
		double horizontalMovement = velocity.horizontalDistance();
		if (horizontalMovement > 1.0E-4D) {
			float wantedYaw = (float) (Math.toDegrees(Math.atan2(velocity.z, velocity.x)) - 90.0D);
			float yaw = CompanionMovement.turn(spirit.getYRot(), wantedYaw);
			spirit.setYRot(yaw);
			spirit.setYBodyRot(yaw);
			spirit.setYHeadRot(yaw);
		}
		float wantedPitch = (float) -Math.toDegrees(Math.atan2(velocity.y, horizontalMovement));
		spirit.setXRot(CompanionMovement.turn(spirit.getXRot(), wantedPitch));
	}

	private static void stopSpiritImmediately(RuntimeSpirit runtime, boolean phaseThroughBlocks) {
		if (runtime == null || runtime.spirit == null) return;
		applyControlledVelocity(runtime, Vec3.ZERO, phaseThroughBlocks);
	}

	private static long randomWanderPauseTicks(ServerPlayer owner) {
		return WANDER_PAUSE_MIN_TICKS + owner.getRandom().nextInt((int) WANDER_PAUSE_RANDOM_TICKS);
	}

	private static Vec3 safeCompanionPoint(
			ServerPlayer owner,
			ServerLevel level,
			NecromancerSpiritVex spirit,
			double horizontalDistance,
			double yOffset
	) {
		Vec3 preferred = companionPoint(owner, horizontalDistance, yOffset);
		if (isSafeSpiritDestination(level, spirit, preferred)) return preferred;

		double baseYaw = Math.toRadians(owner.getYRot() + 105.0D);
		double[] heightOffsets = {yOffset, yOffset + 0.55D, Math.max(-0.15D, yOffset - 0.4D)};
		for (double candidateHeight : heightOffsets) {
			for (int index = 0; index < 8; index++) {
				double angle = baseYaw + index * Math.PI / 4.0D;
				double radius = Math.max(0.8D, horizontalDistance);
				Vec3 candidate = owner.position().add(
						Math.cos(angle) * radius,
						1.0D + candidateHeight,
						Math.sin(angle) * radius
				);
				if (isSafeSpiritDestination(level, spirit, candidate)) return candidate;
			}
		}
		return spirit == null ? preferred : spirit.position();
	}

	private static boolean isSafeSpiritDestination(
			ServerLevel level,
			NecromancerSpiritVex spirit,
			Vec3 destination
	) {
		return isOpenSpiritDestination(level, destination)
				&& (spirit == null || isFlightPathClear(level, spirit, destination));
	}

	private static boolean isOpenSpiritDestination(ServerLevel level, Vec3 destination) {
		if (level == null || destination == null) return false;
		return level.noBlockCollision(null, new net.minecraft.world.phys.AABB(
				destination.x - 0.3D,
				destination.y,
				destination.z - 0.3D,
				destination.x + 0.3D,
				destination.y + 0.9D,
				destination.z + 0.3D
		));
	}

	private static boolean isFlightPathClear(ServerLevel level, NecromancerSpiritVex spirit, Vec3 destination) {
		if (level == null || spirit == null || destination == null) return false;
		Vec3 movement = destination.subtract(spirit.position());
		double distance = movement.length();
		if (distance < 1.0E-5D) return level.noBlockCollision(spirit, spirit.getBoundingBox());

		int steps = Math.max(1, (int) Math.ceil(distance / 0.3D));
		boolean escapedInitialBlock = level.noBlockCollision(spirit, spirit.getBoundingBox());
		for (int step = 1; step <= steps; step++) {
			Vec3 offset = movement.scale(step / (double) steps);
			boolean free = level.noBlockCollision(spirit, spirit.getBoundingBox().move(offset));
			if (!escapedInitialBlock) {
				if (free) escapedInitialBlock = true;
				continue;
			}
			if (!free) return false;
		}
		return escapedInitialBlock;
	}

	private static Vec3 companionPoint(ServerPlayer owner, double horizontalDistance, double yOffset) {
		double yaw = Math.toRadians(owner.getYRot() + 105.0D);
		return owner.position().add(Math.cos(yaw) * horizontalDistance, 1.0D + yOffset, Math.sin(yaw) * horizontalDistance);
	}

	private static boolean updateReturnFlightChunkTicket(ServerLevel level, RuntimeSpirit runtime, Vec3 direction) {
		if (level == null || runtime == null || runtime.spirit == null) return false;
		Vec3 ticketPosition = runtime.spirit.position();
		if (direction != null && direction.lengthSqr() > 1.0E-8D) {
			ticketPosition = ticketPosition.add(direction.normalize().scale(2.0D));
		}
		ChunkPos desired = new ChunkPos(BlockPos.containing(ticketPosition));

		if (runtime.flightTicketCenters.add(desired)) {
			level.getChunkSource().addTicketWithRadius(
					runtime.flightTicketType,
					desired,
					SPIRIT_FLIGHT_CHUNK_RADIUS
			);
		}
		for (ChunkPos chunk : new HashSet<>(runtime.flightTicketCenters)) {
			if (desired.equals(chunk)) continue;
			level.getChunkSource().removeTicketWithRadius(
					runtime.flightTicketType,
					chunk,
					SPIRIT_FLIGHT_CHUNK_RADIUS
			);
			runtime.flightTicketCenters.remove(chunk);
		}
		return level.getChunkSource().hasChunk(desired.x, desired.z);
	}

	private static void releaseFlightChunkTickets(ServerLevel level, RuntimeSpirit runtime) {
		if (level == null || runtime == null || runtime.flightTicketCenters.isEmpty()) return;
		for (ChunkPos chunk : new HashSet<>(runtime.flightTicketCenters)) {
			level.getChunkSource().removeTicketWithRadius(
					runtime.flightTicketType,
					chunk,
					SPIRIT_FLIGHT_CHUNK_RADIUS
			);
		}
		runtime.flightTicketCenters.clear();
	}

	private static void onLivingDeath(LivingEntity victim, DamageSource source) {
		if (victim instanceof ServerPlayer player && STATES.containsKey(player.getUUID())) destroyWithOwner(player);
	}

	private static void onDisconnect(ServerPlayer player, MinecraftServer server) {
		RuntimeSpirit runtime = RUNTIME.get(player.getUUID());
		if (runtime != null && !runtime.carriedItem.isEmpty()) {
			returnItemDirectly(player, runtime.carriedItem);
			runtime.carriedItem = ItemStack.EMPTY;
		}
		cleanupRuntime(server, player.getUUID(), false, false);
	}

	private static void destroyWithOwner(ServerPlayer owner) {
		SpiritState state = STATES.get(owner.getUUID());
		if (state == null || !state.summoned) return;
		RuntimeSpirit runtime = RUNTIME.get(owner.getUUID());
		if (runtime != null && runtime.spirit != null && runtime.spirit.level() instanceof ServerLevel level) {
			if (!runtime.carriedItem.isEmpty()) dropCarriedAtDeath(level, owner, runtime);
			spawnDissolve(level, runtime.spirit.position());
		}
		cleanupRuntime(owner.level().getServer(), owner.getUUID(), false, false);
		state.summoned = false;
		state.hidden = false;
		markDirty();
	}

	private static void dropCarriedAtDeath(ServerLevel level, ServerPlayer owner, RuntimeSpirit runtime) {
		ItemStack stack = runtime.carriedItem.copy();
		if (stack.isEmpty()) return;
		ItemEntity dropped = new ItemEntity(level, owner.getX(), owner.getY() + 0.25D, owner.getZ(), stack);
		dropped.setNoPickUpDelay();
		if (!level.addFreshEntity(dropped)) returnItemDirectly(owner, stack);
		runtime.carriedItem = ItemStack.EMPTY;
		runtime.spirit.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
	}

	private static void cleanupRuntime(MinecraftServer server, UUID ownerId, boolean returnCarried, boolean dissolve) {
		removeAirClickTrigger(ownerId);
		RuntimeSpirit runtime = RUNTIME.remove(ownerId);
		LAST_RIGHT_CLICK_TICKS.remove(ownerId);
		if (runtime == null) return;
		ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
		if (returnCarried && owner != null && !runtime.carriedItem.isEmpty()) {
			returnItemDirectly(owner, runtime.carriedItem);
			runtime.carriedItem = ItemStack.EMPTY;
		}
		if (dissolve && runtime.spirit != null && runtime.spirit.level() instanceof ServerLevel level) {
			spawnDissolve(level, runtime.spirit.position());
		}
		removeRuntimeEntity(server, runtime);
	}

	private static void removeRuntimeEntity(MinecraftServer server, RuntimeSpirit runtime) {
		ServerLevel forcedLevel = server.getLevel(runtime.dimension);
		if (forcedLevel != null) releaseFlightChunkTickets(forcedLevel, runtime);
		if (runtime.spirit != null && !runtime.spirit.isRemoved()) runtime.spirit.removeForController();
	}

	private static boolean canUseSpirit(ServerPlayer player) {
		return player != null
				&& NecromancerStockSystem.isActive(player)
				&& ServerRaceSystem.hasUnlockedAbility(player, RaceAbilitySlot.UNIQUE_ABILITY);
	}

	private static double configuredCost(double configured, double fallback) {
		return Double.isFinite(configured) && configured >= 0.0D ? configured : fallback;
	}

	private static void markDirty() {
		dirty = true;
	}

	private static void load(MinecraftServer server) {
		STATES.clear();
		RUNTIME.clear();
		LAST_RIGHT_CLICK_TICKS.clear();
		AIR_CLICK_TRIGGERS.clear();
		Path path = statePath(server);
		if (Files.exists(path)) {
			try {
				PersistedData data = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), PersistedData.class);
				if (data != null && data.players != null) STATES.putAll(data.players);
			} catch (Exception exception) {
				Lg2.LOGGER.error("Failed to load Necromancer spirit state from {}", path, exception);
			}
		}
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity.getTags().contains(SPIRIT_TAG)
						|| entity.getTags().contains(AIR_CLICK_TRIGGER_TAG)) entity.discard();
			}
		}
		dirty = false;
	}

	private static void save(MinecraftServer server) {
		if (server == null) return;
		PersistedData data = new PersistedData();
		data.players.putAll(STATES);
		Path path = statePath(server);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
			dirty = false;
		} catch (IOException exception) {
			Lg2.LOGGER.error("Failed to save Necromancer spirit state to {}", path, exception);
		}
	}

	private static void shutdown(MinecraftServer server) {
		for (UUID ownerId : new HashSet<>(RUNTIME.keySet())) cleanupRuntime(server, ownerId, true, false);
		AIR_CLICK_TRIGGERS.values().forEach(Entity::discard);
		AIR_CLICK_TRIGGERS.clear();
		save(server);
		RUNTIME.clear();
		LAST_RIGHT_CLICK_TICKS.clear();
	}

	private static Path statePath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(STATE_FILE_NAME);
	}

	private enum SpiritMode {
		WANDERING,
		RECENTERING,
		FETCHING,
		RETURNING
	}

	private static final class SpiritState {
		private boolean summoned;
		private boolean hidden;
	}

	private static final class PersistedData {
		private int version = 1;
		private final Map<UUID, SpiritState> players = new LinkedHashMap<>();
	}

	private record DeliveryTarget(UUID itemId, ChunkPos chunkPos) {
	}

	private static final class RuntimeSpirit {
		private final NecromancerSpiritVex spirit;
		private final ResourceKey<Level> dimension;
		private final TicketType flightTicketType = new TicketType(0L, SPIRIT_FLIGHT_TICKET_FLAGS);
		private final Set<ChunkPos> flightTicketCenters = new HashSet<>();
		private SpiritMode mode = SpiritMode.WANDERING;
		private UUID targetItemId;
		private ChunkPos targetItemChunk;
		private final Deque<DeliveryTarget> deliveryQueue = new ArrayDeque<>();
		private ItemStack carriedItem = ItemStack.EMPTY;
		private Vec3 controlledVelocity = Vec3.ZERO;
		private net.minecraft.world.level.pathfinder.Path wanderPath;
		private Vec3 recenterTarget;
		private Vec3 lastTrailPosition;
		private long nextWanderTargetTick;
		private Long outsideSinceTick;

		private RuntimeSpirit(NecromancerSpiritVex spirit, ResourceKey<Level> dimension) {
			this.spirit = spirit;
			this.dimension = dimension;
		}
	}

	private static final class NecromancerSpiritVex extends Vex {
		private boolean controllerRemoval;
		private boolean controlledPhasing = true;

		private NecromancerSpiritVex(ServerLevel level) {
			super(EntityType.VEX, level);
			this.addTag(SPIRIT_TAG);
			this.setPersistenceRequired();
			this.setSilent(true);
			this.setNoAi(true);
			this.setCanPickUpLoot(false);
			this.setInvulnerable(true);
			this.setNoGravity(true);
			this.setPathfindingMalus(PathType.WATER, 0.0F);
			this.setPathfindingMalus(PathType.LAVA, 0.0F);
			this.setPathfindingMalus(PathType.DAMAGE_FIRE, 0.0F);
			this.setPathfindingMalus(PathType.DANGER_FIRE, 0.0F);
			this.setPathfindingMalus(PathType.DAMAGE_OTHER, 0.0F);
			this.noPhysics = true;
			for (EquipmentSlot slot : EquipmentSlot.values()) this.setDropChance(slot, 0.0F);
		}

		private void setControlledPhasing(boolean phaseThroughBlocks) {
			this.controlledPhasing = phaseThroughBlocks;
			this.noPhysics = phaseThroughBlocks;
		}

		@Override
		public void move(MoverType type, Vec3 movement) {
			boolean previousNoPhysics = this.noPhysics;
			this.noPhysics = controlledPhasing;
			super.move(type, movement);
			this.noPhysics = previousNoPhysics;
		}

		private void removeForController() {
			this.controllerRemoval = true;
			this.discard();
		}

		@Override
		protected void registerGoals() {
		}

		@Override
		protected PathNavigation createNavigation(Level level) {
			FlyingPathNavigation navigation = new FlyingPathNavigation(this, level);
			// Fluids must not redirect a spirit's route to the surface.
			navigation.setCanFloat(false);
			navigation.setCanOpenDoors(false);
			return navigation;
		}

		@Override
		public boolean isAffectedByFluids() {
			return false;
		}

		@Override
		public boolean isPushedByFluid() {
			return false;
		}

		@Override
		public void onAboveBubbleColumn(boolean dragDown, BlockPos pos) {
		}

		@Override
		public void onInsideBubbleColumn(boolean dragDown) {
		}

		@Override
		public void makeStuckInBlock(BlockState state, Vec3 movementMultiplier) {
			this.resetFallDistance();
		}

		@Override
		public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
			return false;
		}

		@Override
		public void setHealth(float health) {
			super.setHealth(Math.max(1.0F, health));
		}

		@Override
		public void die(DamageSource source) {
		}

		@Override
		public void remove(RemovalReason reason) {
			if (!this.controllerRemoval
					&& (reason == RemovalReason.DISCARDED || reason == RemovalReason.KILLED)) return;
			super.remove(reason);
		}

		@Override
		public boolean canBeHitByProjectile() {
			return false;
		}

		@Override
		public boolean isPickable() {
			return false;
		}

		@Override
		public boolean isPushable() {
			return false;
		}

		@Override
		public boolean canCollideWith(Entity entity) {
			return false;
		}

		@Override
		public boolean isAttackable() {
			return false;
		}

		@Override
		public boolean skipAttackInteraction(Entity attacker) {
			return true;
		}

		@Override
		public void checkDespawn() {
		}
	}
}
