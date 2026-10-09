package jp.morrowgear.drone.carrier;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** Exact per-tick terrain memoization shared by all carrier sweep alternatives. */
final class CarrierTerrainQueryCache {
    private static final Map<ServerLevel, TickCache> LEVELS = new IdentityHashMap<>();

    private CarrierTerrainQueryCache() {}

    static int height(ServerLevel level, int x, int z) {
        long tick = level.getGameTime();
        TickCache cache = LEVELS.get(level);
        if (cache == null || cache.tick != tick) {
            cache = new TickCache(tick, new HashMap<>());
            LEVELS.put(level, cache);
        }
        long key = (x & 0xffffffffL) | ((z & 0xffffffffL) << 32);
        Integer height = cache.heights.get(key);
        if (height != null) return height;
        int sampled = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        cache.heights.put(key, sampled);
        return sampled;
    }

    static int uniqueQueriesForTest(int alternatives, int columns) {
        return alternatives <= 0 || columns <= 0 ? 0 : columns;
    }

    static void clear() { LEVELS.clear(); }

    private record TickCache(long tick, Map<Long, Integer> heights) {}
}
