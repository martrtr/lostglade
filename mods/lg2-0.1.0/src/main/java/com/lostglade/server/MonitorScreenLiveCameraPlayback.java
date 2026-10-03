package com.lostglade.server;

import static com.lostglade.server.MonitorScreenMessages.*;
import static com.lostglade.server.MonitorScreenBackgroundLoader.*;
import static com.lostglade.server.MonitorScreenGalleryRuntime.*;
import static com.lostglade.server.MonitorScreenLiveSources.*;
import static com.lostglade.server.MonitorScreenMediaActions.*;
import static com.lostglade.server.MonitorScreenSystem.*;
import static com.lostglade.server.MonitorScreenMediaHydration.*;
import static com.lostglade.server.MonitorScreenMediaLoadResults.*;
import static com.lostglade.server.MonitorScreenMediaSessionLifecycle.*;
import static com.lostglade.server.MonitorScreenPlaybackScheduler.*;
import static com.lostglade.server.MonitorScreenTickScheduler.*;
import static com.lostglade.server.MonitorScreenYoutubeQueueRuntime.*;

import com.lostglade.Lg2;
import com.lostglade.block.CameraBlock;
import com.lostglade.block.ModBlocks;
import com.lostglade.config.Lg2Config;
import com.lostglade.item.ModItems;
import com.lostglade.item.MonitorItem;
import com.lostglade.item.PhotoPrintData;
import com.lostglade.server.map.MapPaletteQuantizer;
import com.lostglade.server.monitor.MonitorApp;
import com.lostglade.server.monitor.MonitorAppRole;
import com.lostglade.server.monitor.MonitorAppRegistry;
import com.lostglade.server.monitor.MonitorBackgroundPlaybackPolicy;
import com.lostglade.server.monitor.MonitorMediaApp;
import com.lostglade.server.monitor.MonitorSberDronesCatalog;
import com.lostglade.server.monitor.MonitorYoutubeRelayClient;
import com.lostglade.server.monitor.MonitorYoutubeMusicCache;
import com.lostglade.server.progress.TaskProgress;
import com.mojang.math.Transformation;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

final class MonitorScreenLiveCameraPlayback {
	private MonitorScreenLiveCameraPlayback() {
	}

