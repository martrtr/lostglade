package com.lostglade.mixin;

import com.lostglade.server.AccountAuthSystem;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rejects movement and state-changing play packets until AccountAuthSystem authorizes the session. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerAccountAuthMixin {
	@Shadow public ServerPlayer player;

	@Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedMovement(ServerboundMovePlayerPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handlePlayerInput", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedInput(ServerboundPlayerInputPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedItemUse(ServerboundUseItemPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedBlockUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedInteract(ServerboundInteractPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedSlot(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedContainer(ServerboundContainerClickPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleContainerButtonClick", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedContainerButton(ServerboundContainerButtonClickPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
		if (AccountAuthSystem.shouldBlockCommand(this.player, packet.command())) ci.cancel();
	}
	@Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedSignedCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
		if (AccountAuthSystem.shouldBlockCommand(this.player, packet.command())) ci.cancel();
	}

	private void block(CallbackInfo ci) {
		if (AccountAuthSystem.shouldBlockPacket(this.player)) ci.cancel();
	}
}
