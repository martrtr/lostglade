package com.lostglade.server.map;

import com.lostglade.item.PhotoPrintData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.UUID;

public interface MapPixelProvider {
	record PreparedPixel(int x, int y, Object payload) {
	}

	UUID ownerId();

	ResourceKey<Level> dimension();

	default PreparedPixel preparePixel(MinecraftServer server, int x, int y) {
		return new PreparedPixel(x, y, Byte.valueOf(renderPixel(server, x, y)));
	}

	default byte renderPreparedPixel(PreparedPixel pixel) {
		Object payload = pixel.payload();
		if (payload instanceof Number number) {
			return number.byteValue();
		}
		return 0;
	}

	default byte renderPixel(MinecraftServer server, int x, int y) {
		return 0;
	}

	default boolean prefersWholeFrameRendering() {
		return false;
	}

	default Object prepareFrame(MinecraftServer server) {
		return null;
	}

	default byte[] renderPreparedFrame(Object preparedFrame) {
		return new byte[0];
	}

	default byte[] renderImmediatePreviewFromPreparedFrame(Object preparedFrame) {
		return null;
	}

	default byte[] renderImmediatePreview(MinecraftServer server) {
		return null;
	}

	/** Latest non-final 128×128 frame, if the provider can stream photo warm-up. */
	default byte[] pollProgressPreview() {
		return null;
	}

	default boolean immediatePreviewMatchesPrimaryFrame() {
		return false;
	}

	default int mapTilesWide() {
		return 1;
	}

	default int mapTilesHigh() {
		return 1;
	}

	default boolean isValid(MinecraftServer server) {
		return true;
	}

	default void onCompleted(MinecraftServer server) {
	}

	/** Called when the print is no longer owned by its photographer or a frame. */
	default void onCancelled(MinecraftServer server) {
	}

	default PhotoPrintData.MediaKind mediaKind() {
		return PhotoPrintData.MediaKind.PHOTO;
	}

	default String sourceKey() {
		return "";
	}

	default long mediaDurationMs() {
		return 0L;
	}

	default int mediaFps() {
		return 0;
	}

	default Component completedMessage() {
		return Component.literal("Изображение готово.");
	}
}
