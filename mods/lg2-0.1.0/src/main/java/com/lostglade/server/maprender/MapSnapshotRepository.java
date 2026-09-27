package com.lostglade.server.maprender;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MapSnapshotRepository {
	private static final String ITEM_DISPLAY_ID = "minecraft:item_display";

	private MapSnapshotRepository() {
	}

	public static MapTileSnapshot readTile(
			ServerLevel level,
			Path worldRoot,
			MapChunkExistenceIndex terrainIndex,
			MapChunkExistenceIndex entityIndex,
			MapTileKey key,
			MapRenderProfile profile
	) throws IOException {
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(worldRoot, "worldRoot");
		MapSnapshotManifest manifest = MapSnapshotManifest.create(terrainIndex, entityIndex, key, profile);
		Path regionDirectory = worldRoot.resolve("region");
		Path entityDirectory = worldRoot.resolve("entities");
		RegionStorageInfo chunkInfo = new RegionStorageInfo(level.getServer().getWorldData().getLevelName(), level.dimension(), "chunk");
		RegionStorageInfo entityInfo = new RegionStorageInfo(level.getServer().getWorldData().getLevelName(), level.dimension(), "entity");
		PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());

		try (SimpleRegionStorage chunkStorage = new SimpleRegionStorage(
				chunkInfo,
				regionDirectory,
				level.getServer().getFixerUpper(),
				false,
				DataFixTypes.CHUNK
		)) {
			SimpleRegionStorage entityStorage = null;
			try {
				if (Files.isDirectory(entityDirectory)) {
					entityStorage = new SimpleRegionStorage(
							entityInfo,
							entityDirectory,
							level.getServer().getFixerUpper(),
							false,
							DataFixTypes.ENTITY_CHUNK
					);
				}
				List<MapChunkSnapshot> chunks = new ArrayList<>(manifest.chunks().size());
				List<MapSnapshotManifest.ChunkEntry> renderableEntries = new ArrayList<>(manifest.chunks().size());
				int renderableSourceChunks = 0;
				for (MapSnapshotManifest.ChunkEntry entry : manifest.chunks()) {
					CompoundTag chunkTag = chunkStorage.read(entry.pos()).join().orElseThrow(() ->
							new StaleSnapshotException("Manifest terrain chunk disappeared: " + entry.pos()));
					ChunkStatus status = SerializableChunkData.getChunkStatusFromTag(chunkTag);
					if (status == null || status.isBefore(ChunkStatus.FULL)) {
						// MCA headers also contain proto-chunks. They are candidates, not renderable terrain.
						// Keep their header stamp in the source fingerprint so promotion to FULL invalidates
						// the tile, but omit them from the vanilla client scene for this snapshot.
						continue;
					}
					SerializableChunkData data = SerializableChunkData.parse(level, factory, chunkTag);
					if (!entry.pos().equals(data.chunkPos())) {
						throw new StaleSnapshotException("Stored chunk position mismatch: expected " + entry.pos() + " got " + data.chunkPos());
					}
					List<CompoundTag> itemDisplays = entityStorage == null
							? List.of()
							: readItemDisplays(entityStorage, entry.pos());
					renderableEntries.add(entry);
					chunks.add(MapChunkSnapshot.from(data, entry.role(), itemDisplays));
					if (entry.role() == MapSnapshotManifest.Role.SOURCE) renderableSourceChunks++;
				}
				if (renderableSourceChunks == 0) {
					throw new NoRenderableSourceChunksException("Tile has no FULL source chunks: " + key);
				}
				MapSnapshotManifest renderableManifest = new MapSnapshotManifest(
						manifest.key(), manifest.sourceState(), manifest.snapshotFingerprint(), renderableEntries
				);
				return new MapTileSnapshot(renderableManifest, chunks);
			} finally {
				if (entityStorage != null) {
					entityStorage.close();
				}
			}
		}
	}

	static List<CompoundTag> readItemDisplays(SimpleRegionStorage entityStorage, ChunkPos pos) {
		Optional<CompoundTag> optionalTag = entityStorage.read(pos).join();
		if (optionalTag.isEmpty()) {
			return List.of();
		}
		List<CompoundTag> result = new ArrayList<>();
		for (CompoundTag entityTag : optionalTag.get().getListOrEmpty("Entities").compoundStream().toList()) {
			collectItemDisplays(entityTag, result);
		}
		return List.copyOf(result);
	}

	static List<CompoundTag> filterItemDisplays(CompoundTag entityChunkTag) {
		List<CompoundTag> result = new ArrayList<>();
		if (entityChunkTag == null) {
			return result;
		}
		for (CompoundTag entityTag : entityChunkTag.getListOrEmpty("Entities").compoundStream().toList()) {
			collectItemDisplays(entityTag, result);
		}
		return List.copyOf(result);
	}

	private static void collectItemDisplays(CompoundTag entityTag, List<CompoundTag> result) {
		if (entityTag == null) {
			return;
		}
		if (ITEM_DISPLAY_ID.equals(entityTag.getStringOr("id", ""))) {
			CompoundTag copy = entityTag.copy();
			copy.remove("Passengers");
			result.add(copy);
		}
		ListTag passengers = entityTag.getListOrEmpty("Passengers");
		for (Tag passenger : passengers) {
			passenger.asCompound().ifPresent(compound -> collectItemDisplays(compound, result));
		}
	}

	public static final class NoRenderableSourceChunksException extends IOException {
		public NoRenderableSourceChunksException(String message) {
			super(message);
		}
	}

	public static final class StaleSnapshotException extends IOException {
		public StaleSnapshotException(String message) {
			super(message);
		}
	}
}
