package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FlightDynamicsTest {
	@Test
	void cruiseSpeedTransitionsSmoothlyThroughCatchUpRange() {
		double before = FlightDynamics.speedLimit(11.9, false, false);
		double after = FlightDynamics.speedLimit(12.1, false, false);
		assertTrue(after > before);
		assertTrue(after - before < 0.02);
		assertTrue(FlightDynamics.speedLimit(20.0, false, false) > after);
		assertEquals(0.96, FlightDynamics.speedLimit(40.0, false, false), 0.0001);
	}

	@Test
	void dockingUsesStableApproachAndFinalLimits() {
		assertEquals(1.08, FlightDynamics.speedLimit(30.0, true, false), 0.0001);
		assertTrue(FlightDynamics.speedLimit(12.0, true, false) > 0.28);
		assertTrue(FlightDynamics.speedLimit(12.0, true, false) < 1.08);
		assertEquals(0.28, FlightDynamics.speedLimit(5.0, true, false), 0.0001);
		assertEquals(0.14, FlightDynamics.speedLimit(30.0, true, true), 0.0001);
	}

	@Test
	void steeringAcceleratesWithoutInstantVelocityJump() {
		Vec3 next = FlightDynamics.steer(Vec3.ZERO, Vec3.ZERO, new Vec3(10, 0, 0), Vec3.ZERO, 0.5, false);
		assertTrue(next.x > 0.0);
		assertTrue(next.x < 0.5);
		assertEquals(0.0, next.y, 0.0001);
		assertEquals(0.0, next.z, 0.0001);
	}

	@Test
	void mechanicalBrakeSettlesWithoutHoverOscillation() {
		Vec3 velocity = new Vec3(0.2, 0.08, -0.1);
		for (int i = 0; i < 8; i++) velocity = FlightDynamics.brake(velocity);
		assertEquals(Vec3.ZERO, velocity);
	}

	@Test
	void routeSteeringDoesNotTreatANearbyWaypointAsTheFinalDestination() {
		Vec3 normal = FlightDynamics.steer(Vec3.ZERO, Vec3.ZERO,
			new Vec3(2.0, 0, 0), Vec3.ZERO, 0.72, false);
		Vec3 routed = FlightDynamics.steerRoute(Vec3.ZERO, Vec3.ZERO,
			new Vec3(2.0, 0, 0), Vec3.ZERO, 0.72, 30.0);

		assertTrue(routed.length() > normal.length() * 1.5);
		assertTrue(routed.length() < 0.72);
	}

	@Test
	void convergingUnitMatchesLeaderVelocityNearItsSlot() {
		Vec3 leaderVelocity = new Vec3(0.38, 0.0, 0.0);
		Vec3 result = FlightDynamics.steerConverging(Vec3.ZERO, Vec3.ZERO,
			new Vec3(2.0, 0.0, 0.0), Vec3.ZERO, 0.55, leaderVelocity);

		assertTrue(result.x > 0.30);
		assertTrue(result.x <= 0.55);
	}

	@Test
	void predictiveRouteLimitsHorizontalAndVerticalAcceleration() {
		Vec3 current = new Vec3(0.7, 0.05, 0.0);
		Vec3 next = FlightDynamics.steerPredictiveRoute(current, Vec3.ZERO,
			new Vec3(0, 30, 30), Vec3.ZERO, 0.96, 60.0);
		Vec3 change = next.subtract(current);
		assertTrue(change.multiply(1, 0, 1).length() <= 0.0551);
		assertTrue(Math.abs(change.y) <= 0.0321);
	}

	@Test
	void combatBreakawayCannotCauseASuddenSpeedJump() {
		Vec3 current = new Vec3(0.18, 0.0, 0.0);
		Vec3 next = FlightDynamics.steerCombatBreakaway(current, Vec3.ZERO,
			new Vec3(30, 12, 0), 1.14);
		assertTrue(next.subtract(current).length() <= 0.0851);
		assertTrue(next.length() <= 1.14);
	}

	@Test
	void movingCombatSlotCannotReverseVelocityAbruptly() {
		Vec3 current = new Vec3(0.9, 0.0, 0.0);
		Vec3 next = FlightDynamics.steerMovingOrbit(current, Vec3.ZERO,
			new Vec3(-20, 4, 0), new Vec3(-0.9, 0.0, 0.0), 0.92);

		assertTrue(next.subtract(current).length() <= 0.1201);
		assertTrue(next.length() <= 0.92);
	}
}
