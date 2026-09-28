package com.lostglade.server.maprender;

import com.lostglade.config.Lg2Config;
import com.lostglade.network.YandexMapRenderPayloads;
import com.lostglade.server.RendererBotPresenceSystem;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Phase-2 capability registry. It intentionally owns no map render jobs or scene state. */
public final class MapRenderWorkerRegistry {
	private static final int MAX_IN_FLIGHT_PER_WORKER = 1;
	private static final Map<UUID, WorkerState> WORKERS = new ConcurrentHashMap<>();
	private static volatile String canonicalProfileHash = "";

	private MapRenderWorkerRegistry() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(
				YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload.TYPE,
				(payload, context) -> {
					MinecraftServer server = context.player().level().getServer();
					if (server != null) {
						server.execute(() -> updateWorker(context.player(), payload));
					}
				}
		);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> {
			WORKERS.remove(handler.player.getUUID());
			recomputeCanonicalAndNotify(server);
		}));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			WORKERS.clear();
			canonicalProfileHash = "";
		});
	}

	public static List<WorkerState> workers() {
		List<WorkerState> copy = new ArrayList<>(WORKERS.values());
		copy.sort(Comparator.comparing(WorkerState::playerUuid));
		return List.copyOf(copy);
	}

	public static String canonicalProfileHash() {
		return canonicalProfileHash;
	}

	public static WorkerState worker(UUID playerUuid) {
		return playerUuid == null ? null : WORKERS.get(playerUuid);
	}

	public static List<WorkerState> eligibleWorkers(MinecraftServer server) {
		if (server == null) return List.of();
		return workers().stream()
				.filter(WorkerState::eligible)
				.filter(worker -> server.getPlayerList().getPlayer(worker.playerUuid()) != null)
				.sorted(Comparator.comparing(WorkerState::dedicated).reversed().thenComparing(WorkerState::playerUuid))
				.toList();
	}

	public static WorkerState findEligibleDedicated(MinecraftServer server) {
		if (server == null) {
			return null;
		}
		return workers().stream()
				.filter(WorkerState::dedicated)
				.filter(WorkerState::eligible)
				.filter(worker -> server.getPlayerList().getPlayer(worker.playerUuid()) != null)
				.findFirst()
				.orElse(null);
	}

	private static void updateWorker(ServerPlayer player, YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload payload) {
		if (player == null || payload == null) {
			return;
		}
		boolean dedicated = RendererBotPresenceSystem.isRendererBot(player);
		String baseReason = baseEligibilityReason(payload, dedicated, Lg2Config.get().yandexMapRendererAllowPlayerVolunteers);
		String profileHash = baseReason == null
				? combinedProfileHash(payload.resourceProfileHash(), payload.clientBuildFingerprint())
				: "";
		WORKERS.put(player.getUUID(), new WorkerState(
				player.getUUID(),
				player.getScoreboardName(),
				dedicated,
				payload,
				profileHash,
				baseReason,
				false,
				baseReason == null ? "awaiting-profile-election" : baseReason,
				System.currentTimeMillis(),
				MAX_IN_FLIGHT_PER_WORKER
		));
		recomputeCanonicalAndNotify(player.level().getServer());
	}

	static String baseEligibilityReason(
			YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload capability,
			boolean dedicated,
			boolean allowPlayerVolunteers
	) {
		if (capability == null || capability.mapProtocolVersion() != YandexMapRenderPayloads.PROTOCOL_VERSION) {
			return "protocol-mismatch";
		}
		if (!dedicated && !allowPlayerVolunteers) {
			return "player-volunteers-disabled";
		}
		if (!capability.enabled()) {
			return "disabled-by-client";
		}
		if ("OFF".equalsIgnoreCase(capability.mode())) {
			return "disabled-by-client";
		}
		if (!"IDLE_ONLY".equalsIgnoreCase(capability.mode()) && !"ALWAYS".equalsIgnoreCase(capability.mode())) {
			return "invalid-mode";
		}
		if (!capability.shaderCompatible()) {
			return "shader-pack-active";
		}
		if (capability.maxTilePixels() < MapRenderProfile.CURRENT.tilePixels()) {
			return "tile-size-unsupported";
		}
		if (capability.resourceProfileHash() == null || capability.resourceProfileHash().isBlank()) {
			return "missing-resource-profile";
		}
		if (capability.clientBuildFingerprint() == null || capability.clientBuildFingerprint().isBlank()) {
			return "missing-client-build";
		}
		return null;
	}

	private static void recomputeCanonicalAndNotify(MinecraftServer server) {
		String previous = canonicalProfileHash;
		String selected = chooseCanonicalProfile(WORKERS.values(), previous);
		canonicalProfileHash = selected;
		for (Map.Entry<UUID, WorkerState> entry : WORKERS.entrySet()) {
			WorkerState state = entry.getValue();
			boolean eligible = state.baseReason() == null && !selected.isBlank() && selected.equals(state.profileHash());
			String reason = state.baseReason() != null
					? state.baseReason()
					: eligible ? "ready" : "resource-profile-mismatch";
			WorkerState updated = state.withEligibility(eligible, reason);
			entry.setValue(updated);
			if (server != null) {
				ServerPlayer player = server.getPlayerList().getPlayer(updated.playerUuid());
				if (player != null && ServerPlayNetworking.canSend(player, YandexMapRenderPayloads.MapWorkerStatusS2CPayload.TYPE)) {
					ServerPlayNetworking.send(player, new YandexMapRenderPayloads.MapWorkerStatusS2CPayload(eligible, reason, selected));
				}
			}
		}
		if (server != null && !java.util.Objects.equals(previous, selected)) {
			YandexMapRenderService.refreshAsync(server);
		}
	}

	static String chooseCanonicalProfile(Iterable<WorkerState> workers, String previousCanonical) {
		Map<String, ProfileCohort> cohorts = new java.util.HashMap<>();
		for (WorkerState worker : workers) {
			if (worker == null || worker.baseReason() != null || worker.profileHash().isBlank()) continue;
			cohorts.compute(worker.profileHash(), (profile, previous) -> {
				ProfileCohort cohort = previous == null ? new ProfileCohort() : previous;
				cohort.workerCount++;
				if (worker.dedicated()) cohort.hasDedicated = true;
				return cohort;
			});
		}
		if (cohorts.isEmpty()) {
			return previousCanonical == null ? "" : previousCanonical;
		}

		boolean dedicatedAvailable = cohorts.values().stream().anyMatch(cohort -> cohort.hasDedicated);
		List<Map.Entry<String, ProfileCohort>> eligibleCohorts = cohorts.entrySet().stream()
				.filter(entry -> !dedicatedAvailable || entry.getValue().hasDedicated)
				.toList();
		int largest = eligibleCohorts.stream().mapToInt(entry -> entry.getValue().workerCount).max().orElse(0);
		List<String> largestProfiles = eligibleCohorts.stream()
				.filter(entry -> entry.getValue().workerCount == largest)
				.map(Map.Entry::getKey)
				.sorted()
				.toList();
		// A dedicated renderer anchors the visual contract whenever one is online.
		// Matching volunteers still increase that cohort's throughput; incompatible
		// volunteer cohorts never outvote the canonical dedicated resource profile.
		if (previousCanonical != null && largestProfiles.contains(previousCanonical)) {
			return previousCanonical;
		}
		return largestProfiles.getFirst();
	}

	private static final class ProfileCohort {
		private int workerCount;
		private boolean hasDedicated;
	}

	static String combinedProfileHash(String resourceProfileHash, String clientBuildFingerprint) {
		String value = MapRenderProfile.CURRENT.contractHash() + "\n" + resourceProfileHash + "\n" + clientBuildFingerprint;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public record WorkerState(
			UUID playerUuid,
			String playerName,
			boolean dedicated,
			YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload capability,
			String profileHash,
			String baseReason,
			boolean eligible,
			String statusReason,
			long lastCapabilityAtEpochMs,
			int maxInFlight
	) {
		private WorkerState withEligibility(boolean eligible, String reason) {
			return new WorkerState(
					this.playerUuid,
					this.playerName,
					this.dedicated,
					this.capability,
					this.profileHash,
					this.baseReason,
					eligible,
					reason,
					this.lastCapabilityAtEpochMs,
					this.maxInFlight
			);
		}
	}
}
