package jp.morrowgear.drone.client;

import jp.morrowgear.drone.carrier.CarrierCargoContainerMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Compact HMI window for every page of the detachable carrier cargo container. */
public final class CarrierCargoContainerScreen extends DockCarrierHmiScreen<CarrierCargoContainerMenu> {
    private Button first, previous, next, last;

    public CarrierCargoContainerScreen(CarrierCargoContainerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 240);
    }

    @Override protected boolean inventoryVisible() { return true; }

    @Override protected void init() {
        super.init();
        resetControls();
        int y = topPos + 5;
        first = button("先頭", "chevrons-left", leftPos + 8, y, 34,
            () -> changePage(CarrierCargoContainerMenu.FIRST));
        previous = button("前", "chevron-left", leftPos + 45, y, 28,
            () -> changePage(CarrierCargoContainerMenu.PREVIOUS));
        next = button("次", "chevron-right", leftPos + imageWidth - 73, y, 28,
            () -> changePage(CarrierCargoContainerMenu.NEXT));
        last = button("末尾", "chevrons-right", leftPos + imageWidth - 42, y, 34,
            () -> changePage(CarrierCargoContainerMenu.LAST));
        updateButtons();
    }

    private void changePage(int action) {
        if (minecraft.gameMode == null || !menu.getCarried().isEmpty()) return;
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action);
    }

    private void updateButtons() {
        int page = menu.page();
        boolean clear = menu.getCarried().isEmpty();
        first.active = previous.active = clear && page > 0;
        next.active = last.active = clear && page + 1 < menu.pages();
    }

    @Override protected void drawScreen(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        updateButtons();
        g.fill(leftPos - 4, topPos - 4, leftPos + imageWidth + 4, topPos + imageHeight + 4, PANEL);
        g.outline(leftPos - 4, topPos - 4, imageWidth + 8, imageHeight + 8, LINE);
        String page = (menu.page() + 1) + " / " + menu.pages();
        text(g, page, leftPos + (imageWidth - font.width(page)) / 2, topPos + 11, imageWidth, CYAN);
        text(g, "貨物 " + menu.usedSlots() + " / 1728枠", leftPos + 8, topPos + 137, imageWidth - 16, MUTED);
        slotBackgrounds(g);
    }
}
