package com.lostglade.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Local-only preferences for Lostglade's client features. */
public final class LostgladeClientSettings {
	private static final int DEFAULT_RENDER_BUDGET_PERCENT = 100;
	private static final int MAX_PARALLEL_CAPTURES = 4;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("lostglade-client.json");
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath("lg2", "lostglade")
	);
	private static final KeyMapping OPEN_SETTINGS = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.lg2.open_settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY
	));
	private static Settings settings = Settings.defaults();

	private LostgladeClientSettings() {
	}

	public static synchronized void load() {
		Settings loaded = Settings.defaults();
		if (Files.isRegularFile(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				Settings parsed = GSON.fromJson(reader, Settings.class);
				if (parsed != null) loaded = parsed;
			} catch (Exception exception) {
				Lg2.LOGGER.warn("Failed to read Lostglade client settings {}, restoring defaults", PATH, exception);
			}
		}
		boolean changed = sanitize(loaded);
		settings = loaded;
		if (changed || !Files.exists(PATH)) write();
	}

	/** One shared limit for background camera and map work, from 0 to 100 percent. */
	public static int renderBudgetPercent() {
		return settings.renderBudgetPercent;
	}

	/** The common Controls-screen category used by Lostglade client bindings. */
	public static KeyMapping.Category keyCategory() {
		return CATEGORY;
	}

	public static synchronized void setRenderBudgetPercent(int percent) {
		int clamped = Math.clamp(percent, 0, 100);
		if (settings.renderBudgetPercent == clamped) return;
		settings.renderBudgetPercent = clamped;
		write();
	}

	public static boolean isRenderContributionEnabled() {
		return settings.renderBudgetPercent > 0;
	}

	/** Compatibility for existing camera code; camera work has priority over map work. */
	public static boolean isCameraRendererEnabled() {
		return isRenderContributionEnabled();
	}

	public static synchronized void setCameraRendererEnabled(boolean enabled) {
		setRenderBudgetPercent(enabled
				? (settings.renderBudgetPercent > 0 ? settings.renderBudgetPercent : DEFAULT_RENDER_BUDGET_PERCENT)
				: 0);
	}

	/** Converts the percentage into the existing 1..4 camera frame capacity. */
	public static int maxParallelCaptures() {
		return Math.max(1, (int) Math.ceil(renderBudgetPercent() * MAX_PARALLEL_CAPTURES / 100.0D));
	}

	/** Limits map offers with the same common percentage as camera rendering. */
	public static int maxMapJobsPerMinute() {
		return Math.max(1, (int) Math.ceil(renderBudgetPercent() * 60 / 100.0D));
	}

	public static boolean isRaceMenuEnabled() {
		return settings.raceMenuEnabled;
	}

	public static synchronized void setRaceMenuEnabled(boolean enabled) {
		if (settings.raceMenuEnabled == enabled) return;
		settings.raceMenuEnabled = enabled;
		write();
	}

	public static boolean isDroneTiltEnabled() {
		return settings.droneTiltEnabled;
	}

	public static synchronized void setDroneTiltEnabled(boolean enabled) {
		if (settings.droneTiltEnabled == enabled) return;
		settings.droneTiltEnabled = enabled;
		write();
	}

	public static float droneTiltStrength() {
		return settings.droneTiltStrength;
	}

	public static synchronized void setDroneTiltStrength(float strength) {
		float clamped = Math.clamp(Float.isFinite(strength) ? strength : 1.0F, 0.0F, 1.0F);
		if (Float.compare(settings.droneTiltStrength, clamped) == 0) return;
		settings.droneTiltStrength = clamped;
		write();
	}

	/** Settings are intentionally reachable only by this key binding and Mod Menu. */
	public static void registerSettingsKeyBinding() {
		ClientTickEvents.END_CLIENT_TICK.register(LostgladeClientSettings::openSettingsWhenRequested);
	}

	private static void openSettingsWhenRequested(Minecraft client) {
		while (OPEN_SETTINGS.consumeClick()) {
			if (client != null && !(client.screen instanceof LostgladeSettingsScreen)) {
				client.setScreen(new LostgladeSettingsScreen(client.screen));
			}
		}
	}

	private static boolean sanitize(Settings value) {
		boolean changed = false;
		// Pre-UI-rework files kept camera and map limits separately. Preserve an
		// explicit opt-out; otherwise migrate them to the new shared default.
		if (value.renderBudgetPercent == null) {
			value.renderBudgetPercent = !value.cameraRendererEnabled && value.mapRendererMode == MapRendererMode.OFF
					? 0 : DEFAULT_RENDER_BUDGET_PERCENT;
			changed = true;
		}
		int budget = Math.clamp(value.renderBudgetPercent, 0, 100);
		if (budget != value.renderBudgetPercent) {
			value.renderBudgetPercent = budget;
			changed = true;
		}
		float tiltStrength = Math.clamp(Float.isFinite(value.droneTiltStrength) ? value.droneTiltStrength : 1.0F, 0.0F, 1.0F);
		if (Float.compare(tiltStrength, value.droneTiltStrength) != 0) {
			value.droneTiltStrength = tiltStrength;
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

	/** Retained solely to read older local configuration files safely. */
	private enum MapRendererMode { OFF, IDLE_ONLY, ALWAYS }

	private static final class Settings {
		private Integer renderBudgetPercent;
		private boolean cameraRendererEnabled = false;
		private MapRendererMode mapRendererMode = MapRendererMode.ALWAYS;
		private boolean raceMenuEnabled = true;
		private boolean droneTiltEnabled = true;
		private float droneTiltStrength = 1.0F;

		private static Settings defaults() {
			Settings defaults = new Settings();
			defaults.renderBudgetPercent = DEFAULT_RENDER_BUDGET_PERCENT;
			return defaults;
		}
	}
}
