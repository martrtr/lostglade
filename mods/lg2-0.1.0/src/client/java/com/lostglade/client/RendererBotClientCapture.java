package com.lostglade.client;

import com.lostglade.Lg2;
import com.lostglade.mixin.client.GameRendererRenderLevelInvoker;
import com.lostglade.mixin.client.MinecraftMainRenderTargetAccessor;
import com.lostglade.network.RendererBotPayloads;
import com.lostglade.server.CameraMediaCache;
import com.lostglade.server.map.MapPaletteQuantizer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RendererBotClientCapture {
	private static final Object LOCK = new Object();
	// The server normally allows 15 seconds for a photo.  This is only a
	// last-resort failure limit, not a capture delay: a settled camera sends its
	// frame as soon as its final render is stable.
	private static final long LOCAL_CAPTURE_TIMEOUT_MS = Long.getLong("lg2.rendererBotLocalCaptureTimeoutMs", 14_000L);
	private static final long RECENT_FRAME_TTL_MS = Long.getLong("lg2.rendererBotRecentFrameTtlMs", 175L);
	private static final int DEFAULT_WARMUP_FRAMES = Math.max(1, Integer.getInteger("lg2.rendererBotWarmupFrames", 2));
	private static final int CAPTURE_REQUIRED_SETTLED_RENDERS = Math.max(1, Integer.getInteger("lg2.rendererBotStableCaptureProbes", 2));
	private static final int CAPTURE_THREADS = Math.max(1, Math.min(
			Integer.getInteger("lg2.rendererBotCaptureThreads", recommendedCaptureThreads()),
			recommendedCaptureThreads()
	));
	private static final double TARGET_RENDER_SCALE = Math.max(1.0D, doubleProperty("lg2.rendererBotPhotoRenderScale", 2.5D));
	private static final int MIN_RENDER_WIDTH = Math.max(128, Integer.getInteger("lg2.rendererBotMinRenderWidth", 1024));
	private static final int MIN_RENDER_HEIGHT = Math.max(128, Integer.getInteger("lg2.rendererBotMinRenderHeight", 768));
	private static final int MAX_RENDER_WIDTH = Math.max(MIN_RENDER_WIDTH, Integer.getInteger("lg2.rendererBotMaxRenderWidth", 3072));
	private static final int MAX_RENDER_HEIGHT = Math.max(MIN_RENDER_HEIGHT, Integer.getInteger("lg2.rendererBotMaxRenderHeight", 2048));
	private static final int MAX_LIVE_STREAM_FPS = 20;
	// Pose packets arrive once per server tick.  Blend the two adjacent samples
	// locally so a piston-driven camera does not turn a two-tick vanilla move
	// into two hard cuts in the renderer-bot output.
	private static final long LIVE_POSE_INTERPOLATION_NANOS = 50_000_000L;
	// A GPU readback is asynchronous and can take longer than one render tick.
	// Keeping a small bounded window lets rendering, readback, palette conversion
	// and network sending overlap instead of making every live frame wait for the
	// previous one to finish.  The server discards late frames by capture time.
	private static final int MAX_LIVE_STREAM_FRAMES_IN_FLIGHT = Math.clamp(
			Integer.getInteger("lg2.rendererBotLiveFramesInFlight", 3),
			1,
			6
	);
	private static final long MAP_TILE_TIMEOUT_MS = Long.getLong("lg2.rendererBotMapTileTimeoutMs", 60_000L);
	private static final long ITEM_ICON_TIMEOUT_MS = Long.getLong("lg2.rendererBotItemIconTimeoutMs", 30_000L);
	private static final int MAP_TILE_WARMUP_FRAMES = Math.max(0, Integer.getInteger("lg2.rendererBotMapTileWarmupFrames", 8));
	private static final int ITEM_ICON_ALPHA_THRESHOLD = 6;
	private static final boolean LIVE_STREAM_DITHERING = Boolean.getBoolean("lg2.rendererBotLiveStreamDithering");
	private static final int LIVE_STREAM_PARALLEL_PIXELS_THRESHOLD = Math.max(65_536, Integer.getInteger("lg2.rendererBotLiveParallelPixelsThreshold", 131_072));
	private static final ExecutorService CAPTURE_EXECUTOR = Executors.newFixedThreadPool(CAPTURE_THREADS, runnable -> {
		Thread thread = new Thread(runnable, "lg2-renderer-bot-capture");
		thread.setDaemon(true);
		thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
		return thread;
	});

	private static final Map<UUID, PendingCapture> PENDING_CAPTURES = new HashMap<>();
	private static final Map<UUID, LiveStreamSession> LIVE_STREAM_SESSIONS = new HashMap<>();
	private static final Map<UUID, PendingMapTile> PENDING_MAP_TILES = new HashMap<>();
	private static long nextMapTileSequence;
	private static final Map<UUID, PendingItemIcon> PENDING_ITEM_ICONS = new HashMap<>();
	private static volatile CapturedFrame latestFrame;
	private static TextureTarget itemIconRenderTarget;

	private RendererBotClientCapture() {
	}

	private static int recommendedCaptureThreads() {
		return Math.max(1, Math.min(2, Math.max(1, (Runtime.getRuntime().availableProcessors() - 1) / 2)));
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				RendererBotVolunteerClient.sendRendererHello()
		);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearAllSessions());
		ClientTickEvents.END_CLIENT_TICK.register(RendererBotClientCapture::onClientTick);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotCaptureRequestS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> beginCapture(payload, context.client()))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotLiveStreamStartS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> beginLiveStream(payload, context.client()))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotLiveStreamPoseS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> updateLiveStreamPose(payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotLiveStreamStopS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> clearLiveStreamSession(payload.streamId()))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotMapTileRequestS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> beginMapTile(payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotItemIconRequestS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> beginItemIcon(payload))
		);
	}

	private static void beginCapture(RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload, Minecraft client) {
		long now = System.currentTimeMillis();
		RendererBotShadowWorldManager.hideEntityFromSession(payload.renderSessionId(), payload.hiddenEntityUuid());
		CapturedFrame cached = latestFrame;
		if (cached != null && cached.matches(payload) && cached.capturedAtMillis() + RECENT_FRAME_TTL_MS >= now) {
			Lg2.LOGGER.info("Renderer bot reusing hot cached frame for {}", payload.requestId());
			sendFrame(payload, cached.previewPixels(), cached.fullPixels());
			return;
		}

		int renderWidth = computeRenderWidth(payload);
		int renderHeight = computeRenderHeight(payload, renderWidth);
		synchronized (LOCK) {
			PENDING_CAPTURES.put(payload.requestId(), new PendingCapture(payload, now));
		}
		Lg2.LOGGER.info(
				"Renderer bot received capture request {} preview={}x{} full={}x{} render={}x{} stableProbes={}",
				payload.requestId(),
				payload.previewWidth(),
				payload.previewHeight(),
				payload.fullWidth(),
				payload.fullHeight(),
				renderWidth,
				renderHeight,
				CAPTURE_REQUIRED_SETTLED_RENDERS
		);

	}

	private static void beginLiveStream(RendererBotPayloads.RendererBotLiveStreamStartS2CPayload payload, Minecraft client) {
		int renderWidth = computeLiveRenderWidth(payload);
		int renderHeight = computeLiveRenderHeight(payload, renderWidth);
		int warmupFrames = DEFAULT_WARMUP_FRAMES;
		synchronized (LOCK) {
			LIVE_STREAM_SESSIONS.put(payload.streamId(), new LiveStreamSession(
					payload,
					System.currentTimeMillis(),
					warmupFrames
			));
		}
		hideLiveStreamCameraCarriers(payload);
		Lg2.LOGGER.info(
				"Renderer bot started live stream {} at {} fps {}x{} (render {}x{}, warmup={})",
				payload.streamId(),
				Math.clamp(Math.max(1, payload.targetFps()), 1, MAX_LIVE_STREAM_FPS),
				payload.fullWidth(),
				payload.fullHeight(),
				renderWidth,
				renderHeight,
				warmupFrames
		);
	}

	private static void updateLiveStreamPose(RendererBotPayloads.RendererBotLiveStreamPoseS2CPayload payload) {
		if (payload == null || payload.streamId() == null) {
			return;
		}
		synchronized (LOCK) {
			LiveStreamSession session = LIVE_STREAM_SESSIONS.get(payload.streamId());
			if (session != null) {
				session.updatePose(new LiveStreamPose(
						payload.x(),
						payload.y(),
						payload.z(),
						payload.yaw(),
						payload.pitch(),
						payload.cameraBankRadians()
				), System.nanoTime());
			}
		}
	}

	private static void beginMapTile(RendererBotPayloads.RendererBotMapTileRequestS2CPayload payload) {
		if (payload == null || payload.requestId() == null) {
			return;
		}
		synchronized (LOCK) {
			PENDING_MAP_TILES.put(payload.requestId(), new PendingMapTile(payload, System.currentTimeMillis(), nextMapTileSequence++));
		}
	}

	private static void beginItemIcon(RendererBotPayloads.RendererBotItemIconRequestS2CPayload payload) {
		if (payload == null || payload.requestId() == null || payload.stack() == null || payload.stack().isEmpty()) {
			return;
		}
		synchronized (LOCK) {
			PENDING_ITEM_ICONS.put(payload.requestId(), new PendingItemIcon(payload, System.currentTimeMillis()));
		}
	}

	private static void onClientTick(Minecraft client) {
		List<PendingCapture> captures;
		List<LiveStreamSession> liveStreams;
		List<PendingMapTile> mapTiles;
		List<PendingItemIcon> itemIcons;
		synchronized (LOCK) {
			captures = new ArrayList<>(PENDING_CAPTURES.values());
			liveStreams = new ArrayList<>(LIVE_STREAM_SESSIONS.values());
			mapTiles = new ArrayList<>(PENDING_MAP_TILES.values());
			itemIcons = new ArrayList<>(PENDING_ITEM_ICONS.values());
		}
		long now = System.currentTimeMillis();
		// A hand-held recording exclusively owns the GL scene. Keep unrelated
		// requests queued without converting that intentional pause into a timeout.
		boolean handVideoActive = RendererBotClientVideoRecording.hasActiveRecording();
		for (PendingCapture capture : captures) {
			if (!handVideoActive && capture != null && !capture.screenshotRequested() && now - capture.requestStartedAt() >= LOCAL_CAPTURE_TIMEOUT_MS) {
				RendererBotShadowWorldManager.RenderReadiness readiness =
						RendererBotShadowWorldManager.inspectRenderReadiness(capture.payload().renderSessionId());
				Lg2.LOGGER.warn(
					"Renderer bot capture {} timed out (contentReady={}, frameRendered={}, allSections={}, queues={}/{}, visibleSections={}, revision={})",
					capture.payload().requestId(),
					readiness.contentReady(),
					readiness.currentContentRendered(),
					readiness.allSectionsRendered(),
						readiness.compileQueueSize(),
						readiness.uploadQueueSize(),
						readiness.visibleSections(),
						readiness.contentRevision()
				);
				sendFailure(capture.payload(), "Renderer bot client did not produce a rendered frame in time");
				clearPendingCapture(capture.payload().requestId());
			}
		}
		for (LiveStreamSession liveStream : liveStreams) {
			if (liveStream == null || !liveStream.canScheduleFrame()) {
				continue;
			}
			if (!handVideoActive && now - liveStream.startedAtMillis() >= LOCAL_CAPTURE_TIMEOUT_MS && liveStream.lastFrameAtNanos() == 0L) {
				sendLiveFailure(liveStream.payload(), "Renderer bot live stream did not produce a rendered frame in time");
				clearLiveStreamSession(liveStream.payload().streamId());
			}
		}
		for (PendingMapTile mapTile : mapTiles) {
			if (mapTile == null || mapTile.rendering()) {
				continue;
			}
			if (!handVideoActive && now - mapTile.requestStartedAt() >= MAP_TILE_TIMEOUT_MS) {
				sendMapTileFailure(mapTile.payload(), "Renderer bot map tile did not become ready in time");
				clearPendingMapTile(mapTile.payload().requestId());
			}
		}
		for (PendingItemIcon itemIcon : itemIcons) {
			if (itemIcon == null || itemIcon.rendering()) {
				continue;
			}
			if (now - itemIcon.requestStartedAt() >= ITEM_ICON_TIMEOUT_MS) {
				sendItemIconFailure(itemIcon.payload(), "Renderer bot item icon did not render in time");
				clearPendingItemIcon(itemIcon.payload().requestId());
			}
		}
		// A camera photo/video owns the off-screen target until its final readback.
		// Map tiles, item icons and tiny monitor streams may otherwise render into
		// the same target later in this tick and invalidate that readback.
		boolean cameraCapturePending = hasPendingCameraCapture();
		if (!cameraCapturePending && !handVideoActive) {
			dispatchReadyItemIconRender(client);
		}
		// A hand-held video has priority over a newly requested photo. Both need
		// the same render target, so postponing the photo for these few recording
		// ticks is safer than allowing two sequential renders to race its readback.
		boolean renderedCameraFrame = !handVideoActive && dispatchReadyRenders(client, System.nanoTime());
		if (!cameraCapturePending && !handVideoActive && !renderedCameraFrame) {
			dispatchReadyMapTileRender(client);
		}
	}

	private static boolean hasPendingCameraCapture() {
		synchronized (LOCK) {
			return !PENDING_CAPTURES.isEmpty();
		}
	}

	private static void dispatchReadyItemIconRender(Minecraft client) {
		if (client == null || client.gameRenderer == null || RendererBotOffscreenWorldRenderer.isOffscreenRenderActive()) {
			return;
		}
		PendingItemIcon selected = null;
		synchronized (LOCK) {
			for (PendingItemIcon candidate : PENDING_ITEM_ICONS.values()) {
				if (candidate == null || candidate.rendering()) {
					continue;
				}
				if (selected == null || candidate.requestStartedAt() < selected.requestStartedAt()) {
					selected = candidate;
				}
			}
			if (selected == null) {
				return;
			}
			selected.markRendering();
		}
		dispatchItemIcon(client, selected);
	}

	private static void dispatchItemIcon(Minecraft client, PendingItemIcon itemIcon) {
		RendererBotPayloads.RendererBotItemIconRequestS2CPayload payload = itemIcon.payload();
		try {
			TextureTarget blackTarget = renderItemIconToTarget(client, payload.stack(), payload.iconSize(), 0xFF000000);
			if (blackTarget == null) {
				sendItemIconFailure(payload, "Renderer bot item icon target was not available");
				clearPendingItemIcon(payload.requestId());
				return;
			}
			takeScreenshotFuture(blackTarget).whenComplete((blackImage, blackThrowable) -> client.execute(() -> {
				if (blackThrowable != null) {
					if (blackImage != null) {
						blackImage.close();
					}
					sendItemIconFailure(payload, blackThrowable.getMessage() != null ? blackThrowable.getMessage() : blackThrowable.getClass().getSimpleName());
					clearPendingItemIcon(payload.requestId());
					return;
				}
				try {
					TextureTarget whiteTarget = renderItemIconToTarget(client, payload.stack(), payload.iconSize(), 0xFFFFFFFF);
					if (whiteTarget == null) {
						blackImage.close();
						sendItemIconFailure(payload, "Renderer bot item icon target was not available");
						clearPendingItemIcon(payload.requestId());
						return;
					}
					takeScreenshotFuture(whiteTarget).whenComplete((whiteImage, whiteThrowable) -> {
						if (whiteThrowable != null) {
							blackImage.close();
							if (whiteImage != null) {
								whiteImage.close();
							}
							client.execute(() -> {
								sendItemIconFailure(payload, whiteThrowable.getMessage() != null ? whiteThrowable.getMessage() : whiteThrowable.getClass().getSimpleName());
								clearPendingItemIcon(payload.requestId());
							});
							return;
						}
						CompletableFuture<byte[]> pixelsFuture = CompletableFuture.supplyAsync(() -> {
							try (blackImage; whiteImage) {
								if (blackImage.getWidth() != whiteImage.getWidth() || blackImage.getHeight() != whiteImage.getHeight()) {
									throw new IllegalStateException("Renderer bot item icon matte sizes differ");
								}
								return encodeMattedArgbFrame(
										blackImage.makePixelArray(),
										whiteImage.makePixelArray(),
										blackImage.getWidth(),
										blackImage.getHeight()
								);
							}
						}, CAPTURE_EXECUTOR);
						pixelsFuture.whenComplete((pixels, throwable) -> client.execute(() -> {
							if (throwable != null) {
								sendItemIconFailure(payload, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
								clearPendingItemIcon(payload.requestId());
								return;
							}
							ClientPlayNetworking.send(new RendererBotPayloads.RendererBotItemIconC2SPayload(
									payload.requestId(),
									Math.max(1, payload.iconSize()),
									pixels
							));
							clearPendingItemIcon(payload.requestId());
						}));
					});
				} catch (Throwable throwable) {
					blackImage.close();
					sendItemIconFailure(payload, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
					clearPendingItemIcon(payload.requestId());
				}
			}));
		} catch (Throwable throwable) {
			sendItemIconFailure(payload, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
			clearPendingItemIcon(payload.requestId());
		}
	}

	private static void dispatchReadyMapTileRender(Minecraft client) {
		if (client == null || client.level == null || RendererBotOffscreenWorldRenderer.isOffscreenRenderActive()) {
			return;
		}
		PendingMapTile selected = null;
		synchronized (LOCK) {
			for (PendingMapTile candidate : PENDING_MAP_TILES.values()) {
				if (candidate == null || candidate.rendering()) {
					continue;
				}
				RendererBotPayloads.RendererBotMapTileRequestS2CPayload payload = candidate.payload();
				RendererBotTopDownMapRenderer.TileRequest request = mapTileRenderRequest(payload);
				if (!RendererBotTopDownMapRenderer.hasRequiredChunks(client, request)) {
					continue;
				}
				if (candidate.remainingWarmupFrames() <= 0
						&& !RendererBotTopDownMapRenderer.isTerrainReadyForCapture(client, request)) {
					continue;
				}
				if (selected == null
						|| payload.priorityScore() > selected.payload().priorityScore()
						|| (payload.priorityScore() == selected.payload().priorityScore()
								&& (candidate.requestStartedAt() < selected.requestStartedAt()
										|| (candidate.requestStartedAt() == selected.requestStartedAt() && candidate.sequence() < selected.sequence())))) {
					selected = candidate;
				}
			}
			if (selected == null) {
				return;
			}
			selected.markRendering();
		}
		if (selected == null) {
			return;
		}
		dispatchTopDownMapTile(client, selected);
	}

	private static void dispatchTopDownMapTile(Minecraft client, PendingMapTile mapTile) {
		RendererBotPayloads.RendererBotMapTileRequestS2CPayload payload = mapTile.payload();
		RendererBotTopDownMapRenderer.TileRequest request = mapTileRenderRequest(payload);
		try {
			if (mapTile.remainingWarmupFrames() > 0) {
				boolean warmed = RendererBotTopDownMapRenderer.renderToTarget(client, request, ignored -> {
				});
				if (warmed) {
					mapTile.decrementWarmupFrames();
				}
				clearPendingMapTileRendering(payload.requestId());
				return;
			}
			boolean rendered = RendererBotTopDownMapRenderer.renderToTarget(client, request, renderTarget -> {
				try {
					CompletableFuture<MapTileFrame> pixelsFuture = takeScreenshotFuture(renderTarget).thenApplyAsync(image -> {
						try (image) {
							int[] sourcePixels = image.makePixelArray();
							if (image.getWidth() == payload.tileSize() && image.getHeight() == payload.tileSize()) {
								return encodeExactRgbFrame(sourcePixels, image.getWidth(), image.getHeight());
							}
							return encodeNearestRgbFrame(sourcePixels, image.getWidth(), image.getHeight(), payload.tileSize(), payload.tileSize());
						}
					}, CAPTURE_EXECUTOR).thenCombine(takeDepthCoverageFuture(renderTarget), MapTileFrame::new);
					pixelsFuture.whenComplete((frame, throwable) -> client.execute(() -> {
						if (throwable != null) {
							sendMapTileFailure(payload, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
							clearPendingMapTile(payload.requestId());
							return;
						}
						if (frame == null || frame.pixels() == null) {
							sendMapTileFailure(payload, "Renderer bot map tile did not produce pixels");
							clearPendingMapTile(payload.requestId());
							return;
						}
						// Do not blacklist a colour: a flat block or an ItemDisplay can
						// legitimately fill the entire tile.  The depth buffer instead
						// tells us whether the rendered world contributed any geometry.
						// With a non-air terrain surface known from the synchronized
						// chunks, an all-one-colour frame without depth is necessarily
						// the sky/empty-render race, not a valid map image.
						double expectedGroundCoverage = RendererBotTopDownMapRenderer.expectedGroundCoverage(client, request);
						boolean missingAllTerrain = isUniformRgbFrame(frame.pixels())
								&& frame.depthCoverage().readable()
								&& !frame.depthCoverage().hasWorldDepth()
								&& RendererBotTopDownMapRenderer.expectsTerrain(client, request);
						// Do not accept a frame which is only partly empty either.  This is
						// most visible beneath transparent foliage: its section may be
						// drawn while a lower terrain section is still rebuilding, yielding
						// blue rectangular holes instead of the ground below.
						boolean missingPartialTerrain = frame.depthCoverage().readable()
								&& expectedGroundCoverage >= 0.25D
								&& frame.depthCoverage().worldDepthCoverage() + 0.03D < expectedGroundCoverage;
						if (missingAllTerrain || missingPartialTerrain) {
							if (mapTile.restartAfterMissingTerrain()) {
								RendererBotTopDownMapRenderer.rebuildTerrain(client, request);
								clearPendingMapTileRendering(payload.requestId());
								return;
							}
							sendMapTileFailure(payload, "Renderer bot captured incomplete map terrain");
							clearPendingMapTile(payload.requestId());
							return;
						}
						ClientPlayNetworking.send(new RendererBotPayloads.RendererBotMapTileC2SPayload(
								payload.requestId(),
								payload.lod(),
								payload.tileX(),
								payload.tileZ(),
								System.nanoTime(),
								frame.pixels()
						));
						clearPendingMapTile(payload.requestId());
					}));
				} catch (Throwable throwable) {
					client.execute(() -> {
						sendMapTileFailure(payload, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
						clearPendingMapTile(payload.requestId());
					});
				}
			});
			if (!rendered) {
				clearPendingMapTileRendering(payload.requestId());
			}
		} catch (Throwable throwable) {
			sendMapTileFailure(payload, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
			clearPendingMapTile(payload.requestId());
		}
	}

	private static boolean dispatchReadyRenders(Minecraft client, long nowNanos) {
		if (client == null || client.level == null || RendererBotOffscreenWorldRenderer.isOffscreenRenderActive()) {
			return false;
		}
		PendingCapture captureToRender = null;
		LiveStreamSession liveStreamToRender = null;
		synchronized (LOCK) {
			for (PendingCapture capture : PENDING_CAPTURES.values()) {
				if (capture == null || capture.screenshotRequested()) {
					continue;
				}
				if (captureToRender == null || capture.requestStartedAt() < captureToRender.requestStartedAt()) {
					captureToRender = capture;
				}
			}
			// Do not let a background monitor stream issue a second off-screen render
			// while a full camera frame is warming up or being read back.
			if (captureToRender == null && PENDING_CAPTURES.isEmpty() && !RendererBotClientVideoRecording.hasActiveRecording()) {
				for (LiveStreamSession liveStream : LIVE_STREAM_SESSIONS.values()) {
					if (liveStream == null || !liveStream.canScheduleFrame()) {
						continue;
					}
					if (liveStream.remainingWarmupFrames() > 0) {
						liveStream.decrementWarmupFrames();
						continue;
					}
					long intervalNanos = 1_000_000_000L / Math.clamp(Math.max(1, liveStream.payload().targetFps()), 1, MAX_LIVE_STREAM_FPS);
					if (liveStream.lastFrameAtNanos() != 0L && nowNanos - liveStream.lastFrameAtNanos() < intervalNanos) {
						continue;
					}
					if (liveStreamToRender == null || liveStream.startedAtMillis() < liveStreamToRender.startedAtMillis()) {
						liveStreamToRender = liveStream;
					}
				}
			}
		}
		if (captureToRender != null) {
			PendingCapture capture = captureToRender;
			RendererBotShadowWorldManager.hideEntityFromSession(
					capture.payload().renderSessionId(),
					capture.payload().hiddenEntityUuid()
			);
			if (!markPendingCaptureRequested(capture.payload().requestId())) {
				return false;
			}
			RendererBotOffscreenWorldRenderer.RenderRequest request = captureRenderRequest(capture.payload());
			boolean rendered = RendererBotOffscreenWorldRenderer.renderToTarget(
					client,
					request,
					renderTarget -> {
						if (capture.finalCaptureReady()) {
							dispatchFinalCapture(client, capture, renderTarget);
						} else {
							dispatchCaptureReadinessProbe(capture, request);
						}
					}
			);
			if (!rendered) {
				clearPendingCaptureRequested(capture.payload().requestId());
			}
			return rendered;
		}
		if (liveStreamToRender == null || !markLiveStreamFrameInFlight(liveStreamToRender.payload().streamId(), nowNanos)) {
			return false;
		}
		LiveStreamSession liveStream = liveStreamToRender;
		hideLiveStreamCameraCarriers(liveStream.payload());
		boolean rendered = RendererBotGpuCaptureBackend.isAvailable()
				? RendererBotOffscreenWorldRenderer.renderToTarget(
						client,
						liveRenderRequest(liveStream),
						renderTarget -> dispatchGpuLiveStream(client, liveStream, renderTarget)
				)
				: RendererBotOffscreenWorldRenderer.render(
						client,
						liveRenderRequest(liveStream),
						image -> CAPTURE_EXECUTOR.submit(() -> processSharedFrame(client, List.of(), List.of(liveStream), image))
				);
		if (!rendered) {
			clearLiveStreamFrameInFlight(liveStream.payload().streamId());
		}
		return rendered;
	}

	private static void hideLiveStreamCameraCarriers(RendererBotPayloads.RendererBotLiveStreamStartS2CPayload payload) {
		if (payload == null || payload.hiddenEntityUuids().isEmpty()) {
			return;
		}
		for (UUID entityUuid : payload.hiddenEntityUuids()) {
			RendererBotShadowWorldManager.hideEntityFromSession(payload.renderSessionId(), entityUuid);
		}
	}

	/**
	 * Rendering a shadow camera advances vanilla's section compiler.  The final
	 * photo is withheld until that compiler reports no pending visible work for
	 * consecutive renders with unchanged chunk content.  This intentionally does
	 * not read the colour or depth attachment: those readbacks are for the final
	 * image and can be unavailable while an off-screen framebuffer is active.
	 */
	private static void dispatchCaptureReadinessProbe(
			PendingCapture capture,
			RendererBotOffscreenWorldRenderer.RenderRequest request
	) {
		completeCaptureReadinessProbe(
				capture,
				RendererBotShadowWorldManager.inspectRenderReadiness(request.sessionId())
		);
	}

	private static void completeCaptureReadinessProbe(
			PendingCapture capture,
			RendererBotShadowWorldManager.RenderReadiness readiness
	) {
		if (capture == null || capture.payload().requestId() == null) {
			return;
		}
		boolean ready = false;
		synchronized (LOCK) {
			PendingCapture current = PENDING_CAPTURES.get(capture.payload().requestId());
			if (current != capture) {
				return;
			}
			current.clearScreenshotRequested();
			ready = current.observeReadinessProbe(readiness);
		}
		if (ready) {
			Lg2.LOGGER.debug(
					"Renderer bot camera {} settled after {} probe renders (visibleSections={})",
					capture.payload().requestId(),
					capture.probeCount(),
					readiness.visibleSections()
			);
		}
	}

	private static void dispatchFinalCapture(Minecraft client, PendingCapture capture, RenderTarget renderTarget) {
		if (RendererBotGpuCaptureBackend.isAvailable()) {
			dispatchGpuCapture(client, capture, renderTarget);
			return;
		}
		Screenshot.takeScreenshot(renderTarget, image -> CAPTURE_EXECUTOR.submit(() -> processSharedFrame(client, List.of(capture), List.of(), image)));
	}

	private static void dispatchGpuCapture(Minecraft client, PendingCapture capture, RenderTarget renderTarget) {
		RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload = capture.payload();
		try {
			CompletableFuture<byte[]> previewFuture = RendererBotGpuCaptureBackend.captureQuantizedFrame(
					renderTarget,
					payload.previewWidth(),
					payload.previewHeight(),
					true
			);
			CompletableFuture<byte[]> fullFuture = payload.previewWidth() == payload.fullWidth() && payload.previewHeight() == payload.fullHeight()
					? previewFuture
					: RendererBotGpuCaptureBackend.captureQuantizedFrame(
							renderTarget,
							payload.fullWidth(),
							payload.fullHeight(),
							true
					);
			CompletableFuture<NativeImage> sourceFuture = takeScreenshotFuture(renderTarget);
			CompletableFuture.allOf(previewFuture, fullFuture, sourceFuture).whenComplete((ignored, throwable) -> {
				if (throwable != null) {
					handleGpuCaptureFailure(client, capture, sourceFuture, throwable);
					return;
				}
				CAPTURE_EXECUTOR.submit(() -> completeGpuCapture(client, payload, sourceFuture, previewFuture, fullFuture));
			});
		} catch (Throwable throwable) {
			Lg2.LOGGER.warn("Renderer bot GPU capture path failed before completion, falling back to CPU capture for {}", payload.requestId(), throwable);
			Screenshot.takeScreenshot(renderTarget, image -> CAPTURE_EXECUTOR.submit(() -> processSharedFrame(client, List.of(capture), List.of(), image)));
		}
	}

	private static void dispatchGpuLiveStream(Minecraft client, LiveStreamSession liveStream, RenderTarget renderTarget) {
		RendererBotPayloads.RendererBotLiveStreamStartS2CPayload payload = liveStream.payload();
		try {
			RendererBotGpuCaptureBackend.captureQuantizedFrame(
					renderTarget,
					payload.fullWidth(),
					payload.fullHeight(),
					LIVE_STREAM_DITHERING
			).whenComplete((fullPixels, throwable) -> client.execute(() -> {
				if (throwable != null) {
					Lg2.LOGGER.warn("Renderer bot GPU live stream path failed for {}", payload.streamId(), throwable);
					sendLiveFailure(payload, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
					clearLiveStreamSession(payload.streamId());
					return;
				}
				ClientPlayNetworking.send(new RendererBotPayloads.RendererBotLiveFrameC2SPayload(payload.streamId(), liveStream.lastFrameAtNanos(), fullPixels));
				clearLiveStreamFrameInFlight(payload.streamId());
			}));
		} catch (Throwable throwable) {
			Lg2.LOGGER.warn("Renderer bot GPU live stream path failed before completion, falling back to CPU capture for {}", payload.streamId(), throwable);
			Screenshot.takeScreenshot(renderTarget, image -> CAPTURE_EXECUTOR.submit(() -> processSharedFrame(client, List.of(), List.of(liveStream), image)));
		}
	}

	private static void handleGpuCaptureFailure(
			Minecraft client,
			PendingCapture capture,
			CompletableFuture<NativeImage> sourceFuture,
			Throwable throwable
	) {
		if (sourceFuture.isDone() && !sourceFuture.isCompletedExceptionally() && !sourceFuture.isCancelled()) {
			CAPTURE_EXECUTOR.submit(() -> {
				try {
					processSharedFrame(client, List.of(capture), List.of(), sourceFuture.join());
				} catch (Throwable fallbackThrowable) {
					client.execute(() -> {
						sendFailure(capture.payload(), fallbackThrowable.getMessage() != null ? fallbackThrowable.getMessage() : fallbackThrowable.getClass().getSimpleName());
						clearPendingCapture(capture.payload().requestId());
					});
					Lg2.LOGGER.error("Renderer bot GPU capture fallback failed for {}", capture.payload().requestId(), fallbackThrowable);
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
			sendFailure(capture.payload(), throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
			clearPendingCapture(capture.payload().requestId());
		});
		Lg2.LOGGER.error("Renderer bot GPU capture failed for {}", capture.payload().requestId(), throwable);
	}

	private static void completeGpuCapture(
			Minecraft client,
			RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload,
			CompletableFuture<NativeImage> sourceFuture,
			CompletableFuture<byte[]> previewFuture,
			CompletableFuture<byte[]> fullFuture
	) {
		try (NativeImage sourceImage = sourceFuture.join()) {
			persistPhotoSource(payload, sourceImage);
			byte[] previewPixels = previewFuture.join();
			byte[] fullPixels = fullFuture.join();
			latestFrame = new CapturedFrame(
					payload.dimensionId(),
					payload.followEntityUuid(),
					payload.hiddenEntityUuid(),
					payload.expectedX(),
					payload.expectedY(),
					payload.expectedZ(),
					payload.expectedYaw(),
					payload.expectedPitch(),
					payload.previewWidth(),
					payload.previewHeight(),
					payload.fullWidth(),
					payload.fullHeight(),
					payload.fovDegrees(),
					previewPixels,
					fullPixels,
					System.currentTimeMillis()
			);
			client.execute(() -> {
				sendFrame(payload, previewPixels, fullPixels);
				clearPendingCapture(payload.requestId());
			});
		} catch (Throwable throwable) {
			client.execute(() -> {
				sendFailure(payload, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
				clearPendingCapture(payload.requestId());
			});
			Lg2.LOGGER.error("Renderer bot GPU capture completion failed for {}", payload.requestId(), throwable);
		}
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

	private static CompletableFuture<DepthCoverage> takeDepthCoverageFuture(RenderTarget renderTarget) {
		CompletableFuture<DepthCoverage> future = new CompletableFuture<>();
		if (renderTarget == null || renderTarget.getDepthTexture() == null || renderTarget.width <= 0 || renderTarget.height <= 0) {
			future.complete(DepthCoverage.unavailable());
			return future;
		}
		GpuTexture depthTexture = renderTarget.getDepthTexture();
		long byteSize = (long) renderTarget.width * renderTarget.height * Math.max(1, depthTexture.getFormat().pixelSize());
		if (byteSize <= 0L) {
			future.complete(DepthCoverage.unavailable());
			return future;
		}
		GpuBuffer buffer = null;
		try {
			buffer = RenderSystem.getDevice().createBuffer(
					() -> "lg2 map tile depth readback",
					GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
					byteSize
			);
			GpuBuffer readbackBuffer = buffer;
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.copyTextureToBuffer(depthTexture, readbackBuffer, 0L, () -> {
				try (GpuBuffer.MappedView mapped = encoder.mapBuffer(readbackBuffer, true, false)) {
					future.complete(analyzeDepthCoverage(mapped.data()));
				} catch (Throwable throwable) {
					// Some GPU backends can render a depth attachment but cannot copy it
					// to CPU memory.  Keep the map usable there; section-readiness still
					// protects the normal path, just without this extra confirmation.
					future.complete(DepthCoverage.unavailable());
				} finally {
					readbackBuffer.close();
				}
			}, 0);
		} catch (Throwable throwable) {
			if (buffer != null) {
				buffer.close();
			}
			future.complete(DepthCoverage.unavailable());
		}
		return future;
	}

	private static boolean hasWrittenDepth(ByteBuffer data) {
		return analyzeDepthCoverage(data).hasWorldDepth();
	}

	private static DepthCoverage analyzeDepthCoverage(ByteBuffer data) {
		if (data == null) {
			return DepthCoverage.unavailable();
		}
		int pixels = data.capacity() / Integer.BYTES;
		if (pixels <= 0) {
			return DepthCoverage.unavailable();
		}
		int worldPixels = 0;
		for (int index = 0; index < pixels; index++) {
			int depthBits = data.getInt(index * Integer.BYTES);
			// The renderer clears DEPTH32 to 1.0f.  Accept both byte orders because
			// different GPU backends expose their mapped readback buffer differently.
			if (depthBits != 0x3F800000 && depthBits != 0x0000803F) {
				worldPixels++;
			}
		}
		return new DepthCoverage(worldPixels > 0, true, worldPixels, pixels);
	}

	private static boolean isUniformRgbFrame(byte[] pixels) {
		if (pixels == null || pixels.length < 3) {
			return false;
		}
		byte red = pixels[0];
		byte green = pixels[1];
		byte blue = pixels[2];
		for (int offset = 3; offset + 2 < pixels.length; offset += 3) {
			if (pixels[offset] != red || pixels[offset + 1] != green || pixels[offset + 2] != blue) {
				return false;
			}
		}
		return true;
	}

	private static TextureTarget renderItemIconToTarget(Minecraft client, ItemStack stack, int requestedIconSize, int clearColor) {
		if (client == null || client.gameRenderer == null || stack == null || stack.isEmpty()) {
			return null;
		}
		int iconSize = Mth.clamp(requestedIconSize, 8, 128);
		TextureTarget renderTarget = ensureItemIconRenderTarget(iconSize);
		if (renderTarget == null) {
			return null;
		}
		Window window = client.getWindow();
		if (window == null) {
			return null;
		}
		GameRendererRenderLevelInvoker gameRendererAccessor = (GameRendererRenderLevelInvoker) client.gameRenderer;
		GuiRenderState guiRenderState = gameRendererAccessor.lg2$getGuiRenderState();
		GuiRenderer guiRenderer = gameRendererAccessor.lg2$getGuiRenderer();
		if (guiRenderState == null || guiRenderer == null) {
			return null;
		}
		RenderTarget previousRenderTarget = client.getMainRenderTarget();
		try {
			((MinecraftMainRenderTargetAccessor) client).lg2$setMainRenderTarget(renderTarget);
			RenderSystem.backupProjectionMatrix();
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.clearColorAndDepthTextures(renderTarget.getColorTexture(), clearColor, renderTarget.getDepthTexture(), 1.0D);
			client.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);
			guiRenderState.reset();

			int guiWidth = Math.max(1, window.getGuiScaledWidth());
			int guiHeight = Math.max(1, window.getGuiScaledHeight());
			float scaleX = guiWidth / 16.0F;
			float scaleY = guiHeight / 16.0F;
			GuiGraphics graphics = new GuiGraphics(client, guiRenderState, guiWidth, guiHeight);
			graphics.pose().pushMatrix();
			try {
				graphics.pose().scale(scaleX, scaleY);
				graphics.renderFakeItem(stack.copyWithCount(1), 0, 0, 0);
			} finally {
				graphics.pose().popMatrix();
			}
			guiRenderer.render(gameRendererAccessor.lg2$getFogRenderer().getBuffer(FogRenderer.FogMode.NONE));
			guiRenderer.incrementFrameNumber();
			return renderTarget;
		} finally {
			((MinecraftMainRenderTargetAccessor) client).lg2$setMainRenderTarget(previousRenderTarget);
			RenderSystem.restoreProjectionMatrix();
		}
	}

	private static TextureTarget ensureItemIconRenderTarget(int iconSize) {
		int safeIconSize = Mth.clamp(iconSize, 8, 128);
		if (itemIconRenderTarget == null
				|| itemIconRenderTarget.width != safeIconSize
				|| itemIconRenderTarget.height != safeIconSize) {
			if (itemIconRenderTarget != null) {
				itemIconRenderTarget.destroyBuffers();
			}
			itemIconRenderTarget = new TextureTarget("lg2_renderer_bot_item_icon", safeIconSize, safeIconSize, true);
		}
		return itemIconRenderTarget;
	}

	private static void processSharedFrame(Minecraft client, List<PendingCapture> captures, List<LiveStreamSession> liveStreams, NativeImage image) {
		try {
			int width = image.getWidth();
			int height = image.getHeight();
			int[] pixels = image.makePixelArray();

			for (PendingCapture capture : captures) {
				RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload = capture.payload();
				persistPhotoSource(payload, image);
				CompletableFuture<byte[]> previewFuture = CompletableFuture.supplyAsync(
						() -> quantizeFrame(pixels, width, height, payload.previewWidth(), payload.previewHeight()),
						CAPTURE_EXECUTOR
				);
				CompletableFuture<byte[]> fullFuture = payload.previewWidth() == payload.fullWidth() && payload.previewHeight() == payload.fullHeight()
						? previewFuture.thenApply(bytes -> bytes)
						: CompletableFuture.supplyAsync(
								() -> quantizeFrame(pixels, width, height, payload.fullWidth(), payload.fullHeight()),
								CAPTURE_EXECUTOR
						);
				byte[] previewPixels = previewFuture.join();
				byte[] fullPixels = fullFuture.join();
				latestFrame = new CapturedFrame(
						payload.dimensionId(),
						payload.followEntityUuid(),
						payload.hiddenEntityUuid(),
						payload.expectedX(),
						payload.expectedY(),
						payload.expectedZ(),
						payload.expectedYaw(),
						payload.expectedPitch(),
						payload.previewWidth(),
						payload.previewHeight(),
						payload.fullWidth(),
						payload.fullHeight(),
						payload.fovDegrees(),
						previewPixels,
						fullPixels,
						System.currentTimeMillis()
				);
				client.execute(() -> {
					sendFrame(payload, previewPixels, fullPixels);
					clearPendingCapture(payload.requestId());
				});
			}

			for (LiveStreamSession session : liveStreams) {
				RendererBotPayloads.RendererBotLiveStreamStartS2CPayload payload = session.payload();
				byte[] fullPixels = quantizeLiveFrame(pixels, width, height, payload.fullWidth(), payload.fullHeight());
				client.execute(() -> {
					ClientPlayNetworking.send(new RendererBotPayloads.RendererBotLiveFrameC2SPayload(payload.streamId(), session.lastFrameAtNanos(), fullPixels));
					clearLiveStreamFrameInFlight(payload.streamId());
				});
			}
		} catch (Throwable throwable) {
			client.execute(() -> {
				for (PendingCapture capture : captures) {
					RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload = capture.payload();
					sendFailure(payload, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
					clearPendingCapture(payload.requestId());
				}
				for (LiveStreamSession session : liveStreams) {
					RendererBotPayloads.RendererBotLiveStreamStartS2CPayload payload = session.payload();
					sendLiveFailure(payload, throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName());
					clearLiveStreamSession(payload.streamId());
				}
			});
			Lg2.LOGGER.error("Renderer bot shared capture failed", throwable);
		} finally {
			image.close();
		}
	}

	private static void persistPhotoSource(RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload, NativeImage image) throws Exception {
		if (payload == null || image == null) {
			return;
		}
		String sourceKey = payload.requestId().toString();
		CameraMediaCache.ensurePhotoParent(sourceKey);
		image.writeToFile(CameraMediaCache.photoSourcePath(sourceKey));
	}

	private static byte[] quantizeFrame(int[] sourcePixels, int sourceWidth, int sourceHeight, int outputWidth, int outputHeight) {
		if (sourceWidth == outputWidth && sourceHeight == outputHeight) {
			return quantizeExactFrame(sourcePixels, outputWidth, outputHeight);
		}
		return quantizeScaledFrame(sourcePixels, sourceWidth, sourceHeight, outputWidth, outputHeight);
	}

	private static byte[] quantizeLiveFrame(int[] sourcePixels, int sourceWidth, int sourceHeight, int outputWidth, int outputHeight) {
		boolean dither = LIVE_STREAM_DITHERING;
		if (sourceWidth == outputWidth && sourceHeight == outputHeight) {
			return quantizeExactFrame(sourcePixels, outputWidth, outputHeight, dither, true);
		}
		return quantizeScaledFrame(sourcePixels, sourceWidth, sourceHeight, outputWidth, outputHeight, dither, true);
	}

	private static byte[] quantizeExactFrame(int[] sourcePixels, int width, int height) {
		return quantizeExactFrame(sourcePixels, width, height, true, false);
	}

	private static byte[] quantizeExactFrame(int[] sourcePixels, int width, int height, boolean dither, boolean allowParallel) {
		byte[] output = new byte[Math.max(1, width * height)];
		if (allowParallel && width * height >= LIVE_STREAM_PARALLEL_PIXELS_THRESHOLD) {
			parallelQuantizeRows(sourcePixels, width, height, output, dither);
			return output;
		}
		for (int y = 0; y < height; y++) {
			int rowStart = y * width;
			for (int x = 0; x < width; x++) {
				int rgb = sourcePixels[rowStart + x] & 0xFFFFFF;
				output[rowStart + x] = dither
						? MapPaletteQuantizer.quantizeDithered(rgb, x, y)
						: MapPaletteQuantizer.quantize(rgb);
			}
		}
		return output;
	}

	private static byte[] quantizeScaledFrame(int[] sourcePixels, int sourceWidth, int sourceHeight, int outputWidth, int outputHeight) {
		return quantizeScaledFrame(sourcePixels, sourceWidth, sourceHeight, outputWidth, outputHeight, true, false);
	}

	private static byte[] encodeExactRgbFrame(int[] sourcePixels, int width, int height) {
		byte[] output = new byte[Math.max(1, width * height * 3)];
		for (int i = 0; i < width * height; i++) {
			writeRgb(output, i, sourcePixels[i] & 0xFFFFFF);
		}
		return output;
	}

	private static byte[] encodeMattedArgbFrame(int[] blackPixels, int[] whitePixels, int width, int height) {
		byte[] output = new byte[Math.max(1, width * height * 4)];
		for (int i = 0; i < width * height; i++) {
			int black = blackPixels[i];
			int white = whitePixels[i];
			int blackRed = (black >>> 16) & 0xFF;
			int blackGreen = (black >>> 8) & 0xFF;
			int blackBlue = black & 0xFF;
			int whiteRed = (white >>> 16) & 0xFF;
			int whiteGreen = (white >>> 8) & 0xFF;
			int whiteBlue = white & 0xFF;
			int delta = clampInt(((whiteRed - blackRed) + (whiteGreen - blackGreen) + (whiteBlue - blackBlue)) / 3, 0, 255);
			int alpha = 255 - delta;
			int red = 0;
			int green = 0;
			int blue = 0;
			if (alpha > ITEM_ICON_ALPHA_THRESHOLD) {
				red = Mth.clamp((blackRed * 255 + alpha / 2) / alpha, 0, 255);
				green = Mth.clamp((blackGreen * 255 + alpha / 2) / alpha, 0, 255);
				blue = Mth.clamp((blackBlue * 255 + alpha / 2) / alpha, 0, 255);
			} else {
				alpha = 0;
			}
			int offset = i * 4;
			output[offset] = (byte) alpha;
			output[offset + 1] = (byte) red;
			output[offset + 2] = (byte) green;
			output[offset + 3] = (byte) blue;
		}
		return output;
	}

	private static int clampInt(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static byte[] encodeNearestRgbFrame(int[] sourcePixels, int sourceWidth, int sourceHeight, int outputWidth, int outputHeight) {
		byte[] output = new byte[Math.max(1, outputWidth * outputHeight * 3)];
		for (int y = 0; y < outputHeight; y++) {
			int sourceY = Mth.clamp((int) Math.floor(((y + 0.5D) * sourceHeight) / Math.max(1.0D, outputHeight)), 0, sourceHeight - 1);
			for (int x = 0; x < outputWidth; x++) {
				int sourceX = Mth.clamp((int) Math.floor(((x + 0.5D) * sourceWidth) / Math.max(1.0D, outputWidth)), 0, sourceWidth - 1);
				writeRgb(output, y * outputWidth + x, sourcePixels[sourceY * sourceWidth + sourceX] & 0xFFFFFF);
			}
		}
		return output;
	}

	private static void writeRgb(byte[] output, int pixelIndex, int rgb) {
		int offset = pixelIndex * 3;
		output[offset] = (byte) ((rgb >> 16) & 0xFF);
		output[offset + 1] = (byte) ((rgb >> 8) & 0xFF);
		output[offset + 2] = (byte) (rgb & 0xFF);
	}

	private static byte[] quantizeScaledFrame(int[] sourcePixels, int sourceWidth, int sourceHeight, int outputWidth, int outputHeight, boolean dither, boolean allowParallel) {
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
		if (allowParallel && outputWidth * outputHeight >= LIVE_STREAM_PARALLEL_PIXELS_THRESHOLD) {
			parallelScaleQuantizeRows(sourcePixels, sourceWidth, sourceHeight, outputWidth, outputHeight, cropX, cropY, cropWidth, cropHeight, output, dither);
			return output;
		}
		double sampleSpanX = cropWidth / Math.max(1.0D, outputWidth);
		double sampleSpanY = cropHeight / Math.max(1.0D, outputHeight);
		for (int y = 0; y < outputHeight; y++) {
			double sampleY = cropY + ((y + 0.5D) / outputHeight) * cropHeight;
			for (int x = 0; x < outputWidth; x++) {
				double sampleX = cropX + ((x + 0.5D) / outputWidth) * cropWidth;
				int rgb = sampleScaledRgb(sourcePixels, sourceWidth, sourceHeight, sampleX, sampleY, sampleSpanX, sampleSpanY);
				output[y * outputWidth + x] = dither
						? MapPaletteQuantizer.quantizeDithered(rgb, x, y)
						: MapPaletteQuantizer.quantize(rgb);
			}
		}
		return output;
	}

	private static void parallelQuantizeRows(int[] sourcePixels, int width, int height, byte[] output, boolean dither) {
		int workerCount = Math.max(1, Math.min(Math.max(1, CAPTURE_THREADS - 1), height));
		if (workerCount <= 1) {
			quantizeRows(sourcePixels, width, output, 0, height, dither);
			return;
		}
		CompletableFuture<?>[] tasks = new CompletableFuture<?>[workerCount];
		for (int worker = 0; worker < workerCount; worker++) {
			int startRow = (height * worker) / workerCount;
			int endRow = (height * (worker + 1)) / workerCount;
			tasks[worker] = CompletableFuture.runAsync(
					() -> quantizeRows(sourcePixels, width, output, startRow, endRow, dither),
					CAPTURE_EXECUTOR
			);
		}
		CompletableFuture.allOf(tasks).join();
	}

	private static void quantizeRows(int[] sourcePixels, int width, byte[] output, int startRow, int endRow, boolean dither) {
		for (int y = startRow; y < endRow; y++) {
			int rowStart = y * width;
			for (int x = 0; x < width; x++) {
				int rgb = sourcePixels[rowStart + x] & 0xFFFFFF;
				output[rowStart + x] = dither
						? MapPaletteQuantizer.quantizeDithered(rgb, x, y)
						: MapPaletteQuantizer.quantize(rgb);
			}
		}
	}

	private static void parallelScaleQuantizeRows(
			int[] sourcePixels,
			int sourceWidth,
			int sourceHeight,
			int outputWidth,
			int outputHeight,
			double cropX,
			double cropY,
			double cropWidth,
			double cropHeight,
			byte[] output,
			boolean dither
	) {
		int workerCount = Math.max(1, Math.min(Math.max(1, CAPTURE_THREADS - 1), outputHeight));
		if (workerCount <= 1) {
			scaleQuantizeRows(sourcePixels, sourceWidth, sourceHeight, outputWidth, outputHeight, cropX, cropY, cropWidth, cropHeight, output, 0, outputHeight, dither);
			return;
		}
		CompletableFuture<?>[] tasks = new CompletableFuture<?>[workerCount];
		for (int worker = 0; worker < workerCount; worker++) {
			int startRow = (outputHeight * worker) / workerCount;
			int endRow = (outputHeight * (worker + 1)) / workerCount;
			tasks[worker] = CompletableFuture.runAsync(
					() -> scaleQuantizeRows(sourcePixels, sourceWidth, sourceHeight, outputWidth, outputHeight, cropX, cropY, cropWidth, cropHeight, output, startRow, endRow, dither),
					CAPTURE_EXECUTOR
			);
		}
		CompletableFuture.allOf(tasks).join();
	}

	private static void scaleQuantizeRows(
			int[] sourcePixels,
			int sourceWidth,
			int sourceHeight,
			int outputWidth,
			int outputHeight,
			double cropX,
			double cropY,
			double cropWidth,
			double cropHeight,
			byte[] output,
			int startRow,
			int endRow,
			boolean dither
	) {
		double sampleSpanX = cropWidth / Math.max(1.0D, outputWidth);
		double sampleSpanY = cropHeight / Math.max(1.0D, outputHeight);
		for (int y = startRow; y < endRow; y++) {
			double sampleY = cropY + ((y + 0.5D) / outputHeight) * cropHeight;
			for (int x = 0; x < outputWidth; x++) {
				double sampleX = cropX + ((x + 0.5D) / outputWidth) * cropWidth;
				int rgb = sampleScaledRgb(sourcePixels, sourceWidth, sourceHeight, sampleX, sampleY, sampleSpanX, sampleSpanY);
				output[y * outputWidth + x] = dither
						? MapPaletteQuantizer.quantizeDithered(rgb, x, y)
						: MapPaletteQuantizer.quantize(rgb);
			}
		}
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
		double clampedX = Mth.clamp(sampleX, 0.0D, Math.max(0, sourceWidth - 1));
		double clampedY = Mth.clamp(sampleY, 0.0D, Math.max(0, sourceHeight - 1));
		int x0 = Mth.clamp((int) Math.floor(clampedX), 0, sourceWidth - 1);
		int y0 = Mth.clamp((int) Math.floor(clampedY), 0, sourceHeight - 1);
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
		return Mth.clamp((int) Math.round(top + (bottom - top) * ty), 0, 255);
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

	private static void sendFrame(RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload, byte[] previewPixels, byte[] fullPixels) {
		ClientPlayNetworking.send(new RendererBotPayloads.RendererBotPreviewFrameC2SPayload(payload.requestId(), previewPixels));
		ClientPlayNetworking.send(new RendererBotPayloads.RendererBotFullFrameC2SPayload(payload.requestId(), fullPixels));
	}

	private static void sendLiveFailure(RendererBotPayloads.RendererBotLiveStreamStartS2CPayload payload, String message) {
		Lg2.LOGGER.warn("Renderer bot failing live stream {}: {}", payload.streamId(), message);
		ClientPlayNetworking.send(new RendererBotPayloads.RendererBotLiveStreamFailureC2SPayload(
				payload.streamId(),
				message == null || message.isBlank() ? "Renderer bot live stream failed" : message
		));
	}

	private static void clearPendingCapture(java.util.UUID requestId) {
		synchronized (LOCK) {
			if (requestId == null) {
				PENDING_CAPTURES.clear();
				return;
			}
			PENDING_CAPTURES.remove(requestId);
		}
	}

	private static boolean markPendingCaptureRequested(UUID requestId) {
		synchronized (LOCK) {
			PendingCapture capture = PENDING_CAPTURES.get(requestId);
			if (capture == null || capture.screenshotRequested()) {
				return false;
			}
			capture.markScreenshotRequested();
			return true;
		}
	}

	private static void clearPendingCaptureRequested(UUID requestId) {
		synchronized (LOCK) {
			PendingCapture capture = PENDING_CAPTURES.get(requestId);
			if (capture != null) {
				capture.clearScreenshotRequested();
			}
		}
	}

	private static void clearLiveStreamSession(UUID streamId) {
		synchronized (LOCK) {
			if (streamId == null) {
				LIVE_STREAM_SESSIONS.clear();
				return;
			}
			LIVE_STREAM_SESSIONS.remove(streamId);
		}
	}

	private static void clearLiveStreamFrameInFlight(UUID streamId) {
		synchronized (LOCK) {
			LiveStreamSession session = LIVE_STREAM_SESSIONS.get(streamId);
			if (session == null) {
				return;
			}
			session.clearFrameInFlight();
		}
	}

	private static void clearPendingMapTile(UUID requestId) {
		synchronized (LOCK) {
			if (requestId == null) {
				PENDING_MAP_TILES.clear();
				return;
			}
			PENDING_MAP_TILES.remove(requestId);
		}
	}

	private static void clearPendingItemIcon(UUID requestId) {
		synchronized (LOCK) {
			if (requestId == null) {
				PENDING_ITEM_ICONS.clear();
				return;
			}
			PENDING_ITEM_ICONS.remove(requestId);
		}
	}

	private static void clearPendingMapTileRendering(UUID requestId) {
		synchronized (LOCK) {
			PendingMapTile pending = PENDING_MAP_TILES.get(requestId);
			if (pending != null) {
				pending.clearRendering();
			}
		}
	}

	private static boolean markLiveStreamFrameInFlight(UUID streamId, long nowNanos) {
		synchronized (LOCK) {
			LiveStreamSession session = LIVE_STREAM_SESSIONS.get(streamId);
			if (session == null || !session.canScheduleFrame()) {
				return false;
			}
			session.markFrameInFlight(nowNanos);
			return true;
		}
	}

	private static void clearAllSessions() {
		synchronized (LOCK) {
			PENDING_CAPTURES.clear();
			LIVE_STREAM_SESSIONS.clear();
			PENDING_MAP_TILES.clear();
			PENDING_ITEM_ICONS.clear();
			latestFrame = null;
		}
		if (itemIconRenderTarget != null) {
			itemIconRenderTarget.destroyBuffers();
			itemIconRenderTarget = null;
		}
		RendererBotOffscreenWorldRenderer.clearCaches();
	}

	private static void sendFailure(RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload, String message) {
		Lg2.LOGGER.warn("Renderer bot failing capture {}: {}", payload.requestId(), message);
		ClientPlayNetworking.send(new RendererBotPayloads.RendererBotCaptureFailureC2SPayload(
				payload.requestId(),
				message == null || message.isBlank() ? "Renderer bot capture failed" : message
		));
	}

	private static void sendMapTileFailure(RendererBotPayloads.RendererBotMapTileRequestS2CPayload payload, String message) {
		Lg2.LOGGER.warn("Renderer bot failing map tile {} lod {} {},{}: {}", payload.requestId(), payload.lod(), payload.tileX(), payload.tileZ(), message);
		ClientPlayNetworking.send(new RendererBotPayloads.RendererBotMapTileFailureC2SPayload(
				payload.requestId(),
				message == null || message.isBlank() ? "Renderer bot map tile failed" : message
		));
	}

	private static void sendItemIconFailure(RendererBotPayloads.RendererBotItemIconRequestS2CPayload payload, String message) {
		Lg2.LOGGER.warn("Renderer bot failing item icon {}: {}", payload.requestId(), message);
		ClientPlayNetworking.send(new RendererBotPayloads.RendererBotItemIconFailureC2SPayload(
				payload.requestId(),
				message == null || message.isBlank() ? "Renderer bot item icon failed" : message
		));
	}

	private static int computeRenderWidth(RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload) {
		int fullWidth = Math.max(1, payload.fullWidth());
		int fullHeight = Math.max(1, payload.fullHeight());
		double minScale = Math.max(MIN_RENDER_WIDTH / (double) fullWidth, MIN_RENDER_HEIGHT / (double) fullHeight);
		double maxScale = Math.min(MAX_RENDER_WIDTH / (double) fullWidth, MAX_RENDER_HEIGHT / (double) fullHeight);
		double scale = Math.max(1.0D, Math.min(maxScale, Math.max(minScale, TARGET_RENDER_SCALE)));
		return Mth.clamp((int) Math.round(fullWidth * scale), 1, MAX_RENDER_WIDTH);
	}

	private static int computeRenderHeight(RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload, int renderWidth) {
		double aspect = Math.max(1.0D, payload.fullHeight()) / (double) Math.max(1.0D, payload.fullWidth());
		return Mth.clamp((int) Math.round(renderWidth * aspect), 1, MAX_RENDER_HEIGHT);
	}

	private static int computeLiveRenderWidth(RendererBotPayloads.RendererBotLiveStreamStartS2CPayload payload) {
		return Mth.clamp(Math.max(1, payload.fullWidth()), 1, MAX_RENDER_WIDTH);
	}

	private static int computeLiveRenderHeight(RendererBotPayloads.RendererBotLiveStreamStartS2CPayload payload, int renderWidth) {
		double aspect = Math.max(1.0D, payload.fullHeight()) / (double) Math.max(1.0D, payload.fullWidth());
		return Mth.clamp((int) Math.round(renderWidth * aspect), 1, MAX_RENDER_HEIGHT);
	}

	private static RendererBotOffscreenWorldRenderer.RenderRequest captureRenderRequest(RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload) {
		int renderWidth = computeRenderWidth(payload);
		int renderHeight = computeRenderHeight(payload, renderWidth);
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
				renderWidth,
				renderHeight,
				false,
				false,
				0.0D,
				0.0D,
				0.0F,
				false
		);
	}

	private static RendererBotOffscreenWorldRenderer.RenderRequest liveRenderRequest(LiveStreamSession session) {
		RendererBotPayloads.RendererBotLiveStreamStartS2CPayload payload = session.payload();
		LiveStreamPose pose = session.pose();
		int renderWidth = computeLiveRenderWidth(payload);
		int renderHeight = computeLiveRenderHeight(payload, renderWidth);
		return new RendererBotOffscreenWorldRenderer.RenderRequest(
				payload.renderSessionId(),
				payload.dimensionId(),
				pose == null ? payload.followEntityUuid() : null,
				pose == null ? payload.expectedX() : pose.x(),
				pose == null ? payload.expectedY() : pose.y(),
				pose == null ? payload.expectedZ() : pose.z(),
				pose == null ? payload.expectedYaw() : pose.yaw(),
				pose == null ? payload.expectedPitch() : pose.pitch(),
				payload.fovDegrees(),
				renderWidth,
				renderHeight,
				pose != null,
				false,
				0.0D,
				0.0D,
				pose == null ? 0.0F : pose.cameraBankRadians(),
				payload.hideCameraCollisionBlock()
		);
	}

	private static RendererBotTopDownMapRenderer.TileRequest mapTileRenderRequest(RendererBotPayloads.RendererBotMapTileRequestS2CPayload payload) {
		return new RendererBotTopDownMapRenderer.TileRequest(
				payload.renderSessionId(),
				payload.dimensionId(),
				payload.centerX(),
				payload.centerZ(),
				Math.max(1, payload.tileSize()),
				Math.max(1, payload.tileSize()),
				Math.max(1.0D / 16.0D, payload.blocksPerPixel())
		);
	}

	private static final class PendingMapTile {
		private final RendererBotPayloads.RendererBotMapTileRequestS2CPayload payload;
		private final long requestStartedAt;
		private final long sequence;
		private int remainingWarmupFrames;
		private boolean rendering;
		private boolean missingTerrainRecoveryUsed;

		private PendingMapTile(RendererBotPayloads.RendererBotMapTileRequestS2CPayload payload, long requestStartedAt, long sequence) {
			this.payload = payload;
			this.requestStartedAt = requestStartedAt;
			this.sequence = sequence;
			this.remainingWarmupFrames = MAP_TILE_WARMUP_FRAMES;
			this.rendering = false;
			this.missingTerrainRecoveryUsed = false;
		}

		private RendererBotPayloads.RendererBotMapTileRequestS2CPayload payload() {
			return this.payload;
		}

		private long requestStartedAt() {
			return this.requestStartedAt;
		}

		private long sequence() {
			return this.sequence;
		}

		private boolean rendering() {
			return this.rendering;
		}

		private int remainingWarmupFrames() {
			return this.remainingWarmupFrames;
		}

		private void decrementWarmupFrames() {
			this.remainingWarmupFrames = Math.max(0, this.remainingWarmupFrames - 1);
		}

		private boolean restartAfterMissingTerrain() {
			if (this.missingTerrainRecoveryUsed) {
				return false;
			}
			this.missingTerrainRecoveryUsed = true;
			this.remainingWarmupFrames = Math.max(4, MAP_TILE_WARMUP_FRAMES);
			return true;
		}

		private void markRendering() {
			this.rendering = true;
		}

		private void clearRendering() {
			this.rendering = false;
		}
	}

	private record MapTileFrame(byte[] pixels, DepthCoverage depthCoverage) {
	}

	private record DepthCoverage(boolean hasWorldDepth, boolean readable, int worldPixels, int totalPixels) {
		private static DepthCoverage unavailable() {
			return new DepthCoverage(false, false, 0, 0);
		}

		private double worldDepthCoverage() {
			return this.totalPixels <= 0 ? 0.0D : (double) this.worldPixels / (double) this.totalPixels;
		}
	}

	private static final class PendingItemIcon {
		private final RendererBotPayloads.RendererBotItemIconRequestS2CPayload payload;
		private final long requestStartedAt;
		private boolean rendering;

		private PendingItemIcon(RendererBotPayloads.RendererBotItemIconRequestS2CPayload payload, long requestStartedAt) {
			this.payload = payload;
			this.requestStartedAt = requestStartedAt;
			this.rendering = false;
		}

		private RendererBotPayloads.RendererBotItemIconRequestS2CPayload payload() {
			return this.payload;
		}

		private long requestStartedAt() {
			return this.requestStartedAt;
		}

		private boolean rendering() {
			return this.rendering;
		}

		private void markRendering() {
			this.rendering = true;
		}
	}

	private static final class PendingCapture {
		private final RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload;
		private final long requestStartedAt;
		private boolean screenshotRequested;
		private boolean finalCaptureReady;
		private int probeCount;
		private int stableProbeCount;
		private long stableContentRevision = Long.MIN_VALUE;

		private PendingCapture(RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload, long requestStartedAt) {
			this.payload = payload;
			this.requestStartedAt = requestStartedAt;
			this.screenshotRequested = false;
			this.finalCaptureReady = false;
		}

		private RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload() {
			return this.payload;
		}

		private long requestStartedAt() {
			return this.requestStartedAt;
		}

		private boolean screenshotRequested() {
			return this.screenshotRequested;
		}

		private void markScreenshotRequested() {
			this.screenshotRequested = true;
		}

		private void clearScreenshotRequested() {
			this.screenshotRequested = false;
		}

		private boolean finalCaptureReady() {
			return this.finalCaptureReady;
		}

		private int probeCount() {
			return this.probeCount;
		}

		private boolean observeReadinessProbe(RendererBotShadowWorldManager.RenderReadiness readiness) {
			this.probeCount++;
			if (readiness == null || !readiness.settled()) {
				this.stableProbeCount = 0;
				this.stableContentRevision = Long.MIN_VALUE;
				return false;
			}
			if (this.stableContentRevision != readiness.contentRevision()) {
				this.stableContentRevision = readiness.contentRevision();
				this.stableProbeCount = 1;
				return becomeReadyIfStable();
			}
			this.stableProbeCount++;
			return becomeReadyIfStable();
		}

		private boolean becomeReadyIfStable() {
			if (this.stableProbeCount < CAPTURE_REQUIRED_SETTLED_RENDERS) {
				return false;
			}
			this.finalCaptureReady = true;
			return true;
		}
	}

	private static final class LiveStreamSession {
		private final RendererBotPayloads.RendererBotLiveStreamStartS2CPayload payload;
		private final long startedAtMillis;
		private int remainingWarmupFrames;
		private long lastFrameAtNanos;
		private int framesInFlight;
		private LiveStreamPose pose;
		private LiveStreamPose previousPose;
		private long poseUpdatedAtNanos;

		private LiveStreamSession(
				RendererBotPayloads.RendererBotLiveStreamStartS2CPayload payload,
				long startedAtMillis,
				int remainingWarmupFrames
		) {
			this.payload = payload;
			this.startedAtMillis = startedAtMillis;
			this.remainingWarmupFrames = remainingWarmupFrames;
			this.lastFrameAtNanos = 0L;
			this.framesInFlight = 0;
		}

		private RendererBotPayloads.RendererBotLiveStreamStartS2CPayload payload() {
			return this.payload;
		}

		private long startedAtMillis() {
			return this.startedAtMillis;
		}

		private int remainingWarmupFrames() {
			return this.remainingWarmupFrames;
		}

		private void decrementWarmupFrames() {
			if (this.remainingWarmupFrames > 0) {
				this.remainingWarmupFrames--;
			}
		}

		private long lastFrameAtNanos() {
			return this.lastFrameAtNanos;
		}

		private boolean canScheduleFrame() {
			return this.framesInFlight < MAX_LIVE_STREAM_FRAMES_IN_FLIGHT;
		}

		private LiveStreamPose pose() {
			if (this.pose == null || this.previousPose == null) {
				return this.pose;
			}
			float progress = Mth.clamp(
					(float) (System.nanoTime() - this.poseUpdatedAtNanos) / LIVE_POSE_INTERPOLATION_NANOS,
					0.0F,
					1.0F
			);
			return interpolatePose(this.previousPose, this.pose, progress);
		}

		private void updatePose(LiveStreamPose pose, long updatedAtNanos) {
			if (this.pose != null) {
				this.previousPose = this.pose();
			}
			this.pose = pose;
			this.poseUpdatedAtNanos = updatedAtNanos;
		}

		private void markFrameInFlight(long nowNanos) {
			this.lastFrameAtNanos = nowNanos;
			this.framesInFlight++;
		}

		private void clearFrameInFlight() {
			this.framesInFlight = Math.max(0, this.framesInFlight - 1);
		}
	}

	private static LiveStreamPose interpolatePose(LiveStreamPose from, LiveStreamPose to, float progress) {
		return new LiveStreamPose(
				Mth.lerp(progress, from.x(), to.x()),
				Mth.lerp(progress, from.y(), to.y()),
				Mth.lerp(progress, from.z(), to.z()),
				from.yaw() + Mth.wrapDegrees(to.yaw() - from.yaw()) * progress,
				Mth.lerp(progress, from.pitch(), to.pitch()),
				Mth.lerp(progress, from.cameraBankRadians(), to.cameraBankRadians())
		);
	}

	private record LiveStreamPose(double x, double y, double z, float yaw, float pitch, float cameraBankRadians) {
	}

	private record CapturedFrame(
			String dimensionId,
			UUID followEntityUuid,
			UUID hiddenEntityUuid,
			double expectedX,
			double expectedY,
			double expectedZ,
			float expectedYaw,
			float expectedPitch,
			int previewWidth,
			int previewHeight,
			int fullWidth,
			int fullHeight,
			int fovDegrees,
			byte[] previewPixels,
			byte[] fullPixels,
			long capturedAtMillis
	) {
		private boolean matches(RendererBotPayloads.RendererBotCaptureRequestS2CPayload payload) {
			return Objects.equals(this.dimensionId, payload.dimensionId())
					&& Objects.equals(this.followEntityUuid, payload.followEntityUuid())
					&& Objects.equals(this.hiddenEntityUuid, payload.hiddenEntityUuid())
					&& Math.abs(this.expectedX - payload.expectedX()) <= 0.01D
					&& Math.abs(this.expectedY - payload.expectedY()) <= 0.01D
					&& Math.abs(this.expectedZ - payload.expectedZ()) <= 0.01D
					&& Math.abs(this.expectedYaw - payload.expectedYaw()) <= 0.1F
					&& Math.abs(this.expectedPitch - payload.expectedPitch()) <= 0.1F
					&& this.previewWidth == payload.previewWidth()
					&& this.previewHeight == payload.previewHeight()
					&& this.fullWidth == payload.fullWidth()
					&& this.fullHeight == payload.fullHeight()
					&& this.fovDegrees == payload.fovDegrees();
		}
	}
}
