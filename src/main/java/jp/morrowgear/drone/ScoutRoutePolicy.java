package jp.morrowgear.drone;

final class ScoutRoutePolicy {
	static final double MIN_CONFIDENCE = 0.45;
	static final long MAX_DECISION_AGE_TICKS = 100L;

	private ScoutRoutePolicy() {
	}

	static Decision decide(MissionDataLink.Snapshot snapshot, long now) {
		if (!snapshot.available() || snapshot.confidence() < MIN_CONFIDENCE
			|| now - snapshot.newestObservation() > MAX_DECISION_AGE_TICKS) {
			return Decision.local();
		}
		return switch (snapshot.routeStatus()) {
			case CLEAR -> new Decision(true, 0.0, Double.POSITIVE_INFINITY, "SCOUT CLEAR");
			case PROBING -> new Decision(true, 2.0, 0.78, "SCOUT PROBING");
			case HAZARDOUS -> new Decision(true, 5.0, 0.66, "SCOUT HAZARD");
			case BLOCKED -> new Decision(true, 9.0, 0.52, "SCOUT BLOCKED");
			case UNKNOWN -> Decision.local();
		};
	}

	record Decision(boolean usable, double clearanceBoost, double leaderSpeedCap, String status) {
		static Decision local() {
			return new Decision(false, 0.0, Double.POSITIVE_INFINITY, "LOCAL");
		}
	}
}
