package com.lostglade.server;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.api.events.VoiceDistanceEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.plugins.impl.packets.LocationalSoundPacketImpl;
import de.maxhenkel.voicechat.voice.common.LocationSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
			SeasonStartVoiceSystem.onMicrophonePacket(event);
			if (event.isCancelled()) {
				return;
			}
			LittleDictatorVoiceSystem.onMicrophonePacket(event);
			MicrophoneSystem.onMicrophonePacket(event);
			DroneSystem.onVoicechatMicrophonePacket(event);
		});
		registration.registerEvent(EntitySoundPacketEvent.class, Lg2VoicechatPlugin::routeKilkaSalmonVoice);
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

	private static void routeKilkaSalmonVoice(EntitySoundPacketEvent event) {
		if (event == null || event.isCancelled()
				|| !SoundPacketEvent.SOURCE_PROXIMITY.equals(event.getSource())
				|| event.getPacket() == null || event.getSenderConnection() == null
				|| event.getSenderConnection().getPlayer() == null
				|| event.getReceiverConnection() == null || event.getReceiverConnection().getPlayer() == null) return;
		UUID senderId = event.getPacket().getSender();
		if (!ServerRaceSystem.isKilkaSalmonVoiceSender(senderId)) return;
		Object rawPlayer = event.getSenderConnection().getPlayer().getPlayer();
		if (!(rawPlayer instanceof ServerPlayer sender) || !sender.getUUID().equals(senderId)) return;
		MinecraftServer server = sender.level().getServer();
		VoicechatServerApi voicechat = event.getVoicechat();
		byte[] encoded = event.getPacket().getOpusEncodedData();
		if (server == null || voicechat == null || encoded == null || encoded.length == 0) return;
		UUID receiverId = event.getReceiverConnection().getPlayer().getUuid();
		UUID channelId = UUID.nameUUIDFromBytes(("lg2:kilka_salmon_voice:" + senderId).getBytes(StandardCharsets.UTF_8));
		long sequence = event.getPacket().getSequenceNumber();
		float distance = event.getPacket().getDistance();
		String category = event.getPacket().getCategory();
		byte[] audio = encoded.clone();
		if (!event.cancel()) return;
		server.execute(() -> {
			ServerPlayer currentSender = server.getPlayerList().getPlayer(senderId);
			VoicechatConnection receiver = voicechat.getConnectionOf(receiverId);
			if (currentSender == null || receiver == null || !receiver.isConnected() || receiver.isDisabled()) return;
			Vec3 origin = ServerRaceSystem.getKilkaSalmonVoiceOrigin(currentSender);
			if (origin == null) origin = currentSender.getEyePosition();
			// The hidden player entity cannot anchor SVC audio on clients; the salmon can.
			voicechat.sendLocationalSoundPacketTo(receiver, new LocationalSoundPacketImpl(
					new LocationSoundPacket(channelId, senderId, origin, audio, sequence, distance, category)
			));
		});
	}
}
