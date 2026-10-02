package com.lostglade.client;

import com.lostglade.Lg2;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.CameraType;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;

public final class RendererBotClientMode {
	private static final long RECONNECT_DELAY_MS = Long.getLong("lg2.rendererBotReconnectDelayMs", 2_000L);
	private static final long CONNECT_TIMEOUT_MS = Long.getLong("lg2.rendererBotConnectTimeoutMs", 30_000L);
	private static final String SERVER_ADDRESS = System.getProperty("lg2.rendererBotServer", "127.0.0.1:25565").trim();
	private static final String BOT_NAME = System.getProperty("lg2.rendererBotName", "RendererBot").trim();
	private static final boolean ENABLED = Boolean.getBoolean("lg2.rendererBot");
	private static final boolean HEADLESS = Boolean.getBoolean("lg2.rendererBotHeadless");
	private static final boolean VOICECHAT_LOADED = FabricLoader.getInstance().isModLoaded("voicechat");
	private static final boolean WEBCAM_LOADED = FabricLoader.getInstance().isModLoaded("webcam");

	private static boolean invalidServerAddressLogged;
	private static boolean connectInFlight;
	private static boolean muted;
	private static boolean visualsConfigured;
	private static boolean windowHidden;
	private static long connectAttemptStartedAt;
	private static long nextConnectAttemptAt;

	private RendererBotClientMode() {
	}

	public static void register() {
		if (!ENABLED) {
			return;
		}

		Lg2.LOGGER.info(
				"Renderer bot client mode enabled for '{}' -> {}{}{}{}",
				BOT_NAME,
				SERVER_ADDRESS,
				HEADLESS ? " (headless backend requested)" : "",
				VOICECHAT_LOADED ? " [voicechat]" : "",
				WEBCAM_LOADED ? " [webcam]" : ""
		);
		ClientTickEvents.END_CLIENT_TICK.register(RendererBotClientMode::onClientTick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> scheduleReconnect());
	}

	public static boolean isEnabled() {
		return ENABLED;
	}

	public static boolean useHeadlessGlfw() {
		return ENABLED && HEADLESS;
	}

