package com.lostglade.client;

import com.lostglade.mixin.Lg2MixinPlugin;
import net.fabricmc.loader.api.FabricLoader;

/** Unsupported optional hooks disable contribution, never the player's game. */
public final class RendererOptionalCompatibility {
	private RendererOptionalCompatibility() { }
	public static String blockingReason() {
		String sodium = SodiumRendererCompatibility.compatibilityReason();
		if (sodium != null) return sodium;
		if (FabricLoader.getInstance().isModLoaded("sodium") && !hooks(
				new String[]{"net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", net.minecraft.client.renderer.GameRenderer.class.getName()},
				new String[]{"SodiumCameraWorldMixin", "SodiumCameraFogMixin"})) return "unsupported-sodium-isolation";
		if (FabricLoader.getInstance().isModLoaded("voxy") && !hooks(
				new String[]{"me.cortex.voxy.commonImpl.WorldIdentifier", net.minecraft.client.renderer.LevelRenderer.class.getName()},
				new String[]{"VoxyCameraWorldMixin", "VoxyCameraRendererMixin"})) return "unsupported-voxy-isolation";
		if (FabricLoader.getInstance().isModLoaded("distanthorizons") && !hooks(
				new String[]{"com.seibel.distanthorizons.core.api.internal.ClientApi", "com.seibel.distanthorizons.core.api.internal.SharedApi"},
				new String[]{"DistantHorizonsCameraRenderMixin", "DistantHorizonsCameraChunksMixin"})) return "unsupported-distant-horizons-isolation";
		return null;
	}
	private static boolean hooks(String[] classes, String[] hooks) {
		try {
			for (String name : classes) Class.forName(name, false, RendererOptionalCompatibility.class.getClassLoader());
			for (String name : hooks) if (!Lg2MixinPlugin.compatibilityHookApplied(name)) return false;
			return true;
		} catch (ClassNotFoundException | LinkageError error) { return false; }
	}
}
