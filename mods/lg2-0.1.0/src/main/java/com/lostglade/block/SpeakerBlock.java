package com.lostglade.block;

import com.lostglade.server.SpeakerSystem;
import com.lostglade.server.BluetoothLinkSystem;
import com.lostglade.server.Lg2Messages;
import com.lostglade.server.ServerMechanicsGateSystem;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.SimplePolymerBlock;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;

public final class SpeakerBlock extends SimplePolymerBlock implements PolymerTexturedBlock {
	public static final IntegerProperty VOLUME = IntegerProperty.create("volume", 0, 100);
	private static final int CLICK_VOLUME_STEP = 5;
	private static final int SCROLL_VOLUME_STEP = 1;
	private static final int DEFAULT_VOLUME = 50;
	private static final Identifier MODEL_NORTH_ID = Identifier.fromNamespaceAndPath("lg2", "block/speaker");
	private static final Identifier MODEL_NORTH_ON_ID = Identifier.fromNamespaceAndPath("lg2", "block/speaker_on");
	private static final Identifier MODEL_EAST_ID = Identifier.fromNamespaceAndPath("lg2", "block/speaker_east");
	private static final Identifier MODEL_EAST_ON_ID = Identifier.fromNamespaceAndPath("lg2", "block/speaker_east_on");
	private static final Identifier MODEL_SOUTH_ID = Identifier.fromNamespaceAndPath("lg2", "block/speaker_south");
	private static final Identifier MODEL_SOUTH_ON_ID = Identifier.fromNamespaceAndPath("lg2", "block/speaker_south_on");
	private static final Identifier MODEL_WEST_ID = Identifier.fromNamespaceAndPath("lg2", "block/speaker_west");
	private static final Identifier MODEL_WEST_ON_ID = Identifier.fromNamespaceAndPath("lg2", "block/speaker_west_on");
	private static final Identifier MODEL_UP_ID = Identifier.fromNamespaceAndPath("lg2", "block/speaker_up");
	private static final Identifier MODEL_UP_ON_ID = Identifier.fromNamespaceAndPath("lg2", "block/speaker_up_on");
	private static final Identifier MODEL_DOWN_ID = Identifier.fromNamespaceAndPath("lg2", "block/speaker_down");
	private static final Identifier MODEL_DOWN_ON_ID = Identifier.fromNamespaceAndPath("lg2", "block/speaker_down_on");
	private final BlockState polymerNorthState;
	private final BlockState polymerNorthOnState;
	private final BlockState polymerEastState;
	private final BlockState polymerEastOnState;
	private final BlockState polymerSouthState;
	private final BlockState polymerSouthOnState;
	private final BlockState polymerWestState;
	private final BlockState polymerWestOnState;
	private final BlockState polymerUpState;
	private final BlockState polymerUpOnState;
	private final BlockState polymerDownState;
	private final BlockState polymerDownOnState;
	private final BlockState fallbackState;

	public SpeakerBlock(BlockBehaviour.Properties properties) {
		super(properties, Blocks.NOTE_BLOCK);
		this.polymerNorthState = BackroomsBlock.requestTargetState(MODEL_NORTH_ID, Blocks.NOTE_BLOCK);
		this.polymerNorthOnState = BackroomsBlock.requestTargetState(MODEL_NORTH_ON_ID, Blocks.NOTE_BLOCK);
		this.polymerEastState = BackroomsBlock.requestTargetState(MODEL_EAST_ID, Blocks.NOTE_BLOCK);
		this.polymerEastOnState = BackroomsBlock.requestTargetState(MODEL_EAST_ON_ID, Blocks.NOTE_BLOCK);
		this.polymerSouthState = BackroomsBlock.requestTargetState(MODEL_SOUTH_ID, Blocks.NOTE_BLOCK);
		this.polymerSouthOnState = BackroomsBlock.requestTargetState(MODEL_SOUTH_ON_ID, Blocks.NOTE_BLOCK);
		this.polymerWestState = BackroomsBlock.requestTargetState(MODEL_WEST_ID, Blocks.NOTE_BLOCK);
		this.polymerWestOnState = BackroomsBlock.requestTargetState(MODEL_WEST_ON_ID, Blocks.NOTE_BLOCK);
		this.polymerUpState = BackroomsBlock.requestTargetState(MODEL_UP_ID, Blocks.NOTE_BLOCK);
		this.polymerUpOnState = BackroomsBlock.requestTargetState(MODEL_UP_ON_ID, Blocks.NOTE_BLOCK);
		this.polymerDownState = BackroomsBlock.requestTargetState(MODEL_DOWN_ID, Blocks.NOTE_BLOCK);
		this.polymerDownOnState = BackroomsBlock.requestTargetState(MODEL_DOWN_ON_ID, Blocks.NOTE_BLOCK);
		this.fallbackState = Blocks.NOTE_BLOCK.defaultBlockState();
		this.registerDefaultState(this.stateDefinition.any()
				.setValue(BlockStateProperties.LIT, false)
				.setValue(BlockStateProperties.FACING, Direction.NORTH)
				.setValue(VOLUME, DEFAULT_VOLUME));
	}

