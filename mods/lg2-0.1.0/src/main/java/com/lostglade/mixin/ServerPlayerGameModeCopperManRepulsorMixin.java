package com.lostglade.mixin;

import com.lostglade.server.CopperManGogglesSystem;
import com.lostglade.server.CopperManRepulsorSystem;
import com.lostglade.server.DroneSystem;
import com.lostglade.server.ServerRaceSystem;
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
public abstract class ServerPlayerGameModeCopperManRepulsorMixin {
	@Inject(method = "useItem", at = @At("RETURN"), cancellable = true)
	private void lg2$fireGennadiyDonkeyAfterVanillaItemPass(
			ServerPlayer player,
			Level level,
			ItemStack stack,
			InteractionHand hand,
			CallbackInfoReturnable<InteractionResult> cir
	) {
		if (cir.getReturnValue() != InteractionResult.PASS) {
			return;
		}
		if (ServerRaceSystem.handleGennadiyDonkeyUseInteraction(player, hand)) {
			cir.setReturnValue(InteractionResult.SUCCESS);
			return;
		}
		if (DroneSystem.handleControlledUseItem(player, hand)) {
			cir.setReturnValue(InteractionResult.SUCCESS);
		}
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void lg2$trackCopperGogglesOnAnyBlockUse(
			ServerPlayer player,
			Level level,
			ItemStack stack,
			InteractionHand hand,
			BlockHitResult hitResult,
			CallbackInfoReturnable<InteractionResult> cir
	) {
		InteractionResult dictatorResult = ServerRaceSystem.tryHandleLittleDictatorIronMechanism(
				player,
				hand,
				hitResult
		);
		if (dictatorResult != InteractionResult.PASS) {
			cir.setReturnValue(dictatorResult);
			return;
		}
		if (DroneSystem.handleControlledUseItemOn(player, hand, hitResult)) {
			cir.setReturnValue(InteractionResult.SUCCESS);
			return;
		}
		CopperManGogglesSystem.handleAnyBlockUse(player, hand);
	}

	@Inject(method = "useItemOn", at = @At("RETURN"), cancellable = true)
	private void lg2$fireCopperRepulsorAfterVanillaBlockPass(
			ServerPlayer player,
			Level level,
			ItemStack stack,
			InteractionHand hand,
			BlockHitResult hitResult,
			CallbackInfoReturnable<InteractionResult> cir
	) {
		if (cir.getReturnValue() != InteractionResult.PASS) {
			return;
		}
		if (CopperManRepulsorSystem.handleUseInteraction(player, hand)) {
			cir.setReturnValue(InteractionResult.SUCCESS);
			return;
		}
		if (ServerRaceSystem.handleGennadiyDonkeyUseInteraction(player, hand)) {
			cir.setReturnValue(InteractionResult.SUCCESS);
			return;
		}
		if (DroneSystem.handleControlledUseItem(player, hand)) {
			cir.setReturnValue(InteractionResult.SUCCESS);
			return;
		}
		if (CopperManGogglesSystem.handleUseOnBlockPass(player, hand)) {
			cir.setReturnValue(InteractionResult.SUCCESS);
		}
	}
}
