package com.lostglade.mixin;

import com.lostglade.server.ServerRaceSystem;
import com.lostglade.server.OrthodoxAttackSystem;
import com.lostglade.server.AncientUkrCollectorSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMarkKillMixin {
	@Inject(method = "die", at = @At("HEAD"))
	private void lg2$trackMarkPlayerKill(DamageSource damageSource, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (self.level() instanceof ServerLevel level) {
			ServerRaceSystem.handleMarkRageKill(level, self, damageSource);
			ServerRaceSystem.handleLittleDictatorPlayerKill(level, self, damageSource);
			OrthodoxAttackSystem.onLivingDeath(level, self, damageSource);
			AncientUkrCollectorSystem.onLivingDeath(level, self, damageSource);
		}
	}
}
