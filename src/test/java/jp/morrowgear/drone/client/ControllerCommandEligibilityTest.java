package jp.morrowgear.drone.client;

import jp.morrowgear.drone.SalvageState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ControllerCommandEligibilityTest {
    @Test void everyCommittedPhaseWithholdsFlightRequestsEvenWhenTargetIsUnreceived() {
        for (var state : new SalvageState[] {SalvageState.HOOK, SalvageState.HOIST, SalvageState.RETURN,
                SalvageState.DELIVER, SalvageState.SERVICE}) assertFalse(ControllerCommandEligibility.maySubmit(state));
    }
    @Test void idleAndInterceptAreOnlySubmissionCandidatesNotAcknowledgements() {
        assertTrue(ControllerCommandEligibility.maySubmit(SalvageState.IDLE));
        assertTrue(ControllerCommandEligibility.maySubmit(SalvageState.INTERCEPT));
        assertFalse(ControllerCommandEligibility.maySubmit(null));
    }
}
