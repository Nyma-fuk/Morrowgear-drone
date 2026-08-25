package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FlightAttitudeTest {
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
}
