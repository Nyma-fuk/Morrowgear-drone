package jp.morrowgear.drone;

final class TargetTrackingPolicy {
	private TargetTrackingPolicy() {}

	static TargetDisposition classify(Factors factors) {
		if (!factors.alive() || factors.self() || factors.drone()) return TargetDisposition.INVALID;
		if (factors.allied() || factors.ownerControlled()) return TargetDisposition.FRIENDLY;
		if (factors.targetingOwner() || factors.recentAttacker()) return TargetDisposition.DIRECT_THREAT;
		if (factors.hostileType()) return TargetDisposition.HOSTILE;
		return TargetDisposition.NEUTRAL;
	}

	record Factors(boolean alive, boolean self, boolean drone, boolean allied,
		boolean ownerControlled, boolean hostileType, boolean targetingOwner,
		boolean recentAttacker) {}
}
