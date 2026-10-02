package com.lostglade.mixin;

import com.lostglade.server.GennadiyReportTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class LevelGennadiyReportTrackerMixin {
    @Inject(method = "blockEntityChanged", at = @At("TAIL"))
    private void lg2$trackReportStorageChange(BlockPos pos, CallbackInfo ci) {
        GennadiyReportTracker.blockChanged((Level) (Object) this, pos);
    }
}
