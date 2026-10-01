package com.lostglade.mixin;

import com.lostglade.server.ServerAbsoluteInvisibilitySystem;
import com.lostglade.server.ServerBossBarVisibilitySystem;
import com.lostglade.server.ServerTabPacketSystem;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerAbsoluteInvisibilityMixin {
	@Unique
	private static final ThreadLocal<Boolean> LG2_ABSOLUTE_INVISIBILITY_BYPASS = ThreadLocal.withInitial(() -> false);

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void lg2$filterAbsoluteInvisibilityPackets(Packet<?> packet, CallbackInfo ci) {
		lg2$processAbsoluteInvisibilityPacket(packet, null, ci);
	}

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
	private void lg2$filterAbsoluteInvisibilityPacketsWithListener(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
		lg2$processAbsoluteInvisibilityPacket(packet, listener, ci);
	}

	@Unique
	private void lg2$processAbsoluteInvisibilityPacket(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
		Object self = this;
		if (!(self instanceof ServerGamePacketListenerImpl gameListener)) {
			return;
		}

		ServerPlayer receiver = gameListener.player;
		if (receiver == null) {
			return;
		}
		if (LG2_ABSOLUTE_INVISIBILITY_BYPASS.get()) {
			return;
		}

		if (packet instanceof ClientboundPlayerInfoUpdatePacket playerInfoUpdatePacket) {
			ServerTabPacketSystem.RewriteResult rewrite = ServerTabPacketSystem.rewriteOutgoingPlayerInfoPacket(receiver, playerInfoUpdatePacket);
			if (rewrite != null) {
				ci.cancel();
				LG2_ABSOLUTE_INVISIBILITY_BYPASS.set(true);
				try {
					boolean listenerApplied = false;
					if (rewrite.packet() != null) {
						if (listener == null) {
							gameListener.send(rewrite.packet());
						} else {
							gameListener.send(rewrite.packet(), listener);
							listenerApplied = true;
						}
					}
					if (!rewrite.removedProfileIds().isEmpty()) {
						ClientboundPlayerInfoRemovePacket removePacket = new ClientboundPlayerInfoRemovePacket(rewrite.removedProfileIds());
						if (listener != null && !listenerApplied) {
							gameListener.send(removePacket, listener);
						} else {
							gameListener.send(removePacket);
						}
					}
				} finally {
					LG2_ABSOLUTE_INVISIBILITY_BYPASS.remove();
				}
				return;
			}
		}
		if (packet instanceof ClientboundSetPlayerTeamPacket playerTeamPacket) {
			ServerTabPacketSystem.stripShadowFromTeamPacket(playerTeamPacket);
		}

		if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket
				&& com.lostglade.server.ServerRaceSystem.shouldRewriteLittleDictatorIronMechanismPackets(receiver)) {
			ci.cancel();
			LG2_ABSOLUTE_INVISIBILITY_BYPASS.set(true);
			try {
				if (listener == null) {
					gameListener.send(chunkPacket);
				} else {
					gameListener.send(chunkPacket, listener);
				}
				com.lostglade.server.ServerRaceSystem.sendLittleDictatorIronMechanismChunkOverlay(receiver, chunkPacket);
			} finally {
				LG2_ABSOLUTE_INVISIBILITY_BYPASS.remove();
			}
			return;
		}
		if (packet instanceof ClientboundBlockUpdatePacket blockUpdatePacket) {
			ClientboundBlockUpdatePacket replacement = com.lostglade.server.ServerRaceSystem.rewriteLittleDictatorIronMechanismBlockUpdate(receiver, blockUpdatePacket);
			if (replacement != blockUpdatePacket) {
				ci.cancel();
				LG2_ABSOLUTE_INVISIBILITY_BYPASS.set(true);
				try {
					if (listener == null) {
						gameListener.send(replacement);
					} else {
						gameListener.send(replacement, listener);
					}
				} finally {
					LG2_ABSOLUTE_INVISIBILITY_BYPASS.remove();
				}
				return;
			}
		}
		if (packet instanceof ClientboundSectionBlocksUpdatePacket sectionBlocksUpdatePacket) {
			LG2_ABSOLUTE_INVISIBILITY_BYPASS.set(true);
			try {
				if (com.lostglade.server.ServerRaceSystem.handleLittleDictatorIronMechanismSectionUpdate(receiver, sectionBlocksUpdatePacket)) {
					ci.cancel();
					return;
				}
			} finally {
				LG2_ABSOLUTE_INVISIBILITY_BYPASS.remove();
			}
		}
		Packet<?> kilkaSalmonPacket = com.lostglade.server.ServerRaceSystem.rewriteKilkaSalmonOwnerVisualPacket(receiver, packet);
		if (kilkaSalmonPacket != packet) {
			ci.cancel();
			if (kilkaSalmonPacket == null) {
				return;
			}
			LG2_ABSOLUTE_INVISIBILITY_BYPASS.set(true);
			try {
				if (listener == null) {
					gameListener.send(kilkaSalmonPacket);
				} else {
					gameListener.send(kilkaSalmonPacket, listener);
				}
			} finally {
				LG2_ABSOLUTE_INVISIBILITY_BYPASS.remove();
			}
			return;
		}

		if (packet instanceof ClientboundSetEntityDataPacket entityDataPacket) {
			ServerAbsoluteInvisibilitySystem.maskSprintingMetadataForViewer(receiver, entityDataPacket);
		}

		if (packet instanceof ClientboundSetEquipmentPacket equipmentPacket) {
			ClientboundSetEquipmentPacket replacement = ServerAbsoluteInvisibilitySystem.maskArmorEquipmentForViewer(receiver, equipmentPacket);
				if (replacement != equipmentPacket) {
					ci.cancel();
					LG2_ABSOLUTE_INVISIBILITY_BYPASS.set(true);
					try {
						if (listener == null) {
							gameListener.send(replacement);
						} else {
							gameListener.send(replacement, listener);
						}
					} finally {
						LG2_ABSOLUTE_INVISIBILITY_BYPASS.remove();
					}
					return;
				}
		}

		Packet<?> bossFilteredPacket = ServerBossBarVisibilitySystem.filterOutgoingPacket(receiver, packet);
		if (bossFilteredPacket != packet) {
			ci.cancel();
			if (bossFilteredPacket == null) {
				return;
			}
			LG2_ABSOLUTE_INVISIBILITY_BYPASS.set(true);
			try {
				if (listener == null) {
					gameListener.send(bossFilteredPacket);
				} else {
					gameListener.send(bossFilteredPacket, listener);
				}
			} finally {
				LG2_ABSOLUTE_INVISIBILITY_BYPASS.remove();
			}
			return;
		}

		Packet<?> seasonFilteredPacket = com.lostglade.server.SeasonStartSystem.filterOutgoingPacket(receiver, packet);
		if (seasonFilteredPacket != packet) {
			ci.cancel();
			Packet<?> visiblePacket = com.lostglade.server.ServerWaypointVisibilitySystem.filterOutgoingPacket(receiver, seasonFilteredPacket);
			if (visiblePacket == null) {
				return;
			}
			LG2_ABSOLUTE_INVISIBILITY_BYPASS.set(true);
			try {
				if (listener == null) {
					gameListener.send(visiblePacket);
				} else {
					gameListener.send(visiblePacket, listener);
				}
			} finally {
				LG2_ABSOLUTE_INVISIBILITY_BYPASS.remove();
			}
			return;
		}

		if (ServerAbsoluteInvisibilitySystem.shouldSuppressOutgoingPacket(receiver, packet)) {
			ci.cancel();
			return;
		}

		Packet<?> visiblePacket = com.lostglade.server.ServerWaypointVisibilitySystem.filterOutgoingPacket(receiver, packet);
		if (visiblePacket != packet) {
			ci.cancel();
			if (visiblePacket == null) {
				return;
			}
			LG2_ABSOLUTE_INVISIBILITY_BYPASS.set(true);
			try {
				if (listener == null) {
					gameListener.send(visiblePacket);
				} else {
					gameListener.send(visiblePacket, listener);
				}
			} finally {
				LG2_ABSOLUTE_INVISIBILITY_BYPASS.remove();
			}
			return;
		}
	}

	@Redirect(
			method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V"
			)
	)
	private void lg2$sendBossBarPacketToConnection(
			Connection connection,
			Packet<?> packet,
			ChannelFutureListener listener,
			boolean flush
	) {
		Object self = this;
		if (!(self instanceof ServerGamePacketListenerImpl gameListener)) {
			connection.send(packet, listener, flush);
			return;
		}

		ServerPlayer receiver = gameListener.player;
		Packet<?> deliveredPacket = ServerBossBarVisibilitySystem.filterDeliveredPacket(receiver, packet);
		deliveredPacket = com.lostglade.server.CartelGasMaskVisualSystem.rewrite(receiver, deliveredPacket);
		if (deliveredPacket != null) {
			connection.send(deliveredPacket, listener, flush);
		}
	}
}
