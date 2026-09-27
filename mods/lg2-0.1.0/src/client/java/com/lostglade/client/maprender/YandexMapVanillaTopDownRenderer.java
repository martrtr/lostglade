package com.lostglade.client.maprender;

import com.lostglade.Lg2;
import com.lostglade.mixin.client.BlockEntityCameraStateAccessor;
import com.lostglade.mixin.client.CameraPositionInvoker;
import com.lostglade.mixin.client.LevelRendererRenderStateAccessor;
import com.lostglade.mixin.client.MinecraftMainRenderTargetAccessor;
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
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Vanilla LevelRenderer driven by a fixed orthographic top-down camera.
 *
 * <p>No map-color approximation happens here: terrain, fluids, block entities
 * and ItemDisplay use the same vanilla renderer/resource pipeline as gameplay.
 */
public final class YandexMapVanillaTopDownRenderer implements AutoCloseable {
	private static final float NEAR_PLANE = 0.05F;
	// Keep the same world-to-screen orientation as the original working map renderer:
	// +X goes right and +Z goes down. A 0-degree yaw rotates every independently
	// rendered L0 tile by 180 degrees inside its world-space cell, breaking seams.
	private static final float TOP_DOWN_YAW = 180.0F;
	private static final float TOP_DOWN_PITCH = 90.0F;

	private final YandexMapRenderScene scene;
	private final LightTexture lightTexture;
	private final FogRenderer fogRenderer = new FogRenderer();
	private final PerspectiveProjectionMatrixBuffer projectionBuffer = new PerspectiveProjectionMatrixBuffer("Lostglade Yandex map orthographic projection");
	private final GlobalSettingsUniform globalSettings = new GlobalSettingsUniform();
	private final TextureTarget renderTarget;
	private final Camera camera = new Camera();
	private final Marker cameraAnchor;
	private final Matrix4f projectionMatrix;
	private long lastSettledRevision = Long.MIN_VALUE;
	private int consecutiveSettledChecks;
	private boolean primeFrameRendered;
	private boolean readbackPending;
	private String cullDiagnostics = "not-rendered";
	private boolean closed;

	public YandexMapVanillaTopDownRenderer(YandexMapRenderScene scene) {
		this.scene = Objects.requireNonNull(scene, "scene");
		Minecraft client = Minecraft.getInstance();
		this.lightTexture = new LightTexture(client.gameRenderer, client);
		int pixels = scene.descriptor().renderPixels();
		this.renderTarget = new TextureTarget("lg2_yandex_map_topdown", pixels, pixels, true);
		this.cameraAnchor = new Marker(EntityType.MARKER, scene.level());
		this.cameraAnchor.noPhysics = true;
		this.cameraAnchor.setNoGravity(true);
		this.projectionMatrix = createProjection(scene.level().getHeight());
		positionCamera(0.0F);
	}

	public AdvanceResult advance(Consumer<ValidatedFrame> frameConsumer) {
		ensureOpen();
		Objects.requireNonNull(frameConsumer, "frameConsumer");
		if (this.readbackPending) {
			return new AdvanceResult(State.READBACK_PENDING, this.scene.inspectReadiness(), "readback-pending");
		}
		float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		positionCamera(partialTick);
		this.scene.setCamera(this.camera);
		this.scene.levelRenderer().tick(this.camera);

		if (!renderFrame(false, null, partialTick)) {
			return new AdvanceResult(State.FAILED, this.scene.inspectReadiness(), "prime-render-failed");
		}
		this.primeFrameRendered = true;
		YandexMapRenderScene.RenderReadiness readiness = this.scene.inspectReadiness();
		if (!readiness.settled()) {
			this.consecutiveSettledChecks = 0;
			this.lastSettledRevision = Long.MIN_VALUE;
			return new AdvanceResult(State.WAITING_FOR_VANILLA, readiness, "vanilla-not-settled");
		}
		if (readiness.contentRevision() != this.lastSettledRevision) {
			this.lastSettledRevision = readiness.contentRevision();
			this.consecutiveSettledChecks = 1;
			return new AdvanceResult(State.WAITING_FOR_STABLE_REVISION, readiness, "first-settled-check");
		}
		this.consecutiveSettledChecks++;
		if (this.consecutiveSettledChecks < 2) {
			return new AdvanceResult(State.WAITING_FOR_STABLE_REVISION, readiness, "second-settled-check-required");
		}

		long finalRevision = readiness.contentRevision();
		this.readbackPending = true;
		boolean rendered = renderFrame(true, image -> {
			try {
				if (this.scene.contentRevision() != finalRevision) {
					frameConsumer.accept(new ValidatedFrame(image, false, "scene-revision-changed", null, finalRevision, readiness));
					return;
				}
				YandexMapRenderValidator.ValidationResult validation = YandexMapRenderValidator.validate(
						image,
						this.scene.descriptor().renderPixels()
				);
				frameConsumer.accept(new ValidatedFrame(image, validation.accepted(), validation.reason(), validation, finalRevision, readiness));
			} finally {
				this.readbackPending = false;
			}
		}, partialTick);
		if (!rendered) {
			this.readbackPending = false;
			return new AdvanceResult(State.FAILED, readiness, "final-render-failed");
		}
		return new AdvanceResult(State.READBACK_PENDING, readiness, "final-readback-scheduled");
	}

