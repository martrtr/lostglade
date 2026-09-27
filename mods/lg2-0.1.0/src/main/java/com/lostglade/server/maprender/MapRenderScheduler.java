package com.lostglade.server.maprender;

import com.lostglade.config.Lg2Config;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fair background scheduler for immutable base-tile jobs.
 *
 * It only consumes {@link YandexMapRenderService.DiscoverySnapshot}; it never
 * talks to ServerLevel chunk loading/tickets. World freshness arrives through
 * periodic read-only MCA-header rescans.
 */
public final class MapRenderScheduler {
    private static final int DISPATCH_INTERVAL_TICKS = 20;
    private static final int RESCAN_INTERVAL_TICKS = 20 * 60;
    private static final QueueClass[] FAIR_CYCLE = {
            QueueClass.DIRTY, QueueClass.DIRTY, QueueClass.DIRTY, QueueClass.DIRTY, QueueClass.DIRTY,
            QueueClass.VISIBLE, QueueClass.VISIBLE, QueueClass.VISIBLE, QueueClass.VISIBLE,
            QueueClass.NEW, QueueClass.NEW, QueueClass.NEW,
            QueueClass.AUDIT
    };
    private static final Map<MapTileKey, PriorityState> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, WorkerHealth> WORKER_HEALTH = new ConcurrentHashMap<>();
    private static volatile long lastDiscoveryCompletedAt;
    private static int cycleCursor;

