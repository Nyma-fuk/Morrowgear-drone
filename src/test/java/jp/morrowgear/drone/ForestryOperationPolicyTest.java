package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ForestryOperationPolicyTest {
	@Test void higherVegetationAlwaysPrecedesLowerTrunk() {
		assertTrue(ForestryOperationPolicy.priority(92, false)
			< ForestryOperationPolicy.priority(64, true));
	}

	@Test void foliagePrecedesLogAtTheSameHeight() {
		assertTrue(ForestryOperationPolicy.priority(80, false)
			< ForestryOperationPolicy.priority(80, true));
	}

	@Test void surveyStartsOnlyAfterTheAircraftReachesTheOverheadEnvelope() {
		assertTrue(ForestryOperationPolicy.surveyReady(12 * 12, 20));
		assertFalse(ForestryOperationPolicy.surveyReady(64 * 64, 20));
	}
}
