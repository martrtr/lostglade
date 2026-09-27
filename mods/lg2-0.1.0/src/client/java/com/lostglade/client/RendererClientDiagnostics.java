package com.lostglade.client;

import com.lostglade.Lg2;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Small in-memory telemetry surface for the two distributed client renderers.
 *
 * <p>This is deliberately local-only: it records what this Minecraft process
 * actually accepted/rendered and never changes renderer scheduling.</p>
 */
public final class RendererClientDiagnostics {
    private static final Object LOCK = new Object();
    private static final int MAX_EVENTS = 240;

    private static final Deque<Event> EVENTS = new ArrayDeque<>();
    private static final Set<UUID> CAMERA_PHOTOS = new HashSet<>();
    private static final Set<UUID> CAMERA_STREAMS = new HashSet<>();
    private static final Set<UUID> CAMERA_VIDEOS = new HashSet<>();
    private static final Deque<Long> MAP_ACCEPTED_AT = new ArrayDeque<>();

    private static long cameraAccepted;
    private static long cameraCompleted;
    private static long cameraFailed;
    private static long mapOffers;
    private static long mapAccepted;
    private static long mapRendered;
    private static long mapFailed;
    private static long mapRejected;
    private static UUID activeMapJob;
    private static long activeMapTileX;
    private static long activeMapTileZ;
    private static String activeMapStage = "idle";

    private RendererClientDiagnostics() {
    }

    public static void cameraPhotoAccepted(UUID requestId) {
        if (requestId == null) return;
        synchronized (LOCK) {
            if (CAMERA_PHOTOS.add(requestId)) cameraAccepted++;
            addEventLocked("CAM", "Фото " + shortId(requestId) + " принято");
        }
    }

    public static void cameraPhotoCompleted(UUID requestId) {
        if (requestId == null) return;
        synchronized (LOCK) {
            if (CAMERA_PHOTOS.remove(requestId)) cameraCompleted++;
            addEventLocked("CAM", "Фото " + shortId(requestId) + " отправлено");
        }
    }

    public static void cameraPhotoFailed(UUID requestId, String reason) {
        if (requestId == null) return;
        synchronized (LOCK) {
            if (CAMERA_PHOTOS.remove(requestId)) cameraFailed++;
            addEventLocked("CAM", "Фото " + shortId(requestId) + " ошибка: " + safe(reason));
        }
    }

    public static void cameraLiveStarted(UUID streamId) {
        if (streamId == null) return;
        synchronized (LOCK) {
            if (CAMERA_STREAMS.add(streamId)) cameraAccepted++;
            addEventLocked("CAM", "Live " + shortId(streamId) + " запущен");
        }
    }

    public static void cameraLiveStopped(UUID streamId) {
        if (streamId == null) return;
        synchronized (LOCK) {
            if (CAMERA_STREAMS.remove(streamId)) cameraCompleted++;
            addEventLocked("CAM", "Live " + shortId(streamId) + " остановлен");
        }
    }

    public static void cameraLiveFailed(UUID streamId, String reason) {
        if (streamId == null) return;
        synchronized (LOCK) {
            if (CAMERA_STREAMS.remove(streamId)) cameraFailed++;
            addEventLocked("CAM", "Live " + shortId(streamId) + " ошибка: " + safe(reason));
        }
    }

    public static void cameraVideoStarted(UUID requestId) {
        if (requestId == null) return;
        synchronized (LOCK) {
            if (CAMERA_VIDEOS.add(requestId)) cameraAccepted++;
            addEventLocked("CAM", "Видео " + shortId(requestId) + " принято");
        }
    }

    public static void cameraVideoCompleted(UUID requestId) {
        if (requestId == null) return;
        synchronized (LOCK) {
            if (CAMERA_VIDEOS.remove(requestId)) cameraCompleted++;
            addEventLocked("CAM", "Видео " + shortId(requestId) + " отправлено");
        }
    }

    public static void cameraVideoFailed(UUID requestId, String reason) {
        if (requestId == null) return;
        synchronized (LOCK) {
            if (CAMERA_VIDEOS.remove(requestId)) cameraFailed++;
            addEventLocked("CAM", "Видео " + shortId(requestId) + " ошибка: " + safe(reason));
        }
    }

    public static void mapOffer(UUID jobId, long tileX, long tileZ) {
        synchronized (LOCK) {
            mapOffers++;
            addEventLocked("MAP", "Offer " + shortId(jobId) + " tile " + tileX + "," + tileZ);
        }
    }

    public static void mapRejected(UUID jobId, String reason) {
        synchronized (LOCK) {
            mapRejected++;
            addEventLocked("MAP", "Reject " + shortId(jobId) + ": " + safe(reason));
        }
    }

    public static void mapAccepted(UUID jobId, long tileX, long tileZ) {
        synchronized (LOCK) {
            mapAccepted++;
            MAP_ACCEPTED_AT.addLast(System.currentTimeMillis());
            activeMapJob = jobId;
            activeMapTileX = tileX;
            activeMapTileZ = tileZ;
            activeMapStage = "ожидание scene";
            addEventLocked("MAP", "Tile " + tileX + "," + tileZ + " принят");
        }
    }

    public static void mapStage(UUID jobId, String stage) {
        synchronized (LOCK) {
            if (jobId == null || !jobId.equals(activeMapJob)) return;
            String safeStage = safe(stage);
            if (safeStage.equals(activeMapStage)) return;
            activeMapStage = safeStage;
            addEventLocked("MAP", "Tile " + activeMapTileX + "," + activeMapTileZ + ": " + safeStage);
        }
    }

