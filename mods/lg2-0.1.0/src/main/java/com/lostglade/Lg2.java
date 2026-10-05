package com.lostglade;

import com.lostglade.block.ModBlocks;
import com.lostglade.config.Lg2Config;
import com.lostglade.config.RaceConfig;
import com.lostglade.config.SeasonStartConfig;
import com.lostglade.config.UpgradeUiLayoutMigration;
import com.lostglade.network.Lg2Payloads;
import com.lostglade.network.RendererBotPayloads;
import com.lostglade.network.YandexMapRenderPayloads;
import com.lostglade.server.ServerGlitchSystem;
import com.lostglade.server.ServerBackroomsSystem;
import com.lostglade.server.ServerMilkPocketDimensionSystem;
import com.lostglade.server.ServerBackroomsBlockBreakSystem;
import com.lostglade.server.ServerBackroomsStalkerSystem;
import com.lostglade.server.BluetoothLinkSystem;
import com.lostglade.server.RendererBotCameraSystem;
import com.lostglade.server.RendererModAllowlist;
import com.lostglade.server.RendererBotPresenceSystem;
import com.lostglade.server.RendererBotProcessSystem;
import com.lostglade.server.RainbowHarnessColorSystem;
import com.lostglade.server.SpeakerSystem;
import com.lostglade.item.ModItems;
import com.lostglade.item.ModRecipeSerializers;
import com.lostglade.server.ServerAbsoluteInvisibilitySystem;
import com.lostglade.server.ServerBossBarVisibilitySystem;
import com.lostglade.server.ServerWaypointVisibilitySystem;
import com.lostglade.server.CameraAnimatedMapPlaybackSystem;
import com.lostglade.server.CameraCaptureSystem;
import com.lostglade.server.CameraMediaCache;
import com.lostglade.server.CameraOrientationStore;
import com.lostglade.server.CameraRelocationSystem;
import com.lostglade.server.CameraVideoRecordingSystem;
import com.lostglade.server.CocaineHallucinationSystem;
import com.lostglade.server.CopperManGogglesSystem;
import com.lostglade.server.CopperManRepulsorSystem;
import com.lostglade.server.BrownBedDisplaySystem;
import com.lostglade.server.DroneSystem;
import com.lostglade.server.MonitorSupportRuntime;
import com.lostglade.server.MonitorYandexMapsRuntime;
import com.lostglade.server.MonitorScreenSystem;
import com.lostglade.server.RocketLaunchEventSystem;
import com.lostglade.server.MicrophoneSystem;
import com.lostglade.server.PlacedDeviceNameStore;
import com.lostglade.server.PhotoFramePlacementSystem;
import com.lostglade.server.map.MapImageRenderSystem;
import com.lostglade.server.maprender.YandexMapRenderService;
import com.lostglade.server.maprender.MapRenderWorkerRegistry;
import com.lostglade.server.maprender.MapRenderJobService;
import com.lostglade.server.maprender.MapRenderScheduler;
import com.lostglade.server.maprender.MapPyramidBuilder;
import com.lostglade.server.ServerStabilitySystem;
import com.lostglade.server.ServerStructureBreakSystem;
import com.lostglade.server.ServerMechanicsGateSystem;
import com.lostglade.server.ServerRespectSystem;
import com.lostglade.server.ServerRaceSystem;
import com.lostglade.server.PuroSanStockSystem;
import com.lostglade.server.OrthodoxAttackSystem;
import com.lostglade.server.OrthodoxDefenseSystem;
import com.lostglade.server.OrthodoxStockSystem;
import com.lostglade.server.NecromancerDefenseSystem;
import com.lostglade.server.NecromancerStockSystem;
import com.lostglade.server.NecromancerShnyagaSystem;
import com.lostglade.server.NecromancerUniqueSystem;
import com.lostglade.server.OrthodoxUniqueSystem;
import com.lostglade.server.OrthodoxHolinessSystem;
import com.lostglade.server.StartupRaceAbilitySystem;
import com.lostglade.server.SeasonStartSystem;
import com.lostglade.server.SeasonStartVoiceSystem;
import com.lostglade.server.ServerTabIntegration;
import com.lostglade.server.ServerSelectionHighlightSystem;
import com.lostglade.server.ServerTrojanRoosterSystem;
import com.lostglade.server.ServerUnusedMobSpawnSystem;
import com.lostglade.server.ServerUpgradeUiSystem;
import com.lostglade.server.ServerVoicechatIntegration;
import com.lostglade.server.ServerWebcamIntegration;
import com.lostglade.server.YandexMapMarkerStore;
import com.lostglade.server.AccountAuthSystem;
import com.lostglade.worldgen.ModWorldGen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Lg2 implements ModInitializer {
	public static final String MOD_ID = "lg2";
	public static final String CONTENT_NAMESPACE = "lostglade";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// The client companion contains its own entrypoint.  Do not load server-only systems
		// (and their server-side integrations) into a regular player client.
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
			return;
		}
		Lg2Config.load();
		com.lostglade.server.DailyMaintenanceSystem.register();
		SeasonStartConfig.load();
		CameraMediaCache.initialize(FabricLoader.getInstance().getGameDir());
		AccountAuthSystem.preflightServerProperties();
		RendererBotProcessSystem.preflightServerProperties();
		RaceConfig.load();
		PolymerResourcePackUtils.addModAssets(MOD_ID);
		RendererBotPayloads.registerPayloadTypes();
		YandexMapRenderPayloads.registerPayloadTypes();
		Lg2Payloads.registerPayloadTypes();
		AccountAuthSystem.register();

		ModRecipeSerializers.register();
		ModItems.register();
		ModBlocks.register();
		OrthodoxDefenseSystem.register();
		CameraOrientationStore.register();
		PlacedDeviceNameStore.register();
		CameraRelocationSystem.register();
		YandexMapMarkerStore.register();
		YandexMapRenderService.register();
		MapRenderWorkerRegistry.register();
		MapRenderJobService.register();
		MapRenderScheduler.register();
		MapPyramidBuilder.register();
		ModWorldGen.register();
		BrownBedDisplaySystem.register();
		RainbowHarnessColorSystem.register();
		ServerBossBarVisibilitySystem.register();
		ServerWaypointVisibilitySystem.register();
		MapImageRenderSystem.register();
		CameraCaptureSystem.register();
		CameraVideoRecordingSystem.register();
		CameraAnimatedMapPlaybackSystem.register();
		DroneSystem.register();
		RendererModAllowlist.register();
		RendererBotCameraSystem.register();
		RendererBotPresenceSystem.register();
		RendererBotProcessSystem.register();
		CocaineHallucinationSystem.register();
		CopperManGogglesSystem.register();
		CopperManRepulsorSystem.register();
		ServerSelectionHighlightSystem.register();
		BluetoothLinkSystem.register();
		MonitorScreenSystem.register();
		MonitorYandexMapsRuntime.register();
		RocketLaunchEventSystem.register();
		MonitorSupportRuntime.register();
		PhotoFramePlacementSystem.register();
		ServerStabilitySystem.register();
		ServerTrojanRoosterSystem.register();
		ServerAbsoluteInvisibilitySystem.register();
		ServerBackroomsSystem.register();
		ServerMilkPocketDimensionSystem.register();
		ServerBackroomsBlockBreakSystem.register();
		ServerBackroomsStalkerSystem.register();
		ServerStructureBreakSystem.register();
		SeasonStartSystem.register();
		ServerGlitchSystem.register();
		ServerMechanicsGateSystem.register();
		ServerRespectSystem.register();
		CopperManGogglesSystem.registerLateInteractions();
		CopperManRepulsorSystem.registerLateInteractions();
		ServerRaceSystem.register();
		OrthodoxHolinessSystem.register();
		PuroSanStockSystem.register();
		OrthodoxStockSystem.register();
		NecromancerStockSystem.register();
		NecromancerDefenseSystem.register();
		NecromancerUniqueSystem.register();
		NecromancerShnyagaSystem.register();
		OrthodoxAttackSystem.register();
		OrthodoxUniqueSystem.register();
		StartupRaceAbilitySystem.register();
		ServerUnusedMobSpawnSystem.register();
		SeasonStartVoiceSystem.register();
		if (FabricLoader.getInstance().isModLoaded("tab")) {
			ServerTabIntegration.register();
		}
		if (FabricLoader.getInstance().isModLoaded("voicechat")) {
			ServerVoicechatIntegration.register();
			SpeakerSystem.register();
			MicrophoneSystem.register();
		}
		if (FabricLoader.getInstance().isModLoaded("webcam")) {
			ServerWebcamIntegration.register();
		}
		ServerUpgradeUiSystem.register();
		UpgradeUiLayoutMigration.apply();

		LOGGER.info("Initialized {}", MOD_ID);
	}
}
