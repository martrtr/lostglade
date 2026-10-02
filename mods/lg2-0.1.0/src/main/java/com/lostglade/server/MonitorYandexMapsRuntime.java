package com.lostglade.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lostglade.server.map.TextureAssetManager;
import com.lostglade.server.monitor.MonitorApp;
import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static com.lostglade.server.MonitorScreenSystem.*;

public final class MonitorYandexMapsRuntime {
	private static final double DEFAULT_BLOCKS_PER_PIXEL = 2.0D;
	private static final double MIN_BLOCKS_PER_PIXEL = 1.0D / 256.0D;
	private static final double MAX_BLOCKS_PER_PIXEL = 512.0D;
	private static final double BUTTON_ZOOM_FACTOR = 2.0D;
	private static final double WHEEL_ZOOM_FACTOR = 1.25D;
	private static final long PAN_ANIMATION_MS = 320L;
	private static final long PAN_FRAME_DELAY_MS = 40L;
	private static final long TILE_READY_RENDER_DEBOUNCE_MS = 35L;
	private static final ResourceKey<Level> MAP_DIMENSION = Level.OVERWORLD;
	// Flat monitor/Yandex canvas background. This belongs exclusively to the UI layer;
	// cached/rendered tiles stay transparent outside real map imagery.
	private static final int MAP_CANVAS_BACKGROUND_RGB = 0x282828;
	private static final int MAP_MARKER_ICON_SIZE = 32;
	private static final int MAP_MARKER_SCREEN_ICON_SIZE = 16;
	private static final int MARKER_ICON_ALPHA_BOUNDS_THRESHOLD = 1;
	private static final int STATE_CLEANUP_INTERVAL_TICKS = 40;
	private static final Map<ScreenRuntimeKey, YandexMapState> STATES = new ConcurrentHashMap<>();
	private static final Map<UUID, BufferedImage> ITEM_MARKER_ICON_CACHE = new ConcurrentHashMap<>();
	private static final Set<UUID> PENDING_MARKER_ICON_RENDERS = ConcurrentHashMap.newKeySet();
	private static final Set<MarkerHeadRequest> PENDING_MARKER_HEAD_REQUESTS = ConcurrentHashMap.newKeySet();
	private static final Map<String, BufferedImage> LEGACY_ITEM_MARKER_ICON_CACHE = new ConcurrentHashMap<>();
	private static final Map<UUID, PendingMarkerTitleRequest> PENDING_MARKER_TITLES = new ConcurrentHashMap<>();
	private static final TextureAssetManager MAP_ASSETS = TextureAssetManager.get();
	private static final Identifier DEFAULT_MARKER_ICON_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "map/decorations/red_banner");
	private static final BufferedImage EMPTY_MARKER_ICON = new BufferedImage(MAP_MARKER_ICON_SIZE, MAP_MARKER_ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
	private static volatile BufferedImage defaultMarkerIcon;
	// GPS is intentionally gated by the rocket event. The event state is
	// persisted by RocketLaunchEventSystem and restores this flag on boot.
	private static volatile boolean gpsEnabled = false;

	private MonitorYandexMapsRuntime() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			MonitorYandexMapsClientTileRenderer.tick(server);
			tick(server);
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("yandexmaps")
						.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.then(Commands.literal("gps")
								.then(Commands.literal("status").executes(context -> sendGpsStatus(context.getSource())))
						)
		));
	}

	static boolean isGpsEnabled() {
		return gpsEnabled;
	}

	static void setGpsEnabled(MinecraftServer server, boolean enabled) {
		gpsEnabled = enabled;
		if (server == null) {
			return;
		}
		for (Map.Entry<ScreenRuntimeKey, YandexMapState> entry : STATES.entrySet()) {
			YandexMapState state = entry.getValue();
			if (state != null) {
				synchronized (state) {
					state.version++;
				}
			}
			requestRuntimeRender(server, entry.getKey());
		}
	}

	private static int sendGpsStatus(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal("GPS Яндекс-карт: " + (gpsEnabled ? "включён" : "выключен")), false);
		return Command.SINGLE_SUCCESS;
	}

	static void clearRuntime() {
		STATES.clear();
		PENDING_MARKER_TITLES.clear();
		PlayerHeadRenderSystem.clearRuntime();
		MonitorYandexMapsClientTileRenderer.clear(null);
	}

	static void deactivateRuntime(ScreenRuntimeKey key) {
		if (key == null) {
			return;
		}
		STATES.remove(key);
		MonitorYandexMapsClientTileRenderer.deactivateView(key);
		clearPendingMarkerTitleRequests(key);
	}

	static boolean consumeMarkerTitleChatMessage(MinecraftServer server, PlayerChatMessage message, ServerPlayer sender) {
		if (server == null || message == null || sender == null) {
			return false;
		}
		PendingMarkerTitleRequest pending = PENDING_MARKER_TITLES.remove(sender.getUUID());
		if (pending == null) {
			return false;
		}
		UUID markerId = pending.markerId();
		ScreenRuntimeKey runtimeKey = pending.screenKey();
		YandexMapMarkerStore.YandexMapMarker marker = markerId == null ? null : YandexMapMarkerStore.marker(markerId);
		if (runtimeKey == null || marker == null) {
			sender.displayClientMessage(Component.empty(), true);
			return true;
		}
		String nextTitle = sanitizeMarkerTitle(message.signedContent(), sender);
		YandexMapMarkerStore.YandexMapMarker updated = YandexMapMarkerStore.updateTitle(server, markerId, nextTitle);
		if (updated == null) {
			sender.displayClientMessage(Component.empty(), true);
			return true;
		}
		YandexMapState state = STATES.get(runtimeKey);
		if (state != null) {
			synchronized (state) {
				if (Objects.equals(state.editorMarkerId, markerId)) {
					state.version++;
				}
			}
		}
		requestRuntimeRender(server, runtimeKey);
		sender.displayClientMessage(Component.empty(), true);
		return true;
	}

	private static void requestMarkerTitlePrompt(ServerPlayer player, ScreenRuntimeKey key, UUID markerId) {
		if (player == null || key == null || markerId == null) {
			return;
		}
		PENDING_MEDIA_LINKS.remove(player.getUUID());
		PENDING_GALLERY_RENAMES.remove(player.getUUID());
		PENDING_MARKER_TITLES.put(player.getUUID(), new PendingMarkerTitleRequest(key, markerId));
		player.displayClientMessage(Component.literal(markerTitlePromptMessage(player)), true);
	}

	private static void clearPendingMarkerTitleRequests(ScreenRuntimeKey key) {
		if (key == null || PENDING_MARKER_TITLES.isEmpty()) {
			return;
		}
		PENDING_MARKER_TITLES.entrySet().removeIf(entry -> Objects.equals(entry.getValue().screenKey(), key));
	}

	private static ServerLevel mapLevel(MinecraftServer server) {
		return server == null ? null : server.getLevel(MAP_DIMENSION);
	}

	private static String markerTitlePromptMessage(ServerPlayer player) {
		String locale = MonitorScreenMessages.locale(player);
		if (locale.startsWith("uk")) {
			return "Я.Карти: напиши назву мітки в чат";
		}
		if (locale.startsWith("ja")) {
			return "ヤンデックス地図: マーカー名をチャットに入力して";
		}
		if (locale.startsWith("ru")) {
			return "Я.Карты: напиши название метки в чат";
		}
		return "Yandex Maps: type the marker title in chat";
	}

	private static String defaultMarkerTitle(ServerPlayer player) {
		String locale = MonitorScreenMessages.locale(player);
		if (locale.startsWith("uk")) {
			return "Нова мітка";
		}
		if (locale.startsWith("ja")) {
			return "新しいマーカー";
		}
		if (locale.startsWith("ru")) {
			return "Новая метка";
		}
		return "New marker";
	}

	private static String sanitizeMarkerTitle(String rawTitle, ServerPlayer player) {
		if (rawTitle == null) {
			return defaultMarkerTitle(player);
		}
		String normalized = rawTitle.trim().replaceAll("\\s+", " ");
		if (normalized.isEmpty()) {
			return defaultMarkerTitle(player);
		}
		if (normalized.length() <= 64) {
			return normalized;
		}
		String truncated = normalized.substring(0, 64).trim();
		return truncated.isEmpty() ? defaultMarkerTitle(player) : truncated;
	}

	static YandexMapsVisualSnapshot captureSnapshot(MinecraftServer server, ScreenComponent component) {
		if (server == null || component == null) {
			return emptySnapshot();
		}
		// Snapshot capture runs on the server thread.  Publish the immutable cache
		// location here so the later UI compositor never has to touch server state.
		MonitorYandexMapsClientTileRenderer.configure(server);
		YandexMapState state = STATES.computeIfAbsent(component.runtimeKey(), ignored -> new YandexMapState());
		double centerX;
		double centerZ;
		double blocksPerPixel;
		long version;
		String status;
		long now = System.currentTimeMillis();
		synchronized (state) {
			initializeStateLocked(state, component);
			if (applyPanAnimationLocked(state, now)) {
				state.version++;
			}
			centerX = state.centerX;
			centerZ = state.centerZ;
			blocksPerPixel = state.blocksPerPixel;
			version = state.version;
			status = state.streamStatus;
		}
		ServerLevel level = mapLevel(server);
		if (level == null) {
			return new YandexMapsVisualSnapshot(
					version,
					fallbackMapFrame(component, "Мир недоступен"),
					"Мир недоступен",
					dimensionLabel(MAP_DIMENSION),
					centerX,
					centerZ,
					blocksPerPixel,
					false
			);
		}
		return new YandexMapsVisualSnapshot(
				version,
				null,
				status == null || status.isBlank() ? "Client camera tiles" : status,
				dimensionLabel(level.dimension()),
				centerX,
				centerZ,
				blocksPerPixel,
				true
		);
	}

	static boolean beginRender(ScreenRuntimeKey key, YandexMapsVisualSnapshot snapshot) {
		if (key == null || snapshot == null) {
			return true;
		}
		YandexMapState state = STATES.computeIfAbsent(key, ignored -> new YandexMapState());
		synchronized (state) {
			if (state.activeRenderJobs > 0) {
				state.rerenderRequested = true;
				return false;
			}
			state.activeRenderJobs = 1;
			state.rerenderRequested = false;
			return true;
		}
	}

	static boolean acceptRenderedSnapshot(ScreenRuntimeKey key, YandexMapsVisualSnapshot snapshot) {
		if (key == null || snapshot == null) {
			return true;
		}
		YandexMapState state = STATES.get(key);
		if (state == null) {
			return true;
		}
		synchronized (state) {
			if (snapshot.version() < state.lastAppliedRenderVersion) {
				return false;
			}
			state.lastAppliedRenderVersion = snapshot.version();
			return true;
		}
	}

	static boolean finishRender(ScreenRuntimeKey key, YandexMapsVisualSnapshot snapshot) {
		if (key == null || snapshot == null) {
			return false;
		}
		YandexMapState state = STATES.get(key);
		if (state == null) {
			return false;
		}
		synchronized (state) {
			state.activeRenderJobs = 0;
			boolean rerender = state.rerenderRequested || state.version != snapshot.version();
			state.rerenderRequested = false;
			return rerender;
		}
	}

	static void drawScreen(Graphics2D graphics, UiLayout layout, MonitorApp app, YandexMapsVisualSnapshot snapshot, MinecraftServer server, ScreenRuntimeKey runtimeKey) {
		if (graphics == null || layout == null) {
			return;
		}
		UiRect canvas = mediaCanvasRect(layout);
		graphics.setColor(new Color(MAP_CANVAS_BACKGROUND_RGB));
		graphics.fillRect(canvas.x(), canvas.y(), canvas.width(), canvas.height());
		YandexMapsVisualSnapshot effectiveSnapshot = snapshot;
		BufferedImage frame = snapshot != null ? snapshot.frame() : null;
		if (frame == null && snapshot != null && server != null && runtimeKey != null) {
			MonitorYandexMapsClientTileRenderer.Frame rendered = MonitorYandexMapsClientTileRenderer.render(
					server,
					MAP_DIMENSION,
					snapshot.centerX(),
					snapshot.centerZ(),
					Math.max(1, canvas.width()),
					Math.max(1, canvas.height()),
					snapshot.zoomBlocks(),
					() -> notifyTileReady(server, runtimeKey),
					runtimeKey
			);
			frame = rendered.image();
			effectiveSnapshot = new YandexMapsVisualSnapshot(
					snapshot.version(),
					frame,
					rendered.status(),
					snapshot.dimensionLabel(),
					snapshot.centerX(),
					snapshot.centerZ(),
					snapshot.zoomBlocks(),
					rendered.healthy()
			);
		}
		if (!isGpsEnabled()) {
			drawMapFrameNearest(graphics, gpsUnavailableFrameForCanvas(canvas), canvas);
			drawMapHeader(graphics, layout, app, effectiveSnapshot);
			return;
		}
		if (frame != null) {
			drawMapFrameNearest(graphics, frame, canvas);
		} else {
			String statusText = effectiveSnapshot != null && effectiveSnapshot.statusText() != null && !effectiveSnapshot.statusText().isBlank()
					? effectiveSnapshot.statusText()
					: "Клиентский рендер карты недоступен";
			drawCenteredText(graphics, statusText, canvas, new Color(230, 238, 244), Font.BOLD, Math.max(12, Math.min(canvas.width(), canvas.height()) / 12));
		}
		drawMarkers(graphics, layout, effectiveSnapshot, runtimeKey, server);
		drawMapHeader(graphics, layout, app, effectiveSnapshot);
		drawZoomControls(graphics, layout);
		drawCenterReticle(graphics, layout);
		drawMarkerEditor(graphics, layout, runtimeKey, server);
	}

	private static void drawMapFrameNearest(Graphics2D graphics, BufferedImage frame, UiRect canvas) {
		if (graphics == null || frame == null || canvas == null || canvas.width() <= 0 || canvas.height() <= 0) return;
		Graphics2D mapGraphics = (Graphics2D) graphics.create();
		try {
			mapGraphics.clipRect(canvas.x(), canvas.y(), canvas.width(), canvas.height());
			mapGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			if (frame.getWidth() == canvas.width() && frame.getHeight() == canvas.height()) {
				// Normal contract: map compositor already rendered in final monitor pixels.
				// A second resize destroys the integral 2x/4x close-zoom texel grid.
				mapGraphics.drawImage(frame, canvas.x(), canvas.y(), null);
			} else {
				// Fail visually safe for a stale/in-flight snapshot produced for another
				// monitor geometry. Never stretch it: center/crop 1:1 and let the next
				// render replace it with an exact-size frame.
				int x = canvas.x() + (canvas.width() - frame.getWidth()) / 2;
				int y = canvas.y() + (canvas.height() - frame.getHeight()) / 2;
				mapGraphics.drawImage(frame, x, y, null);
			}
		} finally {
			mapGraphics.dispose();
		}
	}

	static boolean handleTouch(ServerPlayer player, ServerLevel level, ScreenComponent component, UiLayout layout, UiPoint touchPoint) {
		if (level == null || component == null || layout == null || touchPoint == null) {
			return false;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return false;
		}
		if (yandexCloseRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			deactivateRuntime(component.runtimeKey());
			applyTransientComponentViewState(server, level, component, ScreenViewMode.HOME, component.launcherPage());
			return true;
		}
		if (!isGpsEnabled()) {
			return true;
		}
		YandexMapState state = STATES.computeIfAbsent(component.runtimeKey(), ignored -> new YandexMapState());
		if (handleMarkerEditorTouch(player, server, component, layout, touchPoint, state)) {
			return true;
		}
		if (yandexZoomInRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				initializeStateLocked(state, component);
				zoomLocked(state, 1.0D / BUTTON_ZOOM_FACTOR);
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (yandexZoomOutRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				initializeStateLocked(state, component);
				zoomLocked(state, BUTTON_ZOOM_FACTOR);
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (yandexGeoRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				initializeStateLocked(state, component);
				applyPanAnimationLocked(state, System.currentTimeMillis());
				BlockPos screenPos = component.runtimeKey().pos();
				beginPanLocked(state, screenPos.getX() + 0.5D, screenPos.getZ() + 0.5D, System.currentTimeMillis());
			}
			requestRuntimeRender(server, component.runtimeKey());
			schedulePanAnimationFrame(server, component.runtimeKey());
			return true;
		}
		if (yandexWorldCenterRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				initializeStateLocked(state, component);
				applyPanAnimationLocked(state, System.currentTimeMillis());
				beginPanLocked(state, 0.0D, 0.0D, System.currentTimeMillis());
			}
			requestRuntimeRender(server, component.runtimeKey());
			schedulePanAnimationFrame(server, component.runtimeKey());
			return true;
		}
		if (yandexAddMarkerRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			ServerLevel mapLevel = mapLevel(server);
			synchronized (state) {
				initializeStateLocked(state, component);
				state.editorMarkerId = createMarkerAtCenter(mapLevel, player, state);
				state.markerDeleteConfirmMarkerId = null;
				state.version++;
			}
			clearPendingMarkerTitleRequests(component.runtimeKey());
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		ProjectedMarker tappedMarker;
		synchronized (state) {
			initializeStateLocked(state, component);
			tappedMarker = markerAtTouch(
					MAP_DIMENSION,
					layout,
					state.centerX,
					state.centerZ,
					state.blocksPerPixel,
					state.editorMarkerId,
					touchPoint
			);
		}
		if (tappedMarker != null) {
			synchronized (state) {
				state.editorMarkerId = tappedMarker.markerId();
				state.markerDeleteConfirmMarkerId = null;
				state.version++;
			}
			clearPendingMarkerTitleRequests(component.runtimeKey());
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		UiRect canvas = mediaCanvasRect(layout);
		if (canvas.contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				initializeStateLocked(state, component);
				applyPanAnimationLocked(state, System.currentTimeMillis());
				double zoom = state.blocksPerPixel;
				double nextX = state.centerX + (touchPoint.x() - canvas.x() - canvas.width() / 2.0D) * zoom;
				double nextZ = state.centerZ + (touchPoint.y() - canvas.y() - canvas.height() / 2.0D) * zoom;
				beginPanLocked(state, nextX, nextZ, System.currentTimeMillis());
			}
			requestRuntimeRender(server, component.runtimeKey());
			schedulePanAnimationFrame(server, component.runtimeKey());
			return true;
		}
		return true;
	}

	static void tick(MinecraftServer server) {
		if (server == null || STATES.isEmpty()) {
			return;
		}
		refreshObservedMarkerHovers(server);
		if (Math.floorMod(server.getTickCount(), STATE_CLEANUP_INTERVAL_TICKS) != 0) {
			return;
		}
		for (ScreenRuntimeKey key : List.copyOf(STATES.keySet())) {
			ScreenComponent component = resolveScreenComponent(server, key);
			if (component == null || component.viewMode() != ScreenViewMode.YANDEX_MAPS || !component.powered()) {
				deactivateRuntime(key);
			}
		}
	}

	static boolean onPlayerHotbarScroll(ServerPlayer player, int previousSlot, int currentSlot) {
		if (player == null) {
			return false;
		}
		ScreenComponent component = findObservedScrollableComponent(player);
		if (component == null || component.viewMode() != ScreenViewMode.YANDEX_MAPS) {
			return false;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return false;
		}
		int delta = normalizeHotbarDelta(previousSlot, currentSlot);
		if (delta == 0) {
			return false;
		}
		YandexMapState state = STATES.computeIfAbsent(component.runtimeKey(), ignored -> new YandexMapState());
		synchronized (state) {
			initializeStateLocked(state, component);
			zoomLocked(state, Math.pow(WHEEL_ZOOM_FACTOR, -delta));
		}
		requestRuntimeRender(server, component.runtimeKey());
		return true;
	}

	private static void refreshObservedMarkerHovers(MinecraftServer server) {
		Map<ScreenRuntimeKey, Set<UUID>> nextObservedByScreen = new HashMap<>();
		Map<ScreenRuntimeKey, List<ProjectedMarker>> projectedByScreen = new HashMap<>();
		Map<ScreenRuntimeKey, MarkerProjectionState> projectionStateByScreen = new HashMap<>();
		for (ScreenRuntimeKey key : List.copyOf(STATES.keySet())) {
			ScreenComponent component = resolveScreenComponent(server, key);
			YandexMapState state = STATES.get(key);
			if (component == null || state == null || component.viewMode() != ScreenViewMode.YANDEX_MAPS || !component.powered()) {
				continue;
			}
			nextObservedByScreen.put(key, new HashSet<>());
		}
		if (nextObservedByScreen.isEmpty()) {
			return;
		}
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			ObservedYandexMapUiTarget target = findObservedYandexMapUiTarget(player);
			if (target == null || target.component() == null || target.touchPoint() == null) {
				continue;
			}
			ScreenRuntimeKey key = target.component().runtimeKey();
			Set<UUID> observed = nextObservedByScreen.get(key);
			YandexMapState state = STATES.get(key);
			if (observed == null || state == null) {
				continue;
			}
			MarkerProjectionState projectionState = projectionStateByScreen.computeIfAbsent(
					key,
					ignored -> captureProjectionState(state, target.component())
			);
			List<ProjectedMarker> projected = projectedByScreen.computeIfAbsent(
					key,
					ignored -> projectVisibleMarkers(
							MAP_DIMENSION,
							target.layout(),
							projectionState.centerX(),
							projectionState.centerZ(),
							projectionState.zoomBlocks()
					)
			);
			ProjectedMarker marker = markerAtTouch(projected, target.layout(), projectionState.editorMarkerId(), target.touchPoint());
			if (marker != null && marker.markerId() != null) {
				observed.add(marker.markerId());
			}
		}
		for (Map.Entry<ScreenRuntimeKey, Set<UUID>> entry : nextObservedByScreen.entrySet()) {
			ScreenRuntimeKey key = entry.getKey();
			YandexMapState state = STATES.get(key);
			if (state == null) {
				continue;
			}
			Set<UUID> observed = entry.getValue().isEmpty() ? Set.of() : Set.copyOf(entry.getValue());
			boolean changed;
			synchronized (state) {
				changed = !Objects.equals(state.observedMarkerIds, observed);
				if (changed) {
					state.observedMarkerIds = observed;
				}
			}
			if (changed) {
				requestRuntimeRender(server, key);
			}
		}
	}

	private static MarkerProjectionState captureProjectionState(YandexMapState state, ScreenComponent component) {
		synchronized (state) {
			initializeStateLocked(state, component);
			return new MarkerProjectionState(state.centerX, state.centerZ, state.blocksPerPixel, state.editorMarkerId);
		}
	}

	private static ObservedYandexMapUiTarget findObservedYandexMapUiTarget(ServerPlayer player) {
		ObservedScreenTouch target = findObservedScreenTouch(
				player,
				component -> component.viewMode() == ScreenViewMode.YANDEX_MAPS
		);
		if (target == null) {
			return null;
		}
		ScreenComponent component = target.component();
		return new ObservedYandexMapUiTarget(component, createUiLayout(component.width(), component.height()), target.touchPoint());
	}

	static void notifyTileReady(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		YandexMapState state = STATES.get(key);
		if (state == null) {
			requestRuntimeRender(server, key);
			return;
		}
		synchronized (state) {
			if (state.tileReadyRenderScheduled) {
				return;
			}
			state.tileReadyRenderScheduled = true;
		}
		scheduleRuntimeCallback(server, TILE_READY_RENDER_DEBOUNCE_MS, () -> {
			YandexMapState current = STATES.get(key);
			if (current != null) {
				synchronized (current) {
					current.tileReadyRenderScheduled = false;
				}
			}
			requestRuntimeRender(server, key);
		});
	}

	private static void drawMapHeader(Graphics2D graphics, UiLayout layout, MonitorApp app, YandexMapsVisualSnapshot snapshot) {
		UiRect canvas = mediaCanvasRect(layout);
		boolean ultra = ultraCompactScreenLayout(layout);
		int inset = ultra ? Math.max(2, layout.unit() / 3) : clampInt(layout.unit() / 2, 4, 10);
		int height = ultra ? clampInt(layout.unit() * 2 + 4, 12, 16) : clampInt(layout.unit() * 3, 30, 46);
		UiRect close = yandexCloseRect(layout);
		int headerX = close.right() + mediaHeaderControlGap(layout);
		UiRect header = new UiRect(
			headerX,
			canvas.y() + inset,
				Math.min(canvas.right() - headerX - inset, ultra ? clampInt(layout.unit() * 24, 88, 124) : clampInt(layout.unit() * 24, 168, 310)),
				height
		);
		fillRoundedRect(graphics, header, header.height(), new Color(255, 255, 255, 244));
		int iconInset = ultra ? Math.max(2, header.height() / 7) : 4;
		UiRect iconRect = new UiRect(header.x() + iconInset, header.y() + iconInset, header.height() - iconInset * 2, header.height() - iconInset * 2);
		drawAppIcon(graphics, app, iconRect, 0);
		String coords = snapshot != null
				? Math.round(snapshot.centerX()) + ", " + Math.round(snapshot.centerZ()) + "  |  " + formatZoom(snapshot.zoomBlocks())
				: "Карта загружается";
		if (ultra) {
			drawVerticalText(graphics, coords, new UiRect(iconRect.right() + 3, header.y(), Math.max(8, header.right() - iconRect.right() - 6), header.height()), new Color(58, 64, 68), Font.BOLD, clampInt(layout.unit() + 1, 6, 8));
		} else {
			drawVerticalText(graphics, "Яндекс Карты", new UiRect(iconRect.right() + 6, header.y() + 1, header.right() - iconRect.right() - 10, header.height() / 2), new Color(30, 34, 36), Font.BOLD, clampInt(layout.unit() - 1, 9, 13));
			drawVerticalText(graphics, coords, new UiRect(iconRect.right() + 6, header.y() + header.height() / 2 - 2, header.right() - iconRect.right() - 10, header.height() / 2), new Color(78, 86, 92), Font.PLAIN, clampInt(layout.unit() - 3, 7, 10));
		}
		fillRoundedRect(graphics, close, close.height(), new Color(255, 255, 255, 244));
		drawCloseGlyph(graphics, mediaChromeIconRect(close, layout), new Color(16, 18, 20, 244));
	}

	private static UiRect yandexCloseRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		boolean ultra = ultraCompactScreenLayout(layout);
		int inset = ultra ? Math.max(2, layout.unit() / 3) : clampInt(layout.unit() / 2, 4, 10);
		int height = ultra ? clampInt(layout.unit() * 2 + 4, 12, 16) : clampInt(layout.unit() * 3, 30, 46);
		int width = ultra ? height : height + clampInt(layout.unit(), 7, 14);
		return new UiRect(canvas.x() + inset, canvas.y() + inset, width, height);
	}

	private static void drawZoomControls(Graphics2D graphics, UiLayout layout) {
		drawMapIconButton(graphics, yandexZoomInRect(layout), PlayerUiIcon.ADD, layout);
		drawMapIconButton(graphics, yandexZoomOutRect(layout), PlayerUiIcon.MINUS, layout);
		drawMapIconButton(graphics, yandexGeoRect(layout), PlayerUiIcon.AIMING_2, layout);
		drawMapIconButton(graphics, yandexWorldCenterRect(layout), PlayerUiIcon.LOCATION, layout);
		drawMapIconButton(graphics, yandexAddMarkerRect(layout), PlayerUiIcon.DIRECTIONS_2_LINE, layout);
	}

	private static void drawMapIconButton(Graphics2D graphics, UiRect rect, PlayerUiIcon icon, UiLayout layout) {
		boolean ultra = ultraCompactScreenLayout(layout);
		fillRoundedRect(graphics, rect, ultra ? clampInt(layout.unit(), 5, 8) : clampInt(layout.unit(), 8, 14), new Color(250, 252, 248, 232));
		strokeRoundedRect(graphics, rect, ultra ? clampInt(layout.unit(), 5, 8) : clampInt(layout.unit(), 8, 14), ultra ? 0.85F : 1.0F, new Color(0, 0, 0, 42));
		int inset = ultra ? clampInt(rect.width() / 5, 2, 3) : clampInt(rect.width() / 5, 5, 9);
		drawPlayerUiIcon(graphics, rect.inset(inset), icon, new Color(20, 24, 26, 238));
	}

	private static void drawCenterReticle(Graphics2D graphics, UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		int x = canvas.x() + canvas.width() / 2;
		int y = canvas.y() + canvas.height() / 2;
		Stroke previous = graphics.getStroke();
		graphics.setStroke(new BasicStroke(1.2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.setColor(new Color(255, 65, 52, 208));
		graphics.drawLine(x - 7, y, x - 2, y);
		graphics.drawLine(x + 2, y, x + 7, y);
		graphics.drawLine(x, y - 7, x, y - 2);
		graphics.drawLine(x, y + 2, x, y + 7);
		graphics.setStroke(previous);
	}

	private static UiRect yandexZoomInRect(UiLayout layout) {
		UiRect minus = yandexZoomOutRect(layout);
		int gap = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 5) : Math.max(2, layout.unit() / 3);
		return new UiRect(minus.x(), minus.y() - gap - minus.height(), minus.width(), minus.height());
	}

	private static UiRect yandexZoomOutRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		boolean ultra = ultraCompactScreenLayout(layout);
		int size = ultra ? clampInt(layout.unit() * 2 + 4, 12, 16) : clampInt(layout.unit() * 3, 28, 42);
		int inset = ultra ? Math.max(2, layout.unit() / 3) : clampInt(layout.unit(), 8, 16);
		return new UiRect(canvas.right() - inset - size, canvas.bottom() - inset - size, size, size);
	}

	private static UiRect yandexGeoRect(UiLayout layout) {
		UiRect plus = yandexZoomInRect(layout);
		int gap = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 5) : Math.max(2, layout.unit() / 3);
		return new UiRect(plus.x(), plus.y() - gap - plus.height(), plus.width(), plus.height());
	}

	private static UiRect yandexWorldCenterRect(UiLayout layout) {
		UiRect current = yandexGeoRect(layout);
		int gap = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 5) : Math.max(2, layout.unit() / 3);
		return new UiRect(current.x(), current.y() - gap - current.height(), current.width(), current.height());
	}

	private static UiRect yandexAddMarkerRect(UiLayout layout) {
		UiRect current = yandexWorldCenterRect(layout);
		int gap = ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 5) : Math.max(2, layout.unit() / 3);
		return new UiRect(current.x(), current.y() - gap - current.height(), current.width(), current.height());
	}

	private static UUID createMarkerAtCenter(ServerLevel level, ServerPlayer player, YandexMapState state) {
		if (level == null || player == null || state == null) {
			return null;
		}
		int blockX = net.minecraft.util.Mth.floor(state.centerX);
		int blockZ = net.minecraft.util.Mth.floor(state.centerZ);
		int blockY = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
		YandexMapMarkerStore.YandexMapMarker marker = YandexMapMarkerStore.create(
				level,
				new BlockPos(blockX, blockY, blockZ),
				player,
				defaultMarkerTitle(player),
				""
		);
		return marker != null ? marker.markerId() : null;
	}

	private static boolean handleMarkerEditorTouch(
			ServerPlayer player,
			MinecraftServer server,
			ScreenComponent component,
			UiLayout layout,
			UiPoint touchPoint,
			YandexMapState state
	) {
		if (player == null || server == null || component == null || layout == null || touchPoint == null || state == null) {
			return false;
		}
		UUID markerId;
		synchronized (state) {
			markerId = state.editorMarkerId;
		}
		if (markerId == null) {
			return false;
		}
		YandexMapMarkerStore.YandexMapMarker marker = YandexMapMarkerStore.marker(markerId);
		if (marker == null) {
			synchronized (state) {
				state.editorMarkerId = null;
				state.markerDeleteConfirmMarkerId = null;
				state.version++;
			}
			clearPendingMarkerTitleRequests(component.runtimeKey());
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		boolean deleteConfirmOpen;
		synchronized (state) {
			deleteConfirmOpen = Objects.equals(state.markerDeleteConfirmMarkerId, markerId);
		}
		if (deleteConfirmOpen) {
			UiRect confirmPanel = markerEditorDeleteConfirmPanelRect(layout, marker);
			if (markerEditorDeleteConfirmConfirmRect(layout, marker).contains(touchPoint.x(), touchPoint.y())) {
				YandexMapMarkerStore.remove(server, markerId);
				invalidateMarkerIcon(markerId);
				synchronized (state) {
					state.editorMarkerId = null;
					state.markerDeleteConfirmMarkerId = null;
					state.version++;
				}
				PENDING_MARKER_TITLES.remove(player.getUUID());
				requestRuntimeRender(server, component.runtimeKey());
				return true;
			}
			if (markerEditorDeleteConfirmCancelRect(layout, marker).contains(touchPoint.x(), touchPoint.y())
					|| !confirmPanel.contains(touchPoint.x(), touchPoint.y())) {
				synchronized (state) {
					state.markerDeleteConfirmMarkerId = null;
					state.version++;
				}
				requestRuntimeRender(server, component.runtimeKey());
				return true;
			}
			return true;
		}
		UiRect panel = markerEditorPanelRect(layout, marker);
		UiRect close = markerEditorCloseRect(layout, marker);
		if (close.contains(touchPoint.x(), touchPoint.y()) || !panel.contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.editorMarkerId = null;
				state.markerDeleteConfirmMarkerId = null;
				state.version++;
			}
			PENDING_MARKER_TITLES.remove(player.getUUID());
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (markerEditorTitleRect(layout, marker).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.markerDeleteConfirmMarkerId = null;
			}
			requestMarkerTitlePrompt(player, component.runtimeKey(), markerId);
			return true;
		}
		if (markerEditorIconRect(layout, marker).contains(touchPoint.x(), touchPoint.y())) {
			YandexMapMarkerStore.updateIcon(server, markerId, player.getMainHandItem());
			invalidateMarkerIcon(markerId);
			synchronized (state) {
				state.markerDeleteConfirmMarkerId = null;
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		if (markerEditorDeleteRect(layout, marker).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.markerDeleteConfirmMarkerId = markerId;
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		return true;
	}

	private static void drawMarkers(Graphics2D graphics, UiLayout layout, YandexMapsVisualSnapshot snapshot, ScreenRuntimeKey runtimeKey, MinecraftServer server) {
		if (graphics == null || layout == null || snapshot == null || runtimeKey == null) {
			return;
		}
		UUID editorMarkerId = null;
		Set<UUID> observedMarkerIds = Set.of();
		YandexMapState state = STATES.get(runtimeKey);
		if (state != null) {
			synchronized (state) {
				editorMarkerId = state.editorMarkerId;
				observedMarkerIds = state.observedMarkerIds;
			}
		}
		for (ProjectedMarker marker : projectVisibleMarkers(
				MAP_DIMENSION,
				layout,
				snapshot.centerX(),
				snapshot.centerZ(),
				snapshot.zoomBlocks()
		)) {
			drawMarker(
					graphics,
					layout,
					marker,
					Objects.equals(editorMarkerId, marker.markerId()) || observedMarkerIds.contains(marker.markerId()),
					server,
					runtimeKey
			);
		}
	}

	private static void drawMarker(
			Graphics2D graphics,
			UiLayout layout,
			ProjectedMarker projected,
			boolean expanded,
			MinecraftServer server,
			ScreenRuntimeKey runtimeKey
	) {
		if (graphics == null || layout == null || projected == null || projected.marker() == null) {
			return;
		}
		boolean ultra = ultraCompactScreenLayout(layout);
		UiRect canvas = mediaCanvasRect(layout);
		UiRect baseIconRect = projected.iconRect();
		if (!expanded) {
			drawMarkerIcon(graphics, baseIconRect, projected.marker(), server, runtimeKey);
			return;
		}

		int pad = ultra ? 2 : clampInt(layout.unit() / 2, 4, 8);
		int rowGap = ultra ? 1 : Math.max(2, layout.unit() / 3);
		int titleFontSize = ultra ? clampInt(layout.unit() + 1, 6, 8) : clampInt(layout.unit(), 9, 13);
		int metaFontSize = ultra ? clampInt(layout.unit(), 6, 7) : clampInt(layout.unit() - 2, 8, 10);
		int maxChipWidth = Math.max(MAP_MARKER_SCREEN_ICON_SIZE + pad * 2, Math.min(canvas.width() - pad * 2, ultra ? 96 : 188));
		Font previousFont = graphics.getFont();
		Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, titleFontSize);
		Font metaFont = new Font(Font.SANS_SERIF, Font.PLAIN, metaFontSize);
		graphics.setFont(titleFont);
		var titleMetrics = graphics.getFontMetrics();
		int topTextMaxWidth = Math.max(16, maxChipWidth - MAP_MARKER_SCREEN_ICON_SIZE - pad * 3);
		String title = truncateWithEllipsis(titleMetrics, projected.marker().title(), topTextMaxWidth);
		graphics.setFont(metaFont);
		var metaMetrics = graphics.getFontMetrics();
		int metaTextMaxWidth = Math.max(16, maxChipWidth - pad * 2);
		String coordinates = truncateWithEllipsis(metaMetrics, markerCoordinateLabel(projected.marker()), metaTextMaxWidth);
		int topRowWidth = MAP_MARKER_SCREEN_ICON_SIZE + pad + titleMetrics.stringWidth(title);
		int metaRowWidth = metaMetrics.stringWidth(coordinates);
		int metaRowHeight = metaMetrics.getHeight();
		int topRowHeight = Math.max(MAP_MARKER_SCREEN_ICON_SIZE, titleMetrics.getHeight());
		int chipWidth = Math.min(maxChipWidth, Math.max(topRowWidth, metaRowWidth) + pad * 2);
		int chipHeight = topRowHeight + metaRowHeight + pad * 2 + rowGap;
		int markerCenterX = baseIconRect.x() + baseIconRect.width() / 2;
		int markerCenterY = baseIconRect.y() + baseIconRect.height() / 2;
		UiRect chipRect = new UiRect(
				clampInt(markerCenterX - chipWidth / 2, canvas.x(), Math.max(canvas.x(), canvas.right() - chipWidth)),
				clampInt(markerCenterY - chipHeight / 2, canvas.y(), Math.max(canvas.y(), canvas.bottom() - chipHeight)),
				chipWidth,
				chipHeight
		);
		UiRect iconRect = new UiRect(
				chipRect.x() + pad,
				chipRect.y() + pad + (topRowHeight - MAP_MARKER_SCREEN_ICON_SIZE) / 2,
				MAP_MARKER_SCREEN_ICON_SIZE,
				MAP_MARKER_SCREEN_ICON_SIZE
		);
		fillRoundedRect(graphics, chipRect, clampInt(chipRect.height() / 2, 8, 16), new Color(250, 252, 248, 236));
		strokeRoundedRect(graphics, chipRect, clampInt(chipRect.height() / 2, 8, 16), 1.0F, new Color(0, 0, 0, 44));
		drawMarkerIcon(graphics, iconRect, projected.marker(), server, runtimeKey);

		UiRect titleRect = new UiRect(
				iconRect.right() + pad,
				chipRect.y() + pad,
				Math.max(16, chipRect.right() - iconRect.right() - pad * 2),
				topRowHeight
		);
		graphics.setFont(titleFont);
		drawVerticalText(graphics, title, titleRect, new Color(26, 30, 34, 244), Font.BOLD, titleFontSize);

		UiRect coordinatesRect = new UiRect(
				chipRect.x() + pad,
				chipRect.y() + pad + topRowHeight + rowGap,
				chipRect.width() - pad * 2,
				metaRowHeight
		);
		drawVerticalText(graphics, coordinates, coordinatesRect, new Color(72, 80, 88, 228), Font.PLAIN, metaFontSize);
		graphics.setFont(previousFont);
	}

	private static void drawMarkerCreatorRow(Graphics2D graphics, UiLayout layout, UiRect rect, BufferedImage head, String creatorName, int fontSize) {
		if (graphics == null || layout == null || rect == null) {
			return;
		}
		int headSize = Math.min(rect.height(), ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 1, 7, 9) : clampInt(layout.unit() + 2, 10, 14));
		UiRect headRect = new UiRect(rect.x(), rect.y() + (rect.height() - headSize) / 2, headSize, headSize);
		fillRoundedRect(graphics, headRect, clampInt(headSize / 3, 3, 6), new Color(18, 22, 28, 34));
		if (head != null) {
			drawContainedImageNearest(graphics, head, headRect, 0);
		}
		UiRect textRect = new UiRect(
				headRect.right() + Math.max(2, layout.unit() / 3),
				rect.y(),
				Math.max(1, rect.right() - headRect.right() - Math.max(2, layout.unit() / 3)),
				rect.height()
		);
		String fittedName = truncateWithEllipsis(graphics.getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, fontSize)), creatorName, textRect.width());
		drawVerticalText(graphics, fittedName, textRect, new Color(72, 80, 88, 228), Font.PLAIN, fontSize);
	}

	private static Runnable markerImageReadyCallback(MinecraftServer server, ScreenRuntimeKey runtimeKey) {
		if (server == null || runtimeKey == null) {
			return null;
		}
		return () -> server.execute(() -> requestRuntimeRender(server, runtimeKey));
	}

	private static void drawMarkerIcon(Graphics2D graphics, UiRect rect, YandexMapMarkerStore.YandexMapMarker marker, MinecraftServer server, ScreenRuntimeKey runtimeKey) {
		if (graphics == null || rect == null || marker == null) {
			return;
		}
		BufferedImage icon = markerItemIcon(marker, server, runtimeKey);
		if (icon != null) {
			drawContainedImageNearest(graphics, icon, rect, 0);
			return;
		}
		BufferedImage defaultIcon = defaultMarkerIcon();
		if (defaultIcon != null) {
			drawContainedImageNearest(graphics, defaultIcon, rect, 0);
			return;
		}
		int inset = Math.max(2, rect.width() / 6);
		drawPlayerUiIcon(graphics, rect.inset(inset), PlayerUiIcon.DIRECTIONS_2_LINE, new Color(20, 24, 26, 236));
	}

	private static void drawMarkerEditor(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, MinecraftServer server) {
		if (graphics == null || layout == null || runtimeKey == null) {
			return;
		}
		YandexMapState state = STATES.get(runtimeKey);
		if (state == null) {
			return;
		}
		UUID markerId;
		synchronized (state) {
			markerId = state.editorMarkerId;
		}
		if (markerId == null) {
			return;
		}
		YandexMapMarkerStore.YandexMapMarker marker = YandexMapMarkerStore.marker(markerId);
		if (marker == null) {
			return;
		}

		UiRect canvas = mediaCanvasRect(layout);
		boolean deleteConfirmOpen;
		synchronized (state) {
			deleteConfirmOpen = Objects.equals(state.markerDeleteConfirmMarkerId, markerId);
		}
		UiRect closeRect = markerEditorCloseRect(layout, marker);
		UiRect creatorRect = markerEditorCreatorRect(layout, marker);
		UiRect titleRect = markerEditorTitleRect(layout, marker);
		UiRect iconRect = markerEditorIconRect(layout, marker);
		UiRect coordinatesRect = markerEditorCoordinatesRect(layout, marker);
		drawOverlayBackdrop(graphics, canvas);
		if (deleteConfirmOpen) {
			drawMarkerEditorDeleteConfirm(graphics, layout, marker);
			return;
		}
		drawMarkerEditorTitleHeader(graphics, layout, titleRect, marker.title());
		drawCloseGlyph(graphics, mediaChromeIconRect(closeRect, layout), new Color(248, 251, 255, 236));

		// drawScreen runs on MonitorScreenSystem's worker.  It may compose an
		// already-resolved head, but the player-list lookup that refreshes it is
		// explicitly queued back onto the server thread.
		BufferedImage creatorHead = PlayerHeadRenderSystem.cachedHead(marker.creatorUuid(), marker.creatorName());
		requestMarkerCreatorHead(marker, server, runtimeKey);
		drawMarkerEditorCreatorCard(graphics, layout, creatorRect, creatorHead, marker.creatorName());
		drawMarkerEditorIconField(graphics, layout, iconRect, marker, server, runtimeKey);
		drawMarkerEditorCoordinatesRow(graphics, layout, coordinatesRect, marker, markerCoordinateLabel(marker));
	}

	private static List<ProjectedMarker> projectVisibleMarkers(
			ResourceKey<Level> dimension,
			UiLayout layout,
			double centerX,
			double centerZ,
			double zoomBlocks
	) {
		if (dimension == null || layout == null || !Double.isFinite(centerX) || !Double.isFinite(centerZ) || !Double.isFinite(zoomBlocks) || zoomBlocks <= 0.0D) {
			return List.of();
		}
		UiRect canvas = mediaCanvasRect(layout);
		if (canvas.width() <= 0 || canvas.height() <= 0) {
			return List.of();
		}
		int iconSize = MAP_MARKER_SCREEN_ICON_SIZE;
		double horizontalMargin = iconSize * zoomBlocks * 1.5D;
		double verticalMargin = iconSize * zoomBlocks * 1.5D;
		double minX = centerX - canvas.width() * zoomBlocks * 0.5D - horizontalMargin;
		double maxX = centerX + canvas.width() * zoomBlocks * 0.5D + horizontalMargin;
		double minZ = centerZ - canvas.height() * zoomBlocks * 0.5D - verticalMargin;
		double maxZ = centerZ + canvas.height() * zoomBlocks * 0.5D + verticalMargin;
		int centerScreenX = canvas.x() + canvas.width() / 2;
		int centerScreenY = canvas.y() + canvas.height() / 2;
		List<ProjectedMarker> projected = new ArrayList<>();
		for (YandexMapMarkerStore.YandexMapMarker marker : YandexMapMarkerStore.markers(dimension)) {
			if (marker == null || marker.centerX() < minX || marker.centerX() > maxX || marker.centerZ() < minZ || marker.centerZ() > maxZ) {
				continue;
			}
			int markerScreenX = net.minecraft.util.Mth.floor(centerScreenX + (marker.centerX() - centerX) / zoomBlocks);
			int markerScreenY = net.minecraft.util.Mth.floor(centerScreenY + (marker.centerZ() - centerZ) / zoomBlocks);
			UiRect iconRect = new UiRect(markerScreenX - iconSize / 2, markerScreenY - iconSize / 2, iconSize, iconSize);
			projected.add(new ProjectedMarker(marker.markerId(), marker, iconRect));
		}
		return projected;
	}

	private static ProjectedMarker markerAtTouch(
			ResourceKey<Level> dimension,
			UiLayout layout,
			double centerX,
			double centerZ,
			double zoomBlocks,
			UUID editorMarkerId,
			UiPoint touchPoint
	) {
		if (touchPoint == null) {
			return null;
		}
		List<ProjectedMarker> projectedMarkers = projectVisibleMarkers(dimension, layout, centerX, centerZ, zoomBlocks);
		return markerAtTouch(projectedMarkers, layout, editorMarkerId, touchPoint);
	}

	private static ProjectedMarker markerAtTouch(
			List<ProjectedMarker> projectedMarkers,
			UiLayout layout,
			UUID editorMarkerId,
			UiPoint touchPoint
	) {
		if (touchPoint == null || projectedMarkers == null || projectedMarkers.isEmpty()) {
			return null;
		}
		ProjectedMarker fallback = null;
		for (ProjectedMarker projected : projectedMarkers) {
			UiRect hitRect = markerHitRect(projected, layout);
			if (!hitRect.contains(touchPoint.x(), touchPoint.y())) {
				continue;
			}
			if (fallback == null || Objects.equals(projected.markerId(), editorMarkerId)) {
				fallback = projected;
			}
		}
		return fallback;
	}

	private static UiRect markerHitRect(ProjectedMarker projected, UiLayout layout) {
		if (projected == null || projected.iconRect() == null || layout == null) {
			return new UiRect(0, 0, 0, 0);
		}
		int expandX = Math.max(2, layout.unit() / 3);
		int expandY = Math.max(2, layout.unit() / 3);
		return new UiRect(
				projected.iconRect().x() - expandX,
				projected.iconRect().y() - expandY,
				projected.iconRect().width() + expandX * 2,
				projected.iconRect().height() + expandY * 2
		);
	}

	private static UiRect markerEditorPanelRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		UiRect canvas = mediaCanvasRect(layout);
		if (ultraCompactScreenLayout(layout)) {
			return canvas;
		}
		int inset = clampInt(layout.unit(), 8, 16);
		int availableWidth = Math.max(1, canvas.width() - inset * 2);
		int availableHeight = Math.max(1, canvas.height() - inset * 2);
		int targetSquare = compactScreenLayout(layout) ? clampInt(layout.unit() * 20, 132, 190) : clampInt(layout.unit() * 24, 170, 260);
		int square = Math.min(Math.min(availableWidth, availableHeight), targetSquare);
		int maxWidth = Math.min(availableWidth, Math.max(square, compactScreenLayout(layout) ? square + layout.unit() * 8 : square + layout.unit() * 12));
		int requiredWidth = markerEditorEstimatedPanelWidth(layout, marker);
		int width = requiredWidth > square ? Math.min(maxWidth, requiredWidth) : square;
		return new UiRect(
				canvas.x() + Math.max(0, (canvas.width() - width) / 2),
				canvas.y() + Math.max(0, (canvas.height() - square) / 2),
				width,
				square
		);
	}

	private static int markerEditorEstimatedPanelWidth(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		String title = marker != null && marker.title() != null ? marker.title() : "";
		int codePoints = title.codePointCount(0, title.length());
		int titleWidth = Math.max(layout.unit() * 4, codePoints * markerEditorTitleFontSize(layout) * 11 / 16);
		int inset = markerEditorInset(layout);
		int gap = markerEditorGap(layout);
		return titleWidth + markerEditorEditIconSize(layout) + markerEditorCloseButtonSize(layout) + inset * 2 + gap * 4;
	}

	private static int markerEditorPanelArc(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 2, 8, 14) : clampInt(layout.unit() * 3, 16, 24);
	}

	private static int markerEditorInset(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? Math.max(3, layout.unit() / 2) : clampInt(layout.unit(), 8, 14);
	}

	private static int markerEditorGap(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 5) : clampInt(layout.unit() / 3, 2, 6);
	}

	private static int markerEditorTitleFontSize(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? clampInt(layout.unit(), 6, 8) : compactScreenLayout(layout) ? clampInt(layout.unit() + 1, 9, 13) : clampInt(layout.unit() + 2, 12, 17);
	}

	private static int markerEditorMetaFontSize(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? clampInt(layout.unit(), 6, 7) : compactScreenLayout(layout) ? clampInt(layout.unit(), 7, 10) : clampInt(layout.unit(), 9, 12);
	}

	private static int markerEditorEditIconSize(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? clampInt(layout.unit(), 6, 8) : clampInt(layout.unit() + 1, 7, 10);
	}

	private static int markerEditorCloseButtonSize(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 2 + 2, 12, 14) : clampInt(layout.unit() * 2 + 2, 18, 26);
	}

	private static int markerEditorHeaderHeight(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 2 + 2, 14, 18) : compactScreenLayout(layout) ? clampInt(layout.unit() * 2 + 6, 20, 28) : clampInt(layout.unit() * 3, 24, 32);
	}

	private static UiRect markerEditorHeaderRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		UiRect panel = markerEditorPanelRect(layout, marker);
		int inset = markerEditorInset(layout);
		return new UiRect(panel.x() + inset, panel.y() + inset, Math.max(20, panel.width() - inset * 2), markerEditorHeaderHeight(layout));
	}

	private static UiRect markerEditorCloseRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		UiRect header = markerEditorHeaderRect(layout, marker);
		int size = markerEditorCloseButtonSize(layout);
		return new UiRect(header.right() - size, header.y() + Math.max(0, (header.height() - size) / 2), size, size);
	}

	private static UiRect markerEditorTitleRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		UiRect header = markerEditorHeaderRect(layout, marker);
		UiRect close = markerEditorCloseRect(layout, marker);
		int gap = markerEditorGap(layout);
		return new UiRect(header.x(), header.y(), Math.max(16, close.x() - header.x() - gap), header.height());
	}

	private static UiRect markerEditorCreatorRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		UiRect header = markerEditorHeaderRect(layout, marker);
		UiRect panel = markerEditorPanelRect(layout, marker);
		int inset = markerEditorInset(layout);
		int gap = markerEditorGap(layout);
		int height = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 2, 14, 18) : compactScreenLayout(layout) ? clampInt(layout.unit() * 2 + 2, 16, 22) : clampInt(layout.unit() * 3, 20, 28);
		return new UiRect(panel.x() + inset, header.bottom() + gap, Math.max(20, panel.width() - inset * 2), height);
	}

	private static UiRect markerEditorCoordinatesRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		UiRect panel = markerEditorPanelRect(layout, marker);
		int inset = markerEditorInset(layout);
		int height = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 4, 12, 16) : compactScreenLayout(layout) ? clampInt(layout.unit() + 4, 14, 20) : clampInt(layout.unit() * 2, 16, 24);
		return new UiRect(panel.x() + inset, panel.bottom() - inset - height, Math.max(20, panel.width() - inset * 2), height);
	}

	private static UiRect markerEditorIconRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		UiRect creator = markerEditorCreatorRect(layout, marker);
		UiRect coordinates = markerEditorCoordinatesRect(layout, marker);
		UiRect panel = markerEditorPanelRect(layout, marker);
		int gap = markerEditorGap(layout);
		int top = creator.bottom() + gap;
		int bottom = coordinates.y() - gap;
		int availableWidth = Math.max(24, panel.width() - markerEditorInset(layout) * 2);
		int availableHeight = Math.max(24, bottom - top);
		int size = Math.max(22, Math.min(availableWidth, availableHeight) - Math.max(1, layout.unit() / 2));
		return new UiRect(panel.x() + Math.max(0, (panel.width() - size) / 2), top + Math.max(0, (availableHeight - size) / 2), size, size);
	}

	private static int markerEditorDeleteIconSize(UiLayout layout, UiRect rect) {
		if (layout == null || rect == null) {
			return 0;
		}
		return Math.min(rect.height(), ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 2, 12, 16) : clampInt(layout.unit() * 2 + 2, 14, 22));
	}

	private static UiRect markerEditorDeleteRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		UiRect coordinates = markerEditorCoordinatesRect(layout, marker);
		if (layout == null || marker == null || coordinates == null) {
			return new UiRect(0, 0, 0, 0);
		}
		int gap = Math.max(2, markerEditorGap(layout) * 2);
		int size = markerEditorDeleteIconSize(layout, coordinates);
		String label = markerCoordinateLabel(marker);
		int fontSize = markerEditorMetaFontSize(layout);
		int estimatedTextWidth = Math.max(12, Math.min(coordinates.width() - size - gap, label.codePointCount(0, label.length()) * fontSize * 11 / 16));
		int groupWidth = estimatedTextWidth + gap + size;
		int groupX = coordinates.x() + Math.max(0, (coordinates.width() - groupWidth) / 2);
		int iconX = groupX + estimatedTextWidth + gap;
		int iconY = coordinates.y() + Math.max(0, (coordinates.height() - size) / 2);
		int padding = Math.max(12, layout.unit() * 2);
		int left = Math.max(coordinates.x() + coordinates.width() / 2, iconX - padding);
		int top = Math.max(coordinates.y(), iconY - padding);
		int right = Math.min(coordinates.right(), iconX + size + padding);
		int bottom = Math.min(coordinates.bottom(), iconY + size + padding);
		return new UiRect(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
	}

	private static UiRect markerEditorDeleteConfirmPanelRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		UiRect editor = markerEditorPanelRect(layout, marker);
		if (layout == null || marker == null || editor == null || ultraCompactScreenLayout(layout)) {
			return editor != null ? editor : new UiRect(0, 0, 0, 0);
		}
		int side = Math.max(1, Math.min(editor.width(), editor.height()));
		return new UiRect(
				editor.x() + Math.max(0, (editor.width() - side) / 2),
				editor.y() + Math.max(0, (editor.height() - side) / 2),
				side,
				side
		);
	}

	private static UiRect markerEditorDeleteConfirmHeaderRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		return new UiRect(0, 0, 0, 0);
	}

	private static UiRect markerEditorDeleteConfirmBodyRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		UiRect panel = markerEditorDeleteConfirmPanelRect(layout, marker);
		int inset = markerEditorInset(layout);
		return new UiRect(
				panel.x() + inset,
				panel.y() + inset,
				Math.max(12, panel.width() - inset * 2),
				Math.max(16, panel.height() - inset * 2)
		);
	}

	private static UiRect markerEditorDeleteConfirmInfoRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		return markerEditorDeleteConfirmActionRect(layout, marker, 0);
	}

	private static UiRect markerEditorDeleteConfirmCancelRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		return markerEditorDeleteConfirmActionRect(layout, marker, 1);
	}

	private static UiRect markerEditorDeleteConfirmConfirmRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		return markerEditorDeleteConfirmActionRect(layout, marker, 2);
	}

	private static UiRect markerEditorDeleteConfirmCloseRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		return new UiRect(0, 0, 0, 0);
	}

	private static UiRect markerEditorDeleteConfirmActionRect(UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker, int index) {
		UiRect body = markerEditorDeleteConfirmBodyRect(layout, marker);
		int actionCount = 3;
		int safeIndex = clampInt(index, 0, actionCount - 1);
		int gap = markerEditorGap(layout);
		int height = Math.max(16, (body.height() - gap * (actionCount - 1)) / actionCount);
		return new UiRect(
				body.x(),
				body.y() + safeIndex * (height + gap),
				body.width(),
				height
		);
	}

	private static void drawMarkerEditorCreatorCard(Graphics2D graphics, UiLayout layout, UiRect rect, BufferedImage head, String creatorName) {
		if (graphics == null || layout == null || rect == null) {
			return;
		}
		int gap = markerEditorGap(layout);
		int headSize = Math.max(8, Math.min(rect.height(), compactScreenLayout(layout) ? clampInt(layout.unit() * 2 - 1, 10, 16) : clampInt(layout.unit() * 3 - 2, 14, 20)));
		int fontSize = markerEditorMetaFontSize(layout);
		Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
		var metrics = graphics.getFontMetrics(font);
		String fittedName = truncateWithEllipsis(metrics, creatorName, Math.max(12, rect.width() - headSize - gap));
		int nameWidth = Math.max(1, metrics.stringWidth(fittedName));
		int rowWidth = headSize + gap + nameWidth;
		int startX = rect.x() + Math.max(0, (rect.width() - rowWidth) / 2);
		UiRect headRect = new UiRect(startX, rect.y() + (rect.height() - headSize) / 2, headSize, headSize);
		fillRoundedRect(graphics, headRect, clampInt(headSize / 3, 3, 6), new Color(255, 255, 255, 14));
		if (head != null) {
			drawContainedImageNearest(graphics, head, headRect, 0);
		}
		UiRect textRect = new UiRect(headRect.right() + gap, rect.y(), nameWidth, rect.height());
		drawVerticalText(
				graphics,
				fittedName,
				textRect,
				new Color(248, 240, 244, 236),
				Font.BOLD,
				fontSize
		);
	}

	private static void drawMarkerEditorTitleHeader(Graphics2D graphics, UiLayout layout, UiRect rect, String title) {
		if (graphics == null || layout == null || rect == null) {
			return;
		}
		int fontSize = markerEditorTitleFontSize(layout);
		int iconSize = markerEditorEditIconSize(layout);
		int gap = markerEditorGap(layout);
		Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
		var metrics = graphics.getFontMetrics(font);
		int textMaxWidth = Math.max(12, rect.width() - iconSize - gap);
		String fittedTitle = truncateWithEllipsis(metrics, title, textMaxWidth);
		int titleWidth = Math.min(textMaxWidth, metrics.stringWidth(fittedTitle));
		int groupWidth = iconSize + gap + titleWidth;
		int groupX = rect.x() + Math.max(0, (rect.width() - groupWidth) / 2);
		UiRect iconRect = new UiRect(groupX, rect.y() + Math.max(0, (rect.height() - iconSize) / 2), iconSize, iconSize);
		drawPlayerUiIcon(graphics, iconRect, PlayerUiIcon.EDIT, new Color(248, 240, 244, 204));
		UiRect textRect = new UiRect(iconRect.right() + gap, rect.y(), titleWidth, rect.height());
		drawVerticalText(
				graphics,
				fittedTitle,
				textRect,
				new Color(248, 240, 244, 236),
				Font.BOLD,
				fontSize
		);
	}

	private static void drawMarkerEditorIconField(
			Graphics2D graphics,
			UiLayout layout,
			UiRect rect,
			YandexMapMarkerStore.YandexMapMarker marker,
			MinecraftServer server,
			ScreenRuntimeKey runtimeKey
	) {
		if (graphics == null || layout == null || rect == null || marker == null) {
			return;
		}
		int arc = ultraCompactScreenLayout(layout) ? clampInt(layout.unit(), 5, 8) : clampInt(layout.unit() * 2, 10, 16);
		fillRoundedRect(graphics, rect, arc, new Color(255, 255, 255, 10));
		strokeRoundedRect(graphics, rect, arc, 1.0F, new Color(255, 255, 255, 28));
		UiRect previewRect = rect.inset(Math.max(1, rect.width() / 28));
		drawMarkerIcon(graphics, previewRect, marker, server, runtimeKey);
	}

	private static void drawMarkerEditorCoordinatesRow(Graphics2D graphics, UiLayout layout, UiRect rect, YandexMapMarkerStore.YandexMapMarker marker, String coordinates) {
		if (graphics == null || layout == null || rect == null || marker == null) {
			return;
		}
		int gap = Math.max(2, markerEditorGap(layout) * 2);
		int fontSize = markerEditorMetaFontSize(layout);
		Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
		var metrics = graphics.getFontMetrics(font);
		int iconSize = markerEditorDeleteIconSize(layout, rect);
		String fitted = truncateWithEllipsis(metrics, coordinates, Math.max(12, rect.width() - iconSize - gap));
		int textWidth = Math.max(1, metrics.stringWidth(fitted));
		int groupWidth = textWidth + gap + iconSize;
		int groupX = rect.x() + Math.max(0, (rect.width() - groupWidth) / 2);
		UiRect textRect = new UiRect(groupX, rect.y(), textWidth, rect.height());
		drawVerticalText(graphics, fitted, textRect, new Color(218, 226, 236, 210), Font.PLAIN, fontSize);
		UiRect deleteIconRect = new UiRect(textRect.right() + gap, rect.y() + Math.max(0, (rect.height() - iconSize) / 2), iconSize, iconSize);
		drawMarkerEditorDeleteIconButton(graphics, layout, deleteIconRect);
	}

	private static void drawMarkerEditorDeleteIconButton(Graphics2D graphics, UiLayout layout, UiRect rect) {
		if (graphics == null || layout == null || rect == null) {
			return;
		}
		int arc = clampInt(rect.width() / 3, 5, 9);
		fillRoundedRect(graphics, rect, arc, new Color(255, 255, 255, 12));
		drawPlayerUiIcon(graphics, mediaChromeIconRect(rect, layout), PlayerUiIcon.TRASH, new Color(255, 142, 150, 236));
	}

	private static void drawMarkerEditorDeleteConfirm(Graphics2D graphics, UiLayout layout, YandexMapMarkerStore.YandexMapMarker marker) {
		if (graphics == null || layout == null || marker == null) {
			return;
		}
		UiRect infoRect = markerEditorDeleteConfirmInfoRect(layout, marker);
		UiRect cancelRect = markerEditorDeleteConfirmCancelRect(layout, marker);
		UiRect confirmRect = markerEditorDeleteConfirmConfirmRect(layout, marker);
		drawGalleryDeleteConfirmInfo(graphics, layout, infoRect);
		drawGalleryFileMenuActionButton(graphics, layout, cancelRect, PlayerUiIcon.CLOSE, "ОТМЕНА", "", true, false, false, 28, 16);
		drawGalleryFileMenuActionButton(graphics, layout, confirmRect, PlayerUiIcon.TRASH, "УДАЛИТЬ", "", true, false, true, 28, 16);
	}

	private static String markerCoordinateLabel(YandexMapMarkerStore.YandexMapMarker marker) {
		if (marker == null) {
			return "";
		}
		return marker.blockX() + ", " + marker.blockY() + ", " + marker.blockZ();
	}

	private static BufferedImage defaultMarkerIcon() {
		BufferedImage cached = defaultMarkerIcon;
		if (cached == null) {
			BufferedImage loaded = MAP_ASSETS.loadTexture(DEFAULT_MARKER_ICON_TEXTURE);
			cached = loaded != null ? loaded : EMPTY_MARKER_ICON;
			defaultMarkerIcon = cached;
		}
		return cached == EMPTY_MARKER_ICON ? null : cached;
	}

	private static BufferedImage markerItemIcon(YandexMapMarkerStore.YandexMapMarker marker, MinecraftServer server, ScreenRuntimeKey runtimeKey) {
		if (marker == null || marker.iconCacheKey().isBlank()) {
			return null;
		}
		BufferedImage cached = ITEM_MARKER_ICON_CACHE.get(marker.markerId());
		if (cached != null) {
			return cached == EMPTY_MARKER_ICON ? null : cached;
		}
		requestMarkerItemIcon(marker, server, runtimeKey);
		return legacyMarkerItemIcon(marker.iconItemId());
	}

	private static BufferedImage legacyMarkerItemIcon(String iconItemId) {
		if (iconItemId == null || iconItemId.isBlank()) {
			return null;
		}
		BufferedImage cached = LEGACY_ITEM_MARKER_ICON_CACHE.computeIfAbsent(
				iconItemId,
				key -> {
					BufferedImage loaded = loadItemMarkerIcon(key);
					return loaded != null ? loaded : EMPTY_MARKER_ICON;
				}
		);
		return cached == EMPTY_MARKER_ICON ? null : cached;
	}

	private static void requestMarkerItemIcon(YandexMapMarkerStore.YandexMapMarker marker, MinecraftServer server, ScreenRuntimeKey runtimeKey) {
		if (marker == null || server == null || runtimeKey == null || marker.iconCacheKey().isBlank()) {
			return;
		}
		UUID markerId = marker.markerId();
		if (markerId == null || !PENDING_MARKER_ICON_RENDERS.add(markerId)) {
			return;
		}
		// ItemStack decoding and renderer-bot submission both touch live server
		// state.  Marker painting is a worker task, so it only enqueues this
		// server-thread operation.
		server.execute(() -> requestMarkerItemIconOnServer(marker, server, runtimeKey));
	}

	private static void requestMarkerItemIconOnServer(YandexMapMarkerStore.YandexMapMarker marker, MinecraftServer server, ScreenRuntimeKey runtimeKey) {
		if (marker == null || server == null || runtimeKey == null) {
			return;
		}
		UUID markerId = marker.markerId();
		if (markerId == null) {
			return;
		}
		String requestedIconKey = marker.iconCacheKey();
		YandexMapMarkerStore.YandexMapMarker current = YandexMapMarkerStore.marker(markerId);
		if (current == null || !Objects.equals(current.iconCacheKey(), requestedIconKey)) {
			PENDING_MARKER_ICON_RENDERS.remove(markerId);
			return;
		}
		ItemStack iconStack = YandexMapMarkerStore.markerIconStack(server, current);
		if (iconStack.isEmpty()) {
			ITEM_MARKER_ICON_CACHE.put(markerId, EMPTY_MARKER_ICON);
			PENDING_MARKER_ICON_RENDERS.remove(markerId);
			return;
		}
		RendererBotCameraSystem.requestItemIcon(server, iconStack, MAP_MARKER_ICON_SIZE).whenComplete((pixels, throwable) ->
				server.execute(() -> {
					try {
						YandexMapMarkerStore.YandexMapMarker completedMarker = YandexMapMarkerStore.marker(markerId);
						if (completedMarker == null || !Objects.equals(completedMarker.iconCacheKey(), requestedIconKey)) {
							return;
						}
						if (throwable != null) {
							BufferedImage fallback = legacyMarkerItemIcon(marker.iconItemId());
							if (fallback != null) {
								ITEM_MARKER_ICON_CACHE.put(markerId, fallback);
							}
							requestRuntimeRender(server, runtimeKey);
							return;
						}
						BufferedImage icon = decodeMarkerIconArgb(pixels, MAP_MARKER_ICON_SIZE);
						ITEM_MARKER_ICON_CACHE.put(markerId, icon != null ? icon : EMPTY_MARKER_ICON);
						requestRuntimeRender(server, runtimeKey);
					} finally {
						PENDING_MARKER_ICON_RENDERS.remove(markerId);
					}
				})
		);
	}

	private static void requestMarkerCreatorHead(YandexMapMarkerStore.YandexMapMarker marker, MinecraftServer server, ScreenRuntimeKey runtimeKey) {
		if (marker == null || server == null || runtimeKey == null || marker.creatorUuid() == null) {
			return;
		}
		if (PlayerHeadRenderSystem.hasCachedHead(marker.creatorUuid(), marker.creatorName())) {
			return;
		}
		MarkerHeadRequest request = new MarkerHeadRequest(marker.markerId(), runtimeKey);
		if (!PENDING_MARKER_HEAD_REQUESTS.add(request)) {
			return;
		}
		server.execute(() -> {
			try {
				YandexMapMarkerStore.YandexMapMarker current = YandexMapMarkerStore.marker(marker.markerId());
				if (current == null || !Objects.equals(current.creatorUuid(), marker.creatorUuid())) {
					PENDING_MARKER_HEAD_REQUESTS.remove(request);
					return;
				}
				boolean loading = PlayerHeadRenderSystem.requestHead(server, current.creatorUuid(), current.creatorName(), () -> {
					PENDING_MARKER_HEAD_REQUESTS.remove(request);
					server.execute(() -> requestRuntimeRender(server, runtimeKey));
				});
				if (!loading) {
					// Cached image or no usable skin property: no asynchronous callback
					// exists to release this request.
					PENDING_MARKER_HEAD_REQUESTS.remove(request);
				}
			} catch (RuntimeException exception) {
				PENDING_MARKER_HEAD_REQUESTS.remove(request);
			}
		});
	}

	private static void invalidateMarkerIcon(UUID markerId) {
		if (markerId == null) {
			return;
		}
		ITEM_MARKER_ICON_CACHE.remove(markerId);
		PENDING_MARKER_ICON_RENDERS.remove(markerId);
	}

	private static BufferedImage decodeMarkerIconArgb(byte[] pixels, int iconSize) {
		int safeIconSize = Math.max(1, iconSize);
		if (pixels == null || pixels.length < safeIconSize * safeIconSize * 4) {
			return null;
		}
		BufferedImage icon = new BufferedImage(safeIconSize, safeIconSize, BufferedImage.TYPE_INT_ARGB);
		int[] argb = new int[safeIconSize * safeIconSize];
		for (int i = 0; i < argb.length; i++) {
			int offset = i * 4;
			argb[i] = (Byte.toUnsignedInt(pixels[offset]) << 24)
					| (Byte.toUnsignedInt(pixels[offset + 1]) << 16)
					| (Byte.toUnsignedInt(pixels[offset + 2]) << 8)
					| Byte.toUnsignedInt(pixels[offset + 3]);
		}
		icon.setRGB(0, 0, safeIconSize, safeIconSize, argb, 0, safeIconSize);
		return fitMarkerIconToAlphaBounds(icon);
	}

	private static BufferedImage fitMarkerIconToAlphaBounds(BufferedImage icon) {
		if (icon == null || icon.getWidth() <= 1 || icon.getHeight() <= 1) {
			return icon;
		}
		int minX = icon.getWidth();
		int minY = icon.getHeight();
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < icon.getHeight(); y++) {
			for (int x = 0; x < icon.getWidth(); x++) {
				int alpha = (icon.getRGB(x, y) >>> 24) & 0xFF;
				if (alpha <= MARKER_ICON_ALPHA_BOUNDS_THRESHOLD) {
					continue;
				}
				minX = Math.min(minX, x);
				minY = Math.min(minY, y);
				maxX = Math.max(maxX, x);
				maxY = Math.max(maxY, y);
			}
		}
		if (maxX < minX || maxY < minY) {
			return icon;
		}
		if (minX == 0 && minY == 0 && maxX == icon.getWidth() - 1 && maxY == icon.getHeight() - 1) {
			return icon;
		}
		int sourceWidth = maxX - minX + 1;
		int sourceHeight = maxY - minY + 1;
		int targetSize = Math.min(icon.getWidth(), icon.getHeight());
		double scale = Math.min(targetSize / (double) sourceWidth, targetSize / (double) sourceHeight);
		int drawWidth = clampInt((int) Math.round(sourceWidth * scale), 1, targetSize);
		int drawHeight = clampInt((int) Math.round(sourceHeight * scale), 1, targetSize);
		int drawX = (targetSize - drawWidth) / 2;
		int drawY = (targetSize - drawHeight) / 2;
		BufferedImage fitted = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = fitted.createGraphics();
		configurePixelArtGraphics(graphics);
		graphics.drawImage(
				icon,
				drawX,
				drawY,
				drawX + drawWidth,
				drawY + drawHeight,
				minX,
				minY,
				maxX + 1,
				maxY + 1,
				null
		);
		graphics.dispose();
		return fitted;
	}

	private static BufferedImage loadItemMarkerIcon(String iconItemId) {
		Identifier itemId = Identifier.tryParse(iconItemId);
		if (itemId == null) {
			return null;
		}
		Identifier modelId = Identifier.fromNamespaceAndPath(itemId.getNamespace(), "item/" + itemId.getPath());
		ItemModelResolution resolution = resolveItemModel(modelId, new HashSet<>());
		if (resolution == null) {
			return null;
		}
		List<BufferedImage> layers = new ArrayList<>();
		for (int layerIndex = 0; layerIndex < 5; layerIndex++) {
			Identifier textureId = resolveItemModelTexture(resolution, "layer" + layerIndex, new HashSet<>());
			if (textureId == null) {
				continue;
			}
			BufferedImage texture = MAP_ASSETS.loadTexture(textureId);
			if (texture != null) {
				layers.add(texture);
			}
		}
		if (layers.isEmpty()) {
			Identifier fallbackTexture = resolveFallbackItemTexture(resolution);
			if (fallbackTexture != null) {
				BufferedImage texture = MAP_ASSETS.loadTexture(fallbackTexture);
				if (texture != null) {
					layers.add(texture);
				}
			}
		}
		if (layers.isEmpty()) {
			return null;
		}
		BufferedImage icon = new BufferedImage(MAP_MARKER_ICON_SIZE, MAP_MARKER_ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = icon.createGraphics();
		configurePixelArtGraphics(graphics);
		for (BufferedImage layer : layers) {
			graphics.drawImage(layer, 0, 0, MAP_MARKER_ICON_SIZE, MAP_MARKER_ICON_SIZE, null);
		}
		graphics.dispose();
		return icon;
	}

	private static ItemModelResolution resolveItemModel(Identifier modelId, Set<String> resolving) {
		if (modelId == null || resolving == null) {
			return null;
		}
		String cacheKey = modelId.toString();
		if (!resolving.add(cacheKey)) {
			return null;
		}
		try {
			JsonObject json = MAP_ASSETS.loadModel(modelId);
			if (json == null) {
				return null;
			}
			Map<String, String> textures = new HashMap<>();
			Identifier parentId = null;
			if (json.has("parent")) {
				parentId = Identifier.tryParse(json.get("parent").getAsString());
				if (parentId != null && !"builtin/generated".equals(parentId.toString())) {
					ItemModelResolution parent = resolveItemModel(parentId, resolving);
					if (parent != null) {
						textures.putAll(parent.textures());
					}
				}
			}
			if (json.has("textures")) {
				for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("textures").entrySet()) {
					textures.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
			return new ItemModelResolution(modelId.getNamespace(), textures);
		} finally {
			resolving.remove(cacheKey);
		}
	}

	private static Identifier resolveItemModelTexture(ItemModelResolution resolution, String key, Set<String> resolving) {
		if (resolution == null || key == null || key.isBlank()) {
			return null;
		}
		if (!resolving.add(key)) {
			return null;
		}
		try {
			String texture = resolution.textures().get(key);
			if (texture == null || texture.isBlank()) {
				return null;
			}
			if (texture.startsWith("#")) {
				return resolveItemModelTexture(resolution, texture.substring(1), resolving);
			}
			return textureIdentifier(resolution.namespace(), texture);
		} finally {
			resolving.remove(key);
		}
	}

	private static Identifier resolveFallbackItemTexture(ItemModelResolution resolution) {
		if (resolution == null) {
			return null;
		}
		Identifier particle = resolveItemModelTexture(resolution, "particle", new HashSet<>());
		if (particle != null) {
			return particle;
		}
		for (String key : List.of("all", "side", "top", "front", "end", "texture")) {
			Identifier resolved = resolveItemModelTexture(resolution, key, new HashSet<>());
			if (resolved != null) {
				return resolved;
			}
		}
		for (String key : resolution.textures().keySet()) {
			Identifier resolved = resolveItemModelTexture(resolution, key, new HashSet<>());
			if (resolved != null) {
				return resolved;
			}
		}
		return null;
	}

	private static Identifier textureIdentifier(String defaultNamespace, String rawTexture) {
		if (rawTexture == null || rawTexture.isBlank()) {
			return null;
		}
		if (rawTexture.contains(":")) {
			return Identifier.tryParse(rawTexture);
		}
		return Identifier.fromNamespaceAndPath(defaultNamespace == null || defaultNamespace.isBlank() ? "minecraft" : defaultNamespace, rawTexture);
	}

	private static String formatZoom(double blocksPerPixel) {
		double zoom = MonitorYandexMapsClientTileRenderer.snapBlocksPerPixel(
				clampDouble(blocksPerPixel, MIN_BLOCKS_PER_PIXEL, MAX_BLOCKS_PER_PIXEL)
		);
		if (zoom < 1.0D) {
			return Math.max(1, Math.round(1.0D / zoom)) + " px/бл";
		}
		if (Math.abs(zoom - Math.rint(zoom)) < 0.05D) {
			return Math.round(zoom) + " бл/px";
		}
		return String.format(Locale.ROOT, "%.1f бл/px", zoom);
	}

	private static void initializeStateLocked(YandexMapState state, ScreenComponent component) {
		if (state.initialized || component == null || component.runtimeKey() == null) {
			return;
		}
		BlockPos pos = component.runtimeKey().pos();
		state.centerX = pos.getX() + 0.5D;
		state.centerZ = pos.getZ() + 0.5D;
		state.targetX = state.centerX;
		state.targetZ = state.centerZ;
		state.blocksPerPixel = MonitorYandexMapsClientTileRenderer.snapBlocksPerPixel(DEFAULT_BLOCKS_PER_PIXEL);
		state.streamStatus = "Ожидание карты";
		state.initialized = true;
		state.version++;
	}

	private static void zoomLocked(YandexMapState state, double factor) {
		if (state == null || !Double.isFinite(factor) || factor <= 0.0D) {
			return;
		}
		double current = MonitorYandexMapsClientTileRenderer.snapBlocksPerPixel(state.blocksPerPixel);
		int currentZoomExponent = MonitorYandexMapsClientTileRenderer.zoomExponentForBlocksPerPixel(current);
		int nextZoomExponent = currentZoomExponent + (factor > 1.0D ? 1 : -1);
		double next = MonitorYandexMapsClientTileRenderer.blocksPerPixelForZoomExponent(nextZoomExponent);
		if (Math.abs(next - state.blocksPerPixel) <= 0.001D) {
			return;
		}
		state.blocksPerPixel = next;
		state.streamStatus = "Карта online";
		state.version++;
	}

	private static void beginPanLocked(YandexMapState state, double targetX, double targetZ, long now) {
		if (state == null || !Double.isFinite(targetX) || !Double.isFinite(targetZ)) {
			return;
		}
		state.panStartX = state.centerX;
		state.panStartZ = state.centerZ;
		state.targetX = targetX;
		state.targetZ = targetZ;
		state.panStartedAtMillis = now;
		state.streamStatus = "Карта online";
		state.version++;
	}

	private static void schedulePanAnimationFrame(MinecraftServer server, ScreenRuntimeKey key) {
		if (server == null || key == null) {
			return;
		}
		YandexMapState state = STATES.get(key);
		if (state == null) {
			return;
		}
		synchronized (state) {
			if (state.panStartedAtMillis <= 0L || state.panFrameScheduled) {
				return;
			}
			state.panFrameScheduled = true;
		}
		scheduleRuntimeCallback(server, PAN_FRAME_DELAY_MS, () -> {
			YandexMapState current = STATES.get(key);
			if (current == null) {
				return;
			}
			boolean keepAnimating;
			synchronized (current) {
				current.panFrameScheduled = false;
				keepAnimating = current.panStartedAtMillis > 0L;
			}
			if (!keepAnimating) {
				return;
			}
			requestRuntimeRender(server, key);
			schedulePanAnimationFrame(server, key);
		});
	}

	private static void scheduleRuntimeCallback(MinecraftServer server, long delayMillis, Runnable callback) {
		if (server == null || callback == null) {
			return;
		}
		ensureExecutors();
		if (mediaScheduler == null) {
			server.execute(callback);
			return;
		}
		try {
			mediaScheduler.schedule(() -> server.execute(callback), Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException ignored) {
			server.execute(callback);
		}
	}

	private static boolean applyPanAnimationLocked(YandexMapState state, long now) {
		if (state == null || state.panStartedAtMillis <= 0L) {
			return false;
		}
		double previousX = state.centerX;
		double previousZ = state.centerZ;
		long elapsed = Math.max(0L, now - state.panStartedAtMillis);
		if (elapsed >= PAN_ANIMATION_MS) {
			state.centerX = state.targetX;
			state.centerZ = state.targetZ;
			state.panStartedAtMillis = 0L;
		} else {
			double t = elapsed / (double) PAN_ANIMATION_MS;
			double eased = t * t * (3.0D - 2.0D * t);
			state.centerX = lerp(state.panStartX, state.targetX, eased);
			state.centerZ = lerp(state.panStartZ, state.targetZ, eased);
		}
		return Math.abs(previousX - state.centerX) > 0.01D || Math.abs(previousZ - state.centerZ) > 0.01D;
	}

	private static double lerp(double from, double to, double amount) {
		return from + (to - from) * clampDouble(amount, 0.0D, 1.0D);
	}

	private static String dimensionLabel(ResourceKey<Level> dimension) {
		if (dimension == null || dimension.identifier() == null) {
			return "unknown";
		}
		String path = dimension.identifier().getPath();
		if ("overworld".equals(path)) {
			return "overworld";
		}
		if ("the_nether".equals(path)) {
			return "nether";
		}
		if ("the_end".equals(path)) {
			return "end";
		}
		return path.toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	private static BufferedImage fallbackMapFrame(ScreenComponent component, String text) {
		int width = Math.max(1, component != null ? component.width() * MAP_SIZE : MAP_SIZE);
		int height = Math.max(1, component != null ? component.height() * MAP_SIZE : MAP_SIZE);
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		configureMapGraphics(graphics);
		graphics.setPaint(new GradientPaint(0, 0, new Color(0x172027), width, height, new Color(0x061016)));
		graphics.fillRect(0, 0, width, height);
		drawCenteredText(graphics, text, new UiRect(0, 0, width, height), new Color(230, 238, 244), Font.BOLD, Math.max(12, Math.min(width, height) / 12));
		graphics.dispose();
		return image;
	}

	private static BufferedImage gpsUnavailableFrameForCanvas(UiRect canvas) {
		return gpsUnavailableFrame(Math.max(1, canvas.width()), Math.max(1, canvas.height()));
	}

	private static BufferedImage gpsUnavailableFrame(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		configureMapGraphics(graphics);
		graphics.setColor(Color.BLACK);
		graphics.fillRect(0, 0, width, height);
		int iconSize = Math.max(24, Math.min(width, height) / 5);
		int centerY = height / 2 - iconSize / 4;
		drawPlayerUiIcon(graphics, new UiRect(width / 2 - iconSize / 2, centerY - iconSize / 2, iconSize, iconSize), PlayerUiIcon.BASE_STATION_2, Color.WHITE);
		drawCenteredText(graphics, "GPS спутник не найден", new UiRect(8, centerY + iconSize / 2 + 8, Math.max(1, width - 16), Math.max(16, iconSize / 2)), Color.WHITE, Font.PLAIN, Math.max(9, Math.min(width, height) / 13));
		graphics.dispose();
		return image;
	}

	private static void configureMapGraphics(Graphics2D graphics) {
		graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_SPEED);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
	}

	private static void configurePixelArtGraphics(Graphics2D graphics) {
		graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_SPEED);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
	}

	private static YandexMapsVisualSnapshot emptySnapshot() {
		return new YandexMapsVisualSnapshot(0L, null, "", "", 0, 0, DEFAULT_BLOCKS_PER_PIXEL, false);
	}

	private record ProjectedMarker(
			UUID markerId,
			YandexMapMarkerStore.YandexMapMarker marker,
			UiRect iconRect
	) {
	}

	private record ItemModelResolution(
			String namespace,
			Map<String, String> textures
	) {
	}

	private record PendingMarkerTitleRequest(
			ScreenRuntimeKey screenKey,
			UUID markerId
	) {
	}

	private record MarkerHeadRequest(UUID markerId, ScreenRuntimeKey screenKey) {
	}

	private record ObservedYandexMapUiTarget(
			ScreenComponent component,
			UiLayout layout,
			UiPoint touchPoint
	) {
	}

	private record MarkerProjectionState(
			double centerX,
			double centerZ,
			double zoomBlocks,
			UUID editorMarkerId
	) {
	}

	private static final class YandexMapState {
		private boolean initialized;
		private long version;
		private long lastAppliedRenderVersion;
		private int activeRenderJobs;
		private boolean rerenderRequested;
		private double centerX;
		private double centerZ;
		private double targetX;
		private double targetZ;
		private double panStartX;
		private double panStartZ;
		private long panStartedAtMillis;
		private double blocksPerPixel = DEFAULT_BLOCKS_PER_PIXEL;
		private String streamStatus = "Ожидание карты";
		private boolean panFrameScheduled;
		private boolean tileReadyRenderScheduled;
		private UUID editorMarkerId;
		private UUID markerDeleteConfirmMarkerId;
		private Set<UUID> observedMarkerIds = Set.of();
	}
}
