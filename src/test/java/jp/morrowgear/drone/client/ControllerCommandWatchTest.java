package jp.morrowgear.drone.client;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ControllerCommandWatchTest {
    private final UUID unit = UUID.randomUUID();
    private final ControllerCommandWatch.Observation before = new ControllerCommandWatch.Observation("waiting", "old");

    @Test void localSubmitIsOnlyWaitingAndDuplicateIsSuppressed() {
        var watch = new ControllerCommandWatch();
        assertTrue(watch.submit(unit, "patrol:new", before, "new", 100));
        assertFalse(watch.submit(unit, "patrol:new", before, "new", 101));
        assertEquals(new ControllerCommandWatch.Counts(1, 0, 0), watch.observe(Map.of(unit, before), 102));
    }

    @Test void unrelatedMissionChangeCannotConfirmRequestedRoute() {
        var watch = new ControllerCommandWatch();
        watch.submit(unit, "patrol:new", before, "new", 0);
        assertEquals(1, watch.observe(Map.of(unit, new ControllerCommandWatch.Observation("moving", "other")), 1000).waiting());
        assertEquals(1, watch.observe(Map.of(unit, new ControllerCommandWatch.Observation("moving", "new")), 2000).updated());
    }

    @Test void TimeoutAndDisappearanceAreUnknownNotRejectedOrCompleted() {
        var watch = new ControllerCommandWatch();
        watch.submit(unit, "decommission", before, "", 0);
        assertEquals(new ControllerCommandWatch.Counts(0, 0, 1), watch.observe(Map.of(), 5000));
        assertEquals(1, watch.observe(Map.of(unit, new ControllerCommandWatch.Observation("changed", "new")), 6000).unknown());
        assertTrue(watch.submit(unit, "decommission", before, "", 6001));
    }

    @Test void partialReceptionKeepsOtherUnitsWaiting() {
        var watch = new ControllerCommandWatch();
        UUID other = UUID.randomUUID();
        watch.submit(unit, "move", before, "new", 0);
        watch.submit(other, "move", before, "new", 0);
        assertEquals(new ControllerCommandWatch.Counts(1, 1, 0), watch.observe(
            Map.of(unit, new ControllerCommandWatch.Observation("moving", "new")), 100));
    }

    public static void main(String[] args) {
        var tests = new ControllerCommandWatchTest();
        tests.localSubmitIsOnlyWaitingAndDuplicateIsSuppressed();
        tests.unrelatedMissionChangeCannotConfirmRequestedRoute();
        tests.TimeoutAndDisappearanceAreUnknownNotRejectedOrCompleted();
        tests.partialReceptionKeepsOtherUnitsWaiting();
        System.out.println("ControllerCommandWatch: 4 tests passed");
    }
}
