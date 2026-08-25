package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FollowLeadershipPolicyTest {
	@Test
	void briefRecoveryDoesNotCauseLeadershipThrashing() {
		assertTrue(!FollowLeadershipPolicy.leaseExpired(1, 239));
		assertTrue(!FollowLeadershipPolicy.leaseExpired(3, 99));
	}

	@Test
	void prolongedOrLevelThreeRecoveryExpiresLeadershipLease() {
		assertTrue(FollowLeadershipPolicy.leaseExpired(1, 240));
		assertTrue(FollowLeadershipPolicy.leaseExpired(3, 100));
	}

	@Test
	void successorPrefersNearestHealthyUnitThenBatteryAndId() {
		List<FollowLeadershipPolicy.Candidate> candidates = List.of(
			new FollowLeadershipPolicy.Candidate(3, 1.0, 100, "A"),
			new FollowLeadershipPolicy.Candidate(0, 9.0, 70, "D"),
			new FollowLeadershipPolicy.Candidate(0, 9.0, 90, "C"),
			new FollowLeadershipPolicy.Candidate(0, 9.0, 90, "B"));
		assertEquals(3, FollowLeadershipPolicy.successorIndex(candidates));
	}

	@Test
	void noHealthyUnitMeansNoTransfer() {
		assertEquals(-1, FollowLeadershipPolicy.successorIndex(List.of(
			new FollowLeadershipPolicy.Candidate(1, 1.0, 100, "A"))));
	}

	@Test
	void removalAlwaysElectsTheLeastImpairedRemainingUnit() {
		List<FollowLeadershipPolicy.Candidate> candidates = List.of(
			new FollowLeadershipPolicy.Candidate(3, 1.0, 100, "A"),
			new FollowLeadershipPolicy.Candidate(1, 12.0, 60, "C"),
			new FollowLeadershipPolicy.Candidate(1, 6.0, 80, "B"));
		assertEquals(2, FollowLeadershipPolicy.removalSuccessorIndex(candidates));
	}
}
