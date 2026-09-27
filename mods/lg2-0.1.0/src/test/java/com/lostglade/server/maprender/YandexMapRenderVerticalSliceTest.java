package com.lostglade.server.maprender;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class YandexMapRenderVerticalSliceTest {
	private YandexMapRenderVerticalSliceTest() {
	}

	public static void main(String[] args) throws Exception {
		serverResultValidatorAcceptsTerrainLikeImage();
		serverResultValidatorRejectsCorruptFamilies();
		manualLeasePathIsStrictAndCameraIndependent();
		clientJobPathOwnsAndDestroysFreshScene();
		protocolHasExplicitLeaseSceneAndResultStages();
		System.out.println("Yandex map renderer vertical-slice checks passed");
	}

	private static void serverResultValidatorAcceptsTerrainLikeImage() throws Exception {
		BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int red = 35 + (x * 83 / 255);
				int green = 75 + (y * 110 / 255);
				int blue = 30 + ((x ^ y) & 63);
				image.setRGB(x, y, 0xFF000000 | (red << 16) | (green << 8) | blue);
			}
		}
		MapTilePngValidator.ValidationResult result = MapTilePngValidator.validate(png(image), 256);
		require(result.accepted(), "server validator must accept a varied opaque 256x256 terrain-like PNG: " + result.reason());
	}

	private static void serverResultValidatorRejectsCorruptFamilies() throws Exception {
		BufferedImage black = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 256; y++) {
			for (int x = 0; x < 256; x++) black.setRGB(x, y, 0xFF000000);
		}
		require(!MapTilePngValidator.validate(png(black), 256).accepted(), "server validator must reject a uniform black render");
		require(!MapTilePngValidator.validate(new byte[]{1, 2, 3}, 256).accepted(), "server validator must reject non-PNG bytes");
		BufferedImage wrong = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
		require(!MapTilePngValidator.validate(png(wrong), 256).accepted(), "server validator must reject the wrong render geometry");
	}

	private static void manualLeasePathIsStrictAndCameraIndependent() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String jobs = Files.readString(project.resolve("src/main/java/com/lostglade/server/maprender/MapRenderJobService.java"));
		String registry = Files.readString(project.resolve("src/main/java/com/lostglade/server/maprender/MapRenderWorkerRegistry.java"));
		String service = Files.readString(project.resolve("src/main/java/com/lostglade/server/maprender/YandexMapRenderService.java"));

		require(jobs.contains("findEligibleDedicated(server)"), "first vertical slice must dispatch only to an eligible dedicated renderer");
		require(jobs.contains("Permissions.COMMANDS_GAMEMASTER"), "manual one-tile render command must require gamemaster permission");
		require(jobs.contains("MapSnapshotRepository.readTile("), "accepted lease must build its scene from the disk-only snapshot repository");
		require(jobs.contains("snapshot-fingerprint-mismatch"), "result must be bound to the exact assigned snapshot fingerprint");
		require(jobs.contains("worker-profile-changed-before-result"), "result must be rejected if the worker visual profile changes in flight");
		int validate = jobs.indexOf("MapTilePngValidator.validate(");
		int commit = jobs.indexOf("store.commitBaseTile(");
		require(validate >= 0 && commit > validate, "server PNG validation must happen before atomic tile-store commit");
		require(jobs.contains("YandexMapRenderService.markTileCurrent("), "successful commit must publish incremental inventory/freshness state");
		require(!jobs.contains("YandexMapRenderService.refreshAsync(server)"), "single-tile commit must never trigger a full MCA rescan");
		require(registry.contains("!java.util.Objects.equals(previous, selected)"), "canonical profile changes must be detected rather than rescanned on every heartbeat");
		require(service.contains("MapRenderWorkerRegistry.canonicalProfileHash()"), "persistent cache namespace must follow the elected worker resource/build profile");
		require(!jobs.contains("RendererBotCameraSystem") && !jobs.contains("RendererBotShadowWorldManager"), "map jobs must not enter camera/session systems");
	}

	private static void clientJobPathOwnsAndDestroysFreshScene() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String client = Files.readString(project.resolve("src/client/java/com/lostglade/client/maprender/YandexMapRenderClient.java"));
		require(client.contains("YandexMapRenderScene.create(client, descriptor)"), "each accepted map job must create its own scene");
		require(client.contains("new YandexMapVanillaTopDownRenderer(job.scene)"), "each scene must own its top-down vanilla renderer");
		require(client.contains("payload.index() != job.receivedChunkPackets"), "chunk packet order must be strict");
		require(client.contains("payload.index() != job.receivedEntityPackets"), "ItemDisplay packet order must be strict");
		require(client.contains("receivedChunkPackets != job.expectedChunkPackets") && client.contains("receivedEntityPackets != job.expectedEntityPackets"), "scene-ready must require complete packet counts");
		require(client.contains("job.renderer.close()") && client.contains("job.scene.close()"), "completed/failed map jobs must destroy their renderer and ClientLevel scene");
		require(client.contains("client-validator:"), "client must reject a corrupt framebuffer before PNG submission");
	}

	private static void protocolHasExplicitLeaseSceneAndResultStages() throws Exception {
		String payloads = Files.readString(Path.of("").toAbsolutePath().resolve("src/main/java/com/lostglade/network/YandexMapRenderPayloads.java"));
		for (String type : new String[]{
				"MapRenderJobOfferS2CPayload",
				"MapRenderJobDecisionC2SPayload",
				"MapRenderSceneStartS2CPayload",
				"MapRenderSceneChunkS2CPayload",
				"MapRenderSceneEntityS2CPayload",
				"MapRenderSceneReadyS2CPayload",
				"MapRenderResultC2SPayload",
				"MapRenderFailureC2SPayload"
		}) {
			require(payloads.contains(type), "missing explicit map job protocol stage " + type);
		}
	}

	private static byte[] png(BufferedImage image) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		require(ImageIO.write(image, "PNG", output), "JVM PNG writer must be available for regression fixture");
		return output.toByteArray();
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
