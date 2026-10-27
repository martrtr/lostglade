package com.lostglade.client;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Known incompatible visual modifications; unrelated mods and libraries are admitted. */
public final class RendererModPolicy {
	private static final Set<String> BLOCKED_VISUAL_MODS = Set.of(
			"advanced-xray", "advanced_xray", "advancedxray", "xray", "xraymod",
			"litematica", "forgematica", "axiom", "worldeditcui", "minihud", "fullbright", "fullbrightnesstoggle",
			"gammautils", "boostedbrightness", "gammashifter",
			"meteor-client", "wurst", "wurstclient", "bleachhack", "liquidbounce"
	);
	private static final Set<String> UNSUPPORTED_RENDERERS = Set.of("vulkanmod", "canvas");

	private RendererModPolicy() { }

	public static String blockingReason(Collection<String> modIds) {
		return modIds.stream().filter(java.util.Objects::nonNull).map(id -> id.toLowerCase(Locale.ROOT)).sorted()
				.filter(id -> BLOCKED_VISUAL_MODS.contains(id) || UNSUPPORTED_RENDERERS.contains(id))
				.map(id -> (UNSUPPORTED_RENDERERS.contains(id) ? "unsupported-renderer:" : "blocked-visual-mod:") + id)
				.findFirst().orElse(null);
	}

	public static boolean isServerPack(String id, boolean serverSource) {
		return serverSource || id.startsWith("server/");
	}

	public static boolean suspendPack(String id, boolean required, boolean serverSource) {
		if ("vanilla".equals(id) || isServerPack(id, serverSource)) return false;
		return id.startsWith("file/") || "programmer_art".equals(id) || "high_contrast".equals(id) || !required;
	}

	/** Server pack IDs belong to the current connection, never to the saved local profile. */
	public static List<String> restorePackSelection(List<String> original, List<String> currentServerPacks) {
		var result = new LinkedHashSet<>(original);
		result.addAll(currentServerPacks);
		return List.copyOf(result);
	}
}
