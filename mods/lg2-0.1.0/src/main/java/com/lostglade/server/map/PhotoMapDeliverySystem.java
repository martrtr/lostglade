package com.lostglade.server.map;

import com.lostglade.item.PhotoPrintData;
import com.lostglade.server.AccountAuthSystem;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Full baselines for new viewers; ongoing render patches remain owned by the renderer. */
final class PhotoMapDeliverySystem {
	private static final Map<UUID, Set<ItemFrame>> TRACKED_FRAMES = new HashMap<>();
	private static final Map<UUID, Set<Integer>> VISIBLE_MAPS = new HashMap<>();

	static void register() {
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			if (entity instanceof ItemFrame frame)
				TRACKED_FRAMES.computeIfAbsent(player.getUUID(), key -> new HashSet<>()).add(frame);
		});
		EntityTrackingEvents.STOP_TRACKING.register((entity, player) -> {
			Set<ItemFrame> frames = TRACKED_FRAMES.get(player.getUUID());
			if (frames != null) frames.remove(entity);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			TRACKED_FRAMES.remove(handler.player.getUUID());
			VISIBLE_MAPS.remove(handler.player.getUUID());
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, player, alive) -> VISIBLE_MAPS.remove(player.getUUID()));
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> VISIBLE_MAPS.remove(player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> { TRACKED_FRAMES.clear(); VISIBLE_MAPS.clear(); });
		ServerTickEvents.END_SERVER_TICK.register(PhotoMapDeliverySystem::tick);
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 5 != 0) return;
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			Set<Integer> current = new HashSet<>();
			for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
				addPreview(current, player.getInventory().getItem(slot));
			addPreview(current, player.containerMenu.getCarried());
			for (ItemFrame frame : TRACKED_FRAMES.getOrDefault(player.getUUID(), Set.of())) {
				if (frame.isRemoved() || frame.level() != player.level()) continue;
				var data = PhotoPrintData.readFrameTile(frame.getItem());
				if (data != null && !data.asPhotoPrintData().isVideo()) {
					MapId id = frame.getItem().get(net.minecraft.core.component.DataComponents.MAP_ID);
					if (id != null) current.add(id.id());
				}
			}
			Set<Integer> previous = VISIBLE_MAPS.getOrDefault(player.getUUID(), Set.of());
			var level = MapImageRenderSystem.photoMapLevel(server, player.level());
			for (int rawId : newlyVisible(previous, current)) {
				MapId id = new MapId(rawId);
				var data = level.getMapData(id);
				if (data == null) { current.remove(rawId); continue; }
				MapImageRenderSystem.sendPhotoMap(player, id, data);
			}
			VISIBLE_MAPS.put(player.getUUID(), current);
		}
	}

	private static void addPreview(Set<Integer> current, ItemStack stack) {
		var data = PhotoPrintData.readPhotoItem(stack);
		// Video playback has its own viewer baselines; don't overwrite an animated frame.
		if (data != null && !data.isVideo() && data.previewMapId() >= 0) current.add(data.previewMapId());
	}

	static Set<Integer> newlyVisible(Set<Integer> previous, Set<Integer> current) {
		Set<Integer> added = new HashSet<>(current);
		added.removeAll(previous);
		return added;
	}
}
