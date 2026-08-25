package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DockServicePolicyTest {
	@Test
	void damagedAircraftRequiresMaterialBeforeSortieCanComplete() {
		assertTrue(DockServicePolicy.needsRepair(12.0f, 40.0f));
		assertFalse(DockServicePolicy.needsRepair(40.0f, 40.0f));
		assertEquals("DOCK WAIT / REPAIR MATERIAL", DockServicePolicy.waitingStatus(
			DroneServicePolicy.Need.DAMAGE, true, false, true));
	}

	@Test
	void serviceReportsTheResourceThatActuallyBlocksReadiness() {
		assertEquals("DOCK WAIT / AMMUNITION", DockServicePolicy.waitingStatus(
			DroneServicePolicy.Need.AUTOCANNON_AMMO, true, true, false));
		assertEquals("DOCK WAIT / POWER SUPPLY", DockServicePolicy.waitingStatus(
			DroneServicePolicy.Need.WEAPON_POWER, false, true, true));
		assertEquals("DOCK SERVICE / IN PROGRESS", DockServicePolicy.waitingStatus(
			DroneServicePolicy.Need.WEAPON_POWER, true, true, true));
	}

	@Test
	void partialPowerRemainderLoadsTheNextCellBeforeServiceStalls() {
		assertTrue(DockServicePolicy.shouldLoadNextPowerCell(3, 5));
		assertFalse(DockServicePolicy.shouldLoadNextPowerCell(5, 5));
		assertTrue(DockServicePolicy.shouldLoadNextPowerCell(1, 2));
	}

	@Test
	void readinessStatusExposesEveryRemainingSortieGate() {
		assertEquals("DOCK WAIT / POWER SUPPLY / FLT 75/65 WPN 89/65 AMMO 69/1",
			DockServicePolicy.readinessStatus("DOCK WAIT / POWER SUPPLY",
				75, 89, 65, 69, 1));
	}

	@Test
	void unavailablePowerOrAmmunitionCanEndAnImpossibleServiceWait() {
		assertTrue(DockServicePolicy.completionResourceExhausted(true, false, false, true));
		assertTrue(DockServicePolicy.completionResourceExhausted(false, true, true, false));
		assertFalse(DockServicePolicy.completionResourceExhausted(true, true, true, true));
	}
}
