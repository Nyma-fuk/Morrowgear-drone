package jp.morrowgear.drone;

public final class PowerLostBeaconLeasePolicy {
	public static final long HEARTBEAT_TICKS = 100;
	public static final long EXPIRY_TICKS = 260;

	private PowerLostBeaconLeasePolicy() {
	}

	public static boolean isCurrent(long detectedTick, long currentTick) {
		return currentTick < detectedTick || currentTick - detectedTick <= EXPIRY_TICKS;
	}
}
