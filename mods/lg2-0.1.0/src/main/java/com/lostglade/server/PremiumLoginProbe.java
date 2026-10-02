package com.lostglade.server;

import net.minecraft.network.protocol.login.ServerboundHelloPacket;

/** Per-connection state for the optional premium session handshake. */
public record PremiumLoginProbe(ServerboundHelloPacket hello) {
}
