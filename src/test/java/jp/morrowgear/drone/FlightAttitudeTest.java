package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FlightAttitudeTest {
	@Test void flightDirectionCannotTurnFasterThanYawOrAvailableBank() {
		for (double speed : new double[] {.1, .25, .5, .8, 1.14}) {
			Vec3 velocity = new Vec3(0, .05, speed);
			Vec3 next = FlightAttitude.coordinatedVelocity(velocity, new Vec3(speed, .05, 0));
			double angle = Math.acos(next.normalize().dot(velocity.normalize()));
			assertTrue(angle <= Math.toRadians(FlightAttitude.yawTurnLimit(velocity)) + 1e-6);
			assertTrue(next.subtract(velocity).horizontalDistance()
				<= .10 * Math.tan(FlightAttitude.MAX_BANK) + 1e-6);
			assertEquals(speed, next.horizontalDistance(), 1e-8);
			assertEquals(.05, next.y);
		}
	}

	@Test void hoveringCanPivotAndBrakingDoesNotNeedALargeTurnCircle() {
		Vec3 desired = new Vec3(.04, .02, 0);
		assertEquals(desired, FlightAttitude.coordinatedVelocity(new Vec3(0, 0, .04), desired));
		assertEquals(Vec3.ZERO, FlightAttitude.coordinatedVelocity(new Vec3(.8, 0, 0), Vec3.ZERO));
		assertTrue(FlightAttitude.turnRateLimit(new Vec3(0, 0, 1.2))
			< FlightAttitude.turnRateLimit(new Vec3(0, 0, .6)));
	}
	@Test
	void yawFacesHorizontalMovement() {
		assertEquals(0.0f, FlightAttitude.movementYaw(new Vec3(0, 0, 1), 45), 0.001f);
		assertEquals(-90.0f, FlightAttitude.movementYaw(new Vec3(1, 0, 0), 45), 0.001f);
		assertEquals(90.0f, FlightAttitude.movementYaw(new Vec3(-1, 0, 0), 45), 0.001f);
	}

	@Test
	void forwardFlightPitchesNoseDownAndClimbPitchesNoseUp() {
		assertTrue(FlightAttitude.pitch(new Vec3(0, 0, 0.5), 0) > 0);
		assertTrue(FlightAttitude.pitch(new Vec3(0, 0.5, 0), 0) < 0);
	}

	@Test
	void rollFollowsAircraftRightAxis() {
		assertTrue(FlightAttitude.roll(new Vec3(-0.4, 0, 0), 0) > 0);
		assertTrue(FlightAttitude.roll(new Vec3(0.4, 0, 0), 0) < 0);
	}

	@Test
	void fasterFlightAllowsMechanicalHeadingResponse() {
		assertTrue(FlightAttitude.yawTurnLimit(new Vec3(0, 0, 0.7))
			> FlightAttitude.yawTurnLimit(Vec3.ZERO));
	}

	@Test
	void levelCruiseUsesShallowPitchWhileClimbAndDescentAreDistinct() {
		Vec3 level = new Vec3(0, 0, 0.7);
		float pitch = FlightAttitude.pitch(level, 0);
		assertTrue(pitch > 0 && pitch < 0.08f);
		assertTrue(FlightAttitude.pitch(new Vec3(0, 0.25, 0.7), 0) < 0);
		assertTrue(FlightAttitude.pitch(new Vec3(0, -0.25, 0.7), 0) > pitch);
		assertEquals(-FlightAttitude.pitch(new Vec3(0, 0.4, 0), 0),
			FlightAttitude.pitch(new Vec3(0, -0.4, 0), 0), 1e-6);
	}

	@Test
	void coordinatedBankTiltsLiftIntoTheTurnInTheRenderedCoordinateSystem() {
		for (float yaw : new float[] {0, 90, 180, -90}) {
			double heading = Math.toRadians(yaw);
			Vec3 forward = new Vec3(-Math.sin(heading), 0, Math.cos(heading));
			Vec3 right = new Vec3(-Math.cos(heading), 0, -Math.sin(heading));
			for (float yawRate : new float[] {-3, 3}) {
				float roll = FlightAttitude.coordinatedRoll(forward.scale(0.5), yaw, yawRate);
				// Apply Z(roll), then Y(-yaw) to local up, as the renderer/hardpoints do.
				Vec3 up = new Vec3(-Math.sin(roll) * Math.cos(heading), Math.cos(roll),
					-Math.sin(roll) * Math.sin(heading));
				assertTrue(up.dot(right.scale(Math.signum(yawRate))) > 0);
				assertTrue(Math.signum(roll) == Math.signum(yawRate));
			}
		}
		assertEquals(0, FlightAttitude.coordinatedRoll(Vec3.ZERO, 0, 7), 1e-6);
		assertEquals(0, FlightAttitude.roll(new Vec3(0, 0, 0.6), new Vec3(0, 0, 0.03), 0), 1e-6);
		assertTrue(FlightAttitude.roll(new Vec3(0, 0, 0.6), new Vec3(-0.03, 0, 0), 0) > 0);
	}

	@Test
	void headingCrossesWrapAndReversesWithBoundedRateAndAcceleration() {
		float previous = 179;
		float current = 179;
		float lastRate = 0;
		Vec3 velocity = new Vec3(0, 0, 0.6);
		for (int tick = 0; tick < 240; tick++) {
			float target = tick < 20 ? -179 : tick < 65 ? 90 : -90;
			float next = FlightAttitude.nextYaw(current, previous, target, velocity);
			float rate = next - current;
			assertTrue(Math.abs(rate) <= FlightAttitude.yawTurnLimit(velocity) + 1e-4);
			assertTrue(Math.abs(rate - lastRate) <= 0.6501f);
			if (tick < 20) assertTrue(current > 178 && current < 182);
			previous = current;
			current = next;
			lastRate = rate;
		}
		assertEquals(-90, current, 0.001);
	}

	@Test
	void pitchAndRollSmoothingIsTimeBasedBoundedAndFinite() {
		float angle = 0;
		for (int tick = 0; tick < 180; tick++) {
			float target = tick < 50 ? 0.34f : -0.24f;
			float next = FlightAttitude.smoothAngle(angle, target, 1);
			assertTrue(Math.abs(next - angle) <= 0.03501f);
			angle = next;
		}
		assertEquals(-0.24f, angle, 1e-6);
		float split = FlightAttitude.smoothAngle(FlightAttitude.smoothAngle(0, 0.1f, 0.5f), 0.1f, 0.5f);
		assertEquals(FlightAttitude.smoothAngle(0, 0.1f, 1), split, 1e-6);
		assertEquals(angle, FlightAttitude.smoothAngle(angle, 0, 0));
		Vec3 invalid = new Vec3(Double.NaN, 0, Double.POSITIVE_INFINITY);
		assertTrue(Float.isFinite(FlightAttitude.pitch(invalid, Float.NaN)));
		assertTrue(Float.isFinite(FlightAttitude.roll(invalid, invalid, Float.NaN)));
		assertTrue(Float.isFinite(FlightAttitude.coordinatedRoll(invalid, Float.NaN, Float.NaN)));
		assertTrue(Float.isFinite(FlightAttitude.movementYaw(invalid, Float.NaN)));
		assertTrue(Float.isFinite(FlightAttitude.smoothAngle(Float.NaN, Float.NaN, 1)));
	}
}
