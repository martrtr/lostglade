package com.lostglade.server;

import com.lostglade.Lg2;
import com.lostglade.config.RaceConfig.PlayerRaceConfig;
import com.lostglade.config.RaceConfig.RaceAbilityConfig;
import com.lostglade.config.RaceConfig.RaceAbilitySlot;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

public final class NecromancerShnyagaSystem {
	private static final String RACE_ID = "necromancer";
	private static final int MENU_ROWS = 3;
	private static final double DEFAULT_TIER_ONE_COST = 100.0D;
	private static final double DEFAULT_TIER_TWO_COST = 200.0D;
	private static final double DEFAULT_TIER_THREE_COST = 300.0D;
	private static final double DEFAULT_MAX_ABSORPTION_HEARTS = 15.0D;
	private static final double DEFAULT_EFFECT_DURATION_SECONDS = 30.0D;
	private static final String TITLE_SHIFT = "\ue905";
	private static final String TITLE_RESET = "\ue940\ue940\ue941\ue943";
	private static final String MENU_GLYPH = "\uebc0";
	private static final FontDescription MENU_FONT = new FontDescription.Resource(
			Identifier.fromNamespaceAndPath(Lg2.MOD_ID, "necromancer_shnyaga_menu"));
	private static final Identifier INVISIBLE_BUTTON_MODEL = Identifier.fromNamespaceAndPath(
			Lg2.MOD_ID, "gui/button/invisible");
	private static final Identifier ABSORPTION_CAPACITY_ID = Identifier.fromNamespaceAndPath(
			Lg2.MOD_ID, "necromancer_shnyaga_absorption_capacity");
	private static final DustParticleOptions BLACK_DUST = new DustParticleOptions(0x000000, 1.15F);
	private static final DustParticleOptions NECRO_DUST = new DustParticleOptions(0x32113F, 0.85F);
	private static final DustParticleOptions DARK_SOUL_DUST = new DustParticleOptions(0x54206F, 0.85F);

