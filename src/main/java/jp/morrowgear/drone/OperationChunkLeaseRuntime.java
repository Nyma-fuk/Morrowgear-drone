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
		long tick = level.getGameTime();
		long chunkKey = (chunk.x() & 0xffffffffL) | ((chunk.z() & 0xffffffffL) << 32);
		Key key = new Key(level, chunkKey);
		Long previous = REFRESHED.put(key, tick);
		if (previous != null && previous == tick) return;
		level.getChunkSource().addTicketWithRadius(MorrowgearDrone.DRONE_OPERATION_TICKET,
			chunk, RemoteOperationPolicy.ENTITY_TICKING_TICKET_RADIUS);
		if ((tick & 63L) == 0L) REFRESHED.entrySet().removeIf(entry -> entry.getKey().level == level
			&& tick - entry.getValue() > 100L);
	}

	private static final class Key {
		private final ServerLevel level;
		private final long chunk;
		private Key(ServerLevel level, long chunk) { this.level = level; this.chunk = chunk; }
		@Override public boolean equals(Object other) {
			return other instanceof Key key && level == key.level && chunk == key.chunk;
		}
		@Override public int hashCode() {
			return 31 * System.identityHashCode(level) + Long.hashCode(chunk);
		}
	}
}
