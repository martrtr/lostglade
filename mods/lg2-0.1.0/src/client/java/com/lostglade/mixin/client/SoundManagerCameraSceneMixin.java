package com.lostglade.mixin.client;

import com.lostglade.client.RendererBotSceneContext;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Don't let deferred scene sounds escape their scope into the owner's audio queue. */
@Mixin(SoundManager.class)
public abstract class SoundManagerCameraSceneMixin {
	@Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
	private void lg2$sceneDelayedSound(SoundInstance sound, int delay, CallbackInfo ci) {
		if (RendererBotSceneContext.level() != null) {
			((SoundManager) (Object) this).play(sound);
			ci.cancel();
		}
	}
	@Inject(method = "queueTickingSound", at = @At("HEAD"), cancellable = true)
	private void lg2$sceneTickingSound(TickableSoundInstance sound, CallbackInfo ci) {
		if (RendererBotSceneContext.level() != null) {
			((SoundManager) (Object) this).play(sound);
			ci.cancel();
		}
	}
}