	public boolean primeFrameRendered() {
		return this.primeFrameRendered;
	}

	public String cullDiagnostics() {
		return this.cullDiagnostics;
	}

	private boolean renderFrame(boolean capture, Consumer<NativeImage> imageConsumer, float partialTick) {
		Minecraft client = Minecraft.getInstance();
		if (client.gameRenderer == null || client.getEntityRenderDispatcher() == null || client.getBlockEntityRenderDispatcher() == null) {
			return false;
		}
		RenderTarget previousRenderTarget = client.getMainRenderTarget();
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

		try {
			this.scene.runWithWorld(() -> {
				try (var ignored = YandexMapRenderContext.enterRender(this.scene.level(), this.camera, this.lightTexture, this.projectionMatrix)) {
					RenderSystem.backupProjectionMatrix();
					((MinecraftMainRenderTargetAccessor) client).lg2$setMainRenderTarget(this.renderTarget);
					entityDispatcher.prepare(this.camera, null);
					blockDispatcher.lg2$setCameraPos(this.camera.position());
					Entity.setViewScale(256.0D);
					client.smartCull = false;
					// Match the final pre-v2 top-down pipeline exactly: refresh the
					// detached-world environment attributes immediately before building
					// the vanilla lightmap (SKY_LIGHT_COLOR / SKY_LIGHT_FACTOR included).
					this.camera.attributeProbe().reset();
					this.camera.attributeProbe().tick(this.scene.level(), this.camera.position());
					this.lightTexture.updateLightTexture(partialTick);
					applyLevelRenderCameraState();

					Matrix4f viewMatrix = new Matrix4f().rotation(new Quaternionf(this.camera.rotation()).conjugate());
					Matrix4f cullingMatrix = new Matrix4f(this.projectionMatrix);
					GpuBufferSlice projectionSlice = this.projectionBuffer.getBuffer(this.projectionMatrix);
					Vector4f fogColor = this.fogRenderer.setupFog(
							this.camera,
							Math.max(2, this.scene.descriptor().viewDistance()) * 16,
							client.getDeltaTracker(),
							0.0F,
							this.scene.level()
					);
					GpuBufferSlice noFog = this.fogRenderer.getBuffer(FogRenderer.FogMode.NONE);
					CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
					encoder.clearColorAndDepthTextures(this.renderTarget.getColorTexture(), 0, this.renderTarget.getDepthTexture(), 1.0D);
					RenderSystem.setProjectionMatrix(projectionSlice, ProjectionType.ORTHOGRAPHIC);
					RenderSystem.setShaderFog(noFog);
					this.globalSettings.update(
							this.scene.descriptor().renderPixels(),
							this.scene.descriptor().renderPixels(),
							client.options.glintStrength().get(),
							this.scene.level().getGameTime(),
							client.getDeltaTracker(),
							client.options.getMenuBackgroundBlurriness(),
							this.camera,
							false
					);
					this.scene.levelRenderer().renderLevel(
							GraphicsResourceAllocator.UNPOOLED,
							client.getDeltaTracker(),
							false,
							this.camera,
							viewMatrix,
							this.projectionMatrix,
							cullingMatrix,
							noFog,
							fogColor,
							false
					);
					updateCullDiagnostics(viewMatrix, cullingMatrix);
					this.scene.featureRenderDispatcher().endFrame();
					this.scene.levelRenderer().endFrame();
					this.fogRenderer.endFrame();
				}
			});
			if (capture) {
				Screenshot.takeScreenshot(this.renderTarget, imageConsumer);
			}
			return true;
		} catch (Throwable throwable) {
			Lg2.LOGGER.warn("Yandex map vanilla top-down render failed for tile {},{}",
					this.scene.descriptor().tileX(), this.scene.descriptor().tileZ(), throwable);
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
		}
	}

