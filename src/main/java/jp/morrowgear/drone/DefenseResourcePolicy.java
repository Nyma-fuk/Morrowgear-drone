package jp.morrowgear.drone;

final class DefenseResourcePolicy {
	private DefenseResourcePolicy() {
	}

	static int requiredDefenders(ThreatAssessment.Snapshot threat, int total, int missionCount) {
		if (total <= 0 || threat.enemyCount() == 0 && !threat.directThreat()) return 0;
		int requested = switch (threat.band()) {
			case LOW -> threat.directThreat() ? Math.min(2, total) : 0;
			case GUARDED -> threat.directThreat() ? Math.min(2, total) : 1;
			case HIGH -> Math.max(2, (int) Math.ceil(total * 0.40));
			case CRITICAL -> Math.max(3, (int) Math.ceil(total * 0.60));
		};
		int missionReserve = missionCount > 0 && total > 1 ? 1 : 0;
		return Math.max(0, Math.min(requested, total - missionReserve));
	}

	static int availabilityRank(DroneMode mode) {
		return switch (mode) {
			case STANDBY, FOLLOW, RETURN, ORBIT -> 0;
			case DOCK -> 1;
			case WAYPOINT -> 2;
		};
	}
}
