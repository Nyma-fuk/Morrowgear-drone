package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecoveryPolicyTest {
	@Test
	void repeatedStallsEscalateButRemainBounded() {
		assertEquals(1, RecoveryPolicy.nextLevel(0));
		assertEquals(2, RecoveryPolicy.nextLevel(1));
		assertEquals(3, RecoveryPolicy.nextLevel(2));
		assertEquals(3, RecoveryPolicy.nextLevel(3));
	}

	@Test
	void strongerRecoveryGetsMoreTime() {
		assertTrue(RecoveryPolicy.escapeDuration(3, false) > RecoveryPolicy.escapeDuration(1, false));
		assertEquals(45, RecoveryPolicy.escapeDuration(1, true));
	}

	@Test
	void onlyClearProgressResetsRecovery() {
		assertTrue(!RecoveryPolicy.stableProgress(0.9));
		assertTrue(RecoveryPolicy.stableProgress(1.2));
	}
}
