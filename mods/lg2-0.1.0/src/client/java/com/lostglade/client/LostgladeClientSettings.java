package com.lostglade.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Local-only preferences for a player who contributes GPU time to cameras. */
public final class LostgladeClientSettings {
	private static final int MIN_PARALLEL_CAPTURES = 0;
	private static final int MAX_PARALLEL_CAPTURES = 4;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("lostglade-client.json");
	private static Settings settings = Settings.defaults();

	private LostgladeClientSettings() {
	}

	public static synchronized void load() {
		Settings loaded = Settings.defaults();
		if (Files.isRegularFile(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				Settings parsed = GSON.fromJson(reader, Settings.class);
				if (parsed != null) {
					loaded = parsed;
				}
			} catch (Exception exception) {
				Lg2.LOGGER.warn("Failed to read Lostglade client settings {}, restoring defaults", PATH, exception);
			}
		}
		boolean changed = sanitize(loaded);
		settings = loaded;
		if (changed || !Files.exists(PATH)) {
			write();
		}
	}

	public static boolean isCameraRendererEnabled() {
		return settings.maxParallelCaptures > 0;
	}

	public static synchronized void setCameraRendererEnabled(boolean enabled) {
		setMaxParallelCaptures(enabled ? Math.max(1, settings.maxParallelCaptures) : 0);
	}

	public static int maxParallelCaptures() {
		return settings.maxParallelCaptures;
	}

	public static synchronized void setMaxParallelCaptures(int value) {
		int clamped = Math.clamp(value, MIN_PARALLEL_CAPTURES, MAX_PARALLEL_CAPTURES);
		if (settings.maxParallelCaptures == clamped) {
			return;
		}
		settings.maxParallelCaptures = clamped;
		settings.cameraRendererEnabled = clamped > 0;
		write();
	}

	public static void registerOptionsScreen() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof OptionsScreen)) {
				return;
			}
			Screens.getButtons(screen).add(Button.builder(
					Component.literal("Lostglade"),
					button -> client.setScreen(new LostgladeSettingsScreen(screen))
			).bounds(width / 2 - 155, height - 52, 150, 20).build());
		});
	}

	private static boolean sanitize(Settings value) {
		// Keep old configs with the former on/off switch disabled disabled after
		// migrating to the single 0..N resource limit.
		if (!value.cameraRendererEnabled && value.maxParallelCaptures > 0) {
			value.maxParallelCaptures = 0;
			return true;
		}
		int clamped = Math.clamp(value.maxParallelCaptures, MIN_PARALLEL_CAPTURES, MAX_PARALLEL_CAPTURES);
		if (clamped == value.maxParallelCaptures) {
			return false;
		}
		value.maxParallelCaptures = clamped;
		return true;
	}

	private static void write() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(settings, writer);
			}
		} catch (IOException exception) {
			Lg2.LOGGER.error("Failed to save Lostglade client settings {}", PATH, exception);
		}
	}

	private static final class Settings {
		private boolean cameraRendererEnabled = true;
		private int maxParallelCaptures = 1;

		private static Settings defaults() {
			return new Settings();
		}
	}
}
