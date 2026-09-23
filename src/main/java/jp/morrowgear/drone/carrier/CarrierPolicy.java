package jp.morrowgear.drone.carrier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.UUID;

/** Server-independent limits and the approved V27 exterior coordinate contract. */
public final class CarrierPolicy {
    public static final int CARGO_SLOTS = 54;
    public static final int CARGO_PAGES = 64, STORAGE_SLOTS = CARGO_SLOTS * CARGO_PAGES;
    public static final int MAX_CABINS = 4096;
    public static final int BAY_SLOTS = 4;
    public static final int MAX_ACTIVE_CHUNK_LEASES = 4;
    public static final int SCAN_PER_TICK = 512;
    public static final int BREAK_PER_TICK = 32;
    public static final long WORK_NANOS = 2_000_000;
    public static final int PREVIEW_TICKS = 600;
    public static final int COMBAT_CHARGE_TICKS = 60, COMBAT_FIRE_TICKS = 480, COMBAT_COOLDOWN_TICKS = 60;
    public static final int SCAN_TICKS = 40;
    public static final int MINING_CHARGE_TICKS = 20;
    public static final int COMBAT_TICKS = SCAN_TICKS + COMBAT_CHARGE_TICKS + COMBAT_FIRE_TICKS + COMBAT_COOLDOWN_TICKS;
    public static final int COMBAT_RADIUS = 96, COMBAT_TARGETS = 16, COMBAT_PULSE_TICKS = 10;
    /** Renderer and server share this radius for the single broad combat cone. */
    public static final double COMBAT_IMPACT_RADIUS = 8;
    public static final double MAX_BEAM_RADIUS = 1.15;
    public static final int MAX_ENERGY = 100_000;
    public static final double LENGTH = 50, WIDTH = 26, HEIGHT = 11;
    public static final double BOARDING_X = 0, BOARDING_Z = 18, BOARDING_RADIUS = 1.5;
    /** Source compatibility for integrations written against the prototype. */
    @Deprecated public static final double PROPOSED_LENGTH = LENGTH, PROPOSED_WIDTH = WIDTH, PROPOSED_HEIGHT = HEIGHT;
    public static final Codec<UUID> UUID_CODEC = Codec.STRING.comapFlatMap(value -> {
        try { return DataResult.success(UUID.fromString(value)); }
        catch (IllegalArgumentException ex) { return DataResult.error(() -> "Invalid carrier UUID"); }
    }, UUID::toString);

    private CarrierPolicy() {}

    public static boolean inBoardingProjection(double relativeX, double relativeZ) {
        return Math.abs(relativeX - BOARDING_X) <= BOARDING_RADIUS && Math.abs(relativeZ - BOARDING_Z) <= BOARDING_RADIUS;
    }
    public static boolean withinBoardingEnvelope(double playerX, double playerZ, double carrierX, double carrierZ) {
        if (!Double.isFinite(playerX + playerZ + carrierX + carrierZ)) return false;
        double radius = Math.hypot(Math.abs(BOARDING_X) + BOARDING_RADIUS,
            Math.abs(BOARDING_Z) + BOARDING_RADIUS);
        double dx = playerX - carrierX, dz = playerZ - carrierZ;
        return dx * dx + dz * dz <= radius * radius + 1.0e-9;
    }

    public static int operationTop(double bellyY) { return (int) Math.floor(bellyY - .1) - 1; }

    /** Production mining is the whole column below the carrier, never a fixed-height slice. */
    public static Operation miningOperation(UUID generation, String dimension, int blockX, int blockZ,
                                            double bellyY, int minBuildY, int maxBuildY) {
        int top = Math.min(maxBuildY - 1, operationTop(bellyY));
        if (top < minBuildY) throw new IllegalArgumentException("Carrier below mineable world range");
        return Operation.at(generation, dimension, blockX, blockZ, minBuildY, top);
    }

