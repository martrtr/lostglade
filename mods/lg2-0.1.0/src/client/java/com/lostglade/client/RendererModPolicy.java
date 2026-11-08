package com.lostglade.client;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Admission is based on the active render environment, never on a list of
 * installed gameplay/client mods.  A mod being present does not mean that one
 * of its visual features is enabled (Meteor is the most obvious example), and
 * refusing such clients needlessly discards otherwise usable volunteers.
 */
public final class RendererModPolicy {
	// These replace Minecraft's OpenGL renderer.  The off-screen camera uses
	// OpenGL framebuffer/readback APIs and cannot be isolated reliably there.
	private static final Set<String> UNSUPPORTED_RENDERERS = Set.of("vulkanmod", "canvas");

	private RendererModPolicy() { }

	public static String blockingReason(Collection<String> modIds) {
		return modIds.stream().filter(java.util.Objects::nonNull).map(id -> id.toLowerCase(Locale.ROOT)).sorted()
				.filter(UNSUPPORTED_RENDERERS::contains)
				.map(id -> "unsupported-renderer:" + id)
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
