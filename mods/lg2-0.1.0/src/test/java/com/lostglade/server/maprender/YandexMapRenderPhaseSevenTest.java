package com.lostglade.server.maprender;

import com.lostglade.server.map.MapPaletteQuantizer;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class YandexMapRenderPhaseSevenTest {
	private YandexMapRenderPhaseSevenTest() {
	}

	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		zoomMathIsStableAcrossNegativeCoordinates();
		closeZoomKeepsIntegralTexelWidths();
		monitorUiIsStrictlyCacheOnly();
		monitorRoutingUsesDedicatedYandexRuntime();
		oldUiFrameContractIsPreserved();
		tileLayerBackgroundIsTransparent();
		flatUiBackgroundIsOwnedByRuntime();
		transparentTileLayerCompositesWithoutBlack();
		displayCacheDoesNotWaitForDiscovery();
		clientRendererDiagnosticsAndDisconnectLifecycleAreSafe();
		animatedTexturesAreScopedToFirstFrame();
		System.out.println("Yandex map renderer Phase 7 checks passed");
	}

	private static void zoomMathIsStableAcrossNegativeCoordinates() {
		MapRenderProfile profile = MapRenderProfile.CURRENT;
		require(profile.tileBlocks() == 16 && profile.tilePixels() == 256, "canonical detail source must be one chunk at 16 px/block");
		double base = profile.tileBlocks() / (double) profile.tilePixels();
		require(Math.abs(base - 1.0D / 16.0D) < 1.0E-9D, "L0 must preserve native 16 px/block detail");
		require(Math.abs(Math.scalb(base, -4) - 1.0D / 256.0D) < 1.0E-9D, "maximum magnification must display one block at 256 px");
		require(Math.abs(Math.scalb(base, 3) - 0.5D) < 1.0E-9D, "L3 must remain CPU-derived scale only");
		require((long) Math.floor(-0.001D / 16.0D) == -1L, "negative epsilon belongs to tile -1");
		require((long) Math.floor(-16.0D / 16.0D) == -1L, "negative exact boundary belongs to tile -1");
		require((long) Math.floor(-16.001D / 16.0D) == -2L, "coordinate below negative boundary belongs to tile -2");
	}

	private static void closeZoomKeepsIntegralTexelWidths() throws Exception {
		Class<?> compositor = Class.forName("com.lostglade.server.MonitorYandexMapsClientTileRenderer");
		Method draw = compositor.getDeclaredMethod(
				"drawMagnifiedTileViewport",
				Graphics2D.class, BufferedImage.class, int.class, int.class, int.class, int.class, int.class
		);
		draw.setAccessible(true);
		BufferedImage source = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < source.getHeight(); y++) {
			for (int x = 0; x < source.getWidth(); x++) {
				int red = (x & 1) == 0 ? 0x30 : 0xD0;
				int green = (y & 1) == 0 ? 0x40 : 0xE0;
				source.setRGB(x, y, 0xFF000000 | (red << 16) | (green << 8) | 0x55);
			}
		}
		for (int scale : new int[]{2, 4}) {
			BufferedImage target = new BufferedImage(333, 333, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = target.createGraphics();
			draw.invoke(null, graphics, source, -101, -73, source.getWidth() * scale, target.getWidth(), target.getHeight());
			graphics.dispose();
			assertInternalRuns(target, true, 137, scale);
			assertInternalRuns(target, false, 137, scale);
		}
	}

	private static void assertInternalRuns(BufferedImage image, boolean horizontal, int fixed, int expectedRun) {
		int length = horizontal ? image.getWidth() : image.getHeight();
		int[] runs = new int[length];
		int runCount = 0;
		int last = horizontal ? image.getRGB(0, fixed) : image.getRGB(fixed, 0);
		int run = 1;
		for (int i = 1; i < length; i++) {
			int current = horizontal ? image.getRGB(i, fixed) : image.getRGB(fixed, i);
			if (current == last) {
				run++;
			} else {
				runs[runCount++] = run;
				run = 1;
				last = current;
			}
		}
		runs[runCount++] = run;
		require(runCount > 4, "synthetic magnification fixture must contain multiple texel runs");
		for (int i = 1; i < runCount - 1; i++) {
			require(runs[i] == expectedRun, "internal magnified texel run must be exactly " + expectedRun + " px, got " + runs[i]);
		}
	}

	private static void monitorUiIsStrictlyCacheOnly() throws Exception {
		Path root = Path.of("").toAbsolutePath();
		String runtime = Files.readString(root.resolve("src/main/java/com/lostglade/server/MonitorYandexMapsRuntime.java"));
		String adapter = Files.readString(root.resolve("src/main/java/com/lostglade/server/MonitorYandexMapsClientTileRenderer.java"));
		require(runtime.contains("Math.max(1, canvas.width())") && runtime.contains("Math.max(1, canvas.height())"), "Yandex compositor must render directly at the final media viewport size");
		require(!runtime.contains("Math.max(1, layout.canvasWidth())") && !runtime.contains("Math.max(1, layout.canvasHeight())"), "Yandex terrain must not be rendered at full monitor size and then squeezed through the viewport inset");
		require(runtime.contains("frame.getWidth() == canvas.width()") && runtime.contains("mapGraphics.drawImage(frame, canvas.x(), canvas.y(), null)"), "normal Yandex frame path must be a 1:1 blit without a second resample");
		for (String forbidden : List.of(
				"ClientLevel",
				"LevelRenderer",
				"RendererBotCameraSystem",
				"lg2-yandex-map-client-tiles",
				"getChunk("
		)) {
			require(!adapter.contains(forbidden), "Yandex UI cache adapter must not resurrect old rendering/loading path: " + forbidden);
		}
		require(adapter.contains("new MapTileStore(storeRoot)"), "L0 UI adapter must read immutable v2 base cache");
		require(adapter.contains("new MapPyramidStore(storeRoot)"), "zoomed UI adapter must read CPU pyramid cache");
		require(adapter.contains("MapPyramidBuilder.request"), "missing derived cache must be repaired asynchronously");
		require(adapter.contains("MapRenderScheduler.demandVisible"), "viewport must only hint replacement scheduler priority");
		require(adapter.contains("MIN_ZOOM_EXPONENT = -4"), "adapter must preserve four magnification steps beyond native L0");
		require(adapter.contains("VALUE_INTERPOLATION_NEAREST_NEIGHBOR"), "close zoom must preserve Minecraft texture texels without smoothing");
		require(!adapter.contains("MISSING_RGB") && !adapter.contains("graphics.fillRect(0, 0, safeWidth, safeHeight)"), "tile compositor must not bake an opaque placeholder into the tile layer");
		require(adapter.contains("AffineTransform.getTranslateInstance") && adapter.contains("transform.scale(scaleX, scaleY)"), "close zoom must preserve one integral transform for the whole source tile");
		require(!adapter.contains("sourcePerScreenX") && !adapter.contains("Math.ceil((x2 - tileScreenX)"), "close zoom must never rescale a rounded source crop");
		require(runtime.contains("YandexMapMarkerStore.markers"), "markers must remain an old-UI overlay over cached imagery");
	}

	private static void monitorRoutingUsesDedicatedYandexRuntime() throws Exception {
		Path root = Path.of("").toAbsolutePath();
		String system = Files.readString(root.resolve("src/main/java/com/lostglade/server/MonitorScreenSystem.java"));
		String input = Files.readString(root.resolve("src/main/java/com/lostglade/server/MonitorScreenInputController.java"));
		String app = Files.readString(root.resolve("src/main/java/com/lostglade/server/monitor/MonitorYandexMapsApp.java"));

		require(system.contains("MonitorYandexMapsRuntime.drawScreen"), "render path must route Yandex Maps to cache compositor");
		require(system.contains("MonitorYandexMapsRuntime.onPlayerHotbarScroll"), "hotbar scroll must support map zoom");
		require(system.contains("work.viewMode() != ScreenViewMode.YANDEX_MAPS"), "mutable map view must never enter generic static monitor cache");
		require(input.contains("MonitorYandexMapsRuntime.handleTouch"), "touch path must route to Yandex map runtime");
		require(!app.contains("Рендер карты временно отключён"), "launcher must no longer advertise disabled map rendering");

		String runtime = Files.readString(root.resolve("src/main/java/com/lostglade/server/MonitorYandexMapsRuntime.java"));
		require(runtime.contains("PAN_ANIMATION_MS = 320L") && runtime.contains("PAN_FRAME_DELAY_MS = 40L"), "old 320ms/40ms map pan animation contract must be preserved");
		require(runtime.contains("double eased = t * t * (3.0D - 2.0D * t)"), "old smoothstep pan easing must be preserved");
		require(runtime.contains("drawMapHeader") && runtime.contains("drawZoomControls") && runtime.contains("drawCenterReticle") && runtime.contains("drawMarkerEditor"), "pre-rewrite Yandex Maps chrome/editor must remain restored");
		require(runtime.contains("PlayerUiIcon.AIMING_2") && runtime.contains("PlayerUiIcon.LOCATION") && runtime.contains("PlayerUiIcon.DIRECTIONS_2_LINE"), "old floating Yandex Maps icon controls must remain restored");
		require(!runtime.contains("drawChrome("), "replacement simplified Yandex chrome must not return");
		require(system.contains("MonitorYandexMapsRuntime.captureSnapshot") && system.contains("work.yandexMapsSnapshot()"), "screen pipeline must preserve old snapshot-based animation lifecycle");
		require(input.contains("MonitorYandexMapsRuntime.handleTouch(player, level, component, layout, touchPoint)"), "touch pipeline must use the old self-contained Yandex interaction handler");
	}

	private static void oldUiFrameContractIsPreserved() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String adapter = Files.readString(project.resolve("src/main/java/com/lostglade/server/MonitorYandexMapsClientTileRenderer.java"));
		String runtime = Files.readString(project.resolve("src/main/java/com/lostglade/server/MonitorYandexMapsRuntime.java"));
		String screens = Files.readString(project.resolve("src/main/java/com/lostglade/server/MonitorScreenSystem.java"))
				.replace("\r\n", "\n");
		require(!runtime.contains("drawMapCanvasBackground"), "Yandex runtime must not paint a second gradient/textured map background over the compositor frame");
		require(adapter.contains("Frame.failure(canvas, \"Карта недоступна\")") && adapter.contains("Frame.failure(canvas, \"Карта пока не отрендерена\")"), "even an empty/unavailable cache must return the flat old-renderer background instead of exposing screen_on.png");
		require(screens.contains("if (work.yandexMapsSnapshot() != null) {\n\t\t\treturn true;"), "Yandex monitor work must keep the old dynamic-render graphics path");
		require(screens.contains("&& work.yandexMapsSnapshot() == null"), "Yandex snapshots must keep the old static-cache exclusion contract");
	}

	private static void tileLayerBackgroundIsTransparent() throws Exception {
		Class<?> compositor = Class.forName("com.lostglade.server.MonitorYandexMapsClientTileRenderer");
		Method render = java.util.Arrays.stream(compositor.getDeclaredMethods())
				.filter(method -> method.getName().equals("render") && method.getParameterCount() == 9)
				.findFirst().orElseThrow();
		render.setAccessible(true);
		Object frame = render.invoke(null, null, net.minecraft.world.level.Level.OVERWORLD, 0.0D, 0.0D, 31, 29, 1.0D, null, null);
		Method imageAccessor = frame.getClass().getDeclaredMethod("image");
		imageAccessor.setAccessible(true);
		BufferedImage image = (BufferedImage) imageAccessor.invoke(frame);
		require(image != null && image.getType() == BufferedImage.TYPE_INT_ARGB, "tile compositor must return an ARGB layer");
		for (int y : new int[]{0, 14, 28}) for (int x : new int[]{0, 15, 30}) {
			require(((image.getRGB(x, y) >>> 24) & 0xFF) == 0, "empty tile-layer pixels must remain alpha=0");
		}
	}

	private static void flatUiBackgroundIsOwnedByRuntime() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String runtime = Files.readString(project.resolve("src/main/java/com/lostglade/server/MonitorYandexMapsRuntime.java"));
		String adapter = Files.readString(project.resolve("src/main/java/com/lostglade/server/MonitorYandexMapsClientTileRenderer.java"));
		require(runtime.contains("MAP_CANVAS_BACKGROUND_RGB = 0x282828"), "Yandex UI must own the flat neutral monitor gray background");
		require(runtime.contains("graphics.fillRect(canvas.x(), canvas.y(), canvas.width(), canvas.height())"), "flat UI background must be painted under the transparent tile layer");
		require(!adapter.contains("0x18242B"), "tile/cache compositor must stay colorless and preserve alpha");
	}

	private static void transparentTileLayerCompositesWithoutBlack() {
		BufferedImage background = new BufferedImage(17, 13, BufferedImage.TYPE_INT_ARGB);
		Graphics2D base = background.createGraphics();
		base.setColor(new java.awt.Color(0x282828));
		base.fillRect(0, 0, background.getWidth(), background.getHeight());

		BufferedImage tileLayer = new BufferedImage(17, 13, BufferedImage.TYPE_INT_ARGB);
		// Simulate a real map fragment while deliberately leaving the rest alpha=0.
		for (int y = 3; y < 10; y++) {
			for (int x = 4; x < 12; x++) {
				tileLayer.setRGB(x, y, 0xFF5A7A32);
			}
		}
		base.drawImage(tileLayer, 0, 0, null);
		base.dispose();

		for (int[] point : new int[][]{{0, 0}, {16, 12}, {3, 6}, {13, 6}}) {
			int argb = background.getRGB(point[0], point[1]);
			require(argb == 0xFF282828, "transparent tile background must reveal UI gray exactly; got 0x" + Integer.toHexString(argb));
		}
		require(background.getRGB(6, 6) == 0xFF5A7A32, "opaque map imagery must still cover the UI background");
	}

	private static void displayCacheDoesNotWaitForDiscovery() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String adapter = Files.readString(project.resolve("src/main/java/com/lostglade/server/MonitorYandexMapsClientTileRenderer.java"));
		require(adapter.contains("server.getWorldPath(LevelResource.ROOT).resolve(STORE_DIRECTORY)"), "Yandex UI must be able to address committed cache before discovery completes");
		require(adapter.contains("chooseDisplayProfileHash("), "Yandex UI must choose a readable display cache independently from the worker profile");
		require(adapter.contains("coverage > bestCoverage"), "display profile selection must prefer actual visible coverage");
		require(adapter.contains("coverage == bestCoverage && canonical"), "canonical worker profile should win only when visible coverage ties");
		require(!adapter.contains("discovery == null || discovery.profileHash() == null"), "committed map display must not be gated by discovery availability");
		require(adapter.contains("discovery == null || discovery.inventory() == null"), "background viewport priority hint must be null-safe while discovery starts");
	}

	private static void clientRendererDiagnosticsAndDisconnectLifecycleAreSafe() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String mapClient = Files.readString(project.resolve("src/client/java/com/lostglade/client/maprender/YandexMapRenderClient.java"));
		String mapRenderer = Files.readString(project.resolve("src/client/java/com/lostglade/client/maprender/YandexMapVanillaTopDownRenderer.java"));
		String capture = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotClientCapture.java"));
		String video = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotClientVideoRecording.java"));
		String shadow = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotShadowWorldManager.java"));
		String settings = Files.readString(project.resolve("src/client/java/com/lostglade/client/LostgladeSettingsScreen.java"));
		String diagnostics = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererClientDiagnostics.java"));
		String diagnosticsScreen = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererDiagnosticsScreen.java"));

		require(mapClient.contains("retireActiveJob(\"disconnect\")"), "map disconnect must detach jobs instead of synchronously destroying GL resources");
		require(mapClient.contains("RETIRED_JOBS") && mapClient.contains("readbackPending()"), "map GL teardown must wait for screenshot readback retirement");
		require(mapRenderer.contains("public boolean readbackPending()"), "top-down renderer must expose pending readback state for safe retirement");
		require(capture.contains("isPendingCapture(payload.requestId())") && capture.contains("ClientPlayNetworking.canSend"), "late camera callbacks must require an active request and live network channel");
		require(capture.contains("disconnectGpuCleanupTicks = 100"), "camera GPU resources must not be destroyed synchronously from disconnect callback");
		require(video.contains("isActiveRecording(recording.payload().requestId())") && video.contains("ClientPlayNetworking.canSend"), "late video callbacks must be stale-safe");
		int abortStart = video.indexOf("private static void abortAll(String message)");
		int abortEnd = abortStart < 0 ? -1 : video.indexOf("private static void abortRecording", abortStart);
		String abortBody = abortStart >= 0 && abortEnd > abortStart ? video.substring(abortStart, abortEnd) : "";
		require(!abortBody.contains("RendererBotOffscreenWorldRenderer.clearCaches()"), "video disconnect must not synchronously destroy shared camera GL caches");
		require(shadow.contains("retireForDisconnect()") && shadow.contains("DISCONNECT_RETIRED_SESSIONS"), "shadow worlds must be retired away from the disconnect callback");
		require(settings.contains("Renderer diagnostics / логи") && settings.contains("new RendererDiagnosticsScreen(this)"), "Lostglade settings must expose renderer diagnostics");
		require(diagnostics.contains("mapAcceptedLastMinute") && diagnostics.contains("activeCameraJobs()"), "renderer diagnostics must expose camera and map throughput");
		require(diagnostics.contains("[renderer-diagnostics][{}] {}"), "renderer diagnostics must mirror hidden bot events into the ordinary client log");
		require(diagnosticsScreen.contains("текущий map job") && diagnosticsScreen.contains("Последние события"), "renderer diagnostics UI must show active map work and live event history");
		require(mapClient.contains("VANILLA_SETTLE_TIMEOUT_TICKS = 100L") && mapClient.contains("render-settle-timeout:"), "sparse vanilla scenes must fail fast instead of occupying a renderer for the full 120s lease");
		require(mapClient.contains("readinessSummary(result.readiness())"), "settle timeout diagnostics must expose the exact vanilla readiness state");
		String jobService = Files.readString(project.resolve("src/main/java/com/lostglade/server/maprender/MapRenderJobService.java"));
		require(jobService.contains("!payload.reason().startsWith(\"render-settle-timeout:\")"), "settle timeout must back off the tile without penalizing worker health");
	}

	private static void animatedTexturesAreScopedToFirstFrame() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String renderer = Files.readString(project.resolve("src/client/java/com/lostglade/client/maprender/YandexMapVanillaTopDownRenderer.java"));
		String freeze = Files.readString(project.resolve("src/client/java/com/lostglade/client/maprender/YandexMapTextureAnimationFreeze.java"));
		String atlasAccessor = Files.readString(project.resolve("src/client/java/com/lostglade/mixin/client/TextureAtlasAnimationAccessor.java"));
		String stateAccessor = Files.readString(project.resolve("src/client/java/com/lostglade/mixin/client/SpriteAnimationStateAccessor.java"));
		require(MapRenderProfile.CURRENT.version() == 8, "first-frame animated textures must use their own immutable render namespace");
		require(renderer.contains("capture ? YandexMapTextureAnimationFreeze.freezeFirstFrames(client) : null"), "animated-texture freeze must run only for the final captured framebuffer, never warmup frames");
		require(freeze.contains("lg2$setFrame(0)") && freeze.contains("lg2$setSubFrame(0)") && !freeze.contains("state.tick()"),
				"map render must always present the first configured animation frame");
		require(freeze.contains("snapshot.frame()") && freeze.contains("snapshot.subFrame()") && freeze.contains("snapshot.dirty()"), "volunteer client animation state must be restored exactly after map rendering");
		require(countOccurrences(freeze, "lg2$uploadAnimationFrames()") >= 2, "vanilla atlas upload path must be used both to freeze and restore texture state");
		require(atlasAccessor.contains("@Invoker(\"uploadAnimationFrames\")") && stateAccessor.contains("@Accessor(\"frame\")"), "animation hardening must use narrow vanilla-state access instead of replacing the atlas renderer");
	}

	private static int countOccurrences(String text, String needle) {
		int count = 0;
		int from = 0;
		while ((from = text.indexOf(needle, from)) >= 0) {
			count++;
			from += needle.length();
		}
		return count;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
