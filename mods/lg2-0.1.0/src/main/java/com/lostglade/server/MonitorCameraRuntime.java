package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.server.monitor.MonitorApp;
import com.lostglade.server.monitor.MonitorMediaApp;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.lostglade.server.MonitorScreenGalleryRuntime.*;
import static com.lostglade.server.MonitorScreenLiveSources.*;
import static com.lostglade.server.MonitorScreenMediaHydration.*;
import static com.lostglade.server.MonitorScreenMediaLoadResults.*;
import static com.lostglade.server.MonitorScreenSystem.*;

final class MonitorCameraRuntime {
	private static final int VIDEO_TARGET_FPS = 12;
	private static final int AUDIO_SAMPLE_RATE = 48_000;
	private static final int AUDIO_FRAME_SAMPLES = 960;
	private static final ConcurrentHashMap<ScreenRuntimeKey, CameraRuntimeState> STATES = new ConcurrentHashMap<>();
	private static final ExecutorService IO_EXECUTOR = Executors.newCachedThreadPool(task -> {
		Thread thread = new Thread(task, "lg2-monitor-camera-io");
		thread.setDaemon(true);
		return thread;
	});

	private MonitorCameraRuntime() {
	}

	static void clearRuntime() {
		for (CameraRuntimeState state : STATES.values()) {
			state.close();
		}
		STATES.clear();
	}

	static void deactivateRuntime(ScreenRuntimeKey key) {
		if (key == null) {
			return;
		}
		stopPreview(key);
		CameraRuntimeState removed = STATES.remove(key);
		if (removed == null) {
			return;
		}
		synchronized (removed) {
			removed.close();
		}
	}

	static CameraAppVisualSnapshot captureSnapshot(MinecraftServer server, ScreenComponent component) {
		if (server == null || component == null) {
			return emptySnapshot();
		}
		CameraRuntimeState state = STATES.computeIfAbsent(component.runtimeKey(), ignored -> new CameraRuntimeState());
		List<LiveCameraReference> cameras = collectCameraReferences(server, component);
		List<MicrophoneSystem.ScreenMicrophoneDevice> microphones = MicrophoneSystem.connectedMicrophoneDevices(server, component.runtimeKey());
		UiLayout pickerLayout = createUiLayout(component.width(), component.height());
		BufferedImage preview;
		CameraAppCaptureMode mode;
		boolean recording;
		boolean paused;
		boolean deviceMenuOpen;
		long elapsedMs;
		String statusText;
		int selectedCameraIndex;
		int selectedMicrophoneIndex;
		int cameraScroll;
		int microphoneScroll;
		int connectedCameraCount = connectedCameraCount(cameras);
		int connectedDroneCount = connectedDroneCount(cameras);
		long version;
		boolean shouldStopPreview;
		boolean chromeHidden;
		boolean droneControlVisible;
		float droneTiltStrength;
		synchronized (state) {
			String previousCameraUrl = state.selectedCameraUrl;
			normalizeSelectionLocked(state, cameras, microphones);
			normalizeDevicePickerScrollLocked(state, pickerLayout, cameras != null ? cameras.size() : 0, microphones != null ? microphones.size() : 0);
			boolean cameraSelectionChanged = !Objects.equals(previousCameraUrl, state.selectedCameraUrl);
			boolean previewChanged = false;
			if (cameraSelectionChanged || cameras == null || cameras.isEmpty()) {
				previewChanged = clearPreviewFrameLocked(state);
			}
			if (cameraSelectionChanged || previewChanged) {
				state.version++;
			}
			mode = state.captureMode;
			recording = state.recording != null;
			paused = state.recording != null && state.recording.paused();
			deviceMenuOpen = state.deviceMenuOpen;
			elapsedMs = state.recording != null ? state.recording.elapsedMs() : 0L;
			statusText = state.statusText;
			selectedCameraIndex = selectedCameraIndexLocked(state, cameras);
			selectedMicrophoneIndex = selectedMicrophoneIndexLocked(state, microphones);
			cameraScroll = state.cameraScroll;
			microphoneScroll = state.microphoneScroll;
			chromeHidden = state.chromeHidden;
			droneControlVisible = controllableSelectedDroneLocked(state, cameras) != null;
			droneTiltStrength = state.droneTiltStrength;
			preview = copyBufferedImage(state.previewFrame);
			version = state.version + (recording && !paused ? System.currentTimeMillis() / 250L : 0L);
			shouldStopPreview = cameraSelectionChanged || selectedCameraIndex < 0;
		}
		if (shouldStopPreview) {
			stopPreview(component.runtimeKey());
		}
		if (selectedCameraIndex >= 0 && selectedCameraIndex < cameras.size()) {
			ensurePreviewStream(server, component, cameras.get(selectedCameraIndex), state, droneTiltStrength);
		}
		return new CameraAppVisualSnapshot(
				version,
				preview,
				cameraSnapshots(server, state, cameras),
				microphoneSnapshots(state, microphones),
				connectedCameraCount,
				connectedDroneCount,
				selectedCameraIndex,
				selectedMicrophoneIndex,
				cameraScroll,
				microphoneScroll,
				mode,
				recording,
				paused,
				elapsedMs,
				deviceMenuOpen,
				chromeHidden,
				droneControlVisible,
				droneTiltStrength,
				statusText == null ? "" : statusText
		);
	}

	static void drawScreen(Graphics2D graphics, UiLayout layout, MonitorApp app, ScreenRuntimeKey runtimeKey, CameraAppVisualSnapshot snapshot) {
		if (graphics == null || layout == null) {
			return;
		}
		CameraAppVisualSnapshot state = snapshot != null ? snapshot : emptySnapshot();
		UiRect canvas = mediaCanvasRect(layout);
		drawCameraAtmosphere(graphics, canvas, layout);
		drawPreview(graphics, layout, state);
		if (state.chromeHidden()) {
			return;
		}
		drawMediaCloseButton(graphics, mediaCloseRect(layout), layout, MediaButtonSegment.SINGLE);
		drawCameraMenuButton(graphics, cameraMenuButtonRect(layout), layout, state.deviceMenuOpen());
		if (state.droneControlVisible()) {
			drawCameraDroneControlButton(graphics, cameraDroneControlButtonRect(layout), layout);
		}
		if (state.deviceMenuOpen()) {
			drawDevicePicker(graphics, layout, runtimeKey, state);
			drawStatus(graphics, layout, state);
			return;
		}
		drawRecordingPill(graphics, layout, state);
		drawModeDock(graphics, layout, state);
		drawStatus(graphics, layout, state);
	}

