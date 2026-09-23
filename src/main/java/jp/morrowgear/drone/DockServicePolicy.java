package jp.morrowgear.drone;

import net.minecraft.world.phys.Vec3;

public final class DockServicePolicy {
	static final int FLIGHT_CHARGE_PER_TICK = 5;
	static final int WEAPON_CHARGE_PER_TICK = 8;
	static final int AUTOCANNON_REARM_INTERVAL = 2;
	static final int MISSILE_REARM_INTERVAL = 40;
	static final int REPAIR_INTERVAL = 20;
	static final double SERVICE_HORIZONTAL_RADIUS = 2.75;
	static final double SERVICE_MIN_RELATIVE_Y = -0.5;
	static final double SERVICE_MAX_RELATIVE_Y = 4.5;

	private DockServicePolicy() {}

	/** Large-airframe contact envelope around the Dock center, including the raised touchdown pose. */
	static boolean serviceEnvelope(Vec3 aircraft, Vec3 dockCenter) {
		double horizontal = Math.hypot(aircraft.x - dockCenter.x, aircraft.z - dockCenter.z);
		double relativeY = aircraft.y - dockCenter.y;
		return horizontal <= SERVICE_HORIZONTAL_RADIUS
			&& relativeY >= SERVICE_MIN_RELATIVE_Y && relativeY <= SERVICE_MAX_RELATIVE_Y;
	}

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

	static boolean completionResourceExhausted(boolean flightRequired, boolean flightAvailable,
		boolean weaponRequired, boolean weaponAvailable, boolean ammunitionRequired,
		boolean ammunitionAvailable) {
		return flightRequired && !flightAvailable || weaponRequired && !weaponAvailable
			|| ammunitionRequired && !ammunitionAvailable;
	}

	static boolean normalAmmunitionSupplyAvailable(SecurityLoadout loadout, int gunAmmo,
		int missiles, boolean gunSupply, boolean missileSupply) {
		boolean gunReady = gunAmmo >= (int)Math.ceil(CombatPolicy.GUN_CAPACITY * 0.6);
		boolean missileReady = missiles >= 2;
		return switch (loadout == null ? SecurityLoadout.AUTO : loadout) {
			case AUTO -> (gunReady || gunSupply) && (missileReady || missileSupply);
			case AUTOCANNON -> gunReady || gunSupply;
			case MISSILE -> missileReady || missileSupply;
			case LASER, UNARMED -> true;
		};
	}

	static String waitingStatus(DroneServicePolicy.Need need, boolean flightRequired,
		boolean flightAvailable, boolean weaponRequired, boolean weaponAvailable,
		boolean repairAvailable, boolean ammunitionAvailable) {
		if (need == DroneServicePolicy.Need.DAMAGE && !repairAvailable) return "DOCK WAIT / REPAIR MATERIAL";
		if (need == DroneServicePolicy.Need.MAINTENANCE && !repairAvailable) return "DOCK WAIT / MAINTENANCE MATERIAL";
		if (flightRequired && !flightAvailable) return "DOCK WAIT / FLIGHT POWER";
		if (weaponRequired && !weaponAvailable) return "DOCK WAIT / LASER CELL";
		if (!ammunitionAvailable) return "DOCK WAIT / DEDICATED AMMUNITION";
		return "DOCK SERVICE / IN PROGRESS";
	}
}
