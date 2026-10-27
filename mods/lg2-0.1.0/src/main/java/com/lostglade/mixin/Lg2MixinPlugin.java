package com.lostglade.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class Lg2MixinPlugin implements IMixinConfigPlugin {
	private static final Set<String> COMPATIBILITY_HOOKS = java.util.concurrent.ConcurrentHashMap.newKeySet();
	public static boolean compatibilityHookApplied(String simpleName) {
		return COMPATIBILITY_HOOKS.contains("com.lostglade.mixin.client." + simpleName);
	}
	private static final String WEBCAM_MIXIN = "com.lostglade.mixin.WebcamServerCartelDisguiseMixin";
	private static final String WEBCAM_NEARBY_PLAYERS_MIXIN = "com.lostglade.mixin.WebcamNearbyPlayersRendererBotMixin";
	private static final String CLIENT_WEBCAM_RENDER_LAYER_MIXIN = "com.lostglade.mixin.client.WebcamRenderLayerOffscreenMixin";
	private static final String VOICECHAT_PLAYER_STATE_MIXIN = "com.lostglade.mixin.VoicechatPlayerStateRendererBotMixin";

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.startsWith("com.lostglade.mixin.client.SodiumCamera")) return FabricLoader.getInstance().isModLoaded("sodium");
		if (mixinClassName.startsWith("com.lostglade.mixin.client.VoxyCamera")) return FabricLoader.getInstance().isModLoaded("voxy");
		if (mixinClassName.startsWith("com.lostglade.mixin.client.DistantHorizonsCamera")) return FabricLoader.getInstance().isModLoaded("distanthorizons");
		if (WEBCAM_MIXIN.equals(mixinClassName)
				|| WEBCAM_NEARBY_PLAYERS_MIXIN.equals(mixinClassName)
				|| CLIENT_WEBCAM_RENDER_LAYER_MIXIN.equals(mixinClassName)) {
			return FabricLoader.getInstance().isModLoaded("webcam");
		}
		if (VOICECHAT_PLAYER_STATE_MIXIN.equals(mixinClassName)) {
			return FabricLoader.getInstance().isModLoaded("voicechat");
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		String marker = switch (mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)) {
			case "SodiumCameraWorldMixin" -> "lg2$privateScene";
			case "SodiumCameraFogMixin" -> "lg2$privateFog";
			case "VoxyCameraRendererMixin" -> "lg2$noForeignLods";
			case "VoxyCameraWorldMixin" -> "lg2$ignorePrivateWorld";
			case "DistantHorizonsCameraRenderMixin" -> "lg2$noForeignLods";
			case "DistantHorizonsCameraChunksMixin" -> "lg2$noHistoricalLodCache";
			default -> null;
		};
		if (marker == null) return;
		int calls = 0;
		boolean cacheGuard = false;
		for (var method : targetClass.methods) {
			for (var instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call) {
					if (call.name.contains(marker)) calls++;
					if (call.name.contains("lg2$noHistoricalLodCache")) cacheGuard = true;
				}
			}
		}
		int required = mixinClassName.endsWith("DistantHorizonsCameraRenderMixin") ? 3 : 1;
		if (calls >= required && (!mixinClassName.endsWith("VoxyCameraRendererMixin") || cacheGuard)) COMPATIBILITY_HOOKS.add(mixinClassName);
	}
}
