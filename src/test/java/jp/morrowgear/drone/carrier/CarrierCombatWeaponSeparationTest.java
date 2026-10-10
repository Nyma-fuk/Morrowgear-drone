package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CarrierCombatWeaponSeparationTest {
    @Test void bunkerMissileScanningHasNoVerticalTargetLimit() {
        assertTrue(CarrierMissileModulePolicy.targetable(true, true, 0, Integer.MIN_VALUE));
        assertTrue(CarrierMissileModulePolicy.targetable(true, true, 0, Integer.MAX_VALUE));
    }

    @Test void realSurfaceHeightPreventsGroundTargetsFromReceivingBunkerBonus() {
        CarrierPolicy.Operation operation = CarrierPolicy.Operation.at(java.util.UUID.randomUUID(),
            "minecraft:overworld", 8, 8, -64, 80).withSurfaceY(-60);
        int targetY = -54;
        assertFalse(targetY < operation.surfaceY() - 4);
        assertEquals(1, CarrierMissileModulePolicy.assignedMissiles(30, 2.0 / 3.0,
            targetY < operation.surfaceY() - 4));
    }

    @Test void laserAndMissileUseIndependentServerPipelines() throws Exception {
        String work = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/carrier/CarrierWork.java"));
        int laserStart = work.indexOf("private static void tickLaserCombat");
        int missileStart = work.indexOf("private static void tickMissileCombat");
        int salvoStart = work.indexOf("private static void launchHeavySalvo");
        assertTrue(laserStart >= 0 && missileStart > laserStart && salvoStart > missileStart);
        String laser = work.substring(laserStart, missileStart);
        String missile = work.substring(missileStart, salvoStart);
        assertFalse(laser.contains("launchHeavySalvo"), "laser command must not spend physical missiles");
        assertFalse(missile.contains("hurtServer"), "missile command must not apply laser damage");
        assertTrue(missile.contains("launchHeavySalvo"));
    }

    @Test void missileTimelineHasNoLaserChargePhase() {
        int duration = CarrierPolicy.combatDuration(CarrierPolicy.CombatWeapon.MISSILE);
        assertEquals(CarrierPolicy.WorkPhase.SCAN,
            CarrierPolicy.combatPhase(duration, CarrierPolicy.CombatWeapon.MISSILE));
        boolean fireObserved = false;
        for (int remaining = duration; remaining > 0; remaining--) {
            CarrierPolicy.WorkPhase phase = CarrierPolicy.combatPhase(remaining, CarrierPolicy.CombatWeapon.MISSILE);
            assertNotEquals(CarrierPolicy.WorkPhase.CHARGE, phase);
            fireObserved |= phase == CarrierPolicy.WorkPhase.FIRE;
        }
        assertTrue(fireObserved);
        assertEquals(CarrierPolicy.COMBAT_TICKS,
            CarrierPolicy.combatDuration(CarrierPolicy.CombatWeapon.LASER));
    }
}
