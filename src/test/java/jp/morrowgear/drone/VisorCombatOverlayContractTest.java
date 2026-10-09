package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VisorCombatOverlayContractTest {
    @Test void presentationScalesProjectionWithPanelsAndKeepsCriticalInformation() throws Exception {
        String source = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/VisorHudOverlay.java"));
        assertTrue(source.contains("getWidth() / hudScale"));
        assertTrue(source.contains("getHeight() / hudScale"));
        assertTrue(source.contains("pixelScale = hudScale /"));
        assertTrue(source.contains("pinned || focused || actionable"));
        assertTrue(source.contains("!protectedContact && ordinaryDrawn >= ordinaryBudget"));
        assertTrue(source.contains("protectedContact || ordinaryLabeled < ordinaryLabels"));
        assertTrue(source.contains("config.panelAlpha(PANEL)"));
        assertTrue(source.contains("drawPowerLostBeacons(g, client, width, height)"));
        assertTrue(source.contains("drawActionRail(g, client, width, height, plan)"));
    }

    @Test void integrationRetainsProjectionIdentityAndBoundedObservation() throws Exception {
        String source = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/VisorHudOverlay.java"));
        for (String required : new String[]{"private void updateCombatLedger(Minecraft client)",
            "private void drawAdaptiveCombat(", "observedTargets.size() >= VisorCombatLedger.CAPACITY",
            "target.getHealth() <= 0", "client.level.getEntity(target.getId()) == target",
            "pinnedEntityId = match == null ? -1 : match.getId()", "if (pinnedUuid != null) return isPinned(drone)",
            "combatLedger.recent(client.level.getGameTime(), 3)", "drone.getPosition(partialTick)",
            "client.gameRenderer.projectPointToScreen(anchor)", "claimLabel(", "combatHistory()",
            "combatLedger.page(selectedFrontTarget, 3)", "Math.min(3, VisorHudPolicy.engagementDisplayLimit", "worldLabels >= 8",
            "new VisorCombatHistoryScreen(client.gui.screen(), this::combatHistory)"})
            assertTrue(source.contains(required), required);
    }
}
