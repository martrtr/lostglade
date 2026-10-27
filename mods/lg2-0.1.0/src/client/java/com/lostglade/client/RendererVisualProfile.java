package com.lostglade.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import com.lostglade.client.maprender.YandexMapRenderClient;
import com.lostglade.client.maprender.YandexMapShaderGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** One reversible clean profile per volunteer session, never a resource reload per frame. */
public final class RendererVisualProfile {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path JOURNAL = FabricLoader.getInstance().getConfigDir().resolve("lostglade-renderer-visual-backup.json");
	private static Snapshot backup;
	private static boolean recoveryChecked;
	private static boolean prepared;
	private static boolean changing;
	private static boolean restoring;
	private static boolean rollbackNeeded;
	private static String failure;
	private static int ticks;

	private RendererVisualProfile() { }

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(RendererVisualProfile::tick);
	}

	public static String preparationReason() {
		if (failure != null) return failure;
		return changing || !prepared ? "preparing-render-profile" : null;
	}

	public static void preferenceChanged() { failure = null; ticks = 19; }

	static List<Pack> localPacks(Minecraft client) {
		return client.getResourcePackRepository().getSelectedPacks().stream()
				.filter(pack -> RendererModPolicy.suspendPack(pack.getId(),
						pack.isRequired() || FabricLoader.getInstance().isModLoaded(pack.getId()), pack.getPackSource() == PackSource.SERVER))
				.toList();
	}

	private static List<String> serverPacks(Minecraft client) {
		return client.getResourcePackRepository().getSelectedPacks().stream()
				.filter(pack -> RendererModPolicy.isServerPack(pack.getId(), pack.getPackSource() == PackSource.SERVER))
				.map(Pack::getId).toList();
	}

	private static void tick(Minecraft client) {
		if (client == null || client.options == null || changing || client.getOverlay() != null) return;
		if (!recoveryChecked) {
			recoveryChecked = true;
			if (Files.exists(JOURNAL)) {
				try {
					backup = GSON.fromJson(Files.readString(JOURNAL), Snapshot.class);
					if (backup == null || backup.selectedPacks() == null || backup.optionPacks() == null || backup.incompatiblePacks() == null) {
						throw new IllegalStateException("Invalid renderer visual backup");
					}
					restore(client);
				} catch (Exception exception) { fail("render-profile-backup-error", exception); }
				return;
			}
		}
		if (++ticks % 20 != 0) return;
		if (failure != null) {
			if (rollbackNeeded) { rollbackNeeded = false; restore(client); }
			return;
		}
		boolean wanted = client.getConnection() != null && RendererBotRenderEnvironmentGuard.hasServerModManifest()
				&& (RendererBotClientMode.isEnabled() || LostgladeClientSettings.isRenderContributionEnabled())
				&& RendererBotRenderEnvironmentGuard.blockingModReason() == null;
		if (!wanted) {
			prepared = false;
			if (backup != null) restore(client);
			return;
		}
		try {
			boolean shaders = Boolean.TRUE.equals(YandexMapShaderGuard.configuredShadersEnabled());
			if (!localPacks(client).isEmpty() || shaders) {
				// Stop accepting work before changing global textures or shader pipelines.
				prepared = false;
				RendererBotClientCapture.onVolunteerPreferenceChanged(false);
				RendererBotClientVideoRecording.onVolunteerPreferenceChanged(false);
				YandexMapRenderClient.onVisualProfileChanging();
				enter(client);
			} else if (!prepared) {
				prepared = true;
				announce();
			}
		} catch (Exception exception) { fail("render-profile-preparation-failed", exception); }
	}

	private static void enter(Minecraft client) throws Exception {
		if (backup == null) {
			backup = new Snapshot(client.getResourcePackRepository().getSelectedPacks().stream()
					.filter(pack -> !RendererModPolicy.isServerPack(pack.getId(), pack.getPackSource() == PackSource.SERVER))
					.map(Pack::getId).toList(), List.copyOf(client.options.resourcePacks),
					List.copyOf(client.options.incompatibleResourcePacks), YandexMapShaderGuard.configuredShadersEnabled());
			Files.createDirectories(JOURNAL.getParent());
			Path temporary = JOURNAL.resolveSibling(JOURNAL.getFileName() + ".tmp");
			Files.writeString(temporary, GSON.toJson(backup));
			Files.move(temporary, JOURNAL, StandardCopyOption.REPLACE_EXISTING);
		}
		changing = true;
		restoring = false;
		announce();
		try {
			if (Boolean.TRUE.equals(YandexMapShaderGuard.configuredShadersEnabled())) YandexMapShaderGuard.setShadersEnabled(false);
			List<String> suspended = localPacks(client).stream().map(Pack::getId).toList();
			List<String> clean = client.getResourcePackRepository().getSelectedPacks().stream()
					.map(Pack::getId).filter(id -> !suspended.contains(id)).toList();
			client.options.resourcePacks.removeAll(suspended);
			client.options.incompatibleResourcePacks.removeAll(suspended);
			client.options.save();
			client.getResourcePackRepository().setSelected(clean);
			finish(client, suspended.isEmpty() ? CompletableFuture.completedFuture(null) : client.reloadResourcePacks(), false);
		} catch (Exception exception) {
			changing = false;
			throw exception;
		}
	}

	private static void restore(Minecraft client) {
		changing = true;
		restoring = true;
		prepared = false;
		YandexMapRenderClient.onVisualProfileChanging();
		announce();
		try {
			Snapshot saved = backup;
			client.options.resourcePacks.clear();
			client.options.resourcePacks.addAll(saved.optionPacks());
			client.options.incompatibleResourcePacks.clear();
			client.options.incompatibleResourcePacks.addAll(saved.incompatiblePacks());
			client.options.save();
			if (saved.shadersEnabled() != null) YandexMapShaderGuard.setShadersEnabled(saved.shadersEnabled());
			client.getResourcePackRepository().setSelected(RendererModPolicy.restorePackSelection(saved.selectedPacks(), serverPacks(client)));
			finish(client, client.reloadResourcePacks(), true);
		} catch (Exception exception) {
			changing = false;
			fail("render-profile-restore-failed", exception);
		}
	}

	private static void finish(Minecraft client, CompletableFuture<Void> reload, boolean restoring) {
		reload.whenComplete((ignored, error) -> client.execute(() -> {
			changing = false;
			if (error != null) { fail("render-profile-reload-failed", error); return; }
			if (!restoring && (!localPacks(client).isEmpty() || !YandexMapShaderGuard.inspect().compatible())) {
				fail("render-profile-could-not-disable-visuals", new IllegalStateException("Local visual overrides remain enabled"));
				return;
			}
			if (restoring) {
				try { Files.deleteIfExists(JOURNAL); backup = null; }
				catch (Exception exception) { fail("render-profile-backup-error", exception); return; }
			}
			prepared = !restoring;
			announce();
		}));
	}

	private static void fail(String reason, Throwable exception) {
		failure = reason;
		rollbackNeeded = backup != null && !restoring;
		prepared = false;
		Lg2.LOGGER.warn("Could not prepare/restore the volunteer renderer visual profile", exception);
		announce();
	}

	private static void announce() {
		RendererBotVolunteerClient.sendRendererHello();
		YandexMapRenderClient.requestCapabilityRefresh();
	}

	private record Snapshot(List<String> selectedPacks, List<String> optionPacks,
			List<String> incompatiblePacks, Boolean shadersEnabled) { }
}
