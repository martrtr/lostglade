package com.lostglade.client;

import com.lostglade.client.maprender.YandexMapShaderGuard;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.util.Collection;

/** Admission after reversible visual normalization, with a targeted conflict list. */
public final class RendererBotRenderEnvironmentGuard {
	private static volatile boolean serverModManifestKnown;

	private RendererBotRenderEnvironmentGuard() { }

	public static void beginServerSession() {
		serverModManifestKnown = false;
		RendererVisualProfile.preferenceChanged();
	}

	/** Legacy server manifest synchronization; versions deliberately do not participate. */
	public static void setServerModIds(Collection<String> modIds) {
		serverModManifestKnown = true;
	}

	public static boolean hasServerModManifest() { return serverModManifestKnown; }

	public static String blockingModReason() {
		String blocked = RendererModPolicy.blockingReason(FabricLoader.getInstance().getAllMods().stream()
				.map(container -> container.getMetadata().getId()).toList());
		return blocked != null ? blocked : RendererOptionalCompatibility.blockingReason();
	}

	public static Compatibility inspect(Minecraft client) {
		String blocked = blockingModReason();
		if (blocked != null) return new Compatibility(false, blocked);
		String preparing = RendererVisualProfile.preparationReason();
		if (preparing != null) return new Compatibility(false, preparing);
		YandexMapShaderGuard.Compatibility shader = YandexMapShaderGuard.inspect();
		if (!shader.compatible()) return new Compatibility(false, shader.reason());
		if (client == null || client.getResourcePackRepository() == null) return new Compatibility(false, "resource-state-unavailable");
		var localPacks = RendererVisualProfile.localPacks(client);
		if (!localPacks.isEmpty()) return new Compatibility(false, "resource-pack-active:" + localPacks.getFirst().getId());
		return new Compatibility(true, "normalized-render-profile");
	}

	public record Compatibility(boolean compatible, String reason) { }
}
