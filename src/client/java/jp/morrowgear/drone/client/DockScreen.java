package jp.morrowgear.drone.client;

import jp.morrowgear.drone.DockMenu;
import jp.morrowgear.drone.DockStockViewPolicy;
import jp.morrowgear.drone.DockSupplyPolicy;
import jp.morrowgear.drone.PayloadCapacity;
import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Native 27-slot dock inventory, player inventory, and synchronized service state. */
public final class DockScreen extends HmiContainerScreen<DockMenu> {
    private static final String[] EQUIPMENT = {"機体", "電池", "役割装備", "兵装", "燃料", "修理材", "弾薬・兵装電力", "取り出し口", "蓄電装備"};
    private int page = 2, stockPage;
    private SupplyNetworkPanel network;
    private Button networkButton;
    public DockScreen(DockMenu menu, Inventory inventory, Component title) { super(menu, inventory, title, 166); }
    @Override protected boolean inventoryVisible() { return network == null && width >= 300 && height >= 166; }
    @Override protected void init() {
        super.init(); leftPos = 0; topPos = Math.max(0, (height - 166) / 2); build();
    }
    private void build() {
        resetControls();
        if (network != null) { network.build(width, height); return; }
        int x = 182, w = width - x - 6;
        button(page == 0 ? "補給品を見る" : page == 1 ? "備蓄を見る" : "機体の状態", page == 0 ? "package" : "gauge", x, 22, w, () -> { page = (page + 1) % 3; build(); });
        stockPage = Math.clamp(stockPage, 0, DockStockViewPolicy.pageCount(height) - 1);
        if (page == 2 && DockStockViewPolicy.pageCount(height) > 1) {
            button("前の備蓄", "chevron-left", width - 50, 44, 20, () -> { stockPage--; build(); }).active = stockPage > 0;
            button("次の備蓄", "chevron-right", width - 26, 44, 20, () -> { stockPage++; build(); })
                .active = stockPage + 1 < DockStockViewPolicy.pageCount(height);
        }
        networkButton = button("補給経路", "route", x, height - 24, w - 26, () -> {
            if (!menu.getCarried().isEmpty()) return;
            cancelSlotGesture(); network = new SupplyNetworkPanel(this); build();
        });
        button("閉じる", "x", width - 26, height - 24, 20, this::onClose);
    }
    void rebuildNetwork() { build(); }
    void closeNetwork() { cancelSlotGesture(); network = null; build(); }
    @Override protected void containerTick() {
        if (network != null) network.tick();
        else if (networkButton != null && networkButton.active != menu.getCarried().isEmpty()) {
            networkButton.active = menu.getCarried().isEmpty();
            networkButton.setTooltip(Tooltip.create(Component.literal(networkButton.active ? "補給経路" : "持っているアイテムを枠へ戻してください")));
        }
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (network != null) {
            if (event.key() == 256) { network.back(); return true; }
            if (getFocused() instanceof EditBox box && event.key() != 258) { box.keyPressed(event); return true; }
        }
        return super.keyPressed(event);
    }
    @Override protected void drawScreen(GuiGraphicsExtractor g, int mx, int my) {
        if (network != null) { network.draw(g); return; }
        if (inventoryVisible()) {
            slotBackgrounds(g);
            text(g, "Dock", 8, topPos + 5, 155, HmiArt.TEXT);
            text(g, "持ち物", 8, topPos + 73, 155, HmiArt.MUTED);
            for (int i = 0; i < 27; i++) {
                var slot = menu.slots.get(i);
                int x = leftPos + slot.x, y = topPos + slot.y;
                if (i < 9 && !slot.hasItem()) HmiArt.icon(g, new String[] {"field", "battery", "layers", "target", "battery-charging", "wrench", "package-plus", "download", "zap"}[i], x + 2, y + 2, 12);
                g.horizontalLine(x, x + 15, y + 15, i >= 18 ? HmiArt.GREEN : i >= 9 || i == 7 ? HmiArt.AMBER : HmiArt.CYAN);
                if (mx >= x && mx < x + 16 && my >= y && my < y + 16) {
                    String label = i < 9 ? EQUIPMENT[i] : i < 18 ? "回収品 / 取り出し専用" : "補給品の予備";
                    if (!slot.hasItem()) g.setTooltipForNextFrame(Component.literal(label), mx, my);
                    text(g, label, 182, height - 39, width - 188, HmiArt.AMBER);
                }
            }
        }
        int x = 182, w = width - 188;
        text(g, readiness(), x, 7, w, menu.value(DockMenu.READINESS) == DockMenu.BLOCKED ? HmiArt.AMBER : HmiArt.CYAN);
        if (page == 0) {
            text(g, "飛行電力 " + menu.value(DockMenu.DRONE_FLIGHT_PERCENT) + "%", x, 50, w, HmiArt.TEXT);
            text(g, "兵装電力 " + menu.value(DockMenu.DRONE_WEAPON_PERCENT) + "%", x, 65, w, HmiArt.TEXT);
            text(g, "機関砲 " + menu.value(DockMenu.DRONE_GUN_ROUNDS) + "発", x, 80, w, HmiArt.TEXT);
            text(g, "ミサイル " + menu.value(DockMenu.DRONE_MISSILES) + "発", x, 95, w, HmiArt.TEXT);
            String reason = "";
            for (SupplyKind kind : SupplyKind.values()) if (menu.shortage(kind)) reason += (reason.isEmpty() ? "不足: " : " / ") + supplyName(kind);
            if (reason.isEmpty()) reason = menu.value(DockMenu.READINESS) == DockMenu.EMPTY ? "機体・装備を上段へ" : "整備と補給は自動です";
            lines(g, reason, x, 112, w, HmiArt.AMBER);
        } else if (page == 1) {
            int y = 48;
            for (SupplyKind kind : SupplyKind.values()) {
                text(g, supplyName(kind) + " " + menu.supplyCount(kind) + (menu.shortage(kind) ? " / 不足" : menu.available(kind) ? " / 使用可" : " / なし"), x, y, w, menu.shortage(kind) ? HmiArt.AMBER : HmiArt.TEXT);
                y += 14;
            }
            text(g, "飛行備蓄 " + menu.value(DockMenu.FLIGHT_POWER) + "/" + menu.value(DockMenu.FLIGHT_CAPACITY), x, y + 2, w, HmiArt.MUTED);
        } else drawStock(g, mx, my, x, w);
    }
    private void drawStock(GuiGraphicsExtractor g, int mx, int my, int x, int w) {
        int pages = DockStockViewPolicy.pageCount(height);
        text(g, "備蓄" + (pages > 1 ? " " + (stockPage + 1) + "/" + pages : ""), x, 49,
            pages > 1 ? w - 48 : w, HmiArt.MUTED);
        int first = DockStockViewPolicy.firstRow(height, stockPage), rowHeight = DockStockViewPolicy.rowHeight(height);
        for (int row = 0; row < DockStockViewPolicy.rowsPerPage(height); row++) {
            int kind = first + row, y = 68 + row * rowHeight;
            if (kind == 0) {
                int stored = menu.value(DockMenu.FLIGHT_POWER), capacity = menu.value(DockMenu.FLIGHT_CAPACITY);
                int credit = menu.value(DockMenu.FUEL_CREDIT), fuel = menu.supplyCount(SupplyKind.FUEL);
                String tank = capacity > 0 ? "蓄電 " + stored + "/" + capacity : "蓄電 未確認";
                String reserve = "燃料 " + fuel + "個 / 繰越 " + credit;
                drawStockRow(g, mx, my, x, y, w, rowHeight, "飛行電力", DockStockViewPolicy.flightReserve(stored, credit) + " E",
                    tank, reserve, "飛行電力: 蓄電 " + stored + " / 容量 " + capacity + "、繰越 " + credit
                        + "。未投入燃料 " + fuel + "個（種類別の電力量は合算外）", stored, capacity, HmiArt.GREEN);
            } else {
                SupplyKind supply = kind == 1 ? SupplyKind.LASER : kind == 2 ? SupplyKind.GUN : SupplyKind.MISSILE;
                int field = kind == 1 ? DockMenu.WEAPON_POWER : kind == 2 ? DockMenu.GUN_CREDIT : DockMenu.MISSILE_CREDIT;
                int units = kind == 1 ? DockSupplyPolicy.LASER_CELL_ENERGY
                    : kind == 2 ? DockSupplyPolicy.MAGAZINE_ROUNDS : DockSupplyPolicy.MISSILE_PACK_ROUNDS;
                var stock = DockStockViewPolicy.packStock(menu.supplyCount(supply), menu.value(field), units);
                String label = kind == 1 ? "兵装電力" : kind == 2 ? "機関砲" : "ミサイル";
                String pack = kind == 1 ? "セル" : kind == 2 ? "箱" : "パック", unit = kind == 1 ? " E" : "発";
                String detail = stock.unopened() + pack + " + 残" + stock.remaining() + "/" + units;
                int capacityField = kind == 1 ? DockMenu.DRONE_WEAPON_CAPACITY
                    : kind == 2 ? DockMenu.DRONE_GUN_CAPACITY : DockMenu.DRONE_MISSILE_CAPACITY;
                int standard = kind == 1 ? PayloadCapacity.energy(0) : kind == 2 ? PayloadCapacity.gun(0) : PayloadCapacity.missiles(0);
                var reference = DockStockViewPolicy.reference(standard, menu.value(capacityField),
                    menu.value(DockMenu.DRONE_ID) >= 0 && menu.value(DockMenu.READINESS) != DockMenu.EMPTY);
                String basis = (reference.connected() ? "接続機1機分 " : "標準1機分 ") + reference.amount() + unit;
                String tooltip = label + ": 合計 " + stock.total() + unit + "。未開封 " + stock.unopened() + pack
                    + "、開封残 " + stock.remaining() + "/" + units + unit + "。1" + pack + " = " + units + unit
                    + "。比較基準: " + basis + "（Dockの保管上限ではありません）";
                drawStockRow(g, mx, my, x, y, w, rowHeight, label, stock.total() + unit, basis,
                    detail, tooltip, stock.total(), reference.amount(), kind == 1 ? HmiArt.CYAN : HmiArt.AMBER);
            }
        }
    }
    private void drawStockRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h,
                              String label, String total, String detail, String extra, String tooltip,
                              long amount, long capacity, int color) {
        int valueWidth = font.width(total);
        text(g, label, x, y, Math.max(0, w - valueWidth - 6), HmiArt.TEXT);
        text(g, total, x + Math.max(0, w - valueWidth), y, Math.min(w, valueWidth), color);
        text(g, detail, x, y + 12, w, HmiArt.MUTED);
        if (h >= 42) text(g, extra, x, y + 24, w, HmiArt.MUTED);
        int barY = y + h - 6;
        g.fill(x, barY, x + w, barY + 3, HmiArt.LINE);
        int fill = DockStockViewPolicy.gaugePixels(amount, capacity, w);
        if (fill > 0) g.fill(x, barY, x + fill, barY + 3, color);
        if (mx >= x && mx < x + w && my >= y && my < y + h)
            g.setTooltipForNextFrame(Component.literal(tooltip), mx, my);
    }
    @Override protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> result = new ArrayList<>(super.getTooltipFromContainerItem(stack));
        if (hoveredSlot != null && hoveredSlot.index < 27) result.add(Component.literal(hoveredSlot.index < 9
            ? EQUIPMENT[hoveredSlot.index] : hoveredSlot.index < 18 ? "回収品 / 取り出し専用" : "補給品の予備"));
        SupplyKind kind = DockSupplyPolicy.kind(stack);
        if (kind == SupplyKind.MISSILE) result.add(Component.literal("1パック " + DockSupplyPolicy.MISSILE_PACK_ROUNDS + "発"));
        else if (kind == SupplyKind.GUN) result.add(Component.literal("1箱 " + DockSupplyPolicy.MAGAZINE_ROUNDS + "発"));
        else if (kind == SupplyKind.LASER) result.add(Component.literal("兵装電力セル / 1セル " + DockSupplyPolicy.LASER_CELL_ENERGY + " E"));
        return result;
    }
    private String readiness() {
        return switch (menu.value(DockMenu.READINESS)) {
            case DockMenu.EMPTY -> "機体がありません";
            case DockMenu.SERVICING -> "整備・補給中";
            case DockMenu.READY -> "出発できます";
            case DockMenu.BLOCKED -> "補給品が足りません";
            default -> "状態を確認中";
        };
    }
    private static String supplyName(SupplyKind kind) {
        return switch (kind) {
            case FUEL -> "燃料"; case GUN -> "機関砲弾"; case LASER -> "兵装電力"; case MISSILE -> "ミサイル"; case REPAIR -> "修理材";
        };
    }
}
