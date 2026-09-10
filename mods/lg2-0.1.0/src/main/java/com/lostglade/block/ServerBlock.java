package com.lostglade.block;

import com.lostglade.util.ItemDisplayHitboxHelper;
import com.lostglade.server.SeasonStartSystem;
import com.lostglade.server.ServerStructureBreakSystem;
import com.lostglade.server.ServerStabilitySystem;
import com.lostglade.server.ServerUpgradeUiSystem;
import eu.pb4.polymer.core.api.block.SimplePolymerBlock;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.List;

public class ServerBlock extends SimplePolymerBlock {
	private static final SimpleParticleType STRUCTURE_PARTICLE = resolveStructureParticle();
	private static final int STRUCTURE_PLACE_FLAGS = 2 | 16 | 32;

	public ServerBlock(BlockBehaviour.Properties settings) {
		super(settings, Blocks.COMMAND_BLOCK);
	}

	@Override
	public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
		return PolymerResourcePackUtils.hasMainPack(context)
				? Blocks.BARRIER.defaultBlockState()
				: Blocks.COMMAND_BLOCK.defaultBlockState();
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		return this.openServerMenu(level, player);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
		return this.openServerMenu(level, player);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		if (!ServerStructureBreakSystem.isInternalStructureRemoval(level, pos)) {
			ServerStructureBreakSystem.onStructureBlockRemoved(level, pos);
		}
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}

		Direction forward = resolveHorizontalFacing(placer);
		placeServerStructure(serverLevel, pos, forward);
	}

	public static void placeServerStructure(ServerLevel level, BlockPos pos, Direction forward) {
		if (level == null || pos == null) {
			return;
		}
		ServerStructureBreakSystem.pruneStructureDisplays(level);
		Direction safeForward = forward != null && forward.getAxis().isHorizontal() ? forward : Direction.NORTH;
		Direction.Axis axis = safeForward.getAxis();
		List<BlockPos> structurePositions = ServerStructureBreakSystem.getStructurePositions(pos, axis);

		for (BlockPos targetPos : structurePositions) {
			level.setBlock(targetPos, ModBlocks.SERVER.defaultBlockState(), STRUCTURE_PLACE_FLAGS);
		}

		ServerStabilitySystem.onServerStructurePlaced(level, pos);
		SeasonStartSystem.onServerStructurePlaced(level, pos, axis);
		spawnServerDisplay(level, pos, safeForward, axis);
		level.playSound(null, pos, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.BLOCKS, 1.0F, 1.0F);
		spawnStructureParticles(level, structurePositions);
	}

	public static void ensureServerStructureDisplay(ServerLevel level, BlockPos pos, Direction.Axis axis) {
		if (level == null || pos == null || axis == null) {
			return;
		}
		ServerStructureBreakSystem.pruneStructureDisplays(level);
		List<BlockPos> structurePositions = ServerStructureBreakSystem.getStructurePositions(pos, axis);
		for (BlockPos structurePos : structurePositions) {
			if (!level.getBlockState(structurePos).is(ModBlocks.SERVER)) {
				return;
			}
		}
		Direction forward = axis == Direction.Axis.X ? Direction.EAST : Direction.NORTH;
		spawnServerDisplay(level, pos, forward, axis);
	}

	private InteractionResult openServerMenu(Level level, Player player) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (!SeasonStartSystem.isServerMenuAvailable(serverPlayer)) {
			SeasonStartSystem.onServerMenuRequestedTooEarly(serverPlayer);
			return InteractionResult.CONSUME;
		}
		ServerUpgradeUiSystem.openRootScreen(serverPlayer);

		return InteractionResult.CONSUME;
	}

	private static void spawnServerDisplay(ServerLevel level, BlockPos origin, Direction forward, Direction.Axis axis) {
		Display.ItemDisplay display = ServerStructureBreakSystem.resolveSingleStructureDisplay(level, origin, axis);
		boolean created = false;
		if (display == null) {
			display = EntityType.ITEM_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
			created = true;
		}
		if (display == null) {
			return;
		}
		Direction displayForward = created
				? forward
				: ServerStructureBreakSystem.resolveStructureDisplayFacing(display, forward);

		display.setPos(origin.getX() + 0.5D, origin.getY() + 1.5D, origin.getZ() + 0.5D);
		display.setYRot(Mth.wrapDegrees(displayForward.toYRot()));
		display.setXRot(0.0F);
		display.getSlot(0).set(new ItemStack(ModBlocks.SERVER_ITEM));
		if (created) {
			ServerStructureBreakSystem.applyStructureDisplayTags(display, origin, axis);
		}
		ServerStructureBreakSystem.setStructureDisplayFacing(display, displayForward);
		applyFixedItemDisplayTransform(display, level);
		ItemDisplayHitboxHelper.clear(display);
		if (created) {
			level.addFreshEntity(display);
		}
	}

	private static void spawnStructureParticles(ServerLevel level, List<BlockPos> structurePositions) {
		if (structurePositions.isEmpty()) {
			return;
		}

		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (BlockPos pos : structurePositions) {
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX());
			maxY = Math.max(maxY, pos.getY());
			maxZ = Math.max(maxZ, pos.getZ());
		}

		double x0 = minX + 0.1D;
		double y0 = minY + 0.1D;
		double z0 = minZ + 0.1D;
		double x1 = maxX + 0.9D;
		double y1 = maxY + 0.9D;
		double z1 = maxZ + 0.9D;
		double step = 0.45D;

		emitPlaneXY(level, x0, x1, y0, y1, z0, step);
		emitPlaneXY(level, x0, x1, y0, y1, z1, step);
		emitPlaneXZ(level, x0, x1, z0, z1, y0, step);
		emitPlaneXZ(level, x0, x1, z0, z1, y1, step);
		emitPlaneYZ(level, y0, y1, z0, z1, x0, step);
		emitPlaneYZ(level, y0, y1, z0, z1, x1, step);
	}

	private static void emitPlaneXY(ServerLevel level, double minX, double maxX, double minY, double maxY, double z, double step) {
		for (double x = minX; x <= maxX + 1.0E-6D; x += step) {
			for (double y = minY; y <= maxY + 1.0E-6D; y += step) {
				emitParticle(level, x, y, z);
			}
		}
	}

	private static void emitPlaneXZ(ServerLevel level, double minX, double maxX, double minZ, double maxZ, double y, double step) {
		for (double x = minX; x <= maxX + 1.0E-6D; x += step) {
			for (double z = minZ; z <= maxZ + 1.0E-6D; z += step) {
				emitParticle(level, x, y, z);
			}
		}
	}

	private static void emitPlaneYZ(ServerLevel level, double minY, double maxY, double minZ, double maxZ, double x, double step) {
		for (double y = minY; y <= maxY + 1.0E-6D; y += step) {
			for (double z = minZ; z <= maxZ + 1.0E-6D; z += step) {
				emitParticle(level, x, y, z);
			}
		}
	}

	private static void emitParticle(ServerLevel level, double x, double y, double z) {
		level.sendParticles(
				STRUCTURE_PARTICLE,
				x,
				y,
				z,
				1,
				0.0D,
				0.0D,
				0.0D,
				0.0D
		);
	}

	private static Direction resolveHorizontalFacing(LivingEntity placer) {
		if (placer == null) {
			return Direction.NORTH;
		}

		Direction direction = placer.getDirection();
		if (direction.getAxis().isHorizontal()) {
			return direction;
		}
		return Direction.NORTH;
	}

	private static SimpleParticleType resolveStructureParticle() {
		ParticleType<?> byId = BuiltInRegistries.PARTICLE_TYPE.getValue(
				Identifier.fromNamespaceAndPath("minecraft", "trial_spawner_detection")
		);
		if (byId instanceof SimpleParticleType simpleParticleType) {
			return simpleParticleType;
		}
		return ParticleTypes.TRIAL_SPAWNER_DETECTED_PLAYER;
	}

	private static void applyFixedItemDisplayTransform(Display.ItemDisplay display, ServerLevel level) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		if (!display.save(output)) {
			return;
		}

		CompoundTag tag = output.buildResult();
		tag.putString("item_display", "fixed");
		display.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
	}
}
