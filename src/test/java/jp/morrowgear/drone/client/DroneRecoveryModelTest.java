package jp.morrowgear.drone.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import jp.morrowgear.drone.SalvageState;
import org.junit.jupiter.api.Test;

class DroneRecoveryModelTest {
    private DroneRecoveryModel.Row row(String key, String unit, SalvageState state) {
        return new DroneRecoveryModel.Row(key, unit, "overworld", "1, 2, 3", key,
            "carrier", state, "unknown", "received", "unknown", 10);
    }
    @Test void missingObservationDoesNotInferCompletionOrDeath() {
        var model = new DroneRecoveryModel();
        model.observe(List.of(row("uuid", "unit", SalvageState.RETURN)));
        model.observe(List.of());
        assertEquals(SalvageState.RETURN, model.rows().getFirst().stage());
        assertEquals("工程未確認", DroneRecoveryModel.phase(null));
        assertTrue(DroneRecoveryModel.phase(SalvageState.IDLE).contains("完了未確認"));
    }
    @Test void identitiesRemainDistinctEvenWithMatchingLabels() {
        var model = new DroneRecoveryModel();
        model.observe(List.of(row("uuid-a", "unit", null), row("uuid-b", "unit", null), row("beacon:unit", "unit", null)));
        assertEquals(3, model.rows().size());
        model.observe(List.of(row("uuid-a", "renamed", SalvageState.SERVICE)));
        assertEquals(3, model.rows().size());
        assertEquals("renamed", model.rows().stream().filter(r -> r.key().equals("uuid-a")).findFirst().orElseThrow().unit());
    }
    @Test void historyIsBoundedAndRecentObservationsSurvive() {
        var model = new DroneRecoveryModel();
        for (int i = 0; i < 200; i++) model.observe(List.of(row("id-" + i, "unit", null)));
        assertEquals(128, model.rows().size());
        assertTrue(model.rows().stream().noneMatch(r -> r.key().equals("id-0")));
        assertTrue(model.rows().stream().anyMatch(r -> r.key().equals("id-199")));
    }
    @Test void everyStageHasAnExplicitLabelWithoutClaimingMissionResume() {
        for (SalvageState state : SalvageState.values()) {
            assertFalse(DroneRecoveryModel.phase(state).isBlank());
            assertFalse(DroneRecoveryModel.phase(state).contains("復帰済"));
        }
    }
    @Test void detailLinesStayAboveFooterAtCommonGuiScales() {
        for (int height : new int[]{240, 360, 480, 640, 720, 1080}) {
            int count = DroneRecoveryModel.pageCapacity(height - 102, 13);
            assertTrue(64 + (count - 1) * 13 + 9 < height - 28);
        }
    }

    @Test void beaconNotificationsAreNotCountedAsAdditionalRecoveryEntities() {
        var model = new DroneRecoveryModel();
        model.observe(List.of(row("uuid-a", "unit", null), row("beacon:unit", "unit", null)));
        assertEquals(1, model.rows(false).size());
        assertEquals("uuid-a", model.rows(false).getFirst().key());
        assertEquals(1, model.rows(true).size());
        assertEquals("beacon:unit", model.rows(true).getFirst().key());
    }

    @Test void destinationNeverSubstitutesRescuersOwnDock() throws Exception {
        assertTrue(DroneRecoveryModel.unsyncedDestination().contains("実配送先は未同期"));
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/client/java/jp/morrowgear/drone/client/DroneRecoveryScreen.java"));
        assertFalse(source.contains("carrier.hasDock()"));
        assertFalse(source.contains("carrier.dockPos()"));
        assertTrue(source.contains("DroneRecoveryModel.unsyncedDestination()"));
    }
}
