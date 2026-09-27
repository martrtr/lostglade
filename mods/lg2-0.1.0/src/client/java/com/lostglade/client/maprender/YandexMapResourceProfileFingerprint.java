package com.lostglade.client.maprender;

import com.lostglade.server.maprender.MapRenderProfile;
import com.lostglade.network.YandexMapRenderPayloads;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Hashes resolved visual resources, not merely resource-pack display names. */
public final class YandexMapResourceProfileFingerprint {
	private static final List<String> VISUAL_PREFIXES = List.of("textures", "models", "blockstates", "atlases", "shaders");
	private static final Set<String> CANONICAL_RENDER_NAMESPACES = Set.of("minecraft", "lg2");
	private static final AtomicLong INVALIDATION_GENERATION = new AtomicLong();
	private static volatile long cachedGeneration = Long.MIN_VALUE;
	private static volatile String cachedFingerprint;

	private YandexMapResourceProfileFingerprint() {
	}

	public static void invalidate() {
		INVALIDATION_GENERATION.incrementAndGet();
		cachedFingerprint = null;
		cachedGeneration = Long.MIN_VALUE;
	}

	public static String compute(Minecraft client) {
		long generation = INVALIDATION_GENERATION.get();
		String cached = cachedFingerprint;
		if (cached != null && cachedGeneration == generation) {
			return cached;
		}
		if (client == null || client.getResourceManager() == null || client.getResourcePackRepository() == null) {
			throw new IllegalStateException("Minecraft resource manager is unavailable");
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			ResourceManager manager = client.getResourceManager();
			byte[] buffer = new byte[16 * 1024];
			for (String prefix : VISUAL_PREFIXES) {
				Map<Identifier, Resource> resources = manager.listResources(prefix, ignored -> true);
				List<Map.Entry<Identifier, Resource>> ordered = resources.entrySet().stream()
						.filter(entry -> CANONICAL_RENDER_NAMESPACES.contains(entry.getKey().getNamespace()))
						.sorted(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)))
						.toList();
				for (Map.Entry<Identifier, Resource> entry : ordered) {
					updateUtf8(digest, prefix);
					updateUtf8(digest, "\u0000");
					updateUtf8(digest, entry.getKey().toString());
					updateUtf8(digest, "\u0000");
					try (InputStream input = entry.getValue().open()) {
						int read;
						while ((read = input.read(buffer)) >= 0) {
							if (read > 0) {
								digest.update(buffer, 0, read);
							}
						}
					}
					digest.update((byte) 0xFF);
				}
			}
			String computed = HexFormat.of().formatHex(digest.digest());
			if (INVALIDATION_GENERATION.get() == generation) {
				cachedGeneration = generation;
				cachedFingerprint = computed;
			}
			return computed;
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to fingerprint resolved map-render resources", exception);
		}
	}

	public static String clientBuildFingerprint() {
		String modVersion = FabricLoader.getInstance()
				.getModContainer("lg2")
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
		String value = "lg2=" + modVersion
				+ "\nmcProtocol=" + SharedConstants.RELEASE_NETWORK_PROTOCOL_VERSION
				+ "\nmapProtocol=" + YandexMapRenderPayloads.PROTOCOL_VERSION
				+ "\nrenderContract=" + MapRenderProfile.CURRENT.contractHash();
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void updateUtf8(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
	}
}
