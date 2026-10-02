package com.lostglade.mixin;

import com.lostglade.Lg2;
import com.lostglade.server.AccountAuthSystem;
import com.lostglade.server.PremiumLoginProbe;
import com.lostglade.server.RendererBotPresenceSystem;
import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Reuses the vanilla encryption/session exchange for an offline-mode server.
 * A name or client-supplied UUID is never treated as proof of ownership: only
 * {@code MinecraftSessionService.hasJoinedServer} can mark a premium session.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPremiumAuthenticationMixin {
	@Unique
	private static final Map<ServerLoginPacketListenerImpl, PremiumLoginProbe> LG2_PREMIUM_PROBES = Collections.synchronizedMap(new WeakHashMap<>());
	@Unique
	private static final Set<ServerLoginPacketListenerImpl> LG2_OFFLINE_REPLAYS = Collections.newSetFromMap(
			Collections.synchronizedMap(new WeakHashMap<>())
	);
	@Shadow @Final private MinecraftServer server;
	@Shadow @Final private Connection connection;
	@Shadow String requestedUsername;

	@Invoker("handleHello")
	abstract void lg2$replayHello(ServerboundHelloPacket packet);

	@Inject(method = "handleHello", at = @At("HEAD"))
	private void lg2$routePremiumHandshake(ServerboundHelloPacket packet, CallbackInfo ci) {
		ServerLoginPacketListenerImpl listener = (ServerLoginPacketListenerImpl) (Object) this;
		if (LG2_OFFLINE_REPLAYS.contains(listener)) return;
		if (this.server.usesAuthentication() || this.connection.isMemoryConnection()
				|| RendererBotPresenceSystem.isRendererBotConnection(packet.name(), this.connection)) return;

		UUID knownPremiumId = AccountAuthSystem.knownPremiumUuid(packet.name());
		boolean requestPremiumSession;
		if (knownPremiumId != null) {
			// Once a premium owner has really authenticated here, only that official UUID is
			// routed into Mojang auth. Offline UUIDs go to the admin-password fallback.
			requestPremiumSession = knownPremiumId.equals(packet.profileId());
		} else {
			// For names never premium-verified on this server, existence in Mojang's public
			// profile database is irrelevant. Vanilla offline clients use this deterministic
			// UUID and may register immediately. Any other UUID merely asks us to attempt the
			// real Mojang session exchange; it is never accepted as proof by itself.
			requestPremiumSession = !UUIDUtil.createOfflinePlayerUUID(packet.name()).equals(packet.profileId());
		}

		if (requestPremiumSession) LG2_PREMIUM_PROBES.put(listener, new PremiumLoginProbe(packet));
	}

	/** Makes vanilla send its encryption request while keeping server.properties online-mode=false. */
	@Redirect(
			method = "handleHello",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z")
	)
	private boolean lg2$requireSessionHandshake(MinecraftServer server) {
		ServerLoginPacketListenerImpl listener = (ServerLoginPacketListenerImpl) (Object) this;
		if (LG2_OFFLINE_REPLAYS.remove(listener)
				|| RendererBotPresenceSystem.isRendererBotConnection(this.requestedUsername, this.connection)) {
			return server.usesAuthentication();
		}
		return server.usesAuthentication() || LG2_PREMIUM_PROBES.containsKey(listener);
	}

	/** Allows the one intentional replay while the listener is still in KEY state. */
	@Redirect(
			method = "handleHello",
			at = @At(value = "INVOKE", target = "Lorg/apache/commons/lang3/Validate;validState(ZLjava/lang/String;[Ljava/lang/Object;)V", ordinal = 0)
	)
	private void lg2$allowOfflineReplay(boolean valid, String message, Object[] parameters) {
		ServerLoginPacketListenerImpl listener = (ServerLoginPacketListenerImpl) (Object) this;
		if (LG2_OFFLINE_REPLAYS.contains(listener) && "Unexpected hello packet".equals(message)) return;
		org.apache.commons.lang3.Validate.validState(valid, message, parameters);
	}

	@Inject(method = "startClientVerification", at = @At("HEAD"))
	private void lg2$recordVerifiedPremiumProfile(GameProfile profile, CallbackInfo ci) {
		if (LG2_PREMIUM_PROBES.remove((ServerLoginPacketListenerImpl) (Object) this) == null || profile == null) return;
		String name = this.requestedUsername;
		if (name != null && !profile.id().equals(UUIDUtil.createOfflinePlayerUUID(name))) {
			AccountAuthSystem.noteVerifiedPremiumSession(profile.name(), profile.id());
		}
	}

	@Inject(method = "disconnect", at = @At("HEAD"), cancellable = true)
	private void lg2$fallBackToOfflineAfterInvalidSession(Component reason, CallbackInfo ci) {
		ServerLoginPacketListenerImpl listener = (ServerLoginPacketListenerImpl) (Object) this;
		PremiumLoginProbe probe = LG2_PREMIUM_PROBES.remove(listener);
		if (probe == null || this.server.usesAuthentication() || !lg2$isSessionFailure(reason)) return;
		ci.cancel();
		this.lg2$fallBackToOffline(listener, probe.hello(), "unverified Mojang session");
	}

	/**
	 * The regular offline login branch must be replayed, rather than merely
	 * calling startClientVerification: a vanilla client is still waiting for the
	 * login transition after the optional encryption request.
	 */
	@Unique
	private static boolean lg2$isSessionFailure(Component reason) {
		if (reason == null || !(reason.getContents() instanceof TranslatableContents contents)) return false;
		String key = contents.getKey();
		return "multiplayer.disconnect.unverified_username".equals(key)
				|| "multiplayer.disconnect.authservers_down".equals(key);
	}

	@Unique
	private void lg2$fallBackToOffline(ServerLoginPacketListenerImpl listener, ServerboundHelloPacket hello, String reason) {
		Runnable replay = () -> {
			try {
				LG2_OFFLINE_REPLAYS.add(listener);
				this.lg2$replayHello(hello);
				Lg2.LOGGER.debug("Premium probe for '{}' fell back to offline login ({})", hello.name(), reason);
			} catch (RuntimeException exception) {
				Lg2.LOGGER.error("Could not return '{}' to the offline login flow", hello.name(), exception);
				listener.disconnect(Component.literal("Login verification failed. Please reconnect."));
			}
		};
		Channel channel = ((ConnectionChannelAccessor) this.connection).lg2$getChannel();
		if (channel != null && channel.isOpen()) channel.eventLoop().execute(replay);
	}

}
