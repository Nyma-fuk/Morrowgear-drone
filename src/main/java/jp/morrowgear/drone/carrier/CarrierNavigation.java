package jp.morrowgear.drone.carrier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Distance-independent travel, with a strictly local swept envelope and bounded waiting. */
public final class CarrierNavigation {
    public static final double SPEED = .5, GROUND_CLEARANCE = 8;
    public static final double ACCELERATION = .015, BRAKING = .025;
    public static final float YAW_SLEW = 2;
    private static final double HEADING_EPSILON = 1.0e-4;
    public static final int LOAD_WAIT_TICKS = 100;
    public static final int TICKETS_PER_SHIP = 2, TICKET_RADIUS = 3;
    public static final int MAX_TICKETS = CarrierPolicy.MAX_ACTIVE_CHUNK_LEASES * TICKETS_PER_SHIP;
    public static final int FULL_CHUNK_INFLUENCE_BOUND = MAX_TICKETS * (2 * TICKET_RADIUS + 1) * (2 * TICKET_RADIUS + 1);
    public static int generationInfluenceBound() {
        int diameter = 2 * (TICKET_RADIUS + net.minecraft.server.level.ChunkLevel.RADIUS_AROUND_FULL_CHUNK) + 1;
        return MAX_TICKETS * diameter * diameter;
    }
    private CarrierNavigation() {}
    public record Motion(Vec3 velocity, float yaw) {}

    /** X/Z address a block column; Y is the carrier belly-plane altitude, not a block centre. */
    public static Vec3 destination(CarrierAnchor anchor) {
        return new Vec3(anchor.x() + .5, anchor.y(), anchor.z() + .5);
    }

    public static Vec3 destination(BlockPos target) {
        return new Vec3(target.getX() + .5, target.getY(), target.getZ() + .5);
    }

    public static boolean arrived(Vec3 from, Vec3 destination) {
        return from.distanceToSqr(destination) <= 1.0e-12;
    }

    /** Local nose is -Z. The renderer uses the same positive-Y rotation, with no visual-only bank. */
    public static Vec3 rotate(Vec3 local, float yaw) {
        double angle = Math.toRadians(yaw), cosine = Math.cos(angle), sine = Math.sin(angle);
        return new Vec3(local.x * cosine + local.z * sine, local.y, -local.x * sine + local.z * cosine);
    }
    public static Vec3 toWorld(Vec3 origin, float yaw, Vec3 local) { return origin.add(rotate(local, yaw)); }
    public static Vec3 toLocal(Vec3 origin, float yaw, Vec3 world) { return rotate(world.subtract(origin), -yaw); }
    public static float angleDifference(float to, float from) {
        float delta = (to - from) % 360;
        return delta >= 180 ? delta - 360 : delta < -180 ? delta + 360 : delta;
    }
    public static float heading(Vec3 delta, float fallback) {
        return delta.x * delta.x + delta.z * delta.z < 1.0e-12 ? fallback
            : (float) Math.toDegrees(Math.atan2(-delta.x, -delta.z));
    }
    /** Brake on the old course, turn at rest, then accelerate along the aligned nose; never strafe. */
    public static Motion advance(Vec3 from, Vec3 destination, float yaw, Vec3 velocity) {
        return advance(from, destination, yaw, velocity, SPEED);
    }
    public static Motion advance(Vec3 from, Vec3 destination, float yaw, Vec3 velocity, double speedLimit) {
        Vec3 delta = destination.subtract(from);
        speedLimit = Math.clamp(speedLimit, CarrierPowerPolicy.LOW_POWER_SPEED, SPEED);
        double distance = delta.length(), speed = Math.min(SPEED, velocity.length());
        if (distance < 1.0e-8) return new Motion(Vec3.ZERO, yaw);
        float error = angleDifference(heading(delta, yaw), yaw);
        boolean turn = Math.abs(error) > HEADING_EPSILON;
        boolean reverse = speed > 1.0e-8 && velocity.normalize().dot(delta.normalize()) < .99999;
        if ((turn || reverse) && speed > 1.0e-8)
            return new Motion(velocity.normalize().scale(Math.max(0, speed - BRAKING)), yaw);
        if (turn) return new Motion(Vec3.ZERO, yaw + Math.clamp(error, -YAW_SLEW, YAW_SLEW));
        // Complete a short final leg exactly. The continuous braking formula otherwise approaches
        // zero asymptotically; the speed guard keeps the discrete deceleration within BRAKING.
        if (distance <= ACCELERATION && speed <= distance + BRAKING)
            return new Motion(delta, yaw);
        // Discrete stopping distance includes this tick's step, preventing a final overshoot.
        double desired = Math.min(speedLimit, Math.sqrt(BRAKING * BRAKING + 2 * BRAKING * distance) - BRAKING);
        double next = Math.min(distance, speed + Math.clamp(desired - speed, -BRAKING, ACCELERATION));
        return new Motion(delta.normalize().scale(Math.max(0, next)), yaw);
    }
    public static Vec3 step(Vec3 from, Vec3 destination) {
        Vec3 delta = destination.subtract(from);
        return delta.lengthSqr() <= SPEED * SPEED ? delta : delta.normalize().scale(SPEED);
    }
    public static AABB sweep(Vec3 from, Vec3 to) {
        return sweep(from, 0, to, 0);
    }
    public static AABB bounds(Vec3 origin, float yaw) {
        double angle = Math.toRadians(yaw), cosine = Math.abs(Math.cos(angle)), sine = Math.abs(Math.sin(angle));
        double x = (CarrierPolicy.WIDTH * cosine + CarrierPolicy.LENGTH * sine) / 2;
        double z = (CarrierPolicy.WIDTH * sine + CarrierPolicy.LENGTH * cosine) / 2;
        return new AABB(origin.x - x, origin.y, origin.z - z, origin.x + x, origin.y + CarrierPolicy.HEIGHT, origin.z + z);
    }
    public static AABB sweep(Vec3 from, float fromYaw, Vec3 to, float toYaw) {
        double radius = Math.hypot(CarrierPolicy.WIDTH, CarrierPolicy.LENGTH) / 2;
        // Every corner's intervening arc is bounded by r * angle, not just its two endpoints.
        double arc = radius * Math.toRadians(Math.abs(angleDifference(toYaw, fromYaw)));
        return bounds(from, fromYaw).minmax(bounds(to, toYaw)).inflate(1 + arc, 1, 1 + arc);
    }
    public static BlockPos ahead(Vec3 from, Vec3 destination) {
        Vec3 delta = destination.subtract(from);
        Vec3 horizontal = new Vec3(delta.x, 0, delta.z);
        return BlockPos.containing(horizontal.lengthSqr() < .0001 ? from : from.add(horizontal.normalize().scale(16)));
    }
    public static boolean validTarget(Vec3 target, int minY, int maxY) {
        return Double.isFinite(target.x) && Double.isFinite(target.y) && Double.isFinite(target.z)
            && Math.abs(target.x) <= 29_999_000 && Math.abs(target.z) <= 29_999_000
            && target.y >= minY + 16 && target.y + CarrierPolicy.HEIGHT + 1 < maxY;
    }
}
