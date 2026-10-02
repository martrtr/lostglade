package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lostglade.Lg2;
import com.lostglade.mixin.LivingEntityTrackedDataAccessor;
import com.lostglade.config.RaceConfig.PlayerRaceConfig;
import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import com.lostglade.config.RaceConfig.RaceAbilitySlot;
import com.mojang.authlib.properties.Property;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import it.unimi.dsi.fastutil.Pair;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.lionarius.skinrestorer.SkinRestorer;
import net.lionarius.skinrestorer.mineskin.MineskinService;
import net.lionarius.skinrestorer.skin.SkinService;
import net.lionarius.skinrestorer.skin.SkinStorage;
import net.lionarius.skinrestorer.skin.SkinValue;
import net.lionarius.skinrestorer.skin.SkinVariant;
import net.lionarius.skinrestorer.skin.provider.SkinProvider;
import net.lionarius.skinrestorer.skin.provider.SkinProviderContext;
import net.lionarius.skinrestorer.util.PlayerUtils;
import net.lionarius.skinrestorer.util.Result;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class CopperManRepulsorSystem {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
	private static final String COPPER_MAN_RACE_ID = "copper_man";
	private static final int DEFAULT_MAX_CHARGES = 25;
	private static final int DEFAULT_COPPER_INGOT_RESTORE = 5;
	private static final double DEFAULT_NATURAL_LIGHTNING_RESTORE_CHANCE = 0.10D;
	private static final double DEFAULT_ARMOR_IGNORE_FRACTION = 0.50D;
	private static final int DEFAULT_NATURAL_LIGHTNING_RESTORE = 10;
	private static final long AUTO_SHOT_INTERVAL_TICKS = 2L;
	private static final long SINGLE_SHOT_INTERVAL_TICKS = 40L;
	private static final long HUD_UPDATE_INTERVAL_TICKS = 5L;
	private static final long NATURAL_LIGHTNING_RECHARGE_DEDUP_TICKS = 200L;
	private static final double AIR_TRIGGER_RAY_RANGE = 4.5D;
	private static final double AIR_TRIGGER_HEAD_FORWARD_OFFSET = 0.22D;
	private static final float AIR_TRIGGER_WIDTH = 1.8F;
	private static final float AIR_TRIGGER_HEIGHT = 1.8F;
	private static final double AUTO_RANGE = 16.0D;
	private static final double SINGLE_RANGE = 32.0D;
	private static final float AUTO_DAMAGE = 1.0F;
	private static final float SINGLE_DAMAGE = 4.0F;
	private static final int LASER_PARTICLE_COLOR = 0xFF2A2A;
	private static final Identifier REPULSOR_ARMOR_PENETRATION_MODIFIER_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "repulsor_armor_penetration");
	private static final float LASER_PARTICLE_SCALE = 0.75F;
	private static final Identifier REPULSOR_SHOOT_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "repulsor_shoot");
	private static final Identifier REPULSOR_SHOOT_INVISIBLE_MODEL_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "gui/button/invisible");
	private static final Holder<SoundEvent> REPULSOR_SHOOT_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(REPULSOR_SHOOT_SOUND_ID));
	private static final float REPULSOR_SHOOT_SOUND_VOLUME = 0.85F;
	private static final float REPULSOR_SHOOT_SOUND_PITCH = 1.0F;
	private static final float REPULSOR_SHOOT_FALLBACK_PITCH = 1.45F;
private static final byte USING_ITEM_FLAG_MASK = 0x01;
private static final byte USING_OFFHAND_FLAG_MASK = 0x02;
private static final long REPULSOR_SINGLE_SHOOT_ANIMATION_TICKS = 8L;
private static final Consumable REPULSOR_SHOOT_USE_ANIMATION = Consumable.builder()
			.consumeSeconds(3600.0F)
			.animation(ItemUseAnimation.TOOT_HORN)
			.sound(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.EMPTY))
			.hasConsumeParticles(false)
			.build();
	private static final int REPULSOR_MODE_PREFIX_COLOR = 0xC97B3B;
	private static final String REPULSOR_SHIFT_GLYPH = "\uef80";
	private static final String REPULSOR_SLOT_TO_AMMO_SHIFT_GLYPH = "\uef81";
	private static final String REPULSOR_AMMO_RIGHT_SHIFT_GLYPH = "\uef82";
	private static final String REPULSOR_AMMO_POST_COMPENSATION_GLYPH = "\uef7e";
	private static final String REPULSOR_SLOT_ICON_CENTER_BASE_SHIFT_GLYPH = "\uef85";
	private static final String REPULSOR_SLOT_ICON_CENTER_PER_CHAR_SHIFT_GLYPH = "\uef86";
	private static final String REPULSOR_SLOT_ICON_ONLY_POST_SHIFT_GLYPH = "\uef87";
	private static final String REPULSOR_AMMO_EXTRA_LEFT_DIGIT_SHIFT_GLYPH = "\uef88";
	private static final String REPULSOR_ICON_AMMO_EXTRA_LEFT_DIGIT_SHIFT_GLYPH = "\uef89";
	private static final String REPULSOR_SINGLE_DIGIT_ICON_LEFT_SHIFT_GLYPH = "\uef89";
	private static final String REPULSOR_SINGLE_DIGIT_ICON_POST_COMPENSATION_GLYPH = "\uef7f";
	private static final String REPULSOR_SINGLE_DIGIT_AMMO_LEFT_SHIFT_GLYPH = "\uef89";
	private static final String REPULSOR_SINGLE_DIGIT_AMMO_POST_COMPENSATION_GLYPH = "\uef7f";
	private static final String REPULSOR_SLOT_ICON_GLYPH = "\uef83";
	private static final String REPULSOR_SLOT_ICON_NO_AMMO_GLYPH = "\uef84";
	private static final int REPULSOR_ICON_AMMO_BASE_CHAR_COUNT = 4;
	private static final FontDescription REPULSOR_SHIFT_FONT = new FontDescription.Resource(
			Objects.requireNonNull(Identifier.tryParse("lg2:repulsor_ammo_shift"))
	);
	private static final FontDescription REPULSOR_SLOT_ICON_FONT = new FontDescription.Resource(
			Objects.requireNonNull(Identifier.tryParse("lg2:repulsor_slot_icon"))
	);
	private static final FontDescription REPULSOR_AMMO_FONT = new FontDescription.Resource(
			Objects.requireNonNull(Identifier.tryParse("lg2:repulsor_ammo_small"))
	);
	private static final Map<UUID, RepulsorState> STATES = new ConcurrentHashMap<>();
	private static final Map<UUID, RepulsorMode> SAVED_MODES = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> NEXT_MODE_SWITCH_TICKS = new ConcurrentHashMap<>();
