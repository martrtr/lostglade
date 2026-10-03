package com.lostglade.raceclient;

import com.lostglade.client.LostgladeClientSettings;
import com.lostglade.raceclient.mixin.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class RaceClientControls {
	private static final KeyMapping.Category CATEGORY = LostgladeClientSettings.keyCategory();
	private static final KeyMapping ATTACK = bind("key.lg2.race_attack", GLFW.GLFW_KEY_UNKNOWN);
	private static final KeyMapping DEFENSE = bind("key.lg2.race_defense", GLFW.GLFW_KEY_UNKNOWN);
	private static final KeyMapping ABILITY = bind("key.lg2.race_ability", GLFW.GLFW_KEY_UNKNOWN);
	private static final KeyMapping SHNYAGA = bind("key.lg2.race_shnyaga", GLFW.GLFW_KEY_UNKNOWN);
	private static final KeyMapping TOGGLE_INDICATOR = bind("key.lg2.race_toggle_indicator", GLFW.GLFW_KEY_UNKNOWN);

	private RaceClientControls() {
	}

	public static void register() {
		// The dedicated renderer has no interactive controls and its stripped runtime may
		// intentionally omit UI-only mixins such as KeyMappingAccessor.
		if (Boolean.getBoolean("lg2.rendererBot")) return;

		// ClientModInitializer can run before Minecraft.options exists. Try immediately,
		// then retry from the client tick until the vanilla binding is actually rewritten.
		renameVanillaQuickActions(Minecraft.getInstance());
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			renameVanillaQuickActions(client);
			consumeAbility(ATTACK, 0);
			consumeAbility(DEFENSE, 1);
			consumeAbility(ABILITY, 2);
			consumeAbility(SHNYAGA, 3);
			consumeRaceIndicator();
		});
	}

	/** Reuse vanilla Quick Actions as the Lostglade race-menu binding. */
	private static void renameVanillaQuickActions(Minecraft client) {
		if (client == null || client.options == null || client.options.keyQuickActions == null) return;
		KeyMapping binding = client.options.keyQuickActions;
		if ("key.lg2.race_menu".equals(binding.getName()) && CATEGORY.equals(binding.getCategory())) return;
		if (!(binding instanceof KeyMappingAccessor quickActions)) return;
		quickActions.lg2$setName("key.lg2.race_menu");
		quickActions.lg2$setCategory(CATEGORY);
	}

	public static void openMenu(Minecraft client) {
		if (!LostgladeClientSettings.isRaceMenuEnabled()) return;
		if (client != null && client.player != null && client.screen == null) {
			requestAbilityState();
			client.setScreen(new RaceAbilityScreen());
		}
	}

	public static void useAbility(int slot) {
		if (slot >= 0 && slot <= 3 && ClientPlayNetworking.canSend(RaceAbilityPayload.TYPE)) {
			ClientPlayNetworking.send(new RaceAbilityPayload(slot));
		}
	}

	/** The server resolves the current race and silently ignores races without an indicator. */
	public static void consumeRaceIndicator() {
		while (TOGGLE_INDICATOR.consumeClick()) {
			if (ClientPlayNetworking.canSend(RaceAbilityPayload.TYPE)) {
				ClientPlayNetworking.send(new RaceAbilityPayload(4));
			}
		}
	}

	private static void requestAbilityState() {
		if (ClientPlayNetworking.canSend(RaceAbilityStateRequestPayload.TYPE)) {
			ClientPlayNetworking.send(new RaceAbilityStateRequestPayload());
		}
	}

	private static KeyMapping bind(String translationKey, int defaultKey) {
		return KeyBindingHelper.registerKeyBinding(new KeyMapping(translationKey, InputConstants.Type.KEYSYM, defaultKey, CATEGORY));
	}

	private static void consumeAbility(KeyMapping key, int slot) {
		while (key.consumeClick()) {
			useAbility(slot);
		}
	}
}
