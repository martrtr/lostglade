package com.lostglade.server.maprender;

import net.minecraft.world.level.ChunkPos;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record MapSnapshotManifest(
		MapTileKey key,
		MapTileSourceState sourceState,
		String snapshotFingerprint,
		List<ChunkEntry> chunks
) {
	public MapSnapshotManifest {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(sourceState, "sourceState");
		Objects.requireNonNull(snapshotFingerprint, "snapshotFingerprint");
		chunks = List.copyOf(chunks);
	}

	public static MapSnapshotManifest create(
			MapChunkExistenceIndex terrainIndex,
			MapChunkExistenceIndex entityIndex,
			MapTileKey key,
			MapRenderProfile profile
	) {
		Objects.requireNonNull(terrainIndex, "terrainIndex");
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(profile, "profile");
		MapTileSourceState sourceState = terrainIndex.baseTiles(key.dimensionId(), profile, entityIndex).get(key);
		if (sourceState == null || sourceState.existingChunkCount() <= 0) {
			throw new IllegalArgumentException("Map tile has no existing terrain chunks: " + key);
		}

		long minChunkXLong = Math.multiplyExact(key.tileX(), profile.tileChunks());
		long minChunkZLong = Math.multiplyExact(key.tileZ(), profile.tileChunks());
		int minChunkX = Math.toIntExact(minChunkXLong);
		int minChunkZ = Math.toIntExact(minChunkZLong);
		int halo = profile.haloChunks();
		List<ChunkEntry> entries = new ArrayList<>();
		for (int localZ = -halo; localZ < profile.tileChunks() + halo; localZ++) {
			for (int localX = -halo; localX < profile.tileChunks() + halo; localX++) {
				int chunkX = Math.addExact(minChunkX, localX);
				int chunkZ = Math.addExact(minChunkZ, localZ);
				MapChunkExistenceIndex.ChunkStamp terrainStamp = terrainIndex.chunk(chunkX, chunkZ).orElse(null);
				if (terrainStamp == null) {
					continue;
				}
				boolean source = localX >= 0 && localX < profile.tileChunks()
						&& localZ >= 0 && localZ < profile.tileChunks();
				MapChunkExistenceIndex.ChunkStamp entityStamp = entityIndex == null
						? null
						: entityIndex.chunk(chunkX, chunkZ).orElse(null);
				entries.add(new ChunkEntry(
						new ChunkPos(chunkX, chunkZ),
						source ? Role.SOURCE : Role.HALO,
						terrainStamp,
						entityStamp
				));
			}
		}
		entries.sort(Comparator
				.comparing((ChunkEntry entry) -> entry.role() == Role.SOURCE ? 0 : 1)
				.thenComparingInt(entry -> entry.pos().z)
				.thenComparingInt(entry -> entry.pos().x));
		// The committed tile is a world-coordinate asset for its SOURCE chunk set; halo is render context only.
		// Halo chunks are read and sent to the client only to make vanilla meshing,
		// light and face culling correct at the boundary. Their MCA timestamps may
		// advance independently (for example because a neighbouring loaded chunk is
		// autosaved), and must not make this tile dirty forever.
		String snapshotFingerprint = fingerprint(entries.stream()
				.filter(entry -> entry.role() == Role.SOURCE)
				.toList());
		MapTileSourceState cacheSourceState = new MapTileSourceState(
				key,
				sourceState.existingChunkMask(),
				snapshotFingerprint,
				sourceState.existingChunkCount()
		);
		return new MapSnapshotManifest(key, cacheSourceState, snapshotFingerprint, entries);
	}

	public List<ChunkEntry> sourceChunks() {
		return this.chunks.stream().filter(entry -> entry.role() == Role.SOURCE).toList();
	}

	private static String fingerprint(List<ChunkEntry> entries) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			ByteBuffer buffer = ByteBuffer.allocate(3 + Integer.BYTES * 7 + Long.BYTES * 2).order(ByteOrder.BIG_ENDIAN);
			for (ChunkEntry entry : entries) {
				buffer.clear();
				buffer.put((byte) entry.role().ordinal());
				buffer.putInt(entry.pos().x).putInt(entry.pos().z);
				putStamp(buffer, entry.terrainStamp());
				putStamp(buffer, entry.entityStamp());
				digest.update(buffer.array());
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void putStamp(ByteBuffer buffer, MapChunkExistenceIndex.ChunkStamp stamp) {
		buffer.put((byte) (stamp == null ? 0 : 1));
		if (stamp == null) {
			buffer.putInt(0).putInt(0).putLong(0L);
			return;
		}
		buffer.putInt(stamp.locationEntry())
				.putInt(stamp.regionX() * 31 + stamp.regionZ())
				.putLong(stamp.storageTimestamp());
	}

	public enum Role {
		SOURCE,
		HALO
	}

	public record ChunkEntry(
			ChunkPos pos,
			Role role,
			MapChunkExistenceIndex.ChunkStamp terrainStamp,
			MapChunkExistenceIndex.ChunkStamp entityStamp
	) {
		public ChunkEntry {
			Objects.requireNonNull(pos, "pos");
			Objects.requireNonNull(role, "role");
			Objects.requireNonNull(terrainStamp, "terrainStamp");
		}
	}
}
