package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class WingLeadershipPolicyTest {
	@Test
	void serviceAndCombatTemporarilyDetachWithoutRemovingWingMembership() {
		assertTrue(WingLeadershipPolicy.temporarilyDetached(true, false, false));
		assertTrue(WingLeadershipPolicy.temporarilyDetached(false, true, false));
		assertTrue(WingLeadershipPolicy.temporarilyDetached(false, false, true));
		assertFalse(WingLeadershipPolicy.temporarilyDetached(false, false, false));
	}

	@Test
	void successorPrefersHealthyUnitClosestToCurrentMissionObjective() {
		List<WingLeadershipPolicy.Candidate> candidates = List.of(
			candidate(true, 0, 1.0, 90, 0, "leader"),
			candidate(false, 0, 25.0, 80, 2, "rear"),
			candidate(false, 0, 9.0, 55, 1, "front"));
		assertEquals(2, WingLeadershipPolicy.successorIndex(candidates));
	}

	@Test
	void detachedAndRecoveringUnitsCannotDisplaceAvailableWingman() {
		List<WingLeadershipPolicy.Candidate> candidates = List.of(
			candidate(true, 0, 1.0, 100, 0, "detached"),
			candidate(false, 2, 2.0, 100, 1, "recovering"),
			candidate(false, 0, 30.0, 35, 2, "available"));
		assertEquals(2, WingLeadershipPolicy.successorIndex(candidates));
	}

	@Test
	void batteryAndStableRankBreakEquivalentNavigationTies() {
		List<WingLeadershipPolicy.Candidate> candidates = List.of(
			candidate(false, 0, 16.0, 40, 1, "low"),
			candidate(false, 0, 16.0, 80, 3, "high"));
		assertEquals(1, WingLeadershipPolicy.successorIndex(candidates));
	}

	@Test
	void noSuccessorExistsWhenEveryUnitIsTemporarilyDetached() {
		assertEquals(-1, WingLeadershipPolicy.successorIndex(List.of(
			candidate(true, 0, 1.0, 90, 0, "a"),
			candidate(true, 0, 2.0, 90, 1, "b"))));
	}

	@Test
	void returningFormerLeaderDoesNotDisplaceTheAvailableCurrentLeader() {
		List<WingLeadershipPolicy.Candidate> candidates = List.of(
			candidate(false, 0, 1.0, 100, 3, "former"),
			candidate(false, 0, 20.0, 70, 0, "current"));
		assertTrue(WingLeadershipPolicy.leaderAvailable(candidates, "current"));
		assertFalse(WingLeadershipPolicy.leaderAvailable(candidates, "missing"));
	}

	@Test
	void recoveringLeaderIsUnavailableEvenBeforeItIsRemovedFromTheWing() {
		List<WingLeadershipPolicy.Candidate> candidates = List.of(
			candidate(false, 2, 1.0, 100, 0, "recovering-leader"),
			candidate(false, 0, 9.0, 80, 1, "healthy"));
		assertFalse(WingLeadershipPolicy.leaderAvailable(candidates, "recovering-leader"));
		assertEquals(1, WingLeadershipPolicy.successorIndex(candidates));
	}

	private static WingLeadershipPolicy.Candidate candidate(boolean detached, int recovery,
		double distance, int battery, int rank, String id) {
		return new WingLeadershipPolicy.Candidate(detached, recovery, distance, battery, rank, id);
	}
}
