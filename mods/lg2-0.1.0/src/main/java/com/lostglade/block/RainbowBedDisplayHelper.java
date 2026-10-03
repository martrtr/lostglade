package com.lostglade.block;

import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

public final class RainbowBedDisplayHelper {
	private static final String ROOT_TAG = "lg2_rainbow_bed_display";
	private static final String POS_TAG_PREFIX = "lg2_rainbow_bed_display_pos:";
	private static final String BROWN_ROOT_TAG = "lg2_brown_bed_display";
	private static final String BROWN_POS_TAG_PREFIX = "lg2_brown_bed_display_pos:";
	private static final double SEARCH_RADIUS = 2.25D;
	private static final Transformation DISPLAY_TRANSFORMATION = new Transformation(
			new Vector3f(0.0F, 0.0F, 0.0F),
			new Quaternionf(),
			new Vector3f(1.0F, 1.0F, 1.0F),
			new Quaternionf()
	);

	private RainbowBedDisplayHelper() {
	}

	public static void spawnOrUpdate(ServerLevel level, BlockPos pos, BlockState state) {
		spawnOrUpdate(level, pos, state, false);
	}

	public static void spawnOrUpdateBrown(ServerLevel level, BlockPos pos, BlockState state) {
		spawnOrUpdate(level, pos, state, true);
	}

	private static void spawnOrUpdate(ServerLevel level, BlockPos pos, BlockState state, boolean brown) {
		BlockPos footPos = footPos(pos, state, brown);
		BlockState footState = level.getBlockState(footPos);
		if (!isTargetBed(footState, brown) || footState.getValue(BedBlock.PART) != BedPart.FOOT) {
			return;
		}

		Direction facing = footState.getValue(BedBlock.FACING);
		BlockPos headPos = footPos.relative(facing);
		BlockState headState = level.getBlockState(headPos);
		if (!isTargetBed(headState, brown) || headState.getValue(BedBlock.PART) != BedPart.HEAD) {
			return;
		}

		List<Display.ItemDisplay> displays = findDisplays(level, footPos, brown);
		Display.ItemDisplay display = displays.isEmpty() ? null : displays.get(0);

		for (int i = 1; i < displays.size(); i++) {
			displays.get(i).discard();
		}

		if (display == null) {
			display = createDisplay(level, footPos, brown);
		}
		configureDisplay(level, display, footPos, headPos, facing, brown);
	}

	public static void ensureDisplay(ServerLevel level, BlockPos pos, BlockState state) {
		BlockPos footPos = footPos(pos, state, false);
		spawnOrUpdate(level, footPos, level.getBlockState(footPos));
	}

	public static void ensureBrownDisplay(ServerLevel level, BlockPos pos, BlockState state) {
		BlockPos footPos = footPos(pos, state, true);
		spawnOrUpdateBrown(level, footPos, level.getBlockState(footPos));
	}

	public static void remove(ServerLevel level, BlockPos pos, BlockState state) {
		remove(level, footPos(pos, state, false), false);
	}

	public static void removeBrown(ServerLevel level, BlockPos pos, BlockState state) {
		remove(level, footPos(pos, state, true), true);
	}

