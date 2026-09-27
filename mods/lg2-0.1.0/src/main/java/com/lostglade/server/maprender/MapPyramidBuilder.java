package com.lostglade.server.maprender;

import com.lostglade.Lg2;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** CPU-only power-of-two zoom pyramid. No world, client or GPU APIs belong here. */
public final class MapPyramidBuilder {
	public static final int MAX_LEVEL = 8;
	private static final String STORE_DIRECTORY = "lostglade/yandex_maps/v2";
	private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
	private static final AtomicLong REVISION = new AtomicLong();
	private static volatile ExecutorService executor;

	private MapPyramidBuilder() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
	}

	public static long revision() {
		return REVISION.get();
	}

	/** Rebuild only the ancestor chain touched by a newly committed L0 tile. */
	public static void onBaseTileCommitted(MinecraftServer server, MapTileKey baseKey, String profileHash) {
		if (server == null || baseKey == null || profileHash == null || profileHash.isBlank()) return;
		Path storeRoot = server.getWorldPath(LevelResource.ROOT).resolve(STORE_DIRECTORY);
		String pendingKey = profileHash + ":v" + baseKey.renderProfileVersion() + ":base:" + baseKey.dimensionId() + ":" + baseKey.tileX() + ":" + baseKey.tileZ();
		if (!PENDING.add(pendingKey)) return;
		ensureExecutor().execute(() -> {
			try {
				rebuildAncestors(storeRoot, profileHash, MapRenderProfile.CURRENT, baseKey);
			} catch (Throwable throwable) {
				Lg2.LOGGER.warn("Failed to rebuild Yandex map pyramid ancestors for {}", baseKey, throwable);
			} finally {
				PENDING.remove(pendingKey);
			}
		});
	}

	/**
	 * Cache-miss repair used by the monitor compositor. It only reads committed
	 * image files and queues CPU work; the caller never waits for the result.
	 */
	public static void request(MinecraftServer server, MapPyramidTileKey key, String profileHash) {
		if (server == null || key == null || profileHash == null || profileHash.isBlank()) return;
		Path storeRoot = server.getWorldPath(LevelResource.ROOT).resolve(STORE_DIRECTORY);
		String pendingKey = profileHash + ":v" + key.renderProfileVersion() + ":lod:" + key.dimensionId() + ":" + key.level() + ":" + key.tileX() + ":" + key.tileZ();
		if (!PENDING.add(pendingKey)) return;
		ensureExecutor().execute(() -> {
			try {
				ensureTile(storeRoot, profileHash, MapRenderProfile.CURRENT, key);
			} catch (Throwable throwable) {
				Lg2.LOGGER.debug("Yandex map pyramid cache-miss rebuild failed for {}: {}", key, throwable.toString());
			} finally {
				PENDING.remove(pendingKey);
			}
		});
	}

	static void rebuildAncestors(Path storeRoot, String profileHash, MapRenderProfile profile, MapTileKey baseKey) throws IOException {
		requireProfileVersion(baseKey.renderProfileVersion(), profile);
		for (int level = 1; level <= MAX_LEVEL; level++) {
			MapPyramidTileKey parent = MapPyramidTileKey.parentOf(baseKey, level);
			rebuildTile(storeRoot, profileHash, profile, parent);
		}
	}

	static boolean ensureTile(Path storeRoot, String profileHash, MapRenderProfile profile, MapPyramidTileKey key) throws IOException {
		requireProfileVersion(key.renderProfileVersion(), profile);
		MapPyramidStore store = new MapPyramidStore(storeRoot);
		if (store.read(key, profileHash).isPresent()) return true;
		if (key.level() > 1) {
			for (int dz = 0; dz < 2; dz++) {
				for (int dx = 0; dx < 2; dx++) {
					MapPyramidTileKey child = new MapPyramidTileKey(
							key.dimensionId(), key.level() - 1, key.tileX() * 2L + dx, key.tileZ() * 2L + dz, key.renderProfileVersion());
					ensureTile(storeRoot, profileHash, profile, child);
				}
			}
		}
		return rebuildTile(storeRoot, profileHash, profile, key);
	}

	static boolean rebuildTile(Path storeRoot, String profileHash, MapRenderProfile profile, MapPyramidTileKey key) throws IOException {
		if (key.level() < 1) throw new IllegalArgumentException("pyramid level must be >= 1");
		requireProfileVersion(key.renderProfileVersion(), profile);
		MapTileStore baseStore = new MapTileStore(storeRoot);
		MapPyramidStore pyramidStore = new MapPyramidStore(storeRoot);
		List<ChildTile> children = new ArrayList<>(4);
		boolean anyPresent = false;

		for (int dz = 0; dz < 2; dz++) {
			for (int dx = 0; dx < 2; dx++) {
				long childX = key.tileX() * 2L + dx;
				long childZ = key.tileZ() * 2L + dz;
				ChildTile child;
				if (key.level() == 1) {
					MapTileKey childKey = new MapTileKey(key.dimensionId(), childX, childZ, key.renderProfileVersion());
					var stored = baseStore.read(childKey, profileHash);
					child = stored.isPresent()
							? decodeChild(stored.get().imageBytes(), stored.get().metadata().resultChecksum())
							: ChildTile.missing();
				} else {
					MapPyramidTileKey childKey = new MapPyramidTileKey(key.dimensionId(), key.level() - 1, childX, childZ, key.renderProfileVersion());
					var stored = pyramidStore.read(childKey, profileHash);
					child = stored.isPresent()
							? decodeChild(stored.get().imageBytes(), stored.get().metadata().resultChecksum())
							: ChildTile.missing();
				}
				children.add(child);
				anyPresent |= child.image() != null;
			}
		}

		if (!anyPresent) {
			pyramidStore.invalidate(key, profileHash);
			REVISION.incrementAndGet();
			return false;
		}

		String childSignature = childSignature(children);
		var current = pyramidStore.readMetadata(key, profileHash);
		if (current.isPresent() && childSignature.equals(current.get().childSignature())) return true;

		BufferedImage downsampled = new BufferedImage(profile.tilePixels(), profile.tilePixels(), BufferedImage.TYPE_INT_ARGB);
		int tilePixels = profile.tilePixels();
		for (int y = 0; y < tilePixels; y++) {
			for (int x = 0; x < tilePixels; x++) {
				int red = 0;
				int green = 0;
				int blue = 0;
				int count = 0;
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						int combinedX = x * 2 + dx;
						int combinedY = y * 2 + dy;
						int childX = combinedX >= tilePixels ? 1 : 0;
						int childY = combinedY >= tilePixels ? 1 : 0;
						BufferedImage child = children.get(childY * 2 + childX).image();
						if (child == null) continue;
						int rgb = child.getRGB(combinedX % tilePixels, combinedY % tilePixels);
						red += (rgb >>> 16) & 0xFF;
						green += (rgb >>> 8) & 0xFF;
						blue += rgb & 0xFF;
						count++;
					}
				}
				if (count > 0) {
					downsampled.setRGB(x, y, 0xFF000000
							| ((red / count) << 16)
							| ((green / count) << 8)
							| (blue / count));
				}
			}
		}

		byte[] pngBytes = encodePng(downsampled);
		pyramidStore.commit(key, profileHash, childSignature, profile.tilePixels(), pngBytes);
		REVISION.incrementAndGet();
		return true;
	}

	private static void requireProfileVersion(int keyVersion, MapRenderProfile profile) {
		if (profile == null || keyVersion != profile.version()) {
			throw new IllegalArgumentException("pyramid key render-profile version does not match render profile");
		}
	}

	private static ChildTile decodeChild(byte[] pngBytes, String checksum) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
		if (image == null) throw new IOException("Pyramid child is not a decodable PNG");
		return new ChildTile(image, checksum == null ? MapTileStore.checksum(pngBytes) : checksum);
	}

	private static byte[] encodePng(BufferedImage image) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "PNG", output)) throw new IOException("PNG writer is unavailable");
		return output.toByteArray();
	}

	private static String childSignature(List<ChildTile> children) {
		StringBuilder value = new StringBuilder();
		for (int index = 0; index < children.size(); index++) {
			if (index > 0) value.append('\n');
			value.append(index).append('=').append(children.get(index).checksum());
		}
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private static synchronized ExecutorService ensureExecutor() {
		if (executor == null || executor.isShutdown()) {
			executor = Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "lg2-yandex-map-pyramid");
				thread.setDaemon(true);
				return thread;
			});
		}
		return executor;
	}

	private static synchronized void stop() {
		PENDING.clear();
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	private record ChildTile(BufferedImage image, String checksum) {
		private static ChildTile missing() { return new ChildTile(null, "-"); }
	}
}
