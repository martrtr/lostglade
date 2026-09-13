package com.lostglade.mixin.client;

import com.lostglade.client.RendererBotShadowLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public abstract class ClientLevelCameraSceneMixin {
	@Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;particleEngine:Lnet/minecraft/client/particle/ParticleEngine;"))
	private ParticleEngine lg2$sceneParticles(Minecraft client) {
		return (Object) this instanceof RendererBotShadowLevel scene ? scene.sceneParticles() : client.particleEngine;
	}

	@Inject(method = "getMarkerParticleTarget", at = @At("HEAD"), cancellable = true)
	private void lg2$noOwnerHeldItemInScene(CallbackInfoReturnable<Block> cir) {
		if ((Object) this instanceof RendererBotShadowLevel) cir.setReturnValue(null);
	}
}
