package com.lostglade.client;

import com.lostglade.client.maprender.YandexMapRenderClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A short, local settings page with one shared render-budget control. */
final class LostgladeSettingsScreen extends Screen {
	private final Screen parent;
	private AbstractSliderButton renderBudgetSlider;
	private AbstractSliderButton droneTiltStrengthSlider;

	LostgladeSettingsScreen(Screen parent) {
		super(Component.literal("Lostglade"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int width = Math.min(360, this.width - 32);
		int left = (this.width - width) / 2;
		int top = Math.max(52, this.height / 2 - 78);

		this.renderBudgetSlider = this.addRenderableWidget(new AbstractSliderButton(
				left, top, width, 20, Component.empty(), LostgladeClientSettings.renderBudgetPercent() / 100.0D
		) {
			@Override
			protected void updateMessage() {
				this.setMessage(Component.literal("Общий ресурс рендера: " + percent(this.value) + "%"));
			}

			@Override
			protected void applyValue() {
				boolean wasEnabled = LostgladeClientSettings.isRenderContributionEnabled();
				int budget = percent(this.value);
				this.value = budget / 100.0D;
				LostgladeClientSettings.setRenderBudgetPercent(budget);
				RendererBotVolunteerClient.onRenderBudgetChanged(wasEnabled);
				YandexMapRenderClient.requestCapabilityRefresh();
				this.updateMessage();
			}
		});

		this.addRenderableWidget(Checkbox.builder(
				Component.literal("Наклон камеры дрона"), this.font
		).pos(left, top + 42).maxWidth(width).selected(LostgladeClientSettings.isDroneTiltEnabled())
				.onValueChange((checkbox, selected) -> {
					LostgladeClientSettings.setDroneTiltEnabled(selected);
					this.droneTiltStrengthSlider.active = selected;
				}).build());

		this.droneTiltStrengthSlider = this.addRenderableWidget(new AbstractSliderButton(
				left, top + 66, width, 20, Component.empty(), LostgladeClientSettings.droneTiltStrength()
		) {
			@Override
			protected void updateMessage() {
				this.setMessage(Component.literal("Сила наклона: " + percent(this.value) + "%"));
			}

			@Override
			protected void applyValue() {
				LostgladeClientSettings.setDroneTiltStrength((float) this.value);
				this.value = LostgladeClientSettings.droneTiltStrength();
				this.updateMessage();
			}
		});
		this.droneTiltStrengthSlider.active = LostgladeClientSettings.isDroneTiltEnabled();

		this.addRenderableWidget(Checkbox.builder(
				Component.literal("Меню рас (клавиша «Быстрые действия» )"), this.font
		).pos(left, top + 100).maxWidth(width).selected(LostgladeClientSettings.isRaceMenuEnabled())
				.onValueChange((checkbox, selected) -> LostgladeClientSettings.setRaceMenuEnabled(selected)).build());

		this.addRenderableWidget(Button.builder(Component.literal("Диагностика рендера"), button ->
				this.minecraft.setScreen(new RendererDiagnosticsScreen(this))
		).bounds(left, top + 132, width, 20).build());

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
				.bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
		this.renderBudgetSlider.setMessage(Component.literal("Общий ресурс рендера: " + LostgladeClientSettings.renderBudgetPercent() + "%"));
		this.droneTiltStrengthSlider.setMessage(Component.literal("Сила наклона: " + percent(LostgladeClientSettings.droneTiltStrength()) + "%"));
	}

	private static int percent(double value) {
		return Math.clamp((int) Math.round(value * 100.0D), 0, 100);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
		graphics.drawCenteredString(this.font, "Камеры в приоритете; без активных камер ресурс получает Яндекс Карты.",
				this.width / 2, 32, 0xAAAAAA);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
