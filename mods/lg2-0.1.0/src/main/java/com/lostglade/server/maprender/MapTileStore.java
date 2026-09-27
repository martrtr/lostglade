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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MapTileStore {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	private static final String CURRENT_FILE = "current.json";

	private final Path root;

	public MapTileStore(Path root) {
		this.root = Objects.requireNonNull(root, "root");
	}

	public Optional<MapTileMetadata> readMetadata(MapTileKey key, String profileHash) throws IOException {
		GenerationFiles files = currentGenerationFiles(key, profileHash);
		if (files == null || !Files.isRegularFile(files.metadataPath()) || !Files.isRegularFile(files.imagePath())) {
			return Optional.empty();
		}
		try (var reader = Files.newBufferedReader(files.metadataPath(), StandardCharsets.UTF_8)) {
			return Optional.ofNullable(GSON.fromJson(reader, MapTileMetadata.class));
		} catch (RuntimeException exception) {
			throw new IOException("Failed to parse committed map tile metadata for " + key, exception);
		}
	}

	public Optional<StoredTile> read(MapTileKey key, String profileHash) throws IOException {
		GenerationFiles files = currentGenerationFiles(key, profileHash);
		if (files == null || !Files.isRegularFile(files.metadataPath()) || !Files.isRegularFile(files.imagePath())) {
			return Optional.empty();
		}
		MapTileMetadata metadata;
		try (var reader = Files.newBufferedReader(files.metadataPath(), StandardCharsets.UTF_8)) {
			metadata = GSON.fromJson(reader, MapTileMetadata.class);
		}
		if (metadata == null) {
			return Optional.empty();
		}
		byte[] imageBytes = Files.readAllBytes(files.imagePath());
		if (!checksum(imageBytes).equals(metadata.resultChecksum())) {
			throw new IOException("Committed map tile checksum mismatch for " + key);
		}
		return Optional.of(new StoredTile(metadata, imageBytes));
	}

	public MapTileMetadata commitBaseTile(
			MapTileKey key,
			String profileHash,
			MapRenderProfile profile,
			MapTileSourceState source,
			byte[] pngBytes,
			long renderedRevision,
			String workerId
	) throws IOException {
		return commitBaseTile(key, profileHash, profile, source, null, pngBytes, renderedRevision, workerId);
	}

	public MapTileMetadata commitBaseTile(
			MapTileKey key,
			String profileHash,
			MapRenderProfile profile,
			MapTileSourceState source,
			String visualFingerprint,
			byte[] pngBytes,
			long renderedRevision,
			String workerId
	) throws IOException {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(profileHash, "profileHash");
		Objects.requireNonNull(profile, "profile");
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(pngBytes, "pngBytes");
		if (!key.equals(source.key())) {
			throw new IllegalArgumentException("source belongs to a different tile");
		}
		if (key.renderProfileVersion() != profile.version()) {
			throw new IllegalArgumentException("tile render-profile version does not match render profile");
		}
		if (pngBytes.length == 0) {
			throw new IllegalArgumentException("pngBytes must not be empty");
		}

		String generation = UUID.randomUUID().toString();
		Path tileDirectory = tileDirectory(key, profileHash);
		Files.createDirectories(tileDirectory);
		GenerationFiles generationFiles = generationFiles(tileDirectory, generation);
		MapTileMetadata metadata = new MapTileMetadata(
				profileHash,
				source.sourceFingerprint(),
				visualFingerprint,
				System.currentTimeMillis(),
				renderedRevision,
				profile.tilePixels(),
				profile.tilePixels(),
				source.existingChunkMask(),
				checksum(pngBytes),
				workerId
		);

		writeForced(generationFiles.imagePath(), pngBytes);
		writeForced(generationFiles.metadataPath(), GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));
		Path temporaryPointer = tileDirectory.resolve(CURRENT_FILE + ".tmp-" + generation);
		writeForced(temporaryPointer, GSON.toJson(new CurrentPointer(generation)).getBytes(StandardCharsets.UTF_8));
		atomicReplace(temporaryPointer, tileDirectory.resolve(CURRENT_FILE));
		cleanupOldGenerations(tileDirectory, generation);
		return metadata;
	}

	public MapTileMetadata refreshSourceFingerprint(
			MapTileKey key,
			String profileHash,
			MapTileSourceState source,
			String visualFingerprint
	) throws IOException {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(profileHash, "profileHash");
		Objects.requireNonNull(source, "source");
		if (!key.equals(source.key())) throw new IllegalArgumentException("source belongs to a different tile");
		GenerationFiles files = currentGenerationFiles(key, profileHash);
		if (files == null || !Files.isRegularFile(files.metadataPath()) || !Files.isRegularFile(files.imagePath())) {
			throw new IOException("Cannot refresh metadata for missing committed map tile " + key);
		}
		MapTileMetadata previous;
		try (var reader = Files.newBufferedReader(files.metadataPath(), StandardCharsets.UTF_8)) {
			previous = GSON.fromJson(reader, MapTileMetadata.class);
		}
		if (previous == null || !profileHash.equals(previous.profileHash())) {
			throw new IOException("Cannot refresh invalid committed metadata for " + key);
		}
		MapTileMetadata updated = new MapTileMetadata(
				previous.profileHash(), source.sourceFingerprint(), visualFingerprint,
				previous.renderedAtEpochMs(), previous.renderedRevision(),
				previous.pixelWidth(), previous.pixelHeight(), source.existingChunkMask(),
				previous.resultChecksum(), previous.workerId()
		);
		Path temporaryMetadata = files.metadataPath().resolveSibling(files.metadataPath().getFileName() + ".tmp-" + UUID.randomUUID());
		writeForced(temporaryMetadata, GSON.toJson(updated).getBytes(StandardCharsets.UTF_8));
		atomicReplace(temporaryMetadata, files.metadataPath());
		return updated;
	}

	private GenerationFiles currentGenerationFiles(MapTileKey key, String profileHash) throws IOException {
		Path tileDirectory = tileDirectory(key, profileHash);
		Path pointerPath = tileDirectory.resolve(CURRENT_FILE);
		if (!Files.isRegularFile(pointerPath)) {
			return null;
		}
		CurrentPointer pointer;
		try (var reader = Files.newBufferedReader(pointerPath, StandardCharsets.UTF_8)) {
			pointer = GSON.fromJson(reader, CurrentPointer.class);
		}
		if (pointer == null || pointer.generation() == null || pointer.generation().isBlank()) {
			return null;
		}
		return generationFiles(tileDirectory, pointer.generation());
	}

	private Path tileDirectory(MapTileKey key, String profileHash) {
		String dimension = sanitizePathPart(key.dimensionId().toString());
		String profile = sanitizePathPart(profileHash);
		return this.root
				.resolve(dimension)
				.resolve("render-v" + key.renderProfileVersion())
				.resolve("profile-" + profile)
				.resolve("base")
				.resolve(Long.toString(key.tileX()))
				.resolve(Long.toString(key.tileZ()));
	}

	private static GenerationFiles generationFiles(Path tileDirectory, String generation) {
		return new GenerationFiles(
				tileDirectory.resolve("g-" + generation + ".png"),
				tileDirectory.resolve("g-" + generation + ".meta.json")
		);
	}

	private static void writeForced(Path path, byte[] bytes) throws IOException {
		try (FileChannel channel = FileChannel.open(
				path,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
		)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
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
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(tileDirectory, "g-*") ) {
			String currentPrefix = "g-" + currentGeneration + ".";
			for (Path path : stream) {
				if (!path.getFileName().toString().startsWith(currentPrefix)) {
					Files.deleteIfExists(path);
				}
			}
		} catch (IOException ignored) {
			// Cleanup is best-effort. The atomic current pointer already defines the committed generation.
		}
	}

	private static String sanitizePathPart(String value) {
		if (value == null || value.isBlank()) {
			return "unknown";
		}
		return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
	}

	public static String checksum(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private record CurrentPointer(String generation) {
	}

	private record GenerationFiles(Path imagePath, Path metadataPath) {
	}

	public record StoredTile(MapTileMetadata metadata, byte[] imageBytes) {
		public StoredTile {
			imageBytes = imageBytes.clone();
		}

		@Override
		public byte[] imageBytes() {
			return this.imageBytes.clone();
		}
	}
}
