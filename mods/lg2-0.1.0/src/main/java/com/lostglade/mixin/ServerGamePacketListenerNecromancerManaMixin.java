package com.lostglade.mixin;

import com.lostglade.server.NecromancerStockSystem;
import com.lostglade.server.NecromancerUniqueSystem;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerNecromancerManaMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
	private void lg2$chargeNecromancerPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
		if (packet == null || !NecromancerStockSystem.isActive(this.player)) return;
		switch (packet.getAction()) {
			case START_DESTROY_BLOCK -> {
				if (!NecromancerStockSystem.tryStartBlockBreaking(this.player, packet.getPos(), packet.getDirection())) {
					this.player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(
							this.player.level(), packet.getPos()));
					ci.cancel();
				}
			}
			case ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> NecromancerStockSystem.stopBlockBreaking(this.player);
			case STAB -> {
				if (!NecromancerStockSystem.tryAttackAction(this.player)) ci.cancel();
			}
			default -> {
			}
		}
	}

	@Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
	private void lg2$chargeNecromancerUseOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
		if (NecromancerStockSystem.isActive(this.player)
				&& !NecromancerStockSystem.canAttemptHandAction(this.player, packet.getHand())) {
			this.player.connection.send(new net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket(
					this.player.level(), packet.getHitResult().getBlockPos()));
			this.player.containerMenu.broadcastFullState();
			ci.cancel();
		}
	}

	@Inject(method = "handleUseItemOn", at = @At("TAIL"))
	private void lg2$commandNecromancerSpiritOnBlockUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
		NecromancerUniqueSystem.onRightClick(this.player);
	}

	@Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
	private void lg2$chargeNecromancerUse(ServerboundUseItemPacket packet, CallbackInfo ci) {
		if (NecromancerStockSystem.isActive(this.player)
				&& !NecromancerStockSystem.canAttemptHandAction(this.player, packet.getHand())) {
			this.player.containerMenu.broadcastFullState();
			ci.cancel();
		}
	}

	@Inject(method = "handleUseItem", at = @At("TAIL"))
	private void lg2$commandNecromancerSpiritOnItemUse(ServerboundUseItemPacket packet, CallbackInfo ci) {
		NecromancerUniqueSystem.onRightClick(this.player);
	}

	@Inject(method = "handleInteract", at = @At("TAIL"))
	private void lg2$commandNecromancerSpiritOnEntityUse(ServerboundInteractPacket packet, CallbackInfo ci) {
		NecromancerUniqueSystem.onRightClick(this.player);
	}

	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void lg2$syncNecromancerSpiritAirClickTrigger(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
		NecromancerUniqueSystem.handleMovePacket(this.player);
	}

}
