package com.lostglade.client;

import com.lostglade.network.Lg2Payloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public final class KilkaClientForm {
	private static final boolean ENABLED = KilkaClientForm.class.getResource("/lg2-kilka-client.marker") != null;
	private static boolean active;
	private static boolean helloSent;

	private KilkaClientForm() {
	}

	public static void register() {
		if (!ENABLED) {
			return;
		}
		Lg2Payloads.registerKilkaClientPayloadTypes();
		ClientPlayNetworking.registerGlobalReceiver(Lg2Payloads.KilkaFormStateS2CPayload.TYPE,
				(payload, context) -> context.client().execute(() -> setActive(payload.active())));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			setActive(false);
			helloSent = false;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			setActive(false);
			helloSent = false;
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player != null && !helloSent
					&& ClientPlayNetworking.canSend(Lg2Payloads.KilkaClientHelloC2SPayload.TYPE)) {
				ClientPlayNetworking.send(new Lg2Payloads.KilkaClientHelloC2SPayload());
				helloSent = true;
			}
		});
	}

	public static boolean isActive() {
		return ENABLED && active;
	}

	private static void setActive(boolean value) {
		if (active == value) {
			return;
		}
		active = value;
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.refreshDimensions();
		}
	}
}
