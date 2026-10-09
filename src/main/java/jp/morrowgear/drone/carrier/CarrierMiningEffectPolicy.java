package jp.morrowgear.drone.carrier;

/** Deterministic mining presentation timeline shared by server state and the client renderer. */
public final class CarrierMiningEffectPolicy {
    public static final int SCAN_CHUNKS = 9;
    public static final int RING_EMITTERS = 16;
    public static final int EMITTER_INTERVAL_TICKS = 8;
    public static final int CORE_MATURATION_TICKS = 64;
    public static final int EMITTER_SEQUENCE_TICKS = RING_EMITTERS * EMITTER_INTERVAL_TICKS;
    public static final int RELEASE_TICKS = 60;
    public static final double RING_EMITTER_RADIUS = 3.8;
    public static final double CHARGE_DESCENT = 2.4;
    private static final ChunkOffset[] SCAN_ORDER = {
        new ChunkOffset(-1, -1), new ChunkOffset(0, -1), new ChunkOffset(1, -1),
        new ChunkOffset(1, 0), new ChunkOffset(1, 1), new ChunkOffset(0, 1),
        new ChunkOffset(-1, 1), new ChunkOffset(-1, 0), new ChunkOffset(0, 0)
    };

    private CarrierMiningEffectPolicy() {}

    public record ChunkOffset(int x, int z) {}
    public record Visual(int activeEmitters, double sphereRadius, double sphereTravel,
                         double plasmaProgress, double beamStrength, double releasePulse) {}

    public static ChunkOffset scanChunk(int tick) {
        int index = Math.clamp((long) Math.max(0, tick) * SCAN_CHUNKS
            / Math.max(1, CarrierPolicy.SCAN_TICKS), 0, SCAN_CHUNKS - 1);
        return SCAN_ORDER[index];
    }

    public static Visual visual(CarrierPolicy.WorkPhase phase, int tick, int duration) {
        int safeTick = Math.max(0, tick);
        if (phase == CarrierPolicy.WorkPhase.CHARGE) {
            int emitters = Math.clamp(safeTick / EMITTER_INTERVAL_TICKS + 1, 1, RING_EMITTERS);
            double emitterProgress = Math.clamp((safeTick + 1.0) / EMITTER_SEQUENCE_TICKS, 0, 1);
            double maturation = progress(Math.max(0, safeTick - EMITTER_SEQUENCE_TICKS),
                CORE_MATURATION_TICKS);
            double formed = smooth(.42 * emitterProgress + .58 * maturation);
            double plasma = smooth(Math.max(0, (formed - .22) / .78));
            return new Visual(emitters, .10 + 1.25 * formed,
                .18 * smooth(maturation), plasma, 0, 0);
        }
        if (phase == CarrierPolicy.WorkPhase.FIRE) {
            double release = progress(safeTick, RELEASE_TICKS);
            double pulsePhase = Math.clamp((release - .72) / .28, 0, 1);
            double pulse = release >= 1 ? 0 : Math.sin(Math.PI * pulsePhase);
            // The core remains visible after surface contact and follows the active excavation depth.
            return new Visual(RING_EMITTERS, release >= 1 ? 1.05 : 1.35 - .18 * release,
                release * release, 1, 0, pulse);
        }
        return new Visual(0, 0, 0, 0, 0, 0);
    }

    public static double emitterIntensity(int emitter, int tick) {
        if (emitter < 0 || emitter >= RING_EMITTERS) return 0;
        int age = tick - emitter * EMITTER_INTERVAL_TICKS;
        return age < 0 ? 0 : Math.clamp((age + 1.0) / EMITTER_INTERVAL_TICKS, 0, 1);
    }

    public static boolean releaseComplete(int miningScanTicks) {
        return miningScanTicks >= CarrierPolicy.SCAN_TICKS + CarrierPolicy.MINING_CHARGE_TICKS + RELEASE_TICKS;
    }

    private static double progress(int tick, int duration) {
        if (duration <= 0) return 0;
        return Math.clamp((tick + 1.0) / duration, 0, 1);
    }

    private static double smooth(double value) {
        double v = Math.clamp(value, 0, 1);
        return v * v * (3 - 2 * v);
    }
}
