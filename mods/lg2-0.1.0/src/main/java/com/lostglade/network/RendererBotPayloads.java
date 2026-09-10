package com.lostglade.network;

import com.lostglade.Lg2;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RendererBotPayloads {
	// RendererBotLiveStreamStartS2CPayload gained a field. Keep an old renderer
	// bot from accepting the handshake and decoding the following varints from a
	// shifted byte offset.
	public static final int PROTOCOL_VERSION = 25;
	private static final int MAX_CAPTURE_PAYLOAD_BYTES = 1_048_576;
	private static final int MAX_SHADOW_PAYLOAD_BYTES = 2_097_152;
	private static final int MAX_HIDDEN_CAMERA_ENTITIES = 32;
	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

	private RendererBotPayloads() {
	}

	public static void registerPayloadTypes() {
		if (!REGISTERED.compareAndSet(false, true)) {
			return;
		}

		PayloadTypeRegistry.playC2S().register(RendererBotHelloC2SPayload.TYPE, RendererBotHelloC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(RendererBotPreviewFrameC2SPayload.TYPE, RendererBotPreviewFrameC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().registerLarge(RendererBotFullFrameC2SPayload.TYPE, RendererBotFullFrameC2SPayload.STREAM_CODEC, MAX_CAPTURE_PAYLOAD_BYTES);
		PayloadTypeRegistry.playC2S().registerLarge(RendererBotLiveFrameC2SPayload.TYPE, RendererBotLiveFrameC2SPayload.STREAM_CODEC, MAX_CAPTURE_PAYLOAD_BYTES);
		PayloadTypeRegistry.playC2S().registerLarge(RendererBotMapTileC2SPayload.TYPE, RendererBotMapTileC2SPayload.STREAM_CODEC, MAX_CAPTURE_PAYLOAD_BYTES);
		PayloadTypeRegistry.playC2S().registerLarge(RendererBotItemIconC2SPayload.TYPE, RendererBotItemIconC2SPayload.STREAM_CODEC, MAX_CAPTURE_PAYLOAD_BYTES);
		PayloadTypeRegistry.playC2S().register(RendererBotVideoRecordingStartedC2SPayload.TYPE, RendererBotVideoRecordingStartedC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().registerLarge(RendererBotVideoRecordingCompleteC2SPayload.TYPE, RendererBotVideoRecordingCompleteC2SPayload.STREAM_CODEC, MAX_CAPTURE_PAYLOAD_BYTES);
		PayloadTypeRegistry.playC2S().registerLarge(RendererBotVideoFileChunkC2SPayload.TYPE, RendererBotVideoFileChunkC2SPayload.STREAM_CODEC, MAX_CAPTURE_PAYLOAD_BYTES);
		PayloadTypeRegistry.playC2S().register(RendererBotAudioFrameC2SPayload.TYPE, RendererBotAudioFrameC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(RendererBotCaptureFailureC2SPayload.TYPE, RendererBotCaptureFailureC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(RendererBotLiveStreamFailureC2SPayload.TYPE, RendererBotLiveStreamFailureC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(RendererBotMapTileFailureC2SPayload.TYPE, RendererBotMapTileFailureC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(RendererBotItemIconFailureC2SPayload.TYPE, RendererBotItemIconFailureC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(RendererBotAudioCaptureFailureC2SPayload.TYPE, RendererBotAudioCaptureFailureC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotCaptureRequestS2CPayload.TYPE, RendererBotCaptureRequestS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotLiveStreamStartS2CPayload.TYPE, RendererBotLiveStreamStartS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotLiveStreamPoseS2CPayload.TYPE, RendererBotLiveStreamPoseS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotLiveStreamStopS2CPayload.TYPE, RendererBotLiveStreamStopS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotMapTileRequestS2CPayload.TYPE, RendererBotMapTileRequestS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotItemIconRequestS2CPayload.TYPE, RendererBotItemIconRequestS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotVideoRecordingStartS2CPayload.TYPE, RendererBotVideoRecordingStartS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotVideoRecordingStopS2CPayload.TYPE, RendererBotVideoRecordingStopS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotAudioCaptureStartS2CPayload.TYPE, RendererBotAudioCaptureStartS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotAudioCaptureStopS2CPayload.TYPE, RendererBotAudioCaptureStopS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotShadowLevelInitS2CPayload.TYPE, RendererBotShadowLevelInitS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotShadowLevelStateS2CPayload.TYPE, RendererBotShadowLevelStateS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotShadowViewS2CPayload.TYPE, RendererBotShadowViewS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotShadowLevelDestroyS2CPayload.TYPE, RendererBotShadowLevelDestroyS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().registerLarge(RendererBotShadowChunkDataS2CPayload.TYPE, RendererBotShadowChunkDataS2CPayload.STREAM_CODEC, MAX_SHADOW_PAYLOAD_BYTES);
		PayloadTypeRegistry.playS2C().register(RendererBotShadowForgetChunkS2CPayload.TYPE, RendererBotShadowForgetChunkS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(RendererBotShadowContentReadyS2CPayload.TYPE, RendererBotShadowContentReadyS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().registerLarge(RendererBotShadowEntityPacketsS2CPayload.TYPE, RendererBotShadowEntityPacketsS2CPayload.STREAM_CODEC, MAX_SHADOW_PAYLOAD_BYTES);
	}

	public record RendererBotHelloC2SPayload(int protocolVersion, boolean volunteerRenderer) implements CustomPacketPayload {
		public static final Type<RendererBotHelloC2SPayload> TYPE = new Type<>(id("renderer_bot_hello"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotHelloC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotHelloC2SPayload::write, RendererBotHelloC2SPayload::new);

		public RendererBotHelloC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readVarInt(), buffer.readBoolean());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeVarInt(this.protocolVersion);
			buffer.writeBoolean(this.volunteerRenderer);
		}

		@Override
		public Type<RendererBotHelloC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotCaptureRequestS2CPayload(
			UUID requestId,
			UUID renderSessionId,
			String dimensionId,
			double expectedX,
			double expectedY,
			double expectedZ,
			float expectedYaw,
			float expectedPitch,
			UUID followEntityUuid,
			UUID hiddenEntityUuid,
			int previewWidth,
			int previewHeight,
			int fullWidth,
			int fullHeight,
			int fovDegrees
	) implements CustomPacketPayload {
		public static final Type<RendererBotCaptureRequestS2CPayload> TYPE = new Type<>(id("renderer_bot_capture_request"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotCaptureRequestS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotCaptureRequestS2CPayload::write, RendererBotCaptureRequestS2CPayload::new);

		public RendererBotCaptureRequestS2CPayload(FriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					buffer.readUUID(),
					buffer.readUtf(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readFloat(),
					buffer.readFloat(),
					buffer.readBoolean() ? buffer.readUUID() : null,
					buffer.readBoolean() ? buffer.readUUID() : null,
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt()
			);
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			buffer.writeUUID(this.renderSessionId);
			buffer.writeUtf(this.dimensionId);
			buffer.writeDouble(this.expectedX);
			buffer.writeDouble(this.expectedY);
			buffer.writeDouble(this.expectedZ);
			buffer.writeFloat(this.expectedYaw);
			buffer.writeFloat(this.expectedPitch);
			buffer.writeBoolean(this.followEntityUuid != null);
			if (this.followEntityUuid != null) {
				buffer.writeUUID(this.followEntityUuid);
			}
			buffer.writeBoolean(this.hiddenEntityUuid != null);
			if (this.hiddenEntityUuid != null) {
				buffer.writeUUID(this.hiddenEntityUuid);
			}
			buffer.writeVarInt(this.previewWidth);
			buffer.writeVarInt(this.previewHeight);
			buffer.writeVarInt(this.fullWidth);
			buffer.writeVarInt(this.fullHeight);
			buffer.writeVarInt(this.fovDegrees);
		}

		@Override
		public Type<RendererBotCaptureRequestS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotPreviewFrameC2SPayload(UUID requestId, byte[] pixels) implements CustomPacketPayload {
		public static final Type<RendererBotPreviewFrameC2SPayload> TYPE = new Type<>(id("renderer_bot_preview_frame"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotPreviewFrameC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotPreviewFrameC2SPayload::write, RendererBotPreviewFrameC2SPayload::new);

		public RendererBotPreviewFrameC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readByteArray());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			buffer.writeByteArray(this.pixels);
		}

		@Override
		public Type<RendererBotPreviewFrameC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotFullFrameC2SPayload(UUID requestId, byte[] pixels) implements CustomPacketPayload {
		public static final Type<RendererBotFullFrameC2SPayload> TYPE = new Type<>(id("renderer_bot_full_frame"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotFullFrameC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotFullFrameC2SPayload::write, RendererBotFullFrameC2SPayload::new);

		public RendererBotFullFrameC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readByteArray());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			buffer.writeByteArray(this.pixels);
		}

		@Override
		public Type<RendererBotFullFrameC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotLiveStreamStartS2CPayload(
			UUID streamId,
			UUID renderSessionId,
			String dimensionId,
			double expectedX,
			double expectedY,
			double expectedZ,
			float expectedYaw,
			float expectedPitch,
			UUID followEntityUuid,
			List<UUID> hiddenEntityUuids,
			boolean hideCameraCollisionBlock,
			int fullWidth,
			int fullHeight,
			int fovDegrees,
			int targetFps
	) implements CustomPacketPayload {
		public static final Type<RendererBotLiveStreamStartS2CPayload> TYPE = new Type<>(id("renderer_bot_live_stream_start"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotLiveStreamStartS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotLiveStreamStartS2CPayload::write, RendererBotLiveStreamStartS2CPayload::new);

		public RendererBotLiveStreamStartS2CPayload {
			hiddenEntityUuids = hiddenEntityUuids == null ? List.of() : List.copyOf(hiddenEntityUuids);
		}

		public RendererBotLiveStreamStartS2CPayload(FriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					buffer.readUUID(),
					buffer.readUtf(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readFloat(),
					buffer.readFloat(),
					buffer.readBoolean() ? buffer.readUUID() : null,
					readHiddenCameraEntityUuids(buffer),
					buffer.readBoolean(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt()
			);
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.streamId);
			buffer.writeUUID(this.renderSessionId);
			buffer.writeUtf(this.dimensionId);
			buffer.writeDouble(this.expectedX);
			buffer.writeDouble(this.expectedY);
			buffer.writeDouble(this.expectedZ);
			buffer.writeFloat(this.expectedYaw);
			buffer.writeFloat(this.expectedPitch);
			buffer.writeBoolean(this.followEntityUuid != null);
			if (this.followEntityUuid != null) {
				buffer.writeUUID(this.followEntityUuid);
			}
			writeHiddenCameraEntityUuids(buffer, this.hiddenEntityUuids);
			buffer.writeBoolean(this.hideCameraCollisionBlock);
			buffer.writeVarInt(this.fullWidth);
			buffer.writeVarInt(this.fullHeight);
			buffer.writeVarInt(this.fovDegrees);
			buffer.writeVarInt(this.targetFps);
		}

		@Override
		public Type<RendererBotLiveStreamStartS2CPayload> type() {
			return TYPE;
		}
	}

	private static List<UUID> readHiddenCameraEntityUuids(FriendlyByteBuf buffer) {
		int count = buffer.readVarInt();
		if (count < 0 || count > MAX_HIDDEN_CAMERA_ENTITIES) {
			throw new IllegalArgumentException("Invalid hidden camera entity count: " + count);
		}
		List<UUID> entityUuids = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			entityUuids.add(buffer.readUUID());
		}
		return List.copyOf(entityUuids);
	}

	private static void writeHiddenCameraEntityUuids(FriendlyByteBuf buffer, List<UUID> entityUuids) {
		List<UUID> safeEntityUuids = entityUuids == null ? List.of() : entityUuids;
		if (safeEntityUuids.size() > MAX_HIDDEN_CAMERA_ENTITIES) {
			throw new IllegalArgumentException("Too many hidden camera entities: " + safeEntityUuids.size());
		}
		buffer.writeVarInt(safeEntityUuids.size());
		for (UUID entityUuid : safeEntityUuids) {
			if (entityUuid == null) {
				throw new IllegalArgumentException("Hidden camera entity UUID must not be null");
			}
			buffer.writeUUID(entityUuid);
		}
	}

	public record RendererBotLiveStreamStopS2CPayload(UUID streamId) implements CustomPacketPayload {
		public static final Type<RendererBotLiveStreamStopS2CPayload> TYPE = new Type<>(id("renderer_bot_live_stream_stop"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotLiveStreamStopS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotLiveStreamStopS2CPayload::write, RendererBotLiveStreamStopS2CPayload::new);

		public RendererBotLiveStreamStopS2CPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.streamId);
		}

		@Override
		public Type<RendererBotLiveStreamStopS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotMapTileRequestS2CPayload(
			UUID requestId,
			UUID renderSessionId,
			String dimensionId,
			int tileSize,
			int lod,
			long tileX,
			long tileZ,
			double centerX,
			double centerZ,
			double blocksPerPixel,
			int priorityScore,
			boolean activeView
	) implements CustomPacketPayload {
		public static final Type<RendererBotMapTileRequestS2CPayload> TYPE = new Type<>(id("renderer_bot_map_tile_request"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotMapTileRequestS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotMapTileRequestS2CPayload::write, RendererBotMapTileRequestS2CPayload::new);

		public RendererBotMapTileRequestS2CPayload(FriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					buffer.readUUID(),
					buffer.readUtf(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readLong(),
					buffer.readLong(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readVarInt(),
					buffer.readBoolean()
			);
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			buffer.writeUUID(this.renderSessionId);
			buffer.writeUtf(this.dimensionId);
			buffer.writeVarInt(this.tileSize);
			buffer.writeVarInt(this.lod);
			buffer.writeLong(this.tileX);
			buffer.writeLong(this.tileZ);
			buffer.writeDouble(this.centerX);
			buffer.writeDouble(this.centerZ);
			buffer.writeDouble(this.blocksPerPixel);
			buffer.writeVarInt(this.priorityScore);
			buffer.writeBoolean(this.activeView);
		}

		@Override
		public Type<RendererBotMapTileRequestS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotItemIconRequestS2CPayload(
			UUID requestId,
			ItemStack stack,
			int iconSize
	) implements CustomPacketPayload {
		public static final Type<RendererBotItemIconRequestS2CPayload> TYPE = new Type<>(id("renderer_bot_item_icon_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RendererBotItemIconRequestS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotItemIconRequestS2CPayload::write, RendererBotItemIconRequestS2CPayload::new);

		public RendererBotItemIconRequestS2CPayload(RegistryFriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
					buffer.readVarInt()
			);
		}

		private void write(RegistryFriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, this.stack == null ? ItemStack.EMPTY : this.stack);
			buffer.writeVarInt(this.iconSize);
		}

		@Override
		public Type<RendererBotItemIconRequestS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotLiveStreamPoseS2CPayload(
			UUID streamId,
			double x,
			double y,
			double z,
			float yaw,
			float pitch,
			float cameraBankRadians
	) implements CustomPacketPayload {
		public static final Type<RendererBotLiveStreamPoseS2CPayload> TYPE = new Type<>(id("renderer_bot_live_stream_pose"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotLiveStreamPoseS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotLiveStreamPoseS2CPayload::write, RendererBotLiveStreamPoseS2CPayload::new);

		public RendererBotLiveStreamPoseS2CPayload(FriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readFloat(),
					buffer.readFloat(),
					buffer.readFloat()
			);
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.streamId);
			buffer.writeDouble(this.x);
			buffer.writeDouble(this.y);
			buffer.writeDouble(this.z);
			buffer.writeFloat(this.yaw);
			buffer.writeFloat(this.pitch);
			buffer.writeFloat(this.cameraBankRadians);
		}

		@Override
		public Type<RendererBotLiveStreamPoseS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotLiveFrameC2SPayload(UUID streamId, long clientFrameNanos, byte[] pixels) implements CustomPacketPayload {
		public static final Type<RendererBotLiveFrameC2SPayload> TYPE = new Type<>(id("renderer_bot_live_frame"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotLiveFrameC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotLiveFrameC2SPayload::write, RendererBotLiveFrameC2SPayload::new);

		public RendererBotLiveFrameC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readVarLong(), buffer.readByteArray());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.streamId);
			buffer.writeVarLong(this.clientFrameNanos);
			buffer.writeByteArray(this.pixels);
		}

		@Override
		public Type<RendererBotLiveFrameC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotMapTileC2SPayload(
			UUID requestId,
			int lod,
			long tileX,
			long tileZ,
			long clientFrameNanos,
			byte[] pixels
	) implements CustomPacketPayload {
		public static final Type<RendererBotMapTileC2SPayload> TYPE = new Type<>(id("renderer_bot_map_tile"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotMapTileC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotMapTileC2SPayload::write, RendererBotMapTileC2SPayload::new);

		public RendererBotMapTileC2SPayload(FriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					buffer.readVarInt(),
					buffer.readLong(),
					buffer.readLong(),
					buffer.readVarLong(),
					buffer.readByteArray()
			);
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			buffer.writeVarInt(this.lod);
			buffer.writeLong(this.tileX);
			buffer.writeLong(this.tileZ);
			buffer.writeVarLong(this.clientFrameNanos);
			buffer.writeByteArray(this.pixels);
		}

		@Override
		public Type<RendererBotMapTileC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotItemIconC2SPayload(
			UUID requestId,
			int iconSize,
			byte[] argbPixels
	) implements CustomPacketPayload {
		public static final Type<RendererBotItemIconC2SPayload> TYPE = new Type<>(id("renderer_bot_item_icon"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotItemIconC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotItemIconC2SPayload::write, RendererBotItemIconC2SPayload::new);

		public RendererBotItemIconC2SPayload(FriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					buffer.readVarInt(),
					buffer.readByteArray(MAX_CAPTURE_PAYLOAD_BYTES)
			);
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			buffer.writeVarInt(this.iconSize);
			buffer.writeByteArray(this.argbPixels);
		}

		@Override
		public Type<RendererBotItemIconC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotAudioCaptureStartS2CPayload(
			UUID audioId,
			UUID renderSessionId,
			String dimensionId,
			double x,
			double y,
			double z,
			double radiusBlocks,
			int sampleRate,
			int frameSamples
	) implements CustomPacketPayload {
		public static final Type<RendererBotAudioCaptureStartS2CPayload> TYPE = new Type<>(id("renderer_bot_audio_capture_start"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotAudioCaptureStartS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotAudioCaptureStartS2CPayload::write, RendererBotAudioCaptureStartS2CPayload::new);

		public RendererBotAudioCaptureStartS2CPayload(FriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					buffer.readUUID(),
					buffer.readUtf(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readVarInt(),
					buffer.readVarInt()
			);
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.audioId);
			buffer.writeUUID(this.renderSessionId);
			buffer.writeUtf(this.dimensionId);
			buffer.writeDouble(this.x);
			buffer.writeDouble(this.y);
			buffer.writeDouble(this.z);
			buffer.writeDouble(this.radiusBlocks);
			buffer.writeVarInt(this.sampleRate);
			buffer.writeVarInt(this.frameSamples);
		}

		@Override
		public Type<RendererBotAudioCaptureStartS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotAudioCaptureStopS2CPayload(UUID audioId) implements CustomPacketPayload {
		public static final Type<RendererBotAudioCaptureStopS2CPayload> TYPE = new Type<>(id("renderer_bot_audio_capture_stop"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotAudioCaptureStopS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotAudioCaptureStopS2CPayload::write, RendererBotAudioCaptureStopS2CPayload::new);

		public RendererBotAudioCaptureStopS2CPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.audioId);
		}

		@Override
		public Type<RendererBotAudioCaptureStopS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotAudioFrameC2SPayload(UUID audioId, long frameIndex, long clientFrameNanos, byte[] pcm) implements CustomPacketPayload {
		public static final Type<RendererBotAudioFrameC2SPayload> TYPE = new Type<>(id("renderer_bot_audio_frame"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotAudioFrameC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotAudioFrameC2SPayload::write, RendererBotAudioFrameC2SPayload::new);

		public RendererBotAudioFrameC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong(), buffer.readByteArray(8192));
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.audioId);
			buffer.writeVarLong(this.frameIndex);
			buffer.writeVarLong(this.clientFrameNanos);
			buffer.writeByteArray(this.pcm);
		}

		@Override
		public Type<RendererBotAudioFrameC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotAudioCaptureFailureC2SPayload(UUID audioId, String message) implements CustomPacketPayload {
		public static final Type<RendererBotAudioCaptureFailureC2SPayload> TYPE = new Type<>(id("renderer_bot_audio_capture_failure"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotAudioCaptureFailureC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotAudioCaptureFailureC2SPayload::write, RendererBotAudioCaptureFailureC2SPayload::new);

		public RendererBotAudioCaptureFailureC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readUtf(512));
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.audioId);
			buffer.writeUtf(this.message, 512);
		}

		@Override
		public Type<RendererBotAudioCaptureFailureC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotCaptureFailureC2SPayload(UUID requestId, String message) implements CustomPacketPayload {
		public static final Type<RendererBotCaptureFailureC2SPayload> TYPE = new Type<>(id("renderer_bot_capture_failure"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotCaptureFailureC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotCaptureFailureC2SPayload::write, RendererBotCaptureFailureC2SPayload::new);

		public RendererBotCaptureFailureC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readUtf(512));
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			buffer.writeUtf(this.message, 512);
		}

		@Override
		public Type<RendererBotCaptureFailureC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotLiveStreamFailureC2SPayload(UUID streamId, String message) implements CustomPacketPayload {
		public static final Type<RendererBotLiveStreamFailureC2SPayload> TYPE = new Type<>(id("renderer_bot_live_stream_failure"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotLiveStreamFailureC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotLiveStreamFailureC2SPayload::write, RendererBotLiveStreamFailureC2SPayload::new);

		public RendererBotLiveStreamFailureC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readUtf(512));
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.streamId);
			buffer.writeUtf(this.message, 512);
		}

		@Override
		public Type<RendererBotLiveStreamFailureC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotMapTileFailureC2SPayload(UUID requestId, String message) implements CustomPacketPayload {
		public static final Type<RendererBotMapTileFailureC2SPayload> TYPE = new Type<>(id("renderer_bot_map_tile_failure"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotMapTileFailureC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotMapTileFailureC2SPayload::write, RendererBotMapTileFailureC2SPayload::new);

		public RendererBotMapTileFailureC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readUtf(512));
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			buffer.writeUtf(this.message, 512);
		}

		@Override
		public Type<RendererBotMapTileFailureC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotItemIconFailureC2SPayload(UUID requestId, String message) implements CustomPacketPayload {
		public static final Type<RendererBotItemIconFailureC2SPayload> TYPE = new Type<>(id("renderer_bot_item_icon_failure"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotItemIconFailureC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotItemIconFailureC2SPayload::write, RendererBotItemIconFailureC2SPayload::new);

		public RendererBotItemIconFailureC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readUtf(512));
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			buffer.writeUtf(this.message, 512);
		}

		@Override
		public Type<RendererBotItemIconFailureC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotVideoRecordingStartS2CPayload(
			UUID requestId,
			UUID renderSessionId,
			String dimensionId,
			double expectedX,
			double expectedY,
			double expectedZ,
			float expectedYaw,
			float expectedPitch,
			UUID followEntityUuid,
			int previewWidth,
			int previewHeight,
			int fullWidth,
			int fullHeight,
			int fovDegrees,
			int targetFps,
			int maxDurationSeconds
	) implements CustomPacketPayload {
		public static final Type<RendererBotVideoRecordingStartS2CPayload> TYPE = new Type<>(id("renderer_bot_video_recording_start"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotVideoRecordingStartS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotVideoRecordingStartS2CPayload::write, RendererBotVideoRecordingStartS2CPayload::new);

		public RendererBotVideoRecordingStartS2CPayload(FriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					buffer.readUUID(),
					buffer.readUtf(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readDouble(),
					buffer.readFloat(),
					buffer.readFloat(),
					buffer.readBoolean() ? buffer.readUUID() : null,
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt()
			);
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			buffer.writeUUID(this.renderSessionId);
			buffer.writeUtf(this.dimensionId);
			buffer.writeDouble(this.expectedX);
			buffer.writeDouble(this.expectedY);
			buffer.writeDouble(this.expectedZ);
			buffer.writeFloat(this.expectedYaw);
			buffer.writeFloat(this.expectedPitch);
			buffer.writeBoolean(this.followEntityUuid != null);
			if (this.followEntityUuid != null) {
				buffer.writeUUID(this.followEntityUuid);
			}
			buffer.writeVarInt(this.previewWidth);
			buffer.writeVarInt(this.previewHeight);
			buffer.writeVarInt(this.fullWidth);
			buffer.writeVarInt(this.fullHeight);
			buffer.writeVarInt(this.fovDegrees);
			buffer.writeVarInt(this.targetFps);
			buffer.writeVarInt(this.maxDurationSeconds);
		}

		@Override
		public Type<RendererBotVideoRecordingStartS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotVideoRecordingStopS2CPayload(UUID requestId) implements CustomPacketPayload {
		public static final Type<RendererBotVideoRecordingStopS2CPayload> TYPE = new Type<>(id("renderer_bot_video_recording_stop"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotVideoRecordingStopS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotVideoRecordingStopS2CPayload::write, RendererBotVideoRecordingStopS2CPayload::new);

		public RendererBotVideoRecordingStopS2CPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
		}

		@Override
		public Type<RendererBotVideoRecordingStopS2CPayload> type() {
			return TYPE;
		}
	}

	/** Sent only after the renderer client has written the first real video frame. */
	public record RendererBotVideoRecordingStartedC2SPayload(UUID requestId) implements CustomPacketPayload {
		public static final Type<RendererBotVideoRecordingStartedC2SPayload> TYPE = new Type<>(id("renderer_bot_video_recording_started"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotVideoRecordingStartedC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotVideoRecordingStartedC2SPayload::write, RendererBotVideoRecordingStartedC2SPayload::new);

		public RendererBotVideoRecordingStartedC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
		}

		@Override
		public Type<RendererBotVideoRecordingStartedC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotVideoRecordingCompleteC2SPayload(
			UUID requestId,
			long durationMs,
			int fps,
			String videoPath,
			byte[] previewPixels,
			byte[] fullPixels
	) implements CustomPacketPayload {
		public static final Type<RendererBotVideoRecordingCompleteC2SPayload> TYPE = new Type<>(id("renderer_bot_video_recording_complete"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotVideoRecordingCompleteC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotVideoRecordingCompleteC2SPayload::write, RendererBotVideoRecordingCompleteC2SPayload::new);

		public RendererBotVideoRecordingCompleteC2SPayload(FriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					buffer.readVarLong(),
					buffer.readVarInt(),
					buffer.readUtf(4096),
					buffer.readByteArray(),
					buffer.readByteArray()
			);
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			buffer.writeVarLong(this.durationMs);
			buffer.writeVarInt(this.fps);
			buffer.writeUtf(this.videoPath == null ? "" : this.videoPath, 4096);
			buffer.writeByteArray(this.previewPixels);
			buffer.writeByteArray(this.fullPixels);
		}

		@Override
		public Type<RendererBotVideoRecordingCompleteC2SPayload> type() {
			return TYPE;
		}
	}

	/** Ordered MP4 chunks sent only when the renderer is a remote volunteer. */
	public record RendererBotVideoFileChunkC2SPayload(
			UUID requestId,
			int chunkIndex,
			boolean finalChunk,
			byte[] bytes
	) implements CustomPacketPayload {
		public static final Type<RendererBotVideoFileChunkC2SPayload> TYPE = new Type<>(id("renderer_bot_video_file_chunk"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotVideoFileChunkC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotVideoFileChunkC2SPayload::write, RendererBotVideoFileChunkC2SPayload::new);

		public RendererBotVideoFileChunkC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readVarInt(), buffer.readBoolean(), buffer.readByteArray());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.requestId);
			buffer.writeVarInt(this.chunkIndex);
			buffer.writeBoolean(this.finalChunk);
			buffer.writeByteArray(this.bytes);
		}

		@Override
		public Type<RendererBotVideoFileChunkC2SPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotShadowLevelInitS2CPayload(
			UUID sessionId,
			String dimensionId,
			String dimensionTypeId,
			long seed,
			boolean debug,
			boolean flat,
			boolean hardcore,
			int difficultyOrdinal,
			long gameTime,
			long dayTime,
			boolean tickDayTime,
			boolean raining,
			float rainLevel,
			float thunderLevel,
			int seaLevel,
			int viewDistance,
			int simulationDistance
	) implements CustomPacketPayload {
		public static final Type<RendererBotShadowLevelInitS2CPayload> TYPE = new Type<>(id("renderer_bot_shadow_level_init"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotShadowLevelInitS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotShadowLevelInitS2CPayload::write, RendererBotShadowLevelInitS2CPayload::new);

		public RendererBotShadowLevelInitS2CPayload(FriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					buffer.readUtf(),
					buffer.readUtf(),
					buffer.readVarLong(),
					buffer.readBoolean(),
					buffer.readBoolean(),
					buffer.readBoolean(),
					buffer.readVarInt(),
					buffer.readVarLong(),
					buffer.readVarLong(),
					buffer.readBoolean(),
					buffer.readBoolean(),
					buffer.readFloat(),
					buffer.readFloat(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt()
			);
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.sessionId);
			buffer.writeUtf(this.dimensionId);
			buffer.writeUtf(this.dimensionTypeId);
			buffer.writeVarLong(this.seed);
			buffer.writeBoolean(this.debug);
			buffer.writeBoolean(this.flat);
			buffer.writeBoolean(this.hardcore);
			buffer.writeVarInt(this.difficultyOrdinal);
			buffer.writeVarLong(this.gameTime);
			buffer.writeVarLong(this.dayTime);
			buffer.writeBoolean(this.tickDayTime);
			buffer.writeBoolean(this.raining);
			buffer.writeFloat(this.rainLevel);
			buffer.writeFloat(this.thunderLevel);
			buffer.writeVarInt(this.seaLevel);
			buffer.writeVarInt(this.viewDistance);
			buffer.writeVarInt(this.simulationDistance);
		}

		@Override
		public Type<RendererBotShadowLevelInitS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotShadowLevelStateS2CPayload(
			UUID sessionId,
			String dimensionId,
			long gameTime,
			long dayTime,
			boolean tickDayTime,
			boolean raining,
			float rainLevel,
			float thunderLevel
	) implements CustomPacketPayload {
		public static final Type<RendererBotShadowLevelStateS2CPayload> TYPE = new Type<>(id("renderer_bot_shadow_level_state"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotShadowLevelStateS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotShadowLevelStateS2CPayload::write, RendererBotShadowLevelStateS2CPayload::new);

		public RendererBotShadowLevelStateS2CPayload(FriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					buffer.readUtf(),
					buffer.readVarLong(),
					buffer.readVarLong(),
					buffer.readBoolean(),
					buffer.readBoolean(),
					buffer.readFloat(),
					buffer.readFloat()
			);
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.sessionId);
			buffer.writeUtf(this.dimensionId);
			buffer.writeVarLong(this.gameTime);
			buffer.writeVarLong(this.dayTime);
			buffer.writeBoolean(this.tickDayTime);
			buffer.writeBoolean(this.raining);
			buffer.writeFloat(this.rainLevel);
			buffer.writeFloat(this.thunderLevel);
		}

		@Override
		public Type<RendererBotShadowLevelStateS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotShadowViewS2CPayload(
			UUID sessionId,
			int centerChunkX,
			int centerChunkZ,
			int viewDistance
	) implements CustomPacketPayload {
		public static final Type<RendererBotShadowViewS2CPayload> TYPE = new Type<>(id("renderer_bot_shadow_view"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotShadowViewS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotShadowViewS2CPayload::write, RendererBotShadowViewS2CPayload::new);

		public RendererBotShadowViewS2CPayload(FriendlyByteBuf buffer) {
			this(
					buffer.readUUID(),
					buffer.readVarInt(),
					buffer.readVarInt(),
					buffer.readVarInt()
			);
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.sessionId);
			buffer.writeVarInt(this.centerChunkX);
			buffer.writeVarInt(this.centerChunkZ);
			buffer.writeVarInt(this.viewDistance);
		}

		@Override
		public Type<RendererBotShadowViewS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotShadowLevelDestroyS2CPayload(UUID sessionId) implements CustomPacketPayload {
		public static final Type<RendererBotShadowLevelDestroyS2CPayload> TYPE = new Type<>(id("renderer_bot_shadow_level_destroy"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotShadowLevelDestroyS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotShadowLevelDestroyS2CPayload::write, RendererBotShadowLevelDestroyS2CPayload::new);

		public RendererBotShadowLevelDestroyS2CPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.sessionId);
		}

		@Override
		public Type<RendererBotShadowLevelDestroyS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotShadowChunkDataS2CPayload(UUID sessionId, String dimensionId, byte[] packetBytes) implements CustomPacketPayload {
		public static final Type<RendererBotShadowChunkDataS2CPayload> TYPE = new Type<>(id("renderer_bot_shadow_chunk_data"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotShadowChunkDataS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotShadowChunkDataS2CPayload::write, RendererBotShadowChunkDataS2CPayload::new);

		public RendererBotShadowChunkDataS2CPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readUtf(), buffer.readByteArray());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.sessionId);
			buffer.writeUtf(this.dimensionId);
			buffer.writeByteArray(this.packetBytes);
		}

		@Override
		public Type<RendererBotShadowChunkDataS2CPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Sent after every required chunk packet for a camera view has been queued to
	 * the renderer client. The stream is ordered, so receiving this marker means
	 * all preceding shadow chunk changes for the revision are already applied.
	 */
	public record RendererBotShadowContentReadyS2CPayload(UUID sessionId, long revision) implements CustomPacketPayload {
		public static final Type<RendererBotShadowContentReadyS2CPayload> TYPE = new Type<>(id("renderer_bot_shadow_content_ready"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotShadowContentReadyS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotShadowContentReadyS2CPayload::write, RendererBotShadowContentReadyS2CPayload::new);

		public RendererBotShadowContentReadyS2CPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readVarLong());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.sessionId);
			buffer.writeVarLong(this.revision);
		}

		@Override
		public Type<RendererBotShadowContentReadyS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotShadowForgetChunkS2CPayload(UUID sessionId, String dimensionId, int chunkX, int chunkZ) implements CustomPacketPayload {
		public static final Type<RendererBotShadowForgetChunkS2CPayload> TYPE = new Type<>(id("renderer_bot_shadow_forget_chunk"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotShadowForgetChunkS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotShadowForgetChunkS2CPayload::write, RendererBotShadowForgetChunkS2CPayload::new);

		public RendererBotShadowForgetChunkS2CPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readUtf(), buffer.readVarInt(), buffer.readVarInt());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.sessionId);
			buffer.writeUtf(this.dimensionId);
			buffer.writeVarInt(this.chunkX);
			buffer.writeVarInt(this.chunkZ);
		}

		@Override
		public Type<RendererBotShadowForgetChunkS2CPayload> type() {
			return TYPE;
		}
	}

	public record RendererBotShadowEntityPacketsS2CPayload(UUID sessionId, String dimensionId, List<ShadowPacketData> packets) implements CustomPacketPayload {
		public static final Type<RendererBotShadowEntityPacketsS2CPayload> TYPE = new Type<>(id("renderer_bot_shadow_entity_packets"));
		public static final StreamCodec<FriendlyByteBuf, RendererBotShadowEntityPacketsS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(RendererBotShadowEntityPacketsS2CPayload::write, RendererBotShadowEntityPacketsS2CPayload::new);

		public RendererBotShadowEntityPacketsS2CPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readUtf(), readShadowPacketList(buffer));
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.sessionId);
			buffer.writeUtf(this.dimensionId);
			writeShadowPacketList(buffer, this.packets);
		}

		@Override
		public Type<RendererBotShadowEntityPacketsS2CPayload> type() {
			return TYPE;
		}
	}

	public record ShadowPacketData(String packetTypeId, byte[] packetBytes) {
		private static ShadowPacketData read(FriendlyByteBuf buffer) {
			return new ShadowPacketData(buffer.readUtf(), buffer.readByteArray());
		}

		private void write(FriendlyByteBuf buffer) {
			buffer.writeUtf(this.packetTypeId);
			buffer.writeByteArray(this.packetBytes);
		}
	}

	private static List<ShadowPacketData> readShadowPacketList(FriendlyByteBuf buffer) {
		int size = buffer.readVarInt();
		List<ShadowPacketData> packets = new ArrayList<>(size);
		for (int index = 0; index < size; index++) {
			packets.add(ShadowPacketData.read(buffer));
		}
		return packets;
	}

	private static void writeShadowPacketList(FriendlyByteBuf buffer, List<ShadowPacketData> packets) {
		List<ShadowPacketData> safePackets = new ArrayList<>();
		if (packets != null) {
			for (ShadowPacketData packet : packets) {
				if (packet != null) {
					safePackets.add(packet);
				}
			}
		}
		buffer.writeVarInt(safePackets.size());
		for (ShadowPacketData packet : safePackets) {
			packet.write(buffer);
		}
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(Lg2.MOD_ID, path);
	}
}
