package jp.morrowgear.drone.carrier;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Eight symmetric ventral hardpoints that keep the central mining aperture and service bays clear. */
public final class CarrierCargoVisualPolicy {
    public static final double POD_Y = -1.92;
    /** Container mesh X is its long axis. Rotate it so the long axis follows the carrier nose/tail axis. */
    public static final float CONTAINER_YAW = 90.0f;
    /** Four real drum outlets sit inside the selected bay, above the lifting eyes. */
    public static final double WINCH_Y = -.34;
    public static final double CABLE_CORNER_X = 1.08, CABLE_CORNER_Z = 2.08;
    public static final double BAY_HALF_X = 1.72, BAY_HALF_Z = 2.66;
    public static final double COLLAR_TOP = .18, COLLAR_BOTTOM = -.68;
    /*
     * Service bays occupy the forward belly around z=-4.65. Expansion stations therefore
     * form two aft rows, leaving the complete approach/departure corridor unobstructed while
     * the inner four remain directly below the armored ordnance trunks.
     */
    private static final double[] X = {-9.8, -15.4, -9.8, -15.4, 9.8, 15.4, 9.8, 15.4};
    private static final double[] Z = {2.8, 2.8, 8.5, 8.5, 2.8, 2.8, 8.5, 8.5};
    private static final int ORDNANCE_MASK = (1 << 0) | (1 << 2) | (1 << 4) | (1 << 6);
    public record Pod(double x, double y, double z, int hardpoint, boolean ordnanceCapable) {}
    private CarrierCargoVisualPolicy() {}

    public static Pod hardpoint(int slot) {
        CarrierExpansionPolicy.checkedHardpoint(slot);
        return new Pod(X[slot], POD_Y, Z[slot], slot, ordnanceCapable(slot));
    }

    public static boolean ordnanceCapable(int slot) {
        CarrierExpansionPolicy.checkedHardpoint(slot);
        return (ORDNANCE_MASK & 1 << slot) != 0;
    }

    public static int ordnanceMask() { return ORDNANCE_MASK; }

    public static List<Pod> pods(int occupiedMask) {
        List<Pod> result = new ArrayList<>(CarrierExpansionPolicy.HARDPOINTS);
        for (int slot = 0; slot < CarrierExpansionPolicy.HARDPOINTS; slot++)
            if ((occupiedMask & 1 << slot) != 0) result.add(hardpoint(slot));
        return List.copyOf(result);
    }

    public static Vec3 hardpointWorld(Vec3 carrierPosition, float yaw, int slot) {
        Pod pod = hardpoint(slot);
        return CarrierNavigation.toWorld(carrierPosition, yaw, new Vec3(pod.x(), pod.y(), pod.z()));
    }

    /** Carrier belly origin that puts the selected hardpoint exactly above the block centre. */
    public static Vec3 alignedCarrierPosition(BlockPos container, double carrierY, float yaw, int slot) {
        Pod pod = hardpoint(slot);
        Vec3 horizontalOffset = CarrierNavigation.rotate(new Vec3(pod.x(), 0, pod.z()), yaw);
        Vec3 center = Vec3.atBottomCenterOf(container);
        return new Vec3(center.x - horizontalOffset.x, carrierY, center.z - horizontalOffset.z);
    }
}
