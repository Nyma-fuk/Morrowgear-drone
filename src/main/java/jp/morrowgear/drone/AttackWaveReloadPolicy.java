package jp.morrowgear.drone;

import java.util.Objects;

/** Save/reload boundary: never resumes a committed attack output phase. */
public final class AttackWaveReloadPolicy {
    public static final long ASSESS_TICKS = 40;

    public record Decision(AttackWavePlan plan, boolean attackOutputAllowed,
                           boolean resourceConsumptionAllowed) {}

    private AttackWaveReloadPolicy() {}

    public static Decision restore(AttackWavePlan saved, long now) {
        Objects.requireNonNull(saved, "saved");
        if (now < 0 || now > Long.MAX_VALUE - ASSESS_TICKS) throw new IllegalArgumentException("Invalid reload tick");
        if (saved.phase() == AttackWavePhase.COMPLETE || saved.phase() == AttackWavePhase.WAVE_ASSESS)
            return new Decision(saved, false, false);
        AttackWavePlan assessment = saved.atRevision(saved.revision() + 1, AttackWavePhase.WAVE_ASSESS,
            now, now + ASSESS_TICKS);
        return new Decision(assessment, false, false);
    }
}
