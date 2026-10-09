package jp.morrowgear.drone.client;

import java.util.List;
import java.util.function.Supplier;
import jp.morrowgear.drone.VisorCombatLedger;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Local observations stay separate from acknowledged server operation reports. */
final class VisorCombatHistoryScreen extends Screen {
    private final Screen parent;
    private final Supplier<List<VisorCombatLedger.Event>> source;
    private List<VisorCombatLedger.Event> events;
    private int page;
    private Button previous, next;

    VisorCombatHistoryScreen(Screen parent, Supplier<List<VisorCombatLedger.Event>> source) {
        super(Component.literal("バイザー / 戦闘履歴"));
        this.parent = parent;
        this.source = source;
        refresh();
    }

    private void refresh() { events = List.copyOf(source.get()).reversed(); page = 0; }
    private int capacity() { return Math.max(1, (height - 88) / 36); }

    @Override protected void init() {
        addRenderableWidget(ControllerButton.hmi(Component.literal("更新"), b -> refresh()).bounds(width - 64, 6, 56, 20).build());
        previous = addRenderableWidget(ControllerButton.hmi(Component.literal("前へ"), b -> page--).bounds(8, height - 28, 54, 20).build());
        next = addRenderableWidget(ControllerButton.hmi(Component.literal("次へ"), b -> page++).bounds(68, height - 28, 54, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal("戻る"), b -> onClose()).bounds(width - 64, height - 28, 56, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, HmiArt.BACKGROUND);
        g.text(font, title, 8, 12, HmiArt.TEXT);
        g.text(font, "ローカル観測 / " + events.size() + "件", 8, 34, HmiArt.MUTED);
        int pages = Math.max(1, (events.size() + capacity() - 1) / capacity());
        page = Math.clamp(page, 0, pages - 1);
        previous.active = page > 0;
        next.active = page + 1 < pages;
        g.text(font, (page + 1) + "/" + pages, 132, height - 22, HmiArt.MUTED);
        if (events.isEmpty()) g.text(font, "観測した戦闘報告はありません", 8, 58, HmiArt.MUTED);
        for (int row = 0; row < capacity() && page * capacity() + row < events.size(); row++) {
            var event = events.get(page * capacity() + row);
            int y = 54 + row * 36;
            String heading = "交戦" + event.front() + " / " + event.label();
            g.text(font, HmiArt.fitText(heading, width - 24, font::width), 12, y, HmiArt.CYAN);
            g.text(font, HmiArt.fitText(event.detail(), width - 24, font::width), 12, y + 13, HmiArt.TEXT);
            g.horizontalLine(8, width - 8, y + 29, HmiArt.LINE);
            if (mx >= 8 && mx < width - 8 && my >= y && my < y + 30)
                g.setTooltipForNextFrame(Component.literal(heading + " / " + event.detail()), mx, my);
        }
        super.extractRenderState(g, mx, my, delta);
    }

    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
