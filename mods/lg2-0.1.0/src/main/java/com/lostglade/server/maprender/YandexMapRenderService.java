package com.lostglade.server.maprender;

import com.lostglade.Lg2;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase-1 read-only discovery service for the replacement Yandex map renderer.
 *
 * <p>It reads only the Overworld region headers and the v2 tile store. It does
 * not have access to gameplay chunk loading, rendering or renderer-bot state.
 */
public final class YandexMapRenderService {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
	private static final String STORE_DIRECTORY = "lostglade/yandex_maps/v2";
	private static final AtomicLong SCAN_GENERATION = new AtomicLong();
	private static final Map<MapTileKey, CommitOverlay> RECENT_COMMITS = new ConcurrentHashMap<>();

	private static volatile ExecutorService scanExecutor;
	private static volatile DiscoverySnapshot discoverySnapshot;
	private static volatile Status status = Status.idle();

	private YandexMapRenderService() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(YandexMapRenderService::refreshAsync);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
	}

	public static void refreshAsync(MinecraftServer server) {
		if (server == null) {
			return;
		}
		Path worldRoot = server.getWorldPath(LevelResource.ROOT);
		Path regionDirectory = worldRoot.resolve("region");
		Path entityDirectory = worldRoot.resolve("entities");
		Path storeRoot = worldRoot.resolve(STORE_DIRECTORY);
		MapRenderProfile profile = MapRenderProfile.CURRENT;
		String canonicalProfile = MapRenderWorkerRegistry.canonicalProfileHash();
		String profileHash = canonicalProfile == null || canonicalProfile.isBlank()
				? profile.contractHash()
				: canonicalProfile;
		long generation = SCAN_GENERATION.incrementAndGet();
		long startedAt = System.currentTimeMillis();
		status = new Status(State.SCANNING, startedAt, 0L, 0, 0, 0L, 0L, null);

		CompletableFuture
				.supplyAsync(() -> discover(regionDirectory, entityDirectory, storeRoot, profile, profileHash, startedAt), ensureExecutor())
				.whenComplete((snapshot, throwable) -> {
					if (SCAN_GENERATION.get() != generation) {
						return;
					}
					if (throwable != null) {
						String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
						status = new Status(State.FAILED, startedAt, System.currentTimeMillis(), 0, 0, 0L, 0L, message);
						Lg2.LOGGER.warn("Yandex map v2 read-only discovery failed", throwable);
						return;
					}
					synchronized (YandexMapRenderService.class) {
						snapshot = overlayRecentCommits(snapshot);
						discoverySnapshot = snapshot;
					}
					MapRenderInventory.Snapshot inventory = snapshot.inventory();
					status = new Status(
							State.READY,
							startedAt,
							snapshot.completedAtEpochMs(),
							snapshot.index().chunkCount(),
							inventory.tiles().size(),
							inventory.currentCount(),
							inventory.renderNeededCount(),
							null
					);
					MapRenderScheduler.onDiscovery(snapshot);
					Lg2.LOGGER.info(
							"Yandex map v2 discovery: {} existing chunks, {} base tiles, {} current, {} need render",
							status.existingChunks(),
							status.baseTiles(),
							status.currentTiles(),
							status.renderNeededTiles()
					);
				});
	}

	/** Publishes one successful tile commit without rescanning all region headers. */
	public static synchronized void markTileCurrent(
			MapTileKey key,
			String profileHash,
			MapTileSourceState source,
			MapTileMetadata metadata
	) {
		if (key == null || profileHash == null || source == null || metadata == null) return;
		RECENT_COMMITS.put(key, new CommitOverlay(profileHash, source, metadata));
		DiscoverySnapshot current = discoverySnapshot;
		if (current == null || !profileHash.equals(current.profileHash())) return;
		DiscoverySnapshot updated = overlayRecentCommits(current);
		discoverySnapshot = updated;
		MapRenderInventory.Snapshot inventory = updated.inventory();
		status = new Status(
				State.READY,
				status.scanStartedAtEpochMs(),
				updated.completedAtEpochMs(),
				updated.index().chunkCount(),
				inventory.tiles().size(),
				inventory.currentCount(),
				inventory.renderNeededCount(),
				null
		);
		MapRenderScheduler.onDiscovery(updated);
	}

	private static DiscoverySnapshot overlayRecentCommits(DiscoverySnapshot snapshot) {
		if (snapshot == null || RECENT_COMMITS.isEmpty()) return snapshot;
		List<MapRenderInventory.TileStatus> tiles = new ArrayList<>(snapshot.inventory().tiles().size());
		boolean changed = false;
		for (MapRenderInventory.TileStatus tile : snapshot.inventory().tiles()) {
			CommitOverlay overlay = RECENT_COMMITS.get(tile.key());
			if (overlay != null
					&& snapshot.profileHash().equals(overlay.profileHash())
					&& tile.source().sourceFingerprint().equals(overlay.source().sourceFingerprint())
					&& tile.source().existingChunkMask() == overlay.source().existingChunkMask()) {
				tiles.add(new MapRenderInventory.TileStatus(tile.key(), tile.source(), overlay.metadata(), true, null));
				changed = true;
			} else {
				tiles.add(tile);
			}
		}
		if (!changed) return snapshot;
		return new DiscoverySnapshot(
				snapshot.regionDirectory(), snapshot.entityDirectory(), snapshot.storeRoot(),
				snapshot.profile(), snapshot.profileHash(), snapshot.index(), snapshot.entityIndex(),
				new MapRenderInventory.Snapshot(List.copyOf(tiles)),
				snapshot.startedAtEpochMs(), System.currentTimeMillis()
		);
	}

	public static Status status() {
		return status;
	}

	public static DiscoverySnapshot discoverySnapshot() {
		return discoverySnapshot;
	}

	private static DiscoverySnapshot discover(
			Path regionDirectory,
			Path entityDirectory,
			Path storeRoot,
			MapRenderProfile profile,
			String profileHash,
			long startedAt
	) {
		try {
			MapChunkExistenceIndex index = MapChunkExistenceIndex.scan(regionDirectory);
			MapChunkExistenceIndex entityIndex = MapChunkExistenceIndex.scan(entityDirectory);
			MapTileStore store = new MapTileStore(storeRoot);
			MapRenderInventory.Snapshot inventory = MapRenderInventory.inspect(index, entityIndex, store, OVERWORLD, profile, profileHash);
			return new DiscoverySnapshot(
					regionDirectory,
					entityDirectory,
					storeRoot,
					profile,
					profileHash,
					index,
					entityIndex,
					inventory,
					startedAt,
					System.currentTimeMillis()
			);
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to scan Overworld region headers", exception);
		}
	}

	private static synchronized ExecutorService ensureExecutor() {
		if (scanExecutor == null || scanExecutor.isShutdown()) {
			scanExecutor = Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "lg2-yandex-map-index");
				thread.setDaemon(true);
				return thread;
			});
		}
		return scanExecutor;
	}

	private static synchronized void stop() {
		SCAN_GENERATION.incrementAndGet();
		discoverySnapshot = null;
		RECENT_COMMITS.clear();
		status = new Status(State.STOPPED, 0L, System.currentTimeMillis(), 0, 0, 0L, 0L, null);
		if (scanExecutor != null) {
			scanExecutor.shutdownNow();
			scanExecutor = null;
		}
	}

	public enum State {
		IDLE,
		SCANNING,
		READY,
		FAILED,
		STOPPED
	}

	public record Status(
			State state,
			long scanStartedAtEpochMs,
			long scanCompletedAtEpochMs,
			int existingChunks,
			int baseTiles,
			long currentTiles,
			long renderNeededTiles,
			String failure
	) {
		private static Status idle() {
			return new Status(State.IDLE, 0L, 0L, 0, 0, 0L, 0L, null);
		}
	}

	private record CommitOverlay(String profileHash, MapTileSourceState source, MapTileMetadata metadata) {
	}

	public record DiscoverySnapshot(
			Path regionDirectory,
			Path entityDirectory,
			Path storeRoot,
			MapRenderProfile profile,
			String profileHash,
			MapChunkExistenceIndex index,
			MapChunkExistenceIndex entityIndex,
			MapRenderInventory.Snapshot inventory,
			long startedAtEpochMs,
			long completedAtEpochMs
	) {
	}
}
