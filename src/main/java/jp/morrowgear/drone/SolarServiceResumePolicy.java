package jp.morrowgear.drone;

/** Deterministic exit order for a completed or lost solar-service link. */
final class SolarServiceResumePolicy {
	private SolarServiceResumePolicy() {
	}

	static Action choose(boolean savedTaskValid, boolean wingMissionAvailable,
		boolean dockRequestAvailable, boolean ownerAvailable) {
		if (savedTaskValid) return Action.RESUME_SAVED;
		return switch (MissionFallbackPolicy.choose(wingMissionAvailable,
			dockRequestAvailable, ownerAvailable)) {
			case REJOIN_WING -> Action.REJOIN_WING;
			case REQUEST_DOCK -> Action.REQUEST_DOCK;
			case FOLLOW_OWNER -> Action.FOLLOW_OWNER;
			case SAFE_ORBIT -> Action.SAFE_ORBIT;
		};
	}

	enum Action { RESUME_SAVED, REJOIN_WING, REQUEST_DOCK, FOLLOW_OWNER, SAFE_ORBIT }
}
