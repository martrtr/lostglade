package com.lostglade.mixin;

import com.lostglade.server.AccountAuthSystem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

@Mixin(PlayerList.class)
public abstract class PlayerListRendererBotSystemMessageMixin {
	@Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), cancellable = true)
	private void lg2$suppressRendererBotSystemMessage(Component message, boolean overlay, CallbackInfo ci) {
		if (AccountAuthSystem.shouldSuppressPresenceSystemMessage(message)) {
			ci.cancel();
		}
	}

	@Inject(
			method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void lg2$suppressRendererBotSystemMessageWithTransformer(
			Component message,
			Function<ServerPlayer, Component> transformer,
			boolean overlay,
			CallbackInfo ci) {
		if (AccountAuthSystem.shouldSuppressPresenceSystemMessage(message)) {
			ci.cancel();
		}
	}
}
