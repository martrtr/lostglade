package com.lostglade.item;

import com.lostglade.Lg2;
import com.lostglade.server.CopperManGogglesSystem;
import eu.pb4.polymer.core.api.item.SimplePolymerItem;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import xyz.nucleoid.packettweaker.PacketContext;

public final class CopperGogglesItem extends SimplePolymerItem {
	private static final Identifier MODEL_ID = Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "copper_goggles");
	private static final int MODE_SELECTED_COLOR = 0x55FF22;
	private static final int MODE_UNSELECTED_COLOR = 0x808080;
	private static final String MODE_ORE_SEARCH = "ORE_SEARCH";
	private static final String MODE_TRACKING = "TRACKING";
	private static final String MODE_MAGNIFIER = "MAGNIFIER";
	private static final String MODE_NIGHT_VISION = "NIGHT_VISION";

	public CopperGogglesItem(Item.Properties settings) {
		super(settings, Items.LEATHER_HELMET);
	}

	@Override
	public Component getName(ItemStack stack) {
		return Component.translatable(this.getDescriptionId());
	}

	@Override
	public Identifier getPolymerItemModel(ItemStack itemStack, PacketContext context) {
		return PolymerResourcePackUtils.hasMainPack(context) ? MODEL_ID : null;
	}

	@Override
	public void modifyBasePolymerItemStack(ItemStack out, ItemStack original, PacketContext context) {
		out.set(DataComponents.LORE, buildModeLore(context, original));
		if (!PolymerResourcePackUtils.hasMainPack(context)) {
			out.set(DataComponents.CUSTOM_NAME, localizedName(context).withStyle(style -> style.withItalic(false)));
			out.set(DataComponents.DYED_COLOR, new DyedItemColor(0x32FF32));
		}
	}

	private static MutableComponent localizedName(PacketContext context) {
		ServerPlayer player = context.getPlayer();
		if (player == null) {
			return Component.literal("Special Goggles");
		}

		String normalized = getNormalizedLanguage(context);
		if (normalized == null) {
			return Component.literal("Special Goggles");
		}
		if (normalized.startsWith("rpr")) {
			return Component.literal("Всевидящія стекла казённаго образца");
		}
		if (normalized.startsWith("uk")) {
			return Component.literal("Спец-окуляри");
		}
		if (normalized.startsWith("ru")) {
			return Component.literal("Спец-очки");
		}
		if (normalized.startsWith("ja")) {
			return Component.literal("特殊ゴーグル");
		}
		return Component.literal("Special Goggles");
	}

	private static ItemLore buildModeLore(PacketContext context, ItemStack stack) {
		String selectedMode = resolveSelectedModeId(stack);
		ItemLore lore = ItemLore.EMPTY;
		lore = lore.withLineAdded(buildModeLine(context, MODE_ORE_SEARCH, "tooltip.lg2.copper_goggles.mode.ore_search", selectedMode));
		lore = lore.withLineAdded(buildModeLine(context, MODE_TRACKING, "tooltip.lg2.copper_goggles.mode.tracking", selectedMode));
		lore = lore.withLineAdded(buildModeLine(context, MODE_MAGNIFIER, "tooltip.lg2.copper_goggles.mode.magnifier", selectedMode));
		lore = lore.withLineAdded(buildModeLine(context, MODE_NIGHT_VISION, "tooltip.lg2.copper_goggles.mode.night_vision", selectedMode));
		return lore;
	}

	private static Component buildModeLine(PacketContext context, String modeId, String translationKey, String selectedMode) {
		boolean selected = modeId.equals(selectedMode);
		int color = selected ? MODE_SELECTED_COLOR : MODE_UNSELECTED_COLOR;
		MutableComponent label = useTranslatedTooltip(context)
				? Component.translatable(translationKey)
				: Component.literal(localizedTooltipLabel(context, modeId));
		label.withStyle(style -> style.withColor(color).withItalic(false));
		return Component.literal("- ")
				.withStyle(style -> style.withColor(color).withItalic(false))
				.append(label);
	}

	private static boolean useTranslatedTooltip(PacketContext context) {
		return PolymerResourcePackUtils.hasMainPack(context) && hasSupportedLanguage(context);
	}

	private static boolean hasSupportedLanguage(PacketContext context) {
		String normalized = getNormalizedLanguage(context);
		if (normalized == null) {
			return false;
		}
		return normalized.startsWith("en")
				|| normalized.startsWith("ru")
				|| normalized.startsWith("uk")
				|| normalized.startsWith("rpr")
				|| normalized.startsWith("ja");
	}

	private static String getNormalizedLanguage(PacketContext context) {
		ServerPlayer player = context.getPlayer();
		if (player == null || player.clientInformation() == null || player.clientInformation().language() == null) {
			return null;
		}
		return player.clientInformation().language().toLowerCase();
	}

	private static String resolveSelectedModeId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? MODE_ORE_SEARCH : CopperManGogglesSystem.getCurrentModeId(stack);
	}

	private static String localizedTooltipLabel(PacketContext context, String modeId) {
		String normalized = getNormalizedLanguage(context);
		if (normalized == null) {
			return englishTooltipLabel(modeId);
		}
		if (normalized.startsWith("rpr")) {
			return switch (modeId) {
				case MODE_ORE_SEARCH -> "Рудоискательный чинъ";
				case MODE_TRACKING -> "Сыскное выслеживаніе";
				case MODE_MAGNIFIER -> "Окуляръ чрезмѣрнаго взора";
				case MODE_NIGHT_VISION -> "Нощное всевидѣніе";
				default -> englishTooltipLabel(modeId);
			};
		}
		if (normalized.startsWith("uk")) {
			return switch (modeId) {
				case MODE_ORE_SEARCH -> "Режим пошуку руд";
				case MODE_TRACKING -> "Режим відстеження";
				case MODE_MAGNIFIER -> "Режим лупи";
				case MODE_NIGHT_VISION -> "Режим нічного зору";
				default -> englishTooltipLabel(modeId);
			};
		}
		if (normalized.startsWith("ru")) {
			return switch (modeId) {
				case MODE_ORE_SEARCH -> "Режим поиска руд";
				case MODE_TRACKING -> "Режим отслеживания";
				case MODE_MAGNIFIER -> "Режим лупы";
				case MODE_NIGHT_VISION -> "Режим ночного зрения";
				default -> englishTooltipLabel(modeId);
			};
		}
		if (normalized.startsWith("ja")) {
			return switch (modeId) {
				case MODE_ORE_SEARCH -> "鉱石探索モード";
				case MODE_TRACKING -> "追跡モード";
				case MODE_MAGNIFIER -> "ズームモード";
				case MODE_NIGHT_VISION -> "暗視モード";
				default -> englishTooltipLabel(modeId);
			};
		}
		return englishTooltipLabel(modeId);
	}

	private static String englishTooltipLabel(String modeId) {
		return switch (modeId) {
			case MODE_ORE_SEARCH -> "Ore Search Mode";
			case MODE_TRACKING -> "Tracking Mode";
			case MODE_MAGNIFIER -> "Zoom Mode";
			case MODE_NIGHT_VISION -> "Night Vision Mode";
			default -> "Mode";
		};
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (stack.isEmpty()) {
			return InteractionResult.PASS;
		}

		if (hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}

		ItemStack headStack = player.getItemBySlot(EquipmentSlot.HEAD);
		if (!headStack.isEmpty()) {
			return InteractionResult.PASS;
		}

		if (!level.isClientSide()) {
			ItemStack equipped = stack.copyWithCount(1);
			player.setItemSlot(EquipmentSlot.HEAD, equipped);
			if (!player.getAbilities().instabuild) {
				stack.shrink(1);
			}
			player.playSound(SoundEvents.ARMOR_EQUIP_CHAIN.value(), 1.0F, 1.0F);
			if (player instanceof ServerPlayer serverPlayer) {
				CopperManGogglesSystem.refreshVisual(serverPlayer);
			}
		}

		return InteractionResult.SUCCESS;
	}
}
