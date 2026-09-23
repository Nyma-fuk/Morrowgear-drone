package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class DockAllocationPolicy {
	static final long PARKED_YIELD_GRACE_TICKS = 200L;
	static final int HOLDING_RING_SIZE = 8;
	static final double HOLDING_BASE_RADIUS = 14.0;
	static final double HOLDING_LAYER_RADIUS = 4.0;
	static final double HOLDING_BASE_HEIGHT = 10.0;
	static final double HOLDING_LAYER_HEIGHT = 4.0;

	private DockAllocationPolicy() {
	}

	static int queuePosition(List<Request> requests, UUID aircraft) {
		if (aircraft == null) return 0;
		List<Request> ordered = requests.stream().sorted(Comparator
			.comparingInt(Request::priority).reversed()
			.thenComparingLong(Request::requestedTick)
			.thenComparing(request -> request.aircraft().toString())).toList();
		for (int index = 0; index < ordered.size(); index++) {
			if (ordered.get(index).aircraft().equals(aircraft)) return index + 1;
		}
		return 0;
	}

	static boolean mayYield(boolean docked, boolean activeMission, boolean service,
		boolean powerLost, int batteryPercent, long parkedTicks) {
		return docked && !activeMission && !service && !powerLost && batteryPercent >= 80
			&& parkedTicks >= PARKED_YIELD_GRACE_TICKS;
	}

	static HoldingSlot holdingSlot(int queuePosition) {
		int index = Math.max(0, queuePosition - 1);
		int layer = index / HOLDING_RING_SIZE;
		int slot = index % HOLDING_RING_SIZE;
		return new HoldingSlot((Math.PI * 2.0 * slot) / HOLDING_RING_SIZE,
			HOLDING_BASE_RADIUS + layer * HOLDING_LAYER_RADIUS,
			HOLDING_BASE_HEIGHT + layer * HOLDING_LAYER_HEIGHT);
	}

	record Request(UUID aircraft, long requestedTick, int priority) {}
	record HoldingSlot(double angle, double radius, double height) {}
}
