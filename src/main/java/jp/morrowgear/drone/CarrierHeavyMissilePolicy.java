package jp.morrowgear.drone;

import jp.morrowgear.drone.carrier.CarrierCargoVisualPolicy;
import net.minecraft.world.phys.Vec3;

/** Bounded carrier launch and flight contract for a carrier-launched bunker missile. */
public final class CarrierHeavyMissilePolicy {
    public static final int HATCH_TICKS = 24;
    public static final int LAUNCH_INTERVAL_TICKS = 4;
    public static final int POST_LAUNCH_HOLD_TICKS = 8;
    public static final int HATCH_CLOSE_TICKS = 18;
    public static final int EJECT_TICKS = 8;
    /** Absolute missile age at which the cold-ejected motor ignites. */
    public static final int IGNITION_TICKS = 14;
    public static final int CLIMB_TICKS = 112;
    public static final int TURNOVER_TICKS = 44;
    public static final int MAX_FLIGHT_TICKS = 420;
    public static final int FUSE_TICKS = 12;
    public static final double TERMINAL_SPEED = 3.05;
    public static final double TURNOVER_RATE_RADIANS = Math.toRadians(4.5);
    public static final double TERMINAL_RATE_RADIANS = Math.toRadians(9.0);
    /** Thrust-vectoring takes over close to the target so a fast round cannot orbit a nearby lock. */
    public static final double TERMINAL_CAPTURE_RADIUS = 48.0;
    public static final double TERMINAL_CAPTURE_RATE_RADIANS = Math.toRadians(42.0);
    /** Three times the former game explosion power of 7. */
    public static final float EXPLOSION_POWER = 21.0f;
    /** One direct hit cannot kill a 500 HP Warden; two direct hits are sufficient. */
    public static final float DIRECT_DAMAGE = 250.0f;
    public static final float MAX_BLAST_ENTITY_DAMAGE = 200.0f;
    public static final double HATCH_Y = 1.10;
    public static final double HATCH_HALF_WIDTH = 1.25;
    public static final double HATCH_HALF_LENGTH = 2.55;
    public static final double HATCH_OPEN_ANGLE_DEGREES = 72.0;
    public static final double CELL_BANK_X = .55;
    public static final double CELL_FIRST_Z = -2.16;
    public static final double CELL_SPACING_Z = .48;

    public record LaunchTube(double x,double y,double z) {}

    public enum LaunchPhase { IDLE, HATCH_OPEN, RIPPLE_FIRE, HATCH_CLOSE, COMPLETE }

    private CarrierHeavyMissilePolicy() {}

    public static int launchTick(int shot) {
        if (shot < 0) throw new IllegalArgumentException("Negative carrier missile index");
        return HATCH_TICKS + shot * LAUNCH_INTERVAL_TICKS;
    }

    /** Physical cell position after the five-block container is rotated fore/aft under the carrier. */
    public static LaunchTube launchTube(int hardpoint, int cell) {
        if (!CarrierCargoVisualPolicy.ordnanceCapable(hardpoint))
            throw new IllegalArgumentException("Hardpoint has no armored ordnance trunk: " + hardpoint);
        if (cell < 0 || cell >= 20) throw new IllegalArgumentException("Invalid carrier missile cell " + cell);
        var mount=CarrierCargoVisualPolicy.hardpoint(hardpoint);
        int bank=cell/10, column=cell%10;
        double bankOffset=(bank==0?-CELL_BANK_X:CELL_BANK_X)*Math.signum(mount.x());
        return new LaunchTube(mount.x()+bankOffset,HATCH_Y+.20,
            mount.z()+CELL_FIRST_Z+column*CELL_SPACING_Z);
    }

    public static int closeStartTick(int salvoSize) {
        return salvoSize <= 0 ? 0 : launchTick(salvoSize - 1) + POST_LAUNCH_HOLD_TICKS;
    }

    public static int sequenceDuration(int salvoSize) {
        return salvoSize <= 0 ? 0 : closeStartTick(salvoSize) + HATCH_CLOSE_TICKS;
    }

    /** A full-height dimension remains reachable even though penetration removes at most one block per tick. */
    public static int maxFlightTicks(int dimensionHeight) {
        return Math.max(MAX_FLIGHT_TICKS, Math.max(1, dimensionHeight) + CLIMB_TICKS + TURNOVER_TICKS + 96);
    }

