package com.lostglade.server;

import com.lostglade.server.monitor.MonitorMediaApp;
import com.lostglade.server.monitor.MonitorYoutubeRelayClient;
import com.lostglade.server.progress.TaskProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

record RenderCacheKey(boolean powered, ScreenViewMode viewMode, int launcherPage, int width, int height) {
}

record OverlayWindowCacheKey(MediaOverlayWindowSnapshot snapshot, int width, int height, int unit) {
}

record OverlayWindowFamilyKey(MediaOverlayWindowType type, int width, int height, int unit) {
}

record WindowedSnapshot<T>(List<T> items, int totalCount, int windowStartIndex) {
	static <T> WindowedSnapshot<T> empty() {
		return new WindowedSnapshot<>(List.of(), 0, 0);
	}
}

record MediaVisualSnapshot(
		ScreenViewMode mode,
		long version,
		BufferedImage frame,
		BufferedImage backgroundFrame,
		BufferedImage playerBackgroundFrame,
		boolean hasMedia,
		boolean galleryBrowser,
		boolean galleryPickerMode,
		boolean gallerySelectionMode,
		int gallerySelectionCount,
		boolean gallerySlideshowEnabled,
		boolean galleryCurrentSaved,
		boolean galleryBackedYoutube,
		boolean musicPlayerLayout,
		boolean streamPlayback,
		boolean playbackControlsVisible,
		boolean loading,
		boolean waitingForLink,
		boolean timelineVisible,
		boolean centerPlayPauseVisible,
		boolean droneControlVisible,
		boolean timelineSeekable,
		int frameIndex,
		int frameCount,
		float timelineFraction,
		float bufferedStartFraction,
		float bufferedEndFraction,
		String timelineLabel,
		boolean paused,
		MediaOverlayMode overlayMode,
		MediaScaleMode scaleMode,
		MediaScaleMode playerBackgroundScaleMode,
		PlayerBackgroundMode playerBackgroundMode,
		boolean galleryBackgroundAvailable,
		String statusText,
		String linkPlaceholder,
		String mediaTitle,
		String mediaSubtitle,
		TaskProgress.Snapshot progress,
		WindowedSnapshot<YoutubeQueueItemSnapshot> mediaListItems,
		WindowedSnapshot<GalleryCardSnapshot> galleryCards,
		boolean actionVisible,
		MediaActionGlyph actionGlyph,
		MediaActionVisualState actionState,
		boolean wallpaperActionVisible,
		MediaActionGlyph wallpaperActionGlyph,
		MediaActionVisualState wallpaperActionState,
		boolean youtubeMusicShuffleEnabled,
		MediaRepeatMode repeatMode,
		boolean youtubeQueueOpen,
		int mediaListScroll,
		int currentMediaListIndex,
		MediaOverlayWindowSnapshot overlayWindow
) {
}

record WallpaperVisualSnapshot(
		BufferedImage frame,
		MediaScaleMode scaleMode,
		PlayerBackgroundMode backgroundMode
) {
}

record YandexMapsVisualSnapshot(
		long version,
		BufferedImage frame,
		String statusText,
		String dimensionLabel,
		double centerX,
		double centerZ,
		double zoomBlocks,
		boolean healthy
) {
}

record RenderTileTarget(
		int tileIndex,
		MapId mapId,
		byte scale,
		boolean locked,
		byte[] baselineFrame
) {
}

record MediaOverlayWindowSnapshot(
		MediaOverlayWindowType type,
		String title,
		String subtitle,
		List<YoutubeQueueItemSnapshot> items,
		int totalItemCount,
		int itemWindowStartIndex,
		GalleryFileMenuSnapshot galleryFile,
		GallerySlideshowSettingsSnapshot gallerySlideshow,
		int scroll,
		int currentIndex,
		boolean shuffleEnabled,
		MediaRepeatMode repeatMode,
		PlayerBackgroundMode playerBackgroundMode,
		boolean galleryBackgroundAvailable,
		MediaScaleMode playerBackgroundScaleMode
) {
}

record GalleryFileMenuSnapshot(
		String title,
		String subtitle,
		boolean saved,
		boolean canRename,
		boolean canShare,
		boolean canWallpaper,
		boolean wallpaperSelected
) {
}

