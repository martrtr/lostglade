package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.config.RaceConfig.PlayerRaceConfig;
import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import com.lostglade.config.RaceConfig.RaceAbilitySlot;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PuroSanStockSystem {
	private static final String RACE_ID = "puro_san";
	private static final double DEFAULT_TRAIL_DURATION_SECONDS = 3.0D;
	private static final double DEFAULT_TRAIL_DAMAGE = 1.0D;
	private static final double DEFAULT_BASE_SPEED_BONUS_RATIO = 0.10D;
	private static final double DEFAULT_AIM_WARNING_RADIUS_BLOCKS = 64.0D;
	private static final double DEFAULT_MELEE_DAMAGE_PENALTY_RATIO = 0.25D;
	private static final double DEFAULT_MELEE_VULNERABILITY_RATIO = 0.25D;
	private static final double DEFAULT_MAX_HEALTH_HEARTS = 8.0D;
	private static final double POINTED_DRIPSTONE_FALL_DAMAGE_RATIO = 0.5D;
	private static final int AIM_WARNING_CYCLE_TICKS = 40;
	private static final int AIM_WARNING_VISIBLE_TICKS = 20;
	private static final int TRAIL_DAMAGE_INTERVAL_TICKS = 20;
	private static final int TRAIL_PARTICLE_INTERVAL_TICKS = 2;
	private static final double TRAIL_POINT_SPACING = 0.28D;
	private static final double TRAIL_VISUAL_RADIUS = 0.32D;
	private static final double TRAIL_DAMAGE_RADIUS = TRAIL_VISUAL_RADIUS;
	private static final Identifier SPEED_MODIFIER_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "puro_san_stock_speed");
	private static final Identifier MAX_HEALTH_MODIFIER_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "puro_san_stock_max_health");
	private static final FontDescription AIM_WARNING_FONT = new FontDescription.Resource(
			Objects.requireNonNull(Identifier.tryParse("lg2:puro_san_aim_overlay"))
	);
	private static final String AIM_WARNING_GLYPH = "\uef50" + buildHorizontalAdvance(-1) + "\uef51" + buildHorizontalAdvance(-1) + "\uef52";
	private static final String TITLE_OVERLAY_SHIFT = "\ue905";
	private static final String TITLE_OVERLAY_RESET = "\ue940\ue940\ue941\ue943";
	private static final int TITLE_X_OFFSET = -120;
	private static final int TITLE_X_CORRECTION = -20;
	private static final DustParticleOptions TRAIL_DARK_FIRE = new DustParticleOptions(0xD93212, 0.48F);
	private static final DustParticleOptions TRAIL_BRIGHT_FIRE = new DustParticleOptions(0xFF8A18, 0.34F);
	private static final Map<UUID, Vec3> LAST_TRAIL_POSITIONS = new HashMap<>();
	private static final List<TrailPoint> TRAIL_POINTS = new ArrayList<>();
	private static final Map<UUID, AimWarningState> AIM_WARNING_STATES = new HashMap<>();

	private PuroSanStockSystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(PuroSanStockSystem::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> cleanupPlayer(handler.player));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> clearAll(server));
	}

	public static float modifyDamage(ServerLevel level, LivingEntity victim, DamageSource source, float damage) {
		if (level == null || victim == null || source == null || damage <= 0.0F) {
			return damage;
		}
		float modified = damage;
		if (victim instanceof ServerPlayer puro && getStockAbility(puro) != null) {
			if (source.is(DamageTypes.FALL)) {
				return 0.0F;
			}
			if (source.is(DamageTypes.STALAGMITE)) {
				modified *= (float) POINTED_DRIPSTONE_FALL_DAMAGE_RATIO;
			}
			if (isMeleeWeaponAttack(source)) {
				RaceAbilityConfig ability = getStockAbility(puro);
				double vulnerability = nonNegativeOrDefault(
						ability.puroSanStockMeleeVulnerabilityRatio,
						DEFAULT_MELEE_VULNERABILITY_RATIO
				);
				modified *= (float) (1.0D + vulnerability);
			}
		}

		if (source.getEntity() instanceof ServerPlayer attacker
				&& attacker != victim
				&& getStockAbility(attacker) != null
				&& isMeleeWeaponAttack(source)) {
			RaceAbilityConfig ability = getStockAbility(attacker);
			double penalty = clampRatio(nonNegativeOrDefault(
					ability.puroSanStockMeleeDamagePenaltyRatio,
					DEFAULT_MELEE_DAMAGE_PENALTY_RATIO
			));
			modified *= (float) (1.0D - penalty);
		}
		return Math.max(0.0F, modified);
	}

	public static boolean shouldCancelFallDamage(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof ServerPlayer player)
				|| source == null
				|| !source.is(DamageTypes.FALL)
				|| getStockAbility(player) == null) {
			return false;
		}
		player.resetFallDistance();
		ServerRaceSystem.playPuroSanSafeLandingEffects(player);
		return true;
	}
	public static boolean shouldBlockBlindness(LivingEntity entity, MobEffectInstance effect) {
		return entity instanceof ServerPlayer player
				&& effect != null
				&& effect.is(MobEffects.BLINDNESS)
				&& getStockAbility(player) != null
				&& !com.lostglade.server.glitch.BlackoutGlitch.isApplyingEffectsTo(player);
	}

	public static Component getAimWarningTitleOverride(ServerPlayer player) {
		AimWarningState state = player == null ? null : AIM_WARNING_STATES.get(player.getUUID());
		return state != null && state.overlayVisible ? buildAimWarningTitle() : null;
	}

	private static void tick(MinecraftServer server) {
		if (server == null) {
			return;
		}
		long nowTick = server.overworld().getGameTime();
		Set<UUID> online = new HashSet<>();
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			online.add(player.getUUID());
			RaceAbilityConfig ability = getStockAbility(player);
			if (ability == null) {
				clearPlayerState(player);
				continue;
			}
			syncStockAttributes(player, ability);
			tickTrailEmitter(player, ability, nowTick);
			tickAimWarning(player, ability, nowTick);
		}
		LAST_TRAIL_POSITIONS.keySet().removeIf(id -> !online.contains(id));
		AIM_WARNING_STATES.entrySet().removeIf(entry -> {
			if (online.contains(entry.getKey())) {
				return false;
			}
			return true;
		});
		tickTrailPoints(server, nowTick);
	}

	private static void syncStockAttributes(ServerPlayer player, RaceAbilityConfig ability) {
		double speedBonus = nonNegativeOrDefault(
				ability.puroSanStockBaseSpeedBonusRatio,
				DEFAULT_BASE_SPEED_BONUS_RATIO
		);
		syncAttributeModifier(
				player.getAttribute(Attributes.MOVEMENT_SPEED),
				SPEED_MODIFIER_ID,
				speedBonus,
				AttributeModifier.Operation.ADD_MULTIPLIED_BASE
		);
		double maxHealthHearts = positiveOrDefault(
				ability.puroSanStockMaxHealthHearts,
				DEFAULT_MAX_HEALTH_HEARTS
		);
		syncAttributeModifier(
				player.getAttribute(Attributes.MAX_HEALTH),
				MAX_HEALTH_MODIFIER_ID,
				maxHealthHearts * 2.0D - 20.0D,
				AttributeModifier.Operation.ADD_VALUE
		);
		if (player.getHealth() > player.getMaxHealth()) {
			player.setHealth(player.getMaxHealth());
		}
	}

	private static void tickTrailEmitter(ServerPlayer player, RaceAbilityConfig ability, long nowTick) {
		Vec3 current = player.position();
		Vec3 previous = LAST_TRAIL_POSITIONS.put(player.getUUID(), current);
		if (previous == null || !player.onGround() || player.isPassenger()) {
			return;
		}
		double dx = current.x - previous.x;
		double dz = current.z - previous.z;
		double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
		if (horizontalDistance < 0.035D || horizontalDistance > 4.0D) {
			return;
		}
		long durationTicks = Math.max(1L, Math.round(positiveOrDefault(
				ability.puroSanStockTrailDurationSeconds,
				DEFAULT_TRAIL_DURATION_SECONDS
		) * 20.0D));
		int samples = Math.max(1, (int) Math.ceil(horizontalDistance / TRAIL_POINT_SPACING));
		ServerLevel level = (ServerLevel) player.level();
		for (int index = 1; index <= samples; index++) {
			double t = index / (double) samples;
			double x = previous.x + dx * t;
			double z = previous.z + dz * t;
			double y = player.getBoundingBox().minY + 0.035D;
			Vec3 point = new Vec3(x, y, z);
			if (hasActiveTrailAt(player.getUUID(), level.dimension(), point, nowTick)) {
			continue;
			}
			double trailDamage = nonNegativeOrDefault(ability.puroSanStockTrailDamage, DEFAULT_TRAIL_DAMAGE);
			TRAIL_POINTS.add(new TrailPoint(player.getUUID(), level.dimension(), point, nowTick + durationTicks, trailDamage));
			spawnTrailLandingParticles(level, point, player.getRandom());
		}
	}

	private static boolean hasActiveTrailAt(
			UUID ownerId,
			net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			Vec3 position,
			long nowTick
	) {
		double minimumSpacing = TRAIL_VISUAL_RADIUS * 0.9D;
		double minimumSpacingSquared = minimumSpacing * minimumSpacing;
		for (TrailPoint existing : TRAIL_POINTS) {
			if (existing.endTick <= nowTick
					|| !existing.ownerId.equals(ownerId)
					|| !existing.dimension.equals(dimension)
					|| Math.abs(existing.position.y - position.y) > 0.35D) {
				continue;
			}
			double dx = existing.position.x - position.x;
			double dz = existing.position.z - position.z;
			if (dx * dx + dz * dz <= minimumSpacingSquared) {
				return true;
			}
		}
		return false;
	}

	private static void tickTrailPoints(MinecraftServer server, long nowTick) {
		Map<ServerLevel, List<TrailPoint>> pointsByLevel = new HashMap<>();
		Iterator<TrailPoint> iterator = TRAIL_POINTS.iterator();
		while (iterator.hasNext()) {
			TrailPoint point = iterator.next();
			if (nowTick >= point.endTick) {
				iterator.remove();
				continue;
			}
			ServerLevel level = server.getLevel(point.dimension);
			if (level == null) {
				continue;
			}
			pointsByLevel.computeIfAbsent(level, ignored -> new ArrayList<>()).add(point);
			if (nowTick % TRAIL_PARTICLE_INTERVAL_TICKS == 0L) {
				spawnPersistentTrailParticles(level, point.position, nowTick);
			}
		}
		if (nowTick % TRAIL_DAMAGE_INTERVAL_TICKS != 0L) {
			return;
		}
		for (Map.Entry<ServerLevel, List<TrailPoint>> entry : pointsByLevel.entrySet()) {
			damageEntitiesOnTrail(entry.getKey(), entry.getValue());
		}
	}

	private static void damageEntitiesOnTrail(ServerLevel level, List<TrailPoint> points) {
		Set<UUID> damaged = new HashSet<>();
		for (TrailPoint point : points) {
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(point.ownerId);
			if (owner == null) {
				continue;
			}
			DamageSource trailDamageSource = level.damageSources().source(DamageTypes.ON_FIRE, owner);
			AABB area = new AABB(
					point.position.x - TRAIL_DAMAGE_RADIUS,
					point.position.y - 0.12D,
					point.position.z - TRAIL_DAMAGE_RADIUS,
					point.position.x + TRAIL_DAMAGE_RADIUS,
					point.position.y + 0.55D,
					point.position.z + TRAIL_DAMAGE_RADIUS
			);
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
				if (entity.getUUID().equals(point.ownerId) || !damaged.add(entity.getUUID())) {
					continue;
				}
				if (point.damage > 0.0D) {
					entity.setRemainingFireTicks(Math.max(1, entity.getRemainingFireTicks()));
					entity.hurtServer(level, trailDamageSource, (float) point.damage);
				}
			}
		}
	}

	private static void spawnTrailLandingParticles(ServerLevel level, Vec3 point, net.minecraft.util.RandomSource random) {
		for (int index = 0; index < 8; index++) {
			double angle = random.nextDouble() * Math.PI * 2.0D;
			double radius = TRAIL_VISUAL_RADIUS * Math.sqrt(random.nextDouble());
			double x = point.x + Math.cos(angle) * radius;
			double z = point.z + Math.sin(angle) * radius;
			DustParticleOptions dust = (index & 1) == 0 ? TRAIL_DARK_FIRE : TRAIL_BRIGHT_FIRE;
			level.sendParticles(dust, x, point.y + 0.08D, z, 1, 0.015D, 0.035D, 0.015D, 0.008D);
		}
		level.sendParticles(
				ParticleTypes.LAVA,
				point.x,
				point.y + 0.08D,
				point.z,
				2,
				0.12D,
				0.035D,
				0.12D,
				0.055D
		);
		for (int index = 0; index < 2; index++) {
			double angle = random.nextDouble() * Math.PI * 2.0D;
			double radius = TRAIL_VISUAL_RADIUS * Math.sqrt(random.nextDouble());
			level.sendParticles(
					ParticleTypes.SMALL_FLAME,
					point.x + Math.cos(angle) * radius,
					point.y + 0.06D,
					point.z + Math.sin(angle) * radius,
					1,
					0.02D,
					0.015D,
					0.02D,
					0.003D
			);
		}
	}

	private static void spawnPersistentTrailParticles(ServerLevel level, Vec3 point, long nowTick) {
		long positionHash = Double.doubleToLongBits(point.x * 31.0D + point.z * 17.0D);
		double phase = nowTick * 0.37D + (positionHash & 1023L) * (Math.PI * 2.0D / 1024.0D);
		int particleCount = 5;
		for (int index = 0; index < particleCount; index++) {
			double radius = index == 0
					? 0.0D
					: TRAIL_VISUAL_RADIUS * Math.sqrt(index / (double) (particleCount - 1));
			double angle = phase + index * (Math.PI * 2.0D / (particleCount - 1));
			DustParticleOptions dust = ((index + nowTick / TRAIL_PARTICLE_INTERVAL_TICKS) & 1L) == 0L
					? TRAIL_DARK_FIRE
					: TRAIL_BRIGHT_FIRE;
			level.sendParticles(
					dust,
					point.x + Math.cos(angle) * radius,
					point.y + 0.025D,
					point.z + Math.sin(angle) * radius,
					1,
					0.0D,
					0.0D,
					0.0D,
					0.0D
			);
		}
		if (nowTick % 6L == 0L) {
			for (int index = 0; index < 2; index++) {
				double flameAngle = phase + Math.PI * (0.5D + index);
				double flameRadius = TRAIL_VISUAL_RADIUS * 0.65D;
				level.sendParticles(
						ParticleTypes.SMALL_FLAME,
						point.x + Math.cos(flameAngle) * flameRadius,
						point.y + 0.05D,
						point.z + Math.sin(flameAngle) * flameRadius,
						1,
						0.0D,
						0.01D,
						0.0D,
						0.0D
				);
			}
		}
	}
	private static void tickAimWarning(ServerPlayer puro, RaceAbilityConfig ability, long nowTick) {
		double radius = positiveOrDefault(
				ability.puroSanStockAimWarningRadiusBlocks,
				DEFAULT_AIM_WARNING_RADIUS_BLOCKS
		);
		boolean targeted = radius > 0.0D && isTargetedByRangedWeapon(puro, radius);
		AimWarningState state = AIM_WARNING_STATES.get(puro.getUUID());
		if (!targeted) {
			if (state == null) {
				return;
			}
			long elapsed = Math.max(0L, nowTick - state.cycleStartTick);
			if (state.overlayVisible && elapsed < AIM_WARNING_VISIBLE_TICKS) {
				// Let the title finish its configured fade-out instead of replacing it abruptly.
				return;
			}
			clearAimWarning(puro, state);
			AIM_WARNING_STATES.remove(puro.getUUID());
			return;
		}
		if (state == null) {
			state = new AimWarningState(nowTick);
			AIM_WARNING_STATES.put(puro.getUUID(), state);
		}
		long elapsed = Math.max(0L, nowTick - state.cycleStartTick);
		if (elapsed >= AIM_WARNING_CYCLE_TICKS) {
			state.cycleStartTick = nowTick;
			elapsed = 0L;
			state.signalPlayed = false;
		}
		boolean visible = elapsed < AIM_WARNING_VISIBLE_TICKS;
		if (visible && !state.signalPlayed) {
			playAimWarningSignal(puro);
			state.signalPlayed = true;
		}
		if (visible != state.overlayVisible) {
			state.overlayVisible = visible;
			syncAimWarningOverlay(puro, visible);
		}
	}

	private static boolean isTargetedByRangedWeapon(ServerPlayer puro, double radius) {
		ServerLevel level = (ServerLevel) puro.level();
		AABB search = puro.getBoundingBox().inflate(radius);
		for (LivingEntity candidate : level.getEntitiesOfClass(
				LivingEntity.class,
				search,
				entity -> entity != puro && entity.isAlive() && !entity.isSpectator()
		)) {
			if (candidate.distanceToSqr(puro) > radius * radius || !holdsRangedThreat(candidate)) {
				continue;
			}
			Vec3 start = candidate.getEyePosition();
			Vec3 end = start.add(candidate.getLookAngle().normalize().scale(radius));
			Optional<Vec3> entityHit = puro.getBoundingBox().inflate(0.35D).clip(start, end);
			if (entityHit.isEmpty()) {
				continue;
			}
			BlockHitResult blockHit = level.clip(new ClipContext(
					start,
					entityHit.get(),
					ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE,
					candidate
			));
			if (blockHit.getType() == HitResult.Type.MISS
					|| blockHit.getLocation().distanceToSqr(start) + 0.01D >= entityHit.get().distanceToSqr(start)) {
				return true;
			}
		}
		return false;
	}

	private static boolean holdsRangedThreat(LivingEntity entity) {
		if (isThrowableThreatItem(entity.getMainHandItem()) || isThrowableThreatItem(entity.getOffhandItem())) {
			return true;
		}
		return entity.isUsingItem() && isAimedRangedWeapon(entity.getUseItem());
	}

	private static boolean isAimedRangedWeapon(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		return stack.getItem() instanceof ProjectileWeaponItem || stack.is(Items.TRIDENT);
	}

	private static boolean isThrowableThreatItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		return stack.is(Items.SNOWBALL)
				|| stack.is(Items.EGG)
				|| stack.is(Items.BLUE_EGG)
				|| stack.is(Items.BROWN_EGG)
				|| stack.is(Items.ENDER_PEARL)
				|| stack.is(Items.SPLASH_POTION)
				|| stack.is(Items.LINGERING_POTION)
				|| stack.is(Items.EXPERIENCE_BOTTLE)
				|| stack.is(Items.WIND_CHARGE);
	}

	private static boolean isMeleeWeaponAttack(DamageSource source) {
		if (source == null || !source.is(DamageTypes.PLAYER_ATTACK) || !(source.getEntity() instanceof LivingEntity attacker)) {
			return false;
		}
		ItemStack weapon = attacker.getMainHandItem();
		return !weapon.isEmpty() && weapon.has(DataComponents.WEAPON);
	}

	private static void playAimWarningSignal(ServerPlayer player) {
		Holder<SoundEvent> sound = Holder.direct(SoundEvents.WARDEN_HEARTBEAT);
		Vec3 pos = player.position();
		player.connection.send(new ClientboundSoundPacket(
				sound,
				SoundSource.PLAYERS,
				pos.x,
				pos.y,
				pos.z,
				0.75F,
				1.15F,
				player.getRandom().nextLong()
		));
	}

	private static void syncAimWarningOverlay(ServerPlayer player, boolean visible) {
		if (player == null || player.connection == null) {
			return;
		}
		boolean show = visible && PolymerResourcePackUtils.hasMainPack(player);
		if (show) {
			player.connection.send(new ClientboundSetTitlesAnimationPacket(3, 12, 5));
			player.connection.send(new ClientboundSetTitleTextPacket(buildAimWarningTitle()));
			return;
		}
		player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 1, 4));
		player.connection.send(new ClientboundSetTitleTextPacket(CopperManGogglesSystem.getScreenOverlayTitle(player)));
	}

	public static void showAimWarningOverlayOnce(ServerPlayer player) {
		if (player == null || player.connection == null || !PolymerResourcePackUtils.hasMainPack(player)) {
			return;
		}
		player.connection.send(new ClientboundSetTitlesAnimationPacket(3, 12, 5));
		player.connection.send(new ClientboundSetTitleTextPacket(buildAimWarningTitle()));
	}

	public static void restoreScreenOverlayAfterAimWarning(ServerPlayer player) {
		if (player == null || player.connection == null) {
			return;
		}
		AimWarningState state = AIM_WARNING_STATES.get(player.getUUID());
		if (state != null && state.overlayVisible) {
			syncAimWarningOverlay(player, true);
			return;
		}
		syncAimWarningOverlay(player, false);
	}

	private static Component buildAimWarningTitle() {
		Component glyph = Component.literal(AIM_WARNING_GLYPH)
				.withStyle(style -> style
						.withColor(0xFFFFFF)
						.withItalic(false)
						.withFont(AIM_WARNING_FONT)
						.withShadowColor(0x00000000));
		return Component.empty()
				.append(Component.literal(buildHorizontalAdvance(TITLE_X_OFFSET)).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)))
				.append(Component.literal(TITLE_OVERLAY_SHIFT).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)))
				.append(Component.literal(buildHorizontalAdvance(TITLE_X_CORRECTION)).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)))
				.append(glyph)
				.append(Component.literal(buildHorizontalAdvance(-TITLE_X_CORRECTION)).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)))
				.append(Component.literal(TITLE_OVERLAY_RESET).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)));
	}

	private static void clearAimWarning(ServerPlayer player, AimWarningState state) {
		if (state.overlayVisible) {
			state.overlayVisible = false;
			syncAimWarningOverlay(player, false);
		}
	}

	private static RaceAbilityConfig getStockAbility(ServerPlayer player) {
		if (player == null) {
			return null;
		}
		Optional<PlayerRaceConfig> race = ServerRaceSystem.getRace(player);
		if (race.isEmpty() || !RACE_ID.equals(race.get().id) || !ServerRaceSystem.hasUnlockedAbility(player, RaceAbilitySlot.STOCK)) {
			return null;
		}
		RaceAbilityConfig ability = ServerRaceSystem.getAbility(race.get(), RaceAbilitySlot.STOCK);
		return ability != null && ability.enabled ? ability : null;
	}

	private static void clearPlayerState(ServerPlayer player) {
		removeAttributeModifier(player.getAttribute(Attributes.MOVEMENT_SPEED), SPEED_MODIFIER_ID);
		removeAttributeModifier(player.getAttribute(Attributes.MAX_HEALTH), MAX_HEALTH_MODIFIER_ID);
		LAST_TRAIL_POSITIONS.remove(player.getUUID());
		AimWarningState warning = AIM_WARNING_STATES.remove(player.getUUID());
		if (warning != null) {
			clearAimWarning(player, warning);
		}
	}

	private static void cleanupPlayer(ServerPlayer player) {
		if (player != null) {
			clearPlayerState(player);
			TRAIL_POINTS.removeIf(point -> point.ownerId.equals(player.getUUID()));
		}
	}

	private static void clearAll(MinecraftServer server) {
		if (server != null) {
			for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
				clearPlayerState(player);
			}
		}
		LAST_TRAIL_POSITIONS.clear();
		TRAIL_POINTS.clear();
		AIM_WARNING_STATES.clear();
	}

	private static void syncAttributeModifier(
			AttributeInstance attribute,
			Identifier id,
			double amount,
			AttributeModifier.Operation operation
	) {
		if (attribute == null || id == null || !Double.isFinite(amount)) {
			return;
		}
		AttributeModifier current = attribute.getModifier(id);
		if (current != null && current.operation() == operation && Math.abs(current.amount() - amount) <= 1.0E-9D) {
			return;
		}
		if (current != null) {
			attribute.removeModifier(id);
		}
		if (Math.abs(amount) > 1.0E-9D) {
			attribute.addTransientModifier(new AttributeModifier(id, amount, operation));
		}
	}

	private static void removeAttributeModifier(AttributeInstance attribute, Identifier id) {
		if (attribute != null && attribute.getModifier(id) != null) {
			attribute.removeModifier(id);
		}
	}

	private static double positiveOrDefault(double value, double fallback) {
		return Double.isFinite(value) && value > 0.0D ? value : fallback;
	}

	private static double nonNegativeOrDefault(double value, double fallback) {
		return Double.isFinite(value) && value >= 0.0D ? value : fallback;
	}

	private static double clampRatio(double ratio) {
		return Math.max(0.0D, Math.min(1.0D, ratio));
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
	private record TrailPoint(UUID ownerId, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, Vec3 position, long endTick, double damage) {
	}

	private static final class AimWarningState {
		private long cycleStartTick;
		private boolean signalPlayed;
		private boolean overlayVisible;

		private AimWarningState(long cycleStartTick) {
			this.cycleStartTick = cycleStartTick;
		}
	}
}
