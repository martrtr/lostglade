package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.block.ModBlocks;
import com.lostglade.config.RaceConfig.PlayerRaceConfig;
import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import com.lostglade.config.RaceConfig.RaceAbilitySlot;
import com.lostglade.item.ModItems;
import com.lostglade.util.ItemDisplayHitboxHelper;
import com.lostglade.mixin.EntityTrackedDataAccessor;
import com.lostglade.mixin.LivingEntityTrackedDataAccessor;
import com.mojang.math.Transformation;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CopperManGogglesSystem {
	private static final String COPPER_MAN_RACE_ID = "copper_man";
	private static final Identifier HEAD_MODEL_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "copper_goggles_head");
	private static final Identifier INVISIBLE_MAGNIFIER_MODEL_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "gui/button/invisible");
	private static final Identifier ORE_HIGHLIGHT_CARRIER_ITEM_MODEL_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "ore_highlight_carrier");
	private static final Identifier SCAN_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "goggles_scan");
	private static final Holder<SoundEvent> SCAN_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(SCAN_SOUND_ID));
	private static final Identifier RECIPE_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "copper_goggles");
	private static final String GOGGLES_META_TAG = "lg2_copper_goggles";
	private static final String GOGGLES_MODE_TAG = "mode";
	private static final FontDescription SCREEN_OVERLAY_FONT = new FontDescription.Resource(
			Objects.requireNonNull(Identifier.tryParse("lg2:copper_goggles_overlay"))
	);
	private static final String TITLE_OVERLAY_SHIFT = "\ue905";
	private static final String TITLE_OVERLAY_RESET = "\ue940\ue940\ue941\ue943";
	private static final int SCREEN_OVERLAY_X_OFFSET = -120;
	private static final int SCREEN_OVERLAY_X_CORRECTION = -20;
	private static final String[] SCREEN_OVERLAY_GLYPH_FRAMES = {
			"\uef8a" + buildHorizontalAdvance(-1) + "\uef8b" + buildHorizontalAdvance(-1) + "\uef8c",
			"\uef8d" + buildHorizontalAdvance(-1) + "\uef8e" + buildHorizontalAdvance(-1) + "\uef8f",
			"\uef90" + buildHorizontalAdvance(-1) + "\uef91" + buildHorizontalAdvance(-1) + "\uef92",
			"\uef93" + buildHorizontalAdvance(-1) + "\uef94" + buildHorizontalAdvance(-1) + "\uef95"
	};
	private static final long SCREEN_OVERLAY_FRAME_TICKS = 3L;
	private static final int SCREEN_OVERLAY_TITLE_COLOR = 0xFFFFFF;
	private static final int SCREEN_OVERLAY_HUD_COLOR = 0x31B814;
	private static final int SCREEN_OVERLAY_ACCENT_COLOR = 0x55FF22;
	private static final double DEFAULT_SCAN_COOLDOWN_SECONDS = 600.0D;
	private static final double DEFAULT_ORE_SEARCH_RADIUS_BLOCKS = 10.0D;
	private static final double DEFAULT_ORE_SEARCH_HIGHLIGHT_SECONDS = 3.0D;
	private static final double DEFAULT_TRACKING_RADIUS_BLOCKS = 20.0D;
	private static final double DEFAULT_TRACKING_HIGHLIGHT_SECONDS = 45.0D;
	private static final double SCAN_WAVE_VIEW_DISTANCE_BLOCKS = 48.0D;
	private static final double TRACKING_TRIGGER_HEAD_FORWARD_OFFSET = 0.22D;
	private static final float TRACKING_TRIGGER_WIDTH = 1.8F;
	private static final float TRACKING_TRIGGER_HEIGHT = 1.8F;
	private static final float SCAN_WAVE_SPEED_BLOCKS_PER_TICK = 0.5F;
	private static final float SCAN_WAVE_PARTICLE_SCALE = 1.1F;
	private static final int ORE_SEARCH_MAX_HIGHLIGHTS = 128;
	private static final int TRACKING_MAX_HIGHLIGHTS = 128;
	private static final long TRACKING_REFRESH_INTERVAL_TICKS = 10L;
	private static final double SCAN_SOUND_RANGE_BLOCKS = 16.0D;
	private static final float SCAN_SOUND_VOLUME = 0.9F;
	private static final float SCAN_SOUND_PITCH = 1.0F;
	private static final float SCAN_FALLBACK_SOUND_VOLUME = 0.8F;
	private static final float SCAN_FALLBACK_SOUND_PITCH = 1.25F;
	private static final float SCAN_TECH_LAYER_FALLBACK_VOLUME = 0.32F;
	private static final float SCAN_TECH_LAYER_PITCH = 1.65F;
	private static final float HIGHLIGHT_VIEW_RANGE = 1_000_000.0F;
	private static final float ORE_HIGHLIGHT_SCALE = 2.0F;
	private static final byte GLOWING_FLAG_MASK = 0x40;
	private static final byte USING_ITEM_FLAG_MASK = 0x01;
	private static final byte USING_OFFHAND_FLAG_MASK = 0x02;
	private static final DustParticleOptions SCAN_WAVE_PARTICLE = new DustParticleOptions(0xA5FF2A, SCAN_WAVE_PARTICLE_SCALE);
	private static final Map<UUID, Boolean> LAST_VISUAL_STATES = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> LAST_SCREEN_OVERLAY_STATES = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> LAST_NIGHT_VISION_STATES = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> SCAN_COOLDOWNS = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_SCAN_ACTIVATION_TICKS = new ConcurrentHashMap<>();
	private static final Map<UUID, ScanWave> ACTIVE_SCAN_WAVES = new ConcurrentHashMap<>();
	private static final Map<UUID, OreSearchHighlightSession> ACTIVE_ORE_SEARCH_HIGHLIGHTS = new ConcurrentHashMap<>();
	private static final Map<UUID, TrackingHighlightSession> ACTIVE_TRACKING_HIGHLIGHTS = new ConcurrentHashMap<>();
	private static final Map<UUID, Interaction> TRACKING_AIR_TRIGGERS = new ConcurrentHashMap<>();
	private static final Map<UUID, MagnifierSession> ACTIVE_MAGNIFIERS = new ConcurrentHashMap<>();

	private CopperManGogglesSystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(CopperManGogglesSystem::tickVisuals);
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			return onUseItem(serverPlayer, hand);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				AccountAuthSystem.runWhenAuthenticated(handler.player, () -> server.execute(() -> {
					syncViewer(handler.player);
					refreshVisual(handler.player);
				}))
		);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID playerId = handler.player.getUUID();
			clearManagedNightVision(handler.player);
			stopMagnifier(handler.player);
			clearScanVisuals(handler.player);
			LAST_VISUAL_STATES.remove(playerId);
			LAST_SCREEN_OVERLAY_STATES.remove(playerId);
			LAST_NIGHT_VISION_STATES.remove(playerId);
			SCAN_COOLDOWNS.remove(playerId);
			LAST_SCAN_ACTIVATION_TICKS.remove(playerId);
			removeTrackingAirTrigger(playerId);
			ACTIVE_SCAN_WAVES.remove(playerId);
			ACTIVE_ORE_SEARCH_HIGHLIGHTS.remove(playerId);
			ACTIVE_TRACKING_HIGHLIGHTS.remove(playerId);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
				clearManagedNightVision(player);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			LAST_VISUAL_STATES.clear();
			LAST_SCREEN_OVERLAY_STATES.clear();
			LAST_NIGHT_VISION_STATES.clear();
			SCAN_COOLDOWNS.clear();
			LAST_SCAN_ACTIVATION_TICKS.clear();
			TRACKING_AIR_TRIGGERS.values().forEach(Entity::discard);
			TRACKING_AIR_TRIGGERS.clear();
			ACTIVE_MAGNIFIERS.clear();
			ACTIVE_SCAN_WAVES.clear();
			ACTIVE_ORE_SEARCH_HIGHLIGHTS.clear();
			ACTIVE_TRACKING_HIGHLIGHTS.clear();
		});
	}

	public static void registerLateInteractions() {
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			return onUseEntity(serverPlayer, hand, entity);
		});
	}

	public static void resetAllAbilityCooldowns(MinecraftServer server) {
		SCAN_COOLDOWNS.clear();
		LAST_SCAN_ACTIVATION_TICKS.clear();
	}

	public static int toggleMode(ServerPlayer player) {
		if (player == null) {
			return 0;
		}
		if (getGogglesAbility(player).isEmpty()) {
			return 0;
		}
		ItemStack equippedGoggles = getEquippedGogglesStack(player);
		if (equippedGoggles.isEmpty()) {
			MutableComponent notEquippedMessage = PolymerResourcePackUtils.hasMainPack(player)
					? Component.translatable("message.lg2.copper_goggles.not_equipped")
					: Component.literal(localizeGogglesNotEquipped(player));
			player.displayClientMessage(
					notEquippedMessage.withStyle(style -> style.withColor(ChatFormatting.RED).withItalic(false)),
					true
			);
			return 0;
		}
		return cycleMode(player, equippedGoggles);
	}

	public static int toggleInventoryMode(ServerPlayer player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty() || stack.getItem() != ModItems.COPPER_GOGGLES) {
			return 0;
		}
		if (stack == getEquippedGogglesStack(player)) {
			return 0;
		}
		return cycleMode(player, stack);
	}

	public static boolean handleHeldModeToggle(ServerPlayer player, InteractionHand hand) {
		if (player == null || hand == null || !player.isSecondaryUseActive()) {
			return false;
		}
		ItemStack stack = player.getItemInHand(hand);
		if (stack.isEmpty() || stack.getItem() != ModItems.COPPER_GOGGLES) {
			return false;
		}

		ItemStack updated = stack.copy();
		GogglesMode next = advanceMode(updated);
		player.setItemInHand(hand, updated);
		finishModeChange(player, next, false);
		resyncHeldToggleState(player);
		player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.55F, 1.15F);
		return true;
	}

	public static boolean handleInventoryModeClick(
			ServerPlayer player,
			AbstractContainerMenu menu,
			int slotIndex,
			ClickType clickType,
			int button
	) {
		if (player == null || menu == null) {
			return false;
		}
		boolean isRightClick = clickType == ClickType.PICKUP && button == 1;
		boolean isShiftClick = clickType == ClickType.QUICK_MOVE;
		if (!isRightClick && !isShiftClick) {
			return false;
		}
		if (!menu.isValidSlotIndex(slotIndex)) {
			return false;
		}
		Slot slot = menu.getSlot(slotIndex);
		if (slot == null || slot.container != player.getInventory()) {
			return false;
		}
		int containerSlot = slot.getContainerSlot();
		if (containerSlot < 0 || containerSlot >= 36) {
			return false;
		}
		ItemStack stack = slot.getItem();
		if (stack.isEmpty() || stack.getItem() != ModItems.COPPER_GOGGLES) {
			return false;
		}
		ItemStack updated = stack.copy();
		GogglesMode next = advanceMode(updated);
		slot.set(updated);
		slot.setChanged();
		finishModeChange(player, next, false);
		player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.55F, 1.15F);
		return true;
	}

	private static int cycleMode(ServerPlayer player, ItemStack stack) {
		GogglesMode next = advanceMode(stack);
		boolean affectsActiveGoggles = stack == getEquippedGogglesStack(player);
		finishModeChange(player, next, affectsActiveGoggles);
		return 1;
	}

	private static GogglesMode advanceMode(ItemStack stack) {
		GogglesMode next = getMode(stack).next();
		setMode(stack, next);
		return next;
	}

	private static void finishModeChange(ServerPlayer player, GogglesMode next, boolean affectsActiveGoggles) {
		if (affectsActiveGoggles) {
			clearScanVisuals(player);
			syncNightVision(player);
			refreshVisual(player);
		}
		player.getInventory().setChanged();
		player.inventoryMenu.broadcastChanges();
		player.containerMenu.broadcastChanges();
		MutableComponent modeMessage = PolymerResourcePackUtils.hasMainPack(player)
				? Component.translatable("message.lg2.copper_goggles.mode", Component.translatable(next.translationKey()))
				: Component.literal(localizeGogglesModeMessage(player, next));
		player.displayClientMessage(
				modeMessage.withStyle(style -> style.withColor(ChatFormatting.GREEN).withItalic(false)),
				true
		);
	}

	private static void resyncHeldToggleState(ServerPlayer player) {
		if (player == null) {
			return;
		}
		player.getInventory().setChanged();
		player.inventoryMenu.broadcastFullState();
		player.inventoryMenu.sendAllDataToRemote();
		if (player.containerMenu != player.inventoryMenu) {
			player.containerMenu.broadcastFullState();
			player.containerMenu.sendAllDataToRemote();
		}
		sendActualHands(player);
		player.connection.send(new ClientboundSetEquipmentPacket(
				player.getId(),
				List.of(com.mojang.datafixers.util.Pair.of(EquipmentSlot.HEAD, player.getItemBySlot(EquipmentSlot.HEAD).copy()))
		));
	}

	public static void syncPlayerRecipeBook(ServerPlayer player) {
		if (player == null) {
			return;
		}

		Collection<RecipeHolder<?>> holders = collectRecipeHolders(player.level().getServer());
		if (holders.isEmpty()) {
			return;
		}

		if (canSeeRecipe(player)) {
			player.awardRecipes(holders);
		} else {
			player.resetRecipes(holders);
		}
		ServerRecipeBook recipeBook = player.getRecipeBook();
		recipeBook.sendInitialRecipeBook(player);
	}

	public static Collection<RecipeHolder<?>> filterAwardedRecipes(ServerPlayer player, Collection<RecipeHolder<?>> recipes) {
		if (player == null || recipes == null || recipes.isEmpty() || canSeeRecipe(player)) {
			return recipes;
		}

		List<RecipeHolder<?>> filtered = new ArrayList<>(recipes.size());
		for (RecipeHolder<?> holder : recipes) {
			if (holder == null || !isCopperGogglesRecipe(holder.id())) {
				filtered.add(holder);
			}
		}
		return filtered;
	}

	public static List<ResourceKey<Recipe<?>>> filterAwardedRecipeKeys(ServerPlayer player, List<ResourceKey<Recipe<?>>> recipeKeys) {
		if (player == null || recipeKeys == null || recipeKeys.isEmpty() || canSeeRecipe(player)) {
			return recipeKeys;
		}

		List<ResourceKey<Recipe<?>>> filtered = new ArrayList<>(recipeKeys.size());
		for (ResourceKey<Recipe<?>> recipeKey : recipeKeys) {
			if (recipeKey == null || !isCopperGogglesRecipe(recipeKey)) {
				filtered.add(recipeKey);
			}
		}
		return filtered;
	}

	public static boolean canTakeCraftResult(ServerPlayer player, ItemStack stack) {
		if (player == null || stack == null || stack.isEmpty() || stack.getItem() != ModItems.COPPER_GOGGLES) {
			return true;
		}
		return canSeeRecipe(player);
	}

	public static boolean canShowCraftingResult(ServerPlayer player, RecipeHolder<?> recipeHolder, ItemStack stack) {
		if (stack == null || stack.isEmpty() || stack.getItem() != ModItems.COPPER_GOGGLES) {
			return true;
		}
		return player != null && canSeeRecipe(player);
	}

	public static boolean canAutoCraft(RecipeHolder<?> recipeHolder) {
		return recipeHolder == null || !isCopperGogglesRecipe(recipeHolder.id());
	}

	public static String getCurrentModeId(ServerPlayer player) {
		return getMode(player).name();
	}

	public static String getCurrentModeId(ItemStack stack) {
		return getMode(stack).name();
	}

	public static void refreshVisual(ServerPlayer player) {
		if (player == null) {
			return;
		}
		boolean shouldSpoof = shouldSpoofVisual(player);
		LAST_VISUAL_STATES.put(player.getUUID(), shouldSpoof);
		syncWearerToAllViewers(player, shouldSpoof);
		syncScreenOverlay(player, shouldShowScreenOverlay(player));
		syncNightVision(player);
		if (!shouldSpoof) {
			clearScanVisuals(player);
		}
	}

	public static Component getScreenOverlayTitle(ServerPlayer player) {
		Component priorityTitle = ServerRaceSystem.getKilkaAttackFlashTitleOverride(player);
		if (priorityTitle != null) {
			return priorityTitle;
		}
		priorityTitle = PuroSanStockSystem.getAimWarningTitleOverride(player);
		if (priorityTitle != null) {
			return priorityTitle;
		}
		return shouldShowScreenOverlay(player) ? buildScreenOverlayTitle(player) : Component.empty();
	}

	public static int getOverlayTintColor(ServerPlayer player) {
		return shouldShowScreenOverlay(player) ? SCREEN_OVERLAY_HUD_COLOR : 0xFFFFFF;
	}

	public static int getOverlayAccentColor(ServerPlayer player) {
		return shouldShowScreenOverlay(player) ? SCREEN_OVERLAY_ACCENT_COLOR : 0xFFFFFF;
	}

	public static boolean canSeeRecipe(ServerPlayer player) {
		Optional<PlayerRaceConfig> raceOptional = ServerRaceSystem.getRace(player);
		if (raceOptional.isEmpty()) {
			return false;
		}

		PlayerRaceConfig race = raceOptional.get();
		String raceId = race.id == null ? "" : race.id.trim().toLowerCase(Locale.ROOT);
		return COPPER_MAN_RACE_ID.equals(raceId)
				&& race.shnyaga != null
				&& race.shnyaga.enabled
				&& ServerRaceSystem.hasUnlockedAbility(player, RaceAbilitySlot.SHNYAGA);
	}

	public static boolean handleUseOnBlockPass(ServerPlayer player, InteractionHand hand) {
		if (!shouldHandleModeUse(player, hand)) {
			return false;
		}
		return activateCurrentMode(player, hand);
	}

	public static void handleAnyBlockUse(ServerPlayer player, InteractionHand hand) {
		if (player == null || hand == null || getMode(player) != GogglesMode.TRACKING) {
			return;
		}
		if (!shouldHandleTrackingUse(player, hand)) {
			return;
		}
		activateTracking(player);
	}

	public static void handleUseAirPacket(ServerPlayer player, InteractionHand hand) {
		if (player == null || hand == null) {
			return;
		}
		GogglesMode mode = getMode(player);
		if (mode != GogglesMode.TRACKING && mode != GogglesMode.MAGNIFIER) {
			return;
		}
		if (!shouldHandleModeUse(player, hand)) {
			return;
		}
		activateCurrentMode(player, hand);
	}

	public static void handleMovePacket(ServerPlayer player) {
		if (player == null) {
			return;
		}
		syncTrackingAirTrigger(player);
	}

	public static void handleReleaseUsePacket(ServerPlayer player) {
		if (player == null) {
			return;
		}
		stopMagnifier(player);
	}

	public static void handleSelectedSlotChange(ServerPlayer player, int newSelectedSlot) {
		if (player == null || !ACTIVE_MAGNIFIERS.containsKey(player.getUUID())) {
			return;
		}
		stopMagnifier(player);
		if (newSelectedSlot >= 0) {
			sendActualInventorySlot(player, newSelectedSlot);
		}
	}

	public static void handleSelectedSlotChangeApplied(ServerPlayer player, int newSelectedSlot) {
		if (player == null || newSelectedSlot < 0) {
			return;
		}
		sendActualInventorySlot(player, newSelectedSlot);
	}

	public static void handleSwapWithOffhand(ServerPlayer player) {
		if (player == null || !ACTIVE_MAGNIFIERS.containsKey(player.getUUID())) {
			return;
		}
		stopMagnifier(player);
		sendActualInventorySlot(player, Inventory.SLOT_OFFHAND);
		sendActualHands(player);
	}

	public static void handleSwapWithOffhandApplied(ServerPlayer player) {
		if (player == null) {
			return;
		}
		sendActualInventorySlot(player, player.getInventory().getSelectedSlot());
		sendActualInventorySlot(player, Inventory.SLOT_OFFHAND);
		sendActualHands(player);
	}

	public static boolean shouldCancelUseItemPacket(ServerPlayer player, InteractionHand hand) {
		return player != null
				&& hand != null
				&& getMode(player) == GogglesMode.MAGNIFIER
				&& shouldHandleModeUse(player, hand);
	}

	private static InteractionResult onUseEntity(ServerPlayer player, InteractionHand hand, Entity target) {
		if (player == null || hand == null || target == null) {
			return InteractionResult.PASS;
		}
		if (getMode(player) == GogglesMode.TRACKING && shouldHandleTrackingUse(player, hand)) {
			activateTracking(player);
			return InteractionResult.PASS;
		}
		if (isTrackingAirTrigger(player, target)) {
			return switch (getMode(player)) {
				case TRACKING -> activateTracking(player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
				case MAGNIFIER -> startMagnifier(player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
				case ORE_SEARCH, NIGHT_VISION -> InteractionResult.PASS;
			};
		}
		if (getMode(player) == GogglesMode.MAGNIFIER && shouldHandleModeUse(player, hand)) {
			return startMagnifier(player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		}
		return InteractionResult.PASS;
	}

	private static InteractionResult onUseItem(ServerPlayer player, InteractionHand hand) {
		if (!shouldHandleModeUse(player, hand)) {
			return InteractionResult.PASS;
		}
		return activateCurrentMode(player, hand) ? InteractionResult.PASS : InteractionResult.PASS;
	}

	private static boolean activateCurrentMode(ServerPlayer player, InteractionHand hand) {
		return switch (getMode(player)) {
			case ORE_SEARCH -> activateOreSearch(player, hand);
			case TRACKING -> activateTracking(player);
			case MAGNIFIER -> startMagnifier(player);
			case NIGHT_VISION -> false;
		};
	}

	private static boolean activateOreSearch(ServerPlayer player, InteractionHand hand) {
		RaceAbilityConfig ability = getEffectiveGogglesAbility(player);
		ItemStack stack = resolveOreSearchSampleStack(player);
		OreSearchMaterial material = resolveOreSearchMaterial(stack);
		if (material == null) {
			return false;
		}
		long nowTick = player.level().getGameTime();
		if (LAST_SCAN_ACTIVATION_TICKS.getOrDefault(player.getUUID(), Long.MIN_VALUE) == nowTick) {
			return true;
		}
		long cooldownEndTick = SCAN_COOLDOWNS.getOrDefault(player.getUUID(), Long.MIN_VALUE);
		if (cooldownEndTick > nowTick) {
			displayRemainingCooldown(player, cooldownEndTick - nowTick);
			return true;
		}

		double radius = getOreSearchRadiusBlocks(ability);
		long highlightTicks = Math.max(1L, Math.round(getOreSearchHighlightSeconds(ability) * 20.0D));
		long cooldownTicks = Math.max(1L, Math.round(getScanCooldownSeconds(ability) * 20.0D));
		Vec3 center = player.getEyePosition();

		ACTIVE_SCAN_WAVES.put(player.getUUID(), createWave(center, radius, nowTick));
		List<BlockPos> matchingOres = findMatchingOres(player, material, center, radius);
		showOreSearchHighlights(player, matchingOres, nowTick + highlightTicks);
		SCAN_COOLDOWNS.put(player.getUUID(), nowTick + cooldownTicks);
		LAST_SCAN_ACTIVATION_TICKS.put(player.getUUID(), nowTick);
		playScanActivationSound(player);
		return true;
	}

	private static boolean activateTracking(ServerPlayer player) {
		RaceAbilityConfig ability = getEffectiveGogglesAbility(player);
		long nowTick = player.level().getGameTime();
		if (LAST_SCAN_ACTIVATION_TICKS.getOrDefault(player.getUUID(), Long.MIN_VALUE) == nowTick) {
			return true;
		}
		long cooldownEndTick = SCAN_COOLDOWNS.getOrDefault(player.getUUID(), Long.MIN_VALUE);
		if (cooldownEndTick > nowTick) {
			displayRemainingCooldown(player, cooldownEndTick - nowTick);
			return true;
		}

		double radius = getTrackingRadiusBlocks(ability);
		long highlightTicks = Math.max(1L, Math.round(getTrackingHighlightSeconds(ability) * 20.0D));
		long cooldownTicks = Math.max(1L, Math.round(getScanCooldownSeconds(ability) * 20.0D));
		Vec3 center = player.getEyePosition();

		ACTIVE_SCAN_WAVES.put(player.getUUID(), createWave(center, radius, nowTick));
		showTrackingHighlights(player, findTrackingTargets(player, center, radius), nowTick + highlightTicks);
		SCAN_COOLDOWNS.put(player.getUUID(), nowTick + cooldownTicks);
		LAST_SCAN_ACTIVATION_TICKS.put(player.getUUID(), nowTick);
		playScanActivationSound(player);
		return true;
	}

	private static void playScanActivationSound(ServerPlayer player) {
		if (player == null || !(player.level() instanceof ServerLevel level)) {
			return;
		}

		Vec3 origin = player.position().add(0.0D, Math.max(0.4D, player.getBbHeight() * 0.5D), 0.0D);
		double rangeSqr = SCAN_SOUND_RANGE_BLOCKS * SCAN_SOUND_RANGE_BLOCKS;
		long seed = level.getRandom().nextLong();
		for (ServerPlayer viewer : level.players()) {
			if (viewer == null
					|| viewer.connection == null
					|| !viewer.isAlive()
					|| viewer.isSpectator()
					|| !isWearingCopperGoggles(viewer)
					|| viewer.distanceToSqr(origin) > rangeSqr) {
				continue;
			}

			boolean hasPack = PolymerResourcePackUtils.hasMainPack(viewer);
			Holder<SoundEvent> sound = hasPack
					? SCAN_SOUND
					: BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.TRIAL_SPAWNER_DETECT_PLAYER);
			viewer.connection.send(new ClientboundSoundPacket(
					sound,
					SoundSource.PLAYERS,
					origin.x,
					origin.y,
					origin.z,
					hasPack ? SCAN_SOUND_VOLUME : SCAN_FALLBACK_SOUND_VOLUME,
					hasPack ? SCAN_SOUND_PITCH : SCAN_FALLBACK_SOUND_PITCH,
					seed
			));
			if (hasPack) {
				continue;
			}
			viewer.connection.send(new ClientboundSoundPacket(
					BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BEACON_POWER_SELECT),
					SoundSource.PLAYERS,
					origin.x,
					origin.y,
					origin.z,
					SCAN_TECH_LAYER_FALLBACK_VOLUME,
					SCAN_TECH_LAYER_PITCH,
					seed + 1L
			));
		}
	}

	private static boolean shouldHandleModeUse(ServerPlayer player, InteractionHand hand) {
		if (player == null || hand == null || !player.isAlive() || player.isSpectator()) {
			return false;
		}
		if (!isWearingCopperGoggles(player)) {
			return false;
		}
		return switch (getMode(player)) {
			case ORE_SEARCH -> !resolveOreSearchSampleStack(player).isEmpty();
			case TRACKING -> shouldHandleTrackingUse(player, hand);
			case MAGNIFIER -> shouldHandleMagnifierUse(player);
			case NIGHT_VISION -> false;
		};
	}

	private static boolean shouldHandleTrackingUse(ServerPlayer player, InteractionHand hand) {
		return player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty();
	}

	private static boolean shouldHandleMagnifierUse(ServerPlayer player) {
		return player != null
				&& !CopperManRepulsorSystem.shouldBlockMagnifier(player)
				&& player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty();
	}

	private static boolean isLikelyRightClickable(ServerPlayer player, ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (stack.get(DataComponents.CONSUMABLE) != null || stack.get(DataComponents.FOOD) != null) {
			return true;
		}
		if (stack.getUseAnimation() != ItemUseAnimation.NONE || stack.getUseDuration(player) > 0) {
			return true;
		}
		if (stack.getItem() instanceof BlockItem || stack.getItem() instanceof BucketItem) {
			return true;
		}
		return stack.is(Items.BONE_MEAL)
				|| stack.is(Items.FLINT_AND_STEEL)
				|| stack.is(Items.FIRE_CHARGE)
				|| stack.is(Items.END_CRYSTAL)
				|| stack.is(Items.ARMOR_STAND)
				|| stack.is(Items.ITEM_FRAME)
				|| stack.is(Items.GLOW_ITEM_FRAME)
				|| stack.is(Items.PAINTING)
				|| stack.is(Items.LEAD)
				|| stack.is(Items.NAME_TAG)
				|| stack.is(Items.SHEARS)
				|| stack.is(Items.SADDLE);
	}

	private static void tickVisuals(MinecraftServer server) {
		if (server == null) {
			return;
		}

		long gameTime = server.overworld().getGameTime();
		Set<UUID> online = ConcurrentHashMap.newKeySet();
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			online.add(player.getUUID());
			boolean shouldSpoof = shouldSpoofVisual(player);
			Boolean previous = LAST_VISUAL_STATES.put(player.getUUID(), shouldSpoof);
			if (previous == null || previous.booleanValue() != shouldSpoof) {
				syncWearerToAllViewers(player, shouldSpoof);
			}

			boolean shouldShowOverlay = shouldShowScreenOverlay(player);
			Boolean previousOverlay = LAST_SCREEN_OVERLAY_STATES.put(player.getUUID(), shouldShowOverlay);
			if (previousOverlay == null
					|| previousOverlay.booleanValue() != shouldShowOverlay
					|| (shouldShowOverlay && gameTime % SCREEN_OVERLAY_FRAME_TICKS == 0L)) {
				syncScreenOverlay(player, shouldShowOverlay);
			}

			syncNightVision(player);
			tickScanState(player, gameTime);
		}
		LAST_VISUAL_STATES.keySet().removeIf(uuid -> !online.contains(uuid));
		LAST_SCREEN_OVERLAY_STATES.keySet().removeIf(uuid -> !online.contains(uuid));
		LAST_NIGHT_VISION_STATES.keySet().removeIf(uuid -> !online.contains(uuid));
		SCAN_COOLDOWNS.keySet().removeIf(uuid -> !online.contains(uuid));
		ACTIVE_SCAN_WAVES.keySet().removeIf(uuid -> !online.contains(uuid));
		ACTIVE_ORE_SEARCH_HIGHLIGHTS.entrySet().removeIf(entry -> !online.contains(entry.getKey()));
		ACTIVE_TRACKING_HIGHLIGHTS.entrySet().removeIf(entry -> !online.contains(entry.getKey()));
	}

	private static void tickScanState(ServerPlayer player, long gameTime) {
		if (player == null) {
			return;
		}
		syncMagnifier(player);
		syncTrackingAirTrigger(player);
		if (!isWearingCopperGoggles(player)) {
			clearScanVisuals(player);
			return;
		}

		ScanWave wave = ACTIVE_SCAN_WAVES.get(player.getUUID());
		if (wave != null) {
			if (gameTime > wave.endTick()) {
				ACTIVE_SCAN_WAVES.remove(player.getUUID());
			} else {
				spawnWaveParticles(player, wave, gameTime);
			}
		}

		OreSearchHighlightSession highlightSession = ACTIVE_ORE_SEARCH_HIGHLIGHTS.get(player.getUUID());
		if (highlightSession != null && gameTime > highlightSession.expireTick()) {
			clearOreSearchHighlights(player);
		}

		TrackingHighlightSession trackingSession = ACTIVE_TRACKING_HIGHLIGHTS.get(player.getUUID());
		if (trackingSession != null) {
			if (gameTime > trackingSession.expireTick()) {
				clearTrackingHighlights(player);
			} else if (gameTime >= trackingSession.nextRefreshTick()) {
				refreshTrackingHighlights(player, trackingSession.entityIds(), gameTime);
			}
		}
	}

	private static ScanWave createWave(Vec3 center, double radiusBlocks, long startTick) {
		long durationTicks = Math.max(1L, (long) Math.ceil(radiusBlocks / SCAN_WAVE_SPEED_BLOCKS_PER_TICK));
		return new ScanWave(center, radiusBlocks, startTick, startTick + durationTicks);
	}

	private static boolean startMagnifier(ServerPlayer player) {
		if (player == null || !shouldHandleMagnifierUse(player)) {
			return false;
		}

		int selectedSlot = player.getInventory().getSelectedSlot();
		MagnifierSession current = ACTIVE_MAGNIFIERS.get(player.getUUID());
		if (current != null) {
			sendMagnifierVisual(player, current);
			return true;
		}

		MagnifierSession session = new MagnifierSession(selectedSlot);
		ACTIVE_MAGNIFIERS.put(player.getUUID(), session);
		sendMagnifierVisual(player, session);
		return true;
	}

	private static void syncMagnifier(ServerPlayer player) {
		if (player == null) {
			return;
		}
		MagnifierSession session = ACTIVE_MAGNIFIERS.get(player.getUUID());
		if (session == null) {
			return;
		}
		if (!shouldMaintainMagnifier(player, session)) {
			stopMagnifier(player);
			return;
		}
		sendActualInventorySlot(player, session.selectedSlot());
		sendMagnifierVisual(player, session);
	}

	private static boolean shouldMaintainMagnifier(ServerPlayer player, MagnifierSession session) {
		return player != null
				&& session != null
				&& player.isAlive()
				&& !player.isSpectator()
				&& isWearingCopperGoggles(player)
				&& getMode(player) == GogglesMode.MAGNIFIER
				&& player.getInventory().getSelectedSlot() == session.selectedSlot()
				&& player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty();
	}

	private static void stopMagnifier(ServerPlayer player) {
		if (player == null) {
			return;
		}
		MagnifierSession session = ACTIVE_MAGNIFIERS.remove(player.getUUID());
		if (session == null) {
			return;
		}
		sendActualInventorySlot(player, session.selectedSlot());
		sendActualInventorySlot(player, player.getInventory().getSelectedSlot());
		sendActualInventorySlot(player, Inventory.SLOT_OFFHAND);
		sendActualSelectedSlot(player);
		sendActualHands(player);
		sendMagnifierUseFlags(player, false);
	}

	private static void sendMagnifierVisual(ServerPlayer player, MagnifierSession session) {
		if (player == null || session == null) {
			return;
		}
		ItemStack spyglass = buildVirtualSpyglassStack(player);
		player.connection.send(new ClientboundSetEquipmentPacket(
				player.getId(),
				List.of(com.mojang.datafixers.util.Pair.of(EquipmentSlot.MAINHAND, spyglass))
		));
		sendMagnifierUseFlags(player, true);
	}

	private static void sendActualSelectedSlot(ServerPlayer player) {
		if (player == null) {
			return;
		}
		ItemStack actual = player.getMainHandItem().copy();
		player.connection.send(new ClientboundSetEquipmentPacket(
				player.getId(),
				List.of(com.mojang.datafixers.util.Pair.of(EquipmentSlot.MAINHAND, actual))
		));
	}

	private static void sendActualHands(ServerPlayer player) {
		if (player == null) {
			return;
		}
		player.connection.send(new ClientboundSetEquipmentPacket(
				player.getId(),
				List.of(
						com.mojang.datafixers.util.Pair.of(EquipmentSlot.MAINHAND, player.getMainHandItem().copy()),
						com.mojang.datafixers.util.Pair.of(EquipmentSlot.OFFHAND, player.getOffhandItem().copy())
				)
		));
	}

	private static void sendMagnifierUseFlags(ServerPlayer player, boolean active) {
		if (player == null) {
			return;
		}
		EntityDataAccessor<Byte> accessor = LivingEntityTrackedDataAccessor.lg2$getDataLivingEntityFlags();
		byte flags = player.getEntityData().get(accessor);
		byte updated = active
				? (byte) ((flags | USING_ITEM_FLAG_MASK) & ~USING_OFFHAND_FLAG_MASK)
				: (byte) (flags & ~USING_ITEM_FLAG_MASK & ~USING_OFFHAND_FLAG_MASK);
		player.connection.send(new ClientboundSetEntityDataPacket(
				player.getId(),
				List.of(SynchedEntityData.DataValue.create(accessor, updated))
		));
	}

	private static void sendActualInventorySlot(ServerPlayer player, int inventorySlot) {
		if (player == null || inventorySlot < 0) {
			return;
		}
		AbstractContainerMenu menu = player.inventoryMenu;
		Inventory inventory = player.getInventory();
		int menuSlot = findInventoryMenuSlot(menu, inventory, inventorySlot);
		if (menu == null || menuSlot < 0) {
			return;
		}
		int stateId = menu.incrementStateId();
		player.connection.send(new ClientboundContainerSetSlotPacket(
				menu.containerId,
				stateId,
				menuSlot,
				inventory.getItem(inventorySlot).copy()
		));
	}

	private static int findInventoryMenuSlot(AbstractContainerMenu menu, Inventory inventory, int inventorySlot) {
		if (menu == null || inventory == null || inventorySlot < 0) {
			return -1;
		}
		for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
			Slot slot = menu.getSlot(menuSlot);
			if (slot.container == inventory && slot.getContainerSlot() == inventorySlot) {
				return menuSlot;
			}
		}
		return -1;
	}

	private static ItemStack buildVirtualSpyglassStack(ServerPlayer viewer) {
		ItemStack stack = new ItemStack(Items.SPYGLASS);
		if (viewer != null && PolymerResourcePackUtils.hasMainPack(viewer)) {
			stack.set(DataComponents.ITEM_MODEL, INVISIBLE_MAGNIFIER_MODEL_ID);
			stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
		}
		return stack;
	}

	private static void syncTrackingAirTrigger(ServerPlayer player) {
		if (!shouldMaintainTrackingAirTrigger(player)) {
			removeTrackingAirTrigger(player == null ? null : player.getUUID());
			return;
		}
		if (hasTrackingAirTriggerObstruction(player)) {
			removeTrackingAirTrigger(player.getUUID());
			return;
		}

		Interaction trigger = TRACKING_AIR_TRIGGERS.get(player.getUUID());
		if (trigger == null || !trigger.isAlive() || trigger.level() != player.level()) {
			trigger = new Interaction(EntityType.INTERACTION, player.level());
			trigger.setNoGravity(true);
			trigger.setSilent(true);
			trigger.setInvisible(true);
			trigger.setResponse(false);
			trigger.setWidth(TRACKING_TRIGGER_WIDTH);
			trigger.setHeight(TRACKING_TRIGGER_HEIGHT);
			player.level().addFreshEntity(trigger);
			TRACKING_AIR_TRIGGERS.put(player.getUUID(), trigger);
		}

		Vec3 pos = player.getEyePosition()
				.add(player.getLookAngle().normalize().scale(TRACKING_TRIGGER_HEAD_FORWARD_OFFSET))
				.subtract(0.0D, TRACKING_TRIGGER_HEIGHT * 0.5D, 0.0D);
		trigger.setInvisible(true);
		trigger.setPos(pos.x, pos.y, pos.z);
		trigger.setDeltaMovement(Vec3.ZERO);
		trigger.setYRot(player.getYRot());
		trigger.setXRot(player.getXRot());
		player.connection.send(ClientboundEntityPositionSyncPacket.of(trigger));
	}

	private static boolean shouldMaintainTrackingAirTrigger(ServerPlayer player) {
		return player != null
				&& player.isAlive()
				&& !player.isSpectator()
				&& isWearingCopperGoggles(player)
				&& switch (getMode(player)) {
					case TRACKING -> shouldHandleTrackingUse(player, InteractionHand.MAIN_HAND);
					case MAGNIFIER -> shouldHandleMagnifierUse(player);
					case ORE_SEARCH, NIGHT_VISION -> false;
				};
	}

	private static void syncNightVision(ServerPlayer player) {
		if (player == null) {
			return;
		}

		UUID playerId = player.getUUID();
		boolean shouldEnable = shouldApplyNightVision(player);
		MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);

		if (shouldEnable) {
			if (current == null || isManagedNightVision(current)) {
				if (current == null || !current.isInfiniteDuration()) {
					player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
				}
				LAST_NIGHT_VISION_STATES.put(playerId, true);
			} else {
				LAST_NIGHT_VISION_STATES.put(playerId, false);
			}
			return;
		}

		boolean hadManagedNightVision = LAST_NIGHT_VISION_STATES.remove(playerId) == Boolean.TRUE;
		if (hadManagedNightVision) {
			player.removeEffect(MobEffects.NIGHT_VISION);
		}
	}

	private static void clearManagedNightVision(ServerPlayer player) {
		if (player == null) {
			return;
		}
		if (LAST_NIGHT_VISION_STATES.remove(player.getUUID()) == Boolean.TRUE) {
			player.removeEffect(MobEffects.NIGHT_VISION);
		}
	}

	private static boolean shouldApplyNightVision(ServerPlayer player) {
		return player != null
				&& player.isAlive()
				&& !player.isSpectator()
				&& isWearingCopperGoggles(player)
				&& getMode(player) == GogglesMode.NIGHT_VISION;
	}

	private static boolean isManagedNightVision(MobEffectInstance effect) {
		return effect != null
				&& effect.getAmplifier() == 0
				&& !effect.isVisible();
	}

	private static boolean hasTrackingAirTriggerObstruction(ServerPlayer player) {
		double reach = Math.max(player.blockInteractionRange(), player.entityInteractionRange());
		HitResult hit = player.pick(reach, 1.0F, false);
		if (hit instanceof EntityHitResult entityHit && isTrackingAirTrigger(player, entityHit.getEntity())) {
			return false;
		}
		return hit.getType() != HitResult.Type.MISS;
	}

	private static boolean isTrackingAirTrigger(ServerPlayer player, Entity entity) {
		if (player == null || entity == null) {
			return false;
		}
		return TRACKING_AIR_TRIGGERS.get(player.getUUID()) == entity;
	}

	private static void removeTrackingAirTrigger(UUID playerId) {
		if (playerId == null) {
			return;
		}
		Interaction trigger = TRACKING_AIR_TRIGGERS.remove(playerId);
		if (trigger != null) {
			trigger.stopRiding();
			trigger.discard();
		}
	}

	private static void spawnWaveParticles(ServerPlayer player, ScanWave wave, long gameTime) {
		ServerLevel level = player.level();
		double radius = Math.min(wave.radiusBlocks(), (gameTime - wave.startTick()) * SCAN_WAVE_SPEED_BLOCKS_PER_TICK + 0.35D);
		if (radius <= 0.0D) {
			return;
		}
		List<ServerPlayer> viewers = collectWaveViewers(level, wave.center());
		if (viewers.isEmpty()) {
			return;
		}
		int points = Math.max(48, (int) Math.round(radius * 24.0D));
		double goldenAngle = Math.PI * (3.0D - Math.sqrt(5.0D));
		for (int i = 0; i < points; i++) {
			double t = points <= 1 ? 0.0D : (double) i / (points - 1);
			double yNorm = 1.0D - 2.0D * t;
			double horizontal = Math.sqrt(Math.max(0.0D, 1.0D - yNorm * yNorm));
			double angle = goldenAngle * i;
			double x = wave.center().x + Math.cos(angle) * horizontal * radius;
			double y = wave.center().y + yNorm * radius;
			double z = wave.center().z + Math.sin(angle) * horizontal * radius;
			for (ServerPlayer viewer : viewers) {
				level.sendParticles(viewer, SCAN_WAVE_PARTICLE, false, false, x, y, z, 1, 0.02D, 0.02D, 0.02D, 0.0D);
			}
		}
	}

	private static List<ServerPlayer> collectWaveViewers(ServerLevel level, Vec3 center) {
		if (level == null || center == null) {
			return List.of();
		}
		double maxDistanceSqr = SCAN_WAVE_VIEW_DISTANCE_BLOCKS * SCAN_WAVE_VIEW_DISTANCE_BLOCKS;
		List<ServerPlayer> viewers = new ArrayList<>();
		for (ServerPlayer viewer : level.players()) {
			if (viewer == null || !viewer.isAlive() || viewer.isSpectator() || !isWearingCopperGoggles(viewer)) {
				continue;
			}
			if (viewer.distanceToSqr(center.x, center.y, center.z) > maxDistanceSqr) {
				continue;
			}
			viewers.add(viewer);
		}
		return viewers;
	}

	private static List<BlockPos> findMatchingOres(ServerPlayer player, OreSearchMaterial material, Vec3 center, double radius) {
		ServerLevel level = player.level();
		int blockRadius = Math.max(1, (int) Math.ceil(radius));
		double radiusSqr = radius * radius;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		List<BlockPos> positions = new ArrayList<>();

		for (int x = -blockRadius; x <= blockRadius; x++) {
			for (int y = -blockRadius; y <= blockRadius; y++) {
				for (int z = -blockRadius; z <= blockRadius; z++) {
					cursor.set(center.x + x, center.y + y, center.z + z);
					if (!level.hasChunkAt(cursor)) {
						continue;
					}
					double dx = (cursor.getX() + 0.5D) - center.x;
					double dy = (cursor.getY() + 0.5D) - center.y;
					double dz = (cursor.getZ() + 0.5D) - center.z;
					if ((dx * dx) + (dy * dy) + (dz * dz) > radiusSqr) {
						continue;
					}
					BlockState state = level.getBlockState(cursor);
					if (!material.matchesOre(state.getBlock())) {
						continue;
					}
					positions.add(cursor.immutable());
					if (positions.size() >= ORE_SEARCH_MAX_HIGHLIGHTS) {
						return positions;
					}
				}
			}
		}
		return positions;
	}

	private static List<UUID> findTrackingTargets(ServerPlayer player, Vec3 center, double radius) {
		ServerLevel level = player.level();
		double radiusSqr = radius * radius;
		AABB box = new AABB(
				center.x - radius,
				center.y - radius,
				center.z - radius,
				center.x + radius,
				center.y + radius,
				center.z + radius
		);
		List<UUID> entityIds = new ArrayList<>();
		for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
			if (living == player) {
				continue;
			}
			if (living instanceof ServerPlayer trackedPlayer) {
				if (trackedPlayer.isSpectator()) {
					continue;
				}
			} else if (!(living instanceof Mob)) {
				continue;
			}

			Vec3 targetCenter = living.getBoundingBox().getCenter();
			double dx = targetCenter.x - center.x;
			double dy = targetCenter.y - center.y;
			double dz = targetCenter.z - center.z;
			if ((dx * dx) + (dy * dy) + (dz * dz) > radiusSqr) {
				continue;
			}

			entityIds.add(living.getUUID());
			if (entityIds.size() >= TRACKING_MAX_HIGHLIGHTS) {
				return entityIds;
			}
		}
		return entityIds;
	}

	private static void showOreSearchHighlights(ServerPlayer player, List<BlockPos> positions, long expireTick) {
		clearOreSearchHighlights(player);
		if (player == null || positions == null || positions.isEmpty()) {
			return;
		}
		List<Integer> ids = new ArrayList<>();
		for (BlockPos pos : positions) {
			Entity highlight = createHighlightEntity(player.level(), pos);
			if (highlight == null) {
				continue;
			}
			ids.add(highlight.getId());
			sendSpawnPackets(player, highlight);
		}
		if (!ids.isEmpty()) {
			int[] rawIds = ids.stream().mapToInt(Integer::intValue).toArray();
			ACTIVE_ORE_SEARCH_HIGHLIGHTS.put(player.getUUID(), new OreSearchHighlightSession(rawIds, expireTick));
		}
	}

	private static void showTrackingHighlights(ServerPlayer player, List<UUID> entityIds, long expireTick) {
		clearTrackingHighlights(player);
		if (player == null || entityIds == null || entityIds.isEmpty()) {
			return;
		}
		refreshTrackingHighlights(player, entityIds, player.level().getGameTime());
		ACTIVE_TRACKING_HIGHLIGHTS.put(
				player.getUUID(),
				new TrackingHighlightSession(List.copyOf(entityIds), expireTick, player.level().getGameTime() + TRACKING_REFRESH_INTERVAL_TICKS)
		);
	}

	private static Entity createHighlightEntity(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) {
			return null;
		}
		Display.ItemDisplay display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		if (display == null) {
			return null;
		}
		display.setPos(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
		display.setYRot(0.0F);
		display.setXRot(0.0F);
		display.setYHeadRot(0.0F);
		display.setYBodyRot(0.0F);
		display.setTransformation(new Transformation(
				new Vector3f(0.0F, 0.0F, 0.0F),
				new Quaternionf(),
				new Vector3f(ORE_HIGHLIGHT_SCALE, ORE_HIGHLIGHT_SCALE, ORE_HIGHLIGHT_SCALE),
				new Quaternionf()
		));
		display.setNoGravity(true);
		display.setInvulnerable(true);
		display.setSilent(true);
		display.setShadowRadius(0.0F);
		display.setShadowStrength(0.0F);
		display.setGlowingTag(true);
		display.setViewRange(HIGHLIGHT_VIEW_RANGE);
		display.setItemStack(createHighlightCarrierStack());
		display.setItemTransform(ItemDisplayContext.FIXED);
		display.setBillboardConstraints(Display.BillboardConstraints.FIXED);
		ItemDisplayHitboxHelper.clear(display);
		return display;
	}

	private static ItemStack createHighlightCarrierStack() {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, ORE_HIGHLIGHT_CARRIER_ITEM_MODEL_ID);
		return stack;
	}

	private static void clearScanVisuals(ServerPlayer player) {
		if (player == null) {
			return;
		}
		stopMagnifier(player);
		ACTIVE_SCAN_WAVES.remove(player.getUUID());
		clearOreSearchHighlights(player);
		clearTrackingHighlights(player);
	}

	private static void clearOreSearchHighlights(ServerPlayer player) {
		if (player == null) {
			return;
		}
		OreSearchHighlightSession session = ACTIVE_ORE_SEARCH_HIGHLIGHTS.remove(player.getUUID());
		if (session != null && session.entityIds().length > 0) {
			player.connection.send(new ClientboundRemoveEntitiesPacket(session.entityIds()));
		}
	}

	private static void clearTrackingHighlights(ServerPlayer player) {
		if (player == null) {
			return;
		}
		TrackingHighlightSession session = ACTIVE_TRACKING_HIGHLIGHTS.remove(player.getUUID());
		if (session == null) {
			return;
		}
		for (UUID entityId : session.entityIds()) {
			Entity entity = findEntity(player.level().getServer(), entityId);
			if (entity != null && entity.level() == player.level()) {
				sendTrackingGlowPacket(player, entity, false);
			}
		}
	}

	private static void refreshTrackingHighlights(ServerPlayer player, List<UUID> entityIds, long gameTime) {
		if (player == null || entityIds == null || entityIds.isEmpty()) {
			return;
		}
		for (UUID entityId : entityIds) {
			Entity entity = findEntity(player.level().getServer(), entityId);
			if (entity != null && entity.level() == player.level() && entity.isAlive()) {
				sendTrackingGlowPacket(player, entity, true);
			}
		}
		TrackingHighlightSession current = ACTIVE_TRACKING_HIGHLIGHTS.get(player.getUUID());
		if (current != null && current.entityIds().equals(entityIds)) {
			ACTIVE_TRACKING_HIGHLIGHTS.put(player.getUUID(), new TrackingHighlightSession(entityIds, current.expireTick(), gameTime + TRACKING_REFRESH_INTERVAL_TICKS));
		}
	}

	private static Entity findEntity(MinecraftServer server, UUID entityId) {
		if (server == null || entityId == null) {
			return null;
		}
		for (ServerLevel level : server.getAllLevels()) {
			Entity entity = level.getEntity(entityId);
			if (entity != null) {
				return entity;
			}
		}
		return null;
	}

	private static void sendTrackingGlowPacket(ServerPlayer viewer, Entity entity, boolean glowing) {
		if (viewer == null || entity == null) {
			return;
		}
		EntityDataAccessor<Byte> accessor = EntityTrackedDataAccessor.lg2$getDataSharedFlagsId();
		byte flags = entity.getEntityData().get(accessor);
		byte updatedFlags = glowing ? (byte) (flags | GLOWING_FLAG_MASK) : flags;
		viewer.connection.send(new ClientboundSetEntityDataPacket(
				entity.getId(),
				List.of(SynchedEntityData.DataValue.create(accessor, updatedFlags))
		));
	}

	@SuppressWarnings("unchecked")
	private static void sendSpawnPackets(ServerPlayer player, Entity entity) {
		ServerEntity tracker = new ServerEntity(
				(ServerLevel) entity.level(),
				entity,
				1,
				false,
				NOOP_SYNCHRONIZER
		);
		tracker.sendPairingData(player, packet -> player.connection.send((Packet<? super ClientGamePacketListener>) packet));
		List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
		if (values != null && !values.isEmpty()) {
			player.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), values));
		}
	}

	private static void displayRemainingCooldown(ServerPlayer player, long remainingTicks) {
		double remainingSeconds = remainingTicks / 20.0D;
		player.displayClientMessage(
				Component.literal(String.format(Locale.ROOT, "%.1fs", remainingSeconds))
						.withStyle(style -> style.withColor(ChatFormatting.RED).withItalic(false)),
				true
		);
	}

	private static GogglesMode getMode(ServerPlayer player) {
		if (player == null) {
			return GogglesMode.ORE_SEARCH;
		}
		return getMode(getEquippedGogglesStack(player));
	}

	private static GogglesMode getMode(ItemStack stack) {
		if (stack == null || stack.isEmpty() || stack.getItem() != ModItems.COPPER_GOGGLES) {
			return GogglesMode.ORE_SEARCH;
		}
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData == null || customData.isEmpty()) {
			return GogglesMode.ORE_SEARCH;
		}
		String serializedMode = customData.copyTag()
				.getCompoundOrEmpty(GOGGLES_META_TAG)
				.getStringOr(GOGGLES_MODE_TAG, GogglesMode.ORE_SEARCH.name());
		try {
			return GogglesMode.valueOf(serializedMode);
		} catch (IllegalArgumentException ignored) {
			return GogglesMode.ORE_SEARCH;
		}
	}

	private static void setMode(ItemStack stack, GogglesMode mode) {
		if (stack == null || stack.isEmpty() || stack.getItem() != ModItems.COPPER_GOGGLES || mode == null) {
			return;
		}
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			var gogglesTag = tag.getCompoundOrEmpty(GOGGLES_META_TAG);
			gogglesTag.putString(GOGGLES_MODE_TAG, mode.name());
			tag.put(GOGGLES_META_TAG, gogglesTag);
		});
	}

	private static ItemStack getEquippedGogglesStack(ServerPlayer player) {
		if (player == null || !player.isAlive()) {
			return ItemStack.EMPTY;
		}
		ItemStack headStack = player.getItemBySlot(EquipmentSlot.HEAD);
		return headStack.getItem() == ModItems.COPPER_GOGGLES ? headStack : ItemStack.EMPTY;
	}

	private static Optional<RaceAbilityConfig> getGogglesAbility(ServerPlayer player) {
		Optional<PlayerRaceConfig> raceOptional = ServerRaceSystem.getRace(player);
		if (raceOptional.isEmpty()) {
			return Optional.empty();
		}
		PlayerRaceConfig race = raceOptional.get();
		String raceId = race.id == null ? "" : race.id.trim().toLowerCase(Locale.ROOT);
		if (!COPPER_MAN_RACE_ID.equals(raceId)) {
			return Optional.empty();
		}
		if (!ServerRaceSystem.hasUnlockedAbility(player, RaceAbilitySlot.SHNYAGA)) {
			return Optional.empty();
		}
		if (race.shnyaga == null || !race.shnyaga.enabled) {
			return Optional.empty();
		}
		return Optional.of(race.shnyaga);
	}

	private static RaceAbilityConfig getEffectiveGogglesAbility(ServerPlayer player) {
		return getGogglesAbility(player).orElseGet(() -> RaceAbilityConfig.defaults(RaceAbilitySlot.SHNYAGA));
	}

	private static double getScanCooldownSeconds(RaceAbilityConfig ability) {
		if (ability == null) {
			return DEFAULT_SCAN_COOLDOWN_SECONDS;
		}
		return ability.copperGogglesScanCooldownSeconds > 0.0D
				? ability.copperGogglesScanCooldownSeconds
				: DEFAULT_SCAN_COOLDOWN_SECONDS;
	}

	private static double getOreSearchRadiusBlocks(RaceAbilityConfig ability) {
		if (ability == null) {
			return DEFAULT_ORE_SEARCH_RADIUS_BLOCKS;
		}
		return ability.copperGogglesOreSearchRadiusBlocks > 0.0D
				? ability.copperGogglesOreSearchRadiusBlocks
				: DEFAULT_ORE_SEARCH_RADIUS_BLOCKS;
	}

	private static double getOreSearchHighlightSeconds(RaceAbilityConfig ability) {
		if (ability == null) {
			return DEFAULT_ORE_SEARCH_HIGHLIGHT_SECONDS;
		}
		return ability.copperGogglesOreSearchHighlightSeconds > 0.0D
				? ability.copperGogglesOreSearchHighlightSeconds
				: DEFAULT_ORE_SEARCH_HIGHLIGHT_SECONDS;
	}

	private static double getTrackingRadiusBlocks(RaceAbilityConfig ability) {
		if (ability == null) {
			return DEFAULT_TRACKING_RADIUS_BLOCKS;
		}
		return ability.copperGogglesTrackingRadiusBlocks > 0.0D
				? ability.copperGogglesTrackingRadiusBlocks
				: DEFAULT_TRACKING_RADIUS_BLOCKS;
	}

	private static double getTrackingHighlightSeconds(RaceAbilityConfig ability) {
		if (ability == null) {
			return DEFAULT_TRACKING_HIGHLIGHT_SECONDS;
		}
		return ability.copperGogglesTrackingHighlightSeconds > 0.0D
				? ability.copperGogglesTrackingHighlightSeconds
				: DEFAULT_TRACKING_HIGHLIGHT_SECONDS;
	}

	private static ItemStack resolveOreSearchSampleStack(ServerPlayer player) {
		if (player == null) {
			return ItemStack.EMPTY;
		}
		ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
		if (resolveOreSearchMaterial(mainHand) != null) {
			return mainHand;
		}
		ItemStack offHand = player.getItemInHand(InteractionHand.OFF_HAND);
		if (resolveOreSearchMaterial(offHand) != null) {
			return offHand;
		}
		return ItemStack.EMPTY;
	}

	private static OreSearchMaterial resolveOreSearchMaterial(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		for (OreSearchMaterial material : OreSearchMaterial.values()) {
			if (material.matchesSample(stack)) {
				return material;
			}
		}
		return null;
	}

	private static boolean isWearingCopperGoggles(ServerPlayer player) {
		return !getEquippedGogglesStack(player).isEmpty();
	}

	private static void syncViewer(ServerPlayer viewer) {
		MinecraftServer server = viewer == null ? null : viewer.level().getServer();
		if (viewer == null || server == null) {
			return;
		}

		for (ServerPlayer wearer : AccountAuthSystem.authenticatedPlayers(server)) {
			if (wearer == null) {
				continue;
			}
			syncWearerToViewer(wearer, viewer, shouldSpoofVisual(wearer));
		}
	}

	private static void syncWearerToAllViewers(ServerPlayer wearer, boolean spoofVisual) {
		MinecraftServer server = wearer == null ? null : wearer.level().getServer();
		if (wearer == null || server == null) {
			return;
		}

		for (ServerPlayer viewer : AccountAuthSystem.authenticatedPlayers(server)) {
			syncWearerToViewer(wearer, viewer, spoofVisual);
		}
	}

	private static void syncWearerToViewer(ServerPlayer wearer, ServerPlayer viewer, boolean spoofVisual) {
		if (wearer == null || viewer == null) {
			return;
		}

		ItemStack actualHead = wearer.getItemBySlot(EquipmentSlot.HEAD).copy();
		ItemStack stack = actualHead.copy();
		if (spoofVisual && !stack.isEmpty()) {
			if (PolymerResourcePackUtils.hasMainPack(viewer)) {
				stack.set(DataComponents.ITEM_MODEL, HEAD_MODEL_ID);
				preserveVisibleName(stack, actualHead);
			}
		}
		viewer.connection.send(new ClientboundSetEquipmentPacket(
				wearer.getId(),
				List.of(com.mojang.datafixers.util.Pair.of(EquipmentSlot.HEAD, stack.copy()))
		));
	}

	private static void preserveVisibleName(ItemStack visualStack, ItemStack originalStack) {
		if (visualStack == null || visualStack.isEmpty() || originalStack == null || originalStack.isEmpty()) {
			return;
		}

		Component customName = originalStack.get(DataComponents.CUSTOM_NAME);
		if (customName != null) {
			visualStack.set(DataComponents.CUSTOM_NAME, customName.copy().withStyle(style -> style.withItalic(false)));
			return;
		}

		visualStack.set(
				DataComponents.CUSTOM_NAME,
				originalStack.getItem().getName(originalStack).copy().withStyle(style -> style.withItalic(false))
		);
	}

	private static boolean shouldSpoofVisual(ServerPlayer player) {
		return isWearingCopperGoggles(player);
	}

	private static boolean shouldShowScreenOverlay(ServerPlayer player) {
		return shouldSpoofVisual(player) && PolymerResourcePackUtils.hasMainPack(player);
	}

	private static String localizeGogglesModeMessage(ServerPlayer player, GogglesMode mode) {
		String locale = normalizeGogglesLocale(player);
		String modeName = localizeGogglesModeName(locale, mode);
		return switch (locale) {
			case "rpr" -> "Режимъ стеколъ: " + modeName;
			case "uk", "uk_ua" -> "Режим окулярів: " + modeName;
			case "ja", "ja_jp" -> "ゴーグルモード: " + modeName;
			case "ru", "ru_ru" -> "Режим очков: " + modeName;
			default -> "Goggles mode: " + modeName;
		};
	}

	private static String localizeGogglesNotEquipped(ServerPlayer player) {
		return switch (normalizeGogglesLocale(player)) {
			case "rpr" -> "Стекла не прилажены къ носу";
			case "uk", "uk_ua" -> "Окуляри не вдягнені";
			case "ja", "ja_jp" -> "ゴーグルを装備していません";
			case "ru", "ru_ru" -> "Очки не экипированы";
			default -> "Goggles are not equipped";
		};
	}

	private static String localizeGogglesModeName(String locale, GogglesMode mode) {
		return switch (locale) {
			case "rpr" -> switch (mode) {
				case ORE_SEARCH -> "Рудоискательный чинъ";
				case TRACKING -> "Сыскное выслеживаніе";
				case MAGNIFIER -> "Окуляръ чрезмѣрнаго взора";
				case NIGHT_VISION -> "Нощное всевидѣніе";
			};
			case "uk", "uk_ua" -> switch (mode) {
				case ORE_SEARCH -> "Пошук руд";
				case TRACKING -> "Відстеження";
				case MAGNIFIER -> "Лупа";
				case NIGHT_VISION -> "Нічне бачення";
			};
			case "ja", "ja_jp" -> switch (mode) {
				case ORE_SEARCH -> "鉱石探知";
				case TRACKING -> "追跡";
				case MAGNIFIER -> "ズーム";
				case NIGHT_VISION -> "暗視";
			};
			case "ru", "ru_ru" -> switch (mode) {
				case ORE_SEARCH -> "Поиск руд";
				case TRACKING -> "Отслеживание";
				case MAGNIFIER -> "Лупа";
				case NIGHT_VISION -> "Ночное зрение";
			};
			default -> switch (mode) {
				case ORE_SEARCH -> "Ore Search";
				case TRACKING -> "Tracking";
				case MAGNIFIER -> "Zoom";
				case NIGHT_VISION -> "Night Vision";
			};
		};
	}

	private static String normalizeGogglesLocale(ServerPlayer player) {
		if (player == null || player.clientInformation() == null || player.clientInformation().language() == null) {
			return "en_us";
		}
		return player.clientInformation().language().toLowerCase(Locale.ROOT);
	}

	private static Component buildScreenOverlayTitle(ServerPlayer player) {
		long gameTime = player == null || player.level() == null ? 0L : player.level().getGameTime();
		int frameIndex = (int) ((gameTime / SCREEN_OVERLAY_FRAME_TICKS) % SCREEN_OVERLAY_GLYPH_FRAMES.length);
		Component glyph = Component.literal(SCREEN_OVERLAY_GLYPH_FRAMES[frameIndex])
				.withStyle(style -> style
						.withColor(SCREEN_OVERLAY_TITLE_COLOR)
						.withItalic(false)
						.withFont(SCREEN_OVERLAY_FONT)
						.withShadowColor(0x00000000));
		return Component.empty()
				.append(Component.literal(buildHorizontalAdvance(SCREEN_OVERLAY_X_OFFSET)).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)))
				.append(Component.literal(TITLE_OVERLAY_SHIFT).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)))
				.append(Component.literal(buildHorizontalAdvance(SCREEN_OVERLAY_X_CORRECTION)).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)))
				.append(glyph)
				.append(Component.literal(buildHorizontalAdvance(-SCREEN_OVERLAY_X_CORRECTION)).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)))
				.append(Component.literal(TITLE_OVERLAY_RESET).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)));
	}

	private static void syncScreenOverlay(ServerPlayer player, boolean enabled) {
		if (player == null || player.connection == null) {
			return;
		}
		Component priorityTitle = ServerRaceSystem.getKilkaAttackFlashTitleOverride(player);
		if (priorityTitle == null) {
			priorityTitle = PuroSanStockSystem.getAimWarningTitleOverride(player);
		}
		if (priorityTitle != null) {
			player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 16, 0));
			player.connection.send(new ClientboundSetTitleTextPacket(priorityTitle));
			return;
		}
		player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 16, 0));
		player.connection.send(new ClientboundSetTitleTextPacket(enabled ? buildScreenOverlayTitle(player) : Component.empty()));
	}

	private static String buildHorizontalAdvance(int pixels) {
		if (pixels == 0) {
			return "";
		}

		int remaining = pixels;
		StringBuilder result = new StringBuilder();
		int[] values = remaining > 0
				? new int[]{64, 32, 16, 8, 4, 2, 1}
				: new int[]{-64, -32, -16, -8, -4, -2, -1};
		String[] glyphs = remaining > 0
				? new String[]{"\ue94d", "\ue94c", "\ue94b", "\ue94a", "\ue949", "\ue948", "\ue947"}
				: new String[]{"\ue940", "\ue941", "\ue942", "\ue943", "\ue944", "\ue945", "\ue946"};

		for (int index = 0; index < values.length; index++) {
			int step = values[index];
			while ((remaining > 0 && remaining >= step) || (remaining < 0 && remaining <= step)) {
				result.append(glyphs[index]);
				remaining -= step;
			}
		}
		return result.toString();
	}

	private static Collection<RecipeHolder<?>> collectRecipeHolders(MinecraftServer server) {
		if (server == null) {
			return List.of();
		}

		RecipeManager recipeManager = server.getRecipeManager();
		List<RecipeHolder<?>> holders = new ArrayList<>();
		for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
			if (holder != null && isCopperGogglesRecipe(holder.id())) {
				holders.add(holder);
			}
		}
		return holders;
	}

	private static boolean isCopperGogglesRecipe(ResourceKey<Recipe<?>> recipeKey) {
		return recipeKey != null && RECIPE_ID.equals(recipeKey.identifier());
	}

	private enum GogglesMode {
		ORE_SEARCH("message.lg2.copper_goggles.mode.ore_search"),
		TRACKING("message.lg2.copper_goggles.mode.tracking"),
		MAGNIFIER("message.lg2.copper_goggles.mode.magnifier"),
		NIGHT_VISION("message.lg2.copper_goggles.mode.night_vision");

		private final String translationKey;

		GogglesMode(String translationKey) {
			this.translationKey = translationKey;
		}

		private String translationKey() {
			return this.translationKey;
		}

		private GogglesMode next() {
			return switch (this) {
				case ORE_SEARCH -> TRACKING;
				case TRACKING -> MAGNIFIER;
				case MAGNIFIER -> NIGHT_VISION;
				case NIGHT_VISION -> ORE_SEARCH;
			};
		}
	}

	private record ScanWave(Vec3 center, double radiusBlocks, long startTick, long endTick) {
	}

	private record OreSearchHighlightSession(int[] entityIds, long expireTick) {
	}

	private record TrackingHighlightSession(List<UUID> entityIds, long expireTick, long nextRefreshTick) {
	}

	private record MagnifierSession(int selectedSlot) {
	}

	private enum OreSearchMaterial {
		IRON(
				Set.of(
						Items.IRON_INGOT,
						Items.IRON_NUGGET,
						Items.RAW_IRON,
						Items.IRON_BLOCK,
						Items.RAW_IRON_BLOCK,
						Blocks.IRON_ORE.asItem(),
						Blocks.DEEPSLATE_IRON_ORE.asItem()
				),
				Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE)
		),
		GOLD(
				Set.of(
						Items.GOLD_INGOT,
						Items.GOLD_NUGGET,
						Items.RAW_GOLD,
						Items.GOLD_BLOCK,
						Items.RAW_GOLD_BLOCK,
						Blocks.GOLD_ORE.asItem(),
						Blocks.DEEPSLATE_GOLD_ORE.asItem(),
						Blocks.NETHER_GOLD_ORE.asItem()
				),
				Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE)
		),
		DIAMOND(
				Set.of(Items.DIAMOND, Items.DIAMOND_BLOCK, Blocks.DIAMOND_ORE.asItem(), Blocks.DEEPSLATE_DIAMOND_ORE.asItem()),
				Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)
		),
		LAPIS(
				Set.of(Items.LAPIS_LAZULI, Items.LAPIS_BLOCK, Blocks.LAPIS_ORE.asItem(), Blocks.DEEPSLATE_LAPIS_ORE.asItem()),
				Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE)
		),
		COPPER(
				Set.of(
						Items.COPPER_INGOT,
						Items.RAW_COPPER,
						Items.COPPER_BLOCK,
						Items.RAW_COPPER_BLOCK,
						Blocks.COPPER_ORE.asItem(),
						Blocks.DEEPSLATE_COPPER_ORE.asItem()
				),
				Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE)
		),
		REDSTONE(
				Set.of(Items.REDSTONE, Items.REDSTONE_BLOCK, Blocks.REDSTONE_ORE.asItem(), Blocks.DEEPSLATE_REDSTONE_ORE.asItem()),
				Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE)
		),
		COAL(
				Set.of(Items.COAL, Items.COAL_BLOCK, Blocks.COAL_ORE.asItem(), Blocks.DEEPSLATE_COAL_ORE.asItem()),
				Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE)
		),
		EMERALD(
				Set.of(Items.EMERALD, Items.EMERALD_BLOCK, Blocks.EMERALD_ORE.asItem(), Blocks.DEEPSLATE_EMERALD_ORE.asItem()),
				Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE)
		),
		BITCOIN(
				Set.of(ModItems.BITCOIN, ModBlocks.BITCOIN_ORE.asItem(), ModBlocks.DEEPSLATE_BITCOIN_ORE.asItem()),
				Set.of(ModBlocks.BITCOIN_ORE, ModBlocks.DEEPSLATE_BITCOIN_ORE)
		),
		ANCIENT_DEBRIS(
				Set.of(Items.ANCIENT_DEBRIS, Items.NETHERITE_SCRAP, Items.NETHERITE_INGOT, Items.NETHERITE_BLOCK),
				Set.of(Blocks.ANCIENT_DEBRIS)
		),
		QUARTZ(
				Set.of(Items.QUARTZ, Items.QUARTZ_BLOCK, Blocks.NETHER_QUARTZ_ORE.asItem()),
				Set.of(Blocks.NETHER_QUARTZ_ORE)
		);

		private final Set<net.minecraft.world.item.Item> sampleItems;
		private final Set<Block> oreBlocks;

		OreSearchMaterial(Set<net.minecraft.world.item.Item> sampleItems, Set<Block> oreBlocks) {
			this.sampleItems = sampleItems;
			this.oreBlocks = oreBlocks;
		}

		private boolean matchesSample(ItemStack stack) {
			return stack != null && !stack.isEmpty() && sampleItems.contains(stack.getItem());
		}

		private boolean matchesOre(Block block) {
			return block != null && oreBlocks.contains(block);
		}
	}

	private static final ServerEntity.Synchronizer NOOP_SYNCHRONIZER = new ServerEntity.Synchronizer() {
		@Override
		public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
		}

		@Override
		public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
		}

		@Override
		public void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet, java.util.function.Predicate<ServerPlayer> predicate) {
		}
	};
}


