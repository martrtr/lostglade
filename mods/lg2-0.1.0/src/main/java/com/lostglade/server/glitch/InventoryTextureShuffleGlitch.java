package com.lostglade.server.glitch;

import com.google.gson.JsonObject;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.ServerBackroomsSystem;
import com.lostglade.server.ServerStabilitySystem;
import com.lostglade.server.ServerUpgradeUiSystem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class InventoryTextureShuffleGlitch implements ServerGlitchHandler {
	private static final String MIN_TARGET_PLAYERS = "minTargetPlayers";
	private static final String MAX_TARGET_PLAYERS = "maxTargetPlayers";
	private static final String MIN_SHUFFLED_PERCENT = "minShuffledPercent";
	private static final String MAX_SHUFFLED_PERCENT = "maxShuffledPercent";
	private static final String SHUFFLED_PERCENT_PRIORITY = "shuffledPercentPriority";
	private static final String MIN_DURATION_SECONDS = "minDurationSeconds";
	private static final String MAX_DURATION_SECONDS = "maxDurationSeconds";
	private static final String MIN_RESHUFFLE_INTERVAL_TICKS = "minReshuffleIntervalTicks";
	private static final String MAX_RESHUFFLE_INTERVAL_TICKS = "maxReshuffleIntervalTicks";
	private static final String SHUFFLE_META_TAG = "lg2InventoryTextureShuffle";
	private static final String SHUFFLE_META_TOKEN = "token";
	private static final String SHUFFLE_META_HAS_ORIGINAL_MODEL = "hasOriginalModel";
	private static final String SHUFFLE_META_ORIGINAL_MODEL = "originalModel";

	private static final int PLAYER_INVENTORY_SLOT_COUNT = 41;
	private static final int RESEND_INTERVAL_TICKS = 5;
	private static final Set<Integer> FULL_INVENTORY_SLOTS = buildFullInventorySlotSet();
	private static final Map<UUID, ActiveShuffleState> ACTIVE_STATES = new HashMap<>();

	public static boolean isActiveFor(ServerPlayer player) {
		return player != null && ACTIVE_STATES.containsKey(player.getUUID());
	}

	@Override
	public String id() {
		return "inventory_texture_shuffle";
	}

	@Override
	public GlitchConfig.GlitchEntry defaultEntry() {
		GlitchConfig.GlitchEntry entry = new GlitchConfig.GlitchEntry();
		entry.enabled = true;
		entry.minStabilityPercent = 0.0D;
		entry.maxStabilityPercent = 70.0D;
		entry.chancePerCheck = 0.2D;
		entry.stabilityInfluence = 1.0D;
		entry.minCooldownTicks = 80;
		entry.maxCooldownTicks = 1200;

		JsonObject settings = new JsonObject();
		settings.addProperty(MIN_TARGET_PLAYERS, 1);
		settings.addProperty(MAX_TARGET_PLAYERS, 4);
		settings.addProperty(MIN_SHUFFLED_PERCENT, 8.0D);
		settings.addProperty(MAX_SHUFFLED_PERCENT, 75.0D);
		settings.addProperty(SHUFFLED_PERCENT_PRIORITY, 2.0D);
		settings.addProperty(MIN_DURATION_SECONDS, 3);
		settings.addProperty(MAX_DURATION_SECONDS, 20);
		settings.addProperty(MIN_RESHUFFLE_INTERVAL_TICKS, 8);
		settings.addProperty(MAX_RESHUFFLE_INTERVAL_TICKS, 50);
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
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_TARGET_PLAYERS, 4, 1, 100);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, MIN_SHUFFLED_PERCENT, 8.0D, 0.0D, 100.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, MAX_SHUFFLED_PERCENT, 75.0D, 0.0D, 100.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, SHUFFLED_PERCENT_PRIORITY, 2.0D, 0.1D, 10.0D);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MIN_DURATION_SECONDS, 3, 1, 7200);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_DURATION_SECONDS, 20, 1, 7200);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MIN_RESHUFFLE_INTERVAL_TICKS, 8, 1, 1200);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_RESHUFFLE_INTERVAL_TICKS, 50, 1, 1200);

		int minTargetPlayers = GlitchSettingsHelper.getInt(entry.settings, MIN_TARGET_PLAYERS, 1);
		int maxTargetPlayers = Math.max(1, GlitchSettingsHelper.getInt(entry.settings, MAX_TARGET_PLAYERS, 4));
		boolean minTargetExpression = GlitchSettingsHelper.isExpression(entry.settings, MIN_TARGET_PLAYERS);
		boolean maxTargetExpression = GlitchSettingsHelper.isExpression(entry.settings, MAX_TARGET_PLAYERS);
		if (!minTargetExpression && !maxTargetExpression && maxTargetPlayers < minTargetPlayers) {
			entry.settings.addProperty(MAX_TARGET_PLAYERS, minTargetPlayers);
			changed = true;
		}

		double minPercent = GlitchSettingsHelper.getDouble(entry.settings, MIN_SHUFFLED_PERCENT, 8.0D);
		double maxPercent = GlitchSettingsHelper.getDouble(entry.settings, MAX_SHUFFLED_PERCENT, 75.0D);
		if (maxPercent < minPercent) {
			entry.settings.addProperty(MAX_SHUFFLED_PERCENT, minPercent);
			changed = true;
		}

		int minDuration = GlitchSettingsHelper.getInt(entry.settings, MIN_DURATION_SECONDS, 3);
		int maxDuration = GlitchSettingsHelper.getInt(entry.settings, MAX_DURATION_SECONDS, 20);
		if (maxDuration < minDuration) {
			entry.settings.addProperty(MAX_DURATION_SECONDS, minDuration);
			changed = true;
		}

		int minInterval = GlitchSettingsHelper.getInt(entry.settings, MIN_RESHUFFLE_INTERVAL_TICKS, 8);
		int maxInterval = GlitchSettingsHelper.getInt(entry.settings, MAX_RESHUFFLE_INTERVAL_TICKS, 50);
		if (maxInterval < minInterval) {
			entry.settings.addProperty(MAX_RESHUFFLE_INTERVAL_TICKS, minInterval);
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

		int minTargetPlayers = GlitchSettingsHelper.getInt(settings, MIN_TARGET_PLAYERS, 1);
		int maxTargetPlayers = Math.max(1, GlitchSettingsHelper.getInt(settings, MAX_TARGET_PLAYERS, 4));
		int targetPlayers = interpolateInt(minTargetPlayers, maxTargetPlayers, instability);
		targetPlayers = Math.max(1, Math.min(players.size(), targetPlayers));

		double minShuffledPercent = GlitchSettingsHelper.getDouble(settings, MIN_SHUFFLED_PERCENT, 8.0D);
		double maxShuffledPercent = GlitchSettingsHelper.getDouble(settings, MAX_SHUFFLED_PERCENT, 75.0D);
		double shuffledPercentPriority = GlitchSettingsHelper.getDouble(settings, SHUFFLED_PERCENT_PRIORITY, 2.0D);
		double shuffledPercent = pickRandomShuffledPercent(random, minShuffledPercent, maxShuffledPercent, instability, shuffledPercentPriority);

		int minDurationSeconds = GlitchSettingsHelper.getInt(settings, MIN_DURATION_SECONDS, 3);
		int maxDurationSeconds = GlitchSettingsHelper.getInt(settings, MAX_DURATION_SECONDS, 20);

		int minReshuffleInterval = GlitchSettingsHelper.getInt(settings, MIN_RESHUFFLE_INTERVAL_TICKS, 8);
		int maxReshuffleInterval = GlitchSettingsHelper.getInt(settings, MAX_RESHUFFLE_INTERVAL_TICKS, 50);
		int reshuffleIntervalTicks = interpolateInt(maxReshuffleInterval, minReshuffleInterval, instability);

		long nowTick = server.overworld().getGameTime();

		shuffle(players, random);

		boolean appliedAny = false;
		for (int i = 0; i < targetPlayers; i++) {
			ServerPlayer player = players.get(i);
			if (activateForPlayer(
					server,
					player,
					random,
					shuffledPercent,
					reshuffleIntervalTicks,
					nowTick,
					minDurationSeconds,
					maxDurationSeconds,
					minReshuffleInterval,
					maxReshuffleInterval,
					minShuffledPercent,
					maxShuffledPercent,
					shuffledPercentPriority,
					entry.maxStabilityPercent,
					entry.maxStabilityPercent + 10.0D
			)) {
				appliedAny = true;
			}
		}

		return appliedAny;
	}

	public static void tickActiveStates(MinecraftServer server) {
		if (server == null || ACTIVE_STATES.isEmpty()) {
			return;
		}

		long nowTick = server.overworld().getGameTime();
		RandomSource random = server.overworld().getRandom();
		double stabilityPercent = ServerStabilitySystem.getStabilityPercent();
		Iterator<Map.Entry<UUID, ActiveShuffleState>> iterator = ACTIVE_STATES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, ActiveShuffleState> stateEntry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(stateEntry.getKey());
			ActiveShuffleState state = stateEntry.getValue();
			if (player == null) {
				restoreGlitchedModelsForToken(server, state.shuffleToken);
				iterator.remove();
				continue;
			}
			if (ServerBackroomsSystem.isInBackrooms(player)) {
				restoreGlitchedModelsForToken(server, state.shuffleToken);
				restoreFullInventoryVisuals(player);
				iterator.remove();
				continue;
			}

			if (ServerUpgradeUiSystem.isUpgradeMenu(player.containerMenu)) {
				if (!state.pausedForUpgradeMenu) {
					restoreGlitchedModelsForToken(server, state.shuffleToken);
					restoreFullInventoryVisuals(player);
					state.pausedForUpgradeMenu = true;
				}
				state.nextResendAtTick = nowTick + RESEND_INTERVAL_TICKS;
				continue;
			}

			if (state.pausedForUpgradeMenu) {
				state.pausedForUpgradeMenu = false;

				double shuffledPercent = pickRandomShuffledPercent(
						random,
						state.minShuffledPercent,
						state.maxShuffledPercent,
						getStabilityScaledInstability(stabilityPercent, state.maxStabilityPercent),
						state.shuffledPercentPriority
				);
				Map<Integer, Identifier> resumedModels = buildShuffledModelMap(player, random, shuffledPercent);
				if (resumedModels.isEmpty()) {
					restoreFullInventoryVisuals(player);
					iterator.remove();
					continue;
				}

				state.shuffleToken = UUID.randomUUID();
				applyModelMap(player, resumedModels, state.shuffleToken);
				state.modelBySlot = resumedModels;
				state.inventoryModelsSnapshot = captureInventoryModels(player.getInventory());
				state.shuffledPercent = shuffledPercent;
				state.lastReshuffleAtTick = nowTick;
				state.nextResendAtTick = nowTick + RESEND_INTERVAL_TICKS;
				continue;
			}

			if (stabilityPercent > state.expireAboveStabilityPercent) {
				restoreGlitchedModelsForToken(server, state.shuffleToken);
				restoreFullInventoryVisuals(player);
				iterator.remove();
				continue;
			}

			double currentInstability = getStabilityScaledInstability(stabilityPercent, state.maxStabilityPercent);
			int currentDurationSeconds = interpolateInt(state.minDurationSeconds, state.maxDurationSeconds, currentInstability);
			long currentDurationTicks = Math.max(1L, currentDurationSeconds) * 20L;
			if (nowTick >= state.startedAtTick + currentDurationTicks) {
				restoreGlitchedModelsForToken(server, state.shuffleToken);
				restoreFullInventoryVisuals(player);
				iterator.remove();
				continue;
			}

			state.reshuffleIntervalTicks = interpolateInt(state.maxReshuffleIntervalTicks, state.minReshuffleIntervalTicks, currentInstability);
			Map<Integer, Identifier> currentInventoryModels = captureInventoryModels(player.getInventory());
			boolean inventoryChanged = !currentInventoryModels.equals(state.inventoryModelsSnapshot);

			if (inventoryChanged || nowTick >= state.lastReshuffleAtTick + state.reshuffleIntervalTicks) {
				double shuffledPercent = pickRandomShuffledPercent(
						random,
						state.minShuffledPercent,
						state.maxShuffledPercent,
						currentInstability,
						state.shuffledPercentPriority
				);
				Map<Integer, Identifier> newModels = buildShuffledModelMap(player, random, shuffledPercent);
				if (newModels.isEmpty()) {
					restoreGlitchedModelsForToken(server, state.shuffleToken);
					restoreFullInventoryVisuals(player);
					iterator.remove();
					continue;
				}

				Set<Integer> toRestore = new HashSet<>(state.modelBySlot.keySet());
				toRestore.removeAll(newModels.keySet());
				if (!toRestore.isEmpty()) {
					restoreModelsInSlots(player, toRestore, state.shuffleToken);
					restoreRealVisuals(player, toRestore);
				}

				applyModelMap(player, newModels, state.shuffleToken);
				state.modelBySlot = newModels;
				state.inventoryModelsSnapshot = captureInventoryModels(player.getInventory());
				state.shuffledPercent = shuffledPercent;
				state.lastReshuffleAtTick = nowTick;
				state.nextResendAtTick = nowTick + RESEND_INTERVAL_TICKS;
				continue;
			}

			if (nowTick >= state.nextResendAtTick) {
				applyModelMap(player, state.modelBySlot, state.shuffleToken);
				state.nextResendAtTick = nowTick + RESEND_INTERVAL_TICKS;
			}
		}
	}

	private static boolean activateForPlayer(
			MinecraftServer server,
			ServerPlayer player,
			RandomSource random,
			double shuffledPercent,
			int reshuffleIntervalTicks,
			long nowTick,
			int minDurationSeconds,
			int maxDurationSeconds,
			int minReshuffleIntervalTicks,
			int maxReshuffleIntervalTicks,
			double minShuffledPercent,
			double maxShuffledPercent,
			double shuffledPercentPriority,
			double maxStabilityPercent,
			double expireAboveStabilityPercent
	) {
		if (player == null || ServerUpgradeUiSystem.isUpgradeMenuOpen(player)) {
			return false;
		}

		clearState(server, player);

		Map<Integer, Identifier> modelBySlot = buildShuffledModelMap(player, random, shuffledPercent);
		if (modelBySlot.isEmpty()) {
			return false;
		}

		UUID shuffleToken = UUID.randomUUID();
		applyModelMap(player, modelBySlot, shuffleToken);
		ACTIVE_STATES.put(player.getUUID(), new ActiveShuffleState(
				nowTick,
				nowTick,
				nowTick + RESEND_INTERVAL_TICKS,
				Math.max(1, reshuffleIntervalTicks),
				shuffledPercent,
				minDurationSeconds,
				maxDurationSeconds,
				Math.max(1, minReshuffleIntervalTicks),
				Math.max(1, maxReshuffleIntervalTicks),
				minShuffledPercent,
				maxShuffledPercent,
				shuffledPercentPriority,
				maxStabilityPercent,
				expireAboveStabilityPercent,
				shuffleToken,
				captureInventoryModels(player.getInventory()),
				modelBySlot
		));
		return true;
	}

	public static void onUpgradeMenuOpened(ServerPlayer player) {
		if (player == null) {
			return;
		}

		ActiveShuffleState state = ACTIVE_STATES.get(player.getUUID());
		if (state == null || state.pausedForUpgradeMenu) {
			return;
		}

		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}

		restoreGlitchedModelsForToken(server, state.shuffleToken);
		restoreFullInventoryVisuals(player);
		state.pausedForUpgradeMenu = true;
	}

	private static void clearState(MinecraftServer server, ServerPlayer player) {
		if (player == null) {
			return;
		}

		ActiveShuffleState oldState = ACTIVE_STATES.remove(player.getUUID());
		if (oldState == null) {
			return;
		}

		restoreGlitchedModelsForToken(server, oldState.shuffleToken);
		restoreFullInventoryVisuals(player);
	}

	private static Map<Integer, Identifier> buildShuffledModelMap(ServerPlayer player, RandomSource random, double shuffledPercent) {
		Inventory inventory = player.getInventory();
		List<Integer> nonEmptySlots = new ArrayList<>();
		for (int slot = 0; slot < PLAYER_INVENTORY_SLOT_COUNT; slot++) {
			if (!inventory.getItem(slot).isEmpty()) {
				nonEmptySlots.add(slot);
			}
		}

		if (nonEmptySlots.size() < 2 || shuffledPercent <= 0.0D) {
			return Collections.emptyMap();
		}

		int slotsToShuffle = (int) Math.round(nonEmptySlots.size() * (shuffledPercent / 100.0D));
		slotsToShuffle = Math.min(nonEmptySlots.size(), Math.max(0, slotsToShuffle));
		if (slotsToShuffle > 0 && slotsToShuffle < 2) {
			slotsToShuffle = 2;
		}
		if (slotsToShuffle < 2) {
			return Collections.emptyMap();
		}

		shuffle(nonEmptySlots, random);
		List<Integer> selectedSlots = new ArrayList<>(nonEmptySlots.subList(0, slotsToShuffle));
		List<Identifier> sourceModels = new ArrayList<>(selectedSlots.size());
		for (Integer slot : selectedSlots) {
			sourceModels.add(resolveCurrentModelId(inventory.getItem(slot)));
		}

		List<Identifier> shuffledModels = new ArrayList<>(sourceModels);
		shuffle(shuffledModels, random);
		if (sameOrder(sourceModels, shuffledModels) && shuffledModels.size() > 1) {
			Collections.rotate(shuffledModels, 1);
		}

		Map<Integer, Identifier> result = new LinkedHashMap<>();
		boolean changedAny = false;
		for (int i = 0; i < selectedSlots.size(); i++) {
			Identifier sourceModel = sourceModels.get(i);
			Identifier shuffledModel = shuffledModels.get(i);
			if (!sourceModel.equals(shuffledModel)) {
				changedAny = true;
			}
			result.put(selectedSlots.get(i), shuffledModel);
		}

		return changedAny ? result : Collections.emptyMap();
	}

	private static void applyModelMap(ServerPlayer player, Map<Integer, Identifier> modelBySlot, UUID shuffleToken) {
		if (modelBySlot.isEmpty()) {
			return;
		}

		Inventory inventory = player.getInventory();
		PacketContext.NotNullWithPlayer context = PacketContext.create(player);
		Map<Integer, ItemStack> fakeVisualBySlot = new LinkedHashMap<>();
		for (Map.Entry<Integer, Identifier> mapEntry : modelBySlot.entrySet()) {
			int slot = mapEntry.getKey();
			ItemStack realStack = inventory.getItem(slot);
			if (realStack.isEmpty()) {
				continue;
			}
			applyModelToRealStack(realStack, mapEntry.getValue(), shuffleToken);

			ItemStack clientStack = toClientVisualStack(realStack, context);
			if (clientStack.isEmpty()) {
				continue;
			}

			clientStack.set(DataComponents.ITEM_MODEL, mapEntry.getValue());
			fakeVisualBySlot.put(slot, clientStack);
		}

		if (fakeVisualBySlot.isEmpty()) {
			return;
		}

		sendVisualOverridesForMenu(player, player.inventoryMenu, fakeVisualBySlot, null);
		if (player.containerMenu != player.inventoryMenu) {
			sendVisualOverridesForMenu(player, player.containerMenu, fakeVisualBySlot, null);
		}
	}

	private static void restoreRealVisuals(ServerPlayer player, Set<Integer> slots) {
		if (slots.isEmpty()) {
			return;
		}

		Map<Integer, ItemStack> realVisualBySlot = new LinkedHashMap<>();
		for (Integer slot : slots) {
			realVisualBySlot.put(slot, player.getInventory().getItem(slot).copy());
		}

		sendVisualOverridesForMenu(player, player.inventoryMenu, realVisualBySlot, slots);
		if (player.containerMenu != player.inventoryMenu) {
			sendVisualOverridesForMenu(player, player.containerMenu, realVisualBySlot, slots);
		}
	}

	private static void restoreFullInventoryVisuals(ServerPlayer player) {
		restoreRealVisuals(player, FULL_INVENTORY_SLOTS);
	}

	private static void restoreModelsInSlots(ServerPlayer player, Set<Integer> slots, UUID shuffleToken) {
		Inventory inventory = player.getInventory();
		for (Integer slot : slots) {
			restoreModelFromRealStack(inventory.getItem(slot), shuffleToken);
		}
	}

	private static void restoreGlitchedModelsForToken(MinecraftServer server, UUID shuffleToken) {
		if (server == null || shuffleToken == null) {
			return;
		}

		for (ServerPlayer player : GlitchPlayers.eligiblePlayers(server)) {
			boolean inventoryChanged = false;
			Inventory inventory = player.getInventory();
			for (int slot = 0; slot < PLAYER_INVENTORY_SLOT_COUNT; slot++) {
				if (restoreModelFromRealStack(inventory.getItem(slot), shuffleToken)) {
					inventoryChanged = true;
				}
			}

			ItemStack carried = player.containerMenu.getCarried();
			if (restoreModelFromRealStack(carried, shuffleToken)) {
				player.containerMenu.setCarried(carried);
				inventoryChanged = true;
			}

			if (inventoryChanged) {
				restoreFullInventoryVisuals(player);
			}
		}

		for (var level : server.getAllLevels()) {
			level.getChunkSource().chunkMap.forEachReadyToSendChunk(chunk -> {
				for (var blockEntity : chunk.getBlockEntities().values()) {
					if (blockEntity instanceof Container container) {
						restoreContainerModels(container, shuffleToken);
					}
				}
			});

			for (var entity : level.getAllEntities()) {
				if (!(entity instanceof ItemEntity itemEntity)) {
					if (entity instanceof Container container && !(entity instanceof ServerPlayer)) {
						restoreContainerModels(container, shuffleToken);
					}
					continue;
				}

				ItemStack stack = itemEntity.getItem();
				if (!restoreModelFromRealStack(stack, shuffleToken)) {
					continue;
				}

				itemEntity.setItem(stack.copy());
			}
		}
	}

	private static void sendVisualOverridesForMenu(
			ServerPlayer player,
			AbstractContainerMenu menu,
			Map<Integer, ItemStack> visualBySlot,
			Set<Integer> allowedSlots
	) {
		if (menu == null || ServerUpgradeUiSystem.isUpgradeMenu(menu)) {
			return;
		}

		PacketContext.NotNullWithPlayer context = PacketContext.create(player);
		Inventory inventory = player.getInventory();
		int stateId = menu.incrementStateId();
		for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
			Slot slot = menu.getSlot(menuSlot);
			if (slot.container != inventory) {
				continue;
			}

			int inventorySlot = slot.getContainerSlot();
			if (allowedSlots != null && !allowedSlots.contains(inventorySlot)) {
				continue;
			}

			ItemStack visual = visualBySlot.get(inventorySlot);
			if (visual == null) {
				continue;
			}
			ItemStack clientVisual = toClientVisualStack(visual, context);
			if (clientVisual == null) {
				continue;
			}

			player.connection.send(new ClientboundContainerSetSlotPacket(
					menu.containerId,
					stateId,
					menuSlot,
					clientVisual
			));
		}
	}

	private static List<ServerPlayer> collectEligiblePlayers(MinecraftServer server) {
		List<ServerPlayer> result = new ArrayList<>();
		for (ServerPlayer player : GlitchPlayers.eligiblePlayers(server)) {
			if (player.isSpectator()
					|| !player.isAlive()
					|| ServerBackroomsSystem.isInBackrooms(player)
					|| ServerUpgradeUiSystem.isUpgradeMenuOpen(player)) {
				continue;
			}
			result.add(player);
		}
		return result;
	}

	private static Identifier resolveCurrentModelId(ItemStack stack) {
		Identifier explicitModel = stack.get(DataComponents.ITEM_MODEL);
		return explicitModel == null ? BuiltInRegistries.ITEM.getKey(stack.getItem()) : explicitModel;
	}

	private static boolean sameOrder(List<Identifier> first, List<Identifier> second) {
		if (first.size() != second.size()) {
			return false;
		}
		for (int i = 0; i < first.size(); i++) {
			if (!first.get(i).equals(second.get(i))) {
				return false;
			}
		}
		return true;
	}

	private static void applyModelToRealStack(ItemStack stack, Identifier shuffledModel, UUID shuffleToken) {
		if (stack == null || stack.isEmpty() || shuffledModel == null || shuffleToken == null) {
			return;
		}

		String token = shuffleToken.toString();
		ShuffleStackMetadata metadata = readShuffleMetadata(stack);
		if (metadata != null && !token.equals(metadata.token)) {
			restoreModelFromMetadata(stack, metadata);
			metadata = null;
		}

		Identifier currentModel = stack.get(DataComponents.ITEM_MODEL);
		if (metadata == null || !token.equals(metadata.token)) {
			boolean hasOriginalModel = currentModel != null;
			String originalModel = hasOriginalModel ? currentModel.toString() : "";
			CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
				CompoundTag glitchTag = tag.getCompoundOrEmpty(SHUFFLE_META_TAG);
				glitchTag.putString(SHUFFLE_META_TOKEN, token);
				glitchTag.putBoolean(SHUFFLE_META_HAS_ORIGINAL_MODEL, hasOriginalModel);
				if (hasOriginalModel) {
					glitchTag.putString(SHUFFLE_META_ORIGINAL_MODEL, originalModel);
				} else {
					glitchTag.remove(SHUFFLE_META_ORIGINAL_MODEL);
				}
				tag.put(SHUFFLE_META_TAG, glitchTag);
			});
		}

		stack.set(DataComponents.ITEM_MODEL, shuffledModel);
	}

	private static boolean restoreModelFromRealStack(ItemStack stack, UUID shuffleToken) {
		if (stack == null || stack.isEmpty() || shuffleToken == null) {
			return false;
		}

		ShuffleStackMetadata metadata = readShuffleMetadata(stack);
		if (metadata == null || !shuffleToken.toString().equals(metadata.token)) {
			return false;
		}

		restoreModelFromMetadata(stack, metadata);
		return true;
	}

	private static ShuffleStackMetadata readShuffleMetadata(ItemStack stack) {
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData == null || customData.isEmpty()) {
			return null;
		}

		CompoundTag rootTag = customData.copyTag();
		if (!rootTag.contains(SHUFFLE_META_TAG)) {
			return null;
		}

		CompoundTag glitchTag = rootTag.getCompoundOrEmpty(SHUFFLE_META_TAG);
		String token = glitchTag.getStringOr(SHUFFLE_META_TOKEN, "");
		if (token.isEmpty()) {
			return null;
		}

		boolean hasOriginalModel = glitchTag.getBooleanOr(SHUFFLE_META_HAS_ORIGINAL_MODEL, false);
		String originalModel = glitchTag.getStringOr(SHUFFLE_META_ORIGINAL_MODEL, "");
		return new ShuffleStackMetadata(token, hasOriginalModel, originalModel);
	}

	private static void restoreModelFromMetadata(ItemStack stack, ShuffleStackMetadata metadata) {
		if (metadata == null) {
			return;
		}

		if (metadata.hasOriginalModel) {
			Identifier originalModel = Identifier.tryParse(metadata.originalModel);
			if (originalModel != null) {
				stack.set(DataComponents.ITEM_MODEL, originalModel);
			} else {
				stack.remove(DataComponents.ITEM_MODEL);
			}
		} else {
			stack.remove(DataComponents.ITEM_MODEL);
		}

		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(SHUFFLE_META_TAG));
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData != null && customData.isEmpty()) {
			stack.remove(DataComponents.CUSTOM_DATA);
		}
	}

	private static boolean restoreContainerModels(Container container, UUID shuffleToken) {
		boolean changed = false;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (!restoreModelFromRealStack(stack, shuffleToken)) {
				continue;
			}

			container.setItem(slot, stack);
			changed = true;
		}

		if (changed) {
			container.setChanged();
		}
		return changed;
	}

	private static Map<Integer, Identifier> captureInventoryModels(Inventory inventory) {
		Map<Integer, Identifier> snapshot = new HashMap<>();
		for (int slot = 0; slot < PLAYER_INVENTORY_SLOT_COUNT; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			snapshot.put(slot, resolveCurrentModelId(stack));
		}
		return snapshot;
	}

	private static int interpolateInt(int min, int max, double factor) {
		if (max <= min) {
			return min;
		}
		return min + (int) Math.round((max - min) * clamp01(factor));
	}

	private static double interpolateDouble(double min, double max, double factor) {
		if (max <= min) {
			return min;
		}
		return min + ((max - min) * clamp01(factor));
	}

	private static double getRangeInstabilityFactor(double stabilityPercent, double minStabilityPercent, double maxStabilityPercent) {
		double range = maxStabilityPercent - minStabilityPercent;
		if (range <= 1.0E-9D) {
			return 1.0D;
		}
		double normalized = (stabilityPercent - minStabilityPercent) / range;
		return 1.0D - clamp01(normalized);
	}

	private static double getStabilityScaledInstability(double stabilityPercent, double maxStabilityPercent) {
		if (maxStabilityPercent <= 1.0E-9D) {
			return 1.0D;
		}

		double clampedStability = Math.max(0.0D, Math.min(maxStabilityPercent, stabilityPercent));
		return 1.0D - (clampedStability / maxStabilityPercent);
	}

	private static double pickRandomShuffledPercent(
			RandomSource random,
			double minShuffledPercent,
			double maxShuffledPercent,
			double instabilityFactor,
			double shuffledPercentPriority
	) {
		double dynamicMax = interpolateDouble(minShuffledPercent, maxShuffledPercent, instabilityFactor);
		double lower = Math.min(minShuffledPercent, dynamicMax);
		double upper = Math.max(minShuffledPercent, dynamicMax);
		if (upper - lower <= 1.0E-9D) {
			return lower;
		}

		double clampedPriority = Math.max(0.1D, shuffledPercentPriority);
		double weightedRoll = 1.0D - Math.pow(1.0D - random.nextDouble(), clampedPriority);
		return lower + (weightedRoll * (upper - lower));
	}

	private static void shuffle(List<?> list, RandomSource random) {
		for (int i = list.size() - 1; i > 0; i--) {
			int swapWith = random.nextInt(i + 1);
			Collections.swap(list, i, swapWith);
		}
	}

	private static Set<Integer> buildFullInventorySlotSet() {
		Set<Integer> slots = new HashSet<>();
		for (int i = 0; i < PLAYER_INVENTORY_SLOT_COUNT; i++) {
			slots.add(i);
		}
		return Set.copyOf(slots);
	}

	private static ItemStack toClientVisualStack(ItemStack stack, PacketContext.NotNullWithPlayer context) {
		if (stack == null || stack.isEmpty()) {
			return ItemStack.EMPTY;
		}

		ItemStack clientStack = PolymerItemUtils.getClientItemStack(stack, context);
		return clientStack.isEmpty() ? ItemStack.EMPTY : clientStack.copy();
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	private static final class ShuffleStackMetadata {
		private final String token;
		private final boolean hasOriginalModel;
		private final String originalModel;

		private ShuffleStackMetadata(String token, boolean hasOriginalModel, String originalModel) {
			this.token = token;
			this.hasOriginalModel = hasOriginalModel;
			this.originalModel = originalModel;
		}
	}

	private static final class ActiveShuffleState {
		private long startedAtTick;
		private long lastReshuffleAtTick;
		private long nextResendAtTick;
		private int reshuffleIntervalTicks;
		private double shuffledPercent;
		private int minDurationSeconds;
		private int maxDurationSeconds;
		private int minReshuffleIntervalTicks;
		private int maxReshuffleIntervalTicks;
		private double minShuffledPercent;
		private double maxShuffledPercent;
		private double shuffledPercentPriority;
		private double maxStabilityPercent;
		private double expireAboveStabilityPercent;
		private UUID shuffleToken;
		private Map<Integer, Identifier> inventoryModelsSnapshot;
		private Map<Integer, Identifier> modelBySlot;
		private boolean pausedForUpgradeMenu;

		private ActiveShuffleState(
				long startedAtTick,
				long lastReshuffleAtTick,
				long nextResendAtTick,
				int reshuffleIntervalTicks,
				double shuffledPercent,
				int minDurationSeconds,
				int maxDurationSeconds,
				int minReshuffleIntervalTicks,
				int maxReshuffleIntervalTicks,
				double minShuffledPercent,
				double maxShuffledPercent,
				double shuffledPercentPriority,
				double maxStabilityPercent,
				double expireAboveStabilityPercent,
				UUID shuffleToken,
				Map<Integer, Identifier> inventoryModelsSnapshot,
				Map<Integer, Identifier> modelBySlot
		) {
			this.startedAtTick = startedAtTick;
			this.lastReshuffleAtTick = lastReshuffleAtTick;
			this.nextResendAtTick = nextResendAtTick;
			this.reshuffleIntervalTicks = reshuffleIntervalTicks;
			this.shuffledPercent = shuffledPercent;
			this.minDurationSeconds = minDurationSeconds;
			this.maxDurationSeconds = maxDurationSeconds;
			this.minReshuffleIntervalTicks = minReshuffleIntervalTicks;
			this.maxReshuffleIntervalTicks = maxReshuffleIntervalTicks;
			this.minShuffledPercent = minShuffledPercent;
			this.maxShuffledPercent = maxShuffledPercent;
			this.shuffledPercentPriority = shuffledPercentPriority;
			this.maxStabilityPercent = maxStabilityPercent;
			this.expireAboveStabilityPercent = expireAboveStabilityPercent;
			this.shuffleToken = shuffleToken;
			this.inventoryModelsSnapshot = inventoryModelsSnapshot;
			this.modelBySlot = modelBySlot;
			this.pausedForUpgradeMenu = false;
		}
	}
}
