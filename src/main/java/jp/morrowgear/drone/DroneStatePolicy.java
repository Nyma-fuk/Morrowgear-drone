package jp.morrowgear.drone;

public final class DroneStatePolicy {
	private DroneStatePolicy() {
	}

	public static int battery(int value) {
		return Math.max(0, Math.min(1000, value));
	}

	public static int missionExpected(int value) {
		return Math.max(1, Math.min(DroneCommandPolicy.MAX_MISSION_SIZE, value));
	}

	public static int missionIndex(int value, int expected) {
		return Math.max(0, Math.min(value, missionExpected(expected) - 1));
	}

	public static String group(String value) {
		return value != null && value.matches("[A-Z0-9_-]{1,24}") ? value : "ALPHA";
	}

	public static int fieldRadius(FieldOperationType type, int value) {
		if (type == null) return 0;
		return switch (type) {
			case ORE -> Math.max(12, Math.min(64, value));
			case FORESTRY -> Math.max(6, Math.min(40, value));
			case EXCAVATE -> Math.max(2, Math.min(8, value));
			case NONE -> 0;
		};
	}

	public static int securityRadius(int value) {
		return Math.max(6, Math.min(64, value));
	}
}
