package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class CarrierMiningEffectPolicyTest {
    @Test void scanVisitsExactlyTheThreeByThreeNeighborhoodAndFinishesOnSelection() {
        var visited = new HashSet<CarrierMiningEffectPolicy.ChunkOffset>();
        for (int tick = 0; tick < CarrierPolicy.SCAN_TICKS; tick++) {
            var chunk = CarrierMiningEffectPolicy.scanChunk(tick);
            assertTrue(Math.abs(chunk.x()) <= 1 && Math.abs(chunk.z()) <= 1);
            visited.add(chunk);
        }
        assertEquals(CarrierMiningEffectPolicy.SCAN_CHUNKS, visited.size());
        assertEquals(new CarrierMiningEffectPolicy.ChunkOffset(0, 0),
            CarrierMiningEffectPolicy.scanChunk(CarrierPolicy.SCAN_TICKS - 1));
        assertEquals(new CarrierMiningEffectPolicy.ChunkOffset(-1, -1),
            CarrierMiningEffectPolicy.scanChunk(-20));
    }

	@Test void chargeSequencesSixteenEmittersThenMaturesRedCoreIntoPlasma() {
		int previousEmitters = 0;
		double previousRadius = 0, previousTravel = 0, previousPlasma = 0;
		for (int tick = 0; tick < CarrierPolicy.MINING_CHARGE_TICKS; tick++) {
            var visual = CarrierMiningEffectPolicy.visual(CarrierPolicy.WorkPhase.CHARGE,
                tick, CarrierPolicy.MINING_CHARGE_TICKS);
            assertTrue(visual.activeEmitters() >= previousEmitters);
            assertTrue(visual.sphereRadius() >= previousRadius);
			assertTrue(visual.sphereTravel() >= previousTravel);
			assertEquals(0, visual.beamStrength());
			assertTrue(visual.plasmaProgress() >= previousPlasma);
			assertEquals(Math.min(CarrierMiningEffectPolicy.RING_EMITTERS,
				tick / CarrierMiningEffectPolicy.EMITTER_INTERVAL_TICKS + 1), visual.activeEmitters());
            previousEmitters = visual.activeEmitters();
            previousRadius = visual.sphereRadius();
			previousTravel = visual.sphereTravel();
			previousPlasma = visual.plasmaProgress();
		}
		assertEquals(CarrierMiningEffectPolicy.RING_EMITTERS, previousEmitters);
		assertEquals(1.35, previousRadius, 1e-9);
		assertEquals(.18, previousTravel, 1e-9);
		assertEquals(1, previousPlasma, 1e-9);
		assertEquals(CarrierPolicy.MINING_CHARGE_TICKS,
			CarrierMiningEffectPolicy.EMITTER_SEQUENCE_TICKS + CarrierMiningEffectPolicy.CORE_MATURATION_TICKS);
	}

	@Test void releasedPlasmaSphereAcceleratesDownwardWithoutBecomingAWorkColumn() {
		double previousTravel = -1;
        boolean pulseObserved = false;
        for (int tick = 0; tick < CarrierMiningEffectPolicy.RELEASE_TICKS; tick++) {
            var visual = CarrierMiningEffectPolicy.visual(CarrierPolicy.WorkPhase.FIRE, tick,
                CarrierMiningEffectPolicy.RELEASE_TICKS);
            assertTrue(visual.sphereTravel() >= previousTravel);
			assertEquals(0, visual.beamStrength());
			assertEquals(1, visual.plasmaProgress());
            assertTrue(Double.isFinite(visual.sphereRadius()));
            pulseObserved |= visual.releasePulse() > 0;
            previousTravel = visual.sphereTravel();
        }
        assertTrue(pulseObserved);
        var steady = CarrierMiningEffectPolicy.visual(CarrierPolicy.WorkPhase.FIRE,
            CarrierMiningEffectPolicy.RELEASE_TICKS + 100, CarrierMiningEffectPolicy.RELEASE_TICKS);
        assertEquals(1, steady.sphereTravel());
		assertEquals(0, steady.beamStrength());
        assertEquals(1.05, steady.sphereRadius());
        assertEquals(0, steady.releasePulse());
    }

    @Test void excavationCannotBeginBeforeTheReleaseAnimationCompletes() {
        int releaseStart = CarrierPolicy.SCAN_TICKS + CarrierPolicy.MINING_CHARGE_TICKS;
        assertFalse(CarrierMiningEffectPolicy.releaseComplete(releaseStart));
        assertFalse(CarrierMiningEffectPolicy.releaseComplete(releaseStart
            + CarrierMiningEffectPolicy.RELEASE_TICKS - 1));
        assertTrue(CarrierMiningEffectPolicy.releaseComplete(releaseStart
            + CarrierMiningEffectPolicy.RELEASE_TICKS));
        assertTrue(CarrierMiningEffectPolicy.RING_EMITTER_RADIUS > 3);
		assertTrue(CarrierMiningEffectPolicy.CHARGE_DESCENT > 2);
		assertTrue(CarrierPolicy.MINING_CHARGE_TICKS >= 20 * 8);
	}

	@Test void eachEmitterRampsOnlyAfterItsOwnConnectionTick() {
		for (int emitter = 0; emitter < CarrierMiningEffectPolicy.RING_EMITTERS; emitter++) {
			int activation = emitter * CarrierMiningEffectPolicy.EMITTER_INTERVAL_TICKS;
			assertEquals(0, CarrierMiningEffectPolicy.emitterIntensity(emitter, activation - 1));
			assertTrue(CarrierMiningEffectPolicy.emitterIntensity(emitter, activation) > 0);
			assertEquals(1, CarrierMiningEffectPolicy.emitterIntensity(emitter,
				activation + CarrierMiningEffectPolicy.EMITTER_INTERVAL_TICKS), 1e-9);
		}
	}
}
