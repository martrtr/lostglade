package com.lostglade.mixin;

import com.lostglade.server.AccountAuthSystem;
import com.lostglade.server.RendererBotCameraSystem;
import com.lostglade.server.RendererBotPresenceSystem;
import com.lostglade.server.ServerWebcamIntegration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Pseudo
@Mixin(targets = "ru.dimaskama.webcam.fabric.WebcamFabric$1")
public abstract class WebcamNearbyPlayersRendererBotMixin {
	@Inject(method = "acceptForNearbyPlayers", at = @At("HEAD"), cancellable = true, remap = false)
	private void lg2$includeRendererBotsInNearbyWebcamViewers(
			UUID sourcePlayerId,
			double radius,
			Consumer<Set<UUID>> consumer,
			CallbackInfo ci
	) {
		MinecraftServer server = ServerWebcamIntegration.currentServer();
		if (server == null || sourcePlayerId == null || consumer == null) {
			ci.cancel();
			return;
		}

		ServerPlayer sourcePlayer = server.getPlayerList().getPlayer(sourcePlayerId);
		if (sourcePlayer == null) {
			ci.cancel();
			return;
		}

		Set<UUID> nearbyPlayers = new HashSet<>();
		nearbyPlayers.add(sourcePlayer.getUUID());

		try {
			Vec3 sourcePosition = sourcePlayer.position();
			double radiusSq = radius * radius;
			ServerLevel sourceLevel = sourcePlayer.level();
			for (ServerPlayer player : sourceLevel.players()) {
				if (player.position().distanceToSqr(sourcePosition) <= radiusSq) {
					nearbyPlayers.add(player.getUUID());
				}
			}
		} catch (Exception ignored) {
		}

		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			if (!RendererBotPresenceSystem.isRendererBot(player)) {
				continue;
			}
			if (RendererBotCameraSystem.shouldReceiveNearbyWebcam(player, sourcePlayer, radius)) {
				nearbyPlayers.add(player.getUUID());
			}
		}

		consumer.accept(nearbyPlayers);
		ci.cancel();
	}
}
