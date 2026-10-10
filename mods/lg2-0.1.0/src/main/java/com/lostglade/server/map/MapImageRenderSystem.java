package com.lostglade.server.map;

import com.lostglade.server.AccountAuthSystem;

import com.lostglade.Lg2;
import com.lostglade.config.Lg2Config;
import com.lostglade.item.PhotoPrintData;
import com.lostglade.server.CameraCaptureSystem;
import com.lostglade.server.CameraMediaCache;
import com.lostglade.server.ServerMechanicsGateSystem;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.core.Holder;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class MapImageRenderSystem {
	private static final Identifier CAMERA_PRINT_SOUND_ID = Identifier.fromNamespaceAndPath("lg2", "camera_print");
	private static final Holder<SoundEvent> CAMERA_PRINT_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(CAMERA_PRINT_SOUND_ID));
	private static final float CAMERA_PRINT_VOLUME = 0.55F;
	private static final float CAMERA_PRINT_PITCH = 1.0F;
	private static final int MAP_SIZE = 128;
	private static final int PHOTO_MAP_CENTER = 30_000_000;
	private static final int RESULTS_APPLIED_PER_TICK = 384;
	private static final int FRAME_PIXELS_APPLIED_PER_TICK = 4096;
	private static final int MAX_PIXEL_FAILURES = 64;
	private static final long MAX_PREPARE_NANOS_PER_TICK = 1_000_000L;
	private static final long PHOTO_PREVIEW_REPRIME_GRACE_TICKS = 40L;
	private static final long PHOTO_PREVIEW_REPRIME_INTERVAL_TICKS = 5L;
	private static final Map<UUID, RenderJob> PLAYER_JOBS = new HashMap<>();
	private static final Map<UUID, Long> PENDING_PREVIEW_REPRIME_DEADLINES = new HashMap<>();
	private static final Queue<UUID> QUEUE = new ArrayDeque<>();
	private static UUID activePlayerId;
	private static ExecutorService executor;

	private MapImageRenderSystem() {
	}

	public static void register() {
		ensureExecutor();
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				AccountAuthSystem.runWhenAuthenticated(handler.player,
						() -> server.execute(() -> schedulePhotoPreviewReprime((ServerPlayer) handler.player, server)))
		);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				server.execute(() -> clearPhotoPreviewReprime(handler.player.getUUID()))
		);
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			MinecraftServer server = newPlayer.level().getServer();
			if (server != null) {
				server.execute(() -> schedulePhotoPreviewReprime(newPlayer, server));
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(MapImageRenderSystem::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			PENDING_PREVIEW_REPRIME_DEADLINES.clear();
			shutdownExecutor();
		});
	}

	public static boolean hasActiveRender(UUID playerId) {
		return playerId != null && PLAYER_JOBS.containsKey(playerId);
	}

	public static void cancelRender(UUID playerId) {
		if (playerId == null) {
			return;
		}
		removeJob(playerId);
	}

	public static boolean startRender(ServerPlayer player, Component itemName, MapPixelProvider provider) {
		if (player == null || provider == null || hasActiveRender(player.getUUID())) {
			return false;
		}
		int mapsWide = Math.max(1, provider.mapTilesWide());
		int mapsHigh = Math.max(1, provider.mapTilesHigh());
		PhotoMapSet photoMapSet = createPhotoMapSet(player, itemName, mapsWide, mapsHigh);
		if (photoMapSet == null) {
			return false;
		}
		PreviewMap previewMap = preparePreviewMap(player, itemName, photoMapSet);
		if (previewMap == null || previewMap.previewMapId() == null) {
			return false;
		}
		givePhotoItem(
				player,
				PhotoPrintData.createPhotoItem(
						itemName,
						mapsWide,
						mapsHigh,
						previewMap.previewMapId(),
						photoMapSet.mapIds(),
						provider.mediaKind(),
						provider.sourceKey(),
						provider.mediaDurationMs(),
						provider.mediaFps()
				)
		);
		RenderJob job = new RenderJob(player.getUUID(), photoMapSet.mapIds(), photoMapSet.mapDataSet(), mapsWide, mapsHigh, previewMap.previewMapId().id(), previewMap.previewMapData(), provider);
		PLAYER_JOBS.put(player.getUUID(), job);
		QUEUE.offer(player.getUUID());
		ServerMechanicsGateSystem.syncPlayerInventory(player);
		if (activePlayerId == null) {
			activePlayerId = player.getUUID();
			player.displayClientMessage(CameraCaptureSystem.queuedForRenderMessage(player), true);
		} else {
			player.displayClientMessage(CameraCaptureSystem.addedToRenderQueueMessage(player), true);
		}
		return true;
	}

	private static ServerLevel photoMapLevel(MinecraftServer server, ServerLevel fallback) {
		if (server == null) {
			return fallback;
		}
		ServerLevel end = server.getLevel(Level.END);
		if (end != null) {
			return end;
		}
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		return overworld != null ? overworld : fallback;
	}

	private static void tick(MinecraftServer server) {
		ensureExecutor();
		tickQueuedPhotoPreviewReprimes(server);
		if (PLAYER_JOBS.isEmpty()) {
			activePlayerId = null;
			return;
		}
		normalizeQueue();
		if (activePlayerId == null) {
			return;
		}

		RenderJob job = PLAYER_JOBS.get(activePlayerId);
		if (job == null) {
			pollNextActive();
			return;
		}

		ServerPlayer player = server.getPlayerList().getPlayer(job.playerId());
		ServerLevel level = server.getLevel(job.provider().dimension());
		if (player == null || level == null || !job.provider().isValid(server)) {
			removeJob(job.playerId());
			pollNextActive();
			return;
		}
		if (!isPhotoStillReachable(server, player, job.photoData())) {
			job.provider().onCancelled(server);
			player.displayClientMessage(Component.literal("Рендер снимка отменён: снимок больше не в инвентаре или рамке."), true);
			removeJob(job.playerId());
			pollNextActive();
			return;
		}
		beginRender(player, job);
		if (job.provider().prefersWholeFrameRendering()) {
			if (!ensureWholeFrameTaskDispatched(server, player, job)) {
				return;
			}
		}
		// Whole-frame providers already have the final map-sized image.  A second,
		// independent 128x128 readback made the optional preview a hard dependency
		// and could abort an otherwise valid photo.
		if (!job.provider().prefersWholeFrameRendering() && !tickPreviewStage(server, player, job)) {
			return;
		}

		if (job.provider().prefersWholeFrameRendering()) {
			tickWholeFrameJob(server, player, level, job);
			return;
		}

		int processed = 0;
		PixelResult result;
		while (processed < RESULTS_APPLIED_PER_TICK && (result = job.pollResult()) != null) {
			if (result.failed()) {
				job.recordFailure();
				if (!job.hasLoggedFailure() && result.error() != null) {
					Lg2.LOGGER.error("Map image render failed for player {}", job.playerId(), result.error());
					job.markFailureLogged();
				}
				if (job.failureCount() >= MAX_PIXEL_FAILURES) {
					player.displayClientMessage(Component.literal("Рендер снимка остановлен: слишком много ошибок."), true);
					removeJob(job.playerId());
					pollNextActive();
					return;
				}
			} else {
				setPhotoColor(job, result.x(), result.y(), result.color());
			}
			job.finishDispatchedPixel();
			processed++;
		}
		updatePhotoProgress(server, job);

		long tickStart = System.nanoTime();
		while (job.canDispatchMore() && job.nextPixel() < job.totalPixels()) {
			int pixelIndex = job.nextPixel();
			int x = pixelIndex % MAP_SIZE;
			int y = pixelIndex / MAP_SIZE;
			MapPixelProvider.PreparedPixel prepared;
			try {
				prepared = job.provider().preparePixel(server, x, y);
			} catch (Exception exception) {
				job.recordFailure();
				if (!job.hasLoggedFailure()) {
					Lg2.LOGGER.error("Map image prepare failed for player {}", job.playerId(), exception);
					job.markFailureLogged();
				}
				if (job.failureCount() >= MAX_PIXEL_FAILURES) {
					player.displayClientMessage(Component.literal("Подготовка снимка остановлена: слишком много ошибок."), true);
					removeJob(job.playerId());
					pollNextActive();
					return;
				}
				job.advance();
				continue;
			}

			job.dispatchPixel();
			job.advance();
			MapPixelProvider provider = job.provider();
			ExecutorService currentExecutor = executor;
			currentExecutor.submit(() -> {
				try {
					byte color = provider.renderPreparedPixel(prepared);
					job.pushResult(PixelResult.success(prepared.x(), prepared.y(), color));
				} catch (Exception exception) {
					job.pushResult(PixelResult.failure(prepared.x(), prepared.y(), exception));
				}
			});

			if (System.nanoTime() - tickStart >= MAX_PREPARE_NANOS_PER_TICK) {
				break;
			}
		}

		if (job.isDone() && !job.hasDispatchedPixels()) {
			lockRenderedPhoto(level, job);
			finalizePhotoDisplay(server, job);
			job.provider().onCompleted(server);
			player.displayClientMessage(CameraCaptureSystem.captureCompletedMessage(player), true);
			playRenderStartSound(player);
			removeJob(job.playerId());
			pollNextActive();
		}
	}

	private static void tickWholeFrameJob(MinecraftServer server, ServerPlayer player, ServerLevel level, RenderJob job) {
		if (!ensureWholeFrameTaskDispatched(server, player, job)) {
			return;
		}
		applyProgressPreview(server, player, job);

		FrameResult frameResult = job.frameResult();
		if (frameResult == null) {
			return;
		}
		if (frameResult.failed()) {
			Lg2.LOGGER.error("Map frame render failed for player {}", job.playerId(), frameResult.error());
			player.displayClientMessage(Component.literal("Рендер снимка остановлен: ошибка кадра."), true);
			removeJob(job.playerId());
			pollNextActive();
			return;
		}

		byte[] frame = frameResult.pixels();
		if (frame == null || frame.length < job.totalPixels()) {
			Lg2.LOGGER.error("Map frame render returned invalid frame for player {}", job.playerId());
			player.displayClientMessage(Component.literal("Рендер снимка остановлен: кадр повреждён."), true);
			removeJob(job.playerId());
			pollNextActive();
			return;
		}

		applyPreviewToMaps(job, downscaleFrame(frame, job.outputWidth(), job.outputHeight()));
		applyWholeFrameToMaps(job, frame);
		updatePhotoProgress(server, job);

		if (job.frameApplyIndex() >= job.totalPixels()) {
			lockRenderedPhoto(level, job);
			sendCompletedPhotoMaps(server, job.photoData());
			finalizePhotoDisplay(server, job);
			job.provider().onCompleted(server);
			player.displayClientMessage(CameraCaptureSystem.captureCompletedMessage(player), true);
			playRenderStartSound(player);
			removeJob(job.playerId());
			pollNextActive();
		}
	}

	private static void applyProgressPreview(MinecraftServer server, ServerPlayer player, RenderJob job) {
		byte[] preview = job == null ? null : job.provider().pollProgressPreview();
		if (!isValidFrame(preview, MAP_SIZE * MAP_SIZE) || !job.acceptPreview(preview)) {
			return;
		}
		applyPreviewToMaps(job, preview);
		if (player != null) {
			sendPhotoPreviewMap(player, new MapId(job.previewMapId()), job.previewMapData());
		}
		sendCompletedPhotoMaps(server, job.photoData());
	}

	private static void applyPreviewToMaps(RenderJob job, byte[] preview) {
		if (job == null || !isValidFrame(preview, MAP_SIZE * MAP_SIZE)) return;
		applyFrameToMap(job.previewMapData(), preview);
		for (int tileY = 0; tileY < job.mapsHigh(); tileY++) {
			for (int tileX = 0; tileX < job.mapsWide(); tileX++) {
				MapItemSavedData mapData = job.mapDataSet()[tileY * job.mapsWide() + tileX];
				if (mapData == null || mapData.colors == null || mapData.colors.length < MAP_SIZE * MAP_SIZE) continue;
				for (int y = 0; y < MAP_SIZE; y++) for (int x = 0; x < MAP_SIZE; x++) {
					int sourceX = (tileX * MAP_SIZE + x) * MAP_SIZE / job.outputWidth();
					int sourceY = (tileY * MAP_SIZE + y) * MAP_SIZE / job.outputHeight();
					mapData.colors[y * MAP_SIZE + x] = preview[sourceY * MAP_SIZE + sourceX];
				}
				mapData.setDirty();
			}
		}
	}

	private static byte[] downscaleFrame(byte[] frame, int width, int height) {
		byte[] preview = new byte[MAP_SIZE * MAP_SIZE];
		if (frame == null || frame.length < width * height) return preview;
		for (int y = 0; y < MAP_SIZE; y++) for (int x = 0; x < MAP_SIZE; x++) {
			preview[y * MAP_SIZE + x] = frame[(y * height / MAP_SIZE) * width + x * width / MAP_SIZE];
		}
		return preview;
	}

	private static boolean ensureWholeFrameTaskDispatched(MinecraftServer server, ServerPlayer player, RenderJob job) {
		if (job == null || job.frameResult() != null) {
			return true;
		}
		if (!job.hasPreparedFrame()) {
			try {
				job.setPreparedFrame(job.provider().prepareFrame(server));
			} catch (Exception exception) {
				Lg2.LOGGER.error("Map frame prepare failed for player {}", job.playerId(), exception);
				player.displayClientMessage(Component.literal("Подготовка снимка остановлена: ошибка кадра."), true);
				removeJob(job.playerId());
				pollNextActive();
				return false;
			}
		}

		if (job.hasDispatchedFrameTask()) {
			return true;
		}
		job.markFrameTaskDispatched();
		Object preparedFrame = job.preparedFrame();
		MapPixelProvider provider = job.provider();
		executor.submit(() -> {
			try {
				byte[] frame = provider.renderPreparedFrame(preparedFrame);
				job.pushFrameResult(FrameResult.success(frame));
			} catch (Throwable throwable) {
				job.pushFrameResult(FrameResult.failure(throwable));
			}
		});
		return true;
	}

	private static void applyWholeFrameToMaps(RenderJob job, byte[] frame) {
		if (job == null || frame == null) {
			return;
		}
		int outputWidth = job.outputWidth();
		for (int tileY = 0; tileY < job.mapsHigh(); tileY++) {
			for (int tileX = 0; tileX < job.mapsWide(); tileX++) {
				int tileIndex = tileY * job.mapsWide() + tileX;
				if (tileIndex < 0 || tileIndex >= job.mapDataSet().length) {
					continue;
				}
				MapItemSavedData mapData = job.mapDataSet()[tileIndex];
				if (mapData == null || mapData.colors == null || mapData.colors.length < MAP_SIZE * MAP_SIZE) {
					continue;
				}
				for (int row = 0; row < MAP_SIZE; row++) {
					int sourceOffset = (tileY * MAP_SIZE + row) * outputWidth + tileX * MAP_SIZE;
					int targetOffset = row * MAP_SIZE;
					System.arraycopy(frame, sourceOffset, mapData.colors, targetOffset, MAP_SIZE);
				}
				mapData.setDirty();
			}
		}
		job.markFrameFullyApplied();
	}

	private static void playRenderStartSound(ServerPlayer player) {
		Holder<SoundEvent> sound = PolymerResourcePackUtils.hasMainPack(player)
				? CAMERA_PRINT_SOUND
				: BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.EXPERIENCE_ORB_PICKUP);
		float pitch = PolymerResourcePackUtils.hasMainPack(player) ? CAMERA_PRINT_PITCH : 1.15F;
		player.connection.send(new ClientboundSoundPacket(
				sound,
				SoundSource.PLAYERS,
				player.getX(),
				player.getY(),
				player.getZ(),
				CAMERA_PRINT_VOLUME,
				pitch,
				player.level().getRandom().nextLong()
		));
	}

	private static void lockRenderedPhoto(ServerLevel level, RenderJob job) {
		if (level == null || job == null) {
			return;
		}
		for (int i = 0; i < job.mapIds().length; i++) {
			MapId mapId = job.mapIds()[i];
			MapItemSavedData mapData = job.mapDataSet()[i];
			if (mapId == null || mapData == null || mapData.locked) {
				continue;
			}
			level.setMapData(mapId, mapData.locked());
		}
	}

	private static void updatePhotoProgress(MinecraftServer server, RenderJob job) {
		if (server == null || job == null) {
			return;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(job.playerId());
		if (player == null) {
			return;
		}
		int progress = job.progressPercent();
		if (progress <= job.lastDisplayedProgress() || progress >= 100) {
			return;
		}
		player.displayClientMessage(CameraCaptureSystem.createQueuedPhotoName(progress), true);
		job.setLastDisplayedProgress(progress);
	}

	private static void finalizePhotoDisplay(MinecraftServer server, RenderJob job) {
		if (server == null || job == null) {
			return;
		}
		Component completedName = CameraCaptureSystem.createCompletedPhotoName(server);
		updatePhotoItemsInInventories(server, job.photoData(), completedName);
		updatePlacedPhotoFrameNames(server, job.photoData(), completedName);
		job.setLastDisplayedProgress(100);
	}

	private static void sendCompletedPhotoMaps(MinecraftServer server, PhotoPrintData photoData) {
		if (server == null || photoData == null || !photoData.isValid()) {
			return;
		}
		ServerLevel fallbackLevel = server.overworld();
		if (fallbackLevel == null) {
			for (ServerLevel candidate : server.getAllLevels()) {
				fallbackLevel = candidate;
				break;
			}
		}
		if (fallbackLevel == null) {
			return;
		}
		ServerLevel mapLevel = photoMapLevel(server, fallbackLevel);
		for (int rawMapId : photoData.mapIds()) {
			if (rawMapId < 0) {
				continue;
			}
			MapId mapId = new MapId(rawMapId);
			MapItemSavedData mapData = mapLevel.getMapData(mapId);
			if (mapData == null || mapData.colors == null || mapData.colors.length < MAP_SIZE * MAP_SIZE) {
				continue;
			}
			for (ServerPlayer viewer : AccountAuthSystem.authenticatedPlayers(server)) {
				sendPhotoMap(viewer, mapId, mapData);
			}
		}
	}

	private static PhotoMapSet createPhotoMapSet(ServerPlayer player, Component itemName, int mapsWide, int mapsHigh) {
		ServerLevel level = (ServerLevel) player.level();
		ServerLevel mapLevel = photoMapLevel(player.level().getServer(), level);
		MapId[] mapIds = new MapId[mapsWide * mapsHigh];
		MapItemSavedData[] mapDataSet = new MapItemSavedData[mapIds.length];
		for (int i = 0; i < mapIds.length; i++) {
			ItemStack map = MapItem.create(mapLevel, PHOTO_MAP_CENTER, PHOTO_MAP_CENTER, (byte) 0, false, false);
			MapId mapId = map.get(DataComponents.MAP_ID);
			if (mapId == null) {
				return null;
			}
			MapItemSavedData mapData = mapLevel.getMapData(mapId);
			if (mapData != null && !mapData.locked) {
				mapLevel.setMapData(mapId, mapData.locked());
				mapData = mapLevel.getMapData(mapId);
			}
			if (mapData == null) {
				return null;
			}
			map.set(DataComponents.CUSTOM_NAME, itemName);
			mapIds[i] = mapId;
			mapDataSet[i] = mapData;
		}
		return new PhotoMapSet(mapIds, mapDataSet);
	}

	private static void givePhotoItem(ServerPlayer player, ItemStack item) {
		if (player == null || item == null || item.isEmpty()) {
			return;
		}
		boolean inserted = player.getInventory().add(item);
		if (!inserted) {
			ItemEntity itemEntity = player.drop(item, false);
			if (itemEntity != null) {
				itemEntity.setPickUpDelay(0);
			}
		}
	}

	private static void schedulePhotoPreviewReprime(ServerPlayer player, MinecraftServer server) {
		if (player == null || server == null) {
			return;
		}
		PENDING_PREVIEW_REPRIME_DEADLINES.put(player.getUUID(), server.getTickCount() + PHOTO_PREVIEW_REPRIME_GRACE_TICKS);
		primePlayerPhotoPreviewMaps(player);
	}

	private static void clearPhotoPreviewReprime(UUID playerId) {
		if (playerId != null) {
			PENDING_PREVIEW_REPRIME_DEADLINES.remove(playerId);
		}
	}

	private static void tickQueuedPhotoPreviewReprimes(MinecraftServer server) {
		if (server == null || PENDING_PREVIEW_REPRIME_DEADLINES.isEmpty()) {
			return;
		}
		boolean shouldPrimeThisTick = (server.getTickCount() % PHOTO_PREVIEW_REPRIME_INTERVAL_TICKS) == 0L;
		Iterator<Map.Entry<UUID, Long>> iterator = PENDING_PREVIEW_REPRIME_DEADLINES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Long> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null || server.getTickCount() > entry.getValue()) {
				iterator.remove();
				continue;
			}
			if (shouldPrimeThisTick) {
				primePlayerPhotoPreviewMaps(player);
			}
		}
	}

	private static void primePlayerPhotoPreviewMaps(ServerPlayer player) {
		if (player == null) {
			return;
		}
		Set<Integer> primedPreviewMapIds = new HashSet<>();
		primePhotoPreviewMap(player, player.getMainHandItem(), primedPreviewMapIds);
		primePhotoPreviewMap(player, player.getOffhandItem(), primedPreviewMapIds);
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			primePhotoPreviewMap(player, player.getInventory().getItem(slot), primedPreviewMapIds);
		}
	}

	private static void primePhotoPreviewMap(ServerPlayer player, ItemStack stack, Set<Integer> primedPreviewMapIds) {
		if (player == null || stack == null || primedPreviewMapIds == null) {
			return;
		}
		PhotoPrintData photoData = PhotoPrintData.readPhotoItem(stack);
		if (photoData == null || photoData.previewMapId() < 0 || !primedPreviewMapIds.add(photoData.previewMapId())) {
			return;
		}
		sendPhotoPreviewMap(player, photoData);
	}

	public static void sendPhotoPreviewMap(ServerPlayer player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty()) {
			return;
		}
		sendPhotoPreviewMap(player, PhotoPrintData.readPhotoItem(stack));
	}

	private static void updatePhotoItemsInInventories(MinecraftServer server, PhotoPrintData photoData, Component name) {
		if (server == null || photoData == null || name == null) {
			return;
		}
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			boolean changed = false;
			for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
				ItemStack stack = player.getInventory().getItem(slot);
				PhotoPrintData stackData = PhotoPrintData.readPhotoItem(stack);
				if (stackData == null || !stackData.samePhoto(photoData)) {
					continue;
				}
				stack.set(DataComponents.CUSTOM_NAME, name.copy());
				changed = true;
			}
			if (changed) {
				ServerMechanicsGateSystem.syncPlayerInventory(player);
				sendMatchingPhotoPreviewMap(player, photoData);
			}
		}
	}

	private static void updatePlacedPhotoFrameNames(MinecraftServer server, PhotoPrintData photoData, Component name) {
		if (server == null || photoData == null || name == null) {
			return;
		}
		for (ServerLevel level : server.getAllLevels()) {
			for (net.minecraft.world.entity.decoration.ItemFrame frame : level.getEntitiesOfClass(
					net.minecraft.world.entity.decoration.ItemFrame.class,
					new net.minecraft.world.phys.AABB(-30_000_000.0D, level.getMinY(), -30_000_000.0D, 30_000_000.0D, level.getMaxY(), 30_000_000.0D)
			)) {
				ItemStack stack = frame.getItem();
				PhotoPrintData.PlacedPhotoFrameData frameData = PhotoPrintData.readFrameTile(stack);
				if (frameData == null || !frameData.samePhoto(photoData)) {
					continue;
				}
				ItemStack updated = stack.copy();
				updated.remove(DataComponents.CUSTOM_NAME);
				PhotoPrintData.writeFrameStoredName(updated, name);
				frame.setItem(updated, false);
			}
		}
	}

	private static void setPhotoColor(RenderJob job, int globalPixelIndex, byte color) {
		if (job == null || globalPixelIndex < 0 || globalPixelIndex >= job.totalPixels()) {
			return;
		}
		int outputWidth = job.outputWidth();
		int globalX = globalPixelIndex % outputWidth;
		int globalY = globalPixelIndex / outputWidth;
		setPhotoColor(job, globalX, globalY, color);
	}

	private static void setPhotoColor(RenderJob job, int globalX, int globalY, byte color) {
		if (job == null || globalX < 0 || globalY < 0 || globalX >= job.outputWidth() || globalY >= job.outputHeight()) {
			return;
		}
		int tileX = globalX / MAP_SIZE;
		int tileY = globalY / MAP_SIZE;
		int tileIndex = tileY * job.mapsWide() + tileX;
		if (tileIndex < 0 || tileIndex >= job.mapDataSet().length) {
			return;
		}
		MapItemSavedData mapData = job.mapDataSet()[tileIndex];
		if (mapData == null) {
			return;
		}
		mapData.setColor(globalX % MAP_SIZE, globalY % MAP_SIZE, color);
	}

	private static PreviewMap preparePreviewMap(ServerPlayer player, Component itemName, PhotoMapSet photoMapSet) {
		if (player == null || photoMapSet == null || photoMapSet.mapIds().length == 0 || photoMapSet.mapDataSet().length == 0) {
			return null;
		}
		PhotoMapSet previewMapSet = createPhotoMapSet(player, itemName, 1, 1);
		if (previewMapSet == null || previewMapSet.mapIds().length == 0 || previewMapSet.mapDataSet().length == 0) {
			return new PreviewMap(photoMapSet.mapIds()[0], photoMapSet.mapDataSet()[0]);
		}
		return new PreviewMap(previewMapSet.mapIds()[0], previewMapSet.mapDataSet()[0]);
	}

	private static void beginRender(ServerPlayer player, RenderJob job) {
		if (player == null || job == null || job.hasStartedRendering()) {
			return;
		}
		job.markStartedRendering();
		job.setLastDisplayedProgress(0);
		player.displayClientMessage(CameraCaptureSystem.createQueuedPhotoName(0), true);
	}

	private static boolean tickPreviewStage(MinecraftServer server, ServerPlayer player, RenderJob job) {
		if (server == null || player == null || job == null || job.previewRendered()) {
			return true;
		}
		if (!job.previewTaskDispatched()) {
			job.markPreviewTaskDispatched();
			MapPixelProvider provider = job.provider();
			executor.submit(() -> {
				try {
					byte[] preview = provider.renderImmediatePreview(server);
					job.pushPreviewResult(FrameResult.success(preview));
				} catch (Throwable throwable) {
					job.pushPreviewResult(FrameResult.failure(throwable));
				}
			});
			return false;
		}
		FrameResult previewResult = job.previewResult();
		if (previewResult == null) {
			return false;
		}
		if (previewResult.failed()) {
			Lg2.LOGGER.error("Map preview render failed for player {}", job.playerId(), previewResult.error());
			player.displayClientMessage(Component.literal("Рендер снимка остановлен: ошибка preview."), true);
			removeJob(job.playerId());
			pollNextActive();
			return false;
		}
		byte[] previewPixels = previewResult.pixels();
		if (!isValidFrame(previewPixels, MAP_SIZE * MAP_SIZE)) {
			Lg2.LOGGER.error("Map preview render returned invalid preview for player {}", job.playerId());
			player.displayClientMessage(Component.literal("Рендер снимка остановлен: preview повреждён."), true);
			removeJob(job.playerId());
			pollNextActive();
			return false;
		}
		applyFrameToMap(job.previewMapData(), previewPixels);
		sendPhotoPreviewMap(player, new MapId(job.previewMapId()), job.previewMapData());
		if (job.provider().immediatePreviewMatchesPrimaryFrame()) {
			job.markFrameTaskDispatched();
			job.pushFrameResult(FrameResult.success(previewPixels.clone()));
		}
		job.markPreviewRendered();
		return true;
	}

	private static void applyFrameToMap(MapItemSavedData mapData, byte[] frame) {
		if (mapData == null || !isValidFrame(frame, MAP_SIZE * MAP_SIZE)) {
			return;
		}
		for (int pixelIndex = 0; pixelIndex < MAP_SIZE * MAP_SIZE; pixelIndex++) {
			mapData.setColor(pixelIndex % MAP_SIZE, pixelIndex / MAP_SIZE, frame[pixelIndex]);
		}
	}

	private static boolean isValidFrame(byte[] frame, int expectedPixels) {
		return frame != null && frame.length >= expectedPixels;
	}

	private static void sendMatchingPhotoPreviewMap(ServerPlayer player, PhotoPrintData photoData) {
		if (player == null || photoData == null) {
			return;
		}
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			PhotoPrintData stackData = PhotoPrintData.readPhotoItem(stack);
			if (stackData == null || !stackData.samePhoto(photoData)) {
				continue;
			}
			sendPhotoPreviewMap(player, stackData);
			return;
		}
	}

	private static void sendPhotoPreviewMap(ServerPlayer player, PhotoPrintData photoData) {
		if (player == null || photoData == null || photoData.previewMapId() < 0 || !(player.level() instanceof ServerLevel fallbackLevel)) {
			return;
		}
		ServerLevel mapLevel = photoMapLevel(player.level().getServer(), fallbackLevel);
		MapId previewMapId = new MapId(photoData.previewMapId());
		MapItemSavedData previewMapData = mapLevel.getMapData(previewMapId);
		if (!hasFullMapColors(previewMapData)) {
			byte[] rebuiltPreview = rebuildPhotoPreviewPixels(photoData);
			if (rebuiltPreview != null) {
				sendPhotoPreviewMap(player, previewMapId, rebuiltPreview);
			}
			return;
		}
		sendPhotoPreviewMap(player, previewMapId, previewMapData);
	}

	private static void sendPhotoPreviewMap(ServerPlayer player, MapId previewMapId, MapItemSavedData previewMapData) {
		if (player == null || previewMapId == null || !hasFullMapColors(previewMapData)) {
			return;
		}
		sendPhotoMap(player, previewMapId, previewMapData);
	}

	private static void sendPhotoPreviewMap(ServerPlayer player, MapId previewMapId, byte[] previewPixels) {
		if (player == null || previewMapId == null || previewPixels == null || previewPixels.length < MAP_SIZE * MAP_SIZE) {
			return;
		}
		player.connection.send(new ClientboundMapItemDataPacket(
				previewMapId,
				(byte) 0,
				true,
				List.of(),
				new MapItemSavedData.MapPatch(0, 0, MAP_SIZE, MAP_SIZE, previewPixels.clone())
		));
	}

	private static boolean hasFullMapColors(MapItemSavedData mapData) {
		return mapData != null && mapData.colors != null && mapData.colors.length >= MAP_SIZE * MAP_SIZE;
	}

	private static byte[] rebuildPhotoPreviewPixels(PhotoPrintData photoData) {
		if (photoData == null || !photoData.isPhoto() || photoData.sourceKey().isBlank()) {
			return null;
		}
		try {
			return quantizePreviewImage(CameraMediaCache.loadPhotoSource(photoData.sourceKey()));
		} catch (Exception exception) {
			Lg2.LOGGER.debug("Failed to rebuild photo preview for {}", photoData.sourceKey(), exception);
			return null;
		}
	}

	private static byte[] quantizePreviewImage(BufferedImage image) {
		if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
			return null;
		}
		int sourceWidth = image.getWidth();
		int sourceHeight = image.getHeight();
		double sourceAspect = sourceWidth / (double) Math.max(1, sourceHeight);
		double targetAspect = 1.0D;
		double cropX = 0.0D;
		double cropY = 0.0D;
		double cropWidth = sourceWidth;
		double cropHeight = sourceHeight;
		if (sourceAspect > targetAspect) {
			cropWidth = sourceHeight * targetAspect;
			cropX = (sourceWidth - cropWidth) * 0.5D;
		} else if (sourceAspect < targetAspect) {
			cropHeight = sourceWidth / targetAspect;
			cropY = (sourceHeight - cropHeight) * 0.5D;
		}

		byte[] output = new byte[MAP_SIZE * MAP_SIZE];
		for (int y = 0; y < MAP_SIZE; y++) {
			double sampleY = cropY + ((y + 0.5D) / MAP_SIZE) * cropHeight;
			int sourceY = Math.clamp((int) Math.floor(sampleY), 0, sourceHeight - 1);
			for (int x = 0; x < MAP_SIZE; x++) {
				double sampleX = cropX + ((x + 0.5D) / MAP_SIZE) * cropWidth;
				int sourceX = Math.clamp((int) Math.floor(sampleX), 0, sourceWidth - 1);
				int rgb24 = image.getRGB(sourceX, sourceY) & 0xFFFFFF;
				output[y * MAP_SIZE + x] = MapPaletteQuantizer.quantizeDithered(rgb24, x, y);
			}
		}
		return output;
	}

	private static void sendPhotoMap(ServerPlayer player, MapId mapId, MapItemSavedData mapData) {
		if (player == null || mapId == null || mapData == null || mapData.colors == null || mapData.colors.length < MAP_SIZE * MAP_SIZE) {
			return;
		}
		ItemStack mapStack = new ItemStack(Items.FILLED_MAP);
		mapStack.set(DataComponents.MAP_ID, mapId);
		mapData.tickCarriedBy(player, mapStack);
		player.connection.send(new ClientboundMapItemDataPacket(
				mapId,
				mapData.scale,
				mapData.locked,
				List.of(),
				new MapItemSavedData.MapPatch(0, 0, MAP_SIZE, MAP_SIZE, mapData.colors.clone())
		));
		Packet<?> packet = mapData.getUpdatePacket(mapId, player);
		if (packet != null) {
			player.connection.send(packet);
		}
	}

	private static void normalizeQueue() {
		if (activePlayerId != null && !PLAYER_JOBS.containsKey(activePlayerId)) {
			activePlayerId = null;
		}
		if (activePlayerId == null) {
			pollNextActive();
		}
	}

	private static void pollNextActive() {
		activePlayerId = null;
		while (!QUEUE.isEmpty()) {
			UUID candidate = QUEUE.poll();
			if (candidate != null && PLAYER_JOBS.containsKey(candidate)) {
				activePlayerId = candidate;
				break;
			}
		}
	}

	private static void removeJob(UUID playerId) {
		if (playerId == null) {
			return;
		}
		PLAYER_JOBS.remove(playerId);
		if (playerId.equals(activePlayerId)) {
			activePlayerId = null;
		}
		Iterator<UUID> iterator = QUEUE.iterator();
		while (iterator.hasNext()) {
			if (playerId.equals(iterator.next())) {
				iterator.remove();
				break;
			}
		}
	}

	private static boolean isPhotoStillReachable(MinecraftServer server, ServerPlayer owner, PhotoPrintData photoData) {
		if (server == null || owner == null || photoData == null) {
			return false;
		}
		// A click-drag removes the stack from its slot before the destination slot
		// is populated. It is still owned by this player while carried by the
		// active menu, so that short transition must not cancel its render.
		if (isMatchingPhoto(owner.containerMenu.getCarried(), photoData)
				|| isMatchingPhoto(owner.inventoryMenu.getCarried(), photoData)) {
			return true;
		}
		for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
			if (isMatchingPhoto(owner.getInventory().getItem(slot), photoData)) {
				return true;
			}
		}
		for (ServerLevel level : server.getAllLevels()) {
			var bounds = new net.minecraft.world.phys.AABB(
					-30_000_000.0D, level.getMinY(), -30_000_000.0D,
					30_000_000.0D, level.getMaxY(), 30_000_000.0D
			);
			for (net.minecraft.world.entity.decoration.ItemFrame frame : level.getEntitiesOfClass(
					net.minecraft.world.entity.decoration.ItemFrame.class, bounds
			)) {
				PhotoPrintData.PlacedPhotoFrameData frameData = PhotoPrintData.readFrameTile(frame.getItem());
				if (frameData != null && frameData.samePhoto(photoData)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isMatchingPhoto(net.minecraft.world.item.ItemStack stack, PhotoPrintData photoData) {
		PhotoPrintData stackData = PhotoPrintData.readPhotoItem(stack);
		return stackData != null && stackData.samePhoto(photoData);
	}

	private static void ensureExecutor() {
		if (executor != null) {
			return;
		}
		int threads = Math.max(
				1,
				Math.min(
						Lg2Config.get().cameraRenderThreads,
						Math.max(1, Math.min(3, Math.max(1, (Runtime.getRuntime().availableProcessors() - 1) / 2)))
				)
		);
		ThreadFactory threadFactory = runnable -> {
			Thread thread = new Thread(runnable, "lg2-map-render");
			thread.setDaemon(true);
			thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
			return thread;
		};
		executor = Executors.newFixedThreadPool(threads, threadFactory);
	}

	private static void shutdownExecutor() {
		if (executor == null) {
			return;
		}
		executor.shutdownNow();
		executor = null;
	}

	private static final class RenderJob {
		private final UUID playerId;
		private final MapId[] mapIds;
		private final MapItemSavedData[] mapDataSet;
		private final int mapsWide;
		private final int mapsHigh;
		private final int previewMapId;
		private final MapItemSavedData previewMapData;
		private final MapPixelProvider provider;
		private final ConcurrentLinkedQueue<PixelResult> completedResults = new ConcurrentLinkedQueue<>();
		private int nextPixel;
		private int failureCount;
		private boolean failureLogged;
		private int dispatchedPixels;
		private Object preparedFrame;
		private boolean framePrepared;
		private boolean frameTaskDispatched;
		private boolean previewTaskDispatched;
		private boolean previewRendered;
		private boolean startedRendering;
		private volatile FrameResult frameResult;
		private volatile FrameResult previewResult;
		private byte[] lastAppliedProgressPreview;
		private int frameApplyIndex;
		private int lastDisplayedProgress = -1;

		private RenderJob(UUID playerId, MapId[] mapIds, MapItemSavedData[] mapDataSet, int mapsWide, int mapsHigh, int previewMapId, MapItemSavedData previewMapData, MapPixelProvider provider) {
			this.playerId = playerId;
			this.mapIds = mapIds;
			this.mapDataSet = mapDataSet;
			this.mapsWide = mapsWide;
			this.mapsHigh = mapsHigh;
			this.previewMapId = previewMapId;
			this.previewMapData = previewMapData;
			this.provider = provider;
		}

		private UUID playerId() {
			return this.playerId;
		}

		private MapId[] mapIds() {
			return this.mapIds;
		}

		private MapItemSavedData[] mapDataSet() {
			return this.mapDataSet;
		}

		private MapItemSavedData previewMapData() {
			return this.previewMapData;
		}

		private int previewMapId() {
			return this.previewMapId;
		}

		private int mapsWide() {
			return this.mapsWide;
		}

		private int mapsHigh() {
			return this.mapsHigh;
		}

		private int outputWidth() {
			return this.mapsWide * MAP_SIZE;
		}

		private int outputHeight() {
			return this.mapsHigh * MAP_SIZE;
		}

		private int totalPixels() {
			return this.outputWidth() * this.outputHeight();
		}

		private PhotoPrintData photoData() {
			return new PhotoPrintData(this.mapsWide, this.mapsHigh, this.previewMapId, photoMapIdsToRawIds(this.mapIds));
		}

		private MapPixelProvider provider() {
			return this.provider;
		}

		private int nextPixel() {
			return this.nextPixel;
		}

		private void advance() {
			this.nextPixel++;
		}

		private void dispatchPixel() {
			this.dispatchedPixels++;
		}

		private void finishDispatchedPixel() {
			if (this.dispatchedPixels > 0) {
				this.dispatchedPixels--;
			}
		}

		private void recordFailure() {
			this.failureCount++;
		}

		private int failureCount() {
			return this.failureCount;
		}

		private boolean hasLoggedFailure() {
			return this.failureLogged;
		}

		private void markFailureLogged() {
			this.failureLogged = true;
		}

		private boolean canDispatchMore() {
			return this.dispatchedPixels < Lg2Config.get().cameraRenderInFlightPixels;
		}

		private boolean hasDispatchedPixels() {
			return this.dispatchedPixels > 0;
		}

		private boolean hasPreparedFrame() {
			return this.framePrepared;
		}

		private void setPreparedFrame(Object preparedFrame) {
			this.preparedFrame = preparedFrame;
			this.framePrepared = true;
		}

		private Object preparedFrame() {
			return this.preparedFrame;
		}

		private boolean hasDispatchedFrameTask() {
			return this.frameTaskDispatched;
		}

		private void markFrameTaskDispatched() {
			this.frameTaskDispatched = true;
		}

		private boolean previewTaskDispatched() {
			return this.previewTaskDispatched;
		}

		private void markPreviewTaskDispatched() {
			this.previewTaskDispatched = true;
		}

		private boolean previewRendered() {
			return this.previewRendered;
		}

		private void markPreviewRendered() {
			this.previewRendered = true;
		}

		private boolean hasStartedRendering() {
			return this.startedRendering;
		}

		private void markStartedRendering() {
			this.startedRendering = true;
		}

		private void pushFrameResult(FrameResult frameResult) {
			this.frameResult = frameResult;
		}

		private FrameResult frameResult() {
			return this.frameResult;
		}

		private void pushPreviewResult(FrameResult previewResult) {
			this.previewResult = previewResult;
		}

		private FrameResult previewResult() {
			return this.previewResult;
		}

		private boolean acceptPreview(byte[] preview) {
			if (preview == null || preview.length < MAP_SIZE * MAP_SIZE) return false;
			if (java.util.Arrays.equals(this.lastAppliedProgressPreview, preview)) return false;
			this.lastAppliedProgressPreview = preview.clone();
			return true;
		}

		private int frameApplyIndex() {
			return this.frameApplyIndex;
		}

		private void advanceFrameApplyIndex() {
			this.frameApplyIndex++;
		}

		private void markFrameFullyApplied() {
			this.frameApplyIndex = this.totalPixels();
		}

		private void pushResult(PixelResult result) {
			this.completedResults.offer(result);
		}

		private PixelResult pollResult() {
			return this.completedResults.poll();
		}

		private boolean isDone() {
			return this.nextPixel >= totalPixels();
		}

		private int progressPercent() {
			if (this.provider.prefersWholeFrameRendering()) {
				if (this.totalPixels() <= 0) {
					return 100;
				}
				return Math.max(0, Math.min(99, this.frameApplyIndex * 100 / this.totalPixels()));
			}
			if (this.totalPixels() <= 0) {
				return 100;
			}
			return Math.max(0, Math.min(99, this.nextPixel * 100 / this.totalPixels()));
		}

		private int lastDisplayedProgress() {
			return this.lastDisplayedProgress;
		}

		private void setLastDisplayedProgress(int lastDisplayedProgress) {
			this.lastDisplayedProgress = lastDisplayedProgress;
		}
	}

	private static int[] photoMapIdsToRawIds(MapId[] mapIds) {
		if (mapIds == null) {
			return new int[0];
		}
		int[] rawIds = new int[mapIds.length];
		for (int i = 0; i < mapIds.length; i++) {
			rawIds[i] = mapIds[i] == null ? -1 : mapIds[i].id();
		}
		return rawIds;
	}

	private record PhotoMapSet(MapId[] mapIds, MapItemSavedData[] mapDataSet) {
	}

	private record PreviewMap(MapId previewMapId, MapItemSavedData previewMapData) {
	}

	private record PixelResult(int x, int y, byte color, Throwable error) {
		private static PixelResult success(int x, int y, byte color) {
			return new PixelResult(x, y, color, null);
		}

		private static PixelResult failure(int x, int y, Throwable error) {
			return new PixelResult(x, y, (byte) 0, error);
		}

		private boolean failed() {
			return this.error != null;
		}
	}

	private record FrameResult(byte[] pixels, Throwable error) {
		private static FrameResult success(byte[] pixels) {
			return new FrameResult(pixels, null);
		}

		private static FrameResult failure(Throwable error) {
			return new FrameResult(null, error);
		}

		private boolean failed() {
			return this.error != null;
		}
	}
}
