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
		videoBeginsAfterItsFirstRenderedFrame();
		videoRecordsOperatorPathBeforeDeferredTerrainReplay();
		videoFrameTimelineRestoresHistoricShadowState();
		videoRenderSurvivesGracefulServerRestart();
		videoCompletionIsAcknowledgedBeforeRendererCleanup();
		videoTerminalFrameCannotRemainStuck();
		droppedCameraCancelsVideoAndReleasesCooldown();
		discardedPrintCancelsBothServerAndRendererWork();
		placedCameraUsesLegacyPoseWithoutChangingHandheldPose();
		System.out.println("Camera capture policy checks passed");
	}

	private static void videoBeginsAfterItsFirstRenderedFrame() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String video = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotClientVideoRecording.java"));
		String server = Files.readString(project.resolve("src/main/java/com/lostglade/server/CameraVideoRecordingSystem.java"));
		require(video.contains("completeDeferredReplayRender") && video.contains("appendCameraFrame") && video.contains("seal()"), "video replay must keep one explicit render path while accepting poses during recording");
		require(!video.contains("REQUIRED_SETTLED_RENDERS") && !video.contains("observeReadinessProbe"), "deferred video must not inherit obsolete live-recording warm-up gates");
		require(!video.contains("completedFuture(active.firstPreviewFrame)") && !video.contains("completedFuture(active.firstFullFrame)"), "live preview must not reuse the first-frame poster for every encoded frame");
		require(server.contains("Camera video recording {} failed") && server.contains(".append(Component.literal(\" \" + reason))"), "video failures must retain and show their actual reason");
	}

	private static void videoRecordsOperatorPathBeforeDeferredTerrainReplay() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String server = Files.readString(project.resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));
		String recording = Files.readString(project.resolve("src/main/java/com/lostglade/server/CameraVideoRecordingSystem.java"));
		String client = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotClientVideoRecording.java"));
		String payloads = Files.readString(project.resolve("src/main/java/com/lostglade/network/RendererBotPayloads.java"));
		require(recording.contains("recordVideoCameraPose") && recording.contains("finishVideoCameraPath") && recording.contains("applyRenderingCooldown"), "the operator path must be recorded before rendering and the camera must remain cooling down during replay");
		require(recording.contains("createQueuedPhotoName(0)") && recording.contains("notifyRenderingProgress") && recording.contains("estimatedCooldownTicks"), "a video print must be issued immediately, report render percent, and keep a progress-aware cooldown");
		String playback = Files.readString(project.resolve("src/main/java/com/lostglade/server/CameraAnimatedMapPlaybackSystem.java"));
		require(playback.contains("Files.isRegularFile(CameraMediaCache.videoSourcePath(key.sourceKey()))"), "an issued but unfinished video print must not attempt to open a missing MP4");
		require(playback.contains("appendPendingVideoFrame") && playback.contains("pendingVideoFullFrame") && playback.contains("MAX_PENDING_BYTES") && playback.contains("frameStride") && playback.contains("SFrames may render directly from MapItemSavedData"), "an issued video must loop its growing rendered prefix in vanilla and map-frame replacement renderers without unbounded memory use");
		require(Files.readString(project.resolve("src/main/java/com/lostglade/server/map/MapImageRenderSystem.java")).contains("updatePhotoItemsInInventories(server, job.photoData(), progressName)") && recording.contains("clearRenderCooldown"), "photo prints must receive progress titles and all camera render paths must release their cooldowns");
		require(server.contains("recording.clientStartSent()") && server.contains("recording.beginDeferredRender()") && server.contains("resolveVideoRecordingTarget"), "the renderer must start while recording and retain one sliding shadow session through its final replay tail");
		require(server.contains("MAX_VIDEO_RECORDING_FPS = 20") && server.contains("Math.clamp(Math.max(1, targetFps), 1, MAX_VIDEO_RECORDING_FPS)") && client.contains("MAX_TARGET_FPS = 20") && client.contains("Math.clamp(payload.targetFps(), 1, MAX_TARGET_FPS)"), "the server and encoder must both enforce the 20 FPS video ceiling");
		require(server.contains("This seals the streamed pose list") && server.contains("RendererBotVideoRecordingFrameS2CPayload"), "the stop packet must seal a streaming recording instead of cancelling it, and later poses must be appended");
		require(server.contains("if (recording.stopRequested()") && server.contains("captured initial path instead of waiting for poses forever at 0%") && server.contains("new RendererBotPayloads.RendererBotVideoRecordingStopS2CPayload(recording.requestId())"), "a recording stopped before renderer start must send its delayed seal immediately after the start packet");
		require(client.contains("!readiness.settled()") && client.contains("sendReplayProgress") && client.contains("advanceAfterEncodedFrame") && client.contains("completedFrameCount") && client.contains("terminal size"), "each deferred video pose must wait for fully compiled visible terrain, including an explicit final progress acknowledgement");
		require(client.contains("encoderCompatibleDimension") && client.contains("(dimension & 1) == 0") && client.contains("libx264's yuv420p output needs an even width and height"), "offscreen video dimensions must be encoder-compatible instead of producing a closed ffmpeg input stream for odd aspect ratios");
		require(payloads.contains("VideoCameraFrame") && payloads.contains("RendererBotVideoRecordingFrameS2CPayload") && payloads.contains("RendererBotVideoRecordingFrameSnapshotS2CPayload") && payloads.contains("RendererBotVideoRecordingRenderedFrameC2SPayload") && payloads.contains("RendererBotVideoRecordingProgressC2SPayload") && payloads.contains("RendererBotVideoRecordingFrameReadyS2CPayload") && payloads.contains("RendererBotVideoRecordingTruncateS2CPayload") && payloads.contains("PROTOCOL_VERSION = 40"), "streamed replay poses, historic-world boundaries, growing playback frames, readiness acknowledgements, and prefix truncation must be part of the renderer protocol");
	}

	private static void droppedCameraCancelsVideoAndReleasesCooldown() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String recording = Files.readString(project.resolve("src/main/java/com/lostglade/server/CameraVideoRecordingSystem.java"));
		String server = Files.readString(project.resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));
		String payloads = Files.readString(project.resolve("src/main/java/com/lostglade/network/RendererBotPayloads.java"));
		String client = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotClientVideoRecording.java"));
		String maps = Files.readString(project.resolve("src/main/java/com/lostglade/server/map/MapImageRenderSystem.java"));
		int lostCameraCheck = recording.indexOf("boolean issuedVideoReachable = isIssuedVideoStillReachable(server, player, recording.issuedVideoData())");
		int replayCooldown = recording.indexOf("if (recording.stopRequested())");
		require(lostCameraCheck >= 0 && replayCooldown > lostCameraCheck && recording.contains("isIssuedVideoStillReachable") && recording.contains("owner.containerMenu.getCarried()") && recording.contains("sourceKey.equals(candidate.sourceKey())") && recording.contains("frameData.asPhotoPrintData()") && recording.contains("ISSUED_VIDEO_MISSING_CANCEL_TICKS") && recording.contains("noteIssuedVideoMissing()") && recording.contains("requestStop(server, recording, true)") && recording.contains("truncateVideoRecording(server, recording.requestId())") && recording.contains("CameraCaptureSystem.clearRenderCooldown(player)"), "the issued video print must own both live and deferred rendering; sustained loss must finalize its rendered prefix and release the cooldown");
		require(server.contains("public static void cancelVideoRecording") && server.contains("RendererBotVideoRecordingCancelS2CPayload") && server.contains("failVideoRecording(requestId, recording"), "video cancellation must stop both server replay scheduling and the volunteer renderer request");
		require(payloads.contains("RendererBotVideoRecordingCancelS2CPayload") && payloads.contains("RendererBotVideoRecordingTruncateS2CPayload") && payloads.contains("PROTOCOL_VERSION = 40") && client.contains("RendererBotVideoRecordingCancelS2CPayload.TYPE") && client.contains("private static void cancelRecording(UUID requestId)"), "video cancellation and prefix finalization must be negotiated server-to-renderer paths");
		require(maps.contains("PENDING_CAMERA_COOLDOWN_CLEARS.add(playerId)") && maps.contains("CameraCaptureSystem.clearRenderCooldown(player)"), "every finished, failed, or discarded photo job must release its camera cooldown");
	}

	private static void videoFrameTimelineRestoresHistoricShadowState() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String shadow = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotShadowWorldManager.java"));
		String video = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotClientVideoRecording.java"));
		String server = Files.readString(project.resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));
		require(video.contains("captureVideoFrameSnapshot") && video.contains("restoreVideoFrameSnapshot"), "video frames must capture and restore their own shadow-world state instead of rendering the final live world");
		require(shadow.contains("class VideoShadowTimeline") && shadow.contains("Delta-compressed timeline") && shadow.contains("timeline.putChunk") && shadow.contains("timeline.appendPackets") && shadow.contains("setReplayLocked(true)"), "the shadow timeline must retain chunk deltas and entity/transient packets while isolating the replay scene from live updates");
		require(shadow.contains("applyState(restored.level(), snapshot.gameTime()") && shadow.contains("applyShadowPackets(client, restored, timeline.packetsThrough") && shadow.contains("materializeChunks(snapshot.chunkNode())"), "restoring a video frame must rebuild terrain, time/weather, and tracked entity packets from its historic snapshot");
		require(shadow.contains("chunkChangesBetween(previous.chunkNode(), snapshot.chunkNode())") && shadow.contains("packetsBetween(previous.packetCount(), snapshot.packetCount())") && shadow.contains("current.replayLocked()"), "successive video frames must advance one restored shadow scene through deltas rather than recompiling every terrain section from zero");
		require(video.contains("RendererBotVideoRecordingFrameSnapshotS2CPayload.TYPE") && video.contains("captureTimelineFrame") && server.contains("sendRecordedVideoTimelineFrame") && server.indexOf("syncShadowEntities(bot, desiredState, activeState);") < server.indexOf("sendRecordedVideoTimelineFrame(bot, key, activeState, desiredState.level());"), "a video frame must be sealed only after its entity and world packets, so player state cannot race the camera pose");
	}

	private static void videoRenderSurvivesGracefulServerRestart() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String server = Files.readString(project.resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));
		String recording = Files.readString(project.resolve("src/main/java/com/lostglade/server/CameraVideoRecordingSystem.java"));
		String client = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotClientVideoRecording.java"));
		String shadow = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotShadowWorldManager.java"));
		require(server.contains("video-recovery.json") || Files.readString(project.resolve("src/main/java/com/lostglade/server/CameraMediaCache.java")).contains("videoRecoveryPath"), "unfinished video work must have a durable journal");
		require(server.contains("loadVideoRecovery(server)") && server.contains("saveVideoRecovery(true)") && server.contains("serverStopping"), "server shutdown must persist videos rather than completing their futures as failed");
		require(recording.contains("ensureRecoveredRecordings") && recording.contains("attachCompletion"), "the restored renderer future must still complete the already-issued video print");
		require(client.contains("pauseForServerReconnect") && client.contains("retainsShadowSession") && client.contains("existing.completedFrameCount()") && client.contains("markFinalized") && client.contains("completed encoder is still a pending camera transaction"), "the renderer must retain its encoder, preview, and completed MP4 until a reconnected server accepts them");
		require(shadow.contains("clearUnretainedAfterJoin") && shadow.contains("retainsShadowSession(entry.getKey())"), "only a resumable video's frozen shadow session may survive reconnect");
		require(client.contains("ensureVideoTimeline") && shadow.contains("writeCheckpoint") && server.contains("acceptResumeCursor"), "a restarted renderer must restore historical snapshots and negotiate its encoder cursor");
	}

	private static void videoCompletionIsAcknowledgedBeforeRendererCleanup() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String payloads = Files.readString(project.resolve("src/main/java/com/lostglade/network/RendererBotPayloads.java"));
		String server = Files.readString(project.resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));
		String recording = Files.readString(project.resolve("src/main/java/com/lostglade/server/CameraVideoRecordingSystem.java"));
		String client = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotClientVideoRecording.java"));
		require(payloads.contains("RendererBotVideoRecordingCompleteAckS2CPayload") && payloads.contains("PROTOCOL_VERSION = 40"), "the completion acknowledgement must be negotiated with the renderer protocol");
		require(server.contains("COMPLETED_VIDEO_RECEIPTS") && server.contains("acknowledgeHandledVideoRecording") && server.contains("resendCompletedVideoAckIfKnown"), "the server must remember handled completions and acknowledge retried terminal packets");
		require(recording.contains("acknowledgeHandledVideoRecording(server, state.requestId())"), "the server must acknowledge only after the issued video print reaches a terminal state");
		require(client.contains("RendererBotVideoRecordingCompleteAckS2CPayload.TYPE") && client.contains("shouldRetryCompletion") && client.contains("acknowledgeCompletedRecording") && !client.contains("sendCompletedRecording(current, current.completedDurationMs(), current.completedFps())) {\n\t\t\t\t\tclearIfMatching"), "the renderer must retain a finalized MP4 until the server confirms it");
	}

	private static void videoTerminalFrameCannotRemainStuck() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String client = Files.readString(project.resolve("src/client/java/com/lostglade/client/RendererBotClientVideoRecording.java"));
		String server = Files.readString(project.resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));
		require(client.contains("REPLAY_FRAME_STALL_TIMEOUT_NANOS") && client.contains("clearFrameAttemptIfStalled") && client.contains("activeFrameAttempt") && client.contains("finishFrameAttempt(frameAttempt)"), "a stranded asynchronous last-frame callback must be invalidated and retried without encoding a duplicate frame");
		require(client.contains("sendReplayProgress(current.payload().requestId(), current.completedFrameCount())") && server.contains("recording.advanceReplayFrame(recording.cameraFrames().size())") && server.contains("notifyRenderingProgress(server, payload.requestId(), recording.cameraFrames().size())"), "both final completion routes must authoritatively advance the print to its final replay boundary");
	}

	private static void photoWarmupDoesNotMonopolizeLiveStreams() throws Exception {
		boolean previousWasPhoto = false;
		int photos = 0;
		int videos = 0;
		for (int frame = 0; frame < 100; frame++) {
			boolean photo = CameraCaptureReadinessPolicy.choosePhoto(true, true, previousWasPhoto);
			if (photo) photos++; else videos++;
			previousWasPhoto = photo;
		}
		require(photos == 50 && videos == 50, "continuous video and photo contention must not starve either class");
		require(CameraCaptureReadinessPolicy.choosePhoto(true, false, true), "an uncontended photo must not wait for a video turn");
		require(!CameraCaptureReadinessPolicy.choosePhoto(false, true, false), "video must run when photo is not due");
		String client = Files.readString(Path.of("").toAbsolutePath().resolve("src/client/java/com/lostglade/client/RendererBotClientCapture.java"));
		require(client.contains("PHOTO_WARMUP_INTERVAL_NANOS = 100_000_000L") && client.contains("capture.canScheduleRender(nowNanos)"), "photo warm-up renders must be bounded instead of consuming every client tick");
		require(client.contains("activeCaptures < LostgladeClientSettings.maxParallelCaptures()") && client.contains("if (!RendererBotClientVideoRecording.hasActiveRecording())") && client.contains("CameraCaptureReadinessPolicy.choosePhoto("), "photo and live stream scheduling must use the fair selection policy");
		require(client.contains("!readiness.settled()") && client.contains("stableVisibleSections != readiness.visibleSections()") && client.contains("CAPTURE_REQUIRED_SETTLED_RENDERS = Math.max(2"), "a still must finish only after received content and the visible terrain section count stabilize");
		require(client.contains("FIRST_CAPTURE_RENDER_TIMEOUT_MS") && client.contains("!capture.hasRenderedFrame()") && client.contains("capture.markRenderedFrame()"), "terrain loading must not expire after the renderer has begun producing photo frames");
		String readiness = Files.readString(Path.of("").toAbsolutePath().resolve("src/client/java/com/lostglade/client/RendererBotShadowWorldManager.java"));
		require(readiness.contains("CameraCaptureReadinessPolicy.isUsable(contentReady, currentContentRendered, visibleSections, dirtyVisibleSections)") && readiness.contains("section.isDirty()"), "the final still barrier must wait only for dirty sections in the current camera view");
	}

	private static void shadowPhotoSessionsFreezeDynamicWorldState() throws Exception {
		String server = Files.readString(Path.of("").toAbsolutePath().resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));
		String client = Files.readString(Path.of("").toAbsolutePath().resolve("src/client/java/com/lostglade/client/RendererBotShadowWorldManager.java"));
		require(server.contains("snapshotState.markDynamicContentFrozen()"), "still captures must explicitly opt into a frozen shadow session");
		require(server.contains("activeState.freezeDynamicContent() && activeState.lastGameTime() != Long.MIN_VALUE"), "time and weather must be sent only once to a frozen photo session");
		require(server.contains("activeState.freezeDynamicContent() && activeState.entitySnapshotSent()"), "entities must be paired once and then stop moving in a photo session");
		require(server.contains("!activeState.freezeDynamicContent() && DIRTY_SHADOW_CHUNKS.contains") && server.contains("activeState == null || activeState.freezeDynamicContent()"), "dynamic block and transient packets must not mutate the frozen photo scene");
		require(server.contains("desiredState.freezeDynamicContent()") && client.contains("if (!session.freezeDynamicContent())") && client.contains("payload.freezeDynamicContent()"), "the client shadow scene must freeze entity, block, particle, and animate ticks as well as the server stream");
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
		require(client.contains("RendererBotPhotoRenderProgressC2SPayload") && client.contains("estimateTerrainProgress(readiness)") && client.contains("readyForFinalCapture ? 99") && client.contains("Math.min(94, progress)") && maps.contains("notifyPhotoRenderProgress") && maps.contains("rendererProgressPercent"), "a whole-frame photo must report visible-terrain loading rather than claiming completion before its stable final capture");
		require(server.contains("applyInitialPreviewTimeout") && !server.contains("capture.fullFuture()\n\t\t\t\t.orTimeout"), "server must time out only an unresponsive renderer, never a compiling full photo");
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
		require(maps.contains("isPhotoStillReachable(server, player, job.photoData())") && maps.contains("removed.provider().onCancelled(removed.server);"), "every removal path must release the photo's renderer request");
		require(maps.contains("frameData.samePhoto(photoData)") && maps.contains("stackData.samePhoto(photoData)"), "a print must remain valid only in the author's inventory or an item frame");
		require(maps.contains("owner.containerMenu.getCarried()") && maps.contains("owner.inventoryMenu.getCarried()"), "moving a print between inventory slots must retain its active render while it is on the player's cursor");
		require(provider.contains("RendererBotCameraSystem.cancelCapture(this.captureHandle.requestId())"), "camera provider cancellation must reach the renderer request");
		require(server.contains("public static void cancelCapture(UUID requestId)") && server.contains("RendererBotCaptureCancelS2CPayload"), "server cancellation must remove its pending frame and notify the renderer");
		require(payloads.contains("RendererBotCaptureCancelS2CPayload") && payloads.contains("PROTOCOL_VERSION = 40"), "capture cancellation must be part of the negotiated renderer protocol");
		require(client.contains("RendererBotCaptureCancelS2CPayload.TYPE") && client.contains("clearPendingCapture(payload.requestId())"), "renderer cancellation must release pending GPU work");
	}

	private static void placedCameraUsesLegacyPoseWithoutChangingHandheldPose() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String item = Files.readString(project.resolve("src/main/java/com/lostglade/item/CameraItem.java"));
		String display = Files.readString(project.resolve("src/main/java/com/lostglade/block/CameraDisplayHelper.java"));
		String handheldModel = Files.readString(project.resolve("src/main/resources/assets/lg2/models/item/camera.json"));
		String placedModel = Files.readString(project.resolve("src/main/resources/assets/lg2/models/item/camera_placed.json"));
		require(item.contains("PLACED_MODEL_ID") && item.contains("DISPLAY_ONLY_TAG") && item.contains("isPlacedDisplayStack(itemStack)"), "the placed display must select its own model instead of changing every camera stack");
		require(display.contains("float displayYaw = yaw + 180.0F;") && display.contains("display.setXRot(-pitch);"), "placed camera orientation must retain the 535c4abb yaw and pitch formulas");
		require(placedModel.contains("\"parent\": \"lg2:item/camera\"") && placedModel.contains("\"rotation\": [0, -180, 0]"), "placed camera must inherit the canonical mesh with the original fixed transform");
		require(handheldModel.contains("\"translation\": [-2.75, 2, 0]") && handheldModel.contains("\"scale\": [0.65, 0.65, 0.65]"), "the current handheld camera pose must remain untouched");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
