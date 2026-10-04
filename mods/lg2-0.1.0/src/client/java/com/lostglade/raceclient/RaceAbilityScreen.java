package com.lostglade.raceclient;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

public final class RaceAbilityScreen extends Screen {
	private static final int BUTTON_SIZE = 96;
	private static final int TEXTURE_SIZE = 32;
	private static final float TEXTURE_SCALE = BUTTON_SIZE / (float) TEXTURE_SIZE;
	private static final int GAP = 4;
	private static final Identifier[] ICONS = {
			Identifier.fromNamespaceAndPath("lg2_race_client", "textures/gui/race/attack.png"),
			Identifier.fromNamespaceAndPath("lg2_race_client", "textures/gui/race/defense.png"),
			Identifier.fromNamespaceAndPath("lg2_race_client", "textures/gui/race/ability.png"),
			Identifier.fromNamespaceAndPath("lg2_race_client", "textures/gui/race/shnyaga.png")
	};
	private static final Identifier[] DISABLED_ICONS = {
			Identifier.fromNamespaceAndPath("lg2_race_client", "textures/gui/race/attack_disabled.png"),
			Identifier.fromNamespaceAndPath("lg2_race_client", "textures/gui/race/defense_disabled.png"),
			Identifier.fromNamespaceAndPath("lg2_race_client", "textures/gui/race/ability_disabled.png"),
			Identifier.fromNamespaceAndPath("lg2_race_client", "textures/gui/race/shnyaga_disabled.png")
	};
	private static final Identifier[] HOVER_FRAMES = {
			Identifier.fromNamespaceAndPath("lg2_race_client", "textures/gui/race/attack_hover_frame.png"),
			Identifier.fromNamespaceAndPath("lg2_race_client", "textures/gui/race/defense_hover_frame.png"),
			Identifier.fromNamespaceAndPath("lg2_race_client", "textures/gui/race/ability_hover_frame.png"),
			Identifier.fromNamespaceAndPath("lg2_race_client", "textures/gui/race/shnyaga_hover_frame.png")
	};
	private static final Component[] LABELS = {
			Component.translatable("key.lg2.race_attack"), Component.translatable("key.lg2.race_defense"),
			Component.translatable("key.lg2.race_ability"), Component.translatable("key.lg2.race_shnyaga")
	};

	private final RaceActionButton[] actionButtons = new RaceActionButton[ICONS.length];
	private int hoveredSlot = -1;
	private boolean finishingHold;
	private boolean menuBindingObservedDown;

	public RaceAbilityScreen() {
		super(Component.translatable("key.lg2.race_menu"));
	}

	@Override
	protected void init() {
		int gridSize = BUTTON_SIZE * 2 + GAP;
		int startX = (this.width - gridSize) / 2;
		int startY = (this.height - gridSize) / 2;
		for (int slot = 0; slot < ICONS.length; slot++) {
			int x = startX + (slot % 2) * (BUTTON_SIZE + GAP);
			int y = startY + (slot / 2) * (BUTTON_SIZE + GAP);
			RaceActionButton button = new RaceActionButton(x, y, slot);
			this.actionButtons[slot] = button;
			this.addRenderableWidget(button);
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		this.hoveredSlot = -1;
		for (RaceActionButton button : this.actionButtons) {
			if (button != null && RaceAbilityState.isUnlocked(button.slot) && button.isMouseOver(mouseX, mouseY)) {
				this.hoveredSlot = button.slot;
				break;
			}
		}
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (this.minecraft != null && this.minecraft.options.keyQuickActions.matches(event)) {
			if (!RaceClientControls.isMenuBindingPhysicallyDown(this.minecraft)) {
				this.finishHoldSelection();
			}
			return true;
		}
		return super.keyReleased(event);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.minecraft != null && this.minecraft.options.keyQuickActions.matchesMouse(event)) {
			if (!RaceClientControls.isMenuBindingPhysicallyDown(this.minecraft)) {
				this.finishHoldSelection();
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.minecraft == null) return;
		if (RaceClientControls.isMenuBindingPhysicallyDown(this.minecraft)) {
			this.menuBindingObservedDown = true;
		} else if (this.menuBindingObservedDown) {
			this.finishHoldSelection();
		}
	}

	private void finishHoldSelection() {
		if (this.finishingHold) return;
		this.finishingHold = true;
		if (this.hoveredSlot >= 0 && RaceAbilityState.isUnlocked(this.hoveredSlot)) {
			RaceClientControls.useAbility(this.hoveredSlot);
		}
		if (this.minecraft != null && this.minecraft.screen == this) {
			this.minecraft.setScreen(null);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static final class RaceActionButton extends AbstractWidget {
		private final int slot;

		private RaceActionButton(int x, int y, int slot) {
			super(x, y, BUTTON_SIZE, BUTTON_SIZE, LABELS[slot]);
			this.slot = slot;
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			boolean unlocked = RaceAbilityState.isUnlocked(this.slot);
			this.active = unlocked;
			drawTexture(graphics, unlocked ? ICONS[this.slot] : DISABLED_ICONS[this.slot]);
			if (!unlocked) {
				return;
			}
			if (this.isHoveredOrFocused()) {
				drawTexture(graphics, HOVER_FRAMES[this.slot]);
			}
		}

		private void drawTexture(GuiGraphics graphics, Identifier texture) {
			graphics.pose().pushMatrix();
			try {
				graphics.pose().translate(this.getX(), this.getY());
				graphics.pose().scale(TEXTURE_SCALE, TEXTURE_SCALE);
				graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
			} finally {
				graphics.pose().popMatrix();
			}
		}

		@Override
		public void onClick(MouseButtonEvent click, boolean doubleClick) {
			if (!RaceAbilityState.isUnlocked(this.slot)) {
				return;
			}
			RaceClientControls.useAbility(this.slot);
			Minecraft.getInstance().setScreen(null);
		}

		@Override
		public void playDownSound(SoundManager soundManager) {
			soundManager.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput narrationOutput) {
			this.defaultButtonNarrationText(narrationOutput);
		}
	}
}
