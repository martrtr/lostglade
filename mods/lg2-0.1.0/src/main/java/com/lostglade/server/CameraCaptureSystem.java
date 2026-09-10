package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.config.Lg2Config;
import com.lostglade.item.CameraPhotoSettings;
import com.lostglade.item.ModItems;
import com.lostglade.server.map.MapImageRenderSystem;
import com.lostglade.server.map.MapPixelProvider;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CameraCaptureSystem {
	private static final String IT_CAMERA = "it_camera";
	private static final Identifier CAMERA_SHUTTER_SOUND_ID = Identifier.fromNamespaceAndPath("lg2", "camera_shutter");
	private static final Holder<SoundEvent> CAMERA_SHUTTER_SOUND = Holder.direct(SoundEvent.createVariableRangeEvent(CAMERA_SHUTTER_SOUND_ID));
	private static final int CAMERA_COOLDOWN_TICKS = 40;
	private static final int USE_SWING_SUPPRESSION_TICKS = 2;
	private static final double MAX_SHUTTER_SOUND_DISTANCE_SQR = 24.0D * 24.0D;
	private static final float SHUTTER_SOUND_VOLUME = 0.45F;
	private static final float SHUTTER_SOUND_PITCH = 1.0F;
	private static final long TICKS_PER_DAY = 24_000L;
	private static final long TICKS_PER_HOUR = 1_000L;
	private static final long MINUTES_PER_DAY = 24L * 60L;
	private static final Map<UUID, Integer> SUPPRESSED_SWING_UNTIL_TICK = new ConcurrentHashMap<>();

	private CameraCaptureSystem() {
	}

	public static void register() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			if (!isLeftClickCameraTrigger(serverPlayer, hand)) {
				return InteractionResult.PASS;
			}
			return tryCapture(serverPlayer, serverPlayer.getItemInHand(hand))
					? InteractionResult.SUCCESS
					: InteractionResult.PASS;
		});
		AttackEntityCallback.EVENT.register(CameraCaptureSystem::onAttackEntity);
	}

	public static boolean handleLeftClickAir(ServerPlayer player, InteractionHand hand) {
		if (DroneSystem.isCameraBlockedByDroneControl(player)) {
			return false;
		}
		if (!isLeftClickCameraTrigger(player, hand)) {
			return false;
		}
		if (isSuppressedByRecentUse(player, hand)) {
			return false;
		}
		if (hasAttackTarget(player)) {
			return false;
		}
		return tryCapture(player, player.getItemInHand(hand));
	}

	public static void suppressNextCameraSwing(ServerPlayer player, InteractionHand hand) {
		if (player == null || hand != InteractionHand.MAIN_HAND) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		SUPPRESSED_SWING_UNTIL_TICK.put(player.getUUID(), server.getTickCount() + USE_SWING_SUPPRESSION_TICKS);
	}

	public static boolean tryCapture(ServerPlayer player, ItemStack stack) {
		if (DroneSystem.isCameraBlockedByDroneControl(player)) {
			return false;
		}
		if (player == null || stack == null || stack.isEmpty() || !stack.is(ModItems.CAMERA)) {
			return false;
		}
		if (!ServerUpgradeUiSystem.hasUpgrade(player, IT_CAMERA)) {
			String upgradeName = ServerUpgradeUiSystem.getUpgradeDisplayName(player, IT_CAMERA);
			Lg2Messages.actionBar(
					player,
					"message.lg2.camera.upgrade_locked",
					upgradeName == null || upgradeName.isBlank() ? "Camera" : upgradeName
			);
			return false;
		}
		CameraPhotoSettings settings = CameraPhotoSettings.read(stack);
		if (settings.isVideoMode()) {
			if (MapImageRenderSystem.hasActiveRender(player.getUUID())) {
				player.displayClientMessage(cameraBusyMessage(player), true);
				return false;
			}
			return CameraVideoRecordingSystem.toggleRecording(player, settings);
		}
		if (MapImageRenderSystem.hasActiveRender(player.getUUID())) {
			player.displayClientMessage(cameraBusyMessage(player), true);
			return false;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null || !RendererBotCameraSystem.hasReadyBot(server)) {
			player.displayClientMessage(noActiveRendererClientMessage(player), true);
			return false;
		}
		MapPixelProvider provider;
		try {
			provider = createPixelProvider(player, settings);
		} catch (Exception exception) {
			player.displayClientMessage(capturePrepareFailedMessage(player), true);
			return false;
		}
		boolean started = MapImageRenderSystem.startRender(player, createCompletedPhotoName(player.level().getServer()), provider);
		if (!started) {
			return false;
		}

		player.getCooldowns().addCooldown(stack, CAMERA_COOLDOWN_TICKS);
		return true;
	}

	public static Component createQueuedPhotoName(int progressPercent) {
		int clamped = Mth.clamp(progressPercent, 0, 100);
		return Component.literal(clamped + "%").withStyle(style -> style.withItalic(false));
	}

	public static Component createCompletedPhotoName(MinecraftServer server) {
		long dayTime = 0L;
		if (server != null && server.overworld() != null) {
			dayTime = Math.max(0L, server.overworld().getDayTime());
		}
		long dayNumber = Math.floorDiv(dayTime, TICKS_PER_DAY) + 1L;
		long ticksInDay = Math.floorMod(dayTime, TICKS_PER_DAY);
		int hour = (int) ((ticksInDay / TICKS_PER_HOUR + 6L) % 24L);
		int minute = (int) (((ticksInDay % TICKS_PER_HOUR) * MINUTES_PER_DAY) / TICKS_PER_DAY);
		String timestamp = String.format(Locale.ROOT, "%d - %02d:%02d", dayNumber, hour, minute);
		return Component.literal(timestamp).withStyle(style -> style.withItalic(false));
	}

	private static Component cameraBusyMessage(ServerPlayer player) {
		return Lg2Messages.tr("message.lg2.camera.busy");
	}

	private static Component capturePrepareFailedMessage(ServerPlayer player) {
		return Lg2Messages.tr("message.lg2.camera.prepare_failed");
	}

	private static Component noActiveRendererClientMessage(ServerPlayer player) {
		return Lg2Messages.tr("message.lg2.camera.no_renderer");
	}

	public static Component captureCompletedMessage(ServerPlayer player) {
		return Lg2Messages.tr("message.lg2.camera.capture_completed");
	}

	/**
	 * Called after the renderer client has captured the final photo frame, rather
	 * than when the player merely presses the camera button.
	 */
	public static void notifyPhotoCaptured(ServerPlayer player) {
		notifyPhotoCaptured(player, null);
	}

	/**
	 * Confirms a photo from the position where its final camera frame was taken.
	 * The optional origin keeps the shutter sound aligned with a frozen shot even
	 * when its owner has already turned around or walked away.
	 */
	public static void notifyPhotoCaptured(ServerPlayer player, Vec3 shutterOrigin) {
		if (player == null || !(player.level() instanceof ServerLevel)) {
			return;
		}
		Lg2Messages.actionBar(player, "message.lg2.camera.captured");
		playShutterFeedback(player, shutterOrigin);
	}

	/** Plays the same shutter sound without changing the player's photo status. */
	public static void notifyShutterCaptured(ServerPlayer player) {
		if (player == null || !(player.level() instanceof ServerLevel)) {
			return;
		}
		playShutterFeedback(player);
	}

	public static Component queuedForRenderMessage(ServerPlayer player) {
		return Lg2Messages.tr("message.lg2.camera.queued");
	}

	public static Component addedToRenderQueueMessage(ServerPlayer player) {
		return Lg2Messages.tr("message.lg2.camera.queued_more");
	}

	private static void playShutterFeedback(ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		playShutterFeedback(player, player.getEyePosition().add(player.getLookAngle().normalize().scale(0.35D)));
	}

	private static void playShutterFeedback(ServerPlayer player, Vec3 shutterOrigin) {
		ServerLevel level = (ServerLevel) player.level();
		Vec3 origin = shutterOrigin != null
				? shutterOrigin
				: player.getEyePosition().add(player.getLookAngle().normalize().scale(0.35D));
		long seed = level.getRandom().nextLong();

		for (ServerPlayer viewer : level.players()) {
			if (viewer.distanceToSqr(origin.x, origin.y, origin.z) > MAX_SHUTTER_SOUND_DISTANCE_SQR) {
				continue;
			}

			Holder<SoundEvent> sound = PolymerResourcePackUtils.hasMainPack(viewer)
					? CAMERA_SHUTTER_SOUND
					: BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.UI_BUTTON_CLICK.value());
			float pitch = PolymerResourcePackUtils.hasMainPack(viewer) ? SHUTTER_SOUND_PITCH : 1.2F;
			viewer.connection.send(new ClientboundSoundPacket(sound, SoundSource.PLAYERS, origin.x, origin.y, origin.z, SHUTTER_SOUND_VOLUME, pitch, seed));
		}
	}

	private static MapPixelProvider createPixelProvider(ServerPlayer player, CameraPhotoSettings settings) {
		RendererBotCameraSystem.ClientCaptureHandle captureHandle = RendererBotCameraSystem.requestPhotoCapture(
				player,
				settings.mapsWide(),
				settings.mapsHigh()
		);
		if (captureHandle == null) {
			throw new IllegalStateException("No active renderer client is available");
		}
		return new RendererBotPixelProvider(
				player.getUUID(),
				player.level().dimension(),
				settings.mapsWide(),
				settings.mapsHigh(),
				captureHandle
		);
	}

	private static InteractionResult onAttackEntity(
			net.minecraft.world.entity.player.Player player,
			Level world,
			InteractionHand hand,
			Entity entity,
			EntityHitResult hitResult
	) {
		if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (!isLeftClickCameraTrigger(serverPlayer, hand)) {
			return InteractionResult.PASS;
		}
		return tryCapture(serverPlayer, serverPlayer.getItemInHand(hand))
				? InteractionResult.SUCCESS
				: InteractionResult.PASS;
	}

	private static boolean isLeftClickCameraTrigger(ServerPlayer player, InteractionHand hand) {
		return player != null
				&& hand == InteractionHand.MAIN_HAND
				&& player.isAlive()
				&& !player.isSpectator()
				&& !DroneSystem.isCameraBlockedByDroneControl(player)
				&& player.getItemInHand(hand).is(ModItems.CAMERA);
	}

	private static boolean isSuppressedByRecentUse(ServerPlayer player, InteractionHand hand) {
		if (player == null || hand != InteractionHand.MAIN_HAND) {
			return false;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return false;
		}
		Integer untilTick = SUPPRESSED_SWING_UNTIL_TICK.get(player.getUUID());
		if (untilTick == null) {
			return false;
		}
		int currentTick = server.getTickCount();
		if (currentTick <= untilTick) {
			return true;
		}
		SUPPRESSED_SWING_UNTIL_TICK.remove(player.getUUID(), untilTick);
		return false;
	}

	private static boolean hasAttackTarget(ServerPlayer player) {
		double reach = Math.max(player.blockInteractionRange(), player.entityInteractionRange());
		HitResult hitResult = player.pick(reach, 1.0F, false);
		return hitResult != null && hitResult.getType() != HitResult.Type.MISS;
	}

	private static final class RendererBotPixelProvider implements MapPixelProvider {
		private final UUID playerId;
		private final ResourceKey<Level> dimension;
		private final int mapsWide;
		private final int mapsHigh;
		private final RendererBotCameraSystem.ClientCaptureHandle captureHandle;
		private final String sourceKey;

		private RendererBotPixelProvider(UUID playerId, ResourceKey<Level> dimension, int mapsWide, int mapsHigh, RendererBotCameraSystem.ClientCaptureHandle captureHandle) {
			this.playerId = playerId;
			this.dimension = dimension;
			this.mapsWide = mapsWide;
			this.mapsHigh = mapsHigh;
			this.captureHandle = captureHandle;
			this.sourceKey = captureHandle.requestId().toString();
		}

		@Override
		public UUID ownerId() {
			return this.playerId;
		}

		@Override
		public ResourceKey<Level> dimension() {
			return this.dimension;
		}

		@Override
		public boolean prefersWholeFrameRendering() {
			return true;
		}

		@Override
		public Object prepareFrame(MinecraftServer server) {
			return this.captureHandle;
		}

		@Override
		public byte[] renderPreparedFrame(Object preparedFrame) {
			return ((RendererBotCameraSystem.ClientCaptureHandle) preparedFrame).awaitFull();
		}

		@Override
		public byte[] renderImmediatePreviewFromPreparedFrame(Object preparedFrame) {
			return ((RendererBotCameraSystem.ClientCaptureHandle) preparedFrame).awaitPreview();
		}

		@Override
		public byte[] renderImmediatePreview(MinecraftServer server) {
			return this.captureHandle.awaitPreview();
		}

		@Override
		public boolean immediatePreviewMatchesPrimaryFrame() {
			return this.mapsWide == 1 && this.mapsHigh == 1;
		}

		@Override
		public int mapTilesWide() {
			return this.mapsWide;
		}

		@Override
		public int mapTilesHigh() {
			return this.mapsHigh;
		}

		@Override
		public String sourceKey() {
			return this.sourceKey;
		}
	}
}