record GallerySlideshowSettingsSnapshot(
		boolean enabled,
		int durationSeconds,
		int minDurationSeconds,
		int maxDurationSeconds
) {
}

record RenderWork(
		ScreenRuntimeKey runtimeKey,
		boolean powered,
		ScreenViewMode viewMode,
		int launcherPage,
		int width,
		int height,
		long mediaVersion,
		MediaVisualSnapshot mediaSnapshot,
		CameraAppVisualSnapshot cameraAppSnapshot,
		MaxVisualSnapshot maxSnapshot,
		YandexMapsVisualSnapshot yandexMapsSnapshot,
		SupportVisualSnapshot supportSnapshot,
		WallpaperVisualSnapshot wallpaperSnapshot,
		boolean transparentOutput,
		UiPoint debugAimCursor,
		List<RenderTileTarget> tileTargets
) {
}

record SupportVisualSnapshot(
		long version,
		List<SupportMessageSnapshot> messages,
		List<SupportAttachmentSnapshot> pendingAttachments,
		List<SupportGalleryFileSnapshot> galleryFiles,
		boolean attachmentPickerOpen,
		boolean waitingForInput,
		boolean telegramConfigured,
		int supportReceiverCount,
		String statusText,
		int scrollOffset,
		int attachmentPickerScroll,
		int totalMessageCount
) {
}

record SupportMessageSnapshot(
		boolean fromSupport,
		String author,
		String text,
		long createdAtMillis,
		long ticketId,
		List<SupportAttachmentSnapshot> attachments,
		boolean delivered,
		boolean read
) {
}

record SupportAttachmentSnapshot(
		String id,
		String title,
		String subtitle,
		String url,
		String localMediaKey,
		GalleryItemKind kind,
		BufferedImage preview
) {
}

record SupportGalleryFileSnapshot(
		int index,
		String title,
		String subtitle,
		String url,
		String localMediaKey,
		GalleryItemKind kind,
		BufferedImage preview,
		boolean selected
) {
}

record CameraAppVisualSnapshot(
		long version,
		BufferedImage previewFrame,
		List<CameraAppDeviceSnapshot> cameras,
		List<CameraAppDeviceSnapshot> microphones,
		int connectedCameraCount,
		int connectedDroneCount,
		int selectedCameraIndex,
		int selectedMicrophoneIndex,
		int cameraScroll,
		int microphoneScroll,
		CameraAppCaptureMode captureMode,
		boolean recording,
		boolean paused,
		long elapsedMs,
		boolean deviceMenuOpen,
		boolean chromeHidden,
		boolean droneControlVisible,
		String statusText
) {
	boolean dynamic() {
		return this.recording && !this.paused;
	}
}

record CameraAppDeviceSnapshot(
		String title,
		String subtitle,
		String url,
		BufferedImage preview,
		boolean selected,
		boolean online
) {
}

record MaxVisualSnapshot(
		long version,
		String accountCode,
		String accountName,
		BufferedImage avatarFrame,
		WindowedSnapshot<MaxContactSnapshot> contacts,
		MaxCallVisualSnapshot call,
		WindowedSnapshot<MaxAvatarCandidateSnapshot> avatarCandidates,
		int avatarPickerScroll,
		WindowedSnapshot<MaxRingtoneCandidateSnapshot> ringtoneCandidates,
		int ringtonePickerScroll,
		WindowedSnapshot<MaxFileShareContactSnapshot> fileShareContacts,
		int fileSharePickerScroll,
		WindowedSnapshot<MaxIncomingFileSnapshot> incomingFiles,
		int notificationScroll,
		int notificationCount,
		int fileShareFileCount,
		int fileShareSelectedCount,
		String fileShareTitle,
		WindowedSnapshot<MaxContactSnapshot> callContactCandidates,
		boolean avatarPickerOpen,
		boolean ringtonePickerOpen,
		boolean fileSharePickerOpen,
		boolean notificationsOpen,
		boolean animatedAvatars,
		boolean ringtonePreviewPlaying,
		String statusText
) {
	boolean dynamic() {
		return this.animatedAvatars || (this.call != null && this.call.dynamic()) || this.ringtonePreviewPlaying || this.notificationsOpen;
	}
}

