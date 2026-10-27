package com.lostglade.mixin.client;

import com.lostglade.client.KilkaClientForm;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityKilkaLocalDimensionsMixin {
	@Inject(method = "getDimensions", at = @At("RETURN"), cancellable = true)
	private void lg2$useLocalSalmonDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		if ((Object) this instanceof LocalPlayer && KilkaClientForm.isActive()) {
			cir.setReturnValue(EntityType.SALMON.getDimensions());
		}
	}
}
