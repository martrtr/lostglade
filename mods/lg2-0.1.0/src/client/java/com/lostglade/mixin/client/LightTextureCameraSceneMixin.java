package com.lostglade.mixin.client;

import com.lostglade.client.RendererBotSceneContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A camera's lightmap must not inherit the contributing player's potion effects. */
@Mixin(LightTexture.class)
public abstract class LightTextureCameraSceneMixin {
	@org.spongepowered.asm.mixin.Shadow private boolean updateLightTexture;
	@Inject(method = "updateLightTexture", at = @At("HEAD"))
	private void lg2$refreshSceneLight(float partialTick, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (RendererBotSceneContext.level() != null) updateLightTexture = true;
	}
	@Redirect(method = "updateLightTexture", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"))
	private ClientLevel lg2$sceneLevel(Minecraft client) {
		return RendererBotSceneContext.level() != null ? RendererBotSceneContext.level() : client.level;
	}
	@Redirect(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getEffectBlendFactor(Lnet/minecraft/core/Holder;F)F"))
	private float lg2$cameraEffect(LocalPlayer player, Holder<MobEffect> effect, float partialTick) {
		return RendererBotSceneContext.level() != null ? 0 : player.getEffectBlendFactor(effect, partialTick);
	}
	@Redirect(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getWaterVision()F"))
	private float lg2$cameraWaterVision(LocalPlayer player) {
		return RendererBotSceneContext.level() != null ? 0 : player.getWaterVision();
	}
	@Redirect(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;hasEffect(Lnet/minecraft/core/Holder;)Z"))
	private boolean lg2$cameraHasEffect(LocalPlayer player, Holder<MobEffect> effect) {
		return RendererBotSceneContext.level() == null && player.hasEffect(effect);
	}
	@Inject(method = "calculateDarknessScale", at = @At("HEAD"), cancellable = true)
	private void lg2$cameraDarkness(LivingEntity entity, float darkness, float partialTick, CallbackInfoReturnable<Float> cir) {
		if (RendererBotSceneContext.level() != null) cir.setReturnValue(0.0F);
	}
	@Redirect(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;getDarkenWorldAmount(F)F"))
	private float lg2$cameraWorldDarkening(GameRenderer renderer, float partialTick) {
		return RendererBotSceneContext.level() != null ? 0 : ((GameRendererRenderLevelInvoker) renderer).lg2$getDarkenWorldAmount(partialTick);
	}
}
