package com.lostglade.mixin;

import com.lostglade.server.NecromancerStockSystem;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CraftingMenu.class)
public abstract class CraftingMenuNecromancerMixin {
	private static final ThreadLocal<ServerPlayer> LG2_CRAFTING_PLAYER = new ThreadLocal<>();

	@Inject(method = "slotChangedCraftingGrid", at = @At("HEAD"))
	private static void lg2$beginNecromancerCraftResult(
			AbstractContainerMenu menu,
			ServerLevel level,
			Player player,
			CraftingContainer input,
			ResultContainer result,
			RecipeHolder<CraftingRecipe> recipe,
			CallbackInfo ci
	) {
		LG2_CRAFTING_PLAYER.set(player instanceof ServerPlayer serverPlayer ? serverPlayer : null);
	}

	@ModifyArg(
			method = "slotChangedCraftingGrid",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/ResultContainer;setItem(ILnet/minecraft/world/item/ItemStack;)V"),
			index = 1
	)
	private static ItemStack lg2$hideNecromancerResultContainer(ItemStack result) {
		return filterResult(result);
	}

	@ModifyArg(
			method = "slotChangedCraftingGrid",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;setRemoteSlot(ILnet/minecraft/world/item/ItemStack;)V"),
			index = 1
	)
	private static ItemStack lg2$hideNecromancerRemoteResult(ItemStack result) {
		return filterResult(result);
	}

	@ModifyArg(
			method = "slotChangedCraftingGrid",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundContainerSetSlotPacket;<init>(IIILnet/minecraft/world/item/ItemStack;)V"),
			index = 3
	)
	private static ItemStack lg2$hideNecromancerResultPacket(ItemStack result) {
		return filterResult(result);
	}

	@Inject(method = "slotChangedCraftingGrid", at = @At("RETURN"))
	private static void lg2$endNecromancerCraftResult(
			AbstractContainerMenu menu,
			ServerLevel level,
			Player player,
			CraftingContainer input,
			ResultContainer result,
			RecipeHolder<CraftingRecipe> recipe,
			CallbackInfo ci
	) {
		LG2_CRAFTING_PLAYER.remove();
	}

	private static ItemStack filterResult(ItemStack result) {
		ServerPlayer player = LG2_CRAFTING_PLAYER.get();
		return player != null && !NecromancerStockSystem.canTakeCraftResult(player, result)
				? ItemStack.EMPTY
				: result;
	}
}
