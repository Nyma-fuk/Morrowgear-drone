package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.List;
import jp.morrowgear.drone.carrier.CarrierMiningEffectPolicy;
import jp.morrowgear.drone.carrier.CarrierPolicy;

/** Pure presentation policy for the carrier mining map. */
public final class CarrierMapUiPolicy {
    public enum MiningStage { IDLE, SCAN, CHARGE, DESCENT, RELEASE }
    public record Chunk(int x, int z) {}
    private static final double DESCENT_START = .45;

    private CarrierMapUiPolicy() {}

    public static List<Chunk> scanGrid(int centerX, int centerZ) {
        var chunks = new ArrayList<Chunk>(9);
        for (int z = centerZ - 1; z <= centerZ + 1; z++)
            for (int x = centerX - 1; x <= centerX + 1; x++) chunks.add(new Chunk(x, z));
        return List.copyOf(chunks);
    }

    public static List<Chunk> scannedChunks(int mode, CarrierPolicy.WorkPhase phase, int phaseTick,
                                             int centerX, int centerZ) {
        if (mode != CarrierPolicy.Mode.MINING.ordinal()) return List.of();
        if (phase != CarrierPolicy.WorkPhase.SCAN) return scanGrid(centerX, centerZ);
        var chunks = new ArrayList<Chunk>(CarrierMiningEffectPolicy.SCAN_CHUNKS);
        for (int tick = 0; tick <= Math.clamp(phaseTick, 0, CarrierPolicy.SCAN_TICKS); tick++) {
            var offset = CarrierMiningEffectPolicy.scanChunk(tick);
            var chunk = new Chunk(centerX + offset.x(), centerZ + offset.z());
            if (!chunks.contains(chunk)) chunks.add(chunk);
        }
        return List.copyOf(chunks);
    }

    public static boolean persistentMiningFrame(int mode, int previewMode, int previewTicks, int stop, int total) {
        if (mode == CarrierPolicy.Mode.COMBAT.ordinal()) return false;
        if (previewTicks > 0 || stop == CarrierPolicy.Stop.PREVIEW.ordinal())
            return previewMode == CarrierPolicy.Mode.MINING.ordinal();
        return mode == CarrierPolicy.Mode.MINING.ordinal() || total > 0;
    }

    public static MiningStage miningStage(int mode, CarrierPolicy.WorkPhase phase, int phaseTick, int phaseDuration) {
        if (mode == CarrierPolicy.Mode.MINING.ordinal()) return switch (phase) {
            case SCAN -> MiningStage.SCAN;
            case CHARGE -> phaseProgress(phaseTick, phaseDuration) < DESCENT_START
                ? MiningStage.CHARGE : MiningStage.DESCENT;
            case FIRE -> MiningStage.RELEASE;
            default -> MiningStage.IDLE;
        };
        return MiningStage.IDLE;
    }

    public static double progress(MiningStage stage, int phaseTick, int phaseDuration) {
        double phase = phaseProgress(phaseTick, phaseDuration);
        return switch (stage) {
            case SCAN -> phase;
            case CHARGE -> bounded(phase / DESCENT_START);
            case DESCENT -> bounded((phase - DESCENT_START) / (1 - DESCENT_START));
            case RELEASE -> phase;
            default -> 0;
        };
    }

    private static double phaseProgress(int tick, int duration) {
        return bounded(duration <= 0 ? 0 : (double) tick / duration);
    }

    private static double bounded(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }
}
