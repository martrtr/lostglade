package com.lostglade.client;

import com.lostglade.Lg2;
import com.lostglade.network.Lg2Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

public final class MilkPocketVoidFadeClient {
	private static final Identifier OVERLAY_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "milk_pocket_void_fade");
	private static final float FADE_IN_RESPONSE = 0.32F;
	private static final float FADE_OUT_RESPONSE = 0.10F;

	private static float currentAlpha = 0.0F;
	private static float targetAlpha = 0.0F;

	private MilkPocketVoidFadeClient() {
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(
				Lg2Payloads.MilkPocketVoidFadeS2CPayload.TYPE,
				Lg2Payloads.MilkPocketVoidFadeS2CPayload.STREAM_CODEC
		);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			currentAlpha = 0.0F;
			targetAlpha = 0.0F;
		});
		ClientPlayNetworking.registerGlobalReceiver(
				Lg2Payloads.MilkPocketVoidFadeS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> targetAlpha = clamp01(payload.alpha()))
		);
		HudElementRegistry.addLast(OVERLAY_ID, MilkPocketVoidFadeClient::render);
	}

	private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		float deltaTicks = deltaTracker == null ? 1.0F : clamp(deltaTracker.getRealtimeDeltaTicks(), 0.05F, 3.0F);
		float response = targetAlpha > currentAlpha ? FADE_IN_RESPONSE : FADE_OUT_RESPONSE;
		float factor = 1.0F - (float) Math.pow(1.0F - response, deltaTicks);
		currentAlpha += (targetAlpha - currentAlpha) * factor;
		if (Math.abs(currentAlpha - targetAlpha) < 0.003F) {
			currentAlpha = targetAlpha;
		}
		if (currentAlpha <= 0.003F) {
			return;
		}

		int alpha = Math.max(0, Math.min(255, Math.round(currentAlpha * 255.0F)));
		graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24);
	}

	private static float clamp01(float value) {
		return clamp(value, 0.0F, 1.0F);
	}

	private static float clamp(float value, float min, float max) {
		if (Float.isNaN(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}
}
