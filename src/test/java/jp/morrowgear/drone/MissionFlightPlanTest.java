package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class MissionFlightPlanTest {
	@Test
	void observationPointStaysSafelyAboveTerrainOrWaterSurface() {
		assertEquals(66.4, MissionFlightPlan.observationAltitude(64), 0.001);
		assertEquals(-17.6, MissionFlightPlan.observationAltitude(-20), 0.001);
	}
}
