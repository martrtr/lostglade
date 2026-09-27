package com.lostglade.server.maprender;

import net.minecraft.world.level.ChunkPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MapTileSnapshot(
		MapSnapshotManifest manifest,
		List<MapChunkSnapshot> chunks
) {
	public MapTileSnapshot {
		Objects.requireNonNull(manifest, "manifest");
		chunks = List.copyOf(chunks);
		if (chunks.size() != manifest.chunks().size()) {
			throw new IllegalArgumentException("Snapshot chunk count does not match strict manifest");
		}
	}

	public Map<ChunkPos, MapChunkSnapshot> chunksByPosition() {
		Map<ChunkPos, MapChunkSnapshot> result = new LinkedHashMap<>();
		for (MapChunkSnapshot chunk : this.chunks) {
			result.put(chunk.pos(), chunk);
		}
		return Map.copyOf(result);
	}
}
