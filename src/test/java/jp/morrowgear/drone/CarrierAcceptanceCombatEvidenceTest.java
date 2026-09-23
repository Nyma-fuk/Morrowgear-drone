package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class CarrierAcceptanceCombatEvidenceTest {
    @Test void eachCycleRequiresItsOwnOrderedPhasesAndExpectedBeamEndpoints() {
        var first = completeCycle(0);
        assertTrue(first.complete(0));
        assertFalse(first.complete(1));

        var second = new CarrierAcceptanceCombatEvidence();
        assertFalse(second.complete(0));
        second.observePhase("SCAN");
        second.observePhase("CHARGE");
        second.observePhase("FIRE");
        second.observeBeamTargets(List.of(1, 2));
        second.observeHitTargets(List.of(1, 2));
        second.observePhase("COOLDOWN");
        assertTrue(second.complete(1, 2));
    }

    @Test void staleBeamSamplesAndOutOfOrderPhasesCannotPass() {
        var noEndpoint = new CarrierAcceptanceCombatEvidence();
        noEndpoint.observePhase("SCAN");
        noEndpoint.observePhase("CHARGE");
        noEndpoint.observePhase("FIRE");
        noEndpoint.observeBeamTargets(List.of());
        noEndpoint.observePhase("COOLDOWN");
        assertFalse(noEndpoint.complete(0));

        var outOfOrder = new CarrierAcceptanceCombatEvidence();
        outOfOrder.observePhase("FIRE");
        outOfOrder.observeBeamTargets(List.of(0));
        outOfOrder.observeHitTargets(List.of(0));
        outOfOrder.observePhase("COOLDOWN");
        assertFalse(outOfOrder.complete(0));

        var scanEndpoint = new CarrierAcceptanceCombatEvidence();
        scanEndpoint.observePhase("SCAN");
        scanEndpoint.observeBeamTargets(List.of(0));
        scanEndpoint.observeHitTargets(List.of(0));
        scanEndpoint.observePhase("CHARGE");
        scanEndpoint.observePhase("FIRE");
        scanEndpoint.observePhase("COOLDOWN");
        assertFalse(scanEndpoint.complete(0), "SCAN rays must not prove a firing hit");
    }

    private static CarrierAcceptanceCombatEvidence completeCycle(int target) {
        var evidence = new CarrierAcceptanceCombatEvidence();
        evidence.observePhase("SCAN");
        evidence.observePhase("CHARGE");
        evidence.observePhase("FIRE");
        evidence.observeBeamTargets(List.of(target));
        evidence.observeHitTargets(List.of(target));
        evidence.observePhase("COOLDOWN");
        return evidence;
    }
}
