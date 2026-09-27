package com.lostglade.server.maprender;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;

public record MapTileSourceState(
		MapTileKey key,
		long existingChunkMask,
		String sourceFingerprint,
		int existingChunkCount
) {
	static MapTileSourceState from(
			MapTileKey key,
			MapRenderProfile profile,
			Collection<MapChunkExistenceIndex.ChunkStamp> chunks
	) {
		return from(key, profile, chunks, null);
	}

	static MapTileSourceState from(
			MapTileKey key,
			MapRenderProfile profile,
			Collection<MapChunkExistenceIndex.ChunkStamp> chunks,
			MapChunkExistenceIndex entityIndex
	) {
		if (profile.tileChunks() > 8) {
			throw new IllegalArgumentException("existingChunkMask supports at most 64 source chunks per base tile");
		}
		MapChunkExistenceIndex.ChunkStamp[] bySlot = new MapChunkExistenceIndex.ChunkStamp[profile.tileChunks() * profile.tileChunks()];
		long mask = 0L;
		for (MapChunkExistenceIndex.ChunkStamp chunk : chunks) {
			int localX = Math.floorMod(chunk.chunkX(), profile.tileChunks());
			int localZ = Math.floorMod(chunk.chunkZ(), profile.tileChunks());
			int slot = localZ * profile.tileChunks() + localX;
			bySlot[slot] = chunk;
			mask |= 1L << slot;
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			ByteBuffer buffer = ByteBuffer.allocate(2 + Integer.BYTES * 5 + Long.BYTES).order(ByteOrder.BIG_ENDIAN);
			for (int slot = 0; slot < bySlot.length; slot++) {
				MapChunkExistenceIndex.ChunkStamp terrain = bySlot[slot];
				updateStampDigest(digest, buffer, (byte) 1, slot, terrain);
				MapChunkExistenceIndex.ChunkStamp entity = terrain == null || entityIndex == null
						? null
						: entityIndex.chunk(terrain.chunkX(), terrain.chunkZ()).orElse(null);
				updateStampDigest(digest, buffer, (byte) 2, slot, entity);
			}
			return new MapTileSourceState(key, mask, HexFormat.of().formatHex(digest.digest()), Long.bitCount(mask));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void updateStampDigest(
			MessageDigest digest,
			ByteBuffer buffer,
			byte storageKind,
			int slot,
			MapChunkExistenceIndex.ChunkStamp stamp
	) {
		buffer.clear();
		buffer.put(storageKind);
		buffer.put((byte) (stamp == null ? 0 : 1));
		buffer.putInt(slot);
		if (stamp == null) {
			buffer.putInt(0).putInt(0).putInt(0).putInt(0).putLong(0L);
		} else {
			buffer.putInt(stamp.chunkX())
					.putInt(stamp.chunkZ())
					.putInt(stamp.locationEntry())
					.putInt(stamp.regionX() * 31 + stamp.regionZ())
					.putLong(stamp.storageTimestamp());
		}
		digest.update(buffer.array());
	}
}
