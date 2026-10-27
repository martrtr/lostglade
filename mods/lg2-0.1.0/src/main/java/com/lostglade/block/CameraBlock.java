package com.lostglade.block;

import com.lostglade.item.ModItems;
import com.lostglade.server.MonitorScreenSystem;
import com.lostglade.server.RocketLaunchEventSystem;
import com.lostglade.server.CameraOrientationStore;
import com.lostglade.server.BluetoothLinkSystem;
import com.lostglade.server.CameraRelocationSystem;
import com.lostglade.server.PlacedDeviceNameStore;
import com.lostglade.server.ServerSelectionHighlightSystem;
import com.lostglade.server.ServerMechanicsGateSystem;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.block.PolymerHeadBlock;
import eu.pb4.polymer.core.api.block.SimplePolymerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class CameraBlock extends SimplePolymerBlock implements PolymerHeadBlock {
	private final BlockState hitboxState;

	public CameraBlock(BlockBehaviour.Properties properties) {
		super(properties, Blocks.STRUCTURE_VOID);
		this.hitboxState = Blocks.PLAYER_HEAD.defaultBlockState();
		this.registerDefaultState(this.stateDefinition.any().setValue(HorizontalDirectionalBlock.FACING, net.minecraft.core.Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HorizontalDirectionalBlock.FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		net.minecraft.core.Direction facing = context.getHorizontalDirection().getOpposite();
		if (context.getPlayer() != null) {
			float yaw = yawTo(captureBaseOrigin(context.getClickedPos()), context.getPlayer().getEyePosition());
			facing = net.minecraft.core.Direction.fromYRot(yaw);
		}
		return this.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(HorizontalDirectionalBlock.FACING, rotation.rotate(state.getValue(HorizontalDirectionalBlock.FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(HorizontalDirectionalBlock.FACING)));
	}

	@Override
	public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
		return this.hitboxState;
	}

	@Override
	public String getPolymerSkinValue(BlockState state, BlockPos pos, PacketContext context) {
		// getPolymerHeadPacket below uses a local resource-pack texture instead.
		return "";
	}

	@Override
	public Packet<?> getPolymerHeadPacket(BlockState state, BlockPos pos, PacketContext context) {
		CompoundTag blockEntityData = new CompoundTag();
		blockEntityData.putString("id", "minecraft:skull");
		CompoundTag profile = new CompoundTag();
		// Since 1.21.11 profiles can override their skin with a resource-pack
		// texture.  Unlike downloaded player skins its alpha stays transparent.
		profile.putString("texture", "lg2:skin/camera_collision_head");
		blockEntityData.put("profile", profile);
		blockEntityData.putInt("x", pos.getX());
		blockEntityData.putInt("y", pos.getY());
		blockEntityData.putInt("z", pos.getZ());
		return PolymerBlockUtils.createBlockEntityPacket(pos, BlockEntityType.SKULL, blockEntityData);
	}

	/**
	 * Reapplies the client-only transparent skin after a client predicted a
	 * break which the server then cancelled.  That prediction can replace the
	 * Polymer block-entity data with the vanilla PLAYER_HEAD data for a moment,
	 * exposing the default Steve head even though the real camera block remains.
	 */
	public static void resendCollisionHead(ServerPlayer player, BlockPos pos) {
		if (player == null || pos == null || !(player.level() instanceof ServerLevel level)) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof CameraBlock camera)) {
			return;
		}
		PacketContext.NotNullWithPlayer context = PacketContext.create(player);
		player.connection.send(camera.getPolymerHeadPacket(state, pos, context));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return this.hitboxState.getShape(level, pos, context);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return this.hitboxState.getCollisionShape(level, pos, context);
	}

	@Override
	protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
		return this.hitboxState.getShape(level, pos);
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		Entity breaker = builder.getOptionalParameter(LootContextParams.THIS_ENTITY);
		if (!ServerMechanicsGateSystem.shouldDropUpgradeLockedDevice(this, breaker)) {
			return List.of();
		}
		return List.of(new ItemStack(ModItems.CAMERA));
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (level instanceof ServerLevel serverLevel) {
			// Vanilla places the pulled block before the moving-piston ticker invokes
			// its final callback. Commit the retained display here, while its source
			// and destination are still known, so sticky pistons cannot leave the
			// ItemDisplay at the old coordinate until somebody clicks the camera.
			CameraRelocationSystem.finishPistonMoveAt(serverLevel, pos);
			BlockPos logicalCameraPos = CameraRelocationSystem.logicalCameraPosition(serverLevel, pos);
			boolean pistonDestinationPending = CameraRelocationSystem.isPistonCameraMoveDestinationPending(serverLevel, pos);
			CameraOrientationStore.CameraPose pose = CameraOrientationStore.get(serverLevel, logicalCameraPos);
			float yaw = pose != null ? pose.yaw() : state.getValue(HorizontalDirectionalBlock.FACING).toYRot();
			float pitch = pose != null ? pose.pitch() : 0.0F;
			if (!pistonDestinationPending) {
				CameraDisplayHelper.spawnOrUpdate(serverLevel, pos, yaw, pitch);
				MonitorScreenSystem.onCameraNetworkChanged(serverLevel, logicalCameraPos);
			}
		}
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		PlacedDeviceNameStore.rememberPlacedCameraName(serverLevel, pos, stack);
		if (placer == null) {
			return;
		}
		aimAt(serverLevel, pos, placer.getEyePosition());
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		return this.aimAtPlayer(level, pos, player);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
		return this.aimAtPlayer(level, pos, player);
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation, boolean notify) {
		super.neighborChanged(state, level, pos, block, orientation, notify);
		if (level instanceof ServerLevel serverLevel
				&& !CameraRelocationSystem.isPistonCameraMoveDestinationPending(serverLevel, pos)) {
			MonitorScreenSystem.onCameraNetworkChanged(serverLevel, CameraRelocationSystem.logicalCameraPosition(serverLevel, pos));
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		if (CameraRelocationSystem.isPistonCameraMovePending(level, pos)) {
			// Vanilla temporarily replaces a pushed block with MOVING_PISTON and
			// only restores it at the destination after the animation. Keep the
			// camera identity alive until that completion is observed, but retain
			// vanilla's neighbour-notification behaviour for the physical block.
			super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
			return;
		}
		if (RocketLaunchEventSystem.isLaunchedMountedDevice(level, pos)) {
			// A rocket keeps its device identity while the physical block becomes a
			// moving display/anchor.  Do not run any normal removal work: apart from
			// erasing the pose/link it can trigger secondary network cleanup after
			// the endpoint has already been preserved.
			return;
		}
		BlockPos logicalCameraPos = CameraRelocationSystem.removeCameraIdentity(level, pos);
		CameraDisplayHelper.remove(level, pos);
		CameraOrientationStore.remove(level, logicalCameraPos);
		PlacedDeviceNameStore.removeCameraName(level, logicalCameraPos);
		BluetoothLinkSystem.removeBlockEndpoint(level, BluetoothLinkSystem.EndpointType.CAMERA, logicalCameraPos);
		MonitorScreenSystem.onCameraNetworkChanged(level, logicalCameraPos);
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
	}

	public static void applyFallbackName(ItemStack out, PacketContext context) {
		String language = context.getPlayer() != null ? context.getPlayer().clientInformation().language() : "";
		String normalized = language == null ? "" : language.toLowerCase();
		String name = "Camera";
		if (normalized.startsWith("rpr")) {
			name = "Камѣра";
		} else if (normalized.startsWith("ru")) {
			name = "Камера";
		} else if (normalized.startsWith("uk")) {
			name = "Камера";
		} else if (normalized.startsWith("ja")) {
			name = "カメラ";
		}
		out.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withItalic(false)));
	}

	public static Vec3 captureBaseOrigin(BlockPos cameraPos) {
		return new Vec3(cameraPos.getX() + 0.5D, cameraPos.getY() + 0.25D, cameraPos.getZ() + 0.5D);
	}

	/**
	 * Returns the visible model entity which belongs to this placed camera.
	 * Shadow-camera feeds use this to keep their physical camera body out of
	 * their own first-person image.
	 */
	public static Set<UUID> getCameraDisplayEntityUuids(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return Set.of();
		}
		Set<UUID> entityUuids = new LinkedHashSet<>();
		for (Display.ItemDisplay display : CameraDisplayHelper.findDisplays(level, pos)) {
			if (display != null && display.isAlive()) {
				entityUuids.add(display.getUUID());
			}
		}
		return entityUuids.isEmpty() ? Set.of() : Set.copyOf(entityUuids);
	}


	/** Mirrors vanilla's moving-piston progress for the separate ItemDisplay model. */
	public static void movePistonCameraDisplay(ServerLevel level, BlockPos source, BlockPos destination, UUID displayUuid, Vec3 captureBaseOrigin, float yaw, float pitch) {
		CameraDisplayHelper.moveForPiston(level, source, destination, displayUuid, captureBaseOrigin, yaw, pitch);
	}

	/** Re-tags the same ItemDisplay at the piston destination, preserving its UUID and stream exclusion. */
	public static void finishPistonCameraDisplay(ServerLevel level, BlockPos source, BlockPos destination, UUID displayUuid, float yaw, float pitch) {
		CameraDisplayHelper.finishPistonMove(level, source, destination, displayUuid, yaw, pitch);
	}

	/** Removes the retained model if vanilla rejects the camera at the piston target. */
	public static void discardPistonCameraDisplay(ServerLevel level, BlockPos source) {
		CameraDisplayHelper.remove(level, source);
	}

	/** Returns the placed camera model for a Bluetooth selection outline. */
	public static List<ServerSelectionHighlightSystem.DisplayBlueprint> resolveBluetoothHighlightBlueprints(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return List.of();
		}
		BlockPos physicalCameraPos = CameraRelocationSystem.physicalCameraPosition(level, pos);
		List<ServerSelectionHighlightSystem.DisplayBlueprint> blueprints = new ArrayList<>();
		for (Display.ItemDisplay display : CameraDisplayHelper.findDisplays(level, physicalCameraPos)) {
			if (display.isAlive()) {
				blueprints.add(new ServerSelectionHighlightSystem.EntityGlowBlueprint(display));
			}
		}
		return blueprints;
	}

	public static Vec3 captureOrigin(BlockPos cameraPos, float yaw, float pitch) {
		return captureOrigin(captureBaseOrigin(cameraPos), yaw, pitch);
	}

	/** Camera lens position for a base that may be between block centres while a piston moves it. */
	public static Vec3 captureOrigin(Vec3 base, float yaw, float pitch) {
		Vec3 forward = forwardVector(yaw, pitch).scale(0.24D);
		return base.add(forward);
	}

	public static float yawTo(Vec3 origin, Vec3 target) {
		Vec3 delta = target.subtract(origin);
		return (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
	}

	public static float pitchTo(Vec3 origin, Vec3 target) {
		Vec3 delta = target.subtract(origin);
		double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		return (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
	}

	private static Vec3 forwardVector(float yaw, float pitch) {
		float yawRadians = yaw * ((float) Math.PI / 180.0F);
		float pitchRadians = pitch * ((float) Math.PI / 180.0F);
		float x = -Mth.sin(yawRadians) * Mth.cos(pitchRadians);
		float y = -Mth.sin(pitchRadians);
		float z = Mth.cos(yawRadians) * Mth.cos(pitchRadians);
		return new Vec3(x, y, z);
	}

	private InteractionResult aimAtPlayer(Level level, BlockPos pos, Player player) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		boolean changed = aimAt(serverLevel, pos, serverPlayer.getEyePosition());
		return changed ? InteractionResult.CONSUME : InteractionResult.PASS;
	}

	private static boolean aimAt(ServerLevel level, BlockPos pos, Vec3 target) {
		if (level == null || pos == null || target == null) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof CameraBlock)) {
			return false;
		}
		Vec3 origin = captureBaseOrigin(pos);
		float yaw = yawTo(origin, target);
		float pitch = pitchTo(origin, target);
		net.minecraft.core.Direction facing = net.minecraft.core.Direction.fromYRot(yaw);
		BlockState updatedState = state.setValue(HorizontalDirectionalBlock.FACING, facing);
		if (updatedState != state) {
			level.setBlock(pos, updatedState, Block.UPDATE_CLIENTS);
		}
		BlockPos logicalCameraPos = CameraRelocationSystem.logicalCameraPosition(level, pos);
		CameraOrientationStore.set(level, logicalCameraPos, yaw, pitch);
		CameraRelocationSystem.updateCameraOrientation(level, pos, yaw, pitch);
		CameraDisplayHelper.spawnOrUpdate(level, pos, yaw, pitch);
		MonitorScreenSystem.onCameraNetworkChanged(level, logicalCameraPos);
		return true;
	}
}
