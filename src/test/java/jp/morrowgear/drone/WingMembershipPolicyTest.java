package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WingMembershipPolicyTest {
	@Test
	void eighthMemberCanJoinButNinthIsRejected() {
		assertEquals(WingMembershipPolicy.Result.ACCEPTED,
			WingMembershipPolicy.evaluate("ALPHA", "WING-A1", 7));
		assertEquals(WingMembershipPolicy.Result.WING_FULL,
			WingMembershipPolicy.evaluate("ALPHA", "WING-A1", 8));
	}

	@Test
	void sameWingIsIdempotentAndMalformedIdsAreRejected() {
		assertEquals(WingMembershipPolicy.Result.UNCHANGED,
			WingMembershipPolicy.evaluate("WING-A1", "WING-A1", 8));
		assertEquals(WingMembershipPolicy.Result.INVALID_WING,
			WingMembershipPolicy.evaluate("ALPHA", "ALPHA", 0));
	}
}
