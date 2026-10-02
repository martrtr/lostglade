package com.lostglade.mixin;

import com.lostglade.server.AccountAuthSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freezes real gameplay state while the connection is isolated in auth limbo. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerAccountAuthFreezeMixin {
	@Inject(
			method = "tick",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;tickClientLoadTimeout()V",
					shift = At.Shift.AFTER
			),
			cancellable = true
	)
	private void lg2$freezeUnauthenticatedTick(CallbackInfo ci) {
		if (AccountAuthSystem.shouldBlockPacket((ServerPlayer) (Object) this)) ci.cancel();
	}

	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	private void lg2$persistRealStateInsteadOfAuthLimbo(ValueOutput output, CallbackInfo ci) {
		AccountAuthSystem.rewriteLimboPersistentState((ServerPlayer) (Object) this, output);
	}

	@Inject(method = "doTick", at = @At("HEAD"), cancellable = true)
	private void lg2$freezeUnauthenticatedGameplayTick(CallbackInfo ci) {
		if (AccountAuthSystem.shouldBlockPacket((ServerPlayer) (Object) this)) ci.cancel();
	}
}
