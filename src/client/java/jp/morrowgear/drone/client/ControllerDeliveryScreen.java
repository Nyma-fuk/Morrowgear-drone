package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Snapshot inspection and local map navigation only; no delivery commands. */
final class ControllerDeliveryScreen extends Screen {
    record Row(UUID identity, String name, String status, String cargo, String unavailable) {}
    record Snapshot(String status, List<Row> rows) {
        Snapshot { rows = List.copyOf(rows); }
    }
    private final Screen parent;
    private final Supplier<Snapshot> source;
    private final Function<UUID, String> unavailable;
    private final Predicate<UUID> jump;
    private final List<Button> buttons = new ArrayList<>();
    private List<Row> displayed = List.of();
    private int page;
    private String notice = "";
    private Button previous, next;

    ControllerDeliveryScreen(Screen parent, Supplier<Snapshot> source,
            Function<UUID, String> unavailable, Predicate<UUID> jump) {
        super(Component.literal("個別配送 / 地図で機体を確認"));
        this.parent = parent; this.source = source; this.unavailable = unavailable; this.jump = jump;
    }
    private int capacity() { return Math.max(1, (height - 114) / 64); }
    @Override protected void init() {
        buttons.clear();
        for (int i = 0; i < capacity(); i++) {
            int slot = i;
            buttons.add(addRenderableWidget(ControllerButton.hmi(Component.literal("地図へ"), b -> {
                if (slot >= displayed.size()) return;
                UUID identity = displayed.get(slot).identity();
                if (source.get().rows().stream().noneMatch(row -> row.identity().equals(identity))) {
                    notice = "配送情報が更新・期限切れになりました"; return;
                }
                String reason = unavailable.apply(identity);
                if (!reason.isEmpty()) { notice = reason; return; }
                if (jump.test(identity)) minecraft.setScreenAndShow(parent);
                else notice = "機体を確認できませんでした / 再受信を待ってください";
            }).bounds(width - 78, 52 + i * 64, 66, 20).build()));
        }
        previous = addRenderableWidget(ControllerButton.hmi(Component.literal("前へ"), b -> page = Math.max(0, page - 1))
            .bounds(12, height - 30, 54, 20).build());
        next = addRenderableWidget(ControllerButton.hmi(Component.literal("次へ"), b -> page++)
            .bounds(72, height - 30, 54, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal("戻る"), b -> onClose())
            .bounds(width - 72, height - 30, 60, 20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, HmiArt.BACKGROUND);
        g.text(font, title, 12, 14, HmiArt.TEXT);
        Snapshot snapshot = source.get();
        int pages = Math.max(1, (snapshot.rows().size() + capacity() - 1) / capacity());
        page = Math.clamp(page, 0, pages - 1);
        displayed = snapshot.rows().stream().skip((long)page * capacity()).limit(capacity()).toList();
        g.text(font, HmiArt.fitText(snapshot.status(), width - 24, font::width), 12, 33, HmiArt.MUTED);
        for (int i = 0; i < buttons.size(); i++) {
            Button button = buttons.get(i);
            button.visible = i < displayed.size();
            if (!button.visible) continue;
            Row row = displayed.get(i);
            String reason = row.unavailable();
            button.active = reason.isEmpty();
            int rowY = 52 + i * 64;
            line(g, row.name() + " / " + row.status(), 12, rowY, width - 104, HmiArt.TEXT, x, y);
            line(g, row.identity().toString(), 12, rowY + 13, width - 104, HmiArt.MUTED, x, y);
            line(g, row.cargo(), 12, rowY + 27, width - 24, HmiArt.CYAN, x, y);
            line(g, reason.isEmpty() ? "現在受信中 / 地図へ移動可能" : reason, 12, rowY + 41, width - 24, HmiArt.MUTED, x, y);
            g.horizontalLine(12, width - 12, rowY + 57, HmiArt.LINE);
        }
        previous.active = page > 0; next.active = page + 1 < pages;
        g.text(font, (page + 1) + "/" + pages, 138, height - 24, HmiArt.MUTED);
        if (!notice.isEmpty()) line(g, notice, 12, height - 46, width - 24, HmiArt.AMBER, x, y);
        super.extractRenderState(g, x, y, delta);
    }
    private void line(GuiGraphicsExtractor g, String text, int x, int y, int w, int color, int mouseX, int mouseY) {
        g.text(font, HmiArt.fitText(text, w, font::width), x, y, color);
        if (font.width(text) > w && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 11)
            g.setTooltipForNextFrame(Component.literal(text), mouseX, mouseY);
    }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
