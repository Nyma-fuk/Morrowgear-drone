package jp.morrowgear.drone;

final class WeaponModulePolicy {
	private WeaponModulePolicy() {
	}

	static Result evaluate(boolean securityRole, boolean docked, boolean creative,
		boolean requestedModuleAvailable, SecurityLoadout current, SecurityLoadout requested) {
		if (!securityRole) return Result.NOT_SECURITY;
		if (current == requested) return Result.UNCHANGED;
		if (!docked) return Result.NOT_DOCKED;
		if (requested == null || requested == SecurityLoadout.AUTO || requested == SecurityLoadout.UNARMED)
			return Result.PHYSICAL_MODULE_REQUIRED;
		if (!creative && !requestedModuleAvailable) return Result.MODULE_MISSING;
		return Result.ALLOWED;
	}

	enum Result {
		ALLOWED,
		UNCHANGED,
		NOT_SECURITY,
		NOT_DOCKED,
		MODULE_MISSING,
		PHYSICAL_MODULE_REQUIRED
	}
}
