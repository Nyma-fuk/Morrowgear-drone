package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

public final class SolarStationLightingPolicy {
	public static final int SATELLITE_COUNT = 4;
	public static final double ORBIT_RADIUS = 2.35;
	public static final double HEIGHT_OFFSET = -0.45;
	public static final int LIGHT_LEVEL = 15;
	public static final int UPDATE_INTERVAL_TICKS = 4;

	private SolarStationLightingPolicy() {}

	public static Vec3 orbitOffset(int slot, float headingDegrees) {
		double angle = Math.toRadians(headingDegrees + Math.floorMod(slot, SATELLITE_COUNT) * 90.0);
		return new Vec3(Math.cos(angle) * ORBIT_RADIUS, HEIGHT_OFFSET,
			Math.sin(angle) * ORBIT_RADIUS);
	}

	public static int directLightLowerBound(double verticalDistanceBelowStation) {
		int horizontalSteps = (int)Math.ceil(ORBIT_RADIUS);
		int verticalSteps = (int)Math.ceil(Math.max(0.0,
			verticalDistanceBelowStation + HEIGHT_OFFSET));
		return Math.max(0, LIGHT_LEVEL - horizontalSteps - verticalSteps);
	}
}
