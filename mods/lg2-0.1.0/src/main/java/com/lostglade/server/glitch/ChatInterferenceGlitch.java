package com.lostglade.server.glitch;

import com.google.gson.JsonObject;
import com.lostglade.config.GlitchConfig;
import com.lostglade.server.AncientUkrCreditorChatSystem;
import com.lostglade.server.ServerBackroomsSystem;
import com.lostglade.server.ServerMilkPocketDimensionSystem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ChatInterferenceGlitch implements ChatMessageGlitchHandler {
	private static final String MIN_MUTATIONS = "minMutations";
	private static final String MAX_MUTATIONS = "maxMutations";
	private static final String MAX_DUPLICATE_EXTRA = "maxDuplicateExtra";

	private static final String REPLACE_WEIGHT = "replaceWeight";
	private static final String INSERT_WEIGHT = "insertWeight";
	private static final String DUPLICATE_WEIGHT = "duplicateWeight";
	private static final String REVERSE_WEIGHT = "reverseWeight";
	private static final String SHUFFLE_WEIGHT = "shuffleWeight";
	private static final String CASE_WEIGHT = "caseWeight";
	private static final String SWAP_NICKNAME_WEIGHT = "swapNicknameWeight";

	private static final int LATIN_ALPHABET_SIZE = 26;
	private static final int CYRILLIC_ALPHABET_SIZE = 33;
	private static final char[] BASIC_SYMBOL_POOL = new char[]{
			'.', ',', '!', '?', ':', ';', '\'', '"', '-', '@', '#', '$', '%', '^', '&', '*', '+', '=', '<', '>', '|', '~', '/', '\\'
	};

	@Override
	public String id() {
		return "chat_interference";
	}

	@Override
	public GlitchConfig.GlitchEntry defaultEntry() {
		GlitchConfig.GlitchEntry entry = new GlitchConfig.GlitchEntry();
		entry.enabled = true;
		entry.minStabilityPercent = 0.0D;
		entry.maxStabilityPercent = 70.0D;
		entry.chancePerCheck = 0.60D;
		entry.stabilityInfluence = 1.0D;
		entry.minCooldownTicks = 0;
		entry.maxCooldownTicks = 60;

		JsonObject settings = new JsonObject();
		settings.addProperty(MIN_MUTATIONS, 1);
		settings.addProperty(MAX_MUTATIONS, 5);
		settings.addProperty(MAX_DUPLICATE_EXTRA, 3);

		settings.addProperty(REPLACE_WEIGHT, 1.0D);
		settings.addProperty(INSERT_WEIGHT, 0.8D);
		settings.addProperty(DUPLICATE_WEIGHT, 0.8D);
		settings.addProperty(REVERSE_WEIGHT, 0.5D);
		settings.addProperty(SHUFFLE_WEIGHT, 1.0D);
		settings.addProperty(CASE_WEIGHT, 0.8D);
		settings.addProperty(SWAP_NICKNAME_WEIGHT, 0.6D);
		entry.settings = settings;
		return entry;
	}

	@Override
	public boolean sanitizeSettings(GlitchConfig.GlitchEntry entry) {
		if (entry.settings == null) {
			entry.settings = new JsonObject();
		}

		boolean changed = false;
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MIN_MUTATIONS, 1, 1, 8);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_MUTATIONS, 5, 1, 12);
		changed |= GlitchSettingsHelper.sanitizeInt(entry.settings, MAX_DUPLICATE_EXTRA, 3, 1, 8);

		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, REPLACE_WEIGHT, 1.0D, 0.0D, 10.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, INSERT_WEIGHT, 0.8D, 0.0D, 10.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, DUPLICATE_WEIGHT, 0.8D, 0.0D, 10.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, REVERSE_WEIGHT, 0.5D, 0.0D, 10.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, SHUFFLE_WEIGHT, 1.0D, 0.0D, 10.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, CASE_WEIGHT, 0.8D, 0.0D, 10.0D);
		changed |= GlitchSettingsHelper.sanitizeDouble(entry.settings, SWAP_NICKNAME_WEIGHT, 0.6D, 0.0D, 10.0D);

		int minMutations = GlitchSettingsHelper.getInt(entry.settings, MIN_MUTATIONS, 1);
		int maxMutations = GlitchSettingsHelper.getInt(entry.settings, MAX_MUTATIONS, 5);
		if (maxMutations < minMutations) {
			entry.settings.addProperty(MAX_MUTATIONS, minMutations);
			changed = true;
		}

		return changed;
	}

	@Override
	public boolean trigger(MinecraftServer server, RandomSource random, GlitchConfig.GlitchEntry entry, double stabilityPercent) {
		return false;
	}

	@Override
	public boolean triggerChat(
			MinecraftServer server,
			RandomSource random,
			GlitchConfig.GlitchEntry entry,
			double stabilityPercent,
			ServerPlayer sender,
			PlayerChatMessage message,
			ChatType.Bound params,
			boolean routeCreditor
	) {
		if (sender == null || message == null || params == null) {
			return false;
		}

		ChatMutationResult mutation = mutateMessage(server, random, entry, stabilityPercent, sender, message);
		if (mutation == null) {
			return false;
		}

		ChatType.Bound outgoingParams = params;
		if (mutation.displayNameOverride() != null) {
			Optional<Component> targetName = params.targetName();
			outgoingParams = new ChatType.Bound(params.chatType(), mutation.displayNameOverride(), targetName);
		}

		Component glitchedDecorated = outgoingParams.decorate(Component.literal(mutation.content()));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player == null) {
				continue;
			}
			player.sendSystemMessage(glitchedDecorated);
		}
		if (routeCreditor) {
			UUID displayedAuthorId = mutation.displayNameOverridePlayerId() != null
					? mutation.displayNameOverridePlayerId() : sender.getUUID();
			AncientUkrCreditorChatSystem.handleDisplayedChatMessage(server, displayedAuthorId, mutation.content());
		}
		if (mutation.displayNameOverridePlayerId() != null) {
			ServerPlayer effectiveSender = server.getPlayerList().getPlayer(mutation.displayNameOverridePlayerId());
			if (effectiveSender != null) {
				ServerMilkPocketDimensionSystem.handleGlitchedChatContent(mutation.content(), sender, effectiveSender);
			}
		}
		return true;
	}

	@Override
	public boolean triggerPrivateMessage(
			MinecraftServer server,
			RandomSource random,
			GlitchConfig.GlitchEntry entry,
			double stabilityPercent,
			CommandSourceStack source,
			ServerPlayer sender,
			Collection<ServerPlayer> targets,
			PlayerChatMessage message
	) {
		if (sender == null || source == null || message == null || targets == null || targets.isEmpty()) {
			return false;
		}

		ChatMutationResult mutation = mutateMessage(server, random, entry, stabilityPercent, sender, message);
		if (mutation == null) {
			return false;
		}

		Component senderName = mutation.displayNameOverride() != null
				? mutation.displayNameOverride()
				: source.getDisplayName();
		ChatType.Bound incomingParams = ChatType.bind(ChatType.MSG_COMMAND_INCOMING, source.registryAccess(), senderName);
		Component glitchedContent = Component.literal(mutation.content());

		for (ServerPlayer target : targets) {
			if (target == null) {
				continue;
			}

			ChatType.Bound outgoingParams = ChatType.bind(ChatType.MSG_COMMAND_OUTGOING, source.registryAccess(), senderName)
					.withTargetName(target.getDisplayName());
			sender.sendSystemMessage(outgoingParams.decorate(glitchedContent));
			target.sendSystemMessage(incomingParams.decorate(glitchedContent));
		}
		return true;
	}

	private static ChatMutationResult mutateMessage(
			MinecraftServer server,
			RandomSource random,
			GlitchConfig.GlitchEntry entry,
			double stabilityPercent,
			ServerPlayer sender,
			PlayerChatMessage message
	) {
		JsonObject settings = entry.settings == null ? new JsonObject() : entry.settings;
		String original = message.signedContent();
		if (original == null || original.isEmpty()) {
			return null;
		}

		double intensity = getRangeInstabilityFactor(stabilityPercent, entry.minStabilityPercent, entry.maxStabilityPercent);
		boolean canSwapNickname = hasAlternativeOnlinePlayer(server, sender);

		int minMutations = GlitchSettingsHelper.getInt(settings, MIN_MUTATIONS, 1);
		int maxMutations = GlitchSettingsHelper.getInt(settings, MAX_MUTATIONS, 5);
		int mutationCount = sampleMutationCount(minMutations, maxMutations, intensity);

		String finalContent = original;
		Component displayNameOverride = null;
		UUID displayNameOverridePlayerId = null;
		int maxDuplicateExtra = GlitchSettingsHelper.getInt(settings, MAX_DUPLICATE_EXTRA, 3);
		int targetMutations = Math.max(1, mutationCount);
		int appliedMutations = 0;
		int attempts = 0;
		boolean reverseUsed = false;

		while (appliedMutations < targetMutations && attempts < targetMutations * 6) {
			attempts++;

			boolean canPickNicknameSwap = canSwapNickname && displayNameOverride == null;
			TextEffect stepEffect = pickEffect(random, settings, canPickNicknameSwap);
			if (stepEffect == null) {
				break;
			}
			if (reverseUsed && stepEffect == TextEffect.REVERSE) {
				continue;
			}

			String before = finalContent;
			boolean appliedStep = false;

			switch (stepEffect) {
				case REPLACE -> finalContent = applyReplacementEffect(random, finalContent, 1);
				case INSERT -> finalContent = applyInsertEffect(random, finalContent, 1);
				case DUPLICATE -> finalContent = applyDuplicateEffect(random, finalContent, 1, maxDuplicateExtra, intensity);
				case REVERSE -> {
					finalContent = applyReverseEffect(random, finalContent, intensity);
					reverseUsed = true;
				}
				case SHUFFLE -> finalContent = applyShuffleEffect(random, finalContent, 1);
				case CASE -> finalContent = applyCaseEffect(random, finalContent, 1, intensity);
				case SWAP_NICKNAME -> {
					ServerPlayer picked = pickRandomOtherPlayer(server, sender, random);
					if (picked != null) {
						displayNameOverride = picked.getDisplayName();
						displayNameOverridePlayerId = picked.getUUID();
						appliedStep = true;
					}
				}
			}

			if (!appliedStep && !finalContent.equals(before)) {
				appliedStep = true;
			}
			if (appliedStep) {
				appliedMutations++;
			}
		}

		if (displayNameOverride == null && finalContent.equals(original)) {
			return null;
		}

		return new ChatMutationResult(finalContent, displayNameOverride, displayNameOverridePlayerId);
	}

	private static boolean hasAlternativeOnlinePlayer(MinecraftServer server, ServerPlayer sender) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.getUUID().equals(sender.getUUID()) && !ServerBackroomsSystem.isInBackrooms(player)) {
				return true;
			}
		}
		return false;
	}

	private static ServerPlayer pickRandomOtherPlayer(MinecraftServer server, ServerPlayer sender, RandomSource random) {
		List<ServerPlayer> candidates = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.getUUID().equals(sender.getUUID()) || ServerBackroomsSystem.isInBackrooms(player)) {
				continue;
			}
			candidates.add(player);
		}

		if (candidates.isEmpty()) {
			return null;
		}

		ServerPlayer picked = candidates.get(random.nextInt(candidates.size()));
		return picked;
	}

	private static TextEffect pickEffect(RandomSource random, JsonObject settings, boolean canSwapNickname) {
		double replaceWeight = GlitchSettingsHelper.getDouble(settings, REPLACE_WEIGHT, 1.0D);
		double insertWeight = GlitchSettingsHelper.getDouble(settings, INSERT_WEIGHT, 0.8D);
		double duplicateWeight = GlitchSettingsHelper.getDouble(settings, DUPLICATE_WEIGHT, 0.8D);
		double reverseWeight = GlitchSettingsHelper.getDouble(settings, REVERSE_WEIGHT, 0.5D);
		double shuffleWeight = GlitchSettingsHelper.getDouble(settings, SHUFFLE_WEIGHT, 1.0D);
		double caseWeight = GlitchSettingsHelper.getDouble(settings, CASE_WEIGHT, 0.8D);
		double swapNicknameWeight = canSwapNickname
				? GlitchSettingsHelper.getDouble(settings, SWAP_NICKNAME_WEIGHT, 0.6D)
				: 0.0D;

		double totalWeight = replaceWeight
				+ insertWeight
				+ duplicateWeight
				+ reverseWeight
				+ shuffleWeight
				+ caseWeight
				+ swapNicknameWeight;
		if (totalWeight <= 0.0D) {
			return null;
		}

		double roll = random.nextDouble() * totalWeight;
		roll -= replaceWeight;
		if (roll <= 0.0D) {
			return TextEffect.REPLACE;
		}
		roll -= insertWeight;
		if (roll <= 0.0D) {
			return TextEffect.INSERT;
		}
		roll -= duplicateWeight;
		if (roll <= 0.0D) {
			return TextEffect.DUPLICATE;
		}
		roll -= reverseWeight;
		if (roll <= 0.0D) {
			return TextEffect.REVERSE;
		}
		roll -= shuffleWeight;
		if (roll <= 0.0D) {
			return TextEffect.SHUFFLE;
		}
		roll -= caseWeight;
		if (roll <= 0.0D) {
			return TextEffect.CASE;
		}
		return TextEffect.SWAP_NICKNAME;
	}

	private static String applyReplacementEffect(RandomSource random, String text, int mutationCount) {
		if (text.isEmpty()) {
			return text;
		}

		char[] chars = text.toCharArray();
		List<Integer> mutableIndices = new ArrayList<>();
		for (int i = 0; i < chars.length; i++) {
			if (!Character.isLetter(chars[i])) {
				continue;
			}
			mutableIndices.add(i);
		}

		if (mutableIndices.isEmpty()) {
			return text;
		}

		Collections.shuffle(mutableIndices, new java.util.Random(random.nextLong()));
		int applied = Math.min(mutationCount, mutableIndices.size());
		for (int i = 0; i < applied; i++) {
			int index = mutableIndices.get(i);
			chars[index] = randomReplacementLetter(random, chars[index], text);
		}

		return new String(chars);
	}

	private static String applyInsertEffect(RandomSource random, String text, int mutationCount) {
		StringBuilder builder = new StringBuilder(text);
		int insertions = Math.max(1, mutationCount);
		for (int i = 0; i < insertions; i++) {
			char added = random.nextBoolean() ? randomBasicSymbol(random) : pickRandomLetterFromText(random, builder.toString());
			if (added == 0) {
				added = randomBasicSymbol(random);
			}
			int position = random.nextInt(builder.length() + 1);
			builder.insert(position, added);
		}
		return builder.toString();
	}

	private static String applyDuplicateEffect(
			RandomSource random,
			String text,
			int mutationCount,
			int maxDuplicateExtra,
			double intensity
	) {
		if (text.isEmpty()) {
			return text;
		}

		StringBuilder builder = new StringBuilder(text);
		int scaledMaxExtra = Math.max(1, sampleScaledInt(random, 1, Math.max(1, maxDuplicateExtra), intensity));
		int operations = Math.max(1, mutationCount);
		int applied = 0;
		int attempts = 0;

		while (applied < operations && attempts < operations * 6 && builder.length() < 280) {
			attempts++;
			int index = random.nextInt(builder.length());
			char selected = builder.charAt(index);
			if (!Character.isLetter(selected)) {
				continue;
			}

			int extraCopies = 1 + random.nextInt(scaledMaxExtra);
			builder.insert(index + 1, repeatChar(selected, extraCopies));
			applied++;
		}

		return builder.toString();
	}

	private static String applyReverseEffect(RandomSource random, String text, double intensity) {
		if (text.length() < 2) {
			return text;
		}

		if (intensity < 0.45D) {
			List<WordRange> words = collectWordRanges(text, 4);
			if (words.isEmpty()) {
				return text;
			}

			WordRange picked = words.get(random.nextInt(words.size()));
			StringBuilder builder = new StringBuilder(text);
			String fragment = builder.substring(picked.start, picked.end);
			builder.replace(picked.start, picked.end, new StringBuilder(fragment).reverse().toString());
			return builder.toString();
		}

		return new StringBuilder(text).reverse().toString();
	}

	private static String applyShuffleEffect(RandomSource random, String text, int mutationCount) {
		List<WordRange> words = collectWordRanges(text, 4);
		if (words.isEmpty()) {
			return text;
		}

		char[] chars = text.toCharArray();
		Collections.shuffle(words, new java.util.Random(random.nextLong()));
		int affectedWords = Math.min(words.size(), Math.max(1, mutationCount));

		for (int i = 0; i < affectedWords; i++) {
			WordRange range = words.get(i);
			int start = range.start;
			int end = range.end - 1;
			if (end - start < 3) {
				continue;
			}
			shuffleInnerCharacters(chars, start + 1, end - 1, random);
		}

		return new String(chars);
	}

	private static String applyCaseEffect(RandomSource random, String text, int mutationCount, double intensity) {
		if (text.isEmpty()) {
			return text;
		}

		char[] chars = text.toCharArray();
		List<Integer> letterIndices = new ArrayList<>();
		for (int i = 0; i < chars.length; i++) {
			if (Character.isLetter(chars[i])) {
				letterIndices.add(i);
			}
		}

		if (letterIndices.isEmpty()) {
			return text;
		}

		int baseFlips = Math.max(1, mutationCount * 2);
		int boostedFlips = baseFlips + (int) Math.round(letterIndices.size() * 0.20D * intensity);
		int flips = Math.min(letterIndices.size(), boostedFlips);
		Collections.shuffle(letterIndices, new java.util.Random(random.nextLong()));

		for (int i = 0; i < flips; i++) {
			int index = letterIndices.get(i);
			char current = chars[index];
			chars[index] = Character.isUpperCase(current)
					? Character.toLowerCase(current)
					: Character.toUpperCase(current);
		}

		return new String(chars);
	}

	private static List<WordRange> collectWordRanges(String text, int minWordLength) {
		List<WordRange> ranges = new ArrayList<>();
		int currentStart = -1;

		for (int i = 0; i < text.length(); i++) {
			char current = text.charAt(i);
			if (Character.isLetter(current)) {
				if (currentStart < 0) {
					currentStart = i;
				}
				continue;
			}

			if (currentStart >= 0) {
				int length = i - currentStart;
				if (length >= minWordLength) {
					ranges.add(new WordRange(currentStart, i));
				}
				currentStart = -1;
			}
		}

		if (currentStart >= 0) {
			int length = text.length() - currentStart;
			if (length >= minWordLength) {
				ranges.add(new WordRange(currentStart, text.length()));
			}
		}

		return ranges;
	}

	private static void shuffleInnerCharacters(char[] chars, int startInclusive, int endInclusive, RandomSource random) {
		for (int i = endInclusive; i > startInclusive; i--) {
			int swapWith = startInclusive + random.nextInt(i - startInclusive + 1);
			char temp = chars[i];
			chars[i] = chars[swapWith];
			chars[swapWith] = temp;
		}
	}

	private static char randomReplacementLetter(RandomSource random, char original, String sourceText) {
		if (original >= 'a' && original <= 'z') {
			return (char) ('a' + random.nextInt(LATIN_ALPHABET_SIZE));
		}
		if (original >= 'A' && original <= 'Z') {
			return (char) ('A' + random.nextInt(LATIN_ALPHABET_SIZE));
		}
		if (isCyrillicLower(original)) {
			return randomCyrillicLower(random);
		}
		if (isCyrillicUpper(original)) {
			return randomCyrillicUpper(random);
		}

		char fallback = pickRandomLetterFromText(random, sourceText);
		if (fallback != 0) {
			return fallback;
		}

		return Character.isUpperCase(original) ? 'A' : 'a';
	}

	private static boolean isCyrillicLower(char value) {
		return (value >= '\u0430' && value <= '\u044f') || value == '\u0451';
	}

	private static boolean isCyrillicUpper(char value) {
		return (value >= '\u0410' && value <= '\u042f') || value == '\u0401';
	}

	private static char randomCyrillicLower(RandomSource random) {
		int index = random.nextInt(CYRILLIC_ALPHABET_SIZE);
		if (index == CYRILLIC_ALPHABET_SIZE - 1) {
			return '\u0451';
		}
		return (char) ('\u0430' + index);
	}

	private static char randomCyrillicUpper(RandomSource random) {
		int index = random.nextInt(CYRILLIC_ALPHABET_SIZE);
		if (index == CYRILLIC_ALPHABET_SIZE - 1) {
			return '\u0401';
		}
		return (char) ('\u0410' + index);
	}

	private static char pickRandomLetterFromText(RandomSource random, String text) {
		int letters = 0;
		for (int i = 0; i < text.length(); i++) {
			if (Character.isLetter(text.charAt(i))) {
				letters++;
			}
		}

		if (letters <= 0) {
			return 0;
		}

		int selected = random.nextInt(letters);
		for (int i = 0; i < text.length(); i++) {
			char value = text.charAt(i);
			if (!Character.isLetter(value)) {
				continue;
			}
			if (selected == 0) {
				return value;
			}
			selected--;
		}

		return 0;
	}

	private static char randomBasicSymbol(RandomSource random) {
		return BASIC_SYMBOL_POOL[random.nextInt(BASIC_SYMBOL_POOL.length)];
	}

	private static String repeatChar(char value, int count) {
		if (count <= 0) {
			return "";
		}

		StringBuilder builder = new StringBuilder(count);
		for (int i = 0; i < count; i++) {
			builder.append(value);
		}
		return builder.toString();
	}

	private static int sampleScaledInt(RandomSource random, int min, int max, double intensity) {
		if (max <= min) {
			return min;
		}

		int scaledMax = min + (int) Math.round((max - min) * clamp01(intensity));
		if (scaledMax <= min) {
			return min;
		}
		return min + random.nextInt((scaledMax - min) + 1);
	}

	private static int sampleMutationCount(int min, int max, double intensity) {
		if (max <= min) {
			return min;
		}

		double factor = clamp01(intensity);
		double value = min + ((max - min) * factor);
		return Math.max(min, Math.min(max, (int) Math.round(value)));
	}

	private static double getRangeInstabilityFactor(double stabilityPercent, double minStabilityPercent, double maxStabilityPercent) {
		double range = maxStabilityPercent - minStabilityPercent;
		if (range <= 1.0E-9D) {
			return 1.0D;
		}

		double normalized = (stabilityPercent - minStabilityPercent) / range;
		return clamp01(1.0D - normalized);
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	private enum TextEffect {
		REPLACE,
		INSERT,
		DUPLICATE,
		REVERSE,
		SHUFFLE,
		CASE,
		SWAP_NICKNAME
	}

	private record WordRange(int start, int end) {
	}

	private record ChatMutationResult(String content, Component displayNameOverride, UUID displayNameOverridePlayerId) {
	}
}

