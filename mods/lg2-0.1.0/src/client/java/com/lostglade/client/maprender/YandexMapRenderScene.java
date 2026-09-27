package com.lostglade.client.maprender;

import com.lostglade.mixin.client.ClientPacketListenerShadowAccessor;
import com.lostglade.mixin.client.MinecraftOffscreenWorldAccessor;
import com.lostglade.mixin.client.SectionOcclusionGraphAccessor;
import com.lostglade.network.RendererBotPayloads;
import com.lostglade.network.RendererBotShadowPacketCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Fresh, single-job vanilla client scene. Never registered with camera sessions. */
public final class YandexMapRenderScene implements AutoCloseable {
	private final Minecraft client;
	private final ClientPacketListener connection;
	private final SceneDescriptor descriptor;
	private final RenderBuffers renderBuffers;
	private final FeatureRenderDispatcher featureRenderDispatcher;
	private final LevelRenderer levelRenderer;
	private final YandexMapRenderWorld level;
	private final ParticleEngine particleEngine;
	private final Set<Integer> itemDisplayIds = new HashSet<>();
	private long contentRevision;
	private boolean closed;

	private YandexMapRenderScene(
			Minecraft client,
			ClientPacketListener connection,
			SceneDescriptor descriptor,
			RenderBuffers renderBuffers,
			FeatureRenderDispatcher featureRenderDispatcher,
			LevelRenderer levelRenderer,
			YandexMapRenderWorld level,
			ParticleEngine particleEngine
	) {
		this.client = client;
		this.connection = connection;
		this.descriptor = descriptor;
		this.renderBuffers = renderBuffers;
		this.featureRenderDispatcher = featureRenderDispatcher;
		this.levelRenderer = levelRenderer;
		this.level = level;
		this.particleEngine = particleEngine;
	}

