package com.lostglade.client.maprender;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;

/** Thread-scoped routing for vanilla helpers used by one isolated map scene. */
public final class YandexMapRenderContext implements AutoCloseable {
	private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();
	private final State previous;

	private YandexMapRenderContext(State state) {
		this.previous = ACTIVE.get();
		ACTIVE.set(state);
	}

	public static YandexMapRenderContext enterWorld(YandexMapRenderWorld world) {
		return new YandexMapRenderContext(new State(world, null, null, null));
	}

	public static YandexMapRenderContext enterRender(
			YandexMapRenderWorld world,
			Camera camera,
			LightTexture lightTexture,
			Matrix4f projection
	) {
		return new YandexMapRenderContext(new State(world, camera, lightTexture, projection == null ? null : new Matrix4f(projection)));
	}

	public static YandexMapRenderWorld level() {
		State state = ACTIVE.get();
		return state == null ? null : state.level();
	}

	public static Camera camera() {
		State state = ACTIVE.get();
		return state == null ? null : state.camera();
	}

	public static LightTexture lightTexture() {
		State state = ACTIVE.get();
		return state == null ? null : state.lightTexture();
	}

	public static Matrix4f projection() {
		State state = ACTIVE.get();
		return state == null || state.projection() == null ? null : new Matrix4f(state.projection());
	}

	public static boolean active() {
		return ACTIVE.get() != null;
	}

	@Override
	public void close() {
		if (this.previous == null) {
			ACTIVE.remove();
		} else {
			ACTIVE.set(this.previous);
		}
	}

	private record State(
			YandexMapRenderWorld level,
			Camera camera,
			LightTexture lightTexture,
			Matrix4f projection
	) {
	}
}
