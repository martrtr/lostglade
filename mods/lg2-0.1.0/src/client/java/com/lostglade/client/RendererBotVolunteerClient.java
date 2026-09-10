package com.lostglade.client;

import com.lostglade.config.Lg2Config;
import com.lostglade.network.RendererBotPayloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Lets a regular player donate their GPU to camera rendering without changing
 * their game mode or exposing their client as the permanent renderer bot.
 */
public final class RendererBotVolunteerClient {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath("lg2", "camera")
	);
	private static final KeyMapping TOGGLE = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.lg2.camera_renderer_volunteer",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_F8,
			CATEGORY
	));

	private RendererBotVolunteerClient() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(RendererBotVolunteerClient::onClientTick);
	}

	public static boolean isVolunteerRenderer() {
		// The dedicated hidden renderer always remains available as the fallback,
		// regardless of the opt-in preference in its local config file.
		return RendererBotClientMode.isEnabled() || Lg2Config.get().cameraRendererVolunteerEnabled;
	}

	public static void sendRendererHello() {
		if (ClientPlayNetworking.canSend(RendererBotPayloads.RendererBotHelloC2SPayload.TYPE)) {
			ClientPlayNetworking.send(new RendererBotPayloads.RendererBotHelloC2SPayload(
					RendererBotPayloads.PROTOCOL_VERSION,
					isVolunteerRenderer()
			));
		}
	}

	private static void onClientTick(Minecraft client) {
		while (TOGGLE.consumeClick()) {
			if (RendererBotClientMode.isEnabled()) {
				return;
			}
			boolean enabled = !Lg2Config.get().cameraRendererVolunteerEnabled;
			Lg2Config.setCameraRendererVolunteerEnabled(enabled);
			sendRendererHello();
			if (client != null && client.player != null) {
				client.player.displayClientMessage(Component.translatable(
						enabled ? "message.lg2.camera_renderer_volunteer.enabled" : "message.lg2.camera_renderer_volunteer.disabled"
				), true);
			}
		}
	}
}
