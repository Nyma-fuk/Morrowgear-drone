package jp.morrowgear.drone;

import java.util.Arrays;

public enum DroneRole {
	FIELD("field", "FIELD"),
	SCOUT("scout", "SCOUT"),
	CARGO("cargo", "CARGO"),
	ENGINEER("engineer", "ENGINEER"),
	SECURITY("security", "SECURITY"),
	SALVAGE("salvage", "SALVAGE");

	private final String id;
	private final String displayName;

	DroneRole(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public static DroneRole byId(String id) {
		return Arrays.stream(values()).filter(role -> role.id.equalsIgnoreCase(id))
			.findFirst().orElse(FIELD);
	}
}
