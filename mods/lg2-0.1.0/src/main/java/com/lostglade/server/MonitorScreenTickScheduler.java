package com.lostglade.server;

import static com.lostglade.server.MonitorScreenMapTransport.*;
import static com.lostglade.server.MonitorScreenLiveCameraPlayback.*;
import static com.lostglade.server.MonitorScreenLiveSources.*;
import static com.lostglade.server.MonitorScreenMediaFrameRuntime.*;
import static com.lostglade.server.MonitorScreenMediaLoadResults.*;
import static com.lostglade.server.MonitorScreenSystem.*;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class MonitorScreenTickScheduler {
	private MonitorScreenTickScheduler() {
	}

	static void tick(MinecraftServer server) {
		if (server == null) {
			return;
		}
		processPendingScreenSyncs(server);
		processPendingCameraRefreshes(server);
		MonitorScrollAnimationSystem.tick(server);
		updateDebugAimCursors(server);
		processPendingComponentSyncs(server);
		processPendingSpeakerRefreshes(server);
		if ((server.getTickCount() % POWER_REFRESH_FALLBACK_INTERVAL_TICKS) == 0L) {
			enqueuePeriodicPowerRefreshes();
		}
		processPowerRefreshes(server);
		if ((server.getTickCount() % MEDIA_FOCUS_CLEANUP_INTERVAL_TICKS) == 0L) {
			cleanupExpiredMediaFocus();
		}
		if ((server.getTickCount() % MEDIA_ACTIONBAR_REFRESH_INTERVAL_TICKS) == 0L) {
			refreshMediaRequestActionbars(server);
		}
		if ((server.getTickCount() % MEDIA_SESSION_CLEANUP_INTERVAL_TICKS) == 0L) {
			cleanupMediaSessions(server);
		}
		if ((server.getTickCount() % RENDER_CACHE_CLEANUP_INTERVAL_TICKS) == 0L) {
			cleanupRenderCaches(server);
		}
	}

	static void cleanupRenderCaches(MinecraftServer server) {
		if (server == null) {
			return;
		}
		if (TILE_CACHE.size() > MAX_TILE_CACHE_ENTRIES) {
			TILE_CACHE.clear();
		}
		if (renderedMapFrameCacheEmpty()) {
			return;
		}
		Set<Integer> activeMapIds = new HashSet<>();
		for (MonitorLevelState levelState : LEVEL_STATES.values()) {
			if (levelState == null) {
				continue;
			}
			ServerLevel level = server.getLevel(levelState.dimension());
			if (level == null) {
				continue;
			}
			for (ScreenComponent component : levelState.components().values()) {
				if (component == null) {
					continue;
				}
				for (ItemFrame frame : component.frameCoords().keySet()) {
					if (frame == null || !frame.isAlive()) {
						continue;
					}
					MapId mapId = frame.getItem().get(DataComponents.MAP_ID);
					if (mapId != null) {
						activeMapIds.add(mapId.id());
					}
				}
			}
		}
		retainRenderedMapFrames(activeMapIds);
	}

	static void processPendingScreenSyncs(MinecraftServer server) {
		if (server == null) {
			return;
		}
		int remaining = MAX_SCREEN_SYNC_OPERATIONS_PER_TICK;
		for (MonitorLevelState state : LEVEL_STATES.values()) {
			if (remaining <= 0) {
				break;
			}
			while (remaining > 0) {
				ScreenKey key = state.pollDirtyFrame();
				if (key == null) {
					break;
				}
				processPendingScreenSync(server, state, key);
				remaining--;
			}
		}
	}

	static void processPendingComponentSyncs(MinecraftServer server) {
		if (server == null) {
			return;
		}
		int remaining = Math.max(4, MAX_SCREEN_SYNC_OPERATIONS_PER_TICK / 2);
		for (MonitorLevelState state : LEVEL_STATES.values()) {
			if (remaining <= 0) {
				break;
			}
			while (remaining > 0) {
				ScreenRuntimeKey runtimeKey = state.pollDirtyRuntime();
				if (runtimeKey == null) {
					break;
				}
				dispatchRuntimeRender(server, runtimeKey);
				remaining--;
			}
		}
	}

	static void processPendingCameraRefreshes(MinecraftServer server) {
		if (server == null) {
			return;
		}
		int remaining = MAX_CAMERA_REFRESHES_PER_TICK;
		for (MonitorLevelState state : LEVEL_STATES.values()) {
			if (remaining <= 0) {
				break;
			}
			while (remaining > 0) {
				ScreenRuntimeKey runtimeKey = state.pollCameraRefreshRuntime();
				if (runtimeKey == null) {
					break;
				}
				processPendingCameraRefresh(server, runtimeKey);
				remaining--;
			}
		}
	}

	static void processPendingSpeakerRefreshes(MinecraftServer server) {
		if (server == null) {
			return;
		}
		int remaining = MAX_SPEAKER_REFRESHES_PER_TICK;
		for (MonitorLevelState state : LEVEL_STATES.values()) {
			if (remaining <= 0) {
				break;
			}
			while (remaining > 0) {
				ScreenRuntimeKey runtimeKey = state.pollSpeakerRefreshRuntime();
				if (runtimeKey == null) {
					break;
				}
				processPendingSpeakerRefresh(server, runtimeKey);
				remaining--;
			}
		}
	}

	static void processPendingSpeakerRefresh(MinecraftServer server, ScreenRuntimeKey runtimeKey) {
		if (server == null || runtimeKey == null) {
			return;
		}
		ServerLevel level = server.getLevel(runtimeKey.dimension());
		if (level == null) {
			return;
		}
		ScreenComponent component = resolveScreenComponent(server, runtimeKey);
		if (component == null) {
			return;
		}
		refreshConnectedSpeakersNow(server, component);
	}

	static void processPendingCameraRefresh(MinecraftServer server, ScreenRuntimeKey runtimeKey) {
		if (server == null || runtimeKey == null) {
			return;
		}
		ServerLevel level = server.getLevel(runtimeKey.dimension());
		if (level == null) {
			return;
		}
		ScreenComponent component = resolveScreenComponent(server, runtimeKey);
		if (component == null) {
			levelState(level.dimension()).connectedCameraPositions().remove(runtimeKey);
			RendererBotCameraSystem.stopLiveStream(liveCameraStreamOwnerId(runtimeKey));
			return;
		}
		refreshConnectedCameraState(server, level, component);
	}

	static void clearPendingLiveCameraApply(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.pendingLiveCameraPreparedTiles = null;
			state.pendingLiveCameraApplyUrl = null;
			state.liveCameraApplyScheduled = false;
		}
	}

	static void setMediaOverlayModeLocked(MediaRuntimeState state, MediaOverlayMode mode) {
		if (state == null || mode == null) {
			return;
		}
		MediaOverlayMode previousMode = state.overlayMode;
		state.overlayMode = mode;
		if (previousMode != mode && mode == MediaOverlayMode.VIEW && state.streamKind == PlaybackStreamKind.LIVE_CAMERA) {
			invalidateLiveCameraDisplayedTilesLocked(state);
		}
	}

	static void invalidateLiveCameraDisplayedTilesLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		// Keep the last full live frame so closing controls can restore it without a stale preview render.
		state.liveCameraDisplayedGeneration++;
	}

	static boolean restoreLiveCameraViewFromBufferedTiles(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return false;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return false;
		}
		byte[][] liveTiles;
		long displayedGeneration;
		synchronized (state) {
			if (state.streamKind != PlaybackStreamKind.LIVE_CAMERA || state.overlayMode != MediaOverlayMode.VIEW) {
				return false;
			}
			liveTiles = state.liveCameraBufferedTiles != null ? state.liveCameraBufferedTiles : state.liveCameraDisplayedTiles;
			displayedGeneration = state.liveCameraDisplayedGeneration;
		}
		if (liveTiles == null || liveTiles.length == 0) {
			return false;
		}
		ServerLevel level = server.getLevel(key.dimension());
		ScreenComponent component = resolveScreenComponent(server, key);
		if (level == null || component == null || !component.powered() || !hasNearbyMediaViewer(level, component)) {
			return false;
		}
		applyPreparedRenderedTiles(
				level,
				component,
				state,
				new PreparedRenderedTiles(liveTiles, new TileFramePatch[0], displayedGeneration)
		);
		return true;
	}

	static void scheduleLiveCameraApply(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		server.execute(() -> processPendingLiveCameraApply(server, key));
	}

	static void processPendingLiveCameraApply(MinecraftServer server, ScreenRuntimeKey runtimeKey) {
		if (server == null || runtimeKey == null) {
			return;
		}
		ServerLevel level = server.getLevel(runtimeKey.dimension());
		if (level == null) {
			return;
		}
		ScreenComponent component = resolveScreenComponent(server, runtimeKey);
		MediaRuntimeState state = MEDIA_STATES.get(runtimeKey);
		if (component == null || state == null || !component.powered()) {
			clearPendingLiveCameraApply(state);
			return;
		}
		if (!hasNearbyMediaViewer(level, component)) {
			synchronized (state) {
				state.liveCameraApplyScheduled = false;
			}
			return;
		}
		PreparedRenderedTiles preparedTiles;
		boolean reschedule = false;
		synchronized (state) {
			state.liveCameraApplyScheduled = false;
			if (state.streamKind != PlaybackStreamKind.LIVE_CAMERA
					|| state.overlayMode != MediaOverlayMode.VIEW
					|| state.pendingLiveCameraPreparedTiles == null
					|| !Objects.equals(state.pendingLiveCameraApplyUrl, state.sourceUrl)) {
				state.pendingLiveCameraPreparedTiles = null;
				state.pendingLiveCameraApplyUrl = null;
				return;
			}
			preparedTiles = state.pendingLiveCameraPreparedTiles;
			state.pendingLiveCameraPreparedTiles = null;
			state.pendingLiveCameraApplyUrl = null;
		}
		applyPreparedRenderedTiles(level, component, state, preparedTiles);
		synchronized (state) {
			if (state.streamKind == PlaybackStreamKind.LIVE_CAMERA
					&& state.overlayMode == MediaOverlayMode.VIEW
					&& state.pendingLiveCameraPreparedTiles != null
					&& !state.liveCameraApplyScheduled
					&& Objects.equals(state.pendingLiveCameraApplyUrl, state.sourceUrl)) {
				state.liveCameraApplyScheduled = true;
				reschedule = true;
			}
		}
		if (reschedule) {
			scheduleLiveCameraApply(server, runtimeKey);
		}
	}

	static void refreshConnectedCameraState(MinecraftServer server, ServerLevel level, ScreenComponent component) {
		if (server == null || level == null || component == null) {
			return;
		}
		List<LiveCameraReference> connectedCameraPositions = collectConnectedCameraPositions(level, component);
		levelState(level.dimension()).connectedCameraPositions().put(component.runtimeKey(), connectedCameraPositions);
		MediaRuntimeState state = MEDIA_STATES.get(component.runtimeKey());
		if (state == null) {
			return;
		}
		boolean shouldRender = syncConnectedLiveCameraGalleryState(server, component, state, connectedCameraPositions);
		shouldRender |= syncLiveCameraPlayback(server, component, state);
		if (shouldRender && hasNearbyMediaViewer(level, component)) {
			requestRuntimeRender(server, component.runtimeKey());
		}
	}

	static void processPendingScreenSync(MinecraftServer server, MonitorLevelState state, ScreenKey key) {
		if (server == null || state == null || key == null) {
			return;
		}
		ServerLevel level = server.getLevel(state.dimension());
		if (level == null || !level.hasChunkAt(key.pos())) {
			return;
		}
		ItemFrame frame = findScreenFrame(level, key.pos(), key.direction());
		if (frame == null || readScreenState(frame.getItem()) == null) {
			state.knownFrames().remove(key);
			ScreenRuntimeKey runtimeKey = state.frameToRuntime().remove(key);
			if (runtimeKey != null) {
				invalidateCachedRuntime(level, runtimeKey, key, true);
			}
			return;
		}
		synchronizeConnectedScreens(level, frame, null, null, null);
	}

	static void processPowerRefreshes(MinecraftServer server) {
		if (server == null) {
			return;
		}
		int remaining = MAX_POWER_REFRESHES_PER_TICK;
		for (MonitorLevelState state : LEVEL_STATES.values()) {
			if (remaining <= 0) {
				break;
			}
			ServerLevel level = server.getLevel(state.dimension());
			if (level == null) {
				continue;
			}
			while (remaining > 0) {
				ScreenRuntimeKey runtimeKey = state.pollPowerRuntime();
				if (runtimeKey == null) {
					break;
				}
				refreshComponentPower(level, runtimeKey);
				remaining--;
			}
		}
	}

	static void enqueuePeriodicPowerRefreshes() {
		for (MonitorLevelState state : LEVEL_STATES.values()) {
			if (state == null) {
				continue;
			}
			for (ScreenRuntimeKey runtimeKey : state.components().keySet()) {
				state.enqueuePowerRuntime(runtimeKey);
			}
		}
	}

	static void refreshComponentPower(ServerLevel level, ScreenRuntimeKey runtimeKey) {
		if (level == null || runtimeKey == null) {
			return;
		}
		ScreenComponent component = resolveScreenComponent(level.getServer(), runtimeKey);
		if (component == null) {
			return;
		}
		boolean poweredNow = component.frameCoords().keySet().stream()
				.anyMatch(frame -> frame != null && frame.isAlive() && isPowered(level, frame));
		if (poweredNow != component.powered()) {
			ItemFrame rootFrame = findScreenFrame(level, runtimeKey.pos(), runtimeKey.facing());
			if (rootFrame != null) {
				synchronizeConnectedScreens(level, rootFrame, null, null, null);
			}
		}
	}

}