	@Override
	public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
		if (!PolymerResourcePackUtils.hasMainPack(context)) {
			return this.fallbackState;
		}
		Direction facing = state.hasProperty(BlockStateProperties.FACING)
				? state.getValue(BlockStateProperties.FACING)
				: Direction.NORTH;
		boolean lit = state.getValue(BlockStateProperties.LIT);
		return switch (facing) {
			case EAST -> lit ? this.polymerEastOnState : this.polymerEastState;
			case SOUTH -> lit ? this.polymerSouthOnState : this.polymerSouthState;
			case WEST -> lit ? this.polymerWestOnState : this.polymerWestState;
			case UP -> lit ? this.polymerUpOnState : this.polymerUpState;
			case DOWN -> lit ? this.polymerDownOnState : this.polymerDownState;
			default -> lit ? this.polymerNorthOnState : this.polymerNorthState;
		};
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(BlockStateProperties.LIT, BlockStateProperties.FACING, VOLUME);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Direction facing = context.getNearestLookingDirection().getOpposite();
		return this.defaultBlockState().setValue(BlockStateProperties.FACING, facing);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(BlockStateProperties.FACING, rotation.rotate(state.getValue(BlockStateProperties.FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(BlockStateProperties.FACING)));
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
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		SpeakerSystem.trackSpeaker(serverLevel, pos);
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation, boolean notify) {
		super.neighborChanged(state, level, pos, block, orientation, notify);
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		SpeakerSystem.onSpeakerStateChanged(serverLevel, pos);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		BluetoothLinkSystem.removeBlockEndpoint(level, BluetoothLinkSystem.EndpointType.SPEAKER, pos);
		SpeakerSystem.untrackSpeaker(level, pos);
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		return adjustVolume(state, level, pos, player, hitResult);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
		return adjustVolume(state, level, pos, player, hitResult);
	}

	private InteractionResult adjustVolume(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}

		double localY = hitResult.getLocation().y - pos.getY();
		int direction = localY >= 0.5D ? 1 : -1;
		applyVolumeDelta(serverLevel, pos, state, serverPlayer, direction, CLICK_VOLUME_STEP);
		return InteractionResult.CONSUME;
	}

	public static boolean adjustVolumeByScroll(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player, int direction) {
		return applyVolumeDelta(level, pos, state, player, Integer.signum(direction), SCROLL_VOLUME_STEP);
	}

	private static boolean applyVolumeDelta(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player, int direction, int step) {
		if (level == null || pos == null || state == null || player == null || direction == 0) {
			return false;
		}
		int currentVolume = readVolumePercent(state);
		int nextVolume = Math.max(0, Math.min(100, currentVolume + direction * step));
		if (nextVolume == currentVolume) {
			Lg2Messages.actionBar(player, "message.lg2.speaker.volume", currentVolume);
			return false;
		}

		level.setBlock(pos, state.setValue(VOLUME, nextVolume), 3);
		SpeakerSystem.onSpeakerStateChanged(level, pos);
		level.playSound(
				null,
				pos,
				SoundEvents.UI_BUTTON_CLICK.value(),
				SoundSource.BLOCKS,
				0.35F,
				0.75F + 0.25F * (nextVolume / 100.0F)
		);
		Lg2Messages.actionBar(player, "message.lg2.speaker.volume", nextVolume);
		return true;
	}

	public static int readVolumePercent(BlockState state) {
		return state != null && state.hasProperty(VOLUME) ? state.getValue(VOLUME) : DEFAULT_VOLUME;
	}

	public static boolean isLit(BlockState state) {
		return state != null && state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
	}

	public static void applyFallbackName(ItemStack out, PacketContext context) {
		if (PolymerResourcePackUtils.hasMainPack(context)) {
			return;
		}
		String language = context.getPlayer() != null ? context.getPlayer().clientInformation().language() : "";
		String normalized = language == null ? "" : language.toLowerCase();
		String name = "Speaker";
		if (normalized.startsWith("rpr")) {
			name = "Гласникъ";
		} else if (normalized.startsWith("ru")) {
			name = "Динамик";
		} else if (normalized.startsWith("uk")) {
			name = "Динамік";
		} else if (normalized.startsWith("ja")) {
			name = "スピーカー";
		}
		out.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(style -> style.withItalic(false)));
	}
}
