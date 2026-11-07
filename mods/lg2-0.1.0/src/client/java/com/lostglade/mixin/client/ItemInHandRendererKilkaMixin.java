package com.lostglade.mixin.client;

import com.lostglade.client.KilkaClientForm;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererKilkaMixin {
	@Inject(method = "renderPlayerArm", at = @At("HEAD"), cancellable = true)
	private void lg2$hideEmptyHand(PoseStack poseStack, SubmitNodeCollector collector,
			int light, float equippedProgress, float swingProgress, HumanoidArm arm, CallbackInfo ci) {
		if (KilkaClientForm.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderMapHand", at = @At("HEAD"), cancellable = true)
	private void lg2$hideMapHand(PoseStack poseStack, SubmitNodeCollector collector,
			int light, HumanoidArm arm, CallbackInfo ci) {
		if (KilkaClientForm.isActive()) {
			ci.cancel();
		}
	}
}
