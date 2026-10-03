package com.lostglade.client;

import com.lostglade.client.maprender.YandexMapShaderGuard;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Admission gate for any client that produces canonical camera or map pixels.
 *
 * <p>An off-screen target isolates the level data, but it cannot undo arbitrary
 * third-party world-render callbacks after they have submitted vertices to the
 * GPU. Rather than publishing a frame that might contain a schematic overlay,
 * shader effect or a local resource-pack texture, only a clean vanilla/LG2
 * client is allowed to contribute. Server packs remain allowed because they are
 * part of the server's canonical scene.</p>
 */
public final class RendererBotRenderEnvironmentGuard {
	private static final Set<String> TRUSTED_MOD_IDS = Set.of(
			// Vanilla, loader and LG2's client runtime. This is deliberately a
			// closed list: an arbitrary player-side rendering mod must not submit
			// geometry into the canonical off-screen target.
			"minecraft", "java", "fabricloader", "lg2", "lg2-client", "modmenu", "mixinextras",
			// Non-visual libraries and the server-provided features shipped with
			// Lostglade's own renderer-client distribution.
			"cardinal-components", "com_flowpowered_flow-math", "de_bluecolored_bluenbt",
			"org_mineskin_java-client", "packet_tweaker", "skinrestorer", "voicechat", "webcam",
			"polymer-bundled", "polymer-blocks", "polymer-common", "polymer-core",
			"polymer-networking", "polymer-registry-sync-manipulator", "polymer-resource-pack"
	);

	private static volatile Set<String> serverModIds = Set.of();
	private static volatile boolean serverModManifestKnown;

	private RendererBotRenderEnvironmentGuard() {
	}

	/** Starts a new play session before the server mod manifest arrives. */
	public static void beginServerSession() {
		serverModIds = Set.of();
		serverModManifestKnown = false;
	}

	/** Accepts server mod IDs only; versions deliberately do not participate in admission. */
	public static void setServerModIds(Collection<String> modIds) {
		Set<String> normalized = new HashSet<>();
		if (modIds != null) {
			for (String id : modIds) {
				if (id != null && !id.isBlank()) normalized.add(id.toLowerCase(Locale.ROOT));
			}
		}
		serverModIds = Set.copyOf(normalized);
		serverModManifestKnown = true;
	}

	public static boolean hasServerModManifest() {
		return serverModManifestKnown;
	}

	public static Compatibility inspect(Minecraft client) {
		YandexMapShaderGuard.Compatibility shader = YandexMapShaderGuard.inspect();
		if (!shader.compatible()) {
			return new Compatibility(false, shader.reason());
		}
		String unexpectedPackId = unexpectedResourcePackId(client);
		if (unexpectedPackId != null) {
			return new Compatibility(false, "resource-pack-active:" + unexpectedPackId);
		}
		for (var container : FabricLoader.getInstance().getAllMods()) {
			String id = container.getMetadata().getId();
			if (!isTrustedMod(id)) {
				return new Compatibility(false, "third-party-client-mod:" + id);
			}
		}
		return new Compatibility(true, "canonical-vanilla-pipeline");
	}

	/** Returns the first local visual pack that is not part of the canonical scene. */
	private static String unexpectedResourcePackId(Minecraft client) {
		if (client == null || client.getResourcePackRepository() == null) {
			return "<state-unavailable>";
		}
		try {
			for (Pack pack : client.getResourcePackRepository().getSelectedPacks()) {
				String id = pack.getId();
				// A server pack is shared server content, unlike a player-selected
				// pack. Its exact resolved visual resources are still checked by the
				// map profile protocol before a map task is accepted.
				if (pack.getPackSource() == PackSource.SERVER || id.startsWith("server/")) {
					continue;
				}
				// Fabric exposes every mod's bundled resources through the selected
				// pack repository. Those entries are not player resource packs, and
				// rejecting them here made a valid server pack look like it was the
				// cause of the block. Mod admission is enforced separately below.
				// Locally selected folders/zips use the file/ namespace. Keep the
				// two built-in alternate visual packs explicit as well.
				if (id.startsWith("file/")
						|| "programmer_art".equals(id)
						|| "high_contrast".equals(id)) {
					return id;
				}
			}
			return null;
		} catch (Throwable ignored) {
			// A new/unknown pack API must not silently make pixels non-canonical.
			return "<state-unknown>";
		}
	}

	private static boolean isTrustedMod(String id) {
		if (id == null) return false;
		String normalized = id.toLowerCase(Locale.ROOT);
		return TRUSTED_MOD_IDS.contains(normalized)
				|| normalized.startsWith("fabric-")
				|| serverModIds.contains(normalized);
	}

	public record Compatibility(boolean compatible, String reason) {
	}
}
