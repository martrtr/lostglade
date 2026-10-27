package com.lostglade.server;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Executes the production state machines, including the reported 48/49 tail. */
public final class CameraVideoPipelineTest {
	public static void main(String[] args) throws Exception {
		sealPreservesProgressAndDrainsEverySnapshot();
		growingPreviewLoopsWithoutSkippingOnAppend();
		previewSurvivesCompaction();
		creativeFrameCopiesRetainIdentity();
		cachedPhotosCannotCrossRequestsOrSessions();
		photoDeliveryTracksEachViewerIndependently();
		completedVideoReleasesPhotoRenderer();
		terrainWarmupPreservesCaptureResolution();
		photoCancellationReleasesProviderExactlyOnce();
		historyCheckpointPreservesDifferentFrames();
		System.out.println("Camera video pipeline behavior checks passed");
	}

	private static void terrainWarmupPreservesCaptureResolution() throws Exception {
		Class<?> video = Class.forName("com.lostglade.client.RendererBotClientVideoRecording");
		var dimension = video.getDeclaredMethod("warmupDimension", int.class, int.class);
		dimension.setAccessible(true);
		require((int) dimension.invoke(null, 2048, 1024) == 256, "terrain warmup must bound its long edge");
		require((int) dimension.invoke(null, 1024, 2048) == 128, "terrain warmup must preserve the camera aspect ratio");
		require((int) dimension.invoke(null, 128, 128) == 128, "small targets must not be enlarged");
		var pose = new com.lostglade.network.RendererBotPayloads.VideoCameraFrame(0D, 0D, 0D, 0F, 0F);
		var payload = new com.lostglade.network.RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload(
				UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 0D, 0D, 0D, 0F, 0F,
				null, 128, 128, 128, 128, 70, 10, 600, List.of(pose, pose));
		Class<?> type = Class.forName("com.lostglade.client.RendererBotClientVideoRecording$PendingRecording");
		var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
		Object recording = constructor.newInstance(payload, null, java.io.OutputStream.nullOutputStream(),
				java.nio.file.Path.of("unused.tmp"), java.nio.file.Path.of("unused.mp4"), 2048, 1024);
		require(!(boolean) call(recording, "prepareCaptureResolution", new Class<?>[]{boolean.class}, false), "unsettled terrain should warm up cheaply");
		require((boolean) call(recording, "prepareCaptureResolution", new Class<?>[]{boolean.class}, true), "settled terrain must promote to encoder resolution");
		for (int retry = 0; retry < 10; retry++) {
			require((boolean) call(recording, "prepareCaptureResolution", new Class<?>[]{boolean.class}, false), "resize must not oscillate between warmup and capture targets");
		}
		call(recording, "advanceAfterEncodedFrame", new Class<?>[0]);
		require(!(boolean) call(recording, "prepareCaptureResolution", new Class<?>[]{boolean.class}, false), "the next scene must wait for its own terrain");
		require(field(recording, "captureWidth").getInt(recording) == 2048 && field(recording, "captureHeight").getInt(recording) == 1024,
				"warmup must preserve final MP4 dimensions");
	}

