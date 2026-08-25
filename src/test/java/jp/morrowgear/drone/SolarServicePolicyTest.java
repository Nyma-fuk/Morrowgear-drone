package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SolarServicePolicyTest {
	private static final UUID SOLAR_A = UUID.randomUUID();

	@Test
	void solarDockNeverReplacesRepairOrWeaponService() {
		for (DroneServicePolicy.Need need : List.of(DroneServicePolicy.Need.DAMAGE,
			DroneServicePolicy.Need.WEAPON_POWER, DroneServicePolicy.Need.WEAPON_REARM,
			DroneServicePolicy.Need.AUTOCANNON_AMMO, DroneServicePolicy.Need.MISSILE_AMMO,
			DroneServicePolicy.Need.LASER_HEAT)) {
			SolarServicePolicy.ServicePlan plan = SolarServicePolicy.plan(need, 5, true, 500, 1000,
				List.of(candidate(SOLAR_A, 20, 0, 100, 0)));
			assertEquals(SolarServicePolicy.Destination.BASE_DOCK, plan.destination(), need.name());
			assertNull(plan.solarDockId());
		}
	}

	@Test
	void flightOnlyDiversionUsesReachableAvailableSolarStation() {
		SolarServicePolicy.ServicePlan plan = SolarServicePolicy.plan(DroneServicePolicy.Need.FLIGHT_POWER,
			18, true, 640, 900, List.of(candidate(SOLAR_A, 96, 24, 72, 1)));
		assertEquals(SolarServicePolicy.Destination.SOLAR_DOCK, plan.destination());
		assertEquals(SOLAR_A, plan.solarDockId());
	}

	@Test
	void fullOrDepletedStationCannotCaptureAnAircraft() {
		for (SolarServicePolicy.Candidate candidate : List.of(
			candidate(SOLAR_A, 32, 0, 100, 3), candidate(SOLAR_A, 32, 0, 7, 0))) {
			assertEquals(SolarServicePolicy.Destination.BASE_DOCK,
				SolarServicePolicy.plan(DroneServicePolicy.Need.FLIGHT_POWER, 20, true, 300, 600,
					List.of(candidate)).destination());
		}
	}

	@Test
	void nearestScoreBalancesDetourCapacityAndQueue() {
		UUID congested = UUID.randomUUID();
		UUID clear = UUID.randomUUID();
		SolarServicePolicy.ServicePlan plan = SolarServicePolicy.plan(DroneServicePolicy.Need.FLIGHT_POWER,
			15, false, 0, 700, List.of(candidate(congested, 40, 20, 30, 2),
				candidate(clear, 52, 4, 90, 0)));
		assertEquals(clear, plan.solarDockId());
	}

	@Test
	void chargingReleasesTheSharedSlotAtEightyFivePercent() {
		assertFalse(SolarServicePolicy.chargeComplete(84));
		assertTrue(SolarServicePolicy.chargeComplete(85));
		assertFalse(SolarServicePolicy.chargeComplete(91, 92));
		assertTrue(SolarServicePolicy.chargeComplete(92, 92));
	}

	@Test
	void missionAwareThresholdCanProtectALongSortieBeforeBaseReserveIsCritical() {
		SolarServicePolicy.ServicePlan plan = SolarServicePolicy.plan(DroneServicePolicy.Need.FLIGHT_POWER,
			52, true, 40, 1800, 57, List.of(candidate(SOLAR_A, 320, 8, 90, 0)));
		assertEquals(SolarServicePolicy.Destination.SOLAR_DOCK, plan.destination());
		assertEquals(SOLAR_A, plan.solarDockId());
	}

	private static SolarServicePolicy.Candidate candidate(UUID id, double distance, double detour,
		int energy, int occupied) {
		return new SolarServicePolicy.Candidate(id, distance, detour, energy, occupied, true);
	}
}