	private NecromancerShnyagaSystem() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(NecromancerShnyagaSystem::tick);
	}

	public static int use(ServerPlayer player, RaceAbilityConfig ability) {
		if (!isEligible(player) || ability == null || !player.isAlive() || player.isSpectator()) return 0;
		syncAbsorptionCapacity(player, ability);
		boolean hasPack = PolymerResourcePackUtils.hasMainPack(player);
		player.openMenu(new SimpleMenuProvider(
				(syncId, inventory, menuPlayer) -> new TransMenu(syncId, inventory, player, hasPack),
				menuTitle(hasPack)));
		return 1;
	}

	private static void tick(MinecraftServer server) {
		if (server == null || server.getTickCount() % 20L != 0L) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			RaceAbilityConfig ability = eligibleAbility(player);
			if (ability == null) {
				removeAbsorptionCapacity(player);
			} else {
				syncAbsorptionCapacity(player, ability);
			}
		}
	}

	private static boolean activate(ServerPlayer player, TransMode mode) {
		RaceAbilityConfig ability = eligibleAbility(player);
		if (ability == null || mode == null || !player.isAlive() || player.isSpectator()) return false;
		int tier = affordableTier(player, ability);
		if (tier <= 0) {
			player.displayClientMessage(Component.literal("\u041d\u0435\u0434\u043e\u0441\u0442\u0430\u0442\u043e\u0447\u043d\u043e \u043c\u0430\u043d\u044b \u0434\u043b\u044f \u0442\u0440\u0430\u043d\u0441\u0430")
					.withStyle(ChatFormatting.DARK_AQUA), true);
			return false;
		}

		double cost = tierCost(ability, tier);
		if (!NecromancerStockSystem.trySpendMana(player, cost)) return false;
		int durationTicks = Math.max(1, (int) Math.round(positiveOrDefault(
				ability.necromancerShnyagaEffectDurationSeconds,
				DEFAULT_EFFECT_DURATION_SECONDS) * 20.0D));
		int amplifier = tier - 1;
		player.addEffect(new MobEffectInstance(mode.primaryEffect, durationTicks, amplifier));
		player.addEffect(new MobEffectInstance(mode.secondaryEffect, durationTicks, amplifier));
		grantAbsorption(player, ability, tier);
		spawnActivationRings(player, mode);
		player.level().playSound(
				null,
				player.getX(),
				player.getY(),
				player.getZ(),
				SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(),
				SoundSource.PLAYERS,
				1.0F,
				1.0F
		);
		return true;
	}

	private static void spawnActivationRings(ServerPlayer player, TransMode mode) {
		if (!(player.level() instanceof ServerLevel level)) return;
		AABB bounds = player.getBoundingBox();
		double radius = Math.max(bounds.getXsize(), bounds.getZsize()) * 0.5D + 0.18D;
		int points = 40;
		for (int ring = 0; ring < 3; ring++) {
			double y = bounds.minY + bounds.getYsize() * (ring + 1.0D) / 4.0D;
			double phase = (ring & 1) == 0 ? 0.0D : Math.PI / points;
			for (int index = 0; index < points; index++) {
				double angle = Math.PI * 2.0D * index / points + phase;
				double x = player.getX() + Math.cos(angle) * radius;
				double z = player.getZ() + Math.sin(angle) * radius;
				level.sendParticles(NECRO_DUST, x, y, z, 1, 0.01D, 0.01D, 0.01D, 0.0D);
				if ((index & 1) == 0) {
					level.sendParticles(BLACK_DUST, x, y, z, 1, 0.008D, 0.008D, 0.008D, 0.0D);
				}
				if (index % 4 == ring) {
					level.sendParticles(DARK_SOUL_DUST, x, y, z, 1, 0.01D, 0.01D, 0.01D, 0.001D);
				}
				if (index % 8 == ring * 2) {
					level.sendParticles(mode.particle, x, y, z, 1, 0.008D, 0.008D, 0.008D, 0.001D);
				}
				if (index % 10 == ring) {
					level.sendParticles(mode.particle, x, y, z, 1, 0.005D, 0.005D, 0.005D, 0.001D);
				}
				if (index % 12 == ring * 2) {
					level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y, z, 1, 0.008D, 0.008D, 0.008D, 0.003D);
				}
			}
		}
	}

	private static void grantAbsorption(ServerPlayer player, RaceAbilityConfig ability, int tier) {
		syncAbsorptionCapacity(player, ability);
		float cap = (float) (positiveOrDefault(
				ability.necromancerShnyagaMaxAbsorptionHearts,
				DEFAULT_MAX_ABSORPTION_HEARTS) * 2.0D);
		float current = player.getAbsorptionAmount();
		if (current >= cap) return;
		player.setAbsorptionAmount(Math.min(cap, current + tier * 2.0F));
	}

	private static void syncAbsorptionCapacity(ServerPlayer player, RaceAbilityConfig ability) {
		AttributeInstance attribute = player.getAttribute(Attributes.MAX_ABSORPTION);
		if (attribute == null) return;
		double amount = positiveOrDefault(
				ability.necromancerShnyagaMaxAbsorptionHearts,
				DEFAULT_MAX_ABSORPTION_HEARTS) * 2.0D;
		AttributeModifier current = attribute.getModifier(ABSORPTION_CAPACITY_ID);
		if (current != null && Math.abs(current.amount() - amount) <= 1.0E-9D) return;
		if (current != null) attribute.removeModifier(ABSORPTION_CAPACITY_ID);
		attribute.addTransientModifier(new AttributeModifier(
				ABSORPTION_CAPACITY_ID,
				amount,
				AttributeModifier.Operation.ADD_VALUE));
		player.setAbsorptionAmount(Math.min(player.getAbsorptionAmount(), (float) player.getMaxAbsorption()));
	}

	private static void removeAbsorptionCapacity(ServerPlayer player) {
		AttributeInstance attribute = player.getAttribute(Attributes.MAX_ABSORPTION);
		if (attribute == null || attribute.getModifier(ABSORPTION_CAPACITY_ID) == null) return;
		attribute.removeModifier(ABSORPTION_CAPACITY_ID);
		player.setAbsorptionAmount(Math.min(player.getAbsorptionAmount(), (float) player.getMaxAbsorption()));
	}

	private static int affordableTier(ServerPlayer player, RaceAbilityConfig ability) {
		double mana = NecromancerStockSystem.currentMana(player);
		if (mana + 1.0E-9D >= tierCost(ability, 3)) return 3;
		if (mana + 1.0E-9D >= tierCost(ability, 2)) return 2;
		if (mana + 1.0E-9D >= tierCost(ability, 1)) return 1;
		return 0;
	}

	private static double tierCost(RaceAbilityConfig ability, int tier) {
		return switch (tier) {
			case 3 -> positiveOrDefault(ability.necromancerShnyagaTierThreeManaCost, DEFAULT_TIER_THREE_COST);
			case 2 -> positiveOrDefault(ability.necromancerShnyagaTierTwoManaCost, DEFAULT_TIER_TWO_COST);
			default -> positiveOrDefault(ability.necromancerShnyagaTierOneManaCost, DEFAULT_TIER_ONE_COST);
		};
	}

	private static RaceAbilityConfig eligibleAbility(ServerPlayer player) {
		if (player == null) return null;
		Optional<PlayerRaceConfig> race = ServerRaceSystem.getRace(player);
		if (race.isEmpty() || race.get().id == null || !RACE_ID.equalsIgnoreCase(race.get().id.trim())) return null;
		RaceAbilityConfig ability = ServerRaceSystem.getAbility(race.get(), RaceAbilitySlot.SHNYAGA);
		return ability != null && ability.enabled && ServerRaceSystem.hasUnlockedAbility(player, RaceAbilitySlot.SHNYAGA)
				? ability
				: null;
	}

	private static boolean isEligible(ServerPlayer player) {
		return eligibleAbility(player) != null;
	}

	private static Component menuTitle(boolean hasPack) {
		if (!hasPack) return Component.empty();
		MutableComponent title = Component.empty();
		title.append(Component.literal(TITLE_SHIFT).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)));
		title.append(Component.literal(MENU_GLYPH).withStyle(style -> style
				.withColor(0xFFFFFF).withItalic(false).withFont(MENU_FONT).withShadowColor(0x00000000)));
		title.append(Component.literal(TITLE_RESET).withStyle(style -> style.withColor(0xFFFFFF).withItalic(false)));
		return title;
	}

	private static ItemStack button(ServerPlayer player, RaceAbilityConfig ability, TransMode mode, boolean hasPack) {
		Item fallback = switch (mode) {
			case COMBAT -> Items.BLAZE_POWDER;
			case WORK -> Items.LIME_DYE;
			case VITAL -> Items.GLISTERING_MELON_SLICE;
		};
		ItemStack stack = new ItemStack(hasPack ? Items.PAPER : fallback);
		if (hasPack) {
			stack.set(DataComponents.ITEM_MODEL, INVISIBLE_BUTTON_MODEL);
		}
		int tier = affordableTier(player, ability);
		String title = tier > 0 ? mode.title + " " + romanTier(tier) : mode.title;
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(title)
				.withStyle(style -> style.withColor(mode.color).withItalic(false).withBold(true)));
		if (!hasPack) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return stack;
	}

	private static TransMode modeForSlot(int slotId) {
		if (slotId < 0 || slotId >= MENU_ROWS * 9) return null;
		return switch (slotId % 9) {
			case 0, 1, 2 -> TransMode.WORK;
			case 3, 4, 5 -> TransMode.COMBAT;
			case 6, 7, 8 -> TransMode.VITAL;
			default -> null;
		};
	}

	private static String romanTier(int tier) {
		return switch (tier) {
			case 3 -> "III";
			case 2 -> "II";
			default -> "I";
		};
	}

	private static double positiveOrDefault(double value, double fallback) {
		return Double.isFinite(value) && value > 0.0D ? value : fallback;
	}

	private enum TransMode {
		COMBAT(
				"\u0411\u043e\u0435\u0432\u043e\u0439 \u0442\u0440\u0430\u043d\u0441",
				0xFF2D2D,
				MobEffects.STRENGTH,
				MobEffects.SPEED),
		WORK(
				"\u0420\u0430\u0431\u043e\u0447\u0438\u0439 \u0442\u0440\u0430\u043d\u0441",
				0x2F6BFF,
				MobEffects.HASTE,
				MobEffects.NIGHT_VISION),
		VITAL(
				"\u0416\u0438\u0432\u0438\u0442\u0435\u043b\u044c\u043d\u044b\u0439 \u0442\u0440\u0430\u043d\u0441",
				0x7CFF33,
				MobEffects.REGENERATION,
				MobEffects.RESISTANCE);

		private final String title;
		private final int color;
		private final DustParticleOptions particle;
		private final net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> primaryEffect;
		private final net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> secondaryEffect;

		TransMode(
				String title,
				int color,
				net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> primaryEffect,
				net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> secondaryEffect
		) {
			this.title = title;
			this.color = color;
			this.particle = new DustParticleOptions(color, 0.62F);
			this.primaryEffect = primaryEffect;
			this.secondaryEffect = secondaryEffect;
		}
	}

	private static final class TransMenu extends ChestMenu {
		private final SimpleContainer buttons;
		private final ServerPlayer viewer;

		private TransMenu(int syncId, Inventory inventory, ServerPlayer viewer, boolean hasPack) {
			this(syncId, inventory, new SimpleContainer(MENU_ROWS * 9), viewer, hasPack);
		}

		private TransMenu(
				int syncId,
				Inventory inventory,
				SimpleContainer buttons,
				ServerPlayer viewer,
				boolean hasPack
		) {
			super(MenuType.GENERIC_9x3, syncId, inventory, buttons, MENU_ROWS);
			this.buttons = buttons;
			this.viewer = viewer;
			refresh(hasPack);
		}

		private void refresh(boolean hasPack) {
			RaceAbilityConfig ability = eligibleAbility(this.viewer);
			this.buttons.clearContent();
			if (ability == null) return;
			for (int slotId = 0; slotId < MENU_ROWS * 9; slotId++) {
				this.buttons.setItem(slotId, button(this.viewer, ability, modeForSlot(slotId), hasPack));
			}
		}

		@Override
		public void clicked(int slotId, int button, ClickType clickType, Player player) {
			if (clickType != ClickType.PICKUP && clickType != ClickType.QUICK_MOVE && clickType != ClickType.SWAP) return;
			TransMode mode = modeForSlot(slotId);
			if (mode != null && activate(this.viewer, mode)) this.viewer.closeContainer();
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			return ItemStack.EMPTY;
		}

		@Override
		public boolean stillValid(Player player) {
			return player == this.viewer && player.isAlive() && isEligible(this.viewer);
		}
	}
}
