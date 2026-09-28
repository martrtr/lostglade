package com.lostglade.server.maprender;

import com.lostglade.network.YandexMapRenderPayloads;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class YandexMapRenderPhaseTwoTest {
	private YandexMapRenderPhaseTwoTest() {
	}

	public static void main(String[] args) throws Exception {
		capabilityAdmissionIsConservative();
		profileIdentityIncludesResourcesAndBuild();
		resourceFingerprintIgnoresUnrelatedClientPacks();
		dedicatedRendererAnchorsCanonicalProfile();
		volunteerCanonicalProfileStaysStable();
		canonicalProfileSurvivesTemporaryWorkerOutage();
		transportProtocolStaysIndependentFromCameraProtocol();
		System.out.println("Yandex map renderer Phase 2 checks passed");
	}

	private static void capabilityAdmissionIsConservative() {
		YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload valid = capability(
				YandexMapRenderPayloads.PROTOCOL_VERSION, true, "ALWAYS", true, "resources-a", 256, "build-a"
		);
		require(MapRenderWorkerRegistry.baseEligibilityReason(valid, false, true) == null, "valid volunteer must be admissible");
		require("player-volunteers-disabled".equals(MapRenderWorkerRegistry.baseEligibilityReason(valid, false, false)), "server must be able to reject normal volunteers");
		require(MapRenderWorkerRegistry.baseEligibilityReason(valid, true, false) == null, "dedicated renderer must not be blocked by volunteer admission toggle");

		require("protocol-mismatch".equals(MapRenderWorkerRegistry.baseEligibilityReason(
				capability(999, true, "ALWAYS", true, "resources-a", 256, "build-a"), false, true
		)), "protocol mismatch must reject worker");
		require("protocol-mismatch".equals(MapRenderWorkerRegistry.baseEligibilityReason(
				capability(YandexMapRenderPayloads.PROTOCOL_VERSION - 1, true, "ALWAYS", true, "resources-a", 256, "build-a"), false, true
		)), "immediately previous map protocol must not render into the current visual namespace");
		require("disabled-by-client".equals(MapRenderWorkerRegistry.baseEligibilityReason(
				capability(YandexMapRenderPayloads.PROTOCOL_VERSION, false, "OFF", true, "", 256, "build-a"), false, true
		)), "client OFF must reject worker");
		require("shader-pack-active".equals(MapRenderWorkerRegistry.baseEligibilityReason(
				capability(YandexMapRenderPayloads.PROTOCOL_VERSION, true, "ALWAYS", false, "resources-a", 256, "build-a"), false, true
		)), "shader-incompatible client must reject worker");
		require("tile-size-unsupported".equals(MapRenderWorkerRegistry.baseEligibilityReason(
				capability(YandexMapRenderPayloads.PROTOCOL_VERSION, true, "IDLE_ONLY", true, "resources-a", 128, "build-a"), false, true
		)), "worker below canonical tile size must reject");
		require("missing-resource-profile".equals(MapRenderWorkerRegistry.baseEligibilityReason(
				capability(YandexMapRenderPayloads.PROTOCOL_VERSION, true, "IDLE_ONLY", true, "", 256, "build-a"), false, true
		)), "missing visual profile must reject worker");
	}

	private static void profileIdentityIncludesResourcesAndBuild() {
		String base = MapRenderWorkerRegistry.combinedProfileHash("resources-a", "build-a");
		require(base.equals(MapRenderWorkerRegistry.combinedProfileHash("resources-a", "build-a")), "same inputs must produce deterministic profile hash");
		require(!base.equals(MapRenderWorkerRegistry.combinedProfileHash("resources-b", "build-a")), "resource contents must affect canonical profile");
		require(!base.equals(MapRenderWorkerRegistry.combinedProfileHash("resources-a", "build-b")), "client build must affect canonical profile");
	}

	private static void resourceFingerprintIgnoresUnrelatedClientPacks() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String fingerprint = Files.readString(project.resolve("src/client/java/com/lostglade/client/maprender/YandexMapResourceProfileFingerprint.java"));
		require(fingerprint.contains("CANONICAL_RENDER_NAMESPACES = Set.of(\"minecraft\", \"lg2\")"), "distributed workers must fingerprint canonical world-render namespaces");
		require(!fingerprint.contains("selected-pack-order"), "unrelated selected pack names must not split visually identical worker cohorts");
		require(!fingerprint.contains("sourcePackId()"), "resource-pack identity must not matter when resolved visual bytes are identical");
	}

	private static void dedicatedRendererAnchorsCanonicalProfile() {
		MapRenderWorkerRegistry.WorkerState dedicated = worker("00000000-0000-0000-0000-000000000099", true, "profile-dedicated");
		MapRenderWorkerRegistry.WorkerState volunteerA = worker("00000000-0000-0000-0000-000000000001", false, "profile-players");
		MapRenderWorkerRegistry.WorkerState volunteerB = worker("00000000-0000-0000-0000-000000000002", false, "profile-players");
		String selected = MapRenderWorkerRegistry.chooseCanonicalProfile(List.of(dedicated, volunteerA, volunteerB), "profile-players");
		require("profile-dedicated".equals(selected), "online dedicated renderer must anchor canonical profile even when an incompatible volunteer cohort is larger");

		MapRenderWorkerRegistry.WorkerState matchingVolunteer = worker("00000000-0000-0000-0000-000000000003", false, "profile-dedicated");
		selected = MapRenderWorkerRegistry.chooseCanonicalProfile(List.of(dedicated, matchingVolunteer, volunteerA, volunteerB), "profile-dedicated");
		require("profile-dedicated".equals(selected), "matching volunteers must join the dedicated canonical cohort");

		MapRenderWorkerRegistry.WorkerState dedicatedB = worker("00000000-0000-0000-0000-000000000098", true, "profile-b");
		MapRenderWorkerRegistry.WorkerState helperB1 = worker("00000000-0000-0000-0000-000000000004", false, "profile-b");
		MapRenderWorkerRegistry.WorkerState helperB2 = worker("00000000-0000-0000-0000-000000000005", false, "profile-b");
		selected = MapRenderWorkerRegistry.chooseCanonicalProfile(List.of(dedicated, dedicatedB, helperB1, helperB2), "profile-dedicated");
		require("profile-b".equals(selected), "when multiple dedicated profiles exist, the largest dedicated-backed compatible cohort should provide throughput deterministically");
	}

	private static void volunteerCanonicalProfileStaysStable() {
		MapRenderWorkerRegistry.WorkerState first = worker("00000000-0000-0000-0000-000000000001", false, "profile-a");
		MapRenderWorkerRegistry.WorkerState second = worker("00000000-0000-0000-0000-000000000002", false, "profile-b");
		require("profile-b".equals(MapRenderWorkerRegistry.chooseCanonicalProfile(List.of(first, second), "profile-b")), "existing volunteer canonical profile must remain stable while represented");
		require("profile-a".equals(MapRenderWorkerRegistry.chooseCanonicalProfile(List.of(first, second), "missing-profile")), "new volunteer election must be deterministic");
	}

	private static void canonicalProfileSurvivesTemporaryWorkerOutage() {
		require("profile-cache".equals(MapRenderWorkerRegistry.chooseCanonicalProfile(List.of(), "profile-cache")),
				"temporary worker outage must not make committed cache namespace disappear");
	}

	private static void transportProtocolStaysIndependentFromCameraProtocol() throws Exception {
		Path project = Path.of("").toAbsolutePath();
		String payloads = Files.readString(project.resolve("src/main/java/com/lostglade/network/YandexMapRenderPayloads.java"));
		String registry = Files.readString(project.resolve("src/main/java/com/lostglade/server/maprender/MapRenderWorkerRegistry.java"));
		require(payloads.contains("MapWorkerCapabilitiesC2SPayload") && payloads.contains("MapWorkerStatusS2CPayload"), "dedicated map capability/status messages must remain explicit");
		require(!payloads.contains("RendererBotPayloads") && !payloads.contains("RendererBotCameraSystem"), "map transport must not overload camera protocol types");
		require(!registry.contains("RendererBotCameraSystem"), "map worker registry must stay independent from camera renderer state");
	}

	private static YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload capability(
			int protocol, boolean enabled, String mode, boolean shaderCompatible, String resources, int pixels, String build
	) {
		return new YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload(protocol, enabled, mode, shaderCompatible, resources, pixels, build);
	}

	private static MapRenderWorkerRegistry.WorkerState worker(String uuid, boolean dedicated, String profileHash) {
		YandexMapRenderPayloads.MapWorkerCapabilitiesC2SPayload capability = capability(
				YandexMapRenderPayloads.PROTOCOL_VERSION, true, "ALWAYS", true, "resources", 256, "build"
		);
		return new MapRenderWorkerRegistry.WorkerState(
				UUID.fromString(uuid), uuid, dedicated, capability, profileHash, null, false, "awaiting-profile-election", 1L, 1
		);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
