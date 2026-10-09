package jp.morrowgear.drone;

final class CombatRejoinPolicy {
	static final long MAX_REJOIN_TICKS = 240L;
	static final double COMPLETE_DISTANCE = 4.5;

	private CombatRejoinPolicy() {
	}

	static DroneMode resumeMode(int capturedMode, boolean hasWaypoint, boolean hasFollowMission,
		boolean launchedFromDock, boolean hasDock) {
		// A dynamically allocated berth may have been yielded while this aircraft was
		// fighting. Keep the return intent so allocation can reserve another berth or
		// place the aircraft in the bounded holding orbit instead of abandoning it.
		if (launchedFromDock) return DroneMode.DOCK;
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
