package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TargetTrackingPolicyTest {
	@Test
	void invalidAndFriendlyTargetsCanBeObservedButNeverEngaged() {
		assertEquals(TargetDisposition.INVALID, classify(false, false, false, false, false,
			false, false, false));
		assertEquals(TargetDisposition.FRIENDLY, classify(true, false, false, true, false,
			true, true, true));
		assertEquals(TargetDisposition.FRIENDLY, classify(true, false, false, false, true,
			true, true, true));
		assertFalse(TargetDisposition.FRIENDLY.engageable());
	}

	@Test
	void neutralTrackingDoesNotSilentlyBecomeCombat() {
		assertEquals(TargetDisposition.NEUTRAL, classify(true, false, false, false, false,
			false, false, false));
		assertFalse(TargetDisposition.NEUTRAL.engageable());
	}

	@Test
	void hostileAndDirectThreatsAreSeparated() {
		assertEquals(TargetDisposition.HOSTILE, classify(true, false, false, false, false,
			true, false, false));
		assertEquals(TargetDisposition.DIRECT_THREAT, classify(true, false, false, false, false,
			false, true, false));
		assertEquals(TargetDisposition.DIRECT_THREAT, classify(true, false, false, false, false,
			false, false, true));
		assertTrue(TargetDisposition.HOSTILE.engageable());
		assertTrue(TargetDisposition.DIRECT_THREAT.engageable());
	}

	private static TargetDisposition classify(boolean alive, boolean self, boolean drone,
		boolean allied, boolean ownerControlled, boolean hostileType, boolean targetingOwner,
		boolean recentAttacker) {
		return TargetTrackingPolicy.classify(new TargetTrackingPolicy.Factors(alive, self, drone,
			allied, ownerControlled, hostileType, targetingOwner, recentAttacker));
	}
}
