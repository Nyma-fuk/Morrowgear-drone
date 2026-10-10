package jp.morrowgear.drone;

import java.util.List;
import java.util.UUID;

/** Four-aircraft container lift contract. World mutation is permitted only in the marked phases. */
public final class CargoLiftPolicy {
    public static final int REQUIRED_AIRCRAFT = 4;
    public static final double CABLE_LENGTH = 4.0;
    public static final double LOAD_HALF_HEIGHT = 1.2;
    public static final double LOAD_HALF_LENGTH = 2.5;
    public static final double LOAD_HALF_WIDTH = 1.5;
    public static final double TERRAIN_MARGIN = 3.0;
    public static final double MIN_LOAD_BOTTOM_CLEARANCE = 2.0;
    public static final double MAX_TRANSIT_SPEED = 0.82;
    public static final double MAX_VERTICAL_SPEED = 0.36;
    public static final double TRANSIT_AIRCRAFT_SPEED = 1.02;
    public static final int ROUTE_REFRESH_TICKS = 24;
    public static final int ROUTE_PROGRESS_TICKS = 20;
    public static final int ROUTE_STALLED_TICKS = 100;
    public static final int RENDEZVOUS_NOTICE_TICKS = 240;
    public static final int RENDEZVOUS_ABORT_TICKS = 1200;
    /** Physical cable eyes remain inside the container's reinforced upper corners. */
    public static final double ATTACHMENT_X = 2.08;
    public static final double ATTACHMENT_Z = 1.08;
    /** Aircraft fly outside the load footprint so 3x3 hulls cannot overlap. */
    public static final double FORMATION_X = 4.10;
    public static final double FORMATION_Z = 3.40;
    public static final double MIN_AIRCRAFT_SEPARATION = FORMATION_Z * 2.0;
    /** The invisible formation command may lead the supported load, never the load itself. */
    public static final double MAX_GUIDANCE_LEAD = 2.40;
    public static final double MAX_VERTICAL_GUIDANCE_LEAD = 1.60;
    public static final int CONNECT_TICKS = 20;
    public static final int TENSION_TICKS = 30;
    public static final int RELEASE_TICKS = 20;

    private CargoLiftPolicy() {}

    public enum Corner { NORTH_WEST, NORTH_EAST, SOUTH_WEST, SOUTH_EAST }
    public enum Phase {
        RESERVING, RENDEZVOUS, CONNECTING, TENSIONING, LIFT, TRANSIT, DESCENT, RELEASE,
        COMPLETE, HOLD, EMERGENCY_DESCENT, ABORTED
    }

    public record Aircraft(UUID id, Corner corner, double flightFraction, boolean available,
                           boolean inTolerance, boolean winchConnected) {}

    public record Assessment(boolean ready, Phase next, String reason) {}

    public record Offset(double x, double z) {}

    /** Compatibility name: a corner is the aircraft formation station, not the cable eye. */
    public static Offset cornerOffset(Corner corner) {
        return formationOffset(corner);
    }

    public static Offset formationOffset(Corner corner) {
        return switch (corner) {
            case NORTH_WEST -> new Offset(-FORMATION_X, -FORMATION_Z);
            case NORTH_EAST -> new Offset(FORMATION_X, -FORMATION_Z);
            case SOUTH_WEST -> new Offset(-FORMATION_X, FORMATION_Z);
            case SOUTH_EAST -> new Offset(FORMATION_X, FORMATION_Z);
        };
    }

    public static Offset attachmentOffset(Corner corner) {
        return switch (corner) {
            case NORTH_WEST -> new Offset(-ATTACHMENT_X, -ATTACHMENT_Z);
            case NORTH_EAST -> new Offset(ATTACHMENT_X, -ATTACHMENT_Z);
            case SOUTH_WEST -> new Offset(-ATTACHMENT_X, ATTACHMENT_Z);
            case SOUTH_EAST -> new Offset(ATTACHMENT_X, ATTACHMENT_Z);
        };
    }

    public static Assessment assess(Phase phase, List<Aircraft> aircraft, boolean destinationClear,
                                    boolean loadPresent) {
        return assess(phase, aircraft, destinationClear, loadPresent, true);
    }

