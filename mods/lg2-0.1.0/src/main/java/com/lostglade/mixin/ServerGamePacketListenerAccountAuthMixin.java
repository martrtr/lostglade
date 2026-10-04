package com.lostglade.mixin;

import com.lostglade.network.Lg2Payloads;
import com.lostglade.server.AccountAuthSystem;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rejects movement and state-changing play packets until AccountAuthSystem authorizes the session. */
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 900)
public abstract class ServerGamePacketListenerAccountAuthMixin {
	private static final Identifier WEBCAM_SECRET_REQUEST = Identifier.fromNamespaceAndPath("webcam", "secret_request");
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
	@Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedVehicleMove(ServerboundMoveVehiclePacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleRecipeBookSeenRecipePacket", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedRecipeSeen(ServerboundRecipeBookSeenRecipePacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleBundleItemSelectedPacket", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedBundleSelection(ServerboundSelectBundleItemPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleRecipeBookChangeSettingsPacket", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedRecipeSettings(ServerboundRecipeBookChangeSettingsPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleSeenAdvancements", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedAdvancements(ServerboundSeenAdvancementsPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleCustomCommandSuggestions", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedSuggestions(ServerboundCommandSuggestionPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleSetCommandBlock", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedCommandBlock(ServerboundSetCommandBlockPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleSetCommandMinecart", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedCommandMinecart(ServerboundSetCommandMinecartPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handlePickItemFromBlock", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedPickBlock(ServerboundPickItemFromBlockPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handlePickItemFromEntity", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedPickEntity(ServerboundPickItemFromEntityPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleRenameItem", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedRename(ServerboundRenameItemPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleSetBeaconPacket", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedBeacon(ServerboundSetBeaconPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleSetStructureBlock", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedStructureBlock(ServerboundSetStructureBlockPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleSetTestBlock", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedTestBlock(ServerboundSetTestBlockPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleTestInstanceBlockAction", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedTestInstance(ServerboundTestInstanceBlockActionPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleSetJigsawBlock", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedJigsaw(ServerboundSetJigsawBlockPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleJigsawGenerate", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedJigsawGenerate(ServerboundJigsawGeneratePacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleSelectTrade", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedTrade(ServerboundSelectTradePacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleEditBook", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedBook(ServerboundEditBookPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleEntityTagQuery", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedEntityQuery(ServerboundEntityTagQueryPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleContainerSlotStateChanged", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedContainerState(ServerboundContainerSlotStateChangedPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleBlockEntityTagQuery", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedBlockEntityQuery(ServerboundBlockEntityTagQueryPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleTeleportToEntityPacket", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedSpectate(ServerboundTeleportToEntityPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handlePaddleBoat", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedPaddle(ServerboundPaddleBoatPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleAnimate", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedSwing(ServerboundSwingPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handlePlayerCommand", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedPlayerCommand(ServerboundPlayerCommandPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedClientCommand(ServerboundClientCommandPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedContainerClose(ServerboundContainerClosePacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handlePlaceRecipe", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedRecipePlace(ServerboundPlaceRecipePacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedCreativeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleSignUpdate", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedSign(ServerboundSignUpdatePacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handlePlayerAbilities", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedAbilities(ServerboundPlayerAbilitiesPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleChangeDifficulty", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedDifficulty(ServerboundChangeDifficultyPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleChangeGameMode", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedGameMode(ServerboundChangeGameModePacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleLockDifficulty", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedDifficultyLock(ServerboundLockDifficultyPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleDebugSubscriptionRequest", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedDebugSubscription(ServerboundDebugSubscriptionRequestPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleClientInformation", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedClientInformation(ServerboundClientInformationPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleConfigurationAcknowledged", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedConfigurationAck(ServerboundConfigurationAcknowledgedPacket packet, CallbackInfo ci) { block(ci); }
	@Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
	private void lg2$captureUnauthenticatedPasswordChat(ServerboundChatPacket packet, CallbackInfo ci) {
		if (AccountAuthSystem.consumeUnauthenticatedChat(this.player, packet.message())) ci.cancel();
	}

	@Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
		if (AccountAuthSystem.shouldBlockCommand(this.player, packet.command())) ci.cancel();
	}
	@Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedSignedCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
		if (AccountAuthSystem.shouldBlockCommand(this.player, packet.command())) ci.cancel();
	}

	@Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
	private void lg2$blockUnauthenticatedCustomPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
		if (!AccountAuthSystem.shouldBlockPacket(this.player)) return;
		if (packet != null && packet.payload() != null) {
			var payloadType = packet.payload().type();
			if (Lg2Payloads.AuthTokenC2SPayload.TYPE.equals(payloadType)
					|| WEBCAM_SECRET_REQUEST.equals(payloadType.id())) return;
		}
		if (AccountAuthSystem.deferVoicechatSecretRequest(this.player, packet)) {
			ci.cancel();
			return;
		}
		ci.cancel();
	}

	private void block(CallbackInfo ci) {
		if (AccountAuthSystem.shouldBlockPacket(this.player)) ci.cancel();
	}
}
