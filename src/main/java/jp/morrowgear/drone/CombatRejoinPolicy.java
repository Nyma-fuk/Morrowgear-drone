package jp.morrowgear.drone;

final class CombatRejoinPolicy {
	static final long MAX_REJOIN_TICKS = 240L;
	static final double COMPLETE_DISTANCE = 4.5;

	private CombatRejoinPolicy() {
	}

	static DroneMode resumeMode(int capturedMode, boolean hasWaypoint, boolean hasFollowMission,
		boolean launchedFromDock, boolean hasDock) {
		if (launchedFromDock && hasDock) return DroneMode.DOCK;
		DroneMode captured = DroneMode.byId(capturedMode);
		if (captured == DroneMode.WAYPOINT && !hasWaypoint) return DroneMode.STANDBY;
		if (captured == DroneMode.FOLLOW && !hasFollowMission) return DroneMode.STANDBY;
		if (captured == DroneMode.DOCK && !hasDock) return DroneMode.STANDBY;
		return captured;
	}

	static boolean complete(double distanceToResumeTarget, long elapsedTicks) {
		return distanceToResumeTarget <= COMPLETE_DISTANCE || elapsedTicks >= MAX_REJOIN_TICKS;
	}
}
