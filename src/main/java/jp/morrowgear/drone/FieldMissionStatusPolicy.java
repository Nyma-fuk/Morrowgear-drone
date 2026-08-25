package jp.morrowgear.drone;

final class FieldMissionStatusPolicy {
	static final long CARGO_QUIET_TICKS = 40L;

	private FieldMissionStatusPolicy() {
	}

	static FieldOperationState scout(boolean scanComplete, boolean engineerPresent,
		boolean engineersComplete, boolean cargoPresent, boolean cargoComplete) {
		if (!scanComplete) return FieldOperationState.SCOUT_SURVEY;
		if (engineerPresent && !engineersComplete) return FieldOperationState.SCOUT_OVERWATCH;
		if (cargoPresent && !cargoComplete) return FieldOperationState.SCOUT_CARGO_ESCORT;
		return FieldOperationState.COMPLETE;
	}

	static FieldOperationState cargo(boolean engineersComplete, boolean workComplete,
		boolean hasGroundItems, boolean carryingCargo, long quietTicks) {
		if (hasGroundItems) return FieldOperationState.COLLECTING;
		if (carryingCargo) return FieldOperationState.DELIVERING;
		return engineersComplete && workComplete && quietTicks >= CARGO_QUIET_TICKS
			? FieldOperationState.COMPLETE : FieldOperationState.CARGO_HOLD;
	}

	static FieldOperationState guard(boolean engineerPresent, boolean engineersComplete,
		boolean cargoPresent, boolean cargoComplete) {
		if (engineerPresent && !engineersComplete) return FieldOperationState.GUARD_WORK_ESCORT;
		if (cargoPresent && !cargoComplete) return FieldOperationState.GUARD_CARGO_ESCORT;
		return FieldOperationState.COMPLETE;
	}
}
