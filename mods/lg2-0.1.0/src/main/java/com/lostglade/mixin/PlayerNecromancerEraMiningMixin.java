package com.lostglade.mixin;

import com.lostglade.server.NecromancerStockSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Player.class)
public abstract class PlayerNecromancerEraMiningMixin {
	@Redirect(
			method = "getDestroySpeed",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/player/Inventory;getSelectedItem()Lnet/minecraft/world/item/ItemStack;"
			)
	)
	private ItemStack lg2$useNecromancerEraSpeedTool(Inventory inventory, BlockState state) {
		Player player = (Player) (Object) this;
		if (player instanceof ServerPlayer serverPlayer) {
			ItemStack virtualTool = NecromancerStockSystem.virtualMiningSpeedTool(serverPlayer, state);
			if (!virtualTool.isEmpty()) return virtualTool;
		}
		return inventory.getSelectedItem();
	}

	@Redirect(
			method = "hasCorrectToolForDrops",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/player/Inventory;getSelectedItem()Lnet/minecraft/world/item/ItemStack;"
			)
	)
	private ItemStack lg2$useNecromancerEraHarvestTool(Inventory inventory, BlockState state) {
		Player player = (Player) (Object) this;
		if (player instanceof ServerPlayer serverPlayer) {
			ItemStack virtualTool = NecromancerStockSystem.virtualMiningHarvestTool(serverPlayer, state);
			if (!virtualTool.isEmpty()) return virtualTool;
		}
		return inventory.getSelectedItem();
	}
}
