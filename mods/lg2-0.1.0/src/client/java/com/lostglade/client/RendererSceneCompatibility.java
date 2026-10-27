package com.lostglade.client;

import com.lostglade.client.maprender.YandexMapRenderContext;
import com.lostglade.client.maprender.YandexMapRenderWorld;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.Level;

/** Optional renderers may observe the private scene, never the contributor's live world. */
public final class RendererSceneCompatibility implements AutoCloseable {
	private static final ThreadLocal<FogRenderer> FOG = new ThreadLocal<>();
	private final FogRenderer previous;
	private final AutoCloseable terrainBarrier;

	private RendererSceneCompatibility(FogRenderer fog) {
		previous = FOG.get();
		terrainBarrier = SodiumRendererCompatibility.completeFrames();
		FOG.set(fog);
	}

	public static RendererSceneCompatibility frame(FogRenderer fog) { return new RendererSceneCompatibility(fog); }
	public static FogRenderer fog() { return FOG.get(); }
	public static boolean isScene(Level level) { return level instanceof RendererBotShadowLevel || level instanceof YandexMapRenderWorld; }
	public static boolean active() { return activeLevel() != null; }
	public static ClientLevel activeLevel() {
		var camera = RendererBotSceneContext.level();
		return camera != null ? camera : YandexMapRenderContext.level();
	}
	public static LevelRenderer renderer() {
		ClientLevel level = activeLevel();
		if (level instanceof RendererBotShadowLevel camera) return camera.sceneRenderer();
		if (level instanceof YandexMapRenderWorld map) return map.sceneRenderer();
		return null;
	}

	public static boolean isSceneWrapper(Object wrapper) {
		if (wrapper == null) return false;
		try { return wrapper.getClass().getMethod("getWrappedMcObject").invoke(wrapper) instanceof Level level && isScene(level); }
		catch (ReflectiveOperationException ignored) { return false; }
	}

	@Override public void close() {
		try { terrainBarrier.close(); }
		catch (Exception exception) { throw new IllegalStateException("Could not leave Sodium capture scope", exception); }
		finally { if (previous == null) FOG.remove(); else FOG.set(previous); }
	}
}
