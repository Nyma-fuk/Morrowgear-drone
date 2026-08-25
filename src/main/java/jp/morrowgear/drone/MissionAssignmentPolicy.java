package jp.morrowgear.drone;

public final class MissionAssignmentPolicy {
	private MissionAssignmentPolicy() {
	}

	public static boolean allows(DroneRole role, MissionKind mission) {
		if (role == null || mission == null) return false;
		return switch (mission) {
			case GENERAL_FLIGHT -> true;
			case FIELD_OPERATION -> role == DroneRole.FIELD || role == DroneRole.SCOUT
				|| role == DroneRole.CARGO || role == DroneRole.ENGINEER
				|| role == DroneRole.SECURITY;
			case CARGO_ROUTE -> role == DroneRole.CARGO;
			case ENGINEERING_SUPPORT -> role == DroneRole.ENGINEER;
			case SECURITY_PATROL -> role == DroneRole.SECURITY;
			case SALVAGE_RECOVERY -> role == DroneRole.SALVAGE;
		};
	}

	public enum MissionKind {
		GENERAL_FLIGHT,
		FIELD_OPERATION,
		CARGO_ROUTE,
		ENGINEERING_SUPPORT,
		SECURITY_PATROL,
		SALVAGE_RECOVERY
	}
}
