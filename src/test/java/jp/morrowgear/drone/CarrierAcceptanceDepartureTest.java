package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CarrierAcceptanceDepartureTest {
    @Test void waitsForOrdinarySupplyAndCommandCooldown() {
        assertEquals(CarrierAcceptanceDeparture.State.WAIT, CarrierAcceptanceDeparture.state(5, 100000));
        assertEquals(CarrierAcceptanceDeparture.State.WAIT, CarrierAcceptanceDeparture.state(40, 1999));
        assertEquals(CarrierAcceptanceDeparture.State.READY, CarrierAcceptanceDeparture.state(40, 2000));
        assertEquals(CarrierAcceptanceDeparture.State.READY, CarrierAcceptanceDeparture.state(119, 2000));
    }
    @Test void chargingHasABoundedDeadlineEvenWithLateFuel() {
        assertEquals(CarrierAcceptanceDeparture.State.TIMEOUT, CarrierAcceptanceDeparture.state(120, 100000));
        assertEquals(CarrierAcceptanceDeparture.State.TIMEOUT, CarrierAcceptanceDeparture.state(-1, 100000));
    }
    @Test void unobservedMovementCannotBeResumedAsContinuousTravel() {
        Vec3 at = new Vec3(544.5, 128.5, 8.5);
        assertTrue(CarrierAcceptanceDeparture.unchanged(at, new Vec3(544.5, 128.5, 8.5)));
        assertFalse(CarrierAcceptanceDeparture.unchanged(at, at.add(.01, 0, 0)));
        assertFalse(CarrierAcceptanceDeparture.unchanged(at, at.add(0, 512, 0)));
        assertFalse(CarrierAcceptanceDeparture.unchanged(at, new Vec3(Double.NaN, 0, 0)));
        assertFalse(CarrierAcceptanceDeparture.unchanged(null, at));
    }
}
