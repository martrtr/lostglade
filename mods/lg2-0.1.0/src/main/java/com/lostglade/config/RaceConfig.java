package com.lostglade.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.SerializedName;
import com.lostglade.Lg2;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public final class RaceConfig {
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.registerTypeAdapter(RaceAbilityConfig.class, new RaceAbilityConfigSerializer())
			.create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(Lg2.MOD_ID + "-races.json");
	private static final int MAX_PRICE_BITCOINS = 1_000_000;
	private static final String NO_RACE_ID = "no_race";
	public static final double INFINITE_COOLDOWN_SECONDS = -1.0D;

	private static ConfigData data = ConfigData.defaults();

	private RaceConfig() {
	}

	public static synchronized void load() {
		ConfigData loaded = readOrCreate();
		boolean changed = sanitize(loaded);
		data = loaded;

		if (changed) {
			write(data);
		}
	}

	public static ConfigData get() {
		return data;
	}

	public static synchronized void save() {
		write(data);
	}

	private static ConfigData readOrCreate() {
		if (!Files.exists(PATH)) {
			ConfigData defaults = ConfigData.defaults();
			write(defaults);
			return defaults;
		}

		try (Reader reader = Files.newBufferedReader(PATH)) {
			ConfigData parsed = ConfigVariableResolver.fromJsonWithVariables(GSON, reader, ConfigData.class);
			if (parsed == null) {
				Lg2.LOGGER.warn("Race config {} is empty, resetting to defaults", PATH);
				return ConfigData.defaults();
			}
			return parsed;
		} catch (Exception exception) {
			Lg2.LOGGER.warn("Failed to read race config {}, using defaults", PATH, exception);
			return ConfigData.defaults();
		}
	}

	private static void write(ConfigData configData) {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(configData, writer);
			}
		} catch (IOException exception) {
			Lg2.LOGGER.error("Failed to write race config {}", PATH, exception);
		}
	}

	private static boolean sanitize(ConfigData configData) {
		boolean changed = false;
		if (configData.races == null) {
			configData.races = new ArrayList<>();
			changed = true;
		}

		for (int i = 0; i < configData.races.size(); i++) {
			PlayerRaceConfig race = configData.races.get(i);
			if (race == null) {
				configData.races.set(i, PlayerRaceConfig.template());
				changed = true;
				continue;
			}
			changed |= sanitizeRace(race);
		}
		return changed;
	}

	private static boolean sanitizeRace(PlayerRaceConfig race) {
		boolean changed = false;
		changed |= normalizeString(race.id, "example_race", value -> race.id = value);
		changed |= normalizeString(race.displayName, "Пример Расы", value -> race.displayName = value);
		changed |= normalizeString(race.ownerNickname, "", value -> race.ownerNickname = value);
		changed |= normalizeString(race.description, "", value -> race.description = value);
		changed |= ensureAbility(race, RaceAbilitySlot.ATTACK);
		changed |= ensureAbility(race, RaceAbilitySlot.DEFENSE);
		changed |= ensureAbility(race, RaceAbilitySlot.UNIQUE_ABILITY);
		changed |= ensureAbility(race, RaceAbilitySlot.SHNYAGA);
		changed |= ensureAbility(race, RaceAbilitySlot.STOCK);
		boolean preserveBlankAbilityNames = NO_RACE_ID.equals(race.id == null ? "" : race.id.trim());
		changed |= sanitizeAbility(race.attack, RaceAbilitySlot.ATTACK, preserveBlankAbilityNames);
		changed |= sanitizeAbility(race.defense, RaceAbilitySlot.DEFENSE, preserveBlankAbilityNames);
		changed |= sanitizeAbility(race.uniqueAbility, RaceAbilitySlot.UNIQUE_ABILITY, preserveBlankAbilityNames);
		changed |= sanitizeAbility(race.shnyaga, RaceAbilitySlot.SHNYAGA, preserveBlankAbilityNames);
		changed |= sanitizeAbility(race.stock, RaceAbilitySlot.STOCK, preserveBlankAbilityNames);
		return changed;
	}

	private static boolean ensureAbility(PlayerRaceConfig race, RaceAbilitySlot slot) {
		RaceAbilityConfig current = switch (slot) {
			case ATTACK -> race.attack;
			case DEFENSE -> race.defense;
			case UNIQUE_ABILITY -> race.uniqueAbility;
			case SHNYAGA -> race.shnyaga;
			case STOCK -> race.stock;
		};
		if (current != null) {
			return false;
		}

		RaceAbilityConfig replacement = RaceAbilityConfig.defaults(slot);
		switch (slot) {
			case ATTACK -> race.attack = replacement;
			case DEFENSE -> race.defense = replacement;
			case UNIQUE_ABILITY -> race.uniqueAbility = replacement;
			case SHNYAGA -> race.shnyaga = replacement;
			case STOCK -> race.stock = replacement;
		}
		return true;
	}

	private static boolean sanitizeAbility(RaceAbilityConfig ability, RaceAbilitySlot slot, boolean preserveBlankName) {
		boolean changed = false;
		changed |= normalizeString(ability.abilityId, slot.defaultAbilityId, value -> ability.abilityId = value);
		changed |= normalizeString(ability.name, preserveBlankName ? "" : slot.defaultDisplayName, value -> ability.name = value);
		changed |= normalizeString(ability.description, "", value -> ability.description = value);
		changed |= normalizePrice(ability.priceBitcoins, value -> ability.priceBitcoins = value);
		changed |= normalizeCooldownSeconds(ability.cooldownSeconds, value -> ability.cooldownSeconds = value);
		changed |= normalizeNonNegative(ability.activationRangeBlocks, value -> ability.activationRangeBlocks = value);
		changed |= normalizeNonNegative(ability.durationSeconds, value -> ability.durationSeconds = value);
		changed |= normalizeNonNegative(ability.innerMinDistanceBlocks, value -> ability.innerMinDistanceBlocks = value);
		changed |= normalizeNonNegative(ability.followMaxDistanceBlocks, value -> ability.followMaxDistanceBlocks = value);
		changed |= normalizeNonNegative(ability.maxOutsideAreaSeconds, value -> ability.maxOutsideAreaSeconds = value);
		changed |= normalizeNonNegative(ability.healthPoints, value -> ability.healthPoints = value);
		changed |= normalizeNonNegative(ability.reflectedDamageRatio, value -> ability.reflectedDamageRatio = value);
		changed |= normalizeNonNegative(ability.summonLifetimeSeconds, value -> ability.summonLifetimeSeconds = value);
		changed |= normalizeNonNegative(ability.summonAfterKillSeconds, value -> ability.summonAfterKillSeconds = value);
		changed |= normalizeNonNegative(ability.minGrowthSeconds, value -> ability.minGrowthSeconds = value);
		changed |= normalizeNonNegative(ability.maxGrowthSeconds, value -> ability.maxGrowthSeconds = value);
		changed |= normalizeNonNegative(ability.tubochkaBurnSeconds, value -> ability.tubochkaBurnSeconds = value);
		changed |= normalizeNonNegative(ability.tubochkaMaxReleaseSmokeParticles, value -> ability.tubochkaMaxReleaseSmokeParticles = value);
		changed |= normalizeNonNegative(ability.methadoneAddictionSeconds, value -> ability.methadoneAddictionSeconds = value);
		changed |= normalizeNonNegative(ability.methadoneWithdrawalStartSeconds, value -> ability.methadoneWithdrawalStartSeconds = value);
		changed |= normalizeChance(ability.cocaineHallucinationChance, value -> ability.cocaineHallucinationChance = value);
		changed |= normalizeNonNegative(ability.cartelRaiderArmorDivider, value -> ability.cartelRaiderArmorDivider = value);
		changed |= normalizeNonNegative(ability.foodRestoreMultiplier, value -> ability.foodRestoreMultiplier = value);
		changed |= normalizeNonNegative(ability.copperGolemNoticeRangeBlocks, value -> ability.copperGolemNoticeRangeBlocks = value);
		changed |= normalizeNonNegative(ability.copperGogglesScanCooldownSeconds, value -> ability.copperGogglesScanCooldownSeconds = value);
		changed |= normalizeNonNegative(ability.copperGogglesOreSearchRadiusBlocks, value -> ability.copperGogglesOreSearchRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.copperGogglesOreSearchHighlightSeconds, value -> ability.copperGogglesOreSearchHighlightSeconds = value);
		changed |= normalizeNonNegative(ability.copperGogglesTrackingRadiusBlocks, value -> ability.copperGogglesTrackingRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.copperGogglesTrackingHighlightSeconds, value -> ability.copperGogglesTrackingHighlightSeconds = value);
		changed |= normalizeNonNegative(ability.womanFlowerCooldownSeconds, value -> ability.womanFlowerCooldownSeconds = value);
		changed |= normalizeNonNegative(ability.womanAnimalBreedCooldownSeconds, value -> ability.womanAnimalBreedCooldownSeconds = value);
		changed |= normalizeNonNegative(ability.womanAttackChargeRadiusBlocks, value -> ability.womanAttackChargeRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.womanAttackRangeBlocks, value -> ability.womanAttackRangeBlocks = value);
		changed |= normalizeNonNegative(ability.womanAttackDamage, value -> ability.womanAttackDamage = value);
		changed |= normalizeNonNegative(ability.womanAttackFollowSeconds, value -> ability.womanAttackFollowSeconds = value);
		changed |= normalizeNonNegative(ability.womanUniqueDropMinSeconds, value -> ability.womanUniqueDropMinSeconds = value);
		changed |= normalizeNonNegative(ability.womanUniqueDropMaxSeconds, value -> ability.womanUniqueDropMaxSeconds = value);
		changed |= normalizeChance(ability.womanUniqueDropChance, value -> ability.womanUniqueDropChance = value);
		changed |= normalizeNonNegative(ability.womanUniqueTradePriceIncrease, value -> ability.womanUniqueTradePriceIncrease = value);
		changed |= normalizeNonNegative(ability.womanUniqueAbsorptionHearts, value -> ability.womanUniqueAbsorptionHearts = value);
		changed |= normalizeNonNegative(ability.womanShnyagaTransferHearts, value -> ability.womanShnyagaTransferHearts = value);
		changed |= normalizeNonNegative(ability.womanShnyagaBuffRangeBlocks, value -> ability.womanShnyagaBuffRangeBlocks = value);
		changed |= normalizeNonNegative(ability.womanShnyagaRejectDamageHearts, value -> ability.womanShnyagaRejectDamageHearts = value);
		changed |= normalizeNonNegative(ability.womanShnyagaRejectDebuffSeconds, value -> ability.womanShnyagaRejectDebuffSeconds = value);
		changed |= normalizeNonNegative(ability.gennadiyDonkeyHealthPoints, value -> ability.gennadiyDonkeyHealthPoints = value);
		changed |= normalizeNonNegative(ability.gennadiyDonkeyArmorDivider, value -> ability.gennadiyDonkeyArmorDivider = value);
		changed |= normalizeNonNegative(ability.gennadiyDonkeyHealthRegenSeconds, value -> ability.gennadiyDonkeyHealthRegenSeconds = value);
		changed |= normalizeNonNegative(ability.gennadiyDonkeyBulletDamage, value -> ability.gennadiyDonkeyBulletDamage = value);
		changed |= normalizeNonNegative(ability.gennadiyDonkeyBulletRangeBlocks, value -> ability.gennadiyDonkeyBulletRangeBlocks = value);
		changed |= normalizeNonNegative(ability.gennadiyDonkeyFollowMaxDistanceBlocks, value -> ability.gennadiyDonkeyFollowMaxDistanceBlocks = value);
		changed |= normalizeNonNegative(ability.gennadiyDonkeyAmmoRegenSeconds, value -> ability.gennadiyDonkeyAmmoRegenSeconds = value);
		changed |= normalizeNonNegative(ability.gennadiyDefenseDurationSeconds, value -> ability.gennadiyDefenseDurationSeconds = value);
		changed |= normalizeNonNegative(ability.gennadiyDefenseKnockbackBlocksPerDamage, value -> ability.gennadiyDefenseKnockbackBlocksPerDamage = value);
		changed |= normalizeNonNegative(ability.gennadiyDefenseMaxKnockbackBlocks, value -> ability.gennadiyDefenseMaxKnockbackBlocks = value);
		changed |= normalizeNonNegative(ability.gennadiyDefenseWaveRangeBlocks, value -> ability.gennadiyDefenseWaveRangeBlocks = value);
		changed |= normalizeNonNegative(ability.gennadiyDefenseMinDamage, value -> ability.gennadiyDefenseMinDamage = value);
		changed |= normalizeNonNegative(ability.gennadiyDefenseMaxDamage, value -> ability.gennadiyDefenseMaxDamage = value);
		changed |= normalizeNonNegative(ability.gennadiyHookRangeBlocks, value -> ability.gennadiyHookRangeBlocks = value);
		changed |= normalizeNonNegative(ability.gennadiyHookDamage, value -> ability.gennadiyHookDamage = value);
		changed |= normalizeNonNegative(ability.gennadiyHookSlownessSeconds, value -> ability.gennadiyHookSlownessSeconds = value);
		changed |= normalizeNonNegative(ability.gennadiyRageHealthThresholdRatio, value -> ability.gennadiyRageHealthThresholdRatio = value);
		changed |= normalizeNonNegative(ability.gennadiyRageMeleeDamageBonusRatio, value -> ability.gennadiyRageMeleeDamageBonusRatio = value);
		changed |= normalizeNonNegative(ability.gennadiyReportCooldownSeconds, value -> ability.gennadiyReportCooldownSeconds = value);
		changed |= normalizeNonNegative(ability.markAxeRangeBlocks, value -> ability.markAxeRangeBlocks = value);
		changed |= normalizeNonNegative(ability.markAxeBleedingOneDamage, value -> ability.markAxeBleedingOneDamage = value);
		changed |= normalizeNonNegative(ability.markAxeBleedingTwoDamage, value -> ability.markAxeBleedingTwoDamage = value);
		changed |= normalizeNonNegative(ability.markAxeBleedingIntervalSeconds, value -> ability.markAxeBleedingIntervalSeconds = value);
		changed |= normalizeNonNegative(ability.markAxeBleedingTwoDurationSeconds, value -> ability.markAxeBleedingTwoDurationSeconds = value);
		changed |= normalizeNonNegative(ability.markDefenseRadiusBlocks, value -> ability.markDefenseRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.markDefenseDurationSeconds, value -> ability.markDefenseDurationSeconds = value);
		changed |= normalizeNonNegative(ability.markRageMaxPoints, value -> ability.markRageMaxPoints = value);
		changed |= normalizeNonNegative(ability.markRagePassiveKillPoints, value -> ability.markRagePassiveKillPoints = value);
		changed |= normalizeNonNegative(ability.markRageHostileKillPoints, value -> ability.markRageHostileKillPoints = value);
		changed |= normalizeNonNegative(ability.markRageBossKillPoints, value -> ability.markRageBossKillPoints = value);
		changed |= normalizeNonNegative(ability.markRagePlayerKillPoints, value -> ability.markRagePlayerKillPoints = value);
		changed |= normalizeNonNegative(ability.markRageDrainPointsPerSecond, value -> ability.markRageDrainPointsPerSecond = value);
		changed |= normalizeNonNegative(ability.markRageExhaustionSeconds, value -> ability.markRageExhaustionSeconds = value);
		changed |= normalizeNonNegative(ability.markStockPassiveKillHearts, value -> ability.markStockPassiveKillHearts = value);
		changed |= normalizeNonNegative(ability.markStockHostileKillHearts, value -> ability.markStockHostileKillHearts = value);
		changed |= normalizeNonNegative(ability.markStockBossKillHearts, value -> ability.markStockBossKillHearts = value);
		changed |= normalizeNonNegative(ability.markStockPlayerKillHearts, value -> ability.markStockPlayerKillHearts = value);
		changed |= normalizeNonNegative(ability.markStockFirstHitDamageMultiplier, value -> ability.markStockFirstHitDamageMultiplier = value);
		changed |= normalizeNonNegative(ability.markStockFirstHitResetSeconds, value -> ability.markStockFirstHitResetSeconds = value);
		changed |= normalizeNonNegative(ability.markStockMovementSpeedPenaltyRatio, value -> ability.markStockMovementSpeedPenaltyRatio = value);
		changed |= normalizeNonNegative(ability.markShieldBashRangeBlocks, value -> ability.markShieldBashRangeBlocks = value);
		changed |= normalizeNonNegative(ability.markShieldBashAngleDegrees, value -> ability.markShieldBashAngleDegrees = value);
		changed |= normalizeNonNegative(ability.markShieldBashForwardImpulseBlocks, value -> ability.markShieldBashForwardImpulseBlocks = value);
		changed |= normalizeNonNegative(ability.markShieldBashIronKnockbackBlocks, value -> ability.markShieldBashIronKnockbackBlocks = value);
		changed |= normalizeNonNegative(ability.markShieldBashGoldenKnockbackBlocks, value -> ability.markShieldBashGoldenKnockbackBlocks = value);
		changed |= normalizeNonNegative(ability.markShieldBashDiamondKnockbackBlocks, value -> ability.markShieldBashDiamondKnockbackBlocks = value);
		changed |= normalizeNonNegative(ability.markShieldBashNetheriteKnockbackBlocks, value -> ability.markShieldBashNetheriteKnockbackBlocks = value);
		changed |= normalizeNonNegative(ability.milkDefenseTeleportDistanceBlocks, value -> ability.milkDefenseTeleportDistanceBlocks = value);
		changed |= normalizeNonNegative(ability.puroSanAttackBackJumpBlocks, value -> ability.puroSanAttackBackJumpBlocks = value);
		changed |= normalizeNonNegative(ability.puroSanAttackWaveRadiusBlocks, value -> ability.puroSanAttackWaveRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.puroSanAttackDamage, value -> ability.puroSanAttackDamage = value);
		changed |= normalizeNonNegative(ability.puroSanAttackDebuffSeconds, value -> ability.puroSanAttackDebuffSeconds = value);
		changed |= normalizeNonNegative(ability.puroSanDefenseJumpBlocks, value -> ability.puroSanDefenseJumpBlocks = value);
		changed |= normalizeNonNegative(ability.puroSanUniqueDashBlocks, value -> ability.puroSanUniqueDashBlocks = value);
		changed |= normalizeNonNegative(ability.puroSanShnyagaChargeSeconds, value -> ability.puroSanShnyagaChargeSeconds = value);
		changed |= normalizeNonNegative(ability.puroSanShnyagaSpeedBonusRatio, value -> ability.puroSanShnyagaSpeedBonusRatio = value);
		changed |= normalizeNonNegative(ability.puroSanShnyagaShieldHealthRatio, value -> ability.puroSanShnyagaShieldHealthRatio = value);
		changed |= normalizeNonNegative(ability.puroSanStockTrailDurationSeconds, value -> ability.puroSanStockTrailDurationSeconds = value);
		changed |= normalizeNonNegative(ability.puroSanStockTrailDamage, value -> ability.puroSanStockTrailDamage = value);
		changed |= normalizeNonNegative(ability.puroSanStockBaseSpeedBonusRatio, value -> ability.puroSanStockBaseSpeedBonusRatio = value);
		changed |= normalizeNonNegative(ability.puroSanStockAimWarningRadiusBlocks, value -> ability.puroSanStockAimWarningRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.puroSanStockMeleeDamagePenaltyRatio, value -> ability.puroSanStockMeleeDamagePenaltyRatio = value);
		changed |= normalizeNonNegative(ability.puroSanStockMeleeVulnerabilityRatio, value -> ability.puroSanStockMeleeVulnerabilityRatio = value);
		changed |= normalizeNonNegative(ability.puroSanStockMaxHealthHearts, value -> ability.puroSanStockMaxHealthHearts = value);

		changed |= normalizeNonNegative(ability.kilkaAttackChargeSeconds, value -> ability.kilkaAttackChargeSeconds = value);
		changed |= normalizeNonNegative(ability.kilkaAttackRadiusBlocks, value -> ability.kilkaAttackRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.kilkaAttackKnockbackBlocks, value -> ability.kilkaAttackKnockbackBlocks = value);
		changed |= normalizeNonNegative(ability.kilkaAttackDamageHearts, value -> ability.kilkaAttackDamageHearts = value);
		changed |= normalizeNonNegative(ability.kilkaAttackAbilityBlockSeconds, value -> ability.kilkaAttackAbilityBlockSeconds = value);
		changed |= normalizeNonNegative(ability.kilkaAttackNauseaSeconds, value -> ability.kilkaAttackNauseaSeconds = value);
		changed |= normalizeNonNegative(ability.kilkaAttackSlownessSeconds, value -> ability.kilkaAttackSlownessSeconds = value);
		changed |= normalizeNonNegative(ability.kilkaAttackSelfWeaknessSeconds, value -> ability.kilkaAttackSelfWeaknessSeconds = value);
		changed |= normalizeNonNegative(ability.kilkaAttackSelfNauseaSeconds, value -> ability.kilkaAttackSelfNauseaSeconds = value);
		changed |= normalizeNonNegative(ability.kilkaDefenseRadiusBlocks, value -> ability.kilkaDefenseRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.kilkaDefenseDurationSeconds, value -> ability.kilkaDefenseDurationSeconds = value);
		changed |= normalizeNonNegative(ability.kilkaSalmonSprintSwimMultiplier, value -> ability.kilkaSalmonSprintSwimMultiplier = value);
		changed |= normalizeNonNegative(ability.kilkaSeaBeaconLinkRangeBlocks, value -> ability.kilkaSeaBeaconLinkRangeBlocks = value);
		changed |= normalizeNonNegative(ability.kilkaSeaBorderVisibilityBlocks, value -> ability.kilkaSeaBorderVisibilityBlocks = value);
		changed |= normalizeNonNegative(ability.milkPocketRecentDamageLockSeconds, value -> ability.milkPocketRecentDamageLockSeconds = value);
		changed |= normalizeNonNegative(ability.milkStockAvoidRadiusBlocks, value -> ability.milkStockAvoidRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.milkMouseSilverfishCount, (java.util.function.IntConsumer) value -> ability.milkMouseSilverfishCount = value);
		changed |= normalizeNonNegative(ability.littleDictatorAttackAggroRadiusBlocks, value -> ability.littleDictatorAttackAggroRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.littleDictatorAttackMobStrengthSeconds, value -> ability.littleDictatorAttackMobStrengthSeconds = value);
		changed |= normalizeNonNegative(ability.littleDictatorAttackPlayerSlownessSeconds, value -> ability.littleDictatorAttackPlayerSlownessSeconds = value);
		changed |= normalizeNonNegative(ability.littleDictatorDefenseRadiusBlocks, value -> ability.littleDictatorDefenseRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.littleDictatorDefensePingDurationSeconds, value -> ability.littleDictatorDefensePingDurationSeconds = value);
		changed |= normalizeNonNegative(ability.littleDictatorDefenseMinFakePingMs, (java.util.function.IntConsumer) value -> ability.littleDictatorDefenseMinFakePingMs = value);
		changed |= normalizeNonNegative(ability.littleDictatorDefenseMaxFakePingMs, (java.util.function.IntConsumer) value -> ability.littleDictatorDefenseMaxFakePingMs = value);
		changed |= normalizeNonNegative(ability.littleDictatorUniqueRadiusBlocks, value -> ability.littleDictatorUniqueRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.littleDictatorUniqueDurationSeconds, value -> ability.littleDictatorUniqueDurationSeconds = value);
		changed |= normalizeNonNegative(ability.littleDictatorUniqueShockIntervalSeconds, value -> ability.littleDictatorUniqueShockIntervalSeconds = value);
		changed |= normalizeNonNegative(ability.littleDictatorUniqueShockTargetCountMultiplierMin, value -> ability.littleDictatorUniqueShockTargetCountMultiplierMin = value);
		changed |= normalizeNonNegative(ability.littleDictatorUniqueShockTargetCountMultiplierMax, value -> ability.littleDictatorUniqueShockTargetCountMultiplierMax = value);
		changed |= normalizeNonNegative(ability.littleDictatorUniqueXpPerHealth, value -> ability.littleDictatorUniqueXpPerHealth = value);
		changed |= normalizeNonNegative(ability.littleDictatorUniqueFoodPerHealth, value -> ability.littleDictatorUniqueFoodPerHealth = value);
		changed |= normalizeNonNegative(ability.littleDictatorDecreeSanctionsDurationSeconds, value -> ability.littleDictatorDecreeSanctionsDurationSeconds = value);
		changed |= normalizeChance(ability.littleDictatorDecreeSanctionsDropChance, value -> ability.littleDictatorDecreeSanctionsDropChance = value);
		changed |= normalizeNonNegative(ability.littleDictatorDecreeTaxesDurationSeconds, value -> ability.littleDictatorDecreeTaxesDurationSeconds = value);
		changed |= normalizeNonNegative(ability.littleDictatorDecreeTaxesIntervalSeconds, value -> ability.littleDictatorDecreeTaxesIntervalSeconds = value);
		changed |= normalizeNonNegative(ability.littleDictatorDecreePropagandaDurationSeconds, value -> ability.littleDictatorDecreePropagandaDurationSeconds = value);
		changed |= normalizeChance(ability.littleDictatorDecreePropagandaTradeDiscount, value -> ability.littleDictatorDecreePropagandaTradeDiscount = value);
		changed |= normalizeNonNegative(ability.jetpackMaxRiseBlocks, value -> ability.jetpackMaxRiseBlocks = value);
		changed |= normalizeChance(ability.repulsorNaturalLightningChargeChance, value -> ability.repulsorNaturalLightningChargeChance = value);
		changed |= normalizeChance(ability.repulsorArmorIgnoreFraction, value -> ability.repulsorArmorIgnoreFraction = value);
		changed |= normalizeNonNegative(ability.gennadiyDonkeyMaxAmmo, (java.util.function.IntConsumer) value -> ability.gennadiyDonkeyMaxAmmo = value);
		changed |= normalizeNonNegative(ability.gennadiyDonkeyAmmoRegenAmount, (java.util.function.IntConsumer) value -> ability.gennadiyDonkeyAmmoRegenAmount = value);
		changed |= normalizeNonNegative(ability.gennadiyRageHasteLevel, (java.util.function.IntConsumer) value -> ability.gennadiyRageHasteLevel = value);
		changed |= normalizeNonNegative(ability.copperIngotFoodPoints, (java.util.function.IntConsumer) value -> ability.copperIngotFoodPoints = value);
		changed |= normalizeNonNegative(ability.repulsorMaxCharges, (java.util.function.IntConsumer) value -> ability.repulsorMaxCharges = value);
		changed |= normalizeNonNegative(ability.repulsorCopperIngotChargeRestore, (java.util.function.IntConsumer) value -> ability.repulsorCopperIngotChargeRestore = value);
		changed |= normalizeNonNegative(ability.repulsorNaturalLightningChargeRestore, (java.util.function.IntConsumer) value -> ability.repulsorNaturalLightningChargeRestore = value);
		changed |= normalizeNonNegative(ability.ancientUkrAttackSwordCount, (java.util.function.IntConsumer) value -> ability.ancientUkrAttackSwordCount = value);
		changed |= normalizeNonNegative(ability.ancientUkrAttackDurationSeconds, value -> ability.ancientUkrAttackDurationSeconds = value);
			changed |= normalizeNonNegative(ability.ancientUkrDefenseSmokeRadius, value -> ability.ancientUkrDefenseSmokeRadius = value);
			changed |= normalizeNonNegative(ability.ancientUkrDefenseSmokeDurationSeconds, value -> ability.ancientUkrDefenseSmokeDurationSeconds = value);
			changed |= normalizeNonNegative(ability.ancientUkrUniqueHighlightSeconds, value -> ability.ancientUkrUniqueHighlightSeconds = value);
			changed |= normalizeNonNegative(ability.ancientUkrShnyagaMaxDistanceBlocks, value -> ability.ancientUkrShnyagaMaxDistanceBlocks = value);
			changed |= normalizeNonNegative(ability.ancientUkrCreditMaxActive, (java.util.function.IntConsumer) value -> ability.ancientUkrCreditMaxActive = value);
			changed |= normalizeNonNegative(ability.ancientUkrCreditMaxPrincipalBitcoins, (java.util.function.IntConsumer) value -> ability.ancientUkrCreditMaxPrincipalBitcoins = value);
			changed |= normalizeNonNegative(ability.ancientUkrCreditMinHourlyPercent, value -> ability.ancientUkrCreditMinHourlyPercent = value);
			changed |= normalizeNonNegative(ability.ancientUkrCreditMaxHourlyPercent, value -> ability.ancientUkrCreditMaxHourlyPercent = value);
			changed |= normalizeNonNegative(ability.ancientUkrCreditMaxDebtMultiplier, value -> ability.ancientUkrCreditMaxDebtMultiplier = value);
			changed |= normalizeNonNegative(ability.ancientUkrCollectorMinIntervalMinutes, value -> ability.ancientUkrCollectorMinIntervalMinutes = value);
			changed |= normalizeNonNegative(ability.ancientUkrCollectorMaxIntervalMinutes, value -> ability.ancientUkrCollectorMaxIntervalMinutes = value);
		changed |= normalizeNonNegative(ability.ancientUkrStockPorkHungerMultiplier, value -> ability.ancientUkrStockPorkHungerMultiplier = value);
		changed |= normalizeNonNegative(ability.ancientUkrStockPorkPoisonSeconds, value -> ability.ancientUkrStockPorkPoisonSeconds = value);
		changed |= normalizeNonNegative(ability.orthodoxAttackEyeVisibilityRadiusBlocks, value -> ability.orthodoxAttackEyeVisibilityRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.orthodoxAttackDurationSeconds, value -> ability.orthodoxAttackDurationSeconds = value);
		changed |= normalizeNonNegative(ability.orthodoxAttackRemainingHealthHearts, value -> ability.orthodoxAttackRemainingHealthHearts = value);
		changed |= normalizeNonNegative(ability.orthodoxAttackBlindnessSeconds, value -> ability.orthodoxAttackBlindnessSeconds = value);
		changed |= normalizeNonNegative(ability.orthodoxDefenseDurationSeconds, value -> ability.orthodoxDefenseDurationSeconds = value);
		changed |= normalizeNonNegative(ability.orthodoxUniqueRadiusBlocks, value -> ability.orthodoxUniqueRadiusBlocks = value);
		changed |= normalizeNonNegative(ability.orthodoxHolinessSelfDefenseSeconds, value -> ability.orthodoxHolinessSelfDefenseSeconds = value);
		changed |= normalizeChance(ability.orthodoxHolinessBitcoinInventoryFraction, value -> ability.orthodoxHolinessBitcoinInventoryFraction = value);
		changed |= normalizeNonNegative(ability.orthodoxHolinessAfkMinutes, value -> ability.orthodoxHolinessAfkMinutes = value);
		changed |= normalizeNonNegative(ability.orthodoxHolinessNetherMinutes, value -> ability.orthodoxHolinessNetherMinutes = value);
		changed |= normalizeNonNegative(ability.orthodoxHolinessRepentanceMinecraftDays, value -> ability.orthodoxHolinessRepentanceMinecraftDays = value);
		changed |= normalizeNonNegative(ability.orthodoxHolinessRecoveryMinutes, value -> ability.orthodoxHolinessRecoveryMinutes = value);
		changed |= normalizeChance(ability.chance, value -> ability.chance = value);
		if (ability.womanUniqueDropMaxSeconds < ability.womanUniqueDropMinSeconds) {
			ability.womanUniqueDropMaxSeconds = ability.womanUniqueDropMinSeconds;
			changed = true;
		}
		if (ability.maxGrowthSeconds < ability.minGrowthSeconds) {
			ability.maxGrowthSeconds = ability.minGrowthSeconds;
			changed = true;
		}
		if (ability.methadoneAddictionSeconds > 0.0D && ability.methadoneWithdrawalStartSeconds > ability.methadoneAddictionSeconds) {
			ability.methadoneWithdrawalStartSeconds = ability.methadoneAddictionSeconds;
			changed = true;
		}
		if (ability.gennadiyDefenseMaxDamage < ability.gennadiyDefenseMinDamage) {
			ability.gennadiyDefenseMaxDamage = ability.gennadiyDefenseMinDamage;
			changed = true;
		}
		if (ability.ancientUkrCreditMaxHourlyPercent < ability.ancientUkrCreditMinHourlyPercent) {
			ability.ancientUkrCreditMaxHourlyPercent = ability.ancientUkrCreditMinHourlyPercent;
			changed = true;
		}
		return changed;
	}

	private static boolean normalizeCooldownSeconds(double value, java.util.function.DoubleConsumer setter) {
		double normalized;
		if (Double.isNaN(value)) {
			normalized = 0.0D;
		} else if (Double.compare(value, INFINITE_COOLDOWN_SECONDS) == 0) {
			normalized = INFINITE_COOLDOWN_SECONDS;
		} else {
			normalized = Math.max(0.0D, value);
		}
		if (Double.compare(value, normalized) == 0) {
			return false;
		}
		setter.accept(normalized);
		return true;
	}

	private static boolean normalizeNonNegative(double value, java.util.function.DoubleConsumer setter) {
		double normalized = Double.isNaN(value) ? 0.0D : Math.max(0.0D, value);
		if (Double.compare(value, normalized) == 0) {
			return false;
		}
		setter.accept(normalized);
		return true;
	}

	private static boolean normalizeChance(double value, java.util.function.DoubleConsumer setter) {
		double normalized = Double.isNaN(value) ? 0.0D : Math.max(0.0D, Math.min(1.0D, value));
		if (Double.compare(value, normalized) == 0) {
			return false;
		}
		setter.accept(normalized);
		return true;
	}

	private static boolean normalizePrice(int value, java.util.function.IntConsumer setter) {
		int normalized = Math.max(0, Math.min(MAX_PRICE_BITCOINS, value));
		if (value == normalized) {
			return false;
		}
		setter.accept(normalized);
		return true;
	}

	private static boolean normalizeNonNegative(int value, java.util.function.IntConsumer setter) {
		int normalized = Math.max(0, value);
		if (value == normalized) {
			return false;
		}
		setter.accept(normalized);
		return true;
	}

	private static boolean normalizeString(String value, String fallback, java.util.function.Consumer<String> setter) {
		String normalized = value == null ? fallback : value.trim();
		if (normalized.isEmpty() && !fallback.isEmpty()) {
			normalized = fallback;
		}
		if (value != null && value.equals(normalized)) {
			return false;
		}
		if (value == null && fallback.isEmpty()) {
			setter.accept("");
			return true;
		}
		setter.accept(normalized);
		return true;
	}

	private static final class RaceAbilityConfigSerializer implements JsonSerializer<RaceAbilityConfig> {
		@Override
		public JsonElement serialize(RaceAbilityConfig ability, Type type, JsonSerializationContext context) {
			JsonObject json = new JsonObject();
			json.addProperty("enabled", ability.enabled);
			addString(json, "abilityId", ability.abilityId);
			addString(json, "name", ability.name);
			addString(json, "description", ability.description);
			addIntIfNonZero(json, "priceBitcoins", ability.priceBitcoins);
			addDoubleIfNonZero(json, "cooldownSeconds", ability.cooldownSeconds);
			addDoubleIfNonZero(json, "activationRangeBlocks", ability.activationRangeBlocks);
			addDoubleIfNonZero(json, "durationSeconds", ability.durationSeconds);
			addDoubleIfNonZero(json, "innerMinDistanceBlocks", ability.innerMinDistanceBlocks);
			addDoubleIfNonZero(json, "followMaxDistanceBlocks", ability.followMaxDistanceBlocks);
			addDoubleIfNonZero(json, "maxOutsideAreaSeconds", ability.maxOutsideAreaSeconds);
			addDoubleIfNonZero(json, "healthPoints", ability.healthPoints);
			addDoubleIfNonZero(json, "reflectedDamageRatio", ability.reflectedDamageRatio);
			addDoubleIfNonZero(json, "summonLifetimeSeconds", ability.summonLifetimeSeconds);
			addDoubleIfNonZero(json, "summonAfterKillSeconds", ability.summonAfterKillSeconds);
			addDoubleIfNonZero(json, "minGrowthSeconds", ability.minGrowthSeconds);
			addDoubleIfNonZero(json, "maxGrowthSeconds", ability.maxGrowthSeconds);
			addDoubleIfNonZero(json, "tubochkaBurnSeconds", ability.tubochkaBurnSeconds);
			if (Double.compare(ability.tubochkaMaxReleaseSmokeParticles, 8.0D) != 0) {
				addDoubleIfNonZero(json, "tubochkaMaxReleaseSmokeParticles", ability.tubochkaMaxReleaseSmokeParticles);
			}
			addDoubleIfNonZero(json, "methadoneAddictionSeconds", ability.methadoneAddictionSeconds);
			addDoubleIfNonZero(json, "methadoneWithdrawalStartSeconds", ability.methadoneWithdrawalStartSeconds);
			addDoubleIfNonZero(json, "cocaineHallucinationChance", ability.cocaineHallucinationChance);
			addDoubleIfNonZero(json, "cartelRaiderArmorDivider", ability.cartelRaiderArmorDivider);
			addDoubleIfNonZero(json, "foodRestoreMultiplier", ability.foodRestoreMultiplier);
			addIntIfNonZero(json, "copperIngotFoodPoints", ability.copperIngotFoodPoints);
			addDoubleIfNonZero(json, "copperGolemNoticeRangeBlocks", ability.copperGolemNoticeRangeBlocks);
			addDoubleIfNonZero(json, "copperGogglesScanCooldownSeconds", ability.copperGogglesScanCooldownSeconds);
			addDoubleIfNonZero(json, "copperGogglesOreSearchRadiusBlocks", ability.copperGogglesOreSearchRadiusBlocks);
			addDoubleIfNonZero(json, "copperGogglesOreSearchHighlightSeconds", ability.copperGogglesOreSearchHighlightSeconds);
			addDoubleIfNonZero(json, "copperGogglesTrackingRadiusBlocks", ability.copperGogglesTrackingRadiusBlocks);
			addDoubleIfNonZero(json, "copperGogglesTrackingHighlightSeconds", ability.copperGogglesTrackingHighlightSeconds);
			addDoubleIfNonZero(json, "womanFlowerCooldownSeconds", ability.womanFlowerCooldownSeconds);
			addDoubleIfNonZero(json, "womanAnimalBreedCooldownSeconds", ability.womanAnimalBreedCooldownSeconds);
			addDoubleIfNonZero(json, "womanAttackChargeRadiusBlocks", ability.womanAttackChargeRadiusBlocks);
			addDoubleIfNonZero(json, "womanAttackRangeBlocks", ability.womanAttackRangeBlocks);
			addDoubleIfNonZero(json, "womanAttackDamage", ability.womanAttackDamage);
			addDoubleIfNonZero(json, "womanAttackFollowSeconds", ability.womanAttackFollowSeconds);
			addDoubleIfNonZero(json, "womanUniqueDropMinSeconds", ability.womanUniqueDropMinSeconds);
			addDoubleIfNonZero(json, "womanUniqueDropMaxSeconds", ability.womanUniqueDropMaxSeconds);
			addDoubleIfNonZero(json, "womanUniqueDropChance", ability.womanUniqueDropChance);
			addDoubleIfNonZero(json, "womanUniqueTradePriceIncrease", ability.womanUniqueTradePriceIncrease);
			addDoubleIfNonZero(json, "womanUniqueAbsorptionHearts", ability.womanUniqueAbsorptionHearts);
			addDoubleIfNonZero(json, "womanShnyagaTransferHearts", ability.womanShnyagaTransferHearts);
			addDoubleIfNonZero(json, "womanShnyagaBuffRangeBlocks", ability.womanShnyagaBuffRangeBlocks);
			addDoubleIfNonZero(json, "womanShnyagaRejectDamageHearts", ability.womanShnyagaRejectDamageHearts);
			addDoubleIfNonZero(json, "womanShnyagaRejectDebuffSeconds", ability.womanShnyagaRejectDebuffSeconds);
			addDoubleIfNonZero(json, "gennadiyDonkeyHealthPoints", ability.gennadiyDonkeyHealthPoints);
			addDoubleIfNonZero(json, "gennadiyDonkeyArmorDivider", ability.gennadiyDonkeyArmorDivider);
			addDoubleIfNonZero(json, "gennadiyDonkeyHealthRegenSeconds", ability.gennadiyDonkeyHealthRegenSeconds);
			addIntIfNonZero(json, "gennadiyDonkeyMaxAmmo", ability.gennadiyDonkeyMaxAmmo);
			addIntIfNonZero(json, "gennadiyDonkeyAmmoRegenAmount", ability.gennadiyDonkeyAmmoRegenAmount);
			addDoubleIfNonZero(json, "gennadiyDonkeyAmmoRegenSeconds", ability.gennadiyDonkeyAmmoRegenSeconds);
			addDoubleIfNonZero(json, "gennadiyDonkeyBulletDamage", ability.gennadiyDonkeyBulletDamage);
			addDoubleIfNonZero(json, "gennadiyDonkeyBulletRangeBlocks", ability.gennadiyDonkeyBulletRangeBlocks);
			addDoubleIfNonZero(json, "gennadiyDonkeyFollowMaxDistanceBlocks", ability.gennadiyDonkeyFollowMaxDistanceBlocks);
			addDoubleIfNonZero(json, "gennadiyDefenseDurationSeconds", ability.gennadiyDefenseDurationSeconds);
			addDoubleIfNonZero(json, "gennadiyDefenseKnockbackBlocksPerDamage", ability.gennadiyDefenseKnockbackBlocksPerDamage);
			addDoubleIfNonZero(json, "gennadiyDefenseMaxKnockbackBlocks", ability.gennadiyDefenseMaxKnockbackBlocks);
			addDoubleIfNonZero(json, "gennadiyDefenseWaveRangeBlocks", ability.gennadiyDefenseWaveRangeBlocks);
			addDoubleIfNonZero(json, "gennadiyDefenseMinDamage", ability.gennadiyDefenseMinDamage);
			addDoubleIfNonZero(json, "gennadiyDefenseMaxDamage", ability.gennadiyDefenseMaxDamage);
			addDoubleIfNonZero(json, "gennadiyHookRangeBlocks", ability.gennadiyHookRangeBlocks);
			addDoubleIfNonZero(json, "gennadiyHookDamage", ability.gennadiyHookDamage);
			addDoubleIfNonZero(json, "gennadiyHookSlownessSeconds", ability.gennadiyHookSlownessSeconds);
			addDoubleIfNonZero(json, "gennadiyRageHealthThresholdRatio", ability.gennadiyRageHealthThresholdRatio);
			addIntIfNonZero(json, "gennadiyRageHasteLevel", ability.gennadiyRageHasteLevel);
			addDoubleIfNonZero(json, "gennadiyRageMeleeDamageBonusRatio", ability.gennadiyRageMeleeDamageBonusRatio);
			addDoubleIfNonZero(json, "gennadiyReportCooldownSeconds", ability.gennadiyReportCooldownSeconds);
			addDoubleIfNonZero(json, "markAxeRangeBlocks", ability.markAxeRangeBlocks);
			addDoubleIfNonZero(json, "markAxeBleedingOneDamage", ability.markAxeBleedingOneDamage);
			addDoubleIfNonZero(json, "markAxeBleedingTwoDamage", ability.markAxeBleedingTwoDamage);
			addDoubleIfNonZero(json, "markAxeBleedingIntervalSeconds", ability.markAxeBleedingIntervalSeconds);
			addDoubleIfNonZero(json, "markAxeBleedingTwoDurationSeconds", ability.markAxeBleedingTwoDurationSeconds);
			addDoubleIfNonZero(json, "markDefenseRadiusBlocks", ability.markDefenseRadiusBlocks);
			addDoubleIfNonZero(json, "markDefenseDurationSeconds", ability.markDefenseDurationSeconds);
			addDoubleIfNonZero(json, "markRageMaxPoints", ability.markRageMaxPoints);
			addDoubleIfNonZero(json, "markRagePassiveKillPoints", ability.markRagePassiveKillPoints);
			addDoubleIfNonZero(json, "markRageHostileKillPoints", ability.markRageHostileKillPoints);
			addDoubleIfNonZero(json, "markRageBossKillPoints", ability.markRageBossKillPoints);
			addDoubleIfNonZero(json, "markRagePlayerKillPoints", ability.markRagePlayerKillPoints);
			addDoubleIfNonZero(json, "markRageDrainPointsPerSecond", ability.markRageDrainPointsPerSecond);
			addDoubleIfNonZero(json, "markRageExhaustionSeconds", ability.markRageExhaustionSeconds);
			addDoubleIfNonZero(json, "markStockPassiveKillHearts", ability.markStockPassiveKillHearts);
			addDoubleIfNonZero(json, "markStockHostileKillHearts", ability.markStockHostileKillHearts);
			addDoubleIfNonZero(json, "markStockBossKillHearts", ability.markStockBossKillHearts);
			addDoubleIfNonZero(json, "markStockPlayerKillHearts", ability.markStockPlayerKillHearts);
			addDoubleIfNonZero(json, "markStockFirstHitDamageMultiplier", ability.markStockFirstHitDamageMultiplier);
			addDoubleIfNonZero(json, "markStockFirstHitResetSeconds", ability.markStockFirstHitResetSeconds);
			addDoubleIfNonZero(json, "markStockMovementSpeedPenaltyRatio", ability.markStockMovementSpeedPenaltyRatio);
			addDoubleIfNonZero(json, "markShieldBashRangeBlocks", ability.markShieldBashRangeBlocks);
			addDoubleIfNonZero(json, "markShieldBashAngleDegrees", ability.markShieldBashAngleDegrees);
			addDoubleIfNonZero(json, "markShieldBashForwardImpulseBlocks", ability.markShieldBashForwardImpulseBlocks);
			addDoubleIfNonZero(json, "markShieldBashIronKnockbackBlocks", ability.markShieldBashIronKnockbackBlocks);
			addDoubleIfNonZero(json, "markShieldBashGoldenKnockbackBlocks", ability.markShieldBashGoldenKnockbackBlocks);
			addDoubleIfNonZero(json, "markShieldBashDiamondKnockbackBlocks", ability.markShieldBashDiamondKnockbackBlocks);
			addDoubleIfNonZero(json, "markShieldBashNetheriteKnockbackBlocks", ability.markShieldBashNetheriteKnockbackBlocks);
			addDoubleIfNonZero(json, "milkDefenseTeleportDistanceBlocks", ability.milkDefenseTeleportDistanceBlocks);
			addDoubleIfNonZero(json, "puroSanAttackBackJumpBlocks", ability.puroSanAttackBackJumpBlocks);
			addDoubleIfNonZero(json, "puroSanAttackWaveRadiusBlocks", ability.puroSanAttackWaveRadiusBlocks);
			addDoubleIfNonZero(json, "puroSanAttackDamage", ability.puroSanAttackDamage);
			addDoubleIfNonZero(json, "puroSanAttackDebuffSeconds", ability.puroSanAttackDebuffSeconds);
			addDoubleIfNonZero(json, "puroSanDefenseJumpBlocks", ability.puroSanDefenseJumpBlocks);
			addDoubleIfNonZero(json, "puroSanUniqueDashBlocks", ability.puroSanUniqueDashBlocks);
			addDoubleIfNonZero(json, "puroSanShnyagaChargeSeconds", ability.puroSanShnyagaChargeSeconds);
			addDoubleIfNonZero(json, "puroSanShnyagaSpeedBonusRatio", ability.puroSanShnyagaSpeedBonusRatio);
			addDoubleIfNonZero(json, "puroSanShnyagaShieldHealthRatio", ability.puroSanShnyagaShieldHealthRatio);
			addDoubleIfNonZero(json, "puroSanStockTrailDurationSeconds", ability.puroSanStockTrailDurationSeconds);
			addDoubleIfNonZero(json, "puroSanStockTrailDamage", ability.puroSanStockTrailDamage);
			addDoubleIfNonZero(json, "puroSanStockBaseSpeedBonusRatio", ability.puroSanStockBaseSpeedBonusRatio);
			addDoubleIfNonZero(json, "puroSanStockAimWarningRadiusBlocks", ability.puroSanStockAimWarningRadiusBlocks);
			addDoubleIfNonZero(json, "puroSanStockMeleeDamagePenaltyRatio", ability.puroSanStockMeleeDamagePenaltyRatio);
			addDoubleIfNonZero(json, "puroSanStockMeleeVulnerabilityRatio", ability.puroSanStockMeleeVulnerabilityRatio);
			addDoubleIfNonZero(json, "puroSanStockMaxHealthHearts", ability.puroSanStockMaxHealthHearts);

			addDoubleIfNonZero(json, "kilkaAttackChargeSeconds", ability.kilkaAttackChargeSeconds);
			addDoubleIfNonZero(json, "kilkaAttackRadiusBlocks", ability.kilkaAttackRadiusBlocks);
			addDoubleIfNonZero(json, "kilkaAttackKnockbackBlocks", ability.kilkaAttackKnockbackBlocks);
			addDoubleIfNonZero(json, "kilkaAttackDamageHearts", ability.kilkaAttackDamageHearts);
			addDoubleIfNonZero(json, "kilkaAttackAbilityBlockSeconds", ability.kilkaAttackAbilityBlockSeconds);
			addDoubleIfNonZero(json, "kilkaAttackNauseaSeconds", ability.kilkaAttackNauseaSeconds);
			addDoubleIfNonZero(json, "kilkaAttackSlownessSeconds", ability.kilkaAttackSlownessSeconds);
			addDoubleIfNonZero(json, "kilkaAttackSelfWeaknessSeconds", ability.kilkaAttackSelfWeaknessSeconds);
			addDoubleIfNonZero(json, "kilkaAttackSelfNauseaSeconds", ability.kilkaAttackSelfNauseaSeconds);
			addDoubleIfNonZero(json, "kilkaDefenseRadiusBlocks", ability.kilkaDefenseRadiusBlocks);
			addDoubleIfNonZero(json, "kilkaDefenseDurationSeconds", ability.kilkaDefenseDurationSeconds);
			addDoubleIfNonZero(json, "kilkaSalmonSprintSwimMultiplier", ability.kilkaSalmonSprintSwimMultiplier);
			addDoubleIfNonZero(json, "kilkaSeaBeaconLinkRangeBlocks", ability.kilkaSeaBeaconLinkRangeBlocks);
			addDoubleIfNonZero(json, "kilkaSeaBorderVisibilityBlocks", ability.kilkaSeaBorderVisibilityBlocks);
			addDoubleIfNonZero(json, "milkPocketRecentDamageLockSeconds", ability.milkPocketRecentDamageLockSeconds);
			addDoubleIfNonZero(json, "milkStockAvoidRadiusBlocks", ability.milkStockAvoidRadiusBlocks);
			addIntIfNonZero(json, "milkMouseSilverfishCount", ability.milkMouseSilverfishCount);
			addDoubleIfNonZero(json, "littleDictatorAttackAggroRadiusBlocks", ability.littleDictatorAttackAggroRadiusBlocks);
			addDoubleIfNonZero(json, "littleDictatorAttackMobStrengthSeconds", ability.littleDictatorAttackMobStrengthSeconds);
			addDoubleIfNonZero(json, "littleDictatorAttackPlayerSlownessSeconds", ability.littleDictatorAttackPlayerSlownessSeconds);
			addDoubleIfNonZero(json, "littleDictatorDefenseRadiusBlocks", ability.littleDictatorDefenseRadiusBlocks);
			addDoubleIfNonZero(json, "littleDictatorDefensePingDurationSeconds", ability.littleDictatorDefensePingDurationSeconds);
			addIntIfNonZero(json, "littleDictatorDefenseMinFakePingMs", ability.littleDictatorDefenseMinFakePingMs);
			addIntIfNonZero(json, "littleDictatorDefenseMaxFakePingMs", ability.littleDictatorDefenseMaxFakePingMs);
			addDoubleIfNonZero(json, "littleDictatorUniqueRadiusBlocks", ability.littleDictatorUniqueRadiusBlocks);
			addDoubleIfNonZero(json, "littleDictatorUniqueDurationSeconds", ability.littleDictatorUniqueDurationSeconds);
			addDoubleIfNonZero(json, "littleDictatorUniqueShockIntervalSeconds", ability.littleDictatorUniqueShockIntervalSeconds);
			addDoubleIfNonZero(json, "littleDictatorUniqueShockTargetCountMultiplierMin", ability.littleDictatorUniqueShockTargetCountMultiplierMin);
			addDoubleIfNonZero(json, "littleDictatorUniqueShockTargetCountMultiplierMax", ability.littleDictatorUniqueShockTargetCountMultiplierMax);
			addDoubleIfNonZero(json, "littleDictatorUniqueXpPerHealth", ability.littleDictatorUniqueXpPerHealth);
			addDoubleIfNonZero(json, "littleDictatorUniqueFoodPerHealth", ability.littleDictatorUniqueFoodPerHealth);
			addDoubleIfNonZero(json, "littleDictatorDecreeSanctionsDurationSeconds", ability.littleDictatorDecreeSanctionsDurationSeconds);
			addDoubleIfNonZero(json, "littleDictatorDecreeSanctionsDropChance", ability.littleDictatorDecreeSanctionsDropChance);
			addDoubleIfNonZero(json, "littleDictatorDecreeTaxesDurationSeconds", ability.littleDictatorDecreeTaxesDurationSeconds);
			addDoubleIfNonZero(json, "littleDictatorDecreeTaxesIntervalSeconds", ability.littleDictatorDecreeTaxesIntervalSeconds);
			addDoubleIfNonZero(json, "littleDictatorDecreePropagandaDurationSeconds", ability.littleDictatorDecreePropagandaDurationSeconds);
			addDoubleIfNonZero(json, "littleDictatorDecreePropagandaTradeDiscount", ability.littleDictatorDecreePropagandaTradeDiscount);
			addDoubleIfNonZero(json, "jetpackMaxRiseBlocks", ability.jetpackMaxRiseBlocks);
			addIntIfNonZero(json, "repulsorMaxCharges", ability.repulsorMaxCharges);
			addIntIfNonZero(json, "repulsorCopperIngotChargeRestore", ability.repulsorCopperIngotChargeRestore);
			addDoubleIfNonZero(json, "repulsorNaturalLightningChargeChance", ability.repulsorNaturalLightningChargeChance);
			addDoubleIfNonZero(json, "repulsorArmorIgnoreFraction", ability.repulsorArmorIgnoreFraction);
			addIntIfNonZero(json, "repulsorNaturalLightningChargeRestore", ability.repulsorNaturalLightningChargeRestore);
			addIntIfNonZero(json, "ancientUkrAttackSwordCount", ability.ancientUkrAttackSwordCount);
			addDoubleIfNonZero(json, "ancientUkrAttackDurationSeconds", ability.ancientUkrAttackDurationSeconds);
		addDoubleIfNonZero(json, "ancientUkrDefenseSmokeRadius", ability.ancientUkrDefenseSmokeRadius);
		addDoubleIfNonZero(json, "ancientUkrDefenseSmokeDurationSeconds", ability.ancientUkrDefenseSmokeDurationSeconds);
		addDoubleIfNonZero(json, "ancientUkrUniqueHighlightSeconds", ability.ancientUkrUniqueHighlightSeconds);
		addDoubleIfNonZero(json, "ancientUkrShnyagaMaxDistanceBlocks", ability.ancientUkrShnyagaMaxDistanceBlocks);
		addIntIfNonZero(json, "ancientUkrCreditMaxActive", ability.ancientUkrCreditMaxActive);
		addIntIfNonZero(json, "ancientUkrCreditMaxPrincipalBitcoins", ability.ancientUkrCreditMaxPrincipalBitcoins);
		addDoubleIfNonZero(json, "ancientUkrCreditMinHourlyPercent", ability.ancientUkrCreditMinHourlyPercent);
		addDoubleIfNonZero(json, "ancientUkrCreditMaxHourlyPercent", ability.ancientUkrCreditMaxHourlyPercent);
		addDoubleIfNonZero(json, "ancientUkrCreditMaxDebtMultiplier", ability.ancientUkrCreditMaxDebtMultiplier);
		addDoubleIfNonZero(json, "ancientUkrCollectorMinIntervalMinutes", ability.ancientUkrCollectorMinIntervalMinutes);
		addDoubleIfNonZero(json, "ancientUkrCollectorMaxIntervalMinutes", ability.ancientUkrCollectorMaxIntervalMinutes);
		addDoubleIfNonZero(json, "ancientUkrStockPorkHungerMultiplier", ability.ancientUkrStockPorkHungerMultiplier);
		addDoubleIfNonZero(json, "ancientUkrStockPorkPoisonSeconds", ability.ancientUkrStockPorkPoisonSeconds);
		addDoubleIfNonZero(json, "orthodoxAttackEyeVisibilityRadiusBlocks", ability.orthodoxAttackEyeVisibilityRadiusBlocks);
		addDoubleIfNonZero(json, "orthodoxAttackDurationSeconds", ability.orthodoxAttackDurationSeconds);
		addDoubleIfNonZero(json, "orthodoxAttackRemainingHealthHearts", ability.orthodoxAttackRemainingHealthHearts);
		addDoubleIfNonZero(json, "orthodoxAttackBlindnessSeconds", ability.orthodoxAttackBlindnessSeconds);
		addDoubleIfNonZero(json, "orthodoxDefenseDurationSeconds", ability.orthodoxDefenseDurationSeconds);
		addDoubleIfNonZero(json, "orthodoxUniqueRadiusBlocks", ability.orthodoxUniqueRadiusBlocks);
		addDoubleIfNonZero(json, "orthodoxHolinessSelfDefenseSeconds", ability.orthodoxHolinessSelfDefenseSeconds);
		addDoubleIfNonZero(json, "orthodoxHolinessBitcoinInventoryFraction", ability.orthodoxHolinessBitcoinInventoryFraction);
		addDoubleIfNonZero(json, "orthodoxHolinessAfkMinutes", ability.orthodoxHolinessAfkMinutes);
		addDoubleIfNonZero(json, "orthodoxHolinessNetherMinutes", ability.orthodoxHolinessNetherMinutes);
		addDoubleIfNonZero(json, "orthodoxHolinessRepentanceMinecraftDays", ability.orthodoxHolinessRepentanceMinecraftDays);
		addDoubleIfNonZero(json, "orthodoxHolinessRecoveryMinutes", ability.orthodoxHolinessRecoveryMinutes);
			addDoubleIfNonZero(json, "chance", ability.chance);
			return json;
		}

		private static void addString(JsonObject json, String key, String value) {
			if (value != null) {
				json.addProperty(key, value);
			}
		}

		private static void addIntIfNonZero(JsonObject json, String key, int value) {
			if (value != 0) {
				json.addProperty(key, value);
			}
		}

		private static void addDoubleIfNonZero(JsonObject json, String key, double value) {
			if (Double.compare(value, 0.0D) != 0) {
				json.addProperty(key, value);
			}
		}
	}

	public enum RaceAbilitySlot {
		ATTACK("attack_template", "Атака", 300),
		DEFENSE("defense_template", "Защита", 600),
		UNIQUE_ABILITY("unique_ability_template", "Уникальная способность", 1000),
		SHNYAGA("shnyaga_template", "Шняга", 1500),
		STOCK("stock_template", "Сток", 0);

		public final String defaultAbilityId;
		public final String defaultDisplayName;
		public final int defaultPriceBitcoins;

		RaceAbilitySlot(String defaultAbilityId, String defaultDisplayName, int defaultPriceBitcoins) {
			this.defaultAbilityId = defaultAbilityId;
			this.defaultDisplayName = defaultDisplayName;
			this.defaultPriceBitcoins = defaultPriceBitcoins;
		}
	}

	public static final class ConfigData {
		public List<PlayerRaceConfig> races = new ArrayList<>();

		private ConfigData() {
		}

		public static ConfigData defaults() {
			ConfigData data = new ConfigData();
			data.races.add(PlayerRaceConfig.template());
			return data;
		}
	}

	public static final class PlayerRaceConfig {
		public boolean enabled = false;
		public String id = "example_race";
		public String displayName = "Пример Расы";
		public String ownerNickname = "PlayerNickname";
		public String description = "Шаблон персональной расы. Включи запись и настрой 5 категорий способностей.";
		public RaceAbilityConfig attack = RaceAbilityConfig.defaults(RaceAbilitySlot.ATTACK);
		public RaceAbilityConfig defense = RaceAbilityConfig.defaults(RaceAbilitySlot.DEFENSE);
		public RaceAbilityConfig uniqueAbility = RaceAbilityConfig.defaults(RaceAbilitySlot.UNIQUE_ABILITY);
		public RaceAbilityConfig shnyaga = RaceAbilityConfig.defaults(RaceAbilitySlot.SHNYAGA);
		public RaceAbilityConfig stock = RaceAbilityConfig.defaults(RaceAbilitySlot.STOCK);

		private PlayerRaceConfig() {
		}

		public static PlayerRaceConfig template() {
			return new PlayerRaceConfig();
		}
	}

	public static final class RaceAbilityConfig {
		public boolean enabled = true;
		public String abilityId;
		public String name;
		public String description = "";
		public int priceBitcoins = 0;
		public double cooldownSeconds = 0.0D;
		public double activationRangeBlocks = 0.0D;
		public double durationSeconds = 0.0D;
		public double innerMinDistanceBlocks = 0.0D;
		public double followMaxDistanceBlocks = 0.0D;
		public double maxOutsideAreaSeconds = 0.0D;
		public double healthPoints = 0.0D;
		public double reflectedDamageRatio = 0.0D;
		public double summonLifetimeSeconds = 0.0D;
		public double summonAfterKillSeconds = 0.0D;
		public double minGrowthSeconds = 0.0D;
		public double maxGrowthSeconds = 0.0D;
		public double tubochkaBurnSeconds = 0.0D;
		public double tubochkaMaxReleaseSmokeParticles = 8.0D;
		public double methadoneAddictionSeconds = 0.0D;
		public double methadoneWithdrawalStartSeconds = 0.0D;
		public double cocaineHallucinationChance = 0.0D;
		public double cartelRaiderArmorDivider = 0.0D;
		public double foodRestoreMultiplier = 0.0D;
		public int copperIngotFoodPoints = 0;
		public double copperGolemNoticeRangeBlocks = 0.0D;
		@SerializedName(value = "copperGogglesScanCooldownSeconds", alternate = {"copperGogglesOreSearchCooldownSeconds"})
		public double copperGogglesScanCooldownSeconds = 0.0D;
		public double copperGogglesOreSearchRadiusBlocks = 0.0D;
		public double copperGogglesOreSearchHighlightSeconds = 0.0D;
		public double copperGogglesTrackingRadiusBlocks = 0.0D;
		public double copperGogglesTrackingHighlightSeconds = 0.0D;
		public double womanFlowerCooldownSeconds = 0.0D;
		public double womanAnimalBreedCooldownSeconds = 0.0D;
		public double womanAttackChargeRadiusBlocks = 0.0D;
		public double womanAttackRangeBlocks = 0.0D;
		public double womanAttackDamage = 0.0D;
		public double womanAttackFollowSeconds = 0.0D;
		public double womanUniqueDropMinSeconds = 0.0D;
		public double womanUniqueDropMaxSeconds = 0.0D;
		public double womanUniqueDropChance = 0.0D;
		public double womanUniqueTradePriceIncrease = 0.0D;
		public double womanUniqueAbsorptionHearts = 0.0D;
		public double womanShnyagaTransferHearts = 0.0D;
		public double womanShnyagaBuffRangeBlocks = 0.0D;
		public double womanShnyagaRejectDamageHearts = 0.0D;
		public double womanShnyagaRejectDebuffSeconds = 0.0D;
		public double gennadiyDonkeyHealthPoints = 0.0D;
		public double gennadiyDonkeyArmorDivider = 0.0D;
		public double gennadiyDonkeyHealthRegenSeconds = 0.0D;
		public int gennadiyDonkeyMaxAmmo = 0;
		public int gennadiyDonkeyAmmoRegenAmount = 0;
		public double gennadiyDonkeyAmmoRegenSeconds = 0.0D;
		public double gennadiyDonkeyBulletDamage = 0.0D;
		public double gennadiyDonkeyBulletRangeBlocks = 0.0D;
		public double gennadiyDonkeyFollowMaxDistanceBlocks = 0.0D;
		public double gennadiyDefenseDurationSeconds = 0.0D;
		public double gennadiyDefenseKnockbackBlocksPerDamage = 0.0D;
		public double gennadiyDefenseMaxKnockbackBlocks = 0.0D;
		public double gennadiyDefenseWaveRangeBlocks = 0.0D;
		public double gennadiyDefenseMinDamage = 0.0D;
		public double gennadiyDefenseMaxDamage = 0.0D;
		public double gennadiyHookRangeBlocks = 0.0D;
		public double gennadiyHookDamage = 0.0D;
		public double gennadiyHookSlownessSeconds = 0.0D;
		public double gennadiyRageHealthThresholdRatio = 0.0D;
		public int gennadiyRageHasteLevel = 0;
		public double gennadiyRageMeleeDamageBonusRatio = 0.0D;
		public double gennadiyReportCooldownSeconds = 0.0D;
		public double markAxeRangeBlocks = 0.0D;
		public double markAxeBleedingOneDamage = 0.0D;
		public double markAxeBleedingTwoDamage = 0.0D;
		public double markAxeBleedingIntervalSeconds = 0.0D;
		public double markAxeBleedingTwoDurationSeconds = 0.0D;
		public double markDefenseRadiusBlocks = 0.0D;
		public double markDefenseDurationSeconds = 0.0D;
		public double markRageMaxPoints = 0.0D;
		public double markRagePassiveKillPoints = 0.0D;
		public double markRageHostileKillPoints = 0.0D;
		public double markRageBossKillPoints = 0.0D;
		public double markRagePlayerKillPoints = 0.0D;
		public double markRageDrainPointsPerSecond = 0.0D;
		public double markRageExhaustionSeconds = 0.0D;
		public double markStockPassiveKillHearts = 0.0D;
		public double markStockHostileKillHearts = 0.0D;
		public double markStockBossKillHearts = 0.0D;
		public double markStockPlayerKillHearts = 0.0D;
		public double markStockFirstHitDamageMultiplier = 0.0D;
		public double markStockFirstHitResetSeconds = 0.0D;
		public double markStockMovementSpeedPenaltyRatio = 0.0D;
		public double markShieldBashRangeBlocks = 0.0D;
		public double markShieldBashAngleDegrees = 0.0D;
		public double markShieldBashForwardImpulseBlocks = 0.0D;
		public double markShieldBashIronKnockbackBlocks = 0.0D;
		public double markShieldBashGoldenKnockbackBlocks = 0.0D;
		public double markShieldBashDiamondKnockbackBlocks = 0.0D;
		public double markShieldBashNetheriteKnockbackBlocks = 0.0D;
		public double milkDefenseTeleportDistanceBlocks = 0.0D;
		public double puroSanAttackBackJumpBlocks = 0.0D;
		public double puroSanAttackWaveRadiusBlocks = 0.0D;
		public double puroSanAttackDamage = 0.0D;
		public double puroSanAttackDebuffSeconds = 0.0D;
		public double puroSanDefenseJumpBlocks = 0.0D;
		public double puroSanUniqueDashBlocks = 0.0D;
		public double puroSanShnyagaChargeSeconds = 0.0D;
		public double puroSanShnyagaSpeedBonusRatio = 0.0D;
		public double puroSanShnyagaShieldHealthRatio = 0.0D;
		public double puroSanStockTrailDurationSeconds = 0.0D;
		public double puroSanStockTrailDamage = 0.0D;
		public double puroSanStockBaseSpeedBonusRatio = 0.0D;
		public double puroSanStockAimWarningRadiusBlocks = 0.0D;
		public double puroSanStockMeleeDamagePenaltyRatio = 0.0D;
		public double puroSanStockMeleeVulnerabilityRatio = 0.0D;
		public double puroSanStockMaxHealthHearts = 0.0D;

		public double kilkaAttackChargeSeconds = 0.0D;
		public double kilkaAttackRadiusBlocks = 0.0D;
		public double kilkaAttackKnockbackBlocks = 0.0D;
		public double kilkaAttackDamageHearts = 0.0D;
		public double kilkaAttackAbilityBlockSeconds = 0.0D;
		public double kilkaAttackNauseaSeconds = 0.0D;
		public double kilkaAttackSlownessSeconds = 0.0D;
		public double kilkaAttackSelfWeaknessSeconds = 0.0D;
		public double kilkaAttackSelfNauseaSeconds = 0.0D;
		public double kilkaDefenseRadiusBlocks = 0.0D;
		public double kilkaDefenseDurationSeconds = 0.0D;
		public double kilkaSalmonSprintSwimMultiplier = 0.0D;
		public double kilkaSeaBeaconLinkRangeBlocks = 0.0D;
		public double kilkaSeaBorderVisibilityBlocks = 0.0D;
		public double milkPocketRecentDamageLockSeconds = 0.0D;
		public double milkStockAvoidRadiusBlocks = 0.0D;
		public int milkMouseSilverfishCount = 0;
		public double littleDictatorAttackAggroRadiusBlocks = 0.0D;
		public double littleDictatorAttackMobStrengthSeconds = 0.0D;
		public double littleDictatorAttackPlayerSlownessSeconds = 0.0D;
		public double littleDictatorDefenseRadiusBlocks = 0.0D;
		public double littleDictatorDefensePingDurationSeconds = 0.0D;
		public int littleDictatorDefenseMinFakePingMs = 0;
		public int littleDictatorDefenseMaxFakePingMs = 0;
		public double littleDictatorUniqueRadiusBlocks = 0.0D;
		public double littleDictatorUniqueDurationSeconds = 0.0D;
		public double littleDictatorUniqueShockIntervalSeconds = 0.0D;
		public double littleDictatorUniqueShockTargetCountMultiplierMin = 0.0D;
		public double littleDictatorUniqueShockTargetCountMultiplierMax = 0.0D;
		public double littleDictatorUniqueXpPerHealth = 0.0D;
		public double littleDictatorUniqueFoodPerHealth = 0.0D;
		public double littleDictatorDecreeSanctionsDurationSeconds = 0.0D;
		public double littleDictatorDecreeSanctionsDropChance = 0.0D;
		public double littleDictatorDecreeTaxesDurationSeconds = 0.0D;
		public double littleDictatorDecreeTaxesIntervalSeconds = 0.0D;
		public double littleDictatorDecreePropagandaDurationSeconds = 0.0D;
		public double littleDictatorDecreePropagandaTradeDiscount = 0.0D;
		public double jetpackMaxRiseBlocks = 0.0D;
		public int repulsorMaxCharges = 0;
		public int repulsorCopperIngotChargeRestore = 0;
		public double repulsorNaturalLightningChargeChance = 0.0D;
		public double repulsorArmorIgnoreFraction = 0.0D;
		public int repulsorNaturalLightningChargeRestore = 0;
		public int ancientUkrAttackSwordCount = 0;
		public double ancientUkrAttackDurationSeconds = 0.0D;
		public double ancientUkrDefenseSmokeRadius = 0.0D;
		public double ancientUkrDefenseSmokeDurationSeconds = 0.0D;
		public double ancientUkrUniqueHighlightSeconds = 0.0D;
		public double ancientUkrShnyagaMaxDistanceBlocks = 0.0D;
		public int ancientUkrCreditMaxActive = 0;
		public int ancientUkrCreditMaxPrincipalBitcoins = 0;
		public double ancientUkrCreditMinHourlyPercent = 0.0D;
		public double ancientUkrCreditMaxHourlyPercent = 0.0D;
		public double ancientUkrCreditMaxDebtMultiplier = 0.0D;
		public double ancientUkrCollectorMinIntervalMinutes = 0.0D;
		public double ancientUkrCollectorMaxIntervalMinutes = 0.0D;
		public double ancientUkrStockPorkHungerMultiplier = 0.0D;
		public double ancientUkrStockPorkPoisonSeconds = 0.0D;
		public double orthodoxAttackEyeVisibilityRadiusBlocks = 0.0D;
		public double orthodoxAttackDurationSeconds = 0.0D;
		public double orthodoxAttackRemainingHealthHearts = 0.0D;
		public double orthodoxAttackBlindnessSeconds = 0.0D;
		public double orthodoxDefenseDurationSeconds = 0.0D;
		public double orthodoxUniqueRadiusBlocks = 0.0D;
		public double orthodoxHolinessSelfDefenseSeconds = 0.0D;
		public double orthodoxHolinessBitcoinInventoryFraction = 0.0D;
		public double orthodoxHolinessAfkMinutes = 0.0D;
		public double orthodoxHolinessNetherMinutes = 0.0D;
		public double orthodoxHolinessRepentanceMinecraftDays = 0.0D;
		public double orthodoxHolinessRecoveryMinutes = 0.0D;
		public double chance = 0.0D;

		private RaceAbilityConfig() {
		}

		public static RaceAbilityConfig defaults(RaceAbilitySlot slot) {
			RaceAbilityConfig config = new RaceAbilityConfig();
			config.abilityId = slot.defaultAbilityId;
			config.name = slot.defaultDisplayName;
			config.priceBitcoins = slot.defaultPriceBitcoins;
			return config;
		}
	}
}

