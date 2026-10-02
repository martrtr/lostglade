package com.lostglade.server.glitch;

import com.lostglade.server.AccountAuthSystem;

import com.google.gson.JsonObject;
import com.mojang.authlib.properties.Property;
import it.unimi.dsi.fastutil.Pair;
import net.lionarius.skinrestorer.SkinRestorer;
import net.lionarius.skinrestorer.skin.SkinService;
import net.lionarius.skinrestorer.skin.SkinStorage;
import net.lionarius.skinrestorer.skin.SkinValue;
import net.lionarius.skinrestorer.skin.SkinVariant;
import net.lionarius.skinrestorer.util.PlayerUtils;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.ServerBackroomsSystem;
import com.lostglade.server.ServerStabilitySystem;
import com.lostglade.server.CartelWebcamBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerShuffleGlitch implements ServerGlitchHandler {
	private static final String MIN_TARGET_PLAYERS = "minTargetPlayers";
	private static final String MAX_TARGET_PLAYERS = "maxTargetPlayers";
	private static final String POSITION_SHUFFLE_CHANCE = "positionShuffleChance";
	private static final double TARGET_COUNT_PRIORITY = 2.4D;
	private static final Set<Relative> ABSOLUTE_TELEPORT = EnumSet.noneOf(Relative.class);
	private static final Map<UUID, ActiveShuffleState> ACTIVE_STATES = new HashMap<>();

	public static void tickActiveStates(MinecraftServer server) {
		if (server == null || ACTIVE_STATES.isEmpty()) {
			return;
		}

		double stabilityPercent = ServerStabilitySystem.getStabilityPercent();
		Iterator<Map.Entry<UUID, ActiveShuffleState>> iterator = ACTIVE_STATES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, ActiveShuffleState> entry = iterator.next();
			UUID playerId = entry.getKey();
			ActiveShuffleState state = entry.getValue();
			if (playerId == null || state == null) {
				iterator.remove();
				continue;
			}

			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				CartelWebcamBridge.endSourceMirror(playerId);
				iterator.remove();
				continue;
			}

			if (ServerBackroomsSystem.isInBackrooms(player) || stabilityPercent > state.expireAboveStabilityPercent) {
				restorePlayer(server, player, state);
				iterator.remove();
			}
		}
	}

	public static void resetRuntimeState() {
		ACTIVE_STATES.clear();
	}

	public static void restoreAll(MinecraftServer server) {
		if (ACTIVE_STATES.isEmpty()) {
			return;
		}

		for (Map.Entry<UUID, ActiveShuffleState> entry : new ArrayList<>(ACTIVE_STATES.entrySet())) {
			ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(entry.getKey());
			if (player != null) {
				restorePlayer(server, player, entry.getValue());
			} else if (entry.getKey() != null) {
				CartelWebcamBridge.endSourceMirror(entry.getKey());
			}
		}
		ACTIVE_STATES.clear();
	}

	@Override
	public String id() {
		return "player_shuffle";
	}

	@Override
	public GlitchConfig.GlitchEntry defaultEntry() {
		GlitchConfig.GlitchEntry entry = new GlitchConfig.GlitchEntry();
		entry.enabled = true;
		entry.minStabilityPercent = 0.0D;
		entry.maxStabilityPercent = 45.0D;
		entry.chancePerCheck = 0.10D;
		entry.stabilityInfluence = 1.0D;
		entry.minCooldownTicks = 200;
		entry.maxCooldownTicks = 3200;

		JsonObject settings = new JsonObject();
		settings.addProperty(MIN_TARGET_PLAYERS, 2);
		settings.addProperty(MAX_TARGET_PLAYERS, 4);
		settings.addProperty(POSITION_SHUFFLE_CHANCE, 0.35D);
		entry.settings = settings;
		return entry;
	}

	@Override
	public boolean sanitizeSettings(GlitchConfig.GlitchEntry entry) {
		if (entry.settings == null) {
			entry.settings = new JsonObject();
		}

		boolean changed = false;
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MIN_TARGET_PLAYERS, 2, 2, 100);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_TARGET_PLAYERS, 4, 2, 100);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, POSITION_SHUFFLE_CHANCE, 0.35D, 0.0D, 1.0D);
		changed |= entry.settings.remove("minDurationSeconds") != null;
		changed |= entry.settings.remove("maxDurationSeconds") != null;

		int minTargets = GlitchSettingsHelper.getInt(entry.settings, MIN_TARGET_PLAYERS, 2);
		int maxTargets = Math.max(2, GlitchSettingsHelper.getInt(entry.settings, MAX_TARGET_PLAYERS, 4));
		boolean minTargetExpression = GlitchSettingsHelper.isExpression(entry.settings, MIN_TARGET_PLAYERS);
		boolean maxTargetExpression = GlitchSettingsHelper.isExpression(entry.settings, MAX_TARGET_PLAYERS);
		if (!minTargetExpression && !maxTargetExpression && maxTargets < minTargets) {
			entry.settings.addProperty(MAX_TARGET_PLAYERS, minTargets);
			changed = true;
		}

		return changed;
	}

	@Override
	public boolean trigger(MinecraftServer server, RandomSource random, GlitchConfig.GlitchEntry entry, double stabilityPercent) {
		List<ServerPlayer> players = collectEligiblePlayers(server);
		if (players.size() < 2) {
			return false;
		}

		JsonObject settings = entry.settings == null ? new JsonObject() : entry.settings;
		double instability = getRangeInstabilityFactor(stabilityPercent, entry.minStabilityPercent, entry.maxStabilityPercent);
		int minTargets = GlitchSettingsHelper.getInt(settings, MIN_TARGET_PLAYERS, 2);
		int maxTargets = Math.max(2, GlitchSettingsHelper.getInt(settings, MAX_TARGET_PLAYERS, 4));
		int targetCount = pickWeightedTargetCount(random, minTargets, maxTargets, instability);
		targetCount = Math.max(2, Math.min(players.size(), targetCount));

		double positionShuffleChance = GlitchSettingsHelper.getDouble(settings, POSITION_SHUFFLE_CHANCE, 0.35D);

		shuffle(players, random);
		List<ServerPlayer> selectedPlayers = new ArrayList<>(players.subList(0, targetCount));
		if (selectedPlayers.size() < 2) {
			return false;
		}

		List<ServerPlayer> shuffledSources = new ArrayList<>(selectedPlayers);
		shuffle(shuffledSources, random);
		int offset = 1 + random.nextInt(shuffledSources.size() - 1);

		List<PlayerSnapshot> sourceSnapshots = captureSnapshots(shuffledSources);
		List<Assignment> assignments = new ArrayList<>(selectedPlayers.size());
		for (int i = 0; i < selectedPlayers.size(); i++) {
			ServerPlayer target = selectedPlayers.get(i);
			ServerPlayer source = shuffledSources.get((i + offset) % shuffledSources.size());
			ActiveShuffleState existingState = ACTIVE_STATES.get(target.getUUID());
			SkinValue originalSkin = existingState != null ? existingState.originalSkin : captureCurrentSkinValue(target);
			SkinValue shuffledSkin = captureCurrentSkinValue(source);
			assignments.add(new Assignment(target, source, originalSkin, shuffledSkin));
		}

		boolean appliedAny = false;
		for (Assignment assignment : assignments) {
			if (assignment.originalSkin == null || assignment.shuffledSkin == null) {
				continue;
			}
			applySkin(server, assignment.target, assignment.shuffledSkin);
			CartelWebcamBridge.beginSourceMirror(assignment.target.getUUID(), assignment.source.getUUID());
			ACTIVE_STATES.put(
					assignment.target.getUUID(),
					new ActiveShuffleState(assignment.originalSkin, entry.maxStabilityPercent + 10.0D)
			);
			appliedAny = true;
		}

		if (!appliedAny) {
			return false;
		}

		if (positionShuffleChance > 0.0D && random.nextDouble() <= positionShuffleChance) {
			Map<UUID, PlayerSnapshot> snapshotByPlayer = new HashMap<>();
			for (int i = 0; i < shuffledSources.size(); i++) {
				snapshotByPlayer.put(shuffledSources.get(i).getUUID(), sourceSnapshots.get(i));
			}
			teleportAccordingToAssignments(assignments, snapshotByPlayer);
		}

		return true;
	}

	private static List<ServerPlayer> collectEligiblePlayers(MinecraftServer server) {
		List<ServerPlayer> players = new ArrayList<>();
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			if (player == null || !player.isAlive() || player.isSpectator() || ServerBackroomsSystem.isInBackrooms(player)) {
				continue;
			}
			players.add(player);
		}
		return players;
	}

	private static List<PlayerSnapshot> captureSnapshots(List<ServerPlayer> players) {
		List<PlayerSnapshot> snapshots = new ArrayList<>(players.size());
		for (ServerPlayer player : players) {
			snapshots.add(new PlayerSnapshot(
					player.level(),
					player.getX(),
					player.getY(),
					player.getZ(),
					player.getYRot(),
					player.getXRot()
			));
		}
		return snapshots;
	}

	private static void teleportAccordingToAssignments(List<Assignment> assignments, Map<UUID, PlayerSnapshot> snapshotByPlayer) {
		for (Assignment assignment : assignments) {
			PlayerSnapshot snapshot = snapshotByPlayer.get(assignment.source.getUUID());
			if (snapshot == null) {
				continue;
			}
			ServerLevel level = snapshot.level;
			level.getChunkAt(BlockPos.containing(snapshot.x, snapshot.y, snapshot.z));
			assignment.target.teleportTo(
					level,
					snapshot.x,
					snapshot.y,
					snapshot.z,
					ABSOLUTE_TELEPORT,
					snapshot.yaw,
					snapshot.pitch,
					false
			);
		}
	}

	private static void applySkin(MinecraftServer server, ServerPlayer player, SkinValue skinValue) {
		if (server == null || player == null || skinValue == null) {
			return;
		}
		SkinService.applySkin(server, List.of(player), skinValue, false);
	}

	private static void restorePlayer(MinecraftServer server, ServerPlayer player, ActiveShuffleState state) {
		if (player == null || state == null) {
			return;
		}

		CartelWebcamBridge.endSourceMirror(player.getUUID());
		if (server != null && state.originalSkin != null) {
			applySkin(server, player, state.originalSkin);
		}
	}

	private static SkinValue captureCurrentSkinValue(ServerPlayer player) {
		SkinStorage skinStorage = SkinRestorer.getSkinStorage();
		if (skinStorage != null && skinStorage.hasSavedSkin(player.getUUID())) {
			SkinValue stored = skinStorage.getSkin(player.getUUID());
			if (stored != null) {
				return stored;
			}
		}

		Property current = PlayerUtils.getPlayerSkin(player.getGameProfile());
		SkinVariant variant = resolveVariant(current);
		return new SkinValue("lg2_player_shuffle", player.getScoreboardName(), variant, current, current);
	}

	private static SkinVariant resolveVariant(Property property) {
		Pair<String, SkinVariant> skinData = PlayerUtils.getSkinUrl(property);
		if (skinData != null && skinData.right() != null) {
			return skinData.right();
		}
		return SkinVariant.CLASSIC;
	}

	private static int pickWeightedTargetCount(RandomSource random, int minTargets, int maxTargets, double instabilityFactor) {
		int safeMin = Math.max(2, minTargets);
		int safeMax = Math.max(safeMin, maxTargets);
		int dynamicMax = interpolateInt(safeMin, safeMax, instabilityFactor);
		if (dynamicMax <= safeMin) {
			return safeMin;
		}

		double weightedRoll = 1.0D - Math.pow(1.0D - random.nextDouble(), TARGET_COUNT_PRIORITY);
		double sampled = safeMin + (weightedRoll * (dynamicMax - safeMin));
		return Math.max(safeMin, Math.min(dynamicMax, (int) Math.round(sampled)));
	}

	private static int interpolateInt(int min, int max, double factor) {
		if (max <= min) {
			return min;
		}
		return min + (int) Math.round((max - min) * clamp01(factor));
	}

	private static double getRangeInstabilityFactor(double stabilityPercent, double minStabilityPercent, double maxStabilityPercent) {
		double range = maxStabilityPercent - minStabilityPercent;
		if (range <= 1.0E-9D) {
			return 1.0D;
		}

		double normalized = (stabilityPercent - minStabilityPercent) / range;
		return clamp01(1.0D - normalized);
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	private static void shuffle(List<?> list, RandomSource random) {
		for (int i = list.size() - 1; i > 0; i--) {
			int swapWith = random.nextInt(i + 1);
			Collections.swap(list, i, swapWith);
		}
	}

	private static final class Assignment {
		private final ServerPlayer target;
		private final ServerPlayer source;
		private final SkinValue originalSkin;
		private final SkinValue shuffledSkin;

		private Assignment(ServerPlayer target, ServerPlayer source, SkinValue originalSkin, SkinValue shuffledSkin) {
			this.target = target;
			this.source = source;
			this.originalSkin = originalSkin;
			this.shuffledSkin = shuffledSkin;
		}
	}

	private static final class ActiveShuffleState {
		private final SkinValue originalSkin;
		private final double expireAboveStabilityPercent;

		private ActiveShuffleState(SkinValue originalSkin, double expireAboveStabilityPercent) {
			this.originalSkin = originalSkin;
			this.expireAboveStabilityPercent = expireAboveStabilityPercent;
		}
	}

	private static final class PlayerSnapshot {
		private final ServerLevel level;
		private final double x;
		private final double y;
		private final double z;
		private final float yaw;
		private final float pitch;

		private PlayerSnapshot(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
			this.level = level;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
		}
	}
}