	static boolean handleTouch(MinecraftServer server, ServerPlayer player, ScreenComponent component, UiLayout layout, UiPoint touchPoint) {
		if (server == null || component == null || layout == null || touchPoint == null) {
			return false;
		}
		CameraRuntimeState state = STATES.computeIfAbsent(component.runtimeKey(), ignored -> new CameraRuntimeState());
		synchronized (state) {
			if (state.chromeHidden) {
				state.chromeHidden = false;
				state.version++;
				requestRuntimeRender(server, component.runtimeKey());
				return true;
			}
		}
		if (mediaCloseRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			stopPreview(component.runtimeKey());
			return false;
		}
		List<LiveCameraReference> cameras = collectCameraReferences(server, component);
		List<MicrophoneSystem.ScreenMicrophoneDevice> microphones = MicrophoneSystem.connectedMicrophoneDevices(server, component.runtimeKey());
		synchronized (state) {
			normalizeSelectionLocked(state, cameras, microphones);
		}
		boolean devicePickerOpen;
		synchronized (state) {
			devicePickerOpen = state.deviceMenuOpen;
		}
		if (devicePickerOpen) {
			return handleDevicePickerTouch(server, component, state, layout, touchPoint, cameras, microphones);
		}
		if (cameraMenuButtonRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			synchronized (state) {
				state.deviceMenuOpen = true;
				state.chromeHidden = false;
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		LiveCameraReference controllableDrone;
		synchronized (state) {
			controllableDrone = controllableSelectedDroneLocked(state, cameras);
		}
		if (player != null
				&& controllableDrone != null
				&& cameraDroneControlButtonRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			boolean started = DroneSystem.tryStartControllingDrone(
					player,
					controllableDrone.sourceUuid(),
					controllableDrone.dimension() != null ? controllableDrone.dimension() : component.runtimeKey().dimension(),
					controllableDrone.pos()
			);
			setStatus(server, component.runtimeKey(), state, started ? "Подключаюсь к дрону" : "Дрон недоступен");
			return true;
		}
		if (photoModeRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			setMode(server, component.runtimeKey(), state, CameraAppCaptureMode.PHOTO);
			return true;
		}
		if (videoModeRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			setMode(server, component.runtimeKey(), state, CameraAppCaptureMode.VIDEO);
			return true;
		}
		if (audioModeRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			setMode(server, component.runtimeKey(), state, CameraAppCaptureMode.AUDIO);
			return true;
		}
		if (pauseButtonRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			togglePause(server, component.runtimeKey(), state);
			return true;
		}
		if (recordButtonRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			toggleCapture(server, component, state, cameras, microphones);
			return true;
		}
		synchronized (state) {
			state.deviceMenuOpen = false;
			state.chromeHidden = true;
			state.statusText = "";
			state.version++;
		}
		requestRuntimeRender(server, component.runtimeKey());
		return true;
	}

	static boolean onPlayerHotbarScroll(ServerPlayer player, int previousSlot, int currentSlot) {
		if (player == null) {
			return false;
		}
		ObservedCameraUiTarget target = findObservedCameraUiTarget(player);
		if (target == null || target.touchPoint() == null) {
			return false;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return false;
		}
		int delta = normalizeHotbarDelta(previousSlot, currentSlot);
		if (delta == 0) {
			return false;
		}
		ScreenComponent component = target.component();
		CameraRuntimeState state = STATES.get(component.runtimeKey());
		if (state == null) {
			return false;
		}
		boolean devicePickerOpen;
		synchronized (state) {
			devicePickerOpen = state.deviceMenuOpen;
		}
		if (!devicePickerOpen) {
			return false;
		}
		List<LiveCameraReference> cameras = collectCameraReferences(server, component);
		List<MicrophoneSystem.ScreenMicrophoneDevice> microphones = MicrophoneSystem.connectedMicrophoneDevices(server, component.runtimeKey());
		UiLayout layout = target.layout();
		UiPoint touchPoint = target.touchPoint();
		if (devicePickerCameraGridRect(layout).contains(touchPoint.x(), touchPoint.y()) && cameras.size() > devicePickerCameraCapacity(layout)) {
			scrollCameraPicker(server, component.runtimeKey(), state, layout, cameras.size(), -delta);
			return true;
		}
		if (devicePickerMicrophoneListRect(layout).contains(touchPoint.x(), touchPoint.y()) && microphones.size() > devicePickerMicrophoneCapacity(layout)) {
			scrollMicrophonePicker(server, component.runtimeKey(), state, layout, microphones.size(), -delta);
			return true;
		}
		return false;
	}

	private static boolean handleDevicePickerTouch(
			MinecraftServer server,
			ScreenComponent component,
			CameraRuntimeState state,
			UiLayout layout,
			UiPoint touchPoint,
			List<LiveCameraReference> cameras,
			List<MicrophoneSystem.ScreenMicrophoneDevice> microphones
	) {
		if (devicePickerCloseRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			closeDevicePicker(server, component.runtimeKey(), state);
			return true;
		}
		if (!devicePickerPanelRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			closeDevicePicker(server, component.runtimeKey(), state);
			return true;
		}
		if (cameraPickerScrollLeftRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			scrollCameraPicker(server, component.runtimeKey(), state, layout, cameras != null ? cameras.size() : 0, -cameraPickerScrollStep(layout));
			return true;
		}
		if (cameraPickerScrollRightRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			scrollCameraPicker(server, component.runtimeKey(), state, layout, cameras != null ? cameras.size() : 0, cameraPickerScrollStep(layout));
			return true;
		}
		if (microphonePickerScrollUpRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			scrollMicrophonePicker(server, component.runtimeKey(), state, layout, microphones != null ? microphones.size() : 0, -1);
			return true;
		}
		if (microphonePickerScrollDownRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			scrollMicrophonePicker(server, component.runtimeKey(), state, layout, microphones != null ? microphones.size() : 0, 1);
			return true;
		}
		if (devicePickerDroneTiltRect(layout).contains(touchPoint.x(), touchPoint.y())) {
			float strength = droneTiltStrengthAt(layout, touchPoint);
			synchronized (state) {
				state.droneTiltStrength = strength;
				state.statusText = "";
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		int cameraScroll;
		int microphoneScroll;
		synchronized (state) {
			normalizeDevicePickerScrollLocked(state, layout, cameras != null ? cameras.size() : 0, microphones != null ? microphones.size() : 0);
			cameraScroll = state.cameraScroll;
			microphoneScroll = state.microphoneScroll;
		}
		int cameraIndex = devicePickerCameraIndexAt(layout, cameras != null ? cameras.size() : 0, cameraScroll, touchPoint);
		if (cameraIndex >= 0 && cameras != null && cameraIndex < cameras.size()) {
			LiveCameraReference camera = cameras.get(cameraIndex);
			synchronized (state) {
				state.selectedCameraUrl = liveCameraGalleryUrl(camera);
				state.previewFrame = null;
				state.previewFrameClientNanos = 0L;
				state.previewFrameReceivedAtNanos = 0L;
				state.statusText = "";
				state.version++;
			}
			RendererBotCameraSystem.stopLiveStream(previewOwnerId(component.runtimeKey()));
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		int microphoneIndex = devicePickerMicrophoneIndexAt(layout, microphones != null ? microphones.size() : 0, microphoneScroll, touchPoint);
		if (microphoneIndex >= 0 && microphones != null && microphoneIndex < microphones.size()) {
			String microphoneKey = microphoneDeviceKey(microphones.get(microphoneIndex));
			synchronized (state) {
				normalizeSelectionLocked(state, cameras, microphones);
				if (state.selectedMicrophoneKeys.contains(microphoneKey)) {
					if (state.selectedMicrophoneKeys.size() > 1) {
						state.selectedMicrophoneKeys.remove(microphoneKey);
						state.statusText = "";
					} else {
						state.statusText = "Нужен хотя бы один микрофон";
					}
				} else {
					state.selectedMicrophoneKeys.add(microphoneKey);
					state.statusText = "";
				}
				syncSelectedMicrophoneSelectionLocked(state, microphones);
				state.microphoneSelectionInitialized = true;
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
			return true;
		}
		return true;
	}

	private static void closeDevicePicker(MinecraftServer server, ScreenRuntimeKey key, CameraRuntimeState state) {
		if (server == null || key == null || state == null) {
			return;
		}
		synchronized (state) {
			state.deviceMenuOpen = false;
			state.statusText = "";
			state.version++;
		}
		requestRuntimeRender(server, key);
	}

	private static CameraAppVisualSnapshot emptySnapshot() {
		return new CameraAppVisualSnapshot(0L, null, List.of(), List.of(), 0, 0, -1, -1, 0, 0, CameraAppCaptureMode.PHOTO, false, false, 0L, false, false, false, 1.0F, "");
	}

	private static void setMode(MinecraftServer server, ScreenRuntimeKey key, CameraRuntimeState state, CameraAppCaptureMode mode) {
		synchronized (state) {
			if (state.recording != null) {
				state.statusText = "Останови текущую запись";
			} else {
				state.captureMode = mode;
				state.statusText = "";
			}
			state.version++;
		}
		requestRuntimeRender(server, key);
	}

	private static void togglePause(MinecraftServer server, ScreenRuntimeKey key, CameraRuntimeState state) {
		synchronized (state) {
			if (state.recording != null) {
				state.recording.setPaused(!state.recording.paused());
				state.statusText = state.recording.paused() ? "Пауза" : "Запись";
				state.version++;
			}
		}
		requestRuntimeRender(server, key);
	}

	private static void toggleCapture(
			MinecraftServer server,
			ScreenComponent component,
			CameraRuntimeState state,
			List<LiveCameraReference> cameras,
			List<MicrophoneSystem.ScreenMicrophoneDevice> microphones
	) {
		RecordingSession toStop;
		synchronized (state) {
			toStop = state.recording;
		}
		if (toStop != null) {
			finishRecordingAsync(server, component.runtimeKey(), state, toStop);
			return;
		}
		CameraAppCaptureMode mode;
		synchronized (state) {
			mode = state.captureMode;
		}
		if (mode == CameraAppCaptureMode.PHOTO) {
			savePhoto(server, component.runtimeKey(), state);
		} else if (mode == CameraAppCaptureMode.VIDEO) {
			startVideoRecording(server, component, state, cameras, microphones);
		} else {
			startAudioRecording(server, component, state, microphones);
		}
	}

	private static void startVideoRecording(
			MinecraftServer server,
			ScreenComponent component,
			CameraRuntimeState state,
			List<LiveCameraReference> cameras,
			List<MicrophoneSystem.ScreenMicrophoneDevice> microphones
	) {
		if (cameras.isEmpty()) {
			setStatus(server, component.runtimeKey(), state, "Нет камеры для записи");
			return;
		}
		synchronized (state) {
			if (state.previewFrame == null) {
				state.statusText = "Камера прогревается";
				state.version++;
				requestRuntimeRender(server, component.runtimeKey());
				return;
			}
		}
		try {
			String sourceKey = "camera-app-video-" + UUID.randomUUID();
			Path output = CameraMediaCache.videoSourcePath(sourceKey);
			CameraMediaCache.ensureVideoParent(sourceKey);
			BufferedImage initialFrame;
			synchronized (state) {
				initialFrame = copyBufferedImage(state.previewFrame);
			}
			long initialFrameClientNanos;
			long initialFrameReceivedAtNanos;
			synchronized (state) {
				initialFrameClientNanos = state.previewFrameClientNanos;
				initialFrameReceivedAtNanos = state.previewFrameReceivedAtNanos;
			}
			List<Integer> selectedMicrophones;
			synchronized (state) {
				selectedMicrophones = selectedMicrophoneIndicesLocked(state, microphones);
			}
			VideoRecordingSession recording = new VideoRecordingSession(
					sourceKey,
					output,
					VIDEO_TARGET_FPS,
					!selectedMicrophones.isEmpty(),
					initialFrame,
					initialFrameClientNanos,
					initialFrameReceivedAtNanos
			);
			if (recording.hasAudioTrack()) {
				MicrophoneSystem.MicrophonePcmRecorder recorder = MicrophoneSystem.startVideoSyncedPcmRecorder(
						server,
						component.runtimeKey(),
						selectedMicrophones,
						recording::audioTimelineAnchor,
						recording::writeAudioFrame
				);
				if (recorder != null) {
					recording.attachAudioRecorder(recorder);
				} else {
					recording.disableAudioTrack();
				}
			}
			recording.start();
			synchronized (state) {
				state.recording = recording;
				state.statusText = "Запись видео";
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
		} catch (IOException exception) {
			setStatus(server, component.runtimeKey(), state, "Видео не запустилось");
			Lg2.LOGGER.warn("Failed to start monitor camera video recording", exception);
		}
	}

	private static void startAudioRecording(
			MinecraftServer server,
			ScreenComponent component,
			CameraRuntimeState state,
			List<MicrophoneSystem.ScreenMicrophoneDevice> microphones
	) {
		if (microphones.isEmpty()) {
			setStatus(server, component.runtimeKey(), state, "Нет микрофона для записи");
			return;
		}
		try {
			String sourceKey = "camera-app-audio-" + UUID.randomUUID();
			Path output = CameraMediaCache.cacheRoot().resolve("audio").resolve(sourceKey + ".wav");
			Files.createDirectories(output.getParent());
			BufferedImage coverBackground;
			synchronized (state) {
				coverBackground = copyBufferedImage(state.previewFrame);
			}
			AudioRecordingSession recording = new AudioRecordingSession(sourceKey, output, coverBackground);
			List<Integer> selectedMicrophones;
			synchronized (state) {
				selectedMicrophones = selectedMicrophoneIndicesLocked(state, microphones);
			}
			if (selectedMicrophones.isEmpty()) {
				recording.closeImmediately();
				setStatus(server, component.runtimeKey(), state, "Выбери микрофон");
				return;
			}
			MicrophoneSystem.MicrophonePcmRecorder recorder = MicrophoneSystem.startTimedPcmRecorder(server, component.runtimeKey(), selectedMicrophones, recording::writeFrame);
			if (recorder == null) {
				recording.closeImmediately();
				setStatus(server, component.runtimeKey(), state, "Микрофон недоступен");
				return;
			}
			recording.attach(recorder);
			synchronized (state) {
				state.recording = recording;
				state.statusText = "Запись аудио";
				state.version++;
			}
			requestRuntimeRender(server, component.runtimeKey());
		} catch (IOException exception) {
			setStatus(server, component.runtimeKey(), state, "Аудио не запустилось");
			Lg2.LOGGER.warn("Failed to start monitor camera audio recording", exception);
		}
	}

	private static void finishRecordingAsync(MinecraftServer server, ScreenRuntimeKey key, CameraRuntimeState state, RecordingSession recording) {
		synchronized (state) {
			if (state.recording != recording) {
				return;
			}
			state.recording = null;
			state.statusText = "Сохраняю";
			state.version++;
		}
		requestRuntimeRender(server, key);
		IO_EXECUTOR.execute(() -> {
			RecordedMedia media = null;
			try {
				media = recording.finish();
			} catch (Exception exception) {
				Lg2.LOGGER.warn("Failed to finish monitor camera recording", exception);
			}
			RecordedMedia finalMedia = media;
			server.execute(() -> {
				if (finalMedia != null) {
					saveRecordedMediaToGallery(server, key, finalMedia);
					setStatus(server, key, state, "Сохранено в галерею");
				} else {
					setStatus(server, key, state, "Запись не удалась");
				}
			});
		});
	}

	private static void savePhoto(MinecraftServer server, ScreenRuntimeKey key, CameraRuntimeState state) {
		BufferedImage frame;
		synchronized (state) {
			frame = copyBufferedImage(state.previewFrame);
		}
		if (frame == null) {
			setStatus(server, key, state, "Камера ещё не дала кадр");
			return;
		}
		IO_EXECUTOR.execute(() -> {
			RecordedMedia media = null;
			try {
				String sourceKey = "camera-app-photo-" + UUID.randomUUID();
				Path source = CameraMediaCache.photoSourcePath(sourceKey);
				CameraMediaCache.ensurePhotoParent(sourceKey);
				ImageIO.write(frame, "png", source.toFile());
				media = new RecordedMedia(sourceKey, source, GalleryItemKind.MEDIA, "Фото", "camera", "lg2-camera:photo:" + sourceKey, frame);
			} catch (Exception exception) {
				Lg2.LOGGER.warn("Failed to save monitor camera photo", exception);
			}
			RecordedMedia finalMedia = media;
			server.execute(() -> {
				if (finalMedia != null) {
					saveRecordedMediaToGallery(server, key, finalMedia);
					setStatus(server, key, state, "Фото сохранено");
				} else {
					setStatus(server, key, state, "Фото не удалось");
				}
			});
		});
	}

	private static void saveRecordedMediaToGallery(MinecraftServer server, ScreenRuntimeKey key, RecordedMedia media) {
		MediaRuntimeState galleryState = MEDIA_STATES.computeIfAbsent(
				key,
				ignored -> MediaRuntimeState.fresh(ScreenViewMode.GALLERY, "", () -> onMediaProgressChanged(server, key))
		);
		ScreenComponent component = resolveScreenComponent(server, key);
		UiLayout layout = component != null ? createUiLayout(component.width(), component.height()) : null;
		String localKey;
		try {
			localKey = MonitorMediaApp.persistLocalGalleryFile(media.sourceKey(), media.path());
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to persist camera app media {}", media.path(), exception);
			return;
		}
		int index;
		synchronized (galleryState) {
			ensureGalleryStateHydrated(server, key, galleryState);
			index = upsertGalleryItemLocked(
					galleryState,
					media.title(),
					media.subtitle(),
					media.url(),
					localKey,
					null,
					media.preview(),
					media.kind()
			);
			if (layout != null && index >= 0) {
				beginGalleryPendingOpenLocked(galleryState, media.url(), index);
			}
			galleryState.version++;
		}
		persistGalleryState(server, key, galleryState);
		scheduleGalleryItemLoad(server, key, media.title(), media.url(), localKey, media.kind(), false, index);
	}

	private static void ensurePreviewStream(MinecraftServer server, ScreenComponent component, LiveCameraReference cameraRef, CameraRuntimeState state, float droneTiltStrength) {
		if (cameraRef == null) {
			return;
		}
		String sourceUrl = liveCameraGalleryUrl(cameraRef);
		String ownerId = previewOwnerId(component.runtimeKey());
		int fullWidth = Math.max(1, component.width()) * MAP_SIZE;
		int fullHeight = Math.max(1, component.height()) * MAP_SIZE;
		if (cameraRef.sourceType() == LiveCameraSourceType.DRONE) {
			DroneSystem.DroneLiveFeedState droneState = cameraRef.sourceUuid() != null
					? DroneSystem.resolveLiveFeedState(server, cameraRef.sourceUuid(), cameraRef.dimension(), cameraRef.pos())
					: null;
			if (droneState == null) {
				return;
			}
			ServerLevel droneLevel = server.getLevel(droneState.dimension());
			if (droneLevel == null) {
				return;
			}
			RendererBotCameraSystem.ensureLiveStream(
					ownerId,
					droneLevel,
					null,
					droneState.expectedX(),
					droneState.expectedY(),
					droneState.expectedZ(),
					droneState.yaw(),
					droneState.pitch(),
					droneState.followEntityUuid(),
					droneState.hiddenEntityUuids(),
					droneState.omnidirectionalChunkLoading(),
					fullWidth,
					fullHeight,
					LIVE_CAMERA_FOV_DEGREES,
				LIVE_CAMERA_TARGET_FPS,
				droneTiltStrength,
					frame -> onPreviewFrame(server, component.runtimeKey(), sourceUrl, fullWidth, fullHeight, frame),
					error -> setStatus(server, component.runtimeKey(), state, error)
			);
			return;
		}
		ServerLevel cameraLevel = server.getLevel(cameraRef.dimension());
		BlockPos cameraPos = cameraRef.pos();
		CameraRelocationSystem.MobileCameraFeed mobileFeed = cameraLevel != null
				? CameraRelocationSystem.mobileCameraFeed(cameraLevel, cameraPos)
				: null;
		if (mobileFeed != null) {
			RendererBotCameraSystem.ensureLiveStream(
					ownerId,
					cameraLevel,
					null,
					mobileFeed.expectedX(),
					mobileFeed.expectedY(),
					mobileFeed.expectedZ(),
					mobileFeed.yaw(),
					mobileFeed.pitch(),
					mobileFeed.followEntityUuid(),
					mobileFeed.hiddenEntityUuids(),
					true,
					fullWidth,
					fullHeight,
					LIVE_CAMERA_FOV_DEGREES,
					LIVE_CAMERA_TARGET_FPS,
					frame -> onPreviewFrame(server, component.runtimeKey(), sourceUrl, fullWidth, fullHeight, frame),
					error -> setStatus(server, component.runtimeKey(), state, error)
			);
			return;
		}
		if (cameraLevel == null || cameraPos == null || !cameraLevel.hasChunkAt(cameraPos) || !isCameraBlock(cameraLevel, cameraPos)) {
			return;
		}
		BlockState cameraState = cameraLevel.getBlockState(cameraPos);
		LiveCameraPose pose = liveCameraCapturePose(cameraLevel, cameraPos, cameraState);
		Vec3 origin = pose.origin();
		RendererBotCameraSystem.ensureLiveStream(
				ownerId,
				cameraLevel,
				cameraPos,
				origin.x,
				origin.y - RENDERER_BOT_EYE_HEIGHT,
				origin.z,
				pose.yaw(),
				pose.pitch(),
				fullWidth,
				fullHeight,
				LIVE_CAMERA_FOV_DEGREES,
				LIVE_CAMERA_TARGET_FPS,
				frame -> onPreviewFrame(server, component.runtimeKey(), sourceUrl, fullWidth, fullHeight, frame),
				error -> setStatus(server, component.runtimeKey(), state, error)
		);
	}

	private static void onPreviewFrame(MinecraftServer server, ScreenRuntimeKey key, String sourceUrl, int width, int height, RendererBotCameraSystem.LiveStreamFrame streamFrame) {
		byte[] pixels = streamFrame != null ? streamFrame.pixels() : null;
		if (server == null || key == null || pixels == null) {
			return;
		}
		long nowNanos = System.nanoTime();
		long clientFrameNanos = streamFrame.clientFrameNanos() > 0L ? streamFrame.clientFrameNanos() : nowNanos;
		long receivedAtNanos = streamFrame.receivedAtNanos() > 0L ? streamFrame.receivedAtNanos() : nowNanos;
		BufferedImage frame = mapPaletteImage(pixels, width, height);
		server.execute(() -> {
			CameraRuntimeState state = STATES.get(key);
			if (state == null) {
				RendererBotCameraSystem.stopLiveStream(previewOwnerId(key));
				return;
			}
			RecordingSession recording;
			synchronized (state) {
				if (!Objects.equals(state.selectedCameraUrl, sourceUrl)) {
					return;
				}
				state.previewFrame = frame;
				state.previewFrameClientNanos = clientFrameNanos;
				state.previewFrameReceivedAtNanos = receivedAtNanos;
				recording = state.recording;
				state.version++;
			}
			if (recording instanceof VideoRecordingSession videoRecording) {
				videoRecording.offerFrame(frame, clientFrameNanos, receivedAtNanos);
			}
			requestRuntimeRender(server, key);
		});
	}

	private static void stopPreview(ScreenRuntimeKey key) {
		RendererBotCameraSystem.stopLiveStream(previewOwnerId(key));
	}

	private static String previewOwnerId(ScreenRuntimeKey key) {
		return "monitor-camera|" + liveCameraStreamOwnerId(key);
	}

	private static List<LiveCameraReference> collectCameraReferences(MinecraftServer server, ScreenComponent component) {
		if (server == null || component == null || component.runtimeKey() == null) {
			return List.of();
		}
		ServerLevel level = server.getLevel(component.runtimeKey().dimension());
		return level != null ? collectConnectedCameraPositions(level, component) : List.of();
	}

	private static int connectedCameraCount(List<LiveCameraReference> cameras) {
		if (cameras == null || cameras.isEmpty()) {
			return 0;
		}
		int count = 0;
		for (LiveCameraReference camera : cameras) {
			if (camera != null && camera.sourceType() != LiveCameraSourceType.DRONE) {
				count++;
			}
		}
		return count;
	}

	private static int connectedDroneCount(List<LiveCameraReference> cameras) {
		if (cameras == null || cameras.isEmpty()) {
			return 0;
		}
		int count = 0;
		for (LiveCameraReference camera : cameras) {
			if (camera != null && camera.sourceType() == LiveCameraSourceType.DRONE) {
				count++;
			}
		}
		return count;
	}

	private static void normalizeSelectionLocked(
			CameraRuntimeState state,
			List<LiveCameraReference> cameras,
			List<MicrophoneSystem.ScreenMicrophoneDevice> microphones
	) {
		boolean selectedCameraMissing = state.selectedCameraUrl != null
				&& !state.selectedCameraUrl.isBlank()
				&& (cameras == null || cameras.stream().map(MonitorScreenLiveSources::liveCameraGalleryUrl).noneMatch(url -> Objects.equals(url, state.selectedCameraUrl)));
		if ((state.selectedCameraUrl == null || state.selectedCameraUrl.isBlank() || selectedCameraMissing) && cameras != null && !cameras.isEmpty()) {
			state.selectedCameraUrl = liveCameraGalleryUrl(cameras.get(0));
		} else if (cameras == null || cameras.isEmpty()) {
			state.selectedCameraUrl = "";
		}
		if (microphones == null || microphones.isEmpty()) {
			state.selectedMicrophoneIndex = -1;
			state.selectedMicrophoneIndices.clear();
			state.selectedMicrophoneKeys.clear();
			return;
		}
		if (state.selectedMicrophoneKeys.isEmpty()) {
			if (!state.selectedMicrophoneIndices.isEmpty()) {
				for (Integer index : state.selectedMicrophoneIndices) {
					if (index != null && index >= 0 && index < microphones.size()) {
						state.selectedMicrophoneKeys.add(microphoneDeviceKey(microphones.get(index)));
					}
				}
			} else if (state.selectedMicrophoneIndex >= 0 && state.selectedMicrophoneIndex < microphones.size()) {
				state.selectedMicrophoneKeys.add(microphoneDeviceKey(microphones.get(state.selectedMicrophoneIndex)));
			}
		}
		if (!state.microphoneSelectionInitialized) {
			state.selectedMicrophoneKeys.clear();
			state.selectedMicrophoneKeys.add(microphoneDeviceKey(microphones.get(0)));
			state.microphoneSelectionInitialized = true;
		}
		Set<String> availableKeys = new LinkedHashSet<>();
		for (MicrophoneSystem.ScreenMicrophoneDevice microphone : microphones) {
			availableKeys.add(microphoneDeviceKey(microphone));
		}
		state.selectedMicrophoneKeys.removeIf(key -> key == null || key.isBlank() || !availableKeys.contains(key));
		syncSelectedMicrophoneSelectionLocked(state, microphones);
	}

	private static void normalizeDevicePickerScrollLocked(CameraRuntimeState state, UiLayout layout, int cameraCount, int microphoneCount) {
		if (state == null || layout == null) {
			return;
		}
		state.cameraScroll = clampInt(state.cameraScroll, 0, maxCameraPickerScroll(layout, cameraCount));
		state.microphoneScroll = clampInt(state.microphoneScroll, 0, maxMicrophonePickerScroll(layout, microphoneCount));
	}

	private static boolean clearPreviewFrameLocked(CameraRuntimeState state) {
		if (state == null) {
			return false;
		}
		boolean changed = state.previewFrame != null || state.previewFrameClientNanos != 0L || state.previewFrameReceivedAtNanos != 0L;
		state.previewFrame = null;
		state.previewFrameClientNanos = 0L;
		state.previewFrameReceivedAtNanos = 0L;
		return changed;
	}

	private static void scrollCameraPicker(MinecraftServer server, ScreenRuntimeKey key, CameraRuntimeState state, UiLayout layout, int cameraCount, int delta) {
		if (server == null || key == null || state == null || layout == null || delta == 0) {
			return;
		}
		boolean changed = false;
		synchronized (state) {
			normalizeDevicePickerScrollLocked(state, layout, cameraCount, 0);
			int nextScroll = clampInt(state.cameraScroll + delta, 0, maxCameraPickerScroll(layout, cameraCount));
			if (nextScroll != state.cameraScroll) {
				state.cameraScroll = nextScroll;
				state.version++;
				changed = true;
			}
		}
		if (changed) {
			requestRuntimeRender(server, key);
		}
	}

	private static void scrollMicrophonePicker(MinecraftServer server, ScreenRuntimeKey key, CameraRuntimeState state, UiLayout layout, int microphoneCount, int delta) {
		if (server == null || key == null || state == null || layout == null || delta == 0) {
			return;
		}
		boolean changed = false;
		synchronized (state) {
			normalizeDevicePickerScrollLocked(state, layout, 0, microphoneCount);
			int nextScroll = clampInt(state.microphoneScroll + delta, 0, maxMicrophonePickerScroll(layout, microphoneCount));
			if (nextScroll != state.microphoneScroll) {
				state.microphoneScroll = nextScroll;
				state.version++;
				changed = true;
			}
		}
		if (changed) {
			requestRuntimeRender(server, key);
		}
	}

	private static int selectedCameraIndexLocked(CameraRuntimeState state, List<LiveCameraReference> cameras) {
		if (state == null || cameras == null || cameras.isEmpty()) {
			return -1;
		}
		for (int index = 0; index < cameras.size(); index++) {
			if (Objects.equals(liveCameraGalleryUrl(cameras.get(index)), state.selectedCameraUrl)) {
				return index;
			}
		}
		return 0;
	}

	private static int selectedMicrophoneIndexLocked(CameraRuntimeState state, List<MicrophoneSystem.ScreenMicrophoneDevice> microphones) {
		if (state == null || microphones == null || microphones.isEmpty()) {
			return -1;
		}
		syncSelectedMicrophoneSelectionLocked(state, microphones);
		return state.selectedMicrophoneIndex;
	}

	private static List<Integer> selectedMicrophoneIndicesLocked(CameraRuntimeState state, List<MicrophoneSystem.ScreenMicrophoneDevice> microphones) {
		if (state == null || microphones == null || microphones.isEmpty()) {
			return List.of();
		}
		syncSelectedMicrophoneSelectionLocked(state, microphones);
		return state.selectedMicrophoneIndices.isEmpty() ? List.of() : List.copyOf(state.selectedMicrophoneIndices);
	}

	private static LiveCameraReference selectedCameraReferenceLocked(CameraRuntimeState state, List<LiveCameraReference> cameras) {
		int selectedIndex = selectedCameraIndexLocked(state, cameras);
		return selectedIndex >= 0 && cameras != null && selectedIndex < cameras.size() ? cameras.get(selectedIndex) : null;
	}

	private static LiveCameraReference controllableSelectedDroneLocked(CameraRuntimeState state, List<LiveCameraReference> cameras) {
		LiveCameraReference selected = selectedCameraReferenceLocked(state, cameras);
		if (selected == null
				|| selected.sourceType() != LiveCameraSourceType.DRONE
				|| selected.sourceUuid() == null
				|| DroneSystem.hasActiveController(selected.sourceUuid())) {
			return null;
		}
		return selected;
	}

	private static List<CameraAppDeviceSnapshot> cameraSnapshots(MinecraftServer server, CameraRuntimeState state, List<LiveCameraReference> cameras) {
		if (cameras == null || cameras.isEmpty()) {
			return List.of();
		}
		List<CameraAppDeviceSnapshot> snapshots = new ArrayList<>(cameras.size());
		String selected;
		synchronized (state) {
			selected = state.selectedCameraUrl;
		}
		for (int index = 0; index < cameras.size(); index++) {
			LiveCameraReference camera = cameras.get(index);
			BlockPos pos = camera.pos();
			String title = liveCameraDeviceTitle(server, camera);
			String subtitle = pos != null ? pos.getX() + " " + pos.getY() + " " + pos.getZ() : "live";
			String url = liveCameraGalleryUrl(camera);
			boolean online = isLiveCameraOnline(server, camera);
			snapshots.add(new CameraAppDeviceSnapshot(title, subtitle, url, createLiveCameraPlaceholderPreview(title, subtitle, online, camera.sourceType()), Objects.equals(url, selected), online));
		}
		return List.copyOf(snapshots);
	}

	private static List<CameraAppDeviceSnapshot> microphoneSnapshots(CameraRuntimeState state, List<MicrophoneSystem.ScreenMicrophoneDevice> microphones) {
		if (microphones == null || microphones.isEmpty()) {
			return List.of();
		}
		int selected;
		Set<String> selectedKeys;
		synchronized (state) {
			syncSelectedMicrophoneSelectionLocked(state, microphones);
			selected = state.selectedMicrophoneIndex;
			selectedKeys = Set.copyOf(state.selectedMicrophoneKeys);
		}
		List<CameraAppDeviceSnapshot> snapshots = new ArrayList<>(microphones.size());
		for (MicrophoneSystem.ScreenMicrophoneDevice microphone : microphones) {
			String microphoneKey = microphoneDeviceKey(microphone);
			boolean selectedNow = selectedKeys.isEmpty() ? microphone.index() == selected : selectedKeys.contains(microphoneKey);
			snapshots.add(new CameraAppDeviceSnapshot(microphone.title(), microphone.subtitle(), "mic:" + microphone.index(), null, selectedNow, true));
		}
		return List.copyOf(snapshots);
	}

	private static void syncSelectedMicrophoneSelectionLocked(CameraRuntimeState state, List<MicrophoneSystem.ScreenMicrophoneDevice> microphones) {
		state.selectedMicrophoneIndices.clear();
		if (state == null || microphones == null || microphones.isEmpty()) {
			state.selectedMicrophoneIndex = -1;
			if (state != null) {
				state.selectedMicrophoneKeys.clear();
			}
			return;
		}
		Set<String> availableKeys = new LinkedHashSet<>();
		for (MicrophoneSystem.ScreenMicrophoneDevice microphone : microphones) {
			String microphoneKey = microphoneDeviceKey(microphone);
			availableKeys.add(microphoneKey);
			if (state.selectedMicrophoneKeys.contains(microphoneKey)) {
				state.selectedMicrophoneIndices.add(microphone.index());
			}
		}
		state.selectedMicrophoneKeys.removeIf(key -> key == null || key.isBlank() || !availableKeys.contains(key));
		state.selectedMicrophoneIndex = state.selectedMicrophoneIndices.isEmpty() ? -1 : state.selectedMicrophoneIndices.iterator().next();
	}

	private static String microphoneDeviceKey(MicrophoneSystem.ScreenMicrophoneDevice microphone) {
		if (microphone == null || microphone.dimension() == null || microphone.pos() == null) {
			return "";
		}
		BlockPos pos = microphone.pos();
		return microphone.dimension().identifier() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
	}

	private static void setStatus(MinecraftServer server, ScreenRuntimeKey key, CameraRuntimeState state, String status) {
		synchronized (state) {
			state.statusText = status == null ? "" : status;
			state.version++;
		}
		requestRuntimeRender(server, key);
	}

	private static void drawCameraAtmosphere(Graphics2D graphics, UiRect canvas, UiLayout layout) {
		graphics.setColor(new Color(10, 13, 17));
		graphics.fillRect(canvas.x(), canvas.y(), canvas.width(), canvas.height());
		graphics.setPaint(new java.awt.GradientPaint(canvas.x(), canvas.y(), new Color(32, 38, 45), canvas.right(), canvas.bottom(), new Color(7, 8, 10)));
		graphics.fillRect(canvas.x(), canvas.y(), canvas.width(), canvas.height());
		graphics.setColor(new Color(255, 255, 255, 18));
		int gap = Math.max(18, layout.unit() * 2);
		for (int x = canvas.x() - canvas.height(); x < canvas.right(); x += gap) {
			graphics.drawLine(x, canvas.y(), x + canvas.height(), canvas.bottom());
		}
	}

	private static void drawHeader(Graphics2D graphics, UiLayout layout, MonitorApp app, CameraAppVisualSnapshot state) {
		UiRect title = new UiRect(mediaCloseRect(layout).right() + gap(layout), mediaCloseRect(layout).y(), layout.canvasWidth() / 3, mediaCloseRect(layout).height());
		drawAppIcon(graphics, app, new UiRect(title.x(), title.y(), title.height(), title.height()), Math.max(2, layout.unit() / 5));
		drawVerticalText(graphics, "Камера", new UiRect(title.x() + title.height() + gap(layout), title.y(), title.width(), title.height()), new Color(248, 251, 255, 235), Font.BOLD, clampInt(layout.unit() + 2, 13, 22));
		if (state.recording()) {
			UiRect rec = new UiRect(layout.canvasWidth() - layout.unit() * 8, title.y(), layout.unit() * 6, title.height());
			graphics.setColor(new Color(255, 72, 84, 230));
			graphics.fillRoundRect(rec.x(), rec.y(), rec.width(), rec.height(), rec.height(), rec.height());
			drawCenteredText(graphics, "REC " + formatCameraTime(state.elapsedMs()), rec, Color.WHITE, Font.BOLD, clampInt(layout.unit() - 1, 8, 13));
		}
	}

	private static void drawPreview(Graphics2D graphics, UiLayout layout, CameraAppVisualSnapshot state) {
		UiRect rect = previewRect(layout);
		if (state.previewFrame() != null) {
			drawCoveredImage(graphics, state.previewFrame(), rect, 0);
		} else {
			fillRoundedRect(graphics, rect, 0, new Color(0, 0, 0, 96));
		}
	}

	private static void drawCameraMenuButton(Graphics2D graphics, UiRect rect, UiLayout layout, boolean active) {
		Color color = drawMediaHeaderControlBase(graphics, rect, MediaButtonSegment.SINGLE);
		if (active) {
			fillRoundedRect(graphics, rect, rect.height(), new Color(248, 251, 255, 232));
			color = new Color(18, 20, 24, 238);
		}
		UiRect icon = mediaChromeIconRect(rect, layout);
		int lineHeight = Math.max(2, icon.height() / 8);
		int gap = Math.max(3, icon.height() / 5);
		int y = icon.y() + icon.height() / 2 - gap;
		graphics.setColor(color);
		for (int index = 0; index < 3; index++) {
			graphics.fillRoundRect(icon.x() + icon.width() / 6, y + index * gap, icon.width() * 2 / 3, lineHeight, lineHeight, lineHeight);
		}
	}

	private static void drawCameraDroneControlButton(Graphics2D graphics, UiRect rect, UiLayout layout) {
		Color color = drawMediaHeaderControlBase(graphics, rect, MediaButtonSegment.SINGLE);
		UiRect iconRect = mediaChromeIconRect(rect, layout).inset(Math.max(1, layout.unit() / 6));
		drawGamepadGlyph(graphics, iconRect, color, Math.max(1.25F, mediaChromeStrokeWidth(rect) * 0.72F));
	}

	private static void drawDeviceMenu(Graphics2D graphics, UiLayout layout, CameraAppVisualSnapshot state) {
		UiRect panel = deviceMenuRect(layout);
		fillRoundedRect(graphics, panel, clampInt(layout.unit(), 8, 18), new Color(12, 14, 18, 216));
		strokeRoundedRect(graphics, panel, clampInt(layout.unit(), 8, 18), Math.max(1.0F, mediaChromeStrokeWidth(panel)), new Color(255, 255, 255, 58));
		drawDeviceButton(graphics, cameraDeviceButtonRect(layout), PlayerUiIcon.VIDEO_CAMERA, selectedDeviceLabel(state.cameras(), "Камера"), layout);
		drawDeviceButton(graphics, microphoneDeviceButtonRect(layout), PlayerUiIcon.MIC, selectedDeviceLabel(state.microphones(), "Микрофон"), layout);
	}

	private static void drawDevicePicker(Graphics2D graphics, UiLayout layout, ScreenRuntimeKey runtimeKey, CameraAppVisualSnapshot state) {
		UiRect panel = devicePickerPanelRect(layout);
		boolean ultra = ultraCompactScreenLayout(layout);
		fillRoundedRect(graphics, panel, ultra ? clampInt(layout.unit(), 6, 10) : clampInt(layout.unit() * 2, 14, 28), new Color(6, 10, 14, 232));
		strokeRoundedRect(graphics, panel, ultra ? clampInt(layout.unit(), 6, 10) : clampInt(layout.unit() * 2, 14, 28), ultra ? 0.85F : 1.0F, new Color(255, 255, 255, 54));
		drawOverlayCloseButton(graphics, devicePickerCloseRect(layout), layout);
		UiRect title = devicePickerTitleRect(layout);
		if (!ultra) {
			drawVerticalText(graphics, "УСТРОЙСТВА", new UiRect(title.x(), title.y(), title.width() / 2, title.height()), new Color(248, 251, 255, 236), Font.BOLD, clampInt(layout.unit(), 10, 16));
			drawCenteredTextFitted(graphics, deviceCountLabel(state), new UiRect(title.x() + title.width() / 2, title.y(), title.width() / 2, title.height()), new Color(188, 204, 218, 224), Font.PLAIN, clampInt(layout.unit() - 2, 7, 11), 5);
		}

		UiRect cameraTitle = devicePickerCameraTitleRect(layout);
		List<CameraAppDeviceSnapshot> cameras = state.cameras();
		int cameraCapacity = devicePickerCameraCapacity(layout);
		int cameraScroll = clampInt(state.cameraScroll(), 0, Math.max(0, (cameras == null ? 0 : cameras.size()) - cameraCapacity));
		MonitorScrollAnimationSystem.ScrollVisualState cameraVisualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.CAMERA_PICKER_CAMERAS,
				cameraScroll,
				Math.max(0, (cameras == null ? 0 : cameras.size()) - cameraCapacity)
		);
		if (cameras != null && cameras.size() > cameraCapacity) {
			drawPickerScrollStatus(graphics, cameraTitle, layout, cameraScroll, cameraCapacity, cameras.size());
			drawPickerScrollButton(graphics, cameraPickerScrollLeftRect(layout), Math.PI / 2.0D, layout, cameraScroll > 0);
			drawPickerScrollButton(graphics, cameraPickerScrollRightRect(layout), -Math.PI / 2.0D, layout, cameraScroll + cameraCapacity < cameras.size());
		}
		if (cameras == null || cameras.isEmpty()) {
			if (!ultra) {
				drawCenteredText(graphics, "Подключи камеру или дрон к экрану", devicePickerCameraGridRect(layout), new Color(210, 224, 236, 224), Font.BOLD, clampInt(layout.unit(), 9, 14));
			}
		} else {
			int baseIndex = cameraVisualScroll.anchorIndex();
			int xOffset = -(int) Math.round(cameraVisualScroll.fraction() * cameraPickerCellStep(layout));
			int count = Math.min(
					Math.max(0, cameras.size() - baseIndex),
					cameraCapacity + (cameraVisualScroll.animated() && baseIndex + cameraCapacity < cameras.size() ? 1 : 0)
			);
			Shape previousClip = graphics.getClip();
			UiRect grid = devicePickerCameraGridRect(layout);
			graphics.setClip(grid.x(), grid.y(), grid.width(), grid.height());
			for (int visibleIndex = 0; visibleIndex < count; visibleIndex++) {
				int index = baseIndex + visibleIndex;
				CameraAppDeviceSnapshot camera = cameras.get(index);
				UiRect rect = offsetRect(devicePickerCameraRect(layout, visibleIndex), xOffset, 0);
				fillRoundedRect(graphics, rect, clampInt(layout.unit(), 8, 16), new Color(255, 255, 255, camera.selected() ? 34 : 18));
				BufferedImage preview = camera.selected() && state.previewFrame() != null ? state.previewFrame() : camera.preview();
				if (preview != null) {
					drawScaledImage(graphics, preview, rect.inset(Math.max(2, layout.unit() / 4)), MediaScaleMode.FILL);
				}
				if (camera.selected()) {
					strokeRoundedRect(graphics, rect, clampInt(layout.unit(), 8, 16), 1.5F, new Color(255, 255, 255, 172));
				}
				if (!ultra) {
					UiRect label = new UiRect(rect.x() + layout.unit() / 2, rect.bottom() - clampInt(layout.unit() * 3, 24, 38), rect.width() - layout.unit(), clampInt(layout.unit() * 2, 18, 28));
					fillRoundedRect(graphics, label, label.height(), new Color(0, 0, 0, 112));
					drawCenteredTextFitted(graphics, camera.title() + " " + camera.subtitle(), label.inset(2), camera.online() ? new Color(248, 251, 255, 238) : new Color(248, 251, 255, 136), Font.BOLD, clampInt(layout.unit() - 2, 7, 11), 6);
				}
			}
			graphics.setClip(previousClip);
		}

		UiRect microphoneTitle = devicePickerMicrophoneTitleRect(layout);
		drawDroneTiltSlider(graphics, devicePickerDroneTiltRect(layout), state.droneTiltStrength(), layout);
		if (!ultra) {
			drawVerticalText(graphics, "МИКРОФОНЫ " + (state.microphones() != null ? state.microphones().size() : 0), microphoneTitle, new Color(188, 204, 218, 224), Font.BOLD, clampInt(layout.unit() - 2, 7, 11));
		}
		List<CameraAppDeviceSnapshot> microphones = state.microphones();
		int microphoneCapacity = devicePickerMicrophoneCapacity(layout);
		int microphoneScroll = clampInt(state.microphoneScroll(), 0, Math.max(0, (microphones == null ? 0 : microphones.size()) - microphoneCapacity));
		MonitorScrollAnimationSystem.ScrollVisualState microphoneVisualScroll = MonitorScrollAnimationSystem.sample(
				runtimeKey,
				MonitorScrollAnimationSystem.ScrollChannel.CAMERA_PICKER_MICROPHONES,
				microphoneScroll,
				Math.max(0, (microphones == null ? 0 : microphones.size()) - microphoneCapacity)
		);
		if (microphones != null && microphones.size() > microphoneCapacity) {
			drawPickerScrollStatus(graphics, microphoneTitle, layout, microphoneScroll, microphoneCapacity, microphones.size());
			drawPickerScrollButton(graphics, microphonePickerScrollUpRect(layout), Math.PI, layout, microphoneScroll > 0);
			drawPickerScrollButton(graphics, microphonePickerScrollDownRect(layout), 0.0D, layout, microphoneScroll + microphoneCapacity < microphones.size());
		}
		if (microphones == null || microphones.isEmpty()) {
			if (!ultra) {
				drawCenteredText(graphics, "Подключи микрофон к экрану", devicePickerMicrophoneListRect(layout), new Color(210, 224, 236, 224), Font.BOLD, clampInt(layout.unit(), 9, 14));
			}
			return;
		}
		int microphoneBaseIndex = microphoneVisualScroll.anchorIndex();
		int yOffset = -(int) Math.round(microphoneVisualScroll.fraction() * devicePickerMicrophoneRowStep(layout));
		int microphoneCount = Math.min(
				Math.max(0, microphones.size() - microphoneBaseIndex),
				microphoneCapacity + (microphoneVisualScroll.animated() && microphoneBaseIndex + microphoneCapacity < microphones.size() ? 1 : 0)
		);
		Shape previousClip = graphics.getClip();
		UiRect listRect = devicePickerMicrophoneListRect(layout);
		graphics.setClip(listRect.x(), listRect.y(), listRect.width(), listRect.height());
		for (int visibleIndex = 0; visibleIndex < microphoneCount; visibleIndex++) {
			int index = microphoneBaseIndex + visibleIndex;
			drawDevicePickerMicrophoneRow(graphics, layout, offsetRect(devicePickerMicrophoneRowRect(layout, visibleIndex), 0, yOffset), microphones.get(index));
		}
		graphics.setClip(previousClip);
	}

	private static void drawDevicePickerMicrophoneRow(Graphics2D graphics, UiLayout layout, UiRect rect, CameraAppDeviceSnapshot microphone) {
		boolean ultra = ultraCompactScreenLayout(layout);
		int arc = ultra ? clampInt(layout.unit(), 5, 8) : clampInt(layout.unit(), 8, 16);
		fillRoundedRect(graphics, rect, arc, new Color(255, 255, 255, microphone.selected() ? 30 : 14));
		strokeRoundedRect(graphics, rect, arc, ultra ? 0.85F : 1.0F, microphone.selected() ? new Color(255, 255, 255, 128) : new Color(255, 255, 255, 42));
		int iconSize = ultra ? Math.max(8, Math.min(12, rect.height() - 2)) : clampInt(layout.unit() * 2, 18, 28);
		int gap = devicePickerCameraGap(layout);
		UiRect check = new UiRect(rect.x() + gap, rect.y() + (rect.height() - iconSize) / 2, iconSize, iconSize);
		Color checkColor = drawSmallMediaButtonBase(graphics, check, MediaButtonSegment.SINGLE, microphone.selected(), mediaChromeStrokeWidth(check));
		if (microphone.selected()) {
			drawPlayerUiIcon(graphics, mediaChromeIconRect(check, layout), PlayerUiIcon.CHECK, checkColor);
		}
		UiRect micIcon = new UiRect(check.right() + gap, rect.y() + (rect.height() - iconSize) / 2, iconSize, iconSize);
		drawPlayerUiIcon(graphics, micIcon.inset(Math.max(2, layout.unit() / 5)), PlayerUiIcon.MIC, new Color(238, 244, 250, 214));
		if (ultra) {
			return;
		}
		UiRect textRect = new UiRect(micIcon.right() + gap, rect.y() + Math.max(2, layout.unit() / 4), Math.max(8, rect.right() - micIcon.right() - gap * 2), rect.height() - Math.max(4, layout.unit() / 2));
		int titleHeight = Math.max(10, textRect.height() / 2);
		drawWrappedText(graphics, microphone.title(), new UiRect(textRect.x(), textRect.y(), textRect.width(), titleHeight), new Color(248, 251, 255, 232), Font.BOLD, clampInt(layout.unit() - 1, 8, 13), 1);
		drawWrappedText(graphics, microphone.subtitle(), new UiRect(textRect.x(), textRect.y() + titleHeight, textRect.width(), textRect.height() - titleHeight), new Color(178, 194, 210, 214), Font.PLAIN, clampInt(layout.unit() - 2, 7, 11), 1);
	}

	private static void drawDroneTiltSlider(Graphics2D graphics, UiRect rect, float strength, UiLayout layout) {
		boolean ultra = ultraCompactScreenLayout(layout);
		float value = Math.clamp(Float.isFinite(strength) ? strength : 1.0F, 0.0F, 1.0F);
		UiRect track = droneTiltTrackRect(layout);
		if (!ultra) {
			int labelHeight = Math.max(8, rect.height() - track.height() - Math.max(4, layout.unit() / 2));
			drawVerticalText(graphics, "НАКЛОН ДРОНА", new UiRect(rect.x(), rect.y(), rect.width() * 2 / 3, labelHeight), new Color(188, 204, 218, 224), Font.BOLD, clampInt(layout.unit() - 2, 7, 11));
			drawRightAlignedText(graphics, Math.round(value * 100.0F) + "%", new UiRect(rect.x() + rect.width() * 2 / 3, rect.y(), rect.width() / 3, labelHeight), new Color(248, 251, 255, 238), Font.BOLD, clampInt(layout.unit() - 2, 7, 11));
		}
		int arc = Math.min(track.width(), track.height());
		fillRoundedRect(graphics, track, arc, new Color(255, 255, 255, 40));
		int progressWidth = Math.max(track.height(), Math.round(track.width() * value));
		fillRoundedRect(graphics, new UiRect(track.x(), track.y(), Math.min(track.width(), progressWidth), track.height()), arc, new Color(248, 251, 255, 242));
		int markerWidth = 2;
		int markerHeight = clampInt(track.height() + Math.max(5, layout.unit()), 7, 16);
		int markerCenterX = track.x() + Math.round(track.width() * value);
		int markerX = clampInt(markerCenterX - markerWidth / 2, track.x(), Math.max(track.x(), track.right() - markerWidth));
		fillRoundedRect(graphics, new UiRect(markerX, track.y() + (track.height() - markerHeight) / 2, markerWidth, markerHeight), markerWidth, new Color(248, 251, 255, 248));
	}

	private static void drawPickerScrollButton(Graphics2D graphics, UiRect rect, double rotationRadians, UiLayout layout, boolean enabled) {
		drawOverlayChevronButton(graphics, rect, layout, rotationRadians, enabled);
	}

	private static void drawPickerScrollStatus(Graphics2D graphics, UiRect titleRect, UiLayout layout, int offset, int visibleCount, int totalCount) {
		if (totalCount <= visibleCount || visibleCount <= 0) {
			return;
		}
		if (ultraCompactScreenLayout(layout)) {
			return;
		}
		int width = clampInt(layout.unit() * 4, 30, 56);
		UiRect statusRect = new UiRect(titleRect.right() - width - clampInt(layout.unit() * 4, 28, 44), titleRect.y(), width, titleRect.height());
		int from = offset + 1;
		int to = Math.min(totalCount, offset + visibleCount);
		drawCenteredText(
				graphics,
				from + "-" + to,
				statusRect,
				new Color(188, 204, 218, 208),
				Font.PLAIN,
				clampInt(layout.unit() - 2, 7, 11)
		);
	}

	private static String selectedDeviceLabel(List<CameraAppDeviceSnapshot> devices, String fallback) {
		if (devices != null) {
			int selectedCount = 0;
			String firstTitle = "";
			for (CameraAppDeviceSnapshot device : devices) {
				if (device.selected()) {
					selectedCount++;
					if (firstTitle.isBlank()) {
						firstTitle = device.title();
					}
				}
			}
			if (selectedCount > 1) {
				return selectedCount + " выбрано";
			}
			if (selectedCount == 1) {
				return firstTitle;
			}
		}
		return fallback;
	}

	private static String deviceCountLabel(CameraAppVisualSnapshot state) {
		int cameras = state != null ? Math.max(0, state.connectedCameraCount()) : 0;
		int drones = state != null ? Math.max(0, state.connectedDroneCount()) : 0;
		int microphones = state != null && state.microphones() != null ? state.microphones().size() : 0;
		return "Камер: " + cameras + " · Дронов: " + drones + " · Микрофонов: " + microphones;
	}

	private static void drawDeviceButton(Graphics2D graphics, UiRect rect, PlayerUiIcon icon, String label, UiLayout layout) {
		Color color = drawMediaHeaderControlBase(graphics, rect, MediaButtonSegment.SINGLE);
		drawPlayerUiIcon(graphics, new UiRect(rect.x() + gap(layout), rect.y() + rect.height() / 4, rect.height() / 2, rect.height() / 2), icon, color);
		drawVerticalText(graphics, label, new UiRect(rect.x() + rect.height(), rect.y(), rect.width() - rect.height() - gap(layout), rect.height()), color, Font.BOLD, clampInt(layout.unit() - 1, 8, 13));
	}

	private static void drawModeDock(Graphics2D graphics, UiLayout layout, CameraAppVisualSnapshot state) {
		UiRect record = recordButtonRect(layout);
		Color fill = state.recording() ? new Color(248, 251, 255, 244) : new Color(236, 46, 58, 238);
		Color icon = state.recording() ? new Color(236, 46, 58, 238) : Color.WHITE;
		fillRoundedRect(graphics, record, record.height(), fill);
		if (state.recording()) {
			drawStopGlyph(graphics, mediaChromeIconRect(record, layout), icon);
		} else {
			drawPlayerUiIcon(graphics, mediaChromeIconRect(record, layout), state.captureMode() == CameraAppCaptureMode.AUDIO ? PlayerUiIcon.MIC : PlayerUiIcon.CAMERA, icon);
		}
		if (state.recording()) {
			UiRect pause = pauseButtonRect(layout);
			Color pauseColor = drawSmallMediaButtonBase(graphics, pause, MediaButtonSegment.SINGLE, state.paused(), mediaChromeStrokeWidth(pause));
			drawPlayerUiIcon(graphics, mediaChromeIconRect(pause, layout), state.paused() ? PlayerUiIcon.PLAY : PlayerUiIcon.PAUSE, pauseColor);
		}
		drawModeWord(graphics, photoModeRect(layout), "фото", state.captureMode() == CameraAppCaptureMode.PHOTO, layout);
		drawModeWord(graphics, videoModeRect(layout), "видео", state.captureMode() == CameraAppCaptureMode.VIDEO, layout);
		drawModeWord(graphics, audioModeRect(layout), "аудио", state.captureMode() == CameraAppCaptureMode.AUDIO, layout);
	}

	private static void drawModeWord(Graphics2D graphics, UiRect rect, String label, boolean active, UiLayout layout) {
		Color color = active ? new Color(255, 255, 255, 245) : new Color(255, 255, 255, 128);
		drawCenteredText(graphics, label, rect, color, active ? Font.BOLD : Font.PLAIN, ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 1, 6, 8) : clampInt(layout.unit(), 9, 15));
		if (active) {
			int underlineWidth = Math.max(rect.width() / 3, layout.unit());
			int underlineX = rect.x() + (rect.width() - underlineWidth) / 2;
			graphics.setColor(new Color(236, 46, 58, 220));
			int lineHeight = ultraCompactScreenLayout(layout) ? 1 : Math.max(2, layout.unit() / 6);
			graphics.fillRoundRect(underlineX, rect.bottom() - Math.max(1, layout.unit() / 5), underlineWidth, lineHeight, 4, 4);
		}
	}

	private static void drawStatus(Graphics2D graphics, UiLayout layout, CameraAppVisualSnapshot state) {
		String status = state.statusText();
		if (status == null || status.isBlank()) {
			return;
		}
		drawCenteredText(graphics, status, statusRect(layout), new Color(220, 230, 238, 205), Font.PLAIN, clampInt(layout.unit() - 1, 8, 13));
	}

	private static void drawRecordingPill(Graphics2D graphics, UiLayout layout, CameraAppVisualSnapshot state) {
		if (!state.recording()) {
			return;
		}
		UiRect rect = recordingPillRect(layout);
		fillRoundedRect(graphics, rect, rect.height(), new Color(10, 12, 16, 164));
		int dotSize = ultraCompactScreenLayout(layout) ? Math.max(3, rect.height() / 3) : Math.max(5, rect.height() / 3);
		graphics.setColor(new Color(236, 46, 58, 245));
		graphics.fillOval(rect.x() + rect.height() / 3, rect.y() + (rect.height() - dotSize) / 2, dotSize, dotSize);
		drawVerticalText(graphics, formatCameraTime(state.elapsedMs()), new UiRect(rect.x() + rect.height(), rect.y(), rect.width() - rect.height(), rect.height()), new Color(255, 255, 255, 235), Font.BOLD, ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 1, 6, 8) : clampInt(layout.unit() - 1, 9, 14));
	}

	private static UiRect previewRect(UiLayout layout) {
		return mediaCanvasRect(layout);
	}

	private static UiRect devicePickerPanelRect(UiLayout layout) {
		UiRect canvas = mediaCanvasRect(layout);
		if (ultraCompactScreenLayout(layout)) {
			int inset = Math.max(2, layout.unit() / 3);
			return new UiRect(canvas.x() + inset, canvas.y() + inset, canvas.width() - inset * 2, canvas.height() - inset * 2);
		}
		return new UiRect(canvas.x() + layout.unit(), canvas.y() + clampInt(layout.unit() * 3, 24, 48), canvas.width() - layout.unit() * 2, canvas.height() - clampInt(layout.unit() * 4, 32, 60));
	}

	private static UiRect devicePickerCloseRect(UiLayout layout) {
		return overlayPanelCloseRect(devicePickerPanelRect(layout), layout);
	}

	private static UiRect devicePickerTitleRect(UiLayout layout) {
		return overlayPanelTitleRect(devicePickerPanelRect(layout), devicePickerCloseRect(layout), layout);
	}

	private static UiRect devicePickerContentRect(UiLayout layout) {
		return overlayPanelContentRect(devicePickerPanelRect(layout), devicePickerCloseRect(layout), layout);
	}

	private static UiRect devicePickerCameraTitleRect(UiLayout layout) {
		UiRect content = devicePickerContentRect(layout);
		int height = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 3, 7, 8) : clampInt(layout.unit() + 4, 14, 22);
		return new UiRect(content.x(), content.y(), content.width(), height);
	}

	private static UiRect devicePickerCameraGridRect(UiLayout layout) {
		UiRect content = devicePickerContentRect(layout);
		UiRect title = devicePickerCameraTitleRect(layout);
		int gap = devicePickerCameraGap(layout);
		int height = devicePickerCameraHeight(layout);
		return new UiRect(content.x(), title.bottom() + gap, content.width(), height);
	}

	private static UiRect devicePickerMicrophoneTitleRect(UiLayout layout) {
		UiRect tilt = devicePickerDroneTiltRect(layout);
		int gap = devicePickerCameraGap(layout);
		int height = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 3, 7, 8) : clampInt(layout.unit() + 4, 14, 22);
		return new UiRect(tilt.x(), tilt.bottom() + gap, tilt.width(), height);
	}

	private static UiRect devicePickerDroneTiltRect(UiLayout layout) {
		UiRect cameraGrid = devicePickerCameraGridRect(layout);
		UiRect content = devicePickerContentRect(layout);
		int gap = devicePickerCameraGap(layout);
		return new UiRect(content.x(), cameraGrid.bottom() + gap, content.width(), devicePickerDroneTiltHeight(layout));
	}

	private static UiRect devicePickerMicrophoneListRect(UiLayout layout) {
		UiRect content = devicePickerContentRect(layout);
		UiRect title = devicePickerMicrophoneTitleRect(layout);
		int gap = devicePickerCameraGap(layout);
		return new UiRect(content.x(), title.bottom() + gap, content.width(), Math.max(18, content.bottom() - title.bottom() - gap));
	}

	private static UiRect pickerScrollButtonRect(UiRect titleRect, int indexFromRight, UiLayout layout) {
		int gap = devicePickerCameraGap(layout);
		int size = Math.min(titleRect.height(), ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 3, 7, 8) : clampInt(layout.unit() * 2, 18, 30));
		int x = titleRect.right() - size - indexFromRight * (size + gap);
		return new UiRect(x, titleRect.y(), size, titleRect.height());
	}

	private static UiRect cameraPickerScrollLeftRect(UiLayout layout) {
		return pickerScrollButtonRect(devicePickerCameraTitleRect(layout), 1, layout);
	}

	private static UiRect cameraPickerScrollRightRect(UiLayout layout) {
		return pickerScrollButtonRect(devicePickerCameraTitleRect(layout), 0, layout);
	}

	private static UiRect microphonePickerScrollUpRect(UiLayout layout) {
		return pickerScrollButtonRect(devicePickerMicrophoneTitleRect(layout), 1, layout);
	}

	private static UiRect microphonePickerScrollDownRect(UiLayout layout) {
		return pickerScrollButtonRect(devicePickerMicrophoneTitleRect(layout), 0, layout);
	}

	private static int devicePickerCameraGap(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? Math.max(1, layout.unit() / 4) : Math.max(4, layout.unit() / 2);
	}

	private static int devicePickerCameraHeight(UiLayout layout) {
		UiRect content = devicePickerContentRect(layout);
		UiRect title = devicePickerCameraTitleRect(layout);
		int gap = devicePickerCameraGap(layout);
		int microphoneTitleHeight = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 3, 7, 8) : clampInt(layout.unit() + 4, 14, 22);
		int remaining = content.height() - title.height() - devicePickerDroneTiltHeight(layout) - microphoneTitleHeight - gap * 4;
		if (ultraCompactScreenLayout(layout)) {
			int available = Math.max(28, remaining);
			int preferred = (int) Math.round(available * 0.44D);
			return clampInt(preferred, 28, Math.max(28, available - 18));
		}
		int preferred = Math.max(clampInt(layout.unit() * 7, 56, 112), (content.height() - title.height() - gap * 3) / 2);
		return Math.min(preferred, Math.max(18, remaining));
	}

