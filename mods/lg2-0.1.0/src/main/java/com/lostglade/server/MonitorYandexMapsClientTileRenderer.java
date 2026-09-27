package com.lostglade.server;

import com.lostglade.server.maprender.MapPyramidBuilder;
import com.lostglade.server.maprender.MapPyramidMetadata;
import com.lostglade.server.maprender.MapPyramidStore;
import com.lostglade.server.maprender.MapPyramidTileKey;
import com.lostglade.server.maprender.MapRenderInventory;
import com.lostglade.server.maprender.MapRenderWorkerRegistry;
import com.lostglade.server.maprender.MapRenderProfile;
import com.lostglade.server.maprender.MapRenderScheduler;
import com.lostglade.server.maprender.MapTileKey;
import com.lostglade.server.maprender.MapTileMetadata;
import com.lostglade.server.maprender.MapTileStore;
import com.lostglade.server.maprender.YandexMapRenderService;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compatibility compositor for the pre-rewrite Yandex Maps UI.
 *
 * <p>This class deliberately preserves only the small API that the old UI used.
 * It never renders Minecraft, loads chunks, owns LOD generation, or writes a
 * second tile format. Pixels come exclusively from the replacement v2 L0 store
 * and its CPU-derived pyramid.</p>
 */
final class MonitorYandexMapsClientTileRenderer {
    private static final String STORE_DIRECTORY = "lostglade/yandex_maps/v2";
    private static final int MIN_ZOOM_EXPONENT = -4;
    private static final int MAX_ZOOM_EXPONENT = MapPyramidBuilder.MAX_LEVEL;
    // Exact placeholder color used by the pre-rewrite Yandex map compositor.
    private static final int MISSING_RGB = 0x18242B;
    private static final int IMAGE_CACHE_LIMIT = 512;
    private static final Object IMAGE_CACHE_LOCK = new Object();
    private static final Map<String, BufferedImage> IMAGE_CACHE = new LinkedHashMap<>(128, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > IMAGE_CACHE_LIMIT;
        }
    };
    private static final Map<ScreenRuntimeKey, ViewRegistration> ACTIVE_VIEWS = new ConcurrentHashMap<>();
    private static volatile long lastPublishedRevision = Long.MIN_VALUE;

    private MonitorYandexMapsClientTileRenderer() {
    }

    static void configure(MinecraftServer server) {
        // The v2 renderer owns discovery/storage lifecycle. Kept for old UI API compatibility.
    }

    static void tick(MinecraftServer server) {
        if (server == null || Math.floorMod(server.getTickCount(), 10) != 0) return;
        long revision = uiRevision();
        if (revision == lastPublishedRevision) return;
        lastPublishedRevision = revision;
        for (ViewRegistration registration : List.copyOf(ACTIVE_VIEWS.values())) {
            if (registration != null && registration.onTileReady() != null) {
                registration.onTileReady().run();
            }
        }
    }

    static void deactivateView(ScreenRuntimeKey key) {
        if (key != null) ACTIVE_VIEWS.remove(key);
    }

    static void clear(ResourceKey<Level> dimension) {
        synchronized (IMAGE_CACHE_LOCK) {
            IMAGE_CACHE.clear();
        }
        ACTIVE_VIEWS.clear();
        lastPublishedRevision = Long.MIN_VALUE;
    }

    static Frame render(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            double centerX,
            double centerZ,
            int width,
            int height,
            double blocksPerPixel,
            Runnable onTileReady,
            ScreenRuntimeKey runtimeKey
    ) {
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        BufferedImage canvas = new BufferedImage(safeWidth, safeHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        // Match the old renderer exactly: uncovered/not-yet-rendered map pixels are
        // one flat opaque gray-blue surface. Do not let the monitor's textured
        // screen_on.png show through transparent LOD quadrants.
        graphics.setColor(new Color(MISSING_RGB));
        graphics.fillRect(0, 0, safeWidth, safeHeight);

        if (runtimeKey != null) {
            ACTIVE_VIEWS.put(runtimeKey, new ViewRegistration(onTileReady));
        }
        if (server == null || !Level.OVERWORLD.equals(dimension)) {
            graphics.dispose();
            return Frame.failure(canvas, "Карта недоступна");
        }

        // Displaying committed cache is deliberately independent from world discovery and
        // the currently elected render-worker cohort. Discovery decides what should be
        // refreshed next; it must never gate reading PNGs which are already on disk.
        YandexMapRenderService.DiscoverySnapshot discovery = YandexMapRenderService.discoverySnapshot();
        MapRenderProfile profile = MapRenderProfile.CURRENT;
        Path storeRoot = server.getWorldPath(LevelResource.ROOT).resolve(STORE_DIRECTORY);
        int zoomExponent = zoomExponentForBlocksPerPixel(blocksPerPixel);
        double snappedBlocksPerPixel = blocksPerPixelForZoomExponent(zoomExponent);
        long sourceSpan = sourceTileBlocks(zoomExponent, profile);
        double halfWorldWidth = safeWidth * snappedBlocksPerPixel * 0.5D;
        double halfWorldHeight = safeHeight * snappedBlocksPerPixel * 0.5D;
        double minWorldX = centerX - halfWorldWidth;
        double maxWorldX = centerX + halfWorldWidth;
        double minWorldZ = centerZ - halfWorldHeight;
        double maxWorldZ = centerZ + halfWorldHeight;
        demandVisible(discovery, minWorldX, minWorldZ, maxWorldX, maxWorldZ);

        long minTileX = floorTile(minWorldX, sourceSpan);
        long maxTileX = floorTile(Math.nextDown(maxWorldX), sourceSpan);
        long minTileZ = floorTile(minWorldZ, sourceSpan);
        long maxTileZ = floorTile(Math.nextDown(maxWorldZ), sourceSpan);
        String canonicalProfileHash = discovery != null && discovery.profileHash() != null && !discovery.profileHash().isBlank()
                ? discovery.profileHash()
                : MapRenderWorkerRegistry.canonicalProfileHash();
        String displayProfileHash = chooseDisplayProfileHash(
                storeRoot,
                profile,
                zoomExponent,
                minTileX,
                maxTileX,
                minTileZ,
                maxTileZ,
                canonicalProfileHash
        );
        if (displayProfileHash == null || displayProfileHash.isBlank()) {
            graphics.dispose();
            return Frame.failure(canvas, "Карта пока не отрендерена");
        }
        int loaded = 0;
        int missing = 0;
        try {
            for (long tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                for (long tileX = minTileX; tileX <= maxTileX; tileX++) {
                    int screenX = (int) Math.round(safeWidth * 0.5D + (tileX * (double) sourceSpan - centerX) / snappedBlocksPerPixel);
                    int screenY = (int) Math.round(safeHeight * 0.5D + (tileZ * (double) sourceSpan - centerZ) / snappedBlocksPerPixel);
                    int displayTilePixels = Math.max(1, (int) Math.round(sourceSpan / snappedBlocksPerPixel));
                    BufferedImage tile = loadTile(server, storeRoot, profile, displayProfileHash, zoomExponent, tileX, tileZ);
                    if (tile == null) {
                        missing++;
                        continue;
                    }
                    if (zoomExponent < 0) {
                        drawMagnifiedTileViewport(graphics, tile, screenX, screenY, displayTilePixels, safeWidth, safeHeight);
                    } else {
                        graphics.drawImage(tile, screenX, screenY, displayTilePixels, displayTilePixels, null);
                    }
                    loaded++;
                }
            }
        } finally {
            graphics.dispose();
        }
        boolean showingCanonical = canonicalProfileHash != null && canonicalProfileHash.equals(displayProfileHash);
        String status = missing == 0 && showingCanonical
                ? "Карта online"
                : loaded > 0 ? "Карта обновляется" : "Карта пока не отрендерена";
        return new Frame(canvas, status, loaded > 0 || missing == 0);
    }

    static double snapBlocksPerPixel(double blocksPerPixel) {
        return blocksPerPixelForZoomExponent(zoomExponentForBlocksPerPixel(blocksPerPixel));
    }

    static int zoomExponentForBlocksPerPixel(double blocksPerPixel) {
        double base = baseBlocksPerPixel();
        if (!Double.isFinite(blocksPerPixel) || blocksPerPixel <= 0.0D) return 0;
        double exponent = Math.log(blocksPerPixel / base) / Math.log(2.0D);
        return Math.clamp((int) Math.round(exponent), MIN_ZOOM_EXPONENT, MAX_ZOOM_EXPONENT);
    }

    static double blocksPerPixelForZoomExponent(int exponent) {
        return Math.scalb(baseBlocksPerPixel(), Math.clamp(exponent, MIN_ZOOM_EXPONENT, MAX_ZOOM_EXPONENT));
    }

    private static double baseBlocksPerPixel() {
        MapRenderProfile profile = MapRenderProfile.CURRENT;
        return profile.tileBlocks() / (double) profile.tilePixels();
    }

    private static long sourceTileBlocks(int zoomExponent, MapRenderProfile profile) {
        int storageLevel = Math.clamp(zoomExponent, 0, MAX_ZOOM_EXPONENT);
        return Math.multiplyExact((long) profile.tileBlocks(), 1L << storageLevel);
    }

    private static long floorTile(double worldCoordinate, long tileSpan) {
        return (long) Math.floor(worldCoordinate / (double) tileSpan);
    }

    private static BufferedImage loadTile(
            MinecraftServer server,
            Path storeRoot,
            MapRenderProfile profile,
            String profileHash,
            int zoomExponent,
            long tileX,
            long tileZ
    ) {
        try {
            if (zoomExponent <= 0) {
                MapTileKey key = new MapTileKey(Level.OVERWORLD.identifier(), tileX, tileZ, profile.version());
                MapTileStore store = new MapTileStore(storeRoot);
                Optional<MapTileMetadata> metadata = store.readMetadata(key, profileHash);
                if (metadata.isEmpty()) return null;
                String cacheKey = "b|" + profile.version() + '|' + profileHash + '|' + tileX + '|' + tileZ + '|' + metadata.get().resultChecksum();
                BufferedImage cached = cached(cacheKey);
                if (cached != null) return cached;
                Optional<MapTileStore.StoredTile> stored = store.read(key, profileHash);
                return stored.isEmpty() ? null : decodeAndCache(cacheKey, stored.get().imageBytes());
            }

            MapPyramidTileKey key = new MapPyramidTileKey(Level.OVERWORLD.identifier(), zoomExponent, tileX, tileZ, profile.version());
            MapPyramidStore store = new MapPyramidStore(storeRoot);
            Optional<MapPyramidMetadata> metadata = store.readMetadata(key, profileHash);
            if (metadata.isEmpty()) {
                MapPyramidBuilder.request(server, key, profileHash);
                return null;
            }
            String cacheKey = "p|" + profile.version() + '|' + profileHash + '|' + zoomExponent + '|' + tileX + '|' + tileZ + '|' + metadata.get().resultChecksum();
            BufferedImage cached = cached(cacheKey);
            if (cached != null) return cached;
            Optional<MapPyramidStore.StoredTile> stored = store.read(key, profileHash);
            return stored.isEmpty() ? null : decodeAndCache(cacheKey, stored.get().imageBytes());
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * Chooses one profile for the whole visible frame. The elected worker profile wins
     * ties, but an older compatible v4 cache with actual visible coverage wins over an
     * empty newly-elected namespace. This keeps the map immediately readable while a
     * new worker cohort refreshes its own cache in the background.
     */
    static String chooseDisplayProfileHash(
            Path storeRoot,
            MapRenderProfile profile,
            int zoomExponent,
            long minTileX,
            long maxTileX,
            long minTileZ,
            long maxTileZ,
            String canonicalProfileHash
    ) {
        if (storeRoot == null || profile == null) return canonicalProfileHash;
        List<String> candidates = readableProfileHashes(storeRoot, profile);
        if (canonicalProfileHash != null && !canonicalProfileHash.isBlank() && !candidates.contains(canonicalProfileHash)) {
            candidates.addFirst(canonicalProfileHash);
        }
        String best = null;
        int bestCoverage = -1;
        boolean bestCanonical = false;
        for (String candidate : candidates) {
            int coverage = visibleCoverage(storeRoot, profile, candidate, zoomExponent, minTileX, maxTileX, minTileZ, maxTileZ);
            boolean canonical = candidate != null && candidate.equals(canonicalProfileHash);
            if (coverage > bestCoverage || (coverage == bestCoverage && canonical && !bestCanonical)) {
                best = candidate;
                bestCoverage = coverage;
                bestCanonical = canonical;
            }
        }
        return bestCoverage > 0 ? best : canonicalProfileHash;
    }

    private static List<String> readableProfileHashes(Path storeRoot, MapRenderProfile profile) {
        Path root = storeRoot
                .resolve("minecraft_overworld")
                .resolve("render-v" + profile.version());
        if (!Files.isDirectory(root)) return new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(root, "profile-*")) {
            for (Path path : stream) {
                if (!Files.isDirectory(path)) continue;
                String name = path.getFileName().toString();
                if (name.length() > "profile-".length()) hashes.add(name.substring("profile-".length()));
            }
        } catch (IOException ignored) {
        }
        hashes.sort(String::compareTo);
        return hashes;
    }

    private static int visibleCoverage(
            Path storeRoot,
            MapRenderProfile profile,
            String profileHash,
            int zoomExponent,
            long minTileX,
            long maxTileX,
            long minTileZ,
            long maxTileZ
    ) {
        if (profileHash == null || profileHash.isBlank()) return 0;
        int coverage = 0;
        try {
            if (zoomExponent <= 0) {
                MapTileStore store = new MapTileStore(storeRoot);
                for (long z = minTileZ; z <= maxTileZ; z++) for (long x = minTileX; x <= maxTileX; x++) {
                    MapTileKey key = new MapTileKey(Level.OVERWORLD.identifier(), x, z, profile.version());
                    if (store.readMetadata(key, profileHash).isPresent()) coverage++;
                }
                return coverage;
            }
            MapPyramidStore store = new MapPyramidStore(storeRoot);
            for (long z = minTileZ; z <= maxTileZ; z++) for (long x = minTileX; x <= maxTileX; x++) {
                MapPyramidTileKey key = new MapPyramidTileKey(Level.OVERWORLD.identifier(), zoomExponent, x, z, profile.version());
                if (store.readMetadata(key, profileHash).isPresent()) coverage++;
            }
        } catch (IOException ignored) {
        }
        return coverage;
    }

    private static BufferedImage cached(String key) {
        synchronized (IMAGE_CACHE_LOCK) {
            return IMAGE_CACHE.get(key);
        }
    }

    private static BufferedImage decodeAndCache(String key, byte[] pngBytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (image != null) {
            synchronized (IMAGE_CACHE_LOCK) {
                IMAGE_CACHE.put(key, image);
            }
        }
        return image;
    }

    private static void drawMagnifiedTileViewport(
            Graphics2D graphics,
            BufferedImage image,
            int tileScreenX,
            int tileScreenY,
            int displayTilePixels,
            int viewportWidth,
            int viewportHeight
    ) {
        int x1 = Math.max(tileScreenX, 0);
        int y1 = Math.max(tileScreenY, 0);
        int x2 = Math.min(tileScreenX + displayTilePixels, viewportWidth);
        int y2 = Math.min(tileScreenY + displayTilePixels, viewportHeight);
        if (x2 <= x1 || y2 <= y1) return;

        double sourcePerScreenX = image.getWidth() / (double) displayTilePixels;
        double sourcePerScreenY = image.getHeight() / (double) displayTilePixels;
        int sx1 = Math.clamp((int) Math.floor((x1 - tileScreenX) * sourcePerScreenX), 0, image.getWidth());
        int sy1 = Math.clamp((int) Math.floor((y1 - tileScreenY) * sourcePerScreenY), 0, image.getHeight());
        int sx2 = Math.clamp((int) Math.ceil((x2 - tileScreenX) * sourcePerScreenX), 0, image.getWidth());
        int sy2 = Math.clamp((int) Math.ceil((y2 - tileScreenY) * sourcePerScreenY), 0, image.getHeight());
        if (sx2 <= sx1 || sy2 <= sy1) return;

        Object previous = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(image, x1, y1, x2, y2, sx1, sy1, sx2, sy2, null);
        if (previous != null) graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previous);
    }

    private static void demandVisible(
            YandexMapRenderService.DiscoverySnapshot discovery,
            double minX,
            double minZ,
            double maxX,
            double maxZ
    ) {
        if (discovery == null || discovery.inventory() == null) return;
        double span = discovery.profile().tileBlocks();
        List<MapTileKey> visible = new ArrayList<>();
        for (MapRenderInventory.TileStatus status : discovery.inventory().tiles()) {
            MapTileKey key = status.key();
            if (!Level.OVERWORLD.identifier().equals(key.dimensionId())) continue;
            double tileMinX = key.tileX() * span;
            double tileMinZ = key.tileZ() * span;
            if (tileMinX + span <= minX || tileMinX >= maxX || tileMinZ + span <= minZ || tileMinZ >= maxZ) continue;
            visible.add(key);
        }
        if (!visible.isEmpty()) MapRenderScheduler.demandVisible(visible);
    }

    private static long uiRevision() {
        YandexMapRenderService.Status status = YandexMapRenderService.status();
        long current = status != null ? status.currentTiles() : 0L;
        long completed = status != null ? status.scanCompletedAtEpochMs() : 0L;
        return current * 31L + completed * 17L + MapPyramidBuilder.revision();
    }

    record Frame(BufferedImage image, String status, boolean healthy) {
        static Frame failure(BufferedImage image, String status) {
            return new Frame(image, status, false);
        }
    }

    private record ViewRegistration(Runnable onTileReady) {
    }
}
