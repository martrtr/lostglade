package com.lostglade.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.lostglade.Lg2;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.RandomSource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Lg2Config {
	private static final int MAX_ORES_PER_CHUNK = 128;
	private static final int MAX_VEIN_SIZE = 64;
	private static final int MAX_DROP = 64;
	private static final int MAX_XP = 100;
	private static final int MIN_WORLD_Y = -64;
	private static final int MAX_WORLD_Y = 320;
	private static final int MIN_STABILITY_MAX = 1;
	private static final int MAX_STABILITY_MAX = 1_000_000;
	private static final int MIN_STABILITY_DECAY_INTERVAL_SECONDS = 1;
	private static final int MAX_STABILITY_DECAY_INTERVAL_SECONDS = 2_592_000;
	private static final int MIN_STABILITY_DECAY_INTERVAL_SECONDS_PER_PLAYER = 0;
	private static final int MAX_STABILITY_DECAY_INTERVAL_SECONDS_PER_PLAYER = 2_592_000;
	private static final int MIN_BACKROOMS_ENTITY_SPAWN_MIN_RADIUS_CHUNKS = 0;
	private static final int MAX_BACKROOMS_ENTITY_SPAWN_MIN_RADIUS_CHUNKS = 64;
	private static final int MIN_BACKROOMS_ENTITY_SPAWN_MAX_RADIUS_CHUNKS = 1;
	private static final int MAX_BACKROOMS_ENTITY_SPAWN_MAX_RADIUS_CHUNKS = 64;
	private static final int MIN_BACKROOMS_ENTITY_GROUP_RADIUS_CHUNKS = 1;
	private static final int MAX_BACKROOMS_ENTITY_GROUP_RADIUS_CHUNKS = 32;
	private static final int MIN_BACKROOMS_SPECIAL_ROOM_WEIGHT = 0;
	private static final int MAX_BACKROOMS_SPECIAL_ROOM_WEIGHT = 1000;
	private static final int MIN_BACKROOMS_LADDER_ROOM_WEIGHT = 0;
	private static final int MAX_BACKROOMS_LADDER_ROOM_WEIGHT = 100;
	private static final int MIN_RESPECT_COOLDOWN_SECONDS = 0;
	private static final int MAX_RESPECT_COOLDOWN_SECONDS = 86_400;
	private static final int MIN_KILLER_RABBIT_REPLACEMENT_CHANCE = 1;
	private static final int MAX_KILLER_RABBIT_REPLACEMENT_CHANCE = 100_000;
	private static final int MIN_GIANT_ZOMBIE_REPLACEMENT_CHANCE = 1;
	private static final int MAX_GIANT_ZOMBIE_REPLACEMENT_CHANCE = 100_000;
	private static final int MIN_CAMERA_RENDER_THREADS = 1;
	private static final int MAX_CAMERA_RENDER_THREADS = 32;
	private static final int MIN_CAMERA_RENDER_IN_FLIGHT_PIXELS = 8;
	private static final int MAX_CAMERA_RENDER_IN_FLIGHT_PIXELS = 8192;
	private static final int MIN_CAMERA_RENDER_SAMPLES_PER_AXIS = 1;
	private static final int MAX_CAMERA_RENDER_SAMPLES_PER_AXIS = 4;
	private static final int MIN_CAMERA_RENDERER_BOT_TIMEOUT_MS = 250;
	private static final int MAX_CAMERA_RENDERER_BOT_TIMEOUT_MS = 30_000;
	private static final int MIN_MONITOR_RENDER_THREADS = 1;
	private static final int MAX_MONITOR_RENDER_THREADS = 64;
	private static final int MIN_MONITOR_MEDIA_IO_THREADS = 1;
	private static final int MAX_MONITOR_MEDIA_IO_THREADS = 32;
	private static final int MIN_MONITOR_TILE_QUANTIZER_THREADS = 1;
	private static final int MAX_MONITOR_TILE_QUANTIZER_THREADS = 64;
	private static final int MIN_MONITOR_MAP_UPDATE_RADIUS_BLOCKS = 16;
	private static final int MAX_MONITOR_MAP_UPDATE_RADIUS_BLOCKS = 512;
	private static final int MIN_MONITOR_YOUTUBE_POLL_ACTIVE_INTERVAL_MS = 33;
	private static final int MAX_MONITOR_YOUTUBE_POLL_ACTIVE_INTERVAL_MS = 5000;
	private static final int MIN_MONITOR_YOUTUBE_POLL_IDLE_INTERVAL_MS = 100;
	private static final int MAX_MONITOR_YOUTUBE_POLL_IDLE_INTERVAL_MS = 10000;
	private static final double DEFAULT_BITCOINS_PER_STABILITY = 6.4D;
	private static final double MIN_BITCOINS_PER_STABILITY = 0.001D;
	private static final double MAX_BITCOINS_PER_STABILITY = 1_000_000.0D;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(Lg2.MOD_ID + ".json");

	private static ConfigData data = ConfigData.defaults();

	private Lg2Config() {
	}

	public static synchronized void load() {
		ConfigData loaded = readOrCreate();
		boolean changed = sanitize(loaded);
		data = loaded;

		if (changed) {
			write(data);
		}
	}

	public static ConfigData get() {
		return data;
	}

	/** Client-only preference, persisted in that player's local lg2.json. */
	public static synchronized void setCameraRendererVolunteerEnabled(boolean enabled) {
		if (data.cameraRendererVolunteerEnabled == enabled) {
			return;
		}
		data.cameraRendererVolunteerEnabled = enabled;
		write(data);
	}

	private static ConfigData readOrCreate() {
		if (!Files.exists(PATH)) {
			ConfigData defaults = ConfigData.defaults();
			write(defaults);
			return defaults;
		}

		try (Reader reader = Files.newBufferedReader(PATH)) {
			ConfigData parsed = ConfigVariableResolver.fromJsonWithVariables(GSON, reader, ConfigData.class);
			if (parsed == null) {
				Lg2.LOGGER.warn("Config {} is empty, resetting to defaults", PATH);
				return ConfigData.defaults();
			}
			return parsed;
		} catch (Exception e) {
			Lg2.LOGGER.warn("Failed to read config {}, using defaults", PATH, e);
			return ConfigData.defaults();
		}
	}

	private static void write(ConfigData configData) {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(configData, writer);
			}
		} catch (IOException e) {
			Lg2.LOGGER.error("Failed to write config {}", PATH, e);
		}
	}

	private static boolean sanitize(ConfigData configData) {
		boolean changed = false;

		changed |= clampRange(configData, RangeType.ORES_PER_CHUNK, 0, MAX_ORES_PER_CHUNK);
		changed |= clampRange(configData, RangeType.VEIN_SIZE, 0, MAX_VEIN_SIZE);
		changed |= clampRange(configData, RangeType.DROP, 0, MAX_DROP);
		changed |= clampRange(configData, RangeType.XP, 0, MAX_XP);
		changed |= clampRange(configData, RangeType.SPAWN_Y, MIN_WORLD_Y, MAX_WORLD_Y);
		changed |= clampSingleValue(configData.stabilityMax, MIN_STABILITY_MAX, MAX_STABILITY_MAX,
				newValue -> configData.stabilityMax = newValue);
		changed |= clampSingleValue(configData.stabilityDecayIntervalSeconds,
				MIN_STABILITY_DECAY_INTERVAL_SECONDS, MAX_STABILITY_DECAY_INTERVAL_SECONDS,
				newValue -> configData.stabilityDecayIntervalSeconds = newValue);
		changed |= clampSingleValue(configData.stabilityDecayIntervalSecondsPerPlayer,
				MIN_STABILITY_DECAY_INTERVAL_SECONDS_PER_PLAYER,
				MAX_STABILITY_DECAY_INTERVAL_SECONDS_PER_PLAYER,
				newValue -> configData.stabilityDecayIntervalSecondsPerPlayer = newValue);
		changed |= clampSingleValue(configData.backroomsEntitySpawnMinRadiusChunks,
				MIN_BACKROOMS_ENTITY_SPAWN_MIN_RADIUS_CHUNKS,
				MAX_BACKROOMS_ENTITY_SPAWN_MIN_RADIUS_CHUNKS,
				newValue -> configData.backroomsEntitySpawnMinRadiusChunks = newValue);
		changed |= clampSingleValue(configData.backroomsEntitySpawnMaxRadiusChunks,
				MIN_BACKROOMS_ENTITY_SPAWN_MAX_RADIUS_CHUNKS,
				MAX_BACKROOMS_ENTITY_SPAWN_MAX_RADIUS_CHUNKS,
				newValue -> configData.backroomsEntitySpawnMaxRadiusChunks = newValue);
		if (configData.backroomsEntitySpawnMaxRadiusChunks < configData.backroomsEntitySpawnMinRadiusChunks) {
			configData.backroomsEntitySpawnMaxRadiusChunks = configData.backroomsEntitySpawnMinRadiusChunks;
			changed = true;
		}
		changed |= clampSingleValue(configData.backroomsEntityGroupRadiusChunks,
				MIN_BACKROOMS_ENTITY_GROUP_RADIUS_CHUNKS,
				MAX_BACKROOMS_ENTITY_GROUP_RADIUS_CHUNKS,
				newValue -> configData.backroomsEntityGroupRadiusChunks = newValue);
		changed |= clampSingleValue(configData.backroomsLadderRoomWeight,
				MIN_BACKROOMS_LADDER_ROOM_WEIGHT,
				MAX_BACKROOMS_LADDER_ROOM_WEIGHT,
				newValue -> configData.backroomsLadderRoomWeight = newValue);
		changed |= clampSingleValue(configData.respectCooldownSeconds,
				MIN_RESPECT_COOLDOWN_SECONDS,
				MAX_RESPECT_COOLDOWN_SECONDS,
				newValue -> configData.respectCooldownSeconds = newValue);
		changed |= clampSingleValue(configData.killerRabbitReplacementChance,
				MIN_KILLER_RABBIT_REPLACEMENT_CHANCE,
				MAX_KILLER_RABBIT_REPLACEMENT_CHANCE,
				newValue -> configData.killerRabbitReplacementChance = newValue);
		changed |= clampSingleValue(configData.giantZombieReplacementChance,
				MIN_GIANT_ZOMBIE_REPLACEMENT_CHANCE,
				MAX_GIANT_ZOMBIE_REPLACEMENT_CHANCE,
				newValue -> configData.giantZombieReplacementChance = newValue);
		changed |= clampSingleValue(configData.cameraRenderThreads,
				MIN_CAMERA_RENDER_THREADS,
				MAX_CAMERA_RENDER_THREADS,
				newValue -> configData.cameraRenderThreads = newValue);
		changed |= clampSingleValue(configData.cameraRenderInFlightPixels,
				MIN_CAMERA_RENDER_IN_FLIGHT_PIXELS,
				MAX_CAMERA_RENDER_IN_FLIGHT_PIXELS,
				newValue -> configData.cameraRenderInFlightPixels = newValue);
		changed |= clampSingleValue(configData.cameraRenderSamplesPerAxis,
				MIN_CAMERA_RENDER_SAMPLES_PER_AXIS,
				MAX_CAMERA_RENDER_SAMPLES_PER_AXIS,
				newValue -> configData.cameraRenderSamplesPerAxis = newValue);
		changed |= clampSingleValue(configData.cameraRendererBotTimeoutMs,
				MIN_CAMERA_RENDERER_BOT_TIMEOUT_MS,
				MAX_CAMERA_RENDERER_BOT_TIMEOUT_MS,
				newValue -> configData.cameraRendererBotTimeoutMs = newValue);
		changed |= clampSingleValue(configData.monitorRenderThreads,
				MIN_MONITOR_RENDER_THREADS,
				MAX_MONITOR_RENDER_THREADS,
				newValue -> configData.monitorRenderThreads = newValue);
		changed |= clampSingleValue(configData.monitorMediaIoThreads,
				MIN_MONITOR_MEDIA_IO_THREADS,
				MAX_MONITOR_MEDIA_IO_THREADS,
				newValue -> configData.monitorMediaIoThreads = newValue);
		changed |= clampSingleValue(configData.monitorTileQuantizerThreads,
				MIN_MONITOR_TILE_QUANTIZER_THREADS,
				MAX_MONITOR_TILE_QUANTIZER_THREADS,
				newValue -> configData.monitorTileQuantizerThreads = newValue);
		changed |= clampSingleValue(configData.monitorMapUpdateRadiusBlocks,
				MIN_MONITOR_MAP_UPDATE_RADIUS_BLOCKS,
				MAX_MONITOR_MAP_UPDATE_RADIUS_BLOCKS,
				newValue -> configData.monitorMapUpdateRadiusBlocks = newValue);
		changed |= clampSingleValue(configData.monitorYoutubePollActiveIntervalMs,
				MIN_MONITOR_YOUTUBE_POLL_ACTIVE_INTERVAL_MS,
				MAX_MONITOR_YOUTUBE_POLL_ACTIVE_INTERVAL_MS,
				newValue -> configData.monitorYoutubePollActiveIntervalMs = newValue);
		changed |= clampSingleValue(configData.monitorYoutubePollIdleIntervalMs,
				MIN_MONITOR_YOUTUBE_POLL_IDLE_INTERVAL_MS,
				MAX_MONITOR_YOUTUBE_POLL_IDLE_INTERVAL_MS,
				newValue -> configData.monitorYoutubePollIdleIntervalMs = newValue);
		changed |= clampSingleValue(configData.backroomsTrashRoomWeight,
				MIN_BACKROOMS_SPECIAL_ROOM_WEIGHT,
				MAX_BACKROOMS_SPECIAL_ROOM_WEIGHT,
				newValue -> configData.backroomsTrashRoomWeight = newValue);
		changed |= clampSingleValue(configData.backroomsFloorHolesRoomWeight,
				MIN_BACKROOMS_SPECIAL_ROOM_WEIGHT,
				MAX_BACKROOMS_SPECIAL_ROOM_WEIGHT,
				newValue -> configData.backroomsFloorHolesRoomWeight = newValue);
		changed |= clampSingleValue(configData.backroomsVoidHallRoomWeight,
				MIN_BACKROOMS_SPECIAL_ROOM_WEIGHT,
				MAX_BACKROOMS_SPECIAL_ROOM_WEIGHT,
				newValue -> configData.backroomsVoidHallRoomWeight = newValue);
		changed |= clampSingleValue(configData.backroomsHouseHallRoomWeight,
				MIN_BACKROOMS_SPECIAL_ROOM_WEIGHT,
				MAX_BACKROOMS_SPECIAL_ROOM_WEIGHT,
				newValue -> configData.backroomsHouseHallRoomWeight = newValue);
		changed |= clampSingleValue(configData.backroomsPlusMazeRoomWeight,
				MIN_BACKROOMS_SPECIAL_ROOM_WEIGHT,
				MAX_BACKROOMS_SPECIAL_ROOM_WEIGHT,
				newValue -> configData.backroomsPlusMazeRoomWeight = newValue);
		changed |= clampSingleValue(configData.backroomsStairsRoomWeight,
				MIN_BACKROOMS_SPECIAL_ROOM_WEIGHT,
				MAX_BACKROOMS_SPECIAL_ROOM_WEIGHT,
				newValue -> configData.backroomsStairsRoomWeight = newValue);
		changed |= sanitizeBitcoinsPerStability(configData);

		return changed;
	}

	private static boolean clampSingleValue(int value, int minLimit, int maxLimit, java.util.function.IntConsumer consumer) {
		int clamped = clamp(value, minLimit, maxLimit);
		if (clamped == value) {
			return false;
		}
		consumer.accept(clamped);
		return true;
	}

	private static boolean sanitizeBitcoinsPerStability(ConfigData configData) {
		double value = configData.bitcoinsPerStability;
		double sanitized = value;

		if (!Double.isFinite(value) || value <= 0.0D) {
			sanitized = DEFAULT_BITCOINS_PER_STABILITY;
		} else if (value < MIN_BITCOINS_PER_STABILITY) {
			sanitized = MIN_BITCOINS_PER_STABILITY;
		} else if (value > MAX_BITCOINS_PER_STABILITY) {
			sanitized = MAX_BITCOINS_PER_STABILITY;
		}

		if (Double.compare(sanitized, value) == 0) {
			return false;
		}

		configData.bitcoinsPerStability = sanitized;
		return true;
	}

	private static boolean clampRange(ConfigData configData, RangeType type, int minLimit, int maxLimit) {
		int min;
		int max;

		switch (type) {
			case ORES_PER_CHUNK -> {
				min = configData.oresPerChunkMin;
				max = configData.oresPerChunkMax;
			}
			case VEIN_SIZE -> {
				min = configData.veinSizeMin;
				max = configData.veinSizeMax;
			}
			case DROP -> {
				min = configData.dropMin;
				max = configData.dropMax;
			}
			case XP -> {
				min = configData.xpMin;
				max = configData.xpMax;
			}
			case SPAWN_Y -> {
				min = configData.spawnMinY;
				max = configData.spawnMaxY;
			}
			default -> throw new IllegalStateException("Unexpected value: " + type);
		}

		int newMin = clamp(min, minLimit, maxLimit);
		int newMax = clamp(max, minLimit, maxLimit);
		if (newMax < newMin) {
			newMax = newMin;
		}

		boolean changed = newMin != min || newMax != max;
		if (!changed) {
			return false;
		}

		switch (type) {
			case ORES_PER_CHUNK -> {
				configData.oresPerChunkMin = newMin;
				configData.oresPerChunkMax = newMax;
			}
			case VEIN_SIZE -> {
				configData.veinSizeMin = newMin;
				configData.veinSizeMax = newMax;
			}
			case DROP -> {
				configData.dropMin = newMin;
				configData.dropMax = newMax;
			}
			case XP -> {
				configData.xpMin = newMin;
				configData.xpMax = newMax;
			}
			case SPAWN_Y -> {
				configData.spawnMinY = newMin;
				configData.spawnMaxY = newMax;
			}
			default -> throw new IllegalStateException("Unexpected value: " + type);
		}

		return true;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private enum RangeType {
		ORES_PER_CHUNK,
		VEIN_SIZE,
		DROP,
		XP,
		SPAWN_Y
	}

	public static final class ConfigData {
		public int oresPerChunkMin = 8;
		public int oresPerChunkMax = 14;
		public int veinSizeMin = 3;
		public int veinSizeMax = 8;
		public int dropMin = 1;
		public int dropMax = 1;
		public int xpMin = 1;
		public int xpMax = 3;
		public int spawnMinY = -64;
		public int spawnMaxY = 32;
		public boolean silkTouchEnabled = true;
		public boolean fortuneEnabled = true;
		public int stabilityMax = 100;
		public int stabilityDecayIntervalSeconds = 864;
		public int stabilityDecayIntervalSecondsPerPlayer = 0;
		public boolean backroomsEntitySpawnEnabled = true;
		@SerializedName("backroomsEntitySpawnMinRadiusChunks")
		public int backroomsEntitySpawnMinRadiusChunks = 1;
		@SerializedName(value = "backroomsEntitySpawnMaxRadiusChunks", alternate = {"backroomsEntityRadiusChunks"})
		public int backroomsEntitySpawnMaxRadiusChunks = 6;
		public int backroomsEntityGroupRadiusChunks = 2;
		public int backroomsLadderRoomWeight = 8;
		public int respectCooldownSeconds = 300;
		public int backroomsTrashRoomWeight = 2;
		public int backroomsFloorHolesRoomWeight = 2;
		public int backroomsVoidHallRoomWeight = 2;
		public int backroomsHouseHallRoomWeight = 1;
		public int backroomsPlusMazeRoomWeight = 2;
		public int backroomsStairsRoomWeight = 2;
		public double bitcoinsPerStability = DEFAULT_BITCOINS_PER_STABILITY;
		public int killerRabbitReplacementChance = 150;
		public int giantZombieReplacementChance = 250;
		public int cameraRenderThreads = Math.max(1, Math.min(2, Math.max(1, (Runtime.getRuntime().availableProcessors() - 1) / 2)));
		public int cameraRenderInFlightPixels = 1024;
		public int cameraRenderSamplesPerAxis = 2;
		public String cameraRendererBotPlayerName = "";
		/** Server-side admission control for opt-in player GPU renderers. */
		public boolean cameraRendererAllowPlayerVolunteers = true;
		/** Client-side opt-in. Never enabled automatically. */
		public boolean cameraRendererVolunteerEnabled = false;
		public int cameraRendererBotTimeoutMs = 15_000;
		public int monitorRenderThreads = Math.max(1, Math.min(4, Math.max(1, (Runtime.getRuntime().availableProcessors() - 1) / 2)));
		public int monitorMediaIoThreads = Math.max(1, Math.min(2, Math.max(1, Runtime.getRuntime().availableProcessors() / 4)));
		public int monitorTileQuantizerThreads = Math.max(1, Math.min(3, Math.max(1, (Runtime.getRuntime().availableProcessors() - 1) / 2)));
		public int monitorMapUpdateRadiusBlocks = 128;
		public boolean monitorYandexMapsClearCacheOnServerStart = false;
		public int monitorYoutubePollActiveIntervalMs = 50;
		public int monitorYoutubePollIdleIntervalMs = 200;

		private ConfigData() {
		}

		public static ConfigData defaults() {
			return new ConfigData();
		}

		public int sampleOresPerChunk(RandomSource random) {
			return sampleInRange(random, this.oresPerChunkMin, this.oresPerChunkMax);
		}

		public int sampleVeinSize(RandomSource random) {
			return sampleInRange(random, this.veinSizeMin, this.veinSizeMax);
		}

		public int sampleDrop(RandomSource random) {
			return sampleInRange(random, this.dropMin, this.dropMax);
		}

		public int sampleXp(RandomSource random) {
			return sampleInRange(random, this.xpMin, this.xpMax);
		}

		public int sampleSpawnY(RandomSource random) {
			return sampleInRange(random, this.spawnMinY, this.spawnMaxY);
		}

		private static int sampleInRange(RandomSource random, int min, int max) {
			if (max <= min) {
				return min;
			}
			return min + random.nextInt(max - min + 1);
		}
	}
}
