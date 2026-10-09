package com.lostglade.block;

import com.lostglade.item.CameraItem;
import com.lostglade.util.ItemDisplayHitboxHelper;
import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/** Visible, non-interactive representation of a placed camera block. */
final class CameraDisplayHelper {
	private static final String ROOT_TAG = "lg2_camera_display";
	private static final String POS_TAG_PREFIX = "lg2_camera_display_pos:";
	private static final double SEARCH_RADIUS = 0.9D;
	private static final double PISTON_SEARCH_RADIUS = 1.5D;
	private static final double MODEL_FORWARD_OFFSET = 5.0D / 16.0D;
	/*
	 * The model spans x=2..15, y=8..15, z=5..13.  Its fixed transform rotates
	 * it around Y by 180 degrees, so this local translation moves the model's
	 * geometric centre onto the ItemDisplay origin.  That origin is the centre
	 * of the PLAYER_HEAD collision box at (0.5, 0.25, 0.5).
	 */
	private static final Transformation CENTERED_CAMERA_TRANSFORMATION = new Transformation(
			new Vector3f(1.0F / 32.0F, -7.0F / 32.0F, 1.0F / 16.0F),
			new Quaternionf(),
			new Vector3f(1.0F, 1.0F, 1.0F),
			new Quaternionf()
	);

	private CameraDisplayHelper() {
	}

	static void spawnOrUpdate(ServerLevel level, BlockPos pos, float yaw, float pitch) {
		List<Display.ItemDisplay> displays = findDisplays(level, pos);
		Display.ItemDisplay display;
		boolean created = displays.isEmpty();
		if (created) {
			display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
			display.addTag(ROOT_TAG);
			display.addTag(getPosTag(pos));
		} else {
			display = displays.get(0);
			for (int i = 1; i < displays.size(); i++) {
				displays.get(i).discard();
			}
		}

		configureDisplay(display, pos, yaw, pitch);
		if (created) {
			level.addFreshEntity(display);
		}
	}

	static void remove(ServerLevel level, BlockPos pos) {
		for (Display.ItemDisplay display : findDisplays(level, pos)) {
			display.discard();
		}
	}

	/** Keeps the separate model aligned with a vanilla moving-piston block. */
	static void moveForPiston(ServerLevel level, BlockPos source, BlockPos destination, java.util.UUID displayUuid, Vec3 captureBaseOrigin, float yaw, float pitch) {
		if (level == null || source == null || destination == null || captureBaseOrigin == null) {
			return;
		}
		for (Display.ItemDisplay display : findPistonDisplays(level, source, destination, displayUuid)) {
			display.setPosRotInterpolationDuration(1);
			configureDisplay(display, captureBaseOrigin, yaw, pitch);
		}
	}

	/** Reuses the same model entity at the destination, retaining its UUID. */
	static void finishPistonMove(ServerLevel level, BlockPos source, BlockPos destination, java.util.UUID displayUuid, float yaw, float pitch) {
		if (level == null || source == null || destination == null) {
			return;
		}
		List<Display.ItemDisplay> sourceDisplays = findPistonDisplays(level, source, destination, displayUuid);
		List<Display.ItemDisplay> destinationDisplays = findDisplays(level, destination);
		Display.ItemDisplay display = sourceDisplays.isEmpty() ? null : sourceDisplays.get(0);
		for (int index = 1; index < sourceDisplays.size(); index++) {
			sourceDisplays.get(index).discard();
		}
		for (Display.ItemDisplay destinationDisplay : destinationDisplays) {
			if (destinationDisplay != display) {
				destinationDisplay.discard();
			}
		}
		if (display == null) {
			spawnOrUpdate(level, destination, yaw, pitch);
			return;
		}
		display.removeTag(getPosTag(source));
		display.addTag(ROOT_TAG);
		display.addTag(getPosTag(destination));
		// The model has already received the in-flight positions. End the
		// interpolation at the exact final hitbox location so a dropped tracking
		// packet cannot leave it hanging at the half-way pose.
		display.setPosRotInterpolationDuration(0);
		configureDisplay(display, destination, yaw, pitch);
	}

	private static void configureDisplay(Display.ItemDisplay display, BlockPos pos, float yaw, float pitch) {
		configureDisplay(display, CameraBlock.captureBaseOrigin(pos), yaw, pitch);
	}

	private static void configureDisplay(Display.ItemDisplay display, Vec3 captureBaseOrigin, float yaw, float pitch) {
		Vec3 origin = captureBaseOrigin;
		Vec3 forward = CameraBlock.captureOrigin(origin, yaw, pitch).subtract(origin).normalize();
		Vec3 displayOrigin = origin.add(forward.scale(MODEL_FORWARD_OFFSET));
		// The fixed item transform uses the model's local forward axis.  It is
		// opposite to the camera view vector, so turn only the display around.
		float displayYaw = yaw + 180.0F;
		display.setPos(displayOrigin.x, displayOrigin.y, displayOrigin.z);
		display.setYRot(displayYaw);
		display.setXRot(-pitch);
		display.setYHeadRot(displayYaw);
		display.setYBodyRot(displayYaw);
		display.setItemStack(CameraItem.createDisplayStack());
		display.setItemTransform(ItemDisplayContext.FIXED);
		display.setBillboardConstraints(Display.BillboardConstraints.FIXED);
		display.setTransformation(CENTERED_CAMERA_TRANSFORMATION);
		display.setNoGravity(true);
		display.setInvulnerable(true);
		display.setSilent(true);
		display.setShadowRadius(0.0F);
		display.setShadowStrength(0.0F);
		display.setViewRange(1.0F);
		ItemDisplayHitboxHelper.clear(display);
	}

	static List<Display.ItemDisplay> findDisplays(ServerLevel level, BlockPos pos) {
		String posTag = getPosTag(pos);
		return level.getEntities(
				EntityType.ITEM_DISPLAY,
				new AABB(pos).inflate(SEARCH_RADIUS),
				display -> display.getTags().contains(ROOT_TAG) && display.getTags().contains(posTag)
		);
	}

	private static List<Display.ItemDisplay> findPistonDisplays(ServerLevel level, BlockPos source, BlockPos destination, java.util.UUID displayUuid) {
		if (displayUuid != null) {
			Entity entity = level.getEntity(displayUuid);
			if (entity instanceof Display.ItemDisplay display
					&& display.getTags().contains(ROOT_TAG)
					&& display.getTags().contains(getPosTag(source))) {
				return List.of(display);
			}
		}
		String sourceTag = getPosTag(source);
		return level.getEntities(
				EntityType.ITEM_DISPLAY,
				AABB.encapsulatingFullBlocks(source, destination).inflate(PISTON_SEARCH_RADIUS),
				display -> display.getTags().contains(ROOT_TAG) && display.getTags().contains(sourceTag)
		);
	}

	private static String getPosTag(BlockPos pos) {
		return POS_TAG_PREFIX + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}
}
