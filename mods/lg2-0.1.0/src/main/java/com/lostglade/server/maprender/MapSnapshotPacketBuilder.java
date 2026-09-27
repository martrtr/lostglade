package com.lostglade.server.maprender;

import com.lostglade.network.RendererBotPayloads;
import com.lostglade.network.RendererBotShadowPacketCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.server.level.ServerLevel;
import xyz.nucleoid.packettweaker.PacketContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Converts a read-only disk snapshot into worker-specific, Polymer-patched vanilla packets. */
public final class MapSnapshotPacketBuilder {
	private static final int FIRST_SYNTHETIC_ENTITY_ID = 2_000_000_000;

	private MapSnapshotPacketBuilder() {
	}

	public static PacketBatch build(ServerLevel level, ServerPlayer worker, MapTileSnapshot snapshot) {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(worker, "worker");
		Objects.requireNonNull(snapshot, "snapshot");
		if (worker.connection == null) {
			throw new IllegalStateException("Map render worker has no active connection");
		}

		Map<ChunkPos, LevelChunk> detachedChunks = new LinkedHashMap<>();
		for (MapChunkSnapshot chunkSnapshot : snapshot.chunks()) {
			detachedChunks.put(chunkSnapshot.pos(), chunkSnapshot.createDetachedChunk(level));
		}
		SnapshotLightSource lightSource = new SnapshotLightSource(level, detachedChunks);
		LevelLightEngine lightEngine = new LevelLightEngine(lightSource, true, level.dimensionType().hasSkyLight());
		installSavedLight(lightEngine, snapshot);

		MessageDigest visualDigest = sha256();
		List<MapEncodedPacket> chunkPackets = new ArrayList<>(snapshot.chunks().size());
		for (MapChunkSnapshot chunkSnapshot : snapshot.chunks()) {
			LevelChunk chunk = detachedChunks.get(chunkSnapshot.pos());
			ClientboundLevelChunkWithLightPacket packet = PacketContext.supplyWithContext(
					worker.connection,
					() -> new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null)
			);
			MapEncodedPacket encoded = encode(level, worker, packet);
			if (encoded == null) {
				throw new IllegalStateException("Worker packet patcher rejected map chunk " + chunkSnapshot.pos());
			}
			chunkPackets.add(encoded);
			if (chunkSnapshot.role() == MapSnapshotManifest.Role.SOURCE) {
				updateVisualDigest(visualDigest, (byte) 1, encoded);
			}
		}

		List<MapEncodedPacket> entityPackets = new ArrayList<>();
		int nextEntityId = FIRST_SYNTHETIC_ENTITY_ID;
		for (MapChunkSnapshot chunkSnapshot : snapshot.chunks()) {
			for (var itemDisplayTag : chunkSnapshot.itemDisplays()) {
				Entity entity = EntityType.loadEntityRecursive(itemDisplayTag.copy(), level, EntitySpawnReason.LOAD, loaded -> loaded);
				if (!(entity instanceof Display.ItemDisplay itemDisplay)) {
					throw new IllegalStateException("Saved item_display did not materialize as ItemDisplay in " + chunkSnapshot.pos());
				}
				itemDisplay.setId(nextEntityId--);
				itemDisplay.updateDataBeforeSync();
				List<Packet<? extends ClientGamePacketListener>> packets = new ArrayList<>();
				packets.add(new ClientboundAddEntityPacket(
						itemDisplay.getId(),
						itemDisplay.getUUID(),
						itemDisplay.getX(),
						itemDisplay.getY(),
						itemDisplay.getZ(),
						itemDisplay.getXRot(),
						itemDisplay.getYRot(),
						EntityType.ITEM_DISPLAY,
						0,
						itemDisplay.getDeltaMovement(),
						itemDisplay.getYHeadRot()
				));
				var values = itemDisplay.getEntityData().getNonDefaultValues();
				if (values != null && !values.isEmpty()) {
					packets.add(new ClientboundSetEntityDataPacket(itemDisplay.getId(), values));
				}
				for (Packet<? extends ClientGamePacketListener> packet : packets) {
					MapEncodedPacket encoded = encode(level, worker, packet);
					if (encoded == null) {
						throw new IllegalStateException("Worker packet patcher rejected saved ItemDisplay packet");
					}
					entityPackets.add(encoded);
					if (chunkSnapshot.role() == MapSnapshotManifest.Role.SOURCE) {
						updateVisualDigest(visualDigest, (byte) 2, encoded);
					}
				}
			}
		}

