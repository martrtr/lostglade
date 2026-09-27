package com.lostglade.server.maprender;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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

public final class YandexMapRenderPhaseThreeTest {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");

	private YandexMapRenderPhaseThreeTest() {
	}

	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		entityRegionStampInvalidatesSourceFingerprint();
		strictManifestIncludesOnlyExistingSourceAndHalo();
		itemDisplayFilterRejectsEveryOtherEntityType();
		snapshotCodeCannotReachGameplayChunkLoading();
		savedLightAndBlockEntitiesStayInVanillaPacketPath();
		System.out.println("Yandex map renderer Phase 3 checks passed");
	}

	private static void entityRegionStampInvalidatesSourceFingerprint() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-phase3-source-");
		try {
			Path terrain = Files.createDirectories(root.resolve("region"));
			Path entities = Files.createDirectories(root.resolve("entities"));
			writeRegion(terrain.resolve("r.0.0.mca"), List.of(new HeaderEntry(0, 0, 2, 1, 100)));
			Path entityRegion = entities.resolve("r.0.0.mca");
			writeRegion(entityRegion, List.of(new HeaderEntry(0, 0, 2, 1, 200)));
			MapChunkExistenceIndex terrainIndex = MapChunkExistenceIndex.scan(terrain);
			MapTileSourceState before = onlyTile(terrainIndex, MapChunkExistenceIndex.scan(entities));
			writeRegion(entityRegion, List.of(new HeaderEntry(0, 0, 2, 1, 201)));
			MapTileSourceState after = onlyTile(terrainIndex, MapChunkExistenceIndex.scan(entities));
			require(!before.sourceFingerprint().equals(after.sourceFingerprint()), "saved ItemDisplay/entity-region changes must invalidate the tile source fingerprint");
			require(before.existingChunkMask() == after.existingChunkMask(), "entity-only changes must not alter terrain existence mask");
		} finally {
			deleteTree(root);
		}
	}

	private static void strictManifestIncludesOnlyExistingSourceAndHalo() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-phase3-manifest-");
		try {
			Path terrain = Files.createDirectories(root.resolve("region"));
			Path entities = Files.createDirectories(root.resolve("entities"));
			writeRegion(terrain.resolve("r.0.0.mca"), List.of(
					new HeaderEntry(0, 0, 2, 1, 10),
					new HeaderEntry(1, 0, 3, 1, 12),
					new HeaderEntry(2, 0, 4, 1, 13)
			));
			writeRegion(terrain.resolve("r.-1.0.mca"), List.of(new HeaderEntry(31, 0, 2, 1, 14)));
			writeRegion(entities.resolve("r.0.0.mca"), List.of(new HeaderEntry(0, 0, 2, 1, 50)));
			MapChunkExistenceIndex terrainIndex = MapChunkExistenceIndex.scan(terrain);
			MapChunkExistenceIndex entityIndex = MapChunkExistenceIndex.scan(entities);
			MapTileKey key = new MapTileKey(OVERWORLD, 0, 0, MapRenderProfile.CURRENT.version());
			MapSnapshotManifest manifest = MapSnapshotManifest.create(terrainIndex, entityIndex, key, MapRenderProfile.CURRENT);
			require(manifest.sourceChunks().size() == 1, "detail manifest must contain exactly one source chunk");
			require(manifest.chunks().size() == 3, "manifest must add only existing one-ring halo chunks");
			require(manifest.chunks().stream().anyMatch(entry -> entry.pos().x == -1 && entry.role() == MapSnapshotManifest.Role.HALO), "west existing halo must be included");
			require(manifest.chunks().stream().anyMatch(entry -> entry.pos().x == 1 && entry.role() == MapSnapshotManifest.Role.HALO), "east existing halo must be included");
			require(manifest.chunks().stream().noneMatch(entry -> entry.pos().x == 2), "chunks beyond the one-ring halo must never enter the snapshot");
			String before = manifest.snapshotFingerprint();
			writeRegion(terrain.resolve("r.-1.0.mca"), List.of(new HeaderEntry(31, 0, 2, 1, 15)));
			String afterHaloSave = MapSnapshotManifest.create(MapChunkExistenceIndex.scan(terrain), entityIndex, key, MapRenderProfile.CURRENT).snapshotFingerprint();
			require(before.equals(afterHaloSave), "halo-only storage churn must not invalidate the owning world tile");

			writeRegion(terrain.resolve("r.0.0.mca"), List.of(
					new HeaderEntry(0, 0, 2, 1, 16),
					new HeaderEntry(1, 0, 3, 1, 12),
					new HeaderEntry(2, 0, 4, 1, 13)
			));
			String afterSourceSave = MapSnapshotManifest.create(MapChunkExistenceIndex.scan(terrain), entityIndex, key, MapRenderProfile.CURRENT).snapshotFingerprint();
			require(!before.equals(afterSourceSave), "source storage change must invalidate the tile fingerprint");
		} finally {
			deleteTree(root);
		}
	}

	private static void itemDisplayFilterRejectsEveryOtherEntityType() {
		CompoundTag entityChunk = new CompoundTag();
		ListTag entities = new ListTag();
		entities.add(zombieWithNestedItemDisplay());
		entities.add(itemDisplayWithOrdinaryPassenger());
		CompoundTag sheep = new CompoundTag();
		sheep.putString("id", "minecraft:sheep");
		entities.add(sheep);
		entityChunk.put("Entities", entities);

		List<CompoundTag> filtered = MapSnapshotRepository.filterItemDisplays(entityChunk);
		require(filtered.size() == 2, "only the two saved ItemDisplay entities, including nested displays, may survive filtering");
		for (CompoundTag tag : filtered) {
			require("minecraft:item_display".equals(tag.getStringOr("id", "")), "ordinary entity leaked into map snapshot");
			require(!tag.contains("Passengers"), "filtered ItemDisplay must not recursively materialize ordinary passengers");
		}
	}

	private static CompoundTag zombieWithNestedItemDisplay() {
		CompoundTag zombie = new CompoundTag();
		zombie.putString("id", "minecraft:zombie");
		ListTag passengers = new ListTag();
		CompoundTag display = new CompoundTag();
		display.putString("id", "minecraft:item_display");
		passengers.add(display);
		zombie.put("Passengers", passengers);
		return zombie;
	}

	private static CompoundTag itemDisplayWithOrdinaryPassenger() {
		CompoundTag display = new CompoundTag();
		display.putString("id", "minecraft:item_display");
		ListTag passengers = new ListTag();
		CompoundTag pig = new CompoundTag();
		pig.putString("id", "minecraft:pig");
		passengers.add(pig);
		display.put("Passengers", passengers);
		return display;
	}

	private static void snapshotCodeCannotReachGameplayChunkLoading() throws Exception {
		Path sourceRoot = Path.of("").toAbsolutePath().resolve("src/main/java/com/lostglade/server/maprender");
		List<String> forbidden = List.of(
				".getChunk(",
				"getChunkSource(",
				"addRegionTicket(",
				"addTicket(",
				"setChunkForced(",
				"ChunkHolder",
				"DistanceManager"
		);
		try (var paths = Files.walk(sourceRoot)) {
			for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
				String source = Files.readString(path);
				for (String token : forbidden) {
					require(!source.contains(token), "map snapshot/render source must not contain gameplay chunk-loading token '" + token + "' in " + path.getFileName());
				}
			}
		}
	}

	private static void savedLightAndBlockEntitiesStayInVanillaPacketPath() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String snapshot = Files.readString(project.resolve("src/main/java/com/lostglade/server/maprender/MapChunkSnapshot.java"));
		String packets = Files.readString(project.resolve("src/main/java/com/lostglade/server/maprender/MapSnapshotPacketBuilder.java"));
		require(snapshot.contains("section.blockLight().copy()") && snapshot.contains("section.skyLight().copy()"), "snapshot must copy saved BLOCK and SKY DataLayers");
		require(snapshot.contains("BlockEntity.loadStatic") && snapshot.contains("chunk.setBlockEntity(blockEntity)"), "saved block entities must materialize into the detached vanilla LevelChunk");
		require(packets.contains("new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null)"), "worker payload must use vanilla LevelChunkWithLight packet generation");
		require(packets.contains("lightEngine.queueSectionData(LightLayer.BLOCK") && packets.contains("lightEngine.queueSectionData(LightLayer.SKY"), "packet builder must inject saved light arrays instead of canonical/daylight replacement");
		require(!packets.contains("lightEngine.runLightUpdates()"), "packet builder must preserve exact saved light arrays instead of propagating them in the detached snapshot");
		require(packets.contains("public BlockGetter getLevel()") && packets.contains("return this;"), "detached light engine must not expose ServerLevel as its BlockGetter");
		require(packets.contains("EntityType.ITEM_DISPLAY") && packets.contains("instanceof Display.ItemDisplay"), "saved entity packet path must be ItemDisplay-only");
	}

	private static MapTileSourceState onlyTile(MapChunkExistenceIndex terrain, MapChunkExistenceIndex entities) {
		Map<MapTileKey, MapTileSourceState> tiles = terrain.baseTiles(OVERWORLD, MapRenderProfile.CURRENT, entities);
		require(tiles.size() == 1, "fixture must produce one tile");
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
