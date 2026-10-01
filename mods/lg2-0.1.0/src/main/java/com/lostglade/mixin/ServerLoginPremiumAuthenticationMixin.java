package com.lostglade.mixin;

import com.lostglade.Lg2;
import com.lostglade.server.AccountAuthSystem;
import com.lostglade.server.PremiumNameLookup;
import com.mojang.authlib.GameProfile;
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

import java.io.IOException;
import java.util.Collections;
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
	private static final Set<ServerLoginPacketListenerImpl> LG2_PREMIUM_PROBES = Collections.newSetFromMap(
			Collections.synchronizedMap(new WeakHashMap<>())
	);

	@Shadow @Final private MinecraftServer server;
	@Shadow @Final private Connection connection;
	@Shadow String requestedUsername;

	@Invoker("startClientVerification")
	abstract void lg2$startClientVerification(GameProfile profile);

	@Inject(method = "handleHello", at = @At("HEAD"))
	private void lg2$beginPremiumProbe(ServerboundHelloPacket packet, CallbackInfo ci) {
		if (!this.server.usesAuthentication() && !this.connection.isMemoryConnection()) {
			LG2_PREMIUM_PROBES.add((ServerLoginPacketListenerImpl) (Object) this);
		}
	}

	/** Makes vanilla send its encryption request while keeping server.properties online-mode=false. */
	@Redirect(
			method = "handleHello",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z")
	)
	private boolean lg2$requireSessionHandshake(MinecraftServer server) {
		return server.usesAuthentication() || !this.connection.isMemoryConnection();
	}

	@Inject(method = "startClientVerification", at = @At("HEAD"))
	private void lg2$recordVerifiedPremiumProfile(GameProfile profile, CallbackInfo ci) {
		if (!LG2_PREMIUM_PROBES.remove((ServerLoginPacketListenerImpl) (Object) this) || profile == null) return;
		String name = this.requestedUsername;
		if (name != null && !profile.id().equals(UUIDUtil.createOfflinePlayerUUID(name))) {
			AccountAuthSystem.noteVerifiedPremiumSession(profile.name(), profile.id());
		}
	}

	@Inject(method = "disconnect", at = @At("HEAD"), cancellable = true)
	private void lg2$fallBackToOfflineAfterInvalidSession(Component reason, CallbackInfo ci) {
		ServerLoginPacketListenerImpl listener = (ServerLoginPacketListenerImpl) (Object) this;
		if (!LG2_PREMIUM_PROBES.remove(listener) || this.server.usesAuthentication() || !isSessionFailure(reason)) return;
		String name = this.requestedUsername;
		if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) return;

		ci.cancel();
		Thread.ofVirtual().name("lg2-premium-name-check").start(() -> {
			try {
				UUID officialId = PremiumNameLookup.lookup(name);
				if (officialId != null) AccountAuthSystem.reservePremiumName(name, officialId);
				else AccountAuthSystem.notePremiumNameLookupSucceeded(name);
			} catch (IOException exception) {
				// Fail closed only for new registrations; existing password accounts remain usable.
				AccountAuthSystem.notePremiumNameLookupUnavailable(name);
				Lg2.LOGGER.warn("Could not reserve premium name '{}' after an invalid Mojang session", name);
			}
			this.lg2$startClientVerification(UUIDUtil.createOfflineProfile(name));
		});
	}

	@Unique
	private static boolean isSessionFailure(Component reason) {
		if (reason == null || !(reason.getContents() instanceof TranslatableContents contents)) return false;
		String key = contents.getKey();
		return "multiplayer.disconnect.unverified_username".equals(key)
				|| "multiplayer.disconnect.authservers_down".equals(key);
	}
}