	static void refreshLiveCameraStreamHealth(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			server.execute(() -> RendererBotCameraSystem.stopLiveStream(liveCameraStreamOwnerId(key)));
			return;
		}
		synchronized (state) {
			state.playbackFuture = null;
		}
		ScreenComponent component = resolveScreenComponent(server, key);
		boolean shouldRender = syncLiveCameraPlayback(server, component, state);
		if (shouldRender && hasNearbyMediaViewer(server, key)) {
			requestRuntimeRender(server, key);
		}
		MediaRuntimeState current = MEDIA_STATES.get(key);
		if (current == null) {
			return;
		}
		synchronized (current) {
			if (!isStreamPlaybackLocked(current)
					|| current.streamKind != PlaybackStreamKind.LIVE_CAMERA
					|| current.sourceUrl == null
					|| current.sourceUrl.isBlank()
					|| current.waitingForLink) {
				return;
			}
		}
		scheduleNextMediaFrame(server, key);
	}

	static boolean syncLiveCameraPlayback(MinecraftServer server, ScreenComponent component, MediaRuntimeState state) {
		if (server == null || component == null || state == null) {
			if (component != null) {
				RendererBotCameraSystem.stopLiveStream(liveCameraStreamOwnerId(component.runtimeKey()));
			}
			return false;
		}
		if (!component.powered()) {
			RendererBotCameraSystem.stopLiveStream(liveCameraStreamOwnerId(component.runtimeKey()));
			clearPendingLiveCameraApply(state);
			return false;
		}
		String sourceUrl;
		synchronized (state) {
			if (!isStreamPlaybackLocked(state)
					|| state.streamKind != PlaybackStreamKind.LIVE_CAMERA
					|| state.sourceUrl == null
					|| state.sourceUrl.isBlank()
					|| state.waitingForLink) {
				RendererBotCameraSystem.stopLiveStream(liveCameraStreamOwnerId(component.runtimeKey()));
				return false;
			}
			sourceUrl = state.sourceUrl;
		}
		ServerLevel screenLevel = server.getLevel(component.runtimeKey().dimension());
		LiveCameraReference cameraRef = liveCameraGalleryReference(sourceUrl, component.runtimeKey().dimension());
		if (screenLevel == null || cameraRef == null) {
			RendererBotCameraSystem.stopLiveStream(liveCameraStreamOwnerId(component.runtimeKey()));
			applyLiveCameraStreamFailure(server, component.runtimeKey(), sourceUrl, "Источник недоступен");
			return false;
		}
		if (!hasNearbyMediaViewer(screenLevel, component)) {
			RendererBotCameraSystem.stopLiveStream(liveCameraStreamOwnerId(component.runtimeKey()));
			return false;
		}
		int fullWidth = Math.max(1, component.width()) * MAP_SIZE;
		int fullHeight = Math.max(1, component.height()) * MAP_SIZE;
		if (cameraRef.sourceType() == LiveCameraSourceType.DRONE) {
			if (cameraRef.sourceUuid() == null) {
				return resetLiveCameraToHome(server, screenLevel, component, state);
			}
				DroneSystem.DroneLiveFeedState droneState = DroneSystem.resolveLiveFeedState(server, cameraRef.sourceUuid(), cameraRef.dimension(), cameraRef.pos());
				if (droneState == null) {
					return resetLiveCameraToHome(server, screenLevel, component, state);
				}
			ServerLevel droneLevel = server.getLevel(droneState.dimension());
			if (droneLevel == null) {
				return resetLiveCameraToHome(server, screenLevel, component, state);
			}
			boolean started = RendererBotCameraSystem.ensureLiveStream(
					liveCameraStreamOwnerId(component.runtimeKey()),
					droneLevel,
					null,
					droneState.expectedX(),
					droneState.expectedY(),
					droneState.expectedZ(),
					droneState.yaw(),
					droneState.pitch(),
					droneState.followEntityUuid(),
					droneState.hiddenEntityUuids(),
					droneState.omnidirectionalChunkLoading(),
					fullWidth,
					fullHeight,
					LIVE_CAMERA_FOV_DEGREES,
					LIVE_CAMERA_TARGET_FPS,
					frame -> onLiveCameraFrame(server, component.runtimeKey(), sourceUrl, fullWidth, fullHeight, frame.pixels()),
					error -> applyLiveCameraStreamFailure(server, component.runtimeKey(), sourceUrl, error)
			);
			if (!started) {
				boolean changed;
				synchronized (state) {
					boolean nextLoading = state.streamFrame == null;
					// Renderer availability is transient implementation state, not an
					// error for the camera application. Keep the last frame when one
					// exists; a new feed otherwise stays quietly in its loading view.
					String nextStatus = state.streamFrame == null ? "" : state.statusText;
					changed = state.loading != nextLoading || !Objects.equals(state.statusText, nextStatus);
					state.loading = nextLoading;
					state.statusText = nextStatus;
					if (changed) {
						state.version++;
					}
				}
				return changed;
			}
			return false;
		}
		ServerLevel cameraLevel = server.getLevel(cameraRef.dimension());
		BlockPos cameraPos = cameraRef.pos();
		if (cameraLevel == null || cameraPos == null) {
			return resetLiveCameraToHome(server, screenLevel, component, state);
		}
		CameraRelocationSystem.MobileCameraFeed mobileFeed = CameraRelocationSystem.mobileCameraFeed(cameraLevel, cameraPos);
		if (mobileFeed != null) {
			RendererBotCameraSystem.ensureLiveStream(
					liveCameraStreamOwnerId(component.runtimeKey()),
					cameraLevel,
					null,
					mobileFeed.expectedX(),
					mobileFeed.expectedY(),
					mobileFeed.expectedZ(),
					mobileFeed.yaw(),
					mobileFeed.pitch(),
					mobileFeed.followEntityUuid(),
					mobileFeed.hiddenEntityUuids(),
					true,
					fullWidth,
					fullHeight,
					LIVE_CAMERA_FOV_DEGREES,
					LIVE_CAMERA_TARGET_FPS,
					frame -> onLiveCameraFrame(server, component.runtimeKey(), sourceUrl, fullWidth, fullHeight, frame.pixels()),
					error -> applyLiveCameraStreamFailure(server, component.runtimeKey(), sourceUrl, error)
			);
			return false;
		}
		RocketLaunchEventSystem.RocketCameraFeed rocketFeed = RocketLaunchEventSystem.launchedCameraFeed(cameraLevel, cameraPos);
		if (rocketFeed != null) {
			boolean started = RendererBotCameraSystem.ensureLiveStream(
					liveCameraStreamOwnerId(component.runtimeKey()),
					cameraLevel,
					null,
					rocketFeed.expectedX(),
					rocketFeed.expectedY(),
					rocketFeed.expectedZ(),
					rocketFeed.yaw(),
					rocketFeed.pitch(),
					rocketFeed.followEntityUuid(),
					Set.of(rocketFeed.followEntityUuid()),
					true,
					fullWidth,
					fullHeight,
					LIVE_CAMERA_FOV_DEGREES,
					LIVE_CAMERA_TARGET_FPS,
					frame -> onLiveCameraFrame(server, component.runtimeKey(), sourceUrl, fullWidth, fullHeight, frame.pixels()),
					error -> applyLiveCameraStreamFailure(server, component.runtimeKey(), sourceUrl, error)
			);
			return false;
		}
		if (!RendererBotCameraSystem.isCameraPlayerLoaded(cameraLevel, cameraPos)) {
			return resetLiveCameraToHome(server, screenLevel, component, state);
		}
		if (!cameraLevel.hasChunkAt(cameraPos) || !isCameraBlock(cameraLevel, cameraPos)) {
			return resetLiveCameraToHome(server, screenLevel, component, state);
		}
		BlockState cameraState = cameraLevel.getBlockState(cameraPos);
		LiveCameraPose pose = liveCameraCapturePose(cameraLevel, cameraPos, cameraState);
		Vec3 origin = pose.origin();
		double botFeetY = origin.y - RENDERER_BOT_EYE_HEIGHT;
		boolean started = RendererBotCameraSystem.ensureLiveStream(
				liveCameraStreamOwnerId(component.runtimeKey()),
				cameraLevel,
				cameraPos,
				origin.x,
				botFeetY,
				origin.z,
				pose.yaw(),
				pose.pitch(),
				null,
				Set.of(),
				false,
				fullWidth,
				fullHeight,
				LIVE_CAMERA_FOV_DEGREES,
				LIVE_CAMERA_TARGET_FPS,
				frame -> onLiveCameraFrame(server, component.runtimeKey(), sourceUrl, fullWidth, fullHeight, frame.pixels()),
				error -> applyLiveCameraStreamFailure(server, component.runtimeKey(), sourceUrl, error)
		);
		if (!started) {
			boolean changed;
			synchronized (state) {
				boolean nextLoading = state.streamFrame == null;
				String nextStatus = state.streamFrame == null ? "" : state.statusText;
				changed = state.loading != nextLoading || !Objects.equals(state.statusText, nextStatus);
				state.loading = nextLoading;
				state.statusText = nextStatus;
				if (changed) {
					state.version++;
				}
			}
			return changed;
		}
		return false;
	}

	static boolean resetLiveCameraToHome(MinecraftServer server, ServerLevel level, ScreenComponent component, MediaRuntimeState state) {
		if (server == null || level == null || component == null || state == null) {
			return false;
		}
		RendererBotCameraSystem.stopLiveStream(liveCameraStreamOwnerId(component.runtimeKey()));
		clearPendingLiveCameraApply(state);
		deactivateMediaSession(server, component.runtimeKey());
		applyTransientComponentViewState(server, level, component, ScreenViewMode.HOME, 0);
		return false;
	}

	static void onLiveCameraFrame(MinecraftServer server, ScreenRuntimeKey key, String url, int fullWidth, int fullHeight, byte[] pixels) {
		if (server == null || key == null || url == null || url.isBlank() || pixels == null || pixels.length == 0) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			RendererBotCameraSystem.stopLiveStream(liveCameraStreamOwnerId(key));
			return;
		}
		boolean scheduleDecode = false;
		synchronized (state) {
			if (state.streamKind != PlaybackStreamKind.LIVE_CAMERA || !Objects.equals(state.sourceUrl, url)) {
				return;
			}
			state.pendingLiveCameraPixels = pixels;
			state.liveCameraLastFrameAtMillis = System.currentTimeMillis();
			if (!state.liveCameraDecodeScheduled) {
				state.liveCameraDecodeScheduled = true;
				scheduleDecode = true;
			}
		}
		if (!scheduleDecode) {
			return;
		}
		ensureExecutors();
		liveCameraExecutor.execute(() -> decodePendingLiveCameraFrames(server, key, url, fullWidth, fullHeight));
	}

	static void decodePendingLiveCameraFrames(MinecraftServer server, ScreenRuntimeKey key, String url, int fullWidth, int fullHeight) {
		while (true) {
			byte[] pixels;
			boolean decodePreviewFrame;
			byte[][] displayedTiles;
			long displayedGeneration;
			boolean directView;
			MediaRuntimeState state = MEDIA_STATES.get(key);
			if (state == null) {
				RendererBotCameraSystem.stopLiveStream(liveCameraStreamOwnerId(key));
				return;
			}
			synchronized (state) {
				pixels = state.pendingLiveCameraPixels;
				state.pendingLiveCameraPixels = null;
				if (pixels == null) {
					state.liveCameraDecodeScheduled = false;
					return;
				}
				long now = System.currentTimeMillis();
				directView = state.overlayMode == MediaOverlayMode.VIEW;
				decodePreviewFrame = state.streamFrame == null
						|| state.loading
						|| (!directView && now >= state.nextLiveCameraPreviewDecodeAtMillis);
				if (decodePreviewFrame && !directView) {
					state.nextLiveCameraPreviewDecodeAtMillis = now + LIVE_CAMERA_PREVIEW_DECODE_INTERVAL_MS;
				}
				displayedTiles = state.liveCameraDisplayedTiles;
				displayedGeneration = state.liveCameraDisplayedGeneration;
			}
			PreparedRenderedTiles preparedTiles;
			try {
				byte[][] renderedTiles = splitRenderedTiles(pixels, fullWidth, fullHeight);
				preparedTiles = prepareRenderedTiles(renderedTiles, displayedTiles, displayedGeneration);
			} catch (Exception exception) {
				server.execute(() -> applyLiveCameraStreamFailure(server, key, url, sanitizeMediaError(exception.getMessage())));
				continue;
			}
			BufferedImage previewFrame = null;
			if (decodePreviewFrame) {
				try {
					previewFrame = mapPaletteImage(pixels, fullWidth, fullHeight);
				} catch (Exception exception) {
					server.execute(() -> applyLiveCameraStreamFailure(server, key, url, sanitizeMediaError(exception.getMessage())));
					continue;
				}
			}
			BufferedImage finalPreviewFrame = previewFrame;
			PreparedRenderedTiles finalPreparedTiles = preparedTiles;
			server.execute(() -> applyLiveCameraFrame(server, key, url, finalPreparedTiles, finalPreviewFrame));
		}
	}

	static void applyLiveCameraFrame(MinecraftServer server, ScreenRuntimeKey key, String url, PreparedRenderedTiles preparedTiles, BufferedImage previewFrame) {
		if (server == null || key == null || url == null || url.isBlank() || preparedTiles == null || preparedTiles.renderedTiles() == null || preparedTiles.renderedTiles().length == 0) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			RendererBotCameraSystem.stopLiveStream(liveCameraStreamOwnerId(key));
			return;
		}
		boolean shouldRender = false;
		boolean directApply = false;
		synchronized (state) {
			if (state.streamKind != PlaybackStreamKind.LIVE_CAMERA || !Objects.equals(state.sourceUrl, url)) {
				return;
			}
			state.liveCameraBufferedTiles = preparedTiles.renderedTiles();
			boolean stateChanged = state.loading || !state.liveStream || !"LIVE".equals(state.statusText);
			if (previewFrame != null) {
				state.streamFrame = previewFrame;
				state.loadingBackdropFrame = previewFrame;
				upsertLiveCameraPreviewLocked(state, url, previewFrame);
				stateChanged = true;
			}
			state.loading = false;
			state.liveStream = true;
			state.statusText = "LIVE";
			directApply = state.overlayMode == MediaOverlayMode.VIEW;
			if (directApply) {
				state.pendingLiveCameraPreparedTiles = preparedTiles;
				state.pendingLiveCameraApplyUrl = url;
				if (!state.liveCameraApplyScheduled) {
					state.liveCameraApplyScheduled = true;
					shouldRender = true;
				}
			}
			if (stateChanged) {
				state.version++;
			}
			if (!directApply) {
				shouldRender = stateChanged || previewFrame != null;
			}
		}
		if (directApply) {
			ServerLevel level = server.getLevel(key.dimension());
			ScreenComponent component = resolveScreenComponent(server, key);
			if (level != null && component != null && component.powered() && hasNearbyMediaViewer(level, component)) {
				processPendingLiveCameraApply(server, key);
			} else {
				clearPendingLiveCameraApply(state);
			}
			return;
		}
		if (shouldRender && hasNearbyMediaViewer(server, key)) {
			requestRuntimeRender(server, key);
		}
	}

	static void applyLiveCameraStreamFailure(MinecraftServer server, ScreenRuntimeKey key, String url, String error) {
		if (server == null || key == null) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return;
		}
		boolean shouldRender = false;
		synchronized (state) {
			if (state.streamKind != PlaybackStreamKind.LIVE_CAMERA || !Objects.equals(state.sourceUrl, url)) {
				return;
			}
			state.loading = state.streamFrame == null;
			String message = sanitizeMediaError(error);
			// The app must not surface handoff/availability races from the render
			// worker. They resolve automatically and would otherwise overwrite a
			// valid camera view with a misleading error.
			if (isRendererAvailabilityMessage(message)) {
				if (state.streamFrame == null) {
					state.statusText = "";
				}
			} else {
				state.statusText = message;
			}
			state.version++;
			shouldRender = true;
		}
		if (shouldRender && hasNearbyMediaViewer(server, key)) {
			requestRuntimeRender(server, key);
		}
	}

	private static boolean isRendererAvailabilityMessage(String message) {
		if (message == null || message.isBlank()) {
			return false;
		}
		String normalized = message.toLowerCase(java.util.Locale.ROOT);
		return normalized.contains("клиент камеры")
				|| normalized.contains("renderer client")
				|| normalized.contains("active renderer");
	}

	static byte[][] splitRenderedTiles(byte[] pixels, int fullWidth, int fullHeight) {
		if (pixels == null || pixels.length == 0 || fullWidth <= 0 || fullHeight <= 0) {
			throw new IllegalArgumentException("Live camera frame is empty");
		}
		if (fullWidth % MAP_SIZE != 0 || fullHeight % MAP_SIZE != 0) {
			throw new IllegalArgumentException("Live camera frame does not match map tile size");
		}
		if (pixels.length < fullWidth * fullHeight) {
			throw new IllegalArgumentException("Live camera frame is truncated");
		}
		int tilesWide = fullWidth / MAP_SIZE;
		int tilesHigh = fullHeight / MAP_SIZE;
		if (tilesWide == 1 && tilesHigh == 1) {
			return new byte[][]{pixels};
		}
		byte[][] renderedTiles = new byte[tilesWide * tilesHigh][MAP_SIZE * MAP_SIZE];
		for (int tileY = 0; tileY < tilesHigh; tileY++) {
			for (int tileX = 0; tileX < tilesWide; tileX++) {
				byte[] tile = renderedTiles[tileY * tilesWide + tileX];
				for (int row = 0; row < MAP_SIZE; row++) {
					int sourceOffset = (tileY * MAP_SIZE + row) * fullWidth + tileX * MAP_SIZE;
					System.arraycopy(pixels, sourceOffset, tile, row * MAP_SIZE, MAP_SIZE);
				}
			}
		}
		return renderedTiles;
	}

	static PreparedRenderedTiles prepareRenderedTiles(byte[][] renderedTiles, byte[][] previousTiles, long baselineGeneration) {
		if (renderedTiles == null || renderedTiles.length == 0) {
			throw new IllegalArgumentException("Live camera frame is empty");
		}
		return new PreparedRenderedTiles(renderedTiles, buildTileFramePatches(previousTiles, renderedTiles), baselineGeneration);
	}

	static TileFramePatch[] buildTileFramePatches(byte[][] previousTiles, byte[][] renderedTiles) {
		if (renderedTiles == null || renderedTiles.length == 0) {
			return new TileFramePatch[0];
		}
		TileFramePatch[] patches = new TileFramePatch[renderedTiles.length];
		for (int tileIndex = 0; tileIndex < renderedTiles.length; tileIndex++) {
			byte[] currentTile = renderedTiles[tileIndex];
			if (currentTile == null || currentTile.length < MAP_SIZE * MAP_SIZE) {
				continue;
			}
			byte[] previousTile = previousTiles != null && tileIndex < previousTiles.length ? previousTiles[tileIndex] : null;
			patches[tileIndex] = buildTileFramePatch(previousTile, currentTile);
		}
		return patches;
	}

	static TileFramePatch buildTileFramePatch(byte[] previousTile, byte[] currentTile) {
		if (currentTile == null || currentTile.length < MAP_SIZE * MAP_SIZE) {
			return null;
		}
		if (previousTile == null || previousTile.length < MAP_SIZE * MAP_SIZE) {
			return new TileFramePatch(0, 0, MAP_SIZE, MAP_SIZE, currentTile);
		}
		int minX = MAP_SIZE;
		int minY = MAP_SIZE;
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < MAP_SIZE; y++) {
			int rowStart = y * MAP_SIZE;
			for (int x = 0; x < MAP_SIZE; x++) {
				int index = rowStart + x;
				if (previousTile[index] == currentTile[index]) {
					continue;
				}
				minX = Math.min(minX, x);
				minY = Math.min(minY, y);
				maxX = Math.max(maxX, x);
				maxY = Math.max(maxY, y);
			}
		}
		if (maxX < minX || maxY < minY) {
			return null;
		}
		int patchWidth = maxX - minX + 1;
		int patchHeight = maxY - minY + 1;
		if (patchWidth == MAP_SIZE && patchHeight == MAP_SIZE) {
			return new TileFramePatch(0, 0, MAP_SIZE, MAP_SIZE, currentTile);
		}
		byte[] patch = new byte[patchWidth * patchHeight];
		for (int row = 0; row < patchHeight; row++) {
			int sourceOffset = (minY + row) * MAP_SIZE + minX;
			System.arraycopy(currentTile, sourceOffset, patch, row * patchWidth, patchWidth);
		}
		return new TileFramePatch(minX, minY, patchWidth, patchHeight, patch);
	}

	static void upsertLiveCameraPreviewLocked(MediaRuntimeState state, String url, BufferedImage previewFrame) {
		if (state == null || url == null || url.isBlank() || previewFrame == null) {
			return;
		}
		int index = resolveGalleryItemIndex(state, url, -1);
		if (index < 0 || index >= state.galleryItems.size()) {
			return;
		}
		GalleryItem existing = state.galleryItems.get(index);
		if (existing == null || effectiveGalleryItemKind(existing) != GalleryItemKind.LIVE_CAMERA) {
			return;
		}
		state.galleryItems.set(
				index,
				new GalleryItem(
						existing.title(),
						existing.subtitle(),
						existing.url(),
						existing.localMediaKey(),
						null,
						previewFrame,
						GalleryItemKind.LIVE_CAMERA
				)
		);
	}
}
