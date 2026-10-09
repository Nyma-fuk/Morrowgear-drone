package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClearanceResumeSnapshotTest {
    @Test void invalidTaskUsesExistingRouteThenDockOwnerOrSafeOrbitPolicy() throws Exception {
        var route = DroneTaskStack.serviceFallback(DroneMode.FOLLOW, SalvageState.IDLE, null, "live-route", true);
        assertEquals(DroneTaskStack.Kind.ROUTE, route.kind());
        assertEquals(DroneMode.WAYPOINT, route.mode());
        assertEquals(SolarServiceResumePolicy.Action.REQUEST_DOCK, SolarServiceResumePolicy.choose(false, false, true, true));
        assertEquals(SolarServiceResumePolicy.Action.FOLLOW_OWNER, SolarServiceResumePolicy.choose(false, false, false, true));
        assertEquals(SolarServiceResumePolicy.Action.SAFE_ORBIT, SolarServiceResumePolicy.choose(false, false, false, false));
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
        String finish = source.substring(source.indexOf("private void finishClearanceAssist("),
            source.indexOf("private void discardClearanceAssist("));
        assertTrue(finish.contains("resumeAfterInvalidClearanceTask(level)"));
        assertTrue(finish.contains("hasWaypoint() && !hasTrackingTarget()"));
        assertTrue(finish.contains("entityData.set(MODE, resumed.mode().id())"));
        assertFalse(finish.contains("resumed == null ? DroneMode.STANDBY"));
        assertTrue(finish.contains("applySafeMissionFallback(level, \"RECOVERY CLEAR\", false)"));
        assertTrue(source.contains("unitId() + \"-SAFE-ORBIT\""));
    }
    @Test void roundTripPreservesAssignmentIncludingIdleAndDock() {
        for (DroneMode mode : DroneMode.values()) {
            for (DroneTaskStack.Kind kind : DroneTaskStack.Kind.values()) {
                for (UUID target : new UUID[]{null, UUID.randomUUID()}) {
                    var original = new DroneTaskStack.Task(kind, mode, SalvageState.IDLE, target, "formation-42");
                    var encoded = ClearanceResumeSnapshot.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
                    var loaded = ClearanceResumeSnapshot.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
                    assertTrue(original.sameAssignment(loaded));
                }
            }
        }
    }

    @Test void malformedSnapshotIsNotSilentlyReinterpreted() {
        var task = new DroneTaskStack.Task(DroneTaskStack.Kind.FOLLOW, DroneMode.ORBIT, SalvageState.IDLE, null, "wing");
        var encoded = ClearanceResumeSnapshot.CODEC.encodeStart(JsonOps.INSTANCE, task).getOrThrow().getAsJsonObject();
        encoded.addProperty("Mode", "INVALID");
        assertTrue(ClearanceResumeSnapshot.CODEC.parse(JsonOps.INSTANCE, encoded).error().isPresent());
    }

    @Test void entityKeepsClearanceSeparateFromServiceStackAndCancelsBeforeClearingFields() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java")).replace("\r\n", "\n");
        assertTrue(source.contains("best.clearanceResumeTask = best.currentTaskSnapshot();"));
        assertFalse(source.contains("best.taskStack.suspend(best.currentTaskSnapshot());"));
        assertTrue(source.contains("public void clearFieldOperation() {\n\t\tdiscardClearanceAssist();"));
        String finish = source.substring(source.indexOf("private void finishClearanceAssist("),
            source.indexOf("private boolean salvageDeliveryClear("));
        assertFalse(finish.contains("resumeServiceTask"));
        assertTrue(finish.indexOf("discardClearanceAssist();") < finish.indexOf("resolveTaskForResume(level, saved)"));
        assertTrue(source.contains("output.store(\"ClearanceResumeTask\", ClearanceResumeSnapshot.CODEC"));
        assertTrue(source.contains("input.read(\"ClearanceResumeTask\", ClearanceResumeSnapshot.CODEC)"));
        String service = source.substring(source.indexOf("private void beginServiceReturn("),
            source.indexOf("private DroneTaskStack.Task currentTaskSnapshot()"));
        assertTrue(service.indexOf("finishClearanceAssist(server)") < service.indexOf("taskStack.suspend("));
        String solar = source.substring(source.indexOf("if (stationId == null) return;"));
        assertTrue(solar.indexOf("finishClearanceAssist(level)") < solar.indexOf("solarResumeTask = currentTaskSnapshot()"));
    }
}
