package jp.morrowgear.drone.carrier;

/** Authoritative carrier/container handshake. Ownership commits before visible motion to survive interruption. */
public final class CarrierContainerTransferPolicy {
    public static final int NO_SUPPORT = Integer.MIN_VALUE;
    public static final double CONTAINER_WIDTH = 5.0;
    public static final double CONTAINER_LENGTH = 3.0;
    public static final double CONTAINER_HEIGHT = 2.4;
    public static final double CARRIER_ALIGNMENT_TOLERANCE = .35;
    public static final double CONTAINER_ALIGNMENT_TOLERANCE = .18;
    public static final double CLEARANCE_MARGIN = 1.0;
    public static final int HATCH_TICKS = 24;
    public static final int WINCH_DEPLOY_TICKS = 30;
    public static final int CONNECT_TICKS = 16;
    public static final int PURGE_TICKS = 18;
    public static final int HOIST_TICKS = 80;
    public static final int LATCH_TICKS = 20;
    public static final double PURGE_DROP = .55;
    public static final int HOLD_RETRY_TICKS = 40;
    public static final int MAX_RECOVERY_ATTEMPTS = 3;
    public static final int APPROACH_STALL_TICKS = 200;
    public static final double ALTITUDE_REPLAN_STEP = 16.0;

    private CarrierContainerTransferPolicy() {}

    public enum Direction { LOAD, UNLOAD }
    public enum Phase {
        RESERVED, APPROACH, ALIGN, HATCH_OPEN, WINCH_DEPLOY, CONNECT, TENSION,
        HOIST, ATTACH, PURGE, DEPLOY, GROUND_CONTACT, RELEASE, WINCH_RETRACT, HATCH_CLOSE,
        COMPLETE, HOLD, ABORTED
    }

    public record Conditions(boolean carrierPresent, boolean containerPresent, boolean hardpointReserved,
                             boolean carrierAligned, boolean volumeClear, boolean containerClosed,
                             boolean inventoryLeaseFree, boolean groundClear, boolean grounded) {}
    public record Step(Phase next, boolean commitLocation, String reason) {}

    public static Step next(Direction direction, Phase phase, Conditions c) {
        if (!c.carrierPresent()) return new Step(Phase.HOLD, false, "CARRIER_UNAVAILABLE");
        if (!c.hardpointReserved()) return new Step(Phase.ABORTED, false, "HARDPOINT_RESERVATION_LOST");
        if (!c.inventoryLeaseFree()) return new Step(Phase.HOLD, false, "CONTAINER_IN_USE");
        if (direction == Direction.LOAD && !c.containerPresent() && phase.ordinal() < Phase.ATTACH.ordinal())
            return new Step(Phase.ABORTED, false, "CONTAINER_MISSING");
        if (!c.containerClosed()) return new Step(Phase.HOLD, false, "CONTAINER_OPEN");
        return switch (phase) {
            case RESERVED -> new Step(Phase.APPROACH, false, "READY");
            case APPROACH -> c.carrierAligned() ? new Step(Phase.ALIGN, false, "READY")
                : new Step(Phase.APPROACH, false, "CARRIER_APPROACHING");
            case ALIGN -> c.volumeClear() ? new Step(Phase.HATCH_OPEN, false, "READY")
                : new Step(Phase.HOLD, false, "TRANSFER_VOLUME_BLOCKED");
            case HATCH_OPEN -> direction == Direction.LOAD
                ? new Step(Phase.WINCH_DEPLOY, false, "READY")
                : new Step(Phase.TENSION, false, "READY");
            case WINCH_DEPLOY -> new Step(Phase.CONNECT, false, "READY");
            case CONNECT -> new Step(Phase.TENSION, false, "READY");
            case TENSION -> direction == Direction.LOAD ? new Step(Phase.HOIST, true, "LOCATION_COMMIT_CARRIER")
                : new Step(Phase.PURGE, false, "READY");
            case HOIST -> new Step(Phase.ATTACH, false, "READY");
            case ATTACH -> new Step(Phase.WINCH_RETRACT, false, "READY");
            case PURGE -> new Step(Phase.DEPLOY, false, "READY");
            case DEPLOY -> c.groundClear() ? new Step(Phase.GROUND_CONTACT, false, "READY")
                : new Step(Phase.HOLD, false, "LANDING_FOOTPRINT_OCCUPIED");
            case GROUND_CONTACT -> c.grounded() ? new Step(Phase.RELEASE, true, "LOCATION_COMMIT_PLACED")
                : new Step(Phase.GROUND_CONTACT, false, "DESCENDING");
            case RELEASE -> new Step(Phase.WINCH_RETRACT, false, "READY");
            case WINCH_RETRACT -> new Step(Phase.HATCH_CLOSE, false, "READY");
            case HATCH_CLOSE -> new Step(Phase.COMPLETE, false, "READY");
            case COMPLETE, ABORTED -> new Step(phase, false, "TERMINAL");
            case HOLD -> new Step(Phase.HOLD, false, "REPLAN_REQUIRED");
        };
    }

    /** Smooth crane motion with zero velocity at both ends, avoiding a mechanical snap. */
    public static double eased(double progress) {
        double value = Math.clamp(progress, 0, 1);
        return value * value * (3 - 2 * value);
    }

    /** Highest terrain contact under the five-by-three footprint. */
    public static int supportPlane(int minY, int maxY, int... surfaceHeights) {
        if (surfaceHeights == null || surfaceHeights.length == 0) return NO_SUPPORT;
        int result = minY;
        for (int height : surfaceHeights) result = Math.max(result, height);
        return result < minY || result + 2 >= maxY ? NO_SUPPORT : result;
    }

    /** Keep the current safe cruise altitude; the crane, not the hull, descends to the load. */
    public static double initialApproachAltitude(double currentY, int containerY, int minY, int maxY) {
        double floor = Math.max(minY + 16.0, containerY + CONTAINER_HEIGHT
            + CarrierNavigation.GROUND_CLEARANCE + CLEARANCE_MARGIN);
        double ceiling = maxY - CarrierPolicy.HEIGHT - 2.0;
        if (floor > ceiling) return Double.NaN;
        return Math.clamp(Math.max(currentY, floor), floor, ceiling);
    }

    public static double nextApproachAltitude(double currentY, int maxY) {
        double ceiling = maxY - CarrierPolicy.HEIGHT - 2.0;
        double next = Math.ceil((currentY + 0.001) / ALTITUDE_REPLAN_STEP) * ALTITUDE_REPLAN_STEP;
        if (next <= currentY + 0.001) next += ALTITUDE_REPLAN_STEP;
        return next <= ceiling ? next : Double.NaN;
    }

    /** Intentionally has no gameplay maximum; build height is the only physical endpoint. */
    public static double winchLength(double carrierBellyY, double containerBottomY) {
        return Math.max(0, carrierBellyY + CarrierCargoVisualPolicy.WINCH_Y
            - (containerBottomY + CONTAINER_HEIGHT));
    }
}
