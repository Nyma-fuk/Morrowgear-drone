package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class MissionFallbackPolicyTest {
	@Test
	void prefersLiveWingThenDynamicDockThenOwnerThenAutonomousOrbit() {
		assertEquals(MissionFallbackPolicy.Action.REJOIN_WING,
			MissionFallbackPolicy.choose(true, true, true));
		assertEquals(MissionFallbackPolicy.Action.REQUEST_DOCK,
			MissionFallbackPolicy.choose(false, true, true));
		assertEquals(MissionFallbackPolicy.Action.FOLLOW_OWNER,
			MissionFallbackPolicy.choose(false, false, true));
		assertEquals(MissionFallbackPolicy.Action.SAFE_ORBIT,
			MissionFallbackPolicy.choose(false, false, false));
	}
}
