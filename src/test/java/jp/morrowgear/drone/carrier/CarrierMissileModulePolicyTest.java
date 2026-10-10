package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CarrierMissileModulePolicyTest {
    @Test void targetsAtEveryHeightRequireFreshOwnerDataLink() {
        assertTrue(CarrierMissileModulePolicy.targetable(true, true, 20, Integer.MIN_VALUE));
        assertTrue(CarrierMissileModulePolicy.targetable(true, true, 20, Integer.MAX_VALUE));
        assertFalse(CarrierMissileModulePolicy.targetable(true, false, 20, -4096));
        assertFalse(CarrierMissileModulePolicy.targetable(true, true,
            CarrierMissileModulePolicy.CONTACT_MAX_AGE_TICKS + 1L, 0));
    }

    @Test void threatAndUndergroundDepthIncreaseAssignmentWithinBound() {
        assertEquals(1, CarrierMissileModulePolicy.assignedMissiles(0, .2, false));
        assertEquals(1, CarrierMissileModulePolicy.assignedMissiles(90, .9, true));
        assertEquals(2, CarrierMissileModulePolicy.assignedMissiles(500, 1, false));
        assertTrue(CarrierMissileModulePolicy.assignedMissiles(9999, 1, true)
            <= CarrierMissileModulePolicy.MAX_MISSILES_PER_TARGET);
    }

    @Test void salvoUsesEveryLoadedWarheadAsItsOnlyGlobalLimitAndLocksBroadlyFirst() {
        assertEquals(80, CarrierMissileModulePolicy.MAX_SALVO);
        var assignments=CarrierMissileModulePolicy.salvoAssignments(java.util.List.of(3,2,1),80);
        assertEquals(java.util.List.of(0,1,2,0,1,0),assignments);
        var broad=CarrierMissileModulePolicy.salvoAssignments(
            java.util.Collections.nCopies(CarrierMissileModulePolicy.MAX_PARALLEL_LOCKS,1),
            CarrierMissileModulePolicy.MAX_SALVO);
        assertEquals(CarrierMissileModulePolicy.MAX_SALVO,broad.size());
        assertEquals(CarrierMissileModulePolicy.MAX_SALVO,broad.stream().distinct().count());
        assertEquals(java.util.List.of(0,1),
            CarrierMissileModulePolicy.salvoAssignments(java.util.List.of(8,8,8),2));
    }
}
