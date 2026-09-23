package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class DroneNavigatorStrategicEscapeTest {
	@Test void strategicFanIncludesLongRangeDirectionsAwayFromAnOccludedTarget() {
		Vec3 forward = new Vec3(1, 0, 0);
		List<Vec3> directions = DroneNavigator.strategicEscapeDirections(forward, new Vec3(0, 0, 1));
		assertEquals(3, directions.size());
		assertTrue(directions.stream().allMatch(direction -> direction.dot(forward) < -0.6));
		assertTrue(directions.stream().anyMatch(direction -> direction.z > 0.5));
		assertTrue(directions.stream().anyMatch(direction -> direction.z < -0.5));
	}

	@Test void distantClearanceWaypointRemainsAuthoritativeUntilAircraftCanReachIt() {
		Vec3 current = new Vec3(0, 64, 0);
		assertEquals(160, DroneNavigator.strategicHoldTicks(current, current.add(8, 6, 0), true));
		assertTrue(DroneNavigator.strategicHoldTicks(current, current.add(8, 68, 0), true) >= 560);
		assertEquals(720, DroneNavigator.strategicHoldTicks(current, current.add(0, 100, 0), false));
	}

	@Test void movingFormationSlotsShareTheFixedMissionCacheGoal() {
		Vec3 destination = new Vec3(120, 72, 0);
		Vec3 firstSlot = new Vec3(18, 70, -4);
		Vec3 leaderAdvancedSlot = new Vec3(48, 70, 6);
		Vec3 cached = DroneNavigator.strategicCacheGoal(true, destination, firstSlot);
		Vec3 advanced = DroneNavigator.strategicCacheGoal(true, destination, leaderAdvancedSlot);
		assertEquals(destination, cached);
		assertEquals(cached, advanced);
		assertEquals(leaderAdvancedSlot,
			DroneNavigator.strategicCacheGoal(false, destination, leaderAdvancedSlot));
	}

	@Test void aClearDirectLineDoesNotCancelAnUnfinishedStrategicTurn() {
		Vec3 waypoint = new Vec3(-2.5, 2.0, -72.0);
		Vec3 direct = new Vec3(18.0, 2.0, 0.0);
		assertEquals(waypoint, DroneNavigator.preferredClearTarget(waypoint, direct, true));
		assertEquals(direct, DroneNavigator.preferredClearTarget(null, direct, true));
	}

	@Test void fourAircraftCompleteTheProductionStrategicTurnAroundTheGiantWall() {
		Vec3 destination = new Vec3(18.0, 8.0, 0.0);
		for (int member = 0; member < 4; member++) {
			Vec3 position = new Vec3(-10.5, 2.0, -3.0 + member * 2.0);
			double side = member < 2 ? -1.0 : 1.0;
			Vec3 waypoint = position.add(8.0, 0.0, side * 72.0);
			Vec3 velocity = Vec3.ZERO;
			FlightDynamics.Motion motion = new FlightDynamics.Motion();
			boolean exitLeg = false;
			boolean directBecameClearBeforeWaypoint = false;
			for (int tick = 0; tick < 1200 && position.x <= 2.0; tick++) {
				if (!exitLeg && position.distanceTo(waypoint) <= 1.2) exitLeg = true;
				boolean directClear = clearOfGiantWall(position, destination);
				directBecameClearBeforeWaypoint |= directClear && !exitLeg;
				Vec3 cached = exitLeg ? null : waypoint;
				Vec3 target = DroneNavigator.preferredClearTarget(cached, destination, directClear);
				if (target == null) target = waypoint;
				double remaining = position.distanceTo(destination);
				double speed = FlightDynamics.speedLimit(remaining, false, false);
				Vec3 requested = FlightDynamics.steerRoute(velocity, position, target, Vec3.ZERO, speed, remaining);
				Vec3 smoothed = motion.step(velocity, requested, tick, false);
				Vec3 stepOrigin = position;
				Vec3 safe = FlightDynamics.collisionSafeVelocity(smoothed, requested, false,
					step -> clearOfGiantWall(stepOrigin, stepOrigin.add(step)));
				velocity = safe == smoothed ? smoothed : motion.step(velocity, safe, tick, true);
				Vec3 next = position.add(velocity);
				assertTrue(clearOfGiantWall(position, next),
					"member " + member + " entered the wall at tick " + tick + ": " + next);
				position = next;
			}
			assertTrue(directBecameClearBeforeWaypoint,
				"fixture did not exercise the premature direct-line handoff for member " + member);
			assertTrue(position.x > 2.0, "member " + member + " remained west of the giant wall: " + position);
		}
	}

	private static boolean clearOfGiantWall(Vec3 from, Vec3 to) {
		for (int sample = 0; sample <= 12; sample++) {
			Vec3 point = from.add(to.subtract(from).scale(sample / 12.0));
			if (point.x >= -2.0 && point.x <= 2.0
				&& point.z >= -42.0 && point.z <= 42.0
				&& point.y >= 0.0 && point.y <= 52.0) return false;
		}
		return true;
	}
}
