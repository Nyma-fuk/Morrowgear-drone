package jp.morrowgear.drone;

public enum DroneMode {
	STANDBY(0), FOLLOW(1), RETURN(2), ORBIT(3), DOCK(4), WAYPOINT(5);

	private final int id;

	DroneMode(int id) {
		this.id = id;
	}

	public int id() {
		return id;
	}

	public static DroneMode byId(int id) {
		for (DroneMode mode : values()) {
			if (mode.id == id) return mode;
		}
		return STANDBY;
	}

	public static DroneMode fromAction(String action) {
		return switch (action) {
			case "follow" -> FOLLOW;
			case "return" -> RETURN;
			case "orbit" -> ORBIT;
			case "dock" -> DOCK;
			default -> STANDBY;
		};
	}
}
