package com.lostglade.mixin.client;

import com.lostglade.client.RendererSceneCompatibility;
import com.lostglade.client.SodiumRendererCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumCameraWorldMixin {
	@Inject(method = "instanceNullable", at = @At("HEAD"), cancellable = true, require = 0)
	private static void lg2$privateScene(CallbackInfoReturnable<Object> callback) {
		if (RendererSceneCompatibility.active()) callback.setReturnValue(SodiumRendererCompatibility.worldRenderer(RendererSceneCompatibility.renderer()));
	}
}
