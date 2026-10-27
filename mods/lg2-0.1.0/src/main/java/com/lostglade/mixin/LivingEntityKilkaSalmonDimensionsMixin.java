package com.lostglade.mixin;

import com.lostglade.server.ServerRaceSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityKilkaSalmonDimensionsMixin {
	@Inject(method = "getDimensions", at = @At("RETURN"), cancellable = true)
	private void lg2$useKilkaSalmonDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		if (!((Object) this instanceof ServerPlayer player) || !ServerRaceSystem.shouldUseKilkaSalmonDimensions(player)) {
			return;
		}
		cir.setReturnValue(EntityType.SALMON.getDimensions());
	}
}
