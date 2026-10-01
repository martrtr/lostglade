package com.lostglade.mixin;

import com.lostglade.server.GennadiyReportTracker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFrame.class)
public abstract class ItemFrameGennadiyReportTrackerMixin {
    @Inject(method = "setItem(Lnet/minecraft/world/item/ItemStack;Z)V", at = @At("TAIL"))
    private void lg2$trackFramedReport(ItemStack stack, boolean sound, CallbackInfo ci) {
        GennadiyReportTracker.entityChanged((Entity) (Object) this);
    }
}
