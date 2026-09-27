package com.lostglade.network;

import com.lostglade.Lg2;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dedicated protocol for distributed Yandex map rendering. */
public final class YandexMapRenderPayloads {
	public static final int PROTOCOL_VERSION = 5;
	private static final int MAX_SCENE_PACKET_BYTES = 2_097_152;
	private static final int MAX_RESULT_BYTES = 1_048_576;
	private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

	private YandexMapRenderPayloads() {
	}

	public static void registerPayloadTypes() {
		if (!REGISTERED.compareAndSet(false, true)) return;

		PayloadTypeRegistry.playC2S().register(MapWorkerCapabilitiesC2SPayload.TYPE, MapWorkerCapabilitiesC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(MapRenderJobDecisionC2SPayload.TYPE, MapRenderJobDecisionC2SPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().registerLarge(MapRenderResultC2SPayload.TYPE, MapRenderResultC2SPayload.STREAM_CODEC, MAX_RESULT_BYTES);
		PayloadTypeRegistry.playC2S().register(MapRenderFailureC2SPayload.TYPE, MapRenderFailureC2SPayload.STREAM_CODEC);

		PayloadTypeRegistry.playS2C().register(MapWorkerStatusS2CPayload.TYPE, MapWorkerStatusS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(MapRenderJobOfferS2CPayload.TYPE, MapRenderJobOfferS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(MapRenderJobCancelS2CPayload.TYPE, MapRenderJobCancelS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(MapRenderSceneStartS2CPayload.TYPE, MapRenderSceneStartS2CPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().registerLarge(MapRenderSceneChunkS2CPayload.TYPE, MapRenderSceneChunkS2CPayload.STREAM_CODEC, MAX_SCENE_PACKET_BYTES);
		PayloadTypeRegistry.playS2C().registerLarge(MapRenderSceneEntityS2CPayload.TYPE, MapRenderSceneEntityS2CPayload.STREAM_CODEC, MAX_SCENE_PACKET_BYTES);
		PayloadTypeRegistry.playS2C().register(MapRenderSceneReadyS2CPayload.TYPE, MapRenderSceneReadyS2CPayload.STREAM_CODEC);
	}

	public record MapWorkerCapabilitiesC2SPayload(
			int mapProtocolVersion,
			boolean enabled,
			String mode,
			boolean shaderCompatible,
			String resourceProfileHash,
			int maxTilePixels,
			String clientBuildFingerprint
	) implements CustomPacketPayload {
		public static final Type<MapWorkerCapabilitiesC2SPayload> TYPE = new Type<>(id("yandex_map_worker_capabilities"));
		public static final StreamCodec<FriendlyByteBuf, MapWorkerCapabilitiesC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(MapWorkerCapabilitiesC2SPayload::write, MapWorkerCapabilitiesC2SPayload::new);
		public MapWorkerCapabilitiesC2SPayload(FriendlyByteBuf buffer) {
			this(buffer.readVarInt(), buffer.readBoolean(), buffer.readUtf(32), buffer.readBoolean(), buffer.readUtf(128), buffer.readVarInt(), buffer.readUtf(128));
		}
		private void write(FriendlyByteBuf buffer) {
			buffer.writeVarInt(this.mapProtocolVersion);
			buffer.writeBoolean(this.enabled);
			buffer.writeUtf(this.mode, 32);
			buffer.writeBoolean(this.shaderCompatible);
			buffer.writeUtf(this.resourceProfileHash, 128);
			buffer.writeVarInt(this.maxTilePixels);
			buffer.writeUtf(this.clientBuildFingerprint, 128);
		}
		@Override public Type<MapWorkerCapabilitiesC2SPayload> type() { return TYPE; }
	}

	public record MapWorkerStatusS2CPayload(boolean eligible, String reason, String canonicalProfileHash) implements CustomPacketPayload {
		public static final Type<MapWorkerStatusS2CPayload> TYPE = new Type<>(id("yandex_map_worker_status"));
		public static final StreamCodec<FriendlyByteBuf, MapWorkerStatusS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(MapWorkerStatusS2CPayload::write, MapWorkerStatusS2CPayload::new);
		public MapWorkerStatusS2CPayload(FriendlyByteBuf buffer) { this(buffer.readBoolean(), buffer.readUtf(128), buffer.readUtf(128)); }
		private void write(FriendlyByteBuf buffer) {
			buffer.writeBoolean(this.eligible);
			buffer.writeUtf(this.reason, 128);
			buffer.writeUtf(this.canonicalProfileHash, 128);
		}
		@Override public Type<MapWorkerStatusS2CPayload> type() { return TYPE; }
	}

	public record MapRenderJobOfferS2CPayload(
			UUID jobId,
			long tileX,
			long tileZ,
			String profileHash,
			long expiresAtEpochMs
	) implements CustomPacketPayload {
		public static final Type<MapRenderJobOfferS2CPayload> TYPE = new Type<>(id("yandex_map_job_offer"));
		public static final StreamCodec<FriendlyByteBuf, MapRenderJobOfferS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(MapRenderJobOfferS2CPayload::write, MapRenderJobOfferS2CPayload::new);
		public MapRenderJobOfferS2CPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readLong(), buffer.readLong(), buffer.readUtf(128), buffer.readLong());
		}
		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.jobId);
			buffer.writeLong(this.tileX);
			buffer.writeLong(this.tileZ);
			buffer.writeUtf(this.profileHash, 128);
			buffer.writeLong(this.expiresAtEpochMs);
		}
		@Override public Type<MapRenderJobOfferS2CPayload> type() { return TYPE; }
	}

	public record MapRenderJobCancelS2CPayload(UUID jobId, String reason) implements CustomPacketPayload {
		public static final Type<MapRenderJobCancelS2CPayload> TYPE = new Type<>(id("yandex_map_job_cancel"));
		public static final StreamCodec<FriendlyByteBuf, MapRenderJobCancelS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(MapRenderJobCancelS2CPayload::write, MapRenderJobCancelS2CPayload::new);
		public MapRenderJobCancelS2CPayload(FriendlyByteBuf buffer) { this(buffer.readUUID(), buffer.readUtf(256)); }
		private void write(FriendlyByteBuf buffer) { buffer.writeUUID(this.jobId); buffer.writeUtf(this.reason, 256); }
		@Override public Type<MapRenderJobCancelS2CPayload> type() { return TYPE; }
	}

	public record MapRenderJobDecisionC2SPayload(UUID jobId, boolean accepted, String reason) implements CustomPacketPayload {
		public static final Type<MapRenderJobDecisionC2SPayload> TYPE = new Type<>(id("yandex_map_job_decision"));
		public static final StreamCodec<FriendlyByteBuf, MapRenderJobDecisionC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(MapRenderJobDecisionC2SPayload::write, MapRenderJobDecisionC2SPayload::new);
		public MapRenderJobDecisionC2SPayload(FriendlyByteBuf buffer) { this(buffer.readUUID(), buffer.readBoolean(), buffer.readUtf(128)); }
		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.jobId);
			buffer.writeBoolean(this.accepted);
			buffer.writeUtf(this.reason, 128);
		}
		@Override public Type<MapRenderJobDecisionC2SPayload> type() { return TYPE; }
	}

