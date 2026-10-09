package jp.morrowgear.drone;

/** Decisions shared by salvage transitions and their game-independent regression tests. */
final class SalvageSafetyPolicy {
    static final int PARTNER_LOAD_GRACE_TICKS = 200;

    private SalvageSafetyPolicy() {}

    static boolean committed(SalvageState state, boolean hasTarget) {
        return hasTarget && switch (state) {
            case HOOK, HOIST, RETURN, DELIVER, SERVICE -> true;
            default -> false;
        };
    }

    static boolean mayDivertToSolar(SalvageState state) {
        return state != SalvageState.HOOK && state != SalvageState.HOIST
            && state != SalvageState.DELIVER && state != SalvageState.SERVICE;
    }

    static boolean engineerAvailable(EngineerState state) { return state == EngineerState.IDLE; }

    static boolean engineerAvailable(EngineerState state, java.util.UUID repairTarget,
        java.util.UUID lostTarget, boolean powerLost, boolean obstructed) {
        return engineerAvailable(state) || state == EngineerState.APPROACH
            && repairTarget != null && repairTarget.equals(lostTarget) && powerLost && obstructed;
    }

    static boolean repairResumable(boolean alive, boolean sameOwner, boolean damaged,
        boolean powerLost, boolean recovering) {
        return alive && sameOwner && damaged && !powerLost && !recovering;
    }

    static boolean waitForPartner(int missingTicks) {
        return missingTicks >= 0 && missingTicks < PARTNER_LOAD_GRACE_TICKS;
    }

    static boolean preserveOnRemoval(boolean destroyed, boolean unloaded) { return !destroyed && unloaded; }
}
