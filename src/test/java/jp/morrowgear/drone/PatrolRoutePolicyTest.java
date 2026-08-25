package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class PatrolRoutePolicyTest {
	@Test void routeRoundTripsAndLoops() {
		List<BlockPos> points = List.of(new BlockPos(10, 0, 20), new BlockPos(-3, 0, 7), new BlockPos(5, 0, -8));
		assertEquals(points, PatrolRoutePolicy.decode(PatrolRoutePolicy.encode(points)));
		assertEquals(0, PatrolRoutePolicy.nextIndex(2, 3));
	}

	@Test void turnTargetBeginsBendingBeforeTheVertex() {
		Vec3 current = new Vec3(10, 70, 0);
		Vec3 next = new Vec3(10, 70, 20);
		Vec3 target = PatrolRoutePolicy.curvedTarget(new Vec3(5, 70, 0), current, next, 3, 0);
		assertTrue(target.z > 0.0);
		assertTrue(target.x <= current.x);
	}

	@Test void twoPointRouteUsesAVisibleLateralTurnArc() {
		Vec3 first = new Vec3(10, 70, 0);
		Vec3 second = new Vec3(0, 70, 0);
		Vec3 outbound = PatrolRoutePolicy.curvedTarget(new Vec3(6, 70, 0), first, second, 2, 0);
		Vec3 inbound = PatrolRoutePolicy.curvedTarget(new Vec3(4, 70, 0), second, first, 2, 1);
		assertTrue(Math.abs(outbound.z) > 1.0);
		assertTrue(Math.abs(inbound.z) > 1.0);
		assertTrue(outbound.z * inbound.z > 0.0);
	}

	@Test void routeHandsOffWhileTheCurvedTargetIsStillReachable() {
		Vec3 vertex = new Vec3(10, 70, 0);
		Vec3 next = new Vec3(0, 70, 0);
		Vec3 handoff = new Vec3(4, 84, 0);
		assertTrue(PatrolRoutePolicy.shouldAdvance(handoff, vertex));
		Vec3 curved = PatrolRoutePolicy.curvedTarget(new Vec3(4, 70, 0), vertex, next, 2, 0);
		assertTrue(curved.distanceTo(vertex) < PatrolRoutePolicy.TURN_RADIUS);
	}

	@Test void curvedFlightCanAdvanceAfterPassingTheIncomingLegHandoff() {
		Vec3 previous = new Vec3(0, 70, 0);
		Vec3 current = new Vec3(100, 70, 0);
		assertTrue(PatrolRoutePolicy.shouldAdvance(new Vec3(84, 70, 11), previous, current));
		org.junit.jupiter.api.Assertions.assertFalse(
			PatrolRoutePolicy.shouldAdvance(new Vec3(60, 70, 3), previous, current));
		org.junit.jupiter.api.Assertions.assertFalse(
			PatrolRoutePolicy.shouldAdvance(new Vec3(90, 70, 30), previous, current));
	}

	@Test void guidanceTargetLimitsVertexAndAltitudeDiscontinuities() {
		Vec3 previous = new Vec3(10, 70, 5);
		Vec3 requested = new Vec3(40, 90, 30);
		Vec3 smoothed = PatrolRoutePolicy.smoothGuidance(previous, requested);
		assertTrue(smoothed.subtract(previous).multiply(1, 0, 1).length() <= 3.5001);
		assertTrue(Math.abs(smoothed.y - previous.y) <= 0.4501);
	}

	@Test void routeInputIsBoundedAndMalformedInputIsRejected() {
		List<BlockPos> many = java.util.stream.IntStream.range(0, 12)
			.mapToObj(index -> new BlockPos(index, 0, -index)).toList();
		assertEquals(PatrolRoutePolicy.MAX_POINTS,
			PatrolRoutePolicy.decode(PatrolRoutePolicy.encode(many)).size());
		assertTrue(PatrolRoutePolicy.decode("1,2;broken").isEmpty());
		assertEquals(0, PatrolRoutePolicy.nextIndex(99, 0));
	}

	@Test void wingRouteAdvancesWithLeaderQuorumWithoutWaitingForEveryWing() {
		assertTrue(PatrolRoutePolicy.leaderQuorumReached(1, 1));
		assertTrue(PatrolRoutePolicy.leaderQuorumReached(2, 3));
		assertTrue(PatrolRoutePolicy.leaderQuorumReached(3, 5));
		org.junit.jupiter.api.Assertions.assertFalse(PatrolRoutePolicy.leaderQuorumReached(2, 5));
		org.junit.jupiter.api.Assertions.assertFalse(PatrolRoutePolicy.leaderQuorumReached(0, 0));
	}

	@Test void singleDroneStartsAtItsNearestNumberedPoint() {
		List<BlockPos> route = List.of(new BlockPos(0, 0, 0), new BlockPos(100, 0, 0),
			new BlockPos(100, 0, 100), new BlockPos(0, 0, 100));
		assertEquals(2, PatrolRoutePolicy.nearestIndex(route, new Vec3(94, 70, 91)));
	}

	@Test void wingUsesOneCentroidAndOneSharedStartPoint() {
		Vec3 origin = PatrolRoutePolicy.formationOrigin(List.of(
			new Vec3(80, 70, 88), new Vec3(100, 72, 92), new Vec3(90, 68, 100)));
		assertEquals(new Vec3(90, 0, 280.0 / 3.0), origin);
		List<BlockPos> route = List.of(new BlockPos(0, 0, 0), new BlockPos(100, 0, 0),
			new BlockPos(100, 0, 100), new BlockPos(0, 0, 100));
		assertEquals(2, PatrolRoutePolicy.nearestIndex(route, origin));
	}

	@Test void equalDistanceKeepsTheLowestOriginalPointNumber() {
		List<BlockPos> route = List.of(new BlockPos(0, 0, 0), new BlockPos(10, 0, 0));
		assertEquals(0, PatrolRoutePolicy.nearestIndex(route, new Vec3(5.5, 64, .5)));
	}
}