	private static void completedVideoReleasesPhotoRenderer() throws Exception {
		var pose = new com.lostglade.network.RendererBotPayloads.VideoCameraFrame(0D, 0D, 0D, 0F, 0F);
		var payload = new com.lostglade.network.RendererBotPayloads.RendererBotVideoRecordingStartS2CPayload(
				UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 0D, 0D, 0D, 0F, 0F,
				null, 128, 128, 128, 128, 70, 10, 600, List.of(pose));
		Class<?> type = Class.forName("com.lostglade.client.RendererBotClientVideoRecording$PendingRecording");
		Constructor<?> constructor = type.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		Object recording = constructor.newInstance(payload, null, java.io.OutputStream.nullOutputStream(),
				java.nio.file.Path.of("unused.tmp"), java.nio.file.Path.of("unused.mp4"), 128, 128);
		var recordingsField = Class.forName("com.lostglade.client.RendererBotClientVideoRecording").getDeclaredField("RECORDINGS");
		recordingsField.setAccessible(true);
		@SuppressWarnings("unchecked") var recordings = (java.util.Map<UUID, Object>) recordingsField.get(null);
		Class<?> shadow = Class.forName("com.lostglade.client.RendererBotShadowWorldManager");
		var deadlinesField = shadow.getDeclaredField("PENDING_SESSION_DESTROY_AT"); deadlinesField.setAccessible(true);
		@SuppressWarnings("unchecked") var deadlines = (java.util.Map<UUID, Long>) deadlinesField.get(null);
		var expire = shadow.getDeclaredMethod("expirePendingSessionDestroys", long.class); expire.setAccessible(true);
		recordings.put(payload.requestId(), recording);
		deadlines.put(payload.renderSessionId(), 1L);
		try {
			require(((List<?>) expire.invoke(null, 100_000L)).isEmpty(), "old Destroy erased a retained recovery session");
		} finally { recordings.remove(payload.requestId()); deadlines.remove(payload.renderSessionId()); }
		require((boolean) call(recording, "needsRenderWork", new Class<?>[0]), "live video released GPU prematurely");
		field(recording, "sealed").setBoolean(recording, true);
		field(recording, "nextFrameIndex").setInt(recording, 1);
		require(!(boolean) call(recording, "needsRenderWork", new Class<?>[0]), "finished replay blocks photos before encoder close");
		field(recording, "finishScheduled").setBoolean(recording, true);
		require(!(boolean) call(recording, "needsRenderWork", new Class<?>[0]), "MP4 encoding blocks photos");
		call(recording, "markFinalized", new Class<?>[]{long.class, int.class}, 100L, 10);
		for (int retry = 0; retry < 10; retry++) {
			call(recording, "resetCompletionDeliveryForReconnect", new Class<?>[0]);
			require(!(boolean) call(recording, "needsRenderWork", new Class<?>[0]), "ACK retry/reconnect re-acquired the GPU");
		}
	}

	private static void photoCancellationReleasesProviderExactlyOnce() throws Exception {
		var id = UUID.randomUUID();
		var cancellations = new java.util.concurrent.atomic.AtomicInteger();
		var provider = new com.lostglade.server.map.MapPixelProvider() {
			public UUID ownerId() { return id; }
			public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension() { return null; }
			public void onCancelled(net.minecraft.server.MinecraftServer server) { cancellations.incrementAndGet(); }
		};
		Class<?> jobType = Class.forName("com.lostglade.server.map.MapImageRenderSystem$RenderJob");
		var constructor = jobType.getDeclaredConstructors()[0]; constructor.setAccessible(true);
		Object job = constructor.newInstance(id, null, null, 1, 1, 0, null, provider);
		var jobsField = com.lostglade.server.map.MapImageRenderSystem.class.getDeclaredField("PLAYER_JOBS"); jobsField.setAccessible(true);
		@SuppressWarnings("unchecked") var jobs = (java.util.Map<UUID, Object>) jobsField.get(null);
		jobs.put(id, job);
		com.lostglade.server.map.MapImageRenderSystem.cancelRender(id);
		com.lostglade.server.map.MapImageRenderSystem.cancelRender(id);
		require(cancellations.get() == 1 && !jobs.containsKey(id), "photo removed locally without releasing its renderer, or released twice");
	}

