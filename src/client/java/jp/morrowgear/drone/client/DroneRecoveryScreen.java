package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jp.morrowgear.drone.DroneEntity;
import jp.morrowgear.drone.DroneRole;
import jp.morrowgear.drone.SalvageState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Read-only C09 surface. Does not issue recovery or inventory commands. */
public final class DroneRecoveryScreen extends Screen {
    private final Screen parent;
    private final DroneRecoveryModel model = new DroneRecoveryModel();
    private final List<Button> targets = new ArrayList<>();
    private Set<String> current = Set.of();
    private String selected;
    private int detailPage, ticks;
    private Button previous, next, detailPrevious, detailNext;
    private String session;
    private boolean beaconView;
    private jp.morrowgear.drone.network.RecoverySnapshotPayload snapshot;
    private long requestToken;
    private int receivedAt = -1000;
    private boolean receiverRegistered;
    private boolean diagnostics;
    private final java.util.Map<String, Long> observedTimes = new java.util.HashMap<>();

    public DroneRecoveryScreen(Screen parent) { this(parent, null); }
    public DroneRecoveryScreen(Screen parent, UUID target) {
        super(Component.literal("機体回収 / 救助進捗"));
        this.parent = parent;
        selected = target == null ? null : target.toString();
    }

    private boolean wide() { return width >= 600; }
    private int detailX() { return wide() ? 216 : 12; }
    private int listCapacity() { return Math.max(1, (height - 100) / 26); }

    @Override protected void init() {
        if (!receiverRegistered) {
            receiverRegistered = net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerReceiver(
                jp.morrowgear.drone.network.RecoverySnapshotPayload.TYPE, (payload, context) -> context.client().execute(() -> {
                    if (minecraft.player == null || !jp.morrowgear.drone.RecoverySnapshotPolicy.accepts(
                        minecraft.player.getUUID(), payload.owner(), requestToken, payload.token())) return;
                    snapshot = payload; receivedAt = ticks;
                    long observed = System.nanoTime();
                    payload.rows().forEach(row -> observedTimes.put(row.target(), observed));
                    refresh();
                }));
        }
        targets.clear();
        previous = addRenderableWidget(ControllerButton.hmi(Component.literal("前の対象"), b -> select(-1))
            .bounds(12, 32, 72, 20).build());
        next = addRenderableWidget(ControllerButton.hmi(Component.literal("次の対象"), b -> select(1))
            .bounds(90, 32, 72, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal(beaconView ? "機体一覧へ" : "未照合通知へ"), b -> {
            beaconView = !beaconView; selected = null; detailPage = 0; rebuildWidgets();
        }).bounds(width - 102, 32, 90, 20).build());
        detailPrevious = addRenderableWidget(ControllerButton.hmi(Component.literal("前頁"), b -> detailPage = Math.max(0, detailPage - 1))
            .bounds(12, height - 28, 48, 20).build());
        detailNext = addRenderableWidget(ControllerButton.hmi(Component.literal("次頁"), b -> detailPage++)
            .bounds(66, height - 28, 48, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal("戻る"), b -> onClose())
            .bounds(width - 66, height - 28, 54, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal(diagnostics ? "通常表示" : "詳細情報"), b -> {
            diagnostics = !diagnostics; detailPage = 0; rebuildWidgets();
        }).bounds(width - 132, height - 28, 60, 20).build());
        if (wide()) for (int i = 0; i < listCapacity(); i++) {
            int slot = i;
            targets.add(addRenderableWidget(ControllerButton.hmi(Component.empty(), b -> {
                var rows = model.rows(beaconView);
                int start = Math.max(0, selectedIndex(rows)) / listCapacity() * listCapacity();
                if (start + slot < rows.size()) { selected = rows.get(start + slot).key(); detailPage = 0; }
            }).bounds(12, 64 + i * 26, 188, 22).build()));
        }
        refresh();
        requestSnapshot();
    }

