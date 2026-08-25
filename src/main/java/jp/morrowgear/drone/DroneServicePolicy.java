package jp.morrowgear.drone;

import net.minecraft.util.Mth;

final class DroneServicePolicy {
	static final int NORMAL_SORTIE_POWER = 90;
	static final float CRITICAL_HEALTH_RATIO = 0.25f;
	static final float MINIMUM_SORTIE_HEALTH_RATIO = 0.30f;

	private DroneServicePolicy() {}

	static int flightReserveForDock(double distance) {
		return Mth.clamp(12 + (int)Math.ceil(Math.max(0.0, distance) / 32.0), 12, 40);
	}

	static Need serviceNeed(float healthRatio, int flightPower, double dockDistance) {
		if (healthRatio <= CRITICAL_HEALTH_RATIO) return Need.DAMAGE;
		if (flightPower <= flightReserveForDock(dockDistance)) return Need.FLIGHT_POWER;
		return Need.NONE;
	}

	static Need subsystemNeed(int propulsion, int sensor, int payload) {
		return DroneSubsystemPolicy.serviceRequired(propulsion, sensor, payload) ? Need.MAINTENANCE : Need.NONE;
	}

	static boolean nonCombatSortieReady(float healthRatio, int flightPower) {
		return healthRatio >= MINIMUM_SORTIE_HEALTH_RATIO && flightPower >= NORMAL_SORTIE_POWER;
	}

	static Need merge(Need current, Need incoming) {
		Need left = current == null ? Need.NONE : current;
		Need right = incoming == null ? Need.NONE : incoming;
		return priority(right) > priority(left) ? right : left;
	}

	private static int priority(Need need) {
		return switch (need) {
			case NONE -> 0;
			case WEAPON_POWER, WEAPON_REARM, AUTOCANNON_AMMO, MISSILE_AMMO, LASER_HEAT, MAINTENANCE -> 1;
			case FLIGHT_POWER -> 2;
			case DAMAGE -> 3;
		};
	}

	enum Need {
		NONE(""),
		FLIGHT_POWER("FLIGHT RESERVE / DOCK RTB"),
		WEAPON_POWER("WEAPON SERVICE / DOCK RTB"),
		WEAPON_REARM("WEAPONS DEPLETED / DOCK RTB"),
		AUTOCANNON_AMMO("AUTOCANNON REARM / DOCK RTB"),
		MISSILE_AMMO("MISSILE REARM / DOCK RTB"),
		LASER_HEAT("LASER COOLING / DOCK RTB"),
		MAINTENANCE("SUBSYSTEM SERVICE / DOCK RTB"),
		DAMAGE("DAMAGE CRITICAL / DOCK RTB");

		private final String status;

		Need(String status) {
			this.status = status;
		}

		String status() {
			return status;
		}

		static Need byName(String name) {
			for (Need need : values()) if (need.name().equals(name)) return need;
			return NONE;
		}
	}
}
