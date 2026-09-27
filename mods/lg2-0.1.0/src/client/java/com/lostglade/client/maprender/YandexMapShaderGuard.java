package com.lostglade.client.maprender;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

/** Conservative optional shader detection without a hard Iris dependency. */
public final class YandexMapShaderGuard {
	private YandexMapShaderGuard() {
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
