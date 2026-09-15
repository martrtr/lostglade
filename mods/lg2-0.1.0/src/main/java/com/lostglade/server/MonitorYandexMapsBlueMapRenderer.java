package com.lostglade.server;

import com.flowpowered.math.vector.Vector2i;
import com.lostglade.Lg2;
import com.lostglade.server.map.BlockTextureRaycaster;
import com.lostglade.server.map.TextureAssetManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class MonitorYandexMapsBlueMapRenderer {
	private static final int TILE_SIZE = 128;
	private static final int MIN_LOD = -9;
	private static final int MAX_LOD = 16;
	private static final int MAX_CACHED_TILES_PER_DIMENSION = 8192;
	private static final int TILE_RENDER_WORKERS = Mth.clamp(Runtime.getRuntime().availableProcessors() - 1, 2, 8);
	private static final int MAX_TILE_REQUESTS_PER_FRAME = TILE_RENDER_WORKERS * 16;
	private static final long TILE_TTL_MS = 10 * 60_000L;
	private static final long REGION_INDEX_TTL_MS = 60_000L;
	private static final int ABOVE_SURFACE_SCAN_BLOCKS = 32;
	private static final int MAX_SURFACE_SCAN_BLOCKS = 96;
	private static final int MAX_COMPOSITE_LAYERS = 10;
	private static final int[] NO_TINTS = new int[0];
	private static final int NO_TINT = -1;
	private static final int MISSING_RGB = 0x18242B;
	private static final int GRASS_TINT_RGB = 0x73B84A;
	private static final int FOLIAGE_TINT_RGB = 0x4CA33B;
	private static final int DRY_FOLIAGE_TINT_RGB = 0x9B8F4A;
	private static final int WATER_TINT_RGB = 0x3F76E4;
	private static final int ATTACHED_STEM_RGB = 0xE0C71C;
	private static final int LILY_PAD_RGB = 0x208030;
	private static final String BLUEMAP_JAR_PROPERTY = "lg2.bluemapJar";
	private static final String BLUEMAP_JAR_ENV = "LG2_BLUEMAP_JAR";
	private static final long BRIDGE_RETRY_INTERVAL_MS = 30_000L;
	private static final Path VANILLA_COMMON_JAR = Path.of(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom", "1.21.11", "minecraft-common.jar");
	private static final String MONITOR_DISPLAY_TAG = "lg2_monitor_display";
	private static final String EXIT_SIGN_DISPLAY_TAG = "lg2_exit_sign_display";
	private static final String SERVER_DISPLAY_TAG = "lg2_server_display";
	private static final Identifier MONITOR_DISPLAY_MODEL = Identifier.fromNamespaceAndPath("lg2", "item/monitor_display");
	private static final Identifier EXIT_SIGN_DISPLAY_MODEL = Identifier.fromNamespaceAndPath("lg2", "item/exit_sign");
	private static final Identifier SERVER_DISPLAY_MODEL = Identifier.fromNamespaceAndPath("lg2", "item/server");
	private static final Identifier WATER_STILL_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "block/water_still");
	private static final Identifier WATER_FLOW_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "block/water_flow");
	private static final Identifier LAVA_STILL_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");
	private static final Identifier LAVA_FLOW_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "block/lava_flow");
	private static final Object LOCK = new Object();
	private static final TextureAssetManager ASSETS = TextureAssetManager.get();
	private static final ExecutorService TILE_RENDER_EXECUTOR = Executors.newFixedThreadPool(TILE_RENDER_WORKERS, new TileThreadFactory());
	private static final Map<WorldCacheKey, DimensionTileCache> CACHES = new LinkedHashMap<>(8, 0.75F, true);
	private static final Map<String, BlockState> VANILLA_STATE_CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Integer> STATE_ARGB_CACHE = new ConcurrentHashMap<>();
	private static volatile BlueMapBridge bridge;
	private static volatile String bridgeError;
	private static volatile long nextBridgeRetryAtMs;
	private static volatile String lastBridgeLogKey;

	private MonitorYandexMapsBlueMapRenderer() {
	}

	static Frame render(MinecraftServer server, ServerLevel level, double centerX, double centerZ, int width, int height, double blocksPerPixel) {
		return render(server, level, centerX, centerZ, width, height, blocksPerPixel, List.of(), null);
	}

	static Frame render(
			MinecraftServer server,
			ServerLevel level,
			double centerX,
			double centerZ,
			int width,
			int height,
			double blocksPerPixel,
			List<DisplayOverlay> displayOverlays,
			Runnable onTileReady
	) {
		if (server == null || level == null || width <= 0 || height <= 0 || !Double.isFinite(blocksPerPixel) || blocksPerPixel <= 0.0D) {
			return Frame.failure(null, "Карта недоступна");
		}
		BlueMapBridge blueMap = bridge(server);
		if (blueMap == null) {
			String status = bridgeError == null || bridgeError.isBlank() ? "BlueMap renderer недоступен" : bridgeError;
			return Frame.failure(null, status);
		}
		Object world = blueMap.world(server, level);
		if (world == null) {
			String status = blueMap.lastWorldError == null || blueMap.lastWorldError.isBlank() ? "MCA world недоступен" : blueMap.lastWorldError;
			return Frame.failure(null, status);
		}

		double safeBlocksPerPixel = Mth.clamp(blocksPerPixel, 1.0D / 512.0D, 4096.0D);
		int lod = lodForBlocksPerPixel(safeBlocksPerPixel);
		double tileBlocksPerPixel = blocksPerPixelForLod(lod);
		double worldLeft = centerX - width * safeBlocksPerPixel * 0.5D;
		double worldTop = centerZ - height * safeBlocksPerPixel * 0.5D;
		WorldCacheKey cacheKey = new WorldCacheKey(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(), level.dimension());
		DimensionTileCache cache = cacheFor(cacheKey);
		List<TileKey> visibleTiles = visibleTiles(lod, tileBlocksPerPixel, worldLeft, worldTop, width, height, safeBlocksPerPixel, centerX, centerZ);
		long now = System.currentTimeMillis();
		int requestedTiles = 0;
		int missingTiles = 0;
		int levelMinY = level.getMinY();
		int levelMaxY = level.getMaxY() - 1;
		List<DisplayOverlay> safeOverlays = displayOverlays == null || displayOverlays.isEmpty()
				? List.of()
				: List.copyOf(displayOverlays);
		for (TileKey key : visibleTiles) {
			if (cache.peekFresh(key, now) != null) {
				continue;
			}
			missingTiles++;
			if (requestedTiles >= MAX_TILE_REQUESTS_PER_FRAME) {
				continue;
			}
			if (cache.requestTile(blueMap, world, levelMinY, levelMaxY, safeOverlays, key, tileBlocksPerPixel, onTileReady)) {
				requestedTiles++;
			}
		}

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int[] pixels = image.getRaster().getDataBuffer() instanceof DataBufferInt dataBuffer ? dataBuffer.getData() : null;
		int missing = 0;
		for (int screenY = 0; screenY < height; screenY++) {
			double worldZ = worldTop + (screenY + 0.5D) * safeBlocksPerPixel;
			for (int screenX = 0; screenX < width; screenX++) {
				double worldX = worldLeft + (screenX + 0.5D) * safeBlocksPerPixel;
				int rgb = cache.sample(worldX, worldZ, lod);
				if (rgb == MISSING_RGB) {
					missing++;
				}
				int argb = 0xFF000000 | rgb;
				int index = screenY * width + screenX;
				if (pixels != null) {
					pixels[index] = argb;
				} else {
					image.setRGB(screenX, screenY, argb);
				}
			}
		}
		String status = missingTiles > 0
				? "Загрузка карты: " + Math.min(missingTiles, MAX_TILE_REQUESTS_PER_FRAME) + "/" + visibleTiles.size()
				: missing < width * height ? "BlueMap top-down" : "Нет сгенерированных тайлов";
		boolean healthy = missing < width * height || missingTiles > 0;
		return new Frame(image, status, healthy);
	}

	static List<DisplayOverlay> captureDisplayOverlays(ServerLevel level, double centerX, double centerZ, int width, int height, double blocksPerPixel) {
		if (level == null || width <= 0 || height <= 0 || !Double.isFinite(blocksPerPixel) || blocksPerPixel <= 0.0D) {
			return List.of();
		}
		double safeBlocksPerPixel = Mth.clamp(blocksPerPixel, 1.0D / 512.0D, 4096.0D);
		if (safeBlocksPerPixel > 32.0D) {
			return List.of();
		}
		double halfWidth = width * safeBlocksPerPixel * 0.5D;
		double halfHeight = height * safeBlocksPerPixel * 0.5D;
		AABB box = new AABB(
				centerX - halfWidth - 8.0D,
				level.getMinY(),
				centerZ - halfHeight - 8.0D,
				centerX + halfWidth + 8.0D,
				level.getMaxY(),
				centerZ + halfHeight + 8.0D
		);
		List<DisplayOverlay> overlays = new ArrayList<>();
		for (Display.ItemDisplay display : level.getEntities(EntityType.ITEM_DISPLAY, box, MonitorYandexMapsBlueMapRenderer::isMapVisibleDisplay)) {
			DisplayOverlay overlay = displayOverlay(display);
			if (overlay != null) {
				overlays.add(overlay);
			}
		}
		return overlays.isEmpty() ? List.of() : List.copyOf(overlays);
	}

	static void clear(ResourceKey<Level> dimension) {
		synchronized (LOCK) {
			if (dimension == null) {
				CACHES.clear();
				bridgeError = null;
				nextBridgeRetryAtMs = 0L;
				lastBridgeLogKey = null;
			} else {
				CACHES.keySet().removeIf(key -> dimension.equals(key.dimension()));
			}
		}
	}

	private static BlueMapBridge bridge(MinecraftServer server) {
		BlueMapBridge ready = bridge;
		if (ready != null) {
			return ready;
		}
		long now = System.currentTimeMillis();
		if (bridgeError != null && now < nextBridgeRetryAtMs) {
			return null;
		}
		synchronized (LOCK) {
			if (bridge != null) {
				return bridge;
			}
			now = System.currentTimeMillis();
			if (bridgeError != null && now < nextBridgeRetryAtMs) {
				return null;
			}
			try {
				Path blueMapJar = resolveBlueMapJar(server);
				if (blueMapJar == null) {
					bridgeError = "BlueMap jar не найден";
					nextBridgeRetryAtMs = now + BRIDGE_RETRY_INTERVAL_MS;
					logBridgeFailure(bridgeError + ": положи bluemap-*.jar в server-assets или укажи -D" + BLUEMAP_JAR_PROPERTY + "=/path/to/bluemap.jar", null);
					return null;
				}
				bridge = BlueMapBridge.create(server, blueMapJar);
				bridgeError = null;
				nextBridgeRetryAtMs = 0L;
				return bridge;
			} catch (Exception exception) {
				bridgeError = "BlueMap init: " + exception.getClass().getSimpleName();
				nextBridgeRetryAtMs = now + BRIDGE_RETRY_INTERVAL_MS;
				logBridgeFailure("Failed to initialize isolated BlueMap renderer for monitor maps", exception);
				return null;
			}
		}
	}

	private static Path resolveBlueMapJar(MinecraftServer server) {
		List<Path> candidates = new ArrayList<>();
		addCandidate(candidates, System.getProperty(BLUEMAP_JAR_PROPERTY));
		addCandidate(candidates, System.getenv(BLUEMAP_JAR_ENV));
		if (server != null) {
			Path root = server.getServerDirectory();
			addMatchingJars(candidates, root.resolve("server-assets"));
			addMatchingJars(candidates, root.resolve("cache").resolve("lg2"));
			addMatchingJars(candidates, root.resolve("libs"));
			addMatchingJars(candidates, root.resolve("mods").resolve("lg2-0.1.0").resolve("libs"));
			addMatchingJars(candidates, root.resolve("mods"));
		}
		for (Path candidate : candidates) {
			if (candidate != null && Files.isRegularFile(candidate)) {
				return candidate.toAbsolutePath().normalize();
			}
		}
		return null;
	}

	private static void addCandidate(List<Path> candidates, String path) {
		if (path == null || path.isBlank()) {
			return;
		}
		addCandidate(candidates, Path.of(path));
	}

	private static void addCandidate(List<Path> candidates, Path path) {
		if (path != null) {
			candidates.add(path);
		}
	}

	private static void addMatchingJars(List<Path> candidates, Path directory) {
		if (directory == null || !Files.isDirectory(directory)) {
			return;
		}
		addMatchingJars(candidates, directory, "bluemap*.jar");
		addMatchingJars(candidates, directory, "BlueMap*.jar");
	}

	private static void addMatchingJars(List<Path> candidates, Path directory, String glob) {
		List<Path> matches = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, glob)) {
			for (Path path : stream) {
				if (path != null) {
					matches.add(path);
				}
			}
		} catch (IOException ignored) {
			return;
		}
		matches.sort(Comparator.<Path, String>comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER).reversed());
		candidates.addAll(matches);
	}

	private static void logBridgeFailure(String message, Exception exception) {
		String key = message + "|" + (exception == null ? "" : exception.getClass().getName() + ":" + exception.getMessage());
		if (key.equals(lastBridgeLogKey)) {
			return;
		}
		lastBridgeLogKey = key;
		if (exception == null) {
			Lg2.LOGGER.warn("Yandex maps renderer unavailable: {}", message);
		} else {
			Lg2.LOGGER.error(message, exception);
		}
	}

	private static DimensionTileCache cacheFor(WorldCacheKey key) {
		synchronized (LOCK) {
			return CACHES.computeIfAbsent(key, ignored -> new DimensionTileCache());
		}
	}

	private static int lodForBlocksPerPixel(double blocksPerPixel) {
		return Mth.clamp((int) Math.round(Math.log(blocksPerPixel) / Math.log(2.0D)), MIN_LOD, MAX_LOD);
	}

	private static double blocksPerPixelForLod(int lod) {
		return Math.pow(2.0D, Mth.clamp(lod, MIN_LOD, MAX_LOD));
	}

	private static double tileWorldSize(int lod) {
		return TILE_SIZE * blocksPerPixelForLod(lod);
	}

	private static long floorToLong(double value) {
		long floor = (long) value;
		return value < floor ? floor - 1L : floor;
	}

	private static List<TileKey> visibleTiles(
			int lod,
			double tileBlocksPerPixel,
			double worldLeft,
			double worldTop,
			int width,
			int height,
			double blocksPerPixel,
			double centerX,
			double centerZ
	) {
		double tileSize = TILE_SIZE * tileBlocksPerPixel;
		long minTileX = floorToLong(worldLeft / tileSize);
		long minTileZ = floorToLong(worldTop / tileSize);
		long maxTileX = floorToLong((worldLeft + width * blocksPerPixel) / tileSize);
		long maxTileZ = floorToLong((worldTop + height * blocksPerPixel) / tileSize);
		List<TileKey> keys = new ArrayList<>();
		for (long tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
			for (long tileX = minTileX; tileX <= maxTileX; tileX++) {
				keys.add(new TileKey(lod, tileX, tileZ));
			}
		}
		keys.sort(Comparator.comparingDouble(key -> {
			double tileCenterX = (key.tileX() + 0.5D) * tileSize;
			double tileCenterZ = (key.tileZ() + 0.5D) * tileSize;
			double dx = tileCenterX - centerX;
			double dz = tileCenterZ - centerZ;
			return dx * dx + dz * dz;
		}));
		return keys;
	}

	record Frame(BufferedImage image, String status, boolean healthy) {
		static Frame failure(BufferedImage image, String status) {
			return new Frame(image, status, false);
		}
	}

	private record WorldCacheKey(Path root, ResourceKey<Level> dimension) {
	}

	private record TileKey(int lod, long tileX, long tileZ) {
	}

	private record TileImage(int[] pixels, long renderedAt) {
		int sample(int x, int y) {
			if (this.pixels == null || this.pixels.length == 0) {
				return MISSING_RGB;
			}
			int safeX = Mth.clamp(x, 0, TILE_SIZE - 1);
			int safeY = Mth.clamp(y, 0, TILE_SIZE - 1);
			return this.pixels[safeY * TILE_SIZE + safeX] & 0xFFFFFF;
		}
	}

	private static final class DimensionTileCache {
		private final LinkedHashMap<TileKey, TileImage> tiles = new LinkedHashMap<>(256, 0.75F, true);
		private final Map<TileKey, TileImage> tileLookup = new ConcurrentHashMap<>();
		private final Set<TileKey> inFlight = ConcurrentHashMap.newKeySet();

		private boolean requestTile(
				BlueMapBridge blueMap,
				Object world,
				int levelMinY,
				int levelMaxY,
				List<DisplayOverlay> displayOverlays,
				TileKey key,
				double tileBlocksPerPixel,
				Runnable onTileReady
		) {
			long now = System.currentTimeMillis();
			synchronized (LOCK) {
				TileImage cached = this.tiles.get(key);
				if (cached != null && now - cached.renderedAt() <= TILE_TTL_MS) {
					return false;
				}
			}
			if (!this.inFlight.add(key)) {
				return false;
			}
			List<DisplayOverlay> overlaysSnapshot = displayOverlays == null || displayOverlays.isEmpty()
					? List.of()
					: List.copyOf(displayOverlays);
			TILE_RENDER_EXECUTOR.execute(() -> {
				try {
					TileImage rendered = renderTile(blueMap, world, levelMinY, levelMaxY, overlaysSnapshot, key, tileBlocksPerPixel, System.currentTimeMillis());
					synchronized (LOCK) {
						this.tiles.put(key, rendered);
						this.tileLookup.put(key, rendered);
						trimToBudget();
					}
				} catch (Throwable throwable) {
					Lg2.LOGGER.warn("BlueMap monitor async tile render failed at lod {} {},{}", key.lod(), key.tileX(), key.tileZ(), throwable);
				} finally {
					this.inFlight.remove(key);
					if (onTileReady != null) {
						try {
							onTileReady.run();
						} catch (RuntimeException ignored) {
							// The render cache is already populated; the next regular refresh will pick it up.
						}
					}
				}
			});
			return true;
		}

		private TileImage peekFresh(TileKey key, long now) {
			synchronized (LOCK) {
				TileImage cached = this.tiles.get(key);
				if (cached != null && now - cached.renderedAt() <= TILE_TTL_MS) {
					return cached;
				}
			}
			return null;
		}

		private int sample(double worldX, double worldZ, int lod) {
			int exact = sampleAtLod(worldX, worldZ, lod);
			if (exact != MISSING_RGB) {
				return exact;
			}
			for (int offset = 1; offset <= 6; offset++) {
				int coarser = sampleAtLod(worldX, worldZ, lod + offset);
				if (coarser != MISSING_RGB) {
					return coarser;
				}
				int finer = sampleAtLod(worldX, worldZ, lod - offset);
				if (finer != MISSING_RGB) {
					return finer;
				}
			}
			return MISSING_RGB;
		}

		private int sampleAtLod(double worldX, double worldZ, int lod) {
			if (lod < MIN_LOD || lod > MAX_LOD) {
				return MISSING_RGB;
			}
			TileKey key = tileKeyAt(lod, worldX, worldZ);
			TileImage image = this.tileLookup.get(key);
			if (image == null) {
				return MISSING_RGB;
			}
			double tileSize = tileWorldSize(lod);
			double tileBlocksPerPixel = blocksPerPixelForLod(lod);
			int localX = Mth.floor((worldX - key.tileX() * tileSize) / tileBlocksPerPixel);
			int localZ = Mth.floor((worldZ - key.tileZ() * tileSize) / tileBlocksPerPixel);
			return image.sample(localX, localZ);
		}

		private TileKey tileKeyAt(int lod, double worldX, double worldZ) {
			double size = tileWorldSize(lod);
			return new TileKey(lod, floorToLong(worldX / size), floorToLong(worldZ / size));
		}

		private void trimToBudget() {
			while (this.tiles.size() > MAX_CACHED_TILES_PER_DIMENSION) {
				Iterator<Map.Entry<TileKey, TileImage>> iterator = this.tiles.entrySet().iterator();
				if (!iterator.hasNext()) {
					return;
				}
				Map.Entry<TileKey, TileImage> eldest = iterator.next();
				iterator.remove();
				this.tileLookup.remove(eldest.getKey(), eldest.getValue());
			}
		}

		private TileImage renderTile(BlueMapBridge blueMap, Object world, int levelMinY, int levelMaxY, List<DisplayOverlay> displayOverlays, TileKey key, double tileBlocksPerPixel, long now) {
			int[] pixels = new int[TILE_SIZE * TILE_SIZE];
			for (int i = 0; i < pixels.length; i++) {
				pixels[i] = MISSING_RGB;
			}
			double tileSize = TILE_SIZE * tileBlocksPerPixel;
			double worldLeft = key.tileX() * tileSize;
			double worldTop = key.tileZ() * tileSize;
			try {
				renderSurfaceTile(blueMap, world, levelMinY, levelMaxY, displayOverlays, worldLeft, worldTop, tileBlocksPerPixel, pixels);
			} catch (Exception exception) {
				Lg2.LOGGER.warn("BlueMap monitor tile render failed at lod {} {},{}", key.lod(), key.tileX(), key.tileZ(), exception);
			}
			return new TileImage(pixels, now);
		}

		private void renderSurfaceTile(BlueMapBridge blueMap, Object world, int levelMinY, int levelMaxY, List<DisplayOverlay> displayOverlays, double worldLeft, double worldTop, double tileBlocksPerPixel, int[] pixels) throws ReflectiveOperationException {
			Map<Long, Object> chunkCache = new HashMap<>();
			Map<Long, List<SurfaceLayer>> columnCache = new HashMap<>();
			for (int y = 0; y < TILE_SIZE; y++) {
				double sampleZ = worldTop + (y + 0.5D) * tileBlocksPerPixel;
				int blockZ = Mth.floor(sampleZ);
				for (int x = 0; x < TILE_SIZE; x++) {
					double sampleX = worldLeft + (x + 0.5D) * tileBlocksPerPixel;
					int blockX = Mth.floor(sampleX);
					int index = y * TILE_SIZE + x;
					long columnKey = (((long) blockX) << 32) ^ (blockZ & 0xFFFFFFFFL);
					List<SurfaceLayer> layers = columnCache.get(columnKey);
					if (layers == null) {
						layers = blueMap.layersAt(world, chunkCache, blockX, blockZ, levelMinY, levelMaxY);
						columnCache.put(columnKey, layers);
					}
					if (layers != null && !layers.isEmpty()) {
						double fracX = sampleX - Math.floor(sampleX);
						double fracZ = sampleZ - Math.floor(sampleZ);
						pixels[index] = colorForLayers(layers, blockX, blockZ, fracX, fracZ, tileBlocksPerPixel);
					}
				}
			}
			renderDisplayOverlays(displayOverlays, worldLeft, worldTop, tileBlocksPerPixel, pixels);
		}
	}

	private static void renderDisplayOverlays(List<DisplayOverlay> displayOverlays, double worldLeft, double worldTop, double tileBlocksPerPixel, int[] pixels) {
		if (displayOverlays == null || displayOverlays.isEmpty() || pixels == null || pixels.length == 0) {
			return;
		}
		for (DisplayOverlay overlay : displayOverlays) {
			drawDisplayModelOverlay(overlay, worldLeft, worldTop, tileBlocksPerPixel, pixels);
		}
	}

	private static boolean isMapVisibleDisplay(Display.ItemDisplay display) {
		if (display == null || !display.isAlive()) {
			return false;
		}
		Set<String> tags = display.getTags();
		return tags.contains(MONITOR_DISPLAY_TAG)
				|| tags.contains(EXIT_SIGN_DISPLAY_TAG)
				|| tags.contains(SERVER_DISPLAY_TAG);
	}

	private static DisplayOverlay displayOverlay(Display.ItemDisplay display) {
		Set<String> tags = display.getTags();
		if (tags.contains(SERVER_DISPLAY_TAG)) {
			double width = ServerStructureBreakSystem.STRUCTURE_HALF_WIDTH * 2.0D + 1.0D;
			double depth = ServerStructureBreakSystem.STRUCTURE_HALF_DEPTH * 2.0D + 1.0D;
			return new DisplayOverlay(display.getX(), display.getZ(), width, depth, Math.toRadians(display.getYRot()), SERVER_DISPLAY_MODEL);
		}
		if (tags.contains(EXIT_SIGN_DISPLAY_TAG)) {
			return new DisplayOverlay(display.getX(), display.getZ(), 1.15D, 0.20D, Math.toRadians(display.getYRot()), EXIT_SIGN_DISPLAY_MODEL);
		}
		if (tags.contains(MONITOR_DISPLAY_TAG)) {
			return new DisplayOverlay(display.getX(), display.getZ(), 1.15D, 0.16D, Math.toRadians(display.getYRot()), MONITOR_DISPLAY_MODEL);
		}
		return null;
	}

	private static void drawDisplayModelOverlay(DisplayOverlay overlay, double worldLeft, double worldTop, double tileBlocksPerPixel, int[] pixels) {
		OverlayBounds bounds = overlayBounds(overlay);
		int minX = Mth.clamp(Mth.floor((bounds.minX() - worldLeft) / tileBlocksPerPixel) - 1, 0, TILE_SIZE - 1);
		int maxX = Mth.clamp(Mth.ceil((bounds.maxX() - worldLeft) / tileBlocksPerPixel) + 1, 0, TILE_SIZE - 1);
		int minY = Mth.clamp(Mth.floor((bounds.minZ() - worldTop) / tileBlocksPerPixel) - 1, 0, TILE_SIZE - 1);
		int maxY = Mth.clamp(Mth.ceil((bounds.maxZ() - worldTop) / tileBlocksPerPixel) + 1, 0, TILE_SIZE - 1);
		double cos = Math.cos(-overlay.yawRadians());
		double sin = Math.sin(-overlay.yawRadians());
		for (int y = minY; y <= maxY; y++) {
			double worldZ = worldTop + (y + 0.5D) * tileBlocksPerPixel;
			for (int x = minX; x <= maxX; x++) {
				double worldX = worldLeft + (x + 0.5D) * tileBlocksPerPixel;
				double dx = worldX - overlay.centerX();
				double dz = worldZ - overlay.centerZ();
				double localX = dx * cos - dz * sin;
				double localZ = dx * sin + dz * cos;
				double u = localX / overlay.widthBlocks() + 0.5D;
				double v = localZ / overlay.depthBlocks() + 0.5D;
				if (u < 0.0D || u > 1.0D || v < 0.0D || v > 1.0D) {
					continue;
				}
				int foreground = sampleDisplayModelCoverage(
						overlay,
						localX,
						localZ,
						tileBlocksPerPixel,
						cos,
						sin
				);
				if (((foreground >>> 24) & 0xFF) <= 8) {
					continue;
				}
				int index = y * TILE_SIZE + x;
				pixels[index] = alphaOver(foreground, 0xFF000000 | pixels[index]) & 0xFFFFFF;
			}
		}
	}

	private static OverlayBounds overlayBounds(DisplayOverlay overlay) {
		double halfWidth = overlay.widthBlocks() * 0.5D;
		double halfDepth = overlay.depthBlocks() * 0.5D;
		double cos = Math.cos(overlay.yawRadians());
		double sin = Math.sin(overlay.yawRadians());
		double minX = Double.POSITIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;
		for (double localX : new double[]{-halfWidth, halfWidth}) {
			for (double localZ : new double[]{-halfDepth, halfDepth}) {
				double worldX = overlay.centerX() + localX * cos - localZ * sin;
				double worldZ = overlay.centerZ() + localX * sin + localZ * cos;
				minX = Math.min(minX, worldX);
				minZ = Math.min(minZ, worldZ);
				maxX = Math.max(maxX, worldX);
				maxZ = Math.max(maxZ, worldZ);
			}
		}
		return new OverlayBounds(minX, minZ, maxX, maxZ);
	}

	private static int sampleDisplayModel(DisplayOverlay overlay, double u, double v) {
		BlockTextureRaycaster.BlockTraceResult result = BlockTextureRaycaster.traceModelTopDownNormalized(
				overlay.modelId(),
				u,
				v,
				NO_TINTS
		);
		if (result == null) {
			return 0;
		}
		return shadeByFace(result.argb(), result.face(), result.shade());
	}

	private static int sampleDisplayModelCoverage(
			DisplayOverlay overlay,
			double localX,
			double localZ,
			double blocksPerPixel,
			double cos,
			double sin
	) {
		long alpha = 0L;
		long red = 0L;
		long green = 0L;
		long blue = 0L;
		int samples = 0;
		for (int sampleZ = -1; sampleZ <= 1; sampleZ++) {
			for (int sampleX = -1; sampleX <= 1; sampleX++) {
				double worldOffsetX = sampleX * blocksPerPixel * 0.5D;
				double worldOffsetZ = sampleZ * blocksPerPixel * 0.5D;
				double sampleLocalX = localX + worldOffsetX * cos - worldOffsetZ * sin;
				double sampleLocalZ = localZ + worldOffsetX * sin + worldOffsetZ * cos;
				double u = sampleLocalX / overlay.widthBlocks() + 0.5D;
				double v = sampleLocalZ / overlay.depthBlocks() + 0.5D;
				if (u < 0.0D || u > 1.0D || v < 0.0D || v > 1.0D) {
					samples++;
					continue;
				}
				int color = sampleDisplayModel(overlay, u, v);
				int sampleAlpha = (color >>> 24) & 0xFF;
				alpha += sampleAlpha;
				red += (long) ((color >>> 16) & 0xFF) * sampleAlpha;
				green += (long) ((color >>> 8) & 0xFF) * sampleAlpha;
				blue += (long) (color & 0xFF) * sampleAlpha;
				samples++;
			}
		}
		if (alpha == 0L || samples == 0) {
			return 0;
		}
		int coverageAlpha = (int) (alpha / samples);
		return coverageAlpha << 24
				| (int) (red / alpha) << 16
				| (int) (green / alpha) << 8
				| (int) (blue / alpha);
	}

	private static int colorForLayers(List<SurfaceLayer> layers, int blockX, int blockZ, double fracX, double fracZ, double blocksPerPixel) {
		if (layers == null || layers.isEmpty()) {
			return MISSING_RGB;
		}
		int out = 0;
		for (int i = layers.size() - 1; i >= 0; i--) {
			SurfaceLayer layer = layers.get(i);
			int argb = argbForLayer(layer, blockX, blockZ, fracX, fracZ, blocksPerPixel);
			out = alphaOver(argb, out);
		}
		int alpha = (out >>> 24) & 0xFF;
		if (alpha <= 0) {
			return MISSING_RGB;
		}
		return out & 0xFFFFFF;
	}

	private static int argbForLayer(SurfaceLayer layer, int blockX, int blockZ, double fracX, double fracZ, double blocksPerPixel) {
		BlockState state = layer.state();
		if (state == null || state.isAir()) {
			return 0;
		}
		if (blocksPerPixel > 1.0D) {
			if (usesSinglePointLodSample(state)) {
				return STATE_ARGB_CACHE.computeIfAbsent(layer.cacheKey() + "|solid-top", ignored -> sampleStateArgb(state, blockX, layer.y(), blockZ, 0.5D, 0.5D, blocksPerPixel));
			}
			return STATE_ARGB_CACHE.computeIfAbsent(layer.cacheKey() + "|coverage-top:" + coverageSamplesPerAxis(blocksPerPixel), ignored -> sampleCoveredStateArgb(state, blockX, layer.y(), blockZ, blocksPerPixel));
		}
		return sampleStateArgb(state, blockX, layer.y(), blockZ, fracX, fracZ, blocksPerPixel);
	}

	private static boolean usesSinglePointLodSample(BlockState state) {
		return state != null
				&& state.canOcclude()
				&& stopsSurfaceScan(state)
				&& state.getBlock() != Blocks.WATER
				&& state.getBlock() != Blocks.LAVA
				&& !(state.getBlock() instanceof LeavesBlock);
	}

	private static int sampleCoveredStateArgb(BlockState state, int blockX, int blockY, int blockZ, double blocksPerPixel) {
		int samplesPerAxis = coverageSamplesPerAxis(blocksPerPixel);
		int totalAlpha = 0;
		int totalRed = 0;
		int totalGreen = 0;
		int totalBlue = 0;
		int geometryHits = 0;
		for (int sampleZ = 0; sampleZ < samplesPerAxis; sampleZ++) {
			double fracZ = (sampleZ + 0.5D) / samplesPerAxis;
			for (int sampleX = 0; sampleX < samplesPerAxis; sampleX++) {
				double fracX = (sampleX + 0.5D) / samplesPerAxis;
				int argb = sampleStateArgb(state, blockX, blockY, blockZ, fracX, fracZ, 1.0D / samplesPerAxis);
				if (hitsTopDownGeometry(state, blockX, blockY, blockZ, fracX, fracZ)) {
					geometryHits++;
				}
				int alpha = (argb >>> 24) & 0xFF;
				totalAlpha += alpha;
				totalRed += ((argb >> 16) & 0xFF) * alpha;
				totalGreen += ((argb >> 8) & 0xFF) * alpha;
				totalBlue += (argb & 0xFF) * alpha;
			}
		}
		int sampleCount = samplesPerAxis * samplesPerAxis;
		if (sampleCount <= 0 || totalAlpha <= 0) {
			return 0;
		}
		int alpha = Mth.clamp(totalAlpha / sampleCount, 0, 255);
		int geometryCoverage = geometryHits * 255 / sampleCount;
		if (alpha > 0 && geometryCoverage >= 220 && !usesSinglePointLodSample(state)) {
			alpha = Math.max(alpha, 180);
		}
		int red = Mth.clamp(totalRed / totalAlpha, 0, 255);
		int green = Mth.clamp(totalGreen / totalAlpha, 0, 255);
		int blue = Mth.clamp(totalBlue / totalAlpha, 0, 255);
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static boolean hitsTopDownGeometry(BlockState state, int blockX, int blockY, int blockZ, double fracX, double fracZ) {
		if (state == null || state.isAir() || !BlockTextureRaycaster.hasResolvableModel(state)) {
			return false;
		}
		return BlockTextureRaycaster.hitsTopDownGeometry(
				state,
				new BlockPos(blockX, blockY, blockZ),
				new Vec3(blockX + Mth.clamp(fracX, 0.0D, 0.999D), blockY + 1.999D, blockZ + Mth.clamp(fracZ, 0.0D, 0.999D)),
				new Vec3(0.0D, -1.0D, 0.0D)
		);
	}

	private static int coverageSamplesPerAxis(double blocksPerPixel) {
		if (blocksPerPixel <= 2.0D) {
			return 12;
		}
		if (blocksPerPixel <= 8.0D) {
			return 8;
		}
		return 6;
	}

	private static int sampleStateArgb(BlockState state, int blockX, int blockY, int blockZ, double fracX, double fracZ, double blocksPerPixel) {
		int fluidArgb = fluidArgb(state, fracX, fracZ);
		if (fluidArgb != 0) {
			return fluidArgb;
		}
		int[] tintColors = defaultTints(state);
		BlockTextureRaycaster.BlockTraceResult result = BlockTextureRaycaster.traceTopDown(
				state,
				new BlockPos(blockX, blockY, blockZ),
				new Vec3(blockX + Mth.clamp(fracX, 0.0D, 0.999D), blockY + 1.999D, blockZ + Mth.clamp(fracZ, 0.0D, 0.999D)),
				new Vec3(0.0D, -1.0D, 0.0D),
				tintColors
		);
		if (result != null) {
			return shadeByFace(result.argb(), result.face(), result.shade());
		}
		if (!state.canOcclude() || !stopsSurfaceScan(state)) {
			return 0;
		}
		return 0xFF000000 | fallbackMapColor(state);
	}

	private static int fluidArgb(BlockState state, double fracX, double fracZ) {
		if (state == null || state.isAir()) {
			return 0;
		}
		Block block = state.getBlock();
		if (block == Blocks.WATER || block == Blocks.BUBBLE_COLUMN) {
			return sampleFluidTexture(isFlowingFluid(state) ? WATER_FLOW_TEXTURE : WATER_STILL_TEXTURE, fracX, fracZ, WATER_TINT_RGB, 172);
		}
		if (block == Blocks.LAVA) {
			return sampleFluidTexture(isFlowingFluid(state) ? LAVA_FLOW_TEXTURE : LAVA_STILL_TEXTURE, fracX, fracZ, 0xFFFFFF, 238);
		}
		return 0;
	}

	private static boolean isFlowingFluid(BlockState state) {
		return state != null
				&& state.hasProperty(LiquidBlock.LEVEL)
				&& state.getValue(LiquidBlock.LEVEL) > 0;
	}

	private static int sampleFluidTexture(Identifier textureId, double fracX, double fracZ, int tintRgb, int alpha) {
		BufferedImage texture = ASSETS.loadTexture(textureId);
		if (texture == null || texture.getWidth() <= 0 || texture.getHeight() <= 0) {
			return (Mth.clamp(alpha, 0, 255) << 24) | (tintRgb & 0xFFFFFF);
		}
		int x = Mth.clamp((int) Math.floor(Math.floorMod((int) Math.floor(fracX * texture.getWidth()), texture.getWidth())), 0, texture.getWidth() - 1);
		int y = Mth.clamp((int) Math.floor(Math.floorMod((int) Math.floor(fracZ * texture.getHeight()), texture.getHeight())), 0, texture.getHeight() - 1);
		int argb = texture.getRGB(x, y);
		int tinted = multiplyTint(argb, tintRgb);
		int textureAlpha = (tinted >>> 24) & 0xFF;
		int outAlpha = textureAlpha * Mth.clamp(alpha, 0, 255) / 255;
		return (outAlpha << 24) | (tinted & 0xFFFFFF);
	}

	private static int multiplyTint(int argb, int tintRgb) {
		int alpha = (argb >>> 24) & 0xFF;
		int red = ((argb >> 16) & 0xFF) * ((tintRgb >> 16) & 0xFF) / 255;
		int green = ((argb >> 8) & 0xFF) * ((tintRgb >> 8) & 0xFF) / 255;
		int blue = (argb & 0xFF) * (tintRgb & 0xFF) / 255;
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	private static int[] defaultTints(BlockState state) {
		if (state == null) {
			return NO_TINTS;
		}
		Block block = state.getBlock();
		int[] tintLayers = null;
		if (block == Blocks.GRASS_BLOCK
				|| block == Blocks.FERN
				|| block == Blocks.SHORT_GRASS
				|| block == Blocks.POTTED_FERN
				|| block == Blocks.BUSH
				|| block == Blocks.SUGAR_CANE
				|| block == Blocks.LARGE_FERN
				|| block == Blocks.TALL_GRASS) {
			tintLayers = tintArray();
			tintLayers[0] = GRASS_TINT_RGB;
			return tintLayers;
		}
		if (block == Blocks.PINK_PETALS || block == Blocks.WILDFLOWERS) {
			tintLayers = tintArray();
			tintLayers[1] = GRASS_TINT_RGB;
			return tintLayers;
		}
		if (block == Blocks.WATER || block == Blocks.BUBBLE_COLUMN || block == Blocks.WATER_CAULDRON) {
			tintLayers = tintArray();
			tintLayers[0] = WATER_TINT_RGB;
			return tintLayers;
		}
		if (block == Blocks.REDSTONE_WIRE) {
			tintLayers = tintArray();
			tintLayers[0] = RedStoneWireBlock.getColorForPower(state.getValue(RedStoneWireBlock.POWER));
			return tintLayers;
		}
		if (block == Blocks.ATTACHED_MELON_STEM || block == Blocks.ATTACHED_PUMPKIN_STEM) {
			tintLayers = tintArray();
			tintLayers[0] = ATTACHED_STEM_RGB;
			return tintLayers;
		}
		if (block == Blocks.MELON_STEM || block == Blocks.PUMPKIN_STEM) {
			int age = state.getValue(StemBlock.AGE);
			tintLayers = tintArray();
			tintLayers[0] = ((age * 32) << 16) | ((255 - age * 8) << 8) | (age * 4);
			return tintLayers;
		}
		if (block == Blocks.LILY_PAD) {
			tintLayers = tintArray();
			tintLayers[0] = LILY_PAD_RGB;
			return tintLayers;
		}
		if (block == Blocks.LEAF_LITTER) {
			tintLayers = tintArray();
			tintLayers[0] = DRY_FOLIAGE_TINT_RGB;
			return tintLayers;
		}
		if (block == Blocks.SPRUCE_LEAVES) {
			tintLayers = tintArray();
			tintLayers[0] = 0x619961;
			return tintLayers;
		}
		if (block == Blocks.BIRCH_LEAVES) {
			tintLayers = tintArray();
			tintLayers[0] = 0x80A755;
			return tintLayers;
		}
		if (block == Blocks.OAK_LEAVES
				|| block == Blocks.JUNGLE_LEAVES
				|| block == Blocks.ACACIA_LEAVES
				|| block == Blocks.DARK_OAK_LEAVES
				|| block == Blocks.VINE
				|| block == Blocks.MANGROVE_LEAVES
				|| block instanceof LeavesBlock) {
			tintLayers = tintArray();
			tintLayers[0] = FOLIAGE_TINT_RGB;
			return tintLayers;
		}
		return NO_TINTS;
	}

	private static int[] tintArray() {
		int[] tintLayers = new int[4];
		Arrays.fill(tintLayers, NO_TINT);
		return tintLayers;
	}

	private static int shadeByFace(int argb, net.minecraft.core.Direction face, boolean shade) {
		if (!shade || face == null) {
			return argb;
		}
		double factor = switch (face) {
			case DOWN -> 0.50D;
			case NORTH, SOUTH -> 0.80D;
			case WEST, EAST -> 0.60D;
			default -> 1.0D;
		};
		int alpha = (argb >>> 24) & 0xFF;
		return (alpha << 24) | multiplyRgb(argb, factor);
	}

	private static int alphaOver(int foreground, int background) {
		int fa = (foreground >>> 24) & 0xFF;
		if (fa <= 0) {
			return background;
		}
		if (fa >= 255) {
			return foreground;
		}
		int ba = (background >>> 24) & 0xFF;
		int outA = fa + ba * (255 - fa) / 255;
		if (outA <= 0) {
			return 0;
		}
		int fr = (foreground >> 16) & 0xFF;
		int fg = (foreground >> 8) & 0xFF;
		int fb = foreground & 0xFF;
		int br = (background >> 16) & 0xFF;
		int bg = (background >> 8) & 0xFF;
		int bb = background & 0xFF;
		int outR = (fr * fa + br * ba * (255 - fa) / 255) / outA;
		int outG = (fg * fa + bg * ba * (255 - fa) / 255) / outA;
		int outB = (fb * fa + bb * ba * (255 - fa) / 255) / outA;
		return (outA << 24) | (outR << 16) | (outG << 8) | outB;
	}

	private static int fallbackMapColor(BlockState state) {
		try {
			MapColor color = state.getMapColor(null, BlockPos.ZERO);
			if (color != null && color != MapColor.NONE) {
				return color.calculateARGBColor(MapColor.Brightness.NORMAL) & 0xFFFFFF;
			}
		} catch (RuntimeException ignored) {
			// Some dynamic map-color providers expect a real level; keep the map responsive.
		}
		Block block = state.getBlock();
		if (block == Blocks.WATER || block == Blocks.BUBBLE_COLUMN) {
			return WATER_TINT_RGB;
		}
		if (block == Blocks.GRASS_BLOCK) {
			return GRASS_TINT_RGB;
		}
		if (block == Blocks.FERN
				|| block == Blocks.SHORT_GRASS
				|| block == Blocks.TALL_GRASS
				|| block == Blocks.LARGE_FERN
				|| block == Blocks.BUSH
				|| block == Blocks.SUGAR_CANE
				|| block == Blocks.PINK_PETALS
				|| block == Blocks.WILDFLOWERS) {
			return GRASS_TINT_RGB;
		}
		return 0x77746A;
	}

	private static boolean stopsSurfaceScan(BlockState state) {
		if (state == null || state.isAir()) {
			return false;
		}
		if (!state.canOcclude() || state.getBlock() instanceof LeavesBlock) {
			return false;
		}
		return hasFullBlockProjection(state);
	}

	private static boolean hasFullBlockProjection(BlockState state) {
		if (state == null || state.isAir()) {
			return false;
		}
		try {
			VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			return Block.isShapeFullBlock(shape);
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	private static boolean hasStateProperty(BlockState state, String propertyName) {
		return state != null
				&& propertyName != null
				&& state.getBlock().getStateDefinition().getProperty(propertyName) != null;
	}

	private static int multiplyRgb(int rgb, double factor) {
		int red = Mth.clamp((int) Math.round(((rgb >> 16) & 0xFF) * factor), 0, 255);
		int green = Mth.clamp((int) Math.round(((rgb >> 8) & 0xFF) * factor), 0, 255);
		int blue = Mth.clamp((int) Math.round((rgb & 0xFF) * factor), 0, 255);
		return (red << 16) | (green << 8) | blue;
	}

	private static BlockState vanillaStateFor(String cacheKey, String idString, Map<String, String> properties) {
		if (cacheKey == null || idString == null) {
			return Blocks.AIR.defaultBlockState();
		}
		return VANILLA_STATE_CACHE.computeIfAbsent(cacheKey, ignored -> buildVanillaState(idString, properties));
	}

	private static BlockState buildVanillaState(String idString, Map<String, String> properties) {
		Identifier id = Identifier.tryParse(idString);
		if (id == null) {
			return Blocks.AIR.defaultBlockState();
		}
		Block block = BuiltInRegistries.BLOCK.getValue(id);
		if (block == null) {
			return Blocks.AIR.defaultBlockState();
		}
		BlockState state = block.defaultBlockState();
		if (properties == null || properties.isEmpty()) {
			return state;
		}
		for (Map.Entry<String, String> entry : properties.entrySet()) {
			Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
			if (property != null) {
				state = setPropertyValue(state, property, entry.getValue());
			}
		}
		return state;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static BlockState setPropertyValue(BlockState state, Property property, String value) {
		Optional<? extends Comparable> parsed = property.getValue(value);
		if (parsed.isEmpty()) {
			return state;
		}
		return state.setValue(property, parsed.get());
	}

	private record SurfaceLayer(BlockState state, int y, String cacheKey) {
	}

	record DisplayOverlay(double centerX, double centerZ, double widthBlocks, double depthBlocks, double yawRadians, Identifier modelId) {
	}

	private record OverlayBounds(double minX, double minZ, double maxX, double maxZ) {
	}

	private record RegionIndex(Set<Long> regions, long loadedAt) {
	}

	private static final class BlueMapBridge {
		private final URLClassLoader classLoader;
		private final Class<?> packVersionClass;
		private final Class<?> dataPackClass;
		private final Class<?> keyClass;
		private final Class<?> mcaWorldClass;
		private final Class<?> worldClass;
		private final Class<?> chunkClass;
		private final Class<?> blueBlockStateClass;
		private final Method dataPackLoadResources;
		private final Method dataPackBake;
		private final Method keyParse;
		private final Method keyGetFormatted;
		private final Method mcaWorldLoad;
		private final Method worldGetChunkAtBlock;
		private final Method worldListRegions;
		private final Method chunkIsGenerated;
		private final Method chunkGetBlockState;
		private final Method chunkGetMaxY;
		private final Method chunkGetMinY;
		private final Method chunkHasWorldSurfaceHeights;
		private final Method chunkGetWorldSurfaceY;
		private final Method blueBlockStateGetId;
		private final Method blueBlockStateGetProperties;
		private final Method blueBlockStateIsAir;
		private final Object dataPack;
		private final Map<WorldCacheKey, Object> worlds = new LinkedHashMap<>();
		private final Map<Object, RegionIndex> generatedRegions = new LinkedHashMap<>();
		private String lastWorldError;

		private BlueMapBridge(MinecraftServer server, Path blueMapJar) throws Exception {
			if (blueMapJar == null || !Files.isRegularFile(blueMapJar)) {
				throw new IOException("BlueMap jar not found: " + blueMapJar);
			}
			this.classLoader = new URLClassLoader(new URL[]{blueMapJar.toUri().toURL()}, MonitorYandexMapsBlueMapRenderer.class.getClassLoader());
			this.packVersionClass = load("de.bluecolored.bluemap.core.resources.pack.PackVersion");
			this.dataPackClass = load("de.bluecolored.bluemap.core.resources.pack.datapack.DataPack");
			this.keyClass = load("de.bluecolored.bluemap.core.util.Key");
			this.mcaWorldClass = load("de.bluecolored.bluemap.core.world.mca.MCAWorld");
			this.worldClass = load("de.bluecolored.bluemap.core.world.World");
			this.chunkClass = load("de.bluecolored.bluemap.core.world.Chunk");
			this.blueBlockStateClass = load("de.bluecolored.bluemap.core.world.BlockState");

			this.dataPackLoadResources = this.dataPackClass.getMethod("loadResources", Iterable.class);
			this.dataPackBake = this.dataPackClass.getMethod("bake");
			this.keyParse = this.keyClass.getMethod("parse", String.class);
			this.keyGetFormatted = this.keyClass.getMethod("getFormatted");
			this.mcaWorldLoad = this.mcaWorldClass.getMethod("load", Path.class, this.keyClass, this.dataPackClass);
			this.worldGetChunkAtBlock = this.worldClass.getMethod("getChunkAtBlock", int.class, int.class);
			this.worldListRegions = this.worldClass.getMethod("listRegions");
			this.chunkIsGenerated = this.chunkClass.getMethod("isGenerated");
			this.chunkGetBlockState = this.chunkClass.getMethod("getBlockState", int.class, int.class, int.class);
			this.chunkGetMaxY = this.chunkClass.getMethod("getMaxY", int.class, int.class);
			this.chunkGetMinY = this.chunkClass.getMethod("getMinY", int.class, int.class);
			this.chunkHasWorldSurfaceHeights = this.chunkClass.getMethod("hasWorldSurfaceHeights");
			this.chunkGetWorldSurfaceY = this.chunkClass.getMethod("getWorldSurfaceY", int.class, int.class);
			this.blueBlockStateGetId = this.blueBlockStateClass.getMethod("getId");
			this.blueBlockStateGetProperties = this.blueBlockStateClass.getMethod("getProperties");
			this.blueBlockStateIsAir = this.blueBlockStateClass.getMethod("isAir");

			this.dataPack = this.dataPackClass.getConstructor(this.packVersionClass).newInstance(packVersion(94, 1));
			this.dataPackLoadResources.invoke(this.dataPack, dataPackRoots(server));
			this.dataPackBake.invoke(this.dataPack);
		}

		private static BlueMapBridge create(MinecraftServer server, Path blueMapJar) throws Exception {
			return new BlueMapBridge(server, blueMapJar);
		}

		private Class<?> load(String name) throws ClassNotFoundException {
			return Class.forName(name, true, this.classLoader);
		}

		private Object packVersion(int major, int minor) throws ReflectiveOperationException {
			return this.packVersionClass.getConstructor(int.class, int.class).newInstance(major, minor);
		}

		private Object world(MinecraftServer server, ServerLevel level) {
			WorldCacheKey key = new WorldCacheKey(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(), level.dimension());
			synchronized (LOCK) {
				Object cached = this.worlds.get(key);
				if (cached != null) {
					return cached;
				}
			}
			try {
				Object dimension = this.keyParse.invoke(null, level.dimension().identifier().toString());
				Object loaded = this.mcaWorldLoad.invoke(null, key.root(), dimension, this.dataPack);
				synchronized (LOCK) {
					this.worlds.put(key, loaded);
				}
				this.lastWorldError = null;
				return loaded;
			} catch (IllegalAccessException | InvocationTargetException exception) {
				Throwable cause = exception instanceof InvocationTargetException invocation && invocation.getCause() != null
						? invocation.getCause()
						: exception;
				this.lastWorldError = "MCAWorld: " + cause.getClass().getSimpleName();
				Lg2.LOGGER.error("Failed to load BlueMap MCA world for {}", level.dimension().identifier(), cause);
				return null;
			}
		}

		private List<SurfaceLayer> layersAt(Object world, Map<Long, Object> chunkCache, int blockX, int blockZ, int levelMinY, int levelMaxY) throws ReflectiveOperationException {
			if (!hasGeneratedRegion(world, blockX, blockZ)) {
				return List.of();
			}
			Object chunk = cachedChunk(world, chunkCache, blockX, blockZ);
			if (chunk == null || !((Boolean) this.chunkIsGenerated.invoke(chunk))) {
				return List.of();
			}
			int localX = blockX & 15;
			int localZ = blockZ & 15;
			int chunkMinY = ((Number) this.chunkGetMinY.invoke(chunk, blockX, blockZ)).intValue();
			int chunkMaxY = ((Number) this.chunkGetMaxY.invoke(chunk, blockX, blockZ)).intValue();
			int minY = Math.max(levelMinY, chunkMinY);
			int maxY = Math.min(levelMaxY, chunkMaxY);
			if (maxY < minY) {
				return List.of();
			}
			int startY = maxY;
			if ((Boolean) this.chunkHasWorldSurfaceHeights.invoke(chunk)) {
				int surfaceY = ((Number) this.chunkGetWorldSurfaceY.invoke(chunk, localX, localZ)).intValue();
				startY = Mth.clamp(surfaceY + ABOVE_SURFACE_SCAN_BLOCKS, minY, maxY);
			}
			return findLayersInRange(chunk, blockX, blockZ, startY, Math.max(minY, startY - MAX_SURFACE_SCAN_BLOCKS));
		}

		private boolean hasGeneratedRegion(Object world, int blockX, int blockZ) throws ReflectiveOperationException {
			Set<Long> regions = generatedRegions(world);
			if (regions.isEmpty()) {
				return false;
			}
			return regions.contains(regionKey(blockX >> 9, blockZ >> 9));
		}

		private Set<Long> generatedRegions(Object world) throws ReflectiveOperationException {
			long now = System.currentTimeMillis();
			synchronized (LOCK) {
				RegionIndex cached = this.generatedRegions.get(world);
				if (cached != null && now - cached.loadedAt() <= REGION_INDEX_TTL_MS) {
					return cached.regions();
				}
			}
			Object rawRegions = this.worldListRegions.invoke(world);
			Set<Long> regions = new HashSet<>();
			if (rawRegions instanceof Iterable<?> iterable) {
				for (Object rawRegion : iterable) {
					if (rawRegion instanceof Vector2i region) {
						regions.add(regionKey(region.getX(), region.getY()));
					}
				}
			}
			Set<Long> immutable = Set.copyOf(regions);
			synchronized (LOCK) {
				this.generatedRegions.put(world, new RegionIndex(immutable, now));
			}
			return immutable;
		}

		private static long regionKey(int regionX, int regionZ) {
			return (((long) regionX) << 32) ^ (regionZ & 0xFFFFFFFFL);
		}

		private Object cachedChunk(Object world, Map<Long, Object> chunkCache, int blockX, int blockZ) throws ReflectiveOperationException {
			long key = (((long) (blockX >> 4)) << 32) ^ ((blockZ >> 4) & 0xFFFFFFFFL);
			Object cached = chunkCache.get(key);
			if (cached != null) {
				return cached;
			}
			Object loaded = this.worldGetChunkAtBlock.invoke(world, blockX, blockZ);
			chunkCache.put(key, loaded);
			return loaded;
		}

		private List<SurfaceLayer> findLayersInRange(Object chunk, int blockX, int blockZ, int startY, int endY) throws ReflectiveOperationException {
			List<SurfaceLayer> layers = new ArrayList<>(4);
			for (int y = startY; y >= endY; y--) {
				Object blueState = this.chunkGetBlockState.invoke(chunk, blockX, y, blockZ);
				if (blueState == null || (Boolean) this.blueBlockStateIsAir.invoke(blueState)) {
					continue;
				}
				String cacheKey = blueState.toString();
				String id = blueBlockStateId(blueState);
				Map<String, String> properties = blueBlockStateProperties(blueState);
				BlockState state = vanillaStateFor(cacheKey, id, properties);
				if (state == null || state.isAir()) {
					continue;
				}
				layers.add(new SurfaceLayer(state, y, cacheKey));
				if (stopsSurfaceScan(state) || layers.size() >= MAX_COMPOSITE_LAYERS) {
					break;
				}
			}
			return layers;
		}

		private String blueBlockStateId(Object blueState) throws ReflectiveOperationException {
			Object key = this.blueBlockStateGetId.invoke(blueState);
			if (key == null) {
				return null;
			}
			Object formatted = this.keyGetFormatted.invoke(key);
			return formatted == null ? key.toString() : formatted.toString();
		}

		@SuppressWarnings("unchecked")
		private Map<String, String> blueBlockStateProperties(Object blueState) throws ReflectiveOperationException {
			Object properties = this.blueBlockStateGetProperties.invoke(blueState);
			if (properties instanceof Map<?, ?> rawMap) {
				Map<String, String> result = new HashMap<>();
				for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
					if (entry.getKey() != null && entry.getValue() != null) {
						result.put(entry.getKey().toString(), entry.getValue().toString());
					}
				}
				return result;
			}
			return Map.of();
		}

		private static List<Path> dataPackRoots(MinecraftServer server) {
			List<Path> roots = new ArrayList<>();
			addIfExists(roots, VANILLA_COMMON_JAR);
			addIfExists(roots, server.getWorldPath(LevelResource.DATAPACK_DIR));
			addIfExists(roots, server.getServerDirectory().resolve("mods").resolve("lg2-0.1.0").resolve("src").resolve("main").resolve("resources"));
			return roots;
		}

		private static void addIfExists(List<Path> roots, Path path) {
			if (path != null && Files.exists(path)) {
				roots.add(path.toAbsolutePath().normalize());
			}
		}
	}

	private static final class TileThreadFactory implements java.util.concurrent.ThreadFactory {
		private final AtomicInteger nextId = new AtomicInteger(1);

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "lg2-yandex-map-tile-" + this.nextId.getAndIncrement());
			thread.setDaemon(true);
			thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
			return thread;
		}
	}
}
