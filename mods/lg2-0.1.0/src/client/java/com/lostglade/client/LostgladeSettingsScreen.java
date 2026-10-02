package com.lostglade.client;

import com.lostglade.client.maprender.YandexMapRenderClient;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** A short, local settings page with one shared render-budget control. */
final class LostgladeSettingsScreen extends Screen {
	private final Screen parent;
	private AbstractSliderButton renderBudgetSlider;
	private AbstractSliderButton droneTiltStrengthSlider;
	private int renderStatusTop;
	private StringWidget renderStatusTitle;
	private StringWidget renderStatusState;
	private StringWidget renderStatusJobs;
	private StringWidget renderStatusSpeed;
	private StringWidget renderStatusMaps;

	LostgladeSettingsScreen(Screen parent) {
		super(Component.literal("Lostglade"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int width = Math.min(360, this.width - 32);
		int left = (this.width - width) / 2;
		int top = Math.max(62, this.height / 2 - 92);
		int gap = 6;
		int toggleWidth = (width - gap) / 2;
		int row = top;

		this.addRenderableWidget(Checkbox.builder(
				Component.literal("Наклон дрона"), this.font
		).pos(left, row).maxWidth(toggleWidth).selected(LostgladeClientSettings.isDroneTiltEnabled())
				.onValueChange((checkbox, selected) -> {
					LostgladeClientSettings.setDroneTiltEnabled(selected);
					this.clearWidgets();
					this.init();
				}).build());

		this.addRenderableWidget(Checkbox.builder(
				Component.literal("Меню рас"), this.font
		).pos(left + toggleWidth + gap, row).maxWidth(toggleWidth).selected(LostgladeClientSettings.isRaceMenuEnabled())
				.onValueChange((checkbox, selected) -> LostgladeClientSettings.setRaceMenuEnabled(selected)).build());
		row += 30;

		if (LostgladeClientSettings.isDroneTiltEnabled()) {
			this.droneTiltStrengthSlider = this.addRenderableWidget(new AbstractSliderButton(
					left, row, width, 20, Component.empty(), LostgladeClientSettings.droneTiltStrength()
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
			this.droneTiltStrengthSlider.setMessage(Component.literal("Сила наклона: " + percent(LostgladeClientSettings.droneTiltStrength()) + "%"));
			row += 38;
		}

		this.renderBudgetSlider = this.addRenderableWidget(new AbstractSliderButton(
				left, row + 14, width, 20, Component.empty(), LostgladeClientSettings.renderBudgetPercent() / 100.0D
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
		this.renderStatusTop = row + 44;
		this.renderStatusTitle = this.addStatusLine(left, this.renderStatusTop, width);
		this.renderStatusState = this.addStatusLine(left, this.renderStatusTop + 12, width);
		this.renderStatusJobs = this.addStatusLine(left, this.renderStatusTop + 24, width);
		this.renderStatusSpeed = this.addStatusLine(left, this.renderStatusTop + 36, width);
		this.renderStatusMaps = this.addStatusLine(left, this.renderStatusTop + 48, width);
		this.refreshRenderStatus();

		this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.translatable("gui.done"), button -> this.onClose())
				.bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
		this.renderBudgetSlider.setMessage(Component.literal("Общий ресурс рендера: " + LostgladeClientSettings.renderBudgetPercent() + "%"));
	}

	private static int percent(double value) {
		return Math.clamp((int) Math.round(value * 100.0D), 0, 100);
	}

	@Override
	public void tick() {
		super.tick();
		this.refreshRenderStatus();
	}

	private void refreshRenderStatus() {
		RendererClientDiagnostics.RenderStatus status = RendererClientDiagnostics.status();
		RendererBotRenderEnvironmentGuard.Compatibility environment = RendererBotRenderEnvironmentGuard.inspect(this.minecraft);
		this.renderStatusTitle.setMessage(Component.literal("Статус рендера"));
		String contribution = !LostgladeClientSettings.isRenderContributionEnabled()
				? "Помощь рендеру: выключена"
				: !environment.compatible()
						? "Помощь рендеру: заблокирована (" + environmentDescription(environment.reason()) + ")"
						: "Помощь рендеру: включена  •  " + (RendererBotGpuCaptureBackend.isAvailable() ? "GPU" : "CPU-кодирование");
		this.renderStatusState.setMessage(gray(contribution));
		this.renderStatusJobs.setMessage(gray(
				"Трансляции: " + status.activeStreams() + "  •  Фото: " + status.activePhotos() + "  •  Запись: " + status.activeVideos()
		));
		this.renderStatusSpeed.setMessage(gray(
				"Чанки: " + RendererBotShadowWorldManager.loadedChunkCount() + "  •  Камеры: " + status.activeCameraJobs() + "  •  " + status.cameraFramesPerSecond() + " кадр/с"
		));
		String mapStatus = status.mapActive() ? status.mapStage() : status.mapEligible() ? "ожидание" : mapReason(status.mapReason());
		this.renderStatusMaps.setMessage(gray("Карты: " + mapStatus));
	}

	private static Component gray(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	private StringWidget addStatusLine(int x, int y, int width) {
		// StringWidget's short constructor is centred and does not retain the
		// requested left edge. Use its explicit rectangle so every line stays in
		// the settings column on every GUI scale.
		StringWidget widget = new StringWidget(x, y, width, this.font.lineHeight, Component.empty(), this.font);
		widget.setX(x);
		widget.setY(y);
		return this.addRenderableOnly(widget);
	}

	private static String environmentDescription(String reason) {
		if (reason != null && reason.startsWith("third-party-client-mod:")) {
			return "сторонний мод";
		}
		if (reason != null && reason.startsWith("resource-pack-active:")) {
			return "ресурспак";
		}
		return switch (reason) {
			case "shader-pack-active", "shader-state-unknown" -> "шейдеры";
			default -> reason;
		};
	}

	private static String mapReason(String reason) {
		return switch (reason) {
			case "shader-pack-active" -> "выключите шейдеры";
			case "resource-profile-mismatch" -> "профили ресурсов не совпали";
			case "player-volunteers-disabled" -> "участие игроков выключено";
			case "disabled-by-client" -> "выключены";
			case "waiting-for-server", "not-connected" -> "ожидание сервера";
			default -> reason;
		};
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