private static final Map<UUID, Long> ACTIVE_SHOOT_ANIMATIONS = new ConcurrentHashMap<>();
	private static final Map<String, Long> PROCESSED_NATURAL_LIGHTNING_HITS = new ConcurrentHashMap<>();

	private CopperManRepulsorSystem() {
	}

	public static void register() {
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return net.minecraft.world.InteractionResult.PASS;
			}
			return onUseItem(serverPlayer, hand);
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (!alive) {
				onPlayerDeath(newPlayer);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				AccountAuthSystem.runWhenAuthenticated(handler.player, () -> server.execute(() -> {
					RepulsorState state = state(handler.player);
					state.mode = SAVED_MODES.getOrDefault(handler.player.getUUID(), state.mode);
					state.hudDirty = true;
					updateHud(handler.player, state, true);
				}))
		);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			RepulsorState state = STATES.get(handler.player.getUUID());
			if (state != null) {
				SAVED_MODES.put(handler.player.getUUID(), state.mode);
				removeAirTriggerEntity(state);
				clearHud(handler.player, state, true);
				state.lastAutomaticInputTick = Long.MIN_VALUE;
				state.lastSingleInputTick = Long.MIN_VALUE;
				state.useHeld = false;
				state.hudDirty = true;
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(CopperManRepulsorSystem::tickServer);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			STATES.values().forEach(CopperManRepulsorSystem::removeAirTriggerEntity);
			STATES.clear();
			SAVED_MODES.clear();
			NEXT_MODE_SWITCH_TICKS.clear();
			ACTIVE_SHOOT_ANIMATIONS.clear();
			PROCESSED_NATURAL_LIGHTNING_HITS.clear();
		});
	}

	public static void registerLateInteractions() {
		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return net.minecraft.world.InteractionResult.PASS;
			}
			return onUseEntity(serverPlayer, hand, entity, hitResult == null ? null : hitResult.getLocation());
		});
	}

	public static void resetAllAbilityCooldowns(MinecraftServer server) {
		NEXT_MODE_SWITCH_TICKS.clear();
	}

	public static int toggleMode(ServerPlayer player) {
		if (player == null) {
			return 0;
		}

		long cooldownTicks = getModeSwitchCooldownTicks(player);
		long remainingCooldownTicks = getRemainingOnlineCooldownTicks(player.getUUID());
		if (remainingCooldownTicks > 0L) {
			double remaining = remainingCooldownTicks / 20.0D;
			player.displayClientMessage(
					Component.literal(String.format(Locale.ROOT, "%.1fs", remaining))
							.withStyle(style -> style.withColor(ChatFormatting.RED).withItalic(false)),
					true
			);
			return 0;
		}

		RepulsorState state = state(player);
		state.mode = state.mode == RepulsorMode.AUTOMATIC ? RepulsorMode.SINGLE : RepulsorMode.AUTOMATIC;
		SAVED_MODES.put(player.getUUID(), state.mode);
		state.hudDirty = true;
		startModeSwitchCooldown(player.getUUID(), cooldownTicks);
		player.displayClientMessage(
				buildModeChangedMessage(player, state.mode),
				true
		);
		return 1;
	}

	public static boolean handleUseInteraction(ServerPlayer player, InteractionHand hand) {
		if (!canUseRepulsor(player)) {
			return false;
		}

		RepulsorState state = state(player);
		if (state.charges <= 0) {
			state.hudDirty = true;
			return false;
		}

		long nowTick = player.level().getGameTime();
		state.hudDirty = true;

		if (state.mode == RepulsorMode.AUTOMATIC) {
			state.lastAutomaticInputTick = nowTick;
			state.useHeld = true;
			startShootAnimation(player, nowTick);
			if (nowTick >= state.nextShotTick) {
				tryFire(player, state, nowTick);
			}
			return true;
		}

		if (state.lastSingleInputTick != nowTick) {
			state.lastSingleInputTick = nowTick;
			if (tryFire(player, state, nowTick)) {
				startShootAnimation(player, nowTick);
			}
		}
		return true;
	}

	public static net.minecraft.world.InteractionResult onUseItem(ServerPlayer player, InteractionHand hand) {
		return handleUseInteraction(player, hand)
				? net.minecraft.world.InteractionResult.SUCCESS
				: net.minecraft.world.InteractionResult.PASS;
	}

	public static boolean isAirTriggerEntity(ServerPlayer player, Entity entity) {
		if (player == null || entity == null) {
			return false;
		}
		RepulsorState state = STATES.get(player.getUUID());
		return state != null && state.airTriggerEntity == entity;
	}

	public static net.minecraft.world.InteractionResult onUseEntity(ServerPlayer player, InteractionHand hand, Entity target, Vec3 location) {
		if (player == null || target == null) {
			return net.minecraft.world.InteractionResult.PASS;
		}
		if (!canUseRepulsor(player)) {
			return net.minecraft.world.InteractionResult.PASS;
		}

		RepulsorState state = state(player);
		if (state.charges <= 0) {
			state.hudDirty = true;
			return net.minecraft.world.InteractionResult.PASS;
		}

		if (isAirTriggerEntity(player, target)) {
			return onUseItem(player, hand);
		}

		var result = location != null ? target.interactAt(player, location, hand) : net.minecraft.world.InteractionResult.PASS;
		if (result == net.minecraft.world.InteractionResult.PASS) {
			result = player.interactOn(target, hand);
		}
		if (result != net.minecraft.world.InteractionResult.PASS) {
			return result;
		}

		handleEntityInteractionPass(player, hand);
		return net.minecraft.world.InteractionResult.SUCCESS;
	}

	public static void handleEntityInteractionPass(ServerPlayer player, InteractionHand hand) {
		if (!canUseRepulsor(player)) {
			return;
		}

		RepulsorState state = state(player);
		if (state.charges <= 0) {
			state.hudDirty = true;
			return;
		}

		long nowTick = player.level().getGameTime();
		state.hudDirty = true;
		if (state.mode == RepulsorMode.AUTOMATIC) {
			state.lastAutomaticInputTick = nowTick;
			state.useHeld = true;
			startShootAnimation(player, nowTick);
			if (nowTick >= state.nextShotTick) {
				tryFire(player, state, nowTick);
			}
			return;
		}

		if (state.lastSingleInputTick != nowTick) {
			state.lastSingleInputTick = nowTick;
			if (tryFire(player, state, nowTick)) {
				startShootAnimation(player, nowTick);
			}
		}
	}

	public static void handleMovePacket(ServerPlayer player) {
		if (player == null) {
			return;
		}

		RepulsorState existingState = STATES.get(player.getUUID());
		if (existingState != null) {
			syncAirTriggerEntity(player, existingState);
			return;
		}

		if (player.isAlive()
				&& !player.isSpectator()
				&& player.getInventory().getSelectedSlot() == 0
				&& player.getMainHandItem().isEmpty()
				&& isAttackUnlocked(player)) {
			syncAirTriggerEntity(player, state(player));
		}
	}

	public static boolean shouldBlockMagnifier(ServerPlayer player) {
		return player != null
				&& player.isAlive()
				&& !player.isSpectator()
				&& isCopperMan(player)
				&& isAttackUnlocked(player)
				&& player.getInventory().getSelectedSlot() == 0
				&& Math.max(0, state(player).charges) > 0;
	}

	public static void onCopperIngotConsumed(ServerPlayer player) {
		if (player == null || !isCopperMan(player)) {
			return;
		}
		restoreCharges(player, state(player), getCopperIngotChargeRestore(player));
	}

	public static void onNaturalLightningStrike(ServerPlayer player, Entity lightningEntity) {
		if (player == null || lightningEntity == null || !isCopperMan(player)) {
			return;
		}
		if (!(lightningEntity instanceof net.minecraft.world.entity.LightningBolt lightningBolt) || lightningBolt.getCause() != null) {
			return;
		}
		long nowTick = player.level().getGameTime();
		String dedupKey = player.getUUID() + ":" + lightningBolt.getUUID();
		Long processedUntil = PROCESSED_NATURAL_LIGHTNING_HITS.get(dedupKey);
		if (processedUntil != null && processedUntil >= nowTick) {
			return;
		}
		PROCESSED_NATURAL_LIGHTNING_HITS.put(dedupKey, nowTick + NATURAL_LIGHTNING_RECHARGE_DEDUP_TICKS);
		if (player.getRandom().nextDouble() > getNaturalLightningChargeChance(player)) {
			return;
		}
		restoreCharges(player, state(player), getNaturalLightningChargeRestore(player));
	}

	public static void onPlayerDeath(ServerPlayer player) {
		resetChargesAfterDeath(player);
	}

	private static void tickServer(MinecraftServer server) {
		long nowTick = server.overworld().getGameTime();
		tickModeSwitchCooldowns(server);
		tickShootAnimations(server, nowTick);
		if (nowTick % 40L == 0L) {
			PROCESSED_NATURAL_LIGHTNING_HITS.entrySet().removeIf(entry -> entry.getValue() < nowTick);
		}
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			RepulsorState state = state(player);
			syncAirTriggerEntity(player, state);
			if (state.mode == RepulsorMode.AUTOMATIC
					&& state.useHeld
					&& canUseRepulsor(player)
					&& state.charges > 0
					&& nowTick >= state.nextShotTick) {
				tryFire(player, state, nowTick);
			}
			boolean hudHeartbeat = (nowTick + player.getId()) % HUD_UPDATE_INTERVAL_TICKS == 0L;
			if (state.hudDirty || !state.hudVisible || hudHeartbeat) {
				updateHud(player, state, hudHeartbeat);
			}
		}
	}

	private static RepulsorState state(ServerPlayer player) {
		RepulsorState state = STATES.computeIfAbsent(player.getUUID(), ignored -> new RepulsorState());
		state.mode = SAVED_MODES.getOrDefault(player.getUUID(), state.mode);
		return state;
	}

	private static boolean canUseRepulsor(ServerPlayer player) {
		return player != null
				&& player.isAlive()
				&& !player.isSpectator()
				&& isCopperMan(player)
				&& isAttackUnlocked(player)
				&& player.getInventory().getSelectedSlot() == 0
				&& player.getMainHandItem().isEmpty();
	}

	private static boolean tryFire(ServerPlayer player, RepulsorState state, long nowTick) {
		if (state.charges <= 0 || nowTick < state.nextShotTick) {
			state.hudDirty = true;
			return false;
		}

		fireLaser(player, state.mode);
		state.charges = Math.max(0, state.charges - 1);
		state.hudDirty = true;
		state.nextShotTick = nowTick + state.mode.intervalTicks;
		return true;
	}

	private static void fireLaser(ServerPlayer player, RepulsorMode mode) {
		ServerLevel level = player.level();
		Vec3 start = player.getEyePosition();
		Vec3 end = start.add(player.getLookAngle().scale(mode.range));
		BlockHitResult blockHit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 blockEnd = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();

		EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
				level,
				player,
				start,
				blockEnd,
				player.getBoundingBox().expandTowards(player.getLookAngle().scale(mode.range)).inflate(0.75D),
				entity -> canLaserHit(player, entity),
				0.25F
		);

		Vec3 particleEnd = blockEnd;
		if (entityHit != null && entityHit.getEntity() != null) {
			particleEnd = entityHit.getLocation();
			damageEntity(level, player, entityHit.getEntity(), mode.damage);
		}

		spawnLaserParticles(level, start, particleEnd);
		playShootSound(level, player, start);
	}

	private static void tickShootAnimations(MinecraftServer server, long nowTick) {
		if (server == null || ACTIVE_SHOOT_ANIMATIONS.isEmpty()) {
			return;
		}
		for (Map.Entry<UUID, Long> entry : new ArrayList<>(ACTIVE_SHOOT_ANIMATIONS.entrySet())) {
			UUID playerId = entry.getKey();
			long endTick = entry.getValue() == null ? Long.MIN_VALUE : entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				ACTIVE_SHOOT_ANIMATIONS.remove(playerId);
				continue;
			}
			RepulsorState state = STATES.get(playerId);
			if (nowTick >= endTick
					|| state == null
					|| !state.useHeld
					|| state.charges <= 0
					|| !canDisplayShootAnimationCarrier(player)) {
				if (state != null) {
					state.useHeld = false;
				}
				sendShootAnimationVisual(player, false);
				ACTIVE_SHOOT_ANIMATIONS.remove(playerId, endTick);
			}
		}
	}

	private static void startShootAnimation(ServerPlayer player, long nowTick) {
		if (!canDisplayShootAnimationCarrier(player)) {
			return;
		}
		RepulsorState state = state(player);
		state.useHeld = true;
		long endTick = state.mode == RepulsorMode.AUTOMATIC
				? Long.MAX_VALUE
				: nowTick + REPULSOR_SINGLE_SHOOT_ANIMATION_TICKS;
		if (ACTIVE_SHOOT_ANIMATIONS.put(player.getUUID(), endTick) == null) {
			sendShootAnimationVisual(player, true);
		}
	}

	private static boolean canDisplayShootAnimationCarrier(ServerPlayer player) {
		return canUseRepulsor(player)
				&& player.getInventory().getSelectedSlot() == 0
				&& player.getMainHandItem().isEmpty()
				&& player.containerMenu == player.inventoryMenu;
	}

	public static void handleReleaseUsePacket(ServerPlayer player) {
		if (player == null) {
			return;
		}
		RepulsorState state = STATES.get(player.getUUID());
		if (state != null) {
			state.useHeld = false;
		}
		if (ACTIVE_SHOOT_ANIMATIONS.remove(player.getUUID()) != null) {
			sendShootAnimationVisual(player, false);
		}
	}

	private static void sendShootAnimationVisual(ServerPlayer player, boolean active) {
		if (player == null) {
			return;
		}
		ItemStack visualStack = active ? buildRepulsorShootAnimationStack(player) : player.getMainHandItem().copy();
		ClientboundSetEquipmentPacket equipmentPacket = new ClientboundSetEquipmentPacket(
				player.getId(),
				List.of(com.mojang.datafixers.util.Pair.of(EquipmentSlot.MAINHAND, visualStack))
		);
		EntityDataAccessor<Byte> accessor = LivingEntityTrackedDataAccessor.lg2$getDataLivingEntityFlags();
		byte flags = player.getEntityData().get(accessor);
		byte updatedFlags = active
				? (byte) ((flags | USING_ITEM_FLAG_MASK) & ~USING_OFFHAND_FLAG_MASK)
				: (byte) (flags & ~USING_ITEM_FLAG_MASK & ~USING_OFFHAND_FLAG_MASK);
		ClientboundSetEntityDataPacket flagsPacket = new ClientboundSetEntityDataPacket(
				player.getId(),
				List.of(SynchedEntityData.DataValue.create(accessor, updatedFlags))
		);
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}
		for (ServerPlayer viewer : level.players()) {
			viewer.connection.send(equipmentPacket);
			viewer.connection.send(flagsPacket);
		}
		if (!active) {
			sendActualRepulsorSlot(player);
		}
	}

	private static ItemStack buildRepulsorShootAnimationStack(ServerPlayer player) {
		ItemStack carrier = new ItemStack(Items.PAPER);
		carrier.set(DataComponents.ITEM_MODEL, REPULSOR_SHOOT_INVISIBLE_MODEL_ID);
		carrier.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
		carrier.set(DataComponents.CONSUMABLE, REPULSOR_SHOOT_USE_ANIMATION);
		carrier.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, new java.util.LinkedHashSet<>()));
		return carrier;
	}

	private static void sendActualRepulsorSlot(ServerPlayer player) {
		if (player == null) {
			return;
		}
		AbstractContainerMenu menu = player.inventoryMenu;
		int menuSlot = -1;
		for (int index = 0; index < menu.slots.size(); index++) {
			Slot slot = menu.getSlot(index);
			if (slot.container == player.getInventory() && slot.getContainerSlot() == 0) {
				menuSlot = index;
				break;
			}
		}
		if (menuSlot < 0) {
			return;
		}
		player.connection.send(new ClientboundContainerSetSlotPacket(
				menu.containerId,
				menu.incrementStateId(),
				menuSlot,
				player.getInventory().getItem(0).copy()
		));
	}

	private static void playShootSound(ServerLevel level, ServerPlayer shooter, Vec3 origin) {
		if (level == null || shooter == null || origin == null) {
			return;
		}

		long seed = level.getRandom().nextLong();
		double range = REPULSOR_SHOOT_SOUND_VOLUME > 1.0F ? 16.0D * REPULSOR_SHOOT_SOUND_VOLUME : 16.0D;
		double rangeSqr = range * range;
		for (ServerPlayer viewer : level.players()) {
			if (viewer.distanceToSqr(origin) > rangeSqr) {
				continue;
			}

			boolean hasPack = PolymerResourcePackUtils.hasMainPack(viewer);
			Holder<SoundEvent> sound = hasPack
					? REPULSOR_SHOOT_SOUND
					: BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BLAZE_SHOOT);
			float pitch = hasPack ? REPULSOR_SHOOT_SOUND_PITCH : REPULSOR_SHOOT_FALLBACK_PITCH;
			viewer.connection.send(new ClientboundSoundPacket(
					sound,
					SoundSource.PLAYERS,
					origin.x,
					origin.y,
					origin.z,
					REPULSOR_SHOOT_SOUND_VOLUME,
					pitch,
					seed
			));
		}
	}

	private static boolean canLaserHit(ServerPlayer player, Entity entity) {
		if (entity == null || entity == player || !entity.isAlive() || !entity.isPickable() || entity instanceof Interaction) {
			return false;
		}
		var playerTeam = player.getTeam();
		var entityTeam = entity.getTeam();
		if (entity instanceof ServerPlayer otherPlayer && player.isAlliedTo(otherPlayer)) {
			return false;
		}
		return playerTeam == null || entityTeam == null || !Objects.equals(entityTeam, playerTeam);
	}

	private static void damageEntity(ServerLevel level, ServerPlayer player, Entity entity, float damage) {
		Arrow arrow = new Arrow(level, player, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
		arrow.setPos(player.getEyePosition());
		Vec3 previousDelta = entity.getDeltaMovement();
		boolean previousHurtMarked = entity.hurtMarked;
		AttributeInstance armor = entity instanceof LivingEntity living ? living.getAttribute(Attributes.ARMOR) : null;
		try {
			if (armor != null) {
				armor.removeModifier(REPULSOR_ARMOR_PENETRATION_MODIFIER_ID);
				armor.addTransientModifier(new AttributeModifier(
						REPULSOR_ARMOR_PENETRATION_MODIFIER_ID,
						-getArmorIgnoreFraction(player),
						AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
				));
			}
			entity.invulnerableTime = 0;
			entity.hurtServer(level, level.damageSources().arrow(arrow, player), damage);
		} finally {
			if (armor != null) {
				armor.removeModifier(REPULSOR_ARMOR_PENETRATION_MODIFIER_ID);
			}
			entity.invulnerableTime = 0;
			entity.setDeltaMovement(previousDelta);
			entity.hurtMarked = previousHurtMarked;
			arrow.discard();
		}
	}

	private static void spawnLaserParticles(ServerLevel level, Vec3 start, Vec3 end) {
		Vec3 delta = end.subtract(start);
		double length = delta.length();
		if (length <= 1.0E-6D) {
			level.sendParticles(new DustParticleOptions(LASER_PARTICLE_COLOR, LASER_PARTICLE_SCALE), start.x, start.y, start.z, 1, 0.0, 0.0, 0.0, 0.0);
			return;
		}

		int particles = Math.max(1, (int) Math.ceil(length * 3.0D));
		Vec3 step = delta.scale(1.0D / particles);
		Vec3 current = start;
		for (int i = 0; i <= particles; i++) {
			level.sendParticles(new DustParticleOptions(LASER_PARTICLE_COLOR, LASER_PARTICLE_SCALE), current.x, current.y, current.z, 1, 0.0, 0.0, 0.0, 0.0);
			current = current.add(step);
		}
	}

	private static void syncAirTriggerEntity(ServerPlayer player, RepulsorState state) {
		if (!shouldMaintainAirTrigger(player, state)) {
			removeAirTriggerEntity(state);
			return;
		}
		if (hasAirTriggerObstruction(player, state)) {
			removeAirTriggerEntity(state);
			return;
		}

		Interaction trigger = state.airTriggerEntity;
		if (trigger == null || !trigger.isAlive() || trigger.level() != player.level()) {
			trigger = new Interaction(net.minecraft.world.entity.EntityType.INTERACTION, player.level());
			trigger.setNoGravity(true);
			trigger.setSilent(true);
			trigger.setInvisible(true);
			trigger.setResponse(false);
			trigger.setWidth(AIR_TRIGGER_WIDTH);
			trigger.setHeight(AIR_TRIGGER_HEIGHT);
			player.level().addFreshEntity(trigger);
			state.airTriggerEntity = trigger;
		}

		Vec3 pos = player.getEyePosition()
				.add(player.getLookAngle().normalize().scale(AIR_TRIGGER_HEAD_FORWARD_OFFSET))
				.subtract(0.0D, AIR_TRIGGER_HEIGHT * 0.5D, 0.0D);
		trigger.setInvisible(true);
		trigger.setPos(pos.x, pos.y, pos.z);
		trigger.setDeltaMovement(Vec3.ZERO);
		trigger.setYRot(player.getYRot());
		trigger.setXRot(player.getXRot());
		player.connection.send(ClientboundEntityPositionSyncPacket.of(trigger));
	}

	private static boolean shouldMaintainAirTrigger(ServerPlayer player, RepulsorState state) {
		return player != null
				&& state != null
				&& player.isAlive()
				&& !player.isSpectator()
				&& state.charges > 0
				&& isAttackUnlocked(player)
				&& player.getInventory().getSelectedSlot() == 0
				&& player.getMainHandItem().isEmpty();
	}

	private static boolean hasAirTriggerObstruction(ServerPlayer player, RepulsorState state) {
		double reach = Math.max(player.blockInteractionRange(), player.entityInteractionRange());
		HitResult hit = player.pick(reach, 1.0F, false);
		if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() == state.airTriggerEntity) {
			return false;
		}
		return hit.getType() != HitResult.Type.MISS;
	}

	private static void removeAirTriggerEntity(RepulsorState state) {
		if (state.airTriggerEntity != null) {
			state.airTriggerEntity.stopRiding();
			state.airTriggerEntity.discard();
			state.airTriggerEntity = null;
		}
	}

	private static void restoreCharges(ServerPlayer player, RepulsorState state, int amount) {
		if (player == null || state == null || amount <= 0) {
			return;
		}
		int maxCharges = getMaxCharges(player);
		int restored = Math.min(maxCharges, Math.max(0, state.charges) + amount);
		if (restored != state.charges) {
			state.charges = restored;
			state.hudDirty = true;
		}
	}

	private static void resetChargesAfterDeath(ServerPlayer player) {
		if (player == null) {
			return;
		}
		RepulsorState state = state(player);
		state.charges = 0;
		state.nextShotTick = 0L;
		state.lastAutomaticInputTick = Long.MIN_VALUE;
		state.lastSingleInputTick = Long.MIN_VALUE;
		state.useHeld = false;
		if (ACTIVE_SHOOT_ANIMATIONS.remove(player.getUUID()) != null) {
			sendShootAnimationVisual(player, false);
		}
		state.hudDirty = true;
	}

	private static void updateHud(ServerPlayer player, RepulsorState state, boolean force) {
		boolean hasPack = PolymerResourcePackUtils.hasMainPack(player);
		boolean showAttackSlotIcon = hasPack && shouldShowAttackSlotIcon(player);
		boolean showAmmo = shouldShowAmmoHud(player);
		if (!showAttackSlotIcon && !showAmmo) {
			clearHud(player, state, force);
			return;
		}

		String hudText = showAmmo ? Math.max(0, state.charges) + "/" + getMaxCharges(player) : "";
		if (!force && !state.hudDirty && state.hudVisible && Objects.equals(state.lastHudText, hudText) && state.lastHudPack == hasPack) {
			return;
		}

		int hudColor = CopperManGogglesSystem.getOverlayTintColor(player);
		int iconColor = CopperManGogglesSystem.getOverlayAccentColor(player);
		player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 40, 0));
		player.connection.send(new ClientboundSetTitleTextPacket(CopperManGogglesSystem.getScreenOverlayTitle(player)));
		Component subtitle = hasPack
				? buildPackHudComponent(hudText, showAttackSlotIcon, showAmmo, hudColor, iconColor)
				: Component.literal(hudText).withStyle(style -> style.withColor(ChatFormatting.WHITE).withItalic(false));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		state.hudVisible = true;
		state.lastHudText = hudText;
		state.lastHudPack = hasPack;
		state.hudDirty = false;
	}

	private static Component buildPackHudComponent(String text, boolean showAttackSlotIcon, boolean showAmmo, int hudColor, int iconColor) {
		MutableComponent component = Component.empty();
		int slashIndex = text.indexOf('/');
		int extraLeftDigits = Math.max(0, slashIndex - 1);
		boolean singleLeftDigitAmmo = slashIndex == 1;
		if (showAttackSlotIcon && showAmmo) {
			component.append(Component.literal((singleLeftDigitAmmo ? REPULSOR_SINGLE_DIGIT_ICON_LEFT_SHIFT_GLYPH : "")
					+ REPULSOR_ICON_AMMO_EXTRA_LEFT_DIGIT_SHIFT_GLYPH.repeat(extraLeftDigits)
					+ REPULSOR_SLOT_ICON_CENTER_BASE_SHIFT_GLYPH
					+ REPULSOR_SLOT_ICON_CENTER_PER_CHAR_SHIFT_GLYPH.repeat(REPULSOR_ICON_AMMO_BASE_CHAR_COUNT))
					.withStyle(style -> style.withColor(hudColor).withItalic(false).withFont(REPULSOR_SHIFT_FONT)));
		}
		if (showAttackSlotIcon) {
			component.append(Component.literal(showAmmo ? REPULSOR_SLOT_ICON_GLYPH : REPULSOR_SLOT_ICON_NO_AMMO_GLYPH)
					.withStyle(style -> style.withColor(iconColor).withItalic(false).withFont(REPULSOR_SLOT_ICON_FONT).withShadowColor(0x00000000)));
			if (!showAmmo) {
				component.append(Component.literal(REPULSOR_SLOT_ICON_ONLY_POST_SHIFT_GLYPH)
						.withStyle(style -> style.withColor(hudColor).withItalic(false).withFont(REPULSOR_SHIFT_FONT)));
			}
		}
		if (showAmmo) {
			if (showAttackSlotIcon) {
				if (singleLeftDigitAmmo) {
					component.append(Component.literal(REPULSOR_SINGLE_DIGIT_ICON_POST_COMPENSATION_GLYPH)
							.withStyle(style -> style.withColor(hudColor).withItalic(false).withFont(REPULSOR_SHIFT_FONT)));
				}
				component.append(Component.literal(REPULSOR_SLOT_TO_AMMO_SHIFT_GLYPH)
						.withStyle(style -> style.withColor(hudColor).withItalic(false).withFont(REPULSOR_SHIFT_FONT)));
			} else {
				component.append(Component.literal(REPULSOR_SHIFT_GLYPH)
						.withStyle(style -> style.withColor(hudColor).withItalic(false).withFont(REPULSOR_SHIFT_FONT)));
			}
			component.append(Component.literal(REPULSOR_AMMO_RIGHT_SHIFT_GLYPH)
					.withStyle(style -> style.withColor(hudColor).withItalic(false).withFont(REPULSOR_SHIFT_FONT)));
			if (extraLeftDigits > 0) {
				component.append(Component.literal(REPULSOR_AMMO_EXTRA_LEFT_DIGIT_SHIFT_GLYPH.repeat(extraLeftDigits))
						.withStyle(style -> style.withColor(hudColor).withItalic(false).withFont(REPULSOR_SHIFT_FONT)));
			}
			if (singleLeftDigitAmmo) {
				component.append(Component.literal(REPULSOR_SINGLE_DIGIT_AMMO_LEFT_SHIFT_GLYPH)
						.withStyle(style -> style.withColor(hudColor).withItalic(false).withFont(REPULSOR_SHIFT_FONT)));
			}
			component.append(Component.literal(text)
					.withStyle(style -> style.withColor(hudColor).withItalic(false).withFont(REPULSOR_AMMO_FONT)));
			if (singleLeftDigitAmmo) {
				component.append(Component.literal(REPULSOR_SINGLE_DIGIT_AMMO_POST_COMPENSATION_GLYPH)
						.withStyle(style -> style.withColor(hudColor).withItalic(false).withFont(REPULSOR_SHIFT_FONT)));
			}
			component.append(Component.literal(REPULSOR_AMMO_POST_COMPENSATION_GLYPH)
					.withStyle(style -> style.withColor(hudColor).withItalic(false).withFont(REPULSOR_SHIFT_FONT)));
		}
		return component;
	}

	private static void clearHud(ServerPlayer player, RepulsorState state, boolean force) {
		if (!force && !state.hudVisible) {
			return;
		}
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
		state.hudVisible = false;
		state.lastHudText = "";
		state.hudDirty = false;
	}

	private static boolean shouldShowAttackSlotIcon(ServerPlayer player) {
		return player != null
				&& player.isAlive()
				&& !player.isSpectator()
				&& isCopperMan(player)
				&& isAttackUnlocked(player);
	}

	private static boolean shouldShowAmmoHud(ServerPlayer player) {
		if (player == null || !player.isAlive() || player.isSpectator() || !isCopperMan(player) || !isAttackUnlocked(player)) {
			return false;
		}

		ItemStack mainHand = player.getMainHandItem();
		ItemStack offhand = player.getOffhandItem();
		ItemStack selected = player.getInventory().getItem(player.getInventory().getSelectedSlot());
		ItemStack using = player.getUseItem();
		if (mainHand.is(Items.COPPER_INGOT)
				|| offhand.is(Items.COPPER_INGOT)
				|| selected.is(Items.COPPER_INGOT)
				|| using.is(Items.COPPER_INGOT)) {
			return true;
		}

		return player.getInventory().getSelectedSlot() == 0 && (mainHand.isEmpty() || selected.isEmpty());
	}

	private static Component buildModeChangedMessage(ServerPlayer player, RepulsorMode mode) {
		return Component.literal(localizeModePrefix(player))
				.withStyle(style -> style.withColor(REPULSOR_MODE_PREFIX_COLOR).withItalic(false))
				.append(
						Component.literal(localizeModeName(player, mode))
								.withStyle(style -> style.withColor(REPULSOR_MODE_PREFIX_COLOR).withItalic(false))
				);
	}

	private static String localizeModePrefix(ServerPlayer player) {
		return switch (locale(player)) {
			case "rpr" -> "Репульсоръ: ";
			case "uk", "uk_ua" -> "Режим роботи репульсора: ";
			case "ja", "ja_jp" -> "リパルサー: ";
			case "ru", "ru_ru" -> "Режим репульсора: ";
			default -> "Repulsor mode: ";
		};
	}

	private static String localizeModeChanged(ServerPlayer player, RepulsorMode mode) {
		return switch (locale(player)) {
			case "rpr" -> "Репульсоръ: " + localizeModeName(player, mode);
			case "uk", "uk_ua" -> "Режим роботи репульсора: " + localizeModeName(player, mode);
			case "ja", "ja_jp" -> "リパルサー: " + localizeModeName(player, mode);
			case "ru", "ru_ru" -> "Режим репульсора: " + localizeModeName(player, mode);
			default -> "Repulsor mode: " + localizeModeName(player, mode);
		};
	}

	private static String localizeModeName(ServerPlayer player, RepulsorMode mode) {
		return switch (locale(player)) {
			case "rpr" -> mode == RepulsorMode.AUTOMATIC ? "Дѣйствіе безъ понужденія" : "Разовый пускъ";
			case "uk", "uk_ua" -> mode == RepulsorMode.AUTOMATIC ? "Автоматичний" : "Одиночний";
			case "ja", "ja_jp" -> mode == RepulsorMode.AUTOMATIC ? "オート" : "単発";
			case "ru", "ru_ru" -> mode == RepulsorMode.AUTOMATIC ? "Автоматический" : "Одиночный";
			default -> mode == RepulsorMode.AUTOMATIC ? "Automatic" : "Single";
		};
	}

	private static String locale(ServerPlayer player) {
		if (player == null || player.clientInformation() == null || player.clientInformation().language() == null) {
			return "en_us";
		}
		return player.clientInformation().language().toLowerCase(Locale.ROOT);
	}

	private static boolean isCopperMan(ServerPlayer player) {
		Optional<PlayerRaceConfig> raceOptional = ServerRaceSystem.getRace(player);
		return raceOptional.isPresent() && COPPER_MAN_RACE_ID.equals(sanitizePath(raceOptional.get().id));
	}

	private static boolean isAttackUnlocked(ServerPlayer player) {
		return player != null
				&& isCopperMan(player)
				&& ServerRaceSystem.hasUnlockedAbility(player, RaceAbilitySlot.ATTACK);
	}

	private static int getMaxCharges(ServerPlayer player) {
		RaceAbilityConfig ability = ServerRaceSystem.getAbility(player, RaceAbilitySlot.ATTACK).orElse(null);
		return ability != null && ability.repulsorMaxCharges > 0 ? ability.repulsorMaxCharges : DEFAULT_MAX_CHARGES;
	}

	private static int getCopperIngotChargeRestore(ServerPlayer player) {
		RaceAbilityConfig ability = ServerRaceSystem.getAbility(player, RaceAbilitySlot.ATTACK).orElse(null);
		return ability != null && ability.repulsorCopperIngotChargeRestore > 0 ? ability.repulsorCopperIngotChargeRestore : DEFAULT_COPPER_INGOT_RESTORE;
	}

	private static double getNaturalLightningChargeChance(ServerPlayer player) {
		RaceAbilityConfig ability = ServerRaceSystem.getAbility(player, RaceAbilitySlot.ATTACK).orElse(null);
		if (ability != null && ability.repulsorNaturalLightningChargeChance > 0.0D) {
			return ability.repulsorNaturalLightningChargeChance;
		}

		Optional<PlayerRaceConfig> raceOptional = ServerRaceSystem.getRace(player);
		if (raceOptional.isPresent() && raceOptional.get().stock != null && raceOptional.get().stock.repulsorNaturalLightningChargeChance > 0.0D) {
			return raceOptional.get().stock.repulsorNaturalLightningChargeChance;
		}

		return DEFAULT_NATURAL_LIGHTNING_RESTORE_CHANCE;
	}

	private static double getArmorIgnoreFraction(ServerPlayer player) {
		RaceAbilityConfig ability = ServerRaceSystem.getAbility(player, RaceAbilitySlot.ATTACK).orElse(null);
		if (ability != null && ability.repulsorArmorIgnoreFraction > 0.0D) {
			return Math.clamp(ability.repulsorArmorIgnoreFraction, 0.0D, 1.0D);
		}
		return DEFAULT_ARMOR_IGNORE_FRACTION;
	}

	private static int getNaturalLightningChargeRestore(ServerPlayer player) {
		RaceAbilityConfig ability = ServerRaceSystem.getAbility(player, RaceAbilitySlot.ATTACK).orElse(null);
		return ability != null && ability.repulsorNaturalLightningChargeRestore > 0
				? ability.repulsorNaturalLightningChargeRestore
				: DEFAULT_NATURAL_LIGHTNING_RESTORE;
	}

	private static long getModeSwitchCooldownTicks(ServerPlayer player) {
		RaceAbilityConfig ability = ServerRaceSystem.getAbility(player, RaceAbilitySlot.ATTACK).orElse(null);
		if (ability == null || ability.cooldownSeconds <= 0.0D) {
			return 0L;
		}
		return Math.max(0L, Math.round(ability.cooldownSeconds * 20.0D));
	}

	private static long getRemainingOnlineCooldownTicks(UUID playerId) {
		if (playerId == null) {
			return 0L;
		}
		return Math.max(0L, NEXT_MODE_SWITCH_TICKS.getOrDefault(playerId, 0L));
	}

	private static void startModeSwitchCooldown(UUID playerId, long cooldownTicks) {
		if (playerId == null) {
			return;
		}
		if (cooldownTicks <= 0L) {
			NEXT_MODE_SWITCH_TICKS.remove(playerId);
			return;
		}
		NEXT_MODE_SWITCH_TICKS.put(playerId, cooldownTicks);
	}

	private static void tickModeSwitchCooldowns(MinecraftServer server) {
		if (server == null || NEXT_MODE_SWITCH_TICKS.isEmpty()) {
			return;
		}

		for (Map.Entry<UUID, Long> entry : NEXT_MODE_SWITCH_TICKS.entrySet()) {
			UUID playerId = entry.getKey();
			Long remainingTicks = entry.getValue();
			if (remainingTicks == null || remainingTicks <= 0L) {
				NEXT_MODE_SWITCH_TICKS.remove(playerId, remainingTicks);
				continue;
			}
			if (server.getPlayerList().getPlayer(playerId) == null) {
				continue;
			}

			long nextValue = remainingTicks - 1L;
			if (nextValue <= 0L) {
				NEXT_MODE_SWITCH_TICKS.remove(playerId, remainingTicks);
			} else {
				NEXT_MODE_SWITCH_TICKS.replace(playerId, remainingTicks, nextValue);
			}
		}
	}

	private static String sanitizePath(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private enum RepulsorMode {
		AUTOMATIC(AUTO_RANGE, AUTO_DAMAGE, AUTO_SHOT_INTERVAL_TICKS),
		SINGLE(SINGLE_RANGE, SINGLE_DAMAGE, SINGLE_SHOT_INTERVAL_TICKS);

		private final double range;
		private final float damage;
		private final long intervalTicks;

		RepulsorMode(double range, float damage, long intervalTicks) {
			this.range = range;
			this.damage = damage;
			this.intervalTicks = intervalTicks;
		}
	}

	private static final class RepulsorState {
		private RepulsorMode mode = RepulsorMode.AUTOMATIC;
		private int charges = -1;
		private long nextShotTick = 0L;
		private long lastAutomaticInputTick = Long.MIN_VALUE;
		private long lastSingleInputTick = Long.MIN_VALUE;
		private boolean hudDirty = true;
		private boolean hudVisible = false;
		private String lastHudText = "";
		private boolean lastHudPack = false;
		private boolean useHeld = false;
		private Interaction airTriggerEntity;
	}
}
