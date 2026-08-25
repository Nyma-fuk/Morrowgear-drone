package jp.morrowgear.drone;

final class RoleModulePolicy {
	private RoleModulePolicy() {
	}

	static Result evaluate(boolean docked, boolean creative, boolean requestedModuleAvailable,
		DroneRole currentRole, DroneRole requestedRole) {
		if (currentRole == requestedRole) return Result.UNCHANGED;
		if (!docked) return Result.NOT_DOCKED;
		if (!creative && requestedRole != DroneRole.FIELD && !requestedModuleAvailable) return Result.MODULE_MISSING;
		return Result.ALLOWED;
	}

	enum Result {
		ALLOWED,
		UNCHANGED,
		NOT_DOCKED,
		MODULE_MISSING
	}
}
