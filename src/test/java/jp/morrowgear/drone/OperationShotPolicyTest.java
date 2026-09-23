package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OperationShotPolicyTest {
    @Test void gunRunWithoutConfirmedShotIsSilent() {
        assertTrue(OperationShotPolicy.confirmed(CombatWeapon.AUTOCANNON, CombatState.GUN_RUN, 100, -1000, 90).isEmpty());
    }
    @Test void missileApproachWithoutSuccessfulLaunchIsSilent() {
        assertTrue(OperationShotPolicy.confirmed(CombatWeapon.MISSILE, CombatState.MISSILE_APPROACH, 100, -1000, 90).isEmpty());
    }
    @Test void staleShotFromPreviousWeaponPassCannotConfirmNewPass() {
        assertTrue(OperationShotPolicy.confirmed(CombatWeapon.AUTOCANNON, CombatState.GUN_RUN, 100, 80, 90).isEmpty());
        assertTrue(OperationShotPolicy.confirmed(CombatWeapon.MISSILE, CombatState.MISSILE_APPROACH, 100, 80, 90).isEmpty());
    }
    @Test void mismatchedWeaponAndStateCannotConfirmShot() {
        assertTrue(OperationShotPolicy.confirmed(CombatWeapon.LASER, CombatState.GUN_RUN, 100, 99, 90).isEmpty());
        assertTrue(OperationShotPolicy.confirmed(CombatWeapon.AUTOCANNON, CombatState.MISSILE_EGRESS, 100, 99, 90).isEmpty());
    }
    @Test void executedGunShotAndSuccessfulMissileLaunchProduceCorrectCue() {
        assertEquals(OperationEvent.Kind.GUN_STARTED,
                OperationShotPolicy.confirmed(CombatWeapon.AUTOCANNON, CombatState.GUN_RUN, 100, 99, 90).orElseThrow());
        assertEquals(OperationEvent.Kind.MISSILE_STARTED,
                OperationShotPolicy.confirmed(CombatWeapon.MISSILE, CombatState.MISSILE_EGRESS, 100, 99, 99).orElseThrow());
    }
    @Test void firingEvidenceRemainsValidThroughOneSecondGroupingButExpiresAtEightSeconds() {
        assertTrue(OperationShotPolicy.confirmed(CombatWeapon.MISSILE, CombatState.MISSILE_EGRESS, 130, 100, 100).isPresent());
        assertTrue(OperationShotPolicy.confirmed(CombatWeapon.MISSILE, CombatState.MISSILE_EGRESS, 260, 100, 100).isEmpty());
        assertTrue(OperationShotPolicy.confirmed(CombatWeapon.MISSILE, CombatState.MISSILE_EGRESS, 90, 100, 100).isEmpty());
    }
    @Test void laserChargeDoesNotClaimExecutedBeam() {
        assertTrue(OperationShotPolicy.confirmed(CombatWeapon.LASER, CombatState.LASER_CHARGE, 100, 99, 90).isEmpty());
        assertEquals(OperationEvent.Kind.LASER_STARTED,
                OperationShotPolicy.confirmed(CombatWeapon.LASER, CombatState.LASER_FIRE, 100, 99, 90).orElseThrow());
    }
}
