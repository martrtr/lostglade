package com.lostglade.client;

import com.lostglade.mixin.client.DisplayCameraStateAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Display;

/** Materialize metadata-backed visuals without ticking or advancing a captured world. */
public final class FrozenSceneDisplayState {
	private FrozenSceneDisplayState() { }

	public static void prepare(ClientLevel level) {
		for (var entity : level.entitiesForRendering()) {
			if (!(entity instanceof Display display)) continue;
			prepare(display);
		}
	}

	public static void prepare(Display display) {
		prepare(display, (DisplayCameraStateAccessor) display);
	}

	static void prepare(Display display, DisplayCameraStateAccessor state) {
		if (display.renderState() != null && !state.lg2$needsRenderStateUpdate()) return;
		// Vanilla normally performs this inside Display.tick(). Frozen photos,
		// historical video frames and map snapshots deliberately never tick entities.
		state.lg2$setRenderState(state.lg2$createFreshRenderState());
		state.lg2$updateRenderSubState(false, 1.0F);
		state.lg2$setNeedsRenderStateUpdate(false);
	}
}
