package com.lostglade.mixin.client;

import com.lostglade.client.KilkaClientForm;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererKilkaMixin {
	@Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void lg2$hideHumanHandsInSalmonForm(float partialTick, PoseStack poseStack,
			SubmitNodeCollector collector, LocalPlayer player, int light, CallbackInfo ci) {
		if (KilkaClientForm.isActive()) {
			ci.cancel();
		}
	}
}
