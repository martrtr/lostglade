package com.lostglade.server.glitch;

import com.lostglade.config.GlitchConfig;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import java.util.Collection;

public interface ChatMessageGlitchHandler extends ServerGlitchHandler {
	boolean triggerChat(
			MinecraftServer server,
			RandomSource random,
			GlitchConfig.GlitchEntry entry,
			double stabilityPercent,
			ServerPlayer sender,
			PlayerChatMessage message,
			ChatType.Bound params,
			boolean routeCreditor
	);

	default boolean triggerPrivateMessage(
			MinecraftServer server,
			RandomSource random,
			GlitchConfig.GlitchEntry entry,
			double stabilityPercent,
			CommandSourceStack source,
			ServerPlayer sender,
			Collection<ServerPlayer> targets,
			PlayerChatMessage message
	) {
		return false;
	}
}
