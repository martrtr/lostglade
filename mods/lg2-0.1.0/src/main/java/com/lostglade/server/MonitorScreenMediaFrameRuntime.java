package com.lostglade.server;

import static com.lostglade.server.MonitorScreenMessages.*;
import static com.lostglade.server.MonitorScreenMapTransport.*;
import static com.lostglade.server.MonitorScreenBackgroundLoader.*;
import static com.lostglade.server.MonitorScreenGalleryRuntime.*;
import static com.lostglade.server.MonitorScreenLiveCameraPlayback.*;
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

final class MonitorScreenMediaFrameRuntime {
	private MonitorScreenMediaFrameRuntime() {
	}

	static void advanceMediaFrame(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return;
		}

		boolean shouldRender = false;
		boolean shouldContinue;
		Integer galleryAdvanceIndex = null;
		synchronized (state) {
			state.playbackFuture = null;
			long now = System.currentTimeMillis();
			boolean streamPlaybackActive = hasActiveStreamPlaybackLocked(state) && !state.waitingForLink;
			boolean mediaActive = !streamPlaybackActive && loadedMediaAnimationActiveLocked(state);
			boolean slideshowActive = !streamPlaybackActive && gallerySlideshowPlaybackActiveLocked(state);
			if (!mediaActive) {
				state.nextLoadedMediaFrameAtMillis = 0L;
			}
			if (!slideshowActive) {
				state.gallerySlideshowAdvanceAtMillis = 0L;
			}
			if (mediaActive
					&& state.nextLoadedMediaFrameAtMillis > 0L
					&& now >= state.nextLoadedMediaFrameAtMillis) {
				// Frame playback must not invalidate an in-flight large-screen render, or the first
				// completed frame can get discarded forever while animation keeps advancing.
				state.frameIndex = (state.frameIndex + 1) % state.loadedMedia.frameCount();
				state.nextLoadedMediaFrameAtMillis = now + sanitizedAnimationDelayMillis(state.loadedMedia.delayMillis(state.frameIndex));
				shouldRender = true;
			}
			if (slideshowActive) {
				long slideshowDeadlineMillis = state.gallerySlideshowAdvanceAtMillis;
				if (slideshowDeadlineMillis > 0L && now >= slideshowDeadlineMillis) {
					galleryAdvanceIndex = nextGallerySlideshowIndexLocked(state);
					state.gallerySlideshowAdvanceAtMillis = 0L;
				} else {
					state.gallerySlideshowAdvanceAtMillis = nextGallerySlideshowDeadlineMillisLocked(state, now);
				}
			}
			shouldContinue = galleryAdvanceIndex == null && (mediaActive || slideshowActive);
		}

