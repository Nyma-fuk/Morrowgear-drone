package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Coalesces identical operation-ticket refreshes without changing ticket radius or lifetime. */
final class OperationChunkLeaseRuntime {
	private static final Map<Key, Long> REFRESHED = new HashMap<>();
	private static boolean registered;

	private OperationChunkLeaseRuntime() {}

	static void register() {
		if (registered) return;
		registered = true;
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> REFRESHED.clear());
	}

	static void refresh(ServerLevel level, ChunkPos chunk) {
		refresh(level, chunk, RemoteOperationPolicy.ENTITY_TICKING_TICKET_RADIUS);
	}

	static void refreshDataLink(ServerLevel level, ChunkPos chunk) {
		refresh(level, chunk, RemoteOperationPolicy.DATA_LINK_TICKET_RADIUS);
	}

	private static void refresh(ServerLevel level, ChunkPos chunk, int radius) {
		long tick = level.getGameTime();
		long chunkKey = (chunk.x() & 0xffffffffL) | ((chunk.z() & 0xffffffffL) << 32);
		Key key = new Key(level, chunkKey, radius);
		Long previous = REFRESHED.put(key, tick);
		if (previous != null && previous == tick) return;
		level.getChunkSource().addTicketWithRadius(MorrowgearDrone.DRONE_OPERATION_TICKET,
			chunk, radius);
		if ((tick & 63L) == 0L) REFRESHED.entrySet().removeIf(entry -> entry.getKey().level == level
			&& tick - entry.getValue() > 100L);
	}

	private static final class Key {
		private final ServerLevel level;
		private final long chunk;
		private final int radius;
		private Key(ServerLevel level, long chunk, int radius) {
			this.level = level;
			this.chunk = chunk;
			this.radius = radius;
		}
		@Override public boolean equals(Object other) {
			return other instanceof Key key && level == key.level && chunk == key.chunk && radius == key.radius;
		}
		@Override public int hashCode() {
			return 31 * (31 * System.identityHashCode(level) + Long.hashCode(chunk)) + radius;
		}
	}
}
