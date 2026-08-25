package jp.morrowgear.drone;

public final class DockServicePolicy {
	static final int FLIGHT_CHARGE_PER_TICK = 5;
	static final int WEAPON_CHARGE_PER_TICK = 8;
	static final int AUTOCANNON_REARM_INTERVAL = 2;
	static final int MISSILE_REARM_INTERVAL = 40;
	static final int REPAIR_INTERVAL = 20;

	private DockServicePolicy() {}

	static boolean needsRepair(float health, float maximumHealth) {
		return health + 0.01f < maximumHealth;
	}

	public static boolean shouldLoadNextPowerCell(int storedPower, int minimumRequired) {
		return storedPower < Math.max(1, minimumRequired);
	}

	static String waitingStatus(DroneServicePolicy.Need need, boolean powerAvailable,
		boolean repairAvailable, boolean ammunitionAvailable) {
		if (need == DroneServicePolicy.Need.DAMAGE && !repairAvailable) return "DOCK WAIT / REPAIR MATERIAL";
		if (need == DroneServicePolicy.Need.MAINTENANCE && !repairAvailable) return "DOCK WAIT / MAINTENANCE MATERIAL";
		if ((need == DroneServicePolicy.Need.AUTOCANNON_AMMO
			|| need == DroneServicePolicy.Need.MISSILE_AMMO
			|| need == DroneServicePolicy.Need.WEAPON_REARM) && !ammunitionAvailable)
			return "DOCK WAIT / AMMUNITION";
		if (!powerAvailable) return "DOCK WAIT / POWER SUPPLY";
		return "DOCK SERVICE / IN PROGRESS";
	}

	static String readinessStatus(String base, int flightPower, int weaponPower,
		int requiredPower, int ammunition, int requiredAmmunition) {
		String status = base == null || base.isBlank() ? "DOCK SERVICE" : base;
		return status + " / FLT " + flightPower + "/" + requiredPower
			+ " WPN " + weaponPower + "/" + requiredPower
			+ (requiredAmmunition > 0 ? " AMMO " + ammunition + "/" + requiredAmmunition : "");
	}

	static boolean completionResourceExhausted(boolean powerRequired, boolean powerAvailable,
		boolean ammunitionRequired, boolean ammunitionAvailable) {
		return powerRequired && !powerAvailable
			|| ammunitionRequired && !ammunitionAvailable;
	}
}
