package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class NavigationWorkPolicyTest {
	@Test void preciseCollisionWorkIsIndependentOfMissionDistance() {
		assertTrue(NavigationWorkPolicy.corridorSamples(24) > 0);
		assertTrue(NavigationWorkPolicy.corridorSamples(96) <= NavigationWorkPolicy.MAX_CORRIDOR_SAMPLES);
		assertEquals(-1, NavigationWorkPolicy.corridorSamples(96.01));
		assertEquals(-1, NavigationWorkPolicy.corridorSamples(1_000));
	}

	@Test void strategicPlanningHasAHardCollisionProbeCeiling() {
		assertEquals(96, NavigationWorkPolicy.strategicProbeCount(397));
		assertEquals(24, NavigationWorkPolicy.strategicProbeCount(24));
		long worstCaseSamples = (long)NavigationWorkPolicy.MAX_STRATEGIC_PROBES
			* NavigationWorkPolicy.MAX_CORRIDOR_SAMPLES * 2L;
		assertTrue(worstCaseSamples <= 21_000, "strategic samples=" + worstCaseSamples);
	}

	@Test void boundedStrategicPlanningSamplesNearAndExtremeDetours() {
		assertEquals(0, NavigationWorkPolicy.strategicProbeIndex(0, 397));
		assertEquals(47, NavigationWorkPolicy.strategicProbeIndex(47, 397));
		assertEquals(48, NavigationWorkPolicy.strategicProbeIndex(48, 397));
		assertEquals(396, NavigationWorkPolicy.strategicProbeIndex(95, 397));
		int previous = -1;
		for (int probe = 0; probe < NavigationWorkPolicy.strategicProbeCount(397); probe++) {
			int index = NavigationWorkPolicy.strategicProbeIndex(probe, 397);
			assertTrue(index > previous, "duplicate or reversed strategic probe at " + probe);
			previous = index;
		}
	}

	@Test void boundaryBreakersStillFitInsideTheStrategicBudget() {
		int guaranteedOuterCandidates = 2 * 3 * 2 * 2;
		assertTrue(guaranteedOuterCandidates <= NavigationWorkPolicy.MAX_STRATEGIC_PROBES / 4);
	}

	@Test void longRoutesAreRepresentedByBoundedForwardSegments() {
		assertArrayEquals(new double[] {24.0, 48.0, 72.0}, NavigationWorkPolicy.FORWARD_SEGMENTS);
		assertTrue(NavigationWorkPolicy.FORWARD_SEGMENTS[2] < NavigationWorkPolicy.PRECISE_CORRIDOR_DISTANCE);
	}
}
