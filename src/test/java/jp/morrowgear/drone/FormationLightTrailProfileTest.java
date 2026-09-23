package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FormationLightTrailProfileTest {
	@Test
	void approvedRibbonPersistsForFiveSeconds() {
		assertEquals(100, FormationLightTrailProfile.LIFETIME_TICKS);
		assertEquals(1.0f, FormationLightTrailProfile.alpha(0), 0.0001f);
		assertTrue(FormationLightTrailProfile.alpha(70) < FormationLightTrailProfile.alpha(30));
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

	@Test
	void continuousFadeNeverBrightensOrPopsAtExpiry() {
		float previous = 1;
		for (float age = 0; age <= 101; age += .1f) {
			float alpha = FormationLightTrailProfile.alpha(age);
			assertTrue(alpha >= 0 && alpha <= previous);
			assertTrue(previous - alpha < .01f);
			previous = alpha;
		}
		assertEquals(0, FormationLightTrailProfile.alpha(Float.NaN));
		assertEquals(1, FormationLightTrailProfile.stopAlpha(0));
		assertEquals(.5f, FormationLightTrailProfile.stopAlpha(5), .0001);
		assertEquals(0, FormationLightTrailProfile.stopAlpha(10));
	}

	@Test
	void nonCombatStylesShareTheAirframeCyanWhileEntryUsesAmberRed() {
		for (var style : FormationTrailPolicy.TrailStyle.values()) {
			assertEquals(style == FormationTrailPolicy.TrailStyle.COMBAT_ENTRY ? 0xFF471D : 0x20D7DF,
				FormationLightTrailProfile.color(style));
		}
		assertTrue(FormationLightTrailProfile.CORE_WIDTH < .03f);
		assertTrue(FormationLightTrailProfile.HALO_WIDTH < .08f);
	}

	@Test
	void distanceLodBoundsFleetDrawCostAndFadesAtTheLimit() {
		assertEquals(1, FormationLightTrailProfile.sampleStride(8));
		assertEquals(2, FormationLightTrailProfile.sampleStride(48));
		assertEquals(4, FormationLightTrailProfile.sampleStride(80));
		assertEquals(1, FormationLightTrailProfile.distanceAlpha(72));
		assertEquals(.5f, FormationLightTrailProfile.distanceAlpha(84), .0001);
		assertEquals(0, FormationLightTrailProfile.distanceAlpha(96));
		int maxVertices = FormationLightTrailProfile.MAX_TRACKED_DRONES
			* (FormationLightTrailProfile.MAX_CONTROL_POINTS - 1) * 2 * 2 * 4;
		assertTrue(maxVertices <= 72704);
	}
}
