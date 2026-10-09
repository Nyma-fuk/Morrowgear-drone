package jp.morrowgear.drone.client;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source wiring checks complement pure policies; they do not simulate Minecraft input. */
final class ControllerInputContractTest {
    private String dashboard() throws Exception {
        return Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/TacticalDashboard.java")).replace("\r\n", "\n");
    }
    @Test void utilityPagesRenderAndAllowTheCommonFooter() throws Exception {
        String source = dashboard();
        assertTrue(source.contains("drawUtilityPage(g, l, drones);\n\t\t\tdrawBottomPanel(g, l, drones);"));
        assertTrue(source.contains("mouseY < l.bottomTop && handlePageClick("));
        assertTrue(source.contains("i == 4 && availability.hasSelection()) execute(3)"));
    }
    @Test void textAndSecondaryMouseInputReachTheDashboard() throws Exception {
        String source = dashboard();
        assertTrue(source.contains("search.isFocused() && search.charTyped(event)"));
        assertTrue(source.contains("button.button() >= 0 && button.button() <= 2"));
        assertTrue(source.indexOf("if (event.button() != 0)") < source.indexOf("mouseY < l.bottomTop && handlePageClick("));
    }
    @Test void cargoEndpointCommandsHaveAnExplicitVisibleEditor() throws Exception {
        String source = dashboard();
        assertTrue(source.contains("drawCargoButton(g, cargoEditorRect(l)"));
        assertTrue(source.contains("new ControllerCargoRouteScreen("));
        assertTrue(source.contains('"' + "cargo_source:" + '"'));
        assertTrue(source.contains("targets.contains(d.getUUID())"));
        assertTrue(source.contains("current.size() != targets.size()"));
        assertFalse(source.contains("cargoSourceRect(l, visible).contains("));
        assertFalse(source.contains("cargoTargetRect(l, visible).contains("));
    }
    @Test void screenForwardsSecondaryDragsAndCancelsDraftBeforeClosing() throws Exception {
        String screen = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/TacticalScreen.java"));
        assertTrue(screen.contains("dashboard.isPanning()"));
        assertTrue(screen.contains("return dashboard.mouseDragged(event, dx, dy)"));
        assertTrue(screen.contains("return dashboard.mouseReleased(event)"));
        assertTrue(screen.contains("event.isEscape() && dashboard != null && dashboard.keyPressed(event)"));
    }
    @Test void workClickOnlyPreviewsUntilExplicitConfirmation() throws Exception {
        String source = dashboard();
        assertTrue(source.contains("pendingTaskPoint = worldPoint(l.map, mouseX, mouseY)"));
        assertTrue(source.contains("waypointApplyRect(l.map).contains(mouseX, mouseY)"));
        assertTrue(source.contains("drawPendingTask(g, map)"));
        assertTrue(source.contains("地形変更あり"));
    }
    @Test void draftChecksUseIdentityAndActualMissionChanges() throws Exception {
        String source = dashboard();
        assertTrue(source.contains("draftTargets.equals(selected.identities())"));
        assertTrue(source.contains("draftMissions.equals(currentDraftMissions())"));
        assertTrue(source.contains("selected.unavailableCount() > 0"));
        assertTrue(source.contains("高度変更不可: 現行サーバーは水平経路のみ"));
        assertFalse(source.contains("を指示しました"));
    }
    @Test void formationCountsAreBuiltAfterEligibilityFiltering() throws Exception {
        String source = dashboard();
        int start = source.indexOf("private List<List<DroneEntity>> selectedMissionFormations()");
        String method = source.substring(start, source.indexOf("private boolean locallyEligible", start));
        assertTrue(method.indexOf("filter(this::locallyEligible)") < method.indexOf("partitionByWing("));
        assertTrue(method.contains("eligible, DroneEntity::groupId"));
    }
    @Test void importantActionsAreNotOnlyUnmarkedRightHandText() throws Exception {
        String source = dashboard();
        assertTrue(source.contains("HmiArt.icon(g, \"check\", control.x"));
        assertTrue(source.contains("rangeStep(l, false).contains(x, y)"));
        assertTrue(source.contains("drawIconButton(g, control, \"clock\", \"報告履歴\", true)"));
        assertTrue(source.contains("fitText(rosterStatusLabel(drone), row.w - 52)"));
        String history = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/OperationHistoryScreen.java"));
        assertFalse(history.contains("Button.builder("));
        assertTrue(history.contains("ControllerDisplayText.dimensionName(event.dimension())"));
        String combat = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/VisorCombatHistoryScreen.java"));
        assertFalse(combat.contains("Button.builder("));
        assertTrue(combat.contains("ControllerButton.hmi("));
    }
    @Test void visorCategoryOpensTheDedicatedSettingsScreenAndKeepsItsParent() throws Exception {
        String source = dashboard();
        assertTrue(source.contains("page == 5 && visorSettingsButton(l).contains(x, y)"));
        assertTrue(source.contains("client.setScreenAndShow(new VisorSettingsScreen(client.gui.screen()))"));
        assertFalse(source.contains("visorSettingsButton(l).contains(x, y)) { page = 7"));
        String settings = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/VisorSettingsScreen.java"));
        assertTrue(settings.contains("public VisorSettingsScreen(Screen parent)"));
        assertTrue(settings.contains("minecraft.setScreenAndShow(parent)"));
        assertTrue(settings.contains("savePresentation(draft)"));
        assertFalse(settings.contains("ClientPlayNetworking"));
    }
}
