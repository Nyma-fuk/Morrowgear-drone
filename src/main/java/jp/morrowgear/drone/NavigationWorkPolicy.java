package jp.morrowgear.drone;

/** Hard server-side work limits. They bound one planning decision, not mission distance. */
final class NavigationWorkPolicy {
	static final double PRECISE_CORRIDOR_DISTANCE = 96.0;
	static final double CORRIDOR_SAMPLE_STEP = 0.9;
	static final int MAX_CORRIDOR_SAMPLES = (int)Math.ceil(PRECISE_CORRIDOR_DISTANCE / CORRIDOR_SAMPLE_STEP);
	static final int MAX_STRATEGIC_PROBES = 96;
	static final double[] FORWARD_SEGMENTS = {24.0, 48.0, 72.0};

	private NavigationWorkPolicy() {}

	static int corridorSamples(double distance) {
		if (!Double.isFinite(distance) || distance < 0 || distance > PRECISE_CORRIDOR_DISTANCE) return -1;
		return Math.max(1, Math.min(MAX_CORRIDOR_SAMPLES,
			(int)Math.ceil(distance / CORRIDOR_SAMPLE_STEP)));
	}

	static int strategicProbeCount(int candidates) {
		return Math.max(0, Math.min(MAX_STRATEGIC_PROBES, candidates));
	}

	static int strategicProbeIndex(int probe, int candidates) {
		int count = strategicProbeCount(candidates);
		if (probe < 0 || probe >= count) throw new IndexOutOfBoundsException(probe);
		if (candidates <= MAX_STRATEGIC_PROBES) return probe;
		int nearCount = MAX_STRATEGIC_PROBES / 2;
		if (probe < nearCount) return probe;
		int spreadCount = count - nearCount;
		int spreadOffset = probe - nearCount;
		return nearCount + (int)Math.round(spreadOffset
			* (candidates - 1.0 - nearCount) / Math.max(1, spreadCount - 1));
	}
}
