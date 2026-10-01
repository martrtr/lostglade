package com.lostglade.mixin;

import com.lostglade.server.NecromancerStockSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerNecromancerManaMixin {
	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void lg2$chargeNecromancerAttack(Entity target, CallbackInfo ci) {
		if ((Object) this instanceof ServerPlayer player
				&& NecromancerStockSystem.isActive(player)
				&& !NecromancerStockSystem.tryAttackAction(player)) {
			ci.cancel();
		}
	}
}
