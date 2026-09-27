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
}
