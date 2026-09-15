package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.util.ItemDisplayHitboxHelper;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.Brightness;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class OrthodoxAttackSystem {
	private static final double EYE_HEIGHT_OFFSET_BLOCKS = 140.0D;
	private static final float OPEN_STEP = 0.05F;
	private static final int CLOSE_HOLD_TICKS = 2;
	private static final long ACTIVATION_OVERLAY_TICKS = 20L;
	private static final Identifier EYE_OPEN_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "orthodox_eye_open");
	private static final Identifier EYE_CLOSE_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "orthodox_eye_close");
	private static final Holder<SoundEvent> EYE_OPEN_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(EYE_OPEN_SOUND_ID));
	private static final Holder<SoundEvent> EYE_CLOSE_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(EYE_CLOSE_SOUND_ID));
	private static final Set<Relative> ABSOLUTE_TELEPORT = EnumSet.noneOf(Relative.class);
	private static final Map<UUID, DivineGazeSession> SESSIONS = new HashMap<>();

	private OrthodoxAttackSystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(OrthodoxAttackSystem::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> removeViewer(handler.player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPING.register(OrthodoxAttackSystem::clearAll);
	}

	public static boolean isObserving(UUID casterId) {
		DivineGazeSession session = casterId == null ? null : SESSIONS.get(casterId);
		return session != null && !session.terminating;
	}

	public static boolean activate(
			ServerPlayer caster,
			ServerPlayer target,
			long durationTicks,
			double visibilityRadius,
			double remainingHealthHearts,
			long blindnessTicks
	) {
		if (caster == null || target == null || caster == target || durationTicks <= 0L || visibilityRadius <= 0.0D) {
			return false;
		}
		if (!isGazeDimension(target.level())) return false;
		DivineGazeSession old = SESSIONS.remove(caster.getUUID());
		if (old != null) {
			MinecraftServer server = caster.level().getServer();
			ServerPlayer oldTarget = server == null ? null : server.getPlayerList().getPlayer(old.targetId);
			if (old.soundOpen) playEyeTransitionSound(oldTarget, false);
			clearViews(old);
		}
		DivineGazeSession session = new DivineGazeSession(
				caster.getUUID(),
				target.getUUID(),
				durationTicks,
				visibilityRadius,
				Math.max(0.0D, remainingHealthHearts) * 2.0D,
				Math.max(1L, blindnessTicks),
				target.position(),
				calculateEyeY(target),
				caster.level().getServer().overworld().getGameTime() + ACTIVATION_OVERLAY_TICKS
		);
		session.soundOpen = true;
		SESSIONS.put(caster.getUUID(), session);
		ServerLevel level = target.level();
		playEyeTransitionSound(target, true);
		spawnActivationParticleEye(level, target, caster);
		PuroSanStockSystem.showAimWarningOverlayOnce(target);
		return true;
	}

	private static void spawnActivationParticleEye(ServerLevel level, ServerPlayer target, ServerPlayer caster) {
		Vec3 center = target.position().add(0.0D, target.getBbHeight() + 0.65D, 0.0D);
		Vec3 towardCaster = caster.position().subtract(target.position()).multiply(1.0D, 0.0D, 1.0D);
		if (towardCaster.lengthSqr() < 1.0E-6D) {
			double yaw = Math.toRadians(target.getYRot());
			towardCaster = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
		} else {
			towardCaster = towardCaster.normalize();
		}
		Vec3 horizontal = new Vec3(towardCaster.z, 0.0D, -towardCaster.x);

		int lidSteps = 10;
		for (int index = 0; index <= lidSteps; index++) {
			double progress = index / (double) lidSteps;
			double x = (progress * 2.0D - 1.0D) * 0.58D;
			double curve = Math.sin(progress * Math.PI) * 0.25D;
			spawnEndRodPoint(level, center.add(horizontal.scale(x)).add(0.0D, curve, 0.0D));
			if (index > 0 && index < lidSteps) {
				spawnEndRodPoint(level, center.add(horizontal.scale(x)).add(0.0D, -curve, 0.0D));
			}
		}
		for (int index = 0; index < 8; index++) {
			double angle = Math.PI * 2.0D * index / 8.0D;
			Vec3 irisPoint = center
					.add(horizontal.scale(Math.cos(angle) * 0.11D))
					.add(0.0D, Math.sin(angle) * 0.11D, 0.0D)
					.add(towardCaster.scale(0.015D));
			spawnEndRodPoint(level, irisPoint);
		}
		spawnEndRodPoint(level, center.add(towardCaster.scale(0.025D)));
	}

	private static void spawnEndRodPoint(ServerLevel level, Vec3 point) {
		level.sendParticles(ParticleTypes.END_ROD, point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
	}

	public static void onSuccessfulDamage(ServerLevel level, LivingEntity victim, DamageSource source, float amount) {
		if (!isGazeDimension(level) || victim == null || source == null || amount <= 0.0F || SESSIONS.isEmpty()) return;
		Entity attacker = source.getEntity();
		if (!(attacker instanceof LivingEntity) || attacker == victim) return;
		UUID victimId = victim.getUUID();
		UUID attackerId = attacker.getUUID();
		for (DivineGazeSession session : SESSIONS.values()) {
			if (session.terminating) continue;
			if (session.targetId.equals(victimId)) {
				session.attackedFirstByTarget.putIfAbsent(attackerId, false);
			} else if (session.targetId.equals(attackerId)) {
				session.attackedFirstByTarget.putIfAbsent(victimId, true);
			}
		}
	}

	public static void onLivingDeath(ServerLevel level, LivingEntity victim, DamageSource source) {
		if (!isGazeDimension(level) || victim == null || source == null || SESSIONS.isEmpty()) return;
		Entity killer = source.getEntity();
		if (!(killer instanceof ServerPlayer watchedPlayer)) return;
		List<DivineGazeSession> violations = new ArrayList<>();
		for (DivineGazeSession session : SESSIONS.values()) {
			if (session.terminating || !session.targetId.equals(watchedPlayer.getUUID())) continue;
			boolean targetAttackedFirst = session.attackedFirstByTarget.getOrDefault(victim.getUUID(), true);
			if (targetAttackedFirst) violations.add(session);
		}
		if (violations.isEmpty()) return;
		punish(level, watchedPlayer, violations.getFirst());
		for (DivineGazeSession session : violations) session.terminating = true;
	}

	private static void tick(MinecraftServer server) {
		if (server == null || SESSIONS.isEmpty()) return;
		long nowTick = server.overworld().getGameTime();
		Iterator<DivineGazeSession> iterator = SESSIONS.values().iterator();
		while (iterator.hasNext()) {
			DivineGazeSession session = iterator.next();
			ServerPlayer target = server.getPlayerList().getPlayer(session.targetId);
			boolean activeWorld = target != null && target.isAlive() && isGazeDimension(target.level());
			if (!session.activationOverlayRestored && target != null && nowTick >= session.activationOverlayRestoreTick) {
				PuroSanStockSystem.restoreScreenOverlayAfterAimWarning(target);
				session.activationOverlayRestored = true;
			}

			if (!session.terminating && activeWorld) {
				session.remainingTicks--;
				session.lastTargetPosition = target.position();
				session.lastEyeY = calculateEyeY(target);
				if (session.remainingTicks <= 0L) session.terminating = true;
			}
			float desiredOpen = !session.terminating && activeWorld ? 1.0F : 0.0F;
			boolean shouldSoundOpen = desiredOpen > 0.5F;
			if (session.soundOpen != shouldSoundOpen) {
				playEyeTransitionSound(target, shouldSoundOpen);
				session.soundOpen = shouldSoundOpen;
			}
			session.openProgress = approach(session.openProgress, desiredOpen, OPEN_STEP);
			updateViews(server, session, activeWorld ? target : null);

			if (session.terminating && session.openProgress <= 0.001F) session.closedTicks++;
			if (session.closedTicks > CLOSE_HOLD_TICKS) {
				clearViews(session);
				iterator.remove();
			}
		}
	}

	private static void updateViews(MinecraftServer server, DivineGazeSession session, ServerPlayer target) {
		if (target != null) {
			for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
				if (viewer.level() != target.level()) continue;
				double dx = viewer.getX() - target.getX();
				double dz = viewer.getZ() - target.getZ();
				double distance = Math.sqrt(dx * dx + dz * dz);
				if (distance <= session.visibilityRadius || session.views.containsKey(viewer.getUUID())) {
					EyeView view = session.views.computeIfAbsent(viewer.getUUID(), id -> spawnView(viewer, target, session));
					if (view != null) updateView(viewer, view, session, target.position(), distance <= session.visibilityRadius);
				}
			}
		}

		Iterator<Map.Entry<UUID, EyeView>> views = session.views.entrySet().iterator();
		while (views.hasNext()) {
			Map.Entry<UUID, EyeView> entry = views.next();
			ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
			EyeView view = entry.getValue();
			boolean validDimension = viewer != null && viewer.level().dimension().equals(view.level.dimension());
			if (!validDimension) {
				if (viewer != null) removeView(viewer, view);
				views.remove();
				continue;
			}
			if (target == null || viewer.level() != target.level()) {
				updateView(viewer, view, session, session.lastTargetPosition, false);
			}
			if (view.closedTicks > CLOSE_HOLD_TICKS && (target == null || viewer.level() != target.level()
					|| horizontalDistance(viewer, target) > session.visibilityRadius)) {
				removeView(viewer, view);
				views.remove();
			}
		}
	}

	private static EyeView spawnView(ServerPlayer viewer, ServerPlayer target, DivineGazeSession session) {
		if (viewer == null || target == null || viewer.connection == null) return null;
		List<Display.ItemDisplay> displays = new ArrayList<>(OrthodoxEyeComposition.PART_COUNT);
		for (int part = 0; part < OrthodoxEyeComposition.PART_COUNT; part++) {
			Display.ItemDisplay display = createDisplay(target.level(), session.composition.model(part));
			display.setPos(target.getX(), session.lastEyeY, target.getZ());
			display.setTransformation(session.composition.transformation(part, 1.0F, 0.0F));
			sendSpawn(viewer, display);
			display.getEntityData().packDirty();
			displays.add(display);
		}
		EyeView view = new EyeView(displays, target.level());
		return view;
	}

	private static Display.ItemDisplay createDisplay(ServerLevel level, String model) {
		Display.ItemDisplay display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		display.setItemStack(createEyeStack(model));
		display.setItemTransform(ItemDisplayContext.FIXED);
		display.setBillboardConstraints(Display.BillboardConstraints.FIXED);
		display.setNoGravity(true);
		display.setInvulnerable(true);
		display.setSilent(true);
		display.setShadowRadius(0.0F);
		display.setShadowStrength(0.0F);
		display.setBrightnessOverride(Brightness.FULL_BRIGHT);
		display.setViewRange(1_000_000.0F);
		display.setPosRotInterpolationDuration(2);
		display.setTransformationInterpolationDelay(0);
		display.setTransformationInterpolationDuration(2);
		// Zero display dimensions disable frustum culling for the off-center ray tips.
		ItemDisplayHitboxHelper.clear(display);
		return display;
	}

	private static void updateView(ServerPlayer viewer, EyeView view, DivineGazeSession session, Vec3 targetPosition, boolean withinRadius) {
		if (viewer == null || view == null || targetPosition == null) return;
		view.openProgress = approach(view.openProgress, withinRadius ? 1.0F : 0.0F, OPEN_STEP);
		view.closedTicks = !withinRadius && view.openProgress <= 0.001F ? view.closedTicks + 1 : 0;
		float renderedOpen = Math.min(session.openProgress, view.openProgress);
		boolean transformChanged = view.lastRenderedOpen != renderedOpen;
		view.lastRenderedOpen = renderedOpen;
		for (int part = 0; part < view.displays.size(); part++) {
			Display.ItemDisplay display = view.displays.get(part);
			boolean moved = display.getX() != targetPosition.x || display.getY() != session.lastEyeY || display.getZ() != targetPosition.z;
			if (moved) display.setPos(targetPosition.x, session.lastEyeY, targetPosition.z);
			if (transformChanged) {
				display.setTransformation(session.composition.transformation(part, 1.0F, renderedOpen));
				display.setTransformationInterpolationDelay(0);
			}
			if (moved || transformChanged) sendFrame(viewer, display, moved);
		}
	}

	private static double calculateEyeY(ServerPlayer target) {
		int blockX = Mth.floor(target.getX());
		int blockZ = Mth.floor(target.getZ());
		int surfaceY = target.level().getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
		double anchorY = target.getY() >= surfaceY - 0.01D ? target.getY() : surfaceY;
		return anchorY + EYE_HEIGHT_OFFSET_BLOCKS;
	}

	private static boolean isGazeDimension(Level level) {
		return level != null && (Level.OVERWORLD.equals(level.dimension()) || Level.END.equals(level.dimension()));
	}

	private static void playEyeTransitionSound(ServerPlayer target, boolean opening) {
		if (target == null || target.connection == null) return;
		boolean hasPack = PolymerResourcePackUtils.hasMainPack(target);
		Holder<SoundEvent> sound = hasPack
				? (opening ? EYE_OPEN_SOUND : EYE_CLOSE_SOUND)
				: BuiltInRegistries.SOUND_EVENT.wrapAsHolder(opening ? SoundEvents.BEACON_ACTIVATE : SoundEvents.BEACON_DEACTIVATE);
		target.connection.send(new ClientboundSoundPacket(
				sound,
				SoundSource.PLAYERS,
				target.getX(),
				target.getY(),
				target.getZ(),
				1.0F,
				1.0F,
				target.getRandom().nextLong()
		));
	}

	private static double horizontalDistance(ServerPlayer viewer, ServerPlayer target) {
		double dx = viewer.getX() - target.getX();
		double dz = viewer.getZ() - target.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static void punish(ServerLevel level, ServerPlayer target, DivineGazeSession session) {
		float remainingHealth = (float) Math.max(0.1D, session.remainingHealthPoints);
		if (target.getHealth() > remainingHealth) {
			float damage = target.getHealth() - remainingHealth;
			target.hurtServer(level, level.damageSources().magic(), damage);
			if (target.isAlive() && target.getHealth() > remainingHealth) target.setHealth(remainingHealth);
		}
		int blindnessDuration = (int) Math.min(Integer.MAX_VALUE, session.blindnessTicks);
		target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, blindnessDuration, 1, false, true, true));
		level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFFFFF), target.getX(), target.getY() + target.getBbHeight() * 0.6D, target.getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
		level.sendParticles(ParticleTypes.END_ROD, target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(), 90, 0.8D, 1.0D, 0.8D, 0.08D);
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(), 60, 0.65D, 0.9D, 0.65D, 0.12D);
		level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 1.8F, 1.55F);
		level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 1.2F, 1.75F);
	}

	private static ItemStack createEyeStack(String model) {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(Lg2.MOD_ID, model));
		return stack;
	}

	@SuppressWarnings("unchecked")
	private static void sendSpawn(ServerPlayer viewer, Display.ItemDisplay display) {
		ServerEntity tracker = new ServerEntity((ServerLevel) display.level(), display, 1, false, NOOP_SYNCHRONIZER);
		tracker.sendPairingData(viewer, packet -> viewer.connection.send((Packet<? super ClientGamePacketListener>) packet));
		List<SynchedEntityData.DataValue<?>> values = display.getEntityData().getNonDefaultValues();
		if (values != null && !values.isEmpty()) viewer.connection.send(new ClientboundSetEntityDataPacket(display.getId(), values));
	}

	private static void sendFrame(ServerPlayer viewer, Display.ItemDisplay display, boolean moved) {
		if (viewer.connection == null) return;
		if (moved) {
			PositionMoveRotation pose = new PositionMoveRotation(display.position(), Vec3.ZERO, 0.0F, 0.0F);
			viewer.connection.send(ClientboundTeleportEntityPacket.teleport(display.getId(), pose, ABSOLUTE_TELEPORT, false));
		}
		List<SynchedEntityData.DataValue<?>> values = display.getEntityData().packDirty();
		if (values != null && !values.isEmpty()) viewer.connection.send(new ClientboundSetEntityDataPacket(display.getId(), values));
	}

	private static void removeView(ServerPlayer viewer, EyeView view) {
		if (viewer != null && viewer.connection != null && view != null) {
			viewer.connection.send(new ClientboundRemoveEntitiesPacket(view.displays.stream().mapToInt(Entity::getId).toArray()));
		}
	}

	private static void removeViewer(UUID viewerId) {
		if (viewerId == null) return;
		for (DivineGazeSession session : SESSIONS.values()) session.views.remove(viewerId);
	}

	private static void clearViews(DivineGazeSession session) {
		if (session == null) return;
		MinecraftServer server = session.views.values().stream()
				.map(view -> view.level.getServer())
				.filter(java.util.Objects::nonNull)
				.findFirst().orElse(null);
		if (server != null) {
			for (Map.Entry<UUID, EyeView> entry : session.views.entrySet()) {
				removeView(server.getPlayerList().getPlayer(entry.getKey()), entry.getValue());
			}
		}
		session.views.clear();
	}

	private static void clearAll(MinecraftServer server) {
		for (DivineGazeSession session : SESSIONS.values()) clearViews(session);
		SESSIONS.clear();
	}

	private static float approach(float value, float target, float step) {
		if (value < target) return Math.min(target, value + step);
		return Math.max(target, value - step);
	}

	private static final class DivineGazeSession {
		private final UUID casterId;
		private final UUID targetId;
		private long remainingTicks;
		private final double visibilityRadius;
		private final double remainingHealthPoints;
		private final long blindnessTicks;
		private final Map<UUID, Boolean> attackedFirstByTarget = new HashMap<>();
		private final Map<UUID, EyeView> views = new HashMap<>();
		private final OrthodoxEyeComposition composition;
		private Vec3 lastTargetPosition;
		private double lastEyeY;
		private final long activationOverlayRestoreTick;
		private float openProgress;
		private int closedTicks;
		private boolean soundOpen;
		private boolean terminating;
		private boolean activationOverlayRestored;

		private DivineGazeSession(UUID casterId, UUID targetId, long remainingTicks, double visibilityRadius,
				double remainingHealthPoints, long blindnessTicks, Vec3 lastTargetPosition, double lastEyeY,
				long activationOverlayRestoreTick) {
			this.casterId = casterId;
			this.targetId = targetId;
			this.composition = new OrthodoxEyeComposition(casterId.getMostSignificantBits() ^ targetId.getLeastSignificantBits() ^ activationOverlayRestoreTick);
			this.remainingTicks = remainingTicks;
			this.visibilityRadius = visibilityRadius;
			this.remainingHealthPoints = remainingHealthPoints;
			this.blindnessTicks = blindnessTicks;
			this.lastTargetPosition = lastTargetPosition;
			this.lastEyeY = lastEyeY;
			this.activationOverlayRestoreTick = activationOverlayRestoreTick;
		}
	}

	private static final class EyeView {
		private final List<Display.ItemDisplay> displays;
		private float openProgress;
		private int closedTicks;
		private float lastRenderedOpen = -1.0F;
		private final ServerLevel level;

		private EyeView(List<Display.ItemDisplay> displays, ServerLevel level) {
			this.displays = displays;
			this.level = level;
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