	private static void historyCheckpointPreservesDifferentFrames() throws Exception {
		var init = new com.lostglade.network.RendererBotPayloads.RendererBotShadowLevelInitS2CPayload(UUID.randomUUID(),
				"minecraft:overworld", "minecraft:overworld", 42L, false, false, false, 2, 10L, 20L, false, true, 0.5F, 0.2F, 63, 8, 8, true);
		Class<?> type = Class.forName("com.lostglade.client.RendererBotShadowWorldManager$VideoShadowTimeline");
		var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
		Object timeline = constructor.newInstance(init);
		Class<?> nodeType = Class.forName("com.lostglade.client.RendererBotShadowWorldManager$VideoShadowTimeline$ChunkNode");
		var nodeConstructor = nodeType.getDeclaredConstructors()[0]; nodeConstructor.setAccessible(true);
		Object firstNode = nodeConstructor.newInstance(null, 42L, new byte[]{1, 2});
		field(timeline, "latestChunk").set(timeline, firstNode);
		call(timeline, "capture", new Class<?>[]{int.class}, 0);
		field(timeline, "latestChunk").set(timeline, nodeConstructor.newInstance(firstNode, 42L, new byte[]{3, 4}));
		field(timeline, "dayTime").setLong(timeline, 99L);
		call(timeline, "appendPackets", new Class<?>[]{List.class}, List.of(new com.lostglade.network.RendererBotPayloads.ShadowPacketData("test:entity", new byte[]{5})));
		call(timeline, "capture", new Class<?>[]{int.class}, 1);
		var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		try {
			call(timeline, "writeCheckpoint", new Class<?>[]{buffer.getClass()}, buffer);
			var read = type.getDeclaredMethod("readCheckpoint", buffer.getClass()); read.setAccessible(true);
			Object restored = read.invoke(null, buffer);
			@SuppressWarnings("unchecked") var firstChunks = (java.util.Map<Long, byte[]>) field(restored, "firstChunks").get(restored);
			require(firstChunks.get(42L)[0] == 1, "terrain backfill uses future terrain instead of the first received baseline");
			for (int index = 0; index < 2; index++) {
				Object frame = call(restored, "snapshot", new Class<?>[]{int.class}, index);
				Object node = call(frame, "chunkNode", new Class<?>[0]);
				byte[] bytes = (byte[]) call(node, "packetBytes", new Class<?>[0]);
				require(bytes[0] == 1 + index * 2, "checkpoint replaced historical terrain with the latest terrain");
				require((long) call(frame, "dayTime", new Class<?>[0]) == (index == 0 ? 20L : 99L), "checkpoint lost recorded clock");
				require((int) call(frame, "packetCount", new Class<?>[0]) == index, "checkpoint lost entity packet boundary");
			}
		} finally { buffer.release(); }
	}

	private static void photoDeliveryTracksEachViewerIndependently() throws Exception {
		Class<?> type = Class.forName("com.lostglade.server.map.PhotoMapDeliverySystem");
		var added = type.getDeclaredMethod("newlyVisible", java.util.Set.class, java.util.Set.class);
		added.setAccessible(true);
		var empty = java.util.Set.<Integer>of();
		var held = java.util.Set.of(99);
		var heldAndFramed = java.util.Set.of(99, 100, 101);
		require(added.invoke(null, empty, held).equals(held), "new owner never receives photo baseline");
		require(added.invoke(null, held, held).equals(empty), "unchanged inventory resends full image");
		require(added.invoke(null, held, heldAndFramed).equals(java.util.Set.of(100, 101)), "placing photo does not deliver frame tiles");
		require(added.invoke(null, empty, heldAndFramed).equals(heldAndFramed), "late viewer inherits another viewer's delivery state");
		require(added.invoke(null, heldAndFramed, empty).equals(empty), "lost visibility sends unrelated maps");
		require(added.invoke(null, empty, heldAndFramed).equals(heldAndFramed), "returning viewer does not receive baseline again");
	}

