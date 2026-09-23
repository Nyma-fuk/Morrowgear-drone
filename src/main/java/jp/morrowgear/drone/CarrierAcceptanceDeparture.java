package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

/** Ordinary supply charging must finish before a measured voyage starts. */
final class CarrierAcceptanceDeparture {
    static final int MIN_FUEL = 2000, MIN_WAIT = 6, MAX_WAIT = 120;
    enum State { WAIT, READY, TIMEOUT }
    private CarrierAcceptanceDeparture() {}
    static State state(long elapsed, int energy) {
        if (elapsed < 0 || elapsed >= MAX_WAIT) return State.TIMEOUT;
        return elapsed >= MIN_WAIT && energy >= MIN_FUEL ? State.READY : State.WAIT;
    }
    static boolean unchanged(Vec3 stopped, Vec3 current) {
        return stopped != null && current != null && stopped.distanceToSqr(current) <= 1.0e-12;
    }
}
