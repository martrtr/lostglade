package com.lostglade.server;

import java.util.Collection;
import java.util.List;

/** Exercise real admission/selection decisions without loading Minecraft or optional renderers. */
public final class RendererModPolicyTest {
	public static void main(String[] args) throws Exception {
		Class<?> policy = Class.forName("com.lostglade.client.RendererModPolicy");
		var reason = policy.getMethod("blockingReason", Collection.class);
		var suspend = policy.getMethod("suspendPack", String.class, boolean.class, boolean.class);
		require(reason.invoke(null, List.of("fabricloader", "sodium", "iris", "voxy", "distanthorizons", "modmenu", "unknown-library", "xaerominimap")) == null,
				"ordinary modpacks and unknown libraries must not need an allowlist entry");
		for (String mod : List.of("advanced-xray", "advanced_xray", "advancedxray", "xray", "LITEMATICA", "meteor-client", "gammautils")) {
			require(String.valueOf(reason.invoke(null, List.of("sodium", mod))).startsWith("blocked-visual-mod:"), "known visual conflict admitted: " + mod);
		}
		require(String.valueOf(reason.invoke(null, List.of("vulkanmod"))).equals("unsupported-renderer:vulkanmod"), "OpenGL capture must not admit an incompatible graphics backend");
		require(!(boolean) suspend.invoke(null, "vanilla", false, false), "vanilla resources were removed");
		require(!(boolean) suspend.invoke(null, "server/123/pack", false, false), "a server pack was mistaken for a local pack");
		require(!(boolean) suspend.invoke(null, "custom-server-id", false, true), "server-source packs must survive regardless of their ID");
		require(!(boolean) suspend.invoke(null, "fabric", true, false), "required bundled mod resources were removed");
		for (String id : List.of("file/xray.zip", "programmer_art", "high_contrast", "some-mod:optional-pack")) {
			require((boolean) suspend.invoke(null, id, false, false), "optional local visual override was not suspended: " + id);
		}
		require((boolean) suspend.invoke(null, "file/forced-xray.zip", true, false), "a mandatory local pack must still prevent unsafe capture");
		Object restored = policy.getMethod("restorePackSelection", List.class, List.class)
				.invoke(null, List.of("vanilla", "fabric", "file/custom.zip"), List.of("server/current"));
		require(restored.equals(List.of("vanilla", "fabric", "file/custom.zip", "server/current")), "restore lost local order or reused a stale server pack");
		System.out.println("Renderer mod/pack policy checks passed");
	}
	private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
