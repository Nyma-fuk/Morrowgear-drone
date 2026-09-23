package jp.morrowgear.drone.carrier;

import net.minecraft.world.phys.Vec3;

/** Canonical carrier laser contract: both endpoints are immutable world-space positions. */
public record CarrierBeamPath(Vec3 origin, Vec3 target) {
    public static final Vec3 EMITTER_LOCAL = new Vec3(0, -.1, 0);
    private static final double MIN_LENGTH_SQUARED = 1.0e-6;

    public static Vec3 emitter(Vec3 carrierPosition, float yaw) {
        return CarrierNavigation.toWorld(carrierPosition, yaw, EMITTER_LOCAL);
    }

    /** Entity data carries this offset to retain sub-block precision near the world border. */
    public static Vec3 encodeTarget(Vec3 worldOrigin, Vec3 worldTarget) {
        return worldTarget.subtract(worldOrigin);
    }

    public static CarrierBeamPath decode(Vec3 worldOrigin, Vec3 encodedTargetOffset) {
        return new CarrierBeamPath(worldOrigin, worldOrigin.add(encodedTargetOffset));
    }

    public boolean visibleDownwardRay() {
        return finite(origin) && finite(target) && target.y < origin.y - .01
            && origin.distanceToSqr(target) > MIN_LENGTH_SQUARED;
    }

    public Local toRenderLocal(Vec3 renderOrigin) {
        return new Local(origin.subtract(renderOrigin), target.subtract(renderOrigin));
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    public record Local(Vec3 origin, Vec3 target) {
    }
}
