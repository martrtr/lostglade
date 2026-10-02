package com.lostglade.server;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** One inertial steering model for companion travel and mode transitions. */
final class CompanionMovement {
	private static final double RESPONSE = 0.18D;

	private CompanionMovement() {
	}

	static Vec3 steer(Vec3 velocity, Vec3 offset, double speed, double acceleration, boolean arrive) {
		double distance = offset.length();
		double desiredSpeed = arrive ? Math.min(speed, distance * 0.3D) : speed;
		if (arrive) {
			// Start braking before inertia can carry the companion past an item or its owner.
			desiredSpeed = Math.min(desiredSpeed, Math.sqrt(2.0D * acceleration * distance) * 0.65D);
		}
		Vec3 desired = distance < 1.0E-6D ? Vec3.ZERO : offset.scale(desiredSpeed / distance);
		Vec3 change = desired.subtract(velocity).scale(RESPONSE);
		if (change.lengthSqr() > acceleration * acceleration) change = change.normalize().scale(acceleration);
		Vec3 result = velocity.add(change);
		return result.lengthSqr() < 1.0E-8D ? Vec3.ZERO : result;
	}

	static float turn(float current, float wanted) {
		return current + Mth.clamp(Mth.wrapDegrees(wanted - current) * 0.3F, -15.0F, 15.0F);
	}

	static Vec3 waypoint(Path path, Entity entity, boolean ground) {
		if (path == null) return null;
		while (!path.isDone()) {
			Vec3 next = path.getNextEntityPos(entity);
			Vec3 difference = next.subtract(entity.position());
			double distanceSqr = ground ? difference.horizontalDistanceSqr() : difference.lengthSqr();
			if (distanceSqr > 0.35D * 0.35D || (ground && Math.abs(difference.y) > 0.65D)) return next;
			path.advance();
		}
		return null;
	}

	static void skipRoundedStart(Path path, Entity entity) {
		if (path != null && path.notStarted() && path.getNodeCount() > 1
				&& path.getEntityPosAtNode(entity, 0).distanceToSqr(entity.position()) <= 1.0D) path.advance();
	}

	static boolean withinShell(Vec3 center, Vec3 position, double inner, double outer) {
		double distance = position.distanceToSqr(center);
		return distance >= inner * inner - 1.0E-6D && distance <= outer * outer + 1.0E-6D;
	}

	static boolean crossesInner(Vec3 center, Vec3 start, Vec3 end, double inner) {
		Vec3 offset = start.subtract(center);
		Vec3 segment = end.subtract(start);
		double projection = segment.lengthSqr() < 1.0E-8D ? 0.0D
				: Mth.clamp(-offset.dot(segment) / segment.lengthSqr(), 0.0D, 1.0D);
		return offset.add(segment.scale(projection)).lengthSqr() < inner * inner - 1.0E-6D;
	}

	static Vec3 constrainStep(Vec3 center, Vec3 position, Vec3 velocity, double inner, double outer) {
		boolean avoidInner = position.distanceToSqr(center) >= inner * inner;
		if (allowedStep(center, position, velocity, inner, outer, avoidInner)) return velocity;
		double low = 0.0D, high = 1.0D;
		for (int i = 0; i < 24; i++) {
			double middle = (low + high) * 0.5D;
			if (allowedStep(center, position, velocity.scale(middle), inner, outer, avoidInner)) low = middle;
			else high = middle;
		}
		return velocity.scale(low);
	}

	private static boolean allowedStep(Vec3 center, Vec3 start, Vec3 step, double inner, double outer, boolean avoidInner) {
		Vec3 end = start.add(step);
		return end.distanceToSqr(center) <= outer * outer
				&& (!avoidInner || !crossesInner(center, start, end, inner));
	}
}
