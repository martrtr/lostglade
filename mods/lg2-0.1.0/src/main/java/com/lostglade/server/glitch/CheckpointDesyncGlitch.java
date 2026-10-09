package com.lostglade.server.glitch;

import com.google.gson.JsonObject;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.ServerBackroomsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CheckpointDesyncGlitch implements RespawnGlitchHandler {
	private static final Map<UUID, SpawnTarget> PENDING_RESPAWN_TARGET = new HashMap<>();

	@Override
	public String id() {
		return "checkpoint_desync";
	}

	@Override
	public GlitchConfig.GlitchEntry defaultEntry() {
		GlitchConfig.GlitchEntry entry = new GlitchConfig.GlitchEntry();
		entry.enabled = true;
		entry.minStabilityPercent = 0.0D;
		entry.maxStabilityPercent = 70.0D;
		entry.chancePerCheck = 0.35D;
		entry.stabilityInfluence = 1.0D;
		entry.minCooldownTicks = 0;
		entry.maxCooldownTicks = 0;
		entry.settings = new JsonObject();
		return entry;
	}

	@Override
	public boolean sanitizeSettings(GlitchConfig.GlitchEntry entry) {
		if (entry.settings == null) {
			entry.settings = new JsonObject();
			return true;
		}
		return false;
	}

	@Override
	public boolean trigger(MinecraftServer server, RandomSource random, GlitchConfig.GlitchEntry entry, double stabilityPercent) {
		return false;
	}

	@Override
	public boolean triggerOnCopyFrom(
			MinecraftServer server,
			RandomSource random,
			GlitchConfig.GlitchEntry entry,
			double stabilityPercent,
			ServerPlayer oldPlayer,
			ServerPlayer newPlayer,
			boolean alive
	) {
		if (alive || newPlayer == null) {
			return false;
		}
		if ((oldPlayer != null && ServerBackroomsSystem.isInBackrooms(oldPlayer))
				|| ServerBackroomsSystem.isInBackrooms(newPlayer)) {
			return false;
		}

		Map<SpawnPointKey, SpawnTarget> uniqueTargets = collectUniqueForeignSpawnTargets(server, newPlayer);
		if (uniqueTargets.isEmpty()) {
			return false;
		}

		List<SpawnTarget> targets = new ArrayList<>(uniqueTargets.values());
		SpawnTarget picked = targets.get(random.nextInt(targets.size()));
		picked.level().getChunkAt(picked.blockPos());
		PENDING_RESPAWN_TARGET.put(newPlayer.getUUID(), picked);
		return true;
	}

	@Override
	public void onAfterRespawn(MinecraftServer server, ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
		if (alive || newPlayer == null) {
			return;
		}

		SpawnTarget target = PENDING_RESPAWN_TARGET.remove(newPlayer.getUUID());
		if (target == null) {
			return;
		}

		target.level().getChunkAt(target.blockPos());
		newPlayer.teleportTo(
				target.level(),
				target.x(),
				target.y(),
				target.z(),
				Set.<Relative>of(),
				target.yaw(),
				target.pitch(),
				false
		);
	}

	private static Map<SpawnPointKey, SpawnTarget> collectUniqueForeignSpawnTargets(MinecraftServer server, ServerPlayer respawnedPlayer) {
		Map<SpawnPointKey, SpawnTarget> uniqueTargets = new LinkedHashMap<>();
		for (ServerPlayer candidate : GlitchPlayers.eligiblePlayers(server)) {
			if (candidate.getUUID().equals(respawnedPlayer.getUUID())) {
				continue;
			}

			SpawnTarget target = resolveSpawnTarget(server, candidate);
			if (target == null) {
				continue;
			}

			SpawnPointKey key = new SpawnPointKey(target.dimension(), target.blockPos());
			uniqueTargets.putIfAbsent(key, target);
		}

		return uniqueTargets;
	}

	private static SpawnTarget resolveSpawnTarget(MinecraftServer server, ServerPlayer player) {
		ServerPlayer.RespawnConfig respawnConfig = player.getRespawnConfig();
		LevelData.RespawnData respawnData = respawnConfig == null
				? LevelData.RespawnData.DEFAULT
				: respawnConfig.respawnData();
		if (respawnData == null) {
			respawnData = LevelData.RespawnData.DEFAULT;
		}

		ResourceKey<Level> dimension = respawnData.dimension();
		ServerLevel level = server.getLevel(dimension);
		if (level == null) {
			return null;
		}
		if (ServerBackroomsSystem.isBackrooms(level)) {
			return null;
		}

		BlockPos blockPos = respawnData.pos();
		if (LevelData.RespawnData.DEFAULT.equals(respawnData)) {
			respawnData = level.getRespawnData();
			blockPos = respawnData.pos();
		}

		return new SpawnTarget(
				level,
				dimension,
				blockPos.immutable(),
				blockPos.getX() + 0.5D,
				blockPos.getY() + 0.1D,
				blockPos.getZ() + 0.5D,
				respawnData.yaw(),
				respawnData.pitch()
		);
	}

	private record SpawnPointKey(ResourceKey<Level> dimension, BlockPos blockPos) {
	}

	private record SpawnTarget(
			ServerLevel level,
			ResourceKey<Level> dimension,
			BlockPos blockPos,
			double x,
			double y,
			double z,
			float yaw,
			float pitch
	) {
	}
}
