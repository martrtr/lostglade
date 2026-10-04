package com.lostglade.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMovementSpeedMixin {
	@Inject(method = "shouldCheckPlayerMovement", at = @At("HEAD"), cancellable = true)
	private void lg2$skipPlayerSpeedCheck(boolean fallFlying, CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(false);
	}

	@ModifyConstant(method = "handleMoveVehicle", constant = @Constant(doubleValue = 100.0D))
	private double lg2$skipVehicleSpeedCheck(double original) {
		return Double.POSITIVE_INFINITY;
	}
}
