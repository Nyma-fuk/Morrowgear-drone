package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class DroneOperationalStateTest {
	@Test
	void higherPriorityStatesOwnFlightExclusively() {
		assertEquals(DroneOperationalState.POWER_LOSS, resolve(0, true, true,
			CombatState.LASER_FIRE, true, true, true, true, true, DroneMode.WAYPOINT));
		assertEquals(DroneOperationalState.DOCKED, resolve(1000, true, true,
			CombatState.LASER_FIRE, true, true, true, true, true, DroneMode.WAYPOINT));
		assertEquals(DroneOperationalState.SERVICE_RTB, resolve(1000, false, true,
			CombatState.LASER_FIRE, true, true, true, true, true, DroneMode.WAYPOINT));
		assertEquals(DroneOperationalState.COMBAT, resolve(1000, false, false,
			CombatState.GUN_RUN, true, true, true, true, true, DroneMode.WAYPOINT));
		assertEquals(DroneOperationalState.EMERGENCY_INTERCEPT, resolve(1000, false, false,
			CombatState.IDLE, true, true, true, true, true, DroneMode.WAYPOINT));
		assertEquals(DroneOperationalState.REJOIN, resolve(1000, false, false,
			CombatState.REJOIN, false, true, true, true, true, DroneMode.WAYPOINT));
	}

	@Test
	void everyMissionAndGeneralFlightModeHasOneResolvedState() {
		assertEquals(DroneOperationalState.FIELD_OPERATION, resolve(1000, false, false,
			CombatState.IDLE, false, true, true, true, true, DroneMode.WAYPOINT));
		assertEquals(DroneOperationalState.SECURITY_PATROL, resolve(1000, false, false,
			CombatState.IDLE, false, false, true, true, true, DroneMode.WAYPOINT));
		assertEquals(DroneOperationalState.ENGINEER_SUPPORT, resolve(1000, false, false,
			CombatState.IDLE, false, false, false, true, true, DroneMode.WAYPOINT));
		assertEquals(DroneOperationalState.CARGO_ROUTE, resolve(1000, false, false,
			CombatState.IDLE, false, false, false, false, true, DroneMode.WAYPOINT));
		assertEquals(DroneOperationalState.WAYPOINT, idleMission(DroneMode.WAYPOINT));
		assertEquals(DroneOperationalState.FOLLOW, idleMission(DroneMode.FOLLOW));
		assertEquals(DroneOperationalState.RETURN, idleMission(DroneMode.RETURN));
		assertEquals(DroneOperationalState.RETURN, idleMission(DroneMode.DOCK));
		assertEquals(DroneOperationalState.ORBIT, idleMission(DroneMode.ORBIT));
		assertEquals(DroneOperationalState.STANDBY, idleMission(DroneMode.STANDBY));
	}

	private static DroneOperationalState idleMission(DroneMode mode) {
		return resolve(1000, false, false, CombatState.IDLE, false,
			false, false, false, false, mode);
	}

	private static DroneOperationalState resolve(int power, boolean docked, boolean service,
		CombatState combat, boolean emergency, boolean field, boolean security,
		boolean engineer, boolean cargo, DroneMode mode) {
		return DroneOperationalState.resolve(power, docked, service, combat, emergency,
			field, security, engineer, cargo, mode);
	}
}
