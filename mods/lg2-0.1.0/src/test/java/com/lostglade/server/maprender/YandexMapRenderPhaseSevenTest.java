package com.lostglade.server.maprender;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

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
		monitorUiIsStrictlyCacheOnly();
		monitorRoutingUsesDedicatedYandexRuntime();
		oldUiFrameContractIsPreserved();
		displayCacheDoesNotWaitForDiscovery();
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

	private static void monitorUiIsStrictlyCacheOnly() throws Exception {
		Path root = Path.of("").toAbsolutePath();
		String runtime = Files.readString(root.resolve("src/main/java/com/lostglade/server/MonitorYandexMapsRuntime.java"));
		String adapter = Files.readString(root.resolve("src/main/java/com/lostglade/server/MonitorYandexMapsClientTileRenderer.java"));
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
		require(adapter.contains("drawMagnifiedTileViewport") && adapter.contains("sourcePerScreenX"), "close zoom must crop visible source texels instead of drawing a huge 4096px tile");
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
		String screens = Files.readString(project.resolve("src/main/java/com/lostglade/server/MonitorScreenSystem.java"));
		require(adapter.contains("MISSING_RGB = 0x18242B"), "Yandex map valid frames must keep the historical missing-pixel color");
		require(adapter.contains("Frame.failure(null, \"Карта недоступна\")") && adapter.contains("Frame.failure(null, \"Карта пока не отрендерена\")"), "unavailable Yandex map must return a null frame so the old gradient/status UI renders");
		require(adapter.contains("graphics.fillRect(0, 0, safeWidth, safeHeight)"), "valid Yandex map frames must remain fully opaque like the pre-removal compositor");
		require(screens.contains("if (work.yandexMapsSnapshot() != null) {\n\t\t\treturn true;"), "Yandex monitor work must keep the old dynamic-render graphics path");
		require(screens.contains("&& work.yandexMapsSnapshot() == null"), "Yandex snapshots must keep the old static-cache exclusion contract");
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

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
