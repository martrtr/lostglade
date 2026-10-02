package com.lostglade.server;

import com.lostglade.config.Lg2Config;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RendererBotPresenceSystem {
	private static final String HIDDEN_TEAM_NAME = "lg2_renderer_bot_hidden";
	private static final int EFFECT_REFRESH_THRESHOLD_TICKS = 80;
	private static final int EFFECT_DURATION_TICKS = 220;
	private static final Set<UUID> ONLINE_BOT_IDS = ConcurrentHashMap.newKeySet();

	private RendererBotPresenceSystem() {
	}

	public static void register() {
		ServerTabIntegration.registerRendererBotVanishIntegration(RendererBotPresenceSystem::shouldHideFromPlayerList);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			rebuildOnlineBotSet(server);
			ensureHiddenTeam(server.getScoreboard());
			enforceAllBots(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ONLINE_BOT_IDS.clear());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
			ServerPlayer player = handler.player;
			if (isRendererBot(player)) {
				ONLINE_BOT_IDS.add(player.getUUID());
				enforceBotState(player);
			}
		}));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ONLINE_BOT_IDS.remove(handler.player.getUUID()));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (isRendererBot(newPlayer)) {
				ONLINE_BOT_IDS.add(newPlayer.getUUID());
				enforceBotState(newPlayer);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if ((server.getTickCount() % 20) == 0) {
				enforceAllBots(server);
			}
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(rendererBotStatusCommand("rendererbot"));
			dispatcher.register(rendererBotStatusCommand("cameraclient"));
		});
	}

	public static boolean isRendererBot(ServerPlayer player) {
		return player != null && player.connection != null
				&& isRendererBotConnection(player.getScoreboardName(), player.connection.getRemoteAddress());
	}

	public static boolean isRendererBotConnection(String rawName, Connection connection) {
		return connection != null && isRendererBotConnection(rawName, connection.getRemoteAddress());
	}

	private static boolean isRendererBotConnection(String rawName, SocketAddress remoteAddress) {
		if (!isRendererBotName(rawName)) return false;
		if (!(remoteAddress instanceof InetSocketAddress socketAddress)) return false;
		InetAddress address = socketAddress.getAddress();
		return address != null && address.isLoopbackAddress();
	}

	public static boolean shouldHideFromPlayerList(ServerPlayer player) {
		return !AccountAuthSystem.isPresenceVisible(player)
				|| ServerRaceSystem.isMilkMouseActive(player);
	}

	public static boolean isRendererBotNameAndId(NameAndId entry) {
		return entry != null && ONLINE_BOT_IDS.contains(entry.id());
	}

	public static int getOnlineRendererBotCount() {
		return ONLINE_BOT_IDS.size();
	}

	public static ServerPlayer findOnlineRendererBot(MinecraftServer server) {
		if (server == null) {
			return null;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isRendererBot(player)) {
				return player;
			}
		}
		return null;
	}

	public static ServerStatus sanitizeStatus(ServerStatus original) {
		int pendingAuth = AccountAuthSystem.getPendingAuthenticationCount();
		if (original == null || (ONLINE_BOT_IDS.isEmpty() && pendingAuth == 0)) {
			return original;
		}

		Optional<ServerStatus.Players> playersOptional = original.players();
		if (playersOptional.isEmpty()) {
			return original;
		}

		ServerStatus.Players players = playersOptional.get();
		List<NameAndId> sample = players.sample();
		List<NameAndId> filteredSample = sample;
		if (!sample.isEmpty()) {
			filteredSample = new ArrayList<>(sample.size());
			for (NameAndId entry : sample) {
				if (!isRendererBotNameAndId(entry) && !AccountAuthSystem.isPendingAuthentication(entry.id())) {
					filteredSample.add(entry);
				}
			}
		}

		int adjustedOnline = Math.max(0, players.online() - ONLINE_BOT_IDS.size() - pendingAuth);
		if (adjustedOnline == players.online() && filteredSample == sample) {
			return original;
		}

		ServerStatus.Players sanitizedPlayers = new ServerStatus.Players(players.max(), adjustedOnline, filteredSample);
		return new ServerStatus(
				original.description(),
				Optional.of(sanitizedPlayers),
				original.version(),
				original.favicon(),
				original.enforcesSecureChat()
		);
	}

	private static void rebuildOnlineBotSet(MinecraftServer server) {
		ONLINE_BOT_IDS.clear();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isRendererBot(player)) {
				ONLINE_BOT_IDS.add(player.getUUID());
			}
		}
	}

	private static void enforceAllBots(MinecraftServer server) {
		rebuildOnlineBotSet(server);
		ensureHiddenTeam(server.getScoreboard());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (isRendererBot(player)) {
				enforceBotState(player);
			}
		}
	}

	private static void enforceBotState(ServerPlayer player) {
		MinecraftServer server = player == null || player.level() == null ? null : player.level().getServer();
		if (server == null) {
			return;
		}

		if (player.gameMode() != GameType.SPECTATOR) {
			player.setGameMode(GameType.SPECTATOR);
		}
		if (!player.isInvulnerable()) {
			player.setInvulnerable(true);
		}
		if (!player.isSilent()) {
			player.setSilent(true);
		}

		MobEffectInstance currentInvisibility = player.getEffect(MobEffects.INVISIBILITY);
		if (currentInvisibility == null || currentInvisibility.getDuration() < EFFECT_REFRESH_THRESHOLD_TICKS || currentInvisibility.isVisible()) {
			player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, EFFECT_DURATION_TICKS, 0, false, false, false));
		}

		ensureHiddenTeamMembership(server.getScoreboard(), player.getScoreboardName());
	}

	private static PlayerTeam ensureHiddenTeam(ServerScoreboard scoreboard) {
		PlayerTeam team = scoreboard.getPlayerTeam(HIDDEN_TEAM_NAME);
		if (team == null) {
			team = scoreboard.addPlayerTeam(HIDDEN_TEAM_NAME);
		}
		team.setDisplayName(net.minecraft.network.chat.Component.empty());
		team.setPlayerPrefix(net.minecraft.network.chat.Component.empty());
		team.setPlayerSuffix(net.minecraft.network.chat.Component.empty());
		team.setNameTagVisibility(Team.Visibility.NEVER);
		team.setDeathMessageVisibility(Team.Visibility.NEVER);
		team.setCollisionRule(Team.CollisionRule.NEVER);
		return team;
	}

	private static void ensureHiddenTeamMembership(ServerScoreboard scoreboard, String playerName) {
		if (playerName == null || playerName.isBlank()) {
			return;
		}

		PlayerTeam hiddenTeam = ensureHiddenTeam(scoreboard);
		Collection<String> currentPlayers = List.copyOf(hiddenTeam.getPlayers());
		for (String other : currentPlayers) {
			if (!playerName.equals(other) && isRendererBotName(other)) {
				scoreboard.removePlayerFromTeam(other, hiddenTeam);
			}
		}
		if (!hiddenTeam.getPlayers().contains(playerName)) {
			scoreboard.addPlayerToTeam(playerName, hiddenTeam);
		}
	}

	public static boolean isRendererBotName(String rawName) {
		String configured = configuredBotName();
		return configured != null && rawName != null && configured.equals(rawName.trim().toLowerCase(Locale.ROOT));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> rendererBotStatusCommand(String rootLiteral) {
		return Commands.literal(rootLiteral)
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
				.executes(context -> sendRendererBotStatus(context.getSource()))
				.then(Commands.literal("status")
						.executes(context -> sendRendererBotStatus(context.getSource())));
	}

	private static int sendRendererBotStatus(CommandSourceStack source) {
		String configuredName = configuredBotNameRaw();
		if (configuredName == null) {
			source.sendFailure(Component.literal("Camera client name is not configured."));
			return 0;
		}

		MinecraftServer server = source.getServer();
		ServerPlayer onlineBot = findOnlineRendererBot(server);
		ServerPlayer readyBot = RendererBotCameraSystem.readyBot(server);
		String message = "Camera client '" + configuredName + "': process="
				+ yesNo(RendererBotProcessSystem.isProcessRunning())
				+ ", online=" + yesNo(onlineBot != null)
				+ ", ready=" + yesNo(readyBot != null);
		source.sendSuccess(() -> Component.literal(message), false);
		return onlineBot != null ? 1 : 0;
	}

	private static String yesNo(boolean value) {
		return value ? "yes" : "no";
	}

	private static String configuredBotName() {
		String configured = Lg2Config.get().cameraRendererBotPlayerName;
		if (configured == null) {
			return null;
		}
		String trimmed = configured.trim();
		return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
	}

	private static String configuredBotNameRaw() {
		String configured = Lg2Config.get().cameraRendererBotPlayerName;
		if (configured == null) {
			return null;
		}
		String trimmed = configured.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
