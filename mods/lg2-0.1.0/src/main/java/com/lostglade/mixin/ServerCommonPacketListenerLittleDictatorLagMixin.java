package com.lostglade.mixin;

import com.lostglade.server.ServerRaceSystem;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerLittleDictatorLagMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void lg2$delayLittleDictatorClientbound(Packet<?> packet, CallbackInfo ci) {
		lg2$delayLittleDictatorClientbound(packet, null, ci);
	}

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
	private void lg2$delayLittleDictatorClientboundWithListener(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
		lg2$delayLittleDictatorClientbound(packet, listener, ci);
	}

	@Unique
	private void lg2$delayLittleDictatorClientbound(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
		if (packet == null || ci == null || lg2$isLittleDictatorLatencyControlPacket(packet)) {
			return;
		}
		Object self = this;
		if (!(self instanceof ServerGamePacketListenerImpl gameListener)) {
			return;
		}
		ServerPlayer receiver = gameListener.player;
		if (receiver == null) {
			return;
		}
		Runnable replay = () -> {
			if (listener == null) {
				gameListener.send(packet);
			} else {
				gameListener.send(packet, listener);
			}
		};
		if (ServerRaceSystem.delayLittleDictatorClientboundPacket(receiver, packet, replay)) {
			ci.cancel();
		}
	}

	@Unique
	private static boolean lg2$isLittleDictatorLatencyControlPacket(Packet<?> packet) {
		String className = packet.getClass().getName();
		if (!className.startsWith("net.minecraft.network.protocol.game.")) {
			return true;
		}
		return packet instanceof ClientboundPlayerInfoUpdatePacket
				|| packet instanceof ClientboundSetTitleTextPacket
				|| packet instanceof ClientboundSetSubtitleTextPacket
				|| packet instanceof ClientboundSetTitlesAnimationPacket
				|| packet instanceof ClientboundLoginPacket
				|| packet instanceof ClientboundRespawnPacket;
	}
}
