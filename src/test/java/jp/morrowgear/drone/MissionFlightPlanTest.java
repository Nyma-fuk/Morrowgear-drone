package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class MissionFlightPlanTest {
	@Test
	void observationPointStaysSafelyAboveTerrainOrWaterSurface() {
		assertEquals(72.0, MissionFlightPlan.observationAltitude(64), 0.001);
		assertEquals(-12.0, MissionFlightPlan.observationAltitude(-20), 0.001);
	}
}
