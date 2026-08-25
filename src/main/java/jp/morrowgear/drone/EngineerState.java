package jp.morrowgear.drone;

public enum EngineerState {
	IDLE(0, "IDLE"),
	APPROACH(1, "APPROACH"),
	REPAIRING(2, "REPAIRING"),
	MATERIAL_LOW(3, "MATERIAL LOW");

	private final int id;
	private final String label;

	EngineerState(int id, String label) {
		this.id = id;
		this.label = label;
	}

	public int id() { return id; }
	public String label() { return label; }

	public static EngineerState byId(int id) {
		for (EngineerState state : values()) if (state.id == id) return state;
		return IDLE;
	}
}
