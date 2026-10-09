package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Reference-counted world lights avoid an entity search whenever an emitter moves. */
final class ManagedLightRuntime {
	private static final Map<Key, Integer> REFERENCES = new HashMap<>();
	private static boolean registered;

	private ManagedLightRuntime() {}

	static void register() {
		if (registered) return;
		registered = true;
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> REFERENCES.clear());
	}

	static boolean acquire(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) return false;
		if (!level.getBlockState(pos).isAir() && !level.getBlockState(pos).is(Blocks.LIGHT)) return false;
		Key key = new Key(level, pos.asLong());
		int references = REFERENCES.merge(key, 1, Integer::sum);
		if (references == 1 && !level.getBlockState(pos).is(Blocks.LIGHT))
			level.setBlockAndUpdate(pos, Blocks.LIGHT.defaultBlockState());
		return true;
	}

	static void release(ServerLevel level, BlockPos pos) {
		if (level == null || pos == null) return;
		Key key = new Key(level, pos.asLong());
		Integer references = REFERENCES.get(key);
		if (references != null && references > 1) {
			REFERENCES.put(key, references - 1);
			return;
		}
		REFERENCES.remove(key);
		if (level.getBlockState(pos).is(Blocks.LIGHT)) level.removeBlock(pos, false);
	}

	static int phase(java.util.UUID id, int interval) {
		return Math.floorMod(id == null ? 0 : id.hashCode(), Math.max(1, interval));
	}

	private static final class Key {
		private final ServerLevel level;
		private final long pos;
		private Key(ServerLevel level, long pos) { this.level = level; this.pos = pos; }
		@Override public boolean equals(Object other) {
			return other instanceof Key key && level == key.level && pos == key.pos;
		}
		@Override public int hashCode() {
			return 31 * System.identityHashCode(level) + Long.hashCode(pos);
		}
	}
}
