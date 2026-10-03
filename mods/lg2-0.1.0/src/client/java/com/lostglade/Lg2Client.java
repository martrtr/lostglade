package com.lostglade;

import com.lostglade.client.RendererBotClientCapture;
import com.lostglade.client.RendererBotClientAudioCapture;
import com.lostglade.client.RendererBotClientMode;
import com.lostglade.client.RendererBotShadowWorldManager;
import com.lostglade.client.RendererBotClientVideoRecording;
import com.lostglade.client.RendererBotVolunteerClient;
import com.lostglade.client.LostgladeClientSettings;
import com.lostglade.client.VoiceChatCompatibilityGuard;
import com.lostglade.client.maprender.YandexMapRenderClient;
import com.lostglade.config.Lg2Config;
import com.lostglade.client.MilkPocketVoidFadeClient;
import com.lostglade.client.AccountAuthClient;
import com.lostglade.network.Lg2Payloads;
import com.lostglade.network.RendererBotPayloads;
import com.lostglade.network.YandexMapRenderPayloads;
import com.lostglade.server.CameraMediaCache;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import com.lostglade.raceclient.Lg2RaceClient;

public class Lg2Client implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Lg2Config.load();
		LostgladeClientSettings.load();
		VoiceChatCompatibilityGuard.register();
		CameraMediaCache.initialize(FabricLoader.getInstance().getGameDir());
		RendererBotPayloads.registerPayloadTypes();
		YandexMapRenderPayloads.registerPayloadTypes();
		Lg2Payloads.registerClientPayloadTypes();
		AccountAuthClient.register();
		MilkPocketVoidFadeClient.register();
		RendererBotClientMode.register();
		RendererBotVolunteerClient.register();
		YandexMapRenderClient.register();
		RendererBotShadowWorldManager.register();
		RendererBotClientCapture.register();
		RendererBotClientAudioCapture.register();
		RendererBotClientVideoRecording.register();
		new Lg2RaceClient().onInitializeClient();
		LostgladeClientSettings.registerSettingsKeyBinding();
	}
}
