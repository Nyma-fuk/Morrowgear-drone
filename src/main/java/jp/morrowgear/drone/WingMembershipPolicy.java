package jp.morrowgear.drone;

public final class WingMembershipPolicy {
	public static final int MAX_MEMBERS = 8;

	private WingMembershipPolicy() {
	}

	public static Result evaluate(String currentGroup, String targetGroup, int targetSize) {
		if (targetGroup == null || !targetGroup.matches("WING-[A-Z0-9_-]{1,19}")) return Result.INVALID_WING;
		if (targetGroup.equals(currentGroup)) return Result.UNCHANGED;
		if (targetSize < 0) return Result.INVALID_WING;
		if (targetSize >= MAX_MEMBERS) return Result.WING_FULL;
		return Result.ACCEPTED;
	}

	public enum Result {
		ACCEPTED,
		UNCHANGED,
		WING_FULL,
		INVALID_WING
	}
}
