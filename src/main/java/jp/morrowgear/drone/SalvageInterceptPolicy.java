package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

final class SalvageInterceptPolicy {
	static final double MAX_HORIZONTAL_OFFSET = 1.75;
	static final double APPROACH_HOOK_CLEARANCE = 0.75;
	static final double MIN_HOOK_CLEARANCE = -0.15;
	static final double MAX_HOOK_CLEARANCE = 2.75;

	private SalvageInterceptPolicy() {
	}

	/** A transient service return owns navigation until the aircraft has left the Dock again. */
	static boolean controlsNavigation(SalvageState state, boolean targetAvailable,
		boolean serviceReturn, boolean dockApproach) {
		return state == SalvageState.INTERCEPT && targetAvailable
			&& !serviceReturn && !dockApproach;
	}

	static Vec3 approachPosition(Vec3 load, double loadHeight) {
		return load.add(0, Math.max(0.0, loadHeight) + SalvageTowPolicy.HOOK_DROP
			+ APPROACH_HOOK_CLEARANCE, 0);
	}

	static boolean readyToHook(Vec3 carrier, Vec3 load, double loadHeight) {
		double horizontalOffset = Math.hypot(carrier.x - load.x, carrier.z - load.z);
		double hookY = carrier.y - SalvageTowPolicy.HOOK_DROP;
		double verticalClearance = hookY - (load.y + Math.max(0.0, loadHeight));
		return horizontalOffset <= MAX_HORIZONTAL_OFFSET
			&& verticalClearance >= MIN_HOOK_CLEARANCE
			&& verticalClearance <= MAX_HOOK_CLEARANCE;
	}
}
