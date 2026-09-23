package jp.morrowgear.drone;

/** Frame-rate independent, monotonic presentation smoothing; never changes navigation. */
public final class FlightPresentation {
    private FlightPresentation() {}

    public static float approach(float current, float target, double ticks, double timeConstant, double maxPerTick) {
        if (!Float.isFinite(current)) current = Float.isFinite(target) ? target : 0;
        if (!Float.isFinite(target) || !Double.isFinite(ticks) || ticks <= 0) return current;
        double dt = Math.min(ticks, 4);
        double change = (target - current) * -Math.expm1(-dt / Math.max(.01, timeConstant));
        return current + (float)Math.clamp(change, -maxPerTick * dt, maxPerTick * dt);
    }

    public static boolean deployEquipment(DroneRole role, CombatWeapon weapon, CombatState combat,
        boolean docked, boolean powerLost, boolean working) {
        if (docked || powerLost) return false;
        if (role == DroneRole.SECURITY)
            return weapon == CombatWeapon.MISSILE && (combat == CombatState.MISSILE_APPROACH
                || combat == CombatState.MISSILE_EGRESS);
        return working;
    }
}