record MaxContactSnapshot(
		String code,
		String displayName,
		BufferedImage avatarFrame,
		boolean avatarAnimated,
		boolean online,
		boolean ringing,
		boolean active,
		int notificationCount,
		boolean savedContact
) {
}

record MaxCallVisualSnapshot(
		MaxCallPhase phase,
		String peerCode,
		String peerDisplayName,
		BufferedImage peerAvatarFrame,
		boolean peerAvatarAnimated,
		BufferedImage localPreviewFrame,
		BufferedImage remoteFrame,
		List<MaxCallParticipantSnapshot> participants,
		String focusedParticipantCode,
		String statusText,
		boolean cameraEnabled,
		boolean microphoneEnabled,
		List<MaxCameraOptionSnapshot> cameras,
		List<MaxMicrophoneOptionSnapshot> microphones,
		int connectedCameraCount,
		int connectedDroneCount,
		int selectedCameraIndex,
		int microphoneCount,
		int selectedMicrophoneIndex,
		int cameraScroll,
		int microphoneScroll,
		int contactPickerScroll,
		int miniParticipantScroll,
		boolean menuOpen,
		boolean cameraPickerOpen,
		boolean contactPickerOpen,
		boolean selfFocused,
		boolean peerFocused,
		boolean miniParticipantsHidden,
		long elapsedMillis
) {
	boolean dynamic() {
		return this.phase == MaxCallPhase.OUTGOING
				|| this.phase == MaxCallPhase.INCOMING
				|| this.phase == MaxCallPhase.ACTIVE
				|| this.remoteFrame != null
				|| this.participants.stream().anyMatch(participant -> participant != null && participant.videoFrame() != null);
	}
}

record MaxCallParticipantSnapshot(
		String code,
		String displayName,
		BufferedImage avatarFrame,
		Color accentColor,
		boolean avatarAnimated,
		BufferedImage videoFrame,
		boolean self,
		boolean cameraEnabled,
		boolean microphoneEnabled,
		boolean ringing
) {
}

record MaxCameraOptionSnapshot(
		String title,
		String subtitle,
		String url,
		BufferedImage preview,
		boolean selected,
		boolean online
) {
}

record MaxMicrophoneOptionSnapshot(
		int index,
		String title,
		String subtitle,
		String deviceKey,
		boolean selected,
		boolean online
) {
}

record MaxAvatarCandidateSnapshot(
		String title,
		String url,
		String localMediaKey,
		BufferedImage preview
) {
}

record MaxRingtoneCandidateSnapshot(
		String title,
		String subtitle,
		String url,
		String localMediaKey,
		boolean selected,
		boolean playing,
		float timelineFraction
) {
}

record MaxFileShareContactSnapshot(
		String code,
		String displayName,
		BufferedImage avatarFrame,
		boolean online,
		boolean selected
) {
}

record MaxIncomingFileSnapshot(
		String id,
		String senderCode,
		String senderDisplayName,
		BufferedImage senderAvatarFrame,
		boolean senderAvatarAnimated,
		String fileName,
		String subtitle,
		GalleryItemKind kind,
		BufferedImage previewFrame,
		boolean squarePreviewFallback,
		boolean previewPlayable,
		boolean previewActive,
		boolean previewPlaying,
		boolean previewLoading
) {
}

record MediaDispatchKey(
		boolean powered,
		ScreenViewMode viewMode,
		int launcherPage,
		int width,
		int height
) {
}

record PendingMediaLinkRequest(ScreenRuntimeKey screenKey, ScreenViewMode mode, YoutubeLinkRequestAction youtubeAction) {
}

record InFlightMediaLinkRequest(ScreenRuntimeKey screenKey, ScreenViewMode mode, YoutubeLinkRequestAction youtubeAction) {
}

record PendingGalleryRenameRequest(ScreenRuntimeKey screenKey, int galleryIndex, String itemUrl) {
}

record YoutubeQueueItemSnapshot(
		int queueIndex,
		String title,
		String subtitle,
		long durationMs,
		BufferedImage previewFrame,
		boolean squarePreviewFallback,
		boolean current,
		float cacheFraction,
		boolean cacheActive,
		boolean cacheComplete
) {
}

