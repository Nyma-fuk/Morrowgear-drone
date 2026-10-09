package jp.morrowgear.drone.carrier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.UUID;

/** Server-independent limits and the approved V31 exterior coordinate contract. */
public final class CarrierPolicy {
    public static final int CARGO_SLOTS = 54;
    public static final int CARGO_PAGES = 64, STORAGE_SLOTS = CARGO_SLOTS * CARGO_PAGES;
    public static final int MAX_CABINS = 4096;
    public static final int BAY_SLOTS = 4;
    public static final int MAX_ACTIVE_CHUNK_LEASES = 4;
    /** Air and already-cleared cells are cheap to advance, so they must not throttle a deep excavation. */
    public static final int SCAN_PER_TICK = 32768;
    /** Solid terrain remains bounded by both this cap and WORK_NANOS on the server thread. */
    public static final int BREAK_PER_TICK = 3072;
    public static final long WORK_NANOS = 8_000_000;
    public static final int PREVIEW_TICKS = 600;
    public static final int COMBAT_CHARGE_TICKS = 60, COMBAT_FIRE_TICKS = 480, COMBAT_COOLDOWN_TICKS = 60;
    public static final int SCAN_TICKS = 40;
    /** The impact is centred in the selected chunk and expands through a circular three-chunk envelope. */
    public static final int MINING_IMPACT_RADIUS = 24;
    /** 16 sequential emitters (8 ticks each), then 64 ticks of core maturation. */
    public static final int MINING_CHARGE_TICKS = 192;
    public static final int COMBAT_TICKS = SCAN_TICKS + COMBAT_CHARGE_TICKS + COMBAT_FIRE_TICKS + COMBAT_COOLDOWN_TICKS;
    public static final int COMBAT_RADIUS = 96, COMBAT_TARGETS = 16, COMBAT_PULSE_TICKS = 10;
    /** Renderer and server share this radius for the single broad combat cone. */
    public static final double COMBAT_IMPACT_RADIUS = 8;
    public static final double MAX_BEAM_RADIUS = 1.15;
    public static final int MAX_ENERGY = 100_000;
    public static final double LENGTH = 31, WIDTH = 50, HEIGHT = 7.2;
    public static final double BOARDING_X = 0, BOARDING_Z = 18, BOARDING_RADIUS = 1.5;
    /** Source compatibility for integrations written against the prototype. */
    @Deprecated public static final double PROPOSED_LENGTH = LENGTH, PROPOSED_WIDTH = WIDTH, PROPOSED_HEIGHT = HEIGHT;
    public static final Codec<UUID> UUID_CODEC = Codec.STRING.comapFlatMap(value -> {
        try { return DataResult.success(UUID.fromString(value)); }
        catch (IllegalArgumentException ex) { return DataResult.error(() -> "Invalid carrier UUID"); }
    }, UUID::toString);

    private CarrierPolicy() {}

    private static final java.util.Map<Integer, int[]> EXCAVATION_COLUMNS = new java.util.HashMap<>();

