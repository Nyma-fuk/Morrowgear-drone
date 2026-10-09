package jp.morrowgear.drone;

import java.util.Map;
import java.util.WeakHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;

final class MissileDetonationRuntime {
	static final int MAX_DETONATIONS_PER_LEVEL_TICK = 8;
	private static final Map<ServerLevel, TickBudget> LEVELS = new WeakHashMap<>();
	private static boolean registered;

	private MissileDetonationRuntime() {}

	static synchronized void register() {
		if (registered) return;
		registered = true;
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			synchronized (MissileDetonationRuntime.class) { LEVELS.clear(); }
		});
	}

	static synchronized boolean tryAcquire(ServerLevel level) {
		return LEVELS.computeIfAbsent(level, ignored -> new TickBudget(MAX_DETONATIONS_PER_LEVEL_TICK))
			.tryAcquire(level.getGameTime());
	}

	static final class TickBudget {
		private final int limit;
		private long tick = Long.MIN_VALUE;
		private int used;

		TickBudget(int limit) { this.limit = Math.max(1, limit); }

		boolean tryAcquire(long now) {
			if (tick != now) {
				tick = now;
				used = 0;
			}
			if (used >= limit) return false;
			used++;
			return true;
		}

		int used() { return used; }
	}
}
