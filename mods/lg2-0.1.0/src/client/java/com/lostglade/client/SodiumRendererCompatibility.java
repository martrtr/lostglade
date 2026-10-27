package com.lostglade.client;

import com.lostglade.client.maprender.YandexMapRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.LevelRenderer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Optional Sodium 0.8 bridge, with independent terrain queues for each private LevelRenderer. */
public final class SodiumRendererCompatibility {
	private static Api api;
	private static boolean inspected;
	private static String failure;
	private static int frameDepth;

	private SodiumRendererCompatibility() { }

	public static String compatibilityReason() {
		if (!FabricLoader.getInstance().isModLoaded("sodium")) return null;
		if (!inspected) {
			inspected = true;
			try { api = new Api(); }
			catch (ReflectiveOperationException | LinkageError exception) { failure = "unsupported-sodium-api"; }
		}
		return failure;
	}

	public static Object worldRenderer(LevelRenderer renderer) {
		if (renderer == null || !FabricLoader.getInstance().isModLoaded("sodium") || compatibilityReason() != null) return null;
		try { return api.getRenderer.invoke(renderer); }
		catch (ReflectiveOperationException exception) { throw new IllegalStateException("Sodium scene renderer unavailable", exception); }
	}

	/** Null means vanilla. A failed optional backend remains unsettled, never a partial capture. */
	public static Readiness readiness(LevelRenderer renderer) {
		if (!FabricLoader.getInstance().isModLoaded("sodium")) return null;
		if (compatibilityReason() != null) return new Readiness(false, 0, 1, 0);
		try {
			Object world = api.getRenderer.invoke(renderer);
			Object manager = api.manager.get(world);
			if (manager == null) return new Readiness(false, 0, 1, 0);
			Object builder = api.getBuilder.invoke(manager);
			int compile = (int) api.scheduled.invoke(builder) + (int) api.busy.invoke(builder);
			for (Object queue : ((Map<?, ?>) api.tasks.get(manager)).values()) compile += ((Collection<?>) queue).size();
			int uploads = ((Collection<?>) api.results.get(manager)).size();
			int visible = (int) api.visible.invoke(world);
			boolean settled = !(boolean) api.needsUpdate.invoke(manager) && compile == 0 && uploads == 0;
			return new Readiness(settled, visible, compile, uploads);
		} catch (ReflectiveOperationException | RuntimeException exception) { return new Readiness(false, 0, 1, 0); }
	}

	public static Object sceneFogParameters() {
		if (compatibilityReason() != null || api == null) throw new IllegalStateException("Sodium compatibility unavailable");
		try {
			return YandexMapRenderContext.active() ? api.noFog : api.fog.invoke(RendererSceneCompatibility.fog());
		} catch (ReflectiveOperationException exception) { throw new IllegalStateException("Sodium scene fog unavailable", exception); }
	}

	public static AutoCloseable completeFrames() {
		if (!FabricLoader.getInstance().isModLoaded("sodium")) return () -> { };
		if (compatibilityReason() != null) throw new IllegalStateException(failure);
		if (frameDepth == 0) api.completeFrames.accept(true);
		frameDepth++;
		return () -> { if (--frameDepth == 0) api.completeFrames.accept(false); };
	}

	public record Readiness(boolean settled, int visible, int compileQueue, int uploadQueue) { }

	private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
		Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
	}

	private static final class Api {
		final Method getRenderer, visible, getBuilder, needsUpdate, scheduled, busy, fog;
		final Field manager, results, tasks;
		final Object noFog;
		final Consumer<Boolean> completeFrames;
		@SuppressWarnings("unchecked") Api() throws ReflectiveOperationException {
			Class<?> extension = Class.forName("net.caffeinemc.mods.sodium.client.world.LevelRendererExtension");
			getRenderer = extension.getMethod("sodium$getWorldRenderer");
			Class<?> world = getRenderer.getReturnType();
			visible = world.getMethod("getVisibleChunkCount");
			manager = field(world, "renderSectionManager");
			getBuilder = manager.getType().getMethod("getBuilder");
			needsUpdate = manager.getType().getMethod("needsUpdate");
			results = field(manager.getType(), "buildResults");
			tasks = field(manager.getType(), "taskLists");
			scheduled = getBuilder.getReturnType().getMethod("getScheduledJobCount");
			busy = getBuilder.getReturnType().getMethod("getBusyThreadCount");
			fog = Class.forName("net.caffeinemc.mods.sodium.client.util.FogStorage").getMethod("sodium$getFogParameters");
			noFog = fog.getReturnType().getField("NONE").get(null);
			var provider = (Function<String, Consumer<Boolean>>) Class.forName("net.caffeinemc.mods.sodium.client.util.FlawlessFrames")
					.getMethod("getProvider").invoke(null);
			completeFrames = provider.apply("Lostglade private camera scene");
		}
	}
}