record YoutubeQueuePreloadDiff(List<String> retainUrls, List<String> releaseUrls) {
	static final YoutubeQueuePreloadDiff EMPTY = new YoutubeQueuePreloadDiff(List.of(), List.of());

	boolean isEmpty() {
		return this.retainUrls.isEmpty() && this.releaseUrls.isEmpty();
	}
}

record YoutubeMusicQueuePreloadDiff(List<String> retainUrls, List<String> releaseUrls) {
	static final YoutubeMusicQueuePreloadDiff EMPTY = new YoutubeMusicQueuePreloadDiff(List.of(), List.of());

	boolean isEmpty() {
		return this.retainUrls.isEmpty() && this.releaseUrls.isEmpty();
	}
}

record GalleryItem(
		String title,
		String subtitle,
		String url,
		String localMediaKey,
		MonitorMediaApp.LoadedMedia media,
		BufferedImage preview,
		GalleryItemKind kind
) {
}

record PersistedGalleryItem(String title, String subtitle, String url, GalleryItemKind kind, String localMediaKey) {
}

record GalleryRemovalResult(GalleryItem removedItem, boolean selectionRetained) {
}

record GalleryCacheCandidate(String url, String localMediaKey, GalleryItemKind kind) {
}

record GalleryCacheReferenceSnapshot(
		Set<String> localMediaKeys,
		Set<String> galleryMediaUrls,
		Set<String> galleryMusicUrls,
		Set<String> galleryYoutubeUrls,
		Set<String> activeMediaUrls,
		Set<String> activeMusicUrls,
		Set<String> activeYoutubeUrls
) {
}

record PersistedWallpaperState(String url, MediaScaleMode scaleMode, PlayerBackgroundMode backgroundMode) {
}

record PersistedPlayerBackgroundState(String url, MediaScaleMode scaleMode) {
}

record GalleryItemLoadResult(
		ScreenRuntimeKey screenKey,
		String title,
		String subtitle,
		String url,
		String localMediaKey,
		GalleryItemKind kind,
		MonitorMediaApp.LoadedMedia loadedMedia,
		MonitorMediaApp.LoadedVideo loadedVideo,
		boolean openWhenReady,
		int preferredIndex,
		long sessionGeneration,
		String error
) {
}

record SavedGalleryMediaPersistResult(
		String url,
		String savedMediaKey,
		String error
) {
}

record WallpaperLoadResult(
		ScreenRuntimeKey screenKey,
		String url,
		String localMediaKey,
		MonitorMediaApp.LoadedMedia loadedMedia,
		long sessionGeneration,
		String error
) {
}

record PlayerBackgroundLoadResult(
		ScreenRuntimeKey screenKey,
		String url,
		String localMediaKey,
		MonitorMediaApp.LoadedMedia loadedMedia,
		long sessionGeneration,
		String error
) {
}

record GalleryCardSnapshot(
		int index,
		String title,
		String subtitle,
		String tertiary,
		String statusLabel,
		boolean statusActive,
		String sourceLabel,
		boolean metadataVisible,
		GalleryItemKind kind,
		boolean animatedMedia,
		boolean animated,
		BufferedImage preview,
		boolean current,
		boolean bulkSelectionMode,
		boolean selectedForBulk,
		boolean loaded,
		boolean disconnectVisible
) {
}

final class OverlayWindowRenderState {
	volatile BufferedImage image;
	volatile CompletableFuture<BufferedImage> future;
	volatile long lastAccessNanos;
}

record PlayerMediaFocus(ScreenRuntimeKey screenKey, long expiresAtMillis) {
}

record PlacementNeighbor(BlockPos pos, int connectionMask, double distance) {
	PlacementNeighbor(BlockPos pos, int connectionMask) {
		this(pos, connectionMask, 0.0D);
	}
}

record MediaLoadResult(
		ScreenRuntimeKey screenKey,
		UUID requesterUuid,
		String url,
		String title,
		String subtitle,
		GalleryItemKind kind,
		MonitorMediaApp.LoadedMedia loadedMedia,
		MonitorMediaApp.LoadedVideo loadedVideo,
		long sessionGeneration,
		String error
) {
}

record YoutubeLoadResult(
		ScreenRuntimeKey screenKey,
		UUID requesterUuid,
		String url,
		ScreenViewMode targetMode,
		PlaybackStreamKind streamKind,
		String subtitle,
		MonitorYoutubeRelayClient.SessionLoadResponse loadResponse,
		String error
) {
}

