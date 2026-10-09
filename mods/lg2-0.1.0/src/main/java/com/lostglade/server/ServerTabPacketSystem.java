package com.lostglade.server;

import com.lostglade.mixin.ClientboundSetPlayerTeamPacketAccessor;
import com.lostglade.mixin.ClientboundSetPlayerTeamPacketParametersAccessor;
import com.mojang.authlib.GameProfile;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ServerTabPacketSystem {
	private ServerTabPacketSystem() {
	}

	public static RewriteResult rewriteOutgoingPlayerInfoPacket(ServerPlayer receiver, ClientboundPlayerInfoUpdatePacket packet) {
		if (receiver == null || packet == null || receiver.level() == null) {
			return null;
		}

		MinecraftServer server = receiver.level().getServer();
		if (server == null || packet.entries().isEmpty()) {
			return null;
		}

		boolean rewriteDisplayNames = packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
				|| packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);
		List<ClientboundPlayerInfoUpdatePacket.Entry> rewrittenEntries = new ArrayList<>(packet.entries().size());
		List<UUID> removedProfileIds = new ArrayList<>();
		boolean changed = false;

		for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
			if (shouldHideEntry(server, receiver, entry)) {
				removedProfileIds.add(entry.profileId());
				changed = true;
				continue;
			}

			if (!rewriteDisplayNames || entry.displayName() == null) {
				rewrittenEntries.add(entry);
				continue;
			}

			rewrittenEntries.add(new ClientboundPlayerInfoUpdatePacket.Entry(
					entry.profileId(),
					entry.profile(),
					entry.listed(),
					entry.latency(),
					entry.gameMode(),
					withoutShadow(entry.displayName()),
					entry.showHat(),
					entry.listOrder(),
					entry.chatSession()
			));
			changed = true;
		}

		if (!changed) {
			return null;
		}

		ClientboundPlayerInfoUpdatePacket rewrittenPacket = rewrittenEntries.isEmpty()
				? null
				: buildMutablePacket(packet, rewrittenEntries);
		return new RewriteResult(rewrittenPacket, List.copyOf(removedProfileIds));
	}

	private static boolean shouldHideEntry(MinecraftServer server, ServerPlayer receiver, ClientboundPlayerInfoUpdatePacket.Entry entry) {
		if (!AccountAuthSystem.isAuthenticated(receiver)) return true;
		ServerPlayer onlinePlayer = server.getPlayerList().getPlayer(entry.profileId());
		if (onlinePlayer != null) {
			boolean preannounced = AccountAuthSystem.isPresencePreannounced(entry.profileId());
			boolean hiddenByAuthentication = !AccountAuthSystem.isPresenceVisible(onlinePlayer) && !preannounced;
			return hiddenByAuthentication
					|| ServerRaceSystem.isMilkMouseActive(onlinePlayer)
					|| SeasonStartSystem.shouldHidePlayerFrom(receiver, onlinePlayer);
		}

		GameProfile profile = entry.profile();
		return profile != null && RendererBotPresenceSystem.isRendererBotName(profile.name());
	}

	/** Sends the profile before entity spawn without making the player visible in the tab list. */
	public static ClientboundPlayerInfoUpdatePacket createUnlistedPlayerInitializingPacket(ServerPlayer player) {
		ClientboundPlayerInfoUpdatePacket original = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
		List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>(original.entries().size());
		for (ClientboundPlayerInfoUpdatePacket.Entry entry : original.entries()) {
			entries.add(new ClientboundPlayerInfoUpdatePacket.Entry(
					entry.profileId(), entry.profile(), false, entry.latency(), entry.gameMode(), entry.displayName(),
					entry.showHat(), entry.listOrder(), entry.chatSession()
			));
		}
		return buildMutablePacket(original, entries);
	}

	private static ClientboundPlayerInfoUpdatePacket buildMutablePacket(
			ClientboundPlayerInfoUpdatePacket original,
			List<ClientboundPlayerInfoUpdatePacket.Entry> rewrittenEntries) {
		ClientboundPlayerInfoUpdatePacket packet = PolymerEntityUtils.createMutablePlayerListPacket(original.actions().clone());
		packet.entries().addAll(rewrittenEntries);
		return packet;
	}

	public static void stripShadowFromTeamPacket(ServerPlayer receiver, ClientboundSetPlayerTeamPacket packet) {
		if (packet == null) {
			return;
		}
		ClientboundSetPlayerTeamPacket.Parameters parameters =
				((ClientboundSetPlayerTeamPacketAccessor) packet).lg2$getParameters().orElse(null);
		if (parameters == null) {
			return;
		}
		ClientboundSetPlayerTeamPacketParametersAccessor accessor =
				(ClientboundSetPlayerTeamPacketParametersAccessor) (Object) parameters;
		accessor.lg2$setDisplayName(withoutShadow(parameters.getDisplayName()));
		accessor.lg2$setPlayerPrefix(withoutShadow(parameters.getPlayerPrefix()));
		accessor.lg2$setPlayerSuffix(withoutShadow(parameters.getPlayerSuffix()));
		if (receiver != null && receiver.level() != null) {
			MinecraftServer server = receiver.level().getServer();
			PlayerTeam serverTeam = server.getScoreboard().getPlayerTeam(packet.getName());
			boolean hasPlayer = server.getPlayerList().getPlayers().stream().anyMatch(player ->
					packet.getPlayers().contains(player.getScoreboardName())
							|| (serverTeam != null && serverTeam.getPlayers().contains(player.getScoreboardName())));
			if (hasPlayer) {
				accessor.lg2$setNametagVisibility(Team.Visibility.NEVER);
			}
		}
	}

	public static Component withoutShadow(Component component) {
		if (component == null) {
			return null;
		}
		MutableComponent copy = component.copy();
		copy.setStyle(copy.getStyle().withShadowColor(0x00000000));
		List<Component> siblings = copy.getSiblings();
		for (int index = 0; index < siblings.size(); index++) {
			siblings.set(index, withoutShadow(siblings.get(index)));
		}
		return copy;
	}

	public record RewriteResult(ClientboundPlayerInfoUpdatePacket packet, List<UUID> removedProfileIds) {
	}
}
