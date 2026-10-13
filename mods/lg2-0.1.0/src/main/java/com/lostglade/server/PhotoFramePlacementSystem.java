package com.lostglade.server;

import com.lostglade.item.ModItems;
import com.lostglade.item.PhotoPrintData;
import com.lostglade.server.map.MapImageRenderSystem;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class PhotoFramePlacementSystem {
	private PhotoFramePlacementSystem() {
	}

	public static void register() {
		UseEntityCallback.EVENT.register(PhotoFramePlacementSystem::onUseEntity);
	}

	private static InteractionResult onUseEntity(net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level world, InteractionHand hand, Entity entity, net.minecraft.world.phys.EntityHitResult hitResult) {
		if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || !(entity instanceof ItemFrame itemFrame)) {
			return InteractionResult.PASS;
		}

		ItemStack heldStack = player.getItemInHand(hand);
		if (heldStack.is(ModItems.PHOTO_PRINT)) {
			return placePhoto(serverPlayer, hand, heldStack, itemFrame) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
		}

		if (player.isShiftKeyDown() && PhotoPrintData.readFrameTile(itemFrame.getItem()) != null) {
			return pickupPhoto(serverPlayer, itemFrame) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
		}

		return InteractionResult.PASS;
	}

	public static void onFrameBroken(ServerLevel level, ItemFrame frame, Entity breaker, boolean shouldDropPhoto) {
		if (level == null || frame == null) {
			return;
		}
		ItemStack frameStack = frame.getItem();
		PhotoPrintData.PlacedPhotoFrameData frameData = PhotoPrintData.readFrameTile(frameStack);
		if (frameData == null) {
			return;
		}

		ItemStack photoItem = recreatePhotoItem(frameStack, frameData);
		// A breaking frame may already have been removed from the level's entity
		// index. Clearing neighbours cannot clear that frame; vanilla would then
		// drop its map as well as the reconstructed photo. Consume it directly
		// before any callbacks or spawning. Vanilla can still drop the empty frame.
		frame.setItem(ItemStack.EMPTY, false);
		CameraAnimatedMapPlaybackSystem.unregisterPlacedVideo(frameData.groupId());
		clearPlacedPhoto(level, frameData);
		if (photoItem.isEmpty()) {
			return;
		}
		frame.spawnAtLocation(level, photoItem);
	}

	private static boolean placePhoto(ServerPlayer player, InteractionHand hand, ItemStack heldStack, ItemFrame anchorFrame) {
		PhotoPrintData data = PhotoPrintData.readPhotoItem(heldStack);
		if (data == null || !data.isValid()) {
			return false;
		}
		Direction facing = anchorFrame.getDirection();
		if (!facing.getAxis().isHorizontal()) {
			player.displayClientMessage(onlyWallFramesMessage(player), true);
			return false;
		}
		if (!anchorFrame.getItem().isEmpty()) {
			player.displayClientMessage(needsEmptyFramesMessage(player, data.mapsWide(), data.mapsHigh()), true);
			return false;
		}
		AnchorPlacement placement = resolvePlacement(anchorFrame, data.mapsWide(), data.mapsHigh());
		if (placement == null) {
			player.displayClientMessage(needsEmptyFramesMessage(player, data.mapsWide(), data.mapsHigh()), true);
			return false;
		}

		BlockPos anchorPos = placement.anchorPos();
		Direction right = frameRight(facing);
		UUID groupId = UUID.randomUUID();
		Component photoName = heldStack.get(DataComponents.CUSTOM_NAME);
		for (int tileY = 0; tileY < data.mapsHigh(); tileY++) {
			for (int tileX = 0; tileX < data.mapsWide(); tileX++) {
				BlockPos expectedPos = anchorPos.relative(right, tileX).relative(Direction.DOWN, tileY);
				ItemFrame frame = findFrame((ServerLevel) player.level(), expectedPos, facing, placement.frameClass(), true);
				if (frame == null) {
					player.displayClientMessage(needsEmptyFramesMessage(player, data.mapsWide(), data.mapsHigh()), true);
					return false;
				}
				int mapId = data.mapIdAt(tileX, tileY);
				if (mapId < 0) {
					return false;
				}
				ItemStack frameMap = new ItemStack(Items.FILLED_MAP);
				frameMap.set(DataComponents.MAP_ID, new MapId(mapId));
				PhotoPrintData.writeFrameTile(frameMap, data.placed(groupId, anchorPos, facing, tileX, tileY));
				PhotoPrintData.writeFrameStoredName(frameMap, photoName);
				frame.setRotation(0);
				frame.setItem(frameMap, false);
			}
		}

		if (!player.getAbilities().instabuild) {
			heldStack.shrink(1);
		}
		if (data.isVideo()) {
			CameraAnimatedMapPlaybackSystem.registerPlacedVideo((ServerLevel) player.level(), data.placed(groupId, anchorPos, facing, 0, 0));
		}
		ServerMechanicsGateSystem.syncPlayerInventory(player);
		return true;
	}

	private static boolean pickupPhoto(ServerPlayer player, ItemFrame frame) {
		if (!(player.level() instanceof ServerLevel level)) {
			return false;
		}
		ItemStack frameStack = frame.getItem();
		PhotoPrintData.PlacedPhotoFrameData frameData = PhotoPrintData.readFrameTile(frameStack);
		if (frameData == null) {
			return false;
		}
		ItemStack photoItem = recreatePhotoItem(frameStack, frameData);
		frame.setItem(ItemStack.EMPTY, false);
		CameraAnimatedMapPlaybackSystem.unregisterPlacedVideo(frameData.groupId());
		clearPlacedPhoto(level, frameData);
		giveOrDrop(player, photoItem);
		ServerMechanicsGateSystem.syncPlayerInventory(player);
		MapImageRenderSystem.sendPhotoPreviewMap(player, photoItem);
		return true;
	}

	private static ItemStack recreatePhotoItem(ItemStack frameStack, PhotoPrintData.PlacedPhotoFrameData frameData) {
		ItemStack photoItem = new ItemStack(ModItems.PHOTO_PRINT);
		PhotoPrintData.writePhotoItem(photoItem, frameData.asPhotoPrintData());
		Component customName = PhotoPrintData.readFrameStoredName(frameStack);
		if (customName == null) {
			customName = frameStack.get(DataComponents.CUSTOM_NAME);
		}
		if (customName != null) {
			photoItem.set(DataComponents.CUSTOM_NAME, customName.copy());
		}
		return photoItem;
	}

	private static void clearPlacedPhoto(ServerLevel level, PhotoPrintData.PlacedPhotoFrameData frameData) {
		if (level == null || frameData == null) {
			return;
		}
		Direction right = frameRight(frameData.direction());
		for (int tileY = 0; tileY < frameData.mapsHigh(); tileY++) {
			for (int tileX = 0; tileX < frameData.mapsWide(); tileX++) {
				BlockPos expectedPos = frameData.anchorPos().relative(right, tileX).relative(Direction.DOWN, tileY);
				ItemFrame frame = findPlacedFrame(level, expectedPos, frameData);
				if (frame == null) {
					continue;
				}
				frame.setItem(ItemStack.EMPTY, false);
			}
		}
	}

	private static AnchorPlacement resolvePlacement(ItemFrame clickedFrame, int mapsWide, int mapsHigh) {
		if (!(clickedFrame.level() instanceof ServerLevel level)) {
			return null;
		}
		Direction facing = clickedFrame.getDirection();
		Direction right = frameRight(facing);
		BlockPos clickedPos = clickedFrame.blockPosition();
		Class<?> frameClass = clickedFrame.getClass();
		for (int anchorOffsetY = 0; anchorOffsetY < mapsHigh; anchorOffsetY++) {
			for (int anchorOffsetX = 0; anchorOffsetX < mapsWide; anchorOffsetX++) {
				BlockPos anchorPos = clickedPos.relative(right.getOpposite(), anchorOffsetX).relative(Direction.UP, anchorOffsetY);
				if (hasFrameRectangle(level, anchorPos, facing, frameClass, mapsWide, mapsHigh)) {
					return new AnchorPlacement(anchorPos, frameClass);
				}
			}
		}
		return null;
	}

	private static boolean hasFrameRectangle(ServerLevel level, BlockPos anchorPos, Direction facing, Class<?> frameClass, int mapsWide, int mapsHigh) {
		Direction right = frameRight(facing);
		for (int tileY = 0; tileY < mapsHigh; tileY++) {
			for (int tileX = 0; tileX < mapsWide; tileX++) {
				BlockPos expectedPos = anchorPos.relative(right, tileX).relative(Direction.DOWN, tileY);
				if (findFrame(level, expectedPos, facing, frameClass, true) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static ItemFrame findPlacedFrame(ServerLevel level, BlockPos expectedPos, PhotoPrintData.PlacedPhotoFrameData frameData) {
		AABB searchBox = new AABB(expectedPos).inflate(0.6D);
		for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, searchBox, candidate -> candidate.blockPosition().equals(expectedPos))) {
			PhotoPrintData.PlacedPhotoFrameData candidateData = PhotoPrintData.readFrameTile(frame.getItem());
			if (candidateData == null) {
				continue;
			}
			if (candidateData.groupId().equals(frameData.groupId())
					&& candidateData.samePhoto(frameData.asPhotoPrintData())
					&& candidateData.anchorPos().equals(frameData.anchorPos())
					&& candidateData.direction() == frameData.direction()
					&& candidateData.mapsWide() == frameData.mapsWide()
					&& candidateData.mapsHigh() == frameData.mapsHigh()) {
				return frame;
			}
		}
		return null;
	}

	private static ItemFrame findFrame(ServerLevel level, BlockPos expectedPos, Direction facing, Class<?> frameClass, boolean mustBeEmpty) {
		AABB searchBox = new AABB(expectedPos).inflate(0.6D);
		for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, searchBox, candidate -> candidate.blockPosition().equals(expectedPos))) {
			if (frame.getDirection() != facing || frame.getClass() != frameClass) {
				continue;
			}
			if (mustBeEmpty && !frame.getItem().isEmpty()) {
				continue;
			}
			return frame;
		}
		return null;
	}

	private static Direction frameRight(Direction facing) {
		return switch (facing) {
			case NORTH -> Direction.WEST;
			case SOUTH -> Direction.EAST;
			case EAST -> Direction.NORTH;
			case WEST -> Direction.SOUTH;
			default -> Direction.EAST;
		};
	}

	private record AnchorPlacement(BlockPos anchorPos, Class<?> frameClass) {
	}

	private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty()) {
			return;
		}
		boolean inserted = player.getInventory().add(stack);
		if (!inserted) {
			ItemEntity itemEntity = player.drop(stack, false);
			if (itemEntity != null) {
				itemEntity.setPickUpDelay(0);
			}
		}
	}

	private static Component needsEmptyFramesMessage(ServerPlayer player, int mapsWide, int mapsHigh) {
		String size = mapsWide + "x" + mapsHigh;
		String locale = locale(player);
		if (locale.startsWith("rpr")) {
			return literal("Нужны пустыя рамки " + size);
		}
		if (locale.startsWith("uk")) {
			return literal("Потрібні порожні рамки " + size);
		}
		if (locale.startsWith("ja")) {
			return literal(size + " の空の額縁が必要です");
		}
		if (locale.startsWith("ru")) {
			return literal("Нужны пустые рамки " + size);
		}
		return literal("Need empty item frames " + size);
	}

	private static Component onlyWallFramesMessage(ServerPlayer player) {
		String locale = locale(player);
		if (locale.startsWith("rpr")) {
			return literal("Большiя фотокарточки пока токмо на стенах");
		}
		if (locale.startsWith("uk")) {
			return literal("Великі фото поки лише на стінах");
		}
		if (locale.startsWith("ja")) {
			return literal("大きな写真は今のところ壁の額縁のみです");
		}
		if (locale.startsWith("ru")) {
			return literal("Большие фото пока только на настенных рамках");
		}
		return literal("Large photos currently work only on wall item frames");
	}

	private static String locale(ServerPlayer player) {
		if (player == null || player.clientInformation() == null || player.clientInformation().language() == null) {
			return "en_us";
		}
		return player.clientInformation().language().toLowerCase(Locale.ROOT);
	}

	private static Component literal(String value) {
		return Component.literal(value).withStyle(style -> style.withItalic(false));
	}
}
