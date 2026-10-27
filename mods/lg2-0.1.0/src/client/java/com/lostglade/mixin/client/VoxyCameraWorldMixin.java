package com.lostglade.mixin.client;

import com.lostglade.client.RendererSceneCompatibility;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.cortex.voxy.commonImpl.WorldIdentifier", remap = false)
public abstract class VoxyCameraWorldMixin {
	@Inject(method = "of", at = @At("HEAD"), cancellable = true, require = 0)
	private static void lg2$ignorePrivateWorld(Level level, CallbackInfoReturnable<Object> callback) {
		if (RendererSceneCompatibility.isScene(level)) callback.setReturnValue(null);
	}
}
