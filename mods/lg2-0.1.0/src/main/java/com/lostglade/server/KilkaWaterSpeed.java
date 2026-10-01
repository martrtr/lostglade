package com.lostglade.server;

final class KilkaWaterSpeed {
	private static final double FLOOR_DRAG = 0.54600006D;
	private KilkaWaterSpeed() { }

	static double swimmingAttribute(double floorSpeed, boolean sprinting, boolean dolphinsGrace) {
		double swimmingDrag = dolphinsGrace ? 0.96D : ((sprinting ? 0.9D : 0.8D) + FLOOR_DRAG) * 0.5D;
		double floorDrag = dolphinsGrace ? 0.96D : FLOOR_DRAG;
		return Math.max(0.0D, 2.0D * (floorSpeed * (1.0D - swimmingDrag) / (1.0D - floorDrag) - 0.01D));
	}
}
