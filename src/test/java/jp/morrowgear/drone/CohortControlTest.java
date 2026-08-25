package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CohortControlTest {
	@Test
	void largerCohortRetainsAuthorityWhenFormationsMerge() {
		assertEquals("alpha", CohortControl.dominantCohort(
			List.of("alpha", "alpha", "alpha", "bravo", "bravo")));
		assertEquals(3, CohortControl.retainedCohortSize(3, 2));
	}

	@Test
	void equalCohortsUseDeterministicAuthority() {
		assertEquals("alpha", CohortControl.dominantCohort(
			List.of("bravo", "alpha", "bravo", "alpha")));
	}

	@Test
	void cohortsOnlyMergeAtRegroupDistance() {
		assertTrue(CohortControl.shouldMerge(5, 2, 6.0));
		assertTrue(!CohortControl.shouldMerge(5, 2, 14.0));
	}

	@Test
	void nearestDestinationUnitBecomesRendezvousLeader() {
		assertEquals(1, CohortControl.destinationLeaderIndex(
			List.of(42.0, 12.0, 27.0), List.of("C", "B", "A")));
		assertEquals(2, CohortControl.destinationLeaderIndex(
			List.of(12.0, 12.0, 12.0), List.of("C", "B", "A")));
	}

	@Test
	void leaderAcceleratesOnlyAsFormationFills() {
		assertEquals(0.32, CohortControl.assemblySpeedLimit(0.72, 1, 4, 100.0), 0.001);
		assertEquals(0.40, CohortControl.assemblySpeedLimit(0.72, 2, 4, 100.0), 0.001);
		assertEquals(0.48, CohortControl.assemblySpeedLimit(0.72, 3, 4, 100.0), 0.001);
		assertEquals(0.72, CohortControl.assemblySpeedLimit(0.72, 4, 4, 100.0), 0.001);
		assertEquals(0.22, CohortControl.assemblySpeedLimit(0.72, 3, 4, 10.0), 0.001);
	}

	@Test
	void joiningAndCompletionUseExplicitThresholds() {
		assertTrue(CohortControl.readyToJoin(2.8));
		assertTrue(!CohortControl.readyToJoin(2.81));
		assertTrue(CohortControl.formationComplete(4, 4));
		assertTrue(!CohortControl.formationComplete(3, 4));
	}

	@Test
	void cohortOnlyReleasesWhenEveryJoinerOccupiesItsSlot() {
		assertTrue(CohortControl.synchronizedJoinReady(List.of(1.2, 2.4, 2.8)));
		assertTrue(!CohortControl.synchronizedJoinReady(List.of(1.2, 2.81, 2.0)));
		assertTrue(!CohortControl.synchronizedJoinReady(List.of()));
	}

	@Test
	void arrivedCohortCanPatrolWithoutWaitingForeverForRecovery() {
		assertTrue(CohortControl.patrolReleaseAllowed(1, 4, true, true));
		assertTrue(CohortControl.patrolReleaseAllowed(3, 5, true, false));
		assertTrue(!CohortControl.patrolReleaseAllowed(2, 5, true, false));
		assertTrue(!CohortControl.patrolReleaseAllowed(5, 5, false, false));
	}

	@Test
	void wingCannotReleaseItsLeaderWhileHealthyFollowersAreStillAssembling() {
		assertTrue(!CohortControl.wingReadyForPatrol(1, 8, true, true, false));
		assertTrue(CohortControl.wingReadyForPatrol(8, 8, true, false, false));
		assertTrue(CohortControl.wingReadyForPatrol(6, 8, true, false, true));
		assertTrue(!CohortControl.wingReadyForPatrol(4, 8, true, false, true));
		assertTrue(!CohortControl.wingReadyForPatrol(8, 8, false, false, false));
	}

	@Test
	void convergenceUsesMaximumSpeedFarAwayAndMatchesLeaderNearby() {
		assertEquals(1.08, CohortControl.convergenceSpeedLimit(0.96, 20.0, 0.44), 0.001);
		assertTrue(CohortControl.convergenceSpeedLimit(0.80, 12.0, 0.44) > 0.90);
		assertEquals(0.54, CohortControl.convergenceSpeedLimit(0.44, 2.5, 0.44), 0.001);
	}
}
