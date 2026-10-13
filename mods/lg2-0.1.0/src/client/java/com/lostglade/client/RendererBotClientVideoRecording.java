package com.lostglade.client;

import com.lostglade.Lg2;
import com.lostglade.network.RendererBotPayloads;
import com.lostglade.server.CameraMediaCache;
import com.lostglade.server.map.MapPaletteQuantizer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RendererBotClientVideoRecording {
	private static final Object LOCK = new Object();
	private static final ExecutorService RECORD_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "lg2-renderer-bot-video");
		thread.setDaemon(true);
		return thread;
	});
	private static final String DEFAULT_FFMPEG_BIN = "ffmpeg";
	private static final long FINISH_TIMEOUT_MS = Long.getLong("lg2.rendererBotVideoFinishTimeoutMs", 30_000L);
	private static final long COMPLETION_RESEND_INTERVAL_MS = 2_000L;
	private static final long REPLAY_FRAME_STALL_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30L);
	private static final int MIN_RECORDING_FRAMES = 1;
	private static final int MAX_TARGET_FPS = 20;
	private static final int REMOTE_VIDEO_CHUNK_BYTES = 192 * 1024;
	private static final long MAX_REMOTE_VIDEO_UPLOAD_BYTES = 96L * 1024L * 1024L;
	private static final double TARGET_RENDER_SCALE = Math.max(1.0D, doubleProperty("lg2.rendererBotVideoRenderScale", 2.0D));
	private static final int MIN_RENDER_WIDTH = Math.max(128, Integer.getInteger("lg2.rendererBotVideoMinRenderWidth", 1024));
	private static final int MIN_RENDER_HEIGHT = Math.max(128, Integer.getInteger("lg2.rendererBotVideoMinRenderHeight", 768));
	private static final int MAX_RENDER_WIDTH = Math.max(MIN_RENDER_WIDTH, Integer.getInteger("lg2.rendererBotVideoMaxRenderWidth", 2048));
	private static final int MAX_RENDER_HEIGHT = Math.max(MIN_RENDER_HEIGHT, Integer.getInteger("lg2.rendererBotVideoMaxRenderHeight", 1536));
	private static final String ENCODER_PRESET = System.getProperty("lg2.rendererBotVideoPreset", "fast").trim();
	private static final int ENCODER_CRF = Math.max(0, Integer.getInteger("lg2.rendererBotVideoCrf", 18));

	private static final Map<UUID, PendingRecording> RECORDINGS = new HashMap<>();
	private static final Map<UUID, Integer> EARLY_FRAME_READY = new HashMap<>();
	private static final Map<UUID, Map<Integer, RendererBotPayloads.VideoCameraFrame>> EARLY_VIDEO_FRAMES = new HashMap<>();
	private static final Map<UUID, Integer> EARLY_SEALS = new HashMap<>();

	private RendererBotClientVideoRecording() {
	}

	/**
	 * Camera capture uses one shared off-screen target. Background render jobs
	 * must stay out while a hand-held recording is warming up or writing frames.
	 */
	public static boolean hasActiveRecording() {
		synchronized (LOCK) {
			return RECORDINGS.values().stream().anyMatch(PendingRecording::needsRenderWork);
		}
	}

	public static void register() {
		// A server restart is not a user cancellation. Keep the encoder and the
		// immutable shadow timeline alive so the restored server job can continue
		// from its last acknowledged frame after JOIN.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pauseForServerReconnect());
		ClientTickEvents.END_CLIENT_TICK.register(RendererBotClientVideoRecording::onClientTick);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> beginRecording(payload, context.client()))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoRecordingStopS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> requestStop(payload.requestId()))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoRecordingTruncateS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> requestTruncate(payload.requestId(), payload.frameCount()))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoRecordingCancelS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> cancelRecording(payload.requestId()))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoRecordingCompleteAckS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> acknowledgeCompletedRecording(payload.requestId()))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoRecordingFrameS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> appendCameraFrame(payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoRecordingFrameSnapshotS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> captureTimelineFrame(payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoRecordingFrameReadyS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> markReplayFrameReady(payload))
		);
	}

	private static void beginRecording(RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload payload, Minecraft client) {
		if (!RendererBotVolunteerClient.isVolunteerRenderer()) {
			sendFailure(payload.requestId(), "Volunteer renderer disabled by client");
			return;
		}
		try {
			synchronized (LOCK) {
				PendingRecording existing = RECORDINGS.get(payload.requestId());
				if (existing != null && existing.payload().renderSessionId().equals(payload.renderSessionId())) {
					existing.reconcileFrames(payload.cameraFrames());
					// The restarted server restored the same job. Tell it the exact
					// encoder boundary before it releases the next frame; this also
					// covers a progress packet that was in flight during disconnect.
					ClientPlayNetworking.send(new RendererBotPayloads.RendererBotVideoRecordingResumeC2SPayload(payload.requestId(), existing.completedFrameCount()));
					if (existing.firstPreviewFrame != null) {
						sendLiveVideoFrames(client, List.of(existing), existing.firstPreviewFrame, existing.firstFullFrame);
					}
					if (existing.isFinalized()) {
						existing.resetCompletionDeliveryForReconnect();
						sendCompletedRecording(existing, existing.completedDurationMs(), existing.completedFps());
					}
					return;
				}
			}
			String sourceKey = payload.requestId().toString();
			if (!RendererBotShadowWorldManager.ensureVideoTimeline(client, payload.renderSessionId())) {
				throw new IOException("Recorded shadow history is unavailable; cannot recover this video from the current world");
			}
			CameraMediaCache.ensureVideoParent(sourceKey);
			Path tempPath = CameraMediaCache.tempVideoSourcePath(sourceKey);
			Path finalPath = CameraMediaCache.videoSourcePath(sourceKey);
			Files.deleteIfExists(tempPath);
			Files.deleteIfExists(finalPath);

			int captureWidth = computeRenderWidth(payload);
			int captureHeight = computeRenderHeight(payload, captureWidth);
			int targetFps = Math.clamp(payload.targetFps(), 1, MAX_TARGET_FPS);
			Process process = startEncoderProcess(tempPath, captureWidth, captureHeight, targetFps);
			synchronized (LOCK) {
				PendingRecording recording = new PendingRecording(
						payload,
						process,
						process.getOutputStream(),
						tempPath,
						finalPath,
						captureWidth,
						captureHeight
				);
				Integer earlyReady = EARLY_FRAME_READY.remove(payload.requestId());
				if (earlyReady != null) {
					recording.markServerFrameReady(earlyReady);
				}
				Map<Integer, RendererBotPayloads.VideoCameraFrame> earlyFrames = EARLY_VIDEO_FRAMES.remove(payload.requestId());
				if (earlyFrames != null) {
					earlyFrames.entrySet().stream().sorted(Map.Entry.comparingByKey())
							.forEach(entry -> recording.appendCameraFrame(entry.getKey(), entry.getValue()));
				}
				Integer earlySeal = EARLY_SEALS.remove(payload.requestId());
				if (earlySeal != null) {
					if (earlySeal == Integer.MAX_VALUE) recording.seal();
					else recording.truncateAndSeal(earlySeal);
				}
				RECORDINGS.put(payload.requestId(), recording);
			}
			RendererClientDiagnostics.cameraVideoStarted(payload.requestId());
			ClientPlayNetworking.send(new RendererBotPayloads.RendererBotVideoRecordingResumeC2SPayload(payload.requestId(), 0));
			Lg2.LOGGER.info(
					"Renderer bot preparing video recording {} at {} fps {}x{} (capture {}x{})",
					payload.requestId(),
					targetFps,
					payload.fullWidth(),
					payload.fullHeight(),
					captureWidth,
					captureHeight
			);
		} catch (Exception exception) {
			sendFailure(payload.requestId(), exception.getMessage());
			Lg2.LOGGER.warn("Renderer bot failed to start video recording {}", payload.requestId(), exception);
		}
	}

	private static void requestStop(UUID requestId) {
		if (requestId == null) {
			return;
		}
		synchronized (LOCK) {
			PendingRecording recording = RECORDINGS.get(requestId);
			if (recording != null) {
				recording.seal();
			} else {
				EARLY_SEALS.put(requestId, Integer.MAX_VALUE);
			}
		}
	}

	private static void requestTruncate(UUID requestId, int frameCount) {
		if (requestId == null) return;
		synchronized (LOCK) {
			PendingRecording recording = RECORDINGS.get(requestId);
			if (recording != null) recording.truncateAndSeal(frameCount);
			else EARLY_SEALS.put(requestId, Math.max(1, frameCount));
		}
	}

	private static void cancelRecording(UUID requestId) {
		if (requestId == null) {
			return;
		}
		// The server has already completed this request exceptionally, so this is
		// local cleanup only; it must never turn a cancellation into another error.
		PendingRecording current;
		synchronized (LOCK) {
			EARLY_FRAME_READY.remove(requestId);
			EARLY_VIDEO_FRAMES.remove(requestId);
			EARLY_SEALS.remove(requestId);
			current = RECORDINGS.remove(requestId);
		}
		if (current == null) {
			return;
		}
		try {
			current.encoderInput.close();
		} catch (IOException ignored) {
		}
		current.encoderProcess.destroyForcibly();
		try {
			Files.deleteIfExists(current.tempPath());
			// A finalized file may already be the server's playback source when
			// both processes share the cache. Cancellation only removes temp work.
		} catch (IOException ignored) {
		}
	}

	private static void appendCameraFrame(RendererBotPayloads.RendererBotVideoRecordingFrameS2CPayload payload) {
		if (payload == null || payload.requestId() == null || payload.frame() == null || payload.frameIndex() < 0) {
			return;
		}
		synchronized (LOCK) {
			PendingRecording recording = RECORDINGS.get(payload.requestId());
			if (recording != null) {
				recording.appendCameraFrame(payload.frameIndex(), payload.frame());
			} else {
				EARLY_VIDEO_FRAMES.computeIfAbsent(payload.requestId(), ignored -> new HashMap<>()).put(payload.frameIndex(), payload.frame());
			}
		}
	}

	private static void captureTimelineFrame(RendererBotPayloads.RendererBotVideoRecordingFrameSnapshotS2CPayload payload) {
		if (payload == null || payload.requestId() == null || payload.renderSessionId() == null || payload.frameIndex() < 0) {
			return;
		}
		// This packet follows the matching level, chunk and entity packets on the
		// reliable play stream. Capturing here keeps a player pose and its world
		// state in the same video frame instead of racing the camera-pose packet.
		RendererBotShadowWorldManager.captureVideoFrameSnapshot(payload.renderSessionId(), payload.frameIndex());
	}

	private static void markReplayFrameReady(RendererBotPayloads.RendererBotVideoRecordingFrameReadyS2CPayload payload) {
		if (payload == null || payload.requestId() == null) {
			return;
		}
		synchronized (LOCK) {
			PendingRecording recording = RECORDINGS.get(payload.requestId());
			if (recording != null) {
				recording.markServerFrameReady(payload.frameIndex());
			} else {
				EARLY_FRAME_READY.put(payload.requestId(), payload.frameIndex());
			}
		}
	}

	private static void onClientTick(Minecraft client) {
		List<PendingRecording> activeRecordings;
		synchronized (LOCK) {
			activeRecordings = new ArrayList<>(RECORDINGS.values());
		}
		if (activeRecordings.isEmpty()) {
			return;
		}
		long nowMillis = System.currentTimeMillis();
		for (PendingRecording current : activeRecordings) {
			if (current == null) {
				continue;
			}
			if (clearStalledRecordingFrame(current, System.nanoTime())) {
				Lg2.LOGGER.warn("Renderer bot retrying stalled replay frame {} for {}", current.currentFrameIndex(), current.payload().requestId());
			}
			if (current.isFinalized()) {
				if (current.shouldRetryCompletion(nowMillis)) {
					// TCP is reliable, but this is intentionally idempotent: a final
					// progress packet lost during reconnect must not leave the issued
					// print at (total - 1) / total while the MP4 waits for its ACK.
					sendReplayProgress(current.payload().requestId(), current.completedFrameCount());
					sendCompletedRecording(current, current.completedDurationMs(), current.completedFps());
				}
				continue;
			}
			if (current.isReplayComplete() && !current.frameInFlight && !current.finishScheduled) {
				scheduleFinish(client, current);
			}
		}
		dispatchReadyRecordings(client, System.nanoTime());
	}

	private static void dispatchReadyRecordings(Minecraft client, long nowNanos) {
		if (client == null || client.level == null || RendererBotOffscreenWorldRenderer.isOffscreenRenderActive()) {
			return;
		}
		PendingRecording recordingToRender = null;
		synchronized (LOCK) {
			for (PendingRecording current : RECORDINGS.values()) {
				if (current == null || current.frameInFlight || current.finishScheduled || !current.canRenderCurrentFrame()) {
					continue;
				}
				if (recordingToRender == null) {
					recordingToRender = current;
				}
			}
		}
		if (recordingToRender != null) {
			PendingRecording recording = recordingToRender;
			// Restore before drawing. Restoring in the readback callback rendered
			// the preceding scene and could replace the active world mid-render.
			if (!RendererBotShadowWorldManager.restoreVideoFrameSnapshot(
					client, recording.payload().renderSessionId(), recording.currentFrameIndex())) {
				if (recording.snapshotWaitStartedNanos == 0L) recording.snapshotWaitStartedNanos = nowNanos;
				if (nowNanos - recording.snapshotWaitStartedNanos > REPLAY_FRAME_STALL_TIMEOUT_NANOS) {
					String message = "Recorded world snapshot missing for video frame " + recording.currentFrameIndex();
					sendFailure(recording.payload().requestId(), message);
					abortRecording(recording.payload().requestId(), message);
				}
				return;
			}
			recording.snapshotWaitStartedNanos = 0L;
			long frameAttempt = markRecordingFrameInFlight(recording.payload().requestId(), nowNanos);
			if (frameAttempt <= 0L) {
				return;
			}
			boolean rendered = RendererBotOffscreenWorldRenderer.renderToTarget(
					client,
					recordingRenderRequest(recording, recording.captureWidth, recording.captureHeight),
					renderTarget -> completeDeferredReplayRender(client, recording, renderTarget, frameAttempt)
			);
			if (!rendered) {
				clearRecordingFrameInFlight(recording.payload().requestId(), frameAttempt);
			} else {
				RendererClientDiagnostics.cameraFrameRendered();
			}
		}
	}

	private static void dispatchRecordingFrame(Minecraft client, PendingRecording recording, RenderTarget renderTarget, long frameAttempt) {
		try {
			CompletableFuture<NativeImage> sourceFuture = takeScreenshotFuture(renderTarget);
			CompletableFuture<byte[]> previewFuture;
			CompletableFuture<byte[]> fullFuture;
			synchronized (LOCK) {
				PendingRecording active = RECORDINGS.get(recording.payload().requestId());
				if (active == null || active != recording || active.isReplayComplete() || !active.isCurrentFrameAttempt(frameAttempt)) {
					sourceFuture.thenAccept(image -> {
						if (image != null) {
							image.close();
						}
					});
					clearRecordingFrameInFlight(recording.payload().requestId(), frameAttempt);
					return;
				}
				if (active.firstPreviewFrame == null) {
					previewFuture = RendererBotGpuCaptureBackend.isAvailable()
							? RendererBotGpuCaptureBackend.captureQuantizedFrame(
									renderTarget,
									active.payload().previewWidth(),
									active.payload().previewHeight(),
									true
							)
							: CompletableFuture.completedFuture(null);
					fullFuture = RendererBotGpuCaptureBackend.isAvailable()
							? RendererBotGpuCaptureBackend.captureQuantizedFrame(
									renderTarget,
									active.payload().fullWidth(),
									active.payload().fullHeight(),
									true
							)
							: CompletableFuture.completedFuture(null);
				} else {
					// The first frame is only the final file's poster. Subsequent
					// previews must be generated from this frame's screenshot.
					previewFuture = CompletableFuture.completedFuture(null);
					fullFuture = CompletableFuture.completedFuture(null);
				}
			}
			CompletableFuture.allOf(sourceFuture, previewFuture, fullFuture).whenComplete((ignored, throwable) -> {
				if (throwable != null) {
					handleRecordingFrameFailure(client, recording, sourceFuture, throwable, frameAttempt);
					return;
				}
				RECORD_EXECUTOR.submit(() -> processCapturedFrame(client, List.of(recording), sourceFuture.join(), previewFuture.join(), fullFuture.join(), frameAttempt));
			});
		} catch (Throwable throwable) {
			Lg2.LOGGER.warn("Renderer bot recording dispatch failed, falling back to screenshot path for {}", recording.payload().requestId(), throwable);
			Screenshot.takeScreenshot(renderTarget, image -> RECORD_EXECUTOR.submit(() -> processCapturedFrame(client, List.of(recording), image, frameAttempt)));
		}
	}

	private static void completeDeferredReplayRender(Minecraft client, PendingRecording recording, RenderTarget renderTarget, long frameAttempt) {
		RendererBotShadowWorldManager.RenderReadiness readiness =
				RendererBotShadowWorldManager.inspectRenderReadiness(recording.payload().renderSessionId());
		if (readiness == null || !readiness.settled()) {
			// Keep rendering this same replay pose until every visible terrain section
			// is compiled. The server retains the same shadow session and only slides
			// its chunk window after the encoded frame asks for the next pose.
			clearRecordingFrameInFlight(recording.payload().requestId(), frameAttempt);
			return;
		}
		dispatchRecordingFrame(client, recording, renderTarget, frameAttempt);
	}

	private static void processCapturedFrame(Minecraft client, List<PendingRecording> recordings, NativeImage image, long frameAttempt) {
		List<PendingRecording> recordingsStartedNow = new ArrayList<>();
		try {
			int width = image.getWidth();
			int height = image.getHeight();
			int[] pixels = image.makePixelArray();
			byte[] rgb = argbToRgb(pixels);
			byte[] livePreview = quantizeScaledFrame(pixels, width, height, 128, 128);
			byte[] liveFull = quantizeScaledFrame(pixels, width, height, recordings.getFirst().payload().fullWidth(), recordings.getFirst().payload().fullHeight());
			synchronized (LOCK) {
				for (PendingRecording current : recordings) {
					PendingRecording active = RECORDINGS.get(current.payload().requestId());
					if (active == null || active != current || !active.isCurrentFrameAttempt(frameAttempt)) {
						continue;
					}
					if (current.firstPreviewFrame == null) {
						current.firstPreviewFrame = quantizeScaledFrame(pixels, width, height, current.payload().previewWidth(), current.payload().previewHeight());
						current.firstFullFrame = quantizeScaledFrame(pixels, width, height, current.payload().fullWidth(), current.payload().fullHeight());
					}
					writeFrameCopiesLocked(current, rgb);
					if (current.markRecordingStartNotified()) {
						recordingsStartedNow.add(current);
					}
				}
			}
			notifyRecordingStarts(client, recordingsStartedNow);
			sendLiveVideoFrames(client, recordings, livePreview, liveFull);
		} catch (Exception exception) {
			client.execute(() -> {
				for (PendingRecording current : recordings) {
					sendFailure(current.payload().requestId(), exception.getMessage());
					abortRecording(current.payload().requestId(), "Renderer bot failed during video frame encoding");
				}
			});
			Lg2.LOGGER.warn("Renderer bot video frame encode failed", exception);
		} finally {
			image.close();
			client.execute(() -> {
				int completedFrameCount = -1;
				synchronized (LOCK) {
					for (PendingRecording current : recordings) {
						PendingRecording active = RECORDINGS.get(current.payload().requestId());
						if (active != null && active == current && active.finishFrameAttempt(frameAttempt)) {
							completedFrameCount = active.advanceAfterEncodedFrame();
							if (active.isReplayComplete() && !active.finishScheduled) {
								scheduleFinish(client, active);
							}
						}
					}
				}
				if (completedFrameCount >= 0) {
					// The server uses this number as both its next replay index and
					// the visible completed-frame count.  Sending the terminal size
					// is essential: the former -1 sentinel left every finished video
					// visually stuck at (total - 1) / total, commonly 99%.
					sendReplayProgress(recordings.getFirst().payload().requestId(), completedFrameCount);
				}
			});
		}
	}

	private static void processCapturedFrame(
			Minecraft client,
			List<PendingRecording> recordings,
			NativeImage image,
			byte[] previewFrame,
			byte[] fullFrame,
			long frameAttempt
	) {
		List<PendingRecording> recordingsStartedNow = new ArrayList<>();
		try {
			int[] pixels = image.makePixelArray();
			byte[] rgb = argbToRgb(pixels);
			byte[] livePreview = previewFrame != null && previewFrame.length >= 128 * 128
					? previewFrame
					: quantizeScaledFrame(pixels, image.getWidth(), image.getHeight(), 128, 128);
			byte[] liveFull = fullFrame != null ? fullFrame : quantizeScaledFrame(pixels,
					image.getWidth(), image.getHeight(), recordings.getFirst().payload().fullWidth(), recordings.getFirst().payload().fullHeight());
			synchronized (LOCK) {
				for (PendingRecording current : recordings) {
					PendingRecording active = RECORDINGS.get(current.payload().requestId());
					if (active == null || active != current || !active.isCurrentFrameAttempt(frameAttempt)) {
						continue;
					}
					if (current.firstPreviewFrame == null) {
						current.firstPreviewFrame = previewFrame != null
								? previewFrame
								: quantizeScaledFrame(pixels, image.getWidth(), image.getHeight(), current.payload().previewWidth(), current.payload().previewHeight());
						current.firstFullFrame = fullFrame != null
								? fullFrame
								: quantizeScaledFrame(pixels, image.getWidth(), image.getHeight(), current.payload().fullWidth(), current.payload().fullHeight());
					}
					writeFrameCopiesLocked(current, rgb);
					if (current.markRecordingStartNotified()) {
						recordingsStartedNow.add(current);
					}
				}
			}
			notifyRecordingStarts(client, recordingsStartedNow);
			sendLiveVideoFrames(client, recordings, livePreview, liveFull);
		} catch (Exception exception) {
			client.execute(() -> {
				for (PendingRecording current : recordings) {
					sendFailure(current.payload().requestId(), exception.getMessage());
					abortRecording(current.payload().requestId(), "Renderer bot failed during video frame encoding");
				}
			});
			Lg2.LOGGER.warn("Renderer bot video frame encode failed", exception);
		} finally {
			image.close();
			client.execute(() -> {
				int completedFrameCount = -1;
				synchronized (LOCK) {
					for (PendingRecording current : recordings) {
						PendingRecording active = RECORDINGS.get(current.payload().requestId());
						if (active != null && active == current && active.finishFrameAttempt(frameAttempt)) {
							completedFrameCount = active.advanceAfterEncodedFrame();
							if (active.isReplayComplete() && !active.finishScheduled) {
								scheduleFinish(client, active);
							}
						}
					}
				}
				if (completedFrameCount >= 0) {
					sendReplayProgress(recordings.getFirst().payload().requestId(), completedFrameCount);
				}
			});
		}
	}

	private static void handleRecordingFrameFailure(
			Minecraft client,
			PendingRecording recording,
			CompletableFuture<NativeImage> sourceFuture,
			Throwable throwable,
			long frameAttempt
	) {
		if (sourceFuture.isDone() && !sourceFuture.isCompletedExceptionally() && !sourceFuture.isCancelled()) {
			RECORD_EXECUTOR.submit(() -> {
				try {
					processCapturedFrame(client, List.of(recording), sourceFuture.join(), frameAttempt);
				} catch (Throwable fallbackThrowable) {
					client.execute(() -> {
						sendFailure(recording.payload().requestId(), fallbackThrowable.getMessage());
						abortRecording(recording.payload().requestId(), "Renderer bot failed during video frame encoding");
					});
					Lg2.LOGGER.warn("Renderer bot video recording GPU fallback failed for {}", recording.payload().requestId(), fallbackThrowable);
				}
			});
			return;
		}
		sourceFuture.thenAccept(image -> {
			if (image != null) {
				image.close();
			}
		});
		client.execute(() -> {
			sendFailure(recording.payload().requestId(), throwable.getMessage());
			abortRecording(recording.payload().requestId(), "Renderer bot failed during video frame encoding");
		});
		Lg2.LOGGER.warn("Renderer bot video recording GPU path failed for {}", recording.payload().requestId(), throwable);
	}

	private static void notifyRecordingStarts(Minecraft client, List<PendingRecording> recordings) {
		if (client == null || recordings == null || recordings.isEmpty()) {
			return;
		}
		client.execute(() -> {
			for (PendingRecording recording : recordings) {
				if (recording == null || recording.payload().requestId() == null) {
					continue;
				}
				try {
					if (!isActiveRecording(recording.payload().requestId())
							|| !ClientPlayNetworking.canSend(RendererBotPayloads.RendererBotVideoRecordingStartedC2SPayload.TYPE)) continue;
					ClientPlayNetworking.send(new RendererBotPayloads.RendererBotVideoRecordingStartedC2SPayload(
							recording.payload().requestId()
					));
				} catch (RuntimeException exception) {
					Lg2.LOGGER.debug("Renderer bot could not confirm start of video recording {}", recording.payload().requestId(), exception);
				}
			}
		});
	}

	private static void sendReplayProgress(UUID requestId, int frameIndex) {
		if (requestId == null || frameIndex < 0
				|| !ClientPlayNetworking.canSend(RendererBotPayloads.RendererBotVideoRecordingProgressC2SPayload.TYPE)) {
			return;
		}
		ClientPlayNetworking.send(new RendererBotPayloads.RendererBotVideoRecordingProgressC2SPayload(requestId, frameIndex));
	}

	private static void sendLiveVideoFrames(Minecraft client, List<PendingRecording> recordings, byte[] preview, byte[] fullFrame) {
		if (client == null || recordings == null || preview == null || preview.length != 128 * 128 || fullFrame == null || fullFrame.length == 0) {
			return;
		}
		client.execute(() -> {
			if (!ClientPlayNetworking.canSend(RendererBotPayloads.RendererBotVideoRecordingRenderedFrameC2SPayload.TYPE)) {
				return;
			}
			for (PendingRecording recording : recordings) {
				if (recording != null && isActiveRecording(recording.payload().requestId())) {
					ClientPlayNetworking.send(new RendererBotPayloads.RendererBotVideoRecordingRenderedFrameC2SPayload(
							recording.payload().requestId(), preview, fullFrame
					));
				}
			}
		});
	}

	private static CompletableFuture<NativeImage> takeScreenshotFuture(RenderTarget renderTarget) {
		CompletableFuture<NativeImage> future = new CompletableFuture<>();
		try {
			Screenshot.takeScreenshot(renderTarget, future::complete);
		} catch (Throwable throwable) {
			future.completeExceptionally(throwable);
		}
		return future;
	}

	private static void scheduleFinish(Minecraft client, PendingRecording current) {
		current.finishScheduled = true;
		RECORD_EXECUTOR.submit(() -> finishRecording(client, current));
	}

	private static void finishRecording(Minecraft client, PendingRecording current) {
		try {
			try {
				current.encoderInput.close();
			} catch (IOException ignored) {
			}
			if (!current.encoderProcess.waitFor(FINISH_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
				current.encoderProcess.destroyForcibly();
				throw new IOException("Renderer bot encoder timed out");
			}
			if (current.encoderProcess.exitValue() != 0) {
				throw new IOException("Renderer bot encoder exited with code " + current.encoderProcess.exitValue());
			}
			if (current.frameCount < MIN_RECORDING_FRAMES || current.firstPreviewFrame == null || current.firstFullFrame == null) {
				throw new IOException("Renderer bot recording captured too few frames: " + current.frameCount);
			}
			Files.move(current.tempPath(), current.finalPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			int targetFps = Math.clamp(current.payload().targetFps(), 1, MAX_TARGET_FPS);
			long durationMs = current.frameCount * 1_000L / targetFps;
			Lg2.LOGGER.info("Renderer bot finished video recording {} with {} frames ({} ms)", current.payload().requestId(), current.frameCount, durationMs);
			current.markFinalized(durationMs, targetFps);
			client.execute(() -> {
				// A completed encoder is still a pending camera transaction until
				// its MP4 and completion packet have actually reached a server.
				// During a restart canSend() is false for a moment; retain this
				// exact finished job and retry it on JOIN instead of losing 99%.
				sendCompletedRecording(current, durationMs, targetFps);
			});
		} catch (Exception exception) {
			client.execute(() -> {
				sendFailure(current.payload().requestId(), exception.getMessage());
				clearIfMatching(current.payload().requestId());
			});
			Lg2.LOGGER.warn("Renderer bot failed to finish video recording {}", current.payload().requestId(), exception);
		}
	}

	private static boolean sendCompletedRecording(PendingRecording recording, long durationMs, int targetFps) {
		if (recording == null || !isActiveRecording(recording.payload().requestId())) return false;
		if (!ClientPlayNetworking.canSend(RendererBotPayloads.RendererBotVideoRecordingCompleteC2SPayload.TYPE)) return false;
		boolean remoteVolunteer = RendererBotVolunteerClient.isVolunteerRenderer() && !RendererBotClientMode.isEnabled();
		String videoPath = recording.finalPath().toAbsolutePath().toString();
		if (remoteVolunteer) {
			if (recording.needsRemoteUpload()) {
				try {
					long size = Files.size(recording.finalPath());
					if (size <= 0L || size > MAX_REMOTE_VIDEO_UPLOAD_BYTES) {
						throw new IOException("Remote renderer video is too large to upload: " + size + " bytes");
					}
					byte[] videoBytes = Files.readAllBytes(recording.finalPath());
					int chunks = Math.max(1, (videoBytes.length + REMOTE_VIDEO_CHUNK_BYTES - 1) / REMOTE_VIDEO_CHUNK_BYTES);
					if (!ClientPlayNetworking.canSend(RendererBotPayloads.RendererBotVideoFileChunkC2SPayload.TYPE)) return false;
					for (int index = 0; index < chunks; index++) {
						int start = index * REMOTE_VIDEO_CHUNK_BYTES;
						int end = Math.min(videoBytes.length, start + REMOTE_VIDEO_CHUNK_BYTES);
						byte[] chunk = java.util.Arrays.copyOfRange(videoBytes, start, end);
						ClientPlayNetworking.send(new RendererBotPayloads.RendererBotVideoFileChunkC2SPayload(
								recording.payload().requestId(), index, index + 1 == chunks, chunk
						));
					}
					recording.markRemoteUploadSent();
				} catch (IOException exception) {
					sendFailure(recording.payload().requestId(), exception.getMessage());
					return false;
				}
			}
			videoPath = "";
		}
		ClientPlayNetworking.send(new RendererBotPayloads.RendererBotVideoRecordingCompleteC2SPayload(
				recording.payload().requestId(), durationMs, targetFps, videoPath,
				recording.firstPreviewFrame, recording.firstFullFrame
		));
		if (recording.markCompletionSent()) {
			RendererClientDiagnostics.cameraVideoCompleted(recording.payload().requestId());
		}
		return true;
	}

	public static void onVolunteerPreferenceChanged(boolean enabled) {
		if (enabled || RendererBotClientMode.isEnabled()) return;
		abortAll("Volunteer renderer disabled by client");
	}

	private static void abortAll(String message) {
		List<UUID> requestIds;
		synchronized (LOCK) {
			requestIds = new ArrayList<>(RECORDINGS.keySet());
		}
		for (UUID requestId : requestIds) {
			abortRecording(requestId, message);
		}
		synchronized (LOCK) {
			EARLY_FRAME_READY.clear();
			EARLY_VIDEO_FRAMES.clear();
			EARLY_SEALS.clear();
		}
	}

	private static void pauseForServerReconnect() {
		synchronized (LOCK) {
			if (!RECORDINGS.isEmpty()) {
				Lg2.LOGGER.info("Renderer bot paused {} video render(s) while the server reconnects", RECORDINGS.size());
			}
			for (PendingRecording recording : RECORDINGS.values()) {
				if (recording != null) {
					recording.resetCompletionDeliveryForReconnect();
				}
			}
		}
	}

	private static void acknowledgeCompletedRecording(UUID requestId) {
		if (requestId == null) {
			return;
		}
		synchronized (LOCK) {
			PendingRecording recording = RECORDINGS.get(requestId);
			if (recording != null && recording.isFinalized()) {
				RECORDINGS.remove(requestId);
			}
		}
	}

	/** Used by the shadow manager to retain only worlds that an encoder can resume. */
	public static boolean retainsShadowSession(UUID renderSessionId) {
		if (renderSessionId == null) return false;
		synchronized (LOCK) {
			for (PendingRecording recording : RECORDINGS.values()) {
				if (recording != null && renderSessionId.equals(recording.payload().renderSessionId())) return true;
			}
		}
		return false;
	}

	private static void abortRecording(UUID requestId, String message) {
		PendingRecording current;
		synchronized (LOCK) {
			current = RECORDINGS.remove(requestId);
		}
		if (current == null) {
			return;
		}
		try {
			current.encoderInput.close();
		} catch (IOException ignored) {
		}
		current.encoderProcess.destroyForcibly();
		try {
			Files.deleteIfExists(current.tempPath());
		} catch (IOException ignored) {
		}
		sendFailure(current.payload().requestId(), message);
	}

	private static boolean isActiveRecording(UUID requestId) {
		synchronized (LOCK) { return requestId != null && RECORDINGS.containsKey(requestId); }
	}

	private static void clearIfMatching(UUID requestId) {
		synchronized (LOCK) {
			RECORDINGS.remove(requestId);
		}
	}

	private static Process startEncoderProcess(Path outputPath, int width, int height, int fps) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(ffmpegBinary());
		command.add("-y");
		command.add("-loglevel");
		command.add("error");
		command.add("-f");
		command.add("rawvideo");
		command.add("-pix_fmt");
		command.add("rgb24");
		command.add("-s");
		command.add(width + "x" + height);
		command.add("-r");
		command.add(Integer.toString(Math.max(1, fps)));
		command.add("-i");
		command.add("pipe:0");
		command.add("-an");
		command.add("-c:v");
		command.add("libx264");
		command.add("-preset");
		command.add(ENCODER_PRESET.isBlank() ? "fast" : ENCODER_PRESET);
		command.add("-crf");
		command.add(Integer.toString(ENCODER_CRF));
		command.add("-g");
		command.add(Integer.toString(Math.max(1, fps * 2)));
		command.add("-movflags");
		command.add("+faststart");
		command.add("-pix_fmt");
		command.add("yuv420p");
		command.add(outputPath.toAbsolutePath().toString());
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectError(ProcessBuilder.Redirect.DISCARD);
		return builder.start();
	}

	private static String ffmpegBinary() {
		String property = System.getProperty("lg2.ffmpegBin");
		if (property != null && !property.isBlank()) {
			return property.trim();
		}
		return DEFAULT_FFMPEG_BIN;
	}

	private static int computeRenderWidth(RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload payload) {
		int fullWidth = Math.max(1, payload.fullWidth());
		int fullHeight = Math.max(1, payload.fullHeight());
		double minScale = Math.max(MIN_RENDER_WIDTH / (double) fullWidth, MIN_RENDER_HEIGHT / (double) fullHeight);
		double maxScale = Math.min(MAX_RENDER_WIDTH / (double) fullWidth, MAX_RENDER_HEIGHT / (double) fullHeight);
		double scale = Math.max(1.0D, Math.min(maxScale, Math.max(minScale, TARGET_RENDER_SCALE)));
		return encoderCompatibleDimension((int) Math.round(fullWidth * scale), MAX_RENDER_WIDTH);
	}

	private static int computeRenderHeight(RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload payload, int renderWidth) {
		double aspect = Math.max(1.0D, payload.fullHeight()) / (double) Math.max(1.0D, payload.fullWidth());
		return encoderCompatibleDimension((int) Math.round(renderWidth * aspect), MAX_RENDER_HEIGHT);
	}

	/** libx264's yuv420p output needs an even width and height. */
	private static int encoderCompatibleDimension(int value, int maximum) {
		int dimension = Math.clamp(value, 2, Math.max(2, maximum));
		return (dimension & 1) == 0 ? dimension : Math.max(2, dimension - 1);
	}

	private static byte[] argbToRgb(int[] pixels) {
		byte[] rgb = new byte[pixels.length * 3];
		for (int i = 0; i < pixels.length; i++) {
			int pixel = pixels[i];
			int offset = i * 3;
			rgb[offset] = (byte) ((pixel >> 16) & 0xFF);
			rgb[offset + 1] = (byte) ((pixel >> 8) & 0xFF);
			rgb[offset + 2] = (byte) (pixel & 0xFF);
		}
		return rgb;
	}

	private static long markRecordingFrameInFlight(UUID requestId, long nowNanos) {
		synchronized (LOCK) {
			PendingRecording recording = RECORDINGS.get(requestId);
			if (recording == null
					|| recording.frameInFlight
					|| recording.finishScheduled
					|| !recording.canRenderCurrentFrame()) {
				return 0L;
			}
			return recording.beginFrameAttempt(nowNanos);
		}
	}

	private static void writeFrameCopiesLocked(PendingRecording recording, byte[] rgb) throws IOException {
		if (recording == null || rgb == null) {
			return;
		}
		recording.encoderInput.write(rgb);
		recording.encoderInput.flush();
		recording.frameCount++;
	}

	private static void clearRecordingFrameInFlight(UUID requestId, long frameAttempt) {
		synchronized (LOCK) {
			PendingRecording recording = RECORDINGS.get(requestId);
			if (recording != null) {
				recording.cancelFrameAttempt(frameAttempt);
			}
		}
	}

	private static boolean clearStalledRecordingFrame(PendingRecording recording, long nowNanos) {
		if (recording == null) {
			return false;
		}
		synchronized (LOCK) {
			PendingRecording active = RECORDINGS.get(recording.payload().requestId());
			return active == recording && active.clearFrameAttemptIfStalled(nowNanos);
		}
	}

	private static RendererBotOffscreenWorldRenderer.RenderRequest recordingRenderRequest(
			PendingRecording recording,
			int captureWidth,
			int captureHeight
	) {
		RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload payload = recording.payload();
		RendererBotPayloads.VideoCameraFrame frame = recording.currentCameraFrame();
		return new RendererBotOffscreenWorldRenderer.RenderRequest(
				payload.renderSessionId(),
				payload.dimensionId(),
				null,
				frame.x(),
				frame.y(),
				frame.z(),
				frame.yaw(),
				frame.pitch(),
				payload.fovDegrees(),
				captureWidth,
				captureHeight,
				false,
				0.0F,
				false
		);
	}

	private static byte[] quantizeScaledFrame(int[] sourcePixels, int sourceWidth, int sourceHeight, int outputWidth, int outputHeight) {
		double sourceAspect = sourceWidth / (double) Math.max(1, sourceHeight);
		double targetAspect = outputWidth / (double) Math.max(1, outputHeight);
		double cropX = 0.0D;
		double cropY = 0.0D;
		double cropWidth = sourceWidth;
		double cropHeight = sourceHeight;
		if (sourceAspect > targetAspect) {
			cropWidth = sourceHeight * targetAspect;
			cropX = (sourceWidth - cropWidth) * 0.5D;
		} else if (sourceAspect < targetAspect) {
			cropHeight = sourceWidth / targetAspect;
			cropY = (sourceHeight - cropHeight) * 0.5D;
		}

		byte[] output = new byte[Math.max(1, outputWidth * outputHeight)];
		double sampleSpanX = cropWidth / Math.max(1.0D, outputWidth);
		double sampleSpanY = cropHeight / Math.max(1.0D, outputHeight);
		for (int y = 0; y < outputHeight; y++) {
			double sampleY = cropY + ((y + 0.5D) / outputHeight) * cropHeight;
			for (int x = 0; x < outputWidth; x++) {
				double sampleX = cropX + ((x + 0.5D) / outputWidth) * cropWidth;
				int rgb = sampleScaledRgb(sourcePixels, sourceWidth, sourceHeight, sampleX, sampleY, sampleSpanX, sampleSpanY);
				output[y * outputWidth + x] = MapPaletteQuantizer.quantizeDithered(rgb, x, y);
			}
		}
		return output;
	}

	private static int sampleScaledRgb(int[] sourcePixels, int sourceWidth, int sourceHeight, double sampleX, double sampleY, double sampleSpanX, double sampleSpanY) {
		if (sampleSpanX > 1.15D || sampleSpanY > 1.15D) {
			double offsetX = sampleSpanX * 0.25D;
			double offsetY = sampleSpanY * 0.25D;
			int rgbA = sampleBilinearRgb(sourcePixels, sourceWidth, sourceHeight, sampleX - offsetX, sampleY - offsetY);
			int rgbB = sampleBilinearRgb(sourcePixels, sourceWidth, sourceHeight, sampleX + offsetX, sampleY - offsetY);
			int rgbC = sampleBilinearRgb(sourcePixels, sourceWidth, sourceHeight, sampleX - offsetX, sampleY + offsetY);
			int rgbD = sampleBilinearRgb(sourcePixels, sourceWidth, sourceHeight, sampleX + offsetX, sampleY + offsetY);
			return averageRgb(rgbA, rgbB, rgbC, rgbD);
		}
		return sampleBilinearRgb(sourcePixels, sourceWidth, sourceHeight, sampleX, sampleY);
	}

	private static int sampleBilinearRgb(int[] sourcePixels, int sourceWidth, int sourceHeight, double sampleX, double sampleY) {
		double clampedX = Math.max(0.0D, Math.min(Math.max(0, sourceWidth - 1), sampleX));
		double clampedY = Math.max(0.0D, Math.min(Math.max(0, sourceHeight - 1), sampleY));
		int x0 = Math.clamp((int) Math.floor(clampedX), 0, sourceWidth - 1);
		int y0 = Math.clamp((int) Math.floor(clampedY), 0, sourceHeight - 1);
		int x1 = Math.min(x0 + 1, sourceWidth - 1);
		int y1 = Math.min(y0 + 1, sourceHeight - 1);
		double tx = clampedX - x0;
		double ty = clampedY - y0;

		int c00 = sourcePixels[y0 * sourceWidth + x0] & 0xFFFFFF;
		int c10 = sourcePixels[y0 * sourceWidth + x1] & 0xFFFFFF;
		int c01 = sourcePixels[y1 * sourceWidth + x0] & 0xFFFFFF;
		int c11 = sourcePixels[y1 * sourceWidth + x1] & 0xFFFFFF;

		int red = bilinearChannel((c00 >> 16) & 0xFF, (c10 >> 16) & 0xFF, (c01 >> 16) & 0xFF, (c11 >> 16) & 0xFF, tx, ty);
		int green = bilinearChannel((c00 >> 8) & 0xFF, (c10 >> 8) & 0xFF, (c01 >> 8) & 0xFF, (c11 >> 8) & 0xFF, tx, ty);
		int blue = bilinearChannel(c00 & 0xFF, c10 & 0xFF, c01 & 0xFF, c11 & 0xFF, tx, ty);
		return (red << 16) | (green << 8) | blue;
	}

	private static int bilinearChannel(int c00, int c10, int c01, int c11, double tx, double ty) {
		double top = c00 + (c10 - c00) * tx;
		double bottom = c01 + (c11 - c01) * tx;
		return Math.clamp((int) Math.round(top + (bottom - top) * ty), 0, 255);
	}

	private static int averageRgb(int a, int b, int c, int d) {
		int red = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF) + ((c >> 16) & 0xFF) + ((d >> 16) & 0xFF) + 2) >> 2;
		int green = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF) + ((c >> 8) & 0xFF) + ((d >> 8) & 0xFF) + 2) >> 2;
		int blue = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF) + 2) >> 2;
		return (red << 16) | (green << 8) | blue;
	}

	private static double doubleProperty(String key, double fallback) {
		String raw = System.getProperty(key);
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return Double.parseDouble(raw.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static void sendFailure(UUID requestId, String message) {
		if (requestId == null) return;
		RendererClientDiagnostics.cameraVideoFailed(requestId, message);
		if (!ClientPlayNetworking.canSend(RendererBotPayloads.RendererBotCaptureFailureC2SPayload.TYPE)) return;
		ClientPlayNetworking.send(new RendererBotPayloads.RendererBotCaptureFailureC2SPayload(
				requestId,
				message == null || message.isBlank() ? "Renderer bot video recording failed" : message
		));
	}

	private static final class PendingRecording {
		private final RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload payload;
		private final List<RendererBotPayloads.VideoCameraFrame> cameraFrames;
		private final Process encoderProcess;
		private final OutputStream encoderInput;
		private final Path tempPath;
		private final Path finalPath;
		private final int captureWidth;
		private final int captureHeight;
		private byte[] firstPreviewFrame;
		private byte[] firstFullFrame;
		private volatile boolean frameInFlight;
		private long frameAttemptSequence;
		private long activeFrameAttempt;
		private long frameInFlightSinceNanos;
		private long snapshotWaitStartedNanos;
		private volatile boolean finishScheduled;
		private volatile int frameCount;
		private boolean recordingStartNotified;
		private boolean finalized;
		private long completedDurationMs;
		private int completedFps;
		private boolean remoteUploadSent;
		private boolean completionSent;
		private long lastCompletionSentAtMillis;
		private int nextFrameIndex;
		private int serverReadyFrameIndex = -1;
		private boolean sealed;

		private PendingRecording(
				RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload payload,
				Process encoderProcess,
				OutputStream encoderInput,
				Path tempPath,
				Path finalPath,
				int captureWidth,
				int captureHeight
		) {
			this.payload = payload;
			this.cameraFrames = new ArrayList<>(payload.cameraFrames());
			this.encoderProcess = encoderProcess;
			this.encoderInput = encoderInput;
			this.tempPath = tempPath;
			this.finalPath = finalPath;
			this.captureWidth = captureWidth;
			this.captureHeight = captureHeight;
		}

		private RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload payload() {
			return this.payload;
		}

		private Path tempPath() {
			return this.tempPath;
		}

		private Path finalPath() {
			return this.finalPath;
		}

		private boolean canRenderCurrentFrame() {
			return this.nextFrameIndex < this.cameraFrames.size()
					&& this.serverReadyFrameIndex == this.nextFrameIndex;
		}

		private boolean needsRenderWork() {
			// Retention for upload/ACK or reconnect is not ownership of the GPU.
			return !this.finishScheduled && !this.finalized && !this.isReplayComplete();
		}

		private long beginFrameAttempt(long nowNanos) {
			this.frameInFlight = true;
			this.activeFrameAttempt = ++this.frameAttemptSequence;
			this.frameInFlightSinceNanos = nowNanos;
			return this.activeFrameAttempt;
		}

		private boolean isCurrentFrameAttempt(long attempt) {
			return this.frameInFlight && attempt > 0L && attempt == this.activeFrameAttempt;
		}

		private void cancelFrameAttempt(long attempt) {
			if (this.isCurrentFrameAttempt(attempt)) {
				this.frameInFlight = false;
				this.frameInFlightSinceNanos = 0L;
			}
		}

		private boolean finishFrameAttempt(long attempt) {
			if (!this.isCurrentFrameAttempt(attempt)) {
				return false;
			}
			this.frameInFlight = false;
			this.frameInFlightSinceNanos = 0L;
			return true;
		}

		private boolean clearFrameAttemptIfStalled(long nowNanos) {
			if (!this.frameInFlight || this.frameInFlightSinceNanos <= 0L
					|| nowNanos - this.frameInFlightSinceNanos < REPLAY_FRAME_STALL_TIMEOUT_NANOS) {
				return false;
			}
			// Invalidate the old asynchronous callback before retrying the same
			// camera pose.  If it wakes up later it can only release its image;
			// it cannot encode a duplicate frame or advance replay progress.
			this.activeFrameAttempt = ++this.frameAttemptSequence;
			this.frameInFlight = false;
			this.frameInFlightSinceNanos = 0L;
			return true;
		}

		private boolean isReplayComplete() {
			return this.sealed && this.nextFrameIndex >= this.cameraFrames.size();
		}

		private boolean isFinalized() { return this.finalized; }
		private long completedDurationMs() { return this.completedDurationMs; }
		private int completedFps() { return this.completedFps; }
		private void markFinalized(long durationMs, int fps) {
			this.finalized = true;
			this.completedDurationMs = Math.max(0L, durationMs);
			this.completedFps = Math.max(1, fps);
			this.remoteUploadSent = false;
			this.completionSent = false;
			this.lastCompletionSentAtMillis = 0L;
		}

		private boolean needsRemoteUpload() { return !this.remoteUploadSent; }
		private void markRemoteUploadSent() { this.remoteUploadSent = true; }
		private boolean shouldRetryCompletion(long nowMillis) {
			return this.lastCompletionSentAtMillis <= 0L || nowMillis - this.lastCompletionSentAtMillis >= COMPLETION_RESEND_INTERVAL_MS;
		}
		private boolean markCompletionSent() {
			boolean firstSubmission = !this.completionSent;
			this.completionSent = true;
			this.lastCompletionSentAtMillis = System.currentTimeMillis();
			return firstSubmission;
		}
		private void resetCompletionDeliveryForReconnect() {
			this.remoteUploadSent = false;
			this.lastCompletionSentAtMillis = 0L;
		}

		private boolean markRecordingStartNotified() {
			if (this.recordingStartNotified || this.frameCount <= 0) {
				return false;
			}
			this.recordingStartNotified = true;
			return true;
		}

		private void markServerFrameReady(int frameIndex) {
			if (frameIndex == this.nextFrameIndex) {
				this.serverReadyFrameIndex = frameIndex;
			}
		}

		private RendererBotPayloads.VideoCameraFrame currentCameraFrame() {
			return this.cameraFrames.get(Math.clamp(this.nextFrameIndex, 0, this.cameraFrames.size() - 1));
		}

		private int currentFrameIndex() {
			return this.nextFrameIndex;
		}

		private int completedFrameCount() {
			return this.nextFrameIndex;
		}

		/** Returns how many frames have been fully written, including the terminal frame. */
		private int advanceAfterEncodedFrame() {
			this.nextFrameIndex++;
			this.serverReadyFrameIndex = -1;
			return this.nextFrameIndex;
		}

		private void appendCameraFrame(int frameIndex, RendererBotPayloads.VideoCameraFrame frame) {
			if (frameIndex < 0 || frame == null || frameIndex < this.cameraFrames.size()) {
				return;
			}
			if (frameIndex != this.cameraFrames.size()) {
				Lg2.LOGGER.warn("Renderer bot received out-of-order video frame {} for {}", frameIndex, this.payload.requestId());
				return;
			}
			this.cameraFrames.add(frame);
		}

		private void seal() {
			this.sealed = true;
		}

		private void reconcileFrames(List<RendererBotPayloads.VideoCameraFrame> frames) {
			// Server shutdown can journal a pose whose packet never reached us.
			// Also respect a shorter, deliberately truncated recovery journal.
			int keep = Math.max(this.nextFrameIndex + (this.frameInFlight ? 1 : 0), frames.size());
			while (this.cameraFrames.size() > keep) this.cameraFrames.removeLast();
			for (int i = this.cameraFrames.size(); i < frames.size(); i++) this.cameraFrames.add(frames.get(i));
			this.serverReadyFrameIndex = -1;
		}

		private void truncateAndSeal(int frameCount) {
			// An already dispatched GPU readback must be allowed to write once;
			// force-closing it corrupts the encoder's MP4 stream.
			int minimum = this.frameInFlight ? this.nextFrameIndex + 1 : this.nextFrameIndex;
			int keep = Math.max(1, Math.max(minimum, Math.min(frameCount, this.cameraFrames.size())));
			while (this.cameraFrames.size() > keep) this.cameraFrames.removeLast();
			this.sealed = true;
		}
	}

}
