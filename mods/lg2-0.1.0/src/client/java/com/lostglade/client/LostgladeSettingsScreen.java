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
	private static final int CONTENT_WIDTH = 200;
	private static final int MIN_HORIZONTAL_MARGIN = 16;
	private static final int TOGGLE_GAP = 8;
	private static final int CONTROL_HEIGHT = 20;
	private static final int CONTROL_GAP = 4;
	private static final int SECTION_GAP = 6;
	private static final int STATUS_LINE_STEP = 12;

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
		int contentWidth = Math.min(CONTENT_WIDTH, Math.max(1, this.width - MIN_HORIZONTAL_MARGIN * 2));
		int contentLeft = (this.width - contentWidth) / 2;
		int top = Math.max(48, this.height / 2 - 82);
		int row = top;

		Component droneTiltLabel = Component.translatable("screen.lg2.settings.drone_tilt");
		Component raceMenuLabel = Component.translatable("screen.lg2.settings.race_menu");
		int toggleGap = Math.min(TOGGLE_GAP, Math.max(2, contentWidth / 20));
		int leftToggleWidth = Math.max(1, (contentWidth - toggleGap) / 2);
		int rightToggleWidth = Math.max(1, contentWidth - toggleGap - leftToggleWidth);

		Checkbox droneTilt = Checkbox.builder(droneTiltLabel, this.font)
				.pos(contentLeft, row).maxWidth(leftToggleWidth).selected(LostgladeClientSettings.isDroneTiltEnabled())
				.onValueChange((checkbox, selected) -> {
					LostgladeClientSettings.setDroneTiltEnabled(selected);
					this.clearWidgets();
					this.init();
				}).build();
		Checkbox raceMenu = Checkbox.builder(raceMenuLabel, this.font)
				.pos(contentLeft + leftToggleWidth + toggleGap, row).maxWidth(rightToggleWidth).selected(LostgladeClientSettings.isRaceMenuEnabled())
				.onValueChange((checkbox, selected) -> LostgladeClientSettings.setRaceMenuEnabled(selected)).build();
		this.addRenderableWidget(droneTilt);
		this.addRenderableWidget(raceMenu);
		row += CONTROL_HEIGHT + CONTROL_GAP;

		if (LostgladeClientSettings.isDroneTiltEnabled()) {
			this.droneTiltStrengthSlider = this.addRenderableWidget(new AbstractSliderButton(
					contentLeft, row, contentWidth, CONTROL_HEIGHT, Component.empty(), LostgladeClientSettings.droneTiltStrength()
			) {
				@Override
				protected void updateMessage() { this.setMessage(Component.translatable("screen.lg2.settings.drone_tilt_strength", percent(this.value))); }
				@Override
				protected void applyValue() { LostgladeClientSettings.setDroneTiltStrength((float) this.value); this.value = LostgladeClientSettings.droneTiltStrength(); this.updateMessage(); }
			});
			this.droneTiltStrengthSlider.setMessage(Component.translatable("screen.lg2.settings.drone_tilt_strength", percent(LostgladeClientSettings.droneTiltStrength())));
			row += CONTROL_HEIGHT + SECTION_GAP;
		}

		this.renderBudgetSlider = this.addRenderableWidget(new AbstractSliderButton(
				contentLeft, row, contentWidth, CONTROL_HEIGHT, Component.empty(), LostgladeClientSettings.renderBudgetPercent() / 100.0D
		) {
			@Override
			protected void updateMessage() { this.setMessage(Component.translatable("screen.lg2.settings.render_budget", percent(this.value))); }
			@Override
			protected void applyValue() { boolean wasEnabled = LostgladeClientSettings.isRenderContributionEnabled(); int budget = percent(this.value); this.value = budget / 100.0D; LostgladeClientSettings.setRenderBudgetPercent(budget); RendererBotVolunteerClient.onRenderBudgetChanged(wasEnabled); YandexMapRenderClient.requestCapabilityRefresh(); this.updateMessage(); }
		});
		this.renderStatusTop = row + CONTROL_HEIGHT + CONTROL_GAP;
		this.renderStatusTitle = this.addStatusLine(contentLeft, this.renderStatusTop, contentWidth);
		this.renderStatusState = this.addStatusLine(contentLeft, this.renderStatusTop + STATUS_LINE_STEP, contentWidth);
		this.renderStatusJobs = this.addStatusLine(contentLeft, this.renderStatusTop + STATUS_LINE_STEP * 2, contentWidth);
		this.renderStatusSpeed = this.addStatusLine(contentLeft, this.renderStatusTop + STATUS_LINE_STEP * 3, contentWidth);
		this.renderStatusMaps = this.addStatusLine(contentLeft, this.renderStatusTop + STATUS_LINE_STEP * 4, contentWidth);
		this.refreshRenderStatus();

		this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.translatable("gui.done"), button -> this.onClose())
				.bounds(contentLeft, this.height - 28, contentWidth, 20).build());
		this.renderBudgetSlider.setMessage(Component.translatable("screen.lg2.settings.render_budget", LostgladeClientSettings.renderBudgetPercent()));
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
		this.renderStatusTitle.setMessage(Component.translatable("screen.lg2.settings.render_status"));
		Component contribution = !LostgladeClientSettings.isRenderContributionEnabled()
				? Component.translatable("screen.lg2.settings.contribution.off")
				: !environment.compatible()
						? Component.translatable("screen.lg2.settings.contribution.blocked", environmentDescription(environment.reason()))
						: Component.translatable("screen.lg2.settings.contribution.on", Component.translatable(
								RendererBotGpuCaptureBackend.isAvailable() ? "screen.lg2.settings.backend.gpu" : "screen.lg2.settings.backend.cpu"
						));
		this.renderStatusState.setMessage(gray(contribution));
		this.renderStatusJobs.setMessage(gray(Component.translatable(
				"screen.lg2.settings.jobs", status.activeStreams(), status.activePhotos(), status.activeVideos()
		)));
		this.renderStatusSpeed.setMessage(gray(Component.translatable(
				"screen.lg2.settings.speed", RendererBotShadowWorldManager.loadedChunkCount(), status.activeCameraJobs(), status.cameraFramesPerSecond()
		)));
		Component mapStatus = status.mapActive()
				? Component.literal(status.mapStage())
				: status.mapEligible() ? Component.translatable("screen.lg2.settings.map.waiting") : mapReason(status.mapReason());
		this.renderStatusMaps.setMessage(gray(Component.translatable("screen.lg2.settings.maps", mapStatus)));
	}

	private static Component gray(Component text) {
		return text.copy().withStyle(ChatFormatting.GRAY);
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

	private static Component environmentDescription(String reason) {
		if (reason != null && reason.startsWith("third-party-client-mod:")) {
			return Component.translatable("screen.lg2.settings.environment.third_party_mod");
		}
		if (reason != null && reason.startsWith("resource-pack-active:")) {
			return Component.translatable("screen.lg2.settings.environment.resource_pack");
		}
		return switch (reason) {
			case "shader-pack-active", "shader-state-unknown" -> Component.translatable("screen.lg2.settings.environment.shaders");
			case null -> Component.translatable("screen.lg2.settings.unknown");
			default -> Component.literal(reason);
		};
	}

	private static Component mapReason(String reason) {
		return switch (reason) {
			case "shader-pack-active" -> Component.translatable("screen.lg2.settings.map.disable_shaders");
			case "resource-profile-mismatch" -> Component.translatable("screen.lg2.settings.map.resource_mismatch");
			case "player-volunteers-disabled" -> Component.translatable("screen.lg2.settings.map.volunteers_disabled");
			case "disabled-by-client" -> Component.translatable("screen.lg2.settings.map.disabled");
			case "waiting-for-server", "not-connected" -> Component.translatable("screen.lg2.settings.map.waiting_server");
			case null -> Component.translatable("screen.lg2.settings.unknown");
			default -> Component.literal(reason);
		};
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
