package jp.morrowgear.drone;

/** Shared clearance for the three-block runtime airframe, independent of its collision proxy. */
public final class AirframeEnvelope {
	public static final double SPAN = 3.0;
	public static final double SLOT_DISTANCE = 5.0;
	public static final double ORBIT_HEIGHT = 8.0;
	public static final double ORBIT_RADIUS = 8.0;
	public static final double LAYER_HEIGHT = 6.0;
	public static final double COMBAT_CLEARANCE = 6.0;

	private AirframeEnvelope() {}

	public static double orbitRadius(int count) {
		return count <= 1 ? ORBIT_RADIUS : Math.max(ORBIT_RADIUS, SLOT_DISTANCE / (2 * Math.sin(Math.PI / count)));
	}

	public static double angularSpeed(double requested, double radius) {
		return Math.min(requested, 0.36 / Math.max(1, radius));
	}
}
