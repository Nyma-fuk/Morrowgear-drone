package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import static jp.morrowgear.drone.CarrierAcceptancePolicy.*;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CarrierAcceptancePolicyTest {
    @Test void completedCombatReturnIsAStableRestartCheckpointWithoutAdmittingActiveStages() {
        for (String stable : List.of("FULL_WAIT", "MINED", "EXTRACTED", "COMBAT_DONE", "COMBAT_RETURNED", "TRAVEL_DONE", "PAUSED"))
            assertTrue(stableRestartCheckpoint(stable), stable);
        for (String active : List.of("COMBAT_TRAVEL_OUT", "COMBAT", "COMBAT_TRAVEL_BACK", "TRAVEL_OUT", "MINING", "RESTART_PREPARED", "FAILED", ""))
            assertFalse(stableRestartCheckpoint(active), active);
        assertFalse(stableRestartCheckpoint(null));
    }

    @Test void fixtureCandidatesAreDeterministicBoundedAndNeverReuseTheCarrierChunk() {
        List<ChunkCandidate> first = fixtureChunkCandidates(12, -7);
        assertEquals(first, fixtureChunkCandidates(12, -7));
        assertEquals(16, first.size());
        assertEquals(new ChunkCandidate(14, -7), first.get(0));
        assertEquals(16, new HashSet<>(first).size());
        for (ChunkCandidate candidate : first) {
            int dx = candidate.x() - 12, dz = candidate.z() + 7;
            assertTrue(Math.max(Math.abs(dx), Math.abs(dz)) >= 2);
            assertTrue(Math.max(Math.abs(dx), Math.abs(dz)) <= 3);
            assertFalse(dx == 0 && dz == 0);
        }
    }

    @Test void warehouseCandidatesSupportNormalEntryWithoutTeleportingTheOwner() {
        List<CabinCell> candidates = warehouseCandidates(2.5, 2.5);
        assertTrue(candidates.size() >= 10);
        assertEquals(candidates, warehouseCandidates(2.5, 2.5));
        assertEquals(candidates.size(), new HashSet<>(candidates).size());
        for (CabinCell cell : candidates) {
            assertTrue(normalEntryReach(cell));
            assertTrue(cell.x() >= 4 && cell.x() <= 11);
            assertTrue(cell.z() >= 4 && cell.z() <= 11);
            assertNotEquals(new CabinCell(6, 6), cell, "legacy cabin chest stays untouched");
        }
        assertEquals(List.of(new CabinCell(4, 4), new CabinCell(5, 4), new CabinCell(4, 5)), candidates.subList(0, 3));
    }

    @Test void fixtureEnumeratesEveryColumnAndLayerExactlyOnce() {
        var positions = new HashSet<String>();
        int[] columnCounts = new int[COLUMNS];
        int bedrock = 0, mineable = 0;
        for (int index = 0; index < FIXTURE_BLOCKS; index++) {
            int x = x(index), y = y(index), z = z(index);
            assertTrue(fixture(x, y, z));
            assertTrue(positions.add(x + ":" + y + ":" + z));
            columnCounts[x + z * WIDTH]++;
            if (mine(x, y, z)) mineable++; else bedrock++;
        }
        assertEquals(16_384, mineable);
        assertEquals(256, bedrock);
        for (int count : columnCounts) assertEquals(65, count);
        assertFalse(fixture(x(FIXTURE_BLOCKS), y(FIXTURE_BLOCKS), z(FIXTURE_BLOCKS)));
    }

    @Test void surroundingComparisonVolumeIncludesAllSixFaces() {
        var positions = new HashSet<String>();
        int inside = 0, shell = 0;
        for (int index = 0; index < BOX_VOLUME; index++) {
            int x = index % BOX_WIDTH - 1;
            int y = index / (BOX_WIDTH * BOX_WIDTH) - 1;
            int z = index / BOX_WIDTH % BOX_WIDTH - 1;
            assertTrue(positions.add(x + ":" + y + ":" + z));
            if (fixture(x, y, z)) inside++; else shell++;
        }
        assertEquals(FIXTURE_BLOCKS, inside);
        assertEquals(SHELL_BLOCKS, shell);
        assertTrue(positions.contains("-1:0:0"));
        assertTrue(positions.contains("16:0:0"));
        assertTrue(positions.contains("0:-1:0"));
        assertTrue(positions.contains("0:65:0"));
        assertTrue(positions.contains("0:0:-1"));
        assertTrue(positions.contains("0:0:16"));
    }

    @Test void fixtureBoundsRejectAdjacentColumnsAndBedrockIsNotMineable() {
        for (int outside : new int[] {-1, 16, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertFalse(fixture(outside, 1, 0));
            assertFalse(fixture(0, 1, outside));
        }
        assertFalse(fixture(0, -1, 0));
        assertFalse(fixture(0, 65, 0));
        assertFalse(mine(0, 0, 0));
        assertTrue(mine(15, 64, 15));
    }

    @Test void mineLedgerIndexCoversEveryMineableCoordinateExactlyOnce() {
        var indices = new HashSet<Integer>();
        for (int y = 1; y <= LAYERS; y++) for (int z = 0; z < WIDTH; z++) for (int x = 0; x < WIDTH; x++)
            assertTrue(indices.add(mineIndex(x, y, z)));
        assertEquals(MINE_BLOCKS, indices.size());
        assertEquals(0, mineIndex(0, 1, 0));
        assertEquals(MINE_BLOCKS - 1, mineIndex(15, 64, 15));
        assertEquals(256, mineWordCount());
        assertThrows(IllegalArgumentException.class, () -> mineIndex(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> mineIndex(16, 1, 0));
    }

    @Test void materialLayersMatchTheDeclaredMixture() {
        int[] layers = new int[8];
        for (int layer = 0; layer <= LAYERS; layer++) layers[material(layer)]++;
        assertArrayEquals(new int[] {1, 35, 24, 1, 1, 1, 1, 1}, layers);
        assertThrows(IllegalArgumentException.class, () -> material(-1));
        assertThrows(IllegalArgumentException.class, () -> material(65));
    }

    @Test void restartRequiresTwoKnownDistinctBoots() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertTrue(restartIsEvidence(first, second));
        assertFalse(restartIsEvidence(first, first));
        assertFalse(restartIsEvidence(null, second));
        assertFalse(restartIsEvidence(first, null));
    }

    @Test void flightEvidenceRejectsTeleportsAndInvalidMeasurements() {
        assertTrue(TRAVEL_DISTANCE >= 512);
        assertTrue(continuousStep(0));
        assertTrue(continuousStep(.25));
        assertTrue(continuousStep(1));
        for (double invalid : new double[] {-1, Math.nextUp(1.0), 512 * 512, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) assertFalse(continuousStep(invalid));
        assertTrue(WORLD_BUDGET > 0 && WORLD_BUDGET <= 256);
        // Three cargo clicks plus four targeted three-click warehouse transfers fit this bound.
        assertTrue(CLICK_BUDGET >= 13 && CLICK_BUDGET <= 16);
    }

    @Test void flightEnergyEvidenceCountsDebitsButNotRefillsOrStableReadings() {
        assertEquals(1, observedEnergySpend(2000, 1999));
        assertEquals(500, observedEnergySpend(2000, 1500));
        assertEquals(0, observedEnergySpend(2000, 2000));
        assertEquals(0, observedEnergySpend(2000, 3000));
        assertThrows(IllegalArgumentException.class, () -> observedEnergySpend(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> observedEnergySpend(0, -1));
    }
}
