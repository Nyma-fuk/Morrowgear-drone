package jp.morrowgear.drone;

/** Deterministic ordering for safe aerial felling. Lower values leave the queue first. */
public final class ForestryOperationPolicy {
	private ForestryOperationPolicy() {}

	public static int priority(int y, boolean structuralLog) {
		return -y * 4 + (structuralLog ? 1 : 0);
	}

	public static boolean surveyReady(double distanceSquared, int radius) {
		double range = Math.max(14.0, Math.min(30.0, radius * 0.72 + 6.0));
		return distanceSquared <= range * range;
	}
}