    private int selectedIndex(List<DroneRecoveryModel.Row> rows) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).key().equals(selected)) return i;
        return -1;
    }
    private void select(int direction) {
        var rows = model.rows(beaconView);
        if (rows.isEmpty()) return;
        int index = Math.max(0, Math.min(rows.size() - 1, selectedIndex(rows) + direction));
        selected = rows.get(index).key(); detailPage = 0;
    }
    @Override public void tick() {
        ticks++;
        if (ticks % 10 == 0) refresh();
        if (ticks % 20 == 0) requestSnapshot();
    }
    private void requestSnapshot() {
        if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(jp.morrowgear.drone.network.RecoverySnapshotRequest.TYPE)) return;
        requestToken = System.nanoTime();
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new jp.morrowgear.drone.network.RecoverySnapshotRequest(requestToken));
    }

    private void refresh() {
        if (minecraft.level == null || minecraft.player == null) { current = Set.of(); return; }
        String dimension = minecraft.level.dimension().identifier().toString();
        String nowSession = minecraft.player.getUUID().toString();
        // Never reuse entity IDs across dimensions or player sessions.
        if (session != null && !session.equals(nowSession)) { current = Set.of(); return; }
        session = nowSession;
        long now = minecraft.level.getGameTime();
        List<DroneEntity> drones = new ArrayList<>();
        for (var entity : minecraft.level.entitiesForRendering())
            if (entity instanceof DroneEntity drone && drone.isAlive() && drone.isOwnedBy(minecraft.player.getUUID())) drones.add(drone);
        List<DroneRecoveryModel.Row> updates = new ArrayList<>();
        Set<String> visible = new HashSet<>();
        if (snapshot == null) for (DroneEntity target : drones) {
            DroneEntity carrier = drones.stream().filter(d -> d.role() == DroneRole.SALVAGE
                && d.salvageState() != SalvageState.IDLE && d.salvageTargetEntityId() == target.getId()).findFirst().orElse(null);
            String key = target.getUUID().toString();
            if (!target.isPowerLost() && carrier == null && model.rows().stream().noneMatch(r -> r.key().equals(key))) continue;
            updates.add(new DroneRecoveryModel.Row(key, target.unitId(), dimension, coordinates(target), key,
                carrier == null ? "未確認" : carrier.unitId() + " / " + carrier.getUUID(),
                carrier == null ? null : carrier.salvageState(),
                DroneRecoveryModel.unsyncedDestination(),
                carrier == null ? target.dataLinkStatus() : carrier.dataLinkStatus(),
                "飛行電力 " + target.batteryPercent() + "% / " + (target.isPowerLost() ? "LOST" : "通電確認・任務復帰未確認"), now));
            visible.add(key);
        }
        if (snapshot != null) for (var row : snapshot.rows()) {
            SalvageState stage = null;
            try { if (!row.stage().isBlank()) stage = SalvageState.valueOf(row.stage()); }
            catch (IllegalArgumentException ignored) { }
            boolean fresh = jp.morrowgear.drone.RecoverySnapshotPolicy.fresh(ticks, receivedAt);
            updates.add(new DroneRecoveryModel.Row(row.target(), row.unit(), row.dimension(), row.position(), row.target(),
                row.rescuer().isBlank() ? "未担当" : row.rescuer(), stage,
                jp.morrowgear.drone.RecoverySnapshotPolicy.destinationLabel(row.destinationKnown(), row.destinationPosition()), row.reason(),
                jp.morrowgear.drone.RecoverySnapshotPolicy.reception(true, fresh, dimension, row.dimension()), snapshot.tick(),
                row.engineer().isBlank() ? "担当未確認" : row.engineer(),
                row.originalMission().isBlank() ? "保存された任務名称なし / 不明" : row.originalMission()));
            if (fresh) visible.add(row.target());
        }
        for (var beacon : PowerLostBeaconStore.snapshot(now)) {
            // Beacon labels are not UUIDs: keep them separate from entity identity.
            String key = "beacon:" + beacon.dimension() + ":" + beacon.unitId();
            updates.add(new DroneRecoveryModel.Row(key, beacon.unitId() + " / ビーコン", beacon.dimension(),
                (int)Math.floor(beacon.position().x) + ", " + (int)Math.floor(beacon.position().y) + ", " + (int)Math.floor(beacon.position().z),
                "UUID未同期（機体行との同一性未確定）", "未同期", null, "未同期", "停止理由未同期",
                "最終LOST通知 / 通知から " + Math.max(0, now - beacon.detectedTick()) + " tick", beacon.detectedTick()));
            visible.add(key);
        }
        model.observe(updates); current = Set.copyOf(visible);
        var retained = model.rows().stream().map(DroneRecoveryModel.Row::key).collect(java.util.stream.Collectors.toSet());
        observedTimes.keySet().retainAll(retained);
        var rows = model.rows(beaconView);
        if (selectedIndex(rows) < 0 && !rows.isEmpty()) selected = rows.getFirst().key();
    }
    private static String coordinates(DroneEntity drone) { return drone.blockPosition().toShortString(); }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, HmiArt.BACKGROUND);
        g.fill(0, 0, width, 26, HmiArt.PANEL);
        HmiArt.icon(g, "wrench", 12, 6, 16);
        g.text(font, title, 34, 10, HmiArt.TEXT);
        if (snapshot != null && snapshot.truncated())
            g.text(font, "上限128件", Math.max(220, width - 90), 10, HmiArt.AMBER);
        var rows = model.rows(beaconView);
        int index = selectedIndex(rows);
        previous.active = index > 0; next.active = index >= 0 && index + 1 < rows.size();
        g.text(font, HmiArt.fitText((Math.max(0, index + 1)) + "/" + rows.size(), Math.max(0, width - 280), font::width), 168, 38, HmiArt.MUTED);
        int start = Math.max(0, index) / listCapacity() * listCapacity();
        for (int i = 0; i < targets.size(); i++) {
            Button button = targets.get(i);
            button.visible = start + i < rows.size();
            if (button.visible) button.setMessage(Component.literal(HmiArt.fitText(
                (start + i == index ? "> " : "") + rows.get(start + i).unit(), 174, font::width)));
        }
        if (wide()) g.fill(204, 58, 205, height - 36, HmiArt.LINE);
        List<DisplayLine> lines = new ArrayList<>();
        if (index < 0) field(lines, "", beaconView ? "未照合のビーコン通知はありません" : "回収対象の機体情報は未受信です", HmiArt.MUTED);
        else {
            var row = rows.get(index);
            field(lines, "対象", row.unit(), HmiArt.CYAN);
            field(lines, "工程", (current.contains(row.key()) ? "" : "最終確認: ") + DroneRecoveryModel.phase(row.stage()), HmiArt.CYAN);
            field(lines, "搬送先", row.dock().contains("未同期") ? "配送先情報を受信待ち" : row.dock(), HmiArt.TEXT);
            field(lines, "対象電力", targetPower(row), HmiArt.TEXT);
            field(lines, "状況", RecoveryDisplayText.reason(row.reason()), HmiArt.AMBER);
            field(lines, "回収担当", assignee("回収機", row.carrier()), HmiArt.TEXT);
            field(lines, "救援担当", assignee("整備機", row.engineer()), HmiArt.TEXT);
            field(lines, "元任務", RecoveryDisplayText.mission(row.originalMission()), HmiArt.TEXT);
            field(lines, "任務復帰", "未確認", HmiArt.MUTED);
            field(lines, "現在地", ControllerDisplayText.dimensionName(row.dimension()) + " / " + row.position(), HmiArt.TEXT);
            field(lines, "通信", (current.contains(row.key()) ? "" : "受信待ち / ")
                + RecoveryDisplayText.age(System.nanoTime(), observedTimes.getOrDefault(row.key(), -1L)), HmiArt.MUTED);
            field(lines, "現地操作", "現地で機体の状態を確認してください", HmiArt.MUTED);
            if (diagnostics) {
                field(lines, "対象UUID", row.identity(), HmiArt.MUTED);
                field(lines, "回収UUID", row.carrier(), HmiArt.MUTED);
                field(lines, "整備UUID", row.engineer(), HmiArt.MUTED);
                field(lines, "観測tick", Long.toString(row.seenTick()), HmiArt.MUTED);
                field(lines, "報告原文", row.reason(), HmiArt.MUTED);
            }
        }
        int capacity = DroneRecoveryModel.pageCapacity(height - 102, 16);
        int pages = Math.max(1, (lines.size() + capacity - 1) / capacity);
        detailPage = Math.min(detailPage, pages - 1);
        detailPrevious.active = detailPage > 0; detailNext.active = detailPage + 1 < pages;
        for (int i = 0; i < capacity && detailPage * capacity + i < lines.size(); i++) {
            var line = lines.get(detailPage * capacity + i);
            int y = 64 + i * 16;
            g.text(font, line.label(), detailX(), y, HmiArt.MUTED);
            g.text(font, line.value(), detailX() + 70, y, line.color());
            g.fill(detailX(), y + 13, width - 12, y + 14, HmiArt.LINE);
        }
        g.text(font, (detailPage + 1) + "/" + pages, 122, height - 22, HmiArt.MUTED);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }
    private record DisplayLine(String label, FormattedCharSequence value, int color) {}
    private void field(List<DisplayLine> lines, String label, String text, int color) {
        var wrapped = font.split(Component.literal(text), Math.max(40, width - detailX() - 82));
        for (int i = 0; i < wrapped.size(); i++) lines.add(new DisplayLine(i == 0 ? label : "", wrapped.get(i), color));
    }
    private String assignee(String role, String id) {
        if (minecraft.level != null) for (var entity : minecraft.level.entitiesForRendering())
            if (entity instanceof DroneEntity drone && drone.getUUID().toString().equals(id)
                && minecraft.player != null && drone.isOwnedBy(minecraft.player.getUUID())) return role + " " + drone.unitId();
        return RecoveryDisplayText.assignee(role, id);
    }
    private String targetPower(DroneRecoveryModel.Row row) {
        if (minecraft.level != null && minecraft.level.dimension().identifier().toString().equals(row.dimension()))
            for (var entity : minecraft.level.entitiesForRendering())
                if (entity instanceof DroneEntity drone && drone.getUUID().toString().equals(row.key())
                    && minecraft.player != null && drone.isOwnedBy(minecraft.player.getUUID())) return drone.batteryPercent() + "%";
        return "未受信";
    }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public void removed() {
        if (receiverRegistered) net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.unregisterReceiver(
            jp.morrowgear.drone.network.RecoverySnapshotPayload.TYPE.id());
        receiverRegistered = false;
        super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
}
