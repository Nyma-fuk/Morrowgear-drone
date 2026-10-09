package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import jp.morrowgear.drone.carrier.CarrierPolicy;

class CarrierMapUiPolicyTest {
    @Test void scanGridIsExactlyThreeByThreeAroundNegativeOrPositiveTargets() {
        var chunks = CarrierMapUiPolicy.scanGrid(-2, 14);
        assertEquals(9, chunks.size());
        assertEquals(9, new HashSet<>(chunks).size());
        assertTrue(chunks.contains(new CarrierMapUiPolicy.Chunk(-3, 13)));
        assertTrue(chunks.contains(new CarrierMapUiPolicy.Chunk(-2, 14)));
        assertTrue(chunks.contains(new CarrierMapUiPolicy.Chunk(-1, 15)));
    }

    @Test void miningFramePersistsForPreviewWorkAndSavedProgressButNotCombat() {
        int idle = CarrierPolicy.Mode.IDLE.ordinal(), mining = CarrierPolicy.Mode.MINING.ordinal();
        int combat = CarrierPolicy.Mode.COMBAT.ordinal();
        int preview = CarrierPolicy.Stop.PREVIEW.ordinal(), complete = CarrierPolicy.Stop.COMPLETE.ordinal();
        assertTrue(CarrierMapUiPolicy.persistentMiningFrame(idle, mining, 20, preview, 0));
        assertTrue(CarrierMapUiPolicy.persistentMiningFrame(mining, mining, 0, CarrierPolicy.Stop.RUNNING.ordinal(), 1));
        assertTrue(CarrierMapUiPolicy.persistentMiningFrame(idle, idle, 0, complete, 16384));
        assertFalse(CarrierMapUiPolicy.persistentMiningFrame(combat, mining, 0, complete, 16384));
        assertFalse(CarrierMapUiPolicy.persistentMiningFrame(idle, combat, 20, preview, 16384));
        assertFalse(CarrierMapUiPolicy.persistentMiningFrame(idle, combat, 0, preview, 16384));
    }

    @Test void miningStagesFollowTheAuthoritativeVisualTimeline() {
        int mining = CarrierPolicy.Mode.MINING.ordinal(), idle = CarrierPolicy.Mode.IDLE.ordinal();
        assertEquals(CarrierMapUiPolicy.MiningStage.SCAN, stage(mining, CarrierPolicy.WorkPhase.SCAN, 20, 40));
        assertEquals(CarrierMapUiPolicy.MiningStage.CHARGE, stage(mining, CarrierPolicy.WorkPhase.CHARGE, 8, 20));
        assertEquals(CarrierMapUiPolicy.MiningStage.DESCENT, stage(mining, CarrierPolicy.WorkPhase.CHARGE, 9, 20));
        assertEquals(CarrierMapUiPolicy.MiningStage.RELEASE, stage(mining, CarrierPolicy.WorkPhase.FIRE, 1, 24));
        assertEquals(CarrierMapUiPolicy.MiningStage.IDLE, stage(idle, CarrierPolicy.WorkPhase.IDLE, 0, 0));
    }

    @Test void scanIndicatorsFollowTheAuthoritativeThreeByThreeOrder() {
        int mining = CarrierPolicy.Mode.MINING.ordinal();
        var first = CarrierMapUiPolicy.scannedChunks(mining, CarrierPolicy.WorkPhase.SCAN, 0, 10, -4);
        var last = CarrierMapUiPolicy.scannedChunks(mining, CarrierPolicy.WorkPhase.SCAN, 40, 10, -4);
        assertEquals(List.of(new CarrierMapUiPolicy.Chunk(9, -5)), first);
        assertEquals(9, last.size());
        assertEquals(new HashSet<>(CarrierMapUiPolicy.scanGrid(10, -4)), new HashSet<>(last));
        assertEquals(9, CarrierMapUiPolicy.scannedChunks(mining, CarrierPolicy.WorkPhase.CHARGE, 0, 10, -4).size());
        assertTrue(CarrierMapUiPolicy.scannedChunks(CarrierPolicy.Mode.IDLE.ordinal(), CarrierPolicy.WorkPhase.IDLE,
            0, 10, -4).isEmpty());
    }

    @Test void visualStageProgressStaysBounded() {
        assertEquals(.5, CarrierMapUiPolicy.progress(CarrierMapUiPolicy.MiningStage.SCAN, 20, 40));
        assertEquals(1, CarrierMapUiPolicy.progress(CarrierMapUiPolicy.MiningStage.CHARGE, 9, 20));
        assertEquals(0, CarrierMapUiPolicy.progress(CarrierMapUiPolicy.MiningStage.DESCENT, 9, 20));
        assertEquals(1, CarrierMapUiPolicy.progress(CarrierMapUiPolicy.MiningStage.DESCENT, 20, 20));
        assertEquals(1, CarrierMapUiPolicy.progress(CarrierMapUiPolicy.MiningStage.RELEASE, 40, 24));
    }

    private static CarrierMapUiPolicy.MiningStage stage(int mode, CarrierPolicy.WorkPhase phase, int tick, int duration) {
        return CarrierMapUiPolicy.miningStage(mode, phase, tick, duration);
    }
}
