package jp.morrowgear.drone.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Native keyboard/narration behavior with the approved shared HMI palette. */
final class ControllerButton extends Button {
    private ControllerButton(int x, int y, int width, int height, Component label, OnPress press) {
        super(x, y, width, height, label, press, DEFAULT_NARRATION);
    }
    static Factory hmi(Component label, OnPress press) { return new Factory(label, press); }
    @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        boolean hover = isHoveredOrFocused();
        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), active && hover ? HmiArt.RAISED : HmiArt.PANEL);
        g.outline(getX(), getY(), getWidth(), getHeight(), active && hover ? HmiArt.CYAN : HmiArt.LINE);
        var font = Minecraft.getInstance().font;
        String text = HmiArt.fitText(getMessage().getString(), getWidth() - 10, font::width);
        g.centeredText(font, text, getX() + getWidth() / 2, getY() + (getHeight() - 9) / 2, active ? HmiArt.TEXT : HmiArt.MUTED);
        if (isMouseOver(mouseX, mouseY) && !text.equals(getMessage().getString()))
            g.setTooltipForNextFrame(getMessage(), mouseX, mouseY);
    }
    static final class Factory {
        private final Component label;
        private final OnPress press;
        private int x, y, width = 100, height = 20;
        private Factory(Component label, OnPress press) { this.label = label; this.press = press; }
        Factory bounds(int x, int y, int width, int height) {
            this.x = x; this.y = y; this.width = width; this.height = height; return this;
        }
        ControllerButton build() { return new ControllerButton(x, y, width, height, label, press); }
    }
}
