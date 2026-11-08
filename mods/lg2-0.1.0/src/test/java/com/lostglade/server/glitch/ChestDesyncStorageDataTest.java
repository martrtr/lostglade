package com.lostglade.server.glitch;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ChestDesyncStorageDataTest {
	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		String blockKey = "block:minecraft:overworld:1234";
		String uuid = UUID.randomUUID().toString();
		JsonElement legacy = JsonParser.parseString("{\"blocks\":[\"" + blockKey
				+ "\"],\"entities\":[\"" + uuid + "\"]}");
		ChestDesyncGlitch.TouchedStorageData oldData = ChestDesyncGlitch.TouchedStorageData.CODEC
				.parse(JsonOps.INSTANCE, legacy).getOrThrow();
		JsonElement oldEncoded = ChestDesyncGlitch.TouchedStorageData.CODEC
				.encodeStart(JsonOps.INSTANCE, oldData).getOrThrow();
		require(oldEncoded.getAsJsonObject().getAsJsonArray("blocks").get(0).getAsString().equals(blockKey),
				"Legacy block touch was lost");
		require(oldEncoded.getAsJsonObject().getAsJsonArray("entities").get(0).getAsString().equals(uuid),
				"Legacy entity touch was lost");

		ChestDesyncGlitch.EntityLocation location = new ChestDesyncGlitch.EntityLocation(
				uuid, "minecraft:overworld", ChunkPos.asLong(12, -3));
		ChestDesyncGlitch.TouchedStorageData data = new ChestDesyncGlitch.TouchedStorageData(
				List.of(blockKey), List.of(uuid), List.of(location));
		JsonElement encoded = ChestDesyncGlitch.TouchedStorageData.CODEC
				.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
		require(encoded.getAsJsonObject().getAsJsonArray("locations").size() == 1,
				"Entity location was not serialized");
		ChestDesyncGlitch.TouchedStorageData decoded = ChestDesyncGlitch.TouchedStorageData.CODEC
				.parse(JsonOps.INSTANCE, encoded).getOrThrow();
		JsonElement roundTrip = ChestDesyncGlitch.TouchedStorageData.CODEC
				.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow();
		require(encoded.equals(roundTrip), "Touched storage index changed across serialization");
		Tag nbt = ChestDesyncGlitch.TouchedStorageData.CODEC
				.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
		ChestDesyncGlitch.TouchedStorageData fromNbt = ChestDesyncGlitch.TouchedStorageData.CODEC
				.parse(NbtOps.INSTANCE, nbt).getOrThrow();
		require(nbt.equals(ChestDesyncGlitch.TouchedStorageData.CODEC
				.encodeStart(NbtOps.INSTANCE, fromNbt).getOrThrow()), "Touched storage NBT changed across serialization");
		try {
			testOfflineEnderChestOwners();
		} catch (Exception exception) {
			throw new AssertionError("Offline ender chest enumeration failed", exception);
		}
		System.out.println("Chest desync storage data: legacy read and entity index round trip passed");
	}

	private static void testOfflineEnderChestOwners() throws Exception {
		Path dir = Files.createTempDirectory("lg2-ender-owners-");
		UUID offline = UUID.randomUUID();
		UUID online = UUID.randomUUID();
		try {
			Files.createFile(dir.resolve(offline + ".dat"));
			Files.createFile(dir.resolve(online + ".dat"));
			Files.createFile(dir.resolve("not-a-player.dat"));
			Files.createFile(dir.resolve(offline + ".dat_old"));
			require(OfflineEnderChestOwners.list(dir, Set.of(online)).equals(List.of(offline)),
					"Offline owner selection included an online player or a non-player file");
		} finally {
			try (var files = Files.list(dir)) {
				files.forEach(file -> {
					try {
						Files.delete(file);
					} catch (Exception exception) {
						throw new IllegalStateException(exception);
					}
				});
			}
			Files.delete(dir);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
