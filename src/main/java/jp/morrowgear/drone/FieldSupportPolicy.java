package jp.morrowgear.drone;

final class FieldSupportPolicy {
	private static final int EMERGENCY_TRANSFER_THRESHOLD = 20;
	private static final int DONOR_RESERVE = 40;

	private FieldSupportPolicy() {
	}

	static boolean mayTransfer(int donorPercent, int receiverPercent) {
		return donorPercent > DONOR_RESERVE
			&& receiverPercent > 0 && receiverPercent < EMERGENCY_TRANSFER_THRESHOLD;
	}

	static FieldOperationState state(boolean scanComplete, boolean engineersComplete,
		boolean cargoComplete) {
		if (!scanComplete) return FieldOperationState.FIELD_DATA_RELAY;
		if (!engineersComplete || !cargoComplete) return FieldOperationState.FIELD_MISSION_SUPPORT;
		return FieldOperationState.COMPLETE;
	}
}