	private static int devicePickerDroneTiltHeight(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? clampInt(layout.unit() + 3, 7, 8) : clampInt(layout.unit() * 3, 26, 38);
	}

	private static UiRect droneTiltTrackRect(UiLayout layout) {
		UiRect rect = devicePickerDroneTiltRect(layout);
		int sideInset = ultraCompactScreenLayout(layout) ? 2 : clampInt(layout.unit() / 2, 4, 8);
		int height = clampInt((int) Math.round(layout.unit() * 0.40D), 2, 5);
		return new UiRect(rect.x() + sideInset, rect.bottom() - height - (ultraCompactScreenLayout(layout) ? 1 : Math.max(2, layout.unit() / 4)), Math.max(12, rect.width() - sideInset * 2), height);
	}

	private static float droneTiltStrengthAt(UiLayout layout, UiPoint point) {
		UiRect track = droneTiltTrackRect(layout);
		if (point == null || track.width() <= 1) return 1.0F;
		return Math.clamp((point.x() - track.x()) / (float) Math.max(1, track.width() - 1), 0.0F, 1.0F);
	}

	private static int devicePickerCameraCell(UiLayout layout) {
		return Math.max(1, devicePickerCameraHeight(layout));
	}

	private static int devicePickerCameraCapacity(UiLayout layout) {
		UiRect grid = devicePickerCameraGridRect(layout);
		int gap = devicePickerCameraGap(layout);
		int cell = devicePickerCameraCell(layout);
		return clippedListCapacity(grid, cell, gap, true);
	}

