package com.lostglade.mixin.client;

import com.lostglade.client.RendererSceneCompatibility;
import com.lostglade.client.SodiumRendererCompatibility;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GameRenderer.class, priority = 900)
public abstract class SodiumCameraFogMixin {
	@Inject(method = "sodium$getFogParameters", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void lg2$privateFog(CallbackInfoReturnable<Object> callback) {
		if (RendererSceneCompatibility.active() && RendererSceneCompatibility.fog() != null) {
			callback.setReturnValue(SodiumRendererCompatibility.sceneFogParameters());
		}
	}
}