	private static void creativeFrameCopiesRetainIdentity() {
		var data = new com.lostglade.item.PhotoPrintData(2, 1, 99, new int[]{100, 101},
				com.lostglade.item.PhotoPrintData.MediaKind.VIDEO, "job", 0L, 10);
		require(data.ownsMapId(99) && data.ownsMapId(100) && data.ownsMapId(101), "creative preview/tile copies lost ownership");
		require(!data.ownsMapId(-1) && !data.ownsMapId(102), "unrelated map retained a cancelled job");
		var frame = data.placed(UUID.randomUUID(), net.minecraft.core.BlockPos.ZERO, net.minecraft.core.Direction.NORTH, 1, 0);
		require(frame.asPhotoPrintData().samePhoto(data) && frame.sourceKey().equals("job"), "placement changed video identity");
		var unrelated = new com.lostglade.item.PhotoPrintData(2, 1, 99, new int[]{100, 101},
				com.lostglade.item.PhotoPrintData.MediaKind.VIDEO, "another-job", 0L, 10);
		require(!data.samePhoto(unrelated), "different recordings alias through reused map IDs");
		var photo = new com.lostglade.item.PhotoPrintData(2, 1, 99, new int[]{100, 101},
				com.lostglade.item.PhotoPrintData.MediaKind.PHOTO, "job", 0L, 0);
		require(!data.samePhoto(photo), "photo and video share an identity");
		var legacy = new com.lostglade.item.PhotoPrintData(2, 1, 99, new int[]{100, 101});
		require(photo.samePhoto(legacy) && legacy.samePhoto(photo), "legacy photos without source keys no longer match");
	}

	private static void cachedPhotosCannotCrossRequestsOrSessions() throws Exception {
		UUID request = UUID.randomUUID(), session = UUID.randomUUID(), owner = UUID.randomUUID();
		Class<?> type = Class.forName("com.lostglade.client.RendererBotClientCapture$CapturedFrame");
		var constructor = type.getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		Object cached = constructor.newInstance(request, session, "minecraft:overworld", null, owner,
				1D, 2D, 3D, 4F, 5F, 128, 128, 256, 128, 70, new byte[0], new byte[0], 0L);
		for (int scenario = 0; scenario < 4; scenario++) {
			var payload = new com.lostglade.network.RendererBotPayloads.RendererBotCaptureRequestS2CPayload(
					scenario == 1 ? UUID.randomUUID() : request,
					scenario == 2 ? UUID.randomUUID() : session,
					"minecraft:overworld", 1D, 2D, 3D, 4F, 5F, null,
					scenario == 3 ? UUID.randomUUID() : owner, 128, 128, 256, 128, 70);
			require((boolean) call(cached, "matches", new Class<?>[]{payload.getClass()}, payload) == (scenario == 0),
					"cached photo crossed request/session/owner boundary, scenario " + scenario);
		}
	}

