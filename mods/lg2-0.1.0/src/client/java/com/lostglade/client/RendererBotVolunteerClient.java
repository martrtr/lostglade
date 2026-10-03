package com.lostglade.client;

import com.lostglade.Lg2;
import com.lostglade.network.RendererBotPayloads;
import com.lostglade.client.maprender.YandexMapRenderClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Lets a regular player donate their GPU to camera rendering without changing
 * their game mode or exposing their client as the permanent renderer bot.
 */
public final class RendererBotVolunteerClient {
	private RendererBotVolunteerClient() {
	}

	public static void register() {
		// A renderer is admitted only after the server tells us which mod IDs it also has.
		// Matching is ID-only by design: client/server version differences are irrelevant here.
		// New processes start with no manifest, and DISCONNECT clears the old session.
		// Do not clear it from JOIN: the server may send its manifest during its own
		// JOIN callback before Fabric fires the client JOIN event. Clearing here caused
		// a race where valid shared mods (for example voicechat_api) became "third-party".
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				RendererBotRenderEnvironmentGuard.beginServerSession());
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
