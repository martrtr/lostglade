package com.lostglade.mixin;

import com.lostglade.server.MonitorScreenSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Projectile.class)
public abstract class ProjectileMonitorCollisionMixin {
	@Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
	private void lg2$ignoreMonitorFrame(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof ItemFrame frame && MonitorScreenSystem.isMonitorFrame(frame)) {
			cir.setReturnValue(false);
		}
	}
}