    private MapRenderScheduler() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(MapRenderScheduler::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> reset());
    }

    static void onDiscovery(YandexMapRenderService.DiscoverySnapshot snapshot) {
        if (snapshot == null) return;
        long now = System.currentTimeMillis();
        Set<MapTileKey> present = ConcurrentHashMap.newKeySet();
        for (MapRenderInventory.TileStatus tile : snapshot.inventory().tiles()) {
            present.add(tile.key());
            STATES.compute(tile.key(), (key, previous) -> {
                PriorityState state = previous == null ? new PriorityState() : previous;
                String fingerprint = tile.source().sourceFingerprint();
                if (!tile.current()) {
                    if (state.firstStaleAt == 0L) state.firstStaleAt = now;
                    if (state.lastObservedFingerprint != null && !state.lastObservedFingerprint.equals(fingerprint)) {
                        state.changeCount = Math.min(1_000_000, state.changeCount + 1);
                    }
                } else {
                    state.firstStaleAt = 0L;
                    state.changeCount = 0;
                    state.failureCount = 0;
                    state.backoffUntil = 0L;
                }
                if (state.blockedFingerprint != null && !state.blockedFingerprint.equals(fingerprint)) {
                    state.blockedFingerprint = null;
                }
                if (tile.current()) state.blockedFingerprint = null;
                state.lastObservedFingerprint = fingerprint;
                state.lastSeenAt = now;
                return state;
            });
        }
        STATES.keySet().removeIf(key -> !present.contains(key));
        lastDiscoveryCompletedAt = snapshot.completedAtEpochMs();
    }

    /** Gives tiles intersecting an open map viewport a temporary fair-queue boost. */
    public static void demandVisible(Iterable<MapTileKey> keys) {
        if (keys == null) return;
        long now = System.currentTimeMillis();
        long until = now + Math.max(1, Lg2Config.get().yandexMapVisibleDemandSeconds) * 1_000L;
        for (MapTileKey key : keys) {
            if (key == null) continue;
            STATES.computeIfAbsent(key, ignored -> new PriorityState()).visibleDemandUntil = until;
        }
    }

    static void registerSuccess(MapTileKey key) {
        if (key == null) return;
        PriorityState state = STATES.computeIfAbsent(key, ignored -> new PriorityState());
        state.failureCount = 0;
        state.backoffUntil = 0L;
    }

    static void registerFailure(MapTileKey key) {
        if (key == null) return;
        PriorityState state = STATES.computeIfAbsent(key, ignored -> new PriorityState());
        state.failureCount = Math.min(20, state.failureCount + 1);
        long maxMinutes = Math.max(1, Lg2Config.get().yandexMapMaxFailureBackoffMinutes);
        long delaySeconds = Math.min(maxMinutes * 60L, 15L << Math.min(12, state.failureCount - 1));
        state.backoffUntil = System.currentTimeMillis() + delaySeconds * 1_000L;
    }

    static void registerDecline(MapTileKey key) {
        if (key == null) return;
        PriorityState state = STATES.computeIfAbsent(key, ignored -> new PriorityState());
        // A busy/low-FPS volunteer is not a broken tile. Just avoid offer spam.
        state.backoffUntil = Math.max(state.backoffUntil, System.currentTimeMillis() + 5_000L);
    }

    static void registerWorkerSuccess(UUID workerUuid) {
        if (workerUuid == null) return;
        WORKER_HEALTH.remove(workerUuid);
    }

    static void registerWorkerDecline(UUID workerUuid, String reason) {
        if (workerUuid == null) return;
        long now = System.currentTimeMillis();
        long delay = switch (reason == null ? "" : reason) {
            case "client-rate-limit" -> 30_000L;
            case "fps-below-threshold", "client-not-idle" -> 10_000L;
            default -> 5_000L;
        };
        WORKER_HEALTH.compute(workerUuid, (uuid, previous) -> {
            int failures = previous == null ? 0 : previous.failureCount;
            long until = Math.max(previous == null ? 0L : previous.cooldownUntilEpochMs, now + delay);
            return new WorkerHealth(failures, until);
        });
    }

    static void registerUnrenderable(MapTileKey key, String sourceFingerprint) {
        if (key == null || sourceFingerprint == null || sourceFingerprint.isBlank()) return;
        PriorityState state = STATES.computeIfAbsent(key, ignored -> new PriorityState());
        state.blockedFingerprint = sourceFingerprint;
        state.backoffUntil = 0L;
    }

    static void registerWorkerFailure(UUID workerUuid) {
        if (workerUuid == null) return;
        long now = System.currentTimeMillis();
        WORKER_HEALTH.compute(workerUuid, (uuid, previous) -> {
            int failures = previous == null ? 1 : Math.min(20, previous.failureCount + 1);
            return new WorkerHealth(failures, now + workerCooldownMillisForFailureCount(failures));
        });
    }

    static boolean workerAvailable(UUID workerUuid, long nowEpochMs) {
        if (workerUuid == null) return false;
        WorkerHealth health = WORKER_HEALTH.get(workerUuid);
        if (health == null) return true;
        if (health.cooldownUntilEpochMs <= nowEpochMs) {
            WORKER_HEALTH.remove(workerUuid, health);
            return true;
        }
        return false;
    }

    static long workerCooldownMillisForFailureCount(int failureCount) {
        int safe = Math.max(1, failureCount);
        if (safe == 1) return 5_000L;
        if (safe == 2) return 15_000L;
        long seconds = 30L << Math.min(4, safe - 3);
        return Math.min(300_000L, seconds * 1_000L);
    }

    private static void tick(MinecraftServer server) {
        if (server == null) return;
        int tick = server.getTickCount();
        if (tick % RESCAN_INTERVAL_TICKS == 0 && YandexMapRenderService.status().state() != YandexMapRenderService.State.SCANNING) {
            YandexMapRenderService.refreshAsync(server);
        }
        if (tick % DISPATCH_INTERVAL_TICKS != 0) return;
        dispatch(server);
    }

    private static void dispatch(MinecraftServer server) {
        YandexMapRenderService.DiscoverySnapshot discovery = YandexMapRenderService.discoverySnapshot();
        if (discovery == null || YandexMapRenderService.status().state() != YandexMapRenderService.State.READY) return;
        if (discovery.completedAtEpochMs() != lastDiscoveryCompletedAt) onDiscovery(discovery);

        int globalLimit = Math.max(1, Lg2Config.get().yandexMapRendererMaxGlobalInFlight);
        int capacity = globalLimit - MapRenderJobService.activeJobCount();
        if (capacity <= 0) return;

        List<MapRenderWorkerRegistry.WorkerState> workers = MapRenderWorkerRegistry.eligibleWorkers(server);
        if (workers.isEmpty()) return;
        Set<MapTileKey> activeTiles = MapRenderJobService.activeTileKeys();
        long now = System.currentTimeMillis();
        for (MapRenderWorkerRegistry.WorkerState worker : workers) {
            if (capacity <= 0) break;
            if (MapRenderJobService.hasActiveJobForWorker(worker.playerUuid())) continue;
            if (!workerAvailable(worker.playerUuid(), now)) continue;
            MapRenderInventory.TileStatus candidate = chooseCandidate(discovery.inventory(), activeTiles);
            if (candidate == null) break;
            if (MapRenderJobService.tryOfferAutomatic(server, worker, candidate)) {
                activeTiles = new java.util.HashSet<>(activeTiles);
                activeTiles.add(candidate.key());
                capacity--;
            } else {
                registerDecline(candidate.key());
            }
        }
    }

    private static MapRenderInventory.TileStatus chooseCandidate(MapRenderInventory.Snapshot inventory, Set<MapTileKey> activeTiles) {
        if (inventory == null) return null;
        long now = System.currentTimeMillis();
        for (int attempts = 0; attempts < FAIR_CYCLE.length; attempts++) {
            QueueClass queue = FAIR_CYCLE[cycleCursor++ % FAIR_CYCLE.length];
            List<MapRenderInventory.TileStatus> candidates = new ArrayList<>();
            for (MapRenderInventory.TileStatus tile : inventory.tiles()) {
                if (activeTiles.contains(tile.key())) continue;
                PriorityState state = STATES.computeIfAbsent(tile.key(), ignored -> new PriorityState());
                if (state.backoffUntil > now) continue;
                if (state.blockedFingerprint != null && state.blockedFingerprint.equals(tile.source().sourceFingerprint())) continue;
                if (belongsTo(queue, tile, state, now)) candidates.add(tile);
            }
            if (!candidates.isEmpty()) {
                candidates.sort(candidateComparator(queue));
                return candidates.getFirst();
            }
        }
        return null;
    }

    private static boolean belongsTo(QueueClass queue, MapRenderInventory.TileStatus tile, PriorityState state, long now) {
        if (queue == QueueClass.AUDIT) return false; // audit validates store; it never rerenders unchanged world data.
        if (tile.current()) return false;
        boolean visible = state.visibleDemandUntil > now;
        return switch (queue) {
            case VISIBLE -> visible;
            case DIRTY -> !visible && tile.metadata() != null;
            case NEW -> !visible && tile.metadata() == null;
            case AUDIT -> false;
        };
    }

    private static Comparator<MapRenderInventory.TileStatus> candidateComparator(QueueClass queue) {
        return Comparator
                .comparingLong((MapRenderInventory.TileStatus tile) -> staleSince(tile.key()))
                .thenComparing((MapRenderInventory.TileStatus a, MapRenderInventory.TileStatus b) -> Integer.compare(changeCount(b.key()), changeCount(a.key())))
                .thenComparingLong(tile -> tile.metadata() == null ? Long.MIN_VALUE : tile.metadata().renderedAtEpochMs())
                .thenComparing(MapRenderInventory.TileStatus::key);
    }

    private static long staleSince(MapTileKey key) {
        PriorityState state = STATES.get(key);
        return state == null || state.firstStaleAt == 0L ? Long.MAX_VALUE : state.firstStaleAt;
    }

    private static int changeCount(MapTileKey key) {
        PriorityState state = STATES.get(key);
        return state == null ? 0 : state.changeCount;
    }

    private static void reset() {
        STATES.clear();
        WORKER_HEALTH.clear();
        cycleCursor = 0;
        lastDiscoveryCompletedAt = 0L;
    }

    private enum QueueClass { DIRTY, VISIBLE, NEW, AUDIT }

    private static final class WorkerHealth {
        private final int failureCount;
        private final long cooldownUntilEpochMs;

        private WorkerHealth(int failureCount, long cooldownUntilEpochMs) {
            this.failureCount = failureCount;
            this.cooldownUntilEpochMs = cooldownUntilEpochMs;
        }
    }

    private static final class PriorityState {
        private volatile String lastObservedFingerprint;
        private volatile long firstStaleAt;
        private volatile long lastSeenAt;
        private volatile long visibleDemandUntil;
        private volatile long backoffUntil;
        private volatile String blockedFingerprint;
        private volatile int changeCount;
        private volatile int failureCount;
    }
}