record YoutubeQueueResolveResult(
		ScreenRuntimeKey screenKey,
		UUID requesterUuid,
		ScreenViewMode mode,
		String url,
		MonitorYoutubeRelayClient.QueueResolveResponse queueResponse,
		YoutubeLinkRequestAction action,
		long sessionGeneration,
		String error
) {
}

record YoutubeMusicLoadResult(
		ScreenRuntimeKey screenKey,
		UUID requesterUuid,
		String url,
		String title,
		String artist,
		MonitorMediaApp.LoadedVideo loadedVideo,
		MonitorYoutubeRelayClient.SessionLoadResponse relayLoadResponse,
		int queueIndex,
		String error
) {
}

record YoutubeSnapshotResult(
		ScreenRuntimeKey screenKey,
		MonitorYoutubeRelayClient.SessionSnapshot snapshot,
		String error
) {
}

record LiveCameraSnapshotResult(
		ScreenRuntimeKey screenKey,
		String url,
		BufferedImage previewFrame,
		BufferedImage fullFrame,
		String error
) {
}

enum MediaBottomAction {
	WALLPAPER,
	PRIMARY,
	QUEUE,
	REPEAT,
	SCALE
}

enum PlayerUiIcon {
	SEARCH("/monitor/ui_icons/search.png"),
	SHUFFLE("/monitor/ui_icons/shuffle.png"),
	REPEAT("/monitor/ui_icons/repeat.png"),
	REPEAT_ONE("/monitor/ui_icons/repeat_one.png"),
	VIDEO_AI("/monitor/ui_icons/video_ai.png"),
	DROPDOWN("/monitor/ui_icons/dropdown.png"),
	MENU("/monitor/ui_icons/menu.png"),
	QUEUE("/monitor/ui_icons/queue.png"),
	DOWNLOAD("/monitor/ui_icons/download.png"),
	TRASH("/monitor/ui_icons/trash.png"),
	EDIT("/monitor/ui_icons/edit.png"),
	SETTINGS("/monitor/ui_icons/settings.png"),
	SETTINGS_3("/monitor/ui_icons/settings_3.png"),
	WALLPAPER("/monitor/ui_icons/wallpaper.png"),
	CHECK("/monitor/ui_icons/check.png"),
	CHECKBOX_LINE("/monitor/ui_icons/checkbox_line.png"),
	CHECKBOX_FILL("/monitor/ui_icons/checkbox_fill.png"),
	LIST_CHECK_3_LINE("/monitor/ui_icons/list_check_3_line.png"),
	TOGGLE_LEFT("/monitor/ui_icons/toggle_left.png"),
	TOGGLE_RIGHT("/monitor/ui_icons/toggle_right.png"),
	PLAY("/monitor/ui_icons/play.png"),
	PAUSE("/monitor/ui_icons/pause.png"),
	FILE_MUSIC("/monitor/ui_icons/file_music.png"),
	MEDIA_VIDEO("/monitor/ui_icons/media_video.png"),
	MEDIA_IMAGE("/monitor/ui_icons/media_image.png"),
	MEDIA_GIF("/monitor/ui_icons/media_gif.png"),
	MEDIA_AUDIO("/monitor/ui_icons/media_audio.png"),
	FIT("/monitor/ui_icons/fit.png"),
	FILL("/monitor/ui_icons/fill.png"),
	STRETCH("/monitor/ui_icons/stretch.png"),
	CLOSE("/monitor/ui_icons/close.png"),
	BACK("/monitor/ui_icons/back.png"),
	DRONE("/monitor/ui_icons/drone.png"),
	CAMERA("/monitor/ui_icons/camera.png"),
	CALL_ACCEPT("/monitor/ui_icons/call_accept.png"),
	CALL_DECLINE("/monitor/ui_icons/call_decline.png"),
	MIC("/monitor/ui_icons/mic.png"),
	MIC_OFF("/monitor/ui_icons/mic_off.png"),
	VIDEO_CAMERA("/monitor/ui_icons/video_camera.png"),
	VIDEO_CAMERA_OFF("/monitor/ui_icons/video_camera_off.png"),
	DEVICE_SELECT("/monitor/ui_icons/device_select.png"),
	CONTACT_ADD("/monitor/ui_icons/contact_add.png"),
	FULLSCREEN_EXIT("/monitor/ui_icons/fullscreen_exit.png"),
	GRID_FILL("/monitor/ui_icons/grid_fill.png"),
	SIGNAL("/monitor/ui_icons/signal.png"),
	OFFLINE("/monitor/ui_icons/offline.png"),
	UNLINK("/monitor/ui_icons/unlink.png"),
	LOCATION("/monitor/ui_icons/location.png"),
	AIMING_2("/monitor/ui_icons/aiming_2.png"),
	ADD("/monitor/ui_icons/add.png"),
	MINUS("/monitor/ui_icons/minus.png"),
	DIRECTIONS_2_LINE("/monitor/ui_icons/directions_2_line.png"),
	SEND_PLANE("/monitor/ui_icons/send_plane.png"),
	NOTIFICATION("/monitor/ui_icons/notification.png"),
	TARGET("/monitor/ui_icons/target.png"),
	BASE_STATION_2("/monitor/ui_icons/base_station_2.png");

