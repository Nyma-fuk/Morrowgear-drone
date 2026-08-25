package jp.morrowgear.drone;

final class OrbitAltitudeStabilizer {
	static final int DESCENT_CONFIRMATION_SAMPLES = 4;
	private static final double RISE_MARGIN = 0.2;
	private static final double DESCENT_DEADBAND = 1.0;
	private static final double MAX_DESCENT_PER_SAMPLE = 0.35;

	private OrbitAltitudeStabilizer() {
	}

	static State update(State previous, double requiredAltitude) {
		if (previous == null || !Double.isFinite(previous.altitude())) {
			return new State(requiredAltitude, 0);
		}
		if (requiredAltitude > previous.altitude() + RISE_MARGIN) {
			return new State(requiredAltitude, 0);
		}
		if (requiredAltitude >= previous.altitude() - DESCENT_DEADBAND) {
			return new State(previous.altitude(), 0);
		}

		int clearSamples = previous.clearSamples() + 1;
		if (clearSamples < DESCENT_CONFIRMATION_SAMPLES) {
			return new State(previous.altitude(), clearSamples);
		}
		double descended = Math.max(requiredAltitude, previous.altitude() - MAX_DESCENT_PER_SAMPLE);
		return new State(descended, DESCENT_CONFIRMATION_SAMPLES);
	}

	record State(double altitude, int clearSamples) {
	}
}
