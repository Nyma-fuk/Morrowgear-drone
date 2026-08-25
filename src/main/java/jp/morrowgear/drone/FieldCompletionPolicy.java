package jp.morrowgear.drone;

final class FieldCompletionPolicy {
	private FieldCompletionPolicy() {
	}

	static Action action(boolean allAssignedUnitsComplete, boolean hasDock) {
		if (!allAssignedUnitsComplete) return Action.HOLD;
		return hasDock ? Action.DOCK : Action.RETURN_TO_OWNER;
	}

	enum Action {
		HOLD,
		DOCK,
		RETURN_TO_OWNER
	}
}
