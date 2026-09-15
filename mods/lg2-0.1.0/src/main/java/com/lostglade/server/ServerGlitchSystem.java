package com.lostglade.server;

import com.google.gson.JsonObject;
import com.lostglade.Lg2;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.glitch.BlockUseGlitchHandler;
import com.lostglade.server.glitch.BitcoinOvercookGlitch;
import com.lostglade.server.glitch.ChatInterferenceGlitch;
import com.lostglade.server.glitch.ChatMessageGlitchHandler;
import com.lostglade.server.glitch.BlackoutGlitch;
import com.lostglade.server.glitch.ChestDesyncGlitch;
import com.lostglade.server.glitch.CheckpointDesyncGlitch;
import com.lostglade.server.glitch.EntityUseGlitchHandler;
import com.lostglade.server.glitch.GravitySurgeGlitch;
import com.lostglade.server.glitch.InventoryTextureShuffleGlitch;
import com.lostglade.server.glitch.PhantomChunkGlitch;
import com.lostglade.server.glitch.PhantomMobGlitch;
import com.lostglade.server.glitch.PhantomSoundGlitch;
import com.lostglade.server.glitch.PlayerShuffleGlitch;
import com.lostglade.server.glitch.RespawnGlitchHandler;
import com.lostglade.server.glitch.ServerGlitchHandler;
import com.lostglade.server.glitch.TimeOfDayJumpGlitch;
import com.lostglade.server.glitch.UpsideDownMobGlitch;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ServerGlitchSystem {
	private static final String CHAT_INTERFERENCE_ID = "chat_interference";
	private static final String CHECKPOINT_DESYNC_ID = "checkpoint_desync";
	private static final String BITCOIN_OVERCOOK_ID = "bitcoin_overcook";

	private static final Map<String, ServerGlitchHandler> HANDLERS = new LinkedHashMap<>();
	private static final Map<String, Long> NEXT_ALLOWED_TICKS = new HashMap<>();
	private static final Set<String> UNKNOWN_CONFIG_IDS_LOGGED = new HashSet<>();

	private static long checkTicker = 0L;

	private ServerGlitchSystem() {
	}

	public static void register() {
		HANDLERS.clear();
		NEXT_ALLOWED_TICKS.clear();
		UNKNOWN_CONFIG_IDS_LOGGED.clear();
		checkTicker = 0L;

		// Add new glitch types here: implement ServerGlitchHandler and register once.
		registerHandler(new PhantomSoundGlitch());
		registerHandler(new ChatInterferenceGlitch());
		registerHandler(new CheckpointDesyncGlitch());
		registerHandler(new TimeOfDayJumpGlitch());
		registerHandler(new InventoryTextureShuffleGlitch());
		registerHandler(new BlackoutGlitch());
		registerHandler(new GravitySurgeGlitch());
		registerHandler(new PhantomChunkGlitch());
		registerHandler(new PhantomMobGlitch());
		registerHandler(new UpsideDownMobGlitch());
		registerHandler(new PlayerShuffleGlitch());
		registerHandler(new ChestDesyncGlitch());
		registerHandler(new BitcoinOvercookGlitch());
		reloadConfig();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			checkTicker = 0L;
			NEXT_ALLOWED_TICKS.clear();
			ChestDesyncGlitch.resetTracking();
			BitcoinOvercookGlitch.resetRuntimeState();
			GravitySurgeGlitch.resetRuntimeState();
			PhantomChunkGlitch.resetRuntimeState();
			PhantomMobGlitch.resetRuntimeState();
			PhantomMobGlitch.discardLingeringPhantoms(server);
			UpsideDownMobGlitch.resetRuntimeState();
			UpsideDownMobGlitch.restoreAll(server);
			PlayerShuffleGlitch.resetRuntimeState();
			PlayerShuffleGlitch.restoreAll(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(PhantomChunkGlitch::restoreAll);
		ServerLifecycleEvents.SERVER_STOPPING.register(UpsideDownMobGlitch::restoreAll);
		ServerLifecycleEvents.SERVER_STOPPING.register(PlayerShuffleGlitch::restoreAll);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(
						Commands.literal("serverglitch")
								.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
								.then(Commands.literal("reload")
										.executes(context -> {
											reloadConfig();
											context.getSource().sendSuccess(
													() -> Component.literal("Reloaded glitch config: " + HANDLERS.size() + " registered glitches"),
													true
											);
											return 1;
										}))
				)
		);

		ServerTickEvents.END_SERVER_TICK.register(ServerGlitchSystem::tick);
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ServerGlitchSystem::onAllowChatMessage);
		ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register(ServerGlitchSystem::onAllowCommandMessage);
		ServerPlayerEvents.COPY_FROM.register(ServerGlitchSystem::onCopyFrom);
		ServerPlayerEvents.AFTER_RESPAWN.register(ServerGlitchSystem::onAfterRespawn);
		UseBlockCallback.EVENT.register(ServerGlitchSystem::onUseBlock);
		UseEntityCallback.EVENT.register(ServerGlitchSystem::onUseEntity);
		AttackEntityCallback.EVENT.register(ServerGlitchSystem::onAttackEntity);
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			PhantomMobGlitch.handleEntityLoad(entity);
			UpsideDownMobGlitch.handleEntityLoad(entity);
		});
	}

	private static void registerHandler(ServerGlitchHandler handler) {
		ServerGlitchHandler previous = HANDLERS.put(handler.id(), handler);
		if (previous != null) {
			Lg2.LOGGER.warn("Duplicate glitch handler id '{}': {} replaced by {}",
					handler.id(), previous.getClass().getName(), handler.getClass().getName());
		}
	}

	private static void reloadConfig() {
		GlitchConfig.load(buildDefaultEntries());
		boolean changed = sanitizeHandlerSpecificSettings();
		if (changed) {
			GlitchConfig.save();
		}
	}

	private static Map<String, GlitchConfig.GlitchEntry> buildDefaultEntries() {
		Map<String, GlitchConfig.GlitchEntry> defaults = new LinkedHashMap<>();
		for (Map.Entry<String, ServerGlitchHandler> entry : HANDLERS.entrySet()) {
			defaults.put(entry.getKey(), copyEntry(entry.getValue().defaultEntry()));
		}
		return defaults;
	}

	private static boolean sanitizeHandlerSpecificSettings() {
		GlitchConfig.ConfigData config = GlitchConfig.get();
		if (config.glitches == null) {
			config.glitches = new LinkedHashMap<>();
		}

		boolean changed = false;
		for (Map.Entry<String, ServerGlitchHandler> handlerEntry : HANDLERS.entrySet()) {
			String id = handlerEntry.getKey();
			ServerGlitchHandler handler = handlerEntry.getValue();

			GlitchConfig.GlitchEntry entry = config.glitches.get(id);
			if (entry == null) {
				config.glitches.put(id, copyEntry(handler.defaultEntry()));
				changed = true;
				continue;
			}

			changed |= handler.sanitizeSettings(entry);
		}

		return changed;
	}

	private static void tick(MinecraftServer server) {
		ChestDesyncGlitch.tickPlacementTracking(server);
		InventoryTextureShuffleGlitch.tickActiveStates(server);
		BlackoutGlitch.tickActiveStates(server);
		GravitySurgeGlitch.tickActiveStates(server);
		PhantomChunkGlitch.tickActiveStates(server);
		PhantomMobGlitch.tickActiveStates(server);
		UpsideDownMobGlitch.tickActiveStates(server);
		PlayerShuffleGlitch.tickActiveStates(server);

		GlitchConfig.ConfigData config = GlitchConfig.get();
		if (!config.enabled) {
			return;
		}

		if (ServerBackroomsSystem.countPlayersOutsideBackrooms(server) <= 0) {
			return;
		}

		checkTicker++;
		int interval = Math.max(1, config.checkIntervalTicks);
		if (checkTicker < interval) {
			return;
		}
		checkTicker = 0L;

		triggerGlitches(server, config);
	}

	private static boolean onAllowChatMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params) {
		return handleBroadcastedPlayerMessage(message, sender, params, true);
	}

	private static boolean onAllowCommandMessage(PlayerChatMessage message, CommandSourceStack source, ChatType.Bound params) {
		if (source == null || !(source.getEntity() instanceof ServerPlayer sender)) {
			return true;
		}
		return handleBroadcastedPlayerMessage(message, sender, params, false);
	}

	private static boolean handleBroadcastedPlayerMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params, boolean routeCreditor) {
		if (sender == null || message == null || params == null) {
			return true;
		}
		if (ServerRaceSystem.handlePendingWomanShnyagaChatMessage(message, sender)) {
			return false;
		}

		MinecraftServer server = sender.level().getServer();
		if (server == null) {
			return true;
		}

		Component raceDisplayNameOverride = ServerRaceSystem.getChatDisplayNameOverride(sender);
		ChatType.Bound outgoingParams = raceDisplayNameOverride == null
				? params
				: new ChatType.Bound(params.chatType(), raceDisplayNameOverride, params.targetName());

		GlitchConfig.ConfigData config = GlitchConfig.get();
		if (!config.enabled) {
			ServerMilkPocketDimensionSystem.handleChatMessage(message, sender, params);
			if (routeCreditor) {
				AncientUkrCreditorChatSystem.handleDisplayedChatMessage(server, sender.getUUID(), message.signedContent());
			}
			if (raceDisplayNameOverride == null) {
				return true;
			}
			Component decorated = outgoingParams.decorate(Component.literal(message.signedContent()));
			server.getPlayerList().broadcastSystemMessage(decorated, false);
			return false;
		}

		boolean triggered = false;
		boolean senderInBackrooms = ServerBackroomsSystem.isInBackrooms(sender);
		if (senderInBackrooms) {
			return false;
		}

		ServerGlitchHandler baseHandler = HANDLERS.get(CHAT_INTERFERENCE_ID);
		if (baseHandler instanceof ChatMessageGlitchHandler chatHandler
				&& config.glitches != null
				&& !config.glitches.isEmpty()
		) {
			GlitchConfig.GlitchEntry entry = config.glitches.get(CHAT_INTERFERENCE_ID);
			if (entry != null && entry.enabled) {
				double stabilityPercent = ServerStabilitySystem.getStabilityPercent();
				if (stabilityPercent >= entry.minStabilityPercent && stabilityPercent <= entry.maxStabilityPercent) {
					long gameTime = server.overworld().getGameTime();
					long nextAllowedTick = NEXT_ALLOWED_TICKS.getOrDefault(CHAT_INTERFERENCE_ID, Long.MIN_VALUE);
					if (gameTime >= nextAllowedTick) {
						RandomSource random = server.overworld().getRandom();
						double baseChance = clamp01(entry.chancePerCheck);
						double influence = clamp01(entry.stabilityInfluence);
						double rangeInstabilityFactor = getRangeInstabilityFactor(
								stabilityPercent,
								entry.minStabilityPercent,
								entry.maxStabilityPercent
						);
						double effectiveChance = baseChance * ((1.0D - influence) + (rangeInstabilityFactor * influence));
						if (effectiveChance > 0.0D && random.nextDouble() <= effectiveChance) {
							triggered = chatHandler.triggerChat(server, random, entry, stabilityPercent, sender, message, outgoingParams, routeCreditor);
							if (triggered) {
								long cooldownTicks = Math.max(
										0L,
										getCooldownTicksForStability(entry, stabilityPercent)
								);
								NEXT_ALLOWED_TICKS.put(CHAT_INTERFERENCE_ID, gameTime + cooldownTicks);
							}
						}
					}
				}
			}
		}

		if (triggered) {
			return false;
		}

		ServerMilkPocketDimensionSystem.handleChatMessage(message, sender, params);
		Component decorated = outgoingParams.decorate(Component.literal(message.signedContent()));
		server.getPlayerList().broadcastSystemMessage(decorated, false);
		if (routeCreditor) {
			AncientUkrCreditorChatSystem.handleDisplayedChatMessage(server, sender.getUUID(), message.signedContent());
		}
		return false;
	}

	public static boolean handlePrivatePlayerMessageCommand(
			CommandSourceStack source,
			Collection<ServerPlayer> targets,
			PlayerChatMessage message
	) {
		if (source == null
				|| !(source.getEntity() instanceof ServerPlayer sender)
				|| targets == null
				|| targets.isEmpty()
				|| message == null) {
			return false;
		}

		GlitchConfig.ConfigData config = GlitchConfig.get();
		if (!config.enabled) {
			return false;
		}
		if (ServerBackroomsSystem.isInBackrooms(sender)) {
			return true;
		}

		MinecraftServer server = sender.level().getServer();
		if (server == null) {
			return false;
		}

		ServerGlitchHandler baseHandler = HANDLERS.get(CHAT_INTERFERENCE_ID);
		if (!(baseHandler instanceof ChatMessageGlitchHandler chatHandler)
				|| config.glitches == null
				|| config.glitches.isEmpty()) {
			return false;
		}

		GlitchConfig.GlitchEntry entry = config.glitches.get(CHAT_INTERFERENCE_ID);
		if (entry == null || !entry.enabled) {
			return false;
		}

		double stabilityPercent = ServerStabilitySystem.getStabilityPercent();
		if (stabilityPercent < entry.minStabilityPercent || stabilityPercent > entry.maxStabilityPercent) {
			return false;
		}

		long gameTime = server.overworld().getGameTime();
		long nextAllowedTick = NEXT_ALLOWED_TICKS.getOrDefault(CHAT_INTERFERENCE_ID, Long.MIN_VALUE);
		if (gameTime < nextAllowedTick) {
			return false;
		}

		RandomSource random = server.overworld().getRandom();
		double baseChance = clamp01(entry.chancePerCheck);
		double influence = clamp01(entry.stabilityInfluence);
		double rangeInstabilityFactor = getRangeInstabilityFactor(
				stabilityPercent,
				entry.minStabilityPercent,
				entry.maxStabilityPercent
		);
		double effectiveChance = baseChance * ((1.0D - influence) + (rangeInstabilityFactor * influence));
		if (effectiveChance <= 0.0D || random.nextDouble() > effectiveChance) {
			return false;
		}

		boolean triggered = chatHandler.triggerPrivateMessage(
				server,
				random,
				entry,
				stabilityPercent,
				source,
				sender,
				targets,
				message
		);
		if (!triggered) {
			return false;
		}

		long cooldownTicks = Math.max(0L, getCooldownTicksForStability(entry, stabilityPercent));
		NEXT_ALLOWED_TICKS.put(CHAT_INTERFERENCE_ID, gameTime + cooldownTicks);
		return true;
	}

	public static void onFurnaceSmelt(
			ServerLevel level,
			BlockPos pos,
			BlockState state,
			AbstractFurnaceBlockEntity furnace,
			RecipeHolder<? extends AbstractCookingRecipe> recipeHolder,
			SingleRecipeInput input
	) {
		if (ServerBackroomsSystem.isBackrooms(level)) {
			return;
		}

		GlitchConfig.ConfigData config = GlitchConfig.get();
		if (!config.enabled || config.glitches == null || config.glitches.isEmpty()) {
			return;
		}

		ServerGlitchHandler baseHandler = HANDLERS.get(BITCOIN_OVERCOOK_ID);
		if (!(baseHandler instanceof BitcoinOvercookGlitch overcookHandler)) {
			return;
		}

		GlitchConfig.GlitchEntry entry = config.glitches.get(BITCOIN_OVERCOOK_ID);
		if (entry == null) {
			return;
		}

		MinecraftServer server = level.getServer();
		if (server == null) {
			return;
		}

		if (!overcookHandler.matches(furnace, input)) {
			return;
		}

		RandomSource random = server.overworld().getRandom();
		overcookHandler.accumulateExperience(furnace, entry);
		UUID responsiblePlayerId = overcookHandler.getResponsiblePlayerId(level, pos);

		double stabilityPercent = ServerStabilitySystem.getStabilityPercent();
		long gameTime = server.overworld().getGameTime();
		boolean guaranteedFirstTrigger = entry.enabled
				&& responsiblePlayerId != null
				&& overcookHandler.hasGuaranteedFirstTrigger(server, responsiblePlayerId);
		boolean canTrigger = guaranteedFirstTrigger || (entry.enabled
				&& stabilityPercent >= entry.minStabilityPercent
				&& stabilityPercent <= entry.maxStabilityPercent);
		if (canTrigger) {
			long nextAllowedTick = NEXT_ALLOWED_TICKS.getOrDefault(BITCOIN_OVERCOOK_ID, Long.MIN_VALUE);
			canTrigger = guaranteedFirstTrigger || gameTime >= nextAllowedTick;
		}

		if (canTrigger && !guaranteedFirstTrigger) {
			double baseChance = clamp01(entry.chancePerCheck);
			double influence = clamp01(entry.stabilityInfluence);
			double rangeInstabilityFactor = getRangeInstabilityFactor(
					stabilityPercent,
					entry.minStabilityPercent,
					entry.maxStabilityPercent
			);
			double effectiveChance = baseChance * ((1.0D - influence) + (rangeInstabilityFactor * influence));
			canTrigger = effectiveChance > 0.0D && random.nextDouble() <= effectiveChance;
		}

		if (canTrigger && overcookHandler.triggerOnFurnaceSmelt(
				server,
				random,
				entry,
				stabilityPercent,
				level,
				pos,
				state,
				furnace,
				recipeHolder,
				input
		)) {
			if (guaranteedFirstTrigger) {
				overcookHandler.markGuaranteedFirstTriggerUsed(server, responsiblePlayerId);
			}
			long cooldownTicks = Math.max(0L, getCooldownTicksForStability(entry, stabilityPercent));
			NEXT_ALLOWED_TICKS.put(BITCOIN_OVERCOOK_ID, gameTime + cooldownTicks);
			return;
		}

		overcookHandler.clearResult(furnace);
	}

	private static void onCopyFrom(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
		if (alive || newPlayer == null) {
			return;
		}
		if ((oldPlayer != null && ServerBackroomsSystem.isInBackrooms(oldPlayer))
				|| ServerBackroomsSystem.isInBackrooms(newPlayer)) {
			return;
		}

		GlitchConfig.ConfigData config = GlitchConfig.get();
		if (!config.enabled) {
			return;
		}

		ServerGlitchHandler baseHandler = HANDLERS.get(CHECKPOINT_DESYNC_ID);
		if (!(baseHandler instanceof RespawnGlitchHandler respawnHandler)) {
			return;
		}

		if (config.glitches == null || config.glitches.isEmpty()) {
			return;
		}

		GlitchConfig.GlitchEntry entry = config.glitches.get(CHECKPOINT_DESYNC_ID);
		if (entry == null || !entry.enabled) {
			return;
		}

		MinecraftServer server = oldPlayer == null ? newPlayer.level().getServer() : oldPlayer.level().getServer();
		if (server == null) {
			return;
		}

		double stabilityPercent = ServerStabilitySystem.getStabilityPercent();
		if (stabilityPercent < entry.minStabilityPercent || stabilityPercent > entry.maxStabilityPercent) {
			return;
		}

		long gameTime = server.overworld().getGameTime();
		long nextAllowedTick = NEXT_ALLOWED_TICKS.getOrDefault(CHECKPOINT_DESYNC_ID, Long.MIN_VALUE);
		if (gameTime < nextAllowedTick) {
			return;
		}

		RandomSource random = server.overworld().getRandom();
		double baseChance = clamp01(entry.chancePerCheck);
		double influence = clamp01(entry.stabilityInfluence);
		double rangeInstabilityFactor = getRangeInstabilityFactor(
				stabilityPercent,
				entry.minStabilityPercent,
				entry.maxStabilityPercent
		);
		double effectiveChance = baseChance * ((1.0D - influence) + (rangeInstabilityFactor * influence));
		if (effectiveChance <= 0.0D) {
			return;
		}
		if (random.nextDouble() > effectiveChance) {
			return;
		}

		boolean triggered = respawnHandler.triggerOnCopyFrom(
				server,
				random,
				entry,
				stabilityPercent,
				oldPlayer,
				newPlayer,
				alive
		);
		if (!triggered) {
			return;
		}

		long cooldownTicks = Math.max(
				0L,
				getCooldownTicksForStability(entry, stabilityPercent)
		);
		NEXT_ALLOWED_TICKS.put(CHECKPOINT_DESYNC_ID, gameTime + cooldownTicks);
	}

	private static void onAfterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
		ServerGlitchHandler baseHandler = HANDLERS.get(CHECKPOINT_DESYNC_ID);
		if (!(baseHandler instanceof RespawnGlitchHandler respawnHandler)) {
			return;
		}

		MinecraftServer server = oldPlayer == null
				? (newPlayer == null ? null : newPlayer.level().getServer())
				: oldPlayer.level().getServer();
		if (server == null) {
			return;
		}

		respawnHandler.onAfterRespawn(server, oldPlayer, newPlayer, alive);
	}

	private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hitResult) {
		if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || !(world instanceof ServerLevel serverLevel)) {
			return InteractionResult.PASS;
		}
		if (ServerBackroomsSystem.isInBackrooms(serverPlayer) || ServerBackroomsSystem.isBackrooms(serverLevel)) {
			return InteractionResult.PASS;
		}

		BlockPos pos = hitResult.getBlockPos();
		BlockState state = serverLevel.getBlockState(pos);
		ChestDesyncGlitch.noteBlockInteraction(serverPlayer, serverLevel, pos, state, hand, hitResult);
		if (hand == InteractionHand.MAIN_HAND) {
			BitcoinOvercookGlitch.notePotentialOwner(serverLevel, pos, serverPlayer);
		}

		GlitchConfig.ConfigData config = GlitchConfig.get();
		if (!config.enabled || config.glitches == null || config.glitches.isEmpty()) {
			return InteractionResult.PASS;
		}

		MinecraftServer server = serverLevel.getServer();
		if (server == null || server.getPlayerList().getPlayerCount() <= 0) {
			return InteractionResult.PASS;
		}

		long gameTime = server.overworld().getGameTime();
		double stabilityPercent = ServerStabilitySystem.getStabilityPercent();
		RandomSource random = server.overworld().getRandom();

		List<Map.Entry<String, GlitchConfig.GlitchEntry>> entries = new ArrayList<>(config.glitches.entrySet());
		shuffle(entries, random);

		for (Map.Entry<String, GlitchConfig.GlitchEntry> mapEntry : entries) {
			String id = mapEntry.getKey();
			GlitchConfig.GlitchEntry entry = mapEntry.getValue();
			ServerGlitchHandler handler = HANDLERS.get(id);

			if (handler == null) {
				logUnknownGlitchId(id);
				continue;
			}
			if (!(handler instanceof BlockUseGlitchHandler blockUseHandler)) {
				continue;
			}
			if (entry == null || !entry.enabled) {
				continue;
			}
			if (stabilityPercent < entry.minStabilityPercent || stabilityPercent > entry.maxStabilityPercent) {
				continue;
			}

			long nextAllowedTick = NEXT_ALLOWED_TICKS.getOrDefault(id, Long.MIN_VALUE);
			if (gameTime < nextAllowedTick) {
				continue;
			}

			double baseChance = clamp01(entry.chancePerCheck);
			double influence = clamp01(entry.stabilityInfluence);
			double rangeInstabilityFactor = getRangeInstabilityFactor(
					stabilityPercent,
					entry.minStabilityPercent,
					entry.maxStabilityPercent
			);
			double effectiveChance = baseChance * ((1.0D - influence) + (rangeInstabilityFactor * influence));
			if (effectiveChance <= 0.0D) {
				continue;
			}
			if (random.nextDouble() > effectiveChance) {
				continue;
			}

			boolean triggered = blockUseHandler.triggerOnUseBlock(
					server,
					random,
					entry,
					stabilityPercent,
					serverPlayer,
					serverLevel,
					pos,
					state,
					hand,
					hitResult
			);
			if (!triggered) {
				continue;
			}

			long cooldownTicks = Math.max(0L, getCooldownTicksForStability(entry, stabilityPercent));
			NEXT_ALLOWED_TICKS.put(id, gameTime + cooldownTicks);
			return InteractionResult.SUCCESS;
		}

		return InteractionResult.PASS;
	}

	private static InteractionResult onUseEntity(
			Player player,
			Level world,
			InteractionHand hand,
			Entity entity,
			EntityHitResult hitResult
	) {
		if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (ServerBackroomsSystem.isInBackrooms(serverPlayer) || ServerBackroomsSystem.isInBackrooms(entity)) {
			return InteractionResult.PASS;
		}
		ChestDesyncGlitch.noteEntityInteraction(serverPlayer, entity, hand);

		GlitchConfig.ConfigData config = GlitchConfig.get();
		if (!config.enabled || config.glitches == null || config.glitches.isEmpty()) {
			return InteractionResult.PASS;
		}

		MinecraftServer server = serverPlayer.level().getServer();
		if (server == null || server.getPlayerList().getPlayerCount() <= 0) {
			return InteractionResult.PASS;
		}

		long gameTime = server.overworld().getGameTime();
		double stabilityPercent = ServerStabilitySystem.getStabilityPercent();
		RandomSource random = server.overworld().getRandom();

		List<Map.Entry<String, GlitchConfig.GlitchEntry>> entries = new ArrayList<>(config.glitches.entrySet());
		shuffle(entries, random);

		for (Map.Entry<String, GlitchConfig.GlitchEntry> mapEntry : entries) {
			String id = mapEntry.getKey();
			GlitchConfig.GlitchEntry entry = mapEntry.getValue();
			ServerGlitchHandler handler = HANDLERS.get(id);

			if (handler == null) {
				logUnknownGlitchId(id);
				continue;
			}
			if (!(handler instanceof EntityUseGlitchHandler entityUseHandler)) {
				continue;
			}
			if (entry == null || !entry.enabled) {
				continue;
			}
			if (stabilityPercent < entry.minStabilityPercent || stabilityPercent > entry.maxStabilityPercent) {
				continue;
			}

			long nextAllowedTick = NEXT_ALLOWED_TICKS.getOrDefault(id, Long.MIN_VALUE);
			if (gameTime < nextAllowedTick) {
				continue;
			}

			double baseChance = clamp01(entry.chancePerCheck);
			double influence = clamp01(entry.stabilityInfluence);
			double rangeInstabilityFactor = getRangeInstabilityFactor(
					stabilityPercent,
					entry.minStabilityPercent,
					entry.maxStabilityPercent
			);
			double effectiveChance = baseChance * ((1.0D - influence) + (rangeInstabilityFactor * influence));
			if (effectiveChance <= 0.0D) {
				continue;
			}
			if (random.nextDouble() > effectiveChance) {
				continue;
			}

			boolean triggered = entityUseHandler.triggerOnUseEntity(
					server,
					random,
					entry,
					stabilityPercent,
					serverPlayer,
					entity,
					hand,
					hitResult
			);
			if (!triggered) {
				continue;
			}

			long cooldownTicks = Math.max(0L, getCooldownTicksForStability(entry, stabilityPercent));
			NEXT_ALLOWED_TICKS.put(id, gameTime + cooldownTicks);
			return InteractionResult.SUCCESS;
		}

		return InteractionResult.PASS;
	}

	private static InteractionResult onAttackEntity(
			Player player,
			Level world,
			InteractionHand hand,
			Entity entity,
			EntityHitResult hitResult
	) {
		if (world.isClientSide() || !(player instanceof ServerPlayer)) {
			return InteractionResult.PASS;
		}

		return PhantomMobGlitch.handleAttack(entity)
				? InteractionResult.SUCCESS
				: InteractionResult.PASS;
	}

	private static void triggerGlitches(MinecraftServer server, GlitchConfig.ConfigData config) {
		if (config.glitches == null || config.glitches.isEmpty()) {
			return;
		}

		double stabilityPercent = ServerStabilitySystem.getStabilityPercent();
		RandomSource random = server.overworld().getRandom();
		long gameTime = server.overworld().getGameTime();
		int remainingActivations = Math.max(1, config.maxActivationsPerCheck);

		List<Map.Entry<String, GlitchConfig.GlitchEntry>> entries = new ArrayList<>(config.glitches.entrySet());
		shuffle(entries, random);

		for (Map.Entry<String, GlitchConfig.GlitchEntry> mapEntry : entries) {
			if (remainingActivations <= 0) {
				break;
			}

			String id = mapEntry.getKey();
			GlitchConfig.GlitchEntry entry = mapEntry.getValue();
			ServerGlitchHandler handler = HANDLERS.get(id);

			if (handler == null) {
				logUnknownGlitchId(id);
				continue;
			}

			if (entry == null || !entry.enabled) {
				continue;
			}

			if (stabilityPercent < entry.minStabilityPercent || stabilityPercent > entry.maxStabilityPercent) {
				continue;
			}

			long nextAllowedTick = NEXT_ALLOWED_TICKS.getOrDefault(id, Long.MIN_VALUE);
			if (gameTime < nextAllowedTick) {
				continue;
			}

			double baseChance = clamp01(entry.chancePerCheck);
			double influence = clamp01(entry.stabilityInfluence);
			double rangeInstabilityFactor = getRangeInstabilityFactor(
					stabilityPercent,
					entry.minStabilityPercent,
					entry.maxStabilityPercent
			);
			double effectiveChance = baseChance * ((1.0D - influence) + (rangeInstabilityFactor * influence));
			if (effectiveChance <= 0.0D) {
				continue;
			}

			if (random.nextDouble() > effectiveChance) {
				continue;
			}

			boolean triggered = handler.trigger(server, random, entry, stabilityPercent);
			if (!triggered) {
				continue;
			}

			long cooldownTicks = Math.max(
					0L,
					getCooldownTicksForStability(entry, stabilityPercent)
			);
			NEXT_ALLOWED_TICKS.put(id, gameTime + cooldownTicks);
			remainingActivations--;
		}
	}

	private static void shuffle(List<Map.Entry<String, GlitchConfig.GlitchEntry>> list, RandomSource random) {
		for (int i = list.size() - 1; i > 0; i--) {
			int swapWith = random.nextInt(i + 1);
			Collections.swap(list, i, swapWith);
		}
	}

	private static void logUnknownGlitchId(String id) {
		if (!UNKNOWN_CONFIG_IDS_LOGGED.add(id)) {
			return;
		}

		Lg2.LOGGER.warn("Unknown glitch id '{}' in lg2-glitches config. Entry will be ignored.", id);
	}

	private static GlitchConfig.GlitchEntry copyEntry(GlitchConfig.GlitchEntry source) {
		GlitchConfig.GlitchEntry copy = new GlitchConfig.GlitchEntry();
		copy.enabled = source.enabled;
		copy.minStabilityPercent = source.minStabilityPercent;
		copy.maxStabilityPercent = source.maxStabilityPercent;
		copy.chancePerCheck = source.chancePerCheck;
		copy.stabilityInfluence = source.stabilityInfluence;
		copy.minCooldownTicks = source.minCooldownTicks;
		copy.maxCooldownTicks = source.maxCooldownTicks;
		copy.cooldownTicks = source.cooldownTicks;
		copy.settings = source.settings == null ? new JsonObject() : source.settings.deepCopy();
		return copy;
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	private static double getRangeInstabilityFactor(double stabilityPercent, double minStabilityPercent, double maxStabilityPercent) {
		double range = maxStabilityPercent - minStabilityPercent;
		if (range <= 1.0E-9D) {
			return 1.0D;
		}

		double normalized = (stabilityPercent - minStabilityPercent) / range;
		return clamp01(1.0D - normalized);
	}

	private static int getCooldownTicksForStability(GlitchConfig.GlitchEntry entry, double stabilityPercent) {
		int minCooldown = entry.minCooldownTicks == null ? 0 : Math.max(0, entry.minCooldownTicks);
		int maxCooldown = entry.maxCooldownTicks == null ? minCooldown : Math.max(0, entry.maxCooldownTicks);
		if (maxCooldown < minCooldown) {
			maxCooldown = minCooldown;
		}

		double startStability = Math.max(0.0D, entry.maxStabilityPercent);
		if (startStability <= 1.0E-9D) {
			return minCooldown;
		}

		double factor = clamp01(stabilityPercent / startStability);
		double cooldown = minCooldown + ((double) (maxCooldown - minCooldown) * factor);
		return (int) Math.round(cooldown);
	}
}


