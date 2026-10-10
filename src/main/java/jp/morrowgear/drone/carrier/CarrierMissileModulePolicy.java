package jp.morrowgear.drone.carrier;

import java.util.ArrayList;
import java.util.List;

/** Gameplay envelope for the future carrier missile expansion; rendering/audio remain approval-gated. */
public final class CarrierMissileModulePolicy {
    /** Four ordnance trunks, each backed by one physical 10 x 2 magazine. */
    public static final int MAX_SALVO = 4 * CarrierMissileMagazinePolicy.CAPACITY;
    public static final int MAX_PARALLEL_LOCKS = MAX_SALVO;
    public static final int MAX_SCAN_CONTACTS = 256;
    public static final int MAX_MISSILES_PER_TARGET = 8;
    public static final double NOMINAL_DIRECT_HIT_DAMAGE = 250.0;
    public static final int CONTACT_MAX_AGE_TICKS = 20 * 12;
    public static final int COLD_EJECT_TICKS = 8;
    public static final int MOTOR_IGNITION_TICKS = 14;
    public static final int CLIMB_TICKS = 48;
    public static final int TERMINAL_TICKS = 32;
    /** Compatibility constant; runtime penetration is bounded by the dimension and flight lifetime. */
    @Deprecated public static final int MAX_PENETRATION_BLOCKS = 4096;
    private CarrierMissileModulePolicy() {}

    public enum Phase { SCAN, LOCK, HATCH, EJECT, IGNITION, CLIMB, TERMINAL, PENETRATION, DETONATION, COMPLETE }

    public static boolean targetable(boolean hostile, boolean ownerDataLink, long contactAgeTicks, int targetY) {
        return hostile && ownerDataLink && contactAgeTicks >= 0 && contactAgeTicks <= CONTACT_MAX_AGE_TICKS;
    }

    public static int assignedMissiles(double threat, double healthFraction, boolean underground) {
        double effectiveHealth = Math.max(0, Double.isFinite(threat) ? threat : 0)
            * Math.clamp(Double.isFinite(healthFraction) ? healthFraction : 0, 0, 1);
        int missiles = Math.max(1, (int)Math.ceil(effectiveHealth / NOMINAL_DIRECT_HIT_DAMAGE));
        if (underground && effectiveHealth > NOMINAL_DIRECT_HIT_DAMAGE) missiles++;
        return Math.clamp(missiles, 1, MAX_MISSILES_PER_TARGET);
    }

    /**
     * Give every lock one round before assigning follow-up rounds. This keeps a large salvo
     * genuinely multi-target while retaining deterministic threat ordering from the caller.
     */
    public static List<Integer> salvoAssignments(List<Integer> desiredPerTarget, int ammunition) {
        if (desiredPerTarget == null || desiredPerTarget.isEmpty() || ammunition <= 0) return List.of();
        int remaining = Math.min(ammunition, MAX_SALVO);
        int maximum = desiredPerTarget.stream().mapToInt(value -> Math.clamp(value == null ? 0 : value,
            0, MAX_MISSILES_PER_TARGET)).max().orElse(0);
        ArrayList<Integer> result = new ArrayList<>(remaining);
        for (int pass = 0; pass < maximum && remaining > 0; pass++) {
            for (int target = 0; target < desiredPerTarget.size() && remaining > 0; target++) {
                int desired = Math.clamp(desiredPerTarget.get(target) == null ? 0 : desiredPerTarget.get(target),
                    0, MAX_MISSILES_PER_TARGET);
                if (desired <= pass) continue;
                result.add(target);
                remaining--;
            }
        }
        return List.copyOf(result);
    }
}
