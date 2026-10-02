package com.lostglade.mixin;

import com.lostglade.server.GennadiyReportTracker;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityGennadiyReportTrackerMixin {
    @Inject(method = "load", at = @At("TAIL"))
    private void lg2$trackReportEntityData(net.minecraft.world.level.storage.ValueInput input, CallbackInfo ci) {
        GennadiyReportTracker.entityChanged((Entity) (Object) this);
    }
    @Inject(method = "setRemoved", at = @At("TAIL"))
    private void lg2$rememberRemovedReportHost(Entity.RemovalReason reason, CallbackInfo ci) {
        if (reason.shouldDestroy()) GennadiyReportTracker.noteEntityRemoved((Entity) (Object) this);
    }
}
