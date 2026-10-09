package jp.morrowgear.drone.carrier;

/** Pure estimates for HMI countdowns. Runtime safety continues to use authoritative server state. */
public final class CarrierActionEstimatePolicy {
    private CarrierActionEstimatePolicy() {}

    public static int phaseRemainingTicks(int phaseTick, int phaseDuration) {
        return Math.max(0, phaseDuration - Math.max(0, phaseTick));
    }

    public static int miningRemainingTicks(CarrierPolicy.WorkPhase phase, int phaseTick, int phaseDuration,
                                           int cursor, int total) {
        int preparation = switch (phase) {
            case SCAN -> phaseRemainingTicks(phaseTick, phaseDuration)
                + CarrierPolicy.MINING_CHARGE_TICKS + CarrierMiningEffectPolicy.RELEASE_TICKS;
            case CHARGE -> phaseRemainingTicks(phaseTick, phaseDuration) + CarrierMiningEffectPolicy.RELEASE_TICKS;
            case FIRE -> phaseRemainingTicks(phaseTick, CarrierMiningEffectPolicy.RELEASE_TICKS);
            default -> CarrierPolicy.SCAN_TICKS + CarrierPolicy.MINING_CHARGE_TICKS
                + CarrierMiningEffectPolicy.RELEASE_TICKS;
        };
        int cells = Math.max(0, total - Math.max(0, cursor));
        int excavation = (cells + CarrierPowerPolicy.SUSTAINED_MINING_BLOCKS_PER_TICK - 1)
            / CarrierPowerPolicy.SUSTAINED_MINING_BLOCKS_PER_TICK;
        return preparation + excavation;
    }

    public static int movementRemainingTicks(CarrierAnchor from, CarrierAnchor destination) {
        if (from == null || destination == null || !from.dimension().equals(destination.dimension())) return -1;
        double dx = destination.x() - from.x(), dy = destination.y() - from.y(), dz = destination.z() - from.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Includes a bounded allowance for braking, alignment and acceleration.
        return (int) Math.ceil(distance / CarrierNavigation.SPEED) + 40;
    }

    public static int energyReadyTicks(int stored, int required, int generatedPerTick) {
        if (stored >= required) return 0;
        if (generatedPerTick <= 0) return -1;
        return (required - Math.max(0, stored) + generatedPerTick - 1) / generatedPerTick;
    }
}
