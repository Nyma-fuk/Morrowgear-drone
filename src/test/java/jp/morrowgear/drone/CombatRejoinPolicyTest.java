package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CombatRejoinPolicyTest {
	@Test
	void routeAndFollowMissionsResumeAfterTheTargetDisappears() {
		assertEquals(DroneMode.WAYPOINT, CombatRejoinPolicy.resumeMode(
			DroneMode.WAYPOINT.id(), true, true, false, true));
		assertEquals(DroneMode.FOLLOW, CombatRejoinPolicy.resumeMode(
			DroneMode.FOLLOW.id(), false, true, false, true));
	}

	@Test
	void dockLaunchedInterceptorReturnsToItsDock() {
		assertEquals(DroneMode.DOCK, CombatRejoinPolicy.resumeMode(
			DroneMode.STANDBY.id(), false, false, true, true));
	}

	@Test
	void missingMissionDataFallsBackToSafeStandby() {
		assertEquals(DroneMode.STANDBY, CombatRejoinPolicy.resumeMode(
			DroneMode.WAYPOINT.id(), false, false, false, true));
		assertEquals(DroneMode.STANDBY, CombatRejoinPolicy.resumeMode(
			DroneMode.FOLLOW.id(), false, false, false, true));
	}

	@Test
	void rejoinEndsOnPhysicalArrivalOrBoundedTimeout() {
		assertFalse(CombatRejoinPolicy.complete(20.0, 40));
		assertTrue(CombatRejoinPolicy.complete(CombatRejoinPolicy.COMPLETE_DISTANCE, 40));
		assertTrue(CombatRejoinPolicy.complete(20.0, CombatRejoinPolicy.MAX_REJOIN_TICKS));
	}
}
