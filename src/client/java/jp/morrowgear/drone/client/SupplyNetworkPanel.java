package jp.morrowgear.drone.client;

import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.SupplyNetworkPolicy;
import jp.morrowgear.drone.SupplyNetworkPolicy.Status;
import jp.morrowgear.drone.SupplyNetworkRegistry.Route;
import jp.morrowgear.drone.network.SupplyNetworkConfigPayload;
import jp.morrowgear.drone.network.SupplyNetworkStatusPayload.DockStatus;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Dock-local supply control. Warehouses are selected by the owner's logistics network at dispatch time. */
final class SupplyNetworkPanel {
    private enum Page { CONTROL, DELIVERIES }
    private final DockScreen host;
    private final long dock;
    private Page page = Page.CONTROL;
    private boolean enabled;
    private boolean observed;
    private boolean dirty;
    private boolean pending;
    private int offset, width, height;
    private long sentAt;
    private Button apply;
    private String notice = "";

    SupplyNetworkPanel(DockScreen host) {
        this.host = host;
        dock = host.dockPosition().asLong();
        SupplyNetworkClient.request();
    }

    private DockStatus status() { return SupplyNetworkClient.dock(dock).orElse(null); }

    void back() {
        if (pending) { notice = "保存結果を確認しています"; return; }
        if (page == Page.DELIVERIES) { page = Page.CONTROL; offset = 0; host.rebuildNetwork(); }
        else host.closeNetwork();
    }

    void tick() {
        SupplyNetworkClient.request();
        DockStatus live = status();
        if (!observed && live != null) {
            enabled = live.enabled();
            observed = true;
            host.rebuildNetwork();
        }
        if (pending && live != null && live.enabled() == enabled) {
            pending = false;
            dirty = false;
            notice = enabled ? "自動補給を開始しました" : "自動補給を停止しました";
        } else if (pending && System.nanoTime() / 1_000_000 - sentAt > 5000) {
            pending = false;
            notice = "保存結果を確認できません。もう一度お試しください";
        }
        if (apply != null) apply.active = dirty && !pending && ClientPlayNetworking.canSend(SupplyNetworkConfigPayload.TYPE);
    }

    void build(int width, int height) {
        this.width = width;
        this.height = height;
        apply = null;
        host.button("戻る", "chevron-left", 6, 4, 66, this::back);
        if (page == Page.DELIVERIES) { buildDeliveries(); return; }
        host.control(Checkbox.builder(Component.literal("自動補給"), net.minecraft.client.Minecraft.getInstance().font)
            .pos(8, 54).selected(enabled).onValueChange((box, value) -> {
                enabled = value;
                observed = true;
                dirty = true;
                notice = "";
                if (apply != null) apply.active = true;
            }).build());
        host.button("配送状況", "cargo", 8, 84, 132, () -> {
            page = Page.DELIVERIES;
            offset = 0;
            host.rebuildNetwork();
        });
        apply = host.button("設定を保存", "check", 148, 84, Math.max(80, width - 156), this::save);
        apply.active = dirty && !pending && ClientPlayNetworking.canSend(SupplyNetworkConfigPayload.TYPE);
    }

    private void buildDeliveries() {
        DockStatus live = status();
        int total = live == null ? 0 : live.deliveries().size();
        int rows = rows();
        offset = Math.min(offset, Math.max(0, (total - 1) / rows));
        host.button("前へ", "chevron-left", 8, height - 24, 80, () -> { offset--; host.rebuildNetwork(); }).active = offset > 0;
        host.button("次へ", "chevron-right", 94, height - 24, 80, () -> { offset++; host.rebuildNetwork(); }).active = (offset + 1) * rows < total;
        host.button("更新", "refresh-cw", width - 88, height - 24, 80, SupplyNetworkClient::request);
    }

