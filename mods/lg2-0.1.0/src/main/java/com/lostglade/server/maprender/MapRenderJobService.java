package com.lostglade.server.maprender;

import com.lostglade.Lg2;
import com.lostglade.config.Lg2Config;
import com.lostglade.network.YandexMapRenderPayloads;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase-5 single-tile lease transport. Automatic scheduling deliberately does
 * not live here yet: this service proves the disk-snapshot -> vanilla-client ->
 * validated-PNG path with an explicit administrator request first.
 */
public final class MapRenderJobService {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
	private static final long OFFER_LEASE_MS = 30_000L;
	private static final long RENDER_LEASE_MS = 120_000L;
	private static final Map<UUID, PendingJob> JOBS = new ConcurrentHashMap<>();

	private static volatile ExecutorService snapshotExecutor;

	private MapRenderJobService() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(
				YandexMapRenderPayloads.MapRenderJobDecisionC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server != null) {
						server.execute(() -> handleDecision(server, context.player(), payload));
					}
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				YandexMapRenderPayloads.MapRenderResultC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server != null) {
						server.execute(() -> handleResult(server, context.player(), payload));
					}
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				YandexMapRenderPayloads.MapRenderFailureC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server != null) {
						server.execute(() -> handleFailure(context.player(), payload));
					}
				}
		);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> cancelWorkerJobs(handler.player.getUUID(), "worker-disconnected")));
		ServerTickEvents.END_SERVER_TICK.register(MapRenderJobService::expireLeases);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			var tileX = Commands.argument("tileX", LongArgumentType.longArg());
			var tileZ = Commands.argument("tileZ", LongArgumentType.longArg())
					.executes(context -> offerManualTile(
							context.getSource(),
							LongArgumentType.getLong(context, "tileX"),
							LongArgumentType.getLong(context, "tileZ")
					));
		tileX.then(tileZ);
		dispatcher.register(
				Commands.literal("yandexmaprender")
						.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
						.then(Commands.literal("tile").then(tileX))
		);
		});
	}

	static int activeJobCount() {
		return JOBS.size();
	}

	static boolean hasActiveJobForWorker(UUID workerUuid) {
		return workerUuid != null && JOBS.values().stream().anyMatch(job -> job.workerUuid.equals(workerUuid));
	}

	static java.util.Set<MapTileKey> activeTileKeys() {
		return JOBS.values().stream().map(job -> job.key).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	static boolean tryOfferAutomatic(MinecraftServer server, MapRenderWorkerRegistry.WorkerState worker, MapRenderInventory.TileStatus tile) {
		if (server == null || worker == null || tile == null || tile.current()) return false;
		return offerTile(server, worker, tile.key(), tile.source(), null);
	}

	private static int offerManualTile(CommandSourceStack source, long tileX, long tileZ) {
		MinecraftServer server = source.getServer();
		YandexMapRenderService.DiscoverySnapshot discovery = YandexMapRenderService.discoverySnapshot();
		if (discovery == null || YandexMapRenderService.status().state() != YandexMapRenderService.State.READY) {
			source.sendFailure(Component.literal("Yandex map v2 inventory is not ready."));
			return 0;
		}
		MapRenderWorkerRegistry.WorkerState worker = MapRenderWorkerRegistry.findEligibleDedicated(server);
		if (worker == null) {
			source.sendFailure(Component.literal("No eligible dedicated Yandex map renderer is online."));
			return 0;
		}
		MapTileKey key = new MapTileKey(OVERWORLD, tileX, tileZ, MapRenderProfile.CURRENT.version());
		MapRenderInventory.TileStatus tile = discovery.inventory().tiles().stream().filter(status -> status.key().equals(key)).findFirst().orElse(null);
		if (tile == null) {
			source.sendFailure(Component.literal("Tile " + tileX + "," + tileZ + " contains no existing Overworld chunks."));
			return 0;
		}
		boolean offered = offerTile(server, worker, key, tile.source(), source);
		return offered ? 1 : 0;
	}

	private static boolean offerTile(
			MinecraftServer server,
			MapRenderWorkerRegistry.WorkerState worker,
			MapTileKey key,
			MapTileSourceState sourceState,
			CommandSourceStack feedback
	) {
		YandexMapRenderService.DiscoverySnapshot discovery = YandexMapRenderService.discoverySnapshot();
		if (discovery == null || YandexMapRenderService.status().state() != YandexMapRenderService.State.READY
				|| worker == null || !worker.eligible() || sourceState == null
				|| !Objects.equals(worker.profileHash(), discovery.profileHash())
				|| hasActiveJobForWorker(worker.playerUuid())
				|| JOBS.values().stream().anyMatch(job -> job.key.equals(key))) {
			return false;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(worker.playerUuid());
		if (player == null || !ServerPlayNetworking.canSend(player, YandexMapRenderPayloads.MapRenderJobOfferS2CPayload.TYPE)) return false;

		UUID jobId = UUID.randomUUID();
		long now = System.currentTimeMillis();
		PendingJob job = new PendingJob(server, jobId, worker.playerUuid(), key, worker.profileHash(), sourceState, now, now + OFFER_LEASE_MS);
		JOBS.put(jobId, job);
		ServerPlayNetworking.send(player, new YandexMapRenderPayloads.MapRenderJobOfferS2CPayload(
				jobId, key.tileX(), key.tileZ(), job.profileHash, job.expiresAtEpochMs));
		if (feedback != null) {
			feedback.sendSuccess(() -> Component.literal("Yandex map v2 offered tile " + key.tileX() + "," + key.tileZ() + " to " + worker.playerName() + " (job " + jobId + ")."), false);
		}
		Lg2.LOGGER.debug("Yandex map v2 scheduler offered tile {} to {} (job {})", key, worker.playerName(), jobId);
		return true;
	}

	private static void handleDecision(
			MinecraftServer server,
			ServerPlayer player,
			YandexMapRenderPayloads.MapRenderJobDecisionC2SPayload payload
	) {
		PendingJob job = validOwnedJob(player, payload.jobId());
		if (job == null || job.phase != JobPhase.OFFERED) {
			return;
		}
		if (!payload.accepted()) {
			JOBS.remove(job.jobId, job);
			MapRenderScheduler.registerDecline(job.key);
			MapRenderScheduler.registerWorkerDecline(job.workerUuid, payload.reason());
			if (isExpectedDecline(payload.reason())) {
				Lg2.LOGGER.debug("Yandex map v2 job {} declined by {}: {}", job.jobId, player.getScoreboardName(), payload.reason());
			} else {
				Lg2.LOGGER.info("Yandex map v2 job {} rejected by {}: {}", job.jobId, player.getScoreboardName(), payload.reason());
			}
			return;
		}
		if (!isWorkerStillCompatible(job, player)) {
			failJob(job, "worker-profile-changed");
			return;
		}
		job.phase = JobPhase.PREPARING;
		job.expiresAtEpochMs = System.currentTimeMillis() + RENDER_LEASE_MS;
		prepareSnapshotAsync(server, player, job);
	}

	private static void prepareSnapshotAsync(MinecraftServer server, ServerPlayer worker, PendingJob job) {
		YandexMapRenderService.DiscoverySnapshot discovery = YandexMapRenderService.discoverySnapshot();
		if (discovery == null || !job.profileHash.equals(discovery.profileHash())) {
			failJob(job, "inventory-profile-changed");
			return;
		}
		ServerLevel level = server.overworld();
		Path worldRoot = server.getWorldPath(LevelResource.ROOT);
		CompletableFuture
				.supplyAsync(() -> {
					try {
						return MapSnapshotRepository.readTile(
								level,
								worldRoot,
								discovery.index(),
								discovery.entityIndex(),
								job.key,
								MapRenderProfile.CURRENT
						);
					} catch (Exception exception) {
						throw new IllegalStateException(exception);
					}
				}, ensureSnapshotExecutor())
				.whenComplete((snapshot, throwable) -> server.execute(() -> {
					if (JOBS.get(job.jobId) != job || job.phase != JobPhase.PREPARING) {
						return;
					}
					if (throwable != null || snapshot == null) {
						Throwable root = rootCause(throwable);
						if (root instanceof MapSnapshotRepository.NoRenderableSourceChunksException) {
							MapRenderScheduler.registerUnrenderable(job.key, job.sourceState == null ? null : job.sourceState.sourceFingerprint());
							cancelJob(job, "no-renderable-source-chunks");
							return;
						}
						failJob(job, "snapshot-failed:" + shortError(throwable));
						return;
					}
					ServerPlayer currentWorker = server.getPlayerList().getPlayer(job.workerUuid);
					if (currentWorker == null || !isWorkerStillCompatible(job, currentWorker)) {
						failJob(job, "worker-unavailable-after-snapshot");
						return;
					}
					try {
						MapSnapshotPacketBuilder.PacketBatch batch = MapSnapshotPacketBuilder.build(level, currentWorker, snapshot);
						if (coalesceVisuallyUnchanged(server, job, snapshot, batch)) {
							return;
						}
						sendPreparedScene(level, currentWorker, job, snapshot, batch);
					} catch (Exception exception) {
						Lg2.LOGGER.warn("Yandex map v2 failed to encode tile {} job {}", job.key, job.jobId, exception);
						failJob(job, "packet-build-failed:" + shortError(exception));
					}
				}));
	}

	private static boolean coalesceVisuallyUnchanged(
			MinecraftServer server,
			PendingJob job,
			MapTileSnapshot snapshot,
			MapSnapshotPacketBuilder.PacketBatch batch
	) throws Exception {
		Path storeRoot = server.getWorldPath(LevelResource.ROOT).resolve("lostglade/yandex_maps/v2");
		MapTileStore store = new MapTileStore(storeRoot);
		var previous = store.read(job.key, job.profileHash);
		if (previous.isEmpty()) return false;
		MapTileMetadata metadata = previous.get().metadata();
		if (metadata.visualFingerprint() == null
				|| metadata.visualFingerprint().isBlank()
				|| !metadata.visualFingerprint().equals(batch.visualFingerprint())) {
			return false;
		}

		MapTileSourceState source = snapshot.manifest().sourceState();
		MapTileMetadata refreshedMetadata = store.refreshSourceFingerprint(job.key, job.profileHash, source, batch.visualFingerprint());
		job.sourceState = source;
		job.visualFingerprint = batch.visualFingerprint();
		JOBS.remove(job.jobId, job);
		MapRenderScheduler.registerSuccess(job.key);
		MapRenderScheduler.registerWorkerSuccess(job.workerUuid);
		sendCancel(job, "visual-unchanged");
		Lg2.LOGGER.debug("Yandex map v2 coalesced storage-only change for tile {} job {}; GPU render skipped", job.key, job.jobId);
		YandexMapRenderService.markTileCurrent(job.key, job.profileHash, source, refreshedMetadata);
		return true;
	}

	private static void sendPreparedScene(
			ServerLevel level,
			ServerPlayer worker,
			PendingJob job,
			MapTileSnapshot snapshot,
			MapSnapshotPacketBuilder.PacketBatch batch
	) {
		if (!ServerPlayNetworking.canSend(worker, YandexMapRenderPayloads.MapRenderSceneStartS2CPayload.TYPE)) {
			failJob(job, "worker-missing-scene-protocol");
			return;
		}
		String dimensionTypeId = level.dimensionTypeRegistration().unwrapKey()
				.orElseThrow(() -> new IllegalStateException("Overworld dimension type has no registry key"))
				.identifier()
				.toString();
		job.snapshotFingerprint = snapshot.manifest().snapshotFingerprint();
		job.visualFingerprint = batch.visualFingerprint();
		job.sourceState = snapshot.manifest().sourceState();
		job.phase = JobPhase.RENDERING;
		job.expiresAtEpochMs = System.currentTimeMillis() + RENDER_LEASE_MS;

		ServerPlayNetworking.send(worker, new YandexMapRenderPayloads.MapRenderSceneStartS2CPayload(
				job.jobId,
				level.dimension().identifier().toString(),
				dimensionTypeId,
				level.getSeed(),
				level.getSeaLevel(),
				job.key.tileX(),
				job.key.tileZ(),
				MapRenderProfile.CURRENT.tileBlocks(),
				MapRenderProfile.CURRENT.tilePixels(),
				6,
				job.snapshotFingerprint,
				batch.chunkPackets().size(),
				batch.itemDisplayPackets().size()
		));
		int index = 0;
		for (MapSnapshotPacketBuilder.MapEncodedPacket packet : batch.chunkPackets()) {
			ServerPlayNetworking.send(worker, new YandexMapRenderPayloads.MapRenderSceneChunkS2CPayload(job.jobId, index++, packet.packetBytes()));
		}
		index = 0;
		for (MapSnapshotPacketBuilder.MapEncodedPacket packet : batch.itemDisplayPackets()) {
			ServerPlayNetworking.send(worker, new YandexMapRenderPayloads.MapRenderSceneEntityS2CPayload(
					job.jobId,
					index++,
					packet.packetTypeId(),
					packet.packetBytes()
			));
		}
		ServerPlayNetworking.send(worker, new YandexMapRenderPayloads.MapRenderSceneReadyS2CPayload(job.jobId));
		Lg2.LOGGER.info(
				"Yandex map v2 job {} sent tile {} to {}: {} chunk packets, {} ItemDisplay packets",
				job.jobId,
				job.key,
				worker.getScoreboardName(),
				batch.chunkPackets().size(),
				batch.itemDisplayPackets().size()
		);
	}

	private static void handleResult(
			MinecraftServer server,
			ServerPlayer player,
			YandexMapRenderPayloads.MapRenderResultC2SPayload payload
	) {
		PendingJob job = validOwnedJob(player, payload.jobId());
		if (job == null || job.phase != JobPhase.RENDERING) {
			return;
		}
		if (!isWorkerStillCompatible(job, player)) {
			failJob(job, "worker-profile-changed-before-result");
			return;
		}
		if (job.snapshotFingerprint == null || !job.snapshotFingerprint.equals(payload.snapshotFingerprint())) {
			failWorkerJob(job, "snapshot-fingerprint-mismatch");
			return;
		}
		MapTilePngValidator.ValidationResult validation = MapTilePngValidator.validate(payload.pngBytes(), MapRenderProfile.CURRENT.tilePixels());
		if (!validation.accepted()) {
			failWorkerJob(job, "server-validator:" + validation.reason());
			return;
		}
		if (job.sourceState == null) {
			failJob(job, "missing-assigned-source-state");
			return;
		}
		try {
			Path storeRoot = server.getWorldPath(LevelResource.ROOT).resolve("lostglade/yandex_maps/v2");
			MapTileStore store = new MapTileStore(storeRoot);
			MapTileMetadata committedMetadata = store.commitBaseTile(
					job.key,
					job.profileHash,
					MapRenderProfile.CURRENT,
					job.sourceState,
					job.visualFingerprint,
					payload.pngBytes(),
					job.createdAtEpochMs,
					player.getUUID().toString()
			);
			JOBS.remove(job.jobId, job);
			MapRenderScheduler.registerSuccess(job.key);
			MapRenderScheduler.registerWorkerSuccess(job.workerUuid);
			MapPyramidBuilder.onBaseTileCommitted(server, job.key, job.profileHash);
			Lg2.LOGGER.info(
					"Yandex map v2 committed tile {} from {} job {} (mean={}, variance={}, dominant={})",
					job.key,
					player.getScoreboardName(),
					job.jobId,
					validation.meanBrightness(),
					validation.brightnessVariance(),
					validation.dominantColorFraction()
			);
			YandexMapRenderService.markTileCurrent(job.key, job.profileHash, job.sourceState, committedMetadata);
		} catch (Exception exception) {
			Lg2.LOGGER.warn("Yandex map v2 failed to commit job {} tile {}", job.jobId, job.key, exception);
			failJob(job, "commit-failed:" + shortError(exception));
		}
	}

	private static void handleFailure(ServerPlayer player, YandexMapRenderPayloads.MapRenderFailureC2SPayload payload) {
		PendingJob job = validOwnedJob(player, payload.jobId());
		if (job == null) {
			return;
		}
		Lg2.LOGGER.warn("Yandex map v2 worker {} failed job {} tile {}: {}", player.getScoreboardName(), job.jobId, job.key, payload.reason());
		JOBS.remove(job.jobId, job);
		MapRenderScheduler.registerFailure(job.key);
		MapRenderScheduler.registerWorkerFailure(job.workerUuid);
	}

	private static PendingJob validOwnedJob(ServerPlayer player, UUID jobId) {
		if (player == null || jobId == null) {
			return null;
		}
		PendingJob job = JOBS.get(jobId);
		if (job == null || !job.workerUuid.equals(player.getUUID()) || System.currentTimeMillis() > job.expiresAtEpochMs) {
			return null;
		}
		return job;
	}

	private static boolean isWorkerStillCompatible(PendingJob job, ServerPlayer player) {
		MapRenderWorkerRegistry.WorkerState current = MapRenderWorkerRegistry.worker(player.getUUID());
		return current != null
				&& current.eligible()
				&& job.profileHash.equals(current.profileHash());
	}

	private static void expireLeases(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (PendingJob job : JOBS.values()) {
			if (now > job.expiresAtEpochMs && JOBS.remove(job.jobId, job)) {
				MapRenderScheduler.registerFailure(job.key);
				MapRenderScheduler.registerWorkerFailure(job.workerUuid);
				sendCancel(job, "lease-expired");
				Lg2.LOGGER.warn("Yandex map v2 expired job {} tile {} in phase {}", job.jobId, job.key, job.phase);
			}
		}
	}

	private static void cancelWorkerJobs(UUID workerUuid, String reason) {
		for (PendingJob job : JOBS.values()) {
			if (job.workerUuid.equals(workerUuid) && JOBS.remove(job.jobId, job)) {
				Lg2.LOGGER.info("Yandex map v2 cancelled job {}: {}", job.jobId, reason);
			}
		}
	}

	private static void failWorkerJob(PendingJob job, String reason) {
		if (job != null) {
			MapRenderScheduler.registerWorkerFailure(job.workerUuid);
		}
		failJob(job, reason);
	}

	private static void failJob(PendingJob job, String reason) {
		if (job != null && JOBS.remove(job.jobId, job)) {
			MapRenderScheduler.registerFailure(job.key);
			sendCancel(job, reason);
			Lg2.LOGGER.warn("Yandex map v2 job {} tile {} failed: {}", job.jobId, job.key, reason);
		}
	}

	private static void cancelJob(PendingJob job, String reason) {
		if (job != null && JOBS.remove(job.jobId, job)) {
			sendCancel(job, reason);
			Lg2.LOGGER.debug("Yandex map v2 cancelled job {} tile {}: {}", job.jobId, job.key, reason);
		}
	}

	private static void sendCancel(PendingJob job, String reason) {
		if (job == null || job.server == null) return;
		ServerPlayer worker = job.server.getPlayerList().getPlayer(job.workerUuid);
		if (worker != null && ServerPlayNetworking.canSend(worker, YandexMapRenderPayloads.MapRenderJobCancelS2CPayload.TYPE)) {
			ServerPlayNetworking.send(worker, new YandexMapRenderPayloads.MapRenderJobCancelS2CPayload(job.jobId, reason));
		}
	}

	private static boolean isExpectedDecline(String reason) {
		return "worker-busy".equals(reason)
				|| "fps-below-threshold".equals(reason)
				|| "client-not-idle".equals(reason)
				|| "client-rate-limit".equals(reason);
	}

	private static Throwable rootCause(Throwable throwable) {
		Throwable current = throwable;
		while (current != null && current.getCause() != null && current.getCause() != current) current = current.getCause();
		return current;
	}

	private static String shortError(Throwable throwable) {
		if (throwable == null) {
			return "unknown";
		}
		Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

	private static synchronized ExecutorService ensureSnapshotExecutor() {
		if (snapshotExecutor == null || snapshotExecutor.isShutdown()) {
			int threads = Math.max(1, Lg2Config.get().yandexMapSnapshotThreads);
			snapshotExecutor = Executors.newFixedThreadPool(threads, runnable -> {
				Thread thread = new Thread(runnable, "lg2-yandex-map-snapshot");
				thread.setDaemon(true);
				return thread;
			});
		}
		return snapshotExecutor;
	}

	private static synchronized void stop() {
		JOBS.clear();
		if (snapshotExecutor != null) {
			snapshotExecutor.shutdownNow();
			snapshotExecutor = null;
		}
	}

	private enum JobPhase {
		OFFERED,
		PREPARING,
		RENDERING
	}

	private static final class PendingJob {
		private final MinecraftServer server;
		private final UUID jobId;
		private final UUID workerUuid;
		private final MapTileKey key;
		private final String profileHash;
		private final long createdAtEpochMs;
		private volatile MapTileSourceState sourceState;
		private volatile String snapshotFingerprint;
		private volatile String visualFingerprint;
		private volatile long expiresAtEpochMs;
		private volatile JobPhase phase = JobPhase.OFFERED;

		private PendingJob(
				MinecraftServer server,
				UUID jobId,
				UUID workerUuid,
				MapTileKey key,
				String profileHash,
				MapTileSourceState sourceState,
				long createdAtEpochMs,
				long expiresAtEpochMs
		) {
			this.server = server;
			this.jobId = jobId;
			this.workerUuid = workerUuid;
			this.key = key;
			this.profileHash = profileHash;
			this.sourceState = sourceState;
			this.createdAtEpochMs = createdAtEpochMs;
			this.expiresAtEpochMs = expiresAtEpochMs;
		}
	}
}
