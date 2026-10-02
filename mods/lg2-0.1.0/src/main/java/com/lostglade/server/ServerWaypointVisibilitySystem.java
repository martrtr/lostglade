package com.lostglade.server;

import com.mojang.datafixers.util.Either;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the server-side view of the waypoints that have actually reached each
 * client. Vanilla rejects an UPDATE for an unknown waypoint id, so every
 * outgoing waypoint packet passes through this connection-local ledger.
 */
public final class ServerWaypointVisibilitySystem {
	private static final Map<UUID, Set<Either<UUID, String>>> VISIBLE_WAYPOINTS = new HashMap<>();

	private ServerWaypointVisibilitySystem() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> VISIBLE_WAYPOINTS.clear());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				VISIBLE_WAYPOINTS.remove(handler.player.getUUID()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				VISIBLE_WAYPOINTS.remove(handler.player.getUUID()));
	}

	public static Packet<?> filterOutgoingPacket(ServerPlayer receiver, Packet<?> packet) {
		if (receiver == null || packet == null) {
			return packet;
		}
		if (packet instanceof ClientboundBundlePacket bundlePacket) {
			return filterBundle(receiver, bundlePacket);
		}
		if (packet instanceof ClientboundTrackedWaypointPacket waypointPacket) {
			if (!"UNTRACK".equals(String.valueOf(waypointPacket.operation()))
					&& (ServerRaceSystem.shouldSuppressMilkMouseWaypoint(receiver, waypointPacket)
						|| ServerRaceSystem.shouldSuppressKilkaSalmonWaypoint(receiver, waypointPacket))) {
				Set<Either<UUID, String>> visible = VISIBLE_WAYPOINTS.get(receiver.getUUID());
				return visible != null && visible.remove(waypointPacket.waypoint().id())
						? ClientboundTrackedWaypointPacket.removeWaypoint(waypointPacket.waypoint().id().left().orElseThrow()) : null;
			}
			return shouldSend(receiver, waypointPacket) ? packet : null;
		}
		return packet;
	}

	private static Packet<?> filterBundle(ServerPlayer receiver, ClientboundBundlePacket bundlePacket) {
		List<Packet<? super ClientGamePacketListener>> visiblePackets = new ArrayList<>();
		boolean changed = false;
		for (Packet<? super ClientGamePacketListener> nestedPacket : bundlePacket.subPackets()) {
			Packet<?> filteredPacket = filterOutgoingPacket(receiver, nestedPacket);
			if (filteredPacket == null) {
				changed = true;
				continue;
			}
			if (filteredPacket != nestedPacket) {
				changed = true;
			}
			@SuppressWarnings("unchecked")
			Packet<? super ClientGamePacketListener> gamePacket = (Packet<? super ClientGamePacketListener>) filteredPacket;
			visiblePackets.add(gamePacket);
		}
		if (!changed) {
			return bundlePacket;
		}
		return visiblePackets.isEmpty() ? null : new ClientboundBundlePacket(visiblePackets);
	}

	private static boolean shouldSend(ServerPlayer receiver, ClientboundTrackedWaypointPacket packet) {
		if (packet.waypoint() == null) {
			return false;
		}
		Either<UUID, String> waypointId = packet.waypoint().id();
		if (waypointId == null) {
			return false;
		}
		Set<Either<UUID, String>> visible = VISIBLE_WAYPOINTS.computeIfAbsent(
				receiver.getUUID(), ignored -> new HashSet<>()
		);
		String operation = String.valueOf(packet.operation());
		if ("TRACK".equals(operation)) {
			visible.add(waypointId);
			return true;
		}
		if ("UNTRACK".equals(operation)) {
			visible.remove(waypointId);
			return true;
		}
		return !"UPDATE".equals(operation) || visible.contains(waypointId);
	}
}
