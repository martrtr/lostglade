package com.lostglade.server.maprender;

import net.minecraft.resources.Identifier;

import java.util.Objects;

public record MapTileKey(
		Identifier dimensionId,
		long tileX,
		long tileZ,
		int renderProfileVersion
) implements Comparable<MapTileKey> {
	public MapTileKey {
		Objects.requireNonNull(dimensionId, "dimensionId");
		if (renderProfileVersion <= 0) {
			throw new IllegalArgumentException("renderProfileVersion must be positive");
		}
	}

	public static MapTileKey fromBlock(Identifier dimensionId, long blockX, long blockZ, MapRenderProfile profile) {
		Objects.requireNonNull(profile, "profile");
		return new MapTileKey(
				dimensionId,
				Math.floorDiv(blockX, profile.tileBlocks()),
				Math.floorDiv(blockZ, profile.tileBlocks()),
				profile.version()
		);
	}

	public static MapTileKey fromChunk(Identifier dimensionId, int chunkX, int chunkZ, MapRenderProfile profile) {
		Objects.requireNonNull(profile, "profile");
		return new MapTileKey(
				dimensionId,
				Math.floorDiv((long) chunkX, profile.tileChunks()),
				Math.floorDiv((long) chunkZ, profile.tileChunks()),
				profile.version()
		);
	}

	public long minBlockX(MapRenderProfile profile) {
		return Math.multiplyExact(this.tileX, profile.tileBlocks());
	}

	public long minBlockZ(MapRenderProfile profile) {
		return Math.multiplyExact(this.tileZ, profile.tileBlocks());
	}

	@Override
	public int compareTo(MapTileKey other) {
		int dimensionCompare = this.dimensionId.toString().compareTo(other.dimensionId.toString());
		if (dimensionCompare != 0) {
			return dimensionCompare;
		}
		int xCompare = Long.compare(this.tileX, other.tileX);
		if (xCompare != 0) {
			return xCompare;
		}
		int zCompare = Long.compare(this.tileZ, other.tileZ);
		if (zCompare != 0) {
			return zCompare;
		}
		return Integer.compare(this.renderProfileVersion, other.renderProfileVersion);
	}

	/**
	 * Tile coordinates are normally scanned as dense rectangular ranges. The
	 * generated record hash combines the coordinates linearly, making many of
	 * those keys collide in immutable maps. Mix both 64-bit coordinates before
	 * narrowing to the map hash.
	 */
	@Override
	public int hashCode() {
		long hash = this.tileX * 0x9E3779B97F4A7C15L;
		hash ^= Long.rotateLeft(this.tileZ * 0xC2B2AE3D27D4EB4FL, 29);
		hash ^= (long) this.dimensionId.hashCode() * 0x165667B19E3779F9L;
		hash ^= (long) this.renderProfileVersion * 0x85EBCA77C2B2AE63L;
		hash ^= hash >>> 33;
		hash *= 0xFF51AFD7ED558CCDL;
		hash ^= hash >>> 33;
		return (int) (hash ^ (hash >>> 32));
	}
}
