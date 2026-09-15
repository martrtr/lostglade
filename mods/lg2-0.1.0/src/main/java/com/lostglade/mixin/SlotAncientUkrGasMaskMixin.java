package com.lostglade.mixin;

import com.lostglade.server.ServerRaceSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class SlotAncientUkrGasMaskMixin {
	@Inject(method = "mayPickup", at = @At("RETURN"), cancellable = true)
	private void lg2$lockAncientUkrGasMask(Player player, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()
				&& player instanceof ServerPlayer serverPlayer
				&& ServerRaceSystem.isLockedAncientUkrGasMaskSlot(serverPlayer, (Slot) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
