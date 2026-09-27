package com.lostglade.server.maprender;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class YandexMapRenderPhaseOneTest {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");

	private YandexMapRenderPhaseOneTest() {
	}

	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		negativeCoordinateTileMathUsesFloorDivision();
		regionHeadersDiscoverOnlyExistingChunks();
		sourceFingerprintChangesWithHeaderStamp();
		atomicTileStorePublishesWholeGenerations();
		renderProfileVersionsUsePhysicalBaseNamespaces();
		metadataOnlyRefreshPreservesCommittedPixels();
		inventoryDetectsMissingAndChangedTiles();
		mapRenderPackageCannotLoadGameplayChunks();
		String smokeRegion = System.getenv("LG2_YANDEX_MAP_REGION");
		if (smokeRegion != null && !smokeRegion.isBlank()) {
			realWorldReadOnlySmoke(Path.of(smokeRegion));
		}
		System.out.println("Yandex map renderer Phase 0/1 checks passed");
	}

	private static void negativeCoordinateTileMathUsesFloorDivision() {
		MapRenderProfile profile = MapRenderProfile.CURRENT;
		require(MapTileKey.fromBlock(OVERWORLD, 0, 0, profile).tileX() == 0, "block 0 must be tile 0");
		require(MapTileKey.fromBlock(OVERWORLD, 15, 0, profile).tileX() == 0, "block 15 must be tile 0");
		require(MapTileKey.fromBlock(OVERWORLD, 16, 0, profile).tileX() == 1, "block 16 must be tile 1");
		require(MapTileKey.fromBlock(OVERWORLD, -1, 0, profile).tileX() == -1, "block -1 must be tile -1");
		require(MapTileKey.fromBlock(OVERWORLD, -16, 0, profile).tileX() == -1, "block -16 must be tile -1");
		require(MapTileKey.fromBlock(OVERWORLD, -17, 0, profile).tileX() == -2, "block -17 must be tile -2");
		require(MapTileKey.fromChunk(OVERWORLD, -1, -1, profile).tileX() == -1, "chunk -1 must be negative tile");
		require(MapTileKey.fromChunk(OVERWORLD, -8, -8, profile).tileZ() == -8, "detail profile must map one chunk to one tile");
		require(MapTileKey.fromChunk(OVERWORLD, -9, -9, profile).tileZ() == -9, "neighbor chunks must remain distinct detail tiles");
	}

	private static void regionHeadersDiscoverOnlyExistingChunks() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-index-");
		try {
			Path region = Files.createDirectories(root.resolve("region"));
			writeRegion(region.resolve("r.0.0.mca"), List.of(
					new HeaderEntry(0, 0, 2, 1, 100),
					new HeaderEntry(7, 7, 3, 1, 101),
					new HeaderEntry(8, 0, 4, 1, 102),
					new HeaderEntry(31, 31, 5, 1, 103)
			));
			writeRegion(region.resolve("r.-1.-1.mca"), List.of(
					new HeaderEntry(31, 31, 2, 1, 200)
			));
			MapChunkExistenceIndex index = MapChunkExistenceIndex.scan(region);
			require(index.chunkCount() == 5, "index must contain exactly five header-present chunks");
			require(index.chunk(0, 0).isPresent(), "origin chunk must be present");
			require(index.chunk(8, 0).isPresent(), "second base tile chunk must be present");
			require(index.chunk(-1, -1).isPresent(), "negative region coordinate must decode with floor semantics");
			require(index.chunk(1, 0).isEmpty(), "zero location header must stay absent");

			Map<MapTileKey, MapTileSourceState> tiles = index.baseTiles(OVERWORLD, MapRenderProfile.CURRENT);
			require(tiles.size() == 5, "detail profile must produce one base tile per existing chunk");
			MapTileSourceState origin = tiles.get(new MapTileKey(OVERWORLD, 0, 0, MapRenderProfile.CURRENT.version()));
			require(origin != null && origin.existingChunkCount() == 1, "origin detail tile must contain exactly one source chunk");
			require(origin.existingChunkMask() == 1L, "single-chunk detail tile must use only mask bit 0");
		} finally {
			deleteTree(root);
		}
	}

	private static void sourceFingerprintChangesWithHeaderStamp() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-fingerprint-");
		try {
			Path region = Files.createDirectories(root.resolve("region"));
			Path file = region.resolve("r.0.0.mca");
			writeRegion(file, List.of(new HeaderEntry(0, 0, 2, 1, 100)));
			MapTileSourceState before = onlyTile(MapChunkExistenceIndex.scan(region));
			writeRegion(file, List.of(new HeaderEntry(0, 0, 2, 1, 101)));
			MapTileSourceState after = onlyTile(MapChunkExistenceIndex.scan(region));
			require(!before.sourceFingerprint().equals(after.sourceFingerprint()), "MCA timestamp change must change source fingerprint");
		} finally {
			deleteTree(root);
		}
	}

	private static void atomicTileStorePublishesWholeGenerations() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-store-");
		try {
			MapTileStore store = new MapTileStore(root);
			MapTileKey key = new MapTileKey(OVERWORLD, -2, 3, MapRenderProfile.CURRENT.version());
			MapTileSourceState source = new MapTileSourceState(key, 1L, "source-a", 1);
			byte[] first = new byte[]{1, 2, 3, 4};
			byte[] second = new byte[]{9, 8, 7};
			String profileHash = MapRenderProfile.CURRENT.contractHash();
			store.commitBaseTile(key, profileHash, MapRenderProfile.CURRENT, source, first, 1L, "test-worker-a");
			require(java.util.Arrays.equals(store.read(key, profileHash).orElseThrow().imageBytes(), first), "first committed generation must be readable");
			store.commitBaseTile(key, profileHash, MapRenderProfile.CURRENT, source, second, 2L, "test-worker-b");
			MapTileStore.StoredTile stored = store.read(key, profileHash).orElseThrow();
			require(java.util.Arrays.equals(stored.imageBytes(), second), "current pointer must atomically expose the second generation");
			require(stored.metadata().renderedRevision() == 2L, "metadata and image must come from one generation");
			require(MapTileStore.checksum(second).equals(stored.metadata().resultChecksum()), "committed checksum must match image bytes");
			try (var paths = Files.walk(root)) {
				long temporaryPointers = paths.filter(path -> path.getFileName().toString().contains(".tmp-")).count();
				require(temporaryPointers == 0L, "successful commit must leave no temporary current pointer");
			}
		} finally {
			deleteTree(root);
		}
	}

	private static void renderProfileVersionsUsePhysicalBaseNamespaces() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-render-version-store-");
		try {
			MapTileStore store = new MapTileStore(root);
			String profileHash = "same-resource-profile";
			MapRenderProfile v1 = new MapRenderProfile(1, 128, 256, 1);
			MapRenderProfile current = MapRenderProfile.CURRENT;
			MapTileKey keyV1 = new MapTileKey(OVERWORLD, 2, -3, v1.version());
			MapTileKey keyV2 = new MapTileKey(OVERWORLD, 2, -3, current.version());
			MapTileSourceState sourceV1 = new MapTileSourceState(keyV1, 1L, "source-v1", 1);
			MapTileSourceState sourceV2 = new MapTileSourceState(keyV2, 1L, "source-v2", 1);
			byte[] bytesV1 = new byte[]{1, 1, 1};
			byte[] bytesV2 = new byte[]{2, 2, 2, 2};
			store.commitBaseTile(keyV1, profileHash, v1, sourceV1, bytesV1, 1L, "worker-v1");
			store.commitBaseTile(keyV2, profileHash, current, sourceV2, bytesV2, 2L, "worker-current");
			require(java.util.Arrays.equals(bytesV1, store.read(keyV1, profileHash).orElseThrow().imageBytes()), "v1 base cache must remain isolated");
			require(java.util.Arrays.equals(bytesV2, store.read(keyV2, profileHash).orElseThrow().imageBytes()), "current-profile base cache must remain isolated");
			require(Files.isRegularFile(root.resolve("minecraft_overworld/render-v1/profile-same-resource-profile/base/2/-3/current.json")), "v1 base tile must live below render-v1");
			require(Files.isRegularFile(root.resolve("minecraft_overworld/render-v" + current.version() + "/profile-same-resource-profile/base/2/-3/current.json")), "current base tile must live below its render-version namespace");
			boolean mismatchRejected = false;
			try {
				store.commitBaseTile(keyV1, profileHash, current, sourceV1, new byte[]{9}, 3L, "version-mismatch");
			} catch (IllegalArgumentException expected) {
				mismatchRejected = true;
			}
			require(mismatchRejected, "base store must reject mismatched render-profile versions");
		} finally {
			deleteTree(root);
		}
	}

	private static void metadataOnlyRefreshPreservesCommittedPixels() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-metadata-refresh-");
		try {
			MapTileStore store = new MapTileStore(root);
			MapTileKey key = new MapTileKey(OVERWORLD, 4, -5, MapRenderProfile.CURRENT.version());
			String profileHash = MapRenderProfile.CURRENT.contractHash();
			byte[] png = new byte[]{7, 6, 5, 4, 3};
			MapTileSourceState beforeSource = new MapTileSourceState(key, 1L, "header-a", 1);
			store.commitBaseTile(key, profileHash, MapRenderProfile.CURRENT, beforeSource, "visual-a", png, 77L, "worker-a");
			MapTileStore.StoredTile before = store.read(key, profileHash).orElseThrow();

			MapTileSourceState afterSource = new MapTileSourceState(key, 3L, "header-b", 2);
			MapTileMetadata refreshed = store.refreshSourceFingerprint(key, profileHash, afterSource, "visual-a");
			MapTileStore.StoredTile after = store.read(key, profileHash).orElseThrow();

			require(java.util.Arrays.equals(before.imageBytes(), after.imageBytes()), "metadata-only freshness refresh must not rewrite committed pixels");
			require(before.metadata().resultChecksum().equals(after.metadata().resultChecksum()), "metadata-only refresh must preserve image checksum");
			require(before.metadata().renderedAtEpochMs() == after.metadata().renderedAtEpochMs(), "metadata-only refresh must preserve render timestamp");
			require(before.metadata().renderedRevision() == after.metadata().renderedRevision(), "metadata-only refresh must preserve rendered revision");
			require("header-b".equals(refreshed.sourceFingerprint()) && refreshed.existingChunkMask() == 3L, "metadata-only refresh must advance cheap storage freshness");
			require("visual-a".equals(refreshed.visualFingerprint()), "metadata-only refresh must preserve the semantic scene identity");
		} finally {
			deleteTree(root);
		}
	}

	private static void inventoryDetectsMissingAndChangedTiles() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-inventory-");
		try {
			Path region = Files.createDirectories(root.resolve("world-region"));
			Path storeRoot = root.resolve("tile-store");
			Path regionFile = region.resolve("r.0.0.mca");
			writeRegion(regionFile, List.of(
					new HeaderEntry(0, 0, 2, 1, 10),
					new HeaderEntry(8, 0, 3, 1, 20)
			));
			MapRenderProfile profile = MapRenderProfile.CURRENT;
			String profileHash = profile.contractHash();
			MapTileStore store = new MapTileStore(storeRoot);
			MapChunkExistenceIndex firstIndex = MapChunkExistenceIndex.scan(region);
			MapRenderInventory.Snapshot first = MapRenderInventory.inspect(firstIndex, store, OVERWORLD, profile, profileHash);
			require(first.tiles().size() == 2 && first.renderNeededCount() == 2, "new explored tiles must start as render-needed");

			MapTileSourceState origin = first.tiles().stream()
					.filter(status -> status.key().equals(new MapTileKey(OVERWORLD, 0, 0, MapRenderProfile.CURRENT.version())))
					.map(MapRenderInventory.TileStatus::source)
					.findFirst()
					.orElseThrow();
			store.commitBaseTile(origin.key(), profileHash, profile, origin, new byte[]{4, 5, 6}, 1L, "worker");
			MapRenderInventory.Snapshot second = MapRenderInventory.inspect(firstIndex, store, OVERWORLD, profile, profileHash);
			require(second.currentCount() == 1 && second.renderNeededCount() == 1, "committing one source fingerprint must make exactly one tile current; statuses=" + second.tiles());

			writeRegion(regionFile, List.of(
					new HeaderEntry(0, 0, 2, 1, 11),
					new HeaderEntry(8, 0, 3, 1, 20)
			));
			MapRenderInventory.Snapshot third = MapRenderInventory.inspect(MapChunkExistenceIndex.scan(region), store, OVERWORLD, profile, profileHash);
			require(third.renderNeededCount() == 2, "changed header stamp must make the previously committed tile stale again");
			MapRenderInventory.TileStatus changed = third.tiles().stream().filter(status -> status.key().tileX() == 0).findFirst().orElseThrow();
			require("source-changed".equals(changed.staleReason()), "stale reason must identify source fingerprint change");
		} finally {
			deleteTree(root);
		}
	}

	private static void mapRenderPackageCannotLoadGameplayChunks() throws Exception {
		Path sourceRoot = Path.of("").toAbsolutePath().resolve("src/main/java/com/lostglade/server/maprender");
		List<String> forbidden = List.of(
				".getChunk(",
				"addRegionTicket(",
				"addTicket(",
				"TicketType",
				"ChunkHolder",
				"DistanceManager",
				"setChunkForced(",
				"RendererBotCameraSystem"
		);
		try (var paths = Files.walk(sourceRoot)) {
			for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
				String source = Files.readString(path);
				for (String token : forbidden) {
					require(!source.contains(token), "maprender source must not contain forbidden gameplay-loading/camera coupling token '" + token + "' in " + path.getFileName());
				}
			}
		}
	}

	private static void realWorldReadOnlySmoke(Path regionDirectory) throws Exception {
		MapChunkExistenceIndex index = MapChunkExistenceIndex.scan(regionDirectory);
		Map<MapTileKey, MapTileSourceState> tiles = index.baseTiles(OVERWORLD, MapRenderProfile.CURRENT);
		require(index.chunkCount() > 0, "real-world smoke region must contain at least one existing chunk");
		require(!tiles.isEmpty(), "real-world smoke region must produce at least one base tile");
		System.out.println("Yandex map read-only smoke: " + index.chunkCount() + " existing chunks, " + tiles.size() + " base tiles");
	}

	private static MapTileSourceState onlyTile(MapChunkExistenceIndex index) {
		Map<MapTileKey, MapTileSourceState> tiles = index.baseTiles(OVERWORLD, MapRenderProfile.CURRENT);
		require(tiles.size() == 1, "fixture must produce exactly one base tile");
		return tiles.values().iterator().next();
	}

	private static void writeRegion(Path file, List<HeaderEntry> entries) throws IOException {
		ByteBuffer header = ByteBuffer.allocate(8192).order(ByteOrder.BIG_ENDIAN);
		for (HeaderEntry entry : entries) {
			int slot = entry.localZ() * 32 + entry.localX();
			int location = (entry.sectorOffset() << 8) | (entry.sectorCount() & 0xFF);
			header.putInt(slot * Integer.BYTES, location);
			header.putInt(4096 + slot * Integer.BYTES, entry.timestamp());
		}
		Files.write(file, header.array(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
	}

	private static void deleteTree(Path root) throws IOException {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (var paths = Files.walk(root)) {
			List<Path> sorted = new ArrayList<>(paths.toList());
			sorted.sort((left, right) -> Integer.compare(right.getNameCount(), left.getNameCount()));
			for (Path path : sorted) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private record HeaderEntry(int localX, int localZ, int sectorOffset, int sectorCount, int timestamp) {
	}
}