		return new PacketBatch(
				snapshot.manifest().key(),
				snapshot.manifest().snapshotFingerprint(),
				HexFormat.of().formatHex(visualDigest.digest()),
				List.copyOf(chunkPackets),
				List.copyOf(entityPackets)
		);
	}

	private static void installSavedLight(LevelLightEngine lightEngine, MapTileSnapshot snapshot) {
		for (MapChunkSnapshot chunk : snapshot.chunks()) {
			lightEngine.retainData(chunk.pos(), true);
			for (MapChunkSnapshot.SectionSnapshot section : chunk.sections()) {
				SectionPos sectionPos = SectionPos.of(chunk.pos(), section.y());
				if (section.blockLight() != null) {
					lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, section.blockLight().copy());
				}
				if (section.skyLight() != null) {
					lightEngine.queueSectionData(LightLayer.SKY, sectionPos, section.skyLight().copy());
				}
			}
			lightEngine.setLightEnabled(chunk.pos(), true);
		}
		// ClientboundLightUpdatePacketData reads the queued DataLayers directly.
		// Do not run light propagation here: these are exact saved light snapshots,
		// and recomputing them against the detached render window can alter border
		// lighting before the worker ever receives the vanilla packet.
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void updateVisualDigest(MessageDigest digest, byte kind, MapEncodedPacket packet) {
		digest.update(kind);
		byte[] type = packet.packetTypeId().getBytes(StandardCharsets.UTF_8);
		updateInt(digest, type.length);
		digest.update(type);
		byte[] payload = packet.packetBytes();
		updateInt(digest, payload.length);
		digest.update(payload);
	}

	private static void updateInt(MessageDigest digest, int value) {
		digest.update((byte) (value >>> 24));
		digest.update((byte) (value >>> 16));
		digest.update((byte) (value >>> 8));
		digest.update((byte) value);
	}

	private static MapEncodedPacket encode(
			ServerLevel level,
			ServerPlayer worker,
			Packet<? extends ClientGamePacketListener> packet
	) {
		RendererBotPayloads.ShadowPacketData encoded = RendererBotShadowPacketCodec.encodePacket(
				level.registryAccess(),
				worker.connection,
				packet
		);
		return encoded == null ? null : new MapEncodedPacket(encoded.packetTypeId(), encoded.packetBytes());
	}

	public record PacketBatch(
			MapTileKey key,
			String snapshotFingerprint,
			String visualFingerprint,
			List<MapEncodedPacket> chunkPackets,
			List<MapEncodedPacket> itemDisplayPackets
	) {
	}

	public record MapEncodedPacket(String packetTypeId, byte[] packetBytes) {
		public MapEncodedPacket {
			packetBytes = packetBytes.clone();
		}

		@Override
		public byte[] packetBytes() {
			return this.packetBytes.clone();
		}
	}

	private static final class SnapshotLightSource implements LightChunkGetter, BlockGetter {
		private final ServerLevel level;
		private final Map<Long, LevelChunk> chunks;
		private final Map<Long, LevelChunk> emptyBoundaryChunks = new LinkedHashMap<>();

		private SnapshotLightSource(ServerLevel level, Map<ChunkPos, LevelChunk> chunks) {
			this.level = level;
			this.chunks = new LinkedHashMap<>();
			for (Map.Entry<ChunkPos, LevelChunk> entry : chunks.entrySet()) {
				this.chunks.put(entry.getKey().toLong(), entry.getValue());
			}
		}

		@Override
		public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
			long key = ChunkPos.asLong(chunkX, chunkZ);
			LevelChunk existing = this.chunks.get(key);
			if (existing != null) {
				return existing;
			}
			return this.emptyBoundaryChunks.computeIfAbsent(key, ignored -> new LevelChunk(this.level, new ChunkPos(chunkX, chunkZ)));
		}

		@Override
		public BlockGetter getLevel() {
			return this;
		}

		@Override
		public BlockEntity getBlockEntity(BlockPos pos) {
			LevelChunk chunk = this.chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
			return chunk == null ? null : chunk.getBlockEntity(pos);
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			LevelChunk chunk = this.chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
			return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			LevelChunk chunk = this.chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
			return chunk == null ? Fluids.EMPTY.defaultFluidState() : chunk.getFluidState(pos);
		}

		@Override
		public int getHeight() {
			return this.level.getHeight();
		}

		@Override
		public int getMinY() {
			return this.level.getMinY();
		}
	}
}
