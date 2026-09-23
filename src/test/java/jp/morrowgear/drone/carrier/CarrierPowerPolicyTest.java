package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierPowerPolicyTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void reactorRecoversBothStoresWithoutCellsAndNeverOverfills() {
        CarrierShip ship = ship();
        assertTrue(CarrierPowerPolicy.regenerate(ship));
        assertEquals(CarrierPowerPolicy.FLIGHT_GENERATION_PER_TICK, ship.energy);
        assertEquals(CarrierPowerPolicy.WORK_GENERATION_PER_TICK, ship.weaponEnergy);
        ship.energy = CarrierPolicy.MAX_ENERGY - 1;
        ship.weaponEnergy = CarrierPolicy.MAX_ENERGY;
        assertTrue(CarrierPowerPolicy.regenerate(ship));
        assertEquals(CarrierPolicy.MAX_ENERGY, ship.energy);
        assertEquals(CarrierPolicy.MAX_ENERGY, ship.weaponEnergy);
        assertFalse(CarrierPowerPolicy.regenerate(ship));
    }

    @Test void observedConsumptionAccountsForTheSameTickReactorOutput() {
        assertEquals(0, CarrierPowerPolicy.observedWorkConsumption(1_000, 1_010));
        assertEquals(2, CarrierPowerPolicy.observedWorkConsumption(1_000, 1_008));
        assertEquals(40, CarrierPowerPolicy.observedWorkConsumption(1_000, 970));
        assertEquals(40, CarrierPowerPolicy.observedWorkConsumption(CarrierPolicy.MAX_ENERGY,
            CarrierPolicy.MAX_ENERGY - 40));
    }

    @Test void ordinaryCruiseIsFreeButHighOutputAccelerationUsesFlightStorage() {
        Vec3 lowCruise = new Vec3(CarrierPowerPolicy.LOW_POWER_SPEED, 0, 0);
        Vec3 ordinaryCruise = new Vec3(CarrierNavigation.SPEED, 0, 0);
        assertEquals(0, CarrierPowerPolicy.boostCost(lowCruise, lowCruise));
        assertEquals(0, CarrierPowerPolicy.boostCost(ordinaryCruise, ordinaryCruise));
        assertTrue(CarrierPowerPolicy.boostCost(lowCruise, lowCruise.add(.015, 0, 0)) > 0);
        assertEquals(CarrierPowerPolicy.LOW_POWER_SPEED,
            CarrierPowerPolicy.flightSpeed(CarrierPowerPolicy.BOOST_ENABLE_RESERVE - 1));
        assertEquals(CarrierNavigation.SPEED,
            CarrierPowerPolicy.flightSpeed(CarrierPowerPolicy.BOOST_ENABLE_RESERVE));
    }

    @Test void emptyCarrierStillMovesAndReactorEventuallyRestoresFullSpeedAuthority() {
        CarrierShip ship = ship();
        Vec3 position = Vec3.ZERO, velocity = Vec3.ZERO, target = new Vec3(200, 0, 0);
        float yaw = -90;
        boolean boosted = false;
        for (int tick = 0; tick < 500; tick++) {
            CarrierPowerPolicy.regenerate(ship);
            double limit = CarrierPowerPolicy.flightSpeed(ship.energy);
            var motion = CarrierNavigation.advance(position, target, yaw, velocity, limit);
            int cost = CarrierPowerPolicy.boostCost(velocity, motion.velocity());
            ship.energy = Math.max(0, ship.energy - cost);
            position = position.add(motion.velocity());
            velocity = motion.velocity();
            yaw = motion.yaw();
            boosted |= velocity.length() > CarrierPowerPolicy.LOW_POWER_SPEED + 1.0e-6;
        }
        assertTrue(position.x > 0, "zero storage must not immobilize the carrier");
        assertTrue(boosted, "reactor recovery must restore high-output acceleration without an item");
        assertTrue(ship.energy >= 0);
    }

    @Test void workStorageWaitsWithoutGoingNegativeAndCanResumeFromReactorOutput() {
        CarrierShip ship = ship();
        assertFalse(CarrierPowerPolicy.consumeWork(ship, 10));
        CarrierPowerPolicy.regenerate(ship);
        assertTrue(CarrierPowerPolicy.consumeWork(ship, 10));
        assertEquals(0, ship.weaponEnergy);
    }

    @Test void childServiceCannotConsumeTheCarriersOperationalReserve() {
        assertEquals(0, CarrierPowerPolicy.serviceOffer(
            CarrierPowerPolicy.FLIGHT_SERVICE_RESERVE, 40, false));
        assertEquals(12, CarrierPowerPolicy.serviceOffer(
            CarrierPowerPolicy.FLIGHT_SERVICE_RESERVE + 12, 40, false));
        assertEquals(40, CarrierPowerPolicy.serviceOffer(
            CarrierPowerPolicy.WORK_SERVICE_RESERVE + 100, 80, true));
        assertEquals(7, CarrierPowerPolicy.serviceOffer(
            CarrierPowerPolicy.WORK_SERVICE_RESERVE + 7, 80, true));
    }

    @Test void reactorOutputSustainsFourServiceBaysWithoutNetFlightDrain() {
        int generatedPerSecond = CarrierPowerPolicy.FLIGHT_GENERATION_PER_TICK * 20;
        int maximumBayTransferPerSecond = CarrierPolicy.BAY_SLOTS * 40;
        assertTrue(generatedPerSecond >= maximumBayTransferPerSecond);
    }

    @Test void sustainedMiningHasAReactorFundedFloorAndCapacitorFundedPeak() {
        for (int broken = 0; broken < CarrierPolicy.BREAK_PER_TICK; broken++)
            assertEquals(broken < CarrierPowerPolicy.SUSTAINED_MINING_BLOCKS_PER_TICK,
                CarrierPowerPolicy.sustainedMiningSlot(broken));
        assertEquals(8, CarrierPowerPolicy.SUSTAINED_MINING_BLOCKS_PER_TICK);
        assertTrue(CarrierPowerPolicy.SUSTAINED_MINING_BLOCKS_PER_TICK < CarrierPolicy.BREAK_PER_TICK);
        assertTrue(CarrierPowerPolicy.MINING_BURST_ENERGY_PER_BLOCK > 0);
    }

    @Test void fullDepthOverworldChunkFitsTheDocumentedElevenMinuteSolidBlockBudget() {
        var operation = CarrierPolicy.Operation.at(UUID.randomUUID(), "minecraft:overworld",
            8, 8, -64, 319);
        int ticks = CarrierPowerPolicy.miningTickBudget(operation);
        assertEquals(98_304, operation.volume());
        assertEquals(CarrierPolicy.SCAN_TICKS + CarrierPolicy.MINING_CHARGE_TICKS
            + operation.volume() / CarrierPowerPolicy.SUSTAINED_MINING_BLOCKS_PER_TICK, ticks);
        assertTrue(ticks <= 11 * 60 * 20, "full-depth budget exceeded: " + ticks + " ticks");
        assertTrue(ticks > operation.volume() / CarrierPolicy.BREAK_PER_TICK,
            "the upper bound must use sustained output, not assume permanent peak burst");
    }

    private static CarrierShip ship() {
        return new CarrierShip(UUID.randomUUID(), 0, new CarrierAnchor("minecraft:overworld", 0, 120, 0));
    }
}
