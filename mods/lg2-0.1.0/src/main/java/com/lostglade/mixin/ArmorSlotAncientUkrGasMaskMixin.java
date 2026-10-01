package com.lostglade.mixin;

import com.lostglade.server.ServerRaceSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.inventory.ArmorSlot")
public abstract class ArmorSlotAncientUkrGasMaskMixin {
	@Shadow @Final private LivingEntity owner;
	@Shadow @Final private EquipmentSlot slot;

	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void lg2$protectGasMaskFromQuickEquip(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (owner instanceof ServerPlayer player && ServerRaceSystem.isLockedAncientUkrGasMaskSlot(player, slot)) {
			cir.setReturnValue(false);
		}
	}
}
