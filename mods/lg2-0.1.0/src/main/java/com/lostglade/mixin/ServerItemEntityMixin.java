package com.lostglade.mixin;

import com.lostglade.block.ModBlocks;
import com.lostglade.server.ServerMechanicsGateSystem;
import com.lostglade.server.ServerRaceSystem;
import com.lostglade.server.GennadiyReportTracker;
import com.lostglade.server.SeasonStartSystem;
import com.lostglade.server.ServerUpgradeUiSystem;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
public abstract class ServerItemEntityMixin {
	@Inject(method = "setItem", at = @At("TAIL"))
	private void lg2$trackReportStackChange(net.minecraft.world.item.ItemStack stack, CallbackInfo ci) {
		GennadiyReportTracker.itemEntityChanged((ItemEntity) (Object) this);
	}
	@Inject(method = "tick", at = @At("HEAD"))
	private void lg2$protectServerItem(CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (!lg2$isProtectedServerItem(self) || self.level().isClientSide() || lg2$isCommandGiveFakeItem(self)) {
			ServerRaceSystem.tryProcessCocaineCauldron(self);
			return;
		}

		self.setInvulnerable(true);
		self.setUnlimitedLifetime();
		lg2$clampFloor(self);
		ServerRaceSystem.tryProcessCocaineCauldron(self);
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void lg2$trackDestroyedGennadiyReport(CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (self.isRemoved() && self.getRemovalReason() != null && self.getRemovalReason().shouldDestroy()
				&& self.level() instanceof ServerLevel level
				&& ServerRaceSystem.isGennadiyReportItem(self.getItem())) {
			ServerRaceSystem.markGennadiyReportDestroyed(level.getServer(), self.getItem());
		}
	}

	@Inject(method = "hurtClient", at = @At("HEAD"), cancellable = true)
	private void lg2$ignoreClientDamage(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
		if (lg2$isProtectedServerItem((ItemEntity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void lg2$ignoreServerDamage(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if (lg2$isProtectedServerItem((ItemEntity) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "hurtServer", at = @At("TAIL"))
	private void lg2$trackDamagedGennadiyReport(
			ServerLevel level,
			DamageSource source,
			float amount,
			CallbackInfoReturnable<Boolean> cir
	) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (self.isRemoved() && self.getRemovalReason() != null && self.getRemovalReason().shouldDestroy()
				&& ServerRaceSystem.isGennadiyReportItem(self.getItem())) {
			ServerRaceSystem.markGennadiyReportDestroyed(level.getServer(), self.getItem());
		}
	}

	@Inject(method = "ignoreExplosion", at = @At("HEAD"), cancellable = true)
	private void lg2$ignoreExplosion(Explosion explosion, CallbackInfoReturnable<Boolean> cir) {
		if (lg2$isProtectedServerItem((ItemEntity) (Object) this)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "fireImmune", at = @At("HEAD"), cancellable = true)
	private void lg2$forceFireImmunity(CallbackInfoReturnable<Boolean> cir) {
		if (lg2$isProtectedServerItem((ItemEntity) (Object) this)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void lg2$blockPickupWhileUpgradeMenuOpen(Player player, CallbackInfo ci) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		if (ServerUpgradeUiSystem.isUpgradeMenuOpen(serverPlayer)) {
			ci.cancel();
			return;
		}

		ItemEntity self = (ItemEntity) (Object) this;
		if (SeasonStartSystem.shouldBlockItemPickup(serverPlayer, self)) {
			ci.cancel();
			return;
		}
		if (!ServerMechanicsGateSystem.canOwnItem(serverPlayer, self.getItem())) {
			ci.cancel();
		}
	}

	private static boolean lg2$isProtectedServerItem(ItemEntity itemEntity) {
		return itemEntity.getItem().is(ModBlocks.SERVER_ITEM);
	}

	private static boolean lg2$isCommandGiveFakeItem(ItemEntity itemEntity) {
		return itemEntity.hasPickUpDelay() && itemEntity.getAge() >= 5999;
	}

	private static void lg2$clampFloor(ItemEntity itemEntity) {
		double floorY = lg2$getFloorY(itemEntity.level());
		if (itemEntity.getY() > floorY) {
			if (itemEntity.isNoGravity()) {
				itemEntity.setNoGravity(false);
			}
			return;
		}

		Vec3 velocity = itemEntity.getDeltaMovement();
		itemEntity.setNoGravity(true);
		itemEntity.setPos(itemEntity.getX(), floorY, itemEntity.getZ());
		itemEntity.setDeltaMovement(velocity.x, Math.max(0.0D, velocity.y), velocity.z);
	}

	private static double lg2$getFloorY(Level level) {
		ResourceKey<Level> dimension = level.dimension();
		if (Level.OVERWORLD.equals(dimension)) {
			return -64.0D;
		}
		if (Level.NETHER.equals(dimension) || Level.END.equals(dimension)) {
			return 0.0D;
		}
		return level.getMinY();
	}
}
