package jp.morrowgear.drone;

/** Shared final fallback when a suspended assignment can no longer be resumed. */
final class MissionFallbackPolicy {
	private MissionFallbackPolicy() {
	}

	static Action choose(boolean wingMissionAvailable, boolean dockRequestAvailable,
		boolean ownerAvailable) {
		if (wingMissionAvailable) return Action.REJOIN_WING;
		if (dockRequestAvailable) return Action.REQUEST_DOCK;
		if (ownerAvailable) return Action.FOLLOW_OWNER;
		return Action.SAFE_ORBIT;
	}

	enum Action { REJOIN_WING, REQUEST_DOCK, FOLLOW_OWNER, SAFE_ORBIT }
}
