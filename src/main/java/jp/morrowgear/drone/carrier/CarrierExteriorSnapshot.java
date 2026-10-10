package jp.morrowgear.drone.carrier;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.WeakHashMap;
import jp.morrowgear.drone.DroneEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** Small read-only exterior snapshot used by carrier controls while the player is in the cabin. */
final class CarrierExteriorSnapshot {
    static final int TERRAIN_SIDE = 49;
    static final int TERRAIN_SPACING = 4;
    static final int CANDIDATE_RADIUS = 64;
    static final int MAX_CANDIDATES = 32;
    static final int CONTAINER_RADIUS = 256;
    static final int MAX_CONTAINERS = 8;
    static final int TERRAIN_CACHE_TICKS = 40;
    private static final Map<CarrierEntity, CachedTerrain> TERRAIN_CACHE = new WeakHashMap<>();

    private CarrierExteriorSnapshot() {}

    static CarrierViewPayload.Terrain terrain(CarrierEntity carrier) {
        if (carrier == null || !(carrier.level() instanceof ServerLevel level)) return CarrierViewPayload.Terrain.EMPTY;
        int centerX = carrier.blockPosition().getX(), centerZ = carrier.blockPosition().getZ();
        CachedTerrain cached = TERRAIN_CACHE.get(carrier);
        long now = level.getGameTime();
        if (cached != null && now >= cached.tick && now - cached.tick < TERRAIN_CACHE_TICKS
            && cached.centerX == centerX && cached.centerZ == centerZ) return cached.terrain;
        byte[] colors = new byte[TERRAIN_SIDE * TERRAIN_SIDE];
        int half = TERRAIN_SIDE / 2;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int row = 0; row < TERRAIN_SIDE; row++) for (int column = 0; column < TERRAIN_SIDE; column++) {
            int worldX = centerX + (column - half) * TERRAIN_SPACING;
            int worldZ = centerZ + (row - half) * TERRAIN_SPACING;
            var chunk = level.getChunkSource().getChunkNow(Math.floorDiv(worldX, 16), Math.floorDiv(worldZ, 16));
            if (chunk == null) continue;
            int y = surfaceBlockY(chunk.getHeight(Heightmap.Types.WORLD_SURFACE,
                Math.floorMod(worldX, 16), Math.floorMod(worldZ, 16)));
            if (y < level.getMinY()) continue;
            position.set(worldX, y, worldZ);
            colors[row * TERRAIN_SIDE + column] = chunk.getBlockState(position).getMapColor(level, position)
                .getPackedId(MapColor.Brightness.NORMAL);
        }
        CarrierViewPayload.Terrain terrain = new CarrierViewPayload.Terrain(level.dimension().identifier().toString(), centerX, centerZ,
            TERRAIN_SPACING, TERRAIN_SIDE, TERRAIN_SIDE, colors);
        TERRAIN_CACHE.put(carrier, new CachedTerrain(now, centerX, centerZ, terrain));
        return terrain;
    }

    // ChunkAccess already returns the top occupied block; Level#getHeight adds one to that value.
    static int surfaceBlockY(int chunkHeight) { return chunkHeight; }

    static List<CarrierViewPayload.DroneCandidate> candidates(CarrierEntity carrier, UUID owner) {
        if (carrier == null || !(carrier.level() instanceof ServerLevel level)) return List.of();
        double radiusSquared = (double) CANDIDATE_RADIUS * CANDIDATE_RADIUS;
        return level.getEntitiesOfClass(DroneEntity.class, carrier.getBoundingBox().inflate(CANDIDATE_RADIUS),
                drone -> drone.isAlive() && drone.isOwnedBy(owner) && drone.distanceToSqr(carrier) <= radiusSquared)
            .stream().sorted(Comparator.comparing(DroneEntity::unitId)).limit(MAX_CANDIDATES)
            .map(drone -> new CarrierViewPayload.DroneCandidate(drone.getUUID(), drone.unitId())).toList();
    }

    /** Ledger-backed, loaded-only candidates. This never scans blocks or forces chunks to load. */
    static List<CarrierViewPayload.ContainerCandidate> containers(CarrierEntity carrier, UUID owner) {
        if (carrier == null || !(carrier.level() instanceof ServerLevel level)) return List.of();
        String dimension = level.dimension().identifier().toString();
        double radiusSquared = (double) CONTAINER_RADIUS * CONTAINER_RADIUS;
        return CarrierExpansionLedger.get(level.getServer()).entries().values().stream()
            .map(CarrierExpansionLedger.Entry::module)
            .filter(module -> module.owner().equals(owner)
                && module.location() == CarrierExpansionPolicy.Location.PLACED
                && dimension.equals(module.dimension()))
            .map(module -> Map.entry(module, BlockPos.of(module.position())))
            .filter(entry -> entry.getValue().distToCenterSqr(carrier.getX(), carrier.getY(), carrier.getZ()) <= radiusSquared)
            .filter(entry -> level.hasChunkAt(entry.getValue()))
            .filter(entry -> level.getBlockEntity(entry.getValue()) instanceof CarrierExpansionContainer container
                && container.moduleId().equals(entry.getKey().id()) && container.ownerId().equals(owner))
            .sorted(Comparator.comparingDouble(entry -> entry.getValue()
                .distToCenterSqr(carrier.getX(), carrier.getY(), carrier.getZ())))
            .limit(MAX_CONTAINERS)
            .map(entry -> {
                CarrierExpansionContainer container = (CarrierExpansionContainer) level.getBlockEntity(entry.getValue());
                int distance = (int) Math.round(Math.sqrt(entry.getValue()
                    .distToCenterSqr(carrier.getX(), carrier.getY(), carrier.getZ())));
                return new CarrierViewPayload.ContainerCandidate(entry.getKey().id(), entry.getKey().type().ordinal(),
                    entry.getValue(), distance, container.transferReserved());
            }).toList();
    }

    private record CachedTerrain(long tick, int centerX, int centerZ, CarrierViewPayload.Terrain terrain) {}
}
