package com.lostglade.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.network.Lg2Payloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/** Persists a random local secret and presents it after every server join. */
public final class AccountAuthClient {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("lostglade-auth.json");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static String token;

	private AccountAuthClient() {
	}

	public static void register() {
		loadOrCreate();
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			if (ClientPlayNetworking.canSend(Lg2Payloads.AuthTokenC2SPayload.TYPE)) {
				ClientPlayNetworking.send(new Lg2Payloads.AuthTokenC2SPayload(token));
			}
		}));
	}

	private static void loadOrCreate() {
		try {
			if (Files.isRegularFile(PATH)) {
				try (Reader reader = Files.newBufferedReader(PATH)) {
					StoredSecret stored = GSON.fromJson(reader, StoredSecret.class);
					if (stored != null && stored.token != null && stored.token.length() >= 32) {
						token = stored.token;
						return;
					}
				}
			}
			byte[] bytes = new byte[32];
			RANDOM.nextBytes(bytes);
			token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(new StoredSecret(token), writer);
			}
		} catch (Exception exception) {
			Lg2.LOGGER.error("Could not prepare the local account-auth secret", exception);
			token = "";
		}
	}

	private static final class StoredSecret {
		private String token;
		private StoredSecret() { }
		private StoredSecret(String token) { this.token = token; }
	}
}
