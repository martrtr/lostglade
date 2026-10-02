package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.item.ModItems;
import com.lostglade.item.PhotoPrintData;
import com.lostglade.server.map.MapPaletteQuantizer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CameraAnimatedMapPlaybackSystem {
	private static final int MAP_SIZE = 128;
	private static final int PHOTO_MAP_CENTER = 30_000_000;
	private static final int DISCOVERY_INTERVAL_TICKS = 100;
	private static final long SESSION_IDLE_TICKS = 200L;
	private static final long SESSION_STARTUP_TIMEOUT_MS = 5_000L;
	private static final long VIEWER_REPRIME_GRACE_TICKS = 40L;
	private static final long VIEWER_REPRIME_INTERVAL_TICKS = 5L;
	private static final int MIN_DECODE_AHEAD_FRAMES = 24;
	private static final int MAX_DECODE_AHEAD_FRAMES = 120;
	private static final double FRAME_VIEW_RADIUS_SQR = 96.0D * 96.0D;
	private static final String DEFAULT_FFMPEG_BIN = "ffmpeg";
	private static final String PLACED_VIDEOS_FILE = "lg2-camera-placed-videos.json";
	private static final Gson PERSISTENCE_GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final Map<PlaybackKey, VideoPlaybackSession> PLAYBACKS = new ConcurrentHashMap<>();
	private static final Map<UUID, PlacedVideoGroup> PLACED_GROUPS = new ConcurrentHashMap<>();
	private static final Map<UUID, PlacedVideoGroup> PERSISTED_PLACED_GROUPS = new ConcurrentHashMap<>();
	private static final Map<PlayerMapKey, Long> LAST_SENT_SEQUENCE_BY_PLAYER_MAP = new ConcurrentHashMap<>();
	private static final Map<PlacedViewerKey, Boolean> PRIMED_PLACED_GROUPS_BY_VIEWER = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> RECENT_VIEWER_REPRIME_DEADLINES = new ConcurrentHashMap<>();
	private static volatile long lastDiscoveryTick = Long.MIN_VALUE;
	private static volatile MinecraftServer persistenceServer;

	private CameraAnimatedMapPlaybackSystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(CameraAnimatedMapPlaybackSystem::tick);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			persistenceServer = server;
			loadPersistedPlacedVideos(server);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				AccountAuthSystem.runWhenAuthenticated(handler.player,
						() -> server.execute(() -> markViewerJoined((ServerPlayer) handler.player, server))));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> clearViewerRuntimeState(handler.player.getUUID())));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			savePersistedPlacedVideos(server);
			persistenceServer = null;
			shutdownAll();
		});
	}

	public static ItemStack createVideoItem(
			ServerPlayer player,
			Component itemName,
			int mapsWide,
			int mapsHigh,
			String sourceKey,
			long durationMs,
			int fps,
			byte[] previewPixels,
			byte[] fullPixels
	) {
		if (player == null || player.level() == null || sourceKey == null || sourceKey.isBlank()) {
			return ItemStack.EMPTY;
		}
		ServerLevel level = (ServerLevel) player.level();
		ServerLevel mapLevel = photoMapLevel(player.level().getServer(), level);
		PhotoMapSet fullMaps = createPhotoMapSet(mapLevel, itemName, mapsWide, mapsHigh);
		PhotoMapSet previewMaps = createPhotoMapSet(mapLevel, itemName, 1, 1);
		if (fullMaps == null || previewMaps == null || previewMaps.mapIds().length == 0) {
			return ItemStack.EMPTY;
		}

		if (previewPixels != null && previewPixels.length >= MAP_SIZE * MAP_SIZE) {
			applyFrameToMap(previewMaps.mapDataSet()[0], previewPixels);
		}
		if (fullPixels != null && fullPixels.length >= mapsWide * mapsHigh * MAP_SIZE * MAP_SIZE) {
			applyWholeFrame(fullMaps.mapDataSet(), mapsWide, mapsHigh, fullPixels);
		}

		ItemStack item = PhotoPrintData.createPhotoItem(
				itemName,
				mapsWide,
				mapsHigh,
				previewMaps.mapIds()[0],
				fullMaps.mapIds(),
				PhotoPrintData.MediaKind.VIDEO,
				sourceKey,
				durationMs,
				fps
		);
		sendPreviewToPlayer(player, previewMaps.mapIds()[0], previewMaps.mapDataSet()[0]);
		return item;
	}

	public static void registerPlacedVideo(ServerLevel level, PhotoPrintData.PlacedPhotoFrameData frameData) {
		if (level == null || frameData == null) {
			return;
		}
		PhotoPrintData mediaData = frameData.asPhotoPrintData();
		if (!mediaData.isVideo()) {
			return;
		}
		PLACED_GROUPS.put(
				frameData.groupId(),
				new PlacedVideoGroup(frameData.groupId(), level.dimension(), frameData.anchorPos(), frameData.direction(), mediaData)
		);
		PERSISTED_PLACED_GROUPS.put(frameData.groupId(), new PlacedVideoGroup(frameData.groupId(), level.dimension(), frameData.anchorPos(), frameData.direction(), mediaData));
		savePersistedPlacedVideos(persistenceServer);
	}

	public static void unregisterPlacedVideo(UUID groupId) {
		if (groupId != null) {
			PLACED_GROUPS.remove(groupId);
			PERSISTED_PLACED_GROUPS.remove(groupId);
			PRIMED_PLACED_GROUPS_BY_VIEWER.keySet().removeIf(key -> groupId.equals(key.groupId()));
			savePersistedPlacedVideos(persistenceServer);
		}
	}

	private static void tick(MinecraftServer server) {
		if (server == null) {
			return;
		}
		long gameTime = server.overworld() != null ? server.overworld().getGameTime() : 0L;
		if (gameTime - lastDiscoveryTick >= DISCOVERY_INTERVAL_TICKS) {
			rebuildPlacedVideoGroups(server);
			lastDiscoveryTick = gameTime;
		}

		Map<PlaybackKey, Boolean> usedSessions = new HashMap<>();
		tickHeldVideoPreviews(server, usedSessions, gameTime);
		tickPlacedVideoFrames(server, usedSessions, gameTime);
		reapUnusedSessions(usedSessions, gameTime);
		reapSequenceCache(server);
	}

	private static void tickHeldVideoPreviews(MinecraftServer server, Map<PlaybackKey, Boolean> usedSessions, long gameTime) {
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			tickHeldVideoPreview(player, player.getMainHandItem(), usedSessions, gameTime);
			tickHeldVideoPreview(player, player.getOffhandItem(), usedSessions, gameTime);
		}
	}

	private static void tickHeldVideoPreview(ServerPlayer player, ItemStack stack, Map<PlaybackKey, Boolean> usedSessions, long gameTime) {
		PhotoPrintData data = PhotoPrintData.readPhotoItem(stack);
		if (player == null || data == null || !data.isVideo() || data.previewMapId() < 0 || data.sourceKey().isBlank()) {
			return;
		}
		PlaybackKey key = new PlaybackKey(data.sourceKey(), data.mapsWide(), data.mapsHigh(), Math.max(1, data.fps()));
		VideoPlaybackSession session = ensureSession(key);
		if (session == null) {
			return;
		}
		session.markUsed(gameTime);
		usedSessions.put(key, Boolean.TRUE);
		DecodedVideoFrame frame = session.latestFrame();
		if (frame == null || frame.previewFrame() == null || frame.previewFrame().length < MAP_SIZE * MAP_SIZE) {
			return;
		}
		PlayerMapKey playerMapKey = new PlayerMapKey(player.getUUID(), data.previewMapId());
		Long lastSequence = LAST_SENT_SEQUENCE_BY_PLAYER_MAP.get(playerMapKey);
		if (lastSequence != null && lastSequence == frame.sequence()) {
			return;
		}
		sendMapPatch(player, new MapId(data.previewMapId()), (byte) 0, true, 0, 0, MAP_SIZE, MAP_SIZE, frame.previewFrame());
		LAST_SENT_SEQUENCE_BY_PLAYER_MAP.put(playerMapKey, frame.sequence());
	}

	private static void tickPlacedVideoFrames(MinecraftServer server, Map<PlaybackKey, Boolean> usedSessions, long gameTime) {
		for (PlacedVideoGroup group : PLACED_GROUPS.values()) {
			if (group == null || !group.data().isVideo() || group.data().sourceKey().isBlank()) {
				continue;
			}
			ServerLevel level = server.getLevel(group.dimension());
			if (level == null) {
				continue;
			}
			ServerLevel mapLevel = photoMapLevel(server, level);
			PlaybackKey key = new PlaybackKey(group.data().sourceKey(), group.data().mapsWide(), group.data().mapsHigh(), Math.max(1, group.data().fps()));
			VideoPlaybackSession session = ensureSession(key);
			if (session == null) {
				continue;
			}
			List<ServerPlayer> recipients = collectNearbyPlayers(level, group.anchorPos());
			if (recipients.isEmpty()) {
				continue;
			}
			session.markUsed(gameTime);
			usedSessions.put(key, Boolean.TRUE);
			DecodedVideoFrame frame = session.latestFrame();
			if (frame == null || frame.tiles() == null || frame.tiles().length != group.data().mapIds().length) {
				continue;
			}
			for (ServerPlayer recipient : recipients) {
				PlacedViewerKey placedViewerKey = new PlacedViewerKey(recipient.getUUID(), group.groupId());
				boolean forceReprime = shouldForceViewerReprime(recipient.getUUID(), gameTime);
				boolean joinWindowPrimeTick = forceReprime && (gameTime % VIEWER_REPRIME_INTERVAL_TICKS) == 0L;
				if (!PRIMED_PLACED_GROUPS_BY_VIEWER.containsKey(placedViewerKey) || joinWindowPrimeTick) {
					primePlacedVideoGroupForViewer(level, mapLevel, group, recipient);
					if (forceReprime) {
						clearViewerSequenceState(recipient.getUUID(), group.data());
					}
					PRIMED_PLACED_GROUPS_BY_VIEWER.put(placedViewerKey, Boolean.TRUE);
				}
				for (int tileIndex = 0; tileIndex < group.data().mapIds().length; tileIndex++) {
					int rawMapId = group.data().mapIds()[tileIndex];
					if (rawMapId < 0) {
						continue;
					}
					PlayerMapKey playerMapKey = new PlayerMapKey(recipient.getUUID(), rawMapId);
					Long lastSequence = LAST_SENT_SEQUENCE_BY_PLAYER_MAP.get(playerMapKey);
					byte[] fullTile = frame.tiles()[tileIndex];
					TilePatch patch = frame.tilePatches()[tileIndex];
					if (lastSequence == null) {
						primeAnimatedMapForViewer(recipient, new MapId(rawMapId), mapLevel);
						sendMapPatch(recipient, new MapId(rawMapId), (byte) 0, true, 0, 0, MAP_SIZE, MAP_SIZE, fullTile);
					} else if (lastSequence != frame.sequence() - 1L || patch == null) {
						sendMapPatch(recipient, new MapId(rawMapId), (byte) 0, true, 0, 0, MAP_SIZE, MAP_SIZE, fullTile);
					} else {
						sendMapPatch(recipient, new MapId(rawMapId), (byte) 0, true, patch.startX(), patch.startY(), patch.width(), patch.height(), patch.frame());
					}
					LAST_SENT_SEQUENCE_BY_PLAYER_MAP.put(playerMapKey, frame.sequence());
				}
			}
		}
	}

	private static List<ServerPlayer> collectNearbyPlayers(ServerLevel level, BlockPos anchorPos) {
		if (level == null || anchorPos == null) {
			return List.of();
		}
		List<ServerPlayer> players = new ArrayList<>();
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(anchorPos.getX() + 0.5D, anchorPos.getY() + 0.5D, anchorPos.getZ() + 0.5D) <= FRAME_VIEW_RADIUS_SQR) {
				players.add(player);
			}
		}
		return players;
	}

	private static void rebuildPlacedVideoGroups(MinecraftServer server) {
		Map<UUID, PlacedVideoGroup> rebuilt = new HashMap<>();
		for (ServerLevel level : server.getAllLevels()) {
			AABB searchBox = new AABB(-30_000_000.0D, level.getMinY(), -30_000_000.0D, 30_000_000.0D, level.getMaxY(), 30_000_000.0D);
			for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, searchBox)) {
				PhotoPrintData.PlacedPhotoFrameData frameData = PhotoPrintData.readFrameTile(frame.getItem());
				if (frameData == null) {
					continue;
				}
				PhotoPrintData mediaData = frameData.asPhotoPrintData();
				if (!mediaData.isVideo()) {
					continue;
				}
				rebuilt.putIfAbsent(
						frameData.groupId(),
						new PlacedVideoGroup(frameData.groupId(), level.dimension(), frameData.anchorPos(), frameData.direction(), mediaData)
				);
			}
		}
		for (PlacedVideoGroup persisted : PERSISTED_PLACED_GROUPS.values()) {
			if (persisted == null || rebuilt.containsKey(persisted.groupId())) {
				continue;
			}
			ServerLevel level = server.getLevel(persisted.dimension());
			if (level == null) {
				continue;
			}
			if (isPlacedVideoGroupStillPresent(level, persisted)) {
				rebuilt.put(persisted.groupId(), persisted);
			}
		}
		PLACED_GROUPS.clear();
		PLACED_GROUPS.putAll(rebuilt);
		if (!sameGroupSet(PERSISTED_PLACED_GROUPS, rebuilt)) {
			PERSISTED_PLACED_GROUPS.clear();
			PERSISTED_PLACED_GROUPS.putAll(rebuilt);
			savePersistedPlacedVideos(server);
		}
		PRIMED_PLACED_GROUPS_BY_VIEWER.keySet().removeIf(key -> !PLACED_GROUPS.containsKey(key.groupId()));
	}

	private static void reapUnusedSessions(Map<PlaybackKey, Boolean> usedSessions, long gameTime) {
		Iterator<Map.Entry<PlaybackKey, VideoPlaybackSession>> iterator = PLAYBACKS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<PlaybackKey, VideoPlaybackSession> entry = iterator.next();
			VideoPlaybackSession session = entry.getValue();
			if (session == null) {
				iterator.remove();
				continue;
			}
			if (usedSessions.containsKey(entry.getKey())) {
				continue;
			}
			if (gameTime - session.lastUsedTick() < SESSION_IDLE_TICKS) {
				continue;
			}
			session.close();
			iterator.remove();
		}
	}

	private static void reapSequenceCache(MinecraftServer server) {
		if (server == null) {
			return;
		}
		Iterator<PlayerMapKey> iterator = LAST_SENT_SEQUENCE_BY_PLAYER_MAP.keySet().iterator();
		while (iterator.hasNext()) {
			PlayerMapKey key = iterator.next();
			if (server.getPlayerList().getPlayer(key.playerId()) == null) {
				iterator.remove();
			}
		}
		PRIMED_PLACED_GROUPS_BY_VIEWER.keySet().removeIf(key -> server.getPlayerList().getPlayer(key.playerId()) == null);
		RECENT_VIEWER_REPRIME_DEADLINES.entrySet().removeIf(entry -> server.getPlayerList().getPlayer(entry.getKey()) == null || server.getTickCount() > entry.getValue());
	}

	private static VideoPlaybackSession ensureSession(PlaybackKey key) {
		if (key == null || key.sourceKey().isBlank()) {
			return null;
		}
		VideoPlaybackSession existing = PLAYBACKS.get(key);
		if (existing != null) {
			if (existing.isUsable()) {
				return existing;
			}
			if (PLAYBACKS.remove(key, existing)) {
				existing.close();
			}
		}
		try {
			VideoPlaybackSession created = new VideoPlaybackSession(key);
			VideoPlaybackSession raced = PLAYBACKS.putIfAbsent(key, created);
			if (raced != null) {
				created.close();
				return raced;
			}
			return created;
		} catch (Exception exception) {
			Lg2.LOGGER.warn("Failed to start camera video playback session for {}", key.sourceKey(), exception);
			return null;
		}
	}

	private static void shutdownAll() {
		for (VideoPlaybackSession session : PLAYBACKS.values()) {
			session.close();
		}
		PLAYBACKS.clear();
		PLACED_GROUPS.clear();
		LAST_SENT_SEQUENCE_BY_PLAYER_MAP.clear();
		PRIMED_PLACED_GROUPS_BY_VIEWER.clear();
		RECENT_VIEWER_REPRIME_DEADLINES.clear();
	}

	private static PhotoMapSet createPhotoMapSet(ServerLevel mapLevel, Component itemName, int mapsWide, int mapsHigh) {
		if (mapLevel == null) {
			return null;
		}
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

	private static void applyWholeFrame(MapItemSavedData[] mapDataSet, int mapsWide, int mapsHigh, byte[] fullPixels) {
		int outputWidth = mapsWide * MAP_SIZE;
		for (int tileY = 0; tileY < mapsHigh; tileY++) {
			for (int tileX = 0; tileX < mapsWide; tileX++) {
				int tileIndex = tileY * mapsWide + tileX;
				if (tileIndex < 0 || tileIndex >= mapDataSet.length) {
					continue;
				}
				MapItemSavedData mapData = mapDataSet[tileIndex];
				if (mapData == null || mapData.colors == null || mapData.colors.length < MAP_SIZE * MAP_SIZE) {
					continue;
				}
				for (int row = 0; row < MAP_SIZE; row++) {
					int sourceOffset = (tileY * MAP_SIZE + row) * outputWidth + tileX * MAP_SIZE;
					System.arraycopy(fullPixels, sourceOffset, mapData.colors, row * MAP_SIZE, MAP_SIZE);
				}
				mapData.setDirty();
			}
		}
	}

	private static void applyFrameToMap(MapItemSavedData mapData, byte[] frame) {
		if (mapData == null || frame == null || frame.length < MAP_SIZE * MAP_SIZE || mapData.colors == null || mapData.colors.length < MAP_SIZE * MAP_SIZE) {
			return;
		}
		System.arraycopy(frame, 0, mapData.colors, 0, MAP_SIZE * MAP_SIZE);
		mapData.setDirty();
	}

	private static void sendPreviewToPlayer(ServerPlayer player, MapId mapId, MapItemSavedData mapData) {
		if (player == null || mapId == null || mapData == null || mapData.colors == null || mapData.colors.length < MAP_SIZE * MAP_SIZE) {
			return;
		}
		ItemStack mapStack = new ItemStack(Items.FILLED_MAP);
		mapStack.set(DataComponents.MAP_ID, mapId);
		mapData.tickCarriedBy(player, mapStack);
		sendMapPatch(player, mapId, mapData.scale, mapData.locked, 0, 0, MAP_SIZE, MAP_SIZE, mapData.colors.clone());
	}

	private static void primeAnimatedMapForViewer(ServerPlayer player, MapId mapId, ServerLevel mapLevel) {
		if (player == null || mapId == null || mapLevel == null) {
			return;
		}
		MapItemSavedData mapData = mapLevel.getMapData(mapId);
		if (mapData == null) {
			return;
		}
		ItemStack mapStack = new ItemStack(Items.FILLED_MAP);
		mapStack.set(DataComponents.MAP_ID, mapId);
		mapData.tickCarriedBy(player, mapStack);
		var packet = mapData.getUpdatePacket(mapId, player);
		if (packet != null) {
			player.connection.send(packet);
		}
	}

	private static void primePlacedVideoGroupForViewer(ServerLevel level, ServerLevel mapLevel, PlacedVideoGroup group, ServerPlayer recipient) {
		if (level == null || mapLevel == null || group == null || recipient == null) {
			return;
		}
		for (int rawMapId : group.data().mapIds()) {
			if (rawMapId >= 0) {
				primeAnimatedMapForViewer(recipient, new MapId(rawMapId), mapLevel);
			}
		}
		refreshPlacedFrameItems(level, group, recipient);
	}

	private static void refreshPlacedFrameItems(ServerLevel level, PlacedVideoGroup group, ServerPlayer recipient) {
		if (level == null || group == null) {
			return;
		}
		Direction right = frameRight(group.direction());
		for (int tileY = 0; tileY < group.data().mapsHigh(); tileY++) {
			for (int tileX = 0; tileX < group.data().mapsWide(); tileX++) {
				BlockPos expectedPos = group.anchorPos().relative(right, tileX).relative(Direction.DOWN, tileY);
				ItemFrame frame = findPlacedFrame(level, expectedPos, group, tileX, tileY);
				if (frame == null) {
					continue;
				}
				ItemStack current = frame.getItem();
				if (!current.isEmpty()) {
					ItemStack updated = current.copy();
					if (PhotoPrintData.readFrameTile(updated) == null) {
						PhotoPrintData.writeFrameTile(updated, group.data().placed(group.groupId(), group.anchorPos(), group.direction(), tileX, tileY));
					}
					frame.setItem(updated, false);
					sendFrameEntityData(recipient, frame);
				}
			}
		}
	}

	private static void sendFrameEntityData(ServerPlayer recipient, ItemFrame frame) {
		if (recipient == null || frame == null) {
			return;
		}
		List<SynchedEntityData.DataValue<?>> values = frame.getEntityData().getNonDefaultValues();
		if (values == null || values.isEmpty()) {
			return;
		}
		recipient.connection.send(new ClientboundSetEntityDataPacket(frame.getId(), values));
	}

	private static ItemFrame findPlacedFrame(ServerLevel level, BlockPos expectedPos, PlacedVideoGroup group, int tileX, int tileY) {
		AABB searchBox = new AABB(expectedPos).inflate(0.6D);
		int expectedMapId = group.data().mapIdAt(tileX, tileY);
		for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, searchBox, candidate -> candidate.blockPosition().equals(expectedPos))) {
			if (frame.getDirection() != group.direction()) {
				continue;
			}
			PhotoPrintData.PlacedPhotoFrameData candidateData = PhotoPrintData.readFrameTile(frame.getItem());
			if (candidateData != null
					&& candidateData.groupId().equals(group.groupId())
					&& candidateData.anchorPos().equals(group.anchorPos())
					&& candidateData.direction() == group.direction()
					&& candidateData.mapsWide() == group.data().mapsWide()
					&& candidateData.mapsHigh() == group.data().mapsHigh()) {
				return frame;
			}
			MapId mapId = frame.getItem().get(DataComponents.MAP_ID);
			if (candidateData == null && mapId != null && mapId.id() == expectedMapId) {
				return frame;
			}
		}
		return null;
	}

	private static Direction frameRight(Direction facing) {
		return switch (facing) {
			case NORTH -> Direction.WEST;
			case SOUTH -> Direction.EAST;
			case EAST -> Direction.NORTH;
			case WEST -> Direction.SOUTH;
			default -> Direction.EAST;
		};
	}

	private static boolean isPlacedVideoGroupStillPresent(ServerLevel level, PlacedVideoGroup group) {
		if (level == null || group == null) {
			return false;
		}
		Direction right = frameRight(group.direction());
		for (int tileY = 0; tileY < group.data().mapsHigh(); tileY++) {
			for (int tileX = 0; tileX < group.data().mapsWide(); tileX++) {
				BlockPos expectedPos = group.anchorPos().relative(right, tileX).relative(Direction.DOWN, tileY);
				if (!level.isLoaded(expectedPos)) {
					continue;
				}
				if (findPlacedFrame(level, expectedPos, group, tileX, tileY) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean sameGroupSet(Map<UUID, PlacedVideoGroup> existing, Map<UUID, PlacedVideoGroup> rebuilt) {
		if (existing.size() != rebuilt.size()) {
			return false;
		}
		for (Map.Entry<UUID, PlacedVideoGroup> entry : rebuilt.entrySet()) {
			PlacedVideoGroup current = existing.get(entry.getKey());
			if (!Objects.equals(current, entry.getValue())) {
				return false;
			}
		}
		return true;
	}

	private static void markViewerJoined(ServerPlayer player, MinecraftServer server) {
		if (player == null || server == null) {
			return;
		}
		clearViewerRuntimeState(player.getUUID());
		RECENT_VIEWER_REPRIME_DEADLINES.put(player.getUUID(), server.getTickCount() + VIEWER_REPRIME_GRACE_TICKS);
	}

	private static void clearViewerRuntimeState(UUID playerId) {
		if (playerId == null) {
			return;
		}
		LAST_SENT_SEQUENCE_BY_PLAYER_MAP.keySet().removeIf(key -> playerId.equals(key.playerId()));
		PRIMED_PLACED_GROUPS_BY_VIEWER.keySet().removeIf(key -> playerId.equals(key.playerId()));
		RECENT_VIEWER_REPRIME_DEADLINES.remove(playerId);
	}

	private static boolean shouldForceViewerReprime(UUID playerId, long gameTime) {
		if (playerId == null) {
			return false;
		}
		Long deadline = RECENT_VIEWER_REPRIME_DEADLINES.get(playerId);
		if (deadline == null) {
			return false;
		}
		if (gameTime > deadline) {
			RECENT_VIEWER_REPRIME_DEADLINES.remove(playerId, deadline);
			return false;
		}
		return true;
	}

	private static void clearViewerSequenceState(UUID playerId, PhotoPrintData data) {
		if (playerId == null || data == null) {
			return;
		}
		for (int rawMapId : data.mapIds()) {
			if (rawMapId >= 0) {
				LAST_SENT_SEQUENCE_BY_PLAYER_MAP.remove(new PlayerMapKey(playerId, rawMapId));
			}
		}
	}

	private static void loadPersistedPlacedVideos(MinecraftServer server) {
		PERSISTED_PLACED_GROUPS.clear();
		if (server == null) {
			return;
		}
		Path path = placedVideosPath(server);
		if (!Files.isRegularFile(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			PersistedPlacedVideosFile file = PERSISTENCE_GSON.fromJson(reader, PersistedPlacedVideosFile.class);
			if (file == null || file.groups == null) {
				return;
			}
			for (PersistedPlacedVideoGroup persisted : file.groups) {
				PlacedVideoGroup group = persisted == null ? null : persisted.toRuntime();
				if (group != null) {
					PERSISTED_PLACED_GROUPS.put(group.groupId(), group);
				}
			}
			PLACED_GROUPS.clear();
			PLACED_GROUPS.putAll(PERSISTED_PLACED_GROUPS);
		} catch (Exception exception) {
			Lg2.LOGGER.warn("Failed to load persisted camera placed videos from {}", path, exception);
		}
	}

	private static void savePersistedPlacedVideos(MinecraftServer server) {
		if (server == null) {
			return;
		}
		Path path = placedVideosPath(server);
		try {
			Files.createDirectories(path.getParent());
			PersistedPlacedVideosFile file = new PersistedPlacedVideosFile();
			for (PlacedVideoGroup group : PERSISTED_PLACED_GROUPS.values()) {
				if (group != null) {
					file.groups.add(PersistedPlacedVideoGroup.fromRuntime(group));
				}
			}
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				PERSISTENCE_GSON.toJson(file, writer);
			}
		} catch (Exception exception) {
			Lg2.LOGGER.warn("Failed to save persisted camera placed videos to {}", path, exception);
		}
	}

	private static Path placedVideosPath(MinecraftServer server) {
		return server.getServerDirectory().resolve("world").resolve("data").resolve(PLACED_VIDEOS_FILE);
	}

	private static void sendMapPatch(ServerPlayer player, MapId mapId, byte scale, boolean locked, int startX, int startY, int width, int height, byte[] frame) {
		if (player == null || mapId == null || frame == null || width <= 0 || height <= 0) {
			return;
		}
		player.connection.send(new ClientboundMapItemDataPacket(
				mapId,
				scale,
				locked,
				List.of(),
				new MapItemSavedData.MapPatch(startX, startY, width, height, frame)
		));
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

	private static String ffmpegBinary() {
		String property = System.getProperty("lg2.ffmpegBin");
		if (property != null && !property.isBlank()) {
			return property.trim();
		}
		return DEFAULT_FFMPEG_BIN;
	}

	private static byte[] buildPreviewFrame(byte[] rgb, int sourceWidth, int sourceHeight) {
		return quantizeScaledRgb(rgb, sourceWidth, sourceHeight, MAP_SIZE, MAP_SIZE);
	}

	private static byte[][] buildTileFrames(byte[] rgb, int sourceWidth, int sourceHeight, int mapsWide, int mapsHigh) {
		int tileCount = mapsWide * mapsHigh;
		byte[][] tiles = new byte[tileCount][MAP_SIZE * MAP_SIZE];
		for (int tileY = 0; tileY < mapsHigh; tileY++) {
			for (int tileX = 0; tileX < mapsWide; tileX++) {
				byte[] tile = tiles[tileY * mapsWide + tileX];
				for (int y = 0; y < MAP_SIZE; y++) {
					int sourceY = tileY * MAP_SIZE + y;
					int rowBase = sourceY * sourceWidth * 3;
					for (int x = 0; x < MAP_SIZE; x++) {
						int sourceX = tileX * MAP_SIZE + x;
						int sourceIndex = rowBase + sourceX * 3;
						int red = rgb[sourceIndex] & 0xFF;
						int green = rgb[sourceIndex + 1] & 0xFF;
						int blue = rgb[sourceIndex + 2] & 0xFF;
						int rgb24 = (red << 16) | (green << 8) | blue;
						tile[y * MAP_SIZE + x] = MapPaletteQuantizer.quantizeDithered(rgb24, x, y);
					}
				}
			}
		}
		return tiles;
	}

	private static byte[] quantizeScaledRgb(byte[] sourceRgb, int sourceWidth, int sourceHeight, int outputWidth, int outputHeight) {
		double sourceAspect = sourceWidth / (double) Math.max(1, sourceHeight);
		double targetAspect = outputWidth / (double) Math.max(1, outputHeight);
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

		byte[] output = new byte[outputWidth * outputHeight];
		for (int y = 0; y < outputHeight; y++) {
			double sampleY = cropY + ((y + 0.5D) / outputHeight) * cropHeight;
			int sourceY = Math.clamp((int) Math.floor(sampleY), 0, sourceHeight - 1);
			for (int x = 0; x < outputWidth; x++) {
				double sampleX = cropX + ((x + 0.5D) / outputWidth) * cropWidth;
				int sourceX = Math.clamp((int) Math.floor(sampleX), 0, sourceWidth - 1);
				int sourceIndex = (sourceY * sourceWidth + sourceX) * 3;
				int red = sourceRgb[sourceIndex] & 0xFF;
				int green = sourceRgb[sourceIndex + 1] & 0xFF;
				int blue = sourceRgb[sourceIndex + 2] & 0xFF;
				int rgb24 = (red << 16) | (green << 8) | blue;
				output[y * outputWidth + x] = MapPaletteQuantizer.quantizeDithered(rgb24, x, y);
			}
		}
		return output;
	}

	private static TilePatch[] buildTilePatches(byte[][] previousTiles, byte[][] currentTiles) {
		TilePatch[] patches = new TilePatch[currentTiles.length];
		for (int tileIndex = 0; tileIndex < currentTiles.length; tileIndex++) {
			byte[] previous = previousTiles == null || tileIndex >= previousTiles.length ? null : previousTiles[tileIndex];
			byte[] current = currentTiles[tileIndex];
			if (current == null || current.length < MAP_SIZE * MAP_SIZE || previous == null || previous.length < MAP_SIZE * MAP_SIZE) {
				continue;
			}
			int minX = MAP_SIZE;
			int minY = MAP_SIZE;
			int maxX = -1;
			int maxY = -1;
			for (int y = 0; y < MAP_SIZE; y++) {
				int rowBase = y * MAP_SIZE;
				for (int x = 0; x < MAP_SIZE; x++) {
					int index = rowBase + x;
					if (previous[index] == current[index]) {
						continue;
					}
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
			if (maxX < minX || maxY < minY) {
				continue;
			}
			int patchWidth = maxX - minX + 1;
			int patchHeight = maxY - minY + 1;
			byte[] patchBytes = new byte[patchWidth * patchHeight];
			for (int row = 0; row < patchHeight; row++) {
				System.arraycopy(current, (minY + row) * MAP_SIZE + minX, patchBytes, row * patchWidth, patchWidth);
			}
			patches[tileIndex] = new TilePatch(minX, minY, patchWidth, patchHeight, patchBytes);
		}
		return patches;
	}

	private static final class VideoPlaybackSession implements AutoCloseable {
		private final PlaybackKey key;
		private final long startedAtMs;
		private final Object frameQueueLock = new Object();
		private final ArrayDeque<DecodedVideoFrame> decodedFrameQueue = new ArrayDeque<>();
		private final long frameDurationNanos;
		private final int maxBufferedFrames;
		private volatile DecodedVideoFrame presentedFrame;
		private volatile long lastUsedTick;
		private volatile boolean closed;
		private volatile boolean decoderFailed;
		private Process process;
		private Thread decoderThread;

		private VideoPlaybackSession(PlaybackKey key) throws IOException {
			this.key = key;
			this.startedAtMs = System.currentTimeMillis();
			this.frameDurationNanos = 1_000_000_000L / Math.max(1, this.key.fps());
			this.maxBufferedFrames = Math.max(MIN_DECODE_AHEAD_FRAMES, Math.min(MAX_DECODE_AHEAD_FRAMES, Math.max(1, this.key.fps()) * 4));
			start();
		}

		private void start() throws IOException {
			Path videoPath = CameraMediaCache.videoSourcePath(this.key.sourceKey());
			if (!Files.isRegularFile(videoPath)) {
				throw new IOException("Cached camera video is missing");
			}
			this.decoderThread = new Thread(this::decodeLoop, "lg2-camera-video-" + this.key.sourceKey());
			this.decoderThread.setDaemon(true);
			this.decoderThread.start();
		}

		private int outputWidth() {
			return this.key.mapsWide() * MAP_SIZE;
		}

		private int outputHeight() {
			return this.key.mapsHigh() * MAP_SIZE;
		}

		private void decodeLoop() {
			byte[][] previousTiles = null;
			long sequence = 0L;
			long nextPresentationNanos = System.nanoTime() + this.frameDurationNanos;
			int frameBytes = outputWidth() * outputHeight() * 3;
			Path videoPath = CameraMediaCache.videoSourcePath(this.key.sourceKey());
			while (!this.closed) {
				Process currentProcess = null;
				Thread currentStderrThread = null;
				try {
					currentProcess = startDecoderProcess(videoPath);
					this.process = currentProcess;
					currentStderrThread = startStderrDrain(currentProcess);
					try (InputStream input = currentProcess.getInputStream()) {
						while (!this.closed) {
							byte[] rgb = input.readNBytes(frameBytes);
							if (rgb.length < frameBytes) {
								break;
							}
							byte[] preview = buildPreviewFrame(rgb, outputWidth(), outputHeight());
							byte[][] tiles = buildTileFrames(rgb, outputWidth(), outputHeight(), this.key.mapsWide(), this.key.mapsHigh());
							TilePatch[] patches = buildTilePatches(previousTiles, tiles);
							DecodedVideoFrame decodedFrame = new DecodedVideoFrame(++sequence, nextPresentationNanos, preview, tiles, patches);
							enqueueDecodedFrame(decodedFrame);
							if (sequence == 1L) {
								Lg2.LOGGER.info("Camera video playback session decoded first frame for {}", this.key.sourceKey());
							}
							previousTiles = tiles;
							nextPresentationNanos += this.frameDurationNanos;
						}
					}
				} catch (InterruptedException interruptedException) {
					Thread.currentThread().interrupt();
					break;
				} catch (Exception exception) {
					if (!this.closed) {
						this.decoderFailed = true;
						Lg2.LOGGER.warn("Camera video playback session failed for {}", this.key.sourceKey(), exception);
					}
					break;
				} finally {
					this.process = null;
					destroyProcess(currentProcess);
					if (currentStderrThread != null) {
						currentStderrThread.interrupt();
					}
				}
			}
		}

		private Process startDecoderProcess(Path videoPath) throws IOException {
			List<String> command = new ArrayList<>();
			command.add(ffmpegBinary());
			command.add("-loglevel");
			command.add("error");
			command.add("-i");
			command.add(videoPath.toAbsolutePath().toString());
			command.add("-an");
			command.add("-vf");
			command.add("fps=" + Math.max(1, this.key.fps())
					+ ",scale=w=" + outputWidth() + ":h=" + outputHeight() + ":force_original_aspect_ratio=increase"
					+ ",crop=" + outputWidth() + ":" + outputHeight());
			command.add("-pix_fmt");
			command.add("rgb24");
			command.add("-f");
			command.add("rawvideo");
			command.add("pipe:1");
			return new ProcessBuilder(command).start();
		}

		private Thread startStderrDrain(Process currentProcess) {
			Thread stderrDrain = new Thread(() -> {
				try (InputStream error = currentProcess.getErrorStream()) {
					String stderr = new String(error.readAllBytes(), StandardCharsets.UTF_8).trim();
					if (!stderr.isBlank() && !this.closed) {
						Lg2.LOGGER.warn("Camera video playback ffmpeg stderr for {}: {}", this.key.sourceKey(), stderr);
					}
				} catch (Exception ignored) {
				}
			}, "lg2-camera-video-err-" + this.key.sourceKey());
			stderrDrain.setDaemon(true);
			stderrDrain.start();
			return stderrDrain;
		}

		private void enqueueDecodedFrame(DecodedVideoFrame frame) throws InterruptedException {
			synchronized (this.frameQueueLock) {
				while (!this.closed && this.decodedFrameQueue.size() >= this.maxBufferedFrames) {
					this.frameQueueLock.wait(25L);
				}
				if (this.closed) {
					return;
				}
				this.decodedFrameQueue.addLast(frame);
				this.decoderFailed = false;
				this.frameQueueLock.notifyAll();
			}
		}

		private DecodedVideoFrame latestFrame() {
			long now = System.nanoTime();
			synchronized (this.frameQueueLock) {
				while (!this.decodedFrameQueue.isEmpty()) {
					DecodedVideoFrame next = this.decodedFrameQueue.peekFirst();
					if (next == null || next.displayAtNanos() > now) {
						break;
					}
					this.presentedFrame = this.decodedFrameQueue.removeFirst();
					this.frameQueueLock.notifyAll();
				}
				if (this.presentedFrame != null) {
					return this.presentedFrame;
				}
				if (!this.decodedFrameQueue.isEmpty()) {
					this.presentedFrame = this.decodedFrameQueue.removeFirst();
					this.frameQueueLock.notifyAll();
					return this.presentedFrame;
				}
				return null;
			}
		}

		private boolean isUsable() {
			if (this.closed) {
				return false;
			}
			Thread currentDecoder = this.decoderThread;
			if (currentDecoder == null) {
				return false;
			}
			if (hasRenderableFrame()) {
				return true;
			}
			if (this.decoderFailed) {
				return false;
			}
			return currentDecoder.isAlive() && System.currentTimeMillis() - this.startedAtMs < SESSION_STARTUP_TIMEOUT_MS;
		}

		private boolean hasRenderableFrame() {
			synchronized (this.frameQueueLock) {
				return this.presentedFrame != null || !this.decodedFrameQueue.isEmpty();
			}
		}

		private long lastUsedTick() {
			return this.lastUsedTick;
		}

		private void markUsed(long gameTime) {
			this.lastUsedTick = gameTime;
		}

		@Override
		public void close() {
			this.closed = true;
			synchronized (this.frameQueueLock) {
				this.frameQueueLock.notifyAll();
			}
			destroyProcess(this.process);
			this.process = null;
		}
	}

	private static void destroyProcess(Process process) {
		if (process == null) {
			return;
		}
		process.destroy();
	}

	private record PhotoMapSet(MapId[] mapIds, MapItemSavedData[] mapDataSet) {
	}

	private record PlaybackKey(String sourceKey, int mapsWide, int mapsHigh, int fps) {
	}

	private record PlayerMapKey(UUID playerId, int mapId) {
	}

	private record PlacedViewerKey(UUID playerId, UUID groupId) {
	}

	private record TilePatch(int startX, int startY, int width, int height, byte[] frame) {
	}

	private record DecodedVideoFrame(long sequence, long displayAtNanos, byte[] previewFrame, byte[][] tiles, TilePatch[] tilePatches) {
	}

	private record PlacedVideoGroup(
			UUID groupId,
			ResourceKey<Level> dimension,
			BlockPos anchorPos,
			Direction direction,
			PhotoPrintData data
	) {
	}

	private static final class PersistedPlacedVideosFile {
		private List<PersistedPlacedVideoGroup> groups = new ArrayList<>();
	}

	private static final class PersistedPlacedVideoGroup {
		private String groupId;
		private String dimensionId;
		private int anchorX;
		private int anchorY;
		private int anchorZ;
		private String direction;
		private int mapsWide;
		private int mapsHigh;
		private int previewMapId;
		private int[] mapIds;
		private String mediaKind;
		private String sourceKey;
		private long durationMs;
		private int fps;

		private static PersistedPlacedVideoGroup fromRuntime(PlacedVideoGroup runtime) {
			PersistedPlacedVideoGroup persisted = new PersistedPlacedVideoGroup();
			persisted.groupId = runtime.groupId().toString();
			persisted.dimensionId = runtime.dimension().identifier().toString();
			persisted.anchorX = runtime.anchorPos().getX();
			persisted.anchorY = runtime.anchorPos().getY();
			persisted.anchorZ = runtime.anchorPos().getZ();
			persisted.direction = runtime.direction().getName();
			persisted.mapsWide = runtime.data().mapsWide();
			persisted.mapsHigh = runtime.data().mapsHigh();
			persisted.previewMapId = runtime.data().previewMapId();
			persisted.mapIds = runtime.data().mapIds().clone();
			persisted.mediaKind = runtime.data().mediaKind().serializedName();
			persisted.sourceKey = runtime.data().sourceKey();
			persisted.durationMs = runtime.data().durationMs();
			persisted.fps = runtime.data().fps();
			return persisted;
		}

		private PlacedVideoGroup toRuntime() {
			try {
				UUID parsedGroupId = UUID.fromString(this.groupId);
				Direction parsedDirection = Direction.byName(this.direction);
				if (parsedDirection == null || this.dimensionId == null || this.dimensionId.isBlank()) {
					return null;
				}
				ResourceKey<Level> dimensionKey = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(this.dimensionId));
				PhotoPrintData data = new PhotoPrintData(
						this.mapsWide,
						this.mapsHigh,
						this.previewMapId,
						this.mapIds == null ? new int[0] : this.mapIds,
						PhotoPrintData.MediaKind.fromSerializedName(this.mediaKind),
						this.sourceKey,
						this.durationMs,
						this.fps
				);
				if (!data.isValid() || !data.isVideo()) {
					return null;
				}
				return new PlacedVideoGroup(parsedGroupId, dimensionKey, new BlockPos(this.anchorX, this.anchorY, this.anchorZ), parsedDirection, data);
			} catch (Exception ignored) {
				return null;
			}
		}
	}
}
