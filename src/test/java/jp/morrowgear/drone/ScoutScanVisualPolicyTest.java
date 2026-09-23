package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class ScoutScanVisualPolicyTest {
	@Test
	void onlyActualPoweredAirborneScoutsQualify() {
		for (DroneRole role : DroneRole.values()) {
			assertEquals(role == DroneRole.SCOUT, ScoutScanVisualPolicy.eligible(role, true, false, false, true));
		}
		assertFalse(ScoutScanVisualPolicy.eligible(null, true, false, false, true));
		assertFalse(ScoutScanVisualPolicy.eligible(DroneRole.SCOUT, false, false, false, true));
		assertFalse(ScoutScanVisualPolicy.eligible(DroneRole.SCOUT, true, true, false, true));
		assertFalse(ScoutScanVisualPolicy.eligible(DroneRole.SCOUT, true, false, true, true));
		assertFalse(ScoutScanVisualPolicy.eligible(DroneRole.SCOUT, true, false, false, false));
	}

	@Test
	void wideFanCompletesAFullRevolutionWithoutEnclosingTheEntireView() {
		assertEquals(0, ScoutScanVisualPolicy.sweepAngle(0), 1e-9);
		assertEquals(Math.PI, ScoutScanVisualPolicy.sweepAngle(50), 1e-9);
		assertEquals(0, ScoutScanVisualPolicy.sweepAngle(100), 1e-9);
		assertEquals(ScoutScanVisualPolicy.sweepAngle(12.5), ScoutScanVisualPolicy.sweepAngle(112.5), 1e-9);
		assertEquals(Math.toRadians(78), ScoutScanVisualPolicy.FAN_RADIANS);
		assertTrue(ScoutScanVisualPolicy.CURTAIN_ALPHA < 40);
		assertTrue(ScoutScanVisualPolicy.EDGE_ALPHA >= 180);
		assertEquals(ScoutScanVisualPolicy.FAN_RADIANS,
			ScoutScanVisualPolicy.rayAngle(40, 8, 9) - ScoutScanVisualPolicy.rayAngle(40, 0, 9), 1e-9);
	}

	@Test
	void distanceLodReducesRaysCadenceAndOpacityWithAHardRangeLimit() {
		assertEquals(9, ScoutScanVisualPolicy.rayCount(8));
		assertEquals(6, ScoutScanVisualPolicy.rayCount(24));
		assertEquals(4, ScoutScanVisualPolicy.rayCount(48));
		assertEquals(4, ScoutScanVisualPolicy.rayCount(96));
		assertEquals(0, ScoutScanVisualPolicy.rayCount(112));
		assertEquals(0, ScoutScanVisualPolicy.rayCount(Double.NaN));
		assertEquals(0, ScoutScanVisualPolicy.rayCount(Double.POSITIVE_INFINITY));
		assertEquals(1, ScoutScanVisualPolicy.refreshInterval(8));
		assertEquals(2, ScoutScanVisualPolicy.refreshInterval(32));
		assertEquals(4, ScoutScanVisualPolicy.refreshInterval(48));
		assertEquals(.5f, ScoutScanVisualPolicy.distanceAlpha(78), .0001);
		assertEquals(0, ScoutScanVisualPolicy.distanceAlpha(112));
	}

	@Test
	void cacheCadenceIsTickBasedAndNeverFrameBased() {
		for (int frame = 0; frame < 1000; frame++) assertFalse(ScoutScanVisualPolicy.shouldRefresh(100, 100, 8));
		assertTrue(ScoutScanVisualPolicy.shouldRefresh(101, 100, 8));
		assertFalse(ScoutScanVisualPolicy.shouldRefresh(102, 100, 48));
		assertTrue(ScoutScanVisualPolicy.shouldRefresh(104, 100, 48));
		assertTrue(ScoutScanVisualPolicy.shouldRefresh(1, 100, 48));
		assertTrue(ScoutScanVisualPolicy.shouldRefresh(0, Long.MIN_VALUE, 48));
	}

	@Test
	void fastMotionRefreshesDistantTerrainButNeverCastsAgainWithinTheSameTick() {
		assertFalse(ScoutScanVisualPolicy.shouldRefresh(100, 100, 48, 2));
		assertFalse(ScoutScanVisualPolicy.shouldRefresh(101, 100, 48, .3));
		assertTrue(ScoutScanVisualPolicy.shouldRefresh(101, 100, 48, .75));
		var budget = new ScoutScanVisualPolicy.TickBudget();
		assertTrue(budget.reserve(100, 9));
		budget.reset();
		assertEquals(0, budget.spent());
		assertTrue(budget.reserve(100, 9));
	}

	@Test
	void fleetRayBudgetCannotBecomeOneHundredByOneHundredRays() {
		var budget = new ScoutScanVisualPolicy.TickBudget();
		int accepted = 0;
		for (int i = 0; i < 100; i++) if (budget.reserve(10, 9)) accepted++;
		assertEquals(24, accepted);
		assertEquals(216, budget.spent());
		assertFalse(budget.reserve(10, 4));
		assertTrue(budget.reserve(11, 4));
		assertEquals(4, budget.spent());
		assertFalse(budget.reserve(11, 100));
		assertFalse(budget.reserve(11, 0));
		assertTrue(ScoutScanVisualPolicy.MAX_MOB_BOUNDS <= 16);
	}

	@Test
	void wallsClipBeforeMobsAndCurtainDoesNotBridgeCliffsOrEntityHits() {
		assertEquals(4, ScoutScanVisualPolicy.clippedLength(4, 12));
		assertEquals(2, ScoutScanVisualPolicy.clippedLength(4, 2));
		assertEquals(48, ScoutScanVisualPolicy.clippedLength(64, Double.POSITIVE_INFINITY));
		assertTrue(ScoutScanVisualPolicy.joinCurtain(8, 8.2, false, false));
		assertFalse(ScoutScanVisualPolicy.joinCurtain(8, 12, false, false));
		assertFalse(ScoutScanVisualPolicy.joinCurtain(8, 8.2, true, false));
		assertFalse(ScoutScanVisualPolicy.joinCurtain(8, 8.2, false, true));
		assertFalse(ScoutScanVisualPolicy.joinCurtain(.1, .2, false, false));
	}
}
