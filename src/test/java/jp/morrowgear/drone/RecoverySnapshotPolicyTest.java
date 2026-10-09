package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoverySnapshotPolicyTest {
    @Test void requestRateIsBoundedIncludingClockRollback() {
        assertTrue(RecoverySnapshotPolicy.mayRequest(100, null));
        for (long tick = 90; tick < 120; tick++) assertFalse(RecoverySnapshotPolicy.mayRequest(tick, 100L));
        assertTrue(RecoverySnapshotPolicy.mayRequest(120, 100L));
    }
    @Test void responsesRequireSameOwnerAndCurrentRequest() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        assertTrue(RecoverySnapshotPolicy.accepts(owner, owner, 7, 7));
        assertFalse(RecoverySnapshotPolicy.accepts(owner, other, 7, 7));
        assertFalse(RecoverySnapshotPolicy.accepts(owner, owner, 8, 7));
        assertFalse(RecoverySnapshotPolicy.owned(null, owner));
        assertFalse(RecoverySnapshotPolicy.owned(owner, null));
    }
    @Test void serviceDestinationIsTheLoadDockNotRescuersNextReservation() {
        assertEquals("target", RecoverySnapshotPolicy.destination(SalvageState.SERVICE, "rescuer-new", "target"));
        assertEquals("", RecoverySnapshotPolicy.destination(SalvageState.SERVICE, "rescuer-new", ""));
        assertEquals("rescuer", RecoverySnapshotPolicy.destination(SalvageState.RETURN, "rescuer", "old-target"));
        assertEquals("rescuer", RecoverySnapshotPolicy.destination(SalvageState.DELIVER, "rescuer", "old-target"));
        for (SalvageState stage : new SalvageState[]{SalvageState.IDLE, SalvageState.INTERCEPT, SalvageState.HOOK, SalvageState.HOIST})
            assertEquals("", RecoverySnapshotPolicy.destination(stage, "home", "old-target"));
    }
    @Test void unknownExpiredAndOtherDimensionAreDistinct() {
        assertEquals("未受信", RecoverySnapshotPolicy.reception(false, false, "a", "a"));
        assertTrue(RecoverySnapshotPolicy.reception(true, false, "a", "a").contains("期限切れ"));
        assertTrue(RecoverySnapshotPolicy.reception(true, true, "a", "b").contains("別次元"));
        assertEquals("受信済", RecoverySnapshotPolicy.reception(true, true, "a", "a"));
        assertTrue(RecoverySnapshotPolicy.fresh(160, 100));
        assertFalse(RecoverySnapshotPolicy.fresh(161, 100));
        assertFalse(RecoverySnapshotPolicy.fresh(99, 100));
    }
    @Test void missingDestinationIsNotTheSameAsUnassigned() {
        assertTrue(RecoverySnapshotPolicy.destinationLabel(false, java.util.Optional.empty()).contains("不明"));
        assertTrue(RecoverySnapshotPolicy.destinationLabel(true, java.util.Optional.empty()).contains("未割当"));
        assertEquals("1, 2, 3", RecoverySnapshotPolicy.destinationLabel(true, java.util.Optional.of("1, 2, 3")));
    }
}
