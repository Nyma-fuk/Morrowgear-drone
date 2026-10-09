package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CarrierActionEstimatePolicyTest {
    @Test void energyCountdownUsesTheAuthoritativeGenerationRate() {
        assertEquals(0, CarrierActionEstimatePolicy.energyReadyTicks(200, 200, 10));
        assertEquals(10, CarrierActionEstimatePolicy.energyReadyTicks(100, 200, 10));
        assertEquals(1, CarrierActionEstimatePolicy.energyReadyTicks(199, 200, 10));
        assertEquals(-1, CarrierActionEstimatePolicy.energyReadyTicks(0, 200, 0));
    }

    @Test void miningEstimateIncludesPreparationReleaseAndRemainingExcavation() {
        int cells = CarrierPowerPolicy.SUSTAINED_MINING_BLOCKS_PER_TICK * 20;
        assertEquals(CarrierPolicy.SCAN_TICKS + CarrierPolicy.MINING_CHARGE_TICKS
                + CarrierMiningEffectPolicy.RELEASE_TICKS + 20,
            CarrierActionEstimatePolicy.miningRemainingTicks(CarrierPolicy.WorkPhase.SCAN, 0,
                CarrierPolicy.SCAN_TICKS, 0, cells));
        assertEquals(20, CarrierActionEstimatePolicy.miningRemainingTicks(CarrierPolicy.WorkPhase.FIRE,
            CarrierMiningEffectPolicy.RELEASE_TICKS, CarrierMiningEffectPolicy.RELEASE_TICKS, 0, cells));
    }

    @Test void movementEstimateIncludesTravelAndManeuverAllowance() {
        var from = new CarrierAnchor("minecraft:overworld", 0, 100, 0);
        var destination = new CarrierAnchor("minecraft:overworld", 100, 100, 0);
        assertEquals(240, CarrierActionEstimatePolicy.movementRemainingTicks(from, destination));
        assertEquals(-1, CarrierActionEstimatePolicy.movementRemainingTicks(from,
            new CarrierAnchor("minecraft:the_nether", 100, 100, 0)));
    }
}
