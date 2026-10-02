package com.lostglade.mixin;

import com.lostglade.server.DroneSystem;
import com.lostglade.server.AccountAuthSystem;
import com.lostglade.server.RendererBotCameraSystem;
import com.lostglade.server.RendererBotPresenceSystem;
import com.lostglade.server.ServerMilkPocketDimensionSystem;
import com.lostglade.server.SeasonStartSystem;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(ChunkMap.class)
public abstract class ChunkMapVirtualCameraTrackingMixin {
	@Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
	private void lg2$useVirtualCameraChunkTracking(ServerPlayer player, CallbackInfo ci) {
		if (AccountAuthSystem.isAuthTransitioning(player)) {
			return;
		}

		if (AccountAuthSystem.isInLimbo(player)) {
			ChunkTrackingView desiredView = AccountAuthSystem.createLimboChunkTrackingView(player);
			if (!Objects.equals(player.getChunkTrackingView(), desiredView)) {
				((ChunkMapChunkTrackingInvoker) (Object) this).lg2$applyChunkTrackingView(player, desiredView);
			}
			ci.cancel();
			return;
		}

		if (AccountAuthSystem.shouldBlockPacket(player)) {
			ChunkTrackingView desiredView = ChunkTrackingView.EMPTY;
			if (!Objects.equals(player.getChunkTrackingView(), desiredView)) {
				((ChunkMapChunkTrackingInvoker) (Object) this).lg2$applyChunkTrackingView(player, desiredView);
			}
			ci.cancel();
			return;
		}

		if (RendererBotPresenceSystem.isRendererBot(player)) {
			ChunkTrackingView desiredView = RendererBotCameraSystem.createVirtualChunkTrackingView(player);
			if (Objects.equals(player.getChunkTrackingView(), desiredView)) {
				ci.cancel();
				return;
			}
			((ChunkMapChunkTrackingInvoker) (Object) this).lg2$applyChunkTrackingView(player, desiredView);
			ci.cancel();
			return;
		}

		if (ServerMilkPocketDimensionSystem.shouldUsePocketChunkTracking(player)) {
			ChunkTrackingView desiredView = ServerMilkPocketDimensionSystem.createPocketChunkTrackingView(player);
			if (Objects.equals(player.getChunkTrackingView(), desiredView)) {
				ci.cancel();
				return;
			}
			((ChunkMapChunkTrackingInvoker) (Object) this).lg2$applyChunkTrackingView(player, desiredView);
			ci.cancel();
			return;
		}

		if (SeasonStartSystem.shouldUseStartupChunkTracking(player)) {
			ChunkTrackingView desiredView = SeasonStartSystem.createStartupChunkTrackingView(player);
			if (Objects.equals(player.getChunkTrackingView(), desiredView)) {
				ci.cancel();
				return;
			}
			((ChunkMapChunkTrackingInvoker) (Object) this).lg2$applyChunkTrackingView(player, desiredView);
			ci.cancel();
			return;
		}

		if (!DroneSystem.isControllingDrone(player)) {
			return;
		}

		ChunkTrackingView desiredView = DroneSystem.createVirtualChunkTrackingView(player);
		if (Objects.equals(player.getChunkTrackingView(), desiredView)) {
			ci.cancel();
			return;
		}

		((ChunkMapChunkTrackingInvoker) (Object) this).lg2$applyChunkTrackingView(player, desiredView);
		ci.cancel();
	}
}
