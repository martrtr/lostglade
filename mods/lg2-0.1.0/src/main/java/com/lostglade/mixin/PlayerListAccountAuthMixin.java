package com.lostglade.mixin;

import com.lostglade.server.AccountAuthSystem;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public abstract class PlayerListAccountAuthMixin {
	@Inject(method = "placeNewPlayer", at = @At("HEAD"), cancellable = true)
	private void lg2$placeInAuthenticationWorldBeforePlayPackets(
			Connection connection,
			ServerPlayer player,
			CommonListenerCookie cookie,
			CallbackInfo ci
	) {
		if (!AccountAuthSystem.preparePlayerPlacement(player, connection)) ci.cancel();
	}

	@Inject(method = "placeNewPlayer", at = @At("RETURN"))
	private void lg2$startAuthenticationOnlyAfterVanillaPlacementCompletes(
			Connection connection,
			ServerPlayer player,
			CommonListenerCookie cookie,
			CallbackInfo ci
	) {
		AccountAuthSystem.onPlayerPlacementComplete(player);
	}
	@Inject(method = "remove", at = @At("RETURN"))
	private void lg2$clearAuthenticationStateOnlyAfterVanillaSave(ServerPlayer player, CallbackInfo ci) {
		AccountAuthSystem.onPlayerRemoved(player);
	}

	@Inject(method = "isWhiteListed", at = @At("RETURN"), cancellable = true)
	private void lg2$allowWhitelistedNameAcrossHybridUuid(NameAndId profile, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() || profile == null || profile.name() == null) return;

		PlayerList self = (PlayerList) (Object) this;
		for (String whitelistedName : self.getWhiteListNames()) {
			if (whitelistedName != null && whitelistedName.equalsIgnoreCase(profile.name())) {
				cir.setReturnValue(true);
				return;
			}
		}
	}

	@Inject(method = "getPlayerNamesArray", at = @At("RETURN"), cancellable = true)
	private void lg2$hideUnauthenticatedNamesFromSuggestions(CallbackInfoReturnable<String[]> cir) {
		PlayerList self = (PlayerList) (Object) this;
		cir.setReturnValue(self.getPlayers().stream()
				.filter(AccountAuthSystem::isPresenceVisible)
				.map(ServerPlayer::getScoreboardName)
				.toArray(String[]::new));
	}

}
