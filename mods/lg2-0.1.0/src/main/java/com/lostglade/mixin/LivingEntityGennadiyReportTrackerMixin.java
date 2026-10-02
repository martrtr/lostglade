package com.lostglade.mixin;

import com.lostglade.server.GennadiyReportTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityGennadiyReportTrackerMixin {
    @Inject(method = "setItemSlot", at = @At("TAIL"))
    private void lg2$trackEquippedReport(EquipmentSlot slot, ItemStack stack, CallbackInfo ci) {
        GennadiyReportTracker.entityChanged((Entity) (Object) this);
    }
}