		if (galleryAdvanceIndex != null) {
			continueGalleryPlaybackAtIndex(server, key, galleryAdvanceIndex);
			return;
		}
		if (shouldRender) {
			requestRuntimeRender(server, key);
		}
		if (shouldContinue) {
			scheduleNextMediaFrame(server, key);
		}
	}

	static void advanceBackgroundFrame(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return;
		}

		boolean shouldRender = false;
		boolean shouldContinue;
		synchronized (state) {
			state.backgroundFuture = null;
			long now = System.currentTimeMillis();
			boolean wallpaperActive = wallpaperAnimationActiveLocked(state);
			boolean playerBackgroundActive = playerBackgroundAnimationActiveLocked(state);
			if (!wallpaperActive) {
				state.nextWallpaperFrameAtMillis = 0L;
			}
			if (!playerBackgroundActive) {
				state.nextPlayerBackgroundFrameAtMillis = 0L;
			}
			if (wallpaperActive
					&& state.nextWallpaperFrameAtMillis > 0L
					&& now >= state.nextWallpaperFrameAtMillis) {
				state.wallpaperFrameIndex = (state.wallpaperFrameIndex + 1) % state.wallpaperMedia.frameCount();
				state.nextWallpaperFrameAtMillis = now + sanitizedAnimationDelayMillis(state.wallpaperMedia.delayMillis(state.wallpaperFrameIndex));
				shouldRender = true;
			}
			if (playerBackgroundActive
					&& state.nextPlayerBackgroundFrameAtMillis > 0L
					&& now >= state.nextPlayerBackgroundFrameAtMillis) {
				state.playerBackgroundFrameIndex = (state.playerBackgroundFrameIndex + 1) % state.playerBackgroundMedia.frameCount();
				state.nextPlayerBackgroundFrameAtMillis = now + sanitizedAnimationDelayMillis(state.playerBackgroundMedia.delayMillis(state.playerBackgroundFrameIndex));
				shouldRender = true;
			}
			shouldContinue = wallpaperActive || playerBackgroundActive;
			if (shouldContinue) {
				scheduleBackgroundPlaybackLocked(server, key, state);
			}
		}

		if (shouldRender && hasNearbyMediaViewer(server, key)) {
			requestRuntimeRender(server, key);
		}
	}

	static void resumeMediaPlaybackIfNeeded(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return;
		}
		scheduleBackgroundPlaybackIfNeeded(server, key);
		synchronized (state) {
			if (state.playbackFuture != null) {
				return;
			}
			if (state.waitingForLink) {
				return;
			}
			if (state.loading) {
				// Keep loading spinners animating even before the relay session is fully connected.
			} else if (hasActiveStreamPlaybackLocked(state)) {
				if (state.relaySessionId == null) {
					return;
				}
			} else if (!loadedMediaAnimationActiveLocked(state) && !gallerySlideshowPlaybackActiveLocked(state)) {
				return;
			}
		}
		scheduleNextMediaFrame(server, key);
	}

	static void refreshYoutubeSnapshot(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return;
		}
		String sessionId;
		long knownFrameSequence;
		synchronized (state) {
			state.playbackFuture = null;
			if (!isStreamPlaybackLocked(state) || state.relaySessionId == null || state.waitingForLink) {
				return;
			}
			sessionId = state.relaySessionId;
			knownFrameSequence = state.youtubeFrameSequence;
		}
		YoutubeSnapshotResult result;
		try {
			result = new YoutubeSnapshotResult(key, MonitorYoutubeRelayClient.snapshot(sessionId, knownFrameSequence), null);
		} catch (Exception exception) {
			result = new YoutubeSnapshotResult(key, null, sanitizeMediaError(exception.getMessage()));
		}
		YoutubeSnapshotResult finalResult = result;
		server.execute(() -> applyYoutubeSnapshotResult(server, finalResult));
	}

	static void applyYoutubeSnapshotResult(MinecraftServer server, YoutubeSnapshotResult result) {
		if (server == null || result == null) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(result.screenKey());
		if (state == null) {
			return;
		}
		boolean shouldReschedule = false;
		boolean shouldFadeProgress = false;
		boolean shouldRender = false;
		boolean shouldBumpVersion = false;
		boolean speakerRefreshNeeded = false;
		Integer queueAdvanceIndex = null;
		Integer galleryAdvanceIndex = null;
		synchronized (state) {
			if (!isStreamPlaybackLocked(state)) {
				return;
			}
			if (result.snapshot() != null) {
				boolean wasLoading = state.loading;
				long previousPositionMs = state.positionMs;
				long previousDurationMs = state.durationMs;
				long previousBufferedStartMs = state.bufferedStartMs;
				long previousBufferedEndMs = state.bufferedEndMs;
				boolean previousLiveStream = state.liveStream;
				boolean previousAudioPlaceholder = state.audioPlaceholder;
				boolean previousPaused = state.userPaused;
				String previousStatusText = state.statusText;
				String previousAudioStreamUrl = state.audioStreamUrl;
				long previousFrameSequence = state.youtubeFrameSequence;
				state.mediaTitle = result.snapshot().title();
				if (result.snapshot().frameSequence() != previousFrameSequence) {
					state.youtubeFrameSequence = result.snapshot().frameSequence();
					shouldRender = true;
				}
				if (result.snapshot().frame() != null) {
					state.streamFrame = result.snapshot().frame();
					shouldRender = true;
				}
				state.durationMs = result.snapshot().durationMs();
				state.bufferedStartMs = result.snapshot().bufferedStartMs();
				state.bufferedEndMs = result.snapshot().bufferedEndMs();
				state.liveStream = result.snapshot().live();
				state.audioPlaceholder = result.snapshot().audioPlaceholder();
				String snapshotAudioStreamUrl = result.snapshot().audioStreamUrl();
				if (snapshotAudioStreamUrl != null
						&& !snapshotAudioStreamUrl.isBlank()
						&& !Objects.equals(previousAudioStreamUrl, snapshotAudioStreamUrl)) {
					state.audioStreamUrl = snapshotAudioStreamUrl;
					bumpAudioSyncTokenLocked(state);
					speakerRefreshNeeded = true;
				}
				reconcilePendingAudioTransportLocked(state, result.snapshot().paused(), result.snapshot().positionMs());
				state.statusText = result.snapshot().status();
				state.loading = !result.snapshot().ready();
				if (previousDurationMs != state.durationMs
						|| previousBufferedStartMs != state.bufferedStartMs
						|| previousBufferedEndMs != state.bufferedEndMs
						|| previousLiveStream != state.liveStream
						|| previousAudioPlaceholder != state.audioPlaceholder
						|| previousPaused != state.userPaused
						|| !Objects.equals(previousStatusText, state.statusText)
						|| wasLoading != state.loading) {
					shouldRender = true;
				}
				if (wasLoading != state.loading
						|| previousPaused != state.userPaused
						|| previousAudioPlaceholder != state.audioPlaceholder
						|| previousLiveStream != state.liveStream
						|| !Objects.equals(previousStatusText, state.statusText)) {
					speakerRefreshNeeded = true;
				}
				if (!shouldRender && Math.abs(state.positionMs - previousPositionMs) >= effectiveYoutubeUiRefreshThresholdMs(server, result.screenKey())) {
					shouldRender = true;
				}
				if (result.snapshot().ready()) {
					if (wasLoading) {
						// Audio is decoded by a separate ffmpeg process. Re-anchor it once the
						// visual relay has an actual frame, otherwise it starts from the old
						// load position and remains audibly behind the video for the whole track.
						if (state.streamKind == PlaybackStreamKind.YOUTUBE && !state.userPaused) {
							bumpAudioSyncTokenLocked(state);
							speakerRefreshNeeded = true;
						}
						state.progress.complete("READY");
						shouldFadeProgress = true;
						shouldRender = true;
					}
				} else {
					state.progress.setIndeterminate(result.snapshot().live() ? "LIVE" : "LOADING");
					shouldRender = true;
				}
				// Keep YouTube playback on the GIF-like path: new frames should queue the next render,
				// not invalidate an in-flight large-screen render job every poll.
				shouldReschedule = !result.snapshot().ended();
				if (result.snapshot().ended()) {
					if (isYoutubeFamilyMode(state.mode)) {
						queueAdvanceIndex = endedQueueAdvanceIndexLocked(state);
					} else if (state.mode == ScreenViewMode.GALLERY && state.gallerySurfaceMode == GallerySurfaceMode.PLAYER) {
						galleryAdvanceIndex = endedGalleryAdvanceIndexLocked(state);
					}
				}
			} else {
				state.loading = false;
				state.statusText = sanitizeMediaError(result.error());
				state.progress.clear();
				shouldBumpVersion = true;
				shouldRender = true;
				speakerRefreshNeeded = true;
			}
			if (shouldBumpVersion) {
				state.version++;
			}
		}
		maybeCompleteYoutubeDownload(server, result.screenKey());
		if (queueAdvanceIndex != null) {
			if (state.mode == ScreenViewMode.YOUTUBE_MUSIC) {
				startYoutubeMusicQueuePlayback(server, result.screenKey(), null, queueAdvanceIndex);
			} else {
				startYoutubeQueuePlayback(server, result.screenKey(), null, queueAdvanceIndex);
			}
			return;
		}
		if (galleryAdvanceIndex != null) {
			continueGalleryPlaybackAtIndex(server, result.screenKey(), galleryAdvanceIndex);
			return;
		}
		if (speakerRefreshNeeded) {
			refreshConnectedSpeakersNow(server, result.screenKey());
		}
		if (shouldRender && hasNearbyMediaViewer(server, result.screenKey())) {
			requestRuntimeRender(server, result.screenKey());
		}
		if (shouldFadeProgress) {
			scheduleProgressFadeRenders(server, result.screenKey());
		}
		if (shouldReschedule) {
			scheduleNextMediaFrame(server, result.screenKey());
		}
	}

	static Integer endedQueueAdvanceIndexLocked(MediaRuntimeState state) {
		if (state == null || state.youtubeQueue.isEmpty()) {
			return null;
		}
		int currentIndex = normalizeYoutubeQueueIndexLocked(state, Math.max(0, state.youtubeQueueIndex));
		return switch (resolvedRepeatModeLocked(state)) {
			case ONE -> currentIndex;
			case ALL -> normalizeYoutubeQueueIndexLocked(state, currentIndex + 1);
			case OFF -> currentIndex + 1 < state.youtubeQueue.size() ? currentIndex + 1 : null;
		};
	}

	static Integer endedGalleryAdvanceIndexLocked(MediaRuntimeState state) {
		if (state == null
				|| state.mode != ScreenViewMode.GALLERY
				|| state.gallerySurfaceMode != GallerySurfaceMode.PLAYER
				|| state.galleryItems.isEmpty()) {
			return null;
		}
		return adjacentGalleryPlaybackIndexLocked(state, 1);
	}

	static void continueGalleryPlaybackAtIndex(MinecraftServer server, ScreenRuntimeKey key, int requestedIndex) {
		if (server == null || key == null) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return;
		}
		ScreenComponent component = resolveScreenComponent(server, key);
		UiLayout layout = component != null ? createUiLayout(component.width(), component.height()) : createUiLayout(1, 1);
		String title = null;
		String url = null;
		String localMediaKey = null;
		GalleryItemKind kind = GalleryItemKind.MEDIA;
		boolean selected = false;
		synchronized (state) {
			int index = normalizeGalleryIndexLocked(state, requestedIndex);
			if (index < 0 || index >= state.galleryItems.size()) {
				return;
			}
			GalleryItem item = state.galleryItems.get(index);
			if (item == null) {
				return;
			}
			kind = effectiveGalleryItemKind(item);
			title = item.title();
			url = item.url();
			localMediaKey = item.localMediaKey();
			if (kind != GalleryItemKind.YOUTUBE && selectGalleryItemLocked(state, index, layout)) {
				state.statusText = "";
				state.version++;
				selected = true;
			}
		}
		if (selected) {
			requestRuntimeRender(server, key);
			resumeMediaPlaybackIfNeeded(server, key);
			return;
		}
		if (kind == GalleryItemKind.YOUTUBE && url != null && !url.isBlank()) {
			startGalleryYoutubePlayback(server, key, null, title, url, requestedIndex);
			return;
		}
		if (url != null && !url.isBlank()) {
			scheduleGalleryItemLoad(server, key, title, url, localMediaKey, kind, true, requestedIndex);
		}
	}

	static void cleanupMediaSessions(MinecraftServer server) {
		for (ScreenRuntimeKey key : Set.copyOf(MEDIA_STATES.keySet())) {
			if (!isMediaSessionAlive(server, key)) {
				closeMediaSession(server, key);
			} else {
				discardEmptyIdleMediaSession(server, key);
			}
		}
		PENDING_MEDIA_LINKS.entrySet().removeIf(entry -> !isMediaSessionAlive(server, entry.getValue().screenKey()));
		PENDING_GALLERY_RENAMES.entrySet().removeIf(entry -> !isMediaSessionAlive(server, entry.getValue().screenKey()));
		IN_FLIGHT_MEDIA_LINKS.entrySet().removeIf(entry -> !isMediaSessionAlive(server, entry.getValue().screenKey()));
	}

	static void discardEmptyIdleMediaSession(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		ScreenComponent component = resolveScreenComponent(server, key);
		if (component == null || !component.runtimeKey().equals(key)) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return;
		}
		boolean removed = false;
		long now = System.currentTimeMillis();
		synchronized (state) {
			if (emptyIdleMediaStateCanBeDiscardedLocked(state, now)) {
				removed = MEDIA_STATES.remove(key, state);
			}
		}
		if (!removed) {
			return;
		}
		levelState(key.dimension()).connectedCameraPositions().remove(key);
		clearMediaSessionBindings(server, key);
	}

	static boolean emptyIdleMediaStateCanBeDiscardedLocked(MediaRuntimeState state, long now) {
		if (state == null) {
			return false;
		}
		if (state.activeRenderJobs > 0
				|| state.rerenderRequested
				|| state.lastDispatchKey != null
				|| state.playbackFuture != null
				|| state.backgroundFuture != null) {
			return false;
		}
		if (state.loading
				|| state.waitingForLink
				|| state.wallpaperLoading
				|| state.playerBackgroundLoading
				|| state.liveCameraCaptureInFlight
				|| state.liveCameraDecodeScheduled
				|| state.liveCameraApplyScheduled
				|| !state.galleryLoadingUrls.isEmpty()
				|| state.galleryPreloadStatusRefreshScheduled
				|| state.youtubeQueueCacheStatusRefreshScheduled
				|| state.pendingAudioPauseState != null
				|| state.pendingAudioPositionActive) {
			return false;
		}
		if (state.progress.snapshot().visible()) {
			return false;
		}
		if (state.downloadInProgress
				|| (state.downloadCompletedUrl != null && now < state.downloadCompletedUntilMillis)) {
			return false;
		}
		if (state.loadedMedia != null
				|| state.streamFrame != null
				|| state.loadingBackdropFrame != null
				|| state.wallpaperMedia != null
				|| state.playerBackgroundMedia != null
				|| state.pendingLiveCameraPixels != null
				|| state.liveCameraBufferedTiles != null
				|| state.liveCameraDisplayedTiles != null
				|| state.pendingLiveCameraPreparedTiles != null) {
			return false;
		}
		if (state.streamKind != PlaybackStreamKind.NONE
				|| !emptyText(state.sourceUrl)
				|| !emptyText(state.relaySessionId)
				|| !emptyText(state.audioStreamUrl)
				|| !emptyText(state.wallpaperUrl)
				|| !emptyText(state.playerBackgroundUrl)
				|| !emptyText(state.pendingLiveCameraApplyUrl)
				|| !emptyText(state.galleryPendingOpenUrl)
				|| !emptyText(state.statusText)
				|| !emptyText(state.mediaTitle)
				|| !emptyText(state.mediaSubtitle)) {
			return false;
		}
		if (!state.galleryItems.isEmpty()
				|| !state.youtubeQueue.isEmpty()
				|| !state.retainedYoutubePreloadUrls.isEmpty()
				|| !state.retainedYoutubeMusicUrls.isEmpty()
				|| !state.youtubeMusicShuffleOrder.isEmpty()) {
			return false;
		}
		return !state.youtubeQueueOpen
				&& !state.galleryDeleteConfirmOpen
				&& !state.playerBackgroundMenuOpen
				&& !state.playerBackgroundGalleryPickerOpen
				&& !state.preserveRuntimeOnNextViewModeTransition;
	}

	static boolean emptyText(String value) {
		return value == null || value.isBlank();
	}

	static void cleanupExpiredMediaFocus() {
		long now = System.currentTimeMillis();
		PLAYER_MEDIA_FOCUS.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
	}

	static void markMediaFocus(ServerPlayer player, ScreenRuntimeKey key) {
		if (player == null || key == null) {
			return;
		}
		PLAYER_MEDIA_FOCUS.put(player.getUUID(), new PlayerMediaFocus(key, System.currentTimeMillis() + MEDIA_SCROLL_FOCUS_TIMEOUT_MS));
	}

	public static boolean onPlayerHotbarScroll(ServerPlayer player, int previousSlot, int currentSlot) {
		if (player == null) {
			return false;
		}
		ScreenComponent component = findObservedScrollableComponent(player);
		if (component == null) {
			return false;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return false;
		}
		if (component.viewMode() == ScreenViewMode.HOME) {
			UiLayout layout = createUiLayout(component.width(), component.height());
			int delta = normalizeHotbarDelta(previousSlot, currentSlot);
			if (delta == 0) {
				return false;
			}
			int maxScroll = homeMaxScroll(layout);
			if (maxScroll <= 0) {
				return false;
			}
			int nextScroll = clampInt(component.launcherPage() - delta, 0, maxScroll);
			if (nextScroll == component.launcherPage()) {
				return false;
			}
			ItemFrame anchor = component.frameCoords().keySet().stream().filter(Entity::isAlive).findFirst().orElse(null);
			if (anchor == null || !(player.level() instanceof ServerLevel serverLevel)) {
				return false;
			}
			applyTransientComponentViewState(server, serverLevel, component, component.viewMode(), nextScroll);
			return true;
		}
		MediaRuntimeState state = MEDIA_STATES.get(component.runtimeKey());
		if (state == null) {
			return false;
		}

		int delta = normalizeHotbarDelta(previousSlot, currentSlot);
		if (delta == 0) {
			return false;
		}

		boolean handled = false;
		Long youtubeSeekTargetMs = null;
		String youtubeSeekSessionId = null;
		synchronized (state) {
			if (!state.loading
					&& !state.waitingForLink
					&& isLibraryAppMode(state.mode)
					&& state.gallerySurfaceMode == GallerySurfaceMode.BROWSER
					&& !state.galleryItems.isEmpty()) {
				UiLayout layout = createUiLayout(component.width(), component.height());
				int visibleRows = mediaGalleryVisibleRows(layout);
				int totalRows = mediaGalleryTotalRows(galleryBrowserVisibleIndexesLocked(state).size(), layout);
				int maxScroll = Math.max(0, totalRows - visibleRows);
				if (maxScroll > 0) {
					state.galleryScroll = clampInt(state.galleryScroll - delta, 0, maxScroll);
					state.version++;
					handled = true;
				}
			} else if (state.overlayMode == MediaOverlayMode.CONTROLS
					&& !state.loading
					&& !state.waitingForLink) {
				if (isYoutubeFamilyMode(state.mode) && state.youtubeQueueOpen && !state.youtubeQueue.isEmpty()) {
					int visibleRows = youtubeQueueVisibleRowsPreview(state);
					int maxScroll = Math.max(0, state.youtubeQueue.size() - visibleRows);
					if (maxScroll > 0) {
						state.youtubeQueueScroll = clampInt(state.youtubeQueueScroll - delta, 0, maxScroll);
						state.version++;
						handled = true;
					}
				} else if (isStreamPlaybackLocked(state) && canSeekTimelineLocked(state)) {
					cancelPlaybackLocked(state);
					long duration = Math.max(1L, state.durationMs);
					long seekStep = Math.max(YOUTUBE_SCROLL_SEEK_MS, duration / 120L);
					youtubeSeekTargetMs = clampLong(state.positionMs + delta * seekStep, 0L, duration);
					markPendingAudioPositionLocked(state, youtubeSeekTargetMs);
					bumpAudioSyncTokenLocked(state);
					markStreamSeekBufferingLocked(state);
					youtubeSeekSessionId = state.relaySessionId;
					handled = true;
				} else if (state.loadedMedia != null && state.loadedMedia.animated() && state.loadedMedia.frameCount() > 1) {
					cancelPlaybackLocked(state);
					int seekFrames = Math.max(1, state.loadedMedia.frameCount() / 60);
					state.frameIndex = Math.floorMod(state.frameIndex + delta * seekFrames, state.loadedMedia.frameCount());
					state.version++;
					handled = true;
				}
			}
		}
		if (!handled) {
			return false;
		}

		requestComponentRender(server, component, component.viewMode(), component.launcherPage());
		if (youtubeSeekTargetMs != null) {
			long seekTargetMs = youtubeSeekTargetMs;
			String sessionId = youtubeSeekSessionId != null && !youtubeSeekSessionId.isBlank()
					? youtubeSeekSessionId
					: relaySessionId(component.runtimeKey());
			refreshConnectedSpeakersNow(server, component.runtimeKey());
			ensureExecutors();
			CompletableFuture.runAsync(() -> {
				try {
					MonitorYoutubeRelayClient.seek(sessionId, seekTargetMs);
				} catch (Exception ignored) {
				}
			}, mediaIoExecutor).thenRun(() -> server.execute(() -> {
				refreshConnectedSpeakersNow(server, component.runtimeKey());
				scheduleYoutubeRefresh(server, component.runtimeKey(), 0L);
			}));
		} else {
			resumeMediaPlaybackIfNeeded(server, component.runtimeKey());
		}
		return true;
	}
}
