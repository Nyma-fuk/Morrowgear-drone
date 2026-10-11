package jp.morrowgear.drone.client;

import jp.morrowgear.drone.DockMenu;
import jp.morrowgear.drone.DockStockViewPolicy;
import jp.morrowgear.drone.DockSupplyPolicy;
import jp.morrowgear.drone.DroneResourceUiPolicy;
import jp.morrowgear.drone.PayloadCapacity;
import jp.morrowgear.drone.SecurityLoadout;
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
public final class DockScreen extends DockCarrierHmiScreen<DockMenu> {
    private static final String[] EQUIPMENT = {"機体", "機体バッテリー", "役割装備", "兵装設定", "推進電力源", "整備素材", "弾薬・兵装電力", "取り出し口", "Dock蓄電装備"};
    private int page, stockPage;
    private int resourcePage, resourceCount;
    private SupplyNetworkPanel network;
    private Button networkButton;
    public DockScreen(DockMenu menu, Inventory inventory, Component title) { super(menu, inventory, title, 166); }
    @Override protected boolean inventoryVisible() { return network == null && width >= 300 && height >= 166; }
    @Override protected void init() {
        super.init(); leftPos = DockCarrierLayout.dockInventoryX(width);
        topPos = DockCarrierLayout.dockInventoryY(height); build();
    }
    private void build() {
        resetControls();
        if (network != null) { network.build(width, height); return; }
        int x = detailsX(), w = width - x - 6;
        String[] tabs = {"機体", "補給", "備蓄"};
        for (int i = 0; i < tabs.length; i++) {
            final int selected = i;
            button(tabs[i], null, x + i * w / 3, 22, w / 3 - 2,
                () -> { cancelSlotGesture(); page = selected; resourcePage = 0; build(); });
        }
        if (page != 2) {
            int total = page == 0 ? aircraftResources().metrics().size() : SupplyKind.values().length;
            resourceCount = total;
            int pages = Math.max(1, (total + resourceRows() - 1) / resourceRows());
            resourcePage = Math.clamp(resourcePage, 0, pages - 1);
            button("前の資源", "chevron-left", width - 50, 44, 20, () -> { resourcePage--; build(); }).active = resourcePage > 0;
            button("次の資源", "chevron-right", width - 26, 44, 20, () -> { resourcePage++; build(); }).active = resourcePage + 1 < pages;
        }
        stockPage = Math.clamp(stockPage, 0, DockStockViewPolicy.pageCount(height) - 1);
        if (page == 2 && DockStockViewPolicy.pageCount(height) > 1) {
            button("前の備蓄", "chevron-left", width - 50, 44, 20, () -> { stockPage--; build(); }).active = stockPage > 0;
            button("次の備蓄", "chevron-right", width - 26, 44, 20, () -> { stockPage++; build(); })
                .active = stockPage + 1 < DockStockViewPolicy.pageCount(height);
        }
		networkButton = button("補給状況", "route", x, height - 24, w - 26, () -> {
            if (!menu.getCarried().isEmpty()) return;
            cancelSlotGesture(); network = new SupplyNetworkPanel(this); build();
        });
        button("閉じる", "x", width - 26, height - 24, 20, this::onClose);
    }
    void rebuildNetwork() { build(); }
    void closeNetwork() { cancelSlotGesture(); network = null; build(); }
    net.minecraft.core.BlockPos dockPosition() { return menu.dockPosition(); }
    @Override protected void containerTick() {
        if (network == null && page == 0 && resourceCount != aircraftResources().metrics().size()) { cancelSlotGesture(); build(); }
        if (network != null) network.tick();
        else if (networkButton != null && networkButton.active != menu.getCarried().isEmpty()) {
            networkButton.active = menu.getCarried().isEmpty();
			networkButton.setTooltip(Tooltip.create(Component.literal(networkButton.active ? "自動補給は常時ONです" : "持っているアイテムを枠へ戻してください")));
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
        g.fill(0, 0, detailsX() - 6, height, PANEL);
        g.verticalLine(detailsX() - 6, 0, height, LINE);
        g.fill(detailsX() + page * (width - detailsX() - 6) / 3, 41,
            detailsX() + (page + 1) * (width - detailsX() - 6) / 3 - 2, 43, CYAN);
        if (inventoryVisible()) {
            slotBackgrounds(g);
            text(g, "Dock / 装備・回収・補給", leftPos + 8, topPos + 5, 160, TEXT);
            text(g, "持ち物", leftPos + 8, topPos + 73, 155, MUTED);
            int rowLabelX = leftPos + 172;
            int rowLabelWidth = detailsX() - 6 - rowLabelX;
            if (rowLabelWidth >= font.width("補給")) {
                String[] rowLabels = {"装備", "回収", "補給"};
                int[] rowColors = {CYAN, AMBER, GREEN};
                for (int row = 0; row < rowLabels.length; row++) {
                    text(g, rowLabels[row], rowLabelX, topPos + menu.slots.get(row * 9).y + 4,
                        rowLabelWidth, rowColors[row]);
                }
            }
            for (int i = 0; i < 27; i++) {
                var slot = menu.slots.get(i);
                int x = leftPos + slot.x, y = topPos + slot.y;
                if (i < 9 && !slot.hasItem()) HmiArt.icon(g, new String[] {"field", "battery", "layers", "target", "battery-charging", "wrench", "package-plus", "download", "zap"}[i], x + 2, y + 2, 12);
                g.horizontalLine(x, x + 15, y + 15, i >= 18 ? GREEN : i >= 9 || i == 7 ? AMBER : CYAN);
                if (mx >= x && mx < x + 16 && my >= y && my < y + 16) {
                    String label = i < 9 ? EQUIPMENT[i] : i < 18 ? "回収品 / 取り出し専用" : "補給品の予備";
                    if (!slot.hasItem()) g.setTooltipForNextFrame(Component.literal(label), mx, my);
                    text(g, label, detailsX(), height - 39, width - detailsX() - 6, AMBER);
                }
            }
        }
        int x = detailsX(), w = width - x - 6;
        text(g, readiness(), x, 7, w, menu.value(DockMenu.READINESS) == DockMenu.BLOCKED ? AMBER : CYAN);
        if (page == 0) {
            drawAircraftReadiness(g, mx, my, x, w);
        } else if (page == 1) {
            text(g, "補給品 / " + resourcePageLabel(SupplyKind.values().length), x, 49, w - 48, MUTED);
            for (int row = 0; row < resourceRows(); row++) {
                int index = resourcePage * resourceRows() + row;
                if (index >= SupplyKind.values().length) break;
                SupplyKind kind = SupplyKind.values()[index];
                int y = 68 + row * 34;
                String amount = menu.supplyCount(kind) + "個";
                int amountWidth = Math.min(w, font.width(amount));
                text(g, supplyName(kind), x, y, Math.max(0, w - amountWidth - 6), TEXT);
                text(g, amount, x + w - amountWidth, y, amountWidth, CYAN);
                text(g, menu.shortage(kind) ? "不足 / 補給品を投入" : menu.available(kind) ? "供給可能" : "在庫なし", x, y + 13, w,
                    menu.shortage(kind) ? AMBER : MUTED);
            }
        } else drawStock(g, mx, my, x, w);
    }

    private int detailsX() { return DockCarrierLayout.dockDetailsX(width); }
    private int resourceRows() { return Math.max(1, (height - 110) / 34); }
    private String resourcePageLabel(int count) { return (resourcePage + 1) + "/" + Math.max(1, (count + resourceRows() - 1) / resourceRows()); }
    private DroneResourceUiPolicy.Snapshot aircraftResources() {
        SecurityLoadout[] loadouts = SecurityLoadout.values();
        SecurityLoadout loadout = loadouts[Math.clamp(menu.value(DockMenu.DRONE_LOADOUT), 0, loadouts.length - 1)];
        return DroneResourceUiPolicy.assess(menu.value(DockMenu.DRONE_SECURITY) != 0, loadout,
            menu.value(DockMenu.DRONE_FLIGHT_PERCENT), menu.value(DockMenu.DRONE_WEAPON_PERCENT),
            menu.value(DockMenu.DRONE_GUN_ROUNDS), menu.value(DockMenu.DRONE_GUN_CAPACITY),
            menu.value(DockMenu.DRONE_MISSILES), menu.value(DockMenu.DRONE_MISSILE_CAPACITY),
            menu.value(DockMenu.DRONE_LASER_HEAT), menu.value(DockMenu.DRONE_STRUCTURE_PERCENT),
            menu.value(DockMenu.DRONE_SYSTEM_PERCENT), menu.value(DockMenu.READINESS) == DockMenu.SERVICING);
    }
    private void drawAircraftReadiness(GuiGraphicsExtractor g, int mx, int my, int x, int w) {
        if (menu.value(DockMenu.READINESS) == DockMenu.EMPTY) {
            lines(g, "機体・装備を上段へ配置してください", x, 52, w, MUTED);
            return;
        }
        var snapshot = aircraftResources();
        int first = resourcePage * resourceRows();
        text(g, "機体 / " + resourcePageLabel(snapshot.metrics().size()), x, 49, w - 48, MUTED);
        int y = 68;
        for (var metric : snapshot.metrics().subList(Math.min(first, snapshot.metrics().size()), Math.min(first + resourceRows(), snapshot.metrics().size()))) {
            int color = switch (metric.tone()) {
                case BLOCKED -> RED;
                case CAUTION -> AMBER;
                case NOMINAL -> metric.code().equals("GUN") || metric.code().equals("MSL")
                    ? AMBER : CYAN;
            };
            String value = metric.value() + (metric.code().equals("GUN") || metric.code().equals("MSL") ? "発" : "");
            int valueWidth = Math.min(w, font.width(value));
            text(g, metric.label(), x, y, Math.max(0, w - valueWidth - 8), TEXT);
            text(g, value, x + w - valueWidth, y, valueWidth, color);
            int barY = y + 11;
            g.fill(x, barY, x + w, barY + 3, LINE);
            g.fill(x, barY, x + DockStockViewPolicy.gaugePixels(metric.current(), metric.maximum(), w), barY + 3, color);
            String reason = resourceReason(metric);
            text(g, reason, x, y + 19, w, metric.tone() == DroneResourceUiPolicy.Tone.BLOCKED ? AMBER : MUTED);
            if (mx >= x && mx < x + w && my >= y && my < y + 33)
                g.setTooltipForNextFrame(Component.literal(metric.label() + " " + value + " / " + reason), mx, my);
            y += 34;
        }
    }
    private String resourceReason(DroneResourceUiPolicy.Metric metric) {
        SupplyKind supply = switch (metric.code()) {
            case "FLT" -> SupplyKind.FUEL; case "LZR" -> SupplyKind.LASER;
            case "GUN" -> SupplyKind.GUN; case "MSL" -> SupplyKind.MISSILE;
            case "STR", "SYS" -> SupplyKind.REPAIR; default -> null;
        };
        if (supply == null) return metric.tone() == DroneResourceUiPolicy.Tone.BLOCKED ? "冷却待ち" : "熱状態正常";
        String stock = switch (supply) {
            case FUEL -> DockStockViewPolicy.flightReserve(menu.value(DockMenu.FLIGHT_POWER), menu.value(DockMenu.FUEL_CREDIT)) + " E";
            case LASER -> DockStockViewPolicy.packStock(menu.supplyCount(supply), menu.value(DockMenu.WEAPON_POWER), DockSupplyPolicy.LASER_CELL_ENERGY).total() + " E";
            case GUN -> DockStockViewPolicy.packStock(menu.supplyCount(supply), menu.value(DockMenu.GUN_CREDIT), DockSupplyPolicy.MAGAZINE_ROUNDS).total() + "発";
            case MISSILE -> DockStockViewPolicy.packStock(menu.supplyCount(supply), menu.value(DockMenu.MISSILE_CREDIT), DockSupplyPolicy.MISSILE_PACK_ROUNDS).total() + "発";
            case REPAIR -> "整備素材 " + menu.supplyCount(supply) + "個";
        };
        return "Dock " + stock + (menu.shortage(supply) ? " / 不足" : menu.available(supply) ? " / 供給可" : " / 在庫なし");
    }
    private void drawStock(GuiGraphicsExtractor g, int mx, int my, int x, int w) {
        int pages = DockStockViewPolicy.pageCount(height);
        text(g, "備蓄" + (pages > 1 ? " " + (stockPage + 1) + "/" + pages : ""), x, 49,
            pages > 1 ? w - 48 : w, MUTED);
        int first = DockStockViewPolicy.firstRow(height, stockPage), rowHeight = DockStockViewPolicy.rowHeight(height);
        for (int row = 0; row < DockStockViewPolicy.rowsPerPage(height); row++) {
            int kind = first + row, y = 68 + row * rowHeight;
            if (kind == 0) {
                int stored = menu.value(DockMenu.FLIGHT_POWER), capacity = menu.value(DockMenu.FLIGHT_CAPACITY);
                int credit = menu.value(DockMenu.FUEL_CREDIT), fuel = menu.supplyCount(SupplyKind.FUEL);
                String tank = capacity > 0 ? "蓄電 " + stored + "/" + capacity : "蓄電 未確認";
                String reserve = "電力源 " + fuel + "個 / 変換待ち " + credit + " E";
                drawStockRow(g, mx, my, x, y, w, rowHeight, "飛行電力", DockStockViewPolicy.flightReserve(stored, credit) + " E",
                    tank, reserve, "飛行電力: 蓄電 " + stored + " / 容量 " + capacity + "、変換待ち " + credit
                        + " E。未変換の電力源 " + fuel + "個（パワーセル・石炭・木炭）", stored, capacity, GREEN);
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
                    detail, tooltip, stock.total(), reference.amount(), kind == 1 ? CYAN : AMBER);
            }
        }
    }
    private void drawStockRow(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h,
                              String label, String total, String detail, String extra, String tooltip,
                              long amount, long capacity, int color) {
        int valueWidth = font.width(total);
        text(g, label, x, y, Math.max(0, w - valueWidth - 6), TEXT);
        text(g, total, x + Math.max(0, w - valueWidth), y, Math.min(w, valueWidth), color);
        text(g, detail, x, y + 12, w, MUTED);
        if (h >= 42) text(g, extra, x, y + 24, w, MUTED);
        int barY = y + h - 6;
        g.fill(x, barY, x + w, barY + 3, LINE);
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
        else if (DockSupplyPolicy.isBattery(DockSupplyPolicy.itemId(stack)))
            result.add(Component.literal("容量装備 / 推進電力源としては消費されません"));
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
            case FUEL -> "推進電力源"; case GUN -> "機関砲弾"; case LASER -> "兵装電力"; case MISSILE -> "ミサイル"; case REPAIR -> "整備素材";
        };
    }
}
