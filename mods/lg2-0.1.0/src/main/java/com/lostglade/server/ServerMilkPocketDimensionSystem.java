package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.block.ModBlocks;
import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import com.lostglade.config.RaceConfig.RaceAbilitySlot;
import com.lostglade.network.Lg2Payloads;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ServerMilkPocketDimensionSystem {
	public static final ResourceKey<Level> MILK_POCKET_LEVEL = ResourceKey.create(
			Registries.DIMENSION,
			Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "milk_pocket")
	);

	private static final Gson STATE_GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String STATE_FILE_NAME = "lg2-milk-pocket.json";
	private static final String ENTER_WORD = "\u0421\u0443\u043d\u0443\u0440\u043f";
	private static final String EXIT_WORD = "\u0421\u0443\u043b\u0430\u043c";
	private static final String MILK_OLIGARCH_RACE_ID = "milk_oligarch";
	private static final Set<Relative> ABSOLUTE_TELEPORT = EnumSet.noneOf(Relative.class);
	private static final BlockPos SPAWN_POS = new BlockPos(0, 65, 0);
	private static final BlockPos PHANTOM_FLOOR_POS = new BlockPos(0, 64, 0);
	private static final int BUILD_RADIUS_XZ = 7;
	private static final int BUILD_MIN_Y = 64;
	private static final int BUILD_MAX_Y = 83;
	private static final int ENTITY_MAX_Y = BUILD_MAX_Y + 1;
	private static final int MILK_POCKET_CHUNK_VIEW_RADIUS = 2;
	private static final long MILK_POCKET_RETURN_MOTION_RESYNC_TICKS = 4L;
	private static final long MILK_POCKET_JOIN_EXIT_DELAY_TICKS = 10L;
	private static final int VOID_WRAP_Y = -64;
	private static final int VOID_WRAP_TARGET_Y = 320;
	private static final double VOID_FADE_START_Y = -24.0D;
	private static final double VOID_WRAP_PREPARE_Y = -44.0D;
	private static final double VOID_WRAP_PENDING_MAX_FALL_SPEED = -0.12D;
	private static final float VOID_FADE_MAX_ALPHA = 1.0F;
	private static final long VOID_WRAP_DELAY_TICKS = 12L;
	private static final long VOID_FADE_CLEAR_DELAY_TICKS = 14L;
	private static final int BEDROCK_CLEANUP_INTERVAL_TICKS = 20;
	private static final int OUTSIDE_BLOCK_CLEANUP_INTERVAL_TICKS = 20;
	private static final int OUTSIDE_BLOCK_CLEANUP_RADIUS_XZ = BUILD_RADIUS_XZ + 16;
	private static final int OUTSIDE_BLOCK_CLEANUP_MIN_Y = -4;
	private static final int OUTSIDE_BLOCK_CLEANUP_MAX_Y = BUILD_MAX_Y + 16;
	private static final int LEGACY_PLATFORM_CLEANUP_RADIUS_XZ = 8;
	private static final int LEGACY_PLATFORM_CLEANUP_MIN_Y = -64;
	private static final int LEGACY_PLATFORM_CLEANUP_MAX_Y = 0;
	private static final double DEFAULT_RECENT_DAMAGE_LOCK_SECONDS = 10.0D;
	private static final int MILK_POCKET_SELF_TELEPORT_PARTICLE_TICKS = 4;
	private static final Set<UUID> ACCESS_PLAYERS = new HashSet<>();
	private static final Map<UUID, ReturnPoint> RETURN_POINTS = new HashMap<>();
	private static final Map<UUID, Long> LAST_DAMAGE_TICKS = new HashMap<>();
	private static final Map<UUID, Float> VOID_FADE_ALPHA_BY_PLAYER = new HashMap<>();
	private static final Map<UUID, Long> VOID_FADE_CLEAR_AT_TICKS = new HashMap<>();
	private static final Map<UUID, Long> VOID_WRAP_AT_TICKS = new HashMap<>();
	private static final Map<UUID, Long> MILK_POCKET_RETURN_MOTION_RESYNC_UNTIL_TICKS = new HashMap<>();
	private static final Map<UUID, Long> MILK_POCKET_PENDING_JOIN_EXITS = new HashMap<>();
	private static final Map<UUID, Integer> MILK_POCKET_SELF_TELEPORT_PARTICLES = new HashMap<>();
	private static final Set<UUID> FIRST_LANDING_FALL_PROTECTED_PLAYERS = new HashSet<>();

	private static boolean stateLoaded = false;
	private static boolean stateDirty = false;
	private static boolean legacyPlatformCleaned = false;

	private ServerMilkPocketDimensionSystem() {
	}

	public static void register() {
		ACCESS_PLAYERS.clear();
		RETURN_POINTS.clear();
		LAST_DAMAGE_TICKS.clear();
		VOID_FADE_ALPHA_BY_PLAYER.clear();
		VOID_FADE_CLEAR_AT_TICKS.clear();
		VOID_WRAP_AT_TICKS.clear();
		MILK_POCKET_RETURN_MOTION_RESYNC_UNTIL_TICKS.clear();
		MILK_POCKET_PENDING_JOIN_EXITS.clear();
		MILK_POCKET_SELF_TELEPORT_PARTICLES.clear();
		FIRST_LANDING_FALL_PROTECTED_PLAYERS.clear();
		stateLoaded = false;
		stateDirty = false;
		legacyPlatformCleaned = false;

		ServerLifecycleEvents.SERVER_STARTED.register(ServerMilkPocketDimensionSystem::loadState);
		ServerLifecycleEvents.SERVER_STOPPING.register(ServerMilkPocketDimensionSystem::saveState);
		ServerTickEvents.END_SERVER_TICK.register(ServerMilkPocketDimensionSystem::tickServer);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				server.execute(() -> queueJoinExit((ServerPlayer) handler.player, server.getTickCount())));
		UseBlockCallback.EVENT.register(ServerMilkPocketDimensionSystem::onUseBlock);
		PlayerBlockBreakEvents.BEFORE.register(ServerMilkPocketDimensionSystem::beforeBlockBreak);

	}

	public static boolean isMilkPocket(Level level) {
		return level != null && isMilkPocket(level.dimension());
	}

	public static boolean isMilkPocket(ResourceKey<Level> dimension) {
		return MILK_POCKET_LEVEL.equals(dimension);
	}

	public static boolean shouldUsePocketChunkTracking(ServerPlayer player) {
		return player != null && isMilkPocket(player.level());
	}

	public static ChunkTrackingView createPocketChunkTrackingView(ServerPlayer player) {
		if (player == null) {
			return ChunkTrackingView.EMPTY;
		}
		return ChunkTrackingView.of(player.chunkPosition(), MILK_POCKET_CHUNK_VIEW_RADIUS);
	}

	public static boolean handleChatMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params) {
		if (message == null || sender == null) {
			return false;
		}

		String content = message.signedContent() == null ? "" : message.signedContent().trim();
		return handleChatContent(content, sender);
	}

	public static boolean handleChatContent(String content, ServerPlayer actor) {
		if (actor == null) {
			return false;
		}
		if (matchesPocketWord(content, ENTER_WORD)) {
			return tryEnterFromChat(actor);
		}
		if (matchesPocketWord(content, EXIT_WORD)) {
			return tryExitFromChat(actor);
		}
		return false;
	}

	public static boolean handleGlitchedChatContent(String content, ServerPlayer conditionPlayer, ServerPlayer actor) {
		if (conditionPlayer == null || actor == null) {
			return false;
		}
		if (matchesPocketWord(content, ENTER_WORD)) {
			return tryEnterFromChat(actor, conditionPlayer);
		}
		if (matchesPocketWord(content, EXIT_WORD)) {
			return tryExitFromChat(actor, conditionPlayer);
		}
		return false;
	}

	private static boolean matchesPocketWord(String content, String expected) {
		return content != null && expected != null && content.trim().equalsIgnoreCase(expected);
	}

	public static boolean invitePlayer(ServerPlayer owner, ServerPlayer target) {
		if (owner == null || target == null || target.isSpectator()) {
			return false;
		}
		if (!canOpenPocketAsOwner(owner)) {
			return false;
		}

		ACCESS_PLAYERS.add(target.getUUID());
		stateDirty = true;
		owner.displayClientMessage(
				Component.literal("Вы пригласили " + target.getGameProfile().name() + ".")
						.withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE).withItalic(false)),
				true
		);
		sendPersonalSound(owner, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.75F, 1.35F);
		return true;
	}

	public static boolean canOpenPocketAsOwner(ServerPlayer player) {
		if (player == null || player.isSpectator()) {
			return false;
		}
		return ServerRaceSystem.getRace(player)
				.map(race -> MILK_OLIGARCH_RACE_ID.equals(sanitizeRaceId(race.id))
						&& ServerRaceSystem.hasUnlockedAbility(player, RaceAbilitySlot.SHNYAGA))
				.orElse(false);
	}

	public static boolean hasPocketAccess(ServerPlayer player) {
		return player != null && (canOpenPocketAsOwner(player) || ACCESS_PLAYERS.contains(player.getUUID()));
	}

	public static void recordPlayerDamage(ServerPlayer player) {
		MinecraftServer server = player == null ? null : player.level().getServer();
		if (server == null) {
			return;
		}
		LAST_DAMAGE_TICKS.put(player.getUUID(), (long) server.getTickCount());
	}

	public static boolean isPhantomFloorBlockPlacement(BlockPlaceContext context) {
		if (context == null || !(context.getLevel() instanceof ServerLevel level) || !isMilkPocket(level)) {
			return false;
		}
		BlockPos pos = context.getClickedPos();
		return pos != null && level.getBlockState(pos).is(ModBlocks.MILK_POCKET_PHANTOM_FLOOR);
	}

	public static boolean canPlaceBlockOnPhantomFloor(BlockPlaceContext context, BlockState placementState) {
		if (context == null || placementState == null || !(context.getLevel() instanceof ServerLevel level) || !isMilkPocket(level)) {
			return true;
		}

		BlockPos pos = context.getClickedPos();
		if (pos == null || !level.getBlockState(pos).is(ModBlocks.MILK_POCKET_PHANTOM_FLOOR)) {
			return true;
		}
		if (!isInsideBuildZone(pos) || !context.canPlace() || !placementState.canSurvive(level, pos)) {
			return false;
		}

		for (BlockPos extraPos : getExtraPlacementPositions(pos, placementState)) {
			if (!isInsideBuildZone(extraPos)) {
				return false;
			}
			BlockState extraState = level.getBlockState(extraPos);
			if (!extraState.isAir() && !extraState.canBeReplaced()) {
				return false;
			}
		}

		return true;
	}

	public static boolean canPlaceBlockInBuildZone(BlockPlaceContext context, BlockState placementState) {
		if (context == null || placementState == null || !isMilkPocket(context.getLevel())) {
			return true;
		}

		BlockPos placementPos = context.getClickedPos();
		if (!isInsideBuildZone(placementPos)) {
			return false;
		}

		for (BlockPos extraPos : getExtraPlacementPositions(placementPos, placementState)) {
			if (!isInsideBuildZone(extraPos)) {
				return false;
			}
		}
		return true;
	}

	public static boolean shouldRejectBlockStateWrite(Level level, BlockPos pos, BlockState state) {
		if (level == null || pos == null || state == null || !isMilkPocket(level) || state.isAir()) {
			return false;
		}
		return !isInsideBuildZone(pos);
	}

	public static boolean shouldReplaceAirWithPhantomFloor(Level level, BlockPos pos, BlockState state) {
		return level != null
				&& pos != null
				&& state != null
				&& isMilkPocket(level)
				&& PHANTOM_FLOOR_POS.equals(pos)
				&& state.isAir();
	}

	public static boolean shouldRejectPistonMove(
			Level level,
			BlockPos pistonPos,
			Direction pistonDirection,
			Direction pushDirection,
			Collection<BlockPos> toPush,
			Collection<BlockPos> toDestroy
	) {
		if (level == null || !isMilkPocket(level)) {
			return false;
		}
		if (pistonPos != null && !isInsideBuildZone(pistonPos)) {
			return true;
		}
		if (pistonPos != null && pistonDirection != null && !isInsideBuildZone(pistonPos.relative(pistonDirection))) {
			return true;
		}
		if (toPush != null) {
			for (BlockPos pos : toPush) {
				if (pos == null || !isInsideBuildZone(pos)) {
					return true;
				}
				if (pushDirection != null && !isInsideBuildZone(pos.relative(pushDirection))) {
					return true;
				}
			}
		}
		if (toDestroy != null) {
			for (BlockPos pos : toDestroy) {
				if (pos == null || !isInsideBuildZone(pos)) {
					return true;
				}
			}
		}
		return false;
	}

	private static Set<BlockPos> getExtraPlacementPositions(BlockPos pos, BlockState placementState) {
		Set<BlockPos> positions = new HashSet<>();
		if (pos == null || placementState == null) {
			return positions;
		}

		if (placementState.hasProperty(BlockStateProperties.BED_PART)
				&& placementState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			Direction facing = placementState.getValue(BlockStateProperties.HORIZONTAL_FACING);
			BedPart part = placementState.getValue(BlockStateProperties.BED_PART);
			positions.add(part == BedPart.FOOT ? pos.relative(facing) : pos.relative(facing.getOpposite()));
		}

		if (placementState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			DoubleBlockHalf half = placementState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
			positions.add(half == DoubleBlockHalf.LOWER ? pos.above() : pos.below());
		}

		positions.remove(pos);
		return positions;
	}

	private static BlockPos resolvePlacementPos(BlockPlaceContext context) {
		if (context == null) {
			return null;
		}
		Level level = context.getLevel();
		BlockPos clickedPos = context.getClickedPos();
		if (level == null || clickedPos == null) {
			return null;
		}
		if (level.getBlockState(clickedPos).canBeReplaced(context)) {
			return clickedPos;
		}
		return clickedPos.relative(context.getClickedFace());
	}

	private static boolean tryEnterFromChat(ServerPlayer player) {
		return tryEnterFromChat(player, player);
	}

	private static boolean tryEnterFromChat(ServerPlayer actor, ServerPlayer conditionPlayer) {
		if (!hasPocketAccess(conditionPlayer)) {
			return false;
		}
		if (isMilkPocket(actor.level())) {
			return true;
		}
		long remainingTicks = getRecentDamageLockRemainingTicks(conditionPlayer);
		if (remainingTicks > 0L) {
			displayRecentDamageLock(conditionPlayer, remainingTicks);
			return true;
		}
		return teleportToPocket(actor);
	}

	private static boolean tryExitFromChat(ServerPlayer player) {
		return tryExitFromChat(player, player);
	}

	private static boolean tryExitFromChat(ServerPlayer actor, ServerPlayer conditionPlayer) {
		if (!hasPocketAccess(conditionPlayer)) {
			return false;
		}
		if (!isMilkPocket(actor.level())) {
			return false;
		}
		teleportFromPocket(actor);
		return true;
	}

	private static boolean teleportToPocket(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return false;
		}

		ServerLevel pocket = server.getLevel(MILK_POCKET_LEVEL);
		if (pocket == null) {
			player.displayClientMessage(
					Component.literal("Карманное измерение не загружено.")
							.withStyle(style -> style.withColor(ChatFormatting.RED).withItalic(false)),
					true
			);
			return true;
		}

		ReturnPoint entryState = storeReturnPoint(player);
		playPocketTeleportOriginEffect(player);
		preparePocketSpawn(pocket);
		BlockPos spawnPos = resolvePocketSpawn(pocket);
		pocket.getChunkAt(spawnPos);
		player.teleportTo(
				pocket,
				spawnPos.getX() + 0.5D,
				spawnPos.getY(),
				spawnPos.getZ() + 0.5D,
				ABSOLUTE_TELEPORT,
				player.getYRot(),
				player.getXRot(),
				false
		);
		restoreReturnPhysicalState(player, entryState, false);
		clearMilkPocketVoidFade(player);
		playPocketTeleportArrivalEffect(player);
		playPocketTeleportPersonalSound(player);
		return true;
	}

	private static void teleportFromPocket(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}

		ReturnPoint returnPoint = RETURN_POINTS.remove(player.getUUID());
		stateDirty = true;
		ServerLevel targetLevel = resolveReturnLevel(server, returnPoint);
		BlockPos spawnPos = targetLevel.getRespawnData().pos();
		double x = returnPoint == null ? spawnPos.getX() + 0.5D : returnPoint.x;
		double y = returnPoint == null ? spawnPos.getY() + 0.1D : returnPoint.y;
		double z = returnPoint == null ? spawnPos.getZ() + 0.5D : returnPoint.z;
		float yaw = returnPoint == null ? 0.0F : returnPoint.yaw;
		float pitch = returnPoint == null ? 0.0F : returnPoint.pitch;
		playPocketTeleportOriginEffect(player);
		targetLevel.getChunkAt(BlockPos.containing(x, y, z));
		FIRST_LANDING_FALL_PROTECTED_PLAYERS.remove(player.getUUID());
		player.teleportTo(targetLevel, x, y, z, ABSOLUTE_TELEPORT, yaw, pitch, false);
		restoreReturnPhysicalState(player, returnPoint, true);
		scheduleMilkPocketReturnMotionResync(player);
		clearMilkPocketVoidFade(player);
		playPocketTeleportArrivalEffect(player);
		playPocketTeleportPersonalSound(player);
	}

	private static void playPocketTeleportOriginEffect(ServerPlayer player) {
		if (player == null || !(player.level() instanceof ServerLevel level)) {
			return;
		}

		double centerX = player.getX();
		double centerY = player.getY() + Math.max(0.35D, player.getBbHeight() * 0.5D);
		double centerZ = player.getZ();
		double spreadX = Math.max(0.18D, player.getBbWidth() * 0.4D);
		double spreadY = Math.max(0.22D, player.getBbHeight() * 0.28D);
		double spreadZ = Math.max(0.18D, player.getBbWidth() * 0.4D);
		level.sendParticles(ParticleTypes.SMOKE, centerX, centerY, centerZ, 18, spreadX, spreadY, spreadZ, 0.01D);
		level.sendParticles(player, ParticleTypes.SMOKE, false, false, centerX, centerY, centerZ, 18, spreadX, spreadY, spreadZ, 0.01D);

		float volume = 0.55F;
		float pitch = 1.15F;
		sendLocalPlayerSoundExcept(level, player, new Vec3(centerX, centerY, centerZ), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, volume, pitch, 8.0D);
	}

	private static void playPocketTeleportPersonalSound(ServerPlayer player) {
		if (player == null) {
			return;
		}
		sendPersonalSound(player, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.55F, 1.15F);
		MILK_POCKET_SELF_TELEPORT_PARTICLES.put(player.getUUID(), MILK_POCKET_SELF_TELEPORT_PARTICLE_TICKS);
	}

	private static void playPocketTeleportArrivalEffect(ServerPlayer player) {
		if (player == null || !(player.level() instanceof ServerLevel level)) {
			return;
		}

		double centerX = player.getX();
		double centerY = player.getY() + Math.max(0.35D, player.getBbHeight() * 0.5D);
		double centerZ = player.getZ();
		double spreadX = Math.max(0.18D, player.getBbWidth() * 0.4D);
		double spreadY = Math.max(0.22D, player.getBbHeight() * 0.28D);
		double spreadZ = Math.max(0.18D, player.getBbWidth() * 0.4D);
		level.sendParticles(ParticleTypes.SMOKE, centerX, centerY, centerZ, 18, spreadX, spreadY, spreadZ, 0.01D);
		level.sendParticles(player, ParticleTypes.SMOKE, false, false, centerX, centerY, centerZ, 18, spreadX, spreadY, spreadZ, 0.01D);
		sendLocalPlayerSoundExcept(level, player, new Vec3(centerX, centerY, centerZ), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.55F, 1.15F, 8.0D);
	}

	private static void tickPocketSelfTeleportParticles(MinecraftServer server) {
		if (server == null || MILK_POCKET_SELF_TELEPORT_PARTICLES.isEmpty()) {
			return;
		}

		Iterator<Map.Entry<UUID, Integer>> iterator = MILK_POCKET_SELF_TELEPORT_PARTICLES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Integer> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			int ticksLeft = entry.getValue() == null ? 0 : entry.getValue();
			if (player == null || player.connection == null || !player.isAlive() || ticksLeft <= 0) {
				iterator.remove();
				continue;
			}
			sendPocketTeleportSelfParticles(player);
			if (ticksLeft <= 1) {
				iterator.remove();
			} else {
				entry.setValue(ticksLeft - 1);
			}
		}
	}

	private static void sendPocketTeleportSelfParticles(ServerPlayer player) {
		if (player == null || !(player.level() instanceof ServerLevel level)) {
			return;
		}

		double centerX = player.getX();
		double centerY = player.getY() + Math.max(0.35D, player.getBbHeight() * 0.5D);
		double centerZ = player.getZ();
		double spreadX = Math.max(0.18D, player.getBbWidth() * 0.4D);
		double spreadY = Math.max(0.22D, player.getBbHeight() * 0.28D);
		double spreadZ = Math.max(0.18D, player.getBbWidth() * 0.4D);
		level.sendParticles(player, ParticleTypes.SMOKE, false, false, centerX, centerY, centerZ, 18, spreadX, spreadY, spreadZ, 0.01D);
	}

	private static long getRecentDamageLockRemainingTicks(ServerPlayer player) {
		MinecraftServer server = player == null ? null : player.level().getServer();
		if (server == null) {
			return 0L;
		}
		long lockTicks = getRecentDamageLockTicks();
		if (lockTicks <= 0L) {
			return 0L;
		}
		Long lastDamageTick = LAST_DAMAGE_TICKS.get(player.getUUID());
		if (lastDamageTick == null) {
			return 0L;
		}
		long remainingTicks = (lastDamageTick + lockTicks) - server.getTickCount();
		if (remainingTicks <= 0L) {
			LAST_DAMAGE_TICKS.remove(player.getUUID());
			return 0L;
		}
		return remainingTicks;
	}

	private static long getRecentDamageLockTicks() {
		RaceAbilityConfig ability = ServerRaceSystem.getAbilityByRaceId(MILK_OLIGARCH_RACE_ID, RaceAbilitySlot.SHNYAGA).orElse(null);
		double seconds = positiveOrDefault(
				ability == null ? 0.0D : ability.milkPocketRecentDamageLockSeconds,
				DEFAULT_RECENT_DAMAGE_LOCK_SECONDS
		);
		return Math.max(0L, Math.round(seconds * 20.0D));
	}

	private static void displayRecentDamageLock(ServerPlayer player, long remainingTicks) {
		double remainingSeconds = Math.max(0.1D, remainingTicks / 20.0D);
		player.displayClientMessage(
				Component.literal(String.format(Locale.ROOT, "%.1fs", remainingSeconds))
						.withStyle(style -> style.withColor(ChatFormatting.RED).withItalic(false)),
				true
		);
	}

	private static double positiveOrDefault(double value, double defaultValue) {
		if (Double.isNaN(value) || value <= 0.0D) {
			return defaultValue;
		}
		return value;
	}

	private static ServerLevel resolveReturnLevel(MinecraftServer server, ReturnPoint returnPoint) {
		if (returnPoint != null && returnPoint.dimension != null && !returnPoint.dimension.isBlank()) {
			Identifier id = Identifier.tryParse(returnPoint.dimension);
			if (id != null) {
				ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
				if (level != null && !isMilkPocket(level)) {
					return level;
				}
			}
		}
		return server.overworld();
	}

	private static ReturnPoint storeReturnPoint(ServerPlayer player) {
		Vec3 velocity = player.getDeltaMovement();
		ReturnPoint returnPoint = new ReturnPoint(
				player.level().dimension().identifier().toString(),
				player.getX(),
				player.getY(),
				player.getZ(),
				player.getYRot(),
				player.getXRot(),
				player.getYHeadRot(),
				player.yBodyRot,
				velocity.x,
				velocity.y,
				velocity.z,
				player.fallDistance,
				player.isSprinting(),
				player.isShiftKeyDown()
		);
		RETURN_POINTS.put(player.getUUID(), returnPoint);
		stateDirty = true;
		return returnPoint;
	}

	private static void restoreReturnPhysicalState(ServerPlayer player, ReturnPoint returnPoint, boolean restoreFallDistance) {
		if (player == null) {
			return;
		}
		if (returnPoint == null) {
			player.hurtMarked = true;
			player.fallDistance = 0.0F;
			return;
		}

		if (returnPoint.physicalStateStored) {
			player.setDeltaMovement(returnPoint.velocityX, returnPoint.velocityY, returnPoint.velocityZ);
			player.setSprinting(returnPoint.sprinting);
			player.setShiftKeyDown(returnPoint.shiftKeyDown);
			player.fallDistance = restoreFallDistance ? Math.max(0.0D, returnPoint.fallDistance) : 0.0D;
		} else {
			player.fallDistance = 0.0F;
		}
		float headYaw = returnPoint.physicalStateStored ? returnPoint.headYaw : returnPoint.yaw;
		float bodyYaw = returnPoint.physicalStateStored ? returnPoint.bodyYaw : returnPoint.yaw;
		player.setYHeadRot(headYaw);
		player.setYBodyRot(bodyYaw);
		player.yHeadRotO = headYaw;
		player.yBodyRotO = bodyYaw;
		player.hurtMarked = true;
		if (player.connection != null) {
			player.connection.send(new ClientboundSetEntityMotionPacket(player));
		}
	}

	private static void queueJoinExit(ServerPlayer player, long nowTick) {
		if (player == null || !player.isAlive() || player.isSpectator() || !isMilkPocket(player.level())) {
			return;
		}
		if (!hasPocketAccess(player)) {
			return;
		}
		MILK_POCKET_PENDING_JOIN_EXITS.put(player.getUUID(), nowTick + MILK_POCKET_JOIN_EXIT_DELAY_TICKS);
	}

	private static void tickPendingJoinExits(MinecraftServer server) {
		if (server == null || MILK_POCKET_PENDING_JOIN_EXITS.isEmpty()) {
			return;
		}

		long nowTick = server.getTickCount();
		Iterator<Map.Entry<UUID, Long>> iterator = MILK_POCKET_PENDING_JOIN_EXITS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Long> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null || !player.isAlive() || player.isSpectator()) {
				iterator.remove();
				continue;
			}
			if (!isMilkPocket(player.level()) || !hasPocketAccess(player)) {
				iterator.remove();
				continue;
			}
			if (nowTick < entry.getValue()) {
				continue;
			}
			teleportFromPocket(player);
			iterator.remove();
		}
	}

	private static void preparePocketSpawn(ServerLevel level) {
		ensurePhantomFloor(level);
		removeBlocksOutsideBuildZone(level);
	}

	private static BlockPos resolvePocketSpawn(ServerLevel level) {
		if (isPocketSpawnSafe(level, SPAWN_POS)) {
			return SPAWN_POS;
		}

		for (int y = ENTITY_MAX_Y; y >= SPAWN_POS.getY(); y--) {
			BlockPos pos = new BlockPos(0, y, 0);
			if (isPocketSpawnSafe(level, pos)) {
				return pos;
			}
		}
		return SPAWN_POS;
	}

	private static boolean isPocketSpawnSafe(ServerLevel level, BlockPos feetPos) {
		if (level == null || feetPos == null) {
			return false;
		}
		if (!isSpawnSpacePassable(level, feetPos) || !isSpawnSpacePassable(level, feetPos.above())) {
			return false;
		}

		BlockPos supportPos = feetPos.below();
		BlockState support = level.getBlockState(supportPos);
		return !support.getCollisionShape(level, supportPos).isEmpty() || !support.getFluidState().isEmpty();
	}

	private static boolean isSpawnSpacePassable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.getCollisionShape(level, pos).isEmpty();
	}

	private static void tickServer(MinecraftServer server) {
		if (stateDirty) {
			saveState(server);
		}
		cleanupExpiredDamageLocks(server);
		tickMilkPocketReturnMotionResync(server);
		tickPocketSelfTeleportParticles(server);
		tickPendingJoinExits(server);
		tickFirstLandingFallProtection(server);

		ServerLevel pocket = server.getLevel(MILK_POCKET_LEVEL);
		if (pocket == null) {
			return;
		}

		pocket.getChunkAt(PHANTOM_FLOOR_POS);
		if (!legacyPlatformCleaned) {
			cleanupLegacyPocketPlatform(pocket);
			legacyPlatformCleaned = true;
			stateDirty = true;
		}
		ensurePhantomFloor(pocket);
		if (server.getTickCount() % BEDROCK_CLEANUP_INTERVAL_TICKS == 0) {
			removeBedrockInBuildZone(pocket);
		}
		if (server.getTickCount() % OUTSIDE_BLOCK_CLEANUP_INTERVAL_TICKS == 0) {
			removeBlocksOutsideBuildZone(pocket);
		}
		boolean spawnPreparedForVoidWrap = false;
		long nowTick = server.getTickCount();
		for (Entity entity : pocket.getAllEntities()) {
			if (entity == null || entity.isRemoved()) {
				continue;
			}
			if (entity instanceof ServerPlayer player) {
				if (player.getY() <= VOID_WRAP_PREPARE_Y || VOID_WRAP_AT_TICKS.containsKey(player.getUUID())) {
					if (!spawnPreparedForVoidWrap) {
						preparePocketSpawn(pocket);
						spawnPreparedForVoidWrap = true;
					}
					tickMilkPocketPlayerVoidWrap(pocket, player, nowTick);
				}
				continue;
			}
			if (entity.getY() <= VOID_WRAP_Y) {
				if (!spawnPreparedForVoidWrap) {
					preparePocketSpawn(pocket);
					spawnPreparedForVoidWrap = true;
				}
				wrapMilkPocketFallingEntity(pocket, entity);
			}
		}
		tickMilkPocketVoidFade(server);
	}

	private static void scheduleMilkPocketReturnMotionResync(ServerPlayer player) {
		MinecraftServer server = player == null ? null : player.level().getServer();
		if (server == null || player.connection == null) {
			return;
		}
		MILK_POCKET_RETURN_MOTION_RESYNC_UNTIL_TICKS.put(
				player.getUUID(),
				server.getTickCount() + MILK_POCKET_RETURN_MOTION_RESYNC_TICKS
		);
	}

	private static void tickMilkPocketReturnMotionResync(MinecraftServer server) {
		if (server == null || MILK_POCKET_RETURN_MOTION_RESYNC_UNTIL_TICKS.isEmpty()) {
			return;
		}

		long nowTick = server.getTickCount();
		Iterator<Map.Entry<UUID, Long>> iterator = MILK_POCKET_RETURN_MOTION_RESYNC_UNTIL_TICKS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Long> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null || player.connection == null || isMilkPocket(player.level()) || nowTick > entry.getValue()) {
				iterator.remove();
				continue;
			}
			player.hurtMarked = true;
			player.connection.send(new ClientboundSetEntityMotionPacket(player));
		}
	}

	public static boolean shouldCancelFirstLandingFallDamage(ServerPlayer player, DamageSource damageSource) {
		if (player == null || damageSource == null || !damageSource.is(DamageTypes.FALL)) {
			return false;
		}
		if (isMilkPocket(player.level()) && RETURN_POINTS.containsKey(player.getUUID())) {
			player.resetFallDistance();
			player.fallDistance = 0.0F;
			return true;
		}
		if (!FIRST_LANDING_FALL_PROTECTED_PLAYERS.remove(player.getUUID())) {
			return false;
		}
		player.resetFallDistance();
		player.fallDistance = 0.0F;
		return true;
	}

	private static void protectFirstLandingFallDamage(ServerPlayer player) {
		if (player == null) {
			return;
		}
		FIRST_LANDING_FALL_PROTECTED_PLAYERS.add(player.getUUID());
		player.resetFallDistance();
		player.fallDistance = 0.0F;
	}

	private static void tickFirstLandingFallProtection(MinecraftServer server) {
		if (server == null || FIRST_LANDING_FALL_PROTECTED_PLAYERS.isEmpty()) {
			return;
		}

		Set<UUID> onlinePlayers = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID playerId = player.getUUID();
			onlinePlayers.add(playerId);
			if (FIRST_LANDING_FALL_PROTECTED_PLAYERS.contains(playerId) && player.onGround()) {
				FIRST_LANDING_FALL_PROTECTED_PLAYERS.remove(playerId);
				player.resetFallDistance();
				player.fallDistance = 0.0F;
			}
		}
		FIRST_LANDING_FALL_PROTECTED_PLAYERS.removeIf(playerId -> !onlinePlayers.contains(playerId));
	}

	private static void tickMilkPocketPlayerVoidWrap(ServerLevel level, ServerPlayer player, long nowTick) {
		if (level == null || player == null) {
			return;
		}

		UUID playerId = player.getUUID();
		Long wrapAtTick = VOID_WRAP_AT_TICKS.get(playerId);
		if (wrapAtTick == null) {
			VOID_WRAP_AT_TICKS.put(playerId, nowTick + VOID_WRAP_DELAY_TICKS);
			sendMilkPocketVoidFade(player, 1.0F, true);
			slowMilkPocketVoidFall(player);
			return;
		}

		if (player.getY() > VOID_WRAP_PREPARE_Y + 1.5D) {
			VOID_WRAP_AT_TICKS.remove(playerId);
			return;
		}

		slowMilkPocketVoidFall(player);
		if (nowTick >= wrapAtTick) {
			wrapMilkPocketFallingEntity(level, player);
			VOID_WRAP_AT_TICKS.remove(playerId);
		}
	}

	private static void slowMilkPocketVoidFall(ServerPlayer player) {
		Vec3 velocity = player.getDeltaMovement();
		if (velocity.y < VOID_WRAP_PENDING_MAX_FALL_SPEED) {
			player.setDeltaMovement(velocity.x, VOID_WRAP_PENDING_MAX_FALL_SPEED, velocity.z);
			player.hurtMarked = true;
		}
		player.resetFallDistance();
	}

	private static void wrapMilkPocketFallingEntity(ServerLevel level, Entity entity) {
		if (level == null || entity == null) {
			return;
		}
		Vec3 velocity = entity.getDeltaMovement();
		if (entity instanceof ServerPlayer player) {
			beginMilkPocketVoidWrapFade(player);
			player.teleportTo(
					level,
					0.5D,
					VOID_WRAP_TARGET_Y,
					0.5D,
					ABSOLUTE_TELEPORT,
					player.getYRot(),
					player.getXRot(),
					false
			);
		} else {
			entity.teleportTo(0.5D, VOID_WRAP_TARGET_Y, 0.5D);
		}
		entity.setDeltaMovement(velocity.x, -0.15D, velocity.z);
		entity.resetFallDistance();
		if (entity instanceof ServerPlayer player) {
			protectFirstLandingFallDamage(player);
		}
		entity.hurtMarked = true;
	}

	private static void tickMilkPocketVoidFade(MinecraftServer server) {
		if (server == null) {
			return;
		}

		long nowTick = server.getTickCount();
		Set<UUID> onlinePlayers = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player == null) {
				continue;
			}
			UUID playerId = player.getUUID();
			onlinePlayers.add(playerId);

			if (!isMilkPocket(player.level())) {
				VOID_WRAP_AT_TICKS.remove(playerId);
				VOID_FADE_CLEAR_AT_TICKS.remove(playerId);
				clearMilkPocketVoidFade(player);
				continue;
			}

			if (VOID_WRAP_AT_TICKS.containsKey(playerId)) {
				sendMilkPocketVoidFade(player, 1.0F, false);
				continue;
			}

			Long clearAtTick = VOID_FADE_CLEAR_AT_TICKS.get(playerId);
			if (clearAtTick != null) {
				if (nowTick >= clearAtTick) {
					VOID_FADE_CLEAR_AT_TICKS.remove(playerId);
					clearMilkPocketVoidFade(player);
				}
				continue;
			}

			sendMilkPocketVoidFade(player, computeMilkPocketVoidFadeAlpha(player.getY()), false);
		}

		VOID_FADE_ALPHA_BY_PLAYER.keySet().removeIf(playerId -> !onlinePlayers.contains(playerId));
		VOID_FADE_CLEAR_AT_TICKS.keySet().removeIf(playerId -> !onlinePlayers.contains(playerId));
		VOID_WRAP_AT_TICKS.keySet().removeIf(playerId -> !onlinePlayers.contains(playerId));
	}

	private static float computeMilkPocketVoidFadeAlpha(double y) {
		if (y >= VOID_FADE_START_Y) {
			return 0.0F;
		}
		double fadeDistance = VOID_FADE_START_Y - VOID_WRAP_Y;
		if (fadeDistance <= 0.0D) {
			return VOID_FADE_MAX_ALPHA;
		}
		double progress = (VOID_FADE_START_Y - y) / fadeDistance;
		progress = Math.max(0.0D, Math.min(1.0D, progress));
		return (float) (progress * VOID_FADE_MAX_ALPHA);
	}

	private static void beginMilkPocketVoidWrapFade(ServerPlayer player) {
		if (player == null) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		VOID_FADE_CLEAR_AT_TICKS.put(player.getUUID(), server.getTickCount() + VOID_FADE_CLEAR_DELAY_TICKS);
		sendMilkPocketVoidFade(player, 1.0F, true);
	}

	private static void clearMilkPocketVoidFade(ServerPlayer player) {
		if (player == null) {
			return;
		}
		VOID_FADE_CLEAR_AT_TICKS.remove(player.getUUID());
		VOID_WRAP_AT_TICKS.remove(player.getUUID());
		sendMilkPocketVoidFade(player, 0.0F, true);
	}

	private static void sendMilkPocketVoidFade(ServerPlayer player, float alpha, boolean force) {
		if (player == null || player.connection == null) {
			return;
		}
		if (!ServerPlayNetworking.canSend(player, Lg2Payloads.MilkPocketVoidFadeS2CPayload.TYPE)) {
			return;
		}

		alpha = Math.max(0.0F, Math.min(1.0F, alpha));
		UUID playerId = player.getUUID();
		Float previousAlpha = VOID_FADE_ALPHA_BY_PLAYER.get(playerId);
		if (!force && previousAlpha != null && Math.abs(previousAlpha - alpha) < 0.03F) {
			return;
		}
		if (!force && previousAlpha == null && alpha <= 0.001F) {
			return;
		}

		ServerPlayNetworking.send(player, new Lg2Payloads.MilkPocketVoidFadeS2CPayload(alpha));
		if (alpha <= 0.001F) {
			VOID_FADE_ALPHA_BY_PLAYER.remove(playerId);
		} else {
			VOID_FADE_ALPHA_BY_PLAYER.put(playerId, alpha);
		}
	}

	private static void cleanupExpiredDamageLocks(MinecraftServer server) {
		if (server == null || LAST_DAMAGE_TICKS.isEmpty()) {
			return;
		}
		long lockTicks = getRecentDamageLockTicks();
		if (lockTicks <= 0L) {
			LAST_DAMAGE_TICKS.clear();
			return;
		}
		long nowTick = server.getTickCount();
		LAST_DAMAGE_TICKS.entrySet().removeIf(entry -> entry.getValue() + lockTicks <= nowTick);
	}

	private static void ensurePhantomFloor(ServerLevel level) {
		BlockState state = level.getBlockState(PHANTOM_FLOOR_POS);
		if (state.is(Blocks.BEDROCK)) {
			level.setBlock(PHANTOM_FLOOR_POS, Blocks.AIR.defaultBlockState(), 3);
			state = level.getBlockState(PHANTOM_FLOOR_POS);
		}
		if (!state.isAir() && !state.is(ModBlocks.MILK_POCKET_PHANTOM_FLOOR)) {
			return;
		}
		if (!state.is(ModBlocks.MILK_POCKET_PHANTOM_FLOOR)) {
			level.setBlock(PHANTOM_FLOOR_POS, ModBlocks.MILK_POCKET_PHANTOM_FLOOR.defaultBlockState(), 3);
		}
	}

	private static void cleanupLegacyPocketPlatform(ServerLevel level) {
		for (int x = -LEGACY_PLATFORM_CLEANUP_RADIUS_XZ; x <= LEGACY_PLATFORM_CLEANUP_RADIUS_XZ; x++) {
			for (int z = -LEGACY_PLATFORM_CLEANUP_RADIUS_XZ; z <= LEGACY_PLATFORM_CLEANUP_RADIUS_XZ; z++) {
				for (int y = LEGACY_PLATFORM_CLEANUP_MIN_Y; y <= LEGACY_PLATFORM_CLEANUP_MAX_Y; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (isLegacyPocketPlatformBlock(state)) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
		}
		ensurePhantomFloor(level);
	}

	private static void removeBlocksOutsideBuildZone(ServerLevel level) {
		for (int x = -OUTSIDE_BLOCK_CLEANUP_RADIUS_XZ; x <= OUTSIDE_BLOCK_CLEANUP_RADIUS_XZ; x++) {
			for (int z = -OUTSIDE_BLOCK_CLEANUP_RADIUS_XZ; z <= OUTSIDE_BLOCK_CLEANUP_RADIUS_XZ; z++) {
				for (int y = OUTSIDE_BLOCK_CLEANUP_MIN_Y; y <= OUTSIDE_BLOCK_CLEANUP_MAX_Y; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (isInsideBuildZone(pos)) {
						continue;
					}
					BlockState state = level.getBlockState(pos);
					if (!state.isAir()) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
		}
		ensurePhantomFloor(level);
	}

	private static boolean isLegacyPocketPlatformBlock(BlockState state) {
		return state.is(Blocks.STONE)
				|| state.is(Blocks.COBBLESTONE)
				|| state.is(Blocks.BEDROCK)
				|| state.is(Blocks.OBSIDIAN);
	}

	private static void removeBedrockInBuildZone(ServerLevel level) {
		for (int x = -BUILD_RADIUS_XZ; x <= BUILD_RADIUS_XZ; x++) {
			for (int z = -BUILD_RADIUS_XZ; z <= BUILD_RADIUS_XZ; z++) {
				for (int y = BUILD_MIN_Y; y <= BUILD_MAX_Y; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (level.getBlockState(pos).is(Blocks.BEDROCK)) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
		}
		ensurePhantomFloor(level);
	}

	private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hitResult) {
		if (!(player instanceof ServerPlayer serverPlayer) || !(world instanceof ServerLevel level) || !isMilkPocket(level)) {
			return InteractionResult.PASS;
		}
		if (serverPlayer.isSpectator()) {
			return InteractionResult.PASS;
		}

		BlockPos pos = hitResult.getBlockPos();
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof BedBlock || state.is(Blocks.RESPAWN_ANCHOR)) {
			return InteractionResult.FAIL;
		}

		ItemStack held = serverPlayer.getItemInHand(hand);
		if (state.is(ModBlocks.MILK_POCKET_PHANTOM_FLOOR) && held.getItem() instanceof BucketItem bucketItem) {
			InteractionResult bucketResult = placeBucketContentsOnPhantomFloor(serverPlayer, level, hand, held, bucketItem, pos);
			if (bucketResult != InteractionResult.PASS) {
				return bucketResult;
			}
		}

		BlockPos placementPos = pos.relative(hitResult.getDirection());
		if (state.is(ModBlocks.MILK_POCKET_PHANTOM_FLOOR) && held.getItem() instanceof BlockItem) {
			placementPos = pos;
		}

		if (!isInsideBuildZone(pos) || (held.getItem() instanceof BlockItem && !isInsideBuildZone(placementPos))) {
			return InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	private static InteractionResult placeBucketContentsOnPhantomFloor(
			ServerPlayer player,
			ServerLevel level,
			InteractionHand hand,
			ItemStack held,
			BucketItem bucketItem,
			BlockPos pos
	) {
		if (player == null || level == null || hand == null || held == null || bucketItem == null || pos == null) {
			return InteractionResult.PASS;
		}
		if (!isInsideBuildZone(pos)) {
			ServerMechanicsGateSystem.syncPlayerInventory(player);
			return InteractionResult.FAIL;
		}

		BlockState fluidBlockState;
		if (bucketItem.getContent().isSame(Fluids.WATER)) {
			fluidBlockState = Blocks.WATER.defaultBlockState();
		} else if (bucketItem.getContent().isSame(Fluids.LAVA)) {
			fluidBlockState = Blocks.LAVA.defaultBlockState();
		} else {
			return InteractionResult.PASS;
		}

		level.setBlock(pos, fluidBlockState, 3);
		level.playSound(
				null,
				pos,
				bucketItem.getContent().isSame(Fluids.LAVA) ? SoundEvents.BUCKET_EMPTY_LAVA : SoundEvents.BUCKET_EMPTY,
				SoundSource.BLOCKS,
				1.0F,
				1.0F
		);
		bucketItem.checkExtraContent(player, level, held, pos);
		if (!player.getAbilities().instabuild) {
			player.setItemInHand(hand, BucketItem.getEmptySuccessItem(held, player));
		}
		ServerMechanicsGateSystem.syncPlayerInventory(player);
		return InteractionResult.SUCCESS;
	}

	private static boolean beforeBlockBreak(Level world, Player player, BlockPos pos, BlockState state, net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
		if (!(world instanceof ServerLevel level) || !isMilkPocket(level)) {
			return true;
		}
		if (PHANTOM_FLOOR_POS.equals(pos)) {
			if (!state.is(ModBlocks.MILK_POCKET_PHANTOM_FLOOR)) {
				level.setBlock(PHANTOM_FLOOR_POS, ModBlocks.MILK_POCKET_PHANTOM_FLOOR.defaultBlockState(), 3);
			}
			if (player != null) {
				Vec3 velocity = player.getDeltaMovement();
				if (velocity.y < 0.0D) {
					player.setDeltaMovement(velocity.x, 0.0D, velocity.z);
					player.hurtMarked = true;
				}
				player.resetFallDistance();
			}
			return false;
		}
		if (state.is(ModBlocks.MILK_POCKET_PHANTOM_FLOOR)) {
			return false;
		}
		return isInsideBuildZone(pos);
	}

	private static boolean isInsideBuildZone(BlockPos pos) {
		return pos != null
				&& pos.getX() >= -BUILD_RADIUS_XZ
				&& pos.getX() <= BUILD_RADIUS_XZ
				&& pos.getZ() >= -BUILD_RADIUS_XZ
				&& pos.getZ() <= BUILD_RADIUS_XZ
				&& pos.getY() >= BUILD_MIN_Y
				&& pos.getY() <= BUILD_MAX_Y;
	}

	static int clearAccessCommand(CommandSourceStack source) {
		ACCESS_PLAYERS.clear();
		stateDirty = true;
		saveState(source.getServer());
		source.sendSuccess(() -> Component.literal("Список доступа в карманное измерение очищен."), true);
		return 1;
	}

	private static void loadState(MinecraftServer server) {
		ACCESS_PLAYERS.clear();
		RETURN_POINTS.clear();
		stateDirty = false;
		stateLoaded = true;

		Path path = getStatePath(server);
		if (path == null || !Files.exists(path)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			StateData data = STATE_GSON.fromJson(reader, StateData.class);
			if (data == null) {
				return;
			}
			legacyPlatformCleaned = data.legacyPlatformCleaned;
			if (data.accessPlayers != null) {
				ACCESS_PLAYERS.addAll(data.accessPlayers);
			}
			if (data.returnPoints != null) {
				RETURN_POINTS.putAll(data.returnPoints);
			}
		} catch (IOException | RuntimeException exception) {
			Lg2.LOGGER.warn("Failed to load milk pocket state from {}", path, exception);
		}
	}

	private static void saveState(MinecraftServer server) {
		if (server == null || !stateLoaded) {
			return;
		}

		Path path = getStatePath(server);
		if (path == null) {
			return;
		}

		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				StateData data = new StateData();
				data.legacyPlatformCleaned = legacyPlatformCleaned;
				data.accessPlayers = new HashSet<>(ACCESS_PLAYERS);
				data.returnPoints = new HashMap<>(RETURN_POINTS);
				STATE_GSON.toJson(data, writer);
			}
			stateDirty = false;
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to save milk pocket state to {}", path, exception);
		}
	}

	private static Path getStatePath(MinecraftServer server) {
		return server == null ? null : server.getWorldPath(LevelResource.ROOT).resolve(STATE_FILE_NAME);
	}

	private static void sendPersonalSound(ServerPlayer player, net.minecraft.sounds.SoundEvent sound, SoundSource source, float volume, float pitch) {
		if (player == null || player.connection == null || sound == null || source == null) {
			return;
		}
		player.connection.send(new ClientboundSoundPacket(
				BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
				source,
				player.getX(),
				player.getY(),
				player.getZ(),
				volume,
				pitch,
				player.level().getGameTime() ^ player.getUUID().getLeastSignificantBits()
		));
	}

	private static void sendLocalPlayerSoundExcept(
			ServerLevel level,
			ServerPlayer excluded,
			Vec3 origin,
			net.minecraft.sounds.SoundEvent sound,
			SoundSource source,
			float volume,
			float pitch,
			double radius
	) {
		if (level == null || origin == null || sound == null || source == null || radius <= 0.0D) {
			return;
		}

		double radiusSqr = radius * radius;
		long seedBase = level.getGameTime() ^ Double.doubleToLongBits(origin.x + origin.y + origin.z);
		for (ServerPlayer viewer : level.players()) {
			if (viewer == null || viewer.connection == null || viewer == excluded) {
				continue;
			}
			if (viewer.distanceToSqr(origin.x, origin.y, origin.z) > radiusSqr) {
				continue;
			}
			viewer.connection.send(new ClientboundSoundPacket(
					BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
					source,
					origin.x,
					origin.y,
					origin.z,
					volume,
					pitch,
					seedBase ^ viewer.getUUID().getLeastSignificantBits()
			));
		}
	}

	private static String sanitizeRaceId(String raceId) {
		if (raceId == null || raceId.isBlank()) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		String lower = raceId.toLowerCase(java.util.Locale.ROOT);
		for (int index = 0; index < lower.length(); index++) {
			char character = lower.charAt(index);
			if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9') || character == '_' || character == '-') {
				builder.append(character);
			} else if (Character.isWhitespace(character)) {
				builder.append('_');
			}
		}
		return builder.toString();
	}

	private static final class StateData {
		boolean legacyPlatformCleaned;
		Collection<UUID> accessPlayers;
		Map<UUID, ReturnPoint> returnPoints;
	}

	private static final class ReturnPoint {
		String dimension;
		double x;
		double y;
		double z;
		float yaw;
		float pitch;
		float headYaw;
		float bodyYaw;
		double velocityX;
		double velocityY;
		double velocityZ;
		double fallDistance;
		boolean sprinting;
		boolean shiftKeyDown;
		boolean physicalStateStored;

		ReturnPoint(
				String dimension,
				double x,
				double y,
				double z,
				float yaw,
				float pitch,
				float headYaw,
				float bodyYaw,
				double velocityX,
				double velocityY,
				double velocityZ,
				double fallDistance,
				boolean sprinting,
				boolean shiftKeyDown
		) {
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.headYaw = headYaw;
			this.bodyYaw = bodyYaw;
			this.velocityX = velocityX;
			this.velocityY = velocityY;
			this.velocityZ = velocityZ;
			this.fallDistance = fallDistance;
			this.sprinting = sprinting;
			this.shiftKeyDown = shiftKeyDown;
			this.physicalStateStored = true;
		}
	}
}

