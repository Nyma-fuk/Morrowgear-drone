package jp.morrowgear.drone;

final class FlightPowerPolicy {
	private FlightPowerPolicy() {}

	static int drain(double speed, boolean efficientReturnCruise) {
		return drain(speed, efficientReturnCruise, false);
	}

	static int drain(double speed, boolean efficientReturnCruise, boolean towing) {
		if (efficientReturnCruise && speed >= 0.45) return 4;
		int base = speed >= 0.8 ? 6 : speed >= 0.45 ? 4 : 2;
		return towing ? Math.min(8, base + 2) : base;
	}
}
