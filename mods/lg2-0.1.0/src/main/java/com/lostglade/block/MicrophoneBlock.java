package com.lostglade.block;

import com.lostglade.server.MicrophoneSystem;
import com.lostglade.server.BluetoothLinkSystem;
import com.lostglade.server.PlacedDeviceNameStore;
import com.lostglade.server.RocketLaunchEventSystem;
import com.lostglade.server.ServerSelectionHighlightSystem;
import com.lostglade.server.ServerMechanicsGateSystem;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.block.PolymerHeadBlock;
import eu.pb4.polymer.core.api.block.SimplePolymerBlock;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;
import java.util.ArrayList;

public final class MicrophoneBlock extends SimplePolymerBlock implements PolymerHeadBlock {
	private final BlockState hitboxState;

	public MicrophoneBlock(BlockBehaviour.Properties properties) {
		super(properties, Blocks.STRUCTURE_VOID);
		this.hitboxState = Blocks.PLAYER_HEAD.defaultBlockState();
	}

	@Override
	public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
		return this.hitboxState;
	}

	@Override
	public String getPolymerSkinValue(BlockState state, BlockPos pos, PacketContext context) {
		return "";
	}

	@Override
	public Packet<?> getPolymerHeadPacket(BlockState state, BlockPos pos, PacketContext context) {
		CompoundTag blockEntityData = new CompoundTag();
		blockEntityData.putString("id", "minecraft:skull");
		CompoundTag profile = new CompoundTag();
		profile.putString("texture", "lg2:skin/camera_collision_head");
		blockEntityData.put("profile", profile);
		blockEntityData.putInt("x", pos.getX());
		blockEntityData.putInt("y", pos.getY());
		blockEntityData.putInt("z", pos.getZ());
		return PolymerBlockUtils.createBlockEntityPacket(pos, BlockEntityType.SKULL, blockEntityData);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState();
	}

	@Override
	public boolean forceLightUpdates(BlockState blockState) {
		return true;
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state;
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state;
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
		return List.of(new ItemStack(this));
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level instanceof ServerLevel serverLevel) {
			PlacedDeviceNameStore.rememberPlacedMicrophoneName(serverLevel, pos, stack);
			MicrophoneSystem.trackMicrophone(serverLevel, pos);
		}
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (level instanceof ServerLevel serverLevel) {
			ensureDisplay(serverLevel, pos);
		}
	}

	/** Restores the visual model after a chunk containing a microphone is loaded. */
	public static void ensureDisplay(ServerLevel level, BlockPos pos) {
		MicrophoneDisplayHelper.spawnOrUpdate(level, pos);
	}

	/** Returns the placed microphone model for a Bluetooth selection outline. */
	public static List<ServerSelectionHighlightSystem.DisplayBlueprint> resolveBluetoothHighlightBlueprints(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return List.of();
		}
		List<ServerSelectionHighlightSystem.DisplayBlueprint> blueprints = new ArrayList<>();
		for (Display.ItemDisplay display : MicrophoneDisplayHelper.findDisplays(level, pos)) {
			if (display.isAlive()) {
				blueprints.add(new ServerSelectionHighlightSystem.EntityGlowBlueprint(display));
			}
		}
		return blueprints;
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation, boolean notify) {
		super.neighborChanged(state, level, pos, block, orientation, notify);
		if (level instanceof ServerLevel serverLevel) {
			MicrophoneSystem.onMicrophoneStateChanged(serverLevel, pos);
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		MicrophoneDisplayHelper.remove(level, pos);
		if (RocketLaunchEventSystem.isLaunchedMountedDevice(level, pos)) {
			// Keep the endpoint registered; RocketLaunchEventSystem supplies the
			// moving position while the launch is active.
			super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
			return;
		}
		BluetoothLinkSystem.removeBlockEndpoint(level, BluetoothLinkSystem.EndpointType.MICROPHONE, pos);
		PlacedDeviceNameStore.removeMicrophoneName(level, pos);
		MicrophoneSystem.untrackMicrophone(level, pos);
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
	}

	public static void applyFallbackName(ItemStack out, PacketContext context) {
		if (PolymerResourcePackUtils.hasMainPack(context)) {
			return;
		}
		String language = context.getPlayer() != null ? context.getPlayer().clientInformation().language() : "";
		String normalized = language == null ? "" : language.toLowerCase();
		String name = "Microphone";
		if (normalized.startsWith("rpr")) {
			name = "Микрофонъ";
		} else if (normalized.startsWith("ru")) {
			name = "Микрофон";
		} else if (normalized.startsWith("uk")) {
			name = "Мікрофон";
		} else if (normalized.startsWith("ja")) {
			name = "マイク";
		}
		out.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withItalic(false)));
	}
}
