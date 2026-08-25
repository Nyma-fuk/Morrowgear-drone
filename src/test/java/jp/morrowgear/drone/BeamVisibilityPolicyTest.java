package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class BeamVisibilityPolicyTest {
	@Test
	void fullyVisibleBeamProducesOneCompleteSpan() {
		assertEquals(List.of(new BeamVisibilityPolicy.Range(0.0, 1.0)),
			BeamVisibilityPolicy.visibleRanges(4, sample -> true));
	}

	@Test
	void fullyOccludedBeamProducesNoVisibleSpan() {
		assertEquals(List.of(), BeamVisibilityPolicy.visibleRanges(4, sample -> false));
	}

	@Test
	void obstacleSplitsTheBeamWithoutDrawingThroughIt() {
		assertEquals(List.of(
			new BeamVisibilityPolicy.Range(0.0, 0.25),
			new BeamVisibilityPolicy.Range(0.75, 1.0)),
			BeamVisibilityPolicy.visibleRanges(4, sample -> sample != 2));
	}

	@Test
	void isolatedVisibleSampleDoesNotCreateAnInvalidZeroLengthRibbon() {
		assertEquals(List.of(), BeamVisibilityPolicy.visibleRanges(4, sample -> sample == 2));
	}

	@Test
	void invalidSamplingResolutionIsRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> BeamVisibilityPolicy.visibleRanges(0, sample -> true));
	}
}
