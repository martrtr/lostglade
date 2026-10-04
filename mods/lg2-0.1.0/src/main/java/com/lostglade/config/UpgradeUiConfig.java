package com.lostglade.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lostglade.Lg2;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UpgradeUiConfig {
	private static final int MIN_ROWS = 3;
	private static final int MAX_ROWS = 5;
	private static final int MIN_SLOT = 0;
	private static final int MAX_BUTTON_COUNT = 256;
	private static final int MIN_PRICE = 0;
	private static final int MAX_PRICE = 1_000_000;
	private static final int MIN_STACK_COUNT = 1;
	private static final int MAX_STACK_COUNT = 64;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(Lg2.MOD_ID + "-upgrades.json");

	private static ConfigData data = ConfigData.defaults();

	private UpgradeUiConfig() {
	}

	public static synchronized void load() {
		ConfigData loaded = readOrCreate();
		boolean changed = sanitize(loaded);
		data = loaded;
		if (changed) {
			write(data);
		}
	}

	public static synchronized void save() {
		write(data);
	}

	public static ConfigData get() {
		return data;
	}

	private static ConfigData readOrCreate() {
		if (!Files.exists(PATH)) {
			ConfigData defaults = ConfigData.defaults();
			write(defaults);
			return defaults;
		}

		try (Reader reader = Files.newBufferedReader(PATH)) {
			ConfigData parsed = GSON.fromJson(reader, ConfigData.class);
			if (parsed == null) {
				return ConfigData.defaults();
			}
			return parsed;
		} catch (Exception e) {
			Lg2.LOGGER.warn("Failed to read upgrade UI config {}, using defaults", PATH, e);
			return ConfigData.defaults();
		}
	}

	private static void write(ConfigData configData) {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(configData, writer);
			}
		} catch (IOException e) {
			Lg2.LOGGER.error("Failed to write upgrade UI config {}", PATH, e);
		}
	}

	private static boolean sanitize(ConfigData configData) {
		boolean changed = false;
		if (configData.themes == null) {
			configData.themes = new LinkedHashMap<>();
			changed = true;
		}
		if (configData.screens == null) {
			configData.screens = new LinkedHashMap<>();
			changed = true;
		}
		if (configData.themes.isEmpty()) {
			configData.themes.put("default", ThemeConfig.defaultTheme());
			changed = true;
		}
		if (configData.screens.isEmpty()) {
			configData.screens.putAll(ConfigData.defaultScreens());
			changed = true;
		}

		Map<String, ThemeConfig> sanitizedThemes = new LinkedHashMap<>();
		for (Map.Entry<String, ThemeConfig> entry : configData.themes.entrySet()) {
			String id = sanitizeId(entry.getKey());
			if (id == null) {
				changed = true;
				continue;
			}
			ThemeConfig theme = entry.getValue() == null ? ThemeConfig.defaultTheme() : entry.getValue();
			changed |= sanitizeTheme(theme);
			sanitizedThemes.put(id, theme);
		}
		if (sanitizedThemes.isEmpty()) {
			sanitizedThemes.put("default", ThemeConfig.defaultTheme());
			changed = true;
		}
		configData.themes = sanitizedThemes;

		Map<String, ScreenConfig> sanitizedScreens = new LinkedHashMap<>();
		for (Map.Entry<String, ScreenConfig> entry : configData.screens.entrySet()) {
			String id = sanitizeId(entry.getKey());
			if (id == null) {
				changed = true;
				continue;
			}
			ScreenConfig screen = entry.getValue() == null ? ScreenConfig.defaultScreen(id) : entry.getValue();
			changed |= sanitizeScreen(screen, configData.themes.containsKey(screen.theme) ? screen.theme : "default");
			sanitizedScreens.put(id, screen);
		}
		if (sanitizedScreens.isEmpty()) {
			sanitizedScreens.putAll(ConfigData.defaultScreens());
			changed = true;
		}
		configData.screens = sanitizedScreens;
		if (configData.rootScreenId == null || !configData.screens.containsKey(configData.rootScreenId)) {
			configData.rootScreenId = configData.screens.keySet().iterator().next();
			changed = true;
		}

		return changed;
	}

	private static boolean sanitizeTheme(ThemeConfig theme) {
		boolean changed = false;
		if (theme.fillerIcon == null) {
			theme.fillerIcon = IconConfig.defaultFiller();
			changed = true;
		}
		changed |= sanitizeIcon(theme.fillerIcon, IconConfig.defaultFiller());
		if (theme.packTitlePrefix == null) {
			theme.packTitlePrefix = "";
			changed = true;
		}
		if (theme.packTitleSuffix == null) {
			theme.packTitleSuffix = "";
			changed = true;
		}
		return changed;
	}

	private static boolean sanitizeScreen(ScreenConfig screen, String fallbackThemeId) {
		boolean changed = false;
		screen.rows = clamp(screen.rows, MIN_ROWS, MAX_ROWS, 3);
		if (screen.title == null) {
			screen.title = LocalizedText.singleLanguage("en_us", "Upgrade Interface");
			changed = true;
		}
		if (screen.theme == null || screen.theme.isBlank()) {
			screen.theme = fallbackThemeId;
			changed = true;
		}
		if (screen.buttons == null) {
			screen.buttons = new LinkedHashMap<>();
			changed = true;
		}

		Map<String, ButtonConfig> sanitizedButtons = new LinkedHashMap<>();
		int buttonCount = 0;
		for (Map.Entry<String, ButtonConfig> entry : screen.buttons.entrySet()) {
			if (buttonCount >= MAX_BUTTON_COUNT) {
				changed = true;
				break;
			}

			String id = sanitizeId(entry.getKey());
			if (id == null) {
				changed = true;
				continue;
			}

			ButtonConfig button = entry.getValue() == null ? ButtonConfig.defaultDecor(id) : entry.getValue();
			changed |= sanitizeButton(button, screen.rows);
			sanitizedButtons.put(id, button);
			buttonCount++;
		}
		screen.buttons = sanitizedButtons;
		return changed;
	}

	private static boolean sanitizeButton(ButtonConfig button, int rows) {
		boolean changed = false;
		int totalMenuRows = rows + 4;
		int sanitizedSlot = clamp(button.slot, MIN_SLOT, (totalMenuRows * 9) - 1, 0);
		if (button.slot != sanitizedSlot) {
			button.slot = sanitizedSlot;
			changed = true;
		}
		int column = button.slot % 9;
		int row = button.slot / 9;
		int sanitizedWidth = clamp(button.hitboxWidth, 1, Math.max(1, 9 - column), 1);
		if (button.hitboxWidth != sanitizedWidth) {
			button.hitboxWidth = sanitizedWidth;
			changed = true;
		}
		int sanitizedHeight = clamp(button.hitboxHeight, 1, Math.max(1, totalMenuRows - row), 1);
		if (button.hitboxHeight != sanitizedHeight) {
			button.hitboxHeight = sanitizedHeight;
			changed = true;
		}

		if (button.type == null || button.type.isBlank()) {
			button.type = ButtonType.NONE.id;
			changed = true;
		}
		if (ButtonType.byId(button.type) == null) {
			button.type = ButtonType.NONE.id;
			changed = true;
		}

		if (button.icon == null) {
			button.icon = IconConfig.defaultButton();
			changed = true;
		}
		changed |= sanitizeIcon(button.icon, IconConfig.defaultButton());

		if (button.lockedIcon == null) {
			button.lockedIcon = IconConfig.defaultLocked();
			changed = true;
		}
		changed |= sanitizeIcon(button.lockedIcon, IconConfig.defaultLocked());

		if (button.maxedIcon == null) {
			button.maxedIcon = IconConfig.defaultMaxed();
			changed = true;
		}
		changed |= sanitizeIcon(button.maxedIcon, IconConfig.defaultMaxed());

		if (button.name == null) {
			button.name = LocalizedText.singleLanguage("en_us", "Button");
			changed = true;
		}
		if (button.lore == null) {
			button.lore = new LocalizedLines();
			changed = true;
		}
		if (button.targetScreenId == null) {
			button.targetScreenId = "";
			changed = true;
		}
		if (button.upgradeId == null) {
			button.upgradeId = "";
			changed = true;
		}
		if (button.pricesBitcoins == null) {
			button.pricesBitcoins = new ArrayList<>();
			changed = true;
		}
		if (button.requirements == null) {
			button.requirements = new ArrayList<>();
			changed = true;
		}
		if (button.requirementGroups == null) {
			button.requirementGroups = new ArrayList<>();
			changed = true;
		}
		for (int i = 0; i < button.pricesBitcoins.size(); i++) {
			int clamped = Math.max(MIN_PRICE, Math.min(MAX_PRICE, button.pricesBitcoins.get(i) == null ? 0 : button.pricesBitcoins.get(i)));
			if (!Integer.valueOf(clamped).equals(button.pricesBitcoins.get(i))) {
				button.pricesBitcoins.set(i, clamped);
				changed = true;
			}
		}

		List<RequirementConfig> sanitizedRequirements = new ArrayList<>();
		for (RequirementConfig requirement : button.requirements) {
			if (requirement == null || requirement.upgradeId == null || requirement.upgradeId.isBlank()) {
				changed = true;
				continue;
			}
			requirement.minLevel = Math.max(1, requirement.minLevel);
			sanitizedRequirements.add(requirement);
		}
		button.requirements = sanitizedRequirements;

		List<RequirementGroupConfig> sanitizedGroups = new ArrayList<>();
		for (RequirementGroupConfig group : button.requirementGroups) {
			if (group == null) {
				changed = true;
				continue;
			}
			changed |= sanitizeRequirementGroup(group);
			if (!group.requirements.isEmpty()) {
				sanitizedGroups.add(group);
			} else {
				changed = true;
			}
		}
		button.requirementGroups = sanitizedGroups;

		if (ButtonType.PURCHASE_UPGRADE.id.equals(button.type) && button.pricesBitcoins.isEmpty()) {
			button.pricesBitcoins.add(1);
			changed = true;
		}

		return changed;
	}

	private static boolean sanitizeRequirementGroup(RequirementGroupConfig group) {
		boolean changed = false;
		if (group.mode == null || group.mode.isBlank() || RequirementMode.byId(group.mode) == null) {
			group.mode = RequirementMode.ALL.id;
			changed = true;
		}
		if (group.requirements == null) {
			group.requirements = new ArrayList<>();
			changed = true;
		}

		List<RequirementConfig> sanitizedRequirements = new ArrayList<>();
		for (RequirementConfig requirement : group.requirements) {
			if (requirement == null || requirement.upgradeId == null || requirement.upgradeId.isBlank()) {
				changed = true;
				continue;
			}
			requirement.minLevel = Math.max(1, requirement.minLevel);
			sanitizedRequirements.add(requirement);
		}
		group.requirements = sanitizedRequirements;
		group.minSatisfiedRequirements = Math.max(1, group.minSatisfiedRequirements);
		return changed;
	}

	private static boolean sanitizeIcon(IconConfig icon, IconConfig fallback) {
		boolean changed = false;
		if (icon.fallbackItem == null || icon.fallbackItem.isBlank()) {
			icon.fallbackItem = fallback.fallbackItem;
			changed = true;
		}
		if (icon.packModel == null) {
			icon.packModel = fallback.packModel;
			changed = true;
		}
		icon.count = clamp(icon.count, MIN_STACK_COUNT, MAX_STACK_COUNT, fallback.count);
		return changed;
	}

	private static int clamp(Integer value, int minInclusive, int maxInclusive, int fallback) {
		int raw = value == null ? fallback : value;
		return Math.max(minInclusive, Math.min(maxInclusive, raw));
	}

	private static String sanitizeId(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	public enum ButtonType {
		NONE("none"),
		OPEN_SCREEN("open_screen"),
		PURCHASE_UPGRADE("purchase_upgrade"),
		CLOSE("close");

		public final String id;

		ButtonType(String id) {
			this.id = id;
		}

		public static ButtonType byId(String id) {
			for (ButtonType type : values()) {
				if (type.id.equalsIgnoreCase(id)) {
					return type;
				}
			}
			return null;
		}
	}

	public enum RequirementMode {
		ALL("all"),
		AT_LEAST("at_least");

		public final String id;

		RequirementMode(String id) {
			this.id = id;
		}

		public static RequirementMode byId(String id) {
			for (RequirementMode mode : values()) {
				if (mode.id.equalsIgnoreCase(id)) {
					return mode;
				}
			}
			return null;
		}
	}

	public static final class ConfigData {
		public boolean enabled = true;
		public String rootScreenId = "main";
		public Map<String, ThemeConfig> themes = new LinkedHashMap<>();
		public Map<String, ScreenConfig> screens = new LinkedHashMap<>();

		private ConfigData() {
		}

		public static ConfigData defaults() {
			ConfigData data = new ConfigData();
			data.themes.put("default", ThemeConfig.defaultTheme());
			data.screens.putAll(defaultScreens());
			return data;
		}

		private static Map<String, ScreenConfig> defaultScreens() {
			Map<String, ScreenConfig> screens = new LinkedHashMap<>();
			screens.put("main", ScreenConfig.defaultMainScreen());
			screens.put("systems", ScreenConfig.defaultSystemsScreen());
			screens.put("advanced", ScreenConfig.defaultAdvancedScreen());
			return screens;
		}
	}

	public static final class ThemeConfig {
		public boolean enabled = true;
		public String packTitlePrefix = "";
		public String packTitleSuffix = "";
		public boolean fillEmptySlots = true;
		public IconConfig fillerIcon = IconConfig.defaultFiller();

		public static ThemeConfig defaultTheme() {
			ThemeConfig theme = new ThemeConfig();
			theme.fillerIcon = IconConfig.defaultFiller();
			return theme;
		}
	}

	public static final class ScreenConfig {
		public boolean enabled = true;
		public int rows = 3;
		public String theme = "default";
		public boolean hideTitleTextWhenPack = false;
		public LocalizedText title = LocalizedText.singleLanguage("en_us", "Upgrade Interface");
		public Map<String, ButtonConfig> buttons = new LinkedHashMap<>();

		public static ScreenConfig defaultScreen(String id) {
			ScreenConfig screen = new ScreenConfig();
			screen.title = LocalizedText.singleLanguage("en_us", id);
			return screen;
		}

		private static ScreenConfig defaultMainScreen() {
			ScreenConfig screen = new ScreenConfig();
			screen.rows = 3;
			screen.title = LocalizedText.of(
					"Server Upgrades",
					"Прокачка сервера"
			);
			screen.buttons.put("systems", ButtonConfig.openScreen(
					11,
					"systems",
					LocalizedText.of("Systems", "Системы"),
					LocalizedLines.of(
							List.of("Open the 4-row systems screen."),
							List.of("Открыть экран систем на 4 ряда.")
					),
					IconConfig.category()
			));
			screen.buttons.put("buffer", ButtonConfig.purchase(
					13,
					"stability_buffer",
					List.of(4, 8, 12),
					LocalizedText.of("Stability Buffer", "Буфер стабильности"),
					LocalizedLines.of(
							List.of("Smooths server hiccups."),
							List.of("Сглаживает сбои сервера.")
					),
					IconConfig.upgrade()
			));
			screen.buttons.put("advanced", ButtonConfig.openScreen(
					15,
					"advanced",
					LocalizedText.of("Advanced", "Сложный интерфейс"),
					LocalizedLines.of(
							List.of("Open the 5-row advanced screen."),
							List.of("Открыть сложный экран на 5 рядов.")
					),
					IconConfig.info()
			));
			screen.buttons.put("close", ButtonConfig.close(
					22,
					LocalizedText.of("Close", "Закрыть"),
					LocalizedLines.of(
							List.of("Close this interface."),
							List.of("Закрыть интерфейс.")
					)
			));
			return screen;
		}

		private static ScreenConfig defaultSystemsScreen() {
			ScreenConfig screen = new ScreenConfig();
			screen.rows = 4;
			screen.title = LocalizedText.of("Systems", "Системы");
			screen.buttons.put("cooling", ButtonConfig.purchase(
					10,
					"cooling",
					List.of(3, 6, 12, 18),
					LocalizedText.of("Cooling", "Охлаждение"),
					LocalizedLines.of(
							List.of("Reduces thermal failures."),
							List.of("Снижает термические сбои.")
					),
					IconConfig.upgrade()
			));
			screen.buttons.put("bus", ButtonConfig.purchase(
					12,
					"data_bus",
					List.of(5, 9, 15),
					LocalizedText.of("Data Bus", "Шина данных"),
					LocalizedLines.of(
							List.of("Opens room for future upgrades."),
							List.of("Открывает место под будущие апгрейды.")
					),
					IconConfig.upgrade()
			));
			screen.buttons.put("routing", ButtonConfig.purchase(
					14,
					"routing",
					List.of(6, 10, 16),
					LocalizedText.of("Routing", "Маршрутизация"),
					LocalizedLines.of(
							List.of("Better internal traffic handling."),
							List.of("Лучше обрабатывает внутренний трафик.")
					),
					IconConfig.upgrade()
			));
			return screen;
		}

		private static ScreenConfig defaultAdvancedScreen() {
			ScreenConfig screen = new ScreenConfig();
			screen.rows = 5;
			screen.title = LocalizedText.of("Advanced Layout", "Сложный экран");
			screen.buttons.put("ai_core", ButtonConfig.purchase(
					10,
					"ai_core",
					List.of(8, 16, 24, 40, 64),
					LocalizedText.of("AI Core", "ИИ-ядро"),
					LocalizedLines.of(
							List.of("Example of a 5-row upgrade tree."),
							List.of("Пример дерева апгрейдов на 5 рядов.")
					),
					IconConfig.upgrade()
			));
			screen.buttons.put("buffering", ButtonConfig.purchase(
					13,
					"buffering",
					List.of(6, 12, 18, 30),
					LocalizedText.of("Buffering", "Буферизация"),
					LocalizedLines.of(
							List.of("Stores more server operations."),
							List.of("Хранит больше операций сервера.")
					),
					IconConfig.upgrade()
			));
			screen.buttons.put("clock", ButtonConfig.purchase(
					16,
					"clock_sync",
					List.of(7, 14, 28),
					LocalizedText.of("Clock Sync", "Синхронизация тактов"),
					LocalizedLines.of(
							List.of("Stabilizes timing-sensitive systems."),
							List.of("Стабилизирует системы, чувствительные ко времени.")
					),
					IconConfig.upgrade()
			));
			return screen;
		}
	}

	public static final class ButtonConfig {
		public boolean enabled = true;
		public int slot = 0;
		public int hitboxWidth = 1;
		public int hitboxHeight = 1;
		public String type = ButtonType.NONE.id;
		public String targetScreenId = "";
		public String upgradeId = "";
		public List<Integer> pricesBitcoins = new ArrayList<>();
		public IconConfig icon = IconConfig.defaultButton();
		public IconConfig lockedIcon = IconConfig.defaultLocked();
		public IconConfig maxedIcon = IconConfig.defaultMaxed();
		public LocalizedText name = LocalizedText.singleLanguage("en_us", "Button");
		public LocalizedLines lore = new LocalizedLines();
		public List<RequirementConfig> requirements = new ArrayList<>();
		public List<RequirementGroupConfig> requirementGroups = new ArrayList<>();
		public boolean closeAfterClick = false;

		public static ButtonConfig defaultDecor(String id) {
			ButtonConfig button = new ButtonConfig();
			button.name = LocalizedText.singleLanguage("en_us", id);
			return button;
		}

		public static ButtonConfig openScreen(int slot, String targetScreenId, LocalizedText name, LocalizedLines lore, IconConfig icon) {
			ButtonConfig button = new ButtonConfig();
			button.slot = slot;
			button.type = ButtonType.OPEN_SCREEN.id;
			button.targetScreenId = targetScreenId;
			button.name = name;
			button.lore = lore;
			button.icon = icon;
			return button;
		}

		public static ButtonConfig purchase(int slot, String upgradeId, List<Integer> pricesBitcoins, LocalizedText name, LocalizedLines lore, IconConfig icon) {
			ButtonConfig button = new ButtonConfig();
			button.slot = slot;
			button.type = ButtonType.PURCHASE_UPGRADE.id;
			button.upgradeId = upgradeId;
			button.pricesBitcoins = new ArrayList<>(pricesBitcoins);
			button.name = name;
			button.lore = lore;
			button.icon = icon;
			return button;
		}

		public static ButtonConfig close(int slot, LocalizedText name, LocalizedLines lore) {
			ButtonConfig button = new ButtonConfig();
			button.slot = slot;
			button.type = ButtonType.CLOSE.id;
			button.name = name;
			button.lore = lore;
			button.icon = IconConfig.close();
			return button;
		}
	}

	public static final class RequirementConfig {
		public String upgradeId = "";
		public int minLevel = 1;
	}

	public static final class RequirementGroupConfig {
		public String mode = RequirementMode.ALL.id;
		public int minSatisfiedRequirements = 1;
		public List<RequirementConfig> requirements = new ArrayList<>();
	}

	public static final class IconConfig {
		public String fallbackItem = "minecraft:paper";
		public String packModel = "lg2:gui/button/upgrade";
		public int count = 1;
		public boolean foil = false;

		public static IconConfig defaultFiller() {
			IconConfig icon = new IconConfig();
			icon.fallbackItem = "minecraft:gray_stained_glass_pane";
			icon.packModel = "lg2:gui/button/filler";
			return icon;
		}

		public static IconConfig defaultButton() {
			IconConfig icon = new IconConfig();
			icon.fallbackItem = "minecraft:paper";
			icon.packModel = "lg2:gui/button/upgrade";
			return icon;
		}

		public static IconConfig defaultLocked() {
			IconConfig icon = new IconConfig();
			icon.fallbackItem = "minecraft:barrier";
			icon.packModel = "lg2:gui/button/locked";
			return icon;
		}

		public static IconConfig defaultMaxed() {
			IconConfig icon = new IconConfig();
			icon.fallbackItem = "minecraft:emerald";
			icon.packModel = "lg2:gui/button/maxed";
			icon.foil = true;
			return icon;
		}

		public static IconConfig category() {
			IconConfig icon = new IconConfig();
			icon.fallbackItem = "minecraft:compass";
			icon.packModel = "lg2:gui/button/category";
			return icon;
		}

		public static IconConfig upgrade() {
			IconConfig icon = new IconConfig();
			icon.fallbackItem = "minecraft:redstone";
			icon.packModel = "lg2:gui/button/upgrade";
			return icon;
		}

		public static IconConfig info() {
			IconConfig icon = new IconConfig();
			icon.fallbackItem = "minecraft:book";
			icon.packModel = "lg2:gui/button/info";
			return icon;
		}

		public static IconConfig back() {
			IconConfig icon = new IconConfig();
			icon.fallbackItem = "minecraft:arrow";
			icon.packModel = "lg2:gui/button/back";
			return icon;
		}

		public static IconConfig close() {
			IconConfig icon = new IconConfig();
			icon.fallbackItem = "minecraft:barrier";
			icon.packModel = "lg2:gui/button/close";
			return icon;
		}
	}

	public static final class LocalizedText {
		public Map<String, String> values = new LinkedHashMap<>();

		public static LocalizedText singleLanguage(String language, String value) {
			LocalizedText text = new LocalizedText();
			text.values.put(language, value);
			return text;
		}

		public static LocalizedText of(String enUs, String ruRu) {
			LocalizedText text = new LocalizedText();
			text.values.put("en_us", enUs);
			text.values.put("ru_ru", ruRu);
			return text;
		}
	}

	public static final class LocalizedLines {
		public Map<String, List<String>> values = new LinkedHashMap<>();

		public static LocalizedLines of(List<String> enUs, List<String> ruRu) {
			LocalizedLines lines = new LocalizedLines();
			lines.values.put("en_us", new ArrayList<>(enUs));
			lines.values.put("ru_ru", new ArrayList<>(ruRu));
			return lines;
		}
	}
}
