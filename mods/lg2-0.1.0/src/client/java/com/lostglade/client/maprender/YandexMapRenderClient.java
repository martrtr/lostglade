package com.lostglade.client.maprender;

import com.lostglade.Lg2;
import com.lostglade.client.LostgladeClientSettings;
import com.lostglade.client.RendererBotClientMode;
import com.lostglade.client.RendererClientDiagnostics;
import com.lostglade.network.YandexMapRenderPayloads;
import com.lostglade.server.maprender.MapRenderProfile;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Capability plane plus isolated one-job-at-a-time map render worker. */
public final class YandexMapRenderClient {
	private static final int HEARTBEAT_TICKS = 200;
	private static final long VANILLA_SETTLE_TIMEOUT_TICKS = 100L;
	private static final ExecutorService PROFILE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "lg2-yandex-map-profile");
		thread.setDaemon(true);
		return thread;
	});
	private static final AtomicLong REFRESH_GENERATION = new AtomicLong();

	private static volatile YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload lastCapabilities;
	private static volatile WorkerStatus status = new WorkerStatus(false, "not-connected", "");
	private static volatile String localStatusReason = "not-connected";
	private static int heartbeatTicks;
	private static boolean lastShaderCompatible = true;
	private static ActiveJob activeJob;
	private static final Deque<Long> acceptedVolunteerJobs = new ArrayDeque<>();
	private static final Deque<RetiredJob> RETIRED_JOBS = new ArrayDeque<>();
	private static long clientTickSequence;

	private YandexMapRenderClient() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(
				YandexMapRenderPayloads.MapWorkerStatusS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					WorkerStatus next = new WorkerStatus(payload.eligible(), payload.reason(), payload.canonicalProfileHash());
					WorkerStatus previous = status;
					status = next;
					if (!next.equals(previous)) RendererClientDiagnostics.mapStatus(next.eligible(), next.reason());
				})
		);
		ClientPlayNetworking.registerGlobalReceiver(
				YandexMapRenderPayloads.MapRenderJobOfferS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> handleOffer(context.client(), payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				YandexMapRenderPayloads.MapRenderJobCancelS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> handleCancel(payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				YandexMapRenderPayloads.MapRenderSceneStartS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> handleSceneStart(context.client(), payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				YandexMapRenderPayloads.MapRenderSceneChunkS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> handleSceneChunk(payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				YandexMapRenderPayloads.MapRenderSceneEntityS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> handleSceneEntity(payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				YandexMapRenderPayloads.MapRenderSceneReadyS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> handleSceneReady(payload))
		);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(YandexMapRenderClient::requestCapabilityRefresh));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			RendererClientDiagnostics.disconnected();
			retireActiveJob("disconnect");
			reset();
		});
		ClientTickEvents.END_CLIENT_TICK.register(YandexMapRenderClient::tick);
	}

	public static void requestCapabilityRefresh() {
		Minecraft client = Minecraft.getInstance();
		long generation = REFRESH_GENERATION.incrementAndGet();
		boolean dedicated = RendererBotClientMode.isEnabled();
		LostgladeClientSettings.MapRendererMode mode = dedicated
				? LostgladeClientSettings.MapRendererMode.ALWAYS
				: LostgladeClientSettings.mapRendererMode();
		boolean enabled = dedicated || mode != LostgladeClientSettings.MapRendererMode.OFF;
		YandexMapShaderGuard.Compatibility shader = YandexMapShaderGuard.inspect();
		lastShaderCompatible = shader.compatible();
		if (!enabled) {
			localStatusReason = "disabled-by-client";
			send(new YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload(
					YandexMapRenderPayloads.PROTOCOL_VERSION, false, mode.name(), shader.compatible(), "",
					MapRenderProfile.CURRENT.tilePixels(), YandexMapResourceProfileFingerprint.clientBuildFingerprint()
			));
			return;
		}
		if (!shader.compatible()) {
			localStatusReason = shader.reason();
			send(new YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload(
					YandexMapRenderPayloads.PROTOCOL_VERSION, true, mode.name(), false, "",
					MapRenderProfile.CURRENT.tilePixels(), YandexMapResourceProfileFingerprint.clientBuildFingerprint()
			));
			return;
		}

		localStatusReason = "profiling-resources";
		CompletableFuture
				.supplyAsync(() -> YandexMapResourceProfileFingerprint.compute(client), PROFILE_EXECUTOR)
				.whenComplete((resourceProfileHash, throwable) -> client.execute(() -> {
					if (REFRESH_GENERATION.get() != generation) {
						return;
					}
					if (throwable != null || resourceProfileHash == null || resourceProfileHash.isBlank()) {
						localStatusReason = "resource-profile-error";
						Lg2.LOGGER.warn("Failed to build Yandex map worker resource profile", throwable);
						send(new YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload(
								YandexMapRenderPayloads.PROTOCOL_VERSION, true, mode.name(), false, "",
								MapRenderProfile.CURRENT.tilePixels(), YandexMapResourceProfileFingerprint.clientBuildFingerprint()
						));
						return;
					}
					localStatusReason = "waiting-for-server";
					send(new YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload(
							YandexMapRenderPayloads.PROTOCOL_VERSION,
							true,
							mode.name(),
							true,
							resourceProfileHash,
							MapRenderProfile.CURRENT.tilePixels(),
							YandexMapResourceProfileFingerprint.clientBuildFingerprint()
					));
				}));
	}

	public static void onResourcesReloaded() {
		YandexMapResourceProfileFingerprint.invalidate();
		requestCapabilityRefresh();
	}

	public static WorkerStatus status() {
		return status;
	}

	public static String displayStatusReason() {
		if ("profiling-resources".equals(localStatusReason)
				|| "resource-profile-error".equals(localStatusReason)
				|| "disabled-by-client".equals(localStatusReason)
				|| "shader-pack-active".equals(localStatusReason)
				|| "shader-state-unknown".equals(localStatusReason)
				|| "rendering-map-tile".equals(localStatusReason)) {
			return localStatusReason;
		}
		return status.reason();
	}

	private static void handleOffer(Minecraft client, YandexMapRenderPayloads.MapRenderJobOfferS2CPayload payload) {
		if (payload == null || System.currentTimeMillis() > payload.expiresAtEpochMs()) {
			return;
		}
		RendererClientDiagnostics.mapOffer(payload.jobId(), payload.tileX(), payload.tileZ());
		String rejectReason = offerRejectionReason(client, payload);
		if (rejectReason != null) {
			RendererClientDiagnostics.mapRejected(payload.jobId(), rejectReason);
			sendDecision(payload.jobId(), false, rejectReason);
			return;
		}
		activeJob = new ActiveJob(payload.jobId(), payload.profileHash(), payload.tileX(), payload.tileZ(), payload.expiresAtEpochMs());
		RendererClientDiagnostics.mapAccepted(payload.jobId(), payload.tileX(), payload.tileZ());
		localStatusReason = "rendering-map-tile";
		sendDecision(payload.jobId(), true, "accepted");
	}

	private static void handleCancel(YandexMapRenderPayloads.MapRenderJobCancelS2CPayload payload) {
		ActiveJob job = payload == null ? null : activeOwned(payload.jobId());
		if (job == null) return;
		Lg2.LOGGER.debug("Yandex map v2 server cancelled job {}: {}", job.jobId, payload.reason());
		RendererClientDiagnostics.mapCancelled(job.jobId, payload.reason());
		retireActiveJob("server-cancel");
		localStatusReason = "waiting-for-server";
	}

	private static String offerRejectionReason(Minecraft client, YandexMapRenderPayloads.MapRenderJobOfferS2CPayload payload) {
		if (activeJob != null) return "worker-busy";
		if (!status.eligible()) return "worker-not-eligible:" + status.reason();
		if (!Objects.equals(status.canonicalProfileHash(), payload.profileHash())) return "canonical-profile-mismatch";
		if (!YandexMapShaderGuard.inspect().compatible()) return "shader-pack-active";
		YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload capabilities = lastCapabilities;
		if (capabilities == null || capabilities.mapProtocolVersion() != YandexMapRenderPayloads.PROTOCOL_VERSION) return "capability-not-ready";
		if (!RendererBotClientMode.isEnabled()) {
			LostgladeClientSettings.MapRendererMode mode = LostgladeClientSettings.mapRendererMode();
			if (mode == LostgladeClientSettings.MapRendererMode.OFF) return "disabled-by-client";
			if (mode == LostgladeClientSettings.MapRendererMode.IDLE_ONLY) {
				if (client.getFps() > 0 && client.getFps() < LostgladeClientSettings.mapRendererMinFps()) return "fps-below-threshold";
				if (!volunteerClientIdle(client)) return "client-not-idle";
			}
			long now = System.currentTimeMillis();
			while (!acceptedVolunteerJobs.isEmpty() && now - acceptedVolunteerJobs.peekFirst() >= 60_000L) acceptedVolunteerJobs.removeFirst();
			if (acceptedVolunteerJobs.size() >= Math.max(1, LostgladeClientSettings.mapRendererMaxJobsPerMinute())) return "client-rate-limit";
			acceptedVolunteerJobs.addLast(now);
		}
		return null;
	}

	private static boolean volunteerClientIdle(Minecraft client) {
		if (client == null || client.options == null) return false;
		return !client.options.keyUp.isDown()
				&& !client.options.keyDown.isDown()
				&& !client.options.keyLeft.isDown()
				&& !client.options.keyRight.isDown()
				&& !client.options.keyJump.isDown()
				&& !client.options.keyAttack.isDown()
				&& !client.options.keyUse.isDown();
	}

	private static void handleSceneStart(Minecraft client, YandexMapRenderPayloads.MapRenderSceneStartS2CPayload payload) {
		ActiveJob job = activeOwned(payload.jobId());
		if (job == null || job.scene != null) return;
		if (payload.tileBlocks() != MapRenderProfile.CURRENT.tileBlocks()
				|| payload.renderPixels() != MapRenderProfile.CURRENT.tilePixels()
				|| payload.tileX() != job.tileX
				|| payload.tileZ() != job.tileZ) {
			failActiveJob("scene-contract-mismatch");
			return;
		}
		try {
			YandexMapRenderScene.SceneDescriptor descriptor = new YandexMapRenderScene.SceneDescriptor(
					payload.dimensionId(),
					payload.dimensionTypeId(),
					payload.seed(),
					payload.seaLevel(),
					payload.tileX(),
					payload.tileZ(),
					payload.tileBlocks(),
					payload.renderPixels(),
					payload.viewDistance()
			);
			job.scene = YandexMapRenderScene.create(client, descriptor);
			job.renderer = new YandexMapVanillaTopDownRenderer(job.scene);
			job.snapshotFingerprint = payload.snapshotFingerprint();
			job.expectedChunkPackets = payload.chunkPacketCount();
			job.expectedEntityPackets = payload.entityPacketCount();
			RendererClientDiagnostics.mapStage(job.jobId, "scene: " + job.expectedChunkPackets + " chunks, " + job.expectedEntityPackets + " entities");
		} catch (Throwable throwable) {
			Lg2.LOGGER.warn("Failed to create isolated Yandex map v2 scene for job {}", payload.jobId(), throwable);
			failActiveJob("scene-create-failed:" + shortError(throwable));
		}
	}

	private static void handleSceneChunk(YandexMapRenderPayloads.MapRenderSceneChunkS2CPayload payload) {
		ActiveJob job = activeOwned(payload.jobId());
		if (job == null || job.scene == null || job.ready) return;
		if (payload.index() != job.receivedChunkPackets || payload.index() >= job.expectedChunkPackets) {
			failActiveJob("chunk-packet-order-mismatch");
			return;
		}
		try {
			job.scene.applyChunkPacket(payload.packetBytes());
			job.receivedChunkPackets++;
		} catch (Throwable throwable) {
			Lg2.LOGGER.warn("Failed to apply Yandex map v2 chunk packet for job {}", payload.jobId(), throwable);
			failActiveJob("chunk-packet-failed:" + shortError(throwable));
		}
	}

	private static void handleSceneEntity(YandexMapRenderPayloads.MapRenderSceneEntityS2CPayload payload) {
		ActiveJob job = activeOwned(payload.jobId());
		if (job == null || job.scene == null || job.ready) return;
		if (payload.index() != job.receivedEntityPackets || payload.index() >= job.expectedEntityPackets) {
			failActiveJob("entity-packet-order-mismatch");
			return;
		}
		try {
			job.scene.applyItemDisplayPacket(payload.packetTypeId(), payload.packetBytes());
			job.receivedEntityPackets++;
		} catch (Throwable throwable) {
			Lg2.LOGGER.warn("Failed to apply Yandex map v2 ItemDisplay packet for job {}", payload.jobId(), throwable);
			failActiveJob("entity-packet-failed:" + shortError(throwable));
		}
	}

	private static void handleSceneReady(YandexMapRenderPayloads.MapRenderSceneReadyS2CPayload payload) {
		ActiveJob job = activeOwned(payload.jobId());
		if (job == null || job.scene == null) return;
		if (job.receivedChunkPackets != job.expectedChunkPackets || job.receivedEntityPackets != job.expectedEntityPackets) {
			failActiveJob("scene-packet-count-mismatch");
			return;
		}
		job.ready = true;
		job.readyAtTickSequence = clientTickSequence;
		RendererClientDiagnostics.mapStage(job.jobId, "scene ready / vanilla warmup");
	}

	private static void tick(Minecraft client) {
		clientTickSequence++;
		drainRetiredJobs();
		if (client == null || client.getConnection() == null) {
			return;
		}
		heartbeatTicks++;
		boolean shaderCompatible = YandexMapShaderGuard.inspect().compatible();
		if (shaderCompatible != lastShaderCompatible) {
			if (!shaderCompatible && activeJob != null) {
				failActiveJob("shader-state-changed");
			}
			requestCapabilityRefresh();
		}
		if (heartbeatTicks >= HEARTBEAT_TICKS) {
			heartbeatTicks = 0;
			YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload capabilities = lastCapabilities;
			if (capabilities != null) send(capabilities); else requestCapabilityRefresh();
		}

		ActiveJob job = activeJob;
		if (job == null || !job.ready || job.renderer == null || job.resultSubmitted) {
			return;
		}
		if (System.currentTimeMillis() > job.expiresAtEpochMs + 120_000L) {
			failActiveJob("client-job-timeout");
			return;
		}
		YandexMapVanillaTopDownRenderer.AdvanceResult result = job.renderer.advance(frame -> handleRenderedFrame(client, job, frame));
		if (!Objects.equals(job.lastReadiness, result.readiness())) {
			job.lastReadiness = result.readiness();
			RendererClientDiagnostics.mapStage(job.jobId, result.state().name().toLowerCase(java.util.Locale.ROOT) + " / " + result.reason()
					+ " / " + readinessSummary(result.readiness()));
			if (Lg2.LOGGER.isDebugEnabled()) {
				Lg2.LOGGER.debug("Yandex map v2 job {} readiness: state={}, reason={}, {}, cull={}", job.jobId, result.state(), result.reason(), result.readiness(), job.renderer.cullDiagnostics());
			}
		}
		if (result.state() == YandexMapVanillaTopDownRenderer.State.WAITING_FOR_VANILLA
				&& job.readyAtTickSequence > 0L
				&& clientTickSequence - job.readyAtTickSequence >= VANILLA_SETTLE_TIMEOUT_TICKS) {
			String summary = readinessSummary(result.readiness());
			Lg2.LOGGER.warn("Yandex map v2 tile {},{} did not settle within {} ticks: {}, cull={}",
					job.tileX, job.tileZ, VANILLA_SETTLE_TIMEOUT_TICKS, summary, job.renderer.cullDiagnostics());
			failActiveJob("render-settle-timeout:" + summary);
			return;
		}
		if (result.state() == YandexMapVanillaTopDownRenderer.State.FAILED) {
			failActiveJob("render-failed:" + result.reason());
		}
	}

	private static void handleRenderedFrame(
			Minecraft client,
			ActiveJob job,
			YandexMapVanillaTopDownRenderer.ValidatedFrame frame
	) {
		if (activeJob != job || job.resultSubmitted) {
			if (frame.image() != null) frame.image().close();
			return;
		}
		if (!frame.accepted()) {
			YandexMapRenderValidator.ValidationResult validation = frame.validation();
			YandexMapRenderScene.RenderReadiness readiness = frame.readiness();
			Lg2.LOGGER.warn(
					"Yandex map v2 rejected framebuffer job {} tile {},{}: reason={}, mean={}, variance={}, transparent={}, dominant={}, readiness={}",
					job.jobId, job.tileX, job.tileZ, frame.reason(),
					validation == null ? Double.NaN : validation.meanBrightness(),
					validation == null ? Double.NaN : validation.brightnessVariance(),
					validation == null ? Double.NaN : validation.transparentFraction(),
					validation == null ? Double.NaN : validation.dominantColorFraction(),
					readiness
			);
			if (frame.image() != null && FabricLoader.getInstance().isDevelopmentEnvironment()) {
				try {
					Path debugDir = FabricLoader.getInstance().getGameDir().resolve("debug").resolve("yandex-map-v2");
					Files.createDirectories(debugDir);
					Path debugImage = debugDir.resolve(job.jobId + "-" + frame.reason() + ".png");
					frame.image().writeToFile(debugImage);
					Lg2.LOGGER.warn("Saved rejected Yandex map framebuffer to {}", debugImage);
				} catch (Throwable diagnosticError) {
					Lg2.LOGGER.warn("Failed to save rejected Yandex map framebuffer for job {}", job.jobId, diagnosticError);
				}
			}
			if (frame.image() != null) frame.image().close();
			failActiveJob("client-validator:" + frame.reason());
			return;
		}
		Path temporary = null;
		try {
			temporary = Files.createTempFile("lg2-yandex-map-v2-", ".png");
			frame.image().writeToFile(temporary);
			byte[] pngBytes = Files.readAllBytes(temporary);
			job.resultSubmitted = true;
			if (ClientPlayNetworking.canSend(YandexMapRenderPayloads.MapRenderResultC2SPayload.TYPE)) {
				ClientPlayNetworking.send(new YandexMapRenderPayloads.MapRenderResultC2SPayload(
						job.jobId,
						job.snapshotFingerprint,
						pngBytes
				));
			} else {
				throw new IllegalStateException("server no longer accepts Yandex map result payloads");
			}
			Lg2.LOGGER.info("Yandex map v2 rendered tile {},{} job {} ({} PNG bytes)", job.tileX, job.tileZ, job.jobId, pngBytes.length);
			RendererClientDiagnostics.mapRendered(job.jobId, job.tileX, job.tileZ, pngBytes.length);
			retireActiveJob("completed");
			localStatusReason = "waiting-for-server";
		} catch (Throwable throwable) {
			Lg2.LOGGER.warn("Failed to encode/send Yandex map v2 result for job {}", job.jobId, throwable);
			failActiveJob("result-encode-failed:" + shortError(throwable));
		} finally {
			if (frame.image() != null) frame.image().close();
			if (temporary != null) {
				try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
			}
		}
	}

	private static String readinessSummary(YandexMapRenderScene.RenderReadiness readiness) {
		if (readiness == null) return "readiness=null";
		return "visible=" + readiness.visibleSections()
				+ ", all=" + readiness.allSectionsRendered()
				+ ", compile=" + readiness.compileQueueSize()
				+ ", upload=" + readiness.uploadQueueSize()
				+ ", graphNeeds=" + readiness.graphNeedsFullUpdate()
				+ ", graphTask=" + readiness.graphTaskPresent() + '/' + readiness.graphTaskDone()
				+ ", chunks=" + readiness.loadedChunks()
				+ ", light=" + readiness.lightReadyColumns();
	}

	private static ActiveJob activeOwned(UUID jobId) {
		ActiveJob job = activeJob;
		return job != null && job.jobId.equals(jobId) ? job : null;
	}

	private static void sendDecision(UUID jobId, boolean accepted, String reason) {
		if (ClientPlayNetworking.canSend(YandexMapRenderPayloads.MapRenderJobDecisionC2SPayload.TYPE)) {
			ClientPlayNetworking.send(new YandexMapRenderPayloads.MapRenderJobDecisionC2SPayload(jobId, accepted, reason));
		}
	}

	private static void failActiveJob(String reason) {
		ActiveJob job = activeJob;
		if (job == null) return;
		if (ClientPlayNetworking.canSend(YandexMapRenderPayloads.MapRenderFailureC2SPayload.TYPE)) {
			ClientPlayNetworking.send(new YandexMapRenderPayloads.MapRenderFailureC2SPayload(job.jobId, reason));
		}
		Lg2.LOGGER.warn("Yandex map v2 client job {} failed: {}", job.jobId, reason);
		RendererClientDiagnostics.mapFailed(job.jobId, reason);
		retireActiveJob("failed");
		localStatusReason = "waiting-for-server";
	}

	/**
	 * Detach a completed/cancelled job immediately, but never destroy its GL
	 * resources from inside Screenshot's readback callback or a disconnect hook.
	 */
	private static void retireActiveJob(String reason) {
		ActiveJob job = activeJob;
		activeJob = null;
		if (job == null) return;
		RETIRED_JOBS.addLast(new RetiredJob(job, clientTickSequence + 2L, clientTickSequence + 120L, reason));
	}

	private static void drainRetiredJobs() {
		if (RETIRED_JOBS.isEmpty()) return;
		var iterator = RETIRED_JOBS.iterator();
		while (iterator.hasNext()) {
			RetiredJob retired = iterator.next();
			if (clientTickSequence < retired.closeAfterTick) continue;
			ActiveJob job = retired.job;
			boolean readbackPending = job.renderer != null && job.renderer.readbackPending();
			if (readbackPending && clientTickSequence < retired.forceCloseAfterTick) continue;
			closeJobResources(job);
			iterator.remove();
		}
	}

	private static void closeJobResources(ActiveJob job) {
		if (job == null) return;
		if (job.renderer != null) {
			try { job.renderer.close(); } catch (Throwable throwable) {
				Lg2.LOGGER.debug("Failed to retire Yandex map renderer cleanly", throwable);
			}
			job.renderer = null;
		}
		if (job.scene != null) {
			try { job.scene.close(); } catch (Throwable throwable) {
				Lg2.LOGGER.debug("Failed to retire Yandex map scene cleanly", throwable);
			}
			job.scene = null;
		}
	}

	private static void send(YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload payload) {
		lastCapabilities = payload;
		if (ClientPlayNetworking.canSend(YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload.TYPE)) {
			ClientPlayNetworking.send(payload);
		}
	}

	private static void reset() {
		REFRESH_GENERATION.incrementAndGet();
		lastCapabilities = null;
		status = new WorkerStatus(false, "not-connected", "");
		localStatusReason = "not-connected";
		heartbeatTicks = 0;
		acceptedVolunteerJobs.clear();
	}

	private static String shortError(Throwable throwable) {
		if (throwable == null) return "unknown";
		Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

	public record WorkerStatus(boolean eligible, String reason, String canonicalProfileHash) {
		public WorkerStatus {
			reason = Objects.requireNonNullElse(reason, "unknown");
			canonicalProfileHash = Objects.requireNonNullElse(canonicalProfileHash, "");
		}
	}

	private record RetiredJob(ActiveJob job, long closeAfterTick, long forceCloseAfterTick, String reason) {
	}

	private static final class ActiveJob {
		private final UUID jobId;
		private final String profileHash;
		private final long tileX;
		private final long tileZ;
		private final long expiresAtEpochMs;
		private String snapshotFingerprint;
		private int expectedChunkPackets;
		private int expectedEntityPackets;
		private int receivedChunkPackets;
		private int receivedEntityPackets;
		private boolean ready;
		private long readyAtTickSequence;
		private boolean resultSubmitted;
		private YandexMapRenderScene.RenderReadiness lastReadiness;
		private YandexMapRenderScene scene;
		private YandexMapVanillaTopDownRenderer renderer;

		private ActiveJob(UUID jobId, String profileHash, long tileX, long tileZ, long expiresAtEpochMs) {
			this.jobId = jobId;
			this.profileHash = profileHash;
			this.tileX = tileX;
			this.tileZ = tileZ;
			this.expiresAtEpochMs = expiresAtEpochMs;
		}
	}
}
