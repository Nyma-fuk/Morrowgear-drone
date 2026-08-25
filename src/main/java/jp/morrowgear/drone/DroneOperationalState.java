package jp.morrowgear.drone;

public enum DroneOperationalState {
	POWER_LOSS,
	DOCKED,
	SERVICE_RTB,
	COMBAT,
	EMERGENCY_INTERCEPT,
	REJOIN,
	FIELD_OPERATION,
	SECURITY_PATROL,
	ENGINEER_SUPPORT,
	CARGO_ROUTE,
	WAYPOINT,
	FOLLOW,
	RETURN,
	ORBIT,
	STANDBY;

	static DroneOperationalState resolve(int flightPower, boolean docked, boolean serviceReturn,
		CombatState combatState, boolean emergencyIntercept, boolean fieldOperation,
		boolean securityPatrol, boolean engineerSupport, boolean cargoRoute, DroneMode mode) {
		if (flightPower <= 0) return POWER_LOSS;
		if (docked) return DOCKED;
		if (serviceReturn) return SERVICE_RTB;
		CombatState combat = combatState == null ? CombatState.IDLE : combatState;
		if (combat.active()) return COMBAT;
		if (emergencyIntercept) return EMERGENCY_INTERCEPT;
		if (combat.rejoining()) return REJOIN;
		if (fieldOperation) return FIELD_OPERATION;
		if (securityPatrol) return SECURITY_PATROL;
		if (engineerSupport) return ENGINEER_SUPPORT;
		if (cargoRoute) return CARGO_ROUTE;
		DroneMode flightMode = mode == null ? DroneMode.STANDBY : mode;
		return switch (flightMode) {
			case WAYPOINT -> WAYPOINT;
			case FOLLOW -> FOLLOW;
			case RETURN, DOCK -> RETURN;
			case ORBIT -> ORBIT;
			case STANDBY -> STANDBY;
		};
	}
}
