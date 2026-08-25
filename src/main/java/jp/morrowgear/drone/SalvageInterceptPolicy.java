package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

final class SalvageInterceptPolicy {
	static final double MAX_HORIZONTAL_OFFSET = 1.35;
	static final double MIN_VERTICAL_CLEARANCE = 1.8;
	static final double MAX_VERTICAL_CLEARANCE = 3.75;

	private SalvageInterceptPolicy() {
	}

	static boolean readyToHook(Vec3 carrier, Vec3 load) {
		double horizontalOffset = Math.hypot(carrier.x - load.x, carrier.z - load.z);
		double verticalClearance = carrier.y - load.y;
		return horizontalOffset <= MAX_HORIZONTAL_OFFSET
			&& verticalClearance >= MIN_VERTICAL_CLEARANCE
			&& verticalClearance <= MAX_VERTICAL_CLEARANCE;
	}
}
