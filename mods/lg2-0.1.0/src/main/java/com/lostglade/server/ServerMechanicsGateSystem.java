package com.lostglade.server;

import com.lostglade.block.ModBlocks;
import com.lostglade.item.DroneItem;
import com.lostglade.item.ModItems;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public final class ServerMechanicsGateSystem {
	private static final String REDSTONE = "mechanic_redstone";
	private static final String BREWING = "mechanic_brewing";
	private static final String ANVIL = "mechanic_anvil";
	private static final String ENCHANTING = "mechanic_enchanting";
	private static final String VILLAGERS = "mechanic_villagers";
	private static final String ERA_STONE = "era_stone";
	private static final String ERA_COPPER = "era_copper";
	private static final String ERA_IRON_GOLD = "era_iron_gold";
	private static final String ERA_DIAMOND = "era_diamond";
	private static final String ERA_NETHERITE = "era_netherite";
	private static final String IT_CAMERA = "it_camera";
	private static final String IT_MICROPHONE = "it_microphone";
	private static final String IT_SPEAKER = "it_speaker";
	private static final String IT_SCREEN = "it_screen";
	private static final String IT_DRONE = "it_drone_scout";
	private static final String IT_DRONE_KAMIKAZE = "it_drone_kamikaze";
	private static final String IT_BLUETOOTH_ADAPTER = "it_bluetooth_adapter";
	private static final double AUTOMATED_GOLEM_PLAYER_RADIUS = 16.0D;
	private static final double AUTOMATED_GOLEM_PLAYER_RADIUS_SQR = AUTOMATED_GOLEM_PLAYER_RADIUS * AUTOMATED_GOLEM_PLAYER_RADIUS;

	private static final Set<Block> REDSTONE_BLOCKS = Set.of(
			Blocks.REDSTONE_WIRE,
			Blocks.REDSTONE_TORCH,
			Blocks.REDSTONE_WALL_TORCH,
			Blocks.REPEATER,
			Blocks.COMPARATOR,
			Blocks.OBSERVER,
			Blocks.DISPENSER,
			Blocks.DROPPER,
			Blocks.PISTON,
			Blocks.STICKY_PISTON,
			Blocks.LEVER,
			Blocks.NOTE_BLOCK,
			Blocks.DAYLIGHT_DETECTOR,
			Blocks.TARGET,
			Blocks.TRIPWIRE_HOOK,
			Blocks.HOPPER,
			Blocks.REDSTONE_LAMP,
			Blocks.POWERED_RAIL,
			Blocks.DETECTOR_RAIL,
			Blocks.ACTIVATOR_RAIL,
			Blocks.CRAFTER,
			Blocks.CALIBRATED_SCULK_SENSOR
	);
	private static final Set<Item> STONE_ERA_ITEMS = Set.of(
			Items.STONE_PICKAXE,
			Items.STONE_AXE,
			Items.STONE_SHOVEL,
			Items.STONE_HOE,
			Items.STONE_SWORD,
			Items.STONE_SPEAR,
			ModItems.STONE_SHIELD
	);
	private static final Set<Item> IRON_AND_GOLD_ERA_ITEMS = Set.of(
			Items.IRON_PICKAXE,
			Items.IRON_AXE,
			Items.IRON_SHOVEL,
			Items.IRON_HOE,
			Items.IRON_SWORD,
			Items.IRON_SPEAR,
			Items.IRON_HELMET,
			Items.IRON_CHESTPLATE,
			Items.IRON_LEGGINGS,
			Items.IRON_BOOTS,
			Items.SHIELD,
			Items.GOLDEN_PICKAXE,
			Items.GOLDEN_AXE,
			Items.GOLDEN_SHOVEL,
			Items.GOLDEN_HOE,
			Items.GOLDEN_SWORD,
			Items.GOLDEN_SPEAR,
			Items.GOLDEN_HELMET,
			Items.GOLDEN_CHESTPLATE,
			Items.GOLDEN_LEGGINGS,
			Items.GOLDEN_BOOTS,
			ModItems.GOLDEN_SHIELD,
			Items.GOLDEN_APPLE,
			Items.GOLDEN_CARROT,
			Items.GLISTERING_MELON_SLICE
	);
	private static final Set<Item> DIAMOND_ERA_ITEMS = Set.of(
			Items.DIAMOND_PICKAXE,
			Items.DIAMOND_AXE,
			Items.DIAMOND_SHOVEL,
			Items.DIAMOND_HOE,
			Items.DIAMOND_SWORD,
			Items.DIAMOND_SPEAR,
			Items.DIAMOND_HELMET,
			Items.DIAMOND_CHESTPLATE,
			Items.DIAMOND_LEGGINGS,
			Items.DIAMOND_BOOTS,
			ModItems.DIAMOND_SHIELD
	);
	private static final Set<Item> NETHERITE_ERA_ITEMS = Set.of(
			Items.NETHERITE_PICKAXE,
			Items.NETHERITE_AXE,
			Items.NETHERITE_SHOVEL,
			Items.NETHERITE_HOE,
			Items.NETHERITE_SWORD,
			Items.NETHERITE_SPEAR,
			Items.NETHERITE_HELMET,
			Items.NETHERITE_CHESTPLATE,
			Items.NETHERITE_LEGGINGS,
			Items.NETHERITE_BOOTS,
			ModItems.NETHERITE_SHIELD
	);
	private static final Set<String> COPPER_GEAR_SUFFIXES = Set.of(
			"pickaxe",
			"axe",
			"shovel",
			"hoe",
			"sword",
			"spear",
			"helmet",
			"chestplate",
			"leggings",
			"boots",
			"shield"
	);
	private static final ThreadLocal<TrackedGolemPlacement> TRACKED_GOLEM_PLACEMENT = new ThreadLocal<>();
	private static final ThreadLocal<ItemStack> PENDING_GOLEM_HEAD_REFUND = new ThreadLocal<>();

	private ServerMechanicsGateSystem() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}

			InteractionResult dictatorInteraction = ServerRaceSystem.tryHandleLittleDictatorUseInteraction(serverPlayer, hand, hitResult);
			if (dictatorInteraction != InteractionResult.PASS) {
				return dictatorInteraction;
			}

			BlockState state = world.getBlockState(hitResult.getBlockPos());
			String interactionRequirement = requiredUpgradeForInteraction(state);
			if (interactionRequirement != null
					&& !ServerUpgradeUiSystem.hasUpgrade(serverPlayer, interactionRequirement)
					&& !ServerRaceSystem.canLittleDictatorBypassInteraction(serverPlayer, state)) {
				return InteractionResult.FAIL;
			}

			return InteractionResult.PASS;
		});

		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}

			String requirement = requiredUpgradeForBreaking(world.getBlockState(pos));
			if (requirement != null && !ServerUpgradeUiSystem.hasUpgrade(serverPlayer, requirement)) {
				return InteractionResult.SUCCESS;
			}

			return InteractionResult.PASS;
		});

		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			if (!(player instanceof ServerPlayer serverPlayer)) {
				return true;
			}

			String requirement = requiredUpgradeForBreaking(state);
			return requirement == null || ServerUpgradeUiSystem.hasUpgrade(serverPlayer, requirement);
		});

		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}

			String requirement = requiredUpgradeForEntityInteraction(entity);
			if (requirement != null && !ServerUpgradeUiSystem.hasUpgrade(serverPlayer, requirement)) {
				return InteractionResult.FAIL;
			}

			return InteractionResult.PASS;
		});

		ServerTickEvents.END_SERVER_TICK.register(ServerMechanicsGateSystem::tickIllegalItems);
	}

	public static boolean handleLittleDictatorIronDoorPlayerAction(ServerPlayer player, ServerboundPlayerActionPacket packet) {
		if (player == null || packet == null || !(player.level() instanceof ServerLevel level)) {
			return false;
		}
		ServerboundPlayerActionPacket.Action action = packet.getAction();
		if (action != ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
				&& !(player.isCreative() && action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)) {
			return false;
		}
		BlockPos pos = packet.getPos();
		BlockState state = level.getBlockState(pos);
		return handleLittleDictatorIronDoorBreak(level, player, pos, state)
				|| handleLittleDictatorIronTrapdoorBreak(level, player, pos, state);
	}

	private static boolean handleLittleDictatorIronDoorBreak(Level world, ServerPlayer player, BlockPos pos, BlockState state) {
		if (!(world instanceof ServerLevel level)
				|| player == null
				|| pos == null
				|| state == null
				|| !state.is(Blocks.IRON_DOOR)
				|| !ServerRaceSystem.canLittleDictatorBypassInteraction(player, state)) {
			return false;
		}

		BlockPos lowerPos = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
		BlockPos upperPos = lowerPos.above();
		BlockState lowerState = level.getBlockState(lowerPos);
		BlockState upperState = level.getBlockState(upperPos);
		boolean breakingUpperHalf = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
		boolean shouldDrop = !player.isCreative()
				&& (breakingUpperHalf || player.getMainHandItem().isCorrectToolForDrops(lowerState));

		BlockPos brokenPos = breakingUpperHalf ? upperPos : lowerPos;
		BlockPos silentOtherPos = breakingUpperHalf ? lowerPos : upperPos;
		if (level.getBlockState(brokenPos).is(Blocks.IRON_DOOR)) {
			level.destroyBlock(brokenPos, false, player);
		}
		if (level.getBlockState(silentOtherPos).is(Blocks.IRON_DOOR)) {
			level.removeBlock(silentOtherPos, false);
		}
		if (shouldDrop) {
			Block.popResource(level, lowerPos, new ItemStack(Items.IRON_DOOR));
		}
		return true;
	}

	private static boolean handleLittleDictatorIronTrapdoorBreak(Level world, ServerPlayer player, BlockPos pos, BlockState state) {
		if (!(world instanceof ServerLevel level)
				|| player == null
				|| pos == null
				|| state == null
				|| !state.is(Blocks.IRON_TRAPDOOR)
				|| !ServerRaceSystem.canLittleDictatorBypassInteraction(player, state)) {
			return false;
		}

		boolean shouldDrop = !player.isCreative() && player.getMainHandItem().isCorrectToolForDrops(state);
		level.destroyBlock(pos, false, player);
		if (shouldDrop) {
			Block.popResource(level, pos, new ItemStack(Items.IRON_TRAPDOOR));
		}
		return true;
	}
	public static boolean canTakeCraftResult(ServerPlayer player, ItemStack stack) {
		if (player == null) {
			return true;
		}
		String requirement = requiredUpgradeForCraftResult(stack);
		return (requirement == null || ServerUpgradeUiSystem.hasUpgrade(player, requirement))
				&& NecromancerStockSystem.canTakeCraftResult(player, stack);
	}

	public static boolean canPlaceBlock(ServerPlayer player, Block block) {
		if (player == null) {
			return true;
		}
		String requirement = requiredUpgradeForPlacedBlock(null, block);
		return requirement == null || ServerUpgradeUiSystem.hasUpgrade(player, requirement);
	}

	public static boolean canPlaceBlock(ServerPlayer player, BlockPlaceContext context, Block block) {
		if (player == null) {
			return true;
		}
		String requirement = requiredUpgradeForPlacedBlock(context, block);
		return requirement == null || ServerUpgradeUiSystem.hasUpgrade(player, requirement);
	}

	public static void beginTrackedGolemHeadPlacement(ServerPlayer player, Item refundItem) {
		if (player == null || refundItem == null || refundItem == Items.AIR) {
			return;
		}
		TRACKED_GOLEM_PLACEMENT.set(new TrackedGolemPlacement(player, refundItem));
		PENDING_GOLEM_HEAD_REFUND.remove();
	}

	public static boolean shouldCancelPlayerGolemHeadPlacement(ServerPlayer player, BlockPlaceContext context, Block block) {
		if (player == null || context == null || !isGolemHeadBlock(block)) {
			return false;
		}
		BlockPos headPos = resolvePlacementPos(context);
		String requirement = requiredUpgradeForGolemSpawn(context.getLevel(), headPos);
		return requirement != null && !ServerUpgradeUiSystem.hasUpgrade(player, requirement);
	}

	public static void completeTrackedGolemHeadPlacement() {
		TrackedGolemPlacement placement = TRACKED_GOLEM_PLACEMENT.get();
		ItemStack refund = PENDING_GOLEM_HEAD_REFUND.get();
		TRACKED_GOLEM_PLACEMENT.remove();
		PENDING_GOLEM_HEAD_REFUND.remove();
		if (placement == null || refund == null || refund.isEmpty()) {
			return;
		}
		refundTrackedGolemHead(placement.player(), refund);
	}

	public static ServerPlayer getTrackedGolemHeadPlacer() {
		TrackedGolemPlacement placement = TRACKED_GOLEM_PLACEMENT.get();
		return placement == null ? null : placement.player();
	}

	public static void queueTrackedGolemHeadRefund() {
		TrackedGolemPlacement placement = TRACKED_GOLEM_PLACEMENT.get();
		if (placement == null || placement.refundItem() == null || placement.refundItem() == Items.AIR) {
			return;
		}
		PENDING_GOLEM_HEAD_REFUND.set(new ItemStack(placement.refundItem()));
	}

	public static boolean shouldCancelAutomatedGolemHeadPlacement(BlockPlaceContext context, Block block) {
		if (context == null || !isGolemHeadBlock(block)) {
			return false;
		}
		BlockPos headPos = resolvePlacementPos(context);
		Level level = context.getLevel();
		String requirement = requiredUpgradeForGolemSpawn(level, headPos);
		if (requirement == null) {
			return false;
		}
		ServerPlayer actor = findNearestAutomatedGolemActor(level, headPos);
		return actor == null || !ServerUpgradeUiSystem.hasUpgrade(actor, requirement);
	}

	public static String requiredUpgradeForGolemSpawn(LevelReader level, BlockPos headPos) {
		if (level == null || headPos == null) {
			return null;
		}
		if (wouldFormIronGolem(level, headPos)) {
			return ERA_IRON_GOLD;
		}
		if (wouldFormCopperGolem(level, headPos)) {
			return ERA_COPPER;
		}
		return null;
	}

	public static boolean canInteractWithBlock(ServerPlayer player, Block block) {
		if (player == null || block == null) {
			return true;
		}
		String requirement = requiredUpgradeForInteraction(block.defaultBlockState());
		return requirement == null || ServerUpgradeUiSystem.hasUpgrade(player, requirement);
	}

	public static boolean canOwnItem(ServerPlayer player, ItemStack stack) {
		if (player == null) {
			return true;
		}
		String requirement = requiredUpgradeForCraftResult(stack);
		return (requirement == null || ServerUpgradeUiSystem.hasUpgrade(player, requirement))
				&& NecromancerStockSystem.canOwnItem(player, stack);
	}

	private static String requiredUpgradeForCraftResult(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		Item item = stack.getItem();
		if (item == ModItems.CAMERA) {
			return IT_CAMERA;
		}
		if (item == ModItems.MONITOR) {
			return IT_SCREEN;
		}
		if (item == ModItems.BLUETOOTH_ADAPTER) {
			return IT_BLUETOOTH_ADAPTER;
		}
		if (item == ModItems.DRONE) {
			if (DroneItem.getKamikazePower(stack) > 0) {
				return IT_DRONE_KAMIKAZE;
			}
			return IT_DRONE;
		}
		String eraRequirement = requiredEraForItem(item);
		if (eraRequirement != null) {
			return eraRequirement;
		}
		if (item instanceof BlockItem blockItem) {
			return requiredUpgradeForBlock(blockItem.getBlock());
		}
		return null;
	}

	private static String requiredEraForItem(Item item) {
		if (item == null || item == Items.AIR) {
			return null;
		}
		if (STONE_ERA_ITEMS.contains(item)) {
			return ERA_STONE;
		}
		if (isCopperEraItem(item)) {
			return ERA_COPPER;
		}
		if (IRON_AND_GOLD_ERA_ITEMS.contains(item) || hasItemPathToken(item, "iron_golem")) {
			return ERA_IRON_GOLD;
		}
		if (DIAMOND_ERA_ITEMS.contains(item)) {
			return ERA_DIAMOND;
		}
		if (NETHERITE_ERA_ITEMS.contains(item)) {
			return ERA_NETHERITE;
		}
		return null;
	}

	private static boolean isCopperEraItem(Item item) {
		if (hasItemPathToken(item, "copper_golem")) {
			return true;
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		if (id == null) {
			return false;
		}
		String path = id.getPath();
		for (String suffix : COPPER_GEAR_SUFFIXES) {
			if (path.equals("copper_" + suffix) || path.equals(suffix + "_copper")) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasItemPathToken(Item item, String token) {
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		return id != null && id.getPath().contains(token);
	}

	private static String requiredUpgradeForInteraction(BlockState state) {
		if (state == null) {
			return null;
		}
		Block block = state.getBlock();
		if (block instanceof ButtonBlock || block instanceof BasePressurePlateBlock) {
			return null;
		}
		return requiredUpgradeForBlock(block);
	}

	private static String requiredUpgradeForBreaking(BlockState state) {
		if (state == null) {
			return null;
		}
		Block block = state.getBlock();
		if (block == ModBlocks.CAMERA || block == ModBlocks.MICROPHONE || block == ModBlocks.SPEAKER) {
			return null;
		}
		return requiredUpgradeForBlock(block);
	}

	public static boolean shouldDropUpgradeLockedDevice(Block block, Entity breaker) {
		if (!(breaker instanceof ServerPlayer player)) {
			return true;
		}
		String requirement = requiredUpgradeForBlock(block);
		return requirement == null || ServerUpgradeUiSystem.hasUpgrade(player, requirement);
	}

	private static String requiredUpgradeForEntityInteraction(Entity entity) {
		if (DroneSystem.isDroneEntity(entity)) {
			String requirement = DroneSystem.requiredUpgradeForDroneEntity(entity);
			return requirement == null ? IT_DRONE : requirement;
		}
		if (entity instanceof AbstractVillager) {
			return VILLAGERS;
		}
		if (entity instanceof MinecartHopper) {
			return REDSTONE;
		}
		return null;
	}

	private static String requiredUpgradeForBlock(Block block) {
		if (block == null || block == Blocks.AIR) {
			return null;
		}
		if (block instanceof AnvilBlock) {
			return ANVIL;
		}
		if (block == Blocks.BREWING_STAND) {
			return BREWING;
		}
		if (block == Blocks.ENCHANTING_TABLE) {
			return ENCHANTING;
		}
		if (block == ModBlocks.CAMERA) {
			return IT_CAMERA;
		}
		if (block == ModBlocks.MICROPHONE) {
			return IT_MICROPHONE;
		}
		if (block == ModBlocks.SPEAKER) {
			return IT_SPEAKER;
		}
		if (REDSTONE_BLOCKS.contains(block) || block instanceof ButtonBlock || block instanceof BasePressurePlateBlock) {
			return REDSTONE;
		}
		return null;
	}

	private static String requiredUpgradeForPlacedBlock(BlockPlaceContext context, Block block) {
		return requiredUpgradeForBlock(block);
	}

	public static boolean isGolemHeadBlock(Block block) {
		return block == Blocks.CARVED_PUMPKIN || block == Blocks.JACK_O_LANTERN;
	}

	private static BlockPos resolvePlacementPos(BlockPlaceContext context) {
		if (context == null) {
			return null;
		}
		Level level = context.getLevel();
		BlockPos clickedPos = context.getClickedPos();
		if (level == null || clickedPos == null) {
			return null;
		}
		if (level.getBlockState(clickedPos).canBeReplaced(context)) {
			return clickedPos;
		}
		return clickedPos.relative(context.getClickedFace());
	}

	private static boolean wouldFormIronGolem(LevelReader level, BlockPos headPos) {
		if (level == null || headPos == null) {
			return false;
		}
		BlockPos armLine = headPos.below();
		BlockPos torso = armLine.below();
		if (!level.getBlockState(armLine).is(Blocks.IRON_BLOCK)
				|| !level.getBlockState(torso).is(Blocks.IRON_BLOCK)) {
			return false;
		}

		boolean eastWest = level.getBlockState(armLine.west()).is(Blocks.IRON_BLOCK)
				&& level.getBlockState(armLine.east()).is(Blocks.IRON_BLOCK);
		if (eastWest) {
			return true;
		}

		return level.getBlockState(armLine.north()).is(Blocks.IRON_BLOCK)
				&& level.getBlockState(armLine.south()).is(Blocks.IRON_BLOCK);
	}

	private static boolean wouldFormCopperGolem(LevelReader level, BlockPos headPos) {
		return level != null
				&& headPos != null
				&& level.getBlockState(headPos.below()).is(BlockTags.COPPER);
	}

	private static ServerPlayer findNearestAutomatedGolemActor(Level level, BlockPos headPos) {
		if (!(level instanceof ServerLevel serverLevel) || headPos == null) {
			return null;
		}
		Vec3 center = Vec3.atCenterOf(headPos);
		ServerPlayer nearest = null;
		double bestDistance = Double.MAX_VALUE;
		for (Player player : serverLevel.players()) {
			if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.isSpectator()) {
				continue;
			}
			double distance = serverPlayer.position().distanceToSqr(center);
			if (distance > AUTOMATED_GOLEM_PLAYER_RADIUS_SQR || distance >= bestDistance) {
				continue;
			}
			bestDistance = distance;
			nearest = serverPlayer;
		}
		return nearest;
	}

	private static void refundTrackedGolemHead(ServerPlayer player, ItemStack refund) {
		if (player == null || refund == null || refund.isEmpty()) {
			return;
		}
		boolean inserted = player.getInventory().add(refund);
		if (!inserted && !refund.isEmpty()) {
			ItemEntity dropped = player.drop(refund, false);
			if (dropped != null) {
				dropped.setPickUpDelay(0);
			}
		}
		syncPlayerInventory(player);
	}

	public static void syncPlayerInventory(ServerPlayer player) {
		if (player == null) {
			return;
		}
		player.getInventory().setChanged();
		player.inventoryMenu.broadcastFullState();
		player.inventoryMenu.sendAllDataToRemote();
		if (player.containerMenu != player.inventoryMenu) {
			player.containerMenu.broadcastFullState();
			player.containerMenu.sendAllDataToRemote();
		}
	}

	private record TrackedGolemPlacement(ServerPlayer player, Item refundItem) {
	}

	private static void tickIllegalItems(MinecraftServer server) {
		if (server == null) {
			return;
		}

		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			sanitizeIllegalInventory(player);
		}
	}

	private static void sanitizeIllegalInventory(ServerPlayer player) {
		if (player == null || player.level().isClientSide()) {
			return;
		}

		boolean changed = false;
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || canOwnItem(player, stack)) {
				continue;
			}

			ItemStack removed = inventory.removeItemNoUpdate(slot);
			if (!removed.isEmpty()) {
				dropIllegalStack(player, removed);
				changed = true;
			}
		}

		ItemStack carried = player.containerMenu.getCarried();
		if (!carried.isEmpty() && !canOwnItem(player, carried)) {
			player.containerMenu.setCarried(ItemStack.EMPTY);
			dropIllegalStack(player, carried);
			changed = true;
		}

		if (!changed) {
			return;
		}

		inventory.setChanged();
		player.inventoryMenu.broadcastChanges();
		if (player.containerMenu != player.inventoryMenu) {
			player.containerMenu.broadcastChanges();
		}
	}

	private static void dropIllegalStack(ServerPlayer player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty()) {
			return;
		}

		ItemEntity dropped = player.drop(stack, true);
		if (dropped != null) {
			dropped.setPickUpDelay(20);
		}
	}
}
