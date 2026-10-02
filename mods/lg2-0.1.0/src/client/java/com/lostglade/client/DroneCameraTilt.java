package com.lostglade.client;

import com.lostglade.mixin.client.CameraPositionInvoker;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-only FPV bank for drone cameras.
 *
 * <p>The controlled operator is represented locally by the player with the drone root as a
 * passenger.  A screen feed follows the tiny interaction entity used as the drone camera
 * anchor.  Those two shapes let us identify a drone camera without touching normal player
 * cameras, including elytra flight.</p>
 */
public final class DroneCameraTilt {
	private static final float MAX_BANK_DEGREES = 32.0F;
	private static final float BANK_RESPONSE_PER_SECOND = 8.0F;
	private static final float STOP_EPSILON = 0.0025F;
	private static final Map<UUID, RollState> ROLL_STATES = new HashMap<>();

	private DroneCameraTilt() {
	}

	/**
	 * Returns the local-space Z rotation which must be appended to the vanilla camera rotation.
	 */
	public static float bankRadians(Entity cameraEntity) {
		if (!isDroneCameraEntity(cameraEntity)) {
			return 0.0F;
		}
		UUID id = cameraEntity.getUUID();
		if (id == null) {
			return 0.0F;
		}
		RollState state = ROLL_STATES.computeIfAbsent(id, ignored -> new RollState());
		long now = System.nanoTime();
		float deltaSeconds = state.lastUpdateNanos == 0L
				? 1.0F / 60.0F
				: Mth.clamp((now - state.lastUpdateNanos) * 1.0E-9F, 0.0F, 0.25F);
		state.lastUpdateNanos = now;
		float target = targetBankDegrees(cameraEntity);
		if (target == 0.0F) {
			state.bankDegrees = 0.0F;
			return 0.0F;
		}
		float blend = 1.0F - (float) Math.exp(-BANK_RESPONSE_PER_SECOND * deltaSeconds);
		state.bankDegrees += (target - state.bankDegrees) * blend;
		return state.bankDegrees * Mth.DEG_TO_RAD;
	}

	public static void clear() {
		ROLL_STATES.clear();
	}

	/** Applies a known drone bank to any camera, including a static off-screen camera. */
	public static void applyBank(Camera camera, float bankRadians) {
		if (camera == null || bankRadians == 0.0F || !LostgladeClientSettings.isDroneTiltEnabled()) {
			return;
		}
		float scaledBank = bankRadians * LostgladeClientSettings.droneTiltStrength();
		if (scaledBank == 0.0F) return;
		camera.rotation().rotateZ(scaledBank);
		CameraPositionInvoker vectors = (CameraPositionInvoker) camera;
		new org.joml.Vector3f(0.0F, 0.0F, -1.0F).rotate(camera.rotation(), vectors.lg2$getForwards());
		new org.joml.Vector3f(0.0F, 1.0F, 0.0F).rotate(camera.rotation(), vectors.lg2$getUp());
		new org.joml.Vector3f(-1.0F, 0.0F, 0.0F).rotate(camera.rotation(), vectors.lg2$getLeft());
	}

	private static boolean isDroneCameraEntity(Entity entity) {
		return isDroneCameraAnchor(entity) || isControlledDroneOperator(entity);
	}

	private static boolean isDroneCameraAnchor(Entity entity) {
		return entity != null
				&& entity.getType() == EntityType.INTERACTION
				&& entity.getBbWidth() <= 0.02F
				&& entity.getBbHeight() <= 0.02F;
	}

	private static boolean isControlledDroneOperator(Entity entity) {
		if (entity == null || entity.getPassengers().isEmpty()) {
			return false;
		}
		for (Entity passenger : entity.getPassengers()) {
			if (passenger != null
					&& passenger.getType() == EntityType.INTERACTION
					&& passenger.getBbWidth() > 0.02F
					&& passenger.getBbHeight() > 0.02F) {
				return true;
			}
		}
		return false;
	}

	private static float targetBankDegrees(Entity entity) {
		Vec3 look = entity.getLookAngle();
		Vec3 velocity = entity.getDeltaMovement();
		double lookHorizontal = look.x * look.x + look.z * look.z;
		double velocityHorizontal = velocity.x * velocity.x + velocity.z * velocity.z;
		if (lookHorizontal <= STOP_EPSILON * STOP_EPSILON || velocityHorizontal <= STOP_EPSILON * STOP_EPSILON) {
			return 0.0F;
		}
		double cosine = (velocity.x * look.x + velocity.z * look.z) / Math.sqrt(lookHorizontal * velocityHorizontal);
		cosine = Mth.clamp(cosine, -1.0D, 1.0D);
		// The controlled player proxy and the drone's off-screen anchor have opposite local
		// camera bases.  The stream uses the anchor convention; direct FPV uses the proxy one.
		double cross = velocity.x * look.z - velocity.z * look.x;
		double bankSign = isControlledDroneOperator(entity) ? Math.signum(cross) : -Math.signum(cross);
		double turnStrength = Math.sqrt(velocityHorizontal) * Math.acos(Math.abs(cosine)) * 1.25D;
		return (float) Mth.clamp(Math.atan(turnStrength) * bankSign * Mth.RAD_TO_DEG, -MAX_BANK_DEGREES, MAX_BANK_DEGREES);
	}

	private static final class RollState {
		private long lastUpdateNanos;
		private float bankDegrees;
	}
}
