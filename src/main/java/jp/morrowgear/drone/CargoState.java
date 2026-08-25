package jp.morrowgear.drone;

public enum CargoState {
	UNASSIGNED(0, "UNASSIGNED"),
	TO_SOURCE(1, "TO SOURCE"),
	WAIT_SOURCE(2, "WAIT SOURCE"),
	TO_TARGET(3, "TO TARGET"),
	WAIT_TARGET(4, "WAIT TARGET"),
	QUEUE_SOURCE(5, "QUEUE SOURCE"),
	LOADING_SOURCE(6, "LOADING"),
	QUEUE_TARGET(7, "QUEUE TARGET"),
	UNLOADING_TARGET(8, "UNLOADING");

	private final int id;
	private final String label;

	CargoState(int id, String label) {
		this.id = id;
		this.label = label;
	}

	public int id() { return id; }
	public String label() { return label; }
	public boolean usesSource() {
		return this == TO_SOURCE || this == WAIT_SOURCE || this == QUEUE_SOURCE || this == LOADING_SOURCE;
	}
	public boolean queued() { return this == QUEUE_SOURCE || this == QUEUE_TARGET; }
	public boolean servicing() { return this == LOADING_SOURCE || this == UNLOADING_TARGET; }

	public static CargoState byId(int id) {
		for (CargoState state : values()) if (state.id == id) return state;
		return UNASSIGNED;
	}
}
