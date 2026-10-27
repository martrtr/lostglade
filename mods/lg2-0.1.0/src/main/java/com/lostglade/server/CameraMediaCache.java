package com.lostglade.server;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CameraMediaCache {
	private static final String ROOT_PROPERTY = "lg2.cameraMediaCacheRoot";
	private static volatile Path cacheRoot = defaultRoot();

	private CameraMediaCache() {
	}

	public static void setCacheRoot(Path root) {
		if (root != null) {
			cacheRoot = root;
		}
	}

	public static void initialize(Path gameDir) {
		String override = System.getProperty(ROOT_PROPERTY, "").trim();
		if (!override.isBlank()) {
			cacheRoot = Path.of(override).toAbsolutePath().normalize();
			return;
		}
		if (gameDir != null) {
			cacheRoot = gameDir.resolve("cache").resolve("lg2-camera").toAbsolutePath().normalize();
		}
	}

	public static Path cacheRoot() {
		return cacheRoot;
	}

	public static Path photoSourcePath(String key) {
		return cacheRoot.resolve("photos").resolve(normalizedKey(key) + ".png");
	}

	public static Path videoSourcePath(String key) {
		return cacheRoot.resolve("videos").resolve(normalizedKey(key) + ".mp4");
	}

	public static Path tempVideoSourcePath(String key) {
		return cacheRoot.resolve("videos").resolve(normalizedKey(key) + ".tmp.mp4");
	}

	/**
	 * The server-side description of an unfinished hand-camera video.  This is
	 * intentionally kept beside the media cache: the print keeps the public
	 * identity, while this file lets a graceful server restart reconnect it to
	 * the renderer that still owns the frozen shadow timeline.
	 */
	public static Path videoRecoveryPath() {
		return cacheRoot.resolve("video-recovery.json");
	}

	public static BufferedImage loadPhotoSource(String key) throws IOException {
		Path path = photoSourcePath(key);
		if (!Files.isRegularFile(path)) {
			throw new IOException("Cached photo source is missing");
		}
		BufferedImage image = ImageIO.read(path.toFile());
		if (image == null) {
			throw new IOException("Cached photo source is unreadable");
		}
		return image;
	}

	public static void ensurePhotoParent(String key) throws IOException {
		Files.createDirectories(photoSourcePath(key).getParent());
	}

	public static void ensureVideoParent(String key) throws IOException {
		Files.createDirectories(videoSourcePath(key).getParent());
	}

	private static Path defaultRoot() {
		String override = System.getProperty(ROOT_PROPERTY, "").trim();
		if (!override.isBlank()) {
			return Path.of(override);
		}
		return Path.of("cache", "lg2-camera");
	}

	private static String normalizedKey(String key) {
		if (key == null || key.isBlank()) {
			return "missing";
		}
		return key.replaceAll("[^a-zA-Z0-9._-]", "_");
	}
}
