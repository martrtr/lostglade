package com.lostglade.server.maprender;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class YandexMapRenderPhaseSixTest {
	private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");

	private YandexMapRenderPhaseSixTest() {
	}

	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		pyramidParentMathUsesFloorDivision();
		fourBaseTilesDeriveOneL1Tile();
		changingOneBaseTileRebuildsOnlyDerivedContent();
		pyramidUsesLegacyArithmeticRgbAverage();
		pyramidPreservesTransparency();
		pyramidVersionsUsePhysicalCacheNamespaces();
		pyramidIsCpuOnlyAndProtocolHasNoZoomRenderJobs();
		System.out.println("Yandex map renderer Phase 6 checks passed");
	}

	private static void pyramidParentMathUsesFloorDivision() {
		MapTileKey minusOne = new MapTileKey(OVERWORLD, -1, -1, MapRenderProfile.CURRENT.version());
		MapTileKey minusTwo = new MapTileKey(OVERWORLD, -2, -2, MapRenderProfile.CURRENT.version());
		MapTileKey minusThree = new MapTileKey(OVERWORLD, -3, -3, MapRenderProfile.CURRENT.version());
		require(MapPyramidTileKey.parentOf(minusOne, 1).tileX() == -1, "L1 parent of -1 must floor-divide to -1");
		require(MapPyramidTileKey.parentOf(minusTwo, 1).tileX() == -1, "L1 parent of -2 must remain -1");
		require(MapPyramidTileKey.parentOf(minusThree, 1).tileX() == -2, "L1 parent of -3 must floor-divide to -2");
		require(MapPyramidTileKey.parentOf(new MapTileKey(OVERWORLD, -5, 7, MapRenderProfile.CURRENT.version()), 2).tileX() == -2, "L2 parent must use floor division");
	}

	private static void fourBaseTilesDeriveOneL1Tile() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-pyramid-");
		try {
			String profileHash = MapRenderProfile.CURRENT.contractHash();
			MapTileStore base = new MapTileStore(root);
			commit(base, profileHash, 0, 0, 0xFFFF3030, "a");
			commit(base, profileHash, 1, 0, 0xFF30FF30, "b");
			commit(base, profileHash, 0, 1, 0xFF3030FF, "c");
			commit(base, profileHash, 1, 1, 0xFFFFFFFF, "d");

			MapPyramidTileKey l1 = new MapPyramidTileKey(OVERWORLD, 1, 0, 0, MapRenderProfile.CURRENT.version());
			require(MapPyramidBuilder.rebuildTile(root, profileHash, MapRenderProfile.CURRENT, l1), "four L0 children must produce L1");
			MapPyramidStore.StoredTile stored = new MapPyramidStore(root).read(l1, profileHash).orElseThrow();
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(stored.imageBytes()));
			require(image != null && image.getWidth() == 256 && image.getHeight() == 256, "derived L1 must remain 256x256");
		require(rgbNear(image.getRGB(32, 32), 0xFFFF3030), "north-west quadrant must come from child 0,0");
		require(rgbNear(image.getRGB(224, 32), 0xFF30FF30), "north-east quadrant must come from child 1,0");
		require(rgbNear(image.getRGB(32, 224), 0xFF3030FF), "south-west quadrant must come from child 0,1");
		require(rgbNear(image.getRGB(224, 224), 0xFFFFFFFF), "south-east quadrant must come from child 1,1");
		require(stored.metadata().childSignature() != null && !stored.metadata().childSignature().isBlank(), "derived tile must persist a child signature");
		} finally {
			deleteTree(root);
		}
	}

	private static void pyramidUsesLegacyArithmeticRgbAverage() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-pyramid-color-average-");
		try {
			String profileHash = MapRenderProfile.CURRENT.contractHash();
			MapTileStore base = new MapTileStore(root);
			BufferedImage patterned = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) patterned.setRGB(x, y, 0xFF000000);
			patterned.setRGB(0, 0, 0xFFFF0000);
			patterned.setRGB(1, 0, 0xFF00FF00);
			patterned.setRGB(0, 1, 0xFF0000FF);
			patterned.setRGB(1, 1, 0xFFFFFFFF);
			ByteArrayOutputStream png = new ByteArrayOutputStream();
			require(ImageIO.write(patterned, "PNG", png), "PNG writer must exist");
			MapTileKey key = new MapTileKey(OVERWORLD, 0, 0, MapRenderProfile.CURRENT.version());
			base.commitBaseTile(key, profileHash, MapRenderProfile.CURRENT,
					new MapTileSourceState(key, 1L, "legacy-average", 1), png.toByteArray(), 1L, "phase-six-test");
			MapPyramidTileKey l1 = new MapPyramidTileKey(OVERWORLD, 1, 0, 0, MapRenderProfile.CURRENT.version());
			require(MapPyramidBuilder.rebuildTile(root, profileHash, MapRenderProfile.CURRENT, l1), "single available child must derive a sparse L1 tile");
			BufferedImage result = ImageIO.read(new ByteArrayInputStream(new MapPyramidStore(root).read(l1, profileHash).orElseThrow().imageBytes()));
			int expected = 0xFF7F7F7F;
			require(result.getRGB(0, 0) == expected, "pyramid must reproduce the legacy integer 2x2 RGB average exactly");
			String builder = Files.readString(Path.of("").toAbsolutePath().resolve("src/main/java/com/lostglade/server/maprender/MapPyramidBuilder.java"));
			require(!builder.contains("VALUE_INTERPOLATION_BILINEAR"), "pyramid must not use Java2D bilinear filtering");
		} finally {
			deleteTree(root);
		}
	}

	private static void pyramidPreservesTransparency() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-pyramid-alpha-");
		try {
			String profileHash = MapRenderProfile.CURRENT.contractHash();
			MapTileStore base = new MapTileStore(root);
			BufferedImage source = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
			// First output texel: one opaque red sample + three transparent samples.
			source.setRGB(0, 0, 0xFFFF0000);
			ByteArrayOutputStream png = new ByteArrayOutputStream();
			require(ImageIO.write(source, "PNG", png), "PNG writer must exist");
			MapTileKey key = new MapTileKey(OVERWORLD, 0, 0, MapRenderProfile.CURRENT.version());
			base.commitBaseTile(key, profileHash, MapRenderProfile.CURRENT,
					new MapTileSourceState(key, 1L, "alpha", 1), png.toByteArray(), 1L, "phase-six-test");
			MapPyramidTileKey l1 = new MapPyramidTileKey(OVERWORLD, 1, 0, 0, MapRenderProfile.CURRENT.version());
			require(MapPyramidBuilder.rebuildTile(root, profileHash, MapRenderProfile.CURRENT, l1), "transparent L0 must still derive an L1 tile");
			BufferedImage result = ImageIO.read(new ByteArrayInputStream(new MapPyramidStore(root).read(l1, profileHash).orElseThrow().imageBytes()));
			int partial = result.getRGB(0, 0);
			int partialAlpha = (partial >>> 24) & 0xFF;
			require(partialAlpha >= 63 && partialAlpha <= 64, "one opaque source sample out of four must produce quarter alpha, got " + partialAlpha);
			require(((partial >>> 16) & 0xFF) >= 250 && ((partial >>> 8) & 0xFF) <= 2 && (partial & 0xFF) <= 2, "partial-alpha color must remain red, not premultiplied black");
			require(((result.getRGB(8, 8) >>> 24) & 0xFF) == 0, "fully transparent source area must stay alpha=0 instead of opaque black");
			require(((result.getRGB(224, 224) >>> 24) & 0xFF) == 0, "missing child quadrant must stay transparent");
		} finally {
			deleteTree(root);
		}
	}

	private static void changingOneBaseTileRebuildsOnlyDerivedContent() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-pyramid-refresh-");
		try {
			String profileHash = MapRenderProfile.CURRENT.contractHash();
			MapTileStore base = new MapTileStore(root);
			commit(base, profileHash, 0, 0, 0xFFAA5500, "a");
			commit(base, profileHash, 1, 0, 0xFF00AA55, "b");
			MapPyramidTileKey l1 = new MapPyramidTileKey(OVERWORLD, 1, 0, 0, MapRenderProfile.CURRENT.version());
			MapPyramidBuilder.rebuildTile(root, profileHash, MapRenderProfile.CURRENT, l1);
			MapPyramidStore store = new MapPyramidStore(root);
			String before = store.readMetadata(l1, profileHash).orElseThrow().childSignature();

			commit(base, profileHash, 1, 0, 0xFFFF00FF, "b2");
			MapPyramidBuilder.rebuildAncestors(root, profileHash, MapRenderProfile.CURRENT, new MapTileKey(OVERWORLD, 1, 0, MapRenderProfile.CURRENT.version()));
			MapPyramidStore.StoredTile refreshed = store.read(l1, profileHash).orElseThrow();
			require(!before.equals(refreshed.metadata().childSignature()), "changed L0 checksum must change the L1 child signature");
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(refreshed.imageBytes()));
			require(rgbNear(image.getRGB(224, 32), 0xFFFF00FF), "rebuilt ancestor must contain the changed child pixels");
		} finally {
			deleteTree(root);
		}
	}

	private static void pyramidVersionsUsePhysicalCacheNamespaces() throws Exception {
		Path root = Files.createTempDirectory("lg2-map-pyramid-version-store-");
		try {
			MapPyramidStore store = new MapPyramidStore(root);
			String profileHash = "same-resource-profile";
			MapPyramidTileKey v1 = new MapPyramidTileKey(OVERWORLD, 1, 7, -9, 1);
			MapPyramidTileKey v2 = new MapPyramidTileKey(OVERWORLD, 1, 7, -9, 2);
			byte[] bytesV1 = new byte[]{1, 2, 3};
			byte[] bytesV2 = new byte[]{4, 5, 6, 7};
			store.commit(v1, profileHash, "children-v1", 256, bytesV1);
			store.commit(v2, profileHash, "children-v2", 256, bytesV2);
			require(java.util.Arrays.equals(bytesV1, store.read(v1, profileHash).orElseThrow().imageBytes()), "v1 pyramid cache must remain isolated");
			require(java.util.Arrays.equals(bytesV2, store.read(v2, profileHash).orElseThrow().imageBytes()), "v2 pyramid cache must remain isolated");
			require(Files.isRegularFile(root.resolve("minecraft_overworld/render-v1/profile-same-resource-profile/pyramid/l1/7/-9/current.json")), "v1 pyramid tile must live below render-v1");
			require(Files.isRegularFile(root.resolve("minecraft_overworld/render-v2/profile-same-resource-profile/pyramid/l1/7/-9/current.json")), "v2 pyramid tile must live below render-v2");
			boolean mismatchRejected = false;
			try {
				MapPyramidBuilder.rebuildTile(root, profileHash, MapRenderProfile.CURRENT, v1);
			} catch (IllegalArgumentException expected) {
				mismatchRejected = true;
			}
			require(mismatchRejected, "pyramid builder must reject mismatched render-profile versions");
		} finally {
			deleteTree(root);
		}
	}

	private static void pyramidIsCpuOnlyAndProtocolHasNoZoomRenderJobs() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String builder = Files.readString(project.resolve("src/main/java/com/lostglade/server/maprender/MapPyramidBuilder.java"));
		String payloads = Files.readString(project.resolve("src/main/java/com/lostglade/network/YandexMapRenderPayloads.java"));
		String jobs = Files.readString(project.resolve("src/main/java/com/lostglade/server/maprender/MapRenderJobService.java"));
		for (String forbidden : List.of("ClientLevel", "LevelRenderer", "YandexMapRenderPayloads", "ServerLevel", "getChunk(")) {
			require(!builder.contains(forbidden), "pyramid builder must stay CPU/cache-only: " + forbidden);
		}
		require(!payloads.contains("lodLevel") && !payloads.contains("zoomLevel"), "GPU protocol must not carry a zoom/LOD render level");
		require(jobs.contains("MapPyramidBuilder.onBaseTileCommitted"), "successful L0 commit must invalidate/rebuild only its ancestor chain");
	}

	private static void commit(MapTileStore store, String profileHash, long x, long z, int argb, String fingerprint) throws Exception {
		MapTileKey key = new MapTileKey(OVERWORLD, x, z, MapRenderProfile.CURRENT.version());
		MapTileSourceState source = new MapTileSourceState(key, 1L, fingerprint, 1);
		store.commitBaseTile(key, profileHash, MapRenderProfile.CURRENT, source, png(argb), System.nanoTime(), "phase-six-test");
	}

	private static byte[] png(int argb) throws Exception {
		BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) image.setRGB(x, y, argb);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		require(ImageIO.write(image, "PNG", output), "PNG writer must exist");
		return output.toByteArray();
	}

	private static boolean rgbNear(int actual, int expected) {
		for (int shift : new int[]{16, 8, 0}) {
			if (Math.abs(((actual >>> shift) & 0xFF) - ((expected >>> shift) & 0xFF)) > 3) return false;
		}
		return ((actual >>> 24) & 0xFF) >= 250;
	}

	private static void deleteTree(Path root) throws Exception {
		if (root == null || !Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			List<Path> sorted = new ArrayList<>(paths.toList());
			sorted.sort((left, right) -> Integer.compare(right.getNameCount(), left.getNameCount()));
			for (Path path : sorted) Files.deleteIfExists(path);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