	private static UiRect devicePickerCameraRect(UiLayout layout, int index) {
		UiRect grid = devicePickerCameraGridRect(layout);
		int gap = devicePickerCameraGap(layout);
		int cell = devicePickerCameraCell(layout);
		return new UiRect(grid.x() + index * (cell + gap), grid.y(), cell, cell);
	}

	private static int cameraPickerCellStep(UiLayout layout) {
		return devicePickerCameraCell(layout) + devicePickerCameraGap(layout);
	}

	private static int devicePickerCameraIndexAt(UiLayout layout, int cameraCount, int cameraScroll, UiPoint point) {
		UiRect grid = devicePickerCameraGridRect(layout);
		if (point == null || !grid.contains(point.x(), point.y())) {
			return -1;
		}
		int count = Math.min(Math.max(0, cameraCount - cameraScroll), devicePickerCameraCapacity(layout));
		for (int visibleIndex = 0; visibleIndex < count; visibleIndex++) {
			if (devicePickerCameraRect(layout, visibleIndex).contains(point.x(), point.y())) {
				return cameraScroll + visibleIndex;
			}
		}
		return -1;
	}

	private static int cameraPickerScrollStep(UiLayout layout) {
		return 1;
	}

	private static int maxCameraPickerScroll(UiLayout layout, int cameraCount) {
		return Math.max(0, cameraCount - devicePickerCameraCapacity(layout));
	}

