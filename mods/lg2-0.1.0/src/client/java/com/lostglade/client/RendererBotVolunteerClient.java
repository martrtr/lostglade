package com.lostglade.client;

import com.lostglade.Lg2;
import com.lostglade.network.RendererBotPayloads;
import com.lostglade.client.maprender.YandexMapRenderClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Lets a regular player donate their GPU to camera rendering without changing
 * their game mode or exposing their client as the permanent renderer bot.
 */
public final class RendererBotVolunteerClient {
	private static String lastAdmission;
	private RendererBotVolunteerClient() {
	}

	public static void register() {
		RendererVisualProfile.register();
		// The manifest identifies a Lostglade connection before changing local visuals.
		// Do not clear it from JOIN: a server payload can arrive before that event.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				RendererBotRenderEnvironmentGuard.beginServerSession());
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.getConnection() == null || !RendererBotRenderEnvironmentGuard.hasServerModManifest()) return;
			var environment = RendererBotRenderEnvironmentGuard.inspect(client);
			String admission = rendererRequested() + ":" + environment.compatible() + ":" + environment.reason();
			if (!admission.equals(lastAdmission)) sendRendererHello();
		});
		ClientPlayNetworking.registerGlobalReceiver(
				RendererBotPayloads.RendererBotServerModIdsS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					RendererBotRenderEnvironmentGuard.setServerModIds(payload.modIds());
					sendRendererHello();
					YandexMapRenderClient.requestCapabilityRefresh();
				})
		);
	}

	public static boolean isVolunteerRenderer() {
		// The dedicated hidden renderer bypasses the player's opt-in preference,
		// but never the visual-environment admission gate.
		return rendererRequested()
				&& RendererBotRenderEnvironmentGuard.inspect(net.minecraft.client.Minecraft.getInstance()).compatible();
	}

	public static void sendRendererHello() {
		if (ClientPlayNetworking.canSend(RendererBotPayloads.RendererBotHelloC2SPayload.TYPE)) {
			// Do not transiently report "not admitted" while the server-mod manifest is still in flight.
			if (rendererRequested() && !RendererBotRenderEnvironmentGuard.hasServerModManifest()) return;
			RendererBotRenderEnvironmentGuard.Compatibility environment = RendererBotRenderEnvironmentGuard.inspect(net.minecraft.client.Minecraft.getInstance());
			boolean admitted = rendererRequested() && environment.compatible();
			lastAdmission = rendererRequested() + ":" + environment.compatible() + ":" + environment.reason();
			Lg2.LOGGER.info("Renderer contribution {}: {}", admitted ? "admitted" : "not admitted", environment.reason());
			ClientPlayNetworking.send(new RendererBotPayloads.RendererBotHelloC2SPayload(
					RendererBotPayloads.PROTOCOL_VERSION,
					admitted
			));
		}
	}

	private static boolean rendererRequested() {
		return RendererBotClientMode.isEnabled() || LostgladeClientSettings.isCameraRendererEnabled();
	}

	public static void setVolunteerRendererEnabled(boolean enabled) {
		if (RendererBotClientMode.isEnabled()) return;
		LostgladeClientSettings.setCameraRendererEnabled(enabled);
		RendererVisualProfile.preferenceChanged();
		RendererBotClientCapture.onVolunteerPreferenceChanged(enabled);
		RendererBotClientVideoRecording.onVolunteerPreferenceChanged(enabled);
		sendRendererHello();
		YandexMapRenderClient.requestCapabilityRefresh();
	}

	/** Applies a changed shared render budget without discarding its percentage. */
	public static void onRenderBudgetChanged(boolean previouslyEnabled) {
		RendererVisualProfile.preferenceChanged();
		boolean enabled = LostgladeClientSettings.isRenderContributionEnabled();
		if (previouslyEnabled != enabled) {
			RendererBotClientCapture.onVolunteerPreferenceChanged(enabled);
			RendererBotClientVideoRecording.onVolunteerPreferenceChanged(enabled);
		}
		sendRendererHello();
	}

}