	private static void sealPreservesProgressAndDrainsEverySnapshot() throws Exception {
		Class<?> type = Class.forName("com.lostglade.server.RendererBotCameraSystem$PendingVideoRecording");
		Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
				.filter(c -> c.getParameterTypes()[4] == boolean.class).findFirst().orElseThrow();
		constructor.setAccessible(true);
		Object recording = constructor.newInstance(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
				true, null, 0D, 0D, 0D, 0F, 0F, 70, UUID.randomUUID(), 10, 128, 128, 600, new CompletableFuture<>());
		@SuppressWarnings("unchecked") List<Object> frames = (List<Object>) field(recording, "cameraFrames").get(recording);
		Object pose = frames.getFirst();
		while (frames.size() < 49) frames.add(pose);
		field(recording, "replayFrameIndex").setInt(recording, 48);
		field(recording, "timelineFrameSent").setInt(recording, 46);
		call(recording, "beginDeferredRender", new Class<?>[0]);
		require((int) call(recording, "replayFrameIndex", new Class<?>[0]) == 48, "stop rewound the encoder cursor");
		require((int) call(recording, "markTimelineFrameSent", new Class<?>[0]) == 47, "penultimate boundary skipped");
		require((int) call(recording, "markTimelineFrameSent", new Class<?>[0]) == 48, "final boundary lost after stop");
		require((int) call(recording, "markTimelineFrameSent", new Class<?>[0]) == -1, "duplicate boundary");
		call(recording, "advanceReplayFrame", new Class<?>[]{int.class}, 49);
		require(call(recording, "replayFrame", new Class<?>[0]) == null, "completed cursor still waits on terrain");
		for (int tick = 0; tick < 200; tick++) {
			require(call(recording, "shadowTargetFrame", new Class<?>[0]) == pose,
					"encoding/upload delay discarded the camera target after the final frame");
		}
		call(recording, "advanceReplayFrame", new Class<?>[]{int.class}, 50);
		call(recording, "advanceReplayFrame", new Class<?>[]{int.class}, 48);
		require((int) call(recording, "replayFrameIndex", new Class<?>[0]) == 49, "invalid or stale progress moved the cursor");
		require((boolean) call(recording, "acceptResumeCursor", new Class<?>[]{int.class}, 0), "cold encoder cannot restart an archived recording");
		require(!(boolean) call(recording, "acceptResumeCursor", new Class<?>[]{int.class}, 0), "duplicate resume rewinds an active encoder");
		call(recording, "advanceReplayFrame", new Class<?>[]{int.class}, 49);
		// Catching up before Stop has the same empty-queue condition.
		field(recording, "deferredRenderRequested").setBoolean(recording, false);
		require(call(recording, "shadowTargetFrame", new Class<?>[0]) == pose, "live catch-up lost the target");
		frames.add(pose);
		require(call(recording, "replayFrame", new Class<?>[0]) == pose, "next live pose was not renderable");
	}

	private static Object preview() throws Exception {
		Constructor<?> c = Class.forName("com.lostglade.server.CameraAnimatedMapPlaybackSystem$PendingVideoPreview").getDeclaredConstructor();
		c.setAccessible(true);
		return c.newInstance();
	}

	private static void append(Object preview, int color, int fullSize) throws Exception {
		byte[] small = new byte[128 * 128];
		byte[] full = new byte[fullSize];
		Arrays.fill(small, (byte) color);
		Arrays.fill(full, (byte) color);
		call(preview, "append", new Class<?>[]{byte[].class, byte[].class}, small, full);
	}

	private static int color(Object preview, long tick) throws Exception {
		return ((byte[]) call(preview, "frameAt", new Class<?>[]{long.class, int.class}, tick, 10))[0] & 255;
	}

	private static void growingPreviewLoopsWithoutSkippingOnAppend() throws Exception {
		Object preview = preview();
		append(preview, 1, 16384);
		append(preview, 2, 16384);
		require(color(preview, 100) == 1, "loop must start at first frame");
		append(preview, 3, 16384);
		require(color(preview, 102) == 2, "append changed the current loop position");
		require(color(preview, 104) == 1, "next loop must restart at first frame");
		require(color(preview, 106) == 2 && color(preview, 108) == 3 && color(preview, 110) == 1,
				"next loop did not include the newly rendered suffix");
		byte[] full = (byte[]) call(preview, "fullFrameAt", new Class<?>[]{long.class, int.class, int.class, int.class}, 112L, 10, 1, 1);
		require(full[0] == 2, "held and framed previews disagree");
	}

	private static void previewSurvivesCompaction() throws Exception {
		Object preview = preview();
		for (int i = 0; i < 180; i++) append(preview, i, 6 * 4 * 128 * 128);
		require((long) call(preview, "estimatedBytes", new Class<?>[0]) <= 64L * 1024 * 1024, "preview exceeded memory budget");
		require(color(preview, 0) == 0 && color(preview, 4) == 2, "compaction lost chronology or playback speed");
	}

	private static Field field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
	private static Object call(Object target, String name, Class<?>[] parameters, Object... values) throws Exception {
		Method method = target.getClass().getDeclaredMethod(name, parameters);
		method.setAccessible(true);
		return method.invoke(target, values);
	}
	private static void require(boolean valid, String message) {
		if (!valid) throw new AssertionError(message);
	}
}
