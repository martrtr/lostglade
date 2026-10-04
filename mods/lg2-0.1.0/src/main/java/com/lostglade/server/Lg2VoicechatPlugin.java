package com.lostglade.server;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoiceDistanceEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import net.minecraft.server.level.ServerPlayer;

public final class Lg2VoicechatPlugin implements VoicechatPlugin {

	@Override
	public String getPluginId() {
		return "lg2";
	}

	@Override
	public void initialize(VoicechatApi api) {
		ServerVoicechatIntegration.setApi(api);
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(MicrophonePacketEvent.class, event -> {
			if (shouldSuppressKilkaSalmonVoice(event)) {
				event.cancel();
				return;
			}
			SeasonStartVoiceSystem.onMicrophonePacket(event);
			if (event.isCancelled()) {
				return;
			}
			LittleDictatorVoiceSystem.onMicrophonePacket(event);
			MicrophoneSystem.onMicrophonePacket(event);
			DroneSystem.onVoicechatMicrophonePacket(event);
		});
		registration.registerEvent(VoiceDistanceEvent.class, event -> {
			SeasonStartVoiceSystem.onVoiceDistance(event);
			if (!event.isCancelled()) {
				LittleDictatorVoiceSystem.onVoiceDistance(event);
			}
		});
		registration.registerEvent(VoicechatServerStartedEvent.class, event ->
				ServerVoicechatIntegration.setServerApi(event.getVoicechat())
		);
		registration.registerEvent(VoicechatServerStoppedEvent.class, event ->
				ServerVoicechatIntegration.setServerApi(null)
		);
	}

	private static boolean shouldSuppressKilkaSalmonVoice(MicrophonePacketEvent event) {
		if (event == null || event.getSenderConnection() == null || event.getSenderConnection().getPlayer() == null) {
			return false;
		}
		Object rawPlayer = event.getSenderConnection().getPlayer().getPlayer();
		return rawPlayer instanceof ServerPlayer player && ServerRaceSystem.shouldMuteKilkaSalmonVoice(player);
	}
}
