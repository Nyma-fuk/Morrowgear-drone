package jp.morrowgear.drone;

import java.util.HashSet;
import java.util.Set;

/** Per-cycle observations used only by the destructive carrier acceptance verifier. */
final class CarrierAcceptanceCombatEvidence {
    private int phase;
    private boolean invalidOrder;
    private int beamSamples;
    private final Set<Integer> beamTargets = new HashSet<>();
    private final Set<Integer> hitTargets = new HashSet<>();

    void observePhase(String name) {
        if (name.contains("SCAN")) phase = Math.max(phase, 1);
        else if (name.contains("CHARGE")) {
            if (phase < 1) invalidOrder = true;
            phase = Math.max(phase, 2);
        }
        else if (name.contains("FIRE")) {
            if (phase < 2) invalidOrder = true;
            phase = Math.max(phase, 3);
        } else if (name.contains("COOL")) {
            if (phase < 3) invalidOrder = true;
            phase = Math.max(phase, 4);
        }
    }

    void observeBeamTargets(Iterable<Integer> targets) {
        if (phase != 3) return;
        boolean observed = false;
        for (int target : targets) {
            beamTargets.add(target);
            observed = true;
        }
        if (observed) beamSamples++;
    }

    void observeHitTargets(Iterable<Integer> targets) {
        if (phase != 3) return;
        for (int target : targets) hitTargets.add(target);
    }

    boolean complete(int... requiredTargets) {
        if (invalidOrder || phase < 4 || beamSamples == 0) return false;
        for (int target : requiredTargets)
            if (!beamTargets.contains(target) || !hitTargets.contains(target)) return false;
        return true;
    }
}
