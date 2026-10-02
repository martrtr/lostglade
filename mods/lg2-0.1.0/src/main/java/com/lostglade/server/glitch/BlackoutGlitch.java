package com.lostglade.server.glitch;

import com.lostglade.server.AccountAuthSystem;

import com.google.gson.JsonObject;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.ServerBackroomsSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BlackoutGlitch implements ServerGlitchHandler {
	private static final String MIN_TARGET_PLAYERS = "minTargetPlayers";
	private static final String MAX_TARGET_PLAYERS = "maxTargetPlayers";
	private static final String MIN_DURATION_SECONDS = "minDurationSeconds";
	private static final String MAX_DURATION_SECONDS = "maxDurationSeconds";
	private static final int BLINDNESS_AMPLIFIER = 3;
	private static final int DARKNESS_AMPLIFIER = 1;
	private static final int EFFECT_REAPPLY_INTERVAL_TICKS = 4;
	private static final int EFFECT_WINDOW_TICKS = 16;
	private static final int MIN_FADE_IN_TICKS = 20;
	private static final int MAX_FADE_IN_TICKS = 120;
	private static final double FLICKER_WAVE_BASE = 0.78D;
	private static final double FLICKER_WAVE_AMPLITUDE = 0.22D;
	private static final double FLICKER_WAVE_SPEED = 0.40D;
	private static final double FLICKER_NOISE_MIN = 0.75D;
	private static final Map<UUID, ActiveBlackoutState> ACTIVE_STATES = new HashMap<>();
	private static final ThreadLocal<UUID> APPLYING_EFFECT_TO = new ThreadLocal<>();

	public static boolean isApplyingEffectsTo(ServerPlayer player) {
		return player != null && player.getUUID().equals(APPLYING_EFFECT_TO.get());
	}

	public static void tickActiveStates(MinecraftServer server) {
		if (server == null || ACTIVE_STATES.isEmpty()) {
			return;
		}

		long nowTick = server.overworld().getGameTime();
		Iterator<Map.Entry<UUID, ActiveBlackoutState>> iterator = ACTIVE_STATES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, ActiveBlackoutState> mapEntry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(mapEntry.getKey());
			if (player == null || !player.isAlive() || player.isSpectator() || ServerBackroomsSystem.isInBackrooms(player)) {
				iterator.remove();
				continue;
			}

			ActiveBlackoutState state = mapEntry.getValue();
			if (nowTick >= state.endTick) {
				iterator.remove();
				continue;
			}

			if (nowTick < state.nextApplyTick) {
				continue;
			}

			applyScaledEffects(player, state, nowTick);
			state.nextApplyTick = nowTick + EFFECT_REAPPLY_INTERVAL_TICKS;
		}
	}

	@Override
	public String id() {
		return "blackout";
	}

	@Override
	public GlitchConfig.GlitchEntry defaultEntry() {
		GlitchConfig.GlitchEntry entry = new GlitchConfig.GlitchEntry();
		entry.enabled = true;
		entry.minStabilityPercent = 0.0D;
		entry.maxStabilityPercent = 70.0D;
		entry.chancePerCheck = 0.30D;
		entry.stabilityInfluence = 1.0D;
		entry.minCooldownTicks = 80;
		entry.maxCooldownTicks = 1200;

		JsonObject settings = new JsonObject();
		settings.addProperty(MIN_TARGET_PLAYERS, 1);
		settings.addProperty(MAX_TARGET_PLAYERS, 3);
		settings.addProperty(MIN_DURATION_SECONDS, 4);
		settings.addProperty(MAX_DURATION_SECONDS, 12);
		entry.settings = settings;
		return entry;
	}

	@Override
	public boolean sanitizeSettings(GlitchConfig.GlitchEntry entry) {
		if (entry.settings == null) {
			entry.settings = new JsonObject();
		}

		boolean changed = false;
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MIN_TARGET_PLAYERS, 1, 1, 100);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_TARGET_PLAYERS, 3, 1, 100);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MIN_DURATION_SECONDS, 4, 1, 120);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_DURATION_SECONDS, 12, 1, 120);

		int minTargets = GlitchSettingsHelper.getInt(entry.settings, MIN_TARGET_PLAYERS, 1);
		int maxTargets = Math.max(1, GlitchSettingsHelper.getInt(entry.settings, MAX_TARGET_PLAYERS, 3));
		boolean minTargetExpression = GlitchSettingsHelper.isExpression(entry.settings, MIN_TARGET_PLAYERS);
		boolean maxTargetExpression = GlitchSettingsHelper.isExpression(entry.settings, MAX_TARGET_PLAYERS);
		if (!minTargetExpression && !maxTargetExpression && maxTargets < minTargets) {
			entry.settings.addProperty(MAX_TARGET_PLAYERS, minTargets);
			changed = true;
		}

		int minDuration = GlitchSettingsHelper.getInt(entry.settings, MIN_DURATION_SECONDS, 4);
		int maxDuration = GlitchSettingsHelper.getInt(entry.settings, MAX_DURATION_SECONDS, 12);
		if (maxDuration < minDuration) {
			entry.settings.addProperty(MAX_DURATION_SECONDS, minDuration);
			changed = true;
		}

		return changed;
	}

	@Override
	public boolean trigger(MinecraftServer server, RandomSource random, GlitchConfig.GlitchEntry entry, double stabilityPercent) {
		tickActiveStates(server);

		List<ServerPlayer> targets = collectTargets(server);
		if (targets.isEmpty()) {
			return false;
		}

		JsonObject settings = entry.settings == null ? new JsonObject() : entry.settings;
		double instability = getRangeInstabilityFactor(stabilityPercent, entry.minStabilityPercent, entry.maxStabilityPercent);

		int minTargets = GlitchSettingsHelper.getInt(settings, MIN_TARGET_PLAYERS, 1);
		int maxTargets = Math.max(1, GlitchSettingsHelper.getInt(settings, MAX_TARGET_PLAYERS, 3));
		int targetCount = interpolateInt(minTargets, maxTargets, instability);
		targetCount = Math.max(1, Math.min(targets.size(), targetCount));

		int minDurationSeconds = GlitchSettingsHelper.getInt(settings, MIN_DURATION_SECONDS, 4);
		int maxDurationSeconds = GlitchSettingsHelper.getInt(settings, MAX_DURATION_SECONDS, 12);
		int durationSeconds = interpolateInt(minDurationSeconds, maxDurationSeconds, instability);
		int durationTicks = Math.max(20, durationSeconds * 20);
		int fadeInTicks = Math.max(MIN_FADE_IN_TICKS, Math.min(MAX_FADE_IN_TICKS, durationTicks / 2));
		long nowTick = server.overworld().getGameTime();

		shuffle(targets, random);
		boolean appliedAny = false;
		for (int i = 0; i < targetCount; i++) {
			ServerPlayer player = targets.get(i);
			ActiveBlackoutState state = new ActiveBlackoutState(
					nowTick,
					nowTick + durationTicks,
					nowTick,
					fadeInTicks,
					calculatePlayerPhase(player)
			);
			ACTIVE_STATES.put(player.getUUID(), state);
			applyScaledEffects(player, state, nowTick);
			state.nextApplyTick = nowTick + EFFECT_REAPPLY_INTERVAL_TICKS;
			appliedAny = true;
		}

		return appliedAny;
	}

	private static void applyScaledEffects(ServerPlayer player, ActiveBlackoutState state, long nowTick) {
		int remainingTicks = (int) Math.max(1L, state.endTick - nowTick);
		int effectDurationTicks = Math.max(2, Math.min(EFFECT_WINDOW_TICKS, remainingTicks));

		double fadeProgress = getFadeProgress(state, nowTick);
		double flicker = getFlickerFactor(state, nowTick, player.getUUID());
		double intensity = clamp01(fadeProgress * flicker);
		int blindnessAmplifier = scaleAmplifier(BLINDNESS_AMPLIFIER, intensity);
		int darknessAmplifier = scaleAmplifier(DARKNESS_AMPLIFIER, intensity);

		APPLYING_EFFECT_TO.set(player.getUUID());
		try {
			player.addEffect(new MobEffectInstance(
					MobEffects.BLINDNESS,
					effectDurationTicks,
					blindnessAmplifier,
					false,
					false,
					false
			));
			player.addEffect(new MobEffectInstance(
					MobEffects.DARKNESS,
					effectDurationTicks,
					darknessAmplifier,
					false,
					false,
					false
			));
		} finally {
			APPLYING_EFFECT_TO.remove();
		}
	}

	private static double getFadeProgress(ActiveBlackoutState state, long nowTick) {
		if (state.fadeInTicks <= 0) {
			return 1.0D;
		}
		double elapsed = Math.max(0L, nowTick - state.startTick);
		return clamp01(elapsed / (double) state.fadeInTicks);
	}

	private static double getFlickerFactor(ActiveBlackoutState state, long nowTick, UUID playerId) {
		double wave = FLICKER_WAVE_BASE + (FLICKER_WAVE_AMPLITUDE * Math.sin((nowTick + state.phaseOffset) * FLICKER_WAVE_SPEED));
		double noise = FLICKER_NOISE_MIN + ((1.0D - FLICKER_NOISE_MIN) * pseudoRandom01(playerId, nowTick / EFFECT_REAPPLY_INTERVAL_TICKS));
		return clamp01(wave * noise);
	}

	private static int scaleAmplifier(int maxAmplifier, double intensity) {
		if (maxAmplifier <= 0) {
			return 0;
		}
		return Math.max(0, Math.min(maxAmplifier, (int) Math.round(maxAmplifier * clamp01(intensity))));
	}

	private static int calculatePlayerPhase(ServerPlayer player) {
		UUID uuid = player.getUUID();
		long mixed = uuid.getMostSignificantBits() ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 17);
		return (int) (mixed & 1023L);
	}

	private static double pseudoRandom01(UUID playerId, long step) {
		long seed = step
				^ playerId.getMostSignificantBits()
				^ Long.rotateLeft(playerId.getLeastSignificantBits(), 29)
				^ 0x9E3779B97F4A7C15L;
		seed ^= (seed >>> 33);
		seed *= 0xff51afd7ed558ccdL;
		seed ^= (seed >>> 33);
		seed *= 0xc4ceb9fe1a85ec53L;
		seed ^= (seed >>> 33);
		long mantissa = (seed >>> 11) & ((1L << 53) - 1);
		return mantissa / (double) (1L << 53);
	}

	private static List<ServerPlayer> collectTargets(MinecraftServer server) {
		List<ServerPlayer> players = new ArrayList<>();
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			if (player.isSpectator() || !player.isAlive() || ServerBackroomsSystem.isInBackrooms(player)) {
				continue;
			}
			players.add(player);
		}
		return players;
	}

	private static int interpolateInt(int min, int max, double factor) {
		if (max <= min) {
			return min;
		}
		double clamped = Math.max(0.0D, Math.min(1.0D, factor));
		return min + (int) Math.round((max - min) * clamped);
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	private static double getRangeInstabilityFactor(double stabilityPercent, double minStabilityPercent, double maxStabilityPercent) {
		double range = maxStabilityPercent - minStabilityPercent;
		if (range <= 1.0E-9D) {
			return 1.0D;
		}
		double normalized = (stabilityPercent - minStabilityPercent) / range;
		return Math.max(0.0D, Math.min(1.0D, 1.0D - normalized));
	}

	private static void shuffle(List<ServerPlayer> list, RandomSource random) {
		for (int i = list.size() - 1; i > 0; i--) {
			int swapWith = random.nextInt(i + 1);
			Collections.swap(list, i, swapWith);
		}
	}

	private static final class ActiveBlackoutState {
		private final long startTick;
		private final long endTick;
		private long nextApplyTick;
		private final int fadeInTicks;
		private final int phaseOffset;

		private ActiveBlackoutState(long startTick, long endTick, long nextApplyTick, int fadeInTicks, int phaseOffset) {
			this.startTick = startTick;
			this.endTick = endTick;
			this.nextApplyTick = nextApplyTick;
			this.fadeInTicks = fadeInTicks;
			this.phaseOffset = phaseOffset;
		}
	}
}
