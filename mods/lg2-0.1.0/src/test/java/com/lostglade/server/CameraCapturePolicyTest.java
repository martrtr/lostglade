package com.lostglade.server;

import java.nio.file.Files;
import java.nio.file.Path;

/** Regression checks for the still-photo readiness, interaction and cancellation contract. */
public final class CameraCapturePolicyTest {
	private CameraCapturePolicyTest() {
	}

	public static void main(String[] args) throws Exception {
		readinessWaitsForVisibleTerrainOnly();
		rightClickAirIsTheShutterAndBlockUseKeepsPlacement();
		progressFramesFillTheSingleMapPreviewAndPhotoTiles();
		shadowPhotoSessionsFreezeDynamicWorldState();
		photoWarmupDoesNotMonopolizeLiveStreams();
		discardedPrintCancelsBothServerAndRendererWork();
		System.out.println("Camera capture policy checks passed");
	}

	private static void photoWarmupDoesNotMonopolizeLiveStreams() throws Exception {
		String client = Files.readString(Path.of("").toAbsolutePath().resolve("src/client/java/com/lostglade/client/RendererBotClientCapture.java"));
		require(client.contains("PHOTO_WARMUP_INTERVAL_NANOS = 100_000_000L") && client.contains("capture.canScheduleRender(nowNanos)"), "photo warm-up renders must be bounded instead of consuming every client tick");
		require(client.contains("activeCaptures < LostgladeClientSettings.maxParallelCaptures()") && client.contains("if (!RendererBotClientVideoRecording.hasActiveRecording())") && client.contains("captureToRender != null && (liveStreamToRender == null || captureToRender.finalCaptureReady())"), "a due live stream must render while a photo warms up or its final readback is in flight");
		require(client.contains("!readiness.settled()") && client.contains("stableVisibleSections != readiness.visibleSections()") && client.contains("CAPTURE_REQUIRED_SETTLED_RENDERS = Math.max(2"), "a still must finish only after received content and the visible terrain section count stabilize");
		String readiness = Files.readString(Path.of("").toAbsolutePath().resolve("src/client/java/com/lostglade/client/RendererBotShadowWorldManager.java"));
		require(readiness.contains("CameraCaptureReadinessPolicy.isUsable(contentReady, currentContentRendered, visibleSections, dirtyVisibleSections)") && readiness.contains("section.isDirty()"), "the final still barrier must wait only for dirty sections in the current camera view");
	}

