package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierRuntimeVerificationTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void failureStopsSavedMiningWithoutRequiringALoadedOrStillTaggedEntity() {
        var owner = java.util.UUID.randomUUID();
        var ship = new jp.morrowgear.drone.carrier.CarrierShip(owner, 0,
            new jp.morrowgear.drone.carrier.CarrierAnchor("minecraft:overworld", 8, 100, 8));
        var op = jp.morrowgear.drone.carrier.CarrierPolicy.Operation.at(java.util.UUID.randomUUID(), "minecraft:overworld", 8, 8, -64, 98);
        ship.progress = new jp.morrowgear.drone.carrier.CarrierPolicy.Progress(op, 4, java.util.List.of());
        var progress = ship.progress;
        ship.mode = jp.morrowgear.drone.carrier.CarrierPolicy.Mode.MINING;
        assertFalse(CarrierRuntimeVerification.stopRecordedShip(ship, java.util.UUID.randomUUID()));
        assertEquals(jp.morrowgear.drone.carrier.CarrierPolicy.Mode.MINING, ship.mode);
        assertTrue(CarrierRuntimeVerification.stopRecordedShip(ship, owner));
        assertEquals(jp.morrowgear.drone.carrier.CarrierPolicy.Mode.IDLE, ship.mode);
        assertEquals(jp.morrowgear.drone.carrier.CarrierPolicy.Stop.EMERGENCY, ship.stop);
        assertSame(progress, ship.progress);
        assertFalse(CarrierRuntimeVerification.stopRecordedShip(null, owner));
    }

    @Test void missingFixtureTargetClearsOnlyAfterItsExpectedChunkIsLoaded() {
        assertFalse(CarrierRuntimeVerification.absentFixtureEntityMayBeCleared(false, false));
        assertFalse(CarrierRuntimeVerification.absentFixtureEntityMayBeCleared(true, true));
        assertTrue(CarrierRuntimeVerification.absentFixtureEntityMayBeCleared(true, false));
    }

    @Test void unrelatedLoadedDronesDoNotBlockABoundedFixture() {
        assertTrue(CarrierRuntimeVerification.droneConflictsWithFixture(new net.minecraft.core.BlockPos(64, 200, -64), 0, 0));
        assertTrue(CarrierRuntimeVerification.droneConflictsWithFixture(new net.minecraft.core.BlockPos(-64, -60, 64), 0, 0));
        assertFalse(CarrierRuntimeVerification.droneConflictsWithFixture(new net.minecraft.core.BlockPos(65, 70, 0), 0, 0));
        assertFalse(CarrierRuntimeVerification.droneConflictsWithFixture(new net.minecraft.core.BlockPos(0, 70, -65), 0, 0));
        assertFalse(CarrierRuntimeVerification.droneConflictsWithFixture(new net.minecraft.core.BlockPos(20_000, 70, 20_000), 0, 0));
    }
    @Test void exactDedicatedNamePermitsOneCreativeSingleplayer() {
        assertTrue(CarrierRuntimeVerification.allowedWorld("MG Carrier V27 Verification", true, true, 1));
    }

    @Test void ordinaryOrFormerWorldNamesAreNeverAccepted() {
        for (String name : new String[] {"New World", "Morrowgear Carrier Verification V27", "Morrowgear Carrier Verification V", "", "null"})
            assertFalse(CarrierRuntimeVerification.allowedWorld(name, true, true, 1));
        assertFalse(CarrierRuntimeVerification.allowedWorld(null, true, true, 1));
    }

    @Test void namePrefixesSuffixesWhitespaceAndCaseCannotAuthorizeDestruction() {
        for (String name : new String[] {"MG Carrier V27 Verification copy", " MG Carrier V27 Verification",
            "MG Carrier V27 Verification ", "mg carrier v27 verification", "MG Carrier V27"})
            assertFalse(CarrierRuntimeVerification.allowedWorld(name, true, true, 1));
    }

    @Test void multiplayerOrNonCreativeAlwaysRefused() {
        String name = CarrierRuntimeVerification.WORLD_NAME;
        assertFalse(CarrierRuntimeVerification.allowedWorld(name, false, true, 1));
        assertFalse(CarrierRuntimeVerification.allowedWorld(name, true, false, 1));
        assertFalse(CarrierRuntimeVerification.allowedWorld(name, false, false, 1));
    }

    @Test void playerCountMustBeExactlyOne() {
        for (int players : new int[] {-1, 0, 2, 4, Integer.MAX_VALUE})
            assertFalse(CarrierRuntimeVerification.allowedWorld(CarrierRuntimeVerification.WORLD_NAME, true, true, players));
    }
}
