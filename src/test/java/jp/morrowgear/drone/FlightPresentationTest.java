package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FlightPresentationTest {
    @Test void laserAndGunsNeverOpenDorsalMissileHatch() {
        for (CombatState state : CombatState.values()) {
            for (CombatWeapon weapon : new CombatWeapon[]{CombatWeapon.LASER, CombatWeapon.AUTOCANNON, CombatWeapon.RAM})
                assertFalse(FlightPresentation.deployEquipment(DroneRole.SECURITY, weapon, state, false, false, true));
        }
        assertTrue(FlightPresentation.deployEquipment(DroneRole.SECURITY, CombatWeapon.MISSILE,
            CombatState.MISSILE_APPROACH, false, false, false));
        assertFalse(FlightPresentation.deployEquipment(DroneRole.SECURITY, CombatWeapon.MISSILE,
            CombatState.MISSILE_APPROACH, true, false, false));
        assertFalse(FlightPresentation.deployEquipment(DroneRole.SECURITY, CombatWeapon.MISSILE,
            CombatState.MISSILE_APPROACH, false, true, false));
    }

    @Test void mechanismsApproachContinuouslyWithoutOvershooting() {
        float value = 0;
        for (int i = 0; i < 200; i++) {
            float next = FlightPresentation.approach(value, 1, .25, 5, .1);
            assertTrue(next >= value && next <= 1);
            assertTrue(next - value <= .025001);
            value = next;
        }
        assertEquals(1, value, .001);
        float reverse = FlightPresentation.approach(value, 0, .25, 5, .1);
        assertTrue(reverse < value && reverse > .95);
    }

    @Test void smoothResponseDoesNotDependOnFrameRateOrPause() {
        float at20 = 0, at120 = 0;
        for (int i = 0; i < 20; i++) at20 = FlightPresentation.approach(at20, .2f, 1, 4, .1);
        for (int i = 0; i < 120; i++) at120 = FlightPresentation.approach(at120, .2f, 1.0 / 6, 4, .1);
        assertEquals(at20, at120, .00001);
        assertEquals(at20, FlightPresentation.approach(at20, 1, 0, 4, .1));
        assertTrue(Float.isFinite(FlightPresentation.approach(Float.NaN, .2f, 1, 4, .1)));
    }

    @Test void emitterMatchesMasterLensNotOldRearOffset() {
        assertEquals(.40503, DroneHardpoints.LASER.z, .00001);
        assertEquals(.105, DroneHardpoints.LASER.y, .00001);
        assertTrue(DroneHardpoints.GUN_LEFT.z > .93);
        assertEquals(DroneHardpoints.GUN_LEFT.x, -DroneHardpoints.GUN_RIGHT.x, .000001);
        assertTrue(DroneHardpoints.EXHAUST_LEFT.z < -1);
    }

    @Test void hardpointsRespectYawPitchRollAndKeepRigidDistances() {
        Vec3 nose = DroneHardpoints.rotate(new Vec3(0, 0, 1), 90, 0, 0);
        assertEquals(-1, nose.x, 1e-6);
        assertEquals(0, nose.z, 1e-6);
        assertTrue(DroneHardpoints.rotate(new Vec3(0, 0, 1), 0, .2f, 0).y < 0);
        Vec3 lift = DroneHardpoints.rotate(new Vec3(0, 1, 0), 0, 0, .2f);
        assertTrue(lift.x < 0, "positive yaw turn banks lift toward -X");
        for (int yaw = -180; yaw <= 180; yaw += 15)
            assertEquals(DroneHardpoints.LASER.length(), DroneHardpoints.rotate(DroneHardpoints.LASER, yaw, .14f, -.22f).length(), 1e-8);
    }
}
