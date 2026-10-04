package com.lostglade.mixin;

import com.lostglade.server.NecromancerStockSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeNecromancerEraMixin {
	@Shadow
	@Final
	protected ServerPlayer player;

	@Unique
	private ItemStack lg2$necromancerEraTool = ItemStack.EMPTY;

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void lg2$captureNecromancerEraTool(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		this.lg2$necromancerEraTool = NecromancerStockSystem.virtualMiningHarvestTool(
				this.player,
				this.player.level().getBlockState(pos));
	}

	@Redirect(
			method = "destroyBlock",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerPlayer;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"
			)
	)
	private ItemStack lg2$useNecromancerEraToolForDrops(ServerPlayer player) {
		return this.lg2$necromancerEraTool.isEmpty() ? player.getMainHandItem() : this.lg2$necromancerEraTool;
	}

	@Redirect(
			method = "destroyBlock",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerPlayer;hasCorrectToolForDrops(Lnet/minecraft/world/level/block/state/BlockState;)Z"
			)
	)
	private boolean lg2$useNecromancerEraToolForHarvest(ServerPlayer player, BlockState state) {
		return !this.lg2$necromancerEraTool.isEmpty()
				? !state.requiresCorrectToolForDrops() || this.lg2$necromancerEraTool.isCorrectToolForDrops(state)
				: player.hasCorrectToolForDrops(state);
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void lg2$clearNecromancerEraTool(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		this.lg2$necromancerEraTool = ItemStack.EMPTY;
	}
}
