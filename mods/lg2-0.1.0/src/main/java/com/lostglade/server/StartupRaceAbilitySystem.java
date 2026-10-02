package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.config.RaceConfig.RaceAbilitySlot;
import com.lostglade.item.ModItems;
import com.lostglade.util.ItemDisplayHitboxHelper;
import com.mojang.math.Transformation;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Physical abilities of the temporary start race. All visible pieces are standard display
 * entities, so players without any extra client mod see the same effects through Polymer.
 */
public final class StartupRaceAbilitySystem {
	private static final Identifier CONFETTI_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "startup_confetti");
	private static final Identifier JACK_SCREAM_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "startup_jack_scream");
	private static final Identifier JACK_GIFT_BODY_MODEL_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "startup_jack_gift_body");
	private static final Identifier JACK_GIFT_LID_MODEL_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "startup_jack_gift_lid");
	private static final Holder<SoundEvent> CONFETTI_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(CONFETTI_SOUND_ID));
	private static final Holder<SoundEvent> JACK_SCREAM_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(JACK_SCREAM_SOUND_ID));
	private static final int BUBBLE_LIFETIME_TICKS = 20 * 18;
	private static final int CONFETTI_LIFETIME_TICKS = 18;
	private static final Item[] CONFETTI_ITEMS = {
			Items.RED_CARPET,
			Items.ORANGE_CARPET,
			Items.YELLOW_CARPET,
			Items.LIME_CARPET,
			Items.LIGHT_BLUE_CARPET,
			Items.MAGENTA_CARPET,
			Items.PINK_CARPET
	};
	private static final int FREE_BALLOON_LIFETIME_TICKS = 20 * 90;
	private static final int JACK_LIFETIME_TICKS = 20 * 75;
	private static final int JACK_OPEN_TICKS = 12;
	private static final int JACK_VISIBLE_TICKS = 36;
	private static final int JACK_FIREWORK_LAUNCH_TICKS = 14;
	private static final float JACK_GIFT_SCALE = 1.72F;
	private static final int[] JACK_FIREWORK_COLORS = {
			0xFF3355, 0xFFB000, 0xFFE66D, 0x41EAD4, 0x4D96FF, 0xC77DFF, 0xF72585
	};
	private static final float BUBBLE_SPRITE_BASE_SIZE = 7.0F;
	private static final float BUBBLE_DISPLAY_SCALE = 1.10F;
	private static final float BUBBLE_MIN_SCALE_VARIATION = 0.92F;
	private static final float BUBBLE_MAX_SCALE_VARIATION = 1.34F;
	private static final double JACK_ARM_DISTANCE_SQR = 3.5D * 3.5D;
	private static final double JACK_TRIGGER_DISTANCE_SQR = 1.45D * 1.45D;
	private static final ManagedInfiniteEffect BALLOON_JUMP_BOOST = new ManagedInfiniteEffect(
			MobEffects.JUMP_BOOST,
			false,
			false,
			true
	);
	private static final Map<UUID, BubbleState> BUBBLES = new HashMap<>();
	private static final Map<UUID, ConfettiState> CONFETTI = new HashMap<>();
	private static final Map<UUID, BalloonState> BALLOONS = new HashMap<>();
	private static final Map<UUID, FreeBalloonState> FREE_BALLOONS = new HashMap<>();
	private static final Map<UUID, JackBoxState> JACK_BOXES = new HashMap<>();

	private StartupRaceAbilitySystem() {
	}

	public static void register() {
		BUBBLES.clear();
		CONFETTI.clear();
		BALLOONS.clear();
		FREE_BALLOONS.clear();
		JACK_BOXES.clear();
		ServerTickEvents.END_SERVER_TICK.register(StartupRaceAbilitySystem::tick);
		AttackEntityCallback.EVENT.register(StartupRaceAbilitySystem::onAttackEntity);
		UseEntityCallback.EVENT.register(StartupRaceAbilitySystem::onUseEntity);
		ServerLifecycleEvents.SERVER_STOPPING.register(StartupRaceAbilitySystem::clearSeasonStartState);
	}

	public static int useAbility(ServerPlayer player, RaceAbilitySlot slot) {
		if (player == null || slot == null || !(player.level() instanceof ServerLevel level)) {
			return 0;
		}
		return switch (slot) {
			case ATTACK -> spawnSoapBubble(player, level) ? 1 : 0;
			case DEFENSE -> fireConfetti(player, level) ? 1 : 0;
			case UNIQUE_ABILITY -> giveBalloon(player) ? 1 : 0;
			case SHNYAGA -> placeJackBox(player, level) ? 1 : 0;
			case STOCK -> 0;
		};
	}

	public static boolean attachBalloon(ServerPlayer owner, ServerPlayer target) {
		if (owner == null || target == null || !owner.isAlive() || !target.isAlive() || owner.level() != target.level()
				|| !(target.level() instanceof ServerLevel level)) {
			return false;
		}
		ItemStack headStack = target.getItemBySlot(EquipmentSlot.HEAD);
		if (!headStack.isEmpty() && !headStack.is(ModItems.STARTUP_BALLOON)) {
			owner.displayClientMessage(net.minecraft.network.chat.Component.literal("Сначала нужно снять предмет с головы."), true);
			return false;
		}
		if (headStack.isEmpty()) {
			headStack = new ItemStack(ModItems.STARTUP_BALLOON);
		} else if (headStack.getCount() >= headStack.getMaxStackSize()) {
			owner.displayClientMessage(net.minecraft.network.chat.Component.literal("На голове уже слишком много шариков."), true);
			return false;
		} else {
			headStack.grow(1);
		}
		target.setItemSlot(EquipmentSlot.HEAD, headStack);
		ensureAttachedBalloonVisuals(level.getServer(), target);
		return true;
	}

	/** Removes one physical balloon from a player's head and returns it to the player who removed it. */
	public static boolean removeEquippedBalloon(ServerPlayer actor, ServerPlayer target) {
		if (actor == null || target == null || !(target.level() instanceof ServerLevel level)) {
			return false;
		}
		ItemStack headStack = target.getItemBySlot(EquipmentSlot.HEAD);
		if (!headStack.is(ModItems.STARTUP_BALLOON)) {
			return false;
		}
		headStack.shrink(1);
		target.setItemSlot(EquipmentSlot.HEAD, headStack);
		ItemStack returned = new ItemStack(ModItems.STARTUP_BALLOON);
		if (!actor.getInventory().add(returned)) {
			actor.drop(returned, false);
		}
		ensureAttachedBalloonVisuals(level.getServer(), target);
		level.playSound(null, target.blockPosition(), SoundEvents.LEAD_UNTIED, SoundSource.PLAYERS, 0.55F, 1.22F);
		return true;
	}

	/** Releases a balloon from a block face. It rises until it reaches the ceiling or another solid block. */
	public static boolean releaseBalloon(ServerPlayer owner, BlockPos clickedPos, Direction clickedFace) {
		if (owner == null || clickedPos == null || clickedFace == null || !(owner.level() instanceof ServerLevel level)) {
			return false;
		}
		RandomSource random = level.getRandom();
		Vec3 normal = new Vec3(clickedFace.getStepX(), clickedFace.getStepY(), clickedFace.getStepZ());
		Vec3 position = Vec3.atCenterOf(clickedPos)
				.add(normal.scale(0.64D))
				.add(0.0D, 0.12D, 0.0D);
		float size = 0.56F + random.nextFloat() * 0.84F;
		Display.ItemDisplay display = createDisplay(
				level,
				new ItemStack(ModItems.STARTUP_BALLOON),
				position,
				Display.BillboardConstraints.FIXED,
				size
		);
		if (display == null) {
			return false;
		}
		Interaction trigger = new Interaction(EntityType.INTERACTION, level);
		trigger.setNoGravity(true);
		trigger.noPhysics = true;
		trigger.setSilent(true);
		trigger.setInvisible(true);
		trigger.setResponse(false);
		trigger.setWidth(Math.max(0.35F, size * 0.78F));
		trigger.setHeight(Math.max(0.35F, size * 0.78F));
		trigger.setPos(position.x, position.y - size * 0.38D, position.z);
		level.addFreshEntity(trigger);
		Vec3 drift = new Vec3(normal.x, 0.0D, normal.z).scale(0.018D + random.nextDouble() * 0.028D).add(
				(random.nextDouble() - 0.5D) * 0.018D,
				0.042D + random.nextDouble() * 0.018D,
				(random.nextDouble() - 0.5D) * 0.018D
		);
		FreeBalloonState state = new FreeBalloonState(
				level.dimension(),
				owner.getUUID(),
				display.getUUID(),
				trigger.getUUID(),
				position,
				drift,
				size,
				level.getGameTime() + FREE_BALLOON_LIFETIME_TICKS
		);
		FREE_BALLOONS.put(display.getUUID(), state);
		return true;
	}

	public static void clearPlayerState(MinecraftServer server, UUID playerId) {
		if (server == null || playerId == null) {
			return;
		}
		removeBalloons(server, state -> state.ownerId.equals(playerId) || state.targetId.equals(playerId));
		BALLOON_JUMP_BOOST.clear(server.getPlayerList().getPlayer(playerId));
		removeFreeBalloons(server, state -> state.ownerId.equals(playerId));
		removeJackBoxes(server, state -> state.ownerId.equals(playerId));
	}

	public static void clearSeasonStartState(MinecraftServer server) {
		if (server == null) {
			BALLOON_JUMP_BOOST.clearAll(null);
			BUBBLES.clear();
			CONFETTI.clear();
			BALLOONS.clear();
			FREE_BALLOONS.clear();
			JACK_BOXES.clear();
			return;
		}
		for (BubbleState state : BUBBLES.values()) {
			removeEntity(server, state.displayId);
			removeEntity(server, state.triggerId);
		}
		for (ConfettiState state : CONFETTI.values()) {
			removeEntity(server, state.displayId);
		}
		for (BalloonState state : BALLOONS.values()) {
			removeAttachedBalloonVisuals(server, state);
		}
		for (FreeBalloonState state : FREE_BALLOONS.values()) {
			removeEntity(server, state.displayId);
			removeEntity(server, state.triggerId);
		}
		for (JackBoxState state : JACK_BOXES.values()) {
			removeJackVisuals(server, state);
		}
		BALLOON_JUMP_BOOST.clearAll(server);
		BUBBLES.clear();
		CONFETTI.clear();
		BALLOONS.clear();
		FREE_BALLOONS.clear();
		JACK_BOXES.clear();
	}

	private static boolean spawnSoapBubble(ServerPlayer player, ServerLevel level) {
		RandomSource random = level.getRandom();
		Vec3 direction = player.getLookAngle();
		if (direction.lengthSqr() < 1.0E-5D) {
			direction = new Vec3(0.0D, 0.0D, 1.0D);
		}
		direction = direction.normalize();
		Vec3 position = player.getEyePosition().add(direction.scale(1.30D));
		float scaleVariation = BUBBLE_MIN_SCALE_VARIATION
				+ random.nextFloat() * (BUBBLE_MAX_SCALE_VARIATION - BUBBLE_MIN_SCALE_VARIATION);
		float displayScale = BUBBLE_DISPLAY_SCALE * scaleVariation;
		// The 7 px sprite size is its full width, not its collision radius.
		float hitRadius = Math.max(0.11F, BUBBLE_SPRITE_BASE_SIZE / 32.0F * displayScale);
		Display.ItemDisplay display = createDisplay(
				level,
				createSoapBubbleDisplayStack(),
				position,
				Display.BillboardConstraints.CENTER,
				displayScale
		);
		if (display == null) {
			return false;
		}
		Interaction trigger = new Interaction(EntityType.INTERACTION, level);
		trigger.setNoGravity(true);
		trigger.noPhysics = true;
		trigger.setSilent(true);
		trigger.setInvisible(true);
		trigger.setResponse(false);
		trigger.setWidth(hitRadius * 2.0F);
		trigger.setHeight(hitRadius * 2.0F);
		trigger.setPos(position.x, position.y - hitRadius, position.z);
		level.addFreshEntity(trigger);
		BubbleState state = new BubbleState(
				level.dimension(),
				player.getUUID(),
				display.getUUID(),
				trigger.getUUID(),
				position,
				// Start safely ahead of the caster before the bubble begins its random drift.
				direction.scale(0.105D).add(newRandomBubbleDirection(random).scale(0.012D)),
				direction,
				hitRadius,
				level.getGameTime() + 18L + random.nextInt(16),
				level.getGameTime() + BUBBLE_LIFETIME_TICKS
		);
		BUBBLES.put(display.getUUID(), state);
		level.playSound(null, player.blockPosition(), SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.PLAYERS, 0.35F, 1.55F);
		return true;
	}

	private static boolean fireConfetti(ServerPlayer player, ServerLevel level) {
		RandomSource random = level.getRandom();
		Vec3 direction = player.getLookAngle().normalize();
		Vec3 side = new Vec3(-direction.z, 0.0D, direction.x).normalize();
		Vec3 origin = player.getEyePosition().add(direction.scale(0.52D)).add(0.0D, -0.10D, 0.0D);
		for (int index = 0; index < 46; index++) {
			int colorIndex = random.nextInt(CONFETTI_ITEMS.length);
			float stripScale = 0.105F + random.nextFloat() * 0.055F;
			Vec3 velocity = direction.scale(0.24D + random.nextDouble() * 0.22D)
					.add(side.scale((random.nextDouble() - 0.5D) * 0.58D))
					.add(0.0D, 0.18D + random.nextDouble() * 0.27D, 0.0D);
			Display.ItemDisplay strip = createDisplay(
					level,
					new ItemStack(CONFETTI_ITEMS[colorIndex]),
					origin,
					Display.BillboardConstraints.FIXED,
					stripScale
			);
			if (strip == null) {
				continue;
			}
			strip.setShadowRadius(0.0F);
			strip.setShadowStrength(0.0F);
			CONFETTI.put(strip.getUUID(), new ConfettiState(
					level.dimension(),
					strip.getUUID(),
					origin,
					velocity,
					random.nextFloat() * ((float) Math.PI * 2.0F),
					random.nextFloat() * ((float) Math.PI * 2.0F),
					random.nextFloat() * ((float) Math.PI * 2.0F),
					stripScale,
					level.getGameTime(),
					level.getGameTime() + CONFETTI_LIFETIME_TICKS + random.nextInt(9)
			));
		}
		playNearbyPackSound(level, origin, CONFETTI_SOUND, SoundSource.PLAYERS, 1.0F, 1.0F);
		return true;
	}

	private static boolean giveBalloon(ServerPlayer player) {
		ItemStack balloon = new ItemStack(ModItems.STARTUP_BALLOON);
		if (!player.getInventory().add(balloon)) {
			player.drop(balloon, false);
		}
		return true;
	}

	private static boolean placeJackBox(ServerPlayer player, ServerLevel level) {
		BlockPos ground = findGroundBelowPlayer(level, player);
		if (ground == null) {
			return false;
		}
		Vec3 base = Vec3.atBottomCenterOf(ground).add(0.0D, 1.0D, 0.0D);
		for (JackBoxState other : JACK_BOXES.values()) {
			if (other.dimension.equals(level.dimension()) && other.position.distanceToSqr(base) < 2.25D) {
				player.displayClientMessage(net.minecraft.network.chat.Component.literal("Здесь уже стоит джекбокс."), true);
				return false;
			}
		}
		Display.ItemDisplay box = createDisplay(level, createJackGiftBodyDisplayStack(), base, Display.BillboardConstraints.FIXED, JACK_GIFT_SCALE);
		Display.ItemDisplay lid = createDisplay(level, createJackGiftLidDisplayStack(), base, Display.BillboardConstraints.FIXED, JACK_GIFT_SCALE);
		if (box == null || lid == null) {
			if (box != null) {
				box.discard();
			}
			if (lid != null) {
				lid.discard();
			}
			return false;
		}
		JackBoxState state = new JackBoxState(level.dimension(), player.getUUID(), base, box.getUUID(), lid.getUUID(), level.getGameTime());
		JACK_BOXES.put(box.getUUID(), state);
		level.playSound(null, ground, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.72F, 1.18F);
		return true;
	}

	private static BlockPos findGroundBelowPlayer(ServerLevel level, ServerPlayer player) {
		BlockPos start = player.blockPosition();
		for (int offset = 0; offset <= 5; offset++) {
			BlockPos candidate = start.below(offset);
			if (level.getBlockState(candidate).blocksMotion()) {
				return candidate;
			}
		}
		return null;
	}

	private static void tick(MinecraftServer server) {
		if (server == null) {
			return;
		}
		syncEquippedBalloons(server);
		tickConfetti(server);
		tickBubbles(server);
		tickBalloons(server);
		tickFreeBalloons(server);
		tickJackBoxes(server);
	}

	private static void tickBubbles(MinecraftServer server) {
		Iterator<BubbleState> iterator = BUBBLES.values().iterator();
		while (iterator.hasNext()) {
			BubbleState state = iterator.next();
			ServerLevel level = server.getLevel(state.dimension);
			Display.ItemDisplay display = findEntity(server, state.displayId, Display.ItemDisplay.class);
			Interaction trigger = findEntity(server, state.triggerId, Interaction.class);
			if (level == null || display == null || trigger == null || level.getGameTime() >= state.expiresAtTick) {
				popBubble(server, state, false);
				iterator.remove();
				continue;
			}
			long nowTick = level.getGameTime();
			RandomSource random = level.getRandom();
			if (nowTick >= state.nextDirectionChangeTick) {
				state.steering = newRandomBubbleDirection(random);
				state.targetSpeed = random.nextDouble() < 0.18D ? 0.0D : 0.025D + random.nextDouble() * 0.105D;
				state.nextDirectionChangeTick = nowTick + 9L + random.nextInt(38);
			}
			state.velocity = state.velocity.scale(0.90D).add(state.steering.scale(state.targetSpeed * 0.20D));
			if (state.velocity.lengthSqr() > 0.15D * 0.15D) {
				state.velocity = state.velocity.normalize().scale(0.15D);
			}
			Vec3 next = state.position.add(state.velocity);
			BlockHitResult hit = level.clip(new ClipContext(state.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, display));
			boolean hitBlock = hit.getType() != HitResult.Type.MISS;
			// Only real dropped items pop a drifting bubble. Interaction entities used by
			// balloons and other abilities are pickable too, but must not act as obstacles.
			boolean hitEntity = !level.getEntities(
					display,
					AABB.ofSize(next, state.hitRadius * 2.0D, state.hitRadius * 2.0D, state.hitRadius * 2.0D),
					entity -> entity instanceof ItemEntity
			).isEmpty();
			if (hitBlock || hitEntity) {
				popBubble(server, state, true);
				iterator.remove();
				continue;
			}
			state.position = next;
			display.setPos(next.x, next.y, next.z);
			trigger.setPos(next.x, next.y - state.hitRadius, next.z);
		}
	}

	private static void tickConfetti(MinecraftServer server) {
		Iterator<ConfettiState> iterator = CONFETTI.values().iterator();
		while (iterator.hasNext()) {
			ConfettiState state = iterator.next();
			ServerLevel level = server.getLevel(state.dimension);
			Display.ItemDisplay display = findEntity(server, state.displayId, Display.ItemDisplay.class);
			if (level == null || display == null || level.getGameTime() >= state.expiresAtTick) {
				if (display != null) {
					display.discard();
				}
				iterator.remove();
				continue;
			}
			state.velocity = state.velocity.scale(0.945D).add(0.0D, -0.022D, 0.0D);
			state.position = state.position.add(state.velocity);
			display.setPos(state.position.x, state.position.y, state.position.z);
			long age = level.getGameTime() - state.createdAtTick;
			Quaternionf rotation = new Quaternionf().rotateXYZ(
					state.pitch + age * 0.43F,
					state.yaw + age * 0.31F,
					state.roll + age * 0.56F
			);
			display.setTransformation(new Transformation(
					new Vector3f(),
					rotation,
					new Vector3f(state.scale, state.scale * 0.72F, state.scale),
					new Quaternionf()
			));
		}
	}

	private static void tickBalloons(MinecraftServer server) {
		Map<UUID, List<BalloonState>> balloonsByTarget = new HashMap<>();
		Iterator<BalloonState> iterator = BALLOONS.values().iterator();
		while (iterator.hasNext()) {
			BalloonState state = iterator.next();
			ServerPlayer target = server.getPlayerList().getPlayer(state.targetId);
			Display.ItemDisplay display = findEntity(server, state.displayId, Display.ItemDisplay.class);
			Interaction trigger = findEntity(server, state.triggerId, Interaction.class);
			if (target == null || !target.isAlive() || display == null || trigger == null
					|| !target.level().dimension().equals(state.dimension)) {
				removeAttachedBalloonVisuals(server, state);
				iterator.remove();
				continue;
			}
			balloonsByTarget.computeIfAbsent(state.targetId, ignored -> new ArrayList<>()).add(state);
		}
		for (Map.Entry<UUID, List<BalloonState>> entry : balloonsByTarget.entrySet()) {
			ServerPlayer target = server.getPlayerList().getPlayer(entry.getKey());
			if (target == null || !target.isAlive()) {
				continue;
			}
			List<BalloonState> balloons = entry.getValue();
			for (int index = 0; index < balloons.size(); index++) {
				BalloonState state = balloons.get(index);
				Display.ItemDisplay display = findEntity(server, state.displayId, Display.ItemDisplay.class);
				Interaction trigger = findEntity(server, state.triggerId, Interaction.class);
				if (display != null && trigger != null) {
					updateBalloonDisplay(target, display, trigger, index, balloons.size(), state.createdAtTick, state.size);
				}
			}
			// Every physical balloon in the head slot contributes one Jump Boost level.
			int amplifier = Math.min(127, balloons.size() - 1);
			BALLOON_JUMP_BOOST.ensure(target, amplifier);
		}
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			if (!balloonsByTarget.containsKey(player.getUUID())) {
				BALLOON_JUMP_BOOST.clear(player);
			}
		}
	}

	private static void syncEquippedBalloons(MinecraftServer server) {
		if (server == null) {
			return;
		}
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			if (player.isAlive() && player.level() instanceof ServerLevel) {
				ensureAttachedBalloonVisuals(server, player);
			}
		}
	}

	private static void ensureAttachedBalloonVisuals(MinecraftServer server, ServerPlayer target) {
		if (server == null || target == null || !(target.level() instanceof ServerLevel level)) {
			return;
		}
		ItemStack headStack = target.getItemBySlot(EquipmentSlot.HEAD);
		int expected = headStack.is(ModItems.STARTUP_BALLOON) ? headStack.getCount() : 0;
		List<BalloonState> current = new ArrayList<>();
		for (BalloonState state : BALLOONS.values()) {
			if (state.targetId.equals(target.getUUID())) {
				current.add(state);
			}
		}
		while (current.size() > expected) {
			BalloonState removed = current.remove(current.size() - 1);
			BALLOONS.remove(removed.displayId);
			removeAttachedBalloonVisuals(server, removed);
		}
		while (current.size() < expected) {
			BalloonState created = createAttachedBalloon(level, target);
			if (created == null) {
				break;
			}
			BALLOONS.put(created.displayId, created);
			current.add(created);
		}
	}

	private static BalloonState createAttachedBalloon(ServerLevel level, ServerPlayer target) {
		if (level == null || target == null) {
			return null;
		}
		float size = 0.72F + level.getRandom().nextFloat() * 0.62F;
		Vec3 position = target.position().add(0.0D, target.getBbHeight() + 1.0D, 0.0D);
		Display.ItemDisplay display = createDisplay(
				level,
				new ItemStack(ModItems.STARTUP_BALLOON),
				position,
				Display.BillboardConstraints.FIXED,
				size
		);
		if (display == null) {
			return null;
		}
		Interaction trigger = new Interaction(EntityType.INTERACTION, level);
		trigger.setNoGravity(true);
		trigger.noPhysics = true;
		trigger.setSilent(true);
		trigger.setInvisible(true);
		trigger.setResponse(false);
		trigger.setWidth(Math.max(0.35F, size * 0.78F));
		trigger.setHeight(Math.max(0.35F, size * 0.78F));
		trigger.setPos(position.x, position.y - size * 0.38D, position.z);
		level.addFreshEntity(trigger);
		return new BalloonState(
				level.dimension(),
				target.getUUID(),
				target.getUUID(),
				display.getUUID(),
				trigger.getUUID(),
				level.getGameTime(),
				size
		);
	}

	private static void updateBalloonDisplay(ServerPlayer target, Display.ItemDisplay display, Interaction trigger, int index, int total, long createdAtTick, float size) {
		long nowTick = target.level().getGameTime();
		int ring = index / 8;
		int ringStart = ring * 8;
		int ringSize = Math.min(8, total - ringStart);
		double angle = (Math.PI * 2.0D * (index - ringStart) / Math.max(1, ringSize)) + createdAtTick * 0.017D;
		double radius = total == 1 ? 0.0D : 0.24D + ring * 0.17D;
		double sway = Math.sin((nowTick + createdAtTick + index * 11L) * 0.10D) * 0.12D;
		Vec3 position = target.position().add(
				Math.cos(angle) * radius,
				target.getBbHeight() + 1.0D + ring * 0.10D + sway,
				Math.sin(angle) * radius
		);
		display.setPos(position.x, position.y, position.z);
		trigger.setPos(position.x, position.y - size * 0.38D, position.z);
		float wobble = (float) Math.sin((nowTick + index * 7L) * 0.085D) * 0.08F;
		display.setTransformation(new Transformation(
				new Vector3f(),
				new Quaternionf().rotateZ(wobble),
				new Vector3f(size, size, size),
				new Quaternionf()
		));
	}

	private static void tickFreeBalloons(MinecraftServer server) {
		Iterator<FreeBalloonState> iterator = FREE_BALLOONS.values().iterator();
		while (iterator.hasNext()) {
			FreeBalloonState state = iterator.next();
			ServerLevel level = server.getLevel(state.dimension);
			Display.ItemDisplay display = findEntity(server, state.displayId, Display.ItemDisplay.class);
			Interaction trigger = findEntity(server, state.triggerId, Interaction.class);
			if (level == null || display == null || trigger == null || level.getGameTime() >= state.expiresAtTick) {
				popFreeBalloon(server, state, false);
				iterator.remove();
				continue;
			}
			RandomSource random = level.getRandom();
			state.velocity = state.velocity.scale(0.95D).add(
					(random.nextDouble() - 0.5D) * 0.0022D,
					0.005D + random.nextDouble() * 0.003D,
					(random.nextDouble() - 0.5D) * 0.0022D
			);
			Vec3 horizontal = new Vec3(state.velocity.x, 0.0D, state.velocity.z);
			if (horizontal.lengthSqr() > 0.075D * 0.075D) {
				horizontal = horizontal.normalize().scale(0.075D);
			}
			state.velocity = new Vec3(horizontal.x, Math.min(0.115D, state.velocity.y), horizontal.z);
			Vec3 next = state.position.add(state.velocity);
			BlockHitResult hit = level.clip(new ClipContext(state.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, display));
			if (hit.getType() != HitResult.Type.MISS) {
				popFreeBalloon(server, state, true);
				iterator.remove();
				continue;
			}
			state.position = next;
			display.setPos(next.x, next.y, next.z);
			trigger.setPos(next.x, next.y - state.size * 0.38D, next.z);
			float wobble = (float) Math.sin((level.getGameTime() + state.displayId.getLeastSignificantBits()) * 0.10D) * 0.13F;
			display.setTransformation(new Transformation(
					new Vector3f(),
					new Quaternionf().rotateZ(wobble),
					new Vector3f(state.size, state.size, state.size),
					new Quaternionf()
			));
		}
	}

	private static void tickJackBoxes(MinecraftServer server) {
		Iterator<JackBoxState> iterator = JACK_BOXES.values().iterator();
		while (iterator.hasNext()) {
			JackBoxState state = iterator.next();
			ServerLevel level = server.getLevel(state.dimension);
			Display.ItemDisplay box = findEntity(server, state.boxDisplayId, Display.ItemDisplay.class);
			Display.ItemDisplay lid = findEntity(server, state.lidDisplayId, Display.ItemDisplay.class);
			Display.ItemDisplay clown = findEntity(server, state.clownDisplayId, Display.ItemDisplay.class);
			Display.ItemDisplay rocket = findEntity(server, state.rocketDisplayId, Display.ItemDisplay.class);
			if (level == null || box == null || lid == null || level.getGameTime() >= state.createdAtTick + JACK_LIFETIME_TICKS) {
				removeJackVisuals(server, state);
				iterator.remove();
				continue;
			}
			if (state.triggeredAtTick > 0L) {
				long elapsed = level.getGameTime() - state.triggeredAtTick;
				updateOpenedJackBox(level, state, box, lid, clown, rocket, elapsed);
				if (elapsed >= JACK_VISIBLE_TICKS) {
					removeJackVisuals(server, state);
					iterator.remove();
				}
				continue;
			}
			ServerPlayer owner = server.getPlayerList().getPlayer(state.ownerId);
			if (!state.armed && (owner == null || owner.level().dimension() != state.dimension || owner.position().distanceToSqr(state.position) >= JACK_ARM_DISTANCE_SQR)) {
				state.armed = true;
				level.playSound(null, BlockPos.containing(state.position), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, 0.5F, 1.4F);
			}
			if (state.armed) {
				for (ServerPlayer candidate : level.players()) {
					if (!candidate.isAlive() || candidate.isSpectator() || candidate.position().distanceToSqr(state.position) > JACK_TRIGGER_DISTANCE_SQR) {
						continue;
					}
					triggerJackBox(level, state, candidate);
					break;
				}
			}
		}
	}

	private static void triggerJackBox(ServerLevel level, JackBoxState state, ServerPlayer victim) {
		state.triggeredAtTick = level.getGameTime();
		state.surprise = level.getRandom().nextBoolean() ? JackSurprise.SCREAMER : JackSurprise.FIREWORK;
		level.playSound(null, BlockPos.containing(state.position), SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.9F, 0.92F);
		if (state.surprise == JackSurprise.SCREAMER) {
			Display.ItemDisplay clown = createDisplay(
					level,
					new ItemStack(ModItems.STARTUP_JACK_CLOWN),
					state.position.add(0.0D, 0.24D, 0.0D),
					Display.BillboardConstraints.CENTER,
					0.10F
			);
			if (clown != null) {
				state.clownDisplayId = clown.getUUID();
			}
			playPersonalPackSound(victim, JACK_SCREAM_SOUND, SoundSource.HOSTILE, state.position.add(0.0D, 0.9D, 0.0D), 1.15F, 1.0F);
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, state.position.x, state.position.y + 0.48D, state.position.z, 14, 0.24D, 0.34D, 0.24D, 0.02D);
			return;
		}

		state.fireworkPattern = JackFireworkPattern.random(level.getRandom());
		Display.ItemDisplay rocket = createDisplay(
				level,
				new ItemStack(Items.FIREWORK_ROCKET),
				state.position.add(0.0D, 0.32D, 0.0D),
				Display.BillboardConstraints.FIXED,
				0.34F
		);
		if (rocket != null) {
			state.rocketDisplayId = rocket.getUUID();
		}
		level.playSound(null, BlockPos.containing(state.position), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.BLOCKS, 0.82F, 0.96F + level.getRandom().nextFloat() * 0.12F);
	}

	private static void updateOpenedJackBox(
			ServerLevel level,
			JackBoxState state,
			Display.ItemDisplay box,
			Display.ItemDisplay lid,
			Display.ItemDisplay clown,
			Display.ItemDisplay rocket,
			long elapsed
	) {
		float progress = Math.min(1.0F, elapsed / (float) JACK_OPEN_TICKS);
		float eased = 1.0F - (float) Math.pow(1.0F - progress, 3.0F);
		box.setTransformation(new Transformation(
				new Vector3f(),
				new Quaternionf().rotateZ((float) Math.sin(elapsed * 0.7D) * 0.035F),
				new Vector3f(JACK_GIFT_SCALE, JACK_GIFT_SCALE, JACK_GIFT_SCALE),
				new Quaternionf()
		));
		lid.setPos(
				state.position.x + 0.72D * eased,
				state.position.y + 0.18D + 0.62D * eased - 0.12D * eased * eased,
				state.position.z + 0.30D * eased
		);
		lid.setTransformation(new Transformation(
				new Vector3f(),
				new Quaternionf().rotateX(1.35F * eased).rotateZ(0.72F * eased),
				new Vector3f(JACK_GIFT_SCALE, JACK_GIFT_SCALE, JACK_GIFT_SCALE),
				new Quaternionf()
		));

		if (state.surprise == JackSurprise.SCREAMER && clown != null) {
			clown.setPos(state.position.x, state.position.y + 0.26D + 0.88D * eased, state.position.z);
			clown.setTransformation(new Transformation(
					new Vector3f(),
					new Quaternionf().rotateZ((float) Math.sin(elapsed * 0.6D) * 0.18F),
					new Vector3f(0.10F + 0.52F * eased, 0.10F + 0.52F * eased, 0.10F + 0.52F * eased),
					new Quaternionf()
			));
		}
		if (state.surprise == JackSurprise.FIREWORK) {
			updateJackFirework(level, state, rocket, elapsed);
		}
	}

	private static void updateJackFirework(ServerLevel level, JackBoxState state, Display.ItemDisplay rocket, long elapsed) {
		float launchProgress = Math.min(1.0F, elapsed / (float) JACK_FIREWORK_LAUNCH_TICKS);
		float easedLaunch = 1.0F - (1.0F - launchProgress) * (1.0F - launchProgress);
		Vec3 burstPosition = state.position.add(0.0D, 0.34D + 4.0D * easedLaunch, 0.0D);
		if (rocket != null && !state.fireworkExploded) {
			rocket.setPos(burstPosition.x, burstPosition.y, burstPosition.z);
			rocket.setTransformation(new Transformation(
					new Vector3f(),
					new Quaternionf().rotateY((float) (elapsed * 0.48D)),
					new Vector3f(0.34F, 0.34F, 0.34F),
					new Quaternionf()
			));
		}
		if (elapsed < JACK_FIREWORK_LAUNCH_TICKS || state.fireworkExploded) {
			return;
		}
		state.fireworkExploded = true;
		state.rocketDisplayId = null;
		if (rocket != null) {
			rocket.discard();
		}
		emitJackFireworkBurst(level, burstPosition, state.fireworkPattern == null ? JackFireworkPattern.BURST : state.fireworkPattern);
	}

	private static void emitJackFireworkBurst(ServerLevel level, Vec3 origin, JackFireworkPattern pattern) {
		if (level == null || origin == null || pattern == null) {
			return;
		}
		RandomSource random = level.getRandom();
		int primary = JACK_FIREWORK_COLORS[random.nextInt(JACK_FIREWORK_COLORS.length)];
		int secondary = JACK_FIREWORK_COLORS[random.nextInt(JACK_FIREWORK_COLORS.length)];
		int points = switch (pattern) {
			case RING, STAR -> 36;
			case SPIRAL -> 42;
			case BURST -> 48;
		};
		for (int index = 0; index < points; index++) {
			Vec3 offset = resolveJackFireworkOffset(pattern, index, points, random);
			DustParticleOptions particle = new DustParticleOptions(index % 3 == 0 ? secondary : primary, 1.15F + random.nextFloat() * 0.45F);
			level.sendParticles(
					particle,
					origin.x + offset.x,
					origin.y + offset.y,
					origin.z + offset.z,
					1,
					0.0D,
					0.0D,
					0.0D,
					0.0D
			);
		}
		level.sendParticles(ParticleTypes.FIREWORK, origin.x, origin.y, origin.z, 28, 0.34D, 0.34D, 0.34D, 0.18D);
		level.playSound(null, BlockPos.containing(origin), SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.BLOCKS, 1.1F, 0.9F + random.nextFloat() * 0.18F);
	}

	private static Vec3 resolveJackFireworkOffset(JackFireworkPattern pattern, int index, int points, RandomSource random) {
		double angle = Math.PI * 2.0D * index / points;
		return switch (pattern) {
			case RING -> new Vec3(Math.cos(angle) * 1.48D, Math.sin(angle) * 1.48D, Math.sin(angle * 2.0D) * 0.24D);
			case STAR -> {
				double radius = index % 2 == 0 ? 1.62D : 0.72D;
				yield new Vec3(Math.cos(angle) * radius, Math.sin(angle) * radius, Math.sin(angle * 5.0D) * 0.18D);
			}
			case SPIRAL -> {
				double radius = 0.18D + 1.46D * index / Math.max(1.0D, points - 1.0D);
				yield new Vec3(Math.cos(angle * 2.2D) * radius, (index / (double) points - 0.5D) * 2.6D, Math.sin(angle * 2.2D) * radius);
			}
			case BURST -> {
				Vec3 direction = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize();
				yield direction.scale(0.42D + random.nextDouble() * 1.34D);
			}
		};
	}

	private static InteractionResult onAttackEntity(Player player, Level world, InteractionHand hand, Entity entity, net.minecraft.world.phys.EntityHitResult hitResult) {
		if (!(player instanceof ServerPlayer) || world.isClientSide() || !(entity instanceof Interaction)) {
			return InteractionResult.PASS;
		}
		for (BubbleState state : new ArrayList<>(BUBBLES.values())) {
			if (!state.triggerId.equals(entity.getUUID())) {
				continue;
			}
			MinecraftServer server = world.getServer();
			if (server != null) {
				popBubble(server, state, true);
			}
			BUBBLES.remove(state.displayId);
			return InteractionResult.SUCCESS;
		}
		for (FreeBalloonState state : new ArrayList<>(FREE_BALLOONS.values())) {
			if (!state.triggerId.equals(entity.getUUID())) {
				continue;
			}
			MinecraftServer server = world.getServer();
			if (server != null) {
				popFreeBalloon(server, state, true);
			}
			FREE_BALLOONS.remove(state.displayId);
			return InteractionResult.SUCCESS;
		}
		for (BalloonState state : new ArrayList<>(BALLOONS.values())) {
			if (!state.triggerId.equals(entity.getUUID())) {
				continue;
			}
			MinecraftServer server = world.getServer();
			if (server != null) {
				popAttachedBalloon(server, state);
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	private static InteractionResult onUseEntity(Player player, Level world, InteractionHand hand, Entity entity, net.minecraft.world.phys.EntityHitResult hitResult) {
		if (!(player instanceof ServerPlayer actor) || world.isClientSide() || !(entity instanceof Interaction)) {
			return InteractionResult.PASS;
		}
		for (BalloonState state : new ArrayList<>(BALLOONS.values())) {
			if (!state.triggerId.equals(entity.getUUID())) {
				continue;
			}
			MinecraftServer server = world.getServer();
			ServerPlayer target = server == null ? null : server.getPlayerList().getPlayer(state.targetId);
			return target != null && removeEquippedBalloon(actor, target)
					? InteractionResult.SUCCESS
					: InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	private static ItemStack createSoapBubbleDisplayStack() {
		ItemStack stack = new ItemStack(Items.HEART_OF_THE_SEA);
		stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "startup_bubble_sprite"));
		return stack;
	}

	private static ItemStack createJackGiftBodyDisplayStack() {
		ItemStack stack = new ItemStack(Items.HEART_OF_THE_SEA);
		stack.set(DataComponents.ITEM_MODEL, JACK_GIFT_BODY_MODEL_ID);
		return stack;
	}

	private static ItemStack createJackGiftLidDisplayStack() {
		ItemStack stack = new ItemStack(Items.HEART_OF_THE_SEA);
		stack.set(DataComponents.ITEM_MODEL, JACK_GIFT_LID_MODEL_ID);
		return stack;
	}

	private static Display.ItemDisplay createDisplay(
			ServerLevel level,
			ItemStack stack,
			Vec3 position,
			Display.BillboardConstraints billboard,
			float scale
	) {
		if (level == null || stack == null || stack.isEmpty() || position == null) {
			return null;
		}
		Display.ItemDisplay display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		display.setNoGravity(true);
		display.noPhysics = true;
		display.setInvulnerable(true);
		display.setSilent(true);
		display.setShadowRadius(0.0F);
		display.setShadowStrength(0.0F);
		display.setViewRange(64.0F);
		display.setBillboardConstraints(billboard);
		display.setItemTransform(ItemDisplayContext.FIXED);
		display.setTransformationInterpolationDelay(0);
		display.setTransformationInterpolationDuration(2);
		display.setPosRotInterpolationDuration(2);
		display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
		display.setItemStack(stack);
		ItemDisplayHitboxHelper.clear(display);
		display.setPos(position.x, position.y, position.z);
		level.addFreshEntity(display);
		return display;
	}

	private static void popBubble(MinecraftServer server, BubbleState state, boolean visible) {
		if (state == null) {
			return;
		}
		ServerLevel level = server.getLevel(state.dimension);
		if (visible && level != null) {
			Vec3 position = state.position;
			level.sendParticles(ParticleTypes.BUBBLE_POP, position.x, position.y, position.z, 14, 0.18D, 0.18D, 0.18D, 0.05D);
			level.playSound(null, BlockPos.containing(position), SoundEvents.BUBBLE_COLUMN_BUBBLE_POP, SoundSource.PLAYERS, 0.62F, 1.12F);
		}
		removeEntity(server, state.displayId);
		removeEntity(server, state.triggerId);
	}

	private static void popFreeBalloon(MinecraftServer server, FreeBalloonState state, boolean visible) {
		if (state == null) {
			return;
		}
		ServerLevel level = server.getLevel(state.dimension);
		if (visible && level != null) {
			Vec3 position = state.position;
			level.sendParticles(ParticleTypes.POOF, position.x, position.y, position.z, 7, 0.12D, 0.12D, 0.12D, 0.02D);
			playNearbyPackSound(level, position, CONFETTI_SOUND, SoundSource.PLAYERS, 0.78F, 0.96F + level.getRandom().nextFloat() * 0.10F);
		}
		removeEntity(server, state.displayId);
		removeEntity(server, state.triggerId);
	}

	/** Pops one specific visible balloon and consumes its matching head-slot stack entry. */
	private static void popAttachedBalloon(MinecraftServer server, BalloonState state) {
		if (server == null || state == null) {
			return;
		}
		ServerLevel level = server.getLevel(state.dimension);
		Display.ItemDisplay display = findEntity(server, state.displayId, Display.ItemDisplay.class);
		Vec3 position = display == null ? null : display.position();
		ServerPlayer target = server.getPlayerList().getPlayer(state.targetId);
		if (target != null) {
			ItemStack headStack = target.getItemBySlot(EquipmentSlot.HEAD);
			if (headStack.is(ModItems.STARTUP_BALLOON)) {
				headStack.shrink(1);
				target.setItemSlot(EquipmentSlot.HEAD, headStack);
			}
		}
		BALLOONS.remove(state.displayId);
		removeAttachedBalloonVisuals(server, state);
		if (level != null && position != null) {
			level.sendParticles(ParticleTypes.POOF, position.x, position.y, position.z, 6, 0.12D, 0.12D, 0.12D, 0.02D);
			playNearbyPackSound(level, position, CONFETTI_SOUND, SoundSource.PLAYERS, 0.72F, 1.0F);
		}
		if (target != null) {
			ensureAttachedBalloonVisuals(server, target);
		}
	}

	private static Vec3 newRandomBubbleDirection(RandomSource random) {
		double angle = random.nextDouble() * Math.PI * 2.0D;
		return new Vec3(
				Math.cos(angle),
				(random.nextDouble() - 0.5D) * 1.25D,
				Math.sin(angle)
		).normalize();
	}

	private static void removeBalloons(MinecraftServer server, java.util.function.Predicate<BalloonState> predicate) {
		Iterator<BalloonState> iterator = BALLOONS.values().iterator();
		while (iterator.hasNext()) {
			BalloonState state = iterator.next();
			if (predicate.test(state)) {
				removeAttachedBalloonVisuals(server, state);
				iterator.remove();
			}
		}
	}

	private static void removeAttachedBalloonVisuals(MinecraftServer server, BalloonState state) {
		if (state == null) {
			return;
		}
		removeEntity(server, state.displayId);
		removeEntity(server, state.triggerId);
	}

	private static void removeFreeBalloons(MinecraftServer server, java.util.function.Predicate<FreeBalloonState> predicate) {
		Iterator<FreeBalloonState> iterator = FREE_BALLOONS.values().iterator();
		while (iterator.hasNext()) {
			FreeBalloonState state = iterator.next();
			if (predicate.test(state)) {
				popFreeBalloon(server, state, false);
				iterator.remove();
			}
		}
	}

	private static void removeJackBoxes(MinecraftServer server, java.util.function.Predicate<JackBoxState> predicate) {
		Iterator<JackBoxState> iterator = JACK_BOXES.values().iterator();
		while (iterator.hasNext()) {
			JackBoxState state = iterator.next();
			if (predicate.test(state)) {
				removeJackVisuals(server, state);
				iterator.remove();
			}
		}
	}

	private static void removeJackVisuals(MinecraftServer server, JackBoxState state) {
		removeEntity(server, state.boxDisplayId);
		removeEntity(server, state.lidDisplayId);
		removeEntity(server, state.clownDisplayId);
		removeEntity(server, state.rocketDisplayId);
	}

	private static void removeEntity(MinecraftServer server, UUID id) {
		Entity entity = findEntity(server, id, Entity.class);
		if (entity != null) {
			entity.discard();
		}
	}

	private static <T extends Entity> T findEntity(MinecraftServer server, UUID id, Class<T> type) {
		if (server == null || id == null) {
			return null;
		}
		for (ServerLevel level : server.getAllLevels()) {
			Entity entity = level.getEntity(id);
			if (type.isInstance(entity)) {
				return type.cast(entity);
			}
		}
		return null;
	}

	private static void playNearbyPackSound(ServerLevel level, Vec3 position, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) {
		long seed = level.getRandom().nextLong();
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(position) > 48.0D * 48.0D) {
				continue;
			}
			if (PolymerResourcePackUtils.hasMainPack(player)) {
				player.connection.send(new ClientboundSoundPacket(sound, source, position.x, position.y, position.z, volume, pitch, seed));
			} else {
				player.connection.send(new ClientboundSoundPacket(
						BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST),
						source,
						position.x,
						position.y,
						position.z,
						volume,
						pitch,
						seed
				));
			}
		}
	}

	private static void playPersonalPackSound(ServerPlayer player, Holder<SoundEvent> sound, SoundSource source, Vec3 position, float volume, float pitch) {
		if (player == null || player.connection == null) {
			return;
		}
		Holder<SoundEvent> selected = PolymerResourcePackUtils.hasMainPack(player)
				? sound
				: BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.WARDEN_ROAR);
		player.connection.send(new ClientboundSoundPacket(
				selected,
				source,
				position.x,
				position.y,
				position.z,
				volume,
				pitch,
				player.level().getRandom().nextLong()
		));
	}

	private static final class BubbleState {
		private final ResourceKey<Level> dimension;
		private final UUID casterId;
		private final UUID displayId;
		private final UUID triggerId;
		private final long expiresAtTick;
		private Vec3 position;
		private Vec3 velocity;
		private Vec3 steering;
		private double targetSpeed;
		private long nextDirectionChangeTick;
		private final float hitRadius;

		private BubbleState(
				ResourceKey<Level> dimension,
				UUID casterId,
				UUID displayId,
				UUID triggerId,
				Vec3 position,
				Vec3 velocity,
				Vec3 steering,
				float hitRadius,
				long nextDirectionChangeTick,
				long expiresAtTick
		) {
			this.dimension = dimension;
			this.casterId = casterId;
			this.displayId = displayId;
			this.triggerId = triggerId;
			this.position = position;
			this.velocity = velocity;
			this.steering = steering;
			this.hitRadius = hitRadius;
			this.targetSpeed = Math.min(0.13D, velocity.length());
			this.nextDirectionChangeTick = nextDirectionChangeTick;
			this.expiresAtTick = expiresAtTick;
		}
	}

	private static final class ConfettiState {
		private final ResourceKey<Level> dimension;
		private final UUID displayId;
		private final float pitch;
		private final float yaw;
		private final float roll;
		private final float scale;
		private final long createdAtTick;
		private final long expiresAtTick;
		private Vec3 position;
		private Vec3 velocity;

		private ConfettiState(
				ResourceKey<Level> dimension,
				UUID displayId,
				Vec3 position,
				Vec3 velocity,
				float pitch,
				float yaw,
				float roll,
				float scale,
				long createdAtTick,
				long expiresAtTick
		) {
			this.dimension = dimension;
			this.displayId = displayId;
			this.position = position;
			this.velocity = velocity;
			this.pitch = pitch;
			this.yaw = yaw;
			this.roll = roll;
			this.scale = scale;
			this.createdAtTick = createdAtTick;
			this.expiresAtTick = expiresAtTick;
		}
	}

	private static final class BalloonState {
		private final ResourceKey<Level> dimension;
		private final UUID ownerId;
		private final UUID targetId;
		private final UUID displayId;
		private final UUID triggerId;
		private final long createdAtTick;
		private final float size;

		private BalloonState(ResourceKey<Level> dimension, UUID ownerId, UUID targetId, UUID displayId, UUID triggerId, long createdAtTick, float size) {
			this.dimension = dimension;
			this.ownerId = ownerId;
			this.targetId = targetId;
			this.displayId = displayId;
			this.triggerId = triggerId;
			this.createdAtTick = createdAtTick;
			this.size = size;
		}
	}

	private static final class FreeBalloonState {
		private final ResourceKey<Level> dimension;
		private final UUID ownerId;
		private final UUID displayId;
		private final UUID triggerId;
		private final float size;
		private final long expiresAtTick;
		private Vec3 position;
		private Vec3 velocity;

		private FreeBalloonState(
				ResourceKey<Level> dimension,
				UUID ownerId,
				UUID displayId,
				UUID triggerId,
				Vec3 position,
				Vec3 velocity,
				float size,
				long expiresAtTick
		) {
			this.dimension = dimension;
			this.ownerId = ownerId;
			this.displayId = displayId;
			this.triggerId = triggerId;
			this.position = position;
			this.velocity = velocity;
			this.size = size;
			this.expiresAtTick = expiresAtTick;
		}
	}

	private static final class JackBoxState {
		private final ResourceKey<Level> dimension;
		private final UUID ownerId;
		private final Vec3 position;
		private final UUID boxDisplayId;
		private final UUID lidDisplayId;
		private final long createdAtTick;
		private boolean armed;
		private long triggeredAtTick;
		private UUID clownDisplayId;
		private UUID rocketDisplayId;
		private JackSurprise surprise;
		private JackFireworkPattern fireworkPattern;
		private boolean fireworkExploded;

		private JackBoxState(ResourceKey<Level> dimension, UUID ownerId, Vec3 position, UUID boxDisplayId, UUID lidDisplayId, long createdAtTick) {
			this.dimension = dimension;
			this.ownerId = ownerId;
			this.position = position;
			this.boxDisplayId = boxDisplayId;
			this.lidDisplayId = lidDisplayId;
			this.createdAtTick = createdAtTick;
		}
	}

	private enum JackSurprise {
		SCREAMER,
		FIREWORK
	}

	private enum JackFireworkPattern {
		BURST,
		RING,
		STAR,
		SPIRAL;

		private static JackFireworkPattern random(RandomSource random) {
			JackFireworkPattern[] values = values();
			return values[random.nextInt(values.length)];
		}
	}
}
