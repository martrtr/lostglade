package com.lostglade.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ServerBossBarVisibilitySystem {
	/*
	 * A synthetic packet is still sent through the same outgoing gate as a
	 * vanilla packet.  The flag only tells the gate that its payload is a visual
	 * replay of an already tracked *real* bossbar, so it must not overwrite the
	 * real title/progress snapshot with the decorated HUD title.
	 */
	private static final ThreadLocal<Boolean> SYNTHETIC_PACKET = ThreadLocal.withInitial(() -> false);
	private static final Map<UUID, PlayerBossBarState> PLAYER_STATES = new HashMap<>();
	private static final Map<UUID, Set<UUID>> DELIVERED_BOSS_BARS = new HashMap<>();

	private ServerBossBarVisibilitySystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ServerBossBarVisibilitySystem::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			PLAYER_STATES.clear();
			DELIVERED_BOSS_BARS.clear();
		});
		// Bossbar ids belong to a single client connection. Keeping the previous
		// connection's tracked bars after a reconnect lets a later synthetic
		// UPDATE_NAME target an id the new client has never received an ADD for;
		// vanilla then throws an NPE while handling the packet.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PLAYER_STATES.remove(handler.player.getUUID());
			DELIVERED_BOSS_BARS.remove(handler.player.getUUID());
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			PLAYER_STATES.remove(handler.player.getUUID());
			DELIVERED_BOSS_BARS.remove(handler.player.getUUID());
		});
	}

	public static void setServerHudFocus(ServerPlayer player, boolean focused) {
		PlayerBossBarState state = PLAYER_STATES.computeIfAbsent(player.getUUID(), id -> new PlayerBossBarState());
		if (state.serverHudFocused == focused) {
			return;
		}

		state.serverHudFocused = focused;
		if (focused) {
			state.needsReorder = true;
			return;
		}

		state.needsReorder = false;
		if (state.activeBossBars.isEmpty()) {
			PLAYER_STATES.remove(player.getUUID());
		}
	}

	public static void ensureServerHudPriority(ServerPlayer player) {
		PlayerBossBarState state = PLAYER_STATES.get(player.getUUID());
		if (state == null || !state.serverHudFocused || !state.needsReorder) {
			return;
		}

		reorderTrackedBossBarsBelowServerHud(player, state);
		state.needsReorder = false;
	}

	public static void reorderTrackedBossBarsBelowReservedHud(ServerPlayer player) {
		if (player == null) {
			return;
		}
		PlayerBossBarState state = PLAYER_STATES.get(player.getUUID());
		if (state == null || state.activeBossBars.isEmpty()) {
			return;
		}
		reorderTrackedBossBarsBelowServerHud(player, state);
		state.needsReorder = false;
	}

	public static Packet<?> filterOutgoingPacket(ServerPlayer receiver, Packet<?> packet) {
		if (receiver == null || packet == null) {
			return packet;
		}
		if (packet instanceof ClientboundBundlePacket bundlePacket) {
			return filterBundle(receiver, bundlePacket);
		}
		if (packet instanceof ClientboundBossEventPacket bossEventPacket) {
			return rewriteOutgoingBossEventPacket(receiver, bossEventPacket);
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

	public static Packet<?> filterDeliveredPacket(ServerPlayer receiver, Packet<?> packet) {
		if (receiver == null || packet == null) {
			return packet;
		}
		if (packet instanceof ClientboundBundlePacket bundlePacket) {
			return filterDeliveredBundle(receiver, bundlePacket);
		}
		if (!(packet instanceof ClientboundBossEventPacket bossEventPacket)) {
			return packet;
		}
		BossBarPacketUpdate update = BossBarPacketUpdate.from(bossEventPacket);
		if (update == null) {
			return packet;
		}
		Set<UUID> delivered = DELIVERED_BOSS_BARS.computeIfAbsent(
				receiver.getUUID(), ignored -> new HashSet<>()
		);
		if (update.type == BossBarPacketType.ADD) {
			delivered.add(update.id);
			return packet;
		}
		if (update.type == BossBarPacketType.REMOVE) {
			delivered.remove(update.id);
			return packet;
		}
		return delivered.contains(update.id) ? packet : null;
	}

	private static Packet<?> filterDeliveredBundle(ServerPlayer receiver, ClientboundBundlePacket bundlePacket) {
		List<Packet<? super ClientGamePacketListener>> visiblePackets = new ArrayList<>();
		boolean changed = false;
		for (Packet<? super ClientGamePacketListener> nestedPacket : bundlePacket.subPackets()) {
			Packet<?> filteredPacket = filterDeliveredPacket(receiver, nestedPacket);
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

	public static ClientboundBossEventPacket rewriteOutgoingBossEventPacket(ServerPlayer receiver, ClientboundBossEventPacket packet) {
		BossBarPacketUpdate update = BossBarPacketUpdate.from(packet);
		if (update == null) {
			return packet;
		}

		boolean reservedHud = ServerStabilitySystem.isHudBossBar(receiver, update.id)
				|| ServerRaceSystem.isMarkRageBossBar(receiver, update.id)
				|| NecromancerStockSystem.isManaBossBar(receiver, update.id)
				|| DroneSystem.isHudBossBar(receiver, update.id)
				|| ServerRaceSystem.isPuroSanOverdriveBossBar(receiver, update.id)
				|| OrthodoxHolinessSystem.isBossBar(receiver, update.id);
		PlayerBossBarState state = PLAYER_STATES.computeIfAbsent(receiver.getUUID(), id -> new PlayerBossBarState());

		// Drone HUD shutdown is deliberately ordered: mark the id as closed, send
		// REMOVE, then reject every later packet for that id. This prevents both
		// variants of the old bug: UPDATE_* for an absent client bar (crash), and a
		// delayed ADD recreating a HUD after it was meant to disappear.
		if (DroneSystem.isClosingHudBossBar(receiver, update.id) && update.type != BossBarPacketType.REMOVE) {
			return null;
		}

		if (SYNTHETIC_PACKET.get()) {
			// Synthetic replays are allowed to alter only the client-presence state.
			// In particular, an UPDATE_NAME has no path to the socket unless this
			// exact id is still known to have reached this client connection.
			return applySyntheticPacketUpdate(state, reservedHud, update) ? packet : null;
		}

		// Reserved HUD bars used to bypass this state machine entirely. Their REMOVE
		// and UPDATE_NAME packets can still cross during drone teardown, however, and
		// 1.21.11 crashes the client on the resulting update-for-missing-id. Track
		// them too; they merely skip the external-bar title composition below.
		if (reservedHud) {
			applyPacketUpdate(state.reservedBossBars, state.clientVisibleBossBars, update);
			if (update.type.isUpdate() && !state.clientVisibleBossBars.contains(update.id)) {
				return null;
			}
			return packet;
		}

		applyPacketUpdate(state.activeBossBars, state.clientVisibleBossBars, update);
		// The vanilla client dereferences the bossbar map for every UPDATE_* packet.
		// Unlike REMOVE, an update for an id it has not received an ADD for is a hard
		// client crash in 1.21.11. This can happen around the drone HUD teardown: the
		// real bar may have been removed and re-added while packets from the same tick
		// are still being composed. Never forward such an orphaned update.
		if (update.type.isUpdate() && !state.clientVisibleBossBars.contains(update.id)) {
			return null;
		}

		Component overlayTitle = DroneSystem.getHudOverlayTitle(receiver);
		if (overlayTitle == null) {
			return packet;
		}

		DroneSystem.suspendHudOverlayForExternalBossBar(receiver);
		if (update.type == BossBarPacketType.REMOVE && state.activeBossBars.isEmpty()) {
			// Re-add on the following server tick, after the client has processed the
			// real bar's removal. This avoids a one-line vertical jump.
			state.restoreDroneOverlayNextTick = true;
			return packet;
		}

		if (update.type == BossBarPacketType.ADD || update.type == BossBarPacketType.UPDATE_NAME) {
			TrackedBossBar bossBar = state.activeBossBars.get(update.id);
			if (bossBar != null && state.clientVisibleBossBars.contains(update.id)) {
				BossEvent rewrittenBossBar = bossBar.toBossEvent(withDroneOverlayTitle(bossBar.name, overlayTitle));
				return update.type == BossBarPacketType.ADD
						? ClientboundBossEventPacket.createAddPacket(rewrittenBossBar)
						: ClientboundBossEventPacket.createUpdateNamePacket(rewrittenBossBar);
			}
		}

		return packet;
	}

	/**
	 * Installs the current drone title into every real bossbar already visible
	 * to this player. The real bar remains on its original line; only its name
	 * packet is replaced, so no empty bossbar row or vertical spacing appears.
	 */
	public static boolean refreshDroneHudOverlay(ServerPlayer player) {
		if (player == null) {
			return false;
		}
		PlayerBossBarState state = PLAYER_STATES.get(player.getUUID());
		Component overlayTitle = DroneSystem.getHudOverlayTitle(player);
		if (state == null || state.activeBossBars.isEmpty() || overlayTitle == null) {
			return false;
		}

		DroneSystem.suspendHudOverlayForExternalBossBar(player);
		state.restoreDroneOverlayNextTick = false;
		for (TrackedBossBar bossBar : state.activeBossBars.values()) {
			if (!state.clientVisibleBossBars.contains(bossBar.id)) {
				continue;
			}
			sendSyntheticPacket(player, ClientboundBossEventPacket.createUpdateNamePacket(
					bossBar.toBossEvent(withDroneOverlayTitle(bossBar.name, overlayTitle))
			));
		}
		return true;
	}

	/** Restores unmodified titles when the drone HUD is closed. */
	public static void clearDroneHudOverlay(ServerPlayer player) {
		if (player == null) {
			return;
		}
		PlayerBossBarState state = PLAYER_STATES.get(player.getUUID());
		if (state == null) {
			return;
		}
		state.restoreDroneOverlayNextTick = false;
		for (TrackedBossBar bossBar : state.activeBossBars.values()) {
			if (!state.clientVisibleBossBars.contains(bossBar.id)) {
				continue;
			}
			sendSyntheticPacket(player, ClientboundBossEventPacket.createUpdateNamePacket(bossBar.toBossEvent()));
		}
	}

	private static void applyPacketUpdate(
			Map<UUID, TrackedBossBar> trackedBossBars,
			java.util.Set<UUID> clientVisibleBossBars,
			BossBarPacketUpdate update
	) {
		switch (update.type) {
			case ADD -> {
				trackedBossBars.put(
						update.id,
						new TrackedBossBar(
							update.id,
							copyComponent(update.name),
							update.progress == null ? 0.0F : update.progress,
							update.color == null ? BossEvent.BossBarColor.WHITE : update.color,
							update.overlay == null ? BossEvent.BossBarOverlay.PROGRESS : update.overlay,
							Boolean.TRUE.equals(update.darkenScreen),
							Boolean.TRUE.equals(update.playBossMusic),
							Boolean.TRUE.equals(update.createWorldFog)
						)
				);
				clientVisibleBossBars.add(update.id);
			}
			case REMOVE -> {
				trackedBossBars.remove(update.id);
				clientVisibleBossBars.remove(update.id);
			}
			case UPDATE_PROGRESS -> {
				TrackedBossBar bossBar = trackedBossBars.get(update.id);
				if (bossBar != null && update.progress != null) {
					bossBar.progress = update.progress;
				}
			}
			case UPDATE_NAME -> {
				TrackedBossBar bossBar = trackedBossBars.get(update.id);
				if (bossBar != null && update.name != null) {
					bossBar.name = copyComponent(update.name);
				}
			}
			case UPDATE_STYLE -> {
				TrackedBossBar bossBar = trackedBossBars.get(update.id);
				if (bossBar != null) {
					if (update.color != null) {
						bossBar.color = update.color;
					}
					if (update.overlay != null) {
						bossBar.overlay = update.overlay;
					}
				}
			}
			case UPDATE_PROPERTIES -> {
				TrackedBossBar bossBar = trackedBossBars.get(update.id);
				if (bossBar != null) {
					if (update.darkenScreen != null) {
						bossBar.darkenScreen = update.darkenScreen;
					}
					if (update.playBossMusic != null) {
						bossBar.playBossMusic = update.playBossMusic;
					}
					if (update.createWorldFog != null) {
						bossBar.createWorldFog = update.createWorldFog;
					}
				}
			}
		}
	}

	/**
	 * Applies the client-visible part of an internally generated packet without
	 * changing the authoritative snapshot of a real bossbar.  This is what makes
	 * REMOVE -> ADD reordering safe: REMOVE hides the id, ADD may expose only an
	 * already tracked id, and UPDATE_* is impossible while the id is hidden.
	 */
	private static boolean applySyntheticPacketUpdate(
			PlayerBossBarState state,
			boolean reservedHud,
			BossBarPacketUpdate update
	) {
		if (state == null || update == null) {
			return false;
		}
		Map<UUID, TrackedBossBar> trackedBossBars = reservedHud ? state.reservedBossBars : state.activeBossBars;
		switch (update.type) {
			case ADD -> {
				if (!trackedBossBars.containsKey(update.id)) {
					return false;
				}
				state.clientVisibleBossBars.add(update.id);
				return true;
			}
			case REMOVE -> {
				// Vanilla REMOVE is safe even if the client no longer has this id.
				state.clientVisibleBossBars.remove(update.id);
				return true;
			}
			case UPDATE_PROGRESS, UPDATE_NAME, UPDATE_STYLE, UPDATE_PROPERTIES -> {
				return state.clientVisibleBossBars.contains(update.id);
			}
		}
		return false;
	}

	private static void reorderTrackedBossBarsBelowServerHud(ServerPlayer player, PlayerBossBarState state) {
		for (UUID bossBarId : state.activeBossBars.keySet()) {
			sendSyntheticPacket(player, ClientboundBossEventPacket.createRemovePacket(bossBarId));
		}
		for (TrackedBossBar bossBar : state.activeBossBars.values()) {
			sendSyntheticPacket(player, ClientboundBossEventPacket.createAddPacket(bossBar.toBossEvent()));
		}
	}

	private static void sendSyntheticPacket(ServerPlayer player, ClientboundBossEventPacket packet) {
		// Never bypass the boss-event gate.  The gate validates this packet against
		// the per-connection visibility ledger before it can reach the client.
		if (player == null || packet == null) {
			return;
		}
		SYNTHETIC_PACKET.set(true);
		try {
			player.connection.send(packet);
		} finally {
			SYNTHETIC_PACKET.remove();
		}
	}

	private static void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, PlayerBossBarState>> iterator = PLAYER_STATES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, PlayerBossBarState> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				iterator.remove();
				continue;
			}

			PlayerBossBarState state = entry.getValue();
			if (state.restoreDroneOverlayNextTick && state.activeBossBars.isEmpty()) {
				state.restoreDroneOverlayNextTick = false;
				DroneSystem.restoreHudOverlayWithoutExternalBossBar(player);
			}
			// Keep the reserved HUD ids for the whole animation. Otherwise the next
			// frame is mistaken for an update of an unknown bossbar and is suppressed.
			if (!state.serverHudFocused && state.activeBossBars.isEmpty() && state.reservedBossBars.isEmpty()) {
				iterator.remove();
			}
		}
	}

	private static Component copyComponent(Component component) {
		return component == null ? Component.empty() : component.copy();
	}

	private static Component withDroneOverlayTitle(Component realTitle, Component overlayTitle) {
		Component safeRealTitle = copyComponent(realTitle);
		Component safeOverlayTitle = copyComponent(overlayTitle);
		int overlayOffset = (estimatedVanillaTextWidth(safeRealTitle) + 1) / 2 + 24;
		String shiftLeft = buildSpaceAdvance(-overlayOffset);
		String shiftRight = buildSpaceAdvance(overlayOffset);
		return Component.empty()
				.append(safeRealTitle)
				.append(Component.literal(shiftLeft))
				.append(safeOverlayTitle)
				.append(Component.literal("\uE940\uE94B\uE946" + shiftRight));
	}

	private static int estimatedVanillaTextWidth(Component component) {
		if (component == null) {
			return 0;
		}
		String text = component.getString();
		int width = 0;
		for (int index = 0; index < text.length(); ) {
			int codePoint = text.codePointAt(index);
			width += estimatedVanillaGlyphWidth(codePoint);
			index += Character.charCount(codePoint);
		}
		return width;
	}

	private static int estimatedVanillaGlyphWidth(int codePoint) {
		if (codePoint == ' ') {
			return 4;
		}
		return switch (codePoint) {
			case '!', '\'', '.', ',', ':', ';', '|', 'i', 'l', 'I' -> 2;
			case '"', '(', ')', '[', ']', '{', '}', '*', 't', 'f', 'k' -> 4;
			case 'M', 'W', 'm', 'w', '@', '%', '&' -> 7;
			default -> codePoint > 0x7F ? 6 : 6;
		};
	}

	private static String buildSpaceAdvance(int amount) {
		if (amount == 0) {
			return "";
		}
		int[] values = {64, 32, 16, 8, 4, 2, 1};
		int codePointBase = amount < 0 ? 0xE940 : 0xE947;
		int remaining = Math.abs(amount);
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < values.length; index++) {
			while (remaining >= values[index]) {
				builder.appendCodePoint(codePointBase + index);
				remaining -= values[index];
			}
		}
		return builder.toString();
	}

	private enum BossBarPacketType {
		ADD,
		REMOVE,
		UPDATE_PROGRESS,
		UPDATE_NAME,
		UPDATE_STYLE,
		UPDATE_PROPERTIES;

		private boolean isUpdate() {
			return this != ADD && this != REMOVE;
		}
	}

	private static final class PlayerBossBarState {
		private boolean serverHudFocused;
		private boolean needsReorder;
		private boolean restoreDroneOverlayNextTick;
		private final Map<UUID, TrackedBossBar> activeBossBars = new LinkedHashMap<>();
		private final Map<UUID, TrackedBossBar> reservedBossBars = new LinkedHashMap<>();
		private final java.util.Set<UUID> clientVisibleBossBars = new java.util.HashSet<>();
	}

	private static final class TrackedBossBar {
		private final UUID id;
		private Component name;
		private float progress;
		private BossEvent.BossBarColor color;
		private BossEvent.BossBarOverlay overlay;
		private boolean darkenScreen;
		private boolean playBossMusic;
		private boolean createWorldFog;

		private TrackedBossBar(
				UUID id,
				Component name,
				float progress,
				BossEvent.BossBarColor color,
				BossEvent.BossBarOverlay overlay,
				boolean darkenScreen,
				boolean playBossMusic,
				boolean createWorldFog
		) {
			this.id = id;
			this.name = name;
			this.progress = progress;
			this.color = color;
			this.overlay = overlay;
			this.darkenScreen = darkenScreen;
			this.playBossMusic = playBossMusic;
			this.createWorldFog = createWorldFog;
		}

		private BossEvent toBossEvent() {
			return toBossEvent(this.name);
		}

		private BossEvent toBossEvent(Component name) {
			BossEvent bossEvent = new BossEvent(this.id, copyComponent(name), this.color, this.overlay) {
			};
			bossEvent.setProgress(this.progress);
			bossEvent.setDarkenScreen(this.darkenScreen);
			bossEvent.setPlayBossMusic(this.playBossMusic);
			bossEvent.setCreateWorldFog(this.createWorldFog);
			return bossEvent;
		}
	}

	private static final class BossBarPacketUpdate {
		private final UUID id;
		private final BossBarPacketType type;
		private final Component name;
		private final Float progress;
		private final BossEvent.BossBarColor color;
		private final BossEvent.BossBarOverlay overlay;
		private final Boolean darkenScreen;
		private final Boolean playBossMusic;
		private final Boolean createWorldFog;

		private BossBarPacketUpdate(
				UUID id,
				BossBarPacketType type,
				Component name,
				Float progress,
				BossEvent.BossBarColor color,
				BossEvent.BossBarOverlay overlay,
				Boolean darkenScreen,
				Boolean playBossMusic,
				Boolean createWorldFog
		) {
			this.id = id;
			this.type = type;
			this.name = name;
			this.progress = progress;
			this.color = color;
			this.overlay = overlay;
			this.darkenScreen = darkenScreen;
			this.playBossMusic = playBossMusic;
			this.createWorldFog = createWorldFog;
		}

		private static BossBarPacketUpdate from(ClientboundBossEventPacket packet) {
			BossBarPacketUpdate[] holder = new BossBarPacketUpdate[1];
			packet.dispatch(new ClientboundBossEventPacket.Handler() {
				@Override
				public void add(
						UUID id,
						Component name,
						float progress,
						BossEvent.BossBarColor color,
						BossEvent.BossBarOverlay overlay,
						boolean darkenScreen,
						boolean playMusic,
						boolean createWorldFog
				) {
					holder[0] = new BossBarPacketUpdate(
							id,
							BossBarPacketType.ADD,
							name,
							progress,
							color,
							overlay,
							darkenScreen,
							playMusic,
							createWorldFog
					);
				}

				@Override
				public void remove(UUID id) {
					holder[0] = new BossBarPacketUpdate(
							id,
							BossBarPacketType.REMOVE,
							null,
							null,
							null,
							null,
							null,
							null,
							null
					);
				}

				@Override
				public void updateProgress(UUID id, float progress) {
					holder[0] = new BossBarPacketUpdate(
							id,
							BossBarPacketType.UPDATE_PROGRESS,
							null,
							progress,
							null,
							null,
							null,
							null,
							null
					);
				}

				@Override
				public void updateName(UUID id, Component name) {
					holder[0] = new BossBarPacketUpdate(
							id,
							BossBarPacketType.UPDATE_NAME,
							name,
							null,
							null,
							null,
							null,
							null,
							null
					);
				}

				@Override
				public void updateStyle(UUID id, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
					holder[0] = new BossBarPacketUpdate(
							id,
							BossBarPacketType.UPDATE_STYLE,
							null,
							null,
							color,
							overlay,
							null,
							null,
							null
					);
				}

				@Override
				public void updateProperties(UUID id, boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
					holder[0] = new BossBarPacketUpdate(
							id,
							BossBarPacketType.UPDATE_PROPERTIES,
							null,
							null,
							null,
							null,
							darkenScreen,
							playMusic,
							createWorldFog
					);
				}
			});
			return holder[0];
		}
	}
}
