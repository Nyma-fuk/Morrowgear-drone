package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class OperatorCommandAdmissionTest {
    record Unit(String id, boolean available) {}

    @Test void fleetCountsAndSlotsUseOnlyAcceptedUnitsInOriginalOrder() {
        Unit first = new Unit("first", true), load = new Unit("load", false), last = new Unit("last", true);
        var result = OperatorCommandAdmission.select(List.of(first, load, last), Unit::available);
        assertEquals(List.of(first, last), result.accepted());
        assertEquals(List.of(load), result.rejected());
        assertEquals(2, result.accepted().size());
        assertSame(last, result.accepted().get(1));
        assertFalse(result.allAccepted());
    }

    @Test void atomicWingOrDockChangesDoNotApplyPartialSelection() {
        var requested = List.of(new Unit("free", true), new Unit("towing", false));
        var result = OperatorCommandAdmission.select(requested, Unit::available);
        List<Unit> mutated = new ArrayList<>();
        if (result.allAccepted()) mutated.addAll(result.accepted());
        assertTrue(mutated.isEmpty());
        assertTrue(OperatorCommandAdmission.select(List.<Unit>of(), Unit::available).allAccepted());
    }

    @Test void resultIsDetachedAndImmutable() {
        var requested = new ArrayList<>(List.of(new Unit("free", true)));
        var result = OperatorCommandAdmission.select(requested, Unit::available);
        requested.clear();
        assertEquals(1, result.accepted().size());
        assertThrows(UnsupportedOperationException.class, () -> result.accepted().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.rejected().clear());
    }

    @Test void externalPreflightPrecedesFleetPlanningAndAtomicGroupChanges() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/MorrowgearDrone.java"));
        String fleet = source.substring(source.indexOf("private static void dispatchFleetOperation"));
        assertTrue(fleet.indexOf("admission.accepted()") < fleet.indexOf("AutonomousTaskForcePolicy.plan("));
        String wing = source.substring(source.indexOf("private static void moveToWing"));
        assertTrue(wing.indexOf("allowOperatorCommands(player, affected)") < wing.indexOf("drone.assignGroup(targetGroup)"));
        assertTrue(source.contains("if (!allowOperatorCommands(player, displaced)) return;"));
        for (String name : List.of("changeRoleModule", "changeSecurityLoadout", "upgradeCapacity", "storeDrone")) {
            var pattern = java.util.regex.Pattern.compile("(?:public |private )?static void " + name + "\\([^)]*\\) \\{\\s*if \\(!allowOperatorCommand\\(player, drone\\)\\) return;");
            assertTrue(pattern.matcher(source).find(), name);
        }
    }
}
