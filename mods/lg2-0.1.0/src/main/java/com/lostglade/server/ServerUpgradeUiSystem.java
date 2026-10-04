package com.lostglade.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.datafixers.util.Pair;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.lostglade.Lg2;
import com.lostglade.config.RaceConfig;
import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import com.lostglade.config.RaceConfig.RaceAbilitySlot;
import com.lostglade.config.UpgradeUiConfig;
import com.lostglade.item.ModItems;
import com.lostglade.server.glitch.InventoryTextureShuffleGlitch;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.storage.LevelResource;
import xyz.nucleoid.packettweaker.PacketContext;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ServerUpgradeUiSystem {
	private static final String STATE_FILE_NAME = "lg2-upgrade-state.json";
	private static final String NO_RACE_ID = "no_race";
	private static final String TITLE_OVERLAY_SHIFT = "\ue905";
	private static final String TITLE_OVERLAY_RESET = "\ue940\ue940\ue941\ue943";
	private static final int TITLE_OVERLAY_TARGET_ADVANCE = 168;
	private static final int TITLE_OVERLAY_SHIFT_ADVANCE = -8;
	private static final Identifier TOOLTIP_STYLE_ID = Objects.requireNonNull(Identifier.tryParse("lg2:upgrade_card"));
	private static final FontDescription TOOLTIP_FONT = new FontDescription.Resource(
			Objects.requireNonNull(Identifier.tryParse("lg2:upgrade_tooltip"))
	);
	private static final String TOOLTIP_ICON_COIN = "\ue981";
	private static final String NO_PACK_COIN = "₿";
	private static final int MAIN_BALANCE_CENTER_X = 127;
	private static final int ERAS_BALANCE_CENTER_X = 129;
	private static final int BALANCE_DIGIT_WIDTH = 6;
	private static final int TOOLTIP_DESCRIPTION_WRAP = 42;
	private static final int TOOLTIP_DESCRIPTION_WRAP_CJK = 22;
	private static final int TOOLTIP_DESCRIPTION_MAX_LINES = 4;
	private static final int TOOLTIP_REQUIREMENTS_WRAP = 46;
	private static final int TOOLTIP_REQUIREMENTS_WRAP_CJK = 24;
	private static final int ERAS_PROGRESS_GLYPHS_BASE = 0xE991;
	private static final int ERAS_AVAILABLE_GLYPHS_BASE = 0xE9C0;
	private static final int IT_PROGRESS_GLYPHS_BASE = 0xF020;
	private static final int IT_AVAILABLE_GLYPHS_BASE = 0xF000;
	private static final int MAIN_SCREEN_LOGO_GLYPHS_BASE = 0xE9D0;
	private static final int MAIN_SCREEN_DVD_GLYPHS_BASE = 0xEA20;
	private static final int MAIN_SCREEN_ARCHIVE_GLYPHS_BASE = 0xEB00;
	private static final int MAIN_SCREEN_SPIN_GLYPHS_BASE = 0xEC00;
	private static final int ERAS_PROGRESS_FRAME_COUNT = 35;
	private static final int ERAS_PURCHASE_STAGE_COUNT = 5;
	private static final int ERAS_PROGRESS_FRAMES_PER_STAGE = ERAS_PROGRESS_FRAME_COUNT / ERAS_PURCHASE_STAGE_COUNT;
	private static final int ERAS_PROGRESS_FRAME_TICKS = 2;
	private static final int IT_PROGRESS_FRAME_COUNT = 112;
	private static final int IT_PROGRESS_STATE_COUNT = 8;
	private static final int IT_PROGRESS_FRAMES_PER_TRANSITION = 14;
	private static final int IT_PROGRESS_FRAME_TICKS = 2;
	private static final int MAIN_SCREEN_LOGO_FRAME_COUNT = 16;
	private static final int MAIN_SCREEN_DVD_FRAME_COUNT = 130;
	private static final int MAIN_SCREEN_ARCHIVE_FRAME_COUNT = 24;
	private static final int MAIN_SCREEN_SPIN_FRAME_COUNT = 48;
	private static final int MAIN_SCREEN_LOGO_FRAME_TICKS = 2;
	private static final int MAIN_SCREEN_DVD_FRAME_TICKS = 4;
	private static final int MAIN_SCREEN_ARCHIVE_FRAME_TICKS = 2;
	private static final int MAIN_SCREEN_SPIN_FRAME_TICKS = 2;
	private static final int BITCOIN_CONTAINER_SCAN_MAX_DEPTH = 8;
	private static final int MAIN_SCREEN_OVERLAY_GLYPH_ADVANCE = 119;
	private static final int ERAS_OVERLAY_GLYPH_ADVANCE = 150;
	private static final int IT_OVERLAY_GLYPH_ADVANCE = 135;
	private static final int MENU_LOCK_CURSOR_FLASH_TICKS = 4;
	private static final double MAIN_SCREEN_ARCHIVE_VARIANT_CHANCE = 0.0001D;
	private static final int MAIN_SCREEN_VARIANT_GLITCH = 0;
	private static final int MAIN_SCREEN_VARIANT_DVD = 1;
	private static final int MAIN_SCREEN_VARIANT_SPIN = 2;
	private static final int MAIN_SCREEN_VARIANT_ARCHIVE = 3;
	private static final int MENU_VISUAL_RESYNC_TICKS = 3;
	private static final Identifier PURCHASE_BLOCKED_SOUND_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "ui_purchase_blocked");
	private static final float PURCHASE_BLOCKED_SOUND_VOLUME = 0.8F;
	private static final float PURCHASE_BLOCKED_SOUND_PITCH = 1.0F;
	private static final float PURCHASE_BLOCKED_FALLBACK_VOLUME = 0.75F;
	private static final float PURCHASE_BLOCKED_FALLBACK_PITCH = 0.95F;
	private static final String RACES_SCREEN_ID = "races";
	private static final String RACE_ATTACK_BUTTON_ID = "attack";
	private static final String RACE_DEFENSE_BUTTON_ID = "defense";
	private static final String RACE_ABILITY_BUTTON_ID = "ability";
	private static final String RACE_SHNYAGA_BUTTON_ID = "shnyaga";
	private static final String IT_DRONE_SCOUT_UPGRADE_ID = "it_drone_scout";
	private static final String IT_CAMERA_UPGRADE_ID = "it_camera";
	private static final String IT_MICROPHONE_UPGRADE_ID = "it_microphone";
	private static final String IT_SPEAKER_UPGRADE_ID = "it_speaker";
	private static final String IT_SCREEN_UPGRADE_ID = "it_screen";
	private static final String IT_BLUETOOTH_ADAPTER_UPGRADE_ID = "it_bluetooth_adapter";
	private static final int RACE_ATTACK_SLOT = 37;
	private static final int RACE_DEFENSE_SLOT = 39;
	private static final int RACE_ABILITY_SLOT = 41;
	private static final int RACE_SHNYAGA_SLOT = 43;
	private static final int RACE_ATTACK_COLOR = 0xE05555;
	private static final int RACE_DEFENSE_COLOR = 0x5796FF;
	private static final int RACE_ABILITY_COLOR = 0xB06CFF;
	private static final int RACE_SHNYAGA_COLOR = 0x5FCB72;
	private static final String[] ERAS_PROGRESS_GLYPHS = createGlyphSequence(
			ERAS_PROGRESS_GLYPHS_BASE,
			ERAS_PROGRESS_FRAME_COUNT
	);
	private static final String[] ERAS_AVAILABLE_GLYPHS = createGlyphSequence(
			ERAS_AVAILABLE_GLYPHS_BASE,
			ERAS_PURCHASE_STAGE_COUNT + 1
	);
	private static final String[] IT_PROGRESS_GLYPHS = createGlyphSequence(
			IT_PROGRESS_GLYPHS_BASE,
			IT_PROGRESS_FRAME_COUNT
	);
	private static final String[] IT_AVAILABLE_GLYPHS = createGlyphSequence(
			IT_AVAILABLE_GLYPHS_BASE,
			IT_PROGRESS_STATE_COUNT
	);
	private static final String[] MAIN_SCREEN_LOGO_GLYPHS = createGlyphSequence(
			MAIN_SCREEN_LOGO_GLYPHS_BASE,
			MAIN_SCREEN_LOGO_FRAME_COUNT
	);
	private static final String[] MAIN_SCREEN_DVD_GLYPHS = createGlyphSequence(
			MAIN_SCREEN_DVD_GLYPHS_BASE,
			MAIN_SCREEN_DVD_FRAME_COUNT
	);
	private static final String[] MAIN_SCREEN_ARCHIVE_GLYPHS = createGlyphSequence(
			MAIN_SCREEN_ARCHIVE_GLYPHS_BASE,
			MAIN_SCREEN_ARCHIVE_FRAME_COUNT
	);
	private static final String[] MAIN_SCREEN_SPIN_GLYPHS = createGlyphSequence(
			MAIN_SCREEN_SPIN_GLYPHS_BASE,
			MAIN_SCREEN_SPIN_FRAME_COUNT
	);
	private static final UpgradeUiConfig.IconConfig MENU_INVISIBLE_ICON = createTransientIcon("minecraft:paper", "lg2:gui/button/invisible", false);
	private static final UpgradeUiConfig.IconConfig MENU_LOCK_ICON = createTransientIcon("minecraft:paper", "lg2:gui/button/eras_lock", false);
	private static final Gson STATE_GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Map<String, Integer>> PLAYER_UPGRADE_LEVELS = new HashMap<>();
	private static final Map<String, Integer> LEGACY_GLOBAL_UPGRADE_LEVELS = new HashMap<>();
	private static final Map<UUID, EraProgressAnimation> ERAS_PROGRESS_ANIMATIONS = new HashMap<>();
	private static final Map<UUID, ItProgressAnimation> IT_PROGRESS_ANIMATIONS = new HashMap<>();
	private static final Map<UUID, String> ERAS_TITLE_SIGNATURES = new HashMap<>();
	private static final Map<UUID, String> IT_TITLE_SIGNATURES = new HashMap<>();
	private static final Map<UUID, String> MAIN_TITLE_SIGNATURES = new HashMap<>();
	private static final Map<UUID, Integer> MAIN_SCREEN_LOGO_VARIANTS = new HashMap<>();
	private static final Map<UUID, Integer> PENDING_MENU_VISUAL_RESYNCS = new HashMap<>();
	private static final Map<UUID, Integer> PENDING_LOCK_CURSOR_RESTORES = new HashMap<>();
	private static boolean stateLoaded = false;
	private static boolean stateDirty = false;

	private ServerUpgradeUiSystem() {
	}

	public static void register() {
		UpgradeUiConfig.load();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			loadState(server);
			UpgradeUiConfig.load();
			for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
				ItRecipeBookSystem.syncPlayerRecipeBook(player);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				AccountAuthSystem.runWhenAuthenticated(handler.player,
						() -> server.execute(() -> ItRecipeBookSystem.syncPlayerRecipeBook(handler.player)))
		);
		ServerLifecycleEvents.SERVER_STOPPING.register(ServerUpgradeUiSystem::saveState);
		ServerTickEvents.END_SERVER_TICK.register(ServerUpgradeUiSystem::tickAnimatedErasTitles);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(
						Commands.literal("serverupgradeui")
								.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
								.then(Commands.literal("reload")
										.executes(context -> {
											UpgradeUiConfig.load();
											context.getSource().sendSuccess(() -> Component.literal("Reloaded upgrade UI config"), true);
											return 1;
										}))
								.then(Commands.literal("reset")
										.then(Commands.argument("targets", EntityArgument.players())
												.then(Commands.literal("all")
														.executes(context -> {
															int changed = resetAllPurchases(
																	context.getSource().getServer(),
																	EntityArgument.getPlayers(context, "targets")
															);
															context.getSource().sendSuccess(
																	() -> Component.literal("Reset all purchases for " + changed + " player(s)"),
																	true
															);
															return changed;
														}))
												.then(Commands.argument("upgrade_id", StringArgumentType.word())
														.suggests((context, builder) -> SharedSuggestionProvider.suggest(collectKnownUpgradeIds(), builder))
														.executes(context -> {
															String upgradeId = StringArgumentType.getString(context, "upgrade_id");
															int changed = resetSinglePurchase(
																	context.getSource().getServer(),
																	EntityArgument.getPlayers(context, "targets"),
																	upgradeId
															);
															context.getSource().sendSuccess(
																	() -> Component.literal("Reset '" + upgradeId + "' for " + changed + " player(s)"),
																	true
															);
															return changed;
														}))))
				)
		);
	}

	public static boolean openRootScreen(ServerPlayer player) {
		UpgradeUiConfig.load();
		UpgradeUiConfig.ConfigData config = UpgradeUiConfig.get();
		if (!config.enabled) {
			sendPlayerMessage(player, localizeSystem(player, "Upgrade interface is disabled.", "Интерфейс прокачки отключён."));
			return false;
		}

		return openScreen(player, config.rootScreenId);
	}

	public static boolean openScreen(ServerPlayer player, String screenId) {
		if (player == null || screenId == null || screenId.isBlank()) {
			return false;
		}
		if (!SeasonStartSystem.isServerMenuAvailable(player)) {
			SeasonStartSystem.onServerMenuRequestedTooEarly(player);
			return false;
		}
		if ("it_drones".equals(screenId) && !hasUpgrade(player, IT_DRONE_SCOUT_UPGRADE_ID)) {
			playPurchaseBlockedSound(player);
			return false;
		}

		UpgradeUiConfig.load();
		UpgradeUiConfig.ConfigData config = UpgradeUiConfig.get();
		UpgradeUiConfig.ScreenConfig screen = config.screens.get(screenId);
		if (screen == null || !screen.enabled) {
			sendPlayerMessage(player, localizeSystem(player, "Screen not found.", "Экран не найден."));
			return false;
		}

		final UpgradeUiConfig.ThemeConfig resolvedTheme = resolveTheme(config, screen);

		boolean hasPack = PolymerResourcePackUtils.hasMainPack(player);
		if ("main".equals(screenId)) {
			MAIN_SCREEN_LOGO_VARIANTS.put(player.getUUID(), chooseMainScreenVariant(player));
		} else {
			MAIN_SCREEN_LOGO_VARIANTS.remove(player.getUUID());
		}
		InventoryTextureShuffleGlitch.onUpgradeMenuOpened(player);
		Component title = buildTitle(player, screenId, hasPack, screen, resolvedTheme);
		player.openMenu(new SimpleMenuProvider(
				(syncId, inventory, menuPlayer) -> createMenu(syncId, inventory, player, screenId, screen, resolvedTheme, hasPack),
				title
		));
		hideLowerInventoryVisuals(player, player.containerMenu);
		PENDING_MENU_VISUAL_RESYNCS.put(player.getUUID(), MENU_VISUAL_RESYNC_TICKS);
		if ("eras".equals(screenId)) {
			ERAS_TITLE_SIGNATURES.put(player.getUUID(), erasTitleSignature(player, currentGameTime(player)));
			IT_TITLE_SIGNATURES.remove(player.getUUID());
			IT_PROGRESS_ANIMATIONS.remove(player.getUUID());
			MAIN_TITLE_SIGNATURES.remove(player.getUUID());
		} else if ("it_hub".equals(screenId)) {
			IT_TITLE_SIGNATURES.put(player.getUUID(), itTitleSignature(player, currentGameTime(player)));
			ERAS_TITLE_SIGNATURES.remove(player.getUUID());
			ERAS_PROGRESS_ANIMATIONS.remove(player.getUUID());
			MAIN_TITLE_SIGNATURES.remove(player.getUUID());
		} else if ("main".equals(screenId)) {
			MAIN_TITLE_SIGNATURES.put(player.getUUID(), mainTitleSignature(player, currentGameTime(player)));
			ERAS_TITLE_SIGNATURES.remove(player.getUUID());
			ERAS_PROGRESS_ANIMATIONS.remove(player.getUUID());
			IT_TITLE_SIGNATURES.remove(player.getUUID());
			IT_PROGRESS_ANIMATIONS.remove(player.getUUID());
		} else {
			ERAS_TITLE_SIGNATURES.remove(player.getUUID());
			ERAS_PROGRESS_ANIMATIONS.remove(player.getUUID());
			IT_TITLE_SIGNATURES.remove(player.getUUID());
			IT_PROGRESS_ANIMATIONS.remove(player.getUUID());
			MAIN_TITLE_SIGNATURES.remove(player.getUUID());
		}
		SeasonStartSystem.onServerUpgradeScreenOpened(player, screenId);
		return true;
	}

	public static boolean isUpgradeMenu(AbstractContainerMenu menu) {
		return menu instanceof UpgradeMenu;
	}

	public static boolean isUpgradeMenuOpen(ServerPlayer player) {
		return player != null && isUpgradeMenu(player.containerMenu);
	}

	public static boolean hasUpgrade(ServerPlayer player, String upgradeId) {
		return getUpgradeLevel(player, upgradeId) >= 1;
	}

	public static String getUpgradeDisplayName(ServerPlayer player, String upgradeId) {
		return resolveUpgradeDisplayName(player, upgradeId);
	}

	private static int resetAllPurchases(MinecraftServer server, Collection<ServerPlayer> targets) {
		if (server == null || targets == null || targets.isEmpty()) {
			return 0;
		}

		int changedPlayers = 0;
		for (ServerPlayer player : targets) {
			if (player == null) {
				continue;
			}

			Map<String, Integer> removed = PLAYER_UPGRADE_LEVELS.remove(player.getUUID().toString());
			if (removed == null || removed.isEmpty()) {
				continue;
			}

			changedPlayers++;
			stateDirty = true;
			refreshUpgradeMenu(player);
		}

		saveState(server);
		return changedPlayers;
	}

	private static int resetSinglePurchase(MinecraftServer server, Collection<ServerPlayer> targets, String upgradeId) {
		if (server == null || targets == null || targets.isEmpty() || upgradeId == null || upgradeId.isBlank()) {
			return 0;
		}

		int changedPlayers = 0;
		for (ServerPlayer player : targets) {
			if (player == null) {
				continue;
			}

			Map<String, Integer> levels = PLAYER_UPGRADE_LEVELS.get(player.getUUID().toString());
			if (levels == null || levels.remove(upgradeId) == null) {
				continue;
			}

			if (levels.isEmpty()) {
				PLAYER_UPGRADE_LEVELS.remove(player.getUUID().toString());
			}
			changedPlayers++;
			stateDirty = true;
			refreshUpgradeMenu(player);
		}

		saveState(server);
		return changedPlayers;
	}

	/** Removes only the temporary race upgrades bought during the season-start tutorial. */
	public static void resetSeasonStartRacePurchases(
			MinecraftServer server,
			Collection<UUID> playerIds,
			Collection<String> upgradeIds
	) {
		if (server == null || playerIds == null || playerIds.isEmpty() || upgradeIds == null || upgradeIds.isEmpty()) {
			return;
		}
		Set<String> ids = new LinkedHashSet<>();
		for (String upgradeId : upgradeIds) {
			if (upgradeId != null && !upgradeId.isBlank()) {
				ids.add(upgradeId.trim());
			}
		}
		if (ids.isEmpty()) {
			return;
		}
		for (UUID playerId : playerIds) {
			if (playerId == null) {
				continue;
			}
			Map<String, Integer> levels = PLAYER_UPGRADE_LEVELS.get(playerId.toString());
			if (levels == null) {
				continue;
			}
			boolean changed = levels.keySet().removeIf(ids::contains);
			if (!changed) {
				continue;
			}
			if (levels.isEmpty()) {
				PLAYER_UPGRADE_LEVELS.remove(playerId.toString());
			}
			stateDirty = true;
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				refreshUpgradeMenu(player);
				CartelSecretRecipeBookSystem.syncPlayerRecipeBook(player);
				CopperManGogglesSystem.syncPlayerRecipeBook(player);
				MarkShieldRecipeSystem.syncPlayerRecipeBook(player);
				ItRecipeBookSystem.syncPlayerRecipeBook(player);
			}
		}
		saveState(server);
	}

	private static Iterable<String> collectKnownUpgradeIds() {
		LinkedHashSet<String> ids = new LinkedHashSet<>();
		for (UpgradeUiConfig.ScreenConfig screen : UpgradeUiConfig.get().screens.values()) {
			if (screen == null || screen.buttons == null) {
				continue;
			}
			for (UpgradeUiConfig.ButtonConfig button : screen.buttons.values()) {
				if (button == null || button.upgradeId == null || button.upgradeId.isBlank()) {
					continue;
				}
				ids.add(button.upgradeId);
			}
		}
		RaceConfig.ConfigData raceConfig = RaceConfig.get();
		if (raceConfig != null && raceConfig.races != null) {
			for (RaceConfig.PlayerRaceConfig race : raceConfig.races) {
				collectRaceUpgradeId(ids, race == null ? null : race.attack);
				collectRaceUpgradeId(ids, race == null ? null : race.defense);
				collectRaceUpgradeId(ids, race == null ? null : race.uniqueAbility);
				collectRaceUpgradeId(ids, race == null ? null : race.shnyaga);
			}
		}
		return ids;
	}

	private static void refreshUpgradeMenu(ServerPlayer player) {
		if (!(player.containerMenu instanceof UpgradeMenu menu)) {
			return;
		}
		openScreen(player, menu.screenId);
	}

	private static ChestMenu createMenu(
			int syncId,
			Inventory inventory,
			ServerPlayer viewer,
			String screenId,
			UpgradeUiConfig.ScreenConfig screen,
			UpgradeUiConfig.ThemeConfig theme,
			boolean hasPack
	) {
		SimpleContainer container = new SimpleContainer(screen.rows * 9);
		fillContainer(container, viewer, screenId, screen, theme, hasPack);
		return new UpgradeMenu(syncId, inventory, container, screenId, screen.rows, viewer, hasPack);
	}

	private static void fillContainer(
			SimpleContainer container,
			ServerPlayer viewer,
			String screenId,
			UpgradeUiConfig.ScreenConfig screen,
			UpgradeUiConfig.ThemeConfig theme,
			boolean hasPack
	) {
		if (theme.fillEmptySlots) {
			ItemStack filler = buildFillerStack(theme.fillerIcon, hasPack);
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				container.setItem(slot, filler.copy());
			}
		}

		for (Map.Entry<String, UpgradeUiConfig.ButtonConfig> entry : resolveScreenButtons(viewer, screenId, screen).entrySet()) {
			String buttonId = entry.getKey();
			UpgradeUiConfig.ButtonConfig button = entry.getValue();
			int anchorSlot = getButtonAnchorSlot(screenId, buttonId, button, hasPack);
			int displaySlot = getDisplaySlot(screenId, buttonId, button, hasPack);
			if (button == null
					|| !button.enabled
					|| anchorSlot < 0
					|| anchorSlot >= container.getContainerSize()
					|| displaySlot < 0
					|| displaySlot >= container.getContainerSize()) {
				continue;
			}
			if (shouldHideButtonVisual(screenId, buttonId)) {
				continue;
			}
			if (isPureDecorButton(button)) {
				continue;
			}
			ItemStack stack = buildButtonStack(viewer, screenId, buttonId, button, hasPack);
			container.setItem(displaySlot, stack);
		}
	}

	private static Map<String, UpgradeUiConfig.ButtonConfig> resolveScreenButtons(
			ServerPlayer viewer,
			String screenId,
			UpgradeUiConfig.ScreenConfig screen
	) {
		LinkedHashMap<String, UpgradeUiConfig.ButtonConfig> buttons = new LinkedHashMap<>();
		if (screen != null && screen.buttons != null) {
			buttons.putAll(screen.buttons);
		}
		if (RACES_SCREEN_ID.equals(screenId)) {
			applyRaceButtons(viewer, buttons);
		}
		return buttons;
	}

	private static void applyRaceButtons(ServerPlayer viewer, Map<String, UpgradeUiConfig.ButtonConfig> buttons) {
		if (buttons == null) {
			return;
		}

		buttons.remove("budget");
		buttons.remove("medium");
		buttons.remove("expensive");
		buttons.remove("elite");

		Optional<RaceConfig.PlayerRaceConfig> raceOptional = ServerRaceSystem.getRace(viewer);
		if (raceOptional.isEmpty()) {
			buttons.remove(RACE_ATTACK_BUTTON_ID);
			buttons.remove(RACE_DEFENSE_BUTTON_ID);
			buttons.remove(RACE_ABILITY_BUTTON_ID);
			buttons.remove(RACE_SHNYAGA_BUTTON_ID);
			return;
		}

		RaceConfig.PlayerRaceConfig race = raceOptional.get();
		putRaceButton(buttons, RACE_ATTACK_BUTTON_ID, race, race.attack, RaceAbilitySlot.ATTACK);
		putRaceButton(buttons, RACE_DEFENSE_BUTTON_ID, race, race.defense, RaceAbilitySlot.DEFENSE);
		putRaceButton(buttons, RACE_ABILITY_BUTTON_ID, race, race.uniqueAbility, RaceAbilitySlot.UNIQUE_ABILITY);
		putRaceButton(buttons, RACE_SHNYAGA_BUTTON_ID, race, race.shnyaga, RaceAbilitySlot.SHNYAGA);
	}

	private static void putRaceButton(
			Map<String, UpgradeUiConfig.ButtonConfig> buttons,
			String buttonId,
			RaceConfig.PlayerRaceConfig race,
			RaceAbilityConfig ability,
			RaceAbilitySlot slot
	) {
		if (buttons == null) {
			return;
		}
		if (ability == null || !ability.enabled) {
			buttons.remove(buttonId);
			return;
		}

		UpgradeUiConfig.ButtonConfig template = buttons.get(buttonId);
		UpgradeUiConfig.ButtonConfig button = new UpgradeUiConfig.ButtonConfig();
		button.enabled = true;
		button.slot = template == null ? defaultRaceButtonSlot(buttonId) : template.slot;
		button.hitboxWidth = template == null ? 1 : Math.max(1, template.hitboxWidth);
		button.hitboxHeight = template == null ? 1 : Math.max(1, template.hitboxHeight);
		button.type = UpgradeUiConfig.ButtonType.PURCHASE_UPGRADE.id;
		button.targetScreenId = "";
		button.upgradeId = safeString(ability.abilityId).trim();
		button.pricesBitcoins = new ArrayList<>(List.of(Math.max(0, ability.priceBitcoins)));
		button.icon = raceButtonIcon(slot);
		button.lockedIcon = copyIcon(UpgradeUiConfig.IconConfig.defaultLocked());
		button.maxedIcon = raceButtonMaxedIcon(slot);
		boolean preserveBlankText = preserveBlankRaceAbilityText(race);
		String fallbackName = preserveBlankText ? "" : defaultRaceButtonName(slot);
		String buttonName = nonBlank(ability.name, fallbackName);
		String loreLine = nonBlank(ability.description, buttonName);
		button.name = singleValueText(buttonName);
		button.lore = loreLine.isEmpty() ? singleValueLines(List.of()) : singleValueLines(List.of(loreLine));
		button.requirements = new ArrayList<>();
		button.requirementGroups = new ArrayList<>();
		button.closeAfterClick = false;
		buttons.put(buttonId, button);
	}

	private static void collectRaceUpgradeId(Collection<String> ids, RaceAbilityConfig ability) {
		if (ids == null || ability == null || ability.abilityId == null || ability.abilityId.isBlank() || !ability.enabled) {
			return;
		}
		ids.add(ability.abilityId.trim());
	}

	private static UpgradeUiConfig.LocalizedText singleValueText(String value) {
		UpgradeUiConfig.LocalizedText text = new UpgradeUiConfig.LocalizedText();
		text.values.put("default", value);
		return text;
	}

	private static UpgradeUiConfig.LocalizedLines singleValueLines(List<String> lines) {
		UpgradeUiConfig.LocalizedLines localized = new UpgradeUiConfig.LocalizedLines();
		localized.values.put("default", new ArrayList<>(lines));
		return localized;
	}

	private static UpgradeUiConfig.IconConfig raceButtonIcon(RaceAbilitySlot slot) {
		UpgradeUiConfig.IconConfig icon = new UpgradeUiConfig.IconConfig();
		icon.fallbackItem = switch (slot) {
			case ATTACK -> "minecraft:iron_sword";
			case DEFENSE -> "minecraft:shield";
			case UNIQUE_ABILITY -> "minecraft:amethyst_shard";
			case SHNYAGA -> "minecraft:slime_ball";
			case STOCK -> "minecraft:paper";
		};
		icon.packModel = "lg2:gui/button/upgrade";
		icon.count = 1;
		icon.foil = false;
		return icon;
	}

	private static UpgradeUiConfig.IconConfig raceButtonMaxedIcon(RaceAbilitySlot slot) {
		UpgradeUiConfig.IconConfig icon = raceButtonIcon(slot);
		icon.foil = true;
		return icon;
	}

	private static UpgradeUiConfig.IconConfig copyIcon(UpgradeUiConfig.IconConfig source) {
		UpgradeUiConfig.IconConfig icon = new UpgradeUiConfig.IconConfig();
		if (source == null) {
			return icon;
		}
		icon.fallbackItem = source.fallbackItem;
		icon.packModel = source.packModel;
		icon.count = source.count;
		icon.foil = source.foil;
		return icon;
	}

	private static int defaultRaceButtonSlot(String buttonId) {
		return switch (safeString(buttonId)) {
			case RACE_ATTACK_BUTTON_ID -> RACE_ATTACK_SLOT;
			case RACE_DEFENSE_BUTTON_ID -> RACE_DEFENSE_SLOT;
			case RACE_ABILITY_BUTTON_ID -> RACE_ABILITY_SLOT;
			case RACE_SHNYAGA_BUTTON_ID -> RACE_SHNYAGA_SLOT;
			default -> RACE_ATTACK_SLOT;
		};
	}

	private static String defaultRaceButtonName(RaceAbilitySlot slot) {
		return switch (slot) {
			case ATTACK -> "Атака";
			case DEFENSE -> "Защита";
			case UNIQUE_ABILITY -> "Абилка";
			case SHNYAGA -> "Шняга";
			case STOCK -> "Сток";
		};
	}

	private static int getButtonAnchorSlot(
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button,
			boolean hasPack
	) {
		if (button == null) {
			return 0;
		}

		return button.slot;
	}

	private static int getDisplaySlot(
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button,
			boolean hasPack
	) {
		if (button == null) {
			return 0;
		}

		int anchorSlot = getButtonAnchorSlot(screenId, buttonId, button, hasPack);
		int width = Math.max(1, button.hitboxWidth);
		int height = Math.max(1, button.hitboxHeight);
		int startColumn = anchorSlot % 9;
		int startRow = anchorSlot / 9;
		int centerColumn = startColumn + ((width - 1) / 2);
		int centerRow = startRow + ((height - 1) / 2);
		return (centerRow * 9) + centerColumn;
	}

	private static ItemStack buildFillerStack(UpgradeUiConfig.IconConfig icon, boolean hasPack) {
		ItemStack stack = createBaseStack(hasPack ? MENU_INVISIBLE_ICON : icon, hasPack);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
		return stack;
	}

	private static ItemStack buildButtonStack(
			ServerPlayer viewer,
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button,
			boolean hasPack
	) {
		return buildButtonStack(viewer, screenId, buttonId, button, hasPack, null);
	}

	private static ItemStack buildButtonStack(
			ServerPlayer viewer,
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button,
			boolean hasPack,
			UpgradeUiConfig.IconConfig iconOverride
	) {
		boolean useCustomTooltip = usesCustomTooltipPresentation(button, hasPack);
		ButtonState state = getButtonState(viewer, button);
		UpgradeUiConfig.IconConfig icon = iconOverride != null
				? iconOverride
				: resolveButtonIcon(viewer, screenId, buttonId, button, state, hasPack);

		ItemStack stack = createBaseStack(icon, hasPack);
		Map<String, String> placeholders = buildPlaceholders(viewer, screenId, buttonId, button);
		stack.set(DataComponents.CUSTOM_NAME, buildTooltipNameComponent(viewer, screenId, buttonId, button, state, placeholders, useCustomTooltip));

		List<Component> loreLines = buildTooltipLore(viewer, button, state, placeholders, useCustomTooltip);
		if (!loreLines.isEmpty()) {
			ItemLore lore = ItemLore.EMPTY;
			for (Component line : loreLines) {
				lore = lore.withLineAdded(line);
			}
			stack.set(DataComponents.LORE, lore);
		}
		if (useCustomTooltip) {
			stack.set(DataComponents.TOOLTIP_STYLE, TOOLTIP_STYLE_ID);
		}

		return stack;
	}

	private static int getButtonVisualSlot(
			ServerPlayer viewer,
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button,
			boolean hasPack
	) {
		if (button == null) {
			return 0;
		}

		int anchorSlot = getButtonAnchorSlot(screenId, buttonId, button, hasPack);
		if (shouldUseCornerPurchaseLockVisual(viewer, screenId, buttonId, button, hasPack)) {
			int width = Math.max(1, button.hitboxWidth);
			return anchorSlot + width - 1;
		}
		return getDisplaySlot(screenId, buttonId, button, hasPack);
	}

	private static boolean shouldUseCornerPurchaseLockVisual(
			ServerPlayer viewer,
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button,
			boolean hasPack
	) {
		if (!hasPack
				|| viewer == null
				|| button == null
				|| !isDimensionPurchaseButton(screenId, buttonId, button)
				|| Math.max(1, button.hitboxWidth) < 2) {
			return false;
		}

		return shouldShowPurchaseLock(viewer, button, getButtonState(viewer, button));
	}

	private static boolean isDimensionPurchaseButton(
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button
	) {
		return "worlds".equals(screenId)
				&& button != null
				&& UpgradeUiConfig.ButtonType.PURCHASE_UPGRADE.id.equals(button.type)
				&& ("nether".equals(buttonId) || "end".equals(buttonId));
	}

	private static boolean usesCustomTooltipPresentation(UpgradeUiConfig.ButtonConfig button, boolean hasPack) {
		return hasPack
				&& button != null
				&& UpgradeUiConfig.ButtonType.PURCHASE_UPGRADE.id.equals(button.type);
	}

	private static UpgradeUiConfig.IconConfig resolveButtonIcon(
			ServerPlayer viewer,
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button,
			ButtonState state,
			boolean hasPack
	) {
		if (shouldShowPurchaseLock(viewer, button, state)) {
			return MENU_LOCK_ICON;
		}

		return hasPack ? MENU_INVISIBLE_ICON : button.icon;
	}

	private static boolean shouldShowPurchaseLock(
			ServerPlayer player,
			UpgradeUiConfig.ButtonConfig button,
			ButtonState state
	) {
		if (button == null || !UpgradeUiConfig.ButtonType.PURCHASE_UPGRADE.id.equals(button.type)) {
			return false;
		}
		if (state == ButtonState.MAXED) {
			return false;
		}
		if (state == ButtonState.LOCKED) {
			return true;
		}
		return getUpgradeLevel(player, button.upgradeId) <= 0;
	}

	private static Component buildTooltipNameComponent(
			ServerPlayer viewer,
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button,
			ButtonState state,
			Map<String, String> placeholders,
			boolean hasPack
	) {
		String name = applyPlaceholders(resolveLocalizedText(viewer, button.name.values), placeholders);
		int color = resolveButtonNameColor(screenId, buttonId, state);
		return styledTooltipText(name, color, true, hasPack);
	}

	private static int resolveButtonNameColor(String screenId, String buttonId, ButtonState state) {
		if (RACES_SCREEN_ID.equals(screenId)) {
			Integer raceColor = switch (safeString(buttonId)) {
				case RACE_ATTACK_BUTTON_ID -> RACE_ATTACK_COLOR;
				case RACE_DEFENSE_BUTTON_ID -> RACE_DEFENSE_COLOR;
				case RACE_ABILITY_BUTTON_ID -> RACE_ABILITY_COLOR;
				case RACE_SHNYAGA_BUTTON_ID -> RACE_SHNYAGA_COLOR;
				default -> null;
			};
			if (raceColor != null) {
				return raceColor;
			}
		}

		return switch (state) {
			case LOCKED -> 0xB6C0D0;
			case MAXED -> 0xA8F2C0;
			case ACTIVE -> 0xF4E3BA;
		};
	}

	private static List<Component> buildTooltipLore(
			ServerPlayer viewer,
			UpgradeUiConfig.ButtonConfig button,
			ButtonState state,
			Map<String, String> placeholders,
			boolean hasPack
	) {
		List<Component> result = new ArrayList<>();
		List<String> description = compactLocalizedLines(
				staticDescriptionLines(resolveLocalizedLines(viewer, button.lore.values)),
				placeholders,
				tooltipWrapWidth(viewer, TOOLTIP_DESCRIPTION_WRAP, TOOLTIP_DESCRIPTION_WRAP_CJK),
				TOOLTIP_DESCRIPTION_MAX_LINES
		);
		if (!description.isEmpty()) {
			result.add(buildSpacerLine(hasPack));
		}
		for (int i = 0; i < description.size(); i++) {
			String prefix = "";
			result.add(buildTooltipLine(
					prefix,
					0x91B8D9,
					description.get(i),
					0xD8E0EA,
					hasPack
			));
		}

		if (UpgradeUiConfig.ButtonType.byId(button.type) == UpgradeUiConfig.ButtonType.PURCHASE_UPGRADE) {
			if (!description.isEmpty()) {
				result.add(buildSpacerLine(hasPack));
			}
			result.add(buildPriceLine(viewer, button, state, hasPack));
		}
		return result;
	}

	private static Component buildPriceLine(
			ServerPlayer viewer,
			UpgradeUiConfig.ButtonConfig button,
			ButtonState state,
			boolean hasPack
	) {
		if (button.pricesBitcoins == null || button.pricesBitcoins.isEmpty()) {
			return styledTooltipText("", 0xFFFFFF, false, hasPack);
		}

		int currentLevel = getUpgradeLevel(viewer, button.upgradeId);
		int index = Math.max(0, Math.min(button.pricesBitcoins.size() - 1, state == ButtonState.MAXED ? button.pricesBitcoins.size() - 1 : currentLevel));
		int price = SeasonStartSystem.resolveStartupMenuPrice(viewer, button.upgradeId, button.pricesBitcoins.get(index));
		boolean affordable = countBitcoins(viewer) >= price;
		int priceColor = state == ButtonState.MAXED
				? 0xBFAE89
				: (affordable ? 0xF0D39B : 0x8E98A3);

		if (!hasPack) {
			MutableComponent line = Component.empty();
			line.append(styledTooltipText(NO_PACK_COIN, priceColor, false, false));
			line.append(styledTooltipText(Integer.toString(price), priceColor, true, false, state == ButtonState.MAXED));
			return line;
		}

		MutableComponent line = Component.empty();
		line.append(styledTooltipText(TOOLTIP_ICON_COIN, 0xFFFFFF, false, true));
		line.append(styledTooltipText(Integer.toString(price), priceColor, true, true, state == ButtonState.MAXED));
		return line;
	}

	private static Component buildTooltipLine(
			String prefix,
			int prefixColor,
			String text,
			int textColor,
			boolean hasPack
	) {
		MutableComponent line = Component.empty();
		if (prefix != null && !prefix.isBlank()) {
			line.append(styledTooltipText(prefix + " ", prefixColor, false, hasPack));
		}
		line.append(styledTooltipText(text, textColor, false, hasPack));
		return line;
	}

	private static Component buildSpacerLine(boolean hasPack) {
		return styledTooltipText(" ", 0xFFFFFF, false, hasPack);
	}

	private static Component styledTooltipText(String text, int color, boolean bold, boolean hasPack) {
		return styledTooltipText(text, color, bold, hasPack, false);
	}

	private static Component styledTooltipText(String text, int color, boolean bold, boolean hasPack, boolean strikethrough) {
		return Component.literal(safeString(text)).withStyle(style -> {
			style = style.withColor(color).withItalic(false).withBold(bold).withStrikethrough(strikethrough);
			if (hasPack) {
				style = style.withFont(TOOLTIP_FONT);
			}
			return style;
		});
	}

	private static List<String> compactLocalizedLines(
			List<String> lines,
			Map<String, String> placeholders,
			int wrapWidth,
			int maxLines
	) {
		List<String> cleaned = new ArrayList<>();
		if (lines != null) {
			for (String line : lines) {
				String resolved = applyPlaceholders(safeString(line).trim(), placeholders);
				if (!resolved.isBlank()) {
					cleaned.add(resolved);
				}
			}
		}
		if (cleaned.isEmpty()) {
			return List.of();
		}
		return wrapText(String.join(" ", cleaned), wrapWidth, maxLines);
	}

	/**
	 * The card artwork is the sole presentation of live upgrade state.  Lore is
	 * deliberately restricted to a timeless description, including for older
	 * server configs that still contain the former status rows.
	 */
	private static List<String> staticDescriptionLines(List<String> lines) {
		if (lines == null || lines.isEmpty()) {
			return List.of();
		}
		List<String> staticLines = new ArrayList<>();
		for (String line : lines) {
			if (!isDynamicDescriptionLine(line)) {
				staticLines.add(line);
			}
		}
		return staticLines;
	}

	private static boolean isDynamicDescriptionLine(String line) {
		String normalized = safeString(line).trim().toLowerCase(Locale.ROOT);
		if (normalized.contains("%level%")
				|| normalized.contains("%next_level%")
				|| normalized.contains("%max_level%")
				|| normalized.contains("%cost%")
				|| normalized.contains("%bitcoins%")
				|| normalized.contains("%status%")) {
			return true;
		}
		return startsWithAny(normalized,
				"level:", "next level:", "max level:", "cost:", "price:", "balance:", "bitcoins:", "status:",
				"уровень:", "следующий уровень:", "максимальный уровень:", "цена:", "стоимость:", "баланс:", "биткоины:", "статус:",
				"рівень:", "наступний рівень:", "максимальний рівень:", "ціна:", "вартість:", "баланс:", "біткоїни:", "стан:",
				"レベル:", "次のレベル:", "最大レベル:", "コスト:", "価格:", "残高:", "ステータス:");
	}

	private static boolean startsWithAny(String value, String... prefixes) {
		for (String prefix : prefixes) {
			if (value.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	private static List<String> wrapText(String text, int maxWidth, int maxLines) {
		String normalized = safeString(text).replaceAll("\\s+", " ").trim();
		if (normalized.isEmpty()) {
			return List.of();
		}

		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : normalized.split(" ")) {
			if (current.isEmpty()) {
				current.append(word);
				continue;
			}
			if (current.length() + 1 + word.length() <= Math.max(8, maxWidth)) {
				current.append(' ').append(word);
				continue;
			}
			lines.add(current.toString());
			if (lines.size() >= maxLines) {
				return trimWrappedLines(lines, current.toString(), word, normalized);
			}
			current = new StringBuilder(word);
		}
		if (!current.isEmpty() && lines.size() < maxLines) {
			lines.add(current.toString());
		}
		return lines;
	}

	private static List<String> trimWrappedLines(
			List<String> lines,
			String overflowedLine,
			String currentWord,
			String originalText
	) {
		if (lines.isEmpty()) {
			return List.of(ellipsis(originalText));
		}
		int lastIndex = lines.size() - 1;
		lines.set(lastIndex, ellipsis(lines.get(lastIndex)));
		return lines;
	}

	private static String ellipsis(String value) {
		String trimmed = safeString(value).trim();
		if (trimmed.isEmpty() || trimmed.endsWith("...")) {
			return trimmed;
		}
		if (trimmed.endsWith(".")) {
			return trimmed.substring(0, trimmed.length() - 1) + "...";
		}
		return trimmed + "...";
	}

	private static int tooltipWrapWidth(ServerPlayer player, int regularWidth, int cjkWidth) {
		String locale = normalizeLocale(player);
		return locale.startsWith("ja") || locale.startsWith("ko") || locale.startsWith("zh") ? cjkWidth : regularWidth;
	}

	private static String buildRequirementSummary(ServerPlayer player, UpgradeUiConfig.ButtonConfig button) {
		List<String> parts = new ArrayList<>();
		for (UpgradeUiConfig.RequirementConfig requirement : button.requirements) {
			if (requirement == null || requirement.upgradeId == null || requirement.upgradeId.isBlank()) {
				continue;
			}
			parts.add(resolveUpgradeDisplayName(player, requirement.upgradeId));
		}
		for (UpgradeUiConfig.RequirementGroupConfig group : button.requirementGroups) {
			String summary = summarizeRequirementGroup(player, group);
			if (!summary.isBlank()) {
				parts.add(summary);
			}
		}
		return String.join("; ", parts);
	}

	private static String summarizeRequirementGroup(ServerPlayer player, UpgradeUiConfig.RequirementGroupConfig group) {
		if (group == null || group.requirements == null || group.requirements.isEmpty()) {
			return "";
		}

		List<String> names = new ArrayList<>();
		for (UpgradeUiConfig.RequirementConfig requirement : group.requirements) {
			if (requirement == null || requirement.upgradeId == null || requirement.upgradeId.isBlank()) {
				continue;
			}
			names.add(resolveUpgradeDisplayName(player, requirement.upgradeId));
		}
		if (names.isEmpty()) {
			return "";
		}

		if (UpgradeUiConfig.RequirementMode.AT_LEAST.id.equals(group.mode)) {
			return localizeTooltip(
					player,
					group.minSatisfiedRequirements + " of: " + String.join(", ", names),
					group.minSatisfiedRequirements + " из: " + String.join(", ", names),
					group.minSatisfiedRequirements + " з: " + String.join(", ", names),
					group.minSatisfiedRequirements + " 個: " + String.join("、", names),
					group.minSatisfiedRequirements + " изъ: " + String.join(", ", names)
			);
		}
		return String.join(", ", names);
	}

	private static String resolveUpgradeDisplayName(ServerPlayer player, String upgradeId) {
		if (upgradeId == null || upgradeId.isBlank()) {
			return "";
		}
		RaceAbilityConfig raceAbility = findRaceAbilityByUpgradeId(player, upgradeId);
		if (raceAbility != null && raceAbility.name != null && !raceAbility.name.isBlank()) {
			return raceAbility.name;
		}
		for (UpgradeUiConfig.ScreenConfig screen : UpgradeUiConfig.get().screens.values()) {
			if (screen == null || screen.buttons == null) {
				continue;
			}
			for (UpgradeUiConfig.ButtonConfig candidate : screen.buttons.values()) {
				if (candidate == null || !upgradeId.equals(candidate.upgradeId) || candidate.name == null || candidate.name.values == null) {
					continue;
				}
				String localized = resolveLocalizedText(player, candidate.name.values);
				if (!localized.isBlank()) {
					return localized;
				}
			}
		}
		return upgradeId.replace('_', ' ');
	}

	private static RaceAbilityConfig findRaceAbilityByUpgradeId(ServerPlayer player, String upgradeId) {
		String normalizedUpgradeId = safeString(upgradeId).trim();
		if (normalizedUpgradeId.isEmpty()) {
			return null;
		}

		Optional<RaceConfig.PlayerRaceConfig> playerRace = ServerRaceSystem.getRace(player);
		if (playerRace.isPresent()) {
			RaceAbilityConfig direct = findRaceAbilityByUpgradeId(playerRace.get(), normalizedUpgradeId);
			if (direct != null) {
				return direct;
			}
		}

		RaceConfig.ConfigData config = RaceConfig.get();
		if (config == null || config.races == null) {
			return null;
		}
		for (RaceConfig.PlayerRaceConfig race : config.races) {
			RaceAbilityConfig ability = findRaceAbilityByUpgradeId(race, normalizedUpgradeId);
			if (ability != null) {
				return ability;
			}
		}
		return null;
	}

	private static RaceAbilityConfig findRaceAbilityByUpgradeId(RaceConfig.PlayerRaceConfig race, String upgradeId) {
		if (race == null || upgradeId == null || upgradeId.isBlank()) {
			return null;
		}
		if (race.attack != null && upgradeId.equals(safeString(race.attack.abilityId).trim())) {
			return race.attack;
		}
		if (race.defense != null && upgradeId.equals(safeString(race.defense.abilityId).trim())) {
			return race.defense;
		}
		if (race.uniqueAbility != null && upgradeId.equals(safeString(race.uniqueAbility.abilityId).trim())) {
			return race.uniqueAbility;
		}
		if (race.shnyaga != null && upgradeId.equals(safeString(race.shnyaga.abilityId).trim())) {
			return race.shnyaga;
		}
		return null;
	}

	private static ItemStack createBaseStack(UpgradeUiConfig.IconConfig icon, boolean hasPack) {
		Item fallbackItem;
		if (hasPack) {
			fallbackItem = Items.PAPER;
		} else {
			Identifier itemId = Identifier.tryParse(icon.fallbackItem);
			fallbackItem = itemId == null ? Items.PAPER : BuiltInRegistries.ITEM.getOptional(itemId).orElse(Items.PAPER);
		}
		ItemStack stack = new ItemStack(fallbackItem, Math.max(1, Math.min(64, icon.count)));

		if (hasPack && icon.packModel != null && !icon.packModel.isBlank()) {
			Identifier modelId = Identifier.tryParse(icon.packModel);
			if (modelId != null) {
				stack.set(DataComponents.ITEM_MODEL, modelId);
			}
		}

		if (icon.foil) {
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}

		return stack;
	}

	private static Map<String, String> buildPlaceholders(
			ServerPlayer player,
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button
	) {
		Map<String, String> placeholders = new LinkedHashMap<>();
		int currentLevel = getUpgradeLevel(player, button.upgradeId);
		int maxLevel = button.pricesBitcoins == null ? 0 : button.pricesBitcoins.size();
		int cost = getCurrentCost(player, button);
		int bitcoins = countBitcoins(player);

		placeholders.put("%screen_id%", safeString(screenId));
		placeholders.put("%button_id%", safeString(buttonId));
		placeholders.put("%target_screen%", safeString(button.targetScreenId));
		placeholders.put("%upgrade_id%", safeString(button.upgradeId));
		placeholders.put("%level%", Integer.toString(currentLevel));
		placeholders.put("%next_level%", Integer.toString(Math.min(maxLevel, currentLevel + 1)));
		placeholders.put("%max_level%", Integer.toString(maxLevel));
		placeholders.put("%cost%", Integer.toString(cost));
		placeholders.put("%bitcoins%", Integer.toString(bitcoins));
		return placeholders;
	}

	private static ButtonState getButtonState(ServerPlayer player, UpgradeUiConfig.ButtonConfig button) {
		if (!meetsRequirements(player, button)) {
			return ButtonState.LOCKED;
		}
		if (UpgradeUiConfig.ButtonType.PURCHASE_UPGRADE.id.equals(button.type)
				&& getUpgradeLevel(player, button.upgradeId) >= button.pricesBitcoins.size()) {
			return ButtonState.MAXED;
		}
		return ButtonState.ACTIVE;
	}

	private static boolean handleScreenSlotClick(ServerPlayer player, String screenId, int slot, boolean hasPack) {
		Map.Entry<String, UpgradeUiConfig.ButtonConfig> matched = findMatchedButton(player, screenId, slot, hasPack);
		if (matched == null) {
			return false;
		}

		String buttonId = matched.getKey();
		UpgradeUiConfig.ButtonConfig button = matched.getValue();
		UpgradeUiConfig.ButtonType type = UpgradeUiConfig.ButtonType.byId(button.type);
		if (type == null) {
			return false;
		}
		SeasonStartSystem.onServerUpgradeButtonClicked(player, screenId, buttonId);
		boolean suppressClickSound = shouldSuppressClickSound(screenId, buttonId);

		ButtonState state = getButtonState(player, button);

		switch (type) {
			case NONE -> {
				playUiClick(player, false, suppressClickSound);
				return true;
			}
			case CLOSE -> {
				player.closeContainer();
				SeasonStartSystem.onServerUpgradeMenuClosed(player, screenId);
				playUiClick(player, true, suppressClickSound);
				return true;
			}
			case OPEN_SCREEN -> {
				if (state == ButtonState.LOCKED) {
					playUiClick(player, false, suppressClickSound);
					sendPlayerMessage(player, localizeSystem(player, "This section is locked.", "Этот раздел пока заблокирован."));
					return true;
				}
				if (button.targetScreenId == null || button.targetScreenId.isBlank()) {
					playUiClick(player, false, suppressClickSound);
					return true;
				}
				playUiClick(player, true, suppressClickSound);
				return openScreen(player, button.targetScreenId);
			}
			case PURCHASE_UPGRADE -> {
				return handlePurchaseClick(player, screenId, buttonId, button);
			}
			default -> {
				return false;
			}
		}
	}

	private static Map.Entry<String, UpgradeUiConfig.ButtonConfig> findMatchedButton(
			ServerPlayer viewer,
			String screenId,
			int slot,
			boolean hasPack
	) {
		UpgradeUiConfig.ScreenConfig screen = UpgradeUiConfig.get().screens.get(screenId);
		if (screen == null) {
			return null;
		}

		for (Map.Entry<String, UpgradeUiConfig.ButtonConfig> entry : resolveScreenButtons(viewer, screenId, screen).entrySet()) {
			UpgradeUiConfig.ButtonConfig button = entry.getValue();
			if (button != null
					&& button.enabled
					&& !isPureDecorButton(button)
					&& !shouldHideButtonVisual(screenId, entry.getKey())
					&& isWithinButtonHitbox(slot, screenId, entry.getKey(), button, hasPack)) {
				return entry;
			}
		}
		return null;
	}

	private static ItemStack buildLowerInventoryButtonVisual(
			ServerPlayer viewer,
			String screenId,
			int menuSlot,
			int topSlotCount,
			boolean hasPack
	) {
		Map.Entry<String, UpgradeUiConfig.ButtonConfig> matched = findMatchedButton(viewer, screenId, menuSlot, hasPack);
		if (matched == null) {
			return ItemStack.EMPTY;
		}

		UpgradeUiConfig.ButtonConfig button = matched.getValue();
		String buttonId = matched.getKey();
		int anchorSlot = getButtonAnchorSlot(screenId, buttonId, button, hasPack);
		if (anchorSlot < topSlotCount || !isWithinButtonHitbox(menuSlot, screenId, buttonId, button, hasPack)) {
			return ItemStack.EMPTY;
		}

		int visualSlot = getButtonVisualSlot(viewer, screenId, buttonId, button, hasPack);
		if (menuSlot == visualSlot) {
			return buildButtonStack(viewer, screenId, buttonId, button, hasPack);
		}
		if (hasPack && (Math.max(1, button.hitboxWidth) > 1 || Math.max(1, button.hitboxHeight) > 1)) {
			return buildButtonStack(viewer, screenId, buttonId, button, true, MENU_INVISIBLE_ICON);
		}
		return ItemStack.EMPTY;
	}

	private static boolean shouldSuppressClickSound(String screenId, String buttonId) {
		return "main".equals(screenId) && "balance".equals(buttonId);
	}

	private static boolean handlePurchaseClick(
			ServerPlayer player,
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button
	) {
		SeasonStartSystem.onServerUpgradePressed(player, screenId, button.upgradeId);
		maybeFlashDimensionPurchaseLockCursor(player, screenId, buttonId, button);

		if (!meetsRequirements(player, button)) {
			playPurchaseBlockedSound(player);
			sendPlayerMessage(player, localizeSystem(player, "This upgrade is locked.", "Это улучшение пока заблокировано."));
			return true;
		}

		int currentLevel = getUpgradeLevel(player, button.upgradeId);
		if (currentLevel >= button.pricesBitcoins.size()) {
			if (button.targetScreenId != null && !button.targetScreenId.isBlank()) {
				playUiClick(player, true);
				return openScreen(player, button.targetScreenId);
			}
			playUiClick(player, false);
			sendPlayerMessage(player, localizeSystem(player, "This upgrade is already maxed.", "Это улучшение уже прокачано до максимума."));
			return true;
		}

		int cost = getCurrentCost(player, button);
		if (!consumeBitcoins(player, cost)) {
			playPurchaseBlockedSound(player);
			sendPlayerMessage(player, localizeSystem(player, "Not enough bitcoins.", "Недостаточно биткоинов."));
			return true;
		}

		setUpgradeLevel(player.level().getServer(), player, button.upgradeId, currentLevel + 1);
		SeasonStartSystem.onServerUpgradePurchased(player, button.upgradeId);
		startEraProgressAnimation(player, button.upgradeId);
		startItProgressAnimation(player, button.upgradeId);
		playPurchaseSound(player);
		sendPlayerMessage(player, localizeSystem(player, "Upgrade purchased.", "Улучшение куплено."));

		if (button.closeAfterClick) {
			player.closeContainer();
		} else {
			openScreen(player, screenId);
		}

		return true;
	}

	private static boolean meetsRequirements(ServerPlayer player, UpgradeUiConfig.ButtonConfig button) {
		for (UpgradeUiConfig.RequirementConfig requirement : button.requirements) {
			if (requirement == null || requirement.upgradeId == null || requirement.upgradeId.isBlank()) {
				continue;
			}
			if (getUpgradeLevel(player, requirement.upgradeId) < Math.max(1, requirement.minLevel)) {
				return false;
			}
		}
		for (UpgradeUiConfig.RequirementGroupConfig group : button.requirementGroups) {
			if (!meetsRequirementGroup(player, group)) {
				return false;
			}
		}
		return true;
	}

	private static boolean meetsRequirementGroup(ServerPlayer player, UpgradeUiConfig.RequirementGroupConfig group) {
		if (group == null || group.requirements == null || group.requirements.isEmpty()) {
			return true;
		}

		int validRequirements = 0;
		int satisfiedRequirements = 0;
		for (UpgradeUiConfig.RequirementConfig requirement : group.requirements) {
			if (requirement == null || requirement.upgradeId == null || requirement.upgradeId.isBlank()) {
				continue;
			}

			validRequirements++;
			if (getUpgradeLevel(player, requirement.upgradeId) >= Math.max(1, requirement.minLevel)) {
				satisfiedRequirements++;
			}
		}

		if (validRequirements <= 0) {
			return true;
		}

		UpgradeUiConfig.RequirementMode mode = UpgradeUiConfig.RequirementMode.byId(group.mode);
		if (mode == UpgradeUiConfig.RequirementMode.AT_LEAST) {
			int required = Math.max(1, Math.min(validRequirements, group.minSatisfiedRequirements));
			return satisfiedRequirements >= required;
		}
		return satisfiedRequirements >= validRequirements;
	}

	private static int getCurrentCost(ServerPlayer player, UpgradeUiConfig.ButtonConfig button) {
		if (!UpgradeUiConfig.ButtonType.PURCHASE_UPGRADE.id.equals(button.type)
				|| button.pricesBitcoins == null
				|| button.pricesBitcoins.isEmpty()) {
			return 0;
		}
		int currentLevel = getUpgradeLevel(player, button.upgradeId);
		if (currentLevel < 0 || currentLevel >= button.pricesBitcoins.size()) {
			return 0;
		}
		return SeasonStartSystem.resolveStartupMenuPrice(player, button.upgradeId, button.pricesBitcoins.get(currentLevel));
	}

	private static int countBitcoins(Player player) {
		if (player == null) {
			return 0;
		}

		int total = 0;
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			total += countBitcoinsInStack(inventory.getItem(slot));
		}
		return total;
	}

	public static int getTabBitcoinBalance(Player player) {
		return countBitcoins(player);
	}

	private static int countBitcoinsInStack(ItemStack stack) {
		return countBitcoinsInStack(stack, 0);
	}

	private static int countBitcoinsInStack(ItemStack stack, int depth) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		int total = stack.is(ModItems.BITCOIN) ? stack.getCount() : 0;
		if (depth >= BITCOIN_CONTAINER_SCAN_MAX_DEPTH) {
			return total;
		}
		ItemContainerContents shulkerContents = shulkerContents(stack);
		if (shulkerContents != null) {
			for (ItemStack contained : shulkerContents.nonEmptyItems()) {
				total += countBitcoinsInStack(contained, depth + 1);
			}
		}
		BundleContents bundleContents = bundleContents(stack);
		if (bundleContents != null) {
			for (ItemStack contained : bundleContents.itemsCopy()) {
				total += countBitcoinsInStack(contained, depth + 1);
			}
		}
		return total;
	}

	private static boolean consumeBitcoins(Player player, int amount) {
		if (amount <= 0) {
			return true;
		}
		if (countBitcoins(player) < amount) {
			return false;
		}

		int remaining = amount;
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
			remaining -= consumeBitcoinsFromStack(inventory.getItem(slot), remaining, 0);
			if (inventory.getItem(slot).isEmpty()) {
				inventory.setItem(slot, ItemStack.EMPTY);
			}
		}

		inventory.setChanged();
		return remaining <= 0;
	}

	private static int consumeBitcoinsFromStack(ItemStack stack, int amount, int depth) {
		if (amount <= 0 || stack == null || stack.isEmpty()) {
			return 0;
		}
		if (stack.is(ModItems.BITCOIN)) {
			int removed = Math.min(amount, stack.getCount());
			stack.shrink(removed);
			return removed;
		}
		if (depth >= BITCOIN_CONTAINER_SCAN_MAX_DEPTH) {
			return 0;
		}
		int removed = consumeBitcoinsFromShulker(stack, amount, depth);
		if (removed >= amount) {
			return removed;
		}
		return removed + consumeBitcoinsFromBundle(stack, amount - removed, depth);
	}

	private static int consumeBitcoinsFromShulker(ItemStack stack, int amount, int depth) {
		if (amount <= 0 || stack == null || stack.isEmpty()) {
			return 0;
		}
		ItemContainerContents contents = shulkerContents(stack);
		if (contents == null) {
			return 0;
		}
		List<ItemStack> items = contents.stream().toList();
		if (items.isEmpty()) {
			return 0;
		}
		List<ItemStack> updatedItems = new ArrayList<>(items.size());
		for (ItemStack item : items) {
			updatedItems.add(item == null ? ItemStack.EMPTY : item.copy());
		}
		int removed = 0;
		for (int index = 0; index < updatedItems.size() && removed < amount; index++) {
			ItemStack contained = updatedItems.get(index);
			if (contained == null || contained.isEmpty()) {
				continue;
			}
			int consume = consumeBitcoinsFromStack(contained, amount - removed, depth + 1);
			if (contained.isEmpty()) {
				updatedItems.set(index, ItemStack.EMPTY);
			}
			removed += consume;
		}
		if (removed > 0) {
			stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(updatedItems));
		}
		return removed;
	}

	private static int consumeBitcoinsFromBundle(ItemStack stack, int amount, int depth) {
		if (amount <= 0 || stack == null || stack.isEmpty()) {
			return 0;
		}
		BundleContents contents = bundleContents(stack);
		if (contents == null) {
			return 0;
		}
		List<ItemStack> updatedItems = new ArrayList<>();
		for (ItemStack item : contents.itemsCopy()) {
			updatedItems.add(item == null ? ItemStack.EMPTY : item.copy());
		}
		if (updatedItems.isEmpty()) {
			return 0;
		}
		int removed = 0;
		for (int index = 0; index < updatedItems.size() && removed < amount; index++) {
			ItemStack contained = updatedItems.get(index);
			if (contained == null || contained.isEmpty()) {
				continue;
			}
			int consume = consumeBitcoinsFromStack(contained, amount - removed, depth + 1);
			if (contained.isEmpty()) {
				updatedItems.set(index, ItemStack.EMPTY);
			}
			removed += consume;
		}
		if (removed > 0) {
			BundleContents.Mutable mutable = new BundleContents.Mutable(contents);
			mutable.clearItems();
			for (ItemStack updated : updatedItems) {
				if (updated != null && !updated.isEmpty()) {
					mutable.tryInsert(updated);
				}
			}
			stack.set(DataComponents.BUNDLE_CONTENTS, mutable.toImmutable());
		}
		return removed;
	}

	private static ItemContainerContents shulkerContents(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
			return null;
		}
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		return contents != null && contents != ItemContainerContents.EMPTY ? contents : null;
	}

	private static BundleContents bundleContents(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !stack.has(DataComponents.BUNDLE_CONTENTS)) {
			return null;
		}
		BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);
		return contents != null && !contents.isEmpty() ? contents : null;
	}

	private static boolean isWithinButtonHitbox(
			int slot,
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button,
			boolean hasPack
	) {
		if (button == null || slot < 0) {
			return false;
		}

		int anchorSlot = getButtonAnchorSlot(screenId, buttonId, button, hasPack);
		int width = Math.max(1, button.hitboxWidth);
		int height = Math.max(1, button.hitboxHeight);
		int startColumn = anchorSlot % 9;
		int startRow = anchorSlot / 9;
		int targetColumn = slot % 9;
		int targetRow = slot / 9;

		return targetColumn >= startColumn
				&& targetColumn < startColumn + width
				&& targetRow >= startRow
				&& targetRow < startRow + height;
	}

	private static boolean isPureDecorButton(UpgradeUiConfig.ButtonConfig button) {
		if (button == null || !UpgradeUiConfig.ButtonType.NONE.id.equals(button.type)) {
			return false;
		}
		if (button.name != null && button.name.values != null) {
			for (String value : button.name.values.values()) {
				if (value != null && !value.isBlank()) {
					return false;
				}
			}
		}
		if (button.lore != null && button.lore.values != null) {
			for (List<String> lines : button.lore.values.values()) {
				if (lines == null) {
					continue;
				}
				for (String line : lines) {
					if (line != null && !line.isBlank()) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private static boolean shouldHideButtonVisual(String screenId, String buttonId) {
		return "balance".equals(buttonId);
	}

	private static void hideLowerInventoryVisuals(ServerPlayer player, AbstractContainerMenu menu) {
		sendLowerInventoryVisuals(player, menu, true);
		syncHeldEquipmentVisuals(player, true);
	}

	private static void restoreLowerInventoryVisuals(ServerPlayer player, AbstractContainerMenu menu) {
		if (player != null) {
			AbstractContainerMenu targetMenu = player.containerMenu;
			if (targetMenu == null || targetMenu == menu) {
				targetMenu = player.inventoryMenu;
			}
			if (targetMenu != null) {
				sendLowerInventoryVisuals(player, targetMenu, false);
			}
		}
		syncHeldEquipmentVisuals(player, false);
	}

	private static void sendLowerInventoryVisuals(ServerPlayer player, AbstractContainerMenu menu, boolean hide) {
		if (player == null || menu == null) {
			return;
		}

		Inventory inventory = player.getInventory();
		PacketContext.NotNullWithPlayer context = PacketContext.create(player);
		int stateId = menu.incrementStateId();
		for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
			Slot slot = menu.getSlot(menuSlot);
			if (slot.container != inventory) {
				continue;
			}

			int inventorySlot = slot.getContainerSlot();
			ItemStack visual;
			if (hide) {
				visual = ItemStack.EMPTY;
				if (menu instanceof UpgradeMenu upgradeMenu) {
					ItemStack buttonVisual = buildLowerInventoryButtonVisual(
							upgradeMenu.viewer,
							upgradeMenu.screenId,
							menuSlot,
							upgradeMenu.topSlotCount,
							upgradeMenu.hasPack
					);
					if (!buttonVisual.isEmpty()) {
						visual = buttonVisual;
					}
				}
			} else {
				visual = inventory.getItem(inventorySlot).copy();
			}

			player.connection.send(new ClientboundContainerSetSlotPacket(
					menu.containerId,
					stateId,
					menuSlot,
					toClientVisualStack(visual, context)
			));
		}
	}

	private static void sendTopMenuVisuals(ServerPlayer player, UpgradeMenu menu) {
		if (player == null || menu == null) {
			return;
		}

		Inventory inventory = player.getInventory();
		PacketContext.NotNullWithPlayer context = PacketContext.create(player);
		int stateId = menu.incrementStateId();
		for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
			Slot slot = menu.getSlot(menuSlot);
			if (slot.container == inventory) {
				continue;
			}

			player.connection.send(new ClientboundContainerSetSlotPacket(
					menu.containerId,
					stateId,
					menuSlot,
					toClientVisualStack(slot.getItem().copy(), context)
			));
		}
	}

	private static void syncHeldEquipmentVisuals(ServerPlayer player, boolean hide) {
		if (player == null) {
			return;
		}

		PacketContext.NotNullWithPlayer context = PacketContext.create(player);
		ItemStack mainHand = hide ? ItemStack.EMPTY : toClientVisualStack(player.getMainHandItem().copy(), context);
		ItemStack offHand = hide ? ItemStack.EMPTY : toClientVisualStack(player.getOffhandItem().copy(), context);
		player.connection.send(new ClientboundSetEquipmentPacket(
				player.getId(),
				List.of(
						Pair.of(EquipmentSlot.MAINHAND, mainHand),
						Pair.of(EquipmentSlot.OFFHAND, offHand)
				)
		));
	}

	private static ItemStack toClientVisualStack(ItemStack stack, PacketContext.NotNullWithPlayer context) {
		if (stack == null || stack.isEmpty()) {
			return ItemStack.EMPTY;
		}

		ItemStack clientStack = PolymerItemUtils.getClientItemStack(stack, context);
		return clientStack.isEmpty() ? stack.copy() : clientStack.copy();
	}

	private static Component buildTitle(
			ServerPlayer player,
			String screenId,
			boolean hasPack,
			UpgradeUiConfig.ScreenConfig screen,
			UpgradeUiConfig.ThemeConfig theme
	) {
		String localizedTitle = resolveLocalizedText(player, screen.title.values);
		if (!hasPack) {
			if ("main".equals(screenId)) {
				return Component.literal(buildNoPackMainTitle(player, localizedTitle));
			}
			return Component.literal(localizedTitle);
		}

		MutableComponent title = Component.empty();
		if (theme.packTitlePrefix != null && !theme.packTitlePrefix.isBlank()) {
			title.append(packStyledLiteral(theme.packTitlePrefix));
		}
		if ("main".equals(screenId)) {
			title.append(packStyledLiteral(buildOverlayGlyph(mainScreenLogoGlyph(player, currentGameTime(player)), MAIN_SCREEN_OVERLAY_GLYPH_ADVANCE)));
		}
		if ("eras".equals(screenId)) {
			title.append(packStyledLiteral(buildOverlayGlyph(erasBarGlyph(player, currentGameTime(player)), ERAS_OVERLAY_GLYPH_ADVANCE)));
		}
		if ("it_hub".equals(screenId)) {
			title.append(packStyledLiteral(buildOverlayGlyph(itBarGlyph(player, currentGameTime(player)), IT_OVERLAY_GLYPH_ADVANCE)));
		}
		if (usesMainStyleBalance(screenId)) {
			title.append(packStyledLiteral(TITLE_OVERLAY_RESET));
			title.append(packStyledLiteral(buildHorizontalAdvance(balanceStartX(screenId, countBitcoins(player)))));
			title.append(packStyledLiteral(toPackDigitString(countBitcoins(player))));
		}
		if (!screen.hideTitleTextWhenPack && !localizedTitle.isBlank()) {
			if (!title.getString().isBlank()) {
				title.append(packStyledLiteral(" "));
			}
			title.append(packStyledLiteral(localizedTitle));
		}
		if (theme.packTitleSuffix != null && !theme.packTitleSuffix.isBlank()) {
			title.append(packStyledLiteral(theme.packTitleSuffix));
		}
		return title.getString().isBlank() ? Component.literal(localizedTitle) : title;
	}

	private static String buildNoPackMainTitle(ServerPlayer player, String localizedTitle) {
		String baseTitle = safeString(localizedTitle).isBlank() ? "Server" : localizedTitle;
		String balanceText = NO_PACK_COIN + " " + countBitcoins(player);
		int targetColumns = noPackTitleColumns(player);
		int spaces = Math.max(2, targetColumns - baseTitle.length() - balanceText.length());
		return baseTitle + " ".repeat(spaces) + balanceText;
	}

	private static int noPackTitleColumns(ServerPlayer player) {
		String locale = normalizeLocale(player);
		if (locale.startsWith("ja") || locale.startsWith("ko") || locale.startsWith("zh")) {
			return 29;
		}
		return 35;
	}

	private static MutableComponent packStyledLiteral(String value) {
		return Component.literal(value).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false));
	}

	private static String buildOverlayGlyph(String glyph, int glyphAdvance) {
		int compensation = TITLE_OVERLAY_TARGET_ADVANCE - TITLE_OVERLAY_SHIFT_ADVANCE - glyphAdvance;
		return TITLE_OVERLAY_RESET + TITLE_OVERLAY_SHIFT + glyph + buildHorizontalAdvance(compensation);
	}

	private static String[] createGlyphSequence(int baseCodePoint, int count) {
		String[] glyphs = new String[count];
		for (int index = 0; index < count; index++) {
			glyphs[index] = String.valueOf((char) (baseCodePoint + index));
		}
		return glyphs;
	}

	private static UpgradeUiConfig.IconConfig createTransientIcon(String fallbackItem, String packModel, boolean foil) {
		UpgradeUiConfig.IconConfig icon = new UpgradeUiConfig.IconConfig();
		icon.fallbackItem = fallbackItem;
		icon.packModel = packModel;
		icon.foil = foil;
		return icon;
	}

	private static boolean usesMainStyleBalance(String screenId) {
		return true;
	}

	private static int balanceStartX(String screenId, int bitcoinCount) {
		int centerX = "eras".equals(screenId) ? ERAS_BALANCE_CENTER_X : MAIN_BALANCE_CENTER_X;
		if (!"main".equals(screenId)) {
			centerX += 1;
		}
		int digits = Integer.toString(Math.max(0, bitcoinCount)).length();
		int width = digits * BALANCE_DIGIT_WIDTH;
		return Math.max(0, centerX - (width / 2));
	}

	private static int chooseMainScreenVariant(ServerPlayer player) {
		if (player == null) {
			return MAIN_SCREEN_VARIANT_GLITCH;
		}
		if (player.getRandom().nextDouble() < MAIN_SCREEN_ARCHIVE_VARIANT_CHANCE) {
			return MAIN_SCREEN_VARIANT_ARCHIVE;
		}
		return switch (player.getRandom().nextInt(3)) {
			case 1 -> MAIN_SCREEN_VARIANT_DVD;
			case 2 -> MAIN_SCREEN_VARIANT_SPIN;
			default -> MAIN_SCREEN_VARIANT_GLITCH;
		};
	}

	private static int mainScreenVariant(ServerPlayer player) {
		if (player == null) {
			return MAIN_SCREEN_VARIANT_GLITCH;
		}
		return MAIN_SCREEN_LOGO_VARIANTS.getOrDefault(player.getUUID(), MAIN_SCREEN_VARIANT_GLITCH);
	}

	private static String mainScreenLogoGlyph(ServerPlayer player, long gameTime) {
		int variant = mainScreenVariant(player);
		if (variant == MAIN_SCREEN_VARIANT_DVD) {
			int frame = (int) Math.floorMod(gameTime / MAIN_SCREEN_DVD_FRAME_TICKS, MAIN_SCREEN_DVD_FRAME_COUNT);
			return MAIN_SCREEN_DVD_GLYPHS[frame];
		}
		if (variant == MAIN_SCREEN_VARIANT_SPIN) {
			int frame = (int) Math.floorMod(gameTime / MAIN_SCREEN_SPIN_FRAME_TICKS, MAIN_SCREEN_SPIN_FRAME_COUNT);
			return MAIN_SCREEN_SPIN_GLYPHS[frame];
		}
		if (variant == MAIN_SCREEN_VARIANT_ARCHIVE) {
			int frame = (int) Math.floorMod(gameTime / MAIN_SCREEN_ARCHIVE_FRAME_TICKS, MAIN_SCREEN_ARCHIVE_FRAME_COUNT);
			return MAIN_SCREEN_ARCHIVE_GLYPHS[frame];
		}

		int frame = (int) Math.floorMod(gameTime / MAIN_SCREEN_LOGO_FRAME_TICKS, MAIN_SCREEN_LOGO_FRAME_COUNT);
		return MAIN_SCREEN_LOGO_GLYPHS[frame];
	}

	private static String erasBarGlyph(ServerPlayer player, long gameTime) {
		if (player != null) {
			EraProgressAnimation animation = ERAS_PROGRESS_ANIMATIONS.get(player.getUUID());
			if (animation != null) {
				if (gameTime > animation.endTick()) {
					ERAS_PROGRESS_ANIMATIONS.remove(player.getUUID());
				} else {
					int step = (int) Math.min(
							animation.totalSteps(),
							Math.max(0L, (gameTime - animation.startTick()) / ERAS_PROGRESS_FRAME_TICKS)
					);
					int frameIndex = animation.startFrameIndex() + step;
					return ERAS_PROGRESS_GLYPHS[Math.max(0, Math.min(ERAS_PROGRESS_GLYPHS.length - 1, frameIndex))];
				}
			}
		}
		int stage = erasProgressStage(player);
		return ERAS_AVAILABLE_GLYPHS[Math.max(0, Math.min(ERAS_AVAILABLE_GLYPHS.length - 1, stage))];
	}

	private static String itBarGlyph(ServerPlayer player, long gameTime) {
		if (player != null) {
			ItProgressAnimation animation = IT_PROGRESS_ANIMATIONS.get(player.getUUID());
			if (animation != null) {
				if (gameTime > animation.endTick()) {
					IT_PROGRESS_ANIMATIONS.remove(player.getUUID());
				} else {
					int step = (int) Math.min(
							animation.totalSteps(),
							Math.max(0L, (gameTime - animation.startTick()) / IT_PROGRESS_FRAME_TICKS)
					);
					int frameIndex = animation.startFrameIndex() + step;
					return IT_PROGRESS_GLYPHS[Math.max(0, Math.min(IT_PROGRESS_GLYPHS.length - 1, frameIndex))];
				}
			}
		}
		int state = itProgressState(player);
		return IT_AVAILABLE_GLYPHS[Math.max(0, Math.min(IT_AVAILABLE_GLYPHS.length - 1, state))];
	}

	private static int itProgressState(ServerPlayer player) {
		if (player == null || !hasUpgrade(player, IT_CAMERA_UPGRADE_ID)) {
			return 0;
		}
		boolean microphone = hasUpgrade(player, IT_MICROPHONE_UPGRADE_ID);
		boolean speaker = hasUpgrade(player, IT_SPEAKER_UPGRADE_ID);
		if (!microphone && !speaker) {
			return 1;
		}
		if (microphone && !speaker) {
			return 2;
		}
		if (!microphone) {
			return 3;
		}
		if (!hasUpgrade(player, IT_SCREEN_UPGRADE_ID)) {
			return 4;
		}
		if (!hasUpgrade(player, IT_DRONE_SCOUT_UPGRADE_ID)) {
			return 5;
		}
		if (!hasUpgrade(player, IT_BLUETOOTH_ADAPTER_UPGRADE_ID)) {
			return 6;
		}
		return 7;
	}

	private static int erasProgressStage(ServerPlayer player) {
		return erasProgressStageForPurchasedCount(purchasedEraCount(player));
	}

	private static int erasProgressStageForPurchasedCount(int purchasedCount) {
		return Math.max(0, Math.min(ERAS_PURCHASE_STAGE_COUNT, purchasedCount));
	}

	private static int purchasedEraCount(ServerPlayer player) {
		if (player == null) {
			return 0;
		}

		int count = 0;
		if (getUpgradeLevel(player, "era_stone") >= 1) {
			count++;
		} else {
			return count;
		}
		if (getUpgradeLevel(player, "era_copper") >= 1) {
			count++;
		} else {
			return count;
		}
		if (getUpgradeLevel(player, "era_iron_gold") >= 1) {
			count++;
		} else {
			return count;
		}
		if (getUpgradeLevel(player, "era_diamond") >= 1) {
			count++;
		} else {
			return count;
		}
		if (getUpgradeLevel(player, "era_netherite") >= 1) {
			count++;
		}
		return count;
	}

	private static boolean isEraLockSlot(ServerPlayer player, String upgradeId) {
		return safeString(upgradeId).equals(nextUnavailableEraUpgradeId(player));
	}

	private static String nextUnavailableEraUpgradeId(ServerPlayer player) {
		return switch (purchasedEraCount(player)) {
			case 0 -> "era_stone";
			case 1 -> "era_copper";
			case 2 -> "era_iron_gold";
			case 3 -> "era_diamond";
			case 4 -> "era_netherite";
			default -> "";
		};
	}

	private static UpgradeUiConfig.ThemeConfig resolveTheme(UpgradeUiConfig.ConfigData config, UpgradeUiConfig.ScreenConfig screen) {
		if (config == null || screen == null) {
			return UpgradeUiConfig.ThemeConfig.defaultTheme();
		}
		UpgradeUiConfig.ThemeConfig theme = config.themes.get(screen.theme);
		return theme == null || !theme.enabled ? UpgradeUiConfig.ThemeConfig.defaultTheme() : theme;
	}

	private static long currentGameTime(ServerPlayer player) {
		return player == null ? 0L : player.level().getGameTime();
	}

	private static String erasTitleSignature(ServerPlayer player, long gameTime) {
		return erasBarGlyph(player, gameTime) + "|" + countBitcoins(player);
	}

	private static String itTitleSignature(ServerPlayer player, long gameTime) {
		return itBarGlyph(player, gameTime) + "|" + countBitcoins(player);
	}

	private static String mainTitleSignature(ServerPlayer player, long gameTime) {
		return mainScreenVariant(player) + "|" + mainScreenLogoGlyph(player, gameTime) + "|" + countBitcoins(player);
	}

	private static void startEraProgressAnimation(ServerPlayer player, String upgradeId) {
		if (player == null || !isEraUpgrade(upgradeId)) {
			return;
		}

		int stage = erasProgressStage(player);
		if (stage <= 0) {
			ERAS_PROGRESS_ANIMATIONS.remove(player.getUUID());
			return;
		}

		int startFrame = Math.max(0, (stage - 1) * ERAS_PROGRESS_FRAMES_PER_STAGE);
		int endFrame = Math.min(ERAS_PROGRESS_FRAME_COUNT - 1, startFrame + ERAS_PROGRESS_FRAMES_PER_STAGE - 1);
		if (startFrame >= endFrame) {
			ERAS_PROGRESS_ANIMATIONS.remove(player.getUUID());
			return;
		}

		ERAS_PROGRESS_ANIMATIONS.put(
				player.getUUID(),
				new EraProgressAnimation(startFrame, endFrame, currentGameTime(player))
		);
		ERAS_TITLE_SIGNATURES.remove(player.getUUID());
	}

	private static boolean isEraUpgrade(String upgradeId) {
		return switch (safeString(upgradeId)) {
			case "era_stone", "era_copper", "era_iron_gold", "era_diamond", "era_netherite" -> true;
			default -> false;
		};
	}

	private static void startItProgressAnimation(ServerPlayer player, String upgradeId) {
		if (player == null) {
			return;
		}

		int transition = switch (safeString(upgradeId)) {
			case IT_CAMERA_UPGRADE_ID -> 0;
			case IT_MICROPHONE_UPGRADE_ID -> hasUpgrade(player, IT_SPEAKER_UPGRADE_ID) ? 4 : 1;
			case IT_SPEAKER_UPGRADE_ID -> hasUpgrade(player, IT_MICROPHONE_UPGRADE_ID) ? 3 : 2;
			case IT_SCREEN_UPGRADE_ID -> 5;
			case IT_DRONE_SCOUT_UPGRADE_ID -> 6;
			case IT_BLUETOOTH_ADAPTER_UPGRADE_ID -> 7;
			default -> -1;
		};
		if (transition < 0) {
			return;
		}

		int startFrame = transition * IT_PROGRESS_FRAMES_PER_TRANSITION;
		int endFrame = Math.min(IT_PROGRESS_FRAME_COUNT - 1, startFrame + IT_PROGRESS_FRAMES_PER_TRANSITION - 1);
		IT_PROGRESS_ANIMATIONS.put(
				player.getUUID(),
				new ItProgressAnimation(startFrame, endFrame, currentGameTime(player))
		);
		IT_TITLE_SIGNATURES.remove(player.getUUID());
	}

	private static void maybeFlashDimensionPurchaseLockCursor(
			ServerPlayer player,
			String screenId,
			String buttonId,
			UpgradeUiConfig.ButtonConfig button
	) {
		if (player == null
				|| player.containerMenu == null
				|| !player.containerMenu.getCarried().isEmpty()
				|| !shouldUseCornerPurchaseLockVisual(player, screenId, buttonId, button, PolymerResourcePackUtils.hasMainPack(player))) {
			return;
		}

		PacketContext.NotNullWithPlayer context = PacketContext.create(player);
		ItemStack visualLock = toClientVisualStack(createBaseStack(MENU_LOCK_ICON, true), context);
		player.connection.send(new ClientboundSetCursorItemPacket(visualLock));
		PENDING_LOCK_CURSOR_RESTORES.put(player.getUUID(), MENU_LOCK_CURSOR_FLASH_TICKS);
	}

	private static void tickAnimatedErasTitles(MinecraftServer server) {
		if (server == null) {
			return;
		}

		UpgradeUiConfig.ConfigData config = UpgradeUiConfig.get();
		for (ServerPlayer player : AccountAuthSystem.authenticatedPlayers(server)) {
			UUID playerId = player.getUUID();
			tickPendingLockCursorRestore(player);
			if (!(player.containerMenu instanceof UpgradeMenu menu)) {
				PENDING_MENU_VISUAL_RESYNCS.remove(playerId);
				ERAS_TITLE_SIGNATURES.remove(player.getUUID());
				ERAS_PROGRESS_ANIMATIONS.remove(player.getUUID());
				IT_TITLE_SIGNATURES.remove(playerId);
				IT_PROGRESS_ANIMATIONS.remove(playerId);
				MAIN_TITLE_SIGNATURES.remove(playerId);
				MAIN_SCREEN_LOGO_VARIANTS.remove(playerId);
				continue;
			}

			Integer pendingVisualResyncs = PENDING_MENU_VISUAL_RESYNCS.get(playerId);
			if (pendingVisualResyncs != null && pendingVisualResyncs > 0) {
				hideLowerInventoryVisuals(player, menu);
				if (pendingVisualResyncs <= 1) {
					PENDING_MENU_VISUAL_RESYNCS.remove(playerId);
				} else {
					PENDING_MENU_VISUAL_RESYNCS.put(playerId, pendingVisualResyncs - 1);
				}
			}

			if ("main".equals(menu.screenId)) {
				ERAS_TITLE_SIGNATURES.remove(playerId);
				ERAS_PROGRESS_ANIMATIONS.remove(playerId);
				IT_TITLE_SIGNATURES.remove(playerId);
				IT_PROGRESS_ANIMATIONS.remove(playerId);
				if (!PolymerResourcePackUtils.hasMainPack(player)) {
					MAIN_TITLE_SIGNATURES.remove(playerId);
					continue;
				}

				UpgradeUiConfig.ScreenConfig screen = config.screens.get(menu.screenId);
				if (screen == null || !screen.enabled) {
					continue;
				}

				long gameTime = currentGameTime(player);
				String signature = mainTitleSignature(player, gameTime);
				if (signature.equals(MAIN_TITLE_SIGNATURES.get(playerId))) {
					continue;
				}

				Component title = buildTitle(player, menu.screenId, true, screen, resolveTheme(config, screen));
				player.connection.send(new ClientboundOpenScreenPacket(menu.containerId, menu.getType(), title));
				resyncMenuAfterTitleRefresh(player, menu);
				MAIN_TITLE_SIGNATURES.put(playerId, signature);
				continue;
			}

			MAIN_TITLE_SIGNATURES.remove(playerId);
			MAIN_SCREEN_LOGO_VARIANTS.remove(playerId);
			if ("it_hub".equals(menu.screenId)) {
				ERAS_TITLE_SIGNATURES.remove(playerId);
				ERAS_PROGRESS_ANIMATIONS.remove(playerId);
				if (!menu.hasPack || !PolymerResourcePackUtils.hasMainPack(player)) {
					IT_TITLE_SIGNATURES.remove(playerId);
					IT_PROGRESS_ANIMATIONS.remove(playerId);
					continue;
				}

				UpgradeUiConfig.ScreenConfig screen = config.screens.get(menu.screenId);
				if (screen == null || !screen.enabled) {
					continue;
				}

				long gameTime = currentGameTime(player);
				String signature = itTitleSignature(player, gameTime);
				if (signature.equals(IT_TITLE_SIGNATURES.get(playerId))) {
					continue;
				}

				Component title = buildTitle(player, menu.screenId, true, screen, resolveTheme(config, screen));
				player.connection.send(new ClientboundOpenScreenPacket(menu.containerId, menu.getType(), title));
				resyncMenuAfterTitleRefresh(player, menu);
				IT_TITLE_SIGNATURES.put(playerId, signature);
				continue;
			}

			IT_TITLE_SIGNATURES.remove(playerId);
			IT_PROGRESS_ANIMATIONS.remove(playerId);
			if (!"eras".equals(menu.screenId)) {
				ERAS_TITLE_SIGNATURES.remove(playerId);
				ERAS_PROGRESS_ANIMATIONS.remove(playerId);
				continue;
			}
			if (!menu.hasPack || !PolymerResourcePackUtils.hasMainPack(player)) {
				ERAS_TITLE_SIGNATURES.remove(playerId);
				ERAS_PROGRESS_ANIMATIONS.remove(playerId);
				continue;
			}

			UpgradeUiConfig.ScreenConfig screen = config.screens.get(menu.screenId);
			if (screen == null || !screen.enabled) {
				continue;
			}

			long gameTime = currentGameTime(player);
			String signature = erasTitleSignature(player, gameTime);
			if (signature.equals(ERAS_TITLE_SIGNATURES.get(player.getUUID()))) {
				continue;
			}

			Component title = buildTitle(player, menu.screenId, true, screen, resolveTheme(config, screen));
			player.connection.send(new ClientboundOpenScreenPacket(menu.containerId, menu.getType(), title));
			resyncMenuAfterTitleRefresh(player, menu);
			ERAS_TITLE_SIGNATURES.put(playerId, signature);
		}
	}

	private static void tickPendingLockCursorRestore(ServerPlayer player) {
		if (player == null) {
			return;
		}

		UUID playerId = player.getUUID();
		Integer pendingTicks = PENDING_LOCK_CURSOR_RESTORES.get(playerId);
		if (pendingTicks == null) {
			return;
		}

		if (pendingTicks <= 1) {
			restoreCursorVisual(player);
			PENDING_LOCK_CURSOR_RESTORES.remove(playerId);
			return;
		}

		PENDING_LOCK_CURSOR_RESTORES.put(playerId, pendingTicks - 1);
	}

	private static void restoreCursorVisual(ServerPlayer player) {
		if (player == null || player.containerMenu == null) {
			return;
		}

		PacketContext.NotNullWithPlayer context = PacketContext.create(player);
		ItemStack carried = toClientVisualStack(player.containerMenu.getCarried().copy(), context);
		player.connection.send(new ClientboundSetCursorItemPacket(carried));
	}

	private static void resyncMenuAfterTitleRefresh(ServerPlayer player, UpgradeMenu menu) {
		if (player == null || menu == null) {
			return;
		}

		// Refreshing the title with a new open-screen packet can clear the client-side
		// visual stacks, so we resend only the intended visuals without revealing the
		// real lower inventory contents.
		sendTopMenuVisuals(player, menu);
		hideLowerInventoryVisuals(player, menu);
		PENDING_MENU_VISUAL_RESYNCS.put(player.getUUID(), MENU_VISUAL_RESYNC_TICKS);
	}

	private record EraProgressAnimation(int startFrameIndex, int endFrameIndex, long startTick) {
		private int totalSteps() {
			return Math.max(0, this.endFrameIndex - this.startFrameIndex);
		}

		private long endTick() {
			return this.startTick + ((long) totalSteps() * ERAS_PROGRESS_FRAME_TICKS);
		}
	}

	private record ItProgressAnimation(int startFrameIndex, int endFrameIndex, long startTick) {
		private int totalSteps() {
			return Math.max(0, this.endFrameIndex - this.startFrameIndex);
		}

		private long endTick() {
			return this.startTick + ((long) totalSteps() * IT_PROGRESS_FRAME_TICKS);
		}
	}

	private static String buildHorizontalAdvance(int pixels) {
		if (pixels == 0) {
			return "";
		}

		int remaining = pixels;
		StringBuilder result = new StringBuilder();
		int[] values = remaining > 0
				? new int[]{64, 32, 16, 8, 4, 2, 1}
				: new int[]{-64, -32, -16, -8, -4, -2, -1};
		String[] glyphs = remaining > 0
				? new String[]{"\ue94d", "\ue94c", "\ue94b", "\ue94a", "\ue949", "\ue948", "\ue947"}
				: new String[]{"\ue940", "\ue941", "\ue942", "\ue943", "\ue944", "\ue945", "\ue946"};

		for (int index = 0; index < values.length; index++) {
			int step = values[index];
			while ((remaining > 0 && remaining >= step) || (remaining < 0 && remaining <= step)) {
				result.append(glyphs[index]);
				remaining -= step;
			}
		}
		return result.toString();
	}

	private static String toPackDigitString(int value) {
		String digits = Integer.toString(Math.max(0, value));
		StringBuilder result = new StringBuilder(digits.length());
		for (int i = 0; i < digits.length(); i++) {
			char digit = digits.charAt(i);
			if (digit >= '0' && digit <= '9') {
				result.append((char) ('\ue920' + (digit - '0')));
			}
		}
		return result.toString();
	}

	private static String resolveLocalizedText(ServerPlayer player, Map<String, String> values) {
		if (values == null || values.isEmpty()) {
			return "";
		}
		String locale = normalizeLocale(player);
		String direct = values.get(locale);
		if (direct != null) {
			return direct;
		}

		for (Map.Entry<String, String> entry : values.entrySet()) {
			String key = entry.getKey();
			if (key != null && locale.startsWith(key.toLowerCase(Locale.ROOT))) {
				return entry.getValue();
			}
		}

		String defaultValue = values.get("default");
		if (defaultValue != null) {
			return defaultValue;
		}
		String enUsValue = values.get("en_us");
		if (enUsValue != null) {
			return enUsValue;
		}
		return values.values().iterator().next();
	}

	private static List<String> resolveLocalizedLines(ServerPlayer player, Map<String, List<String>> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		String locale = normalizeLocale(player);
		List<String> direct = values.get(locale);
		if (direct != null) {
			return direct;
		}

		for (Map.Entry<String, List<String>> entry : values.entrySet()) {
			String key = entry.getKey();
			if (key != null && locale.startsWith(key.toLowerCase(Locale.ROOT))) {
				return entry.getValue();
			}
		}

		List<String> defaultValue = values.get("default");
		if (defaultValue != null) {
			return defaultValue;
		}
		List<String> enUs = values.get("en_us");
		if (enUs != null) {
			return enUs;
		}
		return values.values().iterator().next();
	}

	private static String applyPlaceholders(String value, Map<String, String> placeholders) {
		if (value == null || value.isEmpty() || placeholders.isEmpty()) {
			return safeString(value);
		}
		String result = value;
		for (Map.Entry<String, String> entry : placeholders.entrySet()) {
			result = result.replace(entry.getKey(), safeString(entry.getValue()));
		}
		return result;
	}

	private static String normalizeLocale(ServerPlayer player) {
		if (player == null || player.clientInformation() == null || player.clientInformation().language() == null) {
			return "en_us";
		}
		return player.clientInformation().language().toLowerCase(Locale.ROOT);
	}

	private static String localizeTooltip(
			ServerPlayer player,
			String enUs,
			String ruRu,
			String ukUa,
			String jaJp,
			String rpr
	) {
		String locale = normalizeLocale(player);
		if (locale.startsWith("rpr")) {
			return rpr;
		}
		if (locale.startsWith("uk")) {
			return ukUa;
		}
		if (locale.startsWith("ja")) {
			return jaJp;
		}
		if (locale.startsWith("ru")) {
			return ruRu;
		}
		return enUs;
	}

	private static String localizeSystem(ServerPlayer player, String enUs, String ruRu) {
		String locale = normalizeLocale(player);
		if (locale.startsWith("ru") || locale.startsWith("rpr")) {
			return ruRu;
		}
		return enUs;
	}

	private static void sendPlayerMessage(ServerPlayer player, String message) {
		// Shop UI is intentionally silent in chat.
	}

	private static void playUiClick(ServerPlayer player, boolean success) {
		playUiClick(player, success, false);
	}

	private static void playUiClick(ServerPlayer player, boolean success, boolean suppress) {
		if (suppress || !success) {
			return;
		}
		player.level().playSound(
				null,
				player.blockPosition(),
				SoundEvents.UI_BUTTON_CLICK.value(),
				SoundSource.PLAYERS,
				0.8F,
				1.0F
		);
	}

	private static void playPurchaseSound(ServerPlayer player) {
		player.level().playSound(
				null,
				player.blockPosition(),
				SoundEvents.PLAYER_LEVELUP,
				SoundSource.PLAYERS,
				0.7F,
				1.2F
		);
	}

	public static void playPurchaseBlockedSound(ServerPlayer player) {
		if (player == null) {
			return;
		}

		long seed = player.level().random.nextLong();
		if (PolymerResourcePackUtils.hasMainPack(player)) {
			Holder<SoundEvent> sound = Holder.direct(SoundEvent.createVariableRangeEvent(PURCHASE_BLOCKED_SOUND_ID));
			player.connection.send(new ClientboundSoundPacket(
					sound,
					SoundSource.PLAYERS,
					player.getX(),
					player.getY(),
					player.getZ(),
					PURCHASE_BLOCKED_SOUND_VOLUME,
					PURCHASE_BLOCKED_SOUND_PITCH,
					seed
			));
			return;
		}

		player.connection.send(new ClientboundSoundPacket(
				BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.VILLAGER_NO),
				SoundSource.PLAYERS,
				player.getX(),
				player.getY(),
				player.getZ(),
				PURCHASE_BLOCKED_FALLBACK_VOLUME,
				PURCHASE_BLOCKED_FALLBACK_PITCH,
				seed
		));
	}

	private static int getUpgradeLevel(ServerPlayer player, String upgradeId) {
		if (player == null || upgradeId == null || upgradeId.isBlank()) {
			return 0;
		}
		Map<String, Integer> levels = PLAYER_UPGRADE_LEVELS.get(player.getUUID().toString());
		return levels == null ? 0 : Math.max(0, levels.getOrDefault(upgradeId, 0));
	}

	private static void setUpgradeLevel(MinecraftServer server, ServerPlayer player, String upgradeId, int level) {
		if (player == null || upgradeId == null || upgradeId.isBlank()) {
			return;
		}
		String playerKey = player.getUUID().toString();
		int normalizedLevel = Math.max(0, level);
		if (normalizedLevel <= 0) {
			Map<String, Integer> levels = PLAYER_UPGRADE_LEVELS.get(playerKey);
			if (levels != null && levels.remove(upgradeId) != null) {
				if (levels.isEmpty()) {
					PLAYER_UPGRADE_LEVELS.remove(playerKey);
				}
				stateDirty = true;
			}
		} else if (!Objects.equals(
				PLAYER_UPGRADE_LEVELS.computeIfAbsent(playerKey, ignored -> new LinkedHashMap<>()).put(upgradeId, normalizedLevel),
				normalizedLevel
		)) {
			stateDirty = true;
		}
		saveState(server);
		CartelSecretRecipeBookSystem.syncPlayerRecipeBook(player);
		CopperManGogglesSystem.syncPlayerRecipeBook(player);
		MarkShieldRecipeSystem.syncPlayerRecipeBook(player);
		ItRecipeBookSystem.syncPlayerRecipeBook(player);
	}

	private static Path getStatePath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(STATE_FILE_NAME);
	}

	private static void loadState(MinecraftServer server) {
		PLAYER_UPGRADE_LEVELS.clear();
		LEGACY_GLOBAL_UPGRADE_LEVELS.clear();
		Path path = getStatePath(server);
		if (!Files.exists(path)) {
			stateLoaded = true;
			stateDirty = false;
			return;
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			StateData state = STATE_GSON.fromJson(reader, StateData.class);
			if (state != null && state.playerLevels != null) {
				for (Map.Entry<String, Map<String, Integer>> playerEntry : state.playerLevels.entrySet()) {
					String playerKey = playerEntry.getKey();
					Map<String, Integer> loadedLevels = playerEntry.getValue();
					if (playerKey == null || playerKey.isBlank() || loadedLevels == null || loadedLevels.isEmpty()) {
						continue;
					}

					Map<String, Integer> sanitizedLevels = new LinkedHashMap<>();
					for (Map.Entry<String, Integer> levelEntry : loadedLevels.entrySet()) {
						String id = levelEntry.getKey();
						Integer level = levelEntry.getValue();
						if (id == null || id.isBlank() || level == null || level <= 0) {
							continue;
						}
						sanitizedLevels.put(id, level);
					}
					if (!sanitizedLevels.isEmpty()) {
						PLAYER_UPGRADE_LEVELS.put(playerKey, sanitizedLevels);
					}
				}
			}
			if (state != null && state.levels != null) {
				for (Map.Entry<String, Integer> entry : state.levels.entrySet()) {
					String id = entry.getKey();
					Integer level = entry.getValue();
					if (id == null || id.isBlank() || level == null || level <= 0) {
						continue;
					}
					LEGACY_GLOBAL_UPGRADE_LEVELS.put(id, level);
				}
			}
			stateLoaded = true;
			stateDirty = false;
		} catch (Exception e) {
			Lg2.LOGGER.warn("Failed to load upgrade UI state from {}", path, e);
			stateLoaded = true;
			stateDirty = false;
		}
	}

	private static void saveState(MinecraftServer server) {
		if (server == null || !stateLoaded || !stateDirty) {
			return;
		}

		Path path = getStatePath(server);
		StateData state = new StateData();
		for (Map.Entry<String, Map<String, Integer>> entry : PLAYER_UPGRADE_LEVELS.entrySet()) {
			state.playerLevels.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
		}
		state.levels.putAll(LEGACY_GLOBAL_UPGRADE_LEVELS);

		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				STATE_GSON.toJson(state, writer);
			}
			stateDirty = false;
		} catch (IOException e) {
			Lg2.LOGGER.warn("Failed to save upgrade UI state to {}", path, e);
		}
	}

	private static String safeString(String value) {
		return value == null ? "" : value;
	}

	private static String nonBlank(String value, String fallback) {
		String normalized = safeString(value).trim();
		return normalized.isEmpty() ? fallback : normalized;
	}

	private static boolean preserveBlankRaceAbilityText(RaceConfig.PlayerRaceConfig race) {
		return race != null && NO_RACE_ID.equals(safeString(race.id).trim());
	}

	private enum ButtonState {
		ACTIVE,
		LOCKED,
		MAXED
	}

	private static final class StateData {
		private final Map<String, Map<String, Integer>> playerLevels = new LinkedHashMap<>();
		private final Map<String, Integer> levels = new LinkedHashMap<>();
	}

	private static final class UpgradeMenu extends ChestMenu {
		private final int topSlotCount;
		private final String screenId;
		private final ServerPlayer viewer;
		private final boolean hasPack;

		private UpgradeMenu(int syncId, Inventory inventory, Container container, String screenId, int rows, ServerPlayer viewer, boolean hasPack) {
			super(resolveMenuType(rows), syncId, inventory, container, rows);
			this.topSlotCount = rows * 9;
			this.screenId = screenId;
			this.viewer = viewer;
			this.hasPack = hasPack;
		}

		@Override
		public void clicked(int slotId, int button, ClickType clickType, Player player) {
			if (slotId >= 0
					&& (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE || clickType == ClickType.SWAP)) {
				handleScreenSlotClick(this.viewer, this.screenId, slotId, this.hasPack);
			}
			// Hidden inventory stays non-interactive while this UI is open, except for virtual button slots.
			return;
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			return ItemStack.EMPTY;
		}

		@Override
		public void broadcastChanges() {
			super.broadcastChanges();
			hideLowerInventoryVisuals(this.viewer, this);
		}

		@Override
		public void broadcastFullState() {
			super.broadcastFullState();
			hideLowerInventoryVisuals(this.viewer, this);
		}

		@Override
		public void removed(Player player) {
			super.removed(player);
			if (player instanceof ServerPlayer serverPlayer
					&& serverPlayer.containerMenu != this
					&& serverPlayer.containerMenu instanceof UpgradeMenu) {
				return;
			}
			if (player instanceof ServerPlayer serverPlayer) {
				serverPlayer.connection.send(new ClientboundContainerClosePacket(this.containerId));
			}
			restoreLowerInventoryVisuals(this.viewer, this);
		}

		@Override
		public boolean stillValid(Player player) {
			return player.isAlive();
		}

		private static MenuType<?> resolveMenuType(int rows) {
			return switch (rows) {
				case 3 -> MenuType.GENERIC_9x3;
				case 4 -> MenuType.GENERIC_9x4;
				case 5 -> MenuType.GENERIC_9x5;
				default -> throw new IllegalArgumentException("Unsupported upgrade menu rows: " + rows);
			};
		}
	}
}
