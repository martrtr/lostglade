package com.lostglade.mixin;

import com.lostglade.server.NecromancerStockSystem;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.network.ServerGamePacketListenerImpl$1")
public abstract class ServerGamePacketListenerNecromancerInteractionMixin {
	@Shadow
	@Final
	private ServerGamePacketListenerImpl field_28963;

	@Unique
	private InteractionHand lg2$necromancerInteractionHand;

	@Inject(method = "performInteraction", at = @At("HEAD"), cancellable = true)
	private void lg2$validateNecromancerInteraction(
			InteractionHand hand,
			@Coerce Object interaction,
			CallbackInfo ci
	) {
		this.lg2$necromancerInteractionHand = hand;
		if (!NecromancerStockSystem.canAttemptHandAction(this.field_28963.player, hand)) {
			ci.cancel();
		}
	}

	@ModifyVariable(method = "performInteraction", at = @At("STORE"), ordinal = 0)
	private InteractionResult lg2$chargeSuccessfulNecromancerInteraction(InteractionResult result) {
		if (result.consumesAction()) {
			NecromancerStockSystem.recordSuccessfulHandAction(
					this.field_28963.player,
					this.lg2$necromancerInteractionHand);
		}
		return result;
	}
}
