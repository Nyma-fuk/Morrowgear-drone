package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class MissileTrailHistoryTest {
	@Test void smallCollisionBoxDoesNotCullNormalCombatRange() {
		assertTrue(MicroMissilePolicy.renderVisible(64 * 64, 1));
		assertTrue(MicroMissilePolicy.renderVisible(127 * 127, 1));
		assertFalse(MicroMissilePolicy.renderVisible(128 * 128, 1));
		assertTrue(MicroMissilePolicy.renderVisible(191 * 191, 2));
		assertFalse(MicroMissilePolicy.renderVisible(192 * 192, 100));
	}

	@Test void visualRangeRespectsReducedUserDistanceAndRejectsInvalidPositions() {
		assertTrue(MicroMissilePolicy.renderVisible(60 * 60, .5));
		assertFalse(MicroMissilePolicy.renderVisible(65 * 65, .5));
		assertFalse(MicroMissilePolicy.renderVisible(Double.NaN, 1));
		assertFalse(MicroMissilePolicy.renderVisible(Double.POSITIVE_INFINITY, 1));
		assertFalse(MicroMissilePolicy.renderVisible(-1, 1));
	}
	@Test void ejectionDoesNotDrawMotorExhaust() {
		MissileTrailHistory history = new MissileTrailHistory();
		for (int t = 0; t < 6; t++) history.tick(new Vec3(0, t * .3, 0), t, false);
		assertTrue(history.points().isEmpty());
		history.tick(new Vec3(0, 2, 0), 6, true);
		assertEquals(1, history.points().size());
	}

	@Test void historyFollowsTheCurvedFlightPathRatherThanTheCurrentHeading() {
		MissileTrailHistory history = new MissileTrailHistory();
		for (int t = 0; t < 12; t++) history.tick(new Vec3(Math.cos(t * .1) * 10, t, Math.sin(t * .1) * 10), t, true);
		assertEquals(12, history.points().size());
		assertEquals(new Vec3(10, 0, 0), history.points().getFirst().position());
		assertTrue(history.points().getLast().position().y > 0);
	}

	@Test void longFlightsCannotGrowTheHistoryWithoutBounds() {
		MissileTrailHistory history = new MissileTrailHistory();
		for (int t = 0; t < 10000; t++) {
			history.tick(new Vec3(t, 0, 0), t, true);
			assertTrue(history.points().size() <= MissileTrailHistory.MAX_POINTS);
			assertTrue(t - history.points().getFirst().tick() < MissileTrailHistory.LIFETIME_TICKS);
		}
	}

	@Test void impactFreezesOldExhaustAndFadesItWithoutDrawingAHostTrail() {
		MissileTrailHistory history = new MissileTrailHistory();
		for (int t = 0; t < 10; t++) history.tick(new Vec3(t, 0, 0), t, true);
		for (int t = 10; t < 28; t++) history.tick(new Vec3(t, 0, 0), t, false);
		assertTrue(history.points().isEmpty());
	}

	@Test void teleportPacketGapsAndRewoundClocksDoNotConnectUnrelatedPositions() {
		MissileTrailHistory history = new MissileTrailHistory();
		history.tick(Vec3.ZERO, 0, true);
		history.tick(new Vec3(100, 0, 0), 1, true);
		assertEquals(1, history.points().size());
		history.tick(new Vec3(101, 0, 0), 10, true);
		assertEquals(1, history.points().size());
		history.tick(new Vec3(102, 0, 0), 5, true);
		assertEquals(1, history.points().size());
	}

	@Test void duplicateTicksAndStationaryFramesDoNotAccumulate() {
		MissileTrailHistory history = new MissileTrailHistory();
		history.tick(Vec3.ZERO, 0, true);
		history.tick(Vec3.ZERO, 0, true);
		history.tick(Vec3.ZERO, 1, true);
		assertEquals(1, history.points().size());
		history.tick(new Vec3(Double.NaN, 0, 0), 2, true);
		assertTrue(history.points().isEmpty());
	}

	@Test void fadeIsMonotoneAndClamped() {
		var point = new MissileTrailHistory.Point(Vec3.ZERO, 100);
		assertEquals(1, point.alpha(99));
		float previous = 1;
		for (int t = 100; t < 120; t++) {
			assertTrue(point.alpha(t) <= previous && point.alpha(t) >= 0);
			previous = point.alpha(t);
		}
		assertEquals(0, point.alpha(118));
	}
}
