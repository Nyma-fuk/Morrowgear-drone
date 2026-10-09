package jp.morrowgear.drone.client;

/** Bounded detail policy for condensed-energy beam geometry. */
final class HighEnergyBeamPolicy {
    record Profile(int compressionPackets, int filamentSegments, int filaments,
                   double hazeScale, double packetSpeed) {}

    private HighEnergyBeamPolicy() {}

    static Profile profile(RuntimeGeometryBudgetPolicy.Detail detail, boolean carrier) {
        if (carrier) return switch (detail) {
            case FULL -> new Profile(12, 28, 3, 1.9, .118);
            case MEDIUM -> new Profile(7, 16, 2, 1.55, .118);
            case FAR -> new Profile(2, 0, 0, 1.18, .118);
        };
        return switch (detail) {
            case FULL -> new Profile(6, 18, 2, 1.75, .19);
            case MEDIUM -> new Profile(4, 10, 1, 1.45, .19);
            case FAR -> new Profile(1, 0, 0, 1.12, .19);
        };
    }

    static double packetProgress(float age, int packet, int count, double speed) {
        if (count <= 0 || packet < 0 || packet >= count || !Double.isFinite(age)
            || !Double.isFinite(speed)) return 0;
        double phase = age * speed + packet / (double) count;
        return phase - Math.floor(phase);
    }
}
