package com.lostglade.server.maprender;

import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MapChunkExistenceIndex {
	private static final int REGION_SIDE_CHUNKS = 32;
	private static final int LOCATION_HEADER_BYTES = 4096;
	private static final int TIMESTAMP_HEADER_BYTES = 4096;
	private static final int HEADER_BYTES = LOCATION_HEADER_BYTES + TIMESTAMP_HEADER_BYTES;
	private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

	private final Map<ChunkCoordinate, ChunkStamp> chunks;

	private MapChunkExistenceIndex(Map<ChunkCoordinate, ChunkStamp> chunks) {
		this.chunks = Map.copyOf(chunks);
	}

	public static MapChunkExistenceIndex scan(Path regionDirectory) throws IOException {
		Map<ChunkCoordinate, ChunkStamp> chunks = new HashMap<>();
		if (regionDirectory == null || !Files.isDirectory(regionDirectory)) {
			return new MapChunkExistenceIndex(chunks);
		}
		List<Path> regionFiles;
		try (var paths = Files.list(regionDirectory)) {
			regionFiles = paths
					.filter(Files::isRegularFile)
					.filter(path -> REGION_FILE_PATTERN.matcher(path.getFileName().toString()).matches())
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.toList();
		}
		for (Path regionFile : regionFiles) {
			scanRegionFile(regionFile, chunks);
		}
		return new MapChunkExistenceIndex(chunks);
	}

	private static void scanRegionFile(Path regionFile, Map<ChunkCoordinate, ChunkStamp> chunks) throws IOException {
		Matcher matcher = REGION_FILE_PATTERN.matcher(regionFile.getFileName().toString());
		if (!matcher.matches() || Files.size(regionFile) < HEADER_BYTES) {
			return;
		}
		int regionX = Integer.parseInt(matcher.group(1));
		int regionZ = Integer.parseInt(matcher.group(2));
		ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
		try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
			while (header.hasRemaining() && channel.read(header) >= 0) {
				// Read only the two MCA header sectors. Chunk payload sectors are never touched here.
			}
		}
		if (header.position() < HEADER_BYTES) {
			return;
		}
		header.flip();
		for (int slot = 0; slot < REGION_SIDE_CHUNKS * REGION_SIDE_CHUNKS; slot++) {
			int locationEntry = header.getInt(slot * Integer.BYTES);
			int sectorOffset = locationEntry >>> 8;
			int sectorCount = locationEntry & 0xFF;
			if (sectorOffset < 2 || sectorCount <= 0) {
				continue;
			}
			long timestamp = Integer.toUnsignedLong(header.getInt(LOCATION_HEADER_BYTES + slot * Integer.BYTES));
			int localX = slot & 31;
			int localZ = slot >>> 5;
			int chunkX = Math.addExact(Math.multiplyExact(regionX, REGION_SIDE_CHUNKS), localX);
			int chunkZ = Math.addExact(Math.multiplyExact(regionZ, REGION_SIDE_CHUNKS), localZ);
			ChunkStamp stamp = new ChunkStamp(chunkX, chunkZ, regionX, regionZ, locationEntry, timestamp);
			chunks.put(new ChunkCoordinate(chunkX, chunkZ), stamp);
		}
	}

	public int chunkCount() {
		return this.chunks.size();
	}

	public Collection<ChunkStamp> chunks() {
		return this.chunks.values();
	}

	public Optional<ChunkStamp> chunk(int chunkX, int chunkZ) {
		return Optional.ofNullable(this.chunks.get(new ChunkCoordinate(chunkX, chunkZ)));
	}

	public Map<MapTileKey, MapTileSourceState> baseTiles(Identifier dimensionId, MapRenderProfile profile) {
		return baseTiles(dimensionId, profile, null);
	}

	public Map<MapTileKey, MapTileSourceState> baseTiles(
			Identifier dimensionId,
			MapRenderProfile profile,
			MapChunkExistenceIndex entityIndex
	) {
		Map<MapTileKey, List<ChunkStamp>> grouped = new HashMap<>();
		for (ChunkStamp chunk : this.chunks.values()) {
			MapTileKey key = MapTileKey.fromChunk(dimensionId, chunk.chunkX(), chunk.chunkZ(), profile);
			grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(chunk);
		}
		List<MapTileKey> keys = new ArrayList<>(grouped.keySet());
		keys.sort(MapTileKey::compareTo);
		Map<MapTileKey, MapTileSourceState> result = new LinkedHashMap<>();
		for (MapTileKey key : keys) {
			result.put(key, MapTileSourceState.from(key, profile, grouped.get(key), entityIndex));
		}
		return Map.copyOf(result);
	}

	private record ChunkCoordinate(int chunkX, int chunkZ) {
	}

	public record ChunkStamp(
			int chunkX,
			int chunkZ,
			int regionX,
			int regionZ,
			int locationEntry,
			long storageTimestamp
	) {
	}
}
