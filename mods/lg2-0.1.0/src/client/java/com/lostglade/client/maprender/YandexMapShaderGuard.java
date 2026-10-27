package com.lostglade.client.maprender;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/** Conservative optional shader detection without a hard Iris dependency. */
public final class YandexMapShaderGuard {
	private YandexMapShaderGuard() {
	}

	/** Null means Iris is absent. Use the public API so no Iris internals are required. */
	public static Boolean configuredShadersEnabled() throws ReflectiveOperationException {
		if (!FabricLoader.getInstance().isModLoaded("iris")) return null;
		Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
		Object config = api.getMethod("getConfig").invoke(api.getMethod("getInstance").invoke(null));
		return (boolean) Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig").getMethod("areShadersEnabled").invoke(config);
	}

	public static void setShadersEnabled(boolean enabled) throws ReflectiveOperationException {
		if (!FabricLoader.getInstance().isModLoaded("iris")) return;
		if (Boolean.valueOf(enabled).equals(configuredShadersEnabled())) return;
		Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
		Object config = api.getMethod("getConfig").invoke(api.getMethod("getInstance").invoke(null));
		Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig")
				.getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, enabled);
		if (!enabled && !inspect().compatible()) throw new IllegalStateException("Iris did not disable its shader pipeline");
	}

	public static Compatibility inspect() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return new Compatibility(true, "vanilla-pipeline");
		}
		try {
			Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Method getInstance = irisApiClass.getMethod("getInstance");
			Object api = getInstance.invoke(null);
			Method isShaderPackInUse = irisApiClass.getMethod("isShaderPackInUse");
			boolean shaderActive = Boolean.TRUE.equals(isShaderPackInUse.invoke(api));
			return shaderActive
					? new Compatibility(false, "shader-pack-active")
					: new Compatibility(true, "iris-installed-shaders-off");
		} catch (Throwable throwable) {
			// Unknown Iris state is rejected rather than risking non-canonical tiles.
			return new Compatibility(false, "shader-state-unknown");
		}
	}

	public record Compatibility(boolean compatible, String reason) {
	}
}
