package com.lostglade.mixin;

import com.lostglade.server.NecromancerStockSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityNecromancerStockMixin {
	@ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
	private float lg2$normalizeNecromancerWeaponDamage(float damage, ServerLevel level, DamageSource source) {
		return NecromancerStockSystem.normalizeWeaponDamage((LivingEntity) (Object) this, source, damage);
	}

	@Inject(method = "checkTotemDeathProtection", at = @At("HEAD"), cancellable = true)
	private void lg2$disableNecromancerTotem(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
		if (NecromancerStockSystem.shouldDisableTotem((LivingEntity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
