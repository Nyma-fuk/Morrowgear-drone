package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

/** V22 master coordinates: +Z nose, +Y up; rotations match the rendered hull. */
public final class DroneHardpoints {
    public static final Vec3 LASER = new Vec3(0, .105, .40503);
    public static final Vec3 GUN_LEFT = new Vec3(-.2427, .298, .937);
    public static final Vec3 GUN_RIGHT = new Vec3(.2427, .298, .937);
    public static final Vec3 EXHAUST_LEFT = new Vec3(-.19, .402, -1.02);
    public static final Vec3 EXHAUST_RIGHT = new Vec3(.19, .402, -1.02);

    private DroneHardpoints() {}

    public static Vec3 rotate(Vec3 local, float yawDegrees, float pitch, float roll) {
        // PoseStack multiplies Y * X * Z, so local vertices rotate Z first.
        double cr = Math.cos(roll), sr = Math.sin(roll);
        double x = local.x * cr - local.y * sr;
        double y = local.x * sr + local.y * cr;
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double py = y * cp - local.z * sp;
        double pz = y * sp + local.z * cp;
        double yaw = Math.toRadians(-yawDegrees), cy = Math.cos(yaw), sy = Math.sin(yaw);
        return new Vec3(x * cy + pz * sy, py, -x * sy + pz * cy);
    }

    public static Vec3 worldPosition(DroneEntity drone, Vec3 local) {
        return drone.position().add(rotate(local, drone.getYRot(),
            drone.isDocked() ? 0 : FlightAttitude.pitch(drone.getDeltaMovement(), drone.getYRot()),
            drone.isDocked() ? 0 : FlightAttitude.coordinatedRoll(drone.getDeltaMovement(), drone.getYRot(),
                net.minecraft.util.Mth.wrapDegrees(drone.getYRot() - drone.yRotO))));
    }
}
