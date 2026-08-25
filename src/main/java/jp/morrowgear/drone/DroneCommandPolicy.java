package jp.morrowgear.drone;

import java.util.Set;
import java.util.regex.Pattern;

public final class DroneCommandPolicy {
	public static final int MAX_ACTION_LENGTH = 256;
	public static final int MAX_MISSION_SIZE = 128;
	public static final int MAX_MISSION_ID_LENGTH = 96;
	private static final Pattern MISSION_ID = Pattern.compile("[A-Za-z0-9._#-]{1," + MAX_MISSION_ID_LENGTH + "}");
	private static final Set<String> SIMPLE_ACTIONS = Set.of("standby", "return", "dock", "orbit", "decommission");

	private DroneCommandPolicy() {
	}

	public static boolean acceptablePayload(String action) {
		return action != null && !action.isBlank() && action.length() <= MAX_ACTION_LENGTH;
	}

	public static boolean isSimpleAction(String action) {
		return SIMPLE_ACTIONS.contains(action);
	}

	public static boolean validMission(String missionId, int expected, int index) {
		return missionId != null && MISSION_ID.matcher(missionId).matches()
			&& expected >= 1 && expected <= MAX_MISSION_SIZE
			&& index >= 0 && index < expected;
	}

	public static boolean validFieldRadius(FieldOperationType type, int radius) {
		if (type == null) return false;
		return switch (type) {
			case ORE -> radius >= 12 && radius <= 48;
			case FORESTRY -> radius >= 6 && radius <= 24;
			case EXCAVATE -> radius >= 2 && radius <= 8;
			case NONE -> false;
		};
	}

	public static boolean validSecurityRadius(int radius) {
		return radius >= 6 && radius <= 32;
	}
}
