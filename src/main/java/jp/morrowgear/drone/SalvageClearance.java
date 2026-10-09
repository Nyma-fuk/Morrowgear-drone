package jp.morrowgear.drone;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/** Top-down, loaded-only inspection of the load's vertical extraction corridor. */
final class SalvageClearance {
    private SalvageClearance() {}

    static BlockPos obstruction(ServerLevel level, DroneEntity load) {
        AABB box = load.getBoundingBox().inflate(0.65, 0, 0.65);
        int minY = (int)Math.floor(load.getY() + .15);
        BlockPos highest = null;
        for (int x = (int)Math.floor(box.minX); x <= (int)Math.floor(box.maxX); x++) {
            for (int z = (int)Math.floor(box.minZ); z <= (int)Math.floor(box.maxZ); z++) {
                BlockPos column = new BlockPos(x, minY, z);
                if (!level.hasChunkAt(column)) return column;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                for (int y = top; y >= minY; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var state = level.getBlockState(pos);
                    if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) {
                        if (highest == null || y > highest.getY()) highest = pos;
                        break;
                    }
                }
            }
        }
        return highest;
    }
}
