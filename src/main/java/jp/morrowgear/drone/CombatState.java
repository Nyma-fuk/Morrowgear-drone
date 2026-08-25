package jp.morrowgear.drone;

public enum CombatState {
	IDLE(0, "IDLE"),
	FLARE_ENTRY(1, "COMBAT ENTRY"),
	GUN_RUN(2, "GUN RUN"),
	LASER_CHARGE(3, "LASER CHARGE"),
	LASER_FIRE(4, "LASER FIRE"),
	MISSILE_APPROACH(5, "MISSILE APPROACH"),
	MISSILE_EGRESS(6, "MISSILE EGRESS"),
	RAM_APPROACH(7, "EMERGENCY RAM"),
	REJOIN(8, "COMBAT REJOIN");

	private final int id;
	private final String label;

	CombatState(int id, String label) {
		this.id = id;
		this.label = label;
	}

	public int id() { return id; }
	public String label() { return label; }
	public boolean showsCombatTrail() { return this == FLARE_ENTRY; }

	public boolean active() {
		return this != IDLE && this != REJOIN;
	}

	public boolean controlsFlight() {
		return this != IDLE;
	}

	public boolean rejoining() {
		return this == REJOIN;
	}

	public static CombatState byId(int id) {
		for (CombatState state : values()) if (state.id == id) return state;
		return IDLE;
	}
}
