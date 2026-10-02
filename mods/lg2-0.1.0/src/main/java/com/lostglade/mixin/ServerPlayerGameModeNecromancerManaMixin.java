package com.lostglade.mixin;

import com.lostglade.server.NecromancerStockSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeNecromancerManaMixin {
	@Inject(method = "useItem", at = @At("RETURN"))
	private void lg2$chargeSuccessfulNecromancerItemUse(
			ServerPlayer player,
			Level level,
			ItemStack stack,
			InteractionHand hand,
			CallbackInfoReturnable<InteractionResult> cir
	) {
		if (cir.getReturnValue().consumesAction()) {
			NecromancerStockSystem.recordSuccessfulHandAction(player, hand);
		}
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void lg2$chargeSuccessfulNecromancerBlockUse(
			ServerPlayer player,
			Level level,
			ItemStack stack,
			InteractionHand hand,
			BlockHitResult hitResult,
			CallbackInfoReturnable<InteractionResult> cir
	) {
		if (cir.getReturnValue().consumesAction()) {
			NecromancerStockSystem.recordSuccessfulHandAction(player, hand);
		}
	}
}
