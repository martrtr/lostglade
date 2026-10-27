package com.lostglade.mixin.client;

import com.lostglade.client.RendererSceneCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.SharedApi", remap = false)
public abstract class DistantHorizonsCameraChunksMixin {
	@Inject(method = "applyChunkUpdate", at = @At("HEAD"), cancellable = true, require = 0)
	private void lg2$noHistoricalLodCache(@Coerce Object chunk, @Coerce Object level, boolean wait, CallbackInfo callback) {
		if (RendererSceneCompatibility.active() || RendererSceneCompatibility.isSceneWrapper(level)) callback.cancel();
	}
}
