package jp.morrowgear.drone;

/** Pure cadence rule for order-independent supply-network evaluation. */
final class SupplyNetworkSchedulePolicy {
	static final int PERIOD_TICKS = 20;

	private SupplyNetworkSchedulePolicy() {}

	static boolean shouldEvaluate(long tick, long lastTick, boolean requested) {
		return tick != lastTick && (requested || Math.floorMod(tick, PERIOD_TICKS) == 0);
	}
}
