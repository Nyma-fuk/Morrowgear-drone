package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AttackWaveReloadPolicyTest {
    private static final UUID PLAN = new UUID(30, 1);
    private static final UUID TARGET = new UUID(30, 2);
    private static final UUID WAVE = new UUID(30, 3);
    private static final UUID MEMBER = new UUID(30, 4);

    @Test void committedFireReloadsIntoAssessmentWithoutAttackOrResourceReplay() {
        AttackWavePlan firing = plan(AttackWavePhase.WAVE_EXECUTE, 12);
        var restored = AttackWaveReloadPolicy.restore(firing, 500);
        assertEquals(AttackWavePhase.WAVE_ASSESS, restored.plan().phase());
        assertEquals(13, restored.plan().revision());
        assertFalse(restored.attackOutputAllowed());
        assertFalse(restored.resourceConsumptionAllowed());
        assertEquals(firing.planId(), restored.plan().planId());
        assertEquals(firing.targetId(), restored.plan().targetId());
        assertEquals(firing.waveId(), restored.plan().waveId());
        assertEquals(firing.formationGeneration(), restored.plan().formationGeneration());
        assertEquals(firing.reservedLaserSlots(), restored.plan().reservedLaserSlots());
    }

    @Test void assessmentAndCompletionReloadIdempotently() {
        AttackWavePlan assess = plan(AttackWavePhase.WAVE_ASSESS, 7);
        AttackWavePlan complete = plan(AttackWavePhase.COMPLETE, 8);
        assertSame(assess, AttackWaveReloadPolicy.restore(assess, 500).plan());
        assertSame(complete, AttackWaveReloadPolicy.restore(complete, 500).plan());
        assertSame(assess, AttackWaveReloadPolicy.restore(
            AttackWaveReloadPolicy.restore(assess, 500).plan(), 600).plan());
    }

    @Test void everyPotentiallySideEffectingSavedPhaseFallsBackToAssessment() {
        for (AttackWavePhase phase : List.of(AttackWavePhase.WAVE_PLAN, AttackWavePhase.WAVE_INGRESS,
            AttackWavePhase.WEAPON_COMMIT, AttackWavePhase.WAVE_EXECUTE, AttackWavePhase.LASER_COOL_ORBIT,
            AttackWavePhase.WAVE_RECOVERY)) {
            var decision = AttackWaveReloadPolicy.restore(plan(phase, 1), 1000);
            assertEquals(AttackWavePhase.WAVE_ASSESS, decision.plan().phase(), phase.name());
            assertFalse(decision.attackOutputAllowed(), phase.name());
            assertFalse(decision.resourceConsumptionAllowed(), phase.name());
        }
    }

    @Test void invalidReloadClockCannotOverflowAssessmentExpiry() {
        assertThrows(IllegalArgumentException.class, () -> AttackWaveReloadPolicy.restore(
            plan(AttackWavePhase.WAVE_EXECUTE, 1), -1));
        assertThrows(IllegalArgumentException.class, () -> AttackWaveReloadPolicy.restore(
            plan(AttackWavePhase.WAVE_EXECUTE, 1), Long.MAX_VALUE));
    }

    private static AttackWavePlan plan(AttackWavePhase phase, long revision) {
        return new AttackWavePlan(PLAN, revision, TARGET, WAVE, 3, List.of(MEMBER), Map.of(MEMBER, 6), 8,
            CombatWeapon.LASER, phase, 100, 200);
    }
}
