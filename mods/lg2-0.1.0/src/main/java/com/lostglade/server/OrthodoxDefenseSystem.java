package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.mixin.EntityPassengerAccessor;
import com.mojang.math.Transformation;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class OrthodoxDefenseSystem {
	private static final String ORTHODOX_RACE_ID = "orthodox";
	private static final String WINGS_TAG = "lg2.orthodox_angel_wings";
	private static final String WINGS_OWNER_TAG_PREFIX = "lg2.orthodox_angel_wings_owner:";
	private static final String WING_PART_TAG_PREFIX = "lg2.orthodox_angel_wing_part:";
	private static final int WING_SIDE_COUNT = 2;
	private static final int WING_PART_COUNT = 6;
	private static final Map<UUID, DefenseSession> SESSIONS = new HashMap<>();

	private OrthodoxDefenseSystem() {
	}

	public static void register() {
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
				!world.isClientSide() && player instanceof ServerPlayer serverPlayer && isActive(serverPlayer)
						? InteractionResult.FAIL : InteractionResult.PASS);
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
				!world.isClientSide() && player instanceof ServerPlayer serverPlayer && isActive(serverPlayer)
						? InteractionResult.FAIL : InteractionResult.PASS);
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
				!world.isClientSide() && player instanceof ServerPlayer serverPlayer && isActive(serverPlayer)
						? InteractionResult.FAIL : InteractionResult.PASS);
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
				!world.isClientSide() && player instanceof ServerPlayer serverPlayer && isActive(serverPlayer)
						? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, world, hand) ->
				!world.isClientSide() && player instanceof ServerPlayer serverPlayer && isActive(serverPlayer)
						? InteractionResult.FAIL : InteractionResult.PASS);
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
				!(player instanceof ServerPlayer serverPlayer) || !isActive(serverPlayer));

		ServerTickEvents.END_SERVER_TICK.register(OrthodoxDefenseSystem::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> deactivate(handler.player, false));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			deactivate(oldPlayer, false);
			newPlayer.onUpdateAbilities();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(OrthodoxDefenseSystem::clearAll);
	}

	public static boolean activate(ServerPlayer player, long durationTicks) {
		if (player == null || !player.isAlive() || player.isSpectator() || durationTicks <= 0L || isActive(player)) {
			return false;
		}

		Abilities abilities = player.getAbilities();
		long nowTick = player.level().getServer().overworld().getGameTime();
		DefenseSession session = new DefenseSession(
				nowTick,
				nowTick + durationTicks,
				player.gameMode.getGameModeForPlayer(),
				abilities.invulnerable,
				abilities.mayfly,
				abilities.flying,
				abilities.getFlyingSpeed()
		);
		SESSIONS.put(player.getUUID(), session);
		player.stopUsingItem();
		player.closeContainer();
		player.setGameMode(GameType.ADVENTURE);
		applyFlight(player);
		ensureWings(player, session);
		updateWings(player, session);
		player.level().playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0F, 1.35F);
		player.level().sendParticles(
				ParticleTypes.END_ROD,
				player.getX(), player.getY() + 1.0D, player.getZ(),
				42, 0.7D, 1.0D, 0.7D, 0.035D
		);
		return true;
	}

	public static boolean isActive(ServerPlayer player) {
		return player != null && SESSIONS.containsKey(player.getUUID());
	}

	public static boolean shouldCancelDamage(LivingEntity victim) {
		return victim instanceof ServerPlayer player && isActive(player);
	}

	public static float protectHealthChange(LivingEntity entity, float requestedHealth) {
		if (!(entity instanceof ServerPlayer player) || !isActive(player)) return requestedHealth;
		return Math.max(entity.getHealth(), requestedHealth);
	}

	public static boolean shouldBlockWorldInteraction(ServerPlayer player) {
		return isActive(player);
	}

	private static void tick(MinecraftServer server) {
		if (server == null || SESSIONS.isEmpty()) return;
		long nowTick = server.overworld().getGameTime();
		for (Map.Entry<UUID, DefenseSession> entry : new ArrayList<>(SESSIONS.entrySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			DefenseSession session = entry.getValue();
			if (player == null) continue;
			if (!player.isAlive() || nowTick >= session.endTick || !isOrthodox(player)) {
				deactivate(player, true);
				continue;
			}

			if (player.gameMode.getGameModeForPlayer() != GameType.ADVENTURE) player.setGameMode(GameType.ADVENTURE);
			Abilities abilities = player.getAbilities();
			boolean changed = !abilities.invulnerable || !abilities.mayfly;
			abilities.invulnerable = true;
			abilities.mayfly = true;
			if (changed) player.onUpdateAbilities();
			player.fallDistance = 0.0F;
			ensureWings(player, session);
			updateWings(player, session);
			emitFlightSounds(player, session, nowTick);
		}
	}

	private static void emitFlightSounds(ServerPlayer player, DefenseSession session, long nowTick) {
		if (!player.getAbilities().flying || player.onGround()) {
			session.nextFlightSoundTick = nowTick;
			return;
		}
		if (nowTick < session.nextFlightSoundTick) return;
		player.level().playSound(null, player.blockPosition(), SoundEvents.BREEZE_IDLE_AIR,
				SoundSource.PLAYERS, 0.18F, 0.90F);
		session.nextFlightSoundTick = nowTick + 32L;
	}

	private static boolean isOrthodox(ServerPlayer player) {
		return ServerRaceSystem.getRace(player)
				.map(race -> race.id != null && ORTHODOX_RACE_ID.equalsIgnoreCase(race.id.trim()))
				.orElse(false);
	}

	private static void applyFlight(ServerPlayer player) {
		Abilities abilities = player.getAbilities();
		abilities.invulnerable = true;
		abilities.mayfly = true;
		abilities.flying = true;
		player.fallDistance = 0.0F;
		player.onUpdateAbilities();
	}

	private static void deactivate(ServerPlayer player, boolean effects) {
		if (player == null) return;
		DefenseSession session = SESSIONS.remove(player.getUUID());
		if (session == null) return;
		removeWings(player, session);
		player.setGameMode(session.previousGameType);

		Abilities abilities = player.getAbilities();
		abilities.invulnerable = session.previousInvulnerable;
		abilities.mayfly = session.previousMayfly;
		abilities.flying = session.previousFlying && session.previousMayfly;
		abilities.setFlyingSpeed(session.previousFlyingSpeed);
		player.fallDistance = 0.0F;
		player.onUpdateAbilities();
		if (effects && player.level() instanceof ServerLevel level) {
			level.playSound(null, player.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.85F, 1.4F);
			level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0D, player.getZ(), 24, 0.6D, 0.8D, 0.6D, 0.02D);
		}
	}

	private static void clearAll(MinecraftServer server) {
		if (server != null) {
			for (UUID playerId : new ArrayList<>(SESSIONS.keySet())) {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player != null) deactivate(player, false);
			}
			for (ServerLevel level : server.getAllLevels()) {
				for (Entity entity : level.getAllEntities()) {
					if (entity.getTags().contains(WINGS_TAG)) entity.discard();
				}
			}
		}
		SESSIONS.clear();
	}

	private static void ensureWings(ServerPlayer player, DefenseSession session) {
		for (int side = 0; side < WING_SIDE_COUNT; side++) {
			for (int part = 0; part < WING_PART_COUNT; part++) {
				UUID previous = session.wingPartIds[side][part];
				session.wingPartIds[side][part] = ensureWingPart(
						player,
						session.wingPartIds[side][part],
						side,
						part
				);
				if (!java.util.Objects.equals(previous, session.wingPartIds[side][part])) {
					session.wingTransforms[side][part] = null;
				}
			}
		}
	}

	private static UUID ensureWingPart(ServerPlayer player, UUID wingId, int side, int part) {
		String partTag = wingPartTag(side, part);
		Display.ItemDisplay wing = findWingPart(player, wingId, partTag);
		if (wing != null) {
			configureWingPart(wing, wingModelId(side, part));
			attachPassenger(player, wing);
			return wing.getUUID();
		}

		removeWingsEntity(player.level().getServer(), wingId);
		Display.ItemDisplay display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, player.level());
		display.addTag(WINGS_TAG);
		display.addTag(WINGS_OWNER_TAG_PREFIX + player.getUUID());
		display.addTag(partTag);
		configureWingPart(display, wingModelId(side, part));
		display.setPos(player.getX(), player.getY(), player.getZ());
		if (!player.level().addFreshEntity(display)) return null;
		attachPassenger(player, display);
		return display.getUUID();
	}

	private static Display.ItemDisplay findWingPart(ServerPlayer player, UUID wingId, String partTag) {
		if (wingId != null) {
			Entity entity = player.level().getEntity(wingId);
			if (entity instanceof Display.ItemDisplay display && display.getTags().contains(WINGS_TAG)
					&& display.getTags().contains(partTag)) return display;
		}
		for (Entity passenger : player.getPassengers()) {
			if (passenger instanceof Display.ItemDisplay display && display.getTags().contains(WINGS_TAG)
					&& display.getTags().contains(partTag)) return display;
		}
		return null;
	}

	private static void configureWingPart(Display.ItemDisplay display, Identifier modelId) {
		display.setInvulnerable(true);
		display.setSilent(true);
		display.setNoGravity(true);
		display.setGlowingTag(false);
		display.setBrightnessOverride(null);
		display.setItemStack(createWingStack(modelId));
		display.setItemTransform(ItemDisplayContext.FIXED);
		display.setViewRange(1.5F);
		display.setPosRotInterpolationDuration(1);
		display.setTransformationInterpolationDelay(0);
		display.setTransformationInterpolationDuration(1);
	}

	private static String wingPartTag(int side, int part) {
		return WING_PART_TAG_PREFIX + side + ":" + part;
	}

	private static Identifier wingModelId(int side, int part) {
		String sideName = side == 0 ? "left" : "right";
		return Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "orthodox_angel_wing_" + sideName + "_" + (part + 1));
	}

	private static ItemStack createWingStack(Identifier modelId) {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, modelId);
		return stack;
	}

	private static void attachPassenger(Entity vehicle, Entity passenger) {
		if (passenger.getVehicle() == vehicle && vehicle.hasPassenger(passenger)) return;
		if (passenger.isPassenger()) passenger.stopRiding();
		((EntityPassengerAccessor) passenger).lg2$setVehicle(vehicle);
		((EntityPassengerAccessor) vehicle).lg2$addPassenger(passenger);
		vehicle.positionRider(passenger);
		if (vehicle.level() instanceof ServerLevel level) {
			ClientboundSetPassengersPacket packet = new ClientboundSetPassengersPacket(vehicle);
			for (ServerPlayer viewer : level.players()) viewer.connection.send(packet);
		}
	}

	private static void updateWings(ServerPlayer player, DefenseSession session) {
		OrthodoxWingRig.Pose[] poses = session.wingAnimation.tick(player.getAbilities().flying && !player.onGround());
		for (int side = 0; side < WING_SIDE_COUNT; side++) {
			for (int part = 0; part < WING_PART_COUNT; part++) {
				Display.ItemDisplay display = findWingPart(player, session.wingPartIds[side][part], wingPartTag(side, part));
				if (display == null) continue;
				OrthodoxWingRig.Pose pose = poses[side * WING_PART_COUNT + part];
				float yaw = player.getYRot() + 180.0F;
				display.setYRot(yaw);
				display.setYHeadRot(yaw);
				// Keep the display offset fixed; the passenger seat itself follows crouching on the client.
				float standingSeatHeight = (float) (player.getDimensions(Pose.STANDING).attachments()
						.getClamped(EntityAttachment.PASSENGER, 0, player.getYRot()).y
						- display.getVehicleAttachmentPoint(player).y);
				Transformation transform = OrthodoxWingRig.displayTransformation(pose, standingSeatHeight);
				if (!transform.equals(session.wingTransforms[side][part])) {
					display.setTransformation(transform);
					display.setTransformationInterpolationDelay(0);
					session.wingTransforms[side][part] = transform;
				}
			}
		}
	}

	private static void removeWings(ServerPlayer player, DefenseSession session) {
		if (player != null) {
			for (Entity passenger : new ArrayList<>(player.getPassengers())) {
				if (passenger.getTags().contains(WINGS_TAG)) passenger.discard();
			}
		}
		MinecraftServer server = player == null ? null : player.level().getServer();
		for (int side = 0; side < WING_SIDE_COUNT; side++) {
			for (int part = 0; part < WING_PART_COUNT; part++) {
				removeWingsEntity(server, session.wingPartIds[side][part]);
				session.wingPartIds[side][part] = null;
			}
		}
	}

	private static void removeWingsEntity(MinecraftServer server, UUID displayId) {
		if (server == null || displayId == null) return;
		for (ServerLevel level : server.getAllLevels()) {
			Entity entity = level.getEntity(displayId);
			if (entity != null) {
				entity.discard();
				return;
			}
		}
	}

	private static final class DefenseSession {
		private final long startTick;
		private final long endTick;
		private final GameType previousGameType;
		private final boolean previousInvulnerable;
		private final boolean previousMayfly;
		private final boolean previousFlying;
		private final float previousFlyingSpeed;
		private final UUID[][] wingPartIds = new UUID[WING_SIDE_COUNT][WING_PART_COUNT];
		private final Transformation[][] wingTransforms = new Transformation[WING_SIDE_COUNT][WING_PART_COUNT];
		private long nextFlightSoundTick;
		private final OrthodoxWingRig.Animation wingAnimation = new OrthodoxWingRig.Animation();

		private DefenseSession(long startTick, long endTick, GameType previousGameType, boolean previousInvulnerable, boolean previousMayfly,
				boolean previousFlying, float previousFlyingSpeed) {
			this.startTick = startTick;
			this.endTick = endTick;
			this.previousGameType = previousGameType;
			this.previousInvulnerable = previousInvulnerable;
			this.previousMayfly = previousMayfly;
			this.previousFlying = previousFlying;
			this.previousFlyingSpeed = previousFlyingSpeed;
		}
	}
}
