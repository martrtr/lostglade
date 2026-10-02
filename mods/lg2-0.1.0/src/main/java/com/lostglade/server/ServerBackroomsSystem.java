package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.block.ModBlocks;
import com.lostglade.worldgen.BackroomsSpecialRooms;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ServerBackroomsSystem {
	public static final ResourceKey<Level> BACKROOMS_LEVEL = ResourceKey.create(
			Registries.DIMENSION,
			Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "backrooms")
	);

	private static final Gson STATE_GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String RETURNS_FILE_NAME = "lg2-backrooms-returns.json";
	private static final Set<Relative> ABSOLUTE_TELEPORT = EnumSet.noneOf(Relative.class);
	private static final BlockPos BACKROOMS_PLATFORM_CENTER = new BlockPos(0, 64, 0);
	private static final int BACKROOMS_LEVEL_FLOOR_Y = 64;
	private static final int BACKROOMS_LEVEL_HEIGHT = 5;
	private static final int RESPAWN_MIN_COORD = -4096;
	private static final int RESPAWN_MAX_COORD = 4096;
	private static final int PLATFORM_RADIUS = 2;
	private static final int PLATFORM_CLEAR_HEIGHT = 4;
	private static final int PLATFORM_EXIT_LENGTH = 12;
	private static final int RESPAWN_SEARCH_ATTEMPTS = 48;
	private static final int RESPAWN_SEARCH_RADIUS = 12;
	private static final int PREWARMED_RESPAWN_TARGET = 8;
	private static final int PREWARMED_RESPAWN_IDLE_TARGET = 3;
	private static final int PREWARMED_RESPAWN_STARTUP_BATCH = 3;
	private static final int PREWARMED_RESPAWN_REFILL_INTERVAL_TICKS = 20;
	private static final int PREWARMED_RESPAWN_CHUNK_RADIUS = 2;
	private static final int PREWARMED_RESPAWN_PENDING_LIMIT = 4;
	private static final int PREWARMED_RESPAWN_SEARCH_TIMEOUT_TICKS = 400;
	private static final int ACTIVE_RESPAWN_CHUNK_HOLD_TICKS = 200;
	private static final int BACKROOMS_SUPPORT_TICK_INTERVAL = 2;
	private static final int LADDER_CRAWL_GRACE_TICKS = 6;
	private static final Identifier BACKROOMS_AMBIENT_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "backrooms_ambient_loop");
	// 12s file at 20 TPS ~= 240 ticks. Start every 200 ticks for ~2s overlap.
	private static final int BACKROOMS_AMBIENT_LOOP_TICKS = 200;
	private static final float[] BACKROOMS_AMBIENT_FADE_IN = new float[]{0.35F, 0.55F, 0.75F, 0.85F};
	private static final float BACKROOMS_LOW_PITCH_CHANCE = 0.06F;
	private static final float BACKROOMS_LOW_PITCH = 0.944F;
	private static final float BACKROOMS_DIP_VOLUME_MULTIPLIER = 0.82F;
	private static final int BACKROOMS_VOLTAGE_DIP_TICKS = BACKROOMS_AMBIENT_LOOP_TICKS + 6;
	private static final int BACKROOMS_DARKNESS_REFRESH_TICKS = 6;
	private static final int BACKROOMS_VOLTAGE_DIP_MIN_LOOPS = 2;
	private static final int BACKROOMS_VOLTAGE_DIP_MAX_LOOPS = 3;
	private static final int LAMP_FLICKER_RADIUS_HORIZONTAL = 14;
	private static final int LAMP_FLICKER_RADIUS_VERTICAL = 4;
	private static final int LAMP_FLICKER_SEARCH_ATTEMPTS = 18;
	private static final int LAMP_FLICKER_MAX_PER_TRIGGER = 2;
	private static final int LAMP_FLICKER_MIN_OFF_TICKS = 3;
	private static final int LAMP_FLICKER_MAX_OFF_TICKS = 9;
	private static final int LAMP_FLICKER_RESTORE_BUDGET_PER_TICK = 28;
	private static final int LAMP_FLICKER_MAX_ACTIVE = 640;
	private static final float LAMP_FLICKER_TRIGGER_CHANCE = 0.40F;
	private static final int LAMP_FLICKER_TRIGGER_COOLDOWN_TICKS = 2;
	private static final Map<UUID, ReturnPointState> RETURN_POINTS = new HashMap<>();
	private static final Map<UUID, AmbientLoopState> AMBIENT_LOOP_STATES = new HashMap<>();
	private static final Map<Long, Long> ACTIVE_LAMP_OUTAGES = new HashMap<>();
	private static final List<BlockPos> PREWARMED_RESPAWNS = new ArrayList<>();
	private static final Set<Long> PREWARMED_RESPAWN_KEYS = new HashSet<>();
	private static final Map<BlockPos, Long> PENDING_PREWARMED_RESPAWN_SEARCHES = new HashMap<>();
	private static final Map<Long, Integer> PREWARMED_RESPAWN_CHUNK_REFS = new HashMap<>();
	private static final Map<BlockPos, Long> ACTIVE_RESPAWN_CHUNK_HOLDS = new HashMap<>();
	private static final Set<UUID> FORCED_LADDER_CRAWL_PLAYERS = new HashSet<>();
	private static final Map<UUID, Long> FORCED_LADDER_CRAWL_GRACE_UNTIL = new HashMap<>();

	private static boolean stateLoaded = false;
	private static boolean stateDirty = false;
	private ServerBackroomsSystem() {
	}

	public static void register() {
		stateLoaded = false;
		stateDirty = false;
		RETURN_POINTS.clear();
		AMBIENT_LOOP_STATES.clear();
		ACTIVE_LAMP_OUTAGES.clear();
		PREWARMED_RESPAWNS.clear();
		PREWARMED_RESPAWN_KEYS.clear();
		PENDING_PREWARMED_RESPAWN_SEARCHES.clear();
		PREWARMED_RESPAWN_CHUNK_REFS.clear();
		ACTIVE_RESPAWN_CHUNK_HOLDS.clear();
		FORCED_LADDER_CRAWL_PLAYERS.clear();
		FORCED_LADDER_CRAWL_GRACE_UNTIL.clear();
		ServerLifecycleEvents.SERVER_STARTED.register(ServerBackroomsSystem::loadState);
		ServerLifecycleEvents.SERVER_STOPPING.register(ServerBackroomsSystem::saveState);
		ServerTickEvents.END_SERVER_TICK.register(ServerBackroomsSystem::tickServer);
		UseBlockCallback.EVENT.register(ServerBackroomsSystem::onUseBlock);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(
						Commands.literal("backrooms")
								.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
								.executes(context -> enterPlayers(context.getSource().getServer(), List.of(context.getSource().getPlayerOrException()), true))
								.then(Commands.literal("enter")
										.executes(context -> enterPlayers(context.getSource().getServer(), List.of(context.getSource().getPlayerOrException()), true))
										.then(Commands.argument("targets", EntityArgument.players())
												.executes(context -> enterPlayers(
														context.getSource().getServer(),
														EntityArgument.getPlayers(context, "targets"),
														false
												))))
								.then(Commands.literal("return")
										.executes(context -> returnPlayers(context.getSource().getServer(), List.of(context.getSource().getPlayerOrException()), true))
										.then(Commands.argument("targets", EntityArgument.players())
												.executes(context -> returnPlayers(
														context.getSource().getServer(),
														EntityArgument.getPlayers(context, "targets"),
														false
												))))
				)
		);
	}

	public static boolean isBackrooms(Level level) {
		return level != null && isBackrooms(level.dimension());
	}

	public static boolean isBackrooms(ResourceKey<Level> dimension) {
		return BACKROOMS_LEVEL.equals(dimension);
	}

	public static boolean isInBackrooms(Entity entity) {
		return entity != null && isBackrooms(entity.level());
	}

	public static int countPlayersOutsideBackrooms(MinecraftServer server) {
		if (server == null) {
			return 0;
		}

		int count = 0;
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			if (player == null || !player.isAlive() || player.isSpectator() || isInBackrooms(player)) {
				continue;
			}
			count++;
		}
		return count;
	}

	public static boolean teleportPlayerToBackrooms(ServerPlayer player) {
		if (player == null) {
			return false;
		}

		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return false;
		}

		ServerLevel backrooms = server.getLevel(BACKROOMS_LEVEL);
		if (backrooms == null) {
			return false;
		}

		if (!player.level().dimension().equals(BACKROOMS_LEVEL)) {
			storeReturnPoint(player);
		}

		teleportToRandomBackroomsRespawn(backrooms, player);
		return true;
	}

	public static boolean teleportPlayerToNormalSpawn(ServerPlayer player) {
		if (player == null) {
			return false;
		}

		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return false;
		}

		ServerLevel targetLevel = server.overworld();
		BlockPos targetPos = targetLevel.getRespawnData().pos();
		float targetYaw = targetLevel.getRespawnData().yaw();
		float targetPitch = targetLevel.getRespawnData().pitch();

		ServerPlayer.RespawnConfig respawnConfig = player.getRespawnConfig();
		LevelData.RespawnData respawnData = respawnConfig == null ? null : respawnConfig.respawnData();
		if (respawnData != null && Level.OVERWORLD.equals(respawnData.dimension())) {
			BlockPos respawnPos = respawnData.pos();
			if (respawnPos != null) {
				targetPos = respawnPos;
				targetYaw = respawnData.yaw();
				targetPitch = respawnData.pitch();
			}
		}

		targetLevel.getChunkAt(targetPos);
		player.teleportTo(
				targetLevel,
				targetPos.getX() + 0.5D,
				targetPos.getY() + 0.1D,
				targetPos.getZ() + 0.5D,
				ABSOLUTE_TELEPORT,
				targetYaw,
				targetPitch,
				false
		);
		player.fallDistance = 0.0F;
		return true;
	}

	private static int enterPlayers(MinecraftServer server, Collection<ServerPlayer> players, boolean singleFeedback) {
		ServerLevel backrooms = server.getLevel(BACKROOMS_LEVEL);
		if (backrooms == null) {
			throw new IllegalStateException("Dimension lg2:backrooms is not loaded. Restart the server after updating the mod resources.");
		}

		int moved = 0;
		for (ServerPlayer player : players) {
			if (teleportPlayerToBackrooms(player)) {
				moved++;
			}
		}

		if (singleFeedback && moved == 1) {
			players.iterator().next().sendSystemMessage(Component.literal("Перемещение в backrooms выполнено."));
		}

		return moved;
	}

	private static int returnPlayers(MinecraftServer server, Collection<ServerPlayer> players, boolean singleFeedback) {
		int moved = 0;
		for (ServerPlayer player : players) {
			if (player == null) {
				continue;
			}

			ReturnPointState returnPoint = RETURN_POINTS.remove(player.getUUID());
			if (returnPoint == null) {
				teleportToFallback(server, player);
			} else {
				ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(returnPoint.dimension));
				ServerLevel level = server.getLevel(dimension);
				if (level == null) {
					teleportToFallback(server, player);
				} else {
					level.getChunkAt(BlockPos.containing(returnPoint.x, returnPoint.y, returnPoint.z));
					player.teleportTo(
							level,
							returnPoint.x,
							returnPoint.y,
							returnPoint.z,
							ABSOLUTE_TELEPORT,
							returnPoint.yaw,
							returnPoint.pitch,
							false
					);
				}
				stateDirty = true;
			}

			player.fallDistance = 0.0F;
			moved++;
		}

		if (singleFeedback && moved == 1) {
			players.iterator().next().sendSystemMessage(Component.literal("Возврат из backrooms выполнен."));
		}

		return moved;
	}

	private static void teleportToFallback(MinecraftServer server, ServerPlayer player) {
		teleportPlayerToNormalSpawn(player);
	}

	private static void storeReturnPoint(ServerPlayer player) {
		ReturnPointState state = new ReturnPointState();
		state.dimension = player.level().dimension().identifier().toString();
		state.x = player.getX();
		state.y = player.getY();
		state.z = player.getZ();
		state.yaw = player.getYRot();
		state.pitch = player.getXRot();
		RETURN_POINTS.put(player.getUUID(), state);
		stateDirty = true;
	}

	private static void loadState(MinecraftServer server) {
		RETURN_POINTS.clear();
		stateLoaded = true;
		stateDirty = false;

		Path path = getStateFilePath(server);
		if (Files.isRegularFile(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				ReturnStateFile file = STATE_GSON.fromJson(reader, ReturnStateFile.class);
				if (file != null && file.players != null) {
					for (Map.Entry<String, ReturnPointState> entry : file.players.entrySet()) {
						try {
							RETURN_POINTS.put(UUID.fromString(entry.getKey()), entry.getValue());
						} catch (IllegalArgumentException ignored) {
						}
					}
				}
			} catch (IOException exception) {
				Lg2.LOGGER.error("Failed to load backrooms return points", exception);
			}
		}

		ServerLevel backrooms = server.getLevel(BACKROOMS_LEVEL);
		if (backrooms != null) {
			queueAsyncPrewarmedRespawnSearches(backrooms, server.getTickCount(), PREWARMED_RESPAWN_STARTUP_BATCH, PREWARMED_RESPAWN_TARGET);
		}
	}

	private static void saveState(MinecraftServer server) {
		if (!stateLoaded || !stateDirty) {
			return;
		}

		Path path = getStateFilePath(server);
		ReturnStateFile file = new ReturnStateFile();
		file.players = new LinkedHashMap<>();
		for (Map.Entry<UUID, ReturnPointState> entry : RETURN_POINTS.entrySet()) {
			file.players.put(entry.getKey().toString(), entry.getValue());
		}

		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				STATE_GSON.toJson(file, writer);
			}
			stateDirty = false;
		} catch (IOException exception) {
			Lg2.LOGGER.error("Failed to save backrooms return points", exception);
		}
	}

	private static Path getStateFilePath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(RETURNS_FILE_NAME);
	}

	private static void tickServer(MinecraftServer server) {
		enforceClearWeather(server);
		tickActiveRespawnChunkHolds(server);
		tickPrewarmedRespawns(server);
		tickUpperLadderCrawl(server);
		if ((server.overworld().getGameTime() % BACKROOMS_SUPPORT_TICK_INTERVAL) != 0L) {
			return;
		}
		tickBackroomsAmbientLoop(server);
		tickLampRestores(server);
	}

	private static void tickUpperLadderCrawl(MinecraftServer server) {
		long nowTick = server.overworld().getGameTime();
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			UUID uuid = player.getUUID();
			boolean inBackrooms = isInBackrooms(player);
			boolean shouldStart = inBackrooms
					&& BackroomsSpecialRooms.isUpperLadderCrawlZone(player.level(), player.getX(), player.getY(), player.getZ());
			boolean inKeepZone = inBackrooms
					&& BackroomsSpecialRooms.isUpperLadderOrTunnelCrawlZone(player.level(), player.getX(), player.getY(), player.getZ());
			boolean onLadder = inBackrooms && player.onClimbable();
			if (shouldStart || inKeepZone || (FORCED_LADDER_CRAWL_PLAYERS.contains(uuid) && onLadder)) {
				FORCED_LADDER_CRAWL_GRACE_UNTIL.put(uuid, nowTick + LADDER_CRAWL_GRACE_TICKS);
			}
			boolean shouldKeep = inBackrooms
					&& FORCED_LADDER_CRAWL_PLAYERS.contains(uuid)
					&& (inKeepZone || onLadder || nowTick <= FORCED_LADDER_CRAWL_GRACE_UNTIL.getOrDefault(uuid, Long.MIN_VALUE));
			if (shouldStart || shouldKeep) {
				FORCED_LADDER_CRAWL_PLAYERS.add(uuid);
				player.setSwimming(true);
				if (player.getPose() != Pose.SWIMMING) {
					player.setPose(Pose.SWIMMING);
					player.refreshDimensions();
				}
				player.fallDistance = 0.0F;
				continue;
			}

			FORCED_LADDER_CRAWL_GRACE_UNTIL.remove(uuid);
			if (!FORCED_LADDER_CRAWL_PLAYERS.remove(uuid)) {
				continue;
			}

			if (!player.isInWater() && !player.isUnderWater() && !player.isFallFlying()) {
				player.setSwimming(false);
				if (player.getPose() == Pose.SWIMMING) {
					player.setPose(Pose.STANDING);
					player.refreshDimensions();
				}
			}
		}
	}

	private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hitResult) {
		if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || !isBackrooms(world)) {
			return InteractionResult.PASS;
		}

		BlockState state = world.getBlockState(hitResult.getBlockPos());
		if (!state.is(Blocks.OAK_DOOR)) {
			return InteractionResult.PASS;
		}
		if (!BackroomsSpecialRooms.isFakeHouseDoorAt(hitResult.getBlockPos().getX(), hitResult.getBlockPos().getY(), hitResult.getBlockPos().getZ())) {
			return InteractionResult.PASS;
		}

		MinecraftServer server = world.getServer();
		if (server == null) {
			return InteractionResult.PASS;
		}

		ServerLevel backrooms = server.getLevel(BACKROOMS_LEVEL);
		if (backrooms == null) {
			return InteractionResult.PASS;
		}

		teleportToRandomBackroomsRespawn(backrooms, serverPlayer);
		return InteractionResult.CONSUME;
	}

	private static void enforceClearWeather(MinecraftServer server) {
		ServerLevel backrooms = server.getLevel(BACKROOMS_LEVEL);
		if (backrooms == null) {
			return;
		}

		if (!backrooms.isRaining() && !backrooms.isThundering() && backrooms.getRainLevel(1.0F) <= 0.0F && backrooms.getThunderLevel(1.0F) <= 0.0F) {
			return;
		}

		backrooms.setWeatherParameters(12000, 0, false, false);
		backrooms.setRainLevel(0.0F);
		backrooms.setThunderLevel(0.0F);
	}

	private static void teleportToRandomBackroomsRespawn(ServerLevel backrooms, ServerPlayer player) {
		BlockPos platformCenter = getRandomBackroomsRespawnFloor(backrooms);

		player.teleportTo(
				backrooms,
				platformCenter.getX() + 0.5D,
				platformCenter.getY() + 1.0D,
				platformCenter.getZ() + 0.5D,
				ABSOLUTE_TELEPORT,
				player.getYRot(),
				player.getXRot(),
				false
		);
		player.fallDistance = 0.0F;
	}

	public static TeleportTransition createImmediateBackroomsRespawnTransition(ServerPlayer player, TeleportTransition.PostTeleportTransition postTeleportTransition) {
		if (player == null || !isInBackrooms(player)) {
			return null;
		}

		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return null;
		}

		ServerLevel backrooms = server.getLevel(BACKROOMS_LEVEL);
		if (backrooms == null) {
			return null;
		}

		BlockPos platformCenter = getRandomBackroomsRespawnFloor(backrooms);
		Vec3 targetPosition = new Vec3(
				platformCenter.getX() + 0.5D,
				platformCenter.getY() + 1.0D,
				platformCenter.getZ() + 0.5D
		);
		return new TeleportTransition(
				backrooms,
				targetPosition,
				Vec3.ZERO,
				player.getYRot(),
				player.getXRot(),
				postTeleportTransition
		);
	}

	private static void tickBackroomsAmbientLoop(MinecraftServer server) {
		long gameTime = server.overworld().getGameTime();

		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			UUID uuid = player.getUUID();
			boolean shouldPlay = isInBackrooms(player) && PolymerResourcePackUtils.hasMainPack(player);
			AmbientLoopState state = AMBIENT_LOOP_STATES.get(uuid);

			if (!shouldPlay) {
				if (state != null) {
					stopBackroomsAmbient(player);
					AMBIENT_LOOP_STATES.remove(uuid);
				}
				continue;
			}

			if (state == null) {
				state = new AmbientLoopState();
				state.nextPlayTick = gameTime;
				state.fadeStep = 0;
				AMBIENT_LOOP_STATES.put(uuid, state);
			}

			if (gameTime >= state.nextPlayTick) {
				float volume = BACKROOMS_AMBIENT_FADE_IN[Math.min(state.fadeStep, BACKROOMS_AMBIENT_FADE_IN.length - 1)];
				if (state.voltageDipLoopsRemaining <= 0 && player.getRandom().nextFloat() < BACKROOMS_LOW_PITCH_CHANCE) {
					state.voltageDipLoopsRemaining = Mth.nextInt(player.getRandom(), BACKROOMS_VOLTAGE_DIP_MIN_LOOPS, BACKROOMS_VOLTAGE_DIP_MAX_LOOPS);
				}
				boolean lowPitchTick = state.voltageDipLoopsRemaining > 0;
				float pitch = lowPitchTick ? BACKROOMS_LOW_PITCH : 1.0F;
				if (lowPitchTick) {
					volume *= BACKROOMS_DIP_VOLUME_MULTIPLIER;
					state.voltageDipTicks = Math.max(state.voltageDipTicks, BACKROOMS_VOLTAGE_DIP_TICKS);
					state.voltageDipLoopsRemaining--;
				}
				playBackroomsAmbient(player, volume, pitch);
				state.fadeStep = Math.min(state.fadeStep + 1, BACKROOMS_AMBIENT_FADE_IN.length - 1);
				state.nextPlayTick = gameTime + BACKROOMS_AMBIENT_LOOP_TICKS;
			}

			if (state.voltageDipTicks > 0) {
				applyVoltageDip(server, player, state, gameTime);
				state.voltageDipTicks--;
			}
		}

		AMBIENT_LOOP_STATES.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
	}

	private static void playBackroomsAmbient(ServerPlayer player, float volume, float pitch) {
		Holder<SoundEvent> sound = Holder.direct(SoundEvent.createVariableRangeEvent(BACKROOMS_AMBIENT_SOUND_ID));
		long seed = player.level().random.nextLong();
		player.connection.send(
				new ClientboundSoundPacket(
						sound,
						SoundSource.AMBIENT,
						player.getX(),
						player.getY(),
						player.getZ(),
						volume,
						pitch,
						seed
				)
		);
	}

	private static void applyVoltageDip(MinecraftServer server, ServerPlayer player, AmbientLoopState state, long gameTime) {
		player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, BACKROOMS_DARKNESS_REFRESH_TICKS, 0, true, false, false));

		ServerLevel backrooms = server.getLevel(BACKROOMS_LEVEL);
		if (backrooms == null || !backrooms.dimension().equals(player.level().dimension())) {
			return;
		}

		if (ACTIVE_LAMP_OUTAGES.size() >= LAMP_FLICKER_MAX_ACTIVE) {
			return;
		}

		if (gameTime < state.nextLampFlickerTick) {
			return;
		}

		if (player.getRandom().nextFloat() > LAMP_FLICKER_TRIGGER_CHANCE) {
			return;
		}

		state.nextLampFlickerTick = gameTime + LAMP_FLICKER_TRIGGER_COOLDOWN_TICKS;
		triggerLampFlickerNearPlayer(backrooms, player, gameTime);
	}

	private static void triggerLampFlickerNearPlayer(ServerLevel level, ServerPlayer player, long gameTime) {
		int centerX = Mth.floor(player.getX());
		int centerY = Mth.floor(player.getY());
		int centerZ = Mth.floor(player.getZ());
		int turnedOff = 0;

		for (int attempt = 0; attempt < LAMP_FLICKER_SEARCH_ATTEMPTS; attempt++) {
			if (turnedOff >= LAMP_FLICKER_MAX_PER_TRIGGER || ACTIVE_LAMP_OUTAGES.size() >= LAMP_FLICKER_MAX_ACTIVE) {
				break;
			}

			int x = centerX + Mth.nextInt(level.random, -LAMP_FLICKER_RADIUS_HORIZONTAL, LAMP_FLICKER_RADIUS_HORIZONTAL);
			int y = centerY + Mth.nextInt(level.random, -LAMP_FLICKER_RADIUS_VERTICAL, LAMP_FLICKER_RADIUS_VERTICAL);
			int z = centerZ + Mth.nextInt(level.random, -LAMP_FLICKER_RADIUS_HORIZONTAL, LAMP_FLICKER_RADIUS_HORIZONTAL);
			BlockPos pos = new BlockPos(x, y, z);
			long packedPos = pos.asLong();

			if (ACTIVE_LAMP_OUTAGES.containsKey(packedPos) || !level.hasChunkAt(pos)) {
				continue;
			}

			if (!level.getBlockState(pos).is(ModBlocks.BACKROOMS_LIGHTBLOCK)) {
				continue;
			}

			level.setBlockAndUpdate(pos, ModBlocks.getRandomizedBackroomsBlockState(packedPos));
			long restoreAt = gameTime + Mth.nextInt(level.random, LAMP_FLICKER_MIN_OFF_TICKS, LAMP_FLICKER_MAX_OFF_TICKS);
			ACTIVE_LAMP_OUTAGES.put(packedPos, restoreAt);
			turnedOff++;
		}
	}

	private static void tickLampRestores(MinecraftServer server) {
		if (ACTIVE_LAMP_OUTAGES.isEmpty()) {
			return;
		}

		ServerLevel backrooms = server.getLevel(BACKROOMS_LEVEL);
		if (backrooms == null) {
			ACTIVE_LAMP_OUTAGES.clear();
			return;
		}

		long gameTime = backrooms.getGameTime();
		int restoreBudget = LAMP_FLICKER_RESTORE_BUDGET_PER_TICK;
		var iterator = ACTIVE_LAMP_OUTAGES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Long, Long> entry = iterator.next();
			if (entry.getValue() > gameTime) {
				continue;
			}

			BlockPos pos = BlockPos.of(entry.getKey());
			if (backrooms.hasChunkAt(pos) && backrooms.getBlockState(pos).is(ModBlocks.BACKROOMS_BLOCK)) {
				backrooms.setBlockAndUpdate(pos, ModBlocks.BACKROOMS_LIGHTBLOCK.defaultBlockState());
			}
			iterator.remove();
			restoreBudget--;
			if (restoreBudget <= 0) {
				break;
			}
		}
	}

	private static void stopBackroomsAmbient(ServerPlayer player) {
		player.connection.send(new ClientboundStopSoundPacket(BACKROOMS_AMBIENT_SOUND_ID, SoundSource.AMBIENT));
	}

	private static void tickPrewarmedRespawns(MinecraftServer server) {
		if (server.getTickCount() % PREWARMED_RESPAWN_REFILL_INTERVAL_TICKS != 0) {
			return;
		}
		ServerLevel backrooms = server.getLevel(BACKROOMS_LEVEL);
		if (backrooms == null) {
			return;
		}

		resolvePendingPrewarmedRespawnSearches(backrooms, server.getTickCount());

		int target = getDesiredPrewarmedRespawnTarget(server);
		if (PREWARMED_RESPAWNS.size() >= target) {
			return;
		}

		int deficit = target - PREWARMED_RESPAWNS.size();
		queueAsyncPrewarmedRespawnSearches(backrooms, server.getTickCount(), deficit, target);
	}

	private static void addPrewarmedRespawn(ServerLevel level, BlockPos floorPos) {
		BlockPos immutableFloorPos = floorPos.immutable();
		if (!PREWARMED_RESPAWN_KEYS.add(immutableFloorPos.asLong())) {
			return;
		}
		PREWARMED_RESPAWNS.add(immutableFloorPos);
		retainPrewarmedRespawnChunk(level, immutableFloorPos);
	}

	private static BlockPos findRandomLoadedSafeRespawn(ServerLevel level) {
		for (int attempt = 0; attempt < RESPAWN_SEARCH_ATTEMPTS; attempt++) {
			BlockPos center = pickRandomRespawnCenter(level);
			if (!isRespawnSearchAreaLoaded(level, center)) {
				continue;
			}

			BlockPos safePos = findNearbyLoadedSafeRespawn(level, center);
			if (safePos != null) {
				return safePos;
			}
		}

		return null;
	}

	private static int getDesiredPrewarmedRespawnTarget(MinecraftServer server) {
		return hasActiveBackroomsPlayers(server) ? PREWARMED_RESPAWN_TARGET : PREWARMED_RESPAWN_IDLE_TARGET;
	}

	private static boolean hasActiveBackroomsPlayers(MinecraftServer server) {
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			if (player != null && player.isAlive() && !player.isSpectator() && player.level().dimension().equals(BACKROOMS_LEVEL)) {
				return true;
			}
		}
		return false;
	}

	private static void queueAsyncPrewarmedRespawnSearches(ServerLevel level, long currentTick, int requestedCount, int targetCount) {
		if (level == null || requestedCount <= 0 || targetCount <= 0) {
			return;
		}

		int missingCapacity = Math.max(0, targetCount - (PREWARMED_RESPAWNS.size() + PENDING_PREWARMED_RESPAWN_SEARCHES.size()));
		int availablePendingSlots = Math.max(0, PREWARMED_RESPAWN_PENDING_LIMIT - PENDING_PREWARMED_RESPAWN_SEARCHES.size());
		int searchesToQueue = Math.min(requestedCount, Math.min(missingCapacity, availablePendingSlots));
		for (int queued = 0; queued < searchesToQueue; queued++) {
			if (!queueAsyncPrewarmedRespawnSearch(level, currentTick)) {
				break;
			}
		}
	}

	private static boolean queueAsyncPrewarmedRespawnSearch(ServerLevel level, long currentTick) {
		for (int attempt = 0; attempt < RESPAWN_SEARCH_ATTEMPTS; attempt++) {
			BlockPos center = pickRandomRespawnCenter(level).immutable();
			if (PENDING_PREWARMED_RESPAWN_SEARCHES.containsKey(center)) {
				continue;
			}

			retainPrewarmedRespawnChunk(level, center);
			PENDING_PREWARMED_RESPAWN_SEARCHES.put(center, currentTick + PREWARMED_RESPAWN_SEARCH_TIMEOUT_TICKS);
			return true;
		}
		return false;
	}

	private static void resolvePendingPrewarmedRespawnSearches(ServerLevel level, long currentTick) {
		var iterator = PENDING_PREWARMED_RESPAWN_SEARCHES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<BlockPos, Long> entry = iterator.next();
			BlockPos center = entry.getKey();
			if (!isRespawnSearchAreaLoaded(level, center)) {
				if (currentTick < entry.getValue()) {
					continue;
				}
				releasePrewarmedRespawnChunk(level, center);
				iterator.remove();
				continue;
			}

			BlockPos safeSpawn = findNearbyLoadedSafeRespawn(level, center);
			releasePrewarmedRespawnChunk(level, center);
			iterator.remove();
			if (safeSpawn != null) {
				addPrewarmedRespawn(level, safeSpawn.below());
			}
		}
	}

	private static BlockPos takePrewarmedRespawn(ServerLevel level) {
		if (PREWARMED_RESPAWNS.isEmpty()) {
			return null;
		}

		int attempts = PREWARMED_RESPAWNS.size();
		for (int checked = 0; checked < attempts && !PREWARMED_RESPAWNS.isEmpty(); checked++) {
			int index = level.random.nextInt(PREWARMED_RESPAWNS.size());
			BlockPos floorPos = PREWARMED_RESPAWNS.remove(index);
			PREWARMED_RESPAWN_KEYS.remove(floorPos.asLong());
			releasePrewarmedRespawnChunk(level, floorPos);
			if (isSafeRespawnPositionLoaded(level, floorPos)) {
				return floorPos.above();
			}
		}

		return null;
	}

	private static BlockPos getRandomBackroomsRespawnFloor(ServerLevel backrooms) {
		MinecraftServer server = backrooms.getServer();
		if (server != null) {
			resolvePendingPrewarmedRespawnSearches(backrooms, server.getTickCount());
		}

		BlockPos safeSpawn = takePrewarmedRespawn(backrooms);
		if (server != null) {
			queueAsyncPrewarmedRespawnSearches(backrooms, server.getTickCount(), 1, getDesiredPrewarmedRespawnTarget(server));
		}
		if (safeSpawn == null) {
			safeSpawn = findRandomLoadedSafeRespawn(backrooms);
		}
		if (safeSpawn == null) {
			safeSpawn = findRandomSafeRespawn(backrooms);
		}
		if (safeSpawn != null) {
			BlockPos floorPos = safeSpawn.below().immutable();
			holdRespawnChunkTemporarily(backrooms, floorPos);
			return floorPos;
		}

		BlockPos platformCenter = pickRandomRespawnCenter(backrooms);
		backrooms.getChunkAt(platformCenter);
		ensurePlatform(backrooms, platformCenter);
		holdRespawnChunkTemporarily(backrooms, platformCenter);
		return platformCenter;
	}

	private static BlockPos findRandomSafeRespawn(ServerLevel level) {
		for (int attempt = 0; attempt < RESPAWN_SEARCH_ATTEMPTS; attempt++) {
			BlockPos center = pickRandomRespawnCenter(level);
			level.getChunkAt(center);
			BlockPos safePos = findNearbySafeRespawn(level, center);
			if (safePos != null) {
				return safePos;
			}
		}

		return null;
	}

	private static boolean isRespawnSearchAreaLoaded(ServerLevel level, BlockPos center) {
		int radius = RESPAWN_SEARCH_RADIUS;
		int minChunkX = SectionPos.blockToSectionCoord(center.getX() - radius);
		int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + radius);
		int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
		int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				if (!level.hasChunk(chunkX, chunkZ)) {
					return false;
				}
			}
		}
		return true;
	}

	private static BlockPos findNearbyLoadedSafeRespawn(ServerLevel level, BlockPos center) {
		if (isSafeRespawnPositionLoaded(level, center)) {
			return center.above();
		}

		for (int radius = 1; radius <= RESPAWN_SEARCH_RADIUS; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int worldX = center.getX() + dx;

				BlockPos north = new BlockPos(worldX, center.getY(), center.getZ() - radius);
				if (isSafeRespawnPositionLoaded(level, north)) {
					return north.above();
				}

				BlockPos south = new BlockPos(worldX, center.getY(), center.getZ() + radius);
				if (isSafeRespawnPositionLoaded(level, south)) {
					return south.above();
				}
			}

			for (int dz = -radius + 1; dz <= radius - 1; dz++) {
				int worldZ = center.getZ() + dz;

				BlockPos west = new BlockPos(center.getX() - radius, center.getY(), worldZ);
				if (isSafeRespawnPositionLoaded(level, west)) {
					return west.above();
				}

				BlockPos east = new BlockPos(center.getX() + radius, center.getY(), worldZ);
				if (isSafeRespawnPositionLoaded(level, east)) {
					return east.above();
				}
			}
		}

		return null;
	}

	private static BlockPos findNearbySafeRespawn(ServerLevel level, BlockPos center) {
		if (isSafeRespawnPosition(level, center)) {
			return center.above();
		}

		for (int radius = 1; radius <= RESPAWN_SEARCH_RADIUS; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int worldX = center.getX() + dx;

				BlockPos north = new BlockPos(worldX, center.getY(), center.getZ() - radius);
				if (isSafeRespawnPosition(level, north)) {
					return north.above();
				}

				BlockPos south = new BlockPos(worldX, center.getY(), center.getZ() + radius);
				if (isSafeRespawnPosition(level, south)) {
					return south.above();
				}
			}

			for (int dz = -radius + 1; dz <= radius - 1; dz++) {
				int worldZ = center.getZ() + dz;

				BlockPos west = new BlockPos(center.getX() - radius, center.getY(), worldZ);
				if (isSafeRespawnPosition(level, west)) {
					return west.above();
				}

				BlockPos east = new BlockPos(center.getX() + radius, center.getY(), worldZ);
				if (isSafeRespawnPosition(level, east)) {
					return east.above();
				}
			}
		}

		return null;
	}

	private static boolean isSafeRespawnPosition(ServerLevel level, BlockPos floorPos) {
		if (!level.getBlockState(floorPos).isFaceSturdy(level, floorPos, Direction.UP)) {
			return false;
		}

		BlockPos feetPos = floorPos.above();
		BlockPos headPos = feetPos.above();
		if (!level.isEmptyBlock(feetPos) || !level.isEmptyBlock(headPos)) {
			return false;
		}

		return hasAdjacentOpenSpace(level, floorPos);
	}

	private static boolean isSafeRespawnPositionLoaded(ServerLevel level, BlockPos floorPos) {
		if (!level.hasChunkAt(floorPos)) {
			return false;
		}

		if (!level.getBlockState(floorPos).isFaceSturdy(level, floorPos, Direction.UP)) {
			return false;
		}

		BlockPos feetPos = floorPos.above();
		BlockPos headPos = feetPos.above();
		if (!level.hasChunkAt(feetPos) || !level.hasChunkAt(headPos)) {
			return false;
		}
		if (!level.isEmptyBlock(feetPos) || !level.isEmptyBlock(headPos)) {
			return false;
		}

		return hasAdjacentOpenSpaceLoaded(level, floorPos);
	}

	private static boolean hasAdjacentOpenSpace(ServerLevel level, BlockPos floorPos) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos adjacentFloor = floorPos.relative(direction);
			if (!level.getBlockState(adjacentFloor).isFaceSturdy(level, adjacentFloor, Direction.UP)) {
				continue;
			}

			BlockPos adjacentFeet = adjacentFloor.above();
			BlockPos adjacentHead = adjacentFeet.above();
			if (level.isEmptyBlock(adjacentFeet) && level.isEmptyBlock(adjacentHead)) {
				return true;
			}
		}

		return false;
	}

	private static boolean hasAdjacentOpenSpaceLoaded(ServerLevel level, BlockPos floorPos) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos adjacentFloor = floorPos.relative(direction);
			BlockPos adjacentFeet = adjacentFloor.above();
			BlockPos adjacentHead = adjacentFeet.above();
			if (!level.hasChunkAt(adjacentFloor) || !level.hasChunkAt(adjacentFeet) || !level.hasChunkAt(adjacentHead)) {
				continue;
			}
			if (!level.getBlockState(adjacentFloor).isFaceSturdy(level, adjacentFloor, Direction.UP)) {
				continue;
			}
			if (level.isEmptyBlock(adjacentFeet) && level.isEmptyBlock(adjacentHead)) {
				return true;
			}
		}

		return false;
	}

	private static BlockPos pickRandomRespawnCenter(ServerLevel level) {
		int x = Mth.nextInt(level.random, RESPAWN_MIN_COORD, RESPAWN_MAX_COORD);
		int z = Mth.nextInt(level.random, RESPAWN_MIN_COORD, RESPAWN_MAX_COORD);
		int minLevelIndex = getBackroomsLevelIndex(level.getMinY());
		int maxLevelIndex = getBackroomsLevelIndex(level.getMaxY() - 1);
		int levelIndex = Mth.nextInt(level.random, minLevelIndex, maxLevelIndex);
		int floorY = BACKROOMS_LEVEL_FLOOR_Y + levelIndex * BACKROOMS_LEVEL_HEIGHT;
		return new BlockPos(x, floorY, z);
	}

	private static void retainPrewarmedRespawnChunk(ServerLevel level, BlockPos floorPos) {
		ChunkPos chunkPos = new ChunkPos(floorPos);
		long key = chunkPos.toLong();
		int refCount = PREWARMED_RESPAWN_CHUNK_REFS.getOrDefault(key, 0);
		if (refCount == 0) {
			level.getChunkSource().addTicketWithRadius(TicketType.UNKNOWN, chunkPos, PREWARMED_RESPAWN_CHUNK_RADIUS);
		}
		PREWARMED_RESPAWN_CHUNK_REFS.put(key, refCount + 1);
	}

	private static void releasePrewarmedRespawnChunk(ServerLevel level, BlockPos floorPos) {
		ChunkPos chunkPos = new ChunkPos(floorPos);
		long key = chunkPos.toLong();
		Integer refCount = PREWARMED_RESPAWN_CHUNK_REFS.get(key);
		if (refCount == null) {
			return;
		}
		if (refCount <= 1) {
			PREWARMED_RESPAWN_CHUNK_REFS.remove(key);
			level.getChunkSource().removeTicketWithRadius(TicketType.UNKNOWN, chunkPos, PREWARMED_RESPAWN_CHUNK_RADIUS);
			return;
		}
		PREWARMED_RESPAWN_CHUNK_REFS.put(key, refCount - 1);
	}

	private static void holdRespawnChunkTemporarily(ServerLevel level, BlockPos floorPos) {
		ChunkPos chunkPos = new ChunkPos(floorPos);
		BlockPos holdKey = new BlockPos(chunkPos.getMinBlockX(), BACKROOMS_LEVEL_FLOOR_Y, chunkPos.getMinBlockZ());
		long expiryTick = level.getGameTime() + ACTIVE_RESPAWN_CHUNK_HOLD_TICKS;
		Long previousExpiry = ACTIVE_RESPAWN_CHUNK_HOLDS.put(holdKey, expiryTick);
		if (previousExpiry == null) {
			retainPrewarmedRespawnChunk(level, holdKey);
		} else if (previousExpiry > expiryTick) {
			ACTIVE_RESPAWN_CHUNK_HOLDS.put(holdKey, previousExpiry);
		}
	}

	private static void tickActiveRespawnChunkHolds(MinecraftServer server) {
		if (ACTIVE_RESPAWN_CHUNK_HOLDS.isEmpty()) {
			return;
		}

		ServerLevel backrooms = server.getLevel(BACKROOMS_LEVEL);
		if (backrooms == null) {
			ACTIVE_RESPAWN_CHUNK_HOLDS.clear();
			return;
		}

		long gameTime = backrooms.getGameTime();
		var iterator = ACTIVE_RESPAWN_CHUNK_HOLDS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<BlockPos, Long> entry = iterator.next();
			if (gameTime < entry.getValue()) {
				continue;
			}
			releasePrewarmedRespawnChunk(backrooms, entry.getKey());
			iterator.remove();
		}
	}

	private static int getBackroomsLevelIndex(int y) {
		return Math.floorDiv(y - BACKROOMS_LEVEL_FLOOR_Y, BACKROOMS_LEVEL_HEIGHT);
	}

	private static void ensurePlatform(ServerLevel level, BlockPos center) {
		for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
			for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
				BlockPos floorPos = center.offset(dx, 0, dz);
				level.setBlockAndUpdate(floorPos, ModBlocks.getRandomizedBackroomsBlockState(floorPos.asLong()));
				for (int dy = 1; dy <= PLATFORM_CLEAR_HEIGHT; dy++) {
					level.setBlockAndUpdate(floorPos.above(dy), Blocks.AIR.defaultBlockState());
				}
			}
		}

		for (Direction direction : Direction.Plane.HORIZONTAL) {
			carvePlatformExit(level, center, direction);
		}
	}

	private static void carvePlatformExit(ServerLevel level, BlockPos center, Direction direction) {
		for (int step = PLATFORM_RADIUS + 1; step <= PLATFORM_RADIUS + PLATFORM_EXIT_LENGTH; step++) {
			BlockPos floorPos = center.relative(direction, step);
			level.setBlockAndUpdate(floorPos, ModBlocks.getRandomizedBackroomsBlockState(floorPos.asLong()));
			for (int dy = 1; dy <= PLATFORM_CLEAR_HEIGHT; dy++) {
				level.setBlockAndUpdate(floorPos.above(dy), Blocks.AIR.defaultBlockState());
			}
		}
	}

	private static final class ReturnStateFile {
		Map<String, ReturnPointState> players = new LinkedHashMap<>();
	}

	private static final class ReturnPointState {
		String dimension;
		double x;
		double y;
		double z;
		float yaw;
		float pitch;
	}

	private static final class AmbientLoopState {
		long nextPlayTick;
		int fadeStep;
		int voltageDipTicks;
		int voltageDipLoopsRemaining;
		long nextLampFlickerTick;
	}
}
