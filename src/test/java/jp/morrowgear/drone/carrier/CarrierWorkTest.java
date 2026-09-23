package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierWorkTest {
    @Test void miningScanSamplesRingThenCrossThenRasterInBoundedGroups() {
        for (int tick = 0; tick < CarrierPolicy.SCAN_TICKS + CarrierPolicy.MINING_CHARGE_TICKS; tick++) {
            var samples = CarrierPolicy.miningScanColumns(tick);
            assertEquals(8, samples.size());
            assertEquals(8, new java.util.HashSet<>(samples).size());
            assertTrue(samples.stream().allMatch(column -> column >= 0 && column < 256));
            if (tick < 16) assertTrue(samples.stream().allMatch(column -> {
                int x = column & 15, z = column >> 4;
                return x == 0 || x == 15 || z == 0 || z == 15;
            }));
            else if (tick < 24) assertTrue(samples.stream().allMatch(column -> (column & 15) == 8 || (column >> 4) == 8));
        }
        assertEquals(java.util.stream.IntStream.range(0, 8).boxed().toList(), CarrierPolicy.miningScanColumns(24));
    }

    @Test void aNewMiningGenerationCannotExposeFireBeforeItsScanInitializes() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var ship = new CarrierShip(UUID.randomUUID(), 0,
            new CarrierAnchor("minecraft:overworld", 8, 100, 8));
        ship.mode = CarrierPolicy.Mode.MINING;
        ship.progress = new CarrierPolicy.Progress(new CarrierPolicy.Operation(second, "minecraft:overworld", 0, 0, -64, 90), 0, List.of());
        ship.miningScanGeneration = first;
        ship.miningScanTicks = CarrierPolicy.SCAN_TICKS + CarrierPolicy.MINING_CHARGE_TICKS;
        assertEquals(CarrierPolicy.WorkPhase.SCAN, ship.workPhase());
        assertEquals(0, ship.workPhaseTick());
    }

    @Test void unequalBedrockHeightsCannotCastObliqueShadowsOnAdjacentColumns() {
        var progress = progress(-1, -1, -4, 6);
        var terrain = Terrain.strata(progress.operation());
        terrain.blocks.put(new BlockPos(-8, 4, -8), Blocks.BEDROCK.defaultBlockState());
        int removed = 0;
        while (!progress.complete()) {
            var pos = requested(progress);
            var state = terrain.getBlockState(pos);
            if (progress.sealed() || state.isAir()) { progress = progress.advance(); continue; }
            if (state.is(Blocks.BEDROCK)) { progress = progress.seal(); continue; }
            var target = CarrierWork.miningTarget(progress,
                terrain.ray(CarrierWork.miningOrigin(progress, 10.9), pos));
            assertEquals(pos, target);
            terrain.blocks.remove(target);
            progress = CarrierWork.afterRemoval(progress, target);
            removed++;
        }
        assertEquals(2560 - 8, removed);
        assertTrue(terrain.getBlockState(new BlockPos(-8, 4, -8)).is(Blocks.BEDROCK));
        assertTrue(terrain.getBlockState(new BlockPos(-8, 3, -8)).is(Blocks.STONE));
        assertTrue(terrain.getBlockState(new BlockPos(-9, -3, -8)).isAir());
    }

    @Test void miningPhasesScanThenChargeThenFireAndStopsClearPresentation() {
        var ship = new CarrierShip(UUID.randomUUID(), 0,
            new CarrierAnchor("minecraft:overworld", 8, 100, 8));
        ship.mode = CarrierPolicy.Mode.MINING;
        var operation = new CarrierPolicy.Operation(UUID.randomUUID(), "minecraft:overworld", 0, 0, -64, 90);
        ship.progress = new CarrierPolicy.Progress(operation, 0, List.of());
        ship.miningScanGeneration = operation.generation();
        for (int tick = 0; tick < 60; tick++) {
            ship.miningScanTicks = tick;
            assertEquals(tick < 40 ? CarrierPolicy.WorkPhase.SCAN : CarrierPolicy.WorkPhase.CHARGE, ship.workPhase());
            assertEquals(tick < 40 ? tick : tick - 40, ship.workPhaseTick());
            assertEquals(tick < 40 ? CarrierPolicy.SCAN_TICKS : CarrierPolicy.MINING_CHARGE_TICKS,
                ship.workPhaseDuration());
        }
        ship.miningScanTicks = 60;
        assertEquals(CarrierPolicy.WorkPhase.FIRE, ship.workPhase());
        assertEquals(0, ship.workPhaseDuration());
        ship.stop(CarrierPolicy.Stop.PROTECTED);
        assertEquals(CarrierPolicy.WorkPhase.IDLE, ship.workPhase());
        assertEquals(0, ship.miningScanTicks);
    }
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void verticalColumnDoesNotBorrowObliqueForegroundOrSkipCursor() {
        var progress = progress(0, 0, 0, 4);
        var terrain = Terrain.strata(progress.operation());
        var origin = new Vec3(8, 8.9, 8);
        BlockPos requested = requested(progress);
        var hit = terrain.ray(origin, requested);
        assertEquals(HitResult.Type.BLOCK, hit.getType());
        assertNotEquals(requested, hit.getBlockPos(), "The old raster implementation stops here");
        assertEquals(requested.getY(), hit.getBlockPos().getY());
        assertNull(CarrierWork.miningTarget(progress, hit));
        BlockPos visible = CarrierWork.miningTarget(progress,
            terrain.ray(CarrierWork.miningOrigin(progress, origin.y), requested));
        assertEquals(requested, visible);
        terrain.blocks.remove(visible);
        progress = CarrierWork.afterRemoval(progress, visible);
        assertEquals(1, progress.cursor());
        assertTrue(terrain.getBlockState(requested).isAir());
    }

    @Test void visibleFrontierReachesBedrockForWholeChunkFromCentreAndEdgesIncludingNegativeCoordinates() {
        for (int chunk : new int[] {0, -2}) for (double offset : new double[] {8, .1, 15.9}) {
            var progress = progress(chunk, chunk, -4, 6);
            var op = progress.operation();
            var terrain = Terrain.strata(op);
            var origin = new Vec3(chunk * 16 + offset, op.maxY() + 4.9, chunk * 16 + offset);
            int removed = 0, steps = 0;
            // Exercise the runtime target/cursor rules with Minecraft's real voxel DDA and collision shapes.
            while (!progress.complete()) {
                assertTrue(steps++ < op.volume() * 2, "Traversal must terminate");
                var target = requested(progress);
                var state = terrain.getBlockState(target);
                if (progress.sealed() || state.isAir()) { progress = progress.advance(); continue; }
                if (state.is(Blocks.BEDROCK)) { progress = progress.seal(); continue; }
                var visible = CarrierWork.miningTarget(progress, terrain.ray(CarrierWork.miningOrigin(progress, origin.y), target));
                assertNotNull(visible, () -> "Unexpected obstruction for " + target + " from " + origin);
                assertTrue(op.contains(visible.getX(), visible.getY(), visible.getZ()));
                assertEquals(target.getY(), visible.getY());
                assertTrue(terrain.getBlockState(visible).is(Blocks.STONE));
                assertNotNull(terrain.blocks.remove(visible), "Never remove a block twice");
                progress = CarrierWork.afterRemoval(progress, visible);
                assertEquals(op, progress.operation());
                removed++;
            }
            assertEquals((op.maxY() - op.minY()) * 256, removed);
            assertEquals(256, progress.sealedColumns().size());
            assertEquals(op.volume(), progress.cursor());
            assertEquals(256, terrain.blocks.size());
            assertTrue(terrain.blocks.values().stream().allMatch(state -> state.is(Blocks.BEDROCK)));
        }
    }

    @Test void actualHigherLayerWallRemainsAnObstructionRatherThanBeingIgnored() {
        var progress = progress(0, 0, 0, 4);
        var terrain = Terrain.strata(progress.operation());
        for (int y = 5; y <= 8; y++) for (int z = 0; z < 16; z++)
            terrain.blocks.put(new BlockPos(4, y, z), Blocks.STONE.defaultBlockState());
        var hit = terrain.ray(new Vec3(8, 8.9, 8), requested(progress));
        assertEquals(HitResult.Type.BLOCK, hit.getType());
        assertTrue(hit.getBlockPos().getY() > 4);
        assertNull(CarrierWork.miningTarget(progress, hit));
        assertFalse(terrain.getBlockState(hit.getBlockPos()).isAir());
        var verticalOrigin = CarrierWork.miningOrigin(progress, 8.9);
        assertEquals(requested(progress), CarrierWork.miningTarget(progress, terrain.ray(verticalOrigin, requested(progress))),
            "A wall in a different column must not block the vertical beam");
        terrain.blocks.put(requested(progress).above(), Blocks.STONE.defaultBlockState());
        assertNull(CarrierWork.miningTarget(progress, terrain.ray(verticalOrigin, requested(progress))),
            "New overhead blocks must still obstruct the current column");
        assertEquals(0, progress.cursor());
    }

    @Test void fullWorldHeightScanSkipsAirThenMinesEveryBlockAboveAll256BedrockColumns() {
        var progress = progress(0, 0, -64, 319);
        var op = progress.operation();
        var terrain = new Terrain();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            terrain.blocks.put(new BlockPos(x, -64, z), Blocks.BEDROCK.defaultBlockState());
            for (int y = -63; y <= 0; y++) terrain.blocks.put(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        }
        BlockPos outside = new BlockPos(16, -20, 8);
        terrain.blocks.put(outside, Blocks.GOLD_BLOCK.defaultBlockState());
        Vec3 origin = new Vec3(8, 324.9, 8);
        int removed = 0, steps = 0;
        while (!progress.complete()) {
            assertTrue(steps++ < op.volume() * 2);
            BlockPos requested = requested(progress);
            var state = terrain.getBlockState(requested);
            if (progress.sealed() || state.isAir()) { progress = progress.advance(); continue; }
            if (state.is(Blocks.BEDROCK)) { progress = progress.seal(); continue; }
            BlockPos target = CarrierWork.miningTarget(progress, terrain.ray(CarrierWork.miningOrigin(progress, origin.y), requested));
            assertNotNull(target, requested::toString);
            assertTrue(op.contains(target.getX(), target.getY(), target.getZ()));
            assertNotNull(terrain.blocks.remove(target));
            progress = CarrierWork.afterRemoval(progress, target);
            removed++;
        }
        assertEquals(16_384, removed);
        assertEquals(98_304, progress.cursor());
        assertEquals(256, progress.sealedColumns().size());
        assertEquals(257, terrain.blocks.size());
        assertTrue(terrain.getBlockState(outside).is(Blocks.GOLD_BLOCK));
    }

    @Test void fluidBearingColumnsDrainAndCompleteWithoutRemovingBedrockOrLeavingFluid() {
        var progress = progress(0, 0, -4, 8);
        var op = progress.operation();
        var terrain = Terrain.strata(op);
        int expectedFluids = 0;
        for (int y = -3; y <= 7; y++) {
            BlockPos pos = new BlockPos(3, y, 5);
            terrain.blocks.put(pos, (y & 1) == 0 ? Blocks.WATER.defaultBlockState() : Blocks.LAVA.defaultBlockState());
            expectedFluids++;
        }
        Vec3 origin = new Vec3(8, 13.9, 8);
        int removedFluids = 0, steps = 0;
        while (!progress.complete()) {
            assertTrue(steps++ < op.volume() * 2, "fluid-bearing traversal must terminate");
            BlockPos requested = requested(progress);
            BlockState state = terrain.getBlockState(requested);
            if (progress.sealed() || state.isAir()) { progress = progress.advance(); continue; }
            if (state.is(Blocks.BEDROCK)) { progress = progress.seal(); continue; }
            BlockPos target = CarrierWork.miningTarget(progress, terrain.ray(CarrierWork.miningOrigin(progress, origin.y), requested));
            assertNotNull(target, requested::toString);
            state = terrain.getBlockState(target);
            assertNull(CarrierProtection.materialStop(state, true, false));
            if (CarrierProtection.drainableFluid(state)) removedFluids++;
            assertNotNull(terrain.blocks.remove(target));
            progress = CarrierWork.afterRemoval(progress, target);
        }
        assertEquals(expectedFluids, removedFluids);
        assertEquals(256, terrain.blocks.size());
        assertTrue(terrain.blocks.values().stream().allMatch(state -> state.is(Blocks.BEDROCK)));
        assertTrue(terrain.blocks.values().stream().allMatch(state -> state.getFluidState().isEmpty()));
    }

    @Test void neverSelectsOtherChunksLayersOrSealedColumns() {
        var progress = progress(-2, -3, -4, 6);
        var op = progress.operation();
        int x = op.chunkX() * 16, z = op.chunkZ() * 16;
        for (BlockPos outside : List.of(new BlockPos(x - 1, 6, z), new BlockPos(x + 16, 6, z),
                new BlockPos(x, 6, z - 1), new BlockPos(x, 6, z + 16),
                new BlockPos(x, 7, z), new BlockPos(x, 5, z))) {
            assertNull(CarrierWork.miningTarget(progress, hit(outside)), outside::toString);
        }
        var sealed = new CarrierPolicy.Progress(op, 0, List.of(17));
        assertNull(CarrierWork.miningTarget(sealed, hit(new BlockPos(x + 1, 6, z + 1))));
        assertNull(CarrierWork.miningTarget(new CarrierPolicy.Progress(op, op.volume(), List.of()), hit(requested(progress))));
        assertNull(CarrierWork.miningTarget(new CarrierPolicy.Progress(op, 0, List.of(0)), hit(requested(progress))));
    }

    @Test void realClipTraversesMixedTreesPlantsAndFallingTerrainToBedrock() {
        var progress = progress(-1, 0, -4, 10);
        var op = progress.operation();
        var terrain = new Terrain();
        for (int x = -16; x < 0; x++) for (int z = 0; z < 16; z++) {
            terrain.blocks.put(new BlockPos(x, -4, z), Blocks.BEDROCK.defaultBlockState());
            for (int y = -3; y < 0; y++) terrain.blocks.put(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
            var ground = switch (z % 3) { case 0 -> Blocks.SAND; case 1 -> Blocks.GRAVEL; default -> Blocks.GRASS_BLOCK; };
            terrain.blocks.put(new BlockPos(x, 0, z), ground.defaultBlockState());
            if (ground == Blocks.GRASS_BLOCK) terrain.blocks.put(new BlockPos(x, 1, z), Blocks.SHORT_GRASS.defaultBlockState());
        }
        for (int y = 1; y <= 4; y++) terrain.blocks.put(new BlockPos(-8, y, 8), Blocks.OAK_LOG.defaultBlockState());
        for (int x = -10; x <= -6; x++) for (int z = 6; z <= 10; z++)
            terrain.blocks.put(new BlockPos(x, 5, z), Blocks.OAK_LEAVES.defaultBlockState());
        terrain.blocks.put(new BlockPos(-13, 1, 5), Blocks.TALL_GRASS.defaultBlockState());
        terrain.blocks.put(new BlockPos(-13, 2, 5), Blocks.TALL_GRASS.defaultBlockState()
            .setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        int expected = terrain.blocks.size() - 256, removed = 0, steps = 0;
        Vec3 origin = new Vec3(-8, 14.9, 8);
        while (!progress.complete()) {
            assertTrue(steps++ < op.volume() * 2);
            BlockPos requested = requested(progress);
            var state = terrain.getBlockState(requested);
            if (progress.sealed() || state.isAir()) { progress = progress.advance(); continue; }
            if (state.is(Blocks.BEDROCK)) { progress = progress.seal(); continue; }
            BlockPos target = CarrierWork.miningTarget(progress, terrain.ray(CarrierWork.miningOrigin(progress, origin.y), requested));
            assertNotNull(target, requested::toString);
            assertNull(CarrierProtection.materialStop(terrain.getBlockState(target), true, false), target::toString);
            assertNotNull(terrain.blocks.remove(target));
            progress = CarrierWork.afterRemoval(progress, target);
            removed++;
        }
        assertEquals(expected, removed);
        assertEquals(256, terrain.blocks.size());
        assertEquals(256, progress.sealedColumns().size());
        assertTrue(terrain.blocks.values().stream().allMatch(state -> state.is(Blocks.BEDROCK)));
    }

    private static CarrierPolicy.Progress progress(int chunkX, int chunkZ, int minY, int maxY) {
        return new CarrierPolicy.Progress(new CarrierPolicy.Operation(UUID.randomUUID(), "minecraft:overworld",
            chunkX, chunkZ, minY, maxY), 0, List.of());
    }

    private static BlockPos requested(CarrierPolicy.Progress progress) {
        var op = progress.operation();
        return new BlockPos(op.x(progress.cursor()), op.y(progress.cursor()), op.z(progress.cursor()));
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    private static final class Terrain implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        static Terrain strata(CarrierPolicy.Operation op) {
            var terrain = new Terrain();
            for (int cursor = 0; cursor < op.volume(); cursor++) {
                var pos = new BlockPos(op.x(cursor), op.y(cursor), op.z(cursor));
                terrain.blocks.put(pos, (pos.getY() == op.minY() ? Blocks.BEDROCK : Blocks.STONE).defaultBlockState());
            }
            return terrain;
        }

        BlockHitResult ray(Vec3 origin, BlockPos target) {
            return clip(new ClipContext(origin, Vec3.atCenterOf(target), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY, CollisionContext.empty()));
        }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }
    }
}
