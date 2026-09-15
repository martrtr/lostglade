package com.lostglade.mixin;

import com.lostglade.server.ServerRaceSystem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobAncientUkrSmokeTargetMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void lg2$blockAncientUkrSmokeTarget(LivingEntity target, CallbackInfo ci) {
        if (target != null && ServerRaceSystem.blocksAncientUkrSmokeTarget((Mob) (Object) this, target)) {
            ci.cancel();
        }
    }
}