    private void save() {
        if (!dirty || pending || !ClientPlayNetworking.canSend(SupplyNetworkConfigPayload.TYPE)) return;
        ClientPlayNetworking.send(new SupplyNetworkConfigPayload(Route.AUTO_SOURCE, dock, enabled, SupplyNetworkPolicy.defaults()));
        pending = true;
        sentAt = System.nanoTime() / 1_000_000;
        notice = "保存結果を確認しています";
        if (apply != null) apply.active = false;
    }

    void draw(GuiGraphicsExtractor g) {
        host.text(g, page == Page.CONTROL ? "Dock 自動補給" : "配送状況", 82, 10, width - 90, HmiArt.TEXT);
        DockStatus live = status();
        if (page == Page.DELIVERIES) { drawDeliveries(g, live); return; }
        host.text(g, "Dock " + coordinates(dock), 8, 30, width - 16, HmiArt.CYAN);
        host.text(g, "補給元は所有者の物流網から在庫・距離・予約状況で自動選択", 8, 112, width - 16, HmiArt.MUTED);
        host.text(g, live == null ? "未登録 / ONにすると物流網へ参加します" : statusName(live.status()),
            8, 129, width - 16, live != null && live.status().error() ? HmiArt.AMBER : HmiArt.CYAN);
        if (live != null) {
            int y = 151;
            for (var stock : live.stock()) {
                host.text(g, name(stock.rule().kind()) + "  " + amount(stock.available())
                    + " / 搬送中 " + stock.incoming(), 8, y, width - 16, HmiArt.TEXT);
                y += 14;
            }
        }
        host.text(g, notice, 8, height - 39, width - 16, HmiArt.AMBER);
    }

    private void drawDeliveries(GuiGraphicsExtractor g, DockStatus live) {
        if (live == null || live.deliveries().isEmpty()) {
            host.text(g, live == null ? "Dockの設定を受信中です" : "運搬中の荷物はありません", 8, 52, width - 16, HmiArt.MUTED);
            return;
        }
        int rows = rows();
        for (int i = offset * rows; i < Math.min(live.deliveries().size(), (offset + 1) * rows); i++) {
            var job = live.deliveries().get(i);
            host.text(g, name(job.kind()) + " " + job.remaining() + "個 / " + statusName(job.status()),
                8, 43 + i % rows * 24, width - 16, HmiArt.TEXT);
        }
    }

    private int rows() { return Math.max(1, (height - 86) / 24); }
    private static String amount(int value) { return value < 0 ? "未確認" : Integer.toString(value); }
    private static String coordinates(long position) {
        BlockPos pos = BlockPos.of(position);
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
    private static String name(SupplyKind kind) {
        return switch (kind) {
            case FUEL -> "推進電力源"; case GUN -> "機関砲マガジン"; case LASER -> "兵装電力セル";
            case MISSILE -> "ミサイルパック"; case REPAIR -> "整備素材";
        };
    }
    private static String statusName(Status status) {
        return switch (status) {
            case DISABLED -> "自動補給OFF"; case READY -> "補給待機中"; case IN_TRANSIT -> "運搬中";
            case RETURNING -> "補給元へ戻っています"; case RECHARGING -> "運搬機の充電中";
            case WAIT_CHUNK -> "物流ノードの読み込み待ち"; case NO_IDLE_CARGO -> "使用可能な貨物機を待っています";
            case SOURCE_SHORTAGE -> "物流網の在庫が足りません"; case SOURCE_LOST -> "登録済み物流ノードがありません";
            case DOCK_LOST -> "Dockが見つかりません"; case DOCK_FULL -> "Dockがいっぱいです";
            case RESERVATION_INVALID, ASSIGNMENT_LOST -> "配送割り当てを再確認しています";
            case CARGO_MISMATCH -> "運搬する荷物が一致しません"; case CANCELLED -> "運搬を取り消しました";
            case LOAD_RETAINED -> "運搬機に荷物が残っています"; case RUNTIME_UNAVAILABLE -> "補給を開始できません";
            case ACCESS_DENIED -> "このDockを操作できません"; case CAPACITY_LIMIT -> "物流網の上限です";
        };
    }
}
