package com.lostglade.mixin;

import com.lostglade.server.ServerRaceSystem;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public abstract class PacketProcessorLittleDictatorLagMixin {
	@Shadow @Final private PacketListener listener;
	@Shadow @Final private Packet<?> packet;

	@Inject(method = "handle", at = @At("HEAD"), cancellable = true)
	private void lg2$delayOrderedGameplayPacket(CallbackInfo ci) {
		if (!(listener instanceof ServerGamePacketListenerImpl gameListener)
				|| !packet.getClass().getName().startsWith("net.minecraft.network.protocol.game.")) return;
		if (ServerRaceSystem.delayLittleDictatorServerboundPacket(gameListener.player, this::lg2$replay)) ci.cancel();
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void lg2$replay() {
		if (!listener.shouldHandleMessage(packet)) return;
		try {
			((Packet) packet).handle(listener);
		} catch (Exception exception) {
			listener.onPacketError(packet, exception);
		}
	}
}
