package com.lostglade.mixin.client;

import com.lostglade.client.RendererBotShadowLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererCameraSceneMixin {
	@Shadow private ClientLevel level;

	@Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"))
	private ClientLevel lg2$ownedLevel(Minecraft client) { return level; }

	@Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;particleEngine:Lnet/minecraft/client/particle/ParticleEngine;"))
	private ParticleEngine lg2$ownedParticles(Minecraft client) {
		return level instanceof RendererBotShadowLevel scene ? scene.sceneParticles() : client.particleEngine;
	}

	@Redirect(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;crosshairPickEntity:Lnet/minecraft/world/entity/Entity;"))
	private Entity lg2$sceneCrosshairTarget(Minecraft client) {
		// The owner can be looking at an entity in their normal world.  That object
		// must not become the highlighted target of an unrelated camera scene.
		return level instanceof RendererBotShadowLevel ? null : client.crosshairPickEntity;
	}

	@Redirect(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
	private boolean lg2$sceneSpectatorState(LocalPlayer player) {
		// There is intentionally no LocalPlayer in a shadow world.  Its terrain
		// culling must not inherit the contributing player's spectator state.
		return level instanceof RendererBotShadowLevel ? false : player.isSpectator();
	}

	@Redirect(method = "extractVisibleEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;hasIndirectPassenger(Lnet/minecraft/world/entity/Entity;)Z"))
	private boolean lg2$sceneOwnerPassenger(Entity entity, Entity passenger) {
		// This fallback in vanilla exists solely to keep the local player's vehicle
		// visible.  A player from the real level can never be a passenger here.
		return !(level instanceof RendererBotShadowLevel) && entity.hasIndirectPassenger(passenger);
	}
}