	private static void onClientTick(Minecraft client) {
		if (!ENABLED) {
			return;
		}

		hideWindowIfNeeded(client);
		keepClientResponsive(client);
		ensureMuted(client);
		ensureVisualProfile(client);
		if (client.level != null || client.player != null || client.getConnection() != null) {
			return;
		}
		if (connectInFlight) {
			if (System.currentTimeMillis() - connectAttemptStartedAt < CONNECT_TIMEOUT_MS) {
				return;
			}
			connectInFlight = false;
			Lg2.LOGGER.warn("Renderer bot connect attempt timed out after {} ms, retrying", CONNECT_TIMEOUT_MS);
		}
		if (client.screen == null || client.screen instanceof ConnectScreen) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now < nextConnectAttemptAt) {
			return;
		}
		startConnecting(client);
	}

	private static void hideWindowIfNeeded(Minecraft client) {
		if (windowHidden || client.getWindow() == null) {
			return;
		}
		GLFW.glfwSetWindowPos(client.getWindow().handle(), -32_000, -32_000);
		GLFW.glfwShowWindow(client.getWindow().handle());
		windowHidden = true;
	}

	private static void keepClientResponsive(Minecraft client) {
		if (client == null || client.options == null) {
			return;
		}
		client.options.pauseOnLostFocus = false;
		client.options.framerateLimit().set(260);
		// Hidden bot windows must not fall into the vanilla "AFK" framerate throttle,
		// otherwise world renders arrive only every few seconds and video capture collapses.
		client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		client.options.enableVsync().set(false);
		client.setWindowActive(true);
	}

	private static void ensureMuted(Minecraft client) {
		if (muted || client == null || client.options == null || client.getSoundManager() == null) {
			return;
		}
		client.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0D);
		client.getSoundManager().updateCategoryVolume(SoundSource.MUSIC, 0.0F);
		if (!VOICECHAT_LOADED) {
			client.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0D);
			client.getSoundManager().updateCategoryVolume(SoundSource.MASTER, 0.0F);
			client.getSoundManager().stop();
		}
		muted = true;
	}

	private static void ensureVisualProfile(Minecraft client) {
		if (client == null || client.options == null || visualsConfigured) {
			return;
		}

		boolean changed = false;
		changed |= setIfDifferent(client.options.graphicsPreset(), GraphicsPreset.FANCY);
		changed |= setIfDifferent(client.options.cloudStatus(), CloudStatus.FANCY);
		changed |= setMin(client.options.simulationDistance(), 10);
		changed |= setMin(client.options.entityDistanceScaling(), 2.5D);
		changed |= setIfDifferent(client.options.chunkSectionFadeInTime(), 0.0D);
		changed |= setIfDifferent(client.options.particles(), ParticleStatus.ALL);
		changed |= setIfDifferent(client.options.ambientOcclusion(), true);
		changed |= setIfDifferent(client.options.prioritizeChunkUpdates(), PrioritizeChunkUpdates.NONE);
		if (client.options.getCameraType() != CameraType.FIRST_PERSON) {
			client.options.setCameraType(CameraType.FIRST_PERSON);
			changed = true;
		}
		changed |= setMin(client.options.mipmapLevels(), 4);
		changed |= setMin(client.options.biomeBlendRadius(), 2);
		changed |= setIfDifferent(client.options.bobView(), false);

		if (changed) {
			client.options.save();
			if (client.levelRenderer != null) {
				client.levelRenderer.needsUpdate();
				client.levelRenderer.allChanged();
			}
			Lg2.LOGGER.info("Renderer bot visual profile applied: fancy graphics, clouds, full particles, server-matched render distance, no chunk fade, no prioritized chunk updates");
		}
		visualsConfigured = true;
	}

	private static void startConnecting(Minecraft client) {
		if (!ServerAddress.isValidAddress(SERVER_ADDRESS)) {
			if (!invalidServerAddressLogged) {
				Lg2.LOGGER.error("Renderer bot server address '{}' is invalid", SERVER_ADDRESS);
				invalidServerAddressLogged = true;
			}
			nextConnectAttemptAt = System.currentTimeMillis() + RECONNECT_DELAY_MS;
			return;
		}

		nextConnectAttemptAt = System.currentTimeMillis() + RECONNECT_DELAY_MS;
		connectAttemptStartedAt = System.currentTimeMillis();
		connectInFlight = true;
		ServerAddress address = ServerAddress.parseString(SERVER_ADDRESS);
		ServerData serverData = new ServerData("LG2 Renderer Bot", SERVER_ADDRESS, ServerData.Type.OTHER);
		serverData.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
		ConnectScreen.startConnecting(client.screen, client, address, serverData, false, (TransferState) null);
	}

	private static void onJoin() {
		connectInFlight = false;
		connectAttemptStartedAt = 0L;
		nextConnectAttemptAt = 0L;
		muted = false;
		visualsConfigured = false;
		Lg2.LOGGER.info("Renderer bot joined {}", SERVER_ADDRESS);
	}

	private static void scheduleReconnect() {
		connectInFlight = false;
		connectAttemptStartedAt = 0L;
		muted = false;
		visualsConfigured = false;
		nextConnectAttemptAt = System.currentTimeMillis() + RECONNECT_DELAY_MS;
	}

	private static <T> boolean setIfDifferent(net.minecraft.client.OptionInstance<T> option, T value) {
		if (option == null || java.util.Objects.equals(option.get(), value)) {
			return false;
		}
		option.set(value);
		return true;
	}

	private static boolean setMin(net.minecraft.client.OptionInstance<Integer> option, int minValue) {
		if (option == null) {
			return false;
		}
		Integer current = option.get();
		int target = Math.max(current == null ? minValue : current, minValue);
		if (current != null && current == target) {
			return false;
		}
		option.set(target);
		return true;
	}

	private static boolean setMin(net.minecraft.client.OptionInstance<Double> option, double minValue) {
		if (option == null) {
			return false;
		}
		Double current = option.get();
		double target = Math.max(current == null ? minValue : current, minValue);
		if (current != null && Double.compare(current, target) == 0) {
			return false;
		}
		option.set(target);
		return true;
	}
}
