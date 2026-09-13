package com.lostglade.mixin.client;

import com.lostglade.client.RendererBotShadowLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerCameraSceneMixin {
	@Shadow private ClientLevel level;

	@Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;levelRenderer:Lnet/minecraft/client/renderer/LevelRenderer;"))
	private LevelRenderer lg2$sceneRenderer(Minecraft client) {
		return level instanceof RendererBotShadowLevel scene ? scene.sceneRenderer() : client.levelRenderer;
	}

	@Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;particleEngine:Lnet/minecraft/client/particle/ParticleEngine;"))
	private ParticleEngine lg2$sceneParticles(Minecraft client) {
		return level instanceof RendererBotShadowLevel scene ? scene.sceneParticles() : client.particleEngine;
	}
}
