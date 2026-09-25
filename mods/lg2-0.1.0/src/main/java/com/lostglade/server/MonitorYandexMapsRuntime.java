package com.lostglade.server;

import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Yandex Maps application shell.
 *
 * The old map renderer, tile cache, LOD pipeline and interactive map runtime were
 * intentionally removed. The launcher entry remains so a replacement renderer can
 * be implemented from a clean boundary without rebuilding app registration.
 */
public final class MonitorYandexMapsRuntime {
	private MonitorYandexMapsRuntime() {
	}

	public static void register() {
		// Intentionally empty: there is no map-render runtime to register.
	}

	static void setGpsEnabled(MinecraftServer server, boolean enabled) {
		// Compatibility hook for rocket lifecycle code; there is no map runtime.
	}

	static boolean consumeMarkerTitleChatMessage(MinecraftServer server, PlayerChatMessage message, ServerPlayer sender) {
		return false;
	}
}
