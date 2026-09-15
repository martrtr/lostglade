package com.lostglade.mixin;

import com.lostglade.server.ServerRaceSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuAncientUkrGasMaskMixin {
	@Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
	private void lg2$lockGasMaskHeadSlot(int slotIndex, int button, ClickType clickType, Player player, CallbackInfo ci) {
		if (player instanceof ServerPlayer serverPlayer && ServerRaceSystem.isLockedAncientUkrGasMaskSlot(
				serverPlayer, (AbstractContainerMenu) (Object) this, slotIndex)) {
			// Cancel only the head-slot operation, including CLONE (which skips mayPickup).
			// The packet handler must still reconcile the client's predicted slots/cursor.
			ci.cancel();
		}
	}
}
