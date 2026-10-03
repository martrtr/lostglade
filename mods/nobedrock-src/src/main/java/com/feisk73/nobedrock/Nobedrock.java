package com.feisk73.nobedrock;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes only the natural bedrock floor in the Overworld. The Nether is
 * deliberately excluded: its roof and floor are part of the intended world.
 */
public final class Nobedrock implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("nobedrock");

	@Override
	public void onInitialize() {
		ServerChunkEvents.CHUNK_LOAD.register(Nobedrock::removeOverworldFloorBedrock);
		LOGGER.info("NoBedrock initialized: removing Overworld floor bedrock only; Nether is preserved");
	}

	private static void removeOverworldFloorBedrock(ServerLevel level, LevelChunk chunk) {
		if (!level.dimension().equals(Level.OVERWORLD)) {
			return;
		}

		int minY = level.getMinY();
		int maxY = Math.min(level.getMaxY() - 1, minY + 4);
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				for (int y = minY; y <= maxY; y++) {
					pos.set(minX + localX, y, minZ + localZ);
					if (chunk.getBlockState(pos).is(Blocks.BEDROCK)) {
						chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);
					}
				}
			}
		}
	}
}