	public static void cleanupOrphanDisplays(ServerLevel level) {
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof Display.ItemDisplay display) {
				cleanupOrphanDisplay(level, display, false);
				cleanupOrphanDisplay(level, display, true);
			}
		}
	}

	private static void remove(ServerLevel level, BlockPos footPos, boolean brown) {
		for (Display.ItemDisplay display : findDisplays(level, footPos, brown)) {
			display.discard();
		}
	}

	private static Display.ItemDisplay createDisplay(ServerLevel level, BlockPos footPos, boolean brown) {
		Display.ItemDisplay display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		display.addTag(rootTag(brown));
		display.addTag(getPosTag(footPos, brown));
		level.addFreshEntity(display);
		return display;
	}

	private static void configureDisplay(ServerLevel level, Display.ItemDisplay display, BlockPos footPos, BlockPos headPos, Direction facing, boolean brown) {
		Vec3 center = Vec3.atCenterOf(footPos).add(Vec3.atCenterOf(headPos)).scale(0.5D);
		display.setPos(center.x, center.y, center.z);
		// Special bed item models face opposite to the placed bed block direction,
		// so the world display needs a 180-degree correction to keep the head away from the player.
		float yRot = facing.getOpposite().toYRot();
		display.setYRot(yRot);
		display.setXRot(0.0F);
		display.setYHeadRot(yRot);
		display.setYBodyRot(yRot);
		display.setItemStack(createDisplayStack(brown));
		display.setItemTransform(ItemDisplayContext.FIXED);
		display.setBillboardConstraints(Display.BillboardConstraints.FIXED);
		display.setTransformation(DISPLAY_TRANSFORMATION);
		display.setNoGravity(true);
		display.setInvulnerable(true);
		display.setSilent(true);
		display.setShadowRadius(0.0F);
		display.setShadowStrength(0.0F);
		display.setViewRange(1.0F);
	}

	private static List<Display.ItemDisplay> findDisplays(ServerLevel level, BlockPos footPos, boolean brown) {
		String rootTag = rootTag(brown);
		String posTag = getPosTag(footPos, brown);
		AABB box = new AABB(footPos).inflate(SEARCH_RADIUS);
		return level.getEntities(
				EntityType.ITEM_DISPLAY,
				box,
				display -> display.getTags().contains(rootTag) && display.getTags().contains(posTag)
		);
	}

	private static void cleanupOrphanDisplay(ServerLevel level, Display.ItemDisplay display, boolean brown) {
		if (!display.getTags().contains(rootTag(brown))) {
			return;
		}
		BlockPos footPos = readTaggedFootPos(display, brown);
		if (footPos == null) {
			display.discard();
			return;
		}
		BlockState footState = getLoadedBlockState(level, footPos);
		if (footState == null || !isTargetBed(footState, brown) || footState.getValue(BedBlock.PART) != BedPart.FOOT) {
			display.discard();
			return;
		}
		BlockPos headPos = footPos.relative(footState.getValue(BedBlock.FACING));
		BlockState headState = getLoadedBlockState(level, headPos);
		if (headState != null && isTargetBed(headState, brown) && headState.getValue(BedBlock.PART) == BedPart.HEAD) {
			return;
		}
		display.discard();
	}

	private static BlockState getLoadedBlockState(ServerLevel level, BlockPos pos) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(
				SectionPos.blockToSectionCoord(pos.getX()),
				SectionPos.blockToSectionCoord(pos.getZ())
		);
		return chunk == null ? null : chunk.getBlockState(pos);
	}

	private static BlockPos readTaggedFootPos(Display.ItemDisplay display, boolean brown) {
		String prefix = brown ? BROWN_POS_TAG_PREFIX : POS_TAG_PREFIX;
		for (String tag : display.getTags()) {
			if (tag.startsWith(prefix)) {
				return parsePos(tag.substring(prefix.length()));
			}
		}
		return null;
	}

	private static BlockPos parsePos(String value) {
		String[] parts = value.split(",", 3);
		if (parts.length != 3) {
			return null;
		}
		try {
			return new BlockPos(
					Integer.parseInt(parts[0]),
					Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2])
			);
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static BlockPos footPos(BlockPos pos, BlockState state, boolean brown) {
		if (!isTargetBed(state, brown)) {
			return pos;
		}
		if (state.getValue(BedBlock.PART) == BedPart.FOOT) {
			return pos;
		}
		return pos.relative(state.getValue(BedBlock.FACING).getOpposite());
	}

	private static boolean isTargetBed(BlockState state, boolean brown) {
		return brown ? state.is(Blocks.BROWN_BED) : state.getBlock() instanceof RainbowBedBlock;
	}

	private static ItemStack createDisplayStack(boolean brown) {
		return brown ? RainbowBedItem.createBrownDisplayStack() : RainbowBedItem.createDisplayStack();
	}

	private static String rootTag(boolean brown) {
		return brown ? BROWN_ROOT_TAG : ROOT_TAG;
	}

	private static String getPosTag(BlockPos pos, boolean brown) {
		String prefix = brown ? BROWN_POS_TAG_PREFIX : POS_TAG_PREFIX;
		return prefix + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}
}
