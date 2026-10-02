package com.lostglade.client;

import com.lostglade.network.RendererBotPayloads;
import com.lostglade.client.maprender.YandexMapRenderClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Lets a regular player donate their GPU to camera rendering without changing
 * their game mode or exposing their client as the permanent renderer bot.
 */
public final class RendererBotVolunteerClient {
	private RendererBotVolunteerClient() {
	}

	public static void register() {
		// Rendering is controlled by the shared percentage in Lostglade settings.
		// Admission is checked on hello and again immediately before every job;
		// do not repeatedly send hello packets while a resource reload is settling.
	}

	public static boolean isVolunteerRenderer() {
		// The dedicated hidden renderer bypasses the player's opt-in preference,
		// but never the visual-environment admission gate.
		return (RendererBotClientMode.isEnabled() || LostgladeClientSettings.isCameraRendererEnabled())
				&& RendererBotRenderEnvironmentGuard.inspect(net.minecraft.client.Minecraft.getInstance()).compatible();
	}

	public static void sendRendererHello() {
		if (ClientPlayNetworking.canSend(RendererBotPayloads.RendererBotHelloC2SPayload.TYPE)) {
			ClientPlayNetworking.send(new RendererBotPayloads.RendererBotHelloC2SPayload(
					RendererBotPayloads.PROTOCOL_VERSION,
					isVolunteerRenderer()
			));
		}
	}

	public static void setVolunteerRendererEnabled(boolean enabled) {
		if (RendererBotClientMode.isEnabled()) return;
		LostgladeClientSettings.setCameraRendererEnabled(enabled);
		RendererBotClientCapture.onVolunteerPreferenceChanged(enabled);
		RendererBotClientVideoRecording.onVolunteerPreferenceChanged(enabled);
		sendRendererHello();
		YandexMapRenderClient.requestCapabilityRefresh();
	}

	/** Applies a changed shared render budget without discarding its percentage. */
	public static void onRenderBudgetChanged(boolean previouslyEnabled) {
		boolean enabled = LostgladeClientSettings.isRenderContributionEnabled();
		if (previouslyEnabled != enabled) {
			RendererBotClientCapture.onVolunteerPreferenceChanged(enabled);
			RendererBotClientVideoRecording.onVolunteerPreferenceChanged(enabled);
		}
		sendRendererHello();
	}

}
