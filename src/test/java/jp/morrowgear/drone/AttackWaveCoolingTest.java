package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AttackWaveCoolingTest {
    private static final UUID A = new UUID(20, 1);
    private static final UUID B = new UUID(20, 2);
    private static final UUID C = new UUID(20, 3);

    @Test void sharedStopMovesTheWholeGroupToCoolingWithoutCompactingSlots() {
        AttackWavePlan plan = laser(AttackWavePhase.WAVE_EXECUTE);
        var decision = AttackWaveCooling.decide(plan, true, Set.of(A, B, C), Set.of());
        assertEquals(AttackWavePhase.LASER_COOL_ORBIT, decision.phase());
        assertFalse(decision.groupFireEnabled());
        assertEquals(Set.of(1, 3, 4, 6, 7), decision.vacantSlots());
        assertEquals(0, plan.slotFor(A).orElseThrow());
        assertEquals(2, plan.slotFor(B).orElseThrow());
        assertEquals(5, plan.slotFor(C).orElseThrow());
    }

    @Test void missingOrEmergencyAircraftLeaveVacanciesButDoNotReindexSurvivors() {
        AttackWavePlan plan = laser(AttackWavePhase.WAVE_EXECUTE);
        var decision = AttackWaveCooling.decide(plan, false, Set.of(A, B), Set.of(B));
        assertEquals(AttackWavePhase.WAVE_EXECUTE, decision.phase());
        assertTrue(decision.groupFireEnabled());
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7), decision.vacantSlots());
        assertEquals(0, plan.slotFor(A).orElseThrow());
        assertEquals(2, plan.slotFor(B).orElseThrow());
    }

    @Test void coolingCannotIndividuallyResumeFireOrSwitchWeapon() {
        AttackWavePlan cooling = laser(AttackWavePhase.LASER_COOL_ORBIT);
        var decision = AttackWaveCooling.decide(cooling, false, Set.of(A, B, C), Set.of());
        assertEquals(AttackWavePhase.LASER_COOL_ORBIT, decision.phase());
        assertFalse(decision.groupFireEnabled());
        AttackWavePlan assess = AttackWaveCooling.leaveCooling(cooling, false, 200, 220);
        assertEquals(AttackWavePhase.WAVE_ASSESS, assess.phase());
        assertEquals(CombatWeapon.LASER, assess.activeWeapon());
    }

    @Test void nonLaserAndUnknownMembersAreRejected() {
        AttackWavePlan gun = new AttackWavePlan(new UUID(21, 1), 0, new UUID(21, 2), new UUID(21, 3), 0,
            List.of(A), Map.of(), 0, CombatWeapon.AUTOCANNON, AttackWavePhase.WAVE_EXECUTE, 0, 20);
        assertThrows(IllegalArgumentException.class,
            () -> AttackWaveCooling.decide(gun, true, Set.of(A), Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> AttackWaveCooling.decide(laser(AttackWavePhase.WAVE_EXECUTE), false,
                Set.of(A, new UUID(99, 1)), Set.of()));
    }

    private static AttackWavePlan laser(AttackWavePhase phase) {
        return new AttackWavePlan(new UUID(20, 10), 4, new UUID(20, 11), new UUID(20, 12), 9,
            List.of(A, B, C), Map.of(A, 0, B, 2, C, 5), 8,
            CombatWeapon.LASER, phase, 100, 180);
    }
}
