package com.lostglade.mixin;

import com.lostglade.server.ServerRaceSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Equippable.class)
public abstract class EquippableAncientUkrGasMaskMixin {
	@Inject(method = "swapWithEquipmentSlot", at = @At("HEAD"), cancellable = true)
	private void lg2$preventGasMaskReplacement(ItemStack stack, Player player,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (player instanceof ServerPlayer serverPlayer
				&& ServerRaceSystem.isLockedAncientUkrGasMaskSlot(serverPlayer, ((Equippable) (Object) this).slot())) {
			// Right-click equipment swaps bypass inventory Slot.mayPickup.
			// Correct only the two slots the vanilla client may have predicted swapping.
			Inventory inventory = serverPlayer.getInventory();
			int handSlot = stack == serverPlayer.getOffhandItem() ? Inventory.SLOT_OFFHAND : inventory.getSelectedSlot();
			serverPlayer.connection.send(inventory.createInventoryUpdatePacket(EquipmentSlot.HEAD.getIndex(Inventory.INVENTORY_SIZE)));
			serverPlayer.connection.send(inventory.createInventoryUpdatePacket(handSlot));
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}
}
