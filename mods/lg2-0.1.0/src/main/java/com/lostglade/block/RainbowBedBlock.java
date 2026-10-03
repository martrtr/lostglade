package com.lostglade.block;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import xyz.nucleoid.packettweaker.PacketContext;

public final class RainbowBedBlock extends BedBlock implements PolymerBlock {
	public static final MapCodec<BedBlock> CODEC = simpleCodec(RainbowBedBlock::new);

	public RainbowBedBlock(BlockBehaviour.Properties settings) {
		super(DyeColor.BROWN, settings);
	}

	@Override
	public MapCodec<BedBlock> codec() {
		return CODEC;
	}

	@Override
	public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
		if (PolymerResourcePackUtils.hasMainPack(context)) {
			return getPackBedState(state);
		}
		return getFallbackBedState(state);
	}

	@Override
	public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
		return getFallbackBedState(state);
	}

	private static BlockState getPackBedState(BlockState state) {
		return copyBedState(Blocks.BROWN_BED.defaultBlockState(), state);
	}

	private static BlockState getFallbackBedState(BlockState state) {
		return copyBedState(Blocks.WHITE_BED.defaultBlockState(), state);
	}

	private static BlockState copyBedState(BlockState baseState, BlockState state) {
		return baseState
				.setValue(FACING, state.getValue(FACING))
				.setValue(PART, state.getValue(PART))
				.setValue(OCCUPIED, state.getValue(OCCUPIED));
	}

	@Override
	protected void spawnDestroyParticles(Level level, Player player, BlockPos pos, BlockState state) {
		level.levelEvent(player, 2001, pos, Block.getId(getFallbackBedState(state)));
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
			RainbowBedDisplayHelper.spawnOrUpdate(serverLevel, pos, state);
		}
	}

	@Override
	protected BlockState updateShape(
			BlockState state,
			LevelReader level,
			ScheduledTickAccess scheduledTickAccess,
			BlockPos pos,
			Direction direction,
			BlockPos neighborPos,
			BlockState neighborState,
			RandomSource random
	) {
		BlockState updated = super.updateShape(state, level, scheduledTickAccess, pos, direction, neighborPos, neighborState, random);
		if (!(updated.getBlock() instanceof RainbowBedBlock)) {
			removeDisplay(level, pos, state);
		}
		return updated;
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		removeDisplay(level, pos, state);
		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	public void destroy(net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockState state) {
		removeDisplay(level, pos, state);
		super.destroy(level, pos, state);
	}

	private static void removeDisplay(LevelReader level, BlockPos pos, BlockState state) {
		if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
			RainbowBedDisplayHelper.remove(serverLevel, pos, state);
		}
	}

	private static void removeDisplay(net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockState state) {
		if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
			RainbowBedDisplayHelper.remove(serverLevel, pos, state);
		}
	}
}
