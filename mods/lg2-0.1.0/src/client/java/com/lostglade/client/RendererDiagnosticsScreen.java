package com.lostglade.client;

import com.lostglade.client.maprender.YandexMapRenderClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Live local diagnostics for camera and Yandex-map volunteer render work. */
final class RendererDiagnosticsScreen extends Screen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int LINE_HEIGHT = 11;
    private static final int SCROLL_STEP = 33;

    private final Screen parent;
    private int scrollOffset;
    private int lastContentHeight;
    private boolean openedLogged;

    RendererDiagnosticsScreen(Screen parent) {
        super(Component.literal("Lostglade — renderer diagnostics"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int bottom = this.height - 28;
        int center = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Очистить журнал"), button -> {
            RendererClientDiagnostics.clearEvents();
            this.scrollOffset = 0;
        }).bounds(center - 154, bottom, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(center + 4, bottom, 150, 20).build());
        if (!this.openedLogged) {
            this.openedLogged = true;
            RendererClientDiagnostics.event("UI", "Открыта страница диагностики");
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xE0181818);
        RendererClientDiagnostics.Snapshot snapshot = RendererClientDiagnostics.snapshot();
        int center = this.width / 2;
        int left = Math.max(12, center - 240);
        int right = Math.min(this.width - 12, center + 240);
        int y = 12;

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
        y += 15;

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

        int logTop = logTop();
        int logBottom = logBottom();
        graphics.drawString(this.font, "Журнал событий — новые сверху, прокрутка колёсиком", left, logTop - 14, 0xFFFFFF);
        graphics.fill(left - 3, logTop - 3, right + 3, logBottom + 3, 0x70101010);

        List<RendererClientDiagnostics.Event> events = RendererClientDiagnostics.events(0, Integer.MAX_VALUE);
        int contentWidth = Math.max(80, right - left - 14);
        int contentHeight = 0;
        for (RendererClientDiagnostics.Event event : events) {
            String line = "[" + TIME.format(Instant.ofEpochMilli(event.timestampMs())) + "] [" + event.source() + "] " + event.message();
            contentHeight += Math.max(1, this.font.split(Component.literal(line), contentWidth).size()) * LINE_HEIGHT + 2;
        }
        if (events.isEmpty()) contentHeight = LINE_HEIGHT;
        this.lastContentHeight = contentHeight;
        this.scrollOffset = Math.clamp(this.scrollOffset, 0, maxScroll());

        graphics.enableScissor(left, logTop, right, logBottom);
        int drawY = logTop - this.scrollOffset;
        if (events.isEmpty()) {
            graphics.drawString(this.font, "Журнал пока пуст.", left, drawY, 0x777777);
        } else {
            for (RendererClientDiagnostics.Event event : events) {
                String line = "[" + TIME.format(Instant.ofEpochMilli(event.timestampMs())) + "] [" + event.source() + "] " + event.message();
                List<FormattedCharSequence> wrapped = this.font.split(Component.literal(line), contentWidth);
                for (FormattedCharSequence part : wrapped) {
                    graphics.drawString(this.font, part, left, drawY, 0xBDBDBD);
                    drawY += LINE_HEIGHT;
                }
                drawY += 2;
            }
        }
        graphics.disableScissor();
        renderScrollbar(graphics, right - 5, logTop, logBottom);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderScrollbar(GuiGraphics graphics, int x, int top, int bottom) {
        int viewport = Math.max(1, bottom - top);
        int max = maxScroll();
        if (max <= 0) return;
        graphics.fill(x, top, x + 3, bottom, 0x70444444);
        int thumbHeight = Math.max(16, viewport * viewport / Math.max(viewport, this.lastContentHeight));
        int travel = Math.max(1, viewport - thumbHeight);
        int thumbTop = top + (int) Math.round((double) this.scrollOffset / max * travel);
        graphics.fill(x, thumbTop, x + 3, thumbTop + thumbHeight, 0xFFD0D0D0);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int left = Math.max(12, this.width / 2 - 240);
        int right = Math.min(this.width - 12, this.width / 2 + 240);
        if (mouseX >= left && mouseX <= right && mouseY >= logTop() && mouseY <= logBottom()) {
            int delta = (int) Math.round(verticalAmount * SCROLL_STEP);
            if (delta != 0) {
                this.scrollOffset = Math.clamp(this.scrollOffset - delta, 0, maxScroll());
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private int logTop() {
        return Math.min(112, Math.max(94, this.height / 2 - 18));
    }

    private int logBottom() {
        return Math.max(logTop() + 24, this.height - 38);
    }

    private int maxScroll() {
        return Math.max(0, this.lastContentHeight - Math.max(1, logBottom() - logTop()));
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
