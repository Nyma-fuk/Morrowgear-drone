package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SwarmFormationTest {
	@Test
	void choosesFormationFromFleetSize() {
		assertEquals(SwarmFormation.Pattern.SOLO, SwarmFormation.patternFor(1));
		assertEquals(SwarmFormation.Pattern.COLUMN, SwarmFormation.patternFor(2));
		assertEquals(SwarmFormation.Pattern.DELTA, SwarmFormation.patternFor(6));
		assertEquals(SwarmFormation.Pattern.DELTA, SwarmFormation.patternFor(7));
		assertEquals(SwarmFormation.Pattern.STAR, SwarmFormation.patternFor(9));
	}

	@Test
	void orbitRadiusGrowsForLargeFleet() {
		assertEquals(5.4, SwarmFormation.orbitRadius(3), 0.001);
		assertTrue(SwarmFormation.orbitRadius(16) > SwarmFormation.orbitRadius(8));
	}

	@Test
	void layeredWingOrbitsFormAnUprightCone() {
		double bottom = SwarmFormation.layeredOrbitRadius(8, 0, 13);
		double middle = SwarmFormation.layeredOrbitRadius(8, 6, 13);
		double top = SwarmFormation.layeredOrbitRadius(4, 12, 13);

		assertTrue(bottom > middle);
		assertTrue(middle > top);
		assertEquals(SwarmFormation.orbitRadius(4), top, 0.001);
		assertTrue(bottom - top <= 18.001);
		assertTrue(bottom - top > 15.0);
	}

	@Test
	void layeredOrbitsUseClearlyDifferentSpeedsAndPhases() {
		double lower = SwarmFormation.layeredAngularSpeed(0.038, 0, 13);
		double upper = SwarmFormation.layeredAngularSpeed(0.038, 12, 13);
		assertTrue(lower < 0.038);
		assertTrue(upper > 0.038);
		assertTrue(upper > lower * 1.4);
		assertTrue(SwarmFormation.layeredPhaseOffset(0.25, 1) != 0.25);
	}

	@Test
	void singleWingKeepsItsEstablishedOrbitRadius() {
		assertEquals(SwarmFormation.orbitRadius(8),
			SwarmFormation.layeredOrbitRadius(8, 0, 1), 0.001);
	}

	@Test
	void largeFleetIsPartitionedIntoBoundedWings() {
		assertTrue(!SwarmFormation.hierarchical(12));
		assertTrue(SwarmFormation.hierarchical(13));
		assertEquals(0, SwarmFormation.wingIndex(7));
		assertEquals(1, SwarmFormation.wingIndex(8));
		assertEquals(8, SwarmFormation.wingSize(30, 16));
		assertEquals(6, SwarmFormation.wingSize(30, 24));
		assertEquals(4, SwarmFormation.wingCount(30));
		assertEquals(1, SwarmFormation.wingLocalIndex(9));
	}

	@Test
	void wingAnchorsAreSeparatedInThreeDimensions() {
		Vec3 forward = new Vec3(0, 0, 1);
		Vec3 first = SwarmFormation.wingTravelOffset(0, 4, forward, false);
		Vec3 second = SwarmFormation.wingTravelOffset(1, 4, forward, false);
		Vec3 fourth = SwarmFormation.wingTravelOffset(3, 4, forward, false);
		assertTrue(first.distanceTo(second) > 8.0);
		assertTrue(second.y > first.y);
		assertTrue(fourth.z < first.z);
		assertEquals(11.4, SwarmFormation.wingOrbitCenter(Vec3.ZERO, 3).y, 0.001);
		assertTrue(SwarmFormation.wingArrivalRadius(30) > 25.0);
	}

	@Test
	void legitimateLargeFormationIsNotClassifiedAsDispersed() {
		double diameter = SwarmFormation.formationDiameter(30);
		assertTrue(!SwarmFormation.convergenceRequired(diameter, 30));
		assertTrue(SwarmFormation.convergenceRequired(diameter + 8.0, 30));
	}

	@Test
	void formationSlotsRemainSeparated() {
		Vec3 forward = new Vec3(0, 0, 1);
		for (int count : new int[] {2, 5, 9}) {
			for (int first = 0; first < count; first++) {
				for (int second = first + 1; second < count; second++) {
					Vec3 a = SwarmFormation.movingOffset(first, count, forward);
					Vec3 b = SwarmFormation.movingOffset(second, count, forward);
					assertTrue(a.distanceTo(b) > 1.5, count + " units: slots " + first + " and " + second);
				}
			}
		}
	}

	@Test
	void undergroundFormationUsesACompactThreeDimensionalColumn() {
		Vec3 forward = new Vec3(1, -0.25, 1).normalize();
		Vec3 first = SwarmFormation.movingOffset(1, 6, forward, true);
		Vec3 third = SwarmFormation.movingOffset(3, 6, forward, true);

		assertTrue(first.dot(forward) < 0);
		assertTrue(third.dot(forward) < first.dot(forward));
		assertTrue(Math.abs(first.x) < 3.0 && Math.abs(first.z) < 3.0);
	}

	@Test
	void waitsForFullMusterThenDegradesGracefully() {
		assertTrue(!SwarmFormation.canProceedWithAvailableUnits(true, 5, 4, 299));
		assertTrue(SwarmFormation.canProceedWithAvailableUnits(true, 5, 4, 300));
		assertTrue(SwarmFormation.canProceedWithAvailableUnits(false, 5, 4, 1));
		assertTrue(SwarmFormation.canProceedWithAvailableUnits(true, 5, 5, 1));
	}

	@Test
	void missionLeaderRotatesInsteadOfAlwaysUsingTheFirstUnit() {
		Set<Integer> leaders = new HashSet<>();
		for (int mission = 0; mission < 20; mission++) {
			leaders.add(SwarmFormation.leaderIndex("mission-" + mission, 5));
		}
		assertTrue(leaders.size() > 1);
		assertTrue(leaders.stream().anyMatch(index -> index != 0));
	}

	@Test
	void electedLeaderAlwaysOccupiesFormationSlotZero() {
		for (int leader = 0; leader < 5; leader++) {
			assertEquals(0, SwarmFormation.formationIndex(leader, leader, 5));
		}
	}

	@Test
	void plannedFormationKeepsReservedSlotsWhenSomeUnitsAreMissing() {
		assertEquals(6, SwarmFormation.plannedFormationSize(4, List.of(0, 2, 5)));
		assertEquals(2, SwarmFormation.formationIndex(4, 2, 6));
		assertEquals(4, SwarmFormation.formationIndex(0, 2, 6));
	}

	@Test
	void reassignedAdvanceUnitDoesNotReturnToAFormationSlotBehindIt() {
		Vec3 current = new Vec3(70, 12, 0);
		Vec3 destination = new Vec3(120, 12, 0);
		Vec3 slotBehind = new Vec3(20, 12, 0);

		Vec3 target = SwarmFormation.nonRegressiveMergeTarget(current, destination, slotBehind);

		assertTrue(target.x > current.x);
		assertTrue(target.distanceTo(destination) < current.distanceTo(destination));
	}

	@Test
	void availableForwardFormationSlotIsKept() {
		Vec3 current = new Vec3(20, 12, 0);
		Vec3 destination = new Vec3(120, 12, 0);
		Vec3 slotAhead = new Vec3(35, 13, 4);

		assertEquals(slotAhead, SwarmFormation.nonRegressiveMergeTarget(current, destination, slotAhead));
	}

	@Test
	void dispersedUnitsUseRendezvousUntilFleetIsCompact() {
		assertTrue(SwarmFormation.convergenceRequired(40.0, 7));
		assertTrue(!SwarmFormation.convergenceComplete(14.0, 7));
		assertTrue(SwarmFormation.convergenceComplete(10.0, 7));
	}

	@Test
	void cohesiveMajorityCanFormWithoutWaitingForOneStraggler() {
		List<Vec3> positions = List.of(
			new Vec3(0, 0, 0), new Vec3(2, 0, 0), new Vec3(4, 0, 0),
			new Vec3(0, 0, 3), new Vec3(2, 0, 3), new Vec3(4, 0, 3),
			new Vec3(45, 0, 0));

		List<Integer> cluster = SwarmFormation.cohesiveCluster(positions, 7);

		assertEquals(6, cluster.size());
		assertTrue(!cluster.contains(6));
		assertEquals(5, SwarmFormation.convergenceQuorum(7));
	}

	@Test
	void splitRecoveryKeepsTheClusterAroundTheFixedLeader() {
		List<Vec3> positions = List.of(
			new Vec3(0, 0, 0),
			new Vec3(35, 0, 0), new Vec3(37, 0, 0), new Vec3(39, 0, 0));

		List<Integer> leaderCluster = SwarmFormation.cohesiveClusterAround(positions, 4, 0);

		assertEquals(List.of(0), leaderCluster);
	}

	@Test
	void maximumSpreadUsesTheMostDistantPair() {
		double spread = SwarmFormation.maxSpread(List.of(
			new Vec3(0, 0, 0), new Vec3(3, 0, 4), new Vec3(12, 0, 0)));
		assertEquals(12.0, spread, 0.001);
	}

	@Test
	void arrivalRequiresACompleteFormation() {
		assertTrue(!SwarmFormation.individuallyArrived(DroneEntity.MISSION_CONVERGING, 8.0, 12.0, true));
		assertTrue(!SwarmFormation.individuallyArrived(DroneEntity.MISSION_MOVING, 8.0, 12.0, false));
		assertTrue(SwarmFormation.individuallyArrived(DroneEntity.MISSION_MOVING, 8.0, 12.0, true));
	}

	@Test
	void deltaFormationUsesClearMirroredWings() {
		Vec3 forward = new Vec3(0, 0, 1);
		Vec3 left = SwarmFormation.movingOffset(1, 5, forward);
		Vec3 right = SwarmFormation.movingOffset(2, 5, forward);

		assertTrue(left.x * right.x < 0.0);
		assertEquals(left.z, right.z, 0.001);
		assertTrue(left.z <= -2.4);
	}

	@Test
	void orbitEntryTrailsTheLeaderThenExpandsToFullCircle() {
		Vec3 center = Vec3.ZERO;
		Vec3 leader = SwarmFormation.orbitEntryPosition(center, 0, 5, 0, 0.032, 0.0, 0.0);
		Vec3 firstFollower = SwarmFormation.orbitEntryPosition(center, 1, 5, 0, 0.032, 0.0, 0.0);
		Vec3 secondFollower = SwarmFormation.orbitEntryPosition(center, 2, 5, 0, 0.032, 0.0, 0.0);
		assertTrue(firstFollower.z < leader.z);
		assertTrue(secondFollower.z < firstFollower.z);

		for (int index = 0; index < 5; index++) {
			Vec3 entry = SwarmFormation.orbitEntryPosition(center, index, 5, 120, 0.032, 1.0, 0.37);
			Vec3 orbit = SwarmFormation.orbitPosition(center, index, 5, 120, 0.032, 0.37);
			assertTrue(entry.distanceTo(orbit) < 0.0001);
		}
	}

	@Test
	void orbitEntryClosesMostOfTheSpacingGapInItsFirstHalf() {
		Vec3 center = Vec3.ZERO;
		Vec3 initial = SwarmFormation.orbitEntryPosition(center, 1, 5, 0, 0.038, 0.0, 0.0);
		Vec3 halfway = SwarmFormation.orbitEntryPosition(center, 1, 5, 0, 0.038, 0.5, 0.0);
		Vec3 complete = SwarmFormation.orbitEntryPosition(center, 1, 5, 0, 0.038, 1.0, 0.0);

		assertTrue(halfway.distanceTo(complete) < initial.distanceTo(complete) * 0.4);
	}

	@Test
	void orbitEntryAndFinalOrbitShareTheSameLayerRadius() {
		double radius = SwarmFormation.layeredOrbitRadius(8, 2, 6);
		for (int index = 0; index < 8; index++) {
			Vec3 entry = SwarmFormation.orbitEntryPosition(Vec3.ZERO, index, 8,
				180, 0.038, 1.0, 0.24, radius);
			Vec3 orbit = SwarmFormation.orbitPosition(Vec3.ZERO, index, 8,
				180, 0.038, 0.24, radius);
			assertEquals(orbit, entry);
		}
	}
}
