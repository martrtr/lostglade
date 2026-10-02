package com.lostglade.client;

import com.lostglade.client.maprender.YandexMapRenderClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Vanilla-style local Lostglade settings page. */
final class LostgladeSettingsScreen extends Screen {
	private final Screen parent;
	private Button volunteerButton;
	private AbstractSliderButton parallelCapturesSlider;
	private Button mapModeButton;
	private AbstractSliderButton mapMinFpsSlider;
	private AbstractSliderButton mapJobsSlider;
	private Button raceMenuButton;
	private Button droneTiltButton;
	private AbstractSliderButton droneTiltStrengthSlider;

	LostgladeSettingsScreen(Screen parent) {
		super(Component.literal("Lostglade"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int center = this.width / 2;
		int gap = 8;
		int columnWidth = Math.min(210, Math.max(130, (this.width - 36) / 2));
		int left = center - gap / 2 - columnWidth;
		int right = center + gap / 2;
		int top = Math.max(36, this.height / 2 - 72);

		this.volunteerButton = this.addRenderableWidget(Button.builder(cameraRendererLabel(), button -> {
			RendererBotVolunteerClient.setVolunteerRendererEnabled(!LostgladeClientSettings.isCameraRendererEnabled());
			RendererClientDiagnostics.event("SET", "Добровольный рендер камер: " + (LostgladeClientSettings.isCameraRendererEnabled() ? "включён" : "выключен"));
			refreshLabels();
		}).bounds(left, top, columnWidth, 20).build());

		this.parallelCapturesSlider = this.addRenderableWidget(new AbstractSliderButton(
				left, top + 28, columnWidth, 20, Component.empty(), (LostgladeClientSettings.maxParallelCaptures() - 1) / 3.0D
		) {
			@Override
			protected void updateMessage() {
				int limit = 1 + (int) Math.round(this.value * 3.0D);
				this.setMessage(cameraLimitLabel(limit));
			}

			@Override
			protected void applyValue() {
				int limit = 1 + (int) Math.round(this.value * 3.0D);
				this.value = (limit - 1) / 3.0D;
				LostgladeClientSettings.setMaxParallelCaptures(limit);
				RendererBotVolunteerClient.sendRendererHello();
				this.updateMessage();
			}
		});

		this.mapModeButton = this.addRenderableWidget(Button.builder(mapModeLabel(), button -> {
			LostgladeClientSettings.setMapRendererMode(LostgladeClientSettings.mapRendererMode().next());
			button.setMessage(mapModeLabel());
			YandexMapRenderClient.requestCapabilityRefresh();
		}).bounds(left, top + 56, columnWidth, 20).build());

		this.mapMinFpsSlider = this.addRenderableWidget(new AbstractSliderButton(
				left, top + 84, columnWidth, 20, Component.empty(), (LostgladeClientSettings.mapRendererMinFps() - 20) / 220.0D
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
				left, top + 112, columnWidth, 20, Component.empty(), (LostgladeClientSettings.mapRendererMaxJobsPerMinute() - 1) / 59.0D
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

		this.raceMenuButton = this.addRenderableWidget(Button.builder(raceMenuLabel(), button -> {
			LostgladeClientSettings.setRaceMenuEnabled(!LostgladeClientSettings.isRaceMenuEnabled());
			button.setMessage(raceMenuLabel());
		}).bounds(right, top, columnWidth, 20).build());

		this.droneTiltButton = this.addRenderableWidget(Button.builder(droneTiltLabel(), button -> {
			LostgladeClientSettings.setDroneTiltEnabled(!LostgladeClientSettings.isDroneTiltEnabled());
			button.setMessage(droneTiltLabel());
			this.droneTiltStrengthSlider.active = LostgladeClientSettings.isDroneTiltEnabled();
		}).bounds(right, top + 28, columnWidth, 20).build());

		this.droneTiltStrengthSlider = this.addRenderableWidget(new AbstractSliderButton(
				right, top + 56, columnWidth, 20, Component.empty(), LostgladeClientSettings.droneTiltStrength()
		) {
			@Override
			protected void updateMessage() {
				this.setMessage(droneTiltStrengthLabel((float) this.value));
			}

			@Override
			protected void applyValue() {
				LostgladeClientSettings.setDroneTiltStrength((float) this.value);
				this.value = LostgladeClientSettings.droneTiltStrength();
				this.updateMessage();
			}
		});
		this.droneTiltStrengthSlider.active = LostgladeClientSettings.isDroneTiltEnabled();

		this.addRenderableWidget(Button.builder(Component.literal("Renderer diagnostics / логи"), button ->
				this.minecraft.setScreen(new RendererDiagnosticsScreen(this))
		).bounds(right, top + 84, columnWidth, 20).build());

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
				.bounds(center - 100, this.height - 28, 200, 20).build());
		refreshLabels();
	}

	private void refreshLabels() {
		this.volunteerButton.setMessage(cameraRendererLabel());
		this.parallelCapturesSlider.setMessage(cameraLimitLabel(LostgladeClientSettings.maxParallelCaptures()));
		this.mapModeButton.setMessage(mapModeLabel());
		this.raceMenuButton.setMessage(raceMenuLabel());
		this.droneTiltButton.setMessage(droneTiltLabel());
		this.droneTiltStrengthSlider.setMessage(droneTiltStrengthLabel(LostgladeClientSettings.droneTiltStrength()));
		this.droneTiltStrengthSlider.active = LostgladeClientSettings.isDroneTiltEnabled();
	}

	private static Component cameraRendererLabel() {
		return Component.literal("Добровольный рендер камер: " + onOff(LostgladeClientSettings.isCameraRendererEnabled()));
	}

	private static Component cameraLimitLabel(int limit) {
		return Component.literal("GPU-потоки камер: " + limit);
	}

	private static Component raceMenuLabel() {
		return Component.literal("Меню рас: " + onOff(LostgladeClientSettings.isRaceMenuEnabled()));
	}

	private static Component droneTiltLabel() {
		return Component.literal("Наклон камеры дрона: " + onOff(LostgladeClientSettings.isDroneTiltEnabled()));
	}

	private static Component droneTiltStrengthLabel(float strength) {
		return Component.literal("Сила наклона дрона: " + Math.round(Math.clamp(strength, 0.0F, 1.0F) * 100.0F) + "%");
	}

	private static String onOff(boolean enabled) {
		return enabled ? "Вкл" : "Выкл";
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
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
		graphics.drawCenteredString(this.font, "Статус карт: " + humanStatus(YandexMapRenderClient.displayStatusReason()), this.width / 2, this.height - 42, 0xAAAAAA);
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
