package jp.morrowgear.drone.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

final class OperationSubtitleOverlay implements HudElement {
    @Override public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.gui.screen() != null) return;
        OperationCommunicationsClient.subtitle().filter(event -> event.kind().important()).ifPresent(event -> {
            int width = client.getWindow().getGuiScaledWidth();
            int height = client.getWindow().getGuiScaledHeight();
            int maxWidth = Math.max(80, Math.min(380, width - 32));
            var lines = client.font.split(Component.translatable(event.messageKey(), event.source(), event.count()), maxWidth - 16);
            int count = Math.min(2, lines.size());
            int x = (width - maxWidth) / 2, y = Math.max(40, height - 112);
            g.fill(x, y, x + maxWidth, y + count * 10 + 10, 0xD014181B);
            g.fill(x, y, x + 2, y + count * 10 + 10, HmiArt.AMBER);
            for (int i = 0; i < count; i++) g.text(client.font, lines.get(i), x + 8, y + 5 + i * 10, HmiArt.TEXT);
        });
    }
}
