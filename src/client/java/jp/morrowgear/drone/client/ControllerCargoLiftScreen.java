package jp.morrowgear.drone.client;

import java.util.List;
import jp.morrowgear.drone.network.CargoLiftCommandPayload;
import jp.morrowgear.drone.network.CargoLiftStatusRequest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** One-screen operator workflow for selecting, dispatching and safely cancelling a four-aircraft lift. */
final class ControllerCargoLiftScreen extends Screen {
    private record Row(long source, String label, String mission, String status, int distance) {
        boolean active() { return !mission.isBlank(); }
    }

    private final Screen parent;
    private List<Row> rows = List.of();
    private int selected;
    private int page;
    private BlockPos destination = BlockPos.ZERO;
    private String notice = "";
    private Button start, cancel, previous, next;
    private int refreshTicks;

    ControllerCargoLiftScreen(Screen parent) {
        super(Component.literal("コンテナ共同輸送"));
        this.parent = parent;
    }

    private int capacity() { return Math.max(1, (height - 152) / 50); }

    @Override protected void init() {
        if (minecraft.player != null && destination.equals(BlockPos.ZERO)) destination = minecraft.player.blockPosition();
        requestStatus();
        previous = addRenderableWidget(ControllerButton.hmi(Component.literal("前へ"), b -> page = Math.max(0, page - 1))
            .bounds(12, height - 30, 54, 20).build());
        next = addRenderableWidget(ControllerButton.hmi(Component.literal("次へ"), b -> page++)
            .bounds(72, height - 30, 54, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal("搬送先=現在地"), b -> {
            if (minecraft.player != null) { destination = minecraft.player.blockPosition(); notice = "搬送先を現在地に更新しました"; }
        }).bounds(12, 55, 124, 22).build());
        start = addRenderableWidget(ControllerButton.hmi(Component.literal("4機で搬送開始"), b -> start())
            .bounds(width - 252, height - 30, 112, 20).build());
        cancel = addRenderableWidget(ControllerButton.hmi(Component.literal("安全中止"), b -> cancel())
            .bounds(width - 134, height - 30, 62, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal("戻る"), b -> onClose())
            .bounds(width - 66, height - 30, 54, 20).build());
    }

    private void start() {
        if (selected < 0 || selected >= rows.size()) return;
        Row row = rows.get(selected);
        if (row.active()) { notice = "進行中の任務です / 安全中止を使用してください"; return; }
        if (!ClientPlayNetworking.canSend(CargoLiftCommandPayload.TYPE)) { notice = "共同輸送リンクがありません"; return; }
        ClientPlayNetworking.send(CargoLiftCommandPayload.start(row.source(), destination.asLong()));
        notice = "共同輸送要求を送信しました / 4機の応答を確認中";
    }

    private void cancel() {
        if (selected < 0 || selected >= rows.size()) return;
        Row row = rows.get(selected);
        if (!row.active()) { notice = "このコンテナは共同輸送中ではありません"; return; }
        if (!ClientPlayNetworking.canSend(CargoLiftCommandPayload.TYPE)) { notice = "共同輸送リンクがありません"; return; }
        ClientPlayNetworking.send(CargoLiftCommandPayload.cancel(row.mission()));
        notice = "安全中止を送信しました / コンテナは最寄りの安全地点へ着地します";
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, HmiArt.BACKGROUND);
        g.text(font, title, 12, 14, HmiArt.TEXT, true);
        g.text(font, "カーゴコンテナを選択し、搬送先で現在地を登録して開始", 12, 34, HmiArt.MUTED);
        g.text(font, "搬送先  " + destination.toShortString(), 148, 62, HmiArt.CYAN);
        rows = scan();
        if (rows.isEmpty()) selected = -1;
        else selected = Math.clamp(selected, 0, rows.size() - 1);
        int pages = Math.max(1, (rows.size() + capacity() - 1) / capacity());
        page = Math.clamp(page, 0, pages - 1);
        int first = page * capacity();
        for (int i = 0; i < capacity() && first + i < rows.size(); i++) {
            int index = first + i, y = 88 + i * 50;
            Row row = rows.get(index);
            int color = index == selected ? HmiArt.RAISED : HmiArt.PANEL;
            g.fill(12, y, width - 12, y + 44, color);
            if (index == selected) g.fill(12, y, 15, y + 44, HmiArt.CYAN);
            g.text(font, HmiArt.fitText(row.label(), width - 124, font::width), 22, y + 6,
                row.active() ? HmiArt.AMBER : HmiArt.TEXT);
            g.text(font, HmiArt.fitText(row.status(), width - 124, font::width), 22, y + 22, HmiArt.MUTED);
            g.text(font, row.distance() + "m", width - 62, y + 14, HmiArt.CYAN);
        }
        if (rows.isEmpty()) g.text(font, "周辺に所有するカーゴコンテナ、または進行中の共同輸送がありません", 12, 96, HmiArt.MUTED);
        Row selectedRow = selected >= 0 && selected < rows.size() ? rows.get(selected) : null;
        start.active = selectedRow != null && !selectedRow.active();
        cancel.active = selectedRow != null && selectedRow.active();
        previous.active = page > 0;
        next.active = page + 1 < pages;
        g.text(font, (page + 1) + "/" + pages, 138, height - 24, HmiArt.MUTED);
        if (!notice.isBlank()) g.text(font, HmiArt.fitText(notice, width - 24, font::width), 12, height - 50, HmiArt.AMBER);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        int first = page * capacity();
        for (int i = 0; i < capacity() && first + i < rows.size(); i++) {
            int y = 88 + i * 50;
            if (event.x() >= 12 && event.x() < width - 12 && event.y() >= y && event.y() < y + 44) {
                selected = first + i;
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    private List<Row> scan() {
        return CargoLiftClientStore.rows().stream().map(row -> new Row(row.source(), row.label(),
            row.mission(), row.status(), row.distance())).toList();
    }

    @Override public void tick() {
        if (++refreshTicks >= 20) { refreshTicks = 0; requestStatus(); }
    }

    private void requestStatus() {
        if (ClientPlayNetworking.canSend(CargoLiftStatusRequest.TYPE))
            ClientPlayNetworking.send(new CargoLiftStatusRequest());
    }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
