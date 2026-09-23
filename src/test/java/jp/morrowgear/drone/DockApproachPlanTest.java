package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

final class DockApproachPlanTest {
	@Test
	void topEntryMovesFromHoldingPointToFinalApproach() {
		DockApproachPlan.Phase phase = DockApproachPlan.phase(true, 0);
		assertEquals(DockApproachPlan.Phase.TOP_HOLD, phase);
		assertEquals(2, DockApproachPlan.nextStage(phase));
		assertEquals(DockApproachPlan.Phase.FINAL, DockApproachPlan.phase(true, 2));
	}

	@Test
	void sideEntryUsesOuterAndGateWaypointsBeforeLanding() {
		DockApproachPlan.Phase outer = DockApproachPlan.phase(false, 0);
		assertEquals(DockApproachPlan.Phase.SIDE_OUTER, outer);
		assertEquals(1, DockApproachPlan.nextStage(outer));
		DockApproachPlan.Phase gate = DockApproachPlan.phase(false, 1);
		assertEquals(DockApproachPlan.Phase.SIDE_GATE, gate);
		assertEquals(2, DockApproachPlan.nextStage(gate));
		assertEquals(DockApproachPlan.Phase.FINAL, DockApproachPlan.phase(false, 2));
	}

	@Test
	void sideEntryEvaluatesEveryHorizontalDirection() {
		List<Direction> directions = DockApproachPlan.sideDirections(Direction.NORTH);
		assertEquals(List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST), directions);
		assertEquals(4, new HashSet<>(directions).size());
	}

	@Test
	void missingDockFacingUsesStableNorthFallback() {
		assertEquals(DockApproachPlan.sideDirections(Direction.NORTH), DockApproachPlan.sideDirections(null));
	}

	@Test
	void blockedThreeSidedDockRoutesAroundThePerimeterToTheOpenEastLane() {
		Vec3 current = new Vec3(-8.0, 2.0, 0.0);
		Vec3 landing = new Vec3(0.0, 0.344, 0.0);
		Vec3 outer = new Vec3(4.5, 0.844, 0.0);
		java.util.function.BiPredicate<Vec3, Vec3> clear = (from, to) -> clearOfBlockedApproaches(from, to);
		List<Vec3> route = DockApproachPlan.ingressRoute(current, landing, outer, Direction.EAST, clear);

		assertFalse(route.isEmpty());
		assertEquals(outer, route.getLast());
		assertTrue(route.size() >= 3, "the west start must go around the blocked perimeter");
		Vec3 previous = current;
		for (Vec3 waypoint : route) {
			assertTrue(clear.test(previous, waypoint), previous + " -> " + waypoint);
			previous = waypoint;
		}
	}

	@Test
	void clearDockApproachUsesTheOuterLaneDirectly() {
		Vec3 current = new Vec3(8.0, 2.0, 0.0);
		Vec3 landing = new Vec3(0.0, 0.344, 0.0);
		Vec3 outer = new Vec3(4.5, 0.844, 0.0);
		assertEquals(List.of(outer), DockApproachPlan.ingressRoute(current, landing, outer,
			Direction.EAST, (from, to) -> true));
	}

	@Test
	void productionSteeringKeepsEveryLiveIngressSegmentClearUntilTheEastLane() {
		Vec3 position = new Vec3(-8.0, 2.0, 0.0);
		Vec3 landing = new Vec3(0.0, 0.344, 0.0);
		Vec3 outer = new Vec3(4.5, 0.844, 0.0);
		Vec3 gate = new Vec3(1.55, 1.094, 0.0);
		java.util.function.BiPredicate<Vec3, Vec3> clear = (from, to) -> clearOfBlockedApproaches(from, to);
		List<Vec3> route = DockApproachPlan.ingressRoute(position, landing, outer, Direction.EAST, clear);
		Vec3 velocity = Vec3.ZERO;
		FlightDynamics.Motion motion = new FlightDynamics.Motion();
		int index = 0;
		int stage = 0;
		boolean docked = false;
		for (int tick = 0; tick < 900 && !docked; tick++) {
			Vec3 target;
			boolean finalApproach = stage >= 2;
			double arrival;
			if (stage == 0) {
				while (index < route.size() - 1
					&& position.distanceTo(route.get(index)) <= DockApproachPlan.CORNER_ARRIVAL_DISTANCE) index++;
				target = route.get(index);
				boolean outerLeg = index == route.size() - 1;
				arrival = DockApproachPlan.routeArrivalDistance(outerLeg);
				if (position.distanceTo(target) <= arrival) {
					stage = outerLeg ? 1 : 0;
					continue;
				}
			} else if (stage == 1) {
				target = DockApproachPlan.gateAlignmentTarget(position, outer, gate, clear);
				arrival = target == gate ? 0.55 : DockApproachPlan.OUTER_ALIGNMENT_DISTANCE;
				if (position.distanceTo(target) <= arrival) {
					if (target == gate) stage = 2;
					continue;
				}
			} else {
				target = landing;
				arrival = 0.035;
			}
			assertTrue(finalApproach || clear.test(position, target),
				"live turn lost its planned corridor at tick " + tick + ": " + position + " -> " + target);
			double distance = position.distanceTo(target);
			double speed = FlightDynamics.speedLimit(distance, true, finalApproach);
			Vec3 requested = stage == 0
				? FlightDynamics.steerRoute(velocity, position, target, Vec3.ZERO, speed, distance)
				: FlightDynamics.steer(velocity, position, target, Vec3.ZERO, speed, finalApproach);
			Vec3 smoothed = motion.step(velocity, requested, tick, false);
			Vec3 stepOrigin = position;
			Vec3 safe = FlightDynamics.collisionSafeVelocity(smoothed, requested, finalApproach,
				step -> finalApproach || clear.test(stepOrigin, stepOrigin.add(step)));
			velocity = safe == smoothed ? smoothed : motion.step(velocity, safe, tick, true);
			Vec3 next = position.add(velocity);
			assertTrue(finalApproach || clear.test(position, next),
				"steering cut inside the blocked perimeter at tick " + tick);
			position = next;
			if (finalApproach && FlightDynamics.touchdownReady(position.distanceTo(landing), velocity)) docked = true;
		}
		assertTrue(docked, "did not complete the live 900 tick east-lane landing: " + position + " stage=" + stage);
		assertEquals(outer, DockApproachPlan.gateAlignmentTarget(
			outer.add(0, 0, 1.8), outer, gate, DockApproachPlanTest::clearOfBlockedApproaches));
		assertEquals(gate, DockApproachPlan.gateAlignmentTarget(
			outer, outer, gate, DockApproachPlanTest::clearOfBlockedApproaches));
	}

	@Test void onlyTheFinalOuterLegRequiresPreciseLaneAlignment() {
		assertEquals(0.9, DockApproachPlan.routeArrivalDistance(false));
		assertEquals(0.28, DockApproachPlan.routeArrivalDistance(true));
	}

	private static boolean clearOfBlockedApproaches(Vec3 from, Vec3 to) {
		for (int sample = 0; sample <= 80; sample++) {
			double progress = sample / 80.0;
			Vec3 point = from.add(to.subtract(from).scale(progress));
			if (inside(point, -7.0, -0.5, -2.5, 2.5)
				|| inside(point, -2.5, 2.5, -7.0, -0.5)
				|| inside(point, -2.5, 2.5, 0.5, 7.0)) return false;
		}
		return true;
	}

	private static boolean inside(Vec3 point, double minX, double maxX, double minZ, double maxZ) {
		return point.x >= minX && point.x <= maxX && point.z >= minZ && point.z <= maxZ;
	}
}
