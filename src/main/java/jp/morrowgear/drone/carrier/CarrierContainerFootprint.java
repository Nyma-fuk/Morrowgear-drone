package jp.morrowgear.drone.carrier;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Maps the approved 5 x 3 x 2.4 model footprint back to its authoritative anchor block entity. */
public final class CarrierContainerFootprint {
    public static final int MIN_X = -2, MAX_X = 2;
    public static final int MIN_Z = -1, MAX_Z = 1;
    public static final int MIN_Y = 0, MAX_Y = 2;
    public static final int ANCHOR_X_INDEX = -MIN_X;
    public static final int ANCHOR_Z_INDEX = -MIN_Z;
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int REBUILDS_PER_TICK = 64;
    private static final Queue<Pending> PENDING = new ConcurrentLinkedQueue<>();
    private static final Set<Pending> PENDING_SET = ConcurrentHashMap.newKeySet();

    private CarrierContainerFootprint() {}

    public static boolean ensure(ServerLevel level, BlockPos anchor) {
        if (!isAnchor(level.getBlockState(anchor)) || CarrierModule.CONTAINER_PART_BLOCK == null) return false;
        boolean complete = true;
        for (int dx = MIN_X; dx <= MAX_X; dx++) for (int dz = MIN_Z; dz <= MAX_Z; dz++)
            for (int dy = MIN_Y; dy <= MAX_Y; dy++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos pos = anchor.offset(dx, dy, dz);
                BlockState present = level.getBlockState(pos);
                if (present.is(CarrierModule.CONTAINER_PART_BLOCK)
                    && anchor.equals(anchorFromPart(pos, present))) continue;
                if (!present.canBeReplaced()) { complete = false; continue; }
                BlockState part = CarrierModule.CONTAINER_PART_BLOCK.defaultBlockState()
                    .setValue(CarrierContainerPartBlock.OFFSET_X, dx - MIN_X)
                    .setValue(CarrierContainerPartBlock.OFFSET_Y, dy)
                    .setValue(CarrierContainerPartBlock.OFFSET_Z, dz - MIN_Z);
                if (!level.setBlock(pos, part, FLAGS)) complete = false;
            }
        return complete;
    }

    public static void ensureAfterLoad(ServerLevel level, BlockPos anchor) {
        Pending pending = new Pending(level, anchor.immutable());
        if (PENDING_SET.add(pending)) PENDING.add(pending);
    }

    /** Runs after chunk post-load work, avoiding a recursive chunk request from BlockEntity#setLevel. */
    public static void tick(MinecraftServer server) {
        for (int rebuilt = 0; rebuilt < REBUILDS_PER_TICK; rebuilt++) {
            Pending pending = PENDING.poll();
            if (pending == null) return;
            PENDING_SET.remove(pending);
            if (pending.level.getServer() != server || !pending.level.hasChunkAt(pending.anchor)) continue;
            if (pending.level.getBlockEntity(pending.anchor) instanceof CarrierExpansionContainer)
                ensure(pending.level, pending.anchor);
        }
    }

    public static void clearPending() { PENDING.clear(); PENDING_SET.clear(); }

    public static BlockPos resolveAnchor(Level level, BlockPos clicked) {
        BlockState state = level.getBlockState(clicked);
        if (isAnchor(state)) return clicked;
        if (!state.is(CarrierModule.CONTAINER_PART_BLOCK)) return null;
        BlockPos anchor = anchorFromPart(clicked, state);
        return isAnchor(level.getBlockState(anchor)) ? anchor : null;
    }

    public static boolean open(ServerPlayer player, BlockPos clicked) {
        BlockPos anchor = resolveAnchor(player.level(), clicked);
        if (anchor == null) return false;
        if (player.level().getBlockEntity(anchor) instanceof CarrierCargoContainerBlockEntity cargo) {
            return cargo.mayAccess(player) && player.openMenu(cargo).isPresent();
        }
        if (player.level().getBlockEntity(anchor) instanceof CarrierMissileContainerBlockEntity missiles) {
            return missiles.mayAccess(player) && player.openMenu(missiles).isPresent();
        }
        return false;
    }

    public static void removeParts(ServerLevel level, BlockPos anchor) {
        if (CarrierModule.CONTAINER_PART_BLOCK == null) return;
        for (int dx = MIN_X; dx <= MAX_X; dx++) for (int dz = MIN_Z; dz <= MAX_Z; dz++)
            for (int dy = MIN_Y; dy <= MAX_Y; dy++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos pos = anchor.offset(dx, dy, dz);
                BlockState state = level.getBlockState(pos);
                if (state.is(CarrierModule.CONTAINER_PART_BLOCK) && anchor.equals(anchorFromPart(pos, state)))
                    level.removeBlock(pos, false);
            }
    }

    public static boolean removeContainer(ServerLevel level, BlockPos anchor) {
        removeParts(level, anchor);
        return level.removeBlock(anchor, false);
    }

    private static BlockPos anchorFromPart(BlockPos part, BlockState state) {
        int dx = state.getValue(CarrierContainerPartBlock.OFFSET_X) + MIN_X;
        int dy = state.getValue(CarrierContainerPartBlock.OFFSET_Y);
        int dz = state.getValue(CarrierContainerPartBlock.OFFSET_Z) + MIN_Z;
        return part.offset(-dx, -dy, -dz);
    }

    private static boolean isAnchor(BlockState state) {
        return state.is(CarrierModule.CARGO_CONTAINER_BLOCK) || state.is(CarrierModule.MISSILE_CONTAINER_BLOCK);
    }

    private record Pending(ServerLevel level, BlockPos anchor) {}
}