    public static float explosionEntityDamage(float vanillaDamage) {
        return Math.min(MAX_BLAST_ENTITY_DAMAGE, MicroMissilePolicy.entityDamage(vanillaDamage));
    }

    public static boolean launchDue(int sequenceTick, int launched) {
        return sequenceTick >= launchTick(launched);
    }

    public static LaunchPhase launchPhase(int sequenceTick, int salvoSize) {
        if (salvoSize <= 0 || sequenceTick < 0) return LaunchPhase.IDLE;
        if (sequenceTick < HATCH_TICKS) return LaunchPhase.HATCH_OPEN;
        if (sequenceTick < closeStartTick(salvoSize)) return LaunchPhase.RIPPLE_FIRE;
        if (sequenceTick < sequenceDuration(salvoSize)) return LaunchPhase.HATCH_CLOSE;
        return LaunchPhase.COMPLETE;
    }

    public static double hatchOpen(int sequenceTick, int salvoSize) {
        LaunchPhase phase = launchPhase(sequenceTick, salvoSize);
        return switch (phase) {
            case HATCH_OPEN -> smooth(sequenceTick / (double) HATCH_TICKS);
            case RIPPLE_FIRE -> 1.0;
            case HATCH_CLOSE -> 1.0 - smooth((sequenceTick - closeStartTick(salvoSize))
                / (double) HATCH_CLOSE_TICKS);
            default -> 0.0;
        };
    }

    /** Powered ascent that doubles the former apex while beginning a shallow target-side arc. */
    public static Vec3 climbVelocity(Vec3 position, Vec3 target, int flightTick) {
        double t = Math.clamp((flightTick - IGNITION_TICKS)
            / (double)Math.max(1, CLIMB_TICKS - IGNITION_TICKS), 0, 1);
        Vec3 horizontal = new Vec3(target.x - position.x, 0, target.z - position.z);
        Vec3 direction = horizontal.lengthSqr() < 1.0e-8 ? Vec3.ZERO : horizontal.normalize();
        double horizontalSpeed = .08 + .54 * smooth(t);
        double verticalSpeed = 1.05 + .40 * smooth(t);
        return direction.scale(horizontalSpeed).add(0, verticalSpeed, 0);
    }

    /** Fin-limited pitch-over followed by faster terminal guidance; no instantaneous direction snap. */
    public static Vec3 guidedVelocity(Vec3 current, Vec3 desired, int flightTick) {
        boolean turnover = flightTick <= CLIMB_TICKS + TURNOVER_TICKS;
        double phase = turnover ? Math.clamp((flightTick - CLIMB_TICKS) / (double)TURNOVER_TICKS, 0, 1) : 1;
        double speed = turnover ? 1.65 + .80 * smooth(phase) : TERMINAL_SPEED;
        double maxTurn = terminalTurnRate(desired.length(), turnover);
        return turnToward(current, desired, maxTurn).scale(speed);
    }

    static double terminalTurnRate(double distance, boolean turnover) {
        if (turnover) return TURNOVER_RATE_RADIANS;
        double proximity = 1.0 - Math.clamp(distance / TERMINAL_CAPTURE_RADIUS, 0, 1);
        return TERMINAL_RATE_RADIANS
            + (TERMINAL_CAPTURE_RATE_RADIANS - TERMINAL_RATE_RADIANS) * smooth(proximity);
    }

    static Vec3 turnToward(Vec3 current, Vec3 desired, double maxRadians) {
        Vec3 from = current.lengthSqr() < 1.0e-8 ? new Vec3(0, 1, 0) : current.normalize();
        Vec3 to = desired.lengthSqr() < 1.0e-8 ? from : desired.normalize();
        double dot = Math.clamp(from.dot(to), -1, 1);
        double angle = Math.acos(dot);
        if (angle <= maxRadians || angle < 1.0e-8) return to;
        Vec3 axis = from.cross(to);
        if (axis.lengthSqr() < 1.0e-8) {
            axis = from.cross(Math.abs(from.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0));
        }
        axis = axis.normalize();
        double c = Math.cos(maxRadians), s = Math.sin(maxRadians);
        return from.scale(c).add(axis.cross(from).scale(s))
            .add(axis.scale(axis.dot(from) * (1 - c))).normalize();
    }

    private static double smooth(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
