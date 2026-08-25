package jp.morrowgear.drone;

final class SolarMissionPolicy {
	static final int NORMAL_DIVERSION = 32;
	static final int NORMAL_TARGET = 85;
	static final int ROUTE_TARGET = 90;
	static final int SALVAGE_OUTBOUND_TARGET = 92;
	static final int SALVAGE_TOW_TARGET = 95;

	private SolarMissionPolicy() {}

	static int diversionThreshold(boolean patrolRoute, boolean salvageOutbound,
		boolean salvageTowing, double remainingDistance) {
		double distance = Math.max(0.0, remainingDistance);
		if (salvageTowing) return Math.min(70, Math.max(55, 45 + (int)Math.ceil(distance / 80.0)));
		if (salvageOutbound) return Math.min(62, Math.max(45, 38 + (int)Math.ceil(distance / 96.0)));
		if (patrolRoute) return Math.min(50, Math.max(38, 32 + (int)Math.ceil(distance / 128.0)));
		return NORMAL_DIVERSION;
	}

	static int chargeTarget(boolean patrolRoute, boolean salvageOutbound, boolean salvageTowing) {
		if (salvageTowing) return SALVAGE_TOW_TARGET;
		if (salvageOutbound) return SALVAGE_OUTBOUND_TARGET;
		return patrolRoute ? ROUTE_TARGET : NORMAL_TARGET;
	}

	static boolean baseReturnRequired(DroneServicePolicy.Need need, boolean solarActive) {
		DroneServicePolicy.Need reason = need == null ? DroneServicePolicy.Need.NONE : need;
		return reason != DroneServicePolicy.Need.NONE
			&& !(solarActive && reason == DroneServicePolicy.Need.FLIGHT_POWER);
	}

	static boolean freezeMissionProgress(boolean solarActive) {
		return solarActive;
	}
}
