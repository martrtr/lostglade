package com.lostglade.server.maprender;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.nio.file.Files;
import java.nio.file.Path;

public final class YandexMapRenderPhaseFourTest {
	private YandexMapRenderPhaseFourTest() {
	}

	public static void main(String[] args) throws Exception {
		orthographicFrustumSeesTerrain();
		Path project = Path.of("").toAbsolutePath();
		String renderer = read(project, "src/client/java/com/lostglade/client/maprender/YandexMapVanillaTopDownRenderer.java");
		String scene = read(project, "src/client/java/com/lostglade/client/maprender/YandexMapRenderScene.java");
		String world = read(project, "src/client/java/com/lostglade/client/maprender/YandexMapRenderWorld.java");
		String effects = read(project, "src/client/java/com/lostglade/mixin/client/YandexMapLevelEffectsMixin.java");
		String validator = read(project, "src/client/java/com/lostglade/client/maprender/YandexMapRenderValidator.java");
		String mixins = read(project, "src/client/resources/lg2.client.mixins.json");
		String verticalRangeMixin = read(project, "src/client/java/com/lostglade/mixin/client/SectionOcclusionGraphMapVerticalRangeMixin.java");

		require(renderer.contains("this.scene.descriptor().tileBlocks() * 0.5F"), "projection span must come from the detail tile contract");
		require(!renderer.contains("HALF_TILE_BLOCKS") && !renderer.contains("128L"), "renderer must not hardcode the retired 128-block tile geometry");
		require(renderer.contains("TOP_DOWN_YAW = 180.0F")
				&& renderer.contains("snapTo(position, TOP_DOWN_YAW, TOP_DOWN_PITCH)")
				&& renderer.contains("setOldPosAndRot(position, TOP_DOWN_YAW, TOP_DOWN_PITCH)"),
				"L0 tiles must keep the original +X-right/+Z-down 180-degree top-down camera orientation");
		require(renderer.contains("setOrtho("), "map projection must be orthographic");
		require(renderer.contains("ProjectionType.ORTHOGRAPHIC"), "GPU projection state must explicitly be orthographic");
		require(renderer.contains("FogRenderer.FogMode.NONE"), "map terrain must not be distance/atmospheric fogged");
		require(renderer.contains("this.fogRenderer.setupFog("), "top-down render must compute the final legacy vanilla fog-color state even with fog disabled");
		require(renderer.contains("fogColor,"), "computed vanilla fog color must reach LevelRenderer");
		require(renderer.contains("this.lightTexture.updateLightTexture(partialTick)"), "map lightmap must use the gameplay partial tick like the final legacy renderer");
		require(renderer.contains("this.camera.attributeProbe().reset()") && renderer.contains("this.camera.attributeProbe().tick(this.scene.level(), this.camera.position())"), "environment probe must refresh immediately before the detached-scene lightmap");
		require(renderer.contains("client.options.getMenuBackgroundBlurriness()"), "global settings must mirror the final legacy LevelRenderer state");
		require(!renderer.contains("TextureFilteringMethod.RGSS"), "top-down map rendering must never enable RGSS; the final legacy renderer forced it off");
		require(renderer.contains("new TextureTarget(\"lg2_yandex_map_topdown\""), "map renderer must own its render target");
		require(renderer.contains("Screenshot.takeScreenshot(this.renderTarget"), "final image must use vanilla render target readback");
		require(!renderer.contains("Thread.sleep") && !renderer.contains("sleep("), "readiness must never use timing sleeps");
		require(!renderer.toLowerCase().contains("atlas") && !renderer.contains("setFrame("), "renderer must not rewind global texture animation state");

		require(world.contains("setTimeFromServer(0L, 6000L, false)"), "map world must force canonical midday");
		require(world.contains("setRainLevel(0.0F)") && world.contains("setThunderLevel(0.0F)"), "map world must force dry weather");
		require(world.contains("entity instanceof Display.ItemDisplay"), "client world must reject ordinary entities");
		require(!world.contains("void tick("), "map world must not own a simulation tick loop");

		require(scene.contains("new RenderBuffers(1)"), "each map scene must own fresh render buffers");
		require(scene.contains("new LevelRenderer("), "each map job must own a fresh vanilla LevelRenderer");
		require(scene.contains("new YandexMapRenderWorld("), "each job must own a fresh map ClientLevel");
		require(scene.contains("getCompileQueueSize()") && scene.contains("getToUpload()") && scene.contains("hasRenderedAllSections()"), "readiness barrier must observe vanilla compile and upload queues");
		require(scene.contains("visibleSections > 0 && allSectionsRendered"), "fresh LevelRenderer must not be considered settled before vanilla publishes visible sections");
		require(scene.contains("metadata.id()") && scene.contains("itemDisplayIds.contains"), "entity metadata must be scoped to admitted ItemDisplay IDs");
		require(!scene.contains("RendererBotShadowWorldManager") && !scene.contains("RendererBotCameraSystem"), "map scene must not register in camera shadow/session systems");

		for (String pass : new String[]{"addParticlesPass", "addCloudsPass", "addWeatherPass", "addSkyPass"}) {
			require(effects.contains("method = \"" + pass + "\""), "map scene must suppress transient vanilla pass " + pass);
		}
		require(mixins.contains("YandexMapLevelEffectsMixin"), "map effect suppressor must be registered in client mixin config");
		require(mixins.contains("SectionOcclusionGraphMapVerticalRangeMixin")
				&& verticalRangeMixin.contains("method = \"getRelativeFrom\"")
				&& verticalRangeMixin.contains("getSectionsCount() + 4"),
				"map scene must widen only vanilla vertical SOG traversal, not horizontal ViewArea size");

		require(renderer.contains("consecutiveSettledChecks") && renderer.contains("lastSettledRevision"), "final readback must require stable readiness across checks");
		require(renderer.indexOf("renderFrame(false, null)") < renderer.indexOf("Screenshot.takeScreenshot"), "prime render must happen before any final readback");
		require(validator.contains("near-uniform-frame") && validator.contains("near-black-frame") && validator.contains("near-white-frame"), "validator must reject known corrupted frame families");
		require(!validator.contains("setPixel(")
				&& !validator.contains("setPixelABGR(")
				&& !validator.toLowerCase().contains("brightness correction")
				&& !validator.toLowerCase().contains("rescale"), "validator must reject bad frames, not repair/recolor them");

		System.out.println("Yandex map renderer Phase 4 architecture checks passed");
	}

