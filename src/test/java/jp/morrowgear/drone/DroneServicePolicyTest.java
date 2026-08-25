package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DroneServicePolicyTest {
	@Test
	void reserveScalesWithDockDistanceForEveryMissionType() {
		assertEquals(12, DroneServicePolicy.flightReserveForDock(0));
		assertEquals(13, DroneServicePolicy.flightReserveForDock(1));
		assertTrue(DroneServicePolicy.flightReserveForDock(512) > 20);
		assertEquals(40, DroneServicePolicy.flightReserveForDock(4096));
	}

	@Test
	void lowFlightPowerRequestsServiceWithoutRoleOrWingInputs() {
		assertEquals(DroneServicePolicy.Need.FLIGHT_POWER,
			DroneServicePolicy.serviceNeed(1.0f, 20, 512));
		assertEquals(DroneServicePolicy.Need.NONE,
			DroneServicePolicy.serviceNeed(1.0f, 100, 512));
	}

	@Test
	void criticalDamageTakesPriorityOverFlightPower() {
		assertEquals(DroneServicePolicy.Need.DAMAGE,
			DroneServicePolicy.serviceNeed(0.25f, 0, 512));
	}

	@Test
	void nonCombatAircraftWaitForSafeFlightChargeAndMaintenance() {
		assertFalse(DroneServicePolicy.nonCombatSortieReady(1.0f, 89));
		assertFalse(DroneServicePolicy.nonCombatSortieReady(0.29f, 100));
		assertTrue(DroneServicePolicy.nonCombatSortieReady(0.30f, 90));
	}

	@Test
	void savedServiceReasonFallsBackSafely() {
		assertEquals(DroneServicePolicy.Need.FLIGHT_POWER,
			DroneServicePolicy.Need.byName("FLIGHT_POWER"));
		assertEquals(DroneServicePolicy.Need.NONE,
			DroneServicePolicy.Need.byName("UNKNOWN_FROM_FUTURE_VERSION"));
	}

	@Test
	void serviceReasonEscalatesFromWeaponsToFlightAndDamage() {
		assertEquals(DroneServicePolicy.Need.FLIGHT_POWER, DroneServicePolicy.merge(
			DroneServicePolicy.Need.LASER_HEAT, DroneServicePolicy.Need.FLIGHT_POWER));
		assertEquals(DroneServicePolicy.Need.DAMAGE, DroneServicePolicy.merge(
			DroneServicePolicy.Need.FLIGHT_POWER, DroneServicePolicy.Need.DAMAGE));
		assertEquals(DroneServicePolicy.Need.DAMAGE, DroneServicePolicy.merge(
			DroneServicePolicy.Need.DAMAGE, DroneServicePolicy.Need.WEAPON_POWER));
	}

	@Test
	void maintenanceReasonSurvivesSaveAndHasAPlayerFacingStatus() {
		assertEquals(DroneServicePolicy.Need.MAINTENANCE,
			DroneServicePolicy.Need.byName("MAINTENANCE"));
		assertEquals("SUBSYSTEM SERVICE / DOCK RTB",
			DroneServicePolicy.Need.MAINTENANCE.status());
	}
}
