package com.lostglade.server.map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lostglade.Lg2;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.DryFoliageColor;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

public final class TextureAssetManager {
	private static final Path PROJECT_ROOT = findProjectRoot();
	private static final Path RESOURCES_ASSETS = PROJECT_ROOT.resolve("mods/lg2-0.1.0/src/main/resources/assets");
	private static final Path POLYMER_SOURCE_ASSETS = PROJECT_ROOT.resolve("polymer/source_assets/assets");
	private static final TextureAssetManager INSTANCE = new TextureAssetManager();

	private final Map<String, JsonObject> jsonCache = new ConcurrentHashMap<>();
	private final Map<String, BufferedImage> imageCache = new ConcurrentHashMap<>();
	private final Path clientJarPath;
	private volatile boolean biomeColorMapsInitialized;

	private TextureAssetManager() {
		this.clientJarPath = findMinecraftClientJar();
	}

	public static TextureAssetManager get() {
		return INSTANCE;
	}

	public JsonObject loadBlockState(Identifier blockId) {
		return loadJson("assets/" + blockId.getNamespace() + "/blockstates/" + blockId.getPath() + ".json");
	}

	public JsonObject loadModel(Identifier modelId) {
		return loadJson("assets/" + modelId.getNamespace() + "/models/" + modelId.getPath() + ".json");
	}

	public JsonObject loadJsonAsset(String assetPath) {
		return loadJson(assetPath);
	}

	public BufferedImage loadTexture(Identifier textureId) {
		String key = "assets/" + textureId.getNamespace() + "/textures/" + textureId.getPath() + ".png";
		BufferedImage cached = this.imageCache.get(key);
		if (cached != null) {
			return cached;
		}
		BufferedImage loaded = loadTextureInternal(key);
		if (loaded != null) {
			this.imageCache.putIfAbsent(key, loaded);
		}
		return loaded;
	}

	public void initializeBiomeColorMaps() {
		if (this.biomeColorMapsInitialized) {
			return;
		}
		synchronized (this) {
			if (this.biomeColorMapsInitialized) {
				return;
			}
			initializeColorMap(Identifier.fromNamespaceAndPath("minecraft", "colormap/grass"), GrassColor::init);
			initializeColorMap(Identifier.fromNamespaceAndPath("minecraft", "colormap/foliage"), FoliageColor::init);
			initializeColorMap(Identifier.fromNamespaceAndPath("minecraft", "colormap/dry_foliage"), DryFoliageColor::init);
			this.biomeColorMapsInitialized = true;
		}
	}

	public Path clientJarPath() {
		return this.clientJarPath;
	}

	private JsonObject loadJson(String assetPath) {
		JsonObject cached = this.jsonCache.get(assetPath);
		if (cached != null) {
			return cached;
		}
		JsonObject loaded = loadJsonInternal(assetPath);
		if (loaded != null) {
			this.jsonCache.putIfAbsent(assetPath, loaded);
		}
		return loaded;
	}

	private JsonObject loadJsonInternal(String assetPath) {
		String text = readText(resolveProjectAsset(assetPath));
		if (text == null) {
			text = readText(resolvePolymerAsset(assetPath));
		}
		if (text == null) {
			text = readTextFromClientJar(assetPath);
		}
		if (text == null) {
			return null;
		}
		return JsonParser.parseString(text).getAsJsonObject();
	}

	private BufferedImage loadTextureInternal(String assetPath) {
		BufferedImage image = readImage(resolveProjectAsset(assetPath));
		if (image == null) {
			image = readImage(resolvePolymerAsset(assetPath));
		}
		if (image == null) {
			image = readImageFromClientJar(assetPath);
		}
		return image;
	}

	private void initializeColorMap(Identifier textureId, java.util.function.Consumer<int[]> initializer) {
		BufferedImage texture = loadTexture(textureId);
		if (texture == null) {
			Lg2.LOGGER.warn("Missing biome color map {}", textureId);
			return;
		}
		initializer.accept(toColorMapPixels(texture));
	}

	private static int[] toColorMapPixels(BufferedImage image) {
		int[] pixels = new int[256 * 256];
		for (int y = 0; y < 256; y++) {
			int sourceY = y * image.getHeight() / 256;
			for (int x = 0; x < 256; x++) {
				int sourceX = x * image.getWidth() / 256;
				pixels[(y << 8) | x] = image.getRGB(sourceX, sourceY) & 0xFFFFFF;
			}
		}
		return pixels;
	}

	private static Path resolveProjectAsset(String assetPath) {
		return RESOURCES_ASSETS.resolve(assetPath.substring("assets/".length()));
	}

	private static Path resolvePolymerAsset(String assetPath) {
		return POLYMER_SOURCE_ASSETS.resolve(assetPath.substring("assets/".length()));
	}

	private static Path findProjectRoot() {
		Path current = Path.of("").toAbsolutePath().normalize();
		while (current != null) {
			if (Files.isDirectory(current.resolve("mods/lg2-0.1.0/src/main/resources/assets"))
					&& Files.isDirectory(current.resolve("polymer/source_assets/assets"))) {
				return current;
			}
			current = current.getParent();
		}
		return Path.of("/home/mart/Desktop/lostglade");
	}

	private static String readText(Path path) {
		if (path == null || !Files.exists(path)) {
			return null;
		}
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to read asset {}", path, exception);
			return null;
		}
	}

	private static BufferedImage readImage(Path path) {
		if (path == null || !Files.exists(path)) {
			return null;
		}
		try {
			return ImageIO.read(path.toFile());
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to read image {}", path, exception);
			return null;
		}
	}

	private String readTextFromClientJar(String assetPath) {
		if (this.clientJarPath == null || !Files.exists(this.clientJarPath)) {
			return null;
		}
		try (ZipFile zipFile = new ZipFile(this.clientJarPath.toFile())) {
			ZipEntry entry = zipFile.getEntry(assetPath);
			if (entry == null) {
				return null;
			}
			try (InputStream stream = zipFile.getInputStream(entry)) {
				return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			}
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to read {} from {}", assetPath, this.clientJarPath, exception);
			return null;
		}
	}

	private BufferedImage readImageFromClientJar(String assetPath) {
		if (this.clientJarPath == null || !Files.exists(this.clientJarPath)) {
			return null;
		}
		try (ZipFile zipFile = new ZipFile(this.clientJarPath.toFile())) {
			ZipEntry entry = zipFile.getEntry(assetPath);
			if (entry == null) {
				return null;
			}
			try (InputStream stream = zipFile.getInputStream(entry)) {
				return ImageIO.read(stream);
			}
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to read {} from {}", assetPath, this.clientJarPath, exception);
			return null;
		}
	}

	private static Path findMinecraftClientJar() {
		Path loomCache = Path.of(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom");
		if (!Files.isDirectory(loomCache)) {
			return null;
		}
		try (Stream<Path> stream = Files.walk(loomCache, 4)) {
			return stream
					.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals("minecraft-client.jar"))
					.findFirst()
					.orElse(null);
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to locate minecraft-client.jar in {}", loomCache, exception);
			return null;
		}
	}
}
