package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class LaserFormationRegressionTest {
	@Test void laserLaneHardCapsInheritedEntrySpeed() {
		Vec3 current = new Vec3(0.5117385, 0, 0);
		Vec3 requested = FlightDynamics.steerLaserFormation(current, Vec3.ZERO,
			new Vec3(0, 8, 10), new Vec3(-0.32, 0, 0), 0.46);
		Vec3 smoothed = new FlightDynamics.Motion().step(current, requested, 1, false);
		assertTrue(smoothed.length() > 0.46,
			"the regression requires common smoothing to retain the entry overspeed");
		Vec3 actual = FlightDynamics.limitLaserVelocity(smoothed, 0.46);
		assertTrue(actual.length() <= 0.460000001, "laser speed=" + actual.length());
		assertTrue(actual.x > 0, "the cap must retain the inherited direction while decelerating");
	}

	@Test void temporaryFourLaserElementConvergesToEvenAngularSlots() {
		for (int startTick = 0; startTick < 200; startTick += 10) {
			assertTrue(simulateFourLaserElement(startTick) >= 40,
				"temporary laser element never sustained even spacing from world tick " + startTick);
		}
	}

	private static int simulateFourLaserElement(int startTick) {
		Vec3 center = new Vec3(.5, 1, .5);
		List<Vec3> positions = new ArrayList<>();
		List<Vec3> velocities = new ArrayList<>();
		List<FlightDynamics.Motion> motion = new ArrayList<>();
		for (int index = 0; index < 4; index++) {
			positions.add(new Vec3(-10 + index * 1.4, 3, -7));
			velocities.add(Vec3.ZERO);
			motion.add(new FlightDynamics.Motion());
		}
		int stableTicks = 0;
		int maximumStableTicks = 0;
		for (int elapsed = 0; elapsed < 220; elapsed++) {
			int tick = startTick + elapsed;
			int charge = Math.min(1000, elapsed * 20);
			for (int index = 0; index < 4; index++) {
				Vec3 slot = CombatPolicy.laserOrbit(center, index, 4, tick, charge);
				Vec3 nextSlot = CombatPolicy.laserOrbit(center, index, 4, tick + 1, charge);
				Vec3 velocity = motion.get(index).step(velocities.get(index),
					FlightDynamics.steerLaserFormation(velocities.get(index), positions.get(index),
						slot, nextSlot.subtract(slot), .46), tick, false);
				velocity = FlightDynamics.limitLaserVelocity(velocity, .46);
				positions.set(index, positions.get(index).add(velocity));
				velocities.set(index, velocity.scale(.91));
			}
			double error = angularGapError(positions, center);
			stableTicks = error <= .38 ? stableTicks + 1 : 0;
			maximumStableTicks = Math.max(maximumStableTicks, stableTicks);
		}
		return maximumStableTicks;
	}

	@Test void timeoutReleasesReadyMembersButNotTheAircraftStillOutsideItsSlot() {
		assertFalse(CombatPolicy.laserFallbackReleaseReady(60, 12, 5, true, false));
		assertFalse(CombatPolicy.laserFallbackReleaseReady(60, 12, 1.65, false, true));
		assertTrue(CombatPolicy.laserFallbackReleaseReady(60, 12, 1.65, true, false));
		assertTrue(CombatPolicy.laserFallbackReleaseReady(80, 36, 5, true, true));
	}

	@Test void coherentPhaseLagReleasesButClusteredAircraftDoNot() {
		Vec3 center = Vec3.ZERO;
		List<Vec3> evenlySpaced = List.of(
			new Vec3(8, 6, 0), new Vec3(0, 6, 8),
			new Vec3(-8, 6, 0), new Vec3(0, 6, -8));
		List<Vec3> clustered = List.of(
			new Vec3(8, 6, 0), new Vec3(8, 6, 1),
			new Vec3(7, 6, 2), new Vec3(6, 6, 3));
		assertTrue(CombatPolicy.laserFormationSpacingReady(evenlySpaced, center, .38));
		assertFalse(CombatPolicy.laserFormationSpacingReady(clustered, center, .38));
	}

	@Test void sharedTargetDoesNotMergeIndependentLaserGroups() {
		String channel = "|INTERCEPT:42|LASER";
		assertTrue(CombatPolicy.sameLaserFormation("WING-A" + channel, "WING-A" + channel));
		assertFalse(CombatPolicy.sameLaserFormation("WING-A" + channel, "WING-B" + channel));

		List<String> assigned = List.of("WING-A" + channel, "WING-A" + channel,
			"WING-B" + channel, "WING-B" + channel);
		for (String own : List.of("WING-A" + channel, "WING-B" + channel)) {
			int localCount = (int)assigned.stream()
				.filter(candidate -> CombatPolicy.sameLaserFormation(own, candidate)).count();
			assertEquals(2, localCount, "each airspace reservation must retain a local two-ship element");
			List<Vec3> localSlots = List.of(
				CombatPolicy.laserOrbit(Vec3.ZERO, 0, localCount, 80, 1000),
				CombatPolicy.laserOrbit(Vec3.ZERO, 1, localCount, 80, 1000));
			assertEquals(0.0, CombatPolicy.laserFormationGapError(localSlots, Vec3.ZERO), 1.0e-9);
		}
	}

	private static double angularGapError(List<Vec3> positions, Vec3 center) {
		return CombatPolicy.laserFormationGapError(positions, center);
	}
}
