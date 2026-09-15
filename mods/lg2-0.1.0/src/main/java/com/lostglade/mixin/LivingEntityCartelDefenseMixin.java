package com.lostglade.mixin;

import com.lostglade.server.ServerMilkPocketDimensionSystem;
import com.lostglade.server.ServerRaceSystem;
import com.lostglade.server.PuroSanStockSystem;
import com.lostglade.server.OrthodoxAttackSystem;
import com.lostglade.server.OrthodoxDefenseSystem;
import com.lostglade.server.AncientUkrCollectorSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityCartelDefenseMixin {
	@ModifyVariable(method = "setHealth", at = @At("HEAD"), argsOnly = true)
	private float lg2$protectOrthodoxDefenseHealth(float health) {
		LivingEntity self = (LivingEntity) (Object) this;
		return OrthodoxDefenseSystem.protectHealthChange(self, health);
	}

	@ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
	private float lg2$applyRaceDamageModifiers(float damage, ServerLevel level, DamageSource damageSource) {
		LivingEntity victim = (LivingEntity) (Object) this;
		float modified = ServerRaceSystem.modifyMarkStockFirstHitDamage(level, victim, damageSource, damage);
		return PuroSanStockSystem.modifyDamage(level, victim, damageSource, modified);
	}
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void lg2$blockGennadiyDefenseDamage(
			ServerLevel level,
			DamageSource damageSource,
			float damage,
			CallbackInfoReturnable<Boolean> cir
	) {
		if (OrthodoxDefenseSystem.shouldCancelDamage((LivingEntity) (Object) this)) {
			cir.setReturnValue(false);
			return;
		}
		if (PuroSanStockSystem.shouldCancelFallDamage((LivingEntity) (Object) this, damageSource)) {
			cir.setReturnValue(false);
			return;
		}
		if ((Object) this instanceof ServerPlayer player
				&& ServerMilkPocketDimensionSystem.shouldCancelFirstLandingFallDamage(player, damageSource)) {
			cir.setReturnValue(false);
			return;
		}
		if (ServerRaceSystem.shouldCancelKilkaSalmonOwnerSuffocationDamage((LivingEntity) (Object) this, damageSource)) {
			cir.setReturnValue(false);
			return;
		}
		if (ServerRaceSystem.shouldCancelMilkMouseCombatDamage((LivingEntity) (Object) this, damageSource)) {
			cir.setReturnValue(false);
			return;
		}
		if (ServerRaceSystem.handleKilkaSalmonVisualDamage(level, (LivingEntity) (Object) this, damageSource, damage)) {
			cir.setReturnValue(false);
			return;
		}
		if (ServerRaceSystem.handleMilkAbsoluteAttack(level, (LivingEntity) (Object) this, damageSource, damage)) {
			cir.setReturnValue(true);
			return;
		}
		if (ServerRaceSystem.handleKilkaDefenseDamage(level, (LivingEntity) (Object) this, damageSource, damage)) {
			cir.setReturnValue(false);
			return;
		}
		if (ServerRaceSystem.handleGennadiyDefenseDamage(level, (LivingEntity) (Object) this, damageSource, damage)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "hurtServer", at = @At("RETURN"))
	private void lg2$reflectDamageToCartelAttackers(
			ServerLevel level,
			DamageSource damageSource,
			float damage,
			CallbackInfoReturnable<Boolean> cir
	) {
		ServerRaceSystem.handleCartelDefenseDamage(level, (LivingEntity) (Object) this, damageSource, damage, cir.getReturnValueZ());
		ServerRaceSystem.handleGennadiyCombatDamage(level, (LivingEntity) (Object) this, damageSource, damage, cir.getReturnValueZ());
		ServerRaceSystem.handleGennadiyRageMeleeDamage(level, (LivingEntity) (Object) this, damageSource, damage, cir.getReturnValueZ());
		ServerRaceSystem.handleMarkRageMeleeDamage(level, (LivingEntity) (Object) this, damageSource, damage, cir.getReturnValueZ());
		ServerRaceSystem.handleMilkStockCombatDamage(level, (LivingEntity) (Object) this, damageSource, damage, cir.getReturnValueZ());
		ServerRaceSystem.handleLittleDictatorCombatDamage(level, (LivingEntity) (Object) this, damageSource, damage, cir.getReturnValueZ());
		ServerRaceSystem.handleKilkaIncomingDamage(level, (LivingEntity) (Object) this, damageSource, damage, cir.getReturnValueZ());
		ServerRaceSystem.handlePuroSanOverdriveCombatDamage(level, (LivingEntity) (Object) this, damageSource, damage, cir.getReturnValueZ());
		ServerRaceSystem.handleMilkDefenseDodge(level, (LivingEntity) (Object) this, damageSource, damage, cir.getReturnValueZ());
		AncientUkrCollectorSystem.onSuccessfulDamage((LivingEntity) (Object) this, damageSource, cir.getReturnValueZ());
		ServerRaceSystem.handleAncientUkrSmokeProvocation(level, (LivingEntity) (Object) this, damageSource, cir.getReturnValueZ());
		if (cir.getReturnValueZ()) {
			OrthodoxAttackSystem.onSuccessfulDamage(level, (LivingEntity) (Object) this, damageSource, damage);
		}
		if (cir.getReturnValueZ() && damage > 0.0F && (Object) this instanceof ServerPlayer player) {
			ServerMilkPocketDimensionSystem.recordPlayerDamage(player);
		}
	}

	@Inject(method = "die", at = @At("HEAD"))
	private void lg2$trackMarkRageKill(DamageSource damageSource, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level() instanceof ServerLevel level) {
			ServerRaceSystem.handleMarkRageKill(level, self, damageSource);
			ServerRaceSystem.handleLittleDictatorPlayerKill(level, self, damageSource);
			OrthodoxAttackSystem.onLivingDeath(level, self, damageSource);
		}
	}
}
