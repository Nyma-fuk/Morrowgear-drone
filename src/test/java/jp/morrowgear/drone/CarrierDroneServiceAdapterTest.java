package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import java.util.Optional;
import java.util.UUID;
import jp.morrowgear.drone.carrier.CarrierAnchor;
import jp.morrowgear.drone.carrier.CarrierServiceBay;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CarrierDroneServiceAdapterTest {
    private CarrierDroneServiceAdapter.WorkState state(int blocked) {
        return new CarrierDroneServiceAdapter.WorkState(true,
            (blocked & 1) != 0, (blocked & 2) != 0, (blocked & 4) != 0,
            (blocked & 8) != 0, (blocked & 16) != 0, (blocked & 32) != 0,
            (blocked & 64) != 0, (blocked & 128) != 0, (blocked & 256) != 0, (blocked & 512) != 0);
    }

    @Test void explicitServiceRejectsEveryProtectedWorkState() {
        assertTrue(state(0).available());
        for (int flag = 1; flag <= 512; flag <<= 1) assertFalse(state(flag).available(), "Protected flag " + flag);
        assertFalse(new CarrierDroneServiceAdapter.WorkState(false, false, false, false, false,
            false, false, false, false, false, false).available());
    }

    @Test void transfersCannotExceedOfferOrCapacityEvenForExtremeInputs() {
        assertEquals(3, CarrierDroneServiceAdapter.accepted(100, 997, 1000));
        assertEquals(0, CarrierDroneServiceAdapter.accepted(-1, 0, 1000));
        assertEquals(0, CarrierDroneServiceAdapter.accepted(100, 1001, 1000));
        assertEquals(1000, CarrierDroneServiceAdapter.accepted(Integer.MAX_VALUE, Integer.MIN_VALUE, 1000));
        assertEquals(0, CarrierDroneServiceAdapter.accepted(100, 0, -1));
    }

    @Test void fullFlightServiceHasTickOrderDeadbandWithoutCreatingEnergy() {
        assertEquals(0, CarrierDroneServiceAdapter.flightDemand(999, 1000));
        assertEquals(0, CarrierDroneServiceAdapter.flightDemand(980, 1000));
        assertEquals(21, CarrierDroneServiceAdapter.flightDemand(979, 1000));
        assertEquals(0, CarrierDroneServiceAdapter.flightDemand(2997, 3000));
    }

    private int demand(DroneRole role, SecurityLoadout loadout, int kind) {
        return CarrierDroneServiceAdapter.supplyDemand(role, loadout, kind, 0, 1000);
    }

    @Test void nonCombatRolesNeverWaitForWeaponsOrUnusedAmmunition() {
        for (DroneRole role : DroneRole.values()) if (role != DroneRole.SECURITY) {
            assertEquals(1000, demand(role, SecurityLoadout.AUTO, 0));
            for (int kind = 1; kind <= 3; kind++) assertEquals(0, demand(role, SecurityLoadout.AUTO, kind));
        }
    }

    @Test void laserRequestsWeaponEnergyOnly() {
        assertEquals(1000, demand(DroneRole.SECURITY, SecurityLoadout.LASER, 1));
        assertEquals(0, demand(DroneRole.SECURITY, SecurityLoadout.LASER, 2));
        assertEquals(0, demand(DroneRole.SECURITY, SecurityLoadout.LASER, 3));
    }

    @Test void gunAndMissileLoadoutsOnlyRequestTheirOwnAmmunition() {
        assertEquals(1000, demand(DroneRole.SECURITY, SecurityLoadout.AUTOCANNON, 1));
        assertEquals(1000, demand(DroneRole.SECURITY, SecurityLoadout.AUTOCANNON, 2));
        assertEquals(0, demand(DroneRole.SECURITY, SecurityLoadout.AUTOCANNON, 3));
        assertEquals(1000, demand(DroneRole.SECURITY, SecurityLoadout.MISSILE, 1));
        assertEquals(0, demand(DroneRole.SECURITY, SecurityLoadout.MISSILE, 2));
        assertEquals(1000, demand(DroneRole.SECURITY, SecurityLoadout.MISSILE, 3));
    }

    @Test void autoUsesBothPayloadsAndUnarmedRetainsCommonSecurityEnergyReserve() {
        for (int kind = 1; kind <= 3; kind++) assertEquals(1000, demand(DroneRole.SECURITY, SecurityLoadout.AUTO, kind));
        assertEquals(1000, demand(DroneRole.SECURITY, SecurityLoadout.UNARMED, 1));
        assertEquals(0, demand(DroneRole.SECURITY, SecurityLoadout.UNARMED, 2));
        assertEquals(0, demand(DroneRole.SECURITY, SecurityLoadout.UNARMED, 3));
        assertEquals(DroneServicePolicy.Need.WEAPON_POWER,
            CombatPolicy.weaponServiceNeed(SecurityLoadout.UNARMED, 0, 0, 0, 0));
    }

    @Test void chargingLargerThanCurrentCapacitiesCanContinueBeyondTwoMinutes() {
        assertEquals(3000, BatteryTier.HIGH_DENSITY.capacity());
        assertEquals(2000, PayloadCapacity.energy(PayloadCapacity.MAX_TIER));
        var progress = new CarrierDroneServiceAdapter.Progress(0);
        for (int tick = 0; tick <= 6000; tick += 20)
            assertFalse(progress.timedOut(tick, true, Math.max(0, 12000 - tick * 2)), "tick " + tick);
        assertTrue(progress.timedOut(6601, true, 0));
    }

    @Test void missingStockAndRepeatedTopUpsDoNotRenewFailedServiceForever() {
        var progress = new CarrierDroneServiceAdapter.Progress(0);
        assertFalse(progress.timedOut(0, true, 20));
        assertFalse(progress.timedOut(200, true, 10));
        assertFalse(progress.timedOut(400, true, 12));
        assertFalse(progress.timedOut(600, true, 10));
        assertTrue(progress.timedOut(801, true, 10));
    }

    @Test void blockedApproachAndTimeRollbackExpire() {
        var progress = new CarrierDroneServiceAdapter.Progress(100);
        assertFalse(progress.timedOut(2500, false, 1000));
        assertTrue(progress.timedOut(2501, false, 1000));
        assertTrue(progress.timedOut(99, true, 500));
        assertTrue(CarrierDroneServiceAdapter.expired(100, Long.MIN_VALUE, 40));
        assertFalse(CarrierDroneServiceAdapter.expired(140, 100, 40));
        assertTrue(CarrierDroneServiceAdapter.expired(141, 100, 40));
    }

    @Test void onlyRecognizedRepairMaterialsProduceRepairValue() {
        assertEquals(4, CarrierDroneServiceAdapter.repairValue("minecraft:copper_ingot"));
        assertEquals(8, CarrierDroneServiceAdapter.repairValue("minecraft:iron_ingot"));
        assertEquals(20, CarrierDroneServiceAdapter.repairValue("morrowgear_drone:morrow_alloy"));
        assertEquals(0, CarrierDroneServiceAdapter.repairValue("minecraft:diamond"));
    }

    @Test void savedSessionPreservesCustomWingHomeAndMissionAndRejectsNewAssignments() {
        var home = new CarrierAnchor("minecraft:overworld", -20, 80, 5);
        var identity = new CarrierServiceBay.Identity(UUID.randomUUID(), UUID.randomUUID(),
            Optional.of(home), "CUSTOM_ESCORT_7", "manual-patrol-42");
        var session = new CarrierDroneServiceAdapter.Session(UUID.randomUUID(), identity, 5, "scout", home, 10);
        var encoded = CarrierDroneServiceAdapter.Session.CODEC.encodeStart(JsonOps.INSTANCE, session).getOrThrow();
        assertEquals(session, CarrierDroneServiceAdapter.Session.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertTrue(session.matches(identity, 5, "scout"));
        assertFalse(session.matches(identity, 0, "scout"));
        assertFalse(session.matches(identity, 5, "cargo"));
        assertFalse(session.matches(new CarrierServiceBay.Identity(identity.drone(), identity.owner(),
            Optional.empty(), identity.wing(), identity.mission()), 5, "scout"));
        assertFalse(session.matches(new CarrierServiceBay.Identity(identity.drone(), identity.owner(),
            identity.homeDock(), "ALPHA", identity.mission()), 5, "scout"));
        assertFalse(session.matches(new CarrierServiceBay.Identity(identity.drone(), UUID.randomUUID(),
            identity.homeDock(), identity.wing(), identity.mission()), 5, "scout"));
        assertFalse(session.matches(new CarrierServiceBay.Identity(identity.drone(), identity.owner(),
            identity.homeDock(), identity.wing(), "new-command"), 5, "scout"));
        encoded.getAsJsonObject().addProperty("started", -1);
        assertTrue(CarrierDroneServiceAdapter.Session.CODEC.parse(JsonOps.INSTANCE, encoded).error().isPresent());
    }

    @Test void existingFlightControllerConvergesOnMovingBayWithoutPositionSnaps() {
        Vec3 carrier = Vec3.ZERO, motion = new Vec3(.15, 0, 0);
        Vec3 drone = new Vec3(-20, -2, 0), velocity = Vec3.ZERO;
        for (int tick = 0; tick < 500; tick++) {
            carrier = carrier.add(motion);
            velocity = FlightDynamics.steerMovingOrbit(velocity, drone, carrier, motion, .55);
            assertTrue(velocity.length() <= .55 + 1e-9);
            drone = drone.add(velocity);
        }
        assertTrue(CarrierServiceBay.stable(drone, velocity, carrier, motion));
    }
}
