package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lostglade.Lg2;
import com.lostglade.block.CameraBlock;
import com.lostglade.block.MicrophoneBlock;
import com.lostglade.block.ModBlocks;
import com.lostglade.item.ModItems;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class BluetoothLinkSystem {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "lg2-bluetooth-links.json";
	private static final String IT_BLUETOOTH_ADAPTER = "it_bluetooth_adapter";
	private static final long ACTIONBAR_REFRESH_INTERVAL_TICKS = 10L;

	private static final Map<Endpoint, LinkedHashSet<Endpoint>> LINKS = new LinkedHashMap<>();
	private static final Map<UUID, Endpoint> SELECTED_ENDPOINTS = new HashMap<>();
	private static final Map<UUID, Long> NEXT_ACTIONBAR_REFRESH_TICKS = new HashMap<>();
	private static final Map<UUID, Endpoint> VISIBLE_ENDPOINTS = new HashMap<>();
	private static final Map<UUID, LastInteraction> LAST_INTERACTIONS = new HashMap<>();
	private static boolean loaded = false;
	private static boolean dirty = false;

	private BluetoothLinkSystem() {
	}

	public static void register() {
		loaded = false;
		dirty = false;
		LINKS.clear();
		SELECTED_ENDPOINTS.clear();
		NEXT_ACTIONBAR_REFRESH_TICKS.clear();
		VISIBLE_ENDPOINTS.clear();
		LAST_INTERACTIONS.clear();
		ServerLifecycleEvents.SERVER_STARTED.register(BluetoothLinkSystem::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save(server);
			SELECTED_ENDPOINTS.clear();
			NEXT_ACTIONBAR_REFRESH_TICKS.clear();
			VISIBLE_ENDPOINTS.clear();
			LAST_INTERACTIONS.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(BluetoothLinkSystem::tickSelectedEndpoints);
		UseBlockCallback.EVENT.register(BluetoothLinkSystem::onUseBlock);
		UseEntityCallback.EVENT.register(BluetoothLinkSystem::onUseEntity);
	}

	public static Endpoint resolveBlockEndpoint(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null || !level.hasChunkAt(pos)) {
			return null;
		}
		BlockState state = level.getBlockState(pos);
		if (state.is(ModBlocks.SPEAKER)) {
			return Endpoint.block(level.dimension(), EndpointType.SPEAKER, pos);
		}
		if (state.is(ModBlocks.MICROPHONE)) {
			return Endpoint.block(level.dimension(), EndpointType.MICROPHONE, pos);
		}
		if (state.is(ModBlocks.CAMERA)) {
			// A piston-moved camera retains the endpoint that was paired before it
			// moved.  Physical clicks must therefore resolve back to that identity.
			return Endpoint.block(level.dimension(), EndpointType.CAMERA,
					CameraRelocationSystem.logicalCameraPosition(level, pos));
		}
		return null;
	}

	public static Endpoint screenEndpoint(ResourceKey<Level> dimension, BlockPos pos, Direction facing) {
		return screenEndpoint(dimension, pos, facing, null);
	}

	public static Endpoint screenEndpoint(ResourceKey<Level> dimension, BlockPos pos, Direction facing, String screenId) {
		if (dimension == null || pos == null || facing == null) {
			return null;
		}
		return Endpoint.screen(dimension, pos, facing, screenId);
	}

	public static Endpoint droneEndpoint(ResourceKey<Level> dimension, BlockPos pos, UUID droneUuid) {
		if (dimension == null || pos == null || droneUuid == null) {
			return null;
		}
		return Endpoint.drone(dimension, pos, droneUuid);
	}

	/** Resolves a persisted endpoint even when its physical device is temporarily
	 * represented by a moving rocket rather than a world block. */
	public static Endpoint mountedBlockEndpoint(ResourceKey<Level> dimension, EndpointType type, BlockPos pos) {
		if (dimension == null || type == null || pos == null
				|| type == EndpointType.SCREEN || type == EndpointType.DRONE) {
			return null;
		}
		return Endpoint.block(dimension, type, pos);
	}

	public static boolean areLinked(Endpoint first, Endpoint second) {
		if (first == null || second == null) {
			return false;
		}
		ensureLoaded(null);
		LinkedHashSet<Endpoint> linked = LINKS.get(first);
		return linked != null && linked.contains(second);
	}

	public static List<Endpoint> linkedEndpoints(Endpoint endpoint) {
		if (endpoint == null) {
			return List.of();
		}
		ensureLoaded(null);
		LinkedHashSet<Endpoint> linked = LINKS.get(endpoint);
		if (linked == null || linked.isEmpty()) {
			return List.of();
		}
		return List.copyOf(linked);
	}

	static void refreshDroneEndpoint(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos, UUID droneUuid) {
		Endpoint endpoint = droneEndpoint(dimension, pos, droneUuid);
		if (server == null || endpoint == null) {
			return;
		}
		ensureLoaded(server);
		if (!replaceEndpointMetadata(endpoint)) {
			return;
		}
		updateSelectedEndpoints(endpoint, endpoint);
		dirty = true;
	}

	public static boolean unlinkEndpoints(MinecraftServer server, Endpoint first, Endpoint second) {
		if (first == null || second == null) {
			return false;
		}
		ensureLoaded(server);
		boolean removed = unlink(first, second);
		if (!removed) {
			return false;
		}
		notifyEndpointChanged(server, first);
		notifyEndpointChanged(server, second);
		return true;
	}

	public static void removeScreenEndpoint(ServerLevel level, BlockPos pos, Direction facing) {
		removeEndpoint(level, screenEndpoint(level == null ? null : level.dimension(), pos, facing));
	}

	public static void removeScreenEndpoint(ServerLevel level, Endpoint endpoint) {
		removeEndpoint(level, endpoint);
	}

	public static void removeBlockEndpoint(ServerLevel level, EndpointType type, BlockPos pos) {
		if (type == null) {
			return;
		}
		if (type == EndpointType.CAMERA && RocketLaunchEventSystem.isLaunchedMountedDevice(level, pos)) {
			// A launched camera retains this original endpoint identity.  Several
			// generic block-removal paths can arrive here after the camera block has
			// become a moving rocket anchor, so guard the endpoint at the Bluetooth
			// layer as well as in CameraBlock itself.
			return;
		}
		removeEndpoint(level, Endpoint.block(level == null ? null : level.dimension(), type, pos));
	}

	/** Removes an endpoint retained for an in-flight rocket after that rocket is
	 * destroyed or has left the world.  This deliberately bypasses the ordinary
	 * in-flight camera guard. */
	public static void removeMountedBlockEndpoint(ServerLevel level, EndpointType type, BlockPos pos) {
		if (type == null) {
			return;
		}
		removeEndpoint(level, Endpoint.block(level == null ? null : level.dimension(), type, pos));
	}

	public static void removeDroneEndpoint(ServerLevel level, UUID droneUuid, BlockPos pos) {
		removeEndpoint(level, droneEndpoint(level == null ? null : level.dimension(), pos == null ? BlockPos.ZERO : pos, droneUuid));
	}

	public static void removeDroneEndpoint(ServerLevel level, UUID droneUuid, BlockPos pos, Vec3 adapterDropPosition) {
		removeEndpoint(level, droneEndpoint(level == null ? null : level.dimension(), pos == null ? BlockPos.ZERO : pos, droneUuid), adapterDropPosition);
	}

	public static void collapseScreenEndpoints(MinecraftServer server, Endpoint rootEndpoint, Iterable<Endpoint> legacyEndpoints) {
		if (rootEndpoint == null || rootEndpoint.type() != EndpointType.SCREEN || legacyEndpoints == null) {
			return;
		}
		ensureLoaded(server);
		boolean changed = false;
		for (Endpoint legacyEndpoint : legacyEndpoints) {
			if (legacyEndpoint == null || legacyEndpoint.equals(rootEndpoint) || legacyEndpoint.type() != EndpointType.SCREEN) {
				continue;
			}
			LinkedHashSet<Endpoint> movedLinks = LINKS.remove(legacyEndpoint);
			if (movedLinks == null || movedLinks.isEmpty()) {
				continue;
			}
			changed = true;
			updateSelectedEndpoints(legacyEndpoint, rootEndpoint);
			for (Endpoint linked : List.copyOf(movedLinks)) {
				LinkedHashSet<Endpoint> otherLinks = LINKS.get(linked);
				if (otherLinks != null) {
					otherLinks.remove(legacyEndpoint);
					if (otherLinks.isEmpty()) {
						LINKS.remove(linked);
					}
				}
				if (linked.equals(rootEndpoint) || !isLinkAllowed(rootEndpoint.type(), linked.type())) {
					continue;
				}
				LINKS.computeIfAbsent(rootEndpoint, ignored -> new LinkedHashSet<>()).add(linked);
				LINKS.computeIfAbsent(linked, ignored -> new LinkedHashSet<>()).add(rootEndpoint);
			}
		}
		if (changed) {
			dirty = true;
		}
	}

	private static void removeEndpoint(ServerLevel level, Endpoint endpoint) {
		removeEndpoint(level, endpoint, null);
	}

	private static void removeEndpoint(ServerLevel level, Endpoint endpoint, Vec3 adapterDropPosition) {
		if (endpoint == null) {
			return;
		}
		MinecraftServer server = level == null ? null : level.getServer();
		ensureLoaded(server);
		clearSelectedEndpoint(endpoint, server);
		LinkedHashSet<Endpoint> removedLinks = LINKS.remove(endpoint);
		if (removedLinks == null || removedLinks.isEmpty()) {
			return;
		}
		for (Endpoint linked : List.copyOf(removedLinks)) {
			LinkedHashSet<Endpoint> otherLinks = LINKS.get(linked);
			if (otherLinks != null) {
				otherLinks.remove(endpoint);
				if (otherLinks.isEmpty()) {
					LINKS.remove(linked);
				}
			}
			if (adapterDropPosition != null && level != null) {
				dropAdapterAtPosition(level, adapterDropPosition);
			} else {
				dropAdapterAtEndpoint(server, linked);
			}
			notifyEndpointChanged(server, linked);
		}
		dirty = true;
	}

	private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hitResult) {
		if (world.isClientSide()
				|| hand != InteractionHand.MAIN_HAND
				|| !(player instanceof ServerPlayer serverPlayer)
				|| !(world instanceof ServerLevel level)) {
			return InteractionResult.PASS;
		}
		if (!serverPlayer.getItemInHand(hand).is(ModItems.BLUETOOTH_ADAPTER)) {
			return InteractionResult.PASS;
		}
		if (!canUseBluetoothAdapter(serverPlayer, true)) {
			return InteractionResult.FAIL;
		}
		Endpoint endpoint = resolveBlockEndpoint(level, hitResult == null ? null : hitResult.getBlockPos());
		if (endpoint == null) {
			return InteractionResult.PASS;
		}
		handleEndpointClick(serverPlayer, endpoint);
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult onUseEntity(Player player, Level world, InteractionHand hand, Entity entity, EntityHitResult hitResult) {
		if (world.isClientSide()
				|| hand != InteractionHand.MAIN_HAND
				|| !(player instanceof ServerPlayer serverPlayer)
				|| !(world instanceof ServerLevel level)) {
			return InteractionResult.PASS;
		}
		if (!serverPlayer.getItemInHand(hand).is(ModItems.BLUETOOTH_ADAPTER)) {
			return InteractionResult.PASS;
		}
		if (!canUseBluetoothAdapter(serverPlayer, true)) {
			return InteractionResult.FAIL;
		}
		Endpoint endpoint = MonitorScreenSystem.resolveBluetoothScreenEndpoint(level, entity);
		if (endpoint == null) {
			endpoint = DroneSystem.resolveBluetoothDroneEndpoint(level, entity);
		}
		if (endpoint == null) {
			return InteractionResult.PASS;
		}
		handleEndpointClick(serverPlayer, endpoint);
		return InteractionResult.SUCCESS;
	}

	private static void handleEndpointClick(ServerPlayer player, Endpoint endpoint) {
		if (player == null || endpoint == null) {
			return;
		}
		MinecraftServer server = player.level() instanceof ServerLevel serverLevel ? serverLevel.getServer() : null;
		ensureLoaded(server);
		if (isDuplicateInteraction(player, endpoint)) {
			return;
		}
		Endpoint selected = SELECTED_ENDPOINTS.get(player.getUUID());
		if (selected == null) {
			setSelectedEndpoint(player, endpoint);
			return;
		}
		if (selected.equals(endpoint)) {
			clearSelectedEndpoint(player, false);
			Lg2Messages.actionBar(player, "message.lg2.bluetooth.selection_cleared");
			return;
		}
		if (!isLinkAllowed(selected.type(), endpoint.type())) {
			Lg2Messages.actionBar(player, ChatFormatting.RED, "message.lg2.bluetooth.link_not_allowed");
			return;
		}

		boolean removed = unlink(selected, endpoint);
		if (!removed) {
			link(selected, endpoint);
			consumeBluetoothAdapter(player);
		} else {
			dropAdapterAtEndpoint(server, endpoint);
		}
		clearSelectedEndpoint(player, false);
		notifyEndpointChanged(server, selected);
		notifyEndpointChanged(server, endpoint);
		Lg2Messages.actionBar(
				player,
				removed ? ChatFormatting.RED : ChatFormatting.GREEN,
				removed ? "message.lg2.bluetooth.unlinked" : "message.lg2.bluetooth.linked",
				endpointName(selected.type()),
				endpointName(endpoint.type())
		);
	}

	private static void link(Endpoint first, Endpoint second) {
		if (first == null || second == null || first.equals(second) || !isLinkAllowed(first.type(), second.type())) {
			return;
		}
		LINKS.computeIfAbsent(first, ignored -> new LinkedHashSet<>()).add(second);
		LINKS.computeIfAbsent(second, ignored -> new LinkedHashSet<>()).add(first);
		dirty = true;
	}

	private static boolean unlink(Endpoint first, Endpoint second) {
		boolean removed = false;
		LinkedHashSet<Endpoint> firstLinks = LINKS.get(first);
		if (firstLinks != null) {
			removed |= firstLinks.remove(second);
			if (firstLinks.isEmpty()) {
				LINKS.remove(first);
			}
		}
		LinkedHashSet<Endpoint> secondLinks = LINKS.get(second);
		if (secondLinks != null) {
			removed |= secondLinks.remove(first);
			if (secondLinks.isEmpty()) {
				LINKS.remove(second);
			}
		}
		if (removed) {
			dirty = true;
		}
		return removed;
	}

	private static boolean replaceEndpointMetadata(Endpoint endpoint) {
		if (endpoint == null) {
			return false;
		}
		Endpoint stored = findStoredEndpoint(endpoint);
		if (stored == null || sameEndpointMetadata(stored, endpoint)) {
			return false;
		}
		LinkedHashSet<Endpoint> linked = LINKS.remove(stored);
		if (linked != null) {
			LINKS.put(endpoint, linked);
		}
		for (LinkedHashSet<Endpoint> otherLinks : LINKS.values()) {
			if (otherLinks == null || !otherLinks.remove(stored)) {
				continue;
			}
			otherLinks.add(endpoint);
		}
		return true;
	}

	private static Endpoint findStoredEndpoint(Endpoint endpoint) {
		if (endpoint == null) {
			return null;
		}
		for (Endpoint candidate : LINKS.keySet()) {
			if (candidate != null && candidate.equals(endpoint)) {
				return candidate;
			}
		}
		return null;
	}

	private static boolean sameEndpointMetadata(Endpoint first, Endpoint second) {
		return Objects.equals(first.dimension(), second.dimension())
				&& first.type() == second.type()
				&& Objects.equals(first.pos(), second.pos())
				&& Objects.equals(first.facing(), second.facing())
				&& Objects.equals(first.screenId(), second.screenId())
				&& Objects.equals(first.deviceUuid(), second.deviceUuid());
	}

	private static void consumeBluetoothAdapter(ServerPlayer player) {
		if (player == null || player.getAbilities().instabuild) {
			return;
		}
		ItemStack stack = player.getMainHandItem();
		if (!stack.is(ModItems.BLUETOOTH_ADAPTER) || stack.isEmpty()) {
			return;
		}
		stack.shrink(1);
		syncPlayerInventory(player);
	}

	private static void returnBluetoothAdapterToPlayer(ServerPlayer player) {
		if (player == null || player.getAbilities().instabuild) {
			return;
		}
		ItemStack refund = new ItemStack(ModItems.BLUETOOTH_ADAPTER);
		boolean inserted = player.getInventory().add(refund);
		if (!inserted && !refund.isEmpty()) {
			ItemEntity dropped = player.drop(refund, false);
			if (dropped != null) {
				dropped.setPickUpDelay(0);
			}
		}
		syncPlayerInventory(player);
	}

	private static void dropAdapterAtEndpoint(MinecraftServer server, Endpoint endpoint) {
		if (server == null || endpoint == null) {
			return;
		}
		ServerLevel dropLevel = server.getLevel(endpoint.dimension());
		if (dropLevel == null) {
			return;
		}
		dropAdapterAtPosition(dropLevel, adapterDropPosition(endpoint));
	}

	private static void dropAdapterAtPosition(ServerLevel level, Vec3 dropPosition) {
		if (level == null || dropPosition == null) {
			return;
		}
		ItemEntity itemEntity = new ItemEntity(
				level,
				dropPosition.x,
				dropPosition.y,
				dropPosition.z,
				new ItemStack(ModItems.BLUETOOTH_ADAPTER)
		);
		itemEntity.setDefaultPickUpDelay();
		level.addFreshEntity(itemEntity);
	}

	private static Vec3 adapterDropPosition(Endpoint endpoint) {
		Vec3 center = Vec3.atCenterOf(endpoint.pos());
		if (endpoint.type() == EndpointType.SCREEN && endpoint.facing() != null) {
			double offset = 0.56D;
			return center.add(
					endpoint.facing().getStepX() * offset,
					endpoint.facing().getStepY() * offset,
					endpoint.facing().getStepZ() * offset
			);
		}
		return center.add(0.0D, 0.25D, 0.0D);
	}

	private static void syncPlayerInventory(ServerPlayer player) {
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

	private static void notifyEndpointChanged(MinecraftServer server, Endpoint endpoint) {
		if (server == null || endpoint == null) {
			return;
		}
		ServerLevel level = server.getLevel(endpoint.dimension());
		if (level == null) {
			return;
		}
		switch (endpoint.type()) {
			case SCREEN -> MonitorScreenSystem.onBluetoothScreenEndpointChanged(level, endpoint);
			case SPEAKER -> SpeakerSystem.onSpeakerStateChanged(level, endpoint.pos());
			case MICROPHONE -> MicrophoneSystem.onMicrophoneStateChanged(level, endpoint.pos());
			case CAMERA -> MonitorScreenSystem.onCameraNetworkChanged(level, endpoint.pos());
			case DRONE -> MonitorScreenSystem.onDroneNetworkChanged(server, endpoint);
		}
	}

	private static void showSelectionMessage(ServerPlayer player, Endpoint endpoint) {
		if (player == null || endpoint == null) {
			return;
		}
		Lg2Messages.actionBar(player, endpointName(endpoint.type()));
	}

	private static boolean canUseBluetoothAdapter(ServerPlayer player, boolean notify) {
		if (player == null || ServerUpgradeUiSystem.hasUpgrade(player, IT_BLUETOOTH_ADAPTER)) {
			return true;
		}
		if (notify) {
			String upgradeName = ServerUpgradeUiSystem.getUpgradeDisplayName(player, IT_BLUETOOTH_ADAPTER);
			Lg2Messages.actionBar(
					player,
					ChatFormatting.RED,
					"message.lg2.bluetooth.upgrade_locked",
					upgradeName == null || upgradeName.isBlank() ? "Bluetooth Adapter" : upgradeName
			);
		}
		return false;
	}

	private static MutableComponent endpointName(EndpointType type) {
		if (type == null) {
			return Lg2Messages.tr("message.lg2.bluetooth.endpoint.generic");
		}
		return switch (type) {
			case SCREEN -> Lg2Messages.tr("message.lg2.bluetooth.endpoint.screen");
			case SPEAKER -> Lg2Messages.tr("message.lg2.bluetooth.endpoint.speaker");
			case MICROPHONE -> Lg2Messages.tr("message.lg2.bluetooth.endpoint.microphone");
			case CAMERA -> Lg2Messages.tr("message.lg2.bluetooth.endpoint.camera");
			case DRONE -> Lg2Messages.tr("message.lg2.bluetooth.endpoint.drone");
		};
	}

	private static String pairKey(Endpoint first, Endpoint second) {
		String firstKey = endpointKey(first);
		String secondKey = endpointKey(second);
		return firstKey.compareTo(secondKey) <= 0 ? firstKey + "|" + secondKey : secondKey + "|" + firstKey;
	}

	private static boolean isLinkAllowed(EndpointType first, EndpointType second) {
		if (first == null || second == null || first == second) {
			return false;
		}
		return switch (first) {
			case SCREEN -> second == EndpointType.SPEAKER || second == EndpointType.MICROPHONE || second == EndpointType.CAMERA || second == EndpointType.DRONE;
			case SPEAKER -> second == EndpointType.SCREEN || second == EndpointType.MICROPHONE;
			case MICROPHONE -> second == EndpointType.SCREEN || second == EndpointType.SPEAKER;
			case CAMERA -> second == EndpointType.SCREEN;
			case DRONE -> second == EndpointType.SCREEN;
		};
	}

	private static String endpointKey(Endpoint endpoint) {
		if (endpoint == null) {
			return "";
		}
		return endpoint.identityKey();
	}

	private static void ensureLoaded(MinecraftServer server) {
		if (!loaded && server != null) {
			load(server);
		}
	}

	private static void tickSelectedEndpoints(MinecraftServer server) {
		if (server == null || SELECTED_ENDPOINTS.isEmpty()) {
			return;
		}
		long gameTime = server.overworld() == null ? 0L : server.overworld().getGameTime();
		Set<UUID> onlineSelectedPlayers = new HashSet<>();
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			UUID playerId = player.getUUID();
			Endpoint endpoint = SELECTED_ENDPOINTS.get(playerId);
			if (endpoint == null) {
				continue;
			}
			onlineSelectedPlayers.add(playerId);
			if (!player.isAlive()
					|| !player.getMainHandItem().is(ModItems.BLUETOOTH_ADAPTER)
					|| !canUseBluetoothAdapter(player, false)) {
				clearSelectedEndpoint(player, true);
				continue;
			}
			updateSelectionVisual(player, endpoint);
			long nextRefreshTick = NEXT_ACTIONBAR_REFRESH_TICKS.getOrDefault(playerId, 0L);
			if (gameTime >= nextRefreshTick) {
				showSelectionMessage(player, endpoint);
				NEXT_ACTIONBAR_REFRESH_TICKS.put(playerId, gameTime + ACTIONBAR_REFRESH_INTERVAL_TICKS);
			}
		}
		for (UUID playerId : new ArrayList<>(SELECTED_ENDPOINTS.keySet())) {
			if (!onlineSelectedPlayers.contains(playerId)) {
				SELECTED_ENDPOINTS.remove(playerId);
				NEXT_ACTIONBAR_REFRESH_TICKS.remove(playerId);
				VISIBLE_ENDPOINTS.remove(playerId);
				LAST_INTERACTIONS.remove(playerId);
			}
		}
	}

	private static boolean isDuplicateInteraction(ServerPlayer player, Endpoint endpoint) {
		if (player == null || endpoint == null) {
			return false;
		}
		long gameTime = player.level().getGameTime();
		UUID playerId = player.getUUID();
		LastInteraction previous = LAST_INTERACTIONS.get(playerId);
		LastInteraction current = new LastInteraction(gameTime, endpoint);
		LAST_INTERACTIONS.put(playerId, current);
		return previous != null && previous.gameTime() == gameTime && previous.endpoint().equals(endpoint);
	}

	private static void setSelectedEndpoint(ServerPlayer player, Endpoint endpoint) {
		if (player == null || endpoint == null) {
			return;
		}
		UUID playerId = player.getUUID();
		SELECTED_ENDPOINTS.put(playerId, endpoint);
		NEXT_ACTIONBAR_REFRESH_TICKS.put(playerId, 0L);
		VISIBLE_ENDPOINTS.remove(playerId);
		updateSelectionVisual(player, endpoint);
		showSelectionMessage(player, endpoint);
	}

	private static void clearSelectedEndpoint(ServerPlayer player, boolean clearActionBar) {
		if (player == null) {
			return;
		}
		UUID playerId = player.getUUID();
		SELECTED_ENDPOINTS.remove(playerId);
		NEXT_ACTIONBAR_REFRESH_TICKS.remove(playerId);
		VISIBLE_ENDPOINTS.remove(playerId);
		ServerSelectionHighlightSystem.clear(player);
		if (clearActionBar) {
			player.displayClientMessage(Component.empty(), true);
		}
	}

	private static void clearSelectedEndpoint(Endpoint endpoint, MinecraftServer server) {
		if (endpoint == null) {
			return;
		}
		for (UUID playerId : new ArrayList<>(SELECTED_ENDPOINTS.keySet())) {
			if (!endpoint.equals(SELECTED_ENDPOINTS.get(playerId))) {
				continue;
			}
			SELECTED_ENDPOINTS.remove(playerId);
			NEXT_ACTIONBAR_REFRESH_TICKS.remove(playerId);
			VISIBLE_ENDPOINTS.remove(playerId);
			if (server == null) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				ServerSelectionHighlightSystem.clear(player);
				player.displayClientMessage(Component.empty(), true);
			}
		}
	}

	private static void updateSelectedEndpoints(Endpoint from, Endpoint to) {
		if (from == null || to == null) {
			return;
		}
		for (UUID playerId : new ArrayList<>(SELECTED_ENDPOINTS.keySet())) {
			if (!from.equals(SELECTED_ENDPOINTS.get(playerId))) {
				continue;
			}
			SELECTED_ENDPOINTS.put(playerId, to);
			VISIBLE_ENDPOINTS.remove(playerId);
		}
	}

	private static void updateSelectionVisual(ServerPlayer player, Endpoint endpoint) {
		if (player == null || endpoint == null || !(player.level() instanceof ServerLevel level)) {
			return;
		}
		UUID playerId = player.getUUID();
		if (!Objects.equals(level.dimension(), endpoint.dimension())) {
			if (VISIBLE_ENDPOINTS.remove(playerId) != null) {
				ServerSelectionHighlightSystem.clear(player);
			}
			return;
		}
		if (endpoint.equals(VISIBLE_ENDPOINTS.get(playerId))) {
			return;
		}
		switch (endpoint.type()) {
			case SCREEN -> {
				ServerSelectionHighlightSystem.show(player, MonitorScreenSystem.resolveBluetoothScreenHighlightBlueprints(level, endpoint));
				VISIBLE_ENDPOINTS.put(playerId, endpoint);
			}
			case SPEAKER, MICROPHONE, CAMERA -> {
				if (!level.hasChunkAt(endpoint.pos())) {
					if (VISIBLE_ENDPOINTS.remove(playerId) != null) {
						ServerSelectionHighlightSystem.clear(player);
					}
					return;
				}
				BlockState state = level.getBlockState(endpoint.pos());
				if (state.isAir()) {
					if (VISIBLE_ENDPOINTS.remove(playerId) != null) {
						ServerSelectionHighlightSystem.clear(player);
					}
					return;
				}
				List<ServerSelectionHighlightSystem.DisplayBlueprint> blueprints = switch (endpoint.type()) {
					case MICROPHONE -> MicrophoneBlock.resolveBluetoothHighlightBlueprints(level, endpoint.pos());
					case CAMERA -> CameraBlock.resolveBluetoothHighlightBlueprints(level, endpoint.pos());
					default -> List.of();
				};
				if (blueprints.isEmpty()) {
					ItemStack highlightCarrier = ServerSelectionHighlightSystem.createHighlightCarrierStack();
					blueprints = List.of(new ServerSelectionHighlightSystem.ItemDisplayBlueprint(
							level,
							Vec3.atCenterOf(endpoint.pos()),
							0.0F,
							0.0F,
							highlightCarrier,
							ItemDisplayContext.FIXED,
							ServerSelectionHighlightSystem.defaultHighlightCarrierTransformation()
					));
				}
				ServerSelectionHighlightSystem.show(player, blueprints);
				VISIBLE_ENDPOINTS.put(playerId, endpoint);
			}
			case DRONE -> {
				ServerSelectionHighlightSystem.show(player, DroneSystem.resolveBluetoothDroneHighlightBlueprints(level, endpoint));
				VISIBLE_ENDPOINTS.put(playerId, endpoint);
			}
		}
	}

	private static void load(MinecraftServer server) {
		LINKS.clear();
		loaded = true;
		dirty = false;
		if (server == null) {
			return;
		}
		Path path = statePath(server);
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			JsonElement parsed = JsonParser.parseReader(reader);
			if (!(parsed instanceof JsonArray entries)) {
				return;
			}
			for (JsonElement element : entries) {
				if (!(element instanceof JsonObject object)) {
					continue;
				}
				Endpoint first = endpointFromJson(object.getAsJsonObject("a"));
				Endpoint second = endpointFromJson(object.getAsJsonObject("b"));
				if (first == null || second == null || first.equals(second) || !isLinkAllowed(first.type(), second.type())) {
					continue;
				}
				link(first, second);
			}
			dirty = false;
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to load bluetooth links", exception);
		}
	}

	private static void save(MinecraftServer server) {
		if (!loaded || !dirty || server == null) {
			return;
		}
		Path path = statePath(server);
		try {
			Files.createDirectories(path.getParent());
			JsonArray entries = new JsonArray();
			Set<String> writtenPairs = new HashSet<>();
			for (Map.Entry<Endpoint, LinkedHashSet<Endpoint>> entry : LINKS.entrySet()) {
				for (Endpoint linked : entry.getValue()) {
					String pairKey = pairKey(entry.getKey(), linked);
					if (!writtenPairs.add(pairKey)) {
						continue;
					}
					JsonObject object = new JsonObject();
					object.add("a", endpointToJson(entry.getKey()));
					object.add("b", endpointToJson(linked));
					entries.add(object);
				}
			}
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(entries, writer);
			}
			dirty = false;
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to save bluetooth links", exception);
		}
	}

	private static Path statePath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE_NAME);
	}

	private static JsonObject endpointToJson(Endpoint endpoint) {
		JsonObject object = new JsonObject();
		object.addProperty("dimension", endpoint.dimension().identifier().toString());
		object.addProperty("type", endpoint.type().serializedName());
		object.addProperty("x", endpoint.pos().getX());
		object.addProperty("y", endpoint.pos().getY());
		object.addProperty("z", endpoint.pos().getZ());
		if (endpoint.facing() != null) {
			object.addProperty("facing", endpoint.facing().getName());
		}
		if (endpoint.screenId() != null && !endpoint.screenId().isBlank()) {
			object.addProperty("screen_id", endpoint.screenId());
		}
		if (endpoint.deviceUuid() != null) {
			object.addProperty("device_uuid", endpoint.deviceUuid().toString());
		}
		return object;
	}

	private static Endpoint endpointFromJson(JsonObject object) {
		if (object == null) {
			return null;
		}
		Identifier dimensionId = Identifier.tryParse(object.has("dimension") ? object.get("dimension").getAsString() : "");
		EndpointType type = EndpointType.fromSerializedName(object.has("type") ? object.get("type").getAsString() : "");
		if (dimensionId == null || type == null) {
			return null;
		}
		Direction facing = null;
		if (object.has("facing")) {
			facing = Direction.byName(object.get("facing").getAsString());
		}
		String screenId = object.has("screen_id") ? object.get("screen_id").getAsString() : null;
		UUID deviceUuid = null;
		if (object.has("device_uuid")) {
			try {
				deviceUuid = UUID.fromString(object.get("device_uuid").getAsString());
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}
		return new Endpoint(
				ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimensionId),
				type,
				new BlockPos(
						object.has("x") ? object.get("x").getAsInt() : 0,
						object.has("y") ? object.get("y").getAsInt() : 0,
						object.has("z") ? object.get("z").getAsInt() : 0
				),
				facing,
				screenId,
				deviceUuid
		);
	}

	public enum EndpointType {
		SCREEN("screen"),
		SPEAKER("speaker"),
		MICROPHONE("microphone"),
		CAMERA("camera"),
		DRONE("drone");

		private final String serializedName;

		EndpointType(String serializedName) {
			this.serializedName = serializedName;
		}

		private String serializedName() {
			return this.serializedName;
		}

		private static EndpointType fromSerializedName(String name) {
			for (EndpointType type : values()) {
				if (type.serializedName.equals(name)) {
					return type;
				}
			}
			return null;
		}
	}

	private record LastInteraction(long gameTime, Endpoint endpoint) {
	}

	public static final class Endpoint {
		private final ResourceKey<Level> dimension;
		private final EndpointType type;
		private final BlockPos pos;
		private final Direction facing;
		private final String screenId;
		private final UUID deviceUuid;

		private Endpoint(ResourceKey<Level> dimension, EndpointType type, BlockPos pos, Direction facing, String screenId, UUID deviceUuid) {
			this.dimension = dimension;
			this.type = type;
			this.pos = pos == null ? BlockPos.ZERO : pos.immutable();
			this.facing = type == EndpointType.SCREEN ? facing : null;
			this.screenId = type == EndpointType.SCREEN && screenId != null && !screenId.isBlank() ? screenId : null;
			this.deviceUuid = type == EndpointType.DRONE ? deviceUuid : null;
		}

		public ResourceKey<Level> dimension() {
			return this.dimension;
		}

		public EndpointType type() {
			return this.type;
		}

		public BlockPos pos() {
			return this.pos;
		}

		public Direction facing() {
			return this.facing;
		}

		public String screenId() {
			return this.screenId;
		}

		public UUID deviceUuid() {
			return this.deviceUuid;
		}

		private String identityKey() {
			StringBuilder builder = new StringBuilder();
			builder.append(this.dimension.identifier()).append('|').append(this.type.name()).append('|');
			if (this.type == EndpointType.SCREEN && this.screenId != null) {
				return builder.append("screen:").append(this.screenId).toString();
			}
			if (this.type == EndpointType.DRONE && this.deviceUuid != null) {
				return builder.append("drone:").append(this.deviceUuid).toString();
			}
			builder.append(this.pos.getX()).append('|').append(this.pos.getY()).append('|').append(this.pos.getZ());
			if (this.type == EndpointType.SCREEN) {
				builder.append('|').append(this.facing == null ? "" : this.facing.getName());
			}
			return builder.toString();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Endpoint other)) {
				return false;
			}
			return this.identityKey().equals(other.identityKey());
		}

		@Override
		public int hashCode() {
			return this.identityKey().hashCode();
		}

		@Override
		public String toString() {
			return this.identityKey();
		}

		private static Endpoint block(ResourceKey<Level> dimension, EndpointType type, BlockPos pos) {
			if (dimension == null || type == null || pos == null) {
				return null;
			}
			return new Endpoint(dimension, type, pos, null, null, null);
		}

		private static Endpoint screen(ResourceKey<Level> dimension, BlockPos pos, Direction facing, String screenId) {
			if (dimension == null || pos == null || facing == null) {
				return null;
			}
			return new Endpoint(dimension, EndpointType.SCREEN, pos, facing, screenId, null);
		}

		private static Endpoint drone(ResourceKey<Level> dimension, BlockPos pos, UUID droneUuid) {
			if (dimension == null || pos == null || droneUuid == null) {
				return null;
			}
			return new Endpoint(dimension, EndpointType.DRONE, pos, null, null, droneUuid);
		}
	}
}
