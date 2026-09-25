package com.lostglade.server;

import static com.lostglade.server.MonitorScreenMessages.*;
import static com.lostglade.server.MonitorScreenMapTransport.*;
import static com.lostglade.server.MonitorScreenBackgroundLoader.*;
import static com.lostglade.server.MonitorScreenGalleryRuntime.*;
import static com.lostglade.server.MonitorScreenLiveCameraPlayback.*;
import static com.lostglade.server.MonitorScreenLiveSources.*;
import static com.lostglade.server.MonitorScreenMediaActions.*;
import static com.lostglade.server.MonitorScreenMediaFrameRuntime.*;
import static com.lostglade.server.MonitorScreenMediaHydration.*;
import static com.lostglade.server.MonitorScreenMediaLoadResults.*;
import static com.lostglade.server.MonitorScreenMediaSessionLifecycle.*;
import static com.lostglade.server.MonitorScreenPlaybackScheduler.*;
import static com.lostglade.server.MonitorScreenTickScheduler.*;
import static com.lostglade.server.MonitorScreenWireConnectivity.*;
import static com.lostglade.server.MonitorScreenYoutubeQueueRuntime.*;

import com.lostglade.Lg2;
import com.lostglade.block.CameraBlock;
import com.lostglade.block.ModBlocks;
import com.lostglade.config.Lg2Config;
import com.lostglade.item.ModItems;
import com.lostglade.item.MonitorItem;
import com.lostglade.item.PhotoPrintData;
import com.lostglade.util.ItemDisplayHitboxHelper;
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
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
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
import java.util.function.Predicate;

public final class MonitorScreenSystem {
	static final String IT_SCREEN = "it_screen";
	static final String SCREEN_ROOT_TAG = "lg2_monitor_screen";
	static final String LINK_LOCKED_TAG = "link_locked";
	static final String ATTACHMENT_MASK_TAG = "attachment_mask";
	static final String GROUP_ID_TAG = "group_id";
	static final String GRID_WIDTH_TAG = "grid_width";
	static final String GRID_HEIGHT_TAG = "grid_height";
	static final String TILE_X_TAG = "tile_x";
	static final String TILE_Y_TAG = "tile_y";
	static final String CONNECTION_MASK_TAG = "connection_mask";
	static final String POWERED_TAG = "powered";
	static final String VIEW_MODE_TAG = "view_mode";
	static final String LAUNCHER_PAGE_TAG = "launcher_page";
	static final String DISPLAY_ROOT_TAG = "lg2_monitor_display";
	static final String PERSISTED_MEDIA_ROOT_TAG = "lg2_monitor_media";
	static final String PERSISTED_GALLERY_COUNT_TAG = "gallery_count";
	static final String PERSISTED_GALLERY_ITEM_PREFIX = "gallery_item_";
	static final String PERSISTED_GALLERY_KIND_TAG = "kind";
	static final String PERSISTED_GALLERY_TITLE_TAG = "title";
	static final String PERSISTED_GALLERY_SUBTITLE_TAG = "subtitle";
	static final String PERSISTED_GALLERY_URL_TAG = "url";
	static final String PERSISTED_GALLERY_LOCAL_MEDIA_TAG = "local_media";
	static final String PERSISTED_WALLPAPER_URL_TAG = "wallpaper_url";
	static final String PERSISTED_WALLPAPER_SCALE_TAG = "wallpaper_scale";
	static final String PERSISTED_WALLPAPER_BACKGROUND_MODE_TAG = "wallpaper_background_mode";
	static final String PERSISTED_PLAYER_BACKGROUND_MODE_TAG = "player_background_mode";
	static final String PERSISTED_PLAYER_BACKGROUND_URL_TAG = "player_background_url";
	static final String PERSISTED_PLAYER_BACKGROUND_SCALE_TAG = "player_background_scale";
	static final String CACHE_ROOT_DIR_NAME = "cache";
	static final String CACHE_NAMESPACE_DIR_NAME = "lg2-monitor";
	static final String POS_TAG_PREFIX = "lg2_monitor_display_pos:";
	static final String FACING_TAG_PREFIX = "lg2_monitor_display_facing:";
	static final int MAP_SIZE = 128;
	static final int MONITOR_VIEWPORT_SAFE_INSET = 6;
	static final int MAP_TRANSPARENT_ALPHA_THRESHOLD = 12;
	static final int PHOTO_MAP_CENTER = 30_000_000;
	static final int CONNECTION_LEFT = 1;
	static final int CONNECTION_RIGHT = 2;
	static final int CONNECTION_UP = 4;
	static final int CONNECTION_DOWN = 8;
	static final int CONNECTION_ALL = CONNECTION_LEFT | CONNECTION_RIGHT | CONNECTION_UP | CONNECTION_DOWN;
	static final double DISPLAY_SEARCH_RADIUS = 0.8D;
	static final double DISPLAY_PLANE_OFFSET = 0.49D;
	static final double TOUCH_TOLERANCE = 0.08D;
	static final String SCREEN_OFF_RESOURCE = "/monitor/screen_off.png";
	static final String SCREEN_ON_RESOURCE = "/monitor/screen_on.png";
	static final long PROGRESS_RENDER_INTERVAL_MS = 300L;
	static final int PROGRESS_FADE_RENDER_STEPS = 5;
	static final long MEDIA_SCROLL_FOCUS_TIMEOUT_MS = 6000L;
	static final double MEDIA_CONTROL_DISTANCE = 6.0D;
	static final long YOUTUBE_SCROLL_SEEK_MS = 5000L;
	static final int YOUTUBE_PRELOAD_PREVIOUS_COUNT = 4;
	static final int YOUTUBE_PRELOAD_NEXT_COUNT = 8;
	static final long MEDIA_ACTION_SPINNER_MIN_MILLIS = 150L;
	static final Set<String> GALLERY_VIDEO_EXTENSIONS = Set.of(".mp4", ".m4v", ".mov", ".webm");
	static final Set<String> GALLERY_AUDIO_EXTENSIONS = Set.of(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac", ".weba");
	static final long MEDIA_ACTION_COMPLETE_VISIBLE_MILLIS = 1600L;
	static final long YOUTUBE_FULLY_BUFFERED_TOLERANCE_MS = 1500L;
	static final long WALLPAPER_IDLE_VISIBILITY_RECHECK_MS = 500L;
	static final int MAX_SCREEN_SYNC_OPERATIONS_PER_TICK = 24;
	static final int MAX_POWER_REFRESHES_PER_TICK = 16;
	static final int MAX_SPEAKER_REFRESHES_PER_TICK = 12;
	static final int MAX_CAMERA_REFRESHES_PER_TICK = 12;
	static final int POWER_REFRESH_FALLBACK_INTERVAL_TICKS = 5;
	static final long MEDIA_SESSION_CLEANUP_INTERVAL_TICKS = 40L;
	static final long MEDIA_ACTIONBAR_REFRESH_INTERVAL_TICKS = 20L;
	static final long MEDIA_FOCUS_CLEANUP_INTERVAL_TICKS = 20L;
	static final long RENDER_CACHE_CLEANUP_INTERVAL_TICKS = 200L;
	static final int MAX_TILE_CACHE_ENTRIES = 128;
	static final boolean DEBUG_AIM_CURSOR_ENABLED = false;
	static final int DEBUG_AIM_CURSOR_REFRESH_TICKS = 2;
	static final int DEBUG_AIM_CURSOR_MOVE_THRESHOLD_PIXELS = 1;
	static final String CAMERA_GALLERY_URL_PREFIX = "lg2-camera:";
	static final String LIVE_CAMERA_GALLERY_URL_PREFIX = MonitorSberDronesCatalog.URL_PREFIX;
	static final double RENDERER_BOT_EYE_HEIGHT = 1.62D;
	static final int LIVE_CAMERA_PREVIEW_SIZE = 128;
	static final int LIVE_CAMERA_FOV_DEGREES = 70;
	static final int LIVE_CAMERA_TARGET_FPS = 20;
	static final long LIVE_CAMERA_HEALTH_CHECK_INTERVAL_MS = 500L;
	static final long LIVE_CAMERA_GALLERY_SYNC_INTERVAL_MS = 400L;
	static final long LIVE_CAMERA_PREVIEW_DECODE_INTERVAL_MS = 200L;
	static final Map<RenderCacheKey, byte[][]> TILE_CACHE = new ConcurrentHashMap<>();
	static final Map<OverlayWindowCacheKey, OverlayWindowRenderState> OVERLAY_WINDOW_CACHE = new ConcurrentHashMap<>();
	static final Map<OverlayWindowFamilyKey, BufferedImage> OVERLAY_WINDOW_FAMILY_CACHE = new ConcurrentHashMap<>();
	static final Map<OverlayWindowFamilyKey, BufferedImage> OVERLAY_WINDOW_PLACEHOLDER_CACHE = new ConcurrentHashMap<>();
	static final Map<String, BufferedImage> APP_ICON_CACHE = new ConcurrentHashMap<>();
	static final Map<PlayerUiIcon, BufferedImage> PLAYER_UI_ICON_CACHE = new ConcurrentHashMap<>();
	static final Map<PlayerUiIconTintKey, BufferedImage> PLAYER_UI_ICON_TINT_CACHE = new ConcurrentHashMap<>();
	static final Map<ScreenRuntimeKey, MediaRuntimeState> MEDIA_STATES = new ConcurrentHashMap<>();
	static final Map<ScreenRuntimeKey, UiPoint> DEBUG_AIM_CURSORS = new ConcurrentHashMap<>();
	static final Map<UUID, PendingMediaLinkRequest> PENDING_MEDIA_LINKS = new ConcurrentHashMap<>();
	static final Map<UUID, PendingGalleryRenameRequest> PENDING_GALLERY_RENAMES = new ConcurrentHashMap<>();
	static final Map<UUID, InFlightMediaLinkRequest> IN_FLIGHT_MEDIA_LINKS = new ConcurrentHashMap<>();
	static final Map<UUID, ScreenRuntimeKey> ACTIVE_MEDIA_ACTIONBARS = new ConcurrentHashMap<>();
	static final Map<UUID, PlayerMediaFocus> PLAYER_MEDIA_FOCUS = new ConcurrentHashMap<>();
	static final Map<ResourceKey<Level>, MonitorLevelState> LEVEL_STATES = new ConcurrentHashMap<>();
	static final Set<ScreenRuntimeKey> CLOSING_MEDIA_SESSIONS = ConcurrentHashMap.newKeySet();
	static volatile ExecutorService renderExecutor;
	static volatile ExecutorService quantizeExecutor;
	static volatile ExecutorService mediaIoExecutor;
	static volatile ExecutorService liveCameraExecutor;
	static volatile ExecutorService overlayWindowExecutor;
	static volatile ScheduledExecutorService mediaScheduler;
	static volatile BufferedImage offBaseImage;
	static volatile BufferedImage onBaseImage;
	static final Composite REPLACING_IMAGE_COMPOSITE = AlphaComposite.getInstance(AlphaComposite.SRC);
	static final Composite PRESERVE_TRANSPARENCY_COMPOSITE = AlphaComposite.getInstance(AlphaComposite.SRC_ATOP);

	private record DebugAimTarget(ScreenRuntimeKey runtimeKey, UiPoint point, double distanceSqr) {
	}

	/**
	 * The single authoritative result for passive screen interactions (hover and
	 * hotbar scrolling).  Unlike an item-frame AABB hit, this is calculated at
	 * the same display plane that is used for an actual screen click.
	 */
	static record ObservedScreenTouch(
			ScreenComponent component,
			ItemFrame frame,
			TileCoord tileCoord,
			UiPoint touchPoint,
			double distanceSqr
	) {
	}

	private MonitorScreenSystem() {
	}

	public static void register() {
		ensureExecutors();
		UseEntityCallback.EVENT.register(MonitorScreenSystem::onUseEntity);
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(MonitorScreenChatLinkController::onAllowChatMessage);
		ServerEntityEvents.ENTITY_LOAD.register(MonitorScreenSystem::onEntityLoad);
		ServerEntityEvents.ENTITY_UNLOAD.register(MonitorScreenSystem::onEntityUnload);
		ServerChunkEvents.CHUNK_LOAD.register(MonitorScreenSystem::onChunkLoad);
		ServerChunkEvents.CHUNK_UNLOAD.register(MonitorScreenSystem::onChunkUnload);
		ServerTickEvents.END_SERVER_TICK.register(MonitorScreenTickScheduler::tick);
		ServerLifecycleEvents.SERVER_STARTED.register(MonitorScreenSystem::configureCacheDirectories);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			MonitorYoutubeRelayClient.shutdown();
			MonitorYoutubeMusicCache.shutdown();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearMonitorRuntime());
	}

	static void configureCacheDirectories(MinecraftServer server) {
		if (server == null) {
			return;
		}
		Path cacheRoot = monitorCacheRoot();
		MonitorMediaApp.setCacheDirectory(cacheRoot.resolve("media"));
		MonitorYoutubeRelayClient.setCacheDirectory(cacheRoot.resolve("youtube-preload"));
		MonitorYoutubeMusicCache.setCacheDirectory(cacheRoot.resolve("youtube-music"));
	}

	static Path monitorCacheRoot() {
		return Path.of(System.getProperty("user.dir"), CACHE_ROOT_DIR_NAME, CACHE_NAMESPACE_DIR_NAME);
	}

	static void ensureExecutors() {
		if (renderExecutor == null) {
			renderExecutor = Executors.newFixedThreadPool(monitorRenderThreads(), daemonThreadFactory("lg2-monitor-render"));
		}
		if (quantizeExecutor == null) {
			quantizeExecutor = Executors.newFixedThreadPool(monitorTileQuantizerThreads(), daemonThreadFactory("lg2-monitor-quantize"));
		}
		if (mediaIoExecutor == null) {
			mediaIoExecutor = Executors.newFixedThreadPool(monitorMediaIoThreads(), daemonThreadFactory("lg2-monitor-io"));
		}
		if (liveCameraExecutor == null) {
			liveCameraExecutor = Executors.newFixedThreadPool(monitorLiveCameraThreads(), daemonThreadFactory("lg2-monitor-live-camera"));
		}
		if (overlayWindowExecutor == null) {
			overlayWindowExecutor = Executors.newFixedThreadPool(monitorOverlayWindowThreads(), daemonThreadFactory("lg2-monitor-window"));
		}
		if (mediaScheduler == null) {
			mediaScheduler = Executors.newScheduledThreadPool(monitorMediaSchedulerThreads(), daemonThreadFactory("lg2-monitor-scheduler"));
		}
	}

	static int monitorRenderThreads() {
		Lg2Config.ConfigData config = Lg2Config.get();
		int configured = config != null ? Math.max(1, config.monitorRenderThreads) : recommendedMonitorRenderThreads();
		return Math.min(configured, maxMonitorWorkerThreads());
	}

	static int monitorTileQuantizerThreads() {
		Lg2Config.ConfigData config = Lg2Config.get();
		int configured = config != null ? Math.max(1, config.monitorTileQuantizerThreads) : recommendedMonitorQuantizerThreads();
		return Math.min(configured, maxMonitorWorkerThreads());
	}

	static int monitorMediaIoThreads() {
		Lg2Config.ConfigData config = Lg2Config.get();
		int configured = config != null ? Math.max(1, config.monitorMediaIoThreads) : recommendedMonitorMediaIoThreads();
		return Math.min(configured, maxMonitorIoThreads());
	}

	static int monitorMediaSchedulerThreads() {
		return Math.max(1, Math.min(2, monitorMediaIoThreads()));
	}

	static int monitorLiveCameraThreads() {
		return recommendedMonitorLiveCameraThreads();
	}

	static int monitorOverlayWindowThreads() {
		return recommendedMonitorOverlayThreads();
	}

	static int availableWorkerCores() {
		return Math.max(1, Runtime.getRuntime().availableProcessors());
	}

	static int maxMonitorWorkerThreads() {
		return Math.max(1, availableWorkerCores() - 1);
	}

	static int maxMonitorIoThreads() {
		return Math.max(2, Math.min(maxMonitorWorkerThreads(), Math.max(2, availableWorkerCores() / 2)));
	}

	static int recommendedMonitorRenderThreads() {
		return Math.max(1, Math.min(4, Math.max(1, (availableWorkerCores() - 1) / 2)));
	}

	static int recommendedMonitorQuantizerThreads() {
		return Math.max(1, Math.min(3, Math.max(1, (availableWorkerCores() - 1) / 2)));
	}

	static int recommendedMonitorMediaIoThreads() {
		return Math.max(1, Math.min(2, Math.max(1, availableWorkerCores() / 4)));
	}

	static int recommendedMonitorLiveCameraThreads() {
		return Math.max(2, Math.min(8, maxMonitorWorkerThreads()));
	}

	static int recommendedMonitorOverlayThreads() {
		return 1;
	}

	static int monitorMapUpdateRadiusBlocks() {
		Lg2Config.ConfigData config = Lg2Config.get();
		return config != null ? Math.max(16, config.monitorMapUpdateRadiusBlocks) : 128;
	}

	static long youtubePollActiveIntervalMs() {
		Lg2Config.ConfigData config = Lg2Config.get();
		return config != null ? Math.max(33L, config.monitorYoutubePollActiveIntervalMs) : 50L;
	}

	static long youtubePollIdleIntervalMs() {
		Lg2Config.ConfigData config = Lg2Config.get();
		return config != null ? Math.max(100L, config.monitorYoutubePollIdleIntervalMs) : 200L;
	}

	static ThreadFactory daemonThreadFactory(String baseName) {
		return runnable -> {
			Thread thread = new Thread(runnable, baseName);
			thread.setDaemon(true);
			thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
			return thread;
		};
	}

	static MonitorLevelState levelState(ResourceKey<Level> dimension) {
		return LEVEL_STATES.computeIfAbsent(dimension, MonitorLevelState::new);
	}

	static void clearMonitorRuntime() {
		for (MediaRuntimeState state : MEDIA_STATES.values()) {
			if (state == null) {
				continue;
			}
			synchronized (state) {
				cancelRuntimePlaybackLocked(state);
				clearPendingLiveCameraApply(state);
			}
		}
		MEDIA_STATES.clear();
		PENDING_MEDIA_LINKS.clear();
		PENDING_GALLERY_RENAMES.clear();
		IN_FLIGHT_MEDIA_LINKS.clear();
		ACTIVE_MEDIA_ACTIONBARS.clear();
		PLAYER_MEDIA_FOCUS.clear();
		DEBUG_AIM_CURSORS.clear();
		MonitorMaxRuntime.clearRuntime();
		MonitorCameraRuntime.clearRuntime();
		MonitorScrollAnimationSystem.clear();
		TILE_CACHE.clear();
		OVERLAY_WINDOW_CACHE.clear();
		OVERLAY_WINDOW_FAMILY_CACHE.clear();
		OVERLAY_WINDOW_PLACEHOLDER_CACHE.clear();
		clearRenderedMapFrames();
		APP_ICON_CACHE.clear();
		PLAYER_UI_ICON_CACHE.clear();
		PLAYER_UI_ICON_TINT_CACHE.clear();
		LEVEL_STATES.clear();
		shutdownExecutor(renderExecutor);
		renderExecutor = null;
		shutdownExecutor(quantizeExecutor);
		quantizeExecutor = null;
		shutdownExecutor(mediaIoExecutor);
		mediaIoExecutor = null;
		shutdownExecutor(liveCameraExecutor);
		liveCameraExecutor = null;
		shutdownExecutor(overlayWindowExecutor);
		overlayWindowExecutor = null;
		shutdownScheduler(mediaScheduler);
		mediaScheduler = null;
		offBaseImage = null;
		onBaseImage = null;
	}

	static void shutdownExecutor(ExecutorService executor) {
		if (executor == null) {
			return;
		}
		executor.shutdownNow();
	}

	static void shutdownScheduler(ScheduledExecutorService scheduler) {
		if (scheduler == null) {
			return;
		}
		scheduler.shutdownNow();
	}

	static void onEntityLoad(Entity entity, ServerLevel level) {
		if (level == null || entity == null) {
			return;
		}
		if (entity instanceof ItemFrame frame && readScreenState(frame.getItem()) != null) {
			trackScreenFrame(level, frame);
			return;
		}
		if (entity instanceof Display.ItemDisplay) {
		}
		if (entity instanceof Display.ItemDisplay display && display.getTags().contains(DISPLAY_ROOT_TAG)) {
			ItemDisplayHitboxHelper.clear(display);
			BlockPos pos = parsePositionTag(display.getTags());
			Direction facing = parseFacingTag(display.getTags());
			if (pos == null || facing == null || findScreenFrame(level, pos, facing) == null) {
				display.discard();
			}
		}
	}

	static void onEntityUnload(Entity entity, ServerLevel level) {
		if (level != null && entity instanceof Display.ItemDisplay) {
		}
		if (level == null || !(entity instanceof ItemFrame frame) || readScreenState(frame.getItem()) == null) {
			return;
		}
		untrackScreenFrame(level, new ScreenKey(frame.blockPosition(), frame.getDirection()), false);
	}

	static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		scanChunkForScreenFrames(level, chunk);
		cleanupChunkDisplays(level, chunk);
	}

	static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
		if (level == null || chunk == null) {
			return;
		}
		int chunkX = chunk.getPos().x;
		int chunkZ = chunk.getPos().z;
		MonitorLevelState state = levelState(level.dimension());
		for (ScreenKey key : new ArrayList<>(state.knownFrames())) {
			if (SectionPos.blockToSectionCoord(key.pos().getX()) != chunkX || SectionPos.blockToSectionCoord(key.pos().getZ()) != chunkZ) {
				continue;
			}
			untrackScreenFrame(level, key, false);
		}
	}

	static void scanChunkForScreenFrames(ServerLevel level, LevelChunk chunk) {
		if (level == null || chunk == null) {
			return;
		}
		AABB box = chunkEntityBox(level, chunk);
		for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, box, candidate -> readScreenState(candidate.getItem()) != null)) {
			trackScreenFrame(level, frame);
		}
	}

	static void cleanupChunkDisplays(ServerLevel level, LevelChunk chunk) {
		if (level == null || chunk == null) {
			return;
		}
		AABB box = chunkEntityBox(level, chunk);
		for (Display.ItemDisplay display : level.getEntitiesOfClass(Display.ItemDisplay.class, box, candidate -> candidate.getTags().contains(DISPLAY_ROOT_TAG))) {
			ItemDisplayHitboxHelper.clear(display);
			BlockPos pos = parsePositionTag(display.getTags());
			Direction facing = parseFacingTag(display.getTags());
			if (pos == null || facing == null || findScreenFrame(level, pos, facing) == null) {
				display.discard();
			}
		}
	}

	static AABB chunkEntityBox(ServerLevel level, LevelChunk chunk) {
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		return new AABB(
				minX - 1,
				level.getMinY(),
				minZ - 1,
				minX + 17,
				level.getMaxY(),
				minZ + 17
		);
	}

	static void trackScreenFrame(ServerLevel level, ItemFrame frame) {
		if (level == null || frame == null || readScreenState(frame.getItem()) == null) {
			return;
		}
		MonitorLevelState state = levelState(level.dimension());
		ScreenKey key = new ScreenKey(frame.blockPosition(), frame.getDirection());
		state.knownFrames().add(key);
		enqueueScreenSync(level, key);
	}

	static void untrackScreenFrame(ServerLevel level, ScreenKey key, boolean permanentRemoval) {
		if (level == null || key == null) {
			return;
		}
		if (permanentRemoval) {
			BluetoothLinkSystem.removeScreenEndpoint(level, key.pos(), key.direction());
		}
		MonitorLevelState state = levelState(level.dimension());
		state.knownFrames().remove(key);
		ScreenRuntimeKey runtimeKey = state.frameToRuntime().remove(key);
		if (runtimeKey != null) {
			invalidateCachedRuntime(level, runtimeKey, key, permanentRemoval);
		} else {
			enqueueNeighborSync(level, key.pos(), key.direction());
		}
	}

	static void enqueueScreenSync(ServerLevel level, ScreenKey key) {
		if (level == null || key == null) {
			return;
		}
		levelState(level.dimension()).enqueueDirtyFrame(key);
	}

	static void enqueueComponentSync(ServerLevel level, ScreenRuntimeKey key) {
		if (level == null || key == null) {
			return;
		}
		levelState(level.dimension()).enqueueDirtyRuntime(key);
	}

	static void enqueueCameraRefresh(ServerLevel level, ScreenRuntimeKey key) {
		if (level == null || key == null) {
			return;
		}
		levelState(level.dimension()).enqueueCameraRefreshRuntime(key);
	}

	static void enqueueNeighborSync(ServerLevel level, BlockPos pos, Direction facing) {
		if (level == null || pos == null || facing == null) {
			return;
		}
		Direction right = frameRight(facing);
		for (BlockPos candidatePos : List.of(
				pos,
				pos.relative(right),
				pos.relative(right.getOpposite()),
				pos.above(),
				pos.below()
		)) {
			enqueueScreenSync(level, new ScreenKey(candidatePos, facing));
		}
	}

	static void cacheComponent(ServerLevel level, ScreenComponent component) {
		if (level == null || component == null) {
			return;
		}
		MonitorLevelState state = levelState(level.dimension());
		ScreenComponent previousComponent = state.components().get(component.runtimeKey());
		Set<ScreenRuntimeKey> replacedRuntimes = new HashSet<>();
		for (ItemFrame frame : component.frameCoords().keySet()) {
			ScreenKey frameKey = new ScreenKey(frame.blockPosition(), frame.getDirection());
			state.knownFrames().add(frameKey);
			ScreenRuntimeKey previous = state.frameToRuntime().put(frameKey, component.runtimeKey());
			if (previous != null && !previous.equals(component.runtimeKey())) {
				replacedRuntimes.add(previous);
			}
		}
		for (ScreenRuntimeKey replaced : replacedRuntimes) {
			removeCachedRuntime(level.getServer(), state, replaced, component.runtimeKey(), false);
			closeMediaSession(level.getServer(), replaced);
		}
		if (previousComponent != null) {
			Set<ScreenKey> currentKeys = new HashSet<>();
			for (ItemFrame frame : component.frameCoords().keySet()) {
				currentKeys.add(new ScreenKey(frame.blockPosition(), frame.getDirection()));
			}
			for (ItemFrame frame : previousComponent.frameCoords().keySet()) {
				ScreenKey frameKey = new ScreenKey(frame.blockPosition(), frame.getDirection());
				if (currentKeys.contains(frameKey)) {
					continue;
				}
				state.frameToRuntime().remove(frameKey, component.runtimeKey());
				if (state.knownFrames().contains(frameKey)) {
					enqueueScreenSync(level, frameKey);
				}
			}
		}
		state.components().put(component.runtimeKey(), component);
		if (component.powered()) {
			state.enqueueCameraRefreshRuntime(component.runtimeKey());
		} else {
			state.connectedCameraPositions().remove(component.runtimeKey());
		}
		state.enqueuePowerRuntime(component.runtimeKey());
	}

	static void invalidateCachedRuntime(ServerLevel level, ScreenRuntimeKey runtimeKey, ScreenKey removedFrameKey, boolean permanentRemoval) {
		if (level == null || runtimeKey == null) {
			return;
		}
		ScreenComponent removed = removeCachedRuntime(level.getServer(), levelState(level.dimension()), runtimeKey, null, permanentRemoval);
		if (removed == null) {
			if (removedFrameKey != null) {
				enqueueNeighborSync(level, removedFrameKey.pos(), removedFrameKey.direction());
			}
			return;
		}
		if (permanentRemoval) {
			List<GalleryCacheCandidate> removedCacheCandidates = galleryCacheCandidatesForRemovedComponent(removed, MEDIA_STATES.get(runtimeKey));
			closeMediaSession(level.getServer(), runtimeKey);
			if (!removedCacheCandidates.isEmpty()) {
				scheduleGalleryCacheRelease(level.getServer(), removedCacheCandidates, runtimeKey);
			}
		}
		for (ItemFrame frame : removed.frameCoords().keySet()) {
			ScreenKey frameKey = new ScreenKey(frame.blockPosition(), frame.getDirection());
			if (removedFrameKey != null && removedFrameKey.equals(frameKey)) {
				continue;
			}
			if (levelState(level.dimension()).knownFrames().contains(frameKey)) {
				enqueueScreenSync(level, frameKey);
			}
		}
		if (removedFrameKey != null) {
			enqueueNeighborSync(level, removedFrameKey.pos(), removedFrameKey.direction());
		}
	}

	static ScreenComponent removeCachedRuntime(MinecraftServer server, MonitorLevelState state, ScreenRuntimeKey runtimeKey, ScreenRuntimeKey replacementKey, boolean contentDestroyed) {
		if (state == null || runtimeKey == null) {
			return null;
		}
		ScreenComponent removed = state.components().remove(runtimeKey);
		if (removed == null) {
			return null;
		}
		MonitorMaxRuntime.closeRuntime(server, runtimeKey, contentDestroyed);
		MonitorSupportRuntime.closeRuntime(server, runtimeKey, contentDestroyed);
		state.connectedCameraPositions().remove(runtimeKey);
		for (ItemFrame frame : removed.frameCoords().keySet()) {
			ScreenKey frameKey = new ScreenKey(frame.blockPosition(), frame.getDirection());
			if (replacementKey != null && replacementKey.equals(state.frameToRuntime().get(frameKey))) {
				continue;
			}
			state.frameToRuntime().remove(frameKey, runtimeKey);
		}
		return removed;
	}

	static ScreenComponent resolveScreenComponent(ServerLevel level, ItemFrame frame) {
		if (level == null || frame == null) {
			return null;
		}
		MonitorLevelState state = levelState(level.dimension());
		ScreenRuntimeKey runtimeKey = state.frameToRuntime().get(new ScreenKey(frame.blockPosition(), frame.getDirection()));
		if (runtimeKey != null) {
			ScreenComponent cached = state.components().get(runtimeKey);
			if (cached != null) {
				return cached;
			}
		}
		ScreenComponent component = collectComponent(level, frame, null);
		if (component != null) {
			cacheComponent(level, component);
		}
		return component;
	}

	static String bluetoothScreenId(ScreenComponent component) {
		if (component == null) {
			return null;
		}
		for (ItemFrame frame : component.frameCoords().keySet()) {
			String groupId = normalizedGroupId(readScreenState(frame.getItem()));
			if (groupId != null) {
				return groupId;
			}
		}
		return componentGroupId(component.runtimeKey());
	}

	static BluetoothLinkSystem.Endpoint bluetoothScreenEndpoint(ServerLevel level, ScreenComponent component) {
		if (level == null || component == null || component.runtimeKey() == null) {
			return null;
		}
		BluetoothLinkSystem.Endpoint endpoint = BluetoothLinkSystem.screenEndpoint(
				level.dimension(),
				component.runtimeKey().pos(),
				component.runtimeKey().facing(),
				bluetoothScreenId(component)
		);
		LinkedHashSet<BluetoothLinkSystem.Endpoint> legacyEndpoints = new LinkedHashSet<>();
		for (ItemFrame frame : component.frameCoords().keySet()) {
			legacyEndpoints.add(BluetoothLinkSystem.screenEndpoint(level.dimension(), frame.blockPosition(), frame.getDirection()));
		}
		BluetoothLinkSystem.collapseScreenEndpoints(level.getServer(), endpoint, legacyEndpoints);
		return endpoint;
	}

	static ScreenComponent resolveBluetoothScreenComponent(ServerLevel level, BluetoothLinkSystem.Endpoint endpoint) {
		if (level == null || endpoint == null || endpoint.type() != BluetoothLinkSystem.EndpointType.SCREEN) {
			return null;
		}
		String screenId = endpoint.screenId();
		if (screenId != null && !screenId.isBlank()) {
			for (ScreenComponent component : cachedComponents(level)) {
				if (screenId.equals(bluetoothScreenId(component))) {
					return component;
				}
			}
		}
		if (endpoint.pos() == null || endpoint.facing() == null) {
			return null;
		}
		ScreenComponent cached = levelState(level.dimension()).components().get(new ScreenRuntimeKey(level.dimension(), endpoint.pos(), endpoint.facing()));
		if (cached != null) {
			return cached;
		}
		ItemFrame frame = findScreenFrame(level, endpoint.pos(), endpoint.facing());
		if (frame == null || readScreenState(frame.getItem()) == null) {
			return null;
		}
		return resolveScreenComponent(level, frame);
	}

	static List<ScreenComponent> cachedComponents(ServerLevel level) {
		if (level == null) {
			return List.of();
		}
		return new ArrayList<>(levelState(level.dimension()).components().values());
	}

	static void updateDebugAimCursors(MinecraftServer server) {
		if (!DEBUG_AIM_CURSOR_ENABLED || server == null) {
			return;
		}
		if (DEBUG_AIM_CURSOR_REFRESH_TICKS > 1 && Math.floorMod(server.getTickCount(), DEBUG_AIM_CURSOR_REFRESH_TICKS) != 0) {
			return;
		}

		Map<ScreenRuntimeKey, UiPoint> nextCursors = new HashMap<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			DebugAimTarget target = findDebugAimTarget(player);
			if (target != null) {
				nextCursors.put(target.runtimeKey(), target.point());
			}
		}

		Set<ScreenRuntimeKey> touchedKeys = new HashSet<>();
		for (Map.Entry<ScreenRuntimeKey, UiPoint> entry : nextCursors.entrySet()) {
			ScreenRuntimeKey key = entry.getKey();
			UiPoint nextPoint = entry.getValue();
			UiPoint previousPoint = DEBUG_AIM_CURSORS.get(key);
			if (!sameDebugAimPoint(previousPoint, nextPoint)) {
				DEBUG_AIM_CURSORS.put(key, nextPoint);
				touchedKeys.add(key);
			}
		}
		for (ScreenRuntimeKey key : new ArrayList<>(DEBUG_AIM_CURSORS.keySet())) {
			if (!nextCursors.containsKey(key) && DEBUG_AIM_CURSORS.remove(key) != null) {
				touchedKeys.add(key);
			}
		}
		for (ScreenRuntimeKey key : touchedKeys) {
			requestRuntimeRender(server, key);
		}
	}

	private static DebugAimTarget findDebugAimTarget(ServerPlayer player) {
		ObservedScreenTouch target = findObservedScreenTouch(player, component -> component.powered());
		return target == null ? null : new DebugAimTarget(target.component().runtimeKey(), target.touchPoint(), target.distanceSqr());
	}

	private static boolean sameDebugAimPoint(UiPoint previousPoint, UiPoint nextPoint) {
		if (previousPoint == null || nextPoint == null) {
			return previousPoint == nextPoint;
		}
		return Math.abs(previousPoint.x() - nextPoint.x()) <= DEBUG_AIM_CURSOR_MOVE_THRESHOLD_PIXELS
				&& Math.abs(previousPoint.y() - nextPoint.y()) <= DEBUG_AIM_CURSOR_MOVE_THRESHOLD_PIXELS;
	}

	public static InteractionResult tryPlaceScreen(UseOnContext context) {
		if (context == null) {
			return InteractionResult.PASS;
		}
		if (context.getLevel().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(context.getLevel() instanceof ServerLevel level) || !(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.PASS;
		}
		if (!ServerUpgradeUiSystem.hasUpgrade(player, IT_SCREEN)) {
			player.displayClientMessage(screenUpgradeLockedMessage(player), true);
			return InteractionResult.FAIL;
		}

		Direction facing = context.getClickedFace();
		if (facing == null || !facing.getAxis().isHorizontal()) {
			player.displayClientMessage(wallOnlyMessage(player), true);
			return InteractionResult.FAIL;
		}

		BlockPos framePos = context.getClickedPos().relative(facing);
		if (!level.getBlockState(framePos).canBeReplaced() || findAnyFrame(level, framePos, facing) != null) {
			player.displayClientMessage(occupiedMessage(player), true);
			return InteractionResult.FAIL;
		}

		GlowItemFrame frame = new GlowItemFrame(level, framePos, facing);
		if (!frame.survives()) {
			player.displayClientMessage(wallOnlyMessage(player), true);
			return InteractionResult.FAIL;
		}

		String groupId = resolvePlacementGroupId(level, framePos, facing, context.getClickLocation(), player.isShiftKeyDown());
		ItemStack frameMap = createScreenMap(level, new ScreenTileState(
				CONNECTION_ALL,
				1,
				1,
				0,
				0,
				0,
				false,
				ScreenViewMode.HOME,
				0,
				groupId
		));
		if (frameMap.isEmpty()) {
			return InteractionResult.FAIL;
		}

		frame.setInvisible(true);
		frame.setSilent(true);
		frame.setItem(frameMap, false);
		frame.setRotation(0);
		level.addFreshEntity(frame);
		trackScreenFrame(level, frame);

		synchronizeConnectedScreens(level, frame, null, null, null);
		if (!player.getAbilities().instabuild) {
			context.getItemInHand().shrink(1);
		}
		return InteractionResult.CONSUME;
	}

	private static Component screenUpgradeLockedMessage(ServerPlayer player) {
		String upgradeName = ServerUpgradeUiSystem.getUpgradeDisplayName(player, IT_SCREEN);
		return Lg2Messages.tr(
				"message.lg2.monitor.upgrade_locked",
				upgradeName == null || upgradeName.isBlank() ? "Screen" : upgradeName
		);
	}

	public static boolean onFrameBroken(ServerLevel level, ItemFrame frame, Entity breaker, boolean shouldDropScreen) {
		if (level == null || frame == null || readScreenState(frame.getItem()) == null) {
			return false;
		}
		ScreenComponent componentBeforeRemoval = resolveScreenComponent(level, frame);
		BluetoothLinkSystem.Endpoint removedScreenEndpoint = componentBeforeRemoval != null && componentBeforeRemoval.frameCoords().size() == 1
				? bluetoothScreenEndpoint(level, componentBeforeRemoval)
				: null;

		BlockPos framePos = frame.blockPosition();
		Direction facing = frame.getDirection();
		removeDisplays(level, frame.blockPosition(), frame.getDirection());
		forgetRenderedMapFrame(frame.getItem());
		frame.setItem(ItemStack.EMPTY, false);
		frame.spawnAtLocation(level, new ItemStack(ModItems.MONITOR));
		frame.discard();
		untrackScreenFrame(level, new ScreenKey(framePos, facing), true);
		if (removedScreenEndpoint != null) {
			BluetoothLinkSystem.removeScreenEndpoint(level, removedScreenEndpoint);
		}
		return true;
	}

	public static boolean isMonitorFrame(ItemFrame frame) {
		return frame != null && readScreenState(frame.getItem()) != null;
	}

	public static BluetoothLinkSystem.Endpoint resolveBluetoothScreenEndpoint(ServerLevel level, ItemFrame frame) {
		if (level == null || frame == null || readScreenState(frame.getItem()) == null) {
			return null;
		}
		return bluetoothScreenEndpoint(level, resolveScreenComponent(level, frame));
	}

	public static BluetoothLinkSystem.Endpoint resolveBluetoothScreenEndpoint(ServerLevel level, Entity entity) {
		if (level == null || entity == null) {
			return null;
		}
		if (entity instanceof ItemFrame itemFrame) {
			return resolveBluetoothScreenEndpoint(level, itemFrame);
		}
		if (entity instanceof Display.ItemDisplay display && display.getTags().contains(DISPLAY_ROOT_TAG)) {
			ItemDisplayHitboxHelper.clear(display);
			BlockPos pos = parsePositionTag(display.getTags());
			Direction facing = parseFacingTag(display.getTags());
			if (pos == null || facing == null) {
				return null;
			}
			ItemFrame frame = findScreenFrame(level, pos, facing);
			if (frame != null) {
				return resolveBluetoothScreenEndpoint(level, frame);
			}
		}
		return null;
	}

	public static List<ServerSelectionHighlightSystem.DisplayBlueprint> resolveBluetoothScreenHighlightBlueprints(ServerLevel level, BluetoothLinkSystem.Endpoint endpoint) {
		if (level == null || endpoint == null) {
			return List.of();
		}
		ScreenComponent component = resolveBluetoothScreenComponent(level, endpoint);
		if (component == null) {
			return List.of();
		}
		List<Map.Entry<ItemFrame, TileCoord>> orderedFrames = new ArrayList<>(component.frameCoords().entrySet());
		orderedFrames.sort((left, right) -> {
			TileCoord leftCoord = left.getValue();
			TileCoord rightCoord = right.getValue();
			int byRow = Integer.compare(leftCoord.y(), rightCoord.y());
			return byRow != 0 ? byRow : Integer.compare(leftCoord.x(), rightCoord.x());
		});
		List<ServerSelectionHighlightSystem.DisplayBlueprint> blueprints = new ArrayList<>(orderedFrames.size());
		Set<UUID> addedGlowEntities = new HashSet<>();
		for (Map.Entry<ItemFrame, TileCoord> entry : orderedFrames) {
			ItemFrame frame = entry.getKey();
			ScreenTileState state = readScreenState(frame.getItem());
			if (state == null) {
				continue;
			}
			List<Display.ItemDisplay> displays = findDisplays(level, frame.blockPosition(), frame.getDirection());
			if (!displays.isEmpty()) {
				Display.ItemDisplay display = displays.get(0);
				if (display != null && display.isAlive() && addedGlowEntities.add(display.getUUID())) {
					blueprints.add(new ServerSelectionHighlightSystem.EntityGlowBlueprint(display));
					continue;
				}
			}
			Direction facing = frame.getDirection();
			Direction mountSide = facing.getOpposite();
			Vec3 center = Vec3.atCenterOf(frame.blockPosition()).add(
					mountSide.getStepX() * DISPLAY_PLANE_OFFSET,
					mountSide.getStepY() * DISPLAY_PLANE_OFFSET,
					mountSide.getStepZ() * DISPLAY_PLANE_OFFSET
			);
			blueprints.add(new ServerSelectionHighlightSystem.ItemDisplayBlueprint(
					level,
					center,
					facing.toYRot(),
					0.0F,
					MonitorItem.createDisplayStack(state.connectionMask()),
					ItemDisplayContext.FIXED,
					Transformation.identity()
			));
		}
		return List.copyOf(blueprints);
	}

	public static void onBluetoothScreenEndpointChanged(ServerLevel level, BluetoothLinkSystem.Endpoint endpoint) {
		if (level == null || endpoint == null) {
			return;
		}
		ScreenComponent component = resolveBluetoothScreenComponent(level, endpoint);
		if (component == null) {
			return;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return;
		}
		enqueueCameraRefresh(level, component.runtimeKey());
		refreshConnectedSpeakersNow(server, component);
		MonitorMaxRuntime.onDeviceNetworkChanged(server, component.runtimeKey());
		if (hasNearbyMediaViewer(level, component)) {
			requestRuntimeRender(server, component.runtimeKey());
		}
	}

	static InteractionResult onUseEntity(Player player, Level world, InteractionHand hand, Entity entity, EntityHitResult hitResult) {
		if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || !(world instanceof ServerLevel level) || !(entity instanceof ItemFrame itemFrame)) {
			return InteractionResult.PASS;
		}
		if (readScreenState(itemFrame.getItem()) == null) {
			return InteractionResult.PASS;
		}
		if (!shouldHandleScreenUse(hand, hitResult != null)) {
			return InteractionResult.SUCCESS;
		}
		return MonitorScreenInputController.handleTouch(serverPlayer, level, itemFrame, hitResult);
	}

	static boolean shouldHandleScreenUseHand(InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND;
	}

	static boolean shouldHandleScreenUse(InteractionHand hand, boolean hasPreciseHit) {
		return shouldHandleScreenUseHand(hand) && hasPreciseHit;
	}

	public static List<SpeakerAudioSource> findSpeakerAudioSources(ServerLevel level, BlockPos speakerPos) {
		Map<ScreenRuntimeKey, ScreenComponent> connectedComponents = collectConnectedSpeakerComponents(level, speakerPos);
		if (connectedComponents.isEmpty()) {
			return List.of();
		}

		List<SpeakerAudioSource> sources = new ArrayList<>();
		for (ScreenComponent component : connectedComponents.values()) {
			if (!component.powered()) {
				continue;
			}
			MediaRuntimeState state = MEDIA_STATES.get(component.runtimeKey());
			if (state == null) {
				continue;
			}
			synchronized (state) {
				boolean waitingForSeekFrame = state.pendingAudioPositionActive && state.streamKind == PlaybackStreamKind.YOUTUBE;
				boolean allowWhileLoading = !waitingForSeekFrame
						&& (isYoutubeMusicMode(state.mode)
						|| state.streamKind == PlaybackStreamKind.DIRECT_VIDEO
						|| state.streamFrame != null
						|| state.bufferedEndMs > state.positionMs + 100L);
				if (!isStreamPlaybackLocked(state)
						|| state.relaySessionId == null
						|| state.audioStreamUrl == null
						|| state.audioStreamUrl.isBlank()
						|| state.waitingForLink
						|| (state.loading && !allowWhileLoading)) {
					continue;
				}
				sources.add(new SpeakerAudioSource(
						componentGroupId(component.runtimeKey()),
						state.relaySessionId,
						state.audioStreamUrl,
						state.positionMs,
						state.audioSyncToken,
						state.loading,
						state.userPaused,
						state.liveStream,
						SpeakerAudioPlaybackPolicy.isPositionAuthoritative(state.streamKind),
						false
				));
			}
		}
		sources.addAll(MonitorMaxRuntime.findSpeakerAudioSources(level.getServer(), connectedComponents.values()));
		sources.addAll(MonitorSupportRuntime.findSpeakerAudioSources(level.getServer(), connectedComponents.values()));
		return sources;
	}

	public static boolean hasPoweredConnectedMonitor(ServerLevel level, BlockPos speakerPos) {
		return collectConnectedSpeakerComponents(level, speakerPos).values().stream().anyMatch(ScreenComponent::powered);
	}

	public static void onCameraNetworkChanged(ServerLevel level, BlockPos cameraPos) {
		MonitorScreenWireConnectivity.onCameraNetworkChanged(level, cameraPos);
	}

	public static void onDroneNetworkChanged(MinecraftServer server, BluetoothLinkSystem.Endpoint endpoint) {
		MonitorScreenWireConnectivity.onDroneNetworkChanged(server, endpoint);
	}

	public static List<DroneSystem.DroneScreenStreamReference> collectActiveDroneScreenStreams(MinecraftServer server) {
		return MonitorScreenWireConnectivity.collectActiveDroneScreenStreams(server);
	}

	public static boolean onPlayerHotbarScroll(ServerPlayer player, int previousSlot, int currentSlot) {
		return MonitorMaxRuntime.onPlayerHotbarScroll(player, previousSlot, currentSlot)
				|| MonitorCameraRuntime.onPlayerHotbarScroll(player, previousSlot, currentSlot)
				|| MonitorScreenMediaFrameRuntime.onPlayerHotbarScroll(player, previousSlot, currentSlot)
				|| MonitorSupportRuntime.onPlayerHotbarScroll(player, previousSlot, currentSlot);
	}

	static ScreenComponent findObservedMediaComponent(ServerPlayer player) {
		ObservedScreenTouch target = findObservedScreenTouch(player, component -> isPlayerMode(component.viewMode()));
		return target == null ? null : target.component();
	}

	static ScreenComponent findObservedScrollableComponent(ServerPlayer player) {
		ObservedScreenTouch target = findObservedScreenTouch(
				player,
				component -> component.viewMode() == ScreenViewMode.HOME
						|| isPlayerMode(component.viewMode())
		);
		return target == null ? null : target.component();
	}

	static ObservedScreenTouch findObservedScreenTouch(ServerPlayer player, Predicate<ScreenComponent> componentFilter) {
		if (player == null || !(player.level() instanceof ServerLevel level) || componentFilter == null) {
			return null;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 rayDirection = player.getLookAngle();
		if (!Double.isFinite(rayDirection.lengthSqr()) || rayDirection.lengthSqr() <= 1.0E-12D) {
			return null;
		}

		ObservedScreenTouch nearestTarget = null;
		for (ScreenComponent component : cachedComponents(level)) {
			if (component == null || !component.powered() || !componentFilter.test(component)) {
				continue;
			}
			for (Map.Entry<ItemFrame, TileCoord> entry : component.frameCoords().entrySet()) {
				ItemFrame frame = entry.getKey();
				TileCoord tileCoord = entry.getValue();
				if (frame == null || !frame.isAlive() || tileCoord == null) {
					continue;
				}
				Vec3 hit = screenPlaneIntersection(frame.blockPosition(), frame.getDirection(), eye, rayDirection, MEDIA_CONTROL_DISTANCE);
				PlacementSurfacePoint surface = boundedScreenSurfacePoint(frame.blockPosition(), frame.getDirection(), hit);
				if (surface == null) {
					continue;
				}
				double distanceSqr = eye.distanceToSqr(hit);
				if (!Double.isFinite(distanceSqr) || distanceSqr > MEDIA_CONTROL_DISTANCE * MEDIA_CONTROL_DISTANCE) {
					continue;
				}
				if (nearestTarget != null && distanceSqr >= nearestTarget.distanceSqr()) {
					continue;
				}
				UiPoint point = screenPointFromSurface(surface, tileCoord, component.width(), component.height());
				if (point != null) {
					nearestTarget = new ObservedScreenTouch(component, frame, tileCoord, point, distanceSqr);
				}
			}
		}
		return nearestTarget;
	}

	static int normalizeHotbarDelta(int previousSlot, int currentSlot) {
		if (previousSlot == currentSlot) {
			return 0;
		}
		int upwardSteps = Math.floorMod(previousSlot - currentSlot, 9);
		if (upwardSteps >= 1 && upwardSteps <= 2) {
			return upwardSteps;
		}
		int downwardSteps = Math.floorMod(currentSlot - previousSlot, 9);
		if (downwardSteps >= 1 && downwardSteps <= 2) {
			return -downwardSteps;
		}
		return 0;
	}

	static boolean isMediaSessionAlive(MinecraftServer server, ScreenRuntimeKey key) {
		ScreenComponent component = resolveScreenComponent(server, key);
		if (component == null || !component.runtimeKey().equals(key)) {
			return false;
		}
		if (isPlayerMode(component.viewMode())) {
			return true;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return false;
		}
		synchronized (state) {
			return (state.wallpaperUrl != null && !state.wallpaperUrl.isBlank()) || !state.galleryItems.isEmpty();
		}
	}

	static void requestRuntimeRender(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		ServerLevel level = server.getLevel(key.dimension());
		if (level == null) {
			return;
		}
		enqueueComponentSync(level, key);
	}

	static void dispatchRuntimeRender(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		ServerLevel level = server.getLevel(key.dimension());
		if (level == null) {
			return;
		}
		ScreenComponent component = resolveScreenComponent(server, key);
		if (component == null) {
			closeMediaSession(server, key);
			return;
		}
		if (isPlayerMode(component.viewMode()) && !hasNearbyMediaViewer(level, component)) {
			return;
		}
		if (!isPlayerMode(component.viewMode())) {
			MediaRuntimeState state = MEDIA_STATES.get(key);
			if (state != null) {
				synchronized (state) {
					if (wallpaperVisibleForCurrentViewLocked(state) && !hasNearbyMediaViewer(level, component)) {
						return;
					}
				}
			}
		}
		requestComponentRender(server, component, ScreenViewMode.normalize(component.viewMode()), component.launcherPage());
	}

	static void requestComponentRender(MinecraftServer server, ScreenComponent component, ScreenViewMode viewMode, int launcherPage) {
		if (server == null || component == null) {
			return;
		}
		ScreenViewMode normalizedViewMode = ScreenViewMode.normalize(viewMode);
		viewMode = normalizedViewMode;
		RenderWork work;
		MediaRuntimeState mediaState = MEDIA_STATES.get(component.runtimeKey());
		boolean hasPersistedWallpaper;
		boolean hasPersistedPlayerBackground;
		boolean hasPersistedGalleryItems;
		boolean homeRender = viewMode == ScreenViewMode.HOME;
		if (mediaState != null) {
			boolean wallpaperNeedsPersistedRead;
			boolean playerBackgroundNeedsPersistedRead;
			boolean galleryNeedsPersistedRead;
			synchronized (mediaState) {
				hasPersistedWallpaper = mediaState.wallpaperUrl != null && !mediaState.wallpaperUrl.isBlank();
				hasPersistedPlayerBackground = mediaState.playerBackgroundUrl != null && !mediaState.playerBackgroundUrl.isBlank();
				hasPersistedGalleryItems = hasSavedGalleryItemsLocked(mediaState);
				wallpaperNeedsPersistedRead = !homeRender && !hasPersistedWallpaper && !mediaState.wallpaperHydrated;
				playerBackgroundNeedsPersistedRead = !homeRender && !hasPersistedPlayerBackground && !mediaState.playerBackgroundHydrated;
				galleryNeedsPersistedRead = !homeRender
						&& viewMode != ScreenViewMode.SBER_DRONES
						&& !hasPersistedGalleryItems
						&& !mediaState.galleryHydrated;
			}
			if (wallpaperNeedsPersistedRead) {
				hasPersistedWallpaper = resolvePersistedWallpaperState(component) != null;
			}
			if (playerBackgroundNeedsPersistedRead) {
				hasPersistedPlayerBackground = resolvePersistedPlayerBackgroundState(component) != null;
			}
			if (galleryNeedsPersistedRead) {
				hasPersistedGalleryItems = !resolvePersistedGalleryState(component).isEmpty();
			}
		} else if (homeRender) {
			hasPersistedWallpaper = false;
			hasPersistedPlayerBackground = false;
			hasPersistedGalleryItems = false;
		} else {
			hasPersistedWallpaper = resolvePersistedWallpaperState(component) != null;
			hasPersistedPlayerBackground = resolvePersistedPlayerBackgroundState(component) != null;
			hasPersistedGalleryItems = viewMode != ScreenViewMode.SBER_DRONES && !resolvePersistedGalleryState(component).isEmpty();
		}
		if (mediaState == null && (isPlayerMode(viewMode) || hasPersistedWallpaper || hasPersistedPlayerBackground || hasPersistedGalleryItems)) {
			mediaState = MEDIA_STATES.computeIfAbsent(
					component.runtimeKey(),
					ignored -> MediaRuntimeState.fresh(normalizedViewMode, "", () -> onMediaProgressChanged(server, component.runtimeKey()))
			);
		}
		if (mediaState != null) {
			synchronized (mediaState) {
				mediaState.mode = viewMode;
			}
			if (!homeRender) {
				ensureWallpaperStateHydrated(server, component.runtimeKey(), mediaState);
				ensurePlayerBackgroundModeHydrated(server, component.runtimeKey(), mediaState);
				ensurePlayerBackgroundStateHydrated(server, component.runtimeKey(), mediaState);
				if (hasPersistedGalleryItems && viewMode == ScreenViewMode.GALLERY) {
					ensureGalleryStateHydrated(server, component.runtimeKey(), mediaState);
				}
			}
		}
		if (isPlayerMode(viewMode)) {
			if (viewMode == ScreenViewMode.GALLERY) {
				ensureGalleryStateHydrated(server, component.runtimeKey(), mediaState);
			} else if (viewMode == ScreenViewMode.SBER_DRONES) {
				ensureSberDronesStateHydrated(server, component.runtimeKey(), mediaState);
			}
			if (mediaState != null) {
				MediaDispatchKey dispatchKey = new MediaDispatchKey(component.powered(), viewMode, launcherPage, component.width(), component.height());
				synchronized (mediaState) {
					if (mediaState.activeRenderJobs > 0 && dispatchKey.equals(mediaState.lastDispatchKey)) {
						mediaState.rerenderRequested = true;
						return;
					}
					mediaState.activeRenderJobs++;
					mediaState.lastDispatchKey = dispatchKey;
					mediaState.rerenderRequested = false;
					work = createRenderWork(server, component, viewMode, launcherPage, mediaState);
				}
			} else {
				work = createRenderWork(server, component, viewMode, launcherPage, null);
			}
		} else {
			work = createRenderWork(server, component, viewMode, launcherPage, mediaState);
		}
		if (work == null) {
			if (mediaState != null) {
				synchronized (mediaState) {
					mediaState.activeRenderJobs = Math.max(0, mediaState.activeRenderJobs - 1);
					if (mediaState.activeRenderJobs == 0) {
						mediaState.lastDispatchKey = null;
					}
				}
			}
			return;
		}
		submitRenderWork(server, work);
		if (mediaState != null
				&& (isPlayerMode(viewMode)
				|| wallpaperVisibleForViewMode(viewMode, mediaState)
				|| playerBackgroundVisibleForViewMode(viewMode, mediaState))) {
			resumeMediaPlaybackIfNeeded(server, component.runtimeKey());
		}
	}

	static void submitRenderWork(MinecraftServer server, RenderWork work) {
		if (server == null || work == null) {
			return;
		}
		ensureExecutors();
		renderExecutor.submit(() -> {
			try {
				byte[][] renderedTiles = renderTiles(server, work);
				RenderedTileBatch renderedBatch = new RenderedTileBatch(renderedTiles, prepareRenderedMapUpdates(work, renderedTiles));
				server.execute(() -> applyRenderedWork(server, work, renderedBatch));
			} catch (Exception exception) {
				Lg2.LOGGER.error("Monitor render job failed for {}", work.runtimeKey(), exception);
				server.execute(() -> handleRenderFailure(server, work, exception));
			}
		});
	}

	static void handleRenderFailure(MinecraftServer server, RenderWork work, Exception exception) {
		if (work == null) {
			return;
		}
		if (!isPlayerMode(work.viewMode())) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(work.runtimeKey());
		if (state == null) {
			return;
		}
		synchronized (state) {
			state.activeRenderJobs = Math.max(0, state.activeRenderJobs - 1);
			if (state.activeRenderJobs == 0) {
				state.lastDispatchKey = null;
			}
			state.rerenderRequested = state.activeRenderJobs > 0;
			state.statusText = "RENDER ERROR";
			state.version++;
		}
	}

	static void applyRenderedWork(MinecraftServer server, RenderWork work, RenderedTileBatch renderedBatch) {
		boolean rerenderAgain = false;
		try {
			if (server == null || work == null || renderedBatch == null || renderedBatch.renderedTiles() == null) {
				return;
			}
			ServerLevel level = server.getLevel(work.runtimeKey().dimension());
			if (level == null) {
				return;
			}
			ScreenComponent component = resolveScreenComponent(server, work.runtimeKey());
			if (component == null || !matchesCurrentComponent(component, work)) {
				return;
			}
			if (isPlayerMode(work.viewMode())) {
				MediaRuntimeState state = MEDIA_STATES.get(work.runtimeKey());
				if (state == null) {
					return;
				}
				synchronized (state) {
					if (state.version != work.mediaVersion()) {
						return;
					}
				}
			}
			applyRenderedTiles(level, component, renderedBatch);
		} finally {
			if (work != null && isPlayerMode(work.viewMode())) {
				rerenderAgain = finishMediaRender(work.runtimeKey(), work.mediaVersion());
			}
		}
		if (rerenderAgain) {
			requestRuntimeRender(server, work.runtimeKey());
		}
	}

	static boolean finishMediaRender(ScreenRuntimeKey key, long renderedVersion) {
		if (key == null) {
			return false;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return false;
		}
		synchronized (state) {
			state.activeRenderJobs = Math.max(0, state.activeRenderJobs - 1);
			boolean rerender = state.rerenderRequested || state.version != renderedVersion;
			if (state.activeRenderJobs == 0) {
				state.lastDispatchKey = null;
				state.rerenderRequested = false;
				return rerender;
			}
			return false;
		}
	}

	static boolean matchesCurrentComponent(ScreenComponent component, RenderWork work) {
		return component != null
				&& work != null
				&& component.runtimeKey().equals(work.runtimeKey())
				&& component.powered() == work.powered()
				&& component.viewMode() == work.viewMode()
				&& component.launcherPage() == work.launcherPage()
				&& component.width() == work.width()
				&& component.height() == work.height();
	}

	static void applyTransientComponentViewState(
			MinecraftServer server,
			ServerLevel level,
			ScreenComponent component,
			ScreenViewMode viewMode,
			int launcherPage
	) {
		if (server == null || level == null || component == null) {
			return;
		}
		ScreenViewMode effectiveViewMode = ScreenViewMode.normalize(viewMode != null ? viewMode : component.viewMode());
		int effectiveLauncherPage = effectiveViewMode == ScreenViewMode.HOME
				? clampInt(launcherPage, 0, homeMaxScroll(createUiLayout(component.width(), component.height())))
				: launcherPage;
		ScreenComponent updated = new ScreenComponent(
				component.runtimeKey(),
				component.facing(),
				component.right(),
				component.width(),
				component.height(),
				component.powered(),
				effectiveViewMode,
				effectiveLauncherPage,
				component.frameCoords(),
				component.byCoord()
		);
		MonitorLevelState state = levelState(level.dimension());
		if (state.components().containsKey(updated.runtimeKey())) {
			state.components().put(updated.runtimeKey(), updated);
		} else {
			cacheComponent(level, updated);
		}
		MediaRuntimeState mediaState = MEDIA_STATES.get(updated.runtimeKey());
		boolean cameraRefreshNeeded = effectiveViewMode == ScreenViewMode.SBER_DRONES;
		if (!cameraRefreshNeeded && mediaState != null) {
			synchronized (mediaState) {
				cameraRefreshNeeded = mediaState.streamKind == PlaybackStreamKind.LIVE_CAMERA || hasLiveCameraItemsLocked(mediaState);
			}
		}
		if (cameraRefreshNeeded) {
			enqueueCameraRefresh(level, updated.runtimeKey());
		}
		requestRuntimeRender(server, updated.runtimeKey());
	}

	static void synchronizeConnectedScreens(ServerLevel level, ItemFrame startFrame, Set<ScreenKey> processedKeys) {
		synchronizeConnectedScreens(level, startFrame, processedKeys, null, null);
	}

	static void synchronizeConnectedScreens(
			ServerLevel level,
			ItemFrame startFrame,
			Set<ScreenKey> processedKeys,
			ScreenViewMode forcedViewMode,
			Integer forcedLauncherPage
	) {
		ScreenComponent component = (forcedViewMode != null || forcedLauncherPage != null)
				? cachedComponentForFrame(level, startFrame, processedKeys)
				: null;
		if (component == null) {
			component = collectComponent(level, startFrame, processedKeys);
		}
		if (component == null) {
			return;
		}

		boolean powered = component.frameCoords().keySet().stream()
				.anyMatch(frame -> frame != null && frame.isAlive() && isPowered(level, frame));
		boolean previouslyPowered = component.byCoord().values().stream()
				.map(ScreenFrame::state)
				.anyMatch(ScreenTileState::powered);
		Set<ScreenRuntimeKey> previousRuntimeKeys = previousRuntimeKeysForComponent(level, component);
		ScreenViewMode viewMode = ScreenViewMode.normalize(forcedViewMode != null ? forcedViewMode : component.viewMode());
		int launcherPage = forcedLauncherPage != null ? forcedLauncherPage : component.launcherPage();
		if (forcedViewMode == null
				&& forcedLauncherPage == null
				&& shouldResetToHomeAfterPhysicalInteraction(component, powered)) {
			resetMediaSessionsForPhysicalInteraction(level.getServer(), component.runtimeKey(), previousRuntimeKeys);
			resetAuxiliaryAppRuntimesForPhysicalInteraction(level.getServer(), component.runtimeKey(), previousRuntimeKeys);
			viewMode = ScreenViewMode.HOME;
			launcherPage = 0;
		}
		boolean fastHomePowerOn = forcedViewMode == null
				&& forcedLauncherPage == null
				&& powered
				&& !previouslyPowered
				&& viewMode == ScreenViewMode.HOME;
		List<PersistedGalleryItem> persistedGallery = fastHomePowerOn ? List.of() : resolvePersistedGalleryState(component);
		PersistedWallpaperState persistedWallpaper = fastHomePowerOn ? null : resolvePersistedWallpaperState(component);
		PersistedPlayerBackgroundState persistedPlayerBackground = fastHomePowerOn ? null : resolvePersistedPlayerBackgroundState(component);
		PlayerBackgroundMode persistedPlayerBackgroundMode = fastHomePowerOn ? null : resolvePersistedPlayerBackgroundMode(component);
		String persistedGroupId = resolvePersistedGroupId(component);
		if (!powered) {
			viewMode = ScreenViewMode.HOME;
			launcherPage = 0;
			resetMediaSessionForPowerOff(level.getServer(), component.runtimeKey(), persistedGallery, persistedWallpaper, persistedPlayerBackground, persistedPlayerBackgroundMode);
			deactivateAuxiliaryAppRuntimes(level.getServer(), component.runtimeKey());
		}
		int effectiveLauncherPage = viewMode == ScreenViewMode.HOME
				? clampInt(launcherPage, 0, homeMaxScroll(createUiLayout(component.width(), component.height())))
				: launcherPage;
		ScreenComponent renderedComponent = new ScreenComponent(
				component.runtimeKey(),
				component.facing(),
				component.right(),
				component.width(),
				component.height(),
				powered,
				viewMode,
				effectiveLauncherPage,
				component.frameCoords(),
				component.byCoord()
		);
		boolean immediateRenderRequested = forcedViewMode != null || forcedLauncherPage != null;
		ServerLevel mapLevel = photoMapLevel(level.getServer(), level);
		boolean synchronizePersistedMedia = !fastHomePowerOn;
		if (synchronizePersistedMedia && powered) {
			synchronizeRuntimeGalleryState(level.getServer(), component.runtimeKey(), persistedGallery);
		}
		boolean rerenderMaps = false;

		cacheComponent(level, renderedComponent);
		if (immediateRenderRequested) {
			requestComponentRender(level.getServer(), renderedComponent, viewMode, effectiveLauncherPage);
		}

		for (Map.Entry<ItemFrame, TileCoord> entry : component.frameCoords().entrySet()) {
			ItemFrame frame = entry.getKey();
			TileCoord tileCoord = entry.getValue();
			ScreenFrame screenFrame = component.byCoord().get(tileCoord);
			if (screenFrame == null) {
				continue;
			}

			ItemStack existing = frame.getItem();
			MapId existingMapId = existing.get(DataComponents.MAP_ID);
			boolean missingMap = existingMapId == null || mapLevel.getMapData(existingMapId) == null;
			ItemStack ensured = ensureMap(level, frame, screenFrame.state());
			ScreenTileState currentState = readScreenState(ensured);
			if (currentState == null) {
				continue;
			}

			int connectionMask = connectionMask(component.byCoord(), tileCoord.x(), tileCoord.y());
			ScreenTileState updatedState = new ScreenTileState(
					CONNECTION_ALL,
					component.width(),
					component.height(),
					tileCoord.x(),
					tileCoord.y(),
					connectionMask,
					powered,
					viewMode,
					effectiveLauncherPage,
					persistedGroupId
			);

			if (missingMap || !currentState.sameRenderState(updatedState)) {
				rerenderMaps = true;
			}
			boolean galleryChanged = false;
			boolean wallpaperChanged = false;
			boolean playerBackgroundChanged = false;
			if (synchronizePersistedMedia) {
				List<PersistedGalleryItem> currentGalleryState = readPersistedGalleryState(ensured);
				PersistedWallpaperState currentWallpaperState = readPersistedWallpaperState(ensured);
				PersistedPlayerBackgroundState currentPlayerBackgroundState = readPersistedPlayerBackgroundState(ensured);
				PlayerBackgroundMode currentPlayerBackgroundMode = readPersistedPlayerBackgroundMode(ensured);
				galleryChanged = !Objects.equals(currentGalleryState, persistedGallery);
				wallpaperChanged = !Objects.equals(currentWallpaperState, persistedWallpaper);
				playerBackgroundChanged = !Objects.equals(currentPlayerBackgroundState, persistedPlayerBackground)
						|| currentPlayerBackgroundMode != persistedPlayerBackgroundMode;
			}
			if (galleryChanged || wallpaperChanged || playerBackgroundChanged) {
				rerenderMaps = true;
			}
			if (!currentState.equals(updatedState) || galleryChanged || wallpaperChanged || playerBackgroundChanged) {
				ItemStack updated = ensured.copy();
				writeScreenState(updated, updatedState);
				if (synchronizePersistedMedia) {
					writePersistedMediaState(
							updated,
							persistedGallery,
							persistedWallpaper,
							persistedPlayerBackground,
							persistedPlayerBackgroundMode
					);
				}
				frame.setItem(updated, false);
			}
			ensureDisplay(level, frame, connectionMask);
		}

		if (!rerenderMaps || immediateRenderRequested) {
			return;
		}
		requestComponentRender(level.getServer(), renderedComponent, viewMode, effectiveLauncherPage);
	}

	static Set<ScreenRuntimeKey> previousRuntimeKeysForComponent(ServerLevel level, ScreenComponent component) {
		if (level == null || component == null) {
			return Set.of();
		}
		Set<ScreenRuntimeKey> runtimeKeys = new HashSet<>();
		MonitorLevelState state = levelState(level.dimension());
		for (ItemFrame frame : component.frameCoords().keySet()) {
			if (frame == null) {
				continue;
			}
			ScreenRuntimeKey runtimeKey = state.frameToRuntime().get(new ScreenKey(frame.blockPosition(), frame.getDirection()));
			if (runtimeKey != null) {
				runtimeKeys.add(runtimeKey);
			}
		}
		return runtimeKeys;
	}

	static boolean shouldResetToHomeAfterPhysicalInteraction(ScreenComponent component, boolean powered) {
		if (component == null) {
			return false;
		}
		for (Map.Entry<ItemFrame, TileCoord> entry : component.frameCoords().entrySet()) {
			ItemFrame frame = entry.getKey();
			TileCoord tileCoord = entry.getValue();
			if (frame == null || tileCoord == null) {
				continue;
			}
			ScreenTileState state = readScreenState(frame.getItem());
			if (state == null) {
				continue;
			}
			int expectedConnectionMask = connectionMask(component.byCoord(), tileCoord.x(), tileCoord.y());
			if (state.gridWidth() != component.width()
					|| state.gridHeight() != component.height()
					|| state.tileX() != tileCoord.x()
					|| state.tileY() != tileCoord.y()
					|| state.connectionMask() != expectedConnectionMask
					|| state.powered() != powered) {
				return true;
			}
		}
		return false;
	}

	static void resetMediaSessionsForPhysicalInteraction(
			MinecraftServer server,
			ScreenRuntimeKey currentRuntimeKey,
			Set<ScreenRuntimeKey> previousRuntimeKeys
	) {
		if (server == null) {
			return;
		}
		boolean resetCurrent = previousRuntimeKeys == null || previousRuntimeKeys.isEmpty();
		if (previousRuntimeKeys != null) {
			for (ScreenRuntimeKey previousRuntimeKey : previousRuntimeKeys) {
				if (previousRuntimeKey == null) {
					continue;
				}
				if (previousRuntimeKey.equals(currentRuntimeKey)) {
					deactivateMediaSession(server, previousRuntimeKey);
					resetCurrent = false;
				} else {
					closeMediaSession(server, previousRuntimeKey);
				}
			}
		}
		if (resetCurrent && currentRuntimeKey != null && MEDIA_STATES.containsKey(currentRuntimeKey)) {
			deactivateMediaSession(server, currentRuntimeKey);
		}
	}

	static void deactivateAuxiliaryAppRuntimes(MinecraftServer server, ScreenRuntimeKey key) {
		if (key == null) {
			return;
		}
		MonitorMaxRuntime.deactivateRuntime(server, key);
		MonitorCameraRuntime.deactivateRuntime(key);
	}

	static void resetAuxiliaryAppRuntimesForPhysicalInteraction(
			MinecraftServer server,
			ScreenRuntimeKey currentRuntimeKey,
			Set<ScreenRuntimeKey> previousRuntimeKeys
	) {
		boolean resetCurrent = previousRuntimeKeys == null || previousRuntimeKeys.isEmpty();
		if (previousRuntimeKeys != null) {
			for (ScreenRuntimeKey previousRuntimeKey : previousRuntimeKeys) {
				if (previousRuntimeKey == null) {
					continue;
				}
				deactivateAuxiliaryAppRuntimes(server, previousRuntimeKey);
				if (previousRuntimeKey.equals(currentRuntimeKey)) {
					resetCurrent = false;
				}
			}
		}
		if (resetCurrent) {
			deactivateAuxiliaryAppRuntimes(server, currentRuntimeKey);
		}
	}

	static ScreenComponent cachedComponentForFrame(ServerLevel level, ItemFrame frame, Set<ScreenKey> processedKeys) {
		if (level == null || frame == null) {
			return null;
		}
		ScreenRuntimeKey runtimeKey = levelState(level.dimension()).frameToRuntime().get(new ScreenKey(frame.blockPosition(), frame.getDirection()));
		if (runtimeKey == null) {
			return null;
		}
		ScreenComponent component = levelState(level.dimension()).components().get(runtimeKey);
		if (component == null) {
			return null;
		}
		if (processedKeys != null) {
			for (ItemFrame currentFrame : component.frameCoords().keySet()) {
				processedKeys.add(new ScreenKey(currentFrame.blockPosition(), currentFrame.getDirection()));
			}
		}
		return component;
	}

	static ScreenComponent collectComponent(ServerLevel level, ItemFrame startFrame, Set<ScreenKey> processedKeys) {
		if (level == null || startFrame == null) {
			return null;
		}
		ScreenTileState startState = readScreenState(startFrame.getItem());
		if (startState == null) {
			return null;
		}
		String targetGroupId = normalizedGroupId(startState);

		Direction facing = startFrame.getDirection();
		Direction right = frameRight(facing);
		Map<ScreenKey, ScreenFrame> frames = new HashMap<>();
		ArrayDeque<ItemFrame> queue = new ArrayDeque<>();
		queue.add(startFrame);

		while (!queue.isEmpty()) {
			ItemFrame frame = queue.removeFirst();
			ScreenTileState state = readScreenState(frame.getItem());
			if (state == null || frame.getDirection() != facing) {
				continue;
			}

			ScreenKey key = new ScreenKey(frame.blockPosition(), facing);
			if (frames.containsKey(key)) {
				continue;
			}
			frames.put(key, new ScreenFrame(frame, state));
			for (PlacementNeighbor candidate : List.of(
					new PlacementNeighbor(frame.blockPosition().relative(right), CONNECTION_RIGHT),
					new PlacementNeighbor(frame.blockPosition().relative(right.getOpposite()), CONNECTION_LEFT),
					new PlacementNeighbor(frame.blockPosition().above(), CONNECTION_UP),
					new PlacementNeighbor(frame.blockPosition().below(), CONNECTION_DOWN)
			)) {
				BlockPos neighborPos = candidate.pos();
				ItemFrame neighbor = findScreenFrame(level, neighborPos, facing);
				if (neighbor == null) {
					continue;
				}
				ScreenTileState neighborState = readScreenState(neighbor.getItem());
				if (neighborState == null) {
					continue;
				}
				if (targetGroupId != null) {
					if (!targetGroupId.equals(normalizedGroupId(neighborState))) {
						continue;
					}
				} else if (!allowsLegacyAttachment(state, candidate.connectionMask())
						|| !allowsLegacyAttachment(neighborState, oppositeConnectionMask(candidate.connectionMask()))) {
					continue;
				}
				queue.add(neighbor);
			}
		}

		if (processedKeys != null) {
			processedKeys.addAll(frames.keySet());
		}
		if (frames.isEmpty()) {
			return null;
		}

		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		Map<ScreenFrame, TileCoord> localCoords = new HashMap<>();
		for (ScreenFrame screenFrame : frames.values()) {
			BlockPos pos = screenFrame.frame().blockPosition();
			int localX = pos.getX() * right.getStepX() + pos.getZ() * right.getStepZ();
			int localY = pos.getY();
			localCoords.put(screenFrame, new TileCoord(localX, localY));
			minX = Math.min(minX, localX);
			maxX = Math.max(maxX, localX);
			minY = Math.min(minY, localY);
			maxY = Math.max(maxY, localY);
		}

		int width = maxX - minX + 1;
		int height = maxY - minY + 1;
		boolean powered = frames.values().stream().anyMatch(screenFrame -> isPowered(level, screenFrame.frame()));
		ScreenViewMode viewMode = frames.values().stream()
				.map(ScreenFrame::state)
				.map(ScreenTileState::viewMode)
				.max(ScreenViewMode::compareTo)
				.orElse(ScreenViewMode.HOME);
		int launcherPage = frames.values().stream()
				.map(ScreenFrame::state)
				.mapToInt(ScreenTileState::launcherPage)
				.max()
				.orElse(0);

		Map<ItemFrame, TileCoord> frameCoords = new HashMap<>();
		Map<TileCoord, ScreenFrame> byCoord = new HashMap<>();
		for (Map.Entry<ScreenFrame, TileCoord> entry : localCoords.entrySet()) {
			int tileX = entry.getValue().x() - minX;
			int tileY = maxY - entry.getValue().y();
			TileCoord tileCoord = new TileCoord(tileX, tileY);
			frameCoords.put(entry.getKey().frame(), tileCoord);
			byCoord.put(tileCoord, entry.getKey());
		}
		ScreenFrame rootFrame = byCoord.get(new TileCoord(0, 0));
		if (rootFrame == null) {
			rootFrame = frames.values().iterator().next();
		}
		return new ScreenComponent(
				new ScreenRuntimeKey(level.dimension(), rootFrame.frame().blockPosition(), facing),
				facing,
				right,
				width,
				height,
				powered,
				viewMode,
				launcherPage,
				frameCoords,
				byCoord
		);
	}

	static void synchronizeNeighborComponents(ServerLevel level, BlockPos pos, Direction facing) {
		if (level == null || pos == null || facing == null) {
			return;
		}
		Direction right = frameRight(facing);
		Set<ScreenKey> processed = new HashSet<>();
		for (BlockPos candidatePos : List.of(
				pos.relative(right),
				pos.relative(right.getOpposite()),
				pos.above(),
				pos.below()
		)) {
			ItemFrame frame = findScreenFrame(level, candidatePos, facing);
			if (frame == null) {
				continue;
			}
			ScreenKey key = new ScreenKey(frame.blockPosition(), frame.getDirection());
			if (processed.contains(key)) {
				continue;
			}
			synchronizeConnectedScreens(level, frame, processed, null, null);
		}
	}

	static int connectionMask(Map<TileCoord, ScreenFrame> byCoord, int tileX, int tileY) {
		int mask = 0;
		if (byCoord.containsKey(new TileCoord(tileX - 1, tileY))) {
			mask |= CONNECTION_LEFT;
		}
		if (byCoord.containsKey(new TileCoord(tileX + 1, tileY))) {
			mask |= CONNECTION_RIGHT;
		}
		if (byCoord.containsKey(new TileCoord(tileX, tileY - 1))) {
			mask |= CONNECTION_UP;
		}
		if (byCoord.containsKey(new TileCoord(tileX, tileY + 1))) {
			mask |= CONNECTION_DOWN;
		}
		return mask;
	}

	static String resolvePlacementGroupId(ServerLevel level, BlockPos framePos, Direction facing, Vec3 clickLocation, boolean shiftPlacement) {
		if (level == null || framePos == null || facing == null) {
			return "";
		}
		Map<Integer, ScreenComponent> adjacentComponents = adjacentComponents(level, framePos, facing);
		if (adjacentComponents.isEmpty()) {
			return standaloneGroupId(level, framePos, facing);
		}
		if (shiftPlacement) {
			int preferredSide = preferredPlacementSide(framePos, facing, clickLocation);
			ScreenComponent chosen = adjacentComponents.get(preferredSide);
			if (chosen == null) {
				return standaloneGroupId(level, framePos, facing);
			}
			String groupId = componentGroupId(chosen.runtimeKey());
			rewriteComponentGroupId(chosen, groupId);
			return groupId;
		}

		ScreenComponent primary = adjacentComponents.values().iterator().next();
		String mergedGroupId = componentGroupId(primary.runtimeKey());
		Set<ScreenRuntimeKey> rewritten = new HashSet<>();
		for (ScreenComponent component : adjacentComponents.values()) {
			if (component == null || !rewritten.add(component.runtimeKey())) {
				continue;
			}
			rewriteComponentGroupId(component, mergedGroupId);
		}
		return mergedGroupId;
	}

	static PlacementSurfacePoint placementSurfacePoint(BlockPos framePos, Direction facing, Vec3 hitLocation) {
		if (framePos == null || facing == null || hitLocation == null) {
			return null;
		}
		double localX = hitLocation.x - framePos.getX();
		double localY = hitLocation.y - framePos.getY();
		double localZ = hitLocation.z - framePos.getZ();
		double u = switch (facing) {
			case SOUTH -> localX;
			case NORTH -> 1.0D - localX;
			case EAST -> 1.0D - localZ;
			case WEST -> localZ;
			default -> Double.NaN;
		};
		if (!Double.isFinite(u) || !Double.isFinite(localY)) {
			return null;
		}
		return new PlacementSurfacePoint(
				clampDouble(u, 0.0D, 1.0D),
				clampDouble(1.0D - localY, 0.0D, 1.0D)
		);
	}

	static Map<Integer, ScreenComponent> adjacentComponents(ServerLevel level, BlockPos framePos, Direction facing) {
		if (level == null || framePos == null || facing == null) {
			return Map.of();
		}
		Direction right = frameRight(facing);
		Map<Integer, ScreenComponent> adjacent = new HashMap<>();
		for (PlacementNeighbor candidate : List.of(
				new PlacementNeighbor(framePos.relative(right.getOpposite()), CONNECTION_LEFT),
				new PlacementNeighbor(framePos.relative(right), CONNECTION_RIGHT),
				new PlacementNeighbor(framePos.above(), CONNECTION_UP),
				new PlacementNeighbor(framePos.below(), CONNECTION_DOWN)
		)) {
			ItemFrame neighbor = findScreenFrame(level, candidate.pos(), facing);
			if (neighbor == null) {
				continue;
			}
			ScreenComponent component = resolveScreenComponent(level, neighbor);
			if (component != null) {
				adjacent.put(candidate.connectionMask(), component);
			}
		}
		return adjacent;
	}

	static int preferredPlacementSide(BlockPos framePos, Direction facing, Vec3 clickLocation) {
		PlacementSurfacePoint hit = placementSurfacePoint(framePos, facing, clickLocation);
		if (hit == null) {
			return 0;
		}
		Direction right = frameRight(facing);
		return List.of(
				new PlacementNeighbor(framePos.relative(right.getOpposite()), CONNECTION_LEFT, hit.u()),
				new PlacementNeighbor(framePos.relative(right), CONNECTION_RIGHT, 1.0D - hit.u()),
				new PlacementNeighbor(framePos.above(), CONNECTION_UP, hit.v()),
				new PlacementNeighbor(framePos.below(), CONNECTION_DOWN, 1.0D - hit.v())
		).stream()
				.min((leftNeighbor, rightNeighbor) -> Double.compare(leftNeighbor.distance(), rightNeighbor.distance()))
				.map(PlacementNeighbor::connectionMask)
				.orElse(0);
	}

	static void rewriteComponentGroupId(ScreenComponent component, String groupId) {
		if (component == null || groupId == null || groupId.isBlank()) {
			return;
		}
		ServerLevel level = null;
		for (ItemFrame frame : component.frameCoords().keySet()) {
			if (frame.level() instanceof ServerLevel serverLevel) {
				level = serverLevel;
			}
			ScreenTileState state = readScreenState(frame.getItem());
			if (state == null || groupId.equals(normalizedGroupId(state))) {
				continue;
			}
			ItemStack updated = frame.getItem().copy();
			writeScreenState(updated, new ScreenTileState(
					CONNECTION_ALL,
					state.gridWidth(),
					state.gridHeight(),
					state.tileX(),
					state.tileY(),
					state.connectionMask(),
					state.powered(),
					state.viewMode(),
					state.launcherPage(),
					groupId
			));
			frame.setItem(updated, false);
		}
		if (level != null) {
			invalidateCachedRuntime(level, component.runtimeKey(), null, false);
		}
	}

	static boolean allowsLegacyAttachment(ScreenTileState state, int connectionMask) {
		return state != null && (state.attachmentMask() & connectionMask) != 0;
	}

	static int oppositeConnectionMask(int connectionMask) {
		return switch (connectionMask) {
			case CONNECTION_LEFT -> CONNECTION_RIGHT;
			case CONNECTION_RIGHT -> CONNECTION_LEFT;
			case CONNECTION_UP -> CONNECTION_DOWN;
			case CONNECTION_DOWN -> CONNECTION_UP;
			default -> 0;
		};
	}

	static String componentGroupId(ScreenRuntimeKey key) {
		if (key == null) {
			return "";
		}
		return key.dimension() + "|" + key.pos().asLong() + "|" + key.facing().getSerializedName();
	}

	static String resolvePersistedGroupId(ScreenComponent component) {
		if (component == null || component.frameCoords().isEmpty()) {
			return "";
		}
		List<Map.Entry<ItemFrame, TileCoord>> orderedFrames = new ArrayList<>(component.frameCoords().entrySet());
		orderedFrames.sort((left, right) -> {
			TileCoord leftCoord = left.getValue();
			TileCoord rightCoord = right.getValue();
			int byRow = Integer.compare(leftCoord.y(), rightCoord.y());
			return byRow != 0 ? byRow : Integer.compare(leftCoord.x(), rightCoord.x());
		});
		for (Map.Entry<ItemFrame, TileCoord> entry : orderedFrames) {
			ItemFrame frame = entry.getKey();
			if (frame == null) {
				continue;
			}
			String groupId = normalizedGroupId(readScreenState(frame.getItem()));
			if (groupId != null) {
				return groupId;
			}
		}
		return "";
	}

	static List<PersistedGalleryItem> resolvePersistedGalleryState(ScreenComponent component) {
		if (component == null) {
			return List.of();
		}
		LinkedHashSet<ScreenRuntimeKey> runtimeKeys = new LinkedHashSet<>();
		if (component.runtimeKey() != null) {
			runtimeKeys.add(component.runtimeKey());
		}
		MonitorLevelState state = null;
		List<Map.Entry<ItemFrame, TileCoord>> orderedFrames = new ArrayList<>(component.frameCoords().entrySet());
		orderedFrames.sort((left, right) -> {
			TileCoord leftCoord = left.getValue();
			TileCoord rightCoord = right.getValue();
			int byRow = Integer.compare(leftCoord.y(), rightCoord.y());
			return byRow != 0 ? byRow : Integer.compare(leftCoord.x(), rightCoord.x());
		});
		for (Map.Entry<ItemFrame, TileCoord> entry : orderedFrames) {
			ItemFrame frame = entry.getKey();
			if (state == null && frame != null && frame.level() instanceof ServerLevel level) {
				state = levelState(level.dimension());
			}
			if (state == null || frame == null) {
				continue;
			}
			ScreenRuntimeKey runtimeKey = state.frameToRuntime().get(new ScreenKey(frame.blockPosition(), frame.getDirection()));
			if (runtimeKey != null) {
				runtimeKeys.add(runtimeKey);
			}
		}
		LinkedHashMap<String, PersistedGalleryItem> merged = new LinkedHashMap<>();
		for (ScreenRuntimeKey runtimeKey : runtimeKeys) {
			MediaRuntimeState runtimeState = MEDIA_STATES.get(runtimeKey);
			if (runtimeState == null) {
				continue;
			}
			synchronized (runtimeState) {
				mergePersistedGalleryItems(merged, persistedGalleryItems(runtimeState.galleryItems));
			}
		}
		for (Map.Entry<ItemFrame, TileCoord> entry : orderedFrames) {
			mergePersistedGalleryItems(merged, readPersistedGalleryState(entry.getKey().getItem()));
		}
		return merged.isEmpty() ? List.of() : List.copyOf(merged.values());
	}

	static void mergePersistedGalleryItems(Map<String, PersistedGalleryItem> merged, List<PersistedGalleryItem> items) {
		if (merged == null || items == null || items.isEmpty()) {
			return;
		}
		for (PersistedGalleryItem item : items) {
			String identity = persistedGalleryIdentity(item);
			if (identity == null) {
				continue;
			}
			PersistedGalleryItem existing = merged.get(identity);
			merged.put(identity, mergePersistedGalleryItem(existing, item));
		}
	}

	static String persistedGalleryIdentity(PersistedGalleryItem item) {
		if (item == null) {
			return null;
		}
		String url = item.url() != null ? item.url().trim() : "";
		if (!url.isBlank()) {
			return "url:" + url;
		}
		String localMediaKey = item.localMediaKey() != null ? item.localMediaKey().trim() : "";
		return localMediaKey.isBlank() ? null : "local:" + localMediaKey;
	}

	static PersistedGalleryItem mergePersistedGalleryItem(PersistedGalleryItem existing, PersistedGalleryItem candidate) {
		if (existing == null) {
			return candidate;
		}
		if (candidate == null) {
			return existing;
		}
		String title = firstNonBlank(existing.title(), candidate.title());
		String subtitle = firstNonBlank(existing.subtitle(), candidate.subtitle());
		String url = firstNonBlank(existing.url(), candidate.url());
		String localMediaKey = firstNonBlank(existing.localMediaKey(), candidate.localMediaKey());
		GalleryItemKind kind = existing.kind() != null && existing.kind() != GalleryItemKind.MEDIA
				? existing.kind()
				: candidate.kind() != null
				? candidate.kind()
				: GalleryItemKind.MEDIA;
		return new PersistedGalleryItem(title, subtitle, url, effectiveGalleryItemKind(url, localMediaKey, kind), localMediaKey);
	}

	static String firstNonBlank(String preferred, String fallback) {
		if (preferred != null && !preferred.isBlank()) {
			return preferred;
		}
		return fallback != null ? fallback : "";
	}

	static void synchronizeRuntimeGalleryState(MinecraftServer server, ScreenRuntimeKey key, List<PersistedGalleryItem> persistedItems) {
		if (server == null || key == null) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return;
		}
		List<PersistedGalleryItem> normalizedItems = persistedItems != null ? persistedItems : List.of();
		long requestId = 0L;
		long sessionGeneration = 0L;
		String selectedUrl = null;
		int preferredIndex = -1;
		boolean scheduleDisplayHydration = false;
		synchronized (state) {
			if (state.mode == ScreenViewMode.SBER_DRONES) {
				return;
			}
			if (Objects.equals(persistedGalleryItems(state.galleryItems), normalizedItems)) {
				return;
			}
			selectedUrl = currentGalleryItemLocked(state) != null ? currentGalleryItemLocked(state).url() : null;
			preferredIndex = state.galleryIndex;
			if (normalizedItems.isEmpty()) {
				state.galleryItems.clear();
				state.galleryHydrationLoading = false;
				state.galleryHydrationRequestId++;
				state.galleryHydrated = true;
				state.galleryIndex = -1;
				state.gallerySurfaceMode = GallerySurfaceMode.BROWSER;
				state.version++;
				return;
			}
			state.galleryHydrationLoading = true;
			requestId = ++state.galleryHydrationRequestId;
			sessionGeneration = state.sessionGeneration;
			state.galleryHydrated = true;
			scheduleDisplayHydration = true;
		}
		if (scheduleDisplayHydration) {
			scheduleGalleryDisplayHydration(server, key, normalizedItems, sessionGeneration, requestId, selectedUrl, preferredIndex, true);
		}
	}

	static PersistedWallpaperState resolvePersistedWallpaperState(ScreenComponent component) {
		if (component == null) {
			return null;
		}
		MediaRuntimeState runtimeState = MEDIA_STATES.get(component.runtimeKey());
		if (runtimeState != null) {
			synchronized (runtimeState) {
				if (runtimeState.wallpaperUrl != null && !runtimeState.wallpaperUrl.isBlank()) {
					return new PersistedWallpaperState(
							runtimeState.wallpaperUrl,
							runtimeState.wallpaperScaleMode,
							runtimeState.wallpaperBackgroundMode
					);
				}
			}
		}
		for (ItemFrame frame : component.frameCoords().keySet()) {
			PersistedWallpaperState state = readPersistedWallpaperState(frame.getItem());
			if (state != null && state.url() != null && !state.url().isBlank()) {
				return state;
			}
		}
		return null;
	}

	static PersistedPlayerBackgroundState resolvePersistedPlayerBackgroundState(ScreenComponent component) {
		if (component == null) {
			return null;
		}
		MediaRuntimeState runtimeState = MEDIA_STATES.get(component.runtimeKey());
		if (runtimeState != null) {
			synchronized (runtimeState) {
				if (runtimeState.playerBackgroundUrl != null && !runtimeState.playerBackgroundUrl.isBlank()) {
					return new PersistedPlayerBackgroundState(
							runtimeState.playerBackgroundUrl,
							runtimeState.playerBackgroundScaleMode
					);
				}
			}
		}
		for (ItemFrame frame : component.frameCoords().keySet()) {
			PersistedPlayerBackgroundState state = readPersistedPlayerBackgroundState(frame.getItem());
			if (state != null && state.url() != null && !state.url().isBlank()) {
				return state;
			}
		}
		return null;
	}

	static PlayerBackgroundMode resolvePersistedPlayerBackgroundMode(ScreenComponent component) {
		if (component == null) {
			return null;
		}
		MediaRuntimeState runtimeState = MEDIA_STATES.get(component.runtimeKey());
		if (runtimeState != null) {
			synchronized (runtimeState) {
				if (runtimeState.playerBackgroundMode != null) {
					return runtimeState.playerBackgroundMode;
				}
			}
		}
		for (ItemFrame frame : component.frameCoords().keySet()) {
			PlayerBackgroundMode mode = readPersistedPlayerBackgroundMode(frame.getItem());
			if (mode != null) {
				return mode;
			}
		}
		return null;
	}

	static List<GalleryItem> galleryItemsFromPersisted(List<PersistedGalleryItem> persistedItems) {
		if (persistedItems == null || persistedItems.isEmpty()) {
			return List.of();
		}
		List<GalleryItem> items = new ArrayList<>(persistedItems.size());
		for (PersistedGalleryItem item : persistedItems) {
			if (item == null || item.url() == null || item.url().isBlank()) {
				continue;
			}
			GalleryItemKind resolvedKind = effectiveGalleryItemKind(item);
			if (persistedGalleryItemRequiresLocalMedia(resolvedKind, item) && !persistedGalleryItemHasLocalMedia(item)) {
				continue;
			}
			BufferedImage preview = resolvedKind == GalleryItemKind.AUDIO
					? persistedGalleryAudioPreview(item)
					: null;
			items.add(new GalleryItem(item.title(), item.subtitle(), item.url(), item.localMediaKey(), null, preview, resolvedKind));
		}
		return List.copyOf(items);
	}

	static List<GalleryItem> displayGalleryItemsFromPersisted(List<PersistedGalleryItem> persistedItems) {
		if (persistedItems == null || persistedItems.isEmpty()) {
			return List.of();
		}
		List<GalleryItem> items = new ArrayList<>(persistedItems.size());
		for (PersistedGalleryItem item : persistedItems) {
			if (item == null || item.url() == null || item.url().isBlank()) {
				continue;
			}
			GalleryItemKind resolvedKind = effectiveGalleryItemKind(item);
			if (persistedGalleryItemRequiresLocalMedia(resolvedKind, item) && !persistedGalleryItemHasLocalMedia(item)) {
				continue;
			}
			BufferedImage preview = persistedGalleryPreviewForDisplay(item, resolvedKind);
			items.add(new GalleryItem(item.title(), item.subtitle(), item.url(), item.localMediaKey(), null, preview, resolvedKind));
		}
		return List.copyOf(items);
	}

	static boolean persistedGalleryItemRequiresLocalMedia(GalleryItemKind kind, PersistedGalleryItem item) {
		return kind == GalleryItemKind.YOUTUBE
				|| kind == GalleryItemKind.VIDEO
				|| kind == GalleryItemKind.MEDIA
				|| kind == GalleryItemKind.AUDIO;
	}

	static boolean persistedGalleryItemHasLocalMedia(PersistedGalleryItem item) {
		if (item == null || item.localMediaKey() == null || item.localMediaKey().isBlank()) {
			return false;
		}
		return MonitorMediaApp.savedGalleryMediaFile(item.localMediaKey()) != null;
	}

	static BufferedImage persistedGalleryPreviewForDisplay(PersistedGalleryItem item, GalleryItemKind kind) {
		if (item == null || kind == null) {
			return null;
		}
		if (kind == GalleryItemKind.YOUTUBE) {
			return null;
		}
		if (kind == GalleryItemKind.AUDIO) {
			BufferedImage localPreview = persistedGalleryAudioPreview(item);
			if (localPreview != null) {
				return localPreview;
			}
			return MonitorYoutubeMusicCache.looksLikeSupportedUrl(item.url())
					? MonitorYoutubeMusicCache.queueEntryPreview(item.url())
					: null;
		}
		String localMediaKey = item.localMediaKey() != null ? item.localMediaKey().trim() : "";
		if (localMediaKey.isBlank()) {
			return MonitorYoutubeMusicCache.looksLikeSupportedUrl(item.url())
					? MonitorYoutubeMusicCache.queueEntryPreview(item.url())
					: null;
		}
		try {
			return switch (kind) {
				case MEDIA -> {
					MonitorMediaApp.LoadedMedia media = MonitorMediaApp.loadSavedGalleryMedia(localMediaKey, null);
					BufferedImage frame = media.frameCount() > 0 ? media.frame(0) : null;
					yield frame != null ? copyBufferedImage(frame) : null;
				}
				case VIDEO -> {
					MonitorMediaApp.LoadedVideo video = MonitorMediaApp.loadSavedGalleryVideo(localMediaKey, null);
					yield video.preview() != null ? copyBufferedImage(video.preview()) : null;
				}
				case AUDIO -> MonitorMediaApp.loadSavedGalleryAudioPreview(localMediaKey, item.title());
				case LIVE_CAMERA, YOUTUBE -> null;
			};
		} catch (Exception exception) {
			Lg2.LOGGER.debug("Failed to hydrate persisted gallery preview {}: {}", localMediaKey, sanitizeMediaError(exception.getMessage()));
			return null;
		}
	}

	static BufferedImage persistedGalleryAudioPreview(PersistedGalleryItem item) {
		if (item == null) {
			return null;
		}
		String localMediaKey = item.localMediaKey() != null ? item.localMediaKey().trim() : "";
		if (localMediaKey.isBlank()) {
			return MonitorYoutubeMusicCache.looksLikeSupportedUrl(item.url())
					? MonitorYoutubeMusicCache.queueEntryPreview(item.url())
					: null;
		}
		try {
			BufferedImage preview = MonitorMediaApp.loadSavedGalleryAudioPreview(localMediaKey, item.title());
			return preview != null ? copyBufferedImage(preview) : null;
		} catch (Exception exception) {
			Lg2.LOGGER.debug("Failed to hydrate persisted audio cover {}: {}", localMediaKey, sanitizeMediaError(exception.getMessage()));
			return MonitorYoutubeMusicCache.looksLikeSupportedUrl(item.url())
					? MonitorYoutubeMusicCache.queueEntryPreview(item.url())
					: null;
		}
	}

	static String standaloneGroupId(ServerLevel level, BlockPos framePos, Direction facing) {
		if (level == null || framePos == null || facing == null) {
			return "";
		}
		return level.dimension() + "|" + framePos.asLong() + "|" + facing.getSerializedName();
	}

	static String normalizedGroupId(ScreenTileState state) {
		if (state == null || state.groupId() == null) {
			return null;
		}
		String normalized = state.groupId().trim();
		return normalized.isEmpty() ? null : normalized;
	}

	static ItemStack ensureMap(ServerLevel level, ItemFrame frame, ScreenTileState state) {
		ItemStack stack = frame.getItem();
		MapId mapId = stack.get(DataComponents.MAP_ID);
		ServerLevel mapLevel = photoMapLevel(level.getServer(), level);
		if (mapId != null && mapLevel.getMapData(mapId) != null) {
			return stack;
		}

		ItemStack replacement = createScreenMap(level, state, false);
		if (!replacement.isEmpty()) {
			copyPersistedMediaState(stack, replacement);
			frame.setItem(replacement, false);
			return replacement;
		}
		return stack;
	}

	static void copyPersistedMediaState(ItemStack source, ItemStack target) {
		if (source == null || source.isEmpty() || target == null || target.isEmpty()) {
			return;
		}
		CustomData customData = source.get(DataComponents.CUSTOM_DATA);
		if (customData == null) {
			return;
		}
		CompoundTag sourceRoot = customData.copyTag();
		CustomData.update(DataComponents.CUSTOM_DATA, target, targetRoot -> copyPersistedMediaTag(sourceRoot, targetRoot));
	}

	static void copyPersistedMediaTag(CompoundTag sourceRoot, CompoundTag targetRoot) {
		if (sourceRoot == null || targetRoot == null || !sourceRoot.contains(PERSISTED_MEDIA_ROOT_TAG)) {
			return;
		}
		CompoundTag mediaTag = sourceRoot.getCompoundOrEmpty(PERSISTED_MEDIA_ROOT_TAG);
		if (mediaTag.isEmpty()) {
			return;
		}
		targetRoot.put(PERSISTED_MEDIA_ROOT_TAG, mediaTag.copy());
	}

	static ItemStack createScreenMap(ServerLevel level, ScreenTileState state) {
		return createScreenMap(level, state, true);
	}

	static ItemStack createScreenMap(ServerLevel level, ScreenTileState state, boolean renderInitialFrame) {
		ServerLevel mapLevel = photoMapLevel(level.getServer(), level);
		ItemStack generated = MapItem.create(mapLevel, PHOTO_MAP_CENTER, PHOTO_MAP_CENTER, (byte) 0, false, false);
		MapId mapId = generated.get(DataComponents.MAP_ID);
		if (mapId == null) {
			return ItemStack.EMPTY;
		}

		ItemStack screenMap = new ItemStack(Items.FILLED_MAP);
		screenMap.set(DataComponents.MAP_ID, mapId);
		writeScreenState(screenMap, state);
		MapItemSavedData mapData = mapLevel.getMapData(mapId);
		if (renderInitialFrame && mapData != null) {
			byte[][] tiles = renderTiles(level.getServer(), new RenderWork(null, state.powered(), state.viewMode(), state.launcherPage(), 1, 1, 0L, null, null, null, null, null, false, null, List.of()));
			applyFrameToMap(mapData, tiles[0]);
		}
		return screenMap;
	}

	static void writeScreenState(ItemStack stack, ScreenTileState state) {
		if (stack == null || stack.isEmpty() || state == null) {
			return;
		}
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			CompoundTag screenTag = tag.getCompoundOrEmpty(SCREEN_ROOT_TAG);
			screenTag.putBoolean(LINK_LOCKED_TAG, state.attachmentMask() != CONNECTION_ALL);
			screenTag.putInt(ATTACHMENT_MASK_TAG, state.attachmentMask() & 0xF);
			screenTag.putString(GROUP_ID_TAG, state.groupId() == null ? "" : state.groupId());
			screenTag.putInt(GRID_WIDTH_TAG, state.gridWidth());
			screenTag.putInt(GRID_HEIGHT_TAG, state.gridHeight());
			screenTag.putInt(TILE_X_TAG, state.tileX());
			screenTag.putInt(TILE_Y_TAG, state.tileY());
			screenTag.putInt(CONNECTION_MASK_TAG, state.connectionMask() & 0xF);
			screenTag.putBoolean(POWERED_TAG, state.powered());
			screenTag.putString(VIEW_MODE_TAG, state.viewMode().serializedName());
			screenTag.putInt(LAUNCHER_PAGE_TAG, Math.max(0, state.launcherPage()));
			tag.put(SCREEN_ROOT_TAG, screenTag);
		});
	}

	static ScreenTileState readScreenState(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData == null) {
			return null;
		}
		CompoundTag root = customData.copyTag();
		if (!root.contains(SCREEN_ROOT_TAG)) {
			return null;
		}
		CompoundTag screenTag = root.getCompoundOrEmpty(SCREEN_ROOT_TAG);
		boolean legacyLinkLocked = screenTag.getBooleanOr(LINK_LOCKED_TAG, false);
		int attachmentMask = screenTag.contains(ATTACHMENT_MASK_TAG)
				? screenTag.getIntOr(ATTACHMENT_MASK_TAG, CONNECTION_ALL)
				: (legacyLinkLocked ? 0 : CONNECTION_ALL);
		return new ScreenTileState(
				attachmentMask & 0xF,
				Math.max(1, screenTag.getIntOr(GRID_WIDTH_TAG, 1)),
				Math.max(1, screenTag.getIntOr(GRID_HEIGHT_TAG, 1)),
				Math.max(0, screenTag.getIntOr(TILE_X_TAG, 0)),
				Math.max(0, screenTag.getIntOr(TILE_Y_TAG, 0)),
				screenTag.getIntOr(CONNECTION_MASK_TAG, 0) & 0xF,
				screenTag.getBooleanOr(POWERED_TAG, false),
				ScreenViewMode.fromTag(screenTag.getStringOr(VIEW_MODE_TAG, ScreenViewMode.HOME.serializedName())),
				Math.max(0, screenTag.getIntOr(LAUNCHER_PAGE_TAG, 0)),
				screenTag.getStringOr(GROUP_ID_TAG, "")
		);
	}

	static List<PersistedGalleryItem> readPersistedGalleryState(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return List.of();
		}
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData == null) {
			return List.of();
		}
		CompoundTag root = customData.copyTag();
		if (!root.contains(PERSISTED_MEDIA_ROOT_TAG)) {
			return List.of();
		}
		CompoundTag mediaTag = root.getCompoundOrEmpty(PERSISTED_MEDIA_ROOT_TAG);
		int count = Math.max(0, mediaTag.getIntOr(PERSISTED_GALLERY_COUNT_TAG, 0));
		if (count <= 0) {
			return List.of();
		}
		List<PersistedGalleryItem> items = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			CompoundTag itemTag = mediaTag.getCompoundOrEmpty(PERSISTED_GALLERY_ITEM_PREFIX + index);
			String url = itemTag.getStringOr(PERSISTED_GALLERY_URL_TAG, "").trim();
			if (url.isBlank()) {
				continue;
			}
			String title = itemTag.getStringOr(PERSISTED_GALLERY_TITLE_TAG, "").trim();
			String subtitle = itemTag.getStringOr(PERSISTED_GALLERY_SUBTITLE_TAG, "").trim();
			String localMediaKey = itemTag.getStringOr(PERSISTED_GALLERY_LOCAL_MEDIA_TAG, "").trim();
			GalleryItemKind kind = effectiveGalleryItemKind(
					url,
					localMediaKey,
					GalleryItemKind.fromPersisted(itemTag.getStringOr(PERSISTED_GALLERY_KIND_TAG, ""), url)
			);
			items.add(new PersistedGalleryItem(title, subtitle, url, kind, localMediaKey));
		}
		return List.copyOf(items);
	}

	static PersistedWallpaperState readPersistedWallpaperState(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData == null) {
			return null;
		}
		CompoundTag root = customData.copyTag();
		if (!root.contains(PERSISTED_MEDIA_ROOT_TAG)) {
			return null;
		}
		CompoundTag mediaTag = root.getCompoundOrEmpty(PERSISTED_MEDIA_ROOT_TAG);
		String url = mediaTag.getStringOr(PERSISTED_WALLPAPER_URL_TAG, "").trim();
		if (url.isBlank()) {
			return null;
		}
		return new PersistedWallpaperState(
				url,
				parsePersistedScaleMode(mediaTag.getStringOr(PERSISTED_WALLPAPER_SCALE_TAG, MediaScaleMode.FIT.name())),
				parsePersistedWallpaperBackgroundMode(mediaTag.getStringOr(PERSISTED_WALLPAPER_BACKGROUND_MODE_TAG, ""))
		);
	}

	static PlayerBackgroundMode readPersistedPlayerBackgroundMode(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData == null) {
			return null;
		}
		CompoundTag root = customData.copyTag();
		if (!root.contains(PERSISTED_MEDIA_ROOT_TAG)) {
			return null;
		}
		CompoundTag mediaTag = root.getCompoundOrEmpty(PERSISTED_MEDIA_ROOT_TAG);
		return PlayerBackgroundMode.fromPersisted(mediaTag.getStringOr(PERSISTED_PLAYER_BACKGROUND_MODE_TAG, ""));
	}

	static PersistedPlayerBackgroundState readPersistedPlayerBackgroundState(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData == null) {
			return null;
		}
		CompoundTag root = customData.copyTag();
		if (!root.contains(PERSISTED_MEDIA_ROOT_TAG)) {
			return null;
		}
		CompoundTag mediaTag = root.getCompoundOrEmpty(PERSISTED_MEDIA_ROOT_TAG);
		String url = mediaTag.getStringOr(PERSISTED_PLAYER_BACKGROUND_URL_TAG, "").trim();
		if (url.isBlank()) {
			return null;
		}
		return new PersistedPlayerBackgroundState(
				url,
				parsePersistedScaleMode(mediaTag.getStringOr(PERSISTED_PLAYER_BACKGROUND_SCALE_TAG, MediaScaleMode.FILL.name()))
		);
	}

	static MediaScaleMode parsePersistedScaleMode(String value) {
		if (value == null || value.isBlank()) {
			return MediaScaleMode.FIT;
		}
		try {
			return MediaScaleMode.valueOf(value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ignored) {
			return MediaScaleMode.FIT;
		}
	}

	static PlayerBackgroundMode parsePersistedWallpaperBackgroundMode(String value) {
		return safeWallpaperBackgroundMode(PlayerBackgroundMode.fromPersisted(value));
	}

	static void writePersistedGalleryState(ItemStack stack, List<GalleryItem> galleryItems) {
		writePersistedGalleryState(stack, galleryItems, null, null, null);
	}

	static void writePersistedGalleryState(ItemStack stack, List<GalleryItem> galleryItems, PersistedWallpaperState wallpaperState) {
		writePersistedGalleryState(stack, galleryItems, wallpaperState, null, null);
	}

	static void writePersistedGalleryState(ItemStack stack, List<GalleryItem> galleryItems, PersistedWallpaperState wallpaperState, PlayerBackgroundMode playerBackgroundMode) {
		writePersistedGalleryState(stack, galleryItems, wallpaperState, null, playerBackgroundMode);
	}

	static void writePersistedGalleryState(
			ItemStack stack,
			List<GalleryItem> galleryItems,
			PersistedWallpaperState wallpaperState,
			PersistedPlayerBackgroundState playerBackgroundState,
			PlayerBackgroundMode playerBackgroundMode
	) {
		writePersistedMediaState(stack, persistedGalleryItems(galleryItems), wallpaperState, playerBackgroundState, playerBackgroundMode);
	}

	static void writePersistedMediaState(
			ItemStack stack,
			List<PersistedGalleryItem> persistedItems,
			PersistedWallpaperState wallpaperState,
			PersistedPlayerBackgroundState playerBackgroundState,
			PlayerBackgroundMode playerBackgroundMode
	) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		List<PersistedGalleryItem> normalizedItems = persistedItems != null ? persistedItems : List.of();
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			tag.remove(PERSISTED_MEDIA_ROOT_TAG);
			boolean hasWallpaperState = wallpaperState != null && wallpaperState.url() != null && !wallpaperState.url().isBlank();
			boolean hasPlayerBackgroundState = playerBackgroundState != null && playerBackgroundState.url() != null && !playerBackgroundState.url().isBlank();
			boolean hasPlayerBackgroundMode = playerBackgroundMode != null;
			if (normalizedItems.isEmpty() && !hasWallpaperState && !hasPlayerBackgroundState && !hasPlayerBackgroundMode) {
				return;
			}
			CompoundTag mediaTag = new CompoundTag();
			mediaTag.putInt(PERSISTED_GALLERY_COUNT_TAG, normalizedItems.size());
			for (int index = 0; index < normalizedItems.size(); index++) {
				PersistedGalleryItem item = normalizedItems.get(index);
				CompoundTag itemTag = new CompoundTag();
				itemTag.putString(PERSISTED_GALLERY_TITLE_TAG, item.title() == null ? "" : item.title());
				itemTag.putString(PERSISTED_GALLERY_SUBTITLE_TAG, item.subtitle() == null ? "" : item.subtitle());
				itemTag.putString(PERSISTED_GALLERY_URL_TAG, item.url() == null ? "" : item.url());
				itemTag.putString(PERSISTED_GALLERY_KIND_TAG, item.kind() != null ? item.kind().persistedName() : GalleryItemKind.MEDIA.persistedName());
				if (item.localMediaKey() != null && !item.localMediaKey().isBlank()) {
					itemTag.putString(PERSISTED_GALLERY_LOCAL_MEDIA_TAG, item.localMediaKey());
				}
				mediaTag.put(PERSISTED_GALLERY_ITEM_PREFIX + index, itemTag);
			}
			if (hasWallpaperState) {
				mediaTag.putString(PERSISTED_WALLPAPER_URL_TAG, wallpaperState.url());
				mediaTag.putString(
						PERSISTED_WALLPAPER_SCALE_TAG,
						(wallpaperState.scaleMode() != null ? wallpaperState.scaleMode() : MediaScaleMode.FIT).name().toLowerCase(Locale.ROOT)
				);
				mediaTag.putString(
						PERSISTED_WALLPAPER_BACKGROUND_MODE_TAG,
						safeWallpaperBackgroundMode(wallpaperState.backgroundMode()).persistedName()
				);
			}
			if (hasPlayerBackgroundState) {
				mediaTag.putString(PERSISTED_PLAYER_BACKGROUND_URL_TAG, playerBackgroundState.url());
				mediaTag.putString(
						PERSISTED_PLAYER_BACKGROUND_SCALE_TAG,
						(playerBackgroundState.scaleMode() != null ? playerBackgroundState.scaleMode() : MediaScaleMode.FIT).name().toLowerCase(Locale.ROOT)
				);
			}
			if (hasPlayerBackgroundMode) {
				mediaTag.putString(PERSISTED_PLAYER_BACKGROUND_MODE_TAG, playerBackgroundMode.persistedName());
			}
			tag.put(PERSISTED_MEDIA_ROOT_TAG, mediaTag);
		});
	}

	static void writePersistedWallpaperState(ItemStack stack, PersistedWallpaperState wallpaperState) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		List<PersistedGalleryItem> persistedItems = readPersistedGalleryState(stack);
		writePersistedMediaState(
				stack,
				persistedItems,
				wallpaperState,
				readPersistedPlayerBackgroundState(stack),
				readPersistedPlayerBackgroundMode(stack)
		);
	}

	static void writePersistedPlayerBackgroundState(ItemStack stack, PersistedPlayerBackgroundState playerBackgroundState) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		List<PersistedGalleryItem> persistedItems = readPersistedGalleryState(stack);
		writePersistedMediaState(
				stack,
				persistedItems,
				readPersistedWallpaperState(stack),
				playerBackgroundState,
				readPersistedPlayerBackgroundMode(stack)
		);
	}

	static void writePersistedPlayerBackgroundMode(ItemStack stack, PlayerBackgroundMode playerBackgroundMode) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		List<PersistedGalleryItem> persistedItems = readPersistedGalleryState(stack);
		writePersistedMediaState(
				stack,
				persistedItems,
				readPersistedWallpaperState(stack),
				readPersistedPlayerBackgroundState(stack),
				playerBackgroundMode
		);
	}

	static List<PersistedGalleryItem> persistedGalleryItems(List<GalleryItem> galleryItems) {
		if (galleryItems == null || galleryItems.isEmpty()) {
			return List.of();
		}
		List<PersistedGalleryItem> items = new ArrayList<>(galleryItems.size());
		for (GalleryItem item : galleryItems) {
			if (item == null || item.url() == null || item.url().isBlank()) {
				continue;
			}
			GalleryItemKind kind = effectiveGalleryItemKind(item);
			if (kind == GalleryItemKind.LIVE_CAMERA) {
				continue;
			}
			PersistedGalleryItem persistedItem = new PersistedGalleryItem(
					item.title() == null ? "" : item.title(),
					item.subtitle() == null ? "" : item.subtitle(),
					item.url().trim(),
					kind,
					item.localMediaKey() == null ? "" : item.localMediaKey().trim()
			);
			if (persistedGalleryItemRequiresLocalMedia(kind, persistedItem) && !persistedGalleryItemHasLocalMedia(persistedItem)) {
				continue;
			}
			items.add(persistedItem);
		}
		return List.copyOf(items);
	}

	static boolean isPowered(ServerLevel level, ItemFrame frame) {
		if (frame != null && frame.getTags().contains(RocketLaunchEventSystem.MOUNTED_SCREEN_TAG)) {
			// Launch devices keep the power state captured at lift-off while their
			// support is represented by the moving rocket body.
			return true;
		}
		BlockPos supportPos = frame.blockPosition().relative(frame.getDirection().getOpposite());
		return level.hasNeighborSignal(supportPos)
				|| level.getBestNeighborSignal(supportPos) > 0
				|| level.hasNeighborSignal(frame.blockPosition());
	}

	static RenderWork createRenderWork(MinecraftServer server, ScreenComponent component, ScreenViewMode viewMode, int launcherPage, MediaRuntimeState mediaState) {
		if (component == null) {
			return null;
		}
		MediaVisualSnapshot mediaSnapshot = null;
		MaxVisualSnapshot maxSnapshot = viewMode == ScreenViewMode.MAX || viewMode == ScreenViewMode.HOME || MonitorMaxRuntime.hasVisibleCall(component.runtimeKey())
				? MonitorMaxRuntime.captureSnapshot(server, component)
				: null;
		CameraAppVisualSnapshot cameraAppSnapshot = viewMode == ScreenViewMode.CAMERA_APP
				? MonitorCameraRuntime.captureSnapshot(server, component)
				: null;
		SupportVisualSnapshot supportSnapshot = viewMode == ScreenViewMode.SUPPORT
				? MonitorSupportRuntime.captureSnapshot(server, component)
				: null;
		WallpaperVisualSnapshot wallpaperSnapshot = captureWallpaperSnapshot(mediaState, viewMode);
		long mediaVersion = 0L;
		if (isPlayerMode(viewMode)) {
			mediaSnapshot = captureMediaSnapshot(server, mediaState, component);
			mediaVersion = mediaSnapshot != null ? mediaSnapshot.version() : 0L;
		}
		boolean transparentOutput = transparentOutputPossible(viewMode, mediaSnapshot, wallpaperSnapshot);
		return new RenderWork(
				component.runtimeKey(),
				component.powered(),
				viewMode,
				launcherPage,
				component.width(),
				component.height(),
				mediaVersion,
				mediaSnapshot,
				cameraAppSnapshot,
				maxSnapshot,
				supportSnapshot,
				wallpaperSnapshot,
				transparentOutput,
				DEBUG_AIM_CURSOR_ENABLED ? DEBUG_AIM_CURSORS.get(component.runtimeKey()) : null,
				captureRenderTileTargets(server, component)
		);
	}

	static boolean transparentOutputPossible(ScreenViewMode viewMode, MediaVisualSnapshot mediaSnapshot, WallpaperVisualSnapshot wallpaperSnapshot) {
		if (wallpaperSnapshot != null) {
			return true;
		}
		if (!isPlayerMode(viewMode) || mediaSnapshot == null) {
			return false;
		}
		PlayerBackgroundMode playerBackgroundMode = mediaSnapshot.playerBackgroundMode();
		if (playerBackgroundMode == PlayerBackgroundMode.EMPTY) {
			return true;
		}
		return playerBackgroundMode == PlayerBackgroundMode.GALLERY
				&& mediaSnapshot.galleryBackgroundAvailable()
				&& mediaSnapshot.playerBackgroundFrame() != null;
	}

	static List<RenderTileTarget> captureRenderTileTargets(MinecraftServer server, ScreenComponent component) {
		if (server == null || component == null || component.frameCoords().isEmpty()) {
			return List.of();
		}
		ServerLevel level = server.getLevel(component.runtimeKey().dimension());
		if (level == null) {
			return List.of();
		}
		ServerLevel mapStorageLevel = photoMapLevel(server, level);
		List<RenderTileTarget> targets = new ArrayList<>(component.frameCoords().size());
		for (Map.Entry<ItemFrame, TileCoord> entry : component.frameCoords().entrySet()) {
			ItemFrame frame = entry.getKey();
			if (frame == null || !frame.isAlive()) {
				continue;
			}
			ItemStack stack = frame.getItem();
			ScreenTileState state = readScreenState(stack);
			MapId mapId = stack.get(DataComponents.MAP_ID);
			if (state == null || mapId == null) {
				continue;
			}
			MapItemSavedData mapData = mapStorageLevel.getMapData(mapId);
			if (mapData == null) {
				continue;
			}
			int tileIndex = state.tileY() * component.width() + state.tileX();
			if (tileIndex < 0 || tileIndex >= component.width() * component.height()) {
				continue;
			}
			targets.add(new RenderTileTarget(
					tileIndex,
					mapId,
					mapData.scale,
					mapData.locked,
					lastRenderedMapFrame(mapId.id())
			));
		}
		return targets.isEmpty() ? List.of() : List.copyOf(targets);
	}

	static MediaVisualSnapshot captureMediaSnapshot(MinecraftServer server, MediaRuntimeState state, ScreenComponent component) {
		if (state == null) {
			return new MediaVisualSnapshot(ScreenViewMode.GALLERY, 0L, null, null, null, false, true, false, false, 0, false, false, false, false, false, false, false, false, false, false, false, false, 0, 0, 0.0F, 0.0F, 0.0F, "", false, MediaOverlayMode.CONTROLS, MediaScaleMode.FIT, MediaScaleMode.FILL, PlayerBackgroundMode.BLACK, false, "", "ВСТАВЬ URL", "", "", null, WindowedSnapshot.empty(), WindowedSnapshot.empty(), false, MediaActionGlyph.DOWNLOAD, MediaActionVisualState.IDLE, false, MediaActionGlyph.WALLPAPER, MediaActionVisualState.IDLE, false, MediaRepeatMode.OFF, false, 0, -1, null);
		}
		boolean youtubeMode = state.mode == ScreenViewMode.YOUTUBE;
		boolean youtubeMusicMode = state.mode == ScreenViewMode.YOUTUBE_MUSIC;
		boolean musicPlayerLayout = usesMusicPlayerLayoutLocked(state);
		boolean youtubeFamilyMode = youtubeMode || youtubeMusicMode;
		boolean galleryMode = state.mode == ScreenViewMode.GALLERY;
		boolean libraryMode = isLibraryAppMode(state.mode);
		boolean galleryBrowser = libraryMode && state.gallerySurfaceMode == GallerySurfaceMode.BROWSER;
		boolean galleryPickerMode = galleryBrowser && state.playerBackgroundGalleryPickerOpen;
		boolean galleryBackedYoutube = isGalleryBackedYoutubeLocked(state);
		boolean streamPlayback = isStreamPlaybackLocked(state);
		boolean liveCameraPlayback = state.streamKind == PlaybackStreamKind.LIVE_CAMERA;
		UiLayout snapshotLayout = component != null ? createUiLayout(component.width(), component.height()) : null;
		BufferedImage liveControlsFrame = liveCameraPlayback && state.overlayMode == MediaOverlayMode.CONTROLS && component != null
				? mapPaletteImage(state.liveCameraBufferedTiles, component.width(), component.height())
				: null;
		WindowedSnapshot<GalleryCardSnapshot> galleryCards = galleryBrowser
				? galleryCardSnapshots(server, component, state, component != null ? component.runtimeKey() : null, snapshotLayout)
				: WindowedSnapshot.empty();
		BufferedImage frame = streamPlayback
				? liveControlsFrame != null ? liveControlsFrame : state.streamFrame
				: !galleryBrowser && state.loadedMedia != null ? state.loadedMedia.frame(state.frameIndex) : null;
		BufferedImage backgroundFrame = youtubeMusicMode && state.loading && state.loadingBackdropFrame != null
				? state.loadingBackdropFrame
				: frame;
		BufferedImage playerBackgroundFrame = state.playerBackgroundMedia != null
				? state.playerBackgroundMedia.frame(state.playerBackgroundFrameIndex)
				: null;
		boolean hasMedia = streamPlayback
				? hasDisplayableMediaLocked(state)
				: galleryBrowser ? galleryCards.totalCount() > 0 : state.loadedMedia != null;
		boolean playbackControlsVisible = mediaControlUiVisibleLocked(state);
		boolean timelineVisible = (streamPlayback && !liveCameraPlayback) || (!galleryBrowser && state.loadedMedia != null && state.loadedMedia.frameCount() > 1);
		boolean centerPlayPauseVisible = (streamPlayback && !liveCameraPlayback) || (!galleryBrowser && state.loadedMedia != null && state.loadedMedia.animated());
		boolean droneControlVisible = currentDroneControlActionVisibleLocked(state);
		boolean timelineSeekable = streamPlayback
				? state.durationMs > 0L && !state.liveStream
				: !galleryBrowser && state.loadedMedia != null && state.loadedMedia.frameCount() > 1;
		int timelineIndex = streamPlayback
				? (int) Math.min(Integer.MAX_VALUE, state.positionMs)
				: !galleryBrowser && state.loadedMedia != null ? Math.floorMod(state.frameIndex, Math.max(1, state.loadedMedia.frameCount())) : 0;
		int timelineCount = streamPlayback
				? (int) Math.min(Integer.MAX_VALUE, state.durationMs)
				: !galleryBrowser && state.loadedMedia != null ? state.loadedMedia.frameCount() : 0;
		float timelineFraction = streamPlayback
				? youtubeTimelineFraction(state)
				: !galleryBrowser && state.loadedMedia != null && state.loadedMedia.frameCount() > 1
				? (float) timelineIndex / (float) Math.max(1, state.loadedMedia.frameCount() - 1)
				: 0.0F;
		float bufferedStartFraction = streamPlayback ? youtubeBufferedFraction(state, state.bufferedStartMs) : 0.0F;
		float bufferedEndFraction = streamPlayback ? youtubeBufferedFraction(state, state.bufferedEndMs) : 0.0F;
		String timelineLabel = streamPlayback
				? state.liveStream ? "LIVE" : formatPlaybackTime(state.positionMs) + " / " + formatPlaybackTime(state.durationMs)
				: (!galleryBrowser && timelineCount > 0 ? (timelineIndex + 1) + "/" + Math.max(1, timelineCount) : "");
		boolean youtubeQueueWindowOpen = youtubeFamilyMode && state.youtubeQueueOpen;
		MediaActionGlyph actionGlyph = resolvedActionGlyph(state);
		MediaActionVisualState actionState = resolvedActionVisualState(state);
		boolean actionVisible = resolvedActionVisible(state);
		boolean wallpaperActionVisible = false;
		MediaActionGlyph wallpaperActionGlyph = currentGalleryItemIsWallpaperLocked(state) ? MediaActionGlyph.CHECK : MediaActionGlyph.WALLPAPER;
		MediaActionVisualState wallpaperActionState = currentGalleryItemIsWallpaperLocked(state) ? MediaActionVisualState.COMPLETE : MediaActionVisualState.IDLE;
		PlayerBackgroundMode playerBackgroundMode = resolvedPlayerBackgroundModeLocked(state);
		boolean galleryBackgroundAvailable = state.playerBackgroundUrl != null
				&& !state.playerBackgroundUrl.isBlank()
				&& state.playerBackgroundMedia != null;
		MediaOverlayWindowSnapshot overlayWindow = galleryMode && state.galleryFileMenuOpen
				? galleryFileMenuWindowSnapshot(state)
				: galleryMode && state.gallerySlideshowSettingsOpen
				? gallerySlideshowWindowSnapshot(state)
				: state.playerBackgroundMenuOpen
				? playerBackgroundMenuWindowSnapshot(
						state,
						playerBackgroundMode,
						galleryBackgroundAvailable,
						playerBackgroundScaleButtonModeLocked(state, playerBackgroundMode)
				)
				: youtubeQueueWindowOpen
				? youtubeQueueWindowSnapshot(component.runtimeKey(), state, snapshotLayout)
				: galleryMode && state.galleryDeleteConfirmOpen
				? galleryDeleteConfirmWindowSnapshot(state)
				: null;
		return new MediaVisualSnapshot(
				state.mode,
				state.version,
				frame,
				backgroundFrame,
				playerBackgroundFrame,
				hasMedia,
				galleryBrowser,
				galleryPickerMode,
				state.galleryBulkSelectionMode,
				state.galleryBulkSelectedKeys.size(),
				state.gallerySlideshowEnabled,
				galleryMode && currentGalleryItemSavedLocked(state),
				galleryBackedYoutube,
				musicPlayerLayout,
				streamPlayback,
				playbackControlsVisible,
				state.loading,
				state.waitingForLink,
				timelineVisible,
				centerPlayPauseVisible,
				droneControlVisible,
				timelineSeekable,
				timelineIndex,
				timelineCount,
				timelineFraction,
				bufferedStartFraction,
				bufferedEndFraction,
				timelineLabel,
				isPlaybackPausedLocked(state),
				state.overlayMode,
				state.scaleMode,
				state.playerBackgroundScaleMode != null ? state.playerBackgroundScaleMode : MediaScaleMode.FILL,
				playerBackgroundMode,
				galleryBackgroundAvailable,
				state.statusText,
				youtubeMusicMode ? "YT MUSIC URL" : youtubeMode ? "YOUTUBE URL" : "URL",
				state.mediaTitle != null ? state.mediaTitle : "",
				state.mediaSubtitle != null ? state.mediaSubtitle : "",
				state.progress.snapshot(),
				WindowedSnapshot.empty(),
				galleryCards,
				actionVisible,
				actionGlyph,
				actionState,
				wallpaperActionVisible,
				wallpaperActionGlyph,
				wallpaperActionState,
				youtubeMusicMode && state.youtubeMusicShuffleEnabled,
				resolvedRepeatModeLocked(state),
				youtubeFamilyMode && state.youtubeQueueOpen,
				youtubeFamilyMode ? state.youtubeQueueScroll : libraryMode ? state.galleryScroll : 0,
				youtubeFamilyMode ? state.youtubeQueueIndex : libraryMode ? state.galleryIndex : -1,
				overlayWindow
		);
	}

	static WallpaperVisualSnapshot captureWallpaperSnapshot(MediaRuntimeState state, ScreenViewMode viewMode) {
		if (state == null || !wallpaperVisibleForViewMode(viewMode, state) || state.wallpaperMedia == null) {
			return null;
		}
		BufferedImage frame = state.wallpaperMedia.frame(state.wallpaperFrameIndex);
		if (frame == null) {
			return null;
		}
		return new WallpaperVisualSnapshot(
				frame,
				state.wallpaperScaleMode != null ? state.wallpaperScaleMode : MediaScaleMode.FIT,
				resolvedWallpaperBackgroundModeLocked(state)
		);
	}

	static byte[][] renderTiles(MinecraftServer server, RenderWork work) {
		if (work == null) {
			return new byte[0][];
		}
		if (staticRenderCacheable(work)) {
			RenderCacheKey key = new RenderCacheKey(work.powered(), work.viewMode(), work.launcherPage(), work.width(), work.height());
			byte[][] cached = TILE_CACHE.get(key);
			if (cached != null) {
				return cached;
			}
		}

		int pixelWidth = work.width() * MAP_SIZE;
		int pixelHeight = work.height() * MAP_SIZE;
		BufferedImage canvas = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = canvas.createGraphics();
		configureUiGraphics(graphics, dynamicRenderWork(work));
		drawBaseBackground(graphics, work.width(), work.height(), work.powered());
		if (work.powered() && work.wallpaperSnapshot() != null && work.wallpaperSnapshot().frame() != null) {
			UiLayout layout = createUiLayout(work.width(), work.height());
			drawWallpaperSnapshot(graphics, layout, mediaCanvasRect(layout), work.wallpaperSnapshot());
		}
		if (work.powered()) {
			UiLayout layout = createUiLayout(work.width(), work.height());
			if (work.viewMode() == ScreenViewMode.HOME) {
				drawHomeScreen(graphics, layout, work.runtimeKey(), work.launcherPage(), work.maxSnapshot());
			} else if (work.viewMode() == ScreenViewMode.CAMERA_APP) {
				MonitorCameraRuntime.drawScreen(graphics, layout, appForViewMode(work.viewMode()), work.runtimeKey(), work.cameraAppSnapshot());
			} else if (work.viewMode() == ScreenViewMode.MAX) {
				MonitorMaxRuntime.drawMaxScreen(graphics, layout, appForViewMode(work.viewMode()), work.runtimeKey(), work.maxSnapshot());
			} else if (work.viewMode() == ScreenViewMode.SUPPORT) {
				MonitorSupportRuntime.drawScreen(graphics, layout, appForViewMode(work.viewMode()), work.runtimeKey(), work.supportSnapshot());
			} else {
				drawAppScreen(graphics, layout, appForViewMode(work.viewMode()), work.runtimeKey(), server, work.mediaSnapshot());
			}
			if (work.viewMode() != ScreenViewMode.MAX && MonitorMaxRuntime.hasCallOverlay(work.maxSnapshot())) {
				MonitorMaxRuntime.drawCallOverlay(graphics, layout, work.runtimeKey(), work.maxSnapshot());
			}
		}
		drawDebugAimCursor(graphics, work.debugAimCursor(), pixelWidth, pixelHeight);
		graphics.dispose();

		int[] rgbPixels = canvas.getRaster().getDataBuffer() instanceof DataBufferInt dataBuffer
				? dataBuffer.getData()
				: canvas.getRGB(0, 0, pixelWidth, pixelHeight, null, 0, pixelWidth);
		byte[][] tiles = new byte[work.width() * work.height()][MAP_SIZE * MAP_SIZE];
		quantizeTiles(work, rgbPixels, pixelWidth, tiles);

		if (staticRenderCacheable(work)) {
			TILE_CACHE.put(new RenderCacheKey(work.powered(), work.viewMode(), work.launcherPage(), work.width(), work.height()), tiles);
		}
		return tiles;
	}

	static boolean staticRenderCacheable(RenderWork work) {
		return work != null
				&& work.debugAimCursor() == null
				&& !isPlayerMode(work.viewMode())
				&& work.wallpaperSnapshot() == null
				&& work.cameraAppSnapshot() == null
				&& work.maxSnapshot() == null
				&& work.supportSnapshot() == null;
	}

	static boolean dynamicRenderWork(RenderWork work) {
		if (work == null) {
			return false;
		}
		if (work.debugAimCursor() != null) {
			return true;
		}
		if (work.maxSnapshot() != null && work.maxSnapshot().dynamic()) {
			return true;
		}
		if (work.cameraAppSnapshot() != null && work.cameraAppSnapshot().dynamic()) {
			return true;
		}
		if (work.supportSnapshot() != null) {
			return true;
		}
		if (work.mediaSnapshot() == null) {
			return work.wallpaperSnapshot() != null;
		}
		MediaVisualSnapshot state = work.mediaSnapshot();
		return state.streamPlayback()
				|| state.frameCount() > 1
				|| work.wallpaperSnapshot() != null
				|| queueCacheAnimationActive(state.overlayWindow());
	}

	static void drawDebugAimCursor(Graphics2D graphics, UiPoint point, int width, int height) {
		if (!DEBUG_AIM_CURSOR_ENABLED || graphics == null || point == null || width <= 0 || height <= 0) {
			return;
		}
		int x = clampInt(point.x(), 0, width - 1);
		int y = clampInt(point.y(), 0, height - 1);
		int radius = clampInt(Math.min(width, height) / 22, 5, 14);
		int gap = Math.max(2, radius / 3);
		Stroke previousStroke = graphics.getStroke();
		Color previousColor = graphics.getColor();

		graphics.setStroke(new BasicStroke(4.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.setColor(new Color(0, 0, 0, 210));
		graphics.drawLine(x - radius, y, x - gap, y);
		graphics.drawLine(x + gap, y, x + radius, y);
		graphics.drawLine(x, y - radius, x, y - gap);
		graphics.drawLine(x, y + gap, x, y + radius);
		graphics.drawOval(x - gap, y - gap, gap * 2, gap * 2);

		graphics.setStroke(new BasicStroke(2.0F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.setColor(new Color(0, 255, 255, 245));
		graphics.drawLine(x - radius, y, x - gap, y);
		graphics.drawLine(x + gap, y, x + radius, y);
		graphics.drawLine(x, y - radius, x, y - gap);
		graphics.drawLine(x, y + gap, x, y + radius);
		graphics.setColor(new Color(255, 40, 190, 245));
		graphics.drawOval(x - gap, y - gap, gap * 2, gap * 2);
		graphics.setColor(Color.WHITE);
		graphics.fillOval(x - 1, y - 1, 3, 3);

		graphics.setStroke(previousStroke);
		graphics.setColor(previousColor);
	}

	static void configureUiGraphics(Graphics2D graphics) {
		configureUiGraphics(graphics, false);
	}

	static void configureUiGraphics(Graphics2D graphics, boolean dynamic) {
		graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, dynamic ? RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED : RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, dynamic ? RenderingHints.VALUE_COLOR_RENDER_SPEED : RenderingHints.VALUE_COLOR_RENDER_QUALITY);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, dynamic ? RenderingHints.VALUE_RENDER_SPEED : RenderingHints.VALUE_RENDER_QUALITY);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
	}

	static void quantizeTiles(RenderWork work, int[] rgbPixels, int pixelWidth, byte[][] tiles) {
		if (work == null || rgbPixels == null || tiles == null) {
			return;
		}
		int tileCount = work.width() * work.height();
		boolean dither = true;
		if (tileCount <= 1 || quantizeExecutor == null) {
			for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
				quantizeSingleTile(work.width(), rgbPixels, pixelWidth, tileIndex, tiles[tileIndex], work.transparentOutput(), dither);
			}
			return;
		}

		int workerCount = Math.max(1, Math.min(tileCount, monitorTileQuantizerThreads()));
		if (workerCount <= 1) {
			for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
				quantizeSingleTile(work.width(), rgbPixels, pixelWidth, tileIndex, tiles[tileIndex], work.transparentOutput(), dither);
			}
			return;
		}
		int chunkSize = Math.max(1, (tileCount + workerCount - 1) / workerCount);
		CompletableFuture<?>[] futures = new CompletableFuture<?>[workerCount];
		for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
			int startTileIndex = workerIndex * chunkSize;
			int endTileIndex = Math.min(tileCount, startTileIndex + chunkSize);
			if (startTileIndex >= endTileIndex) {
				futures[workerIndex] = CompletableFuture.completedFuture(null);
				continue;
			}
			futures[workerIndex] = CompletableFuture.runAsync(() -> {
				for (int tileIndex = startTileIndex; tileIndex < endTileIndex; tileIndex++) {
					quantizeSingleTile(work.width(), rgbPixels, pixelWidth, tileIndex, tiles[tileIndex], work.transparentOutput(), dither);
				}
			}, quantizeExecutor);
		}
		CompletableFuture.allOf(futures).join();
	}

	static void quantizeSingleTile(int tilesWide, int[] rgbPixels, int pixelWidth, int tileIndex, byte[] tile, boolean transparentOutput, boolean dither) {
		if (tilesWide <= 0 || rgbPixels == null || tile == null) {
			return;
		}
		if (!transparentOutput) {
			quantizeSingleTileOpaque(tilesWide, rgbPixels, pixelWidth, tileIndex, tile, dither);
			return;
		}
		int tileX = Math.floorMod(tileIndex, tilesWide);
		int tileY = tileIndex / tilesWide;
		int tileOriginX = tileX * MAP_SIZE;
		int tileOriginY = tileY * MAP_SIZE;
		for (int localY = 0; localY < MAP_SIZE; localY++) {
			int globalY = tileOriginY + localY;
			int rowStart = globalY * pixelWidth + tileOriginX;
			int tileRowStart = localY * MAP_SIZE;
			for (int localX = 0; localX < MAP_SIZE; localX++) {
				int globalX = tileOriginX + localX;
				int argb = rgbPixels[rowStart + localX];
				int alpha = (argb >>> 24) & 0xFF;
				if (alpha <= MAP_TRANSPARENT_ALPHA_THRESHOLD) {
					tile[tileRowStart + localX] = 0;
					continue;
				}
				int rgb = argb & 0xFFFFFF;
				tile[tileRowStart + localX] = dither
						? MapPaletteQuantizer.quantizeDithered(rgb, globalX, globalY)
						: MapPaletteQuantizer.quantize(rgb);
			}
		}
	}

	static void quantizeSingleTileOpaque(int tilesWide, int[] rgbPixels, int pixelWidth, int tileIndex, byte[] tile, boolean dither) {
		int tileX = Math.floorMod(tileIndex, tilesWide);
		int tileY = tileIndex / tilesWide;
		int tileOriginX = tileX * MAP_SIZE;
		int tileOriginY = tileY * MAP_SIZE;
		for (int localY = 0; localY < MAP_SIZE; localY++) {
			int globalY = tileOriginY + localY;
			int rowStart = globalY * pixelWidth + tileOriginX;
			int tileRowStart = localY * MAP_SIZE;
			for (int localX = 0; localX < MAP_SIZE; localX++) {
				int globalX = tileOriginX + localX;
				int rgb = compositeArgbOverBlack(rgbPixels[rowStart + localX]);
				tile[tileRowStart + localX] = dither
						? MapPaletteQuantizer.quantizeDithered(rgb, globalX, globalY)
						: MapPaletteQuantizer.quantize(rgb);
			}
		}
	}

	static int compositeArgbOverBlack(int argb) {
		int alpha = (argb >>> 24) & 0xFF;
		if (alpha <= MAP_TRANSPARENT_ALPHA_THRESHOLD) {
			return 0;
		}
		if (alpha >= 0xFF) {
			return argb & 0xFFFFFF;
		}
		int red = (((argb >>> 16) & 0xFF) * alpha + 127) / 255;
		int green = (((argb >>> 8) & 0xFF) * alpha + 127) / 255;
		int blue = ((argb & 0xFF) * alpha + 127) / 255;
		return ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
	}

	static void drawBaseBackground(Graphics2D graphics, int width, int height, boolean powered) {
		BufferedImage base = powered ? loadOnBaseImage() : loadOffBaseImage();
		int pixelWidth = width * MAP_SIZE;
		int pixelHeight = height * MAP_SIZE;
		graphics.drawImage(base, 0, 0, pixelWidth, pixelHeight, null);
	}

	static void drawHomeScreen(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, int launcherPage, MaxVisualSnapshot maxSnapshot) {
		UiRect panel = homePanelRect(layout);
		UiRect header = homeHeaderRect(layout, panel);
		drawCenteredTextFitted(graphics, "Приложения", header, new Color(248, 250, 252, 236), Font.BOLD, clampInt(layout.unit() * 2 - 1, 10, 30), clampInt(layout.unit(), 8, 18));

		UiRect gridRect = homeGridRect(layout, panel);
		int totalRows = homeTotalRows(layout);
		int visibleRows = homeRowsPerPage(layout);
		int maxScroll = Math.max(0, totalRows - visibleRows);
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.HOME_LAUNCHER,
				launcherPage,
				maxScroll
		);
		int baseRow = visualScroll.anchorIndex();
		int rowOffset = -(int) Math.round(visualScroll.fraction() * (homeAppCardHeight(layout) + homeAppGap(layout)));
		int columns = homeColumns(layout);
		List<MonitorApp> apps = MonitorAppRegistry.apps();
		int rowCount = Math.min(
				Math.max(0, totalRows - baseRow),
				visibleRows + (visualScroll.animated() && baseRow + visibleRows < totalRows ? 1 : 0)
		);
		Shape previousClip = graphics.getClip();
		graphics.clipRect(gridRect.x(), gridRect.y(), gridRect.width(), gridRect.height());
		try {
			for (int visibleRow = 0; visibleRow < rowCount; visibleRow++) {
				for (int column = 0; column < columns; column++) {
					int index = (baseRow + visibleRow) * columns + column;
					if (index < 0 || index >= apps.size()) {
						continue;
					}
					MonitorApp app = apps.get(index);
					int badgeCount = app != null && "max".equals(app.id()) && maxSnapshot != null
							? maxSnapshot.notificationCount()
							: app != null && "support".equals(app.id()) ? MonitorSupportRuntime.notificationCount(runtimeKey) : 0;
					UiRect baseRect = homeAppCardRect(layout, 0, visibleRow * columns + column);
					drawHomeAppCard(graphics, layout, offsetRect(baseRect, 0, rowOffset), app, badgeCount);
				}
			}
		} finally {
			graphics.setClip(previousClip);
		}
		if (maxScroll > 0) {
			drawScrollbar(graphics, homeScrollbarTrackRect(layout), visualScroll.displayValue(), visibleRows, totalRows, layout);
		}
	}

	static void drawAppScreen(Graphics2D graphics, UiLayout layout, MonitorApp app, ScreenRuntimeKey runtimeKey, MinecraftServer server, MediaVisualSnapshot mediaSnapshot) {
		if (app == null) {
			drawHomeScreen(graphics, layout, runtimeKey, 0, null);
			return;
		}
		if (app.role().usesMediaRenderer()) {
			drawMediaScreen(graphics, layout, runtimeKey, server, mediaSnapshot);
			return;
		}
		drawGenericAppScreen(graphics, layout, app);
	}

	static void drawGenericAppScreen(Graphics2D graphics, UiLayout layout, MonitorApp app) {
		UiRect headerRect = mediaHeaderRect(layout);
		graphics.setPaint(new GradientPaint(
				headerRect.x(),
				headerRect.y(),
				new Color(app.accentStartRgb()),
				headerRect.right(),
				headerRect.bottom(),
				new Color(app.accentEndRgb())
		));
		fillRoundedRect(graphics, headerRect, clampInt(layout.unit() * 2, 14, 24), null);
		strokeRoundedRect(graphics, headerRect, clampInt(layout.unit() * 2, 14, 24), 1.0F, new Color(255, 255, 255, 82));

		UiRect closeRect = genericCloseRect(layout);
		drawMediaCloseButton(graphics, closeRect, layout);

		UiRect titleRect = new UiRect(
				closeRect.right() + clampInt(layout.unit() / 2, 6, 12),
				headerRect.y(),
				Math.max(24, headerRect.right() - closeRect.right() - layout.unit() * 2),
				headerRect.height()
		);
		drawVerticalText(graphics, app.title(), titleRect, new Color(248, 251, 255), Font.BOLD, clampInt(layout.unit(), 11, 18));

		UiRect heroRect = genericAppHeroRect(layout);
		fillRoundedRect(graphics, heroRect, clampInt(layout.unit() * 2, 14, 24), withAlpha(app.panelRgb(), 214));
		strokeRoundedRect(graphics, heroRect, clampInt(layout.unit() * 2, 14, 24), 1.0F, new Color(255, 255, 255, 68));

		int iconSize = clampInt(Math.min(heroRect.width(), heroRect.height()) / 2, 48, 96);
		UiRect iconRect = new UiRect(
				heroRect.x() + (heroRect.width() - iconSize) / 2,
				heroRect.y() + layout.unit(),
				iconSize,
				iconSize
		);
		fillRoundedRect(graphics, iconRect, clampInt(layout.unit() * 2, 12, 22), new Color(255, 255, 255, 250));
		drawAppIcon(graphics, app, iconRect, clampInt(layout.unit() / 2, 4, 8));

		UiRect titleTextRect = new UiRect(
				heroRect.x() + layout.unit(),
				iconRect.bottom() + layout.unit() / 2,
				heroRect.width() - layout.unit() * 2,
				clampInt(layout.unit() * 2, 20, 30)
		);
		drawCenteredText(graphics, app.screenTitle(), titleTextRect, new Color(245, 247, 250), Font.BOLD, clampInt(layout.unit() + 1, 11, 17));

		UiRect hintRect = new UiRect(
				heroRect.x() + layout.unit(),
				titleTextRect.bottom(),
				heroRect.width() - layout.unit() * 2,
				clampInt(layout.unit() * 2, 18, 28)
		);
		drawCenteredText(graphics, app.screenHint(), hintRect, new Color(212, 220, 228), Font.PLAIN, clampInt(layout.unit() - 1, 9, 13));
	}

	static void drawMediaScreen(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MinecraftServer server, MediaVisualSnapshot state) {
		if (state != null && isLibraryAppMode(state.mode()) && state.galleryBrowser()) {
			drawGalleryBrowserScreen(graphics, layout, runtimeKey, server, state);
			return;
		}

		UiRect canvasRect = mediaCanvasRect(layout);
		UiRect closeRect = mediaCloseRect(layout);
		BufferedImage mediaFrame = state != null ? state.frame() : null;
		BufferedImage mediaBackgroundFrame = state != null && state.backgroundFrame() != null ? state.backgroundFrame() : mediaFrame;
		boolean hasMedia = state != null && state.hasMedia();
		boolean controlUi = state != null && (state.hasMedia() || state.playbackControlsVisible());
		ScreenViewMode actualMode = state != null ? state.mode() : ScreenViewMode.HOME;
		boolean musicPlayerLayout = usesMusicPlayerLayout(state);
		ScreenViewMode chromeMode = musicPlayerLayout ? ScreenViewMode.YOUTUBE_MUSIC : actualMode;
		boolean youtubeMode = actualMode == ScreenViewMode.YOUTUBE;
		boolean youtubeMusicMode = actualMode == ScreenViewMode.YOUTUBE_MUSIC;
		boolean youtubeFamilyMode = youtubeMode || youtubeMusicMode;
		MediaOverlayWindowSnapshot overlayWindow = state != null ? state.overlayWindow() : null;
		boolean queueOverlayActive = state != null && youtubeFamilyMode && state.youtubeQueueOpen();
		boolean galleryFileMenuOverlayActive = overlayWindow != null && overlayWindow.type() == MediaOverlayWindowType.GALLERY_FILE_MENU;
		boolean playerBackgroundOverlayActive = overlayWindow != null && overlayWindow.type() == MediaOverlayWindowType.PLAYER_BACKGROUND;
		boolean gallerySlideshowOverlayActive = overlayWindow != null && overlayWindow.type() == MediaOverlayWindowType.GALLERY_SLIDESHOW;
		boolean chromeOverlayActive = queueOverlayActive || galleryFileMenuOverlayActive || playerBackgroundOverlayActive || gallerySlideshowOverlayActive;
		boolean galleryMode = actualMode == ScreenViewMode.GALLERY;
		boolean droneMode = actualMode == ScreenViewMode.SBER_DRONES;
		boolean libraryMode = galleryMode || droneMode;
		boolean galleryBackedYoutube = state != null && state.galleryBackedYoutube();
		boolean youtubeHomePrompt = isYoutubeHomePrompt(state);
		boolean youtubeMenuSurface = isYoutubeMenuSurface(state);
		boolean showQueueButton = state != null && youtubeFamilyMode && !galleryBackedYoutube;
		boolean showPrimaryActionButton = state != null && !youtubeMusicMode && state.actionVisible();
		boolean showYoutubeMusicDownloadButton = state != null && youtubeMusicMode && state.actionVisible() && !galleryBackedYoutube;
		boolean showWallpaperActionButton = state != null && galleryMode && state.wallpaperActionVisible();
		boolean showRepeatButton = state != null && repeatButtonVisible(state);
		boolean showGallerySettingsButton = gallerySlideshowSettingsButtonVisible(state);
		boolean nonMusicChrome = !musicPlayerLayout;
		boolean showNonMusicQueueButton = nonMusicChrome && showQueueButton && !youtubeMusicMode;
		MediaButtonSegment scaleButtonSegment = MediaButtonSegment.SINGLE;
		MediaButtonSegment repeatButtonSegment = MediaButtonSegment.SINGLE;
		MediaButtonSegment primaryActionSegment = MediaButtonSegment.SINGLE;
		MediaButtonSegment wallpaperActionSegment = MediaButtonSegment.SINGLE;
		MediaButtonSegment queueButtonSegment = MediaButtonSegment.SINGLE;
		MediaButtonSegment youtubeMusicSearchSegment = MediaButtonSegment.SINGLE;
		MediaButtonSegment youtubeMusicShuffleSegment = MediaButtonSegment.SINGLE;
		MediaButtonSegment youtubeMusicRepeatSegment = MediaButtonSegment.SINGLE;
		if (nonMusicChrome) {
			int smallButtonCount = 1 + (showWallpaperActionButton ? 1 : 0) + (showPrimaryActionButton ? 1 : 0) + (showNonMusicQueueButton ? 1 : 0) + (showRepeatButton ? 1 : 0);
			int smallButtonIndex = 0;
			if (showWallpaperActionButton) {
				wallpaperActionSegment = mediaButtonSegment(smallButtonIndex++, smallButtonCount);
			}
			if (showPrimaryActionButton) {
				primaryActionSegment = mediaButtonSegment(smallButtonIndex++, smallButtonCount);
			}
			if (showNonMusicQueueButton) {
				queueButtonSegment = mediaButtonSegment(smallButtonIndex++, smallButtonCount);
			}
			if (showRepeatButton) {
				repeatButtonSegment = mediaButtonSegment(smallButtonIndex++, smallButtonCount);
			}
			scaleButtonSegment = mediaButtonSegment(smallButtonIndex, smallButtonCount);
		} else if (youtubeMusicMode && (showQueueButton || showYoutubeMusicDownloadButton || showRepeatButton)) {
			youtubeMusicSearchSegment = MediaButtonSegment.LEFT;
			youtubeMusicShuffleSegment = MediaButtonSegment.MIDDLE;
			youtubeMusicRepeatSegment = MediaButtonSegment.MIDDLE;
			queueButtonSegment = showYoutubeMusicDownloadButton ? MediaButtonSegment.MIDDLE : MediaButtonSegment.RIGHT;
			primaryActionSegment = MediaButtonSegment.RIGHT;
		} else if (musicPlayerLayout && galleryMode && showPrimaryActionButton && showRepeatButton) {
			repeatButtonSegment = MediaButtonSegment.LEFT;
			primaryActionSegment = MediaButtonSegment.RIGHT;
		}
		PlayerBackgroundMode playerBackgroundMode = resolvedPlayerBackgroundMode(state);
		boolean galleryBackgroundAvailable = state != null && state.galleryBackgroundAvailable();
		boolean galleryBackgroundSelected = state != null
				&& playerBackgroundMode == PlayerBackgroundMode.GALLERY;
		boolean artworkBackgroundVisible = state != null
				&& playerBackgroundMode == PlayerBackgroundMode.ARTWORK
				&& mediaBackgroundFrame != null;
		UiRect menuRect = mediaPlayerMenuRect(layout);
		UiRect settingsRect = showGallerySettingsButton ? mediaGallerySettingsRect(layout) : new UiRect(menuRect.x(), menuRect.y(), 0, 0);
		int rightControlEdge = showGallerySettingsButton ? Math.min(menuRect.x(), settingsRect.x()) : menuRect.x();
		UiRect titleRect = libraryMode && !musicPlayerLayout ? mediaGalleryPlayerTitleRect(layout) : mediaLinkRect(layout, controlUi);
		if (titleRect.right() > rightControlEdge - clampInt(layout.unit() / 2, 4, 8)) {
			titleRect = new UiRect(
					titleRect.x(),
					titleRect.y(),
					Math.max(48, rightControlEdge - clampInt(layout.unit() / 2, 4, 8) - titleRect.x()),
					titleRect.height()
			);
		}
		UiRect scaleRect = mediaScaleActionRect(layout, showWallpaperActionButton, showPrimaryActionButton, showNonMusicQueueButton, showRepeatButton && nonMusicChrome, nonMusicChrome);
		UiRect repeatRect = nonMusicChrome
				? mediaRepeatActionRect(layout, showWallpaperActionButton, showPrimaryActionButton, showNonMusicQueueButton, showRepeatButton, nonMusicChrome)
				: youtubeMusicMode ? mediaYoutubeMusicRepeatRect(layout, showYoutubeMusicDownloadButton) : mediaGalleryMusicRepeatRect(layout, showPrimaryActionButton);
		UiRect primaryActionRect = mediaPrimaryActionRect(layout, showWallpaperActionButton, showPrimaryActionButton, showNonMusicQueueButton, showRepeatButton && nonMusicChrome, nonMusicChrome);
		UiRect wallpaperActionRect = mediaWallpaperActionRect(layout, showWallpaperActionButton, showPrimaryActionButton, showNonMusicQueueButton, showRepeatButton && nonMusicChrome, nonMusicChrome);
		UiRect queueToggleRect = musicPlayerLayout
				? youtubeMusicMode ? mediaYoutubeMusicQueueRect(layout, showYoutubeMusicDownloadButton) : mediaQueueToggleRect(layout, chromeMode)
				: mediaQueueActionRect(layout, showWallpaperActionButton, showPrimaryActionButton, showNonMusicQueueButton, showRepeatButton && nonMusicChrome, nonMusicChrome);
		UiRect timelineRect = mediaTimelineRect(layout, state);
		boolean darkPlayerSurface = usesDarkMediaPlayerSurface(state);
		BufferedImage playerBackgroundFrame = state != null ? state.playerBackgroundFrame() : null;
		drawPlayerBackgroundSurface(
				graphics,
				layout,
				canvasRect,
				mediaBackgroundFrame,
				playerBackgroundFrame,
				state,
				playerBackgroundMode,
				galleryBackgroundSelected,
				galleryBackgroundAvailable,
				artworkBackgroundVisible,
				darkPlayerSurface,
				musicPlayerLayout,
				youtubeHomePrompt,
				state != null && state.waitingForLink(),
				youtubeMenuSurface
		);
		if (musicPlayerLayout && !youtubeMenuSurface) {
			if (mediaFrame != null && !chromeOverlayActive) {
				drawYoutubeMusicArtworkCard(graphics, layout, mediaFrame, state != null ? state.scaleMode() : MediaScaleMode.FIT);
			} else if (state != null && state.loading() && !chromeOverlayActive) {
				drawYoutubeMusicArtworkLoadingPlaceholder(graphics, layout);
			}
		} else if (mediaFrame != null) {
			drawScaledImage(graphics, mediaFrame, canvasRect, state.scaleMode());
		}

		boolean controlsActive = state != null
				&& (state.overlayMode() == MediaOverlayMode.CONTROLS
				|| state.loading()
				|| (youtubeFamilyMode && !hasMedia))
				&& !chromeOverlayActive;
		if (controlsActive) {
			if (libraryMode) {
				drawMediaBackButton(graphics, closeRect, layout, controlUi && !musicPlayerLayout ? MediaButtonSegment.LEFT : MediaButtonSegment.SINGLE);
			} else {
				drawMediaCloseButton(graphics, closeRect, layout, controlUi && !musicPlayerLayout ? MediaButtonSegment.LEFT : MediaButtonSegment.SINGLE);
			}
			drawMediaPlayerMenuButton(
					graphics,
					menuRect,
					layout,
					state != null
							&& state.overlayWindow() != null
							&& (state.overlayWindow().type() == MediaOverlayWindowType.PLAYER_BACKGROUND
							|| state.overlayWindow().type() == MediaOverlayWindowType.GALLERY_FILE_MENU),
					showGallerySettingsButton ? MediaButtonSegment.RIGHT : MediaButtonSegment.SINGLE
			);
			if (showGallerySettingsButton) {
				drawMediaSlideshowButton(graphics, settingsRect, layout, gallerySlideshowOverlayActive, MediaButtonSegment.LEFT);
			}
			if (controlUi) {
				boolean titleBarMode = libraryMode
						|| galleryBackedYoutube
						|| (youtubeMusicMode && (hasMedia
						|| state.loading()
						|| (state.mediaTitle() != null && !state.mediaTitle().isBlank())));
				if (musicPlayerLayout) {
					if (hasMedia
							|| state.loading()
							|| (state.mediaTitle() != null && !state.mediaTitle().isBlank())) {
						drawYoutubeMusicTrackInfo(graphics, layout, state);
					}
				} else if (titleBarMode && !youtubeMusicMode) {
					drawMediaTitleBar(graphics, titleRect, state != null ? state.mediaTitle() : "", layout, MediaButtonSegment.RIGHT);
					if (showPrimaryActionButton && galleryMode) {
						drawGalleryPlayerActionButton(graphics, primaryActionRect, state, layout, primaryActionSegment);
					}
					if (showWallpaperActionButton && galleryMode) {
						drawGalleryWallpaperActionButton(graphics, wallpaperActionRect, state, layout, wallpaperActionSegment);
					}
				} else {
					drawMediaSearchBar(
							graphics,
							titleRect,
							state != null ? state.linkPlaceholder() : "ВСТАВЬ URL",
							true,
							layout,
							MediaButtonSegment.RIGHT
					);
				}
				if (!musicPlayerLayout) {
					drawMediaScaleButton(graphics, scaleRect, state != null ? state.scaleMode() : MediaScaleMode.FIT, layout, scaleButtonSegment);
					if (showRepeatButton) {
						drawMediaRepeatButton(graphics, repeatRect, resolvedRepeatMode(state), layout, repeatButtonSegment);
					}
				}
				if (youtubeFamilyMode) {
					if (showPrimaryActionButton && !youtubeMusicMode) {
						drawYoutubePlayerActionButton(graphics, primaryActionRect, state, layout, primaryActionSegment);
					}
					if (showQueueButton && !youtubeMusicMode) {
						drawMediaQueueToggleButton(graphics, queueToggleRect, state.youtubeQueueOpen(), layout, queueButtonSegment);
					}
				}
				drawMediaTimeline(graphics, timelineRect, state, layout);
				drawMediaCenterControls(graphics, layout, state);
			}
			if (youtubeMusicMode && !galleryBackedYoutube && !youtubeHomePrompt) {
				drawYoutubeMusicSearchButton(graphics, mediaYoutubeMusicSearchRect(layout, showYoutubeMusicDownloadButton), layout, youtubeMusicSearchSegment);
				drawYoutubeMusicShuffleButton(graphics, mediaYoutubeMusicShuffleRect(layout, showYoutubeMusicDownloadButton), state.youtubeMusicShuffleEnabled(), layout, youtubeMusicShuffleSegment);
				drawMediaRepeatButton(graphics, repeatRect, resolvedRepeatMode(state), layout, youtubeMusicRepeatSegment);
				if (showQueueButton) {
					drawMediaQueueToggleButton(graphics, queueToggleRect, state.youtubeQueueOpen(), layout, queueButtonSegment);
				}
				if (showYoutubeMusicDownloadButton) {
					drawYoutubePlayerActionButton(graphics, mediaYoutubeMusicDownloadRect(layout), state, layout, primaryActionSegment);
				}
			} else if (musicPlayerLayout && galleryMode && (showPrimaryActionButton || showRepeatButton)) {
				if (showRepeatButton) {
					drawMediaRepeatButton(graphics, repeatRect, resolvedRepeatMode(state), layout, repeatButtonSegment);
				}
				if (showPrimaryActionButton) {
					drawGalleryPlayerActionButton(
							graphics,
							mediaGalleryMusicPrimaryActionRect(layout, showRepeatButton),
							state,
							layout,
							primaryActionSegment
					);
				}
			}
		}

		TaskProgress.Snapshot progress = state != null ? state.progress() : null;
		String status = state != null ? state.statusText() : "";
		boolean showPromptStatus = status != null
				&& !status.isBlank()
				&& !isMediaPromptStatus(status)
				&& !isMediaLoadingStatus(status)
				&& (progress == null || !progress.visible());
		if (!hasMedia && showPromptStatus) {
			UiRect statusRect = mediaStatusRect(layout);
			strokeRoundedRect(graphics, statusRect, clampInt(layout.unit() * 2, 12, 20), 1.0F, new Color(255, 255, 255, 42));
			drawCenteredText(
					graphics,
					status != null && !status.isBlank() ? status : "SEND LINK IN CHAT",
					statusRect,
					new Color(248, 251, 255),
					Font.BOLD,
					clampInt(layout.unit() - 1, 9, 14)
			);
		}

		if (progress != null && progress.visible() && !controlUi && !chromeOverlayActive) {
			UiRect progressRect = mediaProgressRect(layout);
			drawProgressBar(graphics, progressRect, progress, layout);
		}

		if (!controlUi && !chromeOverlayActive && youtubeFamilyMode && (!youtubeMusicMode || youtubeHomePrompt)) {
			drawMediaSearchBar(
					graphics,
					titleRect,
					state != null ? state.linkPlaceholder() : "ВСТАВЬ URL",
					false,
					layout
			);
		}
		if (state != null && state.overlayWindow() != null) {
			if (state.overlayWindow().type() == MediaOverlayWindowType.YOUTUBE_QUEUE) {
				drawOverlayBackdrop(graphics, mediaCanvasRect(layout));
			}
			drawMediaOverlayWindow(graphics, layout, runtimeKey, server, state.overlayWindow());
		}
	}

	static boolean usesDarkMediaPlayerSurface(MediaVisualSnapshot state) {
		if (state == null) {
			return false;
		}
		if (isYoutubeHomePrompt(state)) {
			return false;
		}
		if (isYoutubeMenuSurface(state)) {
			return false;
		}
		return switch (state.mode()) {
			case GALLERY -> !state.galleryBrowser();
			case SBER_DRONES -> !state.galleryBrowser();
			case YOUTUBE, YOUTUBE_MUSIC -> state.hasMedia() || state.loading() || state.playbackControlsVisible();
			default -> false;
		};
	}

	static boolean isYoutubeHomePrompt(MediaVisualSnapshot state) {
		if (state == null || !isYoutubeFamilyMode(state.mode())) {
			return false;
		}
		return !state.loading()
				&& !state.hasMedia()
				&& !state.playbackControlsVisible();
	}

	static boolean isYoutubeMenuSurface(MediaVisualSnapshot state) {
		return state != null
				&& isYoutubeFamilyMode(state.mode())
				&& !state.galleryBackedYoutube()
				&& !state.hasMedia()
				&& state.frame() == null;
	}

	static void drawGalleryBrowserScreen(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MinecraftServer server, MediaVisualSnapshot state) {
		UiRect closeRect = mediaGalleryBrowserCloseRect(layout);
		boolean droneMode = state != null && isSberDronesMode(state.mode());
		boolean galleryPickerMode = state != null && state.galleryPickerMode();
		boolean selectionMode = state != null && state.gallerySelectionMode() && state.mode() == ScreenViewMode.GALLERY && !galleryPickerMode;
		int selectionCount = state != null ? state.gallerySelectionCount() : 0;
		UiRect linkRect = mediaGalleryBrowserLinkRect(layout, selectionMode);
		UiRect deleteRect = mediaGalleryBrowserBulkDeleteRect(layout);
		UiRect sendRect = mediaGalleryBrowserBulkSendRect(layout);
		UiRect slideshowRect = mediaGalleryBrowserSlideshowRect(layout);
		UiRect selectAllRect = mediaGalleryBrowserSelectAllRect(layout);
		UiRect selectionRect = mediaGalleryBrowserSelectionRect(layout);
		UiRect gridRect = mediaGalleryGridRect(layout);
		UiRect scrollbarTrackRect = mediaGalleryBrowserScrollbarTrackRect(layout);
		MonitorApp app = state != null ? appForViewMode(state.mode()) : null;
		WindowedSnapshot<GalleryCardSnapshot> cardsWindow = state != null ? state.galleryCards() : WindowedSnapshot.empty();
		boolean allSelected = selectionMode && cardsWindow.totalCount() > 0 && selectionCount >= cardsWindow.totalCount();

		if (droneMode && !galleryPickerMode) {
			drawSberDronesBrowserScreen(graphics, layout, runtimeKey, state, app, closeRect, linkRect, gridRect, scrollbarTrackRect);
			return;
		}

		drawMediaCloseButton(graphics, closeRect, layout, MediaButtonSegment.LEFT);
		if (galleryPickerMode) {
			drawMediaTitleBar(graphics, linkRect, "ВЫБЕРИ ФОН", layout, MediaButtonSegment.MIDDLE);
		} else if (droneMode) {
			drawMediaTitleBar(graphics, linkRect, app != null ? app.screenTitle() : "Сбер дроны", layout, MediaButtonSegment.MIDDLE);
		} else if (selectionMode) {
			drawMediaTitleBar(graphics, linkRect, selectionCount > 0 ? "ВЫБРАНО " + selectionCount : "ВЫБЕРИ МЕДИА", layout, MediaButtonSegment.MIDDLE);
		} else {
			drawMediaSearchBar(graphics, linkRect, state != null ? state.linkPlaceholder() : "ВСТАВЬ URL", true, layout, MediaButtonSegment.MIDDLE);
		}
			if (!galleryPickerMode && !droneMode) {
				if (selectionMode) {
					drawGalleryHeaderIconButton(graphics, deleteRect, layout, PlayerUiIcon.TRASH, false, MediaButtonSegment.MIDDLE);
					drawGalleryHeaderIconButton(graphics, sendRect, layout, PlayerUiIcon.SEND_PLANE, false, MediaButtonSegment.MIDDLE);
					drawGalleryHeaderIconButton(graphics, slideshowRect, layout, PlayerUiIcon.VIDEO_AI, false, MediaButtonSegment.MIDDLE);
					drawGalleryHeaderIconButton(
							graphics,
							selectAllRect,
							layout,
							PlayerUiIcon.LIST_CHECK_3_LINE,
							allSelected,
							MediaButtonSegment.MIDDLE
					);
				}
			drawGalleryHeaderIconButton(graphics, selectionRect, layout, selectionMode ? PlayerUiIcon.CHECKBOX_FILL : PlayerUiIcon.CHECKBOX_LINE, selectionMode, MediaButtonSegment.RIGHT);
		}

		List<GalleryCardSnapshot> cards = cardsWindow.items();
		int columns = mediaGalleryColumns(layout);
		int visibleRows = mediaGalleryVisibleRows(layout);
		int totalRows = mediaGalleryTotalRows(cardsWindow.totalCount(), layout);
		int scroll = state != null ? clampInt(state.mediaListScroll(), 0, Math.max(0, totalRows - visibleRows)) : 0;
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MEDIA_LIBRARY_BROWSER,
				scroll,
				Math.max(0, totalRows - visibleRows)
		);
		if (cards.isEmpty()) {
			drawCenteredText(
					graphics,
					galleryPickerMode ? "Нет подходящих файлов" : droneMode ? "Нет подключённых дронов" : "Галерея пуста",
					gridRect,
					new Color(228, 234, 240, 214),
					Font.BOLD,
					clampInt(layout.unit(), 8, 14)
			);
		} else {
			int baseRow = visualScroll.anchorIndex();
			int rowOffset = -(int) Math.round(visualScroll.fraction() * mediaGalleryRowStep(layout));
			int rowCount = Math.min(
					Math.max(0, totalRows - baseRow),
					visibleRows + (visualScroll.animated() && baseRow + visibleRows < totalRows ? 1 : 0)
			);
			Shape previousClip = graphics.getClip();
			graphics.clipRect(gridRect.x(), gridRect.y(), gridRect.width(), gridRect.height());
			try {
				for (int visibleRow = 0; visibleRow < rowCount; visibleRow++) {
					for (int column = 0; column < columns; column++) {
						int absoluteIndex = (baseRow + visibleRow) * columns + column;
						int index = absoluteIndex - cardsWindow.windowStartIndex();
						if (index < 0 || index >= cards.size()) {
							continue;
						}
						drawGalleryCard(
								graphics,
								layout,
								offsetRect(mediaGalleryCardRect(layout, visibleRow, column), 0, rowOffset),
								cards.get(index)
						);
					}
				}
			} finally {
				graphics.setClip(previousClip);
			}
			drawGalleryScrollbar(graphics, scrollbarTrackRect, visualScroll.displayValue(), visibleRows, totalRows, layout);
		}

		TaskProgress.Snapshot progress = state != null ? state.progress() : null;
		if (progress != null && progress.visible()) {
			drawProgressBar(graphics, mediaProgressRect(layout), progress, layout);
		}
		if (state != null && state.overlayWindow() != null) {
			drawMediaOverlayWindow(graphics, layout, runtimeKey, server, state.overlayWindow());
		}
	}

	static void drawSberDronesBrowserScreen(
			Graphics2D graphics,
			UiLayout layout,
			ScreenRuntimeKey runtimeKey,
			MediaVisualSnapshot state,
			MonitorApp app,
			UiRect closeRect,
			UiRect linkRect,
			UiRect gridRect,
			UiRect scrollbarTrackRect
	) {
		drawMediaCloseButton(graphics, closeRect, layout, MediaButtonSegment.LEFT);

		WindowedSnapshot<GalleryCardSnapshot> cardsWindow = state != null ? state.galleryCards() : WindowedSnapshot.empty();
		List<GalleryCardSnapshot> cards = cardsWindow.items();
		int cameraCount = 0;
		int droneCount = 0;
		for (GalleryCardSnapshot card : cards) {
			if (card == null) {
				continue;
			}
			if ("DRONE".equals(card.sourceLabel())) {
				droneCount++;
			} else {
				cameraCount++;
			}
		}
		int chipHeight = linkRect.height();
		int[] chipCounts = new int[]{cameraCount, droneCount};
		PlayerUiIcon[] chipIcons = new PlayerUiIcon[]{PlayerUiIcon.CAMERA, PlayerUiIcon.DRONE};
		int[] chipWidths = new int[chipCounts.length];
		Font chipFont = new Font(Font.SANS_SERIF, Font.BOLD, clampInt(layout.unit() - 1, 7, 10));
		var metrics = graphics.getFontMetrics(chipFont);
		int totalChipWidth = 0;
		int chipGap = mediaHeaderControlGap(layout);
		for (int index = 0; index < chipCounts.length; index++) {
			String countText = Integer.toString(Math.max(0, chipCounts[index]));
			int iconWidth = clampInt(layout.unit() + 4, 12, 16);
			chipWidths[index] = Math.max(
					chipHeight,
					iconWidth + metrics.stringWidth(countText) + clampInt(layout.unit() * 2 + 10, 18, 30)
			);
			totalChipWidth += chipWidths[index];
			if (index > 0) {
				totalChipWidth += chipGap;
			}
		}
		int chipStartX = Math.max(linkRect.x(), linkRect.right() - totalChipWidth);
		int chipX = chipStartX;
		for (int index = 0; index < chipCounts.length; index++) {
			drawSberDronesSummaryIconChip(
					graphics,
					new UiRect(chipX, linkRect.y(), chipWidths[index], chipHeight),
					chipIcons[index],
					chipCounts[index],
					layout,
					MediaButtonSegment.SINGLE
			);
			chipX += chipWidths[index] + chipGap;
		}

		int columns = mediaGalleryColumns(layout);
		int visibleRows = mediaGalleryVisibleRows(layout);
		int totalRows = mediaGalleryTotalRows(cardsWindow.totalCount(), layout);
		int scroll = state != null ? clampInt(state.mediaListScroll(), 0, Math.max(0, totalRows - visibleRows)) : 0;
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MEDIA_LIBRARY_BROWSER,
				scroll,
				Math.max(0, totalRows - visibleRows)
		);
		if (cards.isEmpty()) {
			drawSberDronesEmptyState(graphics, layout, gridRect);
		} else {
			int baseRow = visualScroll.anchorIndex();
			int rowOffset = -(int) Math.round(visualScroll.fraction() * mediaGalleryRowStep(layout));
			int rowCount = Math.min(
					Math.max(0, totalRows - baseRow),
					visibleRows + (visualScroll.animated() && baseRow + visibleRows < totalRows ? 1 : 0)
			);
			Shape previousClip = graphics.getClip();
			graphics.clipRect(gridRect.x(), gridRect.y(), gridRect.width(), gridRect.height());
			try {
				for (int visibleRow = 0; visibleRow < rowCount; visibleRow++) {
					for (int column = 0; column < columns; column++) {
						int absoluteIndex = (baseRow + visibleRow) * columns + column;
						int index = absoluteIndex - cardsWindow.windowStartIndex();
						if (index < 0 || index >= cards.size()) {
							continue;
						}
						drawSberDronesGalleryCard(
								graphics,
								layout,
								offsetRect(mediaGalleryCardRect(layout, visibleRow, column), 0, rowOffset),
								cards.get(index)
						);
					}
				}
			} finally {
				graphics.setClip(previousClip);
			}
			drawGalleryScrollbar(graphics, scrollbarTrackRect, visualScroll.displayValue(), visibleRows, totalRows, layout);
		}

		TaskProgress.Snapshot progress = state != null ? state.progress() : null;
		if (progress != null && progress.visible()) {
			drawProgressBar(graphics, mediaProgressRect(layout), progress, layout);
		}
	}

	static void drawProgressBar(Graphics2D graphics, UiRect rect, TaskProgress.Snapshot progress, UiLayout layout) {
		float alpha = progress.alpha();
		int panelHeight = clampInt(layout.unit() * 3, 26, 42);
		UiRect panelRect = new UiRect(
				rect.x(),
				rect.y() + (rect.height() - panelHeight) / 2,
				rect.width(),
				panelHeight
		);
		int arc = clampInt(panelHeight, 16, 32);
		fillRoundedRect(graphics, panelRect, arc, withAlpha(new Color(10, 14, 18, 172), alpha));
		strokeRoundedRect(graphics, panelRect, arc, 1.0F, withAlpha(new Color(255, 255, 255, 46), alpha));
		int barHeight = clampInt(layout.unit() / 2, 3, 5);
		int horizontalPadding = clampInt(layout.unit() + 2, 8, 18);
		UiRect barRect = new UiRect(
				panelRect.x() + horizontalPadding,
				panelRect.bottom() - barHeight - clampInt(layout.unit() / 2, 4, 8),
				panelRect.width() - horizontalPadding * 2,
				barHeight
		);
		fillRoundedRect(graphics, barRect, barHeight, withAlpha(new Color(255, 255, 255, 42), alpha));
		if (progress.determinate()) {
			int fillWidth = Math.max(barHeight, Math.round(barRect.width() * progress.fraction()));
			fillRoundedRect(graphics, new UiRect(barRect.x(), barRect.y(), Math.min(barRect.width(), fillWidth), barRect.height()), barHeight, withAlpha(new Color(255, 255, 255, 232), alpha));
		} else {
			int pulseWidth = Math.max(12, barRect.width() / 4);
			long pulse = (System.currentTimeMillis() / 120L) % Math.max(1, barRect.width());
			int pulseX = barRect.x() + (int) Math.min(barRect.width() - pulseWidth, pulse);
			fillRoundedRect(graphics, new UiRect(pulseX, barRect.y(), pulseWidth, barRect.height()), barHeight, withAlpha(new Color(255, 255, 255, 232), alpha));
		}
		String label = localizedProgressStage(progress.stage());
		drawCenteredText(
				graphics,
				label,
				new UiRect(panelRect.x() + horizontalPadding, panelRect.y() + 1, panelRect.width() - horizontalPadding * 2, panelRect.height() - barHeight - clampInt(layout.unit() / 2, 4, 8)),
				withAlpha(new Color(248, 251, 255, 232), alpha),
				Font.BOLD,
				clampInt(layout.unit() - 2, 8, 12)
		);
	}

	static void drawHomeAppCard(Graphics2D graphics, UiLayout layout, UiRect cardRect, MonitorApp app, int badgeCount) {
		UiRect iconRect = homeAppIconRect(cardRect, layout);
		drawHomeAppIcon(graphics, layout, app, iconRect);
		if (badgeCount > 0) {
			drawNotificationBadge(graphics, homeAppNotificationBadgeRect(iconRect, layout), badgeCount, layout);
		}

		UiRect labelRect = homeAppLabelRect(layout, cardRect);
		// Keep labels compact on larger displays, but do not let the 1x1 screen
		// fall below a legible seven-pixel font.
		int textSize = clampInt(layout.unit(), 7, 13);
		drawCenteredTextEllipsized(graphics, app.title(), labelRect, new Color(248, 251, 255, 238), Font.BOLD, textSize);
	}

	/**
	 * A neutral launcher container keeps every app icon equally sized without
	 * introducing coloured outlines, shadows, or per-app card styling.
	 */
	static void drawHomeAppIcon(Graphics2D graphics, UiLayout layout, MonitorApp app, UiRect rect) {
		if (graphics == null || layout == null || app == null || rect == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		int size = Math.min(rect.width(), rect.height());
		UiRect circle = new UiRect(
			rect.x() + (rect.width() - size) / 2,
			rect.y() + (rect.height() - size) / 2,
			size,
			size
		);
		graphics.setColor(new Color(250, 250, 250));
		graphics.fillOval(circle.x(), circle.y(), circle.width(), circle.height());

		Shape previousClip = graphics.getClip();
		graphics.clip(new Ellipse2D.Float(circle.x(), circle.y(), circle.width(), circle.height()));
		try {
			drawAppIcon(graphics, app, circle, clampInt(Math.round(size * 0.18F), 4, 18));
		} finally {
			graphics.setClip(previousClip);
		}
	}

	static void drawNotificationBadge(Graphics2D graphics, UiRect rect, int count, UiLayout layout) {
		if (graphics == null || rect == null || count <= 0 || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		fillRoundedRect(graphics, rect, rect.height(), new Color(238, 64, 82, 238));
		strokeRoundedRect(graphics, rect, rect.height(), Math.max(1.0F, layout.unit() / 12.0F), new Color(255, 255, 255, 210));
		drawCenteredTextFitted(graphics, count > 99 ? "99+" : Integer.toString(count), rect.inset(Math.max(1, layout.unit() / 6)), new Color(255, 255, 255, 248), Font.BOLD, clampInt(layout.unit() - 2, 7, 12), 5);
	}

	static void drawFloatingButton(Graphics2D graphics, UiRect rect, String label, Color fill, Color text, UiLayout layout) {
		fillRoundedRect(graphics, rect, clampInt(layout.unit() * 2, 10, 18), fill);
		strokeRoundedRect(graphics, rect, clampInt(layout.unit() * 2, 10, 18), 1.0F, new Color(255, 255, 255, 44));
		drawCenteredText(graphics, label, rect, text, Font.BOLD, clampInt(layout.unit() - 1, 8, 13));
	}

	static void drawMediaCloseButton(Graphics2D graphics, UiRect rect, UiLayout layout) {
		drawMediaCloseButton(graphics, rect, layout, MediaButtonSegment.SINGLE);
	}

	static void drawMediaCloseButton(Graphics2D graphics, UiRect rect, UiLayout layout, MediaButtonSegment segment) {
		Color color = drawMediaHeaderControlBase(graphics, rect, segment);
		drawCloseGlyph(graphics, mediaChromeIconRect(rect, layout), color);
	}

	static void drawMediaBackButton(Graphics2D graphics, UiRect rect, UiLayout layout) {
		drawMediaBackButton(graphics, rect, layout, MediaButtonSegment.SINGLE);
	}

	static void drawMediaBackButton(Graphics2D graphics, UiRect rect, UiLayout layout, MediaButtonSegment segment) {
		Color color = drawMediaHeaderControlBase(graphics, rect, segment);
		drawBackArrow(graphics, mediaChromeIconRect(rect, layout), color);
	}

	static void drawOverlayCloseButton(Graphics2D graphics, UiRect rect, UiLayout layout) {
		Color color = drawMediaHeaderControlBase(graphics, rect, MediaButtonSegment.SINGLE);
		drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), PlayerUiIcon.CLOSE, color);
	}

	static UiRect overlayPanelCloseRect(UiRect panel, UiLayout layout) {
		if (panel == null || layout == null) {
			return new UiRect(0, 0, 0, 0);
		}
		int size = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 2 + 2, 12, 18)
				: clampInt(layout.unit() * 2 + 4, 24, 36);
		int inset = ultraCompactScreenLayout(layout)
				? Math.max(2, layout.unit() / 3)
				: layout.unit();
		return new UiRect(panel.right() - size - inset, panel.y() + inset, size, size);
	}

	static UiRect overlayPanelTitleRect(UiRect panel, UiRect closeRect, UiLayout layout) {
		if (panel == null || closeRect == null || layout == null) {
			return new UiRect(0, 0, 0, 0);
		}
		int inset = ultraCompactScreenLayout(layout)
				? Math.max(2, layout.unit() / 3)
				: layout.unit();
		return new UiRect(
				panel.x() + inset,
				closeRect.y(),
				Math.max(1, closeRect.x() - panel.x() - inset * 2),
				closeRect.height()
		);
	}

	static UiRect overlayPanelContentRect(UiRect panel, UiRect closeRect, UiLayout layout) {
		if (panel == null || closeRect == null || layout == null) {
			return new UiRect(0, 0, 0, 0);
		}
		int inset = ultraCompactScreenLayout(layout)
				? Math.max(2, layout.unit() / 3)
				: layout.unit();
		int gap = ultraCompactScreenLayout(layout)
				? Math.max(1, layout.unit() / 4)
				: Math.max(4, layout.unit() / 2);
		int y = closeRect.bottom() + gap;
		return new UiRect(panel.x() + inset, y, panel.width() - inset * 2, Math.max(18, panel.bottom() - y - inset));
	}

	static int clippedListCapacity(int viewportExtent, int itemExtent, int gap) {
		if (viewportExtent <= 0) {
			return 1;
		}
		int safeGap = Math.max(0, gap);
		return Math.max(1, (int) Math.ceil((viewportExtent + safeGap) / (double) Math.max(1, itemExtent + safeGap)));
	}

	static int clippedListCapacity(UiRect viewport, int itemExtent, int gap, boolean horizontal) {
		if (viewport == null) {
			return 1;
		}
		return clippedListCapacity(horizontal ? viewport.width() : viewport.height(), itemExtent, gap);
	}

	static void drawMediaPlayerMenuButton(Graphics2D graphics, UiRect rect, UiLayout layout, boolean active) {
		drawMediaPlayerMenuButton(graphics, rect, layout, active, MediaButtonSegment.SINGLE);
	}

	static void drawMediaPlayerMenuButton(Graphics2D graphics, UiRect rect, UiLayout layout, boolean active, MediaButtonSegment segment) {
		float strokeWidth = mediaChromeStrokeWidth(rect);
		Color iconColor = drawSmallMediaButtonBase(graphics, rect, segment, active, strokeWidth);
		drawMenuGlyph(graphics, mediaChromeIconRect(rect, layout), iconColor);
	}

	static void drawMediaSettingsButton(Graphics2D graphics, UiRect rect, UiLayout layout, boolean active) {
		float strokeWidth = mediaChromeStrokeWidth(rect);
		Color iconColor = drawSmallMediaButtonBase(graphics, rect, MediaButtonSegment.SINGLE, active, strokeWidth);
		drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), PlayerUiIcon.SETTINGS_3, iconColor);
	}

	static void drawMediaSlideshowButton(Graphics2D graphics, UiRect rect, UiLayout layout, boolean active, MediaButtonSegment segment) {
		float strokeWidth = mediaChromeStrokeWidth(rect);
		Color iconColor = drawSmallMediaButtonBase(graphics, rect, segment, active, strokeWidth);
		drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), PlayerUiIcon.VIDEO_AI, iconColor);
	}

	static void drawMediaScaleButton(Graphics2D graphics, UiRect rect, MediaScaleMode scaleMode, UiLayout layout, MediaButtonSegment segment) {
		float strokeWidth = mediaChromeStrokeWidth(rect);
		Color iconColor = drawSmallMediaButtonBase(graphics, rect, segment, false, strokeWidth);
		UiRect iconRect = mediaChromeIconRect(rect, layout);
		switch (scaleMode != null ? scaleMode : MediaScaleMode.FIT) {
			case FILL -> drawMediaFillGlyph(graphics, iconRect, iconColor, strokeWidth);
			case STRETCH -> drawMediaStretchGlyph(graphics, iconRect, iconColor, strokeWidth);
			case FIT -> drawMediaFitGlyph(graphics, iconRect, iconColor, strokeWidth);
		}
	}

	static void drawMediaPlayPauseButton(Graphics2D graphics, UiRect rect, boolean paused, UiLayout layout) {
		drawRoundMediaButtonBase(graphics, rect);
		UiRect iconRect = rect.inset(Math.max(2, layout.unit() / 4));
		if (paused) {
			drawPlayGlyph(graphics, iconRect, new Color(248, 251, 255));
		} else {
			drawPauseGlyph(graphics, iconRect, new Color(248, 251, 255));
		}
	}

	static void drawRoundMediaButtonBase(Graphics2D graphics, UiRect rect) {
		drawRoundMediaButtonBase(graphics, rect, null);
	}

	static void drawRoundMediaButtonBase(Graphics2D graphics, UiRect rect, Color fill) {
		Shape shape = mediaSingleButtonShape(rect);
		if (fill != null && fill.getAlpha() > 0) {
			fillShape(graphics, shape, fill);
		}
		strokeShape(graphics, shape, 1.0F, new Color(255, 255, 255, 44));
	}

	static Color drawSmallMediaButtonBase(Graphics2D graphics, UiRect rect, MediaButtonSegment segment, boolean active, float strokeWidth) {
		return drawSmallMediaButtonBase(graphics, rect, segment, active, strokeWidth, null);
	}

	static Color drawSmallMediaButtonBase(Graphics2D graphics, UiRect rect, MediaButtonSegment segment, boolean active, float strokeWidth, Color idleFill) {
		Shape shape = mediaButtonShape(rect, segment);
		Color outline = active ? new Color(255, 255, 255, 76) : new Color(255, 255, 255, 76);
		Color fill = active ? new Color(248, 246, 246, 242) : idleFill;
		if (fill != null) {
			fillShape(graphics, shape, fill);
		}
		strokeShape(graphics, shape, Math.max(0.75F, strokeWidth * 0.5F), outline);
		return active ? new Color(24, 22, 24, 238) : new Color(244, 232, 236, 188);
	}

	static Color drawMediaHeaderControlBase(Graphics2D graphics, UiRect rect, MediaButtonSegment segment) {
		float strokeWidth = mediaChromeStrokeWidth(rect);
		Shape shape = mediaButtonShape(rect, segment);
		strokeShape(graphics, shape, Math.max(0.75F, strokeWidth * 0.5F), new Color(255, 255, 255, 76));
		return new Color(248, 251, 255, 226);
	}

	static UiRect mediaChromeIconRect(UiRect rect, UiLayout layout) {
		boolean ultra = ultraCompactScreenLayout(layout);
		int inset = ultra ? clampInt(layout.unit() / 3, 2, 4) : clampInt(layout.unit() / 2, 4, 8);
		int iconSize = ultra ? clampInt(layout.unit() + 2, 7, 10) : clampInt(layout.unit() + 3, 11, 15);
		iconSize = Math.min(iconSize, Math.max(8, Math.min(rect.width() - inset * 2, rect.height() - inset * 2)));
		int offsetX = Math.max(0, iconSize / 14);
		return new UiRect(
				rect.x() + (rect.width() - iconSize) / 2 + offsetX,
				rect.y() + (rect.height() - iconSize) / 2,
				iconSize,
				iconSize
		);
	}

	static float mediaChromeStrokeWidth(UiRect rect) {
		int minSide = Math.min(rect.width(), rect.height());
		if (minSide <= 16) {
			return clampFloat(minSide / 12.0F, 0.85F, 1.35F);
		}
		return clampFloat(minSide / 12.0F, 1.5F, 2.2F);
	}

	static MediaButtonSegment mediaButtonSegment(int index, int total) {
		if (total <= 1) {
			return MediaButtonSegment.SINGLE;
		}
		if (index <= 0) {
			return MediaButtonSegment.LEFT;
		}
		if (index >= total - 1) {
			return MediaButtonSegment.RIGHT;
		}
		return MediaButtonSegment.MIDDLE;
	}

	static Shape mediaButtonShape(UiRect rect, MediaButtonSegment segment) {
		int outer = Math.max(2, Math.min(rect.width(), rect.height()) / 2);
		int inner = clampInt(Math.min(rect.width(), rect.height()) / 6, 3, 8);
		return switch (segment != null ? segment : MediaButtonSegment.SINGLE) {
			case LEFT -> roundedRectShape(rect, outer, inner, inner, outer);
			case MIDDLE -> roundedRectShape(rect, inner, inner, inner, inner);
			case RIGHT -> roundedRectShape(rect, inner, outer, outer, inner);
			case SINGLE -> mediaSingleButtonShape(rect);
		};
	}

	static Shape mediaSingleButtonShape(UiRect rect) {
		if (rect != null && Math.abs(rect.width() - rect.height()) <= 1) {
			return new Ellipse2D.Float(rect.x(), rect.y(), rect.width(), rect.height());
		}
		int outer = rect != null ? Math.max(2, Math.min(rect.width(), rect.height()) / 2) : 2;
		return roundedRectShape(rect, outer, outer, outer, outer);
	}

	static void drawMediaSearchBar(Graphics2D graphics, UiRect rect, String placeholder, boolean compact, UiLayout layout) {
		drawMediaSearchBar(graphics, rect, placeholder, compact, layout, MediaButtonSegment.SINGLE);
	}

	static void drawMediaSearchBar(Graphics2D graphics, UiRect rect, String placeholder, boolean compact, UiLayout layout, MediaButtonSegment segment) {
		drawMediaHeaderControlBase(graphics, rect, segment);

		boolean ultra = ultraCompactScreenLayout(layout);
		int iconSize = ultra ? clampInt(layout.unit() + 2, 7, 9) : clampInt(layout.unit() + 2, 10, 16);
		int iconInset = ultra ? clampInt(layout.unit() / 3, 2, 4) : clampInt(layout.unit() / 2, 5, 10);
		UiRect iconRect = new UiRect(
				rect.x() + iconInset,
				rect.y() + (rect.height() - iconSize) / 2,
				iconSize,
				iconSize
		);
		drawSearchGlyph(graphics, iconRect, new Color(248, 251, 255, compact ? 214 : 236));

		int textGap = ultra ? Math.max(2, layout.unit() / 4) : clampInt(layout.unit() / 2, 4, 8);
		int rightReserve = ultra ? clampInt(layout.unit(), 4, 8) : clampInt(layout.unit() * 4, 20, 52);
		UiRect textRect = new UiRect(
				iconRect.right() + textGap,
				rect.y(),
				Math.max(4, rect.right() - iconRect.right() - textGap - rightReserve),
				rect.height()
		);
		int fontSize = ultra
				? clampInt(layout.unit() + (compact ? 0 : 1), 6, 8)
				: clampInt(layout.unit() - (compact ? 1 : 0), 9, compact ? 14 : 18);
		drawVerticalText(graphics, placeholder, textRect, new Color(248, 251, 255, compact ? 214 : 236), Font.BOLD, fontSize);
	}

	static void drawMediaActionButton(Graphics2D graphics, UiRect rect, boolean deleteMode, UiLayout layout) {
		drawMediaIconActionButton(
				graphics,
				rect,
				deleteMode ? new Color(66, 18, 24, 222) : new Color(20, 58, 94, 222),
				layout,
				deleteMode ? MediaActionGlyph.TRASH : MediaActionGlyph.DOWNLOAD,
				MediaActionVisualState.IDLE,
				MediaButtonSegment.SINGLE
		);
	}

	static void drawMediaTitleBar(Graphics2D graphics, UiRect rect, String title, UiLayout layout) {
		drawMediaTitleBar(graphics, rect, title, layout, MediaButtonSegment.SINGLE);
	}

	static void drawMediaTitleBar(Graphics2D graphics, UiRect rect, String title, UiLayout layout, MediaButtonSegment segment) {
		boolean ultra = ultraCompactScreenLayout(layout);
		drawVerticalText(
				graphics,
				(title == null || title.isBlank()) ? "ГАЛЕРЕЯ" : title,
				new UiRect(
						rect.x() + (ultra ? Math.max(1, layout.unit() / 4) : clampInt(layout.unit() / 3, 3, 6)),
						rect.y(),
						rect.width() - (ultra ? Math.max(2, layout.unit() / 2) : clampInt(layout.unit() / 2, 4, 10)),
						rect.height()
				),
				new Color(248, 251, 255, 236),
				Font.BOLD,
				ultra ? clampInt(layout.unit() + 1, 6, 9) : clampInt(layout.unit() - (compactScreenLayout(layout) ? 1 : 0), 8, 16)
		);
	}

	static void drawGalleryPlayerActionButton(Graphics2D graphics, UiRect rect, MediaVisualSnapshot state, UiLayout layout, MediaButtonSegment segment) {
		if (state == null) {
			return;
		}
		drawMediaIconActionButton(
				graphics,
				rect,
				state.actionGlyph() == MediaActionGlyph.TRASH ? new Color(70, 20, 28, 214) : new Color(18, 70, 42, 214),
				layout,
				state.actionGlyph(),
				state.actionState(),
				segment
		);
	}

	static void drawGalleryWallpaperActionButton(Graphics2D graphics, UiRect rect, MediaVisualSnapshot state, UiLayout layout, MediaButtonSegment segment) {
		if (state == null) {
			return;
		}
		drawMediaIconActionButton(
				graphics,
				rect,
				new Color(74, 54, 18, 214),
				layout,
				state.wallpaperActionGlyph(),
				state.wallpaperActionState(),
				segment
		);
	}

	static void drawYoutubePlayerActionButton(Graphics2D graphics, UiRect rect, MediaVisualSnapshot state, UiLayout layout, MediaButtonSegment segment) {
		if (state == null) {
			return;
		}
		drawMediaIconActionButton(
				graphics,
				rect,
				new Color(20, 58, 94, 222),
				layout,
				state.actionGlyph(),
				state.actionState(),
				segment
		);
	}

	static void drawMediaIconActionButton(Graphics2D graphics, UiRect rect, Color fill, UiLayout layout, MediaActionGlyph glyph, MediaActionVisualState visualState, MediaButtonSegment segment) {
		float strokeWidth = mediaChromeStrokeWidth(rect);
		Color outlineColor = switch (visualState) {
			case COMPLETE -> new Color(248, 251, 255, 176);
			case DOWNLOADING -> new Color(248, 251, 255, 160);
			case IDLE -> new Color(248, 251, 255, 124);
		};
		Color iconColor = switch (visualState) {
			case COMPLETE -> new Color(248, 251, 255, 246);
			case DOWNLOADING -> new Color(248, 251, 255, 236);
			case IDLE -> new Color(248, 251, 255, 214);
		};
		strokeShape(graphics, mediaButtonShape(rect, segment), Math.max(0.9F, strokeWidth * 0.55F), outlineColor);
		UiRect iconRect = mediaChromeIconRect(rect, layout);
		if (visualState == MediaActionVisualState.DOWNLOADING) {
			drawLoadingSpinner(graphics, iconRect, iconColor, Math.max(1.6F, strokeWidth));
			return;
		}
		if (visualState == MediaActionVisualState.COMPLETE) {
			drawPlayerUiIcon(graphics, iconRect, PlayerUiIcon.CHECK, iconColor);
			return;
		}
		switch (glyph) {
			case TRASH -> drawTrashGlyph(graphics, iconRect, iconColor, strokeWidth);
			case DOWNLOAD -> drawDownloadGlyph(graphics, iconRect, iconColor, strokeWidth);
			case CHECK -> drawPlayerUiIcon(graphics, iconRect, PlayerUiIcon.CHECK, iconColor);
			case WALLPAPER -> drawWallpaperGlyph(graphics, iconRect, iconColor, strokeWidth);
		}
	}

	static void drawGamepadGlyph(Graphics2D graphics, UiRect rect, Color color, float strokeWidth) {
		if (graphics == null || rect == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		Graphics2D g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(color);
			g.setStroke(new BasicStroke(Math.max(1.3F, strokeWidth), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			int w = rect.width();
			int h = rect.height();
			int bodyX = rect.x() + Math.round(w * 0.12F);
			int bodyY = rect.y() + Math.round(h * 0.30F);
			int bodyW = Math.round(w * 0.76F);
			int bodyH = Math.round(h * 0.42F);
			int arc = Math.max(4, Math.min(bodyW, bodyH) / 2);
			g.drawRoundRect(bodyX, bodyY, bodyW, bodyH, arc, arc);
			int cx = bodyX + Math.round(bodyW * 0.28F);
			int cy = bodyY + bodyH / 2;
			int arm = Math.max(2, Math.round(Math.min(w, h) * 0.10F));
			g.drawLine(cx - arm, cy, cx + arm, cy);
			g.drawLine(cx, cy - arm, cx, cy + arm);
			int dotR = Math.max(2, Math.round(Math.min(w, h) * 0.08F));
			int dot1X = bodyX + Math.round(bodyW * 0.66F);
			int dot2X = bodyX + Math.round(bodyW * 0.80F);
			g.fillOval(dot1X - dotR / 2, cy - dotR / 2, dotR, dotR);
			g.fillOval(dot2X - dotR / 2, cy - dotR / 2, dotR, dotR);
		} finally {
			g.dispose();
		}
	}

	static void drawMediaTimeline(Graphics2D graphics, UiRect rect, MediaVisualSnapshot state, UiLayout layout) {
		if (state == null || !state.timelineVisible()) {
			return;
		}
		ScreenViewMode chromeMode = mediaChromeMode(state);
		boolean youtubeMusicMode = isYoutubeMusicMode(chromeMode);
		UiRect trackRect = mediaTimelineTrackRect(layout, state);
		if (trackRect.width() <= 0 || trackRect.height() <= 0) {
			return;
		}
		UiRect counterRect = mediaTimelineCounterRect(layout, state);
		String timelineLabel = resolvedTimelineLabel(state, layout);
		Color trackFill = youtubeMusicMode ? new Color(82, 64, 70, 212) : new Color(255, 255, 255, 40);
		Color bufferedFill = youtubeMusicMode ? new Color(158, 138, 146, 164) : new Color(255, 255, 255, 90);
		Color playedFill = new Color(248, 251, 255, 242);
		int trackArc = Math.min(trackRect.height(), trackRect.width());
		fillRoundedRect(graphics, trackRect, trackArc, trackFill);
		float bufferedStart = clampFloat(state.bufferedStartFraction(), 0.0F, 1.0F);
		float bufferedEnd = clampFloat(Math.max(state.bufferedEndFraction(), state.timelineFraction()), 0.0F, 1.0F);
		if (bufferedEnd > bufferedStart) {
			int bufferedX = trackRect.x() + Math.round(trackRect.width() * bufferedStart);
			int bufferedWidth = Math.max(trackRect.height(), Math.round(trackRect.width() * (bufferedEnd - bufferedStart)));
			fillRoundedRect(
					graphics,
					new UiRect(bufferedX, trackRect.y(), Math.min(trackRect.right() - bufferedX, bufferedWidth), trackRect.height()),
					trackArc,
					bufferedFill
			);
		}
		float fraction = state.timelineFraction();
		int progressWidth = Math.max(trackRect.height(), Math.round(trackRect.width() * fraction));
		fillRoundedRect(graphics, new UiRect(trackRect.x(), trackRect.y(), Math.min(trackRect.width(), progressWidth), trackRect.height()), trackArc, playedFill);
		if (state.timelineSeekable()) {
			int markerWidth = 2;
			int markerHeight = clampInt(trackRect.height() + Math.max(5, layout.unit()), 7, 16);
			int markerCenterX = trackRect.x() + Math.round(trackRect.width() * fraction);
			int markerX = clampInt(markerCenterX - markerWidth / 2, trackRect.x(), Math.max(trackRect.x(), trackRect.right() - markerWidth));
			int markerY = trackRect.y() + (trackRect.height() - markerHeight) / 2;
			int markerArc = markerWidth;
			fillRoundedRect(
					graphics,
					new UiRect(markerX, markerY, markerWidth, markerHeight),
					markerArc,
					new Color(248, 251, 255, 248)
			);
		}
		if (youtubeMusicMode) {
			int timeFont = ultraCompactScreenLayout(layout)
					? clampInt(layout.unit(), 7, 10)
					: compactScreenLayout(layout)
					? clampInt(layout.unit() + 1, 9, 12)
					: clampInt(layout.unit() + 1, 11, 15);
			drawVerticalText(
					graphics,
					timelineLeadingLabel(state),
					mediaYoutubeMusicCurrentTimeRect(layout),
					new Color(244, 232, 236, 228),
					Font.PLAIN,
					timeFont
			);
			drawRightAlignedText(
					graphics,
					timelineTrailingLabel(state),
					mediaYoutubeMusicTotalTimeRect(layout),
					new Color(244, 232, 236, 228),
					Font.PLAIN,
					timeFont
			);
		} else if (!timelineLabel.isBlank() && counterRect.width() > 0) {
			drawCenteredTextFitted(
					graphics,
					timelineLabel,
					counterRect,
					new Color(248, 251, 255, 214),
					Font.BOLD,
					clampInt(layout.unit() - 1, 8, 14),
					clampInt(layout.unit() - 4, 6, 10)
			);
		}
	}

	static void drawGalleryCard(Graphics2D graphics, UiLayout layout, UiRect rect, GalleryCardSnapshot card) {
		if (graphics == null || layout == null || rect == null || card == null) {
			return;
		}
		Color fill = card.selectedForBulk()
				? new Color(248, 251, 255, 50)
				: card.current() ? new Color(86, 188, 255, 82) : new Color(255, 255, 255, 14);
		Color stroke = card.selectedForBulk()
				? new Color(248, 251, 255, 176)
				: card.current() ? new Color(140, 220, 255, 116) : new Color(255, 255, 255, 28);
		fillRoundedRect(graphics, rect, clampInt(layout.unit() * 2, 10, 18), fill);
		strokeRoundedRect(graphics, rect, clampInt(layout.unit() * 2, 10, 18), card.selectedForBulk() ? 1.5F : 1.0F, stroke);

		UiRect previewRect = card.metadataVisible() ? mediaGalleryCardPreviewRectWithMetadata(rect, layout) : mediaGalleryCardPreviewRect(rect, layout);
		if (card.preview() != null) {
			drawScaledImage(graphics, card.preview(), previewRect, MediaScaleMode.FILL);
		} else {
			fillRoundedRect(graphics, previewRect, clampInt(layout.unit() * 2, 8, 14), new Color(255, 255, 255, 10));
		}
		drawGalleryMediaTypeBadge(graphics, previewRect, layout, card);
		if (card.bulkSelectionMode()) {
			drawGallerySelectionBadge(graphics, previewRect, layout, card.selectedForBulk());
		}

		if (galleryCardPlayBadgeVisible(card)) {
			UiRect playBadge = mediaGalleryCardPlayBadgeRect(previewRect, layout);
			drawRoundMediaButtonBase(graphics, playBadge, new Color(12, 16, 20, 196));
			drawPlayGlyph(graphics, playBadge.inset(Math.max(2, layout.unit() / 5)), new Color(248, 251, 255));
		}

		if (card.metadataVisible()) {
			UiRect metadataRect = mediaGalleryCardMetadataRect(rect, layout);
			int gap = Math.max(2, layout.unit() / 4);
			int titleHeight = Math.max(12, metadataRect.height() / 2 - gap / 2);
			UiRect titleRect = new UiRect(metadataRect.x(), metadataRect.y(), metadataRect.width(), titleHeight);
			UiRect subtitleRect = new UiRect(metadataRect.x(), titleRect.bottom() + gap, metadataRect.width(), Math.max(10, metadataRect.bottom() - titleRect.bottom() - gap));
			drawWrappedText(graphics, card.title(), titleRect, new Color(245, 248, 252, 236), Font.BOLD, clampInt(layout.unit() - 1, 8, 13), 1);
			drawWrappedText(graphics, card.subtitle(), subtitleRect, new Color(185, 196, 208, 214), Font.PLAIN, clampInt(layout.unit() - 2, 7, 11), 2);
		}
	}

	static void drawGalleryMediaTypeBadge(Graphics2D graphics, UiRect previewRect, UiLayout layout, GalleryCardSnapshot card) {
		if (graphics == null || previewRect == null || layout == null || card == null) {
			return;
		}
		PlayerUiIcon icon = galleryMediaTypeIcon(card);
		if (icon == null) {
			return;
		}
		int size = clampInt(layout.unit() * 2 + 2, 18, 28);
		int margin = clampInt(layout.unit() / 2, 4, 8);
		UiRect badgeRect = new UiRect(previewRect.right() - margin - size, previewRect.y() + margin, size, size);
		fillRoundedRect(graphics, badgeRect, size, new Color(8, 12, 16, 174));
		strokeRoundedRect(graphics, badgeRect, size, 1.0F, new Color(255, 255, 255, 42));
		int inset = clampInt(size / 4, 4, 7);
		drawPlayerUiIcon(graphics, badgeRect.inset(inset), icon, new Color(248, 251, 255, 226));
	}

	static PlayerUiIcon galleryMediaTypeIcon(GalleryCardSnapshot card) {
		if (card == null) {
			return null;
		}
		GalleryItemKind kind = card.kind() != null ? card.kind() : GalleryItemKind.MEDIA;
		return switch (kind) {
			case AUDIO -> PlayerUiIcon.MEDIA_AUDIO;
			case VIDEO, YOUTUBE -> PlayerUiIcon.MEDIA_VIDEO;
			case LIVE_CAMERA -> PlayerUiIcon.VIDEO_CAMERA;
			case MEDIA -> card.animatedMedia() ? PlayerUiIcon.MEDIA_GIF : PlayerUiIcon.MEDIA_IMAGE;
		};
	}

	static boolean galleryCardPlayBadgeVisible(GalleryCardSnapshot card) {
		return card != null && card.kind() == GalleryItemKind.LIVE_CAMERA;
	}

	static void drawGalleryHeaderIconButton(
			Graphics2D graphics,
			UiRect rect,
			UiLayout layout,
			PlayerUiIcon icon,
			boolean active,
			MediaButtonSegment segment
	) {
		float strokeWidth = mediaChromeStrokeWidth(rect);
		Color iconColor = drawSmallMediaButtonBase(graphics, rect, segment, active, strokeWidth, active ? null : new Color(255, 255, 255, 8));
		drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), icon, iconColor);
	}

	static void drawGallerySelectionBadge(Graphics2D graphics, UiRect previewRect, UiLayout layout, boolean selected) {
		int size = clampInt(layout.unit() * 2 + 2, 18, 28);
		int margin = clampInt(layout.unit() / 2, 4, 8);
		UiRect badgeRect = new UiRect(previewRect.x() + margin, previewRect.y() + margin, size, size);
		fillRoundedRect(graphics, badgeRect, size, selected ? new Color(248, 251, 255, 232) : new Color(8, 12, 16, 174));
		strokeRoundedRect(graphics, badgeRect, size, 1.0F, selected ? new Color(255, 255, 255, 132) : new Color(255, 255, 255, 70));
		int inset = clampInt(size / 4, 4, 7);
		drawPlayerUiIcon(
				graphics,
				badgeRect.inset(inset),
				selected ? PlayerUiIcon.CHECKBOX_FILL : PlayerUiIcon.CHECKBOX_LINE,
				selected ? new Color(18, 22, 28, 238) : new Color(248, 251, 255, 218)
		);
	}

	static void drawSberDronesGalleryCard(Graphics2D graphics, UiLayout layout, UiRect rect, GalleryCardSnapshot card) {
		if (graphics == null || layout == null || rect == null || card == null) {
			return;
		}
		int arc = clampInt(layout.unit() * 2, 10, 18);
		Color fill = card.current() ? new Color(18, 22, 28, 236) : new Color(10, 14, 18, 228);
		Color stroke = card.current() ? new Color(255, 255, 255, 92) : new Color(255, 255, 255, 34);
		fillRoundedRect(graphics, rect, arc, fill);
		strokeRoundedRect(graphics, rect, arc, 1.15F, stroke);

		UiRect previewRect = sberDronesGalleryCardPreviewRect(rect, layout);
		if (card.preview() != null) {
			drawScaledImage(graphics, card.preview(), previewRect, MediaScaleMode.FILL);
		} else {
			fillRoundedRect(graphics, previewRect, clampInt(layout.unit() * 2, 8, 14), new Color(18, 28, 36, 220));
		}

		graphics.setPaint(new GradientPaint(
				previewRect.x(),
				previewRect.bottom() - Math.max(8, previewRect.height() / 2),
				new Color(4, 8, 12, 0),
				previewRect.x(),
				previewRect.bottom(),
				new Color(4, 8, 12, 220)
		));
		graphics.fillRect(previewRect.x(), previewRect.y(), previewRect.width(), previewRect.height());

		if (card.disconnectVisible()) {
			UiRect unlinkRect = mediaGalleryCardDisconnectRect(rect, layout);
			drawSberOutlinedIconButton(graphics, unlinkRect, layout, PlayerUiIcon.UNLINK, MediaButtonSegment.SINGLE, new Color(248, 251, 255, 206));
		}

		UiRect metadataRect = sberDronesGalleryCardMetadataRect(rect, layout);
		int gap = Math.max(2, layout.unit() / 5);
		UiRect metadataContentRect = metadataRect;
		int lineHeight = Math.max(9, (metadataContentRect.height() - gap) / 2);
		UiRect locationRect = new UiRect(metadataContentRect.x(), metadataContentRect.y(), lineHeight, lineHeight);
		UiRect subtitleRect = new UiRect(locationRect.right() + Math.max(2, layout.unit() / 5), metadataContentRect.y(), Math.max(12, metadataContentRect.right() - locationRect.right() - Math.max(2, layout.unit() / 5)), lineHeight);
		UiRect tertiaryRect = new UiRect(metadataContentRect.x(), subtitleRect.bottom() + gap, metadataContentRect.width(), Math.max(8, metadataContentRect.bottom() - subtitleRect.bottom() - gap));
		graphics.setColor(new Color(255, 255, 255, 18));
		graphics.fillRect(metadataRect.x(), metadataRect.y(), metadataRect.width(), 1);
		drawSberLocationGlyph(graphics, locationRect, new Color(248, 251, 255, 188));
		drawWrappedText(graphics, card.subtitle(), subtitleRect, new Color(244, 248, 252, 228), Font.PLAIN, clampInt(layout.unit() - 2, 7, 10), 1);
		drawWrappedText(graphics, card.tertiary(), tertiaryRect, new Color(176, 192, 204, 214), Font.PLAIN, clampInt(layout.unit() - 2, 7, 10), 1);
	}

	static void drawSberDronesBackdropGrid(Graphics2D graphics, UiRect canvas, UiLayout layout) {
		if (graphics == null || canvas == null || layout == null) {
			return;
		}
		graphics.setColor(new Color(255, 255, 255, 8));
		int step = clampInt(layout.unit() * 2, 12, 28);
		for (int x = canvas.x(); x <= canvas.right(); x += step) {
			graphics.drawLine(x, canvas.y(), x, canvas.bottom());
		}
		for (int y = canvas.y(); y <= canvas.bottom(); y += step) {
			graphics.drawLine(canvas.x(), y, canvas.right(), y);
		}
	}

	static void drawSberDronesSummaryChip(Graphics2D graphics, UiRect rect, String text, UiLayout layout, MediaButtonSegment segment) {
		if (graphics == null || rect == null || layout == null || text == null) {
			return;
		}
		Color color = drawMediaHeaderControlBase(graphics, rect, segment);
		drawCenteredTextFitted(
				graphics,
				text,
				rect,
				color,
				Font.BOLD,
				clampInt(layout.unit() - 2, 7, 10),
					6
			);
	}

	static void drawSberDronesSummaryIconChip(
			Graphics2D graphics,
			UiRect rect,
			PlayerUiIcon icon,
			int count,
			UiLayout layout,
			MediaButtonSegment segment
	) {
		if (graphics == null || rect == null || layout == null || icon == null) {
			return;
		}
		Color color = drawMediaHeaderControlBase(graphics, rect, segment);
		int inset = clampInt(layout.unit() / 2, 4, 8);
		int iconSize = Math.min(
				Math.max(10, rect.height() - inset * 2),
				clampInt(layout.unit() + 4, 12, 16)
		);
		UiRect iconRect = new UiRect(
				rect.x() + inset,
				rect.y() + (rect.height() - iconSize) / 2,
				iconSize,
				iconSize
		);
		drawPlayerUiIcon(graphics, iconRect, icon, color);
		UiRect countRect = new UiRect(
				iconRect.right() + Math.max(3, layout.unit() / 4),
				rect.y(),
				Math.max(8, rect.right() - iconRect.right() - inset - Math.max(3, layout.unit() / 4)),
				rect.height()
		);
		drawCenteredText(
				graphics,
				Integer.toString(Math.max(0, count)),
				countRect,
				color,
				Font.BOLD,
				clampInt(layout.unit() - 1, 8, 10)
		);
	}

	static void drawSberDronesEmptyState(Graphics2D graphics, UiLayout layout, UiRect gridRect) {
		if (graphics == null || layout == null || gridRect == null) {
			return;
		}
		UiRect panel = new UiRect(
				gridRect.x() + gridRect.width() / 10,
				gridRect.y() + gridRect.height() / 5,
				gridRect.width() * 4 / 5,
				gridRect.height() * 3 / 5
			);
			fillRoundedRect(graphics, panel, clampInt(layout.unit() * 2, 12, 22), new Color(18, 20, 22, 224));
			strokeRoundedRect(graphics, panel, clampInt(layout.unit() * 2, 12, 22), 1.0F, new Color(255, 255, 255, 44));
			drawCenteredText(graphics, "Сбер дроны", new UiRect(panel.x(), panel.y() + panel.height() / 5, panel.width(), clampInt(layout.unit() * 2, 18, 28)), new Color(236, 244, 252), Font.BOLD, clampInt(layout.unit() + 1, 11, 16));
			drawCenteredText(graphics, "Подключи дрон или камеру через bluetooth или проводную сеть", new UiRect(panel.x() + clampInt(layout.unit(), 8, 16), panel.y() + panel.height() / 2 - clampInt(layout.unit(), 8, 14), panel.width() - clampInt(layout.unit() * 2, 16, 32), clampInt(layout.unit() * 3, 26, 42)), new Color(178, 194, 208, 224), Font.PLAIN, clampInt(layout.unit() - 1, 8, 12));
		}

	static void drawMediaQueueToggleButton(Graphics2D graphics, UiRect rect, boolean open, UiLayout layout, MediaButtonSegment segment) {
		float strokeWidth = mediaChromeStrokeWidth(rect);
		Color iconColor = drawSmallMediaButtonBase(graphics, rect, segment, open, strokeWidth);
		drawQueueGlyph(graphics, mediaChromeIconRect(rect, layout), iconColor, strokeWidth);
	}

	static void drawYoutubeMusicSearchButton(Graphics2D graphics, UiRect rect, UiLayout layout, MediaButtonSegment segment) {
		float strokeWidth = mediaChromeStrokeWidth(rect);
		Color iconColor = drawSmallMediaButtonBase(graphics, rect, segment, false, strokeWidth);
		drawSearchGlyph(graphics, mediaChromeIconRect(rect, layout), iconColor);
	}

	static void drawYoutubeMusicShuffleButton(Graphics2D graphics, UiRect rect, boolean active, UiLayout layout, MediaButtonSegment segment) {
		float strokeWidth = mediaChromeStrokeWidth(rect);
		Color iconColor = drawSmallMediaButtonBase(graphics, rect, segment, active, strokeWidth);
		drawShuffleGlyph(graphics, mediaChromeIconRect(rect, layout), iconColor);
	}

	static void drawMediaRepeatButton(Graphics2D graphics, UiRect rect, MediaRepeatMode repeatMode, UiLayout layout, MediaButtonSegment segment) {
		float strokeWidth = mediaChromeStrokeWidth(rect);
		MediaRepeatMode mode = repeatMode != null ? repeatMode : MediaRepeatMode.OFF;
		Color iconColor = drawSmallMediaButtonBase(graphics, rect, segment, mode.enabled(), strokeWidth);
		if (mode.repeatCurrent()) {
			drawRepeatOneGlyph(graphics, mediaChromeIconRect(rect, layout), iconColor);
		} else {
			drawRepeatGlyph(graphics, mediaChromeIconRect(rect, layout), iconColor);
		}
	}

	static void drawMediaQueueDismissButton(Graphics2D graphics, UiRect rect, UiLayout layout) {
		float strokeWidth = mediaChromeStrokeWidth(rect);
		Color iconColor = drawSmallMediaButtonBase(graphics, rect, MediaButtonSegment.SINGLE, false, strokeWidth);
		drawDropdownGlyph(graphics, mediaChromeIconRect(rect, layout), iconColor);
	}

	static String queueItemSecondaryLabel(YoutubeQueueItemSnapshot item) {
		if (item == null) {
			return "";
		}
		String subtitle = item.subtitle() == null ? "" : item.subtitle().trim();
		String duration = item.durationMs() > 0L ? formatPlaybackTime(item.durationMs()) : "";
		if (!subtitle.isBlank() && !duration.isBlank()) {
			return subtitle + " \u22c5 " + duration;
		}
		if (!subtitle.isBlank()) {
			return subtitle;
		}
		return duration;
	}

	static void drawQueueScrollButton(Graphics2D graphics, UiRect rect, String label, boolean active, UiLayout layout) {
		fillRoundedRect(graphics, rect, clampInt(layout.unit() * 2, 10, 18), active ? new Color(255, 255, 255, 18) : new Color(255, 255, 255, 8));
		strokeRoundedRect(graphics, rect, clampInt(layout.unit() * 2, 10, 18), 1.0F, new Color(255, 255, 255, active ? 34 : 18));
		drawCenteredText(graphics, label, rect, new Color(248, 251, 255, active ? 228 : 90), Font.BOLD, clampInt(layout.unit() + 1, 10, 16));
	}

	static void drawMediaOverlayWindow(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MinecraftServer server, MediaOverlayWindowSnapshot window) {
		if (graphics == null || layout == null || window == null) {
			return;
		}
		if (window.type() == MediaOverlayWindowType.GALLERY_DELETE_CONFIRM) {
			drawOverlayBackdrop(graphics, mediaCanvasRect(layout));
		}
		BufferedImage image = overlayWindowImage(server, runtimeKey, window, layout);
		UiRect rect = overlayWindowRect(layout, window.type());
		graphics.drawImage(image, rect.x(), rect.y(), null);
	}

	static BufferedImage overlayWindowImage(MinecraftServer server, ScreenRuntimeKey runtimeKey, MediaOverlayWindowSnapshot window, UiLayout layout) {
		UiRect rect = overlayWindowRect(layout, window.type());
		if (window.type() == MediaOverlayWindowType.YOUTUBE_QUEUE) {
			return renderOverlayWindowImage(window, layout, rect, runtimeKey);
		}
		if (queueCacheAnimationActive(window)) {
			return renderOverlayWindowImage(window, layout, rect, null);
		}
		OverlayWindowCacheKey key = new OverlayWindowCacheKey(window, rect.width(), rect.height(), layout.unit());
		OverlayWindowFamilyKey familyKey = new OverlayWindowFamilyKey(window.type(), rect.width(), rect.height(), layout.unit());
		OverlayWindowRenderState cached = OVERLAY_WINDOW_CACHE.computeIfAbsent(key, ignored -> new OverlayWindowRenderState());
		cached.lastAccessNanos = System.nanoTime();
		BufferedImage ready = cached.image;
		if (ready != null) {
			return ready;
		}
		scheduleOverlayWindowRender(server, runtimeKey, window, layout, rect, key, familyKey, cached);
		BufferedImage familyFallback = OVERLAY_WINDOW_FAMILY_CACHE.get(familyKey);
		if (familyFallback != null) {
			return familyFallback;
		}
		return OVERLAY_WINDOW_PLACEHOLDER_CACHE.computeIfAbsent(
				familyKey,
				ignored -> renderOverlayWindowPlaceholder(window.type(), layout, rect)
		);
	}

	static boolean queueCacheAnimationActive(MediaOverlayWindowSnapshot window) {
		if (window == null || window.type() != MediaOverlayWindowType.YOUTUBE_QUEUE || window.items() == null) {
			return false;
		}
		for (YoutubeQueueItemSnapshot item : window.items()) {
			if (item != null && item.cacheActive() && !item.cacheComplete()) {
				return true;
			}
		}
		return false;
	}

	static void scheduleOverlayWindowRender(
			MinecraftServer server,
			ScreenRuntimeKey runtimeKey,
			MediaOverlayWindowSnapshot window,
			UiLayout layout,
			UiRect rect,
			OverlayWindowCacheKey key,
			OverlayWindowFamilyKey familyKey,
			OverlayWindowRenderState cacheState
	) {
		if (window == null || layout == null || rect == null || cacheState == null) {
			return;
		}
		ensureExecutors();
		synchronized (cacheState) {
			if (cacheState.image != null) {
				return;
			}
			if (cacheState.future != null && !cacheState.future.isDone()) {
				return;
			}
			cacheState.future = CompletableFuture
					.supplyAsync(() -> renderOverlayWindowImage(window, layout, rect, null), overlayWindowExecutor)
					.whenComplete((rendered, throwable) -> {
						if (throwable != null) {
							OVERLAY_WINDOW_CACHE.remove(key, cacheState);
							Lg2.LOGGER.debug("Failed to render overlay window {}", window.type(), throwable);
							return;
						}
						cacheState.image = rendered;
						cacheState.lastAccessNanos = System.nanoTime();
						OVERLAY_WINDOW_FAMILY_CACHE.put(familyKey, rendered);
						trimOverlayWindowCaches();
						if (server != null && runtimeKey != null) {
							server.execute(() -> requestRuntimeRender(server, runtimeKey));
						}
					});
		}
	}

	static void trimOverlayWindowCaches() {
		if (OVERLAY_WINDOW_CACHE.size() <= 256 && OVERLAY_WINDOW_FAMILY_CACHE.size() <= 24 && OVERLAY_WINDOW_PLACEHOLDER_CACHE.size() <= 24) {
			return;
		}
		OVERLAY_WINDOW_CACHE.entrySet().removeIf(entry -> {
			OverlayWindowRenderState state = entry.getValue();
			return state == null
					|| (state.image != null && System.nanoTime() - state.lastAccessNanos > TimeUnit.MINUTES.toNanos(3));
		});
		if (OVERLAY_WINDOW_CACHE.size() > 320) {
			OVERLAY_WINDOW_CACHE.clear();
		}
		if (OVERLAY_WINDOW_FAMILY_CACHE.size() > 32) {
			OVERLAY_WINDOW_FAMILY_CACHE.clear();
		}
		if (OVERLAY_WINDOW_PLACEHOLDER_CACHE.size() > 32) {
			OVERLAY_WINDOW_PLACEHOLDER_CACHE.clear();
		}
	}

	static BufferedImage renderOverlayWindowImage(MediaOverlayWindowSnapshot window, UiLayout layout, UiRect rect, ScreenRuntimeKey runtimeKey) {
		BufferedImage image = new BufferedImage(Math.max(1, rect.width()), Math.max(1, rect.height()), BufferedImage.TYPE_INT_ARGB);
		Graphics2D overlayGraphics = image.createGraphics();
		configureUiGraphics(overlayGraphics);
		overlayGraphics.translate(-rect.x(), -rect.y());
		switch (window.type()) {
			case YOUTUBE_QUEUE -> drawYoutubeQueueWindow(overlayGraphics, layout, runtimeKey, window);
			case GALLERY_DELETE_CONFIRM -> drawGalleryDeleteConfirmWindow(overlayGraphics, layout, window);
			case GALLERY_FILE_MENU -> drawGalleryFileMenuWindow(overlayGraphics, layout, window);
			case PLAYER_BACKGROUND -> drawPlayerBackgroundWindow(overlayGraphics, layout, window);
			case GALLERY_SLIDESHOW -> drawGallerySlideshowWindow(overlayGraphics, layout, window);
		}
		overlayGraphics.dispose();
		return image;
	}

	static BufferedImage renderOverlayWindowPlaceholder(MediaOverlayWindowType type, UiLayout layout, UiRect rect) {
		BufferedImage image = new BufferedImage(Math.max(1, rect.width()), Math.max(1, rect.height()), BufferedImage.TYPE_INT_ARGB);
		Graphics2D overlayGraphics = image.createGraphics();
		configureUiGraphics(overlayGraphics);
		overlayGraphics.translate(-rect.x(), -rect.y());
		switch (type) {
			case YOUTUBE_QUEUE -> drawYoutubeQueueWindowPlaceholder(overlayGraphics, layout);
			case GALLERY_DELETE_CONFIRM -> drawGalleryDeleteConfirmWindowPlaceholder(overlayGraphics, layout);
			case GALLERY_FILE_MENU -> drawGalleryFileMenuWindowPlaceholder(overlayGraphics, layout);
			case PLAYER_BACKGROUND -> drawPlayerBackgroundWindowPlaceholder(overlayGraphics, layout);
			case GALLERY_SLIDESHOW -> drawGallerySlideshowWindowPlaceholder(overlayGraphics, layout);
		}
		overlayGraphics.dispose();
		return image;
	}

	static void drawOverlayModalBase(Graphics2D graphics, UiLayout layout, UiRect panel, UiRect header, UiRect closeRect, String title, String subtitle) {
		if (graphics == null || layout == null || panel == null || header == null || closeRect == null) {
			return;
		}
		graphics.setPaint(new GradientPaint(
				panel.x(),
				panel.y(),
				new Color(8, 12, 18, 242),
				panel.right(),
				panel.bottom(),
				new Color(20, 26, 34, 236)
		));
		fillRoundedRect(graphics, panel, clampInt(layout.unit() * 3, 16, 28), null);
		strokeRoundedRect(graphics, panel, clampInt(layout.unit() * 3, 16, 28), 1.2F, new Color(255, 255, 255, 62));

		fillRoundedRect(graphics, header, clampInt(layout.unit() * 2, 12, 22), new Color(255, 255, 255, 14));
		strokeRoundedRect(graphics, header, clampInt(layout.unit() * 2, 12, 22), 1.0F, new Color(255, 255, 255, 34));
		drawVerticalText(
				graphics,
				title,
				new UiRect(
						header.x() + clampInt(layout.unit(), 8, 14),
						header.y(),
						Math.max(16, closeRect.x() - header.x() - layout.unit() * 2),
						header.height() / 2 + 2
				),
				new Color(248, 251, 255),
				Font.BOLD,
				compactScreenLayout(layout) ? clampInt(layout.unit() + (ultraCompactScreenLayout(layout) ? 1 : 2), 9, 16) : clampInt(layout.unit() + 4, 14, 24)
		);
		if (subtitle != null && !subtitle.isBlank()) {
			drawVerticalText(
					graphics,
					subtitle,
					new UiRect(
							header.x() + clampInt(layout.unit(), 8, 14),
							header.y() + header.height() / 2 - 1,
							Math.max(16, closeRect.x() - header.x() - layout.unit() * 2),
							Math.max(12, header.bottom() - (header.y() + header.height() / 2 - 1) - 2)
					),
					new Color(178, 194, 212, 220),
					Font.PLAIN,
					compactScreenLayout(layout) ? clampInt(layout.unit(), 8, 11) : clampInt(layout.unit() + 1, 10, 15)
			);
		}
		drawMediaCloseButton(graphics, closeRect, layout);
	}

	static void drawYoutubeQueueWindow(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MediaOverlayWindowSnapshot window) {
		boolean compact = compactScreenLayout(layout);
		boolean ultraCompact = ultraCompactScreenLayout(layout);
		UiRect header = mediaQueueHeaderRect(layout);
		UiRect headerTitleRect = mediaQueueHeaderTitleRect(layout);
		UiRect headerSubtitleRect = mediaQueueHeaderSubtitleRect(layout);
		UiRect closeRect = mediaQueueCloseRect(layout);
		UiRect shuffleRect = mediaQueueShuffleRect(layout);
		UiRect repeatRect = mediaQueueRepeatRect(layout);
		UiRect list = mediaQueueListRect(layout);
		UiRect footer = mediaQueueFooterRect(layout);
		UiRect scrollbarTrackRect = mediaQueueScrollbarTrackRect(layout);
		int visibleRows = mediaQueueVisibleRows(layout);
		int renderableRows = mediaQueueRenderableRows(layout);
		int totalItems = Math.max(0, window.totalItemCount());
		int windowStartIndex = clampInt(window.itemWindowStartIndex(), 0, totalItems);
		int scroll = clampInt(window.scroll(), 0, Math.max(0, totalItems - visibleRows));
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MEDIA_QUEUE_WINDOW,
				scroll,
				Math.max(0, totalItems - visibleRows)
		);
		drawCenteredTextFitted(
				graphics,
				window.title(),
				headerTitleRect,
				new Color(248, 240, 244, 238),
				Font.BOLD,
				compact ? clampInt(layout.unit() + 2, 10, 16) : clampInt(layout.unit() + 4, 14, 22),
				clampInt(layout.unit(), 8, 14)
		);
		drawCenteredTextFitted(
				graphics,
				window.subtitle(),
				headerSubtitleRect,
				new Color(244, 232, 236, 176),
				Font.PLAIN,
				compact ? clampInt(layout.unit(), 8, 11) : clampInt(layout.unit() + 1, 10, 14),
				clampInt(layout.unit(), 8, 14)
		);

		if (totalItems <= 0) {
			drawCenteredText(graphics, "Очередь пуста", list, new Color(210, 218, 226, 214), Font.PLAIN, compact ? clampInt(layout.unit() + 1, 9, 13) : clampInt(layout.unit() + 2, 12, 18));
		} else {
			int baseRow = visualScroll.anchorIndex();
			int rowOffset = -(int) Math.round(visualScroll.fraction() * mediaQueueRowHeight(layout));
			int rowCount = Math.min(
					Math.max(0, totalItems - baseRow),
					renderableRows + (visualScroll.animated() && baseRow + renderableRows < totalItems ? 1 : 0)
			);
			Shape previousClip = graphics.getClip();
			graphics.clipRect(list.x(), list.y(), list.width(), list.height());
			for (int visibleIndex = 0; visibleIndex < rowCount; visibleIndex++) {
				int queueIndex = baseRow + visibleIndex;
				int windowIndex = queueIndex - windowStartIndex;
				if (windowIndex < 0 || windowIndex >= window.items().size()) {
					continue;
				}
				YoutubeQueueItemSnapshot item = window.items().get(windowIndex);
				if (item == null) {
					continue;
				}
				UiRect rowRect = offsetRect(mediaQueueRowRect(layout, visibleIndex), 0, rowOffset);
				UiRect removeRect = mediaQueueRemoveRect(rowRect, layout);
				UiRect cacheStatusRect = mediaQueueCacheStatusRect(rowRect, removeRect, layout);
				UiRect previewRect = mediaQueueThumbnailRect(rowRect, item.previewFrame(), item.squarePreviewFallback(), layout);
				UiRect titleRect = mediaQueueTitleRect(rowRect, cacheStatusRect, previewRect, layout);
				UiRect metaRect = mediaQueueMetaRect(rowRect, cacheStatusRect, previewRect, layout);
				String secondaryLine = queueItemSecondaryLabel(item);
				Color fill = item.current() ? new Color(248, 246, 246, 238) : new Color(255, 255, 255, 8);
				Color primaryText = item.current() ? new Color(24, 20, 24, 244) : new Color(248, 240, 244, 236);
				Color secondaryText = item.current() ? new Color(64, 56, 62, 220) : new Color(244, 232, 236, 164);
				int rowArc = clampInt(layout.unit() * 2, 12, 18);
				fillRoundedRect(graphics, rowRect, rowArc, fill);
				drawQueueThumbnail(graphics, previewRect, item.previewFrame(), item.squarePreviewFallback(), item.current(), layout);
				drawWrappedText(
						graphics,
						item.title(),
						titleRect,
						primaryText,
						Font.BOLD,
						compact ? clampInt(layout.unit() + (ultraCompact ? 0 : 1), 8, 12) : clampInt(layout.unit() + 2, 12, 18),
						2
				);
				if (!secondaryLine.isBlank()) {
					drawVerticalText(
							graphics,
							secondaryLine,
							metaRect,
							secondaryText,
							Font.PLAIN,
							compact ? clampInt(layout.unit() - 1, 7, 10) : clampInt(layout.unit() + 1, 10, 14)
					);
				}
				drawQueueCacheStatusIcon(graphics, cacheStatusRect, item, layout);
				float strokeWidth = mediaChromeStrokeWidth(removeRect);
				Color removeIconColor = drawSmallMediaButtonBase(graphics, removeRect, MediaButtonSegment.SINGLE, false, strokeWidth);
				drawCloseGlyph(graphics, mediaChromeIconRect(removeRect, layout), item.current() ? new Color(36, 28, 32, 220) : removeIconColor);
			}
			graphics.setClip(previousClip);
			drawQueueScrollbar(graphics, scrollbarTrackRect, visualScroll.displayValue(), visibleRows, totalItems, layout);
		}

		drawYoutubeMusicShuffleButton(graphics, shuffleRect, window.shuffleEnabled(), layout, MediaButtonSegment.SINGLE);
		drawMediaQueueDismissButton(graphics, closeRect, layout);
		drawMediaRepeatButton(graphics, repeatRect, resolvedRepeatMode(window), layout, MediaButtonSegment.SINGLE);
	}

	static void drawGalleryDeleteConfirmWindow(Graphics2D graphics, UiLayout layout, MediaOverlayWindowSnapshot window) {
		UiRect header = galleryDeleteConfirmHeaderRect(layout);
		UiRect closeRect = galleryDeleteConfirmCloseRect(layout);
		UiRect infoRect = galleryDeleteConfirmInfoRect(layout);
		UiRect cancelRect = galleryDeleteConfirmCancelRect(layout);
		UiRect confirmRect = galleryDeleteConfirmConfirmRect(layout);
		drawDeleteConfirmWindow(
				graphics,
				layout,
				window != null && window.subtitle() != null && !window.subtitle().isBlank() ? window.subtitle() : "Медиа",
				"Удаление",
				header,
				closeRect,
				infoRect,
				cancelRect,
				confirmRect,
				10,
				6
		);
	}

	static void drawDeleteConfirmWindow(
			Graphics2D graphics,
			UiLayout layout,
			String title,
			String subtitle,
			UiRect header,
			UiRect closeRect,
			UiRect infoRect,
			UiRect cancelRect,
			UiRect confirmRect,
			int enabledFillAlpha,
			int disabledFillAlpha
	) {
		if (graphics == null || layout == null || header == null || closeRect == null || infoRect == null || cancelRect == null || confirmRect == null) {
			return;
		}
		drawCloseGlyph(graphics, mediaChromeIconRect(closeRect, layout), new Color(248, 251, 255, 236));
		drawOverlayWindowHeaderText(graphics, layout, header, title, subtitle);
		drawGalleryDeleteConfirmInfo(graphics, layout, infoRect);
		drawGalleryFileMenuActionButton(graphics, layout, cancelRect, PlayerUiIcon.CLOSE, "ОТМЕНА", "", true, false, false, enabledFillAlpha, disabledFillAlpha);
		drawGalleryFileMenuActionButton(graphics, layout, confirmRect, PlayerUiIcon.TRASH, "УДАЛИТЬ", "", true, false, true, enabledFillAlpha, disabledFillAlpha);
	}

	static void drawGalleryDeleteConfirmInfo(Graphics2D graphics, UiLayout layout, UiRect rect) {
		if (graphics == null || layout == null || rect == null) {
			return;
		}
		drawCenteredTextFitted(
				graphics,
				"Вы хотите удалить это?",
				rect,
				new Color(248, 240, 244, 238),
				Font.BOLD,
				ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 1, 7, 9) : compactScreenLayout(layout) ? clampInt(layout.unit() + 2, 10, 16) : clampInt(layout.unit() + 3, 14, 20),
				7
		);
	}

	static void drawGalleryDeleteConfirmWindowPlaceholder(Graphics2D graphics, UiLayout layout) {
		drawGalleryDeleteConfirmWindow(
				graphics,
				layout,
				new MediaOverlayWindowSnapshot(
						MediaOverlayWindowType.GALLERY_DELETE_CONFIRM,
						"УДАЛИТЬ?",
						"Подтверждение",
						List.of(),
						0,
						0,
						null,
						null,
						0,
						-1,
						false,
						MediaRepeatMode.OFF,
						null,
						false,
						MediaScaleMode.FIT
				)
		);
	}

	static void drawGalleryFileMenuWindow(Graphics2D graphics, UiLayout layout, MediaOverlayWindowSnapshot window) {
		UiRect canvas = mediaCanvasRect(layout);
		UiRect header = galleryFileMenuHeaderRect(layout);
		UiRect closeRect = galleryFileMenuCloseRect(layout);
		GalleryFileMenuSnapshot file = window != null ? window.galleryFile() : null;
		drawOverlayBackdrop(graphics, canvas);
		drawCloseGlyph(graphics, mediaChromeIconRect(closeRect, layout), new Color(248, 251, 255, 236));

		String headerTitle = file != null && file.title() != null && !file.title().isBlank()
				? file.title()
				: window != null && window.subtitle() != null && !window.subtitle().isBlank() ? window.subtitle() : "Медиа";
		String headerSubtitle = file != null && file.subtitle() != null && !file.subtitle().isBlank()
				? file.subtitle()
				: window != null && window.title() != null ? window.title() : "";
		drawOverlayWindowHeaderText(graphics, layout, header, headerTitle, headerSubtitle);

		boolean wallpaperVisible = file != null && file.canWallpaper();
		int actionCount = wallpaperVisible ? 5 : 4;
		int playerBackgroundIndex = wallpaperVisible ? 3 : 2;
		int deleteIndex = playerBackgroundIndex + 1;
		drawGalleryFileMenuActionButton(graphics, layout, galleryFileMenuActionRect(layout, 0, actionCount), PlayerUiIcon.EDIT, "ПЕРЕИМЕНОВАТЬ", "", file != null && file.canRename(), false, false);
		drawGalleryFileMenuActionButton(graphics, layout, galleryFileMenuActionRect(layout, 1, actionCount), PlayerUiIcon.SEND_PLANE, "ПОДЕЛИТЬСЯ", "", file != null && file.canShare(), false, false);
		if (wallpaperVisible) {
			drawGalleryFileMenuActionButton(graphics, layout, galleryFileMenuActionRect(layout, 2, actionCount), file.wallpaperSelected() ? PlayerUiIcon.CHECK : PlayerUiIcon.WALLPAPER, file.wallpaperSelected() ? "ОБОИ УСТАНОВЛЕНЫ" : "СДЕЛАТЬ ОБОЯМИ", "", true, file.wallpaperSelected(), false);
		}
		drawGalleryFileMenuActionButton(graphics, layout, galleryFileMenuActionRect(layout, playerBackgroundIndex, actionCount), PlayerUiIcon.SETTINGS, "ФОН ПЛЕЕРА", "", true, false, false);
		drawGalleryFileMenuActionButton(graphics, layout, galleryFileMenuActionRect(layout, deleteIndex, actionCount), PlayerUiIcon.TRASH, "УДАЛИТЬ", "", file != null && file.saved(), false, true);
	}

	static void drawOverlayBackdrop(Graphics2D graphics, UiRect rect) {
		if (graphics == null || rect == null) {
			return;
		}
		graphics.setPaint(new GradientPaint(
				rect.x(),
				rect.y(),
				new Color(10, 12, 18, 198),
				rect.x(),
				rect.bottom(),
				new Color(10, 12, 18, 166)
		));
		graphics.fillRect(rect.x(), rect.y(), rect.width(), rect.height());
	}

	static void drawOverlayWindowHeaderText(Graphics2D graphics, UiLayout layout, UiRect header, String title, String subtitle) {
		if (graphics == null || layout == null || header == null) {
			return;
		}
		String safeTitle = title != null ? title : "";
		String safeSubtitle = subtitle != null ? subtitle : "";
		if (ultraCompactScreenLayout(layout)) {
			UiRect headerTitleRect = new UiRect(
					header.x() + Math.max(2, layout.unit() / 3),
					header.y(),
					Math.max(10, header.width() - clampInt(layout.unit() * 4, 16, 22)),
					header.height()
			);
			drawCenteredTextFitted(
					graphics,
					safeTitle,
					headerTitleRect,
					new Color(248, 240, 244, 226),
					Font.BOLD,
					clampInt(layout.unit() + 1, 6, 8),
					5
			);
			return;
		}
		UiRect headerTitleRect = new UiRect(header.x(), header.y(), header.width(), Math.max(12, header.height() / 2));
		UiRect headerSubtitleRect = new UiRect(header.x(), headerTitleRect.bottom() - 1, header.width(), Math.max(10, header.bottom() - headerTitleRect.bottom() + 1));
		drawCenteredTextFitted(
				graphics,
				safeTitle,
				headerTitleRect,
				new Color(248, 240, 244, 238),
				Font.BOLD,
				compactScreenLayout(layout) ? clampInt(layout.unit() + 2, 10, 16) : clampInt(layout.unit() + 4, 14, 22),
				clampInt(layout.unit(), 8, 14)
		);
		drawCenteredTextFitted(
				graphics,
				safeSubtitle,
				headerSubtitleRect,
				new Color(188, 198, 212, 176),
				Font.PLAIN,
				compactScreenLayout(layout) ? clampInt(layout.unit(), 8, 11) : clampInt(layout.unit() + 1, 10, 14),
				clampInt(layout.unit(), 8, 14)
		);
	}

	static void drawGalleryFileMenuWindowPlaceholder(Graphics2D graphics, UiLayout layout) {
		drawGalleryFileMenuWindow(
				graphics,
				layout,
				new MediaOverlayWindowSnapshot(
						MediaOverlayWindowType.GALLERY_FILE_MENU,
						"ФАЙЛ",
						"Медиа",
						List.of(),
						0,
						0,
						new GalleryFileMenuSnapshot("Медиа", "", false, false, false, false, false),
						null,
						0,
						-1,
						false,
						MediaRepeatMode.OFF,
						null,
						false,
						MediaScaleMode.FIT
				)
		);
	}

	static void drawQueueThumbnail(Graphics2D graphics, UiRect rect, BufferedImage preview, boolean squareFallback, boolean current, UiLayout layout) {
		if (graphics == null || rect == null || layout == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		int arc = clampInt(Math.min(rect.width(), rect.height()) / 4, 5, 8);
		Color fill = current ? new Color(26, 22, 28, 36) : new Color(255, 255, 255, 14);
		fillRoundedRect(graphics, rect, arc, fill);
		if (preview != null) {
			drawRoundedScaledImage(graphics, preview, rect, MediaScaleMode.FILL, arc);
			return;
		}
		PlayerUiIcon icon = squareFallback ? PlayerUiIcon.FILE_MUSIC : PlayerUiIcon.MEDIA_VIDEO;
		drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), icon, current ? new Color(32, 26, 32, 150) : new Color(248, 251, 255, 122));
	}

	static void drawGalleryFileMenuActionButton(
			Graphics2D graphics,
			UiLayout layout,
			UiRect rect,
			PlayerUiIcon icon,
			String title,
			String subtitle,
			boolean enabled,
			boolean selected,
			boolean danger
	) {
		drawGalleryFileMenuActionButton(graphics, layout, rect, icon, title, subtitle, enabled, selected, danger, 10, 6);
	}

	static void drawGalleryFileMenuActionButton(
			Graphics2D graphics,
			UiLayout layout,
			UiRect rect,
			PlayerUiIcon icon,
			String title,
			String subtitle,
			boolean enabled,
			boolean selected,
			boolean danger,
			int enabledFillAlpha,
			int disabledFillAlpha
	) {
		if (graphics == null || layout == null || rect == null || icon == null) {
			return;
		}
		boolean ultra = ultraCompactScreenLayout(layout);
		Color fill = selected
				? new Color(248, 246, 246, 236)
				: enabled ? new Color(255, 255, 255, clampInt(enabledFillAlpha, 0, 255)) : new Color(255, 255, 255, clampInt(disabledFillAlpha, 0, 255));
		Color titleColor = selected
				? new Color(22, 20, 24, 244)
				: danger && enabled ? new Color(255, 142, 150, 238) : enabled ? new Color(248, 240, 244, 236) : new Color(200, 208, 218, 150);
		int arc = ultra ? clampInt(layout.unit(), 5, 8) : clampInt(layout.unit() * 2, 12, 18);
		fillRoundedRect(graphics, rect, arc, fill);
		int iconSize = ultra ? clampInt(layout.unit() + 5, 9, 11) : clampInt(layout.unit() + 6, 14, 22);
		int sideInset = ultra ? Math.max(2, layout.unit() / 3) : clampInt(layout.unit(), 8, 14);
		UiRect iconRect = new UiRect(
				rect.x() + sideInset,
				rect.y() + (rect.height() - iconSize) / 2,
				iconSize,
				iconSize
		);
		drawPlayerUiIcon(graphics, iconRect, icon, titleColor);
		UiRect titleRect = new UiRect(
				iconRect.right() + (ultra ? Math.max(2, layout.unit() / 3) : clampInt(layout.unit(), 8, 14)),
				rect.y(),
				Math.max(12, rect.width() - (iconRect.right() - rect.x()) - (ultra ? Math.max(4, layout.unit()) : clampInt(layout.unit() * 2, 16, 24))),
				rect.height()
		);
		drawVerticalText(graphics, title, titleRect, titleColor, Font.BOLD, ultra ? clampInt(layout.unit() + 2, 7, 8) : compactScreenLayout(layout) ? clampInt(layout.unit() + 1, 9, 14) : clampInt(layout.unit() + 2, 11, 18));
	}

	static void drawPlayerBackgroundWindow(Graphics2D graphics, UiLayout layout, MediaOverlayWindowSnapshot window) {
		UiRect canvas = mediaCanvasRect(layout);
		UiRect header = playerBackgroundHeaderRect(layout);
		UiRect closeRect = playerBackgroundCloseRect(layout);
		drawOverlayBackdrop(graphics, canvas);
		drawCloseGlyph(graphics, mediaChromeIconRect(closeRect, layout), new Color(248, 251, 255, 236));
		drawOverlayWindowHeaderText(graphics, layout, header, window.title(), window.subtitle());
		boolean artworkSelected = window.playerBackgroundMode() == PlayerBackgroundMode.ARTWORK;
		boolean gallerySelected = window.playerBackgroundMode() == PlayerBackgroundMode.GALLERY;
		drawPlayerBackgroundOptionButton(graphics, layout, playerBackgroundOptionRect(layout, 0), PlayerBackgroundMode.ARTWORK, artworkSelected, true, false, artworkSelected);
		drawPlayerBackgroundOptionButton(graphics, layout, playerBackgroundOptionRect(layout, 1), PlayerBackgroundMode.GALLERY, gallerySelected, true, window.galleryBackgroundAvailable(), gallerySelected);
		drawPlayerBackgroundScaleButton(graphics, layout, window);
		drawPlayerBackgroundOptionButton(graphics, layout, playerBackgroundOptionRect(layout, 2), PlayerBackgroundMode.BLACK, window.playerBackgroundMode() == PlayerBackgroundMode.BLACK, true, false, false);
		drawPlayerBackgroundOptionButton(graphics, layout, playerBackgroundOptionRect(layout, 3), PlayerBackgroundMode.EMPTY, window.playerBackgroundMode() == PlayerBackgroundMode.EMPTY, true, false, false);
	}

	static void drawPlayerBackgroundWindowPlaceholder(Graphics2D graphics, UiLayout layout) {
		drawPlayerBackgroundWindow(
				graphics,
				layout,
				new MediaOverlayWindowSnapshot(
						MediaOverlayWindowType.PLAYER_BACKGROUND,
						"ФОН ПЛЕЕРА",
						"Для видео, музыки, картинок и трансляций",
						List.of(),
						0,
						0,
						null,
						null,
						0,
						-1,
						false,
						MediaRepeatMode.OFF,
						PlayerBackgroundMode.BLACK,
						true,
						MediaScaleMode.FIT
				)
		);
	}

	static void drawGallerySlideshowWindow(Graphics2D graphics, UiLayout layout, MediaOverlayWindowSnapshot window) {
		if (graphics == null || layout == null) {
			return;
		}
		UiRect canvas = mediaCanvasRect(layout);
		UiRect header = gallerySlideshowHeaderRect(layout);
		UiRect closeRect = gallerySlideshowCloseRect(layout);
		UiRect durationRect = gallerySlideshowDurationRect(layout);
		UiRect trackRect = gallerySlideshowDurationTrackRect(layout);
		GallerySlideshowSettingsSnapshot settings = window != null ? window.gallerySlideshow() : null;
		int minSeconds = settings != null ? settings.minDurationSeconds() : minGallerySlideshowDurationSeconds();
		int maxSeconds = settings != null ? settings.maxDurationSeconds() : maxGallerySlideshowDurationSeconds();
		int durationSeconds = settings != null ? settings.durationSeconds() : 25;
		int rawMinSeconds = minSeconds;
		int rawMaxSeconds = maxSeconds;
		minSeconds = Math.min(rawMinSeconds, rawMaxSeconds);
		maxSeconds = Math.max(rawMinSeconds, rawMaxSeconds);
		durationSeconds = clampInt(durationSeconds, minSeconds, maxSeconds);

		drawOverlayBackdrop(graphics, canvas);
		drawCloseGlyph(graphics, mediaChromeIconRect(closeRect, layout), new Color(248, 251, 255, 236));
		drawOverlayWindowHeaderText(
				graphics,
				layout,
				header,
				window != null ? window.title() : "Время показа картинок и gif",
				""
		);

		int arc = ultraCompactScreenLayout(layout) ? clampInt(layout.unit(), 5, 8) : clampInt(layout.unit() * 2, 10, 16);
		fillRoundedRect(graphics, durationRect, arc, new Color(255, 255, 255, 10));
		int markerY = trackRect.y() + trackRect.height() / 2;
		int startX = trackRect.x();
		int endX = trackRect.right();
		double fraction = maxSeconds > minSeconds
				? (durationSeconds - minSeconds) / (double) (maxSeconds - minSeconds)
				: 0.0D;
		int selectedX = clampInt((int) Math.round(startX + fraction * Math.max(1, endX - startX)), startX, endX);
		graphics.setColor(new Color(255, 255, 255, 30));
		graphics.fillRoundRect(startX, markerY - 1, Math.max(1, endX - startX), 3, 3, 3);
		graphics.setColor(new Color(248, 251, 255, 220));
		graphics.fillRoundRect(startX, markerY - 1, Math.max(1, selectedX - startX), 3, 3, 3);

		int dotSize = clampInt(layout.unit(), 7, 13);
		fillRoundedRect(
				graphics,
				new UiRect(selectedX - dotSize / 2, markerY - dotSize / 2, dotSize, dotSize),
				dotSize,
				new Color(248, 251, 255, 248)
		);
		int labelHeight = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 2, 12, 16) : clampInt(layout.unit() * 2 + 2, 18, 24);
		int labelWidth = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 6, 32, 44) : clampInt(layout.unit() * 7, 48, 72);
		int labelFont = ultraCompactScreenLayout(layout) ? clampInt(layout.unit(), 6, 8) : clampInt(layout.unit() + 1, 9, 13);
		int bottomY = Math.min(durationRect.bottom() - labelHeight, markerY + Math.max(4, layout.unit() / 2));
		drawCenteredTextFitted(
				graphics,
				formatSlideshowSeconds(minSeconds),
				new UiRect(startX - labelWidth / 2, bottomY, labelWidth, labelHeight),
				new Color(188, 198, 212, 184),
				Font.BOLD,
				labelFont,
				6
		);
		drawCenteredTextFitted(
				graphics,
				formatSlideshowSeconds(maxSeconds),
				new UiRect(endX - labelWidth / 2, bottomY, labelWidth, labelHeight),
				new Color(188, 198, 212, 184),
				Font.BOLD,
				labelFont,
				6
		);
		drawCenteredTextFitted(
				graphics,
				formatSlideshowSeconds(durationSeconds),
				new UiRect(clampInt(selectedX - labelWidth / 2, durationRect.x(), Math.max(durationRect.x(), durationRect.right() - labelWidth)), Math.max(durationRect.y(), markerY - labelHeight - Math.max(4, layout.unit() / 2)), labelWidth, labelHeight),
				new Color(248, 251, 255, 244),
				Font.BOLD,
				labelFont,
				6
		);
	}

	static void drawGallerySlideshowWindowPlaceholder(Graphics2D graphics, UiLayout layout) {
		drawGallerySlideshowWindow(
				graphics,
				layout,
				new MediaOverlayWindowSnapshot(
						MediaOverlayWindowType.GALLERY_SLIDESHOW,
						"Время показа картинок и gif",
						"",
						List.of(),
						0,
						0,
						null,
						new GallerySlideshowSettingsSnapshot(true, 25, minGallerySlideshowDurationSeconds(), maxGallerySlideshowDurationSeconds()),
						0,
						0,
						false,
						MediaRepeatMode.OFF,
						null,
						false,
						MediaScaleMode.FIT
				)
		);
	}

	static void drawPlayerBackgroundOptionButton(Graphics2D graphics, UiLayout layout, UiRect rect, PlayerBackgroundMode mode, boolean selected, boolean enabled, boolean customWallpaperLoaded, boolean reserveScaleButtonsSpace) {
		if (graphics == null || layout == null || rect == null || mode == null) {
			return;
		}
		boolean ultra = ultraCompactScreenLayout(layout);
		Color fill = selected
				? new Color(248, 246, 246, 236)
				: enabled ? new Color(255, 255, 255, 10) : new Color(255, 255, 255, 6);
		Color titleColor = selected ? new Color(22, 20, 24, 244) : enabled ? new Color(248, 240, 244, 236) : new Color(200, 208, 218, 166);
		Color subtitleColor = selected ? new Color(68, 60, 66, 216) : enabled ? new Color(214, 221, 230, 184) : new Color(164, 174, 186, 140);
		int arc = ultra ? clampInt(layout.unit(), 5, 8) : clampInt(layout.unit() * 2, 12, 18);
		fillRoundedRect(graphics, rect, arc, fill);
		int iconSize = ultra ? clampInt(layout.unit() + 5, 9, 11) : clampInt(layout.unit() + 6, 14, 22);
		int sideInset = ultra ? Math.max(2, layout.unit() / 3) : clampInt(layout.unit(), 8, 14);
		UiRect iconRect = new UiRect(
				rect.x() + sideInset,
				rect.y() + (rect.height() - iconSize) / 2,
				iconSize,
				iconSize
		);
		if (selected) {
			drawPlayerUiIcon(graphics, iconRect, PlayerUiIcon.CHECK, titleColor);
		} else if (mode == PlayerBackgroundMode.GALLERY) {
			drawWallpaperGlyph(graphics, iconRect, titleColor, mediaChromeStrokeWidth(iconRect));
		} else if (mode == PlayerBackgroundMode.ARTWORK) {
			drawMediaFillGlyph(graphics, iconRect, titleColor, mediaChromeStrokeWidth(iconRect));
		} else if (mode == PlayerBackgroundMode.EMPTY) {
			strokeRoundedRect(graphics, iconRect, clampInt(layout.unit(), 8, 12), mediaChromeStrokeWidth(iconRect), titleColor);
		} else {
			fillRoundedRect(graphics, iconRect, clampInt(layout.unit(), 8, 12), titleColor);
		}
		if (ultra) {
			UiRect titleRect = new UiRect(
					iconRect.right() + Math.max(2, layout.unit() / 3),
					rect.y(),
					Math.max(12, rect.width() - (iconRect.right() - rect.x()) - Math.max(4, layout.unit()) - (reserveScaleButtonsSpace ? playerBackgroundScaleButtonReserveWidth(layout) : 0)),
					rect.height()
			);
			drawVerticalText(graphics, playerBackgroundModeTitle(mode), titleRect, titleColor, Font.BOLD, clampInt(layout.unit() + 2, 7, 8));
			return;
		}
		UiRect titleRect = new UiRect(
				iconRect.right() + clampInt(layout.unit(), 8, 14),
				rect.y() + clampInt(layout.unit() / 4, 2, 5),
				Math.max(24, rect.width() - (iconRect.right() - rect.x()) - clampInt(layout.unit() * 2, 14, 24) - (reserveScaleButtonsSpace ? playerBackgroundScaleButtonReserveWidth(layout) : 0)),
				Math.max(12, rect.height() / 2)
		);
		UiRect subtitleRect = new UiRect(
				titleRect.x(),
				titleRect.bottom() - clampInt(layout.unit() / 6, 1, 2),
				titleRect.width(),
				Math.max(10, rect.bottom() - titleRect.bottom() - clampInt(layout.unit() / 4, 2, 4))
		);
		drawVerticalText(graphics, playerBackgroundModeTitle(mode), titleRect, titleColor, Font.BOLD, compactScreenLayout(layout) ? clampInt(layout.unit(), 8, 13) : clampInt(layout.unit() + 1, 10, 16));
		drawVerticalText(graphics, playerBackgroundModeSubtitle(mode, customWallpaperLoaded), subtitleRect, subtitleColor, Font.PLAIN, compactScreenLayout(layout) ? clampInt(layout.unit() - 1, 7, 10) : clampInt(layout.unit(), 9, 13));
	}

	static void drawPlayerBackgroundScaleButton(Graphics2D graphics, UiLayout layout, MediaOverlayWindowSnapshot window) {
		if (graphics == null || layout == null || window == null) {
			return;
		}
		if (!playerBackgroundModeHasScaleButton(window.playerBackgroundMode())) {
			return;
		}
		MediaScaleMode current = window.playerBackgroundScaleMode() != null ? window.playerBackgroundScaleMode() : MediaScaleMode.FILL;
		UiRect rect = playerBackgroundScaleButtonRect(layout, window.playerBackgroundMode());
		float strokeWidth = mediaChromeStrokeWidth(rect);
		fillRoundedRect(graphics, rect, clampInt(layout.unit() + 1, 10, 16), new Color(22, 20, 24, 40));
		Color iconColor = new Color(22, 20, 24, 228);
		UiRect iconRect = mediaChromeIconRect(rect, layout);
		switch (current) {
			case FILL -> drawMediaFillGlyph(graphics, iconRect, iconColor, strokeWidth);
			case STRETCH -> drawMediaStretchGlyph(graphics, iconRect, iconColor, strokeWidth);
			case FIT -> drawMediaFitGlyph(graphics, iconRect, iconColor, strokeWidth);
		}
	}

	static String playerBackgroundModeTitle(PlayerBackgroundMode mode) {
		return switch (mode) {
			case ARTWORK -> "ОТ КАРТИНКИ";
			case GALLERY -> "ИЗ ГАЛЕРЕИ";
			case BLACK -> "ЧЕРНЫЙ";
			case EMPTY -> "ПУСТОЙ";
		};
	}

	static String playerBackgroundModeSubtitle(PlayerBackgroundMode mode, boolean available) {
		return switch (mode) {
			case ARTWORK -> "Фильтрованный фон по текущему медиа";
			case GALLERY -> available ? "Открыть галерею и выбрать новый фон" : "Открыть галерею для выбора фона";
			case BLACK -> "Чистый темный фон";
			case EMPTY -> "Пустота под картами";
		};
	}

	static void drawWallpaperSnapshot(Graphics2D graphics, UiLayout layout, UiRect rect, WallpaperVisualSnapshot wallpaperSnapshot) {
		if (graphics == null || layout == null || rect == null || wallpaperSnapshot == null || wallpaperSnapshot.frame() == null) {
			return;
		}
		PlayerBackgroundMode backgroundMode = safeWallpaperBackgroundMode(wallpaperSnapshot.backgroundMode());
		if (backgroundMode == PlayerBackgroundMode.EMPTY) {
			clearRectToTransparent(graphics, rect);
		} else if (backgroundMode == PlayerBackgroundMode.BLACK) {
			drawBlackPlayerBackground(graphics, rect);
		} else if (backgroundMode == PlayerBackgroundMode.ARTWORK) {
			drawYoutubeMusicArtworkBackground(
					graphics,
					rect,
					wallpaperSnapshot.frame(),
					secondaryArtworkScaleMode(wallpaperSnapshot.scaleMode())
			);
			drawPlayerBackgroundShadeOverlay(graphics, rect, new Color(8, 10, 14, 94), new Color(12, 14, 18, 124));
		}
		drawScaledImageReplacingContent(graphics, wallpaperSnapshot.frame(), rect, wallpaperSnapshot.scaleMode());
	}

	static void drawOverlayModalActionButton(Graphics2D graphics, UiRect rect, String label, boolean destructive, UiLayout layout) {
		fillRoundedRect(
				graphics,
				rect,
				clampInt(layout.unit() * 2, 10, 18),
				destructive ? new Color(118, 30, 42, 228) : new Color(34, 44, 56, 222)
		);
		strokeRoundedRect(
				graphics,
				rect,
				clampInt(layout.unit() * 2, 10, 18),
				1.0F,
				destructive ? new Color(255, 170, 186, 86) : new Color(255, 255, 255, 38)
		);
		drawCenteredText(
				graphics,
				label,
				rect,
				new Color(248, 251, 255),
				Font.BOLD,
				clampInt(layout.unit() + (compactScreenLayout(layout) ? 0 : 1), 8, 14)
		);
	}

	static void drawYoutubeQueueWindowPlaceholder(Graphics2D graphics, UiLayout layout) {
		boolean compact = compactScreenLayout(layout);
		UiRect header = mediaQueueHeaderRect(layout);
		UiRect headerTitleRect = mediaQueueHeaderTitleRect(layout);
		UiRect headerSubtitleRect = mediaQueueHeaderSubtitleRect(layout);
		UiRect list = mediaQueueListRect(layout);
		UiRect footer = mediaQueueFooterRect(layout);
		UiRect scrollbarTrackRect = mediaQueueScrollbarTrackRect(layout);
		UiRect shuffleRect = mediaQueueShuffleRect(layout);
		UiRect closeRect = mediaQueueCloseRect(layout);
		UiRect repeatRect = mediaQueueRepeatRect(layout);
		drawCenteredTextFitted(graphics, "3 трека", headerTitleRect, new Color(248, 240, 244, 232), Font.BOLD, compact ? clampInt(layout.unit() + 2, 9, 16) : clampInt(layout.unit() + 4, 14, 22), clampInt(layout.unit(), 8, 14));
		drawCenteredTextFitted(graphics, "09:41", headerSubtitleRect, new Color(188, 198, 212, 176), Font.PLAIN, compact ? clampInt(layout.unit(), 8, 11) : clampInt(layout.unit() + 1, 10, 14), clampInt(layout.unit(), 8, 14));

		int visibleRows = Math.min(4, mediaQueueRenderableRows(layout));
		Shape previousClip = graphics.getClip();
		graphics.clipRect(list.x(), list.y(), list.width(), list.height());
		for (int rowIndex = 0; rowIndex < visibleRows; rowIndex++) {
			UiRect rowRect = mediaQueueRowRect(layout, rowIndex);
			UiRect removeRect = mediaQueueRemoveRect(rowRect, layout);
			UiRect cacheStatusRect = mediaQueueCacheStatusRect(rowRect, removeRect, layout);
			UiRect titleRect = mediaQueueTitleRect(rowRect, cacheStatusRect, layout);
			UiRect metaRect = mediaQueueMetaRect(rowRect, cacheStatusRect, layout);
			boolean current = rowIndex == 0;
			fillRoundedRect(graphics, rowRect, clampInt(layout.unit() * 2, 12, 18), current ? new Color(248, 246, 246, 232) : new Color(255, 255, 255, 8));
			fillRoundedRect(graphics, new UiRect(titleRect.x(), titleRect.y(), Math.max(18, titleRect.width() * 3 / 4), clampInt(layout.unit(), 10, 14)), clampInt(layout.unit(), 8, 12), current ? new Color(32, 24, 30, 54) : new Color(255, 255, 255, 18));
			fillRoundedRect(graphics, new UiRect(metaRect.x(), metaRect.y() + Math.max(1, layout.unit() / 6), Math.max(16, metaRect.width() / 2), clampInt(layout.unit(), 10, 14)), clampInt(layout.unit(), 8, 12), current ? new Color(32, 24, 30, 42) : new Color(255, 255, 255, 14));
			drawQueueCacheStatusPlaceholder(graphics, cacheStatusRect, rowIndex == 0 ? 0.72F : 0.36F, current, true);
			drawSmallMediaButtonBase(graphics, removeRect, MediaButtonSegment.SINGLE, false, mediaChromeStrokeWidth(removeRect));
		}
		graphics.setClip(previousClip);
		fillRoundedRect(graphics, scrollbarTrackRect, clampInt(layout.unit(), 6, 10), new Color(255, 255, 255, 12));
		fillRoundedRect(graphics, mediaQueueScrollbarThumbRect(layout, 0, 1, 1), clampInt(layout.unit(), 6, 10), new Color(255, 255, 255, 42));

		drawYoutubeMusicShuffleButton(graphics, shuffleRect, false, layout, MediaButtonSegment.SINGLE);
		drawMediaQueueDismissButton(graphics, closeRect, layout);
		drawMediaRepeatButton(graphics, repeatRect, MediaRepeatMode.OFF, layout, MediaButtonSegment.SINGLE);
	}

	static void drawQueueScrollbar(Graphics2D graphics, UiRect trackRect, int scroll, int visibleRows, int totalRows, UiLayout layout) {
		drawQueueScrollbar(graphics, trackRect, (double) scroll, visibleRows, totalRows, layout);
	}

	static void drawGalleryScrollbar(Graphics2D graphics, UiRect trackRect, int scroll, int visibleRows, int totalRows, UiLayout layout) {
		drawGalleryScrollbar(graphics, trackRect, (double) scroll, visibleRows, totalRows, layout);
	}

	static void drawQueueScrollbar(Graphics2D graphics, UiRect trackRect, double scroll, int visibleRows, int totalRows, UiLayout layout) {
		drawScrollbar(graphics, trackRect, scroll, visibleRows, totalRows, layout);
	}

	static void drawGalleryScrollbar(Graphics2D graphics, UiRect trackRect, double scroll, int visibleRows, int totalRows, UiLayout layout) {
		drawScrollbar(graphics, trackRect, scroll, visibleRows, totalRows, layout);
	}

	static void drawQueueCacheStatusIcon(Graphics2D graphics, UiRect rect, YoutubeQueueItemSnapshot item, UiLayout layout) {
		if (graphics == null || rect == null || item == null || layout == null) {
			return;
		}
		if (item.cacheComplete()) {
			float strokeWidth = mediaChromeStrokeWidth(rect);
			Color iconColor = drawSmallMediaButtonBase(graphics, rect, MediaButtonSegment.SINGLE, true, strokeWidth);
			drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), PlayerUiIcon.CHECK, iconColor);
			return;
		}
		drawQueueCacheStatusPlaceholder(graphics, rect, item.cacheFraction(), item.current(), item.cacheActive());
	}

	static void drawQueueCacheStatusPlaceholder(Graphics2D graphics, UiRect rect, float fraction, boolean highlighted, boolean active) {
		if (graphics == null || rect == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		float clampedFraction = Math.max(0.0F, Math.min(0.99F, fraction));
		Color trackColor = highlighted ? new Color(36, 28, 32, 58) : new Color(255, 255, 255, 44);
		Color progressColor = highlighted
				? (active ? new Color(36, 28, 32, 214) : new Color(36, 28, 32, 164))
				: (active ? new Color(248, 240, 244, 228) : new Color(230, 224, 228, 164));
		Stroke previousStroke = graphics.getStroke();
		graphics.setStroke(new BasicStroke(Math.max(1.6F, Math.min(rect.width(), rect.height()) / 7.0F), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.setColor(trackColor);
		graphics.drawArc(rect.x(), rect.y(), rect.width() - 1, rect.height() - 1, 0, 360);
		int startAngle = active ? (int) (-(System.currentTimeMillis() / 12L) % 360L) : 90;
		int sweepAngle = Math.max(22, Math.round(360.0F * clampedFraction));
		graphics.setColor(progressColor);
		graphics.drawArc(rect.x(), rect.y(), rect.width() - 1, rect.height() - 1, startAngle, sweepAngle);
		graphics.setStroke(previousStroke);
	}

	static int scrollbarWidth(UiLayout layout) {
		return clampInt(layout.unit() / 2, 6, 10);
	}

	static int scrollbarGap(UiLayout layout) {
		return clampInt(layout.unit() / 3, 3, 6);
	}

	static int scrollbarGutterWidth(UiLayout layout) {
		return scrollbarWidth(layout) + scrollbarGap(layout);
	}

	static UiRect scrollContentRect(UiRect viewport, UiLayout layout) {
		return new UiRect(
				viewport.x(),
				viewport.y(),
				Math.max(18, viewport.width() - scrollbarGutterWidth(layout)),
				viewport.height()
		);
	}

	static UiRect scrollTrackRect(UiRect viewport, UiLayout layout) {
		int width = scrollbarWidth(layout);
		return new UiRect(
				viewport.right() - width,
				viewport.y(),
				width,
				viewport.height()
		);
	}

	static void drawScrollbar(Graphics2D graphics, UiRect trackRect, int scroll, int visibleRows, int totalRows, UiLayout layout, UiRect thumbRect) {
		if (graphics == null || trackRect.width() <= 0 || trackRect.height() <= 0 || !scrollbarVisible(visibleRows, totalRows)) {
			return;
		}
		fillRoundedRect(graphics, trackRect, clampInt(layout.unit(), 6, 10), new Color(255, 255, 255, 12));
		strokeRoundedRect(graphics, trackRect, clampInt(layout.unit(), 6, 10), 1.0F, new Color(255, 255, 255, 20));
		fillRoundedRect(graphics, thumbRect, clampInt(layout.unit(), 6, 10), new Color(255, 255, 255, 72));
	}

	static void drawScrollbar(Graphics2D graphics, UiRect trackRect, double scroll, int visibleRows, int totalRows, UiLayout layout) {
		drawScrollbar(graphics, trackRect, clampInt((int) Math.round(scroll), 0, Math.max(0, totalRows - Math.max(1, visibleRows))), visibleRows, totalRows, layout, scrollbarThumbRect(trackRect, scroll, visibleRows, totalRows));
	}

	static boolean scrollbarVisible(int visibleRows, int totalRows) {
		return totalRows > visibleRows && visibleRows > 0;
	}

	static int scrollValueForTrack(UiRect trackRect, int visibleRows, int totalRows, int pointerY) {
		if (trackRect == null || !scrollbarVisible(visibleRows, totalRows)) {
			return 0;
		}
		int maxScroll = Math.max(0, totalRows - visibleRows);
		if (maxScroll <= 0) {
			return 0;
		}
		double fraction = clampDouble((pointerY - trackRect.y()) / (double) Math.max(1, trackRect.height() - 1), 0.0D, 1.0D);
		return clampInt((int) Math.round(fraction * maxScroll), 0, maxScroll);
	}

	static UiRect offsetRect(UiRect rect, int dx, int dy) {
		if (rect == null) {
			return new UiRect(0, 0, 0, 0);
		}
		return new UiRect(rect.x() + dx, rect.y() + dy, rect.width(), rect.height());
	}

	static UiRect scrollbarThumbRect(UiRect track, double scroll, int visibleRows, int totalRows) {
		if (track == null || track.height() <= 0 || totalRows <= 0) {
			return track != null ? track : new UiRect(0, 0, 0, 0);
		}
		int safeVisible = Math.max(1, Math.min(visibleRows, totalRows));
		int maxScroll = Math.max(0, totalRows - safeVisible);
		int thumbHeight = Math.max(track.width(), Math.round(track.height() * (safeVisible / (float) totalRows)));
		int travel = Math.max(0, track.height() - thumbHeight);
		double clampedScroll = clampDouble(scroll, 0.0D, Math.max(0, maxScroll));
		int thumbY = maxScroll <= 0 ? track.y() : track.y() + (int) Math.round(travel * (clampedScroll / maxScroll));
		return new UiRect(track.x(), thumbY, track.width(), thumbHeight);
	}

	static void drawScaledImage(Graphics2D graphics, BufferedImage image, UiRect rect, MediaScaleMode scaleMode) {
		drawScaledImageWithComposite(graphics, image, rect, scaleMode, null);
	}

	static void drawTransparentBackdropImage(Graphics2D graphics, BufferedImage image, UiRect rect, MediaScaleMode scaleMode) {
		clearRectToTransparent(graphics, rect);
		drawScaledImage(graphics, image, rect, scaleMode);
	}

	static void fillRectPreservingTransparency(Graphics2D graphics, UiRect rect, Paint paint) {
		if (graphics == null || rect == null || rect.width() <= 0 || rect.height() <= 0 || paint == null) {
			return;
		}
		Paint previousPaint = graphics.getPaint();
		Composite previousComposite = graphics.getComposite();
		try {
			graphics.setPaint(paint);
			graphics.setComposite(PRESERVE_TRANSPARENCY_COMPOSITE);
			graphics.fillRect(rect.x(), rect.y(), rect.width(), rect.height());
		} finally {
			graphics.setComposite(previousComposite);
			graphics.setPaint(previousPaint);
		}
	}

	static void drawScaledImageReplacingContent(Graphics2D graphics, BufferedImage image, UiRect rect, MediaScaleMode scaleMode) {
		drawScaledImageWithComposite(graphics, image, rect, scaleMode, REPLACING_IMAGE_COMPOSITE);
	}

	static void clearRectToTransparent(Graphics2D graphics, UiRect rect) {
		if (graphics == null || rect == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		Composite previousComposite = graphics.getComposite();
		Color previousColor = graphics.getColor();
		try {
			graphics.setComposite(REPLACING_IMAGE_COMPOSITE);
			graphics.setColor(new Color(0, 0, 0, 0));
			graphics.fillRect(rect.x(), rect.y(), rect.width(), rect.height());
		} finally {
			graphics.setComposite(previousComposite);
			graphics.setColor(previousColor);
		}
	}

	static void drawScaledImageWithComposite(Graphics2D graphics, BufferedImage image, UiRect rect, MediaScaleMode scaleMode, Composite composite) {
		if (image == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		Shape previousClip = graphics.getClip();
		Composite previousComposite = graphics.getComposite();
		try {
			if (previousClip == null) {
				graphics.setClip(rect.x(), rect.y(), rect.width(), rect.height());
			} else {
				graphics.clipRect(rect.x(), rect.y(), rect.width(), rect.height());
			}
			if (composite != null) {
				graphics.setComposite(composite);
			}
			if (scaleMode == MediaScaleMode.STRETCH) {
				graphics.drawImage(image, rect.x(), rect.y(), rect.width(), rect.height(), null);
				return;
			}

			double scale = scaleMode == MediaScaleMode.FILL
					? Math.max(rect.width() / (double) image.getWidth(), rect.height() / (double) image.getHeight())
					: Math.min(rect.width() / (double) image.getWidth(), rect.height() / (double) image.getHeight());
			int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
			int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
			int drawX = rect.x() + (rect.width() - drawWidth) / 2;
			int drawY = rect.y() + (rect.height() - drawHeight) / 2;
			graphics.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
		} finally {
			graphics.setComposite(previousComposite);
			graphics.setClip(previousClip);
		}
	}

	static void drawRoundedScaledImage(Graphics2D graphics, BufferedImage image, UiRect rect, MediaScaleMode scaleMode, int arc) {
		drawRoundedScaledImage(graphics, image, rect, scaleMode, arc, null);
	}

	static void drawRoundedScaledImageReplacingContent(Graphics2D graphics, BufferedImage image, UiRect rect, MediaScaleMode scaleMode, int arc) {
		drawRoundedScaledImage(graphics, image, rect, scaleMode, arc, REPLACING_IMAGE_COMPOSITE);
	}

	static void drawRoundedScaledImage(Graphics2D graphics, BufferedImage image, UiRect rect, MediaScaleMode scaleMode, int arc, Composite composite) {
		if (graphics == null || image == null || rect == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		Shape previousClip = graphics.getClip();
		Composite previousComposite = graphics.getComposite();
		try {
			RoundRectangle2D.Float clip = new RoundRectangle2D.Float(rect.x(), rect.y(), rect.width(), rect.height(), arc, arc);
			if (previousClip != null) {
				Area combinedClip = new Area(previousClip);
				combinedClip.intersect(new Area(clip));
				graphics.setClip(combinedClip);
			} else {
				graphics.setClip(clip);
			}
			if (composite != null) {
				graphics.setComposite(composite);
			}
			drawScaledImageWithComposite(graphics, image, rect, scaleMode, composite);
		} finally {
			graphics.setComposite(previousComposite);
			graphics.setClip(previousClip);
		}
	}

	static void drawYoutubeMusicArtworkBackground(Graphics2D graphics, UiRect rect, BufferedImage image, MediaScaleMode scaleMode) {
		if (graphics == null || rect == null || image == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		Composite previousComposite = graphics.getComposite();
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.26F));
		drawScaledImage(graphics, image, rect, scaleMode);
		graphics.setComposite(previousComposite);
	}

	static void drawPlayerBackgroundSurface(
			Graphics2D graphics,
			UiLayout layout,
			UiRect canvasRect,
			BufferedImage mediaBackgroundFrame,
			BufferedImage playerBackgroundFrame,
			MediaVisualSnapshot state,
			PlayerBackgroundMode playerBackgroundMode,
			boolean galleryBackgroundSelected,
			boolean galleryBackgroundAvailable,
			boolean artworkBackgroundVisible,
			boolean darkPlayerSurface,
			boolean musicPlayerLayout,
			boolean youtubeHomePrompt,
			boolean waitingForLink,
			boolean preserveExistingBackground
	) {
		if (graphics == null || layout == null || canvasRect == null) {
			return;
		}
		if (preserveExistingBackground) {
			return;
		}
		if (playerBackgroundMode == PlayerBackgroundMode.EMPTY) {
			clearRectToTransparent(graphics, canvasRect);
			return;
		}
		if (waitingForLink) {
			if (galleryBackgroundSelected && galleryBackgroundAvailable && playerBackgroundFrame != null) {
				drawCleanPlayerBackground(graphics, canvasRect, playerBackgroundFrame, state != null ? state.playerBackgroundScaleMode() : MediaScaleMode.FILL);
				return;
			}
			if (playerBackgroundMode == PlayerBackgroundMode.BLACK || darkPlayerSurface) {
				drawBlackPlayerBackground(graphics, canvasRect);
				return;
			}
			clearRectToTransparent(graphics, canvasRect);
			return;
		}
		if (musicPlayerLayout && !youtubeHomePrompt) {
			if (artworkBackgroundVisible && mediaBackgroundFrame != null) {
				drawArtworkPlayerBackground(graphics, canvasRect, mediaBackgroundFrame, state != null ? state.playerBackgroundScaleMode() : MediaScaleMode.FILL, true);
				return;
			}
			if (galleryBackgroundSelected && galleryBackgroundAvailable && playerBackgroundFrame != null) {
				drawCleanPlayerBackground(graphics, canvasRect, playerBackgroundFrame, state != null ? state.playerBackgroundScaleMode() : MediaScaleMode.FILL);
				return;
			}
			drawBlackPlayerBackground(graphics, canvasRect);
			return;
		}
		if (artworkBackgroundVisible && mediaBackgroundFrame != null) {
			drawArtworkPlayerBackground(graphics, canvasRect, mediaBackgroundFrame, state != null ? state.playerBackgroundScaleMode() : MediaScaleMode.FILL, false);
			return;
		}
		if (galleryBackgroundSelected && galleryBackgroundAvailable && playerBackgroundFrame != null) {
			drawCleanPlayerBackground(graphics, canvasRect, playerBackgroundFrame, state != null ? state.playerBackgroundScaleMode() : MediaScaleMode.FILL);
			return;
		}
		if (darkPlayerSurface || playerBackgroundMode == PlayerBackgroundMode.BLACK) {
			drawBlackPlayerBackground(graphics, canvasRect);
			return;
		}
		clearRectToTransparent(graphics, canvasRect);
	}

	static void drawBlackPlayerBackground(Graphics2D graphics, UiRect rect) {
		if (graphics == null || rect == null) {
			return;
		}
		graphics.setColor(Color.BLACK);
		graphics.fillRect(rect.x(), rect.y(), rect.width(), rect.height());
	}

	static void drawArtworkPlayerBackground(Graphics2D graphics, UiRect rect, BufferedImage frame, MediaScaleMode scaleMode, boolean musicLayout) {
		if (graphics == null || rect == null || frame == null) {
			return;
		}
		drawYoutubeMusicArtworkBackground(
				graphics,
				rect,
				frame,
				musicLayout ? scaleMode : secondaryArtworkScaleMode(scaleMode)
		);
		drawPlayerBackgroundShadeOverlay(
				graphics,
				rect,
				musicLayout ? new Color(6, 8, 10, 122) : new Color(8, 10, 14, 94),
				musicLayout ? new Color(10, 10, 14, 164) : new Color(12, 14, 18, 124)
		);
	}

	static void drawCleanPlayerBackground(Graphics2D graphics, UiRect rect, BufferedImage frame, MediaScaleMode scaleMode) {
		if (graphics == null || rect == null || frame == null) {
			return;
		}
		drawTransparentBackdropImage(graphics, frame, rect, scaleMode != null ? scaleMode : MediaScaleMode.FIT);
	}

	static void drawPlayerBackgroundShadeOverlay(Graphics2D graphics, UiRect rect, Color start, Color end) {
		if (graphics == null || rect == null) {
			return;
		}
		fillRectPreservingTransparency(
				graphics,
				rect,
				new GradientPaint(
						rect.x(),
						rect.y(),
						start != null ? start : new Color(8, 10, 14, 84),
						rect.right(),
						rect.bottom(),
						end != null ? end : new Color(12, 14, 18, 120)
				)
		);
	}

	static MediaScaleMode secondaryArtworkScaleMode(MediaScaleMode scaleMode) {
		MediaScaleMode normalized = scaleMode != null ? scaleMode : MediaScaleMode.FIT;
		if (normalized == MediaScaleMode.STRETCH) {
			return MediaScaleMode.FILL;
		}
		return normalized == MediaScaleMode.FILL ? MediaScaleMode.FIT : MediaScaleMode.FILL;
	}

	static void drawYoutubeMusicArtworkCard(Graphics2D graphics, UiLayout layout, BufferedImage image, MediaScaleMode scaleMode) {
		UiRect artworkRect = mediaYoutubeMusicArtworkRect(layout);
		int arc = clampInt(layout.unit() * 2, 12, 28);
		drawRoundedScaledImage(
				graphics,
				image,
				artworkRect,
				scaleMode == MediaScaleMode.STRETCH ? MediaScaleMode.FILL : scaleMode,
				arc
		);
	}

	static void drawYoutubeMusicArtworkCardReplacingContent(Graphics2D graphics, UiLayout layout, BufferedImage image, MediaScaleMode scaleMode) {
		UiRect artworkRect = mediaYoutubeMusicArtworkRect(layout);
		int arc = clampInt(layout.unit() * 2, 12, 28);
		drawRoundedScaledImageReplacingContent(
				graphics,
				image,
				artworkRect,
				scaleMode == MediaScaleMode.STRETCH ? MediaScaleMode.FILL : scaleMode,
				arc
		);
	}

	static void drawYoutubeMusicArtworkLoadingPlaceholder(Graphics2D graphics, UiLayout layout) {
		if (graphics == null || layout == null) {
			return;
		}
		UiRect artworkRect = mediaYoutubeMusicArtworkRect(layout);
		int arc = clampInt(layout.unit() * 2, 12, 28);
		fillRoundedRect(graphics, artworkRect, arc, new Color(124, 130, 138, 212));
		strokeRoundedRect(graphics, artworkRect, arc, 1.0F, new Color(255, 255, 255, 26));
	}

	static UiRect mediaYoutubeMusicInfoRect(UiLayout layout) {
		UiRect artworkRect = mediaYoutubeMusicArtworkRect(layout);
		if (youtubeMusicMicroLayout(layout)) {
			UiRect canvas = mediaCanvasRect(layout);
			int gap = mediaYoutubeMusicInfoGap(layout);
			return new UiRect(
					artworkRect.right() + gap,
					artworkRect.y(),
					Math.max(32, canvas.right() - artworkRect.right() - gap),
					mediaYoutubeMusicInfoHeight(layout)
			);
		}
		int gap = mediaYoutubeMusicInfoGap(layout);
		if (youtubeMusicLandscapeLayout(layout)) {
			UiRect closeRect = mediaCloseRect(layout);
			int left = closeRect.right() + gap;
			int right = artworkRect.x() - gap;
			return new UiRect(
					left,
					artworkRect.y(),
					Math.max(36, right - left),
					mediaYoutubeMusicInfoHeight(layout)
			);
		}
		return new UiRect(
				artworkRect.x(),
				artworkRect.bottom() + gap,
				artworkRect.width(),
				mediaYoutubeMusicInfoHeight(layout)
		);
	}

	static UiRect mediaYoutubeMusicTitleRect(UiLayout layout) {
		UiRect infoRect = mediaYoutubeMusicInfoRect(layout);
		int titleHeight = Math.max(clampInt(layout.unit() * 2, 14, 28), (int) Math.round(infoRect.height() * 0.66D));
		if (ultraCompactScreenLayout(layout)) {
			titleHeight = Math.min(titleHeight, Math.max(10, infoRect.height() - 10));
		}
		return new UiRect(infoRect.x(), infoRect.y(), infoRect.width(), Math.min(infoRect.height(), titleHeight));
	}

	static UiRect mediaYoutubeMusicArtistRect(UiLayout layout) {
		UiRect infoRect = mediaYoutubeMusicInfoRect(layout);
		UiRect titleRect = mediaYoutubeMusicTitleRect(layout);
		int gap = clampInt(layout.unit() / 5, 2, 4);
		return new UiRect(
				infoRect.x(),
				Math.min(infoRect.bottom(), titleRect.bottom() + gap),
				infoRect.width(),
				Math.max(0, infoRect.bottom() - titleRect.bottom() - gap)
		);
	}

	static int mediaYoutubeMusicInfoHeight(UiLayout layout) {
		if (youtubeMusicMicroLayout(layout)) {
			return clampInt(layout.unit() * 6 + 2, 26, 32);
		}
		return ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 6 + 2, 24, 28)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 4, 24, 40)
				: clampInt(layout.unit() * 5, 34, 60);
	}

	static int mediaYoutubeMusicTrackHeight(UiLayout layout) {
		return mediaTimelineTrackHeight(layout);
	}

	static int mediaYoutubeMusicTimeRowHeight(UiLayout layout) {
		if (youtubeMusicMicroLayout(layout)) {
			return 10;
		}
		return ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 2, 10, 14)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 2 + 1, 14, 20)
				: clampInt(layout.unit() * 2 + 2, 18, 26);
	}

	static int mediaYoutubeMusicControlsRowHeight(UiLayout layout) {
		if (youtubeMusicMicroLayout(layout)) {
			return 18;
		}
		return ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 4, 18, 24)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 4 + 2, 26, 36)
				: clampInt(layout.unit() * 5, 38, 54);
	}

	static int mediaYoutubeMusicActionsRowHeight(UiLayout layout) {
		if (youtubeMusicMicroLayout(layout)) {
			return 14;
		}
		return ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 2 + 2, 12, 16)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 2 + 2, 18, 26)
				: clampInt(layout.unit() * 2 + 4, 24, 34);
	}

	static UiRect mediaYoutubeMusicCurrentTimeRect(UiLayout layout) {
		UiRect timelineRect = mediaTimelineRect(layout, ScreenViewMode.YOUTUBE_MUSIC);
		int top = timelineRect.bottom() + mediaYoutubeMusicTimeGap(layout);
		return new UiRect(timelineRect.x(), top, Math.max(16, timelineRect.width() / 2), mediaYoutubeMusicTimeRowHeight(layout));
	}

	static UiRect mediaYoutubeMusicTotalTimeRect(UiLayout layout) {
		UiRect timelineRect = mediaTimelineRect(layout, ScreenViewMode.YOUTUBE_MUSIC);
		UiRect leftRect = mediaYoutubeMusicCurrentTimeRect(layout);
		return new UiRect(leftRect.right(), leftRect.y(), Math.max(16, timelineRect.right() - leftRect.right()), leftRect.height());
	}

	static UiRect mediaYoutubeMusicControlsRowRect(UiLayout layout) {
		UiRect timelineRect = mediaTimelineRect(layout, ScreenViewMode.YOUTUBE_MUSIC);
		UiRect totalTimeRect = mediaYoutubeMusicTotalTimeRect(layout);
		int top = totalTimeRect.bottom() + mediaYoutubeMusicControlsGap(layout);
		return new UiRect(timelineRect.x(), top, timelineRect.width(), mediaYoutubeMusicControlsRowHeight(layout));
	}

	static UiRect mediaYoutubeMusicActionsRowRect(UiLayout layout) {
		if (youtubeMusicLandscapeLayout(layout)) {
			UiRect artworkRect = mediaYoutubeMusicArtworkRect(layout);
			int height = mediaYoutubeMusicActionsRowHeight(layout);
			UiRect canvas = mediaCanvasRect(layout);
			return new UiRect(canvas.x(), artworkRect.bottom() + mediaYoutubeMusicActionsGap(layout), canvas.width(), height);
		}
		UiRect controlsRect = mediaYoutubeMusicControlsRowRect(layout);
		int top = controlsRect.bottom() + mediaYoutubeMusicActionsGap(layout);
		return new UiRect(controlsRect.x(), top, controlsRect.width(), mediaYoutubeMusicActionsRowHeight(layout));
	}

	static void drawYoutubeMusicTrackInfo(Graphics2D graphics, UiLayout layout, MediaVisualSnapshot state) {
		if (graphics == null || layout == null || state == null) {
			return;
		}
		String title = state.mediaTitle();
		if ((title == null || title.isBlank()) && state.loading()) {
			return;
		}
		if (title == null || title.isBlank()) {
			title = "Track";
		}
		drawWrappedText(
				graphics,
				title,
				mediaYoutubeMusicTitleRect(layout),
				new Color(248, 251, 255, 242),
				Font.BOLD,
				ultraCompactScreenLayout(layout)
						? clampInt(layout.unit() + 1, 9, 12)
						: compactScreenLayout(layout)
						? clampInt(layout.unit() + 2, 11, 15)
						: clampInt(layout.unit() + 3, 14, 20),
				2
		);
		if (state.mediaSubtitle() != null && !state.mediaSubtitle().isBlank()) {
			drawWrappedText(
					graphics,
					state.mediaSubtitle(),
					mediaYoutubeMusicArtistRect(layout),
					new Color(214, 221, 230, 204),
					Font.PLAIN,
					ultraCompactScreenLayout(layout)
							? clampInt(layout.unit(), 8, 10)
							: compactScreenLayout(layout)
							? clampInt(layout.unit() + 1, 9, 12)
							: clampInt(layout.unit() + 1, 11, 15),
					1
			);
		}
	}

	static void drawCloseGlyph(Graphics2D graphics, UiRect rect, Color color) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.CLOSE, color);
	}

	static void drawPlayGlyph(Graphics2D graphics, UiRect rect, Color color) {
		int left = rect.x() + Math.max(1, rect.width() / 5);
		int right = rect.right() - Math.max(1, rect.width() / 6);
		int top = rect.y() + Math.max(1, rect.height() / 6);
		int bottom = rect.bottom() - Math.max(1, rect.height() / 6);
		graphics.setColor(color);
		graphics.fillPolygon(
				new int[]{left, left, right},
				new int[]{top, bottom, rect.y() + rect.height() / 2},
				3
		);
	}

	static void drawPauseGlyph(Graphics2D graphics, UiRect rect, Color color) {
		int barWidth = Math.max(2, rect.width() / 5);
		int gap = Math.max(2, rect.width() / 7);
		int leftX = rect.x() + Math.max(1, (rect.width() - barWidth * 2 - gap) / 2);
		int topY = rect.y() + Math.max(1, rect.height() / 6);
		int barHeight = Math.max(4, rect.height() - Math.max(2, rect.height() / 3));
		graphics.setColor(color);
		graphics.fillRoundRect(leftX, topY, barWidth, barHeight, barWidth, barWidth);
		graphics.fillRoundRect(leftX + barWidth + gap, topY, barWidth, barHeight, barWidth, barWidth);
	}

	static void drawSeekGlyph(Graphics2D graphics, UiRect rect, Color color, boolean backward) {
		if (graphics == null || rect == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		int glyphWidth = Math.max(3, Math.min(rect.width(), (int) Math.round(rect.width() * 0.78D)));
		int glyphHeight = Math.max(3, Math.min(rect.height(), (int) Math.round(rect.height() * 0.72D)));
		int barWidth = Math.max(1, glyphWidth / 7);
		int gap = Math.max(1, glyphWidth / 10);
		int triangleWidth = Math.max(1, glyphWidth - barWidth - gap);
		int glyphX = rect.x() + (rect.width() - glyphWidth) / 2;
		int glyphY = rect.y() + (rect.height() - glyphHeight) / 2;
		UiRect barRect;
		UiRect triangleRect;
		if (backward) {
			barRect = new UiRect(glyphX, glyphY, barWidth, glyphHeight);
			triangleRect = new UiRect(barRect.right() + gap, glyphY, triangleWidth, glyphHeight);
		} else {
			triangleRect = new UiRect(glyphX, glyphY, triangleWidth, glyphHeight);
			barRect = new UiRect(triangleRect.right() + gap, glyphY, barWidth, glyphHeight);
		}
		graphics.setColor(color);
		graphics.fillRoundRect(barRect.x(), barRect.y(), barRect.width(), barRect.height(), barRect.width(), barRect.width());
		if (backward) {
			graphics.fillPolygon(
					new int[]{triangleRect.right(), triangleRect.right(), triangleRect.x()},
					new int[]{triangleRect.y(), triangleRect.bottom(), triangleRect.y() + triangleRect.height() / 2},
					3
			);
		} else {
			graphics.fillPolygon(
					new int[]{triangleRect.x(), triangleRect.x(), triangleRect.right()},
					new int[]{triangleRect.y(), triangleRect.bottom(), triangleRect.y() + triangleRect.height() / 2},
					3
			);
		}
	}

	static void drawLoadingSpinner(Graphics2D graphics, UiRect rect, Color color, float strokeWidth) {
		Stroke previous = graphics.getStroke();
		graphics.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		long tick = System.currentTimeMillis() / 12L;
		int startAngle = (int) (-tick % 360L);
		graphics.setColor(color);
		graphics.drawArc(rect.x(), rect.y(), rect.width() - 1, rect.height() - 1, startAngle, 280);
		graphics.setStroke(previous);
	}

	static void drawMediaFitGlyph(Graphics2D graphics, UiRect rect, Color color, float strokeWidth) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.FIT, color);
	}

	static void drawMediaFillGlyph(Graphics2D graphics, UiRect rect, Color color, float strokeWidth) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.FILL, color);
	}

	static void drawMediaStretchGlyph(Graphics2D graphics, UiRect rect, Color color, float strokeWidth) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.STRETCH, color);
	}

	static void drawSearchGlyph(Graphics2D graphics, UiRect rect, Color color) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.SEARCH, color);
	}

	static void drawShuffleGlyph(Graphics2D graphics, UiRect rect, Color color) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.SHUFFLE, color);
	}

	static void drawRepeatGlyph(Graphics2D graphics, UiRect rect, Color color) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.REPEAT, color);
	}

	static void drawRepeatOneGlyph(Graphics2D graphics, UiRect rect, Color color) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.REPEAT_ONE, color);
	}

	static void drawDropdownGlyph(Graphics2D graphics, UiRect rect, Color color) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.DROPDOWN, color);
	}

	static void drawRotatedPlayerUiIcon(Graphics2D graphics, UiRect rect, PlayerUiIcon icon, Color tint, double rotationRadians) {
		if (graphics == null || rect == null || rect.width() <= 0 || rect.height() <= 0 || icon == null || tint == null) {
			return;
		}
		BufferedImage tinted = tintedPlayerUiIcon(icon, tint);
		Graphics2D rotated = (Graphics2D) graphics.create();
		try {
			double centerX = rect.x() + rect.width() / 2.0D;
			double centerY = rect.y() + rect.height() / 2.0D;
			rotated.rotate(rotationRadians, centerX, centerY);
			rotated.drawImage(tinted, rect.x(), rect.y(), rect.width(), rect.height(), null);
		} finally {
			rotated.dispose();
		}
	}

	static void drawOverlayChevronButton(Graphics2D graphics, UiRect rect, UiLayout layout, double rotationRadians, boolean enabled) {
		Color color = drawSmallMediaButtonBase(graphics, rect, MediaButtonSegment.SINGLE, false, mediaChromeStrokeWidth(rect));
		Color iconColor = enabled ? color : new Color(color.getRed(), color.getGreen(), color.getBlue(), 96);
		drawRotatedPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), PlayerUiIcon.DROPDOWN, iconColor, rotationRadians);
	}

	static void drawMenuGlyph(Graphics2D graphics, UiRect rect, Color color) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.MENU, color);
	}

	static void drawQueueGlyph(Graphics2D graphics, UiRect rect, Color color, float strokeWidth) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.QUEUE, color);
	}

	static void drawTrashGlyph(Graphics2D graphics, UiRect rect, Color color, float strokeWidth) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.TRASH, color);
	}

	static void drawDownloadGlyph(Graphics2D graphics, UiRect rect, Color color, float strokeWidth) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.DOWNLOAD, color);
	}

	static void drawCheckGlyph(Graphics2D graphics, UiRect rect, Color color, float strokeWidth) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.CHECK, color);
	}

	static void drawWallpaperGlyph(Graphics2D graphics, UiRect rect, Color color, float strokeWidth) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.WALLPAPER, color);
	}

	static void drawSberSourceGlyph(Graphics2D graphics, UiRect rect, String sourceLabel, Color color) {
		if (sourceLabel != null && sourceLabel.equalsIgnoreCase("DRONE")) {
			drawPlayerUiIcon(graphics, rect, PlayerUiIcon.DRONE, color);
			return;
		}
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.CAMERA, color);
	}

	static void drawSberStatusGlyph(Graphics2D graphics, UiRect rect, boolean online, Color color) {
		drawPlayerUiIcon(graphics, rect, online ? PlayerUiIcon.SIGNAL : PlayerUiIcon.OFFLINE, color);
	}

	static void drawSberLocationGlyph(Graphics2D graphics, UiRect rect, Color color) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.LOCATION, color);
	}

	static void drawSberOutlinedIconButton(
			Graphics2D graphics,
			UiRect rect,
			UiLayout layout,
			PlayerUiIcon icon,
			MediaButtonSegment segment,
			Color color
	) {
		if (graphics == null || rect == null || layout == null || icon == null || color == null) {
			return;
		}
		float strokeWidth = mediaChromeStrokeWidth(rect);
		strokeShape(
				graphics,
				mediaButtonShape(rect, segment),
				Math.max(0.9F, strokeWidth * 0.55F),
				new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(72, color.getAlpha()))
		);
		drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), icon, color);
	}

	static void drawAppIcon(Graphics2D graphics, MonitorApp app, UiRect rect, int padding) {
		if (app == null) {
			return;
		}
		BufferedImage image = loadAppIcon(app);
		if (image == null) {
			return;
		}
		drawContainedImage(graphics, image, rect, padding);
	}

	static void drawContainedImage(Graphics2D graphics, BufferedImage image, UiRect rect, int padding) {
		if (image == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		int availableWidth = Math.max(1, rect.width() - padding * 2);
		int availableHeight = Math.max(1, rect.height() - padding * 2);
		double scale = Math.min(availableWidth / (double) image.getWidth(), availableHeight / (double) image.getHeight());
		int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
		int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
		int drawX = rect.x() + (rect.width() - drawWidth) / 2;
		int drawY = rect.y() + (rect.height() - drawHeight) / 2;
		graphics.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
	}

	static void drawContainedImageNearest(Graphics2D graphics, BufferedImage image, UiRect rect, int padding) {
		if (graphics == null || image == null || rect == null) {
			return;
		}
		Object previousInterpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			drawContainedImage(graphics, image, rect, padding);
		} finally {
			graphics.setRenderingHint(
					RenderingHints.KEY_INTERPOLATION,
					previousInterpolation != null ? previousInterpolation : RenderingHints.VALUE_INTERPOLATION_BILINEAR
			);
		}
	}

	static BufferedImage loadAppIcon(MonitorApp app) {
		if (app == null) {
			return null;
		}
		return APP_ICON_CACHE.computeIfAbsent(
				app.id(),
				ignored -> loadPngImage(app.iconResourcePath(), fallbackAppIcon(app))
		);
	}

	static BufferedImage fallbackAppIcon(MonitorApp app) {
		BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		configureUiGraphics(graphics);
		graphics.setPaint(new GradientPaint(
				0.0F,
				0.0F,
				new Color(app.accentStartRgb()),
				128.0F,
				128.0F,
				new Color(app.accentEndRgb())
		));
		graphics.fillRoundRect(0, 0, 128, 128, 28, 28);
		graphics.setColor(Color.WHITE);
		graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
		var metrics = graphics.getFontMetrics();
		String text = app.title().length() > 3 ? app.title().substring(0, 3) : app.title();
		int textX = (128 - metrics.stringWidth(text)) / 2;
		int textY = (128 - metrics.getHeight()) / 2 + metrics.getAscent();
		graphics.drawString(text, textX, textY);
		graphics.dispose();
		return image;
	}

	static void drawMediaPlaceholder(Graphics2D graphics, UiRect rect) {
		int pad = Math.max(8, rect.width() / 14);
		UiRect horizon = new UiRect(rect.x() + pad, rect.bottom() - pad - Math.max(14, rect.height() / 6), rect.width() - pad * 2, Math.max(14, rect.height() / 6));
		fillRoundedRect(graphics, horizon, clampInt(pad, 6, 16), new Color(188, 212, 236, 255));
		graphics.setColor(new Color(82, 138, 196, 255));
		graphics.fillPolygon(
				new int[]{
						rect.x() + pad,
						rect.x() + rect.width() / 3,
						rect.x() + rect.width() / 2,
						rect.x() + rect.width() * 3 / 4,
						rect.right() - pad
				},
				new int[]{
						horizon.y() + horizon.height(),
						rect.y() + rect.height() / 2,
						rect.y() + rect.height() / 3,
						rect.y() + rect.height() / 2,
						horizon.y() + horizon.height()
				},
				5
		);
		int sunSize = Math.max(10, rect.width() / 8);
		graphics.setColor(new Color(255, 220, 112, 255));
		graphics.fillOval(rect.right() - pad - sunSize * 2, rect.y() + pad, sunSize, sunSize);
	}

	static void drawMediaCenterControls(Graphics2D graphics, UiLayout layout, MediaVisualSnapshot state) {
		if (graphics == null || layout == null || state == null || !state.playbackControlsVisible()) {
			return;
		}
		ScreenViewMode chromeMode = mediaChromeMode(state);
		drawMediaTransportButton(graphics, mediaCenterBackRect(layout, chromeMode), TransportButtonKind.BACK, false, state.paused(), chromeMode, layout);
		if (state.droneControlVisible()) {
			drawDroneControlButton(graphics, mediaCenterPlayPauseRect(layout, chromeMode), layout);
		} else if (state.centerPlayPauseVisible()) {
			drawMediaTransportButton(graphics, mediaCenterPlayPauseRect(layout, chromeMode), TransportButtonKind.PLAY_PAUSE, state.loading(), state.paused(), chromeMode, layout);
		}
		drawMediaTransportButton(graphics, mediaCenterForwardRect(layout, chromeMode), TransportButtonKind.FORWARD, false, state.paused(), chromeMode, layout);
	}

	static void drawDroneControlButton(Graphics2D graphics, UiRect rect, UiLayout layout) {
		if (graphics == null || rect == null || layout == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		fillRoundedRect(graphics, rect, rect.height(), new Color(248, 246, 246, 244));
		strokeRoundedRect(graphics, rect, rect.height(), 1.0F, new Color(20, 18, 20, 42));
		int padding = clampInt(rect.height() / 4, 4, 12);
		int iconSize = clampInt((int) Math.round(rect.height() * 0.46D), 11, Math.max(11, rect.height() - padding * 2));
		UiRect iconRect = new UiRect(
				rect.x() + padding + Math.max(0, rect.height() - iconSize) / 3,
				rect.y() + (rect.height() - iconSize) / 2,
				iconSize,
				iconSize
		);
		drawGamepadGlyph(graphics, iconRect, new Color(20, 18, 20, 244), Math.max(1.4F, mediaChromeStrokeWidth(rect) * 0.75F));
		int labelGap = clampInt(layout.unit() / 2, 4, 9);
		UiRect labelRect = new UiRect(
				iconRect.right() + labelGap,
				rect.y(),
				Math.max(12, rect.right() - iconRect.right() - padding - labelGap),
				rect.height()
		);
		drawCenteredTextFitted(
				graphics,
				"Управлять",
				labelRect,
				new Color(20, 18, 20, 246),
				Font.BOLD,
				clampInt(layout.unit() + 2, 10, 18),
				8
		);
	}

	static void drawMediaTransportButton(Graphics2D graphics, UiRect rect, TransportButtonKind kind, boolean loading, boolean paused, ScreenViewMode mode, UiLayout layout) {
		Color fill = kind == TransportButtonKind.PLAY_PAUSE
				? new Color(248, 246, 246, 242)
				: new Color(50, 36, 42, 184);
		boolean roundVideoCenterButton = kind == TransportButtonKind.PLAY_PAUSE && !isYoutubeMusicMode(mode);
		UiRect visualRect = rect;
		if (roundVideoCenterButton) {
			int diameter = Math.min(rect.width(), rect.height());
			visualRect = new UiRect(
					rect.x() + (rect.width() - diameter) / 2,
					rect.y() + (rect.height() - diameter) / 2,
					diameter,
					diameter
			);
			graphics.setColor(fill);
			graphics.fillOval(visualRect.x(), visualRect.y(), visualRect.width(), visualRect.height());
			graphics.setColor(new Color(255, 255, 255, 46));
			Stroke previousStroke = graphics.getStroke();
			graphics.setStroke(new BasicStroke(1.0F));
			graphics.drawOval(visualRect.x(), visualRect.y(), visualRect.width() - 1, visualRect.height() - 1);
			graphics.setStroke(previousStroke);
		} else {
			int arc = kind == TransportButtonKind.PLAY_PAUSE ? rect.height() : Math.min(rect.width(), rect.height());
			fillRoundedRect(graphics, rect, arc, fill);
		}
		if (kind != TransportButtonKind.PLAY_PAUSE) {
			int arc = Math.min(rect.width(), rect.height());
			strokeRoundedRect(graphics, rect, arc, 1.0F, new Color(255, 255, 255, 22));
		}
		int iconSize = mediaTransportIconSize(visualRect, layout);
		UiRect iconRect = new UiRect(
				visualRect.x() + (visualRect.width() - iconSize) / 2,
				visualRect.y() + (visualRect.height() - iconSize) / 2,
				iconSize,
				iconSize
		);
		Color iconColor = kind == TransportButtonKind.PLAY_PAUSE
				? new Color(20, 18, 20, 244)
				: new Color(248, 251, 255);
		if (loading && kind == TransportButtonKind.PLAY_PAUSE) {
			drawLoadingSpinner(graphics, iconRect, iconColor, 2.6F);
			return;
		}
		switch (kind) {
			case BACK -> drawSeekGlyph(graphics, iconRect, iconColor, true);
			case FORWARD -> drawSeekGlyph(graphics, iconRect, iconColor, false);
			case PLAY_PAUSE -> {
				if (paused) {
					drawPlayGlyph(graphics, iconRect, iconColor);
				} else {
					drawPauseGlyph(graphics, iconRect, iconColor);
				}
			}
		}
	}

	static int mediaTransportIconSize(UiRect rect, UiLayout layout) {
		return clampInt(
				(int) Math.round(Math.min(rect.width(), rect.height()) * 0.44D),
				ultraCompactScreenLayout(layout) ? 8 : 10,
				Math.max(ultraCompactScreenLayout(layout) ? 8 : 10, Math.min(rect.width(), rect.height()) - Math.max(4, layout.unit()))
		);
	}

	static void drawLauncherArrowButton(Graphics2D graphics, UiRect rect, boolean up) {
		fillRoundedRect(graphics, rect, clampInt(rect.width() / 2, 8, 12), new Color(248, 251, 255, 240));
		graphics.setColor(new Color(18, 22, 28));
		int pad = Math.max(4, rect.width() / 4);
		if (up) {
			graphics.fillPolygon(
					new int[]{rect.x() + rect.width() / 2, rect.x() + pad, rect.right() - pad},
					new int[]{rect.y() + pad, rect.bottom() - pad, rect.bottom() - pad},
					3
			);
		} else {
			graphics.fillPolygon(
					new int[]{rect.x() + pad, rect.right() - pad, rect.x() + rect.width() / 2},
					new int[]{rect.y() + pad, rect.y() + pad, rect.bottom() - pad},
					3
			);
		}
	}

	static void drawBackArrow(Graphics2D graphics, UiRect rect, Color color) {
		drawPlayerUiIcon(graphics, rect, PlayerUiIcon.BACK, color);
	}

	static UiLayout createUiLayout(int width, int height) {
		int canvasWidth = width * MAP_SIZE;
		int canvasHeight = height * MAP_SIZE;
		int viewportInset = monitorViewportInset(width, height);
		int viewportWidth = Math.max(1, canvasWidth - viewportInset * 2);
		int viewportHeight = Math.max(1, canvasHeight - viewportInset * 2);
		int viewportX = Math.max(0, (canvasWidth - viewportWidth) / 2);
		int viewportY = Math.max(0, (canvasHeight - viewportHeight) / 2);
		int minSpan = Math.max(1, Math.min(width, height));
		// Only the first two screen sizes use the denser scale. Starting at 3x3,
		// restore the original roomier proportions.
		double scale = minSpan <= 1
				? 0.85D
				: minSpan == 2
				? 1.35D
				: 1.0D + (minSpan - 1) * 0.50D;
		int margin = clampInt(
				(int) Math.round((minSpan >= 3 ? 4.0D : 3.0D) * scale),
				3,
				32
		);
		int unit = clampInt((int) Math.round(5.0D * scale), 4, 32);
		return new UiLayout(canvasWidth, canvasHeight, viewportX, viewportY, viewportWidth, viewportHeight, margin, unit);
	}

	static int monitorViewportInset(int width, int height) {
		if (width <= 0 || height <= 0) {
			return 0;
		}
		int maxInset = Math.max(0, Math.min(width * MAP_SIZE, height * MAP_SIZE) / 4);
		// Matches the 0.75-unit bezel thickness used by the monitor_display item models.
		return clampInt(MONITOR_VIEWPORT_SAFE_INSET, 0, maxInset);
	}

	static int smallestScreenTileSpan(UiLayout layout) {
		if (layout == null) {
			return 1;
		}
		return Math.max(1, Math.min(layout.canvasWidth() / MAP_SIZE, layout.canvasHeight() / MAP_SIZE));
	}

	static boolean compactScreenLayout(UiLayout layout) {
		return smallestScreenTileSpan(layout) <= 2;
	}

	static boolean ultraCompactScreenLayout(UiLayout layout) {
		return smallestScreenTileSpan(layout) <= 1;
	}

	static boolean youtubeMusicLandscapeLayout(UiLayout layout) {
		if (layout == null) {
			return false;
		}
		return layout.viewportWidth() >= layout.viewportHeight() * 3 / 2;
	}

	static boolean youtubeMusicMicroLayout(UiLayout layout) {
		if (layout == null || youtubeMusicLandscapeLayout(layout)) {
			return false;
		}
		return layout.viewportWidth() <= MAP_SIZE && layout.viewportHeight() <= MAP_SIZE;
	}

	static UiRect workspaceRect(UiLayout layout) {
		return new UiRect(
				layout.viewportX() + layout.margin() / 2,
				layout.viewportY() + layout.margin() / 2,
				layout.viewportWidth() - layout.margin(),
				layout.viewportHeight() - layout.margin()
		);
	}

	static UiRect homePanelRect(UiLayout layout) {
		return new UiRect(
				layout.viewportX() + layout.margin() / 3,
				layout.viewportY() + layout.margin() / 3,
				layout.viewportWidth() - (layout.margin() * 2) / 3,
				layout.viewportHeight() - (layout.margin() * 2) / 3
		);
	}

	static UiRect homeHeaderRect(UiLayout layout, UiRect panel) {
		int width = clampInt((int) Math.round(panel.width() * 0.62D), 52, Math.max(52, panel.width() - layout.unit() * 2));
		return new UiRect(
				panel.x() + (panel.width() - width) / 2,
				panel.y() + layout.unit() / 2,
				width,
				homeHeaderHeight(layout)
		);
	}

	static UiRect homeFooterRect(UiLayout layout, UiRect panel) {
		int height = clampInt((int) Math.round(layout.unit() * 2.2D), 18, 40);
		int width = clampInt((int) Math.round(panel.width() * 0.46D), 52, Math.max(52, panel.width() - layout.unit() * 2));
		return new UiRect(
				panel.x() + (panel.width() - width) / 2,
				panel.bottom() - height - layout.unit() / 2,
				width,
				height
		);
	}

	static UiRect homeContentRect(UiLayout layout, UiRect panel) {
		int contentTop = homeHeaderRect(layout, panel).bottom() + clampInt(layout.unit(), 6, 18);
		int contentBottom = panel.bottom() - clampInt(layout.unit(), 10, 24);
		return new UiRect(
				panel.x() + clampInt(layout.unit() / 2, 4, 14),
				contentTop,
				panel.width() - clampInt(layout.unit(), 8, 28),
				Math.max(24, contentBottom - contentTop)
		);
	}

	static UiRect homeGridRect(UiLayout layout, UiRect panel) {
		return scrollContentRect(homeContentRect(layout, panel), layout);
	}

	static UiRect homeScrollbarTrackRect(UiLayout layout) {
		return scrollTrackRect(homeContentRect(layout, homePanelRect(layout)), layout);
	}

	static UiRect homeScrollbarThumbRect(UiLayout layout, int scroll, int visibleRows, int totalRows) {
		return scrollbarThumbRect(homeScrollbarTrackRect(layout), scroll, visibleRows, totalRows);
	}

	static UiRect homeAppCardRect(UiLayout layout, int launcherPage, int slotIndex) {
		UiRect panel = homePanelRect(layout);
		int columns = homeColumns(layout);
		int row = slotIndex / columns;
		int column = slotIndex % columns;
		int gap = homeAppGap(layout);
		int cardWidth = homeAppCardWidth(layout);
		int cardHeight = homeAppCardHeight(layout);
		int rows = homeRowsPerPage(layout);
		int cardsWidth = columns * cardWidth + Math.max(0, columns - 1) * gap;
		int cardsHeight = rows * cardHeight + Math.max(0, rows - 1) * gap;
		UiRect content = homeGridRect(layout, panel);
		int startX = content.x() + Math.max(0, content.width() - cardsWidth) / 2;
		int startY = content.y() + Math.max(0, content.height() - cardsHeight) / 2;
		return new UiRect(
				startX + column * (cardWidth + gap),
				startY + row * (cardHeight + gap),
				cardWidth,
				cardHeight
		);
	}

	static UiRect homeAppIconRect(UiRect cardRect, UiLayout layout) {
		UiRect label = homeAppLabelRect(layout, cardRect);
		int topInset = clampInt(layout.unit() / 3, 1, 8);
		int labelGap = clampInt(layout.unit() / 2, 2, 8);
		int maxSize = Math.max(16, Math.min(
				cardRect.width() - layout.unit(),
				label.y() - (cardRect.y() + topInset) - labelGap
		));
		int size = clampInt(Math.round(Math.min(cardRect.width(), cardRect.height()) * 0.62F), 16, maxSize);
		return new UiRect(
				cardRect.x() + (cardRect.width() - size) / 2,
				cardRect.y() + topInset,
				size,
				size
		);
	}

	static UiRect homeAppNotificationBadgeRect(UiRect iconRect, UiLayout layout) {
		int height = clampInt(layout.unit() * 2, 16, 26);
		int width = clampInt(height + layout.unit(), height, 38);
		return new UiRect(iconRect.right() - width / 2, iconRect.y() - height / 5, width, height);
	}

	static UiRect homeAppLabelRect(UiLayout layout, UiRect cardRect) {
		int labelHeight = homeAppLabelHeight(layout);
		int bottomInset = clampInt(layout.unit() / 3, 2, 8);
		return new UiRect(
				cardRect.x() + clampInt(layout.unit() / 2, 4, 8),
				cardRect.bottom() - labelHeight - bottomInset,
				cardRect.width() - clampInt(layout.unit(), 8, 14),
				labelHeight
		);
	}

	static UiRect mediaHeaderRect(UiLayout layout) {
		UiRect workspace = workspaceRect(layout);
		return new UiRect(
				workspace.x() + layout.unit() / 2,
				workspace.y() + layout.unit() / 2,
				workspace.width() - layout.unit(),
				clampInt(layout.unit() * 2, 22, 36)
		);
	}

	static UiRect genericCloseRect(UiLayout layout) {
		UiRect header = mediaHeaderRect(layout);
		int size = clampInt(layout.unit() + 8, 18, 28);
		return new UiRect(
				header.x() + layout.unit() / 2,
				header.y() + (header.height() - size) / 2,
				size,
				size
		);
	}

	static UiRect mediaCanvasRect(UiLayout layout) {
		return new UiRect(
				layout.viewportX(),
				layout.viewportY(),
				Math.max(1, layout.viewportWidth()),
				Math.max(1, layout.viewportHeight())
		);
	}

	static UiRect mediaCloseRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		boolean ultra = ultraCompactScreenLayout(layout);
		int size = ultra ? clampInt(layout.unit() * 2 + 2, 12, 14) : clampInt(layout.unit() * 2, 18, 28);
		int inset = ultra ? Math.max(2, layout.unit() / 3) : layout.unit() / 2;
		return new UiRect(canvas.x() + inset, canvas.y() + inset, size, size);
	}

	static UiRect mediaPlayerMenuRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		boolean ultra = ultraCompactScreenLayout(layout);
		int size = ultra ? clampInt(layout.unit() * 2 + 2, 12, 14) : clampInt(layout.unit() * 2, 18, 28);
		int inset = ultra ? Math.max(2, layout.unit() / 3) : layout.unit() / 2;
		return new UiRect(canvas.right() - size - inset, canvas.y() + inset, size, size);
	}

	static UiRect mediaGallerySettingsRect(UiLayout layout) {
		UiRect menuRect = mediaPlayerMenuRect(layout);
		int gap = mediaHeaderControlGap(layout);
		return new UiRect(menuRect.x() - menuRect.width() - gap, menuRect.y(), menuRect.width(), menuRect.height());
	}

	static UiRect mediaOverlayToggleRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		boolean ultra = ultraCompactScreenLayout(layout);
		int width = ultra ? clampInt(layout.unit() * 4, 20, 28) : clampInt(layout.unit() * 4, 34, 58);
		int height = ultra ? clampInt(layout.unit() * 2 + 2, 12, 14) : clampInt(layout.unit() * 2, 18, 28);
		int inset = ultra ? Math.max(2, layout.unit() / 3) : layout.unit() / 2;
		return new UiRect(canvas.right() - width - inset, canvas.y() + inset, width, height);
	}

	static UiRect mediaLinkRect(UiLayout layout) {
		return mediaLinkRect(layout, true);
	}

	static UiRect mediaLinkRect(UiLayout layout, boolean hasMedia) {
		UiRect canvas = mediaCanvasRect(layout);
		if (!hasMedia) {
			int width = clampInt(canvas.width() * 2 / 3, 86, canvas.width() - layout.unit() * 4);
			int height = clampInt(layout.unit() * 3, 28, 44);
			return new UiRect(
					canvas.x() + (canvas.width() - width) / 2,
					canvas.y() + (canvas.height() - height) / 2,
					width,
					height
			);
		}
		UiRect closeRect = mediaCloseRect(layout);
		int height = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 2 + 2, 12, 14)
				: clampInt(layout.unit() * 2, 18, 28);
		int x = closeRect.right() + mediaHeaderControlGap(layout);
		int sideInset = ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit() / 3) : layout.unit() / 2;
		int width = Math.max(24, canvas.right() - x - sideInset);
		return new UiRect(x, canvas.y() + sideInset, width, height);
	}

	static UiRect mediaActionRect(UiLayout layout, boolean hasMedia) {
		UiRect linkRect = mediaLinkRect(layout, hasMedia);
		int inset = clampInt(layout.unit() / 4, 2, 4);
		int width = clampInt(linkRect.height() * 2 + layout.unit() / 2, 24, 42);
		return new UiRect(
				linkRect.right() - width - inset,
				linkRect.y() + inset,
				width,
				linkRect.height() - inset * 2
		);
	}

	static UiRect mediaGalleryBrowserCloseRect(UiLayout layout) {
		return mediaCloseRect(layout);
	}

	static UiRect mediaGalleryBrowserLinkRect(UiLayout layout) {
		return mediaGalleryBrowserLinkRect(layout, false);
	}

	static UiRect mediaGalleryBrowserLinkRect(UiLayout layout, boolean bulkDeleteVisible) {
		UiRect closeRect = mediaGalleryBrowserCloseRect(layout);
		UiRect canvas = mediaCanvasRect(layout);
		int height = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 2 + 2, 12, 14)
				: clampInt(layout.unit() * 2, 18, 28);
		int x = closeRect.right() + mediaHeaderControlGap(layout);
		int right = (bulkDeleteVisible ? mediaGalleryBrowserBulkDeleteRect(layout) : mediaGalleryBrowserSelectionRect(layout)).x() - mediaHeaderControlGap(layout);
		int width = Math.max(24, right - x);
		int sideInset = ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit() / 3) : layout.unit() / 2;
		return new UiRect(x, canvas.y() + sideInset, width, height);
	}

	static UiRect mediaGalleryBrowserSelectionRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		boolean ultra = ultraCompactScreenLayout(layout);
		int size = ultra ? clampInt(layout.unit() * 2 + 2, 12, 14) : clampInt(layout.unit() * 2, 18, 28);
		int inset = ultra ? Math.max(2, layout.unit() / 3) : layout.unit() / 2;
		return new UiRect(
				canvas.right() - size - inset,
				canvas.y() + inset,
				size,
				size
		);
	}

	static UiRect mediaGalleryBrowserBulkDeleteRect(UiLayout layout) {
		UiRect selection = mediaGalleryBrowserBulkSendRect(layout);
		int gap = mediaHeaderControlGap(layout);
		return new UiRect(selection.x() - selection.width() - gap, selection.y(), selection.width(), selection.height());
	}

	static UiRect mediaGalleryBrowserBulkSendRect(UiLayout layout) {
		UiRect selection = mediaGalleryBrowserSlideshowRect(layout);
		int gap = mediaHeaderControlGap(layout);
		return new UiRect(selection.x() - selection.width() - gap, selection.y(), selection.width(), selection.height());
	}

	static UiRect mediaGalleryBrowserSlideshowRect(UiLayout layout) {
		UiRect selection = mediaGalleryBrowserSelectAllRect(layout);
		int gap = mediaHeaderControlGap(layout);
		return new UiRect(selection.x() - selection.width() - gap, selection.y(), selection.width(), selection.height());
	}

	static UiRect mediaGalleryBrowserSelectAllRect(UiLayout layout) {
		UiRect selection = mediaGalleryBrowserSelectionRect(layout);
		int gap = mediaHeaderControlGap(layout);
		return new UiRect(selection.x() - selection.width() - gap, selection.y(), selection.width(), selection.height());
	}

	static UiRect mediaGalleryBrowserSettingsRect(UiLayout layout) {
		UiRect selection = mediaGalleryBrowserSelectionRect(layout);
		int gap = mediaHeaderControlGap(layout);
		return new UiRect(selection.x() - selection.width() - gap, selection.y(), selection.width(), selection.height());
	}

	static int mediaHeaderControlGap(UiLayout layout) {
		return clampInt(layout.unit() / 3, 2, 6);
	}

	static UiRect mediaGalleryPlayerTitleRect(UiLayout layout) {
		UiRect closeRect = mediaCloseRect(layout);
		UiRect canvas = mediaCanvasRect(layout);
		UiRect actionRect = mediaGalleryPlayerActionRect(layout);
		int height = clampInt(layout.unit() * 2, 18, 28);
		int x = closeRect.right() + mediaHeaderControlGap(layout);
		int width = Math.max(48, actionRect.x() - x - clampInt(layout.unit() / 2, 4, 8));
		return new UiRect(x, canvas.y() + layout.unit() / 2, width, height);
	}

	static UiRect mediaGalleryPlayerActionRect(UiLayout layout) {
		return mediaQueueToggleRect(layout, ScreenViewMode.HOME);
	}

	static UiRect mediaScaleRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		boolean ultra = ultraCompactScreenLayout(layout);
		int size = ultra ? clampInt(layout.unit() * 2 + 2, 12, 14) : clampInt(layout.unit() * 2, 18, 28);
		int inset = ultra ? Math.max(2, layout.unit() / 3) : layout.unit() / 2;
		return new UiRect(canvas.right() - size - inset, canvas.bottom() - size - inset, size, size);
	}

	static UiRect mediaWallpaperActionRect(UiLayout layout, MediaRuntimeState state) {
		return mediaWallpaperActionRect(
				layout,
				mediaWallpaperActionVisibleLocked(state),
				mediaPrimaryActionVisibleLocked(state),
				mediaNonMusicQueueVisibleLocked(state),
				mediaRepeatActionVisibleLocked(state),
				mediaScaleActionVisibleLocked(state)
		);
	}

	static UiRect mediaPrimaryActionRect(UiLayout layout, MediaRuntimeState state) {
		return mediaPrimaryActionRect(
				layout,
				mediaWallpaperActionVisibleLocked(state),
				mediaPrimaryActionVisibleLocked(state),
				mediaNonMusicQueueVisibleLocked(state),
				mediaRepeatActionVisibleLocked(state),
				mediaScaleActionVisibleLocked(state)
		);
	}

	static UiRect mediaQueueActionRect(UiLayout layout, MediaRuntimeState state) {
		return mediaQueueActionRect(
				layout,
				mediaWallpaperActionVisibleLocked(state),
				mediaPrimaryActionVisibleLocked(state),
				mediaNonMusicQueueVisibleLocked(state),
				mediaRepeatActionVisibleLocked(state),
				mediaScaleActionVisibleLocked(state)
		);
	}

	static UiRect mediaRepeatActionRect(UiLayout layout, MediaRuntimeState state) {
		return mediaRepeatActionRect(
				layout,
				mediaWallpaperActionVisibleLocked(state),
				mediaPrimaryActionVisibleLocked(state),
				mediaNonMusicQueueVisibleLocked(state),
				mediaRepeatActionVisibleLocked(state),
				mediaScaleActionVisibleLocked(state)
		);
	}

	static UiRect mediaScaleActionRect(UiLayout layout, MediaRuntimeState state) {
		return mediaScaleActionRect(
				layout,
				mediaWallpaperActionVisibleLocked(state),
				mediaPrimaryActionVisibleLocked(state),
				mediaNonMusicQueueVisibleLocked(state),
				mediaRepeatActionVisibleLocked(state),
				mediaScaleActionVisibleLocked(state)
		);
	}

	static boolean mediaWallpaperActionVisibleLocked(MediaRuntimeState state) {
		return false;
	}

	static boolean mediaPrimaryActionVisibleLocked(MediaRuntimeState state) {
		return state != null && !usesMusicPlayerLayoutLocked(state) && !isYoutubeMusicMode(state.mode) && resolvedActionVisible(state);
	}

	static boolean mediaNonMusicQueueVisibleLocked(MediaRuntimeState state) {
		return state != null && !usesMusicPlayerLayoutLocked(state) && isYoutubeFamilyMode(state.mode) && !isGalleryBackedYoutubeLocked(state);
	}

	static boolean mediaRepeatActionVisibleLocked(MediaRuntimeState state) {
		return state != null && !usesMusicPlayerLayoutLocked(state) && repeatButtonVisibleLocked(state);
	}

	static boolean mediaScaleActionVisibleLocked(MediaRuntimeState state) {
		return state != null && !usesMusicPlayerLayoutLocked(state);
	}

	static UiRect mediaWallpaperActionRect(
			UiLayout layout,
			boolean wallpaperVisible,
			boolean primaryVisible,
			boolean queueVisible,
			boolean repeatVisible,
			boolean scaleVisible
	) {
		return mediaBottomActionRect(layout, MediaBottomAction.WALLPAPER, wallpaperVisible, primaryVisible, queueVisible, repeatVisible, scaleVisible);
	}

	static UiRect mediaPrimaryActionRect(
			UiLayout layout,
			boolean wallpaperVisible,
			boolean primaryVisible,
			boolean queueVisible,
			boolean repeatVisible,
			boolean scaleVisible
	) {
		return mediaBottomActionRect(layout, MediaBottomAction.PRIMARY, wallpaperVisible, primaryVisible, queueVisible, repeatVisible, scaleVisible);
	}

	static UiRect mediaQueueActionRect(
			UiLayout layout,
			boolean wallpaperVisible,
			boolean primaryVisible,
			boolean queueVisible,
			boolean repeatVisible,
			boolean scaleVisible
	) {
		return mediaBottomActionRect(layout, MediaBottomAction.QUEUE, wallpaperVisible, primaryVisible, queueVisible, repeatVisible, scaleVisible);
	}

	static UiRect mediaRepeatActionRect(
			UiLayout layout,
			boolean wallpaperVisible,
			boolean primaryVisible,
			boolean queueVisible,
			boolean repeatVisible,
			boolean scaleVisible
	) {
		return mediaBottomActionRect(layout, MediaBottomAction.REPEAT, wallpaperVisible, primaryVisible, queueVisible, repeatVisible, scaleVisible);
	}

	static UiRect mediaScaleActionRect(
			UiLayout layout,
			boolean wallpaperVisible,
			boolean primaryVisible,
			boolean queueVisible,
			boolean repeatVisible,
			boolean scaleVisible
	) {
		return mediaBottomActionRect(layout, MediaBottomAction.SCALE, wallpaperVisible, primaryVisible, queueVisible, repeatVisible, scaleVisible);
	}

	static UiRect mediaBottomActionRect(
			UiLayout layout,
			MediaBottomAction action,
			boolean wallpaperVisible,
			boolean primaryVisible,
			boolean queueVisible,
			boolean repeatVisible,
			boolean scaleVisible
	) {
		int index = mediaBottomActionIndex(action, wallpaperVisible, primaryVisible, queueVisible, repeatVisible, scaleVisible);
		if (index < 0) {
			return new UiRect(0, 0, 0, 0);
		}
		int count = mediaBottomActionCount(wallpaperVisible, primaryVisible, queueVisible, repeatVisible, scaleVisible);
		UiRect scaleAnchor = mediaScaleRect(layout);
		int gap = mediaHeaderControlGap(layout);
		int groupWidth = scaleAnchor.width() * count + gap * Math.max(0, count - 1);
		int x = scaleAnchor.right() - groupWidth + index * (scaleAnchor.width() + gap);
		return new UiRect(x, scaleAnchor.y(), scaleAnchor.width(), scaleAnchor.height());
	}

	static int mediaBottomActionIndex(
			MediaBottomAction action,
			boolean wallpaperVisible,
			boolean primaryVisible,
			boolean queueVisible,
			boolean repeatVisible,
			boolean scaleVisible
	) {
		int index = 0;
		if (wallpaperVisible) {
			if (action == MediaBottomAction.WALLPAPER) {
				return index;
			}
			index++;
		}
		if (primaryVisible) {
			if (action == MediaBottomAction.PRIMARY) {
				return index;
			}
			index++;
		}
		if (queueVisible) {
			if (action == MediaBottomAction.QUEUE) {
				return index;
			}
			index++;
		}
		if (repeatVisible) {
			if (action == MediaBottomAction.REPEAT) {
				return index;
			}
			index++;
		}
		if (scaleVisible && action == MediaBottomAction.SCALE) {
			return index;
		}
		return -1;
	}

	static int mediaBottomActionCount(boolean wallpaperVisible, boolean primaryVisible, boolean queueVisible, boolean repeatVisible, boolean scaleVisible) {
		return (wallpaperVisible ? 1 : 0)
				+ (primaryVisible ? 1 : 0)
				+ (queueVisible ? 1 : 0)
				+ (repeatVisible ? 1 : 0)
				+ (scaleVisible ? 1 : 0);
	}

	static UiRect mediaDownloadRect(UiLayout layout) {
		UiRect queueRect = mediaQueueToggleRect(layout, ScreenViewMode.HOME);
		int gap = mediaHeaderControlGap(layout);
		return new UiRect(queueRect.x() - queueRect.width() - gap, queueRect.y(), queueRect.width(), queueRect.height());
	}

	static UiRect mediaQueueToggleRect(UiLayout layout) {
		return mediaQueueToggleRect(layout, ScreenViewMode.HOME);
	}

	static UiRect mediaQueueToggleRect(UiLayout layout, ScreenViewMode mode) {
		if (isYoutubeMusicMode(mode)) {
			return mediaYoutubeMusicActionButtonRect(layout, 3, 5);
		}
		UiRect scaleRect = mediaScaleRect(layout);
		int gap = mediaHeaderControlGap(layout);
		return new UiRect(scaleRect.x() - scaleRect.width() - gap, scaleRect.y(), scaleRect.width(), scaleRect.height());
	}

	static int mediaYoutubeMusicActionButtonSize(UiLayout layout) {
		return ultraCompactScreenLayout(layout)
				? clampInt(mediaYoutubeMusicActionsRowHeight(layout), 12, 16)
				: clampInt(mediaYoutubeMusicActionsRowHeight(layout), 18, 36);
	}

	static int mediaYoutubeMusicActionButtonsGap(UiLayout layout) {
		return mediaHeaderControlGap(layout);
	}

	static UiRect mediaYoutubeMusicActionButtonRect(UiLayout layout, int slot, int total) {
		UiRect actionsRow = mediaYoutubeMusicActionsRowRect(layout);
		int size = mediaYoutubeMusicActionButtonSize(layout);
		int gap = mediaYoutubeMusicActionButtonsGap(layout);
		int totalWidth = size * total + gap * Math.max(0, total - 1);
		int startX = actionsRow.x() + (actionsRow.width() - totalWidth) / 2;
		return new UiRect(
				startX + slot * (size + gap),
				actionsRow.y() + (actionsRow.height() - size) / 2,
				size,
				size
		);
	}

	static UiRect mediaYoutubeMusicSearchRect(UiLayout layout) {
		return mediaYoutubeMusicSearchRect(layout, true);
	}

	static UiRect mediaYoutubeMusicSearchRect(UiLayout layout, boolean downloadVisible) {
		return mediaYoutubeMusicActionButtonRect(layout, 0, mediaYoutubeMusicActionCount(downloadVisible));
	}

	static UiRect mediaYoutubeMusicShuffleRect(UiLayout layout) {
		return mediaYoutubeMusicShuffleRect(layout, true);
	}

	static UiRect mediaYoutubeMusicShuffleRect(UiLayout layout, boolean downloadVisible) {
		return mediaYoutubeMusicActionButtonRect(layout, 1, mediaYoutubeMusicActionCount(downloadVisible));
	}

	static UiRect mediaYoutubeMusicRepeatRect(UiLayout layout) {
		return mediaYoutubeMusicRepeatRect(layout, true);
	}

	static UiRect mediaYoutubeMusicRepeatRect(UiLayout layout, boolean downloadVisible) {
		return mediaYoutubeMusicActionButtonRect(layout, 2, mediaYoutubeMusicActionCount(downloadVisible));
	}

	static UiRect mediaYoutubeMusicDownloadRect(UiLayout layout) {
		return mediaYoutubeMusicDownloadRect(layout, true);
	}

	static UiRect mediaYoutubeMusicDownloadRect(UiLayout layout, boolean downloadVisible) {
		int total = mediaYoutubeMusicActionCount(downloadVisible);
		return mediaYoutubeMusicActionButtonRect(layout, total - 1, total);
	}

	static UiRect mediaYoutubeMusicQueueRect(UiLayout layout, boolean downloadVisible) {
		return mediaYoutubeMusicActionButtonRect(layout, 3, mediaYoutubeMusicActionCount(downloadVisible));
	}

	static int mediaYoutubeMusicActionCount(boolean downloadVisible) {
		return downloadVisible ? 5 : 4;
	}

	static UiRect mediaGalleryMusicRepeatRect(UiLayout layout) {
		return mediaGalleryMusicRepeatRect(layout, true);
	}

	static UiRect mediaGalleryMusicRepeatRect(UiLayout layout, boolean primaryActionVisible) {
		return mediaYoutubeMusicActionButtonRect(layout, 0, primaryActionVisible ? 2 : 1);
	}

	static UiRect mediaGalleryMusicPrimaryActionRect(UiLayout layout) {
		return mediaGalleryMusicPrimaryActionRect(layout, true);
	}

	static UiRect mediaGalleryMusicPrimaryActionRect(UiLayout layout, boolean repeatVisible) {
		return mediaYoutubeMusicActionButtonRect(layout, repeatVisible ? 1 : 0, repeatVisible ? 2 : 1);
	}

	static UiRect mediaTimelineRect(UiLayout layout) {
		return mediaTimelineRect(layout, ScreenViewMode.HOME);
	}

	static UiRect mediaTimelineRect(UiLayout layout, MediaRuntimeState state) {
		ScreenViewMode chromeMode = mediaChromeMode(state);
		if (isYoutubeMusicMode(chromeMode)) {
			return mediaTimelineRect(layout, chromeMode);
		}
		return mediaTimelineRect(
				layout,
				chromeMode,
				mediaWallpaperActionVisibleLocked(state),
				mediaPrimaryActionVisibleLocked(state),
				mediaNonMusicQueueVisibleLocked(state),
				mediaRepeatActionVisibleLocked(state),
				mediaScaleActionVisibleLocked(state)
		);
	}

	static UiRect mediaTimelineRect(UiLayout layout, MediaVisualSnapshot state) {
		ScreenViewMode chromeMode = mediaChromeMode(state);
		if (isYoutubeMusicMode(chromeMode)) {
			return mediaTimelineRect(layout, chromeMode);
		}
		boolean nonMusicChrome = !usesMusicPlayerLayout(state);
		boolean showPrimaryActionButton = state != null && nonMusicChrome && state.actionVisible();
		boolean showWallpaperActionButton = state != null && state.mode() == ScreenViewMode.GALLERY && state.wallpaperActionVisible();
		boolean showQueueButton = state != null && nonMusicChrome && isYoutubeFamilyMode(state.mode()) && !state.galleryBackedYoutube();
		boolean showRepeatButton = state != null && nonMusicChrome && repeatButtonVisible(state);
		return mediaTimelineRect(
				layout,
				chromeMode,
				showWallpaperActionButton,
				showPrimaryActionButton,
				showQueueButton,
				showRepeatButton,
				nonMusicChrome
		);
	}

	static UiRect mediaTimelineRect(UiLayout layout, ScreenViewMode mode) {
		if (isYoutubeMusicMode(mode)) {
			if (youtubeMusicMicroLayout(layout)) {
				UiRect canvas = mediaCanvasRect(layout);
				UiRect artworkRect = mediaYoutubeMusicArtworkRect(layout);
				UiRect infoRect = mediaYoutubeMusicInfoRect(layout);
				int top = Math.max(artworkRect.bottom(), infoRect.bottom()) + mediaYoutubeMusicTimelineGap(layout);
				return new UiRect(canvas.x(), top, canvas.width(), mediaYoutubeMusicTrackHeight(layout));
			}
			UiRect infoRect = mediaYoutubeMusicInfoRect(layout);
			int gap = mediaYoutubeMusicTimelineGap(layout);
			int height = mediaYoutubeMusicTrackHeight(layout);
			return new UiRect(
					infoRect.x(),
					infoRect.bottom() + gap,
					infoRect.width(),
					height
			);
		}
		return mediaTimelineRect(layout, mode, false, false, false, false, true);
	}

	static UiRect mediaTimelineRect(
			UiLayout layout,
			ScreenViewMode mode,
			boolean wallpaperVisible,
			boolean primaryVisible,
			boolean queueVisible,
			boolean repeatVisible,
			boolean scaleVisible
	) {
		UiRect canvas = mediaCanvasRect(layout);
		int sideInset = ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit() / 3) : layout.unit() / 2;
		int left = canvas.x() + sideInset;
		int right = mediaTimelineRightEdge(layout, mode, wallpaperVisible, primaryVisible, queueVisible, repeatVisible, scaleVisible);
		int height = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 2 + 1, 10, 12)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 2, 14, 20)
				: clampInt(layout.unit() * 2, 16, 28);
		return new UiRect(
				left,
				canvas.bottom() - height - sideInset,
				Math.max(44, right - left),
				height
		);
	}

	static int mediaTimelineRightEdge(
			UiLayout layout,
			ScreenViewMode mode,
			boolean wallpaperVisible,
			boolean primaryVisible,
			boolean queueVisible,
			boolean repeatVisible,
			boolean scaleVisible
	) {
		UiRect canvas = mediaCanvasRect(layout);
		int sideInset = ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit() / 3) : layout.unit() / 2;
		int gap = clampInt(layout.unit() / 2, 4, 8);
		if (isYoutubeMusicMode(mode)) {
			return canvas.right() - sideInset;
		}
		UiRect firstAction = null;
		for (MediaBottomAction action : List.of(MediaBottomAction.WALLPAPER, MediaBottomAction.PRIMARY, MediaBottomAction.QUEUE, MediaBottomAction.REPEAT, MediaBottomAction.SCALE)) {
			UiRect rect = mediaBottomActionRect(layout, action, wallpaperVisible, primaryVisible, queueVisible, repeatVisible, scaleVisible);
			if (rect.width() > 0 && rect.height() > 0) {
				firstAction = rect;
				break;
			}
		}
		return (firstAction != null ? firstAction.x() : canvas.right() - sideInset) - gap;
	}

	static UiRect mediaGalleryGridRect(UiLayout layout) {
		UiRect linkRect = mediaGalleryBrowserLinkRect(layout);
		UiRect canvas = mediaCanvasRect(layout);
		int inset = clampInt(layout.unit() / 2, 4, 8);
		UiRect viewport = new UiRect(
				canvas.x() + inset,
				linkRect.bottom() + inset,
				canvas.width() - inset * 2,
				Math.max(18, canvas.bottom() - linkRect.bottom() - inset * 2)
		);
		return scrollContentRect(viewport, layout);
	}

	static int mediaGalleryFooterRectHeight(UiLayout layout) {
		return ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 2, 16, 22)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 2 + 1, 18, 26)
				: clampInt(layout.unit() * 2 + 2, 22, 30);
	}

	static int mediaGalleryVisibleRows(UiLayout layout) {
		UiRect grid = mediaGalleryGridRect(layout);
		int stride = mediaGalleryCardHeight(layout) + mediaGalleryCardGap(layout);
		return Math.max(1, (grid.height() + mediaGalleryCardGap(layout)) / Math.max(1, stride));
	}

	static int mediaGalleryColumns(UiLayout layout) {
		UiRect grid = mediaGalleryGridRect(layout);
		int gap = mediaGalleryCardGap(layout);
		int desiredCardWidth = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 9, 48, 64)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 9, 66, 92)
				: clampInt(layout.unit() * 10, 86, 132);
		int columns = Math.max(1, (grid.width() + gap) / Math.max(1, desiredCardWidth + gap));
		return Math.max(2, columns);
	}

	static int mediaGalleryCardGap(UiLayout layout) {
		return clampInt(layout.unit() / 2, 4, 8);
	}

	static int mediaGalleryTotalRows(int itemCount, UiLayout layout) {
		int columns = Math.max(1, mediaGalleryColumns(layout));
		return Math.max(0, (itemCount + columns - 1) / columns);
	}

	static int mediaGalleryCardHeight(UiLayout layout) {
		UiRect grid = mediaGalleryGridRect(layout);
		int gap = mediaGalleryCardGap(layout);
		int columns = mediaGalleryColumns(layout);
		int cardWidth = Math.max(18, (grid.width() - gap * Math.max(0, columns - 1)) / Math.max(1, columns));
		return clampInt((int) Math.round(cardWidth * 0.74D), clampInt(layout.unit() * 5, 28, 42), clampInt(layout.unit() * 12, 64, 150));
	}

	static UiRect mediaGalleryCardRect(UiLayout layout, int visibleRow, int column) {
		UiRect grid = mediaGalleryGridRect(layout);
		int gap = mediaGalleryCardGap(layout);
		int columns = mediaGalleryColumns(layout);
		int width = Math.max(18, (grid.width() - gap * Math.max(0, columns - 1)) / columns);
		int height = mediaGalleryCardHeight(layout);
		return new UiRect(
				grid.x() + column * (width + gap),
				grid.y() + visibleRow * (height + gap),
				width,
				height
		);
	}

	static int mediaGalleryRowStep(UiLayout layout) {
		return mediaGalleryCardHeight(layout) + mediaGalleryCardGap(layout);
	}

	static UiRect mediaGalleryCardPreviewRect(UiRect cardRect, UiLayout layout) {
		int inset = clampInt(layout.unit() / 3, 3, 6);
		return new UiRect(
				cardRect.x() + inset,
				cardRect.y() + inset,
				cardRect.width() - inset * 2,
				cardRect.height() - inset * 2
		);
	}

	static UiRect mediaGalleryCardPreviewRectWithMetadata(UiRect cardRect, UiLayout layout) {
		int inset = clampInt(layout.unit() / 3, 3, 6);
		int metadataHeight = clampInt(layout.unit() * 3 + 6, 28, 42);
		return new UiRect(
				cardRect.x() + inset,
				cardRect.y() + inset,
				cardRect.width() - inset * 2,
				Math.max(18, cardRect.height() - inset * 2 - metadataHeight)
		);
	}

	static UiRect sberDronesGalleryCardPreviewRect(UiRect cardRect, UiLayout layout) {
		int inset = clampInt(layout.unit() / 3, 3, 6);
		int metadataHeight = clampInt(
				(int) Math.round(cardRect.height() * 0.38D),
				clampInt(layout.unit() * 4 - 2, 30, 38),
				clampInt(layout.unit() * 6, 42, 62)
		);
		return new UiRect(
				cardRect.x() + inset,
				cardRect.y() + inset,
				cardRect.width() - inset * 2,
				Math.max(18, cardRect.height() - inset * 2 - metadataHeight)
		);
	}

	static UiRect mediaGalleryCardMetadataRect(UiRect cardRect, UiLayout layout) {
		UiRect preview = mediaGalleryCardPreviewRectWithMetadata(cardRect, layout);
		int inset = clampInt(layout.unit() / 3, 3, 6);
		return new UiRect(
				cardRect.x() + inset,
				preview.bottom() + inset,
				cardRect.width() - inset * 2,
				Math.max(12, cardRect.bottom() - preview.bottom() - inset * 2)
		);
	}

	static UiRect sberDronesGalleryCardMetadataRect(UiRect cardRect, UiLayout layout) {
		UiRect preview = sberDronesGalleryCardPreviewRect(cardRect, layout);
		int inset = clampInt(layout.unit() / 3, 3, 6);
		return new UiRect(
				cardRect.x() + inset,
				preview.bottom() + inset,
				cardRect.width() - inset * 2,
				Math.max(12, cardRect.bottom() - preview.bottom() - inset * 2)
		);
	}

	static UiRect mediaGalleryCardTitleRect(UiRect cardRect, UiLayout layout) {
		UiRect preview = mediaGalleryCardPreviewRect(cardRect, layout);
		int inset = clampInt(layout.unit() / 3, 3, 6);
		return new UiRect(
				cardRect.x() + inset,
				preview.bottom() + inset / 2,
				cardRect.width() - inset * 2,
				Math.max(12, cardRect.bottom() - preview.bottom() - inset * 2)
		);
	}

	static UiRect mediaGalleryCardPlayBadgeRect(UiRect previewRect, UiLayout layout) {
		int size = clampInt(layout.unit() * 2, 18, 30);
		return new UiRect(previewRect.right() - size - clampInt(layout.unit() / 3, 3, 6), previewRect.y() + clampInt(layout.unit() / 3, 3, 6), size, size);
	}

	static UiRect mediaGalleryCardStatusBadgeRect(UiRect cardRect, UiLayout layout) {
		UiRect previewRect = sberDronesGalleryCardPreviewRect(cardRect, layout);
		int inset = clampInt(layout.unit() / 3, 3, 6);
		int height = clampInt(layout.unit() + 4, 14, 20);
		int width = height;
		UiRect unlinkRect = mediaGalleryCardDisconnectRect(cardRect, layout);
		return new UiRect(
				unlinkRect.x() - width,
				previewRect.y() + inset,
				width,
				height
		);
	}

	static UiRect mediaGalleryCardSourceBadgeRect(UiRect cardRect, UiLayout layout) {
		UiRect previewRect = sberDronesGalleryCardPreviewRect(cardRect, layout);
		int inset = clampInt(layout.unit() / 3, 3, 6);
		int height = clampInt(layout.unit() + 4, 14, 20);
		int resolvedWidth = height;
		return new UiRect(
				previewRect.x() + inset,
				previewRect.y() + inset,
				resolvedWidth,
				height
		);
	}

	static UiRect mediaGalleryCardDisconnectRect(UiRect cardRect, UiLayout layout) {
		UiRect previewRect = sberDronesGalleryCardPreviewRect(cardRect, layout);
		int inset = clampInt(layout.unit() / 3, 3, 6);
		int height = clampInt(layout.unit() + 4, 14, 20);
		return new UiRect(
				previewRect.right() - height - inset,
				previewRect.y() + inset,
				height,
				height
		);
	}

	static UiRect mediaGalleryBrowserScrollbarTrackRect(UiLayout layout) {
		UiRect linkRect = mediaGalleryBrowserLinkRect(layout);
		UiRect canvas = mediaCanvasRect(layout);
		int inset = clampInt(layout.unit() / 2, 4, 8);
		UiRect viewport = new UiRect(
				canvas.x() + inset,
				linkRect.bottom() + inset,
				canvas.width() - inset * 2,
				Math.max(18, canvas.bottom() - linkRect.bottom() - inset * 2)
		);
		return scrollTrackRect(viewport, layout);
	}

	static UiRect mediaGalleryBrowserScrollbarThumbRect(UiLayout layout, int scroll, int visibleRows, int totalRows) {
		return scrollbarThumbRect(mediaGalleryBrowserScrollbarTrackRect(layout), scroll, visibleRows, totalRows);
	}

	static int mediaYoutubeMusicInfoGap(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? Math.max(3, layout.unit()) : clampInt(layout.unit() * 3 / 4, 6, 12);
	}

	static int mediaYoutubeMusicTimelineGap(UiLayout layout) {
		return youtubeMusicMicroLayout(layout)
				? 3
				: ultraCompactScreenLayout(layout) ? Math.max(3, layout.unit()) : clampInt(layout.unit(), 8, 18);
	}

	static int mediaYoutubeMusicTimeGap(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? 2 : clampInt(layout.unit() / 3, 2, 6);
	}

	static int mediaYoutubeMusicControlsGap(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? 3 : clampInt(layout.unit() * 3 / 4, 6, 12);
	}

	static int mediaYoutubeMusicActionsGap(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? 3 : clampInt(layout.unit() * 3 / 4, 6, 12);
	}

	static int mediaYoutubeMusicArtworkMinSize(UiLayout layout) {
		return ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 7, 32, 48)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 8, 54, 86)
				: clampInt(layout.unit() * 10, 88, 176);
	}

	static int mediaYoutubeMusicArtworkSize(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		if (youtubeMusicMicroLayout(layout)) {
			return clampInt(
					(int) Math.round(Math.min(canvas.width() * 0.40D, canvas.height() * 0.38D)),
					34,
					46
			);
		}
		int minSize = mediaYoutubeMusicArtworkMinSize(layout);
		if (youtubeMusicLandscapeLayout(layout)) {
			int availableHeight = Math.max(
					minSize,
					canvas.height() - mediaYoutubeMusicActionsRowHeight(layout) - mediaYoutubeMusicActionsGap(layout)
			);
			int maxWidth = Math.max(minSize, canvas.width() / 3);
			int preferredSize = (int) Math.round(Math.min(canvas.width() * 0.28D, availableHeight));
			return clampInt(preferredSize, minSize, Math.max(minSize, Math.min(maxWidth, availableHeight)));
		}
		int sideInset = ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit()) : clampInt(layout.unit() * 2, 12, 28);
		int reservedBelowArtwork = mediaYoutubeMusicInfoGap(layout)
				+ mediaYoutubeMusicInfoHeight(layout)
				+ mediaYoutubeMusicTimelineGap(layout)
				+ mediaYoutubeMusicTrackHeight(layout)
				+ mediaYoutubeMusicTimeGap(layout)
				+ mediaYoutubeMusicTimeRowHeight(layout)
				+ mediaYoutubeMusicControlsGap(layout)
				+ mediaYoutubeMusicControlsRowHeight(layout)
				+ mediaYoutubeMusicActionsGap(layout)
				+ mediaYoutubeMusicActionsRowHeight(layout);
		int maxWidth = Math.max(minSize, canvas.width() - sideInset * 2);
		int availableHeight = Math.max(minSize, canvas.height() - reservedBelowArtwork);
		int preferredSize = (int) Math.round(Math.min(maxWidth, canvas.height() * 0.40D));
		return clampInt(preferredSize, minSize, Math.max(minSize, Math.min(maxWidth, availableHeight)));
	}

	static int mediaYoutubeMusicContentTop(UiLayout layout, int artworkSize) {
		UiRect canvas = mediaCanvasRect(layout);
		int contentHeight;
		if (youtubeMusicMicroLayout(layout)) {
			contentHeight = Math.max(artworkSize, mediaYoutubeMusicInfoHeight(layout))
					+ mediaYoutubeMusicTimelineGap(layout)
					+ mediaYoutubeMusicTrackHeight(layout)
					+ mediaYoutubeMusicTimeGap(layout)
					+ mediaYoutubeMusicTimeRowHeight(layout)
					+ mediaYoutubeMusicControlsGap(layout)
					+ mediaYoutubeMusicControlsRowHeight(layout)
					+ mediaYoutubeMusicActionsGap(layout)
					+ mediaYoutubeMusicActionsRowHeight(layout);
		} else if (youtubeMusicLandscapeLayout(layout)) {
			contentHeight = artworkSize + mediaYoutubeMusicActionsGap(layout) + mediaYoutubeMusicActionsRowHeight(layout);
		} else {
			contentHeight = artworkSize
					+ mediaYoutubeMusicInfoGap(layout)
					+ mediaYoutubeMusicInfoHeight(layout)
					+ mediaYoutubeMusicTimelineGap(layout)
					+ mediaYoutubeMusicTrackHeight(layout)
					+ mediaYoutubeMusicTimeGap(layout)
					+ mediaYoutubeMusicTimeRowHeight(layout)
					+ mediaYoutubeMusicControlsGap(layout)
					+ mediaYoutubeMusicControlsRowHeight(layout)
					+ mediaYoutubeMusicActionsGap(layout)
					+ mediaYoutubeMusicActionsRowHeight(layout);
		}
		return canvas.y() + Math.max(0, (canvas.height() - contentHeight) / 2);
	}

	static UiRect mediaYoutubeMusicArtworkRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		int size = mediaYoutubeMusicArtworkSize(layout);
		int top = mediaYoutubeMusicContentTop(layout, size);
		if (youtubeMusicMicroLayout(layout)) {
			return new UiRect(canvas.x(), top, size, size);
		}
		if (youtubeMusicLandscapeLayout(layout)) {
			int sideInset = ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit()) : clampInt(layout.unit() * 2, 12, 28);
			return new UiRect(canvas.right() - sideInset - size, top, size, size);
		}
		return new UiRect(canvas.x() + (canvas.width() - size) / 2, top, size, size);
	}

	static UiRect mediaQueuePanelRect(UiLayout layout) {
		return centeredOverlayPanelRect(
				layout,
				ultraCompactScreenLayout(layout) ? 13.0D / 16.0D : compactScreenLayout(layout) ? 4.0D / 5.0D : 5.0D / 6.0D,
				ultraCompactScreenLayout(layout) ? 5.0D / 8.0D : compactScreenLayout(layout) ? 11.0D / 16.0D : 4.0D / 5.0D,
				86,
				62
		);
	}

	static UiRect galleryDeleteConfirmPanelRect(UiLayout layout) {
		return galleryFileMenuPanelRect(layout);
	}

	static UiRect galleryFileMenuPanelRect(UiLayout layout) {
		if (ultraCompactScreenLayout(layout)) {
			UiRect canvas = mediaCanvasRect(layout);
			int inset = Math.max(2, layout.unit() / 3);
			int top = canvas.y() + clampInt(layout.unit() * 4, 18, 22);
			return new UiRect(canvas.x() + inset, top, canvas.width() - inset * 2, Math.max(42, canvas.bottom() - top - inset));
		}
		return centeredOverlayPanelRect(
				layout,
				ultraCompactScreenLayout(layout) ? 13.0D / 16.0D : compactScreenLayout(layout) ? 3.0D / 4.0D : 2.0D / 3.0D,
				ultraCompactScreenLayout(layout) ? 7.0D / 12.0D : compactScreenLayout(layout) ? 13.0D / 24.0D : 7.0D / 12.0D,
				92,
				76
		);
	}

	static UiRect playerBackgroundPanelRect(UiLayout layout) {
		if (ultraCompactScreenLayout(layout)) {
			UiRect canvas = mediaCanvasRect(layout);
			int inset = Math.max(2, layout.unit() / 3);
			int top = canvas.y() + clampInt(layout.unit() * 4, 18, 22);
			return new UiRect(canvas.x() + inset, top, canvas.width() - inset * 2, Math.max(42, canvas.bottom() - top - inset));
		}
		return centeredOverlayPanelRect(
				layout,
				ultraCompactScreenLayout(layout) ? 13.0D / 16.0D : compactScreenLayout(layout) ? 3.0D / 4.0D : 11.0D / 16.0D,
				ultraCompactScreenLayout(layout) ? 11.0D / 24.0D : compactScreenLayout(layout) ? 1.0D / 2.0D : 13.0D / 24.0D,
				88,
				70
		);
	}

	static UiRect gallerySlideshowPanelRect(UiLayout layout) {
		if (ultraCompactScreenLayout(layout)) {
			UiRect canvas = mediaCanvasRect(layout);
			int inset = Math.max(2, layout.unit() / 3);
			int top = canvas.y() + clampInt(layout.unit() * 4, 18, 22);
			return new UiRect(canvas.x() + inset, top, canvas.width() - inset * 2, Math.max(34, Math.min(canvas.bottom() - top - inset, clampInt(layout.unit() * 10, 50, 70))));
		}
		return centeredOverlayPanelRect(
				layout,
				compactScreenLayout(layout) ? 3.0D / 4.0D : 11.0D / 16.0D,
				compactScreenLayout(layout) ? 1.0D / 3.0D : 7.0D / 24.0D,
				88,
				46
		);
	}

	static UiRect centeredOverlayPanelRect(UiLayout layout, double widthFraction, double heightFraction, int minWidth, int minHeight) {
		UiRect canvas = mediaCanvasRect(layout);
		int width = clampInt((int) Math.round(canvas.width() * widthFraction), minWidth, canvas.width() - layout.unit() * 2);
		int height = clampInt((int) Math.round(canvas.height() * heightFraction), minHeight, canvas.height() - layout.unit() * 2);
		return new UiRect(
				canvas.x() + (canvas.width() - width) / 2,
				canvas.y() + (canvas.height() - height) / 2,
				width,
				height
		);
	}

	static UiRect overlayWindowRect(UiLayout layout, MediaOverlayWindowType type) {
		return switch (type) {
			case YOUTUBE_QUEUE -> mediaQueuePanelRect(layout);
			case GALLERY_DELETE_CONFIRM -> galleryDeleteConfirmPanelRect(layout);
			case GALLERY_FILE_MENU -> mediaCanvasRect(layout);
			case PLAYER_BACKGROUND -> mediaCanvasRect(layout);
			case GALLERY_SLIDESHOW -> mediaCanvasRect(layout);
		};
	}

	static UiRect mediaQueueHeaderRect(UiLayout layout) {
		UiRect panel = overlayWindowRect(layout, MediaOverlayWindowType.YOUTUBE_QUEUE);
		int inset = clampInt(layout.unit() / 2, 4, 8);
		int height = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 3, 22, 30)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 3 + 2, 28, 40)
				: clampInt(layout.unit() * 4, 34, 54);
		return new UiRect(panel.x() + inset, panel.y() + inset, panel.width() - inset * 2, height);
	}

	static UiRect mediaQueueCloseRect(UiLayout layout) {
		UiRect footer = mediaQueueFooterRect(layout);
		int size = Math.max(16, footer.height() - clampInt(layout.unit(), 8, 14));
		return new UiRect(footer.x() + (footer.width() - size) / 2, footer.y() + (footer.height() - size) / 2, size, size);
	}

	static UiRect mediaQueueHeaderTitleRect(UiLayout layout) {
		UiRect header = mediaQueueHeaderRect(layout);
		return new UiRect(
				header.x() + clampInt(layout.unit(), 8, 16),
				header.y() + clampInt(layout.unit() / 3, 2, 6),
				header.width() - clampInt(layout.unit() * 2, 12, 24),
				Math.max(12, header.height() / 2 - 2)
		);
	}

	static UiRect mediaQueueHeaderSubtitleRect(UiLayout layout) {
		UiRect header = mediaQueueHeaderRect(layout);
		int x = header.x() + clampInt(layout.unit(), 8, 16);
		int y = header.y() + header.height() / 2 - 1;
		return new UiRect(
				x,
				y,
				header.width() - clampInt(layout.unit() * 2, 12, 24),
				Math.max(12, header.bottom() - y - clampInt(layout.unit() / 4, 2, 4))
		);
	}

	static UiRect mediaQueueListRect(UiLayout layout) {
		UiRect panel = overlayWindowRect(layout, MediaOverlayWindowType.YOUTUBE_QUEUE);
		UiRect header = mediaQueueHeaderRect(layout);
		UiRect footer = mediaQueueFooterRect(layout);
		int top = header.bottom() + clampInt(layout.unit() / 2, 4, 8);
		int bottom = footer.y() - clampInt(layout.unit() / 2, 4, 8);
		UiRect viewport = new UiRect(panel.x() + clampInt(layout.unit() / 2, 4, 8), top, panel.width() - clampInt(layout.unit(), 8, 16), Math.max(20, bottom - top));
		return scrollContentRect(viewport, layout);
	}

	static UiRect mediaQueueFooterRect(UiLayout layout) {
		UiRect panel = overlayWindowRect(layout, MediaOverlayWindowType.YOUTUBE_QUEUE);
		int inset = clampInt(layout.unit() / 2, 4, 8);
		int height = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 3, 22, 30)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 3 + 1, 28, 38)
				: clampInt(layout.unit() * 4 - 2, 34, 48);
		return new UiRect(panel.x() + inset, panel.bottom() - height - inset, panel.width() - inset * 2, height);
	}

	static UiRect mediaQueueShuffleRect(UiLayout layout) {
		UiRect footer = mediaQueueFooterRect(layout);
		int size = Math.max(16, footer.height() - clampInt(layout.unit(), 8, 14));
		return new UiRect(
				footer.x() + clampInt(layout.unit() / 2, 4, 8),
				footer.y() + (footer.height() - size) / 2,
				size,
				size
		);
	}

	static UiRect mediaQueueRepeatRect(UiLayout layout) {
		UiRect footer = mediaQueueFooterRect(layout);
		int size = Math.max(16, footer.height() - clampInt(layout.unit(), 8, 14));
		return new UiRect(
				footer.right() - size - clampInt(layout.unit() / 2, 4, 8),
				footer.y() + (footer.height() - size) / 2,
				size,
				size
		);
	}

	static UiRect mediaQueueFooterInfoRect(UiLayout layout) {
		UiRect footer = mediaQueueFooterRect(layout);
		return new UiRect(
				footer.x() + clampInt(layout.unit() / 2, 4, 8),
				footer.y(),
				footer.width() - clampInt(layout.unit(), 8, 16),
				footer.height()
		);
	}

	static UiRect galleryDeleteConfirmHeaderRect(UiLayout layout) {
		return galleryFileMenuHeaderRect(layout);
	}

	static UiRect galleryDeleteConfirmCloseRect(UiLayout layout) {
		return galleryFileMenuCloseRect(layout);
	}

	static UiRect galleryDeleteConfirmBodyRect(UiLayout layout) {
		return galleryDeleteConfirmPanelRect(layout);
	}

	static UiRect galleryDeleteConfirmButtonsRowRect(UiLayout layout) {
		return galleryDeleteConfirmActionRect(layout, 1);
	}

	static UiRect galleryDeleteConfirmInfoRect(UiLayout layout) {
		return galleryDeleteConfirmActionRect(layout, 0);
	}

	static UiRect galleryDeleteConfirmCancelRect(UiLayout layout) {
		return galleryDeleteConfirmActionRect(layout, 1);
	}

	static UiRect galleryDeleteConfirmConfirmRect(UiLayout layout) {
		return galleryDeleteConfirmActionRect(layout, 2);
	}

	static UiRect galleryDeleteConfirmActionRect(UiLayout layout, int index) {
		UiRect body = galleryDeleteConfirmBodyRect(layout);
		int actionCount = 3;
		int safeIndex = clampInt(index, 0, actionCount - 1);
		int gap = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 4) : clampInt(layout.unit() / 2, 4, 8);
		int height = Math.max(16, (body.height() - gap * (actionCount - 1)) / actionCount);
		return new UiRect(
				body.x(),
				body.y() + safeIndex * (height + gap),
				body.width(),
				height
		);
	}

	static UiRect galleryFileMenuHeaderRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		UiRect panel = galleryFileMenuPanelRect(layout);
		int height = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 2 + 4, 12, 16)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 5, 34, 52)
				: clampInt(layout.unit() * 6, 44, 66);
		int gap = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 4) : clampInt(layout.unit(), 8, 16);
		int y = Math.max(canvas.y() + (ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit() / 3) : clampInt(layout.unit() / 2, 4, 8)), panel.y() - height - gap);
		return new UiRect(panel.x(), y, panel.width(), height);
	}

	static UiRect galleryFileMenuCloseRect(UiLayout layout) {
		UiRect header = galleryFileMenuHeaderRect(layout);
		int size = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 2 + 2, 12, 14) : clampInt(layout.unit() * 2 + 2, 22, 34);
		int inset = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 4) : clampInt(layout.unit() / 2, 4, 8);
		return new UiRect(
				header.right() - size - inset,
				header.y() + Math.max(0, (header.height() - size) / 2),
				size,
				size
		);
	}

	static UiRect galleryFileMenuBodyRect(UiLayout layout) {
		return galleryFileMenuPanelRect(layout);
	}

	static UiRect galleryFileMenuActionRect(UiLayout layout, int index) {
		return galleryFileMenuActionRect(layout, index, 5);
	}

	static UiRect galleryFileMenuActionRect(UiLayout layout, int index, int actionCount) {
		UiRect body = galleryFileMenuBodyRect(layout);
		int count = Math.max(1, actionCount);
		int safeIndex = clampInt(index, 0, count - 1);
		int gap = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 4) : clampInt(layout.unit() / 2, 4, 8);
		int height = Math.max(16, (body.height() - gap * (count - 1)) / count);
		return new UiRect(
				body.x(),
				body.y() + safeIndex * (height + gap),
				body.width(),
				height
		);
	}

	static UiRect playerBackgroundHeaderRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		UiRect panel = playerBackgroundPanelRect(layout);
		int height = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 2 + 4, 12, 16)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 5, 34, 52)
				: clampInt(layout.unit() * 6, 44, 66);
		int gap = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 4) : clampInt(layout.unit(), 8, 16);
		int y = Math.max(canvas.y() + (ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit() / 3) : clampInt(layout.unit() / 2, 4, 8)), panel.y() - height - gap);
		return new UiRect(panel.x(), y, panel.width(), height);
	}

	static UiRect playerBackgroundCloseRect(UiLayout layout) {
		UiRect header = playerBackgroundHeaderRect(layout);
		int size = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 2 + 2, 12, 14) : clampInt(layout.unit() * 2 + 2, 22, 34);
		int inset = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 4) : clampInt(layout.unit() / 2, 4, 8);
		return new UiRect(
				header.right() - size - inset,
				header.y() + Math.max(0, (header.height() - size) / 2),
				size,
				size
		);
	}

	static UiRect playerBackgroundBodyRect(UiLayout layout) {
		return playerBackgroundPanelRect(layout);
	}

	static UiRect gallerySlideshowHeaderRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		UiRect panel = gallerySlideshowPanelRect(layout);
		int height = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 2 + 4, 12, 16)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 5, 34, 52)
				: clampInt(layout.unit() * 6, 44, 66);
		int gap = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 4) : clampInt(layout.unit(), 8, 16);
		int y = Math.max(canvas.y() + (ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit() / 3) : clampInt(layout.unit() / 2, 4, 8)), panel.y() - height - gap);
		return new UiRect(panel.x(), y, panel.width(), height);
	}

	static UiRect gallerySlideshowCloseRect(UiLayout layout) {
		UiRect header = gallerySlideshowHeaderRect(layout);
		int size = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 2 + 2, 12, 14) : clampInt(layout.unit() * 2 + 2, 22, 34);
		int inset = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 4) : clampInt(layout.unit() / 2, 4, 8);
		return new UiRect(
				header.right() - size - inset,
				header.y() + Math.max(0, (header.height() - size) / 2),
				size,
				size
		);
	}

	static UiRect gallerySlideshowBodyRect(UiLayout layout) {
		return gallerySlideshowPanelRect(layout);
	}

	static UiRect gallerySlideshowToggleRect(UiLayout layout) {
		UiRect body = gallerySlideshowBodyRect(layout);
		int gap = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 3) : clampInt(layout.unit() / 2, 4, 8);
		int height = ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 5, 26, 34)
				: compactScreenLayout(layout)
				? clampInt(layout.unit() * 5 + 4, 34, 46)
				: clampInt(layout.unit() * 6, 44, 58);
		return new UiRect(body.x(), body.y(), body.width(), Math.min(body.height(), height));
	}

	static UiRect gallerySlideshowDurationRect(UiLayout layout) {
		UiRect body = gallerySlideshowBodyRect(layout);
		return new UiRect(
				body.x(),
				body.y(),
				body.width(),
				Math.max(18, body.height())
		);
	}

	static UiRect gallerySlideshowDurationTrackRect(UiLayout layout) {
		UiRect durationRect = gallerySlideshowDurationRect(layout);
		int sideInset = ultraCompactScreenLayout(layout) ? Math.max(8, layout.unit() * 2) : clampInt(layout.unit() * 3, 22, 42);
		int verticalInset = ultraCompactScreenLayout(layout) ? Math.max(10, layout.unit() * 2) : clampInt(layout.unit() * 3, 22, 36);
		int y = clampInt(durationRect.y() + durationRect.height() / 2, durationRect.y() + verticalInset / 2, Math.max(durationRect.y() + verticalInset / 2, durationRect.bottom() - verticalInset / 2));
		return new UiRect(
				durationRect.x() + sideInset,
				y - Math.max(6, layout.unit()),
				Math.max(12, durationRect.width() - sideInset * 2),
				Math.max(12, layout.unit() * 2)
		);
	}

	static UiRect gallerySlideshowToggleIconRect(UiLayout layout) {
		UiRect toggleRect = gallerySlideshowToggleRect(layout);
		int size = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 3, 16, 20) : clampInt(layout.unit() * 4, 24, 34);
		int inset = ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit() / 3) : clampInt(layout.unit(), 8, 16);
		return new UiRect(
				toggleRect.right() - size - inset,
				toggleRect.y() + (toggleRect.height() - size) / 2,
				size,
				size
		);
	}

	static UiRect gallerySlideshowDurationOptionRect(UiLayout layout, int index, int total) {
		UiRect durationRect = gallerySlideshowDurationRect(layout);
		int safeTotal = Math.max(1, total);
		int safeIndex = clampInt(index, 0, safeTotal - 1);
		int sideInset = ultraCompactScreenLayout(layout) ? Math.max(4, layout.unit()) : clampInt(layout.unit() * 2, 14, 26);
		int topInset = ultraCompactScreenLayout(layout) ? Math.max(4, layout.unit()) : clampInt(layout.unit() * 2, 14, 24);
		int trackWidth = Math.max(24, durationRect.width() - sideInset * 2);
		int slotWidth = Math.max(16, trackWidth / safeTotal);
		int centerX = durationRect.x() + sideInset + Math.round((safeIndex + 0.5F) * trackWidth / safeTotal);
		int width = Math.max(slotWidth, ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 4, 20, 28) : clampInt(layout.unit() * 5, 30, 44));
		int height = Math.max(18, durationRect.height() - topInset);
		return new UiRect(
				clampInt(centerX - width / 2, durationRect.x(), Math.max(durationRect.x(), durationRect.right() - width)),
				durationRect.y() + topInset,
				width,
				height
		);
	}

	static UiRect playerBackgroundOptionRect(UiLayout layout, int index) {
		UiRect body = playerBackgroundBodyRect(layout);
		int optionCount = 4;
		int safeIndex = clampInt(index, 0, optionCount - 1);
		int gap = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 4) : clampInt(layout.unit() / 2, 4, 8);
		int height = Math.max(16, (body.height() - gap * (optionCount - 1)) / optionCount);
		return new UiRect(
				body.x(),
				body.y() + safeIndex * (height + gap),
				body.width(),
				height
		);
	}

	static int playerBackgroundScaleButtonReserveWidth(UiLayout layout) {
		return playerBackgroundScaleButtonWidth(layout) + clampInt(layout.unit(), 8, 14);
	}

	static int playerBackgroundScaleButtonWidth(UiLayout layout) {
		return ultraCompactScreenLayout(layout)
				? clampInt(layout.unit() * 2 + 2, 12, 14)
				: clampInt(layout.unit() * 2 + 2, 18, 28);
	}

	static UiRect playerBackgroundScaleButtonRect(UiLayout layout, PlayerBackgroundMode mode) {
		UiRect option = playerBackgroundOptionRect(layout, mode == PlayerBackgroundMode.ARTWORK ? 0 : 1);
		int gap = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 4) : clampInt(layout.unit() / 2, 4, 8);
		int buttonHeight = Math.max(ultraCompactScreenLayout(layout) ? 12 : 14, option.height() - gap * 2);
		int buttonWidth = playerBackgroundScaleButtonWidth(layout);
		return new UiRect(
				option.right() - buttonWidth - gap,
				option.y() + (option.height() - buttonHeight) / 2,
				buttonWidth,
				buttonHeight
		);
	}

	static boolean playerBackgroundModeHasScaleButton(PlayerBackgroundMode mode) {
		return mode == PlayerBackgroundMode.ARTWORK || mode == PlayerBackgroundMode.GALLERY;
	}

	static boolean playerBackgroundScaleButtonContains(UiLayout layout, PlayerBackgroundMode mode, UiPoint touchPoint) {
		return layout != null
				&& touchPoint != null
				&& playerBackgroundModeHasScaleButton(mode)
				&& playerBackgroundScaleButtonRect(layout, mode).contains(touchPoint.x(), touchPoint.y());
	}

	static PlayerBackgroundMode playerBackgroundModeForTouch(UiLayout layout, UiPoint touchPoint) {
		if (layout == null || touchPoint == null) {
			return null;
		}
		if (playerBackgroundOptionRect(layout, 0).contains(touchPoint.x(), touchPoint.y())) {
			return PlayerBackgroundMode.ARTWORK;
		}
		if (playerBackgroundOptionRect(layout, 1).contains(touchPoint.x(), touchPoint.y())) {
			return PlayerBackgroundMode.GALLERY;
		}
		if (playerBackgroundOptionRect(layout, 2).contains(touchPoint.x(), touchPoint.y())) {
			return PlayerBackgroundMode.BLACK;
		}
		if (playerBackgroundOptionRect(layout, 3).contains(touchPoint.x(), touchPoint.y())) {
			return PlayerBackgroundMode.EMPTY;
		}
		return null;
	}

	static UiRect mediaQueueScrollbarTrackRect(UiLayout layout) {
		UiRect panel = overlayWindowRect(layout, MediaOverlayWindowType.YOUTUBE_QUEUE);
		UiRect header = mediaQueueHeaderRect(layout);
		UiRect footer = mediaQueueFooterRect(layout);
		int top = header.bottom() + clampInt(layout.unit() / 2, 4, 8);
		int bottom = footer.y() - clampInt(layout.unit() / 2, 4, 8);
		UiRect viewport = new UiRect(panel.x() + clampInt(layout.unit() / 2, 4, 8), top, panel.width() - clampInt(layout.unit(), 8, 16), Math.max(20, bottom - top));
		return scrollTrackRect(viewport, layout);
	}

	static UiRect mediaQueueScrollbarThumbRect(UiLayout layout, int scroll, int visibleRows, int totalRows) {
		return scrollbarThumbRect(mediaQueueScrollbarTrackRect(layout), scroll, visibleRows, totalRows);
	}

	static int mediaQueueVisibleRows(UiLayout layout) {
		UiRect list = mediaQueueListRect(layout);
		int rowHeight = mediaQueueRowHeight(layout);
		return Math.max(1, list.height() / Math.max(1, rowHeight));
	}

	static int mediaQueueRowHeight(UiLayout layout) {
		if (ultraCompactScreenLayout(layout)) {
			return clampInt(layout.unit() * 5, 30, 40);
		}
		if (compactScreenLayout(layout)) {
			return clampInt(layout.unit() * 5, 38, 52);
		}
		return clampInt(layout.unit() * 6, 52, 86);
	}

	static UiRect mediaQueueRowRect(UiLayout layout, int visibleIndex) {
		UiRect list = mediaQueueListRect(layout);
		int rowHeight = mediaQueueRowHeight(layout);
		return new UiRect(list.x(), list.y() + visibleIndex * rowHeight, list.width(), Math.max(24, rowHeight));
	}

	static UiRect mediaQueueRemoveRect(UiRect rowRect, UiLayout layout) {
		int size = clampInt(rowRect.height() - clampInt(layout.unit(), 10, 16), 18, 30);
		int rightInset = clampInt(layout.unit() / 2, 4, 8);
		return new UiRect(rowRect.right() - size - rightInset, rowRect.y() + (rowRect.height() - size) / 2, size, size);
	}

	static UiRect mediaQueueCacheStatusRect(UiRect rowRect, UiRect removeRect, UiLayout layout) {
		int size = Math.max(14, removeRect.width());
		int gap = clampInt(layout.unit() / 3, 3, 6);
		return new UiRect(removeRect.x() - gap - size, removeRect.y(), size, size);
	}

	static UiRect mediaQueueThumbnailRect(UiRect rowRect, BufferedImage preview, boolean squareFallback, UiLayout layout) {
		if (rowRect == null || layout == null || rowRect.width() <= 0 || rowRect.height() <= 0) {
			return new UiRect(0, 0, 0, 0);
		}
		int leftInset = clampInt(layout.unit(), 8, 14);
		int verticalInset = clampInt(layout.unit() / 2, 4, 8);
		int maxHeight = Math.max(12, rowRect.height() - verticalInset * 2);
		double aspect = queueThumbnailAspect(preview, squareFallback);
		int availableWidth = Math.max(0, rowRect.width() - leftInset * 2);
		if (availableWidth < 8) {
			return new UiRect(0, 0, 0, 0);
		}
		int maxWidth = Math.min(Math.max(12, rowRect.width() / 3), availableWidth);
		int width = Math.max(12, (int) Math.round(maxHeight * aspect));
		int height = maxHeight;
		if (width > maxWidth) {
			width = maxWidth;
			height = Math.max(12, (int) Math.round(width / aspect));
		}
		return new UiRect(
				rowRect.x() + leftInset,
				rowRect.y() + (rowRect.height() - height) / 2,
				width,
				height
		);
	}

	static double queueThumbnailAspect(BufferedImage preview, boolean squareFallback) {
		if (preview != null && preview.getWidth() > 0 && preview.getHeight() > 0) {
			return clampDouble(preview.getWidth() / (double) preview.getHeight(), 0.75D, 2.2D);
		}
		return squareFallback ? 1.0D : 16.0D / 9.0D;
	}

	static UiRect mediaQueueTitleRect(UiRect rowRect, UiRect rightControlRect, UiLayout layout) {
		return mediaQueueTitleRect(rowRect, rightControlRect, null, layout);
	}

	static UiRect mediaQueueTitleRect(UiRect rowRect, UiRect rightControlRect, UiRect previewRect, UiLayout layout) {
		int leftInset = clampInt(layout.unit(), 8, 14);
		int x = previewRect != null && previewRect.width() > 0
				? previewRect.right() + clampInt(layout.unit() / 2, 4, 8)
				: rowRect.x() + leftInset;
		int width = Math.max(18, rightControlRect.x() - x - clampInt(layout.unit(), 8, 12));
		return new UiRect(
				x,
				rowRect.y() + clampInt(layout.unit() / 2, 4, 8),
				width,
				Math.max(16, rowRect.height() / 2 - clampInt(layout.unit() / 3, 2, 6))
		);
	}

	static UiRect mediaQueueMetaRect(UiRect rowRect, UiRect rightControlRect, UiLayout layout) {
		return mediaQueueMetaRect(rowRect, rightControlRect, null, layout);
	}

	static UiRect mediaQueueMetaRect(UiRect rowRect, UiRect rightControlRect, UiRect previewRect, UiLayout layout) {
		int leftInset = clampInt(layout.unit(), 8, 14);
		int x = previewRect != null && previewRect.width() > 0
				? previewRect.right() + clampInt(layout.unit() / 2, 4, 8)
				: rowRect.x() + leftInset;
		int width = Math.max(18, rightControlRect.x() - x - clampInt(layout.unit(), 8, 12));
		int y = rowRect.y() + rowRect.height() / 2;
		return new UiRect(
				x,
				y,
				width,
				Math.max(14, rowRect.bottom() - y - clampInt(layout.unit() / 2, 4, 8))
		);
	}

	static UiRect mediaPlayPauseRect(UiLayout layout) {
		return mediaPlayPauseRect(layout, ScreenViewMode.HOME);
	}

	static UiRect mediaPlayPauseRect(UiLayout layout, MediaRuntimeState state) {
		UiRect timeline = mediaTimelineRect(layout, state);
		ScreenViewMode mode = mediaChromeMode(state);
		if (isYoutubeMusicMode(mode)) {
			return new UiRect(timeline.x(), timeline.y(), 0, 0);
		}
		int size = Math.max(10, timeline.height() - 2);
		return new UiRect(timeline.x() + 2, timeline.y() + (timeline.height() - size) / 2, size, size);
	}

	static UiRect mediaPlayPauseRect(UiLayout layout, ScreenViewMode mode) {
		UiRect timeline = mediaTimelineRect(layout, mode);
		if (isYoutubeMusicMode(mode)) {
			return new UiRect(timeline.x(), timeline.y(), 0, 0);
		}
		int size = Math.max(10, timeline.height() - 2);
		return new UiRect(timeline.x() + 2, timeline.y() + (timeline.height() - size) / 2, size, size);
	}

	static UiRect mediaTimelineCounterRect(UiLayout layout) {
		return mediaTimelineCounterRect(layout, ScreenViewMode.HOME);
	}

	static UiRect mediaTimelineCounterRect(UiLayout layout, MediaRuntimeState state) {
		ScreenViewMode mode = mediaChromeMode(state);
		if (isYoutubeMusicMode(mode)) {
			UiRect timeline = mediaTimelineRect(layout, mode);
			return new UiRect(timeline.right(), timeline.y(), 0, timeline.height());
		}
		UiRect timeline = mediaTimelineRect(layout, state);
		int width = timelineCounterReservedWidth(layout, resolvedTimelineLabelLocked(state));
		int inset = timelineCounterGap(layout);
		return new UiRect(timeline.right() - width - inset, timeline.y(), width, timeline.height());
	}

	static UiRect mediaTimelineCounterRect(UiLayout layout, MediaVisualSnapshot state) {
		ScreenViewMode mode = mediaChromeMode(state);
		if (isYoutubeMusicMode(mode)) {
			UiRect timeline = mediaTimelineRect(layout, mode);
			return new UiRect(timeline.right(), timeline.y(), 0, timeline.height());
		}
		UiRect timeline = mediaTimelineRect(layout, state);
		int width = timelineCounterReservedWidth(layout, resolvedTimelineLabel(state, layout));
		int inset = timelineCounterGap(layout);
		return new UiRect(timeline.right() - width - inset, timeline.y(), width, timeline.height());
	}

	static UiRect mediaTimelineCounterRect(UiLayout layout, ScreenViewMode mode) {
		if (isYoutubeMusicMode(mode)) {
			UiRect timeline = mediaTimelineRect(layout, mode);
			return new UiRect(timeline.right(), timeline.y(), 0, timeline.height());
		}
		UiRect timeline = mediaTimelineRect(layout, mode);
		int width = timelineCounterReservedWidth(layout);
		int inset = clampInt(layout.unit() / 2, 4, 8);
		return new UiRect(timeline.right() - width - inset, timeline.y(), width, timeline.height());
	}

	static UiRect mediaTimelineTrackRect(UiLayout layout) {
		return mediaTimelineTrackRect(layout, ScreenViewMode.HOME);
	}

	static UiRect mediaTimelineTrackRect(UiLayout layout, MediaRuntimeState state) {
		ScreenViewMode mode = mediaChromeMode(state);
		UiRect timeline = mediaTimelineRect(layout, state);
		UiRect counter = isYoutubeMusicMode(mode) ? mediaTimelineCounterRect(layout, mode) : mediaTimelineCounterRect(layout, state);
		int sideInset = ultraCompactScreenLayout(layout) ? 2 : clampInt(layout.unit() / 2, 4, 8);
		int counterGap = timelineCounterGap(layout);
		int x;
		int right;
		if (isYoutubeMusicMode(mode)) {
			x = timeline.x() + sideInset;
			right = counter.width() > 0 ? counter.x() - counterGap : timeline.right() - sideInset;
		} else {
			UiRect playPause = mediaPlayPauseRect(layout, state);
			x = playPause.right() + sideInset;
			right = counter.width() > 0 ? counter.x() - counterGap : timeline.right() - sideInset;
		}
		int height = mediaTimelineTrackHeight(layout);
		return new UiRect(x, timeline.y() + timeline.height() / 2 - height / 2, Math.max(12, right - x), height);
	}

	static UiRect mediaTimelineTrackRect(UiLayout layout, MediaVisualSnapshot state) {
		ScreenViewMode mode = mediaChromeMode(state);
		UiRect timeline = mediaTimelineRect(layout, state);
		UiRect counter = mediaTimelineCounterRect(layout, state);
		int sideInset = ultraCompactScreenLayout(layout) ? 2 : clampInt(layout.unit() / 2, 4, 8);
		int counterGap = timelineCounterGap(layout);
		int x;
		int right;
		if (isYoutubeMusicMode(mode)) {
			x = timeline.x() + sideInset;
			right = counter.width() > 0 ? counter.x() - counterGap : timeline.right() - sideInset;
		} else {
			int size = Math.max(10, timeline.height() - 2);
			UiRect playPause = new UiRect(timeline.x() + 2, timeline.y() + (timeline.height() - size) / 2, size, size);
			x = playPause.right() + sideInset;
			right = counter.width() > 0 ? counter.x() - counterGap : timeline.right() - sideInset;
		}
		int height = mediaTimelineTrackHeight(layout);
		return new UiRect(x, timeline.y() + timeline.height() / 2 - height / 2, Math.max(12, right - x), height);
	}

	static UiRect mediaTimelineTrackRect(UiLayout layout, ScreenViewMode mode) {
		UiRect timeline = mediaTimelineRect(layout, mode);
		UiRect counter = mediaTimelineCounterRect(layout, mode);
		int sideInset = ultraCompactScreenLayout(layout) ? 2 : clampInt(layout.unit() / 2, 4, 8);
		int x;
		int right;
		if (isYoutubeMusicMode(mode)) {
			x = timeline.x() + sideInset;
			right = counter.width() > 0
					? counter.x() - sideInset
					: timeline.right() - sideInset;
		} else {
			UiRect playPause = mediaPlayPauseRect(layout, mode);
			x = playPause.right() + sideInset;
			right = counter.width() > 0
					? counter.x() - sideInset
					: timeline.right() - sideInset;
		}
		int height = mediaTimelineTrackHeight(layout);
		return new UiRect(
				x,
				timeline.y() + timeline.height() / 2 - height / 2,
				Math.max(12, right - x),
				height
		);
	}

	static int mediaTimelineTrackHeight(UiLayout layout) {
		if (layout == null) {
			return 2;
		}
		return clampInt((int) Math.round(layout.unit() * 0.40D), 2, 5);
	}

	static int timelineCounterReservedWidth(UiLayout layout) {
		return switch (timelineCounterDetailLevel(layout)) {
			case NONE -> 0;
			case COMPACT -> clampInt((int) Math.round(layout.unit() * 4.2D), 24, 48);
			case FULL -> clampInt((int) Math.round(layout.unit() * 6.8D), 46, 88);
		};
	}

	static int timelineCounterReservedWidth(UiLayout layout, String label) {
		String safeLabel = label != null ? label.trim() : "";
		int dynamicWidth = safeLabel.isBlank()
				? 0
				: (int) Math.round(safeLabel.length() * Math.max(4, layout.unit()) * 0.68D) + Math.max(8, layout.unit());
		return switch (timelineCounterDetailLevel(layout)) {
			case NONE -> 0;
			case COMPACT -> clampInt(Math.max(clampInt((int) Math.round(layout.unit() * 3.3D), 20, 36), dynamicWidth), 20, 46);
			case FULL -> clampInt(Math.max(clampInt((int) Math.round(layout.unit() * 4.8D), 34, 56), dynamicWidth), 34, 82);
		};
	}

	static int timelineCounterGap(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit() / 4) : clampInt(layout.unit() / 3, 2, 6);
	}

	static UiRect mediaTimelineHitRect(UiLayout layout) {
		return mediaTimelineHitRect(layout, ScreenViewMode.HOME);
	}

	static UiRect mediaTimelineHitRect(UiLayout layout, MediaRuntimeState state) {
		ScreenViewMode mode = mediaChromeMode(state);
		if (isYoutubeMusicMode(mode)) {
			UiRect timeline = mediaTimelineRect(layout, mode);
			int extra = clampInt(layout.unit() / 2, 4, 8);
			return new UiRect(timeline.x(), timeline.y() - extra / 2, timeline.width(), timeline.height() + extra);
		}
		return mediaTimelineTrackRect(layout, state);
	}

	static UiRect mediaTimelineHitRect(UiLayout layout, ScreenViewMode mode) {
		if (isYoutubeMusicMode(mode)) {
			UiRect timeline = mediaTimelineRect(layout, mode);
			int extra = clampInt(layout.unit() / 2, 4, 8);
			return new UiRect(timeline.x(), timeline.y() - extra / 2, timeline.width(), timeline.height() + extra);
		}
		return mediaTimelineTrackRect(layout, mode);
	}

	static UiRect mediaCenterPlayPauseRect(UiLayout layout) {
		return mediaCenterPlayPauseRect(layout, ScreenViewMode.HOME);
	}

	static UiRect mediaCenterPlayPauseRect(UiLayout layout, ScreenViewMode mode) {
		if (isYoutubeMusicMode(mode)) {
			UiRect controls = mediaYoutubeMusicControlsRowRect(layout);
			int gap = mediaCenterControlGap(layout, mode);
			// Keep the transport controls as one aligned group.  On very narrow rows
			// reduce all three buttons together instead of shrinking only prev/next.
			int maxSharedHeight = Math.max(1, (controls.width() - gap * 2) / 3);
			int height = Math.min(controls.height(), maxSharedHeight);
			int maxWidth = Math.max(height, controls.width() - height * 2 - gap * 2);
			int width = clampInt(
					Math.max(height, (int) Math.round(controls.width() * (youtubeMusicLandscapeLayout(layout) ? 0.46D : 0.54D))),
					height,
					maxWidth
			);
			return new UiRect(
					controls.x() + (controls.width() - width) / 2,
					controls.y() + (controls.height() - height) / 2,
					width,
					height
			);
		}
		UiRect canvas = mediaCanvasRect(layout);
		int height;
		if (ultraCompactScreenLayout(layout)) {
			height = clampInt(layout.unit() * 5, 20, 28);
		} else if (compactScreenLayout(layout)) {
			height = clampInt(layout.unit() * 5, 26, 46);
		} else {
			height = clampInt(layout.unit() * 6, 46, 92);
		}
		int width = mode == ScreenViewMode.SBER_DRONES
				? clampInt(
						(int) Math.round(canvas.width() * 0.34D),
						height * 3,
						Math.max(height * 3, canvas.width() - layout.unit() * 4)
				)
				: height;
		return new UiRect(
				canvas.x() + (canvas.width() - width) / 2,
				canvas.y() + (canvas.height() - height) / 2,
				width,
				height
		);
	}

	static UiRect mediaCenterBackRect(UiLayout layout) {
		return mediaCenterBackRect(layout, ScreenViewMode.HOME);
	}

	static UiRect mediaCenterBackRect(UiLayout layout, ScreenViewMode mode) {
		UiRect center = mediaCenterPlayPauseRect(layout, mode);
		int size;
		int gap;
		if (isYoutubeMusicMode(mode)) {
			size = mediaCenterSideButtonSize(layout, mode, center.height());
			gap = mediaCenterControlGap(layout, mode);
		} else if (ultraCompactScreenLayout(layout)) {
			size = center.height();
			gap = clampInt(layout.unit(), 8, 12);
		} else if (compactScreenLayout(layout)) {
			size = center.height();
			gap = clampInt(layout.unit() + 2, 10, 18);
		} else {
			size = center.height();
			gap = clampInt(layout.unit() * 2, 14, 28);
		}
		return new UiRect(center.x() - size - gap, center.y() + (center.height() - size) / 2, size, size);
	}

	static UiRect mediaCenterForwardRect(UiLayout layout) {
		return mediaCenterForwardRect(layout, ScreenViewMode.HOME);
	}

	static UiRect mediaCenterForwardRect(UiLayout layout, ScreenViewMode mode) {
		UiRect center = mediaCenterPlayPauseRect(layout, mode);
		int size;
		int gap;
		if (isYoutubeMusicMode(mode)) {
			size = mediaCenterSideButtonSize(layout, mode, center.height());
			gap = mediaCenterControlGap(layout, mode);
		} else if (ultraCompactScreenLayout(layout)) {
			size = center.height();
			gap = clampInt(layout.unit(), 8, 12);
		} else if (compactScreenLayout(layout)) {
			size = center.height();
			gap = clampInt(layout.unit() + 2, 10, 18);
		} else {
			size = center.height();
			gap = clampInt(layout.unit() * 2, 14, 28);
		}
		return new UiRect(center.right() + gap, center.y() + (center.height() - size) / 2, size, size);
	}

	static int mediaCenterSideButtonSize(UiLayout layout, ScreenViewMode mode, int rowHeight) {
		if (isYoutubeMusicMode(mode)) {
			return rowHeight;
		}
		return rowHeight;
	}

	static int mediaCenterControlGap(UiLayout layout, ScreenViewMode mode) {
		if (isYoutubeMusicMode(mode)) {
			return mediaYoutubeMusicActionButtonsGap(layout);
		}
		if (ultraCompactScreenLayout(layout)) {
			return clampInt(layout.unit(), 4, 8);
		}
		if (compactScreenLayout(layout)) {
			return clampInt(layout.unit() + 1, 6, 14);
		}
		return clampInt(layout.unit() * 2, 14, 28);
	}

	static UiRect mediaStatusRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		int width = clampInt(canvas.width() * 2 / 3, 72, canvas.width() - layout.unit() * 4);
		int height = clampInt(layout.unit() * 2, 18, 30);
		return new UiRect(canvas.x() + (canvas.width() - width) / 2, canvas.y() + canvas.height() / 2 - height / 2, width, height);
	}

	static UiRect mediaProgressRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		int width = clampInt(canvas.width() * 2 / 3, 84, canvas.width() - layout.unit() * 4);
		int height = clampInt(layout.unit() * 4, 34, 54);
		return new UiRect(canvas.x() + (canvas.width() - width) / 2, canvas.bottom() - height - layout.unit(), width, height);
	}

	static UiRect mediaPromptRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		int width = clampInt(canvas.width() - layout.unit() * 3, 60, canvas.width());
		int height = clampInt(layout.unit() * 5, 42, 76);
		return new UiRect(canvas.x() + (canvas.width() - width) / 2, canvas.y() + (canvas.height() - height) / 2 - layout.unit(), width, height);
	}

	static UiRect genericAppHeroRect(UiLayout layout) {
		UiRect workspace = workspaceRect(layout);
		UiRect header = mediaHeaderRect(layout);
		return new UiRect(
				workspace.x() + layout.unit(),
				header.bottom() + layout.unit(),
				workspace.width() - layout.unit() * 2,
				workspace.bottom() - header.bottom() - layout.unit() * 2
		);
	}

	static int homeHeaderHeight(UiLayout layout) {
		return clampInt((int) Math.round(layout.unit() * 2.0D), 12, 44);
	}

	static int homeRowsPerPage(UiLayout layout) {
		UiRect content = homeGridRect(layout, homePanelRect(layout));
		int gap = homeAppGap(layout);
		int desiredHeight = homeDesiredCardHeight(layout);
		return Math.max(1, (content.height() + gap) / Math.max(1, desiredHeight + gap));
	}

	static int homeAppGap(UiLayout layout) {
		return clampInt((int) Math.round(layout.unit() * 0.8D), 4, 18);
	}

	static int homeColumns(UiLayout layout) {
		UiRect content = homeGridRect(layout, homePanelRect(layout));
		int gap = homeAppGap(layout);
		int desiredWidth = homeDesiredCardWidth(layout);
		return Math.max(2, (content.width() + gap) / Math.max(1, desiredWidth + gap));
	}

	static int homeAppLabelHeight(UiLayout layout) {
		return clampInt((int) Math.round(layout.unit() * 1.9D), 12, 34);
	}

	static int homeDesiredCardWidth(UiLayout layout) {
		return clampInt((int) Math.round(layout.unit() * 8.5D), 42, 220);
	}

	static int homeDesiredCardHeight(UiLayout layout) {
		return homeAppLabelHeight(layout) + clampInt((int) Math.round(layout.unit() * 7.0D), 28, 150);
	}

	static int homeAppCardWidth(UiLayout layout) {
		UiRect content = homeGridRect(layout, homePanelRect(layout));
		int columns = homeColumns(layout);
		int gap = homeAppGap(layout);
		return Math.max(32, (content.width() - Math.max(0, columns - 1) * gap) / Math.max(1, columns));
	}

	static int homeAppCardHeight(UiLayout layout) {
		UiRect content = homeGridRect(layout, homePanelRect(layout));
		int rows = homeRowsPerPage(layout);
		int gap = homeAppGap(layout);
		int maxHeight = Math.max(32, (content.height() - Math.max(0, rows - 1) * gap) / Math.max(1, rows));
		int desiredHeight = homeDesiredCardHeight(layout);
		return Math.max(32, Math.min(maxHeight, desiredHeight));
	}

	static int homePageCapacity(UiLayout layout) {
		return Math.max(1, homeRowsPerPage(layout) * homeColumns(layout));
	}

	static int homeTotalRows(UiLayout layout) {
		int columns = Math.max(1, homeColumns(layout));
		return Math.max(0, (MonitorAppRegistry.apps().size() + columns - 1) / columns);
	}

	static int homeMaxScroll(UiLayout layout) {
		return Math.max(0, homeTotalRows(layout) - homeRowsPerPage(layout));
	}

	static int homePageCount(UiLayout layout) {
		return Math.max(1, homeMaxScroll(layout) + 1);
	}

	static List<MonitorApp> visibleHomeApps(UiLayout layout, int launcherPage) {
		List<MonitorApp> apps = MonitorAppRegistry.apps();
		int scroll = clampInt(launcherPage, 0, homeMaxScroll(layout));
		int fromIndex = scroll * homeColumns(layout);
		int toIndex = Math.min(apps.size(), fromIndex + homePageCapacity(layout));
		return apps.subList(fromIndex, toIndex);
	}

	static MonitorApp appForViewMode(ScreenViewMode mode) {
		mode = ScreenViewMode.normalize(mode);
		if (mode == null || mode == ScreenViewMode.HOME) {
			return null;
		}
		return MonitorAppRegistry.findById(mode.serializedName());
	}

	static MonitorAppRole appRoleForViewMode(ScreenViewMode mode) {
		MonitorApp app = appForViewMode(mode);
		return app != null ? app.role() : MonitorAppRole.GENERIC;
	}

	static void fillRoundedRect(Graphics2D graphics, UiRect rect, int arc, Color color) {
		if (color != null) {
			graphics.setColor(color);
		}
		graphics.fillRoundRect(rect.x(), rect.y(), rect.width(), rect.height(), arc, arc);
	}

	static void strokeRoundedRect(Graphics2D graphics, UiRect rect, int arc, float width, Color color) {
		Stroke previous = graphics.getStroke();
		graphics.setStroke(new BasicStroke(width));
		graphics.setColor(color);
		graphics.drawRoundRect(rect.x(), rect.y(), rect.width(), rect.height(), arc, arc);
		graphics.setStroke(previous);
	}

	static Shape roundedRectShape(UiRect rect, int topLeft, int topRight, int bottomRight, int bottomLeft) {
		float x = rect.x();
		float y = rect.y();
		float width = rect.width();
		float height = rect.height();
		float right = x + width;
		float bottom = y + height;
		float tl = clampFloat(topLeft, 0.0F, Math.min(width, height) / 2.0F);
		float tr = clampFloat(topRight, 0.0F, Math.min(width, height) / 2.0F);
		float br = clampFloat(bottomRight, 0.0F, Math.min(width, height) / 2.0F);
		float bl = clampFloat(bottomLeft, 0.0F, Math.min(width, height) / 2.0F);
		Path2D.Float path = new Path2D.Float();
		path.moveTo(x + tl, y);
		path.lineTo(right - tr, y);
		if (tr > 0.0F) {
			path.quadTo(right, y, right, y + tr);
		} else {
			path.lineTo(right, y);
		}
		path.lineTo(right, bottom - br);
		if (br > 0.0F) {
			path.quadTo(right, bottom, right - br, bottom);
		} else {
			path.lineTo(right, bottom);
		}
		path.lineTo(x + bl, bottom);
		if (bl > 0.0F) {
			path.quadTo(x, bottom, x, bottom - bl);
		} else {
			path.lineTo(x, bottom);
		}
		path.lineTo(x, y + tl);
		if (tl > 0.0F) {
			path.quadTo(x, y, x + tl, y);
		} else {
			path.lineTo(x, y);
		}
		path.closePath();
		return path;
	}

	static void fillShape(Graphics2D graphics, Shape shape, Color color) {
		if (graphics == null || shape == null || color == null) {
			return;
		}
		Color previous = graphics.getColor();
		graphics.setColor(color);
		graphics.fill(shape);
		graphics.setColor(previous);
	}

	static void strokeShape(Graphics2D graphics, Shape shape, float width, Color color) {
		if (graphics == null || shape == null || color == null) {
			return;
		}
		Stroke previousStroke = graphics.getStroke();
		Color previousColor = graphics.getColor();
		graphics.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.setColor(color);
		graphics.draw(shape);
		graphics.setStroke(previousStroke);
		graphics.setColor(previousColor);
	}

	static void drawCenteredText(Graphics2D graphics, String text, UiRect rect, Color color, int style, int size) {
		graphics.setColor(color);
		graphics.setFont(new Font(Font.SANS_SERIF, style, size));
		var metrics = graphics.getFontMetrics();
		int textX = rect.x() + (rect.width() - metrics.stringWidth(text)) / 2;
		int textY = rect.y() + (rect.height() - metrics.getHeight()) / 2 + metrics.getAscent();
		graphics.drawString(text, textX, textY);
	}

	static void drawCenteredTextFitted(Graphics2D graphics, String text, UiRect rect, Color color, int style, int maxSize, int minSize) {
		if (graphics == null || rect == null || rect.width() <= 0 || rect.height() <= 0 || text == null || text.isBlank()) {
			return;
		}
		int resolvedMinSize = Math.max(1, Math.min(minSize, maxSize));
		int size = maxSize;
		java.awt.Font font = new Font(Font.SANS_SERIF, style, size);
		graphics.setColor(color);
		graphics.setFont(font);
		var metrics = graphics.getFontMetrics();
		while (size > resolvedMinSize && metrics.stringWidth(text) > rect.width() - 2) {
			size--;
			font = new Font(Font.SANS_SERIF, style, size);
			graphics.setFont(font);
			metrics = graphics.getFontMetrics();
		}
		int textX = rect.x() + (rect.width() - metrics.stringWidth(text)) / 2;
		int textY = rect.y() + (rect.height() - metrics.getHeight()) / 2 + metrics.getAscent();
		graphics.drawString(text, textX, textY);
	}

	static void drawCenteredTextEllipsized(Graphics2D graphics, String text, UiRect rect, Color color, int style, int size) {
		if (graphics == null || rect == null || rect.width() <= 0 || rect.height() <= 0 || text == null || text.isBlank()) {
			return;
		}
		graphics.setColor(color);
		graphics.setFont(new Font(Font.SANS_SERIF, style, size));
		var metrics = graphics.getFontMetrics();
		String visibleText = truncateWithEllipsis(metrics, text, Math.max(1, rect.width() - 2));
		int textX = rect.x() + (rect.width() - metrics.stringWidth(visibleText)) / 2;
		int textY = rect.y() + (rect.height() - metrics.getHeight()) / 2 + metrics.getAscent();
		graphics.drawString(visibleText, textX, textY);
	}

	static void drawVerticalText(Graphics2D graphics, String text, UiRect rect, Color color, int style, int size) {
		graphics.setColor(color);
		graphics.setFont(new Font(Font.SANS_SERIF, style, size));
		var metrics = graphics.getFontMetrics();
		int textY = rect.y() + (rect.height() - metrics.getHeight()) / 2 + metrics.getAscent();
		graphics.drawString(text, rect.x(), textY);
	}

	static void drawRightAlignedText(Graphics2D graphics, String text, UiRect rect, Color color, int style, int size) {
		if (graphics == null || rect == null || text == null || text.isBlank()) {
			return;
		}
		graphics.setColor(color);
		graphics.setFont(new Font(Font.SANS_SERIF, style, size));
		var metrics = graphics.getFontMetrics();
		int textY = rect.y() + (rect.height() - metrics.getHeight()) / 2 + metrics.getAscent();
		int textX = rect.right() - metrics.stringWidth(text);
		graphics.drawString(text, textX, textY);
	}

	static void drawWrappedText(Graphics2D graphics, String text, UiRect rect, Color color, int style, int size, int maxLines) {
		if (graphics == null || rect.width() <= 0 || rect.height() <= 0 || maxLines <= 0) {
			return;
		}
		String normalized = text == null ? "" : text.trim().replace('\n', ' ').replace('\r', ' ');
		graphics.setColor(color);
		graphics.setFont(new Font(Font.SANS_SERIF, style, size));
		var metrics = graphics.getFontMetrics();
		List<String> lines = wrapText(metrics, normalized, rect.width(), maxLines);
		if (lines.isEmpty()) {
			return;
		}
		int lineHeight = metrics.getHeight();
		int totalHeight = lineHeight * lines.size();
		int y = rect.y() + Math.max(metrics.getAscent(), (rect.height() - totalHeight) / 2 + metrics.getAscent());
		for (String line : lines) {
			graphics.drawString(line, rect.x(), y);
			y += lineHeight;
		}
	}

	static List<String> wrapText(java.awt.FontMetrics metrics, String text, int maxWidth, int maxLines) {
		List<String> lines = new ArrayList<>();
		if (metrics == null || maxWidth <= 0 || maxLines <= 0) {
			return lines;
		}
		String normalized = text == null ? "" : text.trim();
		if (normalized.isBlank()) {
			lines.add("");
			return lines;
		}
		String[] words = normalized.split("\\s+");
		StringBuilder current = new StringBuilder();
		for (String word : words) {
			String candidate = current.isEmpty() ? word : current + " " + word;
			if (metrics.stringWidth(candidate) <= maxWidth) {
				current.setLength(0);
				current.append(candidate);
				continue;
			}
			if (!current.isEmpty()) {
				lines.add(current.toString());
				if (lines.size() >= maxLines - 1) {
					lines.add(truncateWithEllipsis(metrics, word, maxWidth));
					return lines;
				}
				current.setLength(0);
				current.append(word);
				continue;
			}
			lines.add(truncateWithEllipsis(metrics, word, maxWidth));
			if (lines.size() >= maxLines) {
				return lines;
			}
		}
		if (!current.isEmpty()) {
			lines.add(truncateWithEllipsis(metrics, current.toString(), maxWidth));
		}
		if (lines.size() > maxLines) {
			return lines.subList(0, maxLines);
		}
		return lines;
	}

	static String truncateWithEllipsis(java.awt.FontMetrics metrics, String text, int maxWidth) {
		if (text == null || text.isEmpty() || metrics == null || maxWidth <= 0) {
			return "";
		}
		if (metrics.stringWidth(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
		int ellipsisWidth = metrics.stringWidth(ellipsis);
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < text.length(); index++) {
			char current = text.charAt(index);
			if (metrics.stringWidth(builder.toString() + current) + ellipsisWidth > maxWidth) {
				break;
			}
			builder.append(current);
		}
		if (builder.isEmpty()) {
			return ellipsis;
		}
		return builder + ellipsis;
	}

	static UiPoint screenTouchPoint(ItemFrame frame, ServerPlayer player, Vec3 fallbackHitLocation, TileCoord tileCoord, int gridWidth, int gridHeight) {
		if (frame == null || tileCoord == null) {
			return null;
		}
		if (player != null) {
			Vec3 eye = player.getEyePosition();
			Vec3 rayDirection = player.getLookAngle();
			double maxDistance = MEDIA_CONTROL_DISTANCE;
			if (fallbackHitLocation != null) {
				Vec3 hitDelta = fallbackHitLocation.subtract(eye);
				double hitDistance = hitDelta.length();
				if (Double.isFinite(hitDistance) && hitDistance > 1.0E-6D) {
					rayDirection = hitDelta.scale(1.0D / hitDistance);
					maxDistance = Math.max(maxDistance, hitDistance + TOUCH_TOLERANCE);
				}
			}
			UiPoint projectedPoint = screenTouchPoint(
					frame.blockPosition(),
					frame.getDirection(),
					eye,
					rayDirection,
					tileCoord,
					gridWidth,
					gridHeight,
					maxDistance
			);
			if (projectedPoint != null) {
				return projectedPoint;
			}
		}
		return screenTouchPoint(frame, fallbackHitLocation, tileCoord, gridWidth, gridHeight);
	}

	static UiPoint screenTouchPoint(ItemFrame frame, ServerPlayer player, TileCoord tileCoord, int gridWidth, int gridHeight) {
		if (frame == null || player == null || tileCoord == null) {
			return null;
		}
		return screenTouchPoint(
				frame.blockPosition(),
				frame.getDirection(),
				player.getEyePosition(),
				player.getLookAngle(),
				tileCoord,
				gridWidth,
				gridHeight,
				MEDIA_CONTROL_DISTANCE
		);
	}

	static UiPoint screenTouchPoint(ItemFrame frame, Vec3 hitLocation, TileCoord tileCoord, int gridWidth, int gridHeight) {
		if (frame == null) {
			return null;
		}
		return screenTouchPoint(frame.blockPosition(), frame.getDirection(), hitLocation, tileCoord, gridWidth, gridHeight);
	}

	static UiPoint screenTouchPoint(BlockPos framePos, Direction facing, Vec3 hitLocation, TileCoord tileCoord, int gridWidth, int gridHeight) {
		if (framePos == null || facing == null || hitLocation == null || tileCoord == null) {
			return null;
		}
		PlacementSurfacePoint hit = boundedScreenSurfacePoint(framePos, facing, hitLocation);
		return hit == null ? null : screenPointFromSurface(hit, tileCoord, gridWidth, gridHeight);
	}

	static UiPoint screenTouchPoint(
			BlockPos framePos,
			Direction facing,
			Vec3 rayStart,
			Vec3 rayDirection,
			TileCoord tileCoord,
			int gridWidth,
			int gridHeight,
			double maxDistance
	) {
		Vec3 hitLocation = screenPlaneIntersection(framePos, facing, rayStart, rayDirection, maxDistance);
		return screenTouchPoint(framePos, facing, hitLocation, tileCoord, gridWidth, gridHeight);
	}

	static Vec3 screenPlaneIntersection(BlockPos framePos, Direction facing, Vec3 rayStart, Vec3 rayDirection, double maxDistance) {
		if (framePos == null || facing == null || rayStart == null || rayDirection == null || !facing.getAxis().isHorizontal()) {
			return null;
		}
		Vec3 normal = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
		double denominator = rayDirection.dot(normal);
		if (!Double.isFinite(denominator) || Math.abs(denominator) < 1.0E-6D) {
			return null;
		}
		Vec3 planePoint = screenPlaneCenter(framePos, facing);
		double distance = planePoint.subtract(rayStart).dot(normal) / denominator;
		if (!Double.isFinite(distance) || distance < -TOUCH_TOLERANCE || distance > maxDistance + TOUCH_TOLERANCE) {
			return null;
		}
		return rayStart.add(rayDirection.scale(Math.max(0.0D, distance)));
	}

	static Vec3 screenPlaneCenter(BlockPos framePos, Direction facing) {
		Direction mountSide = facing.getOpposite();
		return Vec3.atCenterOf(framePos).add(
				mountSide.getStepX() * DISPLAY_PLANE_OFFSET,
				mountSide.getStepY() * DISPLAY_PLANE_OFFSET,
				mountSide.getStepZ() * DISPLAY_PLANE_OFFSET
		);
	}

	private static PlacementSurfacePoint boundedScreenSurfacePoint(BlockPos framePos, Direction facing, Vec3 hitLocation) {
		if (framePos == null || facing == null || hitLocation == null) {
			return null;
		}
		double localX = hitLocation.x - framePos.getX();
		double localY = hitLocation.y - framePos.getY();
		double localZ = hitLocation.z - framePos.getZ();
		if (localY < -TOUCH_TOLERANCE || localY > 1.0D + TOUCH_TOLERANCE) {
			return null;
		}

		double u = switch (facing) {
			case SOUTH -> localX;
			case NORTH -> 1.0D - localX;
			case EAST -> 1.0D - localZ;
			case WEST -> localZ;
			default -> Double.NaN;
		};
		if (!Double.isFinite(u) || u < -TOUCH_TOLERANCE || u > 1.0D + TOUCH_TOLERANCE) {
			return null;
		}
		return new PlacementSurfacePoint(
				clampDouble(u, 0.0D, 1.0D),
				clampDouble(1.0D - localY, 0.0D, 1.0D)
		);
	}

	private static UiPoint screenPointFromSurface(PlacementSurfacePoint hit, TileCoord tileCoord, int gridWidth, int gridHeight) {
		if (hit == null || tileCoord == null) {
			return null;
		}
		int pixelX = tileCoord.x() * MAP_SIZE + clampInt((int) Math.floor(hit.u() * (MAP_SIZE - 1)), 0, MAP_SIZE - 1);
		int pixelY = tileCoord.y() * MAP_SIZE + clampInt((int) Math.floor(hit.v() * (MAP_SIZE - 1)), 0, MAP_SIZE - 1);
		int maxX = Math.max(0, gridWidth * MAP_SIZE - 1);
		int maxY = Math.max(0, gridHeight * MAP_SIZE - 1);
		return new UiPoint(clampInt(pixelX, 0, maxX), clampInt(pixelY, 0, maxY));
	}

	static BufferedImage loadOffBaseImage() {
		if (offBaseImage == null) {
			offBaseImage = loadBaseImage(SCREEN_OFF_RESOURCE);
		}
		return offBaseImage;
	}

	static BufferedImage loadOnBaseImage() {
		if (onBaseImage == null) {
			onBaseImage = loadBaseImage(SCREEN_ON_RESOURCE);
		}
		return onBaseImage;
	}

	static BufferedImage loadBaseImage(String resourcePath) {
		boolean powered = SCREEN_ON_RESOURCE.equals(resourcePath);
		try (InputStream inputStream = MonitorScreenSystem.class.getResourceAsStream(resourcePath)) {
			if (inputStream == null) {
				return fallbackImage(powered);
			}
			BufferedImage image = ImageIO.read(inputStream);
			return image != null ? image : fallbackImage(powered);
		} catch (IOException ignored) {
			return fallbackImage(powered);
		}
	}

	static BufferedImage loadPngImage(String resourcePath, BufferedImage fallback) {
		try (InputStream inputStream = MonitorScreenSystem.class.getResourceAsStream(resourcePath)) {
			if (inputStream == null) {
				return fallback;
			}
			BufferedImage image = ImageIO.read(inputStream);
			return image != null ? image : fallback;
		} catch (IOException ignored) {
			return fallback;
		}
	}

	static BufferedImage loadPlayerUiIcon(PlayerUiIcon icon) {
		if (icon == null) {
			return new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		}
		return PLAYER_UI_ICON_CACHE.computeIfAbsent(
				icon,
				key -> loadPngImage(key.resourcePath(), new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB))
		);
	}

	static BufferedImage tintedPlayerUiIcon(PlayerUiIcon icon, Color tint) {
		if (icon == null || tint == null) {
			return new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		}
		return PLAYER_UI_ICON_TINT_CACHE.computeIfAbsent(
				new PlayerUiIconTintKey(icon, tint.getRGB()),
				key -> colorizePlayerUiIcon(loadPlayerUiIcon(key.icon()), tint)
		);
	}

	static BufferedImage colorizePlayerUiIcon(BufferedImage source, Color tint) {
		if (source == null || tint == null) {
			return new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		}
		BufferedImage tinted = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		int tintAlpha = tint.getAlpha();
		int tintRgb = tint.getRGB() & 0x00FFFFFF;
		for (int y = 0; y < source.getHeight(); y++) {
			for (int x = 0; x < source.getWidth(); x++) {
				int argb = source.getRGB(x, y);
				int alpha = (argb >>> 24) & 0xFF;
				if (alpha <= 0) {
					continue;
				}
				int finalAlpha = alpha * tintAlpha / 255;
				tinted.setRGB(x, y, (finalAlpha << 24) | tintRgb);
			}
		}
		return tinted;
	}

	static void drawPlayerUiIcon(Graphics2D graphics, UiRect rect, PlayerUiIcon icon, Color tint) {
		if (graphics == null || rect == null || rect.width() <= 0 || rect.height() <= 0 || icon == null || tint == null) {
			return;
		}
		BufferedImage tinted = tintedPlayerUiIcon(icon, tint);
		graphics.drawImage(tinted, rect.x(), rect.y(), rect.width(), rect.height(), null);
	}

	static BufferedImage fallbackImage(boolean powered) {
		BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
		int base = powered ? 0xF2F5F8 : 0x040404;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int band = ((y / 4) & 1) == 0 ? 4 : -4;
				int vignette = Math.max(0, 18 - Math.abs(128 - x) / 6 - Math.abs(128 - y) / 6);
				int rgb = adjustBrightness(base, powered ? vignette - band : -vignette + band);
				image.setRGB(x, y, 0xFF000000 | rgb);
			}
		}
		return image;
	}

	static int adjustBrightness(int rgb, int delta) {
		int red = clampInt(((rgb >> 16) & 0xFF) + delta, 0, 255);
		int green = clampInt(((rgb >> 8) & 0xFF) + delta, 0, 255);
		int blue = clampInt((rgb & 0xFF) + delta, 0, 255);
		return (red << 16) | (green << 8) | blue;
	}

	static long effectiveYoutubePollDelayMs(MinecraftServer server, ScreenRuntimeKey key, boolean paused) {
		long baseDelay = paused ? youtubePollIdleIntervalMs() : youtubePollActiveIntervalMs();
		MediaRuntimeState state = key != null ? MEDIA_STATES.get(key) : null;
		if (state != null) {
			synchronized (state) {
				if (isDirectAudioPlaybackLocked(state)) {
					baseDelay = Math.max(baseDelay, paused ? 350L : 200L);
				}
			}
		}
		if (!hasNearbyMediaViewer(server, key)) {
			return Math.max(baseDelay, paused ? youtubePollIdleIntervalMs() * 2L : youtubePollIdleIntervalMs());
		}
		return baseDelay;
	}

	static long effectiveYoutubeUiRefreshThresholdMs(MinecraftServer server, ScreenRuntimeKey key) {
		return Math.max(50L, effectiveYoutubePollDelayMs(server, key, false));
	}

	static ScreenComponent resolveScreenComponent(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return null;
		}
		ServerLevel level = server.getLevel(key.dimension());
		if (level == null) {
			return null;
		}
		MonitorLevelState state = levelState(level.dimension());
		ScreenComponent cached = state.components().get(key);
		if (cached != null) {
			return cached;
		}
		ItemFrame rootFrame = findScreenFrame(level, key.pos(), key.facing());
		if (rootFrame == null) {
			return null;
		}
		ScreenComponent component = collectComponent(level, rootFrame, null);
		if (component != null) {
			cacheComponent(level, component);
		}
		return component;
	}

	static ScreenComponent cachedScreenComponent(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return null;
		}
		ServerLevel level = server.getLevel(key.dimension());
		if (level == null) {
			return null;
		}
		return levelState(level.dimension()).components().get(key);
	}

	static boolean hasNearbyMediaViewer(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return false;
		}
		ServerLevel level = server.getLevel(key.dimension());
		if (level == null) {
			return false;
		}
		ScreenComponent component = resolveScreenComponent(server, key);
		return hasNearbyMediaViewer(level, component);
	}

	static boolean hasNearbyMediaViewer(ServerLevel level, ScreenComponent component) {
		if (level == null || component == null) {
			return false;
		}
		double radiusBlocks = monitorMapUpdateRadiusBlocks();
		double radiusSquared = radiusBlocks * radiusBlocks;
		for (ServerPlayer player : level.players()) {
			if (isPlayerNearScreenComponent(player, component, radiusSquared)) {
				return true;
			}
		}
		return false;
	}

	static boolean isPlayerNearScreenComponent(ServerPlayer player, ScreenComponent component, double radiusSquared) {
		if (player == null || component == null) {
			return false;
		}
		for (ItemFrame frame : component.frameCoords().keySet()) {
			if (frame != null && player.distanceToSqr(frame) <= radiusSquared) {
				return true;
			}
		}
		return false;
	}

	static void ensureDisplay(ServerLevel level, ItemFrame frame, int connectionMask) {
		// A launched rocket keeps the invisible ItemFrame at its construction
		// coordinate for links/state, while its one visible ItemDisplay follows the
		// rocket.  Resolve that moving display first so this refresh never creates
		// a duplicate back at the original coordinate.
		Display.ItemDisplay mountedRocketDisplay = RocketLaunchEventSystem.resolveRocketMountedScreenDisplay(level, frame);
		List<Display.ItemDisplay> displays = mountedRocketDisplay == null
				? findDisplays(level, frame.blockPosition(), frame.getDirection())
				: List.of(mountedRocketDisplay);
		Display.ItemDisplay display;
		boolean created = displays.isEmpty();
		if (created) {
			display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
			display.addTag(DISPLAY_ROOT_TAG);
			display.addTag(positionTag(frame.blockPosition()));
			display.addTag(facingTag(frame.getDirection()));
		} else {
			display = displays.get(0);
			for (int i = 1; i < displays.size(); i++) {
				displays.get(i).discard();
			}
		}

		Direction facing = frame.getDirection();
		Direction mountSide = facing.getOpposite();
		Vec3 center = Vec3.atCenterOf(frame.blockPosition()).add(
				mountSide.getStepX() * DISPLAY_PLANE_OFFSET,
				mountSide.getStepY() * DISPLAY_PLANE_OFFSET,
				mountSide.getStepZ() * DISPLAY_PLANE_OFFSET
		);
		float yRot = facing.toYRot();
		display.setPos(center.x, center.y, center.z);
		display.setYRot(yRot);
		display.setXRot(0.0F);
		display.setYHeadRot(yRot);
		display.setYBodyRot(yRot);
		display.setItemStack(MonitorItem.createDisplayStack(connectionMask));
		display.setItemTransform(ItemDisplayContext.FIXED);
		display.setBillboardConstraints(Display.BillboardConstraints.FIXED);
		display.setTransformation(Transformation.identity());
		display.setNoGravity(true);
		display.setInvulnerable(true);
		display.setSilent(true);
		display.setShadowRadius(0.0F);
		display.setShadowStrength(0.0F);
		display.setViewRange(1.0F);
		ItemDisplayHitboxHelper.clear(display);
		RocketLaunchEventSystem.poseRocketMountedScreen(level, frame, display);
		if (created) {
			level.addFreshEntity(display);
		}
	}

	static void cleanupOrphanDisplays(ServerLevel level) {
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof Display.ItemDisplay display) || !display.getTags().contains(DISPLAY_ROOT_TAG)) {
				continue;
			}
			BlockPos pos = parsePositionTag(display.getTags());
			Direction facing = parseFacingTag(display.getTags());
			if (pos == null || facing == null || findScreenFrame(level, pos, facing) == null) {
				display.discard();
			}
		}
	}

	static void removeDisplays(ServerLevel level, BlockPos pos, Direction facing) {
		for (Display.ItemDisplay display : findDisplays(level, pos, facing)) {
			display.discard();
		}
	}

	static List<Display.ItemDisplay> findDisplays(ServerLevel level, BlockPos pos, Direction facing) {
		AABB box = new AABB(pos).inflate(DISPLAY_SEARCH_RADIUS);
		String posTag = positionTag(pos);
		String facingTag = facingTag(facing);
		return level.getEntities(
				EntityType.ITEM_DISPLAY,
				box,
				display -> display.getTags().contains(DISPLAY_ROOT_TAG)
						&& display.getTags().contains(posTag)
						&& display.getTags().contains(facingTag)
		);
	}

	static ItemFrame findAnyFrame(ServerLevel level, BlockPos pos, Direction facing) {
		AABB box = new AABB(pos).inflate(0.6D);
		for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, box, candidate -> candidate.blockPosition().equals(pos))) {
			if (frame.getDirection() == facing) {
				return frame;
			}
		}
		return null;
	}

	static ItemFrame findScreenFrame(ServerLevel level, BlockPos pos, Direction facing) {
		AABB box = new AABB(pos).inflate(0.6D);
		for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, box, candidate -> candidate.blockPosition().equals(pos))) {
			if (facing != null && frame.getDirection() != facing) {
				continue;
			}
			if (readScreenState(frame.getItem()) != null) {
				return frame;
			}
		}
		return null;
	}

	static Direction frameRight(Direction facing) {
		return switch (facing) {
			case NORTH -> Direction.WEST;
			case SOUTH -> Direction.EAST;
			case EAST -> Direction.NORTH;
			case WEST -> Direction.SOUTH;
			default -> Direction.EAST;
		};
	}

	static ServerLevel photoMapLevel(MinecraftServer server, ServerLevel fallback) {
		if (server == null) {
			return fallback;
		}
		ServerLevel end = server.getLevel(Level.END);
		if (end != null) {
			return end;
		}
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		return overworld != null ? overworld : fallback;
	}

	static String positionTag(BlockPos pos) {
		return POS_TAG_PREFIX + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	static String facingTag(Direction facing) {
		return FACING_TAG_PREFIX + facing.getName();
	}

	static BlockPos parsePositionTag(Set<String> tags) {
		for (String tag : tags) {
			if (!tag.startsWith(POS_TAG_PREFIX)) {
				continue;
			}
			String raw = tag.substring(POS_TAG_PREFIX.length());
			String[] parts = raw.split(",");
			if (parts.length != 3) {
				continue;
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
		return null;
	}

	static Direction parseFacingTag(Set<String> tags) {
		for (String tag : tags) {
			if (tag.startsWith(FACING_TAG_PREFIX)) {
				return Direction.byName(tag.substring(FACING_TAG_PREFIX.length()));
			}
		}
		return null;
	}

	static int clampInt(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	static float clampFloat(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	static double clampDouble(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	static Color withAlpha(int rgb, int alpha) {
		return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, clampInt(alpha, 0, 255));
	}

	static Color withAlpha(Color color, float alpha) {
		if (color == null) {
			return null;
		}
		int nextAlpha = clampInt(Math.round(color.getAlpha() * Math.max(0.0F, Math.min(1.0F, alpha))), 0, 255);
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), nextAlpha);
	}

	static float mediaTimelineFraction(UiLayout layout, UiPoint point) {
		return mediaTimelineFraction(layout, point, ScreenViewMode.HOME);
	}

	static float mediaTimelineFraction(UiLayout layout, UiPoint point, MediaRuntimeState state) {
		UiRect trackRect = mediaTimelineTrackRect(layout, state);
		if (trackRect.width() <= 0) {
			return 0.0F;
		}
		return (float) clampDouble((point.x() - trackRect.x()) / (double) Math.max(1, trackRect.width() - 1), 0.0D, 1.0D);
	}

	static float mediaTimelineFraction(UiLayout layout, UiPoint point, ScreenViewMode mode) {
		UiRect trackRect = mediaTimelineTrackRect(layout, mode);
		if (trackRect.width() <= 0) {
			return 0.0F;
		}
		return (float) clampDouble((point.x() - trackRect.x()) / (double) Math.max(1, trackRect.width() - 1), 0.0D, 1.0D);
	}

	static int mediaFrameIndexForFraction(MonitorMediaApp.LoadedMedia loadedMedia, float fraction) {
		if (loadedMedia == null || loadedMedia.frameCount() <= 1) {
			return 0;
		}
		return clampInt((int) Math.round(clampDouble(fraction, 0.0D, 1.0D) * (loadedMedia.frameCount() - 1)), 0, loadedMedia.frameCount() - 1);
	}

	static boolean isPlayerMode(ScreenViewMode mode) {
		return appRoleForViewMode(mode).usesMediaRuntime();
	}

	static boolean isLibraryAppMode(ScreenViewMode mode) {
		return appRoleForViewMode(mode).usesLibrarySurface();
	}

	static boolean isSberDronesMode(ScreenViewMode mode) {
		return appRoleForViewMode(mode) == MonitorAppRole.SBER_DRONES;
	}

	static int youtubeQueueVisibleRowsPreview(MediaRuntimeState state) {
		return 5;
	}

	static boolean mediaQueueHasPartialBottomRow(UiLayout layout) {
		if (layout == null) {
			return false;
		}
		UiRect list = mediaQueueListRect(layout);
		int rowHeight = Math.max(1, mediaQueueRowHeight(layout));
		return list.height() % rowHeight != 0;
	}

	static int mediaQueueRenderableRows(UiLayout layout) {
		return mediaQueueVisibleRows(layout) + (mediaQueueHasPartialBottomRow(layout) ? 1 : 0);
	}

	static int galleryColumnsPreview(UiLayout layout) {
		return layout != null ? Math.max(1, mediaGalleryColumns(layout)) : 2;
	}

	static int galleryVisibleRowsPreview(UiLayout layout) {
		return layout != null ? Math.max(1, mediaGalleryVisibleRows(layout)) : 2;
	}

	static int galleryTotalRowsPreview(int itemCount, UiLayout layout) {
		if (itemCount <= 0) {
			return 0;
		}
		return layout != null
				? mediaGalleryTotalRows(itemCount, layout)
				: Math.max(0, (itemCount + galleryColumnsPreview(null) - 1) / galleryColumnsPreview(null));
	}

	static int galleryRowForIndexPreview(int index, UiLayout layout) {
		return Math.max(0, index / Math.max(1, galleryColumnsPreview(layout)));
	}

	static int youtubeQueueSnapshotWindowStartIndex(ScreenRuntimeKey runtimeKey, MediaRuntimeState state, UiLayout layout) {
		if (state == null || state.youtubeQueue.isEmpty()) {
			return 0;
		}
		int visibleRows = layout != null ? mediaQueueVisibleRows(layout) : youtubeQueueVisibleRowsPreview(state);
		int maxScroll = Math.max(0, state.youtubeQueue.size() - visibleRows);
		int scroll = clampInt(state.youtubeQueueScroll, 0, maxScroll);
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MEDIA_QUEUE_WINDOW,
				scroll,
				maxScroll
		);
		int renderableRows = layout != null ? mediaQueueRenderableRows(layout) : visibleRows + 1;
		int windowPadding = 2;
		return clampInt(
				Math.min(scroll, visualScroll.anchorIndex()) - windowPadding,
				0,
				Math.max(0, state.youtubeQueue.size() - Math.max(1, renderableRows))
		);
	}

	static int youtubeQueueSnapshotWindowEndExclusive(ScreenRuntimeKey runtimeKey, MediaRuntimeState state, UiLayout layout, int startIndex) {
		if (state == null || state.youtubeQueue.isEmpty()) {
			return 0;
		}
		int visibleRows = layout != null ? mediaQueueVisibleRows(layout) : youtubeQueueVisibleRowsPreview(state);
		int maxScroll = Math.max(0, state.youtubeQueue.size() - visibleRows);
		int scroll = clampInt(state.youtubeQueueScroll, 0, maxScroll);
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MEDIA_QUEUE_WINDOW,
				scroll,
				maxScroll
		);
		int renderableRows = layout != null ? mediaQueueRenderableRows(layout) : visibleRows + 1;
		int windowPadding = 2;
		int coverageEndExclusive = Math.max(scroll, visualScroll.anchorIndex())
				+ renderableRows
				+ (visualScroll.animated() ? 1 : 0)
				+ windowPadding;
		return clampInt(Math.max(startIndex, coverageEndExclusive), 0, state.youtubeQueue.size());
	}

	static int galleryCardSnapshotWindowStartIndex(ScreenRuntimeKey runtimeKey, MediaRuntimeState state, UiLayout layout, int totalCount) {
		if (state == null || layout == null || totalCount <= 0) {
			return 0;
		}
		int columns = mediaGalleryColumns(layout);
		int visibleRows = mediaGalleryVisibleRows(layout);
		int totalRows = mediaGalleryTotalRows(totalCount, layout);
		int maxScroll = Math.max(0, totalRows - visibleRows);
		int scroll = clampInt(state.galleryScroll, 0, maxScroll);
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MEDIA_LIBRARY_BROWSER,
				scroll,
				maxScroll
		);
		int windowPaddingRows = 1;
		return clampInt(
				(Math.min(scroll, visualScroll.anchorIndex()) - windowPaddingRows) * Math.max(1, columns),
				0,
				totalCount
		);
	}

	static int galleryCardSnapshotWindowEndExclusive(ScreenRuntimeKey runtimeKey, MediaRuntimeState state, UiLayout layout, int totalCount, int startIndex) {
		if (state == null || layout == null || totalCount <= 0) {
			return 0;
		}
		int columns = mediaGalleryColumns(layout);
		int visibleRows = mediaGalleryVisibleRows(layout);
		int totalRows = mediaGalleryTotalRows(totalCount, layout);
		int maxScroll = Math.max(0, totalRows - visibleRows);
		int scroll = clampInt(state.galleryScroll, 0, maxScroll);
		MonitorScrollAnimationSystem.ScrollVisualState visualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.MEDIA_LIBRARY_BROWSER,
				scroll,
				maxScroll
		);
		int windowPaddingRows = 1;
		int endRowExclusive = Math.max(scroll, visualScroll.anchorIndex())
				+ visibleRows
				+ (visualScroll.animated() ? 1 : 0)
				+ windowPaddingRows;
		return clampInt(Math.max(startIndex, endRowExclusive * Math.max(1, columns)), 0, totalCount);
	}

	static List<YoutubeQueueItemSnapshot> galleryItemSnapshots(MediaRuntimeState state) {
		if (state == null || state.galleryItems.isEmpty()) {
			return List.of();
		}
		List<YoutubeQueueItemSnapshot> items = new ArrayList<>(state.galleryItems.size());
		for (int index = 0; index < state.galleryItems.size(); index++) {
			GalleryItem item = state.galleryItems.get(index);
			items.add(new YoutubeQueueItemSnapshot(
					index,
					item != null ? item.title() : "Gallery",
					item != null ? item.subtitle() : "",
					0L,
					null,
					false,
					index == state.galleryIndex,
					0.0F,
					false,
					false
			));
		}
		return items;
	}

	static WindowedSnapshot<GalleryCardSnapshot> galleryCardSnapshots(
			MinecraftServer server,
			ScreenComponent component,
			MediaRuntimeState state,
			ScreenRuntimeKey runtimeKey,
			UiLayout layout
	) {
		if (state == null || state.galleryItems.isEmpty()) {
			return WindowedSnapshot.empty();
		}
		normalizeGalleryBulkSelectionLocked(state);
		boolean metadataVisible = isSberDronesMode(state.mode);
		List<Integer> visibleIndexes = galleryBrowserVisibleIndexesLocked(state);
		if (visibleIndexes.isEmpty()) {
			return WindowedSnapshot.empty();
		}
		if (metadataVisible || layout == null) {
			List<GalleryCardSnapshot> items = new ArrayList<>(visibleIndexes.size());
			for (int index : visibleIndexes) {
				GalleryCardSnapshot snapshot = buildGalleryCardSnapshot(server, component, state, metadataVisible, index);
				if (snapshot != null) {
					items.add(snapshot);
				}
			}
			return items.isEmpty() ? WindowedSnapshot.empty() : new WindowedSnapshot<>(List.copyOf(items), items.size(), 0);
		}
		int totalCount = visibleIndexes.size();
		int startIndex = galleryCardSnapshotWindowStartIndex(runtimeKey, state, layout, totalCount);
		int endExclusive = galleryCardSnapshotWindowEndExclusive(runtimeKey, state, layout, totalCount, startIndex);
		List<GalleryCardSnapshot> items = new ArrayList<>(Math.max(0, endExclusive - startIndex));
		for (int visibleIndex = startIndex; visibleIndex < endExclusive; visibleIndex++) {
			int index = visibleIndexes.get(visibleIndex);
			GalleryCardSnapshot snapshot = buildGalleryCardSnapshot(server, component, state, false, index);
			if (snapshot != null) {
				items.add(snapshot);
			}
		}
		return items.isEmpty()
				? new WindowedSnapshot<>(List.of(), totalCount, startIndex)
				: new WindowedSnapshot<>(List.copyOf(items), totalCount, startIndex);
	}

	static GalleryCardSnapshot buildGalleryCardSnapshot(
			MinecraftServer server,
			ScreenComponent component,
			MediaRuntimeState state,
			boolean metadataVisible,
			int index
	) {
		if (state == null || index < 0 || index >= state.galleryItems.size()) {
			return null;
		}
		GalleryItem item = state.galleryItems.get(index);
		MonitorMediaApp.LoadedMedia media = item != null ? item.media() : null;
		BufferedImage preview = media != null ? media.frame(0) : item != null ? item.preview() : null;
		GalleryItemKind itemKind = effectiveGalleryItemKind(item);
		boolean youtubeLoaded = item != null
				&& ((itemKind == GalleryItemKind.YOUTUBE && MonitorYoutubeRelayClient.isQueueEntryLoaded(item.url()))
				|| (itemKind == GalleryItemKind.AUDIO && item.url() != null && !item.url().isBlank() && MonitorYoutubeMusicCache.isQueueEntryLoaded(item.url())))
				&& item.url() != null
				&& !item.url().isBlank();
		if (metadataVisible && itemKind == GalleryItemKind.LIVE_CAMERA) {
			return buildLiveCameraGalleryCardSnapshot(server, component, state, index, item, preview);
		}
		return new GalleryCardSnapshot(
				index,
				item != null ? item.title() : "Gallery",
				item != null ? item.subtitle() : "",
				"",
				"",
				false,
				"",
				metadataVisible,
				itemKind,
				media != null && media.animated(),
				(item != null && (itemKind == GalleryItemKind.YOUTUBE || itemKind == GalleryItemKind.VIDEO || itemKind == GalleryItemKind.AUDIO || itemKind == GalleryItemKind.LIVE_CAMERA))
						|| (media != null && media.animated()),
				preview,
				index == state.galleryIndex,
				state.galleryBulkSelectionMode,
				galleryBulkItemSelectedLocked(state, index),
				youtubeLoaded || itemKind == GalleryItemKind.LIVE_CAMERA,
				false
		);
	}

	static GalleryCardSnapshot buildLiveCameraGalleryCardSnapshot(
			MinecraftServer server,
			ScreenComponent component,
			MediaRuntimeState state,
			int index,
			GalleryItem item,
			BufferedImage preview
	) {
		LiveCameraReference cameraRef = item == null ? null : liveCameraGalleryReference(
				item.url(),
				component != null && component.runtimeKey() != null ? component.runtimeKey().dimension() : null
		);
		LiveCameraSourceType sourceType = cameraRef != null ? cameraRef.sourceType() : LiveCameraSourceType.CAMERA;
		ServerLevel level = server != null && component != null && component.runtimeKey() != null
				? server.getLevel(component.runtimeKey().dimension())
				: null;
		ResourceKey<Level> dimension = cameraRef != null ? cameraRef.dimension() : null;
		BlockPos pos = cameraRef != null ? cameraRef.pos() : null;
		if (cameraRef != null && cameraRef.sourceType() == LiveCameraSourceType.DRONE && cameraRef.sourceUuid() != null && server != null) {
			DroneSystem.DroneLiveFeedState droneState = DroneSystem.resolveLiveFeedState(server, cameraRef.sourceUuid(), cameraRef.dimension(), cameraRef.pos());
			if (droneState != null) {
				if (droneState.dimension() != null) {
					dimension = droneState.dimension();
				}
				if (droneState.pos() != null) {
					pos = droneState.pos();
				}
			}
		}
		String tertiary = minimalDimensionLabel(dimension);
		String title = "";
		String subtitle = formatLiveSourceCoordinates(pos);
		String statusLabel = "";
		String sourceLabel = sourceType == LiveCameraSourceType.DRONE ? "DRONE" : "CAMERA";
		boolean disconnectVisible = cameraRef != null && isBluetoothLinkedLiveCamera(level, component, cameraRef);
		return new GalleryCardSnapshot(
				index,
				title,
				subtitle,
				tertiary,
				statusLabel,
				false,
				sourceLabel,
				true,
				GalleryItemKind.LIVE_CAMERA,
				false,
				true,
				preview,
				state != null && index == state.galleryIndex,
				false,
				false,
				true,
				disconnectVisible
		);
	}

	static List<Integer> galleryBrowserVisibleIndexesLocked(MediaRuntimeState state) {
		if (state == null || state.galleryItems.isEmpty()) {
			return List.of();
		}
		if (!state.playerBackgroundGalleryPickerOpen) {
			List<Integer> indexes = new ArrayList<>(state.galleryItems.size());
			for (int index = 0; index < state.galleryItems.size(); index++) {
				indexes.add(index);
			}
			return indexes;
		}
		List<Integer> indexes = new ArrayList<>(state.galleryItems.size());
		for (int index = 0; index < state.galleryItems.size(); index++) {
			if (galleryItemCanBePlayerBackgroundCandidate(state.galleryItems.get(index))) {
				indexes.add(index);
			}
		}
		return indexes;
	}

	static MediaOverlayWindowSnapshot youtubeQueueWindowSnapshot(ScreenRuntimeKey runtimeKey, MediaRuntimeState state, UiLayout layout) {
		if (state == null) {
			return null;
		}
		int totalItems = state.youtubeQueue.size();
		int windowStartIndex = youtubeQueueSnapshotWindowStartIndex(runtimeKey, state, layout);
		int windowEndExclusive = youtubeQueueSnapshotWindowEndExclusive(runtimeKey, state, layout, windowStartIndex);
		boolean youtubeMusicQueue = isYoutubeMusicMode(state.mode);
		List<YoutubeQueueItemSnapshot> items = new ArrayList<>(Math.max(0, windowEndExclusive - windowStartIndex));
		long totalDurationMs = Math.max(0L, state.youtubeQueueTotalDurationMs);
		for (int index = windowStartIndex; index < windowEndExclusive; index++) {
			YoutubeQueueItem item = state.youtubeQueue.get(index);
			String url = item != null ? item.url() : "";
			float cacheFraction = 0.0F;
			boolean cacheActive = false;
			boolean cacheComplete = false;
			if (url != null && !url.isBlank()) {
				if (youtubeMusicQueue) {
					MonitorYoutubeMusicCache.QueueEntryCacheStatus status = MonitorYoutubeMusicCache.queueEntryCacheStatus(url);
					cacheFraction = status.fraction();
					cacheActive = status.active();
					cacheComplete = status.complete();
				} else {
					MonitorYoutubeRelayClient.QueueEntryCacheStatus status = MonitorYoutubeRelayClient.queueEntryCacheStatus(url);
					cacheFraction = status.fraction();
					cacheActive = status.active();
					cacheComplete = status.complete();
				}
			}
			BufferedImage preview = url != null && !url.isBlank()
					? youtubeMusicQueue ? MonitorYoutubeMusicCache.queueEntryPreview(url) : MonitorYoutubeRelayClient.queueEntryPreview(url)
					: null;
			items.add(new YoutubeQueueItemSnapshot(
					index,
					item != null ? item.title() : "YouTube",
					item != null ? item.subtitle() : "",
					item != null ? item.durationMs() : 0L,
					preview,
					youtubeMusicQueue,
					index == state.youtubeQueueIndex,
					cacheFraction,
					cacheActive,
					cacheComplete
			));
		}
		return new MediaOverlayWindowSnapshot(
				MediaOverlayWindowType.YOUTUBE_QUEUE,
				totalItems + " " + pluralizeQueueItems(totalItems, state.mode),
				totalItems <= 0 ? formatPlaybackTime(0L) : totalDurationMs > 0L ? formatPlaybackTime(totalDurationMs) : "Длина неизвестна",
				List.copyOf(items),
				totalItems,
				windowStartIndex,
				null,
				null,
				Math.max(0, state.youtubeQueueScroll),
				Math.max(-1, state.youtubeQueueIndex),
				state.youtubeMusicShuffleEnabled,
				resolvedRepeatModeLocked(state),
				null,
				false,
				MediaScaleMode.FIT
		);
	}

	static MediaOverlayWindowSnapshot galleryDeleteConfirmWindowSnapshot(MediaRuntimeState state) {
		if (state == null) {
			return null;
		}
		String target = state.mediaTitle != null && !state.mediaTitle.isBlank() ? state.mediaTitle : "это медиа";
		return new MediaOverlayWindowSnapshot(
				MediaOverlayWindowType.GALLERY_DELETE_CONFIRM,
				"УДАЛИТЬ?",
				target,
				List.of(),
				0,
				0,
				null,
				null,
				0,
				-1,
				false,
				MediaRepeatMode.OFF,
				null,
				false,
				MediaScaleMode.FIT
		);
	}

	static MediaOverlayWindowSnapshot galleryFileMenuWindowSnapshot(MediaRuntimeState state) {
		if (state == null) {
			return null;
		}
		GalleryItem item = currentGalleryItemLocked(state);
		GalleryItemKind kind = effectiveGalleryItemKind(item);
		String title = item != null && item.title() != null && !item.title().isBlank() ? item.title() : "Медиа";
		boolean saved = currentGalleryItemSavedLocked(state);
		boolean canShare = saved && kind != GalleryItemKind.LIVE_CAMERA;
		GalleryFileMenuSnapshot file = new GalleryFileMenuSnapshot(
				title,
				galleryFileKindLabel(kind),
				saved,
				saved,
				canShare,
				currentGalleryItemCanBeWallpaperLocked(state),
				currentGalleryItemIsWallpaperLocked(state)
		);
		return new MediaOverlayWindowSnapshot(
				MediaOverlayWindowType.GALLERY_FILE_MENU,
				"ФАЙЛ",
				title,
				List.of(),
				0,
				0,
				file,
				null,
				0,
				Math.max(-1, state.galleryIndex),
				false,
				MediaRepeatMode.OFF,
				null,
				false,
				MediaScaleMode.FIT
		);
	}

	static MediaOverlayWindowSnapshot gallerySlideshowWindowSnapshot(MediaRuntimeState state) {
		if (state == null) {
			return null;
		}
		return new MediaOverlayWindowSnapshot(
				MediaOverlayWindowType.GALLERY_SLIDESHOW,
				"Время показа картинок и gif",
				"",
				List.of(),
				0,
				0,
				null,
				new GallerySlideshowSettingsSnapshot(
						state.gallerySlideshowEnabled,
						resolvedGallerySlideshowDurationSecondsLocked(state),
						minGallerySlideshowDurationSeconds(),
						maxGallerySlideshowDurationSeconds()
				),
				0,
				Math.max(-1, state.galleryIndex),
				false,
				MediaRepeatMode.OFF,
				null,
				false,
				MediaScaleMode.FIT
		);
	}

	static String galleryFileKindLabel(GalleryItemKind kind) {
		return switch (kind != null ? kind : GalleryItemKind.MEDIA) {
			case AUDIO -> "Аудио";
			case VIDEO -> "Видео";
			case YOUTUBE -> "YouTube";
			case LIVE_CAMERA -> "Камера";
			case MEDIA -> "Медиа";
		};
	}

	static MediaOverlayWindowSnapshot playerBackgroundMenuWindowSnapshot(
			MediaRuntimeState state,
			PlayerBackgroundMode backgroundMode,
			boolean galleryBackgroundAvailable,
			MediaScaleMode selectedBackgroundScaleMode
	) {
		if (state == null) {
			return null;
		}
		return new MediaOverlayWindowSnapshot(
				MediaOverlayWindowType.PLAYER_BACKGROUND,
				"ФОН ПЛЕЕРА",
				"Для видео, музыки, картинок и трансляций",
				List.of(),
				0,
				0,
				null,
				null,
				0,
				-1,
				false,
				MediaRepeatMode.OFF,
				backgroundMode != null ? backgroundMode : PlayerBackgroundMode.BLACK,
				galleryBackgroundAvailable,
				selectedBackgroundScaleMode != null ? selectedBackgroundScaleMode : MediaScaleMode.FILL
		);
	}

	static String pluralizeQueueItems(int count, ScreenViewMode mode) {
		if (isYoutubeMusicMode(mode)) {
			return pluralizeTracks(count);
		}
		return pluralizeVideos(count);
	}

	static String pluralizeVideos(int count) {
		int mod100 = Math.floorMod(count, 100);
		int mod10 = Math.floorMod(count, 10);
		if (mod100 >= 11 && mod100 <= 19) {
			return "видео";
		}
		if (mod10 == 1) {
			return "видео";
		}
		if (mod10 >= 2 && mod10 <= 4) {
			return "видео";
		}
		return "видео";
	}

	static String pluralizeTracks(int count) {
		int mod100 = Math.floorMod(count, 100);
		int mod10 = Math.floorMod(count, 10);
		if (mod100 >= 11 && mod100 <= 19) {
			return "треков";
		}
		if (mod10 == 1) {
			return "трек";
		}
		if (mod10 >= 2 && mod10 <= 4) {
			return "трека";
		}
		return "треков";
	}

	static List<String> youtubeQueueUrlsLocked(MediaRuntimeState state) {
		if (state == null || state.youtubeQueue.isEmpty()) {
			return List.of();
		}
		List<String> urls = new ArrayList<>(state.youtubeQueue.size());
		for (YoutubeQueueItem item : state.youtubeQueue) {
			if (item != null && item.url() != null && !item.url().isBlank()) {
				urls.add(item.url());
			}
		}
		return urls;
	}

	static List<String> retainedYoutubePreloadUrlsLocked(MediaRuntimeState state) {
		if (state == null || state.retainedYoutubePreloadUrls.isEmpty()) {
			return List.of();
		}
		return List.copyOf(state.retainedYoutubePreloadUrls);
	}

	static List<String> retainedYoutubeMusicPreloadUrlsLocked(MediaRuntimeState state) {
		if (state == null || state.retainedYoutubeMusicUrls.isEmpty()) {
			return List.of();
		}
		return List.copyOf(state.retainedYoutubeMusicUrls);
	}

	static void clearYoutubeMusicShuffleOrderLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		state.youtubeMusicShuffleOrder.clear();
		state.youtubeMusicShuffleCursor = -1;
	}

	static void alignYoutubeQueueScrollToCurrentTopLocked(MediaRuntimeState state, int visibleRows) {
		if (state == null) {
			return;
		}
		int safeVisibleRows = Math.max(1, visibleRows);
		int maxScroll = Math.max(0, state.youtubeQueue.size() - safeVisibleRows);
		int anchor = state.youtubeQueueIndex >= 0 ? state.youtubeQueueIndex : 0;
		state.youtubeQueueScroll = clampInt(anchor, 0, maxScroll);
	}

	static void syncYoutubeMusicShuffleStateLocked(MediaRuntimeState state, boolean preserveUpcomingOrder) {
		if (state == null) {
			return;
		}
		if (!state.youtubeMusicShuffleEnabled || state.youtubeQueue.isEmpty()) {
			clearYoutubeMusicShuffleOrderLocked(state);
			return;
		}
		if (!preserveUpcomingOrder || state.youtubeMusicShuffleOrder.isEmpty()) {
			rebuildYoutubeMusicShuffleOrderLocked(state);
		} else {
			reconcileYoutubeMusicShuffleOrderLocked(state);
		}
		alignYoutubeMusicShuffleCursorLocked(state);
	}

	static void rebuildYoutubeMusicShuffleOrderLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		if (!state.youtubeMusicShuffleEnabled || state.youtubeQueue.isEmpty()) {
			clearYoutubeMusicShuffleOrderLocked(state);
			return;
		}
		int queueSize = state.youtubeQueue.size();
		int currentIndex = state.youtubeQueueIndex >= 0 && state.youtubeQueueIndex < queueSize ? state.youtubeQueueIndex : -1;
		state.youtubeMusicShuffleOrder.clear();
		List<Integer> remaining = new ArrayList<>();
		for (int index = 0; index < queueSize; index++) {
			if (index == currentIndex) {
				continue;
			}
			remaining.add(index);
		}
		shuffleQueueIndices(remaining);
		if (currentIndex >= 0) {
			state.youtubeMusicShuffleOrder.add(currentIndex);
			state.youtubeMusicShuffleCursor = 0;
		} else {
			state.youtubeMusicShuffleCursor = remaining.isEmpty() ? -1 : 0;
		}
		state.youtubeMusicShuffleOrder.addAll(remaining);
	}

	static void reconcileYoutubeMusicShuffleOrderLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		if (!state.youtubeMusicShuffleEnabled || state.youtubeQueue.isEmpty()) {
			clearYoutubeMusicShuffleOrderLocked(state);
			return;
		}
		int queueSize = state.youtubeQueue.size();
		int currentIndex = state.youtubeQueueIndex >= 0 && state.youtubeQueueIndex < queueSize ? state.youtubeQueueIndex : -1;
		List<Integer> reconciled = new ArrayList<>(queueSize);
		Set<Integer> seen = new HashSet<>();
		for (Integer index : List.copyOf(state.youtubeMusicShuffleOrder)) {
			if (index == null || index < 0 || index >= queueSize || !seen.add(index)) {
				continue;
			}
			reconciled.add(index);
		}
		if (currentIndex >= 0 && !seen.contains(currentIndex)) {
			reconciled.add(0, currentIndex);
			seen.add(currentIndex);
		}
		List<Integer> missing = new ArrayList<>();
		for (int index = 0; index < queueSize; index++) {
			if (!seen.contains(index)) {
				missing.add(index);
			}
		}
		shuffleQueueIndices(missing);
		reconciled.addAll(missing);
		state.youtubeMusicShuffleOrder.clear();
		state.youtubeMusicShuffleOrder.addAll(reconciled);
	}

	static void alignYoutubeMusicShuffleCursorLocked(MediaRuntimeState state) {
		if (state == null || !state.youtubeMusicShuffleEnabled || state.youtubeMusicShuffleOrder.isEmpty()) {
			if (state != null) {
				state.youtubeMusicShuffleCursor = -1;
			}
			return;
		}
		int currentIndex = state.youtubeQueueIndex >= 0 && state.youtubeQueueIndex < state.youtubeQueue.size() ? state.youtubeQueueIndex : -1;
		if (currentIndex < 0) {
			state.youtubeMusicShuffleCursor = clampInt(state.youtubeMusicShuffleCursor, 0, state.youtubeMusicShuffleOrder.size() - 1);
			return;
		}
		int cursor = state.youtubeMusicShuffleOrder.indexOf(currentIndex);
		if (cursor >= 0) {
			state.youtubeMusicShuffleCursor = cursor;
			return;
		}
		reconcileYoutubeMusicShuffleOrderLocked(state);
		cursor = state.youtubeMusicShuffleOrder.indexOf(currentIndex);
		state.youtubeMusicShuffleCursor = cursor >= 0 ? cursor : 0;
	}

	static void shuffleQueueIndices(List<Integer> indices) {
		if (indices == null || indices.size() <= 1) {
			return;
		}
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int index = indices.size() - 1; index > 0; index--) {
			int other = random.nextInt(index + 1);
			if (index == other) {
				continue;
			}
			int value = indices.get(index);
			indices.set(index, indices.get(other));
			indices.set(other, value);
		}
	}

	static int adjacentYoutubeQueueIndexLocked(MediaRuntimeState state, int step) {
		if (state == null || state.youtubeQueue.isEmpty()) {
			return -1;
		}
		if (state.youtubeMusicShuffleEnabled) {
			syncYoutubeMusicShuffleStateLocked(state, true);
			if (!state.youtubeMusicShuffleOrder.isEmpty()) {
				int cursor = state.youtubeMusicShuffleCursor >= 0 ? state.youtubeMusicShuffleCursor : 0;
				int targetCursor = Math.floorMod(cursor + step, state.youtubeMusicShuffleOrder.size());
				return state.youtubeMusicShuffleOrder.get(targetCursor);
			}
		}
		int anchor = state.youtubeQueueIndex >= 0
				? state.youtubeQueueIndex
				: step < 0 ? state.youtubeQueue.size() - 1 : 0;
		return normalizeYoutubeQueueIndexLocked(state, anchor + step);
	}

	static Set<String> desiredYoutubeMusicWindowUrlsLocked(MediaRuntimeState state) {
		Set<String> desired = new LinkedHashSet<>();
		if (state == null || state.youtubeQueue.isEmpty()) {
			return desired;
		}
		if (!state.youtubeMusicShuffleEnabled) {
			return desiredYoutubeQueueWindowUrlsLocked(state);
		}
		syncYoutubeMusicShuffleStateLocked(state, true);
		if (state.youtubeMusicShuffleOrder.isEmpty()) {
			return desired;
		}
		int size = state.youtubeMusicShuffleOrder.size();
		int cursor = state.youtubeMusicShuffleCursor >= 0 ? state.youtubeMusicShuffleCursor : 0;
		int maxPrevious = Math.min(YOUTUBE_PRELOAD_PREVIOUS_COUNT, Math.max(0, size - 1));
		int maxNext = Math.min(YOUTUBE_PRELOAD_NEXT_COUNT, Math.max(0, size - 1));
		for (int offset = 0; offset <= maxNext; offset++) {
			int queueIndex = state.youtubeMusicShuffleOrder.get(Math.floorMod(cursor + offset, size));
			YoutubeQueueItem item = state.youtubeQueue.get(queueIndex);
			if (item != null && item.url() != null && !item.url().isBlank()) {
				desired.add(item.url());
			}
		}
		for (int offset = 1; offset <= maxPrevious; offset++) {
			int queueIndex = state.youtubeMusicShuffleOrder.get(Math.floorMod(cursor - offset, size));
			YoutubeQueueItem item = state.youtubeQueue.get(queueIndex);
			if (item != null && item.url() != null && !item.url().isBlank()) {
				desired.add(item.url());
			}
		}
		return desired;
	}

	static Set<String> desiredYoutubeQueueWindowUrlsLocked(MediaRuntimeState state) {
		Set<String> desired = new LinkedHashSet<>();
		if (state == null || state.youtubeQueue.isEmpty()) {
			return desired;
		}
		if (state.youtubeMusicShuffleEnabled) {
			syncYoutubeMusicShuffleStateLocked(state, true);
			if (!state.youtubeMusicShuffleOrder.isEmpty()) {
				int size = state.youtubeMusicShuffleOrder.size();
				int cursor = state.youtubeMusicShuffleCursor >= 0 ? state.youtubeMusicShuffleCursor : 0;
				int maxPrevious = Math.min(YOUTUBE_PRELOAD_PREVIOUS_COUNT, Math.max(0, size - 1));
				int maxNext = Math.min(YOUTUBE_PRELOAD_NEXT_COUNT, Math.max(0, size - 1));
				for (int offset = 0; offset <= maxNext; offset++) {
					int queueIndex = state.youtubeMusicShuffleOrder.get(Math.floorMod(cursor + offset, size));
					YoutubeQueueItem item = state.youtubeQueue.get(queueIndex);
					if (item != null && item.url() != null && !item.url().isBlank()) {
						desired.add(item.url());
					}
				}
				for (int offset = 1; offset <= maxPrevious; offset++) {
					int queueIndex = state.youtubeMusicShuffleOrder.get(Math.floorMod(cursor - offset, size));
					YoutubeQueueItem item = state.youtubeQueue.get(queueIndex);
					if (item != null && item.url() != null && !item.url().isBlank()) {
						desired.add(item.url());
					}
				}
				return desired;
			}
		}
		int anchorIndex = state.youtubeQueueIndex >= 0 && state.youtubeQueueIndex < state.youtubeQueue.size() ? state.youtubeQueueIndex : -1;
		if (anchorIndex < 0) {
			for (int index = 0; index < Math.min(state.youtubeQueue.size(), YOUTUBE_PRELOAD_NEXT_COUNT); index++) {
				YoutubeQueueItem item = state.youtubeQueue.get(index);
				if (item != null && item.url() != null && !item.url().isBlank()) {
					desired.add(item.url());
				}
			}
			return desired;
		}
		YoutubeQueueItem current = state.youtubeQueue.get(anchorIndex);
		if (current != null && current.url() != null && !current.url().isBlank()) {
			desired.add(current.url());
		}
		for (int index = anchorIndex + 1; index <= Math.min(state.youtubeQueue.size() - 1, anchorIndex + YOUTUBE_PRELOAD_NEXT_COUNT); index++) {
			YoutubeQueueItem item = state.youtubeQueue.get(index);
			if (item != null && item.url() != null && !item.url().isBlank()) {
				desired.add(item.url());
			}
		}
		for (int index = Math.max(0, anchorIndex - YOUTUBE_PRELOAD_PREVIOUS_COUNT); index < anchorIndex; index++) {
			YoutubeQueueItem item = state.youtubeQueue.get(index);
			if (item != null && item.url() != null && !item.url().isBlank()) {
				desired.add(item.url());
			}
		}
		return desired;
	}

	static YoutubeQueuePreloadDiff syncYoutubeQueuePreloadsLocked(MediaRuntimeState state) {
		if (state == null) {
			return YoutubeQueuePreloadDiff.EMPTY;
		}
		Set<String> desired = desiredYoutubeQueueWindowUrlsLocked(state);
		if (!state.galleryItems.isEmpty()) {
			for (GalleryItem item : state.galleryItems) {
				if (item == null || effectiveGalleryItemKind(item) != GalleryItemKind.YOUTUBE || item.url() == null || item.url().isBlank()) {
					continue;
				}
				desired.add(item.url());
			}
		}
		List<String> toRelease = new ArrayList<>();
		for (String url : List.copyOf(state.retainedYoutubePreloadUrls)) {
			if (!desired.contains(url)) {
				state.retainedYoutubePreloadUrls.remove(url);
				toRelease.add(url);
			}
		}
		List<String> toRetain = new ArrayList<>();
		for (String url : desired) {
			if (state.retainedYoutubePreloadUrls.add(url)) {
				toRetain.add(url);
			}
		}
		if (toRetain.isEmpty() && toRelease.isEmpty()) {
			return YoutubeQueuePreloadDiff.EMPTY;
		}
		return new YoutubeQueuePreloadDiff(List.copyOf(toRetain), List.copyOf(toRelease));
	}

	static YoutubeMusicQueuePreloadDiff syncYoutubeMusicQueuePreloadsLocked(MediaRuntimeState state) {
		if (state == null) {
			return YoutubeMusicQueuePreloadDiff.EMPTY;
		}
		Set<String> desired = isYoutubeMusicMode(state.mode) ? desiredYoutubeMusicWindowUrlsLocked(state) : Set.of();
		List<String> toRelease = new ArrayList<>();
		for (String url : List.copyOf(state.retainedYoutubeMusicUrls)) {
			if (!desired.contains(url)) {
				state.retainedYoutubeMusicUrls.remove(url);
				toRelease.add(url);
			}
		}
		List<String> toRetain = new ArrayList<>();
		for (String url : desired) {
			if (state.retainedYoutubeMusicUrls.add(url)) {
				toRetain.add(url);
			}
		}
		if (toRetain.isEmpty() && toRelease.isEmpty()) {
			return YoutubeMusicQueuePreloadDiff.EMPTY;
		}
		return new YoutubeMusicQueuePreloadDiff(List.copyOf(toRetain), List.copyOf(toRelease));
	}

	static void applyYoutubeQueuePreloadDiff(YoutubeQueuePreloadDiff diff) {
		if (diff == null || diff.isEmpty()) {
			return;
		}
		releaseYoutubeQueuePreloads(diff.releaseUrls());
		retainYoutubeQueuePreloads(diff.retainUrls());
	}

	static void applyYoutubeMusicQueuePreloadDiff(YoutubeMusicQueuePreloadDiff diff) {
		if (diff == null || diff.isEmpty()) {
			return;
		}
		releaseYoutubeMusicQueuePreloads(diff.releaseUrls());
		retainYoutubeMusicQueuePreloads(diff.retainUrls());
	}

	static void retainYoutubeQueuePreloads(List<String> urls) {
		if (urls == null || urls.isEmpty()) {
			return;
		}
		for (String url : List.copyOf(urls)) {
			try {
				MonitorYoutubeRelayClient.retainQueueEntry(url);
			} catch (Exception exception) {
				Lg2.LOGGER.debug("Failed to retain YouTube queue preload for {}", url, exception);
			}
		}
	}

	static void releaseYoutubeQueuePreloads(List<String> urls) {
		if (urls == null || urls.isEmpty()) {
			return;
		}
		for (String url : List.copyOf(urls)) {
			try {
				MonitorYoutubeRelayClient.releaseQueueEntry(url);
			} catch (Exception exception) {
				Lg2.LOGGER.debug("Failed to release YouTube queue preload for {}", url, exception);
			}
		}
	}

	static void retainYoutubeMusicQueuePreloads(List<String> urls) {
		if (urls == null || urls.isEmpty()) {
			return;
		}
		for (String url : List.copyOf(urls)) {
			try {
				MonitorYoutubeMusicCache.retainQueueEntry(url);
			} catch (Exception exception) {
				Lg2.LOGGER.debug("Failed to retain YouTube Music queue preload for {}", url, exception);
			}
		}
	}

	static void releaseYoutubeMusicQueuePreloads(List<String> urls) {
		if (urls == null || urls.isEmpty()) {
			return;
		}
		for (String url : List.copyOf(urls)) {
			try {
				MonitorYoutubeMusicCache.releaseQueueEntry(url);
			} catch (Exception exception) {
				Lg2.LOGGER.debug("Failed to release YouTube Music queue preload for {}", url, exception);
			}
		}
	}

	static void clearYoutubeQueueLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		state.youtubeQueue.clear();
		state.youtubeQueueTotalDurationMs = 0L;
		clearYoutubeMusicShuffleOrderLocked(state);
		state.youtubeQueueIndex = -1;
		state.youtubeQueueScroll = 0;
		state.youtubeQueueOpen = false;
		state.youtubeQueueCacheStatusRefreshScheduled = false;
	}

	static void recalculateYoutubeQueueDurationLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		long totalDurationMs = 0L;
		for (YoutubeQueueItem item : state.youtubeQueue) {
			if (item != null) {
				totalDurationMs += Math.max(0L, item.durationMs());
			}
		}
		state.youtubeQueueTotalDurationMs = totalDurationMs;
	}

	static void clearYoutubePlaybackLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		clearPendingAudioTransportLocked(state);
		clearDownloadStateLocked(state);
		state.streamKind = PlaybackStreamKind.NONE;
		state.loadedMedia = null;
		state.streamFrame = null;
		state.youtubeFrameSequence = 0L;
		state.sourceUrl = null;
		state.relaySessionId = null;
		state.audioStreamUrl = null;
		state.mediaTitle = "";
		state.mediaSubtitle = "";
		state.frameIndex = 0;
		state.positionMs = 0L;
		state.durationMs = 0L;
		state.bufferedStartMs = 0L;
		state.bufferedEndMs = 0L;
		state.liveStream = false;
		state.audioPlaceholder = true;
		state.loadingBackdropFrame = null;
		state.liveCameraCaptureInFlight = false;
		state.pendingLiveCameraPixels = null;
		state.liveCameraDecodeScheduled = false;
		state.liveCameraLastFrameAtMillis = 0L;
		state.liveCameraBufferedTiles = null;
		state.liveCameraDisplayedTiles = null;
		state.liveCameraDisplayedGeneration = 0L;
		state.nextLiveCameraPreviewDecodeAtMillis = 0L;
		state.pendingLiveCameraPreparedTiles = null;
		state.pendingLiveCameraApplyUrl = null;
		state.liveCameraApplyScheduled = false;
		state.gallerySlideshowSettingsOpen = false;
		state.gallerySlideshowAdvanceAtMillis = 0L;
	}

	static void clearGalleryLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		clearDownloadStateLocked(state);
		state.galleryItems.clear();
		state.galleryLoadingUrls.clear();
		state.galleryHydrationLoading = false;
		state.galleryHydrationRequestId++;
		state.galleryDeleteConfirmOpen = false;
		state.galleryFileMenuOpen = false;
		state.galleryHydrated = false;
		clearGalleryPendingOpenLocked(state);
		state.galleryIndex = -1;
		state.galleryScroll = 0;
		state.galleryPreloadStatusRefreshScheduled = false;
		state.galleryPreloadStatusRefreshStep = 0;
		state.gallerySlideshowEnabled = false;
		state.gallerySlideshowSettingsOpen = false;
		state.gallerySlideshowAdvanceAtMillis = 0L;
		state.gallerySurfaceMode = GallerySurfaceMode.BROWSER;
		state.playerBackgroundMenuOpen = false;
		state.preserveRuntimeOnNextViewModeTransition = false;
		clearPlayerBackgroundGalleryPickerLocked(state);
		clearGalleryBulkSelectionLocked(state);
		clearGallerySelectionLocked(state);
	}

	static int beginGalleryPendingOpenLocked(MediaRuntimeState state, String url, int preferredIndex) {
		if (state == null || url == null || url.isBlank()) {
			return 0;
		}
		state.galleryNextOpenRequestId = MonitorMediaSessionPolicy.nextGalleryOpenRequestId(state.galleryNextOpenRequestId);
		state.galleryPendingOpenUrl = url;
		state.galleryPendingOpenIndex = preferredIndex;
		state.galleryPendingOpenRequestId = state.galleryNextOpenRequestId;
		return state.galleryPendingOpenRequestId;
	}

	static void clearGalleryPendingOpenLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		state.galleryPendingOpenUrl = null;
		state.galleryPendingOpenIndex = -1;
		state.galleryPendingOpenRequestId = 0;
	}

	static void clearWallpaperLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		state.wallpaperUrl = null;
		state.wallpaperMedia = null;
		state.wallpaperScaleMode = MediaScaleMode.FIT;
		state.wallpaperBackgroundMode = PlayerBackgroundMode.EMPTY;
		state.wallpaperFrameIndex = 0;
		state.nextWallpaperFrameAtMillis = 0L;
		state.wallpaperLoading = false;
	}

	static void clearPlayerBackgroundLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		state.playerBackgroundMedia = null;
		state.playerBackgroundUrl = null;
		state.playerBackgroundScaleMode = MediaScaleMode.FILL;
		state.playerBackgroundFrameIndex = 0;
		state.nextPlayerBackgroundFrameAtMillis = 0L;
		state.playerBackgroundLoading = false;
	}

	static boolean consumePreservedRuntimeTransitionLocked(MediaRuntimeState state, ScreenViewMode nextMode) {
		if (state == null || !state.preserveRuntimeOnNextViewModeTransition || nextMode == null || nextMode != state.mode) {
			return false;
		}
		state.preserveRuntimeOnNextViewModeTransition = false;
		return true;
	}

	static void beginPlayerBackgroundGalleryPickerLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		clearGalleryBulkSelectionLocked(state);
		state.playerBackgroundGalleryPickerOpen = true;
		state.playerBackgroundGalleryPickerReturnMode = state.mode;
		state.playerBackgroundGalleryPickerReturnSurfaceMode = state.gallerySurfaceMode;
		state.mode = ScreenViewMode.GALLERY;
		state.gallerySurfaceMode = GallerySurfaceMode.BROWSER;
		state.galleryDeleteConfirmOpen = false;
		state.youtubeQueueOpen = false;
		state.overlayMode = MediaOverlayMode.CONTROLS;
		state.statusText = "";
		state.preserveRuntimeOnNextViewModeTransition = true;
	}

	static boolean restorePlayerBackgroundGalleryPickerLocked(MediaRuntimeState state) {
		if (state == null || !state.playerBackgroundGalleryPickerOpen) {
			return false;
		}
		state.mode = state.playerBackgroundGalleryPickerReturnMode != null ? state.playerBackgroundGalleryPickerReturnMode : ScreenViewMode.HOME;
		if (state.mode == ScreenViewMode.GALLERY) {
			state.gallerySurfaceMode = state.playerBackgroundGalleryPickerReturnSurfaceMode != null
					? state.playerBackgroundGalleryPickerReturnSurfaceMode
					: GallerySurfaceMode.BROWSER;
		}
		state.preserveRuntimeOnNextViewModeTransition = true;
		clearPlayerBackgroundGalleryPickerLocked(state);
		return true;
	}

	static void clearPlayerBackgroundGalleryPickerLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		state.playerBackgroundGalleryPickerOpen = false;
		state.playerBackgroundGalleryPickerReturnMode = null;
		state.playerBackgroundGalleryPickerReturnSurfaceMode = null;
	}

	static void setGalleryBulkSelectionModeLocked(MediaRuntimeState state, boolean enabled) {
		if (state == null) {
			return;
		}
		state.galleryBulkSelectionMode = enabled;
		if (enabled) {
			normalizeGalleryBulkSelectionLocked(state);
		} else {
			state.galleryBulkSelectedKeys.clear();
		}
	}

	static void clearGalleryBulkSelectionLocked(MediaRuntimeState state) {
		setGalleryBulkSelectionModeLocked(state, false);
	}

	static void setGalleryBulkAllSelectedLocked(MediaRuntimeState state, boolean selected) {
		if (state == null) {
			return;
		}
		state.galleryBulkSelectionMode = true;
		state.galleryBulkSelectedKeys.clear();
		if (!selected) {
			return;
		}
		for (int index = 0; index < state.galleryItems.size(); index++) {
			String key = galleryBulkSelectionKey(state.galleryItems.get(index), index);
			if (!key.isBlank()) {
				state.galleryBulkSelectedKeys.add(key);
			}
		}
	}

	static boolean allGalleryBulkItemsSelectedLocked(MediaRuntimeState state) {
		if (state == null || state.galleryItems.isEmpty()) {
			return false;
		}
		normalizeGalleryBulkSelectionLocked(state);
		boolean hasSelectableItems = false;
		for (int index = 0; index < state.galleryItems.size(); index++) {
			String key = galleryBulkSelectionKey(state.galleryItems.get(index), index);
			if (key.isBlank()) {
				continue;
			}
			hasSelectableItems = true;
			if (!state.galleryBulkSelectedKeys.contains(key)) {
				return false;
			}
		}
		return hasSelectableItems;
	}

	static boolean toggleGalleryBulkItemLocked(MediaRuntimeState state, int galleryIndex) {
		if (state == null || galleryIndex < 0 || galleryIndex >= state.galleryItems.size()) {
			return false;
		}
		String key = galleryBulkSelectionKey(state.galleryItems.get(galleryIndex), galleryIndex);
		if (key.isBlank()) {
			return false;
		}
		state.galleryBulkSelectionMode = true;
		boolean selected;
		if (state.galleryBulkSelectedKeys.contains(key)) {
			state.galleryBulkSelectedKeys.remove(key);
			selected = false;
		} else {
			state.galleryBulkSelectedKeys.add(key);
			selected = true;
		}
		normalizeGalleryBulkSelectionLocked(state);
		return selected;
	}

	static boolean galleryBulkItemSelectedLocked(MediaRuntimeState state, int galleryIndex) {
		if (state == null || galleryIndex < 0 || galleryIndex >= state.galleryItems.size()) {
			return false;
		}
		return state.galleryBulkSelectedKeys.contains(galleryBulkSelectionKey(state.galleryItems.get(galleryIndex), galleryIndex));
	}

	static List<GalleryItem> selectedGalleryItemsForShareLocked(MediaRuntimeState state) {
		if (state == null || state.galleryItems.isEmpty()) {
			return List.of();
		}
		if (!state.galleryBulkSelectionMode || state.galleryBulkSelectedKeys.isEmpty()) {
			GalleryItem current = currentGalleryItemLocked(state);
			return current != null ? List.of(current) : List.of();
		}
		normalizeGalleryBulkSelectionLocked(state);
		if (state.galleryBulkSelectedKeys.isEmpty()) {
			return List.of();
		}
		Set<String> selectedKeys = new HashSet<>(state.galleryBulkSelectedKeys);
		List<GalleryItem> selected = new ArrayList<>();
		for (int index = 0; index < state.galleryItems.size(); index++) {
			GalleryItem item = state.galleryItems.get(index);
			if (selectedKeys.contains(galleryBulkSelectionKey(item, index)) && item != null) {
				selected.add(item);
			}
		}
		return selected.isEmpty() ? List.of() : List.copyOf(selected);
	}

	static List<Integer> selectedGalleryIndexesLocked(MediaRuntimeState state) {
		if (state == null || state.galleryItems.isEmpty() || state.galleryBulkSelectedKeys.isEmpty()) {
			return List.of();
		}
		normalizeGalleryBulkSelectionLocked(state);
		if (state.galleryBulkSelectedKeys.isEmpty()) {
			return List.of();
		}
		Set<String> selectedKeys = new HashSet<>(state.galleryBulkSelectedKeys);
		List<Integer> selected = new ArrayList<>();
		for (int index = 0; index < state.galleryItems.size(); index++) {
			GalleryItem item = state.galleryItems.get(index);
			if (selectedKeys.contains(galleryBulkSelectionKey(item, index)) && item != null) {
				selected.add(index);
			}
		}
		return selected.isEmpty() ? List.of() : List.copyOf(selected);
	}

	static List<Integer> selectedGallerySlideshowIndexesLocked(MediaRuntimeState state) {
		List<Integer> selectedIndexes = selectedGalleryIndexesLocked(state);
		if (selectedIndexes.isEmpty()) {
			return List.of();
		}
		List<Integer> playable = new ArrayList<>();
		for (int index : selectedIndexes) {
			if (index >= 0 && index < state.galleryItems.size() && galleryItemCanParticipateInSlideshow(state.galleryItems.get(index))) {
				playable.add(index);
			}
		}
		return playable.isEmpty() ? List.of() : List.copyOf(playable);
	}

	static boolean galleryItemCanParticipateInSlideshow(GalleryItem item) {
		if (item == null) {
			return false;
		}
		GalleryItemKind kind = effectiveGalleryItemKind(item);
		if (kind == GalleryItemKind.LIVE_CAMERA) {
			return false;
		}
		if (kind == GalleryItemKind.YOUTUBE) {
			return item.url() != null && !item.url().isBlank();
		}
		return item.media() != null || (item.url() != null && !item.url().isBlank());
	}

	static void normalizeGalleryBulkSelectionLocked(MediaRuntimeState state) {
		if (state == null || state.galleryBulkSelectedKeys.isEmpty()) {
			return;
		}
		Set<String> activeKeys = new HashSet<>();
		for (int index = 0; index < state.galleryItems.size(); index++) {
			String key = galleryBulkSelectionKey(state.galleryItems.get(index), index);
			if (!key.isBlank()) {
				activeKeys.add(key);
			}
		}
		state.galleryBulkSelectedKeys.removeIf(key -> !activeKeys.contains(key));
	}

	static String galleryBulkSelectionKey(GalleryItem item, int index) {
		if (item == null) {
			return index >= 0 ? "slot:" + index : "";
		}
		if (item.localMediaKey() != null && !item.localMediaKey().isBlank()) {
			return "local:" + item.localMediaKey();
		}
		if (item.url() != null && !item.url().isBlank()) {
			return "url:" + item.url();
		}
		return index >= 0 ? "slot:" + index : "";
	}

	static List<GalleryCacheCandidate> removeGalleryBulkSelectionLocked(MediaRuntimeState state, UiLayout layout) {
		if (state == null || state.galleryItems.isEmpty()) {
			clearGalleryBulkSelectionLocked(state);
			return List.of();
		}
		normalizeGalleryBulkSelectionLocked(state);
		if (state.galleryBulkSelectedKeys.isEmpty()) {
			return List.of();
		}
		Set<String> selectedKeys = new HashSet<>(state.galleryBulkSelectedKeys);
		List<GalleryCacheCandidate> removedCandidates = new ArrayList<>();
		boolean removedCurrentPlayback = false;
		for (int index = state.galleryItems.size() - 1; index >= 0; index--) {
			GalleryItem item = state.galleryItems.get(index);
			if (!selectedKeys.contains(galleryBulkSelectionKey(item, index))) {
				continue;
			}
			if (item != null && item.url() != null && Objects.equals(item.url(), state.sourceUrl)) {
				removedCurrentPlayback = true;
			}
			GalleryRemovalResult removal = removeGalleryItemLocked(state, index, layout);
			GalleryCacheCandidate candidate = galleryCacheCandidate(removal.removedItem());
			if (candidate != null) {
				removedCandidates.add(candidate);
			}
		}
		if (removedCurrentPlayback) {
			cancelPlaybackLocked(state);
			clearGallerySelectionLocked(state);
			state.gallerySurfaceMode = GallerySurfaceMode.BROWSER;
		}
		clearGalleryBulkSelectionLocked(state);
		return removedCandidates.isEmpty() ? List.of() : List.copyOf(removedCandidates);
	}

	static void clearGallerySelectionLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		state.streamKind = PlaybackStreamKind.NONE;
		clearDownloadStateLocked(state);
		state.loadedMedia = null;
		state.sourceUrl = null;
		state.mediaTitle = "";
		state.mediaSubtitle = "";
		state.frameIndex = 0;
		state.positionMs = 0L;
		state.durationMs = 0L;
		state.bufferedStartMs = 0L;
		state.bufferedEndMs = 0L;
		state.liveStream = false;
		state.audioPlaceholder = true;
		state.liveCameraCaptureInFlight = false;
		state.pendingLiveCameraPixels = null;
		state.liveCameraDecodeScheduled = false;
		state.liveCameraLastFrameAtMillis = 0L;
		state.liveCameraBufferedTiles = null;
		state.liveCameraDisplayedTiles = null;
		state.liveCameraDisplayedGeneration = 0L;
		state.nextLiveCameraPreviewDecodeAtMillis = 0L;
		state.pendingLiveCameraPreparedTiles = null;
		state.pendingLiveCameraApplyUrl = null;
		state.liveCameraApplyScheduled = false;
	}

	static void clearLoadedContentLocked(MediaRuntimeState state) {
		clearLoadedContentLocked(state, true);
	}

	static void clearLoadedContentLocked(MediaRuntimeState state, boolean clearYoutubeQueue) {
		clearYoutubePlaybackLocked(state);
		clearGalleryLocked(state);
		if (clearYoutubeQueue) {
			clearYoutubeQueueLocked(state);
		}
	}

	static void clearTransientPlaybackStateLocked(MediaRuntimeState state, boolean clearYoutubeQueue) {
		if (state == null) {
			return;
		}
		clearYoutubePlaybackLocked(state);
		clearDownloadStateLocked(state);
		state.galleryDeleteConfirmOpen = false;
		state.galleryFileMenuOpen = false;
		clearGalleryPendingOpenLocked(state);
		state.loading = false;
		state.waitingForLink = false;
		state.youtubeReturnToGallery = false;
		state.galleryFileMenuOpen = false;
		state.playerBackgroundMenuOpen = false;
		state.gallerySlideshowSettingsOpen = false;
		state.gallerySlideshowAdvanceAtMillis = 0L;
		state.preserveRuntimeOnNextViewModeTransition = false;
		clearPlayerBackgroundGalleryPickerLocked(state);
		clearGalleryBulkSelectionLocked(state);
		clearGallerySelectionLocked(state);
		if (clearYoutubeQueue) {
			clearYoutubeQueueLocked(state);
		}
	}

	static GalleryItem currentGalleryItemLocked(MediaRuntimeState state) {
		if (state == null || state.galleryIndex < 0 || state.galleryIndex >= state.galleryItems.size()) {
			return null;
		}
		return state.galleryItems.get(state.galleryIndex);
	}

	static BufferedImage currentYoutubeMusicBackdropLocked(MediaRuntimeState state) {
		if (state == null) {
			return null;
		}
		if (state.streamFrame != null) {
			return state.streamFrame;
		}
		if (state.loadedMedia != null) {
			return state.loadedMedia.frame(state.frameIndex);
		}
		return state.loadingBackdropFrame;
	}

	static Optional<GalleryItem> currentGalleryItemMatchingUrlLocked(MediaRuntimeState state, String url) {
		if (state == null || url == null || url.isBlank()) {
			return Optional.empty();
		}
		for (GalleryItem item : state.galleryItems) {
			if (item != null && Objects.equals(item.url(), url)) {
				return Optional.of(item);
			}
		}
		return Optional.empty();
	}

	static boolean currentGalleryItemSavedLocked(MediaRuntimeState state) {
		GalleryItem item = currentGalleryItemLocked(state);
		return item != null
				&& !isTransientSupportAttachmentItem(item)
				&& effectiveGalleryItemKind(item) != GalleryItemKind.LIVE_CAMERA;
	}

	static boolean isTransientSupportAttachmentItem(GalleryItem item) {
		return item != null && item.url() != null && item.url().startsWith("support-preview:");
	}

	static boolean currentGalleryItemCanDecorateBackgroundLocked(MediaRuntimeState state) {
		GalleryItem item = currentGalleryItemLocked(state);
		return galleryItemCanDecorateBackground(item);
	}

	static boolean galleryItemCanBePlayerBackgroundCandidate(GalleryItem item) {
		return item != null
				&& effectiveGalleryItemKind(item) == GalleryItemKind.MEDIA
				&& item.url() != null
				&& !item.url().isBlank();
	}

	static boolean currentGalleryItemCanBeWallpaperLocked(MediaRuntimeState state) {
		GalleryItem item = currentGalleryItemLocked(state);
		return galleryItemCanDecorateBackground(item);
	}

	static boolean galleryItemCanDecorateBackground(GalleryItem item) {
		return item != null
				&& effectiveGalleryItemKind(item) == GalleryItemKind.MEDIA
				&& item.media() != null
				&& item.url() != null
				&& !item.url().isBlank();
	}

	static boolean compactGalleryRuntimeMediaLocked(MediaRuntimeState state) {
		if (state == null || state.galleryItems.isEmpty()) {
			return false;
		}
		boolean changed = false;
		for (int index = 0; index < state.galleryItems.size(); index++) {
			GalleryItem item = state.galleryItems.get(index);
			if (item == null || item.media() == null) {
				continue;
			}
			if (MonitorGalleryRuntimePolicy.shouldRetainDecodedMedia(
					effectiveGalleryItemKind(item),
					item.url(),
					index,
					state.galleryIndex,
					state.sourceUrl,
					state.wallpaperUrl,
					state.playerBackgroundUrl
			)) {
				continue;
			}
			BufferedImage preview = item.preview();
			if (preview == null && item.media().frameCount() > 0) {
				preview = item.media().frame(0);
			}
			state.galleryItems.set(
					index,
					new GalleryItem(
							item.title(),
							item.subtitle(),
							item.url(),
							item.localMediaKey(),
							null,
							preview,
							item.kind()
					)
			);
			changed = true;
		}
		return changed;
	}

	static boolean currentGalleryItemIsWallpaperLocked(MediaRuntimeState state) {
		GalleryItem item = currentGalleryItemLocked(state);
		return item != null
				&& item.url() != null
				&& !item.url().isBlank()
				&& Objects.equals(item.url(), state.wallpaperUrl)
				&& (state.scaleMode != null ? state.scaleMode : MediaScaleMode.FIT) == (state.wallpaperScaleMode != null ? state.wallpaperScaleMode : MediaScaleMode.FIT)
				&& resolvedPlayerBackgroundModeLocked(state) == resolvedWallpaperBackgroundModeLocked(state);
	}

	static PersistedWallpaperState persistedWallpaperStateLocked(MediaRuntimeState state) {
		if (state == null || state.wallpaperUrl == null || state.wallpaperUrl.isBlank()) {
			return null;
		}
		return new PersistedWallpaperState(
				state.wallpaperUrl,
				state.wallpaperScaleMode != null ? state.wallpaperScaleMode : MediaScaleMode.FIT,
				resolvedWallpaperBackgroundModeLocked(state)
		);
	}

	static PersistedPlayerBackgroundState persistedPlayerBackgroundStateLocked(MediaRuntimeState state) {
		if (state == null || state.playerBackgroundUrl == null || state.playerBackgroundUrl.isBlank()) {
			return null;
		}
		return new PersistedPlayerBackgroundState(
				state.playerBackgroundUrl,
				state.playerBackgroundScaleMode != null ? state.playerBackgroundScaleMode : MediaScaleMode.FILL
		);
	}

	static PlayerBackgroundMode safeWallpaperBackgroundMode(PlayerBackgroundMode mode) {
		if (mode == PlayerBackgroundMode.ARTWORK || mode == PlayerBackgroundMode.BLACK || mode == PlayerBackgroundMode.EMPTY) {
			return mode;
		}
		return PlayerBackgroundMode.EMPTY;
	}

	static PlayerBackgroundMode resolvedWallpaperBackgroundModeLocked(MediaRuntimeState state) {
		if (state == null) {
			return PlayerBackgroundMode.EMPTY;
		}
		return safeWallpaperBackgroundMode(state.wallpaperBackgroundMode);
	}

	static PlayerBackgroundMode wallpaperBackgroundModeForCurrentSelectionLocked(MediaRuntimeState state) {
		PlayerBackgroundMode selectedMode = resolvedPlayerBackgroundModeLocked(state);
		if (selectedMode == PlayerBackgroundMode.GALLERY) {
			return resolvedWallpaperBackgroundModeLocked(state);
		}
		return safeWallpaperBackgroundMode(selectedMode);
	}

	static PlayerBackgroundMode persistedPlayerBackgroundModeLocked(MediaRuntimeState state) {
		return state != null ? state.playerBackgroundMode : null;
	}

	static PlayerBackgroundMode defaultPlayerBackgroundModeLocked(MediaRuntimeState state) {
		if (state != null && state.mode == ScreenViewMode.GALLERY && state.gallerySurfaceMode == GallerySurfaceMode.PLAYER) {
			return PlayerBackgroundMode.ARTWORK;
		}
		return usesMusicPlayerLayoutLocked(state) ? PlayerBackgroundMode.ARTWORK : PlayerBackgroundMode.BLACK;
	}

	static PlayerBackgroundMode resolvedPlayerBackgroundModeLocked(MediaRuntimeState state) {
		if (state == null) {
			return PlayerBackgroundMode.BLACK;
		}
		return state.playerBackgroundMode != null ? state.playerBackgroundMode : defaultPlayerBackgroundModeLocked(state);
	}

	static MediaScaleMode playerBackgroundScaleButtonModeLocked(MediaRuntimeState state, PlayerBackgroundMode backgroundMode) {
		if (state == null) {
			return MediaScaleMode.FIT;
		}
		return switch (backgroundMode != null ? backgroundMode : resolvedPlayerBackgroundModeLocked(state)) {
			case ARTWORK, GALLERY -> state.playerBackgroundScaleMode != null ? state.playerBackgroundScaleMode : MediaScaleMode.FILL;
			case BLACK, EMPTY -> MediaScaleMode.FIT;
		};
	}

	static PlayerBackgroundMode resolvedPlayerBackgroundMode(MediaVisualSnapshot state) {
		return state != null && state.playerBackgroundMode() != null ? state.playerBackgroundMode() : PlayerBackgroundMode.BLACK;
	}

	static BufferedImage copyBufferedImage(BufferedImage source) {
		if (source == null) {
			return null;
		}
		BufferedImage copy = new BufferedImage(
				Math.max(1, source.getWidth()),
				Math.max(1, source.getHeight()),
				BufferedImage.TYPE_INT_ARGB
		);
		Graphics2D graphics = copy.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, 0, 0, null);
		} finally {
			graphics.dispose();
		}
		return copy;
	}

	static int normalizeGalleryIndexLocked(MediaRuntimeState state, int requestedIndex) {
		if (state == null || state.galleryItems.isEmpty()) {
			return -1;
		}
		return Math.floorMod(requestedIndex, state.galleryItems.size());
	}

	static boolean selectGalleryItemLocked(MediaRuntimeState state, int requestedIndex, UiLayout layout) {
		int resolvedIndex = normalizeGalleryIndexLocked(state, requestedIndex);
		if (resolvedIndex < 0) {
			clearGallerySelectionLocked(state);
			state.galleryIndex = -1;
			state.galleryScroll = 0;
			state.gallerySurfaceMode = GallerySurfaceMode.BROWSER;
			return false;
		}
		GalleryItem item = state.galleryItems.get(resolvedIndex);
		GalleryItemKind itemKind = effectiveGalleryItemKind(item);
		if (item == null || itemKind == GalleryItemKind.YOUTUBE || (itemKind != GalleryItemKind.LIVE_CAMERA && item.media() == null)) {
			return false;
		}
		cancelPlaybackLocked(state);
		clearYoutubePlaybackLocked(state);
		state.galleryIndex = resolvedIndex;
		state.galleryDeleteConfirmOpen = false;
		state.sourceUrl = item.url();
		state.mediaTitle = item.title();
		state.mediaSubtitle = item.subtitle();
		state.frameIndex = 0;
		state.userPaused = false;
		state.gallerySlideshowSettingsOpen = false;
		resetGallerySlideshowDeadlineLocked(state);
		state.gallerySurfaceMode = GallerySurfaceMode.PLAYER;
		state.positionMs = 0L;
		state.durationMs = 0L;
		state.bufferedStartMs = 0L;
		state.bufferedEndMs = 0L;
		state.liveStream = itemKind == GalleryItemKind.LIVE_CAMERA;
		state.overlayMode = itemKind == GalleryItemKind.LIVE_CAMERA ? MediaOverlayMode.VIEW : MediaOverlayMode.CONTROLS;
		state.audioPlaceholder = true;
		state.progress.clear();
		if (itemKind == GalleryItemKind.LIVE_CAMERA) {
			state.streamKind = PlaybackStreamKind.LIVE_CAMERA;
			state.loading = true;
			state.statusText = "LIVE";
			state.loadedMedia = null;
			state.streamFrame = item.preview();
			state.loadingBackdropFrame = item.preview();
			state.relaySessionId = null;
			state.audioStreamUrl = null;
			state.liveCameraCaptureInFlight = false;
			state.pendingLiveCameraPixels = null;
			state.liveCameraDecodeScheduled = false;
			state.liveCameraLastFrameAtMillis = 0L;
			state.liveCameraBufferedTiles = null;
			state.liveCameraDisplayedTiles = null;
			state.liveCameraDisplayedGeneration = 0L;
			state.nextLiveCameraPreviewDecodeAtMillis = 0L;
		} else {
			state.loadedMedia = item.media();
			state.loading = false;
			state.statusText = "";
		}
		int visibleRows = galleryVisibleRowsPreview(layout);
		int totalRows = galleryTotalRowsPreview(state.galleryItems.size(), layout);
		int maxScroll = Math.max(0, totalRows - visibleRows);
		int targetRow = galleryRowForIndexPreview(resolvedIndex, layout);
		if (targetRow < state.galleryScroll) {
			state.galleryScroll = targetRow;
		} else if (targetRow >= state.galleryScroll + visibleRows) {
			state.galleryScroll = targetRow - visibleRows + 1;
		}
		state.galleryScroll = clampInt(state.galleryScroll, 0, maxScroll);
		compactGalleryRuntimeMediaLocked(state);
		return true;
	}

	static boolean openTransientGalleryItemLocked(MediaRuntimeState state, String title, String url, MonitorMediaApp.LoadedMedia media) {
		if (state == null || media == null) {
			return false;
		}
		cancelPlaybackLocked(state);
		clearYoutubePlaybackLocked(state);
		state.mode = ScreenViewMode.GALLERY;
		state.loadedMedia = media;
		state.galleryDeleteConfirmOpen = false;
		state.sourceUrl = url;
		state.mediaTitle = title == null || title.isBlank() ? galleryItemTitle(url, media, state.galleryItems.size() + 1) : title;
		state.frameIndex = 0;
		state.userPaused = false;
		state.gallerySlideshowSettingsOpen = false;
		resetGallerySlideshowDeadlineLocked(state);
		state.galleryIndex = -1;
		state.gallerySurfaceMode = GallerySurfaceMode.PLAYER;
		state.positionMs = 0L;
		state.durationMs = 0L;
		state.bufferedStartMs = 0L;
		state.bufferedEndMs = 0L;
		state.liveStream = false;
		state.audioPlaceholder = true;
		return true;
	}

	static boolean canImportHeldPhotoPrintToGallery(ServerPlayer player) {
		ItemStack stack = heldPhotoPrintStack(player);
		PhotoPrintData data = PhotoPrintData.readPhotoItem(stack);
		return data != null && data.isValid() && data.sourceKey() != null && !data.sourceKey().isBlank();
	}

	static ItemStack heldPhotoPrintStack(ServerPlayer player) {
		if (player == null) {
			return ItemStack.EMPTY;
		}
		ItemStack mainHand = player.getMainHandItem();
		if (mainHand != null && mainHand.is(ModItems.PHOTO_PRINT)) {
			return mainHand;
		}
		ItemStack offHand = player.getOffhandItem();
		if (offHand != null && offHand.is(ModItems.PHOTO_PRINT)) {
			return offHand;
		}
		return ItemStack.EMPTY;
	}

	static void importHeldPhotoPrintToGallery(MinecraftServer server, ScreenRuntimeKey key, ServerPlayer player, UiLayout layout) {
		if (server == null || key == null || player == null) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return;
		}
		ItemStack stack = heldPhotoPrintStack(player);
		PhotoPrintData data = PhotoPrintData.readPhotoItem(stack);
		if (data == null || !data.isValid() || data.sourceKey() == null || data.sourceKey().isBlank()) {
			return;
		}
		String title = stack.get(DataComponents.CUSTOM_NAME) != null
				? stack.get(DataComponents.CUSTOM_NAME).getString()
				: (data.isVideo() ? "Camera Video" : "Photo");
		String syntheticUrl = cameraGalleryUrl(data);
		String localMediaKey;
		try {
			localMediaKey = data.isVideo()
					? MonitorMediaApp.persistLocalGalleryFile("camera-video-" + data.sourceKey(), CameraMediaCache.videoSourcePath(data.sourceKey()))
					: MonitorMediaApp.persistLocalGalleryFile("camera-photo-" + data.sourceKey(), CameraMediaCache.photoSourcePath(data.sourceKey()));
		} catch (Exception exception) {
			return;
		}
		GalleryItemKind kind = data.isVideo() ? GalleryItemKind.VIDEO : GalleryItemKind.MEDIA;
		int preferredIndex;
		synchronized (state) {
			preferredIndex = upsertGalleryItemLocked(state, title, "", syntheticUrl, localMediaKey, null, null, kind);
			state.loading = true;
			state.waitingForLink = false;
			state.overlayMode = MediaOverlayMode.CONTROLS;
			state.statusText = "IMPORTING";
			state.progress.setIndeterminate("IMPORTING");
			beginGalleryPendingOpenLocked(state, syntheticUrl, preferredIndex);
			state.version++;
		}
		persistGalleryState(server, key, state);
		requestRuntimeRender(server, key);
		scheduleGalleryItemLoad(server, key, title, syntheticUrl, localMediaKey, kind, true, preferredIndex);
	}

	static String cameraGalleryUrl(PhotoPrintData data) {
		if (data == null || data.sourceKey() == null || data.sourceKey().isBlank()) {
			return "";
		}
		return CAMERA_GALLERY_URL_PREFIX + (data.isVideo() ? "video:" : "photo:") + data.sourceKey().trim();
	}

	static boolean isCameraGalleryVideoUrl(String url) {
		return !cameraGallerySourceKey(url, "video").isBlank();
	}

	static String cameraGallerySourceKey(String url, String mediaKind) {
		if (url == null || mediaKind == null || mediaKind.isBlank()) {
			return "";
		}
		String normalized = url.trim();
		String prefix = CAMERA_GALLERY_URL_PREFIX + mediaKind + ":";
		if (!normalized.startsWith(prefix)) {
			return "";
		}
		return normalized.substring(prefix.length()).trim();
	}

	static boolean saveCurrentGalleryItemLocked(MediaRuntimeState state, UiLayout layout) {
		if (state == null || state.loadedMedia == null || state.sourceUrl == null || state.sourceUrl.isBlank()) {
			return false;
		}
		if (currentGalleryItemSavedLocked(state)) {
			return true;
		}
		String title = state.mediaTitle == null || state.mediaTitle.isBlank()
				? galleryItemTitle(state.sourceUrl, state.loadedMedia, state.galleryItems.size() + 1)
				: state.mediaTitle;
		int index = upsertGalleryItemLocked(
				state,
				title,
				"",
				state.sourceUrl,
				null,
				state.loadedMedia,
				state.loadedMedia.frameCount() > 0 ? state.loadedMedia.frame(0) : null,
				GalleryItemKind.MEDIA
		);
		return index >= 0 && selectGalleryItemLocked(state, index, layout);
	}

	static boolean saveCurrentYoutubeToGalleryLocked(MediaRuntimeState state) {
		return saveCurrentYoutubeToGalleryLocked(state, null);
	}

	static boolean saveCurrentYoutubeToGalleryLocked(MediaRuntimeState state, String localMediaKey) {
		return saveCurrentYoutubeToGalleryLocked(state, localMediaKey, null);
	}

	static boolean saveCurrentYoutubeToGalleryLocked(MediaRuntimeState state, String localMediaKey, GalleryItemKind forcedKind) {
		if (state == null || state.sourceUrl == null || state.sourceUrl.isBlank()) {
			return false;
		}
		String title = state.mediaTitle == null || state.mediaTitle.isBlank()
				? (state.mode == ScreenViewMode.YOUTUBE_MUSIC ? "Track" : "YouTube")
				: state.mediaTitle;
		GalleryItemKind kind = forcedKind != null ? forcedKind : state.mode == ScreenViewMode.YOUTUBE_MUSIC ? GalleryItemKind.AUDIO : GalleryItemKind.YOUTUBE;
		return upsertGalleryItemLocked(
				state,
				title,
				state.mediaSubtitle,
				state.sourceUrl,
				localMediaKey,
				null,
				copyBufferedImage(state.streamFrame),
				kind
		) >= 0;
	}

	static int upsertGalleryItemLocked(
			MediaRuntimeState state,
			String title,
			String subtitle,
			String url,
			String localMediaKey,
			MonitorMediaApp.LoadedMedia media,
			BufferedImage preview,
			GalleryItemKind kind
	) {
		if (state == null || url == null || url.isBlank()) {
			return -1;
		}
		int existingIndex = resolveGalleryItemIndex(state, url, -1);
		String resolvedTitle = title == null || title.isBlank()
				? (media != null ? galleryItemTitle(url, media, state.galleryItems.size() + 1) : "Media")
				: title;
		if (existingIndex >= 0 && existingIndex < state.galleryItems.size()) {
			GalleryItem existing = state.galleryItems.get(existingIndex);
			state.galleryItems.set(
					existingIndex,
					new GalleryItem(
							resolvedTitle,
							subtitle != null && !subtitle.isBlank() ? subtitle : existing != null ? existing.subtitle() : "",
							url,
							localMediaKey != null && !localMediaKey.isBlank() ? localMediaKey : existing != null ? existing.localMediaKey() : null,
							media != null ? media : existing != null ? existing.media() : null,
							preview != null ? preview : existing != null ? existing.preview() : null,
							kind != null ? kind : existing != null ? existing.kind() : GalleryItemKind.MEDIA
					)
			);
			return existingIndex;
		}
		state.galleryItems.add(new GalleryItem(
				resolvedTitle,
				subtitle != null ? subtitle : "",
				url,
				localMediaKey,
				media,
				preview,
				kind != null ? kind : GalleryItemKind.MEDIA
		));
		return state.galleryItems.size() - 1;
	}

	static void markDownloadStartedLocked(MediaRuntimeState state, String url, UUID requesterUuid) {
		if (state == null) {
			return;
		}
		state.downloadInProgress = true;
		state.downloadTargetUrl = url;
		state.downloadRequesterUuid = requesterUuid;
		state.downloadStartedAtMillis = System.currentTimeMillis();
		state.downloadCompletedUrl = null;
		state.downloadCompletedUntilMillis = 0L;
	}

	static void markDownloadCompletedLocked(MediaRuntimeState state, String url) {
		if (state == null) {
			return;
		}
		state.downloadInProgress = false;
		state.downloadTargetUrl = null;
		state.downloadRequesterUuid = null;
		state.downloadStartedAtMillis = 0L;
		state.downloadCompletedUrl = url;
		state.downloadCompletedUntilMillis = System.currentTimeMillis() + MEDIA_ACTION_COMPLETE_VISIBLE_MILLIS;
	}

	static void clearDownloadStateLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		state.downloadInProgress = false;
		state.downloadTargetUrl = null;
		state.downloadRequesterUuid = null;
		state.downloadStartedAtMillis = 0L;
	}

	static long remainingDownloadSpinnerMillisLocked(MediaRuntimeState state) {
		if (state == null || state.downloadStartedAtMillis <= 0L) {
			return 0L;
		}
		long elapsed = System.currentTimeMillis() - state.downloadStartedAtMillis;
		return Math.max(0L, MEDIA_ACTION_SPINNER_MIN_MILLIS - elapsed);
	}

	static void scheduleActionCompletionReset(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		ensureExecutors();
		mediaScheduler.schedule(() -> server.execute(() -> requestRuntimeRender(server, key)), MEDIA_ACTION_COMPLETE_VISIBLE_MILLIS, TimeUnit.MILLISECONDS);
	}

	static boolean isYoutubeFullyBufferedLocked(MediaRuntimeState state) {
		if (state == null || state.liveStream || state.durationMs <= 0L) {
			return false;
		}
		return state.bufferedStartMs <= YOUTUBE_FULLY_BUFFERED_TOLERANCE_MS
				&& state.bufferedEndMs >= Math.max(0L, state.durationMs - YOUTUBE_FULLY_BUFFERED_TOLERANCE_MS);
	}

	static boolean isYoutubeGalleryDownloadReadyLocked(MediaRuntimeState state) {
		if (state == null || state.liveStream || state.sourceUrl == null || state.sourceUrl.isBlank()) {
			return false;
		}
		if (state.mode == ScreenViewMode.YOUTUBE_MUSIC) {
			return MonitorYoutubeMusicCache.isQueueEntryLoaded(state.sourceUrl);
		}
		if (MonitorYoutubeRelayClient.isQueueEntryLoaded(state.sourceUrl)) {
			return true;
		}
		long preloadTargetMs = MonitorYoutubeRelayClient.queuePreloadDurationMs();
		if (state.durationMs > 0L) {
			preloadTargetMs = Math.min(preloadTargetMs, state.durationMs);
		}
		long requiredBufferedEndMs = Math.max(0L, preloadTargetMs - YOUTUBE_FULLY_BUFFERED_TOLERANCE_MS);
		return state.bufferedStartMs <= YOUTUBE_FULLY_BUFFERED_TOLERANCE_MS
				&& state.bufferedEndMs >= requiredBufferedEndMs;
	}

	static GalleryRemovalResult removeGalleryItemLocked(MediaRuntimeState state, int requestedIndex, UiLayout layout) {
		if (state == null || state.galleryItems.isEmpty()) {
			clearGallerySelectionLocked(state);
			clearGalleryBulkSelectionLocked(state);
			state.galleryIndex = -1;
			state.galleryScroll = 0;
			return new GalleryRemovalResult(null, false);
		}
		int resolvedIndex = clampInt(requestedIndex, 0, state.galleryItems.size() - 1);
		GalleryItem removed = state.galleryItems.remove(resolvedIndex);
		if (removed != null) {
			state.galleryBulkSelectedKeys.remove(galleryBulkSelectionKey(removed, resolvedIndex));
			normalizeGalleryBulkSelectionLocked(state);
		}
		if (removed != null && removed.url() != null && Objects.equals(removed.url(), state.wallpaperUrl)) {
			clearWallpaperLocked(state);
		}
		if (removed != null && removed.url() != null && Objects.equals(removed.url(), state.playerBackgroundUrl)) {
			clearPlayerBackgroundLocked(state);
		}
		if (state.galleryItems.isEmpty()) {
			clearGallerySelectionLocked(state);
			clearGalleryBulkSelectionLocked(state);
			state.galleryIndex = -1;
			state.galleryScroll = 0;
			return new GalleryRemovalResult(removed, false);
		}
		int nextIndex = Math.min(resolvedIndex, state.galleryItems.size() - 1);
		int visibleRows = galleryVisibleRowsPreview(layout);
		int totalRows = galleryTotalRowsPreview(state.galleryItems.size(), layout);
		state.galleryScroll = clampInt(state.galleryScroll, 0, Math.max(0, totalRows - visibleRows));
		return new GalleryRemovalResult(removed, selectGalleryItemLocked(state, nextIndex, layout));
	}

	static String galleryItemTitle(String url, MonitorMediaApp.LoadedMedia media, int fallbackIndex) {
		String normalized = url == null ? "" : url.trim();
		int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
		String tail = slash >= 0 && slash + 1 < normalized.length() ? normalized.substring(slash + 1) : normalized;
		int query = tail.indexOf('?');
		if (query >= 0) {
			tail = tail.substring(0, query);
		}
		int hash = tail.indexOf('#');
		if (hash >= 0) {
			tail = tail.substring(0, hash);
		}
		if (tail == null || tail.isBlank()) {
			if (MonitorMediaApp.looksLikeDirectVideoUrl(url)) {
				return "VIDEO " + fallbackIndex;
			}
			if (MonitorMediaApp.looksLikeDirectAudioUrl(url) || MonitorYoutubeMusicCache.looksLikeSupportedUrl(url)) {
				return "TRACK " + fallbackIndex;
			}
			return media != null && media.animated() ? "GIF " + fallbackIndex : "IMAGE " + fallbackIndex;
		}
		return tail.length() > 48 ? tail.substring(0, 48) : tail;
	}

	static void ensureYoutubeQueueCurrentEntryLocked(MediaRuntimeState state) {
		if (state == null || !isYoutubeFamilyMode(state.mode) || state.sourceUrl == null || state.sourceUrl.isBlank()) {
			return;
		}
		if (state.youtubeQueueIndex >= 0 && state.youtubeQueueIndex < state.youtubeQueue.size()) {
			YoutubeQueueItem current = state.youtubeQueue.get(state.youtubeQueueIndex);
			if (current != null && Objects.equals(current.url(), state.sourceUrl)) {
				String nextTitle = state.mediaTitle != null && !state.mediaTitle.isBlank() ? state.mediaTitle : current.title();
				String nextSubtitle = state.mediaSubtitle != null && !state.mediaSubtitle.isBlank() ? state.mediaSubtitle : current.subtitle();
				long nextDurationMs = state.durationMs > 0L ? state.durationMs : current.durationMs();
				state.youtubeQueue.set(state.youtubeQueueIndex, new YoutubeQueueItem(nextTitle, nextSubtitle, nextDurationMs, state.sourceUrl));
				recalculateYoutubeQueueDurationLocked(state);
				syncYoutubeMusicShuffleStateLocked(state, true);
				return;
			}
		}
		for (int index = 0; index < state.youtubeQueue.size(); index++) {
			YoutubeQueueItem item = state.youtubeQueue.get(index);
			if (item != null && Objects.equals(item.url(), state.sourceUrl)) {
				String nextTitle = state.mediaTitle != null && !state.mediaTitle.isBlank() ? state.mediaTitle : item.title();
				String nextSubtitle = state.mediaSubtitle != null && !state.mediaSubtitle.isBlank() ? state.mediaSubtitle : item.subtitle();
				long nextDurationMs = state.durationMs > 0L ? state.durationMs : item.durationMs();
				state.youtubeQueue.set(index, new YoutubeQueueItem(nextTitle, nextSubtitle, nextDurationMs, state.sourceUrl));
				recalculateYoutubeQueueDurationLocked(state);
				state.youtubeQueueIndex = index;
				syncYoutubeMusicShuffleStateLocked(state, true);
				return;
			}
		}
		String title = state.mediaTitle != null && !state.mediaTitle.isBlank() ? state.mediaTitle : "YouTube";
		String subtitle = state.mediaSubtitle != null ? state.mediaSubtitle : "";
		state.youtubeQueue.add(new YoutubeQueueItem(title, subtitle, Math.max(0L, state.durationMs), state.sourceUrl));
		recalculateYoutubeQueueDurationLocked(state);
		state.youtubeQueueIndex = state.youtubeQueue.size() - 1;
		syncYoutubeMusicShuffleStateLocked(state, true);
	}

	static int normalizeYoutubeQueueIndexLocked(MediaRuntimeState state, int requestedIndex) {
		if (state == null || state.youtubeQueue.isEmpty()) {
			return -1;
		}
		return Math.floorMod(requestedIndex, state.youtubeQueue.size());
	}

	static boolean hasDisplayableMediaLocked(MediaRuntimeState state) {
		if (state == null) {
			return false;
		}
		if (isStreamPlaybackLocked(state)) {
			return state.sourceUrl != null || state.streamFrame != null;
		}
		if (isLibraryAppMode(state.mode)) {
			return state.loadedMedia != null || !state.galleryItems.isEmpty();
		}
		return state.loadedMedia != null;
	}

	static boolean shouldAppendYoutubeRequestLocked(MediaRuntimeState state) {
		if (state == null) {
			return false;
		}
		return MonitorMediaSessionPolicy.shouldAppendYoutubeRequest(
				isYoutubeFamilyMode(state.mode),
				hasDisplayableMediaLocked(state),
				!state.youtubeQueue.isEmpty(),
				state.loading,
				state.sourceUrl != null && !state.sourceUrl.isBlank(),
				state.relaySessionId != null,
				state.streamFrame != null,
				state.durationMs
		);
	}

	static boolean playbackControlsVisibleLocked(MediaRuntimeState state) {
		if (state == null) {
			return false;
		}
		if (isStreamPlaybackLocked(state)) {
			return state.loading
					|| state.sourceUrl != null
					|| state.relaySessionId != null
					|| (state.streamKind == PlaybackStreamKind.YOUTUBE && state.mode == ScreenViewMode.YOUTUBE && !state.youtubeQueue.isEmpty());
		}
		if (isLibraryAppMode(state.mode)) {
			return state.loadedMedia != null || !state.galleryItems.isEmpty();
		}
		return state.loadedMedia != null;
	}

	static boolean mediaControlUiVisibleLocked(MediaRuntimeState state) {
		if (state == null) {
			return false;
		}
		if (isLibraryAppMode(state.mode) && state.gallerySurfaceMode == GallerySurfaceMode.BROWSER) {
			return false;
		}
		if (isStreamPlaybackLocked(state)) {
			return hasDisplayableMediaLocked(state) || playbackControlsVisibleLocked(state);
		}
		return state.loadedMedia != null
				|| state.loading
				|| (state.sourceUrl != null && !state.sourceUrl.isBlank());
	}

	static boolean playerBackgroundMenuButtonVisibleLocked(MediaRuntimeState state) {
		if (state == null) {
			return false;
		}
		if (isLibraryAppMode(state.mode) && state.gallerySurfaceMode == GallerySurfaceMode.BROWSER) {
			return false;
		}
		if (isYoutubeFamilyMode(state.mode) && state.youtubeQueueOpen) {
			return false;
		}
		if (isYoutubeMenuSurfaceLocked(state.mode, state)) {
			return false;
		}
		return state.overlayMode == MediaOverlayMode.CONTROLS
				|| state.loading
				|| state.waitingForLink
				|| (isYoutubeFamilyMode(state.mode) && !hasDisplayableMediaLocked(state));
	}

	static MediaRepeatMode resolvedRepeatModeLocked(MediaRuntimeState state) {
		return state != null && state.repeatMode != null ? state.repeatMode : MediaRepeatMode.OFF;
	}

	static MediaRepeatMode resolvedRepeatMode(MediaVisualSnapshot state) {
		return state != null && state.repeatMode() != null ? state.repeatMode() : MediaRepeatMode.OFF;
	}

	static MediaRepeatMode resolvedRepeatMode(MediaOverlayWindowSnapshot window) {
		return window != null && window.repeatMode() != null ? window.repeatMode() : MediaRepeatMode.OFF;
	}

	static boolean repeatButtonVisibleLocked(MediaRuntimeState state) {
		if (state == null || isCurrentLiveCameraLocked(state)) {
			return false;
		}
		if (isYoutubeFamilyMode(state.mode)) {
			return state.sourceUrl != null && !state.sourceUrl.isBlank();
		}
		return false;
	}

	static boolean repeatButtonVisible(MediaVisualSnapshot state) {
		if (state == null) {
			return false;
		}
		if (isYoutubeFamilyMode(state.mode())) {
			return state.hasMedia() || (state.mediaTitle() != null && !state.mediaTitle().isBlank());
		}
		return false;
	}

	static boolean gallerySlideshowEligibleLocked(MediaRuntimeState state) {
		if (state == null
				|| state.mode != ScreenViewMode.GALLERY
				|| state.gallerySurfaceMode != GallerySurfaceMode.PLAYER) {
			return false;
		}
		GalleryItem current = currentGalleryItemLocked(state);
		return current != null
				&& effectiveGalleryItemKind(current) == GalleryItemKind.MEDIA
				&& state.loadedMedia != null
				&& current.url() != null
				&& !current.url().isBlank();
	}

	static boolean gallerySlideshowSettingsButtonVisibleLocked(MediaRuntimeState state) {
		return state != null
				&& state.mode == ScreenViewMode.GALLERY
				&& state.gallerySurfaceMode == GallerySurfaceMode.PLAYER
				&& state.gallerySlideshowEnabled;
	}

	static boolean gallerySlideshowSettingsButtonVisible(MediaVisualSnapshot state) {
		return state != null
				&& state.mode() == ScreenViewMode.GALLERY
				&& !state.galleryBrowser()
				&& state.gallerySlideshowEnabled();
	}

	static int minGallerySlideshowDurationSeconds() {
		return 3;
	}

	static int maxGallerySlideshowDurationSeconds() {
		return 120;
	}

	static int sanitizedGallerySlideshowDurationSeconds(int seconds) {
		return clampInt(seconds, minGallerySlideshowDurationSeconds(), maxGallerySlideshowDurationSeconds());
	}

	static int resolvedGallerySlideshowDurationSecondsLocked(MediaRuntimeState state) {
		return sanitizedGallerySlideshowDurationSeconds(state != null ? state.gallerySlideshowDurationSeconds : 25);
	}

	static int gallerySlideshowDurationSecondsForPoint(UiLayout layout, UiPoint point) {
		if (layout == null || point == null) {
			return 25;
		}
		UiRect trackRect = gallerySlideshowDurationTrackRect(layout);
		int minSeconds = minGallerySlideshowDurationSeconds();
		int maxSeconds = maxGallerySlideshowDurationSeconds();
		int startX = trackRect.x();
		int endX = trackRect.right();
		double fraction = endX > startX
				? clampDouble((point.x() - startX) / (double) (endX - startX), 0.0D, 1.0D)
				: 0.0D;
		return sanitizedGallerySlideshowDurationSeconds((int) Math.round(minSeconds + fraction * (maxSeconds - minSeconds)));
	}

	static String formatSlideshowSeconds(int seconds) {
		return sanitizedGallerySlideshowDurationSeconds(seconds) + " сек";
	}

	static boolean gallerySlideshowPlaybackActiveLocked(MediaRuntimeState state) {
		return gallerySlideshowEligibleLocked(state)
				&& state.gallerySlideshowEnabled
				&& !state.waitingForLink
				&& !state.loading
				&& !state.userPaused
				&& !state.gallerySlideshowSettingsOpen
				&& !state.galleryFileMenuOpen
				&& !state.galleryDeleteConfirmOpen
				&& !state.playerBackgroundMenuOpen;
	}

	static long nextGallerySlideshowDeadlineMillisLocked(MediaRuntimeState state, long nowMillis) {
		if (!gallerySlideshowPlaybackActiveLocked(state)) {
			return 0L;
		}
		long currentDeadline = state.gallerySlideshowAdvanceAtMillis;
		if (currentDeadline > nowMillis) {
			return currentDeadline;
		}
		return nowMillis + TimeUnit.SECONDS.toMillis(resolvedGallerySlideshowDurationSecondsLocked(state));
	}

	static void resetGallerySlideshowDeadlineLocked(MediaRuntimeState state) {
		if (state == null) {
			return;
		}
		state.gallerySlideshowAdvanceAtMillis = 0L;
	}

	static Integer nextGallerySlideshowIndexLocked(MediaRuntimeState state) {
		return adjacentGalleryPlaybackIndexLocked(state, 1);
	}

	static Integer adjacentGalleryPlaybackIndexLocked(MediaRuntimeState state, int delta) {
		if (state == null || state.galleryItems.isEmpty()) {
			return null;
		}
		List<Integer> queueIndexes = galleryPlaybackQueueIndexesLocked(state);
		if (queueIndexes.isEmpty()) {
			return null;
		}
		int currentIndex = state.galleryIndex >= 0
				? normalizeGalleryIndexLocked(state, state.galleryIndex)
				: resolveGalleryItemIndex(state, state.sourceUrl, -1);
		int queuePosition = queueIndexes.indexOf(currentIndex);
		if (queuePosition < 0) {
			return delta < 0 ? queueIndexes.get(queueIndexes.size() - 1) : queueIndexes.get(0);
		}
		return queueIndexes.get(Math.floorMod(queuePosition + delta, queueIndexes.size()));
	}

	static List<Integer> galleryPlaybackQueueIndexesLocked(MediaRuntimeState state) {
		if (state == null || state.galleryItems.isEmpty()) {
			return List.of();
		}
		if (state.gallerySlideshowEnabled && !state.galleryBulkSelectedKeys.isEmpty()) {
			return selectedGallerySlideshowIndexesLocked(state);
		}
		List<Integer> indexes = new ArrayList<>();
		for (int index = 0; index < state.galleryItems.size(); index++) {
			GalleryItem item = state.galleryItems.get(index);
			if (item != null && effectiveGalleryItemKind(item) != GalleryItemKind.LIVE_CAMERA) {
				indexes.add(index);
			}
		}
		return indexes.isEmpty() ? List.of() : List.copyOf(indexes);
	}

	static boolean isYoutubeHomePromptLocked(MediaRuntimeState state) {
		if (state == null || !isYoutubeFamilyMode(state.mode)) {
			return false;
		}
		return !state.loading
				&& !hasDisplayableMediaLocked(state)
				&& (state.sourceUrl == null || state.sourceUrl.isBlank())
				&& state.relaySessionId == null;
	}

	static boolean isYoutubeMenuSurfaceLocked(ScreenViewMode viewMode, MediaRuntimeState state) {
		return state != null
				&& isYoutubeFamilyMode(viewMode)
				&& !isGalleryBackedYoutubeLocked(state)
				&& !hasDisplayableMediaLocked(state)
				&& state.streamFrame == null
				&& state.loadedMedia == null;
	}

	static boolean resolvedActionVisible(MediaRuntimeState state) {
		if (state == null) {
			return false;
		}
		if (isCurrentLiveCameraLocked(state)) {
			return false;
		}
		if (isGalleryBackedYoutubeLocked(state)) {
			return true;
		}
		if (state.mode == ScreenViewMode.YOUTUBE || state.mode == ScreenViewMode.YOUTUBE_MUSIC) {
			return state.sourceUrl != null && !state.sourceUrl.isBlank();
		}
		return state.mode == ScreenViewMode.GALLERY && state.gallerySurfaceMode == GallerySurfaceMode.PLAYER;
	}

	static MediaActionGlyph resolvedActionGlyph(MediaRuntimeState state) {
		if (state == null) {
			return MediaActionGlyph.DOWNLOAD;
		}
		if (isGalleryBackedYoutubeLocked(state)) {
			return MediaActionGlyph.TRASH;
		}
		if (state.mode == ScreenViewMode.GALLERY && currentGalleryItemSavedLocked(state)) {
			return MediaActionGlyph.TRASH;
		}
		return MediaActionGlyph.DOWNLOAD;
	}

	static boolean currentDroneControlActionVisibleLocked(MediaRuntimeState state) {
		if (state == null
				|| state.mode != ScreenViewMode.SBER_DRONES
				|| state.gallerySurfaceMode != GallerySurfaceMode.PLAYER
				|| state.streamKind != PlaybackStreamKind.LIVE_CAMERA
				|| state.sourceUrl == null
				|| state.sourceUrl.isBlank()) {
			return false;
		}
		LiveCameraReference cameraRef = liveCameraGalleryReference(state.sourceUrl, null);
		return cameraRef != null
				&& cameraRef.sourceType() == LiveCameraSourceType.DRONE
				&& cameraRef.sourceUuid() != null
				&& !DroneSystem.hasActiveController(cameraRef.sourceUuid());
	}

	static MediaActionVisualState resolvedActionVisualState(MediaRuntimeState state) {
		if (state == null) {
			return MediaActionVisualState.IDLE;
		}
		if (isGalleryBackedYoutubeLocked(state)) {
			return MediaActionVisualState.IDLE;
		}
		String currentUrl = state.sourceUrl;
		if (state.downloadInProgress && currentUrl != null && Objects.equals(currentUrl, state.downloadTargetUrl)) {
			return MediaActionVisualState.DOWNLOADING;
		}
		if (currentUrl != null
				&& state.downloadCompletedUrl != null
				&& Objects.equals(currentUrl, state.downloadCompletedUrl)
				&& System.currentTimeMillis() < state.downloadCompletedUntilMillis) {
			return MediaActionVisualState.COMPLETE;
		}
		if ((state.mode == ScreenViewMode.YOUTUBE || state.mode == ScreenViewMode.YOUTUBE_MUSIC)
				&& currentUrl != null
				&& hasCompleteGalleryItemForUrlLocked(state, currentUrl)) {
			return MediaActionVisualState.COMPLETE;
		}
		return MediaActionVisualState.IDLE;
	}

	static boolean hasGalleryItemForUrlLocked(MediaRuntimeState state, String url) {
		return resolveGalleryItemIndex(state, url, -1) >= 0;
	}

	static boolean hasCompleteGalleryItemForUrlLocked(MediaRuntimeState state, String url) {
		int index = resolveGalleryItemIndex(state, url, -1);
		if (index < 0 || state == null || index >= state.galleryItems.size()) {
			return false;
		}
		GalleryItem item = state.galleryItems.get(index);
		if (item == null) {
			return false;
		}
		if (effectiveGalleryItemKind(item) == GalleryItemKind.YOUTUBE) {
			return false;
		}
		if (effectiveGalleryItemKind(item) == GalleryItemKind.AUDIO
				&& MonitorYoutubeMusicCache.looksLikeSupportedUrl(item.url())
				&& (item.localMediaKey() == null || item.localMediaKey().isBlank())) {
			return false;
		}
		return true;
	}

	static boolean isCurrentLiveCameraLocked(MediaRuntimeState state) {
		GalleryItem current = currentGalleryItemLocked(state);
		return current != null && effectiveGalleryItemKind(current) == GalleryItemKind.LIVE_CAMERA;
	}

	static boolean isGalleryBackedYoutubeLocked(MediaRuntimeState state) {
		return state != null
				&& state.mode == ScreenViewMode.GALLERY
				&& state.gallerySurfaceMode == GallerySurfaceMode.PLAYER
				&& state.sourceUrl != null
				&& !state.sourceUrl.isBlank()
				&& state.streamKind == PlaybackStreamKind.YOUTUBE
				&& currentGalleryItemLocked(state) != null
				&& effectiveGalleryItemKind(currentGalleryItemLocked(state)) == GalleryItemKind.YOUTUBE
				&& hasGalleryItemForUrlLocked(state, state.sourceUrl);
	}

	static boolean isGalleryBackedDirectVideoLocked(MediaRuntimeState state) {
		return state != null
				&& state.mode == ScreenViewMode.GALLERY
				&& state.gallerySurfaceMode == GallerySurfaceMode.PLAYER
				&& state.sourceUrl != null
				&& !state.sourceUrl.isBlank()
				&& state.streamKind == PlaybackStreamKind.DIRECT_VIDEO
				&& currentGalleryItemLocked(state) != null
				&& effectiveGalleryItemKind(currentGalleryItemLocked(state)) == GalleryItemKind.VIDEO
				&& hasGalleryItemForUrlLocked(state, state.sourceUrl);
	}

	static boolean isGalleryBackedAudioLocked(MediaRuntimeState state) {
		return state != null
				&& state.mode == ScreenViewMode.GALLERY
				&& state.gallerySurfaceMode == GallerySurfaceMode.PLAYER
				&& state.sourceUrl != null
				&& !state.sourceUrl.isBlank()
				&& state.streamKind == PlaybackStreamKind.DIRECT_VIDEO
				&& currentGalleryItemLocked(state) != null
				&& effectiveGalleryItemKind(currentGalleryItemLocked(state)) == GalleryItemKind.AUDIO
				&& hasGalleryItemForUrlLocked(state, state.sourceUrl);
	}

	static boolean usesMusicPlayerLayoutLocked(MediaRuntimeState state) {
		if (state == null) {
			return false;
		}
		if (state.mode == ScreenViewMode.YOUTUBE_MUSIC) {
			return true;
		}
		if (state.mode != ScreenViewMode.GALLERY || state.gallerySurfaceMode != GallerySurfaceMode.PLAYER || state.streamKind != PlaybackStreamKind.DIRECT_VIDEO) {
			return false;
		}
		GalleryItem current = currentGalleryItemLocked(state);
		if (current != null) {
			return effectiveGalleryItemKind(current) == GalleryItemKind.AUDIO;
		}
		String url = state.sourceUrl;
		return url != null
				&& !url.isBlank()
				&& (MonitorYoutubeMusicCache.looksLikeSupportedUrl(url) || MonitorMediaApp.looksLikeDirectAudioUrl(url))
				&& !MonitorMediaApp.looksLikeDirectVideoUrl(url);
	}

	static boolean usesMusicPlayerLayout(MediaVisualSnapshot state) {
		return state != null && state.musicPlayerLayout();
	}

	static ScreenViewMode mediaChromeMode(MediaRuntimeState state) {
		return usesMusicPlayerLayoutLocked(state) ? ScreenViewMode.YOUTUBE_MUSIC : state != null ? state.mode : ScreenViewMode.HOME;
	}

	static ScreenViewMode mediaChromeMode(MediaVisualSnapshot state) {
		return usesMusicPlayerLayout(state) ? ScreenViewMode.YOUTUBE_MUSIC : state != null ? state.mode() : ScreenViewMode.HOME;
	}

	static boolean isStreamPlaybackLocked(MediaRuntimeState state) {
		return state != null && state.streamKind != PlaybackStreamKind.NONE;
	}

	static boolean isYoutubePlaybackLocked(MediaRuntimeState state) {
		return isStreamPlaybackLocked(state) && state.streamKind == PlaybackStreamKind.YOUTUBE;
	}

	static boolean isDirectVideoPlaybackLocked(MediaRuntimeState state) {
		return isStreamPlaybackLocked(state) && state.streamKind == PlaybackStreamKind.DIRECT_VIDEO;
	}

	static boolean isDirectAudioPlaybackLocked(MediaRuntimeState state) {
		return isDirectVideoPlaybackLocked(state) && usesMusicPlayerLayoutLocked(state);
	}

	static boolean hasActiveStreamPlaybackLocked(MediaRuntimeState state) {
		if (!isStreamPlaybackLocked(state)) {
			return false;
		}
		return isGalleryBackedYoutubeLocked(state)
				|| isGalleryBackedDirectVideoLocked(state)
				|| state.loading
				|| (state.sourceUrl != null && !state.sourceUrl.isBlank())
				|| (state.relaySessionId != null && !state.relaySessionId.isBlank())
				|| (state.streamKind == PlaybackStreamKind.YOUTUBE && !state.youtubeQueue.isEmpty());
	}

	static boolean canSeekTimelineLocked(MediaRuntimeState state) {
		if (state == null) {
			return false;
		}
		if (isStreamPlaybackLocked(state)) {
			return state.durationMs > 0L && !state.liveStream;
		}
		return state.loadedMedia != null && state.loadedMedia.frameCount() > 1;
	}

	static boolean canTogglePlaybackLocked(MediaRuntimeState state) {
		if (state == null) {
			return false;
		}
		if (isStreamPlaybackLocked(state)) {
			if (state.streamKind == PlaybackStreamKind.LIVE_CAMERA) {
				return false;
			}
			return state.relaySessionId != null || state.loading;
		}
		return state.loadedMedia != null && state.loadedMedia.animated();
	}

	static String resolvedTimelineLabelLocked(MediaRuntimeState state) {
		if (state == null) {
			return "";
		}
		if (isStreamPlaybackLocked(state)) {
			return state.liveStream ? "LIVE" : formatPlaybackTime(state.positionMs) + " / " + formatPlaybackTime(state.durationMs);
		}
		if (state.loadedMedia == null || state.loadedMedia.frameCount() <= 0) {
			return "";
		}
		int frameIndex = Math.floorMod(state.frameIndex, Math.max(1, state.loadedMedia.frameCount()));
		return (frameIndex + 1) + "/" + Math.max(1, state.loadedMedia.frameCount());
	}

	static String resolvedTimelineLabel(MediaVisualSnapshot state, UiLayout layout) {
		if (state == null || layout == null || !state.timelineVisible()) {
			return "";
		}
		return switch (timelineCounterDetailLevel(layout)) {
			case NONE -> "";
			case COMPACT -> {
				if (state.streamPlayback()) {
					yield state.frameCount() <= 0 ? state.timelineLabel() : formatPlaybackTime(state.frameIndex());
				}
				yield state.timelineLabel();
			}
			case FULL -> state.timelineLabel();
		};
	}

	static String timelineLeadingLabel(MediaVisualSnapshot state) {
		if (state == null || state.timelineLabel() == null || state.timelineLabel().isBlank()) {
			return "";
		}
		String label = state.timelineLabel();
		int wideDivider = label.indexOf(" / ");
		if (wideDivider >= 0) {
			return label.substring(0, wideDivider).trim();
		}
		if (state.streamPlayback()) {
			return label;
		}
		int slash = label.indexOf('/');
		if (slash > 0) {
			return label.substring(0, slash).trim();
		}
		return label;
	}

	static String timelineTrailingLabel(MediaVisualSnapshot state) {
		if (state == null || state.timelineLabel() == null || state.timelineLabel().isBlank()) {
			return "";
		}
		String label = state.timelineLabel();
		int wideDivider = label.indexOf(" / ");
		if (wideDivider >= 0 && wideDivider + 3 <= label.length()) {
			return label.substring(wideDivider + 3).trim();
		}
		if (state.streamPlayback()) {
			return "";
		}
		int slash = label.indexOf('/');
		if (slash >= 0 && slash + 1 <= label.length()) {
			return label.substring(slash + 1).trim();
		}
		return "";
	}

	static TimelineCounterDetailLevel timelineCounterDetailLevel(UiLayout layout) {
		int minSpan = smallestScreenTileSpan(layout);
		if (minSpan <= 1) {
			return TimelineCounterDetailLevel.NONE;
		}
		if (minSpan == 2) {
			return TimelineCounterDetailLevel.COMPACT;
		}
		return TimelineCounterDetailLevel.FULL;
	}

	static float youtubeTimelineFraction(MediaRuntimeState state) {
		if (state == null || state.durationMs <= 0L) {
			return 0.0F;
		}
		return (float) clampDouble((double) state.positionMs / (double) state.durationMs, 0.0D, 1.0D);
	}

	static long youtubePositionForFraction(MediaRuntimeState state, float fraction) {
		if (state == null || state.durationMs <= 0L) {
			return 0L;
		}
		return clampLong(Math.round(clampDouble(fraction, 0.0D, 1.0D) * state.durationMs), 0L, state.durationMs);
	}

	static float youtubeBufferedFraction(MediaRuntimeState state, long positionMs) {
		if (state == null || state.durationMs <= 0L || positionMs <= 0L) {
			return 0.0F;
		}
		return (float) clampDouble((double) positionMs / (double) state.durationMs, 0.0D, 1.0D);
	}

	static String relaySessionId(ScreenRuntimeKey key) {
		if (key == null) {
			return "lostglade-unknown";
		}
		String dimension = key.dimension().identifier().toString().replace(':', '_').replace('/', '_');
		return dimension + "_" + key.pos().getX() + "_" + key.pos().getY() + "_" + key.pos().getZ() + "_" + key.facing().getName();
	}

	static void scheduleYoutubeRefresh(MinecraftServer server, ScreenRuntimeKey key, long delayMillis) {
		if (server == null || key == null) {
			return;
		}
		MediaRuntimeState state = MEDIA_STATES.get(key);
		if (state == null) {
			return;
		}
		ensureExecutors();
		synchronized (state) {
			if (!isStreamPlaybackLocked(state) || state.relaySessionId == null || state.waitingForLink) {
				return;
			}
			cancelPlaybackLocked(state);
			state.playbackFuture = mediaScheduler.schedule(() -> server.execute(() -> refreshYoutubeSnapshot(server, key)), Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
		}
	}

	static void refreshConnectedSpeakersNow(MinecraftServer server, ScreenRuntimeKey key) {
		refreshConnectedSpeakersNow(server, key, true);
	}

	static void refreshConnectedSpeakersNow(MinecraftServer server, ScreenRuntimeKey key, boolean resolveMissingComponent) {
		if (server == null || key == null) {
			return;
		}
		ServerLevel level = server.getLevel(key.dimension());
		if (level == null) {
			return;
		}
		ScreenComponent component = resolveMissingComponent
				? resolveScreenComponent(server, key)
				: cachedScreenComponent(server, key);
		if (component == null) {
			return;
		}
		refreshConnectedSpeakersNow(server, component);
	}

	static void refreshConnectedSpeakersNow(MinecraftServer server, ScreenComponent component) {
		if (server == null || component == null) {
			return;
		}
		ServerLevel level = server.getLevel(component.runtimeKey().dimension());
		if (level == null) {
			return;
		}
		LinkedHashSet<BlockPos> speakerPositions = new LinkedHashSet<>();
		for (ItemFrame frame : component.frameCoords().keySet()) {
			BlockPos framePos = frame.blockPosition();
			BlockPos supportPos = framePos.relative(frame.getDirection().getOpposite());
			speakerPositions.addAll(SpeakerSystem.findConnectedPoweredSpeakerPositions(level, framePos));
			speakerPositions.addAll(SpeakerSystem.findConnectedPoweredSpeakerPositions(level, supportPos));
		}
		BluetoothLinkSystem.Endpoint screenEndpoint = bluetoothScreenEndpoint(level, component);
		for (BluetoothLinkSystem.Endpoint linked : BluetoothLinkSystem.linkedEndpoints(screenEndpoint)) {
			if (linked.type() != BluetoothLinkSystem.EndpointType.SPEAKER) {
				continue;
			}
			if (Objects.equals(linked.dimension(), level.dimension())) {
				speakerPositions.add(linked.pos().immutable());
				continue;
			}
			ServerLevel linkedLevel = server.getLevel(linked.dimension());
			if (linkedLevel != null) {
				SpeakerSystem.onSpeakerStateChanged(linkedLevel, linked.pos());
			}
		}
		for (BlockPos speakerPos : speakerPositions) {
			SpeakerSystem.onSpeakerStateChanged(level, speakerPos);
		}
	}

	static boolean acceptLateResultForCurrentView(MinecraftServer server, ScreenRuntimeKey key, ScreenViewMode expectedViewMode) {
		if (server == null || key == null || expectedViewMode == null) {
			return false;
		}
		ScreenComponent component = resolveScreenComponent(server, key);
		return component != null && component.powered() && component.viewMode() == expectedViewMode;
	}

	static boolean acceptLateResultWhileGalleryStillActive(MinecraftServer server, ScreenRuntimeKey key) {
		return acceptLateResultForCurrentView(server, key, ScreenViewMode.GALLERY);
	}

	static long clampLong(long value, long min, long max) {
		return Math.max(min, Math.min(max, value));
	}

	static String formatPlaybackTime(long millis) {
		long totalSeconds = Math.max(0L, millis / 1000L);
		long hours = totalSeconds / 3600L;
		long minutes = (totalSeconds % 3600L) / 60L;
		long seconds = totalSeconds % 60L;
		if (hours > 0L) {
			return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
		}
		return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
	}

	static boolean isPlaybackPausedLocked(MediaRuntimeState state) {
		return state != null && state.userPaused;
	}

	static boolean isYoutubeFamilyMode(ScreenViewMode mode) {
		return mode == ScreenViewMode.YOUTUBE || mode == ScreenViewMode.YOUTUBE_MUSIC;
	}

	static boolean isYoutubeMusicMode(ScreenViewMode mode) {
		return mode == ScreenViewMode.YOUTUBE_MUSIC;
	}
}
