package jp.morrowgear.drone;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

final class ContainerAccessCoordinator<K> {
	private final long leaseTicks;
	private final long waiterTicks;
	private final Map<K, Lane> lanes = new HashMap<>();

	ContainerAccessCoordinator(long leaseTicks, long waiterTicks) {
		this.leaseTicks = leaseTicks;
		this.waiterTicks = waiterTicks;
	}

	synchronized Grant request(K key, UUID unitId, long tick) {
		Lane lane = lanes.computeIfAbsent(key, ignored -> new Lane());
		prune(lane, tick);
		if (unitId.equals(lane.holder)) {
			lane.holderSeenTick = tick;
			return new Grant(true, 0);
		}

		Waiter waiter = find(lane, unitId);
		if (waiter == null) {
			waiter = new Waiter(unitId, tick);
			lane.waiters.addLast(waiter);
		} else {
			waiter.seenTick = tick;
		}

		if (lane.holder == null && lane.waiters.peekFirst() == waiter) {
			lane.waiters.removeFirst();
			lane.holder = unitId;
			lane.holderSeenTick = tick;
			return new Grant(true, 0);
		}
		return new Grant(false, position(lane, unitId));
	}

	synchronized void release(K key, UUID unitId) {
		Lane lane = lanes.get(key);
		if (lane == null) return;
		if (unitId.equals(lane.holder)) lane.holder = null;
		lane.waiters.removeIf(waiter -> waiter.unitId.equals(unitId));
		if (lane.holder == null && lane.waiters.isEmpty()) lanes.remove(key);
	}

	synchronized void releaseEverywhere(UUID unitId) {
		Iterator<Map.Entry<K, Lane>> entries = lanes.entrySet().iterator();
		while (entries.hasNext()) {
			Lane lane = entries.next().getValue();
			if (unitId.equals(lane.holder)) lane.holder = null;
			lane.waiters.removeIf(waiter -> waiter.unitId.equals(unitId));
			if (lane.holder == null && lane.waiters.isEmpty()) entries.remove();
		}
	}

	synchronized int laneCount() {
		return lanes.size();
	}

	private void prune(Lane lane, long tick) {
		if (lane.holder != null && tick - lane.holderSeenTick > leaseTicks) lane.holder = null;
		lane.waiters.removeIf(waiter -> tick - waiter.seenTick > waiterTicks);
	}

	private static Waiter find(Lane lane, UUID unitId) {
		for (Waiter waiter : lane.waiters) if (waiter.unitId.equals(unitId)) return waiter;
		return null;
	}

	private static int position(Lane lane, UUID unitId) {
		int position = 1;
		for (Waiter waiter : lane.waiters) {
			if (waiter.unitId.equals(unitId)) return position;
			position++;
		}
		return position;
	}

	record Grant(boolean granted, int queuePosition) {
	}

	private static final class Lane {
		private UUID holder;
		private long holderSeenTick;
		private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
	}

	private static final class Waiter {
		private final UUID unitId;
		private long seenTick;

		private Waiter(UUID unitId, long seenTick) {
			this.unitId = unitId;
			this.seenTick = seenTick;
		}
	}
}