	private final String resourcePath;

	PlayerUiIcon(String resourcePath) {
		this.resourcePath = resourcePath;
	}

	String resourcePath() {
		return this.resourcePath;
	}
}

record PlayerUiIconTintKey(PlayerUiIcon icon, int argb) {
}

record MapPacketUpdate(
		MapId mapId,
		byte scale,
		boolean locked,
		int startX,
		int startY,
		int width,
		int height,
		byte[] frame
) {
}

record PreparedMapUpdate(
		MapId mapId,
		byte scale,
		boolean locked,
		int startX,
		int startY,
		int width,
		int height,
		byte[] frame,
		byte[] fullFrame,
		byte[] baselineFrame
) {
}

record RenderedTileBatch(byte[][] renderedTiles, List<PreparedMapUpdate> updates) {
}

record TileFramePatch(int startX, int startY, int width, int height, byte[] frame) {
}

record PreparedRenderedTiles(byte[][] renderedTiles, TileFramePatch[] tilePatches, long baselineGeneration) {
}

final class MediaRuntimeState {
	ScreenViewMode mode;
	PlaybackStreamKind streamKind;
	MonitorMediaApp.LoadedMedia loadedMedia;
	BufferedImage streamFrame;
	BufferedImage loadingBackdropFrame;
	String sourceUrl;
	String relaySessionId;
	String audioStreamUrl;
	String mediaTitle;
	String mediaSubtitle;
	int frameIndex;
	long youtubeFrameSequence;
	long positionMs;
	long durationMs;
	long bufferedStartMs;
	long bufferedEndMs;
	long audioSyncToken;
	long version;
	MediaOverlayMode overlayMode;
	MediaScaleMode scaleMode;
	GallerySurfaceMode gallerySurfaceMode;
	PlayerBackgroundMode playerBackgroundMode;
	boolean liveStream;
	boolean audioPlaceholder;
	boolean userPaused;
	boolean waitingForLink;
	boolean loading;
	boolean galleryHydrationLoading;
	boolean galleryDeleteConfirmOpen;
	boolean galleryFileMenuOpen;
	boolean playerBackgroundMenuOpen;
	boolean gallerySlideshowSettingsOpen;
	String statusText;
	boolean galleryHydrated;
	boolean wallpaperHydrated;
	boolean playerBackgroundHydrated;
	boolean playerBackgroundModeHydrated;
	final List<GalleryItem> galleryItems;
	final Set<String> galleryLoadingUrls;
	MonitorMediaApp.LoadedMedia wallpaperMedia;
	String wallpaperUrl;
	MediaScaleMode wallpaperScaleMode;
	PlayerBackgroundMode wallpaperBackgroundMode;
	int wallpaperFrameIndex;
	boolean wallpaperLoading;
	MonitorMediaApp.LoadedMedia playerBackgroundMedia;
	String playerBackgroundUrl;
	MediaScaleMode playerBackgroundScaleMode;
	int playerBackgroundFrameIndex;
	boolean playerBackgroundLoading;
	String galleryPendingOpenUrl;
	int galleryPendingOpenIndex;
	int galleryPendingOpenRequestId;
	int galleryNextOpenRequestId;
	int galleryIndex;
	int galleryScroll;
	boolean galleryBulkSelectionMode;
	final Set<String> galleryBulkSelectedKeys;
	boolean galleryPreloadStatusRefreshScheduled;
	int galleryPreloadStatusRefreshStep;
	boolean gallerySlideshowEnabled;
	int gallerySlideshowDurationSeconds;
	long gallerySlideshowAdvanceAtMillis;
	boolean playerBackgroundGalleryPickerOpen;
	ScreenViewMode playerBackgroundGalleryPickerReturnMode;
	GallerySurfaceMode playerBackgroundGalleryPickerReturnSurfaceMode;
	boolean preserveRuntimeOnNextViewModeTransition;
	boolean supportAttachmentReturnToSupport;
	boolean downloadInProgress;
	String downloadTargetUrl;
	UUID downloadRequesterUuid;
	long downloadStartedAtMillis;
	String downloadCompletedUrl;
	long downloadCompletedUntilMillis;
	final List<YoutubeQueueItem> youtubeQueue;
	final Set<String> retainedYoutubePreloadUrls;
	final Set<String> retainedYoutubeMusicUrls;
	boolean youtubeMusicShuffleEnabled;
	MediaRepeatMode repeatMode;
	final List<Integer> youtubeMusicShuffleOrder;
	int youtubeMusicShuffleCursor;
	int youtubeQueueIndex;
	int youtubeQueueScroll;
	long youtubeQueueTotalDurationMs;
	boolean youtubeQueueOpen;
	boolean youtubeQueueCacheStatusRefreshScheduled;
	boolean youtubeReturnToGallery;
	boolean liveCameraCaptureInFlight;
	byte[] pendingLiveCameraPixels;
	boolean liveCameraDecodeScheduled;
	long liveCameraLastFrameAtMillis;
	byte[][] liveCameraBufferedTiles;
	byte[][] liveCameraDisplayedTiles;
	long liveCameraDisplayedGeneration;
	long nextLiveCameraPreviewDecodeAtMillis;
	PreparedRenderedTiles pendingLiveCameraPreparedTiles;
	String pendingLiveCameraApplyUrl;
	boolean liveCameraApplyScheduled;
	long nextLiveCameraGallerySyncAtMillis;
	long nextLoadedMediaFrameAtMillis;
	long nextWallpaperFrameAtMillis;
	long nextPlayerBackgroundFrameAtMillis;
	int activeRenderJobs;
	boolean rerenderRequested;
	MediaDispatchKey lastDispatchKey;
	ScheduledFuture<?> playbackFuture;
	ScheduledFuture<?> backgroundFuture;
	long nextProgressRenderAtMillis;
	long sessionGeneration;
	long galleryHydrationRequestId;
	Boolean pendingAudioPauseState;
	boolean pendingAudioPositionActive;
	long pendingAudioPositionMs;
	long pendingAudioIssuedAtMillis;
	final Runnable progressListener;
	TaskProgress progress;

