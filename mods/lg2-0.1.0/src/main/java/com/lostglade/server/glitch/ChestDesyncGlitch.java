package com.lostglade.server.glitch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.lostglade.Lg2;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.ServerBackroomsSystem;
import com.lostglade.server.ServerMechanicsGateSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.Container;
import net.minecraft.world.ItemStackWithSlot;
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
import net.minecraft.world.inventory.PlayerEnderChestContainer;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ChestDesyncGlitch implements BlockUseGlitchHandler, EntityUseGlitchHandler {
	private static final String INCLUDE_GENERATED_STORAGES = "includeGeneratedStorages";
	private static final int PENDING_PLACEMENT_LIFETIME_TICKS = 3;
	private static final int REMOTE_OPEN_TIMEOUT_TICKS = 200;
	private static final TicketType REMOTE_STORAGE_TICKET = Registry.register(
			BuiltInRegistries.TICKET_TYPE,
			Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "remote_storage_menu"),
			new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE)
	);
	private static final List<PendingPlacementCheck> PENDING_PLACEMENT_CHECKS = new ArrayList<>();
	private static final Set<PendingStorageOpen> PENDING_STORAGE_OPENS = new HashSet<>();
	private static final Set<PendingEntityOpen> PENDING_ENTITY_OPENS = new HashSet<>();
	private static final Map<UUID, ActiveStorageMenu> ACTIVE_STORAGE_MENUS = new HashMap<>();
	private static final Map<UUID, Map<UUID, ActiveEnderMenu>> ACTIVE_ENDER_MENUS = new HashMap<>();
	private static final Map<UUID, OfflineEnderChest> OFFLINE_ENDER_CHESTS = new HashMap<>();
	private static final Map<ServerLevel, Map<Long, Integer>> CHUNK_TICKET_REFS = new IdentityHashMap<>();
	private static TouchedStorageData touchedStorages;

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

	public static void resetTracking(MinecraftServer server) {
		stopTracking();
		touchedStorages = server.overworld().getDataStorage().computeIfAbsent(TouchedStorageData.TYPE);
	}

	public static void stopTracking() {
		clearActiveMenus();
		for (PendingStorageOpen pending : List.copyOf(PENDING_STORAGE_OPENS)) {
			pending.cancel();
		}
		for (PendingEntityOpen pending : List.copyOf(PENDING_ENTITY_OPENS)) {
			pending.cancel(false);
		}
		PENDING_STORAGE_OPENS.clear();
		PENDING_ENTITY_OPENS.clear();
		CHUNK_TICKET_REFS.forEach((level, refs) -> refs.keySet().forEach(key ->
				level.getChunkSource().removeTicketWithRadius(REMOTE_STORAGE_TICKET, new ChunkPos(key), 0)));
		CHUNK_TICKET_REFS.clear();
		PENDING_PLACEMENT_CHECKS.clear();
		ACTIVE_ENDER_MENUS.clear();
		OFFLINE_ENDER_CHESTS.clear();
		touchedStorages = null;
	}

	public static void onPlayerJoin(ServerPlayer player) {
		OfflineEnderChest chest = OFFLINE_ENDER_CHESTS.remove(player.getUUID());
		if (chest != null) {
			chest.invalidate();
			for (ServerPlayer viewer : List.copyOf(chest.viewers)) {
				viewer.closeContainer();
			}
		}
	}

	public static void onPlayerDisconnect(ServerPlayer player) {
		Map<UUID, ActiveEnderMenu> menus = ACTIVE_ENDER_MENUS.remove(player.getUUID());
		if (menus != null) {
			for (ActiveEnderMenu active : menus.values()) {
				if (active.viewer.containerMenu == active.menu) {
					active.viewer.closeContainer();
				}
			}
		}
		for (Map<UUID, ActiveEnderMenu> targetMenus : ACTIVE_ENDER_MENUS.values()) {
			targetMenus.remove(player.getUUID());
		}
	}

	public static void stopTracking(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				rememberEntityLocation(entity);
			}
		}
		stopTracking();
	}

	public static void tickPlacementTracking(MinecraftServer server) {
		tickRemoteMenus(server);
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

		if (touchedStorages != null && touchedStorages.entities.add(entity.getUUID().toString())) {
			touchedStorages.setDirty();
		}
		rememberEntityLocation(entity);
	}

	public static void noteEntityLoad(Entity entity) {
		rememberEntityLocation(entity);
	}

	public static void noteEntityUnload(Entity entity) {
		if (!(entity instanceof ContainerEntity) || touchedStorages == null) {
			return;
		}
		String uuid = entity.getUUID().toString();
		if (entity.getRemovalReason() != null && entity.getRemovalReason().shouldDestroy()) {
			boolean removed = touchedStorages.entities.remove(uuid);
			removed |= touchedStorages.locations.remove(uuid) != null;
			if (removed) {
				touchedStorages.setDirty();
			}
		} else {
			rememberEntityLocation(entity);
		}
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

		TriggerSource source = TriggerSource.forEntity(entity);
		return triggerFromStorageUse(server, random, entry, player, source);
	}

	private static boolean triggerFromStorageUse(
			MinecraftServer server,
			RandomSource random,
			GlitchConfig.GlitchEntry entry,
			ServerPlayer opener,
			TriggerSource source
	) {
		for (PendingStorageOpen pending : PENDING_STORAGE_OPENS) {
			if (pending.opener == opener) {
				return false;
			}
		}
		for (PendingEntityOpen pending : PENDING_ENTITY_OPENS) {
			if (pending.opener == opener) {
				return false;
			}
		}

		JsonObject settings = entry.settings == null ? new JsonObject() : entry.settings;
		boolean includeGeneratedStorages = getBoolean(settings, INCLUDE_GENERATED_STORAGES, false);

		List<PhysicalStorageTarget> physicalTargets = collectPhysicalStorageTargets(server, includeGeneratedStorages);
		List<EnderChestTarget> enderTargets = collectEnderChestTargets(server);
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
				rememberEntityLocation(entity);

				String key = "entity:" + entity.getUUID();
				if (!visited.add(key)) {
					continue;
				}

				targets.add(new EntityStorageTarget(containerEntity));
			}
		}

		if (touchedStorages != null) {
			for (String key : touchedStorages.blocks) {
				if (!visited.add(key)) {
					continue;
				}
				BlockStorageTarget target = parseBlockStorageKey(server, key);
				if (target != null && !ServerBackroomsSystem.isBackrooms(target.level)) {
					targets.add(target);
				}
			}
			for (EntityLocation location : touchedStorages.locations.values()) {
				if (!touchedStorages.entities.contains(location.uuid) || !visited.add("entity:" + location.uuid)) {
					continue;
				}
				Identifier dimensionId = Identifier.tryParse(location.dimension);
				ServerLevel level = dimensionId == null ? null
						: server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
				if (level != null && !ServerBackroomsSystem.isBackrooms(level)) {
					targets.add(new UnloadedEntityStorageTarget(level, location));
				}
			}
		}

		return targets;
	}

	private static List<EnderChestTarget> collectEnderChestTargets(MinecraftServer server) {
		List<EnderChestTarget> targets = new ArrayList<>();
		Set<UUID> online = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			online.add(player.getUUID());
		}
		for (ServerPlayer target : GlitchPlayers.eligiblePlayers(server)) {
			targets.add(new EnderChestTarget(target.getUUID(), target));
		}
		try {
			for (UUID owner : OfflineEnderChestOwners.list(server.getWorldPath(LevelResource.PLAYER_DATA_DIR), online)) {
				targets.add(new EnderChestTarget(owner, null));
			}
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to enumerate offline ender chests", exception);
		}
		return targets;
	}

	private static boolean openRandomStorage(
			ServerPlayer opener,
			RandomSource random,
			List<PhysicalStorageTarget> physicalTargets,
			List<EnderChestTarget> enderTargets,
			TriggerSource source,
			boolean includeGeneratedStorages
	) {
		List<StorageTarget> combinedTargets = new ArrayList<>(physicalTargets.size() + enderTargets.size());
		for (PhysicalStorageTarget physicalTarget : physicalTargets) {
			combinedTargets.add(new PhysicalCandidate(physicalTarget));
		}
		for (EnderChestTarget enderTarget : enderTargets) {
			combinedTargets.add(new EnderCandidate(enderTarget));
		}

		while (!combinedTargets.isEmpty()) {
			int index = random.nextInt(combinedTargets.size());
			StorageTarget target = combinedTargets.remove(index);
			if (target instanceof PhysicalCandidate physicalCandidate) {
				if (isSameStorageAsSource(physicalCandidate.target, source)) {
					continue;
				}
				if (openPhysicalTarget(opener, physicalCandidate.target, source, includeGeneratedStorages)) {
					return true;
				}
				continue;
			}

			if (target instanceof EnderCandidate enderCandidate) {
				if (source.fromEnderChest && enderCandidate.target.owner().equals(opener.getUUID())) {
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
			if (openPhysicalTarget(opener, target, source, includeGeneratedStorages)) {
				return true;
			}
		}
		return false;
	}

	private interface StorageTarget {
	}

	private record PhysicalCandidate(PhysicalStorageTarget target) implements StorageTarget {
	}

	private record EnderChestTarget(UUID owner, ServerPlayer onlinePlayer) {
	}

	private record EnderCandidate(EnderChestTarget target) implements StorageTarget {
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
		if (target instanceof UnloadedEntityStorageTarget entityTarget) {
			return source.sourceEntityUuid != null && source.sourceEntityUuid.equals(entityTarget.location.uuid);
		}

		return false;
	}

	private static boolean openPhysicalTarget(
			ServerPlayer opener,
			PhysicalStorageTarget target,
			TriggerSource source,
			boolean includeGeneratedStorages
	) {
		if (target instanceof BlockStorageTarget blockTarget) {
			return queueBlockStorage(opener, blockTarget, source, includeGeneratedStorages);
		}
		if (target instanceof EntityStorageTarget entityTarget) {
			if (!(entityTarget.entity instanceof Entity entity) || !(entity.level() instanceof ServerLevel level)) {
				return false;
			}
			EntityLocation location = new EntityLocation(entity.getUUID().toString(),
					level.dimension().identifier().toString(), entity.chunkPosition().toLong());
			return queueEntityStorage(opener, new UnloadedEntityStorageTarget(level, location), source, includeGeneratedStorages);
		}
		if (target instanceof UnloadedEntityStorageTarget entityTarget) {
			return queueEntityStorage(opener, entityTarget, source, includeGeneratedStorages);
		}
		return false;
	}

	private static boolean queueBlockStorage(
			ServerPlayer opener,
			BlockStorageTarget target,
			TriggerSource source,
			boolean includeGeneratedStorages
	) {
		BlockPos pos = target.pos;
		ChunkPos chunkPos = new ChunkPos(pos);
		if (target.level.getChunkSource().hasChunk(chunkPos.x, chunkPos.z)
				&& !(target.level.getBlockEntity(pos) instanceof Container)) {
			forgetStaleBlockStorage(target);
			return false;
		}

		PendingStorageOpen pending = new PendingStorageOpen(opener, target, source, includeGeneratedStorages);
		PENDING_STORAGE_OPENS.add(pending);
		pending.load(chunkPos, true);
		return true;
	}

	private static void forgetStaleBlockStorage(BlockStorageTarget target) {
		if (touchedStorages != null && touchedStorages.blocks.remove(buildBlockStorageKey(target.level, target.pos))) {
			touchedStorages.setDirty();
		}
	}

	private static boolean queueEntityStorage(ServerPlayer opener, UnloadedEntityStorageTarget target,
			TriggerSource source, boolean includeGeneratedStorages) {
		UUID uuid;
		try {
			uuid = UUID.fromString(target.location.uuid);
		} catch (IllegalArgumentException exception) {
			forgetStaleEntityLocation(target.location);
			return false;
		}
		PendingEntityOpen pending = new PendingEntityOpen(opener, target, source, uuid, includeGeneratedStorages);
		PENDING_ENTITY_OPENS.add(pending);
		pending.load();
		return true;
	}

	private static void forgetStaleEntityLocation(EntityLocation location) {
		if (touchedStorages != null && location.equals(touchedStorages.locations.get(location.uuid))) {
			touchedStorages.locations.remove(location.uuid);
			touchedStorages.setDirty();
		}
	}

	private static void tickRemoteMenus(MinecraftServer server) {
		long now = server.overworld().getGameTime();
		ACTIVE_ENDER_MENUS.entrySet().removeIf(entry -> {
			entry.getValue().entrySet().removeIf(viewerEntry -> {
				ActiveEnderMenu active = viewerEntry.getValue();
				return active.viewer.containerMenu != active.menu;
			});
			return entry.getValue().isEmpty();
		});
		for (OfflineEnderChest chest : List.copyOf(OFFLINE_ENDER_CHESTS.values())) {
			if (server.getPlayerList().getPlayer(chest.owner) != null) {
				onPlayerJoin(server.getPlayerList().getPlayer(chest.owner));
			} else if (!chest.valid) {
				for (ServerPlayer viewer : List.copyOf(chest.viewers)) {
					viewer.closeContainer();
				}
			}
		}
		for (PendingStorageOpen pending : List.copyOf(PENDING_STORAGE_OPENS)) {
			if (now >= pending.expireTick) {
				pending.cancel(true);
			}
		}
		for (PendingEntityOpen pending : List.copyOf(PENDING_ENTITY_OPENS)) {
			pending.tick(now);
		}
		ACTIVE_STORAGE_MENUS.entrySet().removeIf(entry -> {
			ActiveStorageMenu active = entry.getValue();
			if (server.getPlayerList().getPlayer(entry.getKey()) == active.player
					&& active.player.containerMenu == active.menu) {
				if (active.entity != null) {
					if (active.entity.isRemoved() || !active.entity.isAlive()
							|| active.entity.level() != active.lease.level) {
						active.player.closeContainer();
					} else {
						try {
							active.lease.retain(active.entity.chunkPosition());
							rememberEntityLocation(active.entity);
							return false;
						} catch (RuntimeException exception) {
							Lg2.LOGGER.warn("Failed to keep remote entity storage loaded", exception);
							active.player.closeContainer();
						}
					}
				} else {
					return false;
				}
			}
			active.lease.release();
			return true;
		});
	}

	private static void clearActiveMenus() {
		for (ActiveStorageMenu active : ACTIVE_STORAGE_MENUS.values()) {
			active.lease.release();
		}
		ACTIVE_STORAGE_MENUS.clear();
	}

	private static void openOriginalStorage(ServerPlayer opener, TriggerSource source) {
		if (source.fromEnderChest) {
			openEnderChest(opener, opener);
		} else if (source.sourceLevel != null && source.sourcePos != null) {
			openBlockStorage(opener, new BlockStorageTarget(source.sourceLevel, source.sourcePos), true);
		} else if (opener.level() instanceof ServerLevel level) {
			Entity entity = level.getEntity(source.sourceEntityId);
			if (entity instanceof ContainerEntity containerEntity) {
				openEntityStorage(opener, new EntityStorageTarget(containerEntity), true);
			}
		}
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
		if (target == null || target.isRemoved()) {
			return false;
		}

		Container proxied = new ConditionalValidContainer(
				target.getEnderChestInventory(),
				player -> !target.isRemoved() && target.level().getServer().getPlayerList().getPlayer(target.getUUID()) == target
		);
		boolean opened = openContainerWithMenuKind(
				opener,
				Component.translatable("container.enderchest"),
				proxied,
				StorageMenuKind.CHEST_DYNAMIC,
				null
		);
		if (opened && opener != target) {
			ACTIVE_ENDER_MENUS.computeIfAbsent(target.getUUID(), ignored -> new HashMap<>())
					.put(opener.getUUID(), new ActiveEnderMenu(opener, opener.containerMenu));
		}
		return opened;
	}

	private static boolean openEnderChest(ServerPlayer opener, EnderChestTarget target) {
		if (target.onlinePlayer() != null) {
			return openEnderChest(opener, target.onlinePlayer());
		}
		MinecraftServer server = opener.level().getServer();
		if (server.getPlayerList().getPlayer(target.owner()) != null) {
			return false;
		}
		OfflineEnderChest chest = OFFLINE_ENDER_CHESTS.get(target.owner());
		if (chest == null) {
			chest = OfflineEnderChest.load(server, target.owner());
			if (chest == null) {
				return false;
			}
			OFFLINE_ENDER_CHESTS.put(target.owner(), chest);
		}
		OfflineEnderChest selected = chest;
		Container proxied = new ConditionalValidContainer(chest,
				player -> selected.valid && server.getPlayerList().getPlayer(target.owner()) == null);
		boolean opened = openContainerWithMenuKind(opener, Component.translatable("container.enderchest"),
				proxied, StorageMenuKind.CHEST_DYNAMIC, null);
		if (!opened && chest.viewers.isEmpty()) {
			OFFLINE_ENDER_CHESTS.remove(target.owner(), chest);
		}
		return opened;
	}

	private static final class OfflineEnderChest extends PlayerEnderChestContainer {
		private final MinecraftServer server;
		private final UUID owner;
		private final Path file;
		private final CompoundTag playerData;
		private final Set<ServerPlayer> viewers = new HashSet<>();
		private boolean initialized;
		private boolean valid = true;

		private OfflineEnderChest(MinecraftServer server, UUID owner, Path file, CompoundTag playerData) {
			this.server = server;
			this.owner = owner;
			this.file = file;
			this.playerData = playerData;
		}

		private static OfflineEnderChest load(MinecraftServer server, UUID owner) {
			Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat");
			if (!Files.isRegularFile(file)) {
				return null;
			}
			try {
				CompoundTag raw = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
				CompoundTag data = DataFixTypes.PLAYER.updateToCurrentVersion(server.getFixerUpper(), raw,
						NbtUtils.getDataVersion(raw));
				ProblemReporter.Collector errors = new ProblemReporter.Collector();
				var input = TagValueInput.create(errors, server.registryAccess(), data);
				var items = input.list("EnderItems", ItemStackWithSlot.CODEC);
				if (data.get("EnderItems") != null && items.isEmpty()) {
					return null;
				}
				OfflineEnderChest chest = new OfflineEnderChest(server, owner, file, data);
				chest.fromSlots(input.listOrEmpty("EnderItems", ItemStackWithSlot.CODEC));
				if (!errors.isEmpty()) {
					Lg2.LOGGER.warn("Refusing to edit malformed offline ender chest for {}: {}", owner, errors.getTreeReport());
					return null;
				}
				chest.initialized = true;
				return chest;
			} catch (Exception exception) {
				Lg2.LOGGER.warn("Failed to load offline ender chest for {}", owner, exception);
				return null;
			}
		}

		private void invalidate() {
			this.valid = false;
		}

		@Override
		public void setChanged() {
			super.setChanged();
			if (this.initialized && this.valid) {
				try {
					this.persist();
				} catch (Exception exception) {
					this.invalidate();
					Lg2.LOGGER.error("Failed to save offline ender chest for {}; closing its menus", this.owner, exception);
				}
			}
		}

		private void persist() throws IOException {
			if (this.server.getPlayerList().getPlayer(this.owner) != null) {
				throw new IOException("Ender chest owner joined while offline menu was open");
			}
			ProblemReporter.Collector errors = new ProblemReporter.Collector();
			TagValueOutput output = TagValueOutput.createWithContext(errors, this.server.registryAccess());
			this.storeAsSlots(output.list("EnderItems", ItemStackWithSlot.CODEC));
			if (!errors.isEmpty()) {
				throw new IOException("Could not encode offline ender chest: " + errors.getTreeReport());
			}
			this.playerData.put("EnderItems", output.buildResult().get("EnderItems"));
			NbtUtils.addCurrentDataVersion(this.playerData);
			Path temp = Files.createTempFile(this.file.getParent(), this.owner + "-", ".dat");
			try {
				NbtIo.writeCompressed(this.playerData, temp);
				if (!Util.safeReplaceOrMoveFile(this.file, temp,
						this.file.resolveSibling(this.owner + ".dat_old"), false)) {
					throw new IOException("Could not atomically replace player data");
				}
			} finally {
				Files.deleteIfExists(temp);
			}
		}

		@Override
		public void startOpen(ContainerUser user) {
			super.startOpen(user);
			if (user instanceof ServerPlayer player) {
				this.viewers.add(player);
			}
		}

		@Override
		public void stopOpen(ContainerUser user) {
			super.stopOpen(user);
			if (user instanceof ServerPlayer player) {
				this.viewers.remove(player);
			}
			if (this.viewers.isEmpty()) {
				OFFLINE_ENDER_CHESTS.remove(this.owner, this);
			}
		}
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
		if (touchedStorages != null && touchedStorages.blocks.add(buildBlockStorageKey(level, keyPos))) {
			touchedStorages.setDirty();
		}
	}

	private static boolean wasBlockStorageTouched(ServerLevel level, BlockPos pos, BlockState state) {
		BlockPos keyPos = getUniqueBlockPosForStorage(state, pos);
		return touchedStorages != null && touchedStorages.blocks.contains(buildBlockStorageKey(level, keyPos));
	}

	private static boolean wasEntityStorageTouched(Entity entity) {
		return touchedStorages != null && touchedStorages.entities.contains(entity.getUUID().toString());
	}

	private static void rememberEntityLocation(Entity entity) {
		if (!(entity instanceof ContainerEntity) || !(entity.level() instanceof ServerLevel level) || touchedStorages == null) {
			return;
		}
		String uuid = entity.getUUID().toString();
		if (!touchedStorages.entities.contains(uuid)) {
			return;
		}
		EntityLocation location = new EntityLocation(uuid, level.dimension().identifier().toString(), entity.chunkPosition().toLong());
		if (!location.equals(touchedStorages.locations.put(uuid, location))) {
			touchedStorages.setDirty();
		}
	}

	private static BlockStorageTarget parseBlockStorageKey(MinecraftServer server, String key) {
		if (!key.startsWith("block:")) {
			return null;
		}
		int separator = key.lastIndexOf(':');
		if (separator <= "block:".length()) {
			return null;
		}
		Identifier dimensionId = Identifier.tryParse(key.substring("block:".length(), separator));
		if (dimensionId == null) {
			return null;
		}
		try {
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
			return level == null ? null : new BlockStorageTarget(level, BlockPos.of(Long.parseLong(key.substring(separator + 1))));
		} catch (NumberFormatException exception) {
			return null;
		}
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

	private record UnloadedEntityStorageTarget(ServerLevel level, EntityLocation location) implements PhysicalStorageTarget {
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

	record EntityLocation(String uuid, String dimension, long chunkPos) {
		private static final Codec<EntityLocation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("uuid").forGetter(EntityLocation::uuid),
				Codec.STRING.fieldOf("dimension").forGetter(EntityLocation::dimension),
				Codec.LONG.fieldOf("chunk").forGetter(EntityLocation::chunkPos)
		).apply(instance, EntityLocation::new));
	}

	static final class TouchedStorageData extends SavedData {
		static final Codec<TouchedStorageData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.listOf().optionalFieldOf("blocks", List.of()).forGetter(data -> List.copyOf(data.blocks)),
				Codec.STRING.listOf().optionalFieldOf("entities", List.of()).forGetter(data -> List.copyOf(data.entities)),
				EntityLocation.CODEC.listOf().optionalFieldOf("locations", List.of())
						.forGetter(data -> List.copyOf(data.locations.values()))
		).apply(instance, TouchedStorageData::new));
		private static final SavedDataType<TouchedStorageData> TYPE = new SavedDataType<>(
				"lg2_chest_desync", TouchedStorageData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE
		);
		private final Set<String> blocks;
		private final Set<String> entities;
		private final Map<String, EntityLocation> locations;

		private TouchedStorageData() {
			this(List.of(), List.of(), List.of());
		}

		TouchedStorageData(List<String> blocks, List<String> entities, List<EntityLocation> locations) {
			this.blocks = new HashSet<>(blocks);
			this.entities = new HashSet<>(entities);
			this.locations = new HashMap<>();
			for (EntityLocation location : locations) {
				this.locations.put(location.uuid, location);
			}
		}
	}

	private record ActiveStorageMenu(ServerPlayer player, AbstractContainerMenu menu, ChunkLease lease, Entity entity) {
	}

	private record ActiveEnderMenu(ServerPlayer viewer, AbstractContainerMenu menu) {
	}

	private static final class ChunkLease {
		private final ServerLevel level;
		private final Set<Long> chunks = new HashSet<>();

		private ChunkLease(ServerLevel level) {
			this.level = level;
		}

		private CompletableFuture<?> load(ChunkPos pos) {
			long key = pos.toLong();
			if (!this.chunks.add(key)) {
				return CompletableFuture.completedFuture(null);
			}
			CHUNK_TICKET_REFS.computeIfAbsent(this.level, ignored -> new HashMap<>()).merge(key, 1, Integer::sum);
			try {
				return this.level.getChunkSource().addTicketAndLoadWithRadius(REMOTE_STORAGE_TICKET, pos, 0);
			} catch (RuntimeException exception) {
				this.releaseOne(key);
				throw exception;
			}
		}

		private void retain(ChunkPos pos) {
			long keep = pos.toLong();
			if (!this.chunks.contains(keep)) {
				this.load(pos);
			}
			for (long key : Set.copyOf(this.chunks)) {
				if (key != keep) {
					this.releaseOne(key);
				}
			}
		}

		private void release() {
			for (long key : Set.copyOf(this.chunks)) {
				this.releaseOne(key);
			}
		}

		private void releaseOne(long key) {
			if (!this.chunks.remove(key)) {
				return;
			}
			Map<Long, Integer> refs = CHUNK_TICKET_REFS.get(this.level);
			if (refs == null) {
				return;
			}
			int remaining = refs.getOrDefault(key, 1) - 1;
			if (remaining == 0) {
				refs.remove(key);
				this.level.getChunkSource().removeTicketWithRadius(REMOTE_STORAGE_TICKET, new ChunkPos(key), 0);
				if (refs.isEmpty()) {
					CHUNK_TICKET_REFS.remove(this.level);
				}
			} else {
				refs.put(key, remaining);
			}
		}
	}

	private static final class PendingStorageOpen {
		private final ServerPlayer opener;
		private final BlockStorageTarget target;
		private final TriggerSource source;
		private final boolean includeGeneratedStorages;
		private final AbstractContainerMenu menuAtStart;
		private final Level levelAtStart;
		private final ChunkLease lease;
		private final long expireTick;
		private boolean finished;

		private PendingStorageOpen(ServerPlayer opener, BlockStorageTarget target, TriggerSource source, boolean includeGeneratedStorages) {
			this.opener = opener;
			this.target = target;
			this.source = source;
			this.includeGeneratedStorages = includeGeneratedStorages;
			this.menuAtStart = opener.containerMenu;
			this.levelAtStart = opener.level();
			this.lease = new ChunkLease(target.level);
			this.expireTick = target.level.getServer().overworld().getGameTime() + REMOTE_OPEN_TIMEOUT_TICKS;
		}

		private void load(ChunkPos pos, boolean primary) {
			CompletableFuture<?> future;
			try {
				future = this.lease.load(pos);
			} catch (RuntimeException exception) {
				Lg2.LOGGER.warn("Failed to schedule remote storage chunk load at {}", pos, exception);
				this.cancel(true);
				return;
			}
			future.whenComplete((ignored, exception) -> this.target.level.getServer().execute(() -> {
				if (this.finished) {
					return;
				}
				if (exception != null || !this.target.level.getChunkSource().hasChunk(pos.x, pos.z)) {
					this.cancel(true);
					return;
				}
				if (primary) {
					this.onPrimaryLoaded();
				} else {
					this.openLoaded();
				}
			}));
		}

		private void onPrimaryLoaded() {
			BlockState state = this.target.level.getBlockState(this.target.pos);
			if (!(this.target.level.getBlockEntity(this.target.pos) instanceof Container)) {
				forgetStaleBlockStorage(this.target);
				this.cancel(true);
				return;
			}
			if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
				BlockPos connected = ChestBlock.getConnectedBlockPos(this.target.pos, state);
				if (connected != null) {
					ChunkPos adjacent = new ChunkPos(connected);
					if (adjacent.toLong() != new ChunkPos(this.target.pos).toLong()) {
						this.load(adjacent, false);
						return;
					}
				}
			}
			this.openLoaded();
		}

		private void openLoaded() {
			if (!this.canOpen()) {
				this.cancel(false);
				return;
			}
			try {
				if (openBlockStorage(this.opener, this.target, this.includeGeneratedStorages)) {
					this.finished = true;
					PENDING_STORAGE_OPENS.remove(this);
					ActiveStorageMenu previous = ACTIVE_STORAGE_MENUS.put(this.opener.getUUID(),
							new ActiveStorageMenu(this.opener, this.opener.containerMenu, this.lease, null));
					if (previous != null) {
						previous.lease.release();
					}
					return;
				}
			} catch (RuntimeException exception) {
				Lg2.LOGGER.warn("Failed to open remote storage at {}", this.target.pos, exception);
			}
			this.cancel(true);
		}

		private boolean canOpen() {
			return this.target.level.getServer().getPlayerList().getPlayer(this.opener.getUUID()) == this.opener
					&& this.opener.isAlive() && !this.opener.isRemoved()
					&& this.opener.level() == this.levelAtStart && this.opener.containerMenu == this.menuAtStart;
		}

		private void cancel() {
			this.cancel(false);
		}

		private void cancel(boolean openOriginal) {
			if (this.finished) {
				return;
			}
			this.finished = true;
			PENDING_STORAGE_OPENS.remove(this);
			this.lease.release();
			if (openOriginal && this.canOpen()) {
				openOriginalStorage(this.opener, this.source);
			}
		}
	}

	private static final class PendingEntityOpen {
		private final ServerPlayer opener;
		private final UnloadedEntityStorageTarget target;
		private final TriggerSource source;
		private final UUID uuid;
		private final boolean includeGeneratedStorages;
		private final AbstractContainerMenu menuAtStart;
		private final Level levelAtStart;
		private final ChunkLease lease;
		private final long expireTick;
		private boolean chunkReady;
		private boolean finished;

		private PendingEntityOpen(ServerPlayer opener, UnloadedEntityStorageTarget target, TriggerSource source,
				UUID uuid, boolean includeGeneratedStorages) {
			this.opener = opener;
			this.target = target;
			this.source = source;
			this.uuid = uuid;
			this.includeGeneratedStorages = includeGeneratedStorages;
			this.menuAtStart = opener.containerMenu;
			this.levelAtStart = opener.level();
			this.lease = new ChunkLease(target.level);
			this.expireTick = target.level.getServer().overworld().getGameTime() + REMOTE_OPEN_TIMEOUT_TICKS;
		}

		private void load() {
			ChunkPos chunk = new ChunkPos(this.target.location.chunkPos);
			CompletableFuture<?> future;
			try {
				future = this.lease.load(chunk);
			} catch (RuntimeException exception) {
				Lg2.LOGGER.warn("Failed to schedule remote entity storage chunk load at {}", chunk, exception);
				this.cancel(true);
				return;
			}
			future.whenComplete((ignored, exception) -> this.target.level.getServer().execute(() -> {
				if (this.finished) {
					return;
				}
				if (exception != null || !this.target.level.getChunkSource().hasChunk(chunk.x, chunk.z)) {
					this.cancel(true);
					return;
				}
				this.chunkReady = true;
			}));
		}

		private void tick(long now) {
			if (this.finished) {
				return;
			}
			if (!this.canOpen()) {
				this.cancel(false);
				return;
			}
			if (now >= this.expireTick) {
				if (this.chunkReady) {
					forgetStaleEntityLocation(this.target.location);
				}
				this.cancel(true);
				return;
			}
			if (!this.chunkReady) {
				return;
			}
			Entity entity = this.target.level.getEntity(this.uuid);
			if (entity == null) {
				return;
			}
			if (!(entity instanceof ContainerEntity containerEntity) || !(entity instanceof Container container)
					|| entity.isRemoved() || !entity.isAlive()) {
				forgetStaleEntityLocation(this.target.location);
				this.cancel(true);
				return;
			}
			ChunkPos actual = entity.chunkPosition();
			ChunkPos indexed = new ChunkPos(this.target.location.chunkPos);
			rememberEntityLocation(entity);
			if (Math.abs(actual.x - indexed.x) > 1 || Math.abs(actual.z - indexed.z) > 1
					|| !canCreateMenuForContainer(resolveMenuKind(entity, container), container)) {
				this.cancel(true);
				return;
			}
			try {
				this.lease.retain(actual);
				if (openEntityStorage(this.opener, new EntityStorageTarget(containerEntity), this.includeGeneratedStorages)) {
					this.finished = true;
					PENDING_ENTITY_OPENS.remove(this);
					ActiveStorageMenu previous = ACTIVE_STORAGE_MENUS.put(this.opener.getUUID(),
							new ActiveStorageMenu(this.opener, this.opener.containerMenu, this.lease, entity));
					if (previous != null) {
						previous.lease.release();
					}
					return;
				}
			} catch (RuntimeException exception) {
				Lg2.LOGGER.warn("Failed to open remote entity storage {}", this.uuid, exception);
			}
			this.cancel(true);
		}

		private boolean canOpen() {
			return this.target.level.getServer().getPlayerList().getPlayer(this.opener.getUUID()) == this.opener
					&& this.opener.isAlive() && !this.opener.isRemoved()
					&& this.opener.level() == this.levelAtStart && this.opener.containerMenu == this.menuAtStart;
		}

		private void cancel(boolean openOriginal) {
			if (this.finished) {
				return;
			}
			this.finished = true;
			PENDING_ENTITY_OPENS.remove(this);
			this.lease.release();
			if (openOriginal && this.canOpen()) {
				openOriginalStorage(this.opener, this.source);
			}
		}
	}

	private static final class TriggerSource {
		private final ServerLevel sourceLevel;
		private final BlockPos sourcePos;
		private final int sourceEntityId;
		private final String sourceEntityUuid;
		private final boolean fromEnderChest;

		private TriggerSource(ServerLevel sourceLevel, BlockPos sourcePos, int sourceEntityId, String sourceEntityUuid,
				boolean fromEnderChest) {
			this.sourceLevel = sourceLevel;
			this.sourcePos = sourcePos;
			this.sourceEntityId = sourceEntityId;
			this.sourceEntityUuid = sourceEntityUuid;
			this.fromEnderChest = fromEnderChest;
		}

		private static TriggerSource forBlock(ServerLevel level, BlockPos pos, BlockState state, boolean fromEnderChest) {
			BlockPos keyPos = getUniqueBlockPosForStorage(state, pos);
			return new TriggerSource(level, keyPos, -1, null, fromEnderChest);
		}

		private static TriggerSource forEntity(Entity entity) {
			return new TriggerSource(null, null, entity.getId(), entity.getUUID().toString(), false);
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
