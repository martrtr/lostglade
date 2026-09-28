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
	private static final int MIN_MAP_RENDERER_FPS = 20;
	private static final int MAX_MAP_RENDERER_FPS = 240;
	private static final int MIN_MAP_RENDERER_JOBS_PER_MINUTE = 1;
	private static final int MAX_MAP_RENDERER_JOBS_PER_MINUTE = 60;
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

	public static MapRendererMode mapRendererMode() {
		return settings.mapRendererMode;
	}

	public static synchronized void setMapRendererMode(MapRendererMode mode) {
		MapRendererMode safe = mode == null ? MapRendererMode.OFF : mode;
		if (settings.mapRendererMode == safe && Boolean.TRUE.equals(settings.mapRendererModeConfigured)) {
			return;
		}
		settings.mapRendererMode = safe;
		settings.mapRendererModeConfigured = true;
		write();
	}

	public static int mapRendererMinFps() {
		return settings.mapRendererMinFps;
	}

	public static synchronized void setMapRendererMinFps(int fps) {
		int clamped = Math.clamp(fps, MIN_MAP_RENDERER_FPS, MAX_MAP_RENDERER_FPS);
		if (settings.mapRendererMinFps == clamped) {
			return;
		}
		settings.mapRendererMinFps = clamped;
		write();
	}

	public static int mapRendererMaxJobsPerMinute() {
		return settings.mapRendererMaxJobsPerMinute;
	}

	public static synchronized void setMapRendererMaxJobsPerMinute(int jobs) {
		int clamped = Math.clamp(jobs, MIN_MAP_RENDERER_JOBS_PER_MINUTE, MAX_MAP_RENDERER_JOBS_PER_MINUTE);
		if (settings.mapRendererMaxJobsPerMinute == clamped) {
			return;
		}
		settings.mapRendererMaxJobsPerMinute = clamped;
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
		boolean changed = false;
		// Keep old configs with the former on/off switch disabled disabled after
		// migrating to the single 0..N resource limit.
		if (!value.cameraRendererEnabled && value.maxParallelCaptures > 0) {
			value.maxParallelCaptures = 0;
			changed = true;
		}
		int clamped = Math.clamp(value.maxParallelCaptures, MIN_PARALLEL_CAPTURES, MAX_PARALLEL_CAPTURES);
		if (clamped != value.maxParallelCaptures) {
			value.maxParallelCaptures = clamped;
			changed = true;
		}
		// Map workers used to default to OFF. Migrate that implicit default once so
		// every updated Lostglade client actually joins the distributed map pool.
		// Any choice made after this migration is marked explicit and is preserved.
		if (!Boolean.TRUE.equals(value.mapRendererModeConfigured)) {
			value.mapRendererMode = MapRendererMode.ALWAYS;
			value.mapRendererModeConfigured = true;
			changed = true;
		} else if (value.mapRendererMode == null) {
			value.mapRendererMode = MapRendererMode.ALWAYS;
			changed = true;
		}
		int minFps = Math.clamp(value.mapRendererMinFps, MIN_MAP_RENDERER_FPS, MAX_MAP_RENDERER_FPS);
		if (minFps != value.mapRendererMinFps) {
			value.mapRendererMinFps = minFps;
			changed = true;
		}
		int jobs = Math.clamp(value.mapRendererMaxJobsPerMinute, MIN_MAP_RENDERER_JOBS_PER_MINUTE, MAX_MAP_RENDERER_JOBS_PER_MINUTE);
		if (jobs != value.mapRendererMaxJobsPerMinute) {
			value.mapRendererMaxJobsPerMinute = jobs;
			changed = true;
		}
		return changed;
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

	public enum MapRendererMode {
		OFF,
		IDLE_ONLY,
		ALWAYS;

		public MapRendererMode next() {
			return switch (this) {
				case OFF -> IDLE_ONLY;
				case IDLE_ONLY -> ALWAYS;
				case ALWAYS -> OFF;
			};
		}
	}

	private static final class Settings {
		private boolean cameraRendererEnabled = true;
		private int maxParallelCaptures = 1;
		private MapRendererMode mapRendererMode = MapRendererMode.ALWAYS;
		private Boolean mapRendererModeConfigured;
		private int mapRendererMinFps = 50;
		private int mapRendererMaxJobsPerMinute = 60;

		private static Settings defaults() {
			Settings defaults = new Settings();
			defaults.mapRendererModeConfigured = true;
			return defaults;
		}
	}
}