	public static YandexMapRenderScene create(Minecraft client, SceneDescriptor descriptor) {
		Objects.requireNonNull(client, "client");
		Objects.requireNonNull(descriptor, "descriptor");
		ClientPacketListener connection = client.getConnection();
		if (connection == null) {
			throw new IllegalStateException("Cannot create map render scene without a play connection");
		}
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(descriptor.dimensionId()));
		ResourceKey<DimensionType> dimensionTypeKey = ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(descriptor.dimensionTypeId()));
		Holder<DimensionType> dimensionType = connection.registryAccess()
				.lookupOrThrow(Registries.DIMENSION_TYPE)
				.getOrThrow(dimensionTypeKey);
		ClientLevel.ClientLevelData levelData = new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false);
		RenderBuffers buffers = new RenderBuffers(1);
		FeatureRenderDispatcher features = new FeatureRenderDispatcher(
				new SubmitNodeStorage(),
				client.getBlockRenderer(),
				buffers.bufferSource(),
				client.getAtlasManager(),
				buffers.outlineBufferSource(),
				buffers.crumblingBufferSource(),
				client.font
		);
		LevelRenderer renderer = new LevelRenderer(
				client,
				client.getEntityRenderDispatcher(),
				client.getBlockEntityRenderDispatcher(),
				buffers,
				new LevelRenderState(),
				features
		);
		renderer.onResourceManagerReload(client.getResourceManager());
		YandexMapRenderWorld level = new YandexMapRenderWorld(
				connection,
				levelData,
				dimension,
				dimensionType,
				Math.max(6, descriptor.viewDistance()),
				renderer,
				descriptor.seed(),
				descriptor.seaLevel()
		);
		renderer.setLevel(level);
		renderer.resize(descriptor.renderPixels(), descriptor.renderPixels());
		level.setServerSimulationDistance(2);
		ParticleEngine particles = new ParticleEngine(level, ((MinecraftOffscreenWorldAccessor) client).lg2$getParticleResources());
		level.setSceneParticles(particles);
		YandexMapRenderScene scene = new YandexMapRenderScene(client, connection, descriptor, buffers, features, renderer, level, particles);
		scene.configureChunkWindow();
		return scene;
	}

	public YandexMapRenderWorld level() {
		return this.level;
	}

	public LevelRenderer levelRenderer() {
		return this.levelRenderer;
	}

	public FeatureRenderDispatcher featureRenderDispatcher() {
		return this.featureRenderDispatcher;
	}

	public SceneDescriptor descriptor() {
		return this.descriptor;
	}

	public long contentRevision() {
		return this.contentRevision;
	}

	public void setCamera(net.minecraft.client.Camera camera) {
		this.level.setCamera(camera);
	}

	public void applyChunkPacket(byte[] packetBytes) {
		ensureOpen();
		ClientboundLevelChunkWithLightPacket packet = RendererBotShadowPacketCodec.decodeChunkPacket(this.connection.registryAccess(), packetBytes);
		runWithWorld(() -> {
			this.connection.handleLevelChunkWithLight(packet);
			this.level.pollLightUpdates();
		});
		ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
		this.levelRenderer.onChunkReadyToRender(pos);
		this.contentRevision++;
	}

	public void applyItemDisplayPacket(String packetTypeId, byte[] packetBytes) {
		ensureOpen();
		Packet<ClientGamePacketListener> packet = RendererBotShadowPacketCodec.decodePacket(
				this.connection.registryAccess(),
				new RendererBotPayloads.ShadowPacketData(packetTypeId, packetBytes)
		);
		if (packet instanceof ClientboundAddEntityPacket add) {
			if (add.getType() != EntityType.ITEM_DISPLAY) {
				throw new IllegalArgumentException("Map scene rejected non-ItemDisplay add packet: " + add.getType());
			}
			this.itemDisplayIds.add(add.getId());
		} else if (packet instanceof ClientboundSetEntityDataPacket metadata) {
			if (!this.itemDisplayIds.contains(metadata.id())) {
				throw new IllegalArgumentException("Map scene rejected metadata for unknown ItemDisplay id " + metadata.id());
			}
		} else {
			throw new IllegalArgumentException("Map scene rejected unexpected entity packet " + packet.getClass().getName());
		}
		runWithWorld(() -> packet.handle(this.connection));
		this.contentRevision++;
	}

	public RenderReadiness inspectReadiness() {
		ensureOpen();
		SectionRenderDispatcher dispatcher = this.levelRenderer.getSectionRenderDispatcher();
		if (dispatcher == null) {
			return RenderReadiness.unavailable(this.contentRevision);
		}
		boolean allSectionsRendered = this.levelRenderer.hasRenderedAllSections();
		int compileQueue = dispatcher.getCompileQueueSize();
		int uploadQueue = dispatcher.getToUpload();
		int visibleSections = this.levelRenderer.countRenderedSections();
		SectionOcclusionGraphAccessor graph = (SectionOcclusionGraphAccessor) this.levelRenderer.getSectionOcclusionGraph();
		var fullUpdateTask = graph.lg2$getFullUpdateTask();
		boolean graphTaskPresent = fullUpdateTask != null;
		boolean graphTaskDone = fullUpdateTask != null && fullUpdateTask.isDone();
		int loadedChunks = this.level.getChunkSource().getLoadedChunksCount();
		int tileChunks = this.descriptor.tileBlocks() / 16;
		int centerChunkX = Math.toIntExact(Math.multiplyExact(this.descriptor.tileX(), (long) tileChunks) + tileChunks / 2L);
		int centerChunkZ = Math.toIntExact(Math.multiplyExact(this.descriptor.tileZ(), (long) tileChunks) + tileChunks / 2L);
		int lightReadyColumns = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				long sectionNode = SectionPos.getZeroNode(centerChunkX + dx, centerChunkZ + dz);
				if (this.level.getLightEngine().lightOnInColumn(sectionNode)) {
					lightReadyColumns++;
				}
			}
		}
		return new RenderReadiness(
				visibleSections > 0 && allSectionsRendered && compileQueue == 0 && uploadQueue == 0,
				this.contentRevision,
				visibleSections,
				allSectionsRendered,
				compileQueue,
				uploadQueue,
				graph.lg2$needsFullUpdate(),
				graphTaskPresent,
				graphTaskDone,
				loadedChunks,
				lightReadyColumns
		);
	}

	public void runWithWorld(Runnable action) {
		ensureOpen();
		ClientPacketListenerShadowAccessor accessor = (ClientPacketListenerShadowAccessor) this.connection;
		ClientLevel previousLevel = accessor.lg2$getLevel();
		ClientLevel.ClientLevelData previousLevelData = accessor.lg2$getLevelData();
		try (var ignored = YandexMapRenderContext.enterWorld(this.level)) {
			accessor.lg2$setLevel(this.level);
			accessor.lg2$setLevelData(this.level.getLevelData());
			action.run();
		} finally {
			accessor.lg2$setLevel(previousLevel);
			accessor.lg2$setLevelData(previousLevelData);
		}
	}

	private void configureChunkWindow() {
		int tileChunks = this.descriptor.tileBlocks() / 16;
		long minChunkX = Math.multiplyExact(this.descriptor.tileX(), (long) tileChunks);
		long minChunkZ = Math.multiplyExact(this.descriptor.tileZ(), (long) tileChunks);
		int centerX = Math.toIntExact(minChunkX + tileChunks / 2L);
		int centerZ = Math.toIntExact(minChunkZ + tileChunks / 2L);
		this.level.getChunkSource().updateViewRadius(Math.max(6, this.descriptor.viewDistance()));
		this.level.getChunkSource().updateViewCenter(centerX, centerZ);
	}

	private void ensureOpen() {
		if (this.closed) {
			throw new IllegalStateException("Yandex map render scene is already closed");
		}
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		try {
			this.levelRenderer.setLevel(null);
		} catch (Throwable ignored) {
		}
		try {
			this.levelRenderer.close();
		} catch (Throwable ignored) {
		}
		try {
			this.featureRenderDispatcher.close();
		} catch (Throwable ignored) {
		}
		try {
			this.particleEngine.clearParticles();
		} catch (Throwable ignored) {
		}
	}

	public record SceneDescriptor(
			String dimensionId,
			String dimensionTypeId,
			long seed,
			int seaLevel,
			long tileX,
			long tileZ,
			int tileBlocks,
			int renderPixels,
			int viewDistance
	) {
		public SceneDescriptor {
			if (tileBlocks <= 0 || tileBlocks % 16 != 0) {
				throw new IllegalArgumentException("map scene tileBlocks must be a positive chunk multiple");
			}
			if (renderPixels != 256) {
				throw new IllegalArgumentException("map scene must render exactly 256x256");
			}
		}
	}

	public record RenderReadiness(
			boolean settled,
			long contentRevision,
			int visibleSections,
			boolean allSectionsRendered,
			int compileQueueSize,
			int uploadQueueSize,
			boolean graphNeedsFullUpdate,
			boolean graphTaskPresent,
			boolean graphTaskDone,
			int loadedChunks,
			int lightReadyColumns
	) {
		private static RenderReadiness unavailable(long revision) {
			return new RenderReadiness(false, revision, 0, false, -1, -1, true, false, false, 0, 0);
		}
	}
}
