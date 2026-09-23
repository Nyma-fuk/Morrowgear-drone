package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Bounded fixture geometry, independent of world generation and carrier work speed. */
final class CarrierAcceptancePolicy {
    static final int WIDTH = 16, LAYERS = 64, COLUMNS = WIDTH * WIDTH;
    static final int MINE_BLOCKS = COLUMNS * LAYERS, FIXTURE_BLOCKS = MINE_BLOCKS + COLUMNS;
    static final int WORLD_BUDGET = 256, CLICK_BUDGET = 16, TRAVEL_DISTANCE = 544;
    static final int BOX_WIDTH = 18, BOX_HEIGHT = LAYERS + 3;
    static final int BOX_VOLUME = BOX_WIDTH * BOX_WIDTH * BOX_HEIGHT;
    static final int SHELL_BLOCKS = BOX_VOLUME - FIXTURE_BLOCKS;
    static final double WAREHOUSE_REACH_SQUARED = 64.0;
    private static final int[][] FIXTURE_OFFSETS = {
        {2, 0}, {-2, 0}, {0, 2}, {0, -2},
        {2, 2}, {2, -2}, {-2, 2}, {-2, -2},
        {3, 0}, {-3, 0}, {0, 3}, {0, -3},
        {3, 3}, {3, -3}, {-3, 3}, {-3, -3}
    };
    private static final Set<String> STABLE_RESTART_CHECKPOINTS = Set.of(
        "FULL_WAIT", "MINED", "EXTRACTED", "COMBAT_DONE", "COMBAT_RETURNED", "TRAVEL_DONE", "PAUSED");
    private CarrierAcceptancePolicy() {}

    record ChunkCandidate(int x, int z) {}
    record CabinCell(int x, int z) {}

    static List<ChunkCandidate> fixtureChunkCandidates(int carrierChunkX, int carrierChunkZ) {
        List<ChunkCandidate> result = new ArrayList<>(FIXTURE_OFFSETS.length);
        for (int[] offset : FIXTURE_OFFSETS)
            result.add(new ChunkCandidate(carrierChunkX + offset[0], carrierChunkZ + offset[1]));
        return List.copyOf(result);
    }

    static List<CabinCell> warehouseCandidates(double playerCabinX, double playerCabinZ) {
        List<CabinCell> result = new ArrayList<>();
        for (int z = 4; z <= 11; z++) for (int x = 4; x <= 11; x++) {
            CabinCell cell = new CabinCell(x, z);
            if (!(x == 6 && z == 6) && normalEntryReach(cell)) result.add(cell);
        }
        result.sort(Comparator
            .comparingDouble((CabinCell cell) -> horizontalDistanceSquared(playerCabinX, playerCabinZ, cell))
            .thenComparingDouble(cell -> horizontalDistanceSquared(2.5, 2.5, cell))
            .thenComparingInt(CabinCell::z)
            .thenComparingInt(CabinCell::x));
        return List.copyOf(result);
    }

    static boolean normalEntryReach(CabinCell cell) {
        // Entry feet are at y=65; the target block center contributes another 0.25 distance squared.
        return horizontalDistanceSquared(2.5, 2.5, cell) + 0.25 < WAREHOUSE_REACH_SQUARED;
    }

    static boolean stableRestartCheckpoint(String stage) {
        return stage != null && STABLE_RESTART_CHECKPOINTS.contains(stage);
    }

    private static double horizontalDistanceSquared(double x, double z, CabinCell cell) {
        double dx = cell.x() + 0.5 - x, dz = cell.z() + 0.5 - z;
        return dx * dx + dz * dz;
    }

    static int x(int index) { return index & 15; }
    static int z(int index) { return (index >> 4) & 15; }
    static int y(int index) { return index / COLUMNS; }
    static int mineIndex(int x, int y, int z) {
        if (!mine(x, y, z)) throw new IllegalArgumentException("not a mineable fixture position");
        return (y - 1) * COLUMNS + z * WIDTH + x;
    }
    static int mineWordCount() { return (MINE_BLOCKS + Long.SIZE - 1) / Long.SIZE; }
    static boolean fixture(int x, int y, int z) {
        return x >= 0 && x < WIDTH && z >= 0 && z < WIDTH && y >= 0 && y <= LAYERS;
    }
    static boolean mine(int x, int y, int z) { return fixture(x, y, z) && y > 0; }
    static int material(int layer) {
        if (layer < 0 || layer > LAYERS) throw new IllegalArgumentException("fixture layer");
        if (layer == 0) return 0;
        // Five ore layers, 24 deepslate layers, 35 stone layers. No Fortune or second loot roll.
        if (layer <= 5) return layer + 2;
        return layer <= 29 ? 2 : 1;
    }
    static boolean restartIsEvidence(java.util.UUID saved, java.util.UUID current) {
        return saved != null && current != null && !saved.equals(current);
    }
    static boolean continuousStep(double distanceSquared) {
        return Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= 1;
    }
    static int observedEnergySpend(int previous, int current) {
        if (previous < 0 || current < 0) throw new IllegalArgumentException("energy");
        return Math.max(0, previous - current);
    }
}
