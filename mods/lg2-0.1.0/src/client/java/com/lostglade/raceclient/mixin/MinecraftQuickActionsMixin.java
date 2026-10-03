package com.lostglade.raceclient.mixin;

import com.lostglade.client.LostgladeClientSettings;
import com.lostglade.raceclient.RaceClientControls;
import com.lostglade.raceclient.RaceAbilityPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftQuickActionsMixin {
	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void lg2RaceClient$replaceVanillaQuickActions(CallbackInfo callbackInfo) {
		if (!LostgladeClientSettings.isRaceMenuEnabled() || !ClientPlayNetworking.canSend(RaceAbilityPayload.TYPE)) {
			return;
		}
		Minecraft client = (Minecraft) (Object) this;
		while (client.options.keyQuickActions.consumeClick()) {
			// This is a hold menu, not a toggle. Ignore clicks that were already
			// released before the client tick reached keybind handling.
			if (client.options.keyQuickActions.isDown()) {
				RaceClientControls.openMenu(client);
			}
		}
	}
}
