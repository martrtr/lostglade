package com.lostglade.server.monitor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lostglade.Lg2;
import com.lostglade.server.progress.TaskProgress;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.lang.ref.SoftReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.TreeMap;
import java.util.stream.Stream;

public final class MonitorYoutubeRelayClient {
	private static final Gson GSON = new Gson();
	private static final Map<String, RelaySession> SESSIONS = new ConcurrentHashMap<>();
	private static final Map<String, QueuePreloadState> QUEUE_PRELOADS = new ConcurrentHashMap<>();
	private static final Set<String> QUEUE_PREVIEW_DOWNLOADS = ConcurrentHashMap.newKeySet();
	private static final ScheduledExecutorService CLEANUP_EXECUTOR = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("lg2-youtube-cleanup"));
	private static final ExecutorService PRELOAD_EXECUTOR = Executors.newFixedThreadPool(2, daemonThreadFactory("lg2-youtube-queue"));
	private static final ExecutorService QUEUE_PREVIEW_EXECUTOR = Executors.newFixedThreadPool(2, daemonThreadFactory("lg2-youtube-preview"));
	private static final String DEFAULT_YT_DLP_BIN = "yt-dlp";
	private static final String DEFAULT_FFMPEG_BIN = "ffmpeg";
	private static final String DEFAULT_FFPROBE_BIN = "ffprobe";
	private static final int QUEUE_PREVIEW_CONNECT_TIMEOUT_MS = 8000;
	private static final int QUEUE_PREVIEW_REQUEST_TIMEOUT_MS = 15000;
	private static final int MAX_QUEUE_PREVIEW_BYTES = 4 * 1024 * 1024;
	private static final HttpClient QUEUE_PREVIEW_HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofMillis(QUEUE_PREVIEW_CONNECT_TIMEOUT_MS))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final String YOUTUBE_VIDEO_STREAM_FORMAT = "bestvideo[height<=480][vcodec^=avc1][protocol=https]"
			+ "/bestvideo[height<=480][ext=mp4][protocol=https]"
			+ "/bestvideo[height<=480][protocol=https]"
			+ "/bestvideo[height<=480][vcodec!=none]"
			+ "/bestvideo[vcodec^=avc1][protocol=https]"
			+ "/bestvideo[ext=mp4][protocol=https]"
			+ "/bestvideo[vcodec!=none]"
			+ "/best[height<=480][vcodec!=none]"
			+ "/best[vcodec!=none]"
			+ "/best";
	private static final String YOUTUBE_AUDIO_STREAM_FORMAT = "bestaudio[ext=m4a][acodec!=none][protocol=https]"
			+ "/bestaudio[acodec!=none][protocol=https]"
			+ "/bestaudio[acodec!=none]"
			+ "/best[acodec!=none]";
	private static final double FRAME_RATE = readDoubleSetting("LG2_YT_FRAME_RATE", "lg2.youtube.frameRate", 12.0D);
	private static final int FRAME_WIDTH = readIntSetting("LG2_YT_FRAME_WIDTH", "lg2.youtube.frameWidth", 480);
	private static final int DIRECT_FRAME_WIDTH_MAX = Math.max(FRAME_WIDTH, readIntSetting("LG2_YT_DIRECT_FRAME_WIDTH_MAX", "lg2.youtube.directFrameWidthMax", 1536));
	private static final int DIRECT_FRAME_WIDTH_MIN = Math.max(FRAME_WIDTH, readIntSetting("LG2_YT_DIRECT_FRAME_WIDTH_MIN", "lg2.youtube.directFrameWidthMin", 640));
	private static final long SESSION_IDLE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(readIntSetting("LG2_YT_IDLE_TIMEOUT_SEC", "lg2.youtube.idleTimeoutSec", 600));
	private static final int COMMAND_TIMEOUT_SEC = readIntSetting("LG2_YT_COMMAND_TIMEOUT_SEC", "lg2.youtube.commandTimeoutSec", 45);
	private static final int GALLERY_VIDEO_DOWNLOAD_TIMEOUT_SEC = readIntSetting("LG2_YT_GALLERY_VIDEO_DOWNLOAD_TIMEOUT_SEC", "lg2.youtube.galleryVideoDownloadTimeoutSec", 1800);
	private static final int GALLERY_VIDEO_VALIDATE_TIMEOUT_SEC = readIntSetting("LG2_YT_GALLERY_VIDEO_VALIDATE_TIMEOUT_SEC", "lg2.youtube.galleryVideoValidateTimeoutSec", 30);
	private static final int STREAM_START_TIMEOUT_SEC = readIntSetting("LG2_YT_STREAM_START_TIMEOUT_SEC", "lg2.youtube.streamStartTimeoutSec", 20);
	private static final long PREVIEW_CACHE_BUCKET_MS = Math.max(1L, Math.round(1000.0D / Math.max(1.0D, FRAME_RATE)));
	private static final int MAX_CACHED_PREVIEW_FRAMES = 3600;
	private static final long QUEUE_PRELOAD_DURATION_MS = TimeUnit.SECONDS.toMillis(readIntSetting("LG2_YT_QUEUE_PRELOAD_SEC", "lg2.youtube.queuePreloadSec", 20));
	// This cache contains compressed JPEG frames, not source video. A 30 minute look-ahead at
	// 12 FPS creates more than 21k images per monitor and makes ffmpeg decode continuously,
	// starving both map rendering and the independent audio decoder. Keep a short playback
	// cushion instead; sequential prefetch extends it as the video advances.
	private static final long CURRENT_VIDEO_CACHE_BEHIND_MS = readCacheWindowMillis(
			"LG2_YT_CURRENT_CACHE_BEHIND_SEC", "lg2.youtube.currentCacheBehindSec",
			"LG2_YT_CURRENT_CACHE_BEHIND_MIN", "lg2.youtube.currentCacheBehindMin", 8
	);
	private static final long CURRENT_VIDEO_CACHE_AHEAD_MS = readCacheWindowMillis(
			"LG2_YT_CURRENT_CACHE_AHEAD_SEC", "lg2.youtube.currentCacheAheadSec",
			"LG2_YT_CURRENT_CACHE_AHEAD_MIN", "lg2.youtube.currentCacheAheadMin", 24
	);
	private static final int MAX_SESSION_CACHED_PREVIEW_FRAMES = Math.max(
			512,
			Math.min(
					MAX_CACHED_PREVIEW_FRAMES,
					(int) Math.ceil((CURRENT_VIDEO_CACHE_BEHIND_MS + CURRENT_VIDEO_CACHE_AHEAD_MS) / (double) PREVIEW_CACHE_BUCKET_MS) + 64
			)
	);
	private static final long CACHE_LOOKUP_TOLERANCE_MS = Math.max(100L, PREVIEW_CACHE_BUCKET_MS * 2L);
	private static final long CONTIGUOUS_BUFFER_TOLERANCE_MS = PREVIEW_CACHE_BUCKET_MS + CACHE_LOOKUP_TOLERANCE_MS;
	private static final long PROCESS_STOP_TIMEOUT_MS = 250L;
	private static volatile Path persistentPreloadCacheRoot = Path.of(System.getProperty("user.dir"), "cache", "lg2-monitor", "youtube-preload");

	static {
		CLEANUP_EXECUTOR.scheduleAtFixedRate(MonitorYoutubeRelayClient::cleanupExpiredSessions, 30L, 30L, TimeUnit.SECONDS);
	}

	private MonitorYoutubeRelayClient() {
	}

	public static SessionLoadResponse load(String sessionId, String rawUrl, TaskProgress progress) throws IOException {
		validateYoutubeUrl(rawUrl);
		if (progress != null) {
			progress.setIndeterminate("CONNECTING");
		}
		RelaySession session = SESSIONS.computeIfAbsent(sessionId, RelaySession::new);
		session.touch();
		SessionLoadResponse response = session.load(rawUrl.trim());
		if (progress != null) {
			progress.setIndeterminate("LOADING");
		}
		return response;
	}

	public static SessionLoadResponse loadDirect(
			String sessionId,
			String sourceId,
			String title,
			String videoInput,
			String audioInput,
			long durationMs,
			TaskProgress progress
	) throws IOException {
		return loadDirect(sessionId, sourceId, title, videoInput, audioInput, durationMs, null, FRAME_WIDTH, progress);
	}

	public static SessionLoadResponse loadDirect(
			String sessionId,
			String sourceId,
			String title,
			String videoInput,
			String audioInput,
			long durationMs,
			BufferedImage staticFrame,
			int preferredFrameWidth,
			TaskProgress progress
	) throws IOException {
		if (videoInput == null || videoInput.isBlank()) {
			throw new IOException("Video input is required");
		}
		if (progress != null) {
			progress.setIndeterminate("CONNECTING");
		}
		RelaySession session = SESSIONS.computeIfAbsent(sessionId, RelaySession::new);
		session.touch();
		SessionLoadResponse response = session.loadDirect(
				sourceId == null || sourceId.isBlank() ? videoInput.trim() : sourceId.trim(),
				title == null || title.isBlank() ? "Video" : title.trim(),
				videoInput.trim(),
				audioInput == null ? "" : audioInput.trim(),
				Math.max(0L, durationMs),
				staticFrame,
				Math.max(FRAME_WIDTH, preferredFrameWidth)
		);
		if (progress != null) {
			progress.setIndeterminate("LOADING");
		}
		return response;
	}

	public static QueueResolveResponse resolveQueue(String rawUrl) throws IOException {
		validateYoutubeUrl(rawUrl);
		return resolveYoutubeQueue(rawUrl.trim());
	}

	public static SessionSnapshot snapshot(String sessionId, long knownFrameSequence) throws IOException {
		RelaySession session = requireSession(sessionId);
		session.touch();
		return session.snapshotWithPrefetch(knownFrameSequence);
	}

	public static void updateStaticFrame(String sessionId, String sourceId, BufferedImage staticFrame) {
		if (sessionId == null || sessionId.isBlank() || staticFrame == null) {
			return;
		}
		RelaySession session = SESSIONS.get(sessionId);
		if (session == null) {
			return;
		}
		session.updateStaticFrame(sourceId, staticFrame);
	}

	public static void pause(String sessionId) throws IOException {
		requireSession(sessionId).pause();
	}

	public static void resume(String sessionId) throws IOException {
		requireSession(sessionId).resume();
	}

	public static void seek(String sessionId, long positionMs) throws IOException {
		requireSession(sessionId).seek(Math.max(0L, positionMs));
	}

	public static void requestFullCache(String sessionId) throws IOException {
		requireSession(sessionId).requestFullCache();
	}

	public static long queuePreloadDurationMs() {
		return QUEUE_PRELOAD_DURATION_MS;
	}

	public static void persistQueueEntryFromSession(String sessionId, String rawUrl) throws IOException {
		if (!looksLikeYoutubeUrl(rawUrl)) {
			return;
		}
		requireSession(sessionId).persistQueuePreload(rawUrl.trim());
	}

	public static DownloadedGalleryVideo downloadGalleryVideo(String rawUrl) throws IOException {
		validateYoutubeUrl(rawUrl);
		String url = rawUrl.trim();
		JsonObject metadata = resolveYoutubeMetadata(url);
		String title = getNonBlankString(metadata, "title", "YouTube");
		long durationMs = Math.round(getDouble(metadata, "duration", 0.0D) * 1000.0D);
		boolean live = getBoolean(metadata, "is_live", false)
				|| "is_live".equalsIgnoreCase(getString(metadata, "live_status", ""));
		if (live) {
			throw new IOException("Live YouTube videos cannot be saved to gallery");
		}
		Path downloadDir = persistentPreloadCacheRoot.resolve("gallery-video-downloads").resolve(urlCacheKey(url));
		deleteDirectoryQuietly(downloadDir, persistentPreloadCacheRoot);
		Files.createDirectories(downloadDir);
		try {
			Path outputTemplatePath = downloadDir.resolve("video.%(ext)s");
			runTextCommand(List.of(
					ytDlpBin(),
					"-f",
					"bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best[ext=mp4]/best",
					"--merge-output-format",
					"mp4",
					"--recode-video",
					"mp4",
					"--no-playlist",
					"-o",
					outputTemplatePath.toString(),
					url
			), GALLERY_VIDEO_DOWNLOAD_TIMEOUT_SEC);
			Path videoPath = downloadedGalleryVideoPath(downloadDir);
			validateGalleryVideoFile(videoPath, durationMs);
			Path stablePath = downloadDir.resolve("video.mp4");
			if (!videoPath.equals(stablePath)) {
				moveFileReplacing(videoPath, stablePath);
			}
			return new DownloadedGalleryVideo(title, durationMs, stablePath, downloadDir);
		} catch (IOException exception) {
			deleteDirectoryQuietly(downloadDir, persistentPreloadCacheRoot);
			throw exception;
		}
	}

	public static void setCacheDirectory(Path directory) {
		if (directory != null) {
			persistentPreloadCacheRoot = directory;
		}
	}

	public static void deletePersistentQueueEntry(String rawUrl) {
		if (!looksLikeYoutubeUrl(rawUrl)) {
			return;
		}
		deleteDirectoryQuietly(persistentQueuePreloadDir(rawUrl.trim()), persistentPreloadCacheRoot);
	}

	public static void close(String sessionId) throws IOException {
		RelaySession session = SESSIONS.remove(sessionId);
		if (session == null) {
			return;
		}
		session.close();
	}

	public static void retainQueueEntry(String rawUrl) {
		if (!looksLikeYoutubeUrl(rawUrl)) {
			return;
		}
		String url = rawUrl.trim();
		primeQueueEntryPreview(url);
		QUEUE_PRELOADS.compute(url, (ignored, existing) -> {
			QueuePreloadState state = existing != null ? existing : new QueuePreloadState(url);
			state.retain();
			return state;
		});
	}

	public static void releaseQueueEntry(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			return;
		}
		String url = rawUrl.trim();
		QueuePreloadState state = QUEUE_PRELOADS.get(url);
		if (state == null) {
			return;
		}
		if (state.release()) {
			QUEUE_PRELOADS.remove(url, state);
		}
	}

	public static boolean isQueueEntryLoaded(String rawUrl) {
		if (!looksLikeYoutubeUrl(rawUrl)) {
			return false;
		}
		String url = rawUrl.trim();
		QueuePreloadState state = QUEUE_PRELOADS.get(url);
		if (state != null) {
			return state.isLoaded();
		}
		return isQueuePreloadReady(loadPersistentQueuePreload(url));
	}

	public static QueueEntryCacheStatus queueEntryCacheStatus(String rawUrl) {
		if (!looksLikeYoutubeUrl(rawUrl)) {
			return new QueueEntryCacheStatus(0.0F, false, false);
		}
		String url = rawUrl.trim();
		QueuePreloadState state = QUEUE_PRELOADS.get(url);
		if (state != null) {
			return state.cacheStatus();
		}
		QueuePreloadSnapshot snapshot = loadPersistentQueuePreload(url);
		if (isQueuePreloadReady(snapshot)) {
			return new QueueEntryCacheStatus(1.0F, false, true);
		}
		return new QueueEntryCacheStatus(0.0F, false, false);
	}

	public static BufferedImage queueEntryPreview(String rawUrl) {
		if (!looksLikeYoutubeUrl(rawUrl)) {
			return null;
		}
		String url = rawUrl.trim();
		BufferedImage preview = readCachedQueuePreview(url);
		if (preview != null) {
			return preview;
		}
		primeQueueEntryPreview(url);
		return null;
	}

	public static void primeQueueEntryPreview(String rawUrl) {
		if (!looksLikeYoutubeUrl(rawUrl)) {
			return;
		}
		String url = rawUrl.trim();
		if (readCachedQueuePreview(url) != null) {
			return;
		}
		if (!QUEUE_PREVIEW_DOWNLOADS.add(url)) {
			return;
		}
		QUEUE_PREVIEW_EXECUTOR.execute(() -> {
			try {
				cacheQueuePreview(url);
			} finally {
				QUEUE_PREVIEW_DOWNLOADS.remove(url);
			}
		});
	}

	public static void shutdown() {
		for (RelaySession session : List.copyOf(SESSIONS.values())) {
			session.closeQuietly();
		}
		SESSIONS.clear();
		for (QueuePreloadState preload : List.copyOf(QUEUE_PRELOADS.values())) {
			preload.closeQuietly();
		}
		QUEUE_PRELOADS.clear();
		CLEANUP_EXECUTOR.shutdownNow();
		PRELOAD_EXECUTOR.shutdownNow();
		QUEUE_PREVIEW_EXECUTOR.shutdownNow();
		QUEUE_PREVIEW_DOWNLOADS.clear();
	}

	public static boolean looksLikeYoutubeUrl(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			return false;
		}
		try {
			java.net.URI uri = java.net.URI.create(rawUrl.trim());
			String scheme = uri.getScheme();
			if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
				return false;
			}
			String host = uri.getHost();
			if (host == null) {
				return false;
			}
			String normalizedHost = host.toLowerCase(Locale.ROOT);
			return normalizedHost.equals("youtu.be")
					|| normalizedHost.endsWith(".youtu.be")
					|| normalizedHost.equals("youtube.com")
					|| normalizedHost.endsWith(".youtube.com")
					|| normalizedHost.equals("youtube-nocookie.com")
					|| normalizedHost.endsWith(".youtube-nocookie.com");
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static RelaySession requireSession(String sessionId) throws IOException {
		if (sessionId == null || sessionId.isBlank()) {
			throw new IOException("sessionId is required");
		}
		RelaySession session = SESSIONS.get(sessionId);
		if (session == null) {
			throw new IOException("unknown session");
		}
		return session;
	}

	private static void cacheQueuePreview(String url) {
		if (url == null || url.isBlank() || readCachedQueuePreview(url) != null) {
			return;
		}
		for (String thumbnailUrl : queuePreviewThumbnailUrls(url)) {
			if (thumbnailUrl == null || thumbnailUrl.isBlank()) {
				continue;
			}
			try {
				BufferedImage preview = downloadQueuePreviewImage(thumbnailUrl);
				if (preview != null) {
					persistQueuePreview(url, preview);
					return;
				}
			} catch (IOException exception) {
				Lg2.LOGGER.debug("Failed to cache YouTube queue preview for {}", thumbnailUrl, exception);
			}
		}
	}

	private static List<String> queuePreviewThumbnailUrls(String url) {
		List<String> urls = new ArrayList<>(MonitorYoutubeMusicCache.directThumbnailUrls(url));
		urls.sort(Comparator.comparingInt(MonitorYoutubeRelayClient::queuePreviewThumbnailPriority));
		return List.copyOf(urls);
	}

	private static int queuePreviewThumbnailPriority(String url) {
		String normalized = url == null ? "" : url.toLowerCase(Locale.ROOT);
		if (normalized.endsWith("/mqdefault.jpg")) {
			return 0;
		}
		if (normalized.endsWith("/hqdefault.jpg")) {
			return 1;
		}
		if (normalized.endsWith("/default.jpg")) {
			return 2;
		}
		if (normalized.endsWith("/sddefault.jpg")) {
			return 3;
		}
		if (normalized.endsWith("/maxresdefault.jpg")) {
			return 4;
		}
		return 5;
	}

	private static BufferedImage downloadQueuePreviewImage(String thumbnailUrl) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(thumbnailUrl))
				.timeout(Duration.ofMillis(QUEUE_PREVIEW_REQUEST_TIMEOUT_MS))
				.header("User-Agent", "LostGladeMonitor/1.0")
				.header("Accept", "image/*,*/*;q=0.8")
				.GET()
				.build();
		HttpResponse<byte[]> response;
		try {
			response = QUEUE_PREVIEW_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while downloading YouTube preview", exception);
		}
		int status = response.statusCode();
		if (status < 200 || status >= 400) {
			throw new IOException("Preview request failed: HTTP " + status);
		}
		long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
		if (contentLength > MAX_QUEUE_PREVIEW_BYTES) {
			throw new IOException("Preview image is too large");
		}
		byte[] bytes = response.body();
		if (bytes == null || bytes.length == 0) {
			return null;
		}
		if (bytes.length > MAX_QUEUE_PREVIEW_BYTES) {
			throw new IOException("Preview image is too large");
		}
		return ImageIO.read(new ByteArrayInputStream(bytes));
	}

	private static BufferedImage readCachedQueuePreview(String url) {
		Path previewPath = queuePreviewPath(url);
		if (previewPath == null || !Files.isRegularFile(previewPath)) {
			return null;
		}
		try {
			return ImageIO.read(previewPath.toFile());
		} catch (IOException exception) {
			Lg2.LOGGER.debug("Failed to read cached YouTube queue preview for {}", url, exception);
			return null;
		}
	}

	private static void persistQueuePreview(String url, BufferedImage preview) throws IOException {
		Path previewPath = queuePreviewPath(url);
		if (previewPath == null || preview == null) {
			return;
		}
		Files.createDirectories(previewPath.getParent());
		ImageIO.write(preview, "png", previewPath.toFile());
	}

	private static QueuePreloadSnapshot snapshotQueuePreload(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		QueuePreloadState state = QUEUE_PRELOADS.get(url.trim());
		return state != null ? state.snapshot() : loadPersistentQueuePreload(url.trim());
	}

	private static boolean isQueuePreloadReady(QueuePreloadSnapshot snapshot) {
		if (snapshot == null || snapshot.resolved() == null || snapshot.resolved().live()) {
			return false;
		}
		return snapshot.bufferedFromStart()
				&& snapshot.bufferedEndMs() + CONTIGUOUS_BUFFER_TOLERANCE_MS >= Math.max(0L, QUEUE_PRELOAD_DURATION_MS - PREVIEW_CACHE_BUCKET_MS);
	}

	private static Path persistentQueuePreloadDir(String url) {
		return persistentPreloadCacheRoot.resolve(urlCacheKey(url));
	}

	private static Path queuePreviewPath(String url) {
		Path cacheDir = persistentQueuePreloadDir(url);
		return cacheDir != null ? cacheDir.resolve("queue-preview.png") : null;
	}

	private static void deleteDirectoryQuietly(Path directory, Path stopDirectory) {
		if (directory == null) {
			return;
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
				Files.deleteIfExists(path);
			}
			pruneEmptyParents(directory.getParent(), stopDirectory);
		} catch (IOException ignored) {
		}
	}

	private static void pruneEmptyParents(Path directory, Path stopDirectory) {
		Path current = directory;
		while (current != null && stopDirectory != null && current.startsWith(stopDirectory) && !current.equals(stopDirectory)) {
			try (Stream<Path> children = Files.list(current)) {
				if (children.findAny().isPresent()) {
					return;
				}
			} catch (IOException exception) {
				return;
			}
			try {
				Files.deleteIfExists(current);
			} catch (IOException exception) {
				return;
			}
			current = current.getParent();
		}
	}

	private static String urlCacheKey(String url) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Missing SHA-256", exception);
		}
	}

	private static QueuePreloadSnapshot loadPersistentQueuePreload(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		Path cacheDir = persistentQueuePreloadDir(url);
		Path metadataPath = cacheDir.resolve("meta.json");
		if (!Files.isRegularFile(metadataPath)) {
			return null;
		}
		try {
			JsonObject metadata = GSON.fromJson(Files.readString(metadataPath, StandardCharsets.UTF_8), JsonObject.class);
			if (metadata == null || !url.equals(getString(metadata, "url", ""))) {
				return null;
			}
			ResolvedYoutube resolved = new ResolvedYoutube(
					getString(metadata, "title", "YouTube"),
					Math.max(0L, getLong(metadata, "durationMs", 0L)),
					getBoolean(metadata, "live", false),
					getString(metadata, "streamUrl", ""),
					getString(metadata, "audioStreamUrl", "")
			);
			JsonArray framesArray = metadata.getAsJsonArray("frames");
			if (framesArray == null || framesArray.isEmpty()) {
				return null;
			}
			NavigableMap<Long, CachedPreviewFrame> frames = new TreeMap<>();
			for (JsonElement element : framesArray) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				JsonObject frameObject = element.getAsJsonObject();
				long bucketMs = Math.max(0L, getLong(frameObject, "bucketMs", -1L));
				String fileName = getString(frameObject, "file", "").trim();
				if (bucketMs < 0L || fileName.isBlank()) {
					continue;
				}
				Path framePath = cacheDir.resolve(fileName);
				if (!Files.isRegularFile(framePath)) {
					continue;
				}
				byte[] bytes = Files.readAllBytes(framePath);
				if (bytes.length == 0) {
					continue;
				}
				frames.put(bucketMs, new CachedPreviewFrame(bytes, null));
			}
			if (frames.isEmpty()) {
				return null;
			}
			QueueBufferRange range = computeQueueBufferRange(frames);
			return new QueuePreloadSnapshot(
					resolved,
					frames,
					range.bufferedStartMs(),
					range.bufferedEndMs(),
					range.bufferedFromStart()
			);
		} catch (Exception exception) {
			Lg2.LOGGER.debug("Failed to load persistent YouTube preload cache for {}", url, exception);
			return null;
		}
	}

	private static void persistQueuePreloadSnapshot(String url, QueuePreloadSnapshot snapshot) {
		if (url == null || url.isBlank() || snapshot == null || snapshot.resolved() == null || snapshot.resolved().live()) {
			return;
		}
		Path cacheDir = persistentQueuePreloadDir(url);
		try {
			Files.createDirectories(cacheDir);
			JsonArray framesArray = new JsonArray();
			for (Map.Entry<Long, CachedPreviewFrame> entry : snapshot.cachedPreviewFrames().entrySet()) {
				CachedPreviewFrame frame = entry.getValue();
				if (frame == null || frame.bytes() == null || frame.bytes().length == 0) {
					continue;
				}
				long bucketMs = entry.getKey();
				String fileName = bucketMs + ".jpg";
				Files.write(cacheDir.resolve(fileName), frame.bytes());
				JsonObject frameObject = new JsonObject();
				frameObject.addProperty("bucketMs", bucketMs);
				frameObject.addProperty("file", fileName);
				framesArray.add(frameObject);
			}
			if (framesArray.isEmpty()) {
				return;
			}
			JsonObject metadata = new JsonObject();
			metadata.addProperty("url", url);
			metadata.addProperty("title", snapshot.resolved().title());
			metadata.addProperty("durationMs", snapshot.resolved().durationMs());
			metadata.addProperty("live", snapshot.resolved().live());
			metadata.addProperty("streamUrl", snapshot.resolved().streamUrl());
			metadata.addProperty("audioStreamUrl", snapshot.resolved().audioStreamUrl());
			metadata.addProperty("bufferedStartMs", snapshot.bufferedStartMs());
			metadata.addProperty("bufferedEndMs", snapshot.bufferedEndMs());
			metadata.addProperty("bufferedFromStart", snapshot.bufferedFromStart());
			metadata.add("frames", framesArray);
			Files.writeString(cacheDir.resolve("meta.json"), GSON.toJson(metadata), StandardCharsets.UTF_8);
		} catch (Exception exception) {
			Lg2.LOGGER.debug("Failed to persist YouTube preload cache for {}", url, exception);
		}
	}

	private static QueueBufferRange computeQueueBufferRange(NavigableMap<Long, CachedPreviewFrame> frames) {
		if (frames == null || frames.isEmpty()) {
			return new QueueBufferRange(0L, 0L, false);
		}
		long startMs = frames.firstKey();
		long endMs = startMs;
		for (Long candidateMs : frames.tailMap(startMs, false).keySet()) {
			if (candidateMs > endMs + CONTIGUOUS_BUFFER_TOLERANCE_MS) {
				break;
			}
			endMs = candidateMs;
		}
		return new QueueBufferRange(startMs, endMs, startMs == 0L);
	}

	private static void cleanupExpiredSessions() {
		long now = System.currentTimeMillis();
		for (Map.Entry<String, RelaySession> entry : List.copyOf(SESSIONS.entrySet())) {
			RelaySession session = entry.getValue();
			if (session == null || now - session.lastAccessAtMillis() <= SESSION_IDLE_TIMEOUT_MS) {
				continue;
			}
			if (SESSIONS.remove(entry.getKey(), session)) {
				session.closeQuietly();
			}
		}
	}

	private static void validateYoutubeUrl(String rawUrl) throws IOException {
		if (!looksLikeYoutubeUrl(rawUrl)) {
			throw new IOException("Only YouTube links are supported");
		}
	}

	private static JsonObject resolveYoutubeMetadata(String url) throws IOException {
		String metadataJson = runTextCommand(List.of(ytDlpBin(), "--dump-single-json", "--no-playlist", url), COMMAND_TIMEOUT_SEC);
		JsonObject metadata = GSON.fromJson(metadataJson, JsonObject.class);
		if (metadata == null) {
			throw new IOException("Failed to resolve YouTube metadata");
		}
		return metadata;
	}

	private static ResolvedYoutube resolveYoutube(String url) throws IOException {
		JsonObject metadata = resolveYoutubeMetadata(url);
		String title = getNonBlankString(metadata, "title", "YouTube");
		long durationMs = Math.round(getDouble(metadata, "duration", 0.0D) * 1000.0D);
		boolean isLive = getBoolean(metadata, "is_live", false)
				|| "is_live".equalsIgnoreCase(getString(metadata, "live_status", ""));
		String streamUrl = resolveStreamUrl(url, YOUTUBE_VIDEO_STREAM_FORMAT);
		String audioStreamUrl = resolveStreamUrl(url, YOUTUBE_AUDIO_STREAM_FORMAT);
		return new ResolvedYoutube(title, durationMs, isLive, streamUrl, audioStreamUrl.isBlank() ? streamUrl : audioStreamUrl);
	}

	private static String resolveStreamUrl(String url, String formatSelector) throws IOException {
		return firstOutputLine(runTextCommand(List.of(
				ytDlpBin(),
				"-g",
				"-f",
				formatSelector,
				"--no-playlist",
				url
		), COMMAND_TIMEOUT_SEC));
	}

	private static boolean hasDedicatedAudioStream(ResolvedYoutube resolved) {
		if (resolved == null || resolved.streamUrl() == null || resolved.audioStreamUrl() == null) {
			return false;
		}
		String streamUrl = resolved.streamUrl().trim();
		String audioStreamUrl = resolved.audioStreamUrl().trim();
		return !streamUrl.isBlank() && !audioStreamUrl.isBlank() && !Objects.equals(streamUrl, audioStreamUrl);
	}

	private static QueueResolveResponse resolveYoutubeQueue(String url) throws IOException {
		if (shouldResolveSingleQueueEntry(url)) {
			return resolveSingleYoutubeQueueEntry(url);
		}
		String metadataJson = runTextCommand(List.of(ytDlpBin(), "--dump-single-json", "--flat-playlist", url), COMMAND_TIMEOUT_SEC);
		JsonObject metadata = GSON.fromJson(metadataJson, JsonObject.class);
		String containerTitle = getNonBlankString(metadata, "title", "YouTube");
		String containerSubtitle = resolveQueueEntrySubtitle(metadata);
		long containerDurationMs = resolveQueueEntryDurationMs(metadata);
		JsonArray entriesArray = metadata != null && metadata.has("entries") && metadata.get("entries").isJsonArray()
				? metadata.getAsJsonArray("entries")
				: null;
		List<QueueEntry> entries = new ArrayList<>();
		if (entriesArray != null && !entriesArray.isEmpty()) {
			for (JsonElement element : entriesArray) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				JsonObject entry = element.getAsJsonObject();
				String entryUrl = resolveQueueEntryUrl(entry);
				if (entryUrl == null || entryUrl.isBlank()) {
					continue;
				}
				String entryTitle = getNonBlankString(entry, "title", "YouTube");
				String entrySubtitle = resolveQueueEntrySubtitle(entry);
				long entryDurationMs = resolveQueueEntryDurationMs(entry);
				entries.add(new QueueEntry(entryTitle, entrySubtitle, entryDurationMs, entryUrl));
			}
			if (!entries.isEmpty()) {
				return new QueueResolveResponse(containerTitle, entries, true);
			}
		}
		String canonicalUrl = getString(metadata, "webpage_url", url);
		return new QueueResolveResponse(
				containerTitle,
				List.of(new QueueEntry(containerTitle, containerSubtitle, containerDurationMs, canonicalUrl)),
				false
		);
	}

	private static QueueResolveResponse resolveSingleYoutubeQueueEntry(String url) throws IOException {
		String metadataJson = runTextCommand(List.of(ytDlpBin(), "--dump-single-json", "--no-playlist", url), COMMAND_TIMEOUT_SEC);
		JsonObject metadata = GSON.fromJson(metadataJson, JsonObject.class);
		String title = getNonBlankString(metadata, "title", "YouTube");
		String subtitle = resolveQueueEntrySubtitle(metadata);
		long durationMs = resolveQueueEntryDurationMs(metadata);
		String canonicalUrl = getString(metadata, "webpage_url", url);
		return new QueueResolveResponse(
				title,
				List.of(new QueueEntry(title, subtitle, durationMs, canonicalUrl == null || canonicalUrl.isBlank() ? url : canonicalUrl)),
				false
		);
	}

	private static boolean shouldResolveSingleQueueEntry(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			return false;
		}
		try {
			java.net.URI uri = java.net.URI.create(rawUrl.trim());
			String host = uri.getHost();
			if (host == null || host.isBlank()) {
				return false;
			}
			String normalizedHost = host.toLowerCase(Locale.ROOT);
			if (normalizedHost.equals("youtu.be") || normalizedHost.endsWith(".youtu.be")) {
				return true;
			}
			String path = uri.getPath();
			String normalizedPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
			String videoId = queryParameter(uri, "v");
			String playlistId = queryParameter(uri, "list");
			if (normalizedPath.equals("/playlist")) {
				return false;
			}
			if (!videoId.isBlank() && playlistId.isBlank()) {
				return true;
			}
			return normalizedPath.startsWith("/shorts/")
					|| normalizedPath.startsWith("/live/")
					|| normalizedPath.startsWith("/embed/")
					|| normalizedPath.startsWith("/v/");
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static String queryParameter(java.net.URI uri, String name) {
		if (uri == null || name == null || name.isBlank()) {
			return "";
		}
		String query = uri.getRawQuery();
		if (query == null || query.isBlank()) {
			return "";
		}
		for (String entry : query.split("&")) {
			if (entry == null || entry.isBlank()) {
				continue;
			}
			int separator = entry.indexOf('=');
			String key = separator >= 0 ? entry.substring(0, separator) : entry;
			if (!name.equals(key)) {
				continue;
			}
			String value = separator >= 0 && separator + 1 < entry.length() ? entry.substring(separator + 1) : "";
			return value == null ? "" : value.trim();
		}
		return "";
	}

	private static String resolveQueueEntrySubtitle(JsonObject entry) {
		String[] keys = {"artist", "channel", "uploader", "creator", "album_artist", "playlist_uploader"};
		for (String key : keys) {
			String value = getString(entry, key, "");
			if (!value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private static long resolveQueueEntryDurationMs(JsonObject entry) {
		if (entry == null) {
			return 0L;
		}
		if (entry.has("duration") && !entry.get("duration").isJsonNull()) {
			try {
				return Math.max(0L, Math.round(entry.get("duration").getAsDouble() * 1000.0D));
			} catch (RuntimeException ignored) {
			}
		}
		if (entry.has("duration_ms") && !entry.get("duration_ms").isJsonNull()) {
			try {
				return Math.max(0L, entry.get("duration_ms").getAsLong());
			} catch (RuntimeException ignored) {
			}
		}
		if (entry.has("durationMs") && !entry.get("durationMs").isJsonNull()) {
			try {
				return Math.max(0L, entry.get("durationMs").getAsLong());
			} catch (RuntimeException ignored) {
			}
		}
		return 0L;
	}

	private static String resolveQueueEntryUrl(JsonObject entry) {
		String webpageUrl = getString(entry, "webpage_url", "");
		if (!webpageUrl.isBlank()) {
			return webpageUrl;
		}
		String rawUrl = getString(entry, "url", "");
		if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
			return rawUrl;
		}
		String id = getString(entry, "id", rawUrl);
		if (id == null || id.isBlank()) {
			return "";
		}
		return "https://www.youtube.com/watch?v=" + id.trim();
	}

	private static String firstOutputLine(String output) throws IOException {
		String normalized = output == null ? "" : output.trim();
		if (normalized.isBlank()) {
			throw new IOException("Empty yt-dlp response");
		}
		String[] lines = normalized.split("\\R");
		return lines[0].trim();
	}

	private static Path downloadedGalleryVideoPath(Path downloadDir) throws IOException {
		if (downloadDir == null || !Files.isDirectory(downloadDir)) {
			throw new IOException("Downloaded YouTube video directory is missing");
		}
		Path mp4Path = downloadDir.resolve("video.mp4");
		if (Files.isRegularFile(mp4Path)) {
			return mp4Path;
		}
		try (Stream<Path> paths = Files.list(downloadDir)) {
			return paths
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName() != null && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp4"))
					.findFirst()
					.orElseThrow(() -> new IOException("Downloaded YouTube mp4 is missing"));
		}
	}

	private static void validateGalleryVideoFile(Path videoPath, long expectedDurationMs) throws IOException {
		if (videoPath == null || !Files.isRegularFile(videoPath) || Files.size(videoPath) <= 0L) {
			throw new IOException("Downloaded YouTube video is missing");
		}
		String output = runTextCommand(List.of(
				ffprobeBin(),
				"-v",
				"error",
				"-show_entries",
				"format=duration:stream=codec_type",
				"-of",
				"json",
				videoPath.toString()
		), GALLERY_VIDEO_VALIDATE_TIMEOUT_SEC);
		JsonObject root = GSON.fromJson(output, JsonObject.class);
		JsonArray streams = root != null && root.has("streams") && root.get("streams").isJsonArray()
				? root.getAsJsonArray("streams")
				: null;
		boolean hasVideo = false;
		boolean hasAudio = false;
		if (streams != null) {
			for (JsonElement element : streams) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				String codecType = getString(element.getAsJsonObject(), "codec_type", "");
				hasVideo |= "video".equalsIgnoreCase(codecType);
				hasAudio |= "audio".equalsIgnoreCase(codecType);
			}
		}
		if (!hasVideo) {
			throw new IOException("Downloaded YouTube mp4 has no video stream");
		}
		if (!hasAudio) {
			throw new IOException("Downloaded YouTube mp4 has no audio stream");
		}
		JsonObject format = root != null && root.has("format") && root.get("format").isJsonObject()
				? root.getAsJsonObject("format")
				: null;
		long actualDurationMs = Math.round(getDouble(format, "duration", 0.0D) * 1000.0D);
		if (expectedDurationMs > 0L) {
			long toleranceMs = Math.min(30_000L, Math.max(7_000L, expectedDurationMs / 50L));
			if (actualDurationMs <= 0L || actualDurationMs + toleranceMs < expectedDurationMs) {
				throw new IOException("Downloaded YouTube video is shorter than expected: " + actualDurationMs + "ms < " + expectedDurationMs + "ms");
			}
		}
	}

	private static void moveFileReplacing(Path sourcePath, Path targetPath) throws IOException {
		if (sourcePath == null || targetPath == null) {
			throw new IOException("Invalid YouTube video move path");
		}
		Path parent = targetPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try {
			Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException ignored) {
			Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String runTextCommand(List<String> command, int timeoutSeconds) throws IOException {
		ProcessBuilder builder = new ProcessBuilder(command);
		Process process = builder.start();
		try {
			ProcessOutputReader stdoutReader = new ProcessOutputReader(process.getInputStream());
			ProcessOutputReader stderrReader = new ProcessOutputReader(process.getErrorStream());
			Thread stdoutThread = new Thread(stdoutReader, "lg2-youtube-process-stdout");
			Thread stderrThread = new Thread(stderrReader, "lg2-youtube-process-stderr");
			stdoutThread.setDaemon(true);
			stderrThread.setDaemon(true);
			stdoutThread.start();
			stderrThread.start();
			boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				stdoutThread.join(1000L);
				stderrThread.join(1000L);
				throw new IOException("Timed out: " + String.join(" ", command));
			}
			stdoutThread.join(1000L);
			stderrThread.join(1000L);
			if (stdoutReader.exception() != null) {
				throw stdoutReader.exception();
			}
			if (stderrReader.exception() != null) {
				throw stderrReader.exception();
			}
			String stdout = new String(stdoutReader.bytes(), StandardCharsets.UTF_8).trim();
			String stderr = new String(stderrReader.bytes(), StandardCharsets.UTF_8).trim();
			if (process.exitValue() != 0) {
				String message = stderr.isBlank() ? stdout : stderr;
				if (message.isBlank()) {
					message = "Command failed: " + String.join(" ", command);
				}
				throw new IOException(message);
			}
			return stdout;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("Interrupted while running command", exception);
		} finally {
			process.destroy();
		}
	}

	private static BufferedImage runPreviewFrameCommand(List<String> command, int timeoutSeconds) throws IOException {
		return decodeImageBytes(runPreviewFrameCommandBytes(command, timeoutSeconds));
	}

	private static byte[] runPreviewFrameCommandBytes(List<String> command, int timeoutSeconds) throws IOException {
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectError(ProcessBuilder.Redirect.DISCARD);
		Process process = builder.start();
		try {
			byte[] bytes = readProcessOutput(process, timeoutSeconds, "Timed out waiting for ffmpeg preview frame");
			if (process.exitValue() != 0 || bytes.length == 0) {
				throw new IOException("Failed to capture YouTube preview frame");
			}
			return bytes;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("Interrupted while capturing preview frame", exception);
		} finally {
			process.destroy();
		}
	}

	private static BufferedImage decodeImageBytes(byte[] bytes) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		if (image == null) {
			throw new IOException("ffmpeg returned an unreadable preview frame");
		}
		return image;
	}

	private static byte[] readProcessOutput(Process process, int timeoutSeconds, String timeoutMessage) throws IOException, InterruptedException {
		ProcessOutputReader reader = new ProcessOutputReader(process.getInputStream());
		Thread readerThread = new Thread(reader, "lg2-youtube-process-output");
		readerThread.setDaemon(true);
		readerThread.start();
		boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
			readerThread.join(1000L);
			throw new IOException(timeoutMessage);
		}
		readerThread.join(1000L);
		if (reader.exception() != null) {
			throw reader.exception();
		}
		return reader.bytes();
	}

	private static List<String> previewFrameCommand(String streamUrl, long seekMs, int frameWidth) {
		List<String> command = new ArrayList<>();
		command.add(ffmpegBin());
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("error");
		command.add("-nostdin");
		if (seekMs > 0L) {
			command.add("-ss");
			command.add(String.format(Locale.ROOT, "%.3f", seekMs / 1000.0D));
		}
		command.add("-i");
		command.add(streamUrl);
		command.add("-frames:v");
		command.add("1");
		command.add("-an");
		command.add("-vf");
		command.add("scale=w=" + Math.max(FRAME_WIDTH, frameWidth) + ":h=-2:force_original_aspect_ratio=decrease");
		command.add("-q:v");
		command.add("5");
		command.add("-f");
		command.add("image2pipe");
		command.add("-vcodec");
		command.add("mjpeg");
		command.add("-");
		return command;
	}

	private static List<String> sequentialPrefetchCommand(String streamUrl, long startMs, long durationMs, int frameWidth) {
		List<String> command = new ArrayList<>();
		command.add(ffmpegBin());
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("error");
		command.add("-nostdin");
		command.add("-threads");
		command.add("1");
		if (startMs > 0L) {
			command.add("-ss");
			command.add(String.format(Locale.ROOT, "%.3f", startMs / 1000.0D));
		}
		if (durationMs > 0L) {
			command.add("-t");
			command.add(String.format(Locale.ROOT, "%.3f", durationMs / 1000.0D));
		}
		command.add("-i");
		command.add(streamUrl);
		command.add("-an");
		command.add("-vf");
		command.add("fps=" + FRAME_RATE + ",scale=w=" + Math.max(FRAME_WIDTH, frameWidth) + ":h=-2:force_original_aspect_ratio=decrease");
		command.add("-q:v");
		command.add("6");
		command.add("-f");
		command.add("image2pipe");
		command.add("-vcodec");
		command.add("mjpeg");
		command.add("-");
		return command;
	}

	private static List<String> queuePreloadCommand(String streamUrl) {
		List<String> command = new ArrayList<>();
		command.add(ffmpegBin());
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("error");
		command.add("-nostdin");
		command.add("-threads");
		command.add("1");
		command.add("-t");
		command.add(String.format(Locale.ROOT, "%.3f", QUEUE_PRELOAD_DURATION_MS / 1000.0D));
		command.add("-i");
		command.add(streamUrl);
		command.add("-an");
		command.add("-vf");
		command.add("fps=" + FRAME_RATE + ",scale=w=" + FRAME_WIDTH + ":h=-2:force_original_aspect_ratio=decrease");
		command.add("-q:v");
		command.add("6");
		command.add("-f");
		command.add("image2pipe");
		command.add("-vcodec");
		command.add("mjpeg");
		command.add("-");
		return command;
	}

	private static void readJpegFrames(InputStream stream, FrameConsumer consumer) throws IOException {
		byte[] readBuffer = new byte[8192];
		byte[] frameBuffer = new byte[16384];
		int size = 0;
		int read;
		while ((read = stream.read(readBuffer)) >= 0) {
			if (size + read > frameBuffer.length) {
				frameBuffer = Arrays.copyOf(frameBuffer, Math.max(frameBuffer.length * 2, size + read));
			}
			System.arraycopy(readBuffer, 0, frameBuffer, size, read);
			size += read;
			while (true) {
				int start = indexOf(frameBuffer, size, 0, (byte) 0xFF, (byte) 0xD8);
				if (start < 0) {
					if (size > 2) {
						frameBuffer[0] = frameBuffer[size - 2];
						frameBuffer[1] = frameBuffer[size - 1];
						size = 2;
					}
					break;
				}
				int end = indexOf(frameBuffer, size, start + 2, (byte) 0xFF, (byte) 0xD9);
				if (end < 0) {
					if (start > 0) {
						System.arraycopy(frameBuffer, start, frameBuffer, 0, size - start);
						size -= start;
					}
					break;
				}
				consumer.accept(Arrays.copyOfRange(frameBuffer, start, end + 2));
				int remaining = size - (end + 2);
				if (remaining > 0) {
					System.arraycopy(frameBuffer, end + 2, frameBuffer, 0, remaining);
				}
				size = remaining;
			}
		}
	}

	private static int indexOf(byte[] buffer, int size, int fromIndex, byte first, byte second) {
		for (int index = Math.max(0, fromIndex); index <= size - 2; index++) {
			if (buffer[index] == first && buffer[index + 1] == second) {
				return index;
			}
		}
		return -1;
	}

	private static long normalizeBucketMs(long positionMs) {
		return Math.max(0L, (positionMs / PREVIEW_CACHE_BUCKET_MS) * PREVIEW_CACHE_BUCKET_MS);
	}

	private static ThreadFactory daemonThreadFactory(String namePrefix) {
		return runnable -> {
			Thread thread = new Thread(runnable, namePrefix);
			thread.setDaemon(true);
			return thread;
		};
	}

	private static String ytDlpBin() {
		return readStringSetting("YT_DLP_BIN", "lg2.youtube.ytDlpBin", DEFAULT_YT_DLP_BIN);
	}

	private static String ffmpegBin() {
		return readStringSetting("FFMPEG_BIN", "lg2.youtube.ffmpegBin", DEFAULT_FFMPEG_BIN);
	}

	private static String ffprobeBin() {
		return readStringSetting("FFPROBE_BIN", "lg2.media.ffprobeBin", DEFAULT_FFPROBE_BIN);
	}

	private static String readStringSetting(String envKey, String propertyKey, String fallback) {
		String property = System.getProperty(propertyKey);
		if (property != null && !property.isBlank()) {
			return property.trim();
		}
		String environment = System.getenv(envKey);
		if (environment != null && !environment.isBlank()) {
			return environment.trim();
		}
		return fallback;
	}

	private static int readIntSetting(String envKey, String propertyKey, int fallback) {
		String raw = readStringSetting(envKey, propertyKey, Integer.toString(fallback));
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static long readCacheWindowMillis(
			String secondsEnvKey,
			String secondsPropertyKey,
			String legacyMinutesEnvKey,
			String legacyMinutesPropertyKey,
			int defaultSeconds
	) {
		Integer configuredSeconds = readOptionalIntSetting(secondsEnvKey, secondsPropertyKey);
		if (configuredSeconds != null) {
			return TimeUnit.SECONDS.toMillis(clampCacheWindowSeconds(configuredSeconds));
		}
		// Keep the old minute-based switches working, but bound them. They used to make a
		// single screen retain tens of thousands of decoded preview frames.
		Integer configuredLegacyMinutes = readOptionalIntSetting(legacyMinutesEnvKey, legacyMinutesPropertyKey);
		if (configuredLegacyMinutes != null) {
			return TimeUnit.SECONDS.toMillis(clampCacheWindowSeconds(configuredLegacyMinutes * 60L));
		}
		return TimeUnit.SECONDS.toMillis(clampCacheWindowSeconds(defaultSeconds));
	}

	private static Integer readOptionalIntSetting(String envKey, String propertyKey) {
		String property = System.getProperty(propertyKey);
		String raw = property != null && !property.isBlank() ? property : System.getenv(envKey);
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static long clampCacheWindowSeconds(long seconds) {
		return Math.max(0L, Math.min(120L, seconds));
	}

	private static double readDoubleSetting(String envKey, String propertyKey, double fallback) {
		String raw = readStringSetting(envKey, propertyKey, Double.toString(fallback));
		try {
			return Double.parseDouble(raw.trim());
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static String getString(JsonObject object, String key, String fallback) {
		return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
	}

	private static String getNonBlankString(JsonObject object, String key, String fallback) {
		String value = getString(object, key, fallback);
		return value == null || value.isBlank() ? fallback : value;
	}

	private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
		return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
	}

	private static double getDouble(JsonObject object, String key, double fallback) {
		return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
	}

	private static long getLong(JsonObject object, String key, long fallback) {
		return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : fallback;
	}

	public record SessionLoadResponse(
			String sessionId,
			String title,
			long durationMs,
			boolean live,
			String status,
			String audioStreamUrl,
			BufferedImage initialFrame,
			long initialFrameSequence,
			long initialPositionMs,
			long bufferedStartMs,
			long bufferedEndMs,
			boolean ready
	) {
	}

	public record QueueResolveResponse(
			String title,
			List<QueueEntry> entries,
			boolean playlist
	) {
	}

	public record QueueEntry(
			String title,
			String subtitle,
			long durationMs,
			String url
	) {
	}

	public record DownloadedGalleryVideo(
			String title,
			long durationMs,
			Path path,
			Path cleanupDirectory
	) implements AutoCloseable {
		@Override
		public void close() {
			deleteDirectoryQuietly(this.cleanupDirectory, persistentPreloadCacheRoot);
		}
	}

	public record SessionSnapshot(
			String sessionId,
			String title,
			BufferedImage frame,
			long frameSequence,
			long positionMs,
			long durationMs,
			long bufferedStartMs,
			long bufferedEndMs,
			boolean paused,
			boolean ended,
			boolean live,
			boolean audioPlaceholder,
			boolean ready,
			String status,
			String audioStreamUrl
	) {
	}

	private record ResolvedYoutube(
			String title,
			long durationMs,
			boolean live,
			String streamUrl,
			String audioStreamUrl
	) {
	}

	@FunctionalInterface
	private interface FrameConsumer {
		void accept(byte[] frameBytes) throws IOException;
	}

	private record CachedPreviewFrame(byte[] bytes, SoftReference<BufferedImage> imageRef) {
	}

	private record QueuePreloadSnapshot(
			ResolvedYoutube resolved,
			NavigableMap<Long, CachedPreviewFrame> cachedPreviewFrames,
			long bufferedStartMs,
			long bufferedEndMs,
			boolean bufferedFromStart
	) {
	}

	private record QueueBufferRange(long bufferedStartMs, long bufferedEndMs, boolean bufferedFromStart) {
	}

	private static final class ProcessOutputReader implements Runnable {
		private final InputStream inputStream;
		private volatile byte[] bytes = new byte[0];
		private volatile IOException exception = null;

		private ProcessOutputReader(InputStream inputStream) {
			this.inputStream = inputStream;
		}

		@Override
		public void run() {
			try (InputStream stream = this.inputStream) {
				this.bytes = stream.readAllBytes();
			} catch (IOException exception) {
				this.exception = exception;
			}
		}

		private byte[] bytes() {
			return this.bytes;
		}

		private IOException exception() {
			return this.exception;
		}
	}

	private static final class QueuePreloadState {
		private final String sourceUrl;
		private final Object lock = new Object();
		private int retainCount = 0;
		private ResolvedYoutube resolved = null;
		private Process process = null;
		private boolean started = false;
		private boolean preloadComplete = false;
		private boolean bufferedFromStart = false;
		private long bufferedStartMs = 0L;
		private long bufferedEndMs = 0L;
		private final NavigableMap<Long, CachedPreviewFrame> cachedPreviewFrames = new TreeMap<>();

		private QueuePreloadState(String sourceUrl) {
			this.sourceUrl = sourceUrl;
		}

		private void retain() {
			boolean shouldStart = false;
			synchronized (this.lock) {
				loadPersistentCacheLocked();
				this.retainCount++;
				if (!this.started && !this.preloadComplete) {
					this.started = true;
					shouldStart = true;
				}
			}
			if (shouldStart) {
				PRELOAD_EXECUTOR.execute(this::runPreload);
			}
		}

		private boolean release() {
			synchronized (this.lock) {
				this.retainCount = Math.max(0, this.retainCount - 1);
				if (this.retainCount > 0) {
					return false;
				}
				stopLocked();
				this.cachedPreviewFrames.clear();
				this.resolved = null;
				this.bufferedStartMs = 0L;
				this.bufferedEndMs = 0L;
				this.bufferedFromStart = false;
				this.preloadComplete = false;
				this.started = false;
				return true;
			}
		}

		private void closeQuietly() {
			synchronized (this.lock) {
				this.retainCount = 0;
				stopLocked();
				this.cachedPreviewFrames.clear();
				this.resolved = null;
				this.bufferedStartMs = 0L;
				this.bufferedEndMs = 0L;
				this.bufferedFromStart = false;
				this.preloadComplete = false;
				this.started = false;
			}
		}

		private QueuePreloadSnapshot snapshot() {
			synchronized (this.lock) {
				loadPersistentCacheLocked();
				if (this.resolved == null) {
					return null;
				}
				return new QueuePreloadSnapshot(
						this.resolved,
						new TreeMap<>(this.cachedPreviewFrames),
						this.bufferedStartMs,
						this.bufferedEndMs,
						this.bufferedFromStart
				);
			}
		}

		private boolean isLoaded() {
			synchronized (this.lock) {
				loadPersistentCacheLocked();
				return isQueuePreloadReady(this.resolved == null ? null : new QueuePreloadSnapshot(
						this.resolved,
						new TreeMap<>(this.cachedPreviewFrames),
						this.bufferedStartMs,
						this.bufferedEndMs,
						this.bufferedFromStart
				));
			}
		}

		private QueueEntryCacheStatus cacheStatus() {
			synchronized (this.lock) {
				loadPersistentCacheLocked();
				QueuePreloadSnapshot snapshot = this.resolved == null ? null : new QueuePreloadSnapshot(
						this.resolved,
						new TreeMap<>(this.cachedPreviewFrames),
						this.bufferedStartMs,
						this.bufferedEndMs,
						this.bufferedFromStart
				);
				if (isQueuePreloadReady(snapshot)) {
					return new QueueEntryCacheStatus(1.0F, false, true);
				}
				long targetMs = Math.max(PREVIEW_CACHE_BUCKET_MS, QUEUE_PRELOAD_DURATION_MS - PREVIEW_CACHE_BUCKET_MS);
				float fraction = this.bufferedFromStart && targetMs > 0L
						? Math.max(0.0F, Math.min(0.99F, (float) this.bufferedEndMs / (float) targetMs))
						: 0.0F;
				return new QueueEntryCacheStatus(fraction, this.started || this.process != null || this.retainCount > 0, false);
			}
		}

		private void runPreload() {
			Process startedProcess = null;
			boolean preloadFinished = false;
			QueuePreloadSnapshot snapshotToPersist = null;
			try {
				ResolvedYoutube resolvedLocal = resolveYoutube(this.sourceUrl);
				synchronized (this.lock) {
					if (this.retainCount <= 0) {
						this.started = false;
						return;
					}
					this.resolved = resolvedLocal;
					if (resolvedLocal.live()) {
						this.preloadComplete = true;
						this.started = false;
						return;
					}
				}

				ProcessBuilder builder = new ProcessBuilder(queuePreloadCommand(resolvedLocal.streamUrl()));
				builder.redirectError(ProcessBuilder.Redirect.DISCARD);
				startedProcess = builder.start();
				Process processRef = startedProcess;
				synchronized (this.lock) {
					if (this.retainCount <= 0) {
						processRef.destroyForcibly();
						this.started = false;
						return;
					}
					this.process = processRef;
				}

				long[] nextPositionMs = {0L};
				try (InputStream stream = processRef.getInputStream()) {
					readJpegFrames(stream, frameBytes -> {
						BufferedImage image = decodeImageBytes(frameBytes);
						long positionMs = nextPositionMs[0];
						nextPositionMs[0] += PREVIEW_CACHE_BUCKET_MS;
						synchronized (this.lock) {
							if (this.process != processRef || this.retainCount <= 0) {
								return;
							}
							cachePreviewFrameLocked(positionMs, frameBytes, image);
						}
					});
				}
				preloadFinished = true;
			} catch (IOException exception) {
				Lg2.LOGGER.debug("Failed to preload YouTube queue entry {}", this.sourceUrl, exception);
			} finally {
				synchronized (this.lock) {
					if (this.process == startedProcess) {
						this.process = null;
					}
					this.preloadComplete = preloadFinished;
					if (preloadFinished && this.resolved != null) {
						QueuePreloadSnapshot snapshot = new QueuePreloadSnapshot(
								this.resolved,
								new TreeMap<>(this.cachedPreviewFrames),
								this.bufferedStartMs,
								this.bufferedEndMs,
								this.bufferedFromStart
						);
						if (isQueuePreloadReady(snapshot)) {
							snapshotToPersist = snapshot;
						}
					}
					this.started = false;
				}
				if (startedProcess != null) {
					startedProcess.destroy();
				}
				if (snapshotToPersist != null) {
					persistQueuePreloadSnapshot(this.sourceUrl, snapshotToPersist);
				}
			}
		}

		private void cachePreviewFrameLocked(long positionMs, byte[] frameBytes, BufferedImage decodedImage) {
			if (frameBytes == null || frameBytes.length == 0) {
				return;
			}
			long bucketMs = normalizeBucketMs(positionMs);
			this.cachedPreviewFrames.putIfAbsent(
					bucketMs,
					new CachedPreviewFrame(
							Arrays.copyOf(frameBytes, frameBytes.length),
							decodedImage != null ? new SoftReference<>(decodedImage) : null
					)
			);
			while (this.cachedPreviewFrames.size() > MAX_CACHED_PREVIEW_FRAMES) {
				this.cachedPreviewFrames.pollFirstEntry();
			}
			recomputeBufferedRangeLocked();
		}

		private void recomputeBufferedRangeLocked() {
			QueueBufferRange range = computeQueueBufferRange(this.cachedPreviewFrames);
			this.bufferedStartMs = range.bufferedStartMs();
			this.bufferedEndMs = range.bufferedEndMs();
			this.bufferedFromStart = range.bufferedFromStart();
		}

		private void loadPersistentCacheLocked() {
			if ((this.resolved != null && !this.cachedPreviewFrames.isEmpty()) || this.started) {
				return;
			}
			QueuePreloadSnapshot snapshot = loadPersistentQueuePreload(this.sourceUrl);
			if (snapshot == null || snapshot.resolved() == null) {
				return;
			}
			this.resolved = snapshot.resolved();
			this.cachedPreviewFrames.clear();
			this.cachedPreviewFrames.putAll(snapshot.cachedPreviewFrames());
			this.bufferedStartMs = snapshot.bufferedStartMs();
			this.bufferedEndMs = snapshot.bufferedEndMs();
			this.bufferedFromStart = snapshot.bufferedFromStart();
			this.preloadComplete = isQueuePreloadReady(snapshot);
		}

		private void stopLocked() {
			Process running = this.process;
			this.process = null;
			if (running == null) {
				return;
			}
			running.destroy();
			try {
				if (!running.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
					running.destroyForcibly();
					running.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				running.destroyForcibly();
			}
		}
	}

	private static final class RelaySession {
		private final String sessionId;
		private final Object lock = new Object();
		private String title = "YouTube";
		private String sourceUrl = "";
		private String streamUrl = "";
		private String audioStreamUrl = "";
		private long durationMs = 0L;
		private long positionMs = 0L;
		private boolean live = false;
		private boolean paused = false;
		private boolean audioPlaceholder = true;
		private boolean staticVisual = false;
		private String status = "IDLE";
		private BufferedImage latestFrame = null;
		private long frameSequence = 0L;
		private long latestFrameBucketMs = Long.MIN_VALUE;
		private long lastAccessAtMillis = System.currentTimeMillis();
		private Process process = null;
		private Thread readerThread = null;
		private Process prefetchProcess = null;
		private Thread prefetchReaderThread = null;
		private boolean sourceRefreshInProgress = false;
		private long playStartedAtNanos = 0L;
		private long playBasePositionMs = 0L;
		private long bufferedStartMs = 0L;
		private long bufferedEndMs = 0L;
		private boolean bufferedFromStart = false;
		private boolean prefetchCompleted = false;
		private boolean fullCacheRequested = false;
		private int directFrameWidth = FRAME_WIDTH;
		private final NavigableMap<Long, CachedPreviewFrame> cachedPreviewFrames = new TreeMap<>();

		private RelaySession(String sessionId) {
			this.sessionId = sessionId;
		}

		private long lastAccessAtMillis() {
			synchronized (this.lock) {
				return this.lastAccessAtMillis;
			}
		}

		private void touch() {
			synchronized (this.lock) {
				this.lastAccessAtMillis = System.currentTimeMillis();
			}
		}

		private SessionLoadResponse load(String url) throws IOException {
			QueuePreloadSnapshot queuePreload = snapshotQueuePreload(url);
			ResolvedYoutube preloadedResolved = queuePreload != null ? queuePreload.resolved() : null;
			boolean usedPreloadedResolved = preloadedResolved != null && hasDedicatedAudioStream(preloadedResolved);
			ResolvedYoutube resolved = usedPreloadedResolved
					? preloadedResolved
					: resolveYoutube(url);
			SessionLoadResponse response = loadResolved(url, resolved, queuePreload, null);
			if (usedPreloadedResolved && !resolved.live()) {
				refreshResolvedSourceAsync(url);
			}
			return response;
		}

		private SessionLoadResponse loadDirect(String sourceId, String title, String videoInput, String audioInput, long durationMs, BufferedImage staticFrame, int preferredFrameWidth) throws IOException {
			return loadResolved(
					sourceId,
					new ResolvedYoutube(title, durationMs, false, videoInput, audioInput),
					null,
					staticFrame,
					Math.max(FRAME_WIDTH, preferredFrameWidth)
			);
		}

		private SessionLoadResponse loadResolved(String sourceId, ResolvedYoutube resolved, QueuePreloadSnapshot queuePreload, BufferedImage staticFrame) throws IOException {
			return loadResolved(sourceId, resolved, queuePreload, staticFrame, FRAME_WIDTH);
		}

		private SessionLoadResponse loadResolved(String sourceId, ResolvedYoutube resolved, QueuePreloadSnapshot queuePreload, BufferedImage staticFrame, int preferredFrameWidth) throws IOException {
			boolean hadPreloadedFrame = false;
			synchronized (this.lock) {
				stopPrefetchLocked();
				stopLocked();
				this.title = resolved.title();
				this.sourceUrl = sourceId;
				this.streamUrl = resolved.streamUrl();
				this.audioStreamUrl = resolved.audioStreamUrl();
				this.durationMs = resolved.durationMs();
				this.positionMs = 0L;
				this.live = resolved.live();
				this.paused = false;
				this.audioPlaceholder = false;
				this.staticVisual = staticFrame != null;
				this.status = "BUFFERING";
				this.latestFrame = staticFrame;
				this.frameSequence = staticFrame != null ? 1L : 0L;
				this.latestFrameBucketMs = staticFrame != null ? 0L : Long.MIN_VALUE;
				this.bufferedStartMs = 0L;
				this.bufferedEndMs = 0L;
				this.bufferedFromStart = false;
				this.prefetchCompleted = false;
				this.fullCacheRequested = false;
				this.directFrameWidth = staticFrame == null && queuePreload == null
						? clampDirectFrameWidth(preferredFrameWidth)
						: FRAME_WIDTH;
				this.cachedPreviewFrames.clear();
				if (!this.staticVisual && queuePreload != null && !resolved.live()) {
					this.cachedPreviewFrames.putAll(queuePreload.cachedPreviewFrames());
					this.bufferedStartMs = queuePreload.bufferedStartMs();
					this.bufferedEndMs = queuePreload.bufferedEndMs();
					this.bufferedFromStart = queuePreload.bufferedFromStart();
					try {
						hadPreloadedFrame = applyCachedPreviewLocked(0L);
					} catch (IOException exception) {
						Lg2.LOGGER.debug("Failed to apply preloaded frame for {}", sourceId, exception);
						this.latestFrame = null;
					}
				}
				this.playBasePositionMs = 0L;
				this.playStartedAtNanos = System.nanoTime();
				this.lastAccessAtMillis = System.currentTimeMillis();
			}
			if (resolved.live()) {
				startStream();
			} else {
				if (!this.staticVisual && !hadPreloadedFrame) {
					tryCapturePreviewFrame("load");
				}
				synchronized (this.lock) {
					if (this.latestFrame != null) {
						this.status = "PLAYING";
					}
				}
			}
			if (!this.staticVisual) {
				ensurePrefetchStarted();
			}
			synchronized (this.lock) {
				boolean ready = this.latestFrame != null && !Objects.equals(this.status, "BUFFERING");
				return new SessionLoadResponse(
						this.sessionId,
						this.title,
						this.durationMs,
						this.live,
						this.status,
						this.audioStreamUrl,
						this.latestFrame,
						this.frameSequence,
						this.positionMs,
						this.bufferedStartMs,
						this.bufferedEndMs,
						ready
				);
			}
		}

		private void refreshResolvedSourceAsync(String url) {
			if (url == null || url.isBlank()) {
				return;
			}
			synchronized (this.lock) {
				if (this.sourceRefreshInProgress || closedOrUnloadedLocked() || !Objects.equals(this.sourceUrl, url)) {
					return;
				}
				this.sourceRefreshInProgress = true;
			}
			PRELOAD_EXECUTOR.execute(() -> {
				ResolvedYoutube refreshed = null;
				try {
					refreshed = resolveYoutube(url);
				} catch (IOException exception) {
					Lg2.LOGGER.debug("Failed to refresh YouTube source for session {}", this.sessionId, exception);
				}
				boolean shouldRestartPrefetch = false;
				synchronized (this.lock) {
					this.sourceRefreshInProgress = false;
					if (closedOrUnloadedLocked() || !Objects.equals(this.sourceUrl, url) || refreshed == null) {
						return;
					}
					boolean streamChanged = !Objects.equals(this.streamUrl, refreshed.streamUrl());
					this.title = refreshed.title();
					this.durationMs = refreshed.durationMs();
					this.live = refreshed.live();
					this.streamUrl = refreshed.streamUrl();
					this.audioStreamUrl = refreshed.audioStreamUrl();
					if (!this.live) {
						this.prefetchCompleted = false;
						if (streamChanged && this.prefetchProcess != null) {
							stopPrefetchLocked();
						}
						shouldRestartPrefetch = this.prefetchProcess == null;
					}
				}
				if (shouldRestartPrefetch) {
					ensurePrefetchStarted();
				}
			});
		}

		private SessionSnapshot snapshot(long knownFrameSequence) {
			boolean shouldEnsurePrefetch;
			boolean ended = false;
			boolean ready;
			synchronized (this.lock) {
				if (this.live) {
					ready = this.latestFrame != null && !Objects.equals(this.status, "BUFFERING");
				} else if (this.staticVisual) {
					long targetPositionMs = this.paused ? this.positionMs : currentPositionMsLocked();
					if (this.durationMs > 0L && targetPositionMs >= this.durationMs) {
						targetPositionMs = this.durationMs;
						ended = true;
					}
					this.positionMs = targetPositionMs;
					if (ended) {
						this.paused = true;
						this.status = "PAUSED";
					} else {
						this.status = this.paused ? "PAUSED" : "PLAYING";
					}
					ready = this.latestFrame != null;
				} else if (this.paused) {
					boolean hasCachedFrame;
					try {
						hasCachedFrame = applyCachedPreviewLocked(this.positionMs);
					} catch (IOException exception) {
						Lg2.LOGGER.debug("Failed to decode cached YouTube frame for paused session {}", this.sessionId, exception);
						hasCachedFrame = false;
					}
					this.status = hasCachedFrame ? "PAUSED" : "BUFFERING";
					ready = hasCachedFrame;
				} else {
					long targetPositionMs = currentPositionMsLocked();
					if (this.durationMs > 0L && targetPositionMs >= this.durationMs) {
						targetPositionMs = this.durationMs;
						ended = true;
					}
					boolean hasCachedFrame;
					try {
						hasCachedFrame = applyCachedPreviewLocked(targetPositionMs);
					} catch (IOException exception) {
						Lg2.LOGGER.debug("Failed to decode cached YouTube frame for session {}", this.sessionId, exception);
						hasCachedFrame = false;
					}
					if (hasCachedFrame) {
						this.positionMs = targetPositionMs;
						trimCachedPreviewWindowLocked();
						if (ended) {
							this.paused = true;
							this.status = "PAUSED";
						} else {
							this.status = "PLAYING";
						}
						ready = true;
					} else {
						freezePlaybackClockLocked();
						this.status = "BUFFERING";
						ready = false;
					}
				}
					if (!this.live && this.prefetchProcess == null && !closedOrUnloadedLocked()) {
						if (this.bufferedEndMs + CONTIGUOUS_BUFFER_TOLERANCE_MS < desiredCacheMaxMsLocked()) {
							this.prefetchCompleted = false;
						}
					}
					if (!this.live && !ready && !this.paused && this.prefetchProcess == null && !closedOrUnloadedLocked()) {
						this.prefetchCompleted = false;
					}
					shouldEnsurePrefetch = !this.live && !this.staticVisual && !this.prefetchCompleted && this.prefetchProcess == null && !closedOrUnloadedLocked();
				BufferedImage frame = this.frameSequence != knownFrameSequence ? this.latestFrame : null;
				SessionSnapshot snapshot = new SessionSnapshot(
						this.sessionId,
						this.title,
						frame,
						this.frameSequence,
						this.positionMs,
						this.durationMs,
						this.bufferedStartMs,
						this.bufferedEndMs,
						this.paused,
						ended,
						this.live,
						this.audioPlaceholder,
						ready,
						this.status,
						this.audioStreamUrl
				);
				if (shouldEnsurePrefetch) {
					// Restart the low-FPS sequential cache reader if it died unexpectedly.
					// The actual process launch happens outside the state lock.
				}
				return snapshot;
			}
		}

		private SessionSnapshot snapshotWithPrefetch(long knownFrameSequence) {
			SessionSnapshot snapshot = snapshot(knownFrameSequence);
			ensurePrefetchStarted();
			return snapshot;
		}

		private void updateStaticFrame(String sourceId, BufferedImage staticFrame) {
			if (staticFrame == null) {
				return;
			}
			synchronized (this.lock) {
				if (!this.staticVisual || closedOrUnloadedLocked()) {
					return;
				}
				if (sourceId != null && !sourceId.isBlank() && !Objects.equals(this.sourceUrl, sourceId)) {
					return;
				}
				this.latestFrame = staticFrame;
				this.latestFrameBucketMs = 0L;
				this.frameSequence++;
				this.lastAccessAtMillis = System.currentTimeMillis();
			}
		}

		private void pause() {
			synchronized (this.lock) {
				if (this.paused) {
					return;
				}
				if (this.staticVisual) {
					this.positionMs = currentPositionMsLocked();
					this.paused = true;
					this.status = "PAUSED";
					stopLocked();
					return;
				}
				if (this.live) {
					this.positionMs = currentPositionMsLocked();
				} else {
					long targetPositionMs = currentPositionMsLocked();
					boolean hasCachedFrame = false;
					try {
						hasCachedFrame = applyCachedPreviewLocked(targetPositionMs);
					} catch (IOException exception) {
						Lg2.LOGGER.debug("Failed to decode cached YouTube frame while pausing session {}", this.sessionId, exception);
					}
					if (hasCachedFrame) {
						this.positionMs = targetPositionMs;
						trimCachedPreviewWindowLocked();
					} else {
						freezePlaybackClockLocked();
					}
				}
				this.paused = true;
				this.status = this.live || hasCachedFrameForPositionLocked(this.positionMs) ? "PAUSED" : "BUFFERING";
				stopLocked();
			}
		}

		private void resume() throws IOException {
			boolean shouldStartLiveStream = false;
			synchronized (this.lock) {
				if (this.sourceUrl.isBlank() || this.streamUrl.isBlank()) {
					throw new IOException("session is not loaded");
				}
				if (this.staticVisual) {
					this.playBasePositionMs = this.positionMs;
					this.playStartedAtNanos = System.nanoTime();
					this.paused = false;
					this.status = "PLAYING";
					return;
				}
				applyCachedPreviewLocked(this.positionMs);
				this.playBasePositionMs = this.positionMs;
				this.playStartedAtNanos = System.nanoTime();
				this.paused = false;
				this.status = this.live ? "BUFFERING" : (hasCachedFrameForPositionLocked(this.positionMs) ? "PLAYING" : "BUFFERING");
				shouldStartLiveStream = this.live;
			}
			if (shouldStartLiveStream) {
				startStream();
			}
			ensurePrefetchStarted();
		}

		private void seek(long targetPositionMs) throws IOException {
			boolean capturePreview;
			boolean shouldStartLiveStream = false;
			long clampedPositionMs;
			synchronized (this.lock) {
				if (this.live) {
					throw new IOException("live stream is not seekable");
				}
				if (this.staticVisual) {
					long clamped = Math.max(0L, this.durationMs > 0L ? Math.min(targetPositionMs, this.durationMs) : targetPositionMs);
					anchorPlaybackClockLocked(clamped);
					this.status = this.paused ? "PAUSED" : "PLAYING";
					stopLocked();
					return;
				}
				long clamped = Math.max(0L, this.durationMs > 0L ? Math.min(targetPositionMs, this.durationMs) : targetPositionMs);
				clampedPositionMs = clamped;
				anchorPlaybackClockLocked(clamped);
				this.prefetchCompleted = false;
				stopPrefetchLocked();
				trimCachedPreviewWindowLocked();
				boolean hadCachedPreview = applyCachedPreviewLocked(clamped);
				capturePreview = !hadCachedPreview;
				this.status = this.paused ? "PAUSED" : (hadCachedPreview ? "PLAYING" : "BUFFERING");
				stopLocked();
				shouldStartLiveStream = this.live && !this.paused;
			}
			if (capturePreview) {
				boolean captured = tryCapturePreviewFrame("seek");
				synchronized (this.lock) {
					if (captured) {
						anchorPlaybackClockLocked(clampedPositionMs);
						this.status = this.paused ? "PAUSED" : "PLAYING";
					} else if (this.paused) {
						return;
					}
				}
			} else {
				synchronized (this.lock) {
					if (this.paused) {
						return;
					}
				}
				if (shouldStartLiveStream) {
					startStream();
				}
			}
			ensurePrefetchStarted();
		}

		private void close() {
			synchronized (this.lock) {
				stopPrefetchLocked();
				stopLocked();
				this.status = "CLOSED";
			}
		}

		private void closeQuietly() {
			try {
				close();
			} catch (Exception ignored) {
			}
		}

		private void capturePreviewFrame() throws IOException {
			String targetStreamUrl;
			long seekMs;
			synchronized (this.lock) {
				if (this.streamUrl.isBlank()) {
					return;
				}
				targetStreamUrl = this.streamUrl;
				seekMs = this.positionMs;
			}
			int frameWidth;
			synchronized (this.lock) {
				frameWidth = effectiveFrameWidthLocked();
			}
			byte[] previewBytes = runPreviewFrameCommandBytes(previewFrameCommand(targetStreamUrl, seekMs, frameWidth), STREAM_START_TIMEOUT_SEC);
			BufferedImage preview = decodeImageBytes(previewBytes);
			synchronized (this.lock) {
				long bucketMs = normalizeBucketMs(seekMs);
				this.latestFrame = preview;
				this.latestFrameBucketMs = bucketMs;
				this.frameSequence++;
				cachePreviewFrameLocked(seekMs, previewBytes, preview, false);
			}
		}

		private boolean tryCapturePreviewFrame(String reason) {
			try {
				capturePreviewFrame();
				return true;
			} catch (IOException exception) {
				synchronized (this.lock) {
					boolean hasCachedFrame = !this.live && hasCachedFrameForPositionLocked(this.positionMs);
					if (this.paused) {
						this.status = hasCachedFrame ? "PAUSED" : "BUFFERING";
					} else if (!this.live) {
						this.status = hasCachedFrame ? "PLAYING" : "BUFFERING";
					}
				}
				if (shouldLogPreviewFailure(reason, exception)) {
					Lg2.LOGGER.debug("Failed to capture YouTube preview frame during {} for session {}", reason, this.sessionId, exception);
				}
				return false;
			}
		}

		private boolean shouldLogPreviewFailure(String reason, IOException exception) {
			if ("seek".equals(reason)) {
				String message = exception.getMessage();
				if (message == null) {
					return false;
				}
				return !message.contains("Timed out waiting for ffmpeg preview frame")
						&& !message.contains("Failed to capture YouTube preview frame")
						&& !message.contains("ffmpeg returned an unreadable preview frame");
			}
			return true;
		}

		private void startStream() throws IOException {
			Process startedProcess;
			synchronized (this.lock) {
				if (this.streamUrl.isBlank()) {
					throw new IOException("session is not loaded");
				}
				stopLocked();
				List<String> command = new ArrayList<>();
				command.add(ffmpegBin());
				command.add("-hide_banner");
				command.add("-loglevel");
				command.add("error");
				command.add("-nostdin");
				if (!this.live && this.positionMs > 0L) {
					command.add("-ss");
					command.add(String.format(Locale.ROOT, "%.3f", this.positionMs / 1000.0D));
				}
				command.add("-re");
				command.add("-i");
				command.add(this.streamUrl);
				command.add("-an");
				command.add("-vf");
				command.add("fps=" + FRAME_RATE + ",scale=w=" + effectiveFrameWidthLocked() + ":h=-2:force_original_aspect_ratio=decrease");
				command.add("-q:v");
				command.add("5");
				command.add("-f");
				command.add("image2pipe");
				command.add("-vcodec");
				command.add("mjpeg");
				command.add("-");

				ProcessBuilder builder = new ProcessBuilder(command);
				builder.redirectError(ProcessBuilder.Redirect.DISCARD);
				startedProcess = builder.start();
				this.process = startedProcess;
				this.playBasePositionMs = this.positionMs;
				this.playStartedAtNanos = System.nanoTime();
				this.lastAccessAtMillis = System.currentTimeMillis();
				Thread thread = new Thread(() -> readFrames(startedProcess), "lg2-youtube-" + this.sessionId);
				thread.setDaemon(true);
				this.readerThread = thread;
				thread.start();
			}
		}

		private void readFrames(Process processToRead) {
			try (InputStream stream = processToRead.getInputStream()) {
				readJpegFrames(stream, frameBytes -> {
					BufferedImage image = ImageIO.read(new ByteArrayInputStream(frameBytes));
					if (image == null) {
						return;
					}
					synchronized (this.lock) {
						if (this.process != processToRead) {
							return;
						}
						long framePositionMs = currentPositionMsLocked();
						this.latestFrame = image;
						this.latestFrameBucketMs = normalizeBucketMs(framePositionMs);
						this.frameSequence++;
						cachePreviewFrameLocked(framePositionMs, frameBytes, image, shouldExtendBufferedRangeLocked(framePositionMs));
						if (this.paused) {
							this.status = "PAUSED";
						} else if (this.live) {
							this.status = "LIVE";
						} else {
							this.status = "PLAYING";
						}
					}
				});
			} catch (IOException exception) {
				if (!isExpectedStreamShutdown(processToRead, exception, false)) {
					Lg2.LOGGER.warn("YouTube frame reader stopped for session {}", this.sessionId, exception);
				}
			} finally {
				synchronized (this.lock) {
					if (this.process == processToRead) {
						if (!this.live && !this.paused) {
							this.positionMs = currentPositionMsLocked();
						}
						if (!Objects.equals(this.status, "PAUSED") && !Objects.equals(this.status, "CLOSED")) {
							this.status = this.sourceUrl.isBlank() ? "IDLE" : "BUFFERING";
						}
						this.process = null;
						this.readerThread = null;
					}
				}
				processToRead.destroy();
			}
		}

		private void ensurePrefetchStarted() {
			try {
				synchronized (this.lock) {
					startPrefetchLocked();
				}
			} catch (IOException exception) {
				Lg2.LOGGER.debug("Failed to start YouTube prefetch for session {}", this.sessionId, exception);
			}
		}

		private void startPrefetchLocked() throws IOException {
			if (this.live || this.staticVisual || this.streamUrl.isBlank() || closedOrUnloadedLocked() || this.prefetchCompleted) {
				return;
			}
			if (this.prefetchProcess != null || (this.prefetchReaderThread != null && this.prefetchReaderThread.isAlive())) {
				return;
			}
			long startMs = nextPrefetchStartMsLocked();
			long maxTargetMs = desiredCacheMaxMsLocked();
			long durationMs = Math.max(0L, maxTargetMs - startMs + PREVIEW_CACHE_BUCKET_MS);
			if (this.durationMs > 0L && startMs >= this.durationMs) {
				this.prefetchCompleted = true;
				return;
			}
			if (durationMs <= 0L) {
				this.prefetchCompleted = true;
				return;
			}
			ProcessBuilder builder = new ProcessBuilder(sequentialPrefetchCommand(this.streamUrl, startMs, durationMs, effectiveFrameWidthLocked()));
			builder.redirectError(ProcessBuilder.Redirect.DISCARD);
			Process startedProcess = builder.start();
			this.prefetchProcess = startedProcess;
			Thread thread = new Thread(() -> readPrefetchFrames(startedProcess, startMs), "lg2-youtube-prefetch-" + this.sessionId);
			thread.setDaemon(true);
			this.prefetchReaderThread = thread;
			thread.start();
		}

		private void readPrefetchFrames(Process processToRead, long startPositionMs) {
			long[] nextPositionMs = {Math.max(0L, normalizeBucketMs(startPositionMs))};
			boolean completedNormally = false;
			try (InputStream stream = processToRead.getInputStream()) {
				readJpegFrames(stream, frameBytes -> {
					long positionMs = nextPositionMs[0];
					nextPositionMs[0] += PREVIEW_CACHE_BUCKET_MS;
					BufferedImage image = decodeImageBytes(frameBytes);
					synchronized (this.lock) {
						if (this.prefetchProcess != processToRead) {
							return;
						}
						cachePreviewFrameLocked(positionMs, frameBytes, image, true);
					}
				});
				completedNormally = true;
			} catch (IOException exception) {
				if (!isExpectedStreamShutdown(processToRead, exception, true)) {
					Lg2.LOGGER.debug("YouTube prefetch reader stopped for session {}", this.sessionId, exception);
				}
			} finally {
				synchronized (this.lock) {
					if (this.prefetchProcess == processToRead) {
						this.prefetchProcess = null;
						this.prefetchReaderThread = null;
						if (completedNormally) {
							this.prefetchCompleted = true;
						}
					}
				}
				processToRead.destroy();
			}
		}

		private long currentPositionMsLocked() {
			if (this.live || this.paused || this.playStartedAtNanos == 0L) {
				return this.positionMs;
			}
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this.playStartedAtNanos);
			long position = this.playBasePositionMs + elapsedMs;
			if (this.durationMs > 0L) {
				position = Math.min(position, this.durationMs);
			}
			return Math.max(0L, position);
		}

		private void anchorPlaybackClockLocked(long positionMs) {
			this.positionMs = Math.max(0L, positionMs);
			this.playBasePositionMs = this.positionMs;
			this.playStartedAtNanos = System.nanoTime();
		}

		private void freezePlaybackClockLocked() {
			this.playBasePositionMs = this.positionMs;
			this.playStartedAtNanos = System.nanoTime();
		}

		private int effectiveFrameWidthLocked() {
			return Math.max(FRAME_WIDTH, this.directFrameWidth);
		}

		private int clampDirectFrameWidth(int preferredFrameWidth) {
			return Math.max(FRAME_WIDTH, Math.min(DIRECT_FRAME_WIDTH_MAX, Math.max(DIRECT_FRAME_WIDTH_MIN, preferredFrameWidth)));
		}

		private void persistQueuePreload(String url) {
			QueuePreloadSnapshot snapshot = null;
			synchronized (this.lock) {
				if (this.live || this.sourceUrl == null || !this.sourceUrl.equals(url) || this.cachedPreviewFrames.isEmpty()) {
					return;
				}
				NavigableMap<Long, CachedPreviewFrame> frames = new TreeMap<>();
				long maxBucketMs = Math.max(0L, QUEUE_PRELOAD_DURATION_MS - PREVIEW_CACHE_BUCKET_MS);
				for (Map.Entry<Long, CachedPreviewFrame> entry : this.cachedPreviewFrames.entrySet()) {
					if (entry.getKey() > maxBucketMs) {
						break;
					}
					CachedPreviewFrame frame = entry.getValue();
					if (frame == null || frame.bytes() == null || frame.bytes().length == 0) {
						continue;
					}
					frames.put(
							entry.getKey(),
							new CachedPreviewFrame(
									Arrays.copyOf(frame.bytes(), frame.bytes().length),
									null
							)
					);
				}
				if (frames.isEmpty()) {
					return;
				}
				QueueBufferRange range = computeQueueBufferRange(frames);
				snapshot = new QueuePreloadSnapshot(
						new ResolvedYoutube(this.title, this.durationMs, this.live, this.streamUrl, this.audioStreamUrl),
						frames,
						range.bufferedStartMs(),
						range.bufferedEndMs(),
						range.bufferedFromStart()
				);
				if (!isQueuePreloadReady(snapshot)) {
					snapshot = null;
				}
			}
			if (snapshot != null) {
				persistQueuePreloadSnapshot(url, snapshot);
			}
		}

		private boolean applyCachedPreviewLocked(long positionMs) throws IOException {
			Map.Entry<Long, CachedPreviewFrame> previewEntry = findNearestCachedPreviewEntryLocked(positionMs);
			if (previewEntry == null) {
				return false;
			}
			long bucketMs = previewEntry.getKey();
			if (this.latestFrame != null && this.latestFrameBucketMs == bucketMs) {
				return true;
			}
			CachedPreviewFrame previewFrame = previewEntry.getValue();
			if (previewFrame == null || previewFrame.bytes() == null) {
				return false;
			}
			BufferedImage cachedImage = previewFrame.imageRef() != null ? previewFrame.imageRef().get() : null;
			if (cachedImage == null) {
				cachedImage = decodeImageBytes(previewFrame.bytes());
				this.cachedPreviewFrames.put(bucketMs, new CachedPreviewFrame(previewFrame.bytes(), new SoftReference<>(cachedImage)));
			}
			this.latestFrame = cachedImage;
			this.latestFrameBucketMs = bucketMs;
			this.frameSequence++;
			return true;
		}

		private boolean hasCachedFrameForPositionLocked(long targetPositionMs) {
			return findNearestCachedPreviewEntryLocked(targetPositionMs) != null;
		}

		private Map.Entry<Long, CachedPreviewFrame> findNearestCachedPreviewEntryLocked(long targetPositionMs) {
			if (this.cachedPreviewFrames.isEmpty()) {
				return null;
			}
			Map.Entry<Long, CachedPreviewFrame> floor = this.cachedPreviewFrames.floorEntry(targetPositionMs);
			Map.Entry<Long, CachedPreviewFrame> ceiling = this.cachedPreviewFrames.ceilingEntry(targetPositionMs);
			Map.Entry<Long, CachedPreviewFrame> candidate = null;
			if (floor != null && ceiling != null) {
				long floorDistance = Math.abs(targetPositionMs - floor.getKey());
				long ceilingDistance = Math.abs(ceiling.getKey() - targetPositionMs);
				candidate = floorDistance <= ceilingDistance ? floor : ceiling;
			} else if (floor != null) {
				candidate = floor;
			} else if (ceiling != null) {
				candidate = ceiling;
			}
			if (candidate == null || Math.abs(candidate.getKey() - targetPositionMs) > CACHE_LOOKUP_TOLERANCE_MS) {
				return null;
			}
			return candidate;
		}

		private boolean shouldExtendBufferedRangeLocked(long positionMs) {
			long bucketMs = normalizeBucketMs(positionMs);
			if (!this.bufferedFromStart) {
				return bucketMs == 0L;
			}
			return bucketMs <= this.bufferedEndMs + CONTIGUOUS_BUFFER_TOLERANCE_MS;
		}

		private long nextPrefetchStartMsLocked() {
			if (this.fullCacheRequested && !this.bufferedFromStart) {
				return 0L;
			}
			if (this.cachedPreviewFrames.isEmpty()) {
				return 0L;
			}
			long currentBucketMs = normalizeBucketMs(this.positionMs);
			if (currentBucketMs < this.bufferedStartMs - CONTIGUOUS_BUFFER_TOLERANCE_MS
					|| currentBucketMs > this.bufferedEndMs + CONTIGUOUS_BUFFER_TOLERANCE_MS) {
				return currentBucketMs;
			}
			return Math.max(0L, normalizeBucketMs(this.bufferedEndMs + PREVIEW_CACHE_BUCKET_MS));
		}

		private void cachePreviewFrameLocked(long positionMs, byte[] frameBytes, BufferedImage decodedImage, boolean extendBufferedRange) {
			if (this.live || frameBytes == null || frameBytes.length == 0) {
				return;
			}
			long bucketMs = normalizeBucketMs(positionMs);
			if (!this.cachedPreviewFrames.containsKey(bucketMs)) {
				this.cachedPreviewFrames.put(
						bucketMs,
						new CachedPreviewFrame(
								Arrays.copyOf(frameBytes, frameBytes.length),
								decodedImage != null ? new SoftReference<>(decodedImage) : null
						)
				);
			}
			trimCachedPreviewWindowLocked();
			recomputeBufferedRangeLocked();
		}

		private void recomputeBufferedRangeLocked() {
			if (this.cachedPreviewFrames.isEmpty()) {
				this.bufferedFromStart = false;
				this.bufferedStartMs = 0L;
				this.bufferedEndMs = 0L;
				return;
			}
			long startMs = this.cachedPreviewFrames.firstKey();
			long endMs = startMs;
			for (Long candidateMs : this.cachedPreviewFrames.tailMap(startMs, false).keySet()) {
				if (candidateMs > endMs + CONTIGUOUS_BUFFER_TOLERANCE_MS) {
					break;
				}
				endMs = candidateMs;
			}
			this.bufferedStartMs = startMs;
			this.bufferedEndMs = endMs;
			this.bufferedFromStart = startMs == 0L;
		}

		private long desiredCacheMinMsLocked() {
			if (this.fullCacheRequested) {
				return 0L;
			}
			return Math.max(0L, normalizeBucketMs(this.positionMs - CURRENT_VIDEO_CACHE_BEHIND_MS));
		}

		private long desiredCacheMaxMsLocked() {
			if (this.fullCacheRequested && this.durationMs > 0L) {
				return Math.max(0L, normalizeBucketMs(this.durationMs));
			}
			long maxMs = normalizeBucketMs(this.positionMs + CURRENT_VIDEO_CACHE_AHEAD_MS);
			if (this.durationMs > 0L) {
				maxMs = Math.min(normalizeBucketMs(this.durationMs), maxMs);
			}
			return Math.max(0L, maxMs);
		}

		private void requestFullCache() throws IOException {
			synchronized (this.lock) {
				if (this.live || closedOrUnloadedLocked()) {
					return;
				}
				this.fullCacheRequested = true;
				this.prefetchCompleted = false;
				if (this.prefetchProcess == null) {
					startPrefetchLocked();
				}
			}
		}

		private void trimCachedPreviewWindowLocked() {
			if (this.cachedPreviewFrames.isEmpty()) {
				return;
			}
			long keepMinMs = desiredCacheMinMsLocked();
			long keepMaxMs = desiredCacheMaxMsLocked();
			while (!this.cachedPreviewFrames.isEmpty() && this.cachedPreviewFrames.firstKey() < keepMinMs) {
				this.cachedPreviewFrames.pollFirstEntry();
			}
			while (!this.cachedPreviewFrames.isEmpty() && this.cachedPreviewFrames.lastKey() > keepMaxMs) {
				this.cachedPreviewFrames.pollLastEntry();
			}
			while (this.cachedPreviewFrames.size() > MAX_SESSION_CACHED_PREVIEW_FRAMES) {
				long firstDistance = Math.abs(this.cachedPreviewFrames.firstKey() - this.positionMs);
				long lastDistance = Math.abs(this.cachedPreviewFrames.lastKey() - this.positionMs);
				if (firstDistance >= lastDistance) {
					this.cachedPreviewFrames.pollFirstEntry();
				} else {
					this.cachedPreviewFrames.pollLastEntry();
				}
			}
		}

		private boolean closedOrUnloadedLocked() {
			return Objects.equals(this.status, "CLOSED") || this.streamUrl.isBlank();
		}

		private long normalizeBucketMs(long positionMs) {
			return Math.max(0L, (positionMs / PREVIEW_CACHE_BUCKET_MS) * PREVIEW_CACHE_BUCKET_MS);
		}

		private boolean isExpectedStreamShutdown(Process processToRead, IOException exception, boolean prefetch) {
			String message = exception.getMessage();
			synchronized (this.lock) {
				if (Thread.currentThread().isInterrupted()) {
					return true;
				}
				if (message != null && message.contains("Stream closed")) {
					return true;
				}
				if (prefetch) {
					return this.prefetchProcess != processToRead || Objects.equals(this.status, "CLOSED");
				}
				return this.process != processToRead || Objects.equals(this.status, "PAUSED") || Objects.equals(this.status, "CLOSED");
			}
		}

		private void stopLocked() {
			Process running = this.process;
			this.process = null;
			this.readerThread = null;
			if (running == null) {
				return;
			}
			running.destroy();
			try {
				if (!running.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
					running.destroyForcibly();
					running.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				running.destroyForcibly();
			}
		}

		private void stopPrefetchLocked() {
			Process running = this.prefetchProcess;
			this.prefetchProcess = null;
			this.prefetchReaderThread = null;
			if (running == null) {
				return;
			}
			running.destroy();
			try {
				if (!running.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
					running.destroyForcibly();
					running.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				running.destroyForcibly();
			}
		}
	}

	public record QueueEntryCacheStatus(float fraction, boolean active, boolean complete) {
	}
}
