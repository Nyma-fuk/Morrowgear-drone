package jp.morrowgear.drone;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class SalvageLaunchPolicy {
	static final double DEPARTURE_HEIGHT = 4.8;
	static final double DEPARTURE_COMPLETE_HEIGHT = 3.6;
	static final double DEPARTURE_RADIUS = 3.2;

	private SalvageLaunchPolicy() {}

	static boolean requiresDepartureLane(Vec3 aircraft, BlockPos dock) {
		if (aircraft == null || dock == null) return false;
		double centerX = dock.getX() + 0.5;
		double centerZ = dock.getZ() + 0.5;
		double horizontal = Math.hypot(aircraft.x - centerX, aircraft.z - centerZ);
		return horizontal <= DEPARTURE_RADIUS
			&& aircraft.y < dock.getY() + DEPARTURE_COMPLETE_HEIGHT;
	}

	static Vec3 departureTarget(BlockPos dock) {
		return new Vec3(dock.getX() + 0.5, dock.getY() + DEPARTURE_HEIGHT, dock.getZ() + 0.5);
	}
}
