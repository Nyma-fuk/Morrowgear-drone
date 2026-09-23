package jp.morrowgear.drone.carrier;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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

    private CarrierExteriorSnapshot() {}

    static CarrierViewPayload.Terrain terrain(CarrierEntity carrier) {
        if (carrier == null || !(carrier.level() instanceof ServerLevel level)) return CarrierViewPayload.Terrain.EMPTY;
        int centerX = carrier.blockPosition().getX(), centerZ = carrier.blockPosition().getZ();
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
        return new CarrierViewPayload.Terrain(level.dimension().identifier().toString(), centerX, centerZ,
            TERRAIN_SPACING, TERRAIN_SIDE, TERRAIN_SIDE, colors);
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
}
