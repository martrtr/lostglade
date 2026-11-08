package com.lostglade.server.glitch;

import com.google.gson.JsonObject;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.AccountAuthSystem;
import com.lostglade.server.RendererBotPresenceSystem;
import com.lostglade.server.ServerBackroomsSystem;
import com.lostglade.server.ServerStabilitySystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class BackroomsEntryGlitch implements ServerGlitchHandler {
	private static final String ID = "backrooms_entry";
	private static final Set<UUID> VOID_EPISODES = new HashSet<>();

	public static void resetRuntimeState() {
		VOID_EPISODES.clear();
	}

	public static void tickActiveStates(MinecraftServer server) {
		VOID_EPISODES.removeIf(uuid -> {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			return player != null && !isBelowWorld(player);
		});
	}

	public static boolean onAllowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer player) || !source.is(DamageTypes.FELL_OUT_OF_WORLD) || !isBelowWorld(player)) {
			return true;
		}
		if (!VOID_EPISODES.add(player.getUUID())) {
			return true;
		}

		MinecraftServer server = player.level().getServer();
		GlitchConfig.ConfigData config = GlitchConfig.get();
		GlitchConfig.GlitchEntry entry = config.glitches == null ? null : config.glitches.get(ID);
		if (server == null || !config.enabled || entry == null || !entry.enabled || !player.isAlive()
				|| player.isSpectator() || !AccountAuthSystem.isAuthenticated(player)
				|| RendererBotPresenceSystem.isRendererBot(player) || ServerBackroomsSystem.isInBackrooms(player)) {
			return true;
		}

		double stability = ServerStabilitySystem.getStabilityPercent();
		if (stability < entry.minStabilityPercent || stability > entry.maxStabilityPercent) {
			return true;
		}
		double range = entry.maxStabilityPercent - entry.minStabilityPercent;
		double instability = range <= 1.0E-9D ? 1.0D
				: clamp01(1.0D - (stability - entry.minStabilityPercent) / range);
		double influence = clamp01(entry.stabilityInfluence);
		double chance = clamp01(entry.chancePerCheck) * ((1.0D - influence) + instability * influence);
		if (chance <= 0.0D || player.getRandom().nextDouble() >= chance) {
			return true;
		}

		// The successful first hit becomes a portal instead of killing the player in the void.
		return !ServerBackroomsSystem.teleportPlayerToBackrooms(player);
	}

	private static boolean isBelowWorld(ServerPlayer player) {
		return player.getY() < player.level().getMinY() - 64;
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public GlitchConfig.GlitchEntry defaultEntry() {
		GlitchConfig.GlitchEntry entry = new GlitchConfig.GlitchEntry();
		entry.enabled = true;
		entry.minStabilityPercent = 0.0D;
		entry.maxStabilityPercent = 40.0D;
		entry.chancePerCheck = 0.03D;
		entry.stabilityInfluence = 1.0D;
		entry.minCooldownTicks = 0;
		entry.maxCooldownTicks = 0;
		entry.settings = new JsonObject();
		return entry;
	}

	@Override
	public boolean sanitizeSettings(GlitchConfig.GlitchEntry entry) {
		if (entry.settings != null) {
			return false;
		}
		entry.settings = new JsonObject();
		return true;
	}

	@Override
	public boolean isPeriodic() {
		return false;
	}

	@Override
	public boolean trigger(MinecraftServer server, RandomSource random, GlitchConfig.GlitchEntry entry, double stabilityPercent) {
		return false;
	}
}
