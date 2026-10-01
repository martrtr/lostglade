package com.lostglade.mixin;

import com.lostglade.server.ServerRaceSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(ItemStack.class)
public abstract class ItemStackGennadiyReportMixin {
	@Unique
	private ItemStack lg2$reportBeforeConsumption = ItemStack.EMPTY;
	@Unique
	private ItemStack lg2$reportBeforeDamage = ItemStack.EMPTY;

	@Inject(method = "consume", at = @At("HEAD"))
	private void lg2$captureConsumedReport(int amount, LivingEntity consumer, CallbackInfo ci) {
		ItemStack self = (ItemStack) (Object) this;
		lg2$reportBeforeConsumption = ServerRaceSystem.isGennadiyReportItem(self) ? self.copy() : ItemStack.EMPTY;
	}

	@Inject(method = "consume", at = @At("TAIL"))
	private void lg2$trackConsumedReport(int amount, LivingEntity consumer, CallbackInfo ci) {
		ItemStack snapshot = lg2$reportBeforeConsumption;
		lg2$reportBeforeConsumption = ItemStack.EMPTY;
		ItemStack self = (ItemStack) (Object) this;
		if (!snapshot.isEmpty() && !ServerRaceSystem.isGennadiyReportItem(self)
				&& consumer.level() instanceof ServerLevel level) {
			ServerRaceSystem.markGennadiyReportDestroyed(level.getServer(), snapshot);
		}
	}

	@Inject(
			method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
			at = @At("HEAD")
	)
	private void lg2$captureDamagedReport(
			int amount,
			ServerLevel level,
			ServerPlayer player,
			Consumer<Item> onBreak,
			CallbackInfo ci
	) {
		ItemStack self = (ItemStack) (Object) this;
		lg2$reportBeforeDamage = ServerRaceSystem.isGennadiyReportItem(self) ? self.copy() : ItemStack.EMPTY;
	}

	@Inject(
			method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
			at = @At("TAIL")
	)
	private void lg2$trackBrokenReport(
			int amount,
			ServerLevel level,
			ServerPlayer player,
			Consumer<Item> onBreak,
			CallbackInfo ci
	) {
		ItemStack snapshot = lg2$reportBeforeDamage;
		lg2$reportBeforeDamage = ItemStack.EMPTY;
		if (!snapshot.isEmpty() && !ServerRaceSystem.isGennadiyReportItem((ItemStack) (Object) this)) {
			ServerRaceSystem.markGennadiyReportDestroyed(level.getServer(), snapshot);
		}
	}
}
