package com.lostglade;

import com.lostglade.client.RendererBotClientCapture;
import com.lostglade.client.RendererBotClientAudioCapture;
import com.lostglade.client.RendererBotClientMode;
import com.lostglade.client.RendererBotShadowWorldManager;
import com.lostglade.client.RendererBotClientVideoRecording;
import com.lostglade.client.RendererBotVolunteerClient;
import com.lostglade.config.Lg2Config;
import com.lostglade.client.MilkPocketVoidFadeClient;
import com.lostglade.network.Lg2Payloads;
import com.lostglade.network.RendererBotPayloads;
import com.lostglade.server.CameraMediaCache;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class Lg2Client implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Lg2Config.load();
		CameraMediaCache.initialize(FabricLoader.getInstance().getGameDir());
		RendererBotPayloads.registerPayloadTypes();
		Lg2Payloads.registerPayloadTypes();
		MilkPocketVoidFadeClient.register();
		RendererBotClientMode.register();
		RendererBotVolunteerClient.register();
		RendererBotShadowWorldManager.register();
		RendererBotClientCapture.register();
		RendererBotClientAudioCapture.register();
		RendererBotClientVideoRecording.register();
	}
}
