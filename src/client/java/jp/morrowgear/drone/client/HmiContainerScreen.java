package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

/** Shared native-slot rendering and hidden-slot input boundary for the two equipment screens. */
abstract class HmiContainerScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {
    protected final List<AbstractWidget> controls = new ArrayList<>();
    private boolean controlPress;
    protected HmiContainerScreen(T menu, Inventory inventory, Component title, int height) {
        super(menu, inventory, title, 176, height);
    }
    protected abstract boolean inventoryVisible();
    protected boolean slotVisible(Slot slot) { return inventoryVisible(); }
    protected boolean inventoryEditable() { return inventoryVisible(); }
    protected abstract void drawScreen(GuiGraphicsExtractor g, int mouseX, int mouseY);
    protected boolean surfaceClicked(MouseButtonEvent event) { return false; }
    protected <W extends AbstractWidget> W control(W widget) { controls.add(widget); return addRenderableWidget(widget); }
    protected void resetControls() { clearFocus(); clearWidgets(); controls.clear(); hoveredSlot = null; }
    protected Button button(String label, String symbol, int x, int y, int buttonWidth, Runnable action) {
        class HmiButton extends Button {
            HmiButton() { super(x, y, buttonWidth, 20, Component.literal(label), b -> action.run(), DEFAULT_NARRATION); }
            @Override public void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
                int color = !active ? HmiArt.MUTED : label.equals("停止") ? HmiArt.RED : HmiArt.TEXT;
                g.fill(getX(), getY(), getRight(), getBottom(), isHoveredOrFocused() ? HmiArt.PANEL : HmiArt.RAISED);
                g.outline(getX(), getY(), getWidth(), getHeight(), isFocused() ? HmiArt.CYAN : color == HmiArt.RED ? color : HmiArt.LINE);
                if (getWidth() <= 24 && symbol != null) { HmiArt.icon(g, symbol, getX() + (getWidth() - 12) / 2, getY() + 4, 12); return; }
                int textX = getX() + 5;
                if (symbol != null) { HmiArt.icon(g, symbol, textX, getY() + 4, 12); textX += 16; }
                g.text(font, HmiArt.fitText(getMessage().getString(), getRight() - textX - 4, font::width), textX, getY() + 6, color, false);
            }
        }
        Button result = new HmiButton();
        result.setTooltip(Tooltip.create(Component.literal(label)));
        return control(result);
    }
    protected void text(GuiGraphicsExtractor g, String value, int x, int y, int maxWidth, int color) {
        g.text(font, HmiArt.fitText(value, maxWidth, font::width), x, y, color, false);
    }
    protected void lines(GuiGraphicsExtractor g, String value, int x, int y, int maxWidth, int color) {
        g.textWithWordWrap(font, Component.literal(value), x, y, maxWidth, color, false);
    }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, HmiArt.BACKGROUND);
    }
    @Override public void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
        drawScreen(g, mx, my);
        if (inventoryVisible()) super.extractContents(g, mx, my, delta);
        else {
            hoveredSlot = null;
            for (AbstractWidget widget : controls) widget.extractRenderState(g, mx, my, delta);
        }
    }
    @Override protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {}
    protected void slotBackgrounds(GuiGraphicsExtractor g) {
        for (Slot slot : menu.slots) {
            if (!slotVisible(slot)) continue;
            g.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, HmiArt.RAISED);
            g.outline(leftPos + slot.x - 1, topPos + slot.y - 1, 18, 18, HmiArt.LINE);
        }
    }
    protected void cancelSlotGesture() { isQuickCrafting = false; quickCraftSlots.clear(); hoveredSlot = null; }
    @Override protected boolean isHovering(int x, int y, int w, int h, double mx, double my) {
        return inventoryVisible() && menu.slots.stream().anyMatch(slot -> slot.x == x && slot.y == y && slotVisible(slot))
            && super.isHovering(x, y, w, h, mx, my);
    }
    @Override protected void extractSlots(GuiGraphicsExtractor g, int mx, int my) {
        if (inventoryVisible()) for (Slot slot : menu.slots)
            if (slot.isActive() && slotVisible(slot)) super.extractSlot(g, slot, mx, my);
    }
    @Override protected void slotClicked(Slot slot, int id, int button, ContainerInput input) {
        if (inventoryEditable() && (id < 0 || id < menu.slots.size() && slotVisible(menu.slots.get(id)))) super.slotClicked(slot, id, button, input);
    }
    @Override protected boolean checkHotbarKeyPressed(KeyEvent event) {
        return inventoryEditable() && super.checkHotbarKeyPressed(event);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (AbstractWidget widget : List.copyOf(controls)) {
            if (widget.visible && event.x() >= widget.getX() && event.x() < widget.getRight()
                && event.y() >= widget.getY() && event.y() < widget.getBottom()) {
                controlPress = true;
                cancelSlotGesture();
                widget.mouseClicked(event, doubleClick);
                if (controls.contains(widget)) setFocused(widget);
                return true;
            }
        }
        if (surfaceClicked(event)) return true;
        if (!inventoryEditable()) return true;
        return super.mouseClicked(event, doubleClick);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (controlPress && getFocused() != null) return getFocused().mouseDragged(event, dx, dy);
        return !controlPress && inventoryEditable() && super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (controlPress) { controlPress = false; cancelSlotGesture(); return true; }
        if (!inventoryEditable()) { cancelSlotGesture(); return true; }
        return super.mouseReleased(event);
    }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        return inventoryEditable() && super.mouseScrolled(x, y, dx, dy);
    }
}