	private static void orthographicFrustumSeesTerrain() {
		Quaternionf cameraRotation = new Quaternionf().rotationYXZ(
				(float) Math.PI,
				-(float) (Math.PI / 2.0D),
				0.0F
		);
		Matrix4f view = new Matrix4f().rotation(new Quaternionf(cameraRotation).conjugate());
		Matrix4f projection = new Matrix4f().setOrtho(-8.0F, 8.0F, -8.0F, 8.0F, 0.05F, 1024.0F);
		Frustum frustum = new Frustum(view, projection);
		frustum.prepare(8.0D, 448.0D, 8.0D);
		AABB terrain = new AABB(0.0D, 64.0D, 0.0D, 16.0D, 80.0D, 16.0D);
		require(frustum.isVisible(terrain),
				"orthographic top-down frustum must see terrain directly below the tile camera");
		Frustum offset = LevelRenderer.offsetFrustum(frustum);
		require(offset.isVisible(terrain),
				"vanilla offsetFrustum must preserve top-down terrain visibility");
		int visibleSmallSections = 0;
		StringBuilder visibility = new StringBuilder();
		for (int y = -64; y < 320; y += 16) {
			AABB section = new AABB(0.0D, y, 0.0D, 16.0D, y + 16.0D, 16.0D);
			boolean rawVisible = frustum.isVisible(section);
			boolean offsetVisible = offset.isVisible(section);
			if (offsetVisible) visibleSmallSections++;
			visibility.append(y).append(':').append(rawVisible).append('/').append(offsetVisible).append(' ');
		}
		System.out.println("top-down section visibility: " + visibility);
		require(visibleSmallSections > 0, "top-down culling must see at least one 16-block section below camera");
	}

	private static String read(Path project, String relative) throws Exception {
		Path path = project.resolve(relative);
		require(Files.isRegularFile(path), "missing Phase 4 source file " + relative);
		return Files.readString(path);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
