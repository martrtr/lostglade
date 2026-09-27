package com.lostglade.client;

import com.lostglade.client.maprender.YandexMapRenderClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Live local diagnostics for camera and Yandex-map volunteer render work. */
final class RendererDiagnosticsScreen extends Screen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int EVENTS_PER_PAGE = 11;

    private final Screen parent;
    private int eventOffset;
    private Button newerButton;
    private Button olderButton;

    RendererDiagnosticsScreen(Screen parent) {
        super(Component.literal("Lostglade — renderer diagnostics"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int bottom = this.height - 28;
        int center = this.width / 2;
        this.newerButton = this.addRenderableWidget(Button.builder(Component.literal("Новее"), button -> {
            this.eventOffset = Math.max(0, this.eventOffset - EVENTS_PER_PAGE);
            refreshButtons();
        }).bounds(center - 154, bottom, 72, 20).build());
        this.olderButton = this.addRenderableWidget(Button.builder(Component.literal("Старше"), button -> {
            RendererClientDiagnostics.Snapshot snapshot = RendererClientDiagnostics.snapshot();
            int maxOffset = Math.max(0, snapshot.eventCount() - 1);
            this.eventOffset = Math.min(maxOffset, this.eventOffset + EVENTS_PER_PAGE);
            refreshButtons();
        }).bounds(center - 78, bottom, 72, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Очистить"), button -> {
            RendererClientDiagnostics.clearEvents();
            this.eventOffset = 0;
            refreshButtons();
        }).bounds(center - 2, bottom, 72, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(center + 74, bottom, 80, 20).build());
        refreshButtons();
    }

    @Override
    public void tick() {
        if (this.eventOffset == 0) refreshButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Do not call Screen.renderBackground() here. On 1.21.11 another screen/mod
        // may already have consumed the single blur pass for this frame, which makes
        // a second blur throw "Can only blur once per frame".
        graphics.fill(0, 0, this.width, this.height, 0xE0181818);
        RendererClientDiagnostics.Snapshot snapshot = RendererClientDiagnostics.snapshot();
        int center = this.width / 2;
        int left = Math.max(12, center - 210);
        int y = 14;

        graphics.drawCenteredString(this.font, this.title, center, y, 0xFFFFFF);
        y += 18;

        String cameraMode = LostgladeClientSettings.isCameraRendererEnabled()
                ? "ВКЛ, лимит " + LostgladeClientSettings.maxParallelCaptures()
                : "ВЫКЛ";
        graphics.drawString(this.font, "Камеры: " + cameraMode + " | активно " + snapshot.activeCameraJobs()
                + " (фото " + snapshot.activePhotos() + ", live " + snapshot.activeStreams() + ", видео " + snapshot.activeVideos() + ")", left, y, 0xE6E6E6);
        y += 12;
        graphics.drawString(this.font, "  принято " + snapshot.cameraAccepted() + " | готово " + snapshot.cameraCompleted()
                + " | ошибок " + snapshot.cameraFailed(), left, y, 0xAFAFAF);
        y += 16;

        String mapMode = switch (LostgladeClientSettings.mapRendererMode()) {
            case OFF -> "OFF";
            case IDLE_ONLY -> "IDLE_ONLY";
            case ALWAYS -> "ALWAYS";
        };
        YandexMapRenderClient.WorkerStatus worker = YandexMapRenderClient.status();
        graphics.drawString(this.font, "Карты: " + mapMode + " | " + (worker.eligible() ? "eligible" : "not eligible")
                + " | " + YandexMapRenderClient.displayStatusReason(), left, y, 0xE6E6E6);
        y += 12;
        graphics.drawString(this.font, "  offers " + snapshot.mapOffers() + " | accepted " + snapshot.mapAccepted()
                + " | rendered " + snapshot.mapRendered() + " | failed " + snapshot.mapFailed()
                + " | rejected " + snapshot.mapRejected() + " | за 60с " + snapshot.mapAcceptedLastMinute(), left, y, 0xAFAFAF);
        y += 12;
        String active = snapshot.activeMapJob().isEmpty()
                ? "нет"
                : snapshot.activeMapJob() + " tile " + snapshot.activeMapTile() + " / " + snapshot.activeMapStage();
        graphics.drawString(this.font, "  текущий map job: " + active, left, y, 0xAFAFAF);
        y += 17;

        graphics.drawString(this.font, "Последние события (новые сверху):", left, y, 0xFFFFFF);
        y += 13;
        List<RendererClientDiagnostics.Event> events = RendererClientDiagnostics.events(this.eventOffset, EVENTS_PER_PAGE);
        for (RendererClientDiagnostics.Event event : events) {
            String line = "[" + TIME.format(Instant.ofEpochMilli(event.timestampMs())) + "] [" + event.source() + "] " + event.message();
            graphics.drawString(this.font, trimToWidth(line, Math.max(80, this.width - left - 12)), left, y, 0xBDBDBD);
            y += 11;
        }
        if (events.isEmpty()) {
            graphics.drawString(this.font, "Журнал пока пуст.", left, y, 0x777777);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String trimToWidth(String value, int width) {
        if (this.font.width(value) <= width) return value;
        String suffix = "…";
        int suffixWidth = this.font.width(suffix);
        int length = value.length();
        while (length > 0 && this.font.width(value.substring(0, length)) + suffixWidth > width) length--;
        return value.substring(0, Math.max(0, length)) + suffix;
    }

    private void refreshButtons() {
        if (this.newerButton == null || this.olderButton == null) return;
        RendererClientDiagnostics.Snapshot snapshot = RendererClientDiagnostics.snapshot();
        this.newerButton.active = this.eventOffset > 0;
        this.olderButton.active = this.eventOffset + EVENTS_PER_PAGE < snapshot.eventCount();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