    public static void mapRendered(UUID jobId, long tileX, long tileZ, int pngBytes) {
        synchronized (LOCK) {
            mapRendered++;
            if (jobId != null && jobId.equals(activeMapJob)) clearActiveMapLocked();
            addEventLocked("MAP", "Tile " + tileX + "," + tileZ + " готов, " + Math.max(0, pngBytes / 1024) + " KiB");
        }
    }

    public static void mapFailed(UUID jobId, String reason) {
        synchronized (LOCK) {
            mapFailed++;
            if (jobId != null && jobId.equals(activeMapJob)) clearActiveMapLocked();
            addEventLocked("MAP", "Job " + shortId(jobId) + " ошибка: " + safe(reason));
        }
    }

    public static void mapCancelled(UUID jobId, String reason) {
        synchronized (LOCK) {
            if (jobId != null && jobId.equals(activeMapJob)) clearActiveMapLocked();
            addEventLocked("MAP", "Job " + shortId(jobId) + " отменён: " + safe(reason));
        }
    }

    public static void mapStatus(boolean eligible, String reason) {
        synchronized (LOCK) {
            addEventLocked("MAP", "Статус: " + (eligible ? "eligible" : "not eligible") + " / " + safe(reason));
        }
    }

    public static void disconnected() {
        synchronized (LOCK) {
            int interrupted = CAMERA_PHOTOS.size() + CAMERA_STREAMS.size() + CAMERA_VIDEOS.size();
            String interruptedMap = activeMapJob == null ? "нет" : shortId(activeMapJob) + " tile " + activeMapTileX + "," + activeMapTileZ;
            CAMERA_PHOTOS.clear();
            CAMERA_STREAMS.clear();
            CAMERA_VIDEOS.clear();
            clearActiveMapLocked();
            addEventLocked("NET", "Отключение; camera jobs: " + interrupted + ", map job: " + interruptedMap);
        }
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            pruneAcceptedTimesLocked(System.currentTimeMillis());
            return new Snapshot(
                    CAMERA_PHOTOS.size(), CAMERA_STREAMS.size(), CAMERA_VIDEOS.size(),
                    cameraAccepted, cameraCompleted, cameraFailed,
                    mapOffers, mapAccepted, mapRendered, mapFailed, mapRejected,
                    MAP_ACCEPTED_AT.size(),
                    activeMapJob == null ? "" : shortId(activeMapJob),
                    activeMapJob == null ? "" : activeMapTileX + "," + activeMapTileZ,
                    activeMapJob == null ? "idle" : activeMapStage,
                    EVENTS.size()
            );
        }
    }

    /** Returns newest events first. */
    public static List<Event> events(int offset, int limit) {
        synchronized (LOCK) {
            int safeOffset = Math.max(0, offset);
            int safeLimit = Math.max(0, limit);
            List<Event> chronological = new ArrayList<>(EVENTS);
            List<Event> result = new ArrayList<>(Math.min(safeLimit, chronological.size()));
            int skipped = 0;
            for (int i = chronological.size() - 1; i >= 0 && result.size() < safeLimit; i--) {
                if (skipped++ < safeOffset) continue;
                result.add(chronological.get(i));
            }
            return List.copyOf(result);
        }
    }

    public static void clearEvents() {
        synchronized (LOCK) {
            EVENTS.clear();
            addEventLocked("UI", "Журнал очищен");
        }
    }

    public static void event(String source, String message) {
        synchronized (LOCK) {
            addEventLocked(source, message);
        }
    }

    private static void pruneAcceptedTimesLocked(long now) {
        while (!MAP_ACCEPTED_AT.isEmpty() && now - MAP_ACCEPTED_AT.peekFirst() >= 60_000L) {
            MAP_ACCEPTED_AT.removeFirst();
        }
    }

    private static void clearActiveMapLocked() {
        activeMapJob = null;
        activeMapTileX = 0L;
        activeMapTileZ = 0L;
        activeMapStage = "idle";
    }

    private static void addEventLocked(String source, String message) {
        String safeSource = safe(source);
        String safeMessage = safe(message);
        EVENTS.addLast(new Event(System.currentTimeMillis(), safeSource, safeMessage));
        while (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
        // The diagnostics screen is useful interactively, but dedicated renderer bots
        // are normally hidden/off-screen. Mirror the same events into latest.log so
        // camera/map activity remains observable from Prism or a terminal.
        Lg2.LOGGER.info("[renderer-diagnostics][{}] {}", safeSource, safeMessage);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String shortId(UUID id) {
        if (id == null) return "—";
        String value = id.toString();
        return value.substring(0, Math.min(8, value.length()));
    }

    public record Event(long timestampMs, String source, String message) {
    }

    public record Snapshot(
            int activePhotos,
            int activeStreams,
            int activeVideos,
            long cameraAccepted,
            long cameraCompleted,
            long cameraFailed,
            long mapOffers,
            long mapAccepted,
            long mapRendered,
            long mapFailed,
            long mapRejected,
            int mapAcceptedLastMinute,
            String activeMapJob,
            String activeMapTile,
            String activeMapStage,
            int eventCount
    ) {
        public int activeCameraJobs() {
            return this.activePhotos + this.activeStreams + this.activeVideos;
        }
    }
}
