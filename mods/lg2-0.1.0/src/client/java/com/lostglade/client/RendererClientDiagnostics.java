package com.lostglade.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tiny local render-state cache used by the in-game HUD.  It intentionally
 * keeps no event history and does not write renderer activity to the log.
 */
public final class RendererClientDiagnostics {
    private static final Object LOCK = new Object();
    private static final Set<UUID> CAMERA_PHOTOS = new HashSet<>();
    private static final Set<UUID> CAMERA_STREAMS = new HashSet<>();
    private static final Set<UUID> CAMERA_VIDEOS = new HashSet<>();
    private static final Deque<Long> CAMERA_FRAME_TIMES = new ArrayDeque<>();
    private static UUID activeMapJob;
    private static String activeMapStage = "ожидание";
    private static boolean mapEligible;
    private static String mapReason = "ожидание статуса";

    private RendererClientDiagnostics() {
    }

    public static void cameraPhotoAccepted(UUID requestId) {
        if (requestId == null) return;
        synchronized (LOCK) {
            CAMERA_PHOTOS.add(requestId);
        }
    }

    public static void cameraPhotoCompleted(UUID requestId) {
        if (requestId == null) return;
        synchronized (LOCK) {
            CAMERA_PHOTOS.remove(requestId);
        }
    }

    public static void cameraPhotoFailed(UUID requestId, String reason) {
        if (requestId == null) return;
        synchronized (LOCK) {
            CAMERA_PHOTOS.remove(requestId);
        }
    }

    public static void cameraLiveStarted(UUID streamId) {
        if (streamId == null) return;
        synchronized (LOCK) {
            CAMERA_STREAMS.add(streamId);
        }
    }

    public static void cameraLiveStopped(UUID streamId) {
        if (streamId == null) return;
        synchronized (LOCK) {
            CAMERA_STREAMS.remove(streamId);
        }
    }

    public static void cameraLiveFailed(UUID streamId, String reason) {
        if (streamId == null) return;
        synchronized (LOCK) {
            CAMERA_STREAMS.remove(streamId);
        }
    }

    public static void cameraVideoStarted(UUID requestId) {
        if (requestId == null) return;
        synchronized (LOCK) {
            CAMERA_VIDEOS.add(requestId);
        }
    }

    public static void cameraVideoCompleted(UUID requestId) {
        if (requestId == null) return;
        synchronized (LOCK) {
            CAMERA_VIDEOS.remove(requestId);
        }
    }

    public static void cameraVideoFailed(UUID requestId, String reason) {
        if (requestId == null) return;
        synchronized (LOCK) {
            CAMERA_VIDEOS.remove(requestId);
        }
    }

    public static void mapOffer(UUID jobId, long tileX, long tileZ) {
        // The HUD needs no per-offer history.
    }

    public static void mapRejected(UUID jobId, String reason) {
        synchronized (LOCK) { mapReason = safe(reason); }
    }

    public static void mapAccepted(UUID jobId, long tileX, long tileZ) {
        synchronized (LOCK) {
            activeMapJob = jobId;
            activeMapStage = "рендер";
        }
    }

    public static void mapStage(UUID jobId, String stage) {
        synchronized (LOCK) {
            if (jobId == null || !jobId.equals(activeMapJob)) return;
            activeMapStage = safe(stage);
        }
    }

    public static void mapRendered(UUID jobId, long tileX, long tileZ, int pngBytes) {
        synchronized (LOCK) {
            if (jobId != null && jobId.equals(activeMapJob)) clearActiveMapLocked();
        }
    }

    public static void mapFailed(UUID jobId, String reason) {
        synchronized (LOCK) {
            if (jobId != null && jobId.equals(activeMapJob)) clearActiveMapLocked();
            mapReason = safe(reason);
        }
    }

    public static void mapCancelled(UUID jobId, String reason) {
        synchronized (LOCK) {
            if (jobId != null && jobId.equals(activeMapJob)) clearActiveMapLocked();
            mapReason = safe(reason);
        }
    }

    public static void mapStatus(boolean eligible, String reason) {
        synchronized (LOCK) {
            mapEligible = eligible;
            mapReason = safe(reason);
        }
    }

    public static void disconnected() {
        synchronized (LOCK) {
            CAMERA_PHOTOS.clear();
            CAMERA_STREAMS.clear();
            CAMERA_VIDEOS.clear();
            CAMERA_FRAME_TIMES.clear();
            clearActiveMapLocked();
            mapEligible = false;
            mapReason = "нет соединения";
        }
    }

    /** Records one completed camera render for the compact frames-per-second readout. */
    public static void cameraFrameRendered() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            CAMERA_FRAME_TIMES.addLast(now);
            pruneFramesLocked(now);
        }
    }

    public static RenderStatus status() {
        synchronized (LOCK) {
            pruneFramesLocked(System.currentTimeMillis());
            return new RenderStatus(
                    CAMERA_PHOTOS.size(), CAMERA_STREAMS.size(), CAMERA_VIDEOS.size(),
                    CAMERA_FRAME_TIMES.size(), activeMapJob != null, activeMapStage, mapEligible, mapReason
            );
        }
    }

    private static void pruneFramesLocked(long now) {
        while (!CAMERA_FRAME_TIMES.isEmpty() && now - CAMERA_FRAME_TIMES.peekFirst() >= 1_000L) {
            CAMERA_FRAME_TIMES.removeFirst();
        }
    }

    private static void clearActiveMapLocked() {
        activeMapJob = null;
        activeMapStage = "ожидание";
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    public record RenderStatus(
            int activePhotos,
            int activeStreams,
            int activeVideos,
            int cameraFramesPerSecond,
            boolean mapActive,
            String mapStage,
            boolean mapEligible,
            String mapReason
    ) {
		public int activeCameraJobs() {
			return this.activePhotos + this.activeStreams + this.activeVideos;
		}
    }
}
