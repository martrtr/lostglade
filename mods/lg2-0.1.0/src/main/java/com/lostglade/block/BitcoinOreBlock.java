package com.lostglade.block;

import com.lostglade.Lg2;
import com.lostglade.config.Lg2Config;
import com.lostglade.item.ModItems;
import com.lostglade.server.SeasonStartSystem;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.BlockResourceCreator;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.SimplePolymerBlock;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import xyz.nucleoid.packettweaker.PacketContext;

import java.lang.reflect.Field;
import java.util.List;

public class BitcoinOreBlock extends SimplePolymerBlock implements PolymerTexturedBlock {
	private final BlockState polymerPackState;

	public BitcoinOreBlock(BlockBehaviour.Properties settings, Identifier modelId, Block preferredPolymerBlock) {
		super(settings, Blocks.RAW_GOLD_BLOCK);
		this.polymerPackState = requestTargetState(modelId, preferredPolymerBlock);
	}

	@Override
	public BlockState getPolymerBlockState(BlockState state, PacketContext context) {
		return PolymerResourcePackUtils.hasMainPack(context)
				? this.polymerPackState
				: Blocks.RAW_GOLD_BLOCK.defaultBlockState();
	}

	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
		ServerLevel level = builder.getLevel();
		Lg2Config.ConfigData config = Lg2Config.get();
		ItemStack tool = builder.getOptionalParameter(LootContextParams.TOOL);
		if (tool == null) {
			tool = ItemStack.EMPTY;
		}

		if (shouldDropSelf(level, tool, config)) {
			return List.of(new ItemStack(this));
		}

		int count = config.sampleDrop(level.getRandom());
		if (config.fortuneEnabled) {
			int fortuneLevel = getEnchantmentLevel(level, tool, Enchantments.FORTUNE);
			count = applyFortuneBonus(level.getRandom(), count, fortuneLevel);
		}

		if (count <= 0) {
			return List.of();
		}

		return List.of(new ItemStack(ModItems.BITCOIN, count));
	}

	@Override
	protected void spawnAfterBreak(BlockState state, ServerLevel level, BlockPos pos, ItemStack tool, boolean dropExperience) {
		super.spawnAfterBreak(state, level, pos, tool, dropExperience);
		// Tutorial ore is currency only; XP orbs briefly overwrite the HUD level while they are collected.
		if (!dropExperience || SeasonStartSystem.isExperienceSuppressed(level)) {
			return;
		}

		Lg2Config.ConfigData config = Lg2Config.get();
		if (shouldDropSelf(level, tool, config)) {
			return;
		}

		int xp = config.sampleXp(level.getRandom());
		if (xp > 0) {
			this.popExperience(level, pos, xp);
		}
	}

	private static boolean shouldDropSelf(ServerLevel level, ItemStack tool, Lg2Config.ConfigData config) {
		if (!config.silkTouchEnabled || tool.isEmpty()) {
			return false;
		}
		return getEnchantmentLevel(level, tool, Enchantments.SILK_TOUCH) > 0;
	}

	private static int getEnchantmentLevel(ServerLevel level, ItemStack tool, ResourceKey<Enchantment> key) {
		Holder<Enchantment> holder = level.registryAccess()
				.lookupOrThrow(Registries.ENCHANTMENT)
				.getOrThrow(key);
		return EnchantmentHelper.getItemEnchantmentLevel(holder, tool);
	}

	private static int applyFortuneBonus(RandomSource random, int baseCount, int fortuneLevel) {
		if (baseCount <= 0 || fortuneLevel <= 0) {
			return baseCount;
		}

		int bonus = random.nextInt(fortuneLevel + 2) - 1;
		if (bonus < 0) {
			bonus = 0;
		}
		return baseCount * (bonus + 1);
	}

	private static BlockState requestTargetState(Identifier modelId, Block preferredPolymerBlock) {
		PolymerBlockModel model = PolymerBlockModel.of(modelId);

		// Vanilla placement always clears waterlogging on double slabs, so this state cannot flash on hand-built slabs.
		BlockState targetState = requestStateWithPredicate(model, preferredPolymerBlock);
		if (targetState != null) {
			return targetState;
		}

		Lg2.LOGGER.warn("No matching double-slab Polymer state for {}; using raw gold fallback", modelId);
		return Blocks.RAW_GOLD_BLOCK.defaultBlockState();
	}

	private static BlockState requestStateWithPredicate(PolymerBlockModel model, Block preferredPolymerBlock) {
		try {
			Field creatorField = PolymerBlockResourceUtils.class.getDeclaredField("CREATOR");
			creatorField.setAccessible(true);
			BlockResourceCreator creator = (BlockResourceCreator) creatorField.get(null);

			return creator.requestBlock(
					BlockModelType.FULL_BLOCK,
					state -> state.is(preferredPolymerBlock)
							&& state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE
							&& state.getValue(SlabBlock.WATERLOGGED),
					model
			);
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}
}
