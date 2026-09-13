package com.lostglade.client;

import com.lostglade.Lg2;
import com.lostglade.mixin.client.ClientLevelMapDataAccessor;
import com.lostglade.mixin.client.ClientPacketListenerShadowAccessor;
import com.lostglade.mixin.client.MinecraftOffscreenWorldAccessor;
import com.lostglade.mixin.client.CloudRendererReloadInvoker;
import com.lostglade.network.RendererBotPayloads;
import com.lostglade.network.RendererBotShadowPacketCodec;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class RendererBotShadowWorldManager {
	private static final Object LOCK = new Object();
	private static final long ACTIVE_SESSION_TICK_WINDOW_MS = 2_500L;
	private static final long SHADOW_DESTROY_GRACE_MS = 10_000L;
	private static final Map<UUID, ShadowLevelSession> SHADOW_SESSIONS = new HashMap<>();
	private static final Map<UUID, Long> LAST_RENDER_ACTIVITY_AT = new HashMap<>();
	private static final Map<UUID, Long> PENDING_SESSION_DESTROY_AT = new HashMap<>();

	private RendererBotShadowWorldManager() {
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
		ClientTickEvents.END_CLIENT_TICK.register(RendererBotShadowWorldManager::tickShadowWorlds);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotShadowLevelInitS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> applyLevelInit(context.client(), payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotShadowLevelStateS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> applyLevelState(context.client(), payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotShadowViewS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> applyView(context.client(), payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotShadowLevelDestroyS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> destroyShadowSession(payload.sessionId()))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotShadowChunkDataS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> applyChunkData(context.client(), payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotShadowForgetChunkS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> applyForgetChunk(context.client(), payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotShadowContentReadyS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> applyContentReady(payload))
		);
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotShadowEntityPacketsS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> applyEntityPackets(context.client(), payload))
		);
	}

	public static void onMapDataUpdated(ClientPacketListener connection, net.minecraft.network.protocol.game.ClientboundMapItemDataPacket packet) {
		if (connection == null || packet == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		ClientLevel sourceLevel = client.level;
		if (sourceLevel == null) {
			return;
		}
		MapItemSavedData mapData = sourceLevel.getMapData(packet.mapId());
		if (mapData == null) {
			return;
		}
		synchronized (LOCK) {
			for (ShadowLevelSession session : SHADOW_SESSIONS.values()) {
				if (session == null || session.level() == null) {
					continue;
				}
				session.level().overrideMapData(packet.mapId(), mapData);
			}
		}
	}

	public static ShadowRenderSession resolveRenderSession(UUID sessionId) {
		if (sessionId == null) {
			return null;
		}
		synchronized (LOCK) {
			ShadowLevelSession session = SHADOW_SESSIONS.get(sessionId);
			if (session == null) {
				return null;
			}
			LAST_RENDER_ACTIVITY_AT.put(sessionId, System.currentTimeMillis());
			return new ShadowRenderSession(
					session.sessionId(),
					session.dimensionId(),
					session.level(),
					session.levelRenderer(),
					session.featureRenderDispatcher(),
					session.particleEngine(),
					session.appliedViewDistance(),
					session.contentRevision(),
					session.isContentReady(),
					session.currentContentRendered()
			);
		}
	}

	/**
	 * Returns the renderer's own completion signal for a shadow-world camera.
	 * A frame is safe to publish only after vanilla has built and uploaded every
	 * visible section, and no new chunk packet has changed the shadow level
	 * between consecutive checks.
	 */
	public static RenderReadiness inspectRenderReadiness(UUID sessionId) {
		ShadowRenderSession session = resolveRenderSession(sessionId);
		if (session == null || session.levelRenderer() == null) {
			return RenderReadiness.unavailable();
		}
		try {
			LevelRenderer levelRenderer = session.levelRenderer();
			SectionRenderDispatcher dispatcher = levelRenderer.getSectionRenderDispatcher();
			if (dispatcher == null) {
				return RenderReadiness.unavailable();
			}
			boolean contentReady = session.contentReady();
			boolean currentContentRendered = session.currentContentRendered();
			boolean allSectionsRendered = levelRenderer.hasRenderedAllSections();
			int compileQueueSize = dispatcher.getCompileQueueSize();
			int uploadQueueSize = dispatcher.getToUpload();
			int visibleSections = levelRenderer.countRenderedSections();
			return new RenderReadiness(
					contentReady && currentContentRendered && allSectionsRendered && compileQueueSize == 0 && uploadQueueSize == 0,
					session.contentRevision(),
					visibleSections,
					contentReady,
					currentContentRendered,
					allSectionsRendered,
					compileQueueSize,
					uploadQueueSize
			);
		} catch (Throwable ignored) {
			return RenderReadiness.unavailable();
		}
	}

	/** Marks a completed off-screen render for the current shadow-world data. */
	public static void markFrameRendered(UUID sessionId) {
		if (sessionId == null) {
			return;
		}
		synchronized (LOCK) {
			ShadowLevelSession session = SHADOW_SESSIONS.get(sessionId);
			if (session != null) {
				session.markCurrentContentRendered();
			}
		}
	}

	/**
	 * Removes an entity that occupies a first-person static camera from this
	 * shadow world. The normal client hides its own camera entity automatically,
	 * but a frozen photo uses a separate marker as its camera entity.
	 */
	public static void hideEntityFromSession(UUID sessionId, UUID entityUuid) {
		if (sessionId == null || entityUuid == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		ClientPacketListener connection = client.getConnection();
		if (connection == null) {
			return;
		}
		ShadowLevelSession session;
		synchronized (LOCK) {
			session = SHADOW_SESSIONS.get(sessionId);
		}
		if (session == null || session.level() == null) {
			return;
		}
		Entity entity = session.level().getPlayerByUUID(entityUuid);
		if (entity == null) {
			for (Entity candidate : session.level().entitiesForRendering()) {
				if (entityUuid.equals(candidate.getUUID())) {
					entity = candidate;
					break;
				}
			}
		}
		if (entity == null) {
			return;
		}
		int entityId = entity.getId();
		runWithShadowSession(connection, session, () ->
				connection.handleRemoveEntities(new ClientboundRemoveEntitiesPacket(new int[]{entityId}))
		);
	}

	/**
	 * Removes only the current camera's collision placeholder from a shadow
	 * world.  The server still keeps the PLAYER_HEAD collision block, and other
	 * heads in the scene remain untouched.  Chunk updates may restore this
	 * state, so the operation is intentionally idempotent and is repeated just
	 * before an affected camera is rendered.
	 */
	public static void hideCameraCollisionBlock(UUID sessionId, BlockPos position) {
		if (sessionId == null || position == null) {
			return;
		}
		ShadowLevelSession session;
		synchronized (LOCK) {
			session = SHADOW_SESSIONS.get(sessionId);
		}
		if (session == null || session.level() == null || session.levelRenderer() == null) {
			return;
		}
		BlockState state = session.level().getBlockState(position);
		// A client companion intentionally has no Polymer dependency. The server
		// sends this block as its vanilla PLAYER_HEAD fallback, so checking the
		// fallback is both sufficient and keeps server-only CameraBlock classes
		// out of the client class-loading path.
		if (!state.is(Blocks.PLAYER_HEAD)) {
			return;
		}
		session.level().setBlock(position, Blocks.AIR.defaultBlockState(), 3);
		session.level().removeBlockEntity(position);
		session.levelRenderer().setSectionDirty(
				SectionPos.blockToSectionCoord(position.getX()),
				SectionPos.blockToSectionCoord(position.getY()),
				SectionPos.blockToSectionCoord(position.getZ())
		);
	}

	public static void updateCameraContext(UUID sessionId, Camera camera) {
		if (sessionId == null || camera == null) {
			return;
		}
		synchronized (LOCK) {
			ShadowLevelSession session = SHADOW_SESSIONS.get(sessionId);
			if (session == null) {
				return;
			}
			session.setLastCamera(camera);
			((RendererBotShadowLevel) session.level()).setCamera(camera);
			LAST_RENDER_ACTIVITY_AT.put(sessionId, System.currentTimeMillis());
		}
	}

	public static boolean isManagedLevel(ClientLevel level) {
		if (level == null) {
			return false;
		}
		synchronized (LOCK) {
			for (ShadowLevelSession session : SHADOW_SESSIONS.values()) {
				if (session != null && session.level() == level) {
					return true;
				}
			}
		}
		return false;
	}

	public static UUID sessionIdForLevel(ClientLevel level) {
		if (level == null) {
			return null;
		}
		synchronized (LOCK) {
			for (ShadowLevelSession session : SHADOW_SESSIONS.values()) {
				if (session != null && session.level() == level) {
					return session.sessionId();
				}
			}
		}
		return null;
	}

	public static void updateAudioContext(UUID sessionId, double x, double y, double z) {
		if (sessionId == null) {
			return;
		}
		synchronized (LOCK) {
			ShadowLevelSession session = SHADOW_SESSIONS.get(sessionId);
			if (session == null) {
				return;
			}
			session.setAudioBlockPos(BlockPos.containing(x, y, z));
			LAST_RENDER_ACTIVITY_AT.put(sessionId, System.currentTimeMillis());
		}
	}

	public static boolean isManagedRenderer(LevelRenderer renderer, ClientLevel level) {
		if (renderer == null || level == null) {
			return false;
		}
		synchronized (LOCK) {
			for (ShadowLevelSession session : SHADOW_SESSIONS.values()) {
				if (session != null && session.level() == level && session.levelRenderer() == renderer) {
					return true;
				}
			}
		}
		return false;
	}

	public static void clear() {
		Minecraft client = Minecraft.getInstance();
		if (client != null && !client.isSameThread()) {
			client.execute(RendererBotShadowWorldManager::clearOnClientThread);
			return;
		}
		clearOnClientThread();
	}

	private static void clearOnClientThread() {
		synchronized (LOCK) {
			for (ShadowLevelSession session : SHADOW_SESSIONS.values()) {
				closeSession(session);
			}
			SHADOW_SESSIONS.clear();
			LAST_RENDER_ACTIVITY_AT.clear();
			PENDING_SESSION_DESTROY_AT.clear();
		}
	}

	private static void tickShadowWorlds(Minecraft client) {
		if (client == null || client.getConnection() == null) {
			return;
		}
		List<ShadowLevelSession> sessions;
		List<UUID> expiredSessionIds;
		synchronized (LOCK) {
			expiredSessionIds = expirePendingSessionDestroys(System.currentTimeMillis());
			if (SHADOW_SESSIONS.isEmpty()) {
				sessions = List.of();
			} else {
				sessions = new ArrayList<>(SHADOW_SESSIONS.values());
			}
		}
		for (UUID sessionId : expiredSessionIds) {
			RendererBotOffscreenWorldRenderer.releaseSession(sessionId);
		}
		long now = System.currentTimeMillis();
		for (ShadowLevelSession session : sessions) {
			if (session == null || session.level() == null) {
				continue;
			}
			if (!shouldTickSession(session, now)) {
				continue;
			}
			tickShadowSession(client, session);
		}
	}

	private static void tickShadowSession(Minecraft client, ShadowLevelSession session) {
		if (client == null || client.getConnection() == null || session == null || session.level() == null) {
			return;
		}
		runWithShadowSession(client.getConnection(), session, () -> {
			Camera camera = session.lastCamera();
			session.level().tickEntities();
			session.level().tickBlockEntities();
			session.level().tick(() -> true);
			if (camera != null) {
				camera.tick();
				session.levelRenderer().tick(camera);
				BlockPos blockPos = camera.blockPosition();
				session.level().animateTick(blockPos.getX(), blockPos.getY(), blockPos.getZ());
			} else if (session.audioBlockPos() != null) {
				BlockPos blockPos = session.audioBlockPos();
				session.level().animateTick(blockPos.getX(), blockPos.getY(), blockPos.getZ());
			}
			session.particleEngine().tick();
			RendererBotOffscreenWorldRenderer.tickSessionResources(session.sessionId());
		});
	}

	private static void applyLevelInit(Minecraft client, RendererBotPayloads.RendererBotShadowLevelInitS2CPayload payload) {
		if (client == null || client.getConnection() == null || payload == null || payload.sessionId() == null) {
			return;
		}
		synchronized (LOCK) {
			ShadowLevelSession existing = SHADOW_SESSIONS.get(payload.sessionId());
			PENDING_SESSION_DESTROY_AT.remove(payload.sessionId());
			if (existing != null && existing.matchesWorld(payload)) {
				// The server may repeat an init packet while it catches up its shadow
				// synchronisation state.  It is not a new world in that case.  Rebuilding
				// ClientLevel here drops every received chunk and forces the renderer to
				// start compiling from zero again, so a continuous video never gets past
				// the few nearest sections.
				LAST_RENDER_ACTIVITY_AT.put(payload.sessionId(), System.currentTimeMillis());
				applyState(
						existing.level(),
						payload.gameTime(),
						payload.dayTime(),
						payload.tickDayTime(),
						payload.raining(),
						payload.rainLevel(),
						payload.thunderLevel()
				);
				// An init packet has no real cache centre (its 0,0 is only used for a
				// freshly-created level).  Keep the centre supplied by ShadowView;
				// moving an existing cache back to 0,0 would evict the camera area.
				applyViewState(
						client.getConnection(),
						existing.level(),
						payload.viewDistance(),
						existing.appliedCenterChunkX(),
						existing.appliedCenterChunkZ()
				);
				return;
			}

			SHADOW_SESSIONS.remove(payload.sessionId());
			LAST_RENDER_ACTIVITY_AT.remove(payload.sessionId());
			closeSession(existing);
			ShadowLevelSession created = createShadowSession(client, payload);
			if (created == null) {
				return;
			}
			SHADOW_SESSIONS.put(payload.sessionId(), created);
			LAST_RENDER_ACTIVITY_AT.put(payload.sessionId(), System.currentTimeMillis());
			applyState(
					created.level(),
					payload.gameTime(),
					payload.dayTime(),
					payload.tickDayTime(),
					payload.raining(),
					payload.rainLevel(),
					payload.thunderLevel()
			);
			applyViewState(client.getConnection(), created.level(), payload.viewDistance(), 0, 0);
			Lg2.LOGGER.info("Renderer bot created shadow session {} for {}", payload.sessionId(), payload.dimensionId());
		}
	}

	private static void applyLevelState(Minecraft client, RendererBotPayloads.RendererBotShadowLevelStateS2CPayload payload) {
		if (client == null || payload == null || payload.sessionId() == null) {
			return;
		}
		ShadowLevelSession session;
		synchronized (LOCK) {
			session = SHADOW_SESSIONS.get(payload.sessionId());
		}
		if (session == null) {
			return;
		}
		applyState(
				session.level(),
				payload.gameTime(),
				payload.dayTime(),
				payload.tickDayTime(),
				payload.raining(),
				payload.rainLevel(),
				payload.thunderLevel()
		);
	}

	private static void applyView(Minecraft client, RendererBotPayloads.RendererBotShadowViewS2CPayload payload) {
		if (client == null || client.getConnection() == null || payload == null || payload.sessionId() == null) {
			return;
		}
		ShadowLevelSession session;
		synchronized (LOCK) {
			session = SHADOW_SESSIONS.get(payload.sessionId());
		}
		if (session == null) {
			return;
		}
		if (session.appliedViewDistance() != payload.viewDistance()
				|| session.appliedCenterChunkX() != payload.centerChunkX()
				|| session.appliedCenterChunkZ() != payload.centerChunkZ()) {
			session.invalidateContentReady();
		}
		applyViewState(client.getConnection(), session.level(), payload.viewDistance(), payload.centerChunkX(), payload.centerChunkZ());
	}

	private static void applyChunkData(Minecraft client, RendererBotPayloads.RendererBotShadowChunkDataS2CPayload payload) {
		if (client == null || client.getConnection() == null || payload == null || payload.sessionId() == null) {
			return;
		}
		ShadowLevelSession session;
		synchronized (LOCK) {
			session = SHADOW_SESSIONS.get(payload.sessionId());
		}
		if (session == null) {
			return;
		}
		ClientboundLevelChunkWithLightPacket packet = RendererBotShadowPacketCodec.decodeChunkPacket(client.getConnection().registryAccess(), payload.packetBytes());
		ChunkPos chunkPos = new ChunkPos(packet.getX(), packet.getZ());
		if (!session.isChunkDataNew(chunkPos, payload.packetBytes())) {
			return;
		}
		runWithShadowSession(client.getConnection(), session, () -> {
			client.getConnection().handleLevelChunkWithLight(packet);
			session.level().pollLightUpdates();
		});
		session.rememberChunkData(chunkPos, payload.packetBytes());
		session.levelRenderer().onChunkReadyToRender(chunkPos);
		session.markContentChanged();
	}

	private static void applyForgetChunk(Minecraft client, RendererBotPayloads.RendererBotShadowForgetChunkS2CPayload payload) {
		if (client == null || client.getConnection() == null || payload == null || payload.sessionId() == null) {
			return;
		}
		ShadowLevelSession session;
		synchronized (LOCK) {
			session = SHADOW_SESSIONS.get(payload.sessionId());
		}
		if (session == null) {
			return;
		}
		ClientboundForgetLevelChunkPacket packet = new ClientboundForgetLevelChunkPacket(new ChunkPos(payload.chunkX(), payload.chunkZ()));
		runWithShadowSession(client.getConnection(), session, () -> {
			client.getConnection().handleForgetLevelChunk(packet);
			session.level().pollLightUpdates();
		});
		session.forgetChunk(packet.pos());
		session.markContentChanged();
	}

	private static void applyContentReady(RendererBotPayloads.RendererBotShadowContentReadyS2CPayload payload) {
		if (payload == null || payload.sessionId() == null) {
			return;
		}
		ShadowLevelSession session;
		synchronized (LOCK) {
			session = SHADOW_SESSIONS.get(payload.sessionId());
		}
		if (session != null) {
			session.markContentReady(payload.revision());
		}
	}

	private static void applyEntityPackets(Minecraft client, RendererBotPayloads.RendererBotShadowEntityPacketsS2CPayload payload) {
		if (client == null || client.getConnection() == null || payload == null || payload.sessionId() == null || payload.packets() == null || payload.packets().isEmpty()) {
			return;
		}
		ShadowLevelSession session;
		synchronized (LOCK) {
			session = SHADOW_SESSIONS.get(payload.sessionId());
		}
		if (session == null) {
			return;
		}
		runWithShadowSession(client.getConnection(), session, () -> {
			for (RendererBotPayloads.ShadowPacketData packetData : payload.packets()) {
				Packet<ClientGamePacketListener> packet = RendererBotShadowPacketCodec.decodePacket(client.getConnection().registryAccess(), packetData);
				RendererBotShadowLevel scene = (RendererBotShadowLevel) session.level();
				if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket info) {
					for (var entry : info.entries()) {
						if (entry.profile() != null) {
							scene.playerProfiles().put(entry.profileId(), new net.minecraft.client.multiplayer.PlayerInfo(entry.profile(), false));
						}
					}
				} else if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket info) {
					info.profileIds().forEach(scene.playerProfiles()::remove);
				} else if (packet instanceof ClientboundAddEntityPacket add && add.getType() == net.minecraft.world.entity.EntityType.PLAYER) {
					scene.addPlayer(add);
				} else if (packet != null) {
					packet.handle(client.getConnection());
				}
			}
		});
	}


	private static void destroyShadowSession(UUID sessionId) {
		if (sessionId == null) {
			return;
		}
		synchronized (LOCK) {
			if (SHADOW_SESSIONS.containsKey(sessionId)) {
				PENDING_SESSION_DESTROY_AT.put(sessionId, System.currentTimeMillis() + SHADOW_DESTROY_GRACE_MS);
			}
		}
	}

	private static List<UUID> expirePendingSessionDestroys(long now) {
		List<UUID> expiredSessionIds = new ArrayList<>();
		for (Map.Entry<UUID, Long> entry : new ArrayList<>(PENDING_SESSION_DESTROY_AT.entrySet())) {
			UUID sessionId = entry.getKey();
			Long destroyAt = entry.getValue();
			if (sessionId == null || destroyAt == null || destroyAt > now) {
				continue;
			}
			PENDING_SESSION_DESTROY_AT.remove(sessionId);
			ShadowLevelSession removed = SHADOW_SESSIONS.remove(sessionId);
			LAST_RENDER_ACTIVITY_AT.remove(sessionId);
			closeSession(removed);
			expiredSessionIds.add(sessionId);
		}
		return expiredSessionIds;
	}

	private static ShadowLevelSession createShadowSession(Minecraft client, RendererBotPayloads.RendererBotShadowLevelInitS2CPayload payload) {
		ClientPacketListener connection = client.getConnection();
		if (connection == null) {
			return null;
		}
		ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(payload.dimensionId()));
		ResourceKey<net.minecraft.world.level.dimension.DimensionType> dimensionTypeKey =
				ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(payload.dimensionTypeId()));
		Holder<net.minecraft.world.level.dimension.DimensionType> dimensionType = connection.registryAccess()
				.lookupOrThrow(Registries.DIMENSION_TYPE)
				.getOrThrow(dimensionTypeKey);
		ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(
				resolveDifficulty(payload.difficultyOrdinal()),
				payload.hardcore(),
				payload.flat()
		);
		// A background scene must not allocate another full CPU-sized mesh pool.
		RenderBuffers renderBuffers = new RenderBuffers(1);
		FeatureRenderDispatcher featureRenderDispatcher = new FeatureRenderDispatcher(
				new SubmitNodeStorage(),
				client.getBlockRenderer(),
				renderBuffers.bufferSource(),
				client.getAtlasManager(),
				renderBuffers.outlineBufferSource(),
				renderBuffers.crumblingBufferSource(),
				client.font
		);
		LevelRenderer levelRenderer = new LevelRenderer(
				client,
				client.getEntityRenderDispatcher(),
				client.getBlockEntityRenderDispatcher(),
				renderBuffers,
				new LevelRenderState(),
				featureRenderDispatcher
		);
		levelRenderer.onResourceManagerReload(client.getResourceManager());
		initializeShadowCloudRenderer(levelRenderer, client.getResourceManager());
		ClientLevel level = new RendererBotShadowLevel(
				connection,
				levelData,
				dimensionKey,
				dimensionType,
				Math.max(2, payload.viewDistance()),
				Math.max(2, payload.simulationDistance()),
				levelRenderer,
				payload.debug(),
				payload.seed(),
				payload.seaLevel()
		);
		levelRenderer.setLevel(level);
		levelRenderer.resize(Math.max(1, client.getWindow().getWidth()), Math.max(1, client.getWindow().getHeight()));
		level.setServerSimulationDistance(Math.max(2, payload.simulationDistance()));
		ParticleEngine particleEngine = new ParticleEngine(level, ((MinecraftOffscreenWorldAccessor) client).lg2$getParticleResources());
		((RendererBotShadowLevel) level).setSceneParticles(particleEngine);
		copyKnownMapData(client.level, level);
		return new ShadowLevelSession(
				payload.sessionId(),
				payload.dimensionId(),
				level,
				levelRenderer,
				featureRenderDispatcher,
				particleEngine
		);
	}

	private static void initializeShadowCloudRenderer(LevelRenderer levelRenderer, ResourceManager resourceManager) {
		if (levelRenderer == null || resourceManager == null) {
			return;
		}
		CloudRenderer cloudRenderer = levelRenderer.getCloudRenderer();
		if (cloudRenderer == null) {
			return;
		}
		try {
			CloudRendererReloadInvoker invoker = (CloudRendererReloadInvoker) cloudRenderer;
			invoker.lg2$applyPreparedClouds(
					invoker.lg2$prepareCloudTexture(resourceManager, InactiveProfiler.INSTANCE),
					resourceManager,
					InactiveProfiler.INSTANCE
			);
		} catch (Throwable throwable) {
			Lg2.LOGGER.warn("Renderer bot failed to initialize shadow cloud renderer", throwable);
		}
	}

	private static void closeSession(ShadowLevelSession session) {
		if (session == null) {
			return;
		}
		try {
			session.levelRenderer().setLevel(null);
		} catch (Throwable throwable) {
			Lg2.LOGGER.debug("Renderer bot failed to detach shadow level renderer cleanly", throwable);
		}
		try {
			session.levelRenderer().close();
		} catch (Throwable throwable) {
			Lg2.LOGGER.debug("Renderer bot failed to close shadow level renderer cleanly", throwable);
		}
		try {
			session.featureRenderDispatcher().close();
		} catch (Throwable throwable) {
			Lg2.LOGGER.debug("Renderer bot failed to close shadow feature dispatcher cleanly", throwable);
		}
		try {
			session.particleEngine().clearParticles();
		} catch (Throwable throwable) {
			Lg2.LOGGER.debug("Renderer bot failed to clear shadow particle manager cleanly", throwable);
		}
	}

	public static void runForLevel(RendererBotShadowLevel level, Runnable action) {
		ShadowLevelSession session = findSession(level);
		if (session != null) runWithShadowSession(Minecraft.getInstance().getConnection(), session, action);
	}

	private static void runWithShadowSession(ClientPacketListener connection, ShadowLevelSession session, Runnable action) {
		if (connection == null || session == null || session.level() == null || action == null) {
			return;
		}
		ClientLevel shadowLevel = session.level();
		ClientPacketListenerShadowAccessor accessor = (ClientPacketListenerShadowAccessor) connection;
		ClientLevel previousLevel = accessor.lg2$getLevel();
		ClientLevel.ClientLevelData previousLevelData = accessor.lg2$getLevelData();
		try (var ignored = RendererBotSceneContext.enter((RendererBotShadowLevel) shadowLevel)) {
			// Vanilla packet handlers use the connection's level reference. Keep that
			// reference local to the handler, but never replace Minecraft.level,
			// Minecraft.levelRenderer or the main camera: those fields drive the
			// player's visible world and caused frame flicker/view-distance rebuilds.
			accessor.lg2$setLevel(shadowLevel);
			accessor.lg2$setLevelData(shadowLevel.getLevelData());
			action.run();
		} finally {
			accessor.lg2$setLevel(previousLevel);
			accessor.lg2$setLevelData(previousLevelData);
		}
	}

	private static void applyViewState(ClientPacketListener connection, ClientLevel level, int viewDistance, int centerChunkX, int centerChunkZ) {
		if (connection == null || level == null) {
			return;
		}
		ShadowLevelSession session = findSession(level);
		if (session == null) {
			return;
		}
		int clampedViewDistance = Math.max(2, viewDistance);
		runWithShadowSession(connection, session, () -> {
			// handleSetChunkCacheRadius rebuilds vanilla's cache storage even when
			// the radius is identical. A moving drone was therefore discarding every
			// already received shadow chunk on every centre update and re-rendering the
			// whole view. A normal client changes only its centre while travelling.
			if (session.appliedViewDistance() != clampedViewDistance) {
				// The vanilla packet handler also changes the OWNER'S Options and
				// invalidates their terrain. Only touch this scene's chunk storage.
				level.getChunkSource().updateViewRadius(clampedViewDistance);
				((RendererBotShadowLevel) level).setSceneViewDistance(clampedViewDistance);
				session.setAppliedViewDistance(clampedViewDistance);
			}
			if (session.appliedCenterChunkX() != centerChunkX || session.appliedCenterChunkZ() != centerChunkZ) {
				level.getChunkSource().updateViewCenter(centerChunkX, centerChunkZ);
				session.setAppliedCenter(centerChunkX, centerChunkZ);
			}
		});
	}

	private static ShadowLevelSession findSession(ClientLevel level) {
		if (level == null) {
			return null;
		}
		synchronized (LOCK) {
			for (ShadowLevelSession session : SHADOW_SESSIONS.values()) {
				if (session != null && session.level() == level) {
					return session;
				}
			}
		}
		return null;
	}

	private static void applyState(
			ClientLevel level,
			long gameTime,
			long dayTime,
			boolean tickDayTime,
			boolean raining,
			float rainLevel,
			float thunderLevel
	) {
		if (level == null) {
			return;
		}
		level.setTimeFromServer(gameTime, dayTime, tickDayTime);
		level.getLevelData().setRaining(raining);
		level.setRainLevel(rainLevel);
		level.setThunderLevel(thunderLevel);
		level.environmentAttributes().invalidateTickCache();
	}

	private static boolean shouldTickSession(ShadowLevelSession session, long now) {
		if (session == null || session.sessionId() == null) {
			return false;
		}
		synchronized (LOCK) {
			Long lastActivity = LAST_RENDER_ACTIVITY_AT.get(session.sessionId());
			// Already called once per simulation tick, not once per rendered frame.
			return lastActivity != null && now - lastActivity <= ACTIVE_SESSION_TICK_WINDOW_MS;
		}
	}

	private static void copyKnownMapData(ClientLevel sourceLevel, ClientLevel targetLevel) {
		if (sourceLevel == null || targetLevel == null) {
			return;
		}
		Map<MapId, MapItemSavedData> sourceMapData = ((ClientLevelMapDataAccessor) sourceLevel).lg2$getMapData();
		if (sourceMapData == null || sourceMapData.isEmpty()) {
			return;
		}
		for (Map.Entry<MapId, MapItemSavedData> entry : sourceMapData.entrySet()) {
			MapId mapId = entry.getKey();
			MapItemSavedData mapData = entry.getValue();
			if (mapId == null || mapData == null) {
				continue;
			}
			targetLevel.overrideMapData(mapId, mapData);
		}
	}

	private static Difficulty resolveDifficulty(int ordinal) {
		Difficulty[] values = Difficulty.values();
		if (ordinal < 0 || ordinal >= values.length) {
			return Difficulty.NORMAL;
		}
		return values[ordinal];
	}

	public record ShadowRenderSession(
			UUID sessionId,
			String dimensionId,
			ClientLevel level,
			LevelRenderer levelRenderer,
			FeatureRenderDispatcher featureRenderDispatcher,
			ParticleEngine particleEngine,
			int viewDistance,
			long contentRevision,
			boolean contentReady,
			boolean currentContentRendered
	) {
	}

	public record RenderReadiness(
			boolean settled,
			long contentRevision,
			int visibleSections,
			boolean contentReady,
			boolean currentContentRendered,
			boolean allSectionsRendered,
			int compileQueueSize,
			int uploadQueueSize
	) {
		private static RenderReadiness unavailable() {
			return new RenderReadiness(false, -1L, 0, false, false, false, -1, -1);
		}
	}

	private static final class ShadowLevelSession {
		private final UUID sessionId;
		private final String dimensionId;
		private final ClientLevel level;
		private final LevelRenderer levelRenderer;
		private final FeatureRenderDispatcher featureRenderDispatcher;
		private final ParticleEngine particleEngine;
		private final Map<Long, Long> chunkPacketFingerprints = new HashMap<>();
		private Camera lastCamera;
		private BlockPos audioBlockPos;
		private int appliedViewDistance = Integer.MIN_VALUE;
		private int appliedCenterChunkX = Integer.MIN_VALUE;
		private int appliedCenterChunkZ = Integer.MIN_VALUE;
		private long contentRevision;
		private long renderedContentRevision = Long.MIN_VALUE;
		private long serverReadyContentRevision = Long.MIN_VALUE;
		private long serverReadyRevision = Long.MIN_VALUE;

		private ShadowLevelSession(
				UUID sessionId,
				String dimensionId,
				ClientLevel level,
				LevelRenderer levelRenderer,
				FeatureRenderDispatcher featureRenderDispatcher,
				ParticleEngine particleEngine
		) {
			this.sessionId = sessionId;
			this.dimensionId = dimensionId;
			this.level = level;
			this.levelRenderer = levelRenderer;
			this.featureRenderDispatcher = featureRenderDispatcher;
			this.particleEngine = particleEngine;
		}

		private long contentRevision() {
			return this.contentRevision;
		}

		private void markContentChanged() {
			this.contentRevision++;
			this.renderedContentRevision = Long.MIN_VALUE;
			this.invalidateContentReady();
		}

		private void markCurrentContentRendered() {
			this.renderedContentRevision = this.contentRevision;
		}

		private boolean currentContentRendered() {
			return this.renderedContentRevision == this.contentRevision;
		}

		private void invalidateContentReady() {
			this.serverReadyContentRevision = Long.MIN_VALUE;
		}

		private void markContentReady(long revision) {
			if (revision < this.serverReadyRevision) {
				return;
			}
			this.serverReadyRevision = revision;
			this.serverReadyContentRevision = this.contentRevision;
		}

		private boolean isContentReady() {
			return this.serverReadyContentRevision == this.contentRevision;
		}

		/**
		 * A LevelChunkWithLight packet replaces the client chunk and invalidates all
		 * of its section meshes.  Shadow synchronisation may resend an unchanged
		 * packet after recovering from a transient server-side state reset; applying
		 * it would make an otherwise warm camera recompile the whole view forever.
		 */
		private boolean isChunkDataNew(ChunkPos chunkPos, byte[] packetBytes) {
			if (chunkPos == null || packetBytes == null) {
				return true;
			}
			long chunkLong = chunkPos.toLong();
			long fingerprint = fingerprint(packetBytes);
			Long previousFingerprint = this.chunkPacketFingerprints.get(chunkLong);
			return previousFingerprint == null || previousFingerprint != fingerprint;
		}

		private void rememberChunkData(ChunkPos chunkPos, byte[] packetBytes) {
			if (chunkPos != null && packetBytes != null) {
				this.chunkPacketFingerprints.put(chunkPos.toLong(), fingerprint(packetBytes));
			}
		}

		private void forgetChunk(ChunkPos chunkPos) {
			if (chunkPos != null) {
				this.chunkPacketFingerprints.remove(chunkPos.toLong());
			}
		}

		private static long fingerprint(byte[] bytes) {
			long hash = 0xcbf29ce484222325L;
			for (byte value : bytes) {
				hash ^= value & 0xFFL;
				hash *= 0x100000001b3L;
			}
			return hash;
		}

		private UUID sessionId() {
			return this.sessionId;
		}

		private boolean matchesWorld(RendererBotPayloads.RendererBotShadowLevelInitS2CPayload payload) {
			// A render-session id is the stable identity of the shadow world. Server
			// init packets may repeat while the normal client is still joining, and
			// some dimension metadata can be reconstructed differently on either side.
			// Replacing a same-dimension session for that metadata would drop all
			// chunks every second. A real dimension change uses another session id.
			return payload != null && Objects.equals(this.dimensionId, payload.dimensionId());
		}

		private String dimensionId() {
			return this.dimensionId;
		}

		private ClientLevel level() {
			return this.level;
		}

		private LevelRenderer levelRenderer() {
			return this.levelRenderer;
		}

		private FeatureRenderDispatcher featureRenderDispatcher() {
			return this.featureRenderDispatcher;
		}

		private ParticleEngine particleEngine() {
			return this.particleEngine;
		}

		private Camera lastCamera() {
			return this.lastCamera;
		}

		private void setLastCamera(Camera lastCamera) {
			this.lastCamera = lastCamera;
		}

		private BlockPos audioBlockPos() {
			return this.audioBlockPos;
		}

		private void setAudioBlockPos(BlockPos audioBlockPos) {
			this.audioBlockPos = audioBlockPos;
		}

		private int appliedViewDistance() {
			return this.appliedViewDistance;
		}

		private void setAppliedViewDistance(int appliedViewDistance) {
			this.appliedViewDistance = appliedViewDistance;
		}

		private int appliedCenterChunkX() {
			return this.appliedCenterChunkX;
		}

		private int appliedCenterChunkZ() {
			return this.appliedCenterChunkZ;
		}

		private void setAppliedCenter(int centerChunkX, int centerChunkZ) {
			this.appliedCenterChunkX = centerChunkX;
			this.appliedCenterChunkZ = centerChunkZ;
		}
	}
}
