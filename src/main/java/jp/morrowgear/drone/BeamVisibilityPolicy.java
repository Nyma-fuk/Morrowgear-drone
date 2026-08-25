package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

public final class BeamVisibilityPolicy {
	private BeamVisibilityPolicy() {
	}

	public static List<Range> visibleRanges(int steps, IntPredicate visibleSample) {
		if (steps < 1) throw new IllegalArgumentException("steps must be positive");
		List<Range> ranges = new ArrayList<>();
		int openStart = -1;
		for (int sample = 0; sample <= steps; sample++) {
			boolean visible = visibleSample.test(sample);
			if (visible && openStart < 0) openStart = sample;
			if ((!visible || sample == steps) && openStart >= 0) {
				int end = visible && sample == steps ? sample : sample - 1;
				if (end > openStart) ranges.add(new Range(
					openStart / (double)steps, end / (double)steps));
				openStart = -1;
			}
		}
		return List.copyOf(ranges);
	}

	public record Range(double start, double end) {
		public Range {
			if (start < 0.0 || end > 1.0 || end <= start) {
				throw new IllegalArgumentException("invalid normalized beam range");
			}
		}
	}
}
