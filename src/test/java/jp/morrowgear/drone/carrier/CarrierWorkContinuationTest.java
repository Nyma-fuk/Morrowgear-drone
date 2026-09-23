package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierWorkContinuationTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private CarrierShip ship() {
        var ship = new CarrierShip(UUID.randomUUID(), 0, new CarrierAnchor("minecraft:overworld", 8, 100, 8));
        ship.progress = new CarrierPolicy.Progress(CarrierPolicy.Operation.at(UUID.randomUUID(), "minecraft:overworld",
            8, 8, -64, 98), 0, List.of());
        ship.mode = CarrierPolicy.Mode.MINING;
        return ship;
    }

    @Test void callbackStopPreventsAnotherBreakEvenThoughProgressIsRetained() {
        var ship = ship();
        var progress = ship.progress;
        assertTrue(CarrierWork.miningSnapshot(ship, progress));
        ship.stop(CarrierPolicy.Stop.EMERGENCY);
        assertSame(progress, ship.progress);
        assertFalse(CarrierWork.miningSnapshot(ship, progress));
        assertEquals(CarrierPolicy.Stop.EMERGENCY, ship.stop);
    }

    @Test void callbackReplacementCannotBeOverwrittenByTheOldTick() {
        var ship = ship();
        var original = ship.progress;
        ship.progress = original.advance();
        assertFalse(CarrierWork.miningSnapshot(ship, original));
        ship.progress = new CarrierPolicy.Progress(CarrierPolicy.Operation.at(UUID.randomUUID(), "minecraft:overworld",
            8, 8, -64, 98), 0, List.of());
        assertFalse(CarrierWork.continues(ship, original.operation(), CarrierPolicy.Mode.MINING));
    }

    @Test void destroyedOrSwitchedModeCannotContinueTheCapturedGeneration() {
        var ship = ship();
        var op = ship.progress.operation();
        ship.destroyed = true;
        assertFalse(CarrierWork.continues(ship, op, CarrierPolicy.Mode.MINING));
        ship.destroyed = false;
        ship.mode = CarrierPolicy.Mode.COMBAT;
        ship.activeCombat = op;
        ship.combatBeamTargets = List.of(UUID.randomUUID());
        assertFalse(CarrierWork.continues(ship, op, CarrierPolicy.Mode.MINING));
        assertTrue(CarrierWork.continues(ship, op, CarrierPolicy.Mode.COMBAT));
        ship.stop(CarrierPolicy.Stop.EMERGENCY);
        assertTrue(ship.combatBeamTargets.isEmpty());
        assertFalse(CarrierWork.continues(ship, op, CarrierPolicy.Mode.COMBAT));
    }
}
