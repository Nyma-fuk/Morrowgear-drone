package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class DockAllocationPolicy {
	static final long PARKED_YIELD_GRACE_TICKS = 200L;
	static final long YIELD_INTERVAL_TICKS = 100L;
	static final int HOLDING_RING_SIZE = 8;
	static final double HOLDING_BASE_RADIUS = 14.0;
	static final double HOLDING_LAYER_RADIUS = 4.0;
	static final double HOLDING_BASE_HEIGHT = 10.0;
	static final double HOLDING_LAYER_HEIGHT = 4.0;

	private DockAllocationPolicy() {
	}

	static boolean replacementRequired(boolean wasDocked, boolean serviceReturn,
		boolean dockMode, boolean holding) {
		return wasDocked || serviceReturn || dockMode || holding;
	}

	static int queuePosition(List<Request> requests, UUID aircraft) {
		if (aircraft == null) return 0;
		Request current = requests.stream().filter(request -> request.aircraft().equals(aircraft))
			.findFirst().orElse(null);
		if (current == null) return 0;
		Comparator<Request> order = Comparator.comparingInt(Request::priority).reversed()
			.thenComparingLong(Request::requestedTick)
			.thenComparing(request -> request.aircraft().toString());
		int ahead = 0;
		for (Request request : requests) if (order.compare(request, current) < 0) ahead++;
		return ahead + 1;
	}

	static int queuePosition(List<Request> requests, UUID aircraft, java.util.Set<UUID> compatible) {
		return queuePosition(requests.stream().filter(request -> compatible.contains(request.aircraft())).toList(), aircraft);
	}

	static boolean mayYield(boolean docked, boolean activeMission, boolean service,
		boolean powerLost, int batteryPercent, long parkedTicks) {
		return docked && !activeMission && !service && !powerLost && batteryPercent >= 80
			&& parkedTicks >= PARKED_YIELD_GRACE_TICKS;
	}

	static boolean yieldTurnAvailable(long tick, long previousTick) {
		return previousTick < 0L || tick >= previousTick
			&& tick - previousTick >= YIELD_INTERVAL_TICKS;
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
