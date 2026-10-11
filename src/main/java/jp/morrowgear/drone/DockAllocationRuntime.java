package jp.morrowgear.drone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.function.BiPredicate;
import jp.morrowgear.drone.block.DockBlockEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class DockAllocationRuntime {
	private static final long REFRESH_TICKS = 20L;
	private static final int CACHE_LIMIT = 256;
	private static final Map<Key, Snapshot> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, Snapshot> eldest) {
			return size() > CACHE_LIMIT;
		}
	};
	private static final Map<Key, CompatibilitySnapshot> COMPATIBILITY = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, CompatibilitySnapshot> eldest) {
			return size() > CACHE_LIMIT;
		}
	};
	private static final Map<Key, Long> LAST_YIELD = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, Long> eldest) {
			return size() > CACHE_LIMIT;
		}
	};
	private static boolean registered;

	private DockAllocationRuntime() {
	}

	static void register() {
		if (registered) return;
		registered = true;
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			CACHE.clear();
			COMPATIBILITY.clear();
			LAST_YIELD.clear();
		});
	}

	static List<DockBlockEntity> docks(ServerLevel level, ServerPlayer owner) {
		Key key = new Key(level, owner.getUUID());
		long tick = level.getGameTime();
		Snapshot cached = CACHE.get(key);
		if (cached != null && fresh(tick, cached.tick())
			&& cached.docks().stream().noneMatch(DockBlockEntity::isRemoved)) return cached.docks();
		List<DockBlockEntity> docks = List.copyOf(MorrowgearDrone.findDocks(level, owner, 256));
		CACHE.put(key, new Snapshot(tick, docks));
		return docks;
	}

	static void invalidate(ServerLevel level, UUID owner) {
		if (level != null && owner != null) {
			Key key = new Key(level, owner);
			CACHE.remove(key);
			COMPATIBILITY.remove(key);
			LAST_YIELD.remove(key);
		}
	}

	static boolean claimYieldTurn(ServerLevel level, ServerPlayer owner) {
		Key key = new Key(level, owner.getUUID());
		long tick = level.getGameTime();
		long previous = LAST_YIELD.getOrDefault(key, -1L);
		if (!DockAllocationPolicy.yieldTurnAvailable(tick, previous)) return false;
		LAST_YIELD.put(key, tick);
		return true;
	}

	static Set<UUID> compatible(ServerLevel level, ServerPlayer owner, List<DroneEntity> fleet,
		BiPredicate<DroneEntity, DockBlockEntity> predicate) {
		Key key = new Key(level, owner.getUUID());
		long tick = level.getGameTime();
		CompatibilitySnapshot cached = COMPATIBILITY.get(key);
		if (cached != null && cached.tick() == tick) return cached.aircraft();
		List<DockBlockEntity> docks = docks(level, owner);
		Set<UUID> aircraft = new HashSet<>();
		for (DroneEntity drone : fleet) {
			if (docks.stream().anyMatch(dock -> predicate.test(drone, dock))) aircraft.add(drone.getUUID());
		}
		Set<UUID> result = Set.copyOf(aircraft);
		COMPATIBILITY.put(key, new CompatibilitySnapshot(tick, result));
		return result;
	}

	static boolean fresh(long tick, long cachedTick) {
		long age = tick - cachedTick;
		return age >= 0L && age < REFRESH_TICKS;
	}

	private static final class Key {
		private final ServerLevel level;
		private final UUID owner;

		private Key(ServerLevel level, UUID owner) {
			this.level = level;
			this.owner = owner;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Key key && level == key.level && owner.equals(key.owner);
		}

		@Override
		public int hashCode() {
			return 31 * System.identityHashCode(level) + owner.hashCode();
		}
	}
	private record Snapshot(long tick, List<DockBlockEntity> docks) {}
	private record CompatibilitySnapshot(long tick, Set<UUID> aircraft) {}
}
