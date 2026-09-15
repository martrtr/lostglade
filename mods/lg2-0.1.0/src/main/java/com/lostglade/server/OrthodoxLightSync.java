package com.lostglade.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class OrthodoxLightSync {
	private static final PendingUpdates<ResourceKey<Level>> UPDATES = new PendingUpdates<>();

	private OrthodoxLightSync() {
	}

	static void retain(ServerLevel level, BlockPos source) {
		UPDATES.retain(level.dimension(), source.getX(), source.getZ());
	}

	static void releaseAfterUpdate(ServerLevel level, BlockPos source) {
		var engine = level.getChunkSource().getLightEngine();
		// Keep receiving removal notifications until the lighting thread has published them.
		engine.waitForPendingTasks(source.getX() >> 4, source.getZ() >> 4).thenRun(() ->
				UPDATES.release(level.dimension(), source.getX(), source.getZ()));
		engine.tryScheduleUpdate();
	}

	public static void onLightUpdate(ServerLevel level, LightLayer layer, SectionPos section) {
		if (layer == LightLayer.BLOCK) {
			UPDATES.mark(level.dimension(), section.x(), section.y(), section.z());
		}
	}

	static void flush(MinecraftServer server) {
		for (var entry : UPDATES.drain().entrySet()) {
			var key = entry.getKey();
			ServerLevel level = server.getLevel(key.dimension());
			if (level == null) continue;
			var chunks = level.getChunkSource();
			ChunkPos pos = new ChunkPos(key.x(), key.z());
			// Vanilla broadcasts light-only changes to tracking-border viewers. Virtual
			// emission has no block update for interior viewers to recalculate locally.
			var viewers = chunks.chunkMap.getPlayers(pos, false);
			if (viewers.isEmpty()) continue;
			var engine = chunks.getLightEngine();
			BitSet mask = sectionMask(entry.getValue(), engine.getMinLightSection(), engine.getLightSectionCount());
			if (mask.isEmpty()) continue;
			var packet = new ClientboundLightUpdatePacket(pos, engine, new BitSet(), mask);
			for (var viewer : viewers) viewer.connection.send(packet);
		}
	}

	static BitSet sectionMask(Set<Integer> sections, int minSection, int count) {
		BitSet mask = new BitSet(count);
		for (int section : sections) {
			int index = section - minSection;
			if (index >= 0 && index < count) mask.set(index);
		}
		return mask;
	}

	static void clear() {
		UPDATES.clear();
	}

	// Lighting callbacks run off-thread. Drain swaps ownership of the batch so a
	// simultaneous update cannot be lost between packet creation and clearing.
	static final class PendingUpdates<D> {
		private final Map<ChunkKey<D>, Integer> watched = new HashMap<>();
		private Map<ChunkKey<D>, Set<Integer>> pending = new HashMap<>();

		synchronized void retain(D dimension, int blockX, int blockZ) {
			changeReferences(dimension, blockX, blockZ, 1);
		}

		synchronized void release(D dimension, int blockX, int blockZ) {
			changeReferences(dimension, blockX, blockZ, -1);
		}

		private void changeReferences(D dimension, int blockX, int blockZ, int delta) {
			int centerX = Math.floorDiv(blockX, 16);
			int centerZ = Math.floorDiv(blockZ, 16);
			// Level 12 light can reach only the source chunk and its eight neighbors.
			for (int x = centerX - 1; x <= centerX + 1; x++) {
				for (int z = centerZ - 1; z <= centerZ + 1; z++) {
					var key = new ChunkKey<>(dimension, x, z);
					int count = watched.getOrDefault(key, 0) + delta;
					if (count > 0) watched.put(key, count);
					else watched.remove(key);
				}
			}
		}

		synchronized void mark(D dimension, int chunkX, int sectionY, int chunkZ) {
			var key = new ChunkKey<>(dimension, chunkX, chunkZ);
			if (watched.containsKey(key)) pending.computeIfAbsent(key, ignored -> new HashSet<>()).add(sectionY);
		}

		synchronized Map<ChunkKey<D>, Set<Integer>> drain() {
			var batch = pending;
			pending = new HashMap<>();
			return batch;
		}

		synchronized void clear() {
			watched.clear();
			pending.clear();
		}
	}

	record ChunkKey<D>(D dimension, int x, int z) {
	}
}
