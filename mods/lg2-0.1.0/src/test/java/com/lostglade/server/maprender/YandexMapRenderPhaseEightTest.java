package com.lostglade.server.maprender;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class YandexMapRenderPhaseEightTest {
	private YandexMapRenderPhaseEightTest() {
	}

	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		workerCooldownBacksOffAndRecovers();
		jobLifecycleSeparatesWorkerAndTileFailures();
		leaseCancellationAndProtoChunkPolicyAreExplicit();
		semanticFreshnessSkipsStorageOnlyGpuWork();
		productionSourceHasNoSmokeTriggerOrInfoReadinessSpam();
		commitsDoNotRescanTheWholeWorld();
		distributedSchedulerUsesManyWorkersWithoutDuplicateTiles();
		System.out.println("Yandex map renderer Phase 8 hardening checks passed");
	}

	private static void workerCooldownBacksOffAndRecovers() {
		require(MapRenderScheduler.workerCooldownMillisForFailureCount(1) == 5_000L, "first worker failure must have short cooldown");
		require(MapRenderScheduler.workerCooldownMillisForFailureCount(2) == 15_000L, "second worker failure must back off longer");
		require(MapRenderScheduler.workerCooldownMillisForFailureCount(3) == 30_000L, "third worker failure must start exponential cooldown");
		require(MapRenderScheduler.workerCooldownMillisForFailureCount(4) == 60_000L, "repeated failures must increase cooldown");
		require(MapRenderScheduler.workerCooldownMillisForFailureCount(20) == 300_000L, "worker cooldown must be capped");

		UUID worker = UUID.randomUUID();
		require(MapRenderScheduler.workerAvailable(worker, System.currentTimeMillis()), "fresh worker must be schedulable");
		MapRenderScheduler.registerWorkerFailure(worker);
		require(!MapRenderScheduler.workerAvailable(worker, System.currentTimeMillis()), "failed worker must cool down");
		MapRenderScheduler.registerWorkerSuccess(worker);
		require(MapRenderScheduler.workerAvailable(worker, System.currentTimeMillis()), "successful worker must recover immediately");
	}

	private static void jobLifecycleSeparatesWorkerAndTileFailures() throws Exception {
		Path root = Path.of("").toAbsolutePath();
		String jobs = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapRenderJobService.java"));
		String scheduler = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapRenderScheduler.java"));

		require(jobs.contains("MapRenderScheduler.registerWorkerSuccess(job.workerUuid)"), "valid commit must restore worker health");
		require(jobs.contains("failWorkerJob(job, \"server-validator:"), "invalid worker framebuffer must penalize worker separately");
		require(jobs.contains("failWorkerJob(job, \"snapshot-fingerprint-mismatch\")"), "bad result identity must penalize worker");
		require(count(jobs, "MapRenderScheduler.registerWorkerFailure(job.workerUuid)") >= 3, "client failure, lease expiry and worker-job failure must all feed worker health");
		require(jobs.contains("MapRenderScheduler.registerDecline(job.key)"), "volunteer decline must remain a short tile offer cooldown");
		require(scheduler.contains("if (!workerAvailable(worker.playerUuid(), now)) continue;"), "scheduler must skip workers while cooling down");
	}

	private static void leaseCancellationAndProtoChunkPolicyAreExplicit() throws Exception {
		Path root = Path.of("").toAbsolutePath();
		String payloads = Files.readString(root.resolve("src/main/java/com/lostglade/network/YandexMapRenderPayloads.java"));
		String jobs = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapRenderJobService.java"));
		String scheduler = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapRenderScheduler.java"));
		String repository = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapSnapshotRepository.java"));
		String client = Files.readString(root.resolve("src/client/java/com/lostglade/client/maprender/YandexMapRenderClient.java"));

		require(payloads.contains("PROTOCOL_VERSION = 6"), "distributed detail-tile contract must use map protocol v6");
		require(payloads.contains("int tileBlocks") && payloads.contains("buffer.writeVarInt(this.tileBlocks)"), "scene-start wire contract must carry canonical world span");
		require(payloads.contains("MapRenderJobCancelS2CPayload.TYPE"), "server-to-client job cancel payload must be registered");
		require(client.contains("handleCancel(payload)") && client.contains("private static void handleCancel"), "client must handle server job cancellation");
		require(client.contains("retireActiveJob(\"server-cancel\")"), "server cancellation must release the logical active job without tearing down an in-flight GL readback");
		require(client.contains("RETIRED_JOBS") && client.contains("readbackPending()"), "cancelled client render state must be retired after pending readback settles");
		require(jobs.contains("sendCancel(job, reason)"), "server-side job failure must close the client lease");
		require(jobs.contains("sendCancel(job, \"lease-expired\")"), "lease expiry must explicitly cancel the client job");
		require(jobs.contains("MapRenderScheduler.registerWorkerDecline(job.workerUuid, payload.reason())"), "expected volunteer declines must cool down the worker without marking it failed");
		require(scheduler.contains("blockedFingerprint") && scheduler.contains("registerUnrenderable"), "all-proto tiles must be blocked until their source fingerprint changes");
		require(repository.contains("status.isBefore(ChunkStatus.FULL)") && repository.contains("renderableSourceChunks"), "proto chunks must be filtered before scene construction");
		require(repository.contains("NoRenderableSourceChunksException"), "tile with no FULL source chunks must have a dedicated non-retryable snapshot outcome");
	}

	private static void semanticFreshnessSkipsStorageOnlyGpuWork() throws Exception {
		Path root = Path.of("").toAbsolutePath();
		String jobs = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapRenderJobService.java"));
		String packets = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapSnapshotPacketBuilder.java"));
		String store = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapTileStore.java"));
		String manifest = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapSnapshotManifest.java"));

		require(packets.contains("String visualFingerprint"), "packet batch must expose semantic scene identity");
		require(packets.contains("chunkSnapshot.role() == MapSnapshotManifest.Role.SOURCE"), "semantic fingerprint must be scoped to source chunks");
		require(packets.contains("updateVisualDigest(visualDigest, (byte) 1, encoded)"), "vanilla source chunk packets must feed semantic fingerprint");
		require(packets.contains("updateVisualDigest(visualDigest, (byte) 2, encoded)"), "source ItemDisplay packets must feed semantic fingerprint");
		require(jobs.contains("coalesceVisuallyUnchanged"), "storage-only changes must have a pre-GPU coalesce path");
		require(jobs.contains("sendCancel(job, \"visual-unchanged\")"), "coalesced job must release the accepted client lease");
		require(store.contains("refreshSourceFingerprint"), "tile store must atomically advance cheap freshness without replacing PNG");
		require(manifest.contains("filter(entry -> entry.role() == Role.SOURCE)"), "halo storage churn must not own base-tile freshness");
	}

	private static void productionSourceHasNoSmokeTriggerOrInfoReadinessSpam() throws Exception {
		Path root = Path.of("").toAbsolutePath();
		String jobs = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapRenderJobService.java"));
		String client = Files.readString(root.resolve("src/client/java/com/lostglade/client/maprender/YandexMapRenderClient.java"));
		String registry = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapRenderWorkerRegistry.java"));

		require(!jobs.contains("lg2.yandexMapSmokeTile"), "temporary smoke property must not survive production hardening");
		require(!jobs.contains("maybeOfferSmokeTile"), "temporary smoke scheduler hook must be removed");
		require(client.contains("LOGGER.debug(\"Yandex map v2 job {} readiness"), "readiness telemetry must remain available at DEBUG");
		require(!client.contains("LOGGER.info(\"Yandex map v2 job {} readiness"), "per-section readiness must not spam INFO logs");
		require(registry.contains("return previousCanonical == null ? \"\" : previousCanonical;"), "temporary worker outage must preserve readable cache namespace");
	}

	private static void commitsDoNotRescanTheWholeWorld() throws Exception {
		Path root = Path.of("").toAbsolutePath();
		String jobs = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapRenderJobService.java"));
		String service = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/YandexMapRenderService.java"));
		require(!jobs.contains("YandexMapRenderService.refreshAsync(server)"), "individual tile commit/coalesce must never rescan all MCA headers");
		require(count(jobs, "YandexMapRenderService.markTileCurrent(") >= 2, "commit and semantic coalesce must publish incremental inventory state");
		require(service.contains("RECENT_COMMITS") && service.contains("overlayRecentCommits"), "background scans must merge concurrent tile commits instead of overwriting them stale");
	}

	private static void distributedSchedulerUsesManyWorkersWithoutDuplicateTiles() throws Exception {
		Path root = Path.of("").toAbsolutePath();
		String scheduler = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapRenderScheduler.java"));
		String jobs = Files.readString(root.resolve("src/main/java/com/lostglade/server/maprender/MapRenderJobService.java"));
		String client = Files.readString(root.resolve("src/client/java/com/lostglade/client/maprender/YandexMapRenderClient.java"));
		String config = Files.readString(root.resolve("src/main/java/com/lostglade/config/Lg2Config.java"));
		String clientSettings = Files.readString(root.resolve("src/client/java/com/lostglade/client/LostgladeClientSettings.java"));
		require(scheduler.contains("for (MapRenderWorkerRegistry.WorkerState worker : workers)"), "scheduler must fan jobs out across the eligible worker pool");
		require(scheduler.contains("activeTiles.add(candidate.key())"), "parallel dispatch must reserve each selected tile before choosing the next worker");
		require(jobs.contains("hasActiveJobForWorker(worker.playerUuid())"), "each GPU client must remain limited to one active map scene");
		require(config.contains("yandexMapRendererMaxGlobalInFlight = 32"), "default distributed pool must allow many simultaneous clients");
		require(clientSettings.contains("mapRendererMode = MapRendererMode.ALWAYS"), "ordinary Lostglade clients must join the map worker pool by default");
		require(clientSettings.contains("value.mapRendererMode = MapRendererMode.ALWAYS")
				&& clientSettings.contains("mapRendererModeConfigured"),
				"existing implicit OFF defaults must migrate once while preserving later explicit choices");
		require(client.contains("mode == LostgladeClientSettings.MapRendererMode.IDLE_ONLY"), "FPS and idle gates must be scoped to IDLE_ONLY mode");
	}

	private static int count(String haystack, String needle) {
		int count = 0;
		int from = 0;
		while ((from = haystack.indexOf(needle, from)) >= 0) {
			count++;
			from += needle.length();
		}
		return count;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
