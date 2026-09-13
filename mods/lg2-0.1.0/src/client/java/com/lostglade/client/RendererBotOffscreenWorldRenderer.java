package com.lostglade.client;

import com.lostglade.Lg2;
import com.lostglade.mixin.client.CameraPositionInvoker;
import com.lostglade.mixin.client.GameRendererRenderLevelInvoker;
import com.lostglade.mixin.client.LevelRendererRenderStateAccessor;
import com.lostglade.mixin.client.MinecraftMainRenderTargetAccessor;
import com.lostglade.mixin.client.MinecraftOffscreenWorldAccessor;
import com.lostglade.mixin.client.BlockEntityCameraStateAccessor;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class RendererBotOffscreenWorldRenderer {
	private static final Object LOCK = new Object();
	private static final double STATIC_CAMERA_EYE_HEIGHT = 1.62D;
	private static final double TOP_DOWN_CAMERA_HEADROOM_BLOCKS = 16.0D;
	private static final long MAP_RENDER_GAME_TIME = 0L;
	private static final Map<UUID, OffscreenSessionState> SESSION_STATES = new HashMap<>();
	private static boolean offscreenRenderActive;
	private static Camera activeCamera;
	private static LightTexture activeLightTexture;

	public static Camera activeCamera() { return activeCamera; }
	public static LightTexture activeLightTexture() { return activeLightTexture; }

	public static void tickSessionResources(UUID sessionId) {
		OffscreenSessionState state = SESSION_STATES.get(sessionId);
		if (state != null) state.lightTexture.tick();
	}

	private RendererBotOffscreenWorldRenderer() {
	}

	public static boolean isOffscreenRenderActive() {
		synchronized (LOCK) {
			return offscreenRenderActive;
		}
	}

	public static void clearCaches() {
		synchronized (LOCK) {
			for (OffscreenSessionState state : SESSION_STATES.values()) {
				closeSessionState(state);
			}
			SESSION_STATES.clear();
		}
		RendererBotTopDownMapRenderer.clearCaches();
		DroneCameraTilt.clear();
	}

	public static void releaseSession(UUID sessionId) {
		if (sessionId == null) {
			return;
		}
		synchronized (LOCK) {
			closeSessionState(SESSION_STATES.remove(sessionId));
		}
	}

	public static boolean render(Minecraft client, RenderRequest request, Consumer<NativeImage> imageConsumer) {
		return renderToTarget(client, request, renderTarget -> Screenshot.takeScreenshot(renderTarget, imageConsumer));
	}

	public static boolean renderToTarget(Minecraft client, RenderRequest request, Consumer<RenderTarget> renderTargetConsumer) {
		if (client == null
				|| request == null
				|| renderTargetConsumer == null
				|| client.gameRenderer == null) {
			return false;
		}
		if (client.screen != null || client.getOverlay() != null) {
			return false;
		}
		RendererBotShadowWorldManager.ShadowRenderSession session = RendererBotShadowWorldManager.resolveRenderSession(request.sessionId());
		if (session == null || session.level() == null || session.levelRenderer() == null) {
			return false;
		}

		ClientLevel renderLevel = session.level();
		LevelRenderer levelRenderer = session.levelRenderer();
		synchronized (LOCK) {
			if (offscreenRenderActive) {
				return false;
			}

			OffscreenSessionState sessionState = SESSION_STATES.computeIfAbsent(request.sessionId(), ignored -> new OffscreenSessionState());
			TextureTarget renderTarget = ensureRenderTarget(sessionState, request.renderWidth(), request.renderHeight());
			Entity followTarget = resolveFollowTarget(renderLevel, request.followEntityUuid());
			CameraState cameraState = resolveCameraState(client, renderLevel, request, sessionState, followTarget);
			if (cameraState == null || !isWorldReady(renderLevel, cameraState)) {
				sessionState.renderInProgress = false;
				return false;
			}
			if (request.hideCameraCollisionBlock()) {
				// A placed camera's client-side PLAYER_HEAD is only the interaction
				// shape.  Its visible body is the separately tracked ItemDisplay, so
				// neither belongs in the camera's own first-person shadow world.
				RendererBotShadowWorldManager.hideCameraCollisionBlock(
						request.sessionId(),
						BlockPos.containing(cameraState.camera().position())
				);
			}

			// resize() invalidates the LevelRenderer even when the dimensions did
			// not change. Calling it for every stream frame continuously rebuilt
			// shadow terrain and consumed the player's render budget.
			if (sessionState.appliedRendererWidth != request.renderWidth()
					|| sessionState.appliedRendererHeight != request.renderHeight()) {
				levelRenderer.resize(request.renderWidth(), request.renderHeight());
				sessionState.appliedRendererWidth = request.renderWidth();
				sessionState.appliedRendererHeight = request.renderHeight();
			}
			RenderTarget previousRenderTarget = client.getMainRenderTarget();
			// Only GPU bindings and dispatcher scratch state are scoped here.
			// Minecraft's level, player, renderer and particle engine never change.
			var entityDispatcher = client.getEntityRenderDispatcher();
			Camera previousEntityCamera = entityDispatcher.camera;
			Entity previousPickedEntity = entityDispatcher.crosshairPickEntity;
			var blockDispatcher = (BlockEntityCameraStateAccessor) client.getBlockEntityRenderDispatcher();
			Vec3 previousBlockCamera = blockDispatcher.lg2$getCameraPos();
			double previousEntityViewScale = Entity.getViewScale();
			var previousGlobalSettings = RenderSystem.getGlobalSettingsUniform();
			var previousFog = RenderSystem.getShaderFog();
			var previousLights = RenderSystem.getShaderLights();
			boolean previousSmartCull = client.smartCull;

			try (var ignored = RendererBotSceneContext.enter((RendererBotShadowLevel) renderLevel)) {
				offscreenRenderActive = true;
				activeCamera = cameraState.camera();
				activeLightTexture = sessionState.lightTexture;
				RenderSystem.backupProjectionMatrix();
				if (request.topDownMap()) {
					client.smartCull = false;
					if (!sessionState.topDownRendererPrimed) {
						levelRenderer.allChanged();
						sessionState.topDownRendererPrimed = true;
					}
				}
				((MinecraftMainRenderTargetAccessor) client).lg2$setMainRenderTarget(renderTarget);
				RendererBotShadowWorldManager.updateCameraContext(request.sessionId(), cameraState.camera());
				renderOffscreenWorld(
						client,
						renderLevel,
						levelRenderer,
						session.featureRenderDispatcher(),
						session.viewDistance(),
						sessionState,
						request,
						cameraState,
						renderTarget
				);
				RendererBotShadowWorldManager.markFrameRendered(request.sessionId());
				renderTargetConsumer.accept(renderTarget);
				return true;
			} catch (Throwable throwable) {
				Lg2.LOGGER.warn("Renderer bot offscreen render failed for {}", request, throwable);
				return false;
			} finally {
				((MinecraftMainRenderTargetAccessor) client).lg2$setMainRenderTarget(previousRenderTarget);
				entityDispatcher.camera = previousEntityCamera;
				entityDispatcher.crosshairPickEntity = previousPickedEntity;
				blockDispatcher.lg2$setCameraPos(previousBlockCamera);
				Entity.setViewScale(previousEntityViewScale);
				RenderSystem.setGlobalSettingsUniform(previousGlobalSettings);
				RenderSystem.setShaderFog(previousFog);
				RenderSystem.setShaderLights(previousLights);
				client.smartCull = previousSmartCull;
				RenderSystem.restoreProjectionMatrix();
				offscreenRenderActive = false;
				activeCamera = null;
				activeLightTexture = null;
				sessionState.renderInProgress = false;
			}
		}
	}

	private static void renderOffscreenWorld(
			Minecraft client,
			ClientLevel renderLevel,
			LevelRenderer levelRenderer,
			net.minecraft.client.renderer.feature.FeatureRenderDispatcher featureRenderDispatcher,
			int shadowViewDistance,
			OffscreenSessionState sessionState,
			RenderRequest request,
			CameraState cameraState,
			TextureTarget renderTarget
	) {
		TopDownEnvironment topDownEnvironment = request.topDownMap()
				? beginTopDownEnvironment(renderLevel)
				: null;
		try {
			GameRendererRenderLevelInvoker gameRendererAccessor = (GameRendererRenderLevelInvoker) client.gameRenderer;
			FogRenderer fogRenderer = sessionState.fogRenderer;
			float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			if (!sessionState.cameraPrimed || request.topDownMap()) {
				cameraState.camera().attributeProbe().reset();
				cameraState.camera().attributeProbe().tick(renderLevel, cameraState.camera().position());
				sessionState.cameraPrimed = true;
			}
			sessionState.lightTexture.updateLightTexture(partialTick);
			applyLevelRenderCameraState(levelRenderer, cameraState.camera(), partialTick);
			Matrix4f projectionMatrix = request.topDownMap()
					? topDownProjectionMatrix(renderLevel, request)
					: new Matrix4f().perspective((float) Math.toRadians(request.fovDegrees()),
							request.renderWidth() / (float) request.renderHeight(), 0.05F, Math.max(32, shadowViewDistance * 16) * 4.0F);
			Matrix4f cullingMatrix = request.topDownMap()
					? new Matrix4f(projectionMatrix)
					: new Matrix4f(projectionMatrix);
			Matrix4f viewMatrix = new Matrix4f().rotation(new Quaternionf(cameraState.camera().rotation()).conjugate());
			Vector4f fogColor = fogRenderer.setupFog(
					cameraState.camera(),
					// Each shadow session has its own server-authoritative radius.  Never
					// use the renderer bot's global Options value here: a background map
					// tile may have a different radius and changing that option rebuilds
					// every active world renderer, including a live drone stream.
					Math.max(2, shadowViewDistance) * 16,
					client.getDeltaTracker(),
					0.0F,
					renderLevel
			);
			GpuBufferSlice projectionMatrixSlice = sessionState.projectionBuffer.getBuffer(projectionMatrix);
			GpuBufferSlice fogBuffer = fogRenderer.getBuffer(request.topDownMap() ? FogRenderer.FogMode.NONE : FogRenderer.FogMode.WORLD);
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.clearColorAndDepthTextures(renderTarget.getColorTexture(), 0, renderTarget.getDepthTexture(), 1.0D);

			RenderSystem.setProjectionMatrix(projectionMatrixSlice, request.topDownMap() ? ProjectionType.ORTHOGRAPHIC : ProjectionType.PERSPECTIVE);
			sessionState.globalSettings.update(
					request.renderWidth(),
					request.renderHeight(),
					client.options.glintStrength().get(),
					renderLevel.getGameTime(),
					client.getDeltaTracker(),
					client.options.getMenuBackgroundBlurriness(),
					cameraState.camera(),
					!request.topDownMap() && client.options.textureFiltering().get() == TextureFilteringMethod.RGSS
			);
			levelRenderer.renderLevel(
					GraphicsResourceAllocator.UNPOOLED,
					client.getDeltaTracker(),
					false,
					cameraState.camera(),
					viewMatrix,
					projectionMatrix,
					cullingMatrix,
					fogBuffer,
					fogColor,
					!request.topDownMap()
			);
			featureRenderDispatcher.endFrame();
			levelRenderer.endFrame();
			fogRenderer.endFrame();
		} finally {
			if (topDownEnvironment != null) {
				topDownEnvironment.restore(client, renderLevel);
			}
		}
	}

	private static Matrix4f topDownProjectionMatrix(ClientLevel level, RenderRequest request) {
		float halfWidth = (float) Math.max(0.5D, request.orthographicWidthBlocks() * 0.5D);
		float halfHeight = (float) Math.max(0.5D, request.orthographicHeightBlocks() * 0.5D);
		double cameraY = clampTopDownCameraY(level, request.y());
		float depth = (float) Math.max(64.0D, cameraY - level.getMinY() + TOP_DOWN_CAMERA_HEADROOM_BLOCKS);
		return new Matrix4f().setOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, 0.01F, depth);
	}

	private static void applyLevelRenderCameraState(LevelRenderer levelRenderer, Camera camera, float partialTick) {
		if (levelRenderer == null || camera == null) {
			return;
		}
		LevelRenderState levelRenderState = ((LevelRendererRenderStateAccessor) levelRenderer).lg2$getLevelRenderState();
		if (levelRenderState == null || levelRenderState.cameraRenderState == null) {
			return;
		}
		CameraRenderState cameraRenderState = levelRenderState.cameraRenderState;
		Entity cameraEntity = camera.entity();
		cameraRenderState.initialized = true;
		cameraRenderState.pos = camera.position();
		cameraRenderState.blockPos = camera.blockPosition();
		cameraRenderState.entityPos = cameraEntity != null ? cameraEntity.getPosition(partialTick) : camera.position();
		cameraRenderState.orientation = new Quaternionf(camera.rotation());
	}

	private static CameraState resolveCameraState(
			Minecraft client,
			ClientLevel renderLevel,
			RenderRequest request,
			OffscreenSessionState sessionState,
			Entity followTarget
	) {
		float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		if (renderLevel == null) {
			return null;
		}
		if (request.followEntityUuid() != null) {
			if (followTarget == null) {
				return null;
			}
			Camera camera = sessionState.followCamera;
			camera.setup(renderLevel, followTarget, false, false, partialTick);
			((CameraPositionInvoker) camera).lg2$setPosition(followTarget.getEyePosition(partialTick));
			DroneCameraTilt.applyBank(camera, request.cameraBankRadians());
			return new CameraState(camera);
		}

		Vec3 eyePosition = request.topDownMap()
				? new Vec3(
						request.x(),
						clampTopDownCameraY(renderLevel, request.y()),
						request.z()
				)
				: request.absoluteCameraPosition()
				? new Vec3(request.x(), request.y(), request.z())
				: new Vec3(request.x(), request.y() + STATIC_CAMERA_EYE_HEIGHT, request.z());
		Marker anchor = sessionState.ensureStaticAnchor(renderLevel);
		anchor.snapTo(eyePosition, request.yaw(), request.pitch());
		anchor.setOldPosAndRot(eyePosition, request.yaw(), request.pitch());
		anchor.noPhysics = true;
		anchor.setNoGravity(true);
		Camera camera = sessionState.staticCamera;
		camera.setup(renderLevel, anchor, false, false, partialTick);
		DroneCameraTilt.applyBank(camera, request.cameraBankRadians());
		return new CameraState(camera);
	}

	private static double clampTopDownCameraY(ClientLevel level, double requestedY) {
		if (level == null || !Double.isFinite(requestedY)) {
			return requestedY;
		}
		double minimumY = level.getMinY() + 1.0D;
		double maximumY = level.getMaxY() + TOP_DOWN_CAMERA_HEADROOM_BLOCKS;
		return Mth.clamp(requestedY, minimumY, maximumY);
	}

	private static Entity resolveFollowTarget(ClientLevel renderLevel, UUID followEntityUuid) {
		if (renderLevel == null || followEntityUuid == null) {
			return null;
		}
		Entity followTarget = renderLevel.getPlayerByUUID(followEntityUuid);
		if (followTarget != null) {
			return followTarget;
		}
		for (Entity entity : renderLevel.entitiesForRendering()) {
			if (followEntityUuid.equals(entity.getUUID())) {
				return entity;
			}
		}
		return null;
	}

	private static TextureTarget ensureRenderTarget(OffscreenSessionState sessionState, int width, int height) {
		if (sessionState == null) {
			return null;
		}
		int safeWidth = Math.max(1, width);
		int safeHeight = Math.max(1, height);
		if (sessionState.renderTarget == null
				|| sessionState.renderWidth != safeWidth
				|| sessionState.renderHeight != safeHeight) {
			if (sessionState.renderTarget != null) {
				sessionState.renderTarget.destroyBuffers();
			}
			sessionState.renderTarget = new TextureTarget("lg2_renderer_bot_offscreen", safeWidth, safeHeight, true);
			sessionState.renderWidth = safeWidth;
			sessionState.renderHeight = safeHeight;
		}
		sessionState.renderInProgress = true;
		return sessionState.renderTarget;
	}

	private static void closeSessionState(OffscreenSessionState state) {
		if (state == null) {
			return;
		}
		if (state.renderTarget != null) {
			state.renderTarget.destroyBuffers();
			state.renderTarget = null;
		}
		state.staticAnchor = null;
		state.lightTexture.close();
		state.fogRenderer.close();
		state.projectionBuffer.close();
		state.globalSettings.close();
	}

	/**
	 * This is deliberately only a start gate, not a completeness test.  A static
	 * camera receives a directional set of chunks, while its visible-section
	 * renderer is the authority on when that set has finished compiling.  Waiting
	 * here for a square around the camera can therefore ask for chunks that were
	 * intentionally not sent (behind or far beside the camera) and prevent the
	 * first render from ever starting.
	 */
	private static boolean isWorldReady(ClientLevel renderLevel, CameraState cameraState) {
		if (renderLevel == null || cameraState == null || cameraState.camera() == null) {
			return false;
		}
		Vec3 position = cameraState.camera().position();
		int centerChunkX = SectionPos.blockToSectionCoord(Mth.floor(position.x));
		int centerChunkZ = SectionPos.blockToSectionCoord(Mth.floor(position.z));
		LevelChunk centerChunk = renderLevel.getChunkSource().getChunk(centerChunkX, centerChunkZ, ChunkStatus.FULL, false);
		return centerChunk != null;
	}

	public record RenderRequest(
			UUID sessionId,
			String dimensionId,
			UUID followEntityUuid,
			double x,
			double y,
			double z,
			float yaw,
			float pitch,
			int fovDegrees,
			int renderWidth,
			int renderHeight,
			boolean absoluteCameraPosition,
			boolean topDownMap,
			double orthographicWidthBlocks,
			double orthographicHeightBlocks,
			float cameraBankRadians,
			boolean hideCameraCollisionBlock
	) {
	}

	private record TopDownEnvironment(
			long gameTime,
			long dayTime,
			boolean tickDayTime,
			boolean raining,
			float rainLevel,
			float thunderLevel
	) {
		private void restore(Minecraft client, ClientLevel level) {
			if (level != null) {
				level.setTimeFromServer(this.gameTime, this.dayTime, this.tickDayTime);
				level.getLevelData().setRaining(this.raining);
				level.setRainLevel(this.rainLevel);
				level.setThunderLevel(this.thunderLevel);
				level.environmentAttributes().invalidateTickCache();
			}
		}
	}

	private static TopDownEnvironment beginTopDownEnvironment(ClientLevel level) {
		TopDownEnvironment previous = new TopDownEnvironment(
				level != null ? level.getGameTime() : 0L,
				level != null ? level.getDayTime() : 6000L,
				level instanceof RendererBotShadowLevel scene && scene.ticksDayTime(),
				level != null && level.getLevelData().isRaining(),
				level != null ? level.getRainLevel(1.0F) : 0.0F,
				level != null ? level.getThunderLevel(1.0F) : 0.0F
		);
		if (level != null) {
			// GlobalSettings uses the level game time for animated shader state.
			// A constant value makes every map tile use the same terrain frame and
			// also removes time-of-day lighting from the capture.
			level.setTimeFromServer(MAP_RENDER_GAME_TIME, 6000L, false);
			level.getLevelData().setRaining(false);
			level.setRainLevel(0.0F);
			level.setThunderLevel(0.0F);
			level.environmentAttributes().invalidateTickCache();
		}
		return previous;
	}

	private record CameraState(Camera camera) {
	}

	private static final class OffscreenSessionState {
		private final LightTexture lightTexture = new LightTexture(Minecraft.getInstance().gameRenderer, Minecraft.getInstance());
		private final FogRenderer fogRenderer = new FogRenderer();
		private final PerspectiveProjectionMatrixBuffer projectionBuffer = new PerspectiveProjectionMatrixBuffer("Lostglade camera projection");
		private final GlobalSettingsUniform globalSettings = new GlobalSettingsUniform();
		private boolean cameraPrimed;
		private TextureTarget renderTarget;
		private int renderWidth;
		private int renderHeight;
		private int appliedRendererWidth = Integer.MIN_VALUE;
		private int appliedRendererHeight = Integer.MIN_VALUE;
		private Marker staticAnchor;
		private final Camera staticCamera = new Camera();
		private final Camera followCamera = new Camera();
		private boolean renderInProgress;
		private boolean topDownRendererPrimed;

		private Marker ensureStaticAnchor(ClientLevel level) {
			if (this.staticAnchor == null || this.staticAnchor.level() != level) {
				this.staticAnchor = new Marker(EntityType.MARKER, level);
				this.staticAnchor.noPhysics = true;
				this.staticAnchor.setNoGravity(true);
			}
			return this.staticAnchor;
		}
	}
}
