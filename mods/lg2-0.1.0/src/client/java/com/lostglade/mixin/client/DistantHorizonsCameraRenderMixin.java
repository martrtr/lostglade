package com.lostglade.mixin.client;

import com.lostglade.client.RendererSceneCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public abstract class DistantHorizonsCameraRenderMixin {
	@Inject(method = {"renderLodLayer", "renderFadeOpaque", "renderFadeTransparent"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void lg2$noForeignLods(CallbackInfo callback) {
		if (RendererSceneCompatibility.active()) callback.cancel();
	}
}
