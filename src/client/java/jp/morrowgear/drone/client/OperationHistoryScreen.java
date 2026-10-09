package jp.morrowgear.drone.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import jp.morrowgear.drone.OperationEvent;
import jp.morrowgear.drone.OperationRadioSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
    private String query = "";
    private String notice = "";
    private final java.util.List<Button> targetButtons = new java.util.ArrayList<>();

    public OperationHistoryScreen(Screen parent) {
        super(Component.literal("報告履歴"));
        this.parent = parent;
        refresh();
    }

    private void refresh() { events = List.copyOf(OperationCommunicationsClient.history()); page = 0; }
    private List<OperationEvent> visible() {
        return events.stream().filter(e -> !importantOnly || e.kind().important())
            .filter(e -> (e.source() + " " + Component.translatable(e.messageKey(), e.source(), e.count()).getString())
                .toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT)))
            .sorted(java.util.Comparator.comparingLong(OperationEvent::occurredAtMillis).reversed()).toList();
    }
    private int capacity() { return Math.max(1, (height - 135) / 38); }

    @Override protected void init() {
        targetButtons.clear();
        int w = Math.max(60, Math.min(110, (width - 40) / 3));
        addRenderableWidget(ControllerButton.hmi(Component.literal(importantOnly ? "重要な報告" : "すべての報告"), b -> {
            importantOnly = !importantOnly; page = 0; rebuildWidgets();
        }).bounds(12, 30, w, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal("更新"), b -> refresh()).bounds(18 + w, 30, w, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal(OperationCommunicationsConfig.current().subtitles() ? "字幕 ON" : "字幕 OFF"), b -> {
            var current = OperationCommunicationsConfig.current();
            OperationCommunicationsConfig.save(new OperationRadioSettings(current.voiceMode(), current.volume(), !current.subtitles()));
            rebuildWidgets();
        }).bounds(24 + w * 2, 30, w, 20).build());
        previous = addRenderableWidget(ControllerButton.hmi(Component.literal("前へ"), b -> page = Math.max(0, page - 1))
            .bounds(12, height - 28, 60, 20).build());
        next = addRenderableWidget(ControllerButton.hmi(Component.literal("次へ"), b -> page++)
            .bounds(78, height - 28, 60, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal("戻る"), b -> onClose())
            .bounds(width - 72, height - 28, 60, 20).build());
        EditBox search = addRenderableWidget(new EditBox(font, 12, 55, Math.max(60, width - 24), 20,
            Component.literal("対象・報告内容を検索")));
        search.setHint(Component.literal("対象・報告内容を検索"));
        search.setValue(query);
        search.setResponder(value -> { query = value; page = 0; });
        for (int i = 0; i < capacity(); i++) {
            final int row = i;
            targetButtons.add(addRenderableWidget(ControllerButton.hmi(Component.literal("対象へ"), b -> {
                var rows = visible();
                int index = page * capacity() + row;
                if (index >= rows.size()) return;
                var event = rows.get(index);
                if (parent instanceof TacticalScreen tactical && tactical.focusReport(event.sourceUnit(), event.dimension())) onClose();
                else notice = "対象は未受信、別次元、または操作権限外です";
            }).bounds(width - 65, 83 + i * 38, 53, 20).build()));
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xFF121514);
        g.fill(0, 0, width, 26, 0xFF1A1E1C);
        g.text(font, title, 12, 12, HmiArt.TEXT);
        var rows = visible();
        int pages = Math.max(1, (rows.size() + capacity() - 1) / capacity());
        page = Math.min(page, pages - 1);
        previous.active = page > 0; next.active = page + 1 < pages;
        g.text(font, (page + 1) + " / " + pages, 148, height - 22, HmiArt.MUTED);
        long incoming = OperationCommunicationsClient.history().stream()
            .filter(event -> events.stream().noneMatch(old -> old.id().equals(event.id()))).count();
        String status = notice.isBlank() ? "新着 " + incoming + "件" : notice;
        g.text(font, font.plainSubstrByWidth(status, Math.max(40, width - 120)), 106, 12, 0xFF99A59D);
        for (int i = 0; i < targetButtons.size(); i++)
            targetButtons.get(i).visible = page * capacity() + i < rows.size();
        if (rows.isEmpty()) g.text(font, "該当する報告はありません", 16, 87, 0xFF99A59D);
        for (int i = 0; i < capacity() && page * capacity() + i < rows.size(); i++) {
            var event = rows.get(page * capacity() + i);
            int y = 83 + i * 38;
            int color = event.kind().important() ? 0xFFE7BD79 : 0xFF7CDEC8;
            g.fill(12, y, 14, y + 31, color);
            var message = Component.translatable(event.messageKey(), event.source(), event.count());
            var lines = font.split(message, Math.max(40, width - 96));
            for (int line = 0; line < Math.min(2, lines.size()); line++)
                g.text(font, lines.get(line), 20, y + line * 10, HmiArt.TEXT);
            String context = TIME.format(Instant.ofEpochMilli(event.occurredAtMillis())) + "  "
                + event.x() + ", " + event.y() + ", " + event.z() + "  " + ControllerDisplayText.dimensionName(event.dimension());
            g.text(font, font.plainSubstrByWidth(context, Math.max(40, width - 40)), 20, y + 22, 0xFF99A59D);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
