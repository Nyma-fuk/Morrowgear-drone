package jp.morrowgear.drone;

/** Deterministic exit order for a completed or lost solar-service link. */
final class SolarServiceResumePolicy {
	private SolarServiceResumePolicy() {
	}

	static Action choose(boolean savedTaskValid, boolean wingMissionAvailable,
		boolean dockRequestAvailable, boolean ownerAvailable) {
		if (savedTaskValid) return Action.RESUME_SAVED;
		if (wingMissionAvailable) return Action.REJOIN_WING;
		if (dockRequestAvailable) return Action.REQUEST_DOCK;
		if (ownerAvailable) return Action.FOLLOW_OWNER;
		return Action.SAFE_ORBIT;
	}

	enum Action { RESUME_SAVED, REJOIN_WING, REQUEST_DOCK, FOLLOW_OWNER, SAFE_ORBIT }
}