    public static boolean aligned(Operation op, String dimension, double x, double bellyY, double z) {
        return op != null && Double.isFinite(x) && Double.isFinite(bellyY) && Double.isFinite(z)
            && op.dimension().equals(dimension)
            && Math.floorDiv((int) Math.floor(x), 16) == op.chunkX()
            && Math.floorDiv((int) Math.floor(z), 16) == op.chunkZ()
            && bellyY - .1 > op.maxY() + 1;
    }

    /** Eight bounded scan samples: perimeter ring, centre cross, then raster coverage. */
    public static List<Integer> miningScanColumns(int tick) {
        int phaseTick = Math.max(0, tick);
        var columns = new java.util.ArrayList<Integer>(8);
        if (phaseTick < 16) {
            for (int i = 0; i < 8; i++) columns.add(perimeterColumn((phaseTick * 4 + i) % 60));
        } else if (phaseTick < 24) {
            int base = (phaseTick - 16) * 4;
            for (int i = 0; i < 4; i++) {
                int axis = (base + i) & 15;
                columns.add(axis | (8 << 4));
                columns.add(8 | (((axis + 4) & 15) << 4));
            }
        } else {
            int base = Math.floorMod((phaseTick - 24) * 8, 256);
            for (int i = 0; i < 8; i++) columns.add((base + i) & 255);
        }
        return List.copyOf(columns);
    }

    private static int perimeterColumn(int index) {
        if (index < 16) return index;
        if (index < 30) return 15 | ((index - 15) << 4);
        if (index < 46) return (45 - index) | (15 << 4);
        return (60 - index) << 4;
    }

    public enum Mode { IDLE, MINING, COMBAT }
    public enum WorkPhase { IDLE, CHARGE, FIRE, COOLDOWN, SCAN }
    public static WorkPhase combatPhase(int remaining) {
        if (remaining <= 0 || remaining > COMBAT_TICKS) return WorkPhase.IDLE;
        int elapsed = COMBAT_TICKS - remaining - SCAN_TICKS;
        if (elapsed < 0) return WorkPhase.SCAN;
        return elapsed < COMBAT_CHARGE_TICKS ? WorkPhase.CHARGE
            : elapsed < COMBAT_CHARGE_TICKS + COMBAT_FIRE_TICKS ? WorkPhase.FIRE : WorkPhase.COOLDOWN;
    }
    public static int phaseDuration(WorkPhase phase) {
        return switch (phase) { case SCAN -> SCAN_TICKS; case CHARGE -> COMBAT_CHARGE_TICKS; case FIRE -> COMBAT_FIRE_TICKS;
            case COOLDOWN -> COMBAT_COOLDOWN_TICKS; default -> 0; };
    }
    public static int phaseTick(int remaining) {
        int elapsed = COMBAT_TICKS - remaining - SCAN_TICKS;
        return switch (combatPhase(remaining)) { case SCAN -> elapsed + SCAN_TICKS; case CHARGE -> elapsed;
            case FIRE -> elapsed - COMBAT_CHARGE_TICKS;
            case COOLDOWN -> elapsed - COMBAT_CHARGE_TICKS - COMBAT_FIRE_TICKS; default -> 0; };
    }
    public enum Stop { READY, PREVIEW, RUNNING, EMERGENCY, RELOAD, OWNER_ABSENT, FULL,
        PROTECTED, UNLOADED, OBSTRUCTED, NO_POWER, COMPLETE, DESTROYED, EXPIRED, FRIENDLY_IN_AREA, LEASE_LIMIT, NO_SAFE_EXIT, LIQUID, TARGET_LIMIT,
        INVALID_TARGET, BUSY, CONTROL_LOST }

    public record Operation(UUID generation, String dimension, int chunkX, int chunkZ, int minY, int maxY) {
        public static final Codec<Operation> CODEC = RecordCodecBuilder.<Operation>create(i -> i.group(
            UUID_CODEC.fieldOf("generation").forGetter(Operation::generation),
            Codec.STRING.fieldOf("dimension").forGetter(Operation::dimension),
            Codec.intRange(-1_875_000, 1_874_999).fieldOf("chunk_x").forGetter(Operation::chunkX),
            Codec.intRange(-1_875_000, 1_874_999).fieldOf("chunk_z").forGetter(Operation::chunkZ),
            Codec.intRange(-2048, 2047).fieldOf("min_y").forGetter(Operation::minY),
            Codec.intRange(-2048, 2047).fieldOf("max_y").forGetter(Operation::maxY)
        ).apply(i, Operation::new)).validate(op -> op.minY <= op.maxY
            ? DataResult.success(op) : DataResult.error(() -> "Inverted carrier operation"));

