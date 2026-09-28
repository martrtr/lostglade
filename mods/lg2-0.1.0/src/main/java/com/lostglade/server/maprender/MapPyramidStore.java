package com.lostglade.server.maprender;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Rebuildable L1+ cache. L0 remains authoritative in {@link MapTileStore}. */
public final class MapPyramidStore {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	private static final String CURRENT_FILE = "current.json";

	private final Path root;

	public MapPyramidStore(Path root) {
		this.root = Objects.requireNonNull(root, "root");
	}

	public Optional<MapPyramidMetadata> readMetadata(MapPyramidTileKey key, String profileHash) throws IOException {
		GenerationFiles files = currentGenerationFiles(key, profileHash);
		if (files == null || !Files.isRegularFile(files.metadataPath()) || !Files.isRegularFile(files.imagePath())) return Optional.empty();
		try (var reader = Files.newBufferedReader(files.metadataPath(), StandardCharsets.UTF_8)) {
			return Optional.ofNullable(GSON.fromJson(reader, MapPyramidMetadata.class));
		} catch (RuntimeException exception) {
			throw new IOException("Failed to parse Yandex map pyramid metadata for " + key, exception);
		}
	}

	public Optional<StoredTile> read(MapPyramidTileKey key, String profileHash) throws IOException {
		GenerationFiles files = currentGenerationFiles(key, profileHash);
		if (files == null || !Files.isRegularFile(files.metadataPath()) || !Files.isRegularFile(files.imagePath())) return Optional.empty();
		MapPyramidMetadata metadata;
		try (var reader = Files.newBufferedReader(files.metadataPath(), StandardCharsets.UTF_8)) {
			metadata = GSON.fromJson(reader, MapPyramidMetadata.class);
		}
		if (metadata == null) return Optional.empty();
		byte[] imageBytes = Files.readAllBytes(files.imagePath());
		if (!MapTileStore.checksum(imageBytes).equals(metadata.resultChecksum())) {
			throw new IOException("Committed Yandex map pyramid checksum mismatch for " + key);
		}
		return Optional.of(new StoredTile(metadata, imageBytes));
	}

	public MapPyramidMetadata commit(
			MapPyramidTileKey key,
			String profileHash,
			String childSignature,
			int pixelSize,
			byte[] pngBytes
	) throws IOException {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(profileHash, "profileHash");
		Objects.requireNonNull(childSignature, "childSignature");
		Objects.requireNonNull(pngBytes, "pngBytes");
		if (pixelSize <= 0 || pngBytes.length == 0) throw new IllegalArgumentException("invalid pyramid image");

		String generation = UUID.randomUUID().toString();
		Path tileDirectory = tileDirectory(key, profileHash);
		Files.createDirectories(tileDirectory);
		GenerationFiles files = generationFiles(tileDirectory, generation);
		MapPyramidMetadata metadata = new MapPyramidMetadata(
				profileHash, key.level(), key.tileX(), key.tileZ(), childSignature, System.currentTimeMillis(),
				pixelSize, pixelSize, MapTileStore.checksum(pngBytes)
		);
		writeForced(files.imagePath(), pngBytes);
		writeForced(files.metadataPath(), GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));
		Path temporaryPointer = tileDirectory.resolve(CURRENT_FILE + ".tmp-" + generation);
		writeForced(temporaryPointer, GSON.toJson(new CurrentPointer(generation)).getBytes(StandardCharsets.UTF_8));
		atomicReplace(temporaryPointer, tileDirectory.resolve(CURRENT_FILE));
		cleanupOldGenerations(tileDirectory, generation);
		return metadata;
	}

	public void invalidate(MapPyramidTileKey key, String profileHash) throws IOException {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(profileHash, "profileHash");
		Files.deleteIfExists(tileDirectory(key, profileHash).resolve(CURRENT_FILE));
	}

	private GenerationFiles currentGenerationFiles(MapPyramidTileKey key, String profileHash) throws IOException {
		Path tileDirectory = tileDirectory(key, profileHash);
		Path pointerPath = tileDirectory.resolve(CURRENT_FILE);
		if (!Files.isRegularFile(pointerPath)) return null;
		CurrentPointer pointer;
		try (var reader = Files.newBufferedReader(pointerPath, StandardCharsets.UTF_8)) {
			pointer = GSON.fromJson(reader, CurrentPointer.class);
		}
		if (pointer == null || pointer.generation() == null || pointer.generation().isBlank()) return null;
		return generationFiles(tileDirectory, pointer.generation());
	}

	private Path tileDirectory(MapPyramidTileKey key, String profileHash) {
		return this.root
				.resolve(sanitizePathPart(key.dimensionId().toString()))
				.resolve("render-v" + key.renderProfileVersion())
				.resolve("profile-" + sanitizePathPart(profileHash))
				.resolve("pyramid")
				.resolve("l" + key.level())
				.resolve(Long.toString(key.tileX()))
				.resolve(Long.toString(key.tileZ()));
	}

	private static GenerationFiles generationFiles(Path tileDirectory, String generation) {
		return new GenerationFiles(tileDirectory.resolve("g-" + generation + ".png"), tileDirectory.resolve("g-" + generation + ".meta.json"));
	}

	private static void writeForced(Path path, byte[] bytes) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) channel.write(buffer);
			channel.force(true);
		}
	}

	private static void atomicReplace(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void cleanupOldGenerations(Path tileDirectory, String currentGeneration) {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(tileDirectory, "g-*")) {
			String currentPrefix = "g-" + currentGeneration + ".";
			for (Path path : stream) if (!path.getFileName().toString().startsWith(currentPrefix)) Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}

	private static String sanitizePathPart(String value) {
		return value == null || value.isBlank() ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]+", "_");
	}

	private record CurrentPointer(String generation) {
	}

	private record GenerationFiles(Path imagePath, Path metadataPath) {
	}

	public record StoredTile(MapPyramidMetadata metadata, byte[] imageBytes) {
		public StoredTile { imageBytes = imageBytes.clone(); }
		@Override public byte[] imageBytes() { return this.imageBytes.clone(); }
	}
}
