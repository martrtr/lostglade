package com.lostglade.mixin.client;

import com.lostglade.client.RendererSceneCompatibility;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class VoxyCameraRendererMixin {
	@Shadow private ClientLevel level;
	@Inject(method = "voxy$getRenderSystem", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void lg2$noForeignLods(CallbackInfoReturnable<Object> callback) {
		if (RendererSceneCompatibility.active() || RendererSceneCompatibility.isScene(level)) callback.setReturnValue(null);
	}
	@Inject(method = "voxy$createRenderer", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void lg2$noHistoricalLodCache(CallbackInfo callback) {
		if (RendererSceneCompatibility.isScene(level)) callback.cancel();
	}
}
