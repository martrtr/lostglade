package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.mixin.PlayerListAccountAuthAccessor;
import com.lostglade.mixin.ServerPlayerAccountAuthAccessor;
import com.lostglade.mixin.ServerPlayerGameModeAccountAuthInvoker;
import com.lostglade.network.Lg2Payloads;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offline-account protection.  Passwords and client secrets are persisted as
 * one-way hashes in config/lg2-auth.json.  A connection is usable only after
 * a remembered client secret, a trusted IP, or a successful password login.
 */
public final class AccountAuthSystem {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = net.fabricmc.loader.api.FabricLoader.getInstance()
			.getConfigDir().resolve("lg2-auth.json");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int PASSWORD_ITERATIONS = 210_000;
	private static final int PASSWORD_KEY_BITS = 256;
	private static final int PROMPT_INTERVAL_TICKS = 40;
	private static final long CLIENT_TOKEN_GRACE_MILLIS = 1_500L;
	private static final int MAX_FAILED_ATTEMPTS = 5;
	private static final long FAILURE_WINDOW_MILLIS = 60_000L;

	public static final ResourceKey<Level> AUTH_LIMBO_LEVEL = ResourceKey.create(
			Registries.DIMENSION, Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "auth_limbo")
	);

	private static final Map<String, Account> ACCOUNTS = new ConcurrentHashMap<>();
	private static final Set<UUID> AUTHENTICATED = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> AUTH_TRANSITIONING = ConcurrentHashMap.newKeySet();
	/** Current connection identity for each human UUID; stale disconnects must never mutate a replacement session. */
	private static final Map<UUID, ServerPlayer> ACTIVE_HUMAN_SESSIONS = new ConcurrentHashMap<>();
	/** Set only after PlayerList.placeNewPlayer has returned and vanilla entity/chunk registration is complete. */
	private static final Map<UUID, ServerPlayer> PLACEMENT_COMPLETE = new ConcurrentHashMap<>();
	private static final Set<UUID> CONNECTED_HUMAN_SESSIONS = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> PRESENCE_VISIBLE = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> PRESENCE_PREANNOUNCED = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, Set<String>> PRESENCE_LABELS = new ConcurrentHashMap<>();
	private static final Map<UUID, Set<String>> INITIAL_JOIN_LABELS = new ConcurrentHashMap<>();
	private static final Set<UUID> PRESENCE_REMOVE_END_OF_TICK = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> VERIFIED_PREMIUM_SESSIONS = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> PREMIUM_TOKEN_BIND_PENDING = ConcurrentHashMap.newKeySet();
	/** Quarantine state is bound to a concrete connection/player object, never merely a UUID. */
	private static final Map<ServerPlayer, LimboState> LIMBO = new ConcurrentHashMap<>();
	private static final Map<Connection, AuthenticationReturnTarget> STAGED_RETURN_TARGETS = Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<UUID, List<Runnable>> AFTER_AUTH_ACTIONS = new ConcurrentHashMap<>();
	private static final Identifier VOICECHAT_REQUEST_SECRET = Identifier.fromNamespaceAndPath("voicechat", "request_secret");
	private static final Map<ServerPlayer, ServerboundCustomPayloadPacket> PENDING_VOICECHAT_SECRET_REQUESTS = new ConcurrentHashMap<>();
	private static MinecraftServer authenticatedPlayersCacheServer;
	private static int authenticatedPlayersCacheTick = Integer.MIN_VALUE;
	private static List<ServerPlayer> authenticatedPlayersCache = List.of();
	private static boolean authenticatedPlayersCacheDirty = true;
	private static final Set<Relative> ABSOLUTE_TELEPORT = EnumSet.noneOf(Relative.class);
	private static final double LIMBO_X = 0.5D;
	private static final double LIMBO_Y = 64.0D;
	private static final double LIMBO_Z = 0.5D;
	private static final Map<UUID, String> OFFERED_CLIENT_TOKENS = new HashMap<>();
	private static final Map<UUID, Long> CLIENT_TOKEN_GRACE_UNTIL = new HashMap<>();
	private static final Map<String, FailureState> FAILURES = new HashMap<>();
	private static int promptTicker;

	private AccountAuthSystem() {
	}

	/**
	 * A paused empty server cannot finish a login-phase session check: no
	 * ServerPlayer exists yet, so the pause is never lifted. Keep the normal
	 * game loop running whenever login protection is installed.
	 */
	public static void preflightServerProperties() {
		Path propertiesPath = net.fabricmc.loader.api.FabricLoader.getInstance()
				.getGameDir().resolve("server.properties");
		if (!Files.isRegularFile(propertiesPath)) return;
		try {
			List<String> lines = new ArrayList<>(Files.readAllLines(propertiesPath));
			boolean found = false;
			boolean changed = false;
			for (int i = 0; i < lines.size(); i++) {
				if (!lines.get(i).trim().startsWith("pause-when-empty-seconds=")) continue;
				found = true;
				if (!"pause-when-empty-seconds=-1".equals(lines.get(i))) {
					lines.set(i, "pause-when-empty-seconds=-1");
					changed = true;
				}
			}
			if (!found) {
				lines.add("pause-when-empty-seconds=-1");
				changed = true;
			}
			if (changed) {
				Files.write(propertiesPath, lines);
				Lg2.LOGGER.info("Adjusted {}: disabled empty-server pause required for login authentication", propertiesPath);
			}
		} catch (IOException exception) {
			Lg2.LOGGER.warn("Failed to disable empty-server pause in {}", propertiesPath, exception);
		}
	}

	public static void register() {
		load();
		// Do not authenticate from ServerPlayConnectionEvents.JOIN. Fabric fires JOIN in the middle of
		// PlayerList.placeNewPlayer, before vanilla adds the ServerPlayer to its level. A dimension
		// teleport from that callback corrupts the entity callback / DistanceManager bookkeeping.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.player;
			if (server.isSameThread()) clearSession(player, server);
			else server.execute(() -> clearSession(player, server));
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			UUID playerId = oldPlayer.getUUID();
			if (!ACTIVE_HUMAN_SESSIONS.replace(playerId, oldPlayer, newPlayer)) return;

			LimboState pendingState = LIMBO.remove(oldPlayer);
			if (pendingState != null || !AUTHENTICATED.contains(playerId)) {
				// A pre-auth player should be impossible to kill, but stale/corrupt client state from an
				// older build can still request a vanilla respawn. Never let that respawn escape quarantine.
				AUTHENTICATED.remove(playerId);
				AUTH_TRANSITIONING.remove(playerId);
				PLACEMENT_COMPLETE.put(playerId, newPlayer);
				if (pendingState != null) LIMBO.put(newPlayer, pendingState);
				retractAnnouncedPresence(newPlayer);
				invalidateAuthenticatedPlayersCache();
				newPlayer.connection.disconnect(Component.literal("Authentication session was reset during respawn. Please reconnect."));
				return;
			}

			PLACEMENT_COMPLETE.put(playerId, newPlayer);
			invalidateAuthenticatedPlayersCache();
		});
		ServerLifecycleEvents.SERVER_STARTED.register(AccountAuthSystem::warmAuthenticationWorld);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			save();
			ACTIVE_HUMAN_SESSIONS.clear();
			PLACEMENT_COMPLETE.clear();
			CONNECTED_HUMAN_SESSIONS.clear();
			AUTH_TRANSITIONING.clear();
			PRESENCE_VISIBLE.clear();
			PRESENCE_PREANNOUNCED.clear();
			PRESENCE_LABELS.clear();
			INITIAL_JOIN_LABELS.clear();
			PRESENCE_REMOVE_END_OF_TICK.clear();
			PREMIUM_TOKEN_BIND_PENDING.clear();
			AFTER_AUTH_ACTIONS.clear();
			PENDING_VOICECHAT_SECRET_REQUESTS.clear();
			STAGED_RETURN_TARGETS.clear();
			authenticatedPlayersCacheServer = null;
			authenticatedPlayersCacheTick = Integer.MIN_VALUE;
			authenticatedPlayersCache = List.of();
			authenticatedPlayersCacheDirty = true;
		});
		ServerTickEvents.END_SERVER_TICK.register(AccountAuthSystem::tickPrompts);

		ServerPlayNetworking.registerGlobalReceiver(Lg2Payloads.AuthTokenC2SPayload.TYPE, (payload, context) -> {
			String token = payload.token() == null ? "" : payload.token().trim();
			if (token.length() < 32 || token.length() > 128) return;
			context.server().execute(() -> acceptClientToken(context.player(), token));
		});

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> blocked(player));
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> blocked(player));
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> blocked(player));
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> blocked(player));
		UseItemCallback.EVENT.register((player, world, hand) -> blocked(player));
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> !isBlocked(player));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("password")
						.requires(source -> source.getEntity() instanceof ServerPlayer)
						.then(Commands.argument("new_password", StringArgumentType.string())
								.executes(context -> submitPassword(
										(ServerPlayer) context.getSource().getEntity(),
										StringArgumentType.getString(context, "new_password")
								) ? 1 : 0)
								.then(Commands.argument("player", StringArgumentType.word())
										.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
										.executes(context -> setPasswordByAdmin(
												context.getSource(),
												StringArgumentType.getString(context, "player"),
												StringArgumentType.getString(context, "new_password")
										))))
		));
	}

	private static InteractionResult blocked(net.minecraft.world.entity.player.Player player) {
		return isBlocked(player) ? InteractionResult.FAIL : InteractionResult.PASS;
	}

	public static boolean isBlocked(net.minecraft.world.entity.player.Player player) {
		return player instanceof ServerPlayer serverPlayer && shouldBlockPacket(serverPlayer);
	}

	public static boolean isAuthenticated(ServerPlayer player) {
		if (player == null) return false;
		if (RendererBotPresenceSystem.isRendererBot(player)) return true;
		UUID playerId = player.getUUID();
		return ACTIVE_HUMAN_SESSIONS.get(playerId) == player
				&& AUTHENTICATED.contains(playerId)
				&& !isAuthenticationWorld(player);
	}

	/** True only after the current human session has completed authentication and is allowed to exist publicly. */
	public static boolean isPresenceVisible(ServerPlayer player) {
		if (player == null || RendererBotPresenceSystem.isRendererBot(player)) return false;
		UUID playerId = player.getUUID();
		return ACTIVE_HUMAN_SESSIONS.get(playerId) == player && PRESENCE_VISIBLE.contains(playerId);
	}

	/** Profile-only preannouncement used to let remote clients construct the entity after auth succeeds. */
	public static boolean isPresencePreannounced(UUID playerId) {
		return playerId != null && PRESENCE_PREANNOUNCED.contains(playerId);
	}

	public static boolean isPendingAuthentication(UUID playerId) {
		return playerId != null && CONNECTED_HUMAN_SESSIONS.contains(playerId) && !PRESENCE_VISIBLE.contains(playerId);
	}

	public static int getPendingAuthenticationCount() {
		int count = 0;
		for (UUID playerId : CONNECTED_HUMAN_SESSIONS) {
			if (!PRESENCE_VISIBLE.contains(playerId)) count++;
		}
		return count;
	}

	/** Gameplay systems must never observe a human session before authentication. */
	public static List<ServerPlayer> authenticatedPlayers(MinecraftServer server) {
		if (server == null) return List.of();
		int tick = server.getTickCount();
		if (authenticatedPlayersCacheDirty
				|| authenticatedPlayersCacheServer != server
				|| authenticatedPlayersCacheTick != tick) {
			authenticatedPlayersCacheServer = server;
			authenticatedPlayersCacheTick = tick;
			authenticatedPlayersCache = server.getPlayerList().getPlayers().stream()
					.filter(AccountAuthSystem::isAuthenticated)
					.toList();
			authenticatedPlayersCacheDirty = false;
		}
		return authenticatedPlayersCache;
	}

	private static void invalidateAuthenticatedPlayersCache() {
		authenticatedPlayersCacheDirty = true;
	}

	/** Defers mod-specific JOIN initialization until the real server is visible to this player. */
	public static void runWhenAuthenticated(ServerPlayer player, Runnable action) {
		if (player == null || action == null) return;
		if (isAuthenticated(player)) {
			action.run();
			return;
		}
		AFTER_AUTH_ACTIONS.compute(player.getUUID(), (ignored, queued) -> {
			List<Runnable> actions = queued == null ? new ArrayList<>() : queued;
			actions.add(action);
			return actions;
		});
		// Covers a same-tick race where authentication completed between the first check and enqueue.
		if (isAuthenticated(player)) runAfterAuthActions(player);
	}

	private static void runAfterAuthActions(ServerPlayer player) {
		List<Runnable> actions = AFTER_AUTH_ACTIONS.remove(player.getUUID());
		if (actions == null) return;
		for (Runnable action : actions) {
			try {
				action.run();
			} catch (RuntimeException exception) {
				Lg2.LOGGER.error("Deferred post-auth join action failed for {}", player.getScoreboardName(), exception);
			}
		}
	}

	/** Suppresses vanilla join/leave announcements for sessions that never passed authentication. */
	public static boolean shouldSuppressPresenceSystemMessage(Component message) {
		if (message == null || !(message.getContents() instanceof TranslatableContents contents)) return false;
		String translationKey = contents.getKey();
		boolean join = translationKey.startsWith("multiplayer.player.joined");
		if (!join && !"multiplayer.player.left".equals(translationKey)) return false;

		for (Object argument : contents.getArgs()) {
			String argumentText = argument instanceof Component component
					? component.getString()
					: argument == null ? "" : String.valueOf(argument);
			String normalized = argumentText.toLowerCase(Locale.ROOT);
			if (join) {
				for (Map.Entry<UUID, Set<String>> entry : INITIAL_JOIN_LABELS.entrySet()) {
					if (entry.getValue().contains(normalized) && INITIAL_JOIN_LABELS.remove(entry.getKey(), entry.getValue())) {
						return true;
					}
				}
			}
			for (Set<String> labels : PRESENCE_LABELS.values()) {
				if (labels.contains(normalized)) return false;
			}
		}
		return true;
	}

	/** Called only by the login-phase vanilla session verification mixin. */
	public static void noteVerifiedPremiumSession(String name, UUID officialUuid) {
		if (officialUuid != null) VERIFIED_PREMIUM_SESSIONS.add(officialUuid);
	}

	/**
	 * Marks a nickname as protected only after Mojang session verification actually succeeded on this server.
	 * A public profile/name lookup must never call this method.
	 */
	public static synchronized void reservePremiumName(String name, UUID officialUuid) {
		if (!validName(name) || officialUuid == null) return;
		String key = key(name);
		Account account = ACCOUNTS.computeIfAbsent(key, ignored -> new Account());
		boolean newlyVerified = !account.premiumVerified || !officialUuid.toString().equals(account.premiumUuid);
		account.premiumUuid = officialUuid.toString();
		account.premiumVerified = true;
		if (newlyVerified) {
			// A real premium owner supersedes any earlier self-registered offline account of the same name.
			account.offlinePasswordAllowed = false;
			account.passwordSalt = null;
			account.passwordHash = null;
			account.trustedIps.clear();
			account.clientTokenHash = null;
			account.createdAt = Instant.now().toString();
		}
		save();
	}


	public static UUID knownPremiumUuid(String name) {
		Account account = ACCOUNTS.get(key(name));
		if (account == null || !account.premiumVerified || account.premiumUuid == null) return null;
		try {
			return UUID.fromString(account.premiumUuid);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	public static boolean isInLimbo(ServerPlayer player) {
		return player != null && LIMBO.containsKey(player);
	}

	/** The technical authentication dimension must never become normal gameplay state. */
	public static boolean isAuthenticationWorld(ServerPlayer player) {
		return player != null && AUTH_LIMBO_LEVEL.equals(player.level().dimension());
	}

	/** True only during the synchronous, already-approved hop from auth_limbo to the real level. */
	public static boolean isAuthTransitioning(ServerPlayer player) {
		return player != null && AUTH_TRANSITIONING.contains(player.getUUID());
	}

	public static ChunkTrackingView createLimboChunkTrackingView(ServerPlayer player) {
		if (player == null) return ChunkTrackingView.EMPTY;
		// A value object is essential here: ChunkMap asks for this view repeatedly. An anonymous
		// object made every call compare unequal and re-applied the same chunk subscription forever.
		return new SingleChunkTrackingView(player.chunkPosition());
	}

	/** Used by the packet mixin as the final guard for movement and inventory packets. */
	public static boolean shouldBlockPacket(ServerPlayer player) {
		return player != null && !RendererBotPresenceSystem.isRendererBot(player) && !isAuthenticated(player);
	}

	/** Voice Chat requests its secret once on join, before password authentication finishes. */
	public static boolean deferVoicechatSecretRequest(ServerPlayer player, ServerboundCustomPayloadPacket packet) {
		if (player == null || packet == null || packet.payload() == null
				|| !VOICECHAT_REQUEST_SECRET.equals(packet.payload().type().id())) return false;
		PENDING_VOICECHAT_SECRET_REQUESTS.putIfAbsent(player, packet);
		if (isAuthenticated(player)) {
			ServerboundCustomPayloadPacket pending = PENDING_VOICECHAT_SECRET_REQUESTS.remove(player);
			if (pending != null) player.level().getServer().execute(() -> {
				if (isAuthenticated(player)) player.connection.handleCustomPayload(pending);
			});
		}
		return true;
	}

	/**
	 * Network quarantine for the play phase. Before authentication the client receives only protocol
	 * liveness, the auth resource pack/title, and packets needed to enter its private empty limbo chunk.
	 * Everything belonging to the real server (tab, chat, inventory, XP, scoreboard, entities,
	 * recipes, advancements, boss bars, sounds, particles, commands, etc.) is discarded.
	 */
	public static Packet<?> filterUnauthenticatedOutgoingPacket(ServerPlayer receiver, Packet<?> packet) {
		if (receiver == null || packet == null || isAuthenticated(receiver) || isAuthTransitioning(receiver)) return packet;
		if (packet instanceof ClientboundBundlePacket bundlePacket) {
			List<Packet<? super ClientGamePacketListener>> allowed = new ArrayList<>();
			boolean changed = false;
			for (Packet<? super ClientGamePacketListener> child : bundlePacket.subPackets()) {
				Packet<?> filtered = filterUnauthenticatedOutgoingPacket(receiver, child);
				if (filtered == null) {
					changed = true;
					continue;
				}
				if (filtered != child) changed = true;
				@SuppressWarnings("unchecked")
				Packet<? super ClientGamePacketListener> gamePacket = (Packet<? super ClientGamePacketListener>) filtered;
				allowed.add(gamePacket);
			}
			if (!changed) return packet;
			return allowed.isEmpty() ? null : new ClientboundBundlePacket(allowed);
		}
		if (packet instanceof ClientboundKeepAlivePacket
				|| packet instanceof ClientboundPingPacket
				|| packet instanceof ClientboundDisconnectPacket
				|| packet instanceof ClientboundResourcePackPushPacket
				|| packet instanceof ClientboundResourcePackPopPacket
				|| packet instanceof ClientboundLoginPacket
				|| packet instanceof ClientboundSetChunkCacheRadiusPacket
				|| packet instanceof ClientboundSetSimulationDistancePacket) {
			return packet;
		}
		if (packet instanceof ClientboundSetTitleTextPacket titlePacket) {
			return isAuthenticationMessage(receiver, titlePacket.text()) ? packet : null;
		}
		if (packet instanceof ClientboundSetTitlesAnimationPacket) {
			return isInLimbo(receiver) ? packet : null;
		}
		if (packet instanceof ClientboundClearTitlesPacket) {
			return isInLimbo(receiver) ? packet : null;
		}
		// 1.21.11 keeps the terrain-loading screen open until this exact marker arrives.
		// It carries no real-world data; all other game events remain quarantined.
		if (packet instanceof ClientboundGameEventPacket gameEventPacket) {
			if (gameEventPacket.getEvent() == ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START) return packet;
			// Auth limbo is intentionally spectator-only. Permit exactly that game-mode update so
			// the client cannot remain visually in survival while the server quarantines it as spectator.
			if (isInLimbo(receiver)
					&& gameEventPacket.getEvent() == ClientboundGameEventPacket.CHANGE_GAME_MODE
					&& Float.compare(gameEventPacket.getParam(), GameType.SPECTATOR.getId()) == 0) {
				return packet;
			}
			return null;
		}
		if (packet instanceof ClientboundPlayerAbilitiesPacket abilitiesPacket) {
			boolean limboAbilities = abilitiesPacket.isInvulnerable()
					&& abilitiesPacket.isFlying()
					&& abilitiesPacket.canFly()
					&& !abilitiesPacket.canInstabuild()
					&& Float.compare(abilitiesPacket.getFlyingSpeed(), 0.05F) == 0
					&& Float.compare(abilitiesPacket.getWalkingSpeed(), 0.1F) == 0;
			return isInLimbo(receiver) && limboAbilities ? packet : null;
		}
		if (packet instanceof ClientboundRespawnPacket
				|| packet instanceof ClientboundPlayerPositionPacket
				|| packet instanceof ClientboundSetChunkCacheCenterPacket
				|| packet instanceof ClientboundChunkBatchStartPacket
				|| packet instanceof ClientboundChunkBatchFinishedPacket
				|| packet instanceof ClientboundLevelChunkWithLightPacket
				|| packet instanceof ClientboundLightUpdatePacket
				|| packet instanceof ClientboundForgetLevelChunkPacket
				|| packet instanceof ClientboundChunksBiomesPacket) {
			return isInLimbo(receiver) ? packet : null;
		}
		return null;
	}

	/** Only /password is meaningful before login. */
	public static boolean shouldBlockCommand(ServerPlayer player, String command) {
		if (isAuthenticated(player)) return false;
		String verb = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
		return !(verb.equals("password") || verb.startsWith("password "));
	}

	/** Called from PlayerList.placeNewPlayer RETURN, after vanilla registered the entity and chunk tracking. */
	public static void onPlayerPlacementComplete(ServerPlayer player) {
		if (player == null || RendererBotPresenceSystem.isRendererBot(player)) return;
		UUID playerId = player.getUUID();
		if (ACTIVE_HUMAN_SESSIONS.get(playerId) != player) return;
		PLACEMENT_COMPLETE.put(playerId, player);
		CONNECTED_HUMAN_SESSIONS.add(playerId);
		// Pin the client cache to its isolated limbo chunk before it processes terrain.
		if (isInLimbo(player) && player.connection != null) {
			var center = player.chunkPosition();
			player.connection.send(new ClientboundSetChunkCacheCenterPacket(center.x, center.z));
		}
		if (VERIFIED_PREMIUM_SESSIONS.remove(player.getUUID())) {
			reservePremiumName(player.getScoreboardName(), player.getUUID());
			// The Mojang-verified session is authoritative. Its companion-mod token is
			// allowed to establish/rotate the offline fallback identity exactly once.
			PREMIUM_TOKEN_BIND_PENDING.add(player.getUUID());
			authorize(player);
			return;
		}
		Account account = accountFor(player);
		if (account != null && account.clientTokenHash != null) {
			// Prefer the installed client identity over IP. Give the client a brief
			// play-phase window to present its persisted token; if it does not,
			// a previously trusted IP remains a compatibility fallback.
			ensureLimboIsolation(player);
			CLIENT_TOKEN_GRACE_UNTIL.put(player.getUUID(), System.currentTimeMillis() + CLIENT_TOKEN_GRACE_MILLIS);
			return;
		}
		if (account != null && account.canUseOfflineAccess() && account.trustedIps.contains(ipHash(player))) {
			authorize(player);
			return;
		}
		ensureLimboIsolation(player);
		sendPrompt(player);
	}

	private static void acceptClientToken(ServerPlayer player, String token) {
		if (player == null) return;
		Account account = accountFor(player);
		if (account == null) {
			// The first accepted password binds this local secret to the new account.
			if (!isAuthenticated(player)) OFFERED_CLIENT_TOKENS.put(player.getUUID(), token);
			return;
		}
		String tokenHash = sha256(token);
		if (isAuthenticated(player)) {
			if (account.premiumVerified && PREMIUM_TOKEN_BIND_PENDING.remove(player.getUUID())) {
				// A freshly Mojang-verified login may rotate the fallback token. This also
				// replaces any token belonging to an older offline occupant of the name.
				if (!constantTimeEquals(account.clientTokenHash, tokenHash)) {
					account.clientTokenHash = tokenHash;
					save();
				}
				return;
			}
			// Non-premium sessions may bind the first token after password/IP auth,
			// but cannot silently replace an established identity.
			if (account.clientTokenHash == null) {
				account.clientTokenHash = tokenHash;
				save();
			}
			return;
		}
		if (constantTimeEquals(account.clientTokenHash, tokenHash)) {
			authorize(player);
		}
	}

	/**
	 * Consumes raw chat before vanilla/Fabric chat processing so a password can never leak to chat listeners.
	 * Network handlers enter on the Netty event loop before vanilla hops to the server thread, so all auth
	 * state changes are explicitly scheduled onto the Minecraft server thread.
	 */
	public static boolean consumeUnauthenticatedChat(ServerPlayer player, String message) {
		if (player == null || isAuthenticated(player)) return false;
		MinecraftServer server = player.level().getServer();
		if (server == null) return true;
		String password = message == null ? "" : message;
		server.execute(() -> {
			if (!player.isRemoved() && !isAuthenticated(player)) submitPassword(player, password);
		});
		return true;
	}

	/** Accepts a bare chat message before login, or /password in any session. */
	private static boolean submitPassword(ServerPlayer player, String password) {
		if (player == null) return false;
		if (RendererBotPresenceSystem.isRendererBot(player)) return true;
		String name = key(player.getScoreboardName());
		Account account = ACCOUNTS.get(name);
		if (account == null) {
			account = Account.fromPassword(password);
			account.trustedIps.add(ipHash(player));
			bindOfferedClientToken(player, account);
			ACCOUNTS.put(name, account);
			save();
			authorize(player, "success_created");
			return true;
		}
		if (!account.canUseOfflineAccess()) {
			authTitle(player, "premium_password_admin");
			return false;
		}
		if (isAuthenticated(player)) {
			if (account.premiumVerified) {
				authTitle(player, "premium_password_admin");
				return false;
			}
			account.replacePassword(password);
			save();
			authTitle(player, "success_changed");
			return true;
		}
		if (isLocked(player)) return false;
		if (!verifyPassword(password, account)) {
			recordFailure(player);
			authTitle(player, "wrong_password");
			return false;
		}
		FAILURES.remove(key(player.getScoreboardName()));
		account.trustedIps.add(ipHash(player));
		bindOfferedClientToken(player, account);
		save();
		authorize(player, "success_login");
		return true;
	}

	private static void bindOfferedClientToken(ServerPlayer player, Account account) {
		String offeredToken = OFFERED_CLIENT_TOKENS.remove(player.getUUID());
		if (offeredToken != null && account.clientTokenHash == null) account.clientTokenHash = sha256(offeredToken);
	}

	/** /password <new_password> <player>; intended for operators recovering an account. */
	private static int setPasswordByAdmin(net.minecraft.commands.CommandSourceStack source, String playerName, String password) {
		if (!validName(playerName)) {
			source.sendFailure(Component.literal("Недопустимый ник игрока."));
			return 0;
		}
		// A recovery is a security reset: old IP and local-client access must not survive it.
		Account replacement = Account.fromPassword(password);
		Account old = ACCOUNTS.get(key(playerName));
		if (old != null && old.premiumVerified && old.premiumUuid != null) {
			replacement.premiumUuid = old.premiumUuid;
			replacement.premiumVerified = true;
		}
		// An administrator is the only actor allowed to enable password fallback for a premium name.
		// For ordinary accounts this is also the expected recovery behavior.
		replacement.offlinePasswordAllowed = true;
		ACCOUNTS.put(key(playerName), replacement);
		FAILURES.remove(key(playerName));
		save();
		MinecraftServer server = source.getServer();
		ServerPlayer onlinePlayer = server == null ? null : server.getPlayerList().getPlayerByName(playerName);
		if (onlinePlayer != null) {
			onlinePlayer.connection.disconnect(Component.literal("Authentication credentials were reset by an administrator. Please reconnect."));
		}
		source.sendSuccess(() -> Component.literal("Пароль игрока " + playerName + " установлен; старые IP и токены отозваны."), true);
		return 1;
	}

	private static boolean validName(String name) {
		return name != null && name.matches("[A-Za-z0-9_]{3,16}");
	}

	private static boolean isLocked(ServerPlayer player) {
		String failureKey = key(player.getScoreboardName());
		FailureState state = FAILURES.get(failureKey);
		if (state == null) return false;
		if (System.currentTimeMillis() - state.windowStartedAt > FAILURE_WINDOW_MILLIS) {
			FAILURES.remove(failureKey);
			return false;
		}
		if (state.count < MAX_FAILED_ATTEMPTS) return false;
		authTitle(player, "too_many_attempts");
		return true;
	}

	private static void recordFailure(ServerPlayer player) {
		long now = System.currentTimeMillis();
		String failureKey = key(player.getScoreboardName());
		FailureState old = FAILURES.get(failureKey);
		if (old == null || now - old.windowStartedAt > FAILURE_WINDOW_MILLIS) {
			FAILURES.put(failureKey, new FailureState(now, 1));
		} else {
			FAILURES.put(failureKey, new FailureState(old.windowStartedAt, old.count + 1));
		}
	}

	private static Set<String> playerPresenceLabels(ServerPlayer player) {
		Set<String> labels = new HashSet<>();
		if (player == null) return Set.of();
		String scoreboardName = player.getScoreboardName();
		if (scoreboardName != null && !scoreboardName.isBlank()) labels.add(scoreboardName.toLowerCase(Locale.ROOT));
		String displayName = player.getDisplayName() == null ? "" : player.getDisplayName().getString();
		if (!displayName.isBlank()) labels.add(displayName.toLowerCase(Locale.ROOT));
		return Set.copyOf(labels);
	}

	/**
	 * Announces the newly authenticated profile before its cross-dimension move.
	 * The client requires this packet before the entity-spawn packet that the
	 * destination ChunkMap sends during {@link ServerPlayer#teleportTo}; sending
	 * it afterwards leaves the player model absent until a later resync.
	 */
	private static void announcePresenceBeforeTeleport(ServerPlayer player) {
		UUID playerId = player.getUUID();
		PRESENCE_REMOVE_END_OF_TICK.remove(playerId);
		PRESENCE_PREANNOUNCED.add(playerId);

		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		ClientboundPlayerInfoUpdatePacket profileOnly = ServerTabPacketSystem.createUnlistedPlayerInitializingPacket(player);
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (viewer != player && isAuthenticated(viewer)) viewer.connection.send(profileOnly);
		}
	}

	/** Completes the normal player-list sync after the player has reached the real world. */
	private static void publishPresenceToPlayer(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		List<ServerPlayer> publicPlayers = server.getPlayerList().getPlayers().stream()
				.filter(AccountAuthSystem::isPresenceVisible)
				.toList();
		if (!publicPlayers.isEmpty()) {
			player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(publicPlayers));
		}
	}

	/** Makes the already-authenticated player public only after the real-world teleport completed. */
	private static void finalizePresence(ServerPlayer player) {
		UUID playerId = player.getUUID();
		PRESENCE_PREANNOUNCED.remove(playerId);
		PRESENCE_REMOVE_END_OF_TICK.remove(playerId);
		PRESENCE_VISIBLE.add(playerId);
		PRESENCE_LABELS.put(playerId, playerPresenceLabels(player));

		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		ClientboundPlayerInfoUpdatePacket announcement = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (viewer != player && isAuthenticated(viewer)) viewer.connection.send(announcement);
		}
		publishPresenceToPlayer(player);
	}

	/** Retracts the pre-teleport profile if the transition could not complete. */
	private static void retractAnnouncedPresence(ServerPlayer player) {
		if (player == null) return;
		UUID playerId = player.getUUID();
		PRESENCE_PREANNOUNCED.remove(playerId);
		removePresence(playerId);
		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		ClientboundPlayerInfoRemovePacket removal = new ClientboundPlayerInfoRemovePacket(List.of(playerId));
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (viewer != player && isAuthenticated(viewer)) viewer.connection.send(removal);
		}
	}

	private static void removePresence(UUID playerId) {
		if (playerId == null) return;
		PRESENCE_VISIBLE.remove(playerId);
		PRESENCE_LABELS.remove(playerId);
	}

	private static void resyncGameplayState(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		try {
			server.getPlayerList().sendLevelInfo(player, player.level());
			server.getPlayerList().sendPlayerPermissionLevel(player);
			server.getCommands().sendCommands(player);
			server.getPlayerList().sendAllPlayerInfo(player);
			player.connection.send(new ClientboundUpdateRecipesPacket(
					server.getRecipeManager().getSynchronizedItemProperties(),
					server.getRecipeManager().getSynchronizedStonecutterRecipes()
			));
			player.getRecipeBook().sendInitialRecipeBook(player);
			player.getStats().markAllDirty();
			((PlayerListAccountAuthAccessor) server.getPlayerList()).lg2$updateEntireScoreboard(server.getScoreboard(), player);
			server.getCustomBossEvents().onPlayerConnect(player);
			server.getPlayerList().sendActivePlayerEffects(player);
			player.getAdvancements().flushDirty(player, true);
			player.resetSentInfo();
			((ServerPlayerAccountAuthAccessor) player).lg2$updatePlayerAttributes();
			player.connection.send(new ClientboundSetHealthPacket(
					player.getHealth(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel()
			));
			player.onUpdateAbilities();
			player.inventoryMenu.broadcastFullState();
			if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastFullState();
		} catch (RuntimeException exception) {
			Lg2.LOGGER.error("Failed to resynchronize gameplay state after authenticating {}", player.getScoreboardName(), exception);
		}
	}

	private static void broadcastAuthenticatedJoin(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		// placeNewPlayer has already returned, so no vanilla initial join can still be pending.
		// Ensure our post-auth announcement is never mistaken for that suppressed pre-auth message.
		INITIAL_JOIN_LABELS.remove(player.getUUID());
		server.getPlayerList().broadcastSystemMessage(
				Component.translatable("multiplayer.player.joined", player.getDisplayName())
						.withStyle(ChatFormatting.YELLOW), false
		);
	}

	/** Automatic authentication (premium session, client token or trusted IP) is intentionally silent. */
	private static void authorize(ServerPlayer player) {
		authorize(player, null);
	}

	/** Pass a message key only when the player explicitly completed an authentication step. */
	private static void authorize(ServerPlayer player, String messageKey) {
		if (player == null || isAuthenticated(player) || isAuthTransitioning(player)) return;
		UUID playerId = player.getUUID();
		if (ACTIVE_HUMAN_SESSIONS.get(playerId) != player || PLACEMENT_COMPLETE.get(playerId) != player) {
			Lg2.LOGGER.warn("Ignored premature/stale authentication attempt for {}", player.getScoreboardName());
			return;
		}

		// Keep gameplay systems blind to this player until the cross-dimension hop has really finished.
		// Only the network/chunk quarantine is relaxed during this short synchronous transition.
		if (!AUTH_TRANSITIONING.add(playerId)) return;
		boolean leftLimbo;
		try {
			// A RemotePlayer needs its player-list entry before ChunkMap emits the spawn packet.
			announcePresenceBeforeTeleport(player);
			leftLimbo = leaveLimbo(player, true);
		} finally {
			AUTH_TRANSITIONING.remove(playerId);
		}
		if (!leftLimbo) {
			retractAnnouncedPresence(player);
			Lg2.LOGGER.error("Authentication transition failed for {}; keeping the player quarantined", player.getScoreboardName());
			player.connection.disconnect(Component.literal("Authentication transition failed. Please reconnect."));
			return;
		}
		if (!AUTHENTICATED.add(playerId)) return;

		invalidateAuthenticatedPlayersCache();
		ServerboundCustomPayloadPacket voicechatRequest = PENDING_VOICECHAT_SECRET_REQUESTS.remove(player);
		if (voicechatRequest != null) player.connection.handleCustomPayload(voicechatRequest);
		OFFERED_CLIENT_TOKENS.remove(player.getUUID());
		CLIENT_TOKEN_GRACE_UNTIL.remove(player.getUUID());
		FAILURES.remove(key(player.getScoreboardName()));
		clearAuthTitle(player);
		finalizePresence(player);
		resyncGameplayState(player);
		// The raw play JOIN happens while human players are still quarantined, so its
		// custom renderer manifest packet is intentionally filtered. Send it again only
		// after authentication is complete and the network quarantine has been lifted.
		RendererModAllowlist.sendManifest(player);
		runAfterAuthActions(player);
		if (messageKey != null) authTitle(player, messageKey);
		broadcastAuthenticatedJoin(player);
	}

	private static void clearSession(ServerPlayer player, MinecraftServer server) {
		if (player == null) return;
		UUID playerId = player.getUUID();
		// A delayed disconnect belonging to a replaced connection must never clear the new session.
		if (!ACTIVE_HUMAN_SESSIONS.remove(playerId, player)) return;
		PENDING_VOICECHAT_SECRET_REQUESTS.remove(player);
		PLACEMENT_COMPLETE.remove(playerId, player);
		invalidateAuthenticatedPlayersCache();
		CONNECTED_HUMAN_SESSIONS.remove(playerId);
		INITIAL_JOIN_LABELS.remove(playerId);
		AUTHENTICATED.remove(playerId);
		AUTH_TRANSITIONING.remove(playerId);
		PRESENCE_PREANNOUNCED.remove(playerId);
		VERIFIED_PREMIUM_SESSIONS.remove(playerId);
		PREMIUM_TOKEN_BIND_PENDING.remove(playerId);
		OFFERED_CLIENT_TOKENS.remove(playerId);
		CLIENT_TOKEN_GRACE_UNTIL.remove(playerId);
		AFTER_AUTH_ACTIONS.remove(playerId);
		// Do NOT remove this concrete player's LIMBO state here. PlayerList.remove saves playerdata
		// after the disconnect event, and rewriteLimboPersistentState still needs the real Dimension/Pos.
		// PlayerListAccountAuthMixin removes the state at PlayerList.remove RETURN instead.
		// Vanilla broadcasts the leave message during the disconnect path. Keep the presence marker until
		// the end of this server tick so an authenticated player's leave message is not accidentally hidden.
		if (PRESENCE_VISIBLE.contains(playerId)) PRESENCE_REMOVE_END_OF_TICK.add(playerId);
		else removePresence(playerId);
	}

	/** Called after vanilla finished PlayerList.remove, including its playerdata save. */
	public static void onPlayerRemoved(ServerPlayer player) {
		if (player == null) return;
		LIMBO.remove(player);
	}

	private static void enforceSessionInvariants(MinecraftServer server) {
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			if (player == null || RendererBotPresenceSystem.isRendererBot(player)) continue;
			UUID playerId = player.getUUID();
			ServerPlayer activePlayer = ACTIVE_HUMAN_SESSIONS.get(playerId);

			if (activePlayer != player) {
				// A human that is physically visible to PlayerList but has no current auth session must
				// never be allowed to idle forever in the technical dimension. Fail closed.
				if (isAuthenticationWorld(player)) {
					Lg2.LOGGER.error("Authentication-world player {} has no active session; disconnecting", player.getScoreboardName());
					player.connection.disconnect(Component.literal("Authentication state was lost. Please reconnect."));
				}
				continue;
			}

			if (PLACEMENT_COMPLETE.get(playerId) != player) {
				if (isAuthenticationWorld(player) && isInLimbo(player)) {
					// END_SERVER_TICK can only run after placeNewPlayer returned. If its RETURN injection
					// was missed by another mixin/control-flow edge case, it is now safe to finish auth setup.
					Lg2.LOGGER.warn("Recovering missed authentication placement completion for {}", player.getScoreboardName());
					onPlayerPlacementComplete(player);
				} else {
					Lg2.LOGGER.error("Incomplete authentication placement state for {}; disconnecting", player.getScoreboardName());
					player.connection.disconnect(Component.literal("Authentication initialization failed. Please reconnect."));
				}
				continue;
			}

			if (isAuthenticationWorld(player)) {
				if (!isInLimbo(player)) {
					// Never allow a state-less human to remain forever in the empty technical dimension.
					LimboState recovery = captureReturnState(player);
					LIMBO.put(player, recovery);
					AUTHENTICATED.remove(playerId);
					retractAnnouncedPresence(player);
					Lg2.LOGGER.error("Lost authentication quarantine state for {}; disconnecting with a safe persistent return target",
							player.getScoreboardName());
					player.connection.disconnect(Component.literal("Authentication state was lost. Please reconnect."));
					continue;
				}
				if (AUTHENTICATED.remove(playerId) || PRESENCE_VISIBLE.contains(playerId)) {
					retractAnnouncedPresence(player);
					invalidateAuthenticatedPlayersCache();
					Lg2.LOGGER.error("Recovered impossible authenticated-in-limbo state for {}", player.getScoreboardName());
				}
				continue;
			}

			if (!AUTHENTICATED.contains(playerId) && !AUTH_TRANSITIONING.contains(playerId)) {
				Lg2.LOGGER.error("Unauthenticated player {} escaped authentication limbo; disconnecting", player.getScoreboardName());
				player.connection.disconnect(Component.literal("Authentication isolation failed. Please reconnect."));
			}
		}
	}

	private static void tickPrompts(MinecraftServer server) {
		enforceSessionInvariants(server);
		// Vanilla's initial join broadcast is synchronous with player placement. Any marker that
		// survived until END_SERVER_TICK was not needed and must not suppress a later auth join.
		INITIAL_JOIN_LABELS.clear();
		long now = System.currentTimeMillis();
		FAILURES.entrySet().removeIf(entry -> now - entry.getValue().windowStartedAt > FAILURE_WINDOW_MILLIS);
		if (!PRESENCE_REMOVE_END_OF_TICK.isEmpty()) {
			for (UUID playerId : List.copyOf(PRESENCE_REMOVE_END_OF_TICK)) {
				PRESENCE_REMOVE_END_OF_TICK.remove(playerId);
				removePresence(playerId);
			}
		}
		if (++promptTicker % PROMPT_INTERVAL_TICKS != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!isAuthenticated(player)) sendPrompt(player);
		}
	}

	private static void sendPrompt(ServerPlayer player) {
		Long graceUntil = CLIENT_TOKEN_GRACE_UNTIL.get(player.getUUID());
		if (graceUntil != null) {
			if (graceUntil > System.currentTimeMillis()) return;
			CLIENT_TOKEN_GRACE_UNTIL.remove(player.getUUID(), graceUntil);
			Account graceAccount = accountFor(player);
			if (graceAccount != null && graceAccount.canUseOfflineAccess()
					&& graceAccount.trustedIps.contains(ipHash(player))) {
				authorize(player);
				return;
			}
		}
		Account account = accountFor(player);
		if (account != null && !account.canUseOfflineAccess()) authTitle(player, "premium_password_admin");
		else authTitle(player, account == null ? "prompt_new" : "prompt_login");
	}


	private static boolean isAuthenticationMessage(ServerPlayer player, Component content) {
		if (player == null || content == null) return false;
		String text = content.getString();
		return text.equals(localize(player, "prompt_new"))
				|| text.equals(localize(player, "prompt_login"))
				|| text.equals(localize(player, "wrong_password"))
				|| text.equals(localize(player, "too_many_attempts"))
				|| text.equals(localize(player, "premium_password_admin"));
	}

	private static void authTitle(ServerPlayer player, String messageKey) {
		if (player == null || player.connection == null) return;
		player.connection.send(new ClientboundSetTitlesAnimationPacket(0, PROMPT_INTERVAL_TICKS + 20, 5));
		player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(localize(player, messageKey))));
	}

	private static void clearAuthTitle(ServerPlayer player) {
		if (player != null && player.connection != null) {
			player.connection.send(new ClientboundClearTitlesPacket(true));
		}
	}

	private static Account accountFor(ServerPlayer player) {
		return player == null ? null : ACCOUNTS.get(key(player.getScoreboardName()));
	}

	private static String key(String name) {
		return name == null ? "" : name.toLowerCase(Locale.ROOT);
	}

	private static void warmAuthenticationWorld(MinecraftServer server) {
		if (server == null) return;
		ServerLevel level = server.getLevel(AUTH_LIMBO_LEVEL);
		if (level == null) {
			Lg2.LOGGER.error("Authentication limbo dimension {} is unavailable", AUTH_LIMBO_LEVEL.identifier());
			return;
		}
		level.getChunkAt(BlockPos.containing(LIMBO_X, LIMBO_Y, LIMBO_Z));
	}

	public static Vec3 authenticationSpawnPosition() {
		return new Vec3(LIMBO_X, LIMBO_Y, LIMBO_Z);
	}

	/** Saves the real vanilla spawn selected before the human player is constructed in auth_limbo. */
	public static void stageAuthenticationReturnTarget(Connection connection, ServerLevel level, Vec3 position, Vec2 angle) {
		if (connection == null || level == null || position == null || angle == null) return;
		ServerLevel targetLevel = level;
		Vec3 targetPosition = position;
		Vec2 targetAngle = angle;
		if (AUTH_LIMBO_LEVEL.equals(level.dimension())) {
			MinecraftServer server = level.getServer();
			targetLevel = server.overworld();
			BlockPos spawn = targetLevel.getRespawnData().pos();
			targetPosition = new Vec3(spawn.getX() + 0.5D, spawn.getY() + 0.1D, spawn.getZ() + 0.5D);
			targetAngle = new Vec2(targetLevel.getRespawnData().yaw(), targetLevel.getRespawnData().pitch());
		}
		STAGED_RETURN_TARGETS.put(connection, new AuthenticationReturnTarget(targetLevel, targetPosition, targetAngle));
	}

	/**
	 * Initializes quarantine bookkeeping for a human that vanilla already created directly in auth_limbo.
	 * No level mutation is allowed here: PlayerList must register the entity exactly once in that level.
	 */
	public static boolean preparePlayerPlacement(ServerPlayer player, Connection connection) {
		invalidateAuthenticatedPlayersCache();
		if (player == null) return false;
		if (RendererBotPresenceSystem.isRendererBotConnection(player.getScoreboardName(), connection)) return true;
		UUID playerId = player.getUUID();

		if (!AUTH_LIMBO_LEVEL.equals(player.level().dimension())) {
			Lg2.LOGGER.error("Human player {} was not constructed in authentication limbo", player.getScoreboardName());
			if (connection != null) connection.disconnect(Component.literal("Authentication isolation failed. Please reconnect."));
			return false;
		}
		AuthenticationReturnTarget target = STAGED_RETURN_TARGETS.remove(connection);
		if (target == null) {
			Lg2.LOGGER.error("Missing staged authentication return target for {}", player.getScoreboardName());
			if (connection != null) connection.disconnect(Component.literal("Authentication initialization failed. Please reconnect."));
			return false;
		}

		// This is a brand-new ServerPlayer object. Drop only transient state belonging to an older
		// connection with the same UUID; persistent account credentials/premium verification stay intact.
		ACTIVE_HUMAN_SESSIONS.put(playerId, player);
		PLACEMENT_COMPLETE.remove(playerId);
		AUTHENTICATED.remove(playerId);
		AUTH_TRANSITIONING.remove(playerId);
		CONNECTED_HUMAN_SESSIONS.remove(playerId);
		PRESENCE_VISIBLE.remove(playerId);
		PRESENCE_PREANNOUNCED.remove(playerId);
		PRESENCE_LABELS.remove(playerId);
		PRESENCE_REMOVE_END_OF_TICK.remove(playerId);
		PREMIUM_TOKEN_BIND_PENDING.remove(playerId);
		OFFERED_CLIENT_TOKENS.remove(playerId);
		CLIENT_TOKEN_GRACE_UNTIL.remove(playerId);
		AFTER_AUTH_ACTIONS.remove(playerId);
		// Never remove another connection's quarantine state merely because it has the same UUID.
		LIMBO.remove(player);

		INITIAL_JOIN_LABELS.put(playerId, playerPresenceLabels(player));
		LimboState state = captureReturnState(player, target);
		if (player.isDeadOrDying() || player.deathTime > 0) {
			state = recoverPersistedDeathState(player, state);
		}
		LIMBO.put(player, state);
		CONNECTED_HUMAN_SESSIONS.add(playerId);
		applyLimboGameMode(player);
		player.setNoGravity(true);
		applyLimboAbilities(player);
		player.resetFallDistance();
		return true;
	}

	/**
	 * Reasserts the already-created quarantine. Humans are constructed directly in auth_limbo by
	 * PrepareSpawnTaskAccountAuthMixin; teleporting here would reintroduce the old PlayerList/ChunkMap
	 * bookkeeping corruption that caused DistanceManager.removePlayer crashes.
	 */
	private static void ensureLimboIsolation(ServerPlayer player) {
		if (player == null) return;
		if (!isAuthenticationWorld(player) || !LIMBO.containsKey(player)) {
			Lg2.LOGGER.error("Missing authentication quarantine for {}; disconnecting instead of teleporting",
					player.getScoreboardName());
			player.connection.disconnect(Component.literal("Authentication isolation failed. Please reconnect."));
			return;
		}
		applyLimboGameMode(player);
		player.setNoGravity(true);
		applyLimboAbilities(player);
		player.resetFallDistance();
	}

	private static LimboState captureReturnState(ServerPlayer player, AuthenticationReturnTarget target) {
		return new LimboState(
				target.level(), target.position().x, target.position().y, target.position().z,
				target.angle().x, target.angle().y, player.tickCount,
				player.getDeltaMovement(), player.fallDistance, false
		);
	}

	/**
	 * A crash can leave playerdata at zero health. Because auth freezes normal ticks, vanilla would otherwise
	 * leave that ServerPlayer permanently in its death animation. Recover the technical login object before
	 * any play packets are sent, while remembering that the post-auth destination must be a respawn target.
	 */
	private static LimboState recoverPersistedDeathState(ServerPlayer player, LimboState state) {
		TeleportTransition respawn = player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
		LimboState recovered = state.withTarget(
				respawn.newLevel(), respawn.position(), respawn.yRot(), respawn.xRot(), true
		);
		normalizeRecoveredDeathState(player);
		Lg2.LOGGER.warn("Recovered persisted dead player state for {}; authentication will return to the respawn target",
				player.getScoreboardName());
		return recovered;
	}

	private static void normalizeRecoveredDeathState(ServerPlayer player) {
		if (player == null) return;
		player.deathTime = 0;
		player.hurtTime = 0;
		player.hurtDuration = 0;
		player.setHealth(player.getMaxHealth());
		player.setPose(Pose.STANDING);
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
	}

	private static LimboState captureReturnState(ServerPlayer player) {
		ServerLevel returnLevel = player.level();
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		Vec3 velocity = player.getDeltaMovement();
		double fallDistance = player.fallDistance;

		// Older auth builds could accidentally persist lg2:auth_limbo into playerdata. Never
		// treat that technical dimension as a legitimate return point; migrate it immediately.
		if (AUTH_LIMBO_LEVEL.equals(returnLevel.dimension())) {
			MinecraftServer server = returnLevel.getServer();
			if (server == null) throw new IllegalStateException("Cannot recover stale auth-limbo player without a server");
			returnLevel = server.overworld();
			BlockPos spawn = returnLevel.getRespawnData().pos();
			x = spawn.getX() + 0.5D;
			y = spawn.getY() + 0.1D;
			z = spawn.getZ() + 0.5D;
			velocity = Vec3.ZERO;
			fallDistance = 0.0D;
			Lg2.LOGGER.warn("Recovered stale auth-limbo playerdata for {}; returning to overworld spawn", player.getScoreboardName());
		}

		return new LimboState(returnLevel, x, y, z, player.getYRot(), player.getXRot(),
				player.tickCount, velocity, fallDistance, false);
	}

	private static boolean leaveLimbo(ServerPlayer player, boolean restorePlayerPosition) {
		if (player == null) return false;
		LimboState state = LIMBO.get(player);
		if (state == null) {
			// Missing quarantine state while still physically in auth_limbo must never be treated as success.
			return !restorePlayerPosition || !isAuthenticationWorld(player);
		}

		if (!restorePlayerPosition) {
			LIMBO.remove(player, state);
			return true;
		}
		if (player.isRemoved()) return false;

		LimboState transitionState = state;
		if (state.recoveredDeadState) {
			// Consume a respawn-anchor charge only now, when authentication has actually succeeded.
			TeleportTransition respawn = player.findRespawnPositionAndUseSpawnBlock(true, TeleportTransition.DO_NOTHING);
			transitionState = state.withTarget(respawn.newLevel(), respawn.position(), respawn.yRot(), respawn.xRot(), true);
			normalizeRecoveredDeathState(player);
		}

		// Remove the limbo marker while teleportTo updates chunk tracking. Keeping it set would make
		// ChunkMapVirtualCameraTrackingMixin force the destination back to the single auth chunk.
		LIMBO.remove(player, state);
		transitionState.level.getChunkAt(BlockPos.containing(transitionState.x, transitionState.y, transitionState.z));
		boolean teleported = player.teleportTo(
				transitionState.level, transitionState.x, transitionState.y, transitionState.z,
				ABSOLUTE_TELEPORT, transitionState.yaw, transitionState.pitch, false
		);
		boolean arrivedInRealWorld = teleported
				&& player.level() == transitionState.level
				&& !player.isRemoved()
				&& !AUTH_LIMBO_LEVEL.equals(player.level().dimension());
		if (!arrivedInRealWorld) {
			// Never restore gravity/normal vulnerability while the player is still standing in the void.
			LIMBO.put(player, state);
			player.setNoGravity(true);
			applyLimboAbilities(player);
			player.resetFallDistance();
			return false;
		}

		// Restore the real gameplay state only after the cross-dimension transition succeeded.
		if (player.gameMode == null || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
			player.setGameMode(GameType.SURVIVAL);
		}
		player.setNoGravity(false);
		player.tickCount = state.tickCount;
		AbilityState.recoveredFor(GameType.SURVIVAL).restore(player.getAbilities());
		if (state.recoveredDeadState) {
			normalizeRecoveredDeathState(player);
		} else {
			player.setDeltaMovement(state.deltaMovement);
			player.fallDistance = state.fallDistance;
		}
		return true;
	}

	/**
	 * Auth limbo is a transport-only state and must never be persisted as the player's real state.
	 * ServerPlayer.addAdditionalSaveData calls this at TAIL, after vanilla wrote Pos/Rotation/Dimension
	 * and abilities, so these keys are atomically rewritten as normal survival gameplay state.
	 */
	public static void rewriteLimboPersistentState(ServerPlayer player, ValueOutput output) {
		if (player == null || output == null) return;
		LimboState state = LIMBO.get(player);
		if (state == null) return;
		output.store("Pos", Vec3.CODEC, new Vec3(state.x, state.y, state.z));
		output.store("Rotation", Vec2.CODEC, new Vec2(state.yaw, state.pitch));
		output.putString("Dimension", state.level.dimension().identifier().toString());
		output.putInt("playerGameType", GameType.SURVIVAL.getId());
		output.putBoolean("NoGravity", false);
		output.store("abilities", Abilities.Packed.CODEC, AbilityState.recoveredFor(GameType.SURVIVAL).pack());
		output.store("Motion", Vec3.CODEC, state.deltaMovement);
		output.putDouble("fall_distance", state.fallDistance);
	}


	private static void applyLimboGameMode(ServerPlayer player) {
		if (player == null || player.gameMode == null || player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
			return;
		}
		GameType previous = player.gameMode.getGameModeForPlayer();
		if (player.connection == null) {
			// placeNewPlayer(HEAD) runs before vanilla assigns ServerGamePacketListenerImpl.
			// ServerPlayer#setGameMode broadcasts a PlayerInfo packet and dereferences connection,
			// so only mutate ServerPlayerGameMode's internal state during this pre-play phase.
			((ServerPlayerGameModeAccountAuthInvoker) player.gameMode).lg2$setGameModeForPlayer(GameType.SPECTATOR, previous);
			return;
		}
		player.setGameMode(GameType.SPECTATOR);
	}

	private static void applyLimboAbilities(ServerPlayer player) {
		Abilities abilities = player.getAbilities();
		abilities.invulnerable = true;
		abilities.flying = true;
		abilities.mayfly = true;
		abilities.instabuild = false;
		abilities.mayBuild = false;
		abilities.setFlyingSpeed(0.05F);
		abilities.setWalkingSpeed(0.1F);
	}


	private static String localize(ServerPlayer player, String key) {
		String locale = player != null && player.clientInformation() != null && player.clientInformation().language() != null
				? player.clientInformation().language().toLowerCase(Locale.ROOT) : "en_us";
		String language = locale.startsWith("rpr") ? "rpr" : locale.startsWith("uk") ? "uk" : locale.startsWith("ja") ? "ja" : locale.startsWith("ru") ? "ru" : "en";
		return switch (language) {
			case "rpr" -> switch (key) {
				case "prompt_new", "prompt_login" -> "Введите пароль";
				case "success_created" -> "Пароль сохранёнъ. Входъ подтверждёнъ.";
				case "success_login" -> "Входъ подтверждёнъ.";
				case "success_changed" -> "Пароль измѣнёнъ.";
				case "wrong_password" -> "Невѣрный пароль.";
				case "too_many_attempts" -> "Слишком много попытокъ. Подождите минуту.";
				case "premium_password_admin" -> "Лицензіонный никъ. Для входа безъ лицензіи администраторъ долженъ задать пароль.";
				default -> "Ошибка авторизаціи.";
			};
			case "uk" -> switch (key) {
				case "prompt_new", "prompt_login" -> "Введіть пароль";
				case "success_created" -> "Пароль збережено. Вхід підтверджено.";
				case "success_login" -> "Вхід підтверджено.";
				case "success_changed" -> "Пароль змінено.";
				case "wrong_password" -> "Неправильний пароль.";
				case "too_many_attempts" -> "Забагато спроб. Зачекайте хвилину.";
				case "premium_password_admin" -> "Це ліцензійний нік. Для входу без ліцензії адміністратор має задати пароль.";
				default -> "Помилка авторизації.";
			};
			case "ja" -> switch (key) {
				case "prompt_new", "prompt_login" -> "パスワードを入力";
				case "success_created" -> "パスワードを保存しました。認証が完了しました。";
				case "success_login" -> "認証が完了しました。";
				case "success_changed" -> "パスワードを変更しました。";
				case "wrong_password" -> "パスワードが違います。";
				case "too_many_attempts" -> "試行回数が多すぎます。1分待ってください。";
				case "premium_password_admin" -> "この名前は正規アカウント用です。非正規で入るには管理者がパスワードを設定する必要があります。";
				default -> "認証エラーです。";
			};
			case "ru" -> switch (key) {
				case "prompt_new", "prompt_login" -> "Введите пароль";
				case "success_created" -> "Пароль сохранён. Вход подтверждён.";
				case "success_login" -> "Вход подтверждён.";
				case "success_changed" -> "Пароль изменён.";
				case "wrong_password" -> "Неверный пароль.";
				case "too_many_attempts" -> "Слишком много попыток. Подождите минуту.";
				case "premium_password_admin" -> "Это лицензионный ник. Для входа без лицензии администратор должен задать пароль.";
				default -> "Ошибка авторизации.";
			};
			default -> switch (key) {
				case "prompt_new", "prompt_login" -> "Enter password";
				case "success_created" -> "Password saved. You are authenticated.";
				case "success_login" -> "You are authenticated.";
				case "success_changed" -> "Password changed.";
				case "wrong_password" -> "Incorrect password.";
				case "too_many_attempts" -> "Too many attempts. Wait one minute.";
				case "premium_password_admin" -> "This is a premium name. An administrator must set a password before offline login is allowed.";
				default -> "Authentication error.";
			};
		};
	}

	private static String ipHash(ServerPlayer player) {
		String address = "";
		try {
			SocketAddress remote = player.connection.getRemoteAddress();
			if (remote instanceof InetSocketAddress inetSocketAddress) {
				InetAddress inetAddress = inetSocketAddress.getAddress();
				address = inetAddress == null ? inetSocketAddress.getHostString() : inetAddress.getHostAddress();
			} else if (remote != null) {
				address = remote.toString();
			}
		} catch (Exception ignored) {
			// A missing address must never become a reusable trusted identity.
			address = UUID.randomUUID().toString();
		}
		return sha256("ip:" + address);
	}

	private static String sha256(String text) {
		try {
			return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
					.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private static boolean constantTimeEquals(String expected, String actual) {
		if (expected == null || actual == null) return false;
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}

	private static boolean verifyPassword(String password, Account account) {
		try {
			byte[] salt = Base64.getDecoder().decode(account.passwordSalt);
			byte[] expected = Base64.getDecoder().decode(account.passwordHash);
			KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PASSWORD_ITERATIONS, PASSWORD_KEY_BITS);
			byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
			return MessageDigest.isEqual(expected, actual);
		} catch (Exception exception) {
			Lg2.LOGGER.error("Could not verify an account password", exception);
			return false;
		}
	}

	private static void load() {
		ACCOUNTS.clear();
		if (!Files.isRegularFile(PATH)) return;
		try (java.io.Reader reader = Files.newBufferedReader(PATH)) {
			AccountFile file = GSON.fromJson(reader, AccountFile.class);
			if (file != null && file.accounts != null) ACCOUNTS.putAll(file.accounts);
		} catch (Exception exception) {
			Lg2.LOGGER.error("Could not load account authentication data {}", PATH, exception);
		}
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (java.io.Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(new AccountFile(ACCOUNTS), writer);
			}
		} catch (Exception exception) {
			Lg2.LOGGER.error("Could not save account authentication data {}", PATH, exception);
		}
	}

	private static final class AccountFile {
		private Map<String, Account> accounts = new HashMap<>();
		private AccountFile() { }
		private AccountFile(Map<String, Account> accounts) { this.accounts = new HashMap<>(accounts); }
	}

	private static final class Account {
		private String passwordSalt;
		private String passwordHash;
		private Set<String> trustedIps = new HashSet<>();
		private String clientTokenHash;
		private String createdAt;
		private String premiumUuid;
		private boolean premiumVerified;
		private boolean offlinePasswordAllowed;

		private boolean canUseOfflineAccess() {
			return !premiumVerified || (offlinePasswordAllowed && passwordSalt != null && passwordHash != null);
		}

		private void replacePassword(String password) {
			Account replacement = fromPassword(password);
			this.passwordSalt = replacement.passwordSalt;
			this.passwordHash = replacement.passwordHash;
		}

		private static Account fromPassword(String password) {
			try {
				byte[] salt = new byte[16];
				RANDOM.nextBytes(salt);
				KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PASSWORD_ITERATIONS, PASSWORD_KEY_BITS);
				byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
				Account account = new Account();
				account.passwordSalt = Base64.getEncoder().encodeToString(salt);
				account.passwordHash = Base64.getEncoder().encodeToString(hash);
				account.createdAt = Instant.now().toString();
				return account;
			} catch (Exception exception) {
				throw new IllegalStateException("Could not hash account password", exception);
			}
		}
	}

	private static final class SingleChunkTrackingView implements ChunkTrackingView {
		private final ChunkPos center;

		private SingleChunkTrackingView(ChunkPos center) { this.center = center; }

		@Override
		public boolean contains(int x, int z, boolean includeNeighbors) {
			return x == center.x && z == center.z;
		}

		@Override
		public void forEach(java.util.function.Consumer<ChunkPos> consumer) { consumer.accept(center); }

		@Override
		public boolean equals(Object other) {
			return other instanceof SingleChunkTrackingView view && center.equals(view.center);
		}

		@Override
		public int hashCode() { return center.hashCode(); }
	}

	private record AuthenticationReturnTarget(ServerLevel level, Vec3 position, Vec2 angle) { }

	private record FailureState(long windowStartedAt, int count) { }

	private record AbilityState(
			boolean invulnerable, boolean flying, boolean mayfly, boolean instabuild, boolean mayBuild,
			float flyingSpeed, float walkingSpeed
	) {
		private static AbilityState recoveredFor(GameType gameType) {
			GameType resolved = gameType == null ? GameType.SURVIVAL : gameType;
			boolean creative = resolved == GameType.CREATIVE;
			boolean spectator = resolved == GameType.SPECTATOR;
			return new AbilityState(creative || spectator, spectator, creative || spectator, creative, true, 0.05F, 0.1F);
		}

		private void restore(Abilities abilities) {
			abilities.invulnerable = invulnerable;
			abilities.flying = flying;
			abilities.mayfly = mayfly;
			abilities.instabuild = instabuild;
			abilities.mayBuild = mayBuild;
			abilities.setFlyingSpeed(flyingSpeed);
			abilities.setWalkingSpeed(walkingSpeed);
		}

		private Abilities.Packed pack() {
			return new Abilities.Packed(invulnerable, flying, mayfly, instabuild, mayBuild, flyingSpeed, walkingSpeed);
		}
	}

	private record LimboState(
			ServerLevel level, double x, double y, double z, float yaw, float pitch,
			int tickCount, Vec3 deltaMovement, double fallDistance, boolean recoveredDeadState
	) {
		private LimboState withTarget(ServerLevel targetLevel, Vec3 position, float targetYaw, float targetPitch, boolean deadRecovery) {
			return new LimboState(targetLevel, position.x, position.y, position.z, targetYaw, targetPitch,
					tickCount, deadRecovery ? Vec3.ZERO : deltaMovement,
					deadRecovery ? 0.0D : fallDistance, deadRecovery || recoveredDeadState);
		}
	}
}