	public record MapRenderSceneStartS2CPayload(
			UUID jobId,
			String dimensionId,
			String dimensionTypeId,
			long seed,
			int seaLevel,
			long tileX,
			long tileZ,
			int tileBlocks,
			int renderPixels,
			int viewDistance,
			String snapshotFingerprint,
			int chunkPacketCount,
			int entityPacketCount
	) implements CustomPacketPayload {
		public static final Type<MapRenderSceneStartS2CPayload> TYPE = new Type<>(id("yandex_map_scene_start"));
		public static final StreamCodec<FriendlyByteBuf, MapRenderSceneStartS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(MapRenderSceneStartS2CPayload::write, MapRenderSceneStartS2CPayload::new);
		public MapRenderSceneStartS2CPayload(FriendlyByteBuf buffer) {
			this(buffer.readUUID(), buffer.readUtf(128), buffer.readUtf(128), buffer.readLong(), buffer.readVarInt(), buffer.readLong(), buffer.readLong(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(128), buffer.readVarInt(), buffer.readVarInt());
		}
		private void write(FriendlyByteBuf buffer) {
			buffer.writeUUID(this.jobId);
			buffer.writeUtf(this.dimensionId, 128);
			buffer.writeUtf(this.dimensionTypeId, 128);
			buffer.writeLong(this.seed);
			buffer.writeVarInt(this.seaLevel);
			buffer.writeLong(this.tileX);
			buffer.writeLong(this.tileZ);
			buffer.writeVarInt(this.tileBlocks);
			buffer.writeVarInt(this.renderPixels);
			buffer.writeVarInt(this.viewDistance);
			buffer.writeUtf(this.snapshotFingerprint, 128);
			buffer.writeVarInt(this.chunkPacketCount);
			buffer.writeVarInt(this.entityPacketCount);
		}
		@Override public Type<MapRenderSceneStartS2CPayload> type() { return TYPE; }
	}

	public record MapRenderSceneChunkS2CPayload(UUID jobId, int index, byte[] packetBytes) implements CustomPacketPayload {
		public static final Type<MapRenderSceneChunkS2CPayload> TYPE = new Type<>(id("yandex_map_scene_chunk"));
		public static final StreamCodec<FriendlyByteBuf, MapRenderSceneChunkS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(MapRenderSceneChunkS2CPayload::write, MapRenderSceneChunkS2CPayload::new);
		public MapRenderSceneChunkS2CPayload(FriendlyByteBuf buffer) { this(buffer.readUUID(), buffer.readVarInt(), buffer.readByteArray(MAX_SCENE_PACKET_BYTES)); }
		private void write(FriendlyByteBuf buffer) { buffer.writeUUID(this.jobId); buffer.writeVarInt(this.index); buffer.writeByteArray(this.packetBytes); }
		@Override public Type<MapRenderSceneChunkS2CPayload> type() { return TYPE; }
	}

	public record MapRenderSceneEntityS2CPayload(UUID jobId, int index, String packetTypeId, byte[] packetBytes) implements CustomPacketPayload {
		public static final Type<MapRenderSceneEntityS2CPayload> TYPE = new Type<>(id("yandex_map_scene_entity"));
		public static final StreamCodec<FriendlyByteBuf, MapRenderSceneEntityS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(MapRenderSceneEntityS2CPayload::write, MapRenderSceneEntityS2CPayload::new);
		public MapRenderSceneEntityS2CPayload(FriendlyByteBuf buffer) { this(buffer.readUUID(), buffer.readVarInt(), buffer.readUtf(128), buffer.readByteArray(MAX_SCENE_PACKET_BYTES)); }
		private void write(FriendlyByteBuf buffer) { buffer.writeUUID(this.jobId); buffer.writeVarInt(this.index); buffer.writeUtf(this.packetTypeId, 128); buffer.writeByteArray(this.packetBytes); }
		@Override public Type<MapRenderSceneEntityS2CPayload> type() { return TYPE; }
	}

	public record MapRenderSceneReadyS2CPayload(UUID jobId) implements CustomPacketPayload {
		public static final Type<MapRenderSceneReadyS2CPayload> TYPE = new Type<>(id("yandex_map_scene_ready"));
		public static final StreamCodec<FriendlyByteBuf, MapRenderSceneReadyS2CPayload> STREAM_CODEC =
				CustomPacketPayload.codec(MapRenderSceneReadyS2CPayload::write, MapRenderSceneReadyS2CPayload::new);
		public MapRenderSceneReadyS2CPayload(FriendlyByteBuf buffer) { this(buffer.readUUID()); }
		private void write(FriendlyByteBuf buffer) { buffer.writeUUID(this.jobId); }
		@Override public Type<MapRenderSceneReadyS2CPayload> type() { return TYPE; }
	}

	public record MapRenderResultC2SPayload(UUID jobId, String snapshotFingerprint, byte[] pngBytes) implements CustomPacketPayload {
		public static final Type<MapRenderResultC2SPayload> TYPE = new Type<>(id("yandex_map_result"));
		public static final StreamCodec<FriendlyByteBuf, MapRenderResultC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(MapRenderResultC2SPayload::write, MapRenderResultC2SPayload::new);
		public MapRenderResultC2SPayload(FriendlyByteBuf buffer) { this(buffer.readUUID(), buffer.readUtf(128), buffer.readByteArray(MAX_RESULT_BYTES)); }
		private void write(FriendlyByteBuf buffer) { buffer.writeUUID(this.jobId); buffer.writeUtf(this.snapshotFingerprint, 128); buffer.writeByteArray(this.pngBytes); }
		@Override public Type<MapRenderResultC2SPayload> type() { return TYPE; }
	}

	public record MapRenderFailureC2SPayload(UUID jobId, String reason) implements CustomPacketPayload {
		public static final Type<MapRenderFailureC2SPayload> TYPE = new Type<>(id("yandex_map_failure"));
		public static final StreamCodec<FriendlyByteBuf, MapRenderFailureC2SPayload> STREAM_CODEC =
				CustomPacketPayload.codec(MapRenderFailureC2SPayload::write, MapRenderFailureC2SPayload::new);
		public MapRenderFailureC2SPayload(FriendlyByteBuf buffer) { this(buffer.readUUID(), buffer.readUtf(256)); }
		private void write(FriendlyByteBuf buffer) { buffer.writeUUID(this.jobId); buffer.writeUtf(this.reason, 256); }
		@Override public Type<MapRenderFailureC2SPayload> type() { return TYPE; }
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(Lg2.MOD_ID, path);
	}
}