        public static Operation at(UUID generation, String dimension, int blockX, int blockZ, int minY, int maxY) {
            return new Operation(generation, dimension, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16), minY, maxY);
        }
        public int volume() { return (maxY - minY + 1) * 256; }
        public int x(int cursor) { return chunkX * 16 + (cursor & 15); }
        public int z(int cursor) { return chunkZ * 16 + ((cursor >> 4) & 15); }
        public int y(int cursor) { return maxY - cursor / 256; }
        public boolean contains(int x, int y, int z) {
            return Math.floorDiv(x, 16) == chunkX && Math.floorDiv(z, 16) == chunkZ && y >= minY && y <= maxY;
        }
    }

    public record Progress(Operation operation, int cursor, List<Integer> sealedColumns) {
        public static final Codec<Progress> CODEC = RecordCodecBuilder.<Progress>create(i -> i.group(
            Operation.CODEC.fieldOf("operation").forGetter(Progress::operation),
            Codec.intRange(0, 1_048_576).fieldOf("cursor").forGetter(Progress::cursor),
            Codec.intRange(0, 255).listOf(0, 256).fieldOf("sealed_columns").forGetter(Progress::sealedColumns)
        ).apply(i, Progress::new)).validate(p -> p.cursor <= p.operation.volume()
            ? DataResult.success(p) : DataResult.error(() -> "Carrier cursor outside operation"));
        public Progress { sealedColumns = List.copyOf(sealedColumns); }
        public Progress advance() { return new Progress(operation, Math.min(cursor + 1, operation.volume()), sealedColumns); }
        public Progress seal() {
            var columns = new java.util.ArrayList<>(sealedColumns);
            if (!columns.contains(cursor & 255)) columns.add(cursor & 255);
            return new Progress(operation, cursor, columns).advance();
        }
        public boolean complete() { return cursor >= operation.volume(); }
        public boolean sealed() { return sealedColumns.contains(cursor & 255); }
    }

    public static boolean canActivate(UUID expected, Operation operation, long now, long previewUntil,
                                      boolean owner, boolean loaded, boolean attended) {
        return owner && loaded && attended && operation != null && operation.generation.equals(expected)
            && now <= previewUntil && now >= previewUntil - PREVIEW_TICKS;
    }

    public static int cabinX(int cell) { checkCell(cell); return (cell % 64) * 64; }
    public static int cabinZ(int cell) { checkCell(cell); return (cell / 64) * 64; }
    public static boolean inCabin(int cell, int x, int y, int z) {
        return x > cabinX(cell) && x < cabinX(cell) + 15 && z > cabinZ(cell)
            && z < cabinZ(cell) + 15 && y > 64 && y < 76;
    }
    public static boolean reservedEntry(int cell, int x, int y, int z) {
        int dx = x - cabinX(cell), dz = z - cabinZ(cell);
        return y >= 64 && y <= 68 && ((dx >= 1 && dx <= 3 && dz >= 1 && dz <= 3)
            || (dx >= 12 && dx <= 14 && dz >= 12 && dz <= 14));
    }
    private static void checkCell(int cell) {
        if (cell < 0 || cell >= MAX_CABINS) throw new IllegalArgumentException("Invalid cabin cell");
    }
    public static double bayX(int slot) {
        return switch (slot) { case 0 -> -7.15; case 1 -> 7.15; case 2 -> -3.6; case 3 -> 3.6;
            default -> throw new IllegalArgumentException("Invalid carrier bay"); };
    }
    public static double bayZ(int slot) {
        return switch (slot) { case 0, 1 -> -5.2; case 2, 3 -> 7.8;
            default -> throw new IllegalArgumentException("Invalid carrier bay"); };
    }
}
