package jp.morrowgear.drone;

final class SalvageRecoveryPolicy {
	static final int FLIGHT_READY_PERCENT = 40;
	static final float HEALTH_READY_FRACTION = 0.60f;
	static final int SUBSYSTEM_READY = 600;
	static final int FLIGHT_CHARGE_PER_TICK = 12;

	private SalvageRecoveryPolicy() {
	}

	static int flightTarget(BatteryTier tier) {
		return tier.storedForPercent(FLIGHT_READY_PERCENT);
	}

	static boolean serviceTickRequired(SalvageState state, boolean targetAvailable) {
		return targetAvailable && (state == SalvageState.DELIVER || state == SalvageState.SERVICE);
	}

	static boolean ready(int flightPower, BatteryTier tier, float health, float maxHealth,
		int propulsion, int sensor, int payload) {
		return flightPower >= flightTarget(tier)
			&& maxHealth > 0.0f && health / maxHealth >= HEALTH_READY_FRACTION
			&& propulsion >= SUBSYSTEM_READY && sensor >= SUBSYSTEM_READY
			&& payload >= SUBSYSTEM_READY;
	}
}
