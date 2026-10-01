package com.lostglade.mixin;

import com.lostglade.server.ServerMechanicsGateSystem;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerLittleDictatorLagMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void lg2$guardIronDoorAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (player.level().getServer().isSameThread()
                && ServerMechanicsGateSystem.handleLittleDictatorIronDoorPlayerAction(player, packet)) ci.cancel();
    }
}