package com.lostglade.mixin;

import com.lostglade.server.GennadiyReportTracker;
import net.minecraft.world.SimpleContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimpleContainer.class)
public abstract class SimpleContainerGennadiyReportMixin {
    @Inject(method = "setChanged", at = @At("TAIL"))
    private void lg2$trackReportInventoryChange(CallbackInfo ci) {
        GennadiyReportTracker.containerChanged((SimpleContainer) (Object) this);
    }
}
