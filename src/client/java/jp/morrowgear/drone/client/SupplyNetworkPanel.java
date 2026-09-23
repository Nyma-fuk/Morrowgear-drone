package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.SupplyNetworkPolicy;
import jp.morrowgear.drone.SupplyNetworkPolicy.Rule;
import jp.morrowgear.drone.SupplyNetworkPolicy.Status;
import jp.morrowgear.drone.SupplyNetworkUiPolicy;
import jp.morrowgear.drone.block.DockBlockEntity;
import jp.morrowgear.drone.network.SupplyNetworkConfigPayload;
import jp.morrowgear.drone.network.SupplyNetworkStatusPayload.DockStatus;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

/** Full-page editor inside the Dock menu; no container close or implicit target inference. */
final class SupplyNetworkPanel {
    private enum Page { DOCKS, ROUTE, SOURCES, RULES, REVIEW, DELIVERIES }
    private record Place(long position, String label) {}
    private final DockScreen host;
    private final Minecraft client = Minecraft.getInstance();
    private final SupplyNetworkUiPolicy result = new SupplyNetworkUiPolicy();
    private final String[] minimum = new String[5], priority = new String[5];
    private List<Place> docks = List.of(), sources = List.of();
    private List<Long> knownDocks = List.of();
    private Page page = Page.DOCKS;
    private Long dock, source;
    private boolean enabled = true;
    private int offset, kind, width, height;
    private Button apply;
    private String notice = "";