    /** Runtime form. A phase cannot advance until its physical completion condition is true. */
    public static Assessment assess(Phase phase, List<Aircraft> aircraft, boolean destinationClear,
                                    boolean loadPresent, boolean phaseReady) {
        if (!loadPresent) return new Assessment(false, Phase.ABORTED, "LOAD_MISSING");
        if (aircraft.size() != REQUIRED_AIRCRAFT
            || aircraft.stream().map(Aircraft::corner).distinct().count() != REQUIRED_AIRCRAFT)
            return new Assessment(false, phase == Phase.RESERVING ? Phase.RESERVING : Phase.HOLD, "FOUR_UNIQUE_CORNERS_REQUIRED");
        if (aircraft.stream().anyMatch(a -> !a.available()))
            return new Assessment(false, phase.ordinal() < Phase.LIFT.ordinal() ? Phase.HOLD : Phase.EMERGENCY_DESCENT, "AIRCRAFT_UNAVAILABLE");
        if (aircraft.stream().anyMatch(a -> a.flightFraction() < .28))
            return new Assessment(false, phase.ordinal() < Phase.LIFT.ordinal() ? Phase.HOLD : Phase.EMERGENCY_DESCENT, "FLIGHT_RESERVE_LOW");
        if (!destinationClear && phase.ordinal() >= Phase.TRANSIT.ordinal())
            return new Assessment(false, Phase.HOLD, "LANDING_FOOTPRINT_OCCUPIED");
        return switch (phase) {
            case RESERVING -> new Assessment(true, Phase.RENDEZVOUS, "READY");
            case RENDEZVOUS -> phaseReady && aircraft.stream().allMatch(Aircraft::inTolerance)
                ? new Assessment(true, Phase.CONNECTING, "READY") : new Assessment(false, phase, "RENDEZVOUS_PENDING");
            case CONNECTING -> phaseReady && aircraft.stream().allMatch(Aircraft::winchConnected)
                ? new Assessment(true, Phase.TENSIONING, "READY") : new Assessment(false, phase, "WINCH_PENDING");
            case TENSIONING -> phaseReady ? new Assessment(true, Phase.LIFT, "READY")
                : new Assessment(false, phase, "CABLE_TENSION_PENDING");
            case LIFT -> phaseReady ? new Assessment(true, Phase.TRANSIT, "READY")
                : new Assessment(false, phase, "LIFT_CLEARANCE_PENDING");
            case TRANSIT -> phaseReady ? new Assessment(true, Phase.DESCENT, "READY")
                : new Assessment(false, phase, "TRANSIT_ACTIVE");
            case DESCENT -> phaseReady ? new Assessment(true, Phase.RELEASE, "READY")
                : new Assessment(false, phase, "LANDING_PENDING");
            case RELEASE -> phaseReady ? new Assessment(true, Phase.COMPLETE, "READY")
                : new Assessment(false, phase, "RELEASE_PENDING");
            case HOLD -> new Assessment(false, Phase.HOLD, "OPERATOR_OR_ROUTE_REPLAN_REQUIRED");
            case EMERGENCY_DESCENT -> new Assessment(false, Phase.EMERGENCY_DESCENT, "SAFE_SURFACE_REQUIRED");
            case COMPLETE, ABORTED -> new Assessment(false, phase, "TERMINAL");
        };
    }

    public static double droneAltitude(double highestTerrain, double containerBottomOffset) {
        double loadCentre = highestTerrain + MIN_LOAD_BOTTOM_CLEARANCE + LOAD_HALF_HEIGHT;
        return loadCentre + Math.max(LOAD_HALF_HEIGHT, containerBottomOffset) + CABLE_LENGTH + TERRAIN_MARGIN;
    }

    public static double loadBaseAltitude(double highestTerrain) {
        return highestTerrain + MIN_LOAD_BOTTOM_CLEARANCE;
    }

    /** The destination footprint matters only once a transit formation reaches its holding area. */
    public static boolean landingClearRequired(Phase phase, double horizontalDistance) {
        return switch (phase) {
            case TRANSIT -> horizontalDistance < 16.0;
            case DESCENT, HOLD, EMERGENCY_DESCENT -> true;
            // RELEASE already placed the container. The container itself must not
            // be reclassified as an obstacle and send the mission back to HOLD.
            default -> false;
        };
    }

    /** Phase-aware aircraft limit. Precision speed is reserved for the last metres only. */
    public static double aircraftSpeed(Phase phase, double distance) {
        distance = Double.isFinite(distance) ? Math.max(0, distance) : 0;
        if (phase == Phase.TRANSIT) return TRANSIT_AIRCRAFT_SPEED;
        if (phase == Phase.EMERGENCY_DESCENT) return .48;
        if (phase == Phase.DESCENT || phase == Phase.RELEASE) return .42;
        if (distance >= 12) return .92;
        if (distance >= 4) return .62 + (distance - 4) * .0375;
        if (distance >= 1.2) return .22 + (distance - 1.2) * (0.40 / 2.8);
        return .18;
    }
}
