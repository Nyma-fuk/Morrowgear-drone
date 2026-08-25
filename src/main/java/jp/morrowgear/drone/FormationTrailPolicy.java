package jp.morrowgear.drone;

public final class FormationTrailPolicy {
	static final int MAX_LINGER_TICKS = 30;
	static final double MAX_LINGER_DISTANCE = 5.5;
	private static final double MIN_FLIGHT_SPEED_SQUARED = 0.0025;

	private FormationTrailPolicy() {}

	public static TrailStyle style(DroneMode mode, CombatState combatState, int expected, int stage,
		boolean docked, boolean serviceReturn, boolean catchingUp, int routePoints,
		int cohortRank, boolean hasLeader, boolean activeFieldOperation, boolean securityPatrol) {
		if (docked) return TrailStyle.NONE;
		if (combatState == CombatState.FLARE_ENTRY) return TrailStyle.COMBAT_ENTRY;
		if (combatState != null && combatState.active()) return TrailStyle.NONE;
		if (serviceReturn || mode == DroneMode.DOCK) return TrailStyle.SERVICE_RETURN;
		if (combatState == CombatState.REJOIN) return TrailStyle.REJOIN;
		if (activeFieldOperation || securityPatrol || mode == DroneMode.STANDBY || mode == DroneMode.ORBIT)
			return TrailStyle.NONE;
		if (catchingUp || stage == DroneEntity.MISSION_CONVERGING) return TrailStyle.CATCH_UP;
		if (routePoints > 1) return TrailStyle.NAVIGATION;
		if (mode == DroneMode.WAYPOINT && stage == DroneEntity.MISSION_MOVING)
			return TrailStyle.NAVIGATION;
		if (mode == DroneMode.RETURN) return TrailStyle.NAVIGATION;
		if (mode == DroneMode.FOLLOW && expected > 1 && cohortRank >= 0 && hasLeader
			&& stage == DroneEntity.MISSION_MOVING) return TrailStyle.NAVIGATION;
		return TrailStyle.NONE;
	}

	public enum TrailStyle {
		NONE,
		NAVIGATION,
		CATCH_UP,
		SERVICE_RETURN,
		REJOIN,
		COMBAT_ENTRY
	}

	public static boolean activeFormationFlight(int expected, int stage, boolean docked, boolean catchingUp,
		int cohortRank, boolean hasLeader, double speedSquared) {
		if (expected <= 1 || docked || cohortRank < 0 || !hasLeader
			|| speedSquared < MIN_FLIGHT_SPEED_SQUARED) return false;
		return stage == DroneEntity.MISSION_MOVING || stage == DroneEntity.MISSION_CONVERGING;
	}

	public static boolean mayLinger(int stage) {
		return stage == DroneEntity.MISSION_MOVING || stage == DroneEntity.MISSION_CONVERGING;
	}

	public static boolean activeLoopRoute(int routePoints, boolean docked, double speedSquared) {
		return routePoints > 1 && !docked && speedSquared >= MIN_FLIGHT_SPEED_SQUARED;
	}

	public static int activationDelay(int cohortRank) {
		return Math.min(18, Math.max(0, cohortRank) * 2);
	}

	public static int emissionInterval(int expected, boolean lingering) {
		int interval = expected <= 8 ? 2 : expected <= 32 ? 4 : 6;
		return lingering ? interval * 2 : interval;
	}

	public static boolean continueLinger(int ticks, double distance) {
		return ticks <= MAX_LINGER_TICKS && distance <= MAX_LINGER_DISTANCE;
	}
}
