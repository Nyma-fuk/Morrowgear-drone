package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class CargoMissionPolicyTest {
	@Test
	void waitsAtAnEmptySourceAndDepartsAfterLoading() {
		assertEquals(CargoState.WAIT_SOURCE, CargoMissionPolicy.afterSource(false));
		assertEquals(CargoState.TO_TARGET, CargoMissionPolicy.afterSource(true));
	}

	@Test
	void retainsCargoAtAFullTargetAndReturnsOnlyAfterUnloading() {
		assertEquals(CargoState.WAIT_TARGET, CargoMissionPolicy.afterTarget(true));
		assertEquals(CargoState.TO_SOURCE, CargoMissionPolicy.afterTarget(false));
	}

	@Test
	void unknownSavedStateMigratesToUnassigned() {
		assertEquals(CargoState.UNASSIGNED, CargoState.byId(-1));
		assertEquals(CargoState.UNASSIGNED, CargoState.byId(99));
	}
}
