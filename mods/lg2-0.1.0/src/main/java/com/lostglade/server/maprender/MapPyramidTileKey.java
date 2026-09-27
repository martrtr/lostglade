package com.lostglade.server.maprender;

import net.minecraft.resources.Identifier;

import java.util.Objects;

public record MapPyramidTileKey(
		Identifier dimensionId,
		int level,
		long tileX,
		long tileZ,
		int renderProfileVersion
) implements Comparable<MapPyramidTileKey> {
	public MapPyramidTileKey {
		Objects.requireNonNull(dimensionId, "dimensionId");
		if (level < 1 || level > 30) throw new IllegalArgumentException("level must be in [1, 30]");
		if (renderProfileVersion <= 0) throw new IllegalArgumentException("renderProfileVersion must be positive");
	}

	public static MapPyramidTileKey parentOf(MapTileKey baseKey, int level) {
		Objects.requireNonNull(baseKey, "baseKey");
		if (level < 1 || level > 30) throw new IllegalArgumentException("level must be in [1, 30]");
		long span = 1L << level;
		return new MapPyramidTileKey(
				baseKey.dimensionId(),
				level,
				Math.floorDiv(baseKey.tileX(), span),
				Math.floorDiv(baseKey.tileZ(), span),
				baseKey.renderProfileVersion()
		);
	}

	@Override
	public int compareTo(MapPyramidTileKey other) {
		int dimension = this.dimensionId.toString().compareTo(other.dimensionId.toString());
		if (dimension != 0) return dimension;
		int profile = Integer.compare(this.renderProfileVersion, other.renderProfileVersion);
		if (profile != 0) return profile;
		int lod = Integer.compare(this.level, other.level);
		if (lod != 0) return lod;
		int x = Long.compare(this.tileX, other.tileX);
		return x != 0 ? x : Long.compare(this.tileZ, other.tileZ);
	}
}
