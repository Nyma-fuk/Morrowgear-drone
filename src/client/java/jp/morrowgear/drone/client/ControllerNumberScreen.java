package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native numeric editing only changes a local draft; sending remains a separate action. */
final class ControllerNumberScreen extends Screen {
    record Field(String label, int value, int minimum, int maximum, boolean automaticZero) {
        Field(String label, int value, int minimum, int maximum) { this(label, value, minimum, maximum, false); }
    }
    private final Screen parent;
    private final List<Field> fields;
    private final Consumer<List<Integer>> apply;
    private final List<EditBox> inputs = new ArrayList<>();
    private final List<Button> modeButtons = new ArrayList<>();
    private final List<Button> manualButtons = new ArrayList<>();
    private final List<Button> decreaseButtons = new ArrayList<>();
    private final List<Button> increaseButtons = new ArrayList<>();
    private List<String> values;
    private Button confirm;
    private int left, top, formWidth;

    ControllerNumberScreen(Screen parent, String title, List<Field> fields, Consumer<List<Integer>> apply) {
        super(Component.literal(title));
        this.parent = parent; this.fields = List.copyOf(fields); this.apply = apply;
        values = fields.stream().map(field -> Integer.toString(field.value())).toList();
    }
    @Override protected void init() {
        if (!inputs.isEmpty()) values = inputs.stream().map(EditBox::getValue).toList();
        inputs.clear(); modeButtons.clear(); manualButtons.clear(); decreaseButtons.clear(); increaseButtons.clear();
        formWidth = ControllerNumericPolicy.formWidth(width, minecraft.getWindow().getGuiScale());
        left = (width - formWidth) / 2;
        top = Math.max(8, (height - (fields.size() * 34 + 66)) / 2);
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            int row = top + 26 + i * 34;
            EditBox input = new EditBox(font, left, row + 11, formWidth, 18, Component.literal(field.label()));
            input.setMaxLength(12); input.setValue(values.get(i)); inputs.add(input);
            if (!field.automaticZero()) {
                addRenderableWidget(input);
                modeButtons.add(null); manualButtons.add(null); decreaseButtons.add(null); increaseButtons.add(null);
            } else {
                input.visible = false;
                int segmentWidth = (formWidth - 100) / 2;
                Button mode = addRenderableWidget(ControllerButton.hmi(Component.literal("最寄り"), b -> input.setValue("0"))
                    .bounds(left, row + 11, segmentWidth, 18).build());
                Button manual = addRenderableWidget(ControllerButton.hmi(Component.literal("手動"), b -> input.setValue("1"))
                    .bounds(left + segmentWidth, row + 11, segmentWidth, 18).build());
                Button minus = addRenderableWidget(ControllerButton.hmi(Component.literal("-"), b -> {
                    int current = Integer.parseInt(input.getValue());
                    if (current > 1) input.setValue(Integer.toString(current - 1));
                }).bounds(left + formWidth - 94, row + 11, 22, 18).build());
                Button plus = addRenderableWidget(ControllerButton.hmi(Component.literal("+"), b -> {
                    int current = Integer.parseInt(input.getValue());
                    if (current > 0 && current < field.maximum()) input.setValue(Integer.toString(current + 1));
                }).bounds(left + formWidth - 22, row + 11, 22, 18).build());
                modeButtons.add(mode); manualButtons.add(manual); decreaseButtons.add(minus); increaseButtons.add(plus);
            }
        }
        int bottom = top + 26 + fields.size() * 34 + 10;
        confirm = addRenderableWidget(ControllerButton.hmi(Component.literal("下書きへ反映"), b -> {
            List<Integer> parsed = parsed();
            if (parsed != null) { apply.accept(parsed); onClose(); }
        }).bounds(left, bottom, Math.min(120, formWidth - 66), 20).build());
        addRenderableWidget(ControllerButton.hmi(Component.literal("取消"), b -> onClose())
            .bounds(left + formWidth - 60, bottom, 60, 20).build());
    }
    private List<Integer> parsed() {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            var value = ControllerNumericPolicy.parse(inputs.get(i).getValue(), fields.get(i).minimum(), fields.get(i).maximum());
            if (value.isEmpty()) return null;
            result.add(value.getAsInt());
        }
        return result;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.fill(0, 0, width, height, HmiArt.BACKGROUND);
        g.text(font, HmiArt.fitText(title.getString(), formWidth, font::width), left, top, HmiArt.TEXT);
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            int row = top + 26 + i * 34;
            String label = field.label() + (field.automaticZero() ? "" : " (" + field.minimum() + " ～ " + field.maximum() + ")");
            g.text(font, HmiArt.fitText(label, formWidth, font::width), left, row, HmiArt.TEXT);
            if (field.automaticZero()) {
                int current = ControllerNumericPolicy.parse(inputs.get(i).getValue(), field.minimum(), field.maximum()).orElse(0);
                modeButtons.get(i).active = current != 0;
                manualButtons.get(i).active = current == 0;
                int selectedX = left + (current == 0 ? 0 : (formWidth - 100) / 2);
                g.horizontalLine(selectedX, selectedX + (formWidth - 100) / 2 - 1, row + 31, HmiArt.CYAN);
                decreaseButtons.get(i).active = current > 1;
                increaseButtons.get(i).active = current > 0 && current < field.maximum();
                g.centeredText(font, current == 0 ? "-" : Integer.toString(current),
                    left + formWidth - 47, row + 16, HmiArt.TEXT);
            }
        }
        confirm.active = parsed() != null;
        if (!confirm.active) g.text(font, "範囲内の整数が必要です", left, top + 26 + fields.size() * 34, HmiArt.AMBER);
        super.extractRenderState(g, x, y, delta);
    }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
