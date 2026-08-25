package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DroneSubsystemPolicyTest {
	@Test
	void damageDegradesOnePrimaryAndTwoSecondarySubsystems() {
		assertEquals(820, DroneSubsystemPolicy.wear(1000, 10.0f, true));
		assertEquals(930, DroneSubsystemPolicy.wear(1000, 10.0f, false));
		assertEquals(0, DroneSubsystemPolicy.wear(5, 10.0f, true));
	}

	@Test
	void dockRepairClampsAtMaximumCondition() {
		assertEquals(1000, DroneSubsystemPolicy.repair(940, 400));
		assertEquals(940, DroneSubsystemPolicy.repair(940, -1));
	}

	@Test
	void maintenanceStartsAtTheLowestSubsystemThreshold() {
		assertFalse(DroneSubsystemPolicy.serviceRequired(1000, 1000, 421));
		assertTrue(DroneSubsystemPolicy.serviceRequired(1000, 1000, 420));
		assertEquals(DroneServicePolicy.Need.MAINTENANCE,
			DroneServicePolicy.subsystemNeed(1000, 420, 1000));
	}

	@Test
	void degradationChangesEfficiencyWithoutChangingFlightSpeed() {
		assertEquals(0, DroneSubsystemPolicy.powerPenalty(421));
		assertEquals(1, DroneSubsystemPolicy.powerPenalty(420));
		assertEquals(2, DroneSubsystemPolicy.powerPenalty(220));
		assertEquals(0.75, DroneSubsystemPolicy.sensorScale(420));
		assertEquals(0.55, DroneSubsystemPolicy.sensorScale(220));
		assertEquals(0.75, DroneSubsystemPolicy.payloadScale(420));
		assertEquals(0.50, DroneSubsystemPolicy.payloadScale(220));
	}

	@Test
	void flightSafetyStillOutranksScheduledMaintenance() {
		assertEquals(DroneServicePolicy.Need.FLIGHT_POWER, DroneServicePolicy.merge(
			DroneServicePolicy.Need.MAINTENANCE, DroneServicePolicy.Need.FLIGHT_POWER));
		assertEquals(DroneServicePolicy.Need.DAMAGE, DroneServicePolicy.merge(
			DroneServicePolicy.Need.MAINTENANCE, DroneServicePolicy.Need.DAMAGE));
	}
}
