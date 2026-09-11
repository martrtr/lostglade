package com.lostglade.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class DroneScreenLinkPolicyTest {
	private DroneScreenLinkPolicyTest() {
	}

	public static void main(String[] args) throws Exception {
		droneEndpointIdentitySurvivesPositionRefresh();
		poweredLinkedScreensKeepDronesLoadedWithoutLivePreview();
		liveCameraScreenApplyUsesAsyncPreparedPatches();
		droneLiveStreamUsesPoseUpdatesInsteadOfShadowEntityCamera();
		screenHeldDroneKeepsReleasedCameraPitch();
		restartRecoveryKeepsScreenDronesBoundedAndSafe();
		rocketCameraHandoffKeepsExistingLiveStream();
		shadowWorldChangesUseIncrementalPackets();
		cameraAppOffersControlButtonForFreeDrone();
		unloadedDroneControlUsesRememberedLocation();
		System.out.println("Drone screen-link policy checks passed");
	}

	private static void droneEndpointIdentitySurvivesPositionRefresh() {
		UUID droneUuid = UUID.fromString("00000000-0000-0000-0000-000000000123");
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld"));
		BluetoothLinkSystem.Endpoint first = BluetoothLinkSystem.droneEndpoint(dimension, new BlockPos(10, 64, -4), droneUuid);
		BluetoothLinkSystem.Endpoint moved = BluetoothLinkSystem.droneEndpoint(dimension, new BlockPos(42, 72, 11), droneUuid);

		require(first != null && moved != null, "drone endpoint factories must produce endpoints for valid data");
		require(first.equals(moved), "drone endpoint identity must stay tied to UUID so metadata can refresh after movement");
		require(first.hashCode() == moved.hashCode(), "drone endpoint hash must stay stable across metadata refresh");
		require(!first.pos().equals(moved.pos()), "metadata refresh regression test must use a genuinely moved drone position");
	}

	private static void poweredLinkedScreensKeepDronesLoadedWithoutLivePreview() throws Exception {
		Path projectDir = Path.of("").toAbsolutePath();
		String droneSystem = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/DroneSystem.java"));
		String wireConnectivity = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/MonitorScreenWireConnectivity.java"));
		String rendererBot = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));

		require(
				wireConnectivity.contains("collectPoweredLinkedDroneStreams(server, streams);"),
				"active drone screen streams must include powered bluetooth-linked screens even without live preview playback"
		);
		require(
				!wireConnectivity.contains("MEDIA_STATES.isEmpty()"),
				"powered linked drones must not disappear just because no media runtime is open"
		);
		require(
				droneSystem.contains("POWERED_SCREEN_LINKED_DRONES.addAll(activeScreenStreamDrones);"),
				"drone ticket refresh must keep track of powered screen-linked drones"
		);
		require(
				droneSystem.contains("boolean holdWithoutGravity = heldByReleaseGlide || heldByScreenStream || heldByPoweredScreen;"),
				"uncontrolled drones must hover while a powered linked screen is holding them"
		);
		require(
				droneSystem.contains("boolean omnidirectionalChunkLoading = true;"),
				"drone live feeds must keep a stable radial chunk window while the camera rotates"
		);
		require(
				droneSystem.contains("if (root == null || !root.isAlive() || !isDroneHeldByScreen(root))"),
				"screen-held drones must continue receiving chunk tickets even when nobody is actively watching the preview"
		);
		require(
				rendererBot.contains("if (spec.followEntityUuid() == null && spec.cameraPos() != null && !isCameraPlayerLoaded")
						&& rendererBot.contains("center = mergePositionedVirtualCenter(center, botLevel, target);")
						&& rendererBot.contains("spec.cameraPos() != null && spec.followEntityUuid() == null"),
				"follow-entity streams must track their moving center without receiving the static-camera chunk buffer"
		);
	}

	private static void cameraAppOffersControlButtonForFreeDrone() throws Exception {
		Path projectDir = Path.of("").toAbsolutePath();
		String cameraRuntime = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/MonitorCameraRuntime.java"));
		String runtimeModel = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/MonitorScreenRuntimeModel.java"));
		String inputController = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/MonitorScreenInputController.java"));

		require(
				runtimeModel.contains("boolean droneControlVisible"),
				"camera runtime snapshot must carry the drone-control button visibility flag"
		);
		require(
				cameraRuntime.contains("drawCameraDroneControlButton"),
				"camera UI must render a dedicated drone-control button"
		);
		require(
				cameraRuntime.contains("DroneSystem.hasActiveController(selected.sourceUuid())"),
				"camera app must only offer drone takeover when the selected drone has no active operator"
		);
		require(
				cameraRuntime.contains("DroneSystem.tryStartControllingDrone("),
				"camera control button must enter the existing drone control flow"
		);
		require(
				inputController.contains("MonitorCameraRuntime.handleTouch(server, player, component, layout, touchPoint)"),
				"camera app touches must include the player so drone takeover can assign an operator"
		);
	}

	private static void liveCameraScreenApplyUsesAsyncPreparedPatches() throws Exception {
		Path projectDir = Path.of("").toAbsolutePath();
		String mapTransport = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/MonitorScreenMapTransport.java"));

		require(
				mapTransport.contains("TileFramePatch[] tilePatches = resolvePreparedRenderedTilePatches(mediaState, preparedTiles);"),
				"live camera screen apply must use async-prepared tile patches instead of diffing map pixels on the server tick"
		);
		require(
				!mapTransport.contains("buildMapUpdate(mapId, mapData.scale, mapData.locked, mapData.colors, tileFrame)"),
				"live camera screen apply must not scan current map pixels on the server tick"
		);
		require(
				mapTransport.contains("return fullFrameTilePatches(preparedTiles.renderedTiles());"),
				"live camera baseline races should fall back to full-tile patches without recomputing pixel diffs on the server tick"
		);
		require(
				mapTransport.contains("hasCompletePreparedPatchSet"),
				"live camera buffered-frame reapply must fall back to full-tile patches when no async patch set is attached"
		);
	}

	private static void droneLiveStreamUsesPoseUpdatesInsteadOfShadowEntityCamera() throws Exception {
		Path projectDir = Path.of("").toAbsolutePath();
		String payloads = Files.readString(projectDir.resolve("src/main/java/com/lostglade/network/RendererBotPayloads.java"));
		String rendererBot = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));
		String clientCapture = Files.readString(projectDir.resolve("src/client/java/com/lostglade/client/RendererBotClientCapture.java"));
		String offscreenRenderer = Files.readString(projectDir.resolve("src/client/java/com/lostglade/client/RendererBotOffscreenWorldRenderer.java"));
		String droneSystem = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/DroneSystem.java"));

		require(
				payloads.contains("RendererBotLiveStreamPoseS2CPayload"),
				"renderer bot protocol must expose lightweight live-stream pose updates"
		);
		require(
				rendererBot.contains("syncLiveStreamPoseUpdates(server);")
						&& rendererBot.contains("DroneSystem.isDroneCameraAnchor(target.followTarget())")
						&& rendererBot.contains("new RendererBotPayloads.RendererBotLiveStreamPoseS2CPayload("),
				"drone live streams must send current camera-anchor pose without waiting for shadow entity camera sync"
		);
		require(
				clientCapture.contains("updateLiveStreamPose")
						&& clientCapture.contains("pose == null ? payload.followEntityUuid() : null")
						&& clientCapture.contains("pose != null"),
				"renderer client live streams must render pose-updated streams as absolute camera poses"
		);
		require(
				offscreenRenderer.contains("request.absoluteCameraPosition()"),
				"offscreen renderer must support exact camera positions without adding static eye height"
		);
		require(
				!offscreenRenderer.contains("MIN_READY_CHUNK_RADIUS"),
				"pose-updated live drone rendering must not wait for a full 5x5 ready-chunk square before every frame"
		);
		require(
				droneSystem.contains("public static boolean isDroneCameraAnchor(Entity entity)"),
				"drone system must expose camera-anchor detection for renderer live stream pose updates"
		);
	}

	private static void screenHeldDroneKeepsReleasedCameraPitch() throws Exception {
		Path projectDir = Path.of("").toAbsolutePath();
		String droneSystem = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/DroneSystem.java"));
		int heldRotationStart = droneSystem.indexOf("private static void applyUncontrolledHeldFlightRotation");
		int autoAimStart = droneSystem.indexOf("private static Vec3 resolveUncontrolledDroneAutoAimTargetPoint", heldRotationStart);
		String heldRotation = heldRotationStart >= 0 && autoAimStart > heldRotationStart
				? droneSystem.substring(heldRotationStart, autoAimStart)
				: "";
		int autoAimSync = droneSystem.indexOf("boolean autoAimAdjusted = syncUncontrolledDroneAutoAim");
		int heldRotationApply = droneSystem.indexOf("applyUncontrolledHeldFlightRotation(root, state)", autoAimSync);

		require(
				heldRotation.contains("root.setXRot(state.pitch());")
						&& !heldRotation.contains("state.setPitch(0.0F)"),
				"a screen-held drone must preserve the released camera pitch instead of leveling it"
		);
		require(
				autoAimSync >= 0 && heldRotationApply > autoAimSync,
				"screen-held pose preservation must keep auto-aim's target rotation as the higher-priority update"
		);
	}

	private static void restartRecoveryKeepsScreenDronesBoundedAndSafe() throws Exception {
		Path projectDir = Path.of("").toAbsolutePath();
		String droneSystem = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/DroneSystem.java"));

		require(
				droneSystem.contains("DroneRestartRecoveryState restartRecovery = readDroneRestartRecoveryState(root);")
						&& droneSystem.contains("root.setDeltaMovement(Vec3.ZERO);")
						&& droneSystem.contains("else if (holdWithoutGravity) {\n\t\t\t// A stream/powered screen is a hover hold"),
				"a restarted screen-held drone must discard stale Motion and hold still when no drive state is restored"
		);
		require(
				droneSystem.contains("droneRestartRecoveryVelocity(root.position(), state.restartRecoveryTarget())")
						&& droneSystem.contains("finishDroneRestartRecovery(root, state);")
						&& droneSystem.contains("state.isRestartLandingProtected()"),
				"airborne drones must return only to their saved pose and be protected from restart-induced landing damage"
		);
	}

	private static void rocketCameraHandoffKeepsExistingLiveStream() throws Exception {
		Path projectDir = Path.of("").toAbsolutePath();
		String rocket = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/RocketLaunchEventSystem.java"));
		String rendererBot = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));

		int deviceCapture = rocket.indexOf("rocket.captureMountedDevices(level);");
		int anchorCreation = rocket.indexOf("updateRocketDeviceAnchors(level);", deviceCapture);
		int handoff = rocket.indexOf("handoffRocketCameraStreams(level);", anchorCreation);
		int blockRemoval = rocket.indexOf("level.setBlock(block.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);", handoff);
		int handoffMethodStart = rendererBot.indexOf("public static int handoffLiveCameraStreamsToEntity(");
		int handoffMethodEnd = rendererBot.indexOf("private static boolean canReuseLiveStream(", handoffMethodStart);
		String handoffMethod = handoffMethodStart >= 0 && handoffMethodEnd > handoffMethodStart
				? rendererBot.substring(handoffMethodStart, handoffMethodEnd).replaceAll("\\s+", " ")
				: "";
		require(
				deviceCapture >= 0 && anchorCreation > deviceCapture && handoff > anchorCreation && blockRemoval > handoff,
				"rocket cameras must create an anchor and hand off streams before their source blocks are removed"
		);
		require(
				rendererBot.contains("handoffLiveCameraStreamsToEntity(")
						&& handoffMethod.contains("stream.replaceSpec(new LiveStreamSpec(")
						&& handoffMethod.contains("current.renderSessionId(), current.dimension(), null, x,"),
				"rocket camera handoff must retain the live stream/session and clear the obsolete static camera position"
		);
		require(
				!handoffMethod.contains("stopLiveStreamInternal"),
				"rocket camera handoff must not stop and restart an already-playing stream"
		);
	}

	private static void shadowWorldChangesUseIncrementalPackets() throws Exception {
		Path projectDir = Path.of("").toAbsolutePath();
		String dirtyMixin = Files.readString(projectDir.resolve("src/main/java/com/lostglade/mixin/ServerChunkCacheRendererBotShadowDirtyMixin.java"));
		String rendererBot = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/RendererBotCameraSystem.java"));
		String packetCodec = Files.readString(projectDir.resolve("src/main/java/com/lostglade/network/RendererBotShadowPacketCodec.java"));

		require(
				dirtyMixin.contains("RendererBotCameraSystem.mirrorShadowBlockUpdate(level, pos);")
						&& dirtyMixin.contains("RendererBotCameraSystem.mirrorShadowLightUpdate(level, new ChunkPos(pos.x(), pos.z()));"),
				"shadow changes must mirror block and light deltas instead of marking entire chunks dirty"
		);
		require(
				!dirtyMixin.contains("markShadowChunkDirty"),
				"server block/light callbacks must not trigger full shadow chunk snapshots"
		);
		require(
				rendererBot.contains("new ClientboundBlockUpdatePacket(level, pos)")
						&& rendererBot.contains("new ClientboundLightUpdatePacket(pos, level.getChunkSource().getLightEngine(), null, null)"),
				"renderer bot must emit vanilla incremental block and light packets"
		);
		require(
				packetCodec.contains("CLIENTBOUND_BLOCK_UPDATE") && packetCodec.contains("CLIENTBOUND_LIGHT_UPDATE"),
				"shadow packet codec must deliver incremental terrain updates to the renderer client"
		);
	}


	private static void unloadedDroneControlUsesRememberedLocation() throws Exception {
		Path projectDir = Path.of("").toAbsolutePath();
		String droneSystem = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/DroneSystem.java"));
		String bluetoothLinks = Files.readString(projectDir.resolve("src/main/java/com/lostglade/server/BluetoothLinkSystem.java"));

		require(
				droneSystem.contains("DroneLiveFeedState liveFeedState = resolveLiveFeedState(server, droneUuid, dimension, fallbackPos);"),
				"starting control for an unloaded drone must still resolve its last known location"
		);
		require(
				droneSystem.contains("BluetoothLinkSystem.refreshDroneEndpoint(level.getServer(), level.dimension(), currentBlockPos, root.getUUID());"),
				"loaded drone movement must refresh the persisted bluetooth endpoint position"
		);
		require(
				bluetoothLinks.contains("static void refreshDroneEndpoint(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos, UUID droneUuid)"),
				"bluetooth links must expose a metadata refresh path for moved drones"
		);
		require(
				bluetoothLinks.contains("replaceEndpointMetadata(endpoint)"),
				"bluetooth endpoint refresh must replace stored endpoint metadata instead of dropping the link"
		);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
