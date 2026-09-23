package jp.morrowgear.drone.carrier;

import net.minecraft.world.phys.Vec3;

/** V28 exhaust centers, in the same local coordinates as the canonical mesh. */
public final class CarrierTrailPolicy {
    public static final Vec3 LEFT = new Vec3(-7, 6.7, 24.9);
    public static final Vec3 RIGHT = new Vec3(7, 6.7, 24.9);
    public static final int MAX_TRACKED = 4;
    public static final int PACKET_GAP_TICKS = 1;
    public static final double RANGE = 384;
    private CarrierTrailPolicy() {}

    public static boolean emitting(CarrierPolicy.Mode mode, double distance, long elapsed) {
        return mode == CarrierPolicy.Mode.IDLE && elapsed == 1
            && Double.isFinite(distance) && distance >= .025 && distance <= 1;
    }

    public static Vec3 rotate(Vec3 local, float yaw) {
        return CarrierNavigation.rotate(local, yaw);
    }
}
