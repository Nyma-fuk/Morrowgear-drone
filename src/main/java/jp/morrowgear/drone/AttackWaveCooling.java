package jp.morrowgear.drone;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Group-level laser cooling decision that never compacts a formation. */
public final class AttackWaveCooling {
    public record Decision(AttackWavePhase phase, boolean groupFireEnabled, Set<Integer> vacantSlots) {
        public Decision { vacantSlots = Set.copyOf(vacantSlots); }
    }

    private AttackWaveCooling() {}

    public static Decision decide(AttackWavePlan plan, boolean sharedPlannedStop,
                                  Set<UUID> presentMembers, Set<UUID> emergencyStoppedMembers) {
        if (plan.activeWeapon() != CombatWeapon.LASER)
            throw new IllegalArgumentException("Cooling decisions require a laser wave");
        if (plan.phase() != AttackWavePhase.WAVE_EXECUTE
            && plan.phase() != AttackWavePhase.LASER_COOL_ORBIT)
            throw new IllegalArgumentException("Laser wave is not firing or cooling");
        Objects.requireNonNull(presentMembers, "presentMembers");
        Objects.requireNonNull(emergencyStoppedMembers, "emergencyStoppedMembers");
        if (presentMembers.size() > AttackWavePlan.MAX_MEMBERS
            || emergencyStoppedMembers.size() > AttackWavePlan.MAX_MEMBERS)
            throw new IllegalArgumentException("Laser member input exceeds its bounded capacity");
        Set<UUID> present = Set.copyOf(presentMembers);
        Set<UUID> emergency = Set.copyOf(emergencyStoppedMembers);
        if (!plan.memberIds().containsAll(present) || !plan.memberIds().containsAll(emergency))
            throw new IllegalArgumentException("Unknown laser member");
        var ableToFire = new HashSet<>(present);
        ableToFire.removeAll(emergency);
        AttackWavePhase phase = plan.phase() == AttackWavePhase.LASER_COOL_ORBIT || sharedPlannedStop
            ? AttackWavePhase.LASER_COOL_ORBIT : AttackWavePhase.WAVE_EXECUTE;
        return new Decision(phase, phase == AttackWavePhase.WAVE_EXECUTE && !ableToFire.isEmpty(),
            plan.vacantLaserSlots(ableToFire));
    }

    public static AttackWavePlan leaveCooling(AttackWavePlan plan, boolean continueSameFormation,
                                               long phaseStartedAt, long expiresAt) {
        if (plan.phase() != AttackWavePhase.LASER_COOL_ORBIT)
            throw new IllegalArgumentException("Laser wave is not cooling");
        return AttackWavePolicy.advance(plan, continueSameFormation
            ? AttackWavePhase.WEAPON_COMMIT : AttackWavePhase.WAVE_ASSESS, phaseStartedAt, expiresAt);
    }
}