    private static synchronized int[] radialExcavationColumns(int radius) {
		int normalized = Math.clamp(radius, 1, MINING_IMPACT_RADIUS);
		int[] cached = EXCAVATION_COLUMNS.get(normalized);
		if (cached != null) return cached;
		int diameter = normalized * 2;
		var columns = new java.util.ArrayList<Integer>(diameter * diameter);
		for (int x = -normalized; x < normalized; x++) for (int z = -normalized; z < normalized; z++) {
			double dx = x + .5, dz = z + .5;
			if (normalized == 8 || dx * dx + dz * dz <= normalized * normalized)
				columns.add((z + normalized) * diameter + x + normalized);
		}
        columns.sort(java.util.Comparator
            .comparingDouble((Integer column) -> {
				double x = column % diameter - normalized + .5;
				double z = column / diameter - normalized + .5;
                return x * x + z * z;
            })
			.thenComparingDouble(column -> Math.atan2(column / diameter - normalized + .5,
				column % diameter - normalized + .5))
            .thenComparingInt(Integer::intValue));
		int[] result = columns.stream().mapToInt(Integer::intValue).toArray();
		EXCAVATION_COLUMNS.put(normalized, result);
		return result;
    }

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
        return Operation.at(generation, dimension, blockX, blockZ, minBuildY, top, MINING_IMPACT_RADIUS);
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
				columns.add(axis | (8 << 4)); columns.add(8 | (((axis + 4) & 15) << 4));
			}
		} else {
			int base = Math.floorMod((phaseTick - 24) * 8, 256);
			for (int i = 0; i < 8; i++) columns.add((base + i) & 255);
		}
		return List.copyOf(columns);
	}

	public static List<Integer> miningScanColumns(int tick, int radius) {
		if (radius == 8) return miningScanColumns(tick);
        int phaseTick = Math.max(0, tick);
		int[] footprint = radialExcavationColumns(radius);
        var columns = new java.util.ArrayList<Integer>(8);
		int base = Math.floorMod(phaseTick * 8, footprint.length);
		for (int i = 0; i < 8; i++) columns.add((base + i) % footprint.length);
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

    public record Operation(UUID generation, String dimension, int chunkX, int chunkZ, int minY, int maxY,
                            int surfaceY, int footprintRadius) {
        public static final Codec<Operation> CODEC = RecordCodecBuilder.<Operation>create(i -> i.group(
            UUID_CODEC.fieldOf("generation").forGetter(Operation::generation),
            Codec.STRING.fieldOf("dimension").forGetter(Operation::dimension),
            Codec.intRange(-1_875_000, 1_874_999).fieldOf("chunk_x").forGetter(Operation::chunkX),
            Codec.intRange(-1_875_000, 1_874_999).fieldOf("chunk_z").forGetter(Operation::chunkZ),
            Codec.intRange(-2048, 2047).fieldOf("min_y").forGetter(Operation::minY),
            Codec.intRange(-2048, 2047).fieldOf("max_y").forGetter(Operation::maxY),
            Codec.intRange(-2048, 2047).optionalFieldOf("surface_y", 2047).forGetter(Operation::surfaceY),
            Codec.intRange(1, MINING_IMPACT_RADIUS).optionalFieldOf("footprint_radius", 8).forGetter(Operation::footprintRadius)
        ).apply(i, Operation::new)).validate(op -> op.minY <= op.maxY
            ? DataResult.success(op) : DataResult.error(() -> "Inverted carrier operation"));

        public Operation {
            surfaceY = minY <= maxY ? Math.clamp(surfaceY, minY, Math.min(2047, maxY + 1)) : minY;
			footprintRadius = Math.clamp(footprintRadius, 1, MINING_IMPACT_RADIUS);
        }

        public Operation(UUID generation, String dimension, int chunkX, int chunkZ, int minY, int maxY) {
			this(generation, dimension, chunkX, chunkZ, minY, maxY, 2047, 8);
		}
		public Operation(UUID generation, String dimension, int chunkX, int chunkZ, int minY, int maxY, int surfaceY) {
			this(generation, dimension, chunkX, chunkZ, minY, maxY, surfaceY, 8);
        }

        public static Operation at(UUID generation, String dimension, int blockX, int blockZ, int minY, int maxY) {
			return at(generation, dimension, blockX, blockZ, minY, maxY, 8);
		}
		public static Operation at(UUID generation, String dimension, int blockX, int blockZ, int minY, int maxY, int radius) {
			return new Operation(generation, dimension, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16),
				minY, maxY, 2047, radius);
        }
        public Operation withSurfaceY(int selectedSurfaceY) {
			return new Operation(generation, dimension, chunkX, chunkZ, minY, maxY, selectedSurfaceY, footprintRadius);
        }
        public int footprintSize() { return radialExcavationColumns(footprintRadius).length; }
        public int volume() { return (maxY - minY + 1) * footprintSize(); }
        public int column(int cursor) { int[] columns = radialExcavationColumns(footprintRadius); return columns[Math.floorMod(cursor, columns.length)]; }
        public int centerX() { return chunkX * 16 + 8; }
        public int centerZ() { return chunkZ * 16 + 8; }
        public int x(int cursor) { int diameter = footprintRadius * 2; return centerX() + column(cursor) % diameter - footprintRadius; }
        public int z(int cursor) { int diameter = footprintRadius * 2; return centerZ() + column(cursor) / diameter - footprintRadius; }
        public int y(int cursor) { return maxY - cursor / footprintSize(); }
		public int minX() { return centerX() - footprintRadius; }
		public int maxX() { return centerX() + footprintRadius - 1; }
		public int minZ() { return centerZ() - footprintRadius; }
		public int maxZ() { return centerZ() + footprintRadius - 1; }
        public boolean contains(int x, int y, int z) {
			if (footprintRadius == 8) {
				return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ()
					&& y >= minY && y <= maxY;
			}
			double dx = x + .5 - centerX(), dz = z + .5 - centerZ();
			return dx * dx + dz * dz <= footprintRadius * footprintRadius && y >= minY && y <= maxY;
        }
    }

    public record Progress(Operation operation, int cursor, List<Integer> sealedColumns) {
        public static final Codec<Progress> CODEC = RecordCodecBuilder.<Progress>create(i -> i.group(
            Operation.CODEC.fieldOf("operation").forGetter(Progress::operation),
            Codec.intRange(0, 1_048_576).fieldOf("cursor").forGetter(Progress::cursor),
            Codec.intRange(0, 4095).listOf(0, 4096).fieldOf("sealed_columns").forGetter(Progress::sealedColumns)
        ).apply(i, Progress::new)).validate(p -> p.cursor <= p.operation.volume()
            ? DataResult.success(p) : DataResult.error(() -> "Carrier cursor outside operation"));
        public Progress { sealedColumns = List.copyOf(sealedColumns); }
        public Progress advance() { return new Progress(operation, Math.min(cursor + 1, operation.volume()), sealedColumns); }
        public Progress seal() {
            var columns = new java.util.ArrayList<>(sealedColumns);
            int column = operation.column(cursor);
            if (!columns.contains(column)) columns.add(column);
            return new Progress(operation, cursor, columns).advance();
        }
        public boolean complete() { return cursor >= operation.volume(); }
        public boolean sealed() { return sealedColumns.contains(operation.column(cursor)); }
    }

    public static boolean canActivate(UUID expected, Operation operation, long now, long previewUntil,
                                      boolean owner, boolean loaded, boolean attended) {
        // Loading is an execution precondition, not a command-acceptance precondition. The active
        // lease loads the bounded footprint and CarrierWork remains in UNLOADED until it is ready.
        return owner && attended && operation != null && operation.generation.equals(expected)
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
        return switch (slot) { case 0 -> -15; case 1 -> -10; case 2 -> 10; case 3 -> 15;
            default -> throw new IllegalArgumentException("Invalid carrier bay"); };
    }
    public static double bayZ(int slot) {
        return switch (slot) { case 0, 1, 2, 3 -> -4.65;
            default -> throw new IllegalArgumentException("Invalid carrier bay"); };
    }
}
