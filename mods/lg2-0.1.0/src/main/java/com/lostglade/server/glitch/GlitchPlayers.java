package com.lostglade.server.glitch;

import com.lostglade.server.AccountAuthSystem;
import com.lostglade.server.RendererBotPresenceSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

final class GlitchPlayers {
	private GlitchPlayers() {
	}

	static List<ServerPlayer> eligiblePlayers(MinecraftServer server) {
		return AccountAuthSystem.authenticatedPlayers(server).stream()
				.filter(player -> !RendererBotPresenceSystem.isRendererBot(player))
				.toList();
	}
}