	private static void shadowPhotoSessionsFreezeDynamicWorldState() throws Exception {
		String server = Files.readString(Path.of("").toAbsolutePath().resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));
		require(server.contains("snapshotState.markDynamicContentFrozen()"), "still captures must explicitly opt into a frozen shadow session");
		require(server.contains("activeState.freezeDynamicContent() && activeState.lastGameTime() != Long.MIN_VALUE"), "time and weather must be sent only once to a frozen photo session");
		require(server.contains("activeState.freezeDynamicContent() && activeState.entitySnapshotSent()"), "entities must be paired once and then stop moving in a photo session");
		require(server.contains("!activeState.freezeDynamicContent() && DIRTY_SHADOW_CHUNKS.contains") && server.contains("activeState == null || activeState.freezeDynamicContent()"), "dynamic block and transient packets must not mutate the frozen photo scene");
	}

	private static void progressFramesFillTheSingleMapPreviewAndPhotoTiles() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String maps = Files.readString(project.resolve("src/main/java/com/lostglade/server/map/MapImageRenderSystem.java"));
		String server = Files.readString(project.resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));
		String client = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotClientCapture.java"));
		require(maps.contains("applyProgressPreview(server, player, job);") && maps.contains("applyPreviewToMaps(job, preview);") && maps.contains("downscaleFrame(frame, job.outputWidth(), job.outputHeight())"), "a multi-map photo must expose a one-map scaled preview before and after final capture");
		require(maps.contains("sendCompletedPhotoMaps(server, job.photoData());"), "progressive photo tiles must be sent to both inventory and framed viewers");
		require(client.contains("dispatchProgressPreview(client, capture, renderTarget)") && client.contains("now - this.lastProgressPreviewAtMillis < 250L"), "renderer must stream bounded intermediate preview frames while terrain compiles");
		require(server.contains("capture.offerPreview(payload.pixels())") && server.contains("public byte[] pollPreview()"), "server must retain the newest intermediate frame without completing the final photo early");
	}

	private static void readinessWaitsForVisibleTerrainOnly() {
		require(CameraCaptureReadinessPolicy.sectionPending(true, true), "new section must wait");
		require(CameraCaptureReadinessPolicy.sectionPending(false, true), "scheduled build or pending upload is not a completed mesh");
		require(CameraCaptureReadinessPolicy.sectionPending(true, false), "invalidated mesh must wait");
		require(!CameraCaptureReadinessPolicy.sectionPending(false, false), "installed mesh including compiled empty terrain is ready");
		require(!CameraCaptureReadinessPolicy.isUsable(false, true, 8, 0), "unready shadow content must not be captured");
		require(!CameraCaptureReadinessPolicy.isUsable(true, false, 8, 0), "a scene must render once before capture");
		require(!CameraCaptureReadinessPolicy.isUsable(true, true, 0, 0), "an empty render target must not become a photo");
		require(!CameraCaptureReadinessPolicy.isUsable(true, true, 8, 1), "a photo must wait while visible terrain sections still compile");
		require(CameraCaptureReadinessPolicy.isUsable(true, true, 1, 0), "an enclosed scene must finish when its visible terrain is ready even if occluded work remains queued");
	}

	private static void rightClickAirIsTheShutterAndBlockUseKeepsPlacement() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String cameraItem = Files.readString(project.resolve("src/main/java/com/lostglade/item/CameraItem.java"));
		int airUseStart = cameraItem.indexOf("public InteractionResult use(Level level");
		int blockUseStart = cameraItem.indexOf("public InteractionResult useOn(UseOnContext context)");
		String airUse = cameraItem.substring(airUseStart, blockUseStart);
		String blockUse = cameraItem.substring(blockUseStart);
		require(airUse.contains("In the air there is nothing to place") && airUse.contains("CameraCaptureSystem.tryCapture"), "RMB in the air must start a still capture");
		require(!airUse.contains("player.isShiftKeyDown()"), "RMB in the air must not require Shift");
		require(blockUse.contains("player.isShiftKeyDown()") && blockUse.contains("return super.useOn(context);"), "RMB on a block must preserve camera placement and reserve Shift for a shutter");
	}

	private static void discardedPrintCancelsBothServerAndRendererWork() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String maps = Files.readString(project.resolve("src/main/java/com/lostglade/server/map/MapImageRenderSystem.java"));
		String provider = Files.readString(project.resolve("src/main/java/com/lostglade/server/CameraCaptureSystem.java"));
		String server = Files.readString(project.resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));
		String payloads = Files.readString(project.resolve("src/main/java/com/lostglade/network/RendererBotPayloads.java"));
		String client = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotClientCapture.java"));
		require(maps.contains("isPhotoStillReachable(server, player, job.photoData())") && maps.contains("job.provider().onCancelled(server);"), "map rendering must stop after its print leaves the inventory and frames");
		require(maps.contains("frameData.samePhoto(photoData)") && maps.contains("stackData.samePhoto(photoData)"), "a print must remain valid only in the author's inventory or an item frame");
		require(provider.contains("RendererBotCameraSystem.cancelCapture(this.captureHandle.requestId())"), "camera provider cancellation must reach the renderer request");
		require(server.contains("public static void cancelCapture(UUID requestId)") && server.contains("RendererBotCaptureCancelS2CPayload"), "server cancellation must remove its pending frame and notify the renderer");
		require(payloads.contains("RendererBotCaptureCancelS2CPayload") && payloads.contains("PROTOCOL_VERSION = 29"), "capture cancellation must be part of the negotiated renderer protocol");
		require(client.contains("RendererBotCaptureCancelS2CPayload.TYPE") && client.contains("clearPendingCapture(payload.requestId())"), "renderer cancellation must release pending GPU work");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
