package jp.morrowgear.drone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
	private static boolean registered;

	private DockAllocationRuntime() {
	}

	static void register() {
		if (registered) return;
		registered = true;
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> CACHE.clear());
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
		if (level != null && owner != null) CACHE.remove(new Key(level, owner));
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
}
