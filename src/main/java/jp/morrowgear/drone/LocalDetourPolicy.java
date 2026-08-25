package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

final class LocalDetourPolicy {
	static final int HOLD_TICKS = 18;
	private static final double ARRIVAL_DISTANCE = 0.85;
	private static final double GOAL_CHANGE_DISTANCE = 6.0;

	private LocalDetourPolicy() {
	}

	static boolean reusable(Vec3 position, Vec3 detour, Vec3 previousGoal,
		Vec3 requestedGoal, int ticksRemaining, boolean corridorClear) {
		return ticksRemaining > 0 && detour != null && previousGoal != null
			&& requestedGoal != null && corridorClear
			&& position.distanceTo(detour) > ARRIVAL_DISTANCE
			&& previousGoal.distanceTo(requestedGoal) <= GOAL_CHANGE_DISTANCE;
	}
}
