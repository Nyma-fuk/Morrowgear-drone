package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ThreatBandTest {
	@Test
	void mapsEveryBoundaryToTheExpectedBand() {
		assertEquals(ThreatBand.LOW, ThreatBand.fromScore(0));
		assertEquals(ThreatBand.LOW, ThreatBand.fromScore(5));
		assertEquals(ThreatBand.GUARDED, ThreatBand.fromScore(6));
		assertEquals(ThreatBand.GUARDED, ThreatBand.fromScore(13));
		assertEquals(ThreatBand.HIGH, ThreatBand.fromScore(14));
		assertEquals(ThreatBand.HIGH, ThreatBand.fromScore(27));
		assertEquals(ThreatBand.CRITICAL, ThreatBand.fromScore(28));
	}
}
