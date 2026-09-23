package jp.morrowgear.drone;

public enum SalvageState {
	IDLE,
	INTERCEPT,
	HOOK,
	HOIST,
	RETURN,
	DELIVER,
	SERVICE;

	public static SalvageState byId(int id) {
		return values()[Math.max(0, Math.min(values().length - 1, id))];
	}
}
