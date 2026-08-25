package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SolarMissionPolicyTest {
	@Test
	void missionThresholdsPreserveIncreasingSafetyMargin() {
		assertEquals(32, SolarMissionPolicy.diversionThreshold(false, false, false, 1800));
		int route = SolarMissionPolicy.diversionThreshold(true, false, false, 1800);
		int outbound = SolarMissionPolicy.diversionThreshold(false, true, false, 1800);
		int towing = SolarMissionPolicy.diversionThreshold(false, false, true, 1800);
		assertTrue(route > 32);
		assertTrue(outbound > route);
		assertTrue(towing > outbound);
	}

	@Test
	void ultraLongSalvageChargesForBothLegsAndTheSuspendedLoad() {
		assertEquals(57, SolarMissionPolicy.diversionThreshold(false, true, false, 1800));
		assertEquals(68, SolarMissionPolicy.diversionThreshold(false, false, true, 1800));
		assertEquals(92, SolarMissionPolicy.chargeTarget(false, true, false));
		assertEquals(95, SolarMissionPolicy.chargeTarget(false, false, true));
	}

	@Test
	void routeChargeTargetDoesNotOverwriteSalvageTargets() {
		assertEquals(85, SolarMissionPolicy.chargeTarget(false, false, false));
		assertEquals(90, SolarMissionPolicy.chargeTarget(true, false, false));
		assertEquals(92, SolarMissionPolicy.chargeTarget(true, true, false));
		assertEquals(95, SolarMissionPolicy.chargeTarget(true, true, true));
	}

	@Test
	void solarDiversionSuppressesOnlyTheDuplicateFlightPowerReturn() {
		assertFalse(SolarMissionPolicy.baseReturnRequired(DroneServicePolicy.Need.FLIGHT_POWER, true));
		assertTrue(SolarMissionPolicy.baseReturnRequired(DroneServicePolicy.Need.DAMAGE, true));
		assertTrue(SolarMissionPolicy.baseReturnRequired(DroneServicePolicy.Need.WEAPON_POWER, true));
		assertTrue(SolarMissionPolicy.baseReturnRequired(DroneServicePolicy.Need.FLIGHT_POWER, false));
	}

	@Test
	void routeStateIsFrozenOnlyDuringTheTemporaryDiversion() {
		assertTrue(SolarMissionPolicy.freezeMissionProgress(true));
		assertFalse(SolarMissionPolicy.freezeMissionProgress(false));
	}
}
