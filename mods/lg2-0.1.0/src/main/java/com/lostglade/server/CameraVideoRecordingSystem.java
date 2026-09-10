package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.block.ModBlocks;
import com.lostglade.item.CameraPhotoSettings;
import com.lostglade.item.ModItems;
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
	private static final Map<UUID, ActiveRecording> RECORDINGS_BY_PLAYER = new ConcurrentHashMap<>();

	private CameraVideoRecordingSystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(CameraVideoRecordingSystem::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> RECORDINGS_BY_PLAYER.clear());
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
		RECORDINGS_BY_PLAYER.put(player.getUUID(), state);
		handle.completionFuture().whenComplete((result, throwable) -> {
			MinecraftServer server = player.level().getServer();
			if (server != null) {
				server.execute(() -> finishRecording(server, state, result, throwable));
			}
		});
		// The renderer still has to warm its shadow world. The definitive "started"
		// feedback is sent only after the first real frame reaches the encoder.
		Lg2Messages.actionBar(player, "message.lg2.camera.video.starting");
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
		if (server == null || RECORDINGS_BY_PLAYER.isEmpty()) {
			return;
		}
		for (ActiveRecording recording : RECORDINGS_BY_PLAYER.values()) {
			if (recording == null || recording.stopRequested()) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(recording.playerId());
			if (player == null
					|| !player.isAlive()
					|| DroneSystem.isCameraBlockedByDroneControl(player)
					|| !isHoldingCameraInAnyHand(player)) {
				requestStop(server, recording, player != null);
			}
		}
	}

	private static void requestStop(MinecraftServer server, ActiveRecording recording, boolean notifyPlayer) {
		if (server == null || recording == null || recording.stopRequested()) {
			return;
		}
		recording.markStopRequested();
		RendererBotCameraSystem.stopVideoRecording(server, recording.requestId());
		if (notifyPlayer) {
			ServerPlayer player = server.getPlayerList().getPlayer(recording.playerId());
			if (player != null) {
				Lg2Messages.actionBar(player, "message.lg2.camera.video.stopping");
			}
		}
	}

	private static boolean isHoldingCameraInAnyHand(ServerPlayer player) {
		if (player == null) {
			return false;
		}
		return player.getMainHandItem().is(ModItems.CAMERA) || player.getOffhandItem().is(ModItems.CAMERA);
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
			if (player != null) {
				Lg2Messages.actionBar(player, "message.lg2.camera.video.failed");
			}
			return;
		}

		if (player == null) {
			state.abortAudioCapture();
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
			Lg2Messages.actionBar(player, "message.lg2.camera.video.persist_failed");
			return;
		} finally {
			state.deleteAudioCapture();
		}

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
			if (player != null) {
				Lg2Messages.actionBar(player, "message.lg2.camera.video.map_failed");
			}
			return;
		}

		giveOrDrop(player, videoItem);
		ServerMechanicsGateSystem.syncPlayerInventory(player);
		Lg2Messages.actionBar(
				player,
				audioMuxFailed ? "message.lg2.camera.video.ready_audio_failed" : "message.lg2.camera.video.ready"
		);
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
		private volatile boolean stopRequested;
		private boolean startedFeedbackSent;

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
			this.stopRequested = false;
			this.startedFeedbackSent = false;
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

		private synchronized boolean markStartedFeedbackSent() {
			if (this.startedFeedbackSent) {
				return false;
			}
			this.startedFeedbackSent = true;
			return true;
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
