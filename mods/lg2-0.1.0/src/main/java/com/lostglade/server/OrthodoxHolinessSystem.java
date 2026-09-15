package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.config.RaceConfig.PlayerRaceConfig;
import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import com.lostglade.config.RaceConfig.RaceAbilitySlot;
import com.lostglade.item.ModItems;
import com.mojang.brigadier.context.CommandContext;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class OrthodoxHolinessSystem {
	private static final String RACE_ID = "orthodox";
	private static final String STATE_FILE_NAME = "lg2-orthodox-holiness.json";
	private static final int MAX_HOLINESS = 4;
	private static final long MINECRAFT_DAY_MILLIS = 20L * 60L * 1000L;
	private static final double DEFAULT_SELF_DEFENSE_SECONDS = 300.0D;
	private static final double DEFAULT_BITCOIN_FRACTION = 0.5D;
	private static final double DEFAULT_AFK_MINUTES = 20.0D;
	private static final double DEFAULT_NETHER_MINUTES = 5.0D;
	private static final double DEFAULT_REPENTANCE_DAYS = 40.0D;
	private static final double DEFAULT_RECOVERY_MINUTES = 30.0D;
	private static final String BAR_SYMBOLS = "\ue906\ue907\ue908\ue909\ue90a";
	private static final FontDescription BAR_FONT = new FontDescription.Resource(
			Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "orthodox_holiness_bar")
	);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, HolinessState> STATES = new LinkedHashMap<>();
	private static final Map<UUID, Map<UUID, Long>> RECENT_ATTACKERS = new HashMap<>();
	private static final Map<UUID, ServerBossEvent> BOSS_BARS = new HashMap<>();
	private static final Map<UUID, AfkTracker> AFK_TRACKERS = new java.util.concurrent.ConcurrentHashMap<>();
	private static boolean dirty;
	private static int secondTicker;

	private OrthodoxHolinessSystem() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(OrthodoxHolinessSystem::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(OrthodoxHolinessSystem::shutdown);
		ServerTickEvents.END_SERVER_TICK.register(OrthodoxHolinessSystem::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> resetAfkTracker(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			hideBar(handler.player);
			AFK_TRACKERS.remove(handler.player.getUUID());
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			recordIncomingAttack(entity, source);
			return true;
		});
		ServerLivingEntityEvents.AFTER_DEATH.register(OrthodoxHolinessSystem::onLivingDeath);
	}

	public static boolean isOrthodox(ServerPlayer player) {
		if (player == null) return false;
		return ServerRaceSystem.getRace(player)
				.map(race -> race.id != null && RACE_ID.equalsIgnoreCase(race.id.trim()))
				.orElse(false);
	}

	public static int getHoliness(ServerPlayer player) {
		if (!isOrthodox(player)) return MAX_HOLINESS;
		HolinessState state = stateFor(player.getUUID());
		if (ServerRaceSystem.isWomanShnyagaBoyfriend(player.getUUID())) return 0;
		return clampHoliness(state.holiness);
	}

	public static boolean hasPositiveHoliness(ServerPlayer player) {
		return getHoliness(player) > 0;
	}

	public static boolean canUseAbility(ServerPlayer player, RaceAbilitySlot slot) {
		if (!isOrthodox(player) || slot == null || slot == RaceAbilitySlot.SHNYAGA) return true;
		int required = switch (slot) {
			case ATTACK -> 4;
			case DEFENSE -> 3;
			case UNIQUE_ABILITY -> 2;
			default -> 1;
		};
		int holiness = getHoliness(player);
		if (holiness >= required) return true;
		player.displayClientMessage(
				Component.literal("\u0411\u043e\u0436\u0435\u0441\u0442\u0432\u0435\u043d\u043d\u044b\u0435 \u0441\u043f\u043e\u0441\u043e\u0431\u043d\u043e\u0441\u0442\u0438 \u043d\u0435\u0434\u043e\u0441\u0442\u0443\u043f\u043d\u044b, \u043f\u043e\u043a\u0430 \u0434\u0443\u0448\u0430 \u0433\u0440\u0435\u0448\u043d\u0430")
						.withStyle(ChatFormatting.RED),
				true
		);
		return false;
	}

	public static int toggleBar(ServerPlayer player) {
		if (!isOrthodox(player) || !hasShnyaga(player)) return 0;
		HolinessState state = stateFor(player.getUUID());
		state.barHidden = !state.barHidden;
		markDirty();
		player.displayClientMessage(
				Component.literal(state.barHidden
						? "\u0418\u043d\u0434\u0438\u043a\u0430\u0442\u043e\u0440 \u0441\u0432\u044f\u0442\u043e\u0441\u0442\u0438 \u0441\u043a\u0440\u044b\u0442"
						: "\u0418\u043d\u0434\u0438\u043a\u0430\u0442\u043e\u0440 \u0441\u0432\u044f\u0442\u043e\u0441\u0442\u0438 \u043f\u043e\u043a\u0430\u0437\u0430\u043d")
						.withStyle(state.barHidden ? ChatFormatting.GRAY : ChatFormatting.GOLD),
				true
		);
		updateBar(player, true);
		return 1;
	}

	public static void onItemConsumed(ServerPlayer player, ItemStack consumed, int foodLevelBefore) {
		if (!isOrthodox(player) || consumed == null || consumed.isEmpty()) return;
		if (consumed.is(Items.POTION) && containsSinfulPotionEffect(consumed)) {
			commitSin(player);
			return;
		}
		FoodProperties food = consumed.get(DataComponents.FOOD);
		if (food == null) return;
		int missingFood = Math.max(0, 20 - foodLevelBefore);
		if (missingFood > 0 && missingFood < food.nutrition() / 2.0D) commitSin(player);
	}

	public static void onNetherPortalOpened(ServerPlayer player) {
		if (isOrthodox(player)) commitSin(player);
	}

	public static boolean preventSleep(ServerPlayer player) {
		if (!isOrthodox(player) || getHoliness(player) > 0) return false;
		player.displayClientMessage(
				Component.literal("\u041f\u0440\u0438 \u043d\u0443\u043b\u0435\u0432\u043e\u0439 \u0441\u0432\u044f\u0442\u043e\u0441\u0442\u0438 \u043d\u0435\u043b\u044c\u0437\u044f \u0441\u043f\u0430\u0442\u044c").withStyle(ChatFormatting.RED),
				true
		);
		return true;
	}

	public static boolean isBossBar(ServerPlayer player, UUID bossBarId) {
		if (player == null || bossBarId == null) return false;
		ServerBossEvent bossBar = BOSS_BARS.get(player.getUUID());
		return bossBar != null && bossBar.getId().equals(bossBarId);
	}

	public static void recordClientActivity(ServerPlayer player) {
		if (player == null) return;
		AfkTracker tracker = AFK_TRACKERS.get(player.getUUID());
		if (tracker == null) return;
		tracker.lastActivityEpochMillis = System.currentTimeMillis();
		tracker.penaltyApplied = false;
	}

	static int resetHolinessCommand(CommandContext<CommandSourceStack> context)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer target = context.getSource().getPlayerOrException();
		if (!isOrthodox(target)) {
			context.getSource().sendFailure(Component.literal(
					"\u0423\u043a\u0430\u0437\u0430\u043d\u043d\u044b\u0439 \u0438\u0433\u0440\u043e\u043a \u043d\u0435 \u044f\u0432\u043b\u044f\u0435\u0442\u0441\u044f \u043f\u0440\u0430\u0432\u043e\u0441\u043b\u0430\u0432\u043d\u044b\u043c"
			));
			return 0;
		}
		if (ServerRaceSystem.isWomanShnyagaBoyfriend(target.getUUID())) {
			context.getSource().sendFailure(Component.literal(
					"\u0421\u0432\u044f\u0442\u043e\u0441\u0442\u044c \u043d\u0435\u043b\u044c\u0437\u044f \u0432\u043e\u0441\u0441\u0442\u0430\u043d\u043e\u0432\u0438\u0442\u044c, \u043f\u043e\u043a\u0430 \u0438\u0433\u0440\u043e\u043a \u043e\u0441\u0442\u0430\u0451\u0442\u0441\u044f \u043f\u0430\u0440\u043d\u0435\u043c \u0416\u0435\u043d\u0449\u0438\u043d\u044b"
			));
			return 0;
		}

		HolinessState state = stateFor(target.getUUID());
		setHoliness(target, state, MAX_HOLINESS);
		state.relationshipLocked = false;
		state.netherSeconds = 0L;
		resetRecovery(state, System.currentTimeMillis());
		resetAfkTracker(target);
		markDirty();
		updateBar(target, true);
		save(context.getSource().getServer());

		context.getSource().sendSuccess(() -> Component.literal(
				"\u0421\u0432\u044f\u0442\u043e\u0441\u0442\u044c " + target.getName().getString() + " \u0432\u043e\u0441\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d\u0430 \u0434\u043e " + MAX_HOLINESS + "/" + MAX_HOLINESS
		), true);
		return 1;
	}

	private static void tick(MinecraftServer server) {
		if (server == null) return;
		if (++secondTicker < 20) return;
		secondTicker = 0;
		long now = System.currentTimeMillis();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!isOrthodox(player)) {
				hideBar(player);
				AFK_TRACKERS.remove(player.getUUID());
				continue;
			}
			HolinessState state = stateFor(player.getUUID());
			syncRelationship(player, state, now);
			tickBitcoinThreshold(player, state);
			tickAfk(player, state, now);
			tickNether(player, state);
			tickRecovery(player, state, now);
			pruneAttackers(player.getUUID(), now);
			updateBar(player, false);
		}
		if (dirty && server.getTickCount() % 100 == 0) save(server);
	}

	private static void syncRelationship(ServerPlayer player, HolinessState state, long now) {
		boolean linked = ServerRaceSystem.isWomanShnyagaBoyfriend(player.getUUID());
		if (linked && !state.relationshipLocked) {
			state.relationshipLocked = true;
			setHoliness(player, state, 0);
			resetRecovery(state, now);
			markDirty();
		} else if (!linked && state.relationshipLocked) {
			state.relationshipLocked = false;
			resetRecovery(state, now);
			markDirty();
		}
	}

	private static void tickBitcoinThreshold(ServerPlayer player, HolinessState state) {
		double fraction = positiveOrDefault(config(player).orthodoxHolinessBitcoinInventoryFraction, DEFAULT_BITCOIN_FRACTION);
		fraction = Math.max(0.0D, Math.min(1.0D, fraction));
		int bitcoins = 0;
		int capacity = 0;
		int storageSlots = Math.min(36, player.getInventory().getContainerSize());
		for (int slot = 0; slot < storageSlots; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			capacity += ModItems.BITCOIN.getDefaultMaxStackSize();
			if (stack.is(ModItems.BITCOIN)) bitcoins += stack.getCount();
		}
		boolean above = bitcoins > capacity * fraction;
		if (above && !state.bitcoinThresholdAbove) commitSin(player);
		if (state.bitcoinThresholdAbove != above) {
			state.bitcoinThresholdAbove = above;
			markDirty();
		}
	}

	private static void tickAfk(ServerPlayer player, HolinessState state, long now) {
		long interval = minutesToMillis(positiveOrDefault(config(player).orthodoxHolinessAfkMinutes, DEFAULT_AFK_MINUTES));
		AfkTracker tracker = AFK_TRACKERS.computeIfAbsent(player.getUUID(), ignored -> new AfkTracker(player, now));
		Input input = player.getLastClientInput();
		boolean hasInput = input != null && (input.forward() || input.backward() || input.left() || input.right()
				|| input.jump() || input.shift() || input.sprint());
		float yaw = player.getYRot();
		float pitch = player.getXRot();
		boolean rotated = Float.compare(yaw, tracker.lastYaw) != 0 || Float.compare(pitch, tracker.lastPitch) != 0;
		tracker.lastYaw = yaw;
		tracker.lastPitch = pitch;
		if (hasInput || rotated) {
			tracker.lastActivityEpochMillis = now;
			tracker.penaltyApplied = false;
		}
		if (interval <= 0L || now - tracker.lastActivityEpochMillis < interval || tracker.penaltyApplied) return;
		if (!tracker.penaltyApplied) {
			commitSin(player);
			tracker.penaltyApplied = true;
		}
	}

	private static void resetAfkTracker(ServerPlayer player) {
		if (player == null) return;
		AFK_TRACKERS.put(player.getUUID(), new AfkTracker(player, System.currentTimeMillis()));
	}

	private static void tickNether(ServerPlayer player, HolinessState state) {
		if (!Level.NETHER.equals(player.level().dimension())) {
			if (state.netherSeconds != 0L) {
				state.netherSeconds = 0L;
				markDirty();
			}
			return;
		}
		long intervalSeconds = Math.max(1L, Math.round(positiveOrDefault(
				config(player).orthodoxHolinessNetherMinutes,
				DEFAULT_NETHER_MINUTES
		) * 60.0D));
		state.netherSeconds++;
		if (state.netherSeconds >= intervalSeconds) {
			state.netherSeconds = 0L;
			commitSin(player);
		}
		markDirty();
	}

	private static void tickRecovery(ServerPlayer player, HolinessState state, long now) {
		if (!hasShnyaga(player) || state.relationshipLocked || state.holiness >= MAX_HOLINESS) return;
		RaceAbilityConfig config = config(player);
		long repentanceMillis = Math.max(1L, Math.round(positiveOrDefault(
				config.orthodoxHolinessRepentanceMinecraftDays,
				DEFAULT_REPENTANCE_DAYS
		) * MINECRAFT_DAY_MILLIS));
		long recoveryMillis = minutesToMillis(positiveOrDefault(
				config.orthodoxHolinessRecoveryMinutes,
				DEFAULT_RECOVERY_MINUTES
		));
		if (state.recoveryBaseHoliness < 0 || state.recoveryBaseHoliness > MAX_HOLINESS) {
			state.recoveryBaseHoliness = clampHoliness(state.holiness);
			markDirty();
		}
		long firstRecovery = state.lastSinEpochMillis + repentanceMillis + recoveryMillis;
		if (now < firstRecovery || recoveryMillis <= 0L) {
			state.nextRecoveryEpochMillis = firstRecovery;
			return;
		}
		long earnedRestorations = 1L + (now - firstRecovery) / recoveryMillis;
		int targetHoliness = Math.min(
				MAX_HOLINESS,
				state.recoveryBaseHoliness + (int) Math.min(MAX_HOLINESS, earnedRestorations)
		);
		if (targetHoliness > state.holiness) setHoliness(player, state, targetHoliness);
		state.nextRecoveryEpochMillis = state.holiness >= MAX_HOLINESS
				? 0L
				: firstRecovery + earnedRestorations * recoveryMillis;
		markDirty();
	}

	private static void commitSin(ServerPlayer player) {
		if (!isOrthodox(player)) return;
		HolinessState state = stateFor(player.getUUID());
		if (!state.relationshipLocked) setHoliness(player, state, state.holiness - 1);
		resetRecovery(state, System.currentTimeMillis());
		markDirty();
		updateBar(player, false);
	}

	private static void resetRecovery(HolinessState state, long now) {
		state.lastSinEpochMillis = now;
		state.nextRecoveryEpochMillis = 0L;
		state.recoveryBaseHoliness = clampHoliness(state.holiness);
	}

	private static void setHoliness(ServerPlayer player, HolinessState state, int value) {
		int previous = clampHoliness(state.holiness);
		state.holiness = clampHoliness(value);
		if (state.holiness == previous || player == null || !hasShnyaga(player) || state.barHidden) return;
		SoundEvent sound = state.holiness > previous
				? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE
				: SoundEvents.ELDER_GUARDIAN_CURSE;
		Vec3 position = player.position();
		player.connection.send(new ClientboundSoundPacket(
				Holder.direct(sound),
				SoundSource.PLAYERS,
				position.x,
				position.y,
				position.z,
				1.0F,
				state.holiness > previous ? 1.0F : 0.9F,
				player.getRandom().nextLong()
		));
	}

	private static void recordIncomingAttack(LivingEntity victim, DamageSource source) {
		if (!(victim instanceof ServerPlayer orthodox) || !isOrthodox(orthodox)) return;
		LivingEntity attacker = resolveLivingAttacker(source);
		if (attacker == null || attacker.getUUID().equals(orthodox.getUUID())) return;
		RECENT_ATTACKERS.computeIfAbsent(orthodox.getUUID(), ignored -> new HashMap<>())
				.put(attacker.getUUID(), System.currentTimeMillis());
	}

	private static void onLivingDeath(LivingEntity victim, DamageSource source) {
		LivingEntity attacker = resolveLivingAttacker(source);
		if (!(attacker instanceof ServerPlayer orthodox) || !isOrthodox(orthodox)) return;
		long now = System.currentTimeMillis();
		long window = Math.max(1L, Math.round(positiveOrDefault(
				config(orthodox).orthodoxHolinessSelfDefenseSeconds,
				DEFAULT_SELF_DEFENSE_SECONDS
		) * 1000.0D));
		Map<UUID, Long> attackers = RECENT_ATTACKERS.get(orthodox.getUUID());
		Long attackedAt = attackers == null ? null : attackers.remove(victim.getUUID());
		if (OrthodoxUniqueSystem.isNativeNetherMob(victim.getType())) return;
		if (attackedAt == null || now - attackedAt > window) commitSin(orthodox);
	}

	private static LivingEntity resolveLivingAttacker(DamageSource source) {
		if (source == null) return null;
		Entity owner = source.getEntity();
		if (owner instanceof LivingEntity living) return living;
		Entity direct = source.getDirectEntity();
		if (direct instanceof Projectile projectile && projectile.getOwner() instanceof LivingEntity living) return living;
		return direct instanceof LivingEntity living ? living : null;
	}

	private static void pruneAttackers(UUID playerId, long now) {
		Map<UUID, Long> attackers = RECENT_ATTACKERS.get(playerId);
		if (attackers == null) return;
		attackers.entrySet().removeIf(entry -> now - entry.getValue() > 10L * 60L * 1000L);
		if (attackers.isEmpty()) RECENT_ATTACKERS.remove(playerId);
	}

	private static boolean containsSinfulPotionEffect(ItemStack stack) {
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		if (contents == null) return false;
		for (MobEffectInstance effect : contents.getAllEffects()) {
			if (effect.getEffect().equals(MobEffects.POISON) || effect.getEffect().equals(MobEffects.INSTANT_DAMAGE)) return true;
		}
		return false;
	}

	private static void updateBar(ServerPlayer player, boolean forcePriority) {
		HolinessState state = stateFor(player.getUUID());
		if (!hasShnyaga(player) || state.barHidden) {
			hideBar(player);
			return;
		}
		ServerBossEvent bossBar = BOSS_BARS.computeIfAbsent(player.getUUID(), ignored -> createBossBar());
		int holiness = getHoliness(player);
		bossBar.setName(buildBarTitle(player, holiness));
		bossBar.setProgress(0.0F);
		bossBar.setVisible(true);
		boolean added = false;
		if (!bossBar.getPlayers().contains(player)) {
			bossBar.addPlayer(player);
			added = true;
		}
		if (forcePriority || added || player.level().getGameTime() % 20L == 0L) {
			ServerStabilitySystem.reorderHudBelowExternalBossBar(player);
			ServerBossBarVisibilitySystem.reorderTrackedBossBarsBelowReservedHud(player);
		}
	}

	private static ServerBossEvent createBossBar() {
		ServerBossEvent event = new ServerBossEvent(
				Component.empty(),
				BossEvent.BossBarColor.GREEN,
				BossEvent.BossBarOverlay.PROGRESS
		);
		event.setDarkenScreen(false);
		event.setPlayBossMusic(false);
		event.setCreateWorldFog(false);
		return event;
	}

	private static Component buildBarTitle(ServerPlayer player, int holiness) {
		if (PolymerResourcePackUtils.hasMainPack(player)) {
			int frame = MAX_HOLINESS - clampHoliness(holiness);
			return Component.literal(String.valueOf(BAR_SYMBOLS.charAt(frame))).withStyle(style -> style
					.withColor(0xFFFFFF)
					.withItalic(false)
					.withBold(false)
					.withFont(BAR_FONT)
					.withShadowColor(0x00000000));
		}
		return Component.literal("\u0421\u0432\u044f\u0442\u043e\u0441\u0442\u044c " + holiness + "/" + MAX_HOLINESS)
				.withStyle(style -> style.withColor(0xFFD866).withBold(true).withItalic(false));
	}

	private static void hideBar(ServerPlayer player) {
		if (player == null) return;
		ServerBossEvent bossBar = BOSS_BARS.remove(player.getUUID());
		if (bossBar != null) bossBar.removeAllPlayers();
	}

	private static HolinessState stateFor(UUID playerId) {
		return STATES.computeIfAbsent(playerId, ignored -> {
			HolinessState state = new HolinessState();
			long now = System.currentTimeMillis();
			state.holiness = MAX_HOLINESS;
			state.lastSinEpochMillis = now;
			state.recoveryBaseHoliness = MAX_HOLINESS;
			markDirty();
			return state;
		});
	}

	private static RaceAbilityConfig config(ServerPlayer player) {
		Optional<PlayerRaceConfig> race = ServerRaceSystem.getRace(player);
		if (race.isPresent()) {
			RaceAbilityConfig ability = ServerRaceSystem.getAbility(race.get(), RaceAbilitySlot.SHNYAGA);
			if (ability != null) return ability;
		}
		return RaceAbilityConfig.defaults(RaceAbilitySlot.SHNYAGA);
	}

	private static boolean hasShnyaga(ServerPlayer player) {
		return isOrthodox(player) && ServerRaceSystem.hasUnlockedAbility(player, RaceAbilitySlot.SHNYAGA);
	}

	private static double positiveOrDefault(double value, double fallback) {
		return Double.isFinite(value) && value > 0.0D ? value : fallback;
	}

	private static long minutesToMillis(double minutes) {
		return Math.max(1L, Math.round(minutes * 60_000.0D));
	}

	private static int clampHoliness(int value) {
		return Math.max(0, Math.min(MAX_HOLINESS, value));
	}

	private static void markDirty() {
		dirty = true;
	}

	private static void load(MinecraftServer server) {
		STATES.clear();
		boolean legacyRecoveryState = false;
		Path path = statePath(server);
		if (Files.exists(path)) {
			try {
				PersistedData data = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), PersistedData.class);
				if (data != null) {
					legacyRecoveryState = data.version < 2;
					if (data.players != null) STATES.putAll(data.players);
				}
			} catch (Exception exception) {
				Lg2.LOGGER.error("Failed to load Orthodox holiness state from {}", path, exception);
			}
		}
		long now = System.currentTimeMillis();
		for (HolinessState state : STATES.values()) {
			state.holiness = clampHoliness(state.holiness);
			if (state.lastSinEpochMillis <= 0L) state.lastSinEpochMillis = now;
			if (legacyRecoveryState || state.recoveryBaseHoliness < 0 || state.recoveryBaseHoliness > MAX_HOLINESS) {
				state.recoveryBaseHoliness = state.holiness;
			}
		}
		dirty = false;
	}

	private static void save(MinecraftServer server) {
		if (server == null) return;
		PersistedData data = new PersistedData();
		data.players.putAll(STATES);
		Path path = statePath(server);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
			dirty = false;
		} catch (IOException exception) {
			Lg2.LOGGER.error("Failed to save Orthodox holiness state to {}", path, exception);
		}
	}

	private static void shutdown(MinecraftServer server) {
		save(server);
		for (ServerBossEvent bossBar : BOSS_BARS.values()) bossBar.removeAllPlayers();
		BOSS_BARS.clear();
		RECENT_ATTACKERS.clear();
		AFK_TRACKERS.clear();
	}

	private static Path statePath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(STATE_FILE_NAME);
	}

	private static final class HolinessState {
		private int holiness = MAX_HOLINESS;
		private boolean barHidden;
		private boolean bitcoinThresholdAbove;
		private boolean relationshipLocked;
		private long lastSinEpochMillis;
		private long nextRecoveryEpochMillis;
		private int recoveryBaseHoliness = -1;
		private long netherSeconds;
	}

	private static final class AfkTracker {
		private volatile long lastActivityEpochMillis;
		private volatile boolean penaltyApplied;
		private float lastYaw;
		private float lastPitch;

		private AfkTracker(ServerPlayer player, long now) {
			this.lastActivityEpochMillis = now;
			this.lastYaw = player.getYRot();
			this.lastPitch = player.getXRot();
		}
	}

	private static final class PersistedData {
		private int version = 2;
		private final Map<UUID, HolinessState> players = new LinkedHashMap<>();
	}

}
