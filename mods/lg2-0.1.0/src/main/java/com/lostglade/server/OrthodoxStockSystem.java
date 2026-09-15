package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.config.RaceConfig.PlayerRaceConfig;
import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import com.lostglade.config.RaceConfig.RaceAbilitySlot;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OrthodoxStockSystem {
	private static final String RACE_ID = "orthodox";
	private static final double MAX_HEALTH_HEARTS = 12.0D;
	private static final int LIGHT_LEVEL = 12;
	private static final double FIRST_BLESSING_HEIGHT = 120.0D;
	private static final double SECOND_BLESSING_HEIGHT = 240.0D;
	private static final Identifier MAX_HEALTH_MODIFIER_ID =
			Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "orthodox_stock_max_health");

	private static final ManagedInfiniteEffect HEIGHT_REGENERATION =
			new ManagedInfiniteEffect(MobEffects.REGENERATION, false, false, true);
	private static final ManagedInfiniteEffect HEIGHT_RESISTANCE =
			new ManagedInfiniteEffect(MobEffects.RESISTANCE, false, false, true);
	private static final ManagedInfiniteEffect ENCHANTED_NAUSEA =
			new ManagedInfiniteEffect(MobEffects.NAUSEA, false, true, true);
	private static final ManagedInfiniteEffect ENCHANTED_SLOWNESS =
			new ManagedInfiniteEffect(MobEffects.SLOWNESS, false, true, true);
	private static final ManagedInfiniteEffect ENCHANTED_WEAKNESS =
			new ManagedInfiniteEffect(MobEffects.WEAKNESS, false, true, true);
	private static final ManagedInfiniteEffect ENCHANTED_MINING_FATIGUE =
			new ManagedInfiniteEffect(MobEffects.MINING_FATIGUE, false, true, true);
	private static final ManagedInfiniteEffect ENCHANTED_POISON =
			new ManagedInfiniteEffect(MobEffects.POISON, false, true, true);
	private static final ManagedInfiniteEffect ENCHANTED_BLINDNESS =
			new ManagedInfiniteEffect(MobEffects.BLINDNESS, false, true, true);

	private static final Map<UUID, LightPlacement> PLAYER_LIGHTS = new HashMap<>();
	private static final Map<LightKey, ManagedLight> MANAGED_LIGHTS = new ConcurrentHashMap<>();

	private OrthodoxStockSystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(OrthodoxStockSystem::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clearPlayer(server, handler.player));
		ServerLifecycleEvents.SERVER_STOPPING.register(OrthodoxStockSystem::clearAll);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> OrthodoxLightSync.clear());
	}

	private static void tick(MinecraftServer server) {
		if (server == null) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (getStockAbility(player) == null) {
				clearPlayer(server, player);
				continue;
			}
			if (OrthodoxHolinessSystem.hasPositiveHoliness(player)) {
				syncMaxHealth(player);
				syncLight(server, player);
				syncHeightBlessing(player);
			} else {
				clearPositiveStock(server, player);
			}
			syncEnchantedPenalty(player);
		}
		OrthodoxLightSync.flush(server);
	}

	private static void clearPositiveStock(MinecraftServer server, ServerPlayer player) {
		AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth != null && maxHealth.getModifier(MAX_HEALTH_MODIFIER_ID) != null) {
			maxHealth.removeModifier(MAX_HEALTH_MODIFIER_ID);
			if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
		}
		HEIGHT_REGENERATION.clear(player);
		HEIGHT_RESISTANCE.clear(player);
		LightPlacement light = PLAYER_LIGHTS.get(player.getUUID());
		if (light != null) releaseLight(server, player.getUUID(), light);
	}

	private static void syncMaxHealth(ServerPlayer player) {
		AttributeInstance attribute = player.getAttribute(Attributes.MAX_HEALTH);
		if (attribute == null) return;
		double amount = MAX_HEALTH_HEARTS * 2.0D - 20.0D;
		AttributeModifier current = attribute.getModifier(MAX_HEALTH_MODIFIER_ID);
		if (current == null || current.operation() != AttributeModifier.Operation.ADD_VALUE
				|| Math.abs(current.amount() - amount) > 1.0E-9D) {
			if (current != null) attribute.removeModifier(MAX_HEALTH_MODIFIER_ID);
			attribute.addTransientModifier(new AttributeModifier(
					MAX_HEALTH_MODIFIER_ID,
					amount,
					AttributeModifier.Operation.ADD_VALUE
			));
		}
		if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
	}

	private static void syncHeightBlessing(ServerPlayer player) {
		int amplifier = player.getY() >= SECOND_BLESSING_HEIGHT ? 1
				: player.getY() >= FIRST_BLESSING_HEIGHT ? 0 : -1;
		if (amplifier >= 0) {
			HEIGHT_REGENERATION.ensure(player, amplifier);
			HEIGHT_RESISTANCE.ensure(player, amplifier);
		} else {
			HEIGHT_REGENERATION.clear(player);
			HEIGHT_RESISTANCE.clear(player);
		}
	}

	private static void syncEnchantedPenalty(ServerPlayer player) {
		if (hasEnchantedItem(player)) {
			ENCHANTED_NAUSEA.ensure(player, 0);
			ENCHANTED_SLOWNESS.ensure(player, 1);
			ENCHANTED_WEAKNESS.ensure(player, 0);
			ENCHANTED_MINING_FATIGUE.ensure(player, 0);
			ENCHANTED_POISON.ensure(player, 0);
			ENCHANTED_BLINDNESS.ensure(player, 0);
		} else {
			clearEnchantedPenalty(player);
		}
	}

	private static boolean hasEnchantedItem(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && stack.isEnchanted()) return true;
		}
		return false;
	}

	private static void syncLight(MinecraftServer server, ServerPlayer player) {
		LightPlacement desired = currentLightPosition(player);
		LightPlacement current = PLAYER_LIGHTS.get(player.getUUID());
		if (desired != null && desired.equals(current)) {
			// A section can reject the first check while it is still loading.
			// Requeue the unchanged virtual source so lighting recovers in newly ready chunks.
			if (server.getTickCount() % 10 == 0) requestLightUpdate(player.level(), desired.key);
			return;
		}
		if (current != null) releaseLight(server, player.getUUID(), current);
		if (desired != null) {
			acquireLight(player.level(), player.getUUID(), desired);
			PLAYER_LIGHTS.put(player.getUUID(), desired);
		} else {
			PLAYER_LIGHTS.remove(player.getUUID());
		}
	}

	private static LightPlacement currentLightPosition(ServerPlayer player) {
		ServerLevel level = player.level();
		BlockPos pos = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
		if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) return null;
		return new LightPlacement(new LightKey(level.dimension(), pos.asLong()));
	}

	private static void acquireLight(ServerLevel level, UUID playerId, LightPlacement placement) {
		LightKey key = placement.key;
		ManagedLight managed = MANAGED_LIGHTS.computeIfAbsent(key, ignored -> new ManagedLight());
		if (managed.owners.add(playerId)) {
			if (managed.owners.size() == 1) OrthodoxLightSync.retain(level, BlockPos.of(key.pos));
			requestLightUpdate(level, key);
		}
	}

	private static void releaseLight(MinecraftServer server, UUID playerId, LightPlacement placement) {
		PLAYER_LIGHTS.remove(playerId, placement);
		LightKey key = placement.key;
		ManagedLight managed = MANAGED_LIGHTS.get(key);
		if (managed == null || !managed.owners.remove(playerId)) return;
		ServerLevel level = server.getLevel(key.dimension);
		if (!managed.owners.isEmpty() || !MANAGED_LIGHTS.remove(key, managed)) return;
		if (level != null) {
			requestLightUpdate(level, key);
			OrthodoxLightSync.releaseAfterUpdate(level, BlockPos.of(key.pos));
		}
	}

	private static void requestLightUpdate(ServerLevel level, LightKey key) {
		BlockPos pos = BlockPos.of(key.pos);
		if (level.isInWorldBounds(pos) && level.hasChunkAt(pos)) {
			ThreadedLevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
			// Completely empty chunk sections have no block-light storage. Vanilla's
			// checkBlock silently ignores a virtual source there, producing hard 16x16
			// boundaries. Marking the occupied section as non-empty creates light-only
			// storage without placing or replacing any world block.
			lightEngine.updateSectionStatus(SectionPos.of(pos), false);
			lightEngine.checkBlock(pos);
			lightEngine.tryScheduleUpdate();
		}
	}

	public static int getDynamicLightEmission(BlockGetter level, long packedPos) {
		if (!(level instanceof ServerLevel serverLevel)) return 0;
		ManagedLight managed = MANAGED_LIGHTS.get(new LightKey(serverLevel.dimension(), packedPos));
		return managed == null || managed.owners.isEmpty() ? 0 : LIGHT_LEVEL;
	}

	private static RaceAbilityConfig getStockAbility(ServerPlayer player) {
		if (player == null) return null;
		Optional<PlayerRaceConfig> race = ServerRaceSystem.getRace(player);
		if (race.isEmpty() || !RACE_ID.equalsIgnoreCase(race.get().id)
				|| !ServerRaceSystem.hasUnlockedAbility(player, RaceAbilitySlot.STOCK)) return null;
		RaceAbilityConfig ability = ServerRaceSystem.getAbility(race.get(), RaceAbilitySlot.STOCK);
		return ability != null && ability.enabled ? ability : null;
	}

	private static void clearEnchantedPenalty(ServerPlayer player) {
		ENCHANTED_NAUSEA.clear(player);
		ENCHANTED_SLOWNESS.clear(player);
		ENCHANTED_WEAKNESS.clear(player);
		ENCHANTED_MINING_FATIGUE.clear(player);
		ENCHANTED_POISON.clear(player);
		ENCHANTED_BLINDNESS.clear(player);
	}

	private static void clearPlayer(MinecraftServer server, ServerPlayer player) {
		if (player == null) return;
		AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth != null && maxHealth.getModifier(MAX_HEALTH_MODIFIER_ID) != null) {
			maxHealth.removeModifier(MAX_HEALTH_MODIFIER_ID);
			if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
		}
		HEIGHT_REGENERATION.clear(player);
		HEIGHT_RESISTANCE.clear(player);
		clearEnchantedPenalty(player);
		LightPlacement light = PLAYER_LIGHTS.get(player.getUUID());
		if (light != null) releaseLight(server, player.getUUID(), light);
	}

	private static void clearAll(MinecraftServer server) {
		if (server != null) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) clearPlayer(server, player);
		}
		List<LightKey> lightKeys = List.copyOf(MANAGED_LIGHTS.keySet());
		PLAYER_LIGHTS.clear();
		MANAGED_LIGHTS.clear();
		if (server != null) {
			for (LightKey key : lightKeys) {
				ServerLevel level = server.getLevel(key.dimension);
				if (level != null) {
					requestLightUpdate(level, key);
					OrthodoxLightSync.releaseAfterUpdate(level, BlockPos.of(key.pos));
				}
			}
		}
		HEIGHT_REGENERATION.clearAll(server);
		HEIGHT_RESISTANCE.clearAll(server);
		ENCHANTED_NAUSEA.clearAll(server);
		ENCHANTED_SLOWNESS.clearAll(server);
		ENCHANTED_WEAKNESS.clearAll(server);
		ENCHANTED_MINING_FATIGUE.clearAll(server);
		ENCHANTED_POISON.clearAll(server);
		ENCHANTED_BLINDNESS.clearAll(server);
	}

	private record LightKey(ResourceKey<Level> dimension, long pos) {
	}

	private record LightPlacement(LightKey key) {
	}

	private static final class ManagedLight {
		private final Set<UUID> owners = ConcurrentHashMap.newKeySet();
	}
}
