package jp.morrowgear.drone;

final class EngineerAssignmentPolicy {
	private EngineerAssignmentPolicy() {
	}

	static int targetIndex(int engineerRank, int damagedTargets) {
		if (engineerRank < 0 || damagedTargets <= 0) return -1;
		return engineerRank % damagedTargets;
	}
}
