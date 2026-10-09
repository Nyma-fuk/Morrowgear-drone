package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Read-only received data with wrapping and bounded pages, never an operation acknowledgement. */
final class ControllerDataScreen extends Screen {
    private final Screen parent;
    private final Supplier<List<String>> source;
    private int page;
    private int pages = 1;
    private Button previous;
    private Button next;

    ControllerDataScreen(Screen parent, String title, Supplier<List<String>> source) {
        super(Component.literal(title));
        this.parent = parent;
        this.source = source;
    }

    @Override protected void init() {
        previous = addRenderableWidget(ControllerButton.hmi(Component.literal("前へ"), b -> page = Math.max(0, page - 1))
            .bounds(12, height - 30, 54, 20).build());
        next = addRenderableWidget(ControllerButton.hmi(Component.literal("次へ"), b -> page = Math.min(pages - 1, page + 1))
            .bounds(72, height - 30, 54, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal("戻る"), b -> onClose())
            .bounds(width - 72, height - 30, 60, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, HmiArt.BACKGROUND);
        g.text(font, HmiArt.fitText(title.getString(), width - 24, font::width), 12, 14, HmiArt.TEXT);
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String line : source.get()) lines.addAll(font.split(Component.literal(line), Math.max(1, width - 24)));
        int capacity = Math.max(1, (height - 76) / 13);
        pages = Math.max(1, (lines.size() + capacity - 1) / capacity);
        page = Math.clamp(page, 0, pages - 1);
        for (int i = 0; i < capacity && page * capacity + i < lines.size(); i++)
            g.text(font, lines.get(page * capacity + i), 12, 38 + i * 13, HmiArt.TEXT);
        previous.active = page > 0;
        next.active = page + 1 < pages;
        g.text(font, (page + 1) + "/" + pages, 138, height - 24, HmiArt.MUTED);
        super.extractRenderState(g, x, y, delta);
    }

    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
