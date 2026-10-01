package com.lostglade.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Resolves the current owner of a Minecraft name.  This is deliberately used
 * only after the encrypted Mojang-session challenge failed: it reserves a
 * premium name, it never authenticates a player by name alone.
 */
public final class PremiumNameLookup {
	private static final String LOOKUP_URL = "https://api.mojang.com/users/profiles/minecraft/";
	private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
	private static final int READ_TIMEOUT_MILLIS = 3_000;

	private PremiumNameLookup() {
	}

	/** @return the official UUID, or {@code null} when the name has no current premium owner. */
	public static UUID lookup(String name) throws IOException {
		if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) return null;
		HttpURLConnection connection = (HttpURLConnection) URI.create(
				LOOKUP_URL + URLEncoder.encode(name, StandardCharsets.UTF_8)
		).toURL().openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
		connection.setReadTimeout(READ_TIMEOUT_MILLIS);
		connection.setRequestProperty("Accept", "application/json");

		try {
			int status = connection.getResponseCode();
			if (status == HttpURLConnection.HTTP_NO_CONTENT || status == HttpURLConnection.HTTP_NOT_FOUND) return null;
			if (status != HttpURLConnection.HTTP_OK) throw new IOException("Mojang profile lookup returned HTTP " + status);
			try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
				return parseProfile(name, JsonParser.parseReader(reader).getAsJsonObject());
			}
		} finally {
			connection.disconnect();
		}
	}

	static UUID parseProfile(String requestedName, JsonObject body) throws IOException {
		String resolvedName = body.has("name") ? body.get("name").getAsString() : "";
		String compactId = body.has("id") ? body.get("id").getAsString() : "";
		if (!requestedName.equalsIgnoreCase(resolvedName) || !compactId.matches("[0-9a-fA-F]{32}")) {
			throw new IOException("Mojang profile lookup returned an invalid profile");
		}
		return UUID.fromString(compactId.replaceFirst(
				"(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
	}
}
