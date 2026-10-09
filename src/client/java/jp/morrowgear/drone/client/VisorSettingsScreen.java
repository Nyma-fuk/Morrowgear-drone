package jp.morrowgear.drone.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Static, explicitly fictional HUD samples; editing never mutates the live config. */
public final class VisorSettingsScreen extends Screen {
    private final Screen parent;
    private VisorClientConfig.Presentation draft;
    private int scene;
    private boolean previewPage;
    private String error = "";
    private static final String[] SCENES = {"通常", "戦闘", "緊急"};

    public VisorSettingsScreen(Screen parent) {
        super(Component.literal("バイザー設定"));
        this.parent = parent;
        draft = VisorClientConfig.current().presentation();
    }
    private boolean wide() { return width >= 600 && height >= 300; }
    private void choose(VisorClientConfig.Presentation value) { draft = value; error = ""; rebuildWidgets(); }

    @Override protected void init() {
        if (!wide()) {
            button("設定", 12, 32, 72, () -> { previewPage = false; rebuildWidgets(); });
            button("プレビュー", 90, 32, 88, () -> { previewPage = true; rebuildWidgets(); });
        }
        if (wide() || !previewPage) {
            int y = wide() ? 58 : 74;
            button(draft.density() == VisorClientConfig.Density.MINIMAL ? "選択: 少なめ" : "少なめ", 12, y, 102,
                () -> choose(new VisorClientConfig.Presentation(VisorClientConfig.Density.MINIMAL, draft.backgroundPercent(), draft.large())));
            button(draft.density() == VisorClientConfig.Density.STANDARD ? "選択: 標準" : "標準", 120, y, 102,
                () -> choose(new VisorClientConfig.Presentation(VisorClientConfig.Density.STANDARD, draft.backgroundPercent(), draft.large())));
            addRenderableWidget(new BackgroundSlider(12, y + 39, Math.min(210, width - 24)));
            button(!draft.large() ? "選択: 標準" : "標準", 12, y + 78, 102,
                () -> choose(new VisorClientConfig.Presentation(draft.density(), draft.backgroundPercent(), false)));
            button(draft.large() ? "選択: 大" : "大", 120, y + 78, 102,
                () -> choose(new VisorClientConfig.Presentation(draft.density(), draft.backgroundPercent(), true)));
        }
        if (wide() || previewPage) {
            int x = wide() ? 248 : 12, y = wide() ? 32 : 58;
            for (int i = 0; i < SCENES.length; i++) {
                int option = i;
                button((scene == i ? "選択: " : "") + SCENES[i], x + i * 80, y, 76,
                    () -> { scene = option; rebuildWidgets(); });
            }
        }
        button("初期値", 12, height - 28, 60, () -> choose(VisorClientConfig.Presentation.DEFAULT));
        button("取消", width - 144, height - 28, 60, this::onClose);
        button("保存", width - 78, height - 28, 66, () -> {
            if (VisorClientConfig.current().savePresentation(draft)) onClose();
            else error = "保存できませんでした。変更は未適用です";
        });
    }
    private void button(String label, int x, int y, int w, Runnable action) {
        addRenderableWidget(ControllerButton.hmi(Component.literal(label), button -> action.run()).bounds(x, y, w, 20).build());
    }
    private final class BackgroundSlider extends AbstractSliderButton {
        BackgroundSlider(int x, int y, int w) {
            super(x, y, w, 20, Component.empty(), (draft.backgroundPercent() - 60) / 60.0);
            updateMessage();
        }
        @Override protected void updateMessage() { setMessage(Component.literal("背景濃度 " + draft.backgroundPercent() + "%")); }
        @Override protected void applyValue() {
            draft = new VisorClientConfig.Presentation(draft.density(), 60 + (int)Math.round(value * 60), draft.large());
            error = "";
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, HmiArt.BACKGROUND);
        g.fill(0, 0, width, 26, HmiArt.PANEL);
        HmiArt.icon(g, "radar", 12, 6, 16);
        g.text(font, title, 34, 10, HmiArt.TEXT);
        if (wide() || !previewPage) {
            int y = wide() ? 45 : 61;
            g.text(font, "通常情報の密度", 12, y, HmiArt.MUTED);
            g.text(font, "背景", 12, y + 39, HmiArt.MUTED);
            g.text(font, "文字とHUDの大きさ", 12, y + 78, HmiArt.MUTED);
            int limit = wide() ? 220 : width - 24;
            String note = draft.large() && effectiveScale() == 1f
                ? "現在は標準表示。大表示には1440×810以上が必要です（論理960×540）。"
                : "緊急警報・固定対象・要対応情報は密度を下げても保持します。";
            int lineY = y + 116;
            for (var line : font.split(Component.literal(note), limit)) {
                if (lineY + 9 >= height - 48) break;
                g.text(font, line, 12, lineY, HmiArt.AMBER); lineY += 11;
            }
        }
        if (wide() || previewPage) drawPreview(g, wide() ? 248 : 12, wide() ? 62 : 88,
            width - (wide() ? 260 : 24), height - (wide() ? 116 : 126));
        if (!error.isEmpty()) g.text(font, HmiArt.fitText(error, width - 24, font::width), 12, height - 42, HmiArt.RED);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private float effectiveScale() {
        return draft.effectiveHudScale(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
    }
    private void drawPreview(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.text(font, HmiArt.fitText("設定プレビュー / 架空の状況・実データではありません", w, font::width), x, y - 9, HmiArt.AMBER);
        if (w < 240 || h < 85) {
            g.text(font, "表示領域を広げると配置を確認できます", x, y + 15, HmiArt.MUTED);
            return;
        }
        g.fill(x, y + 6, x + w, y + h, 0xFF35433D);
        int panelWidth = Math.min(150, w * 28 / 100);
        int unit = Math.round(12 * effectiveScale());
        int headerH = unit * 2 + 8;
        g.fill(x + 4, y + 10, x + panelWidth, y + 10 + headerH, draft.panelAlpha(0xD91A1E1C));
        sampleText(g, "M / LINK", x + 8, y + 14, panelWidth - 12, HmiArt.CYAN);
        sampleText(g, scene == 0 ? "哨戒中" : "交戦 03", x + 8, y + 14 + unit, panelWidth - 12, HmiArt.TEXT);
        if (scene != 0 && h >= headerH + 100) {
            int groups = Math.min(3, (h * 60 / 100 - headerH) / unit);
            for (int i = 0; i < groups; i++) {
                int gy = y + 14 + headerH + i * unit;
                g.fill(x + 4, gy - 2, x + panelWidth, gy + unit - 2, draft.panelAlpha(0xBD121514));
                sampleText(g, "0" + (i + 1) + " 北東 " + (80 + i * 40) + "m", x + 8, gy, panelWidth - 12, HmiArt.TEXT);
            }
        }
        int right = x + w - panelWidth;
        g.fill(right, y + 10, x + w - 4, y + 10 + headerH, draft.panelAlpha(0xD91A1E1C));
        sampleText(g, scene == 0 ? "固定 S01" : "交戦01", right + 4, y + 14, panelWidth - 12, HmiArt.CYAN);
        sampleText(g, scene == 0 ? "随伴中" : "迎撃開始", right + 4, y + 14 + unit, panelWidth - 12, HmiArt.TEXT);
        // Markers, unlike panels, may occupy the central sight area.
        int count = Math.min(4, draft.ordinaryMarkerBudget(4));
        for (int i = 0; i < count; i++) {
            int mx = x + w * (32 + i * 11) / 100;
            int my = y + h / 2 + (i % 2) * 12;
            HmiArt.icon(g, "radar", mx, my, 10);
            if (i < draft.ordinaryLabelBudget(4)) sampleText(g, "S0" + (i + 1), mx, my + 12, 30, HmiArt.TEXT);
        }
        if (scene == 2) {
            int railY = y + Math.min(h * 65 / 100, h * 85 / 100 - unit - 10);
            g.fill(x + 4, railY, x + Math.min(w - 8, 220), railY + unit + 8, draft.panelAlpha(0xD20E1312));
            g.fill(x + 4, railY, x + 6, railY + unit + 8, HmiArt.AMBER);
            sampleText(g, "要救援 S04 / 電力喪失", x + 10, railY + 4, Math.min(w - 24, 200), HmiArt.AMBER);
        }
        g.fill(x, y + h * 85 / 100, x + w, y + h, 0xFF242A27);
        g.text(font, "標準HUD領域", x + w / 2 - 30, y + h - 11, HmiArt.MUTED);
    }
    private void sampleText(GuiGraphicsExtractor g, String text, int x, int y, int maxWidth, int color) {
        float scale = effectiveScale();
        String visible = HmiArt.fitText(text, (int)(maxWidth / scale), font::width);
        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        g.text(font, visible, Math.round(x / scale), Math.round(y / scale), color);
        g.pose().popMatrix();
    }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
