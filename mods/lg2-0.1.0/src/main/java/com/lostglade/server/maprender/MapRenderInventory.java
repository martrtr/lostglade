package com.lostglade.server.maprender;

import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MapRenderInventory {
	private MapRenderInventory() {
	}

	public static Snapshot inspect(
			MapChunkExistenceIndex index,
			MapTileStore store,
			Identifier dimensionId,
			MapRenderProfile profile,
			String profileHash
	) throws IOException {
		return inspect(index, null, store, dimensionId, profile, profileHash);
	}

	public static Snapshot inspect(
			MapChunkExistenceIndex index,
			MapChunkExistenceIndex entityIndex,
			MapTileStore store,
			Identifier dimensionId,
			MapRenderProfile profile,
			String profileHash
	) throws IOException {
		Map<MapTileKey, MapTileSourceState> sourceTiles = index.baseTiles(dimensionId, profile, entityIndex);
		List<TileStatus> statuses = new ArrayList<>(sourceTiles.size());
		for (Map.Entry<MapTileKey, MapTileSourceState> entry : sourceTiles.entrySet()) {
			MapTileSourceState effectiveSource = MapSnapshotManifest.create(index, entityIndex, entry.getKey(), profile).sourceState();
			Optional<MapTileMetadata> metadata;
			try {
				metadata = store.readMetadata(entry.getKey(), profileHash);
			} catch (IOException exception) {
				statuses.add(new TileStatus(entry.getKey(), effectiveSource, null, false, "metadata-read-failed"));
				continue;
			}
			String reason = staleReason(metadata.orElse(null), profileHash, profile, effectiveSource);
			statuses.add(new TileStatus(entry.getKey(), effectiveSource, metadata.orElse(null), reason == null, reason));
		}
		statuses.sort(Comparator.comparing(TileStatus::key));
		return new Snapshot(List.copyOf(statuses));
	}

	private static String staleReason(
			MapTileMetadata metadata,
			String profileHash,
			MapRenderProfile profile,
			MapTileSourceState source
	) {
		if (metadata == null) {
			return "missing";
		}
		if (!profileHash.equals(metadata.profileHash())) {
			return "profile-changed";
		}
		if (metadata.pixelWidth() != profile.tilePixels() || metadata.pixelHeight() != profile.tilePixels()) {
			return "geometry-changed";
		}
		if (!source.sourceFingerprint().equals(metadata.sourceFingerprint())
				|| source.existingChunkMask() != metadata.existingChunkMask()) {
			return "source-changed";
		}
		if (metadata.resultChecksum() == null || metadata.resultChecksum().isBlank()) {
			return "invalid-metadata";
		}
		return null;
	}

	public record Snapshot(List<TileStatus> tiles) {
		public long renderNeededCount() {
			return this.tiles.stream().filter(status -> !status.current()).count();
		}

		public long currentCount() {
			return this.tiles.stream().filter(TileStatus::current).count();
		}
	}

	public record TileStatus(
			MapTileKey key,
			MapTileSourceState source,
			MapTileMetadata metadata,
			boolean current,
			String staleReason
	) {
	}
}
