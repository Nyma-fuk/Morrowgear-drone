package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AttackWavePolicyTest {
    private static final UUID PLAN = new UUID(10, 1);
    private static final UUID TARGET = new UUID(10, 2);
    private static final UUID WAVE = new UUID(10, 3);
    private static final UUID MEMBER = new UUID(10, 4);

    @Test void commonPhasesAdvanceOnlyAcrossSafeBoundaries() {
        AttackWavePlan plan = plan(0, AttackWavePhase.WAVE_PLAN, CombatWeapon.AUTOCANNON);
        for (AttackWavePhase phase : List.of(AttackWavePhase.WAVE_INGRESS, AttackWavePhase.WEAPON_COMMIT,
            AttackWavePhase.WAVE_EXECUTE, AttackWavePhase.WAVE_RECOVERY, AttackWavePhase.WAVE_ASSESS,
            AttackWavePhase.COMPLETE)) {
            plan = AttackWavePolicy.advance(plan, phase, plan.phaseStartedAt() + 1, plan.expiresAt() + 1);
        }
        assertEquals(6, plan.revision());
        assertEquals(AttackWavePhase.COMPLETE, plan.phase());
        AttackWavePlan complete = plan;
        assertThrows(IllegalArgumentException.class,
            () -> AttackWavePolicy.advance(complete, AttackWavePhase.WAVE_PLAN, 200, 220));
    }

    @Test void coolingIsLaserOnlyAndReturnsToTheSameCommitment() {
        AttackWavePlan firing = plan(3, AttackWavePhase.WAVE_EXECUTE, CombatWeapon.LASER);
        AttackWavePlan cooling = AttackWavePolicy.advance(firing, AttackWavePhase.LASER_COOL_ORBIT, 140, 180);
        AttackWavePlan recharge = AttackWaveCooling.leaveCooling(cooling, true, 160, 200);
        assertEquals(AttackWavePhase.WEAPON_COMMIT, recharge.phase());
        assertEquals(firing.targetId(), recharge.targetId());
        assertEquals(firing.waveId(), recharge.waveId());
        assertEquals(firing.formationGeneration(), recharge.formationGeneration());
        assertEquals(firing.reservedLaserSlots(), recharge.reservedLaserSlots());
        assertThrows(IllegalArgumentException.class, () -> AttackWavePolicy.advance(
            plan(3, AttackWavePhase.WAVE_EXECUTE, CombatWeapon.MISSILE),
            AttackWavePhase.LASER_COOL_ORBIT, 140, 180));
    }

    @Test void applyIsIdempotentAndRejectsStaleGappedOrConflictingSnapshots() {
        AttackWavePlan current = plan(3, AttackWavePhase.WAVE_EXECUTE, CombatWeapon.LASER);
        assertEquals(AttackWavePolicy.Acceptance.IDEMPOTENT, AttackWavePolicy.apply(current, current).acceptance());
        assertEquals(AttackWavePolicy.Acceptance.STALE_REVISION,
            AttackWavePolicy.apply(current, plan(2, AttackWavePhase.WEAPON_COMMIT, CombatWeapon.LASER)).acceptance());
        assertEquals(AttackWavePolicy.Acceptance.REVISION_GAP,
            AttackWavePolicy.apply(current, plan(5, AttackWavePhase.LASER_COOL_ORBIT, CombatWeapon.LASER)).acceptance());

        AttackWavePlan next = AttackWavePolicy.advance(current, AttackWavePhase.LASER_COOL_ORBIT, 140, 180);
        assertEquals(AttackWavePolicy.Acceptance.ACCEPTED, AttackWavePolicy.apply(current, next).acceptance());
        AttackWavePlan changedTarget = new AttackWavePlan(PLAN, 4, new UUID(99, 1), WAVE, 6, List.of(MEMBER),
            Map.of(MEMBER, 5), 8, CombatWeapon.LASER, AttackWavePhase.LASER_COOL_ORBIT, 140, 180);
        assertEquals(AttackWavePolicy.Acceptance.IMMUTABLE_CONFLICT,
            AttackWavePolicy.apply(current, changedTarget).acceptance());
        assertSame(current, AttackWavePolicy.apply(current, changedTarget).plan());
    }

    @Test void equalRevisionWithDifferentDataAndDifferentPlanAreNeverReplayed() {
        AttackWavePlan current = plan(3, AttackWavePhase.WAVE_EXECUTE, CombatWeapon.LASER);
        AttackWavePlan altered = new AttackWavePlan(PLAN, 3, TARGET, WAVE, 6, List.of(MEMBER), Map.of(MEMBER, 5),
            8, CombatWeapon.LASER, AttackWavePhase.WAVE_EXECUTE, 121, 160);
        assertEquals(AttackWavePolicy.Acceptance.IMMUTABLE_CONFLICT,
            AttackWavePolicy.apply(current, altered).acceptance());
        AttackWavePlan foreign = new AttackWavePlan(new UUID(88, 1), 4, TARGET, WAVE, 6, List.of(MEMBER),
            Map.of(MEMBER, 5), 8, CombatWeapon.LASER, AttackWavePhase.LASER_COOL_ORBIT, 140, 180);
        assertEquals(AttackWavePolicy.Acceptance.WRONG_PLAN,
            AttackWavePolicy.apply(current, foreign).acceptance());
    }

    private static AttackWavePlan plan(long revision, AttackWavePhase phase, CombatWeapon weapon) {
        boolean laser = weapon == CombatWeapon.LASER;
        return new AttackWavePlan(PLAN, revision, TARGET, WAVE, 6, List.of(MEMBER),
            laser ? Map.of(MEMBER, 5) : Map.of(), laser ? 8 : 0, weapon, phase, 120, 160);
    }
}
