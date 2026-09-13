package com.lostglade.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minimal vanilla-style settings page, available from Options → Lostglade. */
final class LostgladeSettingsScreen extends Screen {
	private final Screen parent;
	private Button rendererButton;
	private Button parallelCapturesButton;

	LostgladeSettingsScreen(Screen parent) {
		super(Component.literal("Lostglade"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 100;
		int y = this.height / 2 - 42;
		this.rendererButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			LostgladeClientSettings.setCameraRendererEnabled(!LostgladeClientSettings.isCameraRendererEnabled());
			RendererBotVolunteerClient.sendRendererHello();
			refreshLabels();
		}).bounds(x, y, 200, 20).build());
		this.parallelCapturesButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			int next = LostgladeClientSettings.maxParallelCaptures() >= 4
					? 1
					: LostgladeClientSettings.maxParallelCaptures() + 1;
			LostgladeClientSettings.setMaxParallelCaptures(next);
			refreshLabels();
		}).bounds(x, y + 26, 200, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
				.bounds(x, y + 64, 200, 20).build());
		refreshLabels();
	}

	private void refreshLabels() {
		this.rendererButton.setMessage(Component.literal("Рендер камер GPU: " + (LostgladeClientSettings.isCameraRendererEnabled() ? "Вкл" : "Выкл")));
		this.parallelCapturesButton.setMessage(Component.literal("Параллельные снимки: " + LostgladeClientSettings.maxParallelCaptures()));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// Screen.renderWithTooltipAndSubtitles already draws (and blurs) the background.
		graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 72, 0xFFFFFF);
		graphics.drawCenteredString(this.font, "Чем выше лимит, тем больше нагрузка на GPU и память.", this.width / 2, this.height / 2 + 98, 0xAAAAAA);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
