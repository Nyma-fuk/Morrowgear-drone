package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OrbitAltitudeStabilizerTest {
	@Test
	void risesImmediatelyWhenNewObstacleRequiresIt() {
		var state = new OrbitAltitudeStabilizer.State(70.0, 2);
		assertEquals(76.0, OrbitAltitudeStabilizer.update(state, 76.0).altitude(), 0.001);
	}

	@Test
	void ignoresSmallTerrainChangesAroundTheCurrentPlane() {
		var state = new OrbitAltitudeStabilizer.State(70.0, 0);
		assertEquals(70.0, OrbitAltitudeStabilizer.update(state, 69.4).altitude(), 0.001);
	}

	@Test
	void descendsOnlyAfterSustainedClearanceAndThenGradually() {
		var state = new OrbitAltitudeStabilizer.State(75.0, 0);
		for (int sample = 1; sample < OrbitAltitudeStabilizer.DESCENT_CONFIRMATION_SAMPLES; sample++) {
			state = OrbitAltitudeStabilizer.update(state, 68.0);
			assertEquals(75.0, state.altitude(), 0.001);
		}
		state = OrbitAltitudeStabilizer.update(state, 68.0);
		assertEquals(74.65, state.altitude(), 0.001);
	}

	@Test
	void interruptedDescentConfirmationResets() {
		var state = OrbitAltitudeStabilizer.update(new OrbitAltitudeStabilizer.State(75.0, 0), 68.0);
		state = OrbitAltitudeStabilizer.update(state, 74.4);
		assertEquals(0, state.clearSamples());
		assertEquals(75.0, state.altitude(), 0.001);
	}
}
