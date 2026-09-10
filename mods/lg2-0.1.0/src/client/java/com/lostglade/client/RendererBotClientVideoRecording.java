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
	private static final int REQUIRED_SETTLED_RENDERS = Math.max(1, Integer.getInteger("lg2.rendererBotStableCaptureProbes", 2));
	private static final long FINISH_TIMEOUT_MS = Long.getLong("lg2.rendererBotVideoFinishTimeoutMs", 30_000L);
	private static final long STOPPED_WARMUP_TIMEOUT_MS = Long.getLong("lg2.rendererBotVideoStoppedWarmupTimeoutMs", 12_000L);
	private static final int MIN_RECORDING_FRAMES = Math.max(2, Integer.getInteger("lg2.rendererBotVideoMinFrames", 2));
	private static final int MAX_CATCH_UP_SECONDS = Math.max(1, Integer.getInteger("lg2.rendererBotVideoMaxCatchUpSeconds", 3));
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

	private RendererBotClientVideoRecording() {
	}

	/**
	 * Camera capture uses one shared off-screen target. Background render jobs
	 * must stay out while a hand-held recording is warming up or writing frames.
	 */
	public static boolean hasActiveRecording() {
		synchronized (LOCK) {
			return !RECORDINGS.isEmpty();
		}
	}

	public static void register() {
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> abortAll("Renderer bot disconnected during video recording"));
		ClientTickEvents.END_CLIENT_TICK.register(RendererBotClientVideoRecording::onClientTick);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> beginRecording(payload, context.client()))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoRecordingStopS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> requestStop(payload.requestId()))
		);
	}

	private static void beginRecording(RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload payload, Minecraft client) {
		try {
			String sourceKey = payload.requestId().toString();
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
				RECORDINGS.put(payload.requestId(), new PendingRecording(
						payload,
						process,
						process.getOutputStream(),
						tempPath,
						finalPath,
						captureWidth,
						captureHeight
				));
			}
			Lg2.LOGGER.info(
					"Renderer bot preparing video recording {} at {} fps {}x{} (capture {}x{}, settledRenders={})",
					payload.requestId(),
					targetFps,
					payload.fullWidth(),
					payload.fullHeight(),
					captureWidth,
					captureHeight,
					REQUIRED_SETTLED_RENDERS
			);
		} catch (Exception exception) {
			sendFailure(payload.requestId(), exception.getMessage());
			Lg2.LOGGER.warn("Renderer bot failed to start video recording {}", payload.requestId(), exception);
		}
	}

	private static void requestStop(UUID requestId) {
		synchronized (LOCK) {
			PendingRecording recording = RECORDINGS.get(requestId);
			if (recording == null) {
				return;
			}
			recording.stopRequested = true;
			recording.stopRequestedAtNanos = System.nanoTime();
			Lg2.LOGGER.info("Renderer bot received stop request for video recording {} after {} frames", requestId, recording.frameCount);
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
		long now = System.currentTimeMillis();
		for (PendingRecording current : activeRecordings) {
			if (current == null) {
				continue;
			}
			if (current.stopRequested
					&& !current.recordingStarted()
					&& current.stopRequestedAtNanos != 0L
					&& System.nanoTime() - current.stopRequestedAtNanos >= STOPPED_WARMUP_TIMEOUT_MS * 1_000_000L) {
				abortRecording(current.payload().requestId(), "Renderer bot video scene did not become ready after stopping");
				continue;
			}
			long elapsedMs = current.recordingStartedAtMs == 0L ? 0L : now - current.recordingStartedAtMs;
			if (current.recordingStartedAtMs != 0L && elapsedMs >= current.payload().maxDurationSeconds() * 1_000L) {
				current.stopRequested = true;
				if (current.stopRequestedAtNanos == 0L) {
					current.stopRequestedAtNanos = System.nanoTime();
				}
			}
			if (current.stopRequested && current.canFinish() && !current.frameInFlight && !current.finishScheduled) {
				scheduleFinish(client, current);
			}
		}
		dispatchReadyRecordings(client, System.nanoTime());
	}

	private static void dispatchReadyRecordings(Minecraft client, long nowNanos) {
		if (client == null || client.level == null || RendererBotOffscreenWorldRenderer.isOffscreenRenderActive()) {
			return;
		}
		PendingRecording warmupToRender = null;
		PendingRecording recordingToRender = null;
		synchronized (LOCK) {
			for (PendingRecording current : RECORDINGS.values()) {
				if (current == null || current.frameInFlight || current.finishScheduled) {
					continue;
				}
				if (!current.recordingStarted()) {
					// One shared target can safely service only one warm-up/frame per
					// tick. Prefer warm-up so a just-stopped short video can become
					// valid without waiting behind an older recording.
					if (warmupToRender == null) {
						warmupToRender = current;
					}
				} else if (!current.stopRequested || !current.canFinish()) {
					if (recordingToRender == null) {
						recordingToRender = current;
					}
				}
			}
		}
		if (warmupToRender != null) {
			PendingRecording recording = warmupToRender;
			if (markRecordingWarmupInFlight(recording.payload().requestId())) {
				boolean rendered = RendererBotOffscreenWorldRenderer.renderToTarget(
						client,
						recordingRenderRequest(recording.payload(), recording.captureWidth, recording.captureHeight),
						renderTarget -> completeRecordingWarmup(recording)
				);
				if (!rendered) {
					clearRecordingFrameInFlight(recording.payload().requestId());
				}
			}
			return;
		}
		if (recordingToRender != null) {
			PendingRecording recording = recordingToRender;
			if (!markRecordingFrameInFlight(recording.payload().requestId(), nowNanos)) {
				return;
			}
			boolean rendered = RendererBotOffscreenWorldRenderer.renderToTarget(
					client,
					recordingRenderRequest(recording.payload(), recording.captureWidth, recording.captureHeight),
					renderTarget -> dispatchRecordingFrame(client, recording, renderTarget)
			);
			if (!rendered) {
				clearRecordingFrameInFlight(recording.payload().requestId());
			}
		}
	}

	private static void dispatchRecordingFrame(Minecraft client, PendingRecording recording, RenderTarget renderTarget) {
		try {
			CompletableFuture<NativeImage> sourceFuture = takeScreenshotFuture(renderTarget);
			CompletableFuture<byte[]> previewFuture;
			CompletableFuture<byte[]> fullFuture;
			synchronized (LOCK) {
				PendingRecording active = RECORDINGS.get(recording.payload().requestId());
				if (active == null || active != recording || (active.stopRequested && active.canFinish())) {
					sourceFuture.thenAccept(image -> {
						if (image != null) {
							image.close();
						}
					});
					clearRecordingFrameInFlight(recording.payload().requestId());
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
					previewFuture = CompletableFuture.completedFuture(active.firstPreviewFrame);
					fullFuture = CompletableFuture.completedFuture(active.firstFullFrame);
				}
			}
			CompletableFuture.allOf(sourceFuture, previewFuture, fullFuture).whenComplete((ignored, throwable) -> {
				if (throwable != null) {
					handleRecordingFrameFailure(client, recording, sourceFuture, throwable);
					return;
				}
				RECORD_EXECUTOR.submit(() -> processCapturedFrame(client, List.of(recording), sourceFuture.join(), previewFuture.join(), fullFuture.join()));
			});
		} catch (Throwable throwable) {
			Lg2.LOGGER.warn("Renderer bot recording dispatch failed, falling back to screenshot path for {}", recording.payload().requestId(), throwable);
			Screenshot.takeScreenshot(renderTarget, image -> RECORD_EXECUTOR.submit(() -> processCapturedFrame(client, List.of(recording), image)));
		}
	}

	private static void processCapturedFrame(Minecraft client, List<PendingRecording> recordings, NativeImage image) {
		List<PendingRecording> recordingsStartedNow = new ArrayList<>();
		try {
			int width = image.getWidth();
			int height = image.getHeight();
			int[] pixels = image.makePixelArray();
			byte[] rgb = argbToRgb(pixels);
			synchronized (LOCK) {
				for (PendingRecording current : recordings) {
					PendingRecording active = RECORDINGS.get(current.payload().requestId());
					if (active == null || active != current) {
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
				synchronized (LOCK) {
					for (PendingRecording current : recordings) {
						PendingRecording active = RECORDINGS.get(current.payload().requestId());
						if (active != null && active == current) {
							active.frameInFlight = false;
								if (active.stopRequested && active.canFinish() && !active.finishScheduled) {
								scheduleFinish(client, active);
							}
						}
					}
				}
			});
		}
	}

	private static void processCapturedFrame(
			Minecraft client,
			List<PendingRecording> recordings,
			NativeImage image,
			byte[] previewFrame,
			byte[] fullFrame
	) {
		List<PendingRecording> recordingsStartedNow = new ArrayList<>();
		try {
			int[] pixels = image.makePixelArray();
			byte[] rgb = argbToRgb(pixels);
			synchronized (LOCK) {
				for (PendingRecording current : recordings) {
					PendingRecording active = RECORDINGS.get(current.payload().requestId());
					if (active == null || active != current) {
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
				synchronized (LOCK) {
					for (PendingRecording current : recordings) {
						PendingRecording active = RECORDINGS.get(current.payload().requestId());
						if (active != null && active == current) {
							active.frameInFlight = false;
								if (active.stopRequested && active.canFinish() && !active.finishScheduled) {
								scheduleFinish(client, active);
							}
						}
					}
				}
			});
		}
	}

	private static void handleRecordingFrameFailure(
			Minecraft client,
			PendingRecording recording,
			CompletableFuture<NativeImage> sourceFuture,
			Throwable throwable
	) {
		if (sourceFuture.isDone() && !sourceFuture.isCompletedExceptionally() && !sourceFuture.isCancelled()) {
			RECORD_EXECUTOR.submit(() -> {
				try {
					processCapturedFrame(client, List.of(recording), sourceFuture.join());
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
					ClientPlayNetworking.send(new RendererBotPayloads.RendererBotVideoRecordingStartedC2SPayload(
							recording.payload().requestId()
					));
				} catch (RuntimeException exception) {
					Lg2.LOGGER.debug("Renderer bot could not confirm start of video recording {}", recording.payload().requestId(), exception);
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
			appendFinalFrameCopies(current);
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
			client.execute(() -> {
				clearIfMatching(current.payload().requestId());
				sendCompletedRecording(current, durationMs, targetFps);
			});
		} catch (Exception exception) {
			client.execute(() -> {
				clearIfMatching(current.payload().requestId());
				sendFailure(current.payload().requestId(), exception.getMessage());
			});
			Lg2.LOGGER.warn("Renderer bot failed to finish video recording {}", current.payload().requestId(), exception);
		}
	}

	private static void sendCompletedRecording(PendingRecording recording, long durationMs, int targetFps) {
		boolean remoteVolunteer = RendererBotVolunteerClient.isVolunteerRenderer() && !RendererBotClientMode.isEnabled();
		String videoPath = recording.finalPath().toAbsolutePath().toString();
		if (remoteVolunteer) {
			try {
				long size = Files.size(recording.finalPath());
				if (size <= 0L || size > MAX_REMOTE_VIDEO_UPLOAD_BYTES) {
					throw new IOException("Remote renderer video is too large to upload: " + size + " bytes");
				}
				byte[] videoBytes = Files.readAllBytes(recording.finalPath());
				int chunks = Math.max(1, (videoBytes.length + REMOTE_VIDEO_CHUNK_BYTES - 1) / REMOTE_VIDEO_CHUNK_BYTES);
				for (int index = 0; index < chunks; index++) {
					int start = index * REMOTE_VIDEO_CHUNK_BYTES;
					int end = Math.min(videoBytes.length, start + REMOTE_VIDEO_CHUNK_BYTES);
					byte[] chunk = java.util.Arrays.copyOfRange(videoBytes, start, end);
					ClientPlayNetworking.send(new RendererBotPayloads.RendererBotVideoFileChunkC2SPayload(
							recording.payload().requestId(), index, index + 1 == chunks, chunk
					));
				}
				videoPath = "";
			} catch (IOException exception) {
				sendFailure(recording.payload().requestId(), exception.getMessage());
				return;
			}
		}
		ClientPlayNetworking.send(new RendererBotPayloads.RendererBotVideoRecordingCompleteC2SPayload(
				recording.payload().requestId(), durationMs, targetFps, videoPath,
				recording.firstPreviewFrame, recording.firstFullFrame
		));
	}

	private static void abortAll(String message) {
		List<UUID> requestIds;
		synchronized (LOCK) {
			requestIds = new ArrayList<>(RECORDINGS.keySet());
		}
		for (UUID requestId : requestIds) {
			abortRecording(requestId, message);
		}
		RendererBotOffscreenWorldRenderer.clearCaches();
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
		return Math.clamp((int) Math.round(fullWidth * scale), 1, MAX_RENDER_WIDTH);
	}

	private static int computeRenderHeight(RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload payload, int renderWidth) {
		double aspect = Math.max(1.0D, payload.fullHeight()) / (double) Math.max(1.0D, payload.fullWidth());
		return Math.clamp((int) Math.round(renderWidth * aspect), 1, MAX_RENDER_HEIGHT);
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

	private static boolean markRecordingWarmupInFlight(UUID requestId) {
		synchronized (LOCK) {
			PendingRecording recording = RECORDINGS.get(requestId);
			if (recording == null
					|| recording.frameInFlight
					|| recording.finishScheduled
					|| recording.recordingStarted()) {
				return false;
			}
			recording.frameInFlight = true;
			return true;
		}
	}

	private static void completeRecordingWarmup(PendingRecording recording) {
		if (recording == null || recording.payload().requestId() == null) {
			return;
		}
		RendererBotShadowWorldManager.RenderReadiness readiness =
				RendererBotShadowWorldManager.inspectRenderReadiness(recording.payload().renderSessionId());
		boolean started = false;
		int probeCount = 0;
		synchronized (LOCK) {
			PendingRecording active = RECORDINGS.get(recording.payload().requestId());
			if (active != recording) {
				return;
			}
			active.frameInFlight = false;
			if (active.observeReadinessProbe(readiness)) {
				active.markRecordingStarted(System.currentTimeMillis());
				started = true;
				probeCount = active.readinessProbeCount;
			}
		}
		if (started) {
			Lg2.LOGGER.info(
					"Renderer bot started video recording {} after {} settled renders (visibleSections={})",
					recording.payload().requestId(),
					probeCount,
					readiness.visibleSections()
			);
		}
	}

	private static boolean markRecordingFrameInFlight(UUID requestId, long nowNanos) {
		synchronized (LOCK) {
			PendingRecording recording = RECORDINGS.get(requestId);
			if (recording == null
					|| recording.frameInFlight
					|| recording.finishScheduled
					|| !recording.recordingStarted()
					|| (recording.stopRequested && recording.canFinish())) {
				return false;
			}
			int frameCopies = reserveDueFrameCopiesLocked(recording, nowNanos, true);
			if (frameCopies <= 0) {
				return false;
			}
			recording.pendingFrameCopies = frameCopies;
			recording.frameInFlight = true;
			return true;
		}
	}

	private static int reserveDueFrameCopiesLocked(PendingRecording recording, long nowNanos, boolean capCatchUp) {
		if (recording == null) {
			return 0;
		}
		int targetFps = Math.clamp(recording.payload().targetFps(), 1, MAX_TARGET_FPS);
		long intervalNanos = 1_000_000_000L / targetFps;
		if (recording.recordingStartedAtNanos == 0L) {
			recording.recordingStartedAtNanos = nowNanos;
		}
		long elapsedNanos = Math.max(0L, nowNanos - recording.recordingStartedAtNanos);
		long desiredFrameCount = Math.max(1L, ceilDiv(elapsedNanos, intervalNanos));
		long maxFrameCount = (long) targetFps * Math.max(1, recording.payload().maxDurationSeconds());
		desiredFrameCount = Math.min(desiredFrameCount, maxFrameCount);
		long due = desiredFrameCount - recording.scheduledFrameCount;
		if (due <= 0L) {
			return 0;
		}
		long maxCopies = capCatchUp ? Math.max(1L, (long) targetFps * MAX_CATCH_UP_SECONDS) : due;
		int copies = (int) Math.min(due, maxCopies);
		recording.scheduledFrameCount += copies;
		return copies;
	}

	private static long ceilDiv(long value, long divisor) {
		if (value <= 0L) {
			return 0L;
		}
		return (value + divisor - 1L) / divisor;
	}

	private static void writeFrameCopiesLocked(PendingRecording recording, byte[] rgb) throws IOException {
		if (recording == null || rgb == null) {
			return;
		}
		int copies = Math.max(1, recording.pendingFrameCopies);
		recording.pendingFrameCopies = 0;
		recording.lastRgbFrame = rgb;
		for (int index = 0; index < copies; index++) {
			recording.encoderInput.write(rgb);
		}
		recording.encoderInput.flush();
		recording.frameCount += copies;
	}

	private static void appendFinalFrameCopies(PendingRecording recording) throws IOException {
		synchronized (LOCK) {
			if (recording == null || recording.lastRgbFrame == null) {
				return;
			}
			long stopAtNanos = recording.stopRequestedAtNanos != 0L ? recording.stopRequestedAtNanos : System.nanoTime();
			int copies = reserveDueFrameCopiesLocked(recording, stopAtNanos, false);
			if (copies <= 0) {
				return;
			}
			recording.pendingFrameCopies = copies;
			writeFrameCopiesLocked(recording, recording.lastRgbFrame);
		}
	}

	private static void clearRecordingFrameInFlight(UUID requestId) {
		synchronized (LOCK) {
			PendingRecording recording = RECORDINGS.get(requestId);
			if (recording != null) {
				if (recording.pendingFrameCopies > 0) {
					recording.scheduledFrameCount = Math.max(0L, recording.scheduledFrameCount - recording.pendingFrameCopies);
					recording.pendingFrameCopies = 0;
				}
				recording.frameInFlight = false;
			}
		}
	}

	private static RendererBotOffscreenWorldRenderer.RenderRequest recordingRenderRequest(
			RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload payload,
			int captureWidth,
			int captureHeight
	) {
		return new RendererBotOffscreenWorldRenderer.RenderRequest(
				payload.renderSessionId(),
				payload.dimensionId(),
				payload.followEntityUuid(),
				payload.expectedX(),
				payload.expectedY(),
				payload.expectedZ(),
				payload.expectedYaw(),
				payload.expectedPitch(),
				payload.fovDegrees(),
				captureWidth,
				captureHeight,
				false,
				false,
				0.0D,
				0.0D,
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
		if (requestId == null) {
			return;
		}
		ClientPlayNetworking.send(new RendererBotPayloads.RendererBotCaptureFailureC2SPayload(
				requestId,
				message == null || message.isBlank() ? "Renderer bot video recording failed" : message
		));
	}

	private static final class PendingRecording {
		private final RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload payload;
		private final Process encoderProcess;
		private final OutputStream encoderInput;
		private final Path tempPath;
		private final Path finalPath;
		private final int captureWidth;
		private final int captureHeight;
		private byte[] firstPreviewFrame;
		private byte[] firstFullFrame;
		private int readinessProbeCount;
		private int settledRenderCount;
		private long settledContentRevision = Long.MIN_VALUE;
		private long recordingStartedAtMs;
		private volatile boolean stopRequested;
		private volatile boolean frameInFlight;
		private volatile boolean finishScheduled;
		private volatile int frameCount;
		private volatile int pendingFrameCopies;
		private long recordingStartedAtNanos;
		private long scheduledFrameCount;
		private long stopRequestedAtNanos;
		private byte[] lastRgbFrame;
		private boolean recordingStartNotified;

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

		private boolean recordingStarted() {
			return this.recordingStartedAtMs != 0L;
		}

		private boolean canFinish() {
			return this.frameCount >= MIN_RECORDING_FRAMES
					&& this.firstPreviewFrame != null
					&& this.firstFullFrame != null;
		}

		private boolean markRecordingStartNotified() {
			if (this.recordingStartNotified || this.frameCount <= 0) {
				return false;
			}
			this.recordingStartNotified = true;
			return true;
		}

		private boolean observeReadinessProbe(RendererBotShadowWorldManager.RenderReadiness readiness) {
			this.readinessProbeCount++;
			if (readiness == null || !readiness.settled()) {
				this.settledRenderCount = 0;
				this.settledContentRevision = Long.MIN_VALUE;
				return false;
			}
			if (this.settledContentRevision != readiness.contentRevision()) {
				this.settledContentRevision = readiness.contentRevision();
				this.settledRenderCount = 1;
				return this.settledRenderCount >= REQUIRED_SETTLED_RENDERS;
			}
			this.settledRenderCount++;
			return this.settledRenderCount >= REQUIRED_SETTLED_RENDERS;
		}

		private void markRecordingStarted(long startedAtMs) {
			this.recordingStartedAtMs = startedAtMs;
		}
	}

}
