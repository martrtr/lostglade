package com.lostglade.mixin;

import com.lostglade.server.AccountAuthSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void lg2$blockAuthenticationWorldDamage(
			ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir
	) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		if (!AccountAuthSystem.isAuthenticationWorld(player)) return;
		player.resetFallDistance();
		cir.setReturnValue(false);
	}
	/**
	 * Vanilla dimension changes preserve entity data (respawn keep flags = 3). That is wrong for the
	 * auth transport dimension: a stale zero-health/death pose would be copied into the real-world
	 * LocalPlayer. Force a clean client player only for the approved auth -> gameplay transition.
	 */
	@ModifyArg(
			method = "teleport",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/network/protocol/game/ClientboundRespawnPacket;<init>(Lnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;B)V"
			),
			index = 1
	)
	private byte lg2$resetClientStateWhenLeavingAuthenticationWorld(byte keepFlags) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		return AccountAuthSystem.isAuthTransitioning(player) ? (byte) 0 : keepFlags;
	}

}
