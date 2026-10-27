package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.block.ModBlocks;
import com.lostglade.item.CameraPhotoSettings;
import com.lostglade.item.ModItems;
import com.lostglade.item.PhotoPrintData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class CameraVideoRecordingSystem {
	private static final int MAX_TARGET_FPS = 20;
	private static final int TARGET_FPS = 10;
	private static final int MAX_DURATION_SECONDS = 10 * 60;
	private static final long MIN_STOP_DELAY_MS = 1_000L;
	private static final int AUDIO_SAMPLE_RATE = 48_000;
	// Slot transfers and multi-frame placement are observed over several server
	// ticks.  A single transient miss must never cancel a valid issued video.
	private static final int ISSUED_VIDEO_MISSING_CANCEL_TICKS = 20;
	private static final Map<UUID, ActiveRecording> RECORDINGS_BY_PLAYER = new ConcurrentHashMap<>();

	private CameraVideoRecordingSystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(CameraVideoRecordingSystem::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			// The authoritative renderer journal survives shutdown, but these
			// callbacks belong to the old server instance (also in singleplayer).
			for (ActiveRecording recording : RECORDINGS_BY_PLAYER.values()) recording.abortAudioCapture();
			RECORDINGS_BY_PLAYER.clear();
		});
		// RendererBotCameraSystem journals unfinished work independently; item-side
		// callbacks are rebuilt once the owner logs back in.
	}

	public static boolean isRecording(UUID playerId) {
		return playerId != null && RECORDINGS_BY_PLAYER.containsKey(playerId);
	}

	public static boolean hasAnyRecording() {
		return !RECORDINGS_BY_PLAYER.isEmpty();
	}

	public static boolean toggleRecording(ServerPlayer player, CameraPhotoSettings settings) {
		if (player == null || settings == null) {
			return false;
		}
		if (DroneSystem.isCameraBlockedByDroneControl(player)) {
			return false;
		}
		ActiveRecording current = RECORDINGS_BY_PLAYER.get(player.getUUID());
		if (current != null) {
			if (current.stopRequested()) {
				Lg2Messages.actionBar(player, "message.lg2.camera.video.stopping");
				return true;
			}
			long elapsedMs = System.currentTimeMillis() - current.startedAtMs();
			if (elapsedMs < MIN_STOP_DELAY_MS) {
				Lg2Messages.actionBar(player, "message.lg2.camera.video.starting");
				return true;
			}
			requestStop(player.level().getServer(), current, true);
			return true;
		}

		RendererBotCameraSystem.VideoRecordingHandle handle = RendererBotCameraSystem.startVideoRecording(
				player,
				settings.mapsWide(),
				settings.mapsHigh(),
				Math.min(MAX_TARGET_FPS, TARGET_FPS),
				MAX_DURATION_SECONDS
		);
		if (handle == null) {
			Lg2Messages.actionBar(player, "message.lg2.camera.video.no_renderer");
			return false;
		}
		ActiveRecording state = new ActiveRecording(
				player.getUUID(),
				handle.requestId(),
				(ServerLevel) player.level(),
				player.position().x,
				player.position().y,
				player.position().z,
				settings.mapsWide(),
				settings.mapsHigh(),
				System.currentTimeMillis()
		);
		ItemStack pendingVideo = CameraAnimatedMapPlaybackSystem.createVideoItem(
				player,
				CameraCaptureSystem.createQueuedPhotoName(0),
				settings.mapsWide(),
				settings.mapsHigh(),
				handle.requestId().toString(),
				0L,
				TARGET_FPS,
				null,
				null
		);
		if (!pendingVideo.isEmpty()) {
			state.setIssuedVideoData(PhotoPrintData.readPhotoItem(pendingVideo));
			giveOrDrop(player, pendingVideo);
			ServerMechanicsGateSystem.syncPlayerInventory(player);
		}
		RECORDINGS_BY_PLAYER.put(player.getUUID(), state);
		state.markStartedFeedbackSent();
		state.startAudioCapture(player);
		RendererBotCameraSystem.recordVideoCameraPose(handle.requestId(), player);
		attachCompletion(player.level().getServer(), state, handle.completionFuture());
		// The volunteer renderer receives and renders the path immediately. It is
		// never the operator's client, so this does not consume operator FPS.
		Lg2Messages.actionBar(player, "message.lg2.camera.video.started");
		CameraCaptureSystem.notifyShutterCaptured(player);
		return true;
	}

	/**
	 * Renderer-bot confirmation that an actual video frame has been captured and
	 * written. This is deliberately separate from creating the recording request.
	 */
	public static void notifyRecordingStarted(MinecraftServer server, UUID requestId) {
		if (server == null || requestId == null) {
			return;
		}
		for (ActiveRecording recording : RECORDINGS_BY_PLAYER.values()) {
			if (recording == null || !requestId.equals(recording.requestId()) || !recording.markStartedFeedbackSent()) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(recording.playerId());
			if (player != null) {
				recording.startAudioCapture(player);
				Lg2Messages.actionBar(player, "message.lg2.camera.video.started");
				CameraCaptureSystem.notifyShutterCaptured(player);
			}
			return;
		}
	}

	public static void stopForDroneControl(ServerPlayer player) {
		if (player == null) {
			return;
		}
		ActiveRecording current = RECORDINGS_BY_PLAYER.get(player.getUUID());
		if (current == null) {
			return;
		}
		MinecraftServer server = player.level() == null ? null : player.level().getServer();
		if (server == null) {
			return;
		}
		requestStop(server, current, false);
	}

	private static void tick(MinecraftServer server) {
		if (server == null) {
			return;
		}
		ensureRecoveredRecordings(server);
		if (RECORDINGS_BY_PLAYER.isEmpty()) return;
		for (ActiveRecording recording : RECORDINGS_BY_PLAYER.values()) {
			if (recording == null) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(recording.playerId());
			if (recording.truncationRequested()) continue;
			if (player == null) {
				// Disconnect is not proof the print was discarded. Keep the job
				// recoverable and seal the path already recorded.
				if (!recording.stopRequested()) requestStop(server, recording, false);
				continue;
			}
			// The emitted video print, not the camera item, owns the work. This is
			// deliberately identical to a photo: moving the print to a chest or
			// throwing it away cancels, while putting the camera away does not.
			boolean issuedVideoReachable = isIssuedVideoStillReachable(server, player, recording.issuedVideoData());
			if (!issuedVideoReachable && !recording.truncationRequested() && recording.noteIssuedVideoMissing()) {
				cancelRecording(server, recording, player, "video print absent from inventory, cursor and loaded item frames for 20 ticks");
				continue;
			}
			if (issuedVideoReachable) {
				recording.clearIssuedVideoMissing();
			}
			if (recording.stopRequested()) {
				// A deliberate print discard keeps only the already rendered prefix;
				// its camera must become usable immediately, not receive a fresh
				// cooldown on every server tick while the short MP4 is closing.
				if (!recording.truncationRequested()) {
					applyRenderingCooldown(player);
				}
				continue;
			}
			if (!player.isAlive()) {
				cancelRecording(server, recording, player, "owner died while recording");
				continue;
			}
			if (DroneSystem.isCameraBlockedByDroneControl(player) || !isHoldingCameraInAnyHand(player)) {
				// Keep the video print and render the path captured so far. The
				// renderer remains alive while that print stays reachable.
				requestStop(server, recording, true);
				continue;
			}
			RendererBotCameraSystem.recordVideoCameraPose(recording.requestId(), player);
		}
	}

	private static void ensureRecoveredRecordings(MinecraftServer server) {
		for (RendererBotCameraSystem.RecoveredVideoRecording recovery : RendererBotCameraSystem.recoveredVideoRecordings()) {
			if (recovery == null || recovery.ownerId() == null || RECORDINGS_BY_PLAYER.containsKey(recovery.ownerId())) continue;
			ServerPlayer player = server.getPlayerList().getPlayer(recovery.ownerId());
			ServerLevel level = recovery.dimension() == null ? null : server.getLevel(recovery.dimension());
			if (player == null || level == null) continue;
			PhotoPrintData issued = findIssuedVideoData(server, player, recovery.requestId().toString());
			if (issued == null) {
				// The print is the owner of asynchronous work. Do not resurrect an
				// abandoned job merely because its old journal still exists.
				RendererBotCameraSystem.cancelVideoRecording(server, recovery.requestId(), "Camera video print is no longer available after restart");
				continue;
			}
			ActiveRecording state = new ActiveRecording(
					player.getUUID(), recovery.requestId(), level, player.getX(), player.getY(), player.getZ(),
					recovery.mapsWide(), recovery.mapsHigh(), System.currentTimeMillis()
			);
			state.setIssuedVideoData(issued);
			CameraAnimatedMapPlaybackSystem.registerPendingVideoMaps(issued);
			state.markStopRequested();
			state.beginRenderProgress(Math.max(1, recovery.totalFrames()));
			int restoredProgress = state.recordRenderedFrames(recovery.completedFrames());
			RECORDINGS_BY_PLAYER.put(player.getUUID(), state);
			attachCompletion(server, state, recovery.completionFuture());
			updateIssuedVideoTitle(player, state, Math.max(0, restoredProgress));
			applyRenderingCooldown(player);
			Lg2.LOGGER.info("Resumed unfinished camera-video render {} for {} after server restart", recovery.requestId(), player.getScoreboardName());
		}
	}

	private static void attachCompletion(
			MinecraftServer server,
			ActiveRecording state,
			java.util.concurrent.CompletableFuture<RendererBotCameraSystem.VideoRecordingResult> completion
	) {
		if (server == null || state == null || completion == null) return;
		completion.whenComplete((result, throwable) -> server.execute(() -> finishRecording(server, state, result, throwable)));
	}

	private static PhotoPrintData findIssuedVideoData(MinecraftServer server, ServerPlayer owner, String sourceKey) {
		if (server == null || owner == null || sourceKey == null || sourceKey.isBlank()) return null;
		for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
			PhotoPrintData data = PhotoPrintData.readPhotoItem(owner.getInventory().getItem(slot));
			if (data != null && data.isVideo() && sourceKey.equals(data.sourceKey())) return data;
		}
		for (ServerLevel level : server.getAllLevels()) {
			for (var entity : level.getAllEntities()) {
				if (!(entity instanceof net.minecraft.world.entity.decoration.ItemFrame frame) || frame.isRemoved()) continue;
				PhotoPrintData.PlacedPhotoFrameData data = PhotoPrintData.readFrameTile(frame.getItem());
				if (data != null && data.mediaKind() == PhotoPrintData.MediaKind.VIDEO && sourceKey.equals(data.sourceKey())) return data.asPhotoPrintData();
			}
		}
		return null;
	}

	/** Cancels only after the issued video print leaves its owner's possession. */
	private static void cancelRecording(MinecraftServer server, ActiveRecording recording, ServerPlayer player, String reason) {
		if (server == null || recording == null) {
			return;
		}
		recording.cancellationReason = reason;
		recording.markTruncationRequested();
		recording.markStopRequested();
		recording.abortAudioCapture();
		if (player != null) {
			// The thrown print is the finished artifact. Remove its percent title
			// immediately; completion will populate the same map ids with the short MP4.
			updateIssuedVideoTitle(player, recording, 100);
			CameraCaptureSystem.clearRenderCooldown(player);
		}
		RendererBotCameraSystem.truncateVideoRecording(server, recording.requestId());
	}

	private static void requestStop(MinecraftServer server, ActiveRecording recording, boolean notifyPlayer) {
		if (server == null || recording == null || recording.stopRequested()) {
			return;
		}
		recording.markStopRequested();
		ServerPlayer player = server.getPlayerList().getPlayer(recording.playerId());
		if (player != null) {
			RendererBotCameraSystem.finishVideoCameraPath(recording.requestId(), player);
			recording.beginRenderProgress(RendererBotCameraSystem.videoFrameCount(recording.requestId()));
			updateIssuedVideoTitle(player, recording, 0);
			applyRenderingCooldown(player);
		}
		RendererBotCameraSystem.stopVideoRecording(server, recording.requestId());
		if (notifyPlayer) {
			if (player != null) {
				Lg2Messages.actionBar(player, "message.lg2.camera.video.stopping");
			}
		}
	}

	private static void applyRenderingCooldown(ServerPlayer player) {
		CameraCaptureSystem.updateRenderCooldown(player);
	}

	/** Called by the renderer after each encoded pose; the issued print keeps a real progress title. */
	public static void notifyRenderingProgress(MinecraftServer server, UUID requestId, int completedFrames) {
		if (server == null || requestId == null) {
			return;
		}
		for (ActiveRecording recording : RECORDINGS_BY_PLAYER.values()) {
			if (recording == null || !requestId.equals(recording.requestId()) || !recording.stopRequested() || recording.truncationRequested()) {
				continue;
			}
			int progress = recording.recordRenderedFrames(completedFrames);
			ServerPlayer player = server.getPlayerList().getPlayer(recording.playerId());
			if (player != null && progress >= 0) {
				updateIssuedVideoTitle(player, recording, progress);
				applyRenderingCooldown(player);
			}
			return;
		}
	}

	public static void notifyRenderedPreview(MinecraftServer server, UUID requestId, byte[] previewPixels) {
		if (server == null || requestId == null || previewPixels == null || previewPixels.length != 128 * 128) {
			return;
		}
		CameraAnimatedMapPlaybackSystem.appendPendingVideoPreview(requestId.toString(), previewPixels);
	}

	/** Stores each completed video frame for cyclic playback before the MP4 closes. */
	public static void notifyRenderedFrame(MinecraftServer server, UUID requestId, byte[] previewPixels, byte[] fullPixels) {
		if (server == null || requestId == null || previewPixels == null || fullPixels == null || previewPixels.length != 128 * 128) return;
		CameraAnimatedMapPlaybackSystem.appendPendingVideoFrame(requestId.toString(), previewPixels, fullPixels);
	}

	private static void updateIssuedVideoTitle(ServerPlayer player, ActiveRecording recording, int progressPercent) {
		if (player == null || recording == null) {
			return;
		}
		int progress = Math.clamp(progressPercent, 0, 100);
		Component title = progress >= 100
				? CameraCaptureSystem.createCompletedPhotoName(player.level().getServer())
				: CameraCaptureSystem.createQueuedPhotoName(progress);
		String sourceKey = recording.requestId().toString();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			PhotoPrintData data = PhotoPrintData.readPhotoItem(stack);
			if (data != null && data.isVideo() && sourceKey.equals(data.sourceKey())) {
				stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, title.copy());
			}
		}
		// A creative frame copy has its own saved name. Updating the inventory
		// alone leaves it displaying the placement percentage even after success.
		for (ServerLevel level : player.level().getServer().getAllLevels()) {
			for (var entity : level.getAllEntities()) {
				if (entity instanceof ItemEntity itemEntity && isMatchingIssuedVideo(itemEntity.getItem(), recording.issuedVideoData())) {
					itemEntity.getItem().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, title.copy());
				}
				if (!(entity instanceof net.minecraft.world.entity.decoration.ItemFrame frame) || frame.isRemoved()) continue;
				ItemStack item = frame.getItem();
				var data = PhotoPrintData.readFrameTile(item);
				var id = item.get(net.minecraft.core.component.DataComponents.MAP_ID);
				PhotoPrintData issued = recording.issuedVideoData();
				if ((data != null && sourceKey.equals(data.sourceKey()))
						|| (id != null && issued != null && issued.ownsMapId(id.id()))) {
					ItemStack updated = item.copy();
					updated.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, title.copy());
					if (data != null) PhotoPrintData.writeFrameStoredName(updated, title);
					frame.setItem(updated, false);
				}
			}
		}
	}

	private static boolean isHoldingCameraInAnyHand(ServerPlayer player) {
		if (player == null) {
			return false;
		}
		return player.getMainHandItem().is(ModItems.CAMERA) || player.getOffhandItem().is(ModItems.CAMERA);
	}

	/** Matches the ownership rule for ordinary photo rendering. */
	private static boolean isIssuedVideoStillReachable(MinecraftServer server, ServerPlayer owner, PhotoPrintData videoData) {
		// Keep the legacy completion fallback alive if an item could not be issued.
		if (videoData == null) {
			return true;
		}
		if (server == null || owner == null || !videoData.isVideo()) {
			return false;
		}
		// Check hands explicitly as well as inventory slots: creative placement
		// deliberately leaves the source print in the hand while the framed tiles
		// are a second valid owner of the same render job.
		if (isMatchingIssuedVideo(owner.getMainHandItem(), videoData)
				|| isMatchingIssuedVideo(owner.getOffhandItem(), videoData)
				// Moving the print between slots briefly holds it on the menu cursor.
				|| isMatchingIssuedVideo(owner.containerMenu.getCarried(), videoData)
				|| isMatchingIssuedVideo(owner.inventoryMenu.getCarried(), videoData)) {
			return true;
		}
		for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
			if (isMatchingIssuedVideo(owner.getInventory().getItem(slot), videoData)) {
				return true;
			}
		}
		for (ServerLevel level : server.getAllLevels()) {
			for (var entity : level.getAllEntities()) {
				if (!(entity instanceof net.minecraft.world.entity.decoration.ItemFrame frame) || frame.isRemoved()) continue;
				if (isMatchingIssuedVideo(frame.getItem(), videoData)) return true;
				PhotoPrintData.PlacedPhotoFrameData frameData = PhotoPrintData.readFrameTile(frame.getItem());
				// The frame contains map tiles rather than the original PHOTO_PRINT.
				// Its immutable source key is therefore the authoritative ownership
				// identity; map ids remain a backwards-compatible fallback for old
				// prints that predate source keys.
				if (frameData != null && isMatchingIssuedVideo(frameData.asPhotoPrintData(), videoData)) {
					return true;
				}
				// Creative pick-block/vanilla placement can store a plain map,
				// without lg2_photo_frame. The actual map still belongs to this print.
				var mapId = frame.getItem().get(net.minecraft.core.component.DataComponents.MAP_ID);
				if (mapId != null && videoData.ownsMapId(mapId.id())) return true;
			}
		}
		return false;
	}

	private static boolean isMatchingIssuedVideo(ItemStack stack, PhotoPrintData videoData) {
		return isMatchingIssuedVideo(PhotoPrintData.readPhotoItem(stack), videoData);
	}

	private static boolean isMatchingIssuedVideo(PhotoPrintData candidate, PhotoPrintData issuedVideo) {
		if (candidate == null || issuedVideo == null || !candidate.isVideo()) {
			return false;
		}
		String sourceKey = issuedVideo.sourceKey();
		return (!sourceKey.isBlank() && sourceKey.equals(candidate.sourceKey()))
				|| candidate.samePhoto(issuedVideo);
	}

	private static boolean isHoldingCameraWithMicrophoneInOtherHand(ServerPlayer player) {
		if (player == null) {
			return false;
		}
		ItemStack mainHand = player.getMainHandItem();
		ItemStack offHand = player.getOffhandItem();
		return mainHand.is(ModItems.CAMERA) && offHand.is(ModBlocks.MICROPHONE_ITEM)
				|| offHand.is(ModItems.CAMERA) && mainHand.is(ModBlocks.MICROPHONE_ITEM);
	}

	private static void finishRecording(
			MinecraftServer server,
			ActiveRecording state,
			RendererBotCameraSystem.VideoRecordingResult result,
			Throwable throwable
	) {
		if (state == null) {
			return;
		}
		RECORDINGS_BY_PLAYER.remove(state.playerId(), state);
		ServerPlayer player = server.getPlayerList().getPlayer(state.playerId());
		if (throwable != null || result == null) {
			state.abortAudioCapture();
			if (state.cancellationReason != null) {
				PhotoPrintData issued = state.issuedVideoData();
				Lg2.LOGGER.info("Camera video {} cancelled for {}: {}; rendered={}/{}, previewMap={}, tileMaps={}",
						state.requestId(), state.playerId(), state.cancellationReason,
						state.renderedFrames, state.renderFrameTotal,
						issued == null ? -1 : issued.previewMapId(),
						issued == null ? "[]" : java.util.Arrays.toString(issued.mapIds()));
				if (player != null) {
					CameraCaptureSystem.clearRenderCooldown(player);
					Lg2Messages.actionBar(player, "message.lg2.camera.video.cancelled");
				}
				RendererBotCameraSystem.acknowledgeHandledVideoRecording(server, state.requestId());
				return;
			}
			// Failed/cancelled renders intentionally retain their successfully
			// encoded prefix as a looping preview on the issued video print.
			String reason = throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()
					? "неизвестная причина"
					: throwable.getMessage();
			Lg2.LOGGER.warn("Camera video recording {} failed for {}: {}", state.requestId(), state.playerId(), reason, throwable);
			if (player != null) {
				CameraCaptureSystem.clearRenderCooldown(player);
				Lg2Messages.actionBar(player, Lg2Messages.tr("message.lg2.camera.video.failed")
						.append(Component.literal(" " + reason)));
			}
			RendererBotCameraSystem.acknowledgeHandledVideoRecording(server, state.requestId());
			return;
		}

		if (player == null) {
			state.abortAudioCapture();
			RendererBotCameraSystem.acknowledgeHandledVideoRecording(server, state.requestId());
			return;
		}
		Path finishedAudioPath;
		try {
			finishedAudioPath = state.finishAudioCapture();
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to finish hand camera audio file for {}", state.requestId(), exception);
			finishedAudioPath = null;
		}
		boolean audioMuxFailed = false;
		Path videoPath;
		try {
			videoPath = persistCompletedVideoFile(state.requestId().toString(), result.videoPath());
			if (finishedAudioPath != null) {
				audioMuxFailed = !muxAudioIntoVideo(videoPath, finishedAudioPath, state.requestId().toString());
			}
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to persist camera video file for {}", state.requestId(), exception);
			state.deleteAudioCapture();
			CameraCaptureSystem.clearRenderCooldown(player);
			Lg2Messages.actionBar(player, "message.lg2.camera.video.persist_failed");
			RendererBotCameraSystem.acknowledgeHandledVideoRecording(server, state.requestId());
			return;
		} finally {
			state.deleteAudioCapture();
		}

		PhotoPrintData issuedVideo = state.issuedVideoData();
		if (issuedVideo != null) {
			if (!CameraAnimatedMapPlaybackSystem.applyCompletedVideoMaps(player, issuedVideo, result.previewPixels(), result.fullPixels())) {
				CameraCaptureSystem.clearRenderCooldown(player);
				Lg2Messages.actionBar(player, "message.lg2.camera.video.map_failed");
				RendererBotCameraSystem.acknowledgeHandledVideoRecording(server, state.requestId());
				return;
			}
			CameraAnimatedMapPlaybackSystem.discardPendingVideoPreview(state.requestId().toString());
			updateIssuedVideoTitle(player, state, 100);
		} else {
			ItemStack videoItem = CameraAnimatedMapPlaybackSystem.createVideoItem(
					player,
					CameraCaptureSystem.createCompletedPhotoName(server),
					state.mapsWide(),
					state.mapsHigh(),
					state.requestId().toString(),
					result.durationMs(),
					Math.max(1, result.fps()),
					result.previewPixels(),
					result.fullPixels()
			);
			if (videoItem.isEmpty()) {
				CameraCaptureSystem.clearRenderCooldown(player);
				Lg2Messages.actionBar(player, "message.lg2.camera.video.map_failed");
				RendererBotCameraSystem.acknowledgeHandledVideoRecording(server, state.requestId());
				return;
			}
			giveOrDrop(player, videoItem);
		}
		ServerMechanicsGateSystem.syncPlayerInventory(player);
		CameraCaptureSystem.clearRenderCooldown(player);
		Lg2Messages.actionBar(
				player,
				audioMuxFailed ? "message.lg2.camera.video.ready_audio_failed" : "message.lg2.camera.video.ready"
		);
		RendererBotCameraSystem.acknowledgeHandledVideoRecording(server, state.requestId());
	}

	private static Path persistCompletedVideoFile(String sourceKey, String rawSourcePath) throws IOException {
		if (sourceKey == null || sourceKey.isBlank()) {
			throw new IOException("Video source key is missing");
		}
		CameraMediaCache.ensureVideoParent(sourceKey);
		Path target = CameraMediaCache.videoSourcePath(sourceKey);
		Path source = rawSourcePath == null || rawSourcePath.isBlank()
				? target
				: Path.of(rawSourcePath).toAbsolutePath().normalize();
		if (!Files.isRegularFile(source)) {
			throw new IOException("Renderer video file is missing: " + source);
		}
		if (source.equals(target.toAbsolutePath().normalize())) {
			if (Files.size(target) <= 0L) {
				throw new IOException("Renderer video file is empty");
			}
			return target;
		}
		Path temp = CameraMediaCache.tempVideoSourcePath(sourceKey);
		Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
		if (Files.size(temp) <= 0L) {
			Files.deleteIfExists(temp);
			throw new IOException("Renderer video file is empty");
		}
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException ignored) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return target;
	}

	private static boolean muxAudioIntoVideo(Path videoPath, Path audioPath, String sourceKey) throws IOException {
		if (videoPath == null || audioPath == null || !Files.isRegularFile(audioPath)) {
			return false;
		}
		if (!Files.isRegularFile(videoPath) || Files.size(videoPath) <= 0L) {
			throw new IOException("Video output is empty");
		}
		if (Files.size(audioPath) <= 44L) {
			return false;
		}
		Path muxPath = videoPath.resolveSibling(videoPath.getFileName().toString() + ".mux.mp4");
		Files.deleteIfExists(muxPath);
		List<String> command = List.of(
				"ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
				"-i", videoPath.toAbsolutePath().toString(),
				"-i", audioPath.toAbsolutePath().toString(),
				"-map", "0:v:0",
				"-map", "1:a:0",
				"-c:v", "copy",
				"-c:a", "aac",
				"-af", "apad",
				"-shortest",
				"-movflags", "+faststart",
				muxPath.toAbsolutePath().toString()
		);
		Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try {
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				Lg2.LOGGER.warn("Hand camera audio mux timed out for {}", sourceKey);
				deleteQuietly(muxPath);
				return false;
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			deleteQuietly(muxPath);
			throw new IOException("Hand camera audio mux interrupted", exception);
		}
		if (process.exitValue() != 0 || !Files.isRegularFile(muxPath) || Files.size(muxPath) <= 0L) {
			deleteQuietly(muxPath);
			return false;
		}
		replaceOutput(muxPath, videoPath);
		return true;
	}

	private static void replaceOutput(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException ignored) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}

	private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty()) {
			return;
		}
		boolean inserted = player.getInventory().add(stack);
		if (!inserted) {
			ItemEntity itemEntity = player.drop(stack, false);
			if (itemEntity != null) {
				itemEntity.setPickUpDelay(0);
			}
		}
	}

	private static final class ActiveRecording {
		private final UUID playerId;
		private final UUID requestId;
		private final ServerLevel level;
		private final double x;
		private final double y;
		private final double z;
		private final int mapsWide;
		private final int mapsHigh;
		private final long startedAtMs;
		private HandCameraAudioTrack audioTrack;
		private PhotoPrintData issuedVideoData;
		private volatile boolean stopRequested;
		private boolean startedFeedbackSent;
		private boolean truncationRequested;
		private String cancellationReason;
		private int consecutiveIssuedVideoMissingTicks;
		private int renderFrameTotal;
		private int renderedFrames;
		private int lastTitleProgress = -1;

		private ActiveRecording(
				UUID playerId,
				UUID requestId,
				ServerLevel level,
				double x,
				double y,
				double z,
				int mapsWide,
				int mapsHigh,
				long startedAtMs
		) {
			this.playerId = playerId;
			this.requestId = requestId;
			this.level = level;
			this.x = x;
			this.y = y;
			this.z = z;
			this.mapsWide = mapsWide;
			this.mapsHigh = mapsHigh;
			this.startedAtMs = startedAtMs;
			this.audioTrack = null;
			this.issuedVideoData = null;
			this.stopRequested = false;
			this.startedFeedbackSent = false;
			this.consecutiveIssuedVideoMissingTicks = 0;
			this.renderFrameTotal = 0;
			this.renderedFrames = 0;
		}

		private UUID playerId() {
			return this.playerId;
		}

		private UUID requestId() {
			return this.requestId;
		}

		private ServerLevel level() {
			return this.level;
		}

		private double x() {
			return this.x;
		}

		private double y() {
			return this.y;
		}

		private double z() {
			return this.z;
		}

		private int mapsWide() {
			return this.mapsWide;
		}

		private int mapsHigh() {
			return this.mapsHigh;
		}

		private long startedAtMs() {
			return this.startedAtMs;
		}

		private boolean stopRequested() {
			return this.stopRequested;
		}

		private void markStopRequested() {
			this.stopRequested = true;
		}

		private synchronized boolean truncationRequested() {
			return this.truncationRequested;
		}

		private synchronized void markTruncationRequested() {
			this.truncationRequested = true;
		}

		private synchronized void setIssuedVideoData(PhotoPrintData data) {
			this.issuedVideoData = data;
		}

		private synchronized PhotoPrintData issuedVideoData() {
			return this.issuedVideoData;
		}

		private synchronized void beginRenderProgress(int totalFrames) {
			this.renderFrameTotal = Math.max(1, totalFrames);
			this.renderedFrames = 0;
			this.lastTitleProgress = -1;
		}

		/** @return a changed percent, or -1 when the visible title does not need an update. */
		private synchronized int recordRenderedFrames(int completedFrames) {
			if (this.renderFrameTotal <= 0) {
				return -1;
			}
			this.renderedFrames = Math.clamp(completedFrames, 0, this.renderFrameTotal);
			int progress = (int) Math.floor(this.renderedFrames * 100.0D / this.renderFrameTotal);
			if (progress == this.lastTitleProgress) {
				return -1;
			}
			this.lastTitleProgress = progress;
			return progress;
		}

		private synchronized boolean markStartedFeedbackSent() {
			if (this.startedFeedbackSent) {
				return false;
			}
			this.startedFeedbackSent = true;
			return true;
		}

		/** @return true only after sustained absence, never during a slot/frame hand-off. */
		private synchronized boolean noteIssuedVideoMissing() {
			this.consecutiveIssuedVideoMissingTicks++;
			return this.consecutiveIssuedVideoMissingTicks >= ISSUED_VIDEO_MISSING_CANCEL_TICKS;
		}

		private synchronized void clearIssuedVideoMissing() {
			this.consecutiveIssuedVideoMissingTicks = 0;
		}

		private synchronized void startAudioCapture(ServerPlayer player) {
			if (this.audioTrack != null || !isHoldingCameraWithMicrophoneInOtherHand(player)) {
				return;
			}
			try {
				HandCameraAudioTrack track = HandCameraAudioTrack.create(this.requestId.toString());
				MicrophoneSystem.MicrophonePcmRecorder recorder = MicrophoneSystem.startPlayerPcmRecorder(player, track::writeFrame);
				if (recorder == null) {
					track.abortQuietly();
					return;
				}
				track.attach(recorder);
				this.audioTrack = track;
			} catch (Exception exception) {
				Lg2.LOGGER.warn("Failed to start hand camera audio recording for {}", this.playerId, exception);
			}
		}

		private Path finishAudioCapture() throws IOException {
			return this.audioTrack != null ? this.audioTrack.finish() : null;
		}

		private void abortAudioCapture() {
			if (this.audioTrack != null) {
				this.audioTrack.abortQuietly();
			}
		}

		private void deleteAudioCapture() {
			if (this.audioTrack != null) {
				this.audioTrack.deleteQuietly();
			}
		}
	}

	private static final class HandCameraAudioTrack {
		private final Path path;
		private final OutputStream output;
		private long samplesWritten;
		private MicrophoneSystem.MicrophonePcmRecorder recorder;
		private boolean closed;

		private HandCameraAudioTrack(Path path) throws IOException {
			this.path = path;
			this.output = new BufferedOutputStream(Files.newOutputStream(path));
			this.output.write(wavHeader(0L));
		}

		private static HandCameraAudioTrack create(String sourceKey) throws IOException {
			CameraMediaCache.ensureVideoParent(sourceKey);
			Path tempVideoPath = CameraMediaCache.tempVideoSourcePath(sourceKey);
			Path audioPath = tempVideoPath.resolveSibling(tempVideoPath.getFileName().toString() + ".audio.wav");
			Files.deleteIfExists(audioPath);
			return new HandCameraAudioTrack(audioPath);
		}

		private synchronized void attach(MicrophoneSystem.MicrophonePcmRecorder recorder) {
			this.recorder = recorder;
		}

		private synchronized void writeFrame(MicrophoneSystem.PcmFrame frame) {
			if (this.closed || frame == null || frame.samples() == null || frame.samples().length == 0) {
				return;
			}
			try {
				short[] samples = frame.samples();
				ByteBuffer buffer = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
				for (short sample : samples) {
					buffer.putShort(sample);
				}
				this.output.write(buffer.array());
				this.samplesWritten += samples.length;
			} catch (IOException exception) {
				this.closed = true;
				MicrophoneSystem.MicrophonePcmRecorder current = this.recorder;
				this.recorder = null;
				if (current != null) {
					current.close();
				}
				try {
					this.output.close();
				} catch (IOException ignored) {
				}
			}
		}

		private Path finish() throws IOException {
			MicrophoneSystem.MicrophonePcmRecorder current;
			synchronized (this) {
				current = this.recorder;
				this.recorder = null;
			}
			if (current != null) {
				current.finishAndJoin();
			}
			long dataBytes;
			synchronized (this) {
				if (!this.closed) {
					this.closed = true;
					this.output.close();
				}
				dataBytes = this.samplesWritten * 2L;
			}
			try (SeekableByteChannel channel = Files.newByteChannel(this.path, StandardOpenOption.WRITE)) {
				channel.position(0L);
				channel.write(ByteBuffer.wrap(wavHeader(dataBytes)));
			}
			return dataBytes > 0L ? this.path : null;
		}

		private void abort() throws IOException {
			closeImmediately();
			CameraVideoRecordingSystem.deleteQuietly(this.path);
		}

		private void abortQuietly() {
			try {
				abort();
			} catch (IOException ignored) {
			}
		}

		private void deleteQuietly() {
			CameraVideoRecordingSystem.deleteQuietly(this.path);
		}

		private void closeImmediately() throws IOException {
			MicrophoneSystem.MicrophonePcmRecorder current;
			synchronized (this) {
				if (this.closed) {
					return;
				}
				this.closed = true;
				current = this.recorder;
				this.recorder = null;
			}
			if (current != null) {
				current.close();
			}
			synchronized (this) {
				this.output.close();
			}
		}

		private static byte[] wavHeader(long dataBytes) {
			ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
			header.put(new byte[]{'R', 'I', 'F', 'F'});
			header.putInt((int) Math.min(Integer.MAX_VALUE, 36L + dataBytes));
			header.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
			header.putInt(16);
			header.putShort((short) 1);
			header.putShort((short) 1);
			header.putInt(AUDIO_SAMPLE_RATE);
			header.putInt(AUDIO_SAMPLE_RATE * 2);
			header.putShort((short) 2);
			header.putShort((short) 16);
			header.put(new byte[]{'d', 'a', 't', 'a'});
			header.putInt((int) Math.min(Integer.MAX_VALUE, dataBytes));
			return header.array();
		}
	}
}
