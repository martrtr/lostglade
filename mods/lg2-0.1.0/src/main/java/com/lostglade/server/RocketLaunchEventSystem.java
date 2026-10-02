package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.block.CameraBlock;
import com.lostglade.block.ModBlocks;
import com.mojang.brigadier.Command;
import com.mojang.math.Transformation;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A one-shot, redstone-driven launch event which turns a player-built rocket
 * into BlockDisplay entities and unlocks Yandex Maps GPS after the rocket has
 * left the visible world.
 */
public final class RocketLaunchEventSystem {
	private static final Gson STATE_GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String STATE_FILE_NAME = "lg2-rocket-launch.json";
	private static final String DISPLAY_TAG = "lg2_rocket_launch_display";
	// Kept only to remove smoke ItemDisplays left by an older version after an update.
	private static final String EXHAUST_DISPLAY_TAG = "lg2_rocket_launch_exhaust";
	private static final String DEBRIS_DISPLAY_TAG = "lg2_rocket_launch_debris";
	private static final String DEVICE_ANCHOR_TAG = "lg2_rocket_launch_device_anchor";
	public static final String MOUNTED_SCREEN_TAG = "lg2_rocket_launch_mounted_screen";
	private static final Identifier ROCKET_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "rocket_launch");
	private static final Holder<SoundEvent> ROCKET_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(ROCKET_SOUND_ID));

	private static final int MAX_SELECTION_VOLUME = 250_000;
	private static final int MAX_AUTO_SELECTION_FOOTPRINT = 4_096;
	private static final int MAX_ROCKET_BLOCKS = 1_200;
	private static final long PREHEAT_TICKS = 20L * 12L;
	private static final int ARMED_POWER_CHECKS_PER_TICK = 16;
	private static final double SPACE_ALTITUDE = 1_000.0D;
	/* Client-only rocket copies sit just inside normal entity visibility.  Their
	 * apparent scale is the same as if the real rocket kept flying farther away. */
	private static final double ROCKET_PROJECTION_DISTANCE = 144.0D;
	private static final float ROCKET_DISPLAY_VIEW_RANGE = 1_000_000.0F;
	private static final int DISPLAY_INTERPOLATION_TICKS = 2;
	private static final DustColorTransitionOptions SPACE_RACE_FLAME_WIDE = new DustColorTransitionOptions(0xFF8800, 0x701F1F, 4.0F);
	private static final DustColorTransitionOptions SPACE_RACE_FLAME_CORE = new DustColorTransitionOptions(0xFF8800, 0x701F1F, 3.0F);
	private static final double AUDIO_EMITTER_RADIUS = 44.0D;
	private static final double AUDIO_AUDIENCE_RADIUS = 190.0D;
	private static final float ROCKET_LAUNCH_SOUND_VOLUME = 20.0F;
	private static final double ROCKET_GRAVITY_PER_TICK = 0.00180D;
	private static final double ROCKET_THRUST_PER_TICK = 0.00335D;
	/* Angular velocity is stored in the rocket's own axes.  It is only very
	 * lightly damped here; the meaningful stabilising force is the aerodynamic
	 * force below, acting behind the centre of mass. */
	private static final double ROCKET_ANGULAR_DAMPING = 0.99985D;
	private static final double ROCKET_AERODYNAMIC_FORCE = 0.045D;
	private static final double ROCKET_AXIAL_DRAG = 0.0018D;
	private static final double ROCKET_IDLE_SPIN_PER_TICK = 0.0042D;
	private static final double LAUNCH_PAD_CLEARANCE = 2.25D;
	private static final long ENGINE_IGNITION_BURST_TICKS = 12L;
	private static final double ENGINE_EXHAUST_BASE_REACH = 10.5D;
	private static final double ENGINE_EXHAUST_MAX_PUSH_PER_TICK = 1.35D;
	private static final long ENGINE_EXHAUST_RAMP_TICKS = 40L;
	// A player is deliberately light compared to a block hull, but not
	// negligible.  Walking or being thrown to one side of a small rocket shifts
	// its centre of mass enough for the engine moment to become visible.
	private static final double ROCKET_OCCUPANT_MASS = 55.0D;
	private static final double OCCUPANT_INPUT_PER_TICK = 0.42D;
	private static final double OCCUPANT_FLUID_DRAG = 0.74D;
	private static final double OCCUPANT_INERTIA_FLOW = 1.45D;
	private static final int VACUUM_DROWNING_LEAD_TICKS = 180;
	private static final int MAX_CRASH_DEBRIS = 96;
	private static final int MAX_LARGE_WRECK_BLOCKS = 640;
	// The blast diameter is ten times the largest rocket dimension.  The cap
	// avoids a single impact freezing the server by editing millions of blocks.
	private static final int MAX_CRASH_RADIUS = 96;
	private static final Direction[] ENGINE_NEIGHBOURS = {
			Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
	};
	private static final Vec3[] AUDIO_EMITTER_DIRECTIONS = {
			new Vec3(1.0D, 0.0D, 0.0D),
			new Vec3(0.70710678118D, 0.0D, 0.70710678118D),
			new Vec3(0.0D, 0.0D, 1.0D),
			new Vec3(-0.70710678118D, 0.0D, 0.70710678118D),
			new Vec3(-1.0D, 0.0D, 0.0D),
			new Vec3(-0.70710678118D, 0.0D, -0.70710678118D),
			new Vec3(0.0D, 0.0D, -1.0D),
			new Vec3(0.70710678118D, 0.0D, -0.70710678118D)
	};

	private static final Map<UUID, AdminSelection> ADMIN_SELECTIONS = new HashMap<>();
	private static final Map<UUID, RocketProjectionView> ROCKET_PROJECTIONS = new HashMap<>();
	private static final Set<BlockPos> ACTIVE_ARMED_POWER_POINTS = new HashSet<>();
	private static int armedPowerScanCursor;
	private static boolean armedPowerStatePrimed;
	private static final Set<Relative> ABSOLUTE_ENTITY_TELEPORT = EnumSet.noneOf(Relative.class);
	private static final ServerEntity.Synchronizer ROCKET_PROJECTION_SYNCHRONIZER = new ServerEntity.Synchronizer() {
		@Override
		public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
		}

		@Override
		public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
		}

		@Override
		public void sendToTrackingPlayersFiltered(
				Packet<? super ClientGamePacketListener> packet,
				java.util.function.Predicate<ServerPlayer> filter
		) {
		}
	};
	private static RocketState rocket;
	private static boolean stateLoaded;
	private static boolean stateDirty;

	private RocketLaunchEventSystem() {
	}

	public static void register() {
		ADMIN_SELECTIONS.clear();
		ROCKET_PROJECTIONS.clear();
		rocket = null;
		stateLoaded = false;
		stateDirty = false;

		ServerLifecycleEvents.SERVER_STARTED.register(RocketLaunchEventSystem::loadState);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			saveState(server);
			ADMIN_SELECTIONS.clear();
			ROCKET_PROJECTIONS.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(RocketLaunchEventSystem::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ADMIN_SELECTIONS.remove(handler.player.getUUID());
			clearRocketProjection(handler.player);
			ServerSelectionHighlightSystem.clear(handler.player);
		});
		UseBlockCallback.EVENT.register(RocketLaunchEventSystem::onUseBlock);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("rocket")
						.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.executes(context -> useRocket(context.getSource()))
						.then(Commands.literal("select").executes(context -> beginPointSelection(context.getSource())))
						.then(Commands.literal("deselect").executes(context -> beginDeselectSelection(context.getSource())))
						.then(Commands.literal("engines").executes(context -> beginEngineSelection(context.getSource())))
		));
	}

	private static int beginPointSelection(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		AdminSelection selection = ADMIN_SELECTIONS.get(player.getUUID());
		if (selection == null) {
			selection = new AdminSelection();
			ADMIN_SELECTIONS.put(player.getUUID(), selection);
			ServerSelectionHighlightSystem.clear(player);
		}
		if (selection.mode == SelectionMode.ROCKET) {
			// A repeated /rocket select means "start over", not merely hide the
			// outlines.  Keeping the old points here made it too easy to arm a
			// different rocket accidentally.
			ADMIN_SELECTIONS.remove(player.getUUID());
			ServerSelectionHighlightSystem.clear(player);
			source.sendSuccess(() -> Component.literal("Выделение ракеты сброшено."), false);
			return Command.SINGLE_SUCCESS;
		}
		selection.clearDeselectState();
		selection.mode = SelectionMode.ROCKET;
		if (!selection.isComplete()) {
			source.sendSuccess(() -> Component.literal("Правым кликом отметь 2+ нижних крайних блока ракеты с разных сторон; верх найдётся сам."), false);
		} else {
			previewSelection(player, selection);
			source.sendSuccess(() -> Component.literal("Выбор блоков включён: клик по подсвеченному блоку исключает его, повторный клик возвращает."), false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int beginDeselectSelection(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		AdminSelection selection = ADMIN_SELECTIONS.get(player.getUUID());
		if (selection == null || !selection.isComplete()) {
			source.sendFailure(Component.literal("Сначала выдели корпус через /rocket select."));
			return 0;
		}
		if (selection.mode == SelectionMode.DESELECT) {
			selection.mode = SelectionMode.NONE;
			selection.clearDeselectState();
			ServerSelectionHighlightSystem.clear(player);
			source.sendSuccess(() -> Component.literal("Режим исключения блоков выключен."), false);
			return Command.SINGLE_SUCCESS;
		}
		SelectionBounds bounds = resolveAutomaticBounds(player.level() instanceof ServerLevel level ? level : null, selection);
		if (bounds == null) {
			source.sendFailure(Component.literal("Не удалось определить область ракеты для исключения."));
			return 0;
		}
		selection.mode = SelectionMode.DESELECT;
		selection.beginDeselect(bounds);
		previewSelection(player, selection);
		source.sendSuccess(() -> Component.literal("Исключение блоков: кликни по двум углам области. Два клика по одному блоку исключат только его."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int beginEngineSelection(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		AdminSelection selection = ADMIN_SELECTIONS.get(player.getUUID());
		if (selection == null || !selection.isComplete()) {
			source.sendFailure(Component.literal("Сначала выбери корпус ракеты через /rocket select."));
			return 0;
		}
		if (selection.mode == SelectionMode.ENGINES) {
			// The hull selection remains the prerequisite for /rocket engines, but
			// the repeated command discards every selected nozzle.
			selection.clearEnginePoints();
			selection.mode = SelectionMode.NONE;
			ServerSelectionHighlightSystem.clear(player);
			source.sendSuccess(() -> Component.literal("Выделение сопел сброшено."), false);
			return Command.SINGLE_SUCCESS;
		}
		selection.clearDeselectState();
		selection.mode = SelectionMode.ENGINES;
		previewSelection(player, selection);
		source.sendSuccess(() -> Component.literal("Кликай по блокам сопел: клик ставит сопло, повторный клик снимает. Затем /rocket — взвести ракету."), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int useRocket(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (rocket != null) {
			LaunchStage stage = LaunchStage.from(rocket.stage);
			if (stage == LaunchStage.ARMED) {
				return abortRocket(source);
			}
			if (stage == LaunchStage.LAUNCHING) {
				source.sendFailure(Component.literal("Ракета уже летит; вернуть её нельзя."));
				return 0;
			}
			clearFinishedRocket(source.getServer());
			ADMIN_SELECTIONS.remove(player.getUUID());
			ServerSelectionHighlightSystem.clear(player);
		}
		AdminSelection selection = ADMIN_SELECTIONS.get(player.getUUID());
		if (selection == null || selection.mode == SelectionMode.NONE) {
			return beginPointSelection(source);
		}
		return armRocket(source);
	}

	private static InteractionResult onUseBlock(net.minecraft.world.entity.player.Player player, Level world,
				net.minecraft.world.InteractionHand hand, BlockHitResult hitResult) {
		if (world.isClientSide() || hand != net.minecraft.world.InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		AdminSelection selection = ADMIN_SELECTIONS.get(serverPlayer.getUUID());
		if (selection == null || hitResult == null) {
			return InteractionResult.PASS;
		}
		BlockPos point = hitResult.getBlockPos().immutable();
		if (selection.mode == SelectionMode.ENGINES) {
			SelectionBounds bounds = resolveAutomaticBounds(world instanceof ServerLevel serverLevel ? serverLevel : null, selection);
			if (bounds == null || !bounds.contains(point) || selection.excludedBlocks.contains(point) || world.getBlockState(point).isAir()) {
				serverPlayer.sendSystemMessage(Component.literal("Точка двигателя должна быть блоком выбранной ракеты."));
				return InteractionResult.FAIL;
			}
			if (selection.enginePoints.contains(point)) {
				selection.removeEnginePoint(point);
				serverPlayer.sendSystemMessage(Component.literal("Сопло снято: " + formatPos(point)));
			} else if (selection.addEnginePoint(point)) {
				serverPlayer.sendSystemMessage(Component.literal("Двигатель " + selection.enginePoints.size() + ": " + formatPos(point)));
			}
			previewSelection(serverPlayer, selection);
			return InteractionResult.SUCCESS;
		}
		if (selection.mode == SelectionMode.DESELECT) {
			ServerLevel serverLevel = world instanceof ServerLevel level ? level : null;
			SelectionBounds bounds = selection.deselectBounds != null
					? selection.deselectBounds
					: resolveAutomaticBounds(serverLevel, selection);
			if (serverLevel == null || bounds == null || !bounds.contains(point) || world.getBlockState(point).isAir()) {
				serverPlayer.sendSystemMessage(Component.literal("Точка исключения должна быть блоком выбранной ракеты."));
				return InteractionResult.FAIL;
			}
			BlockPos firstPoint = selection.takeDeselectFirstPoint(point);
			if (firstPoint == null) {
				serverPlayer.sendSystemMessage(Component.literal("Первая точка исключения: " + formatPos(point) + ". Выбери вторую."));
				return InteractionResult.SUCCESS;
			}
			int excluded = selection.excludeBox(serverLevel, bounds, firstPoint, point);
			serverPlayer.sendSystemMessage(Component.literal("Исключено блоков: " + excluded + "."));
			previewSelection(serverPlayer, selection);
			return InteractionResult.SUCCESS;
		}
		if (selection.mode != SelectionMode.ROCKET) {
			return InteractionResult.PASS;
		}
		if (world.getBlockState(point).isAir()) {
			serverPlayer.sendSystemMessage(Component.literal("Отмечай именно нижний блок ракеты, а не воздух."));
			return InteractionResult.FAIL;
		}
		if (!selection.isComplete()) {
			if (selection.addBottomPoint(point)) {
				serverPlayer.sendSystemMessage(Component.literal("Нижняя точка " + selection.bottomPoints.size() + ": " + formatPos(point)));
			}
			if (selection.bottomPoints.size() >= 2) {
				previewSelection(serverPlayer, selection);
			} else {
				serverPlayer.sendSystemMessage(Component.literal("Отметь ещё хотя бы одну нижнюю точку с другой стороны ракеты."));
			}
			return InteractionResult.SUCCESS;
		}

		ServerLevel serverLevel = world instanceof ServerLevel level ? level : null;
		if (selection.excludedBlocks.contains(point)) {
			if (serverLevel == null || !isInsideSelectionScanRange(serverLevel, selection, point) || world.getBlockState(point).isAir()) {
				serverPlayer.sendSystemMessage(Component.literal("Этот блок нельзя вернуть в выделение."));
				return InteractionResult.FAIL;
			}
			selection.removeExcludedBlock(point);
			serverPlayer.sendSystemMessage(Component.literal("Блок возвращён в ракету: " + formatPos(point)));
			previewSelection(serverPlayer, selection);
			return InteractionResult.SUCCESS;
		}
		SelectionBounds bounds = resolveAutomaticBounds(serverLevel, selection);
		if (bounds == null || !bounds.contains(point)) {
			// The first two points establish a usable footprint, but they do not
			// lock it.  Any later click outside the highlighted volume remains a
			// new bottom reference point and expands the automatic selection.
			if (selection.addBottomPoint(point)) {
				serverPlayer.sendSystemMessage(Component.literal("Добавлена нижняя точка " + selection.bottomPoints.size() + ": " + formatPos(point)));
				previewSelection(serverPlayer, selection);
			}
			return InteractionResult.SUCCESS;
		}
		if (selection.addExcludedBlock(point)) {
			serverPlayer.sendSystemMessage(Component.literal("Блок исключён: " + formatPos(point)));
			previewSelection(serverPlayer, selection);
		}
		return InteractionResult.SUCCESS;
	}

	private static boolean previewSelection(ServerPlayer player, AdminSelection selection) {
		if (player == null || selection == null || !selection.isComplete() || !(player.level() instanceof ServerLevel level)) {
			return false;
		}
		if (selection.mode == SelectionMode.ENGINES) {
			List<ServerSelectionHighlightSystem.DisplayBlueprint> engineBlueprints = new ArrayList<>();
			for (BlockPos engine : selection.enginePoints) {
				if (level.getBlockState(engine).isAir()) {
					continue;
				}
				engineBlueprints.add(new ServerSelectionHighlightSystem.ItemDisplayBlueprint(
						level,
						Vec3.atCenterOf(engine),
						0.0F,
						0.0F,
						ServerSelectionHighlightSystem.createHighlightCarrierStack(),
						ItemDisplayContext.FIXED,
						ServerSelectionHighlightSystem.defaultHighlightCarrierTransformation()
				));
			}
			ServerSelectionHighlightSystem.show(player, engineBlueprints);
		player.sendSystemMessage(Component.literal("Подсвечено сопел: " + engineBlueprints.size() + ". /rocket — взвести ракету."));
			return true;
		}
		SelectionBounds bounds = resolveAutomaticBounds(level, selection);
		if (bounds == null) {
			player.sendSystemMessage(Component.literal("Не удалось определить объём ракеты. Отметь нижние крайние блоки плотнее."));
			return false;
		}
		if (bounds.volume() > MAX_SELECTION_VOLUME) {
			player.sendSystemMessage(Component.literal("Объём выделения слишком большой (лимит " + MAX_SELECTION_VOLUME + " блоков)."));
			return false;
		}
		List<ServerSelectionHighlightSystem.DisplayBlueprint> blueprints = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ)) {
			if (selection.excludedBlocks.contains(pos)) {
				continue;
			}
			BlockState blockState = level.getBlockState(pos);
			if (blockState.isAir()) {
				continue;
			}
			if (blueprints.size() >= MAX_ROCKET_BLOCKS) {
				player.sendSystemMessage(Component.literal("В ракете больше " + MAX_ROCKET_BLOCKS + " блоков; сузь выделение."));
				return false;
			}
			// This is the same invisible item-display carrier used by the Bluetooth
			// adapter.  It adds only the glow outline and never renders a second,
			// black copy of the selected block.
			blueprints.add(new ServerSelectionHighlightSystem.ItemDisplayBlueprint(
					level,
					Vec3.atCenterOf(pos),
					0.0F,
					0.0F,
					ServerSelectionHighlightSystem.createHighlightCarrierStack(),
					ItemDisplayContext.FIXED,
					ServerSelectionHighlightSystem.defaultHighlightCarrierTransformation()
			));
		}
		ServerSelectionHighlightSystem.show(player, blueprints);
		player.sendSystemMessage(Component.literal("Подсвечено блоков ракеты: " + blueprints.size()
				+ ". Исключено: " + selection.excludedBlocks.size() + "; двигателей: " + selection.enginePoints.size()
				+ ". /rocket engines — отметить сопла, /rocket — взвести ракету."));
		return true;
	}

	/**
	 * The administrator only marks the footprint at the bottom.  We take its
	 * horizontal extremes and scan upward to the highest non-air block inside
	 * this footprint, so no top coordinates have to be entered manually.
	 */
	private static SelectionBounds resolveAutomaticBounds(ServerLevel level, AdminSelection selection) {
		if (level == null || selection == null || selection.bottomPoints.size() < 2) {
			return null;
		}
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos point : selection.bottomPoints) {
			minX = Math.min(minX, point.getX());
			minY = Math.min(minY, point.getY());
			minZ = Math.min(minZ, point.getZ());
			maxX = Math.max(maxX, point.getX());
			maxZ = Math.max(maxZ, point.getZ());
		}
		long footprint = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
		if (footprint <= 0L || footprint > MAX_AUTO_SELECTION_FOOTPRINT) {
			return null;
		}
		long scanHeight = Math.max(1L, MAX_SELECTION_VOLUME / footprint);
		int scanTop = (int) Math.min((long) level.getMaxY() - 1L, (long) minY + scanHeight - 1L);
		int maxY = Integer.MIN_VALUE;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = minY; y <= scanTop; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					cursor.set(x, y, z);
					if (!selection.excludedBlocks.contains(cursor.immutable()) && !level.getBlockState(cursor).isAir()) {
						maxY = y;
					}
				}
			}
		}
		if (maxY < minY) {
			return null;
		}
		SelectionBounds bounds = new SelectionBounds(minX, minY, minZ, maxX, maxY, maxZ);
		selection.automaticBounds = bounds;
		return bounds;
	}

	private static boolean isInsideSelectionScanRange(ServerLevel level, AdminSelection selection, BlockPos point) {
		if (level == null || selection == null || point == null || selection.bottomPoints.size() < 2) {
			return false;
		}
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos bottomPoint : selection.bottomPoints) {
			minX = Math.min(minX, bottomPoint.getX());
			minY = Math.min(minY, bottomPoint.getY());
			minZ = Math.min(minZ, bottomPoint.getZ());
			maxX = Math.max(maxX, bottomPoint.getX());
			maxZ = Math.max(maxZ, bottomPoint.getZ());
		}
		long footprint = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
		if (footprint <= 0L || footprint > MAX_AUTO_SELECTION_FOOTPRINT) {
			return false;
		}
		int scanTop = (int) Math.min((long) level.getMaxY() - 1L, (long) minY + MAX_SELECTION_VOLUME / footprint - 1L);
		return point.getX() >= minX && point.getX() <= maxX
				&& point.getY() >= minY && point.getY() <= scanTop
				&& point.getZ() >= minZ && point.getZ() <= maxZ;
	}

	private static int armRocket(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		if (rocket != null && !LaunchStage.COMPLETED.name().equals(rocket.stage)) {
			source.sendFailure(Component.literal("Ракета уже взведена или находится в полёте."));
			return 0;
		}
		ServerPlayer player = source.getPlayerOrException();
		if (!(player.level() instanceof ServerLevel level)) {
			return 0;
		}
		AdminSelection selection = ADMIN_SELECTIONS.get(player.getUUID());
		if (selection == null || !selection.isComplete()) {
			source.sendFailure(Component.literal("Сначала введи /rocket select и отметь 2+ нижних крайних блока ракеты."));
			return 0;
		}
		SelectionBounds bounds = resolveAutomaticBounds(level, selection);
		if (bounds == null) {
			source.sendFailure(Component.literal("Не удалось определить объём ракеты по отмеченным нижним точкам."));
			return 0;
		}
		if (bounds.volume() > MAX_SELECTION_VOLUME) {
			source.sendFailure(Component.literal("Объём выделения превышает лимит " + MAX_SELECTION_VOLUME + " блоков."));
			return 0;
		}

		List<PersistedRocketBlock> blocks = captureRocketBlocks(level, bounds, selection, source);
		if (blocks == null) {
			return 0;
		}
		if (blocks.isEmpty()) {
			source.sendFailure(Component.literal("В выделении нет блоков для ракеты."));
			return 0;
		}

		RocketState next = new RocketState();
		next.stage = LaunchStage.ARMED.name();
		next.dimension = level.dimension().identifier().toString();
		next.minX = bounds.minX;
		next.minY = bounds.minY;
		next.minZ = bounds.minZ;
		next.maxX = bounds.maxX;
		next.maxY = bounds.maxY;
		next.maxZ = bounds.maxZ;
		next.lastTriggerPowered = false;
		next.blocks = blocks;
		next.engines = captureEnginePoints(selection, blocks, source);
		if (next.engines == null || next.engines.isEmpty()) {
			return 0;
		}
		next.initializePhysicsFromBlocks();

		rocket = next;
		resetArmedPowerScan();
		stateLoaded = true;
		stateDirty = true;
		MonitorYandexMapsRuntime.setGpsEnabled(source.getServer(), false);
		saveState(source.getServer());
		// The physical blocks deliberately remain in the world until the rising
		// redstone edge.  That avoids an armed rocket looking like a handful of
		// display entities and keeps it fully editable as a normal build.
		selection.mode = SelectionMode.NONE;
		ServerSelectionHighlightSystem.clear(player);
		source.sendSuccess(
				() -> Component.literal("Ракета взведена: " + blocks.size() + " блоков. Подай редстоун к любому блоку ракеты."),
				true
		);
		return Command.SINGLE_SUCCESS;
	}

	private static List<PersistedRocketBlock> captureRocketBlocks(ServerLevel level, SelectionBounds bounds, AdminSelection selection, CommandSourceStack source) {
		List<PersistedRocketBlock> blocks = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ)) {
			if (selection != null && selection.excludedBlocks.contains(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}
			if (blocks.size() >= MAX_ROCKET_BLOCKS) {
				source.sendFailure(Component.literal("Лимит ракеты — " + MAX_ROCKET_BLOCKS + " блоков."));
				return null;
			}
			blocks.add(new PersistedRocketBlock(pos.getX(), pos.getY(), pos.getZ(), serializeBlockState(state)));
		}
		return blocks;
	}

	private static List<PersistedEnginePoint> captureEnginePoints(
			AdminSelection selection,
			List<PersistedRocketBlock> blocks,
			CommandSourceStack source
	) {
		if (selection == null || selection.enginePoints.isEmpty()) {
			source.sendFailure(Component.literal("Отметь хотя бы один двигатель: /rocket engines."));
			return null;
		}
		Set<BlockPos> selectedBlocks = new HashSet<>();
		for (PersistedRocketBlock block : blocks) {
			selectedBlocks.add(block.pos());
		}
		List<PersistedEnginePoint> engines = new ArrayList<>();
		for (BlockPos point : selection.enginePoints) {
			if (!selectedBlocks.contains(point)) {
				source.sendFailure(Component.literal("Двигатель " + formatPos(point) + " исключён из ракеты или находится вне выделения."));
				return null;
			}
			engines.add(new PersistedEnginePoint(point.getX(), point.getY(), point.getZ()));
		}
		return engines;
	}

	private static int abortRocket(CommandSourceStack source) {
		if (rocket == null || !LaunchStage.ARMED.name().equals(rocket.stage)) {
			source.sendFailure(Component.literal("Отменить можно только взведённую, ещё не запущенную ракету."));
			return 0;
		}
		ServerLevel level = rocketLevel(source.getServer());
		if (level == null) {
			source.sendFailure(Component.literal("Мир ракеты недоступен."));
			return 0;
		}
		for (PersistedRocketBlock block : rocket.blocks) {
			discardRocketDisplay(level, block);
			BlockPos pos = block.pos();
			if (level.getBlockState(pos).isAir()) {
				level.setBlock(pos, deserializeBlockState(block.state), 3);
			}
		}
		discardLegacyRocketExhaustDisplays(level);
		clearAllRocketProjections(source.getServer());
		resetArmedPowerScan();
		rocket = null;
		stateDirty = true;
		MonitorYandexMapsRuntime.setGpsEnabled(source.getServer(), false);
		saveState(source.getServer());
		source.sendSuccess(() -> Component.literal("Взведение отменено: блоки ракеты возвращены."), true);
		return Command.SINGLE_SUCCESS;
	}

	private static void clearFinishedRocket(MinecraftServer server) {
		if (rocket == null || server == null) {
			return;
		}
		ServerLevel level = rocketLevel(server);
		if (level != null) {
			for (PersistedRocketBlock block : rocket.blocks) {
				discardRocketDisplay(level, block);
			}
			discardLegacyRocketExhaustDisplays(level);
			discardCrashDebris(level);
		}
		clearAllRocketProjections(server);
		resetArmedPowerScan();
		rocket = null;
		stateDirty = true;
		MonitorYandexMapsRuntime.setGpsEnabled(server, false);
		saveState(server);
	}

	private static void tick(MinecraftServer server) {
		if (!stateLoaded || rocket == null) {
			return;
		}
		ServerLevel level = rocketLevel(server);
		if (level == null) {
			return;
		}
		discardLegacyRocketExhaustDisplays(level);
		LaunchStage stage = LaunchStage.from(rocket.stage);
		if (stage == LaunchStage.ARMED) {
			tickArmedPowerScan(level, server);
			return;
		}
		if (stage == LaunchStage.CRASHED) {
			tickCrashDebris(server, level);
			return;
		}
		if (stage != LaunchStage.LAUNCHING) {
			return;
		}
		discardMaterializationComponentDrops(level);
		if (!rocket.clientOnlyVisuals) {
			// Migration path for a launch saved by an older build: discard its real
			// displays once, then continue with the one per-player visual model.
			discardServerRocketDisplays(level);
			rocket.clientOnlyVisuals = true;
			stateDirty = true;
		}

		rocket.launchElapsedTicks++;
		long elapsed = rocket.launchElapsedTicks;
		if (elapsed < PREHEAT_TICKS) {
			// The real hull has already become displays during ignition, so keep
			// cabin passengers inside its virtual solid volume before lift-off too.
			resolveRocketHullContacts(level);
			tickRocketOccupants(level, true);
			applyEngineExhaustFlow(level, elapsed, true);
			updateRocketDisplays(level, 0.0D, 1.0D, preheatShake(elapsed));
			updateRocketDeviceAnchors(level);
			updateRocketMountedScreenVisuals(level);
			updateRocketProjections(server, level);
			emitRocketExhaustParticles(level, elapsed, true);
		} else {
			long flightTicks = elapsed - PREHEAT_TICKS;
			tickRocketOccupants(level, false);
			BlockPos impact = tickRocketPhysics(level, flightTicks);
			if (impact != null) {
				crashRocket(server, level, impact);
				return;
			}
			resolveRocketHullContacts(level);
			tickRocketOccupants(level, true);
			applyEngineExhaustFlow(level, elapsed, false);
			updateRocketPhysicsDisplays(level);
			updateRocketDeviceAnchors(level);
			updateRocketMountedScreenVisuals(level);
			updateRocketProjections(server, level);
			emitRocketExhaustParticles(level, elapsed, false);
		}

		if (rocket.physicsY >= SPACE_ALTITUDE) {
			completeLaunch(server, level);
			return;
		}
		if (elapsed % 20L == 0L) {
			stateDirty = true;
			saveState(server);
		}
	}

	private static void startLaunch(MinecraftServer server, ServerLevel level) {
		boolean materialized = materializeRocketForLaunch(level);
		if (!materialized) {
			Lg2.LOGGER.warn("Rocket launch at {} could not capture every current rocket block; launch cancelled.", rocket.origin());
			resetArmedPowerScan();
			return;
		}
		if (rocket.engines == null || rocket.engines.isEmpty()) {
			Lg2.LOGGER.warn("Rocket launch at {} has no engine points; launch cancelled.", rocket.origin());
			return;
		}
		if (!rocket.hasRigidBodyProperties()) {
			rocket.initializePhysicsFromBlocks();
		}
		discardServerRocketDisplays(level);
		resetArmedPowerScan();
		rocket.clientOnlyVisuals = true;
		clearAllRocketProjections(server);
		rocket.stage = LaunchStage.LAUNCHING.name();
		rocket.launchElapsedTicks = 0L;
		rocket.captureOccupants(level);
		rocket.lastTriggerPowered = true;
		discardLegacyRocketExhaustDisplays(level);
		stateDirty = true;
		saveState(server);
		MonitorYandexMapsRuntime.setGpsEnabled(server, false);
		playSynchronizedLaunchSound(level, rocket.origin(), level.getRandom().nextLong());
		level.playSound(null, BlockPos.containing(rocket.origin()), SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 3.0F, 0.55F);
	}

	private static void completeLaunch(MinecraftServer server, ServerLevel level) {
		removeRocketMountedDeviceEndpoints(level);
		clearRocketDeviceAnchors(level);
		clearRocketMountedScreens(level);
		for (PersistedRocketBlock block : rocket.blocks) {
			discardRocketDisplay(level, block);
		}
		clearAllRocketProjections(server);
		discardLegacyRocketExhaustDisplays(level);
		for (ServerPlayer player : level.players()) {
			if (player.connection != null) {
				player.connection.send(new ClientboundStopSoundPacket(ROCKET_SOUND_ID, SoundSource.BLOCKS));
			}
		}
		rocket.stage = LaunchStage.COMPLETED.name();
		stateDirty = true;
		MonitorYandexMapsRuntime.setGpsEnabled(server, true);
		saveState(server);
	}

	private static void crashRocket(MinecraftServer server, ServerLevel level, BlockPos impact) {
		if (rocket == null || level == null || LaunchStage.from(rocket.stage) != LaunchStage.LAUNCHING) {
			return;
		}
		for (PersistedRocketBlock block : rocket.blocks) {
			discardRocketDisplay(level, block);
		}
		removeRocketMountedDeviceEndpoints(level);
		clearRocketDeviceAnchors(level);
		clearRocketMountedScreens(level);
		clearAllRocketProjections(server);
		discardLegacyRocketExhaustDisplays(level);

		BlockPos impactPosition = impact == null ? BlockPos.containing(rocket.pivot().add(rocket.physicsOffset())) : impact;
		int craterRadius = rocketCrashRadius();
		BlockPos craterCenter = resolveCraterGroundCenter(level, impactPosition);
		long craterSeed = level.getSeed() ^ craterCenter.asLong() ^ rocket.launchElapsedTicks;
		int fluidSurfaceY = fluidSurfaceY(level, craterCenter, craterRadius * 3);
		boolean underwaterImpact = fluidSurfaceY >= craterCenter.getY();
		// The primary detonation happens at the first solid impact point.  Water
		// is not treated as a solid wall: the blast clears a column through it and
		// excavates the terrain below instead of leaving a circular scar on top.
		carveImpactCrater(level, craterCenter, craterRadius, craterSeed, fluidSurfaceY);
		float explosionVolume = Math.min(40.0F, 5.0F + craterRadius * 0.40F);
		playServerWideExplosionSound(server, craterCenter.getCenter(), explosionVolume, 0.72F, craterSeed);
		level.sendParticles(ParticleTypes.EXPLOSION, craterCenter.getX() + 0.5D, craterCenter.getY() + 0.5D, craterCenter.getZ() + 0.5D,
				Mth.clamp(craterRadius * 3, 24, 240), craterRadius * 0.54D, craterRadius * 0.34D, craterRadius * 0.54D, 0.05D);
		if (underwaterImpact) {
			level.sendParticles(ParticleTypes.BUBBLE, craterCenter.getX() + 0.5D, craterCenter.getY() + 0.4D, craterCenter.getZ() + 0.5D,
					Mth.clamp(craterRadius * 28, 180, 1800), craterRadius * 0.62D, Math.max(1.0D, fluidSurfaceY - craterCenter.getY()), craterRadius * 0.62D, 0.14D);
		} else {
			level.sendParticles(ParticleTypes.LARGE_SMOKE, craterCenter.getX() + 0.5D, craterCenter.getY() + 0.4D, craterCenter.getZ() + 0.5D,
					Mth.clamp(craterRadius * 12, 120, 960), craterRadius * 0.85D, craterRadius * 0.42D, craterRadius * 0.85D, 0.10D);
		}
		// Fragments are launched only after the impact blast.  They retain the
		// actual body position, linear velocity and rotational velocity at impact.
		spawnCrashDebris(level, craterCenter, craterRadius, underwaterImpact);
		for (ServerPlayer player : level.players()) {
			if (player.connection != null) {
				player.connection.send(new ClientboundStopSoundPacket(ROCKET_SOUND_ID, SoundSource.BLOCKS));
			}
		}
		rocket.stage = LaunchStage.CRASHED.name();
		rocket.lastTriggerPowered = false;
		stateDirty = true;
		MonitorYandexMapsRuntime.setGpsEnabled(server, false);
		saveState(server);
	}

	private static int fluidSurfaceY(ServerLevel level, BlockPos start, int scanHeight) {
		if (level == null || start == null) {
			return Integer.MIN_VALUE;
		}
		int highest = Integer.MIN_VALUE;
		for (int y = start.getY(); y <= start.getY() + Math.max(1, scanHeight); y++) {
			if (!level.getFluidState(new BlockPos(start.getX(), y, start.getZ())).isEmpty()) {
				highest = y;
			}
		}
		return highest;
	}

	/** A rocket can strike a tree trunk, fence, or other narrow obstruction above
	 * the terrain. The blast may still occur there, but the excavated crater is
	 * placed on the surrounding ground rather than left in the canopy. */
	private static BlockPos resolveCraterGroundCenter(ServerLevel level, BlockPos impact) {
		if (level == null || impact == null) {
			return impact;
		}
		for (int y = Math.min(impact.getY(), level.getMaxY() - 1); y >= level.getMinY(); y--) {
			BlockPos candidate = new BlockPos(impact.getX(), y, impact.getZ());
			if (hasTerrainFoundation(level, candidate)) {
				return candidate;
			}
		}
		return impact;
	}

	/** A terrain cell needs a broad base, not just the vertical pole of a tree.
	 * Foliage and other non-occluding blocks are never considered terrain. */
	private static boolean hasTerrainFoundation(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || !isStructuralImpactBlock(level.getBlockState(pos))) {
			return false;
		}
		int horizontalNeighbours = 0;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			if (isStructuralImpactBlock(level.getBlockState(pos.relative(direction)))) {
				horizontalNeighbours++;
			}
		}
		return horizontalNeighbours >= 2;
	}

	private static void carveImpactCrater(ServerLevel level, BlockPos center, int radius, long seed, int fluidSurfaceY) {
		// The explosion is a sphere whose centre is lifted above the terrain.
		// Only its lower cap cuts into the ground; its upper volume clears trees,
		// roofs and other blocks instead of leaving a buried "bowl".
		double verticalRadius = Math.max(4.0D, radius * 0.72D);
		double sphereCenterY = center.getY() + verticalRadius * 0.58D;
		for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
			for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
				int dx = x - center.getX();
				int dz = z - center.getZ();
				double distance = Math.sqrt(dx * dx + dz * dz);
				if (distance > radius) {
					continue;
				}
				double angle = Math.atan2(dz, dx);
				double edgeNoise = craterNoise(x, z, seed);
				double localRadius = radius * (0.82D
						+ 0.11D * Math.sin(angle * 5.0D + edgeNoise * 2.0D)
						+ 0.07D * Math.sin(angle * 9.0D - edgeNoise * 4.0D));
				if (distance > localRadius) {
					continue;
				}
				double normalizedDistance = distance / Math.max(0.001D, localRadius);
				double verticalExtent = verticalRadius * Math.sqrt(Math.max(0.0D, 1.0D - normalizedDistance * normalizedDistance));
				if (verticalExtent < 0.5D) {
					continue;
				}
				int floorY = Mth.floor(sphereCenterY - verticalExtent);
				int ceilingY = Mth.ceil(sphereCenterY + verticalExtent);
				BlockPos floor = new BlockPos(x, floorY, z);
				boolean stableFloor = floorY <= center.getY()
						&& isStructuralImpactBlock(level.getBlockState(floor))
						&& isStructuralImpactBlock(level.getBlockState(floor.below()));
				for (int y = floorY; y <= ceilingY; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (!state.isAir() && !state.is(Blocks.BEDROCK)) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
					}
				}
				if (!stableFloor) {
					continue;
				}
				double scorch = craterNoise(x * 17, z * 17, seed ^ 0x6A09E667F3BCC909L);
				if (fluidSurfaceY < center.getY() && normalizedDistance < 0.58D && scorch > -0.18D) {
					level.setBlock(floor, Blocks.MAGMA_BLOCK.defaultBlockState(), 3);
					if (normalizedDistance < 0.32D && scorch > 0.30D && level.getBlockState(floor.above()).isAir()) {
						level.setBlock(floor.above(), Blocks.LAVA.defaultBlockState(), 3);
					}
				} else if (fluidSurfaceY < center.getY() && scorch > 0.10D) {
					level.setBlock(floor, Blocks.BLACKSTONE.defaultBlockState(), 3);
				}
				if (fluidSurfaceY < center.getY() && normalizedDistance > 0.48D && normalizedDistance < 0.96D && scorch > 0.52D) {
					BlockPos fire = floor.above();
					if (level.getBlockState(fire).isAir()) {
						level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 3);
					}
				}
			}
		}
	}

	private static int rocketCrashRadius() {
		if (rocket == null) {
			return 8;
		}
		int width = Math.max(1, rocket.maxX - rocket.minX + 1);
		int height = Math.max(1, rocket.maxY - rocket.minY + 1);
		int depth = Math.max(1, rocket.maxZ - rocket.minZ + 1);
		// Halved from the previous version: the diameter remains proportional to
		// the largest physical rocket dimension without flattening half a biome.
		int intendedRadius = (int) Math.ceil(Math.max(width, Math.max(height, depth)) * 2.5D);
		return Mth.clamp(intendedRadius, 6, MAX_CRASH_RADIUS);
	}

	private static int craterDepth(int radius) {
		return Math.max(3, (int) Math.round(radius * 0.42D));
	}

	private static double craterNoise(int x, int z, long seed) {
		long value = seed ^ (long) x * 0x9E3779B97F4A7C15L ^ (long) z * 0xC2B2AE3D27D4EB4FL;
		value ^= value >>> 33;
		value *= 0xFF51AFD7ED558CCDL;
		value ^= value >>> 33;
		value *= 0xC4CEB9FE1A85EC53L;
		value ^= value >>> 33;
		return ((value >>> 11) & 0xFFFFL) / 32767.5D - 1.0D;
	}

	private static void spawnCrashDebris(ServerLevel level, BlockPos center, int craterRadius, boolean underwaterImpact) {
		if (rocket == null || rocket.blocks == null || rocket.blocks.isEmpty()) {
			return;
		}
		if (rocket.debris == null) {
			rocket.debris = new ArrayList<>();
		}
		List<PersistedRocketBlock> available = new ArrayList<>(rocket.blocks);
		int mainWreckSize = Math.min(
				available.size(),
				Mth.clamp((int) Math.round(rocket.blocks.size() * 0.45D), 4, MAX_LARGE_WRECK_BLOCKS)
		);
		List<PersistedRocketBlock> mainWreck = takeLargeCrashWreck(available, mainWreckSize);
		spawnCrashPiece(level, mainWreck, center, craterRadius, underwaterImpact);

		int blockBudget = Mth.clamp(Math.max(24, rocket.blocks.size() / 3), 24, MAX_CRASH_DEBRIS);
		int consumed = 0;
		while (consumed < blockBudget && !available.isEmpty()) {
			int desiredPieceSize = level.getRandom().nextFloat() < 0.58F
					? 2 + level.getRandom().nextInt(5)
					: 1;
			List<PersistedRocketBlock> piece = takeCrashPiece(available, Math.min(desiredPieceSize, blockBudget - consumed), level);
			if (piece.isEmpty()) {
				break;
			}
			consumed += piece.size();
			spawnCrashPiece(level, piece, center, craterRadius, underwaterImpact);
		}
	}

	private static List<PersistedRocketBlock> takeLargeCrashWreck(List<PersistedRocketBlock> available, int targetSize) {
		List<PersistedRocketBlock> wreck = new ArrayList<>();
		if (available == null || available.isEmpty() || targetSize <= 0 || rocket == null) {
			return wreck;
		}
		Map<BlockPos, PersistedRocketBlock> remaining = new HashMap<>();
		for (PersistedRocketBlock block : available) {
			remaining.put(block.pos(), block);
		}
		PersistedRocketBlock seed = null;
		double closest = Double.MAX_VALUE;
		Vec3 pivot = rocket.pivot();
		for (PersistedRocketBlock block : available) {
			double distance = block.pos().getCenter().distanceToSqr(pivot);
			if (distance < closest) {
				closest = distance;
				seed = block;
			}
		}
		if (seed == null) {
			return wreck;
		}
		List<BlockPos> frontier = new ArrayList<>();
		frontier.add(seed.pos());
		for (int index = 0; wreck.size() < targetSize && !remaining.isEmpty(); index++) {
			if (index >= frontier.size()) {
				// A rocket can contain disconnected decorative sections.  Keep a
				// single principal wreck by attaching the nearest remaining section.
				frontier.add(remaining.keySet().iterator().next());
			}
			BlockPos nextPos = frontier.get(index);
			PersistedRocketBlock next = remaining.remove(nextPos);
			if (next == null) {
				continue;
			}
			wreck.add(next);
			for (Direction direction : Direction.values()) {
				BlockPos neighbour = next.pos().relative(direction);
				if (remaining.containsKey(neighbour)) {
					frontier.add(neighbour);
				}
			}
		}
		available.removeAll(wreck);
		return wreck;
	}

	private static void placeLargeCrashWreck(
			ServerLevel level,
			List<PersistedRocketBlock> wreck,
			BlockPos craterCenter,
			int craterRadius
	) {
		if (level == null || wreck == null || wreck.isEmpty() || rocket == null) {
			return;
		}
		Quaternionf rotation = nearestBlockGridRotation(rocket.orientation());
		Vec3 pivot = rocket.pivot();
		double lowestOffset = Double.MAX_VALUE;
		for (PersistedRocketBlock source : wreck) {
			Vec3 localCenter = source.pos().getCenter().subtract(pivot);
			lowestOffset = Math.min(lowestOffset, rotate(rotation, localCenter).y);
		}
		// The main wreck settles above the irregular, molten crater floor rather
		// than being buried inside its magma pockets.
		double craterFloor = craterCenter.getY() - craterDepth(craterRadius) + 2.0D;
		Vec3 wreckPivot = new Vec3(
				craterCenter.getX() + 0.5D,
				craterFloor - lowestOffset,
				craterCenter.getZ() + 0.5D
		);
		Rotation blockRotation = wreckBlockYawRotation(rotation);
		for (PersistedRocketBlock source : wreck) {
			BlockState state = deserializeBlockState(source.state);
			if (state.isAir()) {
				continue;
			}
			Vec3 localCenter = source.pos().getCenter().subtract(pivot);
			Vec3 targetCenter = wreckPivot.add(rotate(rotation, localCenter));
			BlockPos target = BlockPos.containing(targetCenter);
			if (!level.getBlockState(target).isAir()) {
				continue;
			}
			level.setBlock(target, state.rotate(blockRotation), 3);
		}
	}

	private static Quaternionf nearestBlockGridRotation(Quaternionf actualRotation) {
		Quaternionf actual = new Quaternionf(actualRotation).normalize();
		Quaternionf best = new Quaternionf();
		double bestSimilarity = -1.0D;
		float quarterTurn = (float) (Math.PI * 0.5D);
		for (int xTurns = 0; xTurns < 4; xTurns++) {
			for (int yTurns = 0; yTurns < 4; yTurns++) {
				for (int zTurns = 0; zTurns < 4; zTurns++) {
					Quaternionf candidate = new Quaternionf().rotateXYZ(
							xTurns * quarterTurn,
							yTurns * quarterTurn,
							zTurns * quarterTurn
					);
					double similarity = Math.abs(
							actual.x * candidate.x + actual.y * candidate.y
									+ actual.z * candidate.z + actual.w * candidate.w
					);
					if (similarity > bestSimilarity) {
						bestSimilarity = similarity;
						best.set(candidate);
					}
				}
			}
		}
		return best;
	}

	private static Rotation wreckBlockYawRotation(Quaternionf rotation) {
		Vec3 transformedNorth = rotate(rotation, new Vec3(0.0D, 0.0D, -1.0D));
		if (Math.abs(transformedNorth.y) > 0.90D) {
			return Rotation.NONE;
		}
		if (Math.abs(transformedNorth.x) > Math.abs(transformedNorth.z)) {
			return transformedNorth.x > 0.0D ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
		}
		return transformedNorth.z > 0.0D ? Rotation.CLOCKWISE_180 : Rotation.NONE;
	}

	private static List<PersistedRocketBlock> takeCrashPiece(
			List<PersistedRocketBlock> available,
			int targetSize,
			ServerLevel level
	) {
		List<PersistedRocketBlock> piece = new ArrayList<>();
		if (available == null || available.isEmpty() || targetSize <= 0) {
			return piece;
		}
		piece.add(available.remove(level.getRandom().nextInt(available.size())));
		while (piece.size() < targetSize && !available.isEmpty()) {
			int closestIndex = 0;
			double closestDistance = Double.MAX_VALUE;
			for (int index = 0; index < available.size(); index++) {
				PersistedRocketBlock candidate = available.get(index);
				for (PersistedRocketBlock existing : piece) {
					double dx = candidate.x - existing.x;
					double dy = candidate.y - existing.y;
					double dz = candidate.z - existing.z;
					double distance = dx * dx + dy * dy + dz * dz;
					if (distance < closestDistance) {
						closestDistance = distance;
						closestIndex = index;
					}
				}
			}
			piece.add(available.remove(closestIndex));
		}
		return piece;
	}

	private static void spawnCrashPiece(
			ServerLevel level,
			List<PersistedRocketBlock> piece,
			BlockPos center,
			int craterRadius,
			boolean underwaterImpact
	) {
		if (piece.isEmpty() || rocket == null) {
			return;
		}
		PersistedRocketBlock anchor = piece.get(0);
		DebrisState debris = new DebrisState();
		Quaternionf impactOrientation = rocket.orientation().normalize();
		Vec3 impactPivot = rocket.pivot().add(rocket.physicsOffset());
		Vec3 anchorOffset = rotate(impactOrientation, anchor.pos().getCenter().subtract(rocket.pivot()));
		Vec3 anchorPosition = impactPivot.add(anchorOffset);
		debris.x = anchorPosition.x;
		debris.y = anchorPosition.y;
		debris.z = anchorPosition.z;
		Vec3 radial = unit(anchorPosition.subtract(center.getCenter()).add(
					level.getRandom().nextDouble() - 0.5D,
					0.28D + level.getRandom().nextDouble() * 0.62D,
					level.getRandom().nextDouble() - 0.5D
		), new Vec3(0.0D, 1.0D, 0.0D));
		double blastImpulse = 0.20D + level.getRandom().nextDouble() * 0.38D + craterRadius * 0.007D;
		if (underwaterImpact) {
			blastImpulse *= 0.58D;
		}
		Vec3 worldAngularVelocity = rotate(impactOrientation, new Vec3(
				rocket.angularVelocityX, rocket.angularVelocityY, rocket.angularVelocityZ
		));
		Vec3 inheritedVelocity = new Vec3(rocket.velocityX, rocket.velocityY, rocket.velocityZ)
				.add(cross(worldAngularVelocity, anchorOffset));
		Vec3 launchVelocity = inheritedVelocity.add(radial.scale(blastImpulse));
		debris.velocityX = launchVelocity.x;
		debris.velocityY = launchVelocity.y;
		debris.velocityZ = launchVelocity.z;
		debris.setOrientation(impactOrientation);
		debris.angularVelocityX = rocket.angularVelocityX + (level.getRandom().nextDouble() - 0.5D) * 0.24D;
		debris.angularVelocityY = rocket.angularVelocityY + (level.getRandom().nextDouble() - 0.5D) * 0.24D;
		debris.angularVelocityZ = rocket.angularVelocityZ + (level.getRandom().nextDouble() - 0.5D) * 0.24D;
		for (PersistedRocketBlock source : piece) {
			BlockState state = deserializeBlockState(source.state);
			if (state.isAir()) {
				continue;
			}
			DebrisBlock block = new DebrisBlock(source.state, source.x - anchor.x, source.y - anchor.y, source.z - anchor.z);
			Display.BlockDisplay display = createCrashDebrisDisplay(level, state, debris, block);
			level.addFreshEntity(display);
			block.displayUuid = display.getUUID().toString();
			debris.blocks.add(block);
		}
		if (!debris.blocks.isEmpty()) {
			rocket.debris.add(debris);
		}
	}

	private static void spawnCrashItem(ServerLevel level, BlockState state, BlockPos center, int craterRadius) {
		if (state == null || state.isAir()) {
			return;
		}
		ItemStack stack = new ItemStack(state.getBlock().asItem());
		if (stack.isEmpty()) {
			return;
		}
		double x = center.getX() + 0.5D + (level.getRandom().nextDouble() - 0.5D) * craterRadius;
		double y = center.getY() + 0.65D + level.getRandom().nextDouble() * 1.8D;
		double z = center.getZ() + 0.5D + (level.getRandom().nextDouble() - 0.5D) * craterRadius;
		ItemEntity item = new ItemEntity(level, x, y, z, stack);
		item.setDeltaMovement(
				(level.getRandom().nextDouble() - 0.5D) * 0.32D,
				0.16D + level.getRandom().nextDouble() * 0.21D,
				(level.getRandom().nextDouble() - 0.5D) * 0.32D
		);
		item.setPickUpDelay(20);
		level.addFreshEntity(item);
	}

	private static Display.BlockDisplay createCrashDebrisDisplay(ServerLevel level, BlockState state, DebrisState debris, DebrisBlock block) {
		Display.BlockDisplay display = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
		display.addTag(DEBRIS_DISPLAY_TAG);
		display.setPos(debris.x + block.offsetX, debris.y + block.offsetY, debris.z + block.offsetZ);
		display.setBlockState(state);
		display.setNoGravity(true);
		display.setInvulnerable(true);
		display.setSilent(true);
		display.setInvisible(false);
		display.setShadowRadius(0.0F);
		display.setShadowStrength(0.0F);
		display.setViewRange(128.0F);
		display.setPosRotInterpolationDuration(1);
		display.setTransformationInterpolationDelay(0);
		display.setTransformationInterpolationDuration(1);
		return display;
	}

	private static void discardServerRocketDisplays(ServerLevel level) {
		if (level == null || rocket == null || rocket.blocks == null) {
			return;
		}
		for (PersistedRocketBlock block : rocket.blocks) {
			discardRocketDisplay(level, block);
			block.displayUuid = null;
		}
	}

	/**
	 * A BlockDisplay has one server-world transform, so it cannot itself look
	 * distant to one player and close to another. During flight every player
	 * receives exactly one private, client-only display copy. Beyond the normal
	 * tracking horizon its centre stays at the horizon and its size follows the
	 * perspective ratio horizon/distance.
	 */
	private static void updateRocketProjections(MinecraftServer server, ServerLevel level) {
		if (server == null || level == null || rocket == null || rocket.blocks == null) {
			return;
		}
		boolean sendFrame = level.getGameTime() % DISPLAY_INTERPOLATION_TICKS == 0L;
		Set<UUID> activePlayers = new HashSet<>();
		for (ServerPlayer player : level.players()) {
			if (player.connection == null) {
				continue;
			}
			activePlayers.add(player.getUUID());
			RocketProjectionView view = ROCKET_PROJECTIONS.get(player.getUUID());
			boolean recreate = view == null || view.displays.size() != rocket.blocks.size();
			if (recreate) {
				clearRocketProjection(player);
				view = new RocketProjectionView();
				ROCKET_PROJECTIONS.put(player.getUUID(), view);
			}
			RocketProjectionPose pose = rocketProjectionPose(player);
			if (recreate) {
				for (PersistedRocketBlock block : rocket.blocks) {
					Display.BlockDisplay display = createRocketProjectionDisplay(level, deserializeBlockState(block.state));
					updateRocketProjectionDisplay(display, block, pose);
					view.displays.add(display);
					sendRocketProjectionSpawn(player, display);
				}
			} else if (sendFrame) {
				for (int index = 0; index < rocket.blocks.size(); index++) {
					Display.BlockDisplay display = view.displays.get(index);
					PersistedRocketBlock block = rocket.blocks.get(index);
					updateRocketProjectionDisplay(display, block, pose);
					sendRocketProjectionFrame(player, display);
				}
			}
		}
		for (UUID playerId : new ArrayList<>(ROCKET_PROJECTIONS.keySet())) {
			if (activePlayers.contains(playerId)) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				clearRocketProjection(player);
			} else {
				ROCKET_PROJECTIONS.remove(playerId);
			}
		}
	}

	private static RocketProjectionPose rocketProjectionPose(ServerPlayer player) {
		Vec3 pivot = rocket.pivot();
		Quaternionf orientation = rocket.orientation();
		if (rocket.launchElapsedTicks < PREHEAT_TICKS) {
			pivot = pivot.add(preheatShake(rocket.launchElapsedTicks));
			orientation = new Quaternionf();
		} else {
			pivot = pivot.add(rocket.physicsOffset());
		}
		Vec3 eye = player.getEyePosition();
		Vec3 offset = pivot.subtract(eye);
		double distance = Math.max(0.001D, offset.length());
		double scale = Math.min(1.0D, ROCKET_PROJECTION_DISTANCE / distance);
		Vec3 projectedPivot = scale >= 1.0D
				? pivot
				: eye.add(offset.scale(ROCKET_PROJECTION_DISTANCE / distance));
		return new RocketProjectionPose(projectedPivot, orientation, scale);
	}

	private static Display.BlockDisplay createRocketProjectionDisplay(ServerLevel level, BlockState state) {
		Display.BlockDisplay display = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
		display.setBlockState(state);
		display.setNoGravity(true);
		display.setInvulnerable(true);
		display.setSilent(true);
		display.setInvisible(false);
		display.setShadowRadius(0.0F);
		display.setShadowStrength(0.0F);
		display.setViewRange(ROCKET_DISPLAY_VIEW_RANGE);
		display.setPosRotInterpolationDuration(DISPLAY_INTERPOLATION_TICKS);
		display.setTransformationInterpolationDelay(0);
		display.setTransformationInterpolationDuration(DISPLAY_INTERPOLATION_TICKS);
		return display;
	}

	private static void updateRocketProjectionDisplay(
			Display.BlockDisplay display,
			PersistedRocketBlock block,
			RocketProjectionPose pose
	) {
		Vec3 local = new Vec3(block.x, block.y, block.z).subtract(rocket.pivot()).scale(pose.scale);
		Vec3 position = pose.pivot.add(rotate(pose.orientation, local));
		display.setTransformation(new Transformation(
				new Vector3f(),
				new Quaternionf(pose.orientation),
				new Vector3f((float) pose.scale, (float) pose.scale, (float) pose.scale),
				new Quaternionf()
		));
		display.setPos(position.x, position.y, position.z);
	}

	@SuppressWarnings("unchecked")
	private static void sendRocketProjectionSpawn(ServerPlayer player, Display.BlockDisplay display) {
		if (player == null || player.connection == null || display == null || !(display.level() instanceof ServerLevel level)) {
			return;
		}
		ServerEntity tracker = new ServerEntity(level, display, 1, false, ROCKET_PROJECTION_SYNCHRONIZER);
		tracker.sendPairingData(player, packet -> player.connection.send((Packet<? super ClientGamePacketListener>) packet));
		List<SynchedEntityData.DataValue<?>> values = display.getEntityData().getNonDefaultValues();
		if (values != null && !values.isEmpty()) {
			player.connection.send(new ClientboundSetEntityDataPacket(display.getId(), values));
		}
	}

	private static void sendRocketProjectionFrame(ServerPlayer player, Display.BlockDisplay display) {
		if (player == null || player.connection == null || display == null) {
			return;
		}
		PositionMoveRotation pose = new PositionMoveRotation(display.position(), Vec3.ZERO, 0.0F, 0.0F);
		player.connection.send(ClientboundTeleportEntityPacket.teleport(
				display.getId(), pose, ABSOLUTE_ENTITY_TELEPORT, false
		));
		List<SynchedEntityData.DataValue<?>> values = display.getEntityData().getNonDefaultValues();
		if (values != null && !values.isEmpty()) {
			player.connection.send(new ClientboundSetEntityDataPacket(display.getId(), values));
		}
	}

	private static void clearAllRocketProjections(MinecraftServer server) {
		if (server == null || ROCKET_PROJECTIONS.isEmpty()) {
			return;
		}
		for (UUID playerId : new ArrayList<>(ROCKET_PROJECTIONS.keySet())) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				clearRocketProjection(player);
			} else {
				ROCKET_PROJECTIONS.remove(playerId);
			}
		}
	}

	private static void clearRocketProjection(ServerPlayer player) {
		if (player == null) {
			return;
		}
		RocketProjectionView view = ROCKET_PROJECTIONS.remove(player.getUUID());
		if (view == null || player.connection == null || view.displays.isEmpty()) {
			return;
		}
		int[] ids = new int[view.displays.size()];
		for (int index = 0; index < view.displays.size(); index++) {
			ids[index] = view.displays.get(index).getId();
		}
		player.connection.send(new ClientboundRemoveEntitiesPacket(ids));
	}

	private static void tickCrashDebris(MinecraftServer server, ServerLevel level) {
		if (rocket == null || rocket.debris == null || rocket.debris.isEmpty()) {
			return;
		}
		for (int index = rocket.debris.size() - 1; index >= 0; index--) {
			DebrisState debris = rocket.debris.get(index);
			debris.ensureBlocks();
			debris.age++;
			Vec3 previousPosition = new Vec3(debris.x, debris.y, debris.z);
			boolean inFluid = !level.getFluidState(BlockPos.containing(previousPosition)).isEmpty();
			double gravity = inFluid ? 0.012D : 0.033D;
			double drag = inFluid ? 0.78D : 0.982D;
			debris.velocityY = (debris.velocityY - gravity) * drag;
			debris.velocityX *= drag;
			debris.velocityZ *= drag;
			debris.x += debris.velocityX;
			debris.y += debris.velocityY;
			debris.z += debris.velocityZ;
			Quaternionf rotation = debris.advanceRotation();
			BlockPos impact = findDebrisImpactAlongPath(level, debris, previousPosition, new Vec3(debris.x, debris.y, debris.z), rotation);
			BlockPos placement = impact == null ? BlockPos.containing(debris.x, debris.y, debris.z) : impact;
			if (impact != null) {
				createDebrisImpact(level, debris, impact);
				settleDebris(level, debris, placement, rotation);
				discardCrashDebrisDisplays(level, debris);
				rocket.debris.remove(index);
				stateDirty = true;
				continue;
			}
			boolean canSettle = debris.age > 110 || (debris.age > 14 && canSettleDebris(level, debris, placement, rotation));
			if (canSettle) {
				settleDebris(level, debris, placement, rotation);
				discardCrashDebrisDisplays(level, debris);
				rocket.debris.remove(index);
				stateDirty = true;
				continue;
			}
			boolean hasDisplay = false;
			for (DebrisBlock block : debris.blocks) {
				Display.BlockDisplay display = resolveCrashDebrisDisplay(level, block.displayUuid);
				if (display == null) {
					continue;
				}
				hasDisplay = true;
				Vec3 offset = rotate(rotation, new Vec3(block.offsetX, block.offsetY, block.offsetZ));
				display.setTransformation(new Transformation(
						new Vector3f(), new Quaternionf(rotation), new Vector3f(1.0F, 1.0F, 1.0F), new Quaternionf()
				));
				display.setPos(debris.x + offset.x, debris.y + offset.y, debris.z + offset.z);
			}
			if (!hasDisplay) {
				// If a chunk was unloaded and its entities disappeared, settle it on
				// the next tick instead of retaining a ghost entry forever.
				debris.age = Math.max(debris.age, 109);
			}
		}
		if (rocket.debris.isEmpty() || level.getGameTime() % 20L == 0L) {
			stateDirty = true;
			saveState(server);
		}
	}

	private static Quaternionf debrisRotation(DebrisState debris) {
		return debris == null ? new Quaternionf() : debris.orientation();
	}

	private static BlockPos findDebrisImpactAlongPath(
			ServerLevel level,
			DebrisState debris,
			Vec3 start,
			Vec3 end,
			Quaternionf rotation
	) {
		if (level == null || debris == null || start == null || end == null) {
			return null;
		}
		int steps = Mth.clamp((int) Math.ceil(start.distanceTo(end) * 4.0D), 1, 16);
		for (int step = 1; step <= steps; step++) {
			Vec3 position = start.add(end.subtract(start).scale(step / (double) steps));
			BlockPos anchor = BlockPos.containing(position);
			for (DebrisBlock block : debris.blocks) {
				BlockPos target = rotatedDebrisTarget(anchor, block, rotation);
				BlockState state = level.getBlockState(target);
				if (isStructuralImpactBlock(state)) {
					return target;
				}
			}
		}
		return null;
	}

	private static void createDebrisImpact(ServerLevel level, DebrisState debris, BlockPos impact) {
		if (level == null || debris == null || impact == null || rocket == null) {
			return;
		}
		double fraction = debris.blocks.size() / (double) Math.max(1, rocket.blocks.size());
		double speed = Math.sqrt(debris.velocityX * debris.velocityX + debris.velocityY * debris.velocityY + debris.velocityZ * debris.velocityZ);
		double energyScale = Mth.clamp(speed / 0.36D, 0.35D, 1.35D);
		int radius = Mth.clamp((int) Math.round(rocketCrashRadius() * 0.30D * Math.cbrt(fraction) * energyScale), 1, Math.max(1, rocketCrashRadius() / 3));
		long seed = level.getSeed() ^ impact.asLong() ^ (long) debris.age * 0x9E3779B97F4A7C15L;
		BlockPos craterCenter = resolveCraterGroundCenter(level, impact);
		int surfaceY = fluidSurfaceY(level, craterCenter, radius * 3);
		carveImpactCrater(level, craterCenter, radius, seed, surfaceY);
		level.sendParticles(ParticleTypes.EXPLOSION, craterCenter.getX() + 0.5D, craterCenter.getY() + 0.5D, craterCenter.getZ() + 0.5D,
				Mth.clamp(radius * 2, 2, 28), radius * 0.24D, radius * 0.18D, radius * 0.24D, 0.025D);
		if (surfaceY >= craterCenter.getY()) {
			level.sendParticles(ParticleTypes.BUBBLE, craterCenter.getX() + 0.5D, craterCenter.getY() + 0.4D, craterCenter.getZ() + 0.5D,
					Mth.clamp(radius * 12, 8, 96), radius * 0.28D, Math.max(0.5D, surfaceY - craterCenter.getY()), radius * 0.28D, 0.08D);
		}
	}

	private static boolean canSettleDebris(ServerLevel level, DebrisState debris, BlockPos anchor, Quaternionf rotation) {
		return !supportedDebrisTargets(level, debris, anchor, rotation).isEmpty();
	}

	private static void settleDebris(ServerLevel level, DebrisState debris, BlockPos anchor, Quaternionf rotation) {
		Rotation blockRotation = wreckBlockYawRotation(rotation);
		Set<BlockPos> supportedTargets = supportedDebrisTargets(level, debris, anchor, rotation);
		for (DebrisBlock block : debris.blocks) {
			BlockPos target = rotatedDebrisTarget(anchor, block, rotation);
			if (supportedTargets.contains(target) && isDebrisPassThroughBlock(level.getBlockState(target))) {
				level.setBlock(target, deserializeBlockState(block.state).rotate(blockRotation), 3);
			}
		}
	}

	/** Only settle a fragment from the ground upward.  The old method found one
	 * supported voxel then placed the whole rotated fragment, leaving most of it
	 * suspended in air when its orientation was uneven. */
	private static Set<BlockPos> supportedDebrisTargets(ServerLevel level, DebrisState debris, BlockPos anchor, Quaternionf rotation) {
		Set<BlockPos> candidates = new HashSet<>();
		if (level == null || debris == null || debris.blocks == null) {
			return candidates;
		}
		for (DebrisBlock block : debris.blocks) {
			BlockPos target = rotatedDebrisTarget(anchor, block, rotation);
			if (isDebrisPassThroughBlock(level.getBlockState(target))) {
				candidates.add(target);
			}
		}
		Set<BlockPos> supported = new HashSet<>();
		boolean changed;
		do {
			changed = false;
			for (BlockPos target : candidates) {
				if (supported.contains(target)) {
					continue;
				}
				if (isStructuralImpactBlock(level.getBlockState(target.below())) || supported.contains(target.below())) {
					supported.add(target);
					changed = true;
				}
			}
		} while (changed);
		return supported;
	}

	private static BlockPos rotatedDebrisTarget(BlockPos anchor, DebrisBlock block, Quaternionf rotation) {
		Vec3 offset = rotate(rotation, new Vec3(block.offsetX, block.offsetY, block.offsetZ));
		return BlockPos.containing(anchor.getX() + offset.x, anchor.getY() + offset.y, anchor.getZ() + offset.z);
	}

	private static Display.BlockDisplay resolveCrashDebrisDisplay(ServerLevel level, String rawUuid) {
		if (level == null || rawUuid == null || rawUuid.isBlank()) {
			return null;
		}
		try {
			Entity entity = level.getEntity(UUID.fromString(rawUuid));
			return entity instanceof Display.BlockDisplay display && display.getTags().contains(DEBRIS_DISPLAY_TAG) ? display : null;
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static void discardCrashDebris(ServerLevel level) {
		if (level == null || rocket == null || rocket.debris == null) {
			return;
		}
		for (DebrisState debris : rocket.debris) {
			discardCrashDebrisDisplays(level, debris);
		}
		rocket.debris.clear();
	}

	private static void discardCrashDebrisDisplays(ServerLevel level, DebrisState debris) {
		if (level == null || debris == null) {
			return;
		}
		debris.ensureBlocks();
		for (DebrisBlock block : debris.blocks) {
			Display.BlockDisplay display = resolveCrashDebrisDisplay(level, block.displayUuid);
			if (display != null) {
				display.discard();
			}
		}
	}

	private static Vec3 preheatShake(long elapsed) {
		double ramp = Mth.clamp(elapsed / (double) PREHEAT_TICKS, 0.0D, 1.0D);
		double amplitude = 0.012D + ramp * 0.050D;
		// Never move sideways or down: those directions can push the bottom
		// BlockDisplays into a pad and make their shaded faces look black.
		double upwardBounce = Math.pow((Math.sin(elapsed * 1.91D) + 1.0D) * 0.5D, 2.0D) * amplitude;
		return new Vec3(0.0D, upwardBounce, 0.0D);
	}

	private static void updateRocketDisplays(ServerLevel level, double ascent, double scale, Vec3 shake) {
		if (rocket == null || rocket.clientOnlyVisuals) {
			return;
		}
		Vec3 origin = rocket.origin();
		for (PersistedRocketBlock block : rocket.blocks) {
			Display.BlockDisplay display = resolveRocketDisplay(level, block);
			if (display == null) {
				continue;
			}
			double x = origin.x + (block.x - origin.x) * scale + shake.x;
			double y = origin.y + ascent + (block.y - origin.y) * scale + shake.y;
			double z = origin.z + (block.z - origin.z) * scale + shake.z;
			display.setPosRotInterpolationDuration(DISPLAY_INTERPOLATION_TICKS);
			display.setTransformationInterpolationDelay(0);
			display.setTransformationInterpolationDuration(DISPLAY_INTERPOLATION_TICKS);
			display.setTransformation(new Transformation(
					new Vector3f(),
					new Quaternionf(),
					new Vector3f((float) scale, (float) scale, (float) scale),
					new Quaternionf()
			));
			display.setPos(x, y, z);
		}
	}

	/**
	 * Advances a rigid-body simulation in body coordinates.  The centre of mass,
	 * engine attachment points and aerodynamic centre are fixed to the body and
	 * transformed by its current quaternion every tick.  Thus a tilted rocket
	 * has tilted nozzles and a tilted centre-of-mass/centre-of-pressure system;
	 * no steering force is injected to fake a turn.
	 */
	private static BlockPos tickRocketPhysics(ServerLevel level, long flightTicks) {
		if (rocket == null || rocket.blocks == null || rocket.blocks.isEmpty()) {
			return null;
		}
		if (!rocket.hasRigidBodyProperties()) {
			rocket.initializePhysicsFromBlocks();
		}
		if (rocket.engines == null || rocket.engines.isEmpty()) {
			return BlockPos.containing(rocket.pivot());
		}

		RocketMassProperties massProperties = rocket.currentMassProperties();
		double mass = massProperties.mass();
		double throttle = 0.76D + 0.24D * Mth.clamp(flightTicks / 70.0D, 0.0D, 1.0D);
		Vec3 previousOffset = rocket.physicsOffset();
		Quaternionf previousOrientation = rocket.orientation().normalize();
		Quaternionf orientation = new Quaternionf(previousOrientation);
		// A passenger must shift the centre of mass and make the rocket unstable,
		// but must not make a valid launch pad build unable to leave the ground
		// solely because a player boarded it.  The engine rating therefore retains
		// the baseline thrust-to-weight ratio for the current loaded vehicle.
		double engineForce = mass * ROCKET_THRUST_PER_TICK * throttle / rocket.engines.size();
		Vec3 pivot = massProperties.centre();
		Vec3 thrustPerEngine = new Vec3(0.0D, engineForce, 0.0D);
		Vec3 localTorque = Vec3.ZERO;
		for (PersistedEnginePoint engine : rocket.engines) {
			// Sum r x F for every nozzle.  Averaging the engine locations and
			// scaling/clamping the result made an off-centre cluster far too weak.
			localTorque = localTorque.add(cross(engine.position().subtract(pivot), thrustPerEngine));
		}

		// Air resistance acts opposite the velocity at the centre of pressure,
		// not at the centre of mass.  A side-slip therefore makes a real moment.
		// With an existing roll this is the familiar coning/precession of an
		// unstable rocket rather than a world-axis spin or a scripted wobble.
		Vec3 localVelocity = inverseRotate(orientation, new Vec3(rocket.velocityX, rocket.velocityY, rocket.velocityZ));
		Vec3 lateralVelocity = new Vec3(localVelocity.x, 0.0D, localVelocity.z);
		double lateralSpeed = lateralVelocity.length();
		Vec3 aerodynamicForce = Vec3.ZERO;
		if (lateralSpeed > 0.00001D) {
			double magnitude = mass * ROCKET_AERODYNAMIC_FORCE * rocket.aerodynamicArea()
					* lateralSpeed * (0.035D + lateralSpeed);
			aerodynamicForce = lateralVelocity.scale(-magnitude / lateralSpeed);
		}
		double axialSpeed = localVelocity.y;
		if (Math.abs(axialSpeed) > 0.00001D) {
			double axialMagnitude = mass * ROCKET_AXIAL_DRAG * rocket.aerodynamicArea() * axialSpeed * axialSpeed;
			aerodynamicForce = aerodynamicForce.add(0.0D, -Math.signum(axialSpeed) * axialMagnitude, 0.0D);
		}
		localTorque = localTorque.add(cross(rocket.aerodynamicCentre().subtract(pivot), aerodynamicForce));

		Vec3 angularVelocity = new Vec3(rocket.angularVelocityX, rocket.angularVelocityY, rocket.angularVelocityZ);
		Vec3 angularMomentum = massProperties.inertiaTimes(angularVelocity);
		// Euler's equation in body axes: I*wDot = torque - w x (I*w).
		Vec3 angularAcceleration = massProperties.inverseInertiaTimes(localTorque.subtract(cross(angularVelocity, angularMomentum)));
		angularVelocity = angularVelocity.add(angularAcceleration).scale(ROCKET_ANGULAR_DAMPING);
		rocket.angularVelocityX = angularVelocity.x;
		rocket.angularVelocityY = angularVelocity.y;
		rocket.angularVelocityZ = angularVelocity.z;
		// Multiplying on the right applies the increment in the rocket's local
		// axes, so an ordinary roll remains an ordinary roll after a pitch/yaw.
		orientation = orientation.mul(quaternionFromAngularVelocity(angularVelocity)).normalize();
		rocket.setOrientation(orientation);

		// Use the just-updated orientation for all forces, so visual tilt, thrust,
		// the nozzles and the pressure point agree in the same tick.
		Vec3 thrustDirection = rotate(orientation, new Vec3(0.0D, 1.0D, 0.0D));
		Vec3 acceleration = new Vec3(0.0D, -ROCKET_GRAVITY_PER_TICK, 0.0D)
				.add(thrustDirection.scale(engineForce * rocket.engines.size() / mass))
				.add(rotate(orientation, aerodynamicForce).scale(1.0D / mass));
		rocket.lastAccelerationX = acceleration.x;
		rocket.lastAccelerationY = acceleration.y;
		rocket.lastAccelerationZ = acceleration.z;
		rocket.velocityX = (rocket.velocityX + acceleration.x) * 0.9995D;
		rocket.velocityY = (rocket.velocityY + acceleration.y) * 0.9995D;
		rocket.velocityZ = (rocket.velocityZ + acceleration.z) * 0.9995D;
		double submergedFraction = rocketSubmergedFraction(level, rocket.physicsOffset(), orientation);
		if (submergedFraction > 0.0D) {
			// Water is penetrable for collision purposes, but not ignored by the
			// flight model.  The resistance rises with the portion of the body in
			// liquid, preserving the high-energy seabed impact without treating the
			// surface as a solid wall.
			double liquidDrag = Math.max(0.10D, 1.0D - submergedFraction * 0.22D);
			rocket.velocityX *= liquidDrag;
			rocket.velocityY *= liquidDrag;
			rocket.velocityZ *= liquidDrag;
		}
		rocket.physicsX += rocket.velocityX;
		rocket.physicsY += rocket.velocityY;
		rocket.physicsZ += rocket.velocityZ;

		// While the nozzles are still within the pad, the model is allowed to
		// leave its original footprint.  Checking collision immediately here made
		// harmless display rounding collide with the pad and explode on take-off.
		if (rocket.physicsY >= launchClearance()) {
			double collisionStart = 0.0D;
			if (previousOffset.y < launchClearance()) {
				double climbed = rocket.physicsY - previousOffset.y;
				collisionStart = climbed <= 0.00001D
						? 1.0D
						: Mth.clamp((launchClearance() - previousOffset.y) / climbed, 0.0D, 1.0D);
			}
			return findRocketImpactAlongPath(level, previousOffset, previousOrientation, rocket.physicsOffset(), orientation, collisionStart);
		}
		if (flightTicks > 120L && rocket.physicsY < -3.0D) {
			return BlockPos.containing(rocket.pivot().add(rocket.physicsOffset()));
		}
		return null;
	}

	private static double launchClearance() {
		if (rocket == null) {
			return LAUNCH_PAD_CLEARANCE;
		}
		// Do not collide with the launch gantry/pad while any part of the body is
		// still crossing it.  Once the entire initial height has cleared, ordinary
		// terrain collision takes over and a bad trajectory can fall naturally.
		return Math.max(LAUNCH_PAD_CLEARANCE, rocket.maxY - rocket.minY + 2.0D);
	}

	private static double rocketOverallSize() {
		if (rocket == null) {
			return 1.0D;
		}
		double width = rocket.maxX - rocket.minX + 1.0D;
		double height = rocket.maxY - rocket.minY + 1.0D;
		double depth = rocket.maxZ - rocket.minZ + 1.0D;
		return Math.max(1.0D, Math.sqrt(width * width + height * height + depth * depth));
	}

	private static BlockPos findRocketImpactAlongPath(
			ServerLevel level,
			Vec3 previousOffset,
			Quaternionf previousOrientation,
			Vec3 currentOffset,
			Quaternionf currentOrientation,
			double startProgress
	) {
		if (level == null || rocket == null || previousOffset == null || currentOffset == null
				|| previousOrientation == null || currentOrientation == null) {
			return null;
		}
		double translation = currentOffset.subtract(previousOffset).length();
		double rotationDistance = rocketFurthestBlockDistance() * quaternionAngle(previousOrientation, currentOrientation);
		int steps = Mth.clamp((int) Math.ceil(Math.max(translation, rotationDistance) * 4.0D), 1, 64);
		int firstStep = Mth.clamp((int) Math.ceil(startProgress * steps), 0, steps);
		for (int step = firstStep; step <= steps; step++) {
			double progress = step / (double) steps;
			Vec3 offset = previousOffset.add(currentOffset.subtract(previousOffset).scale(progress));
			Quaternionf orientation = interpolateQuaternion(previousOrientation, currentOrientation, progress);
			Vec3 pivot = rocket.pivot().add(offset);
			for (PersistedRocketBlock block : rocket.blocks) {
				Vec3 local = block.pos().getCenter().subtract(rocket.pivot());
				BlockPos pos = BlockPos.containing(pivot.add(rotate(orientation, local)));
				BlockState state = level.getBlockState(pos);
				// Fluid, water plants, buttons and other non-structural blocks are
				// penetrable.  They must not turn an ocean impact into a surface blast.
				if (pos.getY() < level.getMinY() || isStructuralImpactBlock(state)) {
					return pos;
				}
			}
		}
		return null;
	}

	private static boolean isStructuralImpactBlock(BlockState state) {
		return state != null
				&& !state.isAir()
				&& state.getFluidState().isEmpty()
				&& state.blocksMotion()
				&& state.canOcclude()
				&& !state.is(BlockTags.LEAVES);
	}

	/** Water, foliage, plants and non-occluding decoration cannot hold wreckage.
	 * They are displaced only when a fragment has reached a solid floor. */
	private static boolean isDebrisPassThroughBlock(BlockState state) {
		return state == null || state.isAir() || !state.getFluidState().isEmpty() || !isStructuralImpactBlock(state);
	}

	private static double rocketFurthestBlockDistance() {
		if (rocket == null || rocket.blocks == null || rocket.blocks.isEmpty()) {
			return 0.0D;
		}
		Vec3 pivot = rocket.pivot();
		double furthest = 0.0D;
		for (PersistedRocketBlock block : rocket.blocks) {
			furthest = Math.max(furthest, block.pos().getCenter().distanceTo(pivot));
		}
		return furthest;
	}

	private static double rocketSubmergedFraction(ServerLevel level, Vec3 offset, Quaternionf orientation) {
		if (level == null || rocket == null || rocket.blocks == null || rocket.blocks.isEmpty()) {
			return 0.0D;
		}
		int submerged = 0;
		int samples = 0;
		// Sampling every fourth block gives a stable liquid fraction on large
		// decorative rockets without multiplying the already detailed collision
		// pass by another full scan.
		int stride = Math.max(1, rocket.blocks.size() / 300);
		Vec3 pivot = rocket.pivot().add(offset);
		for (int index = 0; index < rocket.blocks.size(); index += stride) {
			PersistedRocketBlock block = rocket.blocks.get(index);
			BlockPos pos = BlockPos.containing(pivot.add(rotate(orientation, block.pos().getCenter().subtract(rocket.pivot()))));
			if (!level.getFluidState(pos).isEmpty()) {
				submerged++;
			}
			samples++;
		}
		return samples == 0 ? 0.0D : submerged / (double) samples;
	}

	private static double quaternionAngle(Quaternionf first, Quaternionf second) {
		double dot = Math.abs(first.x * second.x + first.y * second.y + first.z * second.z + first.w * second.w);
		return 2.0D * Math.acos(Mth.clamp(dot, -1.0D, 1.0D));
	}

	private static Quaternionf interpolateQuaternion(Quaternionf first, Quaternionf second, double progress) {
		float sign = first.x * second.x + first.y * second.y + first.z * second.z + first.w * second.w < 0.0F ? -1.0F : 1.0F;
		float amount = (float) Mth.clamp(progress, 0.0D, 1.0D);
		return new Quaternionf(
				first.x + (second.x * sign - first.x) * amount,
				first.y + (second.y * sign - first.y) * amount,
				first.z + (second.z * sign - first.z) * amount,
				first.w + (second.w * sign - first.w) * amount
		).normalize();
	}

	private static void updateRocketPhysicsDisplays(ServerLevel level) {
		if (level == null || rocket == null || rocket.clientOnlyVisuals) {
			return;
		}
		double scale = rocketVisualScale();
		Vec3 visualPivot = rocketVisualPivot(scale);
		Quaternionf orientation = rocket.orientation();
		for (PersistedRocketBlock block : rocket.blocks) {
			Display.BlockDisplay display = resolveRocketDisplay(level, block);
			if (display == null) {
				continue;
			}
			Vec3 local = new Vec3(block.x, block.y, block.z).subtract(rocket.pivot()).scale(scale);
			Vec3 position = visualPivot.add(rotate(orientation, local));
			display.setPosRotInterpolationDuration(DISPLAY_INTERPOLATION_TICKS);
			display.setTransformationInterpolationDelay(0);
			display.setTransformationInterpolationDuration(DISPLAY_INTERPOLATION_TICKS);
			display.setTransformation(new Transformation(
					new Vector3f(),
					new Quaternionf(orientation),
					new Vector3f((float) scale, (float) scale, (float) scale),
					new Quaternionf()
			));
			display.setPos(position.x, position.y, position.z);
		}
	}

	/** Returns the moving camera that replaced a camera block at this original
	 * construction position, if that rocket is currently in flight. */
	public static RocketCameraFeed launchedCameraFeed(ServerLevel level, BlockPos originalPos) {
		if (level == null || originalPos == null || rocket == null || rocket.devices == null
				|| LaunchStage.from(rocket.stage) != LaunchStage.LAUNCHING
				|| !level.dimension().identifier().toString().equals(rocket.dimension)) {
			return null;
		}
		for (RocketMountedDevice device : rocket.devices) {
			if (!device.camera || !originalPos.equals(device.pos()) || device.anchorUuid == null || device.anchorUuid.isBlank()) {
				continue;
			}
			try {
				Entity anchor = level.getEntity(UUID.fromString(device.anchorUuid));
				if (anchor != null) {
					return new RocketCameraFeed(anchor.getUUID(), anchor.getX(), anchor.getY(), anchor.getZ(), anchor.getYRot(), anchor.getXRot());
				}
			} catch (IllegalArgumentException ignored) {
				// A saved launch may be restoring its anchor on the next flight tick.
			}
		}
		return null;
	}

	/** Device blocks are deliberately removed without running their normal
	 * teardown: their endpoint/name state remains bound to the moving rocket. */
	public static boolean isLaunchedMountedDevice(ServerLevel level, BlockPos originalPos) {
		LaunchStage stage = rocket == null ? null : LaunchStage.from(rocket.stage);
		if (level == null || originalPos == null || rocket == null || rocket.devices == null
				|| (stage != LaunchStage.ARMED && stage != LaunchStage.LAUNCHING)
				|| !level.dimension().identifier().toString().equals(rocket.dimension)) {
			return false;
		}
		for (RocketMountedDevice device : rocket.devices) {
			if (originalPos.equals(device.pos())) {
				return true;
			}
		}
		return false;
	}

	public static Vec3 launchedMountedDevicePosition(ServerLevel level, BlockPos originalPos) {
		if (!isLaunchedMountedDevice(level, originalPos)) {
			return null;
		}
		Quaternionf orientation = rocket.orientation();
		Vec3 worldPivot = rocket.pivot().add(rocket.physicsOffset());
		return worldPivot.add(rotate(orientation, originalPos.getCenter().subtract(rocket.pivot())));
	}

	private static void updateRocketDeviceAnchors(ServerLevel level) {
		if (level == null || rocket == null || rocket.devices == null || rocket.devices.isEmpty()) {
			return;
		}
		Quaternionf orientation = rocket.orientation();
		Vec3 worldPivot = rocket.pivot().add(rocket.physicsOffset());
		for (RocketMountedDevice device : rocket.devices) {
			if (!device.camera) {
				continue;
			}
			Vec3 localOrigin = CameraBlock.captureBaseOrigin(device.pos()).subtract(rocket.pivot());
			Vec3 worldOrigin = worldPivot.add(rotate(orientation, localOrigin));
			Vec3 worldForward = rotate(orientation, cameraForward(device.yaw, device.pitch));
			float yaw = CameraBlock.yawTo(worldOrigin, worldOrigin.add(worldForward));
			float pitch = CameraBlock.pitchTo(worldOrigin, worldOrigin.add(worldForward));
			Entity anchor = resolveRocketDeviceAnchor(level, device);
			if (anchor == null) {
				// This is deliberately an Interaction entity rather than an invisible
				// ItemDisplay.  The renderer bot has a reliable network/tracking path
				// for interaction anchors (the same primitive used by drone cameras),
				// whereas a client-only display could disappear from the shadow world
				// exactly when the rocket was materialised.
				Interaction created = new Interaction(EntityType.INTERACTION, level);
				created.addTag(DEVICE_ANCHOR_TAG);
				created.setNoGravity(true);
				created.setInvulnerable(true);
				created.setSilent(true);
				created.setResponse(false);
				created.setWidth(0.01F);
				created.setHeight(0.01F);
				level.addFreshEntity(created);
				device.anchorUuid = created.getUUID().toString();
				anchor = created;
			}
			anchor.setPos(worldOrigin.x, worldOrigin.y, worldOrigin.z);
			anchor.setYRot(yaw);
			anchor.setXRot(pitch);
			anchor.yRotO = yaw;
			anchor.xRotO = pitch;
		}
	}

	private static Entity resolveRocketDeviceAnchor(ServerLevel level, RocketMountedDevice device) {
		if (level == null || device == null || device.anchorUuid == null || device.anchorUuid.isBlank()) {
			return null;
		}
		try {
			Entity entity = level.getEntity(UUID.fromString(device.anchorUuid));
			return entity != null && entity.getTags().contains(DEVICE_ANCHOR_TAG) ? entity : null;
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static void clearRocketDeviceAnchors(ServerLevel level) {
		if (level == null || rocket == null || rocket.devices == null) {
			return;
		}
		for (RocketMountedDevice device : rocket.devices) {
			Entity anchor = resolveRocketDeviceAnchor(level, device);
			if (anchor != null) {
				anchor.discard();
			}
			device.anchorUuid = null;
		}
	}

	/** The original block coordinates are intentionally retained during flight
	 * so Bluetooth screens can follow a rocket camera.  Once the vehicle is
	 * gone, remove those retained endpoints and notify every linked screen. */
	private static void removeRocketMountedDeviceEndpoints(ServerLevel level) {
		if (level == null || rocket == null || rocket.devices == null) {
			return;
		}
		for (RocketMountedDevice device : rocket.devices) {
			if (device.camera) {
				BluetoothLinkSystem.removeMountedBlockEndpoint(level, BluetoothLinkSystem.EndpointType.CAMERA, device.pos());
			} else if (device.microphone) {
				BluetoothLinkSystem.removeMountedBlockEndpoint(level, BluetoothLinkSystem.EndpointType.MICROPHONE, device.pos());
			}
		}
	}

	private static void clearRocketMountedScreens(ServerLevel level) {
		if (level == null || rocket == null || rocket.mountedScreenUuids == null) {
			return;
		}
		for (String rawUuid : rocket.mountedScreenUuids) {
			try {
				Entity entity = level.getEntity(UUID.fromString(rawUuid));
				if (entity != null) {
					entity.removeTag(MOUNTED_SCREEN_TAG);
				}
			} catch (IllegalArgumentException ignored) {
			}
		}
		rocket.mountedScreenUuids.clear();
		if (rocket.mountedScreens != null) {
			rocket.mountedScreens.clear();
		}
	}

	/** The visible monitor panel is an ItemDisplay, which can follow the full
	 * rigid-body rotation even though an ItemFrame itself only has cardinal
	 * hanging directions. */
	static Display.ItemDisplay resolveRocketMountedScreenDisplay(ServerLevel level, ItemFrame frame) {
		if (level == null || frame == null || rocket == null || rocket.mountedScreens == null
				|| LaunchStage.from(rocket.stage) != LaunchStage.LAUNCHING) {
			return null;
		}
		RocketMountedScreen screen = rocket.findMountedScreen(frame.getUUID());
		if (screen == null || screen.displayUuid == null || screen.displayUuid.isBlank()) {
			return null;
		}
		try {
			Entity entity = level.getEntity(UUID.fromString(screen.displayUuid));
			return entity instanceof Display.ItemDisplay display ? display : null;
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	static boolean poseRocketMountedScreen(ServerLevel level, ItemFrame frame, Display.ItemDisplay display) {
		if (level == null || frame == null || display == null || rocket == null || rocket.mountedScreens == null
				|| LaunchStage.from(rocket.stage) != LaunchStage.LAUNCHING) {
			return false;
		}
		RocketMountedScreen screen = rocket.findMountedScreen(frame.getUUID());
		if (screen == null) {
			return false;
		}
		screen.displayUuid = display.getUUID().toString();
		Vec3 position = rocket.bodyToWorld(screen.localPosition());
		Quaternionf orientation = new Quaternionf(rocket.orientation())
				.mul(new Quaternionf().rotationY((float) Math.toRadians(screen.facingYaw)));
		display.setPosRotInterpolationDuration(DISPLAY_INTERPOLATION_TICKS);
		display.setTransformationInterpolationDelay(0);
		display.setTransformationInterpolationDuration(DISPLAY_INTERPOLATION_TICKS);
		display.setPos(position.x, position.y, position.z);
		display.setYRot(0.0F);
		display.setXRot(0.0F);
		display.setYHeadRot(0.0F);
		display.setYBodyRot(0.0F);
		display.setTransformation(new Transformation(
				new Vector3f(), orientation, new Vector3f(1.0F, 1.0F, 1.0F), new Quaternionf()
		));
		return true;
	}

	private static void updateRocketMountedScreenVisuals(ServerLevel level) {
		if (level == null || rocket == null || rocket.mountedScreens == null) {
			return;
		}
		for (RocketMountedScreen screen : rocket.mountedScreens) {
			if (screen == null || screen.frameUuid == null || screen.frameUuid.isBlank()) {
				continue;
			}
			try {
				Entity entity = level.getEntity(UUID.fromString(screen.frameUuid));
				if (!(entity instanceof ItemFrame frame)) {
					continue;
				}
				Display.ItemDisplay display = resolveRocketMountedScreenDisplay(level, frame);
				if (display != null) {
					poseRocketMountedScreen(level, frame, display);
				}
			} catch (IllegalArgumentException ignored) {
			}
		}
	}

	private static Vec3 cameraForward(float yaw, float pitch) {
		double yawRadians = yaw * Math.PI / 180.0D;
		double pitchRadians = pitch * Math.PI / 180.0D;
		return new Vec3(
				-Mth.sin((float) yawRadians) * Mth.cos((float) pitchRadians),
				-Mth.sin((float) pitchRadians),
				Mth.cos((float) yawRadians) * Mth.cos((float) pitchRadians)
		);
	}

	private static double rocketVisualScale() {
		// Server-side entities retain their real-world size and position.  Each
		// viewer receives a separate client-only perspective projection below.
		return 1.0D;
	}

	private static Vec3 rocketVisualPivot(double scale) {
		return rocket.pivot().add(rocket.physicsOffset().scale(scale));
	}

	private static Vec3 engineVisualPosition(PersistedEnginePoint engine, double scale) {
		Vec3 local = engine.position().subtract(rocket.pivot()).scale(scale);
		return rocketVisualPivot(scale).add(rotate(rocket.orientation(), local));
	}

	private static Vec3 rocketUpVector() {
		return rotate(rocket.orientation(), new Vec3(0.0D, 1.0D, 0.0D));
	}

	private static Vec3 rotate(Quaternionf orientation, Vec3 vector) {
		Vector3f transformed = new Vector3f((float) vector.x, (float) vector.y, (float) vector.z);
		orientation.transform(transformed);
		return new Vec3(transformed.x, transformed.y, transformed.z);
	}

	private static Vec3 inverseRotate(Quaternionf orientation, Vec3 vector) {
		Quaternionf inverse = new Quaternionf(orientation).conjugate().normalize();
		return rotate(inverse, vector);
	}

	private static Quaternionf quaternionFromAngularVelocity(Vec3 angularVelocity) {
		double angle = angularVelocity.length();
		if (angle < 0.0000001D) {
			return new Quaternionf();
		}
		return new Quaternionf().rotationAxis(
				(float) angle,
				(float) (angularVelocity.x / angle),
				(float) (angularVelocity.y / angle),
				(float) (angularVelocity.z / angle)
		);
	}

	private static Vec3 cross(Vec3 first, Vec3 second) {
		return new Vec3(
				first.y * second.z - first.z * second.y,
				first.z * second.x - first.x * second.z,
				first.x * second.y - first.y * second.x
		);
	}

	/**
	 * The launched hull is rendered as displays, so vanilla no longer has block
	 * collision for its interior.  These occupants use the original body-space
	 * voxels as a small moving collision world.  Their position is driven by the
	 * rocket's acceleration like a dense fluid would drive it, while player input
	 * is still allowed to move around the cabin and is resolved against the hull.
	 */
	private static void tickRocketOccupants(ServerLevel level, boolean placeInCurrentRocketPose) {
		if (level == null || rocket == null || rocket.occupants == null || rocket.occupants.isEmpty()) {
			return;
		}
		for (int index = rocket.occupants.size() - 1; index >= 0; index--) {
			RocketOccupant occupant = rocket.occupants.get(index);
			ServerPlayer player = occupant == null ? null : occupant.resolve(level);
			if (player == null || !player.isAlive()) {
				rocket.occupants.remove(index);
				continue;
			}

			Vec3 actualLocal = rocket.worldToBody(player.position());
			Vec3 playerMovement = actualLocal.subtract(occupant.position());
			if (playerMovement.lengthSqr() <= 1.0D) {
				playerMovement = clampLength(playerMovement, OCCUPANT_INPUT_PER_TICK);
			} else {
				// Teleports and knockback outside the moving cabin are not player
				// walking input.  Keep the passenger in the last valid compartment.
				playerMovement = Vec3.ZERO;
			}
			Vec3 localAcceleration = inverseRotate(rocket.orientation(), rocket.lastAcceleration());
			Vec3 fromCentre = occupant.position().subtract(rocket.pivot());
			Vec3 angularVelocity = new Vec3(rocket.angularVelocityX, rocket.angularVelocityY, rocket.angularVelocityZ);
			Vec3 centrifugal = cross(angularVelocity, cross(angularVelocity, fromCentre));
			Vec3 flowAcceleration = localAcceleration.scale(-OCCUPANT_INERTIA_FLOW).add(centrifugal);
			Vec3 flowVelocity = occupant.velocity()
					.add(flowAcceleration.scale(0.17D))
					.scale(OCCUPANT_FLUID_DRAG);
			Vec3 desired = occupant.position().add(playerMovement).add(flowVelocity);
			Vec3 resolved = resolveRocketOccupantPosition(occupant.position(), desired);
			if (resolved.distanceToSqr(desired) > 0.000001D) {
				flowVelocity = resolved.subtract(occupant.position()).subtract(playerMovement);
			}
			occupant.setPosition(resolved);
			occupant.setVelocity(flowVelocity);

			if (!placeInCurrentRocketPose) {
				continue;
			}
			Vec3 worldPosition = rocket.bodyToWorld(resolved);
			Vec3 worldVelocity = rotate(rocket.orientation(), flowVelocity)
					.add(rocket.velocity())
					.add(cross(rotate(rocket.orientation(), angularVelocity), worldPosition.subtract(rocket.bodyToWorld(rocket.pivot()))));
			player.setPos(worldPosition.x, worldPosition.y, worldPosition.z);
			player.setDeltaMovement(clampLength(worldVelocity, 1.8D));
			player.hurtMarked = true;
			applyVacuumDrowning(level, player);
		}
	}

	/**
	 * The materialized rocket has no vanilla block collision.  Occupants are
	 * handled above, but an external player (for example standing on its roof
	 * during ignition) was never put into that list and could consequently walk
	 * through the visual hull.  Resolve these contacts against the same original
	 * voxels, so the body is solid both inside and outside.
	 */
	private static void resolveRocketHullContacts(ServerLevel level) {
		if (level == null || rocket == null || rocket.blocks == null || rocket.blocks.isEmpty()) {
			return;
		}
		AABB localBounds = new AABB(rocket.minX, rocket.minY, rocket.minZ,
				rocket.maxX + 1.0D, rocket.maxY + 1.0D, rocket.maxZ + 1.0D);
		AABB worldBounds = null;
		for (int x = 0; x < 2; x++) {
			for (int y = 0; y < 2; y++) {
				for (int z = 0; z < 2; z++) {
					Vec3 corner = rocket.bodyToWorld(new Vec3(
							x == 0 ? localBounds.minX : localBounds.maxX,
							y == 0 ? localBounds.minY : localBounds.maxY,
							z == 0 ? localBounds.minZ : localBounds.maxZ));
					AABB point = new AABB(corner, corner);
					worldBounds = worldBounds == null ? point : worldBounds.minmax(point);
				}
			}
		}
		if (worldBounds == null) {
			return;
		}
		for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, worldBounds.inflate(1.25D),
				candidate -> candidate.isAlive() && !candidate.isSpectator())) {
			Vec3 resolved = resolveRocketHullContact(rocket.worldToBody(player.position()));
			if (resolved == null) {
				continue;
			}
			Vec3 worldPosition = rocket.bodyToWorld(resolved);
			Vec3 localVelocity = inverseRotate(rocket.orientation(), player.getDeltaMovement());
			// A contact can only move along one of the local axes; removing the
			// incoming component prevents a player sinking through the floor again
			// on the next tick while preserving tangential motion.
			Vec3 displacement = resolved.subtract(rocket.worldToBody(player.position()));
			if (Math.abs(displacement.x) > 0.00001D) localVelocity = new Vec3(0.0D, localVelocity.y, localVelocity.z);
			if (Math.abs(displacement.y) > 0.00001D) localVelocity = new Vec3(localVelocity.x, 0.0D, localVelocity.z);
			if (Math.abs(displacement.z) > 0.00001D) localVelocity = new Vec3(localVelocity.x, localVelocity.y, 0.0D);
			player.setPos(worldPosition.x, worldPosition.y, worldPosition.z);
			player.setDeltaMovement(rotate(rocket.orientation(), localVelocity));
			player.hurtMarked = true;
		}
	}

	/** Returns null when no hull voxel intersects the player-sized local box. */
	private static Vec3 resolveRocketHullContact(Vec3 localFeetPosition) {
		if (rocket == null || localFeetPosition == null || rocket.blocks == null) {
			return null;
		}
		Vec3 resolved = localFeetPosition;
		boolean touched = false;
		for (int pass = 0; pass < 8; pass++) {
			AABB playerBox = new AABB(resolved.x - 0.30D, resolved.y, resolved.z - 0.30D,
					resolved.x + 0.30D, resolved.y + 1.79D, resolved.z + 0.30D);
			Vec3 correction = null;
			for (PersistedRocketBlock block : rocket.blocks) {
				AABB blockBox = new AABB(block.x, block.y, block.z, block.x + 1.0D, block.y + 1.0D, block.z + 1.0D);
				if (!playerBox.intersects(blockBox)) {
					continue;
				}
				Vec3[] options = new Vec3[]{
						new Vec3(blockBox.minX - playerBox.maxX - 0.0005D, 0.0D, 0.0D),
						new Vec3(blockBox.maxX - playerBox.minX + 0.0005D, 0.0D, 0.0D),
						new Vec3(0.0D, blockBox.minY - playerBox.maxY - 0.0005D, 0.0D),
						new Vec3(0.0D, blockBox.maxY - playerBox.minY + 0.0005D, 0.0D),
						new Vec3(0.0D, 0.0D, blockBox.minZ - playerBox.maxZ - 0.0005D),
						new Vec3(0.0D, 0.0D, blockBox.maxZ - playerBox.minZ + 0.0005D)
				};
				for (Vec3 option : options) {
					if (correction == null || option.lengthSqr() < correction.lengthSqr()) {
						correction = option;
					}
				}
			}
			if (correction == null) {
				break;
			}
			touched = true;
			resolved = resolved.add(correction);
		}
		return touched ? resolved : null;
	}

	private static Vec3 resolveRocketOccupantPosition(Vec3 previous, Vec3 desired) {
		if (rocket == null || previous == null || desired == null) {
			return previous == null ? Vec3.ZERO : previous;
		}
		// This outer envelope is the cabin's pressure shell.  Individual selected
		// blocks below supply the hard walls, floor and ceiling inside that shell.
		double minX = rocket.minX + 0.31D;
		double maxX = rocket.maxX + 0.69D;
		double minY = rocket.minY + 0.02D;
		double maxY = rocket.maxY - 0.82D;
		double minZ = rocket.minZ + 0.31D;
		double maxZ = rocket.maxZ + 0.69D;
		Vec3 bounded = new Vec3(
				Mth.clamp(desired.x, minX, Math.max(minX, maxX)),
				Mth.clamp(desired.y, minY, Math.max(minY, maxY)),
				Mth.clamp(desired.z, minZ, Math.max(minZ, maxZ))
		);
		Vec3 resolved = previous;
		Vec3 candidateX = new Vec3(bounded.x, resolved.y, resolved.z);
		if (!occupantHitsRocketHull(candidateX)) {
			resolved = candidateX;
		}
		Vec3 candidateY = new Vec3(resolved.x, bounded.y, resolved.z);
		if (!occupantHitsRocketHull(candidateY)) {
			resolved = candidateY;
		}
		Vec3 candidateZ = new Vec3(resolved.x, resolved.y, bounded.z);
		if (!occupantHitsRocketHull(candidateZ)) {
			resolved = candidateZ;
		}
		return resolved;
	}

	private static boolean occupantHitsRocketHull(Vec3 localFeetPosition) {
		if (rocket == null || localFeetPosition == null || rocket.blocks == null) {
			return false;
		}
		AABB occupantBox = new AABB(
				localFeetPosition.x - 0.30D, localFeetPosition.y, localFeetPosition.z - 0.30D,
				localFeetPosition.x + 0.30D, localFeetPosition.y + 1.79D, localFeetPosition.z + 0.30D
		);
		for (PersistedRocketBlock block : rocket.blocks) {
			if (occupantBox.intersects(new AABB(block.x, block.y, block.z, block.x + 1.0D, block.y + 1.0D, block.z + 1.0D))) {
				return true;
			}
		}
		return false;
	}

	private static void applyVacuumDrowning(ServerLevel level, ServerPlayer player) {
		if (level == null || player == null || rocket == null || rocket.physicsY <= 0.0D) {
			return;
		}
		double upwardAcceleration = Math.max(0.0001D, rocket.lastAccelerationY);
		double remainingHeight = Math.max(0.0D, SPACE_ALTITUDE - rocket.physicsY);
		double upwardVelocity = Math.max(0.0D, rocket.velocityY);
		double estimatedTicksToSpace = (Math.sqrt(upwardVelocity * upwardVelocity + 2.0D * upwardAcceleration * remainingHeight) - upwardVelocity) / upwardAcceleration;
		if (estimatedTicksToSpace > VACUUM_DROWNING_LEAD_TICKS) {
			return;
		}
		// There is no breathable atmosphere in the closed moving hull at this
		// altitude.  Setting air to zero lets the normal drowning feedback start;
		// the periodic damage guarantees it becomes fatal before the visual rocket
		// is removed even on a very fast ascent.
		player.setAirSupply(Math.min(player.getAirSupply(), 0));
		if (rocket.launchElapsedTicks % 10L == 0L) {
			player.hurt(level.damageSources().drown(), 2.0F);
		}
	}

	private static void applyEngineExhaustFlow(ServerLevel level, long elapsed, boolean preheating) {
		if (level == null || rocket == null || rocket.engines == null || rocket.engines.isEmpty()) {
			return;
		}
		// Exhaust starts at ignition and reaches full strength over two seconds.
		double warmup = preheating ? Mth.clamp(elapsed / (double) ENGINE_EXHAUST_RAMP_TICKS, 0.0D, 1.0D) : 1.0D;
		if (warmup <= 0.01D) {
			return;
		}
		Vec3 exhaustDirection = rocketUpVector().scale(-1.0D);
		Map<BlockPos, Double> reachByEngine = engineExhaustReachByPoint();
		AABB influence = null;
		for (PersistedEnginePoint engine : rocket.engines) {
			double reach = reachByEngine.getOrDefault(engine.pos(), ENGINE_EXHAUST_BASE_REACH);
			Vec3 nozzle = engineVisualPosition(engine, 1.0D).add(exhaustDirection.scale(0.45D));
			AABB nozzleReach = new AABB(nozzle, nozzle.add(exhaustDirection.scale(reach))).inflate(reach * 0.52D);
			influence = influence == null ? nozzleReach : influence.minmax(nozzleReach);
		}
		if (influence == null) {
			return;
		}
		for (Entity entity : level.getEntities((Entity) null, influence, candidate -> candidate.isAlive() && !candidate.isSpectator() && !(candidate instanceof Display))) {
			Vec3 totalPush = Vec3.ZERO;
			for (PersistedEnginePoint engine : rocket.engines) {
				double reach = reachByEngine.getOrDefault(engine.pos(), ENGINE_EXHAUST_BASE_REACH);
				Vec3 nozzle = engineVisualPosition(engine, 1.0D).add(exhaustDirection.scale(0.45D));
				Vec3 fromNozzle = entity.position().subtract(nozzle);
				double along = fromNozzle.dot(exhaustDirection);
				double distanceSqr = fromNozzle.lengthSqr();
				if (along < -0.35D || along > reach || distanceSqr > reach * reach) {
					continue;
				}
				Vec3 lateral = fromNozzle.subtract(exhaustDirection.scale(along));
				double radius = Math.min(1.25D + along * 0.45D, Math.sqrt(Math.max(0.0D, reach * reach - along * along)));
				if (radius < 0.001D || lateral.lengthSqr() > radius * radius) {
					continue;
				}
				double axialFade = 1.0D - along / reach;
				double radialFade = 1.0D - Math.sqrt(lateral.lengthSqr()) / radius;
				double strength = (0.06D + 0.84D * axialFade * axialFade * radialFade) * warmup;
				Vec3 radialDirection = unit(lateral, perpendicularUnit(exhaustDirection));
				totalPush = totalPush.add(exhaustDirection.scale(strength)).add(radialDirection.scale(strength * 0.42D));
			}
			if (totalPush.lengthSqr() <= 0.000001D) {
				continue;
			}
			Vec3 push = clampLength(totalPush, ENGINE_EXHAUST_MAX_PUSH_PER_TICK);
			// Entity.push only marks a collision push.  In particular it may be
			// damped away by a player's own movement packet, so apply a real velocity
			// impulse as well.  This is intentionally an exhaust flow, not damage.
			entity.setDeltaMovement(entity.getDeltaMovement().add(push));
			entity.push(push.x, push.y, push.z);
			entity.hurtMarked = true;
		}
	}

	private static Map<BlockPos, Double> engineExhaustReachByPoint() {
		Map<BlockPos, Double> reaches = new HashMap<>();
		if (rocket == null || rocket.engines == null) {
			return reaches;
		}
		for (EngineCluster cluster : clusterEnginePoints(rocket.engines)) {
			// A coherent 2x2 footprint produces roughly twice the useful jet length
			// of one nozzle; larger clusters scale with their nozzle area.
			double reach = ENGINE_EXHAUST_BASE_REACH * Math.sqrt(Math.max(1, cluster.points.size()));
			for (PersistedEnginePoint point : cluster.points) {
				reaches.put(point.pos(), reach);
			}
		}
		return reaches;
	}

	private static Vec3 clampLength(Vec3 vector, double maximumLength) {
		if (vector == null || maximumLength <= 0.0D || vector.lengthSqr() <= maximumLength * maximumLength) {
			return vector == null ? Vec3.ZERO : vector;
		}
		return vector.scale(maximumLength / Math.sqrt(vector.lengthSqr()));
	}

	private static void emitRocketExhaustParticles(ServerLevel level, long elapsed, boolean preheating) {
		if (level == null || rocket == null || rocket.engines == null || rocket.engines.isEmpty()) {
			return;
		}
		double rocketScale = rocketVisualScale();
		Vec3 exhaustDirection = rocketUpVector().scale(-1.0D);
		double warmup = preheating ? Mth.clamp(elapsed / (double) ENGINE_EXHAUST_RAMP_TICKS, 0.0D, 1.0D) : 1.0D;
		boolean groundBurst = preheating || rocket.physicsY < 2.8D;
		Vec3 sideA = perpendicularUnit(exhaustDirection);
		Vec3 sideB = unit(cross(exhaustDirection, sideA), new Vec3(0.0D, 0.0D, 1.0D));
		List<EngineCluster> clusters = clusterEnginePoints(rocket.engines);
		// Minecraft's client-side particle limiter starts dropping the oldest
		// particles once a dense plume is emitted every tick.  A bounded stream of
		// long-lived, fast particles looks much larger than hundreds that vanish
		// close to the nozzles.
		int remainingSmokeBudget = 220;
		for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
			if (remainingSmokeBudget <= 0) {
				break;
			}
			EngineCluster cluster = clusters.get(clusterIndex);
			// Add midpoint emitters between adjacent engine blocks. The visible plume
			// therefore remains continuous even when a large engine is built from a
			// connected footprint rather than one block.
			List<Vec3> exhaustSources = buildConnectedEngineExhaustSources(cluster, rocketScale);
			for (Vec3 engine : exhaustSources) {
				Vec3 nozzle = engine.add(exhaustDirection.scale(0.30D * rocketScale));
				level.sendParticles(SPACE_RACE_FLAME_WIDE, nozzle.x + exhaustDirection.x * 1.20D * rocketScale,
						nozzle.y + exhaustDirection.y * 1.20D * rocketScale, nozzle.z + exhaustDirection.z * 1.20D * rocketScale,
						2, 0.16D * rocketScale, 0.16D * rocketScale, 0.16D * rocketScale, 0.008D);
				level.sendParticles(SPACE_RACE_FLAME_WIDE, nozzle.x + exhaustDirection.x * 1.70D * rocketScale,
						nozzle.y + exhaustDirection.y * 1.70D * rocketScale, nozzle.z + exhaustDirection.z * 1.70D * rocketScale,
						2, 0.20D * rocketScale, 0.20D * rocketScale, 0.20D * rocketScale, 0.010D);
				level.sendParticles(SPACE_RACE_FLAME_CORE, nozzle.x + exhaustDirection.x * 0.20D * rocketScale,
						nozzle.y + exhaustDirection.y * 0.20D * rocketScale, nozzle.z + exhaustDirection.z * 0.20D * rocketScale,
						2, 0.08D * rocketScale, 0.08D * rocketScale, 0.08D * rocketScale, 0.006D);
				level.sendParticles(ParticleTypes.FLAME, nozzle.x + exhaustDirection.x * 0.55D * rocketScale,
						nozzle.y + exhaustDirection.y * 0.55D * rocketScale, nozzle.z + exhaustDirection.z * 0.55D * rocketScale,
						3, 0.18D * rocketScale, 0.18D * rocketScale, 0.18D * rocketScale, 0.025D);
			}
			boolean ignitionBurst = preheating && elapsed <= ENGINE_IGNITION_BURST_TICKS;
			int puffsPerEngine = warmup > 0.55D ? 16 : Math.max(3, (int) Math.round(13.0D * warmup));
			if (ignitionBurst) {
				puffsPerEngine = Math.max(puffsPerEngine, 6);
			}
			int smokePuffs = Math.min(remainingSmokeBudget, Math.min(120, puffsPerEngine * cluster.points.size()));
			remainingSmokeBudget -= smokePuffs;
			for (int puff = 0; puff < smokePuffs; puff++) {
				Vec3 engine = exhaustSources.get(puff % exhaustSources.size());
				// Randomize each puff independently. The deterministic phase alone made
				// a multi-engine plume look like a perfectly symmetric spiral.
				double phase = level.random.nextDouble() * (Math.PI * 2.0D)
						+ elapsed * 0.071D + clusterIndex * 1.617D;
				double pulse = 0.72D + level.random.nextDouble() * 0.42D;
				double lateralStrength = 0.66D + level.random.nextDouble() * 0.55D;
				Vec3 aroundNozzle = sideA.scale(Math.cos(phase)).add(sideB.scale(Math.sin(phase)));
				// When the nozzle is close to the pad, hot gases are reflected sideways
				// and upward. Starting just above the pad prevents every ray from being
				// clipped by a solid block directly below the engine.
				double exhaustStrength = groundBurst ? 0.02D : 0.52D + level.random.nextDouble() * 0.30D;
				double reboundStrength = groundBurst ? 0.10D + level.random.nextDouble() * 0.36D : 0.0D;
				Vec3 direction = unit(
						aroundNozzle.scale(lateralStrength)
								.add(exhaustDirection.scale(exhaustStrength - reboundStrength)),
						exhaustDirection
				);
				double lateralLaunchOffset = (groundBurst ? 0.15D : 0.06D) * rocketScale;
				Vec3 rayStart = engine
						.add(exhaustDirection.scale(0.47D * rocketScale))
						.add(aroundNozzle.scale(lateralLaunchOffset));
				// Campfire smoke has a long lifetime; give it a real plume velocity
				// and a much longer clear path instead of leaving a small cloud under
				// the nozzles.
				double requestedDistance = groundBurst ? 11.0D : 18.0D;
				double freeDistance = freeSmokeDistance(level, rayStart, direction, requestedDistance);
				if (freeDistance < 0.12D) {
					continue;
				}
				double launchOffset = Math.min(0.12D, freeDistance * 0.20D);
				Vec3 source = rayStart.add(direction.scale(launchOffset));
				double speed = (0.024D + Math.min(freeDistance, 16.0D) * 0.010D) * pulse;
				level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
						source.x, source.y, source.z, 0,
						direction.x * speed, direction.y * speed, direction.z * speed, 1.0D);
			}
		}
	}

	private static List<Vec3> buildConnectedEngineExhaustSources(EngineCluster cluster, double rocketScale) {
		if (cluster == null || cluster.points.isEmpty()) {
			return List.of();
		}
		Map<BlockPos, PersistedEnginePoint> engineByPos = new HashMap<>();
		List<Vec3> sources = new ArrayList<>();
		for (PersistedEnginePoint point : cluster.points) {
			engineByPos.put(point.pos(), point);
			sources.add(engineVisualPosition(point, rocketScale));
		}
		// EAST and SOUTH cover every shared edge exactly once.
		for (PersistedEnginePoint point : cluster.points) {
			for (Direction direction : new Direction[]{Direction.EAST, Direction.SOUTH}) {
				PersistedEnginePoint neighbour = engineByPos.get(point.pos().relative(direction));
				if (neighbour == null) {
					continue;
				}
				Vec3 first = engineVisualPosition(point, rocketScale);
				Vec3 second = engineVisualPosition(neighbour, rocketScale);
				sources.add(first.add(second).scale(0.5D));
			}
		}
		return sources;
	}

	private static List<EngineCluster> clusterEnginePoints(List<PersistedEnginePoint> engines) {
		List<EngineCluster> clusters = new ArrayList<>();
		if (engines == null || engines.isEmpty()) {
			return clusters;
		}
		Map<BlockPos, PersistedEnginePoint> remaining = new HashMap<>();
		for (PersistedEnginePoint engine : engines) {
			remaining.put(engine.pos(), engine);
		}
		while (!remaining.isEmpty()) {
			List<BlockPos> frontier = new ArrayList<>();
			List<PersistedEnginePoint> points = new ArrayList<>();
			frontier.add(remaining.keySet().iterator().next());
			for (int index = 0; index < frontier.size(); index++) {
				PersistedEnginePoint current = remaining.remove(frontier.get(index));
				if (current == null) {
					continue;
				}
				points.add(current);
				for (Direction direction : ENGINE_NEIGHBOURS) {
					BlockPos neighbour = current.pos().relative(direction);
					if (remaining.containsKey(neighbour)) {
						frontier.add(neighbour);
					}
				}
			}
			clusters.add(new EngineCluster(points));
		}
		return clusters;
	}

	private static Vec3 perpendicularUnit(Vec3 direction) {
		Vec3 reference = Math.abs(direction.y) < 0.90D
				? new Vec3(0.0D, 1.0D, 0.0D)
				: new Vec3(1.0D, 0.0D, 0.0D);
		return unit(cross(direction, reference), new Vec3(1.0D, 0.0D, 0.0D));
	}

	private static Vec3 unit(Vec3 vector, Vec3 fallback) {
		double length = vector.length();
		return length < 0.0001D ? fallback : vector.scale(1.0D / length);
	}

	private static double freeSmokeDistance(ServerLevel level, Vec3 start, Vec3 direction, double requestedDistance) {
		if (level == null || start == null || direction == null || requestedDistance <= 0.0D) {
			return 0.0D;
		}
		Vec3 end = start.add(direction.scale(requestedDistance));
		BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return requestedDistance;
		}
		return Math.max(0.0D, start.distanceTo(hit.getLocation()) - 0.06D);
	}

	private static void playSynchronizedLaunchSound(ServerLevel level, Vec3 origin, long seed) {
		if (level == null || origin == null) {
			return;
		}
		for (ServerPlayer player : level.players()) {
			if (player.connection == null || player.distanceToSqr(origin) > AUDIO_AUDIENCE_RADIUS * AUDIO_AUDIENCE_RADIUS) {
				continue;
			}
			Vec3 source = nearestVirtualEmitter(origin, player.position());
			Holder<SoundEvent> sound = PolymerResourcePackUtils.hasMainPack(player)
					? ROCKET_SOUND
					: BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST);
			player.connection.send(new ClientboundSoundPacket(
					sound,
					SoundSource.BLOCKS,
					source.x,
					source.y,
					source.z,
					ROCKET_LAUNCH_SOUND_VOLUME,
					1.0F,
					seed
			));
		}
	}

	/** Explosion sound is deliberately global: a rocket impact is a server-wide
	 * event, while vanilla positional delivery would omit players outside the
	 * normal sound radius (or in another dimension). */
	private static void playServerWideExplosionSound(MinecraftServer server, Vec3 impact, float volume, float pitch, long seed) {
		if (server == null) {
			return;
		}
		Holder<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.GENERIC_EXPLODE.value());
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			if (player.connection == null) {
				continue;
			}
			// Put the one-shot source at the listener so client attenuation cannot
			// silence a global event from a distant coordinate.
			Vec3 listener = player.position();
			player.connection.send(new ClientboundSoundPacket(
					sound, SoundSource.BLOCKS, listener.x, listener.y, listener.z, volume, pitch, seed
			));
		}
	}

	private static Vec3 nearestVirtualEmitter(Vec3 origin, Vec3 listener) {
		Vec3 best = origin;
		double bestDistance = Double.MAX_VALUE;
		for (Vec3 direction : AUDIO_EMITTER_DIRECTIONS) {
			Vec3 candidate = origin.add(direction.x * AUDIO_EMITTER_RADIUS, 0.0D, direction.z * AUDIO_EMITTER_RADIUS);
			double distance = candidate.distanceToSqr(listener);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		return best;
	}

	/**
	 * Converts the armed, still-real build into displays in the same server tick
	 * as the redstone launch.  State is read again here so a powered block that
	 * changed its visual state just before lift-off is represented faithfully.
	 */
	private static boolean materializeRocketForLaunch(ServerLevel level) {
		if (level == null || rocket == null || rocket.blocks == null || rocket.blocks.isEmpty()) {
			return false;
		}
		List<PersistedRocketBlock> currentBlocks = new ArrayList<>();
		for (PersistedRocketBlock block : rocket.blocks) {
			if (block == null || !level.hasChunkAt(block.pos())) {
				return false;
			}
			BlockState current = level.getBlockState(block.pos());
			if (current.isAir()) {
				// Compatibility with an armed rocket saved by the previous version,
				// where displays were created at arm time.
				if (resolveRocketDisplay(level, block) != null) {
					currentBlocks.add(block);
					continue;
				}
				// The build was edited after /rocket.  A removed block simply does
				// not become part of the launched rigid body.
				continue;
			}
			block.state = serializeBlockState(current);
			currentBlocks.add(block);
		}
		if (currentBlocks.isEmpty()) {
			return false;
		}
		if (rocket.engines == null) {
			return false;
		}
		rocket.blocks = currentBlocks;
		Set<BlockPos> liveBlocks = new HashSet<>();
		for (PersistedRocketBlock block : currentBlocks) {
			liveBlocks.add(block.pos());
		}
		rocket.engines.removeIf(engine -> !liveBlocks.contains(engine.pos()));
		if (rocket.engines.isEmpty()) {
			return false;
		}
		rocket.recalculateBoundsFromBlocks();
		// The mass, centre of mass and inertia must reflect blocks that were
		// removed/replaced while the rocket was armed.
		rocket.initializePhysicsFromBlocks();
		rocket.captureMountedDevices(level);
		// Create the mobile camera endpoint before removing the source blocks and
		// migrate every already-playing screen onto it in this same tick.  This
		// keeps the renderer bot's stream id and shadow session alive through
		// materialisation instead of waiting for the next screen health check.
		updateRocketDeviceAnchors(level);
		handoffRocketCameraStreams(level);
		// The launch is rendered solely through per-player copies.  Retaining a
		// server-world BlockDisplay as well created a second, slightly delayed
		// model beside the client projection.
		discardServerRocketDisplays(level);
		rocket.clientOnlyVisuals = true;
		for (PersistedRocketBlock block : rocket.blocks) {
			if (!level.getBlockState(block.pos()).isAir()) {
				// The whole construction is removed as one rigid body.  Neighbour
				// notifications here made buttons, levers and other attached parts
				// break themselves and drop before their own launch removal.
				level.setBlock(block.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
		// Some attachable vanilla blocks schedule a survival check on the next
		// world tick.  Their drops belong to the launched hull, not the world.
		rocket.materializationDropCleanupUntilTick = level.getGameTime() + 8L;
		discardMaterializationComponentDrops(level);
		return true;
	}

	private static void handoffRocketCameraStreams(ServerLevel level) {
		if (level == null || rocket == null || rocket.devices == null) {
			return;
		}
		for (RocketMountedDevice device : rocket.devices) {
			if (!device.camera || device.anchorUuid == null || device.anchorUuid.isBlank()) {
				continue;
			}
			try {
				Entity anchor = level.getEntity(UUID.fromString(device.anchorUuid));
				if (anchor != null) {
					RendererBotCameraSystem.handoffLiveCameraStreamsToEntity(
							level,
							device.pos(),
							anchor.getUUID(),
							anchor.getX(),
							anchor.getY(),
							anchor.getZ(),
							anchor.getYRot(),
							anchor.getXRot()
					);
				}
			} catch (IllegalArgumentException ignored) {
				// The persisted UUID can only be malformed after a manual state edit.
			}
		}
	}

	private static void discardMaterializationComponentDrops(ServerLevel level) {
		if (level == null || rocket == null || rocket.materializationDropCleanupUntilTick < level.getGameTime() || rocket.blocks == null) {
			return;
		}
		AABB bounds = new AABB(rocket.minX, rocket.minY, rocket.minZ, rocket.maxX + 1.0D, rocket.maxY + 1.0D, rocket.maxZ + 1.0D).inflate(1.5D);
		for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, bounds, entity -> !entity.getItem().isEmpty())) {
			ItemStack stack = itemEntity.getItem();
			for (PersistedRocketBlock block : rocket.blocks) {
				BlockState state = deserializeBlockState(block.state);
				if (!state.isAir() && stack.is(state.getBlock().asItem())) {
					itemEntity.discard();
					break;
				}
			}
		}
	}

	private static Display.BlockDisplay resolveRocketDisplay(ServerLevel level, PersistedRocketBlock block) {
		if (level == null || block == null || block.displayUuid == null || block.displayUuid.isBlank()) {
			return null;
		}
		try {
			Entity entity = level.getEntity(UUID.fromString(block.displayUuid));
			return entity instanceof Display.BlockDisplay display && display.getTags().contains(DISPLAY_TAG) ? display : null;
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static void discardRocketDisplay(ServerLevel level, PersistedRocketBlock block) {
		Display.BlockDisplay display = resolveRocketDisplay(level, block);
		if (display != null) {
			display.discard();
		}
	}

	private static void discardLegacyRocketExhaustDisplays(ServerLevel level) {
		if (level == null || rocket == null || rocket.exhaustDisplayUuids == null || rocket.exhaustDisplayUuids.isEmpty()) {
			return;
		}
		for (String rawUuid : rocket.exhaustDisplayUuids) {
			if (rawUuid == null || rawUuid.isBlank()) {
				continue;
			}
			try {
				Entity entity = level.getEntity(UUID.fromString(rawUuid));
				if (entity != null && entity.getTags().contains(EXHAUST_DISPLAY_TAG)) {
					entity.discard();
				}
			} catch (IllegalArgumentException ignored) {
				// Ignore malformed legacy state and clear it below.
			}
		}
		rocket.exhaustDisplayUuids.clear();
		stateDirty = true;
	}

	private static boolean isRocketBlockPoweredFromOutside(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || rocket == null) {
			return false;
		}
		// An armed hull may contain redstone components for decoration or internal
		// systems.  They must not be able to launch the rocket: only a signal that
		// crosses the hull boundary from an external neighbour is a launch trigger.
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			if (rocket.containsBlock(neighbour)) {
				continue;
			}
			if (level.getSignal(neighbour, direction) > 0 || level.getDirectSignal(neighbour, direction) > 0) {
				return true;
			}
		}
		return false;
	}

	private static void tickArmedPowerScan(ServerLevel level, MinecraftServer server) {
		if (level == null || server == null || rocket == null || rocket.blocks == null || rocket.blocks.isEmpty()) {
			return;
		}
		int scanned = 0;
		while (scanned++ < ARMED_POWER_CHECKS_PER_TICK) {
			if (armedPowerScanCursor >= rocket.blocks.size()) {
				armedPowerScanCursor = 0;
				armedPowerStatePrimed = true;
			}
			PersistedRocketBlock block = rocket.blocks.get(armedPowerScanCursor++);
			if (block == null || !level.hasChunkAt(block.pos())) {
				continue;
			}
			BlockPos pos = block.pos();
			if (isRocketBlockPoweredFromOutside(level, pos)) {
				ACTIVE_ARMED_POWER_POINTS.add(pos);
			} else {
				ACTIVE_ARMED_POWER_POINTS.remove(pos);
			}
		}
		if (!armedPowerStatePrimed) {
			return;
		}
		boolean powered = !ACTIVE_ARMED_POWER_POINTS.isEmpty();
		if (powered && !rocket.lastTriggerPowered) {
			startLaunch(server, level);
			return;
		}
		rocket.lastTriggerPowered = powered;
	}

	private static void resetArmedPowerScan() {
		ACTIVE_ARMED_POWER_POINTS.clear();
		armedPowerScanCursor = 0;
		armedPowerStatePrimed = false;
	}

	private static ServerLevel rocketLevel(MinecraftServer server) {
		if (server == null || rocket == null || rocket.dimension == null) {
			return null;
		}
		Identifier dimensionId = Identifier.tryParse(rocket.dimension);
		return dimensionId == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
	}

	private static void loadState(MinecraftServer server) {
		rocket = null;
		stateDirty = false;
		Path path = statePath(server);
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				RocketState loaded = STATE_GSON.fromJson(reader, RocketState.class);
				if (isValidState(loaded)) {
				rocket = loaded;
				resetArmedPowerScan();
				} else {
					Lg2.LOGGER.warn("Ignoring invalid persisted rocket-launch state from {}", path);
				}
			} catch (Exception exception) {
				Lg2.LOGGER.warn("Failed to load rocket-launch state from {}", path, exception);
			}
		}
		stateLoaded = true;
		MonitorYandexMapsRuntime.setGpsEnabled(server, rocket != null && LaunchStage.COMPLETED.name().equals(rocket.stage));
	}

	private static void saveState(MinecraftServer server) {
		if (!stateLoaded || !stateDirty || server == null) {
			return;
		}
		Path path = statePath(server);
		try {
			Files.createDirectories(path.getParent());
			if (rocket == null) {
				Files.deleteIfExists(path);
			} else {
				try (Writer writer = Files.newBufferedWriter(path)) {
					STATE_GSON.toJson(rocket, writer);
				}
			}
			stateDirty = false;
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to save rocket-launch state to {}", path, exception);
		}
	}

	private static Path statePath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(STATE_FILE_NAME);
	}

	private static boolean isValidState(RocketState state) {
		if (state == null || LaunchStage.fromOrNull(state.stage) == null || state.dimension == null || Identifier.tryParse(state.dimension) == null) {
			return false;
		}
		if (state.blocks == null) {
			state.blocks = new ArrayList<>();
		}
		if (state.engines == null) {
			state.engines = new ArrayList<>();
		}
		if (state.debris == null) {
			state.debris = new ArrayList<>();
		}
		for (DebrisState debris : state.debris) {
			if (debris != null) {
				debris.ensureBlocks();
			}
		}
		if (state.exhaustDisplayUuids == null) {
			state.exhaustDisplayUuids = new ArrayList<>();
		}
		return true;
	}

	private static String serializeBlockState(BlockState state) {
		if (state == null) {
			return "minecraft:air";
		}
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		StringBuilder serialized = new StringBuilder(id == null ? "minecraft:air" : id.toString());
		if (state.getProperties().isEmpty()) {
			return serialized.toString();
		}
		serialized.append('[');
		boolean first = true;
		for (Property<?> property : state.getProperties()) {
			if (!first) {
				serialized.append(',');
			}
			first = false;
			serialized.append(property.getName()).append('=').append(propertyValueName(state, property));
		}
		return serialized.append(']').toString();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static String propertyValueName(BlockState state, Property<?> property) {
		Property rawProperty = property;
		return rawProperty.getName(state.getValue(rawProperty));
	}

	private static BlockState deserializeBlockState(String serialized) {
		if (serialized == null || serialized.isBlank()) {
			return Blocks.AIR.defaultBlockState();
		}
		int propertiesStart = serialized.indexOf('[');
		String rawId = propertiesStart < 0 ? serialized : serialized.substring(0, propertiesStart);
		Identifier id = Identifier.tryParse(rawId);
		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
			return Blocks.AIR.defaultBlockState();
		}
		Block block = BuiltInRegistries.BLOCK.getValue(id);
		BlockState state = block.defaultBlockState();
		if (propertiesStart < 0 || !serialized.endsWith("]")) {
			return state;
		}
		String properties = serialized.substring(propertiesStart + 1, serialized.length() - 1);
		for (String propertyString : properties.split(",")) {
			int split = propertyString.indexOf('=');
			if (split <= 0 || split >= propertyString.length() - 1) {
				continue;
			}
			Property<?> property = block.getStateDefinition().getProperty(propertyString.substring(0, split));
			if (property != null) {
				state = applyProperty(state, property, propertyString.substring(split + 1));
			}
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
	}

	private static String formatPos(BlockPos pos) {
		return pos == null ? "?" : pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private enum LaunchStage {
		ARMED,
		LAUNCHING,
		CRASHED,
		COMPLETED;

		private static LaunchStage from(String raw) {
			LaunchStage stage = fromOrNull(raw);
			return stage == null ? ARMED : stage;
		}

		private static LaunchStage fromOrNull(String raw) {
			if (raw == null) {
				return null;
			}
			try {
				return valueOf(raw.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}
	}

	private enum SelectionMode {
		NONE,
		ROCKET,
		DESELECT,
		ENGINES
	}

	private static final class AdminSelection {
		private final List<BlockPos> bottomPoints = new ArrayList<>();
		private final List<BlockPos> enginePoints = new ArrayList<>();
		private final Set<BlockPos> excludedBlocks = new HashSet<>();
		private SelectionMode mode = SelectionMode.NONE;
		private SelectionBounds automaticBounds;
		private BlockPos deselectFirstPoint;
		private SelectionBounds deselectBounds;

		private boolean isComplete() {
			return this.bottomPoints.size() >= 2;
		}

		private boolean addBottomPoint(BlockPos point) {
			if (point == null || this.bottomPoints.contains(point)) {
				return false;
			}
			this.bottomPoints.add(point.immutable());
			this.automaticBounds = null;
			return true;
		}

		private boolean addEnginePoint(BlockPos point) {
			if (point == null || this.enginePoints.contains(point)) {
				return false;
			}
			this.enginePoints.add(point.immutable());
			return true;
		}

		private void removeEnginePoint(BlockPos point) {
			this.enginePoints.remove(point);
		}

		private void clearEnginePoints() {
			this.enginePoints.clear();
		}

		private void beginDeselect(SelectionBounds bounds) {
			this.deselectFirstPoint = null;
			this.deselectBounds = bounds;
		}

		private void clearDeselectState() {
			this.deselectFirstPoint = null;
			this.deselectBounds = null;
		}

		/**
		 * Stores the first corner and returns it when the second corner arrives.
		 * A pair of equal points is deliberately valid: it deselects one block.
		 */
		private BlockPos takeDeselectFirstPoint(BlockPos point) {
			if (this.deselectFirstPoint == null) {
				this.deselectFirstPoint = point.immutable();
				return null;
			}
			BlockPos first = this.deselectFirstPoint;
			this.deselectFirstPoint = null;
			return first;
		}

		private int excludeBox(ServerLevel level, SelectionBounds bounds, BlockPos first, BlockPos second) {
			if (level == null || bounds == null || first == null || second == null) {
				return 0;
			}
			int minX = Math.max(bounds.minX, Math.min(first.getX(), second.getX()));
			int minY = Math.max(bounds.minY, Math.min(first.getY(), second.getY()));
			int minZ = Math.max(bounds.minZ, Math.min(first.getZ(), second.getZ()));
			int maxX = Math.min(bounds.maxX, Math.max(first.getX(), second.getX()));
			int maxY = Math.min(bounds.maxY, Math.max(first.getY(), second.getY()));
			int maxZ = Math.min(bounds.maxZ, Math.max(first.getZ(), second.getZ()));
			int excluded = 0;
			for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
				if (level.getBlockState(pos).isAir() || !this.excludedBlocks.add(pos.immutable())) {
					continue;
				}
				excluded++;
			}
			this.enginePoints.removeIf(this.excludedBlocks::contains);
			this.automaticBounds = null;
			return excluded;
		}

		private boolean addExcludedBlock(BlockPos point) {
			if (point == null || !this.excludedBlocks.add(point.immutable())) {
				return false;
			}
			this.enginePoints.remove(point);
			this.automaticBounds = null;
			return true;
		}

		private void removeExcludedBlock(BlockPos point) {
			this.excludedBlocks.remove(point);
			this.automaticBounds = null;
		}
	}

	private record SelectionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		private long volume() {
			return (long) (this.maxX - this.minX + 1) * (this.maxY - this.minY + 1) * (this.maxZ - this.minZ + 1);
		}

		private boolean contains(BlockPos pos) {
			return pos != null
					&& pos.getX() >= this.minX && pos.getX() <= this.maxX
					&& pos.getY() >= this.minY && pos.getY() <= this.maxY
					&& pos.getZ() >= this.minZ && pos.getZ() <= this.maxZ;
		}
	}

	private static final class RocketState {
		private String stage;
		private String dimension;
		private int minX;
		private int minY;
		private int minZ;
		private int maxX;
		private int maxY;
		private int maxZ;
		private boolean lastTriggerPowered;
		private long launchElapsedTicks;
		private long materializationDropCleanupUntilTick;
		private List<PersistedRocketBlock> blocks = new ArrayList<>();
		private transient Set<BlockPos> blockPositions;
		private List<PersistedEnginePoint> engines = new ArrayList<>();
		private List<RocketOccupant> occupants = new ArrayList<>();
		private List<RocketMountedDevice> devices = new ArrayList<>();
		private List<String> mountedScreenUuids = new ArrayList<>();
		private List<RocketMountedScreen> mountedScreens = new ArrayList<>();
		private List<DebrisState> debris = new ArrayList<>();
		private List<String> exhaustDisplayUuids = new ArrayList<>();
		private boolean clientOnlyVisuals;
		private boolean physicsInitialized;
		private double pivotX;
		private double pivotY;
		private double pivotZ;
		private double inertia = 1.0D;
		private double inertiaXX = 1.0D;
		private double inertiaYY = 1.0D;
		private double inertiaZZ = 1.0D;
		private double inertiaXY;
		private double inertiaXZ;
		private double inertiaYZ;
		private double aerodynamicCentreX;
		private double aerodynamicCentreY;
		private double aerodynamicCentreZ;
		private double aerodynamicReferenceArea = 1.0D;
		private double physicsX;
		private double physicsY;
		private double physicsZ;
		private double velocityX;
		private double velocityY;
		private double velocityZ;
		private double lastAccelerationX;
		private double lastAccelerationY;
		private double lastAccelerationZ;
		private double angularVelocityX;
		private double angularVelocityY;
		private double angularVelocityZ;
		private double rotationX;
		private double rotationY;
		private double rotationZ;
		private double rotationW = 1.0D;

		private Vec3 origin() {
			return new Vec3((this.minX + this.maxX + 1) * 0.5D, this.minY, (this.minZ + this.maxZ + 1) * 0.5D);
		}

		private void recalculateBoundsFromBlocks() {
			if (this.blocks == null || this.blocks.isEmpty()) {
				return;
			}
			this.blockPositions = null;
			this.minX = Integer.MAX_VALUE;
			this.minY = Integer.MAX_VALUE;
			this.minZ = Integer.MAX_VALUE;
			this.maxX = Integer.MIN_VALUE;
			this.maxY = Integer.MIN_VALUE;
			this.maxZ = Integer.MIN_VALUE;
			for (PersistedRocketBlock block : this.blocks) {
				this.minX = Math.min(this.minX, block.x);
				this.minY = Math.min(this.minY, block.y);
				this.minZ = Math.min(this.minZ, block.z);
				this.maxX = Math.max(this.maxX, block.x);
				this.maxY = Math.max(this.maxY, block.y);
				this.maxZ = Math.max(this.maxZ, block.z);
			}
		}

		private void initializePhysicsFromBlocks() {
			if (this.blocks == null || this.blocks.isEmpty()) {
				return;
			}
			double sumX = 0.0D;
			double sumY = 0.0D;
			double sumZ = 0.0D;
			for (PersistedRocketBlock block : this.blocks) {
				sumX += block.x + 0.5D;
				sumY += block.y + 0.5D;
				sumZ += block.z + 0.5D;
			}
			this.pivotX = sumX / this.blocks.size();
			this.pivotY = sumY / this.blocks.size();
			this.pivotZ = sumZ / this.blocks.size();
			double moment = 0.0D;
			this.inertiaXX = 0.0D;
			this.inertiaYY = 0.0D;
			this.inertiaZZ = 0.0D;
			this.inertiaXY = 0.0D;
			this.inertiaXZ = 0.0D;
			this.inertiaYZ = 0.0D;
			for (PersistedRocketBlock block : this.blocks) {
				double dx = block.x + 0.5D - this.pivotX;
				double dy = block.y + 0.5D - this.pivotY;
				double dz = block.z + 0.5D - this.pivotZ;
				moment += dx * dx + dy * dy + dz * dz;
				this.inertiaXX += dy * dy + dz * dz;
				this.inertiaYY += dx * dx + dz * dz;
				this.inertiaZZ += dx * dx + dy * dy;
				this.inertiaXY -= dx * dy;
				this.inertiaXZ -= dx * dz;
				this.inertiaYZ -= dy * dz;
			}
			// A one-block-wide body has a zero idealised moment about its long axis;
			// give each block a small cube moment so the tensor remains invertible.
			double cubeMoment = this.blocks.size() / 6.0D;
			this.inertiaXX += cubeMoment;
			this.inertiaYY += cubeMoment;
			this.inertiaZZ += cubeMoment;
			this.inertia = Math.max(this.blocks.size() * 0.75D, moment);
			this.aerodynamicCentreX = (this.minX + this.maxX + 1) * 0.5D;
			this.aerodynamicCentreZ = (this.minZ + this.maxZ + 1) * 0.5D;
			double bodyHeight = Math.max(1.0D, this.maxY - this.minY + 1.0D);
			// The centre of pressure of a rocket is normally aft of its centre of
			// mass.  It is body-relative, so it follows every tilt and roll.
			this.aerodynamicCentreY = this.minY + bodyHeight * 0.37D;
			double width = Math.max(1.0D, this.maxX - this.minX + 1.0D);
			double depth = Math.max(1.0D, this.maxZ - this.minZ + 1.0D);
			this.aerodynamicReferenceArea = Math.max(1.0D, Math.sqrt(width * depth));
			this.physicsX = 0.0D;
			this.physicsY = 0.0D;
			this.physicsZ = 0.0D;
			this.velocityX = 0.0D;
			this.velocityY = 0.0D;
			this.velocityZ = 0.0D;
			this.lastAccelerationX = 0.0D;
			this.lastAccelerationY = 0.0D;
			this.lastAccelerationZ = 0.0D;
			this.angularVelocityX = 0.0D;
			// This is an initial angular momentum, not a per-tick visual rotation.
			// In the absence of a torque a straight rocket therefore rolls evenly.
			this.angularVelocityY = ROCKET_IDLE_SPIN_PER_TICK;
			this.angularVelocityZ = 0.0D;
			this.rotationX = 0.0D;
			this.rotationY = 0.0D;
			this.rotationZ = 0.0D;
			this.rotationW = 1.0D;
			this.physicsInitialized = true;
		}

		private void captureOccupants(ServerLevel level) {
			this.occupants = new ArrayList<>();
			if (level == null) {
				return;
			}
			AABB hullBounds = new AABB(this.minX, this.minY, this.minZ, this.maxX + 1.0D, this.maxY + 1.0D, this.maxZ + 1.0D);
			for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, hullBounds, candidate -> candidate.isAlive() && !candidate.isSpectator())) {
				Vec3 localPosition = this.worldToBody(player.position());
				if (occupantHitsRocketHull(localPosition)) {
					continue;
				}
				RocketOccupant occupant = new RocketOccupant();
				occupant.uuid = player.getUUID().toString();
				occupant.x = localPosition.x;
				occupant.y = localPosition.y;
				occupant.z = localPosition.z;
				this.occupants.add(occupant);
			}
		}

		private void captureMountedDevices(ServerLevel level) {
			this.devices = new ArrayList<>();
			this.mountedScreenUuids = new ArrayList<>();
			this.mountedScreens = new ArrayList<>();
			if (level == null || this.blocks == null) {
				return;
			}
			for (PersistedRocketBlock block : this.blocks) {
				BlockPos pos = block.pos();
				BlockState state = level.getBlockState(pos);
				if (state.is(ModBlocks.CAMERA)) {
					float yaw = state.getValue(HorizontalDirectionalBlock.FACING).toYRot();
					float pitch = 0.0F;
					CameraOrientationStore.CameraPose pose = CameraOrientationStore.get(level, pos);
					if (pose != null) {
						yaw = pose.yaw();
						pitch = pose.pitch();
					}
					this.devices.add(RocketMountedDevice.camera(pos, yaw, pitch));
				} else if (state.is(ModBlocks.MICROPHONE)) {
					this.devices.add(RocketMountedDevice.microphone(pos));
				}
			}
			AABB bounds = new AABB(this.minX, this.minY, this.minZ, this.maxX + 1.0D, this.maxY + 1.0D, this.maxZ + 1.0D).inflate(1.0D);
			for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, bounds, MonitorScreenSystem::isMonitorFrame)) {
				BlockPos support = frame.blockPosition().relative(frame.getDirection().getOpposite());
				if (support.getX() < this.minX || support.getX() > this.maxX
						|| support.getY() < this.minY || support.getY() > this.maxY
						|| support.getZ() < this.minZ || support.getZ() > this.maxZ) {
					continue;
				}
				frame.addTag(MOUNTED_SCREEN_TAG);
				this.mountedScreenUuids.add(frame.getUUID().toString());
				MonitorScreenSystem.ensureDisplay(level, frame, MonitorScreenSystem.CONNECTION_ALL);
				List<Display.ItemDisplay> displays = MonitorScreenSystem.findDisplays(level, frame.blockPosition(), frame.getDirection());
				if (!displays.isEmpty()) {
					this.mountedScreens.add(RocketMountedScreen.capture(frame, displays.get(0)));
				}
			}
		}

		private RocketMountedScreen findMountedScreen(UUID frameUuid) {
			if (frameUuid == null || this.mountedScreens == null) {
				return null;
			}
			String rawUuid = frameUuid.toString();
			for (RocketMountedScreen screen : this.mountedScreens) {
				if (screen != null && rawUuid.equals(screen.frameUuid)) {
					return screen;
				}
			}
			return null;
		}

		private Vec3 pivot() {
			return new Vec3(this.pivotX, this.pivotY, this.pivotZ);
		}

		private Vec3 physicsOffset() {
			return new Vec3(this.physicsX, this.physicsY, this.physicsZ);
		}

		private Vec3 velocity() {
			return new Vec3(this.velocityX, this.velocityY, this.velocityZ);
		}

		private Vec3 lastAcceleration() {
			return new Vec3(this.lastAccelerationX, this.lastAccelerationY, this.lastAccelerationZ);
		}

		private Vec3 bodyToWorld(Vec3 bodyPosition) {
			Vec3 local = bodyPosition == null ? Vec3.ZERO : bodyPosition.subtract(this.pivot());
			return this.pivot().add(this.physicsOffset()).add(rotate(this.orientation(), local));
		}

		private Vec3 worldToBody(Vec3 worldPosition) {
			if (worldPosition == null) {
				return this.pivot();
			}
			return this.pivot().add(inverseRotate(this.orientation(), worldPosition.subtract(this.pivot().add(this.physicsOffset()))));
		}

		private Vec3 aerodynamicCentre() {
			return new Vec3(this.aerodynamicCentreX, this.aerodynamicCentreY, this.aerodynamicCentreZ);
		}

		private boolean hasRigidBodyProperties() {
			return this.physicsInitialized
					&& this.inertiaXX > 0.0D && this.inertiaYY > 0.0D && this.inertiaZZ > 0.0D
					&& this.aerodynamicReferenceArea > 0.0D;
		}

		private double aerodynamicArea() {
			return Math.max(1.0D, this.aerodynamicReferenceArea);
		}

		private double hullMass() {
			return Math.max(1.0D, this.blocks == null ? 0.0D : this.blocks.size());
		}

		private boolean containsBlock(BlockPos pos) {
			if (pos == null || this.blocks == null) {
				return false;
			}
			if (this.blockPositions == null) {
				this.blockPositions = new HashSet<>();
				for (PersistedRocketBlock block : this.blocks) {
					if (block != null) {
						this.blockPositions.add(block.pos());
					}
				}
			}
			return this.blockPositions.contains(pos);
		}

		private RocketMassProperties currentMassProperties() {
			double hullMass = this.hullMass();
			Vec3 hullCentre = this.pivot();
			double totalMass = hullMass;
			Vec3 weightedCentre = hullCentre.scale(hullMass);
			if (this.occupants != null) {
				for (RocketOccupant occupant : this.occupants) {
					if (occupant == null) {
						continue;
					}
					totalMass += ROCKET_OCCUPANT_MASS;
					weightedCentre = weightedCentre.add(occupant.position().scale(ROCKET_OCCUPANT_MASS));
				}
			}
			Vec3 centre = weightedCentre.scale(1.0D / Math.max(1.0D, totalMass));
			double xx = this.inertiaXX;
			double yy = this.inertiaYY;
			double zz = this.inertiaZZ;
			double xy = this.inertiaXY;
			double xz = this.inertiaXZ;
			double yz = this.inertiaYZ;
			Vec3 hullOffset = hullCentre.subtract(centre);
			double hx = hullOffset.x;
			double hy = hullOffset.y;
			double hz = hullOffset.z;
			xx += hullMass * (hy * hy + hz * hz);
			yy += hullMass * (hx * hx + hz * hz);
			zz += hullMass * (hx * hx + hy * hy);
			xy -= hullMass * hx * hy;
			xz -= hullMass * hx * hz;
			yz -= hullMass * hy * hz;
			if (this.occupants != null) {
				for (RocketOccupant occupant : this.occupants) {
					if (occupant == null) {
						continue;
					}
					Vec3 offset = occupant.position().subtract(centre);
					double x = offset.x;
					double y = offset.y;
					double z = offset.z;
					xx += ROCKET_OCCUPANT_MASS * (y * y + z * z);
					yy += ROCKET_OCCUPANT_MASS * (x * x + z * z);
					zz += ROCKET_OCCUPANT_MASS * (x * x + y * y);
					xy -= ROCKET_OCCUPANT_MASS * x * y;
					xz -= ROCKET_OCCUPANT_MASS * x * z;
					yz -= ROCKET_OCCUPANT_MASS * y * z;
				}
			}
			return new RocketMassProperties(totalMass, centre, xx, yy, zz, xy, xz, yz);
		}

		private Vec3 inertiaTimes(Vec3 vector) {
			return new Vec3(
					this.inertiaXX * vector.x + this.inertiaXY * vector.y + this.inertiaXZ * vector.z,
					this.inertiaXY * vector.x + this.inertiaYY * vector.y + this.inertiaYZ * vector.z,
					this.inertiaXZ * vector.x + this.inertiaYZ * vector.y + this.inertiaZZ * vector.z
			);
		}

		private Vec3 inverseInertiaTimes(Vec3 vector) {
			double a = this.inertiaXX;
			double b = this.inertiaXY;
			double c = this.inertiaXZ;
			double d = this.inertiaYY;
			double e = this.inertiaYZ;
			double f = this.inertiaZZ;
			double determinant = a * (d * f - e * e) - b * (b * f - c * e) + c * (b * e - c * d);
			if (Math.abs(determinant) < 0.0000001D) {
				return vector.scale(1.0D / Math.max(1.0D, this.inertia));
			}
			return new Vec3(
					((d * f - e * e) * vector.x + (c * e - b * f) * vector.y + (b * e - c * d) * vector.z) / determinant,
					((c * e - b * f) * vector.x + (a * f - c * c) * vector.y + (b * c - a * e) * vector.z) / determinant,
					((b * e - c * d) * vector.x + (b * c - a * e) * vector.y + (a * d - b * b) * vector.z) / determinant
			);
		}

		private Quaternionf orientation() {
			return new Quaternionf((float) this.rotationX, (float) this.rotationY, (float) this.rotationZ, (float) this.rotationW);
		}

		private void setOrientation(Quaternionf orientation) {
			this.rotationX = orientation.x;
			this.rotationY = orientation.y;
			this.rotationZ = orientation.z;
			this.rotationW = orientation.w;
		}
	}

	private record RocketMassProperties(
			double mass,
			Vec3 centre,
			double inertiaXX,
			double inertiaYY,
			double inertiaZZ,
			double inertiaXY,
			double inertiaXZ,
			double inertiaYZ
	) {
		private Vec3 inertiaTimes(Vec3 vector) {
			return new Vec3(
					this.inertiaXX * vector.x + this.inertiaXY * vector.y + this.inertiaXZ * vector.z,
					this.inertiaXY * vector.x + this.inertiaYY * vector.y + this.inertiaYZ * vector.z,
					this.inertiaXZ * vector.x + this.inertiaYZ * vector.y + this.inertiaZZ * vector.z
			);
		}

		private Vec3 inverseInertiaTimes(Vec3 vector) {
			double a = this.inertiaXX;
			double b = this.inertiaXY;
			double c = this.inertiaXZ;
			double d = this.inertiaYY;
			double e = this.inertiaYZ;
			double f = this.inertiaZZ;
			double determinant = a * (d * f - e * e) - b * (b * f - c * e) + c * (b * e - c * d);
			if (Math.abs(determinant) < 0.0000001D) {
				return vector.scale(1.0D / Math.max(1.0D, this.mass));
			}
			return new Vec3(
					((d * f - e * e) * vector.x + (c * e - b * f) * vector.y + (b * e - c * d) * vector.z) / determinant,
					((c * e - b * f) * vector.x + (a * f - c * c) * vector.y + (b * c - a * e) * vector.z) / determinant,
					((b * e - c * d) * vector.x + (b * c - a * e) * vector.y + (a * d - b * b) * vector.z) / determinant
			);
		}
	}

	private static final class RocketOccupant {
		private String uuid;
		private double x;
		private double y;
		private double z;
		private double velocityX;
		private double velocityY;
		private double velocityZ;

		private ServerPlayer resolve(ServerLevel level) {
			if (level == null || this.uuid == null || this.uuid.isBlank()) {
				return null;
			}
			try {
				return level.getServer().getPlayerList().getPlayer(UUID.fromString(this.uuid));
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}

		private Vec3 position() {
			return new Vec3(this.x, this.y, this.z);
		}

		private void setPosition(Vec3 position) {
			this.x = position.x;
			this.y = position.y;
			this.z = position.z;
		}

		private Vec3 velocity() {
			return new Vec3(this.velocityX, this.velocityY, this.velocityZ);
		}

		private void setVelocity(Vec3 velocity) {
			this.velocityX = velocity.x;
			this.velocityY = velocity.y;
			this.velocityZ = velocity.z;
		}
	}

	public record RocketCameraFeed(UUID followEntityUuid, double expectedX, double expectedY, double expectedZ, float yaw, float pitch) {
	}

	private static final class RocketMountedDevice {
		private int x;
		private int y;
		private int z;
		private boolean camera;
		private boolean microphone;
		private float yaw;
		private float pitch;
		private String anchorUuid;

		private static RocketMountedDevice camera(BlockPos pos, float yaw, float pitch) {
			RocketMountedDevice device = new RocketMountedDevice();
			device.x = pos.getX();
			device.y = pos.getY();
			device.z = pos.getZ();
			device.camera = true;
			device.yaw = yaw;
			device.pitch = pitch;
			return device;
		}

		private static RocketMountedDevice microphone(BlockPos pos) {
			RocketMountedDevice device = new RocketMountedDevice();
			device.x = pos.getX();
			device.y = pos.getY();
			device.z = pos.getZ();
			device.microphone = true;
			return device;
		}

		private BlockPos pos() {
			return new BlockPos(this.x, this.y, this.z);
		}
	}

	private static final class RocketMountedScreen {
		private String frameUuid;
		private String displayUuid;
		private double x;
		private double y;
		private double z;
		private float facingYaw;

		private static RocketMountedScreen capture(ItemFrame frame, Display.ItemDisplay display) {
			RocketMountedScreen screen = new RocketMountedScreen();
			screen.frameUuid = frame.getUUID().toString();
			screen.displayUuid = display.getUUID().toString();
			Vec3 position = display.position();
			screen.x = position.x;
			screen.y = position.y;
			screen.z = position.z;
			screen.facingYaw = frame.getDirection().toYRot();
			return screen;
		}

		private Vec3 localPosition() {
			return new Vec3(this.x, this.y, this.z);
		}
	}

	private static final class PersistedEnginePoint {
		private int x;
		private int y;
		private int z;

		private PersistedEnginePoint(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		private Vec3 position() {
			return new Vec3(this.x + 0.5D, this.y + 0.5D, this.z + 0.5D);
		}

		private BlockPos pos() {
			return new BlockPos(this.x, this.y, this.z);
		}
	}

	private static final class EngineCluster {
		private final List<PersistedEnginePoint> points;

		private EngineCluster(List<PersistedEnginePoint> points) {
			this.points = points == null ? List.of() : List.copyOf(points);
		}
	}

	private record RocketProjectionPose(Vec3 pivot, Quaternionf orientation, double scale) {
	}

	private static final class RocketProjectionView {
		private final List<Display.BlockDisplay> displays = new ArrayList<>();
	}

	private static final class DebrisState {
		// state/displayUuid are kept for a one-time migration of debris saved by
		// earlier versions, where every fragment consisted of exactly one block.
		private String state;
		private String displayUuid;
		private List<DebrisBlock> blocks = new ArrayList<>();
		private double x;
		private double y;
		private double z;
		private double velocityX;
		private double velocityY;
		private double velocityZ;
		private double angularVelocityX;
		private double angularVelocityY;
		private double angularVelocityZ;
		private double rotationX;
		private double rotationY;
		private double rotationZ;
		private double rotationW = 1.0D;
		private int age;

		private void ensureBlocks() {
			if (this.blocks == null) {
				this.blocks = new ArrayList<>();
			}
			if (this.blocks.isEmpty() && this.state != null && !this.state.isBlank()) {
				DebrisBlock legacyBlock = new DebrisBlock(this.state, 0, 0, 0);
				legacyBlock.displayUuid = this.displayUuid;
				this.blocks.add(legacyBlock);
			}
		}

		private Quaternionf orientation() {
			return new Quaternionf((float) this.rotationX, (float) this.rotationY, (float) this.rotationZ, (float) this.rotationW).normalize();
		}

		private void setOrientation(Quaternionf orientation) {
			Quaternionf normalized = new Quaternionf(orientation).normalize();
			this.rotationX = normalized.x;
			this.rotationY = normalized.y;
			this.rotationZ = normalized.z;
			this.rotationW = normalized.w;
		}

		private Quaternionf advanceRotation() {
			Quaternionf orientation = this.orientation().mul(quaternionFromAngularVelocity(new Vec3(
					this.angularVelocityX, this.angularVelocityY, this.angularVelocityZ
			))).normalize();
			this.angularVelocityX *= 0.994D;
			this.angularVelocityY *= 0.994D;
			this.angularVelocityZ *= 0.994D;
			this.setOrientation(orientation);
			return orientation;
		}
	}

	private static final class DebrisBlock {
		private String state;
		private int offsetX;
		private int offsetY;
		private int offsetZ;
		private String displayUuid;

		private DebrisBlock(String state, int offsetX, int offsetY, int offsetZ) {
			this.state = state;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
			this.offsetZ = offsetZ;
		}
	}

	private static final class PersistedRocketBlock {
		private int x;
		private int y;
		private int z;
		private String state;
		private String displayUuid;

		private PersistedRocketBlock(int x, int y, int z, String state) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.state = state;
		}

		private BlockPos pos() {
			return new BlockPos(this.x, this.y, this.z);
		}
	}
}
