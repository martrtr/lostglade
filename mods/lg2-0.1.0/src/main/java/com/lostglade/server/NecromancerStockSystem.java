package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.config.RaceConfig.PlayerRaceConfig;
import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import com.lostglade.config.RaceConfig.RaceAbilitySlot;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class NecromancerStockSystem {
	private static final String RACE_ID = "necromancer";
	private static final String STATE_FILE_NAME = "lg2-necromancer-mana.json";
	private static final double DEFAULT_MANA_BASE_MAX = 100.0D;
	private static final double DEFAULT_MANA_PER_LEVEL = 5.0D;
	private static final double DEFAULT_MANA_REGEN_PER_SECOND = 1.0D;
	private static final double DEFAULT_ACTION_COST_PER_SECOND = 1.0D;
	private static final double DEFAULT_REACH_BLOCKS = 10.0D;
	private static final double DEFAULT_MAX_HEALTH_HEARTS = 5.0D;
	private static final String ERA_STONE = "era_stone";
	private static final String ERA_COPPER = "era_copper";
	private static final String ERA_IRON_GOLD = "era_iron_gold";
	private static final String ERA_DIAMOND = "era_diamond";
	private static final String ERA_NETHERITE = "era_netherite";
	private static final Identifier MAX_HEALTH_MODIFIER_ID =
			Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "necromancer_stock_max_health");
	private static final Identifier BLOCK_REACH_MODIFIER_ID =
			Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "necromancer_stock_block_reach");
	private static final Identifier ENTITY_REACH_MODIFIER_ID =
			Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "necromancer_stock_entity_reach");
	private static final String MANA_BAR_FRAME = "\uea00";
	private static final FontDescription MANA_BAR_FONT = new FontDescription.Resource(
			Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "necromancer_mana_bar")
	);
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, ManaState> MANA_STATES = new LinkedHashMap<>();
	private static final Map<UUID, HeldAction> HELD_ACTIONS = new HashMap<>();
	private static final Map<UUID, Long> ACTION_CHARGE_DEADLINES = new HashMap<>();
	private static final Map<UUID, GameType> ACTION_LOCK_PREVIOUS_GAME_TYPES = new HashMap<>();
	private static final Map<UUID, ServerBossEvent> MANA_BARS = new HashMap<>();
	private static final Map<UUID, Boolean> ORIGINAL_GLOWING = new HashMap<>();
	private static boolean dirty;

	private NecromancerStockSystem() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("manabar")
						.requires(source -> source.getPlayer() != null && isActive(source.getPlayer()))
						.executes(context -> toggleManaBar(context.getSource().getPlayer()))
		));
		ServerLifecycleEvents.SERVER_STARTED.register(NecromancerStockSystem::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(NecromancerStockSystem::shutdown);
		ServerTickEvents.END_SERVER_TICK.register(NecromancerStockSystem::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				server.execute(() -> syncPlayerRecipeBook(handler.player)));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clearRuntimeState(handler.player));
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof ServerPlayer player) || !isActive(player)) return;
			ManaState state = stateFor(player, maxMana(player, config(player)));
			state.mana = 0.0D;
			markDirty();
			clearRuntimeState(player);
			updateManaBar(player, state, maxMana(player, config(player)));
		});
		UseBlockCallback.EVENT.register(NecromancerStockSystem::useVirtualEraToolOnBlock);
		UseEntityCallback.EVENT.register(NecromancerStockSystem::useVirtualShearsOnEntity);
	}

	public static boolean isActive(ServerPlayer player) {
		if (player == null) return false;
		Optional<PlayerRaceConfig> race = ServerRaceSystem.getRace(player);
		if (race.isEmpty() || race.get().id == null || !RACE_ID.equalsIgnoreCase(race.get().id.trim())) return false;
		RaceAbilityConfig stock = ServerRaceSystem.getAbility(race.get(), RaceAbilitySlot.STOCK);
		return stock != null && stock.enabled && ServerRaceSystem.hasUnlockedAbility(player, RaceAbilitySlot.STOCK);
	}

	public static boolean tryStartBlockBreaking(ServerPlayer player, BlockPos pos, Direction direction) {
		if (!isActive(player)) return true;
		if (blockForbiddenHeldItem(player, InteractionHand.MAIN_HAND)) return false;
		if (!trySpendActionMana(player)) return false;
		HELD_ACTIONS.put(player.getUUID(), HeldAction.breaking(
				pos.immutable(), direction, nextActionChargeTick(player)));
		return true;
	}

	public static void stopBlockBreaking(ServerPlayer player) {
		if (player == null) return;
		HeldAction action = HELD_ACTIONS.get(player.getUUID());
		if (action != null && action.kind == HeldActionKind.BREAKING) HELD_ACTIONS.remove(player.getUUID());
	}

	public static boolean tryInstantHandAction(ServerPlayer player) {
		return tryInstantHandAction(player, InteractionHand.MAIN_HAND);
	}

	public static boolean tryInstantHandAction(ServerPlayer player, InteractionHand hand) {
		return !isActive(player)
				|| (!blockForbiddenHeldItem(player, hand) && trySpendActionMana(player));
	}

	public static boolean tryAttackAction(ServerPlayer player) {
		if (!isActive(player)) return true;
		if (blockForbiddenHeldItem(player, InteractionHand.MAIN_HAND)) return false;
		RaceAbilityConfig config = config(player);
		double cost = nonNegativeOrDefault(
				config.necromancerManaActionCostPerSecond,
				DEFAULT_ACTION_COST_PER_SECOND
		);
		return trySpendMana(player, cost);
	}

	public static boolean canAttemptHandAction(ServerPlayer player, InteractionHand hand) {
		if (!isActive(player)) return true;
		if (blockForbiddenHeldItem(player, hand)) return false;
		return hasPaidActionInterval(player) || hasActionMana(player, true);
	}

	public static void recordSuccessfulHandAction(ServerPlayer player, InteractionHand hand) {
		if (!isActive(player) || !trySpendActionMana(player)) return;
		if (player.isUsingItem() && player.getUsedItemHand() == hand) {
			HELD_ACTIONS.put(player.getUUID(), HeldAction.using(nextActionChargeTick(player)));
		}
	}

	public static boolean canOwnItem(ServerPlayer player, ItemStack stack) {
		return !isActive(player) || !isForbiddenToolOrWeapon(stack);
	}

	public static boolean canTakeCraftResult(ServerPlayer player, ItemStack stack) {
		return !isActive(player) || !isForbiddenToolOrWeapon(stack);
	}

	public static boolean shouldBlockEquipping(ServerPlayer player, ItemStack stack, EquipmentSlot slot) {
		return isActive(player) && isArmorSlot(slot) && isArmor(stack);
	}

	public static boolean shouldDisableTotem(LivingEntity entity) {
		return entity instanceof ServerPlayer player && isActive(player);
	}

	public static ItemStack virtualMiningSpeedTool(ServerPlayer player, BlockState state) {
		if (!usesEraBasedMining(player) || state == null || state.isAir()) return ItemStack.EMPTY;
		ItemStack best = fasterMiningTool(ItemStack.EMPTY, EraToolSet.WOOD, state);
		if (ServerUpgradeUiSystem.hasUpgrade(player, ERA_STONE)) {
			best = fasterMiningTool(best, EraToolSet.STONE, state);
		}
		if (ServerUpgradeUiSystem.hasUpgrade(player, ERA_COPPER)) {
			best = fasterMiningTool(best, EraToolSet.COPPER, state);
		}
		if (ServerUpgradeUiSystem.hasUpgrade(player, ERA_IRON_GOLD)) {
			best = fasterMiningTool(best, EraToolSet.IRON, state);
			best = fasterMiningTool(best, EraToolSet.GOLD, state);
		}
		if (ServerUpgradeUiSystem.hasUpgrade(player, ERA_DIAMOND)) {
			best = fasterMiningTool(best, EraToolSet.DIAMOND, state);
		}
		if (ServerUpgradeUiSystem.hasUpgrade(player, ERA_NETHERITE)) {
			best = fasterMiningTool(best, EraToolSet.NETHERITE, state);
		}
		return best;
	}

	public static ItemStack virtualMiningHarvestTool(ServerPlayer player, BlockState state) {
		if (!usesEraBasedMining(player) || state == null || state.isAir()) return ItemStack.EMPTY;
		return fasterMiningTool(ItemStack.EMPTY, purchasedEra(player), state);
	}

	private static boolean usesEraBasedMining(ServerPlayer player) {
		return isActive(player) || ServerRaceSystem.isCopperManStockEnabled(player);
	}

	private static ItemStack fasterMiningTool(ItemStack currentBest, EraToolSet era, BlockState state) {
		ItemStack best = currentBest;
		float bestSpeed = best.isEmpty() ? 1.0F : best.getDestroySpeed(state);
		boolean bestCorrect = !best.isEmpty() && best.isCorrectToolForDrops(state);
		for (Item item : era.miningTools()) {
			ItemStack candidate = new ItemStack(item);
			float speed = candidate.getDestroySpeed(state);
			boolean correct = candidate.isCorrectToolForDrops(state);
			if (speed > bestSpeed + 1.0E-4F
					|| (Math.abs(speed - bestSpeed) <= 1.0E-4F && correct && !bestCorrect)) {
				best = candidate;
				bestSpeed = speed;
				bestCorrect = correct;
			}
		}
		return best;
	}

	private static InteractionResult useVirtualEraToolOnBlock(
			Player player,
			Level world,
			InteractionHand hand,
			BlockHitResult hitResult
	) {
		if (world.isClientSide()
				|| hand != InteractionHand.MAIN_HAND
				|| !(player instanceof ServerPlayer serverPlayer)
				|| !isActive(serverPlayer)
				|| !serverPlayer.getItemInHand(hand).isEmpty()
				|| serverPlayer.isSpectator()
				|| hitResult == null) {
			return InteractionResult.PASS;
		}

		EraToolSet era = purchasedEra(serverPlayer);
		ItemStack groundTool = new ItemStack(serverPlayer.isSecondaryUseActive() ? era.hoe() : era.shovel());
		if (!serverPlayer.mayUseItemAt(hitResult.getBlockPos(), hitResult.getDirection(), groundTool)) {
			return InteractionResult.PASS;
		}

		InteractionResult groundResult = groundTool.useOn(
				new VirtualToolUseOnContext(world, serverPlayer, hand, groundTool, hitResult));
		if (groundResult.consumesAction()) return groundResult;
		if (!era.hasShears()) return InteractionResult.PASS;

		ItemStack shears = new ItemStack(Items.SHEARS);
		BlockState state = world.getBlockState(hitResult.getBlockPos());
		InteractionResult blockResult = state.useItemOn(shears, world, serverPlayer, hand, hitResult);
		if (blockResult.consumesAction()) return blockResult;
		InteractionResult itemResult = shears.useOn(
				new VirtualToolUseOnContext(world, serverPlayer, hand, shears, hitResult));
		return itemResult.consumesAction() ? itemResult : InteractionResult.PASS;
	}

	private static InteractionResult useVirtualShearsOnEntity(
			Player player,
			Level world,
			InteractionHand hand,
			Entity entity,
			EntityHitResult hitResult
	) {
		if (world.isClientSide()
				|| hand != InteractionHand.MAIN_HAND
				|| !(world instanceof ServerLevel level)
				|| !(player instanceof ServerPlayer serverPlayer)
				|| !isActive(serverPlayer)
				|| !serverPlayer.getItemInHand(hand).isEmpty()
				|| serverPlayer.isSpectator()
				|| !purchasedEra(serverPlayer).hasShears()
				|| !(entity instanceof Shearable shearable)
				|| !shearable.readyForShearing()) {
			return InteractionResult.PASS;
		}

		ItemStack shears = new ItemStack(Items.SHEARS);
		shearable.shear(level, SoundSource.PLAYERS, shears);
		entity.gameEvent(GameEvent.SHEAR, serverPlayer);
		serverPlayer.swing(hand, true);
		return InteractionResult.SUCCESS_SERVER;
	}

	private static EraToolSet purchasedEra(ServerPlayer player) {
		if (ServerUpgradeUiSystem.hasUpgrade(player, ERA_NETHERITE)) return EraToolSet.NETHERITE;
		if (ServerUpgradeUiSystem.hasUpgrade(player, ERA_DIAMOND)) return EraToolSet.DIAMOND;
		if (ServerUpgradeUiSystem.hasUpgrade(player, ERA_IRON_GOLD)) return EraToolSet.IRON;
		if (ServerUpgradeUiSystem.hasUpgrade(player, ERA_COPPER)) return EraToolSet.COPPER;
		if (ServerUpgradeUiSystem.hasUpgrade(player, ERA_STONE)) return EraToolSet.STONE;
		return EraToolSet.WOOD;
	}

	public static float normalizeWeaponDamage(LivingEntity victim, DamageSource source, float damage) {
		if (source == null || !(source.getEntity() instanceof ServerPlayer attacker) || !isActive(attacker)) return damage;
		if (source.getDirectEntity() instanceof NecromancerWindCharge) return damage;
		boolean directAttack = isForbiddenToolOrWeapon(attacker.getMainHandItem())
				|| isForbiddenToolOrWeapon(attacker.getOffhandItem());
		boolean projectileAttack = source.getDirectEntity() instanceof Projectile;
		if (!directAttack && !projectileAttack) return damage;
		return 1.0F;
	}

	public static boolean trySpendMana(ServerPlayer player, double cost) {
		if (!isActive(player)) return false;
		double safeCost = Double.isFinite(cost) ? Math.max(0.0D, cost) : 0.0D;
		RaceAbilityConfig config = config(player);
		double maxMana = maxMana(player, config);
		ManaState state = stateFor(player, maxMana);
		if (state.mana + 1.0E-9D < safeCost) {
			player.displayClientMessage(Component.literal("Недостаточно маны").withStyle(ChatFormatting.DARK_AQUA), true);
			return false;
		}
		state.mana = Math.max(0.0D, state.mana - safeCost);
		markDirty();
		updateManaBar(player, state, maxMana);
		return true;
	}

	public static double currentMana(ServerPlayer player) {
		if (!isActive(player)) return 0.0D;
		RaceAbilityConfig config = config(player);
		double maxMana = maxMana(player, config);
		return stateFor(player, maxMana).mana;
	}

	public static void restoreAllMana(MinecraftServer server) {
		if (server == null) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!isActive(player)) continue;
			RaceAbilityConfig config = config(player);
			double maxMana = maxMana(player, config);
			ManaState state = stateFor(player, maxMana);
			state.mana = maxMana;
			markDirty();
			updateManaBar(player, state, maxMana);
		}
	}

	public static Collection<RecipeHolder<?>> filterAwardedRecipes(
			ServerPlayer player,
			Collection<RecipeHolder<?>> recipes
	) {
		if (!isActive(player) || recipes == null || recipes.isEmpty()) return recipes;
		List<RecipeHolder<?>> filtered = new ArrayList<>(recipes.size());
		for (RecipeHolder<?> holder : recipes) {
			if (holder == null || !isForbiddenRecipe(player, holder)) filtered.add(holder);
		}
		return filtered;
	}

	public static List<ResourceKey<Recipe<?>>> filterAwardedRecipeKeys(
			ServerPlayer player,
			List<ResourceKey<Recipe<?>>> recipeKeys
	) {
		if (!isActive(player) || recipeKeys == null || recipeKeys.isEmpty()) return recipeKeys;
		List<ResourceKey<Recipe<?>>> filtered = new ArrayList<>(recipeKeys.size());
		for (ResourceKey<Recipe<?>> key : recipeKeys) {
			RecipeHolder<?> holder = key == null ? null : player.level().getServer().getRecipeManager().byKey(key).orElse(null);
			if (holder == null || !isForbiddenRecipe(player, holder)) filtered.add(key);
		}
		return filtered;
	}

	public static void syncPlayerRecipeBook(ServerPlayer player) {
		if (player == null || !isActive(player)) return;
		List<RecipeHolder<?>> forbidden = new ArrayList<>();
		for (RecipeHolder<?> holder : player.level().getServer().getRecipeManager().getRecipes()) {
			if (holder != null && isForbiddenRecipe(player, holder)) forbidden.add(holder);
		}
		if (!forbidden.isEmpty()) player.resetRecipes(forbidden);
		ServerRecipeBook recipeBook = player.getRecipeBook();
		recipeBook.sendInitialRecipeBook(player);
	}

	private static void tick(MinecraftServer server) {
		if (server == null) return;
		long nowTick = server.getTickCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!isActive(player)) {
				clearPlayer(player);
				continue;
			}
			RaceAbilityConfig config = config(player);
			double maxMana = maxMana(player, config);
			ManaState state = stateFor(player, maxMana);
			if (state.mana > maxMana) {
				state.mana = maxMana;
				markDirty();
			}
			if (player.isAlive() && nowTick % 20L == 0L) {
				double regen = nonNegativeOrDefault(config.necromancerManaRegenPerSecond, DEFAULT_MANA_REGEN_PER_SECOND);
				double restored = Math.min(maxMana, state.mana + regen);
				if (Math.abs(restored - state.mana) > 1.0E-9D) {
					state.mana = restored;
					markDirty();
				}
			}
			syncAttributes(player, config);
			syncGlowing(player);
			syncArmor(player);
			syncActionGameMode(player, config, state);
			tickHeldAction(player);
			updateManaBar(player, state, maxMana);
		}
		if (dirty && nowTick % 200L == 0L) save(server);
	}

	private static void tickHeldAction(ServerPlayer player) {
		HeldAction action = HELD_ACTIONS.get(player.getUUID());
		if (action == null) return;
		long now = player.level().getGameTime();
		if (action.kind == HeldActionKind.USING && !player.isUsingItem()) {
			HELD_ACTIONS.remove(player.getUUID());
			return;
		}
		if (action.kind == HeldActionKind.BREAKING
				&& (action.blockPos == null || player.level().getBlockState(action.blockPos).isAir())) {
			HELD_ACTIONS.remove(player.getUUID());
			return;
		}
		if (now < action.nextChargeTick) return;
		if (!trySpendActionMana(player)) {
			cancelHeldAction(player, action);
			return;
		}
		action.nextChargeTick = nextActionChargeTick(player);
	}

	private static void cancelHeldAction(ServerPlayer player, HeldAction action) {
		HELD_ACTIONS.remove(player.getUUID());
		if (action.kind == HeldActionKind.USING) {
			player.stopUsingItem();
			player.containerMenu.broadcastFullState();
			return;
		}
		if (action.blockPos != null) {
			player.level().destroyBlockProgress(player.getId(), action.blockPos, -1);
			player.connection.send(new ClientboundBlockUpdatePacket(player.level(), action.blockPos));
		}
	}

	private static boolean trySpendActionMana(ServerPlayer player) {
		if (hasPaidActionInterval(player)) return true;
		RaceAbilityConfig config = config(player);
		double cost = nonNegativeOrDefault(config.necromancerManaActionCostPerSecond, DEFAULT_ACTION_COST_PER_SECOND);
		if (!trySpendMana(player, cost)) return false;
		ACTION_CHARGE_DEADLINES.put(player.getUUID(), player.level().getGameTime() + 20L);
		return true;
	}

	private static boolean hasPaidActionInterval(ServerPlayer player) {
		Long deadline = ACTION_CHARGE_DEADLINES.get(player.getUUID());
		return deadline != null && player.level().getGameTime() < deadline;
	}

	private static long nextActionChargeTick(ServerPlayer player) {
		return ACTION_CHARGE_DEADLINES.getOrDefault(player.getUUID(), player.level().getGameTime());
	}

	private static boolean hasActionMana(ServerPlayer player, boolean notify) {
		RaceAbilityConfig config = config(player);
		double cost = nonNegativeOrDefault(config.necromancerManaActionCostPerSecond, DEFAULT_ACTION_COST_PER_SECOND);
		double maxMana = maxMana(player, config);
		if (stateFor(player, maxMana).mana + 1.0E-9D >= cost) return true;
		if (notify) {
			player.displayClientMessage(Component.literal("Недостаточно маны").withStyle(ChatFormatting.DARK_AQUA), true);
		}
		return false;
	}

	private static void syncActionGameMode(ServerPlayer player, RaceAbilityConfig config, ManaState state) {
		double cost = nonNegativeOrDefault(
				config.necromancerManaActionCostPerSecond,
				DEFAULT_ACTION_COST_PER_SECOND
		);
		boolean shouldLock = cost > 0.0D
				&& !hasPaidActionInterval(player)
				&& state.mana + 1.0E-9D < cost;
		if (!shouldLock) {
			restoreActionGameMode(player);
			return;
		}

		UUID playerId = player.getUUID();
		GameType current = player.gameMode.getGameModeForPlayer();
		GameType previous = ACTION_LOCK_PREVIOUS_GAME_TYPES.get(playerId);
		if (previous == null) {
			if (current != GameType.SURVIVAL) return;
			ACTION_LOCK_PREVIOUS_GAME_TYPES.put(playerId, current);
		}
		if (current != GameType.ADVENTURE) player.setGameMode(GameType.ADVENTURE);
	}

	private static void restoreActionGameMode(ServerPlayer player) {
		GameType previous = ACTION_LOCK_PREVIOUS_GAME_TYPES.remove(player.getUUID());
		if (previous != null && player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) {
			player.setGameMode(previous);
		}
	}

	private static boolean blockForbiddenHeldItem(ServerPlayer player, InteractionHand hand) {
		if (!isForbiddenToolOrWeapon(player.getItemInHand(hand))) return false;
		player.displayClientMessage(Component.literal("Некромансер не может пользоваться оружием и инструментами")
				.withStyle(ChatFormatting.RED), true);
		player.containerMenu.broadcastFullState();
		return true;
	}

	private static void syncAttributes(ServerPlayer player, RaceAbilityConfig config) {
		double healthHearts = positiveOrDefault(config.necromancerMaxHealthHearts, DEFAULT_MAX_HEALTH_HEARTS);
		syncExactAttribute(player.getAttribute(Attributes.MAX_HEALTH), MAX_HEALTH_MODIFIER_ID, healthHearts * 2.0D);
		if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
		double reach = positiveOrDefault(config.necromancerReachBlocks, DEFAULT_REACH_BLOCKS);
		syncExactAttribute(player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE), BLOCK_REACH_MODIFIER_ID, reach);
		syncExactAttribute(player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE), ENTITY_REACH_MODIFIER_ID, reach);
	}

	private static void syncExactAttribute(AttributeInstance attribute, Identifier modifierId, double targetValue) {
		if (attribute == null) return;
		AttributeModifier current = attribute.getModifier(modifierId);
		if (current != null && Math.abs(attribute.getValue() - targetValue) <= 1.0E-9D) return;
		if (current != null) attribute.removeModifier(modifierId);
		double amount = targetValue - attribute.getValue();
		if (Math.abs(amount) > 1.0E-9D) {
			attribute.addTransientModifier(new AttributeModifier(modifierId, amount, AttributeModifier.Operation.ADD_VALUE));
		}
	}

	private static void syncGlowing(ServerPlayer player) {
		ORIGINAL_GLOWING.putIfAbsent(player.getUUID(), player.hasGlowingTag());
		if (!player.hasGlowingTag()) player.setGlowingTag(true);
	}

	private static void syncArmor(ServerPlayer player) {
		for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
			ItemStack equipped = player.getItemBySlot(slot);
			if (!isArmor(equipped)) continue;
			ItemStack removed = equipped.copy();
			player.setItemSlot(slot, ItemStack.EMPTY);
			if (!player.getInventory().add(removed)) {
				ItemEntity dropped = player.drop(removed, true);
				if (dropped != null) dropped.setPickUpDelay(20);
			}
		}
	}

	private static boolean isForbiddenToolOrWeapon(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		return stack.has(DataComponents.TOOL)
				|| stack.has(DataComponents.WEAPON)
				|| stack.has(DataComponents.PIERCING_WEAPON)
				|| stack.has(DataComponents.KINETIC_WEAPON)
				|| stack.has(DataComponents.BLOCKS_ATTACKS)
				|| stack.getItem() instanceof ProjectileWeaponItem
				|| stack.getItem() instanceof ShieldItem
				|| stack.is(ItemTags.SWORDS)
				|| stack.is(ItemTags.AXES)
				|| stack.is(ItemTags.HOES)
				|| stack.is(ItemTags.PICKAXES)
				|| stack.is(ItemTags.SHOVELS)
				|| stack.is(ItemTags.SPEARS)
				|| stack.is(Items.SHEARS)
				|| stack.is(Items.FISHING_ROD)
				|| stack.is(Items.FLINT_AND_STEEL)
				|| stack.is(Items.BRUSH);
	}

	private static boolean isArmor(ItemStack stack) {
		return stack != null && !stack.isEmpty() && (stack.is(ItemTags.HEAD_ARMOR)
				|| stack.is(ItemTags.CHEST_ARMOR)
				|| stack.is(ItemTags.LEG_ARMOR)
				|| stack.is(ItemTags.FOOT_ARMOR));
	}

	private static boolean isArmorSlot(EquipmentSlot slot) {
		return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
				|| slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
	}

	private static boolean isForbiddenRecipe(ServerPlayer player, RecipeHolder<?> holder) {
		ContextMap context = SlotDisplayContext.fromLevel(player.level());
		for (RecipeDisplay display : holder.value().display()) {
			for (ItemStack result : display.result().resolveForStacks(context)) {
				if (isForbiddenToolOrWeapon(result)) return true;
			}
		}
		return false;
	}

	private static int toggleManaBar(ServerPlayer player) {
		if (!isActive(player)) return 0;
		RaceAbilityConfig config = config(player);
		ManaState state = stateFor(player, maxMana(player, config));
		state.barHidden = !state.barHidden;
		markDirty();
		player.displayClientMessage(
				Component.literal(state.barHidden ? "Индикатор маны скрыт" : "Индикатор маны показан")
						.withStyle(state.barHidden ? ChatFormatting.GRAY : ChatFormatting.AQUA),
				true
		);
		updateManaBar(player, state, maxMana(player, config));
		return 1;
	}

	private static void updateManaBar(ServerPlayer player, ManaState state, double maxMana) {
		if (state.barHidden) {
			hideManaBar(player);
			return;
		}
		ServerBossEvent bar = MANA_BARS.computeIfAbsent(player.getUUID(), ignored -> createManaBar());
		bar.setName(buildManaBarTitle(player, state.mana, maxMana));
		bar.setColor(BossEvent.BossBarColor.WHITE);
		bar.setProgress(manaBarProgress(state.mana, maxMana));
		bar.setVisible(true);
		boolean added = false;
		if (!bar.getPlayers().contains(player)) {
			bar.addPlayer(player);
			added = true;
		}
		if (added || player.level().getGameTime() % 20L == 0L) {
			ServerStabilitySystem.reorderHudBelowExternalBossBar(player);
			ServerBossBarVisibilitySystem.reorderTrackedBossBarsBelowReservedHud(player);
		}
	}

	private static Component buildManaBarTitle(ServerPlayer player, double mana, double maxMana) {
		if (PolymerResourcePackUtils.hasMainPack(player)) {
			return Component.literal(MANA_BAR_FRAME).withStyle(style -> style
					.withColor(0xFFFFFF)
					.withItalic(false)
					.withBold(false)
					.withFont(MANA_BAR_FONT)
					.withShadowColor(0x00000000));
		}
		return Component.literal("Мана: " + formatMana(mana) + "/" + formatMana(maxMana))
				.withStyle(ChatFormatting.AQUA);
	}

	static float manaBarProgress(double mana, double maxMana) {
		return maxMana <= 0.0D ? 0.0F : (float) Math.max(0.0D, Math.min(1.0D, mana / maxMana));
	}

	public static boolean isManaBossBar(ServerPlayer player, UUID bossBarId) {
		if (player == null || bossBarId == null) return false;
		ServerBossEvent bar = MANA_BARS.get(player.getUUID());
		return bar != null && bar.getId().equals(bossBarId);
	}

	private static void hideManaBar(ServerPlayer player) {
		ServerBossEvent bar = MANA_BARS.get(player.getUUID());
		if (bar == null) return;
		bar.removeAllPlayers();
		MANA_BARS.remove(player.getUUID(), bar);
	}

	private static ServerBossEvent createManaBar() {
		ServerBossEvent bar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS);
		bar.setDarkenScreen(false);
		bar.setPlayBossMusic(false);
		bar.setCreateWorldFog(false);
		return bar;
	}

	private static String formatMana(double value) {
		double rounded = Math.rint(value);
		return Math.abs(value - rounded) < 1.0E-6D ? Long.toString(Math.round(rounded)) : String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	private static RaceAbilityConfig config(ServerPlayer player) {
		return ServerRaceSystem.getAbility(player, RaceAbilitySlot.STOCK)
				.orElseGet(() -> RaceAbilityConfig.defaults(RaceAbilitySlot.STOCK));
	}

	private static double maxMana(ServerPlayer player, RaceAbilityConfig config) {
		double base = positiveOrDefault(config.necromancerManaBaseMax, DEFAULT_MANA_BASE_MAX);
		double perLevel = nonNegativeOrDefault(config.necromancerManaPerLevel, DEFAULT_MANA_PER_LEVEL);
		return Math.max(0.0D, base + perLevel * Math.max(0, player.experienceLevel));
	}

	private static ManaState stateFor(ServerPlayer player, double maxMana) {
		return MANA_STATES.computeIfAbsent(player.getUUID(), ignored -> {
			markDirty();
			return new ManaState(maxMana);
		});
	}

	private static void clearPlayer(ServerPlayer player) {
		if (player == null) return;
		removeModifier(player.getAttribute(Attributes.MAX_HEALTH), MAX_HEALTH_MODIFIER_ID);
		removeModifier(player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE), BLOCK_REACH_MODIFIER_ID);
		removeModifier(player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE), ENTITY_REACH_MODIFIER_ID);
		if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
		Boolean originalGlowing = ORIGINAL_GLOWING.remove(player.getUUID());
		if (originalGlowing != null) player.setGlowingTag(originalGlowing);
		clearRuntimeState(player);
	}

	private static void clearRuntimeState(ServerPlayer player) {
		if (player == null) return;
		restoreActionGameMode(player);
		HELD_ACTIONS.remove(player.getUUID());
		ACTION_CHARGE_DEADLINES.remove(player.getUUID());
		hideManaBar(player);
	}

	private static void removeModifier(AttributeInstance attribute, Identifier id) {
		if (attribute != null && attribute.getModifier(id) != null) attribute.removeModifier(id);
	}

	private static double positiveOrDefault(double value, double fallback) {
		return Double.isFinite(value) && value > 0.0D ? value : fallback;
	}

	private static double nonNegativeOrDefault(double value, double fallback) {
		return Double.isFinite(value) && value >= 0.0D ? value : fallback;
	}

	private static void markDirty() {
		dirty = true;
	}

	private static void load(MinecraftServer server) {
		MANA_STATES.clear();
		Path path = statePath(server);
		if (Files.exists(path)) {
			try {
				PersistedData data = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), PersistedData.class);
				if (data != null && data.players != null) MANA_STATES.putAll(data.players);
			} catch (Exception exception) {
				Lg2.LOGGER.error("Failed to load Necromancer mana state from {}", path, exception);
			}
		}
		for (ManaState state : MANA_STATES.values()) {
			if (!Double.isFinite(state.mana) || state.mana < 0.0D) state.mana = 0.0D;
		}
		dirty = false;
	}

	private static void save(MinecraftServer server) {
		if (server == null) return;
		PersistedData data = new PersistedData();
		data.players.putAll(MANA_STATES);
		Path path = statePath(server);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
			dirty = false;
		} catch (IOException exception) {
			Lg2.LOGGER.error("Failed to save Necromancer mana state to {}", path, exception);
		}
	}

	private static void shutdown(MinecraftServer server) {
		save(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) clearPlayer(player);
		for (ServerBossEvent bar : MANA_BARS.values()) bar.removeAllPlayers();
		MANA_BARS.clear();
		HELD_ACTIONS.clear();
		ACTION_CHARGE_DEADLINES.clear();
		ACTION_LOCK_PREVIOUS_GAME_TYPES.clear();
		ORIGINAL_GLOWING.clear();
	}

	private static Path statePath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(STATE_FILE_NAME);
	}

	private enum HeldActionKind {
		BREAKING,
		USING
	}

	private enum EraToolSet {
		WOOD(Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_SHOVEL, Items.WOODEN_HOE, false),
		STONE(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_HOE, false),
		COPPER(Items.COPPER_PICKAXE, Items.COPPER_AXE, Items.COPPER_SHOVEL, Items.COPPER_HOE, false),
		IRON(Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_HOE, true),
		GOLD(Items.GOLDEN_PICKAXE, Items.GOLDEN_AXE, Items.GOLDEN_SHOVEL, Items.GOLDEN_HOE, false),
		DIAMOND(Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE, true),
		NETHERITE(Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE, true);

		private final Item pickaxe;
		private final Item axe;
		private final Item shovel;
		private final Item hoe;
		private final boolean shears;

		EraToolSet(Item pickaxe, Item axe, Item shovel, Item hoe, boolean shears) {
			this.pickaxe = pickaxe;
			this.axe = axe;
			this.shovel = shovel;
			this.hoe = hoe;
			this.shears = shears;
		}

		private List<Item> miningTools() {
			return shears
					? List.of(pickaxe, axe, shovel, hoe, Items.SHEARS)
					: List.of(pickaxe, axe, shovel, hoe);
		}

		private Item shovel() {
			return shovel;
		}

		private Item hoe() {
			return hoe;
		}

		private boolean hasShears() {
			return shears;
		}
	}

	private static final class VirtualToolUseOnContext extends UseOnContext {
		private VirtualToolUseOnContext(
				Level level,
				Player player,
				InteractionHand hand,
				ItemStack tool,
				BlockHitResult hitResult
		) {
			super(level, player, hand, tool, hitResult);
		}
	}

	private static final class HeldAction {
		private final HeldActionKind kind;
		private final BlockPos blockPos;
		private final Direction direction;
		private long nextChargeTick;

		private HeldAction(HeldActionKind kind, BlockPos blockPos, Direction direction, long nextChargeTick) {
			this.kind = kind;
			this.blockPos = blockPos;
			this.direction = direction;
			this.nextChargeTick = nextChargeTick;
		}

		private static HeldAction breaking(BlockPos pos, Direction direction, long nextChargeTick) {
			return new HeldAction(HeldActionKind.BREAKING, pos, direction, nextChargeTick);
		}

		private static HeldAction using(long nextChargeTick) {
			return new HeldAction(HeldActionKind.USING, null, null, nextChargeTick);
		}
	}

	private static final class ManaState {
		private double mana;
		private boolean barHidden;

		private ManaState(double mana) {
			this.mana = mana;
		}
	}

	private static final class PersistedData {
		private int version = 1;
		private final Map<UUID, ManaState> players = new LinkedHashMap<>();
	}
}
