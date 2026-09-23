package jp.morrowgear.drone;

/** Dock-only distance budget, measured from the entire visible multiblock. */
public final class DockGeometryPolicy {
    public enum Detail { FULL, MEDIUM, FAR }
    // Includes the 5x5 master, all cardinal rotations and the 0.018 glow shell.
    public static final double HALF_WIDTH = 2.52;
    public static final double MIN_Y = -.02;
    public static final double MAX_Y = .44;

    private DockGeometryPolicy() {}

    public static double distanceSquared(double x, double y, double z) {
        double dx = Math.max(0, Math.abs(x) - HALF_WIDTH);
        double dy = Math.max(0, Math.max(MIN_Y - y, y - MAX_Y));
        double dz = Math.max(0, Math.abs(z) - HALF_WIDTH);
        return dx * dx + dy * dy + dz * dz;
    }

    public static Detail detail(double distanceSquared) {
        if (distanceSquared <= 6 * 6) return Detail.FULL;
        if (distanceSquared <= 20 * 20) return Detail.MEDIUM;
        return Detail.FAR;
    }
}
