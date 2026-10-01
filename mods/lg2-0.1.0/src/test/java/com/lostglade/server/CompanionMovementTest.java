package com.lostglade.server;

import net.minecraft.world.phys.Vec3;

public final class CompanionMovementTest {
	public static void main(String[] args) {
		for (double speed : new double[]{0.1D, 0.23D, 0.345D, 1.0D}) {
			for (int angle = 0; angle < 360; angle += 15) {
				double radians = Math.toRadians(angle);
				Vec3 velocity = new Vec3(Math.cos(radians) * speed, Math.sin(radians) * speed * 0.3D, Math.sin(radians) * speed);
				convergesWithoutOrbiting(new Vec3(40.0D, 6.0D, 0.0D), Vec3.ZERO, velocity, speed);
				convergesWithoutOrbiting(new Vec3(0.8D, 0.0D, 0.0D), Vec3.ZERO, velocity, speed);
			}
		}
		modeTransitionsKeepMomentum();
		arrivalBrakesBeforePassingOwner();
		wanderStaysInField();
		turnsUseShortestSmoothArc();
		System.out.println("Companion movement: arrival, reversal, smooth mode changes, field bounds and turning passed");
	}

	private static void convergesWithoutOrbiting(Vec3 position, Vec3 target, Vec3 velocity, double speed) {
		double acceleration = speed > 0.5D ? 0.065D : 0.02D;
		for (int tick = 0; tick < 1600; tick++) {
			Vec3 next = CompanionMovement.steer(velocity, target.subtract(position), speed, acceleration, true);
			check(next.subtract(velocity).length() <= acceleration + 1.0E-8D, "instant velocity change");
			velocity = next;
			position = position.add(next);
		}
		check(position.distanceToSqr(target) < 0.005D * 0.005D, "failed arrival: " + position + ", speed=" + speed);
		check(velocity.length() < 0.001D, "kept orbiting the destination");
	}

	private static void modeTransitionsKeepMomentum() {
		Vec3 velocity = Vec3.ZERO;
		for (int tick = 0; tick < 100; tick++) velocity = CompanionMovement.steer(velocity, new Vec3(100.0D, 0.0D, 0.0D), 1.0D, 0.065D, true);
		check(velocity.x > 0.99D, "never reached delivery speed");
		Vec3 afterPickup = CompanionMovement.steer(velocity, new Vec3(-100.0D, 0.0D, 0.0D), 1.0D, 0.065D, true);
		check(afterPickup.x > 0.9D && Math.abs(afterPickup.z) < 1.0E-8D, "pickup reset velocity or created a circular detour");
		Vec3 wandering = CompanionMovement.steer(velocity, new Vec3(5.0D, 0.0D, 0.0D), 0.1D, 0.02D, true);
		check(wandering.x > 0.97D, "mode switch abruptly clamped speed");
		for (int tick = 0; tick < 100; tick++) velocity = CompanionMovement.steer(velocity, Vec3.ZERO, 0.1D, 0.02D, true);
		check(velocity.length() < 0.001D, "idle braking never settled");
	}

	private static void wanderStaysInField() {
		Vec3 center = Vec3.ZERO;
		for (double inner : new double[]{0.0D, 1.35D, 2.0D}) {
			for (int angle = 0; angle < 360; angle += 5) {
				double radians = Math.toRadians(angle);
				Vec3 position = new Vec3(3.7D, 0.0D, 0.0D);
				Vec3 step = new Vec3(Math.cos(radians), Math.sin(radians), 0.2D).scale(3.0D);
				Vec3 limited = CompanionMovement.constrainStep(center, position, step, inner, 3.75D);
				check(CompanionMovement.withinShell(center, position.add(limited), inner, 3.75D), "wander escaped outer radius");
				check(!CompanionMovement.crossesInner(center, position, position.add(limited), inner), "wander crossed the inner field");
			}
		}
		Vec3 escaped = CompanionMovement.constrainStep(center, new Vec3(0.5D, 0.0D, 0.0D), new Vec3(1.0D, 0.0D, 0.0D), 1.35D, 3.75D);
		check(escaped.x > 0.99D, "could not leave inner field after delivery");
		Vec3 edge = new Vec3(0.0D, 0.0D, -5.0D);
		for (int tick = 0; tick < 1000; tick++) {
			Vec3 step = CompanionMovement.constrainStep(center, edge, new Vec3(0.02D, 0.0D, -0.02D), 1.5D, 5.0D);
			edge = edge.add(step);
			check(edge.lengthSqr() <= 25.0D, "rounding switched wandering into return at the boundary");
		}
	}

	private static void arrivalBrakesBeforePassingOwner() {
		Vec3 position = new Vec3(40.0D, 0.0D, 0.0D);
		Vec3 velocity = new Vec3(-1.0D, 0.0D, 0.0D);
		for (int tick = 0; tick < 150; tick++) {
			velocity = CompanionMovement.steer(velocity, position.scale(-1.0D), 1.0D, 0.065D, true);
			position = position.add(velocity);
			if (position.length() <= 1.35D) {
				check(velocity.length() < 0.5D, "arrived at owner too fast and would circle past the calm field");
				return;
			}
		}
		throw new AssertionError("delivery did not reach owner in time");
	}

	private static void turnsUseShortestSmoothArc() {
		check(CompanionMovement.turn(179.0F, -179.0F) > 179.0F, "yaw turned the long way around wraparound");
		float yaw = 0.0F;
		for (int tick = 0; tick < 80; tick++) {
			float next = CompanionMovement.turn(yaw, 150.0F);
			check(Math.abs(next - yaw) <= 15.001F, "instant model turn");
			yaw = next;
		}
		check(Math.abs(yaw - 150.0F) < 0.01F, "model never faced its movement");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
