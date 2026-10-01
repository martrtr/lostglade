package com.lostglade.server;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.UUID;

/** Fast, network-free checks for the trust boundary around Mojang name lookup. */
public final class PremiumNameLookupTest {
	private PremiumNameLookupTest() {
	}

	public static void main(String[] args) throws Exception {
		UUID id = PremiumNameLookup.parseProfile("Alice", JsonParser.parseString(
				"{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"aLiCe\"}"
		).getAsJsonObject());
		require(id.equals(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")), "must parse Mojang's compact UUID");

		requireIOException(() -> PremiumNameLookup.parseProfile("Alice", JsonParser.parseString(
				"{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Mallory\"}"
		).getAsJsonObject()), "a response for another name must never reserve the requested name");
		requireIOException(() -> PremiumNameLookup.parseProfile("Alice", JsonParser.parseString(
				"{\"id\":\"not-a-uuid\",\"name\":\"Alice\"}"
		).getAsJsonObject()), "malformed UUIDs must fail closed");
		System.out.println("Premium name lookup checks passed");
	}

	private static void requireIOException(ThrowingRunnable action, String message) throws Exception {
		try {
			action.run();
		} catch (IOException expected) {
			return;
		}
		throw new IllegalStateException(message);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
