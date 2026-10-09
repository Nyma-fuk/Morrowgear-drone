package jp.morrowgear.drone.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Native recipe inspection using only the received recipe book snapshot. */
final class ControllerRecipeScreen extends Screen {
    private final Screen parent;
    private final ControllerRecipeView.Book book;
    private final Map<Integer, int[]> candidates = new HashMap<>();
    private int selected;
    private ControllerRecipeLayout.Layout layout;

    ControllerRecipeScreen(Screen parent, ControllerRecipeView.Book book) {
        super(Component.literal(book.itemName()));
        this.parent = parent; this.book = book;
    }
    private ControllerRecipeView recipe() { return book.recipes().get(selected); }
    private void select(int direction) {
        selected = Math.clamp(selected + direction, 0, book.recipes().size() - 1);
        clearWidgets();
        init();
    }
    @Override protected void init() {
        layout = ControllerRecipeLayout.layout(width, height);
        addRenderableWidget(ControllerButton.hmi(Component.literal("戻る"), b -> onClose())
            .bounds(width - 72, height - 30, 60, 20).build());
        if (book.recipes().isEmpty()) return;
        selected = Math.clamp(selected, 0, book.recipes().size() - 1);
        var previous = addRenderableWidget(ControllerButton.hmi(Component.literal("前レシピ"), b -> select(-1))
            .bounds(12, height - 30, 60, 20).build());
        var next = addRenderableWidget(ControllerButton.hmi(Component.literal("次レシピ"), b -> select(1))
            .bounds(78, height - 30, 60, 20).build());
        previous.active = selected > 0; next.active = selected + 1 < book.recipes().size();
        var recipe = recipe();
        int[] choices = candidates.computeIfAbsent(recipe.identity(), ignored -> new int[11]);
        for (int i = 0; i < 9; i++) addRenderableWidget(new SlotButton(layout.left() + i % 3 * layout.cell(),
            layout.top() + i / 3 * layout.cell(), layout.cell(), recipe.grid().get(i), choices, i, "材料"));
        addRenderableWidget(new SlotButton(layout.outputX(), layout.top() + layout.cell(),
            layout.cell(), recipe.output(), choices, 9, "生成物"));
        addRenderableWidget(new SlotButton(layout.stationX(), layout.top() + layout.cell(),
            layout.cell(), recipe.station(), choices, 10, "設備"));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, HmiArt.BACKGROUND);
        text(g, title.getString(), 12, 12, width - 24, HmiArt.TEXT, mouseX, mouseY);
        if (book.recipes().isEmpty()) {
            text(g, book.unavailable(), 12, 42, width - 24, HmiArt.MUTED, mouseX, mouseY);
        } else {
            var recipe = recipe();
            text(g, recipe.kind(), 12, 28, width - 24, HmiArt.CYAN, mouseX, mouseY);
            g.text(font, "材料", layout.left(), layout.top() - 12, HmiArt.MUTED);
            g.text(font, "生成物", layout.outputX(), layout.top() - 12, HmiArt.MUTED);
            g.text(font, "設備", layout.stationX(), layout.top() - 12, HmiArt.MUTED);
            HmiArt.icon(g, "chevron-right", layout.outputX() - 20, layout.top() + layout.cell() + (layout.cell() - 16) / 2, 16);
            if (!recipe.output().candidates().isEmpty()) {
                int choice = candidates.get(recipe.identity())[9];
                var output = recipe.output().candidates().get(Math.floorMod(choice, recipe.output().candidates().size()));
                g.text(font, "×" + output.getCount(), layout.outputX(), layout.top() + layout.cell() * 2 + 4, HmiArt.TEXT);
            }
            if (layout.bottom() + 17 < height - 34)
                text(g, recipe.note(), 12, layout.bottom() + 8, width - 24, HmiArt.MUTED, mouseX, mouseY);
            g.text(font, (selected + 1) + "/" + book.recipes().size(), 148, height - 24, HmiArt.MUTED);
        }
        if (book.recipes().isEmpty() || layout.bottom() + 31 < height - 34)
            text(g, book.conversion(), 12, book.recipes().isEmpty() ? 70 : layout.bottom() + 22,
                width - 24, HmiArt.MUTED, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }
    private void text(GuiGraphicsExtractor g, String value, int x, int y, int w, int color, int mouseX, int mouseY) {
        g.text(font, HmiArt.fitText(value, w, font::width), x, y, color);
        if (font.width(value) > w && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 10)
            g.setTooltipForNextFrame(Component.literal(value), mouseX, mouseY);
    }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private static final class SlotButton extends Button {
        private final ControllerRecipeView.Slot slot;
        private final int[] choices;
        private final int index;
        private final String label;
        SlotButton(int x, int y, int size, ControllerRecipeView.Slot slot, int[] choices, int index, String label) {
            super(x, y, size, size, Component.literal(label), b ->
                choices[index] = ControllerRecipeLayout.nextCandidate(choices[index], slot.candidates().size()), DEFAULT_NARRATION);
            this.slot = slot; this.choices = choices; this.index = index; this.label = label;
            active = slot.candidates().size() > 1;
            updateLabel();
        }
        private ItemStack stack() {
            return slot.candidates().isEmpty() ? ItemStack.EMPTY
                : slot.candidates().get(Math.floorMod(choices[index], slot.candidates().size()));
        }
        private void updateLabel() {
            String name = slot.empty() ? "空欄" : slot.unresolved() ? "未解決" : stack().getHoverName().getString();
            setMessage(Component.literal(label + ": " + name
                + (active ? " / 候補 " + (choices[index] + 1) + "/" + slot.candidates().size() : "")));
        }
        @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            updateLabel();
            var font = Minecraft.getInstance().font;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), HmiArt.PANEL);
            g.outline(getX(), getY(), getWidth(), getHeight(), slot.unresolved() ? HmiArt.AMBER : active ? HmiArt.CYAN : HmiArt.LINE);
            ItemStack stack = stack();
            if (!stack.isEmpty()) {
                int scale = Math.max(1, (getWidth() - 6) / 16);
                g.pose().pushMatrix();
                g.pose().translate(getX() + (getWidth() - 16 * scale) / 2f, getY() + (getHeight() - 16 * scale) / 2f);
                g.pose().scale(scale, scale);
                g.item(stack, 0, 0);
                g.itemDecorations(font, stack, 0, 0);
                g.pose().popMatrix();
            } else g.centeredText(font, slot.empty() ? "-" : "?", getX() + getWidth() / 2,
                getY() + (getHeight() - 9) / 2, slot.empty() ? HmiArt.MUTED : HmiArt.AMBER);
            if (isMouseOver(mouseX, mouseY) || isFocused())
                g.setComponentTooltipForNextFrame(font, List.of(getMessage(),
                    Component.literal(stack.isEmpty() ? slot.empty() ? label + "の指定なし" : "受信した候補を解決できません"
                        : "個数 " + stack.getCount() + (active ? " / 選択で次の候補" : ""))), mouseX, mouseY);
        }
    }
}