	private void updateCullDiagnostics(Matrix4f viewMatrix, Matrix4f cullingMatrix) {
		try {
			Vec3 pos = this.camera.position();
			Frustum runtime = new Frustum(viewMatrix, cullingMatrix);
			runtime.prepare(pos.x(), pos.y(), pos.z());
			runtime = LevelRenderer.offsetFrustum(runtime);
			double tileBlocks = this.scene.descriptor().tileBlocks();
			double minX = Math.multiplyExact(this.scene.descriptor().tileX(), (long) this.scene.descriptor().tileBlocks());
			double minZ = Math.multiplyExact(this.scene.descriptor().tileZ(), (long) this.scene.descriptor().tileBlocks());
			AABB tileBox = new AABB(minX, this.scene.level().getMinY(), minZ, minX + tileBlocks, this.scene.level().getMaxY(), minZ + tileBlocks);
			boolean tileVisible = runtime.isVisible(tileBox);
			int[] runtimeLeaves = {0};
			this.scene.levelRenderer().getSectionOcclusionGraph().getOctree().visitNodes((node, inside, depth, close) -> {
				if (node.getSection() != null) runtimeLeaves[0]++;
			}, runtime, 32);

			Matrix4f broadProjection = new Matrix4f().setOrtho(-2048.0F, 2048.0F, -2048.0F, 2048.0F, NEAR_PLANE, 2048.0F);
			Frustum broad = new Frustum(viewMatrix, broadProjection);
			broad.prepare(pos.x(), pos.y(), pos.z());
			int[] broadLeaves = {0};
			int[] manuallyRuntimeVisible = {0};
			double[] bounds = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
					Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
			Frustum runtimeForLeafTests = runtime;
			this.scene.levelRenderer().getSectionOcclusionGraph().getOctree().visitNodes((node, inside, depth, close) -> {
				if (node.getSection() == null) return;
				broadLeaves[0]++;
				AABB bb = node.getAABB();
				bounds[0] = Math.min(bounds[0], bb.minX); bounds[1] = Math.min(bounds[1], bb.minY); bounds[2] = Math.min(bounds[2], bb.minZ);
				bounds[3] = Math.max(bounds[3], bb.maxX); bounds[4] = Math.max(bounds[4], bb.maxY); bounds[5] = Math.max(bounds[5], bb.maxZ);
				if (runtimeForLeafTests.isVisible(bb)) manuallyRuntimeVisible[0]++;
			}, broad, 32);
			this.cullDiagnostics = "tileVisible=" + tileVisible + ", runtimeLeaves=" + runtimeLeaves[0] + ", broadLeaves=" + broadLeaves[0]
					+ ", manualVisible=" + manuallyRuntimeVisible[0]
					+ ", leafBounds=[" + bounds[0] + "," + bounds[1] + "," + bounds[2] + " -> " + bounds[3] + "," + bounds[4] + "," + bounds[5] + "]"
					+ ", camera=" + pos + ", rotation=" + this.camera.rotation();
		} catch (Throwable throwable) {
			this.cullDiagnostics = "diagnostic-error:" + throwable.getClass().getSimpleName() + ":" + throwable.getMessage();
		}
	}

	private void positionCamera(float partialTick) {
		double tileBlocks = this.scene.descriptor().tileBlocks();
		double centerX = Math.multiplyExact(this.scene.descriptor().tileX(), (long) this.scene.descriptor().tileBlocks()) + tileBlocks * 0.5D;
		double centerZ = Math.multiplyExact(this.scene.descriptor().tileZ(), (long) this.scene.descriptor().tileBlocks()) + tileBlocks * 0.5D;
		double cameraY = this.scene.level().getMaxY() + 128.0D;
		Vec3 position = new Vec3(centerX, cameraY, centerZ);
		this.cameraAnchor.snapTo(position, TOP_DOWN_YAW, TOP_DOWN_PITCH);
		this.cameraAnchor.setOldPosAndRot(position, TOP_DOWN_YAW, TOP_DOWN_PITCH);
		this.camera.setup(this.scene.level(), this.cameraAnchor, false, false, partialTick);
		((CameraPositionInvoker) this.camera).lg2$setPosition(position);
	}

	private Matrix4f createProjection(int worldHeight) {
		float far = Math.max(1024.0F, worldHeight + 512.0F);
		float halfTileBlocks = this.scene.descriptor().tileBlocks() * 0.5F;
		return new Matrix4f().setOrtho(
				-halfTileBlocks,
				halfTileBlocks,
				-halfTileBlocks,
				halfTileBlocks,
				NEAR_PLANE,
				far
		);
	}

	private void applyLevelRenderCameraState() {
		LevelRenderState state = ((LevelRendererRenderStateAccessor) this.scene.levelRenderer()).lg2$getLevelRenderState();
		if (state == null || state.cameraRenderState == null) {
			return;
		}
		CameraRenderState cameraState = state.cameraRenderState;
		cameraState.initialized = true;
		cameraState.pos = this.camera.position();
		cameraState.blockPos = this.camera.blockPosition();
		cameraState.entityPos = this.camera.position();
		cameraState.orientation = new Quaternionf(this.camera.rotation());
	}

	private void ensureOpen() {
		if (this.closed) {
			throw new IllegalStateException("Yandex map renderer is already closed");
		}
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.renderTarget.destroyBuffers();
		this.lightTexture.close();
		this.fogRenderer.close();
		this.projectionBuffer.close();
		this.globalSettings.close();
	}

	public enum State {
		WAITING_FOR_VANILLA,
		WAITING_FOR_STABLE_REVISION,
		READBACK_PENDING,
		FAILED
	}

	public record AdvanceResult(State state, YandexMapRenderScene.RenderReadiness readiness, String reason) {
	}

	/** The consumer owns {@link #image()} and must close it after encoding/copying. */
	public record ValidatedFrame(
			NativeImage image,
			boolean accepted,
			String reason,
			YandexMapRenderValidator.ValidationResult validation,
			long contentRevision,
			YandexMapRenderScene.RenderReadiness readiness
	) {
	}
}
