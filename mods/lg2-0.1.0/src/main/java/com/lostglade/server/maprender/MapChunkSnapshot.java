package com.lostglade.server.maprender;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.LevelChunkTicks;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MapChunkSnapshot {
	private final ChunkPos pos;
	private final MapSnapshotManifest.Role role;
	private final boolean lightCorrect;
	private final List<SectionSnapshot> sections;
	private final Map<Heightmap.Types, long[]> heightmaps;
	private final List<CompoundTag> blockEntities;
	private final List<CompoundTag> itemDisplays;

	private MapChunkSnapshot(
			ChunkPos pos,
			MapSnapshotManifest.Role role,
			boolean lightCorrect,
			List<SectionSnapshot> sections,
			Map<Heightmap.Types, long[]> heightmaps,
			List<CompoundTag> blockEntities,
			List<CompoundTag> itemDisplays
	) {
		this.pos = Objects.requireNonNull(pos, "pos");
		this.role = Objects.requireNonNull(role, "role");
		this.lightCorrect = lightCorrect;
		this.sections = List.copyOf(sections);
		this.heightmaps = copyHeightmaps(heightmaps);
		this.blockEntities = copyTags(blockEntities);
		this.itemDisplays = copyTags(itemDisplays);
	}

	static MapChunkSnapshot from(
			SerializableChunkData data,
			MapSnapshotManifest.Role role,
			List<CompoundTag> itemDisplays
	) {
		List<SectionSnapshot> sections = new ArrayList<>();
		for (SerializableChunkData.SectionData section : data.sectionData()) {
			if (section == null || section.chunkSection() == null) {
				continue;
			}
			sections.add(new SectionSnapshot(
					section.y(),
					section.chunkSection().copy(),
					section.blockLight() == null ? null : section.blockLight().copy(),
					section.skyLight() == null ? null : section.skyLight().copy()
			));
		}
		return new MapChunkSnapshot(
				data.chunkPos(),
				role,
				data.lightCorrect(),
				sections,
				data.heightmaps(),
				data.blockEntities(),
				itemDisplays
		);
	}

	public ChunkPos pos() {
		return this.pos;
	}

	public MapSnapshotManifest.Role role() {
		return this.role;
	}

	public boolean lightCorrect() {
		return this.lightCorrect;
	}

	public List<SectionSnapshot> sections() {
		return this.sections;
	}

	public List<CompoundTag> itemDisplays() {
		return copyTags(this.itemDisplays);
	}

	LevelChunk createDetachedChunk(ServerLevel level) {
		PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
		LevelChunkSection[] chunkSections = new LevelChunkSection[level.getSectionsCount()];
		for (int i = 0; i < chunkSections.length; i++) {
			chunkSections[i] = new LevelChunkSection(factory);
		}
		for (SectionSnapshot section : this.sections) {
			int index = level.getSectionIndexFromSectionY(section.y());
			if (index >= 0 && index < chunkSections.length) {
				chunkSections[index] = section.chunkSection().copy();
			}
		}
		LevelChunk chunk = new LevelChunk(
				level,
				this.pos,
				UpgradeData.EMPTY,
				new LevelChunkTicks<>(),
				new LevelChunkTicks<>(),
				0L,
				chunkSections,
				null,
				null
		);
		for (Map.Entry<Heightmap.Types, long[]> entry : this.heightmaps.entrySet()) {
			chunk.setHeightmap(entry.getKey(), entry.getValue().clone());
		}
		chunk.setLightCorrect(this.lightCorrect);
		for (CompoundTag tag : this.blockEntities) {
			BlockPos pos = BlockEntity.getPosFromTag(this.pos, tag);
			BlockEntity blockEntity = BlockEntity.loadStatic(pos, chunk.getBlockState(pos), tag.copy(), level.registryAccess());
			if (blockEntity == null) {
				throw new IllegalStateException("Failed to materialize saved block entity at " + pos + " in " + this.pos);
			}
			chunk.setBlockEntity(blockEntity);
		}
		return chunk;
	}

	private static Map<Heightmap.Types, long[]> copyHeightmaps(Map<Heightmap.Types, long[]> source) {
		Map<Heightmap.Types, long[]> copy = new EnumMap<>(Heightmap.Types.class);
		for (Map.Entry<Heightmap.Types, long[]> entry : source.entrySet()) {
			copy.put(entry.getKey(), entry.getValue().clone());
		}
		return Map.copyOf(copy);
	}

	private static List<CompoundTag> copyTags(List<CompoundTag> source) {
		if (source == null || source.isEmpty()) {
			return List.of();
		}
		List<CompoundTag> copy = new ArrayList<>(source.size());
		for (CompoundTag tag : source) {
			if (tag != null) {
				copy.add(tag.copy());
			}
		}
		return List.copyOf(copy);
	}

	public record SectionSnapshot(
			int y,
			LevelChunkSection chunkSection,
			DataLayer blockLight,
			DataLayer skyLight
	) {
	}
}
