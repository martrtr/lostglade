package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.block.ModBlocks;
import com.lostglade.network.Lg2Payloads;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
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
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
	private static final int MIN_PASSWORD_LENGTH = 8;
	private static final int MAX_PASSWORD_LENGTH = 128;
	private static final int PROMPT_INTERVAL_TICKS = 40;
	private static final int MAX_FAILED_ATTEMPTS = 5;
	private static final long FAILURE_WINDOW_MILLIS = 60_000L;

	private static final Map<String, Account> ACCOUNTS = new ConcurrentHashMap<>();
	private static final Set<UUID> AUTHENTICATED = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> VERIFIED_PREMIUM_SESSIONS = ConcurrentHashMap.newKeySet();
	private static final Map<String, Long> PREMIUM_LOOKUP_UNAVAILABLE_UNTIL = new ConcurrentHashMap<>();
	private static final Map<UUID, LimboState> LIMBO = new ConcurrentHashMap<>();
	private static final Set<Relative> ABSOLUTE_TELEPORT = EnumSet.noneOf(Relative.class);
	private static final int LIMBO_X = 28_000_000;
	private static final int LIMBO_Z = 28_000_000;
	private static final int LIMBO_FLOOR_Y = 250;
	private static final long PREMIUM_LOOKUP_RETRY_MILLIS = 300_000L;
	private static final Map<UUID, String> OFFERED_CLIENT_TOKENS = new HashMap<>();
	private static final Map<UUID, FailureState> FAILURES = new HashMap<>();
	private static int promptTicker;

	private AccountAuthSystem() {
	}

	public static void register() {
		load();
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				server.execute(() -> onJoin((ServerPlayer) handler.player)));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clearSession(handler.player));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (AUTHENTICATED.contains(oldPlayer.getUUID())) AUTHENTICATED.add(newPlayer.getUUID());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());
		ServerTickEvents.END_SERVER_TICK.register(AccountAuthSystem::tickPrompts);
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(AccountAuthSystem::handlePasswordChat);

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
						.then(Commands.argument("new_password", StringArgumentType.word())
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
		return player != null && AUTHENTICATED.contains(player.getUUID());
	}

	/** Called only by the login-phase vanilla session verification mixin. */
	public static void noteVerifiedPremiumSession(String name, UUID officialUuid) {
		if (officialUuid != null) VERIFIED_PREMIUM_SESSIONS.add(officialUuid);
	}

	/** A name lookup reserves a premium nickname; it does not authorize a connection. */
	public static synchronized void reservePremiumName(String name, UUID officialUuid) {
		if (!validName(name) || officialUuid == null) return;
		String key = key(name);
		PREMIUM_LOOKUP_UNAVAILABLE_UNTIL.remove(key);
		Account account = ACCOUNTS.computeIfAbsent(key, ignored -> new Account());
		if (!officialUuid.toString().equals(account.premiumUuid)) {
			account.premiumUuid = officialUuid.toString();
			account.offlinePasswordAllowed = false;
			account.passwordSalt = null;
			account.passwordHash = null;
			account.trustedIps.clear();
			account.clientTokenHash = null;
			account.createdAt = Instant.now().toString();
			save();
		}
	}

	public static void notePremiumNameLookupSucceeded(String name) {
		PREMIUM_LOOKUP_UNAVAILABLE_UNTIL.remove(key(name));
	}

	public static void notePremiumNameLookupUnavailable(String name) {
		PREMIUM_LOOKUP_UNAVAILABLE_UNTIL.put(key(name), System.currentTimeMillis() + PREMIUM_LOOKUP_RETRY_MILLIS);
	}

	public static boolean isInLimbo(ServerPlayer player) {
		return player != null && LIMBO.containsKey(player.getUUID());
	}

	public static ChunkTrackingView createLimboChunkTrackingView(ServerPlayer player) {
		return ChunkTrackingView.of(player.chunkPosition(), 0);
	}

	/** Used by the packet mixin as the final guard for movement and inventory packets. */
	public static boolean shouldBlockPacket(ServerPlayer player) {
		return player != null && !RendererBotPresenceSystem.isRendererBot(player) && !isAuthenticated(player);
	}

	/** Only /password is meaningful before login. */
	public static boolean shouldBlockCommand(ServerPlayer player, String command) {
		if (isAuthenticated(player)) return false;
		String verb = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
		return !(verb.equals("password") || verb.startsWith("password "));
	}

	private static void onJoin(ServerPlayer player) {
		if (player == null) return;
		if (RendererBotPresenceSystem.isRendererBot(player)) return;
		if (VERIFIED_PREMIUM_SESSIONS.remove(player.getUUID())) {
			reservePremiumName(player.getScoreboardName(), player.getUUID());
			authorize(player, "success_premium");
			return;
		}
		Account account = accountFor(player);
		if (account != null && account.canUseOfflineAccess() && account.trustedIps.contains(ipHash(player))) {
			authorize(player, "success_login");
			return;
		}
		enterLimbo(player);
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
		// A player who was admitted by a trusted IP may have just installed the
		// client mod. Bind its first token now, but never replace an existing one.
		if (account.canUseOfflineAccess() && account.clientTokenHash == null && isAuthenticated(player)) {
			account.clientTokenHash = sha256(token);
			save();
			return;
		}
		if (isAuthenticated(player)) return;
		if (account.canUseOfflineAccess() && constantTimeEquals(account.clientTokenHash, sha256(token))) {
			authorize(player, "success_login");
		}
	}

	private static boolean handlePasswordChat(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params) {
		if (sender == null || isAuthenticated(sender)) return true;
		submitPassword(sender, message == null ? "" : message.signedContent());
		// The submitted secret is never shown to other players or written as chat.
		return false;
	}

	/** Accepts a bare chat message before login, or /password in any session. */
	private static boolean submitPassword(ServerPlayer player, String password) {
		if (player == null || !validPassword(player, password)) return false;
		String name = key(player.getScoreboardName());
		Account account = ACCOUNTS.get(name);
		if (account == null) {
			if (isPremiumLookupUnavailable(name)) {
				actionbar(player, "premium_lookup_unavailable");
				return false;
			}
			account = Account.fromPassword(password);
			account.trustedIps.add(ipHash(player));
			bindOfferedClientToken(player, account);
			ACCOUNTS.put(name, account);
			save();
			authorize(player, "success_created");
			return true;
		}
		if (!account.canUseOfflineAccess()) {
			actionbar(player, "premium_password_admin");
			return false;
		}
		if (isAuthenticated(player)) {
			account.replacePassword(password);
			save();
			actionbar(player, "success_changed");
			return true;
		}
		if (isLocked(player)) return false;
		if (!verifyPassword(password, account)) {
			recordFailure(player);
			actionbar(player, "wrong_password");
			return false;
		}
		FAILURES.remove(player.getUUID());
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
		if (!validName(playerName) || !validPassword(null, password)) {
			source.sendFailure(Component.literal("Недопустимый ник или пароль (8–128 символов без пробелов)."));
			return 0;
		}
		// A recovery is a security reset: old IP and local-client access must not survive it.
		Account replacement = Account.fromPassword(password);
		Account old = ACCOUNTS.get(key(playerName));
		if (old != null && old.premiumUuid != null) replacement.premiumUuid = old.premiumUuid;
		replacement.offlinePasswordAllowed = replacement.premiumUuid != null;
		ACCOUNTS.put(key(playerName), replacement);
		MinecraftServer server = source.getServer();
		ServerPlayer onlinePlayer = server == null ? null : server.getPlayerList().getPlayerByName(playerName);
		if (onlinePlayer != null) {
			AUTHENTICATED.remove(onlinePlayer.getUUID());
			OFFERED_CLIENT_TOKENS.remove(onlinePlayer.getUUID());
			FAILURES.remove(onlinePlayer.getUUID());
			sendPrompt(onlinePlayer);
		}
		save();
		source.sendSuccess(() -> Component.literal("Пароль игрока " + playerName + " установлен; старые IP и токены отозваны."), true);
		return 1;
	}

	private static boolean validPassword(ServerPlayer player, String password) {
		boolean valid = password != null && password.length() >= MIN_PASSWORD_LENGTH && password.length() <= MAX_PASSWORD_LENGTH
				&& password.codePoints().noneMatch(Character::isWhitespace);
		if (!valid && player != null) actionbar(player, "invalid_password");
		return valid;
	}

	private static boolean validName(String name) {
		return name != null && name.matches("[A-Za-z0-9_]{3,16}");
	}

	private static boolean isLocked(ServerPlayer player) {
		FailureState state = FAILURES.get(player.getUUID());
		if (state == null || System.currentTimeMillis() - state.windowStartedAt > FAILURE_WINDOW_MILLIS) return false;
		if (state.count < MAX_FAILED_ATTEMPTS) return false;
		actionbar(player, "too_many_attempts");
		return true;
	}

	private static void recordFailure(ServerPlayer player) {
		long now = System.currentTimeMillis();
		FailureState old = FAILURES.get(player.getUUID());
		if (old == null || now - old.windowStartedAt > FAILURE_WINDOW_MILLIS) {
			FAILURES.put(player.getUUID(), new FailureState(now, 1));
		} else {
			FAILURES.put(player.getUUID(), new FailureState(old.windowStartedAt, old.count + 1));
		}
	}

	private static void authorize(ServerPlayer player, String messageKey) {
		AUTHENTICATED.add(player.getUUID());
		OFFERED_CLIENT_TOKENS.remove(player.getUUID());
		leaveLimbo(player, true);
		actionbar(player, messageKey);
	}

	private static void clearSession(ServerPlayer player) {
		if (player == null) return;
		AUTHENTICATED.remove(player.getUUID());
		VERIFIED_PREMIUM_SESSIONS.remove(player.getUUID());
		OFFERED_CLIENT_TOKENS.remove(player.getUUID());
		FAILURES.remove(player.getUUID());
		leaveLimbo(player, false);
	}

	private static void tickPrompts(MinecraftServer server) {
		if (++promptTicker % PROMPT_INTERVAL_TICKS != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!isAuthenticated(player)) sendPrompt(player);
		}
	}

	private static void sendPrompt(ServerPlayer player) {
		Account account = accountFor(player);
		if (account != null && !account.canUseOfflineAccess()) actionbar(player, "premium_password_admin");
		else if (account == null && isPremiumLookupUnavailable(key(player.getScoreboardName()))) actionbar(player, "premium_lookup_unavailable");
		else actionbar(player, account == null ? "prompt_new" : "prompt_login");
	}

	private static boolean isPremiumLookupUnavailable(String name) {
		Long until = PREMIUM_LOOKUP_UNAVAILABLE_UNTIL.get(key(name));
		if (until == null) return false;
		if (until > System.currentTimeMillis()) return true;
		PREMIUM_LOOKUP_UNAVAILABLE_UNTIL.remove(key(name), until);
		return false;
	}

	private static void actionbar(ServerPlayer player, String messageKey) {
		player.displayClientMessage(Component.literal(localize(player, messageKey)), true);
	}

	private static Account accountFor(ServerPlayer player) {
		return player == null ? null : ACCOUNTS.get(key(player.getScoreboardName()));
	}

	private static String key(String name) {
		return name == null ? "" : name.toLowerCase(Locale.ROOT);
	}

	/**
	 * A one-block-wide, two-block-high sealed cell at a remote coordinate. The
	 * shell is restored exactly on authorization/disconnect, so it cannot affect
	 * the season-start construction or leave world debris behind.
	 */
	private static void enterLimbo(ServerPlayer player) {
		if (LIMBO.containsKey(player.getUUID())) return;
		ServerLevel level = player.level();
		int slot = Math.floorMod(player.getUUID().hashCode(), 100_000);
		int baseX = LIMBO_X + slot * 16;
		int baseZ = LIMBO_Z;
		int floorY = LIMBO_FLOOR_Y;

		Map<BlockPos, BlockState> replaced = new HashMap<>();
		for (int x = baseX; x <= baseX + 2; x++) {
			for (int y = floorY; y <= floorY + 3; y++) {
				for (int z = baseZ; z <= baseZ + 2; z++) {
					if (x != baseX + 1 || z != baseZ + 1 || (y != floorY + 1 && y != floorY + 2)) {
						BlockPos pos = new BlockPos(x, y, z);
						replaced.put(pos, level.getBlockState(pos));
						level.setBlock(pos, ModBlocks.STARTUP_VOID.defaultBlockState(), 2);
					}
				}
			}
		}
		LIMBO.put(player.getUUID(), new LimboState(level, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), replaced));
		level.getChunkAt(new BlockPos(baseX + 1, floorY + 1, baseZ + 1));
		player.teleportTo(level, baseX + 1.5D, floorY + 1.0D, baseZ + 1.5D, ABSOLUTE_TELEPORT, player.getYRot(), player.getXRot(), false);
		player.resetFallDistance();
	}

	private static void leaveLimbo(ServerPlayer player, boolean restorePlayerPosition) {
		if (player == null) return;
		LimboState state = LIMBO.remove(player.getUUID());
		if (state == null) return;
		for (Map.Entry<BlockPos, BlockState> entry : state.replaced.entrySet()) {
			state.level.setBlock(entry.getKey(), entry.getValue(), 2);
		}
		if (restorePlayerPosition && !player.isRemoved()) {
			state.level.getChunkAt(BlockPos.containing(state.x, state.y, state.z));
			player.teleportTo(state.level, state.x, state.y, state.z, ABSOLUTE_TELEPORT, state.yaw, state.pitch, false);
			player.resetFallDistance();
		}
	}


	private static String localize(ServerPlayer player, String key) {
		String locale = player != null && player.clientInformation() != null && player.clientInformation().language() != null
				? player.clientInformation().language().toLowerCase(Locale.ROOT) : "en_us";
		String language = locale.startsWith("rpr") ? "rpr" : locale.startsWith("uk") ? "uk" : locale.startsWith("ja") ? "ja" : locale.startsWith("ru") ? "ru" : "en";
		return switch (language) {
			case "rpr" -> switch (key) {
				case "prompt_new" -> "Придумайте пароль (8–128 знаковъ безъ пробѣловъ) и введите его въ чатъ";
				case "prompt_login" -> "Введите пароль въ чатъ";
				case "success_created" -> "Пароль сохранёнъ. Входъ подтверждёнъ.";
				case "success_login" -> "Входъ подтверждёнъ.";
				case "success_premium" -> "Лицензія подтверждена. Входъ открытъ.";
				case "success_changed" -> "Пароль измѣнёнъ.";
				case "invalid_password" -> "Пароль: 8–128 знаковъ безъ пробѣловъ.";
				case "wrong_password" -> "Невѣрный пароль.";
				case "too_many_attempts" -> "Слишком много попытокъ. Подождите минуту.";
				case "premium_password_admin" -> "Лицензіонный никъ. Для входа безъ лицензіи администраторъ долженъ задать пароль.";
				case "premium_lookup_unavailable" -> "Проверка лицензіи временно недоступна. Повторите входъ позже.";
				default -> "Ошибка авторизаціи.";
			};
			case "uk" -> switch (key) {
				case "prompt_new" -> "Придумайте пароль (8–128 символів без пробілів) і введіть його в чат";
				case "prompt_login" -> "Введіть пароль у чат";
				case "success_created" -> "Пароль збережено. Вхід підтверджено.";
				case "success_login" -> "Вхід підтверджено.";
				case "success_premium" -> "Ліцензію підтверджено. Вхід відкрито.";
				case "success_changed" -> "Пароль змінено.";
				case "invalid_password" -> "Пароль: 8–128 символів без пробілів.";
				case "wrong_password" -> "Неправильний пароль.";
				case "too_many_attempts" -> "Забагато спроб. Зачекайте хвилину.";
				case "premium_password_admin" -> "Це ліцензійний нік. Для входу без ліцензії адміністратор має задати пароль.";
				case "premium_lookup_unavailable" -> "Перевірка ліцензії тимчасово недоступна. Повторіть вхід пізніше.";
				default -> "Помилка авторизації.";
			};
			case "ja" -> switch (key) {
				case "prompt_new" -> "パスワード（空白なし・8～128文字）を決めてチャットに入力してください";
				case "prompt_login" -> "パスワードをチャットに入力してください";
				case "success_created" -> "パスワードを保存しました。認証が完了しました。";
				case "success_login" -> "認証が完了しました。";
				case "success_premium" -> "ライセンスを確認しました。ログインしました。";
				case "success_changed" -> "パスワードを変更しました。";
				case "invalid_password" -> "パスワードは空白なしの8～128文字にしてください。";
				case "wrong_password" -> "パスワードが違います。";
				case "too_many_attempts" -> "試行回数が多すぎます。1分待ってください。";
				case "premium_password_admin" -> "この名前は正規アカウント用です。非正規で入るには管理者がパスワードを設定する必要があります。";
				case "premium_lookup_unavailable" -> "ライセンス確認を一時的に利用できません。後でもう一度接続してください。";
				default -> "認証エラーです。";
			};
			case "ru" -> switch (key) {
				case "prompt_new" -> "Придумайте пароль (8–128 символов без пробелов) и введите его в чат";
				case "prompt_login" -> "Введите пароль в чат";
				case "success_created" -> "Пароль сохранён. Вход подтверждён.";
				case "success_login" -> "Вход подтверждён.";
				case "success_premium" -> "Лицензия подтверждена. Вход выполнен.";
				case "success_changed" -> "Пароль изменён.";
				case "invalid_password" -> "Пароль: 8–128 символов без пробелов.";
				case "wrong_password" -> "Неверный пароль.";
				case "too_many_attempts" -> "Слишком много попыток. Подождите минуту.";
				case "premium_password_admin" -> "Это лицензионный ник. Для входа без лицензии администратор должен задать пароль.";
				case "premium_lookup_unavailable" -> "Проверка лицензии временно недоступна. Повторите вход позже.";
				default -> "Ошибка авторизации.";
			};
			default -> switch (key) {
				case "prompt_new" -> "Choose a password (8–128 characters, no spaces) and enter it in chat";
				case "prompt_login" -> "Enter your password in chat";
				case "success_created" -> "Password saved. You are authenticated.";
				case "success_login" -> "You are authenticated.";
				case "success_premium" -> "License verified. You are authenticated.";
				case "success_changed" -> "Password changed.";
				case "invalid_password" -> "Password must be 8–128 characters without spaces.";
				case "wrong_password" -> "Incorrect password.";
				case "too_many_attempts" -> "Too many attempts. Wait one minute.";
				case "premium_password_admin" -> "This is a premium name. An administrator must set a password before offline login is allowed.";
				case "premium_lookup_unavailable" -> "License verification is temporarily unavailable. Please reconnect later.";
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
		private boolean offlinePasswordAllowed;

		private boolean canUseOfflineAccess() {
			return premiumUuid == null || (offlinePasswordAllowed && passwordSalt != null && passwordHash != null);
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

	private record FailureState(long windowStartedAt, int count) { }

	private record LimboState(ServerLevel level, double x, double y, double z, float yaw, float pitch,
								  Map<BlockPos, BlockState> replaced) { }
}
