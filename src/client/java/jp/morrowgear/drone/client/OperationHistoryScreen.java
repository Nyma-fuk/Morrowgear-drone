package jp.morrowgear.drone.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import jp.morrowgear.drone.OperationEvent;
import jp.morrowgear.drone.OperationRadioSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A stable snapshot prevents incoming reports from moving the row being read. */
public final class OperationHistoryScreen extends Screen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Screen parent;
    private List<OperationEvent> events = List.of();
    private boolean importantOnly;
    private int page;
    private Button previous, next;

    public OperationHistoryScreen(Screen parent) {
        super(Component.literal("通信履歴"));
        this.parent = parent;
        refresh();
    }

    private void refresh() { events = List.copyOf(OperationCommunicationsClient.history()); page = 0; }
    private List<OperationEvent> visible() {
        return events.stream().filter(e -> !importantOnly || e.kind().important())
            .sorted(java.util.Comparator.comparingLong(OperationEvent::occurredAtMillis).reversed()).toList();
    }
    private int capacity() { return Math.max(1, (height - 105) / 38); }

    @Override protected void init() {
        int w = Math.max(60, Math.min(110, (width - 40) / 3));
        addRenderableWidget(Button.builder(Component.literal(importantOnly ? "重要な報告" : "すべての報告"), b -> {
            importantOnly = !importantOnly; page = 0; rebuildWidgets();
        }).bounds(12, 30, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("更新"), b -> refresh()).bounds(18 + w, 30, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal(OperationCommunicationsConfig.current().subtitles() ? "字幕 ON" : "字幕 OFF"), b -> {
            var current = OperationCommunicationsConfig.current();
            OperationCommunicationsConfig.save(new OperationRadioSettings(current.voiceMode(), current.volume(), !current.subtitles()));
            rebuildWidgets();
        }).bounds(24 + w * 2, 30, w, 20).build());
        previous = addRenderableWidget(Button.builder(Component.literal("前へ"), b -> page = Math.max(0, page - 1))
            .bounds(12, height - 28, 60, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal("次へ"), b -> page++)
            .bounds(78, height - 28, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("戻る"), b -> onClose())
            .bounds(width - 72, height - 28, 60, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xF014181B);
        g.text(font, title, 12, 12, HmiArt.TEXT);
        var rows = visible();
        int pages = Math.max(1, (rows.size() + capacity() - 1) / capacity());
        page = Math.min(page, pages - 1);
        previous.active = page > 0; next.active = page + 1 < pages;
        g.text(font, (page + 1) + " / " + pages, 148, height - 22, HmiArt.MUTED);
        if (rows.isEmpty()) g.text(font, "通信記録はありません", 16, 68, HmiArt.MUTED);
        for (int i = 0; i < capacity() && page * capacity() + i < rows.size(); i++) {
            var event = rows.get(page * capacity() + i);
            int y = 59 + i * 38;
            int color = event.kind().important() ? HmiArt.AMBER : HmiArt.CYAN;
            g.fill(12, y, 14, y + 31, color);
            var message = Component.translatable(event.messageKey(), event.source(), event.count());
            var lines = font.split(message, Math.max(40, width - 40));
            for (int line = 0; line < Math.min(2, lines.size()); line++)
                g.text(font, lines.get(line), 20, y + line * 10, HmiArt.TEXT);
            String context = TIME.format(Instant.ofEpochMilli(event.occurredAtMillis())) + "  "
                + event.x() + ", " + event.y() + ", " + event.z() + "  " + event.dimension();
            g.text(font, font.plainSubstrByWidth(context, Math.max(40, width - 40)), 20, y + 22, HmiArt.MUTED);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
