package com.lostglade.server.glitch;

import com.lostglade.server.AccountAuthSystem;

import com.google.gson.JsonObject;
import com.lostglade.Lg2;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.ServerBackroomsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.util.datafix.DataFixTypes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class PhantomChunkGlitch implements ServerGlitchHandler {
	private static final String MIN_TARGET_PLAYERS = "minTargetPlayers";
	private static final String MAX_TARGET_PLAYERS = "maxTargetPlayers";
	private static final String MIN_DURATION_SECONDS = "minDurationSeconds";
	private static final String MAX_DURATION_SECONDS = "maxDurationSeconds";
	private static final String MIN_TARGET_DISTANCE_CHUNKS = "minTargetDistanceChunks";
	private static final String MAX_TARGET_DISTANCE_CHUNKS = "maxTargetDistanceChunks";
	private static final String REPLACEMENT_CHUNK_WEIGHT = "replacementChunkWeight";
	private static final String INTANGIBLE_CHUNK_WEIGHT = "intangibleChunkWeight";
	private static final String BACKROOMS_TELEPORT_CHANCE = "backroomsTeleportChance";
	private static final double TARGET_COUNT_PRIORITY = 2.4D;
	private static final int APPLY_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
	private static final int BACKROOMS_TELEPORT_DELAY_TICKS = 20;
	private static final Map<ChunkKey, ActivePhantomChunkState> ACTIVE_STATES = new LinkedHashMap<>();
	private static final Map<java.util.UUID, Long> PENDING_BACKROOMS_TELEPORTS = new HashMap<>();

	public static void tickActiveStates(MinecraftServer server) {
		if (server == null) {
			return;
		}

		tickPendingBackroomsTeleports(server);
		if (ACTIVE_STATES.isEmpty()) {
			return;
		}

		long nowTick = server.overworld().getGameTime();
		Iterator<Map.Entry<ChunkKey, ActivePhantomChunkState>> iterator = ACTIVE_STATES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<ChunkKey, ActivePhantomChunkState> mapEntry = iterator.next();
			ActivePhantomChunkState state = mapEntry.getValue();
			if (nowTick < state.endTick) {
				continue;
			}

			restoreSnapshot(server, state);
			iterator.remove();
		}
	}

	public static void resetRuntimeState() {
		ACTIVE_STATES.clear();
		PENDING_BACKROOMS_TELEPORTS.clear();
	}

	public static void restoreAll(MinecraftServer server) {
		if (server != null) {
			for (ActivePhantomChunkState state : new ArrayList<>(ACTIVE_STATES.values())) {
				restoreSnapshot(server, state);
			}
		}
		ACTIVE_STATES.clear();
		PENDING_BACKROOMS_TELEPORTS.clear();
	}

	@Override
	public String id() {
		return "phantom_chunk";
	}

	@Override
	public GlitchConfig.GlitchEntry defaultEntry() {
		GlitchConfig.GlitchEntry entry = new GlitchConfig.GlitchEntry();
		entry.enabled = true;
		entry.minStabilityPercent = 0.0D;
		entry.maxStabilityPercent = 45.0D;
		entry.chancePerCheck = 0.12D;
		entry.stabilityInfluence = 1.0D;
		entry.minCooldownTicks = 200;
		entry.maxCooldownTicks = 3200;

		JsonObject settings = new JsonObject();
		settings.addProperty(MIN_TARGET_PLAYERS, 1);
		settings.addProperty(MAX_TARGET_PLAYERS, 3);
		settings.addProperty(MIN_DURATION_SECONDS, 8);
		settings.addProperty(MAX_DURATION_SECONDS, 24);
		settings.addProperty(MIN_TARGET_DISTANCE_CHUNKS, 0);
		settings.addProperty(MAX_TARGET_DISTANCE_CHUNKS, 1);
		settings.addProperty(REPLACEMENT_CHUNK_WEIGHT, 1.0D);
		settings.addProperty(INTANGIBLE_CHUNK_WEIGHT, 1.0D);
		settings.addProperty(BACKROOMS_TELEPORT_CHANCE, 0.35D);
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
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MIN_DURATION_SECONDS, 8, 1, 3600);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_DURATION_SECONDS, 24, 1, 3600);
		boolean hasMinDistance = entry.settings.has(MIN_TARGET_DISTANCE_CHUNKS);
		boolean hasMaxDistance = entry.settings.has(MAX_TARGET_DISTANCE_CHUNKS);
		int legacyRadius = GlitchSettingsHelper.getInt(entry.settings, "targetChunkRadius", 1);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MIN_TARGET_DISTANCE_CHUNKS, hasMinDistance ? 0 : 0, 0, 8);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_TARGET_DISTANCE_CHUNKS, hasMaxDistance ? 1 : legacyRadius, 0, 8);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, REPLACEMENT_CHUNK_WEIGHT, 1.0D, 0.0D, 1000.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, INTANGIBLE_CHUNK_WEIGHT, 1.0D, 0.0D, 1000.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, BACKROOMS_TELEPORT_CHANCE, 0.35D, 0.0D, 1.0D);
		changed |= entry.settings.remove("targetChunkRadius") != null;
		changed |= entry.settings.remove("worldChunkWeight") != null;
		changed |= entry.settings.remove("generatedChunkWeight") != null;

		int minTargets = GlitchSettingsHelper.getInt(entry.settings, MIN_TARGET_PLAYERS, 1);
		int maxTargets = Math.max(1, GlitchSettingsHelper.getInt(entry.settings, MAX_TARGET_PLAYERS, 3));
		boolean minTargetExpression = GlitchSettingsHelper.isExpression(entry.settings, MIN_TARGET_PLAYERS);
		boolean maxTargetExpression = GlitchSettingsHelper.isExpression(entry.settings, MAX_TARGET_PLAYERS);
		if (!minTargetExpression && !maxTargetExpression && maxTargets < minTargets) {
			entry.settings.addProperty(MAX_TARGET_PLAYERS, minTargets);
			changed = true;
		}

		int minDurationSeconds = GlitchSettingsHelper.getInt(entry.settings, MIN_DURATION_SECONDS, 8);
		int maxDurationSeconds = GlitchSettingsHelper.getInt(entry.settings, MAX_DURATION_SECONDS, 24);
		if (maxDurationSeconds < minDurationSeconds) {
			entry.settings.addProperty(MAX_DURATION_SECONDS, minDurationSeconds);
			changed = true;
		}

		int minDistanceChunks = GlitchSettingsHelper.getInt(entry.settings, MIN_TARGET_DISTANCE_CHUNKS, 0);
		int maxDistanceChunks = GlitchSettingsHelper.getInt(entry.settings, MAX_TARGET_DISTANCE_CHUNKS, 1);
		if (maxDistanceChunks < minDistanceChunks) {
			entry.settings.addProperty(MAX_TARGET_DISTANCE_CHUNKS, minDistanceChunks);
			changed = true;
		}

		return changed;
	}

	@Override
	public boolean trigger(MinecraftServer server, RandomSource random, GlitchConfig.GlitchEntry entry, double stabilityPercent) {
		tickActiveStates(server);

		List<ServerPlayer> players = collectEligiblePlayers(server);
		if (players.isEmpty()) {
			return false;
		}

		JsonObject settings = entry.settings == null ? new JsonObject() : entry.settings;
		double instability = getRangeInstabilityFactor(stabilityPercent, entry.minStabilityPercent, entry.maxStabilityPercent);
		int minTargets = GlitchSettingsHelper.getInt(settings, MIN_TARGET_PLAYERS, 1);
		int maxTargets = Math.max(1, GlitchSettingsHelper.getInt(settings, MAX_TARGET_PLAYERS, 3));
		int targetCount = pickWeightedTargetCount(random, minTargets, maxTargets, instability);
		targetCount = Math.max(1, Math.min(players.size(), targetCount));

		int minDurationSeconds = GlitchSettingsHelper.getInt(settings, MIN_DURATION_SECONDS, 8);
		int maxDurationSeconds = GlitchSettingsHelper.getInt(settings, MAX_DURATION_SECONDS, 24);
		int durationSeconds = interpolateInt(minDurationSeconds, maxDurationSeconds, 1.0D - instability);
		long durationTicks = Math.max(20L, durationSeconds * 20L);

		int minTargetDistanceChunks = GlitchSettingsHelper.getInt(settings, MIN_TARGET_DISTANCE_CHUNKS, 0);
		int maxTargetDistanceChunks = GlitchSettingsHelper.getInt(settings, MAX_TARGET_DISTANCE_CHUNKS, 1);
		double replacementWeight = GlitchSettingsHelper.getDouble(settings, REPLACEMENT_CHUNK_WEIGHT, 1.0D);
		double intangibleWeight = GlitchSettingsHelper.getDouble(settings, INTANGIBLE_CHUNK_WEIGHT, 1.0D);
		double backroomsTeleportChance = GlitchSettingsHelper.getDouble(settings, BACKROOMS_TELEPORT_CHANCE, 0.35D);

		Set<ChunkKey> occupiedChunks = new HashSet<>(ACTIVE_STATES.keySet());
		Map<net.minecraft.resources.ResourceKey<Level>, Map<Long, LevelChunk>> loadedChunkSourcesByDimension = new HashMap<>();
		Map<net.minecraft.resources.ResourceKey<Level>, List<ChunkPos>> diskChunkSourcesByDimension = new HashMap<>();
		Map<net.minecraft.resources.ResourceKey<Level>, SimpleRegionStorage> chunkStoragesByDimension = new HashMap<>();
		long nowTick = server.overworld().getGameTime();

		shuffle(players, random);
		boolean appliedAny = false;
		int appliedCount = 0;
		try {
			for (int i = 0; i < players.size() && appliedCount < targetCount; i++) {
				ServerPlayer player = players.get(i);
				if (!(player.level() instanceof ServerLevel level)) {
					continue;
				}

				int targetDistanceChunks = pickTargetDistanceChunks(random, minTargetDistanceChunks, maxTargetDistanceChunks);
				LevelChunk targetChunk = pickTargetChunk(level, player, targetDistanceChunks, random, occupiedChunks);
				if (targetChunk == null) {
					continue;
				}

				ChunkMode mode = pickChunkMode(random, replacementWeight, intangibleWeight);
				ChunkSnapshot snapshot = captureChunkSnapshot(level, targetChunk);
				ChunkSnapshot appliedSnapshot = switch (mode) {
					case REPLACEMENT -> buildReplacementSnapshot(
							level,
							targetChunk,
							snapshot,
							loadedChunkSourcesByDimension.computeIfAbsent(level.dimension(), ignored -> collectLoadedChunkSources(level, occupiedChunks)),
							diskChunkSourcesByDimension.computeIfAbsent(level.dimension(), ignored -> collectSavedChunkPositions(server, level)),
							getOrOpenChunkStorage(server, level, chunkStoragesByDimension),
							occupiedChunks,
							random
					);
					case INTANGIBLE -> buildAirSnapshot(level, targetChunk);
				};
				if (appliedSnapshot == null) {
					continue;
				}

				applySnapshot(level, targetChunk.getPos(), appliedSnapshot);
				tryTeleportPlayersStuckInChangedChunk(level, targetChunk.getPos(), backroomsTeleportChance);
				ChunkKey chunkKey = new ChunkKey(level.dimension(), targetChunk.getPos());
				ACTIVE_STATES.put(chunkKey, new ActivePhantomChunkState(level.dimension(), targetChunk.getPos(), nowTick + durationTicks, backroomsTeleportChance, snapshot));
				occupiedChunks.add(chunkKey);
				appliedCount++;
				appliedAny = true;
			}
		} finally {
			closeChunkStorages(chunkStoragesByDimension.values());
		}

		return appliedAny;
	}

	private static void restoreSnapshot(MinecraftServer server, ActivePhantomChunkState state) {
		ServerLevel level = server.getLevel(state.dimension);
		if (level == null) {
			return;
		}

		applySnapshot(level, state.chunkPos, state.originalSnapshot);
		tryTeleportPlayersStuckInChangedChunk(level, state.chunkPos, state.backroomsTeleportChance);
	}

	private static void tryTeleportPlayersStuckInChangedChunk(ServerLevel level, ChunkPos chunkPos, double backroomsTeleportChance) {
		double safeChance = Math.max(0.0D, Math.min(1.0D, backroomsTeleportChance));
		if (safeChance <= 0.0D) {
			return;
		}

		for (ServerPlayer player : new ArrayList<>(level.players())) {
			if (player == null || !player.isAlive() || player.isSpectator()) {
				continue;
			}
			if (!player.chunkPosition().equals(chunkPos)) {
				continue;
			}
			if (!player.isInWall()) {
				continue;
			}
			if (level.random.nextDouble() >= safeChance) {
				continue;
			}

			scheduleBackroomsTeleport(level.getServer(), player);
		}
	}

	private static void scheduleBackroomsTeleport(MinecraftServer server, ServerPlayer player) {
		if (server == null || player == null) {
			return;
		}

		long nowTick = server.overworld().getGameTime();
		PENDING_BACKROOMS_TELEPORTS.merge(
				player.getUUID(),
				nowTick + BACKROOMS_TELEPORT_DELAY_TICKS,
				Math::min
		);
	}

	private static void tickPendingBackroomsTeleports(MinecraftServer server) {
		if (PENDING_BACKROOMS_TELEPORTS.isEmpty()) {
			return;
		}

		long nowTick = server.overworld().getGameTime();
		Iterator<Map.Entry<java.util.UUID, Long>> iterator = PENDING_BACKROOMS_TELEPORTS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<java.util.UUID, Long> entry = iterator.next();
			if (nowTick < entry.getValue()) {
				continue;
			}

			iterator.remove();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null || !player.isAlive() || player.isSpectator() || ServerBackroomsSystem.isInBackrooms(player)) {
				continue;
			}

			ServerBackroomsSystem.teleportPlayerToBackrooms(player);
		}
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

	private static Map<Long, LevelChunk> collectLoadedChunkSources(ServerLevel level, Set<ChunkKey> occupiedChunks) {
		Map<Long, LevelChunk> sources = new LinkedHashMap<>();
		if (level == null || ServerBackroomsSystem.isBackrooms(level)) {
			return sources;
		}

		level.getChunkSource().chunkMap.forEachReadyToSendChunk(chunk -> {
			ChunkKey key = new ChunkKey(level.dimension(), chunk.getPos());
			if (!occupiedChunks.contains(key)) {
				sources.put(chunk.getPos().toLong(), chunk);
			}
		});
		return sources;
	}

	private static List<ChunkPos> collectSavedChunkPositions(MinecraftServer server, ServerLevel level) {
		if (server == null || level == null || ServerBackroomsSystem.isBackrooms(level)) {
			return List.of();
		}

		Path regionFolder = getRegionFolderPath(server, level.dimension());
		if (regionFolder == null || !Files.isDirectory(regionFolder)) {
			return List.of();
		}

		List<ChunkPos> positions = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionFolder, "r.*.*.mca")) {
			for (Path regionFile : stream) {
				RegionCoordinates regionCoordinates = parseRegionCoordinates(regionFile.getFileName().toString());
				if (regionCoordinates == null) {
					continue;
				}

				collectRegionChunkPositions(regionFile, regionCoordinates.regionX, regionCoordinates.regionZ, positions);
			}
		} catch (IOException exception) {
			Lg2.LOGGER.debug("Failed to scan phantom chunk region directory {}", regionFolder, exception);
		}

		return positions;
	}

	private static LevelChunk pickTargetChunk(
			ServerLevel level,
			ServerPlayer player,
			int distanceChunks,
			RandomSource random,
			Set<ChunkKey> occupiedChunks
	) {
		ChunkPos center = player.chunkPosition();
		List<LevelChunk> candidates = new ArrayList<>();
		for (int dx = -distanceChunks; dx <= distanceChunks; dx++) {
			for (int dz = -distanceChunks; dz <= distanceChunks; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != distanceChunks) {
					continue;
				}
				LevelChunk chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
				if (chunk == null) {
					continue;
				}
				if (occupiedChunks.contains(new ChunkKey(level.dimension(), chunk.getPos()))) {
					continue;
				}
				candidates.add(chunk);
			}
		}

		if (candidates.isEmpty()) {
			return null;
		}

		return candidates.get(random.nextInt(candidates.size()));
	}

	private static int pickTargetDistanceChunks(RandomSource random, int minDistanceChunks, int maxDistanceChunks) {
		int safeMin = Math.max(0, minDistanceChunks);
		int safeMax = Math.max(safeMin, maxDistanceChunks);
		if (safeMax <= safeMin) {
			return safeMin;
		}
		return safeMin + random.nextInt(safeMax - safeMin + 1);
	}

	private static ChunkSnapshot captureChunkSnapshot(ServerLevel level, LevelChunk chunk) {
		return new ChunkSnapshot(copySections(level, chunk, level, chunk), copyBlockEntityTags(level, chunk, chunk.getPos()));
	}

	private static ChunkSnapshot buildReplacementSnapshot(
			ServerLevel targetLevel,
			LevelChunk targetChunk,
			ChunkSnapshot targetSnapshot,
			Map<Long, LevelChunk> loadedChunkSources,
			List<ChunkPos> savedChunkPositions,
			SimpleRegionStorage chunkStorage,
			Set<ChunkKey> occupiedChunks,
			RandomSource random
	) {
		if (targetLevel == null || targetChunk == null) {
			return null;
		}

		Map<Long, ChunkPos> candidatePositions = new LinkedHashMap<>();
		for (ChunkPos savedChunkPos : savedChunkPositions) {
			candidatePositions.putIfAbsent(savedChunkPos.toLong(), savedChunkPos);
		}
		for (Map.Entry<Long, LevelChunk> entry : loadedChunkSources.entrySet()) {
			candidatePositions.putIfAbsent(entry.getKey(), entry.getValue().getPos());
		}
		if (candidatePositions.isEmpty()) {
			return null;
		}

		List<ChunkPos> candidates = new ArrayList<>(candidatePositions.values());
		while (!candidates.isEmpty()) {
			ChunkPos sourceChunkPos = candidates.remove(random.nextInt(candidates.size()));
			if (sourceChunkPos.equals(targetChunk.getPos())) {
				continue;
			}
			if (occupiedChunks.contains(new ChunkKey(targetLevel.dimension(), sourceChunkPos))) {
				continue;
			}

			LevelChunk loadedChunk = loadedChunkSources.get(sourceChunkPos.toLong());
			ChunkSnapshot snapshot = loadedChunk != null
					? new ChunkSnapshot(
							copySections(targetLevel, targetChunk, targetLevel, loadedChunk),
							copyBlockEntityTags(targetLevel, loadedChunk, targetChunk.getPos())
					)
					: readChunkSnapshotFromDisk(targetLevel, targetChunk, sourceChunkPos, chunkStorage);
			if (snapshot != null && hasVisibleDifference(targetSnapshot, snapshot)) {
				return snapshot;
			}
		}

		return null;
	}

	private static ChunkSnapshot buildAirSnapshot(ServerLevel level, LevelChunk targetChunk) {
		LevelChunkSection[] airSections = createAirSections(level, targetChunk);
		return new ChunkSnapshot(airSections, new HashMap<>());
	}

	private static LevelChunkSection[] createAirSections(ServerLevel level, LevelChunk chunk) {
		LevelChunkSection[] targetSections = chunk.getSections();
		LevelChunkSection[] sections = new LevelChunkSection[targetSections.length];
		for (int i = 0; i < targetSections.length; i++) {
			sections[i] = createAirSection(targetSections[i]);
		}
		return sections;
	}

	private static LevelChunkSection[] copySections(ServerLevel targetLevel, LevelChunk targetChunk, ServerLevel sourceLevel, LevelChunk sourceChunk) {
		LevelChunkSection[] targetSections = targetChunk.getSections();
		LevelChunkSection[] sourceSections = sourceChunk.getSections();
		LevelChunkSection[] copies = new LevelChunkSection[targetSections.length];

		for (int targetSectionIndex = 0; targetSectionIndex < targetSections.length; targetSectionIndex++) {
			int sectionY = targetLevel.getSectionYFromSectionIndex(targetSectionIndex);
			int sourceSectionIndex = sourceLevel.getSectionIndexFromSectionY(sectionY);
			if (sourceSectionIndex >= 0 && sourceSectionIndex < sourceSections.length) {
				copies[targetSectionIndex] = sourceSections[sourceSectionIndex].copy();
			} else {
				copies[targetSectionIndex] = createAirSection(targetSections[targetSectionIndex]);
			}
		}

		return copies;
	}

	private static LevelChunkSection[] copySectionsFromSerializedChunk(ServerLevel targetLevel, LevelChunk targetChunk, SerializableChunkData serializedChunkData) {
		LevelChunkSection[] copies = createAirSections(targetLevel, targetChunk);
		for (SerializableChunkData.SectionData sectionData : serializedChunkData.sectionData()) {
			LevelChunkSection chunkSection = sectionData.chunkSection();
			if (chunkSection == null) {
				continue;
			}

			int targetSectionIndex = targetLevel.getSectionIndexFromSectionY(sectionData.y());
			if (targetSectionIndex < 0 || targetSectionIndex >= copies.length) {
				continue;
			}
			copies[targetSectionIndex] = chunkSection.copy();
		}
		return copies;
	}

	private static Map<BlockPos, CompoundTag> copyBlockEntityTags(ServerLevel sourceLevel, LevelChunk sourceChunk, ChunkPos targetChunkPos) {
		Map<BlockPos, CompoundTag> tags = new HashMap<>();
		for (BlockEntity blockEntity : sourceChunk.getBlockEntities().values()) {
			CompoundTag tag = blockEntity.saveWithFullMetadata(sourceLevel.registryAccess());
			BlockPos sourcePos = blockEntity.getBlockPos();
			BlockPos targetPos = new BlockPos(
					targetChunkPos.getMinBlockX() + (sourcePos.getX() & 15),
					sourcePos.getY(),
					targetChunkPos.getMinBlockZ() + (sourcePos.getZ() & 15)
			);
			tag.putInt("x", targetPos.getX());
			tag.putInt("y", targetPos.getY());
			tag.putInt("z", targetPos.getZ());
			tags.put(targetPos.immutable(), tag);
		}
		return tags;
	}

	private static Map<BlockPos, CompoundTag> copySerializedBlockEntityTags(SerializableChunkData serializedChunkData, ChunkPos targetChunkPos) {
		Map<BlockPos, CompoundTag> tags = new HashMap<>();
		for (CompoundTag sourceTag : serializedChunkData.blockEntities()) {
			CompoundTag tag = sourceTag.copy();
			int sourceX = tag.getIntOr("x", 0);
			int sourceY = tag.getIntOr("y", 0);
			int sourceZ = tag.getIntOr("z", 0);
			BlockPos targetPos = new BlockPos(
					targetChunkPos.getMinBlockX() + (sourceX & 15),
					sourceY,
					targetChunkPos.getMinBlockZ() + (sourceZ & 15)
			);
			tag.putInt("x", targetPos.getX());
			tag.putInt("y", targetPos.getY());
			tag.putInt("z", targetPos.getZ());
			tags.put(targetPos.immutable(), tag);
		}
		return tags;
	}

	private static void applySnapshot(ServerLevel level, ChunkPos chunkPos, ChunkSnapshot snapshot) {
		LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
		BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
		int minY = level.getMinY();
		int maxY = level.getMaxY();
		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int worldX = chunkPos.getMinBlockX() + localX;
				int worldZ = chunkPos.getMinBlockZ() + localZ;
				for (int worldY = minY; worldY < maxY; worldY++) {
					BlockStateSnapshot stateSnapshot = getSnapshotBlockState(level, snapshot.sections, localX, worldY, localZ);
					if (stateSnapshot == null) {
						continue;
					}

					mutablePos.set(worldX, worldY, worldZ);
					if (level.getBlockState(mutablePos).equals(stateSnapshot.state)) {
						continue;
					}

					level.setBlock(mutablePos, stateSnapshot.state, APPLY_FLAGS);
				}
			}
		}

		List<BlockPos> existingBlockEntities = new ArrayList<>(chunk.getBlockEntities().keySet());
		for (BlockPos blockEntityPos : existingBlockEntities) {
			if (!snapshot.blockEntityTags.containsKey(blockEntityPos)) {
				chunk.removeBlockEntity(blockEntityPos);
			}
		}

		for (Map.Entry<BlockPos, CompoundTag> entry : snapshot.blockEntityTags.entrySet()) {
			BlockPos pos = entry.getKey();
			BlockEntity loaded = BlockEntity.loadStatic(pos, level.getBlockState(pos), entry.getValue().copy(), level.registryAccess());
			if (loaded == null) {
				continue;
			}
			chunk.removeBlockEntity(pos);
			chunk.setBlockEntity(loaded);
		}
		chunk.registerAllBlockEntitiesAfterLevelLoad();
	}

	private static BlockStateSnapshot getSnapshotBlockState(ServerLevel level, LevelChunkSection[] sections, int localX, int worldY, int localZ) {
		int sectionIndex = level.getSectionIndex(worldY);
		if (sectionIndex < 0 || sectionIndex >= sections.length) {
			return null;
		}

		LevelChunkSection section = sections[sectionIndex];
		if (section == null) {
			return null;
		}

		return new BlockStateSnapshot(section.getBlockState(localX, worldY & 15, localZ));
	}

	private static boolean hasVisibleDifference(ChunkSnapshot first, ChunkSnapshot second) {
		if (first == null || second == null) {
			return false;
		}

		LevelChunkSection[] firstSections = first.sections;
		LevelChunkSection[] secondSections = second.sections;
		int sectionCount = Math.min(firstSections.length, secondSections.length);
		for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
			LevelChunkSection firstSection = firstSections[sectionIndex];
			LevelChunkSection secondSection = secondSections[sectionIndex];
			if (firstSection == null || secondSection == null) {
				if (firstSection != secondSection) {
					return true;
				}
				continue;
			}

			for (int x = 0; x < 16; x++) {
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						if (!firstSection.getBlockState(x, y, z).equals(secondSection.getBlockState(x, y, z))) {
							return true;
						}
					}
				}
			}
		}

		return first.blockEntityTags.size() != second.blockEntityTags.size();
	}

	private static LevelChunkSection createAirSection(LevelChunkSection template) {
		LevelChunkSection section = template.copy();
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					section.setBlockState(x, y, z, air, false);
				}
			}
		}
		section.recalcBlockCounts();
		return section;
	}

	private static ChunkMode pickChunkMode(RandomSource random, double replacementWeight, double intangibleWeight) {
		boolean replacementFirst = pickWeightedBranch(random, true, true, replacementWeight, intangibleWeight);
		return replacementFirst ? ChunkMode.REPLACEMENT : ChunkMode.INTANGIBLE;
	}

	private static boolean pickWeightedBranch(
			RandomSource random,
			boolean firstAvailable,
			boolean secondAvailable,
			double firstWeight,
			double secondWeight
	) {
		if (firstAvailable && !secondAvailable) {
			return true;
		}
		if (!firstAvailable && secondAvailable) {
			return false;
		}
		if (!firstAvailable) {
			return false;
		}

		double safeFirstWeight = Math.max(0.0D, firstWeight);
		double safeSecondWeight = Math.max(0.0D, secondWeight);
		double totalWeight = safeFirstWeight + safeSecondWeight;
		if (totalWeight <= 1.0E-9D) {
			return random.nextBoolean();
		}

		return random.nextDouble() * totalWeight < safeFirstWeight;
	}

	private static int pickWeightedTargetCount(RandomSource random, int minTargets, int maxTargets, double instabilityFactor) {
		int safeMin = Math.max(1, minTargets);
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

	private static SimpleRegionStorage getOrOpenChunkStorage(
			MinecraftServer server,
			ServerLevel level,
			Map<net.minecraft.resources.ResourceKey<Level>, SimpleRegionStorage> storagesByDimension
	) {
		SimpleRegionStorage existing = storagesByDimension.get(level.dimension());
		if (existing != null) {
			return existing;
		}

		SimpleRegionStorage opened = openChunkStorage(server, level);
		if (opened != null) {
			storagesByDimension.put(level.dimension(), opened);
		}
		return opened;
	}

	private static SimpleRegionStorage openChunkStorage(MinecraftServer server, ServerLevel level) {
		if (server == null || level == null || ServerBackroomsSystem.isBackrooms(level)) {
			return null;
		}

		Path regionFolder = getRegionFolderPath(server, level.dimension());
		if (regionFolder == null || !Files.isDirectory(regionFolder)) {
			return null;
		}

		try {
			return new SimpleRegionStorage(
					new RegionStorageInfo(server.getWorldData().getLevelName(), level.dimension(), "chunk"),
					regionFolder,
					server.getFixerUpper(),
					false,
					DataFixTypes.CHUNK
			);
		} catch (Exception exception) {
			Lg2.LOGGER.debug("Failed to open phantom chunk storage for {}", level.dimension().identifier(), exception);
			return null;
		}
	}

	private static void closeChunkStorages(Iterable<SimpleRegionStorage> storages) {
		for (SimpleRegionStorage storage : storages) {
			if (storage == null) {
				continue;
			}
			try {
				storage.close();
			} catch (IOException exception) {
				Lg2.LOGGER.debug("Failed to close phantom chunk storage", exception);
			}
		}
	}

	private static ChunkSnapshot readChunkSnapshotFromDisk(
			ServerLevel targetLevel,
			LevelChunk targetChunk,
			ChunkPos sourceChunkPos,
			SimpleRegionStorage chunkStorage
	) {
		try {
			Optional<CompoundTag> optionalChunkTag = chunkStorage.read(sourceChunkPos).join();
			if (optionalChunkTag.isEmpty()) {
				return null;
			}

			CompoundTag chunkTag = optionalChunkTag.get();
			ChunkStatus chunkStatus = SerializableChunkData.getChunkStatusFromTag(chunkTag);
			if (chunkStatus != null && chunkStatus.isBefore(ChunkStatus.FULL)) {
				return null;
			}

			SerializableChunkData serializedChunkData = SerializableChunkData.parse(
					targetLevel,
					PalettedContainerFactory.create(targetLevel.registryAccess()),
					chunkTag
			);
			return new ChunkSnapshot(
					copySectionsFromSerializedChunk(targetLevel, targetChunk, serializedChunkData),
					copySerializedBlockEntityTags(serializedChunkData, targetChunk.getPos())
			);
		} catch (Exception exception) {
			Lg2.LOGGER.debug("Failed to read phantom chunk source {} from disk", sourceChunkPos, exception);
			return null;
		}
	}

	private static Path getRegionFolderPath(MinecraftServer server, net.minecraft.resources.ResourceKey<Level> dimension) {
		if (server == null || dimension == null || ServerBackroomsSystem.isBackrooms(dimension)) {
			return null;
		}

		Path worldRoot = server.getWorldPath(LevelResource.ROOT);
		if (Level.OVERWORLD.equals(dimension)) {
			return worldRoot.resolve("region");
		}
		if (Level.NETHER.equals(dimension)) {
			return worldRoot.resolve("DIM-1").resolve("region");
		}
		if (Level.END.equals(dimension)) {
			return worldRoot.resolve("DIM1").resolve("region");
		}

		Identifier dimensionId = dimension.identifier();
		return worldRoot
				.resolve("dimensions")
				.resolve(dimensionId.getNamespace())
				.resolve(dimensionId.getPath())
				.resolve("region");
	}

	private static RegionCoordinates parseRegionCoordinates(String fileName) {
		String[] parts = fileName.split("\\.");
		if (parts.length != 4 || !"r".equals(parts[0]) || !"mca".equals(parts[3])) {
			return null;
		}

		try {
			return new RegionCoordinates(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static void collectRegionChunkPositions(Path regionFile, int regionX, int regionZ, List<ChunkPos> positions) {
		try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
			if (channel.size() < 4096L) {
				return;
			}

			ByteBuffer header = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
			while (header.hasRemaining()) {
				if (channel.read(header) < 0) {
					return;
				}
			}
			header.flip();

			for (int index = 0; index < 1024; index++) {
				int locationEntry = header.getInt();
				int sectorOffset = (locationEntry >>> 8) & 0xFFFFFF;
				int sectorCount = locationEntry & 0xFF;
				if (sectorOffset <= 0 || sectorCount <= 0) {
					continue;
				}

				int localX = index & 31;
				int localZ = index >> 5;
				positions.add(new ChunkPos((regionX << 5) + localX, (regionZ << 5) + localZ));
			}
		} catch (IOException exception) {
			Lg2.LOGGER.debug("Failed to scan phantom chunk region file {}", regionFile, exception);
		}
	}

	private record ChunkKey(net.minecraft.resources.ResourceKey<Level> dimension, ChunkPos pos) {
	}

	private record ChunkSnapshot(LevelChunkSection[] sections, Map<BlockPos, CompoundTag> blockEntityTags) {
	}

	private record ActivePhantomChunkState(
			net.minecraft.resources.ResourceKey<Level> dimension,
			ChunkPos chunkPos,
			long endTick,
			double backroomsTeleportChance,
			ChunkSnapshot originalSnapshot
	) {
	}

	private record BlockStateSnapshot(BlockState state) {
	}

	private record RegionCoordinates(int regionX, int regionZ) {
	}

	private enum ChunkMode {
		REPLACEMENT,
		INTANGIBLE
	}

}