    SupplyNetworkPanel(DockScreen host) {
        this.host = host;
        useRules(SupplyNetworkPolicy.defaults());
        scan();
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
    private DockStatus status() { return dock == null ? null : SupplyNetworkClient.dock(dock).orElse(null); }
    private void useRules(List<Rule> rules) {
        for (Rule rule : rules) {
            minimum[rule.kind().index()] = Integer.toString(rule.minimum());
            priority[rule.kind().index()] = Integer.toString(rule.priority());
        }
    }
    private void show(Page next) { page = next; offset = 0; notice = ""; host.rebuildNetwork(); }
    void back() {
        if (result.state() == SupplyNetworkUiPolicy.State.PENDING) { notice = "保存結果を確認しています"; return; }
        switch (page) {
            case DOCKS -> host.closeNetwork();
            case ROUTE -> show(Page.DOCKS);
            case REVIEW -> show(Page.RULES);
            default -> show(Page.ROUTE);
        }
    }
    void tick() {
        SupplyNetworkClient.request();
        if (page == Page.DOCKS && !knownDocks.equals(SupplyNetworkClient.docks().stream().map(DockStatus::dock).toList())) {
            scan(); host.rebuildNetwork();
        }
        DockStatus current = status();
        if (current != null) result.accept(configuration(current.source(), current.dock(), current.enabled(), current.rules()), current);
        result.tick(now());
        if (apply != null) apply.active = result.state() != SupplyNetworkUiPolicy.State.PENDING && problem().isEmpty();
    }
    void build(int width, int height) {
        this.width = width; this.height = height; apply = null;
        host.button("戻る", "chevron-left", 6, 4, 66, this::back);
        switch (page) {
            case DOCKS, SOURCES -> buildPlaces();
            case ROUTE -> {
                host.button("補給元を選ぶ", "package", 8, 52, 144, () -> { scan(); show(Page.SOURCES); });
                host.button("在庫数・優先度", "sliders-horizontal", 160, 52, width - 168, () -> show(Page.RULES));
                host.control(Checkbox.builder(Component.literal("自動補給を使う"), client.font).pos(8, 84)
                    .selected(enabled).onValueChange((box, value) -> { enabled = value; result.reset(); }).build());
                host.button("運搬中の荷物", "cargo", 8, 111, 144, () -> show(Page.DELIVERIES));
                host.button("設定を確認", "chevron-right", 160, 111, width - 168, () -> show(Page.REVIEW));
            }
            case RULES -> {
                int w = (width - 16) / 5;
                for (SupplyKind type : SupplyKind.values()) {
                    int index = type.index();
                    host.button(name(type), null, 8 + index * w, 28, w - 3, () -> { kind = index; host.rebuildNetwork(); });
                }
                input("最低在庫", 8, 70, minimum[kind], value -> minimum[kind] = value);
                input("優先度", 164, 70, priority[kind], value -> priority[kind] = value);
                host.button("設定を確認", "chevron-right", 8, height - 24, width - 16, () -> {
                    if (rules() == null) { notice = "最低在庫は0〜576、優先度は0〜100です"; return; }
                    show(Page.REVIEW);
                });
            }
            case REVIEW -> {
                apply = host.button("この設定を保存", "check", 8, height - 24, width - 16, this::save);
                tick();
            }
            case DELIVERIES -> paginate(status() == null ? 0 : status().deliveries().size());
        }
    }
    private void input(String label, int x, int y, String value, java.util.function.Consumer<String> changed) {
        EditBox box = host.control(new EditBox(client.font, x, y, Math.min(140, width - x - 8), 20, Component.literal(label)));
        box.setMaxLength(3); box.setValue(value);
        box.setResponder(next -> { changed.accept(next); result.reset(); });
    }
    private int rows() { return Math.max(1, (height - 86) / 24); }
    private void buildPlaces() {
        List<Place> places = page == Page.DOCKS ? docks : sources;
        offset = Math.min(offset, Math.max(0, (places.size() - 1) / rows()));
        for (int i = offset * rows(); i < Math.min(places.size(), (offset + 1) * rows()); i++) {
            Place place = places.get(i);
            host.button(place.label(), page == Page.DOCKS ? "dock" : "package", 8, 42 + i % rows() * 24, width - 16, () -> {
                if (page == Page.DOCKS) {
                    dock = place.position();
                    DockStatus saved = status();
                    source = saved == null ? null : saved.source(); enabled = saved == null || saved.enabled();
                    useRules(saved == null ? SupplyNetworkPolicy.defaults() : saved.rules());
                } else source = place.position();
                result.reset(); show(Page.ROUTE);
            });
        }
        paginate(places.size());
    }
    private void paginate(int total) {
        host.button("前へ", "chevron-left", 8, height - 24, 80, () -> { offset--; host.rebuildNetwork(); }).active = offset > 0;
        host.button("次へ", "chevron-right", 94, height - 24, 80, () -> { offset++; host.rebuildNetwork(); }).active = (offset + 1) * rows() < total;
        host.button("更新", "refresh-cw", width - 88, height - 24, 80, () -> { scan(); host.rebuildNetwork(); });
    }
    private void scan() {
        if (client.level == null || client.player == null) return;
        var targets = new LinkedHashMap<Long, Place>();
        var warehouses = new LinkedHashMap<Long, Place>();
        knownDocks = SupplyNetworkClient.docks().stream().map(DockStatus::dock).toList();
        for (DockStatus saved : SupplyNetworkClient.docks())
            targets.put(saved.dock(), new Place(saved.dock(), "Dock " + coordinates(saved.dock())));
        BlockPos center = client.player.blockPosition();
        // Bounded loaded-only scan. Opening a menu never loads a chunk or registers a route.
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-8, -8, -8), center.offset(8, 8, 8))) {
            if (!client.level.hasChunkAt(pos)) continue;
            var block = client.level.getBlockEntity(pos);
            if (block instanceof DockBlockEntity target && target.isOwnedBy(client.player.getUUID()))
                targets.put(pos.asLong(), new Place(pos.asLong(), "Dock " + coordinates(pos.asLong())));
            if (warehouse(pos)) warehouses.put(pos.asLong(), new Place(pos.asLong(),
                client.level.getBlockState(pos).getBlock().getName().getString() + " " + coordinates(pos.asLong())));
        }
        Comparator<Place> nearest = Comparator.comparingDouble(place -> BlockPos.of(place.position()).distSqr(center));
        docks = targets.values().stream().sorted(nearest).limit(SupplyNetworkPolicy.MAX_CANDIDATES).toList();
        sources = warehouses.values().stream().sorted(nearest).limit(SupplyNetworkPolicy.MAX_CANDIDATES).toList();
    }
    private boolean warehouse(BlockPos pos) {
        if (client.level == null || client.player == null || !client.level.hasChunkAt(pos)) return false;
        var block = client.level.getBlockEntity(pos);
        return (block instanceof ChestBlockEntity || block instanceof BarrelBlockEntity || block instanceof ShulkerBoxBlockEntity)
            && block instanceof BaseContainerBlockEntity container && !container.isLocked()
            && container.getContainerSize() <= SupplyNetworkPolicy.MAX_SOURCE_SLOTS && container.stillValid(client.player);
    }
    private List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        for (SupplyKind type : SupplyKind.values()) {
            var min = SupplyNetworkUiPolicy.number(minimum[type.index()], SupplyNetworkPolicy.MAX_STOCK);
            var order = SupplyNetworkUiPolicy.number(priority[type.index()], 100);
            if (min.isEmpty() || order.isEmpty()) return null;
            rules.add(new Rule(type, min.getAsInt(), order.getAsInt()));
        }
        return List.copyOf(rules);
    }
    private String problem() {
        if (!ClientPlayNetworking.canSend(SupplyNetworkConfigPayload.TYPE)) return "補給の設定を送信できません";
        if (dock == null) return "対象のDockを選んでください";
        if (!enabled) return status() == null ? "登録済みの経路を選んでください" : "";
        if (rules() == null) return "最低在庫は0〜576、優先度は0〜100です";
        if (source == null) return "補給元を選んでください";
        BlockPos from = BlockPos.of(source), to = BlockPos.of(dock);
        if (from.equals(to) || from.distSqr(to) > (long) SupplyNetworkPolicy.MAX_ROUTE_DISTANCE * SupplyNetworkPolicy.MAX_ROUTE_DISTANCE)
            return "Dockと補給元は256ブロック以内にしてください";
        if (!warehouse(from)) return "補給元の近くで設定してください";
        if (client.level == null || !client.level.hasChunkAt(to)) return "対象のDockを読み込めません";
        if (!(client.level.getBlockEntity(to) instanceof DockBlockEntity target) || !target.isOwnedBy(client.player.getUUID()))
            return "自分のDockを選んでください";
        return "";
    }
    private void save() {
        if (result.state() == SupplyNetworkUiPolicy.State.PENDING) return;
        String problem = problem();
        if (!problem.isEmpty()) { notice = problem; return; }
        DockStatus current = status();
        // Disabling retains the saved source/rules, exactly as the server disable contract does.
        long from = enabled ? source : current.source();
        List<Rule> savedRules = enabled ? rules() : current.rules();
        result.sent(configuration(from, dock, enabled, savedRules), current, now());
        ClientPlayNetworking.send(new SupplyNetworkConfigPayload(from, dock, enabled, savedRules));
        notice = "";
    }
    private static SupplyNetworkUiPolicy.Configuration configuration(long source, long dock, boolean enabled, List<Rule> rules) {
        return new SupplyNetworkUiPolicy.Configuration(source, dock, enabled, rules.stream()
            .map(rule -> new SupplyNetworkUiPolicy.Rule(rule.kind().index(), rule.minimum(), rule.priority())).toList());
    }
    void draw(GuiGraphicsExtractor g) {
        host.text(g, switch (page) {
            case DOCKS -> "補給するDock"; case SOURCES -> "補給元を選ぶ"; case RULES -> name(SupplyKind.values()[kind]) + "の補給数";
            case REVIEW -> "保存する設定"; case DELIVERIES -> "運搬中の荷物"; case ROUTE -> "自動補給";
        }, 82, 10, width - 90, HmiArt.TEXT);
        DockStatus live = status();
        switch (page) {
            case DOCKS, SOURCES -> {
                List<Place> places = page == Page.DOCKS ? docks : sources;
                host.text(g, page == Page.DOCKS ? "自分のDock" : "近くのチェスト・樽・シュルカーボックス", 8, 28, width - 16, HmiArt.MUTED);
                if (places.isEmpty()) host.lines(g, page == Page.DOCKS ? "Dockを確認できません。近づいて更新してください。"
                    : "補給元を確認できません。容器の近くで更新してください。", 8, 60, width - 16, HmiArt.AMBER);
            }
            case ROUTE -> {
                host.text(g, "Dock " + coordinates(dock), 8, 29, width - 16, HmiArt.CYAN);
                host.text(g, source == null ? "補給元: 未選択" : "補給元 " + coordinates(source), 8, 40, width - 16, HmiArt.MUTED);
            }
            case RULES -> {
                host.text(g, "最低在庫 (0〜576)", 8, 55, 145, HmiArt.TEXT);
                host.text(g, "優先度 (0〜100)", 164, 55, width - 172, HmiArt.TEXT);
                var stock = live == null ? null : live.stock().stream().filter(s -> s.rule().kind().index() == kind).findFirst().orElse(null);
                host.text(g, stock == null ? "現在の在庫を確認できません" : "在庫 " + amount(stock.available()) + " / 運搬中 "
                    + stock.incoming() + " / 不足 " + amount(stock.missing()), 8, 100, width - 16, HmiArt.CYAN);
                host.text(g, "数は補給品の個数。優先度は大きい順。", 8, 115, width - 16, HmiArt.MUTED);
            }
            case REVIEW -> {
                host.text(g, "Dock " + coordinates(dock) + " / " + (enabled ? "自動補給ON" : "自動補給OFF"), 8, 29, width - 16, HmiArt.CYAN);
                host.text(g, "補給元 " + coordinates(!enabled && live != null ? Long.valueOf(live.source()) : source), 8, 41, width - 16, HmiArt.MUTED);
                List<Rule> preview = !enabled && live != null ? live.rules() : rules();
                if (preview != null) for (int i = 0; i < preview.size(); i++) {
                    Rule r = preview.get(i);
                    host.text(g, name(r.kind()) + "  最低 " + r.minimum() + "個  優先度 " + r.priority(), 8, 56 + i * 14, width - 16, HmiArt.TEXT);
                }
            }
            case DELIVERIES -> {
                if (live == null || live.deliveries().isEmpty()) host.text(g, live == null ? "運搬状況を確認できません" : "運搬中の荷物はありません", 8, 52, width - 16, HmiArt.MUTED);
                else for (int i = offset * rows(); i < Math.min(live.deliveries().size(), (offset + 1) * rows()); i++) {
                    var job = live.deliveries().get(i);
                    host.text(g, name(job.kind()) + " " + job.remaining() + "個 / " + statusName(job.status()), 8,
                        43 + i % rows() * 24, width - 16, HmiArt.TEXT);
                }
            }
        }
        String message = !notice.isEmpty() ? notice : switch (result.state()) {
            case PENDING -> "保存結果を確認中"; case CONFIRMED -> "設定を保存しました";
            case UNCONFIRMED -> "保存を確認できません。状況を更新してください";
            case IDLE -> page == Page.REVIEW ? problem() : live == null ? "" : statusName(live.status());
        };
        host.text(g, message, 8, height - 39, width - 16, HmiArt.AMBER);
    }
    private static String amount(int value) { return value < 0 ? "未確認" : Integer.toString(value); }
    private static String coordinates(Long position) {
        if (position == null) return "未選択";
        BlockPos pos = BlockPos.of(position);
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
    private static String name(SupplyKind kind) {
        return switch (kind) {
            case FUEL -> "燃料"; case GUN -> "機関砲弾"; case LASER -> "兵装電力"; case MISSILE -> "ミサイル"; case REPAIR -> "修理材";
        };
    }
    private static String statusName(Status status) {
        return switch (status) {
            case DISABLED -> "自動補給OFF"; case READY -> "補給待機中"; case IN_TRANSIT -> "運搬中";
            case RETURNING -> "補給元へ戻っています"; case RECHARGING -> "運搬機の充電中";
            case WAIT_CHUNK -> "周囲の読み込み待ち"; case NO_IDLE_CARGO -> "空いている貨物機がありません";
            case SOURCE_SHORTAGE -> "補給元の在庫が足りません"; case SOURCE_LOST -> "補給元が見つかりません";
            case DOCK_LOST -> "Dockが見つかりません"; case DOCK_FULL -> "Dockがいっぱいです";
            case RESERVATION_INVALID, ASSIGNMENT_LOST -> "運搬の割り当てを確認できません";
            case CARGO_MISMATCH -> "運搬する荷物が一致しません"; case CANCELLED -> "運搬を取り消しました";
            case LOAD_RETAINED -> "運搬機に荷物が残っています"; case RUNTIME_UNAVAILABLE -> "補給を開始できません";
            case ACCESS_DENIED -> "この経路を操作できません"; case CAPACITY_LIMIT -> "登録できる経路の上限です";
        };
    }
}
