package com.lostglade.server.glitch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.ServerBackroomsSystem;
import com.lostglade.server.ServerMechanicsGateSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Nameable;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ChestDesyncGlitch implements BlockUseGlitchHandler, EntityUseGlitchHandler {
	private static final String INCLUDE_GENERATED_STORAGES = "includeGeneratedStorages";
	private static final int PENDING_PLACEMENT_LIFETIME_TICKS = 3;
	private static final Set<String> PLAYER_TOUCHED_BLOCK_STORAGES = new HashSet<>();
	private static final Set<UUID> PLAYER_TOUCHED_ENTITY_STORAGES = new HashSet<>();
	private static final List<PendingPlacementCheck> PENDING_PLACEMENT_CHECKS = new ArrayList<>();

	@Override
	public String id() {
		return "chest_desync";
	}

	@Override
	public GlitchConfig.GlitchEntry defaultEntry() {
		GlitchConfig.GlitchEntry entry = new GlitchConfig.GlitchEntry();
		entry.enabled = true;
		entry.minStabilityPercent = 0.0D;
		entry.maxStabilityPercent = 70.0D;
		entry.chancePerCheck = 0.25D;
		entry.stabilityInfluence = 1.0D;
		entry.minCooldownTicks = 60;
		entry.maxCooldownTicks = 600;

		JsonObject settings = new JsonObject();
		settings.addProperty(INCLUDE_GENERATED_STORAGES, false);
		entry.settings = settings;
		return entry;
	}

	@Override
	public boolean sanitizeSettings(GlitchConfig.GlitchEntry entry) {
		if (entry.settings == null) {
			entry.settings = new JsonObject();
		}

		boolean changed = false;
		changed |= removeSetting(entry.settings, "physicalStorageWeight");
		changed |= removeSetting(entry.settings, "enderChestWeight");
		changed |= sanitizeBoolean(entry.settings, INCLUDE_GENERATED_STORAGES, false);
		return changed;
	}

	@Override
	public boolean trigger(MinecraftServer server, RandomSource random, GlitchConfig.GlitchEntry entry, double stabilityPercent) {
		return false;
	}

	public static void resetTracking() {
		PLAYER_TOUCHED_BLOCK_STORAGES.clear();
		PLAYER_TOUCHED_ENTITY_STORAGES.clear();
		PENDING_PLACEMENT_CHECKS.clear();
	}

	public static void tickPlacementTracking(MinecraftServer server) {
		for (int i = PENDING_PLACEMENT_CHECKS.size() - 1; i >= 0; i--) {
			PendingPlacementCheck check = PENDING_PLACEMENT_CHECKS.get(i);
			if (check.level.getServer() != server) {
				PENDING_PLACEMENT_CHECKS.remove(i);
				continue;
			}

			long now = check.level.getGameTime();
			if (now > check.expireTick) {
				PENDING_PLACEMENT_CHECKS.remove(i);
				continue;
			}

			BlockState state = check.level.getBlockState(check.pos);
			BlockEntity blockEntity = check.level.getBlockEntity(check.pos);
			if (!(blockEntity instanceof Container) || state.getBlock() instanceof EnderChestBlock) {
				continue;
			}

			markBlockStorageTouched(check.level, check.pos, state);
			PENDING_PLACEMENT_CHECKS.remove(i);
		}
	}

	public static void noteBlockInteraction(
			ServerPlayer player,
			ServerLevel level,
			BlockPos pos,
			BlockState state,
			InteractionHand hand,
			BlockHitResult hitResult
	) {
		if (player == null || level == null || pos == null || state == null || hand != InteractionHand.MAIN_HAND || player.isSecondaryUseActive()) {
			return;
		}

		if (isStorageTriggerBlock(level, pos, state)) {
			markBlockStorageTouched(level, pos, state);
		}

		ItemStack held = player.getItemInHand(hand);
		if (!(held.getItem() instanceof BlockItem blockItem)) {
			return;
		}
		if (!blockItem.getBlock().defaultBlockState().hasBlockEntity()) {
			return;
		}

		enqueuePendingPlacement(level, pos);
		if (hitResult != null) {
			enqueuePendingPlacement(level, pos.relative(hitResult.getDirection()));
		}
	}

	public static void noteEntityInteraction(ServerPlayer player, Entity entity, InteractionHand hand) {
		if (player == null || entity == null || hand != InteractionHand.MAIN_HAND || player.isSecondaryUseActive()) {
			return;
		}
		if (!(entity instanceof ContainerEntity)) {
			return;
		}

		PLAYER_TOUCHED_ENTITY_STORAGES.add(entity.getUUID());
	}

	@Override
	public boolean triggerOnUseBlock(
			MinecraftServer server,
			RandomSource random,
			GlitchConfig.GlitchEntry entry,
			double stabilityPercent,
			ServerPlayer player,
			ServerLevel level,
			BlockPos pos,
			BlockState state,
			InteractionHand hand,
			BlockHitResult hitResult
	) {
		if (player == null || state == null || hand != InteractionHand.MAIN_HAND || player.isSecondaryUseActive()) {
			return false;
		}
		if (!isStorageTriggerBlock(level, pos, state)) {
			return false;
		}

		TriggerSource source = TriggerSource.forBlock(level, pos, state, state.getBlock() instanceof EnderChestBlock);
		return triggerFromStorageUse(server, random, entry, player, source);
	}

	@Override
	public boolean triggerOnUseEntity(
			MinecraftServer server,
			RandomSource random,
			GlitchConfig.GlitchEntry entry,
			double stabilityPercent,
			ServerPlayer player,
			Entity entity,
			InteractionHand hand,
			EntityHitResult hitResult
	) {
		if (player == null || entity == null || hand != InteractionHand.MAIN_HAND || player.isSecondaryUseActive()) {
			return false;
		}
		if (!(entity instanceof ContainerEntity)) {
			return false;
		}

		TriggerSource source = TriggerSource.forEntity(entity.getId());
		return triggerFromStorageUse(server, random, entry, player, source);
	}

	private static boolean triggerFromStorageUse(
			MinecraftServer server,
			RandomSource random,
			GlitchConfig.GlitchEntry entry,
			ServerPlayer opener,
			TriggerSource source
	) {
		JsonObject settings = entry.settings == null ? new JsonObject() : entry.settings;
		boolean includeGeneratedStorages = getBoolean(settings, INCLUDE_GENERATED_STORAGES, false);

		List<PhysicalStorageTarget> physicalTargets = collectPhysicalStorageTargets(server, includeGeneratedStorages);
		List<ServerPlayer> enderTargets = collectEnderChestTargets(server);
		if (physicalTargets.isEmpty() && enderTargets.isEmpty()) {
			return false;
		}

		return openRandomStorage(opener, random, physicalTargets, enderTargets, source, includeGeneratedStorages);
	}

	private static boolean isStorageTriggerBlock(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.getBlock() instanceof EnderChestBlock) {
			return true;
		}

		BlockEntity blockEntity = level.getBlockEntity(pos);
		return blockEntity instanceof Container;
	}

	private static List<PhysicalStorageTarget> collectPhysicalStorageTargets(MinecraftServer server, boolean includeGeneratedStorages) {
		List<PhysicalStorageTarget> targets = new ArrayList<>();
		Set<String> visited = new HashSet<>();

		for (ServerLevel level : server.getAllLevels()) {
			if (ServerBackroomsSystem.isBackrooms(level)) {
				continue;
			}
			level.getChunkSource().chunkMap.forEachReadyToSendChunk(chunk -> {
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (!(blockEntity instanceof Container container)) {
						continue;
					}

					BlockPos pos = blockEntity.getBlockPos();
					BlockState state = level.getBlockState(pos);
					if (state.getBlock() instanceof EnderChestBlock) {
						continue;
					}
					if (!includeGeneratedStorages && !wasBlockStorageTouched(level, pos, state)) {
						continue;
					}
					if (!canCreateMenuForContainer(resolveMenuKind(state, blockEntity, container), container)) {
						continue;
					}

					BlockPos uniquePos = getUniqueBlockPosForStorage(state, pos);
					String key = buildBlockStorageKey(level, uniquePos);
					if (!visited.add(key)) {
						continue;
					}

					targets.add(new BlockStorageTarget(level, uniquePos));
				}
			});

			for (Entity entity : level.getAllEntities()) {
				if (!(entity instanceof ContainerEntity containerEntity)) {
					continue;
				}
				if (!includeGeneratedStorages && !wasEntityStorageTouched(entity)) {
					continue;
				}
				if (!(entity instanceof Container container)) {
					continue;
				}
				if (!canCreateMenuForContainer(resolveMenuKind(entity, container), container)) {
					continue;
				}

				String key = "entity:" + entity.getUUID();
				if (!visited.add(key)) {
					continue;
				}

				targets.add(new EntityStorageTarget(containerEntity));
			}
		}

		return targets;
	}

	private static List<ServerPlayer> collectEnderChestTargets(MinecraftServer server) {
		List<ServerPlayer> targets = new ArrayList<>();
		for (ServerPlayer target : GlitchPlayers.eligiblePlayers(server)) {
			if (target.isSpectator() || !target.isAlive() || ServerBackroomsSystem.isInBackrooms(target)) {
				continue;
			}
			targets.add(target);
		}
		return targets;
	}

	private static boolean openRandomStorage(
			ServerPlayer opener,
			RandomSource random,
			List<PhysicalStorageTarget> physicalTargets,
			List<ServerPlayer> enderTargets,
			TriggerSource source,
			boolean includeGeneratedStorages
	) {
		List<StorageTarget> combinedTargets = new ArrayList<>(physicalTargets.size() + enderTargets.size());
		for (PhysicalStorageTarget physicalTarget : physicalTargets) {
			combinedTargets.add(new PhysicalCandidate(physicalTarget));
		}
		for (ServerPlayer enderTarget : enderTargets) {
			combinedTargets.add(new EnderCandidate(enderTarget));
		}

		while (!combinedTargets.isEmpty()) {
			int index = random.nextInt(combinedTargets.size());
			StorageTarget target = combinedTargets.remove(index);
			if (target instanceof PhysicalCandidate physicalCandidate) {
				if (isSameStorageAsSource(physicalCandidate.target, source)) {
					continue;
				}
				if (openPhysicalStorage(opener, physicalCandidate.target, includeGeneratedStorages)) {
					return true;
				}
				continue;
			}

			if (target instanceof EnderCandidate enderCandidate) {
				if (source.fromEnderChest && enderCandidate.target.getUUID().equals(opener.getUUID())) {
					continue;
				}
				if (openEnderChest(opener, enderCandidate.target)) {
					return true;
				}
			}
		}

		return false;
	}

	private static boolean openRandomPhysicalStorage(
			ServerPlayer opener,
			RandomSource random,
			List<PhysicalStorageTarget> candidates,
			TriggerSource source,
			boolean includeGeneratedStorages
	) {
		if (candidates.isEmpty()) {
			return false;
		}

		List<PhysicalStorageTarget> shuffled = new ArrayList<>(candidates);
		while (!shuffled.isEmpty()) {
			int index = random.nextInt(shuffled.size());
			PhysicalStorageTarget target = shuffled.remove(index);
			if (isSameStorageAsSource(target, source)) {
				continue;
			}
			if (openPhysicalStorage(opener, target, includeGeneratedStorages)) {
				return true;
			}
		}
		return false;
	}

	private interface StorageTarget {
	}

	private record PhysicalCandidate(PhysicalStorageTarget target) implements StorageTarget {
	}

	private record EnderCandidate(ServerPlayer target) implements StorageTarget {
	}

	private static boolean openRandomEnderChest(
			ServerPlayer opener,
			RandomSource random,
			List<ServerPlayer> candidates,
			boolean disallowOwnEnderChest
	) {
		if (candidates.isEmpty()) {
			return false;
		}

		List<ServerPlayer> shuffled = new ArrayList<>(candidates);
		while (!shuffled.isEmpty()) {
			int index = random.nextInt(shuffled.size());
			ServerPlayer target = shuffled.remove(index);
			if (disallowOwnEnderChest && target.getUUID().equals(opener.getUUID())) {
				continue;
			}
			if (openEnderChest(opener, target)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isSameStorageAsSource(PhysicalStorageTarget target, TriggerSource source) {
		if (source == null) {
			return false;
		}

		if (target instanceof BlockStorageTarget blockTarget) {
			return source.sourceEntityId < 0
					&& source.sourceLevel == blockTarget.level
					&& source.sourcePos != null
					&& source.sourcePos.equals(blockTarget.pos);
		}

		if (target instanceof EntityStorageTarget entityTarget) {
			if (!(entityTarget.entity instanceof Entity entity)) {
				return false;
			}
			return source.sourceEntityId >= 0 && source.sourceEntityId == entity.getId();
		}

		return false;
	}

	private static boolean openPhysicalStorage(
			ServerPlayer opener,
			PhysicalStorageTarget target,
			boolean includeGeneratedStorages
	) {
		if (target instanceof BlockStorageTarget blockTarget) {
			return openBlockStorage(opener, blockTarget, includeGeneratedStorages);
		}
		if (target instanceof EntityStorageTarget entityTarget) {
			return openEntityStorage(opener, entityTarget, includeGeneratedStorages);
		}
		return false;
	}

	private static boolean openBlockStorage(
			ServerPlayer opener,
			BlockStorageTarget target,
			boolean includeGeneratedStorages
	) {
		ServerLevel level = target.level;
		BlockPos pos = target.pos;
		BlockState state = level.getBlockState(pos);
		if (!includeGeneratedStorages && !wasBlockStorageTouched(level, pos, state)) {
			return false;
		}

		if (state.getBlock() instanceof ChestBlock chestBlock) {
			BlockEntity titleEntity = level.getBlockEntity(pos);
			Container chestContainer = ChestBlock.getContainer(chestBlock, state, level, pos, true);
			if (chestContainer == null) {
				return false;
			}

			int expectedSize = chestContainer.getContainerSize();
			Container proxied = new ConditionalValidContainer(
					chestContainer,
					player -> isChestStillValid(level, pos, expectedSize)
			);
			return openContainerWithMenuKind(
					opener,
					resolveDisplayName(state, titleEntity, null),
					proxied,
					StorageMenuKind.CHEST_DYNAMIC,
					null
			);
		}

		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (!(blockEntity instanceof Container container)) {
			return false;
		}

		StorageMenuKind kind = resolveMenuKind(state, blockEntity, container);
		if (!canCreateMenuForContainer(kind, container)) {
			return false;
		}

		ContainerData data = createDataForMenu(kind, blockEntity, container);
		ValidityCheck validityCheck = player -> isBlockEntityStillValid(level, pos, blockEntity);
		Container proxied;
		if (kind == StorageMenuKind.CRAFTER && container instanceof CraftingContainer craftingContainer) {
			proxied = new ConditionalValidCraftingContainer(craftingContainer, validityCheck);
		} else {
			proxied = new ConditionalValidContainer(container, validityCheck);
		}
		return openContainerWithMenuKind(
				opener,
				resolveDisplayName(state, blockEntity, null),
				proxied,
				kind,
				data
		);
	}

	private static boolean openEntityStorage(
			ServerPlayer opener,
			EntityStorageTarget target,
			boolean includeGeneratedStorages
	) {
		ContainerEntity containerEntity = target.entity;
		if (!(containerEntity instanceof Entity entity) || !(containerEntity instanceof Container container)) {
			return false;
		}
		if (!entity.isAlive() || entity.isRemoved()) {
			return false;
		}
		if (!includeGeneratedStorages && !wasEntityStorageTouched(entity)) {
			return false;
		}

		StorageMenuKind kind = resolveMenuKind(entity, container);
		if (!canCreateMenuForContainer(kind, container)) {
			return false;
		}

		Container proxied = new ConditionalValidContainer(
				container,
				player -> entity.isAlive() && !entity.isRemoved()
		);
		return openContainerWithMenuKind(
				opener,
				entity.getDisplayName(),
				proxied,
				kind,
				null
		);
	}

	private static boolean openEnderChest(ServerPlayer opener, ServerPlayer target) {
		if (target == null || !target.isAlive() || target.isRemoved()) {
			return false;
		}

		Container proxied = new ConditionalValidContainer(
				target.getEnderChestInventory(),
				player -> target.isAlive() && !target.isRemoved() && !target.isSpectator()
		);
		return openContainerWithMenuKind(
				opener,
				Component.translatable("container.enderchest"),
				proxied,
				StorageMenuKind.CHEST_DYNAMIC,
				null
		);
	}

	private static boolean openContainerWithMenuKind(
			ServerPlayer opener,
			Component title,
			Container container,
			StorageMenuKind kind,
			ContainerData data
	) {
		if (!canOpenLockedMenuKind(opener, kind)) {
			return false;
		}

		SimpleMenuProvider provider = new SimpleMenuProvider(
				(syncId, inventory, menuPlayer) -> createMenu(kind, syncId, inventory, container, data),
				title
		);
		return opener.openMenu(provider).isPresent();
	}

	private static boolean canOpenLockedMenuKind(ServerPlayer opener, StorageMenuKind kind) {
		if (opener == null || kind == null) {
			return false;
		}

		return switch (kind) {
			case BREWING -> ServerMechanicsGateSystem.canInteractWithBlock(opener, Blocks.BREWING_STAND);
			case HOPPER -> ServerMechanicsGateSystem.canInteractWithBlock(opener, Blocks.HOPPER);
			case DISPENSER -> ServerMechanicsGateSystem.canInteractWithBlock(opener, Blocks.DISPENSER);
			case CRAFTER -> ServerMechanicsGateSystem.canInteractWithBlock(opener, Blocks.CRAFTER);
			case CHEST_DYNAMIC, GENERIC_BY_SIZE, SHULKER, FURNACE, BLAST_FURNACE, SMOKER -> true;
		};
	}

	private static AbstractContainerMenu createMenu(
			StorageMenuKind kind,
			int syncId,
			Inventory inventory,
			Container container,
			ContainerData data
	) {
		return switch (kind) {
			case CHEST_DYNAMIC, GENERIC_BY_SIZE -> createGenericMenu(syncId, inventory, container);
			case HOPPER -> new HopperMenu(syncId, inventory, container) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			case DISPENSER -> new DispenserMenu(syncId, inventory, container) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			case SHULKER -> new ShulkerBoxMenu(syncId, inventory, container) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			case BREWING -> new BrewingStandMenu(syncId, inventory, container, ensureDataSize(data, 2)) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			case FURNACE -> new FurnaceMenu(syncId, inventory, container, ensureDataSize(data, 4)) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			case BLAST_FURNACE -> new BlastFurnaceMenu(syncId, inventory, container, ensureDataSize(data, 4)) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			case SMOKER -> new SmokerMenu(syncId, inventory, container, ensureDataSize(data, 4)) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			case CRAFTER -> {
				if (!(container instanceof CraftingContainer craftingContainer)) {
					yield createGenericMenu(syncId, inventory, container);
				}
				yield new CrafterMenu(syncId, inventory, craftingContainer, ensureDataSize(data, 10)) {
					@Override
					public boolean stillValid(Player player) {
						return container.stillValid(player);
					}
				};
			}
		};
	}

	private static AbstractContainerMenu createGenericMenu(int syncId, Inventory inventory, Container container) {
		int size = container.getContainerSize();
		if (size == 5) {
			return new HopperMenu(syncId, inventory, container) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
		}
		if (size % 9 != 0 || size < 9 || size > 54) {
			return null;
		}

		int rows = size / 9;
		return switch (rows) {
			case 1 -> new ChestMenu(MenuType.GENERIC_9x1, syncId, inventory, container, 1) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			case 2 -> new ChestMenu(MenuType.GENERIC_9x2, syncId, inventory, container, 2) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			case 3 -> new ChestMenu(MenuType.GENERIC_9x3, syncId, inventory, container, 3) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			case 4 -> new ChestMenu(MenuType.GENERIC_9x4, syncId, inventory, container, 4) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			case 5 -> new ChestMenu(MenuType.GENERIC_9x5, syncId, inventory, container, 5) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			case 6 -> new ChestMenu(MenuType.GENERIC_9x6, syncId, inventory, container, 6) {
				@Override
				public boolean stillValid(Player player) {
					return container.stillValid(player);
				}
			};
			default -> null;
		};
	}

	private static StorageMenuKind resolveMenuKind(BlockState state, BlockEntity blockEntity, Container container) {
		if (state.getBlock() instanceof ChestBlock) {
			return StorageMenuKind.CHEST_DYNAMIC;
		}
		if (blockEntity instanceof HopperBlockEntity) {
			return StorageMenuKind.HOPPER;
		}
		if (blockEntity instanceof DispenserBlockEntity || blockEntity instanceof DropperBlockEntity) {
			return StorageMenuKind.DISPENSER;
		}
		if (blockEntity instanceof ShulkerBoxBlockEntity) {
			return StorageMenuKind.SHULKER;
		}
		if (blockEntity instanceof BrewingStandBlockEntity) {
			return StorageMenuKind.BREWING;
		}
		if (blockEntity instanceof CrafterBlockEntity) {
			return StorageMenuKind.CRAFTER;
		}
		if (blockEntity instanceof BlastFurnaceBlockEntity) {
			return StorageMenuKind.BLAST_FURNACE;
		}
		if (blockEntity instanceof SmokerBlockEntity) {
			return StorageMenuKind.SMOKER;
		}
		if (blockEntity instanceof FurnaceBlockEntity) {
			return StorageMenuKind.FURNACE;
		}
		return StorageMenuKind.GENERIC_BY_SIZE;
	}

	private static StorageMenuKind resolveMenuKind(Entity entity, Container container) {
		if (entity instanceof MinecartHopper || container.getContainerSize() == 5) {
			return StorageMenuKind.HOPPER;
		}
		return StorageMenuKind.GENERIC_BY_SIZE;
	}

	private static boolean canCreateMenuForContainer(StorageMenuKind kind, Container container) {
		return switch (kind) {
			case CHEST_DYNAMIC, GENERIC_BY_SIZE -> isGenericMenuSizeSupported(container.getContainerSize());
			case HOPPER -> container.getContainerSize() == 5;
			case DISPENSER, SHULKER, CRAFTER -> container.getContainerSize() == 9;
			case BREWING -> container.getContainerSize() == 5;
			case FURNACE, BLAST_FURNACE, SMOKER -> container.getContainerSize() >= 3;
		};
	}

	private static boolean isGenericMenuSizeSupported(int size) {
		if (size == 5) {
			return true;
		}
		return size >= 9 && size <= 54 && size % 9 == 0;
	}

	private static ContainerData createDataForMenu(StorageMenuKind kind, BlockEntity blockEntity, Container container) {
		return switch (kind) {
			case BREWING -> new SimpleContainerData(2);
			case FURNACE, BLAST_FURNACE, SMOKER -> new SimpleContainerData(4);
			case CRAFTER -> createCrafterData(blockEntity);
			default -> null;
		};
	}

	private static ContainerData createCrafterData(BlockEntity blockEntity) {
		if (!(blockEntity instanceof CrafterBlockEntity crafter)) {
			return new SimpleContainerData(10);
		}

		return new ContainerData() {
			@Override
			public int get(int index) {
				if (index >= 0 && index < 9) {
					return crafter.isSlotDisabled(index) ? 1 : 0;
				}
				if (index == 9) {
					return crafter.isTriggered() ? 1 : 0;
				}
				return 0;
			}

			@Override
			public void set(int index, int value) {
			}

			@Override
			public int getCount() {
				return 10;
			}
		};
	}

	private static ContainerData ensureDataSize(ContainerData data, int requiredSize) {
		ContainerData delegate = data == null ? new SimpleContainerData(requiredSize) : data;
		if (delegate.getCount() == requiredSize) {
			return delegate;
		}
		return new FixedSizeData(delegate, requiredSize);
	}

	private static Component resolveDisplayName(BlockState state, BlockEntity blockEntity, Entity entity) {
		if (entity != null) {
			return entity.getDisplayName();
		}
		if (blockEntity instanceof Nameable nameable) {
			return nameable.getDisplayName();
		}
		Block block = state.getBlock();
		return block.getName();
	}

	private static String buildBlockStorageKey(ServerLevel level, BlockPos pos) {
		return "block:" + level.dimension().identifier() + ":" + pos.asLong();
	}

	private static void markBlockStorageTouched(ServerLevel level, BlockPos pos, BlockState state) {
		BlockPos keyPos = getUniqueBlockPosForStorage(state, pos);
		PLAYER_TOUCHED_BLOCK_STORAGES.add(buildBlockStorageKey(level, keyPos));
	}

	private static boolean wasBlockStorageTouched(ServerLevel level, BlockPos pos, BlockState state) {
		BlockPos keyPos = getUniqueBlockPosForStorage(state, pos);
		return PLAYER_TOUCHED_BLOCK_STORAGES.contains(buildBlockStorageKey(level, keyPos));
	}

	private static boolean wasEntityStorageTouched(Entity entity) {
		return PLAYER_TOUCHED_ENTITY_STORAGES.contains(entity.getUUID());
	}

	private static void enqueuePendingPlacement(ServerLevel level, BlockPos pos) {
		BlockPos immutablePos = pos.immutable();
		for (PendingPlacementCheck check : PENDING_PLACEMENT_CHECKS) {
			if (check.level == level && check.pos.equals(immutablePos)) {
				return;
			}
		}

		long expireTick = level.getGameTime() + PENDING_PLACEMENT_LIFETIME_TICKS;
		PENDING_PLACEMENT_CHECKS.add(new PendingPlacementCheck(level, immutablePos, expireTick));
	}

	private static BlockPos getUniqueBlockPosForStorage(BlockState state, BlockPos pos) {
		if (!(state.getBlock() instanceof ChestBlock)) {
			return pos.immutable();
		}

		ChestType type = state.getValue(ChestBlock.TYPE);
		if (type == ChestType.SINGLE) {
			return pos.immutable();
		}

		BlockPos connected = ChestBlock.getConnectedBlockPos(pos, state);
		if (connected == null) {
			return pos.immutable();
		}
		return connected.asLong() < pos.asLong() ? connected.immutable() : pos.immutable();
	}

	private static boolean isChestStillValid(ServerLevel level, BlockPos pos, int expectedSize) {
		BlockState currentState = level.getBlockState(pos);
		if (!(currentState.getBlock() instanceof ChestBlock currentChestBlock)) {
			return false;
		}
		Container currentContainer = ChestBlock.getContainer(currentChestBlock, currentState, level, pos, true);
		return currentContainer != null && currentContainer.getContainerSize() == expectedSize;
	}

	private static boolean isBlockEntityStillValid(ServerLevel level, BlockPos pos, BlockEntity expected) {
		BlockEntity current = level.getBlockEntity(pos);
		return current == expected && !current.isRemoved();
	}

	private static boolean sanitizeBoolean(JsonObject settings, String key, boolean defaultValue) {
		JsonElement element = settings.get(key);
		if (element instanceof JsonPrimitive primitive && primitive.isBoolean()) {
			return false;
		}

		settings.addProperty(key, defaultValue);
		return true;
	}

	private static boolean removeSetting(JsonObject settings, String key) {
		if (!settings.has(key)) {
			return false;
		}
		settings.remove(key);
		return true;
	}

	private static boolean getBoolean(JsonObject settings, String key, boolean fallback) {
		JsonElement element = settings.get(key);
		if (!(element instanceof JsonPrimitive primitive)) {
			return fallback;
		}

		if (primitive.isBoolean()) {
			return primitive.getAsBoolean();
		}
		if (primitive.isString()) {
			String value = primitive.getAsString().trim().toLowerCase();
			if ("true".equals(value)) {
				return true;
			}
			if ("false".equals(value)) {
				return false;
			}
		}
		return fallback;
	}

	private enum StorageMenuKind {
		CHEST_DYNAMIC,
		HOPPER,
		DISPENSER,
		SHULKER,
		BREWING,
		FURNACE,
		BLAST_FURNACE,
		SMOKER,
		CRAFTER,
		GENERIC_BY_SIZE
	}

	private interface PhysicalStorageTarget {
	}

	private static final class BlockStorageTarget implements PhysicalStorageTarget {
		private final ServerLevel level;
		private final BlockPos pos;

		private BlockStorageTarget(ServerLevel level, BlockPos pos) {
			this.level = level;
			this.pos = pos;
		}
	}

	private static final class EntityStorageTarget implements PhysicalStorageTarget {
		private final ContainerEntity entity;

		private EntityStorageTarget(ContainerEntity entity) {
			this.entity = entity;
		}
	}

	private static final class PendingPlacementCheck {
		private final ServerLevel level;
		private final BlockPos pos;
		private final long expireTick;

		private PendingPlacementCheck(ServerLevel level, BlockPos pos, long expireTick) {
			this.level = level;
			this.pos = pos;
			this.expireTick = expireTick;
		}
	}

	private static final class TriggerSource {
		private final ServerLevel sourceLevel;
		private final BlockPos sourcePos;
		private final int sourceEntityId;
		private final boolean fromEnderChest;

		private TriggerSource(ServerLevel sourceLevel, BlockPos sourcePos, int sourceEntityId, boolean fromEnderChest) {
			this.sourceLevel = sourceLevel;
			this.sourcePos = sourcePos;
			this.sourceEntityId = sourceEntityId;
			this.fromEnderChest = fromEnderChest;
		}

		private static TriggerSource forBlock(ServerLevel level, BlockPos pos, BlockState state, boolean fromEnderChest) {
			BlockPos keyPos = getUniqueBlockPosForStorage(state, pos);
			return new TriggerSource(level, keyPos, -1, fromEnderChest);
		}

		private static TriggerSource forEntity(int sourceEntityId) {
			return new TriggerSource(null, null, sourceEntityId, false);
		}
	}

	private interface ValidityCheck {
		boolean isValid(Player player);
	}

	private static final class ConditionalValidContainer implements Container {
		private final Container delegate;
		private final ValidityCheck validityCheck;

		private ConditionalValidContainer(Container delegate, ValidityCheck validityCheck) {
			this.delegate = delegate;
			this.validityCheck = validityCheck;
		}

		@Override
		public int getContainerSize() {
			return this.delegate.getContainerSize();
		}

		@Override
		public boolean isEmpty() {
			return this.delegate.isEmpty();
		}

		@Override
		public ItemStack getItem(int slot) {
			return this.delegate.getItem(slot);
		}

		@Override
		public ItemStack removeItem(int slot, int amount) {
			return this.delegate.removeItem(slot, amount);
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			return this.delegate.removeItemNoUpdate(slot);
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			this.delegate.setItem(slot, stack);
		}

		@Override
		public int getMaxStackSize() {
			return this.delegate.getMaxStackSize();
		}

		@Override
		public void setChanged() {
			this.delegate.setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return this.validityCheck.isValid(player);
		}

		@Override
		public void startOpen(ContainerUser user) {
			this.delegate.startOpen(user);
		}

		@Override
		public void stopOpen(ContainerUser user) {
			this.delegate.stopOpen(user);
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return this.delegate.canPlaceItem(slot, stack);
		}

		@Override
		public void clearContent() {
			this.delegate.clearContent();
		}
	}

	private static final class ConditionalValidCraftingContainer implements CraftingContainer {
		private final CraftingContainer delegate;
		private final ValidityCheck validityCheck;

		private ConditionalValidCraftingContainer(CraftingContainer delegate, ValidityCheck validityCheck) {
			this.delegate = delegate;
			this.validityCheck = validityCheck;
		}

		@Override
		public int getContainerSize() {
			return this.delegate.getContainerSize();
		}

		@Override
		public boolean isEmpty() {
			return this.delegate.isEmpty();
		}

		@Override
		public ItemStack getItem(int slot) {
			return this.delegate.getItem(slot);
		}

		@Override
		public ItemStack removeItem(int slot, int amount) {
			return this.delegate.removeItem(slot, amount);
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			return this.delegate.removeItemNoUpdate(slot);
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			this.delegate.setItem(slot, stack);
		}

		@Override
		public int getMaxStackSize() {
			return this.delegate.getMaxStackSize();
		}

		@Override
		public void setChanged() {
			this.delegate.setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return this.validityCheck.isValid(player);
		}

		@Override
		public void startOpen(ContainerUser user) {
			this.delegate.startOpen(user);
		}

		@Override
		public void stopOpen(ContainerUser user) {
			this.delegate.stopOpen(user);
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return this.delegate.canPlaceItem(slot, stack);
		}

		@Override
		public void clearContent() {
			this.delegate.clearContent();
		}

		@Override
		public int getWidth() {
			return this.delegate.getWidth();
		}

		@Override
		public int getHeight() {
			return this.delegate.getHeight();
		}

		@Override
		public List<ItemStack> getItems() {
			return this.delegate.getItems();
		}

		@Override
		public void fillStackedContents(net.minecraft.world.entity.player.StackedItemContents stackedItemContents) {
			this.delegate.fillStackedContents(stackedItemContents);
		}
	}

	private static final class FixedSizeData implements ContainerData {
		private final ContainerData delegate;
		private final int size;

		private FixedSizeData(ContainerData delegate, int size) {
			this.delegate = delegate;
			this.size = size;
		}

		@Override
		public int get(int index) {
			if (index < 0 || index >= this.delegate.getCount()) {
				return 0;
			}
			return this.delegate.get(index);
		}

		@Override
		public void set(int index, int value) {
			if (index < 0 || index >= this.delegate.getCount()) {
				return;
			}
			this.delegate.set(index, value);
		}

		@Override
		public int getCount() {
			return this.size;
		}
	}
}
