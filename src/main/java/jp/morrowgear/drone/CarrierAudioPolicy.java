package jp.morrowgear.drone;

import jp.morrowgear.drone.carrier.CarrierPolicy;

/** Pure carrier audio gates shared by the client controller and regression tests. */
public final class CarrierAudioPolicy {
    public static final float RANGE = 192;
    public static final int MAX_EMITTERS = 2, RETRY_TICKS = 20;

    private CarrierAudioPolicy() {}

    public static boolean audible(boolean alive, boolean silent, double distanceSquared) {
        return alive && !silent && Double.isFinite(distanceSquared) && distanceSquared <= RANGE * RANGE;
    }

    public static FlightMix flightMix(double speed) {
        double normalized = Math.clamp(Double.isFinite(speed) ? speed / .42 : 0, 0, 1);
        return new FlightMix((float)(.34 - .12 * normalized), (float)(.08 + .30 * normalized),
            (float)(.88 + .12 * normalized));
    }

    public static boolean chargeStarted(CarrierPolicy.WorkPhase previous, CarrierPolicy.WorkPhase current) {
        return current == CarrierPolicy.WorkPhase.CHARGE && previous != current;
    }

    public static boolean fireActive(CarrierPolicy.WorkPhase phase, boolean beamActive) {
        return phase == CarrierPolicy.WorkPhase.FIRE && beamActive;
    }

    public static boolean cooldownStarted(CarrierPolicy.WorkPhase previous, CarrierPolicy.WorkPhase current) {
        return current == CarrierPolicy.WorkPhase.COOLDOWN && previous != current;
    }

    public static boolean hitStarted(int previousSequence, int sequence) {
        return previousSequence >= 0 && sequence != previousSequence;
    }

    public record FlightMix(float idle, float cruise, float pitch) {}
}
