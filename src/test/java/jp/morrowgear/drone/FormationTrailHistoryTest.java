package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import static jp.morrowgear.drone.FormationTrailPolicy.TrailStyle.*;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class FormationTrailHistoryTest {
	private static void tick(FormationTrailHistory history, FormationTrailPolicy.TrailStyle style, long tick) {
		tick(history, style, tick, tick * .2, true, 0);
	}

	private static void tick(FormationTrailHistory history, FormationTrailPolicy.TrailStyle style,
		long tick, double z, boolean moving, int delay) {
		Vec3 position = new Vec3(0, 10, z);
		history.tick(style, position, position.add(-.19, .402, -1.02), position.add(.19, .402, -1.02), tick, moving, delay);
	}

	@Test
	void activeStyleSwitchPreservesOldPointsAndBlendsOnlyNewExhaust() {
		var history = new FormationTrailHistory();
		for (int tick = 0; tick < 10; tick++) tick(history, NAVIGATION, tick);
		var original = history.points().getFirst();
		tick(history, COMBAT_ENTRY, 10);
		assertEquals(11, history.points().size());
		assertEquals(original, history.points().getFirst());
		assertEquals(FormationLightTrailProfile.CYAN, history.points().getLast().color());
		for (int tick = 11; tick <= 18; tick++) tick(history, COMBAT_ENTRY, tick);
		assertEquals(FormationLightTrailProfile.COMBAT_AMBER_RED, history.points().getLast().color());
		assertNotEquals(original.color(), history.points().get(14).color());
		assertEquals(original.strip(), history.points().getLast().strip());
	}

	@Test
	void closeOrbitStopsEmissionButExistingRibbonFadesWithoutRebrighteningOnResume() {
		var history = new FormationTrailHistory();
		for (int tick = 0; tick < 5; tick++) tick(history, COMBAT_ENTRY, tick);
		tick(history, NONE, 5);
		assertEquals(5, history.points().size());
		assertFalse(history.emitting());
		var retired = history.points().getLast();
		assertTrue(retired.alpha(5) > .9f);
		assertTrue(retired.alpha(10) < .5f);
		tick(history, NAVIGATION, 7);
		assertNotEquals(retired.strip(), history.points().getLast().strip());
		assertEquals(retired, history.points().get(4));
		assertEquals(0, retired.alpha(15));
	}

	@Test
	void stopAndRestartNeverConnectsAcrossTheInactiveGap() {
		var history = new FormationTrailHistory();
		tick(history, NAVIGATION, 0);
		tick(history, NAVIGATION, 1);
		int oldStrip = history.points().getLast().strip();
		tick(history, NAVIGATION, 2, .2, false, 0);
		tick(history, NAVIGATION, 3, .8, true, 0);
		assertNotEquals(oldStrip, history.points().getLast().strip());
		assertTrue(history.points().getFirst().stoppedAt() < Long.MAX_VALUE);
	}

	@Test
	void teleportAndClockRewindClearRatherThanDrawingLongConnectingLines() {
		var history = new FormationTrailHistory();
		tick(history, NAVIGATION, 0);
		tick(history, NAVIGATION, 1);
		tick(history, NAVIGATION, 2, 32, true, 0);
		assertEquals(1, history.points().size());
		tick(history, NAVIGATION, 1, 32.2, true, 0);
		assertEquals(1, history.points().size());
	}

	@Test
	void repeatedFramesDoNotSampleOrAdvanceActivation() {
		var history = new FormationTrailHistory();
		for (int i = 0; i < 100; i++) tick(history, NAVIGATION, 0, 0, true, 2);
		assertFalse(history.hasPoints());
		tick(history, NAVIGATION, 1, .2, true, 2);
		assertFalse(history.hasPoints());
		tick(history, NAVIGATION, 2, .4, true, 2);
		assertEquals(1, history.points().size());
	}

	@Test
	void historyAndStoppedLifetimeStayBoundedUnderLongContinuousFlight() {
		var history = new FormationTrailHistory();
		for (int tick = 0; tick < 10000; tick++) {
			tick(history, NAVIGATION, tick);
			assertTrue(history.points().size() <= FormationLightTrailProfile.MAX_CONTROL_POINTS);
		}
		tick(history, NONE, 10000);
		assertTrue(history.hasPoints());
		tick(history, NONE, 10010);
		assertFalse(history.hasPoints());
	}

	@Test
	void pairedSamplesPreserveTheApprovedNozzleSpacingAndAftAnchor() {
		var history = new FormationTrailHistory();
		tick(history, NAVIGATION, 0);
		var point = history.points().getFirst();
		assertEquals(.38, point.left().distanceTo(point.right()), 1e-9);
		assertEquals(10.402, point.left().y, 1e-9);
		assertEquals(-1.02, point.left().z, 1e-9);
	}
}
