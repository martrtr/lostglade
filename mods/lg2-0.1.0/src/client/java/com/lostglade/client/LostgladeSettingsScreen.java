package com.lostglade.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minimal vanilla-style settings page, available from Options → Lostglade. */
final class LostgladeSettingsScreen extends Screen {
	private final Screen parent;
	private AbstractSliderButton parallelCapturesSlider;

	LostgladeSettingsScreen(Screen parent) {
		super(Component.literal("Lostglade"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 100;
		int y = this.height / 2 - 28;
		this.parallelCapturesSlider = this.addRenderableWidget(new AbstractSliderButton(
				x, y, 200, 20, Component.empty(), LostgladeClientSettings.maxParallelCaptures() / 4.0D
		) {
			@Override
			protected void updateMessage() {
				int limit = (int) Math.round(this.value * 4.0D);
				this.setMessage(limitLabel(limit));
			}

			@Override
			protected void applyValue() {
				int limit = (int) Math.round(this.value * 4.0D);
				this.value = limit / 4.0D;
				LostgladeClientSettings.setMaxParallelCaptures(limit);
				RendererBotVolunteerClient.sendRendererHello();
				this.updateMessage();
			}
		});
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
				.bounds(x, y + 38, 200, 20).build());
		refreshLabels();
	}

	private void refreshLabels() {
		this.parallelCapturesSlider.setMessage(limitLabel(LostgladeClientSettings.maxParallelCaptures()));
	}

	private static Component limitLabel(int limit) {
		return Component.literal(limit <= 0
				? "Рендер камер GPU: Выкл"
				: "GPU-потоки камер: " + limit);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// Screen.renderWithTooltipAndSubtitles already draws (and blurs) the background.
		graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 58, 0xFFFFFF);
		graphics.drawCenteredString(this.font, "0 — не участвовать в рендере. Чем выше лимит, тем больше нагрузка на GPU и память.", this.width / 2, this.height / 2 + 84, 0xAAAAAA);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