	private static int devicePickerMicrophoneCapacity(UiLayout layout) {
		UiRect list = devicePickerMicrophoneListRect(layout);
		int gap = devicePickerCameraGap(layout);
		int rowHeight = devicePickerMicrophoneRowHeight(layout);
		return clippedListCapacity(list, rowHeight, gap, false);
	}

	private static int devicePickerMicrophoneRowHeight(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 3 + 1, 16, 18) : clampInt(layout.unit() * 3, 28, 44);
	}

	private static UiRect devicePickerMicrophoneRowRect(UiLayout layout, int index) {
		UiRect list = devicePickerMicrophoneListRect(layout);
		int gap = devicePickerCameraGap(layout);
		int height = devicePickerMicrophoneRowHeight(layout);
		return new UiRect(list.x(), list.y() + index * (height + gap), list.width(), height);
	}

	private static int devicePickerMicrophoneRowStep(UiLayout layout) {
		return devicePickerMicrophoneRowHeight(layout) + devicePickerCameraGap(layout);
	}

	private static int devicePickerMicrophoneIndexAt(UiLayout layout, int microphoneCount, int microphoneScroll, UiPoint point) {
		UiRect list = devicePickerMicrophoneListRect(layout);
		if (point == null || !list.contains(point.x(), point.y())) {
			return -1;
		}
		int count = Math.min(Math.max(0, microphoneCount - microphoneScroll), devicePickerMicrophoneCapacity(layout));
		for (int visibleIndex = 0; visibleIndex < count; visibleIndex++) {
			if (devicePickerMicrophoneRowRect(layout, visibleIndex).contains(point.x(), point.y())) {
				return microphoneScroll + visibleIndex;
			}
		}
		return -1;
	}

	private static int maxMicrophonePickerScroll(UiLayout layout, int microphoneCount) {
		return Math.max(0, microphoneCount - devicePickerMicrophoneCapacity(layout));
	}

	private static ObservedCameraUiTarget findObservedCameraUiTarget(ServerPlayer player) {
		ObservedScreenTouch target = findObservedScreenTouch(
				player,
				component -> component.viewMode() == ScreenViewMode.CAMERA_APP
		);
		if (target == null) {
			return null;
		}
		ScreenComponent component = target.component();
		return new ObservedCameraUiTarget(component, createUiLayout(component.width(), component.height()), target.touchPoint());
	}

	private static UiRect cameraDeviceButtonRect(UiLayout layout) {
		UiRect panel = deviceMenuRect(layout);
		int inset = gap(layout);
		return new UiRect(panel.x() + inset, panel.y() + inset, panel.width() - inset * 2, mediaCloseRect(layout).height());
	}

	private static UiRect microphoneDeviceButtonRect(UiLayout layout) {
		UiRect camera = cameraDeviceButtonRect(layout);
		return new UiRect(camera.x(), camera.bottom() + gap(layout), camera.width(), camera.height());
	}

	private static UiRect cameraMenuButtonRect(UiLayout layout) {
		UiRect close = mediaCloseRect(layout);
		return new UiRect(layout.canvasWidth() - close.width() - layout.unit() / 2, close.y(), close.width(), close.height());
	}

	private static UiRect cameraDroneControlButtonRect(UiLayout layout) {
		UiRect menu = cameraMenuButtonRect(layout);
		return new UiRect(menu.x(), menu.bottom() + gap(layout), menu.width(), menu.height());
	}

	private static UiRect deviceMenuRect(UiLayout layout) {
		UiRect menu = cameraMenuButtonRect(layout);
		int width = Math.max(layout.unit() * 12, layout.canvasWidth() / 4);
		int height = mediaCloseRect(layout).height() * 2 + gap(layout) * 3;
		return new UiRect(Math.max(layout.unit(), menu.right() - width), menu.bottom() + gap(layout), width, height);
	}

	private static UiRect modeDockRect(UiLayout layout) {
		int width = Math.min(layout.canvasWidth() - layout.unit() * 2, Math.max(layout.unit() * 15, layout.canvasWidth() / 3));
		int recordSize = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 4, 18, 20) : clampInt(layout.unit() * 4, 36, 64);
		int wordHeight = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 2, 10, 12) : clampInt(layout.unit() * 2, 18, 32);
		int height = recordSize + gap(layout) + wordHeight;
		return new UiRect((layout.canvasWidth() - width) / 2, layout.canvasHeight() - height - layout.unit(), width, height);
	}

	private static UiRect photoModeRect(UiLayout layout) {
		UiRect dock = modeDockRect(layout);
		int spacing = gap(layout);
		int wordWidth = Math.max(1, (dock.width() - spacing * 2) / 3);
		int top = recordButtonRect(layout).bottom() + spacing;
		return new UiRect(dock.x(), top, wordWidth, dock.bottom() - top);
	}

	private static UiRect videoModeRect(UiLayout layout) {
		UiRect photo = photoModeRect(layout);
		return new UiRect(photo.right() + gap(layout), photo.y(), photo.width(), photo.height());
	}

	private static UiRect audioModeRect(UiLayout layout) {
		UiRect video = videoModeRect(layout);
		return new UiRect(video.right() + gap(layout), video.y(), video.width(), video.height());
	}

	private static UiRect recordButtonRect(UiLayout layout) {
		UiRect dock = modeDockRect(layout);
		int size = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 4, 18, 20) : clampInt(layout.unit() * 4, 36, 64);
		return new UiRect(dock.x() + (dock.width() - size) / 2, dock.y(), size, size);
	}

	private static UiRect pauseButtonRect(UiLayout layout) {
		UiRect record = recordButtonRect(layout);
		int size = ultraCompactScreenLayout(layout) ? Math.max(12, record.width() * 3 / 4) : Math.max(24, record.width() * 3 / 4);
		return new UiRect(record.x() - size - gap(layout), record.y() + (record.height() - size) / 2, size, size);
	}

	private static UiRect statusRect(UiLayout layout) {
		UiRect dock = modeDockRect(layout);
		return new UiRect(layout.unit(), dock.y() - layout.unit() * 2, layout.canvasWidth() - layout.unit() * 2, layout.unit() * 2);
	}

	private static UiRect recordingPillRect(UiLayout layout) {
		int width = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 7, 34, 42) : clampInt(layout.unit() * 7, 58, 104);
		int height = ultraCompactScreenLayout(layout) ? clampInt(layout.unit() * 2 + 2, 12, 14) : clampInt(layout.unit() * 2, 18, 30);
		UiRect close = mediaCloseRect(layout);
		return new UiRect(close.right() + gap(layout), close.y() + (close.height() - height) / 2, width, height);
	}

	private static int gap(UiLayout layout) {
		return ultraCompactScreenLayout(layout) ? Math.max(2, layout.unit() / 3) : Math.max(4, layout.unit() / 2);
	}

	private static void drawStopGlyph(Graphics2D graphics, UiRect rect, Color color) {
		graphics.setColor(color);
		int size = Math.min(rect.width(), rect.height()) * 2 / 5;
		graphics.fillRoundRect(rect.x() + (rect.width() - size) / 2, rect.y() + (rect.height() - size) / 2, size, size, Math.max(2, size / 5), Math.max(2, size / 5));
	}

	private static void drawCoveredImage(Graphics2D graphics, BufferedImage image, UiRect rect, int padding) {
		if (image == null || rect.width() <= 0 || rect.height() <= 0) {
			return;
		}
		int availableWidth = Math.max(1, rect.width() - padding * 2);
		int availableHeight = Math.max(1, rect.height() - padding * 2);
		double scale = Math.max(availableWidth / (double) image.getWidth(), availableHeight / (double) image.getHeight());
		int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
		int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
		int drawX = rect.x() + (rect.width() - drawWidth) / 2;
		int drawY = rect.y() + (rect.height() - drawHeight) / 2;
		graphics.setClip(rect.x(), rect.y(), rect.width(), rect.height());
		graphics.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
		graphics.setClip(null);
	}

	private static String formatCameraTime(long millis) {
		long seconds = Math.max(0L, millis / 1000L);
		return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
	}

	private static String shortRecordingIdentifier(String sourceKey) {
		if (sourceKey == null || sourceKey.isBlank()) {
			return "REC";
		}
		String normalized = sourceKey.trim();
		int dashIndex = normalized.lastIndexOf('-');
		String tail = dashIndex >= 0 && dashIndex + 1 < normalized.length() ? normalized.substring(dashIndex + 1) : normalized;
		if (tail.length() > 6) {
			tail = tail.substring(tail.length() - 6);
		}
		return "REC-" + tail.toUpperCase(Locale.ROOT);
	}

	private static final class CameraRuntimeState {
		private String selectedCameraUrl = "";
		private int selectedMicrophoneIndex = -1;
		private final Set<Integer> selectedMicrophoneIndices = new LinkedHashSet<>();
		private final Set<String> selectedMicrophoneKeys = new LinkedHashSet<>();
		private boolean microphoneSelectionInitialized;
		private int cameraScroll;
		private int microphoneScroll;
		private CameraAppCaptureMode captureMode = CameraAppCaptureMode.PHOTO;
		private BufferedImage previewFrame;
		private long previewFrameClientNanos;
		private long previewFrameReceivedAtNanos;
		private RecordingSession recording;
		private boolean deviceMenuOpen;
		private boolean chromeHidden;
		private float droneTiltStrength = 1.0F;
		private String statusText = "";
		private long version;

		private void close() {
			RecordingSession current = this.recording;
			this.recording = null;
			if (current != null) {
				current.abort();
			}
		}
	}

	private record ObservedCameraUiTarget(ScreenComponent component, UiLayout layout, UiPoint touchPoint) {
	}

	private interface RecordingSession {
		boolean paused();

		void setPaused(boolean paused);

		long elapsedMs();

		RecordedMedia finish() throws IOException;

		void abort();
	}

	private static final class PauseClock {
		private final long startedAtMillis = System.currentTimeMillis();
		private final long startedAtNanos = System.nanoTime();
		private final List<PauseInterval> pauseIntervals = new ArrayList<>();
		private boolean paused;
		private long pauseStartedAtMillis;
		private long pauseStartedAtNanos;
		private long pausedDurationMs;

		private synchronized boolean paused() {
			return this.paused;
		}

		private synchronized boolean setPaused(boolean paused) {
			if (this.paused == paused) {
				return this.paused;
			}
			long now = System.currentTimeMillis();
			long nowNanos = System.nanoTime();
			if (paused) {
				this.pauseStartedAtMillis = now;
				this.pauseStartedAtNanos = nowNanos;
			} else {
				this.pausedDurationMs += Math.max(0L, now - this.pauseStartedAtMillis);
				this.pauseIntervals.add(new PauseInterval(this.pauseStartedAtNanos, nowNanos));
				this.pauseStartedAtMillis = 0L;
				this.pauseStartedAtNanos = 0L;
			}
			this.paused = paused;
			return this.paused;
		}

		private synchronized long elapsedMs() {
			long now = System.currentTimeMillis();
			long currentPausedMs = this.paused ? Math.max(0L, now - this.pauseStartedAtMillis) : 0L;
			return Math.max(0L, now - this.startedAtMillis - this.pausedDurationMs - currentPausedMs);
		}

		private synchronized boolean activeAtNanos(long captureNanos) {
			if (captureNanos < this.startedAtNanos) {
				return false;
			}
			if (this.paused && captureNanos >= this.pauseStartedAtNanos) {
				return false;
			}
			for (PauseInterval interval : this.pauseIntervals) {
				if (captureNanos >= interval.startedAtNanos() && captureNanos < interval.endedAtNanos()) {
					return false;
				}
			}
			return true;
		}
	}

	private record PauseInterval(long startedAtNanos, long endedAtNanos) {
	}

	private record TimedVideoFrame(BufferedImage image, long clientFrameNanos, long receivedAtNanos) {
	}

	private static final class VideoRecordingSession implements RecordingSession {
		private final String sourceKey;
		private final Path outputPath;
		private final Path videoOnlyPath;
		private final Path audioPath;
		private final Path muxPath;
		private final int targetFps;
		private final int frameWidth;
		private final int frameHeight;
		private final long frameIntervalNanos;
		private final PauseClock pauseClock = new PauseClock();
		private final Object frameLock = new Object();
		private final Process process;
		private final OutputStream input;
		private final ExecutorService writerExecutor;
		private PcmAudioTrack audioTrack;
		private volatile boolean paused;
		private volatile boolean closed;
		private volatile MicrophoneSystem.PcmTimelineAnchor audioTimelineAnchor;
		private BufferedImage preview;
		private TimedVideoFrame latestFrame;

		private VideoRecordingSession(
				String sourceKey,
				Path outputPath,
				int targetFps,
				boolean captureAudio,
				BufferedImage initialFrame,
				long initialFrameClientNanos,
				long initialFrameReceivedAtNanos
		) throws IOException {
			this.sourceKey = sourceKey;
			this.outputPath = outputPath;
			this.videoOnlyPath = captureAudio ? outputPath.resolveSibling(outputPath.getFileName() + ".video.tmp.mp4") : outputPath;
			this.audioPath = captureAudio ? outputPath.resolveSibling(outputPath.getFileName() + ".audio.tmp.wav") : null;
			this.muxPath = captureAudio ? outputPath.resolveSibling(outputPath.getFileName() + ".mux.tmp.mp4") : null;
			this.targetFps = Math.max(1, targetFps);
			this.frameWidth = Math.max(1, initialFrame == null ? 1 : initialFrame.getWidth());
			this.frameHeight = Math.max(1, initialFrame == null ? 1 : initialFrame.getHeight());
			this.frameIntervalNanos = TimeUnit.SECONDS.toNanos(1L) / this.targetFps;
			this.preview = initialFrame;
			long fallbackNanos = System.nanoTime();
			long safeClientNanos = initialFrameClientNanos > 0L ? initialFrameClientNanos : fallbackNanos;
			long safeReceivedNanos = initialFrameReceivedAtNanos > 0L ? initialFrameReceivedAtNanos : fallbackNanos;
			this.latestFrame = initialFrame == null ? null : new TimedVideoFrame(initialFrame, safeClientNanos, safeReceivedNanos);
			Files.deleteIfExists(this.videoOnlyPath);
			if (this.audioPath != null) {
				Files.deleteIfExists(this.audioPath);
				Files.deleteIfExists(this.muxPath);
				this.audioTrack = new PcmAudioTrack(this.audioPath);
			}
			List<String> command = List.of(
					"ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
					"-f", "rawvideo",
					"-pix_fmt", "rgb24",
					"-s", this.frameWidth + "x" + this.frameHeight,
					"-r", Integer.toString(this.targetFps),
					"-i", "-",
					"-an", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
					"-movflags", "+faststart",
					this.videoOnlyPath.toAbsolutePath().toString()
			);
			this.process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
			this.input = new BufferedOutputStream(this.process.getOutputStream());
			this.writerExecutor = Executors.newSingleThreadExecutor(task -> {
				Thread thread = new Thread(task, "lg2-monitor-camera-video-writer");
				thread.setDaemon(true);
				return thread;
			});
		}

		private void start() {
			this.writerExecutor.execute(this::runVideoWriter);
		}

		private boolean hasAudioTrack() {
			return this.audioTrack != null;
		}

		private synchronized void attachAudioRecorder(MicrophoneSystem.MicrophonePcmRecorder recorder) {
			if (this.audioTrack != null) {
				this.audioTrack.attach(recorder);
			} else if (recorder != null) {
				recorder.close();
			}
		}

		private MicrophoneSystem.PcmTimelineAnchor audioTimelineAnchor() {
			return this.audioTimelineAnchor;
		}

		private synchronized void disableAudioTrack() {
			PcmAudioTrack current = this.audioTrack;
			this.audioTrack = null;
			if (current != null) {
				try {
					current.abort();
				} catch (IOException ignored) {
				}
			}
		}

		private synchronized void writeAudioFrame(MicrophoneSystem.PcmFrame frame) {
			PcmAudioTrack current = this.audioTrack;
			if (current != null && frame != null && this.pauseClock.activeAtNanos(frame.captureNanos())) {
				current.writeFrame(frame.samples());
			}
		}

		private void offerFrame(BufferedImage frame, long clientFrameNanos, long receivedAtNanos) {
			if (this.closed || frame == null) {
				return;
			}
			BufferedImage copy = copyBufferedImage(frame);
			long fallbackNanos = System.nanoTime();
			long safeClientNanos = clientFrameNanos > 0L ? clientFrameNanos : fallbackNanos;
			long safeReceivedNanos = receivedAtNanos > 0L ? receivedAtNanos : fallbackNanos;
			synchronized (this.frameLock) {
				this.latestFrame = new TimedVideoFrame(copy, safeClientNanos, safeReceivedNanos);
				this.preview = copy;
			}
		}

		private void runVideoWriter() {
			long nextFrameAt = System.nanoTime();
			int[] pixels = new int[this.frameWidth * this.frameHeight];
			byte[] rgb = new byte[pixels.length * 3];
			while (!this.closed) {
				if (this.paused) {
					sleepNanos(TimeUnit.MILLISECONDS.toNanos(10L));
					nextFrameAt = System.nanoTime();
					continue;
				}
				TimedVideoFrame frame;
				synchronized (this.frameLock) {
					frame = this.latestFrame;
				}
				if (frame != null) {
					try {
						ensureAudioTimelineAnchor(frame);
						writeRawFrame(frame.image(), pixels, rgb);
					} catch (IOException exception) {
						this.closed = true;
						return;
					}
				}
				nextFrameAt += this.frameIntervalNanos;
				long sleepNanos = nextFrameAt - System.nanoTime();
				if (sleepNanos > 0L) {
					sleepNanos(sleepNanos);
				} else if (sleepNanos < -this.frameIntervalNanos * 8L) {
					nextFrameAt = System.nanoTime();
				}
			}
		}

		private void ensureAudioTimelineAnchor(TimedVideoFrame frame) {
			if (this.audioTimelineAnchor != null || frame == null) {
				return;
			}
			long nowNanos = System.nanoTime();
			long clientStartNanos = frame.clientFrameNanos() > 0L ? frame.clientFrameNanos() : nowNanos;
			this.audioTimelineAnchor = new MicrophoneSystem.PcmTimelineAnchor(nowNanos, clientStartNanos);
		}

		private void writeRawFrame(BufferedImage frame, int[] pixels, byte[] rgb) throws IOException {
			BufferedImage source = frame;
			if (source.getWidth() != this.frameWidth || source.getHeight() != this.frameHeight) {
				BufferedImage scaled = new BufferedImage(this.frameWidth, this.frameHeight, BufferedImage.TYPE_INT_RGB);
				Graphics2D graphics = scaled.createGraphics();
				try {
					graphics.drawImage(source, 0, 0, this.frameWidth, this.frameHeight, null);
				} finally {
					graphics.dispose();
				}
				source = scaled;
			}
			source.getRGB(0, 0, this.frameWidth, this.frameHeight, pixels, 0, this.frameWidth);
			int offset = 0;
			for (int pixel : pixels) {
				rgb[offset++] = (byte) ((pixel >> 16) & 0xFF);
				rgb[offset++] = (byte) ((pixel >> 8) & 0xFF);
				rgb[offset++] = (byte) (pixel & 0xFF);
			}
			this.input.write(rgb, 0, offset);
			this.input.flush();
		}

		private static void sleepNanos(long nanos) {
			if (nanos <= 0L) {
				return;
			}
			try {
				TimeUnit.NANOSECONDS.sleep(nanos);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}

		@Override
		public boolean paused() {
			return this.paused;
		}

		@Override
		public void setPaused(boolean paused) {
			this.paused = this.pauseClock.setPaused(paused);
		}

		@Override
		public long elapsedMs() {
			return this.pauseClock.elapsedMs();
		}

		@Override
		public RecordedMedia finish() throws IOException {
			this.closed = true;
			this.writerExecutor.shutdown();
			try {
				if (!this.writerExecutor.awaitTermination(4, TimeUnit.SECONDS)) {
					this.writerExecutor.shutdownNow();
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				this.writerExecutor.shutdownNow();
				throw new IOException("Video writer interrupted", exception);
			}
			this.input.close();
			try {
				if (!this.process.waitFor(10, TimeUnit.SECONDS)) {
					this.process.destroyForcibly();
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException("Video encoder interrupted", exception);
			}
			if (this.audioTrack == null && !this.videoOnlyPath.equals(this.outputPath)) {
				moveVideoOnlyToOutput();
			}
			if (!Files.isRegularFile(this.outputPath) || Files.size(this.outputPath) <= 0L) {
				PcmAudioTrack currentAudio = this.audioTrack;
				if (currentAudio != null) {
					currentAudio.finish();
					this.audioTrack = null;
					muxAudio(currentAudio.path());
				}
			}
			if (!Files.isRegularFile(this.outputPath) || Files.size(this.outputPath) <= 0L) {
				throw new IOException("Video output is empty");
			}
			BufferedImage mediaPreview;
			synchronized (this.frameLock) {
				mediaPreview = this.preview;
			}
			return new RecordedMedia(this.sourceKey, this.outputPath, GalleryItemKind.VIDEO, "Видео", "camera", "lg2-camera:video:" + this.sourceKey, mediaPreview);
		}

		private void muxAudio(Path finishedAudioPath) throws IOException {
			if (finishedAudioPath == null || this.muxPath == null || !Files.isRegularFile(finishedAudioPath)) {
				moveVideoOnlyToOutput();
				return;
			}
			if (!Files.isRegularFile(this.videoOnlyPath) || Files.size(this.videoOnlyPath) <= 0L) {
				throw new IOException("Video-only output is empty");
			}
			if (Files.size(finishedAudioPath) <= 44L) {
				moveVideoOnlyToOutput();
				return;
			}
			List<String> command = List.of(
					"ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
					"-i", this.videoOnlyPath.toAbsolutePath().toString(),
					"-i", finishedAudioPath.toAbsolutePath().toString(),
					"-map", "0:v:0",
					"-map", "1:a:0",
					"-c:v", "copy",
					"-c:a", "aac",
					"-af", "apad",
					"-shortest",
					"-movflags", "+faststart",
					this.muxPath.toAbsolutePath().toString()
			);
			Process muxProcess = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
			try {
				if (!muxProcess.waitFor(10, TimeUnit.SECONDS)) {
					muxProcess.destroyForcibly();
					throw new IOException("Video audio mux timed out");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException("Video audio mux interrupted", exception);
			}
			if (muxProcess.exitValue() != 0 || !Files.isRegularFile(this.muxPath) || Files.size(this.muxPath) <= 0L) {
				moveVideoOnlyToOutput();
				return;
			}
			replaceOutput(this.muxPath, this.outputPath);
			deleteQuietly(this.videoOnlyPath);
			deleteQuietly(finishedAudioPath);
		}

		private void moveVideoOnlyToOutput() throws IOException {
			if (!this.videoOnlyPath.equals(this.outputPath)) {
				replaceOutput(this.videoOnlyPath, this.outputPath);
			}
			if (this.audioPath != null) {
				deleteQuietly(this.audioPath);
			}
			if (this.muxPath != null) {
				deleteQuietly(this.muxPath);
			}
		}

		@Override
		public void abort() {
			this.closed = true;
			this.writerExecutor.shutdownNow();
			try {
				this.input.close();
			} catch (IOException ignored) {
			}
			this.process.destroyForcibly();
			PcmAudioTrack currentAudio = this.audioTrack;
			this.audioTrack = null;
			if (currentAudio != null) {
				try {
					currentAudio.abort();
				} catch (IOException ignored) {
				}
			}
			deleteQuietly(this.videoOnlyPath);
			if (this.muxPath != null) {
				deleteQuietly(this.muxPath);
			}
		}
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

	private static final class PcmAudioTrack {
		private final Path path;
		private final OutputStream output;
		private long samplesWritten;
		private MicrophoneSystem.MicrophonePcmRecorder recorder;
		private boolean closed;

		private PcmAudioTrack(Path path) throws IOException {
			this.path = path;
			this.output = new BufferedOutputStream(Files.newOutputStream(path));
			this.output.write(wavHeader(0L));
		}

		private synchronized void attach(MicrophoneSystem.MicrophonePcmRecorder recorder) {
			this.recorder = recorder;
		}

		private synchronized void writeFrame(short[] frame) {
			if (this.closed || frame == null) {
				return;
			}
			try {
				ByteBuffer buffer = ByteBuffer.allocate(frame.length * 2).order(ByteOrder.LITTLE_ENDIAN);
				for (short sample : frame) {
					buffer.putShort(sample);
				}
				this.output.write(buffer.array());
				this.samplesWritten += frame.length;
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
			return this.path;
		}

		private void abort() throws IOException {
			closeImmediately();
			deleteQuietly(this.path);
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

		private Path path() {
			return this.path;
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

	private static final class AudioRecordingSession implements RecordingSession {
		private final String sourceKey;
		private final Path outputPath;
		private final BufferedImage coverBackground;
		private final OutputStream output;
		private final PauseClock pauseClock = new PauseClock();
		private volatile boolean paused;
		private boolean closed;
		private long samplesWritten;
		private MicrophoneSystem.MicrophonePcmRecorder recorder;

		private AudioRecordingSession(String sourceKey, Path outputPath, BufferedImage coverBackground) throws IOException {
			this.sourceKey = sourceKey;
			this.outputPath = outputPath;
			this.coverBackground = coverBackground;
			this.output = new BufferedOutputStream(Files.newOutputStream(outputPath));
			writeWavHeader(this.output, 0L);
		}

		private synchronized void attach(MicrophoneSystem.MicrophonePcmRecorder recorder) {
			this.recorder = recorder;
		}

		private synchronized void writeFrame(MicrophoneSystem.PcmFrame frame) {
			if (this.closed || frame == null || !this.pauseClock.activeAtNanos(frame.captureNanos())) {
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
				setPaused(true);
			}
		}

		@Override
		public boolean paused() {
			return this.paused;
		}

		@Override
		public void setPaused(boolean paused) {
			this.paused = this.pauseClock.setPaused(paused);
		}

		@Override
		public long elapsedMs() {
			return this.pauseClock.elapsedMs();
		}

		@Override
		public RecordedMedia finish() throws IOException {
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
			try (SeekableByteChannel channel = Files.newByteChannel(this.outputPath, StandardOpenOption.WRITE)) {
				channel.position(0L);
				channel.write(ByteBuffer.wrap(wavHeader(dataBytes)));
			}
			BufferedImage cover = MonitorCoverPlaceholderRenderer.recordedAudioCover(shortRecordingIdentifier(this.sourceKey), "Диктофон", this.coverBackground, this.sourceKey);
			Path coveredPath;
			try {
				coveredPath = embedCoverAsMp3(cover);
			} catch (IOException exception) {
				Lg2.LOGGER.warn("Failed to embed monitor audio cover", exception);
				coveredPath = this.outputPath;
			}
			return new RecordedMedia(this.sourceKey, coveredPath, GalleryItemKind.AUDIO, "Диктофон", "audio", "lg2-camera:audio:" + this.sourceKey, null);
		}

		private Path embedCoverAsMp3(BufferedImage cover) throws IOException {
			if (cover == null) {
				return this.outputPath;
			}
			Path parent = this.outputPath.getParent();
			Path coverPath = Files.createTempFile(parent, this.sourceKey + "-cover-", ".png");
			Path mp3Path = parent.resolve(this.sourceKey + ".mp3");
			try {
				ImageIO.write(cover, "png", coverPath.toFile());
				List<String> command = List.of(
						"ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
						"-i", this.outputPath.toAbsolutePath().toString(),
						"-i", coverPath.toAbsolutePath().toString(),
						"-map", "0:a:0",
						"-map", "1:v:0",
						"-c:a", "libmp3lame",
						"-q:a", "2",
						"-c:v", "mjpeg",
						"-id3v2_version", "3",
						"-metadata:s:v", "title=Cover",
						"-metadata:s:v", "comment=Cover (front)",
						"-disposition:v:0", "attached_pic",
						mp3Path.toAbsolutePath().toString()
				);
				Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
				try {
					if (!process.waitFor(10, TimeUnit.SECONDS)) {
						process.destroyForcibly();
						throw new IOException("Audio cover embedding timed out");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IOException("Audio cover embedding interrupted", exception);
				}
				if (process.exitValue() != 0 || !Files.isRegularFile(mp3Path) || Files.size(mp3Path) <= 0L) {
					throw new IOException("Audio cover embedding failed");
				}
				deleteQuietly(this.outputPath);
				return mp3Path;
			} finally {
				deleteQuietly(coverPath);
			}
		}

		@Override
		public synchronized void abort() {
			try {
				closeImmediately();
			} catch (IOException ignored) {
			}
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

		private static void writeWavHeader(OutputStream output, long dataBytes) throws IOException {
			output.write(wavHeader(dataBytes));
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

	private record RecordedMedia(String sourceKey, Path path, GalleryItemKind kind, String title, String subtitle, String url, BufferedImage preview) {
	}
}
