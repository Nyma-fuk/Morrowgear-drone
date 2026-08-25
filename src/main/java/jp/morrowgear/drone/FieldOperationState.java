package jp.morrowgear.drone;

public enum FieldOperationState {
	IDLE(0, "IDLE"),
	SCANNING(1, "SCANNING"),
	WAITING_DATA(2, "WAITING DATA"),
	TRANSIT(3, "TRANSIT"),
	WORKING(4, "WORKING"),
	COLLECTING(5, "COLLECTING"),
	DELIVERING(6, "DELIVERING"),
	PLANTING(7, "PLANTING"),
	COMPLETE(8, "COMPLETE"),
	BLOCKED(9, "BLOCKED"),
	SCOUT_SURVEY(10, "SCOUT / AREA SURVEY"),
	SCOUT_OVERWATCH(11, "SCOUT / WORK ORBIT"),
	SCOUT_CARGO_ESCORT(12, "SCOUT / CARGO ESCORT"),
	ENGINEER_WAITING_INTEL(13, "ENGINEER / DATA LINK"),
	ENGINEER_LOCAL_SURVEY(14, "ENGINEER / LOCAL SURVEY"),
	CARGO_HOLD(15, "CARGO / HOLDING ORBIT"),
	GUARD_WORK_ESCORT(16, "GUARD / WORK ESCORT"),
	GUARD_CARGO_ESCORT(17, "GUARD / CARGO ESCORT"),
	ENGINEER_RECOVERY_OVERWATCH(18, "ENGINEER / RECOVERY OVERWATCH"),
	FIELD_DATA_RELAY(19, "FIELD / DATA RELAY"),
	FIELD_MISSION_SUPPORT(20, "FIELD / MISSION SUPPORT");

	private final int id;
	private final String label;

	FieldOperationState(int id, String label) {
		this.id = id;
		this.label = label;
	}

	public int id() { return id; }
	public String label() { return label; }

	public static FieldOperationState byId(int id) {
		for (FieldOperationState state : values()) if (state.id == id) return state;
		return IDLE;
	}
}
