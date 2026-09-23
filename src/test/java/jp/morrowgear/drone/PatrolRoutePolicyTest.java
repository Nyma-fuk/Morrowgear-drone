package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	@Test void recordedFirstLegEquilibriumCannotRemainOutsideTheHandoffVolume() {
		Vec3 origin = new Vec3(5993.5, 137.5, 6008.5);
		Vec3 current = new Vec3(5964.5, -53.5, 5977.5);
		Vec3 next = new Vec3(6036.5, -53.5, 5977.5);
		Vec3 stopped = new Vec3(5977.355242289541, -53.50784398836154, 5977.501774562098);
		double blend = 1 - stopped.distanceTo(current) / PatrolRoutePolicy.TURN_RADIUS;
		blend = blend * blend * (3 - 2 * blend);
		Vec3 oldGoal = current.scale(1 - blend * .9).add(next.scale(blend * .9));
		assertEquals(stopped.x, oldGoal.x, .001, "unbounded lookahead reproduces the observed fixed point");
		Vec3 incoming = current.subtract(origin).multiply(1, 0, 1);
		double progress = stopped.subtract(origin).multiply(1, 0, 1).dot(incoming) / incoming.lengthSqr();
		assertEquals(.7930871, progress, .000001);
		assertFalse(PatrolRoutePolicy.shouldAdvance(stopped, origin, current));
		Vec3 goal = PatrolRoutePolicy.curvedTarget(stopped, current, next, 4, 0);
		assertTrue(goal.distanceTo(current) <= PatrolRoutePolicy.ADVANCE_RADIUS - 1 + 1.0e-9);
		assertTrue(stopped.distanceTo(goal) > 7, "steering must have a real closure vector");
		assertTrue(PatrolRoutePolicy.shouldAdvance(goal, origin, current));
		simulateLoops(List.of(current, next, new Vec3(6036.5, -53.5, 6049.5),
			new Vec3(5964.5, -53.5, 6049.5)), origin);
	}

	@Test void movingCornerGoalAlwaysFitsTheCurrentAcceptanceVolume() {
		Vec3 corner = new Vec3(0, 70, 0);
		for (int count : new int[] {2, 3, 4}) for (double length : new double[] {10, 72, 1000})
			for (double approach : new double[] {.5, 4, 8, 13, 17.9}) {
				Vec3 goal = PatrolRoutePolicy.curvedTarget(new Vec3(-approach, 70, 0),
					corner, new Vec3(length, 90, 0), count, 0);
				assertTrue(goal.distanceTo(corner) < PatrolRoutePolicy.ADVANCE_RADIUS,
					"next leg length/height and a two-point lateral arc must not strand this leg");
			}
	}

	@Test void multiPointHandoffRespectsAltitudeAndTheCurrentSegment() {
		Vec3 previous = new Vec3(0, 70, 0);
		Vec3 current = new Vec3(100, 70, 0);
		assertFalse(PatrolRoutePolicy.shouldAdvance(new Vec3(100, 137, 0), previous, current));
		assertFalse(PatrolRoutePolicy.shouldAdvance(new Vec3(100, 20, 0), previous, current));
		assertFalse(PatrolRoutePolicy.shouldAdvance(new Vec3(84, 84, 11), previous, current));
		assertFalse(PatrolRoutePolicy.shouldAdvance(new Vec3(-10, 70, 0), previous, current));
		assertFalse(PatrolRoutePolicy.shouldAdvance(new Vec3(90, 70, 30), previous, current));
		assertFalse(PatrolRoutePolicy.shouldAdvance(new Vec3(820, 70, 0), previous, new Vec3(1000, 70, 0)),
			"82% of a long leg is not a near-corner handoff");
		assertTrue(PatrolRoutePolicy.shouldAdvance(new Vec3(84, 70, 11), previous, current));
		assertTrue(PatrolRoutePolicy.shouldAdvance(new Vec3(104, 70, 0), previous, current));
		assertTrue(PatrolRoutePolicy.shouldAdvance(new Vec3(4, 70, 0), new Vec3(0, 137, 0), previous));
	}

	@Test void singlePointArrivalAndNumberingKeepTheirExistingMeaning() {
		Vec3 vertex = new Vec3(10, 70, 0);
		assertTrue(PatrolRoutePolicy.shouldAdvance(new Vec3(4, 84, 0), vertex));
		assertEquals(vertex, PatrolRoutePolicy.curvedTarget(new Vec3(5, 70, 0), vertex,
			new Vec3(100, 70, 0), 1, 0));
		assertEquals(0, PatrolRoutePolicy.nextIndex(0, 1));
	}

	@Test void twoThreeAndFourPointRoutesCompleteLoopsFromDifferentFirstLegs() {
		List<List<Vec3>> routes = List.of(
			List.of(new Vec3(0, 70, 0), new Vec3(72, 70, 0)),
			List.of(new Vec3(0, 70, 0), new Vec3(72, 70, 0), new Vec3(36, 70, 64)),
			List.of(new Vec3(0, 70, 0), new Vec3(72, 70, 0), new Vec3(72, 70, 72), new Vec3(0, 70, 72)),
			List.of(new Vec3(0, 70, 0), new Vec3(72, 78, 0), new Vec3(72, 66, 72), new Vec3(0, 74, 72)),
			List.of(new Vec3(0, 70, 0), new Vec3(1000, 70, 0), new Vec3(0, 70, 20)));
		List<Vec3> origins = List.of(new Vec3(29, 260, 31), new Vec3(-72, 70, 0),
			new Vec3(0, 70, -72), new Vec3(110, 70, 35), new Vec3(25, 20, 35), new Vec3(0, 140, 0));
		for (List<Vec3> route : routes) for (Vec3 origin : origins) simulateLoops(route, origin);
	}

	// A bounded inertial follower exercises the policy's moving goal, smoothing and
	// handoff together. It does not claim to replace the in-game entity flight test.
	private static void simulateLoops(List<Vec3> route, Vec3 origin) {
		Vec3 position = origin;
		Vec3 velocity = Vec3.ZERO;
		Vec3 guidance = null;
		int index = 0;
		int handoffs = 0;
		double travel = 0;
		for (int tick = 0; tick < 24000; tick++) {
			Vec3 previous = handoffs == 0 ? origin : route.get(Math.floorMod(index - 1, route.size()));
			if (PatrolRoutePolicy.shouldAdvance(position, previous, route.get(index))) {
				assertTrue(Math.abs(position.y - route.get(index).y) <= PatrolRoutePolicy.ADVANCE_RADIUS);
				index = PatrolRoutePolicy.nextIndex(index, route.size());
				if (++handoffs == route.size() * 3) {
					assertEquals(0, index);
					assertTrue(travel > 100, "count actual traversal, not stationary index cycling");
					return;
				}
			}
			Vec3 goal = PatrolRoutePolicy.curvedTarget(position, route.get(index),
				route.get(PatrolRoutePolicy.nextIndex(index, route.size())), route.size(), index);
			guidance = PatrolRoutePolicy.smoothGuidance(guidance, goal);
			Vec3 error = guidance.subtract(position);
			Vec3 desired = error.length() < .01 ? Vec3.ZERO
				: error.normalize().scale(Math.min(.65, error.length() * .075));
			Vec3 change = desired.subtract(velocity).scale(.22);
			if (change.length() > .055) change = change.normalize().scale(.055);
			velocity = velocity.add(change);
			position = position.add(velocity);
			travel += velocity.length();
		}
		assertEquals(route.size() * 3, handoffs, "stalled route=" + route + " origin=" + origin
			+ " index=" + index + " position=" + position + " velocity=" + velocity);
	}
}
