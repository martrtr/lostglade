package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.block.ModBlocks;
import com.lostglade.config.Lg2Config;
import com.lostglade.item.ModItems;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerStabilitySystem {
	private static final String STABILITY_SYMBOL = "\uE903";
	private static final int TITLE_COLOR = 0xF2CD26;
	private static final int PACK_SYMBOL_COLOR = 0xFFFFFF;
	private static final int STABILITY_PARTICLE_COLOR = 0xFFFFD24D;
	private static final ColorParticleOption STABILITY_POTION_PARTICLE = ColorParticleOption.create(
			ParticleTypes.ENTITY_EFFECT,
			STABILITY_PARTICLE_COLOR
	);

	private static final Map<UUID, ServerBossEvent> PLAYER_HUDS = new HashMap<>();
	private static final Map<UUID, ServerBossEvent> PLAYER_SPACER_HUDS = new HashMap<>();
	private static final Map<UUID, Component> PLAYER_SPACER_OVERLAY_TITLES = new HashMap<>();
	private static final Map<String, Set<String>> TRACKED_SERVER_ANCHORS = new HashMap<>();
	private static final Set<ItemEntity> TRACKED_BITCOIN_ITEMS = ConcurrentHashMap.newKeySet();
	private static final Map<FeedSoundSourceKey, ActiveFeedSoundSource> ACTIVE_FEED_SOUND_SOURCES = new HashMap<>();
	private static final int BITCOIN_FEED_SCAN_RADIUS = 1;
	private static final double BITCOIN_FEED_RADIUS = 0.3D;
	private static final double FEED_MUSIC_SOUND_RADIUS = 64.0D;
	private static final int FEED_MUSIC_SEGMENT_TICKS = 10;
	private static final int FEED_MUSIC_SEGMENT_COUNT = 23;
	private static final Holder<SoundEvent>[] FEED_MUSIC_SEGMENTS = createFeedMusicSegments();
	private static final Identifier STARTUP_FEED_MUSIC_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "bitcoin_billionaire_meloboom");
	private static final Holder<SoundEvent> STARTUP_FEED_MUSIC_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(STARTUP_FEED_MUSIC_SOUND_ID));
	private static final long FEED_SOUND_BASE_DURATION_TICKS = 226L;
	private static final float FEED_MUSIC_SOUND_VOLUME = 1.0F;
	// At volume 8 the vanilla linear sound range is 128 blocks: the whole
	// start box hears a single track sourced from the server at its centre.
	private static final float STARTUP_FEED_MUSIC_VOLUME = 8.0F;
	private static final float STARTUP_FEED_MUSIC_PITCH = 0.86F;
	private static final float FEED_XP_SOUND_VOLUME = 1.0F;
	private static final Identifier STABILITY_SIREN_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "stability_siren");
	private static final Holder<SoundEvent> STABILITY_SIREN_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(STABILITY_SIREN_SOUND_ID));
	private static final long STABILITY_SIREN_START_TICKS = 20L * 60L * 5L;
	private static final long STABILITY_SIREN_REPEAT_TICKS = 20L * 20L;
	private static final long STABILITY_NONPAYMENT_ANNOUNCEMENT_DELAY_TICKS = 20L * 10L;
	private static final long STABILITY_NONPAYMENT_ANNOUNCEMENT_REPEAT_TICKS = 20L * 7L;
	private static final float STABILITY_SIREN_VOLUME = 3.0F;
	private static final Component NONPAYMENT_DISCONNECT_REASON = Component.literal("Сервер не уплачен");
	private static final SimpleParticleType FEED_PARTICLE = resolveFeedParticle();
	private static final Gson STABILITY_STATE_GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String STABILITY_STATE_FILE_NAME = "lg2-stability.json";

	private static int stability = 100;
	private static long decayTickCounter = 0L;
	private static double pendingStabilityFraction = 0.0D;
	private static long nextFeedSoundAllowedTick = Long.MIN_VALUE;
	private static long stabilitySirenStartedTick = Long.MIN_VALUE;
	private static long nextStabilitySirenTick = Long.MIN_VALUE;
	private static long nextNonpaymentAnnouncementTick = Long.MIN_VALUE;
	private static boolean shutdownHaltRequested = false;
	private static boolean stabilityStateLoaded = false;
	private static boolean stabilityDirty = false;

	private static final class StabilityState {
		int stability;
		Map<String, Set<String>> trackedServerAnchors;
	}

	private record FeedSoundSourceKey(net.minecraft.resources.ResourceKey<Level> dimension, BlockPos pos) {
	}

	private static final class ActiveFeedSoundSource {
		private final FeedSoundSourceKey key;
		private final long startTick;
		private final long endTick;
		private final float pitch;
		private final float volume;
		private final long seed;
		private final boolean centeredOnListener;
		private final Map<UUID, Integer> lastSegmentByListener = new HashMap<>();

		private ActiveFeedSoundSource(
				FeedSoundSourceKey key,
				long startTick,
				long endTick,
				float pitch,
				float volume,
				long seed,
				boolean centeredOnListener
		) {
			this.key = key;
			this.startTick = startTick;
			this.endTick = endTick;
			this.pitch = pitch;
			this.volume = volume;
			this.seed = seed;
			this.centeredOnListener = centeredOnListener;
		}
	}
	@SuppressWarnings("unchecked")
	private static Holder<SoundEvent>[] createFeedMusicSegments() {
		Holder<SoundEvent>[] segments = new Holder[FEED_MUSIC_SEGMENT_COUNT];
		for (int i = 0; i < segments.length; i++) {
			Identifier id = Identifier.fromNamespaceAndPath(
					Lg2.MOD_ID,
					String.format(Locale.ROOT, "bitcoin_billionaire_meloboom_part_%02d", i)
			);
			segments[i] = Holder.direct(SoundEvent.createVariableRangeEvent(id));
		}
		return segments;
	}

	private ServerStabilitySystem() {
	}

	public static void register() {
		stabilityStateLoaded = false;
		stabilityDirty = false;
		setStability(getMaxStability());
		decayTickCounter = 0L;
		pendingStabilityFraction = 0.0D;
		nextFeedSoundAllowedTick = Long.MIN_VALUE;
		resetStabilitySiren();
		shutdownHaltRequested = false;
		ACTIVE_FEED_SOUND_SOURCES.clear();
		TRACKED_SERVER_ANCHORS.clear();

		ServerLifecycleEvents.SERVER_STARTED.register(ServerStabilitySystem::loadPersistedStability);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			stopAllFeedSoundSources(server);
			stopStabilitySiren(server);
			savePersistedStability(server);
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(
						Commands.literal("serverstability")
								.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
								.executes(context -> {
									int value = getStability();
									int max = getMaxStability();
									context.getSource().sendSuccess(
											() -> Component.literal("Server stability: " + value + "/" + max),
											false
									);
									return value;
								})
								.then(Commands.argument("value", IntegerArgumentType.integer(0))
										.executes(context -> {
											int value = IntegerArgumentType.getInteger(context, "value");
											setStability(value);
											int current = getStability();
											int max = getMaxStability();
											context.getSource().sendSuccess(
													() -> Component.literal("Set server stability to " + current + "/" + max),
													true
											);
											return 1;
										}))
				)
		);

		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof ItemEntity itemEntity && itemEntity.getItem().is(ModItems.BITCOIN)) {
				TRACKED_BITCOIN_ITEMS.add(itemEntity);
			}
		});

		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof ItemEntity itemEntity) {
				TRACKED_BITCOIN_ITEMS.remove(itemEntity);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tickStabilityDecay(server);
			tickBitcoinOfferings();
			tickCriticalStability(server);
			tickActiveFeedSoundSources(server);

			Set<UUID> online = new HashSet<>();

			for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
				online.add(player.getUUID());
				spawnStabilityPotionParticles(player);

				boolean shouldShowStabilityHud = isLookingAtServerBlock(player) || hasStabilityPotionVision(player);
				ServerBossBarVisibilitySystem.setServerHudFocus(player, shouldShowStabilityHud);

				if (!shouldShowStabilityHud) {
					hideHud(player);
					continue;
				}

				showHud(player, PolymerResourcePackUtils.hasMainPack(player));
				ServerBossBarVisibilitySystem.ensureServerHudPriority(player);
			}

			PLAYER_HUDS.entrySet().removeIf(entry -> {
				if (online.contains(entry.getKey())) {
					return false;
				}
				entry.getValue().removeAllPlayers();
				return true;
			});
			PLAYER_SPACER_HUDS.entrySet().removeIf(entry -> {
				if (online.contains(entry.getKey())) {
					return false;
				}
				entry.getValue().removeAllPlayers();
				return true;
			});
			PLAYER_SPACER_OVERLAY_TITLES.keySet().removeIf(playerId -> !online.contains(playerId));
		});
	}

	private static void showHud(ServerPlayer player, boolean hasPack) {
		boolean spacerBecameVisible = hasPack && showSpacerHud(player);
		if (!hasPack) {
			hideSpacerHud(player);
		}

		ServerBossEvent hud = PLAYER_HUDS.computeIfAbsent(player.getUUID(), id -> createHudEvent());
		float progress = SeasonStartSystem.shouldOverrideStabilityHud()
				? SeasonStartSystem.getStartupHudProgress()
				: (float) getStability() / (float) getMaxStability();
		progress = Math.max(0.0F, Math.min(1.0F, progress));

		hud.setName(getHudTitle(player, hasPack));
		hud.setColor(BossEvent.BossBarColor.YELLOW);
		hud.setProgress(progress);

		if (!hud.getPlayers().contains(player)) {
			hud.addPlayer(player);
		} else if (spacerBecameVisible) {
			hud.removePlayer(player);
			hud.addPlayer(player);
		}

		hud.setVisible(true);
	}

	private static void hideHud(ServerPlayer player) {
		ServerBossEvent hud = PLAYER_HUDS.get(player.getUUID());
		if (hud != null) {
			hud.removePlayer(player);
			if (hud.getPlayers().isEmpty()) {
				PLAYER_HUDS.remove(player.getUUID());
			}
		}

		ServerBossEvent spacer = PLAYER_SPACER_HUDS.get(player.getUUID());
		if (spacer != null) {
			spacer.removePlayer(player);
			if (spacer.getPlayers().isEmpty()) {
				PLAYER_SPACER_HUDS.remove(player.getUUID());
			}
		}
		PLAYER_SPACER_OVERLAY_TITLES.remove(player.getUUID());
	}

	private static ServerBossEvent createHudEvent() {
		ServerBossEvent event = new ServerBossEvent(
				Component.empty(),
				BossEvent.BossBarColor.YELLOW,
				BossEvent.BossBarOverlay.PROGRESS
		);
		event.setDarkenScreen(false);
		event.setPlayBossMusic(false);
		event.setCreateWorldFog(false);
		return event;
	}

	private static ServerBossEvent createSpacerHudEvent() {
		ServerBossEvent event = new ServerBossEvent(
				Component.empty(),
				BossEvent.BossBarColor.GREEN,
				BossEvent.BossBarOverlay.PROGRESS
		);
		event.setDarkenScreen(false);
		event.setPlayBossMusic(false);
		event.setCreateWorldFog(false);
		event.setProgress(0.0F);
		return event;
	}

	private static boolean showSpacerHud(ServerPlayer player) {
		ServerBossEvent spacer = PLAYER_SPACER_HUDS.computeIfAbsent(player.getUUID(), id -> createSpacerHudEvent());
		spacer.setName(PLAYER_SPACER_OVERLAY_TITLES.getOrDefault(player.getUUID(), Component.empty()));
		spacer.setColor(BossEvent.BossBarColor.GREEN);
		spacer.setProgress(0.0F);
		spacer.setVisible(true);

		if (spacer.getPlayers().contains(player)) {
			return false;
		}

		spacer.addPlayer(player);
		return true;
	}

	private static void hideSpacerHud(ServerPlayer player) {
		ServerBossEvent spacer = PLAYER_SPACER_HUDS.get(player.getUUID());
		if (spacer == null) {
			return;
		}

		spacer.removePlayer(player);
		if (spacer.getPlayers().isEmpty()) {
			PLAYER_SPACER_HUDS.remove(player.getUUID());
		}
		PLAYER_SPACER_OVERLAY_TITLES.remove(player.getUUID());
	}

	private static Component getHudTitle(ServerPlayer player, boolean hasPack) {
		if (hasPack) {
			return Component.literal(STABILITY_SYMBOL).withStyle(ServerStabilitySystem::applyPackStyle);
		}

		String language = player.clientInformation().language();
		if (language == null) {
			return textTitle("Stability");
		}

		String normalized = language.toLowerCase(Locale.ROOT);
		if (normalized.startsWith("ru")) {
			return textTitle("Стабильность");
		}
		if (normalized.startsWith("uk")) {
			return textTitle("Стабільність");
		}
		if (normalized.startsWith("ja")) {
			return textTitle("サーバー安定性");
		}
		if (normalized.startsWith("rpr")) {
			return textTitle("Послушанiя жѣлѣзнаго разума");
		}
		return textTitle("Stability");
	}

	private static Component textTitle(String title) {
		return Component.literal(title).withStyle(ServerStabilitySystem::applyTextStyle);
	}

	private static Style applyTextStyle(Style style) {
		return style.withColor(TITLE_COLOR).withBold(true).withItalic(false);
	}

	private static Style applyPackStyle(Style style) {
		return style.withColor(PACK_SYMBOL_COLOR).withBold(false).withItalic(false);
	}

	public static void emitFeedParticles(ServerLevel level, double x, double y, double z, int count) {
		if (level == null) {
			return;
		}
		level.sendParticles(FEED_PARTICLE, x + 0.1D, y + 1.0D, z + 0.1D, Math.max(1, count), 0.1D, 0.0D, 0.1D, 0.0D);
	}

	public static int getStability() {
		return stability;
	}

	public static double getStabilityPercent() {
		int max = getMaxStability();
		if (max <= 0) {
			return 0.0D;
		}

		double percent = ((double) stability / (double) max) * 100.0D;
		return Math.max(0.0D, Math.min(100.0D, percent));
	}

	public static void setStability(int value) {
		int clamped = clamp(value);
		if (stability == clamped) {
			return;
		}

		stability = clamped;
		if (stabilityStateLoaded) {
			stabilityDirty = true;
		}
	}

	private static void tickStabilityDecay(MinecraftServer server) {
		if (server == null || SeasonStartSystem.shouldSuspendStabilitySystem()) {
			return;
		}
		long intervalTicks = Math.max(1L, (long) getEffectiveDecayIntervalSeconds(server) * 20L);
		int maxStability = getMaxStability();
		long tickNow = server.overworld().getGameTime();
		decayTickCounter++;

		while (decayTickCounter >= intervalTicks) {
			decayTickCounter -= intervalTicks;
			int before = getStability();
			setStability(before - 1);
			int after = getStability();
			if (before >= maxStability && after < maxStability) {
				// Leaving max should not force an immediate replay; keep at most one full-track cooldown.
				long relaxedCooldownTick = tickNow + FEED_SOUND_BASE_DURATION_TICKS;
				if (nextFeedSoundAllowedTick > relaxedCooldownTick) {
					nextFeedSoundAllowedTick = relaxedCooldownTick;
				}
			}
		}
	}

	/** Maintains the final five-minute alarm and begins the terminal shutdown at zero. */
	private static void tickCriticalStability(MinecraftServer server) {
		if (server == null || !stabilityStateLoaded || SeasonStartSystem.shouldSuspendStabilitySystem()) {
			return;
		}

		long nowTick = server.overworld().getGameTime();
		if (getStability() <= 0) {
			shutdownForNonpayment(server);
			return;
		}

		long remainingTicks = getEstimatedTicksUntilShutdown(server);
		if (remainingTicks > STABILITY_SIREN_START_TICKS) {
			stopStabilitySiren(server);
			return;
		}
		tickStabilitySiren(server, nowTick);
	}

	private static void tickStabilitySiren(MinecraftServer server, long nowTick) {
		if (stabilitySirenStartedTick == Long.MIN_VALUE) {
			stabilitySirenStartedTick = nowTick;
			nextStabilitySirenTick = nowTick;
			nextNonpaymentAnnouncementTick = nowTick + STABILITY_NONPAYMENT_ANNOUNCEMENT_DELAY_TICKS;
		}
		if (nowTick >= nextStabilitySirenTick) {
			playStabilitySiren(server, nowTick);
			nextStabilitySirenTick = nowTick + STABILITY_SIREN_REPEAT_TICKS;
		}
		if (nowTick >= nextNonpaymentAnnouncementTick) {
			SeasonStartVoiceSystem.stopAllNarration();
			SeasonStartVoiceSystem.fireTrigger(server, "stability_nonpayment_alarm", null);
			nextNonpaymentAnnouncementTick = nowTick + STABILITY_NONPAYMENT_ANNOUNCEMENT_REPEAT_TICKS;
		}
	}

	private static void playStabilitySiren(MinecraftServer server, long nowTick) {
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			if (player.connection == null || RendererBotPresenceSystem.isRendererBot(player)) {
				continue;
			}
			player.connection.send(new ClientboundSoundEntityPacket(
					STABILITY_SIREN_SOUND,
					SoundSource.AMBIENT,
					player,
					STABILITY_SIREN_VOLUME,
					1.0F,
					player.getRandom().nextLong() ^ nowTick
			));
		}
	}

	private static void stopStabilitySiren(MinecraftServer server) {
		if (server != null && stabilitySirenStartedTick != Long.MIN_VALUE) {
			for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
				if (player != null && player.connection != null) {
					player.connection.send(new ClientboundStopSoundPacket(STABILITY_SIREN_SOUND_ID, SoundSource.AMBIENT));
				}
			}
		}
		resetStabilitySiren();
	}

	private static void resetStabilitySiren() {
		stabilitySirenStartedTick = Long.MIN_VALUE;
		nextStabilitySirenTick = Long.MIN_VALUE;
		nextNonpaymentAnnouncementTick = Long.MIN_VALUE;
	}

	private static long getEstimatedTicksUntilShutdown(MinecraftServer server) {
		if (server == null || getStability() <= 0) {
			return 0L;
		}
		long intervalTicks = Math.max(1L, (long) getEffectiveDecayIntervalSeconds(server) * 20L);
		long ticksUntilNextPoint = Math.max(1L, intervalTicks - Math.min(decayTickCounter, intervalTicks - 1L));
		return Math.max(0L, ticksUntilNextPoint + (long) Math.max(0, getStability() - 1) * intervalTicks);
	}

	private static void shutdownForNonpayment(MinecraftServer server) {
		if (shutdownHaltRequested) {
			return;
		}
		shutdownHaltRequested = true;
		stopStabilitySiren(server);
		SeasonStartVoiceSystem.stopAllNarration();
		stopAllFeedSoundSources(server);
		resetStabilityForNextBoot(server);
		kickPlayersForNonpayment(server);
		server.halt(false);
	}

	private static void resetStabilityForNextBoot(MinecraftServer server) {
		setStability(getMaxStability());
		decayTickCounter = 0L;
		pendingStabilityFraction = 0.0D;
		stabilityDirty = true;
		savePersistedStability(server);
	}

	private static void kickPlayersForNonpayment(MinecraftServer server) {
		for (ServerPlayer player : new ArrayList<>(AccountAuthSystem.authenticatedPlayers(server))) {
			if (player != null && player.connection != null) {
				player.connection.disconnect(NONPAYMENT_DISCONNECT_REASON);
			}
		}
	}

	private static void tickBitcoinOfferings() {
		if (SeasonStartSystem.shouldSuspendStabilitySystem()) {
			return;
		}
		for (ItemEntity itemEntity : TRACKED_BITCOIN_ITEMS) {
			if (itemEntity.isRemoved() || !itemEntity.isAlive()) {
				TRACKED_BITCOIN_ITEMS.remove(itemEntity);
				continue;
			}

			ItemStack stack = itemEntity.getItem();
			if (!stack.is(ModItems.BITCOIN) || stack.isEmpty()) {
				TRACKED_BITCOIN_ITEMS.remove(itemEntity);
				continue;
			}

			if (!(itemEntity.level() instanceof ServerLevel serverLevel)) {
				continue;
			}

			BlockPos serverPos = findServerBlockForBitcoinOffering(itemEntity, serverLevel);
			if (serverPos == null) {
				continue;
			}

			consumeBitcoinOffering(itemEntity, serverLevel, serverPos);

			if (itemEntity.isRemoved()
					|| itemEntity.getItem().isEmpty()
					|| !itemEntity.getItem().is(ModItems.BITCOIN)) {
				TRACKED_BITCOIN_ITEMS.remove(itemEntity);
			}
		}
	}

	private static boolean consumeBitcoinOffering(ItemEntity itemEntity, ServerLevel level, BlockPos serverPos) {
		int max = getMaxStability();
		int before = getStability();
		int missing = max - before;
		if (missing <= 0) {
			// Prevent very long pitch-based cooldowns from blocking playback after stability drops from max.
			long relaxedCooldownTick = level.getGameTime() + FEED_SOUND_BASE_DURATION_TICKS;
			if (nextFeedSoundAllowedTick > relaxedCooldownTick) {
				nextFeedSoundAllowedTick = relaxedCooldownTick;
			}
			return false;
		}

		ItemStack stack = itemEntity.getItem();
		if (!stack.is(ModItems.BITCOIN) || stack.isEmpty()) {
			return false;
		}

		int availableBitcoins = stack.getCount();
		double bitcoinsPerStability = getBitcoinsPerStability();
		int bitcoinsToConsume = availableBitcoins;

		double requiredGain = missing - pendingStabilityFraction;
		if (requiredGain > 0.0D) {
			int neededToFill = (int) Math.ceil(requiredGain * bitcoinsPerStability - 1.0E-9D);
			bitcoinsToConsume = Math.min(availableBitcoins, Math.max(1, neededToFill));
		}

		double totalGain = pendingStabilityFraction + (bitcoinsToConsume / bitcoinsPerStability);
		int gainedPoints = Math.min((int) Math.floor(totalGain), missing);
		double nextFraction = totalGain - gainedPoints;

		if (gainedPoints > 0) {
			setStability(before + gainedPoints);
		}

		if (getStability() >= max) {
			pendingStabilityFraction = 0.0D;
		} else {
			pendingStabilityFraction = nextFraction;
		}

		if (bitcoinsToConsume >= availableBitcoins) {
			itemEntity.discard();
		} else {
			stack.shrink(bitcoinsToConsume);
			itemEntity.setItem(stack);
		}

		handleFeedFeedback(level, itemEntity, serverPos);
		SeasonStartSystem.onServerFed(level, serverPos);
		return true;
	}

	private static void handleFeedFeedback(ServerLevel level, ItemEntity itemEntity, BlockPos serverPos) {
		double x = itemEntity.getX();
		double y = itemEntity.getY();
		double z = itemEntity.getZ();
		long tickNow = level.getGameTime();
		emitFeedParticles(level, x, y, z, 10);

		float pitch = getFeedSoundPitch();
		if (pitch <= 0.01F) {
			return;
		}

		// Cap stale low-pitch cooldowns so quick follow-up feedings do not lock playback for too long.
		long maxAllowedTick = tickNow + FEED_SOUND_BASE_DURATION_TICKS;
		if (nextFeedSoundAllowedTick > maxAllowedTick) {
			nextFeedSoundAllowedTick = maxAllowedTick;
		}

		boolean canPlayPackMusic = tickNow >= nextFeedSoundAllowedTick;
		long soundDurationTicks = getFeedSoundCooldownTicks(pitch);
		if (canPlayPackMusic) {
			nextFeedSoundAllowedTick = tickNow + soundDurationTicks;
			startFeedSoundSource(level, serverPos, tickNow, tickNow + soundDurationTicks, pitch, FEED_MUSIC_SOUND_VOLUME, level.getRandom().nextLong(), false);
		}

		for (ServerPlayer player : level.players()) {
			if (!PolymerResourcePackUtils.hasMainPack(player)) {
				// No resource pack: use vanilla xp sound and allow overlaps on every feeding tick.
				player.connection.send(new ClientboundSoundPacket(
						BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.EXPERIENCE_ORB_PICKUP),
						SoundSource.PLAYERS,
						x,
						y,
						z,
						FEED_XP_SOUND_VOLUME,
						pitch,
						level.getRandom().nextLong()
				));
			}
		}
	}

	public static long playFeedMusicForStartup(ServerLevel level, BlockPos serverPos) {
		if (level == null || serverPos == null) {
			return FEED_SOUND_BASE_DURATION_TICKS;
		}
		long tickNow = level.getGameTime();
		float pitch = STARTUP_FEED_MUSIC_PITCH;
		long soundDurationTicks = getFeedSoundCooldownTicks(pitch);
		nextFeedSoundAllowedTick = tickNow + soundDurationTicks;
		startFeedSoundSource(level, serverPos, tickNow, tickNow + soundDurationTicks, pitch, STARTUP_FEED_MUSIC_VOLUME, level.getRandom().nextLong(), true);
		return soundDurationTicks;
	}

	public static long getStartupFeedMusicDurationTicks() {
		return getFeedSoundCooldownTicks(STARTUP_FEED_MUSIC_PITCH);
	}

	private static void startFeedSoundSource(
			ServerLevel level,
			BlockPos serverPos,
			long startTick,
			long endTick,
			float pitch,
			float volume,
			long seed,
			boolean centeredOnListener
	) {
		FeedSoundSourceKey key = new FeedSoundSourceKey(level.dimension(), serverPos.immutable());
		ActiveFeedSoundSource previous = ACTIVE_FEED_SOUND_SOURCES.remove(key);
		if (previous != null) {
			stopFeedSoundForListeners(level.getServer(), previous);
		}

		ActiveFeedSoundSource source = new ActiveFeedSoundSource(key, startTick, endTick, pitch, volume, seed, centeredOnListener);
		ACTIVE_FEED_SOUND_SOURCES.put(key, source);
		syncFeedSoundSource(level, source);
	}

	private static void tickActiveFeedSoundSources(MinecraftServer server) {
		if (ACTIVE_FEED_SOUND_SOURCES.isEmpty()) {
			return;
		}

		Iterator<ActiveFeedSoundSource> iterator = ACTIVE_FEED_SOUND_SOURCES.values().iterator();
		while (iterator.hasNext()) {
			ActiveFeedSoundSource source = iterator.next();
			ServerLevel level = server.getLevel(source.key.dimension());
			long now = level == null ? Long.MAX_VALUE : level.getGameTime();
			if (level == null || now >= source.endTick || (!source.centeredOnListener && isFeedSoundSourceGone(level, source.key.pos()))) {
				stopFeedSoundForListeners(server, source);
				iterator.remove();
				continue;
			}
			syncFeedSoundSource(level, source);
		}
	}

	private static boolean isFeedSoundSourceGone(ServerLevel level, BlockPos pos) {
		return level.hasChunkAt(pos) && !level.getBlockState(pos).is(ModBlocks.SERVER);
	}

	private static void syncFeedSoundSource(ServerLevel level, ActiveFeedSoundSource source) {
		Set<UUID> onlineInLevel = new HashSet<>();
		double x = source.key.pos().getX() + 0.5D;
		double y = source.key.pos().getY() + 0.5D;
		double z = source.key.pos().getZ() + 0.5D;
		if (source.centeredOnListener) {
			syncStartupFeedMusicSource(level, source, onlineInLevel, x, y, z);
			return;
		}
		long now = level.getGameTime();
		int segmentTicks = getFeedMusicSegmentTicks(source.pitch);
		int segmentIndex = (int) ((now - source.startTick) / segmentTicks);
		if (segmentIndex < 0 || segmentIndex >= FEED_MUSIC_SEGMENT_COUNT) {
			return;
		}

		for (ServerPlayer player : level.players()) {
			onlineInLevel.add(player.getUUID());
			UUID playerId = player.getUUID();
			boolean shouldHear = PolymerResourcePackUtils.hasMainPack(player)
					&& (source.centeredOnListener || player.distanceToSqr(x, y, z) <= FEED_MUSIC_SOUND_RADIUS * FEED_MUSIC_SOUND_RADIUS);
			if (!shouldHear) {
				continue;
			}
			// Each part is the remaining tail of the same track. Send exactly one tail when
			// this listener first enters range; sending every subsequent tail stacks audio.
			if (source.lastSegmentByListener.containsKey(playerId)) {
				continue;
			}
			double soundX = source.centeredOnListener ? player.getX() : x;
			double soundY = source.centeredOnListener ? player.getEyeY() : y;
			double soundZ = source.centeredOnListener ? player.getZ() : z;
			playFeedMusicSegmentForPlayer(player, soundX, soundY, soundZ, segmentIndex, source.pitch, source.volume, source.seed + segmentIndex);
			source.lastSegmentByListener.put(playerId, segmentIndex);
		}

		List<UUID> staleListeners = new ArrayList<>();
		for (UUID playerId : source.lastSegmentByListener.keySet()) {
			if (!onlineInLevel.contains(playerId)) {
				staleListeners.add(playerId);
			}
		}
		for (UUID playerId : staleListeners) {
			source.lastSegmentByListener.remove(playerId);
		}
	}

	private static void syncStartupFeedMusicSource(
			ServerLevel level,
			ActiveFeedSoundSource source,
			Set<UUID> onlineInLevel,
			double x,
			double y,
			double z
	) {
		for (ServerPlayer player : level.players()) {
			if (RendererBotPresenceSystem.isRendererBot(player)) {
				source.lastSegmentByListener.remove(player.getUUID());
				continue;
			}
			onlineInLevel.add(player.getUUID());
			UUID playerId = player.getUUID();
			// Sound packets are harmless when a client declined the resource pack, but
			// filtering them here made the finale silent for otherwise valid players.
			if (player.connection == null) {
				source.lastSegmentByListener.remove(playerId);
				continue;
			}
			if (source.lastSegmentByListener.containsKey(playerId)) {
				continue;
			}
			playStartupFeedMusicForPlayer(player, x, y, z, source.pitch, source.volume, source.seed);
			source.lastSegmentByListener.put(playerId, 0);
		}

		List<UUID> staleListeners = new ArrayList<>();
		for (UUID playerId : source.lastSegmentByListener.keySet()) {
			if (!onlineInLevel.contains(playerId)) {
				staleListeners.add(playerId);
			}
		}
		for (UUID playerId : staleListeners) {
			source.lastSegmentByListener.remove(playerId);
		}
	}

	private static void stopFeedSoundForListeners(MinecraftServer server, ActiveFeedSoundSource source) {
		if (server != null && source.centeredOnListener) {
			for (UUID playerId : source.lastSegmentByListener.keySet()) {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player == null || player.connection == null) {
					continue;
				}
				player.connection.send(new ClientboundStopSoundPacket(STARTUP_FEED_MUSIC_SOUND_ID, SoundSource.AMBIENT));
			}
		}
		source.lastSegmentByListener.clear();
	}

	private static void stopAllFeedSoundSources(MinecraftServer server) {
		for (ActiveFeedSoundSource source : ACTIVE_FEED_SOUND_SOURCES.values()) {
			stopFeedSoundForListeners(server, source);
		}
		ACTIVE_FEED_SOUND_SOURCES.clear();
	}

	private static void playFeedMusicSegmentForPlayer(
			ServerPlayer player,
			double x,
			double y,
			double z,
			int segmentIndex,
			float pitch,
			float volume,
			long seed
	) {
		player.connection.send(new ClientboundSoundPacket(
				FEED_MUSIC_SEGMENTS[segmentIndex],
				SoundSource.AMBIENT,
				x,
				y,
				z,
				volume,
				pitch,
				seed
		));
	}

	private static void playStartupFeedMusicForPlayer(
			ServerPlayer player,
			double x,
			double y,
			double z,
			float pitch,
			float volume,
			long seed
	) {
		if (player == null || player.connection == null) {
			return;
		}
		player.connection.send(new ClientboundStopSoundPacket(STARTUP_FEED_MUSIC_SOUND_ID, SoundSource.AMBIENT));
		player.connection.send(new ClientboundSoundPacket(
				STARTUP_FEED_MUSIC_SOUND,
				// Keep this on AMBIENT, alongside the earthquake. Some players mute the
				// music slider, while the finale is a world event rather than UI music.
				SoundSource.AMBIENT,
				x,
				y,
				z,
				volume,
				pitch,
				seed
		));
	}

	private static SimpleParticleType resolveFeedParticle() {
		ParticleType<?> byId = BuiltInRegistries.PARTICLE_TYPE.getValue(
				Identifier.fromNamespaceAndPath("minecraft", "trial_spawner_detection")
		);
		if (byId instanceof SimpleParticleType simpleParticleType) {
			return simpleParticleType;
		}
		return ParticleTypes.TRIAL_SPAWNER_DETECTED_PLAYER;
	}

	private static BlockPos findServerBlockForBitcoinOffering(ItemEntity itemEntity, ServerLevel level) {
		double itemX = itemEntity.getX();
		double itemY = itemEntity.getY();
		double itemZ = itemEntity.getZ();
		double maxDistanceSq = BITCOIN_FEED_RADIUS * BITCOIN_FEED_RADIUS;
		BlockPos entityPos = itemEntity.blockPosition();

		for (int dx = -BITCOIN_FEED_SCAN_RADIUS; dx <= BITCOIN_FEED_SCAN_RADIUS; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -BITCOIN_FEED_SCAN_RADIUS; dz <= BITCOIN_FEED_SCAN_RADIUS; dz++) {
					BlockPos candidatePos = entityPos.offset(dx, dy, dz);
					if (!level.getBlockState(candidatePos).is(ModBlocks.SERVER)) {
						continue;
					}

					double minX = candidatePos.getX();
					double minY = candidatePos.getY();
					double minZ = candidatePos.getZ();
					double maxX = minX + 1.0D;
					double maxY = minY + 1.0D;
					double maxZ = minZ + 1.0D;

					double deltaX = axisDistance(itemX, minX, maxX);
					double deltaY = axisDistance(itemY, minY, maxY);
					double deltaZ = axisDistance(itemZ, minZ, maxZ);
					double distanceSq = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;

					if (distanceSq <= maxDistanceSq) {
						return candidatePos.immutable();
					}
				}
			}
		}

		return null;
	}

	private static double axisDistance(double value, double min, double max) {
		if (value < min) {
			return min - value;
		}
		if (value > max) {
			return value - max;
		}
		return 0.0D;
	}

	private static boolean isLookingAtServerBlock(ServerPlayer player) {
		HitResult hit = player.pick(6.0D, 0.0F, false);
		if (hit.getType() != HitResult.Type.BLOCK) {
			return false;
		}

		BlockHitResult blockHit = (BlockHitResult) hit;
		return player.level().getBlockState(blockHit.getBlockPos()).is(ModBlocks.SERVER);
	}

	public static void activateStabilityPotion(ServerPlayer player, int durationTicks, boolean anyWorldVisibility) {
		if (durationTicks <= 0) {
			return;
		}
		player.addEffect(createStabilityPotionEffect(durationTicks, anyWorldVisibility));
	}

	public static MobEffectInstance createStabilityPotionEffect(int durationTicks, boolean anyWorldVisibility) {
		return new MobEffectInstance(MobEffects.UNLUCK, durationTicks, anyWorldVisibility ? 1 : 0, false, false, true);
	}

	public static void onServerStructurePlaced(ServerLevel level, BlockPos anchor) {
		if (trackServerAnchor(level, anchor)) {
			stabilityDirty = true;
		}
	}

	public static void onServerStructureRemoved(ServerLevel level, BlockPos anchor) {
		if (untrackServerAnchor(level, anchor)) {
			stabilityDirty = true;
		}
	}

	private static boolean hasStabilityPotionVision(ServerPlayer player) {
		MobEffectInstance effect = getStabilityPotionEffect(player);
		if (effect == null) {
			return false;
		}
		if (effect.getAmplifier() >= 1) {
			return true;
		}
		if (!(player.level() instanceof ServerLevel level)) {
			return false;
		}
		return hasTrackedServerInWorld(level);
	}

	private static MobEffectInstance getStabilityPotionEffect(ServerPlayer player) {
		return player.getEffect(MobEffects.UNLUCK);
	}

	private static void spawnStabilityPotionParticles(ServerPlayer player) {
		MobEffectInstance effect = getStabilityPotionEffect(player);
		if (effect == null || !(player.level() instanceof ServerLevel level)) {
			return;
		}

		long gameTime = level.getGameTime();
		if ((gameTime + player.getId()) % 5L != 0L) {
			return;
		}

		double halfWidth = player.getBbWidth() * 0.35D;
		double height = player.getBbHeight();
		level.sendParticles(
				STABILITY_POTION_PARTICLE,
				player.getX(),
				player.getY() + height * 0.5D,
				player.getZ(),
				2,
				halfWidth,
				height * 0.35D,
				halfWidth,
				0.0D
		);
	}

	private static boolean hasTrackedServerInWorld(ServerLevel level) {
		String dimensionId = dimensionId(level.dimension());
		Set<String> anchors = TRACKED_SERVER_ANCHORS.get(dimensionId);
		if (anchors != null && !anchors.isEmpty()) {
			return true;
		}
		return discoverLoadedServerAnchors(level);
	}

	private static boolean discoverLoadedServerAnchors(ServerLevel level) {
		boolean discoveredAny = false;
		for (var entity : level.getAllEntities()) {
			if (!ServerStructureBreakSystem.isServerStructureDisplay(entity)) {
				continue;
			}
			var anchor = ServerStructureBreakSystem.getServerStructureDisplayAnchor(entity);
			if (anchor.isEmpty()) {
				continue;
			}
			discoveredAny |= trackServerAnchor(level, anchor.get());
		}
		if (discoveredAny) {
			stabilityDirty = true;
		}
		Set<String> anchors = TRACKED_SERVER_ANCHORS.get(dimensionId(level.dimension()));
		return anchors != null && !anchors.isEmpty();
	}

	private static boolean trackServerAnchor(ServerLevel level, BlockPos anchor) {
		return TRACKED_SERVER_ANCHORS
				.computeIfAbsent(dimensionId(level.dimension()), ignored -> new HashSet<>())
				.add(serializeBlockPos(anchor));
	}

	private static boolean untrackServerAnchor(ServerLevel level, BlockPos anchor) {
		String dimensionId = dimensionId(level.dimension());
		Set<String> anchors = TRACKED_SERVER_ANCHORS.get(dimensionId);
		if (anchors == null) {
			return false;
		}
		boolean removed = anchors.remove(serializeBlockPos(anchor));
		if (anchors.isEmpty()) {
			TRACKED_SERVER_ANCHORS.remove(dimensionId);
		}
		return removed;
	}

	private static void bootstrapTrackedServerAnchors(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			discoverLoadedServerAnchors(level);
		}
	}

	private static String dimensionId(net.minecraft.resources.ResourceKey<Level> dimension) {
		return dimension.identifier().toString();
	}

	private static String serializeBlockPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	public static boolean isHudBossBar(ServerPlayer player, UUID bossBarId) {
		ServerBossEvent hud = PLAYER_HUDS.get(player.getUUID());
		if (hud != null && hud.getId().equals(bossBarId)) {
			return true;
		}

		ServerBossEvent spacer = PLAYER_SPACER_HUDS.get(player.getUUID());
		return spacer != null && spacer.getId().equals(bossBarId);
	}

	/**
	 * Uses the pre-existing empty bossbar immediately above the server HUD as a
	 * title-only overlay. Returning false means that the server HUD is not open.
	 */
	public static boolean setSpacerHudOverlayTitle(ServerPlayer player, Component title) {
		if (player == null) {
			return false;
		}
		ServerBossEvent spacer = PLAYER_SPACER_HUDS.get(player.getUUID());
		if (spacer == null || !spacer.getPlayers().contains(player)) {
			return false;
		}
		Component safeTitle = title == null ? Component.empty() : title.copy();
		PLAYER_SPACER_OVERLAY_TITLES.put(player.getUUID(), safeTitle);
		spacer.setName(safeTitle);
		return true;
	}

	public static void clearSpacerHudOverlayTitle(ServerPlayer player) {
		if (player == null) {
			return;
		}
		PLAYER_SPACER_OVERLAY_TITLES.remove(player.getUUID());
		ServerBossEvent spacer = PLAYER_SPACER_HUDS.get(player.getUUID());
		if (spacer != null && spacer.getPlayers().contains(player)) {
			spacer.setName(Component.empty());
		}
	}

	public static void reorderHudBelowExternalBossBar(ServerPlayer player) {
		if (player == null) {
			return;
		}

		ServerBossEvent spacer = PLAYER_SPACER_HUDS.get(player.getUUID());
		if (spacer != null && spacer.getPlayers().contains(player)) {
			spacer.removePlayer(player);
			spacer.addPlayer(player);
		}

		ServerBossEvent hud = PLAYER_HUDS.get(player.getUUID());
		if (hud != null && hud.getPlayers().contains(player)) {
			hud.removePlayer(player);
			hud.addPlayer(player);
		}
	}

	private static int getMaxStability() {
		return Math.max(1, Lg2Config.get().stabilityMax);
	}

	private static int getDecayIntervalSeconds() {
		return Math.max(1, Lg2Config.get().stabilityDecayIntervalSeconds);
	}

	private static int getDecayIntervalSecondsPerPlayer() {
		return Math.max(0, Lg2Config.get().stabilityDecayIntervalSecondsPerPlayer);
	}

	private static int getEffectiveDecayIntervalSeconds(MinecraftServer server) {
		int baseIntervalSeconds = getDecayIntervalSeconds();
		int onlinePlayers = server.getPlayerList().getPlayerCount();
		long reductionSeconds = (long) onlinePlayers * getDecayIntervalSecondsPerPlayer();
		long effectiveIntervalSeconds = (long) baseIntervalSeconds - reductionSeconds;
		return (int) Math.max(1L, effectiveIntervalSeconds);
	}

	private static double getBitcoinsPerStability() {
		double value = Lg2Config.get().bitcoinsPerStability;
		if (!Double.isFinite(value) || value <= 0.0D) {
			return 1.0D;
		}
		return value;
	}

	private static float getFeedSoundPitch() {
		int max = getMaxStability();
		if (max <= 0) {
			return 1.0F;
		}

		float normalized = (float) getStability() / (float) max;
		if (normalized >= 0.5F) {
			return 1.0F;
		}

		float pitch = normalized / 0.5F;
		return Math.max(0.0F, Math.min(1.0F, pitch));
	}

	private static long getFeedSoundCooldownTicks(float pitch) {
		if (pitch <= 0.01F) {
			return FEED_SOUND_BASE_DURATION_TICKS;
		}

		long scaled = (long) Math.ceil(FEED_SOUND_BASE_DURATION_TICKS / pitch);
		return Math.max(FEED_SOUND_BASE_DURATION_TICKS, scaled);
	}

	private static int getFeedMusicSegmentTicks(float pitch) {
		if (pitch <= 0.01F) {
			return FEED_MUSIC_SEGMENT_TICKS;
		}
		return Math.max(1, (int) Math.ceil(FEED_MUSIC_SEGMENT_TICKS / pitch));
	}

	private static int clamp(int value) {
		return Math.max(0, Math.min(getMaxStability(), value));
	}

	private static Path getStabilityStatePath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(STABILITY_STATE_FILE_NAME);
	}

	private static void loadPersistedStability(MinecraftServer server) {
		Path path = getStabilityStatePath(server);
		boolean dirtyAfterLoad = false;
		TRACKED_SERVER_ANCHORS.clear();

		if (!Files.exists(path)) {
			setStability(getMaxStability());
			bootstrapTrackedServerAnchors(server);
			stabilityStateLoaded = true;
			stabilityDirty = true;
			return;
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			StabilityState state = STABILITY_STATE_GSON.fromJson(reader, StabilityState.class);
			if (state == null) {
				setStability(getMaxStability());
				dirtyAfterLoad = true;
			} else {
				setStability(state.stability);
				if (state.trackedServerAnchors != null) {
					for (Map.Entry<String, Set<String>> entry : state.trackedServerAnchors.entrySet()) {
						Set<String> anchors = entry.getValue();
						if (anchors == null || anchors.isEmpty()) {
							continue;
						}
						TRACKED_SERVER_ANCHORS.put(entry.getKey(), new HashSet<>(anchors));
					}
				}
			}

			if (TRACKED_SERVER_ANCHORS.isEmpty()) {
				bootstrapTrackedServerAnchors(server);
				if (!TRACKED_SERVER_ANCHORS.isEmpty()) {
					dirtyAfterLoad = true;
				}
			}

			stabilityStateLoaded = true;
			stabilityDirty = dirtyAfterLoad;
		} catch (Exception e) {
			Lg2.LOGGER.warn("Failed to read persisted stability from {}", path, e);
			setStability(getMaxStability());
			bootstrapTrackedServerAnchors(server);
			stabilityStateLoaded = true;
			stabilityDirty = true;
		}
	}

	private static void savePersistedStability(MinecraftServer server) {
		if (!stabilityStateLoaded || !stabilityDirty) {
			return;
		}

		Path path = getStabilityStatePath(server);
		StabilityState state = new StabilityState();
		state.stability = getStability();
		if (!TRACKED_SERVER_ANCHORS.isEmpty()) {
			state.trackedServerAnchors = new HashMap<>();
			for (Map.Entry<String, Set<String>> entry : TRACKED_SERVER_ANCHORS.entrySet()) {
				if (entry.getValue().isEmpty()) {
					continue;
				}
				state.trackedServerAnchors.put(entry.getKey(), new HashSet<>(entry.getValue()));
			}
		}

		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				STABILITY_STATE_GSON.toJson(state, writer);
			}
			stabilityDirty = false;
		} catch (IOException e) {
			Lg2.LOGGER.warn("Failed to save persisted stability to {}", path, e);
		}
	}
}
