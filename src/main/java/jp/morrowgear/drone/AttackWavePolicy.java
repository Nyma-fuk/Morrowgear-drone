package jp.morrowgear.drone;

import java.util.Objects;

/** Revision and phase gate for coordinator snapshots. */
public final class AttackWavePolicy {
    public enum Acceptance {
        ACCEPTED,
        IDEMPOTENT,
        STALE_REVISION,
        REVISION_GAP,
        WRONG_PLAN,
        IMMUTABLE_CONFLICT,
        INVALID_TRANSITION,
        COMPLETED
    }

    public record ApplyResult(Acceptance acceptance, AttackWavePlan plan) {
        public boolean changed() { return acceptance == Acceptance.ACCEPTED; }
    }

    private AttackWavePolicy() {}

    public static boolean validTransition(CombatWeapon weapon, AttackWavePhase from, AttackWavePhase to) {
        Objects.requireNonNull(weapon, "weapon");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return switch (from) {
            case WAVE_PLAN -> to == AttackWavePhase.WAVE_INGRESS;
            case WAVE_INGRESS -> to == AttackWavePhase.WEAPON_COMMIT;
            case WEAPON_COMMIT -> to == AttackWavePhase.WAVE_EXECUTE;
            case WAVE_EXECUTE -> to == AttackWavePhase.WAVE_RECOVERY
                || weapon == CombatWeapon.LASER && (to == AttackWavePhase.LASER_COOL_ORBIT
                    || to == AttackWavePhase.WAVE_ASSESS);
            case LASER_COOL_ORBIT -> weapon == CombatWeapon.LASER
                && (to == AttackWavePhase.WEAPON_COMMIT || to == AttackWavePhase.WAVE_ASSESS);
            case WAVE_RECOVERY -> to == AttackWavePhase.WAVE_ASSESS;
            case WAVE_ASSESS -> to == AttackWavePhase.COMPLETE;
            case COMPLETE -> false;
        };
    }

    public static AttackWavePlan advance(AttackWavePlan plan, AttackWavePhase phase,
                                         long phaseStartedAt, long expiresAt) {
        Objects.requireNonNull(plan, "plan");
        if (!validTransition(plan.activeWeapon(), plan.phase(), phase))
            throw new IllegalArgumentException("Invalid attack wave phase transition");
        return plan.atRevision(plan.revision() + 1, phase, phaseStartedAt, expiresAt);
    }

    public static ApplyResult apply(AttackWavePlan current, AttackWavePlan incoming) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(incoming, "incoming");
        if (!current.planId().equals(incoming.planId())) return new ApplyResult(Acceptance.WRONG_PLAN, current);
        if (incoming.revision() < current.revision()) return new ApplyResult(Acceptance.STALE_REVISION, current);
        if (incoming.revision() == current.revision()) return new ApplyResult(
            incoming.equals(current) ? Acceptance.IDEMPOTENT : Acceptance.IMMUTABLE_CONFLICT, current);
        if (current.phase() == AttackWavePhase.COMPLETE) return new ApplyResult(Acceptance.COMPLETED, current);
        if (incoming.revision() != current.revision() + 1) return new ApplyResult(Acceptance.REVISION_GAP, current);
        if (!current.sameCommitment(incoming)) return new ApplyResult(Acceptance.IMMUTABLE_CONFLICT, current);
        if (!validTransition(current.activeWeapon(), current.phase(), incoming.phase()))
            return new ApplyResult(Acceptance.INVALID_TRANSITION, current);
        return new ApplyResult(Acceptance.ACCEPTED, incoming);
    }
}
