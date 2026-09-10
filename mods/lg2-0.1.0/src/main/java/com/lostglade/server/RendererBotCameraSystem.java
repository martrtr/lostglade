package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.block.CameraBlock;
import com.lostglade.block.ModBlocks;
import com.lostglade.config.Lg2Config;
import com.lostglade.item.ModItems;
import com.lostglade.mixin.SynchedEntityDataAccessor;
import com.lostglade.network.RendererBotPayloads;
import com.lostglade.network.RendererBotShadowPacketCodec;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import xyz.nucleoid.packettweaker.PacketContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class RendererBotCameraSystem {
	private static final Set<Relative> ABSOLUTE_TELEPORT = EnumSet.noneOf(Relative.class);
	private static final int MAX_VIDEO_RECORDING_FPS = 20;
	private static final int MAX_LIVE_STREAM_FPS = 20;
	private static final int HOTBAR_SIZE = 9;
	private static final int STATIC_CAMERA_SESSION_CLUSTER_CHUNKS = 2;
	private static final int CAMERA_HOTBAR_WARMUP_FPS = 2;
	private static final int CAMERA_HOTBAR_WARMUP_SIZE = 1;
	private static final int CAMERA_HOTBAR_WARMUP_FOV_DEGREES = 70;
	private static final int AUDIO_SAMPLE_RATE = 48_000;
	private static final int AUDIO_FRAME_SAMPLES = 960;
	private static final int CAMERA_CHUNK_TICKET_UNIQUE_FLAG = 128;
	private static final int SHADOW_REAR_VIEW_CHUNKS = 2;
	private static final float MAP_TILE_TOP_DOWN_YAW = 180.0F;
	private static final float MAP_TILE_TOP_DOWN_PITCH = 90.0F;
	// Top-down map tiles are cached world snapshots, not live camera frames.
	// Keeping their sky at noon makes a cached tile deterministic and avoids a
	// shadow-level state packet every server tick just because the sun moved.
	private static final long MAP_TILE_RENDER_DAY_TIME = 6_000L;
	private static final long LIVE_STREAM_STALE_MS = 1_500L;
	private static final long MAP_TILE_CAPTURE_TIMEOUT_MS = 60_000L;
	// Only one map job can own the shared shadow world, so a valid queued job may
	// wait behind earlier tiles. It is bound to the renderer bot selected at
	// admission; a selection change fails it immediately and releases its retry.
	private static final long MAP_TILE_SOURCE_READ_TIMEOUT_MS = 45_000L;
	private static final long MAP_TILE_SHADOW_RESYNC_INTERVAL_MS = 5_000L;
	private static final int MAX_PENDING_MAP_TILE_CAPTURES = Math.max(1, Integer.getInteger("lg2.rendererBotMaxPendingMapTiles", 24));
	// All map tile captures share one client shadow-world session.  Feeding it
	// distant targets concurrently makes vanilla reject their chunk packets as
	// out-of-range, so exactly one target owns that session at a time.
	private static final int MAX_ACTIVE_MAP_TILE_SHADOW_TARGETS = 1;
	private static final long ITEM_ICON_CAPTURE_TIMEOUT_MS = 30_000L;
	private static final long AUDIO_CAPTURE_STALE_MS = 8_000L;
	private static final long LIVE_STREAM_ORPHAN_CLEANUP_MS = 15_000L;
	private static final long AUDIO_CAPTURE_ORPHAN_CLEANUP_MS = 15_000L;
	private static final long PHOTO_CAPTURE_RETRY_INTERVAL_MS = 50L;
	private static final double SHADOW_FORWARD_HALF_FOV_DEGREES = 80.0D;
	private static final double SHADOW_NEAR_OMNI_RADIUS_CHUNKS = 3.0D;
	private static final double SHADOW_SIDE_SAFETY_MARGIN_CHUNKS = 1.5D;
	// Static screen cameras keep a wider local buffer so large angle changes stay smooth without full-circle loading.
	private static final double STATIC_CAMERA_FORWARD_HALF_FOV_DEGREES = 90.0D;
	private static final double STATIC_CAMERA_NEAR_OMNI_RADIUS_CHUNKS = 6.0D;
	private static final int STATIC_CAMERA_REAR_VIEW_CHUNKS = 4;
	private static final double STATIC_CAMERA_SIDE_SAFETY_MARGIN_CHUNKS = 2.0D;
	private static final double SHARED_RENDER_RADIUS_BLOCKS = 96.0D;
	private static final double SHARED_RENDER_RADIUS_SQ = SHARED_RENDER_RADIUS_BLOCKS * SHARED_RENDER_RADIUS_BLOCKS;
	// Network handlers must not wait behind the server tick just to hand a frame
	// to a screen.  Each stream itself coalesces to its newest frame, so this
	// bounded worker pool improves latency without letting slow displays build an
	// unbounded queue.
	private static final ExecutorService LIVE_FRAME_DISPATCH_EXECUTOR = Executors.newFixedThreadPool(
			Math.max(2, Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2))),
			runnable -> {
				Thread thread = new Thread(runnable, "lg2-renderer-bot-live-frame");
				thread.setDaemon(true);
				return thread;
			}
	);
	private static final TicketType CAMERA_CHUNK_TICKET_TYPE = new TicketType(
			0L,
			TicketType.FLAG_LOADING | CAMERA_CHUNK_TICKET_UNIQUE_FLAG
	);
	private static final Map<UUID, BotHandshake> READY_BOTS = new ConcurrentHashMap<>();
	private static final Map<UUID, PendingCapture> PENDING_CAPTURES = new ConcurrentHashMap<>();
	private static final Map<UUID, PendingVideoRecording> PENDING_VIDEO_RECORDINGS = new ConcurrentHashMap<>();
	private static final Map<UUID, RemoteVideoUpload> REMOTE_VIDEO_UPLOADS = new ConcurrentHashMap<>();
	private static final long MAX_REMOTE_VIDEO_UPLOAD_BYTES = 96L * 1024L * 1024L;
	private static final Map<UUID, ActiveLiveStream> ACTIVE_LIVE_STREAMS = new ConcurrentHashMap<>();
	private static final Map<String, UUID> LIVE_STREAMS_BY_OWNER = new ConcurrentHashMap<>();
	private static final Object MAP_TILE_QUEUE_LOCK = new Object();
	private static final Map<UUID, PendingMapTileCapture> PENDING_MAP_TILE_CAPTURES = new ConcurrentHashMap<>();
	private static final AtomicLong NEXT_MAP_TILE_SEQUENCE = new AtomicLong();
	private static final Map<UUID, PendingItemIconCapture> PENDING_ITEM_ICON_CAPTURES = new ConcurrentHashMap<>();
	private static final Map<UUID, ActiveAudioCapture> ACTIVE_AUDIO_CAPTURES = new ConcurrentHashMap<>();
	private static final Map<String, UUID> AUDIO_CAPTURES_BY_OWNER = new ConcurrentHashMap<>();
	private static final Map<CameraChunkTicketKey, Integer> ACTIVE_CAMERA_CHUNK_TICKETS = new HashMap<>();
	private static final Map<ShadowSyncKey, ShadowDimensionSyncState> ACTIVE_SHADOW_SYNC_STATES = new HashMap<>();
	private static final Set<ChunkTicketKey> DIRTY_SHADOW_CHUNKS = ConcurrentHashMap.newKeySet();

	private RendererBotCameraSystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(RendererBotCameraSystem::tickVirtualCameraState);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID botUuid = handler.player.getUUID();
			clearShadowSyncState(server, botUuid, false);
			READY_BOTS.remove(botUuid);
			failCapturesForBot(botUuid, "Renderer bot disconnected during capture");
			failMapTileCapturesForBot(botUuid, "Renderer bot disconnected during map tile render");
			failItemIconCapturesForBot(botUuid, "Renderer bot disconnected during item icon render");
			failVideoRecordingsForBot(botUuid, "Renderer bot disconnected during video recording");
			failLiveStreamsForBot(botUuid, "Renderer bot disconnected during live stream");
			failAudioCapturesForBot(botUuid, "Renderer bot disconnected during audio capture");
		});
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotHelloC2SPayload.TYPE,
				(payload, context) -> {
					if (payload.protocolVersion() != RendererBotPayloads.PROTOCOL_VERSION) {
						READY_BOTS.remove(context.player().getUUID());
						return;
					}
					boolean dedicatedBot = RendererBotPresenceSystem.isRendererBot(context.player());
					boolean volunteer = payload.volunteerRenderer() && !dedicatedBot
							&& Lg2Config.get().cameraRendererAllowPlayerVolunteers;
					READY_BOTS.put(context.player().getUUID(), new BotHandshake(
							context.player().getUUID(), context.player().getScoreboardName(), volunteer
					));
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotPreviewFrameC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server == null) {
						return;
					}
					server.execute(() -> {
						PendingCapture capture = PENDING_CAPTURES.get(payload.requestId());
						if (capture == null || !capture.botUuid().equals(context.player().getUUID())) {
							return;
						}
						if (capture.previewFuture().complete(payload.pixels())) {
							notifyHandheldPhotoCaptured(capture);
						}
						cleanupIfFinished(payload.requestId(), capture);
					});
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotFullFrameC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server == null) {
						return;
					}
					server.execute(() -> {
						PendingCapture capture = PENDING_CAPTURES.get(payload.requestId());
						if (capture == null || !capture.botUuid().equals(context.player().getUUID())) {
							return;
						}
						capture.fullFuture().complete(payload.pixels());
						cleanupIfFinished(payload.requestId(), capture);
					});
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotLiveFrameC2SPayload.TYPE,
				(payload, context) -> {
					long receivedAtNanos = System.nanoTime();
					ActiveLiveStream stream = ACTIVE_LIVE_STREAMS.get(payload.streamId());
					if (stream == null || !stream.botUuid().equals(context.player().getUUID())) {
						return;
					}
					long clientFrameNanos = payload.clientFrameNanos() > 0L ? payload.clientFrameNanos() : receivedAtNanos;
					stream.offerFrame(new LiveStreamFrame(payload.pixels(), clientFrameNanos, receivedAtNanos));
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotMapTileC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server == null) {
						return;
					}
					server.execute(() -> {
						PendingMapTileCapture capture = PENDING_MAP_TILE_CAPTURES.get(payload.requestId());
						if (capture == null || !capture.botUuid().equals(context.player().getUUID())) {
							return;
						}
						capture.pixelsFuture().complete(payload.pixels());
						PENDING_MAP_TILE_CAPTURES.remove(payload.requestId(), capture);
					});
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotCaptureFailureC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server == null) {
						return;
					}
					server.execute(() -> {
						PendingCapture capture = PENDING_CAPTURES.get(payload.requestId());
						if (capture == null || !capture.botUuid().equals(context.player().getUUID())) {
							PendingVideoRecording recording = PENDING_VIDEO_RECORDINGS.get(payload.requestId());
							if (recording == null || !recording.botUuid().equals(context.player().getUUID())) {
								return;
							}
							IllegalStateException failure = new IllegalStateException(payload.message());
							recording.completionFuture().completeExceptionally(failure);
							PENDING_VIDEO_RECORDINGS.remove(payload.requestId());
							releaseBotCameraIfNeeded(recording.server(), recording.botUuid(), recording.resetCameraOnFinish());
							return;
						}
						IllegalStateException failure = new IllegalStateException(payload.message());
						capture.previewFuture().completeExceptionally(failure);
						capture.fullFuture().completeExceptionally(failure);
						PENDING_CAPTURES.remove(payload.requestId());
						releaseBotCameraIfNeeded(capture.server(), capture.botUuid(), capture.resetCameraOnFinish());
					});
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotItemIconC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server == null) {
						return;
					}
					server.execute(() -> {
						PendingItemIconCapture capture = PENDING_ITEM_ICON_CAPTURES.get(payload.requestId());
						if (capture == null || !capture.botUuid().equals(context.player().getUUID())) {
							return;
						}
						capture.pixelsFuture().complete(payload.argbPixels());
						PENDING_ITEM_ICON_CAPTURES.remove(payload.requestId(), capture);
					});
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotLiveStreamFailureC2SPayload.TYPE,
				(payload, context) -> {
					ActiveLiveStream stream = ACTIVE_LIVE_STREAMS.get(payload.streamId());
					if (stream == null || !stream.botUuid().equals(context.player().getUUID())) {
						return;
					}
					context.player().level().getServer().execute(() -> {
						ActiveLiveStream current = ACTIVE_LIVE_STREAMS.get(payload.streamId());
						if (current == null || !current.botUuid().equals(context.player().getUUID())) {
							return;
						}
						stopLiveStreamInternal(current, payload.message(), true);
					});
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotMapTileFailureC2SPayload.TYPE,
				(payload, context) -> {
					PendingMapTileCapture capture = PENDING_MAP_TILE_CAPTURES.get(payload.requestId());
					if (capture == null || !capture.botUuid().equals(context.player().getUUID())) {
						return;
					}
					context.player().level().getServer().execute(() -> {
						PendingMapTileCapture current = PENDING_MAP_TILE_CAPTURES.remove(payload.requestId());
						if (current == null || !current.botUuid().equals(context.player().getUUID())) {
							return;
						}
						current.pixelsFuture().completeExceptionally(new IllegalStateException(payload.message()));
					});
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoRecordingStartedC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server == null) {
						return;
					}
					server.execute(() -> {
						PendingVideoRecording recording = PENDING_VIDEO_RECORDINGS.get(payload.requestId());
						if (recording == null || !recording.botUuid().equals(context.player().getUUID())) {
							return;
						}
						CameraVideoRecordingSystem.notifyRecordingStarted(server, payload.requestId());
					});
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoFileChunkC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server == null) {
						return;
					}
					server.execute(() -> receiveRemoteVideoChunk(context.player(), payload));
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotVideoRecordingCompleteC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server == null) {
						return;
					}
					server.execute(() -> {
						PendingVideoRecording recording = PENDING_VIDEO_RECORDINGS.get(payload.requestId());
						if (recording == null || !recording.botUuid().equals(context.player().getUUID())) {
							return;
						}
						Path uploadedVideo = consumeCompletedRemoteVideo(recording, payload.requestId());
						if (uploadedVideo == null && (payload.videoPath() == null || payload.videoPath().isBlank())) {
							failVideoRecording(payload.requestId(), recording, "Удалённый renderer-клиент не передал MP4");
							return;
						}
						recording.completionFuture().complete(new VideoRecordingResult(
								payload.durationMs(), payload.fps(),
								uploadedVideo != null ? uploadedVideo.toString() : payload.videoPath(),
								payload.previewPixels(), payload.fullPixels()
						));
						PENDING_VIDEO_RECORDINGS.remove(payload.requestId());
						releaseBotCameraIfNeeded(recording.server(), recording.botUuid(), recording.resetCameraOnFinish());
					});
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotItemIconFailureC2SPayload.TYPE,
				(payload, context) -> {
					PendingItemIconCapture capture = PENDING_ITEM_ICON_CAPTURES.get(payload.requestId());
					if (capture == null || !capture.botUuid().equals(context.player().getUUID())) {
						return;
					}
					context.player().level().getServer().execute(() -> {
						PendingItemIconCapture current = PENDING_ITEM_ICON_CAPTURES.remove(payload.requestId());
						if (current == null || !current.botUuid().equals(context.player().getUUID())) {
							return;
						}
						current.pixelsFuture().completeExceptionally(new IllegalStateException(payload.message()));
					});
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotAudioFrameC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server == null) {
						return;
					}
					UUID botUuid = context.player().getUUID();
					long receivedAtNanos = System.nanoTime();
					short[] frame = decodePcmFrame(payload.pcm(), AUDIO_FRAME_SAMPLES);
					if (frame == null) {
						return;
					}
					server.execute(() -> {
						ActiveAudioCapture capture = ACTIVE_AUDIO_CAPTURES.get(payload.audioId());
						if (capture == null || !capture.botUuid().equals(botUuid)) {
							return;
						}
						capture.markFrameReceived();
						try {
							long clientFrameNanos = payload.clientFrameNanos() > 0L ? payload.clientFrameNanos() : receivedAtNanos;
							capture.onFrame().accept(new AudioCaptureFrame(frame, receivedAtNanos, clientFrameNanos));
						} catch (Exception exception) {
							Lg2.LOGGER.warn("Renderer bot audio frame callback failed for {}", payload.audioId(), exception);
						}
					});
				}
		);
		ServerPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotAudioCaptureFailureC2SPayload.TYPE,
				(payload, context) -> {
					ActiveAudioCapture capture = ACTIVE_AUDIO_CAPTURES.get(payload.audioId());
					if (capture == null || !capture.botUuid().equals(context.player().getUUID())) {
						return;
					}
					context.player().level().getServer().execute(() -> {
						ActiveAudioCapture current = ACTIVE_AUDIO_CAPTURES.get(payload.audioId());
						if (current == null || !current.botUuid().equals(context.player().getUUID())) {
							return;
						}
						stopAudioCaptureInternal(current, payload.message(), true);
					});
				}
		);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			releaseAllVirtualCameraChunkTickets(server);
			clearAllShadowSyncStates(server, false);
			READY_BOTS.clear();
			for (PendingCapture capture : PENDING_CAPTURES.values()) {
				IllegalStateException failure = new IllegalStateException("Renderer bot capture aborted: server stopping");
				capture.previewFuture().completeExceptionally(failure);
				capture.fullFuture().completeExceptionally(failure);
			}
			PENDING_CAPTURES.clear();

			for (PendingVideoRecording recording : PENDING_VIDEO_RECORDINGS.values()) {
				recording.completionFuture().completeExceptionally(new IllegalStateException("Renderer bot recording aborted: server stopping"));
			}
			PENDING_VIDEO_RECORDINGS.clear();
			for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
				stream.onFailure().accept("Renderer bot live stream aborted: server stopping");
			}
			ACTIVE_LIVE_STREAMS.clear();
			LIVE_STREAMS_BY_OWNER.clear();
			for (PendingMapTileCapture capture : PENDING_MAP_TILE_CAPTURES.values()) {
				capture.pixelsFuture().completeExceptionally(new IllegalStateException("Renderer bot map tile aborted: server stopping"));
			}
			PENDING_MAP_TILE_CAPTURES.clear();
			for (PendingItemIconCapture capture : PENDING_ITEM_ICON_CAPTURES.values()) {
				capture.pixelsFuture().completeExceptionally(new IllegalStateException("Renderer bot item icon aborted: server stopping"));
			}
			PENDING_ITEM_ICON_CAPTURES.clear();
			for (ActiveAudioCapture capture : ACTIVE_AUDIO_CAPTURES.values()) {
				capture.onFailure().accept("Renderer bot audio capture aborted: server stopping");
			}
			ACTIVE_AUDIO_CAPTURES.clear();
			AUDIO_CAPTURES_BY_OWNER.clear();
		});
	}

	public static ClientCaptureHandle requestPhotoCapture(ServerPlayer requester, int mapsWide, int mapsHigh) {
		ServerLevel level = requester == null || !(requester.level() instanceof ServerLevel serverLevel) ? null : serverLevel;
		if (level == null) {
			return null;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return null;
		}

		ServerPlayer bot = selectBot(server);
		if (bot == null) {
			return null;
		}
		return requestCaptureInternal(
				server,
				bot,
				level,
				requester.getX(),
				requester.getY(),
				requester.getZ(),
				requester.getYRot(),
				requester.getXRot(),
				128,
				128,
				Math.max(1, mapsWide) * 128,
				Math.max(1, mapsHigh) * 128,
				70,
				null,
				requester.getUUID(),
				requester.getEyePosition().add(requester.getLookAngle().normalize().scale(0.35D)),
				true
		);
	}

	public static ClientCaptureHandle requestCapture(
			ServerLevel level,
			double x,
			double y,
			double z,
			float yaw,
			float pitch,
			int previewWidth,
			int previewHeight,
			int fullWidth,
			int fullHeight,
			int fovDegrees
	) {
		MinecraftServer server = level != null ? level.getServer() : null;
		if (server == null) {
			return null;
		}

		ServerPlayer bot = selectBot(server);
		if (bot == null) {
			return null;
		}

		return requestCaptureInternal(
				server,
				bot,
				level,
				x,
				y,
				z,
				yaw,
				pitch,
				previewWidth,
				previewHeight,
				fullWidth,
				fullHeight,
				fovDegrees,
				null,
				null,
				null,
				false
		);
	}

	private static ClientCaptureHandle requestCaptureInternal(
			MinecraftServer server,
			ServerPlayer bot,
			ServerLevel level,
			double x,
			double y,
			double z,
			float yaw,
			float pitch,
			int previewWidth,
			int previewHeight,
			int fullWidth,
			int fullHeight,
			int fovDegrees,
			Entity followTarget,
			UUID feedbackPlayerId,
			Vec3 feedbackShutterOrigin,
			boolean resetCameraOnFinish
	) {
		if (server == null || bot == null || level == null) {
			return null;
		}
		if (!canBotRenderLevel(bot, level)) {
			return null;
		}

		int clampedPreviewWidth = Math.max(1, previewWidth);
		int clampedPreviewHeight = Math.max(1, previewHeight);
		int clampedFullWidth = Math.max(1, fullWidth);
		int clampedFullHeight = Math.max(1, fullHeight);
		UUID requestId = UUID.randomUUID();
		UUID renderSessionId = resolveRenderSessionId(
				level.dimension(),
				null,
				x,
				z,
				followTarget != null ? followTarget.getUUID() : null
		);
		CompletableFuture<byte[]> previewFuture = new CompletableFuture<>();
		CompletableFuture<byte[]> fullFuture = new CompletableFuture<>();
		PendingCapture pending = new PendingCapture(
				requestId,
				renderSessionId,
				server,
				bot.getUUID(),
				resetCameraOnFinish,
				level.dimension(),
				x,
				y,
				z,
				yaw,
				pitch,
				Math.max(1, fovDegrees),
				followTarget != null ? followTarget.getUUID() : null,
				feedbackPlayerId,
				feedbackShutterOrigin,
				clampedPreviewWidth,
				clampedPreviewHeight,
				clampedFullWidth,
				clampedFullHeight,
				previewFuture,
				fullFuture
		);
		PENDING_CAPTURES.put(requestId, pending);

		return new ClientCaptureHandle(requestId, previewFuture, fullFuture);
	}

	public static CompletableFuture<byte[]> requestTopDownMapTile(
			ServerLevel level,
			int lod,
			long tileX,
			long tileZ,
			double centerX,
			double centerZ,
			int tileSize,
			double blocksPerPixel,
			List<ChunkPos> sourceChunks,
			int priorityScore,
			boolean activeView
	) {
		CompletableFuture<byte[]> future = new CompletableFuture<>();
		MinecraftServer server = level != null ? level.getServer() : null;
		if (server == null) {
			future.completeExceptionally(new IllegalStateException("Сервер карты недоступен"));
			return future;
		}
		if (!Level.OVERWORLD.equals(level.dimension())) {
			future.completeExceptionally(new IllegalStateException("Яндекс-карта рендерит только верхний мир"));
			return future;
		}
		ServerPlayer bot = selectBot(server);
		if (bot == null) {
			future.completeExceptionally(new IllegalStateException("Нет активного клиента камеры"));
			return future;
		}
		if (!canBotRenderLevel(bot, level)) {
			future.completeExceptionally(new IllegalStateException("Клиент камеры не может рендерить этот мир"));
			return future;
		}
		if (!ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotMapTileRequestS2CPayload.TYPE)) {
			future.completeExceptionally(new IllegalStateException("Клиент камеры не поддерживает тайлы карты"));
			return future;
		}
		List<ChunkPos> safeSourceChunks = sourceChunks == null
				? List.of()
				: sourceChunks.stream().filter(Objects::nonNull).distinct().toList();
		if (safeSourceChunks.isEmpty()) {
			future.completeExceptionally(new IllegalStateException("Для тайла карты нет сохранённых чанков"));
			return future;
		}
		int safePriorityScore = Math.max(0, priorityScore);
		UUID requestId = UUID.randomUUID();
		UUID renderSessionId = resolveMapRenderSessionId(level.dimension());
		int clampedTileSize = Math.max(1, tileSize);
		double safeBlocksPerPixel = Math.max(1.0D / 16.0D, blocksPerPixel);
		long queuedAtMillis = System.currentTimeMillis();
		double worldCenterDistanceSquared = mapTileWorldCenterDistanceSquared(centerX, centerZ);
		PendingMapTileCapture capture = new PendingMapTileCapture(
				requestId,
				renderSessionId,
				server,
				bot.getUUID(),
				level.dimension(),
				centerX,
				centerZ,
				Math.max(0, lod),
				tileX,
				tileZ,
				clampedTileSize,
				safeBlocksPerPixel,
				safeSourceChunks,
				safePriorityScore,
				activeView,
				queuedAtMillis,
				NEXT_MAP_TILE_SEQUENCE.getAndIncrement(),
				worldCenterDistanceSquared,
				future
		);
		PendingMapTileCapture displaced = null;
		boolean admitted;
		synchronized (MAP_TILE_QUEUE_LOCK) {
			boolean displacedRemoved = false;
			if (PENDING_MAP_TILE_CAPTURES.size() >= MAX_PENDING_MAP_TILE_CAPTURES) {
				displaced = findLessPreferredWaitingMapTile(capture);
				if (displaced != null) {
					displacedRemoved = PENDING_MAP_TILE_CAPTURES.remove(displaced.requestId(), displaced);
					if (!displacedRemoved) {
						displaced = null;
					}
				}
			}
			admitted = PENDING_MAP_TILE_CAPTURES.size() < MAX_PENDING_MAP_TILE_CAPTURES || displacedRemoved;
			if (admitted) {
				PENDING_MAP_TILE_CAPTURES.put(requestId, capture);
			}
		}
		if (!admitted) {
			future.completeExceptionally(new IllegalStateException("Очередь тайлов карты занята"));
			return future;
		}
		future.whenComplete((pixels, throwable) -> PENDING_MAP_TILE_CAPTURES.remove(requestId, capture));
		if (displaced != null) {
			displaced.pixelsFuture().completeExceptionally(new IllegalStateException("Тайл карты вытеснен более приоритетной очередью"));
		}
		return future;
	}

	/**
	 * Map tiles must never turn a missing location in an MCA file into new
	 * terrain.  Loading a server chunk through a normal ticket is unsafe here:
	 * vanilla can switch from the loading pyramid to world generation for an
	 * absent dependency.  Read a finished chunk directly from the region store
	 * instead, encode the normal shadow packet, and leave the server chunk map
	 * untouched.
	 */
	private static void prepareReadOnlyMapTileChunks(PendingMapTileCapture capture, ServerLevel level, ServerPlayer bot) {
		if (capture == null || level == null || bot == null || !capture.beginReadOnlyChunkLoad()) {
			return;
		}
		if (capture.sourceChunks().isEmpty()) {
			failMapTileCapture(capture.requestId(), capture, "Для тайла карты нет сохранённых чанков");
			return;
		}
		Map<Long, byte[]> payloads = new ConcurrentHashMap<>();
		for (ChunkPos pos : capture.readOnlyShadowChunks()) {
			if (pos == null) {
				failMapTileCapture(capture.requestId(), capture, "Для тайла карты указан некорректный чанк теневого мира");
				return;
			}
			boolean requiredSource = capture.isRequiredSourceChunk(pos);
			LevelChunk loaded = level.getChunkSource().getChunkNow(pos.x, pos.z);
			if (loaded != null) {
				finishReadOnlyMapTileChunk(capture, level, bot, pos, loaded, null, payloads, requiredSource);
				continue;
			}
			level.getChunkSource().chunkMap.read(pos).whenComplete((optionalTag, throwable) -> {
				MinecraftServer server = capture.server();
				if (server != null) {
					server.execute(() -> finishReadOnlyMapTileChunk(
							capture,
							level,
							bot,
							pos,
							null,
							throwable == null ? optionalTag : null,
							payloads,
							requiredSource,
							throwable
					));
				}
			});
		}
	}

	private static void finishReadOnlyMapTileChunk(
			PendingMapTileCapture capture,
			ServerLevel level,
			ServerPlayer bot,
			ChunkPos pos,
			LevelChunk loadedChunk,
			Optional<CompoundTag> storedTag,
			Map<Long, byte[]> payloads,
			boolean requiredSource
	) {
		finishReadOnlyMapTileChunk(capture, level, bot, pos, loadedChunk, storedTag, payloads, requiredSource, null);
	}

	private static void finishReadOnlyMapTileChunk(
			PendingMapTileCapture capture,
			ServerLevel level,
			ServerPlayer bot,
			ChunkPos pos,
			LevelChunk loadedChunk,
			Optional<CompoundTag> storedTag,
			Map<Long, byte[]> payloads,
			boolean requiredSource,
			Throwable readFailure
	) {
		if (!isPendingMapTileCapture(capture) || level == null || bot == null || pos == null || payloads == null) {
			return;
		}
		if (readFailure != null && requiredSource) {
			Lg2.LOGGER.debug("Failed to read saved Yandex map chunk {}", pos, readFailure);
			failMapTileCapture(capture.requestId(), capture, "Не удалось прочитать сохранённый чанк для тайла карты");
			return;
		}
		try {
			LevelChunk source = loadedChunk != null ? loadedChunk : level.getChunkSource().getChunkNow(pos.x, pos.z);
			LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
			if (source != null && !source.isLightCorrect() && requiredSource) {
				failMapTileCapture(capture.requestId(), capture, "Свет сохранённого чанка для тайла карты ещё не готов");
				return;
			}
			if (source == null) {
				if ((storedTag == null || storedTag.isEmpty()) && requiredSource) {
					failMapTileCapture(capture.requestId(), capture, "Сохранённый чанк для тайла карты не найден");
					return;
				}
				if (storedTag != null && storedTag.isPresent()) {
					ReadOnlyMapChunkSnapshot snapshot = readFinishedMapChunkFromStorage(level, pos, storedTag.get());
					if (snapshot != null) {
						source = snapshot.chunk();
						lightEngine = snapshot.lightEngine();
					}
				}
			}
			if (source == null && !requiredSource) {
				// Terrain compilation needs a small chunk neighbourhood.  Missing
				// neighbours are represented as temporary empty chunks: they make the
				// saved centre chunk renderable without asking the server to generate
				// any terrain or writing anything to the world.
				source = new LevelChunk(level, pos);
				lightEngine = createReadOnlyMapLightEngine(level, source);
			}
			if (source == null || lightEngine == null) {
				failMapTileCapture(capture.requestId(), capture, "Сохранённый чанк для тайла карты не завершён или повреждён");
				return;
			}
			byte[] packet = encodeShadowChunkPacket(level, bot, source, lightEngine);
			if (packet == null || packet.length == 0) {
				failMapTileCapture(capture.requestId(), capture, "Не удалось подготовить сохранённый чанк для тайла карты");
				return;
			}
			payloads.put(pos.toLong(), packet);
			if (payloads.size() == capture.readOnlyShadowChunks().size()) {
				capture.completeReadOnlyChunkLoad(payloads);
			}
		} catch (Exception exception) {
			if (!requiredSource) {
				try {
					LevelChunk emptyChunk = new LevelChunk(level, pos);
					byte[] packet = encodeShadowChunkPacket(level, bot, emptyChunk, createReadOnlyMapLightEngine(level, emptyChunk));
					if (packet != null && packet.length > 0) {
						payloads.put(pos.toLong(), packet);
						if (payloads.size() == capture.readOnlyShadowChunks().size()) {
							capture.completeReadOnlyChunkLoad(payloads);
						}
						return;
					}
				} catch (Exception ignored) {
					// Report the original decode error below.
				}
			}
			Lg2.LOGGER.debug("Failed to decode saved Yandex map chunk {}", pos, exception);
			failMapTileCapture(capture.requestId(), capture, "Не удалось прочитать сохранённый чанк для тайла карты");
		}
	}

	/**
	 * Builds the packet-only representation of an MCA chunk without registering
	 * it in the server's ChunkMap or shared LightEngine.  SerializableChunkData's
	 * normal {@code read} path queues its light sections in the live engine;
	 * doing that for a map snapshot both contaminates live state and used to make
	 * packet timing decide whether a tile was rendered dark.  Keep the decoded
	 * sections and their saved light layers together in a short-lived engine.
	 */
	private static ReadOnlyMapChunkSnapshot readFinishedMapChunkFromStorage(ServerLevel level, ChunkPos pos, CompoundTag tag) {
		if (level == null || pos == null || tag == null) {
			return null;
		}
		ChunkStatus status = SerializableChunkData.getChunkStatusFromTag(tag);
		if (status == null || status.isBefore(ChunkStatus.FULL)) {
			return null;
		}
		SerializableChunkData data = SerializableChunkData.parse(
				level,
				PalettedContainerFactory.create(level.registryAccess()),
				tag
		);
		if (!data.lightCorrect()) {
			return null;
		}
		LevelChunk chunk = new LevelChunk(level, pos);
		LevelChunkSection[] chunkSections = chunk.getSections();
		for (SerializableChunkData.SectionData sectionData : data.sectionData()) {
			if (sectionData == null || sectionData.chunkSection() == null) {
				continue;
			}
			int sectionIndex = level.getSectionIndexFromSectionY(sectionData.y());
			if (sectionIndex < 0 || sectionIndex >= chunkSections.length) {
				continue;
			}
			chunkSections[sectionIndex] = sectionData.chunkSection().copy();
		}
		for (Map.Entry<Heightmap.Types, long[]> heightmap : data.heightmaps().entrySet()) {
			if (heightmap.getKey() != null && heightmap.getValue() != null) {
				chunk.setHeightmap(heightmap.getKey(), heightmap.getValue().clone());
			}
		}
		chunk.setLightCorrect(data.lightCorrect());
		return new ReadOnlyMapChunkSnapshot(chunk, createReadOnlyMapLightEngine(level, chunk, data));
	}

	private static LevelLightEngine createReadOnlyMapLightEngine(
			ServerLevel level,
			LevelChunk chunk,
			SerializableChunkData data
	) {
		LightChunkGetter source = new LightChunkGetter() {
			@Override
			public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
				return chunk.getPos().x == chunkX && chunk.getPos().z == chunkZ ? chunk : null;
			}

			@Override
			public void onLightUpdate(LightLayer layer, SectionPos sectionPos) {
				// This engine exists only long enough to serialize one packet.  It is
				// deliberately not attached to the live level renderer or chunk map.
			}

			@Override
			public BlockGetter getLevel() {
				return level;
			}
		};
		LevelLightEngine lightEngine = new LevelLightEngine(source, true, level.dimensionType().hasSkyLight());
		lightEngine.retainData(chunk.getPos(), true);
		if (data == null) {
			return lightEngine;
		}
		for (SerializableChunkData.SectionData sectionData : data.sectionData()) {
			if (sectionData == null) {
				continue;
			}
			SectionPos sectionPos = SectionPos.of(chunk.getPos(), sectionData.y());
			if (sectionData.blockLight() != null) {
				lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, sectionData.blockLight().copy());
			}
			if (sectionData.skyLight() != null) {
				lightEngine.queueSectionData(LightLayer.SKY, sectionPos, sectionData.skyLight().copy());
			}
		}
		// ClientboundLightUpdatePacketData queries getDataLayerData(), which reads
		// the queued section data directly.  Do not run light propagation here:
		// a one-chunk snapshot has no neighbours and propagation would turn an
		// exact saved-light snapshot into an approximation at its borders.
		return lightEngine;
	}

	private static LevelLightEngine createReadOnlyMapLightEngine(ServerLevel level, LevelChunk chunk) {
		return createReadOnlyMapLightEngine(level, chunk, null);
	}

	private static byte[] encodeShadowChunkPacket(ServerLevel level, ServerPlayer bot, LevelChunk chunk, LevelLightEngine lightEngine) {
		if (level == null || bot == null || chunk == null || lightEngine == null) {
			return null;
		}
		ClientboundLevelChunkWithLightPacket packet = PacketContext.supplyWithContext(
				bot.connection,
				() -> new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null)
		);
		return RendererBotShadowPacketCodec.encodeChunkPacket(level.registryAccess(), bot.connection, packet);
	}

	private static byte[] encodeShadowChunkPacket(ServerLevel level, ServerPlayer bot, LevelChunk chunk) {
		return level == null ? null : encodeShadowChunkPacket(level, bot, chunk, level.getChunkSource().getLightEngine());
	}

	private record ReadOnlyMapChunkSnapshot(LevelChunk chunk, LevelLightEngine lightEngine) {
	}

	private static double mapTileWorldCenterDistanceSquared(double centerX, double centerZ) {
		if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)) {
			return Double.POSITIVE_INFINITY;
		}
		double distance = centerX * centerX + centerZ * centerZ;
		return Double.isFinite(distance) ? distance : Double.POSITIVE_INFINITY;
	}

	private static PendingMapTileCapture findLessPreferredWaitingMapTile(PendingMapTileCapture incoming) {
		if (incoming == null) {
			return null;
		}
		PendingMapTileCapture candidate = null;
		for (PendingMapTileCapture pending : PENDING_MAP_TILE_CAPTURES.values()) {
			if (pending == null
					|| pending.clientRequestSent()
					|| pending.pixelsFuture().isDone()
					|| compareMapTileCapturePriority(pending, incoming) <= 0) {
				continue;
			}
			if (candidate == null || compareMapTileCapturePriority(pending, candidate) > 0) {
				candidate = pending;
			}
		}
		return candidate;
	}

	public static CompletableFuture<byte[]> requestItemIcon(MinecraftServer server, ItemStack stack, int iconSize) {
		CompletableFuture<byte[]> future = new CompletableFuture<>();
		if (server == null) {
			future.completeExceptionally(new IllegalStateException("Сервер камеры недоступен"));
			return future;
		}
		if (stack == null || stack.isEmpty()) {
			future.completeExceptionally(new IllegalStateException("Предмет для иконки пуст"));
			return future;
		}
		ServerPlayer bot = selectBot(server);
		if (bot == null) {
			future.completeExceptionally(new IllegalStateException("Нет активного клиента камеры"));
			return future;
		}
		if (!ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotItemIconRequestS2CPayload.TYPE)) {
			future.completeExceptionally(new IllegalStateException("Клиент камеры не поддерживает иконки предметов"));
			return future;
		}

		UUID requestId = UUID.randomUUID();
		int safeIconSize = Mth.clamp(iconSize, 8, 128);
		ItemStack iconStack = stack.copyWithCount(1);
		PendingItemIconCapture capture = new PendingItemIconCapture(
				requestId,
				server,
				bot.getUUID(),
				safeIconSize,
				future
		);
		PENDING_ITEM_ICON_CAPTURES.put(requestId, capture);
		future.orTimeout(ITEM_ICON_CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS).exceptionally(throwable -> {
			PENDING_ITEM_ICON_CAPTURES.remove(requestId, capture);
			return null;
		});
		ServerPlayNetworking.send(
				bot,
				new RendererBotPayloads.RendererBotItemIconRequestS2CPayload(requestId, iconStack, safeIconSize)
		);
		return future;
	}

	public static boolean ensureLiveStream(
			String ownerKey,
			ServerLevel level,
			BlockPos cameraPos,
			double x,
			double y,
			double z,
			float yaw,
			float pitch,
			int fullWidth,
			int fullHeight,
			int fovDegrees,
			int targetFps,
			Consumer<LiveStreamFrame> onFrame,
			Consumer<String> onFailure
	) {
		return ensureLiveStream(
				ownerKey,
				level,
				cameraPos,
				x,
				y,
				z,
				yaw,
				pitch,
				null,
				Set.of(),
				false,
				fullWidth,
				fullHeight,
				fovDegrees,
				targetFps,
				onFrame,
				onFailure
		);
	}

	public static boolean ensureLiveStream(
			String ownerKey,
			ServerLevel level,
			BlockPos cameraPos,
			double x,
			double y,
			double z,
			float yaw,
			float pitch,
			UUID followEntityUuid,
			Set<UUID> hiddenEntityUuids,
			boolean omnidirectionalChunkLoading,
			int fullWidth,
			int fullHeight,
			int fovDegrees,
			int targetFps,
			Consumer<LiveStreamFrame> onFrame,
			Consumer<String> onFailure
	) {
		if (ownerKey == null || level == null || onFrame == null || onFailure == null) {
			return false;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			onFailure.accept("Сервер камеры недоступен");
			return false;
		}

		ServerPlayer bot = selectBot(server);
		if (bot == null) {
			onFailure.accept("Нет активного клиента камеры");
			return false;
		}
		if (!canBotRenderLevel(bot, level)) {
			onFailure.accept("Клиент камеры не может рендерить этот мир");
			return false;
		}

		// A live stream is rendered from explicit pose packets, not by making the
		// client follow this entity.  It is therefore safe (and necessary) to
		// remove the physical camera carrier from its shadow world.  Besides a
		// player-held camera this also covers the ItemDisplay model of a placed
		// camera, which otherwise can sit directly in front of its own lens.
		Set<UUID> effectiveHiddenEntityUuids = resolveLiveStreamHiddenEntityUuids(
				level,
				cameraPos,
				followEntityUuid,
				hiddenEntityUuids
		);
		// The placed camera uses a Polymer PLAYER_HEAD solely for collision.  It
		// is not part of the camera's picture and must be removed from the shadow
		// world just like its ItemDisplay.  A mobile camera has no fixed block
		// coordinate here, but its follow anchor identifies that same case.
		Entity followEntity = followEntityUuid == null ? null : level.getEntity(followEntityUuid);
		boolean hideCameraCollisionBlock = cameraPos != null
				|| CameraRelocationSystem.isMobileCameraAnchor(followEntity);
		LiveStreamSpec desiredSpec = new LiveStreamSpec(
				resolveRenderSessionId(level.dimension(), cameraPos, x, z, followEntityUuid),
				level.dimension(),
				cameraPos == null ? null : cameraPos.immutable(),
				x,
				y,
				z,
				yaw,
				pitch,
				followEntityUuid,
				effectiveHiddenEntityUuids,
				omnidirectionalChunkLoading,
				hideCameraCollisionBlock,
				Math.max(1, fullWidth),
				Math.max(1, fullHeight),
				Math.max(1, fovDegrees),
				Math.clamp(Math.max(1, targetFps), 1, MAX_LIVE_STREAM_FPS)
		);
		UUID existingStreamId = LIVE_STREAMS_BY_OWNER.get(ownerKey);
		if (existingStreamId != null) {
			ActiveLiveStream existing = ACTIVE_LIVE_STREAMS.get(existingStreamId);
			if (canReuseLiveStream(existing, bot, desiredSpec)) {
				return true;
			}
			stopLiveStreamInternal(existing, "Renderer bot live stream restarted", false);
		}

		UUID streamId = UUID.randomUUID();
		ActiveLiveStream stream = new ActiveLiveStream(server, streamId, ownerKey, bot.getUUID(), desiredSpec, onFrame, onFailure);
		ACTIVE_LIVE_STREAMS.put(streamId, stream);
		LIVE_STREAMS_BY_OWNER.put(ownerKey, streamId);
		ServerPlayNetworking.send(
				bot,
				new RendererBotPayloads.RendererBotLiveStreamStartS2CPayload(
						streamId,
						desiredSpec.renderSessionId(),
						desiredSpec.dimension().identifier().toString(),
						desiredSpec.expectedX(),
						desiredSpec.expectedY(),
						desiredSpec.expectedZ(),
						desiredSpec.expectedYaw(),
						desiredSpec.expectedPitch(),
					// Follow UUIDs are kept server-side for target resolution.  The
					// renderer client is driven by the pose packets below instead of
					// waiting for a moving anchor entity to be tracked in its shadow
					// world; missing that entity produced a silent stream timeout.
					null,
					List.copyOf(desiredSpec.hiddenEntityUuids()),
					desiredSpec.hideCameraCollisionBlock(),
					desiredSpec.fullWidth(),
						desiredSpec.fullHeight(),
						desiredSpec.fovDegrees(),
						desiredSpec.targetFps()
				)
		);
		// The client deliberately renders follow streams from explicit pose packets
		// instead of trusting entity tracking in its shadow world. Send the first
		// pose in the same tick as start; otherwise it briefly treats the absolute
		// lens Y coordinate as player feet and points the camera into its own body.
		if (desiredSpec.followEntityUuid() != null
				&& ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotLiveStreamPoseS2CPayload.TYPE)) {
			ServerPlayNetworking.send(bot, new RendererBotPayloads.RendererBotLiveStreamPoseS2CPayload(
					streamId,
					desiredSpec.expectedX(),
					desiredSpec.expectedY(),
					desiredSpec.expectedZ(),
					desiredSpec.expectedYaw(),
					desiredSpec.expectedPitch(),
					0.0F
			));
		}
		return true;
	}

	private static Set<UUID> resolveLiveStreamHiddenEntityUuids(
			ServerLevel level,
			BlockPos cameraPos,
			UUID followEntityUuid,
			Set<UUID> requestedHiddenEntityUuids
	) {
		Set<UUID> hiddenEntityUuids = new LinkedHashSet<>();
		if (requestedHiddenEntityUuids != null) {
			hiddenEntityUuids.addAll(requestedHiddenEntityUuids);
		}
		if (followEntityUuid != null) {
			hiddenEntityUuids.add(followEntityUuid);
		}
		if (cameraPos != null) {
			hiddenEntityUuids.addAll(CameraBlock.getCameraDisplayEntityUuids(level, cameraPos));
		}
		return hiddenEntityUuids.isEmpty() ? Set.of() : Set.copyOf(hiddenEntityUuids);
	}

	/**
	 * Moves every active screen stream sourced from a fixed camera block onto a
	 * moving entity without stopping the client stream.  Rocket materialisation
	 * removes the block in the same tick, so waiting for the normal screen
	 * refresh would leave the old static target alive long enough for the
	 * renderer to stop producing frames.
	 */
	public static int handoffLiveCameraStreamsToEntity(
			ServerLevel level,
			BlockPos cameraPos,
			UUID followEntityUuid,
			double x,
			double y,
			double z,
			float yaw,
			float pitch
	) {
		if (level == null || cameraPos == null || followEntityUuid == null) {
			return 0;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return 0;
		}
		int handedOff = 0;
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null) {
				continue;
			}
			LiveStreamSpec current = stream.spec();
			if (current == null
					|| current.followEntityUuid() != null
					|| !level.dimension().equals(current.dimension())
					|| !cameraPos.equals(current.cameraPos())) {
				continue;
			}
			Set<UUID> hiddenEntities = new HashSet<>(current.hiddenEntityUuids());
			hiddenEntities.add(followEntityUuid);
			// Keep the stream and render-session identifiers intact.  The client can
			// switch to explicit pose packets on the existing session, avoiding both
			// the stop/start gap and a fresh shadow-world warm-up.
			stream.replaceSpec(new LiveStreamSpec(
					current.renderSessionId(),
					current.dimension(),
					null,
					x,
					y,
					z,
					yaw,
					pitch,
					followEntityUuid,
					Set.copyOf(hiddenEntities),
					true,
					current.hideCameraCollisionBlock(),
					current.fullWidth(),
					current.fullHeight(),
					current.fovDegrees(),
					current.targetFps()
			));
			ServerPlayer bot = server.getPlayerList().getPlayer(stream.botUuid());
			if (bot != null && ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotLiveStreamPoseS2CPayload.TYPE)) {
				ServerPlayNetworking.send(bot, new RendererBotPayloads.RendererBotLiveStreamPoseS2CPayload(
						stream.streamId(), x, y, z, yaw, pitch, 0.0F
				));
			}
			handedOff++;
		}
		return handedOff;
	}

	private static boolean canReuseLiveStream(ActiveLiveStream existing, ServerPlayer bot, LiveStreamSpec desiredSpec) {
		if (existing == null || bot == null || desiredSpec == null
				|| (existing.isStale() && !hasActiveVideoRecording(existing.botUuid()))
				|| !existing.botUuid().equals(bot.getUUID())) {
			return false;
		}
		LiveStreamSpec existingSpec = existing.spec();
		if (existingSpec == null) {
			return false;
		}
		if (desiredSpec.followEntityUuid() != null) {
			return Objects.equals(existingSpec.dimension(), desiredSpec.dimension())
					&& Objects.equals(existingSpec.cameraPos(), desiredSpec.cameraPos())
					&& Objects.equals(existingSpec.followEntityUuid(), desiredSpec.followEntityUuid())
					&& Objects.equals(existingSpec.hiddenEntityUuids(), desiredSpec.hiddenEntityUuids())
					&& existingSpec.omnidirectionalChunkLoading() == desiredSpec.omnidirectionalChunkLoading()
					&& existingSpec.hideCameraCollisionBlock() == desiredSpec.hideCameraCollisionBlock()
					&& existingSpec.fullWidth() == desiredSpec.fullWidth()
					&& existingSpec.fullHeight() == desiredSpec.fullHeight()
					&& existingSpec.fovDegrees() == desiredSpec.fovDegrees()
					&& existingSpec.targetFps() == desiredSpec.targetFps();
		}
		return existingSpec.equals(desiredSpec);
	}

	public static void stopLiveStream(String ownerKey) {
		if (ownerKey == null) {
			return;
		}
		UUID streamId = LIVE_STREAMS_BY_OWNER.remove(ownerKey);
		if (streamId == null) {
			return;
		}
		stopLiveStreamInternal(ACTIVE_LIVE_STREAMS.remove(streamId), "Renderer bot live stream stopped", false);
	}

	public static boolean isLiveStreamHealthy(String ownerKey) {
		if (ownerKey == null) {
			return false;
		}
		UUID streamId = LIVE_STREAMS_BY_OWNER.get(ownerKey);
		if (streamId == null) {
			return false;
		}
		ActiveLiveStream stream = ACTIVE_LIVE_STREAMS.get(streamId);
		return stream != null && (!stream.isStale() || hasActiveVideoRecording(stream.botUuid()));
	}

	/**
	 * Whether this renderer bot is currently serving a real-time view rather
	 * than the low-rate camera-hotbar warm-up.  Map tiles use the same client
	 * world renderer, so they must yield while a monitor is receiving video.
	 */
	public static boolean hasActiveRealtimeLiveStream(MinecraftServer server) {
		if (server == null) {
			return false;
		}
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || stream.server() != server || stream.isStale()) {
				continue;
			}
			LiveStreamSpec spec = stream.spec();
			if (spec != null && spec.targetFps() > CAMERA_HOTBAR_WARMUP_FPS) {
				return true;
			}
		}
		return false;
	}

	public static boolean hasHealthyLiveStreamFollowingEntity(UUID entityUuid) {
		if (entityUuid == null) {
			return false;
		}
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || stream.isStale()) {
				continue;
			}
			LiveStreamSpec spec = stream.spec();
			if (spec != null && entityUuid.equals(spec.followEntityUuid())) {
				return true;
			}
		}
		return false;
	}

	public static boolean ensureAudioCapture(
			String ownerKey,
			ServerLevel level,
			BlockPos microphonePos,
			double radiusBlocks,
			Consumer<AudioCaptureFrame> onFrame,
			Consumer<String> onFailure
	) {
		if (ownerKey == null || level == null || microphonePos == null || onFrame == null || onFailure == null) {
			return false;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			onFailure.accept("Сервер аудио камеры недоступен");
			return false;
		}

		ServerPlayer bot = selectBot(server);
		if (bot == null) {
			onFailure.accept("Нет активного клиента камеры для аудио");
			return false;
		}
		if (!canBotRenderLevel(bot, level)) {
			onFailure.accept("Клиент камеры не может записывать аудио в этом мире");
			return false;
		}
		if (!ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotAudioCaptureStartS2CPayload.TYPE)) {
			onFailure.accept("Клиент камеры не поддерживает запись аудио");
			return false;
		}

		double x = microphonePos.getX() + 0.5D;
		double y = microphonePos.getY() + 0.5D;
		double z = microphonePos.getZ() + 0.5D;
		AudioCaptureSpec desiredSpec = new AudioCaptureSpec(
				resolveRenderSessionId(level.dimension(), microphonePos, x, z, null),
				level.dimension(),
				microphonePos.immutable(),
				x,
				y,
				z,
				Math.max(1.0D, radiusBlocks),
				AUDIO_SAMPLE_RATE,
				AUDIO_FRAME_SAMPLES
		);
		UUID existingAudioId = AUDIO_CAPTURES_BY_OWNER.get(ownerKey);
		if (existingAudioId != null) {
			ActiveAudioCapture existing = ACTIVE_AUDIO_CAPTURES.get(existingAudioId);
			if (canReuseAudioCapture(existing, bot, desiredSpec)) {
				existing.updateCallbacks(onFrame, onFailure);
				return true;
			}
			stopAudioCaptureInternal(existing, "Renderer bot audio capture restarted", false);
		}

		UUID audioId = UUID.randomUUID();
		ActiveAudioCapture capture = new ActiveAudioCapture(server, audioId, ownerKey, bot.getUUID(), desiredSpec, onFrame, onFailure);
		ACTIVE_AUDIO_CAPTURES.put(audioId, capture);
		AUDIO_CAPTURES_BY_OWNER.put(ownerKey, audioId);
		ServerPlayNetworking.send(
				bot,
				new RendererBotPayloads.RendererBotAudioCaptureStartS2CPayload(
						audioId,
						desiredSpec.renderSessionId(),
						desiredSpec.dimension().identifier().toString(),
						desiredSpec.x(),
						desiredSpec.y(),
						desiredSpec.z(),
						desiredSpec.radiusBlocks(),
						desiredSpec.sampleRate(),
						desiredSpec.frameSamples()
				)
		);
		return true;
	}

	private static boolean canReuseAudioCapture(ActiveAudioCapture existing, ServerPlayer bot, AudioCaptureSpec desiredSpec) {
		return existing != null
				&& bot != null
				&& desiredSpec != null
				&& !existing.isStale()
				&& existing.botUuid().equals(bot.getUUID())
				&& desiredSpec.equals(existing.spec());
	}

	public static void stopAudioCapture(String ownerKey) {
		if (ownerKey == null) {
			return;
		}
		UUID audioId = AUDIO_CAPTURES_BY_OWNER.remove(ownerKey);
		if (audioId == null) {
			return;
		}
		stopAudioCaptureInternal(ACTIVE_AUDIO_CAPTURES.remove(audioId), "Renderer bot audio capture stopped", false);
	}

	public static VideoRecordingHandle startVideoRecording(ServerPlayer requester, int mapsWide, int mapsHigh, int targetFps, int maxDurationSeconds) {
		MinecraftServer server = requester == null || requester.level() == null ? null : requester.level().getServer();
		if (server == null) {
			return null;
		}

		ServerPlayer bot = selectBot(server);
		if (bot == null) {
			return null;
		}

		int previewWidth = 128;
		int previewHeight = 128;
		int fullWidth = Math.max(1, mapsWide) * 128;
		int fullHeight = Math.max(1, mapsHigh) * 128;
		int clampedTargetFps = Math.clamp(Math.max(1, targetFps), 1, MAX_VIDEO_RECORDING_FPS);
		if (!canBotRenderLevel(bot, (ServerLevel) requester.level())) {
			return null;
		}
		UUID requestId = UUID.randomUUID();
		UUID renderSessionId = resolveRenderSessionId(
				requester.level().dimension(),
				null,
				requester.getX(),
				requester.getZ(),
				requester.getUUID()
		);
		CompletableFuture<VideoRecordingResult> completionFuture = new CompletableFuture<>();
		PendingVideoRecording pending = new PendingVideoRecording(
				requestId,
				renderSessionId,
				server,
				bot.getUUID(),
				true,
				requester.level().dimension(),
				requester.getX(),
				requester.getY(),
				requester.getZ(),
				requester.getYRot(),
				requester.getXRot(),
				70,
				requester.getUUID(),
				clampedTargetFps,
				fullWidth,
				fullHeight,
				Math.max(1, maxDurationSeconds),
				completionFuture
		);
		PENDING_VIDEO_RECORDINGS.put(requestId, pending);
		return new VideoRecordingHandle(requestId, bot.getUUID(), completionFuture);
	}

	public static void stopVideoRecording(MinecraftServer server, UUID requestId) {
		if (server == null || requestId == null) {
			return;
		}
		PendingVideoRecording recording = PENDING_VIDEO_RECORDINGS.get(requestId);
		if (recording == null) {
			return;
		}
		recording.markStopRequested();
		if (!recording.clientStartSent()) {
			return;
		}
		ServerPlayer bot = server.getPlayerList().getPlayer(recording.botUuid());
		if (bot == null || !ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotVideoRecordingStopS2CPayload.TYPE)) {
			return;
		}
		ServerPlayNetworking.send(bot, new RendererBotPayloads.RendererBotVideoRecordingStopS2CPayload(requestId));
	}

	private static PendingVideoRecording selectVideoRecordingForRenderer(UUID botUuid) {
		if (botUuid == null) {
			return null;
		}
		PendingVideoRecording selected = null;
		for (PendingVideoRecording recording : PENDING_VIDEO_RECORDINGS.values()) {
			if (recording == null || !botUuid.equals(recording.botUuid()) || recording.completionFuture().isDone()) {
				continue;
			}
			// A renderer client has one OpenGL scene. Once a recording has been
			// admitted it owns that scene until its encoder confirms completion.
			if (recording.clientStartSent()) {
				if (selected == null || !selected.clientStartSent() || recording.queuedAtMillis() < selected.queuedAtMillis()) {
					selected = recording;
				}
				continue;
			}
			if (selected == null || (!selected.clientStartSent() && recording.queuedAtMillis() < selected.queuedAtMillis())) {
				selected = recording;
			}
		}
		return selected;
	}

	private static void dispatchReadyVideoRecording(
			MinecraftServer server,
			Map<ShadowSyncKey, ShadowDesiredState> desiredStates
	) {
		if (server == null) {
			return;
		}
		ServerPlayer bot = selectBot(server);
		if (bot == null) {
			return;
		}
		PendingVideoRecording recording = selectVideoRecordingForRenderer(bot.getUUID());
		PendingCapture activeCapture = selectPendingCaptureForRenderer(bot.getUUID());
		if (recording == null || recording.clientStartSent()
				|| (activeCapture != null && activeCapture.clientRequestSent())
				|| desiredStates == null
				|| !desiredStates.containsKey(new ShadowSyncKey(bot.getUUID(), recording.renderSessionId()))
				|| !ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload.TYPE)) {
			return;
		}
		try {
			ServerPlayNetworking.send(bot, new RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload(
					recording.requestId(),
					recording.renderSessionId(),
					recording.dimension().identifier().toString(),
					recording.x(),
					recording.y(),
					recording.z(),
					recording.yaw(),
					recording.pitch(),
					recording.followEntityUuid(),
					128,
					128,
					recording.fullWidth(),
					recording.fullHeight(),
					recording.fovDegrees(),
					recording.targetFps(),
					recording.maxDurationSeconds()
			));
			recording.markClientStartSent();
			armVideoRecordingTimeout(recording);
			if (recording.stopRequested()
					&& ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotVideoRecordingStopS2CPayload.TYPE)) {
				ServerPlayNetworking.send(bot, new RendererBotPayloads.RendererBotVideoRecordingStopS2CPayload(recording.requestId()));
			}
		} catch (RuntimeException exception) {
			failVideoRecording(recording.requestId(), recording, "Не удалось запустить запись на клиенте камеры");
		}
	}

	private static void armVideoRecordingTimeout(PendingVideoRecording recording) {
		if (recording == null || !recording.markTimeoutArmed()) {
			return;
		}
		long timeoutMillis = Math.max(5_000L, Lg2Config.get().cameraRendererBotTimeoutMs);
		recording.completionFuture()
				.orTimeout(Math.max(timeoutMillis, recording.maxDurationSeconds() * 1_000L + 30_000L), TimeUnit.MILLISECONDS)
				.exceptionally(throwable -> {
					if (PENDING_VIDEO_RECORDINGS.remove(recording.requestId(), recording)) {
						MinecraftServer callbackServer = recording.server();
						if (callbackServer != null) {
							callbackServer.execute(() -> releaseBotCameraIfNeeded(callbackServer, recording.botUuid(), recording.resetCameraOnFinish()));
						}
						recording.completionFuture().completeExceptionally(throwable);
					}
					return null;
				});
	}

	private static PendingCapture selectPendingCaptureForRenderer(UUID botUuid) {
		if (botUuid == null) {
			return null;
		}
		PendingCapture selected = null;
		for (PendingCapture capture : PENDING_CAPTURES.values()) {
			if (capture == null || !botUuid.equals(capture.botUuid()) || capture.isDone()) {
				continue;
			}
			if (capture.clientRequestSent()) {
				if (selected == null || !selected.clientRequestSent() || capture.queuedAtMillis() < selected.queuedAtMillis()) {
					selected = capture;
				}
			} else if (selected == null || (!selected.clientRequestSent() && capture.queuedAtMillis() < selected.queuedAtMillis())) {
				selected = capture;
			}
		}
		return selected;
	}

	private static void dispatchReadyPhotoCapture(
			MinecraftServer server,
			Map<ShadowSyncKey, ShadowDesiredState> desiredStates
	) {
		if (server == null) {
			return;
		}
		ServerPlayer bot = selectBot(server);
		if (bot == null || selectVideoRecordingForRenderer(bot.getUUID()) != null) {
			return;
		}
		PendingCapture capture = selectPendingCaptureForRenderer(bot.getUUID());
		if (capture == null || capture.clientRequestSent()
				|| desiredStates == null
				|| !desiredStates.containsKey(new ShadowSyncKey(bot.getUUID(), capture.renderSessionId()))
				|| !ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotCaptureRequestS2CPayload.TYPE)) {
			return;
		}
		try {
			ServerPlayNetworking.send(bot, new RendererBotPayloads.RendererBotCaptureRequestS2CPayload(
					capture.requestId(), capture.renderSessionId(), capture.dimension().identifier().toString(),
					capture.x(), capture.y(), capture.z(), capture.yaw(), capture.pitch(),
					capture.followEntityUuid(), capture.feedbackPlayerId(),
					capture.previewWidth(), capture.previewHeight(), capture.fullWidth(), capture.fullHeight(), capture.fovDegrees()
			));
			capture.markClientRequestSent();
			armCaptureTimeout(capture);
		} catch (RuntimeException exception) {
			failPending(capture.requestId(), capture, exception);
		}
	}

	private static void armCaptureTimeout(PendingCapture capture) {
		if (capture == null || !capture.markTimeoutArmed()) {
			return;
		}
		applyTimeout(capture.requestId(), capture, Math.max(500L, Lg2Config.get().cameraRendererBotTimeoutMs));
	}

	public static boolean hasReadyBot(MinecraftServer server) {
		return server != null && selectBot(server) != null;
	}

	/**
	 * A hand-held video and a still photo cannot share one renderer client's
	 * off-screen world target. Keep photo admission closed until the encoder has
	 * returned its final frame rather than creating a request that must time out.
	 */
	public static boolean isPhotoCaptureBlockedByActiveVideo(MinecraftServer server) {
		ServerPlayer bot = server == null ? null : selectBot(server);
		return bot != null && hasActiveVideoRecording(bot.getUUID());
	}

	public static ServerPlayer readyBot(MinecraftServer server) {
		return server == null ? null : selectBot(server);
	}

	public static List<ServerPlayer> activeBotsRenderingLevel(ServerLevel level) {
		if (level == null) {
			return List.of();
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return List.of();
		}
		List<ServerPlayer> recipients = new ArrayList<>();
		for (UUID botUuid : READY_BOTS.keySet()) {
			ServerPlayer bot = server.getPlayerList().getPlayer(botUuid);
			BotHandshake handshake = READY_BOTS.get(botUuid);
			if (bot == null || handshake == null
					|| (!handshake.volunteerRenderer() && !RendererBotPresenceSystem.isRendererBot(bot))) {
				continue;
			}
			if (isLevelActivelyRenderedByBot(server, botUuid, level.dimension())) {
				recipients.add(bot);
			}
		}
		return recipients;
	}

	public static boolean isCameraPlayerLoaded(ServerLevel level, BlockPos cameraPos) {
		if (level == null || cameraPos == null) {
			return false;
		}
		if (!level.hasChunkAt(cameraPos)) {
			return false;
		}
		return level.getBlockState(cameraPos).is(ModBlocks.CAMERA);
	}

	public static ChunkTrackingView createVirtualChunkTrackingView(ServerPlayer bot) {
		if (bot == null) {
			return ChunkTrackingView.EMPTY;
		}
		if (!RendererBotPresenceSystem.isRendererBot(bot)) {
			ChunkPos realCenter = bot.chunkPosition();
			return ChunkTrackingView.of(realCenter, resolveViewDistance(bot));
		}
		int viewDistance = resolveShadowViewDistance(bot);
		ChunkTrackingView positionedView = createPositionedVirtualChunkTrackingView(bot, viewDistance);
		if (positionedView != null) {
			return positionedView;
		}
		LongSet virtualChunks = collectVirtualTrackedChunks(bot, viewDistance);
		return virtualChunks.isEmpty() ? ChunkTrackingView.EMPTY : new VirtualChunkTrackingView(virtualChunks);
	}

	public static boolean isEntityWithinAnyVirtualTrackingRange(ServerPlayer viewer, Entity entity, double horizontalRangeBlocks) {
		if (viewer == null
				|| entity == null
				|| horizontalRangeBlocks <= 0.0D
				|| !RendererBotPresenceSystem.isRendererBot(viewer)) {
			return false;
		}
		if (viewer.level() != entity.level()) {
			return false;
		}
		ServerLevel viewerLevel = viewer.level();
		MinecraftServer server = viewerLevel != null ? viewerLevel.getServer() : null;
		if (server == null) {
			return false;
		}
		return isEntityWithinAnyTrackingTarget(server, viewer.getUUID(), entity, horizontalRangeBlocks * horizontalRangeBlocks);
	}

	public static boolean shouldReceiveNearbyWebcam(ServerPlayer viewer, Entity source, double horizontalRangeBlocks) {
		if (viewer == null
				|| source == null
				|| horizontalRangeBlocks <= 0.0D
				|| !RendererBotPresenceSystem.isRendererBot(viewer)
				|| !(source.level() instanceof ServerLevel sourceLevel)) {
			return false;
		}
		MinecraftServer server = sourceLevel.getServer();
		if (server == null) {
			return false;
		}
		return isEntityWithinAnyTrackingTarget(server, viewer.getUUID(), source, horizontalRangeBlocks * horizontalRangeBlocks);
	}

	private static void applyTimeout(UUID requestId, PendingCapture capture, long timeoutMillis) {
		capture.previewFuture()
				.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
				.exceptionally(throwable -> {
					failPending(requestId, capture, throwable);
					return null;
				});
		capture.fullFuture()
				.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
				.exceptionally(throwable -> {
					failPending(requestId, capture, throwable);
					return null;
				});
	}

	private static void failPending(UUID requestId, PendingCapture capture, Throwable throwable) {
		PENDING_CAPTURES.remove(requestId);
		if (!capture.previewFuture().isDone()) {
			capture.previewFuture().completeExceptionally(throwable);
		}
		if (!capture.fullFuture().isDone()) {
			capture.fullFuture().completeExceptionally(throwable);
		}
		MinecraftServer server = capture.server();
		if (server != null) {
			server.execute(() -> releaseBotCameraIfNeeded(server, capture.botUuid(), capture.resetCameraOnFinish()));
		}
	}

	private static short[] decodePcmFrame(byte[] pcm, int expectedSamples) {
		if (pcm == null || expectedSamples <= 0) {
			return null;
		}
		short[] samples = new short[expectedSamples];
		int sampleCount = Math.min(expectedSamples, pcm.length / 2);
		for (int index = 0; index < sampleCount; index++) {
			int byteIndex = index * 2;
			int low = pcm[byteIndex] & 0xFF;
			int high = pcm[byteIndex + 1];
			samples[index] = (short) (low | (high << 8));
		}
		return samples;
	}

	private static boolean isLevelActivelyRenderedByBot(MinecraftServer server, UUID botUuid, ResourceKey<Level> dimension) {
		if (server == null || botUuid == null || dimension == null) {
			return false;
		}
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream != null
					&& botUuid.equals(stream.botUuid())
					&& dimension.equals(stream.spec().dimension())) {
				return true;
			}
		}
		for (ActiveAudioCapture capture : ACTIVE_AUDIO_CAPTURES.values()) {
			if (capture != null
					&& botUuid.equals(capture.botUuid())
					&& dimension.equals(capture.spec().dimension())) {
				return true;
			}
		}
		for (PendingCapture capture : PENDING_CAPTURES.values()) {
			if (capture != null
					&& botUuid.equals(capture.botUuid())
					&& !capture.isDone()
					&& dimension.equals(capture.dimension())) {
				return true;
			}
		}
		for (PendingVideoRecording recording : PENDING_VIDEO_RECORDINGS.values()) {
			if (recording != null
					&& botUuid.equals(recording.botUuid())
					&& !recording.completionFuture().isDone()
					&& dimension.equals(recording.dimension())) {
				return true;
			}
		}
		return false;
	}

	private static void cleanupIfFinished(UUID requestId, PendingCapture capture) {
		if (capture.previewFuture().isDone() && capture.fullFuture().isDone()) {
			PENDING_CAPTURES.remove(requestId);
			releaseBotCameraIfNeeded(capture.server(), capture.botUuid(), capture.resetCameraOnFinish());
		}
	}

	private static void failVideoRecordingsForBot(UUID botUuid, String message) {
		if (botUuid == null) {
			return;
		}
		IllegalStateException failure = new IllegalStateException(message);
		for (Map.Entry<UUID, PendingVideoRecording> entry : PENDING_VIDEO_RECORDINGS.entrySet()) {
			PendingVideoRecording recording = entry.getValue();
			if (recording == null || !botUuid.equals(recording.botUuid())) {
				continue;
			}
			if (PENDING_VIDEO_RECORDINGS.remove(entry.getKey(), recording)) {
				recording.completionFuture().completeExceptionally(failure);
				releaseBotCameraIfNeeded(recording.server(), recording.botUuid(), recording.resetCameraOnFinish());
			}
		}
	}

	private static void failLiveStreamsForBot(UUID botUuid, String message) {
		if (botUuid == null) {
			return;
		}
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || !botUuid.equals(stream.botUuid())) {
				continue;
			}
			stopLiveStreamInternal(stream, message, true);
		}
	}

	private static void failAudioCapturesForBot(UUID botUuid, String message) {
		if (botUuid == null) {
			return;
		}
		for (ActiveAudioCapture capture : ACTIVE_AUDIO_CAPTURES.values()) {
			if (capture == null || !botUuid.equals(capture.botUuid())) {
				continue;
			}
			stopAudioCaptureInternal(capture, message, true);
		}
	}

	private static void stopLiveStreamForBot(UUID botUuid, String message, boolean notifyFailure) {
		if (botUuid == null) {
			return;
		}
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || !botUuid.equals(stream.botUuid())) {
				continue;
			}
			stopLiveStreamInternal(stream, message, notifyFailure);
		}
	}

	private static void stopLiveStreamInternal(ActiveLiveStream stream, String message, boolean notifyFailure) {
		if (stream == null) {
			return;
		}
		ACTIVE_LIVE_STREAMS.remove(stream.streamId(), stream);
		LIVE_STREAMS_BY_OWNER.remove(stream.ownerKey(), stream.streamId());
		stream.clearPendingFrames();
		ServerPlayer bot = stream.server().getPlayerList().getPlayer(stream.botUuid());
		if (bot != null && ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotLiveStreamStopS2CPayload.TYPE)) {
			ServerPlayNetworking.send(bot, new RendererBotPayloads.RendererBotLiveStreamStopS2CPayload(stream.streamId()));
		}
		releaseBotCameraIfNeeded(stream.server(), stream.botUuid(), true);
		if (notifyFailure) {
			stream.onFailure().accept(message);
		}
	}

	private static void stopAudioCaptureInternal(ActiveAudioCapture capture, String message, boolean notifyFailure) {
		if (capture == null) {
			return;
		}
		ACTIVE_AUDIO_CAPTURES.remove(capture.audioId(), capture);
		AUDIO_CAPTURES_BY_OWNER.remove(capture.ownerKey(), capture.audioId());
		ServerPlayer bot = capture.server().getPlayerList().getPlayer(capture.botUuid());
		if (bot != null && ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotAudioCaptureStopS2CPayload.TYPE)) {
			ServerPlayNetworking.send(bot, new RendererBotPayloads.RendererBotAudioCaptureStopS2CPayload(capture.audioId()));
		}
		if (notifyFailure) {
			capture.onFailure().accept(message);
		}
	}

	private static void failCapturesForBot(UUID botUuid, String message) {
		if (botUuid == null) {
			return;
		}
		IllegalStateException failure = new IllegalStateException(message);
		for (Map.Entry<UUID, PendingCapture> entry : PENDING_CAPTURES.entrySet()) {
			PendingCapture capture = entry.getValue();
			if (capture == null || !botUuid.equals(capture.botUuid())) {
				continue;
			}
			if (PENDING_CAPTURES.remove(entry.getKey(), capture)) {
				capture.previewFuture().completeExceptionally(failure);
				capture.fullFuture().completeExceptionally(failure);
			}
		}
	}

	private static void failMapTileCapturesForBot(UUID botUuid, String message) {
		if (botUuid == null) {
			return;
		}
		IllegalStateException failure = new IllegalStateException(message);
		for (Map.Entry<UUID, PendingMapTileCapture> entry : PENDING_MAP_TILE_CAPTURES.entrySet()) {
			PendingMapTileCapture capture = entry.getValue();
			if (capture == null || !botUuid.equals(capture.botUuid())) {
				continue;
			}
			if (PENDING_MAP_TILE_CAPTURES.remove(entry.getKey(), capture)) {
				capture.pixelsFuture().completeExceptionally(failure);
			}
		}
	}

	private static void failMapTileCapturesExceptBot(UUID selectedBotUuid, String message) {
		IllegalStateException failure = new IllegalStateException(message == null || message.isBlank()
				? "Клиент камеры для тайла карты недоступен"
				: message);
		for (Map.Entry<UUID, PendingMapTileCapture> entry : PENDING_MAP_TILE_CAPTURES.entrySet()) {
			PendingMapTileCapture capture = entry.getValue();
			if (capture == null || (selectedBotUuid != null && selectedBotUuid.equals(capture.botUuid()))) {
				continue;
			}
			if (PENDING_MAP_TILE_CAPTURES.remove(entry.getKey(), capture)) {
				capture.pixelsFuture().completeExceptionally(failure);
			}
		}
	}

	private static void failItemIconCapturesForBot(UUID botUuid, String message) {
		if (botUuid == null) {
			return;
		}
		IllegalStateException failure = new IllegalStateException(message);
		for (Map.Entry<UUID, PendingItemIconCapture> entry : PENDING_ITEM_ICON_CAPTURES.entrySet()) {
			PendingItemIconCapture capture = entry.getValue();
			if (capture == null || !botUuid.equals(capture.botUuid())) {
				continue;
			}
			if (PENDING_ITEM_ICON_CAPTURES.remove(entry.getKey(), capture)) {
				capture.pixelsFuture().completeExceptionally(failure);
			}
		}
	}

	private static ServerPlayer selectBot(MinecraftServer server) {
		if (server == null || server.getPlayerList() == null) {
			return null;
		}
		// A player has to opt in locally and the server owner may disable this
		// admission path in lg2.json. Volunteers are preferred, leaving the hidden
		// renderer client as a transparent fallback for the Contabo VPS.
		if (Lg2Config.get().cameraRendererAllowPlayerVolunteers) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				BotHandshake handshake = READY_BOTS.get(player.getUUID());
				if (handshake == null || !handshake.volunteerRenderer()
						|| !ServerPlayNetworking.canSend(player, RendererBotPayloads.RendererBotCaptureRequestS2CPayload.TYPE)) {
					continue;
				}
				return player;
			}
		}
		String configuredName = Lg2Config.get().cameraRendererBotPlayerName;
		if (configuredName == null || configuredName.isBlank()) {
			return null;
		}

		String trimmedName = configuredName.trim();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.getScoreboardName().equalsIgnoreCase(trimmedName)) {
				continue;
			}
			if (!READY_BOTS.containsKey(player.getUUID())) {
				continue;
			}
			if (!ServerPlayNetworking.canSend(player, RendererBotPayloads.RendererBotCaptureRequestS2CPayload.TYPE)) {
				continue;
			}
			return player;
		}

		return null;
	}

	private static void prepareBotForStaticView(
			ServerPlayer bot,
			ServerLevel level,
			double x,
			double y,
			double z,
			float yaw,
			float pitch
	) {
		if (bot == null || level == null) {
			return;
		}
		if (bot.getCamera() != bot) {
			bot.setCamera(bot);
		}
		level.getChunkAt(net.minecraft.core.BlockPos.containing(x, y, z));
		bot.teleportTo(
				level,
				x,
				y,
				z,
				ABSOLUTE_TELEPORT,
				yaw,
				pitch,
				false
		);
		bot.fallDistance = 0.0F;
	}

	private static void anchorBotIfNeeded(
			ServerPlayer bot,
			ServerLevel level,
			double x,
			double y,
			double z,
			float yaw,
			float pitch
	) {
		if (bot == null || level == null || botHasActiveJobs(bot.getUUID())) {
			return;
		}
		prepareBotForStaticView(bot, level, x, y, z, yaw, pitch);
	}

	private static void prepareBotToFollowEntity(
			ServerPlayer bot,
			ServerLevel level,
			double x,
			double y,
			double z,
			float yaw,
			float pitch,
			Entity target
	) {
		prepareBotForStaticView(bot, level, x, y, z, yaw, pitch);
		if (bot != null && target != null && target.level() == level) {
			bot.setCamera(target);
		}
	}

	private static void releaseBotCameraIfNeeded(MinecraftServer server, UUID botUuid, boolean resetCameraOnFinish) {
		if (!resetCameraOnFinish || server == null || botUuid == null) {
			return;
		}
		if (botHasActiveJobs(botUuid)) {
			return;
		}
		ServerPlayer bot = server.getPlayerList().getPlayer(botUuid);
		if (bot != null && bot.getCamera() != bot) {
			bot.setCamera(bot);
		}
	}

	private static void refreshVirtualEntityTracking(ServerPlayer bot) {
		if (bot == null || !(bot.level() instanceof ServerLevel level)) {
			return;
		}
		level.getChunkSource().move(bot);
	}

	private static void tickVirtualCameraState(MinecraftServer server) {
		if (server == null) {
			return;
		}
		syncCameraHotbarWarmupStreams(server);
		cleanupOrphanedLiveStreams(server);
		cleanupOrphanedAudioCaptures(server);
		syncLiveStreamPoseUpdates(server);
		if (!hasActiveShadowSyncWork(server)) {
			return;
		}
		syncShadowWorlds(server);
	}

	private static void syncLiveStreamPoseUpdates(MinecraftServer server) {
		if (server == null || ACTIVE_LIVE_STREAMS.isEmpty()) {
			return;
		}
		ServerPlayer bot = selectBot(server);
		if (bot == null
				|| !READY_BOTS.containsKey(bot.getUUID())
				|| !ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotLiveStreamPoseS2CPayload.TYPE)) {
			return;
		}
		UUID botUuid = bot.getUUID();
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || !botUuid.equals(stream.botUuid())) {
				continue;
			}
			LiveStreamSpec spec = stream.spec();
			if (spec == null || spec.followEntityUuid() == null) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(
					server,
					spec.dimension(),
					spec.expectedX(),
					spec.expectedY(),
					spec.expectedZ(),
					spec.expectedYaw(),
					spec.expectedPitch(),
					spec.followEntityUuid()
			);
			if (target == null || target.followTarget() == null) {
				continue;
			}
			boolean directCameraAnchor = DroneSystem.isDroneCameraAnchor(target.followTarget())
					|| CameraRelocationSystem.isMobileCameraAnchor(target.followTarget());
			Vec3 cameraPosition = directCameraAnchor
					? target.followTarget().position()
					: target.followTarget().getEyePosition(1.0F);
			float cameraBankRadians = DroneSystem.isDroneCameraAnchor(target.followTarget())
					? droneCameraBankRadians(target.followTarget())
					: 0.0F;
			ServerPlayNetworking.send(
					bot,
					new RendererBotPayloads.RendererBotLiveStreamPoseS2CPayload(
							stream.streamId(),
							cameraPosition.x,
							cameraPosition.y,
						cameraPosition.z,
						target.yaw(),
						target.pitch(),
						cameraBankRadians
					)
			);
		}
	}

	private static float droneCameraBankRadians(Entity cameraAnchor) {
		if (cameraAnchor == null) {
			return 0.0F;
		}
		Vec3 look = cameraAnchor.getLookAngle();
		Vec3 velocity = cameraAnchor.getDeltaMovement();
		double lookHorizontal = look.x * look.x + look.z * look.z;
		double velocityHorizontal = velocity.x * velocity.x + velocity.z * velocity.z;
		if (lookHorizontal <= 6.25E-6D || velocityHorizontal <= 6.25E-6D) {
			return 0.0F;
		}
		double cosine = (velocity.x * look.x + velocity.z * look.z) / Math.sqrt(lookHorizontal * velocityHorizontal);
		cosine = Mth.clamp(cosine, -1.0D, 1.0D);
		double sign = -Math.signum(velocity.x * look.z - velocity.z * look.x);
		double degrees = Mth.clamp(Math.atan(Math.sqrt(velocityHorizontal) * Math.acos(Math.abs(cosine)) * 1.25D) * sign * Mth.RAD_TO_DEG, -32.0D, 32.0D);
		return (float) (degrees * Mth.DEG_TO_RAD);
	}

	private static boolean hasActiveShadowSyncWork(MinecraftServer server) {
		return !ACTIVE_LIVE_STREAMS.isEmpty()
				|| !ACTIVE_AUDIO_CAPTURES.isEmpty()
				|| !PENDING_CAPTURES.isEmpty()
				|| !PENDING_MAP_TILE_CAPTURES.isEmpty()
				|| !PENDING_VIDEO_RECORDINGS.isEmpty()
				|| !ACTIVE_SHADOW_SYNC_STATES.isEmpty()
				|| !ACTIVE_CAMERA_CHUNK_TICKETS.isEmpty()
				|| !DIRTY_SHADOW_CHUNKS.isEmpty()
				|| hasCameraHotbarWarmupTargets(server);
	}

	private static boolean hasCameraHotbarWarmupTargets(MinecraftServer server) {
		if (server == null || server.getPlayerList() == null) {
			return false;
		}
		ServerPlayer bot = selectBot(server);
		if (bot != null && hasActiveVideoRecording(bot.getUUID())) {
			return false;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player == null
					|| !player.isAlive()
					|| DroneSystem.isCameraBlockedByDroneControl(player)
					|| !(player.level() instanceof ServerLevel)
					|| !playerHasCameraInActiveSlot(player)) {
				continue;
			}
			return true;
		}
		return false;
	}

	private static boolean playerHasCameraInActiveSlot(ServerPlayer player) {
		if (player == null) {
			return false;
		}
		int selected = player.getInventory().getSelectedSlot();
		if (selected < 0 || selected >= HOTBAR_SIZE) {
			return false;
		}
		return player.getInventory().getItem(selected).is(ModItems.CAMERA);
	}

	private static String cameraHotbarWarmupOwnerKey(UUID playerUuid) {
		return playerUuid == null ? null : "lg2:camera_hotbar_warmup:" + playerUuid;
	}

	public static void stopCameraHotbarWarmupForPlayer(UUID playerUuid) {
		String ownerKey = cameraHotbarWarmupOwnerKey(playerUuid);
		if (ownerKey == null) {
			return;
		}
		stopLiveStream(ownerKey);
	}

	private static void syncCameraHotbarWarmupStreams(MinecraftServer server) {
		if (server == null || server.getPlayerList() == null) {
			return;
		}
		// A recording needs the renderer client's single off-screen target and a
		// stable shadow world.  The invisible 1x1 hotbar prewarm stream otherwise
		// keeps being restarted while the client deliberately services the video.
		ServerPlayer bot = selectBot(server);
		if (bot != null && hasActiveVideoRecording(bot.getUUID())) {
			for (String ownerKey : new ArrayList<>(LIVE_STREAMS_BY_OWNER.keySet())) {
				if (ownerKey != null && ownerKey.startsWith("lg2:camera_hotbar_warmup:")) {
					stopLiveStream(ownerKey);
				}
			}
			return;
		}
		Set<String> desiredOwnerKeys = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player == null) {
				continue;
			}
			String ownerKey = cameraHotbarWarmupOwnerKey(player.getUUID());
			if (ownerKey == null) {
				continue;
			}
			if (!player.isAlive()
					|| DroneSystem.isCameraBlockedByDroneControl(player)
					|| !(player.level() instanceof ServerLevel playerLevel)
					|| !playerHasCameraInActiveSlot(player)) {
				stopLiveStream(ownerKey);
				continue;
			}
			desiredOwnerKeys.add(ownerKey);
			ensureLiveStream(
					ownerKey,
					playerLevel,
					null,
					player.getX(),
					player.getY(),
					player.getZ(),
					player.getYRot(),
					player.getXRot(),
					player.getUUID(),
					Set.of(),
					true,
					CAMERA_HOTBAR_WARMUP_SIZE,
					CAMERA_HOTBAR_WARMUP_SIZE,
					CAMERA_HOTBAR_WARMUP_FOV_DEGREES,
					CAMERA_HOTBAR_WARMUP_FPS,
					pixels -> {
					},
					error -> {
					}
			);
		}

		for (String ownerKey : new ArrayList<>(LIVE_STREAMS_BY_OWNER.keySet())) {
			if (ownerKey == null || !ownerKey.startsWith("lg2:camera_hotbar_warmup:") || desiredOwnerKeys.contains(ownerKey)) {
				continue;
			}
			stopLiveStream(ownerKey);
		}
	}

	private static void cleanupOrphanedLiveStreams(MinecraftServer server) {
		if (server == null || ACTIVE_LIVE_STREAMS.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		for (ActiveLiveStream stream : new ArrayList<>(ACTIVE_LIVE_STREAMS.values())) {
			if (stream == null) {
				continue;
			}
			UUID expectedStreamId = LIVE_STREAMS_BY_OWNER.get(stream.ownerKey());
			boolean ownerMismatch = !Objects.equals(expectedStreamId, stream.streamId());
			if (ownerMismatch) {
				stopLiveStreamInternal(stream, "Renderer bot live stream orphaned", false);
				continue;
			}
			boolean botUnavailable = !READY_BOTS.containsKey(stream.botUuid())
					|| server.getPlayerList() == null
					|| server.getPlayerList().getPlayer(stream.botUuid()) == null;
			if (botUnavailable) {
				stopLiveStreamInternal(stream, "Renderer bot live stream target is unavailable", true);
				continue;
			}
			if (!hasActiveVideoRecording(stream.botUuid())
					&& now - stream.lastActivityAtMillis() > LIVE_STREAM_ORPHAN_CLEANUP_MS) {
				stopLiveStreamInternal(stream, "Renderer bot live stream timed out", true);
			}
		}
	}

	private static void cleanupOrphanedAudioCaptures(MinecraftServer server) {
		if (server == null || ACTIVE_AUDIO_CAPTURES.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		for (ActiveAudioCapture capture : new ArrayList<>(ACTIVE_AUDIO_CAPTURES.values())) {
			if (capture == null) {
				continue;
			}
			UUID expectedAudioId = AUDIO_CAPTURES_BY_OWNER.get(capture.ownerKey());
			boolean ownerMismatch = !Objects.equals(expectedAudioId, capture.audioId());
			if (ownerMismatch) {
				stopAudioCaptureInternal(capture, "Renderer bot audio capture orphaned", false);
				continue;
			}
			boolean botUnavailable = !READY_BOTS.containsKey(capture.botUuid())
					|| server.getPlayerList() == null
					|| server.getPlayerList().getPlayer(capture.botUuid()) == null;
			if (botUnavailable) {
				stopAudioCaptureInternal(capture, "Renderer bot audio capture target is unavailable", true);
				continue;
			}
			if (!hasActiveVideoRecording(capture.botUuid())
					&& now - capture.lastActivityAtMillis() > AUDIO_CAPTURE_ORPHAN_CLEANUP_MS) {
				stopAudioCaptureInternal(capture, "Renderer bot audio capture timed out", true);
			}
		}
	}

	private static UUID resolveRenderSessionId(
			ResourceKey<Level> dimension,
			BlockPos cameraPos,
			double x,
			double z,
			UUID followEntityUuid
	) {
		if (dimension == null) {
			return UUID.randomUUID();
		}
		if (followEntityUuid != null) {
			return UUID.nameUUIDFromBytes(
					("lg2:render-session:follow:" + dimension.identifier() + ":" + followEntityUuid).getBytes(StandardCharsets.UTF_8)
			);
		}
		int chunkX = SectionPos.blockToSectionCoord(cameraPos != null ? cameraPos.getX() : Mth.floor(x));
		int chunkZ = SectionPos.blockToSectionCoord(cameraPos != null ? cameraPos.getZ() : Mth.floor(z));
		int clusterX = Math.floorDiv(chunkX, STATIC_CAMERA_SESSION_CLUSTER_CHUNKS);
		int clusterZ = Math.floorDiv(chunkZ, STATIC_CAMERA_SESSION_CLUSTER_CHUNKS);
		return UUID.nameUUIDFromBytes(
				("lg2:render-session:static:" + dimension.identifier() + ":" + clusterX + ":" + clusterZ).getBytes(StandardCharsets.UTF_8)
		);
	}

	private static UUID resolveMapRenderSessionId(ResourceKey<Level> dimension) {
		if (dimension == null) {
			return UUID.randomUUID();
		}
		return UUID.nameUUIDFromBytes(
				("lg2:render-session:yandex-map:" + dimension.identifier()).getBytes(StandardCharsets.UTF_8)
		);
	}

	private static int resolveViewDistance(ServerPlayer bot) {
		MinecraftServer server = bot != null && bot.level() != null ? bot.level().getServer() : null;
		return Mth.clamp(
				server != null && server.getPlayerList() != null ? server.getPlayerList().getViewDistance() : 2,
				2,
				32
		);
	}

	private static int resolveShadowViewDistance(ServerPlayer bot) {
		// Shadow streams must have the same practical loading radius as a real
		// player. The former +6 margin made a 10-chunk server try to prepare a
		// 16-chunk camera window on every new drone position; most of that window
		// was still waiting for FULL chunks while the frame was already rendered.
		return resolveViewDistance(bot);
	}

	public static int resolveCameraShadowViewDistance(ServerPlayer viewer) {
		return resolveShadowViewDistance(viewer);
	}

	public static int resolveCameraShadowViewDistance(MinecraftServer server) {
		ServerPlayer bot = selectBot(server);
		return bot == null ? 2 : resolveShadowViewDistance(bot);
	}

	private static int mapTileViewDistance(PendingMapTileCapture capture) {
		if (capture == null) {
			return 2;
		}
		return mapTileViewDistance(capture.tileSize(), capture.blocksPerPixel());
	}

	private static int mapTileViewDistance(int tileSize, double blocksPerPixel) {
		double halfBlocks = Math.max(1, tileSize) * Math.max(1.0D / 16.0D, blocksPerPixel) * 0.5D;
		int radius = (int) Math.ceil(halfBlocks / 16.0D) + 2;
		return Mth.clamp(radius, 2, 32);
	}

	private static List<ChunkPos> readOnlyMapShadowChunks(
			double centerX,
			double centerZ,
			int tileSize,
			double blocksPerPixel,
			List<ChunkPos> sourceChunks
	) {
		LongSet chunkLongs = computeOmnidirectionalCameraChunks(
				centerX,
				centerZ,
				mapTileViewDistance(tileSize, blocksPerPixel)
		);
		if (sourceChunks != null) {
			for (ChunkPos source : sourceChunks) {
				if (source != null) {
					chunkLongs.add(source.toLong());
				}
			}
		}
		LongArrayList ordered = new LongArrayList(chunkLongs);
		int centerChunkX = SectionPos.blockToSectionCoord(Mth.floor(centerX));
		int centerChunkZ = SectionPos.blockToSectionCoord(Mth.floor(centerZ));
		ordered.sort((left, right) -> compareTrackedChunks(left, right, centerChunkX, centerChunkZ));
		List<ChunkPos> chunks = new ArrayList<>(ordered.size());
		for (long chunkLong : ordered) {
			chunks.add(new ChunkPos(chunkLong));
		}
		return List.copyOf(chunks);
	}

	private static double mapTileCameraY(ServerLevel level) {
		if (level == null) {
			return 256.0D;
		}
		return Math.max(level.getMinY() + 1.0D, level.getMaxY() - 0.5D);
	}

	public static ChunkTrackingView createCameraChunkTrackingView(
			double x,
			double z,
			float yaw,
			int viewDistance,
			boolean omnidirectionalChunkLoading,
			boolean staticCameraChunkBuffer
	) {
		int clampedViewDistance = Mth.clamp(viewDistance, 2, 32);
		if (omnidirectionalChunkLoading) {
			return ChunkTrackingView.of(chunkPosAt(x, z), clampedViewDistance);
		}
		LongSet chunks = collectCameraViewChunks(x, z, yaw, viewDistance, omnidirectionalChunkLoading, staticCameraChunkBuffer);
		return chunks.isEmpty() ? ChunkTrackingView.EMPTY : new VirtualChunkTrackingView(chunks);
	}

	public static LongSet collectCameraViewChunks(
			double x,
			double z,
			float yaw,
			int viewDistance,
			boolean omnidirectionalChunkLoading,
			boolean staticCameraChunkBuffer
	) {
		return computeVirtualCameraChunks(
				x,
				z,
				yaw,
				Mth.clamp(viewDistance, 2, 32),
				omnidirectionalChunkLoading,
				staticCameraChunkBuffer
		);
	}

	private static boolean canBotRenderLevel(ServerPlayer bot, ServerLevel level) {
		return bot != null && level != null && READY_BOTS.containsKey(bot.getUUID());
	}

	private static ChunkTrackingView createPositionedVirtualChunkTrackingView(ServerPlayer bot, int viewDistance) {
		if (bot == null || !(bot.level() instanceof ServerLevel botLevel)) {
			return null;
		}
		MinecraftServer server = botLevel.getServer();
		if (server == null) {
			return null;
		}
		UUID botUuid = bot.getUUID();
		ChunkPos center = null;

		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || !botUuid.equals(stream.botUuid())) {
				continue;
			}
			LiveStreamSpec spec = stream.spec();
			if (spec == null || spec.dimension() == null || !botLevel.dimension().equals(spec.dimension())) {
				continue;
			}
			if (spec.followEntityUuid() == null && spec.cameraPos() != null && !isCameraPlayerLoaded(botLevel, spec.cameraPos())) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(
					server,
					spec.dimension(),
					spec.expectedX(),
					spec.expectedY(),
					spec.expectedZ(),
					spec.expectedYaw(),
					spec.expectedPitch(),
					spec.followEntityUuid()
			);
			// Follow streams (drones and rocket cameras) still need their moving
			// chunks sent to the renderer bot.  Returning null here left the bot at
			// its old view centre; the moving anchor was never tracked client-side
			// and the stream consequently timed out without frames.
			center = mergePositionedVirtualCenter(center, botLevel, target);
			if (center == null && target != null) {
				return null;
			}
		}

		for (ActiveAudioCapture capture : ACTIVE_AUDIO_CAPTURES.values()) {
			if (capture == null || !botUuid.equals(capture.botUuid())) {
				continue;
			}
			AudioCaptureSpec spec = capture.spec();
			if (spec == null || spec.dimension() == null || !botLevel.dimension().equals(spec.dimension())) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(
					server,
					spec.dimension(),
					spec.x(),
					spec.y(),
					spec.z(),
					0.0F,
					0.0F,
					null
			);
			center = mergePositionedVirtualCenter(center, botLevel, target);
			if (center == null && target != null) {
				return null;
			}
		}

		for (PendingCapture capture : PENDING_CAPTURES.values()) {
			if (capture == null || !botUuid.equals(capture.botUuid()) || capture.isDone()) {
				continue;
			}
			if (capture.followEntityUuid() != null) {
				return null;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, capture.dimension(), capture.x(), capture.y(), capture.z(), capture.yaw(), capture.pitch(), capture.followEntityUuid());
			center = mergePositionedVirtualCenter(center, botLevel, target);
			if (center == null && target != null) {
				return null;
			}
		}

		PendingVideoRecording recording = selectVideoRecordingForRenderer(botUuid);
		if (recording != null) {
			if (recording.followEntityUuid() != null) {
				return null;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, recording.dimension(), recording.x(), recording.y(), recording.z(), recording.yaw(), recording.pitch(), recording.followEntityUuid());
			center = mergePositionedVirtualCenter(center, botLevel, target);
			if (center == null && target != null) {
				return null;
			}
		}

		return center == null ? null : ChunkTrackingView.of(center, Mth.clamp(viewDistance, 2, 32));
	}

	private static ChunkPos mergePositionedVirtualCenter(ChunkPos current, ServerLevel expectedLevel, ScheduledServiceTarget target) {
		if (target == null || target.level() != expectedLevel) {
			return current;
		}
		ChunkPos candidate = chunkPosAt(target.x(), target.z());
		if (current == null || current.equals(candidate)) {
			return candidate;
		}
		return null;
	}

	private static ChunkPos chunkPosAt(double x, double z) {
		return new ChunkPos(
				SectionPos.blockToSectionCoord(Mth.floor(x)),
				SectionPos.blockToSectionCoord(Mth.floor(z))
		);
	}

	private static LongSet collectVirtualTrackedChunks(ServerPlayer bot, int viewDistance) {
		LongOpenHashSet chunks = new LongOpenHashSet();
		if (bot == null || !(bot.level() instanceof ServerLevel botLevel)) {
			return chunks;
		}
		MinecraftServer server = botLevel.getServer();
		if (server == null) {
			return chunks;
		}
		UUID botUuid = bot.getUUID();

		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || !botUuid.equals(stream.botUuid())) {
				continue;
			}
			LiveStreamSpec spec = stream.spec();
			if (spec == null || spec.dimension() == null || !botLevel.dimension().equals(spec.dimension())) {
				continue;
			}
			if (spec.cameraPos() != null && !isCameraPlayerLoaded(botLevel, spec.cameraPos())) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(
					server,
					spec.dimension(),
					spec.expectedX(),
					spec.expectedY(),
					spec.expectedZ(),
					spec.expectedYaw(),
					spec.expectedPitch(),
					spec.followEntityUuid()
			);
			appendVirtualTargetChunks(
					chunks,
					botLevel,
					target,
					viewDistance,
					spec.omnidirectionalChunkLoading(),
					spec.cameraPos() != null && spec.followEntityUuid() == null
			);
		}
		for (ActiveAudioCapture capture : ACTIVE_AUDIO_CAPTURES.values()) {
			if (capture == null || !botUuid.equals(capture.botUuid())) {
				continue;
			}
			AudioCaptureSpec spec = capture.spec();
			if (spec == null || spec.dimension() == null || !botLevel.dimension().equals(spec.dimension())) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, spec.dimension(), spec.x(), spec.y(), spec.z(), 0.0F, 0.0F, null);
			if (target == null || target.level() != botLevel) {
				continue;
			}
			appendVirtualTargetChunks(chunks, botLevel, target, viewDistance, true, false);
		}
		for (PendingCapture capture : PENDING_CAPTURES.values()) {
			if (capture == null || !botUuid.equals(capture.botUuid()) || capture.isDone()) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, capture.dimension(), capture.x(), capture.y(), capture.z(), capture.yaw(), capture.pitch(), capture.followEntityUuid());
			if (target == null || target.level() != botLevel) {
				continue;
			}
			appendVirtualTargetChunks(chunks, botLevel, target, viewDistance, false, false);
		}
		PendingVideoRecording recording = selectVideoRecordingForRenderer(botUuid);
		if (recording != null) {
			ScheduledServiceTarget target = resolveServiceTarget(server, recording.dimension(), recording.x(), recording.y(), recording.z(), recording.yaw(), recording.pitch(), recording.followEntityUuid());
			if (target == null || target.level() != botLevel) {
				return chunks;
			}
			// Hand-held recording follows a player. Like a normal player client it
			// keeps one circular chunk window while moving, rather than re-evaluating
			// a directional static-camera frustum every frame.
			appendVirtualTargetChunks(chunks, botLevel, target, viewDistance, true, false);
		}
		return chunks;
	}

	private static void appendVirtualTargetChunks(
			LongSet chunks,
			ServerLevel level,
			ScheduledServiceTarget target,
			int viewDistance,
			boolean omnidirectionalChunkLoading,
			boolean staticCameraChunkBuffer
	) {
		if (chunks == null || level == null || target == null || target.level() != level) {
			return;
		}
		appendVirtualCameraChunks(
				chunks,
				target.x(),
				target.z(),
				target.yaw(),
				viewDistance,
				omnidirectionalChunkLoading,
				staticCameraChunkBuffer
		);
	}

	private static void appendVirtualCameraChunks(
			LongSet target,
			double x,
			double z,
			float yaw,
			int viewDistance,
			boolean omnidirectionalChunkLoading,
			boolean staticCameraChunkBuffer
	) {
		if (target == null || viewDistance <= 0) {
			return;
		}
		LongSet chunks = computeVirtualCameraChunks(x, z, yaw, viewDistance, omnidirectionalChunkLoading, staticCameraChunkBuffer);
		LongIterator iterator = chunks.iterator();
		while (iterator.hasNext()) {
			target.add(iterator.nextLong());
		}
	}

	private static LongSet computeVirtualCameraChunks(
			double x,
			double z,
			float yaw,
			int viewDistance,
			boolean omnidirectionalChunkLoading,
			boolean staticCameraChunkBuffer
	) {
		if (omnidirectionalChunkLoading) {
			return computeOmnidirectionalCameraChunks(x, z, viewDistance);
		}
		if (staticCameraChunkBuffer) {
			return computeDirectionalCameraChunks(
					x,
					z,
					yaw,
					viewDistance,
					STATIC_CAMERA_FORWARD_HALF_FOV_DEGREES,
					STATIC_CAMERA_NEAR_OMNI_RADIUS_CHUNKS,
					STATIC_CAMERA_REAR_VIEW_CHUNKS,
					STATIC_CAMERA_SIDE_SAFETY_MARGIN_CHUNKS
			);
		}
		return computeDirectionalCameraChunks(
				x,
				z,
				yaw,
				viewDistance,
				SHADOW_FORWARD_HALF_FOV_DEGREES,
				SHADOW_NEAR_OMNI_RADIUS_CHUNKS,
				SHADOW_REAR_VIEW_CHUNKS,
				SHADOW_SIDE_SAFETY_MARGIN_CHUNKS
		);
	}

	private static LongSet computeDirectionalCameraChunks(
			double x,
			double z,
			float yaw,
			int viewDistance,
			double halfFovDegrees,
			double nearOmniRadiusChunks,
			int rearViewChunks,
			double sideSafetyMarginChunks
	) {
		LongOpenHashSet chunks = new LongOpenHashSet();
		int centerChunkX = SectionPos.blockToSectionCoord(Mth.floor(x));
		int centerChunkZ = SectionPos.blockToSectionCoord(Mth.floor(z));
		double yawRadians = Math.toRadians(yaw);
		double forwardX = -Math.sin(yawRadians);
		double forwardZ = Math.cos(yawRadians);
		double halfFovRadians = Math.toRadians(halfFovDegrees);
		double tangentLimit = Math.tan(halfFovRadians);
		for (int dx = -viewDistance; dx <= viewDistance; dx++) {
			for (int dz = -viewDistance; dz <= viewDistance; dz++) {
				int chunkX = centerChunkX + dx;
				int chunkZ = centerChunkZ + dz;
				if (!ChunkTrackingView.isInViewDistance(centerChunkX, centerChunkZ, viewDistance, chunkX, chunkZ)) {
					continue;
				}
				double chunkCenterX = chunkX * 16.0D + 8.0D;
				double chunkCenterZ = chunkZ * 16.0D + 8.0D;
				double deltaChunkX = (chunkCenterX - x) / 16.0D;
				double deltaChunkZ = (chunkCenterZ - z) / 16.0D;
				double horizontalDistanceChunks = Math.sqrt(deltaChunkX * deltaChunkX + deltaChunkZ * deltaChunkZ);
				if (horizontalDistanceChunks <= nearOmniRadiusChunks) {
					chunks.add(new ChunkPos(chunkX, chunkZ).toLong());
					continue;
				}
				double forwardDistance = deltaChunkX * forwardX + deltaChunkZ * forwardZ;
				if (forwardDistance < -rearViewChunks || forwardDistance > viewDistance + sideSafetyMarginChunks) {
					continue;
				}
				double sideDistance = Math.abs(deltaChunkX * forwardZ - deltaChunkZ * forwardX);
				double allowedSideDistance = forwardDistance <= 0.0D
						? nearOmniRadiusChunks + sideSafetyMarginChunks
						: forwardDistance * tangentLimit + sideSafetyMarginChunks;
				if (sideDistance > allowedSideDistance) {
					continue;
				}
				chunks.add(new ChunkPos(chunkX, chunkZ).toLong());
			}
		}
		return chunks;
	}

	private static LongSet computeOmnidirectionalCameraChunks(double x, double z, int viewDistance) {
		LongOpenHashSet chunks = new LongOpenHashSet();
		int centerChunkX = SectionPos.blockToSectionCoord(Mth.floor(x));
		int centerChunkZ = SectionPos.blockToSectionCoord(Mth.floor(z));
		for (int dx = -viewDistance; dx <= viewDistance; dx++) {
			for (int dz = -viewDistance; dz <= viewDistance; dz++) {
				int chunkX = centerChunkX + dx;
				int chunkZ = centerChunkZ + dz;
				if (!ChunkTrackingView.isInViewDistance(centerChunkX, centerChunkZ, viewDistance, chunkX, chunkZ)) {
					continue;
				}
				chunks.add(new ChunkPos(chunkX, chunkZ).toLong());
			}
		}
		return chunks;
	}

	private static boolean updateVirtualCameraChunkTickets(MinecraftServer server) {
		Map<CameraChunkTicketKey, Integer> desiredRefs = new HashMap<>();
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null) {
				continue;
			}
			LiveStreamSpec spec = stream.spec();
			if (spec == null || spec.dimension() == null) {
				continue;
			}
			ServerLevel level = server.getLevel(spec.dimension());
			if (level == null || (spec.cameraPos() != null && !isCameraPlayerLoaded(level, spec.cameraPos()))) {
				continue;
			}
			ServerPlayer bot = server.getPlayerList().getPlayer(stream.botUuid());
			if (bot == null || !READY_BOTS.containsKey(bot.getUUID())) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(
					server,
					spec.dimension(),
					spec.expectedX(),
					spec.expectedY(),
					spec.expectedZ(),
					spec.expectedYaw(),
					spec.expectedPitch(),
					spec.followEntityUuid()
			);
			if (target == null || target.level() == null) {
				continue;
			}
			int viewDistance = resolveShadowViewDistance(bot);
			addCameraChunkTicket(desiredRefs, target.level().dimension(), chunkPosAt(target.x(), target.z()), viewDistance);
		}

		if (Objects.equals(ACTIVE_CAMERA_CHUNK_TICKETS, desiredRefs)) {
			return false;
		}

		Set<CameraChunkTicketKey> keys = new LinkedHashSet<>();
		keys.addAll(ACTIVE_CAMERA_CHUNK_TICKETS.keySet());
		keys.addAll(desiredRefs.keySet());
		for (CameraChunkTicketKey key : keys) {
			int current = ACTIVE_CAMERA_CHUNK_TICKETS.getOrDefault(key, 0);
			int desired = desiredRefs.getOrDefault(key, 0);
			if (current <= 0 && desired > 0) {
				ServerLevel level = server.getLevel(key.dimension());
				if (level != null) {
					level.getChunkSource().addTicketWithRadius(CAMERA_CHUNK_TICKET_TYPE, new ChunkPos(key.chunkLong()), key.radius());
				}
			} else if (current > 0 && desired <= 0) {
				ServerLevel level = server.getLevel(key.dimension());
				if (level != null) {
					level.getChunkSource().removeTicketWithRadius(CAMERA_CHUNK_TICKET_TYPE, new ChunkPos(key.chunkLong()), key.radius());
				}
			}
		}
		ACTIVE_CAMERA_CHUNK_TICKETS.clear();
		ACTIVE_CAMERA_CHUNK_TICKETS.putAll(desiredRefs);
		return true;
	}

	private static void releaseAllVirtualCameraChunkTickets(MinecraftServer server) {
		if (server == null || ACTIVE_CAMERA_CHUNK_TICKETS.isEmpty()) {
			ACTIVE_CAMERA_CHUNK_TICKETS.clear();
			return;
		}
		for (CameraChunkTicketKey key : new ArrayList<>(ACTIVE_CAMERA_CHUNK_TICKETS.keySet())) {
			ServerLevel level = server.getLevel(key.dimension());
			if (level != null) {
				level.getChunkSource().removeTicketWithRadius(CAMERA_CHUNK_TICKET_TYPE, new ChunkPos(key.chunkLong()), key.radius());
			}
		}
		ACTIVE_CAMERA_CHUNK_TICKETS.clear();
	}

	public static void markShadowChunkDirty(ServerLevel level, ChunkPos pos) {
		if (level == null || pos == null) {
			return;
		}
		DIRTY_SHADOW_CHUNKS.add(new ChunkTicketKey(level.dimension(), pos.toLong()));
		MonitorYandexMapsClientTileRenderer.markChunkDirty(level, pos);
	}

	/**
	 * Mirrors one changed block into every shadow session that already owns its
	 * chunk.  A full {@code LevelChunkWithLight} packet here used to replace the
	 * whole client chunk, invalidate all of its section meshes and occasionally
	 * leave a live-camera frame with only the sky while those meshes rebuilt.
	 */
	public static void mirrorShadowBlockUpdate(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return;
		}
		mirrorTransientLevelPacket(level, pos, new ClientboundBlockUpdatePacket(level, pos));
	}

	/**
	 * Light propagation is incremental too.  Sending this packet preserves the
	 * already compiled terrain instead of reloading the whole chunk merely to
	 * refresh its light arrays.
	 */
	public static void mirrorShadowLightUpdate(ServerLevel level, ChunkPos pos) {
		if (level == null || pos == null) {
			return;
		}
		BlockPos chunkOrigin = pos.getWorldPosition();
		mirrorTransientLevelPacket(
				level,
				chunkOrigin,
				new ClientboundLightUpdatePacket(pos, level.getChunkSource().getLightEngine(), null, null)
		);
	}

	private static void syncShadowWorlds(MinecraftServer server) {
		if (server == null) {
			return;
		}
		ServerPlayer selectedBot = selectBot(server);
		// A capture has a shadow session on one client only. If configuration or
		// readiness selects another bot (or none), the current sync pass can never
		// send it. Fail it now so its map tile is retried instead of holding a
		// pending slot forever.
		failMapTileCapturesExceptBot(
				selectedBot != null ? selectedBot.getUUID() : null,
				selectedBot == null
						? "Клиент камеры для тайла карты недоступен"
						: "Активный клиент камеры сменился во время рендера тайла карты"
		);
		List<PendingMapTileCapture> activeMapTiles = selectedBot == null
				? List.of()
				: activeMapTileShadowTargets(selectedBot.getUUID());
		for (PendingMapTileCapture capture : activeMapTiles) {
			if (capture == null || !isPendingMapTileCapture(capture)) {
				continue;
			}
			ServerLevel level = server.getLevel(capture.dimension());
			if (level != null) {
				prepareReadOnlyMapTileChunks(capture, level, selectedBot);
			}
		}
		Map<ShadowSyncKey, ShadowDesiredState> desiredStates = collectDesiredShadowStates(server, activeMapTiles);
		Set<ChunkTicketKey> trackedChunks = collectTrackedShadowChunks(desiredStates.values());
		syncShadowChunkTickets(server, desiredStates.values());
		Set<ChunkTicketKey> consumedDirtyChunks = new HashSet<>();
		for (Map.Entry<ShadowSyncKey, ShadowDesiredState> entry : desiredStates.entrySet()) {
			ShadowSyncKey key = entry.getKey();
			ServerPlayer bot = server.getPlayerList().getPlayer(key.botUuid());
			if (bot == null || !READY_BOTS.containsKey(bot.getUUID())) {
				continue;
			}
			syncShadowState(server, bot, key, entry.getValue(), consumedDirtyChunks);
		}
		dispatchReadyVideoRecording(server, desiredStates);
		dispatchReadyPhotoCapture(server, desiredStates);
		// Send the render payload only after the selected target has a ticket and
		// its shadow chunks were pushed to the renderer bot in this tick.
		dispatchReadyMapTileRequests(server, activeMapTiles, desiredStates);

		Set<ShadowSyncKey> staleKeys = new LinkedHashSet<>(ACTIVE_SHADOW_SYNC_STATES.keySet());
		staleKeys.removeAll(desiredStates.keySet());
		for (ShadowSyncKey staleKey : staleKeys) {
			removeShadowSyncState(server, staleKey, true);
		}
		DIRTY_SHADOW_CHUNKS.removeAll(consumedDirtyChunks);
		if (trackedChunks.isEmpty()) {
			DIRTY_SHADOW_CHUNKS.clear();
			return;
		}
		DIRTY_SHADOW_CHUNKS.removeIf(key -> key == null || !trackedChunks.contains(key));
		// Add retry dirties after the normal cleanup so a lost client chunk is
		// guaranteed to be pushed again on the following tick.
		resyncStalledMapTileShadows(server, activeMapTiles);
	}

	private static void dispatchReadyMapTileRequests(
			MinecraftServer server,
			List<PendingMapTileCapture> activeMapTiles,
			Map<ShadowSyncKey, ShadowDesiredState> desiredStates
	) {
		if (server == null || activeMapTiles == null || activeMapTiles.isEmpty()) {
			return;
		}
		ServerPlayer bot = selectBot(server);
		if (bot == null) {
			return;
		}
		for (PendingMapTileCapture capture : activeMapTiles) {
			if (!isPendingMapTileCapture(capture)
					|| !bot.getUUID().equals(capture.botUuid())
					|| capture.clientRequestSent()
					|| desiredStates == null
					|| !desiredStates.containsKey(new ShadowSyncKey(bot.getUUID(), capture.renderSessionId()))) {
				continue;
			}
			ServerLevel level = server.getLevel(capture.dimension());
			if (level == null) {
				failMapTileCapture(capture.requestId(), capture, "Renderer bot map tile target is unavailable");
				continue;
			}
			if (!capture.hasReadOnlyChunkPayloads()) {
				if (capture.sourceReadTimedOut(System.currentTimeMillis())) {
					failMapTileCapture(capture.requestId(), capture, "Тайм-аут чтения сохранённых чанков для тайла карты");
				}
				continue;
			}
			if (!ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotShadowLevelInitS2CPayload.TYPE)
					|| !ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotShadowChunkDataS2CPayload.TYPE)
					|| !ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotMapTileRequestS2CPayload.TYPE)) {
				failMapTileCapture(capture.requestId(), capture, "Клиент камеры не поддерживает тайлы карты");
				continue;
			}
			try {
				ServerPlayNetworking.send(bot, mapTileRequestPayload(capture));
				capture.markClientRequestSent(System.currentTimeMillis());
				capture.armClientTimeout();
			} catch (RuntimeException exception) {
				failMapTileCapture(capture.requestId(), capture, "Не удалось отправить тайл карты клиенту камеры");
			}
		}
	}

	private static void resyncStalledMapTileShadows(MinecraftServer server, List<PendingMapTileCapture> activeMapTiles) {
		if (server == null || activeMapTiles == null || activeMapTiles.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		for (PendingMapTileCapture capture : activeMapTiles) {
			if (!isPendingMapTileCapture(capture) || !capture.shouldResyncShadow(now)) {
				continue;
			}
			// Re-sending every shadow chunk kept a stuck client-side tile alive, but
			// also re-queued the whole view every five seconds forever. One failed
			// tile could therefore consume the server tick long after a rocket launch
			// had completed. Fail it instead: the map cache already applies bounded
			// exponential retry backoff, so a transient renderer hiccup still recovers
			// without an unbounded chunk/network flood.
			failMapTileCapture(capture.requestId(), capture,
					"Клиент камеры не подтвердил тайл карты после синхронизации теневого мира");
		}
	}

	private static RendererBotPayloads.RendererBotMapTileRequestS2CPayload mapTileRequestPayload(PendingMapTileCapture capture) {
		return new RendererBotPayloads.RendererBotMapTileRequestS2CPayload(
				capture.requestId(),
				capture.renderSessionId(),
				capture.dimension().identifier().toString(),
				capture.tileSize(),
				capture.lod(),
				capture.tileX(),
				capture.tileZ(),
				capture.centerX(),
				capture.centerZ(),
				capture.blocksPerPixel(),
				capture.priorityScore(),
				capture.activeView()
		);
	}

	private static Set<ChunkTicketKey> collectTrackedShadowChunks(Iterable<ShadowDesiredState> desiredStates) {
		if (desiredStates == null) {
			return Set.of();
		}
		Set<ChunkTicketKey> tracked = new HashSet<>();
		for (ShadowDesiredState desiredState : desiredStates) {
			if (desiredState == null || desiredState.level() == null) {
				continue;
			}
			ResourceKey<Level> dimension = desiredState.level().dimension();
			LongIterator iterator = desiredState.trackedChunks().iterator();
			while (iterator.hasNext()) {
				tracked.add(new ChunkTicketKey(dimension, iterator.nextLong()));
			}
		}
		return tracked;
	}

	private static void syncShadowChunkTickets(MinecraftServer server, Iterable<ShadowDesiredState> desiredStates) {
		Map<CameraChunkTicketKey, Integer> desiredRefs = new HashMap<>();
		if (server == null) {
			ACTIVE_CAMERA_CHUNK_TICKETS.clear();
			return;
		}
		if (desiredStates != null) {
			for (ShadowDesiredState desiredState : desiredStates) {
				if (desiredState == null || desiredState.level() == null || desiredState.readOnlyMapOnly()) {
					continue;
				}
				for (Map.Entry<CameraChunkTicketKey, Integer> entry : desiredState.chunkTickets().entrySet()) {
					CameraChunkTicketKey key = entry.getKey();
					int refs = entry.getValue() == null ? 0 : entry.getValue();
					if (key == null || refs <= 0) {
						continue;
					}
					desiredRefs.merge(key, refs, Integer::sum);
				}
			}
		}
		if (Objects.equals(ACTIVE_CAMERA_CHUNK_TICKETS, desiredRefs)) {
			return;
		}
		Set<CameraChunkTicketKey> keys = new LinkedHashSet<>();
		keys.addAll(ACTIVE_CAMERA_CHUNK_TICKETS.keySet());
		keys.addAll(desiredRefs.keySet());
		for (CameraChunkTicketKey key : keys) {
			int current = ACTIVE_CAMERA_CHUNK_TICKETS.getOrDefault(key, 0);
			int desired = desiredRefs.getOrDefault(key, 0);
			ServerLevel level = server.getLevel(key.dimension());
			if (level == null) {
				continue;
			}
			if (current <= 0 && desired > 0) {
				level.getChunkSource().addTicketWithRadius(CAMERA_CHUNK_TICKET_TYPE, new ChunkPos(key.chunkLong()), key.radius());
			} else if (current > 0 && desired <= 0) {
				level.getChunkSource().removeTicketWithRadius(CAMERA_CHUNK_TICKET_TYPE, new ChunkPos(key.chunkLong()), key.radius());
			}
		}
		ACTIVE_CAMERA_CHUNK_TICKETS.clear();
		ACTIVE_CAMERA_CHUNK_TICKETS.putAll(desiredRefs);
	}

	private static void addCameraChunkTicket(
			Map<CameraChunkTicketKey, Integer> desiredRefs,
			ResourceKey<Level> dimension,
			ChunkPos center,
			int radius
	) {
		if (desiredRefs == null || dimension == null || center == null) {
			return;
		}
		int clampedRadius = Mth.clamp(radius, 2, 32);
		desiredRefs.merge(new CameraChunkTicketKey(dimension, center.toLong(), clampedRadius), 1, Integer::sum);
	}

	private static Map<ShadowSyncKey, ShadowDesiredState> collectDesiredShadowStates(
			MinecraftServer server,
			List<PendingMapTileCapture> activeMapTiles
	) {
		Map<ShadowSyncKey, ShadowDesiredState> desiredStates = new HashMap<>();
		ServerPlayer bot = selectBot(server);
		if (server == null || bot == null) {
			return desiredStates;
		}
		UUID botUuid = bot.getUUID();
		int viewDistance = resolveShadowViewDistance(bot);
		PendingVideoRecording rendererVideo = selectVideoRecordingForRenderer(botUuid);
		PendingCapture rendererCapture = selectPendingCaptureForRenderer(botUuid);
		boolean videoRecordingActive = rendererVideo != null && rendererVideo.clientStartSent();

		if (!videoRecordingActive) {
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || !botUuid.equals(stream.botUuid())) {
				continue;
			}
			LiveStreamSpec spec = stream.spec();
			ScheduledServiceTarget target = resolveServiceTarget(
					server,
					spec.dimension(),
					spec.expectedX(),
					spec.expectedY(),
					spec.expectedZ(),
					spec.expectedYaw(),
					spec.expectedPitch(),
					spec.followEntityUuid()
			);
			if (target == null || target.level() == null) {
				stopLiveStreamInternal(stream, "Renderer bot live stream target is unavailable", true);
				continue;
			}
			if (spec.cameraPos() != null && !isCameraPlayerLoaded(target.level(), spec.cameraPos())) {
				continue;
			}
			accumulateShadowDesiredState(
					desiredStates,
					botUuid,
					stream.spec().renderSessionId(),
					target,
					viewDistance,
					spec.hiddenEntityUuids(),
					spec.omnidirectionalChunkLoading(),
					spec.cameraPos() != null && spec.followEntityUuid() == null
			);
		}
		}

		if (!videoRecordingActive) {
		for (ActiveAudioCapture capture : ACTIVE_AUDIO_CAPTURES.values()) {
			if (capture == null || !botUuid.equals(capture.botUuid())) {
				continue;
			}
			AudioCaptureSpec spec = capture.spec();
			ScheduledServiceTarget target = resolveServiceTarget(
					server,
					spec.dimension(),
					spec.x(),
					spec.y(),
					spec.z(),
					0.0F,
					0.0F,
					null
			);
			if (target == null || target.level() == null) {
				stopAudioCaptureInternal(capture, "Renderer bot audio capture target is unavailable", true);
				continue;
			}
			accumulateShadowDesiredState(
					desiredStates,
					botUuid,
					spec.renderSessionId(),
					target,
					viewDistance,
					Set.of(),
					true,
					false
			);
		}
		}

		if (!videoRecordingActive) {
		for (Map.Entry<UUID, PendingCapture> entry : PENDING_CAPTURES.entrySet()) {
			PendingCapture capture = entry.getValue();
			if (capture == null || capture != rendererCapture) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, capture.dimension(), capture.x(), capture.y(), capture.z(), capture.yaw(), capture.pitch(), capture.followEntityUuid());
			if (target == null || target.level() == null) {
				failCapture(entry.getKey(), capture, "Renderer bot capture target is unavailable");
				continue;
			}
			Set<UUID> hiddenEntities = capture.feedbackPlayerId() == null
					? Set.of()
					: Set.of(capture.feedbackPlayerId());
			accumulateShadowDesiredState(desiredStates, botUuid, capture.renderSessionId(), target, viewDistance, hiddenEntities, false, false);
		}
		}

		if (!videoRecordingActive) {
		for (PendingMapTileCapture capture : activeMapTiles == null ? List.<PendingMapTileCapture>of() : activeMapTiles) {
			if (!isPendingMapTileCapture(capture) || !botUuid.equals(capture.botUuid())) {
				continue;
			}
			ServerLevel level = server.getLevel(capture.dimension());
			if (level == null) {
				failMapTileCapture(capture.requestId(), capture, "Renderer bot map tile target is unavailable");
				continue;
			}
			capture.markShadowTargetActive(System.currentTimeMillis());
			ScheduledServiceTarget target = new ScheduledServiceTarget(
					level,
					capture.centerX(),
					mapTileCameraY(level),
					capture.centerZ(),
					MAP_TILE_TOP_DOWN_YAW,
					MAP_TILE_TOP_DOWN_PITCH,
					null
			);
			accumulateMapTileShadowDesiredState(
					desiredStates,
					botUuid,
					capture.renderSessionId(),
					capture,
					target,
					mapTileViewDistance(capture)
			);
		}
		}

		for (Map.Entry<UUID, PendingVideoRecording> entry : PENDING_VIDEO_RECORDINGS.entrySet()) {
			PendingVideoRecording recording = entry.getValue();
			// Stop is a request to end the file, not permission to tear down the
			// shadow world. The client may still be warming the camera or needs a
			// couple of frames to make a valid short video. Keep its chunks and
			// render session alive until it confirms the file has finished.
			if (recording == null || recording != rendererVideo) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, recording.dimension(), recording.x(), recording.y(), recording.z(), recording.yaw(), recording.pitch(), recording.followEntityUuid());
			if (target == null || target.level() == null) {
				failVideoRecording(entry.getKey(), recording, "Renderer bot recording target is unavailable");
				continue;
			}
			// A hand camera is a moving first-person view. Its shadow cache must
			// slide as a radius around the player, just like the drone view.
			accumulateShadowDesiredState(desiredStates, botUuid, recording.renderSessionId(), target, viewDistance, Set.of(), true, false);
		}

		if (!videoRecordingActive && server.getPlayerList() != null) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player == null
						|| !player.isAlive()
						|| DroneSystem.isCameraBlockedByDroneControl(player)
						|| !(player.level() instanceof ServerLevel playerLevel)
						|| !playerHasCameraInActiveSlot(player)) {
					continue;
				}
				accumulateShadowDesiredState(
						desiredStates,
						botUuid,
						resolveRenderSessionId(playerLevel.dimension(), null, player.getX(), player.getZ(), player.getUUID()),
						new ScheduledServiceTarget(
								playerLevel,
								player.getX(),
								player.getEyeY(),
								player.getZ(),
								player.getYRot(),
								player.getXRot(),
								player
						),
						viewDistance,
						Set.of(),
						true,
						false
				);
			}
		}
		return desiredStates;
	}

	private static List<PendingMapTileCapture> activeMapTileShadowTargets(UUID botUuid) {
		if (botUuid == null || PENDING_MAP_TILE_CAPTURES.isEmpty()) {
			return List.of();
		}
		if (hasActiveVideoRecording(botUuid)) {
			return List.of();
		}
		// A map capture whose payload has already reached the client owns its
		// shadow world until it completes.  Keep that one alive, but do not start
		// another expensive top-down render while a real-time screen is active.
		// This is deliberately checked here as well as in the map scheduler:
		// visible map screens can enqueue work outside the background scheduler.
		boolean deferNewMapTiles = hasActiveRealtimeLiveStreamForBot(botUuid);
		List<PendingMapTileCapture> candidates = new ArrayList<>();
		for (PendingMapTileCapture capture : PENDING_MAP_TILE_CAPTURES.values()) {
			if (capture != null
					&& botUuid.equals(capture.botUuid())
					&& (!deferNewMapTiles || capture.clientRequestSent())
					&& !capture.pixelsFuture().isDone()) {
				candidates.add(capture);
			}
		}
		candidates.sort(RendererBotCameraSystem::compareMapTileCapturePriority);
		if (candidates.size() <= MAX_ACTIVE_MAP_TILE_SHADOW_TARGETS) {
			return candidates;
		}
		return new ArrayList<>(candidates.subList(0, MAX_ACTIVE_MAP_TILE_SHADOW_TARGETS));
	}

	private static boolean hasActiveRealtimeLiveStreamForBot(UUID botUuid) {
		if (botUuid == null) {
			return false;
		}
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || !botUuid.equals(stream.botUuid()) || stream.isStale()) {
				continue;
			}
			LiveStreamSpec spec = stream.spec();
			if (spec != null && spec.targetFps() > CAMERA_HOTBAR_WARMUP_FPS) {
				return true;
			}
		}
		return false;
	}

	private static boolean isPendingMapTileCapture(PendingMapTileCapture capture) {
		return capture != null
				&& !capture.pixelsFuture().isDone()
				&& PENDING_MAP_TILE_CAPTURES.get(capture.requestId()) == capture;
	}

	private static int compareMapTileCapturePriority(PendingMapTileCapture left, PendingMapTileCapture right) {
		if (left == right) {
			return 0;
		}
		if (left == null) {
			return 1;
		}
		if (right == null) {
			return -1;
		}
		// Once a payload has reached the client its chunks must stay owned by
		// the same shadow session until the capture finishes.
		if (left.clientRequestSent() != right.clientRequestSent()) {
			return left.clientRequestSent() ? -1 : 1;
		}
		int priority = Integer.compare(right.priorityScore(), left.priorityScore());
		if (priority != 0) {
			return priority;
		}
		// New geography is rendered from the world origin outward.  This must be
		// part of admission itself (not just the producer's iteration order),
		// otherwise a full queue can strand central tiles behind far-away ones.
		if (left.priorityScore() >= 2) {
			int distance = Double.compare(left.worldCenterDistanceSquared(), right.worldCenterDistanceSquared());
			if (distance != 0) {
				return distance;
			}
		}
		if (left.activeView() != right.activeView()) {
			return left.activeView() ? -1 : 1;
		}
		int queuedAt = Long.compare(left.queuedAtMillis(), right.queuedAtMillis());
		return queuedAt != 0 ? queuedAt : Long.compare(left.sequence(), right.sequence());
	}

	private static void accumulateShadowDesiredState(
			Map<ShadowSyncKey, ShadowDesiredState> desiredStates,
			UUID botUuid,
			UUID sessionId,
			ScheduledServiceTarget target,
			int viewDistance,
			Set<UUID> hiddenEntityUuids,
			boolean omnidirectionalChunkLoading,
			boolean staticCameraChunkBuffer
	) {
		if (desiredStates == null || botUuid == null || sessionId == null || target == null || !(target.level() instanceof ServerLevel level)) {
			return;
		}
		ShadowSyncKey key = new ShadowSyncKey(botUuid, sessionId);
		ShadowDesiredState desiredState = desiredStates.get(key);
		if (desiredState == null) {
			desiredState = new ShadowDesiredState(sessionId, level);
			desiredStates.put(key, desiredState);
		}
		if (desiredState.level() != level) {
			return;
		}
		desiredState.addTarget(
				target,
				viewDistance,
				hiddenEntityUuids == null ? Set.of() : Set.copyOf(hiddenEntityUuids),
				omnidirectionalChunkLoading,
				staticCameraChunkBuffer
		);
	}

	private static void accumulateMapTileShadowDesiredState(
			Map<ShadowSyncKey, ShadowDesiredState> desiredStates,
			UUID botUuid,
			UUID sessionId,
			PendingMapTileCapture capture,
			ScheduledServiceTarget target,
			int viewDistance
	) {
		if (desiredStates == null || botUuid == null || sessionId == null || capture == null || target == null || !(target.level() instanceof ServerLevel level)) {
			return;
		}
		ShadowSyncKey key = new ShadowSyncKey(botUuid, sessionId);
		ShadowDesiredState desiredState = desiredStates.get(key);
		if (desiredState == null) {
			desiredState = new ShadowDesiredState(sessionId, level);
			desiredStates.put(key, desiredState);
		}
		if (desiredState.level() != level) {
			return;
		}
		desiredState.setItemDisplaysOnly(true);
		// A map tile is a read-only snapshot of MCA data.  It intentionally does
		// not enter the normal camera-ticket path: a loading ticket may generate
		// absent neighbours while resolving the FULL chunk dependency pyramid.
		desiredState.addReadOnlyMapTarget(target, viewDistance, capture.readOnlyChunkPayloads());
	}

	private static void syncShadowState(
			MinecraftServer server,
			ServerPlayer bot,
			ShadowSyncKey key,
			ShadowDesiredState desiredState,
			Set<ChunkTicketKey> consumedDirtyChunks
	) {
		if (server == null || bot == null || key == null || desiredState == null || desiredState.level() == null) {
			return;
		}
		if (!ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotShadowLevelInitS2CPayload.TYPE)) {
			return;
		}
		ShadowDimensionSyncState activeState = ACTIVE_SHADOW_SYNC_STATES.computeIfAbsent(
				key,
				ignored -> new ShadowDimensionSyncState(key.botUuid(), key.sessionId(), desiredState.level().dimension())
		);
		syncShadowLevelInit(bot, desiredState, activeState);
		syncShadowView(bot, desiredState, activeState);
		syncShadowLevelState(bot, desiredState, activeState);
		boolean allRequiredChunksQueued = syncShadowChunks(bot, desiredState, activeState, consumedDirtyChunks);
		sendShadowContentReady(bot, activeState, allRequiredChunksQueued);
		syncShadowEntities(bot, desiredState, activeState);
	}

	private static void syncShadowLevelInit(ServerPlayer bot, ShadowDesiredState desiredState, ShadowDimensionSyncState activeState) {
		ServerLevel level = desiredState.level();
		boolean fixedMapLighting = desiredState.readOnlyMapOnly();
		long gameTime = fixedMapLighting ? MAP_TILE_RENDER_DAY_TIME : level.getGameTime();
		long dayTime = fixedMapLighting ? MAP_TILE_RENDER_DAY_TIME : level.getDayTime();
		boolean tickDayTime = !fixedMapLighting && level.getGameRules().get(GameRules.ADVANCE_TIME);
		boolean raining = !fixedMapLighting && level.isRaining();
		float rainLevel = fixedMapLighting ? 0.0F : level.getRainLevel(1.0F);
		float thunderLevel = fixedMapLighting ? 0.0F : level.getThunderLevel(1.0F);
		String dimensionTypeId = level.dimensionTypeRegistration()
				.unwrapKey()
				.orElseThrow()
				.identifier()
				.toString();
		// A radius change is a normal chunk-cache update, not a world change.
		// Reinitialising here destroys the entire client shadow level, which made
		// every camera chunk disappear and reload whenever its effective view
		// window changed by even one chunk.
		if (activeState.initialized()
				&& Objects.equals(activeState.dimensionTypeId(), dimensionTypeId)
				&& activeState.seed() == level.getSeed()) {
			return;
		}
		activeState.setInitialized(true);
		activeState.setDimensionTypeId(dimensionTypeId);
		activeState.setSeed(level.getSeed());
		activeState.setViewDistance(desiredState.viewDistance());
		activeState.trackedChunks().clear();
		activeState.trackedEntities().clear();
		activeState.markContentPending();
		// Level init recreates the client shadow level with a temporary 0,0 view.
		// Force the following ShadowView even when the server centre did not move;
		// otherwise the client may reject every subsequently sent chunk as outside
		// its stale view after a view-distance/dimension reinitialisation.
		activeState.forceViewResend();
		ServerPlayNetworking.send(
				bot,
				new RendererBotPayloads.RendererBotShadowLevelInitS2CPayload(
						desiredState.sessionId(),
						level.dimension().identifier().toString(),
						dimensionTypeId,
						level.getSeed(),
						level.isDebug(),
						level.isFlat(),
						level.getServer().getWorldData().isHardcore(),
						level.getDifficulty().ordinal(),
						gameTime,
						dayTime,
						tickDayTime,
						raining,
						rainLevel,
						thunderLevel,
						level.getSeaLevel(),
						desiredState.viewDistance(),
						Math.max(2, level.getServer().getPlayerList().getSimulationDistance())
				)
		);
	}

	private static void syncShadowView(ServerPlayer bot, ShadowDesiredState desiredState, ShadowDimensionSyncState activeState) {
		if (bot == null || desiredState == null || activeState == null) {
			return;
		}
		int centerChunkX = desiredState.centerChunkX();
		int centerChunkZ = desiredState.centerChunkZ();
		if (activeState.lastCenterChunkX() == centerChunkX
				&& activeState.lastCenterChunkZ() == centerChunkZ
				&& activeState.viewDistance() == desiredState.viewDistance()) {
			return;
		}
		activeState.setLastCenterChunkX(centerChunkX);
		activeState.setLastCenterChunkZ(centerChunkZ);
		activeState.setViewDistance(desiredState.viewDistance());
		activeState.markContentPending();
		ServerPlayNetworking.send(
				bot,
				new RendererBotPayloads.RendererBotShadowViewS2CPayload(
						desiredState.sessionId(),
						centerChunkX,
						centerChunkZ,
						desiredState.viewDistance()
				)
		);
	}

	private static void syncShadowLevelState(ServerPlayer bot, ShadowDesiredState desiredState, ShadowDimensionSyncState activeState) {
		if (desiredState == null || desiredState.level() == null) {
			return;
		}
		ServerLevel level = desiredState.level();
		boolean fixedMapLighting = desiredState.readOnlyMapOnly();
		long gameTime = fixedMapLighting ? MAP_TILE_RENDER_DAY_TIME : level.getGameTime();
		long dayTime = fixedMapLighting ? MAP_TILE_RENDER_DAY_TIME : level.getDayTime();
		boolean tickDayTime = !fixedMapLighting && level.getGameRules().get(GameRules.ADVANCE_TIME);
		boolean raining = !fixedMapLighting && level.isRaining();
		float rainLevel = fixedMapLighting ? 0.0F : level.getRainLevel(1.0F);
		float thunderLevel = fixedMapLighting ? 0.0F : level.getThunderLevel(1.0F);
		if (activeState.lastGameTime() == gameTime
				&& activeState.lastDayTime() == dayTime
				&& activeState.lastTickDayTime() == tickDayTime
				&& activeState.lastRaining() == raining
				&& Float.compare(activeState.lastRainLevel(), rainLevel) == 0
				&& Float.compare(activeState.lastThunderLevel(), thunderLevel) == 0) {
			return;
		}
		activeState.setLastGameTime(gameTime);
		activeState.setLastDayTime(dayTime);
		activeState.setLastTickDayTime(tickDayTime);
		activeState.setLastRaining(raining);
		activeState.setLastRainLevel(rainLevel);
		activeState.setLastThunderLevel(thunderLevel);
		ServerPlayNetworking.send(
				bot,
				new RendererBotPayloads.RendererBotShadowLevelStateS2CPayload(
						activeState.sessionId(),
						level.dimension().identifier().toString(),
						gameTime,
						dayTime,
						tickDayTime,
						raining,
						rainLevel,
						thunderLevel
				)
		);
	}

	private static boolean syncShadowChunks(
			ServerPlayer bot,
			ShadowDesiredState desiredState,
			ShadowDimensionSyncState activeState,
			Set<ChunkTicketKey> consumedDirtyChunks
	) {
		ServerLevel level = desiredState.level();
		LongSet previousChunks = new LongOpenHashSet(activeState.trackedChunks());
		LongSet newTrackedChunks = new LongOpenHashSet();
		boolean allRequiredChunksQueued = true;
		boolean contentChanged = false;

		for (long chunkLong : orderedTrackedChunks(desiredState)) {
			ChunkPos pos = new ChunkPos(chunkLong);
			ChunkTicketKey dirtyKey = new ChunkTicketKey(level.dimension(), chunkLong);
			boolean alreadyTracked = previousChunks.contains(chunkLong);
			boolean dirty = DIRTY_SHADOW_CHUNKS.contains(dirtyKey);
			previousChunks.remove(chunkLong);
			byte[] readOnlyPayload = desiredState.readOnlyChunkPayload(chunkLong);
			if (readOnlyPayload != null && readOnlyPayload.length > 0) {
				if (!alreadyTracked || dirty) {
					ServerPlayNetworking.send(
							bot,
							new RendererBotPayloads.RendererBotShadowChunkDataS2CPayload(
									desiredState.sessionId(),
									level.dimension().identifier().toString(),
									readOnlyPayload
						)
					);
					if (dirty) {
						consumedDirtyChunks.add(dirtyKey);
					}
					contentChanged = true;
				}
				newTrackedChunks.add(chunkLong);
				continue;
			}
			net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
			if (chunk == null) {
				allRequiredChunksQueued = false;
				if (alreadyTracked) {
					newTrackedChunks.add(chunkLong);
				}
				continue;
			}
			if (!alreadyTracked || dirty) {
				ServerPlayNetworking.send(
						bot,
						new RendererBotPayloads.RendererBotShadowChunkDataS2CPayload(
								desiredState.sessionId(),
								level.dimension().identifier().toString(),
								encodeShadowChunkPacket(level, bot, chunk)
						)
				);
				if (dirty) {
					consumedDirtyChunks.add(dirtyKey);
				}
				contentChanged = true;
			}
			newTrackedChunks.add(chunkLong);
		}

		LongIterator removedIterator = previousChunks.iterator();
		while (removedIterator.hasNext()) {
			ChunkPos removedPos = new ChunkPos(removedIterator.nextLong());
			ServerPlayNetworking.send(
					bot,
					new RendererBotPayloads.RendererBotShadowForgetChunkS2CPayload(
							desiredState.sessionId(),
							level.dimension().identifier().toString(),
							removedPos.x,
							removedPos.z
					)
			);
			contentChanged = true;
		}

		activeState.trackedChunks().clear();
		activeState.trackedChunks().addAll(newTrackedChunks);
		if (contentChanged) {
			activeState.markContentPending();
		}
		return allRequiredChunksQueued && newTrackedChunks.size() == desiredState.trackedChunks().size();
	}

	private static void sendShadowContentReady(
			ServerPlayer bot,
			ShadowDimensionSyncState activeState,
			boolean allRequiredChunksQueued
	) {
		if (bot == null || activeState == null || !allRequiredChunksQueued || !activeState.contentReadyPending()) {
			return;
		}
		if (!ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotShadowContentReadyS2CPayload.TYPE)) {
			return;
		}
		ServerPlayNetworking.send(
				bot,
				new RendererBotPayloads.RendererBotShadowContentReadyS2CPayload(
						activeState.sessionId(),
						activeState.publishContentReady()
				)
		);
	}

	private static LongArrayList orderedTrackedChunks(ShadowDesiredState desiredState) {
		LongArrayList ordered = new LongArrayList();
		if (desiredState == null || desiredState.trackedChunks().isEmpty()) {
			return ordered;
		}
		LongIterator iterator = desiredState.trackedChunks().iterator();
		while (iterator.hasNext()) {
			ordered.add(iterator.nextLong());
		}
		int centerChunkX = desiredState.centerChunkX();
		int centerChunkZ = desiredState.centerChunkZ();
		ordered.sort((leftChunkLong, rightChunkLong) -> compareTrackedChunks(leftChunkLong, rightChunkLong, centerChunkX, centerChunkZ));
		return ordered;
	}

	private static int compareTrackedChunks(long leftChunkLong, long rightChunkLong, int centerChunkX, int centerChunkZ) {
		ChunkPos left = new ChunkPos(leftChunkLong);
		ChunkPos right = new ChunkPos(rightChunkLong);
		int leftChebyshevDistance = Math.max(Math.abs(left.x - centerChunkX), Math.abs(left.z - centerChunkZ));
		int rightChebyshevDistance = Math.max(Math.abs(right.x - centerChunkX), Math.abs(right.z - centerChunkZ));
		if (leftChebyshevDistance != rightChebyshevDistance) {
			return Integer.compare(leftChebyshevDistance, rightChebyshevDistance);
		}
		int leftManhattanDistance = Math.abs(left.x - centerChunkX) + Math.abs(left.z - centerChunkZ);
		int rightManhattanDistance = Math.abs(right.x - centerChunkX) + Math.abs(right.z - centerChunkZ);
		if (leftManhattanDistance != rightManhattanDistance) {
			return Integer.compare(leftManhattanDistance, rightManhattanDistance);
		}
		if (left.z != right.z) {
			return Integer.compare(left.z, right.z);
		}
		return Integer.compare(left.x, right.x);
	}

	private static void syncShadowEntities(ServerPlayer bot, ShadowDesiredState desiredState, ShadowDimensionSyncState activeState) {
		ServerLevel level = desiredState.level();
		Map<Integer, ShadowTrackedEntity> trackedEntities = activeState.trackedEntities();
		Set<Integer> desiredEntityIds = new HashSet<>();
		List<Packet<? extends ClientGamePacketListener>> packets = new ArrayList<>();
		AABB searchBox = shadowEntitySearchBox(desiredState);

		PacketContext.runWithContext(bot.connection, () -> {
			for (Entity entity : level.getEntities((Entity) null, searchBox, entity -> true)) {
				if (!shouldShadowTrackEntity(entity, desiredState)) {
					continue;
				}
				desiredEntityIds.add(entity.getId());
				ShadowTrackedEntity trackedEntity = trackedEntities.get(entity.getId());
				if (trackedEntity == null || trackedEntity.entity() != entity) {
					trackedEntity = createShadowTrackedEntity(level, entity, bot);
					trackedEntities.put(entity.getId(), trackedEntity);
					trackedEntity.serverEntity().sendPairingData(bot, packets::add);
				}
				trackedEntity.collector().clear();
				List<SynchedEntityData.DataValue<?>> preservedDirtyData = preserveDirtyTrackedData(entity);
				trackedEntity.serverEntity().sendChanges();
				restoreDirtyTrackedData(entity, preservedDirtyData);
				packets.addAll(trackedEntity.collector().drain());
			}
		});

		if (!trackedEntities.isEmpty()) {
			List<Integer> removals = new ArrayList<>();
			for (Integer entityId : new ArrayList<>(trackedEntities.keySet())) {
				if (desiredEntityIds.contains(entityId)) {
					continue;
				}
				removals.add(entityId);
				trackedEntities.remove(entityId);
			}
			if (!removals.isEmpty()) {
				int[] removalIds = removals.stream().mapToInt(Integer::intValue).toArray();
				packets.add(new ClientboundRemoveEntitiesPacket(removalIds));
			}
		}

		if (packets.isEmpty()) {
			return;
		}
		ServerPlayNetworking.send(
				bot,
				new RendererBotPayloads.RendererBotShadowEntityPacketsS2CPayload(
						desiredState.sessionId(),
						level.dimension().identifier().toString(),
						RendererBotShadowPacketCodec.encodePacketList(level.registryAccess(), bot.connection, packets)
				)
		);
	}

	private static AABB shadowEntitySearchBox(ShadowDesiredState desiredState) {
		ServerLevel level = desiredState != null ? desiredState.level() : null;
		if (level == null || desiredState == null || desiredState.trackedChunks().isEmpty()) {
			return new AABB(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		return new AABB(
				desiredState.minChunkX() * 16.0D,
				level.getMinY(),
				desiredState.minChunkZ() * 16.0D,
				desiredState.maxChunkX() * 16.0D + 16.0D,
				level.getMaxY() + 1.0D,
				desiredState.maxChunkZ() * 16.0D + 16.0D
		);
	}

	public static void mirrorTransientEntityPacket(Entity entity, Packet<? super ClientGamePacketListener> packet) {
		if (!(entity != null && entity.level() instanceof ServerLevel level) || !isShadowTransientEntityPacket(packet)) {
			return;
		}
		@SuppressWarnings("unchecked")
		Packet<? extends ClientGamePacketListener> clientPacket = (Packet<? extends ClientGamePacketListener>) packet;
		for (ShadowDimensionSyncState activeState : ACTIVE_SHADOW_SYNC_STATES.values()) {
			if (activeState == null
					|| !activeState.initialized()
					|| !activeState.dimension().equals(level.dimension())
					|| !activeState.trackedEntities().containsKey(entity.getId())) {
				continue;
			}
			sendShadowTransientPacket(level, activeState, clientPacket);
		}
	}

	public static void mirrorTransientBlockEvent(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block, int paramA, int paramB) {
		if (level == null || pos == null || block == null) {
			return;
		}
		mirrorTransientLevelPacket(level, pos, new ClientboundBlockEventPacket(pos, block, paramA, paramB));
	}

	public static void mirrorTransientBlockDestruction(ServerLevel level, int breakerId, BlockPos pos, int progress) {
		if (level == null || pos == null) {
			return;
		}
		mirrorTransientLevelPacket(level, pos, new ClientboundBlockDestructionPacket(breakerId, pos, progress));
	}

	public static void mirrorTransientLevelEvent(ServerLevel level, int type, BlockPos pos, int data, boolean globalEvent) {
		if (level == null || pos == null) {
			return;
		}
		mirrorTransientLevelPacket(level, pos, new ClientboundLevelEventPacket(type, pos, data, globalEvent));
	}

	public static void mirrorTransientParticles(
			ServerLevel level,
			net.minecraft.core.particles.ParticleOptions particle,
			boolean overrideLimiter,
			boolean alwaysShow,
			double x,
			double y,
			double z,
			int count,
			double xDist,
			double yDist,
			double zDist,
			double maxSpeed
	) {
		if (level == null || particle == null) {
			return;
		}
		ClientboundLevelParticlesPacket packet = new ClientboundLevelParticlesPacket(
				particle,
				overrideLimiter,
				alwaysShow,
				x,
				y,
				z,
				(float) xDist,
				(float) yDist,
				(float) zDist,
				(float) maxSpeed,
				count
		);
		mirrorTransientLevelPacket(level, BlockPos.containing(x, y, z), packet);
	}

	public static void mirrorTransientSound(
			ServerLevel level,
			Holder<SoundEvent> sound,
			SoundSource source,
			double x,
			double y,
			double z,
			float volume,
			float pitch,
			long seed
	) {
		if (level == null || sound == null || source == null) {
			return;
		}
		mirrorTransientLevelPacket(
				level,
				BlockPos.containing(x, y, z),
				new ClientboundSoundPacket(sound, source, x, y, z, volume, pitch, seed)
		);
	}

	public static void mirrorTransientEntitySound(
			ServerLevel level,
			Holder<SoundEvent> sound,
			SoundSource source,
			Entity entity,
			float volume,
			float pitch,
			long seed
	) {
		if (level == null || sound == null || source == null || entity == null) {
			return;
		}
		mirrorTransientLevelPacket(
				level,
				entity.blockPosition(),
					new ClientboundSoundEntityPacket(sound, source, entity, volume, pitch, seed)
			);
		}

	public static void mirrorTransientStopSound(MinecraftServer server, Identifier soundId, SoundSource source) {
		if (server == null) {
			return;
		}
		ClientboundStopSoundPacket packet = new ClientboundStopSoundPacket(soundId, source);
		for (ShadowDimensionSyncState activeState : ACTIVE_SHADOW_SYNC_STATES.values()) {
			if (activeState == null || !activeState.initialized()) {
				continue;
			}
			ServerLevel level = server.getLevel(activeState.dimension());
			if (level == null) {
				continue;
			}
			sendShadowTransientPacket(level, activeState, packet);
		}
	}

	private static void mirrorTransientLevelPacket(ServerLevel level, BlockPos pos, Packet<? extends ClientGamePacketListener> packet) {
		if (level == null || pos == null || packet == null) {
			return;
		}
		long chunkLong = new ChunkPos(pos).toLong();
		for (ShadowDimensionSyncState activeState : ACTIVE_SHADOW_SYNC_STATES.values()) {
			if (activeState == null
					|| !activeState.initialized()
					|| !activeState.dimension().equals(level.dimension())
					|| !activeState.trackedChunks().contains(chunkLong)) {
				continue;
			}
			sendShadowTransientPacket(level, activeState, packet);
		}
	}

	private static void sendShadowTransientPacket(
			ServerLevel level,
			ShadowDimensionSyncState activeState,
			Packet<? extends ClientGamePacketListener> packet
	) {
		if (level == null || activeState == null || packet == null) {
			return;
		}
		ServerPlayer bot = level.getServer().getPlayerList().getPlayer(activeState.botUuid());
		if (bot == null
				|| bot.connection == null
				|| !READY_BOTS.containsKey(bot.getUUID())
				|| !ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotShadowEntityPacketsS2CPayload.TYPE)) {
			return;
		}
		RendererBotPayloads.ShadowPacketData encoded = RendererBotShadowPacketCodec.encodePacket(level.registryAccess(), bot.connection, packet);
		if (encoded == null) {
			return;
		}
		ServerPlayNetworking.send(
				bot,
				new RendererBotPayloads.RendererBotShadowEntityPacketsS2CPayload(
						activeState.sessionId(),
						level.dimension().identifier().toString(),
						List.of(encoded)
				)
		);
	}

	private static boolean isShadowTransientEntityPacket(Packet<?> packet) {
		return packet instanceof ClientboundAnimatePacket
				|| packet instanceof ClientboundEntityEventPacket
				|| packet instanceof ClientboundHurtAnimationPacket
				|| packet instanceof ClientboundDamageEventPacket
				|| packet instanceof ClientboundSetEntityDataPacket
				|| packet instanceof ClientboundSetEquipmentPacket
				|| packet instanceof ClientboundUpdateAttributesPacket
				|| packet instanceof ClientboundSetPassengersPacket
				|| packet instanceof ClientboundSetEntityLinkPacket;
	}

	private static boolean shouldShadowTrackEntity(Entity entity, ShadowDesiredState desiredState) {
		if (entity == null
				|| desiredState == null
				|| desiredState.level() == null
				|| entity.isRemoved()
				|| entity.level() != desiredState.level()
				|| !desiredState.trackedChunks().contains(entity.chunkPosition().toLong())) {
			return false;
		}
		if (desiredState.hiddenEntityUuids().contains(entity.getUUID())) {
			return false;
		}
		if (entity instanceof ServerPlayer player && RendererBotPresenceSystem.isRendererBot(player)) {
			return false;
		}
		if (desiredState.itemDisplaysOnly()) {
			return entity instanceof Display.ItemDisplay;
		}
		double entityRangeBlocks = Math.max(16.0D, entity.getType().clientTrackingRange() * 16.0D);
		double entityRangeSq = entityRangeBlocks * entityRangeBlocks;
		for (ScheduledServiceTarget target : desiredState.targets()) {
			if (target == null || target.level() != desiredState.level()) {
				continue;
			}
			double dx = entity.getX() - target.x();
			double dz = entity.getZ() - target.z();
			if (dx * dx + dz * dz <= entityRangeSq) {
				return true;
			}
		}
		return false;
	}

	private static ShadowTrackedEntity createShadowTrackedEntity(ServerLevel level, Entity entity, ServerPlayer bot) {
		ShadowPacketCollector collector = new ShadowPacketCollector(bot);
		return new ShadowTrackedEntity(
				entity,
				new net.minecraft.server.level.ServerEntity(
						level,
						entity,
						1,
						true,
						collector
				),
				collector
		);
	}

	private static List<SynchedEntityData.DataValue<?>> preserveDirtyTrackedData(Entity entity) {
		if (entity == null) {
			return List.of();
		}
		List<SynchedEntityData.DataValue<?>> dirtyData = entity.getEntityData().packDirty();
		restoreDirtyTrackedData(entity, dirtyData);
		return dirtyData == null ? List.of() : dirtyData;
	}

	private static void restoreDirtyTrackedData(Entity entity, List<SynchedEntityData.DataValue<?>> dirtyData) {
		if (entity == null || dirtyData == null || dirtyData.isEmpty()) {
			return;
		}
		SynchedEntityData entityData = entity.getEntityData();
		SynchedEntityData.DataItem<?>[] itemsById = ((SynchedEntityDataAccessor) (Object) entityData).lg2$getItemsById();
		if (itemsById == null || itemsById.length == 0) {
			return;
		}
		for (SynchedEntityData.DataValue<?> value : dirtyData) {
			if (value == null || value.id() < 0 || value.id() >= itemsById.length) {
				continue;
			}
			SynchedEntityData.DataItem<?> item = itemsById[value.id()];
			if (item == null) {
				continue;
			}
			restoreDirtyTrackedDataItem(entityData, item);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> void restoreDirtyTrackedDataItem(SynchedEntityData entityData, SynchedEntityData.DataItem<?> item) {
		if (entityData == null || item == null) {
			return;
		}
		SynchedEntityData.DataItem<T> typedItem = (SynchedEntityData.DataItem<T>) item;
		EntityDataAccessor<T> accessor = typedItem.getAccessor();
		if (accessor == null) {
			return;
		}
		entityData.set(accessor, typedItem.getValue(), true);
	}

	private static void clearAllShadowSyncStates(MinecraftServer server, boolean notifyClient) {
		for (ShadowSyncKey key : new ArrayList<>(ACTIVE_SHADOW_SYNC_STATES.keySet())) {
			removeShadowSyncState(server, key, notifyClient);
		}
		DIRTY_SHADOW_CHUNKS.clear();
	}

	private static void clearShadowSyncState(MinecraftServer server, UUID botUuid, boolean notifyClient) {
		if (botUuid == null) {
			return;
		}
		for (ShadowSyncKey key : new ArrayList<>(ACTIVE_SHADOW_SYNC_STATES.keySet())) {
			if (!botUuid.equals(key.botUuid())) {
				continue;
			}
			removeShadowSyncState(server, key, notifyClient);
		}
	}

	private static void removeShadowSyncState(MinecraftServer server, ShadowSyncKey key, boolean notifyClient) {
		ShadowDimensionSyncState removed = ACTIVE_SHADOW_SYNC_STATES.remove(key);
		if (removed == null || !notifyClient || server == null) {
			return;
		}
		ServerPlayer bot = server.getPlayerList().getPlayer(key.botUuid());
		if (bot == null || !ServerPlayNetworking.canSend(bot, RendererBotPayloads.RendererBotShadowLevelDestroyS2CPayload.TYPE)) {
			return;
		}
		ServerPlayNetworking.send(
				bot,
				new RendererBotPayloads.RendererBotShadowLevelDestroyS2CPayload(key.sessionId())
		);
	}

	private static void tickBotJobs(MinecraftServer server) {
		if (server == null) {
			return;
		}
		ServerPlayer bot = selectBot(server);
		if (bot == null) {
			return;
		}
		long now = System.currentTimeMillis();
		ScheduledServiceTarget selectedTarget = null;
		long selectedDueAt = Long.MAX_VALUE;
		int selectedPriority = Integer.MAX_VALUE;

		for (Map.Entry<UUID, PendingCapture> entry : PENDING_CAPTURES.entrySet()) {
			PendingCapture capture = entry.getValue();
			if (capture == null || !bot.getUUID().equals(capture.botUuid()) || capture.isDone()) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, capture.dimension(), capture.x(), capture.y(), capture.z(), capture.yaw(), capture.pitch(), capture.followEntityUuid());
			if (target == null) {
				failCapture(entry.getKey(), capture, "Renderer bot capture target is unavailable");
				continue;
			}
			long dueAt = capture.nextDueAtMillis();
			if (isBetterCandidate(dueAt, 0, selectedDueAt, selectedPriority)) {
				selectedTarget = target;
				selectedDueAt = dueAt;
				selectedPriority = 0;
			}
		}

		for (Map.Entry<UUID, PendingVideoRecording> entry : PENDING_VIDEO_RECORDINGS.entrySet()) {
			PendingVideoRecording recording = entry.getValue();
			if (recording == null || !bot.getUUID().equals(recording.botUuid()) || recording.completionFuture().isDone() || recording.stopRequested()) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, recording.dimension(), recording.x(), recording.y(), recording.z(), recording.yaw(), recording.pitch(), recording.followEntityUuid());
			if (target == null) {
				failVideoRecording(entry.getKey(), recording, "Renderer bot recording target is unavailable");
				continue;
			}
			long dueAt = recording.nextDueAtMillis();
			if (isBetterCandidate(dueAt, 1, selectedDueAt, selectedPriority)) {
				selectedTarget = target;
				selectedDueAt = dueAt;
				selectedPriority = 1;
			}
		}

		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || !bot.getUUID().equals(stream.botUuid())) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(
					server,
					stream.spec().dimension(),
					stream.spec().expectedX(),
					stream.spec().expectedY(),
					stream.spec().expectedZ(),
					stream.spec().expectedYaw(),
					stream.spec().expectedPitch(),
					stream.spec().followEntityUuid()
			);
			if (target == null) {
				stopLiveStreamInternal(stream, "Renderer bot live stream target is unavailable", true);
				continue;
			}
			long dueAt = stream.nextDueAtMillis();
			if (isBetterCandidate(dueAt, 2, selectedDueAt, selectedPriority)) {
				selectedTarget = target;
				selectedDueAt = dueAt;
				selectedPriority = 2;
			}
		}

		if (selectedTarget == null || selectedDueAt > now) {
			return;
		}
		applyServiceTarget(bot, selectedTarget);
		markDispatchedForMatchingJobs(bot.getUUID(), selectedTarget, now);
	}

	private static boolean isBetterCandidate(long dueAt, int priority, long selectedDueAt, int selectedPriority) {
		return dueAt < selectedDueAt || (dueAt == selectedDueAt && priority < selectedPriority);
	}

	private static void applyServiceTarget(ServerPlayer bot, ScheduledServiceTarget target) {
		if (bot == null || target == null || target.level() == null) {
			return;
		}
		if (target.followTarget() != null) {
			prepareBotToFollowEntity(
					bot,
					target.level(),
					target.x(),
					target.y(),
					target.z(),
					target.yaw(),
					target.pitch(),
					target.followTarget()
			);
			return;
		}
		prepareBotForStaticView(bot, target.level(), target.x(), target.y(), target.z(), target.yaw(), target.pitch());
	}

	private static void markDispatchedForMatchingJobs(UUID botUuid, ScheduledServiceTarget target, long now) {
		if (botUuid == null || target == null) {
			return;
		}
		for (PendingCapture capture : PENDING_CAPTURES.values()) {
			if (capture == null || !botUuid.equals(capture.botUuid()) || capture.isDone()) {
				continue;
			}
			if (matchesTarget(capture.dimension(), capture.followEntityUuid(), capture.x(), capture.y(), capture.z(), capture.yaw(), capture.pitch(), target)) {
				capture.markDispatched(now);
			}
		}
		for (PendingVideoRecording recording : PENDING_VIDEO_RECORDINGS.values()) {
			if (recording == null || !botUuid.equals(recording.botUuid()) || recording.completionFuture().isDone()) {
				continue;
			}
			if (matchesTarget(recording.dimension(), recording.followEntityUuid(), recording.x(), recording.y(), recording.z(), recording.yaw(), recording.pitch(), target)) {
				recording.markDispatched(now);
			}
		}
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || !botUuid.equals(stream.botUuid())) {
				continue;
			}
			LiveStreamSpec spec = stream.spec();
			if (matchesTarget(spec.dimension(), spec.followEntityUuid(), spec.expectedX(), spec.expectedY(), spec.expectedZ(), spec.expectedYaw(), spec.expectedPitch(), target)) {
				stream.markDispatched(now);
			}
		}
	}

	private static boolean matchesTarget(
			ResourceKey<Level> dimension,
			UUID followEntityUuid,
			double x,
			double y,
			double z,
			float yaw,
			float pitch,
			ScheduledServiceTarget target
	) {
		if (target == null) {
			return false;
		}
		if (followEntityUuid != null) {
			Entity followTarget = target.followTarget();
			return followTarget != null && followEntityUuid.equals(followTarget.getUUID());
		}
		if (target.followTarget() != null || target.level() == null || dimension == null || !target.level().dimension().equals(dimension)) {
			return false;
		}
		return Math.abs(target.x() - x) <= 0.01D
				&& Math.abs(target.y() - y) <= 0.01D
				&& Math.abs(target.z() - z) <= 0.01D
				&& Math.abs(target.yaw() - yaw) <= 0.1F
				&& Math.abs(target.pitch() - pitch) <= 0.1F;
	}

	private static ScheduledServiceTarget resolveServiceTarget(
			MinecraftServer server,
			ResourceKey<Level> dimension,
			double x,
			double y,
			double z,
			float yaw,
			float pitch,
			UUID followEntityUuid
	) {
		if (server == null) {
			return null;
		}
		if (followEntityUuid != null) {
			Entity target = resolveFollowEntity(server, dimension, followEntityUuid);
			if (target == null || !(target.level() instanceof ServerLevel targetLevel)) {
				return null;
			}
			return new ScheduledServiceTarget(targetLevel, target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot(), target);
		}
		ServerLevel level = dimension != null ? server.getLevel(dimension) : null;
		if (level == null) {
			return null;
		}
		return new ScheduledServiceTarget(level, x, y, z, yaw, pitch, null);
	}

	private static Entity resolveFollowEntity(MinecraftServer server, ResourceKey<Level> dimension, UUID followEntityUuid) {
		if (server == null || followEntityUuid == null) {
			return null;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(followEntityUuid);
		if (player != null) {
			return player;
		}
		ServerLevel level = dimension != null ? server.getLevel(dimension) : null;
		return level == null ? null : level.getEntity(followEntityUuid);
	}

	private static void failCapture(UUID requestId, PendingCapture capture, String message) {
		if (capture == null || requestId == null) {
			return;
		}
		IllegalStateException failure = new IllegalStateException(message);
		if (PENDING_CAPTURES.remove(requestId, capture)) {
			capture.previewFuture().completeExceptionally(failure);
			capture.fullFuture().completeExceptionally(failure);
			releaseBotCameraIfNeeded(capture.server(), capture.botUuid(), capture.resetCameraOnFinish());
		}
	}

	private static void notifyHandheldPhotoCaptured(PendingCapture capture) {
		if (capture == null || capture.feedbackPlayerId() == null || capture.server() == null) {
			return;
		}
		ServerPlayer requester = capture.server().getPlayerList().getPlayer(capture.feedbackPlayerId());
		if (requester != null) {
			CameraCaptureSystem.notifyPhotoCaptured(requester, capture.feedbackShutterOrigin());
		}
	}

	private static void failMapTileCapture(UUID requestId, PendingMapTileCapture capture, String message) {
		if (capture == null || requestId == null) {
			return;
		}
		IllegalStateException failure = new IllegalStateException(message);
		if (PENDING_MAP_TILE_CAPTURES.remove(requestId, capture)) {
			capture.pixelsFuture().completeExceptionally(failure);
		}
	}

	private static void failVideoRecording(UUID requestId, PendingVideoRecording recording, String message) {
		if (recording == null || requestId == null) {
			return;
		}
		IllegalStateException failure = new IllegalStateException(message);
		if (PENDING_VIDEO_RECORDINGS.remove(requestId, recording)) {
			RemoteVideoUpload upload = REMOTE_VIDEO_UPLOADS.remove(requestId);
			if (upload != null) {
				upload.abort();
			}
			recording.completionFuture().completeExceptionally(failure);
			releaseBotCameraIfNeeded(recording.server(), recording.botUuid(), recording.resetCameraOnFinish());
		}
	}

	private static void receiveRemoteVideoChunk(
			ServerPlayer sender,
			RendererBotPayloads.RendererBotVideoFileChunkC2SPayload payload
	) {
		if (sender == null || payload == null || payload.requestId() == null || payload.bytes() == null
				|| payload.bytes().length == 0 || payload.bytes().length > 256 * 1024 || payload.chunkIndex() < 0) {
			return;
		}
		PendingVideoRecording recording = PENDING_VIDEO_RECORDINGS.get(payload.requestId());
		if (recording == null || !sender.getUUID().equals(recording.botUuid())) {
			return;
		}
		try {
			RemoteVideoUpload upload = REMOTE_VIDEO_UPLOADS.computeIfAbsent(payload.requestId(), ignored -> RemoteVideoUpload.create(payload.requestId(), sender.getUUID()));
			if (!upload.accept(sender.getUUID(), payload)) {
				failVideoRecording(payload.requestId(), recording, "Некорректная последовательность частей MP4 от renderer-клиента");
			}
		} catch (IOException exception) {
			failVideoRecording(payload.requestId(), recording, "Не удалось сохранить MP4 от удалённого renderer-клиента");
		}
	}

	private static Path consumeCompletedRemoteVideo(PendingVideoRecording recording, UUID requestId) {
		RemoteVideoUpload upload = requestId == null ? null : REMOTE_VIDEO_UPLOADS.remove(requestId);
		if (upload == null || recording == null || !upload.isCompleteFor(recording.botUuid())) {
			return null;
		}
		return upload.completedPath();
	}

	private static boolean botHasActiveJobs(UUID botUuid) {
		if (botUuid == null) {
			return false;
		}
		for (PendingCapture capture : PENDING_CAPTURES.values()) {
			if (capture != null && botUuid.equals(capture.botUuid()) && !capture.isDone()) {
				return true;
			}
		}
		for (PendingMapTileCapture capture : PENDING_MAP_TILE_CAPTURES.values()) {
			if (capture != null && botUuid.equals(capture.botUuid()) && !capture.pixelsFuture().isDone()) {
				return true;
			}
		}
		for (PendingVideoRecording recording : PENDING_VIDEO_RECORDINGS.values()) {
			if (recording != null && botUuid.equals(recording.botUuid()) && !recording.completionFuture().isDone()) {
				return true;
			}
		}
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream != null && botUuid.equals(stream.botUuid())) {
				return true;
			}
		}
		for (ActiveAudioCapture capture : ACTIVE_AUDIO_CAPTURES.values()) {
			if (capture != null && botUuid.equals(capture.botUuid())) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasActiveVideoRecording(UUID botUuid) {
		PendingVideoRecording recording = selectVideoRecordingForRenderer(botUuid);
		return recording != null && recording.clientStartSent();
	}

	private static boolean isEntityWithinAnyTrackingTarget(MinecraftServer server, UUID botUuid, Entity entity, double horizontalRangeSq) {
		if (server == null || botUuid == null || entity == null || horizontalRangeSq <= 0.0D) {
			return false;
		}

		for (PendingCapture capture : PENDING_CAPTURES.values()) {
			if (capture == null || !botUuid.equals(capture.botUuid()) || capture.isDone()) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, capture.dimension(), capture.x(), capture.y(), capture.z(), capture.yaw(), capture.pitch(), capture.followEntityUuid());
			if (isEntityWithinTrackingTarget(entity, target, horizontalRangeSq)) {
				return true;
			}
		}
		for (PendingVideoRecording recording : PENDING_VIDEO_RECORDINGS.values()) {
			if (recording == null || !botUuid.equals(recording.botUuid()) || recording.completionFuture().isDone()) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, recording.dimension(), recording.x(), recording.y(), recording.z(), recording.yaw(), recording.pitch(), recording.followEntityUuid());
			if (isEntityWithinTrackingTarget(entity, target, horizontalRangeSq)) {
				return true;
			}
		}
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || !botUuid.equals(stream.botUuid())) {
				continue;
			}
			LiveStreamSpec spec = stream.spec();
			ScheduledServiceTarget target = resolveServiceTarget(
					server,
					spec.dimension(),
					spec.expectedX(),
					spec.expectedY(),
					spec.expectedZ(),
					spec.expectedYaw(),
					spec.expectedPitch(),
					spec.followEntityUuid()
			);
			if (isEntityWithinTrackingTarget(entity, target, horizontalRangeSq)) {
				return true;
			}
		}
		for (ActiveAudioCapture capture : ACTIVE_AUDIO_CAPTURES.values()) {
			if (capture == null || !botUuid.equals(capture.botUuid())) {
				continue;
			}
			AudioCaptureSpec spec = capture.spec();
			ScheduledServiceTarget target = resolveServiceTarget(server, spec.dimension(), spec.x(), spec.y(), spec.z(), 0.0F, 0.0F, null);
			if (isEntityWithinTrackingTarget(entity, target, horizontalRangeSq)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isEntityWithinTrackingTarget(Entity entity, ScheduledServiceTarget target, double horizontalRangeSq) {
		if (entity == null || target == null || target.level() == null || entity.level() != target.level()) {
			return false;
		}
		double dx = entity.getX() - target.x();
		double dz = entity.getZ() - target.z();
		return dx * dx + dz * dz <= horizontalRangeSq;
	}

	private static boolean isWithinActiveBotZone(MinecraftServer server, UUID botUuid, ServerLevel level, double x, double y, double z) {
		if (server == null || botUuid == null || level == null) {
			return false;
		}
		ServerPlayer bot = server.getPlayerList().getPlayer(botUuid);
		if (bot == null) {
			return false;
		}
		if (!botHasActiveJobs(botUuid)) {
			return true;
		}
		if (!(bot.level() instanceof ServerLevel botLevel) || !botLevel.dimension().equals(level.dimension())) {
			return false;
		}
		double dx = bot.getX() - x;
		double dy = bot.getY() - y;
		double dz = bot.getZ() - z;
		return dx * dx + dy * dy + dz * dz <= SHARED_RENDER_RADIUS_SQ;
	}

	private static boolean isWithinLiveStreamBotZone(MinecraftServer server, UUID botUuid, ServerLevel level, BlockPos cameraPos, double x, double y, double z) {
		if (cameraPos == null) {
			return isWithinActiveBotZone(server, botUuid, level, x, y, z);
		}
		if (server == null || botUuid == null || level == null) {
			return false;
		}
		ServerPlayer bot = server.getPlayerList().getPlayer(botUuid);
		if (bot == null) {
			return false;
		}
		if (!botHasActiveJobs(botUuid)) {
			return true;
		}
		return bot.level() == level;
	}

	private static ScheduledServiceTarget resolveAnyActiveBotTarget(MinecraftServer server, UUID botUuid) {
		if (server == null || botUuid == null) {
			return null;
		}
		for (PendingVideoRecording recording : PENDING_VIDEO_RECORDINGS.values()) {
			if (recording == null || !botUuid.equals(recording.botUuid()) || recording.completionFuture().isDone()) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, recording.dimension(), recording.x(), recording.y(), recording.z(), recording.yaw(), recording.pitch(), recording.followEntityUuid());
			if (target != null) {
				return target;
			}
		}
		for (ActiveLiveStream stream : ACTIVE_LIVE_STREAMS.values()) {
			if (stream == null || !botUuid.equals(stream.botUuid())) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, stream.spec().dimension(), stream.spec().expectedX(), stream.spec().expectedY(), stream.spec().expectedZ(), stream.spec().expectedYaw(), stream.spec().expectedPitch(), stream.spec().followEntityUuid());
			if (target != null) {
				return target;
			}
		}
		for (ActiveAudioCapture capture : ACTIVE_AUDIO_CAPTURES.values()) {
			if (capture == null || !botUuid.equals(capture.botUuid())) {
				continue;
			}
			AudioCaptureSpec spec = capture.spec();
			ScheduledServiceTarget target = resolveServiceTarget(server, spec.dimension(), spec.x(), spec.y(), spec.z(), 0.0F, 0.0F, null);
			if (target != null) {
				return target;
			}
		}
		for (PendingCapture capture : PENDING_CAPTURES.values()) {
			if (capture == null || !botUuid.equals(capture.botUuid()) || capture.isDone()) {
				continue;
			}
			ScheduledServiceTarget target = resolveServiceTarget(server, capture.dimension(), capture.x(), capture.y(), capture.z(), capture.yaw(), capture.pitch(), capture.followEntityUuid());
			if (target != null) {
				return target;
			}
		}
		return null;
	}

	private record VirtualChunkTrackingView(LongSet chunks) implements ChunkTrackingView {
		private VirtualChunkTrackingView(LongSet chunks) {
			this.chunks = chunks == null ? new LongOpenHashSet() : new LongOpenHashSet(chunks);
		}

		@Override
		public boolean contains(int x, int z, boolean includeEdge) {
			return this.chunks.contains(new ChunkPos(x, z).toLong());
		}

		@Override
		public void forEach(Consumer<ChunkPos> consumer) {
			if (consumer == null || this.chunks.isEmpty()) {
				return;
			}
			LongIterator iterator = this.chunks.iterator();
			while (iterator.hasNext()) {
				consumer.accept(new ChunkPos(iterator.nextLong()));
			}
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) {
				return true;
			}
			if (!(object instanceof VirtualChunkTrackingView other)) {
				return false;
			}
			return Objects.equals(this.chunks, other.chunks);
		}

		@Override
		public int hashCode() {
			return this.chunks.hashCode();
		}
	}

	private record ChunkTicketKey(ResourceKey<Level> dimension, long chunkLong) {
	}

	private record CameraChunkTicketKey(ResourceKey<Level> dimension, long chunkLong, int radius) {
	}

	private record ShadowSyncKey(UUID botUuid, UUID sessionId) {
	}

	private static final class ShadowDesiredState {
		private final UUID sessionId;
		private final ServerLevel level;
		private final List<ScheduledServiceTarget> targets = new ArrayList<>();
		private final Set<UUID> hiddenEntityUuids = new HashSet<>();
		private final LongOpenHashSet trackedChunks = new LongOpenHashSet();
		private final Map<CameraChunkTicketKey, Integer> chunkTickets = new HashMap<>();
		private final Map<Long, byte[]> readOnlyChunkPayloads = new HashMap<>();
		private boolean itemDisplaysOnly;
		// A map session is a packet-only MCA snapshot.  This extra state is a
		// hard safety boundary: even if a future caller accidentally adds a
		// normal target to it, syncShadowChunkTickets must never turn it into a
		// server loading ticket and generate terrain.
		private boolean readOnlyMapOnly;
		private int requestedViewDistance;
		private int viewDistance = 2;
		private int centerChunkX;
		private int centerChunkZ;
		private int minChunkX;
		private int minChunkZ;
		private int maxChunkX;
		private int maxChunkZ;

		private ShadowDesiredState(UUID sessionId, ServerLevel level) {
			this.sessionId = sessionId;
			this.level = level;
			this.centerChunkX = 0;
			this.centerChunkZ = 0;
			this.minChunkX = 0;
			this.minChunkZ = 0;
			this.maxChunkX = 0;
			this.maxChunkZ = 0;
		}

		private UUID sessionId() {
			return this.sessionId;
		}

		private ServerLevel level() {
			return this.level;
		}

		private int viewDistance() {
			return this.viewDistance;
		}

		private int centerChunkX() {
			return this.centerChunkX;
		}

		private int centerChunkZ() {
			return this.centerChunkZ;
		}

		private List<ScheduledServiceTarget> targets() {
			return this.targets;
		}

		private Set<UUID> hiddenEntityUuids() {
			return this.hiddenEntityUuids;
		}

		private boolean itemDisplaysOnly() {
			return this.itemDisplaysOnly;
		}

		private void setItemDisplaysOnly(boolean itemDisplaysOnly) {
			this.itemDisplaysOnly = itemDisplaysOnly;
		}

		private LongOpenHashSet trackedChunks() {
			return this.trackedChunks;
		}

		private Map<CameraChunkTicketKey, Integer> chunkTickets() {
			return this.chunkTickets;
		}

		private boolean readOnlyMapOnly() {
			return this.readOnlyMapOnly;
		}

		private byte[] readOnlyChunkPayload(long chunkLong) {
			return this.readOnlyChunkPayloads.get(chunkLong);
		}

		private int minChunkX() {
			return this.minChunkX;
		}

		private int minChunkZ() {
			return this.minChunkZ;
		}

		private int maxChunkX() {
			return this.maxChunkX;
		}

		private int maxChunkZ() {
			return this.maxChunkZ;
		}

		private void addTarget(
				ScheduledServiceTarget target,
				int viewDistance,
				Set<UUID> hiddenEntityUuids,
				boolean omnidirectionalChunkLoading,
				boolean staticCameraChunkBuffer
		) {
			addTarget(target, viewDistance, hiddenEntityUuids, omnidirectionalChunkLoading, staticCameraChunkBuffer, true);
		}

		private void addPassiveTarget(
				ScheduledServiceTarget target,
				int viewDistance,
				Set<UUID> hiddenEntityUuids,
				boolean omnidirectionalChunkLoading,
				boolean staticCameraChunkBuffer
		) {
			addTarget(target, viewDistance, hiddenEntityUuids, omnidirectionalChunkLoading, staticCameraChunkBuffer, false);
		}

		private void addReadOnlyMapTarget(ScheduledServiceTarget target, int viewDistance, Map<Long, byte[]> chunkPayloads) {
			if (target == null || target.level() != this.level) {
				return;
			}
			this.readOnlyMapOnly = true;
			this.targets.add(target);
			this.requestedViewDistance = Math.max(this.requestedViewDistance, Math.max(2, viewDistance));
			if (chunkPayloads != null) {
				for (Map.Entry<Long, byte[]> entry : chunkPayloads.entrySet()) {
					Long chunkLong = entry.getKey();
					byte[] payload = entry.getValue();
					if (chunkLong == null || payload == null || payload.length == 0) {
						continue;
					}
					this.trackedChunks.add(chunkLong);
					this.readOnlyChunkPayloads.put(chunkLong, payload);
				}
			}
			recomputeViewWindow(target);
		}

		private void addTarget(
				ScheduledServiceTarget target,
				int viewDistance,
				Set<UUID> hiddenEntityUuids,
				boolean omnidirectionalChunkLoading,
				boolean staticCameraChunkBuffer,
				boolean addChunkTicket
		) {
			if (target == null || target.level() != this.level) {
				return;
			}
			this.targets.add(target);
			this.requestedViewDistance = Math.max(this.requestedViewDistance, Math.max(2, viewDistance));
			if (hiddenEntityUuids != null && !hiddenEntityUuids.isEmpty()) {
				this.hiddenEntityUuids.addAll(hiddenEntityUuids);
			}
			appendVirtualTargetChunks(
					this.trackedChunks,
					this.level,
					target,
					Math.max(2, viewDistance),
					omnidirectionalChunkLoading,
					staticCameraChunkBuffer
			);
			if (addChunkTicket) {
				addCameraChunkTicket(
						this.chunkTickets,
						this.level.dimension(),
						chunkPosAt(target.x(), target.z()),
						Math.max(2, viewDistance)
				);
			}
			recomputeViewWindow(target);
		}

		private void recomputeViewWindow(ScheduledServiceTarget fallbackTarget) {
			if (this.trackedChunks.isEmpty()) {
				ScheduledServiceTarget target = fallbackTarget;
				if (target != null) {
					this.centerChunkX = SectionPos.blockToSectionCoord(Mth.floor(target.x()));
					this.centerChunkZ = SectionPos.blockToSectionCoord(Mth.floor(target.z()));
				}
				this.minChunkX = this.centerChunkX;
				this.minChunkZ = this.centerChunkZ;
				this.maxChunkX = this.centerChunkX;
				this.maxChunkZ = this.centerChunkZ;
				this.viewDistance = Math.max(2, this.requestedViewDistance);
				return;
			}

			int minX = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;
			LongIterator iterator = this.trackedChunks.iterator();
			while (iterator.hasNext()) {
				ChunkPos pos = new ChunkPos(iterator.nextLong());
				minX = Math.min(minX, pos.x);
				minZ = Math.min(minZ, pos.z);
				maxX = Math.max(maxX, pos.x);
				maxZ = Math.max(maxZ, pos.z);
			}
			this.minChunkX = minX;
			this.minChunkZ = minZ;
			this.maxChunkX = maxX;
			this.maxChunkZ = maxZ;
			this.centerChunkX = Math.floorDiv(minX + maxX, 2);
			this.centerChunkZ = Math.floorDiv(minZ + maxZ, 2);

			int requiredRadius = 0;
			iterator = this.trackedChunks.iterator();
			while (iterator.hasNext()) {
				ChunkPos pos = new ChunkPos(iterator.nextLong());
				requiredRadius = Math.max(
						requiredRadius,
						Math.max(Math.abs(pos.x - this.centerChunkX), Math.abs(pos.z - this.centerChunkZ))
				);
			}
			this.viewDistance = Mth.clamp(Math.max(this.requestedViewDistance, requiredRadius), 2, 32);
		}
	}

	private static final class ShadowDimensionSyncState {
		private final UUID botUuid;
		private final UUID sessionId;
		private final ResourceKey<Level> dimension;
		private final LongOpenHashSet trackedChunks = new LongOpenHashSet();
		private final Map<Integer, ShadowTrackedEntity> trackedEntities = new HashMap<>();
		private boolean initialized;
		private String dimensionTypeId;
		private long seed;
		private int viewDistance;
		private long lastGameTime = Long.MIN_VALUE;
		private long lastDayTime = Long.MIN_VALUE;
		private boolean lastTickDayTime;
		private boolean lastRaining;
		private float lastRainLevel = Float.NaN;
		private float lastThunderLevel = Float.NaN;
		private int lastCenterChunkX = Integer.MIN_VALUE;
		private int lastCenterChunkZ = Integer.MIN_VALUE;
		private boolean contentReadyPending = true;
		private long contentReadyRevision;

		private ShadowDimensionSyncState(UUID botUuid, UUID sessionId, ResourceKey<Level> dimension) {
			this.botUuid = botUuid;
			this.sessionId = sessionId;
			this.dimension = dimension;
		}

		private UUID botUuid() {
			return this.botUuid;
		}

		private UUID sessionId() {
			return this.sessionId;
		}

		private ResourceKey<Level> dimension() {
			return this.dimension;
		}

		private LongOpenHashSet trackedChunks() {
			return this.trackedChunks;
		}

		private Map<Integer, ShadowTrackedEntity> trackedEntities() {
			return this.trackedEntities;
		}

		private boolean initialized() {
			return this.initialized;
		}

		private void setInitialized(boolean initialized) {
			this.initialized = initialized;
		}

		private String dimensionTypeId() {
			return this.dimensionTypeId;
		}

		private void setDimensionTypeId(String dimensionTypeId) {
			this.dimensionTypeId = dimensionTypeId;
		}

		private long seed() {
			return this.seed;
		}

		private void setSeed(long seed) {
			this.seed = seed;
		}

		private int viewDistance() {
			return this.viewDistance;
		}

		private void setViewDistance(int viewDistance) {
			this.viewDistance = viewDistance;
		}

		private long lastGameTime() {
			return this.lastGameTime;
		}

		private void setLastGameTime(long lastGameTime) {
			this.lastGameTime = lastGameTime;
		}

		private long lastDayTime() {
			return this.lastDayTime;
		}

		private void setLastDayTime(long lastDayTime) {
			this.lastDayTime = lastDayTime;
		}

		private boolean lastTickDayTime() {
			return this.lastTickDayTime;
		}

		private void setLastTickDayTime(boolean lastTickDayTime) {
			this.lastTickDayTime = lastTickDayTime;
		}

		private boolean lastRaining() {
			return this.lastRaining;
		}

		private void setLastRaining(boolean lastRaining) {
			this.lastRaining = lastRaining;
		}

		private float lastRainLevel() {
			return this.lastRainLevel;
		}

		private void setLastRainLevel(float lastRainLevel) {
			this.lastRainLevel = lastRainLevel;
		}

		private float lastThunderLevel() {
			return this.lastThunderLevel;
		}

		private void setLastThunderLevel(float lastThunderLevel) {
			this.lastThunderLevel = lastThunderLevel;
		}

		private int lastCenterChunkX() {
			return this.lastCenterChunkX;
		}

		private void setLastCenterChunkX(int lastCenterChunkX) {
			this.lastCenterChunkX = lastCenterChunkX;
		}

		private int lastCenterChunkZ() {
			return this.lastCenterChunkZ;
		}

		private void setLastCenterChunkZ(int lastCenterChunkZ) {
			this.lastCenterChunkZ = lastCenterChunkZ;
		}

		private void forceViewResend() {
			this.lastCenterChunkX = Integer.MIN_VALUE;
			this.lastCenterChunkZ = Integer.MIN_VALUE;
		}

		private void markContentPending() {
			this.contentReadyPending = true;
		}

		private boolean contentReadyPending() {
			return this.contentReadyPending;
		}

		private long publishContentReady() {
			this.contentReadyPending = false;
			return ++this.contentReadyRevision;
		}
	}

	private record ShadowTrackedEntity(
			Entity entity,
			net.minecraft.server.level.ServerEntity serverEntity,
			ShadowPacketCollector collector
	) {
	}

	private static final class ShadowPacketCollector implements net.minecraft.server.level.ServerEntity.Synchronizer {
		private final ServerPlayer bot;
		private final List<Packet<? extends ClientGamePacketListener>> packets = new ArrayList<>();

		private ShadowPacketCollector(ServerPlayer bot) {
			this.bot = bot;
		}

		@Override
		public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
			add(packet);
		}

		@Override
		public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
			add(packet);
		}

		@Override
		public void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet, java.util.function.Predicate<ServerPlayer> predicate) {
			if (predicate == null || this.bot == null || predicate.test(this.bot)) {
				add(packet);
			}
		}

		private void clear() {
			this.packets.clear();
		}

		private List<Packet<? extends ClientGamePacketListener>> drain() {
			List<Packet<? extends ClientGamePacketListener>> drained = new ArrayList<>(this.packets);
			this.packets.clear();
			return drained;
		}

		@SuppressWarnings("unchecked")
		private void add(Packet<? super ClientGamePacketListener> packet) {
			if (packet == null) {
				return;
			}
			this.packets.add((Packet<? extends ClientGamePacketListener>) packet);
		}
	}

	public record ClientCaptureHandle(
			UUID requestId,
			CompletableFuture<byte[]> previewFuture,
			CompletableFuture<byte[]> fullFuture
	) {
		public byte[] awaitPreview() {
			return await(this.previewFuture);
		}

		public byte[] awaitFull() {
			return await(this.fullFuture);
		}

		private static byte[] await(CompletableFuture<byte[]> future) {
			try {
				return future.get();
			} catch (InterruptedException interruptedException) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Renderer bot capture interrupted", interruptedException);
			} catch (ExecutionException executionException) {
				Throwable cause = executionException.getCause();
				throw new IllegalStateException(
						cause != null ? cause.getMessage() : "Renderer bot capture failed",
						cause != null ? cause : executionException
				);
			}
		}
	}

	public record LiveStreamFrame(byte[] pixels, long clientFrameNanos, long receivedAtNanos) {
	}

	public record AudioCaptureFrame(short[] samples, long receivedAtNanos, long clientFrameNanos) {
	}

	private record BotHandshake(UUID playerUuid, String playerName, boolean volunteerRenderer) {
	}

	private static final class RemoteVideoUpload {
		private final UUID senderUuid;
		private final Path temporaryPath;
		private final Path completedPath;
		private int nextChunkIndex;
		private long bytesWritten;
		private boolean complete;

		private RemoteVideoUpload(UUID senderUuid, Path temporaryPath, Path completedPath) {
			this.senderUuid = senderUuid;
			this.temporaryPath = temporaryPath;
			this.completedPath = completedPath;
			this.nextChunkIndex = 0;
			this.bytesWritten = 0L;
			this.complete = false;
		}

		private static RemoteVideoUpload create(UUID requestId, UUID senderUuid) {
			try {
				CameraMediaCache.ensureVideoParent(requestId.toString());
				Path completed = CameraMediaCache.videoSourcePath(requestId.toString());
				Path temporary = completed.resolveSibling(completed.getFileName().toString() + ".upload");
				Files.deleteIfExists(temporary);
				return new RemoteVideoUpload(senderUuid, temporary, completed);
			} catch (IOException exception) {
				throw new IllegalStateException("Unable to create remote video upload", exception);
			}
		}

		private synchronized boolean accept(UUID sender, RendererBotPayloads.RendererBotVideoFileChunkC2SPayload chunk) throws IOException {
			if (this.complete || !this.senderUuid.equals(sender) || chunk.chunkIndex() != this.nextChunkIndex
					|| this.bytesWritten + chunk.bytes().length > MAX_REMOTE_VIDEO_UPLOAD_BYTES) {
				return false;
			}
			Files.write(this.temporaryPath, chunk.bytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			this.bytesWritten += chunk.bytes().length;
			this.nextChunkIndex++;
			if (chunk.finalChunk()) {
				try {
					Files.move(this.temporaryPath, this.completedPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException ignored) {
					Files.move(this.temporaryPath, this.completedPath, StandardCopyOption.REPLACE_EXISTING);
				}
				this.complete = true;
			}
			return true;
		}

		private synchronized boolean isCompleteFor(UUID sender) {
			return this.complete && this.senderUuid.equals(sender) && this.bytesWritten > 0L && Files.isRegularFile(this.completedPath);
		}

		private Path completedPath() {
			return this.completedPath;
		}

		private synchronized void abort() {
			if (this.complete) {
				return;
			}
			try {
				Files.deleteIfExists(this.temporaryPath);
			} catch (IOException ignored) {
			}
		}
	}

	private static final class PendingCapture {
		private final UUID requestId;
		private final UUID renderSessionId;
		private final MinecraftServer server;
		private final UUID botUuid;
		private final boolean resetCameraOnFinish;
		private final ResourceKey<Level> dimension;
		private final double x;
		private final double y;
		private final double z;
		private final float yaw;
		private final float pitch;
		private final int fovDegrees;
		private final UUID followEntityUuid;
		private final UUID feedbackPlayerId;
		private final Vec3 feedbackShutterOrigin;
		private final int previewWidth;
		private final int previewHeight;
		private final int fullWidth;
		private final int fullHeight;
		private final CompletableFuture<byte[]> previewFuture;
		private final CompletableFuture<byte[]> fullFuture;
		private final long queuedAtMillis;
		private volatile long lastDispatchAtMillis;
		private volatile boolean clientRequestSent;
		private volatile boolean timeoutArmed;

		private PendingCapture(
				UUID requestId,
				UUID renderSessionId,
				MinecraftServer server,
				UUID botUuid,
				boolean resetCameraOnFinish,
				ResourceKey<Level> dimension,
				double x,
				double y,
				double z,
				float yaw,
				float pitch,
				int fovDegrees,
				UUID followEntityUuid,
				UUID feedbackPlayerId,
				Vec3 feedbackShutterOrigin,
				int previewWidth,
				int previewHeight,
				int fullWidth,
				int fullHeight,
				CompletableFuture<byte[]> previewFuture,
				CompletableFuture<byte[]> fullFuture
		) {
			this.requestId = requestId;
			this.renderSessionId = renderSessionId;
			this.server = server;
			this.botUuid = botUuid;
			this.resetCameraOnFinish = resetCameraOnFinish;
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.fovDegrees = fovDegrees;
			this.followEntityUuid = followEntityUuid;
			this.feedbackPlayerId = feedbackPlayerId;
			this.feedbackShutterOrigin = feedbackShutterOrigin;
			this.previewWidth = previewWidth;
			this.previewHeight = previewHeight;
			this.fullWidth = fullWidth;
			this.fullHeight = fullHeight;
			this.previewFuture = previewFuture;
			this.fullFuture = fullFuture;
			this.queuedAtMillis = System.currentTimeMillis();
			this.lastDispatchAtMillis = 0L;
			this.clientRequestSent = false;
			this.timeoutArmed = false;
		}

		private UUID requestId() {
			return this.requestId;
		}

		private MinecraftServer server() {
			return this.server;
		}

		private UUID renderSessionId() {
			return this.renderSessionId;
		}

		private UUID botUuid() {
			return this.botUuid;
		}

		private boolean resetCameraOnFinish() {
			return this.resetCameraOnFinish;
		}

		private ResourceKey<Level> dimension() {
			return this.dimension;
		}

		private double x() {
			return this.x;
		}

		private double y() {
			return this.y;
		}

		private double z() {
			return this.z;
		}

		private float yaw() {
			return this.yaw;
		}

		private float pitch() {
			return this.pitch;
		}

		private int fovDegrees() {
			return this.fovDegrees;
		}

		private UUID followEntityUuid() {
			return this.followEntityUuid;
		}

		private UUID feedbackPlayerId() {
			return this.feedbackPlayerId;
		}

		private Vec3 feedbackShutterOrigin() {
			return this.feedbackShutterOrigin;
		}

		private int previewWidth() {
			return this.previewWidth;
		}

		private int previewHeight() {
			return this.previewHeight;
		}

		private int fullWidth() {
			return this.fullWidth;
		}

		private int fullHeight() {
			return this.fullHeight;
		}

		private long queuedAtMillis() {
			return this.queuedAtMillis;
		}

		private boolean clientRequestSent() {
			return this.clientRequestSent;
		}

		private void markClientRequestSent() {
			this.clientRequestSent = true;
		}

		private synchronized boolean markTimeoutArmed() {
			if (this.timeoutArmed) {
				return false;
			}
			this.timeoutArmed = true;
			return true;
		}

		private CompletableFuture<byte[]> previewFuture() {
			return this.previewFuture;
		}

		private CompletableFuture<byte[]> fullFuture() {
			return this.fullFuture;
		}

		private boolean isDone() {
			return this.previewFuture.isDone() && this.fullFuture.isDone();
		}

		private long nextDueAtMillis() {
			return this.lastDispatchAtMillis <= 0L ? 0L : this.lastDispatchAtMillis + PHOTO_CAPTURE_RETRY_INTERVAL_MS;
		}

		private void markDispatched(long now) {
			this.lastDispatchAtMillis = now;
		}
	}

	private static final class PendingMapTileCapture {
		private final UUID requestId;
		private final UUID renderSessionId;
		private final MinecraftServer server;
		private final UUID botUuid;
		private final ResourceKey<Level> dimension;
		private final double centerX;
		private final double centerZ;
		private final int lod;
		private final long tileX;
		private final long tileZ;
		private final int tileSize;
		private final double blocksPerPixel;
		private final List<ChunkPos> sourceChunks;
		private final List<ChunkPos> readOnlyShadowChunks;
		private final int priorityScore;
		private final boolean activeView;
		private final long queuedAtMillis;
		private final long sequence;
		private final double worldCenterDistanceSquared;
		private final CompletableFuture<byte[]> pixelsFuture;
		private final Map<Long, byte[]> readOnlyChunkPayloads = new ConcurrentHashMap<>();
		private volatile boolean readOnlyChunkLoadStarted;
		private volatile boolean readOnlyChunkLoadFinished;
		private volatile boolean clientRequestSent;
		private volatile boolean clientTimeoutArmed;
		private volatile long shadowTargetStartedAtMillis;
		private volatile long shadowTargetLastActiveAtMillis;
		private volatile long clientRequestSentAtMillis;
		private volatile long lastShadowResyncAtMillis;

		private PendingMapTileCapture(
				UUID requestId,
				UUID renderSessionId,
				MinecraftServer server,
				UUID botUuid,
				ResourceKey<Level> dimension,
				double centerX,
				double centerZ,
				int lod,
				long tileX,
				long tileZ,
				int tileSize,
				double blocksPerPixel,
				List<ChunkPos> sourceChunks,
				int priorityScore,
				boolean activeView,
				long queuedAtMillis,
				long sequence,
				double worldCenterDistanceSquared,
				CompletableFuture<byte[]> pixelsFuture
		) {
			this.requestId = requestId;
			this.renderSessionId = renderSessionId;
			this.server = server;
			this.botUuid = botUuid;
			this.dimension = dimension;
			this.centerX = centerX;
			this.centerZ = centerZ;
			this.lod = lod;
			this.tileX = tileX;
			this.tileZ = tileZ;
			this.tileSize = tileSize;
			this.blocksPerPixel = blocksPerPixel;
			this.sourceChunks = sourceChunks == null ? List.of() : List.copyOf(sourceChunks);
			this.readOnlyShadowChunks = readOnlyMapShadowChunks(centerX, centerZ, tileSize, blocksPerPixel, this.sourceChunks);
			this.priorityScore = priorityScore;
			this.activeView = activeView;
			this.queuedAtMillis = queuedAtMillis;
			this.sequence = sequence;
			this.worldCenterDistanceSquared = worldCenterDistanceSquared;
			this.pixelsFuture = pixelsFuture;
			this.readOnlyChunkLoadStarted = false;
			this.readOnlyChunkLoadFinished = false;
			this.clientRequestSent = false;
			this.clientTimeoutArmed = false;
			this.shadowTargetStartedAtMillis = 0L;
			this.shadowTargetLastActiveAtMillis = 0L;
			this.clientRequestSentAtMillis = 0L;
			this.lastShadowResyncAtMillis = 0L;
		}

		private UUID requestId() {
			return this.requestId;
		}

		private UUID renderSessionId() {
			return this.renderSessionId;
		}

		private MinecraftServer server() {
			return this.server;
		}

		private UUID botUuid() {
			return this.botUuid;
		}

		private ResourceKey<Level> dimension() {
			return this.dimension;
		}

		private double centerX() {
			return this.centerX;
		}

		private double centerZ() {
			return this.centerZ;
		}

		private int lod() {
			return this.lod;
		}

		private long tileX() {
			return this.tileX;
		}

		private long tileZ() {
			return this.tileZ;
		}

		private int tileSize() {
			return this.tileSize;
		}

		private double blocksPerPixel() {
			return this.blocksPerPixel;
		}

		private List<ChunkPos> sourceChunks() {
			return this.sourceChunks;
		}

		private List<ChunkPos> readOnlyShadowChunks() {
			return this.readOnlyShadowChunks;
		}

		private boolean isRequiredSourceChunk(ChunkPos pos) {
			return pos != null && this.sourceChunks.contains(pos);
		}

		private boolean beginReadOnlyChunkLoad() {
			if (this.readOnlyChunkLoadStarted || this.pixelsFuture.isDone()) {
				return false;
			}
			this.readOnlyChunkLoadStarted = true;
			return true;
		}

		private void completeReadOnlyChunkLoad(Map<Long, byte[]> payloads) {
			if (payloads == null || payloads.size() != this.readOnlyShadowChunks.size() || this.pixelsFuture.isDone()) {
				return;
			}
			this.readOnlyChunkPayloads.clear();
			this.readOnlyChunkPayloads.putAll(payloads);
			this.readOnlyChunkLoadFinished = true;
		}

		private boolean hasReadOnlyChunkPayloads() {
			return this.readOnlyChunkLoadFinished
					&& this.readOnlyChunkPayloads.size() == this.readOnlyShadowChunks.size();
		}

		private byte[] readOnlyChunkPayload(long chunkLong) {
			return this.readOnlyChunkPayloads.get(chunkLong);
		}

		private Map<Long, byte[]> readOnlyChunkPayloads() {
			return hasReadOnlyChunkPayloads() ? Map.copyOf(this.readOnlyChunkPayloads) : Map.of();
		}

		private int priorityScore() {
			return this.priorityScore;
		}

		private boolean activeView() {
			return this.activeView;
		}

		private long queuedAtMillis() {
			return this.queuedAtMillis;
		}

		private long sequence() {
			return this.sequence;
		}

		private double worldCenterDistanceSquared() {
			return this.worldCenterDistanceSquared;
		}

		private boolean clientRequestSent() {
			return this.clientRequestSent;
		}

		private void markShadowTargetActive(long now) {
			if (now <= 0L || this.clientRequestSent) {
				return;
			}
			// If a higher-priority target temporarily took the shared session, this
			// capture starts a fresh chunk-load attempt when it regains ownership.
			if (this.shadowTargetLastActiveAtMillis <= 0L || now - this.shadowTargetLastActiveAtMillis > 250L) {
				this.shadowTargetStartedAtMillis = now;
			}
			this.shadowTargetLastActiveAtMillis = now;
		}

		private boolean sourceReadTimedOut(long now) {
			return !this.clientRequestSent
					&& this.shadowTargetStartedAtMillis > 0L
					&& now - this.shadowTargetStartedAtMillis >= MAP_TILE_SOURCE_READ_TIMEOUT_MS;
		}

		private void markClientRequestSent(long now) {
			this.clientRequestSent = true;
			this.clientRequestSentAtMillis = Math.max(1L, now);
		}

		private synchronized void armClientTimeout() {
			if (this.clientTimeoutArmed || this.pixelsFuture.isDone()) {
				return;
			}
			this.clientTimeoutArmed = true;
			this.pixelsFuture.orTimeout(MAP_TILE_CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		}

		private boolean shouldResyncShadow(long now) {
			return this.clientRequestSent
					&& this.clientRequestSentAtMillis > 0L
					&& now - this.clientRequestSentAtMillis >= MAP_TILE_SHADOW_RESYNC_INTERVAL_MS
					&& (this.lastShadowResyncAtMillis <= 0L || now - this.lastShadowResyncAtMillis >= MAP_TILE_SHADOW_RESYNC_INTERVAL_MS);
		}

		private void markShadowResynced(long now) {
			this.lastShadowResyncAtMillis = now;
		}

		private CompletableFuture<byte[]> pixelsFuture() {
			return this.pixelsFuture;
		}
	}

	private static final class PendingItemIconCapture {
		private final UUID requestId;
		private final MinecraftServer server;
		private final UUID botUuid;
		private final int iconSize;
		private final CompletableFuture<byte[]> pixelsFuture;

		private PendingItemIconCapture(
				UUID requestId,
				MinecraftServer server,
				UUID botUuid,
				int iconSize,
				CompletableFuture<byte[]> pixelsFuture
		) {
			this.requestId = requestId;
			this.server = server;
			this.botUuid = botUuid;
			this.iconSize = iconSize;
			this.pixelsFuture = pixelsFuture;
		}

		private UUID requestId() {
			return this.requestId;
		}

		private MinecraftServer server() {
			return this.server;
		}

		private UUID botUuid() {
			return this.botUuid;
		}

		private int iconSize() {
			return this.iconSize;
		}

		private CompletableFuture<byte[]> pixelsFuture() {
			return this.pixelsFuture;
		}
	}

	private static final class PendingVideoRecording {
		private final UUID requestId;
		private final UUID renderSessionId;
		private final MinecraftServer server;
		private final UUID botUuid;
		private final boolean resetCameraOnFinish;
		private final ResourceKey<Level> dimension;
		private final double x;
		private final double y;
		private final double z;
		private final float yaw;
		private final float pitch;
		private final int fovDegrees;
		private final UUID followEntityUuid;
		private final int targetFps;
		private final int fullWidth;
		private final int fullHeight;
		private final int maxDurationSeconds;
		private final CompletableFuture<VideoRecordingResult> completionFuture;
		private final long queuedAtMillis;
		private volatile long lastDispatchAtMillis;
		private volatile boolean stopRequested;
		private volatile boolean clientStartSent;
		private volatile boolean timeoutArmed;

		private PendingVideoRecording(
				UUID requestId,
				UUID renderSessionId,
				MinecraftServer server,
				UUID botUuid,
				boolean resetCameraOnFinish,
				ResourceKey<Level> dimension,
				double x,
				double y,
				double z,
				float yaw,
				float pitch,
				int fovDegrees,
				UUID followEntityUuid,
				int targetFps,
				int fullWidth,
				int fullHeight,
				int maxDurationSeconds,
				CompletableFuture<VideoRecordingResult> completionFuture
		) {
			this.requestId = requestId;
			this.renderSessionId = renderSessionId;
			this.server = server;
			this.botUuid = botUuid;
			this.resetCameraOnFinish = resetCameraOnFinish;
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.fovDegrees = fovDegrees;
			this.followEntityUuid = followEntityUuid;
			this.targetFps = targetFps;
			this.fullWidth = fullWidth;
			this.fullHeight = fullHeight;
			this.maxDurationSeconds = maxDurationSeconds;
			this.completionFuture = completionFuture;
			this.queuedAtMillis = System.currentTimeMillis();
			this.lastDispatchAtMillis = 0L;
			this.stopRequested = false;
			this.clientStartSent = false;
			this.timeoutArmed = false;
		}

		private UUID requestId() {
			return this.requestId;
		}

		private MinecraftServer server() {
			return this.server;
		}

		private UUID renderSessionId() {
			return this.renderSessionId;
		}

		private UUID botUuid() {
			return this.botUuid;
		}

		private boolean resetCameraOnFinish() {
			return this.resetCameraOnFinish;
		}

		private ResourceKey<Level> dimension() {
			return this.dimension;
		}

		private double x() {
			return this.x;
		}

		private double y() {
			return this.y;
		}

		private double z() {
			return this.z;
		}

		private float yaw() {
			return this.yaw;
		}

		private float pitch() {
			return this.pitch;
		}

		private int fovDegrees() {
			return this.fovDegrees;
		}

		private UUID followEntityUuid() {
			return this.followEntityUuid;
		}

		private int targetFps() {
			return this.targetFps;
		}

		private int fullWidth() {
			return this.fullWidth;
		}

		private int fullHeight() {
			return this.fullHeight;
		}

		private int maxDurationSeconds() {
			return this.maxDurationSeconds;
		}

		private CompletableFuture<VideoRecordingResult> completionFuture() {
			return this.completionFuture;
		}

		private boolean stopRequested() {
			return this.stopRequested;
		}

		private long queuedAtMillis() {
			return this.queuedAtMillis;
		}

		private boolean clientStartSent() {
			return this.clientStartSent;
		}

		private void markClientStartSent() {
			this.clientStartSent = true;
		}

		private synchronized boolean markTimeoutArmed() {
			if (this.timeoutArmed) {
				return false;
			}
			this.timeoutArmed = true;
			return true;
		}

		private void markStopRequested() {
			this.stopRequested = true;
		}

		private long nextDueAtMillis() {
			long interval = Math.max(1L, 1_000L / Math.max(1, this.targetFps));
			return this.lastDispatchAtMillis <= 0L ? 0L : this.lastDispatchAtMillis + interval;
		}

		private void markDispatched(long now) {
			this.lastDispatchAtMillis = now;
		}
	}

	private record LiveStreamSpec(
			UUID renderSessionId,
			ResourceKey<Level> dimension,
			BlockPos cameraPos,
			double expectedX,
			double expectedY,
			double expectedZ,
			float expectedYaw,
			float expectedPitch,
			UUID followEntityUuid,
			Set<UUID> hiddenEntityUuids,
			boolean omnidirectionalChunkLoading,
			boolean hideCameraCollisionBlock,
			int fullWidth,
			int fullHeight,
			int fovDegrees,
			int targetFps
	) {
	}

	private static final class ActiveLiveStream {
		private final MinecraftServer server;
		private final UUID streamId;
		private final String ownerKey;
		private final UUID botUuid;
		private volatile LiveStreamSpec spec;
		private final Consumer<LiveStreamFrame> onFrame;
		private final Consumer<String> onFailure;
		private final Object frameDeliveryLock = new Object();
		private final long startedAtMillis;
		private volatile long lastFrameAtMillis;
		private volatile long lastDispatchAtMillis;
		private LiveStreamFrame pendingFrame;
		private long newestAcceptedClientFrameNanos;
		private boolean frameDeliveryScheduled;

		private ActiveLiveStream(
				MinecraftServer server,
				UUID streamId,
				String ownerKey,
				UUID botUuid,
				LiveStreamSpec spec,
				Consumer<LiveStreamFrame> onFrame,
				Consumer<String> onFailure
		) {
			this.server = server;
			this.streamId = streamId;
			this.ownerKey = ownerKey;
			this.botUuid = botUuid;
			this.spec = spec;
			this.onFrame = onFrame;
			this.onFailure = onFailure;
			this.startedAtMillis = System.currentTimeMillis();
			this.lastFrameAtMillis = 0L;
			this.lastDispatchAtMillis = 0L;
			this.pendingFrame = null;
			this.newestAcceptedClientFrameNanos = 0L;
			this.frameDeliveryScheduled = false;
		}

		private MinecraftServer server() {
			return this.server;
		}

		private UUID streamId() {
			return this.streamId;
		}

		private String ownerKey() {
			return this.ownerKey;
		}

		private UUID botUuid() {
			return this.botUuid;
		}

		private LiveStreamSpec spec() {
			return this.spec;
		}

		private void replaceSpec(LiveStreamSpec spec) {
			this.spec = spec;
		}

		private Consumer<LiveStreamFrame> onFrame() {
			return this.onFrame;
		}

		private Consumer<String> onFailure() {
			return this.onFailure;
		}

		private void offerFrame(LiveStreamFrame frame) {
			if (frame == null || frame.pixels() == null || frame.pixels().length == 0) {
				return;
			}
			boolean scheduleDelivery = false;
			synchronized (this.frameDeliveryLock) {
				long frameNanos = frame.clientFrameNanos() > 0L ? frame.clientFrameNanos() : frame.receivedAtNanos();
				// Frames may complete GPU readback out of order when the client has
				// several captures in flight.  Never let an older result overwrite
				// a newer picture on a monitor.
				if (frameNanos > 0L && frameNanos <= this.newestAcceptedClientFrameNanos) {
					return;
				}
				if (frameNanos > 0L) {
					this.newestAcceptedClientFrameNanos = frameNanos;
				}
				this.lastFrameAtMillis = System.currentTimeMillis();
				this.pendingFrame = frame;
				if (!this.frameDeliveryScheduled) {
					this.frameDeliveryScheduled = true;
					scheduleDelivery = true;
				}
			}
			if (scheduleDelivery) {
				LIVE_FRAME_DISPATCH_EXECUTOR.execute(this::deliverPendingFrames);
			}
		}

		private void deliverPendingFrames() {
			while (true) {
				LiveStreamFrame frame;
				synchronized (this.frameDeliveryLock) {
					frame = this.pendingFrame;
					this.pendingFrame = null;
					if (frame == null) {
						this.frameDeliveryScheduled = false;
						return;
					}
				}
				if (ACTIVE_LIVE_STREAMS.get(this.streamId) != this) {
					return;
				}
				try {
					this.onFrame.accept(frame);
				} catch (Exception exception) {
					Lg2.LOGGER.warn("Renderer bot live stream frame callback failed for {}", this.streamId, exception);
				}
			}
		}

		private void clearPendingFrames() {
			synchronized (this.frameDeliveryLock) {
				this.pendingFrame = null;
			}
		}

		private long nextDueAtMillis() {
			long interval = Math.max(1L, 1_000L / Math.max(1, this.spec.targetFps()));
			return this.lastDispatchAtMillis <= 0L ? 0L : this.lastDispatchAtMillis + interval;
		}

		private void markDispatched(long now) {
			this.lastDispatchAtMillis = now;
		}

		private boolean isStale() {
			long referenceTime = this.startedAtMillis;
			if (this.lastDispatchAtMillis > referenceTime) {
				referenceTime = this.lastDispatchAtMillis;
			}
			if (this.lastFrameAtMillis > referenceTime) {
				referenceTime = this.lastFrameAtMillis;
			}
			return System.currentTimeMillis() - referenceTime > LIVE_STREAM_STALE_MS;
		}

		private long lastActivityAtMillis() {
			long referenceTime = this.startedAtMillis;
			if (this.lastDispatchAtMillis > referenceTime) {
				referenceTime = this.lastDispatchAtMillis;
			}
			if (this.lastFrameAtMillis > referenceTime) {
				referenceTime = this.lastFrameAtMillis;
			}
			return referenceTime;
		}
	}

	private record AudioCaptureSpec(
			UUID renderSessionId,
			ResourceKey<Level> dimension,
			BlockPos microphonePos,
			double x,
			double y,
			double z,
			double radiusBlocks,
			int sampleRate,
			int frameSamples
	) {
	}

	private static final class ActiveAudioCapture {
		private final MinecraftServer server;
		private final UUID audioId;
		private final String ownerKey;
		private final UUID botUuid;
		private final AudioCaptureSpec spec;
		private final long startedAtMillis;
		private volatile Consumer<AudioCaptureFrame> onFrame;
		private volatile Consumer<String> onFailure;
		private volatile long lastFrameAtMillis;

		private ActiveAudioCapture(
				MinecraftServer server,
				UUID audioId,
				String ownerKey,
				UUID botUuid,
				AudioCaptureSpec spec,
				Consumer<AudioCaptureFrame> onFrame,
				Consumer<String> onFailure
		) {
			this.server = server;
			this.audioId = audioId;
			this.ownerKey = ownerKey;
			this.botUuid = botUuid;
			this.spec = spec;
			this.onFrame = onFrame;
			this.onFailure = onFailure;
			this.startedAtMillis = System.currentTimeMillis();
			this.lastFrameAtMillis = 0L;
		}

		private MinecraftServer server() {
			return this.server;
		}

		private UUID audioId() {
			return this.audioId;
		}

		private String ownerKey() {
			return this.ownerKey;
		}

		private UUID botUuid() {
			return this.botUuid;
		}

		private AudioCaptureSpec spec() {
			return this.spec;
		}

		private Consumer<AudioCaptureFrame> onFrame() {
			return this.onFrame;
		}

		private Consumer<String> onFailure() {
			return this.onFailure;
		}

		private void updateCallbacks(Consumer<AudioCaptureFrame> onFrame, Consumer<String> onFailure) {
			if (onFrame != null) {
				this.onFrame = onFrame;
			}
			if (onFailure != null) {
				this.onFailure = onFailure;
			}
		}

		private void markFrameReceived() {
			this.lastFrameAtMillis = System.currentTimeMillis();
		}

		private boolean isStale() {
			long referenceTime = this.lastFrameAtMillis > 0L ? this.lastFrameAtMillis : this.startedAtMillis;
			return System.currentTimeMillis() - referenceTime > AUDIO_CAPTURE_STALE_MS;
		}

		private long lastActivityAtMillis() {
			return this.lastFrameAtMillis > 0L ? this.lastFrameAtMillis : this.startedAtMillis;
		}
	}

	private record ScheduledServiceTarget(
			ServerLevel level,
			double x,
			double y,
			double z,
			float yaw,
			float pitch,
			Entity followTarget
	) {
	}

	public record VideoRecordingResult(
			long durationMs,
			int fps,
			String videoPath,
			byte[] previewPixels,
			byte[] fullPixels
	) {
	}

	public record VideoRecordingHandle(
			UUID requestId,
			UUID botUuid,
			CompletableFuture<VideoRecordingResult> completionFuture
	) {
	}
}
