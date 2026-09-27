package com.lostglade.client;

import com.lostglade.client.maprender.YandexMapRenderClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minimal vanilla-style settings page, available from Options → Lostglade. */
final class LostgladeSettingsScreen extends Screen {
	private final Screen parent;
	private AbstractSliderButton parallelCapturesSlider;
	private Button mapModeButton;
	private AbstractSliderButton mapMinFpsSlider;
	private AbstractSliderButton mapJobsSlider;

	LostgladeSettingsScreen(Screen parent) {
		super(Component.literal("Lostglade"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - 100;
		int y = this.height / 2 - 72;
		this.parallelCapturesSlider = this.addRenderableWidget(new AbstractSliderButton(
				x, y, 200, 20, Component.empty(), LostgladeClientSettings.maxParallelCaptures() / 4.0D
		) {
			@Override
			protected void updateMessage() {
				int limit = (int) Math.round(this.value * 4.0D);
				this.setMessage(cameraLimitLabel(limit));
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

		this.mapModeButton = this.addRenderableWidget(Button.builder(mapModeLabel(), button -> {
			LostgladeClientSettings.setMapRendererMode(LostgladeClientSettings.mapRendererMode().next());
			button.setMessage(mapModeLabel());
			YandexMapRenderClient.requestCapabilityRefresh();
		}).bounds(x, y + 28, 200, 20).build());

		this.mapMinFpsSlider = this.addRenderableWidget(new AbstractSliderButton(
				x, y + 56, 200, 20, Component.empty(), (LostgladeClientSettings.mapRendererMinFps() - 20) / 220.0D
		) {
			@Override
			protected void updateMessage() {
				int fps = 20 + (int) Math.round(this.value * 220.0D);
				this.setMessage(Component.literal("Карты: минимум FPS " + fps));
			}

			@Override
			protected void applyValue() {
				int fps = 20 + (int) Math.round(this.value * 220.0D);
				LostgladeClientSettings.setMapRendererMinFps(fps);
				YandexMapRenderClient.requestCapabilityRefresh();
				this.updateMessage();
			}
		});

		this.mapJobsSlider = this.addRenderableWidget(new AbstractSliderButton(
				x, y + 84, 200, 20, Component.empty(), (LostgladeClientSettings.mapRendererMaxJobsPerMinute() - 1) / 59.0D
		) {
			@Override
			protected void updateMessage() {
				int jobs = 1 + (int) Math.round(this.value * 59.0D);
				this.setMessage(Component.literal("Карты: максимум заданий/мин " + jobs));
			}

			@Override
			protected void applyValue() {
				int jobs = 1 + (int) Math.round(this.value * 59.0D);
				LostgladeClientSettings.setMapRendererMaxJobsPerMinute(jobs);
				YandexMapRenderClient.requestCapabilityRefresh();
				this.updateMessage();
			}
		});

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
				.bounds(x, y + 120, 200, 20).build());
		refreshLabels();
	}

	private void refreshLabels() {
		this.parallelCapturesSlider.setMessage(cameraLimitLabel(LostgladeClientSettings.maxParallelCaptures()));
		this.mapModeButton.setMessage(mapModeLabel());
	}

	private static Component cameraLimitLabel(int limit) {
		return Component.literal(limit <= 0 ? "Рендер камер GPU: Выкл" : "GPU-потоки камер: " + limit);
	}

	private static Component mapModeLabel() {
		String value = switch (LostgladeClientSettings.mapRendererMode()) {
			case OFF -> "Выкл";
			case IDLE_ONLY -> "Только когда клиент свободен";
			case ALWAYS -> "Всегда";
		};
		return Component.literal("Рендер Яндекс Карт: " + value);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 104, 0xFFFFFF);
		graphics.drawCenteredString(this.font, "Статус карт: " + humanStatus(YandexMapRenderClient.displayStatusReason()), this.width / 2, this.height / 2 + 78, 0xAAAAAA);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	private static String humanStatus(String reason) {
		return switch (reason) {
			case "ready" -> "готов к заданиям";
			case "disabled-by-client" -> "отключено";
			case "shader-pack-active" -> "активны шейдеры";
			case "shader-state-unknown" -> "не удалось проверить шейдеры";
			case "profiling-resources" -> "проверка ресурспаков…";
			case "resource-profile-mismatch" -> "ресурспаки отличаются от канонических";
			case "player-volunteers-disabled" -> "сервер запретил добровольцев";
			case "waiting-for-server", "not-connected" -> "ожидание сервера";
			case "resource-profile-error" -> "ошибка профиля ресурсов";
			default -> reason == null || reason.isBlank() ? "неизвестно" : reason;
		};
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
