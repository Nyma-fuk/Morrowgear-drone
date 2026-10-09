package jp.morrowgear.drone.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/** Presentation scoped to the dock and carrier; native container input stays in the shared base. */
abstract class DockCarrierHmiScreen<T extends AbstractContainerMenu> extends HmiContainerScreen<T> {
    protected static final int BACKGROUND = 0xFF121514, PANEL = 0xFF1A1E1C, RAISED = 0xFF252C27;
    protected static final int LINE = 0xFF343C36, TEXT = 0xFFE1E7E0, MUTED = 0xFF99A59D;
    protected static final int CYAN = 0xFF7CDEC8, GREEN = 0xFFA5D391, AMBER = 0xFFE7BD79, RED = 0xFFF28D83;

    protected DockCarrierHmiScreen(T menu, Inventory inventory, Component title, int height) {
        super(menu, inventory, title, height);
    }

    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, BACKGROUND);
    }

    @Override protected Button button(String label, String symbol, int x, int y, int buttonWidth, Runnable action) {
        class OperationButton extends Button {
            OperationButton() { super(x, y, Math.max(20, buttonWidth), 20, Component.literal(label), b -> action.run(), DEFAULT_NARRATION); }
            @Override public void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
                int color = !active ? MUTED : label.equals("停止") ? RED : TEXT;
                g.fill(getX(), getY(), getRight(), getBottom(), isHoveredOrFocused() ? RAISED : PANEL);
                g.outline(getX(), getY(), getWidth(), getHeight(), isHoveredOrFocused() ? CYAN : LINE);
                if (getWidth() <= 24 && symbol != null) {
                    HmiArt.icon(g, symbol, getX() + (getWidth() - 12) / 2, getY() + 4, 12);
                    return;
                }
                int tx = getX() + 5;
                if (symbol != null) { HmiArt.icon(g, symbol, tx, getY() + 4, 12); tx += 16; }
                g.text(font, HmiArt.fitText(getMessage().getString(), getRight() - tx - 4, font::width), tx, getY() + 6, color, false);
            }
        }
        Button result = new OperationButton();
        result.setTooltip(Tooltip.create(Component.literal(label)));
        return control(result);
    }

    @Override protected void slotBackgrounds(GuiGraphicsExtractor g) {
        for (Slot slot : menu.slots) {
            if (!slotVisible(slot)) continue;
            g.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, RAISED);
            g.outline(leftPos + slot.x - 1, topPos + slot.y - 1, 18, 18, LINE);
        }
    }
}
