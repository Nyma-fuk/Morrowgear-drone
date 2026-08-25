package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class AdaptiveFollowFormationTest {
	@Test
	void largeFleetRingsDistributeAcrossTheirWholeCircumference() {
		var profile = AdaptiveFollowFormation.profile(30, 1.0, 11, true, false, false, 0.0);
		Vec3 heading = new Vec3(0, 0, 1);
		Vec3 first = AdaptiveFollowFormation.offset(profile, 8, 30, 0, heading, 0.0);
		Vec3 opposite = AdaptiveFollowFormation.offset(profile, 12, 30, 0, heading, 0.0);
		assertTrue(first.distanceTo(opposite) > profile.radius() * 1.8);
	}

	@Test
	void crampedLowCeilingChangesOrbitIntoCompactColumn() {
		AdaptiveFollowFormation.Profile cave = AdaptiveFollowFormation.profile(
			6, 0.25, 3, false, false, false, 0.05);
		AdaptiveFollowFormation.Profile open = AdaptiveFollowFormation.profile(
			6, 0.92, 11, true, false, false, 0.05);

		assertTrue(cave.column());
		assertFalse(open.column());
		assertTrue(cave.radius() < open.radius());
		assertTrue(cave.baseHeight() < open.baseHeight());
	}

	@Test
	void waterAndSpeedIncreaseSafetyHeightAndForwardSpacing() {
		AdaptiveFollowFormation.Profile calm = AdaptiveFollowFormation.profile(
			4, 0.9, 11, true, false, false, 0.0);
		AdaptiveFollowFormation.Profile exposed = AdaptiveFollowFormation.profile(
			4, 0.9, 11, true, true, true, 0.8);

		assertTrue(exposed.baseHeight() > calm.baseHeight());
		assertTrue(exposed.radius() > calm.radius());
		assertTrue(exposed.forwardBias() > calm.forwardBias());
		assertTrue(exposed.angularSpeed() < calm.angularSpeed());
	}

	@Test
	void compactColumnUsesDistinctThreeDimensionalSlots() {
		AdaptiveFollowFormation.Profile cave = AdaptiveFollowFormation.profile(
			6, 0.2, 3, false, false, false, 0.1);
		Vec3 heading = new Vec3(0, 0, 1);
		for (int first = 0; first < 6; first++) {
			for (int second = first + 1; second < 6; second++) {
				Vec3 a = AdaptiveFollowFormation.offset(cave, first, 6, 0, heading);
				Vec3 b = AdaptiveFollowFormation.offset(cave, second, 6, 0, heading);
				assertTrue(a.distanceTo(b) > 0.7, first + " and " + second + " overlap");
			}
		}
	}

	@Test
	void largeOpenFormationExpandsWithoutUnboundedRadius() {
		AdaptiveFollowFormation.Profile small = AdaptiveFollowFormation.profile(
			2, 1.0, 11, true, false, false, 0.0);
		AdaptiveFollowFormation.Profile large = AdaptiveFollowFormation.profile(
			18, 1.0, 11, true, false, false, 0.0);

		assertTrue(large.radius() > small.radius());
		assertTrue(large.radius() <= 7.4);
	}

	@Test
	void openFollowFormationTurnsFasterAndEntersLeaderFirst() {
		AdaptiveFollowFormation.Profile open = AdaptiveFollowFormation.profile(
			5, 1.0, 12, true, false, false, 0.0);
		Vec3 heading = new Vec3(0, 0, 1);
		Vec3 leader = AdaptiveFollowFormation.orbitEntryOffset(open, 0, 5, 0,
			heading, 0.0, 0.0);
		Vec3 firstFollower = AdaptiveFollowFormation.orbitEntryOffset(open, 1, 5, 0,
			heading, 0.0, 0.0);
		Vec3 secondFollower = AdaptiveFollowFormation.orbitEntryOffset(open, 2, 5, 0,
			heading, 0.0, 0.0);

		assertTrue(open.angularSpeed() >= 0.03);
		assertTrue(firstFollower.z < leader.z);
		assertTrue(secondFollower.z < firstFollower.z);
	}

	@Test
	void followOrbitEntryEndsAtTheNormalCircularSlots() {
		AdaptiveFollowFormation.Profile open = AdaptiveFollowFormation.profile(
			5, 1.0, 12, true, false, false, 0.0);
		Vec3 heading = new Vec3(0, 0, 1);
		for (int index = 0; index < 5; index++) {
			Vec3 entry = AdaptiveFollowFormation.orbitEntryOffset(open, index, 5, 80,
				heading, 1.0, 0.42);
			Vec3 orbit = AdaptiveFollowFormation.offset(open, index, 5, 80, heading, 0.42);
			assertTrue(entry.distanceTo(orbit) < 0.0001);
		}
	}
}
