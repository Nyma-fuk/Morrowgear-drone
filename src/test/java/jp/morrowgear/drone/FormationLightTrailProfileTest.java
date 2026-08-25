package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FormationLightTrailProfileTest {
	@Test
	void approvedRibbonPersistsForFiveSeconds() {
		assertEquals(100, FormationLightTrailProfile.LIFETIME_TICKS);
		assertEquals(1.0f, FormationLightTrailProfile.alpha(70), 0.0001f);
		assertTrue(FormationLightTrailProfile.alpha(85) > 0.0f);
		assertEquals(0.0f, FormationLightTrailProfile.alpha(100), 0.0001f);
	}

	@Test
	void samplingKeepsFastCurvesContinuousWithoutDuplicatingStationaryPoints() {
		assertTrue(FormationLightTrailProfile.shouldSample(0.13, 1));
		assertTrue(FormationLightTrailProfile.shouldSample(0.01, 2));
		assertFalse(FormationLightTrailProfile.shouldSample(0.01, 1));
	}

	@Test
	void historyIsBoundedForLargeFleets() {
		assertTrue(FormationLightTrailProfile.MAX_CONTROL_POINTS <= 72);
		assertTrue(FormationLightTrailProfile.MAX_CONTROL_POINTS >= 50);
	}

	@Test
	void followersRemainTrackedDuringTheirStaggeredActivationDelay() {
		assertTrue(FormationLightTrailProfile.retainState(true, true, false));
		assertTrue(FormationLightTrailProfile.retainState(true, false, true));
		assertFalse(FormationLightTrailProfile.retainState(true, false, false));
		assertFalse(FormationLightTrailProfile.retainState(false, true, true));
	}
}
