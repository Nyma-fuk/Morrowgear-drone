package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FormationTrailPolicyTest {
	@Test
	void onlyJoinedFormationMembersInCruiseEmit() {
		assertTrue(FormationTrailPolicy.activeFormationFlight(8, DroneEntity.MISSION_MOVING,
			false, false, 2, true, 0.2));
		assertFalse(FormationTrailPolicy.activeFormationFlight(8, DroneEntity.MISSION_ORBIT_ENTRY,
			false, false, 2, true, 0.2));
		assertFalse(FormationTrailPolicy.activeFormationFlight(8, DroneEntity.MISSION_ORBIT,
			false, false, 2, true, 0.2));
		assertTrue(FormationTrailPolicy.activeFormationFlight(8, DroneEntity.MISSION_CONVERGING,
			false, false, 2, true, 0.2));
		assertTrue(FormationTrailPolicy.activeFormationFlight(8, DroneEntity.MISSION_MOVING,
			false, true, 2, true, 0.2));
		assertFalse(FormationTrailPolicy.activeFormationFlight(1, DroneEntity.MISSION_MOVING,
			false, false, 0, true, 0.2));
	}

	@Test
	void catchUpKeepsTheAirshowTrailWhileTheMissionIsMoving() {
		assertTrue(FormationTrailPolicy.activeFormationFlight(8, DroneEntity.MISSION_MOVING,
			false, true, 7, true, 0.2));
		assertTrue(FormationTrailPolicy.activeFormationFlight(8, DroneEntity.MISSION_CONVERGING,
			false, true, 7, true, 0.2));
	}

	@Test
	void distributedConvergenceUsesTheSameWhiteTrailContract() {
		assertTrue(FormationTrailPolicy.activeFormationFlight(16, DroneEntity.MISSION_CONVERGING,
			false, false, 0, true, 0.12));
		assertTrue(FormationTrailPolicy.mayLinger(DroneEntity.MISSION_CONVERGING));
	}

	@Test
	void patrolTransitionHardStopsEvenTheExitTrail() {
		assertTrue(FormationTrailPolicy.mayLinger(DroneEntity.MISSION_MOVING));
		assertFalse(FormationTrailPolicy.mayLinger(DroneEntity.MISSION_ORBIT_ENTRY));
		assertFalse(FormationTrailPolicy.mayLinger(DroneEntity.MISSION_ORBIT));
	}

	@Test
	void smokeActivatesInCohortJoinOrder() {
		assertEquals(0, FormationTrailPolicy.activationDelay(0));
		assertEquals(2, FormationTrailPolicy.activationDelay(1));
		assertEquals(14, FormationTrailPolicy.activationDelay(7));
		assertEquals(18, FormationTrailPolicy.activationDelay(99));
	}

	@Test
	void largeFleetsAreThrottledAndExitTrailsAreSparser() {
		assertEquals(2, FormationTrailPolicy.emissionInterval(8, false));
		assertEquals(4, FormationTrailPolicy.emissionInterval(24, false));
		assertEquals(6, FormationTrailPolicy.emissionInterval(100, false));
		assertEquals(12, FormationTrailPolicy.emissionInterval(100, true));
	}

	@Test
	void detachedTrailEndsByTimeOrTravelDistance() {
		assertTrue(FormationTrailPolicy.continueLinger(30, 5.5));
		assertFalse(FormationTrailPolicy.continueLinger(31, 1.0));
		assertFalse(FormationTrailPolicy.continueLinger(10, 5.6));
	}

	@Test
	void multiPointPatrolAlwaysEmitsEvenForOneDrone() {
		assertTrue(FormationTrailPolicy.activeLoopRoute(2, false, 0.1));
		assertFalse(FormationTrailPolicy.activeLoopRoute(1, false, 0.1));
		assertFalse(FormationTrailPolicy.activeLoopRoute(4, true, 0.1));
	}

	@Test
	void visualStylesCommunicateTransitWithoutClutteringWorkAndCombatOrbits() {
		assertEquals(FormationTrailPolicy.TrailStyle.NAVIGATION, FormationTrailPolicy.style(
			DroneMode.WAYPOINT, CombatState.IDLE, 1, DroneEntity.MISSION_MOVING,
			false, false, false, 1, 0, false, false, false));
		assertEquals(FormationTrailPolicy.TrailStyle.CATCH_UP, FormationTrailPolicy.style(
			DroneMode.WAYPOINT, CombatState.IDLE, 8, DroneEntity.MISSION_CONVERGING,
			false, false, true, 0, 4, true, false, false));
		assertEquals(FormationTrailPolicy.TrailStyle.SERVICE_RETURN, FormationTrailPolicy.style(
			DroneMode.DOCK, CombatState.IDLE, 8, DroneEntity.MISSION_MOVING,
			false, true, false, 0, 2, true, false, false));
		assertEquals(FormationTrailPolicy.TrailStyle.REJOIN, FormationTrailPolicy.style(
			DroneMode.WAYPOINT, CombatState.REJOIN, 8, DroneEntity.MISSION_MOVING,
			false, false, false, 0, 7, true, false, false));
		assertEquals(FormationTrailPolicy.TrailStyle.COMBAT_ENTRY, FormationTrailPolicy.style(
			DroneMode.WAYPOINT, CombatState.FLARE_ENTRY, 8, DroneEntity.MISSION_MOVING,
			false, false, false, 0, 0, true, false, false));
		assertEquals(FormationTrailPolicy.TrailStyle.NONE, FormationTrailPolicy.style(
			DroneMode.WAYPOINT, CombatState.LASER_FIRE, 8, DroneEntity.MISSION_MOVING,
			false, false, false, 0, 0, true, false, false));
		assertEquals(FormationTrailPolicy.TrailStyle.NONE, FormationTrailPolicy.style(
			DroneMode.STANDBY, CombatState.IDLE, 1, DroneEntity.MISSION_ORBIT,
			false, false, false, 0, -1, false, true, false));
	}
}
