package com.lostglade.server;

import com.lostglade.config.Lg2Config;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ServerRespectSystem {
	private static final String OBJECTIVE_NAME = "lg2_respects";
	private static final String TAB_RESPECT_RELATIONAL_PLACEHOLDER = "%rel_lg2_respect_suffix%";
	private static final Component OBJECTIVE_TITLE = Component.literal("Респекты");
	private static final double RESPECT_PARTICLE_VIEW_DISTANCE_SQR = 32.0D * 32.0D;
	private static final String PACK_ACTIONBAR_EN = "\uEE00\uEE01\uEE02\uEE03\uEE04\uEE05\uEE06\uEE07";
	private static final String PACK_ACTIONBAR_RU = "\uEE10\uEE11\uEE12\uEE13\uEE14\uEE15\uEE16\uEE17";
	private static final String PACK_ACTIONBAR_UK = "\uEE20\uEE21\uEE22\uEE23\uEE24\uEE25\uEE26";
	private static final String PACK_ACTIONBAR_RPR = "\uEE30\uEE31\uEE32\uEE33\uEE34\uEE35\uEE36\uEE37\uEE38";
	private static final String PACK_ACTIONBAR_JA = "\uEE40\uEE41\uEE42\uEE43\uEE44\uEE45";
	private static final int DIGIT_GLYPH_BASE = 0xEE80;
	private static final ChatFormatting[] RAINBOW_CHAT_COLORS = {
			ChatFormatting.RED,
			ChatFormatting.GOLD,
			ChatFormatting.YELLOW,
			ChatFormatting.GREEN,
			ChatFormatting.AQUA,
			ChatFormatting.BLUE,
			ChatFormatting.LIGHT_PURPLE
	};
	private static final String[] RAINBOW_LEGACY_CODES = {"&c", "&6", "&e", "&a", "&b", "&9", "&d"};
	private static final ColorParticleOption[] RESPECT_PARTICLES = {
			createRespectParticle(0xFFFF2A40),
			createRespectParticle(0xFFFF7C00),
			createRespectParticle(0xFFFFD300),
			createRespectParticle(0xFF4AD643),
			createRespectParticle(0xFF008CFF),
			createRespectParticle(0xFF685CFF),
			createRespectParticle(0xFFCB47FF)
	};
	private static final Map<UUID, Long> NEXT_RESPECT_AT_MILLIS = new HashMap<>();
	private static final Map<RespectInteractionKey, Long> RECENT_RESPECT_INTERACTIONS = new HashMap<>();

	private ServerRespectSystem() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ServerTabIntegration.registerRelationalPlaceholder(
					TAB_RESPECT_RELATIONAL_PLACEHOLDER,
					250,
					ServerRespectSystem::buildViewerAwareTabSuffix
			);
			refreshAllTabSuffixes(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			NEXT_RESPECT_AT_MILLIS.clear();
			RECENT_RESPECT_INTERACTIONS.clear();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				AccountAuthSystem.runWhenAuthenticated(handler.player, () -> refreshTabSuffix((ServerPlayer) handler.player)));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> refreshTabSuffix(newPlayer));
		ServerTabIntegration.registerPlayerLoadHandler(ServerRespectSystem::refreshTabSuffix);

		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClientSide()
					|| hand != InteractionHand.MAIN_HAND
					|| !(player instanceof ServerPlayer actor)
					|| !(entity instanceof ServerPlayer target)) {
				return InteractionResult.PASS;
			}
			if (!actor.isShiftKeyDown() || actor == target || !actor.isAlive() || !target.isAlive()) {
				return InteractionResult.PASS;
			}
			if (isDuplicateRespectInteraction(actor, target)) {
				return InteractionResult.SUCCESS;
			}
			if (getRemainingCooldownMillis(actor) > 0L) {
				return InteractionResult.SUCCESS;
			}

			addRespect(target);
			applyCooldown(actor);
			refreshTabSuffix(target);
			showRespectActionbar(actor);
			showRespectActionbar(target);
			spawnRespectParticles(target);
			playRespectSound(actor);
			playRespectSound(target);
			return InteractionResult.SUCCESS;
		});
	}

	private static boolean isDuplicateRespectInteraction(ServerPlayer actor, ServerPlayer target) {
		long tick = actor.level().getGameTime();
		RECENT_RESPECT_INTERACTIONS.entrySet().removeIf(entry -> entry.getValue() + 40L < tick);
		RespectInteractionKey key = new RespectInteractionKey(actor.getUUID(), target.getUUID());
		Long previousTick = RECENT_RESPECT_INTERACTIONS.put(key, tick);
		return previousTick != null && previousTick == tick;
	}

	private static void refreshAllTabSuffixes(MinecraftServer server) {
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			refreshTabSuffix(player);
		}
	}

	private static void refreshTabSuffix(ServerPlayer player) {
		ServerTabIntegration.setTabSuffix(player, TAB_RESPECT_RELATIONAL_PLACEHOLDER);
	}

	private static String buildViewerAwareTabSuffix(ServerPlayer viewer, ServerPlayer target) {
		int respectCount = getRespectCount(target);
		return PolymerResourcePackUtils.hasMainPack(viewer)
				? " &f" + toPackDigitString(respectCount)
				: " " + rainbowLegacy(Integer.toString(respectCount), getFallbackRainbowOffset(viewer, target));
	}

	private static int getFallbackRainbowOffset(ServerPlayer viewer, ServerPlayer target) {
		List<ServerPlayer> orderedPlayers = getOrderedTabPlayers(viewer);
		int offset = 0;
		for (ServerPlayer player : orderedPlayers) {
			if (player.getUUID().equals(target.getUUID())) {
				return offset;
			}
			offset += Integer.toString(Math.max(0, getRespectCount(player))).length();
		}
		return 0;
	}

	private static List<ServerPlayer> getOrderedTabPlayers(ServerPlayer viewer) {
		List<ServerPlayer> orderedPlayers = new ArrayList<>(ServerTabIntegration.getOnlinePlayers());

		if (!orderedPlayers.isEmpty()) {
			return orderedPlayers;
		}

		MinecraftServer server = viewer.level().getServer();
		if (server == null) {
			return orderedPlayers;
		}
		return new ArrayList<>(AccountAuthSystem.authenticatedPlayers(server));
	}

	private static int addRespect(ServerPlayer target) {
		MinecraftServer server = target.level().getServer();
		if (server == null) {
			return 0;
		}

		Objective objective = getOrCreateObjective(server);
		ScoreAccess score = server.getScoreboard().getOrCreatePlayerScore(target, objective);
		return score.add(1);
	}

	private static int getRespectCount(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return 0;
		}

		Objective objective = getOrCreateObjective(server);
		return server.getScoreboard().getOrCreatePlayerScore(player, objective).get();
	}

	private static void applyCooldown(ServerPlayer actor) {
		int cooldownSeconds = Lg2Config.get().respectCooldownSeconds;
		if (cooldownSeconds <= 0) {
			NEXT_RESPECT_AT_MILLIS.remove(actor.getUUID());
			return;
		}

		long nextUseMillis = System.currentTimeMillis() + cooldownSeconds * 1000L;
		NEXT_RESPECT_AT_MILLIS.put(actor.getUUID(), nextUseMillis);
	}

	private static long getRemainingCooldownMillis(ServerPlayer actor) {
		Long nextUseMillis = NEXT_RESPECT_AT_MILLIS.get(actor.getUUID());
		if (nextUseMillis == null) {
			return 0L;
		}

		long remaining = nextUseMillis - System.currentTimeMillis();
		if (remaining <= 0L) {
			NEXT_RESPECT_AT_MILLIS.remove(actor.getUUID());
			return 0L;
		}

		return remaining;
	}

	private static void showRespectActionbar(ServerPlayer player) {
		if (PolymerResourcePackUtils.hasMainPack(player)) {
			player.displayClientMessage(
					Component.literal(packRespectActionbar(player))
							.withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)),
					true
			);
			return;
		}

		player.displayClientMessage(buildRainbowActionbar(player), true);
	}

	private static MutableComponent buildRainbowActionbar(ServerPlayer player) {
		return rainbowComponent(localizedRespectActionbar(player));
	}

	private static String packRespectActionbar(ServerPlayer player) {
		String locale = normalizeLocale(player);
		if (locale.startsWith("rpr")) {
			return PACK_ACTIONBAR_RPR;
		}
		if (locale.startsWith("uk")) {
			return PACK_ACTIONBAR_UK;
		}
		if (locale.startsWith("ja")) {
			return PACK_ACTIONBAR_JA;
		}
		if (locale.startsWith("ru")) {
			return PACK_ACTIONBAR_RU;
		}
		return PACK_ACTIONBAR_EN;
	}

	private static String localizedRespectActionbar(ServerPlayer player) {
		String locale = normalizeLocale(player);
		if (locale.startsWith("rpr")) {
			return "Почтѣніе!";
		}
		if (locale.startsWith("uk")) {
			return "Повага!";
		}
		if (locale.startsWith("ja")) {
			return "リスペクト!";
		}
		if (locale.startsWith("ru")) {
			return "Респект!";
		}
		return "Respect!";
	}

	private static String toPackDigitString(int value) {
		String digits = Integer.toString(Math.max(0, value));
		StringBuilder builder = new StringBuilder(digits.length());
		for (int index = 0; index < digits.length(); index++) {
			char digit = digits.charAt(index);
			if (digit >= '0' && digit <= '9') {
				builder.append((char) (DIGIT_GLYPH_BASE + (digit - '0')));
			}
		}
		return builder.toString();
	}

	private static MutableComponent rainbowComponent(String text) {
		MutableComponent result = Component.empty();
		for (int index = 0; index < text.length(); index++) {
			result.append(Component.literal(String.valueOf(text.charAt(index)))
					.withStyle(RAINBOW_CHAT_COLORS[index % RAINBOW_CHAT_COLORS.length]));
		}
		return result;
	}

	private static String rainbowLegacy(String text) {
		return rainbowLegacy(text, 0);
	}

	private static String rainbowLegacy(String text, int startOffset) {
		StringBuilder result = new StringBuilder(text.length() * 3);
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			if (Character.isWhitespace(character)) {
				result.append(character);
				continue;
			}
			int colorIndex = Math.floorMod(startOffset + index, RAINBOW_LEGACY_CODES.length);
			result.append(RAINBOW_LEGACY_CODES[colorIndex]).append(character);
		}
		return result.toString();
	}

	private static String normalizeLocale(ServerPlayer player) {
		if (player == null || player.clientInformation() == null || player.clientInformation().language() == null) {
			return "en_us";
		}
		return player.clientInformation().language().toLowerCase(Locale.ROOT);
	}

	private static void spawnRespectParticles(ServerPlayer target) {
		ServerLevel level = target.level() instanceof ServerLevel serverLevel ? serverLevel : null;
		if (level == null) {
			return;
		}

		double centerX = target.getX();
		double centerY = target.getY();
		double centerZ = target.getZ();
		double height = target.getBbHeight();
		double[] radii = {0.60D, 0.80D, 1.00D};
		double[] heightFactors = {0.22D, 0.56D, 0.9D};
		int pointsPerRing = 5;
		for (ServerPlayer viewer : level.players()) {
			if (viewer.distanceToSqr(centerX, centerY + height * 0.5D, centerZ) > RESPECT_PARTICLE_VIEW_DISTANCE_SQR) {
				continue;
			}

			for (int ringIndex = 0; ringIndex < heightFactors.length; ringIndex++) {
				double y = centerY + height * heightFactors[ringIndex];
				double angleOffset = (Math.PI / pointsPerRing) * ringIndex;
				for (int radiusIndex = 0; radiusIndex < radii.length; radiusIndex++) {
					double radius = radii[radiusIndex];
					for (int point = 0; point < pointsPerRing; point++) {
						if (((ringIndex + radiusIndex + point) & 1) != 0) {
							continue;
						}
						double angle = angleOffset + (Math.PI * 2.0D * point / pointsPerRing);
						double x = centerX + Math.cos(angle) * radius;
						double z = centerZ + Math.sin(angle) * radius;
						level.sendParticles(
								viewer,
								getRespectParticle(ringIndex, radiusIndex, point),
								false,
								true,
								x,
								y,
								z,
								1,
								0.03D,
								0.05D,
								0.03D,
								0.01D
						);
					}
				}
			}

			level.sendParticles(
					viewer,
					getRespectParticle(heightFactors.length, radii.length, 0),
					false,
					true,
					centerX,
					centerY + height + 0.2D,
					centerZ,
					1,
					0.1D,
					0.06D,
					0.1D,
					0.02D
			);
		}
	}

	private static ColorParticleOption getRespectParticle(int ringIndex, int radiusIndex, int pointIndex) {
		int index = Math.floorMod(ringIndex * 3 + radiusIndex * 2 + pointIndex, RESPECT_PARTICLES.length);
		return RESPECT_PARTICLES[index];
	}

	private static ColorParticleOption createRespectParticle(int argbColor) {
		return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, argbColor);
	}

	private static void playRespectSound(ServerPlayer target) {
		ServerLevel level = target.level() instanceof ServerLevel serverLevel ? serverLevel : null;
		if (level == null) {
			return;
		}

		target.connection.send(new ClientboundSoundPacket(
				BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.EXPERIENCE_ORB_PICKUP),
				SoundSource.PLAYERS,
				target.getX(),
				target.getY(),
				target.getZ(),
				0.65F,
				1.15F,
				level.getRandom().nextLong()
		));
	}

	private static Objective getOrCreateObjective(MinecraftServer server) {
		Scoreboard scoreboard = server.getScoreboard();
		Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
		if (objective != null) {
			return objective;
		}

		return scoreboard.addObjective(
				OBJECTIVE_NAME,
				ObjectiveCriteria.DUMMY,
				OBJECTIVE_TITLE,
				ObjectiveCriteria.RenderType.INTEGER,
				false,
				null
		);
	}

	private record RespectInteractionKey(UUID actor, UUID target) {
	}
}