	MediaRuntimeState(ScreenViewMode mode, Runnable progressListener) {
		this.mode = mode;
		this.streamKind = PlaybackStreamKind.NONE;
		this.overlayMode = MediaOverlayMode.CONTROLS;
		this.scaleMode = MediaScaleMode.FIT;
		this.gallerySurfaceMode = GallerySurfaceMode.BROWSER;
		this.playerBackgroundMode = null;
		this.liveStream = false;
		this.audioPlaceholder = true;
		this.userPaused = false;
		this.waitingForLink = false;
		this.loading = false;
		this.galleryHydrationLoading = false;
		this.galleryDeleteConfirmOpen = false;
		this.galleryFileMenuOpen = false;
		this.playerBackgroundMenuOpen = false;
		this.gallerySlideshowSettingsOpen = false;
		this.pendingAudioPauseState = null;
		this.pendingAudioPositionActive = false;
		this.pendingAudioPositionMs = 0L;
		this.pendingAudioIssuedAtMillis = 0L;
		this.galleryHydrated = false;
		this.wallpaperHydrated = false;
		this.playerBackgroundHydrated = false;
		this.playerBackgroundModeHydrated = false;
		this.version = 0L;
		this.statusText = "";
		this.mediaSubtitle = "";
		this.loadingBackdropFrame = null;
		this.galleryItems = new ArrayList<>();
		this.galleryLoadingUrls = new HashSet<>();
		this.wallpaperMedia = null;
		this.wallpaperUrl = null;
		this.wallpaperScaleMode = MediaScaleMode.FIT;
		this.wallpaperBackgroundMode = PlayerBackgroundMode.EMPTY;
		this.wallpaperFrameIndex = 0;
		this.wallpaperLoading = false;
		this.playerBackgroundMedia = null;
		this.playerBackgroundUrl = null;
		this.playerBackgroundScaleMode = MediaScaleMode.FILL;
		this.playerBackgroundFrameIndex = 0;
		this.playerBackgroundLoading = false;
		this.galleryPendingOpenUrl = null;
		this.galleryPendingOpenIndex = -1;
		this.galleryPendingOpenRequestId = 0;
		this.galleryNextOpenRequestId = 0;
		this.galleryIndex = -1;
		this.galleryScroll = 0;
		this.galleryBulkSelectionMode = false;
		this.galleryBulkSelectedKeys = new LinkedHashSet<>();
		this.galleryPreloadStatusRefreshScheduled = false;
		this.galleryPreloadStatusRefreshStep = 0;
		this.gallerySlideshowEnabled = false;
		this.gallerySlideshowDurationSeconds = 25;
		this.gallerySlideshowAdvanceAtMillis = 0L;
		this.playerBackgroundGalleryPickerOpen = false;
		this.playerBackgroundGalleryPickerReturnMode = null;
		this.playerBackgroundGalleryPickerReturnSurfaceMode = null;
		this.preserveRuntimeOnNextViewModeTransition = false;
		this.downloadInProgress = false;
		this.downloadTargetUrl = null;
		this.downloadRequesterUuid = null;
		this.downloadStartedAtMillis = 0L;
		this.downloadCompletedUrl = null;
		this.downloadCompletedUntilMillis = 0L;
		this.youtubeQueue = new ArrayList<>();
		this.retainedYoutubePreloadUrls = new HashSet<>();
		this.retainedYoutubeMusicUrls = new HashSet<>();
		this.youtubeMusicShuffleEnabled = false;
		this.repeatMode = MediaRepeatMode.OFF;
		this.youtubeMusicShuffleOrder = new ArrayList<>();
		this.youtubeMusicShuffleCursor = -1;
		this.youtubeQueueIndex = -1;
		this.youtubeQueueScroll = 0;
		this.youtubeQueueTotalDurationMs = 0L;
		this.youtubeQueueOpen = false;
		this.youtubeQueueCacheStatusRefreshScheduled = false;
		this.youtubeReturnToGallery = false;
		this.liveCameraCaptureInFlight = false;
		this.pendingLiveCameraPixels = null;
		this.liveCameraDecodeScheduled = false;
		this.liveCameraLastFrameAtMillis = 0L;
		this.liveCameraBufferedTiles = null;
		this.liveCameraDisplayedTiles = null;
		this.liveCameraDisplayedGeneration = 0L;
		this.nextLiveCameraPreviewDecodeAtMillis = 0L;
		this.pendingLiveCameraPreparedTiles = null;
		this.pendingLiveCameraApplyUrl = null;
		this.liveCameraApplyScheduled = false;
		this.nextLiveCameraGallerySyncAtMillis = 0L;
		this.nextLoadedMediaFrameAtMillis = 0L;
		this.nextWallpaperFrameAtMillis = 0L;
		this.nextPlayerBackgroundFrameAtMillis = 0L;
		this.activeRenderJobs = 0;
		this.nextProgressRenderAtMillis = 0L;
		this.backgroundFuture = null;
		this.sessionGeneration = 1L;
		this.galleryHydrationRequestId = 0L;
		this.progressListener = progressListener;
		this.progress = new TaskProgress(progressListener);
	}

	static MediaRuntimeState fresh(ScreenViewMode mode, String statusText, Runnable progressListener) {
		MediaRuntimeState state = new MediaRuntimeState(mode, progressListener);
		state.statusText = statusText;
		return state;
	}
}

record YoutubeQueueItem(String title, String subtitle, long durationMs, String url) {
}
