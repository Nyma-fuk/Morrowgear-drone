package jp.morrowgear.drone.client;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Each confirmation submits one endpoint, matching the existing non-atomic server contract. */
final class ControllerCargoRouteScreen extends Screen {
    record Place(String name, long position, String coordinates, String contents, int distance,
        int verticalOffset, boolean registered) {}
    private final Screen parent;
    private final Supplier<List<Place>> receive;
    private final BiFunction<Long, Long, String> submit;
    private final int targets;
    private List<Place> places;
    private boolean source = true;
    private Long sourceNode, targetNode;
    private int page;
    private String notice = "";
    private long submittedAt;

    ControllerCargoRouteScreen(Screen parent, int targets, Supplier<List<Place>> receive,
            BiFunction<Long, Long, String> submit) {
        super(Component.literal("輸送経路"));
        this.parent = parent;
        this.targets = targets;
        this.receive = receive;
        this.submit = submit;
        places = List.copyOf(receive.get());
    }

    private int capacity() { return Math.max(1, (height - 160) / 25); }

    @Override protected void init() {
        int modeWidth = Math.max(60, Math.min(130, (width - 42) / 3));
        addRenderableWidget(ControllerButton.hmi(Component.literal(source ? "積込元 [選択中]" : "積込元"), b -> {
            source = true; rebuildWidgets();
        }).bounds(12, 32, modeWidth, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal(!source ? "搬送先 [選択中]" : "搬送先"), b -> {
            source = false; rebuildWidgets();
        }).bounds(18 + modeWidth, 32, modeWidth, 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal("再受信"), b -> {
            places = List.copyOf(receive.get()); page = 0; rebuildWidgets();
        }).bounds(24 + modeWidth * 2, 32, modeWidth, 20).build());
        page = Math.min(page, Math.max(0, (places.size() - 1) / capacity()));
        for (int row = 0; row < capacity() && page * capacity() + row < places.size(); row++) {
            Place place = places.get(page * capacity() + row);
            boolean selected = (source ? sourceNode : targetNode) != null
                && (source ? sourceNode : targetNode) == place.position();
            String label = (selected ? "[選択] " : "") + place.name()
                + " / " + place.coordinates() + " / " + place.contents();
            addRenderableWidget(ControllerButton.hmi(Component.literal(font.plainSubstrByWidth(label, width - 40)), b -> {
                if (source) sourceNode = place.position(); else targetNode = place.position();
                rebuildWidgets();
            }).bounds(12, 72 + row * 25, width - 24, 21).build());
        }
        var confirm = addRenderableWidget(ControllerButton.hmi(Component.literal("輸送経路を確定"), b -> {
            if (sourceNode == null || targetNode == null || sourceNode.equals(targetNode)) return;
            notice = submit.apply(sourceNode, targetNode);
            submittedAt = System.currentTimeMillis();
            rebuildWidgets();
        }).bounds(12, height - 62, Math.max(100, Math.min(150, width - 110)), 20).build());
        confirm.active = sourceNode != null && targetNode != null && !sourceNode.equals(targetNode);
        var previous = addRenderableWidget(ControllerButton.hmi(Component.literal("前へ"), b -> { page--; rebuildWidgets(); })
            .bounds(12, height - 28, 54, 20).build());
        var next = addRenderableWidget(ControllerButton.hmi(Component.literal("次へ"), b -> { page++; rebuildWidgets(); })
            .bounds(72, height - 28, 54, 20).build());
        previous.active = page > 0;
        next.active = (page + 1) * capacity() < places.size();
        addRenderableWidget(ControllerButton.hmi(Component.literal("戻る"), b -> onClose())
            .bounds(width - 72, height - 28, 60, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, 0xFF121514);
        g.text(font, "手動チェスト間輸送 / 対象 " + targets + "機", 12, 12, HmiArt.TEXT);
        g.text(font, "近傍物流ノード / 水平32m・上下8m / 登録済み経路を共有", 12, 58, HmiArt.MUTED);
        String route = "積込元 " + label(sourceNode) + "  →  搬送先 " + label(targetNode);
        g.text(font, font.plainSubstrByWidth(route, width - 190), 170, height - 57, HmiArt.CYAN);
        if (places.isEmpty()) g.text(font, "候補なし / 容器と同じ高度付近へ移動して再受信", 12, 80, HmiArt.MUTED);
        String status = submittedAt > 0 && System.currentTimeMillis() - submittedAt >= 5000 && !notice.startsWith("未送信")
            ? "結果未確認 / 現在行動を確認 / 自動再送なし" : notice;
        g.text(font, font.plainSubstrByWidth(status, width - 24), 12, height - 77, HmiArt.MUTED);
        super.extractRenderState(g, x, y, delta);
    }

    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private String label(Long position) {
        if (position == null) return "未選択";
        return places.stream().filter(place -> place.position() == position).findFirst()
            .map(Place::name).orElse("受信範囲外");
    }
}
