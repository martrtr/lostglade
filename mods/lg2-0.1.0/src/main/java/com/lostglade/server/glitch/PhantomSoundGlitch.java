package com.lostglade.server.glitch;

import com.google.gson.JsonObject;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.ServerBackroomsSystem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PhantomSoundGlitch implements ServerGlitchHandler {
	private static final String TARGET_PLAYERS = "targetPlayers";
	private static final String MIN_TARGET_PLAYERS = "minTargetPlayers";
	private static final String MAX_TARGET_PLAYERS = "maxTargetPlayers";
	private static final String MIN_DISTANCE = "minDistance";
	private static final String MAX_DISTANCE = "maxDistance";
	private static final String VOLUME = "volume";
	private static final String MIN_VOLUME = "minVolume";
	private static final String MAX_VOLUME = "maxVolume";
	private static final String PITCH_MIN = "pitchMin";
	private static final String PITCH_MAX = "pitchMax";
	private static final List<SoundEvent> VANILLA_SOUND_POOL = collectVanillaSoundPool();
	private static final Map<UUID, Identifier> ACTIVE_PLAYER_GLITCH_SOUND = new HashMap<>();

	@Override
	public String id() {
		return "phantom_sounds";
	}

	@Override
	public GlitchConfig.GlitchEntry defaultEntry() {
		GlitchConfig.GlitchEntry entry = new GlitchConfig.GlitchEntry();
		entry.enabled = true;
		entry.minStabilityPercent = 0.0D;
		entry.maxStabilityPercent = 84.0D;
		entry.chancePerCheck = 0.45D;
		entry.stabilityInfluence = 1.0D;
		entry.minCooldownTicks = 100;
		entry.maxCooldownTicks = 100;

		JsonObject settings = new JsonObject();
		settings.addProperty(MIN_TARGET_PLAYERS, 2);
		settings.addProperty(MAX_TARGET_PLAYERS, 2);
		settings.addProperty(MIN_DISTANCE, 4.0D);
		settings.addProperty(MAX_DISTANCE, 12.0D);
		settings.addProperty(MIN_VOLUME, 1.0D);
		settings.addProperty(MAX_VOLUME, 1.0D);
		settings.addProperty(PITCH_MIN, 0.70D);
		settings.addProperty(PITCH_MAX, 1.25D);
		entry.settings = settings;
		return entry;
	}

	@Override
	public boolean sanitizeSettings(GlitchConfig.GlitchEntry entry) {
		if (entry.settings == null) {
			entry.settings = new JsonObject();
		}

		boolean changed = false;
		boolean hasMinTarget = entry.settings.has(MIN_TARGET_PLAYERS);
		boolean hasMaxTarget = entry.settings.has(MAX_TARGET_PLAYERS);
		boolean hasMinVolume = entry.settings.has(MIN_VOLUME);
		boolean hasMaxVolume = entry.settings.has(MAX_VOLUME);
		int fallbackTargetPlayers = GlitchSettingsHelper.getInt(entry.settings, TARGET_PLAYERS, 2);
		double fallbackVolume = GlitchSettingsHelper.getDouble(entry.settings, VOLUME, 1.0D);

		changed |= GlitchSettingsHelper.sanitizeInt(
				entry.settings,
				MIN_TARGET_PLAYERS,
				hasMinTarget ? 2 : fallbackTargetPlayers,
				1,
				20
		);
		changed |= GlitchSettingsHelper.sanitizeInt(
				entry.settings,
				MAX_TARGET_PLAYERS,
				hasMaxTarget ? 2 : fallbackTargetPlayers,
				1,
				20
		);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, MIN_DISTANCE, 4.0D, 1.0D, 64.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, MAX_DISTANCE, 12.0D, 1.0D, 96.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(
				entry.settings,
				MIN_VOLUME,
				hasMinVolume ? 1.0D : fallbackVolume,
				0.05D,
				4.0D
		);
		changed |= GlitchSettingsHelper.sanitizeDouble(
				entry.settings,
				MAX_VOLUME,
				hasMaxVolume ? 1.0D : fallbackVolume,
				0.05D,
				4.0D
		);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, PITCH_MIN, 0.70D, 0.1D, 2.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, PITCH_MAX, 1.25D, 0.1D, 2.0D);

		int minTargetPlayers = GlitchSettingsHelper.getInt(entry.settings, MIN_TARGET_PLAYERS, fallbackTargetPlayers);
		int maxTargetPlayers = Math.max(1, GlitchSettingsHelper.getInt(entry.settings, MAX_TARGET_PLAYERS, fallbackTargetPlayers));
		boolean minTargetExpression = GlitchSettingsHelper.isExpression(entry.settings, MIN_TARGET_PLAYERS);
		boolean maxTargetExpression = GlitchSettingsHelper.isExpression(entry.settings, MAX_TARGET_PLAYERS);
		if (!minTargetExpression && !maxTargetExpression && maxTargetPlayers < minTargetPlayers) {
			entry.settings.addProperty(MAX_TARGET_PLAYERS, minTargetPlayers);
			changed = true;
		}

		double minDistance = GlitchSettingsHelper.getDouble(entry.settings, MIN_DISTANCE, 4.0D);
		double maxDistance = GlitchSettingsHelper.getDouble(entry.settings, MAX_DISTANCE, 12.0D);
		if (maxDistance < minDistance) {
			entry.settings.addProperty(MAX_DISTANCE, minDistance);
			changed = true;
		}

		double minVolume = GlitchSettingsHelper.getDouble(entry.settings, MIN_VOLUME, fallbackVolume);
		double maxVolume = GlitchSettingsHelper.getDouble(entry.settings, MAX_VOLUME, fallbackVolume);
		if (maxVolume < minVolume) {
			entry.settings.addProperty(MAX_VOLUME, minVolume);
			changed = true;
		}

		double pitchMin = GlitchSettingsHelper.getDouble(entry.settings, PITCH_MIN, 0.70D);
		double pitchMax = GlitchSettingsHelper.getDouble(entry.settings, PITCH_MAX, 1.25D);
		if (pitchMax < pitchMin) {
			entry.settings.addProperty(PITCH_MAX, pitchMin);
			changed = true;
		}

		return changed;
	}

	@Override
	public boolean trigger(MinecraftServer server, RandomSource random, GlitchConfig.GlitchEntry entry, double stabilityPercent) {
		cleanupInactivePlayerEntries(server);

		List<ServerPlayer> players = collectTargets(server);
		if (players.isEmpty() || VANILLA_SOUND_POOL.isEmpty()) {
			return false;
		}

		shuffle(players, random);
		JsonObject settings = entry.settings == null ? new JsonObject() : entry.settings;

		int fallbackTargetPlayers = GlitchSettingsHelper.getInt(settings, TARGET_PLAYERS, 2);
		int minTargetPlayers = GlitchSettingsHelper.getInt(settings, MIN_TARGET_PLAYERS, fallbackTargetPlayers);
		int maxTargetPlayers = Math.max(1, GlitchSettingsHelper.getInt(settings, MAX_TARGET_PLAYERS, fallbackTargetPlayers));
		int targetPlayers = Math.min(players.size(), sampleRangeInt(random, minTargetPlayers, maxTargetPlayers));
		double minDistance = GlitchSettingsHelper.getDouble(settings, MIN_DISTANCE, 4.0D);
		double maxDistance = GlitchSettingsHelper.getDouble(settings, MAX_DISTANCE, 12.0D);
		double fallbackVolume = GlitchSettingsHelper.getDouble(settings, VOLUME, 1.0D);
		double minVolume = GlitchSettingsHelper.getDouble(settings, MIN_VOLUME, fallbackVolume);
		double maxVolume = GlitchSettingsHelper.getDouble(settings, MAX_VOLUME, fallbackVolume);
		double pitchMin = GlitchSettingsHelper.getDouble(settings, PITCH_MIN, 0.70D);
		double pitchMax = GlitchSettingsHelper.getDouble(settings, PITCH_MAX, 1.25D);
		SoundEvent sound = VANILLA_SOUND_POOL.get(random.nextInt(VANILLA_SOUND_POOL.size()));
		Identifier soundId = BuiltInRegistries.SOUND_EVENT.getKey(sound);
		if (soundId == null) {
			return false;
		}

		boolean appliedAny = false;
		for (int i = 0; i < targetPlayers; i++) {
			ServerPlayer player = players.get(i);

			double angle = random.nextDouble() * Math.PI * 2.0D;
			double distance = sampleRange(random, minDistance, maxDistance);
			double x = player.getX() + Math.cos(angle) * distance;
			double z = player.getZ() + Math.sin(angle) * distance;
			double y = player.getY() + sampleRange(random, -1.5D, 1.5D);

			float pitch = (float) sampleRange(random, pitchMin, pitchMax);
			float volume = (float) sampleRange(random, minVolume, maxVolume);

			stopPreviousSoundForPlayer(player);
			sendSoundToPlayer(player, sound, x, y, z, volume, pitch, random.nextLong());
			ACTIVE_PLAYER_GLITCH_SOUND.put(player.getUUID(), soundId);
			appliedAny = true;
		}

		return appliedAny;
	}

	private static int sampleRangeInt(RandomSource random, int min, int max) {
		if (max <= min) {
			return min;
		}
		return min + random.nextInt(max - min + 1);
	}

	private static double sampleRange(RandomSource random, double min, double max) {
		if (max <= min) {
			return min;
		}
		return min + random.nextDouble() * (max - min);
	}

	private static List<ServerPlayer> collectTargets(MinecraftServer server) {
		List<ServerPlayer> players = new ArrayList<>();
		for (ServerPlayer player : GlitchPlayers.eligiblePlayers(server)) {
			if (player.isSpectator() || !player.isAlive() || ServerBackroomsSystem.isInBackrooms(player)) {
				continue;
			}
			players.add(player);
		}
		return players;
	}

	private static List<SoundEvent> collectVanillaSoundPool() {
		List<SoundEvent> sounds = new ArrayList<>();
		for (SoundEvent soundEvent : BuiltInRegistries.SOUND_EVENT) {
			Identifier id = BuiltInRegistries.SOUND_EVENT.getKey(soundEvent);
			if (id != null && "minecraft".equals(id.getNamespace())) {
				sounds.add(soundEvent);
			}
		}
		return sounds;
	}

	private static void sendSoundToPlayer(
			ServerPlayer player,
			SoundEvent sound,
			double x,
			double y,
			double z,
			float volume,
			float pitch,
			long seed
	) {
		player.connection.send(
				new ClientboundSoundPacket(
						BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
						SoundSource.MASTER,
						x,
						y,
						z,
						volume,
						pitch,
						seed
				)
		);
	}

	private static void stopPreviousSoundForPlayer(ServerPlayer player) {
		Identifier previousSound = ACTIVE_PLAYER_GLITCH_SOUND.get(player.getUUID());
		if (previousSound == null) {
			return;
		}

		player.connection.send(new ClientboundStopSoundPacket(previousSound, SoundSource.MASTER));
	}

	private static void cleanupInactivePlayerEntries(MinecraftServer server) {
		if (ACTIVE_PLAYER_GLITCH_SOUND.isEmpty()) {
			return;
		}

		Set<UUID> onlinePlayers = new HashSet<>();
		for (ServerPlayer player : GlitchPlayers.eligiblePlayers(server)) {
			onlinePlayers.add(player.getUUID());
		}

		ACTIVE_PLAYER_GLITCH_SOUND.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
	}

	private static void shuffle(List<ServerPlayer> list, RandomSource random) {
		for (int i = list.size() - 1; i > 0; i--) {
			int swapWith = random.nextInt(i + 1);
			Collections.swap(list, i, swapWith);
		}
	}
}
